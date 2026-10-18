package com.obsidianscout.routes

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.requireSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.widgets.WidgetSnapshot
import com.obsidianscout.widgets.WidgetSnapshotService
import com.obsidianscout.widgets.WidgetTokenService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class WidgetTokenRequest(val platform: String = "")

@Serializable
data class WidgetTokenResponse(val token: String, val expiresAt: Long)

private val compactJson = Json(JsonSupport.json) { prettyPrint = false }

/** Last snapshot served per widget token, so a burst of widget refreshes costs one build. */
private data class ServedSnapshot(val at: Long, val body: String, val etag: String)
private val recentlyServed = ConcurrentHashMap<UUID, ServedSnapshot>()
private const val TOKEN_REUSE_MS = 3_000L

private fun etagOf(snapshot: WidgetSnapshot): String {
    // generatedAt changes every build; leave it out so an unchanged snapshot keeps its ETag.
    val stable = compactJson.encodeToString(WidgetSnapshot.serializer(), snapshot.copy(generatedAt = 0))
    val digest = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8))
    return "\"" + digest.take(12).joinToString("") { "%02x".format(it) } + "\""
}

private fun ApplicationCall.widgetToken(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Widget ", ignoreCase = true) }?.substring(7)?.trim()

private suspend fun ApplicationCall.respondSnapshot(body: String, etag: String) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, "no-store")
    if (request.headers[HttpHeaders.IfNoneMatch] == etag) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondText(body, ContentType.Application.Json)
    }
}

/**
 * Home-screen widgets. The app trades its login for a widget token, and the phone's widget code uses that token
 * to fetch one snapshot document on its own schedule, even when the app is closed.
 */
fun Route.widgetRoutes() {
    route("/widgets") {
        post("/token") {
            val session = call.requireSession()
            val request = runCatching { call.receive<WidgetTokenRequest>() }.getOrDefault(WidgetTokenRequest())
            if (session.sessionId.isNullOrBlank()) throw ApiException(HttpStatusCode.Unauthorized, "Sign in again to set up widgets")
            val issued = WidgetTokenService.issue(session, request.platform)
            call.respond(WidgetTokenResponse(issued.token, issued.expiresAt.epochSecond))
        }

        delete("/token") {
            val session = call.requireSession()
            WidgetTokenService.revokeForSession(session.sessionId)
            call.respond(HttpStatusCode.NoContent)
        }

        // Accepts either a widget token (phone widgets, app closed) or the normal login (the app itself).
        get("/snapshot") {
            val placed = call.request.queryParameters["placed"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            val token = call.widgetToken()
            if (token == null) {
                val session = call.requireSession()
                val snapshot = WidgetSnapshotService.build(session)
                // The app fetches for its widgets too: record what's placed so refresh pushes reach this phone.
                if (placed != null) WidgetTokenService.touchForSession(session.sessionId, placed, snapshot.event?.key.orEmpty())
                call.respondSnapshot(compactJson.encodeToString(WidgetSnapshot.serializer(), snapshot), etagOf(snapshot))
                return@get
            }

            val resolved = WidgetTokenService.resolve(token)
                ?: throw ApiException(HttpStatusCode.Unauthorized, "Widget sign-in expired")
            val now = System.currentTimeMillis()
            val recent = recentlyServed[resolved.tokenId]?.takeIf { now - it.at < TOKEN_REUSE_MS }
            if (recent != null && placed == null) {
                call.respondSnapshot(recent.body, recent.etag)
                return@get
            }
            val snapshot = WidgetSnapshotService.build(resolved.session)
            WidgetTokenService.touch(resolved, placed, snapshot.event?.key.orEmpty())
            val body = compactJson.encodeToString(WidgetSnapshot.serializer(), snapshot)
            val etag = etagOf(snapshot)
            recentlyServed[resolved.tokenId] = ServedSnapshot(now, body, etag)
            if (recentlyServed.size > 5_000) recentlyServed.entries.removeIf { now - it.value.at > TOKEN_REUSE_MS }
            call.respondSnapshot(body, etag)
        }
    }
}
