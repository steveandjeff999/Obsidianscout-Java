package com.obsidianscout.slack

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.integrations.ClusterLease
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one Slack Socket Mode connection per team that has Slack switched on, on exactly one cluster node.
 *
 * Every node runs this loop. A team's socket belongs to whichever node holds the `slack:{team}:{program}` lease
 * ([ClusterLease]); the holder renews it every tick, and if that node dies another takes over once the lease
 * expires. Slack resends envelopes that were not acknowledged, and [SlackIntegrationService.insertNotification]
 * dedups by message, so a handover neither loses nor doubles banners.
 */
object SlackSocketSupervisor {
    private val log = LoggerFactory.getLogger("SlackSocketSupervisor")
    private const val TICK_MS = 15_000L
    private const val LEASE_TTL_SECONDS = 45L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loop: Job? = null

    /** [tokens] is what the socket was opened with; channel and people edits don't need a reconnect. */
    private class Connection(val job: Job, val tokens: Pair<String, String>)
    private val connections = ConcurrentHashMap<String, Connection>()
    /** Config version that failed with an error reconnecting can't fix; skipped until an admin saves again. */
    private val fatalAt = ConcurrentHashMap<String, Instant>()

    private fun key(r: SlackIntegrationRecord) = "${r.teamNumber}:${r.program}"
    private fun tokens(r: SlackIntegrationRecord) = r.botToken to r.appToken
    private fun leaseKey(k: String) = "slack:$k"

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            delay(5_000)
            while (isActive) {
                runCatching { tick() }.onFailure { if (it is CancellationException) throw it; log.debug("Slack supervisor tick failed: ${it.message}") }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        connections.forEach { (k, c) -> c.job.cancel(); ClusterLease.release(leaseKey(k)) }
        connections.clear()
    }

    /** Re-evaluate soon after an admin saves, instead of waiting for the next tick. */
    fun poke() {
        scope.launch { runCatching { tick() } }
    }

    @Synchronized
    private fun tickLocked(ready: List<SlackIntegrationRecord>) {
        val byKey = ready.associateBy(::key)

        // Drop connections whose team was switched off, or whose tokens changed (reconnect with the new ones).
        connections.entries.toList().forEach { (k, c) ->
            val current = byKey[k]
            if (current == null || tokens(current) != c.tokens || !c.job.isActive) {
                c.job.cancel()
                connections.remove(k)
                if (current == null) ClusterLease.release(leaseKey(k))
            }
        }

        for ((k, r) in byKey) {
            if (fatalAt[k] == r.updatedAt) continue
            when (ClusterLease.tryAcquire(leaseKey(k), LEASE_TTL_SECONDS)) {
                ClusterLease.Result.ACQUIRED -> if (connections[k] == null) {
                    connections[k] = Connection(scope.launch { runConnection(r) }, tokens(r))
                }
                ClusterLease.Result.HELD_ELSEWHERE -> connections.remove(k)?.job?.cancel()
                ClusterLease.Result.UNAVAILABLE -> Unit
            }
        }
    }

    private fun tick() {
        val ready = SlackIntegrationService.allReady()
        tickLocked(ready)
    }

    private suspend fun runConnection(r: SlackIntegrationRecord) {
        val k = key(r)
        var backoffMs = 2_000L
        while (scope.isActive) {
            try {
                SlackIntegrationService.setStatus(r.teamNumber, r.program, "connecting")
                val url = SlackApiClient.openConnection(r.appToken)
                SlackApiClient.client.webSocket(urlString = url) {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val envelope = runCatching { JsonSupport.json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                        // Acknowledge first: Slack resends anything not acked within 3 seconds.
                        envelope.str("envelope_id")?.let { id ->
                            send(Frame.Text(buildJsonObject { put("envelope_id", id) }.toString()))
                        }
                        when (envelope.str("type")) {
                            "hello" -> {
                                backoffMs = 2_000L
                                SlackIntegrationService.setStatus(r.teamNumber, r.program, "connected", "Connected through ${ClusterLease.nodeId.substringBefore('/')}")
                            }
                            "disconnect" -> break
                            "events_api" -> envelope.obj("payload")?.let { payload -> dispatch(r, payload) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SlackApiException) {
                if (e.error in SlackApiClient.fatalErrors) {
                    log.warn("Slack for team $k stopped: ${e.error}")
                    SlackIntegrationService.setStatus(r.teamNumber, r.program, "error", e.message ?: e.error)
                    fatalAt[k] = r.updatedAt
                    return
                }
                SlackIntegrationService.setStatus(r.teamNumber, r.program, "reconnecting", e.message ?: e.error)
            } catch (e: Exception) {
                log.debug("Slack socket for team $k dropped: ${e.message}")
                SlackIntegrationService.setStatus(r.teamNumber, r.program, "reconnecting", "Connection dropped; retrying.")
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun dispatch(r: SlackIntegrationRecord, payload: JsonObject) {
        scope.launch {
            runCatching { SlackEventProcessor.handle(r.teamNumber, r.program, payload) }
                .onFailure { log.warn("Slack event for team ${r.teamNumber} failed: ${it.message}") }
        }
    }
}
