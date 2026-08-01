package com.obsidianscout.admin

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.orchestration.GoogleSheetsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

@Serializable
data class ClusterNodeInfo(
    val nodeId: String,
    val ip: String,
    val dbPort: Int,
    val appPort: Int,
    val isLocal: Boolean,
    val status: String,
    val role: String = "Cockroach Gateway Node",
    val cockroachVersion: String = "v26.2.3",
    val isDbActive: Boolean = true
)

@Serializable
data class ClusterNodesResponse(
    val localNodeIp: String,
    val totalNodes: Int,
    val nodes: List<ClusterNodeInfo>
)

@Serializable
data class ActionResultResponse(
    val success: Boolean,
    val message: String,
    val targetIp: String
)

object ClusterManagementService {

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private val startTimeMillis = System.currentTimeMillis()

    fun getLocalTailscaleIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val netInterface = interfaces.nextElement()
                    val addresses = netInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress
                            if (ip.startsWith("100.")) return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return "127.0.0.1"
    }

    suspend fun getClusterNodes(): ClusterNodesResponse = withContext(Dispatchers.IO) {
        val appConfig = AppConfigLoader.load()
        val localIp = getLocalTailscaleIp()
        val appPort = appConfig.server.port
        val dbPort = appConfig.cockroach_port

        val nodesList = mutableListOf<ClusterNodeInfo>()

        // Add Local Node
        nodesList.add(
            ClusterNodeInfo(
                nodeId = "node-local-$localIp",
                ip = localIp,
                dbPort = dbPort,
                appPort = appPort,
                isLocal = true,
                status = if (DatabaseFactory.isReady) "online" else "booting",
                isDbActive = com.obsidianscout.db.orchestration.CockroachOrchestrator.isDbActive
            )
        )

        // Query database gossip nodes if DB is active
        val gossipIps = mutableSetOf<String>()
        if (DatabaseFactory.isReady) {
            try {
                DatabaseFactory.activeDataSource?.connection?.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("SET allow_unsafe_internals = true;")
                        stmt.executeQuery("SELECT address FROM crdb_internal.gossip_nodes;").use { rs ->
                            while (rs.next()) {
                                val addr = rs.getString("address") ?: ""
                                val ip = addr.substringBefore(":")
                                if (ip.isNotBlank() && ip != localIp) {
                                    gossipIps.add(ip)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                ServerLogService.appendLog("WARN", "ClusterManagementService", "Could not fetch gossip nodes: ${e.message}")
            }
        }

        // Fetch peer IPs from Google Sheet fallback
        try {
            val sheetPeers = GoogleSheetsManager.fetchPeers(appConfig.google_sheet_url, appConfig.google_sheet_password)
            for ((peerIp, peerPort) in sheetPeers) {
                if (peerIp != localIp && peerIp.isNotBlank()) {
                    gossipIps.add(peerIp)
                }
            }
        } catch (e: Exception) {
            // sheet fetch fallback
        }

        // Ping / probe remote peer nodes
        for (remoteIp in gossipIps) {
            val isOnline = isNodeResponsive(remoteIp, appPort)
            nodesList.add(
                ClusterNodeInfo(
                    nodeId = "node-peer-$remoteIp",
                    ip = remoteIp,
                    dbPort = dbPort,
                    appPort = appPort,
                    isLocal = false,
                    status = if (isOnline) "online" else "offline",
                    isDbActive = isOnline
                )
            )
        }

        ClusterNodesResponse(
            localNodeIp = localIp,
            totalNodes = nodesList.size,
            nodes = nodesList
        )
    }

    private fun isNodeResponsive(ip: String, appPort: Int): Boolean {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://$ip:$appPort/api/cluster/status"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            resp.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getNodeLogs(targetIp: String, limit: Int = 500, filter: String? = null): ServerLogsPayload = withContext(Dispatchers.IO) {
        val localIp = getLocalTailscaleIp()
        val isLocal = (targetIp == localIp || targetIp == "127.0.0.1" || targetIp == "local" || targetIp.isBlank())

        if (isLocal) {
            val logs = ServerLogService.getLogs(limit, filter)
            ServerLogsPayload(
                nodeIp = localIp,
                isLocal = true,
                totalEntries = logs.size,
                logs = logs
            )
        } else {
            // Remote node proxy call
            val appConfig = AppConfigLoader.load()
            val appPort = appConfig.server.port
            val queryStr = "?limit=$limit" + (if (!filter.isNullOrBlank()) "&filter=${java.net.URLEncoder.encode(filter, "UTF-8")}" else "")
            val url = "http://$targetIp:$appPort/api/admin/cluster/nodes/local/logs$queryStr"
            
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    com.obsidianscout.config.JsonSupport.json.decodeFromString<ServerLogsPayload>(resp.body())
                } else {
                    ServerLogsPayload(
                        nodeIp = targetIp,
                        isLocal = false,
                        totalEntries = 1,
                        logs = listOf(
                            LogEntry(
                                timestamp = "",
                                level = "ERROR",
                                logger = "ClusterManagementService",
                                message = "Remote log fetch failed from $targetIp (HTTP ${resp.statusCode()})"
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                ServerLogsPayload(
                    nodeIp = targetIp,
                    isLocal = false,
                    totalEntries = 1,
                    logs = listOf(
                        LogEntry(
                            timestamp = "",
                            level = "ERROR",
                            logger = "ClusterManagementService",
                            message = "Failed to reach remote node $targetIp for logs: ${e.message}"
                        )
                    )
                )
            }
        }
    }

    suspend fun rebootNode(targetIp: String): ActionResultResponse = withContext(Dispatchers.IO) {
        val localIp = getLocalTailscaleIp()
        val isLocal = (targetIp == localIp || targetIp == "127.0.0.1" || targetIp == "local" || targetIp.isBlank())

        if (isLocal) {
            ServerLogService.appendLog("WARN", "ClusterManagementService", "Reboot command received for local server node. Scheduling restart...")
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                delay(1500)
                ServerLogService.appendLog("INFO", "ClusterManagementService", "Shutting down application process for reboot...")
                exitProcess(0)
            }
            ActionResultResponse(
                success = true,
                message = "Local server reboot initiated. The server process will restart momentarily.",
                targetIp = localIp
            )
        } else {
            val appConfig = AppConfigLoader.load()
            val appPort = appConfig.server.port
            val url = "http://$targetIp:$appPort/api/admin/cluster/nodes/local/reboot"
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    ActionResultResponse(true, "Reboot command successfully dispatched to remote server $targetIp.", targetIp)
                } else {
                    ActionResultResponse(false, "Remote server $targetIp returned status ${resp.statusCode()}.", targetIp)
                }
            } catch (e: Exception) {
                ActionResultResponse(false, "Failed to send reboot command to $targetIp: ${e.message}", targetIp)
            }
        }
    }

    suspend fun forceReinstallUpdateNode(targetIp: String): ActionResultResponse = withContext(Dispatchers.IO) {
        val localIp = getLocalTailscaleIp()
        val isLocal = (targetIp == localIp || targetIp == "127.0.0.1" || targetIp == "local" || targetIp.isBlank())

        if (isLocal) {
            ServerLogService.appendLog("WARN", "ClusterManagementService", "Force reinstall / update command received for local node.")
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    ServerLogService.appendLog("INFO", "ClusterManagementService", "Initiating CockroachDB binary re-verification & update check...")
                    val appConfig = AppConfigLoader.load()
                    com.obsidianscout.utils.GistUpdateService.stop()
                    com.obsidianscout.utils.GistUpdateService.start(appConfig)
                    ServerLogService.appendLog("INFO", "ClusterManagementService", "Force update check sequence scheduled successfully.")
                } catch (e: Exception) {
                    ServerLogService.appendLog("ERROR", "ClusterManagementService", "Force reinstall/update error: ${e.message}")
                }
            }
            ActionResultResponse(
                success = true,
                message = "Force reinstall and update sequence triggered for local node $localIp.",
                targetIp = localIp
            )
        } else {
            val appConfig = AppConfigLoader.load()
            val appPort = appConfig.server.port
            val url = "http://$targetIp:$appPort/api/admin/cluster/nodes/local/reinstall-update"
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    ActionResultResponse(true, "Force reinstall/update command successfully dispatched to remote server $targetIp.", targetIp)
                } else {
                    ActionResultResponse(false, "Remote server $targetIp returned status ${resp.statusCode()}.", targetIp)
                }
            } catch (e: Exception) {
                ActionResultResponse(false, "Failed to send force reinstall command to $targetIp: ${e.message}", targetIp)
            }
        }
    }

    suspend fun rebootEntireCluster(): ActionResultResponse = withContext(Dispatchers.IO) {
        val cluster = getClusterNodes()
        val results = mutableListOf<String>()
        var successCount = 0

        for (node in cluster.nodes) {
            try {
                val res = rebootNode(node.ip)
                if (res.success) {
                    successCount++
                    results.add("${node.ip}: Success")
                } else {
                    results.add("${node.ip}: Failed (${res.message})")
                }
            } catch (e: Exception) {
                results.add("${node.ip}: Error (${e.message})")
            }
        }

        ServerLogService.appendLog("WARN", "ClusterManagementService", "Cluster-wide reboot executed. Dispatched to $successCount of ${cluster.nodes.size} nodes.")
        ActionResultResponse(
            success = successCount > 0,
            message = "Reboot command dispatched to $successCount/${cluster.nodes.size} nodes on the cluster: " + results.joinToString("; "),
            targetIp = "all-nodes"
        )
    }

    suspend fun forceReinstallUpdateEntireCluster(): ActionResultResponse = withContext(Dispatchers.IO) {
        val cluster = getClusterNodes()
        val results = mutableListOf<String>()
        var successCount = 0

        for (node in cluster.nodes) {
            try {
                val res = forceReinstallUpdateNode(node.ip)
                if (res.success) {
                    successCount++
                    results.add("${node.ip}: Success")
                } else {
                    results.add("${node.ip}: Failed (${res.message})")
                }
            } catch (e: Exception) {
                results.add("${node.ip}: Error (${e.message})")
            }
        }

        ServerLogService.appendLog("WARN", "ClusterManagementService", "Cluster-wide force reinstall/update executed. Dispatched to $successCount of ${cluster.nodes.size} nodes.")
        ActionResultResponse(
            success = successCount > 0,
            message = "Force reinstall/update command dispatched to $successCount/${cluster.nodes.size} nodes on the cluster: " + results.joinToString("; "),
            targetIp = "all-nodes"
        )
    }

    suspend fun getAllClusterLogs(limit: Int = 500, filter: String? = null): ServerLogsPayload = withContext(Dispatchers.IO) {
        val cluster = getClusterNodes()
        val aggregatedLogs = mutableListOf<LogEntry>()

        for (node in cluster.nodes) {
            val nodePayload = getNodeLogs(node.ip, limit = limit, filter = filter)
            val taggedLogs = nodePayload.logs.map { entry ->
                LogEntry(
                    timestamp = entry.timestamp,
                    level = entry.level,
                    logger = if (entry.logger.isNotBlank()) "${node.ip} | ${entry.logger}" else node.ip,
                    message = entry.message
                )
            }
            aggregatedLogs.addAll(taggedLogs)
        }

        val sortedLogs = aggregatedLogs.sortedBy { it.timestamp }.takeLast(limit.coerceIn(1, 1000))

        ServerLogsPayload(
            nodeIp = "all-cluster-nodes",
            isLocal = false,
            totalEntries = sortedLogs.size,
            logs = sortedLogs
        )
    }
}
