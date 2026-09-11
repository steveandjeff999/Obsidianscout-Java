package com.obsidianscout.db

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.AutoBackupConfig
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.SqliteConfig
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class SnapshotServiceTest {

    private val testDir = File("build/test_snapshots_dir_")
    private val testPrimaryDbFile = File("build/test_primary_.db")

    @BeforeTest
    fun setUp() {
        testDir.mkdirs()
        testPrimaryDbFile.parentFile?.mkdirs()
        if (testPrimaryDbFile.exists()) testPrimaryDbFile.delete()

        val dbConfig = DatabaseConfig(
            type = "sqlite",
            sqlite = SqliteConfig(file = testPrimaryDbFile.absolutePath)
        )
        DatabaseFactory.init(dbConfig, runMigration = true, isCockroach = false)

        SnapshotService.customStorageDirectory = testDir
    }

    @AfterTest
    fun tearDown() {
        SnapshotService.customStorageDirectory = null
        DatabaseFactory.close()
        if (testPrimaryDbFile.exists()) testPrimaryDbFile.delete()
        if (testDir.exists()) testDir.deleteRecursively()
    }

    @Test
    fun testCreateSnapshotAndPruning() {
        val superAdminId = UUID.randomUUID()
        transaction {
            Users.insert {
                it[id] = EntityID(superAdminId, Users)
                it[username] = "superadmin_test"
                it[teamNumber] = 9999
                it[program] = "FRC"
                it[passwordHash] = "hash123"
                it[role] = "SUPERADMIN"
                it[createdAt] = Instant.now()
            }
        }

        val res = SnapshotService.createSnapshot(isAutoBackup = false)
        assertTrue(res.success)
        assertTrue(res.sizeBytes > 0)
        assertTrue(res.fileName.isNotBlank())

        val snapshotFile = SnapshotService.getSnapshotFile(res.fileName)
        assertNotNull(snapshotFile)
        assertTrue(snapshotFile.exists())

        val status = SnapshotService.getLocalNodeStatus()
        assertEquals(1, status.snapshots.size)
        assertEquals(res.fileName, status.snapshots[0].fileName)
        assertFalse(status.snapshots[0].isAutoBackup)

        val deleted = SnapshotService.deleteSnapshot(res.fileName)
        assertTrue(deleted)
        assertFalse(snapshotFile.exists())
        assertEquals(0, SnapshotService.listSnapshots().size)
    }

    @Test
    fun testFullDatabaseRestoration() {
        val adminId = UUID.randomUUID()
        val scouterId = UUID.randomUUID()

        transaction {
            Users.insert {
                it[id] = EntityID(adminId, Users)
                it[username] = "seed_admin"
                it[teamNumber] = 100
                it[program] = "FRC"
                it[passwordHash] = "pass_admin"
                it[role] = "SUPERADMIN"
                it[createdAt] = Instant.now()
            }
            Users.insert {
                it[id] = EntityID(scouterId, Users)
                it[username] = "john_scouter"
                it[teamNumber] = 100
                it[program] = "FRC"
                it[passwordHash] = "pass_scouter"
                it[role] = "SCOUTER"
                it[createdAt] = Instant.now()
            }
            AppSettings.insert {
                it[id] = EntityID(UUID.randomUUID(), AppSettings)
                it[teamNumber] = 100
                it[program] = "FRC"
                it[settingsJson] = "{\"eventCode\":\"2026TEST\"}"
                it[updatedAt] = Instant.now()
            }
            ScoutingEntries.insert {
                it[id] = EntityID(UUID.randomUUID(), ScoutingEntries)
                it[ownerTeamNumber] = 100
                it[targetTeamNumber] = 254
                it[matchNumber] = 1
                it[program] = "FRC"
                it[eventKey] = "2026test"
                it[dataJson] = "{\"auto_score\": 12}"
                it[submittedByUserId] = EntityID(scouterId, Users)
                it[createdAt] = Instant.now()
            }
        }

        val snapshotRes = SnapshotService.createSnapshot(isAutoBackup = true)
        val snapshotFile = SnapshotService.getSnapshotFile(snapshotRes.fileName)
        assertNotNull(snapshotFile)

        transaction {
            ScoutingEntries.deleteAll()
            Users.deleteAll()
            AppSettings.deleteAll()
        }

        transaction {
            assertEquals(0, Users.selectAll().count())
            assertEquals(0, ScoutingEntries.selectAll().count())
            assertEquals(0, AppSettings.selectAll().count())
        }

        val report = SnapshotService.restoreFromSnapshot(snapshotFile)
        assertTrue(report.success)
        assertTrue(report.totalRecordsRestored >= 4)

        transaction {
            val users = Users.selectAll().toList()
            // Users count will include seed_admin, john_scouter, and optionally seed superadmin
            assertTrue(users.size >= 2)
            val usernames = users.map { it[Users.username] }.toSet()
            assertTrue(usernames.contains("seed_admin"))
            assertTrue(usernames.contains("john_scouter"))

            val entries = ScoutingEntries.selectAll().toList()
            assertEquals(1, entries.size)
            assertEquals(254, entries[0][ScoutingEntries.targetTeamNumber])
            assertEquals(1, entries[0][ScoutingEntries.matchNumber])

            val settings = AppSettings.selectAll().toList()
            assertEquals(1, settings.size)
            assertTrue(settings[0][AppSettings.settingsJson].contains("2026TEST"))
        }
    }

    @Test
    fun testPruneOldSnapshotsRetention() {
        val oldFile = File(testDir, "obsidianscout_snapshot_old.db")
        oldFile.writeBytes(ByteArray(1024))
        oldFile.setLastModified(System.currentTimeMillis() - 45L * 24 * 60 * 60 * 1000)

        val freshFile = File(testDir, "obsidianscout_snapshot_fresh.db")
        freshFile.writeBytes(ByteArray(1024))
        freshFile.setLastModified(System.currentTimeMillis())

        val pruned = SnapshotService.pruneOldSnapshots(retentionDays = 30)
        assertEquals(1, pruned)
        assertFalse(oldFile.exists())
        assertTrue(freshFile.exists())
    }

    @Test
    fun testAutoBackupSchedulerNextRunCalculation() {
        val nextUtc = AutoBackupScheduler.computeNextRunUtc("02:54")
        assertTrue(nextUtc.isNotBlank())
        assertTrue(nextUtc.contains("02:54") || nextUtc.contains("T02:54"))
    }
}
