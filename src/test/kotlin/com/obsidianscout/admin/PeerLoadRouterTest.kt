package com.obsidianscout.admin

import com.obsidianscout.db.AppSettings
import com.obsidianscout.integrations.LoadBalancerSettings
import com.obsidianscout.integrations.SettingsService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerLoadRouterTest {

    private var testDbFile = File("build/test_peer_load.db")

    @BeforeTest
    fun setUp() {
        testDbFile = File("build/test_peer_load_${System.currentTimeMillis()}_${(0..9999).random()}.db")
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        val config = com.obsidianscout.config.DatabaseConfig(
            type = "sqlite",
            sqlite = com.obsidianscout.config.SqliteConfig(file = testDbFile.path)
        )
        com.obsidianscout.db.DatabaseFactory.init(config, runMigration = true, isCockroach = false)
    }

    @AfterTest
    fun tearDown() {
        com.obsidianscout.db.DatabaseFactory.close()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testComputeScoreFormula() {
        // Perfect node: 100% available heap, 0% CPU, 0ms latency -> score = 1.0
        val perfectScore = PeerLoadRouter.computeScore(
            availableHeapMb = 1000L,
            maxHeapMb = 1000L,
            cpuLoad = 0.0,
            latencyMs = 0L,
            maxExpectedLatencyMs = 150.0
        )
        assertEquals(1.0, perfectScore)

        // Heavily loaded node: 0% available heap, 100% CPU, 150ms+ latency -> score = 0.0
        val loadedScore = PeerLoadRouter.computeScore(
            availableHeapMb = 0L,
            maxHeapMb = 1000L,
            cpuLoad = 1.0,
            latencyMs = 200L,
            maxExpectedLatencyMs = 150.0
        )
        assertEquals(0.0, loadedScore)

        // Balanced node: 50% heap (0.25), 50% CPU (0.15), 75ms latency (0.10) -> score = 0.50
        val balancedScore = PeerLoadRouter.computeScore(
            availableHeapMb = 500L,
            maxHeapMb = 1000L,
            cpuLoad = 0.5,
            latencyMs = 75L,
            maxExpectedLatencyMs = 150.0
        )
        assertEquals(0.50, balancedScore)
    }

    @Test
    fun testSelectBestNodeLocalWinsOnTieAndNearTie() {
        PeerLoadRouter.updateCachedSettings(
            LoadBalancerSettings(
                enabled = true,
                localPreferenceMargin = 0.10,
                maxExpectedLatencyMs = 150.0
            )
        )

        // Simulated local node under moderate/high load (score = 0.40)
        val localLoad = NodeLoad(
            ip = "100.64.0.1",
            appPort = 8080,
            availableHeapMb = 400L,
            maxHeapMb = 1000L,
            cpuLoad = 0.60,
            latencyMs = 0L,
            score = 0.40
        )

        // Clear peers map
        PeerLoadRouter.peerLoadMap.clear()

        // 1. Peer with exact same score (0.40) -> Local wins
        val tiePeer = NodeLoad(
            ip = "100.64.0.2",
            appPort = 8080,
            availableHeapMb = 400L,
            maxHeapMb = 1000L,
            cpuLoad = 0.60,
            latencyMs = 5L,
            score = 0.40
        )
        PeerLoadRouter.peerLoadMap[tiePeer.ip] = tiePeer
        val chosenOnTie = PeerLoadRouter.selectBestNode(localLoad)
        assertEquals(localLoad.ip, chosenOnTie.ip, "Local node should win on tie due to network savings")

        // 2. Peer with score slightly better (0.45, difference 0.05 < margin 0.10) -> Local still wins
        val slightlyBetterPeer = NodeLoad(
            ip = "100.64.0.3",
            appPort = 8080,
            availableHeapMb = 500L,
            maxHeapMb = 1000L,
            cpuLoad = 0.50,
            latencyMs = 5L,
            score = 0.45
        )
        PeerLoadRouter.peerLoadMap.clear()
        PeerLoadRouter.peerLoadMap[slightlyBetterPeer.ip] = slightlyBetterPeer
        val chosenOnNearTie = PeerLoadRouter.selectBestNode(localLoad)
        assertEquals(localLoad.ip, chosenOnNearTie.ip, "Local node should win on near-tie when difference is within margin")

        // 3. Peer significantly better (0.75, difference 0.35 > margin 0.10) -> Peer wins
        val muchBetterPeer = NodeLoad(
            ip = "100.64.0.4",
            appPort = 8080,
            availableHeapMb = 4000L,
            maxHeapMb = 4000L,
            cpuLoad = 0.05,
            latencyMs = 2L,
            score = 0.75
        )
        PeerLoadRouter.peerLoadMap.clear()
        PeerLoadRouter.peerLoadMap[muchBetterPeer.ip] = muchBetterPeer
        val chosenOnMuchBetter = PeerLoadRouter.selectBestNode(localLoad)
        assertEquals(muchBetterPeer.ip, chosenOnMuchBetter.ip, "Much better peer should be selected when it exceeds the local margin")
    }

    @Test
    fun testLoadBalancerSettingsDatabaseRoundtrip() {
        val initial = SettingsService.getLoadBalancerSettings()
        assertFalse(initial.enabled)

        val updated = LoadBalancerSettings(
            enabled = true,
            probeIntervalSeconds = 10,
            forwardTimeoutSeconds = 25,
            localPreferenceMargin = 0.15,
            maxExpectedLatencyMs = 200.0,
            excludedPathPrefixes = listOf("/api/admin", "/api/cluster", "/custom-path")
        )

        SettingsService.updateLoadBalancerSettings(updated)

        val loaded = SettingsService.getLoadBalancerSettings()
        assertTrue(loaded.enabled)
        assertEquals(10, loaded.probeIntervalSeconds)
        assertEquals(25, loaded.forwardTimeoutSeconds)
        assertEquals(0.15, loaded.localPreferenceMargin)
        assertEquals(200.0, loaded.maxExpectedLatencyMs)
        assertTrue(loaded.excludedPathPrefixes.contains("/custom-path"))
    }

    @Test
    fun testActivityHistoryAndRecentStats() {
        val localNode = NodeLoad(
            ip = "100.64.0.1",
            appPort = 8080,
            availableHeapMb = 500L,
            maxHeapMb = 1000L,
            cpuLoad = 0.40,
            latencyMs = 0L,
            score = 0.50
        )
        val peerNode = NodeLoad(
            ip = "100.64.0.2",
            appPort = 8080,
            availableHeapMb = 800L,
            maxHeapMb = 1000L,
            cpuLoad = 0.10,
            latencyMs = 5L,
            score = 0.85
        )

        // Record local requests and snapshot
        PeerLoadRouter.recordLocalServed()
        PeerLoadRouter.recordLocalServed()
        PeerLoadRouter.forwardedCount.addAndGet(5)

        PeerLoadRouter.recordActivitySnapshot(peerNode, localNode)

        val status = PeerLoadRouter.getStatus()
        assertTrue(status.activityHistory.isNotEmpty(), "Activity history should contain recorded snapshot")
        val latest = status.activityHistory.last()
        assertEquals(5, latest.requestsForwarded)
        assertEquals(2, latest.requestsServedLocally)
        assertEquals("100.64.0.2", latest.targetIp)
        assertTrue(latest.isForwarded)

        val stats = status.recentStats
        assertEquals(5, stats.totalForwarded30m)
        assertEquals(2, stats.totalLocalServed30m)
        assertTrue(stats.forwardedRatio30m > 0.70)
    }
}
