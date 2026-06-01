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
data class PitScoutingEntryRecord(
    val id: Int,
    val ownerTeamNumber: Int,
    val targetTeamNumber: Int?,
    val eventKey: String?,
    val data: JsonObject,
    val createdAt: String
)

object PitScoutingService {
    fun listEntries(session: UserSession): List<PitScoutingEntryRecord> {
        return transaction {
            val query = PitScoutingEntries.selectAll()
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            query.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                PitScoutingEntryRecord(
                    id = row[PitScoutingEntries.id].value,
                    ownerTeamNumber = row[PitScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[PitScoutingEntries.targetTeamNumber],
                    eventKey = row[PitScoutingEntries.eventKey],
                    data = data,
                    createdAt = row[PitScoutingEntries.createdAt].toString()
                )
            }
        }
    }

    fun createEntry(
        session: UserSession,
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
        val now = Instant.now()

        val id = transaction {
            PitScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = session.teamNumber
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[PitScoutingEntries.dataJson] = dataJson
                it[submittedByUserId] = EntityID(session.userId, Users)
                it[createdAt] = now
            }
        }

        return PitScoutingEntryRecord(
            id = id.value,
            ownerTeamNumber = session.teamNumber,
            targetTeamNumber = meta.targetTeamNumber,
            eventKey = meta.eventKey,
            data = request.data,
            createdAt = now.toString()
        )
    }

    private fun extractMeta(data: JsonObject): PitScoutingEntryMeta {
        val eventKey = readString(data, "eventKey")
        val targetTeamNumber = readInt(data, "targetTeamNumber")
        return PitScoutingEntryMeta(eventKey, targetTeamNumber)
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

private data class PitScoutingEntryMeta(
    val eventKey: String?,
    val targetTeamNumber: Int?
)
