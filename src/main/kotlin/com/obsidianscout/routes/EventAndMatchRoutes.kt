package com.obsidianscout.routes

import com.obsidianscout.auth.clientIp
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.analytics.AnalyticsService
import com.obsidianscout.analytics.PredictorService
import com.obsidianscout.analytics.ValidationService
import com.obsidianscout.config.ConfigMigrationService
import com.obsidianscout.config.GamepadProfileService
import com.obsidianscout.config.GamepadProfileDTO
import com.obsidianscout.config.GamepadProfileListResponse
import com.obsidianscout.config.GamepadProfileResponse
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.requireAdmin
import com.obsidianscout.auth.requireAnalyticsOrAbove
import com.obsidianscout.auth.requireSession
import com.obsidianscout.auth.requireSuperAdmin
import com.obsidianscout.auth.requireSuperAdminOrClusterAuth
import com.obsidianscout.auth.EmailService
import com.obsidianscout.auth.TeamSecretService
import com.obsidianscout.db.PasswordResetTokens
import com.obsidianscout.db.PushSubscriptions
import com.obsidianscout.db.PushNotificationService
import com.obsidianscout.db.FcmService
import com.obsidianscout.config.AppConfigLoader
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.lowerCase
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.SyncScheduler
import com.obsidianscout.integrations.SmtpSettings
import com.obsidianscout.integrations.CloudflaredSettings
import com.obsidianscout.admin.CloudflaredService
import com.obsidianscout.scouting.PitScoutingService
import com.obsidianscout.scouting.QualitativeScoutingService
import com.obsidianscout.scouting.QualitativeScoutingEntryRecord
import com.obsidianscout.scouting.ScoutingService
import com.obsidianscout.scouting.ScoutingAssignmentService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCallPipeline
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.http.content.streamProvider
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.patch
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import io.ktor.server.http.content.staticFiles
import java.io.File
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.sessions.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import com.obsidianscout.utils.*
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ChatService
import com.obsidianscout.db.ChatMessages

