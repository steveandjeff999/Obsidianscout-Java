package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.db.PitScoutingEntries
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
data class PitScoutingEntryRecord(
    val id: Int,
    val ownerTeamNumber: Int,
    val targetTeamNumber: Int?,
    val eventKey: String?,
    val data: JsonObject,
    val createdAt: String,
    val isPrescout: Boolean = false,
    val hasDiscrepancy: Boolean = false,
    val conflictingTeams: List<Int> = emptyList()
)

object PitScoutingService {
    fun listEntries(session: UserSession, includePrescout: Boolean = false, all: Boolean = false): List<PitScoutingEntryRecord> {
        return transaction {
            val query = PitScoutingEntries.selectAll()
            if (!includePrescout) {
                query.andWhere { PitScoutingEntries.isPrescout eq false }
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            val rawRecords = query.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                val conflictStr = row[PitScoutingEntries.conflictingTeams]
                val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
                PitScoutingEntryRecord(
                    id = row[PitScoutingEntries.id].value,
                    ownerTeamNumber = row[PitScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[PitScoutingEntries.targetTeamNumber],
                    eventKey = row[PitScoutingEntries.eventKey],
                    data = data,
                    createdAt = row[PitScoutingEntries.createdAt].toString(),
                    isPrescout = row[PitScoutingEntries.isPrescout],
                    hasDiscrepancy = row[PitScoutingEntries.hasDiscrepancy],
                    conflictingTeams = conflicting
                )
            }
            resolveEntriesList(rawRecords, session.teamNumber, all)
        }
    }

    fun listPrescoutEntries(session: UserSession, all: Boolean = false): List<PitScoutingEntryRecord> {
        return transaction {
            val query = PitScoutingEntries.selectAll()
            query.andWhere { PitScoutingEntries.isPrescout eq true }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            val rawRecords = query.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                val conflictStr = row[PitScoutingEntries.conflictingTeams]
                val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
                PitScoutingEntryRecord(
                    id = row[PitScoutingEntries.id].value,
                    ownerTeamNumber = row[PitScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[PitScoutingEntries.targetTeamNumber],
                    eventKey = row[PitScoutingEntries.eventKey],
                    data = data,
                    createdAt = row[PitScoutingEntries.createdAt].toString(),
                    isPrescout = row[PitScoutingEntries.isPrescout],
                    hasDiscrepancy = row[PitScoutingEntries.hasDiscrepancy],
                    conflictingTeams = conflicting
                )
            }
            resolveEntriesList(rawRecords, session.teamNumber, all)
        }
    }

    fun resolveEntriesList(
        rawRecords: List<PitScoutingEntryRecord>,
        requestingTeamNumber: Int,
        all: Boolean
    ): List<PitScoutingEntryRecord> {
        if (all) {
            return rawRecords
        }
        val grouped = rawRecords.groupBy { Pair(it.eventKey ?: "", it.targetTeamNumber ?: 0) }
        return grouped.values.map { group ->
            if (group.size <= 1) {
                group.first()
            } else {
                group.find { it.ownerTeamNumber == requestingTeamNumber }
                    ?: group.first()
            }
        }
    }

    fun recalculateDiscrepancies(eventKey: String?, targetTeamNumber: Int?, isPrescout: Boolean) {
        if (eventKey == null || targetTeamNumber == null) return
        transaction {
            val entries = PitScoutingEntries.selectAll().where {
                (PitScoutingEntries.eventKey eq eventKey) and
                (PitScoutingEntries.targetTeamNumber eq targetTeamNumber) and
                (PitScoutingEntries.isPrescout eq isPrescout)
            }.toList()

            if (entries.isEmpty()) return@transaction

            val hasDiscrepancyVal: Boolean
            val conflictingTeamsVal: String

            if (entries.size <= 1) {
                hasDiscrepancyVal = false
                conflictingTeamsVal = ""
            } else {
                val firstData = JsonSupport.json.parseToJsonElement(entries.first()[PitScoutingEntries.dataJson]).jsonObject
                val allAgree = entries.all { row ->
                    val data = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                    JsonSupport.scoutingDataAgrees(data, firstData)
                }
                if (allAgree) {
                    hasDiscrepancyVal = false
                    conflictingTeamsVal = ""
                } else {
                    hasDiscrepancyVal = true
                    conflictingTeamsVal = entries.map { it[PitScoutingEntries.ownerTeamNumber] }.distinct().sorted().joinToString(",")
                }
            }

            PitScoutingEntries.update({
                (PitScoutingEntries.eventKey eq eventKey) and
                (PitScoutingEntries.targetTeamNumber eq targetTeamNumber) and
                (PitScoutingEntries.isPrescout eq isPrescout)
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
    ): PitScoutingEntryRecord {
        val missing = config.fields.filter { it.required && !request.data.containsKey(it.id) }
        if (missing.isNotEmpty()) {
            val missingList = missing.joinToString(", ") { it.id }
            throw ApiException(HttpStatusCode.BadRequest, "Missing required fields: $missingList")
        }

        val meta = extractMeta(request.data)
        if (meta.targetTeamNumber == null) {
            throw ApiException(HttpStatusCode.BadRequest, "Team is required")
        }

        val duplicate = transaction {
            val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
            val visibleTeams = partnerTeams + session.teamNumber
            PitScoutingEntries.selectAll().where {
                (PitScoutingEntries.ownerTeamNumber inList visibleTeams) and
                (PitScoutingEntries.targetTeamNumber eq meta.targetTeamNumber) and
                (PitScoutingEntries.eventKey eq meta.eventKey) and
                (PitScoutingEntries.isPrescout eq isPrescout)
            }.firstOrNull { row ->
                val existingData = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                JsonSupport.scoutingDataAgrees(existingData, request.data)
            }
        }

        if (duplicate != null) {
            val conflictStr = duplicate[PitScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            return PitScoutingEntryRecord(
                id = duplicate[PitScoutingEntries.id].value,
                ownerTeamNumber = duplicate[PitScoutingEntries.ownerTeamNumber],
                targetTeamNumber = duplicate[PitScoutingEntries.targetTeamNumber],
                eventKey = duplicate[PitScoutingEntries.eventKey],
                data = JsonSupport.json.parseToJsonElement(duplicate[PitScoutingEntries.dataJson]).jsonObject,
                createdAt = duplicate[PitScoutingEntries.createdAt].toString(),
                isPrescout = duplicate[PitScoutingEntries.isPrescout],
                hasDiscrepancy = duplicate[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }

        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), request.data)
        val now = Instant.now()

        val id = transaction {
            PitScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = session.teamNumber
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[PitScoutingEntries.dataJson] = dataJson
                it[submittedByUserId] = EntityID(session.userId, Users)
                it[createdAt] = now
                it[PitScoutingEntries.isPrescout] = isPrescout
            }
        }

        recalculateDiscrepancies(meta.eventKey, meta.targetTeamNumber, isPrescout)

        return transaction {
            val updatedRow = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq id.value }.first()
            val conflictStr = updatedRow[PitScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            PitScoutingEntryRecord(
                id = id.value,
                ownerTeamNumber = session.teamNumber,
                targetTeamNumber = meta.targetTeamNumber,
                eventKey = meta.eventKey,
                data = request.data,
                createdAt = now.toString(),
                isPrescout = isPrescout,
                hasDiscrepancy = updatedRow[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }
    }

    private fun extractMeta(data: JsonObject): PitScoutingEntryMeta {
        val eventKey = readString(data, "eventKey")
        val targetTeamNumber = readInt(data, "targetTeamNumber")
        return PitScoutingEntryMeta(eventKey, targetTeamNumber)
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
    ): PitScoutingEntryRecord {
        val missing = config.fields.filter { it.required && !request.data.containsKey(it.id) }
        if (missing.isNotEmpty()) {
            val missingList = missing.joinToString(", ") { it.id }
            throw ApiException(HttpStatusCode.BadRequest, "Missing required fields: $missingList")
        }

        val meta = extractMeta(request.data)
        if (meta.targetTeamNumber == null) {
            throw ApiException(HttpStatusCode.BadRequest, "Team is required")
        }
        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), request.data)

        return transaction {
            val row = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq entryId }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Pit scouting entry not found")

            val ownerTeam = row[PitScoutingEntries.ownerTeamNumber]
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

            val oldEventKey = row[PitScoutingEntries.eventKey]
            val oldTargetTeamNumber = row[PitScoutingEntries.targetTeamNumber]
            val oldIsPrescout = row[PitScoutingEntries.isPrescout]

            PitScoutingEntries.update({ PitScoutingEntries.id eq entryId }) {
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[PitScoutingEntries.dataJson] = dataJson
            }

            recalculateDiscrepancies(oldEventKey, oldTargetTeamNumber, oldIsPrescout)
            recalculateDiscrepancies(meta.eventKey, meta.targetTeamNumber, oldIsPrescout)

            val updatedRow = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq entryId }.first()
            val data = JsonSupport.json.parseToJsonElement(updatedRow[PitScoutingEntries.dataJson]).jsonObject
            val conflictStr = updatedRow[PitScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            PitScoutingEntryRecord(
                id = updatedRow[PitScoutingEntries.id].value,
                ownerTeamNumber = updatedRow[PitScoutingEntries.ownerTeamNumber],
                targetTeamNumber = updatedRow[PitScoutingEntries.targetTeamNumber],
                eventKey = updatedRow[PitScoutingEntries.eventKey],
                data = data,
                createdAt = updatedRow[PitScoutingEntries.createdAt].toString(),
                isPrescout = updatedRow[PitScoutingEntries.isPrescout],
                hasDiscrepancy = updatedRow[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting
            )
        }
    }

    fun deleteEntry(session: UserSession, entryId: Int) {
        transaction {
            val row = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq entryId }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Pit scouting entry not found")

            val ownerTeam = row[PitScoutingEntries.ownerTeamNumber]
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

            val eventKey = row[PitScoutingEntries.eventKey]
            val targetTeamNumber = row[PitScoutingEntries.targetTeamNumber]
            val isPrescout = row[PitScoutingEntries.isPrescout]

            PitScoutingEntries.deleteWhere { PitScoutingEntries.id eq entryId }

            recalculateDiscrepancies(eventKey, targetTeamNumber, isPrescout)
        }
    }
}

private data class PitScoutingEntryMeta(
    val eventKey: String?,
    val targetTeamNumber: Int?
)
