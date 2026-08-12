package com.obsidianscout.admin

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.orchestration.GoogleSheetsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
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
    val isDbActive: Boolean = true,
    val serverVersion: String = "Unknown"
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

@Serializable
data class AppConfigPayload(
    val nodeIp: String,
    val isLocal: Boolean,
    val rawJson: String,
    val config: AppConfig? = null
)

@Serializable
data class ClusterStatusResponse(
    val status: String = "online",
    val serverVersion: String = "Unknown",
    val nodeIp: String = "",
    val dbActive: Boolean = true
)

object ClusterManagementService {

    @Volatile
    private var cachedHttpClient: HttpClient? = null

    private val prettyJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private fun getHttpClient(): HttpClient {
        var client = cachedHttpClient
        if (client == null) {
            client = buildNewHttpClient()
            cachedHttpClient = client
        }
        return client
    }

    private fun buildNewHttpClient(): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private fun resetHttpClient() {
        try {
            cachedHttpClient = buildNewHttpClient()
        } catch (e: Exception) {
            // ignore
        }
    }

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
                isDbActive = com.obsidianscout.db.orchestration.CockroachOrchestrator.isDbActive,
                serverVersion = appConfig.current_version
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

        // Ping / probe remote peer nodes concurrently
        val peerResults = coroutineScope {
            gossipIps.map { remoteIp ->
                async {
                    val (isOnline, remoteVersion) = fetchNodeStatusAndVersion(remoteIp, appPort, dbPort)
                    ClusterNodeInfo(
                        nodeId = "node-peer-$remoteIp",
                        ip = remoteIp,
                        dbPort = dbPort,
                        appPort = appPort,
                        isLocal = false,
                        status = if (isOnline) "online" else "offline",
                        isDbActive = isOnline,
                        serverVersion = remoteVersion
                    )
                }
            }.awaitAll()
        }
        nodesList.addAll(peerResults)

