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
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
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
    val username: String? = null
)

object PitScoutingService {
    fun listEntries(session: UserSession, includePrescout: Boolean = false, all: Boolean = false): List<PitScoutingEntryRecord> {
        return readTransaction {
            val query = PitScoutingEntries.selectAll()
            if (!includePrescout) {
                query.andWhere { PitScoutingEntries.isPrescout eq false }
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            val rows = query.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).toList()
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
                    username = userNames[row[PitScoutingEntries.submittedByUserId].value]
                )
            }
            resolveEntriesList(rawRecords, session.teamNumber, all)
        }
    }

    fun listPrescoutEntries(session: UserSession, all: Boolean = false): List<PitScoutingEntryRecord> {
        return readTransaction {
            val query = PitScoutingEntries.selectAll()
            query.andWhere { PitScoutingEntries.isPrescout eq true }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            val rows = query.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).toList()
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
                    username = userNames[row[PitScoutingEntries.submittedByUserId].value]
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
                id = duplicate[PitScoutingEntries.id].value.toString(),
                ownerTeamNumber = duplicate[PitScoutingEntries.ownerTeamNumber],
                targetTeamNumber = duplicate[PitScoutingEntries.targetTeamNumber],
                eventKey = duplicate[PitScoutingEntries.eventKey],
                data = JsonSupport.json.parseToJsonElement(duplicate[PitScoutingEntries.dataJson]).jsonObject,
                createdAt = duplicate[PitScoutingEntries.createdAt].toString(),
                isPrescout = duplicate[PitScoutingEntries.isPrescout],
                hasDiscrepancy = duplicate[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting,
                username = session.username
            )
        }

        val cleanData = sanitizePitData(request.data)
        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), cleanData)
        val now = Instant.now()
        val callerUuid = UUID.fromString(session.userId)

        val id = transaction {
            PitScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = session.teamNumber
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[PitScoutingEntries.dataJson] = dataJson
                it[submittedByUserId] = EntityID(callerUuid, Users)
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
                id = id.value.toString(),
                ownerTeamNumber = session.teamNumber,
                targetTeamNumber = meta.targetTeamNumber,
                eventKey = meta.eventKey,
                data = cleanData,
                createdAt = now.toString(),
                isPrescout = isPrescout,
                hasDiscrepancy = updatedRow[PitScoutingEntries.hasDiscrepancy],
                conflictingTeams = conflicting,
                username = session.username
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

        return transaction {
            val row = PitScoutingEntries.selectAll().where { PitScoutingEntries.id eq entryUuid }.firstOrNull()
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

            PitScoutingEntries.update({ PitScoutingEntries.id eq entryUuid }) {
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[PitScoutingEntries.dataJson] = dataJson
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
                conflictingTeams = conflicting
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

            PitScoutingEntries.deleteWhere { PitScoutingEntries.id eq entryUuid }

            recalculateDiscrepancies(eventKey, targetTeamNumber, isPrescout)
        }
    }
}

private fun sanitizePitData(data: JsonObject): JsonObject {
    val sanitized = mutableMapOf<String, JsonElement>()
    for ((key, value) in data) {
        if (value is JsonPrimitive && value.isString) {
            val content = value.content
            if (content.startsWith("data:image/") || (content.length > 5000 && content.startsWith("/9j/"))) {
                sanitized[key] = JsonPrimitive(sanitizeAndDownscaleBase64Image(content))
                continue
            }
        }
        sanitized[key] = value
    }
    return JsonObject(sanitized)
}

private fun sanitizeAndDownscaleBase64Image(dataUriOrBase64: String, maxDim: Int = 1280): String {
    try {
        val base64Data = if (dataUriOrBase64.contains(",")) {
            dataUriOrBase64.substringAfter(",")
        } else {
            dataUriOrBase64
        }
        val bytes = Base64.getDecoder().decode(base64Data.trim())
        if (bytes.size < 4) return dataUriOrBase64
        val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val isWebp = bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()

        if (!isJpeg && !isPng && !isWebp) {
            return dataUriOrBase64
        }

        val originalImage: BufferedImage? = ImageIO.read(ByteArrayInputStream(bytes))
        if (originalImage == null) return dataUriOrBase64

        var width = originalImage.width
        var height = originalImage.height

        val needsResize = width > maxDim || height > maxDim
        if (needsResize) {
            if (width > height) {
                height = ((height.toDouble() * maxDim) / width).toInt().coerceAtLeast(1)
                width = maxDim
            } else {
                width = ((width.toDouble() * maxDim) / height).toInt().coerceAtLeast(1)
                height = maxDim
            }
        }

        // Re-render to clean RGB BufferedImage to strip all EXIF/GPS/malicious chunks
        val cleanImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2d: Graphics2D = cleanImage.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.drawImage(originalImage, 0, 0, width, height, null)
        g2d.dispose()

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(cleanImage, "jpg", outputStream)
        val cleanBytes = outputStream.toByteArray()
        val cleanBase64 = Base64.getEncoder().encodeToString(cleanBytes)
        return "data:image/jpeg;base64,$cleanBase64"
    } catch (e: Exception) {
        return dataUriOrBase64
    }
}

private data class PitScoutingEntryMeta(
    val eventKey: String?,
    val targetTeamNumber: Int?
)
