package com.obsidianscout.routes

import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.analytics.AnalyticsService
import com.obsidianscout.analytics.PredictorService
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.requireAdmin
import com.obsidianscout.auth.requireAdminOrClusterAuth
import com.obsidianscout.auth.requireAnalyticsOrAbove
import com.obsidianscout.auth.requireSession
import com.obsidianscout.auth.requireSuperAdmin
import com.obsidianscout.auth.requireSuperAdminOrClusterAuth
import com.obsidianscout.auth.EmailService
import com.obsidianscout.db.PasswordResetTokens
import com.obsidianscout.db.PushSubscriptions
import com.obsidianscout.db.PushNotificationService
import com.obsidianscout.db.FcmService
import com.obsidianscout.config.AppConfigLoader
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.and


import org.jetbrains.exposed.sql.lowerCase
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytes
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.http.content.streamProvider
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
            get("/version") {
                val appConfig = AppConfigLoader.load()
                call.respond(VersionResponse(appConfig.current_version, com.obsidianscout.admin.ClusterManagementService.getLocalExecutionMode()))
            }
            route("/cluster") {
                get("/status") {
                    val appConfig = AppConfigLoader.load()
                    call.respond(
                        buildJsonObject {
                            put("status", if (com.obsidianscout.db.DatabaseFactory.isReady) "online" else "booting")
                            put("dbReady", com.obsidianscout.db.DatabaseFactory.isReady)
                            put("isDbActive", com.obsidianscout.db.orchestration.CockroachOrchestrator.isDbActive)
                            put("isQuorumLost", com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost)
                            put("quorumDetails", com.obsidianscout.db.orchestration.CockroachOrchestrator.quorumLossDetails ?: "")
                            put("serverVersion", appConfig.current_version)
                            put("executionMode", com.obsidianscout.admin.ClusterManagementService.getLocalExecutionMode())
                            put("nodeIp", com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp())
                        }
                    )
                }
                get("/time") {
                    call.respond(
                        buildJsonObject {
                            put("currentTimeMillis", System.currentTimeMillis())
                        }
                    )
                }
                get("/probe-node") {
                    val targetIp = call.request.queryParameters["targetIp"] ?: ""
                    val appPort = call.request.queryParameters["appPort"]?.toIntOrNull() ?: 8080
                    val dbPort = call.request.queryParameters["dbPort"]?.toIntOrNull() ?: 26257

                    if (targetIp.isBlank()) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing targetIp parameter")
                    }

                    val isOnline = com.obsidianscout.admin.ClusterManagementService.probeNodeFromPeer(targetIp, appPort, dbPort)
                    call.respond(
                        buildJsonObject {
                            put("targetIp", targetIp)
                            put("isOnline", isOnline)
                            put("probedBy", com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp())
                        }
                    )
                }
            }
            route("/auth") {
                post("/login") {
                    val request = call.receive<LoginRequest>()
                    val user = AuthService.login(
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password,
                        program = request.program
                    ) ?: throw com.obsidianscout.auth.ApiException(
                        HttpStatusCode.Unauthorized,
                        "Invalid credentials"
                    )

                    val session = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        program = user.program,
                        role = user.role,
                        email = user.email,
                        profilePicture = null,
                        notificationPreference = user.notificationPreference,
                        tourProgress = user.tourProgress,
                        nodeAlertsEnabled = user.nodeAlertsEnabled
                    )
                    call.attributes.put(com.obsidianscout.auth.KeepMeLoggedInSessionTransport.KEEP_ME_LOGGED_IN_KEY, request.keepMeLoggedIn)
                    call.sessions.set(session)

                    val responseSession = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        program = user.program,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture,
                        notificationPreference = user.notificationPreference,
                        tourProgress = user.tourProgress,
                        nodeAlertsEnabled = user.nodeAlertsEnabled
                    )
                    call.respond(LoginResponse(responseSession))
                }
                post("/register") {
                    val request = call.receive<RegisterRequest>()
                    val user = AuthService.register(
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password,
                        program = request.program,
                        role = request.role,
                        email = request.email
                    )
                    val session = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        program = user.program,
                        role = user.role,
                        email = user.email,
                        profilePicture = null,
                        notificationPreference = user.notificationPreference,
                        tourProgress = user.tourProgress,
                        nodeAlertsEnabled = user.nodeAlertsEnabled
                    )
                    call.attributes.put(com.obsidianscout.auth.KeepMeLoggedInSessionTransport.KEEP_ME_LOGGED_IN_KEY, request.keepMeLoggedIn)
                    call.sessions.set(session)

                    val responseSession = UserSession(
                        userId = user.id,
                        username = user.username,
                        teamNumber = user.teamNumber,
                        program = user.program,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture,
                        notificationPreference = user.notificationPreference,
                        tourProgress = user.tourProgress,
                        nodeAlertsEnabled = user.nodeAlertsEnabled
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
                        program = user.program,
                        role = user.role,
                        email = user.email,
                        profilePicture = user.profilePicture,
                        notificationPreference = user.notificationPreference,
                        tourProgress = user.tourProgress,
                        nodeAlertsEnabled = user.nodeAlertsEnabled
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
                                .map { AccountInfo(it[com.obsidianscout.db.Users.id].value.toString(), it[com.obsidianscout.db.Users.username], it[com.obsidianscout.db.Users.teamNumber]) }
                        } else if (!emailVal.isNullOrBlank()) {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.email.lowerCase() eq emailVal.lowercase() }
                                .map { AccountInfo(it[com.obsidianscout.db.Users.id].value.toString(), it[com.obsidianscout.db.Users.username], it[com.obsidianscout.db.Users.teamNumber]) }
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
                        tokenUserId.value.toString()
                    } else if (!tokenEmail.isNullOrBlank()) {
                        val reqUserId = request.userId
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Account selection is required.")
                        val reqUuid = runCatching { UUID.fromString(reqUserId) }.getOrElse {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid user ID format.")
                        }
                        // Verify that the requested userId has the matching email address
                        val isValidAccount = transaction {
                            com.obsidianscout.db.Users
                                .selectAll().where { 
                                    (com.obsidianscout.db.Users.id eq reqUuid) and 
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
                get("/defaults") {
                    val session = call.requireSession()
                    val type = call.request.queryParameters["type"]
                    val presets = ConfigService.getDefaultConfigs(session.program, type)
                    call.respond(presets)
                }
                post("/apply-default") {
                    val session = call.requireAdmin()
                    val request = call.receive<ApplyDefaultConfigRequest>()
                    val updated = ConfigService.applyDefaultConfig(session.teamNumber, session.program, request.configType, request.presetName)
                    call.respond(updated)
                }
                post("/reset") {
                    val session = call.requireAdmin()
                    val request = call.receive<ResetConfigRequest>()
                    val updated = ConfigService.resetToDefaultConfig(session.teamNumber, session.program, request.configType)
                    call.respond(updated)
                }
                get {
                    val session = call.requireSession()
                    val local = call.request.queryParameters["local"]?.toBoolean() ?: false
                    // Return JSON where any string `label` values are wrapped into { "en": "..." }
                    val raw = ConfigService.getConfigJson(session.teamNumber, session.program, local)
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
                    val updated = ConfigService.updateConfig(session.teamNumber, session.program, request.configJson)
                    call.respond(updated)
                }
            }

            route("/pit-config") {
                get {
                    val session = call.requireSession()
                    val local = call.request.queryParameters["local"]?.toBoolean() ?: false
                    val raw = ConfigService.getPitConfigJson(session.teamNumber, session.program, local)
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
                    val updated = ConfigService.updatePitConfig(session.teamNumber, session.program, request.configJson)
                    call.respond(updated)
                }
            }

            route("/qual-config") {
                get {
                    val session = call.requireSession()
                    val local = call.request.queryParameters["local"]?.toBoolean() ?: false
                    val raw = ConfigService.getQualitativeConfigJson(session.teamNumber, session.program, local)
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
                    val updated = ConfigService.updateQualitativeConfig(session.teamNumber, session.program, request.configJson)
                    call.respond(updated)
                }
            }

            route("/settings") {
                get {
                    val session = call.requireSession()
                    val local = call.request.queryParameters["local"]?.toBoolean() ?: false
                    val settings = call.measure("settings-db", "Settings DB Query") {
                        if (local) {
                            SettingsService.getSettings(session.teamNumber, session.program)
                        } else {
                            AllianceService.getEffectiveSettings(session.teamNumber, session.program)
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
                post("/test-api") {
                    val session = call.requireAdmin()
                    val request = call.receive<TestApiRequest>()
                    val response = IntegrationService.testApiKey(session, request)
                    call.respond(response)
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
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getConfig(session.teamNumber)
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
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getPitConfig(session.teamNumber)
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
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<ScoutingEntryRequest>()
                    val config = ConfigService.getQualitativeConfig(session.teamNumber)
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
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val (cachedTeams, cachedMatches) = transaction {
                        val teamCount = com.obsidianscout.db.ApiTeams.selectAll().where { com.obsidianscout.db.ApiTeams.eventKey eq eventKey }.count().toInt()
                        val matchCount = com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.eventKey eq eventKey }.count().toInt()
                        Pair(teamCount, matchCount)
                    }
                    com.obsidianscout.integrations.SyncScheduler.enqueueCustomEventDataSync(session.teamNumber, settings, eventKey)
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

                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val activeKey = settings.resolvedEventKey()

                    val events = IntegrationService.listEvents(year, cachedOnly, activeKey, settings, session = session)
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
                        ?: AllianceService.getEffectiveSettings(session.teamNumber, session.program).resolvedEventKey()
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
                        ?: AllianceService.getEffectiveSettings(session.teamNumber, session.program).resolvedEventKey()
                    val eventKeyLower = eventKey.lowercase().trim()
                    val count = transaction {
                        com.obsidianscout.db.ApiMatches.selectAll().where { com.obsidianscout.db.ApiMatches.eventKey eq eventKeyLower }.count()
                    }
                    if (count == 0L) {
                        val settings = transaction { AllianceService.getEffectiveSettings(session.teamNumber, session.program) }
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
                    val session = call.requireSession()
                    val status = SyncScheduler.getStatusForTeam(session.teamNumber, session.program)
                    call.respond(
                        SyncStatusResponse(
                            intervalMinutes = SyncScheduler.INTERVAL_MS / 60_000.0,
                            lastSyncAt = status.lastSyncAt?.toString(),
                            lastSyncSummary = status.lastSyncSummary,
                            lastSyncError = status.lastSyncError,
                            lastSyncTeams = status.lastSyncTeams,
                            lastSyncMatches = status.lastSyncMatches,
                            lastSyncTeamCount = status.lastSyncTeamCount,
                            lastSyncFailedTeams = status.lastSyncFailedTeams,
                            syncInProgress = status.syncInProgress,
                            currentSyncLabel = status.currentSyncLabel
                        )
                    )
                }
                post("/sync/events") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val queued = SyncScheduler.enqueueEventSync(session.teamNumber, settings)
                    if (queued) {
                        call.respond(HttpStatusCode.Accepted, SyncResponse(0, settings.preferredSource, settings.resolvedEventKey(), queued, "Event sync started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sync is already running for team ${session.teamNumber}"))
                    }
                }
                post("/sync/event") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val queued = SyncScheduler.enqueueEventDataSync(session.teamNumber, settings)
                    if (queued) {
                        call.respond(HttpStatusCode.Accepted, SyncResponse(0, settings.preferredSource, settings.resolvedEventKey(), queued, "Teams and matches sync started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sync is already running for team ${session.teamNumber}"))
                    }
                }
                post("/sync/stats") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val queued = SyncScheduler.enqueueStatsSync(session.teamNumber, settings)
                    if (queued) {
                        call.respond(HttpStatusCode.Accepted, SyncResponse(0, "stats", settings.resolvedEventKey(), queued, "Stats sync started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sync is already running for team ${session.teamNumber}"))
                    }
                }
                post("/sync/all") {
                    val session = call.requireAdmin()
                    val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                    val queued = SyncScheduler.enqueueFullSync(session.teamNumber, settings)
                    if (queued) {
                        call.respond(HttpStatusCode.Accepted, SyncResponse(0, "all", settings.resolvedEventKey(), queued, "Full sync started"))
                    } else {
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "Sync is already running for team ${session.teamNumber}"))
                    }
                }
            }

            route("/admin") {
                get("/users") {
                    val session = call.requireAdmin()
                    val q = call.request.queryParameters["q"]
                    val teamNumber = call.request.queryParameters["teamNumber"]?.toIntOrNull()
                    val program = call.request.queryParameters["program"]
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
                            programFilter = program,
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
                        program = request.program,
                        role = request.role,
                        email = request.email
                    )
                    call.respond(user)
                }
                put("/users/{id}") {
                    val session = call.requireAdmin()
                    val userId = call.parameters["id"]
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
                    val userId = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid user id")
                    AuthService.deleteUser(session, userId)
                    if (userId == session.userId) {
                        call.sessions.clear<UserSession>()
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/email-settings") {
                    call.requireSuperAdmin()
                    val smtp = SettingsService.getSmtpSettings()
                    call.respond(smtp.copy(passwordPlain = if (smtp.passwordPlain.isNotBlank()) "********" else ""))
                }

                put("/email-settings") {
                    call.requireSuperAdmin()
                    val smtp = call.receive<SmtpSettings>()
                    val existing = SettingsService.getSmtpSettings()
                    val merged = if (smtp.passwordPlain == "********") {
                        smtp.copy(passwordPlain = existing.passwordPlain)
                    } else {
                        smtp
                    }
                    val saved = SettingsService.updateSmtpSettings(merged)
                    call.respond(saved.copy(passwordPlain = if (saved.passwordPlain.isNotBlank()) "********" else ""))
                }

                get("/cloudflared") {
                    call.requireSuperAdmin()
                    try {
                        val settings = SettingsService.getCloudflaredSettings()
                        val status = CloudflaredService.getStatus()
                        val safeSettings = settings.copy(tunnelToken = if (settings.tunnelToken.isNotBlank()) "********" else "")
                        call.respond(com.obsidianscout.admin.CloudflaredResponse(safeSettings, status))
                    } catch (e: com.obsidianscout.auth.ApiException) {
                        throw e
                    } catch (e: Throwable) {
                        call.application.environment.log.error("Failed to get Cloudflare status", e)
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.InternalServerError, "Failed to get Cloudflare status: ${e.message}")
                    }
                }

                put("/cloudflared") {
                    call.requireSuperAdmin()
                    try {
                        val newSettings = call.receive<CloudflaredSettings>()
                        val existing = SettingsService.getCloudflaredSettings()
                        val merged = if (newSettings.tunnelToken == "********") {
                            newSettings.copy(tunnelToken = existing.tunnelToken)
                        } else {
                            newSettings
                        }
                        val status = CloudflaredService.updateSettingsAndApply(merged)
                        val saved = SettingsService.getCloudflaredSettings()
                        val safeSettings = saved.copy(tunnelToken = if (saved.tunnelToken.isNotBlank()) "********" else "")
                        call.respond(com.obsidianscout.admin.CloudflaredResponse(safeSettings, status))
                    } catch (e: com.obsidianscout.auth.ApiException) {
                        throw e
                    } catch (e: Throwable) {
                        call.application.environment.log.error("Failed to update Cloudflare settings", e)
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.InternalServerError, "Failed to update Cloudflare settings: ${e.message}")
                    }
                }

                post("/cloudflared/restart") {
                    call.requireSuperAdmin()
                    try {
                        val status = CloudflaredService.startTunnel()
                        call.respond(com.obsidianscout.admin.CloudflaredRestartResponse(true, status))
                    } catch (e: com.obsidianscout.auth.ApiException) {
                        throw e
                    } catch (e: Throwable) {
                        call.application.environment.log.error("Failed to restart Cloudflare tunnel", e)
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.InternalServerError, "Failed to restart Cloudflare tunnel: ${e.message}")
                    }
                }

                get("/migration/status") {
                    call.requireSuperAdmin()
                    call.respond(com.obsidianscout.db.MigrationService.getStatus())
                }

                post("/migration/run") {
                    call.requireSuperAdmin()
                    val req = call.receive<MigrationRequest>()
                    com.obsidianscout.db.MigrationService.startMigration(
                        sourceType = req.sourceType,
                        sqliteInstancePath = req.sqliteInstancePath,
                        pgConfig = req.pgConfig
                    )
                    call.respond(mapOf("success" to true))
                }

                post("/reset-database") {
                    val session = call.requireSuperAdmin()
                    val req = call.receive<ResetDatabaseRequest>()
                    
                    val userRecord = transaction {
                        val uuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
                        if (uuid != null) {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.id eq uuid }
                                .limit(1)
                                .firstOrNull()
                        } else null
                    } ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "Superadmin user not found")

                    val hash = userRecord[com.obsidianscout.db.Users.passwordHash]
                    val verified = at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(req.password.toCharArray(), hash).verified
                    if (!verified) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "Invalid password")
                    }

                    val originalId = session.userId
                    val originalUsername = userRecord[com.obsidianscout.db.Users.username]
                    val originalTeamNumber = userRecord[com.obsidianscout.db.Users.teamNumber]
                    val originalPasswordHash = userRecord[com.obsidianscout.db.Users.passwordHash]
                    val originalRole = userRecord[com.obsidianscout.db.Users.role]
                    val originalEmail = userRecord[com.obsidianscout.db.Users.email]
                    val originalProfilePicture = userRecord[com.obsidianscout.db.Users.profilePicture]
                    val originalNotificationPreference = userRecord[com.obsidianscout.db.Users.notificationPreference]

                    transaction {
                        org.jetbrains.exposed.sql.SchemaUtils.drop(
                            com.obsidianscout.db.Users,
                            com.obsidianscout.db.ScoutingConfigs,
                            com.obsidianscout.db.PitScoutingConfigs,
                            com.obsidianscout.db.QualitativeScoutingConfigs,
                            com.obsidianscout.db.ScoutingEntries,
                            com.obsidianscout.db.PitScoutingEntries,
                            com.obsidianscout.db.QualitativeScoutingEntries,
                            com.obsidianscout.db.AppSettings,
                            com.obsidianscout.db.ApiEvents,
                            com.obsidianscout.db.ApiTeams,
                            com.obsidianscout.db.ApiMatches,
                            com.obsidianscout.db.ScoutingAlliances,
                            com.obsidianscout.db.AllianceMemberships,
                            com.obsidianscout.db.EpaOprHistoryCache,
                            com.obsidianscout.db.PasswordResetTokens,
                            com.obsidianscout.db.AllianceSelections,
                            com.obsidianscout.db.Banners,
                            com.obsidianscout.db.ChatMessages,
                            com.obsidianscout.db.UserChatLastRead,
                            com.obsidianscout.db.PushSubscriptions
                        )

                        org.jetbrains.exposed.sql.SchemaUtils.create(
                            com.obsidianscout.db.Users,
                            com.obsidianscout.db.ScoutingConfigs,
                            com.obsidianscout.db.PitScoutingConfigs,
                            com.obsidianscout.db.QualitativeScoutingConfigs,
                            com.obsidianscout.db.ScoutingEntries,
                            com.obsidianscout.db.PitScoutingEntries,
                            com.obsidianscout.db.QualitativeScoutingEntries,
                            com.obsidianscout.db.AppSettings,
                            com.obsidianscout.db.ApiEvents,
                            com.obsidianscout.db.ApiTeams,
                            com.obsidianscout.db.ApiMatches,
                            com.obsidianscout.db.ScoutingAlliances,
                            com.obsidianscout.db.AllianceMemberships,
                            com.obsidianscout.db.EpaOprHistoryCache,
                            com.obsidianscout.db.PasswordResetTokens,
                            com.obsidianscout.db.AllianceSelections,
                            com.obsidianscout.db.Banners,
                            com.obsidianscout.db.ChatMessages,
                            com.obsidianscout.db.UserChatLastRead,
                            com.obsidianscout.db.PushSubscriptions
                        )

                        com.obsidianscout.db.Users.insert {
                            it[id] = EntityID(UUID.fromString(originalId), com.obsidianscout.db.Users)
                            it[username] = originalUsername
                            it[teamNumber] = originalTeamNumber
                            it[passwordHash] = originalPasswordHash
                            it[role] = originalRole
                            it[email] = originalEmail
                            it[profilePicture] = originalProfilePicture
                            it[notificationPreference] = originalNotificationPreference
                            it[createdAt] = Instant.now()
                        }
                    }

                    call.respond(mapOf("success" to true))
                }

                post("/wipe-team-data") {
                    val session = call.requireAdmin()
                    val req = call.receive<WipeTeamDataRequest>()
                    
                    val userRecord = transaction {
                        val uuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
                        if (uuid != null) {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.id eq uuid }
                                .limit(1)
                                .firstOrNull()
                        } else null
                    } ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "User not found")

                    val hash = userRecord[com.obsidianscout.db.Users.passwordHash]
                    val verified = at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(req.password.toCharArray(), hash).verified
                    if (!verified) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "Invalid password")
                    }

                    transaction {
                        // Delete ALL scouting entries completely
                        com.obsidianscout.db.ScoutingEntries.deleteWhere { com.obsidianscout.db.ScoutingEntries.id.isNotNull() }
                        com.obsidianscout.db.PitScoutingEntries.deleteWhere { com.obsidianscout.db.PitScoutingEntries.id.isNotNull() }
                        com.obsidianscout.db.QualitativeScoutingEntries.deleteWhere { com.obsidianscout.db.QualitativeScoutingEntries.id.isNotNull() }

                        
                        // Delete ALL cached global events, teams, matches, stats, and selection data
                        com.obsidianscout.db.ApiEvents.deleteWhere { com.obsidianscout.db.ApiEvents.id.isNotNull() }
                        com.obsidianscout.db.ApiTeams.deleteWhere { com.obsidianscout.db.ApiTeams.id.isNotNull() }
                        com.obsidianscout.db.ApiMatches.deleteWhere { com.obsidianscout.db.ApiMatches.id.isNotNull() }
                        com.obsidianscout.db.EpaOprHistoryCache.deleteWhere { com.obsidianscout.db.EpaOprHistoryCache.id.isNotNull() }
                        com.obsidianscout.db.AllianceSelections.deleteWhere { com.obsidianscout.db.AllianceSelections.id.isNotNull() }


                        // Clear the active event and setup wizard state in the settings for this team
                        val settings = SettingsService.getSettings(session.teamNumber)
                        val clearedSettings = settings.copy(
                            eventCode = "",
                            eventKey = "",
                            setupWizardCompleted = false
                        )
                        SettingsService.updateSettings(session.teamNumber, clearedSettings)
                    }
                    
                    call.respond(mapOf("success" to true))
                }




                post("/email-settings/test") {
                    call.requireSuperAdmin()
                    val testReq = call.receive<SmtpTestConnectionRequest>()
                    val existing = SettingsService.getSmtpSettings()
                    val resolvedPassword = if (testReq.passwordPlain == "********") {
                        existing.passwordPlain
                    } else {
                        testReq.passwordPlain
                    }
                    val tempSmtp = SmtpSettings(
                        host = testReq.host,
                        port = testReq.port,
                        username = testReq.username,
                        passwordPlain = resolvedPassword,
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

                get("/export") {
                    val session = call.requireAdmin()
                    val type = call.request.queryParameters["type"] ?: "scouting"
                    val format = call.request.queryParameters["format"] ?: "obsidiandb"
                    val requestedScope = call.request.queryParameters["scope"] ?: "team"

                    val isSuperAdmin = session.role == com.obsidianscout.auth.UserRole.SUPERADMIN
                    val scope = if (requestedScope == "global") {
                        if (!isSuperAdmin) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can perform global exports")
                        }
                        "global"
                    } else {
                        "team"
                    }

                    if (format == "obsidiandb") {
                        val backup = com.obsidianscout.db.BackupService.exportBackup(session.teamNumber, type, scope)
                        val jsonString = JsonSupport.json.encodeToString(com.obsidianscout.db.ObsidianDbBackup.serializer(), backup)
                        val filename = if (scope == "global") "global_backup_${type}.obsidiandb" else "team_${session.teamNumber}_backup_${type}.obsidiandb"
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
                        call.respondText(jsonString, ContentType.Application.Json)
                    } else if (format == "csv") {
                        val zipBytes = com.obsidianscout.db.BackupService.exportCsv(session.teamNumber, type, scope)
                        val filename = if (scope == "global") "global_backup_${type}.zip" else "team_${session.teamNumber}_backup_${type}.zip"
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
                        call.respondBytes(zipBytes, ContentType.Application.Zip)
                    } else {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Unsupported format: $format")
                    }
                }

                post("/import") {
                    val session = call.requireAdmin()
                    val requestedScope = call.request.queryParameters["scope"] ?: "team"
                    val isSuperAdmin = session.role == com.obsidianscout.auth.UserRole.SUPERADMIN

                    if (requestedScope == "global" && !isSuperAdmin) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can perform global imports")
                    }

                    val multipart = call.receiveMultipart()
                    var fileBytes: ByteArray? = null
                    var fileName = ""
                    while (true) {
                        val part = multipart.readPart() ?: break
                        if (part is PartData.FileItem) {
                            fileBytes = part.streamProvider().readBytes()
                            fileName = part.originalFileName ?: ""
                        }
                        part.dispose()
                    }

                    if (fileBytes == null) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "No file uploaded")
                    }

                    val report = if (fileName.endsWith(".obsidiandb") || fileName.endsWith(".json")) {
                        val jsonString = String(fileBytes!!, Charsets.UTF_8)
                        val backup = JsonSupport.json.decodeFromString<com.obsidianscout.db.ObsidianDbBackup>(jsonString)
                        val importAsGlobal = isSuperAdmin && requestedScope == "global" && backup.scope == "global"
                        com.obsidianscout.db.BackupService.importBackup(session.teamNumber, backup, session.userId, importAsGlobal)
                    } else if (fileName.endsWith(".zip")) {
                        val importAsGlobal = isSuperAdmin && requestedScope == "global"
                        com.obsidianscout.db.BackupService.importCsv(session.teamNumber, fileBytes!!, session.userId, importAsGlobal)
                    } else {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Unsupported file format. Please upload .obsidiandb or .zip")
                    }

                    call.respond(report)
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
                    newNotificationPreference = request.notificationPreference,
                    newNodeAlertsEnabled = if (session.role == UserRole.SUPERADMIN) request.nodeAlertsEnabled else null
                )
                // Refresh the session so /api/auth/me returns the updated details
                val updatedSession = session.copy(
                    profilePicture = null,
                    email = updated.email,
                    notificationPreference = updated.notificationPreference,
                    tourProgress = updated.tourProgress,
                    nodeAlertsEnabled = updated.nodeAlertsEnabled
                )
                call.sessions.set(updatedSession)
                call.respond(updated)
            }

            get("/user/tour-progress") {
                val session = call.requireSession()
                val user = AuthService.getUserById(session.userId)
                    ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "User not found")
                val rawProgress = user.tourProgress ?: "{}"
                call.respondText(rawProgress, ContentType.Application.Json)
            }

            post("/user/tour-progress") {
                val session = call.requireSession()
                val request = call.receive<String>()
                val updated = AuthService.updateUser(
                    callerSession = session,
                    targetUserId = session.userId,
                    newUsername = null,
                    newPassword = null,
                    newRole = null,
                    newEmail = null,
                    newProfilePicture = null,
                    clearProfilePicture = false,
                    newNotificationPreference = null,
                    newTourProgress = request
                )
                val updatedSession = session.copy(
                    tourProgress = updated.tourProgress
                )
                call.sessions.set(updatedSession)
                call.respond(HttpStatusCode.OK, mapOf("message" to "Tour progress updated"))
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
                    val id = call.parameters["id"]
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
                post("/groups") {
                    val session = call.requireSession()
                    val request = call.receive<CreateGroupRequest>()
                    val success = ChatService.createGroup(session.teamNumber, request.groupName, session.userId)
                    call.respond(buildJsonObject {
                        put("success", success)
                        put("groupName", request.groupName)
                    })
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

            webSocket("/ws/notifications") {
                val session = call.sessions.get<UserSession>() ?: return@webSocket this.close(
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
                    val configJson = transaction {
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
                webSocket("/{id}/collaborate/{kind}") {
                    val session = call.sessions.get<UserSession>() ?: return@webSocket this.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session")
                    )
                    val id = call.parameters["id"] ?: return@webSocket this.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid alliance ID")
                    )
                    val idUuid = runCatching { UUID.fromString(id) }.getOrElse {
                        return@webSocket this.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid alliance ID"))
                    }
                    val kind = call.parameters["kind"] ?: return@webSocket this.close(
                        CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid config kind")
                    )
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // Verify user is a member of this alliance
                        val isMember = transaction {
                            AllianceMemberships
                                .selectAll().where {
                                    (AllianceMemberships.allianceId eq idUuid) and
                                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                                    (AllianceMemberships.status inList listOf("ADMIN", "ACCEPTED"))
                                }
                                .any()
                        }
                        if (!isMember) {
                            this@webSocket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not a member"))
                            return@withContext
                        }
                        
                        AllianceCollaborationManager.handleConnection(this@webSocket, idUuid, kind, session)
                    }
                }
            }

            route("/banners") {
                get("/login") {
                    val loginBanners = com.obsidianscout.db.BannerService.getLoginBanners()
                    call.respond(loginBanners)
                }
                get {
                    val session = call.requireSession()
                    val active = mutableListOf<BannerDto>()
                    val orchestrator = com.obsidianscout.db.orchestration.CockroachOrchestrator

                    if (orchestrator.isQuorumLost) {
                        active.add(
                            BannerDto(
                                id = "sys-db-quorum-lost",
                                teamNumber = session.teamNumber,
                                message = "🚨 Database Quorum Lost: CockroachDB cluster has lost quorum (majority of nodes offline). Database read/write operations are temporarily restricted until quorum is restored.",
                                bannerType = "danger",
                                isDismissible = false,
                                isExpandable = true,
                                expandableMessage = orchestrator.quorumLossDetails ?: "CockroachDB requires a majority consensus of nodes to execute database transactions safely. The cluster is currently under-quorum. Full operation will resume automatically when peer nodes reconnect.",
                                isActive = true,
                                createdAt = java.time.Instant.now().toString(),
                                updatedAt = java.time.Instant.now().toString()
                            )
                        )
                    }

                    try {
                        val dbBanners = com.obsidianscout.db.BannerService.getActive(session.teamNumber)
                        active.addAll(dbBanners)

                        val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                        val eventKey = settings.resolvedEventKey()
                        if (eventKey.isBlank()) {
                            active.add(
                                0,
                                com.obsidianscout.routes.BannerDto(
                                    id = "sys-no-event-key",
                                    teamNumber = session.teamNumber,
                                    message = "No Event Key is currently configured for your team. Please configure an Event Key in Settings.",
                                    bannerType = "warning",
                                    isDismissible = true,
                                    isExpandable = false,
                                    expandableMessage = "",
                                    isActive = true,
                                    createdAt = java.time.Instant.now().toString(),
                                    updatedAt = java.time.Instant.now().toString()
                                )
                            )
                        }

                        val keys = settings.apiKeys
                        val isFtc = session.program.equals("FTC", ignoreCase = true)
                        val hasApi = if (isFtc) {
                            true
                        } else {
                            keys.tbaKey.isNotBlank() || (keys.firstUsername.isNotBlank() && keys.firstKey.isNotBlank())
                        }
                        if (!hasApi) {
                            active.add(
                                0,
                                com.obsidianscout.routes.BannerDto(
                                    id = "sys-no-api-key",
                                    teamNumber = session.teamNumber,
                                    message = "No API Key is currently configured for your team. External data syncing (The Blue Alliance / FIRST) is disabled.",
                                    bannerType = "warning",
                                    isDismissible = true,
                                    isExpandable = false,
                                    expandableMessage = "",
                                    isActive = true,
                                    createdAt = java.time.Instant.now().toString(),
                                    updatedAt = java.time.Instant.now().toString()
                                )
                            )
                        }
                    } catch (e: Exception) {
                        if (orchestrator.isQuorumLossException(e)) {
                            orchestrator.isQuorumLost = true
                            if (!active.any { it.id == "sys-db-quorum-lost" }) {
                                active.add(
                                    0,
                                    BannerDto(
                                        id = "sys-db-quorum-lost",
                                        teamNumber = session.teamNumber,
                                        message = "🚨 Database Quorum Lost: CockroachDB cluster has lost quorum (majority of nodes offline). Database read/write operations are temporarily restricted until quorum is restored.",
                                        bannerType = "danger",
                                        isDismissible = false,
                                        isExpandable = true,
                                        expandableMessage = e.message ?: "CockroachDB requires a majority consensus of nodes to execute database transactions safely. The cluster is currently under-quorum. Full operation will resume automatically when peer nodes reconnect.",
                                        isActive = true,
                                        createdAt = java.time.Instant.now().toString(),
                                        updatedAt = java.time.Instant.now().toString()
                                    )
                                )
                            }
                        }
                    }

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
                    val id = call.parameters["id"]
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
                    val id = call.parameters["id"]
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

            route("/contact") {
                post {
                    val session = call.requireSession()
                    val request = call.receive<ContactRequest>()
                    
                    val smtp = SettingsService.getSmtpSettings()
                    if (smtp.host.isBlank()) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.ServiceUnavailable,
                            "SMTP email settings are not configured. Please contact a superadmin."
                        )
                    }
                    
                    val formattedType = when (request.type.uppercase()) {
                        "BUG_REPORT" -> "Bug Report"
                        "FEATURE_REQUEST" -> "Feature Request"
                        "OTHER" -> "Other"
                        else -> request.type
                    }
                    
                    val subject = "ObsidianScout: ${formattedType} from ${session.username} (Team ${session.teamNumber})"
                    val userEmailInfo = session.email?.let { "<strong>Account Email:</strong> ${it}<br/>" } ?: ""
                    val replyToInfo = if (request.replyToEmail.isNullOrBlank()) "" else "<strong>Reply-To Email:</strong> ${request.replyToEmail}<br/>"
                    
                    val body = """
                        <html>
                        <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                            <div style="max-width: 600px; margin: 0 auto; padding: 20px; border: 1px solid #ddd; border-radius: 8px;">
                                <h2 style="color: #4f46e5; border-bottom: 2px solid #edf2f7; padding-bottom: 10px;">New Contact Form Submission</h2>
                                <p><strong>Type:</strong> ${formattedType}</p>
                                <p><strong>Sender Name:</strong> ${request.name}</p>
                                <p><strong>Logged-in Account:</strong> ${session.username} (Team ${session.teamNumber})</p>
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

            route("/admin") {
                route("/default-configs") {
                    get {
                        call.requireAdmin()
                        call.respond(ConfigService.getAllDefaultConfigs())
                    }
                    post {
                        call.requireAdmin()
                        val dto = call.receive<com.obsidianscout.config.DefaultConfigDTO>()
                        val created = ConfigService.createDefaultConfig(dto)
                        call.respond(HttpStatusCode.Created, created)
                    }
                    put("/{id}") {
                        call.requireAdmin()
                        val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing id parameter")
                        val dto = call.receive<com.obsidianscout.config.DefaultConfigDTO>()
                        val updated = ConfigService.updateDefaultConfig(id, dto)
                        call.respond(updated)
                    }
                    delete("/{id}") {
                        call.requireAdmin()
                        val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing id parameter")
                        val success = ConfigService.deleteDefaultConfig(id)
                        if (success) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Default config preset not found")
                        }
                    }
                }
                route("/cluster") {
                    get("/status") {
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        val appConfig = AppConfigLoader.load()
                        call.respond(
                            com.obsidianscout.admin.ClusterStatusResponse(
                                status = "online",
                                serverVersion = appConfig.current_version,
                                nodeIp = localIp,
                                dbActive = true,
                                executionMode = com.obsidianscout.admin.ClusterManagementService.getLocalExecutionMode()
                            )
                        )
                    }
                    get("/nodes") {
                        call.requireAdminOrClusterAuth()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getClusterNodes())
                    }
                    get("/nodes/local/logs") {
                        call.requireAdminOrClusterAuth()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                        val filter = call.request.queryParameters["filter"]
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getNodeLogs(localIp, limit, filter))
                    }
                    get("/nodes/local/app-config") {
                        call.requireAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getAppConfig(localIp, isInterNodeCall = true))
                    }
                    put("/nodes/local/app-config") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        val rawJson = call.receiveText()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.updateAppConfig(localIp, rawJson, isInterNodeCall = true))
                    }
                    post("/nodes/local/reboot") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.rebootNode(localIp))
                    }
                    post("/nodes/local/reinstall-update") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.forceReinstallUpdateNode(localIp))
                    }
                    get("/nodes/{ip}/logs") {
                        call.requireAdminOrClusterAuth()
                        val ip = call.parameters["ip"] ?: "local"
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                        val filter = call.request.queryParameters["filter"]
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getNodeLogs(ip, limit, filter))
                    }
                    get("/nodes/{ip}/app-config") {
                        call.requireAdminOrClusterAuth()
                        val ip = call.parameters["ip"] ?: "local"
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getAppConfig(ip))
                    }
                    put("/nodes/{ip}/app-config") {
                        call.requireSuperAdminOrClusterAuth()
                        val ip = call.parameters["ip"] ?: "local"
                        val rawJson = call.receiveText()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.updateAppConfig(ip, rawJson))
                    }
                    post("/nodes/{ip}/reboot") {
                        call.requireSuperAdminOrClusterAuth()
                        val ip = call.parameters["ip"] ?: "local"
                        call.respond(com.obsidianscout.admin.ClusterManagementService.rebootNode(ip))
                    }
                    post("/nodes/{ip}/reinstall-update") {
                        call.requireSuperAdminOrClusterAuth()
                        val ip = call.parameters["ip"] ?: "local"
                        call.respond(com.obsidianscout.admin.ClusterManagementService.forceReinstallUpdateNode(ip))
                    }
                    post("/reboot-all") {
                        call.requireSuperAdminOrClusterAuth()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.rebootEntireCluster())
                    }
                    post("/reinstall-update-all") {
                        call.requireSuperAdminOrClusterAuth()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.forceReinstallUpdateEntireCluster())
                    }
                    post("/regenerate-keys") {
                        call.requireSuperAdminOrClusterAuth()
                        val appConfig = AppConfigLoader.load()
                        val result = com.obsidianscout.auth.ClusterSecretService.regenerateClusterKeys(appConfig)
                        call.respond(result)
                    }
                    get("/notifications/enrollment") {
                        val session = call.requireSuperAdmin()
                        val user = AuthService.getUserById(session.userId)
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "User not found")
                        call.respond(
                            NodeAlertsEnrollmentResponse(
                                success = true,
                                enrolled = user.nodeAlertsEnabled,
                                message = if (user.nodeAlertsEnabled) "Enrolled in node down alerts." else "Not enrolled in node down alerts."
                            )
                        )
                    }
                    put("/notifications/enrollment") {
                        val session = call.requireSuperAdmin()
                        val request = call.receive<NodeAlertsEnrollmentRequest>()
                        val updated = AuthService.updateUser(
                            callerSession = session,
                            targetUserId = session.userId,
                            newUsername = null,
                            newPassword = null,
                            newRole = null,
                            newNodeAlertsEnabled = request.enrolled
                        )
                        val updatedSession = session.copy(
                            nodeAlertsEnabled = updated.nodeAlertsEnabled
                        )
                        call.sessions.set(updatedSession)
                        call.respond(
                            NodeAlertsEnrollmentResponse(
                                success = true,
                                enrolled = updated.nodeAlertsEnabled,
                                message = if (updated.nodeAlertsEnabled) "Successfully enrolled in node down FCM & Email alerts." else "Successfully unsubscribed from node down alerts."
                            )
                        )
                    }
                    post("/notifications/test") {
                        val session = call.requireSuperAdmin()
                        val uuid = runCatching { java.util.UUID.fromString(session.userId) }.getOrNull()
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid user ID")
                        val (success, msg) = com.obsidianscout.admin.NodeMonitoringService.sendTestNodeDownAlert(uuid)
                        call.respond(
                            buildJsonObject {
                                put("success", success)
                                put("message", msg)
                            }
                        )
                    }
                    get("/logs-all") {
                        call.requireAdminOrClusterAuth()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                        val filter = call.request.queryParameters["filter"]
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getAllClusterLogs(limit, filter))
                    }
                }
            }

            route("/cluster") {
                get("/status") {
                    val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                    val appConfig = AppConfigLoader.load()
                    call.respond(
                        com.obsidianscout.admin.ClusterStatusResponse(
                            status = "online",
                            serverVersion = appConfig.current_version,
                            nodeIp = localIp,
                            dbActive = true,
                            executionMode = com.obsidianscout.admin.ClusterManagementService.getLocalExecutionMode()
                        )
                    )
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
            "custom-analytics" to "custom-analytics.html",
            "graphs" to "graphs.html",
            "events" to "events.html",
            "teams" to "teams.html",
            "rankings" to "rankings.html",
            "qual-rankings" to "qual-rankings.html",
            "team" to "team.html",
            "matches" to "matches.html",
            "predictor" to "predictor.html",
            "event-predictor" to "event-predictor.html",
            "alliances" to "alliances.html",
            "alliance-edit" to "alliance-edit.html",
            "alliance-selection" to "alliance-selection.html",
            "users" to "users.html",
            "config" to "config.html",
            "admin-settings" to "admin-settings.html",
            "cluster-management" to "cluster-management.html",
            "fcm-settings" to "fcm-settings.html",
            "default-configs" to "default-configs.html",
            "backup" to "backup.html",
            "qr-scanner" to "qr-scanner.html",
            "cache-manager" to "cache-manager.html",
            "banners" to "banners.html",
            "chat" to "chat.html",
            "docs" to "docs.html",
            "contact" to "contact.html",
            "migration" to "migration.html",
            "theme-editor" to "theme-editor.html",
            "404" to "404.html",
            "500" to "500.html"
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
}

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
    respondText(rendered, ContentType.Text.Html, status)
}

private fun ApiSettings.toPayload(): ApiSettingsPayload {
    val activeTheme = themes.firstOrNull { it.name == activeThemeName } ?: themes.firstOrNull() ?: theme
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
            tbaKey = if (apiKeys.tbaKey.isNotBlank()) "********" else "",
            firstUsername = apiKeys.firstUsername,
            firstKey = if (apiKeys.firstKey.isNotBlank()) "********" else ""
        ),
        scoutPages = scoutPages,
        analyticsPages = analyticsPages,
        adminPages = adminPages,
        theme = activeTheme,
        themes = themes,
        activeThemeName = activeThemeName,
        setupWizardCompleted = setupWizardCompleted,
        program = program
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
        ),
        scoutPages = if (scoutPages.isEmpty()) com.obsidianscout.integrations.DEFAULT_SCOUT_PAGES else scoutPages,
        analyticsPages = if (analyticsPages.isEmpty()) com.obsidianscout.integrations.DEFAULT_ANALYTICS_PAGES else analyticsPages,
        adminPages = if (adminPages.isEmpty()) com.obsidianscout.integrations.DEFAULT_ADMIN_PAGES else adminPages,
        theme = theme,
        themes = themes,
        activeThemeName = activeThemeName,
        setupWizardCompleted = setupWizardCompleted,
        program = program
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
