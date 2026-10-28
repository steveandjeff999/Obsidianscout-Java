package com.obsidianscout.slack

import com.obsidianscout.auth.ClusterCryptoUtils
import com.obsidianscout.auth.ClusterSecretService
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.BackupService
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.SlackIntegrations
import com.obsidianscout.db.SlackUserPreferences
import com.obsidianscout.db.SnapshotService
import com.obsidianscout.db.Users
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** Slack settings survive JSON backups, CSV backups and database snapshots. */
class SlackBackupTest {
    private val dbFile = File("build/test_slack_backup_${System.currentTimeMillis()}.db")
    private val snapshotDir = File("build/test_slack_snapshots_${System.currentTimeMillis()}")
    private lateinit var alice: UUID
    private val rulesSerializer = ListSerializer(SlackChannelRule.serializer())

    @BeforeTest
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        snapshotDir.mkdirs()
        DatabaseFactory.init(DatabaseConfig(type = "sqlite", sqlite = SqliteConfig(file = dbFile.absolutePath)), runMigration = true, isCockroach = false)
        SnapshotService.customStorageDirectory = snapshotDir
        ClusterSecretService.forgetDataEncryptionKey()
        SlackIntegrationService.clearAllCaches()

        val key = ClusterSecretService.getDataEncryptionKey()
        transaction {
            alice = Users.insertAndGetId {
                it[username] = "alice"
                it[teamNumber] = 1111
                it[program] = "FRC"
                it[passwordHash] = "x"
                it[role] = "ADMIN"
                it[createdAt] = Instant.now()
            }.value
            val rule = SlackChannelRule("C123", "drive", audience = SlackAudience(everyone = false, userIds = listOf(alice.toString())))
            SlackIntegrations.insert {
                it[teamNumber] = 1111
                it[program] = "FRC"
                it[enabled] = true
                it[botTokenEnc] = ClusterCryptoUtils.encrypt("xoxb-bot", key)
                it[appTokenEnc] = ClusterCryptoUtils.encrypt("xapp-app", key)
                it[workspaceName] = "Robots"
                it[rulesJson] = JsonSupport.json.encodeToString(rulesSerializer, listOf(rule))
                it[userLinksJson] = """{"$alice":"UALICE"}"""
                it[updatedAt] = Instant.now()
            }
            SlackUserPreferences.insert {
                it[userId] = EntityID(alice, Users)
                it[preference] = "mentions"
                it[updatedAt] = Instant.now()
            }
        }
    }

    @AfterTest
    fun tearDown() {
        SnapshotService.customStorageDirectory = null
        SlackIntegrationService.clearAllCaches()
        DatabaseFactory.close()
        dbFile.delete()
        snapshotDir.deleteRecursively()
    }

    private fun assertImportedInto(team: Int) {
        val newAlice = transaction {
            Users.selectAll().where { (Users.username eq "alice") and (Users.teamNumber eq team) }.single()[Users.id].value
        }
        val record = SlackIntegrationService.load(team, "FRC")
        assertNotNull(record)
        assertEquals("xoxb-bot", record.botToken)
        assertEquals("xapp-app", record.appToken)
        assertEquals("Robots", record.workspaceName)
        // Ids are remapped to the imported user.
        assertEquals(listOf(newAlice.toString()), record.rules.single().audience.userIds)
        assertEquals(mapOf(newAlice.toString() to "UALICE"), record.userLinks)
        assertEquals("mentions", SlackIntegrationService.preferenceOf(newAlice.toString()))
    }

    @Test
    fun jsonBackupCarriesSlackWithUsernamesAndRestoresIntoAnotherTeam() {
        val backup = BackupService.exportBackup(1111, "entire", "team", "FRC")
        val slack = backup.slackIntegrations.single()
        assertEquals("xoxb-bot", slack.botToken)
        assertEquals(listOf("alice"), slack.rules.single().audience.userIds)
        assertEquals(mapOf("alice" to "UALICE"), slack.userLinks)
        assertEquals("mentions", backup.slackUserPreferences.single().preference)

        // A scouting-only backup leaves Slack out.
        assertTrue(BackupService.exportBackup(1111, "scouting", "team", "FRC").slackIntegrations.isEmpty())

        val report = BackupService.importBackup(3333, backup, alice.toString(), isSuperAdmin = false, targetProgram = "FRC")
        assertEquals(1, report.slackIntegrationsImported)
        assertEquals(1, report.slackPreferencesImported)
        assertImportedInto(3333)
    }

    @Test
    fun csvBackupRoundTrips() {
        val zip = BackupService.exportCsv(1111, "entire", "team", "FRC")
        val report = BackupService.importCsv(4444, zip, alice.toString(), isSuperAdmin = false, targetProgram = "FRC")
        assertEquals(1, report.slackIntegrationsImported)
        assertImportedInto(4444)
    }

    @Test
    fun snapshotRestoreBringsSlackBack() {
        val snapshot = SnapshotService.getSnapshotFile(SnapshotService.createSnapshot().fileName)
        assertNotNull(snapshot)
        transaction {
            SlackUserPreferences.deleteAll()
            SlackIntegrations.deleteAll()
        }

        val report = SnapshotService.restoreFromSnapshot(snapshot)
        assertTrue(report.success, report.message)
        assertEquals(1L, report.restoredTables["slack_integrations"])
        assertEquals("xoxb-bot", SlackIntegrationService.load(1111, "FRC")?.botToken)
        assertEquals("mentions", SlackIntegrationService.preferenceOf(alice.toString()))
    }

    @Test
    fun restoringASnapshotFromBeforeSlackKeepsTheCurrentConnection() {
        val snapshot = SnapshotService.getSnapshotFile(SnapshotService.createSnapshot().fileName)
        assertNotNull(snapshot)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${snapshot.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("DROP TABLE slack_user_preferences")
                it.executeUpdate("DROP TABLE slack_integrations")
            }
        }

        val report = SnapshotService.restoreFromSnapshot(snapshot)
        assertTrue(report.success, report.message)
        assertEquals("xoxb-bot", SlackIntegrationService.load(1111, "FRC")?.botToken)
    }
}
