package com.obsidianscout.slack

import com.obsidianscout.auth.UserRole
import com.obsidianscout.db.ClusterSecrets
import com.obsidianscout.db.SlackIntegrations
import com.obsidianscout.db.SlackNotifications
import com.obsidianscout.db.SlackUserPreferences
import com.obsidianscout.db.Users
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class SlackIntegrationServiceTest {
    private val testDbFile = File("build/test_slack_${System.currentTimeMillis()}.db")
    private lateinit var alice: UUID
    private lateinit var bob: UUID

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, ClusterSecrets, SlackIntegrations, SlackNotifications, SlackUserPreferences)
            alice = insertUser("alice", 1111)
            bob = insertUser("bob", 1111)
        }
        SlackIntegrationService.clearCache(1111, "FRC")
    }

    @AfterTest
    fun tearDown() {
        SlackIntegrationService.clearCache(1111, "FRC")
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun insertUser(name: String, team: Int): UUID = Users.insertAndGetId {
        it[username] = name
        it[teamNumber] = team
        it[program] = "FRC"
        it[passwordHash] = "x"
        it[role] = UserRole.SCOUT.name
        it[createdAt] = Instant.now()
    }.value

    @Test
    fun rulesAndLinksSaveWithoutTokensAndResponseNeverHasTokens() = runBlocking {
        val rule = SlackChannelRule("C123", "general", trigger = SlackTrigger.ALL_MESSAGES)
        val saved = SlackIntegrationService.save(1111, "FRC", SlackIntegrationUpdate(rules = listOf(rule), userLinks = mapOf(alice.toString() to "UALICE")), "admin")
        assertEquals(listOf(rule), saved.rules)
        assertEquals(mapOf(alice.toString() to "UALICE"), saved.userLinks)
        val response = SlackIntegrationService.toResponse(saved)
        assertFalse(response.botTokenSet)
        assertFalse(response.configured)
        assertTrue(response.manifestUrl.startsWith("https://api.slack.com/apps?new_app=1"))
    }

    @Test
    fun badTokenPrefixesAreRejectedBeforeCallingSlack() = runBlocking<Unit> {
        assertFailsWith<com.obsidianscout.auth.ApiException> {
            SlackIntegrationService.save(1111, "FRC", SlackIntegrationUpdate(botToken = "xapp-wrong"), "admin")
        }
        assertFailsWith<com.obsidianscout.auth.ApiException> {
            SlackIntegrationService.save(1111, "FRC", SlackIntegrationUpdate(appToken = "xoxb-wrong"), "admin")
        }
    }

    @Test
    fun cannotEnableWithoutToken() = runBlocking<Unit> {
        assertFailsWith<com.obsidianscout.auth.ApiException> {
            SlackIntegrationService.save(1111, "FRC", SlackIntegrationUpdate(enabled = true), "admin")
        }
    }

    @Test
    fun invalidRulesAreRejected() = runBlocking<Unit> {
        assertFailsWith<com.obsidianscout.auth.ApiException> {
            SlackIntegrationService.save(1111, "FRC", SlackIntegrationUpdate(rules = listOf(SlackChannelRule("not-a-channel"))), "admin")
        }
        assertFailsWith<com.obsidianscout.auth.ApiException> {
            SlackIntegrationService.save(1111, "FRC", SlackIntegrationUpdate(rules = listOf(SlackChannelRule("C1A"), SlackChannelRule("C1A"))), "admin")
        }
        assertFailsWith<com.obsidianscout.auth.ApiException> {
            val r = SlackChannelRule("C1A", audience = SlackAudience(everyone = false, roles = listOf("WIZARD")))
            SlackIntegrationService.save(1111, "FRC", SlackIntegrationUpdate(rules = listOf(r)), "admin")
        }
    }

    @Test
    fun tokensAreEncryptedAtRest() {
        val key = com.obsidianscout.auth.ClusterSecretService.getDataEncryptionKey()
        val enc = com.obsidianscout.auth.ClusterCryptoUtils.encrypt("xoxb-secret", key)
        transaction {
            SlackIntegrations.insert {
                it[teamNumber] = 1111
                it[program] = "FRC"
                it[botTokenEnc] = enc
                it[updatedAt] = Instant.now()
            }
        }
        val raw = transaction { SlackIntegrations.selectAll().single()[SlackIntegrations.botTokenEnc] }
        assertFalse(raw.contains("xoxb-secret"))
        assertEquals("xoxb-secret", SlackIntegrationService.load(1111, "FRC")!!.botToken)
        // The key is stable across calls.
        assertEquals(key, com.obsidianscout.auth.ClusterSecretService.getDataEncryptionKey())
    }

    @Test
    fun notificationsAreStoredOncePerMessage() {
        fun insert() = SlackIntegrationService.insertNotification(
            1111, "FRC", "C1:1.0", "C1", "general", "Ann", "hi", "", mapOf(alice.toString() to true), false
        )
        assertNotNull(insert())
        assertNull(insert())
        assertEquals(1, transaction { SlackNotifications.selectAll().count() }.toInt())
    }

    @Test
    fun recentForReturnsOnlyTheCallersBanners() {
        SlackIntegrationService.insertNotification(1111, "FRC", "C1:1.0", "C1", "general", "Ann", "for alice", "", mapOf(alice.toString() to true), false)
        SlackIntegrationService.insertNotification(1111, "FRC", "C1:2.0", "C1", "general", "Ann", "for bob", "", mapOf(bob.toString() to false), false)
        val forAlice = SlackIntegrationService.recentFor(alice.toString(), Instant.now().minusSeconds(60))
        assertEquals(listOf("for alice"), forAlice.map { it.text })
        assertTrue(forAlice.single().mentioned)
        assertEquals("#general", forAlice.single().channel)
    }

    @Test
    fun deletedMessagesAreRemoved() {
        SlackIntegrationService.insertNotification(1111, "FRC", "C1:1.0", "C1", "general", "Ann", "oops", "", mapOf(alice.toString() to true), false)
        SlackIntegrationService.deleteByEvent(1111, "FRC", SlackEventProcessor.messageKey("C1", "1.0"))
        assertTrue(SlackIntegrationService.recentFor(alice.toString(), Instant.now().minusSeconds(60)).isEmpty())
    }

    @Test
    fun preferencesDefaultToAllAndValidate() {
        assertEquals(SlackPreference.ALL, SlackIntegrationService.preferenceOf(alice.toString()))
        SlackIntegrationService.setPreference(alice.toString(), SlackPreference.MENTIONS)
        SlackIntegrationService.setPreference(alice.toString(), SlackPreference.NONE)
        assertEquals(SlackPreference.NONE, SlackIntegrationService.preferenceOf(alice.toString()))
        assertEquals(mapOf(alice.toString() to "none"), SlackIntegrationService.preferences(listOf(alice.toString(), bob.toString())))
        assertFailsWith<com.obsidianscout.auth.ApiException> { SlackIntegrationService.setPreference(alice.toString(), "loud") }
    }
}
