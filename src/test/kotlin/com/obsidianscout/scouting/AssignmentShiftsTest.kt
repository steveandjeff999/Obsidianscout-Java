package com.obsidianscout.scouting

import com.obsidianscout.db.TeamCacheOverrides
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.integrations.SettingsService
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssignmentShiftsTest {

    private val testDbFile = File("build/test_assignment_shifts_${System.currentTimeMillis()}.db")
    private val eventKey = "2026txhou"
    private val zone = ZoneId.of("America/Chicago")
    // Shifts on days already past are skipped after a sync, so plan one in the future
    private val shiftDay = LocalDate.now(zone).plusDays(10)

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, AppSettings, ApiEvents, ApiTeams, ApiMatches, TeamCacheOverrides, ScoutingAssignments,
                ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries,
                ScoutingAlliances, AllianceMemberships
            )
            ApiEvents.insert {
                it[ApiEvents.eventKey] = this@AssignmentShiftsTest.eventKey
                it[year] = 2026
                it[eventCode] = "txhou"
                it[name] = "Houston"
                it[timezone] = "America/Chicago"
                it[dataJson] = "{}"
                it[updatedAt] = Instant.now()
            }
        }
        SettingsService.ensureDefaultSettings()
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun addUser(name: String, role: UserRole = UserRole.SCOUT) = transaction {
        Users.insertAndGetId {
            it[username] = name
            it[passwordHash] = "hash"
            it[teamNumber] = 254
            it[program] = "FRC"
            it[this.role] = role.name
            it[createdAt] = Instant.now()
        }.value.toString()
    }

    private fun admin() = UserSession(addUser("lead", UserRole.ADMIN), "lead", 254, "FRC", UserRole.ADMIN)

    private fun addMatch(number: Int, localTime: LocalTime) = transaction {
        ApiMatches.insert {
            it[matchKey] = "${this@AssignmentShiftsTest.eventKey}_qm$number"
            it[ApiMatches.eventKey] = this@AssignmentShiftsTest.eventKey
            it[compLevel] = "qm"
            it[matchNumber] = number
            it[scheduledTime] = ZonedDateTime.of(shiftDay, localTime, zone).toEpochSecond()
            it[redTeams] = """["frc1","frc2","frc3"]"""
            it[blueTeams] = """["frc4","frc5","frc6"]"""
            it[dataJson] = "{}"
            it[updatedAt] = Instant.now()
        }
    }

    private fun seats(matchNumber: Int, type: String) = transaction {
        ScoutingAssignments.selectAll().where {
            (ScoutingAssignments.matchNumber eq matchNumber) and (ScoutingAssignments.assignmentType eq type)
        }.associate { (it[ScoutingAssignments.targetTeamNumber] to it[ScoutingAssignments.allianceColor]) to it[ScoutingAssignments.assignedUserId].value.toString() }
    }

    /** Key of a filled slot in [seats]: target team (null for an alliance-wide slot) and alliance. */
    private fun slot(team: Int?, alliance: String): Pair<Int?, String?> = team to alliance

    private fun request(match: List<String> = emptyList(), qualMode: String = "TEAMS", qual: List<String> = emptyList(), event: String = eventKey) =
        SaveAssignmentShiftRequest(
            eventKey = event,
            day = shiftDay.toString(),
            startTime = "08:00",
            endTime = "12:00",
            matchScouterIds = match,
            qualMode = qualMode,
            qualScouterIds = qual
        )

    @Test
    fun eachScoutKeepsTheirStationForEveryMatchInTheShift() {
        val admin = admin()
        val match = (1..6).map { addUser("match$it") }
        val qual = listOf(addUser("qualRed"), addUser("qualBlue"))

        // No matches yet: the shift is stored and waits
        val saved = ScoutingAssignmentService.saveShift(admin, request(match, "ALLIANCE", qual))
        assertEquals(0, saved.createdCount)
        assertEquals(0, saved.shifts.single().matchCount)

        // Schedule arrives: qm1 and qm2 are inside the shift, qm3 starts at its end so it is outside
        addMatch(1, LocalTime.of(9, 0))
        addMatch(2, LocalTime.of(9, 10))
        addMatch(3, LocalTime.of(12, 0))
        ScoutingAssignmentService.applyShiftsAfterSync("FRC", listOf(eventKey))

        val expectedMatch = mapOf(
            slot(1, "RED") to match[0], slot(2, "RED") to match[1], slot(3, "RED") to match[2],
            slot(4, "BLUE") to match[3], slot(5, "BLUE") to match[4], slot(6, "BLUE") to match[5]
        )
        val expectedQual = mapOf(slot(null, "RED") to qual[0], slot(null, "BLUE") to qual[1])
        listOf(1, 2).forEach { n ->
            assertEquals(expectedMatch, seats(n, "MATCH"))
            assertEquals(expectedQual, seats(n, "QUALITATIVE"))
        }
        assertTrue(seats(3, "MATCH").isEmpty())

        // A later sync leaves filled slots alone
        ScoutingAssignmentService.applyShiftsAfterSync("FRC", listOf(eventKey))
        assertEquals(6, seats(1, "MATCH").size)

        val listed = ScoutingAssignmentService.listShifts(admin, eventKey).shifts.single()
        assertEquals(2, listed.matchCount)
        assertEquals(16, listed.shift.appliedCount)
        assertEquals("match1", listed.scouterNames[match[0]])
    }

    @Test
    fun openSeatsAndTakenSlotsAreLeftAlone() {
        val admin = admin()
        val red2 = addUser("red2")
        val other = addUser("other")
        addMatch(1, LocalTime.of(9, 0))
        // Someone already covers team 5 by hand
        ScoutingAssignmentService.createAssignment(
            admin,
            CreateAssignmentRequest(
                assignedUserId = other, assignmentType = "MATCH", eventKey = eventKey,
                matchKey = "${eventKey}_qm1", matchNumber = 1, targetTeamNumber = 5, allianceColor = "BLUE"
            )
        )
        val blue2 = addUser("blue2")

        val saved = ScoutingAssignmentService.saveShift(admin, request(listOf("", red2, "", "", blue2)))
        assertEquals(1, saved.createdCount)
        assertEquals(mapOf(slot(2, "RED") to red2, slot(5, "BLUE") to other), seats(1, "MATCH"))
    }

    @Test
    fun seatLimitsAreEnforced() {
        val admin = admin()
        val scouts = (1..7).map { addUser("s$it") }
        assertFailsWith<ApiException> { ScoutingAssignmentService.saveShift(admin, request(scouts)) }
        assertFailsWith<ApiException> { ScoutingAssignmentService.saveShift(admin, request(qualMode = "ALLIANCE", qual = scouts.take(3))) }
        assertFailsWith<ApiException> { ScoutingAssignmentService.saveShift(admin, request(match = listOf(scouts[0]), qual = listOf(scouts[0]))) }
    }

    private fun playMatch(number: Int, localTime: LocalTime) = transaction {
        ApiMatches.update({ ApiMatches.matchKey eq "${eventKey}_qm$number" }) {
            it[actualTime] = ZonedDateTime.of(shiftDay, localTime, zone).toEpochSecond()
        }
    }

    private fun shift(match: List<String>, start: String, end: String) =
        SaveAssignmentShiftRequest(eventKey = eventKey, day = shiftDay.toString(), startTime = start, endTime = end, matchScouterIds = match)

    @Test
    fun matchesFollowScheduleDriftIntoTheShiftThatWillScoutThem() {
        val admin = admin()
        val morning = listOf(addUser("am1"), addUser("am2"))
        val midday = listOf(addUser("mid1"), addUser("mid2"))
        addMatch(1, LocalTime.of(8, 30))
        addMatch(2, LocalTime.of(9, 50))
        addMatch(3, LocalTime.of(10, 10))
        ScoutingAssignmentService.saveShift(admin, shift(morning, "08:00", "10:00"))
        ScoutingAssignmentService.saveShift(admin, shift(midday, "10:00", "12:00"))
        val morningSeats = mapOf(slot(1, "RED") to morning[0], slot(2, "RED") to morning[1])
        val middaySeats = mapOf(slot(1, "RED") to midday[0], slot(2, "RED") to midday[1])
        assertEquals(morningSeats, seats(2, "MATCH"))
        assertEquals(middaySeats, seats(3, "MATCH"))

        // Running 25 minutes behind: qm2 now starts around 10:15, when the midday shift is scouting
        playMatch(1, LocalTime.of(8, 55))
        ScoutingAssignmentService.applyShiftsAfterSync("FRC", listOf(eventKey))
        assertEquals(morningSeats, seats(1, "MATCH"))
        assertEquals(middaySeats, seats(2, "MATCH"))
        assertEquals(middaySeats, seats(3, "MATCH"))

        // Then 30 minutes ahead: qm2 and qm3 both land back in the morning
        playMatch(1, LocalTime.of(8, 0))
        ScoutingAssignmentService.applyShiftsAfterSync("FRC", listOf(eventKey))
        assertEquals(morningSeats, seats(2, "MATCH"))
        assertEquals(morningSeats, seats(3, "MATCH"))
    }

    @Test
    fun driftLeavesStartedAndHandMadeAssignmentsAlone() {
        val admin = admin()
        val morning = addUser("am1")
        val midday = addUser("mid1")
        val helper = addUser("helper")
        addMatch(1, LocalTime.of(8, 30))
        addMatch(2, LocalTime.of(9, 50))
        ScoutingAssignmentService.saveShift(admin, shift(listOf(morning), "08:00", "10:00"))
        ScoutingAssignmentService.saveShift(admin, shift(listOf(midday, ""), "10:00", "12:00"))
        // Red 2 of qm2 is covered by hand, and red 1 has already been started
        ScoutingAssignmentService.createAssignment(
            admin,
            CreateAssignmentRequest(
                assignedUserId = helper, assignmentType = "MATCH", eventKey = eventKey,
                matchKey = "${eventKey}_qm2", matchNumber = 2, targetTeamNumber = 2, allianceColor = "RED"
            )
        )
        transaction {
            ScoutingAssignments.update({ (ScoutingAssignments.matchNumber eq 2) and (ScoutingAssignments.targetTeamNumber eq 1) }) {
                it[status] = "IN_PROGRESS"
            }
        }

        playMatch(1, LocalTime.of(8, 55))
        ScoutingAssignmentService.applyShiftsAfterSync("FRC", listOf(eventKey))
        assertEquals(mapOf(slot(1, "RED") to morning, slot(2, "RED") to helper), seats(2, "MATCH"))
    }

    @Test
    fun shiftForAnotherEventIsNotApplied() {
        val admin = admin()
        val scout = addUser("scout1")
        ScoutingAssignmentService.saveShift(admin, request(listOf(scout), event = "2026other"))
        addMatch(1, LocalTime.of(9, 0))
        ScoutingAssignmentService.applyShiftsAfterSync("FRC", listOf(eventKey))
        assertTrue(seats(1, "MATCH").isEmpty())
    }
}
