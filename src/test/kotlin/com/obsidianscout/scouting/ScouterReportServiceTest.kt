package com.obsidianscout.scouting

import com.obsidianscout.scouting.ScouterReportService.EntryKind
import com.obsidianscout.scouting.ScouterReportService.ReportAssignment
import com.obsidianscout.scouting.ScouterReportService.ReportEntry
import com.obsidianscout.scouting.ScouterReportService.ReportUser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScouterReportServiceTest {

    private val users = listOf(
        ReportUser("a", "alice", "SCOUT"),
        ReportUser("b", "bob", "SCOUT"),
        ReportUser("c", "carol", "ADMIN")
    )

    private fun entry(
        kind: EntryKind,
        user: String,
        event: String?,
        match: Int? = null,
        team: Int? = 100,
        at: String = "2026-03-10T15:00:00Z",
        completeness: Float? = null,
        discrepancy: Boolean = false,
        prescout: Boolean = false
    ) = ReportEntry(
        kind, user, event, match?.let { "${event}_qm$it" }, match, team, Instant.parse(at),
        completeness, discrepancy, prescout
    )

    private val entries = listOf(
        // alice: two entries for the same match (two teams), one other match, one pit entry
        entry(EntryKind.MATCH, "a", "2026abc", 1, team = 100, completeness = 80f),
        entry(EntryKind.MATCH, "a", "2026abc", 1, team = 200, completeness = 100f, discrepancy = true),
        entry(EntryKind.MATCH, "a", "2026abc", 2, team = 300),
        entry(EntryKind.PIT, "a", "2026abc", team = 400),
        // bob: one match at another 2026 event and one at a 2025 event
        entry(EntryKind.MATCH, "b", "2026xyz", 5),
        entry(EntryKind.QUAL, "b", "2025old", 3, at = "2025-03-10T15:00:00Z"),
        // a prescout entry that is excluded by default
        entry(EntryKind.MATCH, "b", "2026abc", 9, prescout = true),
        // someone no longer on the team
        entry(EntryKind.MATCH, "gone", "2026abc", 2)
    )

    private val assignments = listOf(
        ReportAssignment("a", "2026abc", "COMPLETED"),
        ReportAssignment("a", "2026abc", "SKIPPED"),
        ReportAssignment("b", "2026abc", "PENDING"),
        ReportAssignment("b", "2026xyz", "COMPLETED")
    )

    private fun build(eventKey: String? = null, year: Int? = null, includePrescout: Boolean = false) =
        ScouterReportService.aggregate(
            scope = "test", eventKey = eventKey, year = year, teamNumber = 1, program = "FRC",
            includePrescout = includePrescout, users = users, entries = entries, assignments = assignments,
            eventYears = emptyMap(), eventNames = mapOf("2026abc" to "ABC Regional")
        )

    @Test
    fun eventReportCountsDistinctMatchesAndAssignments() {
        val report = build(eventKey = "2026ABC")
        val alice = report.scouters.first { it.username == "alice" }

        assertEquals("2026abc", report.eventKey)
        assertEquals("ABC Regional", report.eventName)
        assertEquals(2026, report.year)
        assertEquals(3, alice.matchEntries)
        assertEquals(2, alice.matchesScouted)
        assertEquals(1, alice.pitEntries)
        assertEquals(4, alice.totalEntries)
        assertEquals(4, alice.teamsScouted)
        assertEquals(90.0, alice.avgCompletenessPct)
        assertEquals(1, alice.discrepancyEntries)
        assertEquals(2, alice.assignmentsTotal)
        assertEquals(50.0, alice.assignmentCompletionPct)
        // Most active scout first.
        assertEquals("alice", report.scouters.first().username)
    }

    @Test
    fun prescoutIsExcludedUnlessRequested() {
        val bob = build(eventKey = "2026abc").scouters.first { it.username == "bob" }
        assertEquals(0, bob.totalEntries)
        assertEquals(1, bob.assignmentsTotal)

        val withPrescout = build(eventKey = "2026abc", includePrescout = true).scouters.first { it.username == "bob" }
        assertEquals(1, withPrescout.matchEntries)
    }

    @Test
    fun yearReportSpansEventsOfThatSeasonOnly() {
        val report = build(year = 2026)
        val bob = report.scouters.first { it.username == "bob" }

        assertEquals(1, bob.totalEntries)
        assertEquals(listOf("2026xyz"), bob.events.map { it.eventKey })
        assertEquals(1, bob.assignmentsCompleted)
        assertEquals(2, bob.assignmentsTotal)
        // Distinct matches across the team: abc qm1, abc qm2, xyz qm5.
        assertEquals(3, report.totals.matchesCovered)
        assertEquals(3, report.totals.activeScouters)
    }

    @Test
    fun unknownSubmittersAndIdleUsersAreListed() {
        val report = build(eventKey = "2026abc")
        val stray = report.scouters.first { it.userId == "gone" }
        val carol = report.scouters.first { it.username == "carol" }

        assertEquals("Unknown user", stray.username)
        assertEquals(1, stray.matchEntries)
        assertEquals(0, carol.totalEntries)
        assertNull(carol.avgCompletenessPct)
        assertNull(carol.assignmentCompletionPct)
    }

    @Test
    fun resolveYearFallsBackToEventTableThenTimestamp() {
        assertEquals(2024, ScouterReportService.resolveYear("2024casj", emptyMap(), null))
        assertEquals(2023, ScouterReportService.resolveYear("custom", mapOf("custom" to 2023), null))
        assertEquals(2022, ScouterReportService.resolveYear(null, emptyMap(), Instant.parse("2022-05-01T00:00:00Z")))
    }

    @Test
    fun csvExportsHaveOneRowPerScouterOrPerEvent() {
        val report = build(year = 2026)
        val summary = ScouterReportService.toSummaryCsv(report).trim().lines()
        assertEquals(report.scouters.size + 1, summary.size)
        assertTrue(summary.first().startsWith("Scouter,Role,Match Entries"))

        val byEvent = ScouterReportService.toByEventCsv(report).trim().lines()
        assertEquals(report.scouters.sumOf { it.events.size } + 1, byEvent.size)
        assertTrue(byEvent.any { it.startsWith("alice,SCOUT,2026abc,ABC Regional,3,1,0,4") })
    }
}
