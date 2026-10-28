package com.obsidianscout.routes

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.requireAdmin
import com.obsidianscout.auth.requireSession
import com.obsidianscout.db.BackupService
import com.obsidianscout.db.EventInfoService
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.scouting.AllianceService
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.streamProvider
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** Reading needs the Event Info page under the team's page permissions (a CUSTOM user's own picks for that role). */
private fun requireEventInfoPage(session: UserSession) {
    if (session.publicView || !SettingsService.canAccessPage(session.teamNumber, session.program, session.role, "event-info", session.userId)) {
        throw ApiException(HttpStatusCode.Forbidden, "Your role doesn't have access to Event Info")
    }
}

private fun currentEventKey(session: UserSession): String =
    runCatching { AllianceService.getEffectiveSettings(session.teamNumber, session.program).resolvedEventKey() }.getOrDefault("")

fun Route.eventInfoRoutes() {
    route("/event-info") {
        get {
            val session = call.requireSession()
            requireEventInfoPage(session)
            val eventKey = call.request.queryParameters["eventKey"]?.trim()?.takeIf { it.isNotEmpty() } ?: currentEventKey(session)
            call.respond(
                EventInfoListResponse(
                    eventKey = eventKey,
                    canEdit = session.role.isAtLeast(UserRole.ADMIN),
                    maxFileBytes = EventInfoService.MAX_FILE_BYTES,
                    posts = EventInfoService.list(session.teamNumber, session.program, eventKey)
                )
            )
        }

        post {
            val session = call.requireAdmin()
            val request = call.receive<EventInfoPostRequest>()
            call.respond(EventInfoService.create(session.teamNumber, session.program, currentEventKey(session), session.username, request))
        }

        put("/{id}") {
            val session = call.requireAdmin()
            val id = call.parameters["id"].orEmpty()
            val request = call.receive<EventInfoPostRequest>()
            val updated = EventInfoService.update(id, session.teamNumber, session.program, currentEventKey(session), request)
                ?: throw ApiException(HttpStatusCode.NotFound, "Post not found")
            call.respond(updated)
        }

        delete("/{id}") {
            val session = call.requireAdmin()
            if (!EventInfoService.delete(call.parameters["id"].orEmpty(), session.teamNumber, session.program)) {
                throw ApiException(HttpStatusCode.NotFound, "Post not found")
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/files") {
            val session = call.requireAdmin()
            val postId = call.parameters["id"].orEmpty()
            val multipart = call.receiveMultipart(formFieldLimit = EventInfoService.MAX_FILE_BYTES + 64 * 1024)
            val added = mutableListOf<EventInfoFileDto>()
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem) {
                        val bytes = try {
                            part.streamProvider().use { BackupService.readLimited(it, EventInfoService.MAX_FILE_BYTES) }
                        } catch (e: Exception) {
                            throw ApiException(HttpStatusCode.PayloadTooLarge, "Files must be ${EventInfoService.MAX_FILE_BYTES / (1024 * 1024)} MB or smaller")
                        }
                        val file = try {
                            EventInfoService.addFile(postId, session.teamNumber, session.program, part.originalFileName ?: "file", bytes)
                        } catch (e: IllegalStateException) {
                            throw ApiException(HttpStatusCode.BadRequest, e.message ?: "Could not attach file")
                        } ?: throw ApiException(HttpStatusCode.NotFound, "Post not found")
                        added.add(file)
                    }
                } finally {
                    part.release()
                }
            }
            if (added.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "No file uploaded")
            call.respond(added)
        }

        get("/files/{fileId}") {
            val session = call.requireSession()
            requireEventInfoPage(session)
            val file = EventInfoService.getFile(call.parameters["fileId"].orEmpty())
            if (file == null || file.teamNumber != session.teamNumber || file.program != session.program) {
                throw ApiException(HttpStatusCode.NotFound, "File not found")
            }
            val inline = EventInfoService.isInline(file.contentType) && call.request.queryParameters["download"] != "1"
            val disposition = (if (inline) ContentDisposition.Inline else ContentDisposition.Attachment)
                .withParameter(ContentDisposition.Parameters.FileName, file.fileName)
            call.response.header(HttpHeaders.ContentDisposition, disposition.toString())
            call.response.header("X-Content-Type-Options", "nosniff")
            // Browsers' PDF viewers don't run in a sandboxed document; everything else is locked down.
            if (file.contentType != "application/pdf") {
                call.response.header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self'; style-src 'unsafe-inline'")
            }
            // File ids are never reused, so a viewer can keep its copy.
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(file.bytes, runCatching { ContentType.parse(file.contentType) }.getOrDefault(ContentType.Application.OctetStream))
        }

        delete("/files/{fileId}") {
            val session = call.requireAdmin()
            if (!EventInfoService.deleteFile(call.parameters["fileId"].orEmpty(), session.teamNumber, session.program)) {
                throw ApiException(HttpStatusCode.NotFound, "File not found")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
