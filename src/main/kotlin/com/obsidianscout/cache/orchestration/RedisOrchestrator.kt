package com.obsidianscout.cache.orchestration

import com.obsidianscout.config.AppConfig
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import java.io.File

class RedisOrchestrator(private val appConfig: AppConfig) {
    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L
        private const val NO_PACKAGE_MANAGER = 3
        /** How long a freshly launched WSL Redis gets to fail before it counts as started. */
        private const val EARLY_EXIT_WAIT_MS = 2_000L
        private const val BACKEND_RECHECK_MS = 10 * 60_000L
        private const val MAX_LOG_BYTES = 5L * 1024 * 1024
        private const val MIN_REDIS_MAJOR = 7
        private const val MIN_REDIS_MINOR = 4
        private const val MIN_REDIS_PATCH = 0

        /**
         * Configures the official Redis APT repository on Debian/Ubuntu so `redis-server` is 7.4+ (RDB v12 compatible)
         * instead of the older 7.0.x shipped in default distro repos, then installs/upgrades `redis-server`.
         * Falls back to the distro repo if packages.redis.io is unreachable.
         */
        private const val APT_INSTALL_REDIS_SCRIPT =
            "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get update && apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold lsb-release curl gpg ca-certificates && " +
            "CODENAME=\$(lsb_release -cs 2>/dev/null || (. /etc/os-release && echo \"\$VERSION_CODENAME\")) && " +
            "if [ -n \"\$CODENAME\" ] && curl -fsSL https://packages.redis.io/gpg | gpg --dearmor --yes -o /usr/share/keyrings/redis-archive-keyring.gpg; then " +
            "chmod 644 /usr/share/keyrings/redis-archive-keyring.gpg && " +
            "echo \"deb [signed-by=/usr/share/keyrings/redis-archive-keyring.gpg] https://packages.redis.io/deb \${CODENAME} main\" > /etc/apt/sources.list.d/redis.list && " +
            "apt-get update && " +
            "(apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold redis-server || " +
            "apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold redis); " +
            "else apt-get install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold redis-server; fi"

        /** Installs redis-server with whatever package manager the distro has; exits [NO_PACKAGE_MANAGER] if none. */
        private const val WSL_INSTALL_SCRIPT =
            "if command -v apt-get >/dev/null 2>&1; then $APT_INSTALL_REDIS_SCRIPT; " +
            "elif command -v dnf >/dev/null 2>&1; then dnf install -y redis || dnf install -y valkey-compat-redis; " +
            "elif command -v yum >/dev/null 2>&1; then yum install -y redis; " +
            "elif command -v zypper >/dev/null 2>&1; then zypper --non-interactive install redis; " +
            "elif command -v apk >/dev/null 2>&1; then apk add redis; " +
            "else exit 3; fi"

        /** Upgrades an existing redis-server installation in WSL or Linux. */
        private const val UPGRADE_SCRIPT =
            "if command -v apt-get >/dev/null 2>&1; then $APT_INSTALL_REDIS_SCRIPT; " +
            "elif command -v dnf >/dev/null 2>&1; then dnf upgrade -y redis || dnf upgrade -y valkey-compat-redis; " +
            "elif command -v yum >/dev/null 2>&1; then yum update -y redis; " +
            "elif command -v zypper >/dev/null 2>&1; then zypper --non-interactive update redis; " +
            "elif command -v apk >/dev/null 2>&1; then apk upgrade redis; " +
            "else exit 3; fi"

        private val VERSION_REGEX = Regex("""\bv=(\d+)\.(\d+)\.(\d+)""")

        internal fun parseRedisVersion(versionOutput: String): Triple<Int, Int, Int>? {
            val match = VERSION_REGEX.find(versionOutput) ?: return null
            val (maj, min, patch) = match.destructured
            return Triple(maj.toInt(), min.toInt(), patch.toInt())
        }

        internal fun isVersionSupported(version: Triple<Int, Int, Int>): Boolean {
            val (maj, min, patch) = version
            if (maj != MIN_REDIS_MAJOR) return maj > MIN_REDIS_MAJOR
            if (min != MIN_REDIS_MINOR) return min > MIN_REDIS_MINOR
            return patch >= MIN_REDIS_PATCH
        }

        /** Distro packages start their own Redis on 127.0.0.1:6379; this node runs its own, so turn that one off. */
        private const val DISABLE_SERVICE_SCRIPT =
            "systemctl disable --now redis-server redis valkey 2>/dev/null; service redis-server stop 2>/dev/null; true"
        private val installing = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Last install step or result on this node, shown on the Redis Cache page. */
        @Volatile var installProgress: String? = null
            private set

