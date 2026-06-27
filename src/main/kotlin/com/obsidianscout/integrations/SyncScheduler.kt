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

    class TeamSyncStatus {
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
    }

    private val teamStatuses = java.util.concurrent.ConcurrentHashMap<Int, TeamSyncStatus>()

    fun getStatusForTeam(teamNumber: Int): TeamSyncStatus {
        return teamStatuses.computeIfAbsent(teamNumber) { TeamSyncStatus() }
    }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var lastSyncAt: Instant?
        get() = getStatusForTeam(0).lastSyncAt
        set(value) { getStatusForTeam(0).lastSyncAt = value }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var lastSyncSummary: String?
        get() = getStatusForTeam(0).lastSyncSummary
        set(value) { getStatusForTeam(0).lastSyncSummary = value }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var lastSyncError: String?
        get() = getStatusForTeam(0).lastSyncError
        set(value) { getStatusForTeam(0).lastSyncError = value }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var lastSyncTeams: Int?
        get() = getStatusForTeam(0).lastSyncTeams
        set(value) { getStatusForTeam(0).lastSyncTeams = value }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var lastSyncMatches: Int?
        get() = getStatusForTeam(0).lastSyncMatches
        set(value) { getStatusForTeam(0).lastSyncMatches = value }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var lastSyncTeamCount: Int?
        get() = getStatusForTeam(0).lastSyncTeamCount
        set(value) { getStatusForTeam(0).lastSyncTeamCount = value }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var lastSyncFailedTeams: Int?
        get() = getStatusForTeam(0).lastSyncFailedTeams
        set(value) { getStatusForTeam(0).lastSyncFailedTeams = value }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var syncInProgress: Boolean
        get() = getStatusForTeam(0).syncInProgress
        set(value) { getStatusForTeam(0).syncInProgress = value }

    @Deprecated("Use getStatusForTeam(teamNumber)")
    var currentSyncLabel: String?
        get() = getStatusForTeam(0).currentSyncLabel
        set(value) { getStatusForTeam(0).currentSyncLabel = value }

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

    @Deprecated("Use enqueueEventSync(teamNumber, settings)")
    fun enqueueEventSync(settings: ApiSettings): Boolean = enqueueEventSync(0, settings)

    fun enqueueEventSync(teamNumber: Int, settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync(teamNumber, "Manual event list sync")) {
            return false
        }
        activeScope.launch {
            try {
                val count = IntegrationService.syncEvents(settings)
                val status = getStatusForTeam(teamNumber)
                status.lastSyncAt = Instant.now()
                status.lastSyncSummary = "Manual event list sync complete: $count events"
                status.lastSyncError = null
                status.lastSyncTeams = null
                status.lastSyncMatches = null
                status.lastSyncTeamCount = null
                status.lastSyncFailedTeams = null
                log.info("Manual event list sync complete for team $teamNumber: $count events")
            } catch (error: Exception) {
                recordFailure(teamNumber, "Manual event list sync failed", error)
            } finally {
                finishSync(teamNumber)
            }
        }
        return true
    }

    @Deprecated("Use enqueueEventDataSync(teamNumber, settings)")
    fun enqueueEventDataSync(settings: ApiSettings): Boolean = enqueueEventDataSync(0, settings)

    fun enqueueEventDataSync(teamNumber: Int, settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync(teamNumber, "Manual teams and matches sync")) {
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
                        log.warn("EPA/OPR history sync failed for team $teamNumber: ${e.message}")
                    }
                }
                val status = getStatusForTeam(teamNumber)
                status.lastSyncAt = Instant.now()
                status.lastSyncSummary = "Manual sync: ${counts.teams} teams, ${counts.matches} matches"
                status.lastSyncTeams = counts.teams
                status.lastSyncMatches = counts.matches
                status.lastSyncTeamCount = 1
                status.lastSyncFailedTeams = null
                status.lastSyncError = null
                log.info("Manual sync complete for team $teamNumber: ${counts.teams} teams, ${counts.matches} matches")
            } catch (error: Exception) {
                recordFailure(teamNumber, "Manual teams and matches sync failed", error)
            } finally {
                finishSync(teamNumber)
            }
        }
        return true
    }

    @Deprecated("Use enqueueStatsSync(teamNumber, settings)")
    fun enqueueStatsSync(settings: ApiSettings): Boolean = enqueueStatsSync(0, settings)

    fun enqueueStatsSync(teamNumber: Int, settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync(teamNumber, "Manual stats sync")) {
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
                        log.warn("EPA/OPR history sync failed for team $teamNumber: ${e.message}")
                    }
                }
                val status = getStatusForTeam(teamNumber)
                status.lastSyncAt = Instant.now()
                status.lastSyncSummary = "Manual stats sync complete: $count team stat record(s)"
                status.lastSyncError = null
                status.lastSyncTeams = count
                status.lastSyncMatches = null
                status.lastSyncTeamCount = 1
                status.lastSyncFailedTeams = null
                log.info("Manual stats sync complete for team $teamNumber: $count team stat record(s)")
            } catch (error: Exception) {
                recordFailure(teamNumber, "Manual stats sync failed", error)
            } finally {
                finishSync(teamNumber)
            }
        }
        return true
    }

    suspend fun runScheduledSync() {
        try {
            runScheduledSyncUnchecked()
        } catch (e: Exception) {
            log.error("Scheduled sync error: ${e.message}", e)
        }
    }

    private suspend fun runScheduledSyncUnchecked() {
        val teams = SettingsService.teamNumbersEligibleForAutoSync()
        if (teams.isEmpty()) {
            val status = getStatusForTeam(0)
            status.lastSyncSummary = "No teams with event code and API keys configured"
            status.lastSyncAt = Instant.now()
            status.lastSyncError = null
            status.lastSyncTeams = null
            status.lastSyncMatches = null
            status.lastSyncTeamCount = null
            status.lastSyncFailedTeams = null
            return
        }

        var totalTeams = 0
        var totalMatches = 0
        var failures = 0

        teams.forEach { teamNumber ->
            if (!beginSync(teamNumber, "Auto-sync")) {
                return@forEach
            }
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
                val status = getStatusForTeam(teamNumber)
                status.lastSyncAt = Instant.now()
                status.lastSyncError = null
                status.lastSyncTeams = counts.teams
                status.lastSyncMatches = counts.matches
                status.lastSyncTeamCount = 1
                status.lastSyncFailedTeams = null
                status.lastSyncSummary = "Auto-sync complete: ${counts.teams} teams, ${counts.matches} matches"
            } catch (error: Exception) {
                failures++
                log.warn("Auto-sync failed for team $teamNumber: ${error.message}")
                recordFailure(teamNumber, "Auto-sync failed", error)
            } finally {
                finishSync(teamNumber)
            }
        }
    }

    private fun beginSync(teamNumber: Int, label: String): Boolean {
        val status = getStatusForTeam(teamNumber)
        synchronized(status) {
            if (status.syncInProgress) {
                status.lastSyncError = "Sync already running"
                return false
            }
            status.syncInProgress = true
            status.currentSyncLabel = label
            status.lastSyncError = null
            status.lastSyncSummary = "$label started"
            return true
        }
    }

    private fun finishSync(teamNumber: Int) {
        val status = getStatusForTeam(teamNumber)
        synchronized(status) {
            status.syncInProgress = false
            status.currentSyncLabel = null
        }
    }

    private fun recordFailure(teamNumber: Int, label: String, error: Exception) {
        val status = getStatusForTeam(teamNumber)
        synchronized(status) {
            status.lastSyncAt = Instant.now()
            status.lastSyncError = error.message ?: label
            status.lastSyncSummary = label
            status.lastSyncTeams = null
            status.lastSyncMatches = null
            status.lastSyncTeamCount = null
            status.lastSyncFailedTeams = 1
            log.warn("$label for team $teamNumber: ${error.message}")
        }
    }

    @Deprecated("Use enqueueCustomEventDataSync(teamNumber, settings, eventKey)")
    fun enqueueCustomEventDataSync(settings: ApiSettings, eventKey: String): Boolean =
        enqueueCustomEventDataSync(0, settings, eventKey)

    fun enqueueCustomEventDataSync(teamNumber: Int, settings: ApiSettings, eventKey: String): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync(teamNumber, "Manual custom event sync: $eventKey")) {
            return false
        }
        activeScope.launch {
            try {
                val counts = IntegrationService.syncCustomEventData(settings, eventKey)
                try {
                    IntegrationService.syncEpaOprHistory(settings, eventKey)
                } catch (e: Exception) {
                    log.warn("Custom event EPA/OPR history sync failed for team $teamNumber: ${e.message}")
                }
                val status = getStatusForTeam(teamNumber)
                status.lastSyncAt = Instant.now()
                status.lastSyncSummary = "Custom event sync complete: $eventKey - ${counts.teams} teams, ${counts.matches} matches"
                status.lastSyncTeams = counts.teams
                status.lastSyncMatches = counts.matches
                status.lastSyncTeamCount = 1
                status.lastSyncFailedTeams = null
                status.lastSyncError = null
                log.info("Custom event sync complete for team $teamNumber: $eventKey - ${counts.teams} teams, ${counts.matches} matches")
            } catch (error: Exception) {
                recordFailure(teamNumber, "Custom event sync failed for $eventKey", error)
            } finally {
                finishSync(teamNumber)
            }
        }
        return true
    }

    @Deprecated("Use enqueueFullSync(teamNumber, settings)")
    fun enqueueFullSync(settings: ApiSettings): Boolean = enqueueFullSync(0, settings)

    fun enqueueFullSync(teamNumber: Int, settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        if (!beginSync(teamNumber, "Manual full sync")) {
            return false
        }
        activeScope.launch {
            try {
                val eventsCount = IntegrationService.syncEvents(settings)
                val counts = IntegrationService.syncEventData(settings)
                val statsCount = IntegrationService.syncStats(settings)
                val eventKey = settings.resolvedEventKey()
                if (eventKey.isNotBlank()) {
                    try {
                        IntegrationService.syncEpaOprHistory(settings, eventKey)
                    } catch (e: Exception) {
                        log.warn("Full sync EPA/OPR history sync failed for team $teamNumber: ${e.message}")
                    }
                }
                val status = getStatusForTeam(teamNumber)
                status.lastSyncAt = Instant.now()
                status.lastSyncSummary = "Manual full sync complete: $eventsCount events, ${counts.teams} teams, ${counts.matches} matches, $statsCount stats"
                status.lastSyncTeams = counts.teams
                status.lastSyncMatches = counts.matches
                status.lastSyncTeamCount = 1
                status.lastSyncFailedTeams = null
                status.lastSyncError = null
                log.info("Manual full sync complete for team $teamNumber: $eventsCount events, ${counts.teams} teams, ${counts.matches} matches, $statsCount stats")
            } catch (error: Exception) {
                recordFailure(teamNumber, "Manual full sync failed", error)
            } finally {
                finishSync(teamNumber)
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
