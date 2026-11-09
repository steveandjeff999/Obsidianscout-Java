package com.obsidianscout.security

import com.obsidianscout.cache.RedisClusterCoordinator
import com.obsidianscout.cache.RedisPubSubListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tells the other nodes that blocks or the allowlist changed, so an unblock takes effect cluster-wide within a second
 * instead of waiting for the 30-second refresh. Rides on the existing cache invalidation channel.
 */
internal object SecuritySync {
    private const val PREFIX = "obsidian:security:changed:"
    const val BLOCKS = "blocks"
    const val ALLOWLIST = "allowlist"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var listening = false

    fun publish(kind: String) {
        if (!RedisClusterCoordinator.isReady) return
        runCatching { RedisClusterCoordinator.getJedis().use { it.publish(RedisPubSubListener.INVALIDATION_CHANNEL, PREFIX + kind) } }
    }

    @Synchronized
    fun listen() {
        if (listening) return
        listening = true
        RedisPubSubListener.addListener { message ->
            if (!message.startsWith(PREFIX)) return@addListener
            when (message.removePrefix(PREFIX)) {
                BLOCKS -> scope.launch { runCatching { BlockList.refresh() } }
                ALLOWLIST -> scope.launch { runCatching { Allowlist.refresh() } }
            }
        }
    }
}
