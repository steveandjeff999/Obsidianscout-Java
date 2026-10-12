package com.obsidianscout.integrations

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.UserRole
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.MatchChecklists
import com.obsidianscout.db.MatchPlans
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.routes.MatchRecord
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualMatchMergeTest {

    private val testDbFile = File("build/test_manual_match_merge_${System.currentTimeMillis()}.db")
    private val event = "2026test"
    private val legacyKey = "${event}_qm_s1_m5"
    private val canonicalKey = "${event}_qm5"
    private val red = listOf("frc1", "frc2", "frc3")
    private val blue = listOf("frc4", "frc5", "frc6")
    private lateinit var userId: UUID

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, ApiMatches, ScoutingEntries, QualitativeScoutingEntries, ScoutingAssignments, MatchPlans, MatchChecklists)
            userId = Users.insertAndGetId {
                it[username] = "admin"
                it[passwordHash] = "hash"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[role] = UserRole.ADMIN.name
                it[createdAt] = Instant.now()
            }.value
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun teamsJson(teams: List<String>) = "[" + teams.joinToString(",") { "\"$it\"" } + "]"

    private fun insertMatchRow(key: String, dataJson: String) = transaction {
        ApiMatches.insert {
            it[matchKey] = key
            it[eventKey] = event
            it[compLevel] = "qm"
            it[setNumber] = 1
            it[matchNumber] = 5
            it[redTeams] = teamsJson(red)
            it[blueTeams] = teamsJson(blue)
            it[ApiMatches.dataJson] = dataJson
            it[updatedAt] = Instant.now()
        }
    }

    private fun insertChildRows(key: String, planUpdatedAt: Instant = Instant.now()) = transaction {
        ScoutingEntries.insert {
            it[ownerTeamNumber] = 254
            it[targetTeamNumber] = 1
            it[eventKey] = event
            it[matchKey] = key
            it[dataJson] = "{}"
            it[submittedByUserId] = userId
            it[createdAt] = Instant.now()
        }
        QualitativeScoutingEntries.insert {
            it[ownerTeamNumber] = 254
            it[eventKey] = event
            it[matchKey] = key
            it[dataJson] = "{}"
            it[submittedByUserId] = userId
            it[createdAt] = Instant.now()
        }
        ScoutingAssignments.insert {
            it[ownerTeamNumber] = 254
            it[eventKey] = event
            it[assignedUserId] = userId
            it[assignmentType] = "QUALITATIVE"
            it[matchKey] = key
            it[createdAt] = Instant.now()
            it[updatedAt] = Instant.now()
        }
        MatchPlans.insert {
            it[ownerKey] = "254"
            it[eventKey] = event
            it[matchKey] = key
            it[planJson] = """{"from":"$key"}"""
            it[updatedAt] = planUpdatedAt
        }
    }

    private fun childKeys(): List<String?> = transaction {
        listOf(
            ScoutingEntries.selectAll().single()[ScoutingEntries.matchKey],
            QualitativeScoutingEntries.selectAll().single()[QualitativeScoutingEntries.matchKey],
            ScoutingAssignments.selectAll().single()[ScoutingAssignments.matchKey],
            MatchPlans.selectAll().single()[MatchPlans.matchKey]
        )
    }

    @Test
    fun legacyManualMatchMergedWithApiMatchMovesAllChildData() {
        insertMatchRow(legacyKey, "{}")
        insertChildRows(legacyKey)
        insertMatchRow(canonicalKey, """{"score":1,"scoreRedFinal":50,"scoreBlueFinal":40}""")

        MatchCanonical.deduplicateDatabaseForEvent(event)

        transaction {
            val rows = ApiMatches.selectAll().toList()
            assertEquals(1, rows.size)
            assertEquals(canonicalKey, rows.single()[ApiMatches.matchKey])
            assertTrue(rows.single()[ApiMatches.dataJson].contains("scoreRedFinal"))
        }
        assertEquals(List(4) { canonicalKey }, childKeys())
    }

    @Test
    fun matchPlanConflictKeepsNewerPlan() {
        insertMatchRow(legacyKey, "{}")
        insertMatchRow(canonicalKey, """{"scoreRedFinal":50}""")
        val older = Instant.now().minusSeconds(600)
        transaction {
            MatchPlans.insert {
                it[ownerKey] = "254"
                it[eventKey] = event
                it[matchKey] = canonicalKey
                it[planJson] = """{"from":"old"}"""
                it[updatedAt] = older
            }
            MatchPlans.insert {
                it[ownerKey] = "254"
                it[eventKey] = event
                it[matchKey] = legacyKey
                it[planJson] = """{"from":"new"}"""
                it[updatedAt] = Instant.now()
            }
        }

        MatchCanonical.deduplicateDatabaseForEvent(event)

        transaction {
            val plan = MatchPlans.selectAll().single()
            assertEquals(canonicalKey, plan[MatchPlans.matchKey])
            assertEquals("""{"from":"new"}""", plan[MatchPlans.planJson])
        }
    }

    @Test
    fun saveMatchUsesCanonicalKeyAndMergesIntoExistingApiMatch() {
        insertMatchRow(canonicalKey, """{"scoreRedFinal":50}""")

        val saved = IntegrationService.saveMatch(
            MatchRecord(matchKey = "", eventKey = event, compLevel = "Qualification", matchNumber = 5, redTeams = red, blueTeams = blue)
        )

        assertEquals(canonicalKey, saved.matchKey)
        assertEquals("qm", saved.compLevel)
        transaction { assertEquals(1, ApiMatches.selectAll().count()) }
    }

    @Test
    fun saveMatchEditRekeysLegacyRowAndChildren() {
        insertMatchRow(legacyKey, "{}")
        insertChildRows(legacyKey)

        val saved = IntegrationService.saveMatch(
            MatchRecord(matchKey = legacyKey, eventKey = event, compLevel = "qm", matchNumber = 5, redTeams = red, blueTeams = blue)
        )

        assertEquals(canonicalKey, saved.matchKey)
        transaction {
            assertEquals(listOf(canonicalKey), ApiMatches.selectAll().map { it[ApiMatches.matchKey] })
        }
        assertEquals(List(4) { canonicalKey }, childKeys())
    }

    @Test
    fun repairReconnectsAlreadyOrphanedRows() {
        insertMatchRow(canonicalKey, """{"scoreRedFinal":50}""")
        insertChildRows(legacyKey)
        // Simulate the old bug: match scouting was remapped, everything else was left behind.
        transaction {
            ScoutingEntries.update({ ScoutingEntries.matchKey eq legacyKey }) { it[matchKey] = canonicalKey }
        }

        val repaired = MatchCanonical.repairOrphanedMatchKeys()

        assertEquals(1, repaired)
        assertEquals(List(4) { canonicalKey }, childKeys())
        assertEquals(0, MatchCanonical.repairOrphanedMatchKeys(), "repair should be idempotent")
    }

    @Test
    fun parseLegacyManualKeyHandlesAllLevels() {
        assertEquals("${event}_qm5", MatchCanonical.parseLegacyManualKey("${event}_qm_s1_m5")?.toMatchKey())
        assertEquals("${event}_practice3", MatchCanonical.parseLegacyManualKey("${event}_practice_s1_m3")?.toMatchKey())
        assertEquals("${event}_sf2m1", MatchCanonical.parseLegacyManualKey("${event}_sf_s2_m1")?.toMatchKey())
        assertEquals("${event}_f1m2", MatchCanonical.parseLegacyManualKey("${event}_f_s1_m2")?.toMatchKey())
        assertEquals("${event}_qm7", MatchCanonical.parseLegacyManualKey("${event}_qualification_s1_m7")?.toMatchKey())
        assertNull(MatchCanonical.parseLegacyManualKey("${event}_qm5"))
        assertNotNull(MatchCanonical.parseLegacyManualKey("${event}_QF_s3_m1"))
    }
}
