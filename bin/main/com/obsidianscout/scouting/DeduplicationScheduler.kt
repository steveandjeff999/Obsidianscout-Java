package com.obsidianscout.scouting

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
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
    
    fun runCleanup() {
        log.info("Running background deduplication cleanup...")
        var matchDeleted = 0
        var pitDeleted = 0
        var qualDeleted = 0
        
        transaction {
            // 1. ScoutingEntries
            val matchRows = ScoutingEntries.selectAll().toList()
            val matchGrouped = matchRows.groupBy { row ->
                val target = row[ScoutingEntries.targetTeamNumber]
                val event = row[ScoutingEntries.eventKey]
                val match = row[ScoutingEntries.matchKey]
                val isPrescout = row[ScoutingEntries.isPrescout]
                MatchGroupKey(event, match, target, isPrescout)
            }
            matchGrouped.forEach { (key, group) ->
                val unique = mutableListOf<org.jetbrains.exposed.sql.ResultRow>()
                val toDelete = mutableListOf<Int>()
                group.forEach { row ->
                    val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                    val dup = unique.find { uRow ->
                        val uData = JsonSupport.json.parseToJsonElement(uRow[ScoutingEntries.dataJson]).jsonObject
                        JsonSupport.scoutingDataAgrees(data, uData)
                    }
                    if (dup != null) {
                        toDelete.add(row[ScoutingEntries.id].value)
                    } else {
                        unique.add(row)
                    }
                }
                if (toDelete.isNotEmpty()) {
                    ScoutingEntries.deleteWhere { ScoutingEntries.id inList toDelete }
                    matchDeleted += toDelete.size
                }
                ScoutingService.recalculateDiscrepancies(key.eventKey, key.matchKey, key.targetTeamNumber, key.isPrescout)
            }
            
            // 2. PitScoutingEntries
            val pitRows = PitScoutingEntries.selectAll().toList()
            val pitGrouped = pitRows.groupBy { row ->
                val target = row[PitScoutingEntries.targetTeamNumber]
                val event = row[PitScoutingEntries.eventKey]
                val isPrescout = row[PitScoutingEntries.isPrescout]
                PitGroupKey(event, target, isPrescout)
            }
            pitGrouped.forEach { (key, group) ->
                val unique = mutableListOf<org.jetbrains.exposed.sql.ResultRow>()
                val toDelete = mutableListOf<Int>()
                group.forEach { row ->
                    val data = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                    val dup = unique.find { uRow ->
                        val uData = JsonSupport.json.parseToJsonElement(uRow[PitScoutingEntries.dataJson]).jsonObject
                        JsonSupport.scoutingDataAgrees(data, uData)
                    }
                    if (dup != null) {
                        toDelete.add(row[PitScoutingEntries.id].value)
                    } else {
                        unique.add(row)
                    }
                }
                if (toDelete.isNotEmpty()) {
                    PitScoutingEntries.deleteWhere { PitScoutingEntries.id inList toDelete }
                    pitDeleted += toDelete.size
                }
                PitScoutingService.recalculateDiscrepancies(key.eventKey, key.targetTeamNumber, key.isPrescout)
            }
            
            // 3. QualitativeScoutingEntries
            val qualRows = QualitativeScoutingEntries.selectAll().toList()
            val qualGrouped = qualRows.groupBy { row ->
                val target = row[QualitativeScoutingEntries.targetTeamNumber]
                val event = row[QualitativeScoutingEntries.eventKey]
                val match = row[QualitativeScoutingEntries.matchKey]
                val isPrescout = row[QualitativeScoutingEntries.isPrescout]
                QualitativeGroupKey(event, match, target, isPrescout)
            }
            qualGrouped.forEach { (key, group) ->
                val unique = mutableListOf<org.jetbrains.exposed.sql.ResultRow>()
                val toDelete = mutableListOf<Int>()
                group.forEach { row ->
                    val data = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                    val dup = unique.find { uRow ->
                        val uData = JsonSupport.json.parseToJsonElement(uRow[QualitativeScoutingEntries.dataJson]).jsonObject
                        JsonSupport.scoutingDataAgrees(data, uData)
                    }
                    if (dup != null) {
                        toDelete.add(row[QualitativeScoutingEntries.id].value)
                    } else {
                        unique.add(row)
                    }
                }
                if (toDelete.isNotEmpty()) {
                    QualitativeScoutingEntries.deleteWhere { QualitativeScoutingEntries.id inList toDelete }
                    qualDeleted += toDelete.size
                }
                QualitativeScoutingService.recalculateDiscrepancies(key.eventKey, key.matchKey, key.targetTeamNumber, key.isPrescout)
            }
        }
        log.info("Deduplication cleanup complete: deleted $matchDeleted match, $pitDeleted pit, $qualDeleted qualitative entries.")
    }
}

private data class MatchGroupKey(
    val eventKey: String?,
    val matchKey: String?,
    val targetTeamNumber: Int?,
    val isPrescout: Boolean
)

private data class PitGroupKey(
    val eventKey: String?,
    val targetTeamNumber: Int?,
    val isPrescout: Boolean
)

private data class QualitativeGroupKey(
    val eventKey: String?,
    val matchKey: String?,
    val targetTeamNumber: Int?,
    val isPrescout: Boolean
)
