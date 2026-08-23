package com.obsidianscout.auth

import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.Users
import com.obsidianscout.db.UserSessions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import com.obsidianscout.db.PasswordResetTokens
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingEntries

class UserSessionsTest {

    private lateinit var testDbFile: File
    private lateinit var db: Database

    @BeforeTest
    fun setUp() {
        testDbFile = File("build/test_sessions_db_${System.nanoTime()}.db")
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        db = Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                Users,
                AppSettings,
                UserSessions,
                ScoutingEntries,
                PitScoutingEntries,
                QualitativeScoutingEntries,
                PasswordResetTokens
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (::testDbFile.isInitialized && testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testCreateAndListSessions() {
        val user = AuthService.register(
            username = "sessiontester",
            teamNumber = 9999,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        val userUuid = UUID.fromString(user.id)

        val session1 = AuthService.createSession(
            userId = userUuid,
            clientType = "web",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            ipAddress = "192.168.1.100"
        )
        val session2 = AuthService.createSession(
            userId = userUuid,
            clientType = "mobile",
            userAgent = "ObsidianScout/1.0 (Android 14)",
            ipAddress = "10.0.0.2"
        )

        val list = AuthService.listSessions(userUuid, currentSessionId = session1.toString())
        assertEquals(2, list.size)

        val s1Info = list.find { it.id == session1.toString() }
        assertNotNull(s1Info)
        assertTrue(s1Info.isCurrent)
        assertEquals("Chrome on Windows", s1Info.deviceName)
        assertEquals("192.168.1.100", s1Info.ipAddress)

        val s2Info = list.find { it.id == session2.toString() }
        assertNotNull(s2Info)
        assertFalse(s2Info.isCurrent)
        assertTrue(s2Info.deviceName.contains("Android"))
    }

    @Test
    fun testRevokeSingleSession() {
        val user = AuthService.register(
            username = "revoketester",
            teamNumber = 8888,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        val userUuid = UUID.fromString(user.id)

        val session1 = AuthService.createSession(userUuid, "web", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/17.0", "1.1.1.1")
        val session2 = AuthService.createSession(userUuid, "web", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0", "2.2.2.2")

        var list = AuthService.listSessions(userUuid, session1.toString())
        assertEquals(2, list.size)

        // Revoke session 2
        val revoked = AuthService.revokeSession(userUuid, session2)
        assertTrue(revoked)

        list = AuthService.listSessions(userUuid, session1.toString())
        assertEquals(1, list.size)
        assertEquals(session1.toString(), list[0].id)

        // Revoking already revoked session returns false
        val revokedAgain = AuthService.revokeSession(userUuid, session2)
        assertFalse(revokedAgain)
    }

    @Test
    fun testRevokeAllOtherSessions() {
        val user = AuthService.register(
            username = "revokeotherstester",
            teamNumber = 7777,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        val userUuid = UUID.fromString(user.id)

        val session1 = AuthService.createSession(userUuid, "web", "", "1.1.1.1")
        val session2 = AuthService.createSession(userUuid, "mobile", "", "2.2.2.2")
        val session3 = AuthService.createSession(userUuid, "web", "", "3.3.3.3")

        var list = AuthService.listSessions(userUuid, session1.toString())
        assertEquals(3, list.size)

        // Revoke all other sessions keeping session1
        val revokedCount = AuthService.revokeAllOtherSessions(userUuid, session1.toString())
        assertEquals(2, revokedCount)

        list = AuthService.listSessions(userUuid, session1.toString())
        assertEquals(1, list.size)
        assertEquals(session1.toString(), list[0].id)
        assertTrue(list[0].isCurrent)
    }

    @Test
    fun testRevokeAllSessions() {
        val user = AuthService.register(
            username = "revokealltester",
            teamNumber = 6666,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        val userUuid = UUID.fromString(user.id)

        AuthService.createSession(userUuid, "web", "", "1.1.1.1")
        AuthService.createSession(userUuid, "mobile", "", "2.2.2.2")

        val count = AuthService.revokeAllSessions(userUuid)
        assertEquals(2, count)

        val list = AuthService.listSessions(userUuid, null)
        assertEquals(0, list.size)
    }

    @Test
    fun testParseDeviceName() {
        assertEquals(
            "Chrome on Windows",
            AuthService.parseDeviceName("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        )
        assertEquals(
            "Safari on iOS (iPhone)",
            AuthService.parseDeviceName("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
        )
        assertEquals(
            "Firefox on macOS",
            AuthService.parseDeviceName("Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0")
        )
        assertEquals(
            "Edge on Windows",
            AuthService.parseDeviceName("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0")
        )
        assertEquals(
            "ObsidianScout App on Android",
            AuthService.parseDeviceName("ObsidianScout/2.0 (Android 14; Pixel 8)")
        )
    }

    @Test
    fun testDeleteUserCleansUpSessions() {
        val user = AuthService.register(
            username = "deletewithsessions",
            teamNumber = 5555,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        val userUuid = UUID.fromString(user.id)

        AuthService.createSession(userUuid, "web", "", "1.1.1.1")
        AuthService.createSession(userUuid, "web", "", "2.2.2.2")

        val callerSession = UserSession(
            userId = user.id,
            username = user.username,
            teamNumber = user.teamNumber,
            role = UserRole.SCOUT
        )

        AuthService.deleteUser(callerSession, user.id)

        val remainingSessions = transaction {
            UserSessions.selectAll().where { UserSessions.userId eq userUuid }.count()
        }
        assertEquals(0, remainingSessions)
    }

    @Test
    fun testRevokedSessionValidation() {
        val user = AuthService.register(
            username = "validatortester",
            teamNumber = 4444,
            password = "Password123!",
            program = "FRC",
            role = UserRole.SCOUT
        )
        val userUuid = UUID.fromString(user.id)

        val sessionUuid = AuthService.createSession(userUuid, "web", "TestAgent", "127.0.0.1")

        // Before revocation: exists and valid
        val existsBefore = transaction {
            UserSessions.selectAll().where { (UserSessions.id eq sessionUuid) and (UserSessions.userId eq userUuid) }.any()
        }
        assertTrue(existsBefore)

        // Revoke
        AuthService.revokeSession(userUuid, sessionUuid)

        // After revocation: does not exist
        val existsAfter = transaction {
            UserSessions.selectAll().where { (UserSessions.id eq sessionUuid) and (UserSessions.userId eq userUuid) }.any()
        }
        assertFalse(existsAfter)
    }
}
