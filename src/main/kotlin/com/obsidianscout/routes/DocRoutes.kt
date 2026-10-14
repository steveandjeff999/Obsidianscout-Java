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

private fun findDocsDir(): java.io.File {
    val paths = listOf("docs", "Obsidianscout/docs", "../docs")
    for (p in paths) {
        val f = java.io.File(p)
        if (f.exists() && f.isDirectory) {
            return f
        }
    }
    return java.io.File("docs")
}

fun Route.docRoutes() {
            route("/docs") {
                get {
                    val lang = call.request.queryParameters["lang"]?.lowercase()?.takeIf { it.matches(Regex("^[a-z]{2}(-[a-z]{2})?$")) } ?: "en"
                    val docsDir = findDocsDir()
                    if (!docsDir.exists()) {
                        call.respond(emptyList<Map<String, String>>())
                        return@get
                    }
                    val baseFiles = docsDir.listFiles { _, name -> 
                        name.endsWith(".md") && !name.contains("_es.md") && !name.contains("_tr.md") && !name.contains("_he.md")
                    }?.sortedBy { it.name } ?: emptyList()
                    
                    val files = baseFiles.map { baseFile ->
                        val baseName = baseFile.nameWithoutExtension
                        val translatedFile = java.io.File(docsDir, "${baseName}_$lang.md")
                        val fileToRead = if (lang != "en" && translatedFile.exists() && translatedFile.isFile) {
                            translatedFile
                        } else {
                            baseFile
                        }
                        val content = fileToRead.readText()
                        val title = content.lineSequence().firstOrNull { it.startsWith("#") }
                            ?.removePrefix("#")?.trim() ?: fileToRead.nameWithoutExtension
                        mapOf(
                            "filename" to baseFile.name,
                            "title" to title
                        )
                    }
                    call.respond(files)
                }
                get("/{filename}") {
                    val filename = call.parameters["filename"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing filename")
                    if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid filename")
                    }
                    val lang = call.request.queryParameters["lang"]?.lowercase()?.takeIf { it.matches(Regex("^[a-z]{2}(-[a-z]{2})?$")) } ?: "en"
                    val docsDir = findDocsDir()
                    val baseFile = java.io.File(docsDir, filename)
                    val baseName = baseFile.nameWithoutExtension
                    val translatedFile = java.io.File(docsDir, "${baseName}_$lang.md")
                    val fileToRead = if (lang != "en" && translatedFile.exists() && translatedFile.isFile) {
                        translatedFile
                    } else {
                        baseFile
                    }
                    if (!fileToRead.exists() || !fileToRead.isFile || !fileToRead.name.endsWith(".md")) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Doc not found")
                    }
                    call.respond(mapOf(
                        "filename" to filename,
                        "content" to fileToRead.readText()
                    ))
                }
            }
}