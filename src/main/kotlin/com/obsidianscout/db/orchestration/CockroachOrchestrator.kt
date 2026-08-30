package com.obsidianscout.db.orchestration

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.PostgresConfig
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class PeerStatus(val dbReady: Boolean, val isDbActive: Boolean)

class CockroachOrchestrator(private val appConfig: AppConfig) {

    private var process: Process? = null
    private val rootDir: File = try {
        val uri = CockroachOrchestrator::class.java.protectionDomain.codeSource.location.toURI()
        val jarFile = File(uri)
        val parent = if (jarFile.isFile) jarFile.parentFile else File(".")
        File(parent, ".cockroach")
    } catch (e: Exception) {
        File(".cockroach")
    }
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isArm = System.getProperty("os.arch").lowercase().let { it.contains("arm") || it.contains("aarch64") }
    private val binaryName = if (isWindows) "cockroach.exe" else "cockroach"
    private val binaryFile = File(rootDir, binaryName)
    private var replicationJob: Job? = null
    private var healthProbeJob: Job? = null
    private var tailscaleIp: String = "127.0.0.1"
    private var port: Int = 26257
    private var isInsecure: Boolean = true

    private val version = "v26.2.3"
    private val linuxDownloadUrl = if (isArm) {
        "https://binaries.cockroachdb.com/cockroach-$version.linux-arm64.tgz"
    } else {
        "https://binaries.cockroachdb.com/cockroach-$version.linux-amd64.tgz"
    }
    private val windowsDownloadUrl = "https://binaries.cockroachdb.com/cockroach-$version.windows-6.2-amd64.zip"

