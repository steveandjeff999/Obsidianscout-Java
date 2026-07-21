package com.obsidianscout.auth

import com.obsidianscout.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
            SchemaUtils.create(Users)
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
}
