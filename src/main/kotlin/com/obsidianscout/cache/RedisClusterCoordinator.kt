package com.obsidianscout.cache

import com.obsidianscout.admin.ClusterManagementService
import com.obsidianscout.auth.ClusterCryptoUtils
import com.obsidianscout.auth.ClusterSecretService
import com.obsidianscout.cache.orchestration.RedisOrchestrator
import com.obsidianscout.config.AppConfig
import com.obsidianscout.integrations.ClusterLease
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

object RedisClusterCoordinator {
    private val log = LoggerFactory.getLogger("com.obsidianscout.cache.RedisClusterCoordinator")
    private const val LEASE_KEY = "redis_host"
    private const val LEASE_TTL_SECONDS = 15L
    private const val RENEW_INTERVAL_MS = 5_000L
    private const val DEAD_MAN_TIMEOUT_MS = 8_000L
    private const val DEFAULT_PORT = 6379

    private lateinit var appConfig: AppConfig
    private lateinit var orchestrator: RedisOrchestrator
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var isReady: Boolean = false; private set
    @Volatile var isHost: Boolean = false; private set
    @Volatile var canHostRedis: Boolean = false; private set
    val installProgress: String? get() = RedisOrchestrator.installProgress

    /** Starts installing Redis in the background. Returns false if this node is not set up yet or an install is already running. */
    fun triggerInstall(sudoPassword: String?): Boolean {
        if (!::orchestrator.isInitialized || RedisOrchestrator.isInstalling) return false
        scope.launch(Dispatchers.IO) {
            if (orchestrator.runInstall(sudoPassword)) {
                // Re-detect on the current orchestrator: a settings save may have rebuilt it mid-install.
                canHostRedis = orchestrator.detectHostCapability(getEffectivePassword())
                log.info("[RedisClusterCoordinator] Installation succeeded. Host capability now canHost={}", canHostRedis)
            }
        }
        return true
    }
    @Volatile var activeEndpoint: String? = null; private set // Format: "hostIp:port"
    @Volatile private var localPort: Int = DEFAULT_PORT
    @Volatile private var jedisPool: JedisPool? = null
    @Volatile private var lastRenewalTimestampMs: Long = 0L
    @Volatile private var warnedNoTailnet: Boolean = false
    @Volatile private var loopJob: Job? = null
    private val reloadMutex = kotlinx.coroutines.sync.Mutex()

    fun getEffectivePassword(): String {
        if (!::appConfig.isInitialized) return ""
        if (!appConfig.redis.require_password) return ""
        if (appConfig.redis.password.isNotBlank()) return appConfig.redis.password
        val secret = ClusterSecretService.getSessionSecret().ifBlank { appConfig.server.sessionSecret }
        return ClusterCryptoUtils.sha256Hex((secret + "_redis_cluster_pwd").toByteArray(StandardCharsets.UTF_8))
    }

    fun init(bootConfig: AppConfig) {
        // The redis section may have been changed from the Redis Cache page since boot.
        val config = bootConfig.copy(redis = com.obsidianscout.config.AppConfigLoader.load().redis)
        this.appConfig = config
        this.orchestrator = RedisOrchestrator(config)
        this.canHostRedis = orchestrator.detectHostCapability(getEffectivePassword())
        log.info("[RedisClusterCoordinator] Host capability verified: canHost={}", canHostRedis)

        if (!config.redis.enabled) {
            log.info("[RedisClusterCoordinator] Redis is disabled in configuration. Running in Direct-DB Fallback mode.")
            return
        }

        // Statically configured external Redis: continuously monitor & reconnect via lifecycle loop
        if (config.redis.host.isNotBlank()) {
            val staticEndpoint = "${config.redis.host}:${config.redis.port}"
            log.info("[RedisClusterCoordinator] Using statically configured external Redis at {}", staticEndpoint)
            loopJob = startStaticHostLoop(config.redis.host, config.redis.port)
            return
        }

        loopJob = startLifecycleLoop()
    }

    private fun startStaticHostLoop(host: String, port: Int) = scope.launch {
        val endpoint = "$host:$port"
        activeEndpoint = endpoint
        while (isActive) {
            try {
                if (!isReady) {
                    connectAsClient(host, port, endpoint)
                } else {
                    verifyConnection()
                }
            } catch (e: Exception) {
                log.warn("[RedisClusterCoordinator] Static Redis connection check error: {}", e.message)
                isReady = false
            }
            delay(RENEW_INTERVAL_MS)
        }
    }

