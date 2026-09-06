package com.obsidianscout.integrations

import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.AllianceMemberships
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

class SettingsTeamIsolationTest {

    private val testDbFile = File("build/test_settings_isolation_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, AppSettings, ApiEvents, ApiTeams, ApiMatches,
                ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries,
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
    fun testNewTeamDoesNotInheritTeamZeroEventCode() {
        // Set event code on global team 0 settings
        val teamZeroSettings = SettingsService.getSettings(0, "FRC")
        SettingsService.updateSettings(0, teamZeroSettings.copy(eventCode = "2025txis"))
        assertEquals("2025txis", SettingsService.getSettings(0, "FRC").eventCode)

        // 1. Fetch settings for an unregistered team (no custom AppSettings row)
        val unregisteredTeamSettings = SettingsService.getSettings(8888, "FRC")
        assertEquals("", unregisteredTeamSettings.eventCode, "Unregistered team must not inherit team 0 eventCode")
        assertEquals("", unregisteredTeamSettings.eventKey, "Unregistered team must not inherit team 0 eventKey")

        // 2. Register a user on a brand new team
        AuthService.register(
            username = "admin8888",
            teamNumber = 8888,
            password = "Password123!",
            program = "FRC",
            role = UserRole.ADMIN
        )

        // Registered team settings must have blank eventCode/eventKey by default
        val registeredTeamSettings = SettingsService.getSettings(8888, "FRC")
        assertEquals("", registeredTeamSettings.eventCode, "Registered team must have blank eventCode by default")
        assertEquals("", registeredTeamSettings.eventKey, "Registered team must have blank eventKey by default")

        // 3. Update team 8888 settings with its own event code
        SettingsService.updateSettings(8888, registeredTeamSettings.copy(eventCode = "2026txho"))
        assertEquals("2026txho", SettingsService.getSettings(8888, "FRC").eventCode)

        // Team 0 and other new teams must remain unaffected
        assertEquals("2025txis", SettingsService.getSettings(0, "FRC").eventCode)
        assertEquals("", SettingsService.getSettings(9999, "FRC").eventCode)
    }

    @Test
    fun testListEventsIsScopedPerTeam() {
        // Populate global events in ApiEvents (simulating FTC Scout event sync or TBA sync)
        transaction {
            val now = Instant.now()
            listOf("2026eventA", "2026eventB", "2026eventC").forEach { key ->
                ApiEvents.insert {
                    it[eventKey] = key
                    it[name] = "Event $key"
                    it[year] = 2026
                    it[eventCode] = key.removePrefix("2026")
                    it[dataJson] = "{}"
                    it[updatedAt] = now
                }
            }
        }

        val sessionTeam1 = UserSession("u1", "user1", 1001, "FTC", UserRole.SCOUT)
        val sessionTeam2 = UserSession("u2", "user2", 1002, "FTC", UserRole.SCOUT)
        val superAdminSession = UserSession("sa", "admin", 0, "FTC", UserRole.SUPERADMIN)

        // Brand new team 1001 has no events yet
        val eventsTeam1Initial = IntegrationService.listEvents(year = 2026, session = sessionTeam1)
        assertTrue(eventsTeam1Initial.isEmpty(), "New team 1001 must see zero events from other teams")

        // Set active event for team 1001
        val settingsTeam1 = SettingsService.getSettings(1001, "FTC")
        SettingsService.updateSettings(1001, settingsTeam1.copy(eventCode = "eventA", eventKey = "2026eventA"))

        val eventsTeam1 = IntegrationService.listEvents(year = 2026, activeKey = "2026eventa", session = sessionTeam1)
        assertEquals(1, eventsTeam1.size)
        assertEquals("2026eventa", eventsTeam1.first().eventKey.lowercase())

        // Team 1002 still sees zero events
        val eventsTeam2 = IntegrationService.listEvents(year = 2026, session = sessionTeam2)
        assertTrue(eventsTeam2.isEmpty(), "New team 1002 must see zero events from team 1001")

        // Superadmin sees all global events
        val eventsSuperAdmin = IntegrationService.listEvents(year = 2026, session = superAdminSession)
        assertEquals(3, eventsSuperAdmin.size, "Superadmin sees all 3 events")
    }

    @Test
    fun testSummaryIsScopedPerScoutingTeam() {
        val now = Instant.now()
        val dummyUserId = transaction {
            Users.insert {
                it[id] = java.util.UUID.randomUUID()
                it[username] = "dummyUser"
                it[passwordHash] = "hash"
                it[teamNumber] = 1001
                it[role] = "SCOUT"
                it[program] = "FRC"
                it[createdAt] = now
            }[Users.id]
        }
        transaction {
            // Insert scouting data for Team 1001
            ScoutingEntries.insert {
                it[id] = java.util.UUID.randomUUID()
                it[ownerTeamNumber] = 1001
                it[program] = "FRC"
                it[eventKey] = "2026txho"
                it[matchNumber] = 1
                it[targetTeamNumber] = 254
                it[submittedByUserId] = dummyUserId
                it[dataJson] = "{}"
                it[createdAt] = now
            }
            PitScoutingEntries.insert {
                it[id] = java.util.UUID.randomUUID()
                it[ownerTeamNumber] = 1001
                it[program] = "FRC"
                it[eventKey] = "2026txho"
                it[targetTeamNumber] = 254
                it[submittedByUserId] = dummyUserId
                it[dataJson] = "{}"
                it[createdAt] = now
            }
            QualitativeScoutingEntries.insert {
                it[id] = java.util.UUID.randomUUID()
                it[ownerTeamNumber] = 1001
                it[program] = "FRC"
                it[eventKey] = "2026txho"
                it[targetTeamNumber] = 254
                it[submittedByUserId] = dummyUserId
                it[dataJson] = "{}"
                it[createdAt] = now
            }

            // Insert scouting data for Team 1002
            ScoutingEntries.insert {
                it[id] = java.util.UUID.randomUUID()
                it[ownerTeamNumber] = 1002
                it[program] = "FRC"
                it[eventKey] = "2026txda"
                it[matchNumber] = 2
                it[targetTeamNumber] = 1678
                it[submittedByUserId] = dummyUserId
                it[dataJson] = "{}"
                it[createdAt] = now
            }
        }

        val sessionTeam1 = UserSession("u1", "user1", 1001, "FRC", UserRole.SCOUT)
        val sessionTeam2 = UserSession("u2", "user2", 1002, "FRC", UserRole.SCOUT)
        val superAdmin = UserSession("sa", "admin", 0, "FRC", UserRole.SUPERADMIN)

        // 1. Team 1001 summary: only sees its 1 match entry, 1 pit entry, 1 qual entry
        val summary1 = IntegrationService.summary(session = sessionTeam1)
        assertEquals(1001, summary1.scoutingTeamNumber)
        assertEquals(1, summary1.entries)
        assertEquals(1, summary1.pitEntries)
        assertEquals(1, summary1.qualEntries)

        // 2. Team 1002 summary: only sees its 1 match entry, 0 pit entries, 0 qual entries
        val summary2 = IntegrationService.summary(session = sessionTeam2)
        assertEquals(1002, summary2.scoutingTeamNumber)
        assertEquals(1, summary2.entries)
        assertEquals(0, summary2.pitEntries)
        assertEquals(0, summary2.qualEntries)

        // 3. Team 1002 attempts to spoof targetTeamNumber=1001 -> must be ignored and fallback to team 1002
        val spoofAttempt = IntegrationService.summary(session = sessionTeam2, targetTeamNumber = 1001)
        assertEquals(1002, spoofAttempt.scoutingTeamNumber)
        assertEquals(1, spoofAttempt.entries)
        assertEquals(0, spoofAttempt.pitEntries)

        // 4. SuperAdmin can view global site counts
        val globalSummary = IntegrationService.summary(session = superAdmin, targetTeamNumber = 0)
        assertEquals(null, globalSummary.scoutingTeamNumber)
        assertEquals(2, globalSummary.entries)
        assertEquals(1, globalSummary.pitEntries)
        assertEquals(1, globalSummary.qualEntries)
        assertTrue(globalSummary.availableTeams.containsAll(listOf(1001, 1002)))

        // 5. SuperAdmin can view filtered counts for a specific team
        val superAdminFiltered = IntegrationService.summary(session = superAdmin, targetTeamNumber = 1001)
        assertEquals(1001, superAdminFiltered.scoutingTeamNumber)
        assertEquals(1, superAdminFiltered.entries)
        assertEquals(1, superAdminFiltered.pitEntries)
        assertEquals(1, superAdminFiltered.qualEntries)
    }

    @Test
    fun testDismissSetupWizard() {
        val initialSettings = SettingsService.getSettings(7777, "FRC")
        assertEquals(false, initialSettings.setupWizardCompleted)

        val updated = SettingsService.dismissSetupWizard(7777, "FRC")
        assertEquals(true, updated.setupWizardCompleted)

        val fetched = SettingsService.getSettings(7777, "FRC")
        assertEquals(true, fetched.setupWizardCompleted)

        val effective = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(7777, "FRC")
        assertEquals(true, effective.setupWizardCompleted)
    }
}
