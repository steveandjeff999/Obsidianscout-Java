package com.obsidianscout.auth

import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.*

class LocalAiPreferenceTest {

    private val testDbFile = File("build/test_local_ai_pref_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, AppSettings)
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    private fun createScout(username: String): UserRecord = transaction {
        AuthService.createUser(
            callerSession = UserSession("seed", "superadmin", 0, "FRC", UserRole.SUPERADMIN),
            username = username,
            teamNumber = 254,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
    }

    @Test
    fun testLocalAiIsDisabledByDefault() {
        transaction { Users.deleteAll() }

        val user = createScout("test_local_ai_default")
        assertFalse(user.localAiEnabled, "Local AI must be off by default")

        val fetched = AuthService.getUserById(user.id)
        assertNotNull(fetched)
        assertFalse(fetched.localAiEnabled)
    }

    @Test
    fun testToggleLocalAi() {
        transaction { Users.deleteAll() }

        val user = createScout("test_local_ai_toggle")
        val userSession = UserSession(user.id, user.username, user.teamNumber, user.program, user.role)

        val enabled = AuthService.updateUser(
            callerSession = userSession,
            targetUserId = user.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newLocalAiEnabled = true
        )
        assertTrue(enabled.localAiEnabled)
        assertEquals(true, AuthService.getUserById(user.id)?.localAiEnabled)

        // Updating an unrelated field must not reset the preference.
        val unrelated = AuthService.updateUser(
            callerSession = userSession,
            targetUserId = user.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newBugReportPreference = "never"
        )
        assertTrue(unrelated.localAiEnabled)

        val disabled = AuthService.updateUser(
            callerSession = userSession,
            targetUserId = user.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newLocalAiEnabled = false
        )
        assertFalse(disabled.localAiEnabled)
        assertEquals(false, AuthService.getUserById(user.id)?.localAiEnabled)
    }
}
