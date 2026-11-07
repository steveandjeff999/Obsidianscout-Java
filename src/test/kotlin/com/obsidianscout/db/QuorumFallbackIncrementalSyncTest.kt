package com.obsidianscout.db

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.QuorumFallbackConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.orchestration.CockroachOrchestrator
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fallback mirror writes only what changed instead of emptying and refilling every table. */
class QuorumFallbackIncrementalSyncTest {

    private val stamp = System.nanoTime()
    private val mainDbFile = File("build/test_incremental_main_$stamp.db")
    private val mirrorDbFile = File("build/test_incremental_mirror_$stamp.db")

    @BeforeTest
    fun setUp() {
        mainDbFile.parentFile?.mkdirs()
        DatabaseFactory.isCockroach = false
        CockroachOrchestrator.isQuorumLost = false
        DatabaseFactory.init(
            DatabaseConfig(type = "sqlite", sqlite = SqliteConfig(file = mainDbFile.absolutePath)),
            runMigration = true,
            isCockroach = false
        )
        initMirror(QuorumFallbackConfig(enabled = true, sqlite_file = mirrorDbFile.absolutePath))
    }

    @AfterTest
    fun tearDown() {
        QuorumFallbackStore.disableAndPurge(updateConfigFile = false)
        DatabaseFactory.close()
        for (suffix in listOf("", "-wal", "-shm")) File(mainDbFile.path + suffix).delete()
    }

    private fun initMirror(config: QuorumFallbackConfig) {
        QuorumFallbackStore.init(AppConfig(quorum_fallback = config))
        assertTrue(QuorumFallbackStore.isAvailable, QuorumFallbackStore.lastSyncStatus)
    }

    private fun sync(forceFull: Boolean = false): QuorumFallbackStore.SyncResult {
        assertTrue(QuorumFallbackStore.syncFromCockroach(forceFull), QuorumFallbackStore.lastSyncStatus)
        return assertNotNull(QuorumFallbackStore.lastSyncResult)
    }

    private fun <T> source(block: () -> T): T = transaction(DatabaseFactory.primaryDatabase) { block() }

    private fun <T> mirror(block: () -> T): T = try {
        transaction(QuorumFallbackStore.sqliteDb) { block() }
    } finally {
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
    }

    private fun addUser(name: String, role: String = "SCOUTER", picture: String? = null): UUID {
        val userId = UUID.randomUUID()
        source {
            Users.insert {
                it[id] = EntityID(userId, Users)
                it[username] = name
                it[teamNumber] = 5454
                it[passwordHash] = "hash"
                it[this.role] = role
                it[createdAt] = Instant.now()
                it[profilePicture] = picture
            }
        }
        return userId
    }

