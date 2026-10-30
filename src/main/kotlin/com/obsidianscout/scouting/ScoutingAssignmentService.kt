package com.obsidianscout.scouting

import com.obsidianscout.integrations.TeamCacheOverrideService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

private val consoleLog = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.scouting.ScoutingAssignmentService")


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
    val emailReminderSentAt: String? = null,
    val scoutGroup: String? = null,
    /** Usernames of everyone in this pit row's scout group on the same team (including the assignee). */
    val scoutGroupMembers: List<String> = emptyList()
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
    val reminderMinutesBefore: Int? = null,
    val scoutGroup: String? = null
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
    val notes: String? = null,
    val scoutGroup: String? = null
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
    val matchKeys: List<String>? = null, // explicit match set (e.g. a time block); overrides start/end when non-empty
    val consecutiveMatches: Int = 5,
    val overwrite: Boolean = false,
    val pitScope: String = "unassigned", // unassigned | all
    val pitGroups: List<PitGroupAssignment> = emptyList(), // PIT_AUTO: groups that each take a team together
    val reminderMinutesBefore: Int? = null
)

@Serializable
data class SavePitGroupsRequest(
    val groups: List<com.obsidianscout.integrations.PitScoutGroup> = emptyList()
)

@Serializable
data class PitGroupAssignment(
    val name: String,
    val memberIds: List<String> = emptyList()
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

@Serializable
data class SaveAssignmentShiftRequest(
    val id: String? = null,
    val eventKey: String = "",
    val name: String = "",
    val day: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val stageFilter: String = "all",
    val matchScouterIds: List<String> = emptyList(),
    val qualMode: String = "TEAMS",
    val qualScouterIds: List<String> = emptyList()
)

@Serializable
data class AssignmentShiftView(
    val shift: com.obsidianscout.integrations.AssignmentShift,
    /** Synced matches currently scheduled inside the shift's window. */
    val matchCount: Int = 0,
    /** Username for each scouter id on the shift. */
    val scouterNames: Map<String, String> = emptyMap()
)

@Serializable
data class AssignmentShiftsResponse(
    val shifts: List<AssignmentShiftView> = emptyList(),
    /** Assignments created by this request (saving a shift). */
    val createdCount: Int = 0
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
                it[scoutGroup] = cleanScoutGroup(request.scoutGroup)
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
                this[ScoutingAssignments.scoutGroup] = cleanScoutGroup(item.raw.scoutGroup)
            }
            inserted.map { it[ScoutingAssignments.id].value }
        }

        if (createdIds.isEmpty()) return emptyList()

        return readTransaction {
            val fullRows = ScoutingAssignments.selectAll().where { ScoutingAssignments.id inList createdIds }.toList()
            mapRowsToRecords(fullRows)
        }
    }

    private fun cleanScoutGroup(name: String?): String? = name?.trim()?.take(64)?.ifBlank { null }

    fun getPitGroups(session: UserSession): List<com.obsidianscout.integrations.PitScoutGroup> =
        com.obsidianscout.integrations.SettingsService.getSettings(session.teamNumber, session.program).pitScoutGroups

    /** Replaces the team's saved pit scout groups. Members must be accounts on the team. */
    fun savePitGroups(
        session: UserSession,
        groups: List<com.obsidianscout.integrations.PitScoutGroup>
    ): List<com.obsidianscout.integrations.PitScoutGroup> {
        val prog = session.program.uppercase().trim()
        val requestedIds = groups.flatMap { it.memberIds }.mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }.distinct()
        val teamUserIds = if (requestedIds.isEmpty()) emptySet() else readTransaction {
            Users.selectAll().where {
                (Users.id inList requestedIds) and (Users.program eq prog) and
                (if (session.role != UserRole.SUPERADMIN) Users.teamNumber eq session.teamNumber else Users.teamNumber eq Users.teamNumber)
            }.map { it[Users.id].value.toString() }.toSet()
        }

        val seenNames = mutableSetOf<String>()
        val cleaned = groups.mapNotNull { g ->
            val name = cleanScoutGroup(g.name) ?: return@mapNotNull null
            if (!seenNames.add(name.lowercase())) {
                throw ApiException(HttpStatusCode.BadRequest, "Group name \"$name\" is used more than once.")
            }
            val members = g.memberIds.map { it.trim().lowercase() }.distinct().filter { it in teamUserIds }
            if (members.isEmpty()) null
            else com.obsidianscout.integrations.PitScoutGroup(
                id = g.id.trim().ifBlank { UUID.randomUUID().toString() }.take(64),
                name = name,
                memberIds = members
            )
        }

        val current = com.obsidianscout.integrations.SettingsService.getSettings(session.teamNumber, session.program)
        return com.obsidianscout.integrations.SettingsService.updateSettings(session.teamNumber, current.copy(pitScoutGroups = cleaned)).pitScoutGroups
    }

    // ==========================================
    // TIME SHIFTS (planned before the schedule exists)
    // ==========================================

    /** Robot stations per match (red 1-3, blue 1-3); FTC fields use the first four. */
    private const val SHIFT_MAX_SEATS = 6
    private val SHIFT_TIME = Regex("""^(\d{1,2}):(\d{2})$""")

    private fun shiftMinutes(time: String): Int? {
        val m = SHIFT_TIME.matchEntire(time.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    private fun matchesStage(compLevel: String?, stage: String?): Boolean {
        val lvl = (compLevel ?: "").lowercase()
        return when (stage?.trim()?.lowercase() ?: "all") {
            "qm", "qual", "quals" -> lvl in listOf("qm", "qual", "quals", "qualification")
            "pr", "practice", "pm" -> lvl in listOf("pr", "practice", "pm")
            "qf" -> lvl == "qf"
            "sf" -> lvl == "sf"
            "f", "final", "finals" -> lvl in listOf("f", "final", "finals")
            "playoffs", "playoff" -> lvl in listOf("qf", "sf", "f", "ef", "playoff", "playoffs")
            else -> true
        }
    }

    private fun eventZone(eventKey: String, fallback: String?): java.time.ZoneId {
        val tz = IntegrationService.getEvent(eventKey)?.timezone?.takeIf { it.isNotBlank() && it != "UTC" } ?: fallback
        return runCatching { java.time.ZoneId.of(tz?.trim().orEmpty().ifBlank { "UTC" }) }.getOrDefault(java.time.ZoneOffset.UTC)
    }

    /** When a synced match starts, in epoch seconds, for placing it in a shift. */
    private class ExpectedStart(val epochSeconds: Long, val compLevel: String?, val played: Boolean)

    /**
     * Start of every synced match of [eventKey], keyed by match key: when it was played, otherwise its schedule
     * shifted by how far ahead or behind the event is running, so shifts follow the field rather than the
     * printed schedule.
     */
    private fun expectedStarts(eventKey: String, program: String, teamNumber: Int): Map<String, ExpectedStart> =
        IntegrationService.listMatches(eventKey.trim().lowercase(), program, teamNumber).mapNotNull { m ->
            val played = m.redScore != null || m.blueScore != null ||
                (m.actualTime != null && m.actualTime > 0 && m.actualTime != m.scheduledTime)
            val raw = (if (played) m.actualTime else m.predictedTime ?: m.scheduledTime)?.takeIf { it > 0 }
                ?: return@mapNotNull null
            m.matchKey to ExpectedStart(if (raw < 100_000_000_000L) raw else raw / 1000, m.compLevel, played)
        }.toMap()

    /** Whether [start] falls in [shift]'s window, in the event's timezone. */
    private fun inShift(shift: com.obsidianscout.integrations.AssignmentShift, zone: java.time.ZoneId, start: ExpectedStart): Boolean {
        val day = runCatching { java.time.LocalDate.parse(shift.day) }.getOrNull() ?: return false
        val startMin = shiftMinutes(shift.startTime) ?: return false
        val endMin = shiftMinutes(shift.endTime) ?: return false
        if (!matchesStage(start.compLevel, shift.stageFilter)) return false
        val local = Instant.ofEpochSecond(start.epochSeconds).atZone(zone)
        val minutes = local.hour * 60 + local.minute
        return local.toLocalDate() == day && minutes >= startMin && minutes < endMin
    }

    /** Keys of synced matches expected to start in [shift]'s window. */
    private fun matchKeysInShift(
        shift: com.obsidianscout.integrations.AssignmentShift,
        zone: java.time.ZoneId,
        starts: Map<String, ExpectedStart>
    ): List<String> = starts.filterValues { inShift(shift, zone, it) }.keys.toList()

    private fun shiftScouterIds(shift: com.obsidianscout.integrations.AssignmentShift): List<String> =
        (shift.matchScouterIds + shift.qualScouterIds).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Gives each of [shift]'s seats its open slot in every match of the window. Never overwrites: a slot that
     * already has a scout, or a scout who already has something in that match, is left alone.
     * Returns the number of assignments created.
     */
    private fun applyShift(
        teamNumber: Int,
        program: String,
        shift: com.obsidianscout.integrations.AssignmentShift,
        zone: java.time.ZoneId,
        starts: Map<String, ExpectedStart>
    ): Int {
        val matchKeys = matchKeysInShift(shift, zone, starts)
        if (matchKeys.isEmpty() || shiftScouterIds(shift).isEmpty()) return 0
        val prog = program.uppercase().trim()
        val eventKey = shift.eventKey.trim().lowercase()
        val creator = shift.createdByUserId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val now = Instant.now()

        return transaction {
            // Seats whose scout has left the team stay open
            val requested = shiftScouterIds(shift).mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            val onTeam = Users.selectAll().where {
                (Users.id inList requested) and (Users.program eq prog) and (Users.teamNumber eq teamNumber)
            }.map { it[Users.id].value }.toSet()
            fun seat(ids: List<String>, i: Int): UUID? =
                ids.getOrNull(i)?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.takeIf { it in onTeam }

            val existing = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq prog) and (ScoutingAssignments.eventKey eq eventKey) and
                (ScoutingAssignments.ownerTeamNumber eq teamNumber)
            }.toList()

            data class NewRow(
                val scout: UUID, val matchKey: String, val matchNumber: Int?, val compLevel: String?,
                val type: String, val team: Int?, val alliance: String, val notes: String
            )
            val rows = mutableListOf<NewRow>()

            TeamCacheOverrideService.matchRowsByKeys(matchKeys, teamNumber, prog).forEach { m ->
                val mKey = m[ApiMatches.matchKey]
                val inMatch = existing.filter { it[ScoutingAssignments.matchKey]?.equals(mKey, ignoreCase = true) == true }
                val busy = inMatch.map { it[ScoutingAssignments.assignedUserId].value }.toMutableSet()
                val red = m[ApiMatches.redTeams].split(",").mapNotNull { parseTeamNum(it) }
                val blue = m[ApiMatches.blueTeams].split(",").mapNotNull { parseTeamNum(it) }
                val stations = red.map { it to "RED" } + blue.map { it to "BLUE" }

                fun claim(scout: UUID?, type: String, team: Int?, alliance: String, notes: String) {
                    if (scout == null || scout in busy) return
                    val taken = inMatch.any { a ->
                        a[ScoutingAssignments.assignmentType] == type &&
                            a[ScoutingAssignments.targetTeamNumber] == team &&
                            (team != null || a[ScoutingAssignments.allianceColor]?.uppercase() == alliance)
                    }
                    if (taken) return
                    busy.add(scout)
                    rows.add(NewRow(scout, mKey, m[ApiMatches.matchNumber], m[ApiMatches.compLevel], type, team, alliance, notes))
                }

                stations.forEachIndexed { i, (team, alliance) ->
                    claim(seat(shift.matchScouterIds, i), "MATCH", team, alliance, "Match Scouting - Team $team ($alliance)")
                }
                if (shift.qualMode == "ALLIANCE") {
                    listOf("RED", "BLUE").forEachIndexed { i, alliance ->
                        claim(seat(shift.qualScouterIds, i), "QUALITATIVE", null, alliance, "Qualitative scouting for $alliance alliance")
                    }
                } else {
                    stations.forEachIndexed { i, (team, alliance) ->
                        claim(seat(shift.qualScouterIds, i), "QUALITATIVE", team, alliance, "Qualitative scouting for Team #$team ($alliance)")
                    }
                }
            }

            if (rows.isNotEmpty()) {
                ScoutingAssignments.batchInsert(rows, shouldReturnGeneratedValues = false) { r ->
                    this[ScoutingAssignments.ownerTeamNumber] = teamNumber
                    this[ScoutingAssignments.program] = prog
                    this[ScoutingAssignments.eventKey] = eventKey
                    this[ScoutingAssignments.assignedUserId] = r.scout
                    this[ScoutingAssignments.assignmentType] = r.type
                    this[ScoutingAssignments.matchKey] = r.matchKey
                    this[ScoutingAssignments.matchNumber] = r.matchNumber
                    this[ScoutingAssignments.compLevel] = r.compLevel
                    this[ScoutingAssignments.targetTeamNumber] = r.team
                    this[ScoutingAssignments.allianceColor] = r.alliance
                    this[ScoutingAssignments.status] = "PENDING"
                    this[ScoutingAssignments.notes] = r.notes
                    this[ScoutingAssignments.createdByUserId] = creator
                    this[ScoutingAssignments.createdAt] = now
                    this[ScoutingAssignments.updatedAt] = now
                }
            }
            rows.size
        }
    }

    /**
     * Keeps shifts following the real schedule. When the event runs ahead or behind, an upcoming match can drift
     * out of the shift it was given to and into a neighbouring one; its untouched (PENDING) shift assignments then
     * move to whoever holds the same seat in the shift the match will now be played in. Played matches, started
     * work, hand-made assignments, open seats and matches that now fall between shifts are left as they are.
     * Returns the number of assignments moved.
     */
    private fun rebalanceShifts(
        teamNumber: Int,
        program: String,
        eventKey: String,
        shifts: List<com.obsidianscout.integrations.AssignmentShift>,
        zone: java.time.ZoneId,
        starts: Map<String, ExpectedStart>
    ): Int {
        if (shifts.size < 2) return 0
        val target = starts.filterValues { !it.played }.mapNotNull { (key, start) ->
            shifts.firstOrNull { inShift(it, zone, start) }?.let { key.lowercase() to it }
        }.toMap()
        if (target.isEmpty()) return 0
        val prog = program.uppercase().trim()
        val now = Instant.now()

        return transaction {
            val requested = shifts.flatMap { shiftScouterIds(it) }.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.distinct()
            val onTeam = Users.selectAll().where {
                (Users.id inList requested) and (Users.program eq prog) and (Users.teamNumber eq teamNumber)
            }.map { it[Users.id].value }.toSet()

            val byMatch = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq prog) and (ScoutingAssignments.eventKey eq eventKey) and
                (ScoutingAssignments.ownerTeamNumber eq teamNumber)
            }.toList().groupBy { it[ScoutingAssignments.matchKey]?.lowercase() }

            // The seat list a slot belongs to: match stations, qualitative stations, or qualitative alliances
            fun seatsOf(shift: com.obsidianscout.integrations.AssignmentShift, type: String, allianceWide: Boolean): List<String>? = when {
                type == "MATCH" -> shift.matchScouterIds
                type != "QUALITATIVE" -> null
                allianceWide == (shift.qualMode == "ALLIANCE") -> shift.qualScouterIds
                else -> null
            }
            fun holder(shift: com.obsidianscout.integrations.AssignmentShift, type: String, allianceWide: Boolean, i: Int): String? =
                seatsOf(shift, type, allianceWide)?.getOrNull(i)?.trim()?.lowercase()?.ifBlank { null }

            var moved = 0
            TeamCacheOverrideService.matchRows(eventKey, teamNumber, prog).forEach { m ->
                val mKey = m[ApiMatches.matchKey].lowercase()
                val shift = target[mKey] ?: return@forEach
                val rows = byMatch[mKey] ?: return@forEach
                val red = m[ApiMatches.redTeams].split(",").mapNotNull { parseTeamNum(it) }
                val blue = m[ApiMatches.blueTeams].split(",").mapNotNull { parseTeamNum(it) }
                val stations = red.map { it to "RED" } + blue.map { it to "BLUE" }
                val assignee = rows.associate { it[ScoutingAssignments.id].value to it[ScoutingAssignments.assignedUserId].value }.toMutableMap()

                rows.filter { it[ScoutingAssignments.status] == "PENDING" }.forEach { row ->
                    val type = row[ScoutingAssignments.assignmentType]
                    val team = row[ScoutingAssignments.targetTeamNumber]
                    val alliance = row[ScoutingAssignments.allianceColor]?.uppercase() ?: return@forEach
                    val allianceWide = team == null
                    val seat = if (allianceWide) listOf("RED", "BLUE").indexOf(alliance) else stations.indexOf(team to alliance)
                    if (seat < 0) return@forEach

                    val id = row[ScoutingAssignments.id].value
                    val current = assignee.getValue(id)
                    val currentText = current.toString().lowercase()
                    if (holder(shift, type, allianceWide, seat) == currentText) return@forEach
                    // Only slots another shift handed out by seat; anything else was assigned by hand
                    if (shifts.none { it.id != shift.id && holder(it, type, allianceWide, seat) == currentText }) return@forEach
                    val next = holder(shift, type, allianceWide, seat)
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.takeIf { it in onTeam } ?: return@forEach
                    if (next in assignee.values) return@forEach

                    ScoutingAssignments.update({ ScoutingAssignments.id eq id }) {
                        it[assignedUserId] = next
                        it[updatedAt] = now
                        it[pushReminderSentAt] = null
                        it[emailReminderSentAt] = null
                    }
                    assignee[id] = next
                    moved++
                }
            }
            moved
        }
    }

    /** Applies the given shifts of one team and records what each created. Returns the total created. */
    private fun applyTeamShifts(teamNumber: Int, program: String, shiftIds: Set<String>): Int {
        val settings = com.obsidianscout.integrations.SettingsService.getSettings(teamNumber, program)
        val created = mutableMapOf<String, Int>()
        var moved = 0
        settings.assignmentShifts.filter { it.id in shiftIds }.groupBy { it.eventKey.lowercase() }.forEach { (eventKey, shifts) ->
            val zone = eventZone(eventKey, settings.timezone)
            val starts = expectedStarts(eventKey, program, teamNumber)
            // Every shift of the event, so a match can move to a neighbour that isn't being applied right now
            val eventShifts = settings.assignmentShifts.filter { it.eventKey.lowercase() == eventKey }
            moved += rebalanceShifts(teamNumber, program, eventKey, eventShifts, zone, starts)
            shifts.forEach { shift ->
                val count = applyShift(teamNumber, program, shift, zone, starts)
                if (count > 0) created[shift.id] = count
            }
        }
        if (moved > 0) {
            consoleLog.info("[ScoutingAssignmentService] Schedule drift moved $moved shift assignment(s) for team $teamNumber ($program)")
            if (created.isEmpty()) com.obsidianscout.widgets.WidgetPushService.notifyTeam(teamNumber, program, "assignments")
        }
        if (created.isEmpty()) return 0

        // Re-read so a shift edited while this ran isn't reverted
        val latest = com.obsidianscout.integrations.SettingsService.getSettings(teamNumber, program)
        val nowText = Instant.now().toString()
        com.obsidianscout.integrations.SettingsService.updateSettings(
            teamNumber,
            latest.copy(assignmentShifts = latest.assignmentShifts.map { s ->
                created[s.id]?.let { s.copy(appliedCount = s.appliedCount + it, lastAppliedAt = nowText) } ?: s
            })
        )
        com.obsidianscout.widgets.WidgetPushService.notifyTeam(teamNumber, program, "assignments")
        return created.values.sum()
    }

    /**
     * Called after a schedule sync: fills the planned shifts for [eventKeys] on every team that has some.
     * Shifts whose day has passed are skipped, since no new matches will land in them.
     */
    fun applyShiftsAfterSync(program: String, eventKeys: Collection<String>) {
        val keys = eventKeys.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        if (keys.isEmpty()) return
        try {
            val prog = program.uppercase().trim()
            val cutoff = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1)
            val teams = readTransaction {
                com.obsidianscout.db.AppSettings.selectAll().where {
                    (com.obsidianscout.db.AppSettings.program eq prog) and
                    (com.obsidianscout.db.AppSettings.settingsJson like "%\"qualScouterIds\"%")
                }.map { it[com.obsidianscout.db.AppSettings.teamNumber] }
            }
            teams.forEach { teamNumber ->
                val shiftIds = com.obsidianscout.integrations.SettingsService.getSettings(teamNumber, prog).assignmentShifts
                    .filter { it.eventKey.lowercase() in keys }
                    .filter { runCatching { !java.time.LocalDate.parse(it.day).isBefore(cutoff) }.getOrDefault(false) }
                    .map { it.id }.toSet()
                if (shiftIds.isNotEmpty()) {
                    val created = applyTeamShifts(teamNumber, prog, shiftIds)
                    if (created > 0) consoleLog.info("[ScoutingAssignmentService] Shifts created $created assignment(s) for team $teamNumber ($prog)")
                }
            }
        } catch (e: Exception) {
            consoleLog.warn("[ScoutingAssignmentService] applyShiftsAfterSync warning: ${e.message}")
        }
    }

    fun listShifts(session: UserSession, eventKey: String, createdCount: Int = 0): AssignmentShiftsResponse {
        val key = eventKey.trim().lowercase()
        val settings = com.obsidianscout.integrations.SettingsService.getSettings(session.teamNumber, session.program)
        val shifts = settings.assignmentShifts.filter { it.eventKey.lowercase() == key }
            .sortedWith(compareBy({ it.day }, { shiftMinutes(it.startTime) ?: 0 }))
        if (shifts.isEmpty()) return AssignmentShiftsResponse(createdCount = createdCount)

        val zone = eventZone(key, settings.timezone)
        val starts = expectedStarts(key, session.program, session.teamNumber)
        val userIds = shifts.flatMap { shiftScouterIds(it) }.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.distinct()
        val names = if (userIds.isEmpty()) emptyMap() else readTransaction {
            Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value.toString() to it[Users.username] }
        }
        return AssignmentShiftsResponse(
            shifts = shifts.map { s ->
                AssignmentShiftView(
                    shift = s,
                    matchCount = matchKeysInShift(s, zone, starts).size,
                    scouterNames = shiftScouterIds(s).mapNotNull { id -> names[id]?.let { id to it } }.toMap()
                )
            },
            createdCount = createdCount
        )
    }

    /** Creates or updates a shift, then applies it right away if its matches are already synced. */
    fun saveShift(session: UserSession, request: SaveAssignmentShiftRequest): AssignmentShiftsResponse {
        val eventKey = request.eventKey.trim().lowercase()
        if (eventKey.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Event key is required.")
        val day = runCatching { java.time.LocalDate.parse(request.day.trim()) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Pick the day of the shift.")
        val startMin = shiftMinutes(request.startTime) ?: throw ApiException(HttpStatusCode.BadRequest, "Shift start time is invalid.")
        val endMin = shiftMinutes(request.endTime) ?: throw ApiException(HttpStatusCode.BadRequest, "Shift end time is invalid.")
        if (endMin <= startMin) throw ApiException(HttpStatusCode.BadRequest, "Shift end must be after shift start.")
        val qualMode = if (request.qualMode.trim().uppercase() == "ALLIANCE") "ALLIANCE" else "TEAMS"
        val qualSeats = if (qualMode == "ALLIANCE") 2 else SHIFT_MAX_SEATS

        // Seats are positional; trailing open seats are dropped
        fun seats(ids: List<String>, max: Int, label: String): List<String> {
            val cleaned = ids.map { it.trim() }.dropLastWhile { it.isEmpty() }
            if (cleaned.size > max) throw ApiException(HttpStatusCode.BadRequest, "$label takes at most $max scouts.")
            return cleaned
        }
        val matchSeats = seats(request.matchScouterIds, SHIFT_MAX_SEATS, "Match scouting")
        val qualSeatIds = seats(request.qualScouterIds, qualSeats, "Qualitative scouting")

        val picked = (matchSeats + qualSeatIds).filter { it.isNotEmpty() }
        if (picked.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "Pick at least one scout for the shift.")
        if (picked.size != picked.distinct().size) {
            throw ApiException(HttpStatusCode.BadRequest, "Each scout can only have one seat in a shift.")
        }

        val prog = session.program.uppercase().trim()
        val requestedIds = picked.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        val validIds = if (requestedIds.isEmpty()) emptySet() else readTransaction {
            Users.selectAll().where {
                (Users.id inList requestedIds) and (Users.program eq prog) and
                (if (session.role != UserRole.SUPERADMIN) Users.teamNumber eq session.teamNumber else Users.teamNumber eq Users.teamNumber)
            }.map { it[Users.id].value.toString() }.toSet()
        }
        if (picked.any { it.lowercase() !in validIds }) {
            throw ApiException(HttpStatusCode.BadRequest, "Every scout on the shift must be on your team.")
        }

        val current = com.obsidianscout.integrations.SettingsService.getSettings(session.teamNumber, session.program)
        val existing = request.id?.trim()?.ifBlank { null }?.let { id -> current.assignmentShifts.firstOrNull { it.id == id } }
        val fmt = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }
        val shift = com.obsidianscout.integrations.AssignmentShift(
            id = existing?.id ?: UUID.randomUUID().toString(),
            eventKey = eventKey,
            name = request.name.trim().take(64),
            day = day.toString(),
            startTime = fmt(startMin),
            endTime = fmt(endMin),
            stageFilter = request.stageFilter.trim().lowercase().ifBlank { "all" },
            matchScouterIds = matchSeats.map { it.lowercase() },
            qualMode = qualMode,
            qualScouterIds = qualSeatIds.map { it.lowercase() },
            createdByUserId = existing?.createdByUserId ?: session.userId,
            lastAppliedAt = existing?.lastAppliedAt,
            appliedCount = existing?.appliedCount ?: 0
        )
        val updatedShifts = if (existing != null) current.assignmentShifts.map { if (it.id == shift.id) shift else it }
            else current.assignmentShifts + shift
        com.obsidianscout.integrations.SettingsService.updateSettings(session.teamNumber, current.copy(assignmentShifts = updatedShifts))

        val created = applyTeamShifts(session.teamNumber, session.program, setOf(shift.id))
        return listShifts(session, eventKey, created)
    }

    /** Removes a shift. Assignments it already created stay; delete those from the list if unwanted. */
    fun deleteShift(session: UserSession, id: String): Boolean {
        val current = com.obsidianscout.integrations.SettingsService.getSettings(session.teamNumber, session.program)
        if (current.assignmentShifts.none { it.id == id }) return false
        com.obsidianscout.integrations.SettingsService.updateSettings(
            session.teamNumber,
            current.copy(assignmentShifts = current.assignmentShifts.filter { it.id != id })
        )
        return true
    }

    /**
     * Pit rows tagged with the same scout group are one unit (the group visits the team together),
     * so they never count as duplicates of each other. Ungrouped rows are each their own unit.
     */
    private fun pitUnitKey(row: org.jetbrains.exposed.v1.core.ResultRow): String =
        row.getOrNull(ScoutingAssignments.scoutGroup)?.lowercase()?.let { "group:$it" }
            ?: "row:${row[ScoutingAssignments.id].value}"

    private fun pitGroupRosterKey(row: org.jetbrains.exposed.v1.core.ResultRow): String =
        "${row[ScoutingAssignments.ownerTeamNumber]}|${row[ScoutingAssignments.eventKey].lowercase()}|" +
            "${row[ScoutingAssignments.targetTeamNumber]}|${row.getOrNull(ScoutingAssignments.scoutGroup)?.lowercase()}"

    private fun compLevelRank(compLevel: String?): Int {
        if (compLevel.isNullOrBlank()) return 1
        return when (compLevel.trim().lowercase()) {
            "pr", "practice", "prac", "prc", "pm", "p" -> 0
            "qm", "qual", "quals", "qualification", "q" -> 1
            "qf", "quarterfinal", "quarterfinals" -> 2
            "sf", "semifinal", "semifinals" -> 3
            "f", "final", "finals" -> 4
            "ef", "einstein" -> 5
            "playoff", "playoffs" -> 6
            else -> 7
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

        val prog = session.program.uppercase().trim()
        val now = Instant.now()
        val creatorUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()

        return transaction {
            // Validate scouters belong to team/program
            val userRows = Users.selectAll().where {
                (Users.id inList rawUserIds) and
                (Users.program eq prog) and
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
                (ScoutingAssignments.program eq prog) and
                (ScoutingAssignments.eventKey eq eventKeyClean)
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
                val notes: String? = null,
                val scoutGroup: String? = null
            )
            val itemsToInsert = mutableListOf<BulkItem>()

            if (typeUpper == "PIT_AUTO" || typeUpper == "PIT") {
                val allTeams = TeamCacheOverrideService.teamRows(eventKeyClean, session.teamNumber, session.program)

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

                // Each unit (a scout group, or a lone scouter not in any selected group) takes the next team in turn
                data class PitUnit(val groupName: String?, val members: List<UUID>)
                val poolSet = scouterPool.toSet()
                val groupUnits = request.pitGroups.mapNotNull { g ->
                    val name = cleanScoutGroup(g.name) ?: return@mapNotNull null
                    val members = g.memberIds.mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
                        .distinct().filter { it in poolSet }
                    if (members.isEmpty()) null else PitUnit(name, members)
                }
                val groupedIds = groupUnits.flatMap { it.members }.toSet()
                val pitUnits = groupUnits + scouterPool.filter { it !in groupedIds }.map { PitUnit(null, listOf(it)) }

                targetTeams.forEachIndexed { idx, tRow ->
                    val tNum = tRow[ApiTeams.teamNumber]
                    val unit = pitUnits[idx % pitUnits.size]
                    val nickname = tRow[ApiTeams.nickname] ?: tRow[ApiTeams.name]
                    val baseNote = if (!nickname.isNullOrBlank()) "Pit Scouting - $nickname" else "Pit Scouting"
                    unit.members.forEach { scouterId ->
                        itemsToInsert.add(
                            BulkItem(
                                targetUuid = scouterId,
                                effectiveOwnerTeam = userOwnerMap[scouterId] ?: session.teamNumber,
                                typeClean = "PIT",
                                targetTeamNumber = tNum,
                                notes = if (unit.groupName != null) "$baseNote (Group: ${unit.groupName})" else baseNote,
                                scoutGroup = unit.groupName
                            )
                        )
                    }
                }
            } else {
                // Match or Qualitative Assignment
                val rawMatches = TeamCacheOverrideService.matchRows(eventKeyClean, session.teamNumber, session.program)
                if (rawMatches.isEmpty()) {
                    throw ApiException(HttpStatusCode.BadRequest, "No matches found for event $eventKeyClean.")
                }

                // Stage filter
                val stage = request.stageFilter?.trim()?.lowercase() ?: "all"
                val filteredMatches = rawMatches.filter { mRow -> matchesStage(mRow[ApiMatches.compLevel], stage) }.sortedWith(
                    compareBy(
                        { compLevelRank(it[ApiMatches.compLevel]) },
                        { it[ApiMatches.setNumber] ?: 1 },
                        { it[ApiMatches.matchNumber] ?: 0 }
                    )
                )

                if (filteredMatches.isEmpty()) {
                    throw ApiException(HttpStatusCode.BadRequest, "No matches found matching tournament stage ($stage).")
                }

                val explicitKeys = request.matchKeys.orEmpty().map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

                val targetMatches = if (explicitKeys.isNotEmpty()) {
                    // Time-block mode: the client resolved which matches fall inside the block
                    filteredMatches.filter { it[ApiMatches.matchKey].lowercase() in explicitKeys }.ifEmpty {
                        throw ApiException(HttpStatusCode.BadRequest, "No matches found in the selected time block.")
                    }
                } else {
                    val startIdx = if (!request.startMatchKey.isNullOrBlank()) {
                        filteredMatches.indexOfFirst { it[ApiMatches.matchKey].equals(request.startMatchKey.trim(), ignoreCase = true) }.takeIf { it >= 0 } ?: 0
                    } else 0

                    val endIdx = if (!request.endMatchKey.isNullOrBlank()) {
                        filteredMatches.indexOfFirst { it[ApiMatches.matchKey].equals(request.endMatchKey.trim(), ignoreCase = true) }.takeIf { it >= 0 } ?: (filteredMatches.size - 1)
                    } else (filteredMatches.size - 1)

                    if (startIdx > endIdx) {
                        throw ApiException(HttpStatusCode.BadRequest, "Start match must come before or equal to End match.")
                    }

                    filteredMatches.subList(startIdx, endIdx + 1)
                }
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
                    this[ScoutingAssignments.scoutGroup] = item.scoutGroup
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

        val prog = session.program.uppercase().trim()
        val now = Instant.now()
        transaction {
            val query = ScoutingAssignments.selectAll().where { (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program eq prog) }
            if (session.role != UserRole.SUPERADMIN && session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            val existing = query.firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "Assignment not found.")

            val newTargetUser = if (!request.assignedUserId.isNullOrBlank()) {
                val userUuid = runCatching { UUID.fromString(request.assignedUserId) }.getOrNull()
                    ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID.")
                val userExists = Users.selectAll().where { (Users.id eq userUuid) and (Users.program eq prog) }.count() > 0
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

        val prog = session.program.uppercase().trim()
        val now = Instant.now()
        transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program eq prog)
            }
            if (session.role != UserRole.SUPERADMIN) {
                query.andWhere {
                    (ScoutingAssignments.ownerTeamNumber eq session.teamNumber) and
                    (if (session.role == UserRole.SCOUT || session.role == UserRole.ANALYTICS || session.role == UserRole.CUSTOM) ScoutingAssignments.assignedUserId eq userUuid else ScoutingAssignments.ownerTeamNumber eq session.teamNumber)
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
            existing[ScoutingAssignments.assignedUserId].value.toString()
        }.let { assignedTo ->
            // The assigned scout's My Assignments widget moves on (or back) right away.
            com.obsidianscout.widgets.WidgetPushService.notifyUsers(listOf(assignedTo), "assignments")
        }

        return getAssignmentById(session, id)
            ?: throw ApiException(HttpStatusCode.InternalServerError, "Failed to retrieve updated assignment.")
    }

    fun deleteAssignment(session: UserSession, id: String): Boolean {
        val assignmentUuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid assignment ID.")

        val prog = session.program.uppercase().trim()
        return transaction {
            val query = (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program eq prog)
            val fullQuery = if (session.role != UserRole.SUPERADMIN && session.teamNumber != 0) {
                query and (ScoutingAssignments.ownerTeamNumber eq session.teamNumber)
            } else query

            ScoutingAssignments.deleteWhere { fullQuery } > 0
        }
    }

    fun getAssignmentById(session: UserSession, id: String): ScoutingAssignmentRecord? {
        val assignmentUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        val prog = session.program.uppercase().trim()
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.id eq assignmentUuid) and (ScoutingAssignments.program eq prog)
            }
            if (session.role != UserRole.SUPERADMIN && session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            val row = query.firstOrNull() ?: return@readTransaction null
            mapRowToRecord(row)
        }
    }

    private val lastCompletionSync = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Safety net before listing: marks open assignments whose data is already on the server
     * (entries that arrived via a path that skipped auto-completion). Throttled per event.
     */
    private fun reconcileCompleted(program: String, eventKeys: Collection<String>) {
        val nowMs = System.currentTimeMillis()
        for (key in eventKeys.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()) {
            val cacheKey = "$program|$key"
            val last = lastCompletionSync[cacheKey]
            if (last != null && nowMs - last < 10_000) continue
            lastCompletionSync[cacheKey] = nowMs
            syncCompletedAssignments(program, key)
        }
    }

    fun listAssignments(
        session: UserSession,
        eventKey: String? = null,
        assignmentType: String? = null,
        userId: String? = null,
        status: String? = null
    ): List<ScoutingAssignmentRecord> {
        val prog = session.program.uppercase().trim()
        if (!eventKey.isNullOrBlank()) reconcileCompleted(prog, listOf(eventKey))
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where { ScoutingAssignments.program eq prog }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.eventKey eq eventKey.trim().lowercase() }
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

    /**
     * The event an assignment listing is for: the one the caller asked for, else the team's active event.
     * Null when neither exists, in which case the routes return nothing rather than every event's assignments.
     */
    fun scopedEventKey(session: UserSession, requested: String?): String? {
        if (!requested.isNullOrBlank()) return requested.trim().lowercase()
        return AllianceService.getEffectiveSettings(session.teamNumber, session.program)
            .resolvedEventKey().trim().lowercase().ifBlank { null }
    }

    fun getMyAssignments(session: UserSession, eventKey: String? = null): List<ScoutingAssignmentRecord> {
        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull() ?: return emptyList()
        val prog = session.program.uppercase().trim()
        val openEvents = if (!eventKey.isNullOrBlank()) listOf(eventKey) else readTransaction {
            ScoutingAssignments.select(ScoutingAssignments.eventKey).where {
                (ScoutingAssignments.assignedUserId eq userUuid) and
                (ScoutingAssignments.program eq prog) and
                ((ScoutingAssignments.status eq "PENDING") or (ScoutingAssignments.status eq "IN_PROGRESS"))
            }.withDistinct().map { it[ScoutingAssignments.eventKey] }
        }
        reconcileCompleted(prog, openEvents)
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.assignedUserId eq userUuid) and
                (ScoutingAssignments.program eq prog)
            }
            if (session.role != UserRole.SUPERADMIN || session.teamNumber != 0) {
                query.andWhere { ScoutingAssignments.ownerTeamNumber eq session.teamNumber }
            }
            if (!eventKey.isNullOrBlank()) {
                query.andWhere { ScoutingAssignments.eventKey eq eventKey.trim().lowercase() }
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
        val prog = session.program.uppercase().trim()
        val eKey = eventKey.trim().lowercase()
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq prog) and
                (ScoutingAssignments.eventKey eq eKey) and
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
                val isSingleUnitPit = slotRows.first()[ScoutingAssignments.assignmentType] == "PIT" &&
                    slotRows.map { pitUnitKey(it) }.distinct().size == 1
                if (slotRows.size > 1 && !isSingleUnitPit) {
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
        val prog = session.program.uppercase().trim()
        val eKey = eventKey.trim().lowercase()
        return readTransaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq prog) and
                (ScoutingAssignments.eventKey eq eKey)
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
        val eventKeyClean = eventKey.trim().lowercase()
        if (eventKeyClean.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Event key is required.")
        }
        val prog = session.program.uppercase().trim()
        return transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq prog) and
                (ScoutingAssignments.eventKey eq eventKeyClean)
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
        val prog = session.program.uppercase().trim()

        return transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq prog) and
                (ScoutingAssignments.eventKey eq eventKeyClean) and
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
            val slotMap = mutableMapOf<String, MutableList<org.jetbrains.exposed.v1.core.ResultRow>>()
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
                    val sorted = slotRows.sortedWith(compareByDescending<org.jetbrains.exposed.v1.core.ResultRow> {
                        when (it[ScoutingAssignments.status].uppercase()) {
                            "COMPLETED" -> 3
                            "IN_PROGRESS" -> 2
                            else -> 1
                        }
                    }.thenByDescending { !it[ScoutingAssignments.notes].isNullOrBlank() }
                     .thenBy { it[ScoutingAssignments.createdAt] })

                    // For pit slots the whole group of the kept row stays; only other units are duplicates
                    val keptUnit = pitUnitKey(sorted.first())
                    val duplicates = if (sorted.first()[ScoutingAssignments.assignmentType].uppercase() == "PIT") {
                        sorted.filter { pitUnitKey(it) != keptUnit }
                    } else sorted.drop(1)
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
                    val sorted = uMatchRows.sortedWith(compareByDescending<org.jetbrains.exposed.v1.core.ResultRow> {
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
        val eventClean = eventKey.trim().lowercase()
        val now = Instant.now()

        try {
            transaction {
                val pendingAsgns = ScoutingAssignments.selectAll().where {
                    (ScoutingAssignments.program eq program) and
                    (ScoutingAssignments.eventKey.lowerCase() eq eventClean) and
                    ((ScoutingAssignments.status eq "PENDING") or (ScoutingAssignments.status eq "IN_PROGRESS"))
                }.toList()

                if (pendingAsgns.isEmpty()) return@transaction

                // Load all existing match scouting data keys for this event
                val matchEntries = ScoutingEntries.selectAll().where {
                    (ScoutingEntries.program eq program) and
                    (ScoutingEntries.eventKey.lowerCase() eq eventClean)
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
                    (PitScoutingEntries.eventKey.lowerCase() eq eventClean)
                }.toList()
                val pitTeams = pitEntries.mapNotNull { it[PitScoutingEntries.targetTeamNumber] }.toSet()

                // Load all existing qualitative scouting data for this event
                val qualEntries = QualitativeScoutingEntries.selectAll().where {
                    (QualitativeScoutingEntries.program eq program) and
                    (QualitativeScoutingEntries.eventKey.lowerCase() eq eventClean)
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
                val matchesForEvent = ApiMatches.selectAll().where { ApiMatches.eventKey.lowerCase() eq eventClean }.associate { row ->
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
                    consoleLog.info("[ScoutingAssignmentService] Auto-synced and marked completed ${idsToComplete.size} assignment(s) for event $eventClean")
                    val completedFor = pendingAsgns.filter { it[ScoutingAssignments.id].value in idsToComplete }
                        .map { it[ScoutingAssignments.assignedUserId].value.toString() }.distinct()
                    com.obsidianscout.widgets.WidgetPushService.notifyUsers(completedFor, "assignments")
                }
            }
        } catch (e: Exception) {
            consoleLog.warn("[ScoutingAssignmentService] syncCompletedAssignments warning: ${e.message}")
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

        val completedFor = transaction {
            val query = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.program eq program) and
                (ScoutingAssignments.eventKey.lowerCase() eq eventKey.trim().lowercase()) and
                (ScoutingAssignments.assignmentType eq typeClean) and
                ((ScoutingAssignments.status eq "PENDING") or (ScoutingAssignments.status eq "IN_PROGRESS"))
            }

            if (typeClean == "MATCH") {
                if (!matchKey.isNullOrBlank()) {
                    query.andWhere { ScoutingAssignments.matchKey.lowerCase() eq matchKey.trim().lowercase() }
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
                    query.andWhere { ScoutingAssignments.matchKey.lowerCase() eq matchKey.trim().lowercase() }
                } else if (matchNumber != null) {
                    query.andWhere { ScoutingAssignments.matchNumber eq matchNumber }
                }

                var teamAlliance: String? = allianceColor?.trim()?.uppercase()
                if (teamAlliance == null && !matchKey.isNullOrBlank() && targetTeamNumber != null) {
                    val matchRow = TeamCacheOverrideService.matchRow(matchKey.trim(), ownerTeam, program)
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

            val matching = query.toList()
            val matchingIds = matching.map { it[ScoutingAssignments.id].value }
            if (matchingIds.isNotEmpty()) {
                ScoutingAssignments.update({ ScoutingAssignments.id inList matchingIds }) {
                    it[status] = "COMPLETED"
                    it[completedAt] = now
                    it[updatedAt] = now
                }
                consoleLog.info("[ScoutingAssignmentService] Auto-completed ${matchingIds.size} assignment(s) for event $eventKey ($typeClean)")
            }
            matching.map { it[ScoutingAssignments.assignedUserId].value.toString() }.distinct()
        }
        // Their My Assignments widgets move on to the next one.
        com.obsidianscout.widgets.WidgetPushService.notifyUsers(completedFor, "assignments")
    }

    private fun mapRowToRecord(row: org.jetbrains.exposed.v1.core.ResultRow): ScoutingAssignmentRecord {
        val userIds = listOfNotNull(row[ScoutingAssignments.assignedUserId].value, row[ScoutingAssignments.createdByUserId]?.value)
        val userMap = Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value to it[Users.username] }

        val assignedUId = row[ScoutingAssignments.assignedUserId].value
        val creatorUId = row[ScoutingAssignments.createdByUserId]?.value

        val mKey = row[ScoutingAssignments.matchKey]?.trim()
        val eKey = row[ScoutingAssignments.eventKey].trim().lowercase()

        val matchTiming = if (!mKey.isNullOrBlank()) {
            TeamCacheOverrideService.matchRow(mKey, row[ScoutingAssignments.ownerTeamNumber], row[ScoutingAssignments.program])?.let {
                val sched = it[ApiMatches.scheduledTime]
                val act = it[ApiMatches.actualTime]
                val offset = if (act != null && sched != null && act > 0 && act != sched) (act - sched) else 0L
                val pred = if (sched != null && offset != 0L) (sched + offset) else sched
                Triple(sched, pred, offset)
            }
        } else null

        val matchScheduledTime = matchTiming?.first
        val matchPredictedTime = matchTiming?.second ?: matchScheduledTime
        val matchOffset = matchTiming?.third ?: 0L

        val tNum = row[ScoutingAssignments.targetTeamNumber]
        val targetTeamNickname = if (tNum != null && eKey.isNotBlank()) {
            TeamCacheOverrideService.teamRows(eKey, row[ScoutingAssignments.ownerTeamNumber], row[ScoutingAssignments.program]).firstOrNull { it[ApiTeams.teamNumber] == tNum }?.let {
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
            emailReminderSentAt = runCatching { row.getOrNull(ScoutingAssignments.emailReminderSentAt) }.getOrNull()?.toString(),
            scoutGroup = runCatching { row.getOrNull(ScoutingAssignments.scoutGroup) }.getOrNull()
        )
    }

    private data class MatchTiming(
        val scheduledTime: Long?,
        val predictedTime: Long?,
        val offsetSeconds: Long,
        val compLevel: String? = null,
        val setNumber: Int? = null,
        val matchNumber: Int? = null
    )

    private val legacyKeyRegex = Regex("""^.+_([a-z]+)_s(\d+)_m(\d+)$""", RegexOption.IGNORE_CASE)
    private val tbaSuffixRegex = Regex("""^([a-z]+?)(\d+)(?:m(\d+))?$""", RegexOption.IGNORE_CASE)

    private fun extractOrderTuple(record: ScoutingAssignmentRecord, matchInfo: MatchTiming?): Triple<Int, Int, Int> {
        if (record.assignmentType.equals("PIT", ignoreCase = true)) {
            return Triple(10, 0, record.targetTeamNumber ?: Int.MAX_VALUE)
        }

        val rawKey = record.matchKey?.trim()?.lowercase() ?: ""
        var keyLevel: String? = null
        var keySet: Int? = null
        var keyMatch: Int? = null

        if (rawKey.isNotEmpty()) {
            val legacy = legacyKeyRegex.matchEntire(rawKey)
            if (legacy != null) {
                keyLevel = legacy.groupValues[1].lowercase()
                keySet = legacy.groupValues[2].toIntOrNull()
                keyMatch = legacy.groupValues[3].toIntOrNull()
            } else {
                val suffix = rawKey.substringAfterLast('_')
                val tba = tbaSuffixRegex.matchEntire(suffix)
                if (tba != null) {
                    keyLevel = tba.groupValues[1].lowercase()
                    val g2 = tba.groupValues[2].toIntOrNull()
                    val g3 = tba.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
                    if (g3 != null) {
                        keySet = g2 ?: 1
                        keyMatch = g3
                    } else {
                        keySet = 1
                        keyMatch = g2
                    }
                }
            }
        }

        val explicitLevel = record.compLevel?.takeIf { it.isNotBlank() } ?: matchInfo?.compLevel
        var resolvedLevel = explicitLevel?.trim()?.lowercase() ?: ""
        val parsedKeyLevel = keyLevel?.takeIf { it != "m" } ?: ""

        if (parsedKeyLevel.isNotEmpty() &&
            (resolvedLevel.isEmpty() ||
                ((resolvedLevel in setOf("qm", "qual", "quals", "qualification", "playoff", "playoffs")) &&
                    parsedKeyLevel !in setOf("qm", "qual", "quals", "qualification")))
        ) {
            resolvedLevel = parsedKeyLevel
        }

        val setNum = matchInfo?.setNumber?.takeIf { it > 0 } ?: keySet ?: 1
        if (resolvedLevel == "playoff" || resolvedLevel == "playoffs") {
            resolvedLevel = when (setNum) {
                1 -> "qf"
                2 -> "sf"
                3 -> "f"
                else -> "playoff"
            }
        }

        val stageRank = compLevelRank(resolvedLevel.ifEmpty { "qm" })
        val matchNum = record.matchNumber?.takeIf { it > 0 } ?: keyMatch ?: matchInfo?.matchNumber ?: 0
        return Triple(stageRank, setNum, matchNum)
    }

    private fun mapRowsToRecords(rows: List<org.jetbrains.exposed.v1.core.ResultRow>): List<ScoutingAssignmentRecord> {
        if (rows.isEmpty()) return emptyList()

        val userIds = rows.flatMap {
            val assigned = runCatching { it[ScoutingAssignments.assignedUserId].value }.getOrNull()
            val creator = runCatching { it[ScoutingAssignments.createdByUserId]?.value }.getOrNull()
            listOfNotNull(assigned, creator)
        }.distinct()
        val userMap = if (userIds.isNotEmpty()) {
            Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value to it[Users.username] }
        } else emptyMap()

        val matchKeys = rows.mapNotNull { runCatching { it[ScoutingAssignments.matchKey] }.getOrNull() }
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        // Timing and nicknames follow each owning team's own view of the event.
        val owners = rows.mapNotNull { row ->
            runCatching { row[ScoutingAssignments.ownerTeamNumber] to row[ScoutingAssignments.program] }.getOrNull()
        }.distinct()

        val matchTimingMap: Map<String, MatchTiming> = if (matchKeys.isNotEmpty()) {
            owners.flatMap { (owner, program) -> TeamCacheOverrideService.matchRowsByKeys(matchKeys, owner, program) }.associate { row ->
                val mKey = row[ApiMatches.matchKey].trim().lowercase()
                val sched = row[ApiMatches.scheduledTime]
                val act = row[ApiMatches.actualTime]
                val offset = if (act != null && sched != null && act > 0 && act != sched) (act - sched) else 0L
                val pred = if (sched != null && offset != 0L) (sched + offset) else sched
                mKey to MatchTiming(
                    scheduledTime = sched,
                    predictedTime = pred,
                    offsetSeconds = offset,
                    compLevel = row[ApiMatches.compLevel],
                    setNumber = row[ApiMatches.setNumber],
                    matchNumber = row[ApiMatches.matchNumber]
                )
            }
        } else emptyMap()

        val eventTeamPairs = rows.mapNotNull {
            val t = runCatching { it[ScoutingAssignments.targetTeamNumber] }.getOrNull()
            val e = runCatching { it[ScoutingAssignments.eventKey] }.getOrNull()?.trim()?.lowercase()
            if (t != null && !e.isNullOrBlank()) Pair(e, t) else null
        }.distinct()

        val teamNicknames = if (eventTeamPairs.isNotEmpty()) {
            val events = eventTeamPairs.map { it.first }.distinct()
            val teams = eventTeamPairs.map { it.second }.distinct()
            val shared = ApiTeams.selectAll().where { (ApiTeams.eventKey inList events) and (ApiTeams.teamNumber inList teams) }.toList()
            owners.flatMap { (owner, program) -> TeamCacheOverrideService.patchTeamRows(shared, owner, program) }
                .associate { Pair(it[ApiTeams.eventKey].trim().lowercase(), it[ApiTeams.teamNumber]) to (it[ApiTeams.nickname] ?: it[ApiTeams.name]) }
        } else emptyMap()

        // Pit group rosters, so a scout's own list can show who they walk the pits with
        val groupNames = rows.mapNotNull { runCatching { it.getOrNull(ScoutingAssignments.scoutGroup) }.getOrNull() }.distinct()
        val groupRosters: Map<String, List<String>> = if (groupNames.isNotEmpty()) {
            val groupRows = ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.assignmentType eq "PIT") and (ScoutingAssignments.scoutGroup inList groupNames)
            }.toList()
            val memberIds = groupRows.map { it[ScoutingAssignments.assignedUserId].value }.distinct()
            val memberNames = Users.selectAll().where { Users.id inList memberIds }.associate { it[Users.id].value to it[Users.username] }
            groupRows.groupBy { pitGroupRosterKey(it) }.mapValues { (_, members) ->
                members.mapNotNull { memberNames[it[ScoutingAssignments.assignedUserId].value] }.distinct().sorted()
            }
        } else emptyMap()

        return rows.map { row ->
            val assignedUId = runCatching { row[ScoutingAssignments.assignedUserId].value }.getOrNull()
            val creatorUId = runCatching { row[ScoutingAssignments.createdByUserId]?.value }.getOrNull()
            val mKey = runCatching { row[ScoutingAssignments.matchKey] }?.getOrNull()?.trim()
            val tNum = runCatching { row[ScoutingAssignments.targetTeamNumber] }.getOrNull()
            val eKey = runCatching { row[ScoutingAssignments.eventKey] }.getOrNull()?.trim() ?: ""

            val timing = mKey?.lowercase()?.let { matchTimingMap[it] }
            val sTime = timing?.scheduledTime
            val pTime = timing?.predictedTime ?: sTime
            val offset = timing?.offsetSeconds ?: 0L
            val targetTeamNickname = if (tNum != null && eKey.isNotBlank()) teamNicknames[Pair(eKey.lowercase(), tNum)] else null
            val rowCompLevel = runCatching { row[ScoutingAssignments.compLevel] }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: timing?.compLevel

            ScoutingAssignmentRecord(
                id = runCatching { row[ScoutingAssignments.id].value.toString() }.getOrNull() ?: "",
                ownerTeamNumber = runCatching { row[ScoutingAssignments.ownerTeamNumber] }.getOrNull() ?: 0,
                program = runCatching { row[ScoutingAssignments.program] }.getOrNull() ?: "FRC",
                eventKey = eKey,
                assignedUserId = assignedUId?.toString() ?: "",
                assignedUsername = assignedUId?.let { userMap[it] } ?: "Unknown",
                assignmentType = runCatching { row[ScoutingAssignments.assignmentType] }.getOrNull() ?: "MATCH",
                matchKey = mKey,
                matchNumber = runCatching { row[ScoutingAssignments.matchNumber] }.getOrNull() ?: timing?.matchNumber,
                compLevel = rowCompLevel,
                targetTeamNumber = tNum,
                targetTeamName = targetTeamNickname,
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
                emailReminderSentAt = runCatching { row.getOrNull(ScoutingAssignments.emailReminderSentAt) }.getOrNull()?.toString(),
                scoutGroup = runCatching { row.getOrNull(ScoutingAssignments.scoutGroup) }.getOrNull(),
                scoutGroupMembers = runCatching { groupRosters[pitGroupRosterKey(row)] }.getOrNull().orEmpty()
            )
        }.sortedWith(
            compareBy<ScoutingAssignmentRecord>(
                { extractOrderTuple(it, it.matchKey?.lowercase()?.let { k -> matchTimingMap[k] }).first },
                { extractOrderTuple(it, it.matchKey?.lowercase()?.let { k -> matchTimingMap[k] }).second },
                { extractOrderTuple(it, it.matchKey?.lowercase()?.let { k -> matchTimingMap[k] }).third },
                { it.assignmentType },
                { it.targetTeamNumber ?: Int.MAX_VALUE },
                { it.allianceColor ?: "" },
                { it.id }
            )
        )
    }
}
