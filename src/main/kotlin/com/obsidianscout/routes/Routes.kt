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
import com.obsidianscout.auth.requireSuperAdmin
import com.obsidianscout.auth.EmailService
import com.obsidianscout.db.PasswordResetTokens
import com.obsidianscout.db.PushSubscriptions
import com.obsidianscout.db.PushNotificationService
import com.obsidianscout.config.AppConfigLoader
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import java.time.Instant
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.SyncScheduler
import com.obsidianscout.integrations.SmtpSettings
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
import com.obsidianscout.utils.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.and
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ChatService
import com.obsidianscout.db.ChatMessages


fun Application.configureRoutes() {
    routing {
        route("/api") {
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
                        profilePicture = null,
                        notificationPreference = user.notificationPreference
                    )
                    call.attributes.put(com.obsidianscout.auth.KeepMeLoggedInSessionTransport.KEEP_ME_LOGGED_IN_KEY, request.keepMeLoggedIn)
                    call.sessions.set(session)

                    val responseSession = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture,
                        notificationPreference = user.notificationPreference
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
                        profilePicture = null,
                        notificationPreference = user.notificationPreference
                    )
                    call.attributes.put(com.obsidianscout.auth.KeepMeLoggedInSessionTransport.KEEP_ME_LOGGED_IN_KEY, request.keepMeLoggedIn)
                    call.sessions.set(session)

                    val responseSession = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture,
                        notificationPreference = user.notificationPreference
                    )
                    call.respond(LoginResponse(responseSession))
                }
                post("/logout") {
                    call.sessions.clear<UserSession>()
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/me") {
                    val session = call.requireSession()
                    val user = call.measure("user-db", "Get User DB Query") {
                        AuthService.getUserById(session.userId)
                    }
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "User not found")
                    val responseSession = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture,
                        notificationPreference = user.notificationPreference
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

                post("/forgot-password") {
                    val request = call.receive<ForgotPasswordRequest>()
                    val smtp = SettingsService.getSmtpSettings()
                    if (smtp.host.isBlank()) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.ServiceUnavailable,
                            "SMTP email settings are not configured. Please contact a superadmin."
                        )
                    }

                    val token = java.util.UUID.randomUUID().toString()
                    val expires = java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.HOURS)

                    val userEmail: String
                    val isEmailRecovery = !request.email.isNullOrBlank()

                    if (isEmailRecovery) {
                        val recoverEmail = request.email!!.trim()
                        val matchedUsers = transaction {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.email.lowerCase() eq recoverEmail.lowercase() }
                                .toList()
                        }
                        if (matchedUsers.isEmpty()) {
                            throw com.obsidianscout.auth.ApiException(
                                HttpStatusCode.NotFound,
                                "No accounts found with that email address."
                            )
                        }
                        userEmail = recoverEmail

                        transaction {
                            // Invalidate older tokens for this email
                            com.obsidianscout.db.PasswordResetTokens.update({ 
                                (com.obsidianscout.db.PasswordResetTokens.email.lowerCase() eq recoverEmail.lowercase()) and 
                                (com.obsidianscout.db.PasswordResetTokens.used eq false) 
                            }) {
                                it[used] = true
                            }

                            // Insert new token associated with email
                            com.obsidianscout.db.PasswordResetTokens.insert {
                                it[userId] = null
                                it[com.obsidianscout.db.PasswordResetTokens.email] = recoverEmail
                                it[com.obsidianscout.db.PasswordResetTokens.token] = token
                                it[expiresAt] = expires
                            }
                        }
                    } else {
                        // Recover by username + teamNumber
                        val username = request.username?.trim()
                        val teamNumber = request.teamNumber
                        if (username.isNullOrBlank() || teamNumber == null) {
                            throw com.obsidianscout.auth.ApiException(
                                HttpStatusCode.BadRequest,
                                "Username and team number or email is required."
                            )
                        }

                        val user = transaction {
                            com.obsidianscout.db.Users
                                .selectAll().where { 
                                    (com.obsidianscout.db.Users.username eq username) and 
                                    (com.obsidianscout.db.Users.teamNumber eq teamNumber) 
                                }
                                .limit(1)
                                .firstOrNull()
                        }

                        if (user == null) {
                            throw com.obsidianscout.auth.ApiException(
                                HttpStatusCode.NotFound,
                                "User not found on team."
                            )
                        }

                        val foundEmail = user[com.obsidianscout.db.Users.email]
                        if (foundEmail.isNullOrBlank()) {
                            throw com.obsidianscout.auth.ApiException(
                                HttpStatusCode.BadRequest,
                                "This account does not have a registered email address. Please contact your team admin."
                            )
                        }
                        userEmail = foundEmail
                        val userIdVal = user[com.obsidianscout.db.Users.id]

                        transaction {
                            // Invalidate older tokens for this user
                            com.obsidianscout.db.PasswordResetTokens.update({ 
                                (com.obsidianscout.db.PasswordResetTokens.userId eq userIdVal) and 
                                (com.obsidianscout.db.PasswordResetTokens.used eq false) 
                            }) {
                                it[used] = true
                            }

                            // Insert new token associated with user ID
                            com.obsidianscout.db.PasswordResetTokens.insert {
                                it[userId] = userIdVal
                                it[com.obsidianscout.db.PasswordResetTokens.email] = null
                                it[com.obsidianscout.db.PasswordResetTokens.token] = token
                                it[expiresAt] = expires
                            }
                        }
                    }

                    val referer = call.request.headers["Referer"]
                    val origin = call.request.headers["Origin"]
                    val baseUrl = when {
                        !origin.isNullOrBlank() -> origin.trimEnd('/')
                        !referer.isNullOrBlank() -> {
                            runCatching {
                                val uri = java.net.URI(referer)
                                "${uri.scheme}://${uri.authority}"
                            }.getOrNull()
                        }
                        else -> null
                    } ?: run {
                        val hostHeader = call.request.headers["X-Forwarded-Host"]
                            ?: call.request.headers["Host"]
                            ?: "localhost:8080"
                        val scheme = call.request.headers["X-Forwarded-Proto"] ?: "http"
                        "$scheme://$hostHeader"
                    }
                    
                    try {
                        EmailService.sendForgotPasswordEmail(
                            to = userEmail,
                            username = if (isEmailRecovery) userEmail else request.username!!,
                            teamNumber = if (isEmailRecovery) -1 else request.teamNumber!!,
                            token = token,
                            baseUrl = baseUrl
                        )
                    } catch (e: Exception) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.InternalServerError,
                            "Failed to send email: ${e.message}"
                        )
                    }

                    call.respond(mapOf("message" to "Password reset link sent to registered email."))
                }

                get("/verify-reset-token") {
                    val token = call.request.queryParameters["token"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing token")
                    
                    val tokenRow = transaction {
                        com.obsidianscout.db.PasswordResetTokens
                            .selectAll().where { 
                                (com.obsidianscout.db.PasswordResetTokens.token eq token) and 
                                (com.obsidianscout.db.PasswordResetTokens.used eq false) 
                            }
                            .limit(1)
                            .firstOrNull()
                    }

                    if (tokenRow == null) {
                        call.respond(VerifyResetTokenResponse(valid = false))
                        return@get
                    }

                    val expiresAt = tokenRow[com.obsidianscout.db.PasswordResetTokens.expiresAt]
                    if (expiresAt.isBefore(java.time.Instant.now())) {
                        call.respond(VerifyResetTokenResponse(valid = false))
                        return@get
                    }

                    val userIdVal = tokenRow[com.obsidianscout.db.PasswordResetTokens.userId]
                    val emailVal = tokenRow[com.obsidianscout.db.PasswordResetTokens.email]

                    val accounts = transaction {
                        if (userIdVal != null) {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.id eq userIdVal }
                                .map { AccountInfo(it[com.obsidianscout.db.Users.id].value, it[com.obsidianscout.db.Users.username], it[com.obsidianscout.db.Users.teamNumber]) }
                        } else if (!emailVal.isNullOrBlank()) {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.email.lowerCase() eq emailVal.lowercase() }
                                .map { AccountInfo(it[com.obsidianscout.db.Users.id].value, it[com.obsidianscout.db.Users.username], it[com.obsidianscout.db.Users.teamNumber]) }
                        } else {
                            emptyList()
                        }
                    }

                    if (accounts.isEmpty()) {
                        call.respond(VerifyResetTokenResponse(valid = false))
                        return@get
                    }

                    call.respond(VerifyResetTokenResponse(valid = true, accounts = accounts))
                }

                post("/reset-password") {
                    val request = call.receive<ResetPasswordRequest>()
                    
                    val tokenRow = transaction {
                        com.obsidianscout.db.PasswordResetTokens
                            .selectAll().where { 
                                (com.obsidianscout.db.PasswordResetTokens.token eq request.token) and 
                                (com.obsidianscout.db.PasswordResetTokens.used eq false) 
                            }
                            .limit(1)
                            .firstOrNull()
                    }

                    if (tokenRow == null) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid or expired reset token.")
                    }

                    val expiresAt = tokenRow[com.obsidianscout.db.PasswordResetTokens.expiresAt]
                    if (expiresAt.isBefore(java.time.Instant.now())) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid or expired reset token.")
                    }

                    val tokenUserId = tokenRow[com.obsidianscout.db.PasswordResetTokens.userId]
                    val tokenEmail = tokenRow[com.obsidianscout.db.PasswordResetTokens.email]

                    val finalUserId = if (tokenUserId != null) {
                        tokenUserId.value
                    } else if (!tokenEmail.isNullOrBlank()) {
                        val reqUserId = request.userId
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Account selection is required.")
                        
                        // Verify that the requested userId has the matching email address
                        val isValidAccount = transaction {
                            com.obsidianscout.db.Users
                                .selectAll().where { 
                                    (com.obsidianscout.db.Users.id eq reqUserId) and 
                                    (com.obsidianscout.db.Users.email.lowerCase() eq tokenEmail.lowercase()) 
                                }
                                .any()
                        }
                        if (!isValidAccount) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid account selected.")
                        }
                        reqUserId
                    } else {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid reset token.")
                    }
                    
                    transaction {
                        com.obsidianscout.auth.AuthService.updateUser(
                            callerSession = com.obsidianscout.auth.UserSession(
                                userId = finalUserId,
                                username = "SYSTEM",
                                teamNumber = 0,
                                role = com.obsidianscout.auth.UserRole.SUPERADMIN
                            ),
                            targetUserId = finalUserId,
                            newUsername = request.newUsername?.takeIf { it.isNotBlank() },
                            newPassword = request.newPassword,
                            newRole = null
                        )

                        com.obsidianscout.db.PasswordResetTokens.update({ 
                            com.obsidianscout.db.PasswordResetTokens.id eq tokenRow[com.obsidianscout.db.PasswordResetTokens.id] 
                        }) {
                            it[used] = true
                        }
                    }

                    call.respond(mapOf("message" to "Credentials have been reset successfully."))
                }
            }

            route("/docs") {
                get {
                    call.requireSession()
                    val lang = call.request.queryParameters["lang"]?.lowercase() ?: "en"
                    val docsDir = findDocsDir()
                    if (!docsDir.exists()) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Docs directory not found"))
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
                    call.requireSession()
                    val filename = call.parameters["filename"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing filename")
                    if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid filename")
                    }
                    val lang = call.request.queryParameters["lang"]?.lowercase() ?: "en"
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
                    val settings = call.measure("settings-db", "Settings DB Query") {
                        if (local) {
                            SettingsService.getSettings(session.teamNumber)
                        } else {
                            AllianceService.getEffectiveSettings(session.teamNumber)
                        }
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
                        val teamCount = com.obsidianscout.db.ApiTeams.selectAll().where { com.obsidianscout.db.ApiTeams.eventKey eq eventKey }.count().toInt()
                        val matchCount = com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.eventKey eq eventKey }.count().toInt()
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

            route("/alliance-selection") {
                get {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val response = com.obsidianscout.scouting.AllianceSelectionService.getSelection(session, eventKey)
                    call.respond(response)
                }
                post {
                    val session = call.requireSession()
                    val request = call.receive<com.obsidianscout.scouting.AllianceSelectionUpdateRequest>()
                    val response = com.obsidianscout.scouting.AllianceSelectionService.updateSelection(session, request)
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
                put {
                    call.requireAdmin()
                    val request = call.receive<EventRenameRequest>()
                    val updated = IntegrationService.renameEvent(request.oldKey, request.event)
                    call.respond(updated)
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
                    val eventKeyLower = eventKey.lowercase().trim()
                    val count = transaction {
                        com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.eventKey eq eventKeyLower }.count()
                    }
                    if (count == 0L) {
                        val settings = transaction { AllianceService.getEffectiveSettings(session.teamNumber) }
                        try {
                            IntegrationService.syncCustomEventData(settings, eventKeyLower)
                        } catch (e: Exception) {
                            // ignore or log
                        }
                    }
                    call.respond(IntegrationService.listMatches(eventKeyLower))
                }
                get("/predict") {
                    val session = call.requireSession()
                    val matchKey = call.request.queryParameters["matchKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing matchKey parameter")
                    val eventKey = call.request.queryParameters["eventKey"]
                    val forcePrescout = call.request.queryParameters["usePrescout"]?.toBoolean() ?: false
                    val prediction = PredictorService.predict(session, matchKey, forcePrescout, eventKey)
                    call.respond(prediction)
                }
                get("/predict-all") {
                    val session = call.requireSession()
                    val eventKey = call.request.queryParameters["eventKey"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing eventKey parameter")
                    val forcePrescout = call.request.queryParameters["usePrescout"]?.toBoolean() ?: false
                    val predictions = PredictorService.predictAll(session, eventKey, forcePrescout)
                    call.respond(predictions)
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
                    val summary = call.measure("summary-db", "Summary DB Query") {
                        IntegrationService.summary()
                    }
                    call.respond(summary)
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
                delete("/users/{id}") {
                    val session = call.requireAdmin()
                    val userId = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid user id")
                    AuthService.deleteUser(session, userId)
                    if (userId == session.userId) {
                        call.sessions.clear<UserSession>()
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/email-settings") {
                    call.requireSuperAdmin()
                    call.respond(SettingsService.getSmtpSettings())
                }

                put("/email-settings") {
                    call.requireSuperAdmin()
                    val smtp = call.receive<SmtpSettings>()
                    val saved = SettingsService.updateSmtpSettings(smtp)
                    call.respond(saved)
                }

                post("/email-settings/test") {
                    call.requireSuperAdmin()
                    val testReq = call.receive<SmtpTestConnectionRequest>()
                    val tempSmtp = SmtpSettings(
                        host = testReq.host,
                        port = testReq.port,
                        username = testReq.username,
                        passwordPlain = testReq.passwordPlain,
                        fromAddress = testReq.fromAddress,
                        encryption = testReq.encryption
                    )
                    try {
                        EmailService.sendEmailWithSettings(
                            to = testReq.testEmail,
                            subject = "ObsidianScout SMTP Test Connection",
                            body = "If you are reading this email, the SMTP configuration on ObsidianScout was successful!",
                            settings = tempSmtp
                        )
                        call.respond(mapOf("success" to true))
                    } catch (e: Exception) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.BadRequest,
                            "SMTP connection test failed: ${e.message}"
                        )
                    }
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
                    newEmail = request.email,
                    newProfilePicture = request.profilePicture,
                    clearProfilePicture = request.clearProfilePicture,
                    newNotificationPreference = request.notificationPreference
                )
                // Refresh the session so /api/auth/me returns the updated details
                val updatedSession = session.copy(
                    profilePicture = null,
                    email = updated.email,
                    notificationPreference = updated.notificationPreference
                )
                call.sessions.set(updatedSession)
                call.respond(updated)
            }

            delete("/user") {
                val session = call.requireSession()
                AuthService.deleteUser(session, session.userId)
                call.sessions.clear<UserSession>()
                call.respond(HttpStatusCode.NoContent)
            }

            route("/chat") {
                intercept(ApplicationCallPipeline.Plugins) {
                    val session = call.requireSession()
                    val settings = SettingsService.getSettings(session.teamNumber)
                    if (!settings.chatEnabled) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Chat is disabled by team admin")
                    }
                }
                get("/messages") {
                    val session = call.requireSession()
                    val groupName = call.request.queryParameters["group"] ?: "general"
                    val messages = ChatService.getMessages(session.teamNumber, groupName)
                    call.respond(messages)
                }
                post("/messages") {
                    val session = call.requireSession()
                    val request = call.receive<SendMessageRequest>()
                    val message = ChatService.sendMessage(
                        teamNumber = session.teamNumber,
                        groupName = request.groupName,
                        userId = session.userId,
                        username = session.username,
                        content = request.content
                    )
                    try {
                        PushNotificationService.sendChatNotification(message)
                    } catch (e: Exception) {
                        call.application.environment.log.error("Failed to trigger push notifications", e)
                    }
                    call.respond(message)
                }
                post("/messages/{id}/react") {
                    val session = call.requireSession()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid message id")
                    val request = call.receive<ReactMessageRequest>()
                    try {
                        val updated = ChatService.toggleReaction(id, session.username, request.emoji)
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Message not found")
                        call.respond(updated)
                    } catch (e: IllegalArgumentException) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, e.message ?: "Cannot react to your own message")
                    }
                }
                get("/groups") {
                    val session = call.requireSession()
                    val groups = ChatService.getGroups(session.teamNumber)
                    call.respond(groups)
                }
                get("/unread-status") {
                    val session = call.requireSession()
                    val status = ChatService.getUnreadStatus(session.userId, session.teamNumber, session.username)
                    call.respond(status)
                }
                post("/read") {
                    val session = call.requireSession()
                    val request = call.receive<ReadChatGroupRequest>()
                    ChatService.updateLastRead(session.userId, request.groupName)
                    call.respond(HttpStatusCode.OK)
                }
                get("/team-users") {
                    val session = call.requireSession()
                    val usernames = transaction {
                        com.obsidianscout.db.Users.selectAll()
                            .where { (com.obsidianscout.db.Users.teamNumber eq session.teamNumber) and (com.obsidianscout.db.Users.username neq "Deleted User") }
                            .map { it[com.obsidianscout.db.Users.username] }
                            .sorted()
                    }
                    call.respond(usernames)
                }
            }

            route("/push") {
                get("/public-key") {
                    val appConfig = AppConfigLoader.load()
                    call.respond(mapOf("publicKey" to appConfig.vapid.publicKey))
                }
                post("/subscribe") {
                    val session = call.requireSession()
                    val subscription = call.receive<PushSubscriptionDto>()
                    
                    transaction {
                        val existing = PushSubscriptions.selectAll()
                            .where { PushSubscriptions.endpoint eq subscription.endpoint }
                            .firstOrNull()
                        if (existing == null) {
                            PushSubscriptions.insert {
                                it[userId] = session.userId
                                it[endpoint] = subscription.endpoint
                                it[p256dh] = subscription.keys.p256dh
                                it[auth] = subscription.keys.auth
                                it[createdAt] = Instant.now()
                            }
                        } else {
                            PushSubscriptions.update({ PushSubscriptions.endpoint eq subscription.endpoint }) {
                                it[userId] = session.userId
                                it[p256dh] = subscription.keys.p256dh
                                it[auth] = subscription.keys.auth
                            }
                        }
                    }
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                }
            }


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
                    val alliance = call.measure("alliance-db", "Get Alliance Query") {
                        AllianceService.getAlliance(session, id)
                    }
                    call.respond(alliance)
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
                        val row = ScoutingAlliances.selectAll().where { ScoutingAlliances.id eq id }.firstOrNull()
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
                    val session = call.sessions.get<UserSession>() ?: return@webSocket this.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session")
                    )
                    val id = call.parameters["id"]?.toIntOrNull() ?: return@webSocket this.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid alliance ID")
                    )
                    val kind = call.parameters["kind"] ?: return@webSocket this.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid config kind")
                    )
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // Verify user is a member of this alliance
                        val isMember = transaction {
                            AllianceMemberships
                                .selectAll().where {
                                    (AllianceMemberships.allianceId eq id) and
                                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                                    (AllianceMemberships.status inList listOf("ADMIN", "ACCEPTED"))
                                }
                                .any()
                        }
                        if (!isMember) {
                            this@webSocket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not a member"))
                            return@withContext
                        }
                        
                        AllianceCollaborationManager.handleConnection(this@webSocket, id, kind, session)
                    }
                }
            }

            route("/banners") {
                get {
                    val session = call.requireSession()
                    val active = com.obsidianscout.db.BannerService.getActive(session.teamNumber)
                    call.respond(active)
                }
            }

            route("/admin/banners") {
                get {
                    val session = call.requireAdmin()
                    val all = if (session.role == com.obsidianscout.auth.UserRole.SUPERADMIN) {
                        com.obsidianscout.db.BannerService.getAll()
                    } else {
                        com.obsidianscout.db.BannerService.getAll(session.teamNumber)
                    }
                    call.respond(all)
                }
                post {
                    val session = call.requireAdmin()
                    val request = call.receive<com.obsidianscout.routes.BannerCreateRequest>()
                    
                    val targetTeam = request.teamNumber ?: 0
                    if (targetTeam == 0) {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can create sitewide banners")
                        }
                    } else {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && targetTeam != session.teamNumber) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only create banners for your own team")
                        }
                    }

                    val created = com.obsidianscout.db.BannerService.create(request)
                    call.respond(created)
                }
                put("/{id}") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<com.obsidianscout.routes.BannerUpdateRequest>()

                    val existing = com.obsidianscout.db.BannerService.getById(id)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Banner not found")

                    if (existing.teamNumber == 0) {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can modify sitewide banners")
                        }
                    } else {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && existing.teamNumber != session.teamNumber) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only modify banners for your own team")
                        }
                    }

                    val targetTeam = request.teamNumber
                    if (targetTeam != null) {
                        if (targetTeam == 0) {
                            if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                                throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can target banners sitewide")
                            }
                        } else {
                            if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && targetTeam != session.teamNumber) {
                                throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only target banners to your own team")
                            }
                        }
                    }

                    val updated = com.obsidianscout.db.BannerService.update(id, request)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Banner not found")
                    call.respond(updated)
                }
                delete("/{id}") {
                    val session = call.requireAdmin()
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")

                    val existing = com.obsidianscout.db.BannerService.getById(id)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Banner not found")

                    if (existing.teamNumber == 0) {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can delete sitewide banners")
                        }
                    } else {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && existing.teamNumber != session.teamNumber) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only delete banners for your own team")
                        }
                    }

                    val deleted = com.obsidianscout.db.BannerService.delete(id)
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Banner not found")
                    }
                }
            }
        }

        val pages = mapOf(
            "index" to "index.html",
            "reset-password" to "reset-password.html",
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
            "event-predictor" to "event-predictor.html",
            "alliances" to "alliances.html",
            "alliance-edit" to "alliance-edit.html",
            "alliance-selection" to "alliance-selection.html",
            "users" to "users.html",
            "config" to "config.html",
            "qr-scanner" to "qr-scanner.html",
            "cache-manager" to "cache-manager.html",
            "banners" to "banners.html",
            "chat" to "chat.html",
            "docs" to "docs.html"
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
    val (html, sidebar) = measureSuspend("load-html", "Load HTML from Resource") {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val resource = Thread.currentThread().contextClassLoader.getResource("static/$fileName")
            val htmlContent = resource?.readText()
            val sidebarContent = Thread.currentThread().contextClassLoader
                .getResource("static/base.html")
                ?.readText()
                ?.trim()
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
        chatEnabled = chatEnabled,
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
        chatEnabled = chatEnabled,
        apiKeys = com.obsidianscout.integrations.ApiKeys(
            tbaKey = apiKeys.tbaKey,
            firstUsername = apiKeys.firstUsername,
            firstKey = apiKeys.firstKey
        )
    )
}

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
