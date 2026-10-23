package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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

    @Test
    fun testAssignmentStageOrdering() {
        val (adminSession, scoutSession) = transaction {
            val adminId = Users.insertAndGetId {
                it[username] = "lead_admin"
                it[passwordHash] = "hash"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[role] = UserRole.ADMIN.name
                it[createdAt] = Instant.now()
            }
            val scoutId = Users.insertAndGetId {
                it[username] = "scout_alex"
                it[passwordHash] = "hash"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[role] = UserRole.SCOUT.name
                it[createdAt] = Instant.now()
            }
            Pair(
                UserSession(adminId.value.toString(), "lead_admin", 254, "FRC", UserRole.ADMIN, "lead_admin"),
                UserSession(scoutId.value.toString(), "scout_alex", 254, "FRC", UserRole.SCOUT, "Alex")
            )
        }

        ScoutingAssignmentService.bulkCreateAssignments(
            adminSession,
            BulkCreateAssignmentsRequest(
                eventKey = "2026caln",
                assignments = listOf(
                    BulkAssignmentItem(assignedUserId = scoutSession.userId, assignmentType = "MATCH", matchKey = "2026caln_f1m1", matchNumber = 1, compLevel = "f", targetTeamNumber = 254),
                    BulkAssignmentItem(assignedUserId = scoutSession.userId, assignmentType = "MATCH", matchKey = "2026caln_sf2m1", matchNumber = 1, compLevel = "sf", targetTeamNumber = 254),
                    BulkAssignmentItem(assignedUserId = scoutSession.userId, assignmentType = "MATCH", matchKey = "2026caln_sf1m1", matchNumber = 1, compLevel = "sf", targetTeamNumber = 254),
                    BulkAssignmentItem(assignedUserId = scoutSession.userId, assignmentType = "MATCH", matchKey = "2026caln_qf1m1", matchNumber = 1, compLevel = "qf", targetTeamNumber = 254),
                    BulkAssignmentItem(assignedUserId = scoutSession.userId, assignmentType = "MATCH", matchKey = "2026caln_qm10", matchNumber = 10, compLevel = "qm", targetTeamNumber = 254),
                    BulkAssignmentItem(assignedUserId = scoutSession.userId, assignmentType = "MATCH", matchKey = "2026caln_qm1", matchNumber = 1, compLevel = "qm", targetTeamNumber = 254),
                    BulkAssignmentItem(assignedUserId = scoutSession.userId, assignmentType = "MATCH", matchKey = "2026caln_pr2", matchNumber = 2, compLevel = "pr", targetTeamNumber = 254),
                    BulkAssignmentItem(assignedUserId = scoutSession.userId, assignmentType = "MATCH", matchKey = "2026caln_pr1", matchNumber = 1, compLevel = "practice", targetTeamNumber = 254)
                )
            )
        )
        val ordered = ScoutingAssignmentService.getMyAssignments(scoutSession, "2026caln")
        assertEquals(
            listOf(
                "2026caln_pr1",
                "2026caln_pr2",
                "2026caln_qm1",
                "2026caln_qm10",
                "2026caln_qf1m1",
                "2026caln_sf1m1",
                "2026caln_sf2m1",
                "2026caln_f1m1"
            ),
            ordered.map { it.matchKey }
        )
    }

    private fun insertScouts(vararg names: String): List<String> = transaction {
        names.map { n ->
            Users.insertAndGetId {
                it[username] = n
                it[passwordHash] = "hash"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[role] = UserRole.SCOUT.name
                it[createdAt] = Instant.now()
            }.value.toString()
        }
    }

    private val adminSession = UserSession(java.util.UUID.randomUUID().toString(), "lead_admin", 254, "FRC", UserRole.ADMIN, "lead_admin")

    @Test
    fun testPitAutoAssignWithScoutGroups() {
        val (jack, bob, alice, dana) = insertScouts("jack", "bob", "alice", "dana")
        transaction {
            listOf(100, 200, 300, 400).forEach { num ->
                ApiTeams.insert {
                    it[eventKey] = "2026caln"
                    it[teamKey] = "frc$num"
                    it[teamNumber] = num
                    it[dataJson] = "{}"
                    it[updatedAt] = Instant.now()
                }
            }
        }

        // One group of three plus one solo scout -> two units alternate across four teams
        val res = ScoutingAssignmentService.autoGenerateAssignments(
            adminSession,
            AutoGenerateAssignmentsRequest(
                eventKey = "2026caln",
                assignmentType = "PIT_AUTO",
                scouterUserIds = listOf(jack, bob, alice, dana),
                pitGroups = listOf(PitGroupAssignment(name = "Pit Crew A", memberIds = listOf(jack, bob, alice)))
            )
        )
        assertEquals(8, res.createdCount) // 2 teams x 3 group members + 2 teams x 1 solo

        val pit = ScoutingAssignmentService.listAssignments(adminSession, eventKey = "2026caln").filter { it.assignmentType == "PIT" }
        val byTeam = pit.groupBy { it.targetTeamNumber }
        assertEquals(setOf(jack, bob, alice), byTeam[100]!!.map { it.assignedUserId }.toSet())
        assertTrue(byTeam[100]!!.all { it.scoutGroup == "Pit Crew A" })
        assertEquals(listOf(dana), byTeam[200]!!.map { it.assignedUserId })
        assertEquals(3, byTeam[300]!!.size)

        // Each member's own list carries the whole roster
        val jackSession = UserSession(jack, "jack", 254, "FRC", UserRole.SCOUT, "jack")
        val jackPit = ScoutingAssignmentService.getMyAssignments(jackSession, "2026caln").first { it.targetTeamNumber == 100 }
        assertEquals(listOf("alice", "bob", "jack"), jackPit.scoutGroupMembers)

        // A group on one team is not a conflict, and auto-resolve leaves it alone
        assertTrue(!ScoutingAssignmentService.getConflicts(adminSession, "2026caln").hasConflict)
        assertEquals(0, ScoutingAssignmentService.autoResolveConflicts(adminSession, "2026caln").resolvedCount)

        // An extra solo scout on a group's team is a conflict; resolving keeps the whole group
        ScoutingAssignmentService.createAssignment(
            adminSession,
            CreateAssignmentRequest(assignedUserId = dana, assignmentType = "PIT", eventKey = "2026caln", targetTeamNumber = 100)
        )
        assertTrue(ScoutingAssignmentService.getConflicts(adminSession, "2026caln").hasConflict)
        assertEquals(1, ScoutingAssignmentService.autoResolveConflicts(adminSession, "2026caln").resolvedCount)
        val team100 = ScoutingAssignmentService.listAssignments(adminSession, eventKey = "2026caln")
            .filter { it.assignmentType == "PIT" && it.targetTeamNumber == 100 }
        assertEquals(setOf(jack, bob, alice), team100.map { it.assignedUserId }.toSet())

        // One member submitting pit data completes the assignment for the whole group
        ScoutingAssignmentService.handleScoutingSubmission(254, "FRC", "2026caln", "PIT", null, null, 300, jack)
        val team300 = ScoutingAssignmentService.listAssignments(adminSession, eventKey = "2026caln")
            .filter { it.assignmentType == "PIT" && it.targetTeamNumber == 300 }
        assertTrue(team300.all { it.status == "COMPLETED" })
    }

    @Test
    fun testAutoGenerateLimitedToTimeBlockMatches() {
        val (jack, bob) = insertScouts("jack", "bob")
        transaction {
            (1..4).forEach { n ->
                ApiMatches.insert {
                    it[matchKey] = "2026caln_qm$n"
                    it[eventKey] = "2026caln"
                    it[compLevel] = "qm"
                    it[matchNumber] = n
                    it[redTeams] = "frc1,frc2,frc3"
                    it[blueTeams] = "frc4,frc5,frc6"
                    it[dataJson] = "{}"
                    it[updatedAt] = Instant.now()
                }
            }
        }

        ScoutingAssignmentService.autoGenerateAssignments(
            adminSession,
            AutoGenerateAssignmentsRequest(
                eventKey = "2026caln",
                assignmentType = "QUALITATIVE_RED",
                scouterUserIds = listOf(jack, bob),
                matchKeys = listOf("2026caln_qm2", "2026caln_qm3")
            )
        )
        val keys = ScoutingAssignmentService.listAssignments(adminSession, eventKey = "2026caln").map { it.matchKey }.toSet()
        assertEquals(setOf("2026caln_qm2", "2026caln_qm3"), keys)
    }
}
