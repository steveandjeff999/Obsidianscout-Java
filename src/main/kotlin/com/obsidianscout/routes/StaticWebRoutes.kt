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

internal suspend fun ApplicationCall.respondStaticHtml(fileName: String, status: HttpStatusCode = HttpStatusCode.OK) {
    val (html, sidebar) = measureSuspend("load-html", "Load HTML from Resource") {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Try filesystem first (native binary bundle with static/ folder next to binary)
            val fsFile = File("static/$fileName")
            val fsBase = File("static/base.html")
            val htmlContent = if (fsFile.exists()) fsFile.readText()
                else Thread.currentThread().contextClassLoader.getResource("static/$fileName")?.readText()
            val sidebarContent = if (fsBase.exists()) fsBase.readText().trim()
                else Thread.currentThread().contextClassLoader.getResource("static/base.html")?.readText()?.trim()
            htmlContent to sidebarContent
        }
    }
    if (html == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    val rendered = measure("render-sidebar", "Render HTML Sidebar") {
        if (sidebar.isNullOrBlank()) {
            html
        } else {
            html.replace(
                Regex("""<aside class="sidebar">.*?</aside>""", setOf(RegexOption.DOT_MATCHES_ALL)),
                sidebar
            )
        }
    }
    val currentVersion = AppConfigLoader.load().current_version
    val versionedHtml = measure("inject-asset-version", "Inject Asset Versioning") {
        injectAssetVersioning(rendered, currentVersion)
    }
    response.headers.append(HttpHeaders.CacheControl, "no-cache, must-revalidate")
    response.headers.append("CDN-Cache-Control", "no-cache")
    response.headers.append("Cloudflare-CDN-Cache-Control", "no-cache")
    respondText(versionedHtml, ContentType.Text.Html, status)
}

private val ASSET_URL_REGEX = Regex(
    """<(script|link)\b([^>]*?)\b(src|href)=["'](/[^"'\s>]+?\.(?:js|css))(\?[^"'\s>]*)?["']""",
    RegexOption.IGNORE_CASE
)

internal fun injectAssetVersioning(html: String, version: String): String {
    if (version.isBlank()) return html
    return ASSET_URL_REGEX.replace(html) { match ->
        val tag = match.groupValues[1]
        val beforeAttr = match.groupValues[2]
        val attrName = match.groupValues[3]
        val path = match.groupValues[4]
        val existingQuery = match.groupValues[5]

        if (path.startsWith("//")) {
            return@replace match.value
        }

        if (existingQuery.contains("v=") || existingQuery.contains("_sw_ver=")) {
            return@replace match.value
        }

        val separator = if (existingQuery.isEmpty()) "?" else "&"
        val newQuery = "$existingQuery${separator}v=$version"
        "<$tag$beforeAttr$attrName=\"$path$newQuery\""
    }
}

/** Pages that can run in public mode at /public/{team}/{page}. */
internal val PUBLIC_DATA_PAGES = setOf(
    "graphs", "analytics", "compare", "custom-analytics", "rankings", "projected-rankings",
    "predictor", "event-predictor", "team", "match-data", "pit-data", "qual-data", "all-data"
) + com.obsidianscout.publicdata.PUBLIC_NOTES_PAGES

