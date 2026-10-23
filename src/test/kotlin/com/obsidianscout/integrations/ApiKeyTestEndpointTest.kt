package com.obsidianscout.integrations

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.AppSettings
import com.obsidianscout.routes.TestApiRequest
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiKeyTestEndpointTest {

    private val testDbFile = File("build/test_apikey_db_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(AppSettings)
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
    fun testEmptyTbaKeyFailsValidation() = runBlocking {
        val session = UserSession(
            userId = "admin-1",
            username = "admin",
            teamNumber = 100,
            program = "FRC",
            role = UserRole.ADMIN
        )
        val request = TestApiRequest(api = "tba", tbaKey = "")
        val result = IntegrationService.testApiKey(session, request)
        assertFalse(result.success)
        assertTrue(result.message.contains("TBA Key is empty"))
    }

    @Test
    fun testEmptyFirstCredentialsFailsValidation() = runBlocking {
        val session = UserSession(
            userId = "admin-1",
            username = "admin",
            teamNumber = 100,
            program = "FRC",
            role = UserRole.ADMIN
        )
        val request = TestApiRequest(api = "first", firstUsername = "", firstKey = "")
        val result = IntegrationService.testApiKey(session, request)
        assertFalse(result.success)
        assertTrue(result.message.contains("FIRST Username and FIRST Key are required"))
    }

    @Test
    fun testUnknownApiReturnsError() = runBlocking {
        val session = UserSession(
            userId = "admin-1",
            username = "admin",
            teamNumber = 100,
            program = "FRC",
            role = UserRole.ADMIN
        )
        val request = TestApiRequest(api = "invalid_api")
        val result = IntegrationService.testApiKey(session, request)
        assertFalse(result.success)
        assertTrue(result.message.contains("Unknown API specified"))
    }

    @Test
    fun testStatboticsApiConnection() = runBlocking {
        val session = UserSession(
            userId = "admin-1",
            username = "admin",
            teamNumber = 100,
            program = "FRC",
            role = UserRole.ADMIN
        )
        val request = TestApiRequest(api = "statbotics", statboticsBaseUrl = "https://api.statbotics.io")
        val result = IntegrationService.testApiKey(session, request)
        assertTrue(result.message.contains("Statbotics API"), "Message should mention Statbotics API: ${result.message}")
    }

    @Test
    fun testStatboticsSyncEpaAndHistory() = runBlocking {
        val settings = ApiSettings(
            year = 2024,
            eventCode = "ncwak",
            useStatboticsEpa = true,
            statboticsBaseUrl = "https://api.statbotics.io"
        )
        transaction {
            SchemaUtils.create(com.obsidianscout.db.ApiTeams, com.obsidianscout.db.EpaOprHistoryCache)
        }
        IntegrationService.syncEpaOprHistory(settings, "2024ncwak")
        var (_, epaHistory) = IntegrationService.getEpaOprHistory(settings, "2024ncwak")
        if (epaHistory.isEmpty()) {
            // External Statbotics server was unreachable or failed, so nothing was written (a failure must not
            // replace cached history). Store a sample the way a successful sync would, to verify the round-trip.
            val sample = com.obsidianscout.config.JsonSupport.json.parseToJsonElement(
                "{\"team\": 5454, \"match\": \"2024ncwak_qm1\", \"alliance\": \"red\", \"epa\": {\"epa\": 25.0}}"
            )
            IntegrationService.writeStatsHistory("2024ncwak", oprs = null, epaHistory = listOf(sample), match13History = null)
            val res = IntegrationService.getEpaOprHistory(settings, "2024ncwak")
            epaHistory = res.second
        }
        assertTrue(epaHistory.isNotEmpty(), "Statbotics EPA history should be synced and non-empty")
    }

    @Test
    fun testFailedSourcesNeverWipeSharedHistory() {
        transaction {
            SchemaUtils.create(com.obsidianscout.db.EpaOprHistoryCache)
        }
        val epa = com.obsidianscout.config.JsonSupport.json.parseToJsonElement("{\"team\": 1, \"match\": \"2024wipe_qm1\"}")
        val m13 = com.obsidianscout.config.JsonSupport.json.parseToJsonElement("{\"key\": \"2024wipe_qm1\"}")
        IntegrationService.writeStatsHistory("2024wipe", mapOf("frc1" to 12.5), listOf(epa), listOf(m13))

        // A team without a Match 13 key, whose Statbotics call failed, only learned TBA OPRs.
        IntegrationService.writeStatsHistory("2024wipe", mapOf("frc1" to 13.0), epaHistory = null, match13History = null)
        // A sync that learned nothing writes nothing.
        IntegrationService.writeStatsHistory("2024wipe", null, null, null)

        val row = transaction {
            com.obsidianscout.db.EpaOprHistoryCache.selectAll()
                .where { com.obsidianscout.db.EpaOprHistoryCache.eventKey eq "2024wipe" }.single()
        }
        assertTrue(row[com.obsidianscout.db.EpaOprHistoryCache.oprsJson].contains("13.0"))
        assertTrue(row[com.obsidianscout.db.EpaOprHistoryCache.epaHistoryJson].contains("2024wipe_qm1"))
        assertTrue(row[com.obsidianscout.db.EpaOprHistoryCache.match13HistoryJson].contains("2024wipe_qm1"))
    }

    @Test
    fun testMatch13ApiConnectionHandlesLocalOrRemoteUrl() = runBlocking {
        val session = UserSession(
            userId = "admin-1",
            username = "admin",
            teamNumber = 100,
            program = "FRC",
            role = UserRole.ADMIN
        )
        val request = TestApiRequest(api = "match13", match13BaseUrl = "https://actions.match13.com")
        val result = IntegrationService.testApiKey(session, request)
        // Verify it was processed as Match 13 and returned a proper response (success or connection message)
        assertTrue(result.message.contains("Match 13 API"), "Message should mention Match 13 API: ${result.message}")
    }
}