fun Route.eventAndMatchRoutes() {
            route("/events") {
                get {
                    val session = call.requireSession()
                    val eventKeyParam = call.request.queryParameters["eventKey"]
                    if (!eventKeyParam.isNullOrBlank()) {
                        val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                        var event = IntegrationService.getEvent(eventKeyParam)
                        // Public visitors only see what's cached: they mustn't spend the team's API keys.
                        if (!session.publicView && (event == null || event.timezone.isNullOrBlank() || event.timezone == "UTC")) {
                            try {
                                val fetched = IntegrationService.fetchAndUpsertEvent(settings, eventKeyParam)
                                if (fetched != null && !fetched.timezone.isNullOrBlank() && fetched.timezone != "UTC") {
                                    event = fetched
                                } else if (event == null) {
                                    event = fetched
                                }
                            } catch (e: Exception) {
                                // Ignore fetch failure
                            }
                        }
                        if (event != null) {
                            val resolvedEvent = if (event.timezone.isNullOrBlank()) {
                                event.copy(timezone = "UTC")
                            } else {
                                event
                            }
                            call.respond(resolvedEvent)
                            return@get
                        } else {
                            call.respond(
                                EventRecord(
                                    eventKey = eventKeyParam.lowercase().trim(),
                                    name = eventKeyParam,
                                    year = settings.year,
                                    timezone = "UTC"
                                )
                            )
                            return@get
                        }
                    }
                    val year = call.request.queryParameters["year"]?.toIntOrNull()
                    val cachedOnly = session.publicView || call.request.queryParameters["cached"]?.let { value ->
                        value == "1" || value.equals("true", ignoreCase = true)
                    } ?: false

                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val activeKey = settings.resolvedEventKey()

                    val events = IntegrationService.listEvents(year, cachedOnly, activeKey, settings, session = session)
                    call.respond(events)
                }
                post {
                    call.requireAdmin()
                    val request = call.receive<EventRecord>()
                    val saved = IntegrationService.saveEvent(request)
                    call.respond(saved)
                }
                put {
                    call.requireAdmin()
                    val request = call.receive<EventRenameRequest>()
                    val updated = IntegrationService.renameEvent(request.oldKey, request.event)
                    call.respond(updated)
                }
                delete {
                    call.requireAdmin()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val success = IntegrationService.deleteEvent(eventKey)
                    call.respond(mapOf("success" to success))
                }
            }

            route("/teams") {
                get {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: AllianceService.getEffectiveSettings(session.teamNumber, session.program).resolvedEventKey()
                    val eventKeyLower = eventKey.lowercase().trim()
                    // Serve what's cached; syncing happens on settings change and via SyncScheduler.
                    call.respond(IntegrationService.listTeams(eventKeyLower, session))
                }
                post {
                    val session = call.requireAdmin()
                    val request = call.receive<TeamRecord>()
                    val saved = IntegrationService.saveTeam(request, session.program)
                    call.respond(saved)
                }
                delete {
                    call.requireAdmin()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val teamKey = call.request.queryParameters["teamKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing teamKey parameter")
                    val success = IntegrationService.deleteTeam(eventKey, teamKey)
                    call.respond(mapOf("success" to success))
                }
            }

            route("/matches") {
                get {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: AllianceService.getEffectiveSettings(session.teamNumber, session.program).resolvedEventKey()
                    val eventKeyLower = eventKey.lowercase().trim()
                    // Serve what's cached; syncing happens on settings change and via SyncScheduler.
                    call.respond(IntegrationService.listMatches(eventKeyLower, session.program))
                }
                get("/predict") {
                    val session = call.requireSession()
                    val matchKey = call.request.queryParameters["matchKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing matchKey parameter")
                    val eventKey = call.request.queryParameters["eventKey"]
                    val forcePrescout = call.request.queryParameters["usePrescout"]?.toBoolean() ?: false
                    val prediction = PredictorService.predict(session, matchKey, forcePrescout, eventKey)
                    call.respond(prediction)
                }
                get("/predict-all") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val forcePrescout = call.request.queryParameters["usePrescout"]?.toBoolean() ?: false
                    val predictions = PredictorService.predictAll(session, eventKey, forcePrescout)
                    call.respond(predictions)
                }
                get("/projected-rankings") {
                    val session = call.requireSession()
                    if (!session.publicView && !SettingsService.canAccessPage(session.teamNumber, session.program, session.role, "projected-rankings", session.userId)) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Your role doesn't have access to Projected Rankings")
                    }
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val simulations = call.request.queryParameters["simulations"]?.toIntOrNull()
                        ?: com.obsidianscout.analytics.RankingProjectionService.DEFAULT_SIMULATIONS
                    val seed = call.request.queryParameters["seed"]?.toLongOrNull()
                    call.respond(com.obsidianscout.analytics.RankingProjectionService.project(session, eventKey, simulations, seed))
                }
                post {
                    val session = call.requireAdmin()
                    val request = call.receive<MatchRecord>()
                    val saved = IntegrationService.saveMatch(request, session.program)
                    call.respond(saved)
                }
                delete {
                    call.requireAdmin()
                    val matchKey = call.request.queryParameters["matchKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing matchKey parameter")
                    val success = IntegrationService.deleteMatch(matchKey)
                    call.respond(mapOf("success" to success))
                }
            }

            route("/integrations") {
                get("/sync/status") {
                    val session = call.requireSession()
                    val status = SyncScheduler.statusForDisplay(session.teamNumber, session.program)
                    call.respond(
                        SyncStatusResponse(
                            intervalMinutes = SyncScheduler.INTERVAL_MS / 60_000.0,
                            lastSyncAt = status.lastSyncAt?.toString(),
                            lastSyncSummary = status.lastSyncSummary,
                            lastSyncError = status.lastSyncError,
                            lastSyncTeams = status.lastSyncTeams,
                            lastSyncMatches = status.lastSyncMatches,
                            lastSyncTeamCount = status.lastSyncTeamCount,
                            lastSyncFailedTeams = status.lastSyncFailedTeams,
                            syncInProgress = status.syncInProgress,
                            currentSyncLabel = status.currentSyncLabel
                        )
                    )
                }
                post("/sync/events") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val queued = SyncScheduler.enqueueEventSync(session.teamNumber, settings)
                    if (queued) {
                        call.respond(HttpStatusCode.Accepted, SyncResponse(0, SyncScheduler.getSourcesLabel(settings), settings.resolvedEventKey(), queued, "Event sync started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sync is already running for team ${session.teamNumber}"))
                    }
                }
                post("/sync/event") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val queued = SyncScheduler.enqueueEventDataSync(session.teamNumber, settings)
                    if (queued) {
                        call.respond(HttpStatusCode.Accepted, SyncResponse(0, SyncScheduler.getSourcesLabel(settings), settings.resolvedEventKey(), queued, "Teams and matches sync started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sync is already running for team ${session.teamNumber}"))
                    }
                }
                post("/sync/stats") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val queued = SyncScheduler.enqueueStatsSync(session.teamNumber, settings)
                    if (queued) {
                        call.respond(HttpStatusCode.Accepted, SyncResponse(0, "stats", settings.resolvedEventKey(), queued, "Stats sync started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sync is already running for team ${session.teamNumber}"))
                    }
                }
                post("/sync/all") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val queued = SyncScheduler.enqueueFullSync(session.teamNumber, settings)
                    if (queued) {
                        call.respond(HttpStatusCode.Accepted, SyncResponse(0, "all", settings.resolvedEventKey(), queued, "Full sync started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sync is already running for team ${session.teamNumber}"))
                    }
                }
            }
}