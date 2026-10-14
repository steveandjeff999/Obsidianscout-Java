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

private suspend fun ApplicationCall.requireChatSession(): UserSession {
    val session = requireSession()
    val settings = SettingsService.getSettings(session.teamNumber)
    if (!settings.chatEnabled) {
        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Chat is disabled by team admin")
    }
    return session
}

private suspend fun ApplicationCall.requireChatAdmin(): UserSession {
    val session = requireAdmin()
    val settings = SettingsService.getSettings(session.teamNumber)
    if (!settings.chatEnabled) {
        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Chat is disabled by team admin")
    }
    return session
}

fun Route.chatRoutes() {
            route("/chat") {
                get("/messages") {
                    val session = call.requireChatSession()
                    val groupName = call.request.queryParameters["group"] ?: "general"
                    try {
                        val messages = ChatService.getMessages(session.teamNumber, session.program, groupName, session.userId, session.role)
                        call.respond(messages)
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, e.message ?: "Access denied")
                    }
                }
                post("/messages") {
                    val session = call.requireChatSession()
                    val request = call.receive<SendMessageRequest>()
                    try {
                        val message = ChatService.sendMessage(
                            teamNumber = session.teamNumber,
                            program = session.program,
                            groupName = request.groupName,
                            userId = session.userId,
                            username = session.username,
                            content = request.content,
                            userRole = session.role
                        )
                        try {
                            PushNotificationService.sendChatNotification(message)
                        } catch (e: Exception) {
                            call.application.environment.log.error("Failed to trigger push notifications", e)
                        }
                        call.respond(message)
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, e.message ?: "Access denied")
                    }
                }
                put("/messages/{id}") {
                    val session = call.requireChatSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid message id")
                    val request = call.receive<EditChatMessageRequest>()
                    val trimmedContent = request.content.trim()
                    if (trimmedContent.isEmpty()) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Message content cannot be empty")
                    }
                    try {
                        val updated = ChatService.editMessage(id, session.userId, session.teamNumber, trimmedContent)
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Message not found")
                        call.respond(updated)
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, e.message ?: "Cannot edit message")
                    }
                }
                delete("/messages/{id}") {
                    val session = call.requireChatSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid message id")
                    try {
                        val deleted = ChatService.deleteMessage(id, session.userId, session.role, session.teamNumber)
                        if (!deleted) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Message not found")
                        }
                        call.respond(buildJsonObject { put("success", true) })
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, e.message ?: "Cannot delete message")
                    }
                }
                post("/messages/{id}/react") {
                    val session = call.requireChatSession()
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid message id")
                    val request = call.receive<ReactMessageRequest>()
                    try {
                        val updated = ChatService.toggleReaction(id, session.username, request.emoji, session.teamNumber, session.program)
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Message not found")
                        call.respond(updated)
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, e.message ?: "Cannot react to your own message")
                    }
                }
                get("/groups") {
                    val session = call.requireChatSession()
                    val groups = ChatService.getGroups(session.teamNumber, session.program, session.userId, session.role)
                    call.respond(groups)
                }
                get("/groups/details") {
                    val session = call.requireChatSession()
                    val details = ChatService.getAllGroupDetails(session.teamNumber, session.program, session.userId, session.role)
                    call.respond(details)
                }
                get("/groups/{name}/details") {
                    val session = call.requireChatSession()
                    val groupName = call.parameters["name"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing channel name")
                    try {
                        val details = ChatService.getGroupDetails(session.teamNumber, session.program, groupName, session.userId, session.role)
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Channel not found")
                        call.respond(details)
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, e.message ?: "Access denied")
                    }
                }
                put("/groups/{name}/permissions") {
                    val session = call.requireChatAdmin()
                    val groupName = call.parameters["name"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing channel name")
                    val request = call.receive<UpdateGroupPermissionsRequest>()
                    try {
                        val success = ChatService.updateGroupPermissions(
                            teamNumber = session.teamNumber,
                            program = session.program,
                            groupName = groupName,
                            allowedRoles = request.allowedRoles,
                            allowedUserIds = request.allowedUserIds,
                            userRole = session.role
                        )
                        call.respond(buildJsonObject { put("success", success) })
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, e.message ?: "Cannot update permissions")
                    }
                }
                post("/groups/{name}/clear") {
                    val session = call.requireChatAdmin()
                    val groupName = call.parameters["name"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing channel name")
                    try {
                        val success = ChatService.clearGroupMessages(session.teamNumber, session.program, groupName, session.role)
                        call.respond(buildJsonObject { put("success", success) })
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, e.message ?: "Cannot clear channel")
                    }
                }
                post("/groups") {
                    val session = call.requireChatSession()
                    val request = call.receive<CreateGroupRequest>()
                    val success = ChatService.createGroup(
                        teamNumber = session.teamNumber,
                        program = session.program,
                        groupName = request.groupName,
                        userId = session.userId,
                        allowedRoles = request.allowedRoles,
                        allowedUserIds = request.allowedUserIds
                    )
                    call.respond(buildJsonObject {
                        put("success", success)
                        put("groupName", request.groupName)
                    })
                }
                delete("/groups/{name}") {
                    val session = call.requireChatSession()
                    val groupName = call.parameters["name"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing channel name")
                    try {
                        val deleted = ChatService.deleteGroup(session.teamNumber, session.program, groupName, session.role)
                        if (!deleted) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Channel not found")
                        }
                        call.respond(buildJsonObject { put("success", true) })
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, e.message ?: "Cannot delete channel")
                    }
                }
                get("/unread-status") {
                    val session = call.requireChatSession()
                    val status = ChatService.getUnreadStatus(session.userId, session.teamNumber, session.username, session.role, session.program)
                    call.respond(status)
                }
                post("/read") {
                    val session = call.requireChatSession()
                    val request = call.receive<ReadChatGroupRequest>()
                    ChatService.updateLastRead(session.userId, request.groupName)
                    call.respond(HttpStatusCode.OK)
                }
                get("/team-users") {
                    val session = call.requireChatSession()
                    val usernames = com.obsidianscout.db.readTransaction {
                        com.obsidianscout.db.Users.selectAll()
                            .where {
                                (com.obsidianscout.db.Users.teamNumber eq session.teamNumber) and
                                (com.obsidianscout.db.Users.program eq session.program) and
                                (com.obsidianscout.db.Users.username neq "Deleted User")
                            }
                            .map { it[com.obsidianscout.db.Users.username] }
                            .sorted()
                    }
                    call.respond(usernames)
                }
                get("/team-members") {
                    val session = call.requireChatSession()
                    val members = com.obsidianscout.db.readTransaction {
                        com.obsidianscout.db.Users.selectAll()
                            .where {
                                (com.obsidianscout.db.Users.teamNumber eq session.teamNumber) and
                                (com.obsidianscout.db.Users.program eq session.program) and
                                (com.obsidianscout.db.Users.username neq "Deleted User")
                            }
                            .map {
                                ChatTeamMemberDto(
                                    userId = it[com.obsidianscout.db.Users.id].value.toString(),
                                    username = it[com.obsidianscout.db.Users.username],
                                    role = it[com.obsidianscout.db.Users.role],
                                    profilePicture = it[com.obsidianscout.db.Users.profilePicture]
                                )
                            }
                            .sortedBy { it.username.lowercase() }
                    }
                    call.respond(members)
                }
            }

            webSocket("/ws/notifications") {
                val session = runCatching { call.requireSession() }.getOrNull() ?: return@webSocket this.close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session")
                )
                com.obsidianscout.db.NotificationWebSocketManager.registerSession(session.userId, this)
                try {
                    for (frame in incoming) {
                        // Keep-alive loop
                    }
                } finally {
                    com.obsidianscout.db.NotificationWebSocketManager.unregisterSession(session.userId, this)
                }
            }

}