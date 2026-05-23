package com.obsidianscout.routes

import com.obsidianscout.analytics.AnalyticsService
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.requireAdmin
import com.obsidianscout.auth.requireAnalyticsOrAbove
import com.obsidianscout.auth.requireSession
import com.obsidianscout.config.ConfigService
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.integrations.SyncScheduler
import com.obsidianscout.scouting.PitScoutingService
import com.obsidianscout.scouting.ScoutingService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

fun Application.configureRoutes() {
    routing {
        route("/api") {
            route("/auth") {
                post("/login") {
                    val request = call.receive<LoginRequest>()
                    val user = AuthService.login(
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password
                    ) ?: throw com.obsidianscout.auth.ApiException(
                        HttpStatusCode.Unauthorized,
                        "Invalid credentials"
                    )

                    val session = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role
                    )
                    call.sessions.set(session)
                    call.respond(LoginResponse(session))
                }
                post("/register") {
                    val request = call.receive<RegisterRequest>()
                    val user = AuthService.register(
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password,
                        role = request.role
                    )
                    val session = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role
                    )
                    call.sessions.set(session)
                    call.respond(LoginResponse(session))
                }
                post("/logout") {
                    call.sessions.clear<UserSession>()
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/me") {
                    val session = call.requireSession()
                    call.respond(MeResponse(session))
                }
                get("/providers") {
                    val providers = listOf(
                        AuthProviderInfo("local", true),
                        AuthProviderInfo("oauth", false)
                    )
                    call.respond(AuthProvidersResponse(providers))
                }
            }

            route("/config") {
                get {
                    val session = call.requireSession()
                    call.respond(ConfigService.getConfig(session.teamNumber))
                }
                put {
                    val session = call.requireAdmin()
                    val request = call.receive<ConfigUpdateRequest>()
                    val updated = ConfigService.updateConfig(session.teamNumber, request.configJson)
                    call.respond(updated)
                }
            }

            route("/pit-config") {
                get {
                    val session = call.requireSession()
                    call.respond(ConfigService.getPitConfig(session.teamNumber))
                }
                put {
                    val session = call.requireAdmin()
                    val request = call.receive<ConfigUpdateRequest>()
                    val updated = ConfigService.updatePitConfig(session.teamNumber, request.configJson)
                    call.respond(updated)
                }
            }

            route("/settings") {
                get {
                    val session = call.requireSession()
                    val settings = SettingsService.getSettings(session.teamNumber)
                    call.respond(SettingsResponse(settings.toPayload()))
                }
                put {
                    val session = call.requireAdmin()
                    val payload = call.receive<ApiSettingsPayload>()
                    val updated = SettingsService.updateSettings(session.teamNumber, payload.toSettings())
                    call.respond(SettingsResponse(updated.toPayload()))
                }
            }

            route("/scouting") {
                get {
                    val session = call.requireSession()
                    call.respond(ScoutingService.listEntries(session))
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getConfig(session.teamNumber)
                    val entry = ScoutingService.createEntry(session, request, config)
                    call.respond(entry)
                }
            }

            route("/pit-scouting") {
                get {
                    val session = call.requireSession()
                    call.respond(PitScoutingService.listEntries(session))
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getPitConfig(session.teamNumber)
                    val entry = PitScoutingService.createEntry(session, request, config)
                    call.respond(entry)
                }
            }

            route("/analytics") {
                get {
                    val session = call.requireAnalyticsOrAbove()
                    val config = ConfigService.getConfig(session.teamNumber)
                    val entries = ScoutingService.listEntries(session)
                    val response = AnalyticsService.generate(config, entries)
                    call.respond(response)
                }
            }

            route("/events") {
                get {
                    val session = call.requireSession()
                    val year = call.request.queryParameters["year"]?.toIntOrNull()
                    val cachedOnly = call.request.queryParameters["cached"]?.let { value ->
                        value == "1" || value.equals("true", ignoreCase = true)
                    } ?: false

                    val settings = SettingsService.getSettings(session.teamNumber)
                    val activeKey = settings.resolvedEventKey()

                    val events = IntegrationService.listEvents(year, cachedOnly, activeKey, settings)
                    call.respond(events)
                }
                post {
                    call.requireAdmin()
                    val request = call.receive<EventRecord>()
                    val saved = IntegrationService.saveEvent(request)
                    call.respond(saved)
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
                        ?: SettingsService.getSettings(session.teamNumber).resolvedEventKey()
                    call.respond(IntegrationService.listTeams(eventKey))
                }
                post {
                    call.requireAdmin()
                    val request = call.receive<TeamRecord>()
                    val saved = IntegrationService.saveTeam(request)
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
                        ?: SettingsService.getSettings(session.teamNumber).resolvedEventKey()
                    call.respond(IntegrationService.listMatches(eventKey))
                }
                post {
                    call.requireAdmin()
                    val request = call.receive<MatchRecord>()
                    val saved = IntegrationService.saveMatch(request)
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

            route("/summary") {
                get {
                    call.requireSession()
                    call.respond(IntegrationService.summary())
                }
            }

            route("/integrations") {
                get("/sync/status") {
                    call.requireSession()
                    call.respond(
                        SyncStatusResponse(
                            intervalMinutes = SyncScheduler.INTERVAL_MS / 60_000.0,
                            lastSyncAt = SyncScheduler.lastSyncAt?.toString(),
                            lastSyncSummary = SyncScheduler.lastSyncSummary,
                            lastSyncError = SyncScheduler.lastSyncError
                        )
                    )
                }
                post("/sync/events") {
                    val session = call.requireAdmin()
                    val settings = SettingsService.getSettings(session.teamNumber)
                    val count = IntegrationService.syncEvents(settings)
                    call.respond(SyncResponse(count, settings.preferredSource, settings.resolvedEventKey()))
                }
                post("/sync/event") {
                    val session = call.requireAdmin()
                    val settings = SettingsService.getSettings(session.teamNumber)
                    val counts = IntegrationService.syncEventData(settings)
                    SyncScheduler.lastSyncAt = java.time.Instant.now()
                    SyncScheduler.lastSyncSummary =
                        "Manual sync: ${counts.teams} teams, ${counts.matches} matches"
                    SyncScheduler.lastSyncError = null
                    call.respond(SyncResponse(counts.teams + counts.matches, settings.preferredSource, settings.resolvedEventKey()))
                }
                post("/sync/stats") {
                    val session = call.requireAdmin()
                    val settings = SettingsService.getSettings(session.teamNumber)
                    val count = IntegrationService.syncStats(settings)
                    call.respond(SyncResponse(count, "stats", settings.resolvedEventKey()))
                }
            }

            route("/admin") {
                get("/users") {
                    val session = call.requireAdmin()
                    call.respond(AuthService.listUsers(session))
                }
                post("/users") {
                    val session = call.requireAdmin()
                    val request = call.receive<CreateUserRequest>()
                    val user = AuthService.createUser(
                        callerSession = session,
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password,
                        role = request.role
                    )
                    call.respond(user)
                }
            }
        }

        val pages = mapOf(
            "index" to "index.html",
            "dashboard" to "dashboard.html",
            "scout" to "scout.html",
            "pit-scout" to "pit-scout.html",
            "pit-data" to "pit-data.html",
            "analytics" to "analytics.html",
            "graphs" to "graphs.html",
            "events" to "events.html",
            "teams" to "teams.html",
            "matches" to "matches.html",
            "users" to "users.html",
            "config" to "config.html"
        )

        pages.forEach { (path, fileName) ->
            get("/$path") {
                call.respondStaticHtml(fileName)
            }
            get("/$fileName") {
                val target = if (path == "index") "/" else "/$path"
                call.respondRedirect(target, permanent = true)
            }
        }

        staticResources("/", "static") {
            default("index.html")
        }
    }
}