    private fun startLifecycleLoop() = scope.launch {
        while (isActive) {
            try {
                coordinate()
                checkDeadManSwitch()
            } catch (e: Exception) {
                log.warn("[RedisClusterCoordinator] Coordination tick error: {}", e.message)
            }
            delay(RENEW_INTERVAL_MS)
        }
    }

    private fun coordinate() {
        val myIp = ClusterManagementService.getLocalTailscaleIp()
        // getLocalTailscaleIp falls back to loopback when Tailscale is down. Advertising that would send
        // every other node to its own localhost, so this node only hosts while it has a tailnet address.
        val onTailnet = myIp != "127.0.0.1"
        if (!onTailnet && canHostRedis && !warnedNoTailnet) {
            log.warn("[RedisClusterCoordinator] No Tailscale IP found. Not hosting Redis until this node is on the tailnet.")
            warnedNoTailnet = true
        } else if (onTailnet) {
            warnedNoTailnet = false
        }

        // 1. Upfront Port Resolution:
        // Use running port, or an existing Redis on DEFAULT_PORT if peers can reach it, otherwise probe loopback
        val targetPort = if (orchestrator.isAlive() || orchestrator.isExternalRunning) {
            localPort
        } else if (onTailnet && isLocalPortOpen(DEFAULT_PORT) && orchestrator.isRedisReachable(myIp, DEFAULT_PORT, getEffectivePassword())) {
            orchestrator.markExternalRunning(true)
            localPort = DEFAULT_PORT
            DEFAULT_PORT
        } else {
            findAvailablePort(DEFAULT_PORT).also { localPort = it }
        }
        val myEndpoint = "$myIp:$targetPort"

        // 2. Capability-Gated Election: Only capable nodes attempt to claim the host lease
        val leaseResult = if (canHostRedis && onTailnet) {
            ClusterLease.tryAcquire(LEASE_KEY, LEASE_TTL_SECONDS, holder = myEndpoint)
        } else {
            ClusterLease.Result.HELD_ELSEWHERE
        }

        when (leaseResult) {
            ClusterLease.Result.ACQUIRED -> {
                lastRenewalTimestampMs = System.currentTimeMillis()
                if (!isHost) {
                    becomeHost(myIp, targetPort)
                } else {
                    // Already host: continuously verify health and recover if connection blipped
                    if (!isReady) {
                        verifyConnection()
                        if (isReady) {
                            RedisPubSubListener.start("127.0.0.1", targetPort, getEffectivePassword())
                        }
                    }
                }
            }
            ClusterLease.Result.HELD_ELSEWHERE -> {
                if (isHost) {
                    stepDownAsHost()
                }

                val holder = ClusterLease.holderOf(LEASE_KEY)
                if (holder != null && holder != activeEndpoint) {
                    val parts = holder.split(":")
                    val host = parts[0]
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
                    connectAsClient(host, port, holder)
                } else if (holder != null && !isReady) {
                    // Re-check periodically so node auto-recovers as soon as master is reachable
                    verifyConnection()
                    if (isReady) {
                        val parts = holder.split(":")
                        val host = parts[0]
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
                        RedisPubSubListener.start(host, port, getEffectivePassword())
                    }
                }

                // Standby Warm Replica: If capable and not running, launch a replica on a free dynamic port
                if (canHostRedis && onTailnet && !orchestrator.isAlive() && !orchestrator.isExternalRunning && activeEndpoint != null) {
                    val parts = activeEndpoint!!.split(":")
                    val masterHost = parts[0]
                    val masterPort = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT

                    val replicaPort = findAvailablePort(DEFAULT_PORT + 1)
                    localPort = replicaPort
                    // Pass myIp so replica binds to Tailscale IP, ready for immediate promotion without restart
                    orchestrator.startReplica(myIp, replicaPort, masterHost, masterPort, getEffectivePassword())
                }
            }
            ClusterLease.Result.UNAVAILABLE -> {
                log.warn("[RedisClusterCoordinator] Database lease unavailable. Operating in Direct-DB Fallback mode.")
                isReady = false
            }
        }
    }

    private fun becomeHost(myIp: String, port: Int) {
        log.info("[RedisClusterCoordinator] Node elected REDIS HOST on endpoint {}:{}", myIp, port)
        isHost = true
        activeEndpoint = "$myIp:$port"
        val assessment = RedisMemoryAutoSizer.calculateOptimalMaxMemory(appConfig.redis)
        val pwd = getEffectivePassword()

        if (orchestrator.isAlive()) {
            orchestrator.promoteToMaster(myIp, assessment.recommendedRedisMb * 1024 * 1024, pwd)
        } else {
            orchestrator.startMaster(myIp, port, assessment.recommendedRedisMb * 1024 * 1024, pwd)
        }

        // Wait up to 3 seconds for Redis process to become responsive
        waitForPortReady("127.0.0.1", port, 3000L)

        updatePool("127.0.0.1", port)
        verifyConnection()
        if (isReady) {
            RedisPubSubListener.start("127.0.0.1", port, pwd)
        }
    }

