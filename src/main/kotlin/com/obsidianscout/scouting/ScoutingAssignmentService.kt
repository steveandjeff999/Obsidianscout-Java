package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

@Serializable
data class ScoutingAssignmentRecord(
    val id: String,
    val ownerTeamNumber: Int,
    val program: String,
    val eventKey: String,
    val assignedUserId: String,
    val assignedUsername: String,
    val assignmentType: String, // MATCH | PIT | QUALITATIVE
    val matchKey: String? = null,
    val matchNumber: Int? = null,
    val compLevel: String? = null,
    val targetTeamNumber: Int? = null,
    val targetTeamName: String? = null,
    val allianceColor: String? = null,
    val status: String = "PENDING", // PENDING | IN_PROGRESS | COMPLETED | SKIPPED
    val notes: String? = null,
    val createdByUserId: String? = null,
    val createdByUsername: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String? = null,
    val scheduledTime: Long? = null,
    val reminderMinutesBefore: Int? = null,
    val pushReminderSentAt: String? = null,
    val emailReminderSentAt: String? = null
)

@Serializable
data class CreateAssignmentRequest(
    val assignedUserId: String,
    val assignmentType: String, // MATCH | PIT | QUALITATIVE
    val eventKey: String,
    val matchKey: String? = null,
    val matchNumber: Int? = null,
    val compLevel: String? = null,
    val targetTeamNumber: Int? = null,
    val allianceColor: String? = null,
    val notes: String? = null,
    val reminderMinutesBefore: Int? = null
)

@Serializable
data class BulkAssignmentItem(
    val assignedUserId: String,
    val assignmentType: String,
    val matchKey: String? = null,
    val matchNumber: Int? = null,
    val compLevel: String? = null,
    val targetTeamNumber: Int? = null,
    val allianceColor: String? = null,
    val notes: String? = null
)

@Serializable
data class BulkCreateAssignmentsRequest(
    val eventKey: String = "",
    val assignments: List<BulkAssignmentItem> = emptyList(),
    val reminderMinutesBefore: Int? = null
)

@Serializable
data class UpdateAssignmentRequest(
    val assignedUserId: String? = null,
    val targetTeamNumber: Int? = null,
    val allianceColor: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val reminderMinutesBefore: Int? = null
)

@Serializable
data class UpdateAssignmentStatusRequest(
    val status: String
)

@Serializable
data class ConflictScouterDto(
    val assignmentId: String,
    val userId: String,
    val username: String,
    val assignmentType: String,
    val status: String
)

@Serializable
data class ConflictItemDto(
    val assignmentType: String,
    val matchKey: String? = null,
    val matchNumber: Int? = null,
    val targetTeamNumber: Int? = null,
    val allianceColor: String? = null,
    val description: String,
    val assignmentIds: List<String>,
    val scouters: List<ConflictScouterDto>
)

@Serializable
data class AssignmentConflictDto(
    val hasConflict: Boolean,
    val existingScouters: List<ConflictScouterDto> = emptyList(),
    val conflicts: List<ConflictItemDto> = emptyList()
)

@Serializable
data class EventAssignmentCoverageDto(
    val eventKey: String,
    val totalAssignments: Int,
    val pendingCount: Int,
    val completedCount: Int,
    val inProgressCount: Int,
    val skippedCount: Int = 0,
    val matchCoverage: Map<String, Map<String, List<String>>> = emptyMap(),
    val pitCoverage: Map<String, List<String>> = emptyMap(),
    val qualCoverage: Map<String, Map<String, List<String>>> = emptyMap()
)

@Serializable
data class DeleteAssignmentsResponse(
    val success: Boolean = true,
    val deletedCount: Int = 0,
    val message: String? = null
)

@Serializable
data class AssignmentActionResponse(
    val success: Boolean = true,
    val message: String
)

object ScoutingAssignmentService {

