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

fun Route.analyticsRoutes() {
            route("/analytics") {
                get {
                    val session = call.requireSession()
                    val config = ConfigService.getConfig(session.teamNumber, session.program)
                    val forcePrescout = call.request.queryParameters["usePrescout"]?.toBoolean() ?: false
                    
                    val regularEntries = ScoutingService.listEntries(session, includePrescout = false)
                    val prescoutEntries = ScoutingService.listEntries(session, includePrescout = true).filter { it.isPrescout }
                    
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
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

                get("/compare") {
                    val session = call.requireSession()
                    val teamsParam = call.request.queryParameters["teams"]
                        ?: listOfNotNull(
                            call.request.queryParameters["team1"],
                            call.request.queryParameters["team2"],
                            call.request.queryParameters["team3"]
                        ).joinToString(",")
                    val teamNumbers = teamsParam.split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .distinct()
                    if (teamNumbers.isEmpty()) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "No team numbers provided")
                    }

                    val eventKey = call.request.queryParameters["eventKey"]
                    // source=match13 (or exp) compares Match 13 xP instead of scouting averages.
                    val source = call.request.queryParameters["source"]?.trim()?.lowercase()
                    val response = if (source == com.obsidianscout.analytics.Match13Compare.SOURCE || source == "exp") {
                        com.obsidianscout.analytics.Match13Compare.compare(session, teamNumbers, eventKey)
                    } else {
                        com.obsidianscout.analytics.AnalyticsService.compareTeams(session, teamNumbers, eventKey)
                    }
                    call.respond(response)
                }
            }

            route("/custom-analytics") {
                route("/reports") {
                    get {
                        val session = call.requireSession()
                        call.respond(com.obsidianscout.analytics.AnalyticsReportService.listReports(session))
                    }
                    get("/{id}") {
                        val session = call.requireSession()
                        val id = call.parameters["id"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing report ID")
                        call.respond(com.obsidianscout.analytics.AnalyticsReportService.getReport(id, session))
                    }
                    post {
                        val session = call.requireSession()
                        val req = call.receive<com.obsidianscout.analytics.CreateReportRequest>()
                        val created = com.obsidianscout.analytics.AnalyticsReportService.createReport(session, req)
                        call.respond(HttpStatusCode.Created, created)
                    }
                    put("/{id}") {
                        val session = call.requireSession()
                        val id = call.parameters["id"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing report ID")
                        val req = call.receive<com.obsidianscout.analytics.UpdateReportRequest>()
                        val updated = com.obsidianscout.analytics.AnalyticsReportService.updateReport(id, session, req)
                        call.respond(updated)
                    }
                    delete("/{id}") {
                        val session = call.requireSession()
                        val id = call.parameters["id"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing report ID")
                        val deleted = com.obsidianscout.analytics.AnalyticsReportService.deleteReport(id, session)
                        call.respond(mapOf("success" to deleted))
                    }
                    post("/{id}/duplicate") {
                        val session = call.requireSession()
                        val id = call.parameters["id"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing report ID")
                        val duplicated = com.obsidianscout.analytics.AnalyticsReportService.duplicateReport(id, session)
                        call.respond(HttpStatusCode.Created, duplicated)
                    }
                }
                get("/dataset") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                    val includePrescout = call.request.queryParameters["includePrescout"]?.toBoolean() ?: true
                    val dataset = com.obsidianscout.analytics.AnalyticsReportService.generateDataset(
                        session = session,
                        eventKeyFilter = eventKey,
                        includePrescout = includePrescout
                    )
                    call.respond(dataset)
                }
            }

            route("/alliance-selection") {
                get {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val response = com.obsidianscout.scouting.AllianceSelectionService.getSelection(session, eventKey)
                    call.respond(response)
                }
                get("/export/csv") {
                    val session = call.requireAnalyticsOrAbove()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val response = com.obsidianscout.scouting.AllianceSelectionService.getSelection(session, eventKey)
                    val sb = StringBuilder()
                    sb.append("Alliance,Role,Team Number\n")
                    val json = runCatching { JsonSupport.json.parseToJsonElement(response.selectionJson).jsonObject }.getOrNull()
                    if (json != null) {
                        for (allianceIndex in 1..8) {
                            val allianceKey = "alliance$allianceIndex"
                            val allianceObj = json[allianceKey]?.let { runCatching { it.jsonObject }.getOrNull() }
                            if (allianceObj != null) {
                                val captain = allianceObj["captain"]?.let { if (it is JsonPrimitive) it.content else null } ?: ""
                                val firstPick = allianceObj["firstPick"]?.let { if (it is JsonPrimitive) it.content else null } ?: ""
                                val secondPick = allianceObj["secondPick"]?.let { if (it is JsonPrimitive) it.content else null } ?: ""
                                val backup = allianceObj["backup"]?.let { if (it is JsonPrimitive) it.content else null } ?: ""
                                if (captain.isNotBlank()) sb.append("Alliance $allianceIndex,Captain,$captain\n")
                                if (firstPick.isNotBlank()) sb.append("Alliance $allianceIndex,First Pick,$firstPick\n")
                                if (secondPick.isNotBlank()) sb.append("Alliance $allianceIndex,Second Pick,$secondPick\n")
                                if (backup.isNotBlank()) sb.append("Alliance $allianceIndex,Backup,$backup\n")
                            }
                        }
                    }
                    // Pick lists follow the board in the same Alliance/Role/Team columns, plus an optional note.
                    val pickLists = com.obsidianscout.scouting.AllianceSelectionService.getPickLists(session, eventKey).pickLists
                    fun csvCell(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
                    listOf("Want" to pickLists.want, "Avoid" to pickLists.avoid, "Do Not Pick" to pickLists.dnp).forEach { (listName, teams) ->
                        teams.forEachIndexed { idx, team ->
                            sb.append("${csvCell(listName)},${csvCell("#${idx + 1}")},$team")
                            pickLists.notes[team.toString()]?.let { sb.append(",${csvCell(it)}") }
                            sb.append("\n")
                        }
                    }
                    com.obsidianscout.scouting.AllianceSelectionService.getRankedPickList(session, eventKey).ranking
                        .forEachIndexed { idx, team -> sb.append("${csvCell("Pick List")},${csvCell("#${idx + 1}")},$team\n") }
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"alliance_selection_${eventKey}.csv\"")
                    call.respondText(sb.toString(), ContentType.Text.CSV, HttpStatusCode.OK)
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<com.obsidianscout.scouting.AllianceSelectionUpdateRequest>()
                    val response = com.obsidianscout.scouting.AllianceSelectionService.updateSelection(session, request)
                    call.respond(response)
                }
                route("/pick-lists") {
                    get {
                        val session = call.requireSession()
                        val eventKey = call.request.queryParameters["eventKey"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                        call.respond(com.obsidianscout.scouting.AllianceSelectionService.getPickLists(session, eventKey))
                    }
                    post {
                        val session = call.requireSession()
                        val request = call.receive<com.obsidianscout.scouting.PickListsUpdateRequest>()
                        if (request.eventKey.isBlank()) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey")
                        }
                        call.respond(com.obsidianscout.scouting.AllianceSelectionService.updatePickLists(session, request))
                    }
                }
                // Ranked pick list. Shares the Do Not Pick list with the pick lists above.
                route("/picklist") {
                    get {
                        val session = call.requireSession()
                        val eventKey = call.request.queryParameters["eventKey"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                        call.respond(com.obsidianscout.scouting.AllianceSelectionService.getRankedPickList(session, eventKey))
                    }
                    post {
                        val session = call.requireSession()
                        val request = call.receive<com.obsidianscout.scouting.RankedPickListUpdateRequest>()
                        if (request.eventKey.isBlank()) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey")
                        }
                        call.respond(com.obsidianscout.scouting.AllianceSelectionService.updateRankedPickList(session, request))
                    }
                    post("/dnp") {
                        val session = call.requireSession()
                        val request = call.receive<com.obsidianscout.scouting.DoNotPickUpdateRequest>()
                        if (request.eventKey.isBlank() || request.teamNumber <= 0) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey or teamNumber")
                        }
                        call.respond(com.obsidianscout.scouting.AllianceSelectionService.setDoNotPick(session, request))
                    }
                }
            }

            route("/stats") {
                get("/history") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: AllianceService.getEffectiveSettings(session.teamNumber, session.program).resolvedEventKey()
                    if (eventKey.isBlank()) {
                        call.respond(StatsHistoryResponse())
                        return@get
                    }
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val history = IntegrationService.getFullStatsHistory(settings, eventKey)
                    call.respond(history)
                }
            }
}