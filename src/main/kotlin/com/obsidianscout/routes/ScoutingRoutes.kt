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

private data class FieldImageResult(val webPath: String, val fileName: String, val lastModified: Long)

private fun findFieldImage(year: Int): FieldImageResult? {
    val supportedExts = setOf("png", "jpg", "jpeg", "webp")
    val candidateDirs = listOf(
        File("static/assets/images/field-images/$year"),
        File("Obsidianscout/src/main/resources/static/assets/images/field-images/$year"),
        File("src/main/resources/static/assets/images/field-images/$year"),
        File("assets/images/field-images/$year")
    )
    for (dir in candidateDirs) {
        if (dir.exists() && dir.isDirectory) {
            val file = dir.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in supportedExts }
            if (file != null) {
                return FieldImageResult("/assets/images/field-images/$year/${file.name}", file.name, file.lastModified())
            }
        }
    }
    try {
        val resource = Thread.currentThread().contextClassLoader.getResource("static/assets/images/field-images/$year")
        if (resource != null) {
            val uri = resource.toURI()
            if (uri.scheme == "file") {
                val file = File(uri).listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in supportedExts }
                if (file != null) {
                    return FieldImageResult("/assets/images/field-images/$year/${file.name}", file.name, file.lastModified())
                }
            }
        }
    } catch (e: Exception) {
        // ignore
    }
    return null
}

suspend fun ApplicationCall.optionalSessionOrMobileJwt(jwtSecret: String): UserSession? {
    if (sessions.get<UserSession>() != null) {
        return runCatching { requireSession() }.getOrNull()
    }
    val authHeader = request.headers["Authorization"]
    if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
        return runCatching { requireMobileSession(jwtSecret) }.getOrNull()
    }
    return null
}

suspend fun ApplicationCall.resolveSessionOrMobileJwt(jwtSecret: String): UserSession {
    return optionalSessionOrMobileJwt(jwtSecret) ?: throw ApiException(HttpStatusCode.Unauthorized, "Authentication required")
}

