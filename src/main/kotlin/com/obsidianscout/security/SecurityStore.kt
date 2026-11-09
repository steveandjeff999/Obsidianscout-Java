package com.obsidianscout.security

import com.obsidianscout.cache.RedisClusterCoordinator
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.pow

/**
 * Counters the detectors and the threat scorer share. Every value is kept in this node's memory and, while Redis is
 * up, in shared Redis keys too; reads take the larger of the two. So a load balancer spreading an attack over the
 * cluster doesn't dilute it, and a Redis outage mid-attack doesn't reset anything (the same rule as LoginRateLimiter).
 */
internal object SecurityStore {
    private val log = org.slf4j.LoggerFactory.getLogger(SecurityStore::class.java)
    private const val PREFIX = "obsidian:sec:"

    /** Upper bound on keys per local map, so a flood from many addresses can't exhaust memory. */
    private const val MAX_LOCAL_KEYS = 200_000

    @Volatile var halfLifeMs: Long = 15 * 60_000L

    /** Tests switch Redis off so they never touch a real server. */
    @Volatile var redisEnabled: Boolean = true

    private class Score(@Volatile var value: Double, @Volatile var at: Long)
    private class Window(@Volatile var start: Long, @Volatile var count: Int)

    private val scores = ConcurrentHashMap<String, Score>()
    private val distinct = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val counters = ConcurrentHashMap<String, Window>()
    private val flags = ConcurrentHashMap<String, Long>()

    private const val SCORE_SCRIPT = """
local s = tonumber(redis.call('HGET', KEYS[1], 's') or '0')
local t = tonumber(redis.call('HGET', KEYS[1], 't') or ARGV[1])
local now = tonumber(ARGV[1])
local hl = tonumber(ARGV[2])
s = s * math.pow(0.5, math.max(0, now - t) / hl) + tonumber(ARGV[3])
redis.call('HSET', KEYS[1], 's', tostring(s), 't', tostring(now))
redis.call('PEXPIRE', KEYS[1], ARGV[4])
return tostring(s)
"""
    private const val DISTINCT_SCRIPT = """
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', tonumber(ARGV[1]) - tonumber(ARGV[3]))
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return redis.call('ZCARD', KEYS[1])
"""
    private const val COUNT_SCRIPT =
        "local c = redis.call('INCR', KEYS[1]) if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end return c"
    private const val SLIDING_COUNT_SCRIPT =
        "local c = redis.call('INCR', KEYS[1]) redis.call('PEXPIRE', KEYS[1], ARGV[1]) return c"

    private fun <T> redis(block: (redis.clients.jedis.Jedis) -> T): T? {
        if (!redisEnabled || !RedisClusterCoordinator.isReady) return null
        return try {
            RedisClusterCoordinator.getJedis().use(block)
        } catch (e: Exception) {
            log.debug("[SecurityStore] Redis unavailable, using this node's values: {}", e.message)
            null
        }
    }

    private fun decayed(value: Double, at: Long, now: Long): Double =
        if (value <= 0.0) 0.0 else value * 0.5.pow(max(0L, now - at).toDouble() / halfLifeMs)

    private fun ensureCapacity(map: ConcurrentHashMap<*, *>) {
        if (map.size > MAX_LOCAL_KEYS) cleanup(System.currentTimeMillis(), aggressive = true)
    }

    // ---------------------------------------------------------------- threat scores

    /** Adds [amount] to a decaying score and returns the new value. */
    fun addScore(key: String, amount: Double, now: Long = System.currentTimeMillis()): Double {
        ensureCapacity(scores)
        val local = scores.compute(key) { _, old ->
            if (old == null) Score(amount, now) else Score(decayed(old.value, old.at, now) + amount, now)
        }!!.value
        val shared = redis {
            (it.eval(SCORE_SCRIPT, listOf(PREFIX + "score:" + key),
                listOf(now.toString(), halfLifeMs.toString(), amount.toString(), (halfLifeMs * 8).toString())) as? String)?.toDoubleOrNull()
        }
        return max(local, shared ?: 0.0)
    }

    fun score(key: String, now: Long = System.currentTimeMillis()): Double {
        val local = scores[key]?.let { decayed(it.value, it.at, now) } ?: 0.0
        val shared = redis { jedis ->
            val fields = jedis.hmget(PREFIX + "score:" + key, "s", "t")
            val s = fields.getOrNull(0)?.toDoubleOrNull()
            val t = fields.getOrNull(1)?.toLongOrNull()
            if (s != null && t != null) decayed(s, t, now) else null
        }
        return max(local, shared ?: 0.0)
    }

    fun resetScore(key: String) {
        scores.remove(key)
        redis { it.del(PREFIX + "score:" + key) }
    }

