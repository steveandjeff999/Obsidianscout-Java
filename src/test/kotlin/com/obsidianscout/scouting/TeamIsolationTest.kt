package com.obsidianscout.scouting

import com.obsidianscout.analytics.AnalyticsReportService
import com.obsidianscout.analytics.CreateReportRequest
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.db.*
import com.obsidianscout.integrations.TeamCacheOverrideService
import com.obsidianscout.routes.EventRecord
import com.obsidianscout.routes.ScoutingEntryRequest
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class TeamIsolationTest {

    private val testDbFile = File("build/test_team_isolation_${System.currentTimeMillis()}.db")

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
                ScoutingConfigs,
                PitScoutingConfigs,
                QualitativeScoutingConfigs,
                ConfigRevisions,
                DefaultConfigs,
                DeletedDefaultConfigs,
                ScoutingEntries,
                PitScoutingEntries,
                QualitativeScoutingEntries,
                EntryTombstones,
                AppSettings,
                ApiEvents,
                ApiTeams,
                ApiMatches,
                TeamCacheOverrides,
                ScoutingAlliances,
                AllianceMemberships,
                AllianceSelections,
                MatchPlans,
                MatchChecklists,
                BatteryTrackers,
                MatchPredictions,
                Banners,
                EventInfoPosts,
                EventInfoFiles,
                ChatMessages,
                UserChatLastRead,
                ChatGroups,
                AnalyticsReports,
                ScoutingAssignments,
                GamepadProfiles,
                SharedLinks,
                SharedLinkAccessLogs,
                EpaOprHistoryCache,
                PasswordResetTokens,
                PushSubscriptions,
                FcmConfigs,
                FcmDeviceTokens,
                SlackIntegrations,
                SlackNotifications,
                SlackUserPreferences,
                ClusterSecrets,
                ClusterNotificationLocks,
                SyncStatuses,
                UserSessions,
                WidgetTokens,
                ReportedErrors,
                PasskeyCredentials,
                PasskeyChallenges
            )
        }
        AllianceService.clearEffectiveSettingsCache()
    }

    @AfterTest
    fun tearDown() {
        AllianceService.clearEffectiveSettingsCache()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    private fun createUser(username: String, team: Int, program: String = "FRC", role: UserRole = UserRole.ADMIN): UserSession {
        val id = transaction {
            Users.insertAndGetId {
                it[Users.username] = username
                it[Users.passwordHash] = "hash"
                it[Users.teamNumber] = team
                it[Users.program] = program
                it[Users.role] = role.name
                it[Users.createdAt] = Instant.now()
            }
        }
        return UserSession(id.value.toString(), username, team, program, role, username)
    }

    private fun parseJsonObj(raw: String) = JsonSupport.json.parseToJsonElement(raw).jsonObject

    @Test
    fun testAllianceImportRejectsNonMemberAndUnacceptedTeams() {
        val team100Admin = createUser("admin100", 100, "FRC", UserRole.ADMIN)
        val team200Admin = createUser("admin200", 200, "FRC", UserRole.ADMIN)
        val team300Admin = createUser("admin300", 300, "FRC", UserRole.ADMIN)

        // Seed scouting entry on Team 200 (invited only) and Team 300 (unrelated)
        transaction {
            ScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = 200
                it[program] = "FRC"
                it[targetTeamNumber] = 254
                it[eventKey] = "2026txhou"
                it[matchKey] = "2026txhou_qm1"
                it[matchNumber] = 1
                it[dataJson] = """{"score":10}"""
                it[submittedByUserId] = EntityID(UUID.fromString(team200Admin.userId), Users)
                it[createdAt] = Instant.now()
            }
            ScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = 300
                it[program] = "FRC"
                it[targetTeamNumber] = 254
                it[eventKey] = "2026txhou"
                it[matchKey] = "2026txhou_qm2"
                it[matchNumber] = 2
                it[dataJson] = """{"score":99}"""
                it[submittedByUserId] = EntityID(UUID.fromString(team300Admin.userId), Users)
                it[createdAt] = Instant.now()
            }
        }

        val alliance = AllianceService.createAlliance(
            session = team100Admin,
            name = "Test Alliance",
            eventKey = "2026txhou",
            notes = null
        )
        AllianceService.inviteTeam(team100Admin, alliance.id, 200)

        // 1. Importing from unrelated Team 300 must fail with 403 Forbidden
        val exUnrelated = assertFailsWith<ApiException> {
            AllianceService.importAllianceData(
                session = team100Admin,
                allianceId = alliance.id,
                sourceTeamNumber = 300,
                eventKey = "2026txhou",
                includeMatchScouting = true,
                includePitScouting = false,
                includeQualitativeScouting = false
            )
        }
        assertEquals(HttpStatusCode.Forbidden, exUnrelated.status)

        // 2. Importing from INVITED (not yet accepted) Team 200 must also fail with 403 Forbidden
        val exInvited = assertFailsWith<ApiException> {
            AllianceService.importAllianceData(
                session = team100Admin,
                allianceId = alliance.id,
                sourceTeamNumber = 200,
                eventKey = "2026txhou",
                includeMatchScouting = true,
                includePitScouting = false,
                includeQualitativeScouting = false
            )
        }
        assertEquals(HttpStatusCode.Forbidden, exInvited.status)

        // 3. Once Team 200 accepts in FRC, import succeeds
        AllianceService.respondToInvite(team200Admin, alliance.id, accept = true)
        val result = AllianceService.importAllianceData(
            session = team100Admin,
            allianceId = alliance.id,
            sourceTeamNumber = 200,
            eventKey = "2026txhou",
            includeMatchScouting = true,
            includePitScouting = false,
            includeQualitativeScouting = false
        )
        assertEquals(1, result.importedMatchScouting)
    }

    @Test
    fun testAllianceProgramIsolationBetweenFrcAndFtcSameTeamNumber() {
        val frc100 = createUser("frc100", 100, "FRC", UserRole.ADMIN)
        val frc200 = createUser("frc200", 200, "FRC", UserRole.ADMIN)
        val ftc200 = createUser("ftc200", 200, "FTC", UserRole.ADMIN)

        val alliance = AllianceService.createAlliance(
            session = frc100,
            name = "FRC Alliance",
            eventKey = "2026txhou",
            notes = null
        )
        // Invite Team 200 (exists in both FRC and FTC)
        AllianceService.inviteTeam(frc100, alliance.id, 200)

        // FTC Team 200 must NOT be able to respond to FRC Team 200's invite
        assertFailsWith<ApiException> {
            AllianceService.respondToInvite(ftc200, alliance.id, accept = true)
        }

        // FRC Team 200 can accept
        AllianceService.respondToInvite(frc200, alliance.id, accept = true)
        val updated = AllianceService.getAlliance(frc200, alliance.id)
        assertEquals("ACCEPTED", updated.members.first { it.teamNumber == 200 }.status)

        // FTC Team 200 cannot toggle FRC Team 200's active membership or view the FRC alliance
        assertFailsWith<ApiException> {
            AllianceService.toggleActiveMembership(ftc200, alliance.id, active = false)
        }
        assertFailsWith<ApiException> {
            AllianceService.getAlliance(ftc200, alliance.id)
        }
    }

    @Test
    fun testAnalyticsReportIsolationAcrossTeamsAndUsers() {
        val team100Scout1 = createUser("scout1_100", 100, "FRC", UserRole.SCOUT)
        val team100Scout2 = createUser("scout2_100", 100, "FRC", UserRole.SCOUT)
        val team100Admin = createUser("admin_100", 100, "FRC", UserRole.ADMIN)
        val team200Scout = createUser("scout_200", 200, "FRC", UserRole.SCOUT)
        val team100FtcScout = createUser("scout_100_ftc", 100, "FTC", UserRole.SCOUT)

        val sharedReport = AnalyticsReportService.createReport(
            team100Scout1,
            CreateReportRequest(
                title = "Team 100 Shared Strategy",
                configJson = """{"metrics":["epa"]}""",
                isShared = true
            )
        )

        val privateReport = AnalyticsReportService.createReport(
            team100Scout1,
            CreateReportRequest(
                title = "Scout 1 Private Notes",
                configJson = """{"metrics":["opr"]}""",
                isShared = false
            )
        )

        // Team 200 must NOT see Team 100's shared report in listReports or getReport
        assertTrue(AnalyticsReportService.listReports(team200Scout).none { it.id == sharedReport.id })
        val exCrossTeam = assertFailsWith<ApiException> {
            AnalyticsReportService.getReport(sharedReport.id, team200Scout)
        }
        assertEquals(HttpStatusCode.Forbidden, exCrossTeam.status)

        // FTC Team 100 must NOT see FRC Team 100's shared report
        assertTrue(AnalyticsReportService.listReports(team100FtcScout).none { it.id == sharedReport.id })
        assertFailsWith<ApiException> {
            AnalyticsReportService.getReport(sharedReport.id, team100FtcScout)
        }

        // Team 100 Scout 2 CAN see shared report, but CANNOT see Scout 1's private report
        val scout2Reports = AnalyticsReportService.listReports(team100Scout2).map { it.id }
        assertTrue(sharedReport.id in scout2Reports)
        assertFalse(privateReport.id in scout2Reports)
        assertFailsWith<ApiException> {
            AnalyticsReportService.getReport(privateReport.id, team100Scout2)
        }

        // Team 100 Admin CAN see both
        val adminReports = AnalyticsReportService.listReports(team100Admin).map { it.id }
        assertTrue(sharedReport.id in adminReports)
        assertTrue(privateReport.id in adminReports)
        assertEquals(privateReport.id, AnalyticsReportService.getReport(privateReport.id, team100Admin).id)
    }

    @Test
    fun testScoutingAssignmentsDoNotCompleteAcrossUnrelatedTeams() {
        val team100Admin = createUser("admin100", 100, "FRC", UserRole.ADMIN)
        val team100Scout = createUser("scout100", 100, "FRC", UserRole.SCOUT)
        val team200Admin = createUser("admin200", 200, "FRC", UserRole.ADMIN)
        val team200Scout = createUser("scout200", 200, "FRC", UserRole.SCOUT)

        val a100 = ScoutingAssignmentService.createAssignment(
            team100Admin,
            CreateAssignmentRequest(
                eventKey = "2026txhou",
                assignmentType = "MATCH",
                assignedUserId = team100Scout.userId,
                matchKey = "2026txhou_qm1",
                matchNumber = 1,
                targetTeamNumber = 118
            )
        )
        val a200 = ScoutingAssignmentService.createAssignment(
            team200Admin,
            CreateAssignmentRequest(
                eventKey = "2026txhou",
                assignmentType = "MATCH",
                assignedUserId = team200Scout.userId,
                matchKey = "2026txhou_qm1",
                matchNumber = 1,
                targetTeamNumber = 118
            )
        )

        // Team 100 submits match entry and triggers handleScoutingSubmission
        transaction {
            ScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = 100
                it[program] = "FRC"
                it[targetTeamNumber] = 118
                it[eventKey] = "2026txhou"
                it[matchKey] = "2026txhou_qm1"
                it[matchNumber] = 1
                it[dataJson] = """{"auto":1}"""
                it[submittedByUserId] = EntityID(UUID.fromString(team100Scout.userId), Users)
                it[createdAt] = Instant.now()
            }
        }
        ScoutingAssignmentService.handleScoutingSubmission(
            assignmentType = "MATCH",
            eventKey = "2026txhou",
            program = "FRC",
            ownerTeam = 100,
            matchNumber = 1,
            matchKey = "2026txhou_qm1",
            targetTeamNumber = 118
        )

        // Team 100's assignment is COMPLETED, but Team 200's assignment must remain PENDING
        val list100 = ScoutingAssignmentService.getMyAssignments(team100Scout)
        val list200 = ScoutingAssignmentService.getMyAssignments(team200Scout)
        assertEquals("COMPLETED", list100.single { it.id == a100.id }.status)
        assertEquals("PENDING", list200.single { it.id == a200.id }.status)
    }

    @Test
    fun testDiscrepancyCalculationDoesNotLeakOrFlagAcrossUnrelatedTeams() {
        val team100Scout = createUser("scout100", 100, "FRC", UserRole.SCOUT)
        val team200Scout = createUser("scout200", 200, "FRC", UserRole.SCOUT)
        val emptyConfig = ScoutingConfig(title = "Test", fields = emptyList())

        val e100 = ScoutingService.createEntry(
            team100Scout,
            ScoutingEntryRequest(
                data = parseJsonObj("""{"targetTeamNumber":254,"eventKey":"2026txhou","matchKey":"2026txhou_qm5","matchNumber":5,"points":10}""")
            ),
            emptyConfig
        )
        val e200 = ScoutingService.createEntry(
            team200Scout,
            ScoutingEntryRequest(
                data = parseJsonObj("""{"targetTeamNumber":254,"eventKey":"2026txhou","matchKey":"2026txhou_qm5","matchNumber":5,"points":50}""")
            ),
            emptyConfig
        )

        // Because Team 100 and Team 200 are NOT in an alliance, neither entry should be marked as having a discrepancy
        transaction {
            val row100 = ScoutingEntries.selectAll().where { ScoutingEntries.id eq EntityID(UUID.fromString(e100.id), ScoutingEntries) }.single()
            val row200 = ScoutingEntries.selectAll().where { ScoutingEntries.id eq EntityID(UUID.fromString(e200.id), ScoutingEntries) }.single()
            assertFalse(row100[ScoutingEntries.hasDiscrepancy])
            assertEquals("", row100[ScoutingEntries.conflictingTeams])
            assertFalse(row200[ScoutingEntries.hasDiscrepancy])
            assertEquals("", row200[ScoutingEntries.conflictingTeams])
        }

        // Now add a second conflicting entry within Team 100 itself -> Team 100's entries should flag discrepancy
        val e100Second = ScoutingService.createEntry(
            team100Scout,
            ScoutingEntryRequest(
                data = parseJsonObj("""{"targetTeamNumber":254,"eventKey":"2026txhou","matchKey":"2026txhou_qm5","matchNumber":5,"points":25}""")
            ),
            emptyConfig
        )
        transaction {
            val row100a = ScoutingEntries.selectAll().where { ScoutingEntries.id eq EntityID(UUID.fromString(e100.id), ScoutingEntries) }.single()
            val row100b = ScoutingEntries.selectAll().where { ScoutingEntries.id eq EntityID(UUID.fromString(e100Second.id), ScoutingEntries) }.single()
            val row200 = ScoutingEntries.selectAll().where { ScoutingEntries.id eq EntityID(UUID.fromString(e200.id), ScoutingEntries) }.single()
            assertTrue(row100a[ScoutingEntries.hasDiscrepancy])
            assertTrue(row100b[ScoutingEntries.hasDiscrepancy])
            assertFalse(row100a[ScoutingEntries.conflictingTeams].contains("200"))
            assertFalse(row200[ScoutingEntries.hasDiscrepancy])
        }
    }

    @Test
    fun testScoutingEntryUpdateAndDeleteEnforceProgramIsolation() {
        val frc100 = createUser("frc100", 100, "FRC", UserRole.ADMIN)
        val ftc100 = createUser("ftc100", 100, "FTC", UserRole.ADMIN)
        val emptyConfig = ScoutingConfig(title = "Test", fields = emptyList())

        val matchEntry = ScoutingService.createEntry(
            frc100,
            ScoutingEntryRequest(
                data = parseJsonObj("""{"targetTeamNumber":254,"eventKey":"2026txhou","matchKey":"2026txhou_qm1","matchNumber":1,"a":1}""")
            ),
            emptyConfig
        )
        assertFailsWith<ApiException> {
            ScoutingService.updateEntry(
                ftc100,
                matchEntry.id,
                ScoutingEntryRequest(
                    data = parseJsonObj("""{"targetTeamNumber":254,"eventKey":"2026txhou","matchKey":"2026txhou_qm1","matchNumber":1,"a":2}""")
                ),
                emptyConfig
            )
        }
        assertFailsWith<ApiException> {
            ScoutingService.deleteEntry(ftc100, matchEntry.id)
        }
    }

    @Test
    fun testChatAndBackupIsolationAcrossTeamsAndPrograms() {
        val frc100 = createUser("frc100", 100, "FRC", UserRole.ADMIN)
        val ftc100 = createUser("ftc100", 100, "FTC", UserRole.ADMIN)
        val frc200 = createUser("frc200", 200, "FRC", UserRole.ADMIN)

        val msgFrc100 = ChatService.sendMessage(100, "FRC", "general", frc100.userId, frc100.username, "Hello FRC 100", UserRole.ADMIN)
        ChatService.sendMessage(100, "FTC", "general", ftc100.userId, ftc100.username, "Hello FTC 100", UserRole.ADMIN)
        ChatService.sendMessage(200, "FRC", "general", frc200.userId, frc200.username, "Hello FRC 200", UserRole.ADMIN)

        ChatService.updateLastRead(frc100.userId, "general")
        ChatService.updateLastRead(ftc100.userId, "general")
        ChatService.updateLastRead(frc200.userId, "general")

        // FTC 100 cannot edit or delete FRC 100's message
        assertNull(ChatService.editMessage(msgFrc100.id, frc100.userId, 100, "Hacked", program = "FTC"))
        assertFalse(ChatService.deleteMessage(msgFrc100.id, frc100.userId, UserRole.ADMIN, 100, program = "FTC"))

        // Clearing #general on FRC 100 must NOT delete UserChatLastRead for FTC 100 or FRC 200
        ChatService.clearGroupMessages(100, "FRC", "general", UserRole.ADMIN)
        transaction {
            val remainingReads = UserChatLastRead.selectAll().map { it[UserChatLastRead.userId].value.toString() }.toSet()
            assertFalse(frc100.userId in remainingReads)
            assertTrue(ftc100.userId in remainingReads)
            assertTrue(frc200.userId in remainingReads)
        }

        // Re-post on FRC 100 and verify BackupService.exportBackup only exports FRC 100's chat messages
        ChatService.sendMessage(100, "FRC", "general", frc100.userId, frc100.username, "FRC Only Message", UserRole.ADMIN)
        val frcBackup = BackupService.exportBackup(teamNumber = 100, type = "entire", program = "FRC")
        assertEquals(1, frcBackup.chatMessages.size)
        assertTrue(frcBackup.chatMessages.all { it.program == "FRC" && it.content == "FRC Only Message" })
    }

    @Test
    fun testShareLinkAllianceScopeEnforcesProgramMatch(): Unit = runBlocking {
        val frc100 = createUser("frc100", 100, "FRC", UserRole.ADMIN)
        val ftc100 = createUser("ftc100", 100, "FTC", UserRole.ADMIN)

        val share = ShareService.createShare(
            frc100,
            CreateShareRequest(
                title = "FRC Alliance Share",
                resourceType = "event_overview",
                targetEventKey = "2026txhou",
                shareMode = "snapshot",
                queryConfigJson = "{}",
                snapshotDataJson = """{"teams":[]}""",
                accessScope = "alliance"
            )
        )

        // FTC 100 has the same teamNumber (100), but different program ("FTC"), so resolveShare must deny access
        val ftcResolved = ShareService.resolveShare(
            token = share.token,
            pin = null,
            session = ftc100,
            clientIp = "127.0.0.1",
            userAgent = "test"
        )
        assertFalse(ftcResolved.isAllowed)
        assertNull(ftcResolved.snapshotDataJson)

        // FRC 100 has matching teamNumber and program, so resolveShare grants access
        val frcResolved = ShareService.resolveShare(
            token = share.token,
            pin = null,
            session = frc100,
            clientIp = "127.0.0.1",
            userAgent = "test"
        )
        assertTrue(frcResolved.isAllowed)
        assertNotNull(frcResolved.snapshotDataJson)
    }

    @Test
    fun testSaveEventAndRemapMatchKeyRespectProgramBoundary() {
        val frc100 = createUser("frc100", 100, "FRC", UserRole.ADMIN)
        val ftc100 = createUser("ftc100", 100, "FTC", UserRole.ADMIN)

        // Create custom event "2026custom" for FRC 100
        TeamCacheOverrideService.saveEvent(
            frc100,
            EventRecord(
                eventKey = "2026custom",
                year = 2026,
                eventCode = "custom",
                name = "FRC Custom Event"
            )
        )

        transaction {
            MatchPredictions.insertAndGetId {
                it[ownerTeamNumber] = 100
                it[program] = "FRC"
                it[userId] = frc100.userId
                it[eventKey] = "2026custom"
                it[matchKey] = "2026custom_qm1"
                it[predictedWinner] = "red"
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
            MatchPredictions.insertAndGetId {
                it[ownerTeamNumber] = 100
                it[program] = "FTC"
                it[userId] = ftc100.userId
                it[eventKey] = "2026custom"
                it[matchKey] = "2026custom_qm1"
                it[predictedWinner] = "blue"
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }

        // Remap match key for FRC Team 100 only
        transaction {
            MatchPredictionService.remapMatchKey("2026custom_qm1", "2026custom_qm99", ownerTeam = 100, program = "FRC")
            val frcPred = MatchPredictions.selectAll().where { MatchPredictions.userId eq frc100.userId }.single()
            val ftcPred = MatchPredictions.selectAll().where { MatchPredictions.userId eq ftc100.userId }.single()
            assertEquals("2026custom_qm99", frcPred[MatchPredictions.matchKey])
            assertEquals("2026custom_qm1", ftcPred[MatchPredictions.matchKey])
        }

        // Rename custom event key for FRC Team 100 via renameEvent
        TeamCacheOverrideService.renameEvent(
            frc100,
            "2026custom",
            EventRecord(
                eventKey = "2026renamed",
                year = 2026,
                eventCode = "renamed",
                name = "FRC Renamed Event"
            )
        )
        transaction {
            val frcPred = MatchPredictions.selectAll().where { MatchPredictions.userId eq frc100.userId }.single()
            val ftcPred = MatchPredictions.selectAll().where { MatchPredictions.userId eq ftc100.userId }.single()
            assertEquals("2026renamed", frcPred[MatchPredictions.eventKey])
            assertEquals("2026custom", ftcPred[MatchPredictions.eventKey])
        }
    }
}
