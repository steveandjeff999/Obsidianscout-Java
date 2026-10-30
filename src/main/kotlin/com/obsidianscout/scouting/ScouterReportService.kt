package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import com.obsidianscout.utils.CSVHelper
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Serializable
data class ScouterEventCount(
    val eventKey: String,
    val eventName: String? = null,
    val matchEntries: Int = 0,
    val pitEntries: Int = 0,
    val qualEntries: Int = 0
)

@Serializable
data class ScouterReportRow(
    val userId: String,
    val username: String,
    val role: String,
    val matchEntries: Int,
    /** Distinct matches this scouter submitted at least one match entry for. */
    val matchesScouted: Int,
    val pitEntries: Int,
    val qualEntries: Int,
    val totalEntries: Int,
    val eventsScouted: Int,
    val teamsScouted: Int,
    val avgCompletenessPct: Double? = null,
    val discrepancyEntries: Int,
    val assignmentsTotal: Int,
    val assignmentsCompleted: Int,
    val assignmentsSkipped: Int,
    val assignmentCompletionPct: Double? = null,
    val firstSubmission: String? = null,
    val lastSubmission: String? = null,
    val events: List<ScouterEventCount> = emptyList()
)

@Serializable
data class ScouterReportTotals(
    val activeScouters: Int,
    val matchEntries: Int,
    val pitEntries: Int,
    val qualEntries: Int,
    /** Distinct matches with at least one match entry from anyone on the team. */
    val matchesCovered: Int,
    val assignmentsTotal: Int,
    val assignmentsCompleted: Int
)

@Serializable
data class ScouterReport(
    /** `event`, `year` or `all`. */
    val scope: String,
    val eventKey: String? = null,
    val eventName: String? = null,
    val year: Int? = null,
    val teamNumber: Int,
    val program: String,
    val includePrescout: Boolean,
    val generatedAt: String,
    val totals: ScouterReportTotals,
    val scouters: List<ScouterReportRow>
)

@Serializable
data class ScouterReportEvent(val eventKey: String, val name: String? = null, val year: Int? = null)

@Serializable
data class ScouterReportOptions(val years: List<Int>, val events: List<ScouterReportEvent>)

/**
 * Per-scouter contribution report for admins: how many matches / pit / qualitative entries each
 * user on a team submitted for one event, one season, or all time, plus assignment follow-through.
 */
object ScouterReportService {

    enum class EntryKind { MATCH, PIT, QUAL }

    internal data class ReportEntry(
        val kind: EntryKind,
        val userId: String,
        val eventKey: String?,
        val matchKey: String?,
        val matchNumber: Int?,
        val targetTeam: Int?,
        val createdAt: Instant,
        val completenessPct: Float?,
        val hasDiscrepancy: Boolean,
        val isPrescout: Boolean
    )

    internal data class ReportAssignment(val userId: String, val eventKey: String, val status: String)

    internal data class ReportUser(val id: String, val username: String, val role: String)

    /** Season of an event: the leading year of its key, else the synced event's year, else when the entry was made. */
    internal fun resolveYear(eventKey: String?, eventYears: Map<String, Int>, fallback: Instant?): Int? {
        val key = eventKey?.trim()?.lowercase()
        if (!key.isNullOrEmpty()) {
            if (key.length >= 4 && key.take(4).all { it.isDigit() }) return key.take(4).toInt()
            eventYears[key]?.let { return it }
        }
        return fallback?.atZone(ZoneOffset.UTC)?.year
    }

    private fun matchIdentity(e: ReportEntry): String? =
        e.matchKey?.takeIf { it.isNotBlank() }?.lowercase()
            ?: e.matchNumber?.let { "${e.eventKey.orEmpty().lowercase()}#$it" }