        ClusterNodesResponse(
            localNodeIp = localIp,
            totalNodes = nodesList.size,
            nodes = nodesList
        )
    }

    private fun fetchNodeStatusAndVersion(ip: String, appPort: Int, dbPort: Int = 26257): Pair<Boolean, String> {
        // 1. HTTP Probe to ObsidianScout server port
        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://$ip:$appPort/api/cluster/status"))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build()
            val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 200) {
                val body = resp.body()
                val jsonElem = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                val ver = jsonElem?.get("serverVersion")?.let {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                } ?: fetchNodeVersionFromEndpoint(ip, appPort) ?: "Unknown"
                return Pair(true, ver)
            }
        } catch (e: Exception) {
            if (e.message?.contains("selector manager closed") == true) {
                resetHttpClient()
            }
        }

        // 2. Fallback: TCP Socket probe to CockroachDB port
        val isTcpAlive = try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, dbPort), 3000)
                true
            }
        } catch (e: Exception) {
            false
        }

        return if (isTcpAlive) Pair(true, "Unknown") else Pair(false, "Offline")
    }

    private fun fetchNodeVersionFromEndpoint(ip: String, appPort: Int): String? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://$ip:$appPort/api/version"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 200) {
                val jsonElem = runCatching { Json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
                jsonElem?.get("version")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isNodeResponsive(ip: String, appPort: Int, dbPort: Int = 26257): Boolean {
        return fetchNodeStatusAndVersion(ip, appPort, dbPort).first
    }

    fun probeNodeFromPeer(targetIp: String, appPort: Int, dbPort: Int): Boolean {
        return isNodeResponsive(targetIp, appPort, dbPort)
    }

    fun checkNodeResponsiveFromPeerNode(peerIp: String, targetIp: String, appPort: Int, dbPort: Int): Boolean? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("http://$peerIp:$appPort/api/cluster/probe-node?targetIp=$targetIp&appPort=$appPort&dbPort=$dbPort"))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build()
            val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 200) {
                val body = resp.body()
                body.contains("\"isOnline\":true") || body.contains("\"isOnline\": true")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildSignedClusterRequest(urlStr: String, method: String): HttpRequest.Builder {
        val secret = com.obsidianscout.auth.ClusterSecretService.getSessionSecret()
        val timestamp = System.currentTimeMillis().toString()
        val uri = URI.create(urlStr)
        val path = uri.rawPath + (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
        val dataToSign = "$timestamp:$method:$path"
        val signature = com.obsidianscout.auth.ClusterCryptoUtils.hmacSha256(dataToSign, secret)

        return HttpRequest.newBuilder()
            .uri(uri)
            .header("X-Cluster-Timestamp", timestamp)
            .header("X-Cluster-Signature", signature)
            .header("X-Requested-With", "XMLHttpRequest")
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
                val req = buildSignedClusterRequest(url, "GET")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    JsonSupport.json.decodeFromString<ServerLogsPayload>(resp.body())
                } else {
                    ServerLogsPayload(
                        nodeIp = targetIp,
                        isLocal = false,
                        totalEntries = 1,
                        logs = listOf(
                            LogEntry(
                                timestamp = "",
                                level = "WARN",
                                logger = "ClusterManagementService",
                                message = "Node $targetIp is currently offline or rebooting (HTTP ${resp.statusCode()})"
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                if (e.message?.contains("selector manager closed") == true) {
                    resetHttpClient()
                }
                ServerLogsPayload(
                    nodeIp = targetIp,
                    isLocal = false,
                    totalEntries = 1,
                    logs = listOf(
                        LogEntry(
                            timestamp = "",
                            level = "WARN",
                            logger = "ClusterManagementService",
                            message = "Node $targetIp is currently rebooting or unreachable."
                        )
                    )
                )
            }
        }
    }

    suspend fun getAppConfig(targetIp: String, isInterNodeCall: Boolean = false): AppConfigPayload = withContext(Dispatchers.IO) {
        val localIp = getLocalTailscaleIp()
        val isLocal = (targetIp == localIp || targetIp == "127.0.0.1" || targetIp == "local" || targetIp.isBlank())

        if (isLocal) {
            try {
                val loadedConfig = AppConfigLoader.load()
                val prettyText = prettyJson.encodeToString(loadedConfig)
                val responseJson = if (isInterNodeCall) {
                    val secret = com.obsidianscout.auth.ClusterSecretService.getSessionSecret()
                    com.obsidianscout.auth.ClusterCryptoUtils.encrypt(prettyText, secret)
                } else {
                    prettyText
                }
                AppConfigPayload(
                    nodeIp = localIp,
                    isLocal = true,
                    rawJson = responseJson,
                    config = if (isInterNodeCall) null else loadedConfig
                )
            } catch (e: Exception) {
                val configPath = Paths.get("config", "app-config.json")
                val text = if (Files.exists(configPath)) Files.readString(configPath) else "{}"
                val responseJson = if (isInterNodeCall) {
                    val secret = com.obsidianscout.auth.ClusterSecretService.getSessionSecret()
                    com.obsidianscout.auth.ClusterCryptoUtils.encrypt(text, secret)
                } else {
                    text
                }
                AppConfigPayload(
                    nodeIp = localIp,
                    isLocal = true,
                    rawJson = responseJson,
                    config = null
                )
            }
        } else {
            val appConfig = AppConfigLoader.load()
            val appPort = appConfig.server.port
            val url = "http://$targetIp:$appPort/api/admin/cluster/nodes/local/app-config"
            try {
                val req = buildSignedClusterRequest(url, "GET")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
                val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    val payload = JsonSupport.json.decodeFromString<AppConfigPayload>(resp.body())
                    val secret = com.obsidianscout.auth.ClusterSecretService.getSessionSecret()
                    val decryptedRaw = try {
                        com.obsidianscout.auth.ClusterCryptoUtils.decrypt(payload.rawJson, secret)
                    } catch (e: Exception) {
                        payload.rawJson
                    }
                    val parsedConfig = runCatching { JsonSupport.json.decodeFromString<AppConfig>(decryptedRaw) }.getOrNull()
                    AppConfigPayload(
                        nodeIp = targetIp,
                        isLocal = false,
                        rawJson = decryptedRaw,
                        config = parsedConfig
                    )
                } else {
                    AppConfigPayload(
                        nodeIp = targetIp,
                        isLocal = false,
                        rawJson = "// Failed to retrieve app-config.json from remote server $targetIp (HTTP ${resp.statusCode()})",
                        config = null
                    )
                }
            } catch (e: Exception) {
                if (e.message?.contains("selector manager closed") == true) {
                    resetHttpClient()
                }
                AppConfigPayload(
                    nodeIp = targetIp,
                    isLocal = false,
                    rawJson = "// Remote server $targetIp is unreachable or offline: ${e.message}",
                    config = null
                )
            }
        }
    }

    suspend fun updateAppConfig(targetIp: String, rawJson: String, isInterNodeCall: Boolean = false): ActionResultResponse = withContext(Dispatchers.IO) {
        val localIp = getLocalTailscaleIp()
        val isLocal = (targetIp == localIp || targetIp == "127.0.0.1" || targetIp == "local" || targetIp.isBlank())
        val secret = com.obsidianscout.auth.ClusterSecretService.getSessionSecret()

        val jsonToSave = if (isLocal && isInterNodeCall) {
            try {
                com.obsidianscout.auth.ClusterCryptoUtils.decrypt(rawJson, secret)
            } catch (e: Exception) {
                rawJson
            }
        } else {
            rawJson
        }

        // Validate JSON payload
        val parsedConfig = try {
            JsonSupport.json.decodeFromString<AppConfig>(jsonToSave)
        } catch (e: Exception) {
            return@withContext ActionResultResponse(
                success = false,
                message = "Invalid app-config.json format: ${e.message}",
                targetIp = targetIp
            )
        }

        if (isLocal) {
            try {
                val formattedJson = prettyJson.encodeToString(parsedConfig)
                val configPath = Paths.get("config", "app-config.json")
                configPath.parent?.let { Files.createDirectories(it) }
                Files.writeString(configPath, formattedJson)
                AppConfigLoader.updateCache(parsedConfig)

                ServerLogService.appendLog("WARN", "ClusterManagementService", "AppConfig updated on local node $localIp.")
                ActionResultResponse(
                    success = true,
                    message = "app-config.json successfully updated for server node $localIp.",
                    targetIp = localIp
                )
            } catch (e: Exception) {
                ActionResultResponse(
                    success = false,
                    message = "Failed to save app-config.json on local node: ${e.message}",
                    targetIp = localIp
                )
            }
        } else {
            val appConfig = AppConfigLoader.load()
            val appPort = appConfig.server.port
            val url = "http://$targetIp:$appPort/api/admin/cluster/nodes/local/app-config"
            try {
                val formattedJson = prettyJson.encodeToString(parsedConfig)
                val encryptedBody = com.obsidianscout.auth.ClusterCryptoUtils.encrypt(formattedJson, secret)
                val req = buildSignedClusterRequest(url, "PUT")
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "text/plain")
                    .PUT(HttpRequest.BodyPublishers.ofString(encryptedBody))
                    .build()
                val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    JsonSupport.json.decodeFromString<ActionResultResponse>(resp.body())
                } else {
                    ActionResultResponse(
                        success = false,
                        message = "Remote server $targetIp returned HTTP ${resp.statusCode()} while saving config.",
                        targetIp = targetIp
                    )
                }
            } catch (e: Exception) {
                if (e.message?.contains("selector manager closed") == true) {
                    resetHttpClient()
                }
                ActionResultResponse(
                    success = false,
                    message = "Failed to dispatch app-config.json update to remote server $targetIp: ${e.message}",
                    targetIp = targetIp
                )
            }
        }
    }

    suspend fun rebootNode(targetIp: String): ActionResultResponse = withContext(Dispatchers.IO) {
        val localIp = getLocalTailscaleIp()
        val isLocal = (targetIp == localIp || targetIp == "127.0.0.1" || targetIp == "local" || targetIp.isBlank())

        if (isLocal) {
            ServerLogService.appendLog("WARN", "ClusterManagementService", "Reboot command received for local node $localIp. Restarting database & node services...")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val orchestrator = DatabaseFactory.orchestrator
                    if (orchestrator != null) {
                        ServerLogService.appendLog("INFO", "ClusterManagementService", "Restarting CockroachDB process on $localIp...")
                        orchestrator.stop()
                        delay(2000)
                        val dbConfig = orchestrator.orchestrate()
                        DatabaseFactory.init(dbConfig, runMigration = false, isCockroach = true)
                        ServerLogService.appendLog("INFO", "ClusterManagementService", "Local node CockroachDB process successfully rebooted and re-joined cluster.")
                    } else {
                        ServerLogService.appendLog("INFO", "ClusterManagementService", "Scheduling process restart...")
                        delay(1000)
                        exitProcess(0)
                    }
                } catch (e: Exception) {
                    ServerLogService.appendLog("ERROR", "ClusterManagementService", "Local node reboot error: ${e.message}")
                }
            }
            ActionResultResponse(
                success = true,
                message = "Local node reboot initiated. CockroachDB service is restarting.",
                targetIp = localIp
            )
        } else {
            val appConfig = AppConfigLoader.load()
            val appPort = appConfig.server.port
            val url = "http://$targetIp:$appPort/api/admin/cluster/nodes/local/reboot"
            try {
                val req = buildSignedClusterRequest(url, "POST")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
                val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    ActionResultResponse(true, "Reboot command successfully dispatched to remote server $targetIp.", targetIp)
                } else {
                    ActionResultResponse(false, "Remote server $targetIp returned status ${resp.statusCode()}.", targetIp)
                }
            } catch (e: Exception) {
                if (e.message?.contains("selector manager closed") == true) {
                    resetHttpClient()
                }
                ActionResultResponse(false, "Failed to send reboot command to $targetIp: ${e.message}", targetIp)
            }
        }
    }

    suspend fun forceReinstallUpdateNode(targetIp: String): ActionResultResponse = withContext(Dispatchers.IO) {
        val localIp = getLocalTailscaleIp()
        val isLocal = (targetIp == localIp || targetIp == "127.0.0.1" || targetIp == "local" || targetIp.isBlank())

        if (isLocal) {
            ServerLogService.appendLog("WARN", "ClusterManagementService", "Force reinstall / update command received for local node $localIp.")
            CoroutineScope(Dispatchers.IO).launch {
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
                val req = buildSignedClusterRequest(url, "POST")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build()
                val resp = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    ActionResultResponse(true, "Force reinstall/update command successfully dispatched to remote server $targetIp.", targetIp)
                } else {
                    ActionResultResponse(false, "Remote server $targetIp returned status ${resp.statusCode()}.", targetIp)
                }
            } catch (e: Exception) {
                if (e.message?.contains("selector manager closed") == true) {
                    resetHttpClient()
                }
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
            message = "Reboot command dispatched to $successCount/${cluster.nodes.size} nodes on the cluster.",
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
            message = "Force reinstall/update command dispatched to $successCount/${cluster.nodes.size} nodes on the cluster.",
            targetIp = "all-nodes"
        )
    }

    suspend fun getAllClusterLogs(limit: Int = 500, filter: String? = null): ServerLogsPayload = withContext(Dispatchers.IO) {
        val cluster = getClusterNodes()
        val nodePayloads = coroutineScope {
            cluster.nodes.map { node ->
                async {
                    Pair(node, getNodeLogs(node.ip, limit = limit, filter = filter))
                }
            }.awaitAll()
        }

        val aggregatedLogs = mutableListOf<LogEntry>()
        for ((node, nodePayload) in nodePayloads) {
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
