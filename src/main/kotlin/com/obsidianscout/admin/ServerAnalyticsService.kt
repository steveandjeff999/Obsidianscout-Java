package com.obsidianscout.admin

import com.obsidianscout.auth.clientIp
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.NotificationWebSocketManager
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.userAgent
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
data class DatabasePoolMetrics(
    val activeConnections: Int = 0,
    val idleConnections: Int = 0,
    val threadsAwaiting: Int = 0,
    val totalConnections: Int = 0,
    val maxPoolSize: Int = 0,
    val minIdle: Int = 0,
    val saturationPercent: Double = 0.0,
    val isHealthy: Boolean = true,
    val engineType: String = "unknown"
)

@Serializable
data class WebSocketMetrics(
    val activeConnections: Int = 0,
    val connectedUsers: Int = 0,
    val connectsLastMinute: Int = 0,
    val disconnectsLastMinute: Int = 0,
    val churnRatePerMinute: Int = 0,
    val totalTopicBroadcasts: Long = 0,
    val droppedBroadcasts: Long = 0
)

@Serializable
data class ClientFleetMetrics(
    val totalActiveDevices: Int = 0,
    val clientsByPlatform: Map<String, Int> = emptyMap(),
    val clientsByVersion: Map<String, Int> = emptyMap(),
    val avgIngestLagMs: Long = 0,
    val maxIngestLagMs: Long = 0,
    val syncSubmissionsCount: Long = 0
)

@Serializable
data class JvmDiagnosticsMetrics(
    val usedHeapMb: Long = 0,
    val maxHeapMb: Long = 0,
    val heapUsagePercent: Double = 0.0,
    val nonHeapUsedMb: Long = 0,
    val gcCount: Long = 0,
    val gcTimeMs: Long = 0,
    val liveThreads: Int = 0,
    val peakThreads: Int = 0,
    val daemonThreads: Int = 0,
    val uptimeSeconds: Long = 0
)

@Serializable
data class LatencyMetrics(
    val p50Ms: Double = 0.0,
    val p90Ms: Double = 0.0,
    val p95Ms: Double = 0.0,
    val p99Ms: Double = 0.0,
    val maxMs: Double = 0.0,
    val requestsPerSecond: Double = 0.0,
    val sampleCount: Int = 0,
    val totalRequests: Long = 0,
    val error4xxCount: Long = 0,
    val error5xxCount: Long = 0
)

@Serializable
data class SecurityAuditItem(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val action: String,
    val username: String,
    val role: String,
    val ipAddress: String,
    val details: String,
    val success: Boolean = true
)

@Serializable
data class SecurityInsightsMetrics(
    val failedLoginsLastHour: Int = 0,
    val rateLimitHitsLastHour: Int = 0,
    val recentAuditLogs: List<SecurityAuditItem> = emptyList()
)

@Serializable
data class ServerAnalyticsOverview(
    val nodeIp: String,
    val timestamp: Long = System.currentTimeMillis(),
    val dbPool: DatabasePoolMetrics,
    val webSockets: WebSocketMetrics,
    val clientFleet: ClientFleetMetrics,
    val jvm: JvmDiagnosticsMetrics,
    val latency: LatencyMetrics,
    val security: SecurityInsightsMetrics
)

/** One client seen by a node. Nodes share these so a device routed to two of them is still counted once. */
@Serializable
data class DeviceSummary(
    val deviceId: String,
    val platform: String,
    val version: String,
    val lastSeen: Long
)

/** What one node sends the node answering the superadmin. */
@Serializable
data class NodeAnalyticsSnapshot(
    val overview: ServerAnalyticsOverview,
    val devices: List<DeviceSummary> = emptyList()
)

/** [combined] is the whole cluster, or null when no node answered; [nodes] are the same figures per node. */
@Serializable
data class ClusterAnalyticsOverview(
    val combined: ServerAnalyticsOverview? = null,
    val nodes: List<ServerAnalyticsOverview> = emptyList(),
    val unreachableNodes: List<String> = emptyList()
)

