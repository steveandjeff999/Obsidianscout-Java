package com.obsidianscout.widgets

import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.UserSessions
import com.obsidianscout.db.Users
import com.obsidianscout.db.WidgetTokens
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetTokenServiceTest {
    private val testDbFile = File("build/test_widget_tokens_${System.nanoTime()}.db")
    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, UserSessions, WidgetTokens)
            Users.insert {
                it[id] = userId
                it[username] = "driver"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[passwordHash] = "x"
                it[role] = "SCOUT"
                it[createdAt] = Instant.now()
            }
            UserSessions.insert {
                it[id] = sessionId
                it[UserSessions.userId] = this@WidgetTokenServiceTest.userId
                it[clientType] = "mobile"
                it[createdAt] = Instant.now()
                it[lastActiveAt] = Instant.now()
                it[expiresAt] = Instant.now().plus(Duration.ofDays(7))
            }
        }
    }

    @AfterTest
    fun tearDown() {
        testDbFile.delete()
    }

    private fun session() = UserSession(userId.toString(), "driver", 254, "FRC", UserRole.SCOUT, sessionId = sessionId.toString())

    @Test
    fun tokenResolvesToTheUsersCurrentAccountState() {
        val issued = WidgetTokenService.issue(session(), "android")
        assertTrue(issued.token.startsWith("wt_"))
        // Only the hash is stored.
        val stored = transaction { WidgetTokens.selectAll().single()[WidgetTokens.tokenHash] }
        assertEquals(WidgetTokenService.hash(issued.token), stored)
        assertFalse(stored.contains(issued.token))

        val resolved = assertNotNull(WidgetTokenService.resolve(issued.token))
        assertEquals(254, resolved.session.teamNumber)
        assertEquals(UserRole.SCOUT, resolved.session.role)
        assertFalse(resolved.session.nonScoutingToolsEnabled)

        // Role and Non-Scouting Tools are read fresh, so widget access follows account changes.
        transaction {
            Users.update({ Users.id eq userId }) {
                it[role] = "ANALYTICS"
                it[nonScoutingToolsEnabled] = true
            }
        }
        val again = assertNotNull(WidgetTokenService.resolve(issued.token))
        assertEquals(UserRole.ANALYTICS, again.session.role)
        assertTrue(again.session.nonScoutingToolsEnabled)
    }

    @Test
    fun tokenDiesWithItsLoginSession() {
        val issued = WidgetTokenService.issue(session(), "ios")
        assertTrue(AuthService.revokeSession(userId, sessionId))
        assertNull(WidgetTokenService.resolve(issued.token))
    }

    @Test
    fun reissuingReplacesTheOldTokenAndBadTokensFail() {
        val first = WidgetTokenService.issue(session(), "android")
        val second = WidgetTokenService.issue(session(), "android")
        assertNull(WidgetTokenService.resolve(first.token))
        assertNotNull(WidgetTokenService.resolve(second.token))
        assertNull(WidgetTokenService.resolve("wt_nope"))
        assertNull(WidgetTokenService.resolve("not-a-widget-token"))
        assertNull(WidgetTokenService.resolve(second.token, now = Instant.now().plus(Duration.ofDays(31))))
    }

    @Test
    fun placedWidgetsAreRecordedForPushTargeting() {
        val issued = WidgetTokenService.issue(session(), "android")
        val resolved = assertNotNull(WidgetTokenService.resolve(issued.token))
        WidgetTokenService.touch(resolved, setOf("next_match", "chat", "bogus"), "2026test")
        val holder = WidgetTokenService.activeHolders().single()
        assertEquals(setOf("chat", "next_match"), holder.placed)
        assertEquals("2026test", holder.eventKey)
    }
}
