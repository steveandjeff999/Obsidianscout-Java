package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
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
import java.time.Instant
import java.util.UUID

@Serializable
data class QualitativeScoutingEntryRecord(
    val id: String,
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
    val conflictingTeams: List<Int> = emptyList(),
    val username: String? = null,
    val completenessPct: Float? = null
)

object QualitativeScoutingService {

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

    fun listEntries(session: UserSession, includePrescout: Boolean = false, all: Boolean = false, eventKey: String? = null): List<QualitativeScoutingEntryRecord> {
        return readTransaction {
            val query = QualitativeScoutingEntries.selectAll()
            query.andWhere { QualitativeScoutingEntries.program eq session.program }
            if (!includePrescout) {
                query.andWhere { QualitativeScoutingEntries.isPrescout eq false }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { QualitativeScoutingEntries.eventKey eq eventKey.trim() }
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            resolveEntriesList(buildRecords(query.orderBy(QualitativeScoutingEntries.createdAt, SortOrder.DESC).toList(), session.teamNumber, session.program), session.teamNumber, all)
        }
    }

    fun listPrescoutEntries(session: UserSession, all: Boolean = false, eventKey: String? = null): List<QualitativeScoutingEntryRecord> {
        return readTransaction {
            val query = QualitativeScoutingEntries.selectAll()
            query.andWhere { (QualitativeScoutingEntries.program eq session.program) and (QualitativeScoutingEntries.isPrescout eq true) }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { QualitativeScoutingEntries.eventKey eq eventKey.trim() }
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            resolveEntriesList(buildRecords(query.orderBy(QualitativeScoutingEntries.createdAt, SortOrder.DESC).toList(), session.teamNumber, session.program), session.teamNumber, all)
        }
    }

    /**
     * Entries visible to the caller, prescout included, changed at or after [since] (all of them when null). For delta sync.
     * With [eventKey], only that event's entries plus every prescout entry.
     */
    fun listChangedEntries(session: UserSession, since: Instant?, eventKey: String? = null): List<QualitativeScoutingEntryRecord> {
        return readTransaction {
            val query = QualitativeScoutingEntries.selectAll()
            query.andWhere { QualitativeScoutingEntries.program eq session.program }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
                val visibleTeams = partnerTeams + session.teamNumber
                query.andWhere { QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { (QualitativeScoutingEntries.eventKey.lowerCase() eq eventKey.trim().lowercase()) or (QualitativeScoutingEntries.isPrescout eq true) }
            }
            if (since != null) {
                query.andWhere { EntrySync.changedSince(QualitativeScoutingEntries.updatedAt, QualitativeScoutingEntries.createdAt, since) }
            }
            buildRecords(query.orderBy(QualitativeScoutingEntries.createdAt, SortOrder.DESC).toList(), session.teamNumber, session.program)
        }
    }

    /** Turns rows into records. Call inside a transaction. */
    private fun buildRecords(rows: List<ResultRow>, teamNumber: Int = 0, program: String = "FRC"): List<QualitativeScoutingEntryRecord> {
        val matchKeys = rows.mapNotNull { it[QualitativeScoutingEntries.matchKey] }.distinct()
        val matchTimes = if (matchKeys.isNotEmpty()) {
            val matchRows = runCatching {
                com.obsidianscout.integrations.TeamCacheOverrideService.matchRowsByKeys(matchKeys, teamNumber, program)
            }.getOrElse {
                com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.matchKey inList matchKeys }.toList()
            }
            matchRows.associate { it[com.obsidianscout.db.ApiMatches.matchKey] to (it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime]) }
        } else {
            emptyMap()
        }
        val userIds = rows.map { it[QualitativeScoutingEntries.submittedByUserId].value }.distinct()
        val userNames = if (userIds.isNotEmpty()) {
            Users.selectAll().where { Users.id inList userIds }
                .associate { it[Users.id].value to it[Users.username] }
        } else {
            emptyMap()
        }
        val rawRecords = rows.map { row ->
            val data = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
            val mKey = row[QualitativeScoutingEntries.matchKey]
            val conflictStr = row[QualitativeScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            QualitativeScoutingEntryRecord(
                id = row[QualitativeScoutingEntries.id].value.toString(),
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
                conflictingTeams = conflicting,
                username = userNames[row[QualitativeScoutingEntries.submittedByUserId].value],
                completenessPct = row[QualitativeScoutingEntries.completenessPct]
            )
        }
        return rawRecords
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
            val entries = QualitativeScoutingEntries.selectAll().where {
                (QualitativeScoutingEntries.eventKey eq eventKey) and
                (QualitativeScoutingEntries.matchKey eq matchKey) and
                (QualitativeScoutingEntries.targetTeamNumber eq targetTeamNumber) and
                (QualitativeScoutingEntries.isPrescout eq isPrescout)
            }.toList()

            if (entries.isEmpty()) return@transaction

            val now = Instant.now()
            for ((prog, progEntries) in entries.groupBy { it[QualitativeScoutingEntries.program] }) {
                for ((ownerTeam, teamRows) in progEntries.groupBy { it[QualitativeScoutingEntries.ownerTeamNumber] }) {
                    val visibleTeams = AllianceService.getAlliancePartnerTeams(ownerTeam, prog) + ownerTeam
                    val cohort = progEntries.filter { it[QualitativeScoutingEntries.ownerTeamNumber] in visibleTeams }

                    val hasDiscrepancyVal: Boolean
                    val conflictingTeamsVal: String

                    if (cohort.size <= 1) {
                        hasDiscrepancyVal = false
                        conflictingTeamsVal = ""
                    } else {
                        val firstData = JsonSupport.json.parseToJsonElement(cohort.first()[QualitativeScoutingEntries.dataJson]).jsonObject
                        val allAgree = cohort.all { row ->
                            val data = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                            JsonSupport.scoutingDataAgrees(data, firstData)
                        }
                        if (allAgree) {
                            hasDiscrepancyVal = false
                            conflictingTeamsVal = ""
                        } else {
                            hasDiscrepancyVal = true
                            conflictingTeamsVal = cohort.map { it[QualitativeScoutingEntries.ownerTeamNumber] }.distinct().sorted().joinToString(",")
                        }
                    }

                    val rowIds = teamRows.map { it[QualitativeScoutingEntries.id].value }
                    QualitativeScoutingEntries.update({ QualitativeScoutingEntries.id inList rowIds }) {
                        it[hasDiscrepancy] = hasDiscrepancyVal
                        it[conflictingTeams] = conflictingTeamsVal
                        it[QualitativeScoutingEntries.updatedAt] = now
                    }
                }
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

        val kindKey = if (isPrescout) "qual-prescout" else "qual"
        // A retry of an upload this node already took: same entry, whatever the content comparison says.
        val knownEntryId = RecentSubmissions.find(kindKey, session, request.clientId)

        val duplicate = transaction {
            val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
            val visibleTeams = partnerTeams + session.teamNumber
            QualitativeScoutingEntries.selectAll().where {
                (QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams) and
                (QualitativeScoutingEntries.program eq session.program) and
                (QualitativeScoutingEntries.targetTeamNumber eq meta.targetTeamNumber) and
                (QualitativeScoutingEntries.eventKey eq meta.eventKey) and
                (QualitativeScoutingEntries.matchKey eq meta.matchKey) and
                (QualitativeScoutingEntries.isPrescout eq isPrescout)
            }.toList().let { rows ->
                rows.firstOrNull { knownEntryId != null && it[QualitativeScoutingEntries.id].value.toString() == knownEntryId }
                    ?: rows.firstOrNull { row ->
                        val existingData = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                        JsonSupport.scoutingDataAgrees(existingData, request.data)
                    }
            }
        }

        // Runs for duplicates too: a re-sent entry (offline sync, cache, QR scan) must still clear the assignment.
        val completeAssignments = {
            try {
                ScoutingAssignmentService.handleScoutingSubmission(
                    ownerTeam = session.teamNumber,
                    program = session.program,
                    eventKey = meta.eventKey,
                    assignmentType = "QUALITATIVE",
                    matchNumber = meta.matchNumber,
                    matchKey = meta.matchKey,
                    targetTeamNumber = meta.targetTeamNumber,
                    userId = session.userId
                )
            } catch (_: Exception) {}
        }

        if (duplicate != null) {
            RecentSubmissions.remember(kindKey, session, request.clientId, duplicate[QualitativeScoutingEntries.id].value.toString())
            completeAssignments()
            val mKey = duplicate[QualitativeScoutingEntries.matchKey]
            val matchPlayedTime = transaction {
                mKey?.let { mk ->
                    val matchRow = runCatching {
                        com.obsidianscout.integrations.TeamCacheOverrideService.matchRow(mk, session.teamNumber, session.program)
                    }.getOrElse {
                        com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.matchKey eq mk }.limit(1).firstOrNull()
                    }
                    matchRow?.let { it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime] }
                }
            }
            val conflictStr = duplicate[QualitativeScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            return QualitativeScoutingEntryRecord(
                id = duplicate[QualitativeScoutingEntries.id].value.toString(),
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
                conflictingTeams = conflicting,
                username = session.username,
                completenessPct = duplicate[QualitativeScoutingEntries.completenessPct]
            )
        }

        val dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), request.data)
        val now = Instant.now()
        val completeness = computeCompleteness(config, request.data)

        val id = com.obsidianscout.db.writeTransactionWithRetry {
            QualitativeScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = session.teamNumber
                it[program] = session.program
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[matchKey] = meta.matchKey
                it[matchNumber] = meta.matchNumber
                it[QualitativeScoutingEntries.dataJson] = dataJson
                it[submittedByUserId] = EntityID(UUID.fromString(session.userId), Users)
                it[createdAt] = now
                it[QualitativeScoutingEntries.isPrescout] = isPrescout
                it[completenessPct] = completeness
                it[QualitativeScoutingEntries.updatedAt] = now
            }
        }
        RecentSubmissions.remember(kindKey, session, request.clientId, id.value.toString())

        completeAssignments()

        recalculateDiscrepancies(meta.eventKey, meta.matchKey, meta.targetTeamNumber, isPrescout)

        val matchPlayedTime = transaction {
            meta.matchKey.let { mKey ->
                val matchRow = runCatching {
                    com.obsidianscout.integrations.TeamCacheOverrideService.matchRow(mKey, session.teamNumber, session.program)
                }.getOrElse {
                    com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.matchKey eq mKey }.limit(1).firstOrNull()
                }
                matchRow?.let { it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime] }
            }
        }

        return transaction {
            val updatedRow = QualitativeScoutingEntries.selectAll().where { QualitativeScoutingEntries.id eq id.value }.first()
            val conflictStr = updatedRow[QualitativeScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            QualitativeScoutingEntryRecord(
                id = id.value.toString(),
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
                conflictingTeams = conflicting,
                username = session.username,
                completenessPct = completeness
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
        entryId: String,
        request: ScoutingEntryRequest,
        config: ScoutingConfig
    ): QualitativeScoutingEntryRecord {
        val entryUuid = runCatching { UUID.fromString(entryId) }.getOrElse {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid entry ID format")
        }
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
        val completeness = computeCompleteness(config, request.data)

        return transaction {
            val row = QualitativeScoutingEntries.selectAll().where { QualitativeScoutingEntries.id eq entryUuid }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Qualitative scouting entry not found")

            val ownerTeam = row[QualitativeScoutingEntries.ownerTeamNumber]
            val entryProgram = row[QualitativeScoutingEntries.program]
            val callerActiveAllianceId = AllianceService.getActiveAllianceId(session.teamNumber, session.program)
            val entryActiveAllianceId = AllianceService.getActiveAllianceId(ownerTeam, session.program)
            val isAllianceAdmin = callerActiveAllianceId != null && 
                                  callerActiveAllianceId == entryActiveAllianceId && 
                                  AllianceService.isAllianceAdmin(session.teamNumber, callerActiveAllianceId, session.program)

            val isSubmitter = row[QualitativeScoutingEntries.submittedByUserId].value.toString() == session.userId
            val isTeamAdmin = (ownerTeam == session.teamNumber && session.role.isAtLeast(UserRole.ADMIN))
            val hasPermission = session.role == UserRole.SUPERADMIN ||
                                (entryProgram == session.program && (isSubmitter || isTeamAdmin || (isAllianceAdmin && session.role.isAtLeast(UserRole.ADMIN))))

            if (!hasPermission) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to edit this entry")
            }

            val oldEventKey = row[QualitativeScoutingEntries.eventKey]
            val oldMatchKey = row[QualitativeScoutingEntries.matchKey]
            val oldTargetTeamNumber = row[QualitativeScoutingEntries.targetTeamNumber]
            val oldIsPrescout = row[QualitativeScoutingEntries.isPrescout]

            QualitativeScoutingEntries.update({ QualitativeScoutingEntries.id eq entryUuid }) {
                it[targetTeamNumber] = meta.targetTeamNumber
                it[eventKey] = meta.eventKey
                it[matchKey] = meta.matchKey
                it[matchNumber] = meta.matchNumber
                it[QualitativeScoutingEntries.dataJson] = dataJson
                it[completenessPct] = completeness
                it[QualitativeScoutingEntries.updatedAt] = Instant.now()
            }

            recalculateDiscrepancies(oldEventKey, oldMatchKey, oldTargetTeamNumber, oldIsPrescout)
            recalculateDiscrepancies(meta.eventKey, meta.matchKey, meta.targetTeamNumber, oldIsPrescout)

            val updatedRow = QualitativeScoutingEntries.selectAll().where { QualitativeScoutingEntries.id eq entryUuid }.first()
            val data = JsonSupport.json.parseToJsonElement(updatedRow[QualitativeScoutingEntries.dataJson]).jsonObject
            val mKey = updatedRow[QualitativeScoutingEntries.matchKey]
            val matchPlayedTime = mKey?.let { mk ->
                val matchRow = runCatching {
                    com.obsidianscout.integrations.TeamCacheOverrideService.matchRow(mk, session.teamNumber, session.program)
                }.getOrElse {
                    com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.matchKey eq mk }.limit(1).firstOrNull()
                }
                matchRow?.let { it[com.obsidianscout.db.ApiMatches.actualTime] ?: it[com.obsidianscout.db.ApiMatches.scheduledTime] }
            }
            val conflictStr = updatedRow[QualitativeScoutingEntries.conflictingTeams]
            val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
            QualitativeScoutingEntryRecord(
                id = updatedRow[QualitativeScoutingEntries.id].value.toString(),
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
            val row = QualitativeScoutingEntries.selectAll().where { QualitativeScoutingEntries.id eq entryUuid }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Qualitative scouting entry not found")

            val ownerTeam = row[QualitativeScoutingEntries.ownerTeamNumber]
            val entryProgram = row[QualitativeScoutingEntries.program]
            val callerActiveAllianceId = AllianceService.getActiveAllianceId(session.teamNumber, session.program)
            val entryActiveAllianceId = AllianceService.getActiveAllianceId(ownerTeam, session.program)
            val isAllianceAdmin = callerActiveAllianceId != null && 
                                  callerActiveAllianceId == entryActiveAllianceId && 
                                  AllianceService.isAllianceAdmin(session.teamNumber, callerActiveAllianceId, session.program)

            val isSubmitter = row[QualitativeScoutingEntries.submittedByUserId].value.toString() == session.userId
            val isTeamAdmin = (ownerTeam == session.teamNumber && session.role.isAtLeast(UserRole.ADMIN))
            val hasPermission = session.role == UserRole.SUPERADMIN ||
                                (entryProgram == session.program && (isSubmitter || isTeamAdmin || (isAllianceAdmin && session.role.isAtLeast(UserRole.ADMIN))))

            if (!hasPermission) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to delete this entry")
            }

            val eventKey = row[QualitativeScoutingEntries.eventKey]
            val matchKey = row[QualitativeScoutingEntries.matchKey]
            val targetTeamNumber = row[QualitativeScoutingEntries.targetTeamNumber]
            val isPrescout = row[QualitativeScoutingEntries.isPrescout]

            QualitativeScoutingEntries.deleteWhere { QualitativeScoutingEntries.id eq entryUuid }
            EntrySync.recordDeletions(EntrySync.KIND_QUAL, listOf(entryId), ownerTeam, row[QualitativeScoutingEntries.program])

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