fun Route.staticWebRoutes() {
        get("/shared/{token}") {
            call.respondStaticHtml("shared.html")
        }

        // Read-only analytics pages over a team's public data. The HTML holds no data, so no login is needed;
        // the page's API calls go through /api/public/..., which checks the team still shares its data.
        get("/public/{team}/{page}") {
            val page = call.parameters["page"].orEmpty()
            val team = call.parameters["team"]?.toIntOrNull()
            val program = call.request.queryParameters["program"]?.trim()?.uppercase()?.takeIf { it == "FTC" } ?: "FRC"
            val notesPageBlocked = page in com.obsidianscout.publicdata.PUBLIC_NOTES_PAGES && team != null &&
                com.obsidianscout.publicdata.PublicDataService.publicSettings(team, program)?.dataPublicIncludeNotes != true
            if (team == null || page !in PUBLIC_DATA_PAGES || notesPageBlocked) {
                call.respondStaticHtml("404.html", HttpStatusCode.NotFound)
            } else {
                call.respondStaticHtml("$page.html")
            }
        }

        val pages = mapOf(
            "index" to "index.html",
            "reset-password" to "reset-password.html",
            "dashboard" to "dashboard.html",
            "scout" to "scout.html",
            "pit-scout" to "pit-scout.html",
            "qual-scout" to "qual-scout.html",
            "assistant" to "assistant.html",
            "prescout-scout" to "prescout-scout.html",
            "prescout-pit" to "prescout-pit.html",
            "prescout-qual" to "prescout-qual.html",
            "prescout" to "prescout.html",
            "match-data" to "match-data.html",
            "qual-data" to "qual-data.html",
            "pit-data" to "pit-data.html",
            "all-data" to "all-data.html",
            "analytics" to "analytics.html",
            "compare" to "compare.html",
            "custom-analytics" to "custom-analytics.html",
            "data-validation" to "data-validation.html",
            "graphs" to "graphs.html",
            "events" to "events.html",
            "teams" to "teams.html",
            "rankings" to "rankings.html",
            "projected-rankings" to "projected-rankings.html",
            "qual-rankings" to "qual-rankings.html",
            "team" to "team.html",
            "matches" to "matches.html",
            "predictor" to "predictor.html",
            "event-predictor" to "event-predictor.html",
            "alliances" to "alliances.html",
            "alliance-edit" to "alliance-edit.html",
            "alliance-selection" to "alliance-selection.html",
            "pick-list" to "pick-list.html",
            "match-planning" to "match-planning.html",
            "match-checklist" to "match-checklist.html",
            "battery-tracker" to "battery-tracker.html",
            "match-predictions" to "match-predictions.html",
            "prediction-leaderboard" to "prediction-leaderboard.html",
            "assignments" to "assignments.html",
            "my-assignments" to "my-assignments.html",
            "users" to "users.html",
            "scouter-report" to "scouter-report.html",
            "config" to "config.html",
            "admin-settings" to "admin-settings.html",
            "cluster-management" to "cluster-management.html",
            "storage-manager" to "storage-manager.html",
            "error-reports" to "error-reports.html",
            "fcm-settings" to "fcm-settings.html",
            "default-configs" to "default-configs.html",
            "backup" to "backup.html",
            "qr-scanner" to "qr-scanner.html",
            "cache-manager" to "cache-manager.html",
            "banners" to "banners.html",
            "event-info" to "event-info.html",
            "chat" to "chat.html",
            "docs" to "docs.html",
            "contact" to "contact.html",
            "migration" to "migration.html",
            "config-migration" to "config-migration.html",
            "schema-history" to "schema-history.html",
            "theme-editor" to "theme-editor.html",
            "demo" to "demo.html",
            "about" to "about.html",
            "login" to "login.html",
            "shared-links" to "shared-links.html",
            "public-data" to "public-data.html",
            "tutorials" to "tutorials.html",
            "404" to "404.html",
            "500" to "500.html",
            "503" to "503.html"
        )

        get("/sw.js") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate, max-age=0")
            call.response.headers.append(HttpHeaders.Pragma, "no-cache")
            call.response.headers.append(HttpHeaders.Expires, "0")
            call.response.headers.append("Service-Worker-Allowed", "/")
            call.response.headers.append("CDN-Cache-Control", "no-store")
            call.response.headers.append("Cloudflare-CDN-Cache-Control", "no-store")
            val swBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val fsFile = File("static/sw.js")
                if (fsFile.exists()) fsFile.readBytes()
                else Thread.currentThread().contextClassLoader.getResourceAsStream("static/sw.js")?.readBytes()
            }
            if (swBytes != null) {
                val swText = swBytes.toString(Charsets.UTF_8)
                val appConfig = AppConfigLoader.load()
                val sanitizedVer = appConfig.current_version.replace(".", "-")
                val dynamicCacheConst = "const CACHE_NAME = 'obsidianscout-shell-v$sanitizedVer';"
                val patchedSw = swText.replace(
                    Regex("""const CACHE_NAME = ['\"][^'\"]+['\"];"""),
                    dynamicCacheConst
                )
                call.respondText(patchedSw, ContentType.parse("application/javascript; charset=UTF-8"))
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get("/") {
            call.respondStaticHtml("index.html")
        }

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

        // Prefer filesystem static/ folder (native binary deployment) for correct MIME types.
        // Fall back to classpath resources for fat-JAR deployment.
        val staticDir = File("static")
        if (staticDir.exists() && staticDir.isDirectory) {
            staticFiles("/", staticDir) {
                default("index.html")
            }
        } else {
            staticResources("/", "static") {
                default("index.html")
            }
        }
    }