package com.obsidianscout.scouting

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoutingEventFilterTest {

    private val testDbFile = File("build/test_scouting_event_filter_${System.currentTimeMillis()}.db")

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
                ScoutingAlliances, AllianceMemberships, ApiMatches
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
    fun testEventKeyFilteringOnScoutingServices() {
        val userUuid = transaction {
            Users.insertAndGetId {
                it[username] = "analyst1"
                it[passwordHash] = "hash"
                it[teamNumber] = 1111
                it[program] = "FRC"
                it[role] = UserRole.ANALYTICS.name
                it[createdAt] = Instant.now()
            }
        }

        val session = UserSession(userUuid.value.toString(), "analyst1", 1111, "FRC", UserRole.ANALYTICS)

        transaction {
            val now = Instant.now()
            // Match scouting entries
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 1111
                it[targetTeamNumber] = 101
                it[eventKey] = "2026eventA"
                it[matchKey] = "2026eventA_qm1"
                it[matchNumber] = 1
                it[dataJson] = "{}"
                it[submittedByUserId] = userUuid
                it[createdAt] = now
            }
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 1111
                it[targetTeamNumber] = 102
                it[eventKey] = "2026eventB"
                it[matchKey] = "2026eventB_qm1"
                it[matchNumber] = 1
                it[dataJson] = "{}"
                it[submittedByUserId] = userUuid
                it[createdAt] = now
            }

            // Pit scouting entries
            PitScoutingEntries.insert {
                it[ownerTeamNumber] = 1111
                it[targetTeamNumber] = 101
                it[eventKey] = "2026eventA"
                it[dataJson] = "{}"
                it[submittedByUserId] = userUuid
                it[createdAt] = now
            }
            PitScoutingEntries.insert {
                it[ownerTeamNumber] = 1111
                it[targetTeamNumber] = 102
                it[eventKey] = "2026eventB"
                it[dataJson] = "{}"
                it[submittedByUserId] = userUuid
                it[createdAt] = now
            }

            // Qualitative scouting entries
            QualitativeScoutingEntries.insert {
                it[ownerTeamNumber] = 1111
                it[targetTeamNumber] = 101
                it[eventKey] = "2026eventA"
                it[matchKey] = "2026eventA_qm1"
                it[matchNumber] = 1
                it[dataJson] = "{}"
                it[submittedByUserId] = userUuid
                it[createdAt] = now
            }
            QualitativeScoutingEntries.insert {
                it[ownerTeamNumber] = 1111
                it[targetTeamNumber] = 102
                it[eventKey] = "2026eventB"
                it[matchKey] = "2026eventB_qm1"
                it[matchNumber] = 1
                it[dataJson] = "{}"
                it[submittedByUserId] = userUuid
                it[createdAt] = now
            }
        }

        // Test ScoutingService.listEntries
        val matchAll = ScoutingService.listEntries(session, includePrescout = true, all = true)
        assertEquals(2, matchAll.size, "Without eventKey, all entries are returned")

        val matchFilteredA = ScoutingService.listEntries(session, includePrescout = true, all = true, eventKey = "2026eventA")
        assertEquals(1, matchFilteredA.size, "With eventKey=2026eventA, only 1 entry returned")
        assertEquals("2026eventA", matchFilteredA[0].eventKey)

        val matchFilteredB = ScoutingService.listEntries(session, includePrescout = true, all = true, eventKey = "2026eventB")
        assertEquals(1, matchFilteredB.size, "With eventKey=2026eventB, only 1 entry returned")
        assertEquals("2026eventB", matchFilteredB[0].eventKey)

        // Test PitScoutingService.listEntries
        val pitAll = PitScoutingService.listEntries(session, includePrescout = true, all = true)
        assertEquals(2, pitAll.size)

        val pitFilteredA = PitScoutingService.listEntries(session, includePrescout = true, all = true, eventKey = "2026eventA")
        assertEquals(1, pitFilteredA.size)
        assertEquals("2026eventA", pitFilteredA[0].eventKey)

        // Test QualitativeScoutingService.listEntries
        val qualAll = QualitativeScoutingService.listEntries(session, includePrescout = true, all = true)
        assertEquals(2, qualAll.size)

        val qualFilteredA = QualitativeScoutingService.listEntries(session, includePrescout = true, all = true, eventKey = "2026eventA")
        assertEquals(1, qualFilteredA.size)
        assertEquals("2026eventA", qualFilteredA[0].eventKey)
    }
}
