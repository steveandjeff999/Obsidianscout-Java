package com.obsidianscout.slack

import com.obsidianscout.auth.ClusterCryptoUtils
import com.obsidianscout.auth.ClusterSecretService
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.SlackIntegrations
import com.obsidianscout.db.SlackUserPreferences
import com.obsidianscout.db.Users
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** Slack settings survive a server restart, and an update onto a database from before Slack existed. */
class SlackRestartTest {
    private val dbFile = File("build/test_slack_restart_${System.currentTimeMillis()}.db")

    /** Starts the "server" fresh: nothing kept in memory from the last run. */
    private fun boot() {
        ClusterSecretService.forgetDataEncryptionKey()
        SlackIntegrationService.clearAllCaches()
        DatabaseFactory.init(DatabaseConfig(type = "sqlite", sqlite = SqliteConfig(file = dbFile.absolutePath)), runMigration = true, isCockroach = false)
    }

    private fun shutdown() {
        SlackIntegrationService.clearAllCaches()
        DatabaseFactory.close()
    }

    @BeforeTest
    fun setUp() {
        dbFile.parentFile?.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        runCatching { shutdown() }
        ClusterSecretService.forgetDataEncryptionKey()
        dbFile.delete()
    }

    @Test
    fun settingsAndTokensSurviveARestart() {
        boot()
        val key = ClusterSecretService.getDataEncryptionKey()
        lateinit var alice: UUID
        transaction {
            alice = Users.insertAndGetId {
                it[username] = "alice"
                it[teamNumber] = 1111
                it[program] = "FRC"
                it[passwordHash] = "x"
                it[role] = "ADMIN"
                it[createdAt] = Instant.now()
            }.value
            SlackIntegrations.insert {
                it[teamNumber] = 1111
                it[program] = "FRC"
                it[enabled] = true
                it[botTokenEnc] = ClusterCryptoUtils.encrypt("xoxb-bot", key)
                it[appTokenEnc] = ClusterCryptoUtils.encrypt("xapp-app", key)
                it[updatedAt] = Instant.now()
            }
            SlackUserPreferences.insert {
                it[userId] = EntityID(alice, Users)
                it[preference] = "none"
                it[updatedAt] = Instant.now()
            }
        }
        shutdown()

        // Twice, so a second restart doesn't replace the key or the tables either.
        repeat(2) {
            boot()
            assertEquals(key, ClusterSecretService.getDataEncryptionKey())
            val ready = SlackIntegrationService.allReady().single()
            assertEquals("xoxb-bot", ready.botToken)
            assertEquals("xapp-app", ready.appToken)
            assertEquals("none", SlackIntegrationService.preferenceOf(alice.toString()))
            shutdown()
        }
    }

    @Test
    fun updatingFromADatabaseWithoutSlackCreatesTheTables() {
        boot()
        shutdown()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("DROP TABLE slack_user_preferences")
                it.executeUpdate("DROP TABLE slack_integrations")
                it.executeUpdate("DROP TABLE slack_notifications")
                it.executeUpdate("DELETE FROM cluster_secrets WHERE key_name = 'data_encryption_key'")
            }
        }

        boot()
        assertTrue(SlackIntegrationService.allReady().isEmpty())
        assertTrue(SlackIntegrationService.recentFor("00000000-0000-0000-0000-000000000000", Instant.EPOCH).isEmpty())
        assertTrue(ClusterSecretService.getDataEncryptionKey().isNotBlank())
    }
}
