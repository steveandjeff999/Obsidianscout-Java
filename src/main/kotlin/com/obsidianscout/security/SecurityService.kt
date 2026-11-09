package com.obsidianscout.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Starts the background parts of the intrusion detection system and builds the Server Insights summary. */
object SecurityService {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var housekeeping: Job? = null
    private const val SUMMARY_CACHE_MS = 15_000L
    @Volatile private var cachedSummary: Pair<SecuritySummaryDto, Long>? = null

    /** Call once the database is ready. */
    @Synchronized
    fun start() {
        SecuritySync.listen()
        SecurityLog.start()
        BlockList.start()
        SecurityAlerter.startDigest()
        if (housekeeping?.isActive != true) {
            housekeeping = scope.launch {
                while (isActive) {
                    delay(60_000L)
                    runCatching { SecurityStore.cleanup() }
                    runCatching { SecurityMonitor.cleanup() }
                }
            }
        }
    }

    fun summary(force: Boolean = false): SecuritySummaryDto {
        val now = System.currentTimeMillis()
        if (!force) cachedSummary?.let { if (now - it.second < SUMMARY_CACHE_MS) return it.first }
        val runtime = SecuritySettings.runtime()
        val (timeline, byType) = runCatching { SecurityLog.timeline(24, now) }.getOrDefault(emptyList<SecurityTimelineBucket>() to emptyMap())
        val offenders = ThreatEngine.topOffenders(10)
        val last = runCatching { SecurityLog.lastEventFor(offenders.map { it.first }) }.getOrDefault(emptyMap())
        val summary = SecuritySummaryDto(
            enabled = SecuritySettings.enabled,
            mode = runtime.mode,
            autoRevokeSessions = runtime.autoRevokeSessions,
            maxBlockMinutes = (BlockList.MAX_BLOCK_MS / 60_000L).toInt(),
            activeBlocks = BlockList.activeBlocks(now).size,
            pendingUnblockRequests = runCatching { UnblockRequests.list("pending").size }.getOrDefault(0),
            protectedAccounts = runCatching { AccountProtection.list().size }.getOrDefault(0),
            credentialStuffingActive = LoginGuard.credentialStuffingActive,
            eventsLast24h = byType,
            timeline = timeline,
            topOffenders = offenders.map { (ip, score) ->
                val (at, type) = last[ip] ?: (0L to "")
                ThreatOffenderDto(ip, Math.round(score * 10) / 10.0, ThreatEngine.level(score).name, at, type)
            },
            droppedEvents = SecurityEvents.dropped.get() + SecurityLog.droppedWrites.get()
        )
        cachedSummary = summary to now
        return summary
    }

    fun invalidateSummary() {
        cachedSummary = null
    }
}