        val isInstalling: Boolean get() = installing.get()
    }

    private val log = LoggerFactory.getLogger("com.obsidianscout.cache.orchestration.RedisOrchestrator")
    private val rootDir = File(".redis")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val binaryName = if (isWindows) "redis-server.exe" else "redis-server"
    private val localBinaryFile = File(rootDir, binaryName)
    private var resolvedBinaryPath: String? = null
    private var process: Process? = null
    @Volatile private var lastLogFile: File? = null
    private var isDockerContainer: Boolean = false
    private var isWslProcess: Boolean = false
    private var wslPort: Int = 0
    private var wslProxyIp: String? = null
    @Volatile private var upgradeAttempted: Boolean = false
    @Volatile var isExternalRunning: Boolean = false; private set
    @Volatile var activePort: Int = appConfig.redis.port; private set

    fun markExternalRunning(running: Boolean) {
        this.isExternalRunning = running
    }

    /**
     * Determines whether this host can run a Redis server process.
     * Prevents incapable nodes from contending for the host lease.
     */
    fun detectHostCapability(password: String = ""): Boolean {
        if (!appConfig.redis.enabled) return false

        // Check if Redis is already running and usable locally on configured port. It only counts if
        // peers can reach it on this node's Tailscale IP; one bound to loopback alone is left alone.
        if (isExistingRedisUsable(appConfig.redis.port, password)) {
            val tailnetIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
            if (isRedisReachable(tailnetIp, appConfig.redis.port, password)) {
                isExternalRunning = true
                log.info("[RedisOrchestrator] Verified usable local Redis service on {}:{}. Node can host Redis.", tailnetIp, appConfig.redis.port)
                return true
            }
            log.warn("[RedisOrchestrator] Local Redis on port {} is not reachable on the Tailscale IP {}. Ignoring it and hosting on another port.", appConfig.redis.port, tailnetIp)
        }

        if (!isWindows) {
            // Linux/macOS: Check local .redis dir, system PATH, or standard paths
            val candidatePaths = listOf(
                localBinaryFile.absolutePath,
                "/usr/bin/redis-server",
                "/usr/local/bin/redis-server",
                "redis-server"
            )
            for (path in candidatePaths) {
                if (testBinaryExecution(path)) {
                    resolvedBinaryPath = path
                    log.info("[RedisOrchestrator] Discovered Redis server binary at {}", path)
                    return true
                }
            }
            if (isDockerAvailable()) {
                log.info("[RedisOrchestrator] Docker runtime detected on Linux. Node can host Redis.")
                return true
            }
            log.warn("[RedisOrchestrator] No local Redis binary (redis-server) or Docker found on this Linux node. Operating strictly in client mode.")
            return false
        }

        // On Windows: Docker or WSL (with netsh portproxy) can host Redis
        if (isDockerAvailable()) {
            log.info("[RedisOrchestrator] Windows Docker runtime detected. Node can host Redis.")
            return true
        }
        if (isWslRedisInstalled()) {
            log.info("[RedisOrchestrator] Windows WSL with redis-server detected. Node can host Redis.")
            return true
        }

        log.info("[RedisOrchestrator] Windows host lacks Docker or WSL redis-server. Install it from the Redis Cache page to host Redis.")
        return false
    }

    fun startMaster(tailscaleIp: String, port: Int, maxMemoryBytes: Long, password: String) {
        if (isExternalRunning) {
            log.info("[RedisOrchestrator] Local Redis is already running externally on port {}. Skipping process launch.", port)
            activePort = port
            return
        }

        stop()
        activePort = port

        if (isWindows) {
            startWindowsMaster(tailscaleIp, port, maxMemoryBytes, password)
            return
        }

        // Linux/macOS
        if (resolvedBinaryPath != null) {
            rootDir.mkdirs()
            val cmd = mutableListOf(
                resolvedBinaryPath!!,
                "--bind", bindAddresses(tailscaleIp),
                "--port", port.toString(),
                "--protected-mode", protectedMode(password),
                "--maxmemory", maxMemoryBytes.toString(),
                "--maxmemory-policy", "allkeys-lru",
                "--save", "",
                "--appendonly", "no"
            )
            if (password.isNotBlank()) {
                cmd.addAll(listOf("--requirepass", password))
            }

            val logFile = File(rootDir, "redis.log")
            capLogFile(logFile)
            lastLogFile = logFile
            process = ProcessBuilder(cmd)
                .directory(rootDir)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                .start()

            log.info("[RedisOrchestrator] Started Redis Master via {} on {}:{} (maxmemory: {}MB)", resolvedBinaryPath, tailscaleIp, port, maxMemoryBytes / (1024 * 1024))
        } else if (isDockerAvailable()) {
            startDockerMaster(tailscaleIp, port, maxMemoryBytes, password)
        } else {
            log.error("[RedisOrchestrator] Unable to start Redis Master: No valid binary or container runtime found.")
        }
    }

    fun startReplica(tailscaleIp: String, replicaPort: Int, masterHost: String, masterPort: Int, password: String): Boolean {
        stop()
        activePort = replicaPort

        if (isWindows) {
            return startWindowsReplica(tailscaleIp, replicaPort, masterHost, masterPort, password)
        }

        if (resolvedBinaryPath != null) {
            rootDir.mkdirs()
            // CRITICAL: Bind to 127.0.0.1 AND tailscaleIp so promotion (REPLICAOF NO ONE) is immediately reachable by peers
            val cmd = mutableListOf(
                resolvedBinaryPath!!,
                "--bind", bindAddresses(tailscaleIp),
                "--port", replicaPort.toString(),
                "--protected-mode", protectedMode(password),
                "--replicaof", masterHost, masterPort.toString(),
                "--save", "",
                "--appendonly", "no"
            )
            if (password.isNotBlank()) {
                cmd.addAll(listOf("--requirepass", password, "--masterauth", password))
            }

            val logFile = File(rootDir, "redis-replica.log")
            capLogFile(logFile)
            lastLogFile = logFile
            process = ProcessBuilder(cmd)
                .directory(rootDir)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                .start()

            val exitedEarly = runCatching { process!!.waitFor(EARLY_EXIT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (exitedEarly && !isLoopbackPortOpen(replicaPort)) {
                log.error("[RedisOrchestrator] Redis replica on port {} exited right after launch ({}).", replicaPort, describeLastExit())
                return false
            }
            log.info("[RedisOrchestrator] Started Redis Replica on {}:{} replicating {}:{}", tailscaleIp, replicaPort, masterHost, masterPort)
            return true
        } else if (isDockerAvailable()) {
            return startDockerReplica(tailscaleIp, replicaPort, masterHost, masterPort, password)
        }
        return false
    }

    fun promoteToMaster(tailscaleIp: String, maxMemoryBytes: Long, password: String) {
        log.info("[RedisOrchestrator] Promoting running replica on port {} to Master (REPLICAOF NO ONE)...", activePort)
        try {
            Jedis("127.0.0.1", activePort).use { jedis ->
                if (password.isNotBlank()) jedis.auth(password)
                jedis.replicaofNoOne()
                jedis.configSet("maxmemory", maxMemoryBytes.toString())
                jedis.configSet("maxmemory-policy", "allkeys-lru")
            }
            log.info("[RedisOrchestrator] Promotion complete. Local Redis is now Master on port {}.", activePort)
        } catch (e: Exception) {
            log.error("[RedisOrchestrator] Failed to promote replica via command: {}. Falling back to fresh start.", e.message)
            startMaster(tailscaleIp, activePort, maxMemoryBytes, password)
        }
    }

    fun stop() {
        if (isWslProcess) {
            wslProxyIp?.let { removeWindowsPortproxy(it, wslPort) }
            stopWslRedis(wslPort)
            wslProxyIp = null
            isWslProcess = false
        }
        if (isDockerContainer) {
            runCatching {
                ProcessBuilder(dockerExe, "stop", "obsidianscout-redis").start().waitFor()
            }
            isDockerContainer = false
        }
        process?.let {
            if (it.isAlive) {
                log.info("[RedisOrchestrator] Stopping local Redis process...")
                it.destroy()
                if (!it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                }
            }
        }
        process = null
        isExternalRunning = false
    }

    /**
     * Whether the Redis this orchestrator launched is still up. For WSL, wsl.exe exiting does not always mean
     * Redis stopped (and vice versa), so a Redis answering on the loopback port counts too.
     */
    fun isAlive(): Boolean =
        (process?.isAlive ?: false) || isDockerContainer || (isWslProcess && isLoopbackPortOpen(wslPort))

    private fun isLoopbackPortOpen(port: Int): Boolean = port > 0 && runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    }.getOrDefault(false)

    /** Returns true if the last Redis log ended with an RDB format version incompatibility error. */
    fun hasIncompatibleRdbError(): Boolean {
        val tail = lastLogFile?.let { tailOf(it, 20) }.orEmpty()
        return tail.contains("Can't handle RDB format version")
    }

    /** Exit code and the end of the log of the last launched Redis process, for the restart-backoff warning. */
    fun describeLastExit(): String {
        val p = process
        val code = when {
            p == null -> "no process"
            p.isAlive -> "still running"
            else -> "exit code ${p.exitValue()}"
        }
        val tail = lastLogFile?.let { tailOf(it, 15) }.orEmpty().trim()
        return if (tail.isEmpty()) code else "$code. Last log lines from ${lastLogFile?.absolutePath}:\n$tail"
    }

    private fun tailOf(file: File, lines: Int): String = runCatching {
        if (!file.exists()) return@runCatching ""
        java.io.RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = maxOf(0L, len - 8192)
            val buf = ByteArray((len - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(lines).joinToString("\n")
        }
    }.getOrDefault("")

    private fun testBinaryExecution(path: String): Boolean = runCatching {
        val p = ProcessBuilder(path, "--version").start()
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    /**
     * The docker CLI. A Docker Desktop installed after the app started is not on the app's PATH yet,
     * so fall back to its standard location.
     */
    private val dockerExe: String by lazy {
        val desktopCli = File("C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe")
        if (isWindows && probe(listOf("docker", "--version"), 10) != 0 && desktopCli.exists()) desktopCli.absolutePath else "docker"
    }

    /** Why the last Docker / WSL check failed, for the install status on the Redis Cache page. */
    @Volatile private var dockerCheckResult: String = "not checked"
    @Volatile private var wslCheckResult: String = "not checked"

    // `docker info` can take several seconds on Docker Desktop and prints more than a pipe holds.
    private fun isDockerAvailable(): Boolean {
        val code = probe(listOf(dockerExe, "info"), 20)
        dockerCheckResult = describeProbe(code, 20)
        return code == 0
    }

    /**
     * Runs a check command with its output discarded (so it can never fill a pipe and stall) and returns
     * its exit code, -1 on timeout, or -2 when it could not be started at all.
     */
    private fun probe(cmd: List<String>, timeoutSeconds: Long): Int = try {
        val p = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null")))
            .start()
        if (p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) p.exitValue() else { p.destroyForcibly(); -1 }
    } catch (_: Exception) {
        -2
    }

    private fun describeProbe(code: Int, timeoutSeconds: Long): String = when (code) {
        0 -> "ok"
        -1 -> "timed out after ${timeoutSeconds}s"
        -2 -> "not found"
        else -> "exited with code $code"
    }

    /** Loopback plus the Tailscale IP, so peers on the tailnet can connect but nothing else can. */
    private fun bindAddresses(tailscaleIp: String): String = listOf("127.0.0.1", tailscaleIp).distinct().joinToString(" ")

    /**
     * Redis 7 refuses every non-loopback client when protected mode is on and there is no password,
     * so a passwordless node turns it off and relies on [bindAddresses] to keep it on the tailnet.
     */
    private fun protectedMode(password: String): String = if (password.isBlank()) "no" else "yes"

    /** Docker publishes on all interfaces unless given an address, so publish on loopback and the Tailscale IP only. */
    private fun dockerPortArgs(tailscaleIp: String, hostPort: Int): List<String> =
        listOf("127.0.0.1", tailscaleIp).distinct().flatMap { listOf("-p", "$it:$hostPort:6379") }

    /** Whether a Redis answers PING on [ip]:[port], i.e. whether peers on the tailnet could use it. */
    fun isRedisReachable(ip: String, port: Int, password: String): Boolean {
        if (ip == "127.0.0.1") return false
        return runCatching {
            Jedis(ip, port, 500).use { jedis ->
                if (password.isNotBlank()) jedis.auth(password)
                jedis.ping() == "PONG"
            }
        }.getOrDefault(false)
    }

    /**
     * Checks if a Redis server is already reachable on [port] and usable with [password].
     * If unprotected, applies [password] via CONFIG SET requirepass so it matches cluster auth.
     */
    private fun isExistingRedisUsable(port: Int, password: String): Boolean {
        // 1. Try ping with password
        val pingWithPass = runCatching {
            Jedis("127.0.0.1", port, 500).use { jedis ->
                if (password.isNotBlank()) jedis.auth(password)
                jedis.ping() == "PONG"
            }
        }.getOrDefault(false)
        if (pingWithPass) return true

        // 2. Try ping without password; if successful and unsecured, configure password
        val pingNoPass = runCatching {
            Jedis("127.0.0.1", port, 500).use { jedis ->
                if (jedis.ping() == "PONG") {
                    if (password.isNotBlank()) {
                        jedis.configSet("requirepass", password)
                    }
                    true
                } else false
            }
        }.getOrDefault(false)
        return pingNoPass
    }

    private enum class WindowsBackend { DOCKER, WSL }

    @Volatile private var windowsBackend: WindowsBackend? = null
    @Volatile private var windowsBackendCheckedAtMs: Long = 0L

    /**
     * Docker or WSL, whichever can run Redis. `docker info` and the WSL probe each start processes and can take
     * many seconds, so the answer is reused for [BACKEND_RECHECK_MS] (or until a launch fails) instead of
     * being re-probed on every launch.
     */
    private fun resolveWindowsBackend(): WindowsBackend? {
        val now = System.currentTimeMillis()
        if (windowsBackendCheckedAtMs != 0L && now - windowsBackendCheckedAtMs < BACKEND_RECHECK_MS) return windowsBackend
        windowsBackend = when {
            isDockerAvailable() -> WindowsBackend.DOCKER
            isWslRedisInstalled() -> WindowsBackend.WSL
            else -> null
        }
        windowsBackendCheckedAtMs = now
        return windowsBackend
    }

    private fun startWindowsMaster(tailscaleIp: String, port: Int, maxMemoryBytes: Long, password: String) {
        when (resolveWindowsBackend()) {
            WindowsBackend.DOCKER -> startDockerMaster(tailscaleIp, port, maxMemoryBytes, password)
            WindowsBackend.WSL -> startWslMaster(tailscaleIp, port, maxMemoryBytes, password)
            null -> log.error("[RedisOrchestrator] Windows host cannot host Redis without Docker or WSL.")
        }
    }

    private fun startWindowsReplica(tailscaleIp: String, replicaPort: Int, masterHost: String, masterPort: Int, password: String): Boolean {
        return when (resolveWindowsBackend()) {
            WindowsBackend.DOCKER -> startDockerReplica(tailscaleIp, replicaPort, masterHost, masterPort, password)
            WindowsBackend.WSL -> startWslReplica(tailscaleIp, replicaPort, masterHost, masterPort, password)
            null -> {
                log.error("[RedisOrchestrator] Windows host cannot run replica without Docker or WSL.")
                false
            }
        }
    }

    private fun startWslMaster(tailscaleIp: String, port: Int, maxMemoryBytes: Long, password: String) {
        val args = mutableListOf(
            "--port", port.toString(),
            "--maxmemory", maxMemoryBytes.toString(),
            "--maxmemory-policy", "allkeys-lru"
        )
        if (password.isNotBlank()) {
            args.addAll(listOf("--requirepass", password))
        }
        if (startWslRedis(tailscaleIp, port, args, "redis.log")) {
            log.info("[RedisOrchestrator] Started WSL Redis Master on {}:{} (maxmemory: {}MB)", tailscaleIp, port, maxMemoryBytes / (1024 * 1024))
        }
    }

    private fun startWslReplica(tailscaleIp: String, replicaPort: Int, masterHost: String, masterPort: Int, password: String): Boolean {
        val args = mutableListOf(
            "--port", replicaPort.toString(),
            "--replicaof", masterHost, masterPort.toString()
        )
        if (password.isNotBlank()) {
            args.addAll(listOf("--requirepass", password, "--masterauth", password))
        }
        val started = startWslRedis(tailscaleIp, replicaPort, args, "redis-replica.log")
        if (started) {
            log.info("[RedisOrchestrator] Started WSL Redis Replica on {}:{} replicating {}:{}", tailscaleIp, replicaPort, masterHost, masterPort)
        }
        return started
    }

    /**
     * WSL 2 cannot bind the Windows Tailscale address, so Redis listens on loopback inside WSL (which WSL
     * forwards to Windows loopback) and a portproxy rule on the Tailscale IP alone hands peers to it.
     * Nothing listens on the LAN, and the proxied clients arrive from loopback so protected mode lets them in.
     */
    private fun startWslRedis(tailscaleIp: String, port: Int, args: List<String>, logName: String): Boolean {
        rootDir.mkdirs()
        // A Redis left over from a crash would still hold the port inside WSL.
        stopWslRedis(port)
        val cmd = listOf("wsl", "-e", "redis-server", "--bind", "127.0.0.1", "--protected-mode", "yes", "--save", "", "--appendonly", "no") + args
        val logFile = File(rootDir, logName)
        capLogFile(logFile)
        lastLogFile = logFile
        process = ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            // Redis never reads stdin; NUL keeps wsl.exe off an open pipe to this process.
            .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
            .start()
        isWslProcess = true
        wslPort = port

        // A Redis that cannot start (port taken inside WSL, bad argument, broken distro) exits within a moment.
        // Catch that here so it is logged with its reason instead of silently relaunched on the next tick.
        val exitedEarly = runCatching { process!!.waitFor(EARLY_EXIT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (exitedEarly && !isLoopbackPortOpen(port)) {
            log.error("[RedisOrchestrator] WSL Redis on port {} exited right after launch ({}).", port, describeLastExit())
            // Only re-probe WSL/Docker if redis-server didn't even write to its log (broken distro/launch).
            if (!logFile.exists() || logFile.length() == 0L) {
                windowsBackendCheckedAtMs = 0L
            }
            return false
        }
        if (tailscaleIp != "127.0.0.1") {
            wslProxyIp = tailscaleIp
            setupWindowsPortproxy(tailscaleIp, port)
        }
        return true
    }

    /** Restart loops used to append to these logs without limit; start over once one passes [MAX_LOG_BYTES]. */
    private fun capLogFile(file: File) {
        runCatching { if (file.length() > MAX_LOG_BYTES) file.writeText("") }
    }

    /** Killing wsl.exe does not reliably stop the Linux process, so stop it inside WSL by its listen address. */
    private fun stopWslRedis(port: Int) {
        runCapture(listOf("wsl", "-u", "root", "-e", "pkill", "-f", "(redis|valkey)-server 127.0.0.1:$port"), 15)
    }

    private fun setupWindowsPortproxy(listenIp: String, port: Int) {
        val (code, out) = runCapture(listOf("netsh", "interface", "portproxy", "add", "v4tov4",
            "listenaddress=$listenIp", "listenport=$port",
            "connectaddress=127.0.0.1", "connectport=$port"), 10)
        if (code != 0) {
            log.error("[RedisOrchestrator] Could not add portproxy {}:{} to WSL (netsh needs the app to run as Administrator): {}", listenIp, port, out.trim())
        }
    }

    private fun removeWindowsPortproxy(listenIp: String, port: Int) {
        runCapture(listOf("netsh", "interface", "portproxy", "delete", "v4tov4",
            "listenaddress=$listenIp", "listenport=$port"), 10)
    }

    /**
     * Installs Redis on this node. Returns false straight away if an install is already running; progress
     * lives in the companion so it survives the coordinator rebuilding the orchestrator on a settings save.
     */
    fun runInstall(sudoPassword: String?): Boolean {
        if (!installing.compareAndSet(false, true)) return false
        try {
            installProgress = "Checking system environment..."
            return if (isWindows) installOnWindows() else installOnUnix(sudoPassword)
        } catch (e: Exception) {
            installProgress = "Error: ${e.message}"
            log.error("[RedisOrchestrator] Installation error: {}", e.message)
            return false
        } finally {
            installing.set(false)
        }
    }

    private fun installOnUnix(sudoPassword: String?): Boolean {
        val steps: List<Pair<String, List<String>>> = when {
            isCommandAvailable("apt-get") -> listOf(
                "Updating apt package list..." to listOf("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "update"),
                "Downloading & installing redis-server (apt)..." to listOf("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", "redis-server")
            )
            isCommandAvailable("dnf") -> listOf("Downloading & installing redis (dnf)..." to listOf("sh", "-c", "dnf install -y redis || dnf install -y valkey-compat-redis"))
            isCommandAvailable("yum") -> listOf("Downloading & installing redis (yum)..." to listOf("yum", "install", "-y", "redis"))
            else -> {
                installProgress = "Failed: No supported package manager found (apt, dnf, yum)."
                return false
            }
        }
        for ((label, cmd) in steps) {
            installProgress = label
            val (code, out) = runSudoCommand(cmd, sudoPassword)
            if (code != 0) {
                val sudoRefused = out.contains("a password is required") || out.contains("incorrect password") ||
                    out.contains("Sorry, try again") || out.contains("is not in the sudoers")
                installProgress = when {
                    sudoRefused -> "Failed: sudo was refused. Check the sudo password."
                    code == -1 -> "Failed: install timed out or could not start. See .redis/redis-install.log."
                    else -> "Failed: package install exited with code $code. See .redis/redis-install.log."
                }
                log.error("[RedisOrchestrator] '{}' failed with code {}: {}", cmd.joinToString(" "), code, out.takeLast(2000))
                return false
            }
        }
        // Distro packages start their own Redis on 127.0.0.1:6379. This node runs its own, so turn that one off.
        runSudoCommand(listOf("sh", "-c", DISABLE_SERVICE_SCRIPT), sudoPassword)

        installProgress = "Verifying redis-server..."
        val candidatePaths = listOf(
            localBinaryFile.absolutePath,
            "/usr/bin/redis-server",
            "/usr/local/bin/redis-server",
            "redis-server"
        )
        for (path in candidatePaths) {
            if (testBinaryExecution(path)) {
                resolvedBinaryPath = path
                installProgress = "Installed & Verified"
                log.info("[RedisOrchestrator] Redis binary installed and verified at {}", path)
                return true
            }
        }
        installProgress = "Failed: installed, but redis-server did not run."
        return false
    }

    private fun installOnWindows(): Boolean {
        if (isWslAvailable()) {
            installProgress = "Installing redis-server in the default WSL distro..."
            val cmd = listOf("wsl", "-u", "root", "-e", "sh", "-c", WSL_INSTALL_SCRIPT)
            val (code, out) = runCapture(cmd, INSTALL_TIMEOUT_SECONDS)
            if (code != 0) {
                installProgress = when (code) {
                    NO_PACKAGE_MANAGER -> "Failed: the default WSL distro has no apt, dnf, yum, zypper or apk. Set an Ubuntu/Debian/Fedora distro as default (wsl -s <name>)."
                    -1 -> "Failed: WSL install timed out. See .redis/redis-install.log."
                    else -> "Failed: WSL package install exited with code $code. See .redis/redis-install.log."
                }
                log.error("[RedisOrchestrator] WSL install failed with code {}: {}", code, out.takeLast(2000))
                return false
            }
            // With systemd enabled in WSL, the package's own service would take 6379 inside WSL.
            runCapture(listOf("wsl", "-u", "root", "-e", "sh", "-c", DISABLE_SERVICE_SCRIPT), 30)
            if (isWslRedisInstalled()) {
                installProgress = "Installed & Verified in WSL"
                log.info("[RedisOrchestrator] redis-server installed in WSL.")
                return true
            }
            installProgress = "Failed: installed, but redis-server was not found in WSL."
            return false
        }
        if (isDockerAvailable()) {
            installProgress = "Pulling Docker redis:7-alpine image..."
            val (code, out) = runCapture(listOf(dockerExe, "pull", "redis:7-alpine"), INSTALL_TIMEOUT_SECONDS)
            if (code == 0) {
                installProgress = "Docker image pulled & ready"
                return true
            }
            installProgress = "Failed: docker pull exited with code $code."
            log.error("[RedisOrchestrator] docker pull failed with code {}: {}", code, out.takeLast(2000))
            return false
        }
        installProgress = "Failed: no usable Docker or WSL. Docker: $dockerCheckResult. WSL: $wslCheckResult."
        log.warn("[RedisOrchestrator] Cannot install Redis: docker info {}, wsl {}", dockerCheckResult, wslCheckResult)
        return false
    }

    /** Runs [cmd] as root: directly when already root, otherwise via sudo (password on stdin, or -n when none is given). */
    private fun runSudoCommand(cmd: List<String>, sudoPassword: String?): Pair<Int, String> {
        val isRoot = System.getProperty("user.name") == "root"
        return when {
            isRoot -> runCapture(cmd, INSTALL_TIMEOUT_SECONDS)
            sudoPassword.isNullOrBlank() -> runCapture(listOf("sudo", "-n") + cmd, INSTALL_TIMEOUT_SECONDS)
            else -> runCapture(listOf("sudo", "-S", "-p", "") + cmd, INSTALL_TIMEOUT_SECONDS, stdin = sudoPassword + "\n")
        }
    }

    /**
     * Runs [cmd] with its output going to a file, so a chatty command can never fill a pipe and stall, and kills
     * it after [timeoutSeconds]. Returns the exit code (-1 on timeout or launch failure) and the output, which is
     * also appended to .redis/redis-install.log.
     */
    private fun runCapture(cmd: List<String>, timeoutSeconds: Long, stdin: String? = null): Pair<Int, String> = try {
        rootDir.mkdirs()
        val out = File.createTempFile("redis-cmd", ".log", rootDir)
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out)
            if (stdin == null) pb.redirectInput(ProcessBuilder.Redirect.from(File(if (isWindows) "NUL" else "/dev/null")))
            val p = pb.start()
            if (stdin != null) {
                runCatching { p.outputStream.bufferedWriter().use { it.write(stdin) } }
            }
            val finished = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            val text = out.readText()
            runCatching { File(rootDir, "redis-install.log").appendText("> ${cmd.joinToString(" ")}\n$text\n") }
            (if (finished) p.exitValue() else -1) to text
        } finally {
            out.delete()
        }
    } catch (e: Exception) {
        -1 to (e.message ?: e.toString())
    }

    private fun isCommandAvailable(cmd: String): Boolean = runCatching {
        val p = ProcessBuilder("sh", "-c", "command -v $cmd").redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    /** True only when WSL has a distro that starts; `wsl --status` succeeds even with none installed. */
    // Generous timeouts: the first wsl call after boot has to start the distro, which can take ~20s.
    private fun isWslAvailable(): Boolean {
        val code = probe(listOf("wsl", "-e", "true"), 60)
        wslCheckResult = describeProbe(code, 60)
        return code == 0
    }

    private fun isWslRedisInstalled(): Boolean = probe(listOf("wsl", "-e", "sh", "-c", "command -v redis-server"), 60) == 0

    private fun startDockerMaster(tailscaleIp: String, port: Int, maxMemoryBytes: Long, password: String) {
        probe(listOf(dockerExe, "rm", "-f", "obsidianscout-redis"), 30)
        // Clients reach the container through Docker's bridge, which Redis sees as non-local, so protected
        // mode stays off inside it. The published ports from dockerPortArgs are what limit it to the tailnet.
        val cmd = mutableListOf(dockerExe, "run", "-d", "--name", "obsidianscout-redis")
        cmd.addAll(dockerPortArgs(tailscaleIp, port))
        cmd.addAll(listOf(
            "redis:7-alpine",
            "redis-server",
            "--protected-mode", "no",
            "--maxmemory", maxMemoryBytes.toString(),
            "--maxmemory-policy", "allkeys-lru",
            "--save", "",
            "--appendonly", "no"
        ))
        if (password.isNotBlank()) {
            cmd.addAll(listOf("--requirepass", password))
        }
        if (runCapture(cmd, 300).first == 0) {
            isDockerContainer = true
            log.info("[RedisOrchestrator] Started Docker Redis Master container on port {}", port)
        } else {
            log.error("[RedisOrchestrator] Failed to start Docker Redis Master container")
        }
    }

    private fun startDockerReplica(tailscaleIp: String, replicaPort: Int, masterHost: String, masterPort: Int, password: String): Boolean {
        probe(listOf(dockerExe, "rm", "-f", "obsidianscout-redis"), 30)
        val cmd = mutableListOf(dockerExe, "run", "-d", "--name", "obsidianscout-redis")
        cmd.addAll(dockerPortArgs(tailscaleIp, replicaPort))
        cmd.addAll(listOf(
            "redis:7-alpine",
            "redis-server",
            "--protected-mode", "no",
            "--replicaof", masterHost, masterPort.toString(),
            "--save", "",
            "--appendonly", "no"
        ))
        if (password.isNotBlank()) {
            cmd.addAll(listOf("--requirepass", password, "--masterauth", password))
        }
        return if (runCapture(cmd, 300).first == 0) {
            isDockerContainer = true
            log.info("[RedisOrchestrator] Started Docker Redis Replica container on port {}", replicaPort)
            true
        } else {
            log.error("[RedisOrchestrator] Failed to start Docker Redis Replica container")
            false
        }
    }

    /**
     * Checks the installed Redis version and upgrades it automatically if it is older than 7.4.0
     * (which is required to load RDB format version 12 from newer cluster masters).
     * Runs at most once per process and shares [installing] so it never races with a manual UI install.
     */
    fun autoUpgradeIfNeeded(): Boolean {
        if (upgradeAttempted) return false
        if (!installing.compareAndSet(false, true)) return false
        upgradeAttempted = true
        try {
            if (isWindows) {
                return autoUpgradeWindows()
            }
            return autoUpgradeUnix()
        } catch (e: Exception) {
            log.warn("[RedisOrchestrator] Auto-upgrade check failed: {}", e.message)
            return false
        } finally {
            installing.set(false)
            installProgress = null
        }
    }

    private fun autoUpgradeWindows(): Boolean {
        // Docker uses redis:7-alpine; only WSL distro packages (like Ubuntu's 7.0.15) lag behind RDB v12.
        if (resolveWindowsBackend() != WindowsBackend.WSL) return false
        val versionOut = runCapture(listOf("wsl", "-e", "redis-server", "--version"), 30).second
        val current = parseRedisVersion(versionOut) ?: return false
        if (isVersionSupported(current)) return false

        val verStr = "${current.first}.${current.second}.${current.third}"
        log.warn(
            "[RedisOrchestrator] WSL Redis {} is older than {}.{}.{} (required for RDB v12 replication). Auto-upgrading WSL Redis...",
            verStr, MIN_REDIS_MAJOR, MIN_REDIS_MINOR, MIN_REDIS_PATCH
        )
        installProgress = "Auto-upgrading WSL Redis ($verStr -> 7.4+)..."
        stop()
        val (code, out) = runCapture(listOf("wsl", "-u", "root", "-e", "sh", "-c", UPGRADE_SCRIPT), INSTALL_TIMEOUT_SECONDS)
        runCapture(listOf("wsl", "-u", "root", "-e", "sh", "-c", DISABLE_SERVICE_SCRIPT), 30)
        if (code != 0) {
            log.error("[RedisOrchestrator] WSL Redis auto-upgrade exited with code {}: {}", code, out.takeLast(1500))
            return false
        }
        val afterOut = runCapture(listOf("wsl", "-e", "redis-server", "--version"), 30).second
        val after = parseRedisVersion(afterOut)
        if (after != null && isVersionSupported(after)) {
            log.info("[RedisOrchestrator] Auto-upgraded WSL Redis from {} to {}.{}.{}.", verStr, after.first, after.second, after.third)
            windowsBackendCheckedAtMs = 0L
            return true
        }
        log.warn("[RedisOrchestrator] WSL Redis upgrade finished, but version is still '{}'.", afterOut.trim())
        return false
    }

    private fun autoUpgradeUnix(): Boolean {
        val bin = resolvedBinaryPath ?: return false
        val versionOut = runCapture(listOf(bin, "--version"), 10).second
        val current = parseRedisVersion(versionOut) ?: return false
        if (isVersionSupported(current)) return false

        val verStr = "${current.first}.${current.second}.${current.third}"
        val isRoot = System.getProperty("user.name") == "root"
        val canSudoNoPass = !isRoot && probe(listOf("sudo", "-n", "true"), 5) == 0
        if (!isRoot && !canSudoNoPass) {
            log.warn(
                "[RedisOrchestrator] Local Redis {} is older than {}.{}.{} (required for RDB v12 replication), and passwordless sudo is unavailable. Upgrade Redis manually or via the Redis Cache page.",
                verStr, MIN_REDIS_MAJOR, MIN_REDIS_MINOR, MIN_REDIS_PATCH
            )
            return false
        }

        log.warn(
            "[RedisOrchestrator] Local Redis {} is older than {}.{}.{} (required for RDB v12 replication). Auto-upgrading Redis...",
            verStr, MIN_REDIS_MAJOR, MIN_REDIS_MINOR, MIN_REDIS_PATCH
        )
        installProgress = "Auto-upgrading Redis ($verStr -> 7.4+)..."
        stop()
        val cmd = if (isRoot) listOf("sh", "-c", UPGRADE_SCRIPT) else listOf("sudo", "-n", "sh", "-c", UPGRADE_SCRIPT)
        val (code, out) = runCapture(cmd, INSTALL_TIMEOUT_SECONDS)
        val disableCmd = if (isRoot) listOf("sh", "-c", DISABLE_SERVICE_SCRIPT) else listOf("sudo", "-n", "sh", "-c", DISABLE_SERVICE_SCRIPT)
        runCapture(disableCmd, 30)
        if (code != 0) {
            log.error("[RedisOrchestrator] Redis auto-upgrade exited with code {}: {}", code, out.takeLast(1500))
            return false
        }
        val afterOut = runCapture(listOf(bin, "--version"), 10).second
        val after = parseRedisVersion(afterOut)
        if (after != null && isVersionSupported(after)) {
            log.info("[RedisOrchestrator] Auto-upgraded local Redis from {} to {}.{}.{}.", verStr, after.first, after.second, after.third)
            return true
        }
        log.warn("[RedisOrchestrator] Redis upgrade finished, but version is still '{}'.", afterOut.trim())
        return false
    }
}
