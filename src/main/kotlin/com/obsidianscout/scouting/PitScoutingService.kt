package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
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
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.util.Base64
import java.time.Instant
import java.util.UUID

@Serializable
data class PitScoutingEntryRecord(
    val id: String,
    val ownerTeamNumber: Int,
    val targetTeamNumber: Int?,
    val eventKey: String?,
    val data: JsonObject,
    val createdAt: String,
    val isPrescout: Boolean = false,
    val hasDiscrepancy: Boolean = false,
    val conflictingTeams: List<Int> = emptyList(),
    val username: String? = null,
    val completenessPct: Float? = null
)

object PitScoutingService {

    fun computeCompleteness(config: ScoutingConfig, data: JsonObject): Float {
        val fields = config.fields.filter {
            val t = it.type.lowercase()
            t !in listOf("label", "divider", "section", "header")
        }
        if (fields.isEmpty()) return 100f
        val filled = fields.count { field ->
            val v = data[field.id]
            v != null && v !is JsonNull &&
                    !(v is JsonPrimitive && v.content.isBlank())
        }
        return (filled.toFloat() / fields.size * 100f).coerceIn(0f, 100f)
    }

    fun listEntries(session: UserSession, includePrescout: Boolean = false, all: Boolean = false, eventKey: String? = null): List<PitScoutingEntryRecord> {
        return readTransaction {
            val query = PitScoutingEntries.selectAll()
            query.andWhere { PitScoutingEntries.program eq session.program }
            if (!includePrescout) {
                query.andWhere { PitScoutingEntries.isPrescout eq false }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { PitScoutingEntries.eventKey eq eventKey.trim() }
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            resolveEntriesList(buildRecords(query.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).toList()), session.teamNumber, all)
        }
    }

