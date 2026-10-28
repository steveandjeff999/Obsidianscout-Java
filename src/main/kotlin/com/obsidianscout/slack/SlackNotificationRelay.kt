package com.obsidianscout.slack

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.NotificationWebSocketManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Gets Slack banners to users on every node. [NotificationWebSocketManager] only knows the sockets connected to
 * this node, so the node that stores a notification can't reach everyone. Instead every node tails the
 * slack_notifications table and sends new rows to its own sockets.
 *
 * The tail looks back a fixed window rather than keeping a high-water mark, so rows written by a node whose
 * clock is a little behind are not skipped; [delivered] stops a row going out twice.
 */
object SlackNotificationRelay {
    private val log = LoggerFactory.getLogger("SlackNotificationRelay")
    private const val POLL_MS = 2_000L
    private const val LOOKBACK_SECONDS = 60L
    private const val RETENTION_DAYS = 7L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loop: Job? = null
    private val delivered = ConcurrentHashMap<String, Long>()
    private var lastCleanup = 0L

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                try {
                    poll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.debug("Slack relay poll failed: ${e.message}")
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        SlackIntegrationService.notificationsSince(Instant.now().minusSeconds(LOOKBACK_SECONDS)).forEach(::deliver)
        delivered.entries.removeIf { now - it.value > (LOOKBACK_SECONDS + 60) * 1000 }
        if (now - lastCleanup > 10 * 60_000) {
            lastCleanup = now
            runCatching { SlackIntegrationService.deleteOlderThan(Instant.now().minusSeconds(RETENTION_DAYS * 86_400)) }
        }
    }

    /** Sends [n] to this node's sockets for its target users, once. */
    fun deliver(n: SlackIntegrationService.StoredNotification) {
        if (delivered.putIfAbsent(n.id, System.currentTimeMillis()) != null) return
        n.targets.keys.forEach { userId ->
            NotificationWebSocketManager.sendToUser(
                userId,
                JsonSupport.wireJson.encodeToString(SlackBannerDto.serializer(), n.bannerFor(userId))
            )
        }
    }
}
