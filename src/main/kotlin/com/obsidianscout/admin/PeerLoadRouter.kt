package com.obsidianscout.admin

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.integrations.LoadBalancerSettings
import com.obsidianscout.integrations.SettingsService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class NodeLoad(
    val ip: String,
    val appPort: Int,
    val availableHeapMb: Long,
    val maxHeapMb: Long,
    val usedHeapMb: Long = 0L,
    val cpuLoad: Double = 0.0, // 0.0–1.0
    val activeThreads: Int = 0,
    val latencyMs: Long = 0L,   // round-trip time of the /api/cluster/load probe; 0 for local node
    val score: Double = 0.0,    // computed from heap, CPU, and latency
    val sampledAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class LoadBalancingActivityEntry(
    val timestampEpochMs: Long,
    val localIp: String,
    val targetIp: String,
    val isForwarded: Boolean,
    val localScore: Double,
    val targetScore: Double,
    val localHeapFreeMb: Long,
    val localCpuPercent: Int,
    val requestsForwarded: Long = 0L,
    val requestsServedLocally: Long = 0L,
    val note: String = ""
)

@Serializable
data class LoadBalancingRecentStats(
    val totalForwarded30m: Long = 0L,
    val totalLocalServed30m: Long = 0L,
    val forwardedRatio30m: Double = 0.0,
    val targetDistribution: Map<String, Long> = emptyMap()
)

@Serializable
data class LoadBalancerStatusResponse(
    val enabled: Boolean,
    val localNode: NodeLoad,
    val peerNodes: List<NodeLoad>,
    val bestNodeIp: String,
    val isForwardingActive: Boolean,
    val forwardedCount: Long,
    val localServedCount: Long = 0L,
    val localPreferenceMargin: Double,
    val maxExpectedLatencyMs: Double,
    val probeIntervalSeconds: Long,
    val recentStats: LoadBalancingRecentStats = LoadBalancingRecentStats(),
    val activityHistory: List<LoadBalancingActivityEntry> = emptyList()
)

object PeerLoadRouter {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null

    @Volatile
    var cachedSettings: LoadBalancerSettings = LoadBalancerSettings()
        private set

    val peerLoadMap = ConcurrentHashMap<String, NodeLoad>()
    val forwardedCount = AtomicLong(0L)
    val localServedCount = AtomicLong(0L)
    private val activityLog = java.util.concurrent.ConcurrentLinkedDeque<LoadBalancingActivityEntry>()
    private val lastRecordedForwarded = AtomicLong(0L)
    private val lastRecordedLocal = AtomicLong(0L)

    @Volatile
    private var cachedHttpClient: HttpClient? = null

    private val HOP_BY_HOP_REQUEST_HEADERS = setOf(
        "host",
        "content-length",
        "connection",
        "upgrade",
        "te",
        "keep-alive",
        "proxy-connection",
        "http2-settings"
    )

    private val HOP_BY_HOP_RESPONSE_HEADERS = setOf(
        "connection",
        "keep-alive",
        "transfer-encoding",
        "content-length",
        "upgrade",
        "proxy-authenticate",
        "trailer"
    )

    private fun getHttpClient(): HttpClient {
        var client = cachedHttpClient
        if (client == null) {
            client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            cachedHttpClient = client
        }
        return client
    }

    fun updateCachedSettings(settings: LoadBalancerSettings) {
        cachedSettings = settings
    }

    @Synchronized
    fun start(appConfig: AppConfig) {
        if (monitoringJob?.isActive == true) return

        cachedSettings = try {
            SettingsService.getLoadBalancerSettings()
        } catch (_: Throwable) {
            LoadBalancerSettings()
        }

        ServerLogService.appendLog("INFO", "PeerLoadRouter", "Peer Load Router started (probe interval: s, enabled=).")

        monitoringJob = scope.launch {
            delay(5_000L)
            while (isActive) {
                try {
                    refreshSettingsAndProbePeers()
                } catch (e: Exception) {
                    ServerLogService.appendLog("ERROR", "PeerLoadRouter", "Error during peer load probe cycle: ")
                }
                val interval = cachedSettings.probeIntervalSeconds.coerceAtLeast(5L) * 1000L
                delay(interval)
            }
        }
    }

    @Synchronized
    fun stop() {
        monitoringJob?.cancel()
        monitoringJob = null
        peerLoadMap.clear()
        ServerLogService.appendLog("INFO", "PeerLoadRouter", "Peer Load Router stopped.")
    }

    private suspend fun refreshSettingsAndProbePeers() = withContext(Dispatchers.IO) {
        cachedSettings = try {
            SettingsService.getLoadBalancerSettings()
        } catch (_: Throwable) {
            cachedSettings
        }

        if (!cachedSettings.enabled) {
            peerLoadMap.clear()
            return@withContext
        }

        val cluster = try {
            ClusterManagementService.getClusterNodes()
        } catch (e: Exception) {
            ServerLogService.appendLog("WARN", "PeerLoadRouter", "Failed to retrieve cluster nodes for load probe: ")
            return@withContext
        }

        val localIp = ClusterManagementService.getLocalTailscaleIp()
        val onlinePeers = cluster.nodes.filter { !it.isLocal && it.ip != localIp && it.status == "online" }
        val onlinePeerIps = onlinePeers.map { it.ip }.toSet()

        // Clean up stale nodes no longer online
        peerLoadMap.keys.retainAll(onlinePeerIps)

        for (peer in onlinePeers) {
            try {
                val t0 = System.currentTimeMillis()
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("http://:/api/cluster/load"))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build()
                val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                val latencyMs = (System.currentTimeMillis() - t0).coerceAtLeast(0L)

                if (resp.statusCode() == 200) {
                    val body = resp.body()
                    val elem = JsonSupport.json.parseToJsonElement(body).jsonObject
                    val availHeap = elem["availableHeapMb"]?.jsonPrimitive?.longOrNull ?: 0L
                    val maxHeap = (elem["maxHeapMb"]?.jsonPrimitive?.longOrNull ?: 1L).coerceAtLeast(1L)
                    val usedHeap = elem["usedHeapMb"]?.jsonPrimitive?.longOrNull ?: 0L
                    val cpu = (elem["cpuLoad"]?.jsonPrimitive?.doubleOrNull ?: 0.0).coerceIn(0.0, 1.0)
                    val threads = elem["activeThreads"]?.jsonPrimitive?.intOrNull ?: 0

                    val score = computeScore(
                        availableHeapMb = availHeap,
                        maxHeapMb = maxHeap,
                        cpuLoad = cpu,
                        latencyMs = latencyMs,
                        maxExpectedLatencyMs = cachedSettings.maxExpectedLatencyMs
                    )

                    val load = NodeLoad(
                        ip = peer.ip,
                        appPort = peer.appPort,
                        availableHeapMb = availHeap,
                        maxHeapMb = maxHeap,
                        usedHeapMb = usedHeap,
                        cpuLoad = cpu,
                        activeThreads = threads,
                        latencyMs = latencyMs,
                        score = score,
                        sampledAtEpochMs = System.currentTimeMillis()
                    )
                    peerLoadMap[peer.ip] = load
                } else {
                    peerLoadMap.remove(peer.ip)
                }
            } catch (e: Exception) {
                peerLoadMap.remove(peer.ip)
            }
        }

        val localNode = getLocalNodeLoad()
        val bestNode = selectBestNode(localNode)
        recordActivitySnapshot(bestNode, localNode)
    }

    fun recordLocalServed() {
        localServedCount.incrementAndGet()
    }

    fun recordActivitySnapshot(bestNode: NodeLoad, localNode: NodeLoad) {
        val now = System.currentTimeMillis()
        val currentForwarded = forwardedCount.get()
        val currentLocal = localServedCount.get()

        val prevFwd = lastRecordedForwarded.getAndSet(currentForwarded)
        val prevLoc = lastRecordedLocal.getAndSet(currentLocal)

        val deltaForwarded = (currentForwarded - prevFwd).coerceAtLeast(0L)
        val deltaLocal = (currentLocal - prevLoc).coerceAtLeast(0L)

        val isForwarded = (bestNode.ip != localNode.ip)
        val note = if (!cachedSettings.enabled) {
            "Load balancing disabled (served locally)"
        } else if (isForwarded) {
            "Forwarded to ${bestNode.ip} (Score: ${(bestNode.score * 100).toInt()}% vs Local: ${(localNode.score * 100).toInt()}%)"
        } else {
            "Served locally (Local score: ${(localNode.score * 100).toInt()}%)"
        }

        val entry = LoadBalancingActivityEntry(
            timestampEpochMs = now,
            localIp = localNode.ip,
            targetIp = bestNode.ip,
            isForwarded = isForwarded,
            localScore = localNode.score,
            targetScore = bestNode.score,
            localHeapFreeMb = localNode.availableHeapMb,
            localCpuPercent = Math.round(localNode.cpuLoad * 100).toInt(),
            requestsForwarded = deltaForwarded,
            requestsServedLocally = deltaLocal,
            note = note
        )

        activityLog.addLast(entry)

        // Prune entries older than 30 minutes
        val cutoff = now - (30 * 60 * 1000L)
        while (activityLog.isNotEmpty() && activityLog.peekFirst().timestampEpochMs < cutoff) {
            activityLog.pollFirst()
        }
    }

    fun getRecentStats(): LoadBalancingRecentStats {
        val now = System.currentTimeMillis()
        val cutoff = now - (30 * 60 * 1000L)
        val recentEntries = activityLog.filter { it.timestampEpochMs >= cutoff }

        var totalFwd = 0L
        var totalLoc = 0L
        val targetCounts = mutableMapOf<String, Long>()

        for (entry in recentEntries) {
            totalFwd += entry.requestsForwarded
            totalLoc += entry.requestsServedLocally
            if (entry.isForwarded && entry.requestsForwarded > 0) {
                targetCounts[entry.targetIp] = (targetCounts[entry.targetIp] ?: 0L) + entry.requestsForwarded
            }
        }

        val total = totalFwd + totalLoc
        val ratio = if (total > 0) totalFwd.toDouble() / total else 0.0

        return LoadBalancingRecentStats(
            totalForwarded30m = totalFwd,
            totalLocalServed30m = totalLoc,
            forwardedRatio30m = Math.round(ratio * 1000.0) / 1000.0,
            targetDistribution = targetCounts
        )
    }

    fun computeScore(
        availableHeapMb: Long,
        maxHeapMb: Long,
        cpuLoad: Double,
        latencyMs: Long,
        maxExpectedLatencyMs: Double
    ): Double {
        val heapRatio = if (maxHeapMb > 0) (availableHeapMb.toDouble() / maxHeapMb).coerceIn(0.0, 1.0) else 0.0
        val cpuScore = (1.0 - cpuLoad.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        val latencyRatio = if (maxExpectedLatencyMs > 0.0) (latencyMs.toDouble() / maxExpectedLatencyMs).coerceIn(0.0, 1.0) else 0.0
        val latencyScore = (1.0 - latencyRatio).coerceIn(0.0, 1.0)

        val score = (heapRatio * 0.5) + (cpuScore * 0.3) + (latencyScore * 0.2)
        return (Math.round(score * 10000.0) / 10000.0).coerceIn(0.0, 1.0)
    }

    fun getLocalNodeLoad(): NodeLoad {
        val localIp = ClusterManagementService.getLocalTailscaleIp()
        val appConfig = AppConfigLoader.load()
        val appPort = appConfig.server.port

        val memBean = ManagementFactory.getMemoryMXBean()
        val heap = memBean.heapMemoryUsage
        val maxHeap = if (heap.max > 0) heap.max else heap.committed
        val usedHeap = heap.used
        val availableHeap = (maxHeap - usedHeap).coerceAtLeast(0L)
        val availableHeapMb = availableHeap / (1024 * 1024)
        val maxHeapMb = (maxHeap / (1024 * 1024)).coerceAtLeast(1L)
        val usedHeapMb = usedHeap / (1024 * 1024)

        val osBean = try {
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)
        } catch (_: Throwable) {
            null
        }
        val rawCpu = try {
            osBean?.processCpuLoad ?: osBean?.cpuLoad ?: -1.0
        } catch (_: Throwable) {
            -1.0
        }
        val cpuLoad = if (rawCpu in 0.0..1.0) rawCpu else 0.0
        val activeThreads = Thread.activeCount()

        val score = computeScore(
            availableHeapMb = availableHeapMb,
            maxHeapMb = maxHeapMb,
            cpuLoad = cpuLoad,
            latencyMs = 0L,
            maxExpectedLatencyMs = cachedSettings.maxExpectedLatencyMs
        )

        return NodeLoad(
            ip = localIp,
            appPort = appPort,
            availableHeapMb = availableHeapMb,
            maxHeapMb = maxHeapMb,
            usedHeapMb = usedHeapMb,
            cpuLoad = (Math.round(cpuLoad * 1000.0) / 1000.0),
            activeThreads = activeThreads,
            latencyMs = 0L,
            score = score,
            sampledAtEpochMs = System.currentTimeMillis()
        )
    }

    fun selectBestNode(currentLocal: NodeLoad = getLocalNodeLoad()): NodeLoad {
        if (!cachedSettings.enabled) return currentLocal

        val bestPeer = peerLoadMap.values.maxByOrNull { it.score } ?: return currentLocal
        return if (bestPeer.score > currentLocal.score + cachedSettings.localPreferenceMargin) {
            bestPeer
        } else {
            currentLocal
        }
    }

    suspend fun forwardRequest(peer: NodeLoad, call: ApplicationCall): Boolean = withContext(Dispatchers.IO) {
        val targetUrl = "http://:"
        val method = call.request.httpMethod.value

        val bodyBytes = try {
            call.receive<ByteArray>()
        } catch (_: Throwable) {
            ByteArray(0)
        }

        val publisher = if (bodyBytes.isNotEmpty()) {
            HttpRequest.BodyPublishers.ofByteArray(bodyBytes)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }

        val timeoutSeconds = cachedSettings.forwardTimeoutSeconds.coerceAtLeast(5L)
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(targetUrl))
            .method(method, publisher)
            .timeout(Duration.ofSeconds(timeoutSeconds))

        call.request.headers.forEach { name, values ->
            val lower = name.lowercase()
            if (lower !in HOP_BY_HOP_REQUEST_HEADERS) {
                values.forEach { v ->
                    try {
                        reqBuilder.header(name, v)
                    } catch (_: Throwable) {
                        // ignore invalid header values for Java HttpClient
                    }
                }
            }
        }

        val localIp = ClusterManagementService.getLocalTailscaleIp()
        val clientIp = call.request.headers["CF-Connecting-IP"]
            ?: call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
            ?: call.request.local.remoteHost
        val proto = call.request.headers["X-Forwarded-Proto"] ?: "http"
        val host = call.request.headers["X-Forwarded-Host"] ?: call.request.headers["Host"] ?: "localhost"

        reqBuilder.header("X-Forwarded-By-Peer", localIp)
        reqBuilder.header("X-Forwarded-For", clientIp)
        reqBuilder.header("X-Forwarded-Proto", proto)
        reqBuilder.header("X-Forwarded-Host", host)

        return@withContext try {
            val resp = getHttpClient().send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
            forwardedCount.incrementAndGet()

            resp.headers().map().forEach { (name, values) ->
                val lower = name.lowercase()
                if (lower !in HOP_BY_HOP_RESPONSE_HEADERS) {
                    values.forEach { v ->
                        call.response.headers.append(name, v, safeOnly = false)
                    }
                }
            }

            val statusCode = HttpStatusCode.fromValue(resp.statusCode())
            val contentType = resp.headers().firstValue("Content-Type").orElse(null)?.let {
                try { ContentType.parse(it) } catch (_: Throwable) { null }
            }
            val respBytes = resp.body() ?: ByteArray(0)

            call.respondBytes(
                bytes = respBytes,
                contentType = contentType,
                status = statusCode
            )
            true
        } catch (e: Exception) {
            ServerLogService.appendLog("WARN", "PeerLoadRouter", "Failed to forward request   to peer : ")
            false
        }
    }

    fun getStatus(): LoadBalancerStatusResponse {
        val local = getLocalNodeLoad()
        val peers = peerLoadMap.values.toList().sortedByDescending { it.score }
        val best = selectBestNode(local)
        val stats = getRecentStats()
        val history = activityLog.toList().takeLast(120)

        return LoadBalancerStatusResponse(
            enabled = cachedSettings.enabled,
            localNode = local,
            peerNodes = peers,
            bestNodeIp = best.ip,
            isForwardingActive = (cachedSettings.enabled && best.ip != local.ip),
            forwardedCount = forwardedCount.get(),
            localServedCount = localServedCount.get(),
            localPreferenceMargin = cachedSettings.localPreferenceMargin,
            maxExpectedLatencyMs = cachedSettings.maxExpectedLatencyMs,
            probeIntervalSeconds = cachedSettings.probeIntervalSeconds,
            recentStats = stats,
            activityHistory = history
        )
    }
}