/** Counts events over a rolling window of fixed slots, so a flood costs no more memory than a quiet hour. */
internal class RollingCounter(private val slots: Int, private val slotMs: Long) {
    private val counts = AtomicLongArray(slots)
    private val stamps = AtomicLongArray(slots)

    fun add(nowMs: Long = System.currentTimeMillis()) {
        val stamp = nowMs / slotMs
        val slot = (stamp % slots).toInt()
        val seen = stamps.get(slot)
        if (seen != stamp && stamps.compareAndSet(slot, seen, stamp)) counts.set(slot, 0)
        counts.incrementAndGet(slot)
    }

    fun total(nowMs: Long = System.currentTimeMillis()): Long {
        val stamp = nowMs / slotMs
        return (0 until slots).sumOf { slot ->
            if (stamp - stamps.get(slot) < slots) counts.get(slot) else 0L
        }
    }

    fun reset() {
        for (slot in 0 until slots) {
            counts.set(slot, 0)
            stamps.set(slot, 0)
        }
    }
}

/** Keeps the latest [capacity] values. Writing is O(1), which matters because every request writes one. */
internal class SampleRing(private val capacity: Int) {
    private val values = AtomicLongArray(capacity)
    private val written = AtomicLong(0)

    fun add(value: Long) {
        values.set((written.getAndIncrement() % capacity).toInt(), value)
    }

    fun snapshot(): LongArray = LongArray(min(written.get(), capacity.toLong()).toInt()) { values.get(it) }

    fun reset() = written.set(0)
}

object ServerAnalyticsService {

    private const val MAX_LATENCY_SAMPLES = 2000
    private const val MAX_INGEST_LAG_SAMPLES = 500
    private const val MAX_AUDIT_LOGS = 100
    private const val DEVICE_RETENTION_MS = 60 * 60 * 1000L // 1 hour
    private const val DEVICE_PURGE_INTERVAL_MS = 60_000L
    private const val MAX_OUTBOX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // Anything older is a broken or forged header

    // Device headers come from unauthenticated callers, so what's kept of them is bounded.
    internal const val MAX_TRACKED_DEVICES = 5000
    private const val MAX_DEVICE_ID_LENGTH = 64
    private const val MAX_AUDIT_USERNAME_LENGTH = 64
    private val KNOWN_PLATFORMS = setOf("Android", "iOS", "Windows", "macOS", "Linux", "Web", "Mobile", "Unknown")
    private val VERSION_FORMAT = Regex("[0-9A-Za-z.+_-]{1,32}")

    private val latencySamples = SampleRing(MAX_LATENCY_SAMPLES)
    private val ingestLagSamples = SampleRing(MAX_INGEST_LAG_SAMPLES)
    private val auditLogs = ConcurrentLinkedDeque<SecurityAuditItem>()

    private val totalRequestsCount = AtomicLong(0)
    private val error4xxCount = AtomicLong(0)
    private val error5xxCount = AtomicLong(0)
    private val syncSubmissionsCount = AtomicLong(0)
    private val topicBroadcastsCount = AtomicLong(0)
    private val droppedBroadcastsCount = AtomicLong(0)

    private val requestsWindow = RollingCounter(60, 1000L)
    private val connectsWindow = RollingCounter(60, 1000L)
    private val disconnectsWindow = RollingCounter(60, 1000L)
    private val failedLoginsWindow = RollingCounter(60, 60_000L)
    private val rateLimitHitsWindow = RollingCounter(60, 60_000L)

    private val activeDevices = ConcurrentHashMap<String, DeviceSummary>()
    private val lastDevicePurgeMs = AtomicLong(0)

    fun recordRequest(call: ApplicationCall, durationMicros: Long, status: Int) {
        val headers = call.request.headers
        val ip = call.clientIp()
        val platform = headers["X-App-Platform"]?.trim()?.takeIf { it in KNOWN_PLATFORMS } ?: when {
            headers["X-Mobile-App"] == "true" -> "Mobile"
            else -> parsePlatformFromUserAgent(call.request.userAgent() ?: "")
        }
        val version = headers["X-App-Version"]?.trim()?.takeIf { VERSION_FORMAT.matches(it) } ?: "Web/Unknown"
        val deviceId = headers["X-Device-Id"]?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DEVICE_ID_LENGTH)
            ?: "web-$ip" // Web and anonymous requests are told apart by address

