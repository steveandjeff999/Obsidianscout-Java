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
    private val knownPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, Int>>()
    private var tailscaleIp: String = "127.0.0.1"
    private var port: Int = 26257
    private var isInsecure: Boolean = true
    private var failedLeaderChecks = 0

    private val version = "v24.1.1"
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
        println("[Cockroach] Starting autonomous database lifecycle...")

        // 1. Ensure installed
        ensureInstalled()

        // 2. Identify local Tailscale IP
        val tailscaleIp = getTailscaleIp()
        this.tailscaleIp = tailscaleIp
        val port = appConfig.cockroach_port
        this.port = port
        println("[Cockroach] Bound to Tailscale IP: $tailscaleIp on port $port")

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
                    println("[Cockroach] Peer at ${peer.first} reports leader is ${status.leaderIp}")
                    leaderIp = status.leaderIp
                    break
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
    }

    private fun fetchLeaderTime(leaderIp: String): Long? {
        val ports = listOf(appConfig.server.port, 8080, 8888, 80).distinct()
        for (port in ports) {
            try {
                val url = java.net.URL("http://$leaderIp:$port/api/cluster/time")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 1500
                connection.readTimeout = 1500
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

    private fun queryPeerStatus(ip: String): PeerStatus? {
        val ports = listOf(appConfig.server.port, 8080, 8888, 80).distinct()
        for (port in ports) {
            try {
                val url = java.net.URL("http://$ip:$port/api/cluster/status")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 1500
                connection.readTimeout = 1500
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
                                    waitForSqlReady(tailscaleIp, port, isInsecure, 30)
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
                                com.obsidianscout.db.DatabaseFactory.init(newDbConfig)
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
            "--max-offset=4s"
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

    private fun createDatabaseUser(
        tailscaleIp: String,
        port: Int,
        dbUser: String,
        dbPass: String,
        isInsecure: Boolean
    ) {
        val logFile = File(rootDir, "cockroach.log")
        val sql = "CREATE USER IF NOT EXISTS \"$dbUser\" WITH PASSWORD '$dbPass'; GRANT admin TO \"$dbUser\";"
        
        val cmd = mutableListOf(
            binaryFile.absolutePath,
            "sql",
            "--host=$tailscaleIp:$port",
            "-e",
            sql
        )

        if (isInsecure) {
            cmd.add("--insecure")
        } else {
            val certsDir = File(rootDir, "certs")
            cmd.add("--certs-dir=${certsDir.absolutePath}")
        }

        val sqlPb = ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))

        val sqlProcess = sqlPb.start()
        val exitCode = sqlProcess.waitFor()
        println("[Cockroach] User SQL setup returned exit code: $exitCode")
    }

    private fun runCommand(cmd: List<String>): Process {
        return ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectErrorStream(true)
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
