package com.obsidianscout.analytics

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.ScoutingField
import com.obsidianscout.db.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ValidationServiceTest {

    private val testDbFile = File("build/test_validation_${System.currentTimeMillis()}.db")
    private val session = UserSession(
        userId = UUID.randomUUID().toString(),
        username = "lead_analyst",
        teamNumber = 254,
        program = "FRC",
        role = UserRole.ADMIN
    )

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users,
                AppSettings,
                ApiEvents,
                ApiTeams,
                ApiMatches,
                ScoutingAlliances,
                AllianceMemberships,
                ScoutingConfigs,
                ScoutingEntries
            )
        }
        ConfigService.ensureDefaultConfig()
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testMatchValidationWithScoreComparisonAndMissingTeams() = runBlocking {
        val eventKey = "2026test"
        var userId = UUID.randomUUID()

        transaction {
            userId = Users.insertAndGetId {
                it[username] = "lead_analyst"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[passwordHash] = "hash"
                it[role] = "ADMIN"
                it[createdAt] = Instant.now()
            }.value

            // Insert AppSettings
            val settingsJson = """{"program":"FRC","year":2026,"eventKey":"$eventKey","useStatboticsEpa":true,"useTbaOpr":true}"""
            AppSettings.insert {
                it[teamNumber] = 254
                it[program] = "FRC"
                it[AppSettings.settingsJson] = settingsJson
                it[updatedAt] = Instant.now()
            }

            // Insert Teams
            ApiTeams.insert {
                it[ApiTeams.eventKey] = eventKey
                it[teamKey] = "frc254"
                it[teamNumber] = 254
                it[name] = "The Cheesy Poofs"
                it[epa] = 45.0
                it[opr] = 44.0
                it[dataJson] = "{}"
                it[updatedAt] = Instant.now()
            }
            ApiTeams.insert {
                it[ApiTeams.eventKey] = eventKey
                it[teamKey] = "frc1678"
                it[teamNumber] = 1678
                it[name] = "Citrus Circuits"
                it[epa] = 42.0
                it[opr] = 41.0
                it[dataJson] = "{}"
                it[updatedAt] = Instant.now()
            }
            ApiTeams.insert {
                it[ApiTeams.eventKey] = eventKey
                it[teamKey] = "frc971"
                it[teamNumber] = 971
                it[name] = "Spartan Robotics"
                it[epa] = 38.0
                it[opr] = 37.0
                it[dataJson] = "{}"
                it[updatedAt] = Instant.now()
            }
            ApiTeams.insert {
                it[ApiTeams.eventKey] = eventKey
                it[teamKey] = "frc1323"
                it[teamNumber] = 1323
                it[name] = "MadTown Robotics"
                it[epa] = 40.0
                it[opr] = 39.0
                it[dataJson] = "{}"
                it[updatedAt] = Instant.now()
            }
            ApiTeams.insert {
                it[ApiTeams.eventKey] = eventKey
                it[teamKey] = "frc4414"
                it[teamNumber] = 4414
                it[name] = "HighTide"
                it[epa] = 41.0
                it[opr] = 40.0
                it[dataJson] = "{}"
                it[updatedAt] = Instant.now()
            }
            ApiTeams.insert {
                it[ApiTeams.eventKey] = eventKey
                it[teamKey] = "frc2910"
                it[teamNumber] = 2910
                it[name] = "Jack in the Bot"
                it[epa] = 39.0
                it[opr] = 38.0
                it[dataJson] = "{}"
                it[updatedAt] = Instant.now()
            }

            // Insert custom config with known points: autoPoints (5 pts each) and teleopPoints (2 pts each)
            val customConfig = ScoutingConfig(
                version = 1,
                title = "Test Scoring",
                fields = listOf(
                    ScoutingField(id = "autoNotes", label = "Auto Notes", type = "counter", pointsPer = 5.0),
                    ScoutingField(id = "teleNotes", label = "Teleop Notes", type = "counter", pointsPer = 2.0)
                )
            )
            ScoutingConfigs.insert {
                it[teamNumber] = 254
                it[program] = "FRC"
                it[configJson] = Json.encodeToString(customConfig)
                it[updatedAt] = Instant.now()
            }

            // Match 1: Played. Red alliance has 3 teams (254, 1678, 971), Blue alliance has (1323, 4414, 2910)
            // Official TBA Score: Red = 110, Blue = 85
            val match1Data = """{"alliances":{"red":{"score":110,"team_keys":["frc254","frc1678","frc971"]},"blue":{"score":85,"team_keys":["frc1323","frc4414","frc2910"]}}}"""
            ApiMatches.insert {
                it[matchKey] = "${eventKey}_qm1"
                it[ApiMatches.eventKey] = eventKey
                it[compLevel] = "qm"
                it[matchNumber] = 1
                it[redTeams] = """["frc254","frc1678","frc971"]"""
                it[blueTeams] = """["frc1323","frc4414","frc2910"]"""
                it[dataJson] = match1Data
                it[updatedAt] = Instant.now()
            }

            // Match 2: Missing teams. Only 254 scouted on Red, Blue unscouted.
            ApiMatches.insert {
                it[matchKey] = "${eventKey}_qm2"
                it[ApiMatches.eventKey] = eventKey
                it[compLevel] = "qm"
                it[matchNumber] = 2
                it[redTeams] = """["frc254","frc1678","frc971"]"""
                it[blueTeams] = """["frc1323","frc4414","frc2910"]"""
                it[dataJson] = """{"alliances":{"red":{"score":95,"team_keys":["frc254","frc1678","frc971"]},"blue":{"score":90,"team_keys":["frc1323","frc4414","frc2910"]}}}"""
                it[updatedAt] = Instant.now()
            }

            // Insert Scouting Entries for Match 1:
            // 254: 6 auto (30) + 10 teleop (20) = 50 pts
            // 1678: 4 auto (20) + 10 teleop (20) = 40 pts
            // 971: 2 auto (10) + 5 teleop (10) = 20 pts
            // Red sum = 50 + 40 + 20 = 110 pts (Diff = 0 vs official 110)
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 254
                it[ScoutingEntries.eventKey] = eventKey
                it[matchKey] = "${eventKey}_qm1"
                it[matchNumber] = 1
                it[dataJson] = buildJsonObject {
                    put("autoNotes", 6)
                    put("teleNotes", 10)
                }.toString()
                it[submittedByUserId] = userId
                it[createdAt] = Instant.now()
            }
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 1678
                it[ScoutingEntries.eventKey] = eventKey
                it[matchKey] = "${eventKey}_qm1"
                it[matchNumber] = 1
                it[dataJson] = buildJsonObject {
                    put("autoNotes", 4)
                    put("teleNotes", 10)
                }.toString()
                it[submittedByUserId] = userId
                it[createdAt] = Instant.now()
            }
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 971
                it[ScoutingEntries.eventKey] = eventKey
                it[matchKey] = "${eventKey}_qm1"
                it[matchNumber] = 1
                it[dataJson] = buildJsonObject {
                    put("autoNotes", 2)
                    put("teleNotes", 5)
                }.toString()
                it[submittedByUserId] = userId
                it[createdAt] = Instant.now()
            }

            // Blue Alliance Match 1:
            // 1323: 3 auto (15) + 5 teleop (10) = 25 pts
            // 4414: 2 auto (10) + 10 teleop (20) = 30 pts
            // 2910: 2 auto (10) + 5 teleop (10) = 20 pts
            // Blue sum = 25 + 30 + 20 = 75 pts (Official = 85, Diff = -10 pts)
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 1323
                it[ScoutingEntries.eventKey] = eventKey
                it[matchKey] = "${eventKey}_qm1"
                it[matchNumber] = 1
                it[dataJson] = buildJsonObject {
                    put("autoNotes", 3)
                    put("teleNotes", 5)
                }.toString()
                it[submittedByUserId] = userId
                it[createdAt] = Instant.now()
            }
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 4414
                it[ScoutingEntries.eventKey] = eventKey
                it[matchKey] = "${eventKey}_qm1"
                it[matchNumber] = 1
                it[dataJson] = buildJsonObject {
                    put("autoNotes", 2)
                    put("teleNotes", 10)
                }.toString()
                it[submittedByUserId] = userId
                it[createdAt] = Instant.now()
            }
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 2910
                it[ScoutingEntries.eventKey] = eventKey
                it[matchKey] = "${eventKey}_qm1"
                it[matchNumber] = 1
                it[dataJson] = buildJsonObject {
                    put("autoNotes", 2)
                    put("teleNotes", 5)
                }.toString()
                it[submittedByUserId] = userId
                it[createdAt] = Instant.now()
            }

            // Insert 1 entry for Match 2 on Red (Team 254 only)
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 254
                it[ScoutingEntries.eventKey] = eventKey
                it[matchKey] = "${eventKey}_qm2"
                it[matchNumber] = 2
                it[dataJson] = buildJsonObject {
                    put("autoNotes", 5)
                    put("teleNotes", 10)
                }.toString()
                it[submittedByUserId] = userId
                it[createdAt] = Instant.now()
            }
        }

        val result = ValidationService.validateEvent(
            session = session,
            eventKeyParam = eventKey,
            anomalyThreshold = 15.0
        )

        assertEquals(2, result.totalMatches)
        assertEquals(1, result.fullyScoutedMatches)
        assertEquals(1, result.incompleteMatches)

        // Verify Match 1
        val match1 = result.matches.first { it.matchNumber == 1 }
        assertTrue(match1.isFullyScouted)
        assertEquals(110.0, match1.redAlliance.actualScore)
        assertEquals(110.0, match1.redAlliance.scoutedScoreSum)
        assertEquals(0.0, match1.redAlliance.scoreDiff)
        assertTrue(match1.redAlliance.missingTeams.isEmpty())

        assertEquals(85.0, match1.blueAlliance.actualScore)
        assertEquals(75.0, match1.blueAlliance.scoutedScoreSum)
        assertEquals(-10.0, match1.blueAlliance.scoreDiff)
        assertTrue(match1.blueAlliance.missingTeams.isEmpty())

        // Verify Match 2 warning and missing teams
        val match2 = result.matches.first { it.matchNumber == 2 }
        assertFalse(match2.isFullyScouted)
        assertFalse(match2.redAlliance.isFullyScouted)
        assertEquals(listOf(1678, 971), match2.redAlliance.missingTeams)
        assertTrue(match2.redAlliance.warning?.contains("Missing scouting for team(s): 1678, 971") == true)
        assertEquals(listOf(1323, 4414, 2910), match2.blueAlliance.missingTeams)

        // Verify Team EPA / OPR comparison
        val team254 = result.teams.first { it.teamNumber == 254 }
        assertEquals(2, team254.scoutedMatchCount)
        // match 1 = 50 pts, match 2 = 45 pts -> avg = 47.5 pts
        assertEquals(47.5, team254.averageScoutedScore)
        assertEquals(45.0, team254.epa)
        assertEquals(2.5, team254.epaDiff)
        assertFalse(team254.isAnomaly) // diff is 2.5, less than 15.0 threshold
    }
}
