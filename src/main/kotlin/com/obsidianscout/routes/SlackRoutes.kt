package com.obsidianscout.routes

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.requireAdminOrPage
import com.obsidianscout.auth.requireSession
import com.obsidianscout.slack.SlackApiClient
import com.obsidianscout.slack.SlackApiException
import com.obsidianscout.slack.SlackChannelInfo
import com.obsidianscout.slack.SlackIntegrationRecord
import com.obsidianscout.slack.SlackIntegrationService
import com.obsidianscout.slack.SlackIntegrationUpdate
import com.obsidianscout.slack.SlackNotificationRelay
import com.obsidianscout.slack.SlackPreferenceDto
import com.obsidianscout.slack.SlackSocketSupervisor
import com.obsidianscout.slack.SlackTestRequest
import com.obsidianscout.slack.SlackTestResponse
import com.obsidianscout.slack.SlackUserGroupsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val ADMIN_PAGE = "admin-settings"

private val channelListCache = ConcurrentHashMap<String, Pair<Long, List<SlackChannelInfo>>>()

private fun quorumGuard() {
    if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
        throw ApiException(HttpStatusCode.ServiceUnavailable, "Database is in read-only offline mode due to cluster quorum loss.")
    }
}

/** The caller's team integration with a usable bot token, or 400. */
private suspend fun ApplicationCall.connectedIntegration(): SlackIntegrationRecord {
    val session = requireAdminOrPage(ADMIN_PAGE)
    val record = SlackIntegrationService.load(session.teamNumber, session.program)
    if (record == null || record.botToken.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Connect Slack first.")
    return record
}

private suspend fun <T> slackCall(block: suspend () -> T): T = try {
    block()
} catch (e: SlackApiException) {
    throw ApiException(HttpStatusCode.BadRequest, e.message ?: e.error)
}

fun Route.slackRoutes() {
    route("/slack") {
        route("/integration") {
            get {
                val session = call.requireAdminOrPage(ADMIN_PAGE)
                call.respond(SlackIntegrationService.toResponse(SlackIntegrationService.load(session.teamNumber, session.program)))
            }
            put {
                val session = call.requireAdminOrPage(ADMIN_PAGE)
                quorumGuard()
                val update = call.receive<SlackIntegrationUpdate>()
                val saved = SlackIntegrationService.save(session.teamNumber, session.program, update, session.username)
                channelListCache.remove("${session.teamNumber}:${session.program}")
                SlackSocketSupervisor.poke()
                call.respond(SlackIntegrationService.toResponse(saved))
            }
            delete {
                val session = call.requireAdminOrPage(ADMIN_PAGE)
                quorumGuard()
                SlackIntegrationService.delete(session.teamNumber, session.program)
                channelListCache.remove("${session.teamNumber}:${session.program}")
                SlackSocketSupervisor.poke()
                call.respond(SlackIntegrationService.toResponse(null))
            }
            post("/test") {
                val session = call.requireAdminOrPage(ADMIN_PAGE)
                val request = runCatching { call.receive<SlackTestRequest>() }.getOrDefault(SlackTestRequest())
                val stored = SlackIntegrationService.load(session.teamNumber, session.program)
                fun pick(v: String?, fallback: String?) =
                    v?.trim()?.takeIf { it.isNotEmpty() && it != SlackIntegrationService.MASK } ?: fallback?.takeIf { it.isNotBlank() }
                val bot = pick(request.botToken, stored?.botToken)
                val app = pick(request.appToken, stored?.appToken)
                if (bot == null || app == null) {
                    call.respond(SlackTestResponse(false, message = "Both the bot token and the app-level token are needed."))
                    return@post
                }
                val result = runCatching { SlackIntegrationService.verifyTokens(bot, app) }
                call.respond(
                    result.fold(
                        { SlackTestResponse(true, it?.teamName.orEmpty(), "Connected to ${it?.teamName.orEmpty()}.") },
                        { SlackTestResponse(false, message = it.message ?: "Slack test failed.") }
                    )
                )
            }
        }

        get("/channels") {
            val record = call.connectedIntegration()
            val cacheKey = "${record.teamNumber}:${record.program}"
            val fresh = call.request.queryParameters["refresh"] == "true"
            val cached = channelListCache[cacheKey]?.takeIf { !fresh && System.currentTimeMillis() - it.first < 60_000 }?.second
            val channels = cached ?: slackCall { SlackApiClient.channels(record.botToken) }.also {
                channelListCache[cacheKey] = System.currentTimeMillis() to it
            }
            call.respond(channels)
        }

        post("/channels/{id}/join") {
            val record = call.connectedIntegration()
            val id = call.parameters["id"].orEmpty()
            if (!Regex("[CG][A-Z0-9]{2,30}").matches(id)) throw ApiException(HttpStatusCode.BadRequest, "Invalid channel id")
            slackCall { SlackApiClient.join(record.botToken, id) }
            channelListCache.remove("${record.teamNumber}:${record.program}")
            call.respond(mapOf("success" to true))
        }

        get("/usergroups") {
            val record = call.connectedIntegration()
            val result = runCatching { SlackApiClient.userGroups(record.botToken) }
            call.respond(
                result.fold(
                    { SlackUserGroupsResponse(it.sortedBy { g -> g.handle }) },
                    { SlackUserGroupsResponse(emptyList(), (it as? SlackApiException)?.message ?: "Could not load user groups.") }
                )
            )
        }

        get("/user-links") {
            val record = call.connectedIntegration()
            call.respond(slackCall { SlackIntegrationService.userLinkRows(record) })
        }

        // Sends the calling admin a sample banner through the real delivery path.
        post("/preview") {
            val session = call.requireAdminOrPage(ADMIN_PAGE)
            quorumGuard()
            val stored = SlackIntegrationService.insertNotification(
                team = session.teamNumber,
                program = session.program,
                eventId = "preview:${java.util.UUID.randomUUID()}",
                channelId = "C0PREVIEW",
                channelName = "general",
                authorName = "Obsidianscout",
                text = "This is how Slack messages will appear. @${session.username}, you were mentioned.",
                permalink = SlackIntegrationService.load(session.teamNumber, session.program)?.workspaceUrl.orEmpty(),
                targets = mapOf(session.userId to true),
                push = false
            ) ?: throw ApiException(HttpStatusCode.InternalServerError, "Could not create preview")
            SlackNotificationRelay.deliver(stored)
            call.respond(mapOf("success" to true))
        }

        // Banners the caller missed while disconnected (at most 10 minutes back).
        get("/notifications") {
            val session = call.requireSession()
            if (session.publicView) throw ApiException(HttpStatusCode.Forbidden, "Not available")
            val since = call.request.queryParameters["since"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.now().minusSeconds(600)
            call.respond(SlackIntegrationService.recentFor(session.userId, since))
        }

        route("/me/preference") {
            get {
                val session = call.requireSession()
                if (session.publicView) throw ApiException(HttpStatusCode.Forbidden, "Not available")
                call.respond(SlackPreferenceDto(SlackIntegrationService.preferenceOf(session.userId)))
            }
            put {
                val session = call.requireSession()
                if (session.publicView) throw ApiException(HttpStatusCode.Forbidden, "Not available")
                quorumGuard()
                val body = call.receive<SlackPreferenceDto>()
                SlackIntegrationService.setPreference(session.userId, body.preference)
                call.respond(SlackPreferenceDto(body.preference))
            }
        }
    }
}
