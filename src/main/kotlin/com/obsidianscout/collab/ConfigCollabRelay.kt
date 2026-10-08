package com.obsidianscout.collab

import com.obsidianscout.auth.ClusterCryptoUtils
import com.obsidianscout.auth.ClusterSecretService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Pipes an editor's socket to the node that owns its room. The editor's identity and permission are
 * checked on the node it connected to and passed along in an HMAC-signed request (the same cluster
 * signature other inter-node calls use).
 */
object ConfigCollabRelay {
    const val PATH = "/api/cluster/config-collab/relay"

    private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.collab.ConfigCollabRelay")
    private val client by lazy {
        HttpClient(CIO) {
            install(io.ktor.client.plugins.websocket.WebSockets) { pingIntervalMillis = 15_000 }
            engine { requestTimeout = 0 }
        }
    }

    suspend fun relay(session: DefaultWebSocketServerSession, ownerAddress: String, scope: ConfigCollabScope, p: CollabParticipant) {
        val host = ownerAddress.substringBeforeLast(':')
        val port = ownerAddress.substringAfterLast(':').toIntOrNull()
        if (host.isBlank() || port == null) {
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "owner unreachable"))
            return
        }
        // Values are base64url so the signed query reaches the owner byte-for-byte.
        val query = listOf(
            "room" to scope.roomKey,
            "sid" to p.sid,
            "uid" to p.userId,
            "user" to p.username,
            "team" to p.teamNumber.toString(),
            "role" to p.role,
            "edit" to if (p.canEdit) "1" else "0"
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val pathAndQuery = "$PATH?$query"
        val timestamp = System.currentTimeMillis().toString()
        val signature = ClusterCryptoUtils.hmacSha256("$timestamp:GET:$pathAndQuery", ClusterSecretService.getSessionSecret())

        try {
            client.webSocket(
                method = HttpMethod.Get,
                host = host,
                port = port,
                path = pathAndQuery,
                request = {
                    header("X-Cluster-Timestamp", timestamp)
                    header("X-Cluster-Signature", signature)
                }
            ) {
                val upstream = this
                val pump = launch {
                    try {
                        for (frame in session.incoming) if (frame is Frame.Text) upstream.send(Frame.Text(frame.readText()))
                    } catch (_: Throwable) {
                    }
                    runCatching { upstream.close(CloseReason(CloseReason.Codes.NORMAL, "editor left")) }
                }
                try {
                    for (frame in incoming) if (frame is Frame.Text) session.send(Frame.Text(frame.readText()))
                } catch (_: Throwable) {
                } finally {
                    pump.cancel()
                }
                val reason = runCatching { closeReason.await() }.getOrNull()
                session.close(reason ?: CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "relay closed"))
            }
        } catch (e: Throwable) {
            log.debug("Relay of ${scope.roomKey} to $ownerAddress failed: ${e.message}")
            runCatching { session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "owner unreachable")) }
        }
    }

    fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun decode(value: String?): String? =
        value?.let { runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
}
