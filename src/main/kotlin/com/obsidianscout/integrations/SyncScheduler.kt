package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.SyncStatuses
import com.obsidianscout.db.readTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant

/** Every [ApiSettings] field that event-data and history sync read. Equal inputs give equal sync results. */
private data class SyncInputs(
    val program: String,
    val year: Int,
    val eventKey: String,
    val eventCode: String,
    val alternateEventCode: String,
    val timezone: String,
    val useStatboticsEpa: Boolean,
    val useMatch13Exp: Boolean,
    val apiKeys: ApiKeys,
    val statboticsBaseUrl: String,
    val match13BaseUrl: String
) {
    companion object {
        fun of(settings: ApiSettings) = SyncInputs(
            program = settings.program,
            year = settings.year,
            eventKey = settings.resolvedEventKey(),
            eventCode = settings.eventCode,
            alternateEventCode = settings.alternateEventCode,
            timezone = settings.timezone,
            useStatboticsEpa = settings.useStatboticsEpa,
            useMatch13Exp = settings.useMatch13Exp,
            apiKeys = settings.apiKeys,
            statboticsBaseUrl = settings.statboticsBaseUrl,
            match13BaseUrl = settings.match13BaseUrl
        )
    }
}

/**
 * Which cluster node runs the automatic sync. Every node competes for one [ClusterLease]; the holder
 * renews it every [RENEW_MS]. A node only treats itself as leader until [LOCAL_VALIDITY_SECONDS] after
 * its last successful renewal, which is well inside the lease's [TTL_SECONDS]: a node that loses the
 * database stops syncing at least 30 seconds before anyone else may take over, so two leaders never
 * overlap even with a few seconds of clock drift between nodes.
 */
internal object SyncLeadership {
    const val LEASE_KEY = "sync-scheduler-leader"
    const val TTL_SECONDS = 90L
    const val LOCAL_VALIDITY_SECONDS = 60L
    const val RENEW_MS = 15_000L

    private val log = LoggerFactory.getLogger("SyncLeadership")

    @Volatile
    private var validUntil: Instant = Instant.EPOCH

    fun isLeader(now: Instant = Instant.now()): Boolean = now.isBefore(validUntil)

    /** Takes or renews the lease. Returns whether this node is leader afterwards. */
    fun refresh(now: Instant = Instant.now()): Boolean {
        val wasLeader = isLeader(now)
        val result = ClusterLease.tryAcquire(LEASE_KEY, TTL_SECONDS, now = now)
        validUntil = if (result == ClusterLease.Result.ACQUIRED) now.plusSeconds(LOCAL_VALIDITY_SECONDS) else Instant.EPOCH
        val leader = isLeader(now)
        if (leader && !wasLeader) log.info("This node (${ClusterLease.nodeId}) is now the auto-sync leader")
        if (!leader && wasLeader) log.warn("This node (${ClusterLease.nodeId}) is no longer the auto-sync leader ($result)")
        return leader
    }

    /** Hands leadership off right away, e.g. on shutdown, instead of making the others wait out the lease. */
    fun resign() {
        validUntil = Instant.EPOCH
        ClusterLease.release(LEASE_KEY)
    }
}

object SyncScheduler {
    private val log = LoggerFactory.getLogger("SyncScheduler")

    const val INTERVAL_MS: Long = 450_000L // 7.5 minutes

    /** How often an ended event still refreshes from its schedule sources (TBA/FIRST, or FTC Scout/FIRST). */
    private const val ENDED_EVENT_INTERVAL_SECONDS = 86_400L

    /** Upper bound on one team's sync; the cluster-wide "already running" lock expires after this if a node dies mid-sync. */
    private const val TEAM_SYNC_LEASE_SECONDS = 1_800L

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

