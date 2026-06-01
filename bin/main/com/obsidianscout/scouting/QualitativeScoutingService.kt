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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
    val createdAt: String
)

object QualitativeScoutingService {
    fun listEntries(session: UserSession): List<QualitativeScoutingEntryRecord> {
        return transaction {
            val query = QualitativeScoutingEntries.selectAll()
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            query.orderBy(QualitativeScoutingEntries.createdAt, SortOrder.DESC).map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                QualitativeScoutingEntryRecord(
                    id = row[QualitativeScoutingEntries.id].value,
                    ownerTeamNumber = row[QualitativeScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[QualitativeScoutingEntries.targetTeamNumber],
                    eventKey = row[QualitativeScoutingEntries.eventKey],
                    matchKey = row[QualitativeScoutingEntries.matchKey],
                    matchNumber = row[QualitativeScoutingEntries.matchNumber],
                    data = data,
                    createdAt = row[QualitativeScoutingEntries.createdAt].toString()
                )
            }
        }
    }

    fun createEntry(
        session: UserSession,
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
            }
        }

        return QualitativeScoutingEntryRecord(
            id = id.value,
            ownerTeamNumber = session.teamNumber,
            targetTeamNumber = meta.targetTeamNumber,
            eventKey = meta.eventKey,
            matchKey = meta.matchKey,
            matchNumber = meta.matchNumber,
            data = request.data,
            createdAt = now.toString()
        )
    }

    private fun extractMeta(data: JsonObject): QualitativeScoutingMeta {
        val eventKey = readString(data, "eventKey")
        val matchKey = readString(data, "matchKey")
        val matchNumber = readInt(data, "matchNumber")
        val targetTeamNumber = readInt(data, "targetTeamNumber")
        return QualitativeScoutingMeta(eventKey, matchKey, matchNumber, targetTeamNumber)
    }

    private fun readString(data: JsonObject, fieldId: String): String? {
        val value = data[fieldId] as? JsonPrimitive ?: return null
        return value.content
    }

    private fun readInt(data: JsonObject, fieldId: String): Int? {
        val value = data[fieldId] as? JsonPrimitive ?: return null
        return value.content.toIntOrNull() ?: value.content.toDoubleOrNull()?.toInt()
    }
}

private data class QualitativeScoutingMeta(
    val eventKey: String?,
    val matchKey: String?,
    val matchNumber: Int?,
    val targetTeamNumber: Int?
)