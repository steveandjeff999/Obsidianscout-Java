package com.obsidianscout.security

import com.obsidianscout.auth.clientIp
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.userAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The one way security events enter the system. [emit] never blocks a request: it drops the event onto a bounded
 * channel, and a single consumer hands each event to the threat scorer, the database log, the alerter and the
 * in-memory list on Server Insights. When the channel is full the event is dropped and counted, so a flood can't
 * exhaust memory.
 */
object SecurityEvents {
    private val log = org.slf4j.LoggerFactory.getLogger(SecurityEvents::class.java)
    private const val CAPACITY = 10_000

    private val channel = Channel<SecurityEvent>(CAPACITY)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val dropped = AtomicLong(0)

    /** Tests run events synchronously so they can assert straight after emitting. */
    @Volatile var synchronousForTests: Boolean = false

    /** Extra consumers (tests). */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(SecurityEvent) -> Unit>()
    fun addListener(listener: (SecurityEvent) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (SecurityEvent) -> Unit) { listeners.remove(listener) }

    init {
        scope.launch {
            for (event in channel) dispatch(event)
        }
    }

    fun emit(event: SecurityEvent) {
        if (!SecuritySettings.enabled && event.type != SecurityEventType.ADMIN_ACTION) {
            // Detection off: keep the superadmin audit trail working as before, nothing else.
            com.obsidianscout.admin.ServerAnalyticsService.recordSecurityAudit(
                event.type.name, event.username ?: "ANONYMOUS", event.role ?: "GUEST", event.ip, event.details, success = false
            )
            return
        }
        val clean = sanitize(event)
        if (synchronousForTests) {
            dispatch(clean)
            return
        }
        if (!channel.trySend(clean).isSuccess) dropped.incrementAndGet()
    }

    /** Builds an event from the request it happened on. */
    fun emit(
        call: ApplicationCall,
        type: SecurityEventType,
        details: String = "",
        userId: String? = null,
        username: String? = null,
        teamNumber: Int? = null,
        role: String? = null,
        ip: String? = null,
        account: String? = null
    ) {
        emit(
            SecurityEvent(
                type = type,
                ip = ip ?: runCatching { call.clientIp() }.getOrDefault("unknown"),
                userId = userId,
                username = username,
                teamNumber = teamNumber,
                method = runCatching { call.request.httpMethod.value }.getOrDefault(""),
                path = runCatching { call.request.path() }.getOrDefault(""),
                userAgent = runCatching { call.request.userAgent() }.getOrNull(),
                details = details,
                role = role,
                account = account
            )
        )
    }

    private fun dispatch(event: SecurityEvent) {
        runCatching {
            com.obsidianscout.admin.ServerAnalyticsService.recordSecurityAudit(
                event.type.name,
                event.username ?: "ANONYMOUS",
                event.role ?: (if (event.username != null) "USER" else "GUEST"),
                event.ip,
                event.details.ifBlank { "${event.method} ${event.path}".trim() },
                success = event.type == SecurityEventType.ADMIN_ACTION || event.type.severity == Severity.INFO
            )
        }.onFailure { log.debug("[SecurityEvents] audit list failed: {}", it.message) }
        runCatching { SecurityLog.enqueue(event) }.onFailure { log.debug("[SecurityEvents] log failed: {}", it.message) }
        runCatching { ThreatEngine.onEvent(event) }.onFailure { log.warn("[SecurityEvents] scoring failed: {}", it.message) }
        runCatching { SecurityAlerter.onEvent(event) }.onFailure { log.warn("[SecurityEvents] alerting failed: {}", it.message) }
        listeners.forEach { listener -> runCatching { listener(event) } }
    }

    /** Caps every user-supplied field and strips control characters, so the log can't be injected into or bloated. */
    internal fun sanitize(event: SecurityEvent): SecurityEvent = event.copy(
        ip = clean(event.ip, 64).ifBlank { "unknown" },
        userId = event.userId?.let { clean(it, 64) },
        username = event.username?.let { clean(it, 128) },
        method = clean(event.method, 10),
        path = clean(event.path, 512),
        userAgent = event.userAgent?.let { clean(it, 256) },
        details = clean(event.details, 1024),
        role = event.role?.let { clean(it, 32) },
        account = event.account?.let { clean(it, 200) }
    )

    internal fun clean(value: String, max: Int): String {
        val sb = StringBuilder(minOf(value.length, max))
        for (ch in value) {
            if (sb.length >= max) break
            sb.append(if (ch.isISOControl() || ch == ' ' || ch == ' ') ' ' else ch)
        }
        return sb.toString()
    }
}
