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

fun Route.contactRoutes() {
            route("/contact") {
                post {
                    val session = call.sessions.get<UserSession>()
                    val request = call.receive<ContactRequest>()
                    
                    val smtp = SettingsService.getSmtpSettings()
                    if (smtp.host.isBlank()) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.ServiceUnavailable,
                            "SMTP email settings are not configured. Please contact obsidianscoutfrc@gmail.com directly."
                        )
                    }
                    
                    val formattedType = when (request.type.uppercase()) {
                        "BUG_REPORT" -> "Bug Report"
                        "FEATURE_REQUEST" -> "Feature Request"
                        "OTHER" -> "Other"
                        else -> request.type
                    }
                    
                    val teamDisplay = session?.let { "Team ${it.teamNumber}" } 
                        ?: (request.teamNumber?.let { "Team $it" } ?: "Unauthenticated / Locked Out")
                    val senderDisplay = session?.username ?: request.name.ifBlank { "Visitor" }
                    val subject = "ObsidianScout: ${formattedType} from ${senderDisplay} (${teamDisplay})"
                    val userEmailInfo = session?.email?.let { "<strong>Account Email:</strong> ${it}<br/>" } ?: ""
                    val replyToInfo = if (request.replyToEmail.isNullOrBlank()) "" else "<strong>Reply-To Email:</strong> ${request.replyToEmail}<br/>"
                    val loggedInInfo = session?.let { "${it.username} (Team ${it.teamNumber})" } ?: "None (Unauthenticated / Locked Out Visitor)"
                    
                    val body = """
                        <html>
                        <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                            <div style="max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 8px;">
                                <h2 style="color: #4f46e5; border-bottom: 2px solid #edf2f7; padding-bottom: 10px;">New Contact Form Submission</h2>
                                <p><strong>Type:</strong> ${formattedType}</p>
                                <p><strong>Sender Name:</strong> ${request.name}</p>
                                <p><strong>Logged-in Account:</strong> ${loggedInInfo}</p>
                                <p>${userEmailInfo}</p>
                                <p>${replyToInfo}</p>
                                <p><strong>Message:</strong></p>
                                <div style="background-color: #f9fafb; padding: 15px; border-radius: 6px; border: 1px solid #e5e7eb; white-space: pre-wrap;">
                                    ${request.message}
                                </div>
                            </div>
                        </body>
                        </html>
                    """.trimIndent()
                    
                    try {
                        EmailService.sendEmail(
                            to = "obsidianscoutfrc@gmail.com",
                            subject = subject,
                            body = body
                        )
                    } catch (e: Exception) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.InternalServerError,
                            "Failed to send email: ${e.message}"
                        )
                    }
                    
                    call.respond(mapOf("success" to true))
                }
            }
}