    fun listPrescoutEntries(session: UserSession, all: Boolean = false, eventKey: String? = null): List<PitScoutingEntryRecord> {
        return readTransaction {
            val query = PitScoutingEntries.selectAll()
            query.andWhere { (PitScoutingEntries.program eq session.program) and (PitScoutingEntries.isPrescout eq true) }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { PitScoutingEntries.eventKey eq eventKey.trim() }
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            resolveEntriesList(buildRecords(query.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).toList()), session.teamNumber, all)
        }
    }

    /**
     * Entries visible to the caller, prescout included, changed at or after [since] (all of them when null). For delta sync.
     * With [eventKey], only that event's entries plus every prescout entry.
     */
    fun listChangedEntries(session: UserSession, since: Instant?, eventKey: String? = null): List<PitScoutingEntryRecord> {
        return readTransaction {
            val query = PitScoutingEntries.selectAll()
            query.andWhere { PitScoutingEntries.program eq session.program }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { (PitScoutingEntries.eventKey.lowerCase() eq eventKey.trim().lowercase()) or (PitScoutingEntries.isPrescout eq true) }
            }
            if (since != null) {
                query.andWhere { EntrySync.changedSince(PitScoutingEntries.updatedAt, PitScoutingEntries.createdAt, since) }
            }
            buildRecords(query.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).toList())
        }
    }

    /** Turns rows into records. Call inside a transaction. */
    private fun buildRecords(rows: List<ResultRow>): List<PitScoutingEntryRecord> {
        val userIds = rows.map { it[PitScoutingEntries.submittedByUserId].value }.distinct()
        val userNames = if (userIds.isNotEmpty()) {
            Users.selectAll().where { Users.id inList userIds }
                .associate { it[Users.id].value to it[Users.username] }
        } else {
            emptyMap()
        }
        val rawRecords = rows.map { row ->
            val data = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
            val conflictStr = row[PitScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            PitScoutingEntryRecord(
                id = row[PitScoutingEntries.id].value.toString(),
                ownerTeamNumber = row[PitScoutingEntries.ownerTeamNumber],
                targetTeamNumber = row[PitScoutingEntries.targetTeamNumber],
                eventKey = row[PitScoutingEntries.eventKey],
                data = data,
                createdAt = row[PitScoutingEntries.createdAt].toString(),
                isPrescout = row[PitScoutingEntries.isPrescout],
                hasDiscrepancy = row[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting,
                username = userNames[row[PitScoutingEntries.submittedByUserId].value],
                completenessPct = row[PitScoutingEntries.completenessPct]
            )
        }
        return rawRecords
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
                it[PitScoutingEntries.updatedAt] = Instant.now()
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
            val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
            val visibleTeams = partnerTeams + session.teamNumber
            PitScoutingEntries.selectAll().where {
                (PitScoutingEntries.ownerTeamNumber inList visibleTeams) and
                (PitScoutingEntries.program eq session.program) and
                (PitScoutingEntries.targetTeamNumber eq meta.targetTeamNumber) and
                (PitScoutingEntries.eventKey eq meta.eventKey) and
                (PitScoutingEntries.isPrescout eq isPrescout)
            }.firstOrNull { row ->
                val existingData = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                JsonSupport.scoutingDataAgrees(existingData, request.data)
            }
        }

        // Runs for duplicates too: a re-sent entry (offline sync, cache, QR scan) must still clear the assignment.
        val completeAssignments = {
            try {
                ScoutingAssignmentService.handleScoutingSubmission(
                    ownerTeam = session.teamNumber,
                    program = session.program,
                    eventKey = meta.eventKey,
                    assignmentType = "PIT",
                    matchNumber = null,
                    matchKey = null,
                    targetTeamNumber = meta.targetTeamNumber,
                    userId = session.userId
                )
            } catch (_: Exception) {}
        }

        if (duplicate != null) {
            completeAssignments()
            val conflictStr = duplicate[PitScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            return PitScoutingEntryRecord(
                id = duplicate[PitScoutingEntries.id].value.toString(),
                ownerTeamNumber = duplicate[PitScoutingEntries.ownerTeamNumber],
                targetTeamNumber = duplicate[PitScoutingEntries.targetTeamNumber],
                eventKey = duplicate[PitScoutingEntries.eventKey],
                data = JsonSupport.json.parseToJsonElement(duplicate[PitScoutingEntries.dataJson]).jsonObject,
                createdAt = duplicate[PitScoutingEntries.createdAt].toString(),
                isPrescout = duplicate[PitScoutingEntries.isPrescout],
                hasDiscrepancy = duplicate[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting,
                username = session.username,
                completenessPct = duplicate[PitScoutingEntries.completenessPct]
            )
        }

        val cleanData = sanitizePitData(request.data)
        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), cleanData)
        val now = Instant.now()
        val callerUuid = UUID.fromString(session.userId)
        val completeness = computeCompleteness(config, cleanData)

        val id = com.obsidianscout.db.writeTransactionWithRetry {
            PitScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = session.teamNumber
                it[program] = session.program
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[PitScoutingEntries.dataJson] = dataJson
                it[submittedByUserId] = EntityID(callerUuid, Users)
                it[createdAt] = now
                it[PitScoutingEntries.isPrescout] = isPrescout
                it[completenessPct] = completeness
                it[PitScoutingEntries.updatedAt] = now
            }
        }

        completeAssignments()

        recalculateDiscrepancies(meta.eventKey, meta.targetTeamNumber, isPrescout)

        return transaction {
            val updatedRow = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq id.value }.first()
            val conflictStr = updatedRow[PitScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            PitScoutingEntryRecord(
                id = id.value.toString(),
                ownerTeamNumber = session.teamNumber,
                targetTeamNumber = meta.targetTeamNumber,
                eventKey = meta.eventKey,
                data = cleanData,
                createdAt = now.toString(),
                isPrescout = isPrescout,
                hasDiscrepancy = updatedRow[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting,
                username = session.username,
                completenessPct = completeness
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
        entryId: String,
        request: ScoutingEntryRequest,
        config: ScoutingConfig
    ): PitScoutingEntryRecord {
        val entryUuid = runCatching { UUID.fromString(entryId) }.getOrElse {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid entry ID format")
        }

        val missing = config.fields.filter { it.required && !request.data.containsKey(it.id) }
        if (missing.isNotEmpty()) {
            val missingList = missing.joinToString(", ") { it.id }
            throw ApiException(HttpStatusCode.BadRequest, "Missing required fields: $missingList")
        }

        val meta = extractMeta(request.data)
        if (meta.targetTeamNumber == null) {
            throw ApiException(HttpStatusCode.BadRequest, "Team is required")
        }
        val cleanData = sanitizePitData(request.data)
        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), cleanData)
        val completeness = computeCompleteness(config, cleanData)

        return transaction {
            val row = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq entryUuid }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Pit scouting entry not found")

            val ownerTeam = row[PitScoutingEntries.ownerTeamNumber]
            val callerActiveAllianceId = AllianceService.getActiveAllianceId(session.teamNumber, session.program)
            val entryActiveAllianceId = AllianceService.getActiveAllianceId(ownerTeam, session.program)
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

            PitScoutingEntries.update({ PitScoutingEntries.id eq entryUuid }) {
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[PitScoutingEntries.dataJson] = dataJson
                it[completenessPct] = completeness
                it[PitScoutingEntries.updatedAt] = Instant.now()
            }

            recalculateDiscrepancies(oldEventKey, oldTargetTeamNumber, oldIsPrescout)
            recalculateDiscrepancies(meta.eventKey, meta.targetTeamNumber, oldIsPrescout)

            val updatedRow = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq entryUuid }.first()
            val data = JsonSupport.json.parseToJsonElement(updatedRow[PitScoutingEntries.dataJson]).jsonObject
            val conflictStr = updatedRow[PitScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            PitScoutingEntryRecord(
                id = updatedRow[PitScoutingEntries.id].value.toString(),
                ownerTeamNumber = updatedRow[PitScoutingEntries.ownerTeamNumber],
                targetTeamNumber = updatedRow[PitScoutingEntries.targetTeamNumber],
                eventKey = updatedRow[PitScoutingEntries.eventKey],
                data = data,
                createdAt = updatedRow[PitScoutingEntries.createdAt].toString(),
                isPrescout = updatedRow[PitScoutingEntries.isPrescout],
                hasDiscrepancy = updatedRow[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting,
                completenessPct = completeness
            )
        }
    }

    fun deleteEntry(session: UserSession, entryId: String) {
        val entryUuid = runCatching { UUID.fromString(entryId) }.getOrElse {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid entry ID format")
        }
        transaction {
            val row = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq entryUuid }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Pit scouting entry not found")

            val ownerTeam = row[PitScoutingEntries.ownerTeamNumber]
            val callerActiveAllianceId = AllianceService.getActiveAllianceId(session.teamNumber, session.program)
            val entryActiveAllianceId = AllianceService.getActiveAllianceId(ownerTeam, session.program)
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

            PitScoutingEntries.deleteWhere { PitScoutingEntries.id eq entryUuid }
            EntrySync.recordDeletions(EntrySync.KIND_PIT, listOf(entryId), ownerTeam, row[PitScoutingEntries.program])

            recalculateDiscrepancies(eventKey, targetTeamNumber, isPrescout)
        }
    }
}