    /**
     * Orchestrates the startup sequence of CockroachDB in a leaderless gateway architecture.
     * Starts the local CockroachDB node, joins cluster peers, and returns a DatabaseConfig
     * configured to connect directly to this local CockroachDB gateway.
     */
    fun orchestrate(): DatabaseConfig {
        println("[Cockroach] Starting autonomous leaderless database lifecycle...")

        // Clean up any orphaned processes from previous runs
        killExistingProcesses()

        // 1. Ensure binary is installed and executable
        ensureInstalled()

        // 2. Identify local Tailscale IP
        val tailscaleIp = getTailscaleIp()
        this.tailscaleIp = tailscaleIp
        val port = appConfig.cockroach_port
        this.port = port
        println("[Cockroach] Bound to local Tailscale gateway IP: $tailscaleIp on port $port")

        // Check local firewall rules for active ports
        val portsToCheck = mutableListOf(appConfig.server.port, port)
        if (appConfig.server.https.enabled) {
            portsToCheck.add(appConfig.server.https.port)
        }
        checkFirewallRules(portsToCheck)

        // 3. Fetch cluster peers from Google Sheet for dynamic node discovery
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
        println("[Cockroach] Fetched ${peers.size} cluster peers from Google Sheet: ${peers.joinToString { "${it.first}:${it.second}" }}")

        // 4. Handle secure/insecure certificates
        val isInsecure = appConfig.db_username.isBlank() && appConfig.db_password.isBlank()
        this.isInsecure = isInsecure
        if (!isInsecure) {
            generateCertificates(tailscaleIp, appConfig.db_username)
        }

        // 5. Start the local CockroachDB process, joining all known cluster peers
        val joinPeers = if (peers.isNotEmpty()) peers else listOf(Pair(tailscaleIp, port))
        val dbProcess = startCockroachProcess(tailscaleIp, port, joinPeers, isInsecure)
        this.process = dbProcess
        isDbActive = true

        // 6. Wait for CockroachDB port to open
        println("[Cockroach] Waiting for CockroachDB to start listening...")
        if (!waitForPort(tailscaleIp, port, 45)) {
            val logFile = File(rootDir, "cockroach.log")
            val logSnippet = if (logFile.exists()) logFile.readLines().takeLast(20).joinToString("\n") else "No logs found."
            throw IllegalStateException("CockroachDB failed to bind to $tailscaleIp:$port within timeout. Last logs:\n$logSnippet")
        }
        println("[Cockroach] CockroachDB is listening on $tailscaleIp:$port")
        println("[Cockroach] Admin Web UI is active at http://$tailscaleIp:${port + 1}")

        // 7. Execute cluster initialization (safe on all nodes; CockroachDB handles idempotent initialization)
        println("[Cockroach] Initializing cluster...")
        initializeCluster(tailscaleIp, port, isInsecure)

        // 8. Wait for local SQL engine to be fully ready
        println("[Cockroach] Waiting for SQL engine to be ready...")
        var sqlReady = waitForSqlReady(tailscaleIp, port, isInsecure, 60)
        if (!sqlReady) {
            val isAlive = process?.isAlive ?: false
            if (!isAlive) {
                val logFile = File(rootDir, "cockroach.log")
                val logSnippet = if (logFile.exists()) logFile.readLines().takeLast(500).joinToString("\n") else "No logs found."
                throw IllegalStateException("CockroachDB process crashed during startup (exit code: ${process?.exitValue()}). Last logs:\n$logSnippet")
            } else {
                println("[Cockroach] WARNING: SQL engine pending readiness (cluster Raft leader catch-up or node join in progress)...")
                sqlReady = waitForSqlReady(tailscaleIp, port, isInsecure, 30)
            }
        }

        if (sqlReady) {
            println("[Cockroach] SQL engine is ready.")
            configureClusterReplication(tailscaleIp, port, isInsecure)
        } else {
            println("[Cockroach] WARNING: Cluster quorum recovery pending. Proceeding in degraded mode...")
            configureClusterReplication(tailscaleIp, port, isInsecure)
        }

        // 9. Setup DB User and Password if requested
        if (!isInsecure && appConfig.db_username.isNotBlank() && appConfig.db_username != "root") {
            println("[Cockroach] Creating application database user: ${appConfig.db_username}...")
            createDatabaseUser(tailscaleIp, port, appConfig.db_username, appConfig.db_password, isInsecure)
        }

        // Return connection config pointing directly to this local gateway
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
     * Terminates the local database process.
     */
    fun stop() {
        replicationJob?.cancel()
        process?.let {
            if (it.isAlive) {
                println("[Cockroach] Stopping local database process...")
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

    fun isProcessAlive(): Boolean {
        return process?.isAlive ?: false
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
                println("[Cockroach] Preserving existing CockroachDB data directory so the node keeps its persisted identity.")
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
        URI(linuxDownloadUrl).toURL().openStream().use { input ->
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
        URI(windowsDownloadUrl).toURL().openStream().use { input ->
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
            return
        }
        certsDir.mkdirs()

        println("[Cockroach] Generating secure cluster certificates...")
        val binaryPath = binaryFile.absolutePath
        val caKeyPath = File(certsDir, "ca.key").absolutePath
        val certsDirPath = certsDir.absolutePath

        runCommand(listOf(binaryPath, "cert", "create-ca", "--certs-dir=$certsDirPath", "--ca-key=$caKeyPath")).waitFor()
        runCommand(listOf(binaryPath, "cert", "create-node", tailscaleIp, "localhost", "127.0.0.1", "--certs-dir=$certsDirPath", "--ca-key=$caKeyPath")).waitFor()
        runCommand(listOf(binaryPath, "cert", "create-client", "root", "--certs-dir=$certsDirPath", "--ca-key=$caKeyPath")).waitFor()
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
        healthProbeJob?.cancel()

        // Ultra-fast proactive health probe running every 1s
        healthProbeJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val wasLost = isQuorumLost
                    checkQuorumStatus()
                    if (!wasLost && isQuorumLost) {
                        println("[Cockroach] ⚠️ Cluster quorum loss proactively detected by health probe! Switched read transactions to AS OF SYSTEM TIME offline mode.")
                    } else if (wasLost && !isQuorumLost) {
                        println("[Cockroach] ✅ Cluster quorum restored! Resumed live read/write mode.")
                    }
                } catch (_: Exception) {}
                delay(1000)
            }
        }

        replicationJob = CoroutineScope(Dispatchers.IO).launch {
            var currentReplicas = getReplicationFactorFromDb().let { if (it > 0) it else 1 }
            var nodesBelowThreeStart = 0L
            var lastDiagnosticsTime = 0L
            var lastLeaseHealTime = 0L
            var lastQueueHealTime = 0L
            var lastClockSyncTime = 0L
            var consecutiveInvalidLeaseRounds = 0
            println("[Cockroach] Starting background replication monitor (current replication factor: $currentReplicas)...")
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()

                    // ── Self-Healing: Local Process Crash ─────────────────────────────
                    val localProcess = process
                    if (localProcess != null && !localProcess.isAlive) {
                        val exitCode = localProcess.exitValue()
                        println("[ProcessMonitor] ⚠️ Local CockroachDB process has died (exit=$exitCode). Preserving data directory and restarting...")
                        try {
                            val peers = try {
                                GoogleSheetsManager.fetchPeers(appConfig.google_sheet_url, appConfig.google_sheet_password)
                            } catch (e: Exception) {
                                emptyList<Pair<String, Int>>()
                            }
                            val joinPeers = if (peers.isNotEmpty()) peers else listOf(Pair(tailscaleIp, port))
                            val newProc = startCockroachProcess(tailscaleIp, port, joinPeers, isInsecure)
                            process = newProc
                            isDbActive = true
                            println("[ProcessMonitor] ✅ CockroachDB restarted. Waiting for port to open...")
                            waitForPort(tailscaleIp, port, 45)
                            println("[ProcessMonitor] CockroachDB port open. Node rejoining cluster with preserved identity.")
                        } catch (e: Exception) {
                            println("[ProcessMonitor] Failed to restart CockroachDB: ${e.message}")
                        }
                    }

                    checkQuorumStatus()

                    val activeNodes = getActiveNodesCount()
                    if (activeNodes >= 3) {
                        nodesBelowThreeStart = 0L
                        if (currentReplicas < 3) {
                            println("[Cockroach] $activeNodes active nodes detected. Upgrading cluster replication factor to 3...")
                            if (setReplicationFactor(3)) {
                                currentReplicas = 3
                                println("[Cockroach] Replication factor successfully set to 3.")
                                applySnapshotRateLimits()
                            }
                        }
                    } else if (activeNodes < 3) {
                        if (nodesBelowThreeStart == 0L) {
                            nodesBelowThreeStart = now
                        }
                        // Intentionally keep cluster replication factor at 3 without downscaling to 1.
                        // This prevents split-brain writes/divergent histories and ensures seamless Raft consensus catch-up upon node reconnection,
                        // while readTransaction safely serves latest available committed reads via AS OF SYSTEM TIME.
                    }

                    // ── Self-Healing: Invalid Leases ──────────────────────────────────
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

                    // ── Self-Healing: Clock Drift Check ──────────────────────────────
                    if (now - lastClockSyncTime > 60_000) {
                        val closedTsLagMs = (getMetricValue("kv.closed_timestamp.max_behind_nanos") ?: 0L) / 1_000_000
                        if (closedTsLagMs > 3000) {
                            println("[ClockHealer] ℹ️ Closed_ts=${closedTsLagMs}ms behind. Verifying system clock sync...")
                            forceNtpSync()
                        }
                        lastClockSyncTime = now
                    }

                    if (now - lastDiagnosticsTime > 30_000) {
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

    private fun getMetricValue(metricName: String): Long? {
        return try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return null
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.queryTimeout = 5
                    try { stmt.execute("SET statement_timeout = '5000ms'") } catch (_: Exception) {}
                    try { stmt.execute("SET allow_unsafe_internals = true") } catch (_: Exception) {}
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

    internal fun readTailLines(file: File, maxLines: Int): List<String> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            val fileLength = file.length()
            val maxBytesToRead = (maxLines * 300L).coerceAtMost(2_000_000L)
            val buffer = ArrayDeque<String>(maxLines)

            if (fileLength > maxBytesToRead) {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(fileLength - maxBytesToRead)
                    raf.readLine() // Discard first line since seek might start mid-line
                    var line: String? = raf.readLine()
                    while (line != null) {
                        if (buffer.size >= maxLines) {
                            buffer.removeFirst()
                        }
                        buffer.addLast(line)
                        line = raf.readLine()
                    }
                }
            } else {
                file.useLines { lineSequence ->
                    for (line in lineSequence) {
                        if (buffer.size >= maxLines) {
                            buffer.removeFirst()
                        }
                        buffer.addLast(line)
                    }
                }
            }
            buffer.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun checkForFatalDiskError(linesToScan: List<String>? = null): Boolean {
        return try {
            val lines = linesToScan ?: run {
                val logFile = File(rootDir, "cockroach.log")
                if (!logFile.exists()) return false
                readTailLines(logFile, 200)
            }
            lines.any { line ->
                val isPebbleOrFatal = line.startsWith("F") || line.contains("fatal error") || line.contains("hard disk failure")
                val isTransientSlowness = line.contains("disk_slowness_detected") || line.contains("disk slowness detected") ||
                        line.contains("disk_slowness_cleared") || line.contains("slow heartbeat") || line.contains("disk write failed")
                isPebbleOrFatal && !isTransientSlowness && (line.contains("faulty hardware") || line.contains("storage/pebble: fatal") || line.contains("terminating due to a fatal"))
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun healInvalidLeases() {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET allow_unsafe_internals = true")

                    try {
                        stmt.execute("SET CLUSTER SETTING kv.snapshot_rebalance.max_rate = '32 MiB'")
                        stmt.execute("SET CLUSTER SETTING kv.snapshot_recovery.max_rate  = '32 MiB'")
                        println("[LeaseHealer] Reset snapshot rates to 32 MiB")
                    } catch (e: Exception) {
                        println("[LeaseHealer] Could not set snapshot rates: ${e.message}")
                    }

                    println("[LeaseHealer] Lease healing check complete.")
                }
            }
        } catch (e: Exception) {
            println("[LeaseHealer] Healing failed: ${e.message}")
        }
    }

    private fun healFrozenReplicateQueue() {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET allow_unsafe_internals = true")

                    try {
                        stmt.execute("SET CLUSTER SETTING kv.snapshot_rebalance.max_rate = '32 MiB'")
                        stmt.execute("SET CLUSTER SETTING kv.snapshot_recovery.max_rate  = '32 MiB'")
                        println("[QueueHealer] Set snapshot rates to 32 MiB to maintain transfer speed")
                    } catch (e: Exception) {
                        println("[QueueHealer] Could not set snapshot rates: ${e.message}")
                    }

                    println("[QueueHealer] Queue heal check complete.")
                }
            }
        } catch (e: Exception) {
            println("[QueueHealer] Healing failed: ${e.message}")
        }
    }

    private fun forceNtpSync() {
        try {
            val cmd = if (isWindows) {
                listOf("w32tm", "/resync", "/force")
            } else {
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
        val invalidLeases = getMetricValue("replicas.leaders_invalid_lease") ?: -1L
        val leaseholders  = getMetricValue("replicas.leaseholders") ?: -1L
        val snapGenerated = getMetricValue("range.snapshots.generated") ?: -1L
        val queuePending  = getMetricValue("queue.replicate.pending") ?: -1L
        val addReplica    = getMetricValue("queue.replicate.addreplica") ?: -1L
        val closedTsLagMs = (getMetricValue("kv.closed_timestamp.max_behind_nanos") ?: 0L) / 1_000_000

        val recentLogLines = try {
            val logFile = File(rootDir, "cockroach.log")
            if (logFile.exists()) {
                val allLines = readTailLines(logFile, 3000)
                val matchedLines = mutableListOf<String>()
                val maxLines = allLines.size
                var i = 0
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

        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SET allow_unsafe_internals = true;")

                    println("=========================================================================")
                    println("[Replication Diagnostics] Running cluster health check...")

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

                    println("[Replication Diagnostics] Lease health: leaseholders=$leaseholders, invalid=$invalidLeases")
                    println("[Replication Diagnostics] Snapshot queue: generated=$snapGenerated, pending=$queuePending, addreplica=$addReplica")
                    println("[Replication Diagnostics] Raft commit lag: closed_ts=${closedTsLagMs}ms behind")
                    if (invalidLeases > 0 && leaseholders == 0L)
                        println("[Replication Diagnostics] ⚠️  ALL LEASES INVALID — replication queue frozen")
                    if (snapGenerated == 0L && queuePending > 0L)
                        println("[Replication Diagnostics] ⚠️  QUEUE FROZEN — ${queuePending} pending but 0 snapshots sent")
                }
            }
        } catch (e: Exception) {
            println("[Replication Diagnostics] Failed to run diagnostics: ${e.message?.substringBefore("\n")}")
        }

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

    private fun applySnapshotRateLimits() {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    try { stmt.execute("SET CLUSTER SETTING kv.snapshot_rebalance.max_rate = '32 MiB'") } catch (_: Exception) {}
                    try { stmt.execute("SET CLUSTER SETTING kv.snapshot_recovery.max_rate = '32 MiB'") } catch (_: Exception) {}
                    try { stmt.execute("SET CLUSTER SETTING kv.replication_reports.interval = '30s'") } catch (_: Exception) {}
                    try { stmt.execute("SET CLUSTER SETTING kv.closed_timestamp.target_duration = '1s'") } catch (_: Exception) {}
                    try { stmt.execute("SET CLUSTER SETTING kv.closed_timestamp.side_transport_interval = '200ms'") } catch (_: Exception) {}
                    try { stmt.execute("SET CLUSTER SETTING kv.liveness.heartbeat_interval = '4s'") } catch (_: Exception) {}
                    try { stmt.execute("SET CLUSTER SETTING kv.liveness.lease_duration = '12s'") } catch (_: Exception) {}
                    try { stmt.execute("SET CLUSTER SETTING sql.defaults.default_transaction_use_follower_reads.enabled = true") } catch (_: Exception) {}
                    println("[Cockroach] Snapshot rate limits, follower read defaults, closed timestamp targets, and Tailscale VPN liveness thresholds applied.")
                }
            }
        } catch (e: Exception) {
            println("[Cockroach] Warning: could not apply snapshot rate limits: ${e.message}")
        }
    }

    private fun getReplicationFactorFromDb(): Int {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return 0
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SHOW ZONE CONFIG FOR RANGE default").use { rs ->
                        while (rs.next()) {
                            val config = rs.getString("raw_config_sql") ?: rs.getString(1) ?: ""
                            val match = Regex("""num_replicas\s*=\s*(\d+)""").find(config)
                            if (match != null) {
                                return match.groupValues[1].toInt()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return 0
    }

    fun isClusterHealthy(): Boolean {
        return try {
            val unavailable = getMetricValue("ranges.unavailable") ?: 0L
            val underRep    = getMetricValue("ranges.underreplicated") ?: 0L
            unavailable == 0L && underRep < 5L
        } catch (e: Exception) {
            true
        }
    }

    private fun setReplicationFactor(replicas: Int): Boolean {
        try {
            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource ?: return false
            val current = getReplicationFactorFromDb()
            if (current == replicas) {
                return true
            }
            val dbName = appConfig.database.postgres.database.lowercase()
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    println("[Cockroach] Upgrading cluster replication factor (default, system, database) to $replicas (was $current)...")
                    try { stmt.execute("ALTER RANGE default CONFIGURE ZONE USING num_replicas = $replicas") } catch (e: Exception) {}
                    try { stmt.execute("ALTER RANGE system CONFIGURE ZONE USING num_replicas = $replicas") } catch (e: Exception) {}
                    try {
                        stmt.execute("CREATE DATABASE IF NOT EXISTS \"$dbName\"")
                        stmt.execute("ALTER DATABASE \"$dbName\" CONFIGURE ZONE USING num_replicas = $replicas")
                    } catch (e: Exception) {
                        println("[Cockroach] Note configuring zone for $dbName: ${e.message}")
                    }
                    if (dbName != "obsidianscoutjava") {
                        try {
                            stmt.execute("CREATE DATABASE IF NOT EXISTS \"obsidianscoutjava\"")
                            stmt.execute("ALTER DATABASE \"obsidianscoutjava\" CONFIGURE ZONE USING num_replicas = $replicas")
                        } catch (e: Exception) {}
                    }
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
            val current = getReplicationFactorFromDb()
            println("[Cockroach] Initial cluster replication factor check: currently at $current replicas.")
            if (current == 0) {
                setReplicationFactor(1)
            }
        } catch (e: Exception) {
            println("[Cockroach] Failed checking cluster replication factor: ${e.message}")
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

    fun checkQuorumStatus() {
        try {
            val unavailable = getMetricValue("ranges.unavailable") ?: 0L
            if (unavailable > 0L) {
                isQuorumLost = true
                quorumLossDetails = "$unavailable range(s) unavailable due to cluster quorum loss."
                return
            }

            val ds = com.obsidianscout.db.DatabaseFactory.activeDataSource
            if (ds != null) {
                ds.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.queryTimeout = 1
                        try { stmt.execute("SET statement_timeout = '800ms'") } catch (_: Exception) {}
                        // Test a real table read at T_now to confirm live cluster quorum and range leaseholders are responding
                        stmt.executeQuery("SELECT id FROM users LIMIT 1").use { rs ->
                            rs.next()
                            // If the live query succeeds, quorum is healthy and restored!
                            isQuorumLost = false
                            quorumLossDetails = null
                            com.obsidianscout.db.DatabaseFactory.saveLastHealthyTimestamp(java.time.Instant.now())
                            com.obsidianscout.db.DatabaseFactory.cachedWorkingAsOfSystemTime = null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isQuorumLossException(e)) {
                isQuorumLost = true
                quorumLossDetails = e.message ?: "Database cluster quorum lost."
            }
        }
    }

    companion object {
        @Volatile
        var isDbActive = false

        @Volatile
        var isQuorumLost: Boolean = false

        @Volatile
        var quorumLossDetails: String? = null

        fun isQuorumLossException(e: Throwable): Boolean {
            var curr: Throwable? = e
            while (curr != null) {
                val msg = curr.message?.lowercase() ?: ""
                val className = curr::class.java.simpleName.lowercase()
                if (msg.contains("lost quorum") ||
                    msg.contains("quorum lost") ||
                    msg.contains("quorum is lost") ||
                    msg.contains("range unavailable") ||
                    msg.contains("ranges unavailable") ||
                    msg.contains("range is unavailable") ||
                    msg.contains("under-quorum") ||
                    msg.contains("under quorum") ||
                    msg.contains("poisoned latch") ||
                    msg.contains("cannot write because range") ||
                    msg.contains("cannot write because replica") ||
                    msg.contains("cannot serve requests") ||
                    msg.contains("leaderless for") ||
                    msg.contains("range is leaderless") ||
                    msg.contains("leader unavailable") ||
                    msg.contains("no lease holder") ||
                    msg.contains("no leaseholder") ||
                    msg.contains("transactionretrywithtoughluck") ||
                    msg.contains("result is ambiguous") ||
                    msg.contains("ambiguousresult") ||
                    msg.contains("unable to satisfy read at timestamp") ||
                    msg.contains("unable to route request") ||
                    msg.contains("latch acquisition failed") ||
                    msg.contains("replica descriptor for range") ||
                    msg.contains("desc = transport is closing") ||
                    msg.contains("statement timeout") ||
                    msg.contains("statement_timeout") ||
                    msg.contains("query execution canceled") ||
                    msg.contains("canceling statement") ||
                    msg.contains("read timed out") ||
                    msg.contains("socket timeout") ||
                    msg.contains("an i/o error occurred while sending to the backend") ||
                    msg.contains("broken pipe") ||
                    msg.contains("08006") ||
                    msg.contains("restart transaction") ||
                    msg.contains("transactionretry") ||
                    msg.contains("connection is not available") ||
                    msg.contains("unable to serve request") ||
                    msg.contains("closed timestamp") ||
                    msg.contains("namespacetable") ||
                    (msg.contains("descriptor") && msg.contains("modified")) ||
                    className.contains("sqltransientconnectionexception") ||
                    className.contains("rangeunavailable") ||
                    className.contains("notleaseholder") ||
                    className.contains("ambiguousresult") ||
                    className.contains("sockettimeoutexception") ||
                    className.contains("timeoutexception") ||
                    (msg.contains("replica") && msg.contains("unavailable")) ||
                    (msg.contains("deadline exceeded") && (msg.contains("range") || msg.contains("replica") || msg.contains("lease") || msg.contains("raft") || msg.contains("liveness") || msg.contains("heartbeat") || msg.contains("context")))
                ) {
                    return true
                }
                curr = curr.cause
            }
            return false
        }
    }
}

