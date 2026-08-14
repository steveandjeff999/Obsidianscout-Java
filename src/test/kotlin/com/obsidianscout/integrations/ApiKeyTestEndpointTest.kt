package com.obsidianscout.integrations

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.AppSettings
import com.obsidianscout.routes.TestApiRequest
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
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
}
