package com.obsidianscout.cache.orchestration

import com.obsidianscout.config.AppConfig
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import java.io.File

class RedisOrchestrator(private val appConfig: AppConfig) {
    private val log = LoggerFactory.getLogger("com.obsidianscout.cache.orchestration.RedisOrchestrator")
    private val rootDir = File(".redis")
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val binaryName = if (isWindows) "redis-server.exe" else "redis-server"
    private val localBinaryFile = File(rootDir, binaryName)
    private var resolvedBinaryPath: String? = null
    private var process: Process? = null
    private var isDockerContainer: Boolean = false
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

        // On Windows: ONLY Docker can host cluster Redis over Tailscale (WSL 2 cannot bind to host Tailscale interface)
        if (isDockerAvailable()) {
            log.info("[RedisOrchestrator] Windows Docker runtime detected. Node can host Redis.")
            return true
        }

        log.info("[RedisOrchestrator] Windows host lacks Docker runtime or existing Redis. Operating strictly in Redis client mode.")
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

    fun startReplica(tailscaleIp: String, replicaPort: Int, masterHost: String, masterPort: Int, password: String) {
        stop()
        activePort = replicaPort

        if (isWindows) {
            startWindowsReplica(tailscaleIp, replicaPort, masterHost, masterPort, password)
            return
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
            process = ProcessBuilder(cmd)
                .directory(rootDir)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                .start()

            log.info("[RedisOrchestrator] Started Redis Replica on {}:{} replicating {}:{}", tailscaleIp, replicaPort, masterHost, masterPort)
        } else if (isDockerAvailable()) {
            startDockerReplica(tailscaleIp, replicaPort, masterHost, masterPort, password)
        }
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
        if (isDockerContainer) {
            runCatching {
                ProcessBuilder("docker", "stop", "obsidianscout-redis").start().waitFor()
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

    fun isAlive(): Boolean = (process?.isAlive ?: false) || isDockerContainer

    private fun testBinaryExecution(path: String): Boolean = runCatching {
        val p = ProcessBuilder(path, "--version").start()
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private fun isDockerAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("docker", "info").start()
        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

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

    private fun startWindowsMaster(tailscaleIp: String, port: Int, maxMemoryBytes: Long, password: String) {
        if (isDockerAvailable()) {
            startDockerMaster(tailscaleIp, port, maxMemoryBytes, password)
        } else {
            log.error("[RedisOrchestrator] Windows host cannot host Redis without Docker.")
        }
    }

    private fun startWindowsReplica(tailscaleIp: String, replicaPort: Int, masterHost: String, masterPort: Int, password: String) {
        if (isDockerAvailable()) {
            startDockerReplica(tailscaleIp, replicaPort, masterHost, masterPort, password)
        } else {
            log.error("[RedisOrchestrator] Windows host cannot run replica without Docker.")
        }
    }

    private fun startDockerMaster(tailscaleIp: String, port: Int, maxMemoryBytes: Long, password: String) {
        runCatching { ProcessBuilder("docker", "rm", "-f", "obsidianscout-redis").start().waitFor() }
        // Clients reach the container through Docker's bridge, which Redis sees as non-local, so protected
        // mode stays off inside it. The published ports from dockerPortArgs are what limit it to the tailnet.
        val cmd = mutableListOf("docker", "run", "-d", "--name", "obsidianscout-redis")
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
        val p = ProcessBuilder(cmd).start()
        if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
            isDockerContainer = true
            log.info("[RedisOrchestrator] Started Docker Redis Master container on port {}", port)
        } else {
            log.error("[RedisOrchestrator] Failed to start Docker Redis Master container")
        }
    }

    private fun startDockerReplica(tailscaleIp: String, replicaPort: Int, masterHost: String, masterPort: Int, password: String) {
        runCatching { ProcessBuilder("docker", "rm", "-f", "obsidianscout-redis").start().waitFor() }
        val cmd = mutableListOf("docker", "run", "-d", "--name", "obsidianscout-redis")
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
        val p = ProcessBuilder(cmd).start()
        if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
            isDockerContainer = true
            log.info("[RedisOrchestrator] Started Docker Redis Replica container on port {}", replicaPort)
        } else {
            log.error("[RedisOrchestrator] Failed to start Docker Redis Replica container")
        }
    }
}
