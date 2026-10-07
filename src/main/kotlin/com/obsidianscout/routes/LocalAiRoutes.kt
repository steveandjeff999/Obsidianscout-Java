package com.obsidianscout.routes

import com.obsidianscout.ai.LocalAiModelService
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.requireSession
import com.obsidianscout.auth.requireSuperAdmin
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class LocalAiTierRequest(val tier: String)

/** `/api/local-ai/...` and `/api/admin/local-ai/...` — mounted inside the `/api` route. */
fun Route.localAiApiRoutes() {
    route("/local-ai") {
        // Installed tiers + runtime URLs for the browser. Any signed-in user; the per-user
        // `localAiEnabled` preference only controls whether the UI uses it.
        get("/manifest") {
            call.requireSession()
            call.respond(LocalAiModelService.clientManifest())
        }
    }

    route("/admin/local-ai") {
        get("/status") {
            call.requireSuperAdmin()
            call.respond(LocalAiModelService.adminStatus())
        }
        post("/install") {
            call.requireSuperAdmin()
            val request = call.receive<LocalAiTierRequest>()
            if (LocalAiModelService.tier(request.tier) == null) {
                throw ApiException(HttpStatusCode.BadRequest, "Unknown model tier")
            }
            val started = LocalAiModelService.startInstall(request.tier)
            call.respond(mapOf("started" to started))
        }
        post("/cancel") {
            call.requireSuperAdmin()
            val request = call.receive<LocalAiTierRequest>()
            call.respond(mapOf("cancelled" to LocalAiModelService.cancelInstall(request.tier)))
        }
        delete("/{tier}") {
            call.requireSuperAdmin()
            val tier = call.parameters["tier"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing tier")
            if (LocalAiModelService.tier(tier) == null) {
                throw ApiException(HttpStatusCode.BadRequest, "Unknown model tier")
            }
            LocalAiModelService.deleteTier(tier)
            call.respond(LocalAiModelService.adminStatus())
        }
    }
}

/** `/models/...`: model weights and inference runtimes, served from disk (never bundled in the app). */
fun Route.localAiModelFileRoutes() {
    route("/models") {
        get("{segments...}") {
            // Signed-in users only, so the multi-GB files can't be hotlinked.
            call.requireSession()
            val segments = call.parameters.getAll("segments") ?: emptyList()
            val file = LocalAiModelService.resolveServedFile(segments)
                ?: throw ApiException(HttpStatusCode.NotFound, "Not found")
            call.respond(LocalFileContent(file, ContentType.parse(LocalAiModelService.contentTypeFor(file))))
        }
    }
}
