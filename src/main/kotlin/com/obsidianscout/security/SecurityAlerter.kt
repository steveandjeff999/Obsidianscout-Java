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
 * Tells people about security events: superadmins by push and email for HIGH events, blocks and unblock requests;
 * a team's admins by push when their team's accounts or data are targeted; and a daily digest email. One alert per
 * event type and IP (or account) per cooldown, across the cluster, so an attack sends one alert plus the digest.
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

    fun onEvent(event: SecurityEvent) {
        val alertsSuperadmins = event.type.alerts
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
        val url = "/server-insights#security"
        if (config.alert_push) {
            userIds(UserRole.SUPERADMIN).forEach { id ->
                PushNotificationService.sendSystemNotification(id, "Security: $title", body, url, "security-${event.type.name.lowercase()}", "Security")
            }
        }
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
        if (LocalTime.now().hour != 8) return
        if (!com.obsidianscout.admin.NodeMonitoringService.claimNotificationLock("security-digest-${LocalDate.now()}", 23 * 60L)) return
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        val counts = SecurityLog.countsSince(since).filterKeys { it != SecurityEventType.ADMIN_ACTION.name }
        val protected = AccountProtection.list()
        val pending = UnblockRequests.list("pending").size
        if (counts.isEmpty() && protected.isEmpty() && pending == 0) return
        val top = SecurityLog.topIpsSince(since, 10)
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