    private fun stepDownAsHost() {
        log.warn("[RedisClusterCoordinator] Stepping down as host.")
        isHost = false
        orchestrator.stop()
        RedisPubSubListener.stop()
        isReady = false
    }

    fun triggerFailover() {
        val holder = activeEndpoint ?: ClusterLease.holderOf(LEASE_KEY)
        if (holder != null) {
            ClusterLease.release(LEASE_KEY, holder)
            log.info("[RedisClusterCoordinator] Released cluster lease for holder {}", holder)
        }
        if (isHost) {
            stepDownAsHost()
        }
        isReady = false
    }

    private fun connectAsClient(host: String, port: Int, endpoint: String) {
        log.info("[RedisClusterCoordinator] Connecting to Redis Master at {}:{}", host, port)
        activeEndpoint = endpoint
        updatePool(host, port)
        verifyConnection()
        if (isReady) {
            RedisPubSubListener.start(host, port, getEffectivePassword())
        }
    }

    private fun checkDeadManSwitch() {
        if (isHost) {
            val elapsed = System.currentTimeMillis() - lastRenewalTimestampMs
            if (elapsed > DEAD_MAN_TIMEOUT_MS) {
                log.error("[RedisClusterCoordinator] DEAD-MAN'S SWITCH TRIPPED: Renewal overdue ({}ms > {}ms). Forcibly terminating local Redis to prevent split-brain.", elapsed, DEAD_MAN_TIMEOUT_MS)
                stepDownAsHost()
            }
        }
    }

    private fun isLocalPortOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    }.getOrDefault(false)

    private fun waitForPortReady(host: String, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalPortOpen(port)) return
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
    }

    private fun findAvailablePort(startPort: Int): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (port in startPort..startPort + 50) {
            try {
                ServerSocket(port, 1, loopback).use { return it.localPort }
            } catch (_: Exception) {}
        }
        return DEFAULT_PORT + 10
    }

    private fun updatePool(host: String, port: Int) {
        val poolConfig = JedisPoolConfig().apply {
            maxTotal = 64
            maxIdle = 16
            minIdle = 4
            testOnBorrow = false
            testWhileIdle = true
        }
        val oldPool = jedisPool
        val password = getEffectivePassword().ifBlank { null }
        jedisPool = if (password != null) {
            JedisPool(poolConfig, host, port, 2000, password)
        } else {
            JedisPool(poolConfig, host, port, 2000)
        }

        if (oldPool != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { oldPool.close() }
            }
        }
    }

    private fun verifyConnection() {
        try {
            getJedis().use { jedis ->
                val pong = jedis.ping()
                isReady = (pong == "PONG")
            }
        } catch (e: Exception) {
            log.warn("[RedisClusterCoordinator] Connection check to {} failed: {}. Operating in fallback mode.", activeEndpoint, e.message)
            isReady = false
        }
    }

    fun getJedis(): Jedis {
        return jedisPool?.resource ?: throw IllegalStateException("JedisPool not ready or Redis host unreachable")
    }

    /**
     * Restarts Redis coordination with [config] without restarting the app. Steps down and releases the host
     * lease first so another node can take over while this one comes back with the new settings.
     */
    fun applyConfig(config: AppConfig) {
        if (!::appConfig.isInitialized) {
            // Background startup hasn't reached init yet; it reads the saved redis section when it does.
            return
        }
        scope.launch {
            reloadMutex.withLock {
                log.info("[RedisClusterCoordinator] Applying updated Redis configuration (enabled={}).", config.redis.enabled)
                loopJob?.cancelAndJoin()
                loopJob = null
                if (isHost) {
                    activeEndpoint?.let { ClusterLease.release(LEASE_KEY, it) }
                    stepDownAsHost()
                } else {
                    RedisPubSubListener.stop()
                    orchestrator.stop()
                }
                isReady = false
                activeEndpoint = null
                val poolToClose = jedisPool
                jedisPool = null
                runCatching { poolToClose?.close() }
                init(config)
            }
        }
    }

    fun stop() {
        scope.cancel()
        RedisPubSubListener.stop()
        if (isHost) orchestrator.stop()
        val poolToClose = jedisPool
        jedisPool = null
        poolToClose?.close()
        isReady = false
    }
}
