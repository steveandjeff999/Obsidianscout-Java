package com.obsidianscout.scouting

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScoutingAssignmentServiceTest {

    private val testDbFile = File("build/test_scouting_assignments_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries,
                ApiMatches, ApiTeams, ScoutingAssignments
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testAssignmentLifecycleAndConflictDetection() {
        val (adminSession, scout1Session, scout2Session) = transaction {
            val adminId = Users.insertAndGetId {
                it[username] = "lead_admin"
                it[passwordHash] = "hash"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[role] = UserRole.ADMIN.name
                it[createdAt] = Instant.now()
            }
            val scout1Id = Users.insertAndGetId {
                it[username] = "scout_alex"
                it[passwordHash] = "hash"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[role] = UserRole.SCOUT.name
                it[createdAt] = Instant.now()
            }
            val scout2Id = Users.insertAndGetId {
                it[username] = "scout_bob"
                it[passwordHash] = "hash"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[role] = UserRole.SCOUT.name
                it[createdAt] = Instant.now()
            }

            Triple(
                UserSession(adminId.value.toString(), "lead_admin", 254, "FRC", UserRole.ADMIN, "lead_admin"),
                UserSession(scout1Id.value.toString(), "scout_alex", 254, "FRC", UserRole.SCOUT, "Alex"),
                UserSession(scout2Id.value.toString(), "scout_bob", 254, "FRC", UserRole.SCOUT, "Bob")
            )
        }

        // 1. Admin creates a match assignment for Alex
        val created1 = ScoutingAssignmentService.createAssignment(
            adminSession,
            CreateAssignmentRequest(
                eventKey = "2026caln",
                assignmentType = "MATCH",
                assignedUserId = scout1Session.userId,
                matchKey = "2026caln_qm1",
                matchNumber = 1,
                targetTeamNumber = 254,
                notes = "Watch auto intake"
            )
        )
        assertNotNull(created1)
        assertEquals("PENDING", created1.status)
        assertEquals(254, created1.targetTeamNumber)

        // 2. Query Alex's assignments
        val alexAsgns = ScoutingAssignmentService.getMyAssignments(scout1Session, "2026caln")
        assertEquals(1, alexAsgns.size)
        assertEquals("2026caln_qm1", alexAsgns[0].matchKey)

        // 3. Admin assigns Bob to the SAME match and team -> Conflict detection
        val created2 = ScoutingAssignmentService.createAssignment(
            adminSession,
            CreateAssignmentRequest(
                eventKey = "2026caln",
                assignmentType = "MATCH",
                assignedUserId = scout2Session.userId,
                matchKey = "2026caln_qm1",
                matchNumber = 1,
                targetTeamNumber = 254
            )
        )
        assertNotNull(created2)

        val conflictDto = ScoutingAssignmentService.getConflicts(
            session = adminSession,
            eventKey = "2026caln",
            assignmentType = "MATCH",
            matchNumber = 1,
            targetTeamNumber = 254
        )
        assertTrue(conflictDto.hasConflict)
        assertEquals(2, conflictDto.existingScouters.size)

        // 4. Test Pit Assignment & Conflict
        ScoutingAssignmentService.createAssignment(
            adminSession,
            CreateAssignmentRequest(
                eventKey = "2026caln",
                assignmentType = "PIT",
                assignedUserId = scout1Session.userId,
                targetTeamNumber = 1678
            )
        )
        ScoutingAssignmentService.createAssignment(
            adminSession,
            CreateAssignmentRequest(
                eventKey = "2026caln",
                assignmentType = "PIT",
                assignedUserId = scout2Session.userId,
                targetTeamNumber = 1678
            )
        )
        val pitConflict = ScoutingAssignmentService.getConflicts(
            session = adminSession,
            eventKey = "2026caln",
            assignmentType = "PIT",
            targetTeamNumber = 1678
        )
        assertTrue(pitConflict.hasConflict)
        assertEquals(2, pitConflict.existingScouters.size)

        // 5. Test Auto-Completion on Scouting Submission
        ScoutingAssignmentService.handleScoutingSubmission(
            ownerTeam = 254,
            program = "FRC",
            eventKey = "2026caln",
            assignmentType = "MATCH",
            matchNumber = 1,
            matchKey = "2026caln_qm1",
            targetTeamNumber = 254,
            userId = scout1Session.userId
        )

        // Verify assignment status transitioned to COMPLETED
        val updatedAlexAsgns = ScoutingAssignmentService.getMyAssignments(scout1Session, "2026caln")
        val matchAsgn = updatedAlexAsgns.find { it.id == created1.id }
        assertNotNull(matchAsgn)
        assertEquals("COMPLETED", matchAsgn.status)
        assertNotNull(matchAsgn.completedAt)

        // 6. Test Bulk Creation
        val bulkList = listOf(
            BulkAssignmentItem(
                assignmentType = "MATCH",
                assignedUserId = scout1Session.userId,
                matchKey = "2026caln_qm2",
                matchNumber = 2,
                targetTeamNumber = 971
            ),
            BulkAssignmentItem(
                assignmentType = "MATCH",
                assignedUserId = scout2Session.userId,
                matchKey = "2026caln_qm2",
                matchNumber = 2,
                targetTeamNumber = 1323
            )
        )
        val createdBulk = ScoutingAssignmentService.bulkCreateAssignments(
            adminSession,
            BulkCreateAssignmentsRequest(
                eventKey = "2026caln",
                assignments = bulkList
            )
        )
        assertEquals(2, createdBulk.size)

        val totalAssignments = ScoutingAssignmentService.listAssignments(adminSession, eventKey = "2026caln")
        assertEquals(6, totalAssignments.size)

        // 7. Test Coverage Summary
        val coverage = ScoutingAssignmentService.getCoverage(adminSession, "2026caln")
        assertEquals("2026caln", coverage.eventKey)
        assertEquals(6, coverage.totalAssignments)
        assertEquals(2, coverage.completedCount)
        assertEquals(4, coverage.pendingCount)

        // 8. Test Delete All Assignments
        val deletedCount = ScoutingAssignmentService.deleteAllAssignments(adminSession, "2026caln")
        assertEquals(6, deletedCount)
        val remaining = ScoutingAssignmentService.listAssignments(adminSession, eventKey = "2026caln")
        assertEquals(0, remaining.size)
    }
}
