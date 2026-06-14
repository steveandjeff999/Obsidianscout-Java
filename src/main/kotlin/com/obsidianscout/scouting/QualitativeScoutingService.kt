package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.Users
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
data class QualitativeScoutingEntryRecord(
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

object QualitativeScoutingService {
    fun listEntries(session: UserSession, includePrescout: Boolean = false, all: Boolean = false): List<QualitativeScoutingEntryRecord> {
        return transaction {
            val query = QualitativeScoutingEntries.selectAll()
            if (!includePrescout) {
                query.andWhere { QualitativeScoutingEntries.isPrescout eq false }
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            val rows = query.orderBy(QualitativeScoutingEntries.createdAt, SortOrder.DESC).toList()
            val matchKeys = rows.mapNotNull { it[QualitativeScoutingEntries.matchKey] }.distinct()
            val matchTimes = if (matchKeys.isNotEmpty()) {
                com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey inList matchKeys }
                    .associate { it[com.obsidianscout.db.ApiMatches.matchKey] to (it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime]) }
            } else {
                emptyMap()
            }
            val rawRecords = rows.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                val mKey = row[QualitativeScoutingEntries.matchKey]
                val conflictStr = row[QualitativeScoutingEntries.conflictingTeams]
                val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
                QualitativeScoutingEntryRecord(
                    id = row[QualitativeScoutingEntries.id].value,
                    ownerTeamNumber = row[QualitativeScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[QualitativeScoutingEntries.targetTeamNumber],
                    eventKey = row[QualitativeScoutingEntries.eventKey],
                    matchKey = mKey,
                    matchNumber = row[QualitativeScoutingEntries.matchNumber],
                    data = data,
                    createdAt = row[QualitativeScoutingEntries.createdAt].toString(),
                    isPrescout = row[QualitativeScoutingEntries.isPrescout],
                    matchPlayedTime = matchTimes[mKey],
                    hasDiscrepancy = row[QualitativeScoutingEntries.hasDiscrepancy],
                    conflictingTeams = conflicting
                )
            }
            resolveEntriesList(rawRecords, session.teamNumber, all)
        }
    }

    fun listPrescoutEntries(session: UserSession, all: Boolean = false): List<QualitativeScoutingEntryRecord> {
        return transaction {
            val query = QualitativeScoutingEntries.selectAll()
            query.andWhere { QualitativeScoutingEntries.isPrescout eq true }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            val rows = query.orderBy(QualitativeScoutingEntries.createdAt, SortOrder.DESC).toList()
            val matchKeys = rows.mapNotNull { it[QualitativeScoutingEntries.matchKey] }.distinct()
            val matchTimes = if (matchKeys.isNotEmpty()) {
                com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey inList matchKeys }
                    .associate { it[com.obsidianscout.db.ApiMatches.matchKey] to (it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime]) }
            } else {
                emptyMap()
            }
            val rawRecords = rows.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                val mKey = row[QualitativeScoutingEntries.matchKey]
                val conflictStr = row[QualitativeScoutingEntries.conflictingTeams]
                val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
                QualitativeScoutingEntryRecord(
                    id = row[QualitativeScoutingEntries.id].value,
                    ownerTeamNumber = row[QualitativeScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[QualitativeScoutingEntries.targetTeamNumber],
                    eventKey = row[QualitativeScoutingEntries.eventKey],
                    matchKey = mKey,
                    matchNumber = row[QualitativeScoutingEntries.matchNumber],
                    data = data,
                    createdAt = row[QualitativeScoutingEntries.createdAt].toString(),
                    isPrescout = row[QualitativeScoutingEntries.isPrescout],
                    matchPlayedTime = matchTimes[mKey],
                    hasDiscrepancy = row[QualitativeScoutingEntries.hasDiscrepancy],
                    conflictingTeams = conflicting
                )
            }
            resolveEntriesList(rawRecords, session.teamNumber, all)
        }
    }

    fun resolveEntriesList(
        rawRecords: List<QualitativeScoutingEntryRecord>,
        requestingTeamNumber: Int,
        all: Boolean
    ): List<QualitativeScoutingEntryRecord> {
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
            val entries = QualitativeScoutingEntries.select {
                (QualitativeScoutingEntries.eventKey eq eventKey) and
                (QualitativeScoutingEntries.matchKey eq matchKey) and
                (QualitativeScoutingEntries.targetTeamNumber eq targetTeamNumber) and
                (QualitativeScoutingEntries.isPrescout eq isPrescout)
            }.toList()

            if (entries.isEmpty()) return@transaction

            val hasDiscrepancyVal: Boolean
            val conflictingTeamsVal: String

            if (entries.size <= 1) {
                hasDiscrepancyVal = false
                conflictingTeamsVal = ""
            } else {
                val firstData = JsonSupport.json.parseToJsonElement(entries.first()[QualitativeScoutingEntries.dataJson]).jsonObject
                val allAgree = entries.all { row ->
                    val data = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                    JsonSupport.scoutingDataAgrees(data, firstData)
                }
                if (allAgree) {
                    hasDiscrepancyVal = false
                    conflictingTeamsVal = ""
                } else {
                    hasDiscrepancyVal = true
                    conflictingTeamsVal = entries.map { it[QualitativeScoutingEntries.ownerTeamNumber] }.distinct().sorted().joinToString(",")
                }
            }

            QualitativeScoutingEntries.update({
                (QualitativeScoutingEntries.eventKey eq eventKey) and
                (QualitativeScoutingEntries.matchKey eq matchKey) and
                (QualitativeScoutingEntries.targetTeamNumber eq targetTeamNumber) and
                (QualitativeScoutingEntries.isPrescout eq isPrescout)
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
    ): QualitativeScoutingEntryRecord {
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
            QualitativeScoutingEntries.select {
                (QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams) and
                (QualitativeScoutingEntries.targetTeamNumber eq meta.targetTeamNumber) and
                (QualitativeScoutingEntries.eventKey eq meta.eventKey) and
                (QualitativeScoutingEntries.matchKey eq meta.matchKey) and
                (QualitativeScoutingEntries.isPrescout eq isPrescout)
            }.firstOrNull { row ->
                val existingData = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                JsonSupport.scoutingDataAgrees(existingData, request.data)
            }
        }

        if (duplicate != null) {
            val mKey = duplicate[QualitativeScoutingEntries.matchKey]
            val matchPlayedTime = transaction {
                mKey?.let { mk ->
                    com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey eq mk }
                        .limit(1)
                        .map { it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime] }
                        .firstOrNull()
                }
            }
            val conflictStr = duplicate[QualitativeScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            return QualitativeScoutingEntryRecord(
                id = duplicate[QualitativeScoutingEntries.id].value,
                ownerTeamNumber = duplicate[QualitativeScoutingEntries.ownerTeamNumber],
                targetTeamNumber = duplicate[QualitativeScoutingEntries.targetTeamNumber],
                eventKey = duplicate[QualitativeScoutingEntries.eventKey],
                matchKey = mKey,
                matchNumber = duplicate[QualitativeScoutingEntries.matchNumber],
                data = JsonSupport.json.parseToJsonElement(duplicate[QualitativeScoutingEntries.dataJson]).jsonObject,
                createdAt = duplicate[QualitativeScoutingEntries.createdAt].toString(),
                isPrescout = duplicate[QualitativeScoutingEntries.isPrescout],
                matchPlayedTime = matchPlayedTime,
                hasDiscrepancy = duplicate[QualitativeScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }

        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), request.data)
        val now = Instant.now()

        val id = transaction {
            QualitativeScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = session.teamNumber
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[matchKey] = meta.matchKey
                it[matchNumber] = meta.matchNumber
                it[QualitativeScoutingEntries.dataJson] = dataJson
                it[submittedByUserId] = EntityID(session.userId, Users)
                it[createdAt] = now
                it[QualitativeScoutingEntries.isPrescout] = isPrescout
            }
        }

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
            val updatedRow = QualitativeScoutingEntries.select { QualitativeScoutingEntries.id eq id.value }.first()
            val conflictStr = updatedRow[QualitativeScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            QualitativeScoutingEntryRecord(
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
                hasDiscrepancy = updatedRow[QualitativeScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }
    }

    private fun extractMeta(data: JsonObject): QualitativeScoutingMeta {
        val eventKey = readString(data, "eventKey")
        val matchKey = readString(data, "matchKey")
        val matchNumber = readInt(data, "matchNumber")
        val targetTeamNumber = readInt(data, "targetTeamNumber")
        return QualitativeScoutingMeta(eventKey, matchKey, matchNumber, targetTeamNumber)
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
    ): QualitativeScoutingEntryRecord {
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
            val row = QualitativeScoutingEntries.select { QualitativeScoutingEntries.id eq entryId }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Qualitative scouting entry not found")

            val ownerTeam = row[QualitativeScoutingEntries.ownerTeamNumber]
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

            val oldEventKey = row[QualitativeScoutingEntries.eventKey]
            val oldMatchKey = row[QualitativeScoutingEntries.matchKey]
            val oldTargetTeamNumber = row[QualitativeScoutingEntries.targetTeamNumber]
            val oldIsPrescout = row[QualitativeScoutingEntries.isPrescout]

            QualitativeScoutingEntries.update({ QualitativeScoutingEntries.id eq entryId }) {
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[matchKey] = meta.matchKey
                it[matchNumber] = meta.matchNumber
                it[QualitativeScoutingEntries.dataJson] = dataJson
            }

            recalculateDiscrepancies(oldEventKey, oldMatchKey, oldTargetTeamNumber, oldIsPrescout)
            recalculateDiscrepancies(meta.eventKey, meta.matchKey, meta.targetTeamNumber, oldIsPrescout)

            val updatedRow = QualitativeScoutingEntries.select { QualitativeScoutingEntries.id eq entryId }.first()
            val data = JsonSupport.json.parseToJsonElement(updatedRow[QualitativeScoutingEntries.dataJson]).jsonObject
            val mKey = updatedRow[QualitativeScoutingEntries.matchKey]
            val matchPlayedTime = mKey?.let { mk ->
                com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.matchKey eq mk }
                    .limit(1)
                    .map { it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime] }
                    .firstOrNull()
            }
            val conflictStr = updatedRow[QualitativeScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            QualitativeScoutingEntryRecord(
                id = updatedRow[QualitativeScoutingEntries.id].value,
                ownerTeamNumber = updatedRow[QualitativeScoutingEntries.ownerTeamNumber],
                targetTeamNumber = updatedRow[QualitativeScoutingEntries.targetTeamNumber],
                eventKey = updatedRow[QualitativeScoutingEntries.eventKey],
                matchKey = mKey,
                matchNumber = updatedRow[QualitativeScoutingEntries.matchNumber],
                data = data,
                createdAt = updatedRow[QualitativeScoutingEntries.createdAt].toString(),
                isPrescout = updatedRow[QualitativeScoutingEntries.isPrescout],
                matchPlayedTime = matchPlayedTime,
                hasDiscrepancy = updatedRow[QualitativeScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }
    }

    fun deleteEntry(session: UserSession, entryId: Int) {
        transaction {
            val row = QualitativeScoutingEntries.select { QualitativeScoutingEntries.id eq entryId }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Qualitative scouting entry not found")

            val ownerTeam = row[QualitativeScoutingEntries.ownerTeamNumber]
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

            val eventKey = row[QualitativeScoutingEntries.eventKey]
            val matchKey = row[QualitativeScoutingEntries.matchKey]
            val targetTeamNumber = row[QualitativeScoutingEntries.targetTeamNumber]
            val isPrescout = row[QualitativeScoutingEntries.isPrescout]

            QualitativeScoutingEntries.deleteWhere { QualitativeScoutingEntries.id eq entryId }

            recalculateDiscrepancies(eventKey, matchKey, targetTeamNumber, isPrescout)
        }
    }
}

private data class QualitativeScoutingMeta(
    val eventKey: String?,
    val matchKey: String?,
    val matchNumber: Int?,
    val targetTeamNumber: Int?
)