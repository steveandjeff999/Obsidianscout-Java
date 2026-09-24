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
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.routes.MatchRecord
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.lowerCase
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
    val predictedTime: Long? = null,
    val scheduleOffsetSeconds: Long? = null,
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
data class AutoGenerateAssignmentsRequest(
    val eventKey: String,
    val assignmentType: String, // MATCH | QUALITATIVE_BOTH | QUALITATIVE_RED | QUALITATIVE_BLUE | QUALITATIVE_TEAMS | PIT_AUTO
    val scouterUserIds: List<String> = emptyList(),
    val stageFilter: String? = "all", // all | qm | qf | sf | f | pr
    val startMatchKey: String? = null,
    val endMatchKey: String? = null,
    val consecutiveMatches: Int = 5,
    val overwrite: Boolean = false,
    val pitScope: String = "unassigned", // unassigned | all
    val reminderMinutesBefore: Int? = null
)

@Serializable
data class AutoGenerateAssignmentsResponse(
    val success: Boolean = true,
    val createdCount: Int = 0,
    val deletedCount: Int = 0,
    val message: String = "",
    val assignments: List<ScoutingAssignmentRecord> = emptyList()
)

@Serializable
data class AutoResolveConflictsRequest(
    val eventKey: String
)

@Serializable
data class AutoResolveConflictsResponse(
    val success: Boolean = true,
    val resolvedCount: Int = 0,
    val message: String = "",
    val deletedIds: List<String> = emptyList()
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
                (Users.id eq targetUserUuid) and (Users.program.lowerCase() eq session.program.lowercase().trim()) and
                (if (session.role != UserRole.SUPERADMIN) Users.teamNumber eq session.teamNumber else Users.teamNumber eq Users.teamNumber)
            }.firstOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Assigned user not found on team.")

            val effectiveOwnerTeam = if (session.role == UserRole.SUPERADMIN && session.teamNumber == 0) {
                userRow[Users.teamNumber]
            } else {
                session.teamNumber
            }

            ScoutingAssignments.insertAndGetId {
                it[ownerTeamNumber] = effectiveOwnerTeam
                it[program] = session.program.uppercase().trim()
                it[eventKey] = eventKeyClean.lowercase()
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

    private data class ValidatedBulkItem(
        val raw: BulkAssignmentItem,
        val targetUuid: UUID,
        val typeClean: String,
        val effectiveOwnerTeam: Int
    )

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
                (Users.id inList userIds) and (Users.program.lowerCase() eq session.program.lowercase().trim()) and
                (if (session.role != UserRole.SUPERADMIN) Users.teamNumber eq session.teamNumber else Users.teamNumber eq Users.teamNumber)
            }.associateBy { it[Users.id].value }

            val validItems = mutableListOf<ValidatedBulkItem>()
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
                validItems.add(ValidatedBulkItem(item, targetUuid, typeClean, effectiveOwnerTeam))
            }

            if (validItems.isEmpty()) return@transaction emptyList()

            val inserted = ScoutingAssignments.batchInsert(validItems, shouldReturnGeneratedValues = true) { item ->
                this[ScoutingAssignments.ownerTeamNumber] = item.effectiveOwnerTeam
                this[ScoutingAssignments.program] = session.program.uppercase().trim()
                this[ScoutingAssignments.eventKey] = eventKeyClean.lowercase()
                this[ScoutingAssignments.assignedUserId] = item.targetUuid
                this[ScoutingAssignments.assignmentType] = item.typeClean
                this[ScoutingAssignments.matchKey] = item.raw.matchKey?.trim()?.ifBlank { null }
                this[ScoutingAssignments.matchNumber] = item.raw.matchNumber
                this[ScoutingAssignments.compLevel] = item.raw.compLevel?.trim()?.ifBlank { null }
                this[ScoutingAssignments.targetTeamNumber] = item.raw.targetTeamNumber
                this[ScoutingAssignments.allianceColor] = item.raw.allianceColor?.trim()?.uppercase()?.ifBlank { null }
                this[ScoutingAssignments.status] = "PENDING"
                this[ScoutingAssignments.notes] = item.raw.notes?.trim()?.ifBlank { null }
                this[ScoutingAssignments.createdByUserId] = creatorUuid
                this[ScoutingAssignments.createdAt] = now
                this[ScoutingAssignments.updatedAt] = now
                this[ScoutingAssignments.reminderMinutesBefore] = request.reminderMinutesBefore
            }
            inserted.map { it[ScoutingAssignments.id].value }
        }

        if (createdIds.isEmpty()) return emptyList()

        return readTransaction {
            val fullRows = ScoutingAssignments.selectAll().where { ScoutingAssignments.id inList createdIds }.toList()
            mapRowsToRecords(fullRows)
        }
    }

    private fun compLevelRank(compLevel: String?): Int {
        if (compLevel.isNullOrBlank()) return 1
        return when (compLevel.trim().lowercase()) {
            "pr", "practice", "pm" -> 0
            "qm", "qual", "quals", "qualification" -> 1
            "ef", "einstein" -> 2
            "qf", "quarterfinal", "quarterfinals" -> 3
            "sf", "semifinal", "semifinals" -> 4
            "f", "final", "finals" -> 5
            "playoff", "playoffs" -> 6
            else -> 1
        }
    }

    private fun parseTeamNum(keyOrStr: String?): Int? {
        if (keyOrStr.isNullOrBlank()) return null
        val clean = keyOrStr.replace(Regex("^(frc|ftc)", RegexOption.IGNORE_CASE), "").trim()
        return clean.toIntOrNull() ?: clean.replace(Regex("[^0-9]"), "").toIntOrNull()
    }

    fun autoGenerateAssignments(session: UserSession, request: AutoGenerateAssignmentsRequest): AutoGenerateAssignmentsResponse {
        val eventKeyClean = request.eventKey.trim().lowercase()
        if (eventKeyClean.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Event key is required.")
        }
        val typeUpper = request.assignmentType.trim().uppercase()
        if (typeUpper !in listOf("MATCH", "QUALITATIVE_BOTH", "QUALITATIVE_RED", "QUALITATIVE_BLUE", "QUALITATIVE_TEAMS", "PIT_AUTO", "PIT")) {
            throw ApiException(HttpStatusCode.BadRequest, "Unsupported assignment type: $typeUpper")
        }

        val rawUserIds = request.scouterUserIds.mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }.distinct()
        if (rawUserIds.isEmpty()) {
            throw ApiException(HttpStatusCode.BadRequest, "Please select at least one scouter.")
        }

        val now = Instant.now()
        val creatorUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()

        return transaction {
            // Validate scouters belong to team/program
            val userRows = Users.selectAll().where {
                (Users.id inList rawUserIds) and
                (Users.program.lowerCase() eq session.program.lowercase().trim()) and
                (if (session.role != UserRole.SUPERADMIN) Users.teamNumber eq session.teamNumber else Users.teamNumber eq Users.teamNumber)
            }.toList()

            if (userRows.isEmpty()) {
                throw ApiException(HttpStatusCode.BadRequest, "No active team scouters found.")
            }

            // Map user UUID to effective owner team
            val userOwnerMap = userRows.associate {
                val ownerTeam = if (session.role == UserRole.SUPERADMIN && session.teamNumber == 0) {
                    it[Users.teamNumber]
                } else {
                    session.teamNumber
                }
                it[Users.id].value to ownerTeam
            }
            val scouterPool = userRows.map { it[Users.id].value }

            // Existing assignments query
            val existingQuery = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim()) and
                (ScoutingAssignments.eventKey.lowerCase() eq eventKeyClean)
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                existingQuery.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            val existingAssignments = existingQuery.toList()

            val toDeleteIds = mutableSetOf<UUID>()
            data class BulkItem(
                val targetUuid: UUID,
                val effectiveOwnerTeam: Int,
                val typeClean: String,
                val matchKey: String? = null,
                val matchNumber: Int? = null,
                val compLevel: String? = null,
                val targetTeamNumber: Int? = null,
                val allianceColor: String? = null,
                val notes: String? = null
            )
            val itemsToInsert = mutableListOf<BulkItem>()

            if (typeUpper == "PIT_AUTO" || typeUpper == "PIT") {
                val allTeams = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKeyClean }
                    .orderBy(ApiTeams.teamNumber to SortOrder.ASC)
                    .toList()

                if (allTeams.isEmpty()) {
                    throw ApiException(HttpStatusCode.BadRequest, "No teams found for event $eventKeyClean.")
                }

                val existingPit = existingAssignments.filter { it[ScoutingAssignments.assignmentType] == "PIT" }
                val existingTeamNums = existingPit.mapNotNull { it[ScoutingAssignments.targetTeamNumber] }.toSet()

                val targetTeams = if (request.pitScope.trim().lowercase() == "all") {
                    allTeams
                } else {
                    allTeams.filter { it[ApiTeams.teamNumber] !in existingTeamNums }
                }

                if (targetTeams.isEmpty()) {
                    return@transaction AutoGenerateAssignmentsResponse(
                        success = true,
                        createdCount = 0,
                        deletedCount = 0,
                        message = "All teams are already assigned for pit scouting."
                    )
                }

                if (request.overwrite) {
                    val targetTeamNums = targetTeams.map { it[ApiTeams.teamNumber] }.toSet()
                    val overwriteIds = existingPit.filter { it[ScoutingAssignments.targetTeamNumber] in targetTeamNums }
                        .map { it[ScoutingAssignments.id].value }
                    toDeleteIds.addAll(overwriteIds)
                }

                targetTeams.forEachIndexed { idx, tRow ->
                    val tNum = tRow[ApiTeams.teamNumber]
                    val scouterId = scouterPool[idx % scouterPool.size]
                    val nickname = tRow[ApiTeams.nickname] ?: tRow[ApiTeams.name]
                    itemsToInsert.add(
                        BulkItem(
                            targetUuid = scouterId,
                            effectiveOwnerTeam = userOwnerMap[scouterId] ?: session.teamNumber,
                            typeClean = "PIT",
                            targetTeamNumber = tNum,
                            notes = if (!nickname.isNullOrBlank()) "Pit Scouting - $nickname" else "Pit Scouting"
                        )
                    )
                }
            } else {
                // Match or Qualitative Assignment
                val allMatchesQuery = ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKeyClean }
                val rawMatches = allMatchesQuery.toList()
                if (rawMatches.isEmpty()) {
                    throw ApiException(HttpStatusCode.BadRequest, "No matches found for event $eventKeyClean.")
                }

                // Stage filter
                val stage = request.stageFilter?.trim()?.lowercase() ?: "all"
                val filteredMatches = rawMatches.filter { mRow ->
                    val lvl = (mRow[ApiMatches.compLevel] ?: "").lowercase()
                    when (stage) {
                        "qm", "qual", "quals" -> lvl in listOf("qm", "qual", "quals", "qualification")
                        "pr", "practice", "pm" -> lvl in listOf("pr", "practice", "pm")
                        "qf" -> lvl == "qf"
                        "sf" -> lvl == "sf"
                        "f", "final", "finals" -> lvl in listOf("f", "final", "finals")
                        "playoffs", "playoff" -> lvl in listOf("qf", "sf", "f", "ef", "playoff", "playoffs")
                        else -> true
                    }
                }.sortedWith(
                    compareBy(
                        { compLevelRank(it[ApiMatches.compLevel]) },
                        { it[ApiMatches.setNumber] ?: 1 },
                        { it[ApiMatches.matchNumber] ?: 0 }
                    )
                )

                if (filteredMatches.isEmpty()) {
                    throw ApiException(HttpStatusCode.BadRequest, "No matches found matching tournament stage ($stage).")
                }

                val startIdx = if (!request.startMatchKey.isNullOrBlank()) {
                    filteredMatches.indexOfFirst { it[ApiMatches.matchKey].equals(request.startMatchKey.trim(), ignoreCase = true) }.takeIf { it >= 0 } ?: 0
                } else 0

                val endIdx = if (!request.endMatchKey.isNullOrBlank()) {
                    filteredMatches.indexOfFirst { it[ApiMatches.matchKey].equals(request.endMatchKey.trim(), ignoreCase = true) }.takeIf { it >= 0 } ?: (filteredMatches.size - 1)
                } else (filteredMatches.size - 1)

                if (startIdx > endIdx) {
                    throw ApiException(HttpStatusCode.BadRequest, "Start match must come before or equal to End match.")
                }

                val targetMatches = filteredMatches.subList(startIdx, endIdx + 1)
                val consecutive = request.consecutiveMatches.coerceAtLeast(1)

                fun pickScouterForMatch(preferredIdx: Int, busyInMatch: MutableSet<UUID>): UUID {
                    val preferredId = scouterPool[preferredIdx % scouterPool.size]
                    if (!busyInMatch.contains(preferredId)) {
                        busyInMatch.add(preferredId)
                        return preferredId
                    }
                    for (offset in 1 until scouterPool.size) {
                        val altId = scouterPool[(preferredIdx + offset) % scouterPool.size]
                        if (!busyInMatch.contains(altId)) {
                            busyInMatch.add(altId)
                            return altId
                        }
                    }
                    busyInMatch.add(preferredId)
                    return preferredId
                }

                if (typeUpper == "MATCH") {
                    targetMatches.forEachIndexed { mIdx, mRow ->
                        val mKey = mRow[ApiMatches.matchKey]
                        val mNum = mRow[ApiMatches.matchNumber]
                        val comp = mRow[ApiMatches.compLevel]
                        val block = mIdx / consecutive

                        val redTeams = mRow[ApiMatches.redTeams].split(",").mapNotNull { parseTeamNum(it) }
                        val blueTeams = mRow[ApiMatches.blueTeams].split(",").mapNotNull { parseTeamNum(it) }
                        val allTeamsInMatch = redTeams + blueTeams

                        val busyInMatch = mutableSetOf<UUID>()
                        existingAssignments.forEach { a ->
                            val aKey = a[ScoutingAssignments.matchKey]
                            val aNum = a[ScoutingAssignments.matchNumber]
                            val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                            if (sameMatch) {
                                if (a[ScoutingAssignments.assignmentType] == "MATCH" && request.overwrite) {
                                    // Overwrite: do not mark busy
                                } else {
                                    busyInMatch.add(a[ScoutingAssignments.assignedUserId].value)
                                }
                            }
                        }

                        allTeamsInMatch.forEachIndexed { sIdx, teamNum ->
                            val alliance = if (sIdx < redTeams.size) "RED" else "BLUE"
                            val existingSlot = existingAssignments.filter { a ->
                                val aKey = a[ScoutingAssignments.matchKey]
                                val aNum = a[ScoutingAssignments.matchNumber]
                                val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                                a[ScoutingAssignments.assignmentType] == "MATCH" && sameMatch && a[ScoutingAssignments.targetTeamNumber] == teamNum
                            }

                            if (existingSlot.isNotEmpty()) {
                                if (!request.overwrite) {
                                    return@forEachIndexed
                                } else {
                                    toDeleteIds.addAll(existingSlot.map { it[ScoutingAssignments.id].value })
                                }
                            }

                            val preferredIdx = block * allTeamsInMatch.size + sIdx
                            val scouterId = pickScouterForMatch(preferredIdx, busyInMatch)
                            itemsToInsert.add(
                                BulkItem(
                                    targetUuid = scouterId,
                                    effectiveOwnerTeam = userOwnerMap[scouterId] ?: session.teamNumber,
                                    typeClean = "MATCH",
                                    matchKey = mKey,
                                    matchNumber = mNum,
                                    compLevel = comp,
                                    targetTeamNumber = teamNum,
                                    allianceColor = alliance,
                                    notes = "Match Scouting - Team $teamNum ($alliance)"
                                )
                            )
                        }
                    }
                } else if (typeUpper == "QUALITATIVE_BOTH") {
                    targetMatches.forEachIndexed { mIdx, mRow ->
                        val mKey = mRow[ApiMatches.matchKey]
                        val mNum = mRow[ApiMatches.matchNumber]
                        val comp = mRow[ApiMatches.compLevel]
                        val block = mIdx / consecutive

                        val busyInMatch = mutableSetOf<UUID>()
                        existingAssignments.forEach { a ->
                            val aKey = a[ScoutingAssignments.matchKey]
                            val aNum = a[ScoutingAssignments.matchNumber]
                            val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                            if (sameMatch) {
                                if (a[ScoutingAssignments.assignmentType] == "QUALITATIVE" && request.overwrite) {
                                    // Overwrite: will be deleted
                                } else {
                                    busyInMatch.add(a[ScoutingAssignments.assignedUserId].value)
                                }
                            }
                        }

                        val existingQual = existingAssignments.filter { a ->
                            val aKey = a[ScoutingAssignments.matchKey]
                            val aNum = a[ScoutingAssignments.matchNumber]
                            val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                            a[ScoutingAssignments.assignmentType] == "QUALITATIVE" && sameMatch
                        }

                        if (existingQual.isNotEmpty()) {
                            if (!request.overwrite) {
                                return@forEachIndexed
                            } else {
                                toDeleteIds.addAll(existingQual.map { it[ScoutingAssignments.id].value })
                            }
                        }

                        val scouterId = pickScouterForMatch(block, busyInMatch)
                        itemsToInsert.add(
                            BulkItem(
                                targetUuid = scouterId,
                                effectiveOwnerTeam = userOwnerMap[scouterId] ?: session.teamNumber,
                                typeClean = "QUALITATIVE",
                                matchKey = mKey,
                                matchNumber = mNum,
                                compLevel = comp,
                                targetTeamNumber = null,
                                allianceColor = "RED",
                                notes = "Qualitative scouting for RED alliance (Both Alliances)"
                            )
                        )
                        itemsToInsert.add(
                            BulkItem(
                                targetUuid = scouterId,
                                effectiveOwnerTeam = userOwnerMap[scouterId] ?: session.teamNumber,
                                typeClean = "QUALITATIVE",
                                matchKey = mKey,
                                matchNumber = mNum,
                                compLevel = comp,
                                targetTeamNumber = null,
                                allianceColor = "BLUE",
                                notes = "Qualitative scouting for BLUE alliance (Both Alliances)"
                            )
                        )
                    }
                } else if (typeUpper == "QUALITATIVE_RED" || typeUpper == "QUALITATIVE_BLUE") {
                    val targetAlliance = if (typeUpper == "QUALITATIVE_RED") "RED" else "BLUE"
                    targetMatches.forEachIndexed { mIdx, mRow ->
                        val mKey = mRow[ApiMatches.matchKey]
                        val mNum = mRow[ApiMatches.matchNumber]
                        val comp = mRow[ApiMatches.compLevel]
                        val block = mIdx / consecutive

                        val targetAllianceTeams = if (targetAlliance == "RED") {
                            mRow[ApiMatches.redTeams].split(",").mapNotNull { parseTeamNum(it) }
                        } else {
                            mRow[ApiMatches.blueTeams].split(",").mapNotNull { parseTeamNum(it) }
                        }

                        val busyInMatch = mutableSetOf<UUID>()
                        existingAssignments.forEach { a ->
                            val aKey = a[ScoutingAssignments.matchKey]
                            val aNum = a[ScoutingAssignments.matchNumber]
                            val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                            if (sameMatch) {
                                val isSameAllianceQual = a[ScoutingAssignments.assignmentType] == "QUALITATIVE" &&
                                    ((a[ScoutingAssignments.targetTeamNumber] == null && a[ScoutingAssignments.allianceColor]?.uppercase() == targetAlliance) ||
                                     (a[ScoutingAssignments.targetTeamNumber] != null && a[ScoutingAssignments.targetTeamNumber] in targetAllianceTeams))
                                if (isSameAllianceQual && request.overwrite) {
                                    // Overwrite: will be deleted
                                } else {
                                    busyInMatch.add(a[ScoutingAssignments.assignedUserId].value)
                                }
                            }
                        }

                        val existingQual = existingAssignments.filter { a ->
                            val aKey = a[ScoutingAssignments.matchKey]
                            val aNum = a[ScoutingAssignments.matchNumber]
                            val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                            a[ScoutingAssignments.assignmentType] == "QUALITATIVE" && sameMatch &&
                            ((a[ScoutingAssignments.targetTeamNumber] == null && a[ScoutingAssignments.allianceColor]?.uppercase() == targetAlliance) ||
                             (a[ScoutingAssignments.targetTeamNumber] != null && a[ScoutingAssignments.targetTeamNumber] in targetAllianceTeams))
                        }

                        if (existingQual.isNotEmpty()) {
                            if (!request.overwrite) {
                                return@forEachIndexed
                            } else {
                                toDeleteIds.addAll(existingQual.map { it[ScoutingAssignments.id].value })
                            }
                        }

                        val scouterId = pickScouterForMatch(block, busyInMatch)
                        itemsToInsert.add(
                            BulkItem(
                                targetUuid = scouterId,
                                effectiveOwnerTeam = userOwnerMap[scouterId] ?: session.teamNumber,
                                typeClean = "QUALITATIVE",
                                matchKey = mKey,
                                matchNumber = mNum,
                                compLevel = comp,
                                targetTeamNumber = null,
                                allianceColor = targetAlliance,
                                notes = "Qualitative scouting for $targetAlliance alliance"
                            )
                        )
                    }
                } else if (typeUpper == "QUALITATIVE_TEAMS") {
                    targetMatches.forEachIndexed { mIdx, mRow ->
                        val mKey = mRow[ApiMatches.matchKey]
                        val mNum = mRow[ApiMatches.matchNumber]
                        val comp = mRow[ApiMatches.compLevel]
                        val block = mIdx / consecutive

                        val redTeams = mRow[ApiMatches.redTeams].split(",").mapNotNull { parseTeamNum(it) }
                        val blueTeams = mRow[ApiMatches.blueTeams].split(",").mapNotNull { parseTeamNum(it) }
                        val allTeamsInMatch = redTeams + blueTeams

                        // If overwrite is true, delete existing individual team qual assignments AND any alliance-level qual assignments for this match
                        if (request.overwrite) {
                            val existingMatchQual = existingAssignments.filter { a ->
                                val aKey = a[ScoutingAssignments.matchKey]
                                val aNum = a[ScoutingAssignments.matchNumber]
                                val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                                a[ScoutingAssignments.assignmentType] == "QUALITATIVE" && sameMatch
                            }
                            toDeleteIds.addAll(existingMatchQual.map { it[ScoutingAssignments.id].value })
                        }

                        val busyInMatch = mutableSetOf<UUID>()
                        existingAssignments.forEach { a ->
                            val aKey = a[ScoutingAssignments.matchKey]
                            val aNum = a[ScoutingAssignments.matchNumber]
                            val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                            if (sameMatch) {
                                if (a[ScoutingAssignments.assignmentType] == "QUALITATIVE" && request.overwrite) {
                                    // Overwrite: will be deleted
                                } else {
                                    busyInMatch.add(a[ScoutingAssignments.assignedUserId].value)
                                }
                            }
                        }

                        allTeamsInMatch.forEachIndexed { sIdx, teamNum ->
                            val alliance = if (sIdx < redTeams.size) "RED" else "BLUE"
                            val existingSlot = existingAssignments.filter { a ->
                                val aKey = a[ScoutingAssignments.matchKey]
                                val aNum = a[ScoutingAssignments.matchNumber]
                                val sameMatch = (aKey != null && aKey.equals(mKey, ignoreCase = true)) || (mNum != null && aNum == mNum)
                                a[ScoutingAssignments.assignmentType] == "QUALITATIVE" && sameMatch && a[ScoutingAssignments.targetTeamNumber] == teamNum
                            }

                            if (existingSlot.isNotEmpty() && !request.overwrite) {
                                return@forEachIndexed
                            }

                            val preferredIdx = block * allTeamsInMatch.size + sIdx
                            val scouterId = pickScouterForMatch(preferredIdx, busyInMatch)
                            itemsToInsert.add(
                                BulkItem(
                                    targetUuid = scouterId,
                                    effectiveOwnerTeam = userOwnerMap[scouterId] ?: session.teamNumber,
                                    typeClean = "QUALITATIVE",
                                    matchKey = mKey,
                                    matchNumber = mNum,
                                    compLevel = comp,
                                    targetTeamNumber = teamNum,
                                    allianceColor = alliance,
                                    notes = "Qualitative scouting for Team #$teamNum ($alliance)"
                                )
                            )
                        }
                    }
                }
            }

            // Perform deletions if any
            var deletedCount = 0
            if (toDeleteIds.isNotEmpty()) {
                deletedCount = ScoutingAssignments.deleteWhere { ScoutingAssignments.id inList toDeleteIds }
            }

            // Batch insert all generated items
            if (itemsToInsert.isNotEmpty()) {
                ScoutingAssignments.batchInsert(itemsToInsert, shouldReturnGeneratedValues = false) { item ->
                    this[ScoutingAssignments.ownerTeamNumber] = item.effectiveOwnerTeam
                    this[ScoutingAssignments.program] = session.program.uppercase().trim()
                    this[ScoutingAssignments.eventKey] = eventKeyClean
                    this[ScoutingAssignments.assignedUserId] = item.targetUuid
                    this[ScoutingAssignments.assignmentType] = item.typeClean
                    this[ScoutingAssignments.matchKey] = item.matchKey?.trim()?.ifBlank { null }
                    this[ScoutingAssignments.matchNumber] = item.matchNumber
                    this[ScoutingAssignments.compLevel] = item.compLevel?.trim()?.ifBlank { null }
                    this[ScoutingAssignments.targetTeamNumber] = item.targetTeamNumber
                    this[ScoutingAssignments.allianceColor] = item.allianceColor?.trim()?.uppercase()?.ifBlank { null }
                    this[ScoutingAssignments.status] = "PENDING"
                    this[ScoutingAssignments.notes] = item.notes?.trim()?.ifBlank { null }
                    this[ScoutingAssignments.createdByUserId] = creatorUuid
                    this[ScoutingAssignments.createdAt] = now
                    this[ScoutingAssignments.updatedAt] = now
                    this[ScoutingAssignments.reminderMinutesBefore] = request.reminderMinutesBefore
                }
            }

            AutoGenerateAssignmentsResponse(
                success = true,
                createdCount = itemsToInsert.size,
                deletedCount = deletedCount,
                message = "Successfully created ${itemsToInsert.size} assignment(s)${if (deletedCount > 0) " (replaced $deletedCount existing)" else ""}."
            )
        }
    }

    fun updateAssignment(session: UserSession, id: String, request: UpdateAssignmentRequest): ScoutingAssignmentRecord {
        val assignmentUuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid assignment ID.")

        val now = Instant.now()
        transaction {
            val query = ScoutingAssignments.selectAll().where { (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim()) }
            if (session.role != UserRole.SUPERADMIN && session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            val existing = query.firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "Assignment not found.")

            val newTargetUser = if (!request.assignedUserId.isNullOrBlank()) {
                val userUuid = runCatching { UUID.fromString(request.assignedUserId) }.getOrNull()
                    ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID.")
                val userExists = Users.selectAll().where { (Users.id eq userUuid) and (Users.program.lowerCase() eq session.program.lowercase().trim()) }.count() > 0
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
                (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim())
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
            val query = (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim())
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
                (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim())
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
            val query = ScoutingAssignments.selectAll().where { ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim() }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.eventKey.lowerCase() eq eventKey.trim().lowercase() }
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
                (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim())
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.eventKey.lowerCase() eq eventKey.trim().lowercase() }
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
                (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim()) and
                (ScoutingAssignments.eventKey.lowerCase() eq eventKey.trim().lowercase()) and
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
                (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim()) and
                (ScoutingAssignments.eventKey.lowerCase() eq eventKey.trim().lowercase())
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

    fun deleteAllAssignments(
        session: UserSession,
        eventKey: String,
        assignmentType: String? = null,
        specificIds: List<String>? = null
    ): Int {
        val eventKeyClean = eventKey.trim()
        if (eventKeyClean.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Event key is required.")
        }
        return transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim()) and
                (ScoutingAssignments.eventKey.lowerCase() eq eventKeyClean.lowercase())
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            if (!assignmentType.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.assignmentType eq assignmentType.trim().uppercase() }
            }
            if (!specificIds.isNullOrEmpty()) {
                val uuids = specificIds.mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
                if (uuids.isNotEmpty()) {
                    query.andWhere { ScoutingAssignments.id inList uuids }
                }
            }
            val ids = query.map { it[ScoutingAssignments.id].value }
            if (ids.isNotEmpty()) {
                ScoutingAssignments.deleteWhere { ScoutingAssignments.id inList ids }
            } else 0
        }
    }

    fun autoResolveConflicts(session: UserSession, eventKey: String): AutoResolveConflictsResponse {
        val eventKeyClean = eventKey.trim().lowercase()
        if (eventKeyClean.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Event key is required.")
        }

        return transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program.lowerCase() eq session.program.lowercase().trim()) and
                (ScoutingAssignments.eventKey.lowerCase() eq eventKeyClean) and
                (ScoutingAssignments.status neq "SKIPPED") and
                (ScoutingAssignments.status neq "CANCELLED")
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            val rows = query.toList()
            if (rows.isEmpty()) {
                return@transaction AutoResolveConflictsResponse(success = true, resolvedCount = 0, message = "No assignments found.")
            }

            val toDeleteIds = mutableSetOf<UUID>()

            // 1. Same-slot duplicate assignments
            val slotMap = mutableMapOf<String, MutableList<org.jetbrains.exposed.sql.ResultRow>>()
            for (row in rows) {
                val type = row[ScoutingAssignments.assignmentType].uppercase()
                val mKey = row[ScoutingAssignments.matchKey]?.lowercase() ?: (row[ScoutingAssignments.matchNumber]?.let { "m$it" } ?: "")
                val tNum = row[ScoutingAssignments.targetTeamNumber]?.toString() ?: ""
                val alliance = row[ScoutingAssignments.allianceColor]?.lowercase() ?: ""
                val slotKey = when (type) {
                    "MATCH" -> "MATCH:$mKey:$tNum"
                    "PIT" -> "PIT:$tNum"
                    "QUALITATIVE" -> "QUAL:$mKey:${if (tNum.isNotBlank()) "team_$tNum" else "alliance_$alliance"}"
                    else -> "$type:$mKey:$tNum:$alliance"
                }
                slotMap.getOrPut(slotKey) { mutableListOf() }.add(row)
            }

            for ((_, slotRows) in slotMap) {
                if (slotRows.size > 1) {
                    // Priority: Keep COMPLETED (score 3), IN_PROGRESS (score 2), has notes, oldest
                    val sorted = slotRows.sortedWith(compareByDescending<org.jetbrains.exposed.sql.ResultRow> {
                        when (it[ScoutingAssignments.status].uppercase()) {
                            "COMPLETED" -> 3
                            "IN_PROGRESS" -> 2
                            else -> 1
                        }
                    }.thenByDescending { !it[ScoutingAssignments.notes].isNullOrBlank() }
                     .thenBy { it[ScoutingAssignments.createdAt] })

                    val duplicates = sorted.drop(1)
                    duplicates.forEach { toDeleteIds.add(it[ScoutingAssignments.id].value) }
                }
            }

            // 2. Redundant qualitative assignments (alliance level vs individual team level)
            val matchQual = rows.filter {
                it[ScoutingAssignments.id].value !in toDeleteIds &&
                it[ScoutingAssignments.assignmentType].uppercase() == "QUALITATIVE"
            }.groupBy { it[ScoutingAssignments.matchKey]?.lowercase() ?: "m${it[ScoutingAssignments.matchNumber]}" }

            for ((_, mQualRows) in matchQual) {
                val allianceRows = mQualRows.filter { it[ScoutingAssignments.targetTeamNumber] == null && it[ScoutingAssignments.allianceColor] != null }
                val teamRows = mQualRows.filter { it[ScoutingAssignments.targetTeamNumber] != null }

                for (aRow in allianceRows) {
                    val allianceColor = aRow[ScoutingAssignments.allianceColor]?.uppercase() ?: continue
                    val matchingTeamRows = teamRows.filter { it[ScoutingAssignments.allianceColor]?.uppercase() == allianceColor }
                    if (matchingTeamRows.isNotEmpty()) {
                        val anyTeamCompleted = matchingTeamRows.any { it[ScoutingAssignments.status].uppercase() == "COMPLETED" }
                        val allianceCompleted = aRow[ScoutingAssignments.status].uppercase() == "COMPLETED"
                        if (anyTeamCompleted && !allianceCompleted) {
                            toDeleteIds.add(aRow[ScoutingAssignments.id].value)
                        } else {
                            matchingTeamRows.forEach { toDeleteIds.add(it[ScoutingAssignments.id].value) }
                        }
                    }
                }
            }

            // 3. Dual-scouter match overlaps (single user assigned multiple roles in same match)
            val activeRows = rows.filter { it[ScoutingAssignments.id].value !in toDeleteIds }
            val userMatchGroups = activeRows.filter {
                val type = it[ScoutingAssignments.assignmentType].uppercase()
                type == "MATCH" || type == "QUALITATIVE"
            }.groupBy {
                val uId = it[ScoutingAssignments.assignedUserId].value
                val mKey = it[ScoutingAssignments.matchKey]?.lowercase() ?: "m${it[ScoutingAssignments.matchNumber]}"
                "$uId:$mKey"
            }

            for ((_, uMatchRows) in userMatchGroups) {
                val isBothAlliancesQualPair = uMatchRows.size == 2 &&
                    uMatchRows.all { it[ScoutingAssignments.assignmentType].uppercase() == "QUALITATIVE" && it[ScoutingAssignments.targetTeamNumber] == null } &&
                    (uMatchRows[0][ScoutingAssignments.allianceColor]?.lowercase() != uMatchRows[1][ScoutingAssignments.allianceColor]?.lowercase())

                if (uMatchRows.size > 1 && !isBothAlliancesQualPair) {
                    val sorted = uMatchRows.sortedWith(compareByDescending<org.jetbrains.exposed.sql.ResultRow> {
                        when (it[ScoutingAssignments.status].uppercase()) {
                            "COMPLETED" -> 3
                            "IN_PROGRESS" -> 2
                            else -> 1
                        }
                    }.thenByDescending { it[ScoutingAssignments.assignmentType].uppercase() == "MATCH" }
                     .thenBy { it[ScoutingAssignments.createdAt] })

                    val extras = sorted.drop(1)
                    extras.forEach { toDeleteIds.add(it[ScoutingAssignments.id].value) }
                }
            }

            val deletedCount = if (toDeleteIds.isNotEmpty()) {
                ScoutingAssignments.deleteWhere { ScoutingAssignments.id inList toDeleteIds }
            } else 0

            AutoResolveConflictsResponse(
                success = true,
                resolvedCount = deletedCount,
                message = if (deletedCount > 0) "Auto-resolved and cleaned up $deletedCount conflict(s)." else "No conflicts found.",
                deletedIds = toDeleteIds.map { it.toString() }
            )
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
        val eKey = row[ScoutingAssignments.eventKey]
        val prog = row[ScoutingAssignments.program]
        val matchRecord: MatchRecord? = if (!mKey.isNullOrBlank() && eKey.isNotBlank()) {
            IntegrationService.listMatches(eKey, prog).firstOrNull { it.matchKey.equals(mKey, ignoreCase = true) }
        } else null

        val matchScheduledTime = matchRecord?.scheduledTime ?: if (!mKey.isNullOrBlank()) {
            ApiMatches.selectAll().where { ApiMatches.matchKey eq mKey }.firstOrNull()?.get(ApiMatches.scheduledTime)
        } else null
        val matchPredictedTime = matchRecord?.predictedTime ?: matchScheduledTime
        val matchOffset = matchRecord?.scheduleOffsetSeconds ?: 0L

        val tNum = row[ScoutingAssignments.targetTeamNumber]
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
            completedAt = runCatching { row.getOrNull(ScoutingAssignments.completedAt) }.getOrNull()?.toString(),
            scheduledTime = matchScheduledTime,
            predictedTime = matchPredictedTime,
            scheduleOffsetSeconds = matchOffset,
            reminderMinutesBefore = runCatching { row.getOrNull(ScoutingAssignments.reminderMinutesBefore) }.getOrNull(),
            pushReminderSentAt = runCatching { row.getOrNull(ScoutingAssignments.pushReminderSentAt) }.getOrNull()?.toString(),
            emailReminderSentAt = runCatching { row.getOrNull(ScoutingAssignments.emailReminderSentAt) }.getOrNull()?.toString()
        )
    }

    private fun mapRowsToRecords(rows: List<org.jetbrains.exposed.sql.ResultRow>): List<ScoutingAssignmentRecord> {
        if (rows.isEmpty()) return emptyList()

        val userIds = rows.flatMap {
            val assigned = runCatching { it[ScoutingAssignments.assignedUserId].value }.getOrNull()
            val creator = runCatching { it[ScoutingAssignments.createdByUserId]?.value }.getOrNull()
            listOfNotNull(assigned, creator)
        }.distinct()
        val userMap = if (userIds.isNotEmpty()) {
            Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value to it[Users.username] }
        } else emptyMap()

        val eventProgPairs = rows.mapNotNull {
            val e = runCatching { it[ScoutingAssignments.eventKey] }.getOrNull()
            val p = runCatching { it[ScoutingAssignments.program] }.getOrNull() ?: "FRC"
            if (!e.isNullOrBlank()) Pair(e, p) else null
        }.distinct()
        val matchRecordMap: Map<String, MatchRecord> = eventProgPairs.flatMap { (eKey, prog) ->
            IntegrationService.listMatches(eKey, prog)
        }.associateBy { it.matchKey.lowercase() }

        val matchKeys = rows.mapNotNull { runCatching { it[ScoutingAssignments.matchKey] }.getOrNull() }.filter { it.isNotBlank() }.distinct()
        val matchTimes = if (matchKeys.isNotEmpty()) {
            ApiMatches.selectAll().where { ApiMatches.matchKey inList matchKeys }.associate { it[ApiMatches.matchKey] to it[ApiMatches.scheduledTime] }
        } else emptyMap()

        val eventTeamPairs = rows.mapNotNull {
            val t = runCatching { it[ScoutingAssignments.targetTeamNumber] }.getOrNull()
            val e = runCatching { it[ScoutingAssignments.eventKey] }.getOrNull()
            if (t != null && e != null) Pair(e, t) else null
        }.distinct()

        val teamNicknames = if (eventTeamPairs.isNotEmpty()) {
            val events = eventTeamPairs.map { it.first }.distinct()
            val teams = eventTeamPairs.map { it.second }.distinct()
            ApiTeams.selectAll().where { (ApiTeams.eventKey inList events) and (ApiTeams.teamNumber inList teams) }
                .associate { Pair(it[ApiTeams.eventKey], it[ApiTeams.teamNumber]) to (it[ApiTeams.nickname] ?: it[ApiTeams.name]) }
        } else emptyMap()

        return rows.map { row ->
            val assignedUId = runCatching { row[ScoutingAssignments.assignedUserId].value }.getOrNull()
            val creatorUId = runCatching { row[ScoutingAssignments.createdByUserId]?.value }.getOrNull()
            val mKey = runCatching { row[ScoutingAssignments.matchKey] }.getOrNull()
            val tNum = runCatching { row[ScoutingAssignments.targetTeamNumber] }.getOrNull()
            val eKey = runCatching { row[ScoutingAssignments.eventKey] }.getOrNull() ?: ""

            val mRecord = mKey?.lowercase()?.let { matchRecordMap[it] }
            val sTime = mRecord?.scheduledTime ?: mKey?.let { matchTimes[it] }
            val pTime = mRecord?.predictedTime ?: sTime
            val offset = mRecord?.scheduleOffsetSeconds ?: 0L

            ScoutingAssignmentRecord(
                id = runCatching { row[ScoutingAssignments.id].value.toString() }.getOrNull() ?: "",
                ownerTeamNumber = runCatching { row[ScoutingAssignments.ownerTeamNumber] }.getOrNull() ?: 0,
                program = runCatching { row[ScoutingAssignments.program] }.getOrNull() ?: "FRC",
                eventKey = eKey,
                assignedUserId = assignedUId?.toString() ?: "",
                assignedUsername = assignedUId?.let { userMap[it] } ?: "Unknown",
                assignmentType = runCatching { row[ScoutingAssignments.assignmentType] }.getOrNull() ?: "MATCH",
                matchKey = mKey,
                matchNumber = runCatching { row[ScoutingAssignments.matchNumber] }.getOrNull(),
                compLevel = runCatching { row[ScoutingAssignments.compLevel] }.getOrNull(),
                targetTeamNumber = tNum,
                targetTeamName = if (tNum != null) teamNicknames[Pair(eKey, tNum)] else null,
                allianceColor = runCatching { row[ScoutingAssignments.allianceColor] }.getOrNull(),
                status = runCatching { row[ScoutingAssignments.status] }.getOrNull() ?: "PENDING",
                notes = runCatching { row[ScoutingAssignments.notes] }.getOrNull(),
                createdByUserId = creatorUId?.toString(),
                createdByUsername = creatorUId?.let { userMap[it] },
                createdAt = runCatching { row[ScoutingAssignments.createdAt].toString() }.getOrNull() ?: "",
                updatedAt = runCatching { row[ScoutingAssignments.updatedAt].toString() }.getOrNull() ?: "",
                completedAt = runCatching { row.getOrNull(ScoutingAssignments.completedAt) }.getOrNull()?.toString(),
                scheduledTime = sTime,
                predictedTime = pTime,
                scheduleOffsetSeconds = offset,
                reminderMinutesBefore = runCatching { row.getOrNull(ScoutingAssignments.reminderMinutesBefore) }.getOrNull(),
                pushReminderSentAt = runCatching { row.getOrNull(ScoutingAssignments.pushReminderSentAt) }.getOrNull()?.toString(),
                emailReminderSentAt = runCatching { row.getOrNull(ScoutingAssignments.emailReminderSentAt) }.getOrNull()?.toString()
            )
        }
    }
}
