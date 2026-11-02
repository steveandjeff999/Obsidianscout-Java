package com.obsidianscout.cache

import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPubSub
import java.util.concurrent.CopyOnWriteArrayList

object RedisPubSubListener {
    private val log = LoggerFactory.getLogger("com.obsidianscout.cache.RedisPubSubListener")
    const val INVALIDATION_CHANNEL = "obsidian:cache_invalidations"

    private var listenerThread: Thread? = null
    @Volatile private var dedicatedJedis: Jedis? = null
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Starts the subscriber on a dedicated non-pooled socket outside JedisPool.
     */
    fun start(host: String, port: Int, password: String? = null) {
        stop()
        listenerThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val client = Jedis(host, port, 5000)
                    if (!password.isNullOrBlank()) {
                        client.auth(password)
                    }
                    dedicatedJedis = client
                    log.info("[RedisPubSubListener] Subscribed to {} on dedicated socket {}:{}", INVALIDATION_CHANNEL, host, port)

                    client.subscribe(object : JedisPubSub() {
                        override fun onMessage(channel: String, message: String) {
                            listeners.forEach { listener ->
                                runCatching { listener(message) }
                            }
                        }
                    }, INVALIDATION_CHANNEL)
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    log.debug("[RedisPubSubListener] Subscription disconnected: {}. Retrying in 2s...", e.message)
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }, "Redis-Dedicated-PubSub-Thread").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        listenerThread?.interrupt()
        try {
            dedicatedJedis?.close()
        } catch (_: Exception) {}
        dedicatedJedis = null
        listenerThread = null
    }
}
