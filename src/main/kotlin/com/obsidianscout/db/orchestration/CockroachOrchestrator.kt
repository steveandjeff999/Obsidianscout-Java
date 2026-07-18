package com.obsidianscout.db.orchestration

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.PostgresConfig
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull


data class PeerStatus(val dbReady: Boolean, val isDbActive: Boolean, val leaderIp: String?)

class CockroachOrchestrator(private val appConfig: AppConfig) {

    private var process: Process? = null
    private val rootDir = File(".cockroach")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isArm = System.getProperty("os.arch").lowercase().let { it.contains("arm") || it.contains("aarch64") }
    private val binaryName = if (isWindows) "cockroach.exe" else "cockroach"
    private val binaryFile = File(rootDir, binaryName)
    private var pollJob: Job? = null
    private var replicationJob: Job? = null
    private val knownPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, Int>>()
    private var tailscaleIp: String = "127.0.0.1"
    private var port: Int = 26257
    private var isInsecure: Boolean = true
    private var failedLeaderChecks = 0

    private val version = "v26.2.3"
    private val linuxDownloadUrl = if (isArm) {
        "https://binaries.cockroachdb.com/cockroach-$version.linux-arm64.tgz"
    } else {
        "https://binaries.cockroachdb.com/cockroach-$version.linux-amd64.tgz"
    }
    private val windowsDownloadUrl = "https://binaries.cockroachdb.com/cockroach-$version.windows-6.2-amd64.zip"

    /**
     * Orchestrates the startup sequence of CockroachDB.
     * Returns a DatabaseConfig configured to connect to this local CockroachDB instance.
     */
    fun orchestrate(): DatabaseConfig {
        currentLeaderIp = null
        println("[Cockroach] Starting autonomous database lifecycle...")

        // Clean up any orphaned processes from previous runs
        killExistingProcesses()

        // 1. Ensure installed
        ensureInstalled()

        // 2. Identify local Tailscale IP
        val tailscaleIp = getTailscaleIp()
        this.tailscaleIp = tailscaleIp
        val port = appConfig.cockroach_port
        this.port = port
        println("[Cockroach] Bound to Tailscale IP: $tailscaleIp on port $port")

        // Check local firewall rules for active ports
        val portsToCheck = mutableListOf(appConfig.server.port, port)
        if (appConfig.server.https.enabled) {
            portsToCheck.add(appConfig.server.https.port)
        }
        checkFirewallRules(portsToCheck)

        // 3. Fetch cluster peers (with retry in case network is not fully up yet)
        var peers = emptyList<Pair<String, Int>>()
        val fetchStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - fetchStart < 45_000) {
            try {
                peers = GoogleSheetsManager.fetchPeers(appConfig.google_sheet_url, appConfig.google_sheet_password)
                break
            } catch (e: Exception) {
                println("[Cockroach] Failed to fetch peers from Google Sheet, retrying: ${e.message}")
                Thread.sleep(2000)
            }
        }
        println("[Cockroach] Fetched ${peers.size} peers from Google Sheet: ${peers.joinToString { "${it.first}:${it.second}" }}")
        val otherPeers = peers.filter { it.first != tailscaleIp || it.second != port }
        val amIInSheet = peers.any { it.first == tailscaleIp }

        // 4. Handle secure/insecure certificates
        val isInsecure = appConfig.db_username.isBlank() && appConfig.db_password.isBlank()
        this.isInsecure = isInsecure
        if (!isInsecure) {
            generateCertificates(tailscaleIp, appConfig.db_username)
        }

        // 5. Elect leader by communicating over HTTP status endpoints
        var leaderIp: String? = null

        // First, check if any peer is already running as a leader
        for (peer in otherPeers) {
            println("[Cockroach] Querying peer status at ${peer.first}...")
            val status = queryPeerStatus(peer.first)
            if (status != null) {
                if (!status.leaderIp.isNullOrBlank()) {
                    val reportedLeader = status.leaderIp
                    if (reportedLeader == tailscaleIp) {
                        println("[Cockroach] Peer at ${peer.first} reports we ($tailscaleIp) are the leader, but we are initializing.")
                    } else {
                        // Verify that the reported leader is actually online and responsive
                        val leaderStatus = queryPeerStatus(reportedLeader)
                        if (leaderStatus != null) {
                            println("[Cockroach] Peer at ${peer.first} reports leader is $reportedLeader, and it is online.")
                            leaderIp = reportedLeader
                            break
                        } else {
                            println("[Cockroach] Peer at ${peer.first} reports leader is $reportedLeader, but it is offline/unreachable. Ignoring.")
                        }
                    }
                } else if (status.isDbActive) {
                    println("[Cockroach] Peer at ${peer.first} has active database. Treating it as leader.")
                    leaderIp = peer.first
                    break
                }
            }
        }

        // If no leader is found, decide who initializes (coordinating over online priorities)
        if (leaderIp.isNullOrBlank()) {
            val jitter = (500..2000).random().toLong()
            println("[Cockroach] No active leader found. Sleeping for ${jitter}ms to stagger elections...")
            try {
                Thread.sleep(jitter)
            } catch (e: Exception) {
                // Ignore interrupted exception
            }
            
            println("[Cockroach] Performing leader election...")
            for (peer in peers) {
                if (peer.first == tailscaleIp && peer.second == port) {
                    println("[Cockroach] We ($tailscaleIp) are the highest priority online node in the sheet. Designating ourselves as leader.")
                    leaderIp = tailscaleIp
                    break
                } else {
                    println("[Cockroach] Checking if higher priority peer ${peer.first} is online over HTTP...")
                    val status = queryPeerStatus(peer.first)
                    if (status != null) {
                        println("[Cockroach] Higher priority peer ${peer.first} is online. Letting it become leader.")
                        leaderIp = peer.first
                        break
                    }
                }
            }
        }

        // Fallback in case we are not in the sheet and no leader is found
        if (leaderIp.isNullOrBlank()) {
            if (amIInSheet) {
                leaderIp = tailscaleIp
            } else {
                println("[Cockroach] WARNING: We are not in the sheet and no leader was found. Waiting for a peer to become online...")
            }
        }

        currentLeaderIp = leaderIp
        println("[Cockroach] Elected Database Leader: $leaderIp")

        // Sync clock with leader if we are not the leader
        if (!leaderIp.isNullOrBlank() && leaderIp != tailscaleIp) {
            syncClockWithLeader(leaderIp)
        }

        // 6. Start the local database process if we are in the sheet or if we have a leader to join
        if (amIInSheet || !leaderIp.isNullOrBlank()) {
            val joinPeers = if (amIInSheet) peers else listOf(Pair(leaderIp ?: "", port))
            val dbProcess = startCockroachProcess(tailscaleIp, port, joinPeers, isInsecure)
            this.process = dbProcess
            isDbActive = true

            // 7. Wait for CockroachDB port to open
            println("[Cockroach] Waiting for CockroachDB to start listening...")
            if (!waitForPort(tailscaleIp, port, 45)) {
                val logFile = File(rootDir, "cockroach.log")
                val logSnippet = if (logFile.exists()) logFile.readLines().takeLast(20).joinToString("\n") else "No logs found."
                throw IllegalStateException("CockroachDB failed to bind to $tailscaleIp:$port within timeout. Last logs:\n$logSnippet")
            }
            println("[Cockroach] CockroachDB is listening on $tailscaleIp:$port")

            // 8. Initialize cluster only if we are the elected leader
            if (leaderIp == tailscaleIp) {
                println("[Cockroach] We are the leader. Initializing cluster...")
                initializeCluster(tailscaleIp, port, isInsecure)
            }

            // 9. Wait for SQL engine to be fully ready
            println("[Cockroach] Waiting for SQL engine to be ready...")
            val sqlReady = waitForSqlReady(tailscaleIp, port, isInsecure, 60)
            if (!sqlReady) {
                val isAlive = process?.isAlive ?: false
                if (!isAlive) {
                    val logFile = File(rootDir, "cockroach.log")
                    val logSnippet = if (logFile.exists()) logFile.readLines().takeLast(500).joinToString("\n") else "No logs found."
                    throw IllegalStateException("CockroachDB process crashed during startup (exit code: ${process?.exitValue()}). Last logs:\n$logSnippet")
                } else {
                    println("[Cockroach] WARNING: SQL engine did not become ready in time (likely waiting for other cluster nodes to achieve quorum). Proceeding anyway...")
                }
            } else {
                println("[Cockroach] SQL engine is ready.")
                if (leaderIp == tailscaleIp) {
                    configureClusterReplication(tailscaleIp, port, isInsecure)
                }
            }

            // 10. Setup DB User and Password if requested
            if (leaderIp == tailscaleIp && !isInsecure && appConfig.db_username.isNotBlank() && appConfig.db_username != "root") {
                println("[Cockroach] Creating application database user: ${appConfig.db_username}...")
                createDatabaseUser(tailscaleIp, port, appConfig.db_username, appConfig.db_password, isInsecure)
            }
        }

