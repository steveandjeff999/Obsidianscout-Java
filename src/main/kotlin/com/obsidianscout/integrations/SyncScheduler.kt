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

    @Volatile
    var lastSyncTeams: Int? = null

    @Volatile
    var lastSyncMatches: Int? = null

    @Volatile
    var lastSyncTeamCount: Int? = null

    @Volatile
    var lastSyncFailedTeams: Int? = null

    @Volatile
    var syncInProgress: Boolean = false

    @Volatile
    var currentSyncLabel: String? = null

    private var scope: CoroutineScope? = null
    private val syncLock = Any()

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

    fun enqueueEventSync(settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync("Manual event list sync")) {
            return false
        }
        activeScope.launch {
            try {
                val count = IntegrationService.syncEvents(settings)
                lastSyncAt = Instant.now()
                lastSyncSummary = "Manual event list sync complete: $count events"
                lastSyncError = null
                lastSyncTeams = null
                lastSyncMatches = null
                lastSyncTeamCount = null
                lastSyncFailedTeams = null
                log.info(lastSyncSummary)
            } catch (error: Exception) {
                recordFailure("Manual event list sync failed", error)
            } finally {
                finishSync()
            }
        }
        return true
    }

    fun enqueueEventDataSync(settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync("Manual teams and matches sync")) {
            return false
        }
        activeScope.launch {
            try {
                val counts = IntegrationService.syncEventData(settings)
                val eventKey = settings.resolvedEventKey()
                if (eventKey.isNotBlank()) {
                    try {
                        IntegrationService.syncEpaOprHistory(settings, eventKey)
                    } catch (e: Exception) {
                        log.warn("EPA/OPR history sync failed: ${e.message}")
                    }
                }
                lastSyncAt = Instant.now()
                lastSyncSummary = "Manual sync: ${counts.teams} teams, ${counts.matches} matches"
                lastSyncTeams = counts.teams
                lastSyncMatches = counts.matches
                lastSyncTeamCount = 1
                lastSyncFailedTeams = null
                lastSyncError = null
                log.info(lastSyncSummary)
            } catch (error: Exception) {
                recordFailure("Manual teams and matches sync failed", error)
            } finally {
                finishSync()
            }
        }
        return true
    }

    fun enqueueStatsSync(settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync("Manual stats sync")) {
            return false
        }
        activeScope.launch {
            try {
                val count = IntegrationService.syncStats(settings)
                val eventKey = settings.resolvedEventKey()
                if (eventKey.isNotBlank()) {
                    try {
                        IntegrationService.syncEpaOprHistory(settings, eventKey)
                    } catch (e: Exception) {
                        log.warn("EPA/OPR history sync failed: ${e.message}")
                    }
                }
                lastSyncAt = Instant.now()
                lastSyncSummary = "Manual stats sync complete: $count team stat record(s)"
                lastSyncError = null
                lastSyncTeams = count
                lastSyncMatches = null
                lastSyncTeamCount = 1
                lastSyncFailedTeams = null
                log.info(lastSyncSummary)
            } catch (error: Exception) {
                recordFailure("Manual stats sync failed", error)
            } finally {
                finishSync()
            }
        }
        return true
    }

    suspend fun runScheduledSync() {
        if (!beginSync("Auto-sync")) {
            return
        }
        try {
            runScheduledSyncUnchecked()
        } finally {
            finishSync()
        }
    }

    private suspend fun runScheduledSyncUnchecked() {
        val teams = SettingsService.teamNumbersEligibleForAutoSync()
        if (teams.isEmpty()) {
            lastSyncSummary = "No teams with event code and API keys configured"
            lastSyncAt = Instant.now()
            lastSyncError = null
            lastSyncTeams = null
            lastSyncMatches = null
            lastSyncTeamCount = null
            lastSyncFailedTeams = null
            return
        }

        var totalTeams = 0
        var totalMatches = 0
        var failures = 0

        teams.forEach { teamNumber ->
            try {
                val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(teamNumber)
                val counts = IntegrationService.syncEventData(settings)
                totalTeams += counts.teams
                totalMatches += counts.matches
                val eventKey = settings.resolvedEventKey()
                if (eventKey.isNotBlank()) {
                    try {
                        IntegrationService.syncEpaOprHistory(settings, eventKey)
                    } catch (e: Exception) {
                        log.warn("Auto-sync EPA/OPR history sync failed for team $teamNumber: ${e.message}")
                    }
                }
            } catch (error: Exception) {
                failures++
                log.warn("Auto-sync failed for team $teamNumber: ${error.message}")
            }
        }

        lastSyncAt = Instant.now()
        lastSyncError = if (failures > 0) "$failures team sync(s) failed" else null
        lastSyncTeams = totalTeams
        lastSyncMatches = totalMatches
        lastSyncTeamCount = teams.size
        lastSyncFailedTeams = if (failures > 0) failures else null
        lastSyncSummary = "Synced $totalTeams teams and $totalMatches matches for ${teams.size} team(s)"
        log.info("Auto-sync complete: $lastSyncSummary")
    }

    private fun beginSync(label: String): Boolean {
        synchronized(syncLock) {
            if (syncInProgress) {
                lastSyncError = "Sync already running"
                return false
            }
            syncInProgress = true
            currentSyncLabel = label
            lastSyncError = null
            lastSyncSummary = "$label started"
            return true
        }
    }

    private fun finishSync() {
        synchronized(syncLock) {
            syncInProgress = false
            currentSyncLabel = null
        }
    }

    private fun recordFailure(label: String, error: Exception) {
        lastSyncAt = Instant.now()
        lastSyncError = error.message ?: label
        lastSyncSummary = label
        lastSyncTeams = null
        lastSyncMatches = null
        lastSyncTeamCount = null
        lastSyncFailedTeams = 1
        log.warn("$label: ${error.message}")
    }

    fun enqueueCustomEventDataSync(settings: ApiSettings, eventKey: String): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync("Manual custom event sync: $eventKey")) {
            return false
        }
        activeScope.launch {
            try {
                val counts = IntegrationService.syncCustomEventData(settings, eventKey)
                try {
                    IntegrationService.syncEpaOprHistory(settings, eventKey)
                } catch (e: Exception) {
                    log.warn("Custom event EPA/OPR history sync failed: ${e.message}")
                }
                lastSyncAt = Instant.now()
                lastSyncSummary = "Custom event sync complete: $eventKey - ${counts.teams} teams, ${counts.matches} matches"
                lastSyncTeams = counts.teams
                lastSyncMatches = counts.matches
                lastSyncTeamCount = 1
                lastSyncFailedTeams = null
                lastSyncError = null
                log.info(lastSyncSummary)
            } catch (error: Exception) {
                recordFailure("Custom event sync failed for $eventKey", error)
            } finally {
                finishSync()
            }
        }
        return true
    }

    fun enqueueFullSync(settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync("Manual full sync")) {
            return false
        }
        activeScope.launch {
            try {
                val eventsCount = IntegrationService.syncEvents(settings)
                val counts = IntegrationService.syncEventData(settings)
                val eventKey = settings.resolvedEventKey()
                if (eventKey.isNotBlank()) {
                    try {
                        IntegrationService.syncEpaOprHistory(settings, eventKey)
                    } catch (e: Exception) {
                        log.warn("Full sync EPA/OPR history sync failed: ${e.message}")
                    }
                }
                lastSyncAt = Instant.now()
                lastSyncSummary = "Manual full sync complete: $eventsCount events, ${counts.teams} teams, ${counts.matches} matches"
                lastSyncTeams = counts.teams
                lastSyncMatches = counts.matches
                lastSyncTeamCount = 1
                lastSyncFailedTeams = null
                lastSyncError = null
                log.info(lastSyncSummary)
            } catch (error: Exception) {
                recordFailure("Manual full sync failed", error)
            } finally {
                finishSync()
            }
        }
        return true
    }

    fun triggerBackgroundHistorySync(settings: ApiSettings, eventKey: String) {
        val activeScope = scope ?: return
        activeScope.launch {
            try {
                IntegrationService.syncEpaOprHistory(settings, eventKey)
            } catch (e: Exception) {
                log.warn("Background OPR/EPA history sync failed for $eventKey: ${e.message}")
            }
        }
    }
}
