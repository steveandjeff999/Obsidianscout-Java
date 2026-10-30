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

fun Route.allianceRoutes() {
            route("/alliances") {

                get {
                    val session = call.requireSession()
                    val list = call.measure("alliances-db", "List Alliances Query") {
                        AllianceService.listAlliances(session)
                    }
                    call.respond(list)
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
                    call.respond(InviteCountResponse(AllianceService.getInviteCount(session)))
                }
                get("/import-sources") {
                    val session = call.requireAdmin()
                    call.respond(AllianceService.listImportSources(session))
                }
                put("/{id}") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<UpdateAllianceRequest>()
                    call.respond(AllianceService.updateAlliance(session, id, req.name, req.eventKey, req.notes, req.year, req.eventCode))
                }
                delete("/{id}") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    AllianceService.deleteAlliance(session, id)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/invite") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<InviteTeamRequest>()
                    AllianceService.inviteTeam(session, id, req.partnerTeamNumber)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/import") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]
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
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<RespondInviteRequest>()
                    AllianceService.respondToInvite(session, id, req.accept)
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/{id}/members/{teamNumber}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val targetTeam = call.parameters["teamNumber"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid team number")
                    AllianceService.removeMember(session, id, targetTeam)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val alliance = call.measure("alliance-db", "Get Alliance Query") {
                        AllianceService.getAlliance(session, id)
                    }
                    call.respond(alliance)
                }
                get("/{id}/config/{kind}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val kind = call.parameters["kind"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid kind")
                    
                    // Verify membership (throws if not member)
                    AllianceService.getAlliance(session, id)
                    
                    val idUuid = runCatching { java.util.UUID.fromString(id) }.getOrElse {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id format")
                    }
                    val configJson = com.obsidianscout.db.readTransaction {
                        val row = ScoutingAlliances.selectAll().where { ScoutingAlliances.id eq idUuid }.firstOrNull()
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
                // Saves an alliance form directly; the editor uses this when live editing can't connect.
                put("/{id}/config/{kind}") {
                    val session = call.requireSession()
                    val idUuid = runCatching { java.util.UUID.fromString(call.parameters["id"]) }.getOrElse {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id format")
                    }
                    val kind = com.obsidianscout.collab.ConfigCollabScope.normalizeKind(call.parameters["kind"])
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid kind")
                    if (!AllianceService.isAllianceAdmin(session.teamNumber, idUuid, session.program)) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only alliance admins can edit alliance forms")
                    }
                    val body = call.receiveText()
                    if (runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() == null) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Config must be a JSON object")
                    }
                    ConfigService.requireNoBlankOptionValues(body)
                    com.obsidianscout.collab.ConfigCollabScope.Alliance(idUuid, kind).persist(body)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/toggle-active") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<ToggleAllianceActiveRequest>()
                    AllianceService.toggleActiveMembership(session, id, req.active)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/toggle-disable") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val req = call.receive<ToggleAllianceDisableRequest>()
                    AllianceService.toggleActiveMembership(session, id, !req.disabled)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/members/{teamNumber}/promote") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid alliance id")
                    val targetTeam = call.parameters["teamNumber"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid team number")
                    AllianceService.promoteMember(session, id, targetTeam)
                    call.respond(HttpStatusCode.NoContent)
                }
                allianceConfigCollabRoute()
            }

            configCollabRoutes()
            matchChecklistCollabRoute()
            batteryTrackerCollabRoute()

}