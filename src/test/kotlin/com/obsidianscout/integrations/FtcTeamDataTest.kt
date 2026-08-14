package com.obsidianscout.integrations

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.DefaultConfigs
import com.obsidianscout.db.PitScoutingConfigs
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingConfigs
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingConfigs
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FtcTeamDataTest {

    private val testDbFile = File("build/test_ftc_team_data_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, AppSettings, ScoutingConfigs, PitScoutingConfigs, QualitativeScoutingConfigs, DefaultConfigs,
                ApiEvents, ApiTeams, ApiMatches, ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries,
                ScoutingAlliances, AllianceMemberships
            )
        }
        SettingsService.ensureDefaultSettings()
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testFtcTeamNumbersNotShiftedByBBotMapping() {
        val eventKey = "2025usmiocm2"
        val now = Instant.now()

        // Insert FTC teams with numbers >= 9900
        val ftcTeams = listOf(
            7689 to "Clarkston Chicken Bots",
            8487 to "Robo Toasters Orange",
            11717 to "Circuit Breakers",
            22415 to "Robo Toasters Blue",
            23373 to "Viking Warriors",
            23590 to "Wildfire",
            23833 to "Circuit Makers",
            23834 to "Pine Knob Pilots",
            30451 to "Apex Warriors"
        )

        transaction {
            ftcTeams.forEach { (num, name) ->
                ApiTeams.insert {
                    it[ApiTeams.eventKey] = eventKey
                    it[ApiTeams.teamKey] = "ftc$num"
                    it[ApiTeams.teamNumber] = num
                    it[ApiTeams.name] = name
                    it[ApiTeams.nickname] = name
                    it[ApiTeams.city] = "Troy"
                    it[ApiTeams.state] = "MI"
                    it[ApiTeams.country] = "USA"
                    it[ApiTeams.dataJson] = "{}"
                    it[ApiTeams.updatedAt] = now
                }
            }
        }

        // 1. Verify getBBotMappings returns emptyList for FTC events
        val mappings = IntegrationService.getBBotMappings(eventKey)
        assertTrue(mappings.isEmpty(), "BBot mappings must be empty for FTC events")

        // 2. Verify listTeams preserves correct teamNumbers and teamKeys without shifting/merging
        val session = UserSession("u1", "user1", 7689, "FTC", UserRole.SCOUT)
        val teamRecords = IntegrationService.listTeams(eventKey, session)

        assertEquals(ftcTeams.size, teamRecords.size)
        teamRecords.forEachIndexed { index, record ->
            val expectedNum = ftcTeams[index].first
            val expectedName = ftcTeams[index].second
            assertEquals(expectedNum, record.teamNumber, "Team number must match exact FTC team number")
            assertEquals("ftc$expectedNum", record.teamKey, "Team key must not be remapped to B-bot format")
            assertEquals(expectedName, record.nickname, "Team nickname must match FTC Scout nickname")
        }
    }
}
