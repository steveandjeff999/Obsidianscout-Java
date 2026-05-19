package com.obsidianscout.integrations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

object SyncScheduler {
    private val log = LoggerFactory.getLogger("SyncScheduler")

    const val INTERVAL_MS: Long = 450_000L // 7.5 minutes

    @Volatile
    var lastSyncAt: Instant? = null

    @Volatile
    var lastSyncSummary: String? = null

    @Volatile
    var lastSyncError: String? = null

    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) {
            return
        }
        val job = SupervisorJob()
        scope = CoroutineScope(job + Dispatchers.IO)
        scope?.launch {
            log.info("Auto-sync scheduler started (every ${INTERVAL_MS / 60_000.0} minutes)")
            delay(30_000)
            while (isActive) {
                runScheduledSync()
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    suspend fun runScheduledSync() {
        val teams = SettingsService.teamNumbersEligibleForAutoSync()
        if (teams.isEmpty()) {
            lastSyncSummary = "No teams with event code and API keys configured"
            lastSyncAt = Instant.now()
            return
        }

        var totalTeams = 0
        var totalMatches = 0
        var failures = 0

        teams.forEach { teamNumber ->
            try {
                val settings = SettingsService.getSettings(teamNumber)
                val counts = IntegrationService.syncEventData(settings)
                totalTeams += counts.teams
                totalMatches += counts.matches
            } catch (error: Exception) {
                failures++
                log.warn("Auto-sync failed for team $teamNumber: ${error.message}")
            }
        }

        lastSyncAt = Instant.now()
        lastSyncError = if (failures > 0) "$failures team sync(s) failed" else null
        lastSyncSummary = "Synced $totalTeams teams and $totalMatches matches for ${teams.size} team(s)"
        log.info("Auto-sync complete: $lastSyncSummary")
    }
}