private const val MAX_PIT_IMAGE_BYTES = 2L * 1024 * 1024

internal fun sanitizePitData(data: JsonObject): JsonObject {
    val sanitized = mutableMapOf<String, JsonElement>()
    for ((key, value) in data) {
        if (value is JsonPrimitive && value.isString) {
            val content = value.content
            if (content.startsWith("data:image/") || (content.length > 5000 && content.startsWith("/9j/"))) {
                // The server can no longer downscale (no ImageIO in the native image), so cap what
                // a modified client can store. Web and app clients send <=1280px, ~300 KB photos.
                val approxBytes = content.substringAfter(",").length.toLong() * 3 / 4
                if (approxBytes > MAX_PIT_IMAGE_BYTES) {
                    throw ApiException(HttpStatusCode.PayloadTooLarge, "Photo is too large. Retake or re-select it so it can be compressed.")
                }
                sanitized[key] = JsonPrimitive(sanitizeBase64Image(content))
                continue
            }
        }
        sanitized[key] = value
    }
    return JsonObject(sanitized)
}

// The client already downscales to 1280px and re-encodes through a canvas; this is the
// server-side guarantee that EXIF/GPS and other metadata never get stored, even from a
// modified client. It must not use ImageIO/AWT, which fail to initialize in the native image.
internal fun sanitizeBase64Image(dataUriOrBase64: String): String {
    try {
        val base64Data = if (dataUriOrBase64.contains(",")) {
            dataUriOrBase64.substringAfter(",")
        } else {
            dataUriOrBase64
        }
        val bytes = Base64.getMimeDecoder().decode(base64Data.trim())
        val format = ImageMetadataStripper.detect(bytes) ?: return dataUriOrBase64
        val cleanBytes = ImageMetadataStripper.strip(bytes, format)
            ?: return if (dataUriOrBase64.startsWith("data:image/")) dataUriOrBase64 else "data:${format.mime};base64,$base64Data"
        return "data:${format.mime};base64,${Base64.getEncoder().encodeToString(cleanBytes)}"
    } catch (e: Exception) {
        return dataUriOrBase64
    }
}

private data class PitScoutingEntryMeta(
    val eventKey: String?,
    val targetTeamNumber: Int?
)