fun Route.scoutingRoutes() {
            route("/scouting") {
                get {
                    val session = call.requireSession()
                    val includePrescout = call.request.queryParameters["includePrescout"]?.toBoolean() ?: false
                    val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                    val eventKey = call.request.queryParameters["eventKey"]
                    call.respond(ScoutingService.listEntries(session, includePrescout, all, eventKey))
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getConfig(session.teamNumber, session.program)
                    val entry = ScoutingService.createEntry(session, request, config)
                    call.respond(entry)
                }
                put("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getConfig(session.teamNumber, session.program)
                    val entry = ScoutingService.updateEntry(session, id, request, config)
                    call.respond(entry)
                }
                delete("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
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
                    val eventKey = call.request.queryParameters["eventKey"]
                    call.respond(PitScoutingService.listEntries(session, includePrescout, all, eventKey))
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getPitConfig(session.teamNumber, session.program)
                    val entry = PitScoutingService.createEntry(session, request, config)
                    call.respond(entry)
                }
                put("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getPitConfig(session.teamNumber, session.program)
                    val entry = PitScoutingService.updateEntry(session, id, request, config)
                    call.respond(entry)
                }
                delete("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
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
                    val eventKey = call.request.queryParameters["eventKey"]
                    call.respond(QualitativeScoutingService.listEntries(session, includePrescout, all, eventKey))
                }
                post {
                    val session = call.requireSession()
                    val reqJson = call.receiveText()
                    val elem = JsonSupport.json.parseToJsonElement(reqJson)
                    val config = ConfigService.getQualitativeConfig(session.teamNumber, session.program)
                    if (elem is JsonObject && elem.containsKey("entries") && elem["entries"] is kotlinx.serialization.json.JsonArray) {
                        val entriesArr = elem["entries"]!!.jsonArray
                        val results = mutableListOf<QualitativeScoutingEntryRecord>()
                        entriesArr.forEach { item ->
                            val obj = item.jsonObject
                            val entryData = obj["data"]?.jsonObject ?: obj
                            results.add(QualitativeScoutingService.createEntry(session, ScoutingEntryRequest(entryData), config))
                        }
                        call.respond(results)
                    } else {
                        val req = JsonSupport.json.decodeFromJsonElement(ScoutingEntryRequest.serializer(), elem)
                        val entry = QualitativeScoutingService.createEntry(session, req, config)
                        call.respond(entry)
                    }
                }
                post("/batch") {
                    val session = call.requireSession()
                    val reqJson = call.receiveText()
                    val elem = JsonSupport.json.parseToJsonElement(reqJson)
                    val config = ConfigService.getQualitativeConfig(session.teamNumber, session.program)
                    val entriesArr = when (elem) {
                        is kotlinx.serialization.json.JsonArray -> elem
                        is JsonObject -> elem["entries"]?.jsonArray ?: (elem["data"]?.jsonArray ?: throw ApiException(HttpStatusCode.BadRequest, "Missing 'entries' array"))
                        else -> throw ApiException(HttpStatusCode.BadRequest, "Invalid batch format")
                    }
                    val results = mutableListOf<QualitativeScoutingEntryRecord>()
                    entriesArr.forEach { item ->
                        val obj = item.jsonObject
                        val entryData = obj["data"]?.jsonObject ?: obj
                        results.add(QualitativeScoutingService.createEntry(session, ScoutingEntryRequest(entryData), config))
                    }
                    call.respond(results)
                }
                put("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getQualitativeConfig(session.teamNumber, session.program)
                    val entry = QualitativeScoutingService.updateEntry(session, id, request, config)
                    call.respond(entry)
                }
                delete("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
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
                        val eventKey = call.request.queryParameters["eventKey"]
                        call.respond(ScoutingService.listPrescoutEntries(session, all, eventKey))
                    }
                    post {
                        val session = call.requireSession()
                        val request = call.receive<ScoutingEntryRequest>()
                        val config = ConfigService.getConfig(session.teamNumber, session.program)
                        val entry = ScoutingService.createEntry(session, request, config, isPrescout = true)
                        call.respond(entry)
                    }
                }
                route("/pit-scouting") {
                    get {
                        val session = call.requireSession()
                        val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                        val eventKey = call.request.queryParameters["eventKey"]
                        call.respond(PitScoutingService.listPrescoutEntries(session, all, eventKey))
                    }
                    post {
                        val session = call.requireSession()
                        val request = call.receive<ScoutingEntryRequest>()
                        val config = ConfigService.getPitConfig(session.teamNumber, session.program)
                        val entry = PitScoutingService.createEntry(session, request, config, isPrescout = true)
                        call.respond(entry)
                    }
                }
                route("/qual-scouting") {
                    get {
                        val session = call.requireSession()
                        val all = call.request.queryParameters["all"]?.toBoolean() ?: false
                        val eventKey = call.request.queryParameters["eventKey"]
                        call.respond(QualitativeScoutingService.listPrescoutEntries(session, all, eventKey))
                    }
                    post {
                        val session = call.requireSession()
                        val request = call.receive<ScoutingEntryRequest>()
                        val config = ConfigService.getQualitativeConfig(session.teamNumber, session.program)
                        val entry = QualitativeScoutingService.createEntry(session, request, config, isPrescout = true)
                        call.respond(entry)
                    }
                }
                post("/sync-event") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val (cachedTeams, cachedMatches) = com.obsidianscout.db.readTransaction {
                        val teamCount = com.obsidianscout.db.ApiTeams.selectAll().where { com.obsidianscout.db.ApiTeams.eventKey eq eventKey }.count().toInt()
                        val matchCount = com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.eventKey eq eventKey }.count().toInt()
                        Pair(teamCount, matchCount)
                    }
                    com.obsidianscout.integrations.SyncScheduler.enqueueCustomEventDataSync(session.teamNumber, settings, eventKey)
                    call.respond(com.obsidianscout.integrations.SyncCounts(cachedTeams, cachedMatches))
                }
            }

            route("/assignments") {
                get {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                    val type = call.request.queryParameters["type"]
                    val userId = call.request.queryParameters["userId"]
                    val status = call.request.queryParameters["status"]
                    val list = ScoutingAssignmentService.listAssignments(session, eventKey, type, userId, status)
                    call.respond(mapOf("assignments" to list))
                }
                get("/my") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                    val list = ScoutingAssignmentService.getMyAssignments(session, eventKey)
                    call.respond(mapOf("assignments" to list))
                }
                get("/coverage") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    call.respond(ScoutingAssignmentService.getCoverage(session, eventKey))
                }
                get("/conflicts") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey")
                    val type = call.request.queryParameters["type"]
                    val matchNumber = call.request.queryParameters["matchNumber"]?.toIntOrNull()
                    val matchKey = call.request.queryParameters["matchKey"]
                    val targetTeamNumber = call.request.queryParameters["targetTeamNumber"]?.toIntOrNull()
                    val allianceColor = call.request.queryParameters["allianceColor"]
                    call.respond(ScoutingAssignmentService.getConflicts(session, eventKey, type, matchNumber, matchKey, targetTeamNumber, allianceColor))
                }
                post {
                    val session = call.requireAdmin()
                    val request = call.receive<com.obsidianscout.scouting.CreateAssignmentRequest>()
                    call.respond(ScoutingAssignmentService.createAssignment(session, request))
                    com.obsidianscout.widgets.WidgetPushService.notifyTeam(session.teamNumber, session.program, "assignments")
                }
                post("/bulk") {
                    val session = call.requireAdmin()
                    val request = call.receive<com.obsidianscout.scouting.BulkCreateAssignmentsRequest>()
                    call.respond(ScoutingAssignmentService.bulkCreateAssignments(session, request))
                    com.obsidianscout.widgets.WidgetPushService.notifyTeam(session.teamNumber, session.program, "assignments")
                }
                post("/auto-generate") {
                    val session = call.requireAdmin()
                    val request = call.receive<com.obsidianscout.scouting.AutoGenerateAssignmentsRequest>()
                    call.respond(ScoutingAssignmentService.autoGenerateAssignments(session, request))
                    com.obsidianscout.widgets.WidgetPushService.notifyTeam(session.teamNumber, session.program, "assignments")
                }
                post("/auto-resolve-conflicts") {
                    val session = call.requireAdmin()
                    val request = call.receive<com.obsidianscout.scouting.AutoResolveConflictsRequest>()
                    call.respond(ScoutingAssignmentService.autoResolveConflicts(session, request.eventKey))
                    com.obsidianscout.widgets.WidgetPushService.notifyTeam(session.teamNumber, session.program, "assignments")
                }
                get("/pit-groups") {
                    val session = call.requireSession()
                    call.respond(ScoutingAssignmentService.getPitGroups(session))
                }
                put("/pit-groups") {
                    val session = call.requireAdmin()
                    val request = call.receive<com.obsidianscout.scouting.SavePitGroupsRequest>()
                    call.respond(ScoutingAssignmentService.savePitGroups(session, request.groups))
                }
                get("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing assignment ID")
                    val record = ScoutingAssignmentService.getAssignmentById(session, id)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Assignment not found")
                    call.respond(record)
                }
                put("/{id}") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing assignment ID")
                    val request = call.receive<com.obsidianscout.scouting.UpdateAssignmentRequest>()
                    call.respond(ScoutingAssignmentService.updateAssignment(session, id, request))
                    com.obsidianscout.widgets.WidgetPushService.notifyTeam(session.teamNumber, session.program, "assignments")
                }
                post("/{id}/status") {
                    val session = call.requireSession()
                    val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing assignment ID")
                    val request = call.receive<com.obsidianscout.scouting.UpdateAssignmentStatusRequest>()
                    call.respond(ScoutingAssignmentService.updateStatus(session, id, request.status))
                }
                patch("/{id}/status") {
                    val session = call.requireSession()
                    val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing assignment ID")
                    val request = call.receive<com.obsidianscout.scouting.UpdateAssignmentStatusRequest>()
                    call.respond(ScoutingAssignmentService.updateStatus(session, id, request.status))
                }
                delete {
                    val session = call.requireAdmin()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val type = call.request.queryParameters["type"]
                    val idsParam = call.request.queryParameters["ids"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                    val deletedCount = ScoutingAssignmentService.deleteAllAssignments(session, eventKey, type, idsParam)
                    call.respond(com.obsidianscout.scouting.DeleteAssignmentsResponse(success = true, deletedCount = deletedCount, message = "Deleted $deletedCount assignments"))
                    com.obsidianscout.widgets.WidgetPushService.notifyTeam(session.teamNumber, session.program, "assignments")
                }
                delete("/{id}") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing assignment ID")
                    val deleted = ScoutingAssignmentService.deleteAssignment(session, id)
                    if (deleted) {
                        com.obsidianscout.widgets.WidgetPushService.notifyTeam(session.teamNumber, session.program, "assignments")
                        call.respond(mapOf("success" to true))
                    } else {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Assignment not found")
                    }
                }
                post("/{id}/remind") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing assignment ID")
                    val record = ScoutingAssignmentService.getAssignmentById(session, id)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Assignment not found")
                    val targetUserUuid = runCatching { java.util.UUID.fromString(record.assignedUserId) }.getOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid user")
                    val mKey = record.matchKey ?: "M${record.matchNumber ?: ""}"
                    val teamStr = if (record.targetTeamNumber != null) "Team ${record.targetTeamNumber}" else "your target"
                    val title = "Scouting Reminder: Match #${record.matchNumber ?: ""}"
                    val body = "Manual reminder: You are assigned to scout $teamStr in Match #${record.matchNumber ?: ""}."
                    val scoutUrl = "/scout?matchKey=$mKey&matchNumber=${record.matchNumber ?: ""}${if (record.targetTeamNumber != null) "&team=${record.targetTeamNumber}" else ""}&event=${record.eventKey}"

                    PushNotificationService.sendAssignmentReminder(
                        userId = targetUserUuid,
                        title = title,
                        body = body,
                        url = scoutUrl,
                        tag = "reminder-manual-$id"
                    )
                    call.respond(com.obsidianscout.scouting.AssignmentActionResponse(success = true, message = "Reminder sent"))
                }
            }

            route("/summary") {
                get {
                    val session = call.requireSession()
                    val teamParam = call.request.queryParameters["teamNumber"]
                    val targetTeamNumber = when {
                        teamParam.equals("all", ignoreCase = true) -> 0
                        else -> teamParam?.toIntOrNull()
                    }
                    val eventKeyParam = call.request.queryParameters["eventKey"]

                    val summary = call.measure("summary-db", "Summary DB Query") {
                        IntegrationService.summary(
                            session = session,
                            targetTeamNumber = targetTeamNumber,
                            eventKey = eventKeyParam
                        )
                    }
                    call.respond(summary)
                }
            }

            route("/validation") {
                get {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: call.request.queryParameters["event"]
                        ?: AllianceService.getEffectiveSettings(session.teamNumber, session.program).resolvedEventKey()
                    val forcePrescout = call.request.queryParameters["forcePrescout"]?.toBoolean() ?: false
                    val threshold = call.request.queryParameters["threshold"]?.toDoubleOrNull() ?: 15.0

                    if (eventKey.isBlank()) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    }

                    val summary = ValidationService.validateEvent(
                        session = session,
                        eventKeyParam = eventKey,
                        forcePrescout = forcePrescout,
                        anomalyThreshold = threshold
                    )
                    call.respond(summary)
                }
            }

            route("/field-images") {
                get {
                    val session = call.requireSession()
                    val yearParam = call.request.queryParameters["year"]?.toIntOrNull()
                        ?: AllianceService.getEffectiveSettings(session.teamNumber, session.program).year
                    val result = findFieldImage(yearParam)
                    if (result != null) {
                        val versionParam = if (result.lastModified > 0) "?v=${result.lastModified}" else ""
                        call.respond(mapOf(
                            "year" to yearParam.toString(),
                            "imagePath" to "${result.webPath}$versionParam",
                            "rawImagePath" to result.webPath,
                            "imageName" to result.fileName,
                            "lastModified" to result.lastModified.toString()
                        ))
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("No field image found for year $yearParam"))
                    }
                }
            }

            route("/match-planning") {
                get {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing eventKey parameter"))
                    val matchKey = call.request.queryParameters["matchKey"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing matchKey parameter"))
                    call.respond(com.obsidianscout.scouting.MatchPlanningService.getPlan(session, eventKey, matchKey))
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<com.obsidianscout.scouting.MatchPlanUpdateRequest>()
                    call.respond(com.obsidianscout.scouting.MatchPlanningService.updatePlan(session, request))
                }
            }

            // Drive-team pre-match / on-field checklists (Personal Settings > Non-Scouting Tools).
            route("/match-checklist") {
                get("/template") {
                    val session = call.requireSession()
                    call.respond(com.obsidianscout.scouting.MatchChecklistService.getTemplate(session))
                }
                put("/template") {
                    val session = call.requireSession()
                    val request = call.receive<com.obsidianscout.scouting.MatchChecklistTemplateRequest>()
                    call.respond(com.obsidianscout.scouting.MatchChecklistService.saveTemplate(session, request))
                }
                get("/event") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing eventKey parameter"))
                    call.respond(com.obsidianscout.scouting.MatchChecklistService.listForEvent(session, eventKey))
                }
                get {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing eventKey parameter"))
                    val matchKey = call.request.queryParameters["matchKey"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing matchKey parameter"))
                    call.respond(com.obsidianscout.scouting.MatchChecklistService.getMatch(session, eventKey, matchKey))
                }
                put {
                    val session = call.requireSession()
                    val request = call.receive<com.obsidianscout.scouting.MatchChecklistUpdateRequest>()
                    call.respond(com.obsidianscout.scouting.MatchChecklistService.saveMatch(session, request))
                }
            }

            // Drive-team battery rotation (Personal Settings > Non-Scouting Tools).
            route("/battery-tracker") {
                get("/inventory") {
                    val session = call.requireSession()
                    call.respond(com.obsidianscout.scouting.BatteryTrackerService.getInventory(session))
                }
                put("/inventory") {
                    val session = call.requireSession()
                    val request = call.receive<com.obsidianscout.scouting.BatteryInventoryRequest>()
                    call.respond(com.obsidianscout.scouting.BatteryTrackerService.saveInventory(session, request))
                }
                get("/event") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing eventKey parameter"))
                    call.respond(com.obsidianscout.scouting.BatteryTrackerService.getEventPlan(session, eventKey))
                }
                put("/event") {
                    val session = call.requireSession()
                    val request = call.receive<com.obsidianscout.scouting.BatteryEventPlanRequest>()
                    call.respond(com.obsidianscout.scouting.BatteryTrackerService.saveEventPlan(session, request))
                }
            }

            route("/shares") {
                post {
                    val appConfig = AppConfigLoader.load()
                    val session = call.resolveSessionOrMobileJwt(com.obsidianscout.auth.ClusterSecretService.getSessionSecret())
                    val req = call.receive<com.obsidianscout.scouting.CreateShareRequest>()
                    val origin = call.request.headers["Origin"] ?: call.request.headers["Host"]?.let { "http://$it" } ?: ""
                    call.respond(com.obsidianscout.scouting.ShareService.createShare(session, req, origin))
                }
                get("/team") {
                    val appConfig = AppConfigLoader.load()
                    val session = call.resolveSessionOrMobileJwt(com.obsidianscout.auth.ClusterSecretService.getSessionSecret())
                    val statusFilter = call.request.queryParameters["status"]
                    val origin = call.request.headers["Origin"] ?: call.request.headers["Host"]?.let { "http://$it" } ?: ""
                    call.respond(com.obsidianscout.scouting.ShareService.listTeamShares(session.teamNumber, session.program, statusFilter, origin))
                }
                get("/resolve/{token}") {
                    val appConfig = AppConfigLoader.load()
                    val session = call.optionalSessionOrMobileJwt(com.obsidianscout.auth.ClusterSecretService.getSessionSecret())
                    val token = call.parameters["token"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing token")
                    val pin = call.request.queryParameters["pin"]
                    val clientIp = call.clientIp()
                    val userAgent = call.request.headers[HttpHeaders.UserAgent]
                    call.respond(com.obsidianscout.scouting.ShareService.resolveShare(token, pin, session, clientIp, userAgent))
                }
                post("/verify-pin/{token}") {
                    val appConfig = AppConfigLoader.load()
                    val session = call.optionalSessionOrMobileJwt(com.obsidianscout.auth.ClusterSecretService.getSessionSecret())
                    val token = call.parameters["token"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing token")
                    val req = call.receive<com.obsidianscout.scouting.VerifyPinRequest>()
                    val clientIp = call.clientIp()
                    val userAgent = call.request.headers[HttpHeaders.UserAgent]
                    call.respond(com.obsidianscout.scouting.ShareService.resolveShare(token, req.pin, session, clientIp, userAgent))
                }
                patch("/{token}") {
                    val appConfig = AppConfigLoader.load()
                    val session = call.resolveSessionOrMobileJwt(com.obsidianscout.auth.ClusterSecretService.getSessionSecret())
                    val token = call.parameters["token"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing token")
                    val req = call.receive<com.obsidianscout.scouting.UpdateShareRequest>()
                    val origin = call.request.headers["Origin"] ?: call.request.headers["Host"]?.let { "http://$it" } ?: ""
                    call.respond(com.obsidianscout.scouting.ShareService.updateShare(token, req, session, origin))
                }
                delete("/{token}") {
                    val appConfig = AppConfigLoader.load()
                    val session = call.resolveSessionOrMobileJwt(com.obsidianscout.auth.ClusterSecretService.getSessionSecret())
                    val token = call.parameters["token"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing token")
                    call.respond(mapOf("success" to com.obsidianscout.scouting.ShareService.deleteShare(token, session)))
                }
                post("/{token}/revoke") {
                    val appConfig = AppConfigLoader.load()
                    val session = call.resolveSessionOrMobileJwt(com.obsidianscout.auth.ClusterSecretService.getSessionSecret())
                    val token = call.parameters["token"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing token")
                    call.respond(mapOf("success" to com.obsidianscout.scouting.ShareService.revokeShare(token, session)))
                }
            }
}