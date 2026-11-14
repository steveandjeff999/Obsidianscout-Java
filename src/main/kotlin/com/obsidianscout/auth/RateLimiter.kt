package com.obsidianscout.auth

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val ipv4LiteralRegex = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

/** True only for an IPv4/IPv6 literal. Never resolves names, so header values can't trigger DNS lookups. */
private fun isValidIpLiteral(value: String): Boolean {
    val clean = value.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    if (clean.isEmpty() || clean.length > 45) return false
    if (ipv4LiteralRegex.matches(clean)) return true
    // IPv6 literals always contain ':'; InetAddress parses those without a DNS lookup.
    if (':' !in clean) return false
    if (!clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }) {
        return false
    }
    return runCatching { java.net.InetAddress.getByName(clean) }.isSuccess
}

/**
 * Resolves the client IP for rate limiting and audit logs.
 *
 * Forwarding headers are only honoured when the direct peer is a proxy we run ourselves:
 * loopback (cloudflared, the built-in HTTPS proxy, a local reverse proxy) or a Tailscale
 * cluster peer (100.64.0.0/10). Anyone else could set those headers to any value.
 */
fun ApplicationCall.clientIp(): String {
    val peer = request.local.remoteAddress
    if (!isTrustedProxyAddress(peer)) return canonicalIp(peer)

    val forwarded = request.headers.getAll("X-Forwarded-For")
        ?.flatMap { it.split(",") }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && isValidIpLiteral(it) }
        .orEmpty()
    // Walk right-to-left, skipping trusted proxy hops, so client-supplied left entries can't spoof IPs.
    val clientHop = forwarded.asReversed().firstOrNull { !isTrustedProxyAddress(canonicalIp(it)) }

    // CF-Connecting-IP is only meaningful when Cloudflare is actually in front: the peer is a Cloudflare edge,
    // our own cloudflared tunnel is running, or a local proxy (e.g. the built-in HTTPS proxy) reports a
    // Cloudflare edge as the hop that reached it.
    val cfTrusted = com.obsidianscout.security.CloudflareNetwork.contains(canonicalIp(peer)) ||
        (clientHop != null && com.obsidianscout.security.CloudflareNetwork.contains(canonicalIp(clientHop))) ||
        com.obsidianscout.admin.CloudflaredService.isTunnelProcessAlive()
    if (cfTrusted) {
        request.headers["CF-Connecting-IP"]?.trim()
            ?.takeIf { it.isNotEmpty() && isValidIpLiteral(it) }
            ?.let { return canonicalIp(it) }
    }

    return canonicalIp(clientHop ?: forwarded.lastOrNull() ?: peer)
}

/**
 * One spelling per address, so every per-IP key (blocks, threat scores, rate limits) agrees. IPv6 can be written many
 * ways ("2001:db8::1", "2001:DB8:0:0:0:0:0:1", "[2001:db8::1]"); all become Java's form, which is also what stored
 * blocks use. IPv4-mapped IPv6 ("::ffff:203.0.113.7") becomes plain IPv4. IPv4 and non-literals come back as given.
 */
fun canonicalIp(address: String): String {
    val trimmed = address.trim()
    if (!trimmed.contains(':')) return trimmed
    val clean = trimmed.removePrefix("[").removeSuffix("]").substringBefore('%')
    // Literal addresses only: InetAddress.getByName would otherwise resolve a hostname over DNS.
    if (clean.isEmpty() || clean.length > 45 || !clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }) {
        return trimmed
    }
    return runCatching { java.net.InetAddress.getByName(clean).hostAddress }.getOrDefault(trimmed)
}