        recordTraffic(deviceId, platform, version, durationMicros, status)

        if (status == 429) recordRateLimitHit(ip, call.request.path())

        // Sent by the app with an outbox upload: how long the oldest item waited on the device. A refused upload isn't one.
        val outboxAgeMs = headers["X-Outbox-Age-Ms"]?.toLongOrNull()
        if (outboxAgeMs != null && outboxAgeMs in 0..MAX_OUTBOX_AGE_MS &&
            call.request.httpMethod == HttpMethod.Post && status in 200..299
        ) {
            recordIngestLag(outboxAgeMs)
        }
    }

    internal fun recordTraffic(
        deviceId: String,
        platform: String,
        version: String,
        durationMicros: Long,
        status: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        totalRequestsCount.incrementAndGet()
        requestsWindow.add(nowMs)
        if (status in 400..499) error4xxCount.incrementAndGet()
        if (status >= 500) error5xxCount.incrementAndGet()
        latencySamples.add(durationMicros)

        purgeExpiredDevices(nowMs)
        if (activeDevices.containsKey(deviceId) || activeDevices.size < MAX_TRACKED_DEVICES) {
            activeDevices[deviceId] = DeviceSummary(deviceId, platform, version, nowMs)
        }
    }

    private fun purgeExpiredDevices(nowMs: Long) {
        val last = lastDevicePurgeMs.get()
        if (nowMs - last < DEVICE_PURGE_INTERVAL_MS || !lastDevicePurgeMs.compareAndSet(last, nowMs)) return
        val cutoff = nowMs - DEVICE_RETENTION_MS
        activeDevices.entries.removeIf { it.value.lastSeen < cutoff }
    }

    fun recordIngestLag(lagMs: Long) {
        syncSubmissionsCount.incrementAndGet()
        ingestLagSamples.add(lagMs)
    }

    fun recordWebSocketConnect() = connectsWindow.add()

    fun recordWebSocketDisconnect() = disconnectsWindow.add()

    fun recordTopicBroadcast() {
        topicBroadcastsCount.incrementAndGet()
    }

    fun recordDroppedBroadcast() {
        droppedBroadcastsCount.incrementAndGet()
    }

    fun recordAuthFailure(ip: String, username: String) {
        failedLoginsWindow.add()
        recordSecurityAudit("AUTH_FAILURE", username, "UNKNOWN", ip, "Failed login attempt", success = false)
    }

    fun recordRateLimitHit(ip: String, endpoint: String) {
        rateLimitHitsWindow.add()
        recordSecurityAudit("RATE_LIMIT", "ANONYMOUS", "GUEST", ip, "Rate limit hit on $endpoint", success = false)
    }

    fun recordSecurityAudit(
        action: String,
        username: String,
        role: String,
        ipAddress: String,
        details: String,
        success: Boolean = true
    ) {
        val item = SecurityAuditItem(
            action = action,
            username = username.take(MAX_AUDIT_USERNAME_LENGTH),
            role = role,
            ipAddress = ipAddress,
            details = details,
            success = success
        )
        auditLogs.addFirst(item)
        while (auditLogs.size > MAX_AUDIT_LOGS) {
            auditLogs.pollLast()
        }
    }

    private fun parsePlatformFromUserAgent(ua: String): String {
        val lower = ua.lowercase()
        return when {
            lower.contains("android") -> "Android"
            lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios") -> "iOS"
            lower.contains("windows") -> "Windows"
            lower.contains("macintosh") || lower.contains("mac os") -> "macOS"
            lower.contains("linux") -> "Linux"
            ua.isBlank() -> "Unknown"
            else -> "Web"
        }
    }

    fun getDatabasePoolMetrics(): DatabasePoolMetrics {
        val ds = DatabaseFactory.activeDataSource
        if (ds == null) {
            return DatabasePoolMetrics(isHealthy = false, engineType = "none")
        }

        return try {
            val poolBean = ds.hikariPoolMXBean
            val active = poolBean?.activeConnections ?: 0
            val idle = poolBean?.idleConnections ?: 0
            val awaiting = poolBean?.threadsAwaitingConnection ?: 0
            val total = poolBean?.totalConnections ?: 0
            val maxPool = ds.maximumPoolSize
            val minIdle = ds.minimumIdle
            val saturation = if (maxPool > 0) ((active.toDouble() / maxPool.toDouble()) * 100.0) else 0.0

            DatabasePoolMetrics(
                activeConnections = active,
                idleConnections = idle,
                threadsAwaiting = awaiting,
                totalConnections = total,
                maxPoolSize = maxPool,
                minIdle = minIdle,
                saturationPercent = (saturation * 10.0).roundToInt() / 10.0,
                isHealthy = awaiting < 5 && saturation < 95.0,
                engineType = if (DatabaseFactory.isPostgresCompatible) "cockroach/postgres" else "sqlite"
            )
        } catch (e: Exception) {
            DatabasePoolMetrics(
                isHealthy = false,
                engineType = if (DatabaseFactory.isPostgresCompatible) "cockroach/postgres" else "sqlite"
            )
        }
    }

    fun getWebSocketMetrics(): WebSocketMetrics {
        val connects = connectsWindow.total().toInt()
        val disconnects = disconnectsWindow.total().toInt()

        return WebSocketMetrics(
            activeConnections = NotificationWebSocketManager.connectedSocketsCount(),
            connectedUsers = NotificationWebSocketManager.connectedUsers(),
            connectsLastMinute = connects,
            disconnectsLastMinute = disconnects,
            churnRatePerMinute = connects + disconnects,
            totalTopicBroadcasts = topicBroadcastsCount.get(),
            droppedBroadcasts = droppedBroadcastsCount.get()
        )
    }

    /** Clients seen on this node within the last hour. */
    fun getLocalDevices(nowMs: Long = System.currentTimeMillis()): List<DeviceSummary> {
        val cutoff = nowMs - DEVICE_RETENTION_MS
        return activeDevices.values.filter { it.lastSeen >= cutoff }
    }

    fun getClientFleetMetrics(nowMs: Long = System.currentTimeMillis()): ClientFleetMetrics {
        val lags = ingestLagSamples.snapshot()
        return fleetMetrics(
            devices = getLocalDevices(nowMs),
            avgLagMs = if (lags.isNotEmpty()) lags.average().toLong() else 0L,
            maxLagMs = lags.maxOrNull() ?: 0L,
            submissions = syncSubmissionsCount.get()
        )
    }

    private fun fleetMetrics(devices: List<DeviceSummary>, avgLagMs: Long, maxLagMs: Long, submissions: Long) =
        ClientFleetMetrics(
            totalActiveDevices = devices.size,
            clientsByPlatform = devices.groupingBy { it.platform }.eachCount(),
            clientsByVersion = devices.groupingBy { it.version }.eachCount(),
            avgIngestLagMs = avgLagMs,
            maxIngestLagMs = maxLagMs,
            syncSubmissionsCount = submissions
        )

    fun getJvmDiagnosticsMetrics(): JvmDiagnosticsMetrics {
        val memoryBean = ManagementFactory.getMemoryMXBean()
        val heapUsage = memoryBean.heapMemoryUsage
        val nonHeapUsage = memoryBean.nonHeapMemoryUsage

        val usedHeapMb = heapUsage.used / (1024 * 1024)
        val maxHeapMb = (if (heapUsage.max > 0) heapUsage.max else heapUsage.committed) / (1024 * 1024)
        val heapPercent = if (maxHeapMb > 0) ((usedHeapMb.toDouble() / maxHeapMb.toDouble()) * 100.0) else 0.0

        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val totalGcCount = gcBeans.sumOf { max(0L, it.collectionCount) }
        val totalGcTime = gcBeans.sumOf { max(0L, it.collectionTime) }

        val threadBean = ManagementFactory.getThreadMXBean()
        val uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1000

        return JvmDiagnosticsMetrics(
            usedHeapMb = usedHeapMb,
            maxHeapMb = maxHeapMb,
            heapUsagePercent = (heapPercent * 10.0).roundToInt() / 10.0,
            nonHeapUsedMb = nonHeapUsage.used / (1024 * 1024),
            gcCount = totalGcCount,
            gcTimeMs = totalGcTime,
            liveThreads = threadBean.threadCount,
            peakThreads = threadBean.peakThreadCount,
            daemonThreads = threadBean.daemonThreadCount,
            uptimeSeconds = uptimeSeconds
        )
    }

    fun getLatencyMetrics(nowMs: Long = System.currentTimeMillis()): LatencyMetrics {
        val samples = latencySamples.snapshot().also { it.sort() }
        val count = samples.size
        val total = totalRequestsCount.get()

        if (count == 0) {
            return LatencyMetrics(
                totalRequests = total,
                error4xxCount = error4xxCount.get(),
                error5xxCount = error5xxCount.get()
            )
        }

        fun toMs(micros: Long): Double = (micros / 10.0).roundToInt() / 100.0

        fun percentile(p: Double): Double = toMs(samples[(p * (count - 1)).toInt().coerceIn(0, count - 1)])

        return LatencyMetrics(
            p50Ms = percentile(0.50),
            p90Ms = percentile(0.90),
            p95Ms = percentile(0.95),
            p99Ms = percentile(0.99),
            maxMs = toMs(samples.last()),
            requestsPerSecond = ((requestsWindow.total(nowMs) / 60.0) * 10.0).roundToInt() / 10.0,
            sampleCount = count,
            totalRequests = total,
            error4xxCount = error4xxCount.get(),
            error5xxCount = error5xxCount.get()
        )
    }

    fun getSecurityInsights(): SecurityInsightsMetrics = SecurityInsightsMetrics(
        failedLoginsLastHour = failedLoginsWindow.total().toInt(),
        rateLimitHitsLastHour = rateLimitHitsWindow.total().toInt(),
        recentAuditLogs = auditLogs.toList()
    )

    fun getOverview(): ServerAnalyticsOverview {
        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
        return ServerAnalyticsOverview(
            nodeIp = localIp,
            dbPool = getDatabasePoolMetrics(),
            webSockets = getWebSocketMetrics(),
            clientFleet = getClientFleetMetrics(),
            jvm = getJvmDiagnosticsMetrics(),
            latency = getLatencyMetrics(),
            security = getSecurityInsights()
        )
    }

    fun getLocalSnapshot(): NodeAnalyticsSnapshot = NodeAnalyticsSnapshot(getOverview(), getLocalDevices())

    /**
     * The whole cluster from each node's figures. Percentiles can't be added up from summaries, so latency shows the
     * slowest node's, and a device seen by two nodes counts once. Null when there are no snapshots.
     */
    fun combineSnapshots(snapshots: List<NodeAnalyticsSnapshot>): ServerAnalyticsOverview? {
        if (snapshots.isEmpty()) return null
        val nodes = snapshots.map { it.overview }
        val pools = nodes.map { it.dbPool }
        val sockets = nodes.map { it.webSockets }
        val fleets = nodes.map { it.clientFleet }
        val jvms = nodes.map { it.jvm }
        val latencies = nodes.map { it.latency }

        val activeConnections = pools.sumOf { it.activeConnections }
        val maxPoolSize = pools.sumOf { it.maxPoolSize }
        val maxHeapMb = jvms.sumOf { it.maxHeapMb }
        val usedHeapMb = jvms.sumOf { it.usedHeapMb }
        val submissions = fleets.sumOf { it.syncSubmissionsCount }
        val weightedLag = fleets.sumOf { it.avgIngestLagMs * it.syncSubmissionsCount }

        return ServerAnalyticsOverview(
            nodeIp = "all",
            timestamp = nodes.maxOf { it.timestamp },
            dbPool = DatabasePoolMetrics(
                activeConnections = activeConnections,
                idleConnections = pools.sumOf { it.idleConnections },
                threadsAwaiting = pools.sumOf { it.threadsAwaiting },
                totalConnections = pools.sumOf { it.totalConnections },
                maxPoolSize = maxPoolSize,
                minIdle = pools.sumOf { it.minIdle },
                saturationPercent = percentOf(activeConnections.toLong(), maxPoolSize.toLong()),
                isHealthy = pools.all { it.isHealthy },
                engineType = pools.first().engineType
            ),
            webSockets = WebSocketMetrics(
                activeConnections = sockets.sumOf { it.activeConnections },
                connectedUsers = sockets.sumOf { it.connectedUsers },
                connectsLastMinute = sockets.sumOf { it.connectsLastMinute },
                disconnectsLastMinute = sockets.sumOf { it.disconnectsLastMinute },
                churnRatePerMinute = sockets.sumOf { it.churnRatePerMinute },
                totalTopicBroadcasts = sockets.sumOf { it.totalTopicBroadcasts },
                droppedBroadcasts = sockets.sumOf { it.droppedBroadcasts }
            ),
            clientFleet = fleetMetrics(
                devices = snapshots.flatMap { it.devices }
                    .groupBy { it.deviceId }
                    .map { (_, seen) -> seen.maxBy { it.lastSeen } },
                avgLagMs = if (submissions > 0) weightedLag / submissions else 0L,
                maxLagMs = fleets.maxOf { it.maxIngestLagMs },
                submissions = submissions
            ),
            jvm = JvmDiagnosticsMetrics(
                usedHeapMb = usedHeapMb,
                maxHeapMb = maxHeapMb,
                heapUsagePercent = percentOf(usedHeapMb, maxHeapMb),
                nonHeapUsedMb = jvms.sumOf { it.nonHeapUsedMb },
                gcCount = jvms.sumOf { it.gcCount },
                gcTimeMs = jvms.sumOf { it.gcTimeMs },
                liveThreads = jvms.sumOf { it.liveThreads },
                peakThreads = jvms.sumOf { it.peakThreads },
                daemonThreads = jvms.sumOf { it.daemonThreads },
                uptimeSeconds = jvms.minOf { it.uptimeSeconds }
            ),
            latency = LatencyMetrics(
                p50Ms = latencies.maxOf { it.p50Ms },
                p90Ms = latencies.maxOf { it.p90Ms },
                p95Ms = latencies.maxOf { it.p95Ms },
                p99Ms = latencies.maxOf { it.p99Ms },
                maxMs = latencies.maxOf { it.maxMs },
                requestsPerSecond = (latencies.sumOf { it.requestsPerSecond } * 10.0).roundToInt() / 10.0,
                sampleCount = latencies.sumOf { it.sampleCount },
                totalRequests = latencies.sumOf { it.totalRequests },
                error4xxCount = latencies.sumOf { it.error4xxCount },
                error5xxCount = latencies.sumOf { it.error5xxCount }
            ),
            security = SecurityInsightsMetrics(
                failedLoginsLastHour = nodes.sumOf { it.security.failedLoginsLastHour },
                rateLimitHitsLastHour = nodes.sumOf { it.security.rateLimitHitsLastHour },
                recentAuditLogs = nodes.flatMap { it.security.recentAuditLogs }
                    .sortedByDescending { it.timestamp }
                    .take(MAX_AUDIT_LOGS)
            )
        )
    }

    private fun percentOf(part: Long, whole: Long): Double =
        if (whole > 0) ((part.toDouble() / whole.toDouble()) * 1000.0).roundToInt() / 10.0 else 0.0

    fun getThreadDump(): String {
        val threadBean = ManagementFactory.getThreadMXBean()
        val threadInfos = threadBean.dumpAllThreads(true, true)
        // Native images return nothing here, though they do know their threads' stacks.
        if (threadInfos.isEmpty()) return getPlainThreadDump()
        val sb = StringBuilder()
        sb.append("JVM Thread Dump Generated At: ${java.time.Instant.now()}\n")
        sb.append("Total Threads: ${threadInfos.size}\n\n")

        for (info in threadInfos) {
            sb.append("\"${info.threadName}\" Id=${info.threadId} ${info.threadState}\n")
            if (info.lockName != null) {
                sb.append("    on lock=${info.lockName} owned by \"${info.lockOwnerName}\" Id=${info.lockOwnerId}\n")
            }
            if (info.isSuspended) {
                sb.append("    (suspended)\n")
            }
            if (info.isInNative) {
                sb.append("    (in native)\n")
            }
            for (elem in info.stackTrace) {
                sb.append("    at $elem\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /** The thread dump without lock details, from what every runtime offers. */
    internal fun getPlainThreadDump(): String {
        val stacks = Thread.getAllStackTraces()
        val sb = StringBuilder()
        sb.append("JVM Thread Dump Generated At: ${java.time.Instant.now()}\n")
        sb.append("Total Threads: ${stacks.size}\n\n")

        for ((thread, stack) in stacks) {
            sb.append("\"${thread.name}\" Id=${thread.threadId()} ${thread.state}\n")
            for (elem in stack) {
                sb.append("    at $elem\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    fun getPrometheusMetrics(): String {
        val overview = getOverview()
        val sb = StringBuilder()

        fun metric(type: String, name: String, help: String, value: Number) {
            sb.append("# HELP $name $help\n")
            sb.append("# TYPE $name $type\n")
            sb.append("$name $value\n")
        }

        fun gauge(name: String, help: String, value: Number) = metric("gauge", name, help, value)

        fun counter(name: String, help: String, value: Number) = metric("counter", name, help, value)

        // JVM
        gauge("jvm_memory_heap_used_mb", "Current used heap in megabytes", overview.jvm.usedHeapMb)
        gauge("jvm_memory_heap_max_mb", "Maximum heap in megabytes", overview.jvm.maxHeapMb)
        gauge("jvm_threads_live", "Current live JVM threads", overview.jvm.liveThreads)
        counter("jvm_gc_time_ms_total", "Total GC execution time in milliseconds", overview.jvm.gcTimeMs)
        gauge("jvm_uptime_seconds", "JVM uptime in seconds", overview.jvm.uptimeSeconds)

        // Database Pool
        gauge("hikaricp_active_connections", "Hikari active connections", overview.dbPool.activeConnections)
        gauge("hikaricp_idle_connections", "Hikari idle connections", overview.dbPool.idleConnections)
        gauge("hikaricp_pending_threads", "Hikari threads awaiting connection", overview.dbPool.threadsAwaiting)
        gauge("hikaricp_saturation_percent", "Hikari connection pool saturation", overview.dbPool.saturationPercent)

        // WebSockets
        gauge("ws_active_connections", "Current active WebSocket connections", overview.webSockets.activeConnections)
        gauge("ws_connected_users", "Current unique connected users via WebSockets", overview.webSockets.connectedUsers)
        gauge("ws_churn_rate_per_min", "WebSocket connect/disconnect churn rate per minute", overview.webSockets.churnRatePerMinute)

        // HTTP Latency
        gauge("http_latency_p50_ms", "HTTP median latency in milliseconds", overview.latency.p50Ms)
        gauge("http_latency_p95_ms", "HTTP 95th percentile latency in milliseconds", overview.latency.p95Ms)
        gauge("http_latency_p99_ms", "HTTP 99th percentile latency in milliseconds", overview.latency.p99Ms)
        counter("http_requests_total", "Total HTTP requests served", overview.latency.totalRequests)
        counter("http_errors_4xx_total", "Total 4xx client errors", overview.latency.error4xxCount)
        counter("http_errors_5xx_total", "Total 5xx server errors", overview.latency.error5xxCount)

        // Ingest
        gauge("scout_ingest_avg_lag_ms", "Average offline sync ingestion lag in ms", overview.clientFleet.avgIngestLagMs)
        gauge("scout_active_devices", "Active client devices in the last hour", overview.clientFleet.totalActiveDevices)

        return sb.toString()
    }

    internal fun resetForTest() {
        latencySamples.reset()
        ingestLagSamples.reset()
        auditLogs.clear()
        listOf(
            totalRequestsCount, error4xxCount, error5xxCount, syncSubmissionsCount,
            topicBroadcastsCount, droppedBroadcastsCount, lastDevicePurgeMs
        ).forEach { it.set(0) }
        listOf(requestsWindow, connectsWindow, disconnectsWindow, failedLoginsWindow, rateLimitHitsWindow)
            .forEach { it.reset() }
        activeDevices.clear()
    }
}