    fun createAssignment(session: UserSession, request: CreateAssignmentRequest): ScoutingAssignmentRecord {
        val targetUserUuid = runCatching { UUID.fromString(request.assignedUserId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid assigned user ID.")

        val eventKeyClean = request.eventKey.trim()
        if (eventKeyClean.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Event key is required.")
        }

        val typeClean = request.assignmentType.trim().uppercase()
        if (typeClean !in listOf("MATCH", "PIT", "QUALITATIVE")) {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid assignment type: $typeClean. Must be MATCH, PIT, or QUALITATIVE.")
        }

        val now = Instant.now()
        val createdId = transaction {
            // Verify target user belongs to the same team and program (or superadmin)
            val userRow = Users.selectAll().where {
                (Users.id eq targetUserUuid) and (Users.program eq session.program) and
                (if (session.role != UserRole.SUPERADMIN) Users.teamNumber eq session.teamNumber else Users.teamNumber eq Users.teamNumber)
            }.firstOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Assigned user not found on team.")

            val effectiveOwnerTeam = if (session.role == UserRole.SUPERADMIN && session.teamNumber == 0) {
                userRow[Users.teamNumber]
            } else {
                session.teamNumber
            }

            ScoutingAssignments.insertAndGetId {
                it[ownerTeamNumber] = effectiveOwnerTeam
                it[program] = session.program
                it[eventKey] = eventKeyClean
                it[assignedUserId] = userRow[Users.id]
                it[assignmentType] = typeClean
                it[matchKey] = request.matchKey?.trim()?.ifBlank { null }
                it[matchNumber] = request.matchNumber
                it[compLevel] = request.compLevel?.trim()?.ifBlank { null }
                it[targetTeamNumber] = request.targetTeamNumber
                it[allianceColor] = request.allianceColor?.trim()?.uppercase()?.ifBlank { null }
                it[status] = "PENDING"
                it[notes] = request.notes?.trim()?.ifBlank { null }
                it[createdByUserId] = runCatching { UUID.fromString(session.userId) }.getOrNull()
                it[createdAt] = now
                it[updatedAt] = now
                it[reminderMinutesBefore] = request.reminderMinutesBefore
            }.value
        }

        return getAssignmentById(session, createdId.toString())
            ?: throw ApiException(HttpStatusCode.InternalServerError, "Failed to retrieve created assignment.")
    }

    fun bulkCreateAssignments(session: UserSession, request: BulkCreateAssignmentsRequest): List<ScoutingAssignmentRecord> {
        val eventKeyClean = request.eventKey.trim()
        if (eventKeyClean.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Event key is required.")
        }
        if (request.assignments.isEmpty()) {
            return emptyList()
        }

        val now = Instant.now()
        val creatorUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()

        val createdIds = transaction {
            val userIds = request.assignments.mapNotNull { runCatching { UUID.fromString(it.assignedUserId) }.getOrNull() }.distinct()
            val validUsers = Users.selectAll().where {
                (Users.id inList userIds) and (Users.program eq session.program) and
                (if (session.role != UserRole.SUPERADMIN) Users.teamNumber eq session.teamNumber else Users.teamNumber eq Users.teamNumber)
            }.associateBy { it[Users.id].value }

            val ids = mutableListOf<UUID>()
            for (item in request.assignments) {
                val targetUuid = runCatching { UUID.fromString(item.assignedUserId) }.getOrNull() ?: continue
                val uRow = validUsers[targetUuid] ?: continue
                val typeClean = item.assignmentType.trim().uppercase()
                if (typeClean !in listOf("MATCH", "PIT", "QUALITATIVE")) continue

                val effectiveOwnerTeam = if (session.role == UserRole.SUPERADMIN && session.teamNumber == 0) {
                    uRow[Users.teamNumber]
                } else {
                    session.teamNumber
                }

                val id = ScoutingAssignments.insertAndGetId {
                    it[ownerTeamNumber] = effectiveOwnerTeam
                    it[program] = session.program
                    it[eventKey] = eventKeyClean
                    it[assignedUserId] = targetUuid
                    it[assignmentType] = typeClean
                    it[matchKey] = item.matchKey?.trim()?.ifBlank { null }
                    it[matchNumber] = item.matchNumber
                    it[compLevel] = item.compLevel?.trim()?.ifBlank { null }
                    it[targetTeamNumber] = item.targetTeamNumber
                    it[allianceColor] = item.allianceColor?.trim()?.uppercase()?.ifBlank { null }
                    it[status] = "PENDING"
                    it[notes] = item.notes?.trim()?.ifBlank { null }
                    it[createdByUserId] = creatorUuid
                    it[createdAt] = now
                    it[updatedAt] = now
                    it[reminderMinutesBefore] = request.reminderMinutesBefore
                }.value
                ids.add(id)
            }
            ids
        }

        return listAssignments(session, eventKey = eventKeyClean).filter { it.id in createdIds.map { id -> id.toString() } }
    }

    fun updateAssignment(session: UserSession, id: String, request: UpdateAssignmentRequest): ScoutingAssignmentRecord {
        val assignmentUuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid assignment ID.")

        val now = Instant.now()
        transaction {
            val query = ScoutingAssignments.selectAll().where { (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program eq session.program) }
            if (session.role != UserRole.SUPERADMIN && session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            val existing = query.firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "Assignment not found.")

            val newTargetUser = if (!request.assignedUserId.isNullOrBlank()) {
                val userUuid = runCatching { UUID.fromString(request.assignedUserId) }.getOrNull()
                    ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID.")
                val userExists = Users.selectAll().where { (Users.id eq userUuid) and (Users.program eq session.program) }.count() > 0
                if (!userExists) throw ApiException(HttpStatusCode.BadRequest, "Assigned user not found.")
                userUuid
            } else null

            val newStatus = request.status?.trim()?.uppercase()
            val completedTime = if (newStatus == "COMPLETED" && existing[ScoutingAssignments.completedAt] == null) now else existing[ScoutingAssignments.completedAt]

            ScoutingAssignments.update({ ScoutingAssignments.id eq assignmentUuid }) {
                if (newTargetUser != null) {
                    it[assignedUserId] = newTargetUser
                }
                if (request.targetTeamNumber != null) {
                    it[targetTeamNumber] = request.targetTeamNumber
                }
                if (request.allianceColor != null) {
                    it[allianceColor] = request.allianceColor.trim().uppercase().ifBlank { null }
                }
                if (request.notes != null) {
                    it[notes] = request.notes.trim().ifBlank { null }
                }
                if (newStatus != null && newStatus in listOf("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "CANCELLED")) {
                    it[status] = newStatus
                    it[completedAt] = completedTime
                }
                if (request.reminderMinutesBefore != null) {
                    it[reminderMinutesBefore] = request.reminderMinutesBefore
                }
                it[updatedAt] = now
            }
        }

        return getAssignmentById(session, id)
            ?: throw ApiException(HttpStatusCode.InternalServerError, "Failed to retrieve updated assignment.")
    }

    fun updateStatus(session: UserSession, id: String, status: String): ScoutingAssignmentRecord {
        val assignmentUuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid assignment ID.")
        val statusClean = status.trim().uppercase()
        if (statusClean !in listOf("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "CANCELLED")) {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid status: $statusClean. Must be PENDING, IN_PROGRESS, COMPLETED, SKIPPED, or CANCELLED.")
        }

        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.Unauthorized, "Invalid user session.")

        val now = Instant.now()
        transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program eq session.program)
            }
            if (session.role != UserRole.SUPERADMIN) {
                query.andWhere {
                    (ScoutingAssignments.ownerTeamNumber eq session.teamNumber) and
                    (if (session.role == UserRole.SCOUT || session.role == UserRole.ANALYTICS) ScoutingAssignments.assignedUserId eq userUuid else ScoutingAssignments.ownerTeamNumber eq session.teamNumber)
                }
            }
            val existing = query.firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "Assignment not found or unauthorized.")