private suspend fun ApplicationCall.respondStaticHtml(fileName: String) {
    val resource = Thread.currentThread().contextClassLoader.getResource("static/$fileName")
    if (resource == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    respondText(resource.readText(), ContentType.Text.Html)
}

private fun ApiSettings.toPayload(): ApiSettingsPayload {
    return ApiSettingsPayload(
        year = year,
        eventCode = eventCode,
        eventKey = eventKey,
        timezone = timezone,
        preferredSource = preferredSource,
        useStatboticsEpa = useStatboticsEpa,
        useTbaOpr = useTbaOpr,
        apiKeys = ApiKeysPayload(
            tbaKey = apiKeys.tbaKey,
            firstUsername = apiKeys.firstUsername,
            firstKey = apiKeys.firstKey,
            statboticsKey = apiKeys.statboticsKey
        )
    )
}

private fun ApiSettingsPayload.toSettings(): ApiSettings {
    return ApiSettings(
        year = year,
        eventCode = eventCode,
        timezone = timezone,
        preferredSource = preferredSource,
        useStatboticsEpa = useStatboticsEpa,
        useTbaOpr = useTbaOpr,
        apiKeys = com.obsidianscout.integrations.ApiKeys(
            tbaKey = apiKeys.tbaKey,
            firstUsername = apiKeys.firstUsername,
            firstKey = apiKeys.firstKey,
            statboticsKey = apiKeys.statboticsKey
        )
    )
}
