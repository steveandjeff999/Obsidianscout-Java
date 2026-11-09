package com.obsidianscout.security

import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.SecurityConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.IpBlocksTable
import com.obsidianscout.db.SecurityEventsTable
import com.obsidianscout.db.readTransaction
import com.obsidianscout.db.writeTransactionWithRetry
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
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

/** The parts that persist: blocks, the security log, unblock requests and the allowlist, against a real SQLite file. */
class SecurityDatabaseTest {
    private val dbFile = File("build/test_security_${System.nanoTime()}.db")

    @BeforeTest
    fun setUp() {
        SecurityTestSupport.reset(SecurityConfig(mode = "enforce"))
        dbFile.parentFile?.mkdirs()
        DatabaseFactory.init(DatabaseConfig(type = "sqlite", sqlite = SqliteConfig(file = dbFile.path)), runMigration = true, isCockroach = false)
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
        dbFile.delete()
    }

    @Test
    fun `blocks are saved, reloaded on refresh and lifted in the database`() {
        val block = assertNotNull(BlockList.manualBlock("203.0.113.10", 30, "saved", "admin"))
        BlockList.clearForTests()
        assertNull(BlockList.match("203.0.113.10"))
        BlockList.refresh()
        assertNotNull(BlockList.match("203.0.113.10"))

        val listed = BlockList.list(activeOnly = true).single()
        assertEquals("saved", listed.reason)
        assertTrue(listed.active)

        BlockList.lift(block.id, "admin", "done", resetStrikes = true, allowlistFor = "none")
        BlockList.refresh()
        assertNull(BlockList.match("203.0.113.10"))
        val row = readTransaction { IpBlocksTable.selectAll().where { IpBlocksTable.id eq block.id }.single() }
        assertEquals("admin", row[IpBlocksTable.liftedBy])
        assertEquals(0, BlockList.list(activeOnly = true).size)
        assertEquals(1, BlockList.list(activeOnly = false).size)
    }

    @Test
    fun `a row written with a longer block is still held to an hour`() {
        val id = UUID.randomUUID()
        val now = Instant.now()
        writeTransactionWithRetry {
            IpBlocksTable.insert {
                it[IpBlocksTable.id] = id
                it[ipOrCidr] = "203.0.113.11"
                it[reason] = "edited by hand"
                it[createdAt] = now
                it[expiresAt] = now.plusSeconds(24 * 3600)
                it[createdBy] = "someone"
            }
        }
        BlockList.refresh()
        val block = assertNotNull(BlockList.match("203.0.113.11"))
        assertTrue(block.expiresAt - block.createdAt <= BlockList.MAX_BLOCK_MS)
    }

    @Test
    fun `events are written in batches, searchable, and old ones are removed`() {
        SecurityEvents.emit(SecurityEvent(SecurityEventType.SCANNER_PATH, "203.0.113.20", path = "/.env", details = "Probe for /.env"))
        SecurityEvents.emit(SecurityEvent(SecurityEventType.AUTH_FAILURE, "203.0.113.21", username = "Alice"))
        SecurityEvents.emit(SecurityEvent(SecurityEventType.ADMIN_ACTION, "203.0.113.22", username = "root",
            at = System.currentTimeMillis() - 100L * 86_400_000L))
        SecurityLog.flush()

        assertEquals(3, SecurityLog.query().total)
        assertEquals(1, SecurityLog.query(ip = "203.0.113.20").total)
        assertEquals("SCANNER_PATH", SecurityLog.query(type = "scanner_path").events.single().type)
        assertEquals(1, SecurityLog.query(user = "ali").total)
        assertEquals(1, SecurityLog.query(severity = "MEDIUM").total)

        val (timeline, byType) = SecurityLog.timeline(24)
        assertEquals(24, timeline.size)
        assertEquals(1, byType["SCANNER_PATH"])

        // A 100-day-old superadmin action outlives the 90-day window; ordinary events don't.
        writeTransactionWithRetry {
            SecurityEventsTable.insert {
                it[at] = Instant.now().minusSeconds(95L * 86_400)
                it[type] = "ROUTE_SCAN"
                it[severity] = "MEDIUM"
                it[ip] = "203.0.113.23"
            }
        }
        SecurityLog.applyRetention()
        assertEquals(0, SecurityLog.query(ip = "203.0.113.23").total)
        assertEquals(1, SecurityLog.query(type = "ADMIN_ACTION").total)
    }

    @Test
    fun `unblock requests and the allowlist persist`() {
        val ip = "203.0.113.30"
        val block = assertNotNull(BlockList.manualBlock(ip, 60, "test", "admin"))
        val challenge = ProofOfWork.issue(ip, difficulty = 8)
        val request = UnblockRequests.submit(ip, UnblockRequestSubmission(
            name = "Pat", teamNumber = 99, email = "pat@example.com", message = "At the event",
            challenge = "${challenge.token}:${ProofOfWork.solve(challenge.nonce, 8)}"
        ))
        val pending = UnblockRequests.list("pending").single()
        assertEquals(request.id, pending.id)
        assertEquals("test", pending.blockReason)
        assertEquals(request.id, BlockList.list(activeOnly = true).single().pendingRequestId)

        UnblockRequests.approve(request.id, "admin", "", resetStrikes = true, allowlistFor = "7d")
        assertEquals("approved", UnblockRequests.get(request.id)?.status)
        assertFalse(assertNotNull(BlockList.find(block.id)).active)
        assertTrue(Allowlist.isAllowed(ip))
        Allowlist.clearForTests()
        Allowlist.refresh()
        assertTrue(Allowlist.isAllowed(ip), "the allowlist entry is in the database")

        val entry = Allowlist.list().single { !it.fromConfig }
        assertTrue(Allowlist.remove(entry.id))
        assertFalse(Allowlist.isAllowed(ip))
    }

    @Test
    fun `waiting requests expire with their block`() {
        val ip = "203.0.113.40"
        val block = assertNotNull(BlockList.manualBlock(ip, 60, "test", "admin"))
        val challenge = ProofOfWork.issue(ip, difficulty = 8)
        val request = UnblockRequests.submit(ip, UnblockRequestSubmission(
            name = "Sam", message = "Hi", challenge = "${challenge.token}:${ProofOfWork.solve(challenge.nonce, 8)}"
        ))
        writeTransactionWithRetry {
            IpBlocksTable.update({ IpBlocksTable.id eq block.id }) {
                it[IpBlocksTable.expiresAt] = Instant.now().minusSeconds(5)
            }
        }
        BlockList.refresh()
        UnblockRequests.expireStale()
        assertEquals("expired", UnblockRequests.get(request.id)?.status)
    }
}
