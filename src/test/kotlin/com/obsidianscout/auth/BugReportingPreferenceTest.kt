package com.obsidianscout.auth

import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.*

class BugReportingPreferenceTest {

    private val testDbFile = File("build/test_bug_pref_${System.currentTimeMillis()}.db")

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

    @Test
    fun testDefaultBugReportPreferenceIsAsk() {
        transaction { Users.deleteAll() }

        val user = transaction {
            AuthService.createUser(
                callerSession = UserSession("seed", "superadmin", 0, "FRC", UserRole.SUPERADMIN),
                username = "test_user_pref",
                teamNumber = 254,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SCOUT
            )
        }

        assertEquals("ask", user.bugReportPreference, "Default bugReportPreference should be 'ask'")

        val fetched = AuthService.getUserById(user.id)
        assertNotNull(fetched)
        assertEquals("ask", fetched.bugReportPreference)
    }

    @Test
    fun testUpdateBugReportPreferenceToAlwaysAndNever() {
        transaction { Users.deleteAll() }

        val user = transaction {
            AuthService.createUser(
                callerSession = UserSession("seed", "superadmin", 0, "FRC", UserRole.SUPERADMIN),
                username = "test_user_pref2",
                teamNumber = 254,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SCOUT
            )
        }

        val userSession = UserSession(user.id, user.username, user.teamNumber, user.program, user.role, bugReportPreference = user.bugReportPreference)

        // 1. Update to 'always'
        val updatedAlways = AuthService.updateUser(
            callerSession = userSession,
            targetUserId = user.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newBugReportPreference = "always"
        )
        assertEquals("always", updatedAlways.bugReportPreference)

        var fetched = AuthService.getUserById(user.id)
        assertNotNull(fetched)
        assertEquals("always", fetched.bugReportPreference)

        // 2. Update to 'never'
        val updatedNever = AuthService.updateUser(
            callerSession = userSession,
            targetUserId = user.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newBugReportPreference = "never"
        )
        assertEquals("never", updatedNever.bugReportPreference)

        fetched = AuthService.getUserById(user.id)
        assertNotNull(fetched)
        assertEquals("never", fetched.bugReportPreference)
    }
}
