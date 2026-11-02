package com.obsidianscout.cache

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.LongAdder

object RedisCache {
    @PublishedApi internal val log = LoggerFactory.getLogger("com.obsidianscout.cache.RedisCache")
    @PublishedApi internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Telemetry counters for SuperAdmin Dashboard
    val hits = LongAdder()
    val misses = LongAdder()
    val stampedesAvoided = LongAdder()
    val evictions = LongAdder()

    /**
     * Retrieves [key] from Redis or executes [loader] with SingleFlight stampede protection.
     * Guarantees only 1 database query occurs when a key expires, even under 50 concurrent requests.
     */
    suspend inline fun <reified T> getOrSet(key: String, ttlSeconds: Long, crossinline loader: suspend () -> T): T {
        if (!RedisClusterCoordinator.isReady) {
            misses.increment()
            return loader()
        }

        // 1. Lock-free optimistic read
        val cached = readFromRedis<T>(key, countStats = false)
        if (cached != null) {
            hits.increment()
            return cached
        }

        // 2. Cache miss: enter SingleFlight mutex per key. Each request is counted once: a miss only if
        // this request ran the loader, otherwise a hit (a waiter shares the leader's result).
        var ranBlock = false
        val result = SingleFlight.execute(key) {
            ranBlock = true
            // Double-check: another coroutine may have populated the key while we were waiting
            val doubleChecked = readFromRedis<T>(key, countStats = false)
            if (doubleChecked != null) {
                hits.increment()
                stampedesAvoided.increment()
                return@execute doubleChecked
            }

            // Still missing: execute database loader
            misses.increment()
            val fresh = loader()
            writeToRedis(key, ttlSeconds, fresh)
            fresh
        }
        if (!ranBlock) {
            hits.increment()
            stampedesAvoided.increment()
        }
        return result
    }

    inline fun <reified T> readFromRedis(key: String, countStats: Boolean = true): T? {
        if (!RedisClusterCoordinator.isReady) {
            if (countStats) misses.increment()
            return null
        }
        return try {
            val raw = RedisClusterCoordinator.getJedis().use { jedis ->
                jedis.get(key)
            }
            if (raw != null) {
                if (countStats) hits.increment()
                json.decodeFromString<T>(raw)
            } else {
                if (countStats) misses.increment()
                null
            }
        } catch (e: Exception) {
            if (countStats) misses.increment()
            log.debug("[RedisCache] Read failed for {}: {}", key, e.message)
            null
        }
    }

    inline fun <reified T> writeToRedis(key: String, ttlSeconds: Long, value: T) {
        if (!RedisClusterCoordinator.isReady) return
        try {
            RedisClusterCoordinator.getJedis().use { jedis ->
                jedis.setex(key, ttlSeconds, json.encodeToString(value))
            }
        } catch (e: Exception) {
            log.warn("[RedisCache] Write failed for {}: {}", key, e.message)
        }
    }

    fun deleteByPattern(pattern: String) {
        if (!RedisClusterCoordinator.isReady) return
        try {
            RedisClusterCoordinator.getJedis().use { jedis ->
                var cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START
                val params = redis.clients.jedis.params.ScanParams().match(pattern).count(100)
                var totalDeleted = 0L
                do {
                    val scanResult = jedis.scan(cursor, params)
                    val matchedKeys = scanResult.result
                    if (!matchedKeys.isNullOrEmpty()) {
                        jedis.del(*matchedKeys.toTypedArray())
                        totalDeleted += matchedKeys.size
                    }
                    cursor = scanResult.cursor
                } while (cursor != redis.clients.jedis.params.ScanParams.SCAN_POINTER_START && cursor != "0")
                if (totalDeleted > 0) {
                    evictions.add(totalDeleted)
                }
                jedis.publish(RedisPubSubListener.INVALIDATION_CHANNEL, pattern)
            }
        } catch (e: Exception) {
            log.warn("[RedisCache] deleteByPattern failed for {}: {}", pattern, e.message)
        }
    }

    fun delete(vararg keys: String) {
        if (!RedisClusterCoordinator.isReady || keys.isEmpty()) return
        try {
            RedisClusterCoordinator.getJedis().use { jedis ->
                jedis.del(*keys)
                evictions.add(keys.size.toLong())
            }
        } catch (e: Exception) {
            log.warn("[RedisCache] Delete failed for keys: {}", e.message)
        }
    }

    /**
     * Evicts keys locally and publishes an invalidation event across the cluster.
     */
    fun evictAndBroadcast(vararg keys: String) {
        if (!RedisClusterCoordinator.isReady || keys.isEmpty()) return
        try {
            RedisClusterCoordinator.getJedis().use { jedis ->
                jedis.del(*keys)
                evictions.add(keys.size.toLong())
                jedis.publish(RedisPubSubListener.INVALIDATION_CHANNEL, keys.joinToString(","))
            }
        } catch (e: Exception) {
            log.warn("[RedisCache] Eviction broadcast failed: {}", e.message)
        }
    }

    /**
     * Atomically bumps the generational version for an event, instantaneously obsoleting
     * all cached predictions, rankings, and schedules for that event in O(1) time (<0.2ms).
     */
    fun bumpEventVersion(eventKey: String?): Long {
        if (!RedisClusterCoordinator.isReady || eventKey.isNullOrBlank()) return 0L
        val cleanKey = eventKey.lowercase().trim()
        return try {
            RedisClusterCoordinator.getJedis().use { jedis ->
                val newVer = jedis.incr("obsidian:event:$cleanKey:v")
                jedis.publish(RedisPubSubListener.INVALIDATION_CHANNEL, "event:$cleanKey:v:$newVer")
                newVer
            }
        } catch (e: Exception) {
            log.warn("[RedisCache] bumpEventVersion failed for {}: {}", cleanKey, e.message)
            0L
        }
    }

    fun getEventVersion(eventKey: String?): Long {
        if (!RedisClusterCoordinator.isReady || eventKey.isNullOrBlank()) return 1L
        val cleanKey = eventKey.lowercase().trim()
        return try {
            RedisClusterCoordinator.getJedis().use { jedis ->
                // Not counted in hits/misses: this is bookkeeping for a cache read, not a cache read itself.
                jedis.get("obsidian:event:$cleanKey:v")?.toLongOrNull() ?: 1L
            }
        } catch (_: Exception) {
            1L
        }
    }
}
