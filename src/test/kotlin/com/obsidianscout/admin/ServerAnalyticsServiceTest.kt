package com.obsidianscout.admin

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAnalyticsServiceTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val now = 1_000_000 * minute

    @BeforeTest
    fun reset() = ServerAnalyticsService.resetForTest()

    private fun traffic(
        device: String = "device-1",
        platform: String = "Android",
        version: String = "1.2.2",
        micros: Long = 2_000,
        status: Int = 200,
        at: Long = now
    ) = ServerAnalyticsService.recordTraffic(device, platform, version, micros, status, at)

    private fun node(
        ip: String,
        latency: LatencyMetrics = LatencyMetrics(),
        fleet: ClientFleetMetrics = ClientFleetMetrics(),
        pool: DatabasePoolMetrics = DatabasePoolMetrics(),
        security: SecurityInsightsMetrics = SecurityInsightsMetrics(),
        devices: List<DeviceSummary> = emptyList()
    ) = NodeAnalyticsSnapshot(
        ServerAnalyticsOverview(
            nodeIp = ip,
            dbPool = pool,
            webSockets = WebSocketMetrics(),
            clientFleet = fleet,
            jvm = JvmDiagnosticsMetrics(),
            latency = latency,
            security = security
        ),
        devices
    )

    @Test
    fun `requests per second comes from the last minute, not the size of the sample buffer`() {
        repeat(120) { traffic(at = now) }
        assertEquals(2.0, ServerAnalyticsService.getLatencyMetrics(now).requestsPerSecond)

        val idle = ServerAnalyticsService.getLatencyMetrics(now + 2 * minute)
        assertEquals(0.0, idle.requestsPerSecond)
        assertEquals(120, idle.sampleCount)
    }

    @Test
    fun `latency percentiles keep only the newest samples`() {
        repeat(2000) { traffic(micros = 1_000) }
        repeat(2000) { traffic(micros = 9_000) }

        val latency = ServerAnalyticsService.getLatencyMetrics(now)
        assertEquals(2000, latency.sampleCount)
        assertEquals(9.0, latency.p50Ms)
        assertEquals(4000, latency.totalRequests)
    }

    @Test
    fun `error classes are counted apart`() {
        traffic(status = 404)
        traffic(status = 429)
        traffic(status = 503)

        val latency = ServerAnalyticsService.getLatencyMetrics(now)
        assertEquals(2, latency.error4xxCount)
        assertEquals(1, latency.error5xxCount)
    }

    @Test
    fun `tracked devices stop growing at the cap however many ids callers invent`() {
        repeat(ServerAnalyticsService.MAX_TRACKED_DEVICES + 500) { traffic(device = "forged-$it") }

        assertEquals(
            ServerAnalyticsService.MAX_TRACKED_DEVICES,
            ServerAnalyticsService.getClientFleetMetrics(now).totalActiveDevices
        )
    }

    @Test
    fun `expired devices are cleaned up by traffic alone, freeing room for new ones`() {
        repeat(ServerAnalyticsService.MAX_TRACKED_DEVICES) { traffic(device = "old-$it") }

        traffic(device = "new", at = now + 2 * hour)

        assertEquals(listOf("new"), ServerAnalyticsService.getLocalDevices(now + 2 * hour).map { it.deviceId })
    }

    @Test
    fun `a device that updates shows its new version`() {
        traffic(version = "1.2.2")
        traffic(version = "1.2.3", at = now + minute)

        assertEquals(mapOf("1.2.3" to 1), ServerAnalyticsService.getClientFleetMetrics(now + minute).clientsByVersion)
    }

    @Test
    fun `a rolling counter forgets what left its window and survives slot reuse`() {
        val counter = RollingCounter(60, 1000L)
        repeat(5) { counter.add(now) }

        assertEquals(5, counter.total(now + 59_000))
        assertEquals(0, counter.total(now + 60_000))

        counter.add(now + 60_000) // Same slot, a window later
        assertEquals(1, counter.total(now + 60_000))
    }

    @Test
    fun `a sample ring keeps the latest values`() {
        val ring = SampleRing(3)
        assertEquals(0, ring.snapshot().size)

        (1L..5L).forEach(ring::add)

        assertEquals(listOf(3L, 4L, 5L), ring.snapshot().sorted())
    }

    @Test
    fun `failed logins and rate limits are counted and audited, newest first`() {
        ServerAnalyticsService.recordAuthFailure("10.0.0.1", "someone")
        ServerAnalyticsService.recordRateLimitHit("10.0.0.2", "/api/auth/login")

        val security = ServerAnalyticsService.getSecurityInsights()
        assertEquals(1, security.failedLoginsLastHour)
        assertEquals(1, security.rateLimitHitsLastHour)
        assertEquals(listOf("RATE_LIMIT", "AUTH_FAILURE"), security.recentAuditLogs.map { it.action })
    }

    @Test
    fun `the audit trail is bounded and long usernames are cut`() {
        repeat(150) { ServerAnalyticsService.recordAuthFailure("10.0.0.1", "x".repeat(500)) }

        val logs = ServerAnalyticsService.getSecurityInsights().recentAuditLogs
        assertEquals(100, logs.size)
        assertEquals(64, logs.first().username.length)
    }

    @Test
    fun `combining nothing gives nothing`() {
        assertNull(ServerAnalyticsService.combineSnapshots(emptyList()))
    }

    @Test
    fun `a device seen by two nodes counts once across the cluster`() {
        val phone = DeviceSummary("phone", "Android", "1.2.2", now)
        val laptop = DeviceSummary("laptop", "Windows", "1.2.2", now)

        val combined = ServerAnalyticsService.combineSnapshots(
            listOf(
                node("a", devices = listOf(phone, laptop)),
                node("b", devices = listOf(phone.copy(version = "1.2.3", lastSeen = now + 1)))
            )
        )!!

        assertEquals("all", combined.nodeIp)
        assertEquals(2, combined.clientFleet.totalActiveDevices)
        assertEquals(mapOf("1.2.3" to 1, "1.2.2" to 1), combined.clientFleet.clientsByVersion)
    }

    @Test
    fun `cluster latency shows the slowest node and traffic adds up`() {
        val combined = ServerAnalyticsService.combineSnapshots(
            listOf(
                node("a", latency = LatencyMetrics(p95Ms = 10.0, requestsPerSecond = 1.5, totalRequests = 10)),
                node("b", latency = LatencyMetrics(p95Ms = 80.0, requestsPerSecond = 2.5, totalRequests = 30))
            )
        )!!

        assertEquals(80.0, combined.latency.p95Ms)
        assertEquals(4.0, combined.latency.requestsPerSecond)
        assertEquals(40, combined.latency.totalRequests)
    }

    @Test
    fun `cluster sync lag is weighted by how many uploads each node took`() {
        val combined = ServerAnalyticsService.combineSnapshots(
            listOf(
                node("a", fleet = ClientFleetMetrics(avgIngestLagMs = 100, maxIngestLagMs = 500, syncSubmissionsCount = 3)),
                node("b", fleet = ClientFleetMetrics(avgIngestLagMs = 500, maxIngestLagMs = 900, syncSubmissionsCount = 1))
            )
        )!!

        assertEquals(200, combined.clientFleet.avgIngestLagMs)
        assertEquals(900, combined.clientFleet.maxIngestLagMs)
        assertEquals(4, combined.clientFleet.syncSubmissionsCount)
    }

    @Test
    fun `one unhealthy pool makes the cluster unhealthy and saturation is recomputed`() {
        val combined = ServerAnalyticsService.combineSnapshots(
            listOf(
                node("a", pool = DatabasePoolMetrics(activeConnections = 64, maxPoolSize = 64, isHealthy = false)),
                node("b", pool = DatabasePoolMetrics(activeConnections = 0, maxPoolSize = 64))
            )
        )!!

        assertFalse(combined.dbPool.isHealthy)
        assertEquals(50.0, combined.dbPool.saturationPercent)
    }

    @Test
    fun `cluster audit logs merge newest first`() {
        fun item(at: Long) = SecurityAuditItem(
            timestamp = at, action = "A", username = "u", role = "r", ipAddress = "i", details = "d"
        )

        val combined = ServerAnalyticsService.combineSnapshots(
            listOf(
                node("a", security = SecurityInsightsMetrics(failedLoginsLastHour = 1, recentAuditLogs = listOf(item(1), item(3)))),
                node("b", security = SecurityInsightsMetrics(failedLoginsLastHour = 2, recentAuditLogs = listOf(item(2))))
            )
        )!!

        assertEquals(listOf(3L, 2L, 1L), combined.security.recentAuditLogs.map { it.timestamp })
        assertEquals(3, combined.security.failedLoginsLastHour)
    }

    @Test
    fun `the monitoring pages' own polling stays out of the figures`() {
        assertFalse(LoadProfiler.tracks("/api/admin/cluster/analytics/overview"))
        assertFalse(LoadProfiler.tracks("/api/admin/cluster/nodes/local/analytics"))
        assertFalse(LoadProfiler.tracks("/api/admin/cluster/nodes/local/thread-dump"))
        assertTrue(LoadProfiler.tracks("/api/scouting"))
    }

    @Test
    fun `analytics routes open to a browser accept superadmins only`() {
        val projectDir = System.getProperty("obsidianscout.projectDir")
        val routes = java.io.File(projectDir, "src/main/kotlin/com/obsidianscout/routes/AdminRoutes.kt").readText()

        for (path in listOf("/analytics/overview", "/analytics/thread-dump", "/metrics/prometheus")) {
            val firstLine = routes.substringAfter("get(\"$path\") {").trim().lineSequence().first().trim()
            assertEquals("call.requireSuperAdmin()", firstLine, path)
        }
    }

    @Test
    fun `prometheus output types counters as counters`() {
        val text = ServerAnalyticsService.getPrometheusMetrics()

        assertTrue("# TYPE http_requests_total counter" in text)
        assertTrue("# TYPE jvm_threads_live gauge" in text)
    }
}
