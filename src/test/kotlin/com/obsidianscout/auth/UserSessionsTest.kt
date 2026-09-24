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
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ChatMessages
import com.obsidianscout.db.UserChatLastRead
import com.obsidianscout.db.ChatGroups
import com.obsidianscout.db.PushSubscriptions
import com.obsidianscout.db.FcmDeviceTokens
import com.obsidianscout.db.AnalyticsReports
import com.obsidianscout.db.PasskeyCredentials
import com.obsidianscout.db.PasskeyChallenges
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import java.time.Instant

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
                PasswordResetTokens,
                ScoutingAssignments,
                ChatMessages,
                UserChatLastRead,
                ChatGroups,
                PushSubscriptions,
                FcmDeviceTokens,
                AnalyticsReports,
                PasskeyCredentials,
                PasskeyChallenges
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

    @Test
    fun testDeleteUserWithAllForeignKeys() {
        val user = AuthService.register(
            username = "fullfktester",
            teamNumber = 9506,
            password = "Password123!",
            program = "FRC",
            role = UserRole.ADMIN
        )
        val userUuid = UUID.fromString(user.id)
        val now = Instant.now()

        // Create referencing rows across all tables
        val assignmentId = transaction {
            // ScoutingAssignments (assignedUserId and createdByUserId)
            val aId = ScoutingAssignments.insertAndGetId {
                it[ownerTeamNumber] = 9506
                it[program] = "FRC"
                it[eventKey] = "2026test"
                it[assignedUserId] = EntityID(userUuid, Users)
                it[createdByUserId] = EntityID(userUuid, Users)
                it[assignmentType] = "MATCH"
                it[createdAt] = now
                it[updatedAt] = now
            }.value

            // ChatMessages
            ChatMessages.insert {
                it[teamNumber] = 9506
                it[program] = "FRC"
                it[groupName] = "general"
                it[userId] = EntityID(userUuid, Users)
                it[username] = "fullfktester"
                it[content] = "Hello team!"
                it[createdAt] = now
            }

            // UserChatLastRead
            UserChatLastRead.insert {
                it[userId] = EntityID(userUuid, Users)
                it[groupName] = "general"
                it[lastReadAt] = now
            }

            // ChatGroups
            ChatGroups.insert {
                it[teamNumber] = 9506
                it[program] = "FRC"
                it[groupName] = "custom-channel"
                it[createdByUserId] = EntityID(userUuid, Users)
                it[createdAt] = now
            }

            // AnalyticsReports
            AnalyticsReports.insert {
                it[ownerTeamNumber] = 9506
                it[program] = "FRC"
                it[userId] = EntityID(userUuid, Users)
                it[title] = "My Custom Report"
                it[configJson] = "{}"
                it[createdAt] = now
                it[updatedAt] = now
            }

            // PushSubscriptions
            PushSubscriptions.insert {
                it[userId] = EntityID(userUuid, Users)
                it[endpoint] = "https://push.example.com/sub/123"
                it[p256dh] = "key123"
                it[auth] = "auth123"
                it[createdAt] = now
            }

            // FcmDeviceTokens
            FcmDeviceTokens.insert {
                it[userId] = EntityID(userUuid, Users)
                it[deviceToken] = "token123"
                it[platform] = "android"
                it[updatedAt] = now
            }

            // PasskeyCredentials
            PasskeyCredentials.insert {
                it[userId] = EntityID(userUuid, Users)
                it[credentialId] = "cred123"
                it[publicKeyCose] = "cose123"
                it[createdAt] = now
            }

            // PasskeyChallenges
            PasskeyChallenges.insert {
                it[challenge] = "challenge123"
                it[userId] = EntityID(userUuid, Users)
                it[flow] = "authenticate"
                it[expiresAt] = now.plusSeconds(300)
            }

            // Scouting entries
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 9506
                it[program] = "FRC"
                it[dataJson] = "{}"
                it[submittedByUserId] = EntityID(userUuid, Users)
                it[createdAt] = now
            }

            aId
        }

        // Add active session
        AuthService.createSession(userUuid, "web", "", "127.0.0.1")

        val callerSession = UserSession(
            userId = user.id,
            username = user.username,
            teamNumber = user.teamNumber,
            role = UserRole.ADMIN
        )

        // Delete the user - this previously failed with foreign key constraint violation
        AuthService.deleteUser(callerSession, user.id)

        // Verify target user is deleted
        val userExists = transaction {
            Users.selectAll().where { Users.id eq userUuid }.any()
        }
        assertFalse(userExists)

        // Verify "Deleted User" placeholder exists and took ownership
        transaction {
            val deletedUserRow = Users.selectAll().where {
                (Users.username eq "Deleted User") and (Users.teamNumber eq 9506)
            }.firstOrNull()
            assertNotNull(deletedUserRow)
            val deletedUserId = deletedUserRow[Users.id].value

            // Assignment was reassigned to Deleted User and createdBy cleared
            val assignment = ScoutingAssignments.selectAll().where { ScoutingAssignments.id eq assignmentId }.first()
            assertEquals(deletedUserId, assignment[ScoutingAssignments.assignedUserId].value)
            assertEquals(null, assignment[ScoutingAssignments.createdByUserId])

            // Cleaned up personal tables
            assertEquals(0, UserSessions.selectAll().where { UserSessions.userId eq userUuid }.count())
            assertEquals(0, PushSubscriptions.selectAll().where { PushSubscriptions.userId eq userUuid }.count())
            assertEquals(0, FcmDeviceTokens.selectAll().where { FcmDeviceTokens.userId eq userUuid }.count())
            assertEquals(0, PasskeyCredentials.selectAll().where { PasskeyCredentials.userId eq userUuid }.count())
            assertEquals(0, PasskeyChallenges.selectAll().where { PasskeyChallenges.userId eq userUuid }.count())
            assertEquals(0, UserChatLastRead.selectAll().where { UserChatLastRead.userId eq userUuid }.count())

            // Reassigned data tables to Deleted User
            val chatMsg = ChatMessages.selectAll().where { ChatMessages.teamNumber eq 9506 }.first()
            assertEquals(deletedUserId, chatMsg[ChatMessages.userId].value)

            val report = AnalyticsReports.selectAll().where { AnalyticsReports.ownerTeamNumber eq 9506 }.first()
            assertEquals(deletedUserId, report[AnalyticsReports.userId].value)

            val entry = ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq 9506 }.first()
            assertEquals(deletedUserId, entry[ScoutingEntries.submittedByUserId].value)
        }
    }
}
