package com.obsidianscout.integrations

import com.obsidianscout.db.ClusterNotificationLocks
import com.obsidianscout.db.SyncStatuses
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Leases, leader election and shared sync status against a real (SQLite) database. */
class ClusterSyncTest {

    private val dbFile = File("build/test_cluster_sync_${System.nanoTime()}.db")
    private val t0 = Instant.parse("2026-10-23T12:00:00Z")

    @BeforeTest
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(ClusterNotificationLocks, SyncStatuses)
            ClusterNotificationLocks.deleteAll()
            SyncStatuses.deleteAll()
        }
        ClusterLease.availabilityOverride = { true }
        SyncLeadership.resign()
    }

    @AfterTest
    fun tearDown() {
        ClusterLease.availabilityOverride = null
        dbFile.delete()
    }

    @Test
    fun testOnlyOneHolderUntilExpiry() {
        assertEquals(ClusterLease.Result.ACQUIRED, ClusterLease.tryAcquire("k", 60, holder = "A", now = t0))
        assertEquals(ClusterLease.Result.HELD_ELSEWHERE, ClusterLease.tryAcquire("k", 60, holder = "B", now = t0.plusSeconds(30)))
        // The holder can always renew.
        assertEquals(ClusterLease.Result.ACQUIRED, ClusterLease.tryAcquire("k", 60, holder = "A", now = t0.plusSeconds(30)))
        assertEquals("A", ClusterLease.holderOf("k", t0.plusSeconds(60)))
    }

    @Test
    fun testExpiredLeaseCanBeTakenOverAndOldHolderLosesIt() {
        ClusterLease.tryAcquire("k", 60, holder = "A", now = t0)
        assertEquals(ClusterLease.Result.ACQUIRED, ClusterLease.tryAcquire("k", 60, holder = "B", now = t0.plusSeconds(61)))
        assertEquals(ClusterLease.Result.HELD_ELSEWHERE, ClusterLease.tryAcquire("k", 60, holder = "A", now = t0.plusSeconds(62)))
        assertEquals("B", ClusterLease.holderOf("k", t0.plusSeconds(62)))
    }

    @Test
    fun testStaleClaimLosesTheCompareAndSet() {
        ClusterLease.tryAcquire("k", 60, holder = "A", now = t0)
        // B saw A's lease expire and claims it...
        assertTrue(transaction { ClusterLease.claim("k", "B", 60, t0.plusSeconds(61)) })
        // ...while C, which also saw it expired, arrives after B: it must not overwrite B.
        assertFalse(transaction { ClusterLease.claim("k", "C", 60, t0.plusSeconds(61)) })
        assertEquals("B", ClusterLease.holderOf("k", t0.plusSeconds(62)))
    }

    @Test
    fun testConcurrentClaimantsGetExactlyOneWinner() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..8).map { n ->
            pool.submit<ClusterLease.Result> {
                start.await()
                ClusterLease.tryAcquire("race", 60, holder = "node$n", now = t0)
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, outcomes.count { it == ClusterLease.Result.ACQUIRED }, "outcomes: $outcomes")
        assertEquals(7, outcomes.count { it == ClusterLease.Result.HELD_ELSEWHERE }, "outcomes: $outcomes")
    }

    @Test
    fun testReleaseOnlyRemovesOwnLease() {
        ClusterLease.tryAcquire("k", 60, holder = "A", now = t0)
        ClusterLease.release("k", holder = "B")
        assertEquals("A", ClusterLease.holderOf("k", t0.plusSeconds(1)))
        ClusterLease.release("k", holder = "A")
        assertNull(ClusterLease.holderOf("k", t0.plusSeconds(1)))
        assertEquals(ClusterLease.Result.ACQUIRED, ClusterLease.tryAcquire("k", 60, holder = "B", now = t0.plusSeconds(2)))
    }

    @Test
    fun testUnavailableDatabaseIsReportedNotTreatedAsHeld() {
        ClusterLease.availabilityOverride = { false }
        assertEquals(ClusterLease.Result.UNAVAILABLE, ClusterLease.tryAcquire("k", 60, holder = "A", now = t0))
    }

    @Test
    fun testLeaderElectionPicksOneNodeAndHandsOver() {
        val now = Instant.now()
        // Another node holds leadership: this node must not run the scheduled sync.
        ClusterLease.tryAcquire(SyncLeadership.LEASE_KEY, SyncLeadership.TTL_SECONDS, holder = "other-node", now = now)
        assertFalse(SyncLeadership.refresh(now))
        assertFalse(SyncLeadership.isLeader(now))

        // Its lease runs out without renewal: this node takes over.
        val later = now.plusSeconds(SyncLeadership.TTL_SECONDS + 1)
        assertTrue(SyncLeadership.refresh(later))
        assertTrue(SyncLeadership.isLeader(later))
        assertEquals(ClusterLease.nodeId, ClusterLease.holderOf(SyncLeadership.LEASE_KEY, later))

        // The other node now can't claim it.
        assertEquals(
            ClusterLease.Result.HELD_ELSEWHERE,
            ClusterLease.tryAcquire(SyncLeadership.LEASE_KEY, SyncLeadership.TTL_SECONDS, holder = "other-node", now = later.plusSeconds(1))
        )
    }

    @Test
    fun testLeaderStopsBeforeAnyoneElseCanTakeOver() {
        val now = Instant.now()
        assertTrue(SyncLeadership.refresh(now))
        // No renewal (e.g. lost the database): it stops calling itself leader after LOCAL_VALIDITY...
        val stopsAt = now.plusSeconds(SyncLeadership.LOCAL_VALIDITY_SECONDS)
        assertFalse(SyncLeadership.isLeader(stopsAt))
        // ...which is well before the lease expires and another node may claim it.
        assertTrue(SyncLeadership.LOCAL_VALIDITY_SECONDS + 15 < SyncLeadership.TTL_SECONDS)
        assertEquals(
            ClusterLease.Result.HELD_ELSEWHERE,
            ClusterLease.tryAcquire(SyncLeadership.LEASE_KEY, SyncLeadership.TTL_SECONDS, holder = "other-node", now = stopsAt)
        )
    }

    @Test
    fun testLosingTheDatabaseEndsLeadership() {
        val now = Instant.now()
        assertTrue(SyncLeadership.refresh(now))
        ClusterLease.availabilityOverride = { false }
        assertFalse(SyncLeadership.refresh(now.plusSeconds(15)))
        assertFalse(SyncLeadership.isLeader(now.plusSeconds(15)))
    }

    @Test
    fun testResignHandsLeadershipOverImmediately() {
        val now = Instant.now()
        assertTrue(SyncLeadership.refresh(now))
        SyncLeadership.resign()
        assertFalse(SyncLeadership.isLeader(now))
        assertEquals(
            ClusterLease.Result.ACQUIRED,
            ClusterLease.tryAcquire(SyncLeadership.LEASE_KEY, SyncLeadership.TTL_SECONDS, holder = "other-node", now = now)
        )
    }

    @Test
    fun testStatusIsSharedWithOtherNodes() {
        val status = SyncScheduler.getStatusForTeam(9001, "FRC")
        status.lastSyncSummary = "Auto-sync complete from TBA: 40 teams, 80 matches"
        status.lastSyncTeams = 40
        SyncScheduler.persistStatus(9001, "FRC")

        // Another node wrote a newer status: a sync it is running right now.
        val future = Instant.now().plusSeconds(600).toEpochMilli()
        writeStoredStatus(9001, """{"lastSyncSummary":"Manual sync started","syncInProgress":true,"currentSyncLabel":"Manual sync","inProgressUntil":$future,"updatedAt":${Instant.now().plusSeconds(5).toEpochMilli()}}""")
        val shown = SyncScheduler.statusForDisplay(9001, "FRC")
        assertTrue(shown.syncInProgress)
        assertEquals("Manual sync", shown.currentSyncLabel)
    }

    @Test
    fun testAbandonedInProgressStatusExpires() {
        val past = Instant.now().minusSeconds(60).toEpochMilli()
        writeStoredStatus(9002, """{"lastSyncSummary":"Auto-sync started","syncInProgress":true,"currentSyncLabel":"Auto-sync","inProgressUntil":$past,"updatedAt":${Instant.now().toEpochMilli()}}""")
        val shown = SyncScheduler.statusForDisplay(9002, "FRC")
        assertFalse(shown.syncInProgress)
        assertNull(shown.currentSyncLabel)
    }

    private fun writeStoredStatus(team: Int, json: String) {
        transaction {
            SyncStatuses.deleteAll()
            SyncStatuses.insert {
                it[teamNumber] = team
                it[program] = "FRC"
                it[statusJson] = json
                it[updatedAt] = Instant.now()
            }
        }
    }
}
