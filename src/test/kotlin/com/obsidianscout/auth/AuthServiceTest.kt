package com.obsidianscout.auth

import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.Users
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthServiceTest {

    private val testDbFile = File("build/test_auth_db_${System.currentTimeMillis()}.db")

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
    fun testCrossProgramAuthenticationIsolation() {
        try {
            // Register an FRC user
            val frcUser = AuthService.register(
                username = "scout1",
                teamNumber = 1234,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SCOUT
            )
            assertNotNull(frcUser)
            assertEquals("FRC", frcUser.program)

            // Attempt login with FTC program -> must fail and return null
            val ftcLoginAttempt = AuthService.login(
                username = "scout1",
                teamNumber = 1234,
                password = "Password123!",
                program = "FTC"
            )
            assertNull(ftcLoginAttempt, "Logging in with FTC program for an FRC user should fail when no FTC user exists")

            // Attempt login with FRC program -> must succeed
            val frcLoginAttempt = AuthService.login(
                username = "scout1",
                teamNumber = 1234,
                password = "Password123!",
                program = "FRC"
            )
            assertNotNull(frcLoginAttempt)
            assertEquals("scout1", frcLoginAttempt.username)
            assertEquals("FRC", frcLoginAttempt.program)

            // Register a distinct FTC user with the same username and team number
            val ftcUser = AuthService.register(
                username = "scout1",
                teamNumber = 1234,
                password = "DifferentPassword456!",
                program = "FTC",
                role = UserRole.SCOUT
            )
            assertNotNull(ftcUser)
            assertEquals("FTC", ftcUser.program)

            // Verify FTC login succeeds with FTC credentials
            val ftcLoginSuccess = AuthService.login(
                username = "scout1",
                teamNumber = 1234,
                password = "DifferentPassword456!",
                program = "FTC"
            )
            assertNotNull(ftcLoginSuccess)
            assertEquals("FTC", ftcLoginSuccess.program)

            // Verify FRC login succeeds with FRC credentials
            val frcLoginSuccess = AuthService.login(
                username = "scout1",
                teamNumber = 1234,
                password = "Password123!",
                program = "FRC"
            )
            assertNotNull(frcLoginSuccess)
            assertEquals("FRC", frcLoginSuccess.program)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun testSelfServiceUsernameReset() {
        val user = AuthService.register(
            username = "initial_scout",
            teamNumber = 5678,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        assertNotNull(user)

        val session = UserSession(
            userId = user.id,
            username = user.username,
            teamNumber = user.teamNumber,
            program = user.program,
            role = user.role
        )

        val updated = AuthService.updateUser(
            callerSession = session,
            targetUserId = user.id,
            newUsername = "renamed_scout",
            newPassword = null,
            newRole = null
        )

        assertEquals("renamed_scout", updated.username)

        val fetched = AuthService.getUserById(user.id)
        assertNotNull(fetched)
        assertEquals("renamed_scout", fetched.username)
    }

    @Test
    fun testSelfServicePasswordReset() {
        val user = AuthService.register(
            username = "pw_scout",
            teamNumber = 4321,
            password = "OldPassword123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        assertNotNull(user)

        val session = UserSession(
            userId = user.id,
            username = user.username,
            teamNumber = user.teamNumber,
            program = user.program,
            role = user.role
        )

        // Update password
        AuthService.updateUser(
            callerSession = session,
            targetUserId = user.id,
            newUsername = null,
            newPassword = "NewPassword456!",
            newRole = null
        )

        // Login with old password must fail
        val oldLogin = AuthService.login("pw_scout", 4321, "OldPassword123!", "FRC")
        assertNull(oldLogin)

        // Login with new password must succeed
        val newLogin = AuthService.login("pw_scout", 4321, "NewPassword456!", "FRC")
        assertNotNull(newLogin)
        assertEquals("pw_scout", newLogin.username)

        // Blank password throws BadRequest
        val blankEx = assertFailsWith<ApiException> {
            AuthService.updateUser(
                callerSession = session,
                targetUserId = user.id,
                newUsername = null,
                newPassword = "   ",
                newRole = null
            )
        }
        assertEquals(HttpStatusCode.BadRequest, blankEx.status)
    }

    @Test
    fun testSelfServiceUsernameDuplicateConflict() {
        val user1 = AuthService.register(
            username = "user_one",
            teamNumber = 9999,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        val user2 = AuthService.register(
            username = "user_two",
            teamNumber = 9999,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )

        val session2 = UserSession(
            userId = user2.id,
            username = user2.username,
            teamNumber = user2.teamNumber,
            program = user2.program,
            role = user2.role
        )

        val ex = assertFailsWith<ApiException> {
            AuthService.updateUser(
                callerSession = session2,
                targetUserId = user2.id,
                newUsername = "user_one",
                newPassword = null,
                newRole = null
            )
        }
        assertEquals(HttpStatusCode.Conflict, ex.status)
    }

    @Test
    fun testSelfServiceUsernameValidation() {
        val user = AuthService.register(
            username = "valid_user",
            teamNumber = 1111,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )

        val session = UserSession(
            userId = user.id,
            username = user.username,
            teamNumber = user.teamNumber,
            program = user.program,
            role = user.role
        )

        val blankEx = assertFailsWith<ApiException> {
            AuthService.updateUser(
                callerSession = session,
                targetUserId = user.id,
                newUsername = "   ",
                newPassword = null,
                newRole = null
            )
        }
        assertEquals(HttpStatusCode.BadRequest, blankEx.status)

        val deletedEx = assertFailsWith<ApiException> {
            AuthService.updateUser(
                callerSession = session,
                targetUserId = user.id,
                newUsername = "Deleted User",
                newPassword = null,
                newRole = null
            )
        }
        assertEquals(HttpStatusCode.BadRequest, deletedEx.status)
    }

    @Test
    fun testAdminCannotChangeOtherUserUsername() {
        val admin = AuthService.register(
            username = "team_admin",
            teamNumber = 2222,
            password = "Password123!",
            program = "FRC",
            role = UserRole.ADMIN
        )
        val scout = AuthService.register(
            username = "team_scout",
            teamNumber = 2222,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )

        val adminSession = UserSession(
            userId = admin.id,
            username = admin.username,
            teamNumber = admin.teamNumber,
            program = admin.program,
            role = admin.role
        )

        val ex = assertFailsWith<ApiException> {
            AuthService.updateUser(
                callerSession = adminSession,
                targetUserId = scout.id,
                newUsername = "scout_renamed_by_admin",
                newPassword = null,
                newRole = null
            )
        }
        assertEquals(HttpStatusCode.Forbidden, ex.status)
    }

    @Test
    fun testTeamRegistrationLockBlocksSelfRegistration() {
        // 1. Initial admin registers on new team (allowed)
        val admin = AuthService.register(
            username = "admin5555",
            teamNumber = 5555,
            password = "Password123!",
            program = "FRC",
            role = UserRole.ADMIN
        )
        assertNotNull(admin)

        // 2. Admin locks registration for team 5555
        val settings = com.obsidianscout.integrations.SettingsService.getSettings(5555, "FRC")
        com.obsidianscout.integrations.SettingsService.updateSettings(5555, settings.copy(registrationLocked = true))

        // 3. Self-registration attempt on team 5555 should fail with Forbidden
        val ex = assertFailsWith<ApiException> {
            AuthService.register(
                username = "new_scout",
                teamNumber = 5555,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SCOUT
            )
        }
        assertEquals(HttpStatusCode.Forbidden, ex.status)
        assertEquals("Registration is locked for this team. Please contact a team administrator to create an account.", ex.message)

        // 4. Admin can still create user on team 5555 via createUser
        val adminSession = UserSession(
            userId = admin.id,
            username = admin.username,
            teamNumber = admin.teamNumber,
            program = admin.program,
            role = admin.role
        )
        val adminCreatedUser = AuthService.createUser(
            callerSession = adminSession,
            username = "admin_added_scout",
            teamNumber = 5555,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        assertNotNull(adminCreatedUser)
        assertEquals("admin_added_scout", adminCreatedUser.username)

        // 5. Admin unlocks registration
        com.obsidianscout.integrations.SettingsService.updateSettings(5555, settings.copy(registrationLocked = false))

        // 6. Self-registration now succeeds
        val unlockedScout = AuthService.register(
            username = "unlocked_scout",
            teamNumber = 5555,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        assertNotNull(unlockedScout)
        assertEquals("unlocked_scout", unlockedScout.username)
    }
}