        // Failover daemon is started dynamically after connection is established

        // Return connection config pointing to the active leader
        val hostIp = leaderIp ?: tailscaleIp
        return DatabaseConfig(
            type = "postgres",
            postgres = PostgresConfig(
                host = hostIp,
                port = port,
                database = "obsidianscoutjava",
                user = if (appConfig.db_username.isNotBlank()) appConfig.db_username else "root",
                password = appConfig.db_password,
                ssl = !isInsecure
            )
        )
    }

    /**
     * Terminates the background database process.
     */
    fun stop() {
        pollJob?.cancel()
        replicationJob?.cancel()
        process?.let {
            if (it.isAlive) {
                println("[Cockroach] Stopping database process...")
                it.destroy()
                if (!it.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    println("[Cockroach] Database process did not terminate. Forcing shutdown...")
                    it.destroyForcibly()
                }
                println("[Cockroach] Database process stopped.")
            }
        }
        isDbActive = false
        currentLeaderIp = null
    }

    fun isLeaderSchemaReady(): Boolean {
        val leader = currentLeaderIp ?: return false
        if (leader == tailscaleIp) return true
        val status = queryPeerStatus(leader)
        return status?.dbReady ?: false
    }

    fun isProcessAlive(): Boolean {
        return process?.isAlive ?: false
    }

    fun amILeader(): Boolean {
        return tailscaleIp == currentLeaderIp
    }

    private fun killExistingProcesses() {
        try {
            val os = System.getProperty("os.name").lowercase()
            if (os.contains("win")) {
                ProcessBuilder("taskkill", "/F", "/IM", "cockroach.exe")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } else {
                ProcessBuilder("killall", "-9", "cockroach")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
            println("[Cockroach] Cleaned up any existing cockroach processes.")
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun fetchLeaderTime(leaderIp: String): Long? {
        val isHttps = appConfig.server.https.enabled
        val scheme = if (isHttps) "https" else "http"
        val ports = listOf(
            if (isHttps) appConfig.server.https.port else appConfig.server.port,
            8080,
            8888,
            80
        ).distinct()
        for (port in ports) {
            try {
                val url = java.net.URL("$scheme://$leaderIp:$port/api/cluster/time")
                val connection = url.openConnection() as java.net.HttpURLConnection
                if (isHttps && connection is javax.net.ssl.HttpsURLConnection) {
                    connection.sslSocketFactory = createTrustAllSslContext().socketFactory
                    connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonElement = com.obsidianscout.config.JsonSupport.json.parseToJsonElement(responseText).jsonObject
                    return jsonElement["currentTimeMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                }
            } catch (e: Exception) {
                println("[Clock] Failed to fetch time from $leaderIp on port $port: ${e.message}")
            }
        }
        return null
    }

    private fun syncClockWithLeader(leaderIp: String) {
        println("[Clock] Checking clock synchronization with leader $leaderIp...")
        val leaderTime = fetchLeaderTime(leaderIp)
        if (leaderTime == null) {
            println("[Clock] WARNING: Could not fetch leader clock time. Skipping clock synchronization.")
            return
        }

        val localTime = System.currentTimeMillis()
        val offsetMs = leaderTime - localTime
        println("[Clock] Local clock offset relative to leader: ${offsetMs}ms")

        if (java.lang.Math.abs(offsetMs) > 1000) {
            println("[Clock] Clock drift is greater than 1s. Attempting to adjust system clock...")
            try {
                val processBuilder = if (isWindows) {
                    ProcessBuilder("powershell", "-Command", "\"(Get-Date).AddMilliseconds($offsetMs) | Set-Date\"")
                } else {
                    val newEpochSeconds = (localTime + offsetMs) / 1000
                    ProcessBuilder("sudo", "date", "-s", "@$newEpochSeconds")
                }
                
                val p = processBuilder.start()
                val exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (exited && p.exitValue() == 0) {
                    println("[Clock] System clock successfully synchronized with database leader.")
                } else {
                    val errorMsg = p.errorStream.bufferedReader().use { it.readText() }.trim()
                    println("[Clock] WARNING: Failed to synchronize system clock (exit code: ${p.exitValue()}). Error: $errorMsg")
                    println("[Clock] Please sync time manually or run with Administrator/root privileges.")
                }
            } catch (e: Exception) {
                println("[Clock] WARNING: Exception while synchronizing clock: ${e.message}")
            }
        } else {
            println("[Clock] System clock is within acceptable drift threshold (<1s).")
        }
    }

    private fun createTrustAllSslContext(): javax.net.ssl.SSLContext {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate>? = null
                override fun checkClientTrusted(certs: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(certs: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            }
        )
        val sc = javax.net.ssl.SSLContext.getInstance("SSL")
        sc.init(null, trustAllCerts, java.security.SecureRandom())
        return sc
    }

    private fun checkFirewallRules(ports: List<Int>) {
        if (!isWindows) {
            try {
                val p = ProcessBuilder("sudo", "ufw", "status").start()
                if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    val status = p.inputStream.bufferedReader().use { it.readText() }
                    println("[Firewall] UFW status:\n$status")
                }
            } catch (e: Exception) {
                // ignore
            }
            return
        }
        
        println("[Firewall] Checking local Windows Firewall rules for ports: $ports...")
        try {
            val portsString = ports.joinToString(",")
            val pb = ProcessBuilder("powershell", "-Command", 
                "Get-NetFirewallRule -Enabled True -Direction Inbound -Action Allow | Get-NetFirewallPortFilter | Where-Object { \$_.LocalPort -in @($portsString) } | Select-Object Protocol, LocalPort"
            )
            val p = pb.start()
            if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                val output = p.inputStream.bufferedReader().use { it.readText() }.trim()
                if (output.isNotEmpty()) {
                    println("[Firewall] Found inbound allow rules:\n$output")
                } else {
                    println("[Firewall] WARNING: No inbound allow rules found for ports $ports. Nodes might not be able to connect to us!")
                }
            } else {
                val err = p.errorStream.bufferedReader().use { it.readText() }.trim()
                println("[Firewall] Warning checking firewall rules: $err")
            }
        } catch (e: Exception) {
            println("[Firewall] Could not check Windows Firewall rules: ${e.message}")
        }
    }

    private fun queryPeerStatus(ip: String): PeerStatus? {
        val isHttps = appConfig.server.https.enabled
        val scheme = if (isHttps) "https" else "http"
        val ports = listOf(
            if (isHttps) appConfig.server.https.port else appConfig.server.port,
            8080,
            8888,
            80
        ).distinct()
        for (port in ports) {
            try {
                val url = java.net.URL("$scheme://$ip:$port/api/cluster/status")
                val connection = url.openConnection() as java.net.HttpURLConnection
                if (isHttps && connection is javax.net.ssl.HttpsURLConnection) {
                    connection.sslSocketFactory = createTrustAllSslContext().socketFactory
                    connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonElement = com.obsidianscout.config.JsonSupport.json.parseToJsonElement(responseText).jsonObject
                    val dbReady = jsonElement["dbReady"]?.jsonPrimitive?.booleanOrNull ?: false
                    val isDbActive = jsonElement["isDbActive"]?.jsonPrimitive?.booleanOrNull ?: false
                    val leaderIp = jsonElement["leaderIp"]?.jsonPrimitive?.contentOrNull
                    return PeerStatus(dbReady, isDbActive, leaderIp)
                }
            } catch (e: Exception) {
                println("[Status] Failed to query status from $ip on port $port: ${e.message}")
            }
        }
        return null
    }

    fun startFailoverLoop() {
        startFailoverDaemon(tailscaleIp, port, isInsecure)
    }

    private fun startFailoverDaemon(tailscaleIp: String, port: Int, isInsecure: Boolean) {
        val scope = CoroutineScope(Dispatchers.Default)
        var lastClockSyncTime = 0L
        pollJob = scope.launch {
            while (isActive) {
                delay(5000) // check every 5 seconds
                try {
                    val leader = currentLeaderIp
                    if (leader != null) {
                        var leaderAlive = false
                        if (leader == tailscaleIp) {
                            leaderAlive = process?.isAlive ?: false
                        } else {
                            val status = queryPeerStatus(leader)
                            leaderAlive = status != null && status.isDbActive
                        }

                        if (leaderAlive) {
                            failedLeaderChecks = 0
                            
                            // Periodically sync clock with the remote leader to prevent "timestamp too far in future" errors
                            if (leader != tailscaleIp) {
                                val now = System.currentTimeMillis()
                                if (now - lastClockSyncTime > 30000) {
                                    syncClockWithLeader(leader)
                                    lastClockSyncTime = now
                                }
                            }
                        } else {
                            failedLeaderChecks++
                            println("[Cockroach] Database Leader ($leader) check failed ($failedLeaderChecks/5).")
                            if (failedLeaderChecks >= 5) {
                                println("[Cockroach] Database Leader ($leader) has gone offline for 25 seconds! Starting failover...")
                                failedLeaderChecks = 0
                                
                                val currentPeers = try {
                                GoogleSheetsManager.fetchPeers(appConfig.google_sheet_url, appConfig.google_sheet_password)
                            } catch (e: Exception) {
                                emptyList()
                            }
                            
                            val peers = if (currentPeers.isNotEmpty()) currentPeers else listOf(Pair(tailscaleIp, port))
                            val amIInSheet = peers.any { it.first == tailscaleIp }

                            var newLeaderIp: String? = null
                            for (peer in peers) {
                                if (peer.first == tailscaleIp) {
                                    println("[Cockroach] We ($tailscaleIp) are the next priority online node. Designating ourselves as the new leader.")
                                    newLeaderIp = tailscaleIp
                                    break
                                } else {
                                    val status = queryPeerStatus(peer.first)
                                    if (status != null) {
                                        println("[Cockroach] Peer ${peer.first} is online. Electing it as the new leader.")
                                        newLeaderIp = peer.first
                                        break
                                    }
                                }
                            }

                            if (newLeaderIp != null && newLeaderIp != leader) {
                                println("[Cockroach] Failover completed. New Leader: $newLeaderIp")
                                 currentLeaderIp = newLeaderIp
                                if (newLeaderIp != tailscaleIp) {
                                    syncClockWithLeader(newLeaderIp)
                                }

                                stop()
                                
                                val joinPeers = if (amIInSheet) peers else listOf(Pair(newLeaderIp, port))
                                val dbProcess = startCockroachProcess(tailscaleIp, port, joinPeers, isInsecure)
                                process = dbProcess
                                isDbActive = true
                                
                                waitForPort(tailscaleIp, port, 30)

                                if (newLeaderIp == tailscaleIp) {
                                    println("[Cockroach] We are the new leader. Initializing cluster...")
                                    initializeCluster(tailscaleIp, port, isInsecure)
                                    waitForSqlReady(tailscaleIp, port, isInsecure, 60)
                                    configureClusterReplication(tailscaleIp, port, isInsecure)
                                    // Wait for Raft leaseholders to stabilize before DatabaseFactory.init
                                    // runs DDL. Without this, CREATE TABLE blocks indefinitely because the
                                    // meta ranges have no leaseholder yet after a fresh cluster restart.
                                    waitForRaftReady(tailscaleIp, port, isInsecure, 120)
                                }

                                val newDbConfig = DatabaseConfig(
                                    type = "postgres",
                                    postgres = PostgresConfig(
                                        host = newLeaderIp,
                                        port = port,
                                        database = "obsidianscoutjava",
                                        user = if (appConfig.db_username.isNotBlank()) appConfig.db_username else "root",
                                        password = appConfig.db_password,
                                        ssl = !isInsecure
                                    )
                                )
                                 com.obsidianscout.db.DatabaseFactory.orchestrator = this@CockroachOrchestrator
                                 com.obsidianscout.db.DatabaseFactory.init(newDbConfig, runMigration = (newLeaderIp == tailscaleIp), isCockroach = true)
                            }
                        }
                    }
                }
                } catch (e: Exception) {
                    println("[Cockroach] Error in failover daemon loop: ${e.message}")
                }
            }
        }
    }

    private fun testBinaryExecution(): Boolean {
        return try {
            val testProcess = ProcessBuilder(binaryFile.absolutePath, "version")
                .start()
            val output = testProcess.inputStream.bufferedReader().use { it.readText() }
            testProcess.waitFor()
            output.contains(version.removePrefix("v"))
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureInstalled() {
        if (binaryFile.exists()) {
            if (testBinaryExecution()) {
                println("[Cockroach] Local binary found and verified at ${binaryFile.absolutePath}")
                return
            } else {
                println("[Cockroach] Local binary execution check failed. Deleting stale binary to redownload with correct architecture...")
                binaryFile.delete()
                val dataDir = File(rootDir, "data")
                if (dataDir.exists()) {
                    println("[Cockroach] CockroachDB version changed. Wiping old data directory to prevent version incompatibility crashes...")
                    dataDir.deleteRecursively()
                }
            }
        }

        rootDir.mkdirs()

        if (isWindows) {
            downloadAndExtractWindowsZip()
        } else {
            downloadAndExtractLinuxTarball()
        }

        if (!binaryFile.exists()) {
            throw IllegalStateException("Failed to extract CockroachDB binary to ${binaryFile.absolutePath}")
        }

        binaryFile.setExecutable(true, false)
        println("[Cockroach] CockroachDB installation completed successfully.")
    }

    private fun downloadAndExtractLinuxTarball() {
        val tgzFile = File(rootDir, "cockroach.tgz")
        val archLabel = if (isArm) "ARM64" else "AMD64"
        println("[Cockroach] Downloading stable Linux $archLabel tarball from $linuxDownloadUrl...")
        URL(linuxDownloadUrl).openStream().use { input ->
            tgzFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        println("[Cockroach] Extracting tarball using native tar...")
        val tarProcess = ProcessBuilder("tar", "-xzf", tgzFile.name, "-C", ".")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()

        val exitCode = tarProcess.waitFor()
        if (exitCode != 0) {
            val errorText = tarProcess.inputStream.bufferedReader().readText()
            tgzFile.delete()
            throw IllegalStateException("Failed to extract tarball. Native tar exit code: $exitCode, Error: $errorText")
        }

        tgzFile.delete()

        // Locate extracted binary (it gets extracted into cockroach-<version>.linux-amd64/cockroach)
        val extractedDir = rootDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("cockroach-") }
        if (extractedDir != null) {
            val extractedBinary = File(extractedDir, "cockroach")
            if (extractedBinary.exists()) {
                Files.move(extractedBinary.toPath(), binaryFile.toPath())
            }
            extractedDir.deleteRecursively()
        }
    }

    private fun downloadAndExtractWindowsZip() {
        val zipFile = File(rootDir, "cockroach.zip")
        println("[Cockroach] Downloading stable Windows amd64 zip from $windowsDownloadUrl...")
        URL(windowsDownloadUrl).openStream().use { input ->
            zipFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        println("[Cockroach] Extracting zip file...")
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(rootDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile.mkdirs()
                    newFile.outputStream().use { fos ->
                        zis.copyTo(fos)
                    }
                }
                entry = zis.nextEntry
            }
        }

        zipFile.delete()

        // Locate extracted binary (it gets extracted into cockroach-<version>.windows-amd64/cockroach.exe)
        val extractedDir = rootDir.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("cockroach-") }
        if (extractedDir != null) {
            val extractedBinary = File(extractedDir, "cockroach.exe")
            if (extractedBinary.exists()) {
                Files.move(extractedBinary.toPath(), binaryFile.toPath())
            }
            extractedDir.deleteRecursively()
        }
    }

    private fun getTailscaleIp(): String {
        println("[Cockroach] Locating Tailscale interface...")
        val maxWaitMs = 60_000
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxWaitMs) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                if (interfaces != null) {
                    while (interfaces.hasMoreElements()) {
                        val netInterface = interfaces.nextElement()
                        val name = netInterface.name.lowercase()
                        val displayName = netInterface.displayName.lowercase()
                        if (name.contains("tailscale") || displayName.contains("tailscale") || name.startsWith("utun") || name.contains("tun")) {
                            val addresses = netInterface.inetAddresses
                            while (addresses.hasMoreElements()) {
                                val addr = addresses.nextElement()
                                if (!addr.isLoopbackAddress && addr is Inet4Address && addr.hostAddress.startsWith("100.")) {
                                    return addr.hostAddress
                                }
                            }
                        }
                    }
                }
                
                // Fallback scan for any 100.x.y.z IPv4 address
                val allInterfaces = NetworkInterface.getNetworkInterfaces()
                if (allInterfaces != null) {
                    while (allInterfaces.hasMoreElements()) {
                        val netInterface = allInterfaces.nextElement()
                        val addresses = netInterface.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val addr = addresses.nextElement()
                            if (!addr.isLoopbackAddress && addr is Inet4Address && addr.hostAddress.startsWith("100.")) {
                                return addr.hostAddress
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore transient network errors during boot-time lookup
            }
            Thread.sleep(2000)
        }

        // Fallback for debugging when tailscale interface might not be active but configured
        val fallbackEnv = System.getenv("COCKROACH_TAILSCALE_FALLBACK")
        if (!fallbackEnv.isNullOrBlank()) {
            println("[Cockroach] WARNING: Tailscale interface not found. Using fallback env: $fallbackEnv")
            return fallbackEnv
        }
        
        throw IllegalStateException("Tailscale network interface (IP starting with 100.x.y.z) not found after waiting 60s. CockroachDB orchestration requires Tailscale to be running.")
    }

    private fun generateCertificates(tailscaleIp: String, dbUser: String) {
        val certsDir = File(rootDir, "certs")
        if (certsDir.exists()) {
            // Already generated, skip to speed up startup
            return
        }
        certsDir.mkdirs()

        println("[Cockroach] Generating secure cluster certificates...")
        val binaryPath = binaryFile.absolutePath

        val caKeyPath = File(certsDir, "ca.key").absolutePath
        val certsDirPath = certsDir.absolutePath

        // 1. Create CA
        runCommand(listOf(binaryPath, "cert", "create-ca", "--certs-dir=$certsDirPath", "--ca-key=$caKeyPath")).waitFor()
        // 2. Create node certificate
        runCommand(listOf(binaryPath, "cert", "create-node", tailscaleIp, "localhost", "127.0.0.1", "--certs-dir=$certsDirPath", "--ca-key=$caKeyPath")).waitFor()
        // 3. Create root client certificate
        runCommand(listOf(binaryPath, "cert", "create-client", "root", "--certs-dir=$certsDirPath", "--ca-key=$caKeyPath")).waitFor()
        // 4. Create custom user client certificate if not root
        if (dbUser.isNotBlank() && dbUser != "root") {
            runCommand(listOf(binaryPath, "cert", "create-client", dbUser, "--certs-dir=$certsDirPath", "--ca-key=$caKeyPath")).waitFor()
        }
    }

    private fun startCockroachProcess(
        tailscaleIp: String,
        port: Int,
        peers: List<Pair<String, Int>>,
        isInsecure: Boolean
    ): Process {
        val logFile = File(rootDir, "cockroach.log")
        
        val cmd = mutableListOf(
            binaryFile.absolutePath,
            "start",
            "--listen-addr=$tailscaleIp:$port",
            "--advertise-addr=$tailscaleIp:$port",
            "--http-addr=$tailscaleIp:${port + 1}",
            "--store=${File(rootDir, "data").absolutePath}",
            "--logtostderr=INFO",
            "--vmodule=replicate_queue=1,allocator=1,rebalancer=1",
            "--max-offset=500ms"
        )

        if (isInsecure) {
            cmd.add("--insecure")
        } else {
            val certsDir = File(rootDir, "certs")
            cmd.add("--certs-dir=${certsDir.absolutePath}")
        }

        if (peers.isNotEmpty()) {
            val joinStr = peers.joinToString(",") { "${it.first}:${it.second}" }
            cmd.add("--join=$joinStr")
        } else {
            cmd.add("--join=$tailscaleIp:$port")
        }

        println("[Cockroach] Executing command: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))

        return pb.start()
    }

    private fun initializeCluster(tailscaleIp: String, port: Int, isInsecure: Boolean) {
        val logFile = File(rootDir, "cockroach.log")
        val cmd = mutableListOf(
            binaryFile.absolutePath,
            "init",
            "--host=$tailscaleIp:$port"
        )

        if (isInsecure) {
            cmd.add("--insecure")
        } else {
            val certsDir = File(rootDir, "certs")
            cmd.add("--certs-dir=${certsDir.absolutePath}")
        }

        val initPb = ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))

        val initProcess = initPb.start()
        val exitCode = initProcess.waitFor()
        println("[Cockroach] Cluster initialization returned exit code: $exitCode")
    }

    fun startReplicationMonitor() {
        replicationJob?.cancel()

        replicationJob = CoroutineScope(Dispatchers.IO).launch {
            var currentReplicas = 1
            var lastDiagnosticsTime = 0L
            var lastLeaseHealTime = 0L
            var lastQueueHealTime = 0L
            var lastClockSyncTime = 0L
            var consecutiveInvalidLeaseRounds = 0
            println("[Cockroach] Starting background replication monitor with self-healing...")
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()

                    // ── Self-Healing: Local Process Crash ─────────────────────────────
                    // Runs every loop (10s). Detects if the local CockroachDB process
                    // died unexpectedly (e.g., fatal Pebble/disk error on SD card).
                    // If a fatal disk error is detected in the log, wipes the data
                    // directory so the node rejoins with a clean snapshot from peers.
                    val localProcess = process
                    if (localProcess != null && !localProcess.isAlive) {
                        val exitCode = localProcess.exitValue()
                        println("[ProcessMonitor] ⚠️ Local CockroachDB process has died (exit=$exitCode). Investigating...")
                        val fatalDisk = checkForFatalDiskError()
                        if (fatalDisk) {
                            println("[ProcessMonitor] 💾 Fatal storage/disk error detected in log (likely SD card I/O failure). Wiping data directory for clean rejoin...")
                            wipeDataDirectory()
                        }
                        try {
                            val peers = try {
                                GoogleSheetsManager.fetchPeers(appConfig.google_sheet_url, appConfig.google_sheet_password)
                            } catch (e: Exception) {
                                emptyList<Pair<String, Int>>()
                            }
                            val joinPeers = if (peers.isNotEmpty()) peers else listOf(Pair(currentLeaderIp ?: tailscaleIp, port))
                            val newProc = startCockroachProcess(tailscaleIp, port, joinPeers, isInsecure)
                            process = newProc
                            isDbActive = true
                            println("[ProcessMonitor] ✅ CockroachDB restarted. Waiting for port to open...")
                            waitForPort(tailscaleIp, port, 45)
                            println("[ProcessMonitor] CockroachDB port open. Node rejoining cluster.")
                        } catch (e: Exception) {
                            println("[ProcessMonitor] Failed to restart CockroachDB: ${e.message}")
                        }
                    }

                    if (amILeader()) {
                        val activeNodes = getActiveNodesCount()
                        if (activeNodes >= 2 && currentReplicas == 1) {
                            println("[Cockroach] $activeNodes active nodes detected. Upgrading cluster replication factor to 3...")
                            if (setReplicationFactor(3)) {
                                currentReplicas = 3
                                println("[Cockroach] Replication factor successfully set to 3.")
                                // ── Throttle snapshot rate so Pi nodes aren't overwhelmed ──────────
                                // Default is 32 MiB/s which overruns Pi4's Raft commit capacity
                                // during the initial snapshot burst, causing Raft timeouts and
                                // poisoned-latch quorum loss on individual ranges. 4 MiB/s is safe
                                // for a Pi4 over Tailscale without stalling normal write traffic.
                                applySnapshotRateLimits()
                            }
                        } else if (activeNodes < 2 && currentReplicas == 3) {
                            println("[Cockroach] WARNING: Active nodes fell to $activeNodes. Cluster is under-quorum.")
                        }

                        // ── Self-Healing: Invalid Leases ──────────────────────────────────
                        // Runs every 30s. If all leases are invalid, the replicate queue
                        // is completely frozen. Detect this and force lease re-acquisition.
                        if (now - lastLeaseHealTime > 30_000) {
                            val invalidLeases = getMetricValue("replicas.leaders_invalid_lease")
                            val totalLeaders  = getMetricValue("replicas.leaders")
                            val leaseholders  = getMetricValue("replicas.leaseholders")
                            if (invalidLeases != null && totalLeaders != null && leaseholders != null) {
                                val allInvalid = totalLeaders > 0 && leaseholders == 0L && invalidLeases >= totalLeaders
                                if (allInvalid) {
                                    consecutiveInvalidLeaseRounds++
                                    println("[LeaseHealer] ⚠️ All $invalidLeases leases are invalid (round $consecutiveInvalidLeaseRounds). Leaseholders=0. Triggering lease re-acquisition...")
                                    healInvalidLeases()
                                } else {
                                    if (consecutiveInvalidLeaseRounds > 0)
                                        println("[LeaseHealer] ✅ Leases recovered. Leaseholders=$leaseholders, Invalid=$invalidLeases")
                                    consecutiveInvalidLeaseRounds = 0
                                }
                            }
                            lastLeaseHealTime = now
                        }

                        // ── Self-Healing: Frozen Replicate Queue ─────────────────────────
                        // Runs every 60s. If the queue has pending items but addreplica=0
                        // and no snapshots have been generated, the queue is frozen.
                        if (now - lastQueueHealTime > 60_000) {
                            val pending     = getMetricValue("queue.replicate.pending")
                            val addReplica  = getMetricValue("queue.replicate.addreplica")
                            val snapshots   = getMetricValue("range.snapshots.generated")
                            val underRep    = getMetricValue("ranges.underreplicated")
                            if (pending != null && addReplica != null && snapshots != null && underRep != null) {
                                val queueFrozen = pending > 0 && addReplica == 0L && snapshots == 0L && underRep > 0
                                if (queueFrozen) {
                                    println("[QueueHealer] ⚠️ Replicate queue frozen: pending=$pending, addreplica=0, snapshots=0, underRep=$underRep. Applying remediation...")
                                    healFrozenReplicateQueue()
                                } else if (underRep == 0L) {
                                    println("[QueueHealer] ✅ Cluster fully replicated.")
                                }
                            }
                            lastQueueHealTime = now
                        }
                    }

                    // ── Self-Healing: Clock Drift (all nodes, not just leader) ────────
                    // Runs every 60s on every node. Measures ACTUAL wall-clock offset
                    // against the leader via HTTP — NOT the closed_ts lag, which is a
                    // Raft commit performance metric and not a clock synchronization issue.
                    // Fixes the root cause of lease invalidation on Pi nodes after network blips.
                    if (now - lastClockSyncTime > 60_000) {
                        val leader = currentLeaderIp
                        if (leader != null && leader != tailscaleIp) {
                            val leaderTimeMs = fetchLeaderTime(leader)
                            if (leaderTimeMs != null) {
                                val wallOffsetMs = Math.abs(leaderTimeMs - System.currentTimeMillis())
                                if (wallOffsetMs > 400) {
                                    println("[ClockHealer] ⚠️ Wall-clock is ${wallOffsetMs}ms off vs leader. Forcing NTP resync...")
                                    forceNtpSync()
                                } else {
                                    // Distinguish Raft lag from clock skew in diagnostics
                                    val closedTsMs = (getMetricValue("kv.closed_timestamp.max_behind_nanos") ?: 0L) / 1_000_000
                                    if (closedTsMs > 2000) {
                                        println("[ClockHealer] ℹ️ Closed_ts=${closedTsMs}ms behind but wall-clock OK (${wallOffsetMs}ms). Cause: slow Raft commits, not clock drift.")
                                    }
                                }
                            }
                        }
                        lastClockSyncTime = now
                    }

                    if (now - lastDiagnosticsTime > 20_000) {
                        runReplicationDiagnostics()
                        lastDiagnosticsTime = now
                    }
                } catch (e: Exception) {
                    println("[ReplicationMonitor] Error in monitor loop: ${e.message}")
                }
                delay(10_000)
            }
        }
    }

    /**
     * Reads a single numeric metric from crdb_internal.node_metrics.
     * Returns null if unavailable.
     */
    private fun getMetricValue(metricName: String): Long? {
        return try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return null
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET allow_unsafe_internals = true")
                    stmt.executeQuery(
                        "SELECT value FROM crdb_internal.node_metrics WHERE name = '$metricName' LIMIT 1"
                    ).use { rs ->
                        if (rs.next()) rs.getLong(1) else null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Scans the last 200 lines of cockroach.log for Pebble fatal errors that
     * indicate disk hardware failure (SD card I/O errors on Raspberry Pi).
     * Returns true if a disk-fatal log entry is found.
     */
    private fun checkForFatalDiskError(): Boolean {
        val logFile = File(rootDir, "cockroach.log")
        if (!logFile.exists()) return false
        return try {
            val lastLines = logFile.readLines().takeLast(200)
            lastLines.any { line ->
                // Pebble fatal lines start with 'F' and reference storage/disk
                (line.startsWith("F") || line.contains("fatal error") || line.contains("pebble")) &&
                (line.contains("faulty hardware") || line.contains("storage/pebble") ||
                 line.contains("I/O error") || line.contains("disk") || line.contains("terminating due to a fatal"))
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wipes the CockroachDB data directory.
     * Called only after a confirmed fatal disk/storage error.
     * On restart, CockroachDB will rejoin the cluster and receive a fresh
     * snapshot from the leader — rebuilding its state from peers.
     */
    private fun wipeDataDirectory() {
        val dataDir = File(rootDir, "data")
        try {
            if (dataDir.exists()) {
                dataDir.deleteRecursively()
                println("[ProcessMonitor] Data directory wiped: ${dataDir.absolutePath}")
            }
        } catch (e: Exception) {
            println("[ProcessMonitor] Warning: failed to fully wipe data directory: ${e.message}")
        }
    }

    /**
     * Self-Healing: Invalid Leases
     *
     * When replicas.leaders_invalid_lease == replicas.leaders (all leases gone), the
     * replicate queue is completely frozen. This happens when:
     *   1. Periodic network blips increment the liveness epoch on other nodes
     *   2. The old epoch-based leases are instantly invalidated
     *   3. CockroachDB can't re-acquire because clock drift > max-offset
     *
     * Fix: Force lease transfers away from and back to n1, reset snapshot rates,
     * and trigger the lease queue to re-process.
     */
    private fun healInvalidLeases() {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET allow_unsafe_internals = true")

                    // Step 1: Reset snapshot rates to defaults to unblock any throttled paths
                    try {
                        stmt.execute("SET CLUSTER SETTING kv.snapshot_rebalance.max_rate = '32 MiB'")
                        stmt.execute("SET CLUSTER SETTING kv.snapshot_recovery.max_rate  = '32 MiB'")
                        println("[LeaseHealer] Reset snapshot rates to 32 MiB")
                    } catch (e: Exception) {
                        println("[LeaseHealer] Could not set snapshot rates: ${e.message}")
                    }

                    // Step 2: Nudge the lease queue by toggling a cluster setting
                    // This causes CockroachDB to re-evaluate all lease holders
                    try {
                        stmt.execute("SET CLUSTER SETTING kv.allocator.load_based_lease_rebalancing.enabled = false")
                        Thread.sleep(2000)
                        stmt.execute("SET CLUSTER SETTING kv.allocator.load_based_lease_rebalancing.enabled = true")
                        println("[LeaseHealer] Toggled load-based lease rebalancing to force re-evaluation")
                    } catch (e: Exception) {
                        println("[LeaseHealer] Could not toggle lease rebalancing: ${e.message}")
                    }

                    // Step 3: If leases are still invalid after toggle, re-apply zone configs
                    // to wake up the allocator and force it to re-assign leases
                    try {
                        val dbName = appConfig.database.postgres.database.lowercase()
                        stmt.execute("ALTER RANGE default CONFIGURE ZONE USING num_replicas = 3")
                        stmt.execute("ALTER RANGE system  CONFIGURE ZONE USING num_replicas = 3")
                        stmt.execute("ALTER DATABASE \"$dbName\" CONFIGURE ZONE USING num_replicas = 3")
                        println("[LeaseHealer] Re-applied zone configs to wake allocator")
                    } catch (e: Exception) {
                        println("[LeaseHealer] Could not re-apply zone configs: ${e.message}")
                    }

                    println("[LeaseHealer] Lease healing actions complete. Waiting for CockroachDB to re-acquire leases...")
                }
            }
        } catch (e: Exception) {
            println("[LeaseHealer] Healing failed: ${e.message}")
        }
    }

    /**
     * Self-Healing: Frozen Replicate Queue
     *
     * When queue.replicate.pending > 0 but addreplica = 0 and snapshots.generated = 0
     * the queue is stuck in exponential backoff from repeated context cancellations.
     *
     * Fix: Adjust snapshot rates and force a replication factor re-application which
     * causes CockroachDB to re-enqueue all under-replicated ranges with fresh backoff.
     */
    private fun healFrozenReplicateQueue() {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET allow_unsafe_internals = true")

                    // Bump then restore snapshot rates to kick the sender
                    try {
                        stmt.execute("SET CLUSTER SETTING kv.snapshot_rebalance.max_rate = '16 MiB'")
                        stmt.execute("SET CLUSTER SETTING kv.snapshot_recovery.max_rate  = '16 MiB'")
                        println("[QueueHealer] Set snapshot rates to 16 MiB to unblock sender")
                    } catch (e: Exception) {
                        println("[QueueHealer] Could not set snapshot rates: ${e.message}")
                    }

                    // Re-apply replication factor to force re-enqueue with fresh backoff
                    try {
                        val dbName = appConfig.database.postgres.database.lowercase()
                        stmt.execute("ALTER RANGE default CONFIGURE ZONE USING num_replicas = 3")
                        stmt.execute("ALTER RANGE system  CONFIGURE ZONE USING num_replicas = 3")
                        stmt.execute("ALTER DATABASE \"$dbName\" CONFIGURE ZONE USING num_replicas = 3")
                        println("[QueueHealer] Re-applied zone config to force replication re-enqueue")
                    } catch (e: Exception) {
                        println("[QueueHealer] Could not re-apply zone config: ${e.message}")
                    }

                    println("[QueueHealer] Queue heal actions complete.")
                }
            }
        } catch (e: Exception) {
            println("[QueueHealer] Healing failed: ${e.message}")
        }
    }

    /**
     * Self-Healing: Clock Drift
     *
     * Forces an immediate NTP resync. On Linux (Raspberry Pi) uses chronyc makestep.
     * On Windows uses w32tm /resync /force.
     * Called when kv.closed_timestamp.max_behind_nanos exceeds 400ms.
     */
    private fun forceNtpSync() {
        try {
            val cmd = if (isWindows) {
                listOf("w32tm", "/resync", "/force")
            } else {
                // Try chrony first, fall back to ntpdate
                val chronyAvailable = try {
                    ProcessBuilder("which", "chronyc").start().waitFor() == 0
                } catch (e: Exception) { false }
                if (chronyAvailable) listOf("sudo", "chronyc", "makestep")
                else listOf("sudo", "ntpdate", "-s", "time.cloudflare.com")
            }
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val exited = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            val output = p.inputStream.bufferedReader().use { it.readText() }.trim()
            if (exited && p.exitValue() == 0) {
                println("[ClockHealer] ✅ NTP resync succeeded: $output")
            } else {
                println("[ClockHealer] ⚠️ NTP resync exited=${if(exited) p.exitValue() else -1}: $output")
            }
        } catch (e: Exception) {
            println("[ClockHealer] Failed to run NTP resync: ${e.message}")
        }
    }

    private fun runReplicationDiagnostics() {
        // Pre-fetch metrics via their own short-lived connections BEFORE opening
        // the main diagnostic connection. Avoids holding the pool connection open
        // while 6+ concurrent sub-connections are opened (HikariCP leak warning).
        val invalidLeases = getMetricValue("replicas.leaders_invalid_lease") ?: -1L
        val leaseholders  = getMetricValue("replicas.leaseholders") ?: -1L
        val snapGenerated = getMetricValue("range.snapshots.generated") ?: -1L
        val queuePending  = getMetricValue("queue.replicate.pending") ?: -1L
        val addReplica    = getMetricValue("queue.replicate.addreplica") ?: -1L
        val closedTsLagMs = (getMetricValue("kv.closed_timestamp.max_behind_nanos") ?: 0L) / 1_000_000

        // Pre-read log lines (slow I/O) before opening the DB connection
        val recentLogLines = try {
            val logFile = File(rootDir, "cockroach.log")
            if (logFile.exists()) {
                val allLines = logFile.readLines()
                val matchedLines = mutableListOf<String>()
                val maxLines = allLines.size
                val scanStart = (maxLines - 3000).coerceAtLeast(0)
                var i = scanStart
                while (i < maxLines) {
                    val line = allLines[i]
                    if (line.contains("new range lease") || line.contains("replica_proposal.go")) { i++; continue }
                    val lower = line.lowercase()
                    if (lower.contains("warning") || lower.contains("error") || lower.contains("allocator") ||
                        lower.contains("replicate") || lower.contains("raft") || lower.contains("purgatory")) {
                        matchedLines.add(line)
                        var j = i + 1
                        while (j < maxLines && j < i + 6) {
                            val nextLine = allLines[j]
                            if (nextLine.startsWith(" ") || nextLine.startsWith("\t") ||
                                nextLine.contains("Error types:") || nextLine.contains("wrapper:") ||
                                nextLine.contains("failed:")) {
                                matchedLines.add("    $nextLine"); j++
                            } else break
                        }
                        i = j - 1
                    }
                    i++
                }
                matchedLines.takeLast(30)
            } else emptyList()
        } catch (e: Exception) { emptyList() }

        // Now open the main connection only for the fast SQL queries
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET allow_unsafe_internals = true;")

                    println("=========================================================================")
                    println("[Replication Diagnostics] Running cluster health check...")

                    // 1. Gossip nodes
                    val peerIps = mutableListOf<String>()
                    println("[Replication Diagnostics] Gossip Nodes:")
                    try {
                        stmt.executeQuery("SELECT node_id, address FROM crdb_internal.gossip_nodes;").use { rs ->
                            var count = 0
                            while (rs.next()) {
                                val id = rs.getInt("node_id")
                                val address = rs.getString("address")
                                println("  - Node #$id: $address")
                                peerIps.add(address)
                                count++
                            }
                            println("  Total Gossip Nodes: $count")
                        }
                    } catch (e: Exception) {
                        println("  Could not query gossip nodes: ${e.message?.substringBefore("\n")}")
                    }

                    // 2. Outbound connectivity
                    println("[Replication Diagnostics] Outbound Network Connectivity Check (Port 26257):")
                    for (addr in peerIps) {
                        val ip = addr.substringBefore(":")
                        val p  = addr.substringAfter(":").toIntOrNull() ?: 26257
                        try {
                            java.net.Socket().use { socket ->
                                socket.connect(java.net.InetSocketAddress(ip, p), 3000)
                                println("  - Connection to $ip:$p: SUCCESS")
                            }
                        } catch (e: Exception) {
                            println("  - Connection to $ip:$p: FAILED (${e.message})")
                        }
                    }

                    // 3. Under-replicated ranges (may fail if quorum is lost on a system range)
                    try {
                        stmt.executeQuery("SELECT count(*) FROM crdb_internal.ranges WHERE array_length(replicas, 1) < 3;").use { rs ->
                            if (rs.next()) println("[Replication Diagnostics] Under-replicated ranges: ${rs.getInt(1)}")
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        if (msg.contains("lost quorum") || msg.contains("replica unavailable") || msg.contains("poisoned latch")) {
                            println("[Replication Diagnostics] ⚠️  QUORUM LOSS — ${msg.substringBefore("\n").take(120)}")
                        } else {
                            println("[Replication Diagnostics] Under-replicated count unavailable: ${msg.substringBefore("\n").take(80)}")
                        }
                    }

                    // 3b. Pre-fetched lease and snapshot metrics
                    println("[Replication Diagnostics] Lease health: leaseholders=$leaseholders, invalid=$invalidLeases")
                    println("[Replication Diagnostics] Snapshot queue: generated=$snapGenerated, pending=$queuePending, addreplica=$addReplica")
                    println("[Replication Diagnostics] Raft commit lag: closed_ts=${closedTsLagMs}ms behind")
                    if (invalidLeases > 0 && leaseholders == 0L)
                        println("[Replication Diagnostics] ⚠️  ALL LEASES INVALID — replication queue frozen")
                    if (snapGenerated == 0L && queuePending > 0L)
                        println("[Replication Diagnostics] ⚠️  QUEUE FROZEN — ${queuePending} pending but 0 snapshots sent")
                    if (closedTsLagMs > 2000)
                        println("[Replication Diagnostics] ℹ️  Raft lag: closed_ts=${closedTsLagMs}ms — slow disk or network (not clock drift)")

                    // 4. Default zone config
                    try {
                        stmt.executeQuery("SHOW ZONE CONFIGURATION FOR RANGE default;").use { rs ->
                            if (rs.next()) println("[Replication Diagnostics] default zone config: ${rs.getString("raw_config_sql").replace("\n", " ").trim()}")
                        }
                    } catch (e: Exception) { /* non-critical */ }

                    // 5. Database zone config
                    try {
                        val dbName = conn.catalog.lowercase()
                        stmt.executeQuery("SHOW ZONE CONFIGURATION FOR DATABASE \"$dbName\";").use { rs ->
                            if (rs.next()) println("[Replication Diagnostics] $dbName zone config: ${rs.getString("raw_config_sql").replace("\n", " ").trim()}")
                        }
                    } catch (e: Exception) { /* inherits from default */ }
                }
            }
        } catch (e: Exception) {
            println("[Replication Diagnostics] Failed to run diagnostics: ${e.message?.substringBefore("\n")}")
        }

        // 6. Pre-read log lines printed after connection is closed
        println("[Replication Diagnostics] Recent CockroachDB Warnings/Errors (From Log):")
        if (recentLogLines.isEmpty()) {
            println("  No recent warnings or replication events found in log.")
        } else {
            for (line in recentLogLines) println("  $line")
        }
        println("=========================================================================")
    }

    private fun runSqlViaJdbc(tailscaleIp: String, port: Int, isInsecure: Boolean, block: (java.sql.Connection) -> Unit) {
        val ssl = if (isInsecure) "sslmode=disable" else "sslmode=require"
        val url = "jdbc:postgresql://$tailscaleIp:$port/defaultdb?$ssl"
        try {
            Class.forName("org.postgresql.Driver")
            java.sql.DriverManager.getConnection(url, "root", "").use { conn ->
                block(conn)
            }
        } catch (e: Exception) {
            println("[Cockroach] JDBC connection failed to $tailscaleIp:$port: ${e.message}")
            throw e
        }
    }

    private fun getActiveNodesCount(): Int {
        var count = 1
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource
            if (ds != null) {
                ds.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("SET allow_unsafe_internals = true;")
                        // is_live moved to gossip_liveness in v26.x
                        stmt.executeQuery("SELECT count(*) FROM crdb_internal.gossip_nodes;").use { rs ->
                            if (rs.next()) {
                                count = rs.getInt(1)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[Cockroach] Error querying active nodes count: ${e.message}")
        }
        return count
    }

    /**
     * Sets cluster-level snapshot throughput limits safe for Raspberry Pi nodes over Tailscale.
     * The default 32 MiB/s rate overwhelms Pi4's Raft commit pipeline during the initial
     * snapshot burst, causing individual ranges to go leaderless (poisoned latch quorum loss).
     * 4 MiB/s still replicates 82 ranges in ~20s while keeping each Pi's I/O safely below saturation.
     */
    private fun applySnapshotRateLimits() {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    // Snapshot rate: 8 MiB/s — safe for Pi4 SSD over Tailscale, ~2x faster than 4 MiB
                    stmt.execute("SET CLUSTER SETTING kv.snapshot_rebalance.max_rate = '8 MiB'")
                    stmt.execute("SET CLUSTER SETTING kv.snapshot_recovery.max_rate = '8 MiB'")
                    // Limit concurrent snapshots: prevent Pi from receiving many simultaneously
                    stmt.execute("SET CLUSTER SETTING kv.replication_reports.interval = '30s'")
                    println("[Cockroach] Snapshot rate limits applied: 8 MiB/s max (Pi4 SSD safe)")

                }
            }
        } catch (e: Exception) {
            println("[Cockroach] Warning: could not apply snapshot rate limits: ${e.message}")
        }
    }

    /**
     * Returns true if the cluster has no unavailable ranges.
     * Used by background tasks (DeduplicationScheduler, SyncScheduler) to skip
     * processing during initial replication when some ranges may be temporarily
     * leaderless due to snapshot burst causing Raft timeouts on Pi nodes.
     */
    fun isClusterHealthy(): Boolean {
        return try {
            val unavailable = getMetricValue("ranges.unavailable") ?: 0L
            val underRep    = getMetricValue("ranges.underreplicated") ?: 0L
            // Allow minor under-replication (< 5 ranges) but block if any range is fully unavailable
            unavailable == 0L && underRep < 5L
        } catch (e: Exception) {
            true // If we can't check, don't block background tasks
        }
    }

    private fun setReplicationFactor(replicas: Int): Boolean {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return false
            val dbName = appConfig.database.postgres.database.lowercase()
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    println("[Cockroach] Upgrading cluster replication factor (default, system, database) to $replicas...")
                    stmt.execute("ALTER RANGE default CONFIGURE ZONE USING num_replicas = $replicas")
                    stmt.execute("ALTER RANGE system CONFIGURE ZONE USING num_replicas = $replicas")
                    stmt.execute("ALTER DATABASE \"$dbName\" CONFIGURE ZONE USING num_replicas = $replicas")
                }
            }
            return true
        } catch (e: Exception) {
            println("[Cockroach] Error setting replication factor: ${e.message}")
            return false
        }
    }

    private fun configureClusterReplication(
        tailscaleIp: String,
        port: Int,
        isInsecure: Boolean
    ) {
        try {
            // Set zone configs for built-in ranges only.
            // The application database (obsidianscoutjava) doesn't exist yet at this point —
            // it is created by DatabaseFactory.init -> ensurePostgresDatabaseExists later.
            // The per-DB replication factor is applied by setReplicationFactor() after init.
            runSqlViaJdbc(tailscaleIp, port, isInsecure) { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("ALTER RANGE default CONFIGURE ZONE USING num_replicas = 1")
                    stmt.execute("ALTER RANGE system CONFIGURE ZONE USING num_replicas = 1")
                }
            }
            println("[Cockroach] Set cluster replication factor to 1 via JDBC")
        } catch (e: Exception) {
            println("[Cockroach] Failed to set replication factor to 1: ${e.message}")
        }
    }

    private fun createDatabaseUser(
        tailscaleIp: String,
        port: Int,
        dbUser: String,
        dbPass: String,
        isInsecure: Boolean
    ) {
        try {
            runSqlViaJdbc(tailscaleIp, port, isInsecure) { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE USER IF NOT EXISTS \"$dbUser\" WITH PASSWORD '$dbPass'")
                    stmt.execute("GRANT admin TO \"$dbUser\"")
                }
            }
            println("[Cockroach] User SQL setup completed via JDBC")
        } catch (e: Exception) {
            println("[Cockroach] Failed database user setup: ${e.message}")
        }
    }

    private fun runCommand(cmd: List<String>): Process {
        val logFile = File(rootDir, "cockroach.log")
        return ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
    }

    private fun waitForPort(ip: String, port: Int, timeoutSeconds: Int): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutSeconds * 1000) {
            if (process != null && !process!!.isAlive) {
                // Cockroach process crashed
                return false
            }
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(ip, port), 1000)
                    return true
                }
            } catch (e: Exception) {
                Thread.sleep(500)
            }
        }
        return false
    }

    private fun waitForSqlReady(tailscaleIp: String, port: Int, isInsecure: Boolean, timeoutSeconds: Int): Boolean {
        val start = System.currentTimeMillis()
        val cmd = mutableListOf(
            binaryFile.absolutePath,
            "sql",
            "--host=$tailscaleIp:$port",
            "-e",
            "SELECT 1"
        )
        if (isInsecure) {
            cmd.add("--insecure")
        } else {
            val certsDir = File(rootDir, "certs")
            cmd.add("--certs-dir=${certsDir.absolutePath}")
        }

        while (System.currentTimeMillis() - start < timeoutSeconds * 1000) {
            if (process != null && !process!!.isAlive) {
                return false
            }
            try {
                val p = ProcessBuilder(cmd)
                    .directory(rootDir)
                    .start()
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    return true
                }
            } catch (e: Exception) {
                // ignore
            }
            Thread.sleep(1000)
        }
        return false
    }

    /**
     * Waits until CockroachDB's internal Raft ranges have elected leaseholders.
     * This must be called after [waitForSqlReady] and before running any DDL (CREATE TABLE etc.).
     * Without this, DDL blocks indefinitely because the meta ranges have no leaseholder
     * immediately after a fresh `cockroach init` or a node restart that triggers re-election.
     *
     * The probe queries crdb_internal.ranges and checks that no range has a null/empty
     * lease_holder, which indicates leaseholder election is complete.
     */
    private fun waitForRaftReady(tailscaleIp: String, port: Int, isInsecure: Boolean, timeoutSeconds: Int) {
        val start = System.currentTimeMillis()
        val ssl = if (isInsecure) "sslmode=disable" else "sslmode=require"
        val url = "jdbc:postgresql://$tailscaleIp:$port/defaultdb?$ssl"
        println("[Cockroach] Waiting for Raft leaseholders to stabilize (up to ${timeoutSeconds}s)...")
        while (System.currentTimeMillis() - start < timeoutSeconds * 1000L) {
            if (process != null && !process!!.isAlive) {
                println("[Cockroach] Process died while waiting for Raft ready.")
                return
            }
            try {
                Class.forName("org.postgresql.Driver")
                java.sql.DriverManager.getConnection(url, "root", "").use { conn ->
                    conn.autoCommit = true
                    // Count ranges that have no leaseholder yet.
                    val rs = conn.createStatement().executeQuery(
                        "SELECT count(*) FROM crdb_internal.ranges WHERE lease_holder = 0 OR lease_holder IS NULL"
                    )
                    if (rs.next()) {
                        val unhealthy = rs.getInt(1)
                        if (unhealthy == 0) {
                            println("[Cockroach] Raft leaseholders stabilized. Proceeding with schema init.")
                            return
                        }
                        val elapsed = (System.currentTimeMillis() - start) / 1000
                        println("[Cockroach] Waiting for Raft: $unhealthy range(s) still electing leaseholder (elapsed: ${elapsed}s)...")
                    }
                }
            } catch (e: Exception) {
                // Not ready yet — CRDB may still be initializing internal tables
            }
            Thread.sleep(2000)
        }
        println("[Cockroach] WARNING: Raft leaseholder wait timed out after ${timeoutSeconds}s. Proceeding anyway — DDL may be slow.")
    }

    private fun isPeerOnline(ip: String, port: Int, isInsecure: Boolean): Boolean {
        val portOpen = try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, port), 1500)
                true
            }
        } catch (e: Exception) {
            false
        }
        if (!portOpen) return false

        val cmd = mutableListOf(
            binaryFile.absolutePath,
            "sql",
            "--host=$ip:$port",
            "-e",
            "SELECT 1"
        )
        if (isInsecure) {
            cmd.add("--insecure")
        } else {
            val certsDir = File(rootDir, "certs")
            cmd.add("--certs-dir=${certsDir.absolutePath}")
        }
        return try {
            val p = ProcessBuilder(cmd)
                .directory(rootDir)
                .start()
            val completed = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            completed && p.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        var currentLeaderIp: String? = null

        @Volatile
        var isDbActive = false
    }
}