            ScoutingAssignments.update({ ScoutingAssignments.id eq assignmentUuid }) {
                it[ScoutingAssignments.status] = statusClean
                it[updatedAt] = now
                if (statusClean == "COMPLETED") {
                    it[completedAt] = now
                } else if (statusClean == "PENDING" || statusClean == "IN_PROGRESS") {
                    it[completedAt] = null
                }
            }
        }

        return getAssignmentById(session, id)
            ?: throw ApiException(HttpStatusCode.InternalServerError, "Failed to retrieve updated assignment.")
    }

    fun deleteAssignment(session: UserSession, id: String): Boolean {
        val assignmentUuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid assignment ID.")

        return transaction {
            val query = (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program eq session.program)
            val fullQuery = if (session.role != UserRole.SUPERADMIN && session.teamNumber != 0) {
                query and (ScoutingAssignments.ownerTeamNumber eq session.teamNumber)
            } else query

            ScoutingAssignments.deleteWhere { fullQuery } > 0
        }
    }

    fun getAssignmentById(session: UserSession, id: String): ScoutingAssignmentRecord? {
        val assignmentUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program eq session.program)
            }
            if (session.role != UserRole.SUPERADMIN && session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            val row = query.firstOrNull() ?: return@readTransaction null
            mapRowToRecord(row)
        }
    }

    fun listAssignments(
        session: UserSession,
        eventKey: String? = null,
        assignmentType: String? = null,
        userId: String? = null,
        status: String? = null
    ): List<ScoutingAssignmentRecord> {
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where { ScoutingAssignments.program eq session.program }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.eventKey eq eventKey.trim() }
            }
            if (!assignmentType.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.assignmentType eq assignmentType.trim().uppercase() }
            }
            if (!userId.isNullOrBlank()) {
                val userUuid = runCatching { UUID.fromString(userId) }.getOrNull()
                if (userUuid != null) {
                    query.andWhere { ScoutingAssignments.assignedUserId eq userUuid }
                }
            }
            if (!status.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.status eq status.trim().uppercase() }
            }

            query.orderBy(ScoutingAssignments.matchNumber to SortOrder.ASC_NULLS_LAST, ScoutingAssignments.createdAt to SortOrder.DESC)
            val rows = query.toList()
            mapRowsToRecords(rows)
        }
    }

    fun getMyAssignments(session: UserSession, eventKey: String? = null): List<ScoutingAssignmentRecord> {
        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull() ?: return emptyList()
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.assignedUserId eq userUuid) and
                (ScoutingAssignments.program eq session.program)
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.eventKey eq eventKey.trim() }
            }
            query.orderBy(ScoutingAssignments.matchNumber to SortOrder.ASC_NULLS_LAST, ScoutingAssignments.createdAt to SortOrder.DESC)
            val rows = query.toList()
            mapRowsToRecords(rows)
        }
    }

    fun getConflicts(
        session: UserSession,
        eventKey: String,
        assignmentType: String? = null,
        matchNumber: Int? = null,
        matchKey: String? = null,
        targetTeamNumber: Int? = null,
        allianceColor: String? = null
    ): AssignmentConflictDto {
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq session.program) and
                (ScoutingAssignments.eventKey eq eventKey.trim()) and
                (ScoutingAssignments.status neq "SKIPPED") and
                (ScoutingAssignments.status neq "CANCELLED")
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }

            if (!assignmentType.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.assignmentType eq assignmentType.trim().uppercase() }
            }
            if (matchNumber != null) {
                query.andWhere { ScoutingAssignments.matchNumber eq matchNumber }
            }
            if (!matchKey.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.matchKey eq matchKey.trim() }
            }
            if (targetTeamNumber != null) {
                query.andWhere { ScoutingAssignments.targetTeamNumber eq targetTeamNumber }
            }
            if (!allianceColor.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.allianceColor eq allianceColor.trim().uppercase() }
            }

            val rows = query.toList()
            if (rows.isEmpty()) {
                return@readTransaction AssignmentConflictDto(false, emptyList(), emptyList())
            }

            val userIds = rows.map { it[ScoutingAssignments.assignedUserId].value }.distinct()
            val userMap = if (userIds.isNotEmpty()) {
                Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value to it[Users.username] }
            } else emptyMap()

            // Group by slot to find duplicates
            val grouped = rows.groupBy { row ->
                val type = row[ScoutingAssignments.assignmentType]
                val comp = row[ScoutingAssignments.compLevel]?.trim()?.lowercase() ?: ""
                val mKey = row[ScoutingAssignments.matchKey]?.trim()?.lowercase() ?: "${comp}_${row[ScoutingAssignments.matchNumber]}"
                val tNum = row[ScoutingAssignments.targetTeamNumber]?.toString() ?: ""
                val alliance = row[ScoutingAssignments.allianceColor]?.trim()?.uppercase() ?: ""
                "$type:$mKey:$tNum:$alliance"
            }

            val conflictItems = mutableListOf<ConflictItemDto>()
            val conflictingScouters = mutableListOf<ConflictScouterDto>()

            for ((_, slotRows) in grouped) {
                if (slotRows.size > 1) {
                    val first = slotRows.first()
                    val scouters = slotRows.map { row ->
                        val uId = row[ScoutingAssignments.assignedUserId].value
                        ConflictScouterDto(
                            assignmentId = row[ScoutingAssignments.id].value.toString(),
                            userId = uId.toString(),
                            username = userMap[uId] ?: "Unknown",
                            assignmentType = row[ScoutingAssignments.assignmentType],
                            status = row[ScoutingAssignments.status]
                        )
                    }
                    conflictingScouters.addAll(scouters)
                    conflictItems.add(
                        ConflictItemDto(
                            assignmentType = first[ScoutingAssignments.assignmentType],
                            matchKey = first[ScoutingAssignments.matchKey],
                            matchNumber = first[ScoutingAssignments.matchNumber],
                            targetTeamNumber = first[ScoutingAssignments.targetTeamNumber],
                            allianceColor = first[ScoutingAssignments.allianceColor],
                            description = "${first[ScoutingAssignments.assignmentType]} target assigned ${slotRows.size} times",
                            assignmentIds = slotRows.map { it[ScoutingAssignments.id].value.toString() },
                            scouters = scouters
                        )
                    )
                }
            }

            val isSpecificSlotCheck = !assignmentType.isNullOrBlank() && (matchKey != null || matchNumber != null || targetTeamNumber != null || allianceColor != null)

            // Also check for dual scouter match overlaps if checking all event conflicts
            if (!isSpecificSlotCheck) {
                val matchUserGroups = rows.filter { row ->
                    val type = row[ScoutingAssignments.assignmentType]
                    (type == "MATCH" || type == "QUALITATIVE")
                }.groupBy { row ->
                    val uId = row[ScoutingAssignments.assignedUserId].value
                    val comp = row[ScoutingAssignments.compLevel]?.trim()?.lowercase() ?: ""
                    val mKey = row[ScoutingAssignments.matchKey]?.trim()?.lowercase() ?: "${comp}_${row[ScoutingAssignments.matchNumber]}"
                    "$uId:$mKey"
                }

                for ((_, userMatchRows) in matchUserGroups) {
                    val isBothAlliancesQualPair = userMatchRows.size == 2 &&
                        userMatchRows.all { it[ScoutingAssignments.assignmentType] == "QUALITATIVE" && it[ScoutingAssignments.targetTeamNumber] == null } &&
                        (userMatchRows[0][ScoutingAssignments.allianceColor]?.trim()?.lowercase() != userMatchRows[1][ScoutingAssignments.allianceColor]?.trim()?.lowercase())

                    if (userMatchRows.size > 1 && !isBothAlliancesQualPair) {
                        val first = userMatchRows.first()
                        val uId = first[ScoutingAssignments.assignedUserId].value
                        val scouters = userMatchRows.map { row ->
                            ConflictScouterDto(
                                assignmentId = row[ScoutingAssignments.id].value.toString(),
                                userId = uId.toString(),
                                username = userMap[uId] ?: "Unknown",
                                assignmentType = row[ScoutingAssignments.assignmentType],
                                status = row[ScoutingAssignments.status]
                            )
                        }
                        val existingIds = conflictingScouters.map { it.assignmentId }.toSet()
                        val newScouters = scouters.filter { it.assignmentId !in existingIds }
                        conflictingScouters.addAll(newScouters)

                        val existingItemIds = conflictItems.flatMap { it.assignmentIds }.toSet()
                        val rowIds = userMatchRows.map { it[ScoutingAssignments.id].value.toString() }
                        if (rowIds.any { it !in existingItemIds }) {
                            conflictItems.add(
                                ConflictItemDto(
                                    assignmentType = first[ScoutingAssignments.assignmentType],
                                    matchKey = first[ScoutingAssignments.matchKey],
                                    matchNumber = first[ScoutingAssignments.matchNumber],
                                    targetTeamNumber = first[ScoutingAssignments.targetTeamNumber],
                                    allianceColor = first[ScoutingAssignments.allianceColor],
                                    description = "Scouter assigned to ${userMatchRows.size} roles in this match",
                                    assignmentIds = rowIds,
                                    scouters = scouters
                                )
                            )
                        }
                    }
                }
            }

            val hasConflict = if (isSpecificSlotCheck) {
                rows.isNotEmpty()
            } else {
                conflictItems.isNotEmpty()
            }

            val scoutersList = if (isSpecificSlotCheck) {
                rows.map { row ->
                    val uId = row[ScoutingAssignments.assignedUserId].value
                    ConflictScouterDto(
                        assignmentId = row[ScoutingAssignments.id].value.toString(),
                        userId = uId.toString(),
                        username = userMap[uId] ?: "Unknown",
                        assignmentType = row[ScoutingAssignments.assignmentType],
                        status = row[ScoutingAssignments.status]
                    )
                }
            } else {
                conflictingScouters
            }

            AssignmentConflictDto(hasConflict, scoutersList, conflictItems)
        }
    }

    fun getCoverage(session: UserSession, eventKey: String): EventAssignmentCoverageDto {
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq session.program) and
                (ScoutingAssignments.eventKey eq eventKey.trim())
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            val rows = query.toList()
            val userIds = rows.map { it[ScoutingAssignments.assignedUserId].value }.distinct()
            val userMap = Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value to it[Users.username] }

            var pending = 0
            var completed = 0
            var inProgress = 0
            var skipped = 0

            val matchCov = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
            val pitCov = mutableMapOf<String, MutableList<String>>()
            val qualCov = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

            for (row in rows) {
                val st = row[ScoutingAssignments.status].uppercase()
                when (st) {
                    "PENDING" -> pending++
                    "COMPLETED" -> completed++
                    "IN_PROGRESS" -> inProgress++
                    "SKIPPED" -> skipped++
                }

                val uName = userMap[row[ScoutingAssignments.assignedUserId].value] ?: "Unknown"
                val type = row[ScoutingAssignments.assignmentType].uppercase()
                val mKey = row[ScoutingAssignments.matchKey] ?: (row[ScoutingAssignments.matchNumber]?.let { "M$it" } ?: "unknown")
                val tNum = row[ScoutingAssignments.targetTeamNumber]?.toString() ?: ""
                val alliance = row[ScoutingAssignments.allianceColor] ?: ""

                when (type) {
                    "MATCH" -> {
                        if (tNum.isNotBlank()) {
                            matchCov.getOrPut(mKey) { mutableMapOf() }.getOrPut(tNum) { mutableListOf() }.add(uName)
                        }
                    }
                    "PIT" -> {
                        if (tNum.isNotBlank()) {
                            pitCov.getOrPut(tNum) { mutableListOf() }.add(uName)
                        }
                    }
                    "QUALITATIVE" -> {
                        val key = if (tNum.isNotBlank()) tNum else alliance
                        if (key.isNotBlank()) {
                            qualCov.getOrPut(mKey) { mutableMapOf() }.getOrPut(key) { mutableListOf() }.add(uName)
                        }
                    }
                }
            }

            EventAssignmentCoverageDto(
                eventKey = eventKey.trim(),
                totalAssignments = rows.size,
                pendingCount = pending,
                completedCount = completed,
                inProgressCount = inProgress,
                skippedCount = skipped,
                matchCoverage = matchCov,
                pitCoverage = pitCov,
                qualCoverage = qualCov
            )
        }
    }

    fun deleteAllAssignments(session: UserSession, eventKey: String, assignmentType: String? = null): Int {
        val eventKeyClean = eventKey.trim()
        if (eventKeyClean.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Event key is required.")
        }
        return transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq session.program) and
                (ScoutingAssignments.eventKey eq eventKeyClean)
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            if (!assignmentType.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.assignmentType eq assignmentType.trim().uppercase() }
            }
            val ids = query.map { it[ScoutingAssignments.id].value }
            if (ids.isNotEmpty()) {
                ScoutingAssignments.deleteWhere { ScoutingAssignments.id inList ids }
            } else 0
        }
    }

    /**
     * Synchronizes completed assignments by checking against existing scouting data in the database.
     */
    fun syncCompletedAssignments(program: String, eventKey: String?) {
        if (eventKey.isNullOrBlank()) return
        val eventClean = eventKey.trim()
        val now = Instant.now()

        try {
            transaction {
                val pendingAsgns = ScoutingAssignments.selectAll().where {
                    (ScoutingAssignments.program eq program) and
                    (ScoutingAssignments.eventKey eq eventClean) and
                    ((ScoutingAssignments.status eq "PENDING") or (ScoutingAssignments.status eq "IN_PROGRESS"))
                }.toList()

                if (pendingAsgns.isEmpty()) return@transaction

                // Load all existing match scouting data keys for this event
                val matchEntries = ScoutingEntries.selectAll().where {
                    (ScoutingEntries.program eq program) and
                    (ScoutingEntries.eventKey eq eventClean)
                }.toList()
                val matchEntryKeys = matchEntries.mapNotNull { row ->
                    val mKey = row[ScoutingEntries.matchKey]?.trim()?.lowercase()
                    val mNum = row[ScoutingEntries.matchNumber]
                    val tNum = row[ScoutingEntries.targetTeamNumber]
                    if (tNum != null) {
                        setOfNotNull(
                            mKey?.let { "K:${it}:${tNum}" },
                            mNum?.let { "N:${it}:${tNum}" }
                        )
                    } else null
                }.flatten().toSet()

                // Load all existing pit scouting data for this event
                val pitEntries = PitScoutingEntries.selectAll().where {
                    (PitScoutingEntries.program eq program) and
                    (PitScoutingEntries.eventKey eq eventClean)
                }.toList()
                val pitTeams = pitEntries.mapNotNull { it[PitScoutingEntries.targetTeamNumber] }.toSet()

                // Load all existing qualitative scouting data for this event
                val qualEntries = QualitativeScoutingEntries.selectAll().where {
                    (QualitativeScoutingEntries.program eq program) and
                    (QualitativeScoutingEntries.eventKey eq eventClean)
                }.toList()
                val qualEntryKeys = qualEntries.mapNotNull { row ->
                    val mKey = row[QualitativeScoutingEntries.matchKey]?.trim()?.lowercase()
                    val mNum = row[QualitativeScoutingEntries.matchNumber]
                    val tNum = row[QualitativeScoutingEntries.targetTeamNumber]
                    if (tNum != null) {
                        setOfNotNull(
                            mKey?.let { "K:${it}:${tNum}" },
                            mNum?.let { "N:${it}:${tNum}" }
                        )
                    } else null
                }.flatten().toSet()

                // Load match alliances for qualitative alliance resolution
                val matchesForEvent = ApiMatches.selectAll().where { ApiMatches.eventKey eq eventClean }.associate { row ->
                    val mKey = row[ApiMatches.matchKey].trim().lowercase()
                    val red = row[ApiMatches.redTeams].split(",").mapNotNull { it.replace(Regex("^(frc|ftc)", RegexOption.IGNORE_CASE), "").trim().toIntOrNull() }.toSet()
                    val blue = row[ApiMatches.blueTeams].split(",").mapNotNull { it.replace(Regex("^(frc|ftc)", RegexOption.IGNORE_CASE), "").trim().toIntOrNull() }.toSet()
                    mKey to Pair(red, blue)
                }

                val idsToComplete = mutableListOf<UUID>()

                for (a in pendingAsgns) {
                    val type = a[ScoutingAssignments.assignmentType]
                    val mKey = a[ScoutingAssignments.matchKey]?.trim()?.lowercase()
                    val mNum = a[ScoutingAssignments.matchNumber]
                    val tNum = a[ScoutingAssignments.targetTeamNumber]
                    val alliance = a[ScoutingAssignments.allianceColor]?.trim()?.uppercase()

                    if (type == "MATCH") {
                        if (tNum != null) {
                            val matchByKey = mKey?.let { matchEntryKeys.contains("K:${it}:${tNum}") } ?: false
                            val matchByNum = mNum?.let { matchEntryKeys.contains("N:${it}:${tNum}") } ?: false
                            if (matchByKey || matchByNum) {
                                idsToComplete.add(a[ScoutingAssignments.id].value)
                            }
                        }
                    } else if (type == "PIT") {
                        if (tNum != null && pitTeams.contains(tNum)) {
                            idsToComplete.add(a[ScoutingAssignments.id].value)
                        }
                    } else if (type == "QUALITATIVE") {
                        if (tNum != null) {
                            val qualByKey = mKey?.let { qualEntryKeys.contains("K:${it}:${tNum}") } ?: false
                            val qualByNum = mNum?.let { qualEntryKeys.contains("N:${it}:${tNum}") } ?: false
                            if (qualByKey || qualByNum) {
                                idsToComplete.add(a[ScoutingAssignments.id].value)
                            }
                        } else if (alliance != null && mKey != null) {
                            val allianceTeams = matchesForEvent[mKey]?.let {
                                if (alliance == "RED") it.first else if (alliance == "BLUE") it.second else null
                            }
                            if (allianceTeams != null && allianceTeams.isNotEmpty()) {
                                val anyScouted = allianceTeams.any { num -> qualEntryKeys.contains("K:${mKey}:${num}") }
                                if (anyScouted) {
                                    idsToComplete.add(a[ScoutingAssignments.id].value)
                                }
                            }
                        }
                    }
                }

                if (idsToComplete.isNotEmpty()) {
                    ScoutingAssignments.update({ ScoutingAssignments.id inList idsToComplete }) {
                        it[status] = "COMPLETED"
                        it[completedAt] = now
                        it[updatedAt] = now
                    }
                    println("[ScoutingAssignmentService] Auto-synced and marked completed ${idsToComplete.size} assignment(s) for event $eventClean")
                }
            }
        } catch (e: Exception) {
            println("[ScoutingAssignmentService] syncCompletedAssignments warning: ${e.message}")
        }
    }

    /**
     * Called whenever a scouting entry (Match, Pit, Qualitative) is submitted.
     * Automatically completes matching PENDING assignments.
     */
    fun handleScoutingSubmission(
        ownerTeam: Int,
        program: String,
        eventKey: String?,
        assignmentType: String,
        matchNumber: Int?,
        matchKey: String?,
        targetTeamNumber: Int?,
        userId: String? = null,
        allianceColor: String? = null
    ) {
        if (eventKey.isNullOrBlank()) return
        val typeClean = assignmentType.trim().uppercase()
        val now = Instant.now()

        transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq program) and
                (ScoutingAssignments.eventKey eq eventKey.trim()) and
                (ScoutingAssignments.assignmentType eq typeClean) and
                ((ScoutingAssignments.status eq "PENDING") or (ScoutingAssignments.status eq "IN_PROGRESS"))
            }

            if (typeClean == "MATCH") {
                if (!matchKey.isNullOrBlank()) {
                    query.andWhere { ScoutingAssignments.matchKey eq matchKey.trim() }
                } else if (matchNumber != null) {
                    query.andWhere { ScoutingAssignments.matchNumber eq matchNumber }
                }
                if (targetTeamNumber != null) {
                    query.andWhere { ScoutingAssignments.targetTeamNumber eq targetTeamNumber }
                }
            } else if (typeClean == "PIT") {
                if (targetTeamNumber != null) {
                    query.andWhere { ScoutingAssignments.targetTeamNumber eq targetTeamNumber }
                }
            } else if (typeClean == "QUALITATIVE") {
                if (!matchKey.isNullOrBlank()) {
                    query.andWhere { ScoutingAssignments.matchKey eq matchKey.trim() }
                } else if (matchNumber != null) {
                    query.andWhere { ScoutingAssignments.matchNumber eq matchNumber }
                }

                var teamAlliance: String? = allianceColor?.trim()?.uppercase()
                if (teamAlliance == null && !matchKey.isNullOrBlank() && targetTeamNumber != null) {
                    val matchRow = ApiMatches.selectAll().where { ApiMatches.matchKey eq matchKey.trim() }.firstOrNull()
                    if (matchRow != null) {
                        val red = matchRow[ApiMatches.redTeams].split(",").mapNotNull { it.replace(Regex("^(frc|ftc)", RegexOption.IGNORE_CASE), "").trim().toIntOrNull() }
                        val blue = matchRow[ApiMatches.blueTeams].split(",").mapNotNull { it.replace(Regex("^(frc|ftc)", RegexOption.IGNORE_CASE), "").trim().toIntOrNull() }
                        if (targetTeamNumber in red) teamAlliance = "RED"
                        else if (targetTeamNumber in blue) teamAlliance = "BLUE"
                    }
                }

                if (targetTeamNumber != null && teamAlliance != null) {
                    query.andWhere {
                        (ScoutingAssignments.targetTeamNumber eq targetTeamNumber) or
                        (ScoutingAssignments.allianceColor eq teamAlliance)
                    }
                } else if (targetTeamNumber != null) {
                    query.andWhere { ScoutingAssignments.targetTeamNumber eq targetTeamNumber }
                } else if (teamAlliance != null) {
                    query.andWhere { ScoutingAssignments.allianceColor eq teamAlliance }
                }
            }

            val matchingIds = query.map { it[ScoutingAssignments.id].value }
            if (matchingIds.isNotEmpty()) {
                ScoutingAssignments.update({ ScoutingAssignments.id inList matchingIds }) {
                    it[status] = "COMPLETED"
                    it[completedAt] = now
                    it[updatedAt] = now
                }
                println("[ScoutingAssignmentService] Auto-completed ${matchingIds.size} assignment(s) for event $eventKey ($typeClean)")
            }
        }
    }

    private fun mapRowToRecord(row: org.jetbrains.exposed.sql.ResultRow): ScoutingAssignmentRecord {
        val userIds = listOfNotNull(row[ScoutingAssignments.assignedUserId].value, row[ScoutingAssignments.createdByUserId]?.value)
        val userMap = Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value to it[Users.username] }

        val assignedUId = row[ScoutingAssignments.assignedUserId].value
        val creatorUId = row[ScoutingAssignments.createdByUserId]?.value

        val mKey = row[ScoutingAssignments.matchKey]
        val matchScheduledTime = if (!mKey.isNullOrBlank()) {
            ApiMatches.selectAll().where { ApiMatches.matchKey eq mKey }.firstOrNull()?.get(ApiMatches.scheduledTime)
        } else null

        val tNum = row[ScoutingAssignments.targetTeamNumber]
        val eKey = row[ScoutingAssignments.eventKey]
        val targetTeamNickname = if (tNum != null) {
            ApiTeams.selectAll().where { (ApiTeams.eventKey eq eKey) and (ApiTeams.teamNumber eq tNum) }.firstOrNull()?.let {
                it[ApiTeams.nickname] ?: it[ApiTeams.name]
            }
        } else null

        return ScoutingAssignmentRecord(
            id = row[ScoutingAssignments.id].value.toString(),
            ownerTeamNumber = row[ScoutingAssignments.ownerTeamNumber],
            program = row[ScoutingAssignments.program],
            eventKey = row[ScoutingAssignments.eventKey],
            assignedUserId = assignedUId.toString(),
            assignedUsername = userMap[assignedUId] ?: "Unknown",
            assignmentType = row[ScoutingAssignments.assignmentType],
            matchKey = row[ScoutingAssignments.matchKey],
            matchNumber = row[ScoutingAssignments.matchNumber],
            compLevel = row[ScoutingAssignments.compLevel],
            targetTeamNumber = row[ScoutingAssignments.targetTeamNumber],
            targetTeamName = targetTeamNickname,
            allianceColor = row[ScoutingAssignments.allianceColor],
            status = row[ScoutingAssignments.status],
            notes = row[ScoutingAssignments.notes],
            createdByUserId = creatorUId?.toString(),
            createdByUsername = creatorUId?.let { userMap[it] },
            createdAt = row[ScoutingAssignments.createdAt].toString(),
            updatedAt = row[ScoutingAssignments.updatedAt].toString(),
            completedAt = row[ScoutingAssignments.completedAt]?.toString(),
            scheduledTime = matchScheduledTime,
            reminderMinutesBefore = row[ScoutingAssignments.reminderMinutesBefore],
            pushReminderSentAt = row[ScoutingAssignments.pushReminderSentAt]?.toString(),
            emailReminderSentAt = row[ScoutingAssignments.emailReminderSentAt]?.toString()
        )
    }

    private fun mapRowsToRecords(rows: List<org.jetbrains.exposed.sql.ResultRow>): List<ScoutingAssignmentRecord> {
        if (rows.isEmpty()) return emptyList()

        val userIds = rows.flatMap { listOfNotNull(it[ScoutingAssignments.assignedUserId].value, it[ScoutingAssignments.createdByUserId]?.value) }.distinct()
        val userMap = if (userIds.isNotEmpty()) {
            Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value to it[Users.username] }
        } else emptyMap()

        val matchKeys = rows.mapNotNull { it[ScoutingAssignments.matchKey] }.filter { it.isNotBlank() }.distinct()
        val matchTimes = if (matchKeys.isNotEmpty()) {
            ApiMatches.selectAll().where { ApiMatches.matchKey inList matchKeys }.associate { it[ApiMatches.matchKey] to it[ApiMatches.scheduledTime] }
        } else emptyMap()

        val eventTeamPairs = rows.mapNotNull {
            val t = it[ScoutingAssignments.targetTeamNumber]
            val e = it[ScoutingAssignments.eventKey]
            if (t != null) Pair(e, t) else null
        }.distinct()

        val teamNicknames = if (eventTeamPairs.isNotEmpty()) {
            val events = eventTeamPairs.map { it.first }.distinct()
            val teams = eventTeamPairs.map { it.second }.distinct()
            ApiTeams.selectAll().where { (ApiTeams.eventKey inList events) and (ApiTeams.teamNumber inList teams) }
                .associate { Pair(it[ApiTeams.eventKey], it[ApiTeams.teamNumber]) to (it[ApiTeams.nickname] ?: it[ApiTeams.name]) }
        } else emptyMap()

        return rows.map { row ->
            val assignedUId = row[ScoutingAssignments.assignedUserId].value
            val creatorUId = row[ScoutingAssignments.createdByUserId]?.value
            val mKey = row[ScoutingAssignments.matchKey]
            val tNum = row[ScoutingAssignments.targetTeamNumber]
            val eKey = row[ScoutingAssignments.eventKey]

            ScoutingAssignmentRecord(
                id = row[ScoutingAssignments.id].value.toString(),
                ownerTeamNumber = row[ScoutingAssignments.ownerTeamNumber],
                program = row[ScoutingAssignments.program],
                eventKey = row[ScoutingAssignments.eventKey],
                assignedUserId = assignedUId.toString(),
                assignedUsername = userMap[assignedUId] ?: "Unknown",
                assignmentType = row[ScoutingAssignments.assignmentType],
                matchKey = row[ScoutingAssignments.matchKey],
                matchNumber = row[ScoutingAssignments.matchNumber],
                compLevel = row[ScoutingAssignments.compLevel],
                targetTeamNumber = row[ScoutingAssignments.targetTeamNumber],
                targetTeamName = if (tNum != null) teamNicknames[Pair(eKey, tNum)] else null,
                allianceColor = row[ScoutingAssignments.allianceColor],
                status = row[ScoutingAssignments.status],
                notes = row[ScoutingAssignments.notes],
                createdByUserId = creatorUId?.toString(),
                createdByUsername = creatorUId?.let { userMap[it] },
                createdAt = row[ScoutingAssignments.createdAt].toString(),
                updatedAt = row[ScoutingAssignments.updatedAt].toString(),
                completedAt = row[ScoutingAssignments.completedAt]?.toString(),
                scheduledTime = mKey?.let { matchTimes[it] },
                reminderMinutesBefore = row[ScoutingAssignments.reminderMinutesBefore],
                pushReminderSentAt = row[ScoutingAssignments.pushReminderSentAt]?.toString(),
                emailReminderSentAt = row[ScoutingAssignments.emailReminderSentAt]?.toString()
            )
        }
    }
}
