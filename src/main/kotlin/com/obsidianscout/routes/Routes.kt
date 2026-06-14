package com.obsidianscout.routes

import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.analytics.AnalyticsService
import com.obsidianscout.analytics.PredictorService
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.requireAdmin
import com.obsidianscout.auth.requireAnalyticsOrAbove
import com.obsidianscout.auth.requireSession
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.SyncScheduler
import com.obsidianscout.scouting.PitScoutingService
import com.obsidianscout.scouting.QualitativeScoutingService
import com.obsidianscout.scouting.ScoutingService
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
import io.ktor.server.sessions.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import com.obsidianscout.scouting.AllianceCollaborationManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.and
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.ScoutingAlliances

fun Application.configureRoutes() {
    routing {
        route("/api") {
            intercept(ApplicationCallPipeline.Call) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    proceed()
                }
            }
            intercept(ApplicationCallPipeline.Plugins) {
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                call.response.headers.append(HttpHeaders.Pragma, "no-cache")
                call.response.headers.append(HttpHeaders.Expires, "0")
            }
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
                        role = user.role,
                        email = user.email,
                        profilePicture = null
                    )
                    call.attributes.put(com.obsidianscout.auth.KeepMeLoggedInSessionTransport.KEEP_ME_LOGGED_IN_KEY, request.keepMeLoggedIn)
                    call.sessions.set(session)

                    val responseSession = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture
                    )
                    call.respond(LoginResponse(responseSession))
                }
                post("/register") {
                    val request = call.receive<RegisterRequest>()
                    val user = AuthService.register(
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password,
                        role = request.role,
                        email = request.email
                    )
                    val session = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role,
                        email = user.email,
                        profilePicture = null
                    )
                    call.attributes.put(com.obsidianscout.auth.KeepMeLoggedInSessionTransport.KEEP_ME_LOGGED_IN_KEY, request.keepMeLoggedIn)
                    call.sessions.set(session)

                    val responseSession = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture
                    )
                    call.respond(LoginResponse(responseSession))
                }
                post("/logout") {
                    call.sessions.clear<UserSession>()
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/me") {
                    val session = call.requireSession()
                    val user = AuthService.getUserById(session.userId)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "User not found")
                    val responseSession = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture
                    )
                    call.respond(MeResponse(responseSession))
                }
                get("/status") {
                    val session = call.sessions.get<UserSession>()
                    call.respond(LoginStatusResponse(session != null))
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
                    val local = call.request.queryParameters["local"]?.toBoolean() ?: false
                    // Return JSON where any string `label` values are wrapped into { "en": "..." }
                    val raw = ConfigService.getConfigJson(session.teamNumber, local)
                    val elem = JsonSupport.json.parseToJsonElement(raw)
                    val obj = elem as? JsonObject
                    if (obj != null) {
                        val fields = obj["fields"]
                        if (fields is kotlinx.serialization.json.JsonArray) {
                            val transformed = fields.map { f ->
                                val fo = f as? JsonObject ?: return@map f
                                val label = fo["label"]
                                if (label is JsonPrimitive && label.isString) {
                                    val newField = buildJsonObject {
                                        fo.entries.forEach { (k, v) ->
                                            if (k == "label") {
                                                put(k, JsonObject(mapOf("en" to JsonPrimitive(v.toString().trim('"')))))
                                            } else {
                                                put(k, v)
                                            }
                                        }
                                    }
                                    return@map newField
                                }
                                f
                            }
                            val out = buildJsonObject {
                                obj.entries.forEach { (k, v) ->
                                    if (k == "fields") {
                                        put(k, kotlinx.serialization.json.JsonArray(transformed))
                                    } else {
                                        put(k, v)
                                    }
                                }
                            }
                            call.respondText(JsonSupport.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), out), ContentType.Application.Json)
                            return@get
                        }
                    }
                    // Fallback: respond with raw config JSON
                    call.respondText(raw, ContentType.Application.Json)
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
                    val local = call.request.queryParameters["local"]?.toBoolean() ?: false
                    val raw = ConfigService.getPitConfigJson(session.teamNumber, local)
                    val elem = JsonSupport.json.parseToJsonElement(raw)
                    val obj = elem as? JsonObject
                    if (obj != null) {
                        val fields = obj["fields"]
                        if (fields is kotlinx.serialization.json.JsonArray) {
                            val transformed = fields.map { f ->
                                val fo = f as? JsonObject ?: return@map f
                                val label = fo["label"]
                                if (label is JsonPrimitive && label.isString) {
                                    val newField = buildJsonObject {
                                        fo.entries.forEach { (k, v) ->
                                            if (k == "label") {
                                                put(k, JsonObject(mapOf("en" to JsonPrimitive(v.toString().trim('"')))))
                                            } else {
                                                put(k, v)
                                            }
                                        }
                                    }
                                    return@map newField
                                }
                                f
                            }
                            val out = buildJsonObject {
                                obj.entries.forEach { (k, v) ->
                                    if (k == "fields") {
                                        put(k, kotlinx.serialization.json.JsonArray(transformed))
                                    } else {
                                        put(k, v)
                                    }
                                }
                            }
                            call.respondText(JsonSupport.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), out), ContentType.Application.Json)
                            return@get
                        }
                    }
                    call.respondText(raw, ContentType.Application.Json)
                }
                put {
                    val session = call.requireAdmin()
                    val request = call.receive<ConfigUpdateRequest>()
                    val updated = ConfigService.updatePitConfig(session.teamNumber, request.configJson)
                    call.respond(updated)
                }
            }

            route("/qual-config") {
                get {
                    val session = call.requireSession()
                    val local = call.request.queryParameters["local"]?.toBoolean() ?: false
                    val raw = ConfigService.getQualitativeConfigJson(session.teamNumber, local)
                    val elem = JsonSupport.json.parseToJsonElement(raw)
                    val obj = elem as? JsonObject
                    if (obj != null) {
                        val fields = obj["fields"]
                        if (fields is kotlinx.serialization.json.JsonArray) {
                            val transformed = fields.map { f ->
                                val fo = f as? JsonObject ?: return@map f
                                val label = fo["label"]
                                if (label is JsonPrimitive && label.isString) {
                                    val newField = buildJsonObject {
                                        fo.entries.forEach { (k, v) ->
                                            if (k == "label") {
                                                put(k, JsonObject(mapOf("en" to JsonPrimitive(v.toString().trim('"')))))
                                            } else {
                                                put(k, v)
                                            }
                                        }
                                    }
                                    return@map newField
                                }
                                f
                            }
                            val out = buildJsonObject {
                                obj.entries.forEach { (k, v) ->
                                    if (k == "fields") {
                                        put(k, kotlinx.serialization.json.JsonArray(transformed))
                                    } else {
                                        put(k, v)
                                    }
                                }
                            }
                            call.respondText(JsonSupport.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), out), ContentType.Application.Json)
                            return@get
                        }
                    }
                    call.respondText(raw, ContentType.Application.Json)
                }
                put {
                    val session = call.requireAdmin()
                    val request = call.receive<ConfigUpdateRequest>()
                    val updated = ConfigService.updateQualitativeConfig(session.teamNumber, request.configJson)
                    call.respond(updated)
                }
            }

            route("/settings") {
                get {
                    val session = call.requireSession()
                    val local = call.request.queryParameters["local"]?.toBoolean() ?: false
                    val settings = if (local) {
                        SettingsService.getSettings(session.teamNumber)
                    } else {
                        AllianceService.getEffectiveSettings(session.teamNumber)
                    }
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
                    val includePrescout = call.request.queryParameters["includePrescout"]?.toBoolean() ?: false
                    val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                    call.respond(ScoutingService.listEntries(session, includePrescout, all))
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getConfig(session.teamNumber)
                    val entry = ScoutingService.createEntry(session, request, config)
                    call.respond(entry)
                }
                put("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getConfig(session.teamNumber)
                    val entry = ScoutingService.updateEntry(session, id, request, config)
                    call.respond(entry)
                }
                delete("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    ScoutingService.deleteEntry(session, id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/pit-scouting") {
                get {
                    val session = call.requireSession()
                    val includePrescout = call.request.queryParameters["includePrescout"]?.toBoolean() ?: false
                    val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                    call.respond(PitScoutingService.listEntries(session, includePrescout, all))
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getPitConfig(session.teamNumber)
                    val entry = PitScoutingService.createEntry(session, request, config)
                    call.respond(entry)
                }
                put("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getPitConfig(session.teamNumber)
                    val entry = PitScoutingService.updateEntry(session, id, request, config)
                    call.respond(entry)
                }
                delete("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    PitScoutingService.deleteEntry(session, id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/qual-scouting") {
                get {
                    val session = call.requireSession()
                    val includePrescout = call.request.queryParameters["includePrescout"]?.toBoolean() ?: false
                    val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                    call.respond(QualitativeScoutingService.listEntries(session, includePrescout, all))
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getQualitativeConfig(session.teamNumber)
                    val entry = QualitativeScoutingService.createEntry(session, request, config)
                    call.respond(entry)
                }
                put("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getQualitativeConfig(session.teamNumber)
                    val entry = QualitativeScoutingService.updateEntry(session, id, request, config)
                    call.respond(entry)
                }
                delete("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    QualitativeScoutingService.deleteEntry(session, id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/prescout") {
                route("/scouting") {
                    get {
                        val session = call.requireSession()
                        val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                        call.respond(ScoutingService.listPrescoutEntries(session, all))
                    }
                    post {
                        val session = call.requireSession()
                        val request = call.receive<ScoutingEntryRequest>()
                        val config = ConfigService.getConfig(session.teamNumber)
                        val entry = ScoutingService.createEntry(session, request, config, isPrescout = true)
                        call.respond(entry)
                    }
                }
                route("/pit-scouting") {
                    get {
                        val session = call.requireSession()
                        val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                        call.respond(PitScoutingService.listPrescoutEntries(session, all))
                    }
                    post {
                        val session = call.requireSession()
                        val request = call.receive<ScoutingEntryRequest>()
                        val config = ConfigService.getPitConfig(session.teamNumber)
                        val entry = PitScoutingService.createEntry(session, request, config, isPrescout = true)
                        call.respond(entry)
                    }
                }
                route("/qual-scouting") {
                    get {
                        val session = call.requireSession()
                        val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                        call.respond(QualitativeScoutingService.listPrescoutEntries(session, all))
                    }
                    post {
                        val session = call.requireSession()
                        val request = call.receive<ScoutingEntryRequest>()
                        val config = ConfigService.getQualitativeConfig(session.teamNumber)
                        val entry = QualitativeScoutingService.createEntry(session, request, config, isPrescout = true)
                        call.respond(entry)
                    }
                }
                post("/sync-event") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber)
                    val (cachedTeams, cachedMatches) = transaction {
                        val teamCount = com.obsidianscout.db.ApiTeams.select { com.obsidianscout.db.ApiTeams.eventKey eq eventKey }.count().toInt()
                        val matchCount = com.obsidianscout.db.ApiMatches.select { com.obsidianscout.db.ApiMatches.eventKey eq eventKey }.count().toInt()
                        Pair(teamCount, matchCount)
                    }
                    com.obsidianscout.integrations.SyncScheduler.enqueueCustomEventDataSync(settings, eventKey)
                    call.respond(com.obsidianscout.integrations.SyncCounts(cachedTeams, cachedMatches))
                }
            }

            route("/analytics") {
                get {
                    val session = call.requireAnalyticsOrAbove()
                    val config = ConfigService.getConfig(session.teamNumber)
                    val forcePrescout = call.request.queryParameters["usePrescout"]?.toBoolean() ?: false
                    
                    val regularEntries = ScoutingService.listEntries(session, includePrescout = false)
                    val prescoutEntries = ScoutingService.listEntries(session, includePrescout = true).filter { it.isPrescout }
                    
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber)
                    val currentEventKey = settings.resolvedEventKey()
                    
                    val mergedEntries = AnalyticsService.mergePrescoutEntries(
                        regularEntries,
                        prescoutEntries,
                        currentEventKey,
                        forcePrescout
                    )
                    
                    val response = AnalyticsService.generate(config, mergedEntries)
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

                    val settings = AllianceService.getEffectiveSettings(session.teamNumber)
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
                        ?: AllianceService.getEffectiveSettings(session.teamNumber).resolvedEventKey()
                    call.respond(IntegrationService.listTeams(eventKey, session))
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
                        ?: AllianceService.getEffectiveSettings(session.teamNumber).resolvedEventKey()
                    call.respond(IntegrationService.listMatches(eventKey))
                }
                get("/predict") {
                    val session = call.requireSession()
                    val matchKey = call.request.queryParameters["matchKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing matchKey parameter")
                    val forcePrescout = call.request.queryParameters["usePrescout"]?.toBoolean() ?: false
                    val prediction = PredictorService.predict(session, matchKey, forcePrescout)
                    call.respond(prediction)
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
                            lastSyncError = SyncScheduler.lastSyncError,
                            lastSyncTeams = SyncScheduler.lastSyncTeams,
                            lastSyncMatches = SyncScheduler.lastSyncMatches,
                            lastSyncTeamCount = SyncScheduler.lastSyncTeamCount,
                            lastSyncFailedTeams = SyncScheduler.lastSyncFailedTeams,
                            syncInProgress = SyncScheduler.syncInProgress,
                            currentSyncLabel = SyncScheduler.currentSyncLabel
                        )
                    )
                }
                post("/sync/events") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber)
                    val queued = SyncScheduler.enqueueEventSync(settings)
                    val status = if (queued) HttpStatusCode.Accepted else HttpStatusCode.Conflict
                    val message = if (queued) "Event sync started" else "Another sync is already running"
                    call.respond(status, SyncResponse(0, settings.preferredSource, settings.resolvedEventKey(), queued, message))
                }
                post("/sync/event") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber)
                    val queued = SyncScheduler.enqueueEventDataSync(settings)
                    val status = if (queued) HttpStatusCode.Accepted else HttpStatusCode.Conflict
                    val message = if (queued) "Teams and matches sync started" else "Another sync is already running"
                    call.respond(status, SyncResponse(0, settings.preferredSource, settings.resolvedEventKey(), queued, message))
                }
                post("/sync/stats") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber)
                    val queued = SyncScheduler.enqueueStatsSync(settings)
                    val status = if (queued) HttpStatusCode.Accepted else HttpStatusCode.Conflict
                    val message = if (queued) "Stats sync started" else "Another sync is already running"
                    call.respond(status, SyncResponse(0, "stats", settings.resolvedEventKey(), queued, message))
                }
            }

            route("/admin") {
                get("/users") {
                    val session = call.requireAdmin()
                    val q = call.request.queryParameters["q"]
                    val teamNumber = call.request.queryParameters["teamNumber"]?.toIntOrNull()
                    val roleStr = call.request.queryParameters["role"]
                    val role = roleStr?.takeIf { it.isNotBlank() }?.let {
                        runCatching { UserRole.valueOf(it) }.getOrNull()
                    }
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L

                    call.respond(
                        AuthService.listUsers(
                            callerSession = session,
                            search = q,
                            teamFilter = teamNumber,
                            roleFilter = role,
                            limit = limit,
                            offset = offset
                        )
                    )
                }
                post("/users") {
                    val session = call.requireAdmin()
                    val request = call.receive<CreateUserRequest>()
                    val user = AuthService.createUser(
                        callerSession = session,
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password,
                        role = request.role,
                        email = request.email
                    )
                    call.respond(user)
                }
                put("/users/{id}") {
                    val session = call.requireAdmin()
                    val userId = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid user id")
                    val request = call.receive<UpdateUserRequest>()
                    val updated = AuthService.updateUser(
                        callerSession = session,
                        targetUserId = userId,
                        newUsername = request.username,
                        newPassword = request.password,
                        newRole = request.role,
                        newEmail = request.email,
                        newProfilePicture = request.profilePicture,
                        clearProfilePicture = request.clearProfilePicture
                    )
                    call.respond(updated)
                }
            } // end /admin route

            // Self-service profile picture endpoint (any authenticated user)
            put("/user/profile-picture") {
                val session = call.requireSession()
                val request = call.receive<UpdateUserRequest>()
                val updated = AuthService.updateUser(
                    callerSession = session,
                    targetUserId = session.userId,
                    newUsername = null,
                    newPassword = null,
                    newRole = null,
                    newEmail = null,
                    newProfilePicture = request.profilePicture,
                    clearProfilePicture = request.clearProfilePicture
                )
                // Refresh the session so /api/auth/me returns the updated picture
                val updatedSession = session.copy(profilePicture = null)
                call.sessions.set(updatedSession)
                call.respond(updated)
            }

            route("/alliances") {

                get {
                    val session = call.requireSession()
                    call.respond(AllianceService.listAlliances(session))
                }
                post {
                    val session = call.requireAdmin()
                    val req = call.receive<CreateAllianceRequest>()
                    call.respond(AllianceService.createAlliance(session, req.name, req.eventKey, req.notes, req.year, req.eventCode))
                }
                get("/invites") {
                    val session = call.requireSession()
                    call.respond(AllianceService.listInvites(session))
                }
                get("/invites/count") {
                    val session = call.requireSession()
                    call.respond(InviteCountResponse(AllianceService.getInviteCount(session.teamNumber)))
                }
                get("/import-sources") {
                    val session = call.requireAdmin()
                    call.respond(AllianceService.listImportSources(session))
                }
                put("/{id}") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<UpdateAllianceRequest>()
                    call.respond(AllianceService.updateAlliance(session, id, req.name, req.eventKey, req.notes, req.year, req.eventCode))
                }
                delete("/{id}") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    AllianceService.deleteAlliance(session, id)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/invite") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<InviteTeamRequest>()
                    AllianceService.inviteTeam(session, id, req.partnerTeamNumber)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/import") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<AllianceImportDataRequest>()
                    val result = AllianceService.importAllianceData(
                        session = session,
                        allianceId = id,
                        sourceTeamNumber = req.sourceTeamNumber,
                        eventKey = req.eventKey,
                        includeMatchScouting = req.includeMatchScouting,
                        includePitScouting = req.includePitScouting,
                        includeQualitativeScouting = req.includeQualitativeScouting
                    )
                    call.respond(
                        AllianceImportDataResponse(
                            importedMatchScouting = result.importedMatchScouting,
                            importedPitScouting = result.importedPitScouting,
                            importedQualitativeScouting = result.importedQualitativeScouting,
                            sourceTeamNumber = result.sourceTeamNumber,
                            eventKey = result.eventKey,
                            skippedDuplicates = result.skippedDuplicates
                        )
                    )
                }
                post("/{id}/respond") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<RespondInviteRequest>()
                    AllianceService.respondToInvite(session, id, req.accept)
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/{id}/members/{teamNumber}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val targetTeam = call.parameters["teamNumber"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid team number")
                    AllianceService.removeMember(session, id, targetTeam)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    call.respond(AllianceService.getAlliance(session, id))
                }
                get("/{id}/config/{kind}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val kind = call.parameters["kind"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid kind")
                    
                    // Verify membership (throws if not member)
                    AllianceService.getAlliance(session, id)
                    
                    val configJson = transaction {
                        val row = ScoutingAlliances.select { ScoutingAlliances.id eq id }.firstOrNull()
                        if (row != null) {
                            when (kind) {
                                "game", "match" -> row[ScoutingAlliances.matchConfigJson]
                                "pit" -> row[ScoutingAlliances.pitConfigJson]
                                "qual" -> row[ScoutingAlliances.qualitativeConfigJson]
                                else -> null
                            }
                        } else null
                    } ?: "{}"
                    call.respondText(configJson, ContentType.Application.Json)
                }
                post("/{id}/toggle-active") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<ToggleAllianceActiveRequest>()
                    AllianceService.toggleActiveMembership(session, id, req.active)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/toggle-disable") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<ToggleAllianceDisableRequest>()
                    AllianceService.toggleActiveMembership(session, id, !req.disabled)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/members/{teamNumber}/promote") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val targetTeam = call.parameters["teamNumber"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid team number")
                    AllianceService.promoteMember(session, id, targetTeam)
                    call.respond(HttpStatusCode.NoContent)
                }
                webSocket("/{id}/collaborate/{kind}") {
                    val session = call.sessions.get<UserSession>() ?: return@webSocket close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session")
                    )
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@webSocket close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid alliance ID")
                    )
                    val kind = call.parameters["kind"] ?: return@webSocket close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid config kind")
                    )
                    
                    // Verify user is a member of this alliance
                    val isMember = transaction {
                        AllianceMemberships
                            .select {
                                (AllianceMemberships.allianceId eq id) and
                                (AllianceMemberships.teamNumber eq session.teamNumber) and
                                (AllianceMemberships.status inList listOf("ADMIN", "ACCEPTED"))
                            }
                            .any()
                    }
                    if (!isMember) {
                        return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not a member"))
                    }
                    
                    AllianceCollaborationManager.handleConnection(this, id, kind, session)
                }
            }
        }

        val pages = mapOf(
            "index" to "index.html",
            "dashboard" to "dashboard.html",
            "scout" to "scout.html",
            "pit-scout" to "pit-scout.html",
            "qual-scout" to "qual-scout.html",
            "prescout-scout" to "prescout-scout.html",
            "prescout-pit" to "prescout-pit.html",
            "prescout-qual" to "prescout-qual.html",
            "prescout" to "prescout.html",
            "qual-data" to "qual-data.html",
            "pit-data" to "pit-data.html",
            "all-data" to "all-data.html",
            "analytics" to "analytics.html",
            "graphs" to "graphs.html",
            "events" to "events.html",
            "teams" to "teams.html",
            "team" to "team.html",
            "matches" to "matches.html",
            "predictor" to "predictor.html",
            "alliances" to "alliances.html",
            "alliance-edit" to "alliance-edit.html",
            "users" to "users.html",
            "config" to "config.html",
            "qr-scanner" to "qr-scanner.html",
            "cache-manager" to "cache-manager.html"
        )

        pages.forEach { (path, fileName) ->
            get("/$path") {
                call.respondStaticHtml(fileName)
            }
            get("/$fileName") {
                val target = if (path == "index") "/" else "/$path"
                val query = call.request.queryParameters
                val queryStr = if (query.isEmpty()) "" else "?" + query.entries().flatMap { (k, v) -> v.map { "$k=$it" } }.joinToString("&")
                call.respondRedirect(target + queryStr, permanent = true)
            }
        }

        staticResources("/", "static") {
            default("index.html")
        }
    }
}

private suspend fun ApplicationCall.respondStaticHtml(fileName: String) {
    val (html, sidebar) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resource = Thread.currentThread().contextClassLoader.getResource("static/$fileName")
        val htmlContent = resource?.readText()
        val sidebarContent = Thread.currentThread().contextClassLoader
            .getResource("static/base.html")
            ?.readText()
            ?.trim()
        htmlContent to sidebarContent
    }
    if (html == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    val rendered = if (sidebar.isNullOrBlank()) {
        html
    } else {
        html.replace(
            Regex("""<aside class="sidebar">.*?</aside>""", setOf(RegexOption.DOT_MATCHES_ALL)),
            sidebar
        )
    }
    respondText(rendered, ContentType.Text.Html)
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
            firstKey = apiKeys.firstKey
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
            firstKey = apiKeys.firstKey
        )
    )
}
