package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.routes.ScoutingEntryRequest
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere
import java.time.Instant

@Serializable
data class ScoutingEntryRecord(
    val id: Int,
    val ownerTeamNumber: Int,
    val targetTeamNumber: Int?,
    val eventKey: String?,
    val matchKey: String?,
    val matchNumber: Int?,
    val data: JsonObject,
    val createdAt: String,
    val isPrescout: Boolean = false,
    val matchPlayedTime: Long? = null,
    val hasDiscrepancy: Boolean = false,
    val conflictingTeams: List<Int> = emptyList()
)

object ScoutingService {
    /**
     * Lists scouting entries visible to the caller.
     * SUPERADMIN sees all entries across all teams.
     * Everyone else sees their own team's entries PLUS entries from accepted alliance partner teams.
     */
    fun listEntries(session: UserSession, includePrescout: Boolean = false, all: Boolean = false): List<ScoutingEntryRecord> {
        return transaction {
            val query = ScoutingEntries.selectAll()
            if (!includePrescout) {
                query.andWhere { ScoutingEntries.isPrescout eq false }
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { ScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            val rows = query.orderBy(ScoutingEntries.createdAt, SortOrder.DESC).toList()
            val matchKeys = rows.mapNotNull { it[ScoutingEntries.matchKey] }.distinct()
            val matchTimes = if (matchKeys.isNotEmpty()) {
                com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey inList matchKeys }
                    .associate { it[com.obsidianscout.db.ApiMatches.matchKey] to (it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime]) }
            } else {
                emptyMap()
            }
            val rawRecords = rows.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                val mKey = row[ScoutingEntries.matchKey]
                val conflictStr = row[ScoutingEntries.conflictingTeams]
                val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
                ScoutingEntryRecord(
                    id = row[ScoutingEntries.id].value,
                    ownerTeamNumber = row[ScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[ScoutingEntries.targetTeamNumber],
                    eventKey = row[ScoutingEntries.eventKey],
                    matchKey = mKey,
                    matchNumber = row[ScoutingEntries.matchNumber],
                    data = data,
                    createdAt = row[ScoutingEntries.createdAt].toString(),
                    isPrescout = row[ScoutingEntries.isPrescout],
                    matchPlayedTime = matchTimes[mKey],
                    hasDiscrepancy = row[ScoutingEntries.hasDiscrepancy],
                    conflictingTeams = conflicting
                )
            }
            resolveEntriesList(rawRecords, session.teamNumber, all)
        }
    }

