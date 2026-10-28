package com.obsidianscout.auth

import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.Users
import com.obsidianscout.integrations.SettingsService
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A CUSTOM user given the Users page manages scout-level accounts but can't hand out more than they have. */
class CustomRoleUserManagementTest {

    private val testDbFile = File("build/test_custom_role_db_${System.currentTimeMillis()}.db")
    private val team = 4321
    private val program = "FRC"

    private lateinit var admin: UserSession
    private lateinit var manager: UserRecord
    private lateinit var managerSession: UserSession

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction { SchemaUtils.create(Users, AppSettings) }

        val adminRecord = AuthService.register("admin", team, "Password123!", program, UserRole.ADMIN)
        admin = UserSession(adminRecord.id, adminRecord.username, team, program, UserRole.ADMIN)
        manager = AuthService.createUser(
            admin, "manager", team, "Password123!", program, UserRole.CUSTOM,
            customPages = listOf("users", "matches", "logs")
        )
        // What requireAdminOrPage("users") hands the CUSTOM user for one request.
        managerSession = UserSession(manager.id, manager.username, team, program, UserRole.ADMIN,
            actingCustomPages = SettingsService.customPagesFor(team, program, manager.id))
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    @Test
    fun superadminPagesAreNeverStored() {
        assertEquals(setOf("dashboard", "my-assignments", "users", "matches"), manager.customPages.toSet())
    }

    @Test
    fun customManagerCannotCreateOrPromoteAdmins() {
        assertFailsWith<ApiException> {
            AuthService.createUser(managerSession, "boss", team, "Password123!", program, UserRole.ADMIN)
        }
        val scout = AuthService.createUser(managerSession, "scout", team, "Password123!", program, UserRole.SCOUT)
        assertFailsWith<ApiException> {
            AuthService.updateUser(managerSession, scout.id, null, null, UserRole.ADMIN)
        }
        assertFailsWith<ApiException> {
            AuthService.updateUser(managerSession, manager.id, null, null, UserRole.ADMIN)
        }
    }

    @Test
    fun customManagerCannotTouchAdminAccounts() {
        assertFailsWith<ApiException> {
            AuthService.updateUser(managerSession, admin.userId, null, "NewPassword123!", null)
        }
        assertFailsWith<ApiException> { AuthService.deleteUser(managerSession, admin.userId) }
    }

    @Test
    fun customManagerGivesOnlyTheirOwnPages() {
        val ok = AuthService.createUser(managerSession, "helper", team, "Password123!", program, UserRole.CUSTOM,
            customPages = listOf("matches"))
        assertTrue("matches" in ok.customPages)
        assertFailsWith<ApiException> {
            AuthService.createUser(managerSession, "helper2", team, "Password123!", program, UserRole.CUSTOM,
                customPages = listOf("admin-settings"))
        }
        assertFailsWith<ApiException> {
            AuthService.updateUser(managerSession, ok.id, null, null, null, newCustomPages = listOf("matches", "banners"))
        }
    }

    @Test
    fun customManagerCannotChangeTheirOwnPages() {
        assertFailsWith<ApiException> {
            AuthService.updateUser(managerSession, manager.id, null, null, null,
                newCustomPages = manager.customPages + "admin-settings")
        }
        // Re-sending the same pages (the edit form always does) is fine.
        AuthService.updateUser(managerSession, manager.id, null, null, null, newCustomPages = manager.customPages)
    }

    @Test
    fun adminsCanGiveAnyTeamAdminPage() {
        val updated = AuthService.updateUser(admin, manager.id, null, null, null,
            newCustomPages = listOf("users", "admin-settings", "banners", "cluster-management"))
        assertEquals(setOf("dashboard", "my-assignments", "users", "admin-settings", "banners"), updated.customPages.toSet())
    }
}