    internal fun aggregate(
        scope: String,
        eventKey: String?,
        year: Int?,
        teamNumber: Int,
        program: String,
        includePrescout: Boolean,
        users: List<ReportUser>,
        entries: List<ReportEntry>,
        assignments: List<ReportAssignment>,
        eventYears: Map<String, Int>,
        eventNames: Map<String, String>,
        now: Instant = Instant.now()
    ): ScouterReport {
        val wantedEvent = eventKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        fun inScope(key: String?, createdAt: Instant?): Boolean = when {
            wantedEvent != null -> key?.trim()?.lowercase() == wantedEvent
            year != null -> resolveYear(key, eventYears, createdAt) == year
            else -> true
        }

        val scopedEntries = entries.filter { (includePrescout || !it.isPrescout) && inScope(it.eventKey, it.createdAt) }
        val scopedAssignments = assignments.filter { inScope(it.eventKey, null) }
        val entriesByUser = scopedEntries.groupBy { it.userId }
        val assignmentsByUser = scopedAssignments.groupBy { it.userId }

        val knownUsers = users.associateBy { it.id }
        val userIds = (users.map { it.id } + entriesByUser.keys + assignmentsByUser.keys).distinct()

        val rows = userIds.map { id ->
            val mine = entriesByUser[id].orEmpty()
            val myAssignments = assignmentsByUser[id].orEmpty()
            val match = mine.filter { it.kind == EntryKind.MATCH }
            val completeness = mine.mapNotNull { it.completenessPct?.toDouble() }
            val completed = myAssignments.count { it.status.equals("COMPLETED", ignoreCase = true) }
            val skipped = myAssignments.count { it.status.equals("SKIPPED", ignoreCase = true) }
            val events = mine.groupBy { it.eventKey?.trim()?.lowercase().orEmpty() }
                .map { (key, list) ->
                    ScouterEventCount(
                        eventKey = key,
                        eventName = eventNames[key],
                        matchEntries = list.count { it.kind == EntryKind.MATCH },
                        pitEntries = list.count { it.kind == EntryKind.PIT },
                        qualEntries = list.count { it.kind == EntryKind.QUAL }
                    )
                }
                .sortedBy { it.eventKey }
            val user = knownUsers[id]
            ScouterReportRow(
                userId = id,
                username = user?.username ?: "Unknown user",
                role = user?.role ?: "",
                matchEntries = match.size,
                matchesScouted = match.mapNotNull { matchIdentity(it) }.distinct().size,
                pitEntries = mine.count { it.kind == EntryKind.PIT },
                qualEntries = mine.count { it.kind == EntryKind.QUAL },
                totalEntries = mine.size,
                eventsScouted = events.count { it.eventKey.isNotEmpty() },
                teamsScouted = mine.mapNotNull { it.targetTeam }.distinct().size,
                avgCompletenessPct = if (completeness.isEmpty()) null else round1(completeness.average()),
                discrepancyEntries = mine.count { it.hasDiscrepancy },
                assignmentsTotal = myAssignments.size,
                assignmentsCompleted = completed,
                assignmentsSkipped = skipped,
                assignmentCompletionPct = if (myAssignments.isEmpty()) null else round1(completed * 100.0 / myAssignments.size),
                firstSubmission = mine.minOfOrNull { it.createdAt }?.toString(),
                lastSubmission = mine.maxOfOrNull { it.createdAt }?.toString(),
                events = events
            )
        }.sortedWith(compareByDescending<ScouterReportRow> { it.totalEntries }.thenBy { it.username.lowercase() })

        val totals = ScouterReportTotals(
            activeScouters = rows.count { it.totalEntries > 0 },
            matchEntries = rows.sumOf { it.matchEntries },
            pitEntries = rows.sumOf { it.pitEntries },
            qualEntries = rows.sumOf { it.qualEntries },
            matchesCovered = scopedEntries.filter { it.kind == EntryKind.MATCH }.mapNotNull { matchIdentity(it) }.distinct().size,
            assignmentsTotal = scopedAssignments.size,
            assignmentsCompleted = scopedAssignments.count { it.status.equals("COMPLETED", ignoreCase = true) }
        )

        return ScouterReport(
            scope = scope,
            eventKey = wantedEvent,
            eventName = wantedEvent?.let { eventNames[it] },
            year = if (wantedEvent == null) year else resolveYear(wantedEvent, eventYears, null),
            teamNumber = teamNumber,
            program = program,
            includePrescout = includePrescout,
            generatedAt = now.toString(),
            totals = totals,
            scouters = rows
        )
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    fun buildReport(teamNumber: Int, program: String, eventKey: String?, year: Int?, includePrescout: Boolean): ScouterReport {
        val scope = when {
            !eventKey.isNullOrBlank() -> "event"
            year != null -> "year"
            else -> "all"
        }
        return readTransaction {
            val users = Users.select(Users.id, Users.username, Users.role)
                .where { (Users.teamNumber eq teamNumber) and (Users.program eq program) }
                .map { ReportUser(it[Users.id].value.toString(), it[Users.username], it[Users.role]) }

            val entries = loadEntries(teamNumber, program)
            val assignments = ScoutingAssignments
                .select(ScoutingAssignments.assignedUserId, ScoutingAssignments.eventKey, ScoutingAssignments.status)
                .where { (ScoutingAssignments.ownerTeamNumber eq teamNumber) and (ScoutingAssignments.program eq program) }
                .map {
                    ReportAssignment(
                        it[ScoutingAssignments.assignedUserId].value.toString(),
                        it[ScoutingAssignments.eventKey],
                        it[ScoutingAssignments.status]
                    )
                }

            // Entries can come from users who have since left the team (or a QR scanner account): name them too.
            val knownIds = users.map { it.id }.toSet()
            val strayIds = (entries.map { it.userId } + assignments.map { it.userId })
                .filter { it !in knownIds }.distinct()
                .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            val strayUsers = if (strayIds.isEmpty()) emptyList() else Users.select(Users.id, Users.username, Users.role)
                .where { Users.id inList strayIds }
                .map { ReportUser(it[Users.id].value.toString(), it[Users.username], it[Users.role]) }

            val (eventYears, eventNames) = loadEventInfo(teamNumber, program)
            aggregate(
                scope, eventKey, year, teamNumber, program, includePrescout,
                users + strayUsers, entries, assignments, eventYears, eventNames
            )
        }
    }

    fun listOptions(teamNumber: Int, program: String): ScouterReportOptions = readTransaction {
        val (eventYears, eventNames) = loadEventInfo(teamNumber, program)
        val entries = loadEntries(teamNumber, program)
        val keys = entries.mapNotNull { it.eventKey?.trim()?.lowercase()?.takeIf { k -> k.isNotEmpty() } }.distinct()
        val events = keys.map { ScouterReportEvent(it, eventNames[it], resolveYear(it, eventYears, null)) }
            .sortedWith(compareByDescending<ScouterReportEvent> { it.year ?: 0 }.thenBy { it.name ?: it.eventKey })
        val years = entries.mapNotNull { resolveYear(it.eventKey, eventYears, it.createdAt) }.distinct().sortedDescending()
        ScouterReportOptions(years, events)
    }

    private fun loadEventInfo(teamNumber: Int, program: String): Pair<Map<String, Int>, Map<String, String>> {
        val baseEvents = ApiEvents.select(ApiEvents.eventKey, ApiEvents.year, ApiEvents.name).map {
            com.obsidianscout.routes.EventRecord(
                eventKey = it[ApiEvents.eventKey],
                name = it[ApiEvents.name],
                year = it[ApiEvents.year]
            )
        }
        val merged = runCatching {
            com.obsidianscout.integrations.TeamCacheOverrideService.eventOverlay(teamNumber, program).apply(baseEvents, null)
        }.getOrDefault(baseEvents)
        return merged.associate { it.eventKey.lowercase() to it.year } to
            merged.associate { it.eventKey.lowercase() to it.name }
    }

    private fun loadEntries(teamNumber: Int, program: String): List<ReportEntry> {
        val match = ScoutingEntries.select(
            ScoutingEntries.submittedByUserId, ScoutingEntries.eventKey, ScoutingEntries.matchKey,
            ScoutingEntries.matchNumber, ScoutingEntries.targetTeamNumber, ScoutingEntries.createdAt,
            ScoutingEntries.completenessPct, ScoutingEntries.hasDiscrepancy, ScoutingEntries.isPrescout
        ).where { (ScoutingEntries.ownerTeamNumber eq teamNumber) and (ScoutingEntries.program eq program) }.map {
            ReportEntry(
                EntryKind.MATCH, it[ScoutingEntries.submittedByUserId].value.toString(), it[ScoutingEntries.eventKey],
                it[ScoutingEntries.matchKey], it[ScoutingEntries.matchNumber], it[ScoutingEntries.targetTeamNumber],
                it[ScoutingEntries.createdAt], it[ScoutingEntries.completenessPct], it[ScoutingEntries.hasDiscrepancy],
                it[ScoutingEntries.isPrescout]
            )
        }
        val pit = PitScoutingEntries.select(
            PitScoutingEntries.submittedByUserId, PitScoutingEntries.eventKey, PitScoutingEntries.targetTeamNumber,
            PitScoutingEntries.createdAt, PitScoutingEntries.completenessPct, PitScoutingEntries.hasDiscrepancy,
            PitScoutingEntries.isPrescout
        ).where { (PitScoutingEntries.ownerTeamNumber eq teamNumber) and (PitScoutingEntries.program eq program) }.map {
            ReportEntry(
                EntryKind.PIT, it[PitScoutingEntries.submittedByUserId].value.toString(), it[PitScoutingEntries.eventKey],
                null, null, it[PitScoutingEntries.targetTeamNumber], it[PitScoutingEntries.createdAt],
                it[PitScoutingEntries.completenessPct], it[PitScoutingEntries.hasDiscrepancy], it[PitScoutingEntries.isPrescout]
            )
        }
        val qual = QualitativeScoutingEntries.select(
            QualitativeScoutingEntries.submittedByUserId, QualitativeScoutingEntries.eventKey,
            QualitativeScoutingEntries.matchKey, QualitativeScoutingEntries.matchNumber,
            QualitativeScoutingEntries.targetTeamNumber, QualitativeScoutingEntries.createdAt,
            QualitativeScoutingEntries.completenessPct, QualitativeScoutingEntries.hasDiscrepancy,
            QualitativeScoutingEntries.isPrescout
        ).where { (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and (QualitativeScoutingEntries.program eq program) }.map {
            ReportEntry(
                EntryKind.QUAL, it[QualitativeScoutingEntries.submittedByUserId].value.toString(),
                it[QualitativeScoutingEntries.eventKey], it[QualitativeScoutingEntries.matchKey],
                it[QualitativeScoutingEntries.matchNumber], it[QualitativeScoutingEntries.targetTeamNumber],
                it[QualitativeScoutingEntries.createdAt], it[QualitativeScoutingEntries.completenessPct],
                it[QualitativeScoutingEntries.hasDiscrepancy], it[QualitativeScoutingEntries.isPrescout]
            )
        }
        return match + pit + qual
    }

    /** One row per scouter, matching the on-screen table. */
    fun toSummaryCsv(report: ScouterReport): String {
        val headers = listOf(
            "Scouter", "Role", "Match Entries", "Matches Scouted", "Pit Entries", "Qual Entries", "Total Entries",
            "Events", "Teams Scouted", "Avg Completeness %", "Entries With Discrepancy",
            "Assignments", "Assignments Completed", "Assignments Skipped", "Assignment Completion %",
            "First Submission", "Last Submission"
        )
        val rows = report.scouters.map { r ->
            listOf(
                r.username, r.role, r.matchEntries.toString(), r.matchesScouted.toString(), r.pitEntries.toString(),
                r.qualEntries.toString(), r.totalEntries.toString(), r.eventsScouted.toString(), r.teamsScouted.toString(),
                r.avgCompletenessPct?.toString().orEmpty(), r.discrepancyEntries.toString(),
                r.assignmentsTotal.toString(), r.assignmentsCompleted.toString(), r.assignmentsSkipped.toString(),
                r.assignmentCompletionPct?.toString().orEmpty(), r.firstSubmission.orEmpty(), r.lastSubmission.orEmpty()
            )
        }
        return CSVHelper.toCSV(headers, rows)
    }

    /** One row per scouter per event, for season reports. */
    fun toByEventCsv(report: ScouterReport): String {
        val headers = listOf("Scouter", "Role", "Event Key", "Event Name", "Match Entries", "Pit Entries", "Qual Entries", "Total Entries")
        val rows = report.scouters.flatMap { r ->
            r.events.map { e ->
                listOf(
                    r.username, r.role, e.eventKey, e.eventName.orEmpty(), e.matchEntries.toString(), e.pitEntries.toString(),
                    e.qualEntries.toString(), (e.matchEntries + e.pitEntries + e.qualEntries).toString()
                )
            }
        }
        return CSVHelper.toCSV(headers, rows)
    }
}