    fun listPrescoutEntries(session: UserSession, all: Boolean = false): List<ScoutingEntryRecord> {
        return transaction {
            val query = ScoutingEntries.selectAll()
            query.andWhere { ScoutingEntries.isPrescout eq true }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { ScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            val rows = query.orderBy(ScoutingEntries.createdAt, SortOrder.DESC).toList()
            val matchKeys = rows.mapNotNull { it[ScoutingEntries.matchKey] }.distinct()
            val matchTimes = if (matchKeys.isNotEmpty()) {
                com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey inList matchKeys }
                    .associate { it[com.obsidianscout.db.ApiMatches.matchKey] to (it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime]) }
            } else {
                emptyMap()
            }
            val rawRecords = rows.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                val mKey = row[ScoutingEntries.matchKey]
                val conflictStr = row[ScoutingEntries.conflictingTeams]
                val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
                ScoutingEntryRecord(
                    id = row[ScoutingEntries.id].value,
                    ownerTeamNumber = row[ScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[ScoutingEntries.targetTeamNumber],
                    eventKey = row[ScoutingEntries.eventKey],
                    matchKey = mKey,
                    matchNumber = row[ScoutingEntries.matchNumber],
                    data = data,
                    createdAt = row[ScoutingEntries.createdAt].toString(),
                    isPrescout = row[ScoutingEntries.isPrescout],
                    matchPlayedTime = matchTimes[mKey],
                    hasDiscrepancy = row[ScoutingEntries.hasDiscrepancy],
                    conflictingTeams = conflicting
                )
            }
            resolveEntriesList(rawRecords, session.teamNumber, all)
        }
    }

    fun resolveEntriesList(
        rawRecords: List<ScoutingEntryRecord>,
        requestingTeamNumber: Int,
        all: Boolean
    ): List<ScoutingEntryRecord> {
        if (all) {
            return rawRecords
        }
        val grouped = rawRecords.groupBy { Pair(it.matchKey ?: "", it.targetTeamNumber ?: 0) }
        return grouped.values.map { group ->
            if (group.size <= 1) {
                group.first()
            } else {
                group.find { it.ownerTeamNumber == requestingTeamNumber }
                    ?: group.first()
            }
        }
    }

    fun recalculateDiscrepancies(eventKey: String?, matchKey: String?, targetTeamNumber: Int?, isPrescout: Boolean) {
        if (eventKey == null || matchKey == null || targetTeamNumber == null) return
        transaction {
            val entries = ScoutingEntries.select {
                (ScoutingEntries.eventKey eq eventKey) and
                (ScoutingEntries.matchKey eq matchKey) and
                (ScoutingEntries.targetTeamNumber eq targetTeamNumber) and
                (ScoutingEntries.isPrescout eq isPrescout)
            }.toList()

            if (entries.isEmpty()) return@transaction

            val hasDiscrepancyVal: Boolean
            val conflictingTeamsVal: String

            if (entries.size <= 1) {
                hasDiscrepancyVal = false
                conflictingTeamsVal = ""
            } else {
                val firstData = JsonSupport.json.parseToJsonElement(entries.first()[ScoutingEntries.dataJson]).jsonObject
                val allAgree = entries.all { row ->
                    val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                    JsonSupport.scoutingDataAgrees(data, firstData)
                }
                if (allAgree) {
                    hasDiscrepancyVal = false
                    conflictingTeamsVal = ""
                } else {
                    hasDiscrepancyVal = true
                    conflictingTeamsVal = entries.map { it[ScoutingEntries.ownerTeamNumber] }.distinct().sorted().joinToString(",")
                }
            }

            ScoutingEntries.update({
                (ScoutingEntries.eventKey eq eventKey) and
                (ScoutingEntries.matchKey eq matchKey) and
                (ScoutingEntries.targetTeamNumber eq targetTeamNumber) and
                (ScoutingEntries.isPrescout eq isPrescout)
            }) {
                it[hasDiscrepancy] = hasDiscrepancyVal
                it[conflictingTeams] = conflictingTeamsVal
            }
        }
    }

    fun createEntry(
        session: UserSession,
        request: ScoutingEntryRequest,
        config: ScoutingConfig,
        isPrescout: Boolean = false
    ): ScoutingEntryRecord {
        val missing = config.fields.filter { it.required && !request.data.containsKey(it.id) }
        if (missing.isNotEmpty()) {
            val missingList = missing.joinToString(", ") { it.id }
            throw ApiException(HttpStatusCode.BadRequest, "Missing required fields: $missingList")
        }

        val meta = extractMeta(request.data)
        if (meta.targetTeamNumber == null || meta.matchKey.isNullOrBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Team and match are required")
        }

        val duplicate = transaction {
            val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
            val visibleTeams = partnerTeams + session.teamNumber
            ScoutingEntries.select {
                (ScoutingEntries.ownerTeamNumber inList visibleTeams) and
                (ScoutingEntries.targetTeamNumber eq meta.targetTeamNumber) and
                (ScoutingEntries.eventKey eq meta.eventKey) and
                (ScoutingEntries.matchKey eq meta.matchKey) and
                (ScoutingEntries.isPrescout eq isPrescout)
            }.firstOrNull { row ->
                val existingData = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                JsonSupport.scoutingDataAgrees(existingData, request.data)
            }
        }

        if (duplicate != null) {
            val mKey = duplicate[ScoutingEntries.matchKey]
            val matchPlayedTime = transaction {
                mKey?.let { mk ->
                    com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey eq mk }
                        .limit(1)
                        .map { it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime] }
                        .firstOrNull()
                }
            }
            val conflictStr = duplicate[ScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            return ScoutingEntryRecord(
                id = duplicate[ScoutingEntries.id].value,
                ownerTeamNumber = duplicate[ScoutingEntries.ownerTeamNumber],
                targetTeamNumber = duplicate[ScoutingEntries.targetTeamNumber],
                eventKey = duplicate[ScoutingEntries.eventKey],
                matchKey = mKey,
                matchNumber = duplicate[ScoutingEntries.matchNumber],
                data = JsonSupport.json.parseToJsonElement(duplicate[ScoutingEntries.dataJson]).jsonObject,
                createdAt = duplicate[ScoutingEntries.createdAt].toString(),
                isPrescout = duplicate[ScoutingEntries.isPrescout],
                matchPlayedTime = matchPlayedTime,
                hasDiscrepancy = duplicate[ScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }

        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), request.data)
        val now = Instant.now()

        val id = transaction {
            ScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = session.teamNumber
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[matchKey] = meta.matchKey
                it[matchNumber] = meta.matchNumber
                it[ScoutingEntries.dataJson] = dataJson
                it[submittedByUserId] = EntityID(session.userId, com.obsidianscout.db.Users)
                it[createdAt] = now
                it[ScoutingEntries.isPrescout] = isPrescout
            }
        }

        // Recalculate discrepancies for the group
        recalculateDiscrepancies(meta.eventKey, meta.matchKey, meta.targetTeamNumber, isPrescout)

        val matchPlayedTime = transaction {
            meta.matchKey?.let { mKey ->
                com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey eq mKey }
                    .limit(1)
                    .map { it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime] }
                    .firstOrNull()
            }
        }

        return transaction {
            val updatedRow = ScoutingEntries.select { ScoutingEntries.id eq id.value }.first()
            val conflictStr = updatedRow[ScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            ScoutingEntryRecord(
                id = id.value,
                ownerTeamNumber = session.teamNumber,
                targetTeamNumber = meta.targetTeamNumber,
                eventKey = meta.eventKey,
                matchKey = meta.matchKey,
                matchNumber = meta.matchNumber,
                data = request.data,
                createdAt = now.toString(),
                isPrescout = isPrescout,
                matchPlayedTime = matchPlayedTime,
                hasDiscrepancy = updatedRow[ScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }
    }

    private fun extractMeta(data: JsonObject): ScoutingEntryMeta {
        val eventKey = readString(data, "eventKey")
        val matchKey = readString(data, "matchKey")
        val matchNumber = readInt(data, "matchNumber")
        val targetTeamNumber = readInt(data, "targetTeamNumber")
        return ScoutingEntryMeta(eventKey, matchKey, matchNumber, targetTeamNumber)
    }

    private fun readString(data: JsonObject, fieldId: String): String? {
        val value = data[fieldId] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.content
    }

    private fun readInt(data: JsonObject, fieldId: String): Int? {
        val value = data[fieldId] as? JsonPrimitive ?: return null
        return value.content.toIntOrNull() ?: value.content.toDoubleOrNull()?.toInt()
    }

    fun updateEntry(
        session: UserSession,
        entryId: Int,
        request: ScoutingEntryRequest,
        config: ScoutingConfig
    ): ScoutingEntryRecord {
        val missing = config.fields.filter { it.required && !request.data.containsKey(it.id) }
        if (missing.isNotEmpty()) {
            val missingList = missing.joinToString(", ") { it.id }
            throw ApiException(HttpStatusCode.BadRequest, "Missing required fields: $missingList")
        }

        val meta = extractMeta(request.data)
        if (meta.targetTeamNumber == null || meta.matchKey.isNullOrBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Team and match are required")
        }
        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), request.data)

        return transaction {
            val row = ScoutingEntries.select { ScoutingEntries.id eq entryId }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Scouting entry not found")

            val ownerTeam = row[ScoutingEntries.ownerTeamNumber]
            val callerActiveAllianceId = AllianceService.getActiveAllianceId(session.teamNumber)
            val entryActiveAllianceId = AllianceService.getActiveAllianceId(ownerTeam)
            val isAllianceAdmin = callerActiveAllianceId != null && 
                                  callerActiveAllianceId == entryActiveAllianceId && 
                                  AllianceService.isAllianceAdmin(session.teamNumber, callerActiveAllianceId)

            val hasPermission = session.role == UserRole.SUPERADMIN ||
                                ownerTeam == session.teamNumber ||
                                isAllianceAdmin

            if (!hasPermission) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to edit this entry")
            }

            val oldEventKey = row[ScoutingEntries.eventKey]
            val oldMatchKey = row[ScoutingEntries.matchKey]
            val oldTargetTeamNumber = row[ScoutingEntries.targetTeamNumber]
            val oldIsPrescout = row[ScoutingEntries.isPrescout]

            ScoutingEntries.update({ ScoutingEntries.id eq entryId }) {
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[matchKey] = meta.matchKey
                it[matchNumber] = meta.matchNumber
                it[ScoutingEntries.dataJson] = dataJson
            }

            // Recalculate for both old and new groups
            recalculateDiscrepancies(oldEventKey, oldMatchKey, oldTargetTeamNumber, oldIsPrescout)
            recalculateDiscrepancies(meta.eventKey, meta.matchKey, meta.targetTeamNumber, oldIsPrescout)

            val updatedRow = ScoutingEntries.select { ScoutingEntries.id eq entryId }.first()
            val data = JsonSupport.json.parseToJsonElement(updatedRow[ScoutingEntries.dataJson]).jsonObject
            val mKey = updatedRow[ScoutingEntries.matchKey]
            val matchPlayedTime = mKey?.let { mk ->
                com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey eq mk }
                    .limit(1)
                    .map { it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime] }
                    .firstOrNull()
            }
            val conflictStr = updatedRow[ScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            ScoutingEntryRecord(
                id = updatedRow[ScoutingEntries.id].value,
                ownerTeamNumber = updatedRow[ScoutingEntries.ownerTeamNumber],
                targetTeamNumber = updatedRow[ScoutingEntries.targetTeamNumber],
                eventKey = updatedRow[ScoutingEntries.eventKey],
                matchKey = mKey,
                matchNumber = updatedRow[ScoutingEntries.matchNumber],
                data = data,
                createdAt = updatedRow[ScoutingEntries.createdAt].toString(),
                isPrescout = updatedRow[ScoutingEntries.isPrescout],
                matchPlayedTime = matchPlayedTime,
                hasDiscrepancy = updatedRow[ScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }
    }

    fun deleteEntry(session: UserSession, entryId: Int) {
        transaction {
            val row = ScoutingEntries.select { ScoutingEntries.id eq entryId }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Scouting entry not found")

            val ownerTeam = row[ScoutingEntries.ownerTeamNumber]
            val callerActiveAllianceId = AllianceService.getActiveAllianceId(session.teamNumber)
            val entryActiveAllianceId = AllianceService.getActiveAllianceId(ownerTeam)
            val isAllianceAdmin = callerActiveAllianceId != null && 
                                  callerActiveAllianceId == entryActiveAllianceId && 
                                  AllianceService.isAllianceAdmin(session.teamNumber, callerActiveAllianceId)

            val hasPermission = session.role == UserRole.SUPERADMIN ||
                                ownerTeam == session.teamNumber ||
                                isAllianceAdmin

            if (!hasPermission) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to delete this entry")
            }

            val eventKey = row[ScoutingEntries.eventKey]
            val matchKey = row[ScoutingEntries.matchKey]
            val targetTeamNumber = row[ScoutingEntries.targetTeamNumber]
            val isPrescout = row[ScoutingEntries.isPrescout]

            ScoutingEntries.deleteWhere { ScoutingEntries.id eq entryId }

            // Recalculate discrepancies
            recalculateDiscrepancies(eventKey, matchKey, targetTeamNumber, isPrescout)
        }
    }
}

private data class ScoutingEntryMeta(
    val eventKey: String?,
    val matchKey: String?,
    val matchNumber: Int?,
    val targetTeamNumber: Int?
)
