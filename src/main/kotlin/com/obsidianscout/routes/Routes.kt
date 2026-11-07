package com.obsidianscout.routes

import com.obsidianscout.config.AppConfigLoader
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureRoutes() {
    routing {
        route("/api") {
            localAiApiRoutes()

            get("/version") {
                val appConfig = AppConfigLoader.load()
                call.respond(VersionResponse(appConfig.current_version, com.obsidianscout.admin.ClusterManagementService.getLocalExecutionMode()))
            }
            // Scouting photos moved out of entry JSON. The id is random (122 bits) and only appears inside entries the
            // viewer could already read, so the URL works like the base64 it replaced, including on public share links.
            get("/blobs/{id}") {
                val id = com.obsidianscout.blob.BlobService.idFromUrl("/api/blobs/" + (call.parameters["id"] ?: ""))
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val meta = com.obsidianscout.blob.BlobService.meta(id)
                if (meta == null || meta.kind !in com.obsidianscout.blob.BlobService.PUBLIC_URL_KINDS) {
                    return@get call.respond(HttpStatusCode.NotFound)
                }
                val blob = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.obsidianscout.blob.BlobService.get(id) }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
                call.response.headers.append("X-Content-Type-Options", "nosniff")
                call.respondBytes(blob.bytes, ContentType.parse(blob.meta.contentType))
            }
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            }

            adminRoutes()
            authRoutes()
            docRoutes()
            configRoutes()
            scoutingRoutes()
            analyticsRoutes()
            eventAndMatchRoutes()
            chatRoutes()
            notificationRoutes()
            allianceRoutes()
            bannerRoutes()
            eventInfoRoutes()
            contactRoutes()
            widgetRoutes()
            publicDataRoutes()
            slackRoutes()
        }

        localAiModelFileRoutes()
        staticWebRoutes()
    }
}