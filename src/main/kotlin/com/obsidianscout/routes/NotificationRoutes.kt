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

fun Route.notificationRoutes() {
            route("/push") {
                get("/public-key") {
                    val appConfig = AppConfigLoader.load()
                    call.respond(mapOf("publicKey" to appConfig.vapid.publicKey))
                }
                post("/subscribe") {
                    val session = call.requireSession()
                    val subscription = call.receive<PushSubscriptionDto>()
                    
                    if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.ServiceUnavailable,
                            "Database is in read-only offline mode due to cluster quorum loss."
                        )
                    }

                    transaction {
                        val existing = PushSubscriptions.selectAll()
                            .where { PushSubscriptions.endpoint eq subscription.endpoint }
                            .firstOrNull()
                        if (existing == null) {
                            PushSubscriptions.insert {
                                it[userId] = EntityID(UUID.fromString(session.userId), com.obsidianscout.db.Users)
                                it[endpoint] = subscription.endpoint
                                it[p256dh] = subscription.keys.p256dh
                                it[auth] = subscription.keys.auth
                                it[createdAt] = Instant.now()
                            }
                        } else {
                            PushSubscriptions.update({ PushSubscriptions.endpoint eq subscription.endpoint }) {
                                it[userId] = EntityID(UUID.fromString(session.userId), com.obsidianscout.db.Users)
                                it[p256dh] = subscription.keys.p256dh
                                it[auth] = subscription.keys.auth
                            }
                        }
                    }
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                }
            }


            route("/config") {
                get("/fcm-public") {
                    call.respond(FcmService.getPublicConfig())
                }
            }

            route("/fcm") {
                get("/public-config") {
                    call.respond(FcmService.getPublicConfig())
                }
                post("/token") {
                    val session = call.requireSession()
                    val req = call.receive<RegisterFcmTokenRequest>()
                    FcmService.registerDeviceToken(
                        userId = UUID.fromString(session.userId),
                        deviceToken = req.deviceToken,
                        platform = req.platform
                    )
                    call.respond(HttpStatusCode.OK, buildJsonObject { put("success", true) })
                }
                delete("/token") {
                    val session = call.requireSession()
                    val req = call.receive<UnregisterFcmTokenRequest>()
                    FcmService.unregisterDeviceToken(
                        userId = UUID.fromString(session.userId),
                        deviceToken = req.deviceToken
                    )
                    call.respond(HttpStatusCode.OK, buildJsonObject { put("success", true) })
                }
            }

            route("/admin/fcm") {
                get {
                    val session = call.requireSuperAdmin()
                    call.respond(FcmService.getAdminConfig())
                }
                post {
                    val session = call.requireSuperAdmin()
                    val req = call.receive<SaveFcmConfigRequest>()
                    val success = FcmService.saveConfig(
                        enabled = req.enabled,
                        projectId = req.projectId,
                        apiKey = req.apiKey,
                        appId = req.appId,
                        messagingSenderId = req.messagingSenderId,
                        serviceAccountJson = req.serviceAccountJson,
                        vapidKey = req.vapidKey
                    )
                    call.respond(buildJsonObject { put("success", success) })
                }
                post("/test") {
                    val session = call.requireSuperAdmin()
                    val result = FcmService.sendTestNotification(UUID.fromString(session.userId))
                    call.respond(buildJsonObject {
                        put("success", result.first)
                        put("message", result.second)
                    })
                }
            }

}