    private fun addEntry(userId: UUID, data: String): UUID {
        val entryId = UUID.randomUUID()
        val now = Instant.now()
        source {
            ScoutingEntries.insert {
                it[id] = EntityID(entryId, ScoutingEntries)
                it[ownerTeamNumber] = 5454
                it[eventKey] = "2026test"
                it[matchNumber] = 1
                it[dataJson] = data
                it[submittedByUserId] = EntityID(userId, Users)
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return entryId
    }

    private fun mirroredEntry(entryId: UUID): ResultRow? =
        mirror { ScoutingEntries.selectAll().where { ScoutingEntries.id eq entryId }.singleOrNull() }

    @Test
    fun quietResyncWritesNothing() {
        val user = addUser("alice")
        addEntry(user, """{"auto":3}""")

        val first = sync()
        assertTrue(first.fullCompare, "The first sync after init compares full rows")
        assertTrue(first.rowsWritten >= 2, "Seeded rows are copied: $first")

        val quick = sync()
        assertFalse(quick.fullCompare)
        assertEquals(0, quick.rowsWritten, "Nothing changed, so nothing is written")
        assertEquals(0, quick.rowsDeleted)

        // Every column of every mirrored row must compare equal after a round trip through SQLite.
        val full = sync(forceFull = true)
        assertTrue(full.fullCompare)
        assertEquals(0, full.rowsWritten, "A full compare of unchanged data writes nothing")
        assertEquals(0, full.rowsDeleted)
    }

    @Test
    fun editThatBumpsUpdatedAtIsCopiedByQuickCheck() {
        val user = addUser("bob")
        val entry = addEntry(user, """{"auto":1}""")
        addEntry(user, """{"auto":2}""")
        sync()

        source {
            ScoutingEntries.update({ ScoutingEntries.id eq entry }) {
                it[dataJson] = """{"auto":9}"""
                it[updatedAt] = Instant.now().plusSeconds(5)
            }
        }

        val result = sync()
        assertFalse(result.fullCompare)
        assertEquals(1, result.rowsWritten, "Only the edited row is written")
        assertEquals("""{"auto":9}""", mirroredEntry(entry)?.get(ScoutingEntries.dataJson))
    }

    @Test
    fun editThatSkipsUpdatedAtIsCaughtByFullCompare() {
        val user = addUser("carol")
        val entry = addEntry(user, """{"auto":1}""")
        sync()

        // Some writes (e.g. discrepancy flags) don't bump updated_at.
        source { ScoutingEntries.update({ ScoutingEntries.id eq entry }) { it[hasDiscrepancy] = true } }

        val quick = sync()
        assertEquals(0, quick.rowsWritten, "The quick check only sees timestamps")
        assertEquals(false, mirroredEntry(entry)?.get(ScoutingEntries.hasDiscrepancy))

        val full = sync(forceFull = true)
        assertEquals(1, full.rowsWritten)
        assertEquals(true, mirroredEntry(entry)?.get(ScoutingEntries.hasDiscrepancy))
    }

    @Test
    fun deletedRowsAreRemovedByQuickCheck() {
        val user = addUser("dave")
        val gone = addEntry(user, """{"auto":1}""")
        val kept = addEntry(user, """{"auto":2}""")
        sync()

        source { ScoutingEntries.deleteWhere { ScoutingEntries.id eq gone } }

        val result = sync()
        assertFalse(result.fullCompare)
        assertEquals(1, result.rowsDeleted)
        assertEquals(0, result.rowsWritten)
        assertNull(mirroredEntry(gone))
        assertNotNull(mirroredEntry(kept))
    }

    @Test
    fun userChangesAreComparedOnEverySync() {
        val user = addUser("erin", role = "ADMIN")
        sync()

        source { Users.update({ Users.id eq user }) { it[role] = "SCOUTER" } }

        val result = sync()
        assertFalse(result.fullCompare)
        assertEquals(1, result.rowsWritten, "A demotion reaches the mirror without waiting for a full compare")
        assertEquals("SCOUTER", mirror { Users.selectAll().where { Users.id eq user }.single()[Users.role] })
    }

    @Test
    fun profilePicturesStayOutOfTheMirror() {
        val user = addUser("frank", picture = "data:image/png;base64,AAAA")
        sync()

        assertNull(mirror { Users.selectAll().where { Users.id eq user }.single()[Users.profilePicture] })
        assertEquals(0, sync(forceFull = true).rowsWritten, "The left-out column doesn't count as a difference")
    }

    @Test
    fun turningOffACategoryEmptiesItsTables() {
        addUser("grace")
        sync()
        assertTrue(mirror { Users.selectAll().count() } > 0)

        initMirror(QuorumFallbackConfig(enabled = true, sqlite_file = mirrorDbFile.absolutePath, mirror_users = false))
        val result = sync()
        assertTrue(result.rowsDeleted > 0)
        assertEquals(0L, mirror { Users.selectAll().count() })
    }

    @Test
    fun timestampsCompareEqualAfterSqliteRoundTrip() {
        // CockroachDB hands back microseconds; the mirror must not see that as a change on every sync.
        val precise = Instant.parse("2026-11-06T12:34:56.123456789Z")
        val userId = UUID.randomUUID()
        mirror {
            Users.insert {
                it[id] = EntityID(userId, Users)
                it[username] = "precise"
                it[teamNumber] = 1
                it[passwordHash] = "hash"
                it[role] = "SCOUTER"
                it[createdAt] = precise
            }
        }
        val stored = mirror { Users.selectAll().where { Users.id eq userId }.single()[Users.createdAt] }

        assertEquals(precise.truncatedTo(ChronoUnit.MILLIS), stored.truncatedTo(ChronoUnit.MILLIS), "SQLite keeps milliseconds")
        assertEquals(QuorumFallbackStore.normalizeForCompare(precise), QuorumFallbackStore.normalizeForCompare(stored))
        assertEquals(userId, QuorumFallbackStore.normalizeForCompare(EntityID(userId, Users)))
    }
}
