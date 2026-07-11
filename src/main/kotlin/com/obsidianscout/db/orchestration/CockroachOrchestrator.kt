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


class CockroachOrchestrator(private val appConfig: AppConfig) {

    private var process: Process? = null
    private val rootDir = File(".cockroach")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isArm = System.getProperty("os.arch").lowercase().let { it.contains("arm") || it.contains("aarch64") }
    private val binaryName = if (isWindows) "cockroach.exe" else "cockroach"
    private val binaryFile = File(rootDir, binaryName)
    private var pollJob: Job? = null
    private val knownPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, Int>>()

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
        println("[Cockroach] Starting autonomous database lifecycle...")

        // 1. Ensure installed
        ensureInstalled()

        // 2. Identify local Tailscale IP
        val tailscaleIp = getTailscaleIp()
        val port = appConfig.cockroach_port
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

        // 4. Handle secure/insecure certificates
        val isInsecure = appConfig.db_username.isBlank() && appConfig.db_password.isBlank()
        if (!isInsecure) {
            generateCertificates(tailscaleIp, appConfig.db_username)
        }

        // 5. Start the CockroachDB process
        val dbProcess = startCockroachProcess(tailscaleIp, port, peers, isInsecure)
        this.process = dbProcess

        // 6. Wait for CockroachDB port to open
        println("[Cockroach] Waiting for CockroachDB to start listening...")
        if (!waitForPort(tailscaleIp, port, 45)) {
            val logFile = File(rootDir, "cockroach.log")
            val logSnippet = if (logFile.exists()) logFile.readLines().takeLast(20).joinToString("\n") else "No logs found."
            throw IllegalStateException("CockroachDB failed to bind to $tailscaleIp:$port within timeout. Last logs:\n$logSnippet")
        }
        println("[Cockroach] CockroachDB is listening on $tailscaleIp:$port")

        // 7. Determine if we need to initialize a new cluster.
        // We only initialize if NONE of the other peers in the sheet are currently online.
        val otherPeers = peers.filter { it.first != tailscaleIp || it.second != port }
        
        // If we are not the first node listed in the sheet, wait a short moment to let the primary node boot first
        val isFirstInSheet = peers.firstOrNull()?.first == tailscaleIp
        if (!isFirstInSheet && otherPeers.isNotEmpty()) {
            println("[Cockroach] We are not the first peer in the sheet. Waiting 6 seconds to let the primary node initialize first...")
            Thread.sleep(6000)
        }

        val activePeer = otherPeers.firstOrNull { isPeerOnline(it.first, it.second) }
        val isPrimary = activePeer == null
        if (isPrimary) {
            println("[Cockroach] No active peers are online. We will act as the cluster initializer ($tailscaleIp)...")
            initializeCluster(tailscaleIp, port, isInsecure)
        } else {
            println("[Cockroach] Found active peer online at ${activePeer!!.first}:${activePeer.second}. Joining existing cluster...")
        }

        // 8. Wait for SQL engine to be fully ready
        println("[Cockroach] Waiting for SQL engine to be ready...")
        if (!waitForSqlReady(tailscaleIp, port, isInsecure, 60)) {
            println("[Cockroach] WARNING: SQL engine did not become ready in time. Proceeding anyway...")
        } else {
            println("[Cockroach] SQL engine is ready.")
        }

        // 9. Setup DB User and Password if requested
        if (!isInsecure && appConfig.db_username.isNotBlank() && appConfig.db_username != "root") {
            println("[Cockroach] Creating application database user: ${appConfig.db_username}...")
            createDatabaseUser(tailscaleIp, port, appConfig.db_username, appConfig.db_password, isInsecure)
        }

        // 10. Start periodic polling of the Google Sheet for new cluster peers
        startPolling(tailscaleIp, port)

        // Return a config mapping to this local instance as a PostgreSQL database
        return DatabaseConfig(
            type = "postgres",
            postgres = PostgresConfig(
                host = tailscaleIp,
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
    }

    private fun startPolling(tailscaleIp: String, port: Int) {
        val initialPeers = GoogleSheetsManager.fetchPeers(appConfig.google_sheet_url, appConfig.google_sheet_password)
        knownPeers.addAll(initialPeers.filter { it.first != tailscaleIp || it.second != port })
        
        val scope = CoroutineScope(Dispatchers.IO)
        pollJob = scope.launch {
            while (isActive) {
                delay(60_000) // Poll every 60 seconds
                try {
                    val currentPeers = GoogleSheetsManager.fetchPeers(appConfig.google_sheet_url, appConfig.google_sheet_password)
                    val newPeers = currentPeers.filter { 
                        (it.first != tailscaleIp || it.second != port) && it !in knownPeers 
                    }
                    if (newPeers.isNotEmpty()) {
                        println("[Cockroach] New peer servers detected on Google Sheet: ${newPeers.joinToString { "${it.first}:${it.second}" }}")
                        knownPeers.addAll(newPeers)
                    }
                } catch (e: Exception) {
                    println("[Cockroach] Error polling Google Sheet for new peers: ${e.message}")
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

        // 1. Create CA
        runCommand(listOf(binaryPath, "cert", "create-ca", "--certs-dir=${certsDir.absolutePath}", "--ca-key=${certsDir.absolutePath}/ca.key")).waitFor()
        // 2. Create node certificate
        runCommand(listOf(binaryPath, "cert", "create-node", tailscaleIp, "localhost", "127.0.0.1", "--certs-dir=${certsDir.absolutePath}", "--ca-key=${certsDir.absolutePath}/ca.key")).waitFor()
        // 3. Create root client certificate
        runCommand(listOf(binaryPath, "cert", "create-client", "root", "--certs-dir=${certsDir.absolutePath}", "--ca-key=${certsDir.absolutePath}/ca.key")).waitFor()
        // 4. Create custom user client certificate if not root
        if (dbUser.isNotBlank() && dbUser != "root") {
            runCommand(listOf(binaryPath, "cert", "create-client", dbUser, "--certs-dir=${certsDir.absolutePath}", "--ca-key=${certsDir.absolutePath}/ca.key")).waitFor()
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
            "--store=${rootDir.absolutePath}/data"
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

    private fun isPeerOnline(ip: String, port: Int): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, port), 2000)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