        /** When this status last changed; decides between this node's copy and the shared one. */
        @Volatile
        var updatedAt: Instant = Instant.EPOCH
    }

    /** The stored form of [TeamSyncStatus]. An in-progress flag only counts until [inProgressUntil]. */
    @Serializable
    private data class StoredStatus(
        val lastSyncAt: Long? = null,
        val lastSyncSummary: String? = null,
        val lastSyncError: String? = null,
        val lastSyncTeams: Int? = null,
        val lastSyncMatches: Int? = null,
        val lastSyncTeamCount: Int? = null,
        val lastSyncFailedTeams: Int? = null,
        val syncInProgress: Boolean = false,
        val currentSyncLabel: String? = null,
        val inProgressUntil: Long? = null,
        val updatedAt: Long = 0
    )

    private val teamStatuses = java.util.concurrent.ConcurrentHashMap<String, TeamSyncStatus>()

    /** This node's working copy of a team's status. Use [statusForDisplay] to show it to users. */
    fun getStatusForTeam(teamNumber: Int, program: String = "FRC"): TeamSyncStatus {
        val key = "$program-$teamNumber"
        return teamStatuses.computeIfAbsent(key) { TeamSyncStatus() }
    }

    /**
     * The team's status as the whole cluster sees it: whichever node changed it last. A sync running on
     * another node shows as running; one that node abandoned (it died) stops showing after its lock expires.
     */
    fun statusForDisplay(teamNumber: Int, program: String = "FRC"): TeamSyncStatus {
        val local = getStatusForTeam(teamNumber, program)
        if (local.syncInProgress) return local
        val stored = runCatching { readStoredStatus(teamNumber, program) }.getOrNull() ?: return local
        if (!Instant.ofEpochMilli(stored.updatedAt).isAfter(local.updatedAt)) return local
        val now = Instant.now().toEpochMilli()
        return TeamSyncStatus().apply {
            lastSyncAt = stored.lastSyncAt?.let(Instant::ofEpochMilli)
            lastSyncSummary = stored.lastSyncSummary
            lastSyncError = stored.lastSyncError
            lastSyncTeams = stored.lastSyncTeams
            lastSyncMatches = stored.lastSyncMatches
            lastSyncTeamCount = stored.lastSyncTeamCount
            lastSyncFailedTeams = stored.lastSyncFailedTeams
            syncInProgress = stored.syncInProgress && (stored.inProgressUntil ?: 0) > now
            currentSyncLabel = if (syncInProgress) stored.currentSyncLabel else null
            updatedAt = Instant.ofEpochMilli(stored.updatedAt)
        }
    }

    private fun readStoredStatus(teamNumber: Int, program: String): StoredStatus? = readTransaction {
        SyncStatuses.selectAll()
            .where { (SyncStatuses.teamNumber eq teamNumber) and (SyncStatuses.program eq program) }
            .limit(1)
            .firstOrNull()
            ?.let { JsonSupport.json.decodeFromString(StoredStatus.serializer(), it[SyncStatuses.statusJson]) }
    }

    /** Publishes this node's copy of the status to the shared table. Never fails the sync it reports on. */
    internal fun persistStatus(teamNumber: Int, program: String) {
        val status = getStatusForTeam(teamNumber, program)
        val now = Instant.now()
        status.updatedAt = now
        val stored = StoredStatus(
            lastSyncAt = status.lastSyncAt?.toEpochMilli(),
            lastSyncSummary = status.lastSyncSummary,
            lastSyncError = status.lastSyncError,
            lastSyncTeams = status.lastSyncTeams,
            lastSyncMatches = status.lastSyncMatches,
            lastSyncTeamCount = status.lastSyncTeamCount,
            lastSyncFailedTeams = status.lastSyncFailedTeams,
            syncInProgress = status.syncInProgress,
            currentSyncLabel = status.currentSyncLabel,
            inProgressUntil = if (status.syncInProgress) now.plusSeconds(TEAM_SYNC_LEASE_SECONDS).toEpochMilli() else null,
            updatedAt = now.toEpochMilli()
        )
        val json = JsonSupport.json.encodeToString(StoredStatus.serializer(), stored)
        runCatching {
            transaction {
                val updated = SyncStatuses.update({ (SyncStatuses.teamNumber eq teamNumber) and (SyncStatuses.program eq program) }) {
                    it[statusJson] = json
                    it[updatedAt] = now
                }
                if (updated == 0) {
                    SyncStatuses.insert {
                        it[SyncStatuses.teamNumber] = teamNumber
                        it[SyncStatuses.program] = program
                        it[statusJson] = json
                        it[updatedAt] = now
                    }
                }
            }
        }.onFailure { log.debug("Could not share sync status for team $teamNumber ($program): ${it.message}") }
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

    fun start() {
        if (scope != null) {
            return
        }
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            if (throwable is OutOfMemoryError || throwable.cause is OutOfMemoryError) {
                log.error("[SyncScheduler] OutOfMemoryError in background coroutine. Clearing GC and recovering...", throwable)
                System.gc()
            } else {
                log.error("[SyncScheduler] Uncaught coroutine exception", throwable)
            }
        }
        val job = SupervisorJob()
        scope = CoroutineScope(job + Dispatchers.IO + handler)
        // Leadership heartbeat. Every node runs it; only the lease holder runs the scheduled sync below.
        scope?.launch {
            while (isActive) {
                try {
                    SyncLeadership.refresh()
                } catch (e: Exception) {
                    log.warn("[SyncScheduler] Leadership check failed: ${e.message}")
                }
                delay(SyncLeadership.RENEW_MS)
            }
        }
        scope?.launch {
            log.info("Auto-sync scheduler initialized on node ${ClusterLease.nodeId} (first sync in 2 minutes, repeating every ${INTERVAL_MS / 60_000.0} minutes, leader only)")
            delay(120_000)
            while (isActive) {
                try {
                    if (SyncLeadership.isLeader()) {
                        runScheduledSync()
                        System.gc()
                    } else {
                        log.debug("Skipping scheduled sync: another node is the auto-sync leader")
                    }
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError || e.cause is OutOfMemoryError) {
                        log.error("[SyncScheduler] OutOfMemoryError during scheduled sync. Triggering System.gc() and pausing...", e)
                        System.gc()
                        delay(30_000)
                    } else {
                        log.error("[SyncScheduler] Unhandled error during scheduled sync", e)
                    }
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        runCatching { SyncLeadership.resign() }
        scope?.cancel()
        scope = null
    }

    @Deprecated("Use enqueueEventSync(teamNumber, settings)")
    fun enqueueEventSync(settings: ApiSettings): Boolean = enqueueEventSync(0, settings)

    // Manual syncs below always run on the node that received the request, leader or not. The per-team
    // cluster lock in beginSync only stops two syncs for the same team from overlapping across nodes.

    fun enqueueEventSync(teamNumber: Int, settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        val program = settings.program
        if (!beginSync(teamNumber, program, "Manual event list sync")) {
            return false
        }
        activeScope.launch {
            try {
                val count = IntegrationService.syncEvents(settings)
                val status = getStatusForTeam(teamNumber, program)
                status.lastSyncAt = Instant.now()
                status.lastSyncSummary = "Manual event list sync complete: $count events"
                status.lastSyncError = null
                status.lastSyncTeams = null
                status.lastSyncMatches = null
                status.lastSyncTeamCount = null
                status.lastSyncFailedTeams = null
                log.info("Manual event list sync complete for team $teamNumber ($program): $count events")
            } catch (error: Exception) {
                recordFailure(teamNumber, program, "Manual event list sync failed", error)
            } finally {
                finishSync(teamNumber, program)
            }
        }
        return true
    }

    @Deprecated("Use enqueueEventDataSync(teamNumber, settings)")
    fun enqueueEventDataSync(settings: ApiSettings): Boolean = enqueueEventDataSync(0, settings)

    fun enqueueEventDataSync(teamNumber: Int, settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        val program = settings.program
        if (!beginSync(teamNumber, program, "Manual teams and matches sync")) {
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
                        log.warn("EPA/OPR history sync failed for team $teamNumber ($program): ${e.message}")
                    }
                }
                recordSuccess(teamNumber, program, "Manual sync: ${counts.teams} teams, ${counts.matches} matches", counts)
                log.info("Manual sync complete for team $teamNumber ($program): ${counts.teams} teams, ${counts.matches} matches")
            } catch (error: Exception) {
                recordFailure(teamNumber, program, "Manual teams and matches sync failed", error)
            } finally {
                finishSync(teamNumber, program)
            }
        }
        return true
    }

    @Deprecated("Use enqueueStatsSync(teamNumber, settings)")
    fun enqueueStatsSync(settings: ApiSettings): Boolean = enqueueStatsSync(0, settings)

    fun enqueueStatsSync(teamNumber: Int, settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        val program = settings.program
        if (!beginSync(teamNumber, program, "Manual stats sync")) {
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
                        log.warn("EPA/OPR history sync failed for team $teamNumber ($program): ${e.message}")
                    }
                }
                val status = getStatusForTeam(teamNumber, program)
                status.lastSyncAt = Instant.now()
                status.lastSyncSummary = "Manual stats sync complete: $count team stat record(s)"
                status.lastSyncError = null
                status.lastSyncTeams = count
                status.lastSyncMatches = null
                status.lastSyncTeamCount = 1
                status.lastSyncFailedTeams = null
                log.info("Manual stats sync complete for team $teamNumber ($program): $count team stat record(s)")
            } catch (error: Exception) {
                recordFailure(teamNumber, program, "Manual stats sync failed", error)
            } finally {
                finishSync(teamNumber, program)
            }
        }
        return true
    }

    suspend fun runScheduledSync() {
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return
        try {
            runScheduledSyncUnchecked()
        } catch (e: Exception) {
            log.error("Scheduled sync error: ${e.message}", e)
        }
    }

    private suspend fun runScheduledSyncUnchecked() {
        val teams = SettingsService.teamNumbersEligibleForAutoSync()
        if (teams.isEmpty()) {
            val status = getStatusForTeam(0, "FRC")
            status.lastSyncSummary = "No teams with event code and API keys configured"
            status.lastSyncAt = Instant.now()
            status.lastSyncError = null
            status.lastSyncTeams = null
            status.lastSyncMatches = null
            status.lastSyncTeamCount = null
            status.lastSyncFailedTeams = null
            return
        }

        // Alliance members often resolve to the exact same event, codes and keys. A second sync with
        // identical inputs in the same pass would refetch and rewrite the same shared rows, so reuse
        // the first result. Inputs that differ at all (any key, code or source) still sync on their own.
        val completedThisPass = mutableMapOf<SyncInputs, SyncCounts>()

        for ((teamNumber, program) in teams) {
            // Stop mid-pass if another node took over, rather than running alongside the new leader.
            if (!SyncLeadership.isLeader()) {
                log.warn("Auto-sync leadership lost mid-pass; leaving the remaining teams to the new leader")
                return
            }
            if (!beginSync(teamNumber, program, "Auto-sync")) {
                continue
            }
            try {
                val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(teamNumber, program)
                val inputs = SyncInputs.of(settings)
                val eventKey = settings.resolvedEventKey()
                // Teams often leave a finished event configured; polling it every cycle forever only loads the
                // APIs (reported by Match 13, issue #2). The schedule sources still get late additions (awards,
                // corrected scores), so an ended event refreshes from those once a day; Statbotics and Match 13
                // are left alone. "Once a day" is read from the event row, so it holds across nodes and restarts.
                // Manual sync still does a full refresh at any time.
                val eventEnded = IntegrationService.getEvent(eventKey)
                    .let { isPastEventSyncWindow(it?.endDate, it?.timezone) }
                if (eventEnded && completedThisPass[inputs] == null) {
                    val lastSynced = IntegrationService.eventSyncedAt(eventKey)
                    if (lastSynced != null && lastSynced.isAfter(Instant.now().minusSeconds(ENDED_EVENT_INTERVAL_SECONDS))) {
                        val status = getStatusForTeam(teamNumber, program)
                        status.lastSyncSummary = "Event ended; refreshing from ${getSourcesLabel(settings)} once a day (manual sync available)"
                        status.lastSyncError = null
                        continue
                    }
                }
                val counts = completedThisPass[inputs] ?: run {
                    val fresh = IntegrationService.syncEventData(settings, tbaAndFirstOnly = eventEnded)
                    // History pulls Statbotics and Match 13 too; page views already refresh it daily for ended events.
                    if (eventKey.isNotBlank() && !eventEnded) {
                        try {
                            IntegrationService.syncEpaOprHistory(settings, eventKey)
                        } catch (e: Exception) {
                            log.warn("Auto-sync EPA/OPR history sync failed for team $teamNumber ($program): ${e.message}")
                        }
                    }
                    completedThisPass[inputs] = fresh
                    fresh
                }
                val sources = getSourcesLabel(settings)
                val summary = if (eventEnded) {
                    "Daily post-event sync complete from $sources: ${counts.teams} teams, ${counts.matches} matches"
                } else {
                    "Auto-sync complete from $sources: ${counts.teams} teams, ${counts.matches} matches"
                }
                recordSuccess(teamNumber, program, summary, counts)
            } catch (error: Exception) {
                log.warn("Auto-sync failed for team $teamNumber ($program): ${error.message}")
                recordFailure(teamNumber, program, "Auto-sync failed", error)
            } finally {
                finishSync(teamNumber, program)
            }
        }
    }

    private fun teamLeaseKey(teamNumber: Int, program: String) = "sync-team:$program-$teamNumber"

    private fun beginSync(teamNumber: Int, program: String = "FRC", label: String): Boolean {
        val status = getStatusForTeam(teamNumber, program)
        synchronized(status) {
            if (status.syncInProgress) {
                status.lastSyncError = "Sync already running"
                return false
            }
        }
        // Cluster-wide: refuse while another node is syncing this team. If the database can't be reached
        // the local check above is all we have, which is how a single server always behaved.
        if (ClusterLease.tryAcquire(teamLeaseKey(teamNumber, program), TEAM_SYNC_LEASE_SECONDS) == ClusterLease.Result.HELD_ELSEWHERE) {
            status.lastSyncError = "Sync already running on another server"
            return false
        }
        synchronized(status) {
            if (status.syncInProgress) {
                // Another request on this node got here first; it owns the (shared, same-node) lease.
                status.lastSyncError = "Sync already running"
                return false
            }
            status.syncInProgress = true
            status.currentSyncLabel = label
            status.lastSyncError = null
            status.lastSyncSummary = "$label started"
        }
        persistStatus(teamNumber, program)
        return true
    }

    private fun finishSync(teamNumber: Int, program: String = "FRC") {
        val status = getStatusForTeam(teamNumber, program)
        synchronized(status) {
            status.syncInProgress = false
            status.currentSyncLabel = null
        }
        ClusterLease.release(teamLeaseKey(teamNumber, program))
        persistStatus(teamNumber, program)
    }

    /** A source that failed while others succeeded is reported as the error, so a broken key gets noticed. */
    private fun recordSuccess(teamNumber: Int, program: String, summary: String, counts: SyncCounts) {
        val status = getStatusForTeam(teamNumber, program)
        synchronized(status) {
            status.lastSyncAt = Instant.now()
            status.lastSyncSummary = summary
            status.lastSyncError = counts.warnings.takeIf { it.isNotEmpty() }
                ?.joinToString("; ", prefix = "Partial sync: ")
            status.lastSyncTeams = counts.teams
            status.lastSyncMatches = counts.matches
            status.lastSyncTeamCount = 1
            status.lastSyncFailedTeams = null
        }
        if (counts.warnings.isNotEmpty()) {
            log.warn("Partial sync for team $teamNumber ($program): ${counts.warnings.joinToString("; ")}")
        }
    }

    private fun recordFailure(teamNumber: Int, program: String = "FRC", label: String, error: Exception) {
        val status = getStatusForTeam(teamNumber, program)
        synchronized(status) {
            status.lastSyncAt = Instant.now()
            status.lastSyncError = error.message ?: label
            status.lastSyncSummary = label
            status.lastSyncTeams = null
            status.lastSyncMatches = null
            status.lastSyncTeamCount = null
            status.lastSyncFailedTeams = 1
            log.warn("$label for team $teamNumber ($program): ${error.message}")
        }
    }

    @Deprecated("Use enqueueCustomEventDataSync(teamNumber, settings, eventKey)")
    fun enqueueCustomEventDataSync(settings: ApiSettings, eventKey: String): Boolean =
        enqueueCustomEventDataSync(0, settings, eventKey)

    fun enqueueCustomEventDataSync(teamNumber: Int, settings: ApiSettings, eventKey: String): Boolean {
        val activeScope = scope ?: return false
        val program = settings.program
        if (!beginSync(teamNumber, program, "Manual custom event sync: $eventKey")) {
            return false
        }
        activeScope.launch {
            try {
                val counts = IntegrationService.syncCustomEventData(settings, eventKey)
                try {
                    IntegrationService.syncEpaOprHistory(settings, eventKey)
                } catch (e: Exception) {
                    log.warn("Custom event EPA/OPR history sync failed for team $teamNumber ($program): ${e.message}")
                }
                val sources = getSourcesLabel(settings)
                recordSuccess(teamNumber, program, "Custom event sync complete from $sources: $eventKey - ${counts.teams} teams, ${counts.matches} matches", counts)
                log.info("Custom event sync complete from $sources for team $teamNumber ($program): $eventKey - ${counts.teams} teams, ${counts.matches} matches")
            } catch (error: Exception) {
                recordFailure(teamNumber, program, "Custom event sync failed for $eventKey", error)
            } finally {
                finishSync(teamNumber, program)
            }
        }
        return true
    }

    @Deprecated("Use enqueueFullSync(teamNumber, settings)")
    fun enqueueFullSync(settings: ApiSettings): Boolean = enqueueFullSync(0, settings)

    fun enqueueFullSync(teamNumber: Int, settings: ApiSettings): Boolean {
        val activeScope = scope ?: return false
        val program = settings.program
        if (!beginSync(teamNumber, program, "Manual full sync")) {
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
                        log.warn("Full sync EPA/OPR history sync failed for team $teamNumber ($program): ${e.message}")
                    }
                }
                val sources = getSourcesLabel(settings)
                recordSuccess(
                    teamNumber, program,
                    "Manual full sync complete from $sources: $eventsCount events, ${counts.teams} teams, ${counts.matches} matches, $statsCount stats",
                    counts
                )
                log.info("Manual full sync complete from $sources for team $teamNumber ($program): $eventsCount events, ${counts.teams} teams, ${counts.matches} matches, $statsCount stats")
            } catch (error: Exception) {
                recordFailure(teamNumber, program, "Manual full sync failed", error)
            } finally {
                finishSync(teamNumber, program)
            }
        }
        return true
    }

    // Every page view of a stale event calls triggerBackgroundHistorySync. One fetch per event at a time on
    // this node (the set), and across the cluster (the lease), however many people are looking.
    private val historySyncInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun triggerBackgroundHistorySync(settings: ApiSettings, eventKey: String) {
        val activeScope = scope ?: return
        val flightKey = "${settings.program}-$eventKey"
        if (!historySyncInFlight.add(flightKey)) return
        activeScope.launch {
            val leaseKey = "history-sync:$flightKey"
            val lease = ClusterLease.tryAcquire(leaseKey, 120)
            try {
                if (lease != ClusterLease.Result.HELD_ELSEWHERE) {
                    IntegrationService.syncEpaOprHistory(settings, eventKey)
                }
            } catch (e: Exception) {
                log.warn("Background OPR/EPA history sync failed for $eventKey: ${e.message}")
            } finally {
                if (lease == ClusterLease.Result.ACQUIRED) ClusterLease.release(leaseKey)
                historySyncInFlight.remove(flightKey)
            }
        }
    }

    fun getSourcesLabel(settings: ApiSettings): String {
        val hasFirst = settings.apiKeys.firstUsername.isNotBlank() && settings.apiKeys.firstKey.isNotBlank()
        return if (settings.program == "FTC") {
            // FTC Scout needs no key and is always used; the FIRST FTC API is added when credentials exist.
            if (hasFirst) "FTC Scout & FIRST FTC API" else "FTC Scout API"
        } else {
            val hasTba = settings.apiKeys.tbaKey.isNotBlank()
            when {
                hasTba && hasFirst -> "TBA & FIRST Robotics API"
                hasFirst -> "FIRST Robotics API"
                hasTba -> "The Blue Alliance API"
                else -> "no API credentials"
            }
        }
    }
}