/** Loopback or a Tailscale cluster peer: our own proxies and nodes. Never blocked or scored by the security layer. */
fun isTrustedProxyAddress(address: String): Boolean {
    if (address.equals("localhost", ignoreCase = true)) return true
    // Literal addresses only: InetAddress.getByName would otherwise resolve a hostname over DNS.
    if (address.isBlank() || !address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' || it == '%' }) return false
    val inet = runCatching { java.net.InetAddress.getByName(address) }.getOrNull() ?: return false
    if (inet.isLoopbackAddress) return true
    val bytes = inet.address
    // Tailscale CGNAT range 100.64.0.0/10
    return bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
}

/**
 * Failed logins per IP and per username, [MAX_ATTEMPTS] a minute. Every node counts in its own memory and, while Redis is
 * up, in shared keys too, so a load balancer spreading attempts over the cluster doesn't multiply the limit. Either count
 * reaching the limit blocks: if Redis drops mid-attack, this node's own count still holds.
 */
object LoginRateLimiter {
    private val log = org.slf4j.LoggerFactory.getLogger(LoginRateLimiter::class.java)
    private const val REDIS_PREFIX = "obsidian:ratelimit:login"

    /** INCR and start the window in one step, so a key can't be left without an expiry and block forever. */
    private const val INCREMENT_SCRIPT =
        "local c = redis.call('INCR', KEYS[1]) if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end return c"

    private data class AttemptWindow(
        val count: AtomicInteger = AtomicInteger(0),
        val windowStart: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    private val ipAttempts = ConcurrentHashMap<String, AttemptWindow>()
    private val usernameAttempts = ConcurrentHashMap<String, AttemptWindow>()

    private fun ipKey(ip: String) = "$REDIS_PREFIX:ip:$ip"
    private fun userKey(username: String) = "$REDIS_PREFIX:user:${username.trim().lowercase()}"

    /** The keys for whichever of [ip] and [username] are given. */
    private fun redisKeys(ip: String, username: String): List<String> = buildList {
        if (ip.isNotBlank()) add(ipKey(ip))
        if (username.isNotBlank()) add(userKey(username))
    }

    /** Runs [block] against Redis when it's up; null when it isn't or the call fails, and the local counts decide. */
    private fun <T> withRedis(block: (redis.clients.jedis.Jedis) -> T): T? {
        if (!com.obsidianscout.cache.RedisClusterCoordinator.isReady) return null
        return try {
            com.obsidianscout.cache.RedisClusterCoordinator.getJedis().use(block)
        } catch (e: Exception) {
            log.debug("[LoginRateLimiter] Redis unavailable, using this node's counts: {}", e.message)
            null
        }
    }

    private const val MAX_ATTEMPTS = 10
    private const val WINDOW_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null

    /**
     * Checks whether the given IP and username are allowed to attempt login.
     * Returns true if allowed, false if rate limited.
     */
    fun checkAllowed(ip: String, username: String): Boolean {
        val now = System.currentTimeMillis()

        if (ip.isNotBlank()) {
            val ipWindow = ipAttempts.computeIfAbsent(ip) { AttemptWindow(AtomicInteger(0), AtomicLong(now)) }
            if (now - ipWindow.windowStart.get() > WINDOW_MS) {
                ipWindow.windowStart.set(now)
                ipWindow.count.set(0)
            }
            if (ipWindow.count.get() >= MAX_ATTEMPTS) {
                return false
            }
        }

        if (username.isNotBlank()) {
            val userKey = username.trim().lowercase()
            val userWindow = usernameAttempts.computeIfAbsent(userKey) { AttemptWindow(AtomicInteger(0), AtomicLong(now)) }
            if (now - userWindow.windowStart.get() > WINDOW_MS) {
                userWindow.windowStart.set(now)
                userWindow.count.set(0)
            }
            if (userWindow.count.get() >= MAX_ATTEMPTS) {
                return false
            }
        }

        val keys = redisKeys(ip, username)
        if (keys.isEmpty()) return true
        val shared = withRedis { it.mget(*keys.toTypedArray()) } ?: return true
        return shared.none { (it?.toIntOrNull() ?: 0) >= MAX_ATTEMPTS }
    }

    /**
     * Records a failed attempt for the given IP and username. [isLogin] is false for other requests sharing the limit.
     */
    fun recordFailedAttempt(
        ip: String,
        username: String,
        isLogin: Boolean = true,
        unknownUser: Boolean = false,
        teamNumber: Int? = null,
        account: String? = null
    ) {
        val now = System.currentTimeMillis()

        if (ip.isNotBlank()) {
            val ipWindow = ipAttempts.computeIfAbsent(ip) { AttemptWindow(AtomicInteger(0), AtomicLong(now)) }
            if (now - ipWindow.windowStart.get() > WINDOW_MS) {
                ipWindow.windowStart.set(now)
                ipWindow.count.set(0)
            }
            ipWindow.count.incrementAndGet()
        }

        if (username.isNotBlank()) {
            val userKey = username.trim().lowercase()
            val userWindow = usernameAttempts.computeIfAbsent(userKey) { AttemptWindow(AtomicInteger(0), AtomicLong(now)) }
            if (now - userWindow.windowStart.get() > WINDOW_MS) {
                userWindow.windowStart.set(now)
                userWindow.count.set(0)
            }
            userWindow.count.incrementAndGet()
        }

        val keys = redisKeys(ip, username)
        if (keys.isNotEmpty()) {
            withRedis { jedis ->
                keys.forEach { jedis.eval(INCREMENT_SCRIPT, listOf(it), listOf(WINDOW_MS.toString())) }
            }
        }
        if (isLogin) com.obsidianscout.admin.ServerAnalyticsService.recordAuthFailure(ip, username, unknownUser, teamNumber, account)
    }

    /**
     * Clears recorded attempts on successful authentication.
     */
    fun clear(ip: String, username: String) {
        if (ip.isNotBlank()) {
            ipAttempts.remove(ip)
        }
        if (username.isNotBlank()) {
            usernameAttempts.remove(username.trim().lowercase())
        }
        val keys = redisKeys(ip, username)
        if (keys.isNotEmpty()) withRedis { it.del(*keys.toTypedArray()) }
    }

    @Synchronized
    fun startCleanupJob() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch {
            while (isActive) {
                delay(5 * 60_000L) // Cleanup every 5 minutes
                try {
                    val now = System.currentTimeMillis()
                    ipAttempts.entries.removeIf { now - it.value.windowStart.get() > WINDOW_MS * 2 }
                    usernameAttempts.entries.removeIf { now - it.value.windowStart.get() > WINDOW_MS * 2 }
                } catch (_: Exception) {}
            }
        }
    }
}

object BugReportRateLimiter {

    private data class RateWindow(
        val count: AtomicInteger = AtomicInteger(0),
        val windowStart: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    private val ipRequests = ConcurrentHashMap<String, RateWindow>()

    internal const val DEFAULT_MAX_REPORTS = 10
    internal const val DEFAULT_WINDOW_MS = 60_000L

    var maxReports: Int = DEFAULT_MAX_REPORTS
    var windowMs: Long = DEFAULT_WINDOW_MS

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null

    /**
     * Atomically checks whether the given IP is allowed to submit a bug report,
     * and records the request if allowed.
     * Returns true if allowed and recorded; false if rate-limited.
     */
    fun tryAcquire(ip: String): Boolean {
        val key = ip.trim().ifBlank { "unknown" }
        val now = System.currentTimeMillis()
        val window = ipRequests.computeIfAbsent(key) { RateWindow(AtomicInteger(0), AtomicLong(now)) }

        synchronized(window) {
            if (now - window.windowStart.get() > windowMs) {
                window.windowStart.set(now)
                window.count.set(0)
            }
            if (window.count.get() >= maxReports) {
                return false
            }
            window.count.incrementAndGet()
            return true
        }
    }

    /**
     * Checks whether the given IP is currently allowed to submit a bug report,
     * without incrementing the counter.
     */
    fun checkAllowed(ip: String): Boolean {
        val key = ip.trim().ifBlank { "unknown" }
        val now = System.currentTimeMillis()
        val window = ipRequests[key] ?: return true
        synchronized(window) {
            if (now - window.windowStart.get() > windowMs) {
                return true
            }
            return window.count.get() < maxReports
        }
    }

    /**
     * Records a request for the given IP.
     */
    fun recordRequest(ip: String) {
        val key = ip.trim().ifBlank { "unknown" }
        val now = System.currentTimeMillis()
        val window = ipRequests.computeIfAbsent(key) { RateWindow(AtomicInteger(0), AtomicLong(now)) }
        synchronized(window) {
            if (now - window.windowStart.get() > windowMs) {
                window.windowStart.set(now)
                window.count.set(0)
            }
            window.count.incrementAndGet()
        }
    }

    /**
     * Clears recorded requests for a specific IP.
     */
    fun clear(ip: String) {
        val key = ip.trim().ifBlank { "unknown" }
        ipRequests.remove(key)
    }

    /**
     * Clears all recorded requests and resets limits to defaults (useful for testing).
     */
    fun reset() {
        ipRequests.clear()
        maxReports = DEFAULT_MAX_REPORTS
        windowMs = DEFAULT_WINDOW_MS
    }

    @Synchronized
    fun startCleanupJob() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch {
            while (isActive) {
                delay(5 * 60_000L) // Cleanup every 5 minutes
                try {
                    val now = System.currentTimeMillis()
                    ipRequests.entries.removeIf { now - it.value.windowStart.get() > windowMs * 2 }
                } catch (_: Exception) {}
            }
        }
    }
}
