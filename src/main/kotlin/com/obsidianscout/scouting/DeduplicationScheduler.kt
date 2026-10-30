package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

object DeduplicationScheduler {
    private val log = LoggerFactory.getLogger("DeduplicationScheduler")
    private var scope: CoroutineScope? = null
    
    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope?.launch {
            log.info("Background deduplication scheduler started")
            // Delay 1 minute after startup before running the first cleanup
            delay(60_000L)
            while (isActive) {
                try {
                    runCleanup()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Failed to run background deduplication cleanup: ${e.message}", e)
                }
                // Run every 30 minutes
                delay(1_800_000L)
            }
        }
    }
    
    fun stop() {
        scope?.cancel()
        scope = null
    }
    
    /** Lock held for most of one 30-minute cycle so only one cluster node runs the cleanup. */
    private const val CLEANUP_LOCK_KEY = "dedup-cleanup"
    private const val CLEANUP_LOCK_MINUTES = 25L

    fun runCleanup() {
        // Skip if cluster has unavailable ranges — touching user tables during
        // initial replication causes "replica unavailable" errors on leaderless ranges.
        if (!isClusterReady()) {
            log.info("Skipping deduplication cleanup — cluster has unavailable ranges (replication in progress)")
            return
        }
        // Every node runs this scheduler. Two nodes deduplicating at once could each keep a
        // different copy and delete the other, losing the entry entirely.
        if (!com.obsidianscout.admin.NodeMonitoringService.claimNotificationLock(CLEANUP_LOCK_KEY, CLEANUP_LOCK_MINUTES)) {
            log.info("Skipping deduplication cleanup — another node is running it")
            return
        }
        log.info("Running background deduplication cleanup...")

        // Duplicates are re-submissions of the same form by the same scout (e.g. offline sync
        // posting twice). Entries from different scouts are kept even when their data matches.
        val matchDeleted = dedupeTable(
            table = ScoutingEntries,
            idColumn = ScoutingEntries.id,
            createdAt = ScoutingEntries.createdAt,
            dataJson = ScoutingEntries.dataJson,
            groupColumns = listOf(
                ScoutingEntries.ownerTeamNumber, ScoutingEntries.program, ScoutingEntries.eventKey, ScoutingEntries.matchKey,
                ScoutingEntries.targetTeamNumber, ScoutingEntries.isPrescout, ScoutingEntries.submittedByUserId
            ),
            keyWhere = { k ->
                (ScoutingEntries.ownerTeamNumber eq k.ownerTeamNumber) and (ScoutingEntries.program eq k.program) and
                    ScoutingEntries.eventKey.matches(k.eventKey) and ScoutingEntries.matchKey.matches(k.matchKey) and
                    ScoutingEntries.targetTeamNumber.matches(k.targetTeamNumber) and (ScoutingEntries.isPrescout eq k.isPrescout) and
                    (ScoutingEntries.submittedByUserId eq k.submittedByUserId)
            },
            keyOf = { row ->
                MatchGroupKey(
                    row[ScoutingEntries.ownerTeamNumber], row[ScoutingEntries.program], row[ScoutingEntries.eventKey],
                    row[ScoutingEntries.matchKey], row[ScoutingEntries.targetTeamNumber], row[ScoutingEntries.isPrescout],
                    row[ScoutingEntries.submittedByUserId].value
                )
            },
            delete = { ids -> transaction { ScoutingEntries.deleteWhere { ScoutingEntries.id inList ids }; EntrySync.recordDeletions(EntrySync.KIND_MATCH, ids.map { it.toString() }, 0, "*") } },
            recalculate = { key -> ScoutingService.recalculateDiscrepancies(key.eventKey, key.matchKey, key.targetTeamNumber, key.isPrescout) }
        )

        val pitDeleted = dedupeTable(
            table = PitScoutingEntries,
            idColumn = PitScoutingEntries.id,
            createdAt = PitScoutingEntries.createdAt,
            dataJson = PitScoutingEntries.dataJson,
            groupColumns = listOf(
                PitScoutingEntries.ownerTeamNumber, PitScoutingEntries.program, PitScoutingEntries.eventKey,
                PitScoutingEntries.targetTeamNumber, PitScoutingEntries.isPrescout, PitScoutingEntries.submittedByUserId
            ),
            keyWhere = { k ->
                (PitScoutingEntries.ownerTeamNumber eq k.ownerTeamNumber) and (PitScoutingEntries.program eq k.program) and
                    PitScoutingEntries.eventKey.matches(k.eventKey) and PitScoutingEntries.targetTeamNumber.matches(k.targetTeamNumber) and
                    (PitScoutingEntries.isPrescout eq k.isPrescout) and (PitScoutingEntries.submittedByUserId eq k.submittedByUserId)
            },
            keyOf = { row ->
                PitGroupKey(
                    row[PitScoutingEntries.ownerTeamNumber], row[PitScoutingEntries.program], row[PitScoutingEntries.eventKey],
                    row[PitScoutingEntries.targetTeamNumber], row[PitScoutingEntries.isPrescout],
                    row[PitScoutingEntries.submittedByUserId].value
                )
            },
            delete = { ids -> transaction { PitScoutingEntries.deleteWhere { PitScoutingEntries.id inList ids }; EntrySync.recordDeletions(EntrySync.KIND_PIT, ids.map { it.toString() }, 0, "*") } },
            recalculate = { key -> PitScoutingService.recalculateDiscrepancies(key.eventKey, key.targetTeamNumber, key.isPrescout) }
        )

        val qualDeleted = dedupeTable(
            table = QualitativeScoutingEntries,
            idColumn = QualitativeScoutingEntries.id,
            createdAt = QualitativeScoutingEntries.createdAt,
            dataJson = QualitativeScoutingEntries.dataJson,
            groupColumns = listOf(
                QualitativeScoutingEntries.ownerTeamNumber, QualitativeScoutingEntries.program,
                QualitativeScoutingEntries.eventKey, QualitativeScoutingEntries.matchKey,
                QualitativeScoutingEntries.targetTeamNumber, QualitativeScoutingEntries.isPrescout,
                QualitativeScoutingEntries.submittedByUserId
            ),
            keyWhere = { k ->
                (QualitativeScoutingEntries.ownerTeamNumber eq k.ownerTeamNumber) and (QualitativeScoutingEntries.program eq k.program) and
                    QualitativeScoutingEntries.eventKey.matches(k.eventKey) and QualitativeScoutingEntries.matchKey.matches(k.matchKey) and
                    QualitativeScoutingEntries.targetTeamNumber.matches(k.targetTeamNumber) and
                    (QualitativeScoutingEntries.isPrescout eq k.isPrescout) and
                    (QualitativeScoutingEntries.submittedByUserId eq k.submittedByUserId)
            },
            keyOf = { row ->
                QualitativeGroupKey(
                    row[QualitativeScoutingEntries.ownerTeamNumber], row[QualitativeScoutingEntries.program],
                    row[QualitativeScoutingEntries.eventKey], row[QualitativeScoutingEntries.matchKey],
                    row[QualitativeScoutingEntries.targetTeamNumber], row[QualitativeScoutingEntries.isPrescout],
                    row[QualitativeScoutingEntries.submittedByUserId].value
                )
            },
            delete = { ids -> transaction { QualitativeScoutingEntries.deleteWhere { QualitativeScoutingEntries.id inList ids }; EntrySync.recordDeletions(EntrySync.KIND_QUAL, ids.map { it.toString() }, 0, "*") } },
            recalculate = { key -> QualitativeScoutingService.recalculateDiscrepancies(key.eventKey, key.matchKey, key.targetTeamNumber, key.isPrescout) }
        )

        log.info("Deduplication cleanup complete: deleted $matchDeleted match, $pitDeleted pit, $qualDeleted qualitative entries.")
    }

    /**
     * Dedupes one entry table without loading it whole: a GROUP BY finds the groups holding more than one entry, and
     * only those groups' rows (with their JSON) are read, one group at a time.
     */
    private fun <K> dedupeTable(
        table: Table,
        idColumn: Column<EntityID<java.util.UUID>>,
        createdAt: Column<java.time.Instant>,
        dataJson: Column<String>,
        groupColumns: List<Expression<*>>,
        keyOf: (ResultRow) -> K,
        keyWhere: (K) -> Op<Boolean>,
        delete: (List<java.util.UUID>) -> Unit,
        recalculate: (K) -> Unit
    ): Int {
        val entries = idColumn.count()
        val keys = transaction {
            table.select(groupColumns + entries)
                .groupBy(*groupColumns.toTypedArray())
                .having { entries greater 1L }
                .map(keyOf)
        }
        var deleted = 0
        for (key in keys) {
            val rows = transaction {
                table.select(idColumn, dataJson)
                    .where { keyWhere(key) }
                    .orderBy(createdAt to SortOrder.ASC, idColumn to SortOrder.ASC)
                    .toList()
            }
            deleted += dedupe(rows, { it[idColumn].value }, { it[dataJson] }, { key }, delete, recalculate)
        }
        return deleted
    }

    /** `column = value`, or `column IS NULL` when [value] is null (SQL `= NULL` never matches). */
    private fun <T : Any> Column<T?>.matches(value: T?): Op<Boolean> = if (value == null) isNull() else (this eq value)

    /**
     * Deletes later copies of identical entries within each group, keeping the earliest
     * ([rows] must be sorted oldest first). Only groups that lost an entry are recalculated.
     */
    internal fun <R, K> dedupe(
        rows: List<R>,
        id: (R) -> java.util.UUID,
        dataJson: (R) -> String,
        keyOf: (R) -> K,
        delete: (List<java.util.UUID>) -> Unit,
        recalculate: (K) -> Unit
    ): Int {
        var deleted = 0
        rows.groupBy(keyOf).forEach { (key, group) ->
            if (group.size < 2) return@forEach
            val kept = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val toDelete = mutableListOf<java.util.UUID>()
            for (row in group) {
                // Rows whose data can't be parsed are never treated as duplicates.
                val data = runCatching { JsonSupport.json.parseToJsonElement(dataJson(row)).jsonObject }.getOrNull()
                    ?: continue
                if (kept.any { JsonSupport.scoutingDataAgrees(data, it) }) {
                    toDelete.add(id(row))
                } else {
                    kept.add(data)
                }
            }
            if (toDelete.isNotEmpty()) {
                delete(toDelete)
                deleted += toDelete.size
                recalculate(key)
            }
        }
        return deleted
    }

    /**
     * Returns true if the cluster has no fully-unavailable ranges.
     * Queries crdb_internal.node_metrics directly via DatabaseFactory so this
     * stays independent of any CockroachOrchestrator instance.
     */
    private fun isClusterReady(): Boolean {
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return false
        return try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return true
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.queryTimeout = 2
                    try { stmt.execute("SET allow_unsafe_internals = true") } catch (_: Exception) {}
                    stmt.executeQuery(
                        "SELECT value FROM crdb_internal.node_metrics WHERE name = 'ranges.unavailable' LIMIT 1"
                    ).use { rs ->
                        val unavailable = if (rs.next()) rs.getLong(1) else 0L
                        unavailable == 0L
                    }
                }
            }
        } catch (e: Exception) {
            true // If metrics are unreachable, don't block cleanup
        }
    }
}


private data class MatchGroupKey(
    val ownerTeamNumber: Int,
    val program: String,
    val eventKey: String?,
    val matchKey: String?,
    val targetTeamNumber: Int?,
    val isPrescout: Boolean,
    val submittedByUserId: java.util.UUID
)

private data class PitGroupKey(
    val ownerTeamNumber: Int,
    val program: String,
    val eventKey: String?,
    val targetTeamNumber: Int?,
    val isPrescout: Boolean,
    val submittedByUserId: java.util.UUID
)

private data class QualitativeGroupKey(
    val ownerTeamNumber: Int,
    val program: String,
    val eventKey: String?,
    val matchKey: String?,
    val targetTeamNumber: Int?,
    val isPrescout: Boolean,
    val submittedByUserId: java.util.UUID
)
