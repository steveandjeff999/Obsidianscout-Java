package com.obsidianscout.routes

import com.obsidianscout.auth.clientIp
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.publicdata.PublicDataService
import com.obsidianscout.publicdata.PublicViewContext
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.util.pipeline.PipelinePhase
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * Read routes a visitor may use on a team's public data, relative to /api/public/{team}/{event}. Everything else
 * mounted under that prefix (assignments, users, chat, checklists, share links, saved reports...) answers 404, so a
 * new read route stays private until it is added here on purpose.
 */
internal val PUBLIC_DATA_ALLOWED_PATHS = setOf(
    "settings",
    "config", "pit-config", "qual-config",
    "scouting", "pit-scouting", "qual-scouting",
    "events", "teams", "matches",
    "stats/history",
    "analytics", "analytics/compare",
    "custom-analytics/dataset",
    "matches/predict", "matches/predict-all", "matches/projected-rankings",
    "field-images"
)

/**
 * Allowed reads that can call Statbotics / TBA / Match 13 with the team's API keys when nothing is cached. On these,
 * an event other than the visitor's chosen one must be an event the team has entries at, so an anonymous visitor
 * can't spend the team's quota fetching arbitrary events.
 */
private val PUBLIC_UPSTREAM_PATHS = setOf("stats/history", "matches/predict", "matches/predict-all", "matches/projected-rankings")

private val PublicSanitizePhase = PipelinePhase("PublicDataSanitize")

/** Outcome of checking a request under /api/public/{team}/{event}: a refusal, or the context to run it with. */
internal sealed interface PublicGateResult {
    data class Refused(val status: HttpStatusCode, val message: String) : PublicGateResult
    data class Allowed(val context: PublicViewContext) : PublicGateResult
}

/**
 * Decides whether a public data request may run. Rate limiting is separate (it needs the caller's IP).
 * [relativePath] is the path after /api/public/{team}/{event}/, without the query.
 */
internal fun checkPublicRequest(
    method: HttpMethod,
    relativePath: String,
    teamSegment: String,
    eventSegment: String,
    programParam: String?,
    /** The request's eventKey query parameter (or the event part of matchKey), if any. */
    requestedEvent: String? = null
): PublicGateResult {
    if (method != HttpMethod.Get && method != HttpMethod.Head) {
        return PublicGateResult.Refused(HttpStatusCode.MethodNotAllowed, "Public data is read-only")
    }
    val path = relativePath.trim('/')
    if (path !in PUBLIC_DATA_ALLOWED_PATHS) {
        return PublicGateResult.Refused(HttpStatusCode.NotFound, "Not available on public data")
    }
    val team = teamSegment.toIntOrNull() ?: 0
    val program = programParam?.trim()?.uppercase()?.takeIf { it == "FRC" || it == "FTC" } ?: "FRC"
    // Checked on every request, uncached, so unchecking the setting hides the data at once.
    val settings = PublicDataService.publicSettings(team, program)
        // 403, not 404: the app-wide 404 handler would replace the message the public view looks for.
        ?: return PublicGateResult.Refused(HttpStatusCode.Forbidden, PUBLIC_DATA_NOT_PUBLIC)
    val eventKey = eventSegment.trim().lowercase()
        .takeIf { it != PublicViewContext.NO_EVENT && it.matches(Regex("[a-z0-9_-]{1,64}")) }
        .orEmpty()
    val requested = requestedEvent?.trim()?.lowercase().orEmpty()
    if (path in PUBLIC_UPSTREAM_PATHS && requested.isNotBlank() && requested != eventKey &&
        requested != settings.resolvedEventKey().lowercase() && !PublicDataService.hasEntriesAt(team, program, requested)
    ) {
        return PublicGateResult.Refused(HttpStatusCode.NotFound, "No public data for that event")
    }
    return PublicGateResult.Allowed(PublicViewContext(team, program, eventKey, settings.dataPublicIncludeNotes))
}

/** The public view's script looks for this exact message to tell the visitor the data was made private. */
internal const val PUBLIC_DATA_NOT_PUBLIC = "This team's data is not public"

fun Route.publicDataRoutes() {
    route("/public") {
        get("/directory") {
            if (!PublicDataService.tryAcquire(call.clientIp())) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many requests. Try again in a minute."))
                return@get
            }
            call.respond(PublicDataService.directory())
        }
        route("/{publicTeam}/{publicEvent}") {
            installPublicDataGate()
            // The normal read routes, run as the owner team through the gate above.
            configRoutes()
            scoutingRoutes()
            analyticsRoutes()
            eventAndMatchRoutes()
        }
    }
}

private fun Route.installPublicDataGate() {
    val node = this as RoutingNode

    node.intercept(ApplicationCallPipeline.Plugins) {
        if (!PublicDataService.tryAcquire(call.clientIp())) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many requests. Try again in a minute."))
            finish()
            return@intercept
        }
        val teamSegment = call.parameters["publicTeam"].orEmpty()
        val eventSegment = call.parameters["publicEvent"].orEmpty()
        val relative = call.request.path().removePrefix("/api/public/$teamSegment/$eventSegment")
        val query = call.request.queryParameters
        val requestedEvent = query["eventKey"] ?: query["matchKey"]?.substringBefore('_')
        val context = when (val result = checkPublicRequest(
            call.request.httpMethod, relative, teamSegment, eventSegment, query["program"], requestedEvent
        )) {
            is PublicGateResult.Refused -> {
                call.respond(result.status, ErrorResponse(result.message))
                finish()
                return@intercept
            }
            is PublicGateResult.Allowed -> result.context
        }
        call.attributes.put(PublicViewContext.AttributeKey, context)
        withContext(PublicViewContext.local.asContextElement(context)) {
            proceed()
        }
    }

    // Strip scout names, credentials and (unless the team opted in) free-text notes from every JSON response.
    node.sendPipeline.insertPhaseAfter(ApplicationSendPipeline.Transform, PublicSanitizePhase)
    node.sendPipeline.intercept(PublicSanitizePhase) { message ->
        val context = call.attributes.getOrNull(PublicViewContext.AttributeKey) ?: return@intercept
        val text = when (message) {
            is TextContent -> message.text
            is OutgoingContent.ByteArrayContent -> message.bytes().toString(Charsets.UTF_8)
            else -> return@intercept
        }
        val contentType = (message as OutgoingContent).contentType
        if (contentType != null && !contentType.match(ContentType.Application.Json)) return@intercept
        val parsed = runCatching { JsonSupport.json.parseToJsonElement(text) }.getOrNull() ?: return@intercept
        val freeTextIds = if (context.includeNotes) emptySet() else PublicDataService.freeTextFieldIds(context.teamNumber, context.program)
        val sanitized = PublicDataService.sanitize(parsed, context.includeNotes, freeTextIds).toString()
        proceedWith(TextContent(sanitized, ContentType.Application.Json.withCharset(Charsets.UTF_8), message.status))
    }
}
