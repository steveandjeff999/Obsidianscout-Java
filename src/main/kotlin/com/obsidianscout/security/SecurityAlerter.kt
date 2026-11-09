package com.obsidianscout.security

import com.obsidianscout.auth.EmailService
import com.obsidianscout.auth.UserRole
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.PushNotificationService
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import com.obsidianscout.integrations.SettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Tells people about security events without flooding them. Bots hit every public server all day, so by default
 * superadmins get:
 * - the **daily summary** (email and push);
 * - **one** notification when blocks surge (security.surge_block_threshold blocks within surge_window_minutes),
 *   then nothing more about the surge for surge_cooldown_minutes;
 * - each **unblock request**, since a person is waiting on an answer (security.alert_unblock_requests).
 *
 * Per-event alerts for high-severity events and single blocks only go out when security.instant_alerts is on,
 * one per event type and IP (or account) per cooldown across the cluster. A team's admins still get a push when
 * their own team's accounts or data are targeted.
 */
object SecurityAlerter {
    private val log = org.slf4j.LoggerFactory.getLogger(SecurityAlerter::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var digestJob: Job? = null

    /** Tests capture alerts here instead of sending them. */
    @Volatile var sink: ((title: String, body: String, event: SecurityEvent) -> Unit)? = null

    private val teamTargetedTypes = setOf(
        SecurityEventType.PASSWORD_SPRAY, SecurityEventType.CROSS_TEAM_ACCESS, SecurityEventType.ACCOUNT_PROTECTED,
        SecurityEventType.UNBLOCK_REQUESTED, SecurityEventType.SESSIONS_REVOKED
    )

    fun title(event: SecurityEvent): String = when (event.type) {
        SecurityEventType.PASSWORD_SPRAY -> "Password spray detected"
        SecurityEventType.CREDENTIAL_STUFFING -> "Credential stuffing under way"
        SecurityEventType.CROSS_TEAM_ACCESS -> "Attempt to read another team's data"
        SecurityEventType.CLUSTER_SIG_INVALID -> "Forged cluster request"
        SecurityEventType.SESSION_ANOMALY -> "Session may be stolen"
        SecurityEventType.TOKEN_REUSE_REVOKED -> "Revoked session used again"
        SecurityEventType.BLOCK_APPLIED -> "IP blocked"
        SecurityEventType.WOULD_BLOCK -> "IP would have been blocked"
        SecurityEventType.BLOCK_SKIPPED -> "Threat from a protected network"
        SecurityEventType.UNBLOCK_REQUESTED -> "Unblock request waiting"
        SecurityEventType.SESSIONS_REVOKED -> "Sessions signed out"
        SecurityEventType.ACCOUNT_PROTECTED -> "Account protected"
        else -> event.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun body(event: SecurityEvent): String = buildString {
        append(event.details.ifBlank { event.type.name })
        append(" | IP ").append(event.ip)
        event.username?.let { append(" | ").append(it) }
        event.teamNumber?.let { append(" (team ").append(it).append(')') }
    }

    private val blockTypes = setOf(SecurityEventType.BLOCK_APPLIED, SecurityEventType.WOULD_BLOCK)

    fun onEvent(event: SecurityEvent) {
        if (event.type in blockTypes) trackSurge(event)
        val config = SecuritySettings.config
        val alertsSuperadmins = when (event.type) {
            SecurityEventType.UNBLOCK_REQUESTED -> config.alert_unblock_requests
            else -> config.instant_alerts && event.type.alerts
        }
        val alertsTeam = event.type in teamTargetedTypes && event.teamNumber != null && event.teamNumber > 0
        if (!alertsSuperadmins && !alertsTeam) return
        val cooldownMs = SecuritySettings.config.alert_cooldown_minutes.coerceAtLeast(1) * 60_000L
        val subject = event.username?.lowercase() ?: event.ip
        // Unblock requests are each worth a notification; everything else is rate-limited per type and subject.
        if (event.type != SecurityEventType.UNBLOCK_REQUESTED &&
            !SecurityStore.claimOnce("alert:${event.type}:$subject", cooldownMs, event.at)) return
        val title = title(event)
        val body = body(event)
        sink?.let { it(title, body, event); return }
        scope.launch {
            runCatching { if (alertsSuperadmins) notifySuperadmins(title, body, event) }
                .onFailure { log.warn("[SecurityAlerter] Superadmin alert failed: {}", it.message) }
            runCatching { if (alertsTeam) notifyTeamAdmins(event.teamNumber!!, title, event) }
                .onFailure { log.warn("[SecurityAlerter] Team alert failed: {}", it.message) }
        }
    }

    /**
     * Counts blocks across the cluster in a sliding window. The block that takes the count to the threshold sends one
     * notification for the whole surge; the cooldown keeps a long attack from sending another every few minutes.
     */
    private fun trackSurge(event: SecurityEvent) {
        val config = SecuritySettings.config
        val windowMs = config.surge_window_minutes.coerceAtLeast(1) * 60_000L
        val threshold = config.surge_block_threshold.coerceAtLeast(2)
        val count = SecurityStore.addDistinct("surge-blocks", event.ip, windowMs, event.at)
        if (count < threshold) return
        if (!SecurityStore.claimOnce("surge-alert", config.surge_cooldown_minutes.coerceAtLeast(1) * 60_000L, event.at)) return
        val observing = event.type == SecurityEventType.WOULD_BLOCK
        val title = if (observing) "Many addresses would be blocked" else "Surge of blocked addresses"
        val body = "$count addresses ${if (observing) "would have been" else "were"} blocked in the last " +
            "${config.surge_window_minutes} minutes. Nothing more will be sent about it for " +
            "${config.surge_cooldown_minutes} minutes; see Server Insights for details."
        val surge = event.copy(type = SecurityEventType.BLOCK_APPLIED, details = body, username = null, teamNumber = null)
        sink?.let { it(title, body, surge); return }
        scope.launch {
            runCatching {
                pushSuperadmins("Security: $title", body, "security-surge")
                if (config.alert_email) emailSuperadmins("[SECURITY] $title", listOf(
                    "What" to body,
                    "Most recent" to event.details,
                    "Mode" to SecuritySettings.runtime().mode
                ))
            }.onFailure { log.warn("[SecurityAlerter] Surge alert failed: {}", it.message) }
        }
    }

    private fun pushSuperadmins(title: String, body: String, tag: String) {
        if (!SecuritySettings.config.alert_push) return
        userIds(UserRole.SUPERADMIN).forEach { id ->
            PushNotificationService.sendSystemNotification(id, title, body, "/server-insights#security", tag, "Security")
        }
    }

    private fun userIds(role: UserRole, teamNumber: Int? = null): List<UUID> {
        if (!DatabaseFactory.isReady) return emptyList()
        return readTransaction {
            val roles = if (role == UserRole.ADMIN) listOf(UserRole.ADMIN.name) else listOf(role.name)
            Users.select(Users.id).where {
                val byRole = Users.role inList roles
                if (teamNumber != null) byRole and (Users.teamNumber eq teamNumber) else byRole
            }.map { it[Users.id].value }
        }
    }

    private fun notifySuperadmins(title: String, body: String, event: SecurityEvent) {
        val config = SecuritySettings.config
        pushSuperadmins("Security: $title", body, "security-${event.type.name.lowercase()}")
        if (config.alert_email && (event.type.severity == Severity.HIGH || event.type == SecurityEventType.BLOCK_APPLIED ||
                event.type == SecurityEventType.UNBLOCK_REQUESTED || event.type == SecurityEventType.SESSIONS_REVOKED)) {
            emailSuperadmins("[SECURITY] $title", listOf(
                "What" to title, "Details" to event.details, "IP" to event.ip,
                "Account" to listOfNotNull(event.username, event.teamNumber?.let { "team $it" }).joinToString(", "),
                "Request" to "${event.method} ${event.path}".trim()
            ))
        }
    }

    private fun notifyTeamAdmins(teamNumber: Int, title: String, event: SecurityEvent) {
        if (!SecuritySettings.config.alert_push) return
        // Less detail than superadmins get: nothing about other teams or addresses.
        val body = when (event.type) {
            SecurityEventType.UNBLOCK_REQUESTED -> "Someone from your team asked for their network to be unblocked. A superadmin will review it."
            SecurityEventType.CROSS_TEAM_ACCESS -> "A member of your team tried to open another team's data."
            else -> "Unusual sign-in activity on ${event.username ?: "an account"} in your team. Superadmins have been told."
        }
        userIds(UserRole.ADMIN, teamNumber).forEach { id ->
            PushNotificationService.sendSystemNotification(id, "Security: $title", body, "/users", "security-team", "Security")
        }
    }

    private fun smtpReady(): Boolean = runCatching { SettingsService.getSmtpSettings().host.isNotBlank() }.getOrDefault(false)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun emailSuperadmins(subject: String, rows: List<Pair<String, String>>, intro: String? = null) {
        if (!smtpReady()) return
        val recipients = com.obsidianscout.admin.ServerErrorAlertService.getEnrolledSuperadminEmails()
        if (recipients.isEmpty()) return
        val site = runCatching { com.obsidianscout.config.AppConfigLoader.load().getEffectiveSiteUrl() }.getOrDefault("")
        val html = buildString {
            append("<div style=\"font-family:system-ui,sans-serif;max-width:640px\">")
            append("<h2 style=\"margin:0 0 12px\">").append(esc(subject.removePrefix("[SECURITY] "))).append("</h2>")
            intro?.let { append("<p>").append(esc(it)).append("</p>") }
            append("<table style=\"border-collapse:collapse;width:100%\">")
            rows.filter { it.second.isNotBlank() }.forEach { (k, v) ->
                append("<tr><td style=\"padding:6px 10px;color:#666;vertical-align:top\">").append(esc(k))
                append("</td><td style=\"padding:6px 10px\">").append(esc(v).replace("\n", "<br>")).append("</td></tr>")
            }
            append("</table>")
            if (site.isNotBlank()) append("<p><a href=\"").append(esc(site)).append("/server-insights#security\">Open Server Insights</a></p>")
            append("</div>")
        }
        recipients.forEach { to -> runCatching { EmailService.sendEmail(to, subject, html) } }
    }

    /** A plain message to one person (the result of their unblock request, a forced sign-out). */
    fun emailUser(to: String, subject: String, text: String) {
        if (sink != null) return
        scope.launch {
            if (!smtpReady()) return@launch
            val html = "<div style=\"font-family:system-ui,sans-serif;max-width:640px\"><p>${esc(text).replace("\n", "<br>")}</p></div>"
            runCatching { EmailService.sendEmail(to, subject, html) }.onFailure { log.warn("[SecurityAlerter] Email failed: {}", it.message) }
        }
    }

    // ------------------------------------------------------------------------------------------- daily digest

    @Synchronized
    fun startDigest() {
        if (digestJob?.isActive == true) return
        digestJob = scope.launch {
            delay(2 * 60_000L)
            while (isActive) {
                runCatching { maybeSendDigest() }.onFailure { log.warn("[SecurityAlerter] Digest failed: {}", it.message) }
                delay(10 * 60_000L)
            }
        }
    }

    private fun maybeSendDigest() {
        if (!SecuritySettings.enabled || !SecuritySettings.config.daily_digest || !DatabaseFactory.isReady) return
        if (LocalTime.now().hour != SecuritySettings.config.digest_hour.coerceIn(0, 23)) return
        if (!com.obsidianscout.admin.NodeMonitoringService.claimNotificationLock("security-digest-${LocalDate.now()}", 23 * 60L)) return
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        val counts = SecurityLog.countsSince(since).filterKeys { it != SecurityEventType.ADMIN_ACTION.name }
        val protected = AccountProtection.list()
        val pending = UnblockRequests.list("pending").size
        if (counts.isEmpty() && protected.isEmpty() && pending == 0) return
        val top = SecurityLog.topIpsSince(since, 10)
        val blocks = (counts[SecurityEventType.BLOCK_APPLIED.name] ?: 0) + (counts[SecurityEventType.WOULD_BLOCK.name] ?: 0)
        val high = counts.filterKeys { k -> runCatching { SecurityEventType.valueOf(k).severity == Severity.HIGH }.getOrDefault(false) }.values.sum()
        pushSuperadmins(
            "Security: daily summary",
            "$blocks block${if (blocks == 1) "" else "s"}, $high high-severity event${if (high == 1) "" else "s"}, " +
                "$pending unblock request${if (pending == 1) "" else "s"} waiting in the last 24 hours.",
            "security-digest"
        )
        emailSuperadmins(
            "[SECURITY] Daily summary",
            listOf(
                "Mode" to SecuritySettings.runtime().mode,
                "Events (24 h)" to counts.entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key}: ${it.value}" },
                "Top addresses" to top.joinToString("\n") { "${it.first}: ${it.second} events" },
                "Blocks applied" to (counts[SecurityEventType.BLOCK_APPLIED.name] ?: 0).toString(),
                "Active blocks" to BlockList.activeBlocks().size.toString(),
                "Accounts in protection" to protected.joinToString(", ") { a -> a.username + (a.teamNumber?.let { " (team $it)" } ?: "") }.ifBlank { "None" },
                "Unblock requests waiting" to pending.toString()
            ),
            intro = "Security activity over the last 24 hours."
        )
    }
}
