package com.obsidianscout.scouting

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.*
import com.obsidianscout.routes.BannerCreateRequest
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ProgramAndAllianceIsolationVerificationTest {

    private val testDbFile = File("build/test_isolation_verify_${System.currentTimeMillis()}.db")

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
                ScoutingAlliances, AllianceMemberships, Banners, AnalyticsReports, AllianceSelections
            )
        }
        com.obsidianscout.integrations.SettingsService.ensureDefaultSettings()
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testBannerIsolationBetweenProgramsAndTeams() {
        // Create FRC team 100 banner
        val bannerFrc100 = BannerService.create(
            BannerCreateRequest(
                teamNumber = 100,
                program = "FRC",
                message = "FRC Team 100 announcement",
                bannerType = "info"
            ),
            defaultProgram = "FRC"
        )

        // Create FTC team 100 banner (same team number, different program)
        val bannerFtc100 = BannerService.create(
            BannerCreateRequest(
                teamNumber = 100,
                program = "FTC",
                message = "FTC Team 100 announcement",
                bannerType = "info"
            ),
            defaultProgram = "FTC"
        )

        // Create FRC global banner
        val bannerFrcGlobal = BannerService.create(
            BannerCreateRequest(
                teamNumber = 0,
                program = "FRC",
                message = "FRC Global Banner",
                bannerType = "warning"
            ),
            defaultProgram = "FRC"
        )

        // 1. FRC Team 100 queries active banners
        val frcActive = BannerService.getActive(100, "FRC")
        val frcMessages = frcActive.map { it.message }
        assertTrue(frcMessages.contains("FRC Team 100 announcement"))
        assertTrue(frcMessages.contains("FRC Global Banner"))
        assertFalse(frcMessages.contains("FTC Team 100 announcement"), "FRC must never see FTC banners even with same team number")

        // 2. FTC Team 100 queries active banners
        val ftcActive = BannerService.getActive(100, "FTC")
        val ftcMessages = ftcActive.map { it.message }
        assertTrue(ftcMessages.contains("FTC Team 100 announcement"))
        assertFalse(ftcMessages.contains("FRC Team 100 announcement"), "FTC must never see FRC team banners")
        assertFalse(ftcMessages.contains("FRC Global Banner"), "FTC must never see FRC global banners")

        // 3. FTC Team 200 queries active banners
        val ftcTeam200Active = BannerService.getActive(200, "FTC")
        assertTrue(ftcTeam200Active.isEmpty(), "FTC Team 200 should have no active banners")
    }

    @Test
    fun testAllianceIsolationBetweenProgramsForSameTeamNumber() {
        val frcSessionAdmin = UserSession("admin-frc", "adminFrc", 5000, "FRC", UserRole.ADMIN)
        val ftcSessionAdmin = UserSession("admin-ftc", "adminFtc", 5000, "FTC", UserRole.ADMIN)

        // Create an alliance in FRC for team 5000
        val frcAlliance = AllianceService.createAlliance(frcSessionAdmin, "FRC Alliance 5000", null, null)
        assertNotNull(frcAlliance)

        // Team 5000 in FTC should NOT be considered part of an active alliance in FTC
        val ftcActiveAllianceId = AllianceService.getActiveAllianceId(5000, "FTC")
        assertNull(ftcActiveAllianceId, "Team 5000 should not have an active alliance in FTC")

        val frcActiveAllianceId = AllianceService.getActiveAllianceId(5000, "FRC")
        assertEquals(UUID.fromString(frcAlliance.id), frcActiveAllianceId, "Team 5000 must have its active alliance in FRC")

        // Create an alliance in FTC for team 5000 as well
        val ftcAlliance = AllianceService.createAlliance(ftcSessionAdmin, "FTC Alliance 5000", null, null)
        assertNotNull(ftcAlliance)

        // Now both programs have separate active alliances for team 5000
        assertEquals(UUID.fromString(frcAlliance.id), AllianceService.getActiveAllianceId(5000, "FRC"))
        assertEquals(UUID.fromString(ftcAlliance.id), AllianceService.getActiveAllianceId(5000, "FTC"))
        assertNotEquals(frcAlliance.id, ftcAlliance.id)
    }

    @Test
    fun testScoutingEntriesIsolationAcrossTeamsAndPrograms() {
        val now = Instant.now()
        val dummyUserId = UUID.randomUUID()

        transaction {
            Users.insert {
                it[id] = dummyUserId
                it[username] = "scout1"
                it[passwordHash] = "hash"
                it[teamNumber] = 2000
                it[role] = "SCOUT"
                it[program] = "FRC"
                it[createdAt] = now
            }

            // Insert FRC entry for Team 2000
            ScoutingEntries.insert {
                it[id] = UUID.randomUUID()
                it[ownerTeamNumber] = 2000
                it[program] = "FRC"
                it[eventKey] = "2026txcmp"
                it[matchKey] = "2026txcmp_qm1"
                it[matchNumber] = 1
                it[targetTeamNumber] = 254
                it[submittedByUserId] = dummyUserId
                it[dataJson] = """{"autoPoints": 15}"""
                it[createdAt] = now
            }

            // Insert FTC entry for Team 2000 (same team number, different program)
            ScoutingEntries.insert {
                it[id] = UUID.randomUUID()
                it[ownerTeamNumber] = 2000
                it[program] = "FTC"
                it[eventKey] = "2026ftccmp"
                it[matchKey] = "2026ftccmp_qm1"
                it[matchNumber] = 1
                it[targetTeamNumber] = 9999
                it[submittedByUserId] = dummyUserId
                it[dataJson] = """{"autoPoints": 40}"""
                it[createdAt] = now
            }
        }

        val frcSession = UserSession(dummyUserId.toString(), "scout1", 2000, "FRC", UserRole.SCOUT)
        val ftcSession = UserSession(dummyUserId.toString(), "scout1", 2000, "FTC", UserRole.SCOUT)

        // FRC listEntries should only return FRC entry
        val frcEntries = ScoutingService.listEntries(frcSession, includePrescout = true, all = true)
        assertEquals(1, frcEntries.size)
        assertEquals(254, frcEntries[0].targetTeamNumber)

        // FTC listEntries should only return FTC entry
        val ftcEntries = ScoutingService.listEntries(ftcSession, includePrescout = true, all = true)
        assertEquals(1, ftcEntries.size)
        assertEquals(9999, ftcEntries[0].targetTeamNumber)
    }

    @Test
    fun testTeamWipeDoesNotDeleteOtherProgramOrTeamData() {
        val now = Instant.now()
        val dummyUserId = UUID.randomUUID()

        transaction {
            Users.insert {
                it[id] = dummyUserId
                it[username] = "scoutWipe"
                it[passwordHash] = "hash"
                it[teamNumber] = 3000
                it[role] = "ADMIN"
                it[program] = "FRC"
                it[createdAt] = now
            }

            // Insert FRC entry for Team 3000
            ScoutingEntries.insert {
                it[id] = UUID.randomUUID()
                it[ownerTeamNumber] = 3000
                it[program] = "FRC"
                it[eventKey] = "2026txcmp"
                it[matchKey] = "2026txcmp_qm1"
                it[matchNumber] = 1
                it[targetTeamNumber] = 118
                it[submittedByUserId] = dummyUserId
                it[dataJson] = "{}"
                it[createdAt] = now
            }

            // Insert FTC entry for Team 3000 (must NOT be deleted when wiping FRC)
            ScoutingEntries.insert {
                it[id] = UUID.randomUUID()
                it[ownerTeamNumber] = 3000
                it[program] = "FTC"
                it[eventKey] = "2026ftc"
                it[matchKey] = "2026ftc_qm1"
                it[matchNumber] = 1
                it[targetTeamNumber] = 7777
                it[submittedByUserId] = dummyUserId
                it[dataJson] = "{}"
                it[createdAt] = now
            }

            // Insert FRC entry for Team 4000 (must NOT be deleted when wiping Team 3000)
            ScoutingEntries.insert {
                it[id] = UUID.randomUUID()
                it[ownerTeamNumber] = 4000
                it[program] = "FRC"
                it[eventKey] = "2026txcmp"
                it[matchKey] = "2026txcmp_qm1"
                it[matchNumber] = 1
                it[targetTeamNumber] = 148
                it[submittedByUserId] = dummyUserId
                it[dataJson] = "{}"
                it[createdAt] = now
            }

            // Global API event cache (must NOT be wiped by team wipe)
            ApiEvents.insert {
                it[eventKey] = "2026txcmp"
                it[name] = "Texas State Championship"
                it[year] = 2026
                it[eventCode] = "txcmp"
                it[dataJson] = "{}"
                it[updatedAt] = now
            }
        }

        // Perform the exact scoped wipe query from Routes.kt:
        val session = UserSession(dummyUserId.toString(), "scoutWipe", 3000, "FRC", UserRole.ADMIN)
        transaction {
            com.obsidianscout.db.ScoutingEntries.deleteWhere {
                (com.obsidianscout.db.ScoutingEntries.ownerTeamNumber eq session.teamNumber) and
                (com.obsidianscout.db.ScoutingEntries.program eq session.program)
            }
        }

        // Verify results
        transaction {
            val allEntries = ScoutingEntries.selectAll().toList()
            assertEquals(2, allEntries.size, "Should still have 2 entries remaining")

            val ftcEntry = allEntries.firstOrNull { it[ScoutingEntries.program] == "FTC" }
            assertNotNull(ftcEntry, "FTC entry for team 3000 must be preserved")
            assertEquals(3000, ftcEntry[ScoutingEntries.ownerTeamNumber])

            val otherTeamEntry = allEntries.firstOrNull { it[ScoutingEntries.ownerTeamNumber] == 4000 }
            assertNotNull(otherTeamEntry, "Team 4000 FRC entry must be preserved")

            val apiEventCount = ApiEvents.selectAll().count()
            assertEquals(1L, apiEventCount, "Global API events cache must remain intact")
        }
    }
}