    /** Local scores above [minimum] whose key starts with [prefix], highest first. Used for the "top offenders" list. */
    fun topLocalScores(prefix: String, minimum: Double, limit: Int, now: Long = System.currentTimeMillis()): List<Pair<String, Double>> =
        scores.entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.key.removePrefix(prefix) to decayed(it.value.value, it.value.at, now) }
            .filter { it.second >= minimum }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()

    // ---------------------------------------------------------------- distinct members in a sliding window

    /** Records [member] under [key] and returns how many distinct members were seen in the last [windowMs]. */
    fun addDistinct(key: String, member: String, windowMs: Long, now: Long = System.currentTimeMillis()): Int {
        ensureCapacity(distinct)
        val members = distinct.computeIfAbsent(key) { ConcurrentHashMap() }
        members[member] = now
        members.entries.removeIf { now - it.value > windowMs }
        if (members.size > 10_000) members.entries.removeIf { now - it.value > windowMs / 4 }
        val local = members.size
        val shared = redis {
            (it.eval(DISTINCT_SCRIPT, listOf(PREFIX + "set:" + key), listOf(now.toString(), member, windowMs.toString())) as? Long)?.toInt()
        }
        return max(local, shared ?: 0)
    }

    fun distinctCount(key: String, windowMs: Long, now: Long = System.currentTimeMillis()): Int {
        val local = distinct[key]?.values?.count { now - it <= windowMs } ?: 0
        val shared = redis { jedis ->
            jedis.zcount(PREFIX + "set:" + key, (now - windowMs).toDouble(), Double.POSITIVE_INFINITY).toInt()
        }
        return max(local, shared ?: 0)
    }

    // ---------------------------------------------------------------- counters

    /** Fixed-window counter: increments and returns the count in the current [windowMs] window. */
    fun increment(key: String, windowMs: Long, now: Long = System.currentTimeMillis(), sliding: Boolean = false): Int {
        ensureCapacity(counters)
        val local = counters.compute(key) { _, old ->
            when {
                old == null || now - old.start > windowMs -> Window(now, 1)
                sliding -> Window(now, old.count + 1)
                else -> old.also { it.count++ }
            }
        }!!.count
        val shared = redis {
            (it.eval(if (sliding) SLIDING_COUNT_SCRIPT else COUNT_SCRIPT, listOf(PREFIX + "count:" + key), listOf(windowMs.toString())) as? Long)?.toInt()
        }
        return max(local, shared ?: 0)
    }

    fun count(key: String, windowMs: Long, now: Long = System.currentTimeMillis()): Int {
        val local = counters[key]?.takeIf { now - it.start <= windowMs }?.count ?: 0
        val shared = redis { it.get(PREFIX + "count:" + key)?.toIntOrNull() }
        return max(local, shared ?: 0)
    }

    fun resetCount(key: String) {
        counters.remove(key)
        redis { it.del(PREFIX + "count:" + key) }
    }

    // ---------------------------------------------------------------- flags

    fun setFlag(key: String, ttlMs: Long, now: Long = System.currentTimeMillis()) {
        flags[key] = now + ttlMs
        redis { it.psetex(PREFIX + "flag:" + key, ttlMs, "1") }
    }

    fun hasFlag(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val local = flags[key]?.let { it > now } ?: false
        if (local) return true
        return redis { it.exists(PREFIX + "flag:" + key) } ?: false
    }

    /** Milliseconds left on a flag, or 0 when it isn't set. */
    fun flagTtlMs(key: String, now: Long = System.currentTimeMillis()): Long {
        val local = flags[key]?.let { max(0L, it - now) } ?: 0L
        val shared = redis { it.pttl(PREFIX + "flag:" + key) }?.takeIf { it > 0 } ?: 0L
        return max(local, shared)
    }

    fun clearFlag(key: String) {
        flags.remove(key)
        redis { it.del(PREFIX + "flag:" + key) }
    }

    /** Local flag keys starting with [prefix] that are still set, with their expiry. */
    fun localFlags(prefix: String, now: Long = System.currentTimeMillis()): Map<String, Long> =
        flags.filter { it.key.startsWith(prefix) && it.value > now }.mapKeys { it.key.removePrefix(prefix) }

    /** Redis flag keys starting with [prefix], with their expiry. Empty when Redis is down. */
    fun sharedFlags(prefix: String, now: Long = System.currentTimeMillis()): Map<String, Long> = redis { jedis ->
        val out = HashMap<String, Long>()
        var cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START
        val params = redis.clients.jedis.params.ScanParams().match(PREFIX + "flag:" + prefix + "*").count(500)
        var rounds = 0
        do {
            val page = jedis.scan(cursor, params)
            for (key in page.result) {
                val ttl = jedis.pttl(key)
                if (ttl > 0) out[key.removePrefix(PREFIX + "flag:" + prefix)] = now + ttl
            }
            cursor = page.cursor
            rounds++
        } while (cursor != redis.clients.jedis.params.ScanParams.SCAN_POINTER_START && rounds < 50)
        out
    } ?: emptyMap()

    /**
     * Claims [key] for [ttlMs] if nobody holds it, across the cluster when Redis is up. True when this caller got it.
     * Used to report something once per window ("once per IP per 10 minutes").
     */
    fun claimOnce(key: String, ttlMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val localExpiry = flags[PREFIX + "once:" + key]
        if (localExpiry != null && localExpiry > now) return false
        flags[PREFIX + "once:" + key] = now + ttlMs
        val shared = redis {
            it.set(PREFIX + "once:" + key, "1", redis.clients.jedis.params.SetParams().nx().px(ttlMs))
        }
        // Redis down: this node's claim stands. Redis up: only the caller that set the key reports.
        return shared == null || shared == "OK"
    }

    // ---------------------------------------------------------------- housekeeping

    fun cleanup(now: Long = System.currentTimeMillis(), aggressive: Boolean = false) {
        val scoreFloor = if (aggressive) 5.0 else 0.5
        scores.entries.removeIf { decayed(it.value.value, it.value.at, now) < scoreFloor }
        val windowAge = if (aggressive) 10 * 60_000L else 2 * 60 * 60_000L
        distinct.entries.removeIf { entry ->
            entry.value.entries.removeIf { now - it.value > windowAge }
            entry.value.isEmpty()
        }
        counters.entries.removeIf { now - it.value.start > if (aggressive) 10 * 60_000L else 25 * 60 * 60_000L }
        flags.entries.removeIf { it.value <= now }
    }

    /** Tests only. */
    fun resetForTests() {
        scores.clear()
        distinct.clear()
        counters.clear()
        flags.clear()
    }
}
