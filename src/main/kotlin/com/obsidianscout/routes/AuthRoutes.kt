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

internal const val FORGOT_PASSWORD_GENERIC_MESSAGE =
    "If an account matches those details and has an email address, a password reset link has been sent to it."

/**
 * Every forgot-password request counts against the shared login limiter (per IP and per
 * account identifier), which caps email sends and account probing.
 */
internal fun checkForgotPasswordRateLimit(ip: String, identifier: String) {
    val key = "reset:${identifier.trim().lowercase()}"
    if (!com.obsidianscout.auth.LoginRateLimiter.checkAllowed(ip, key)) {
        throw com.obsidianscout.auth.ApiException(HttpStatusCode.TooManyRequests, "Too many requests. Please wait 60 seconds.")
    }
    com.obsidianscout.auth.LoginRateLimiter.recordFailedAttempt(ip, key)
}

fun Route.authRoutes() {
            route("/auth") {
                post("/login") {
                    val request = call.receive<LoginRequest>()
                    val ipAddress = call.clientIp()

                    if (!com.obsidianscout.auth.LoginRateLimiter.checkAllowed(ipAddress, request.username)) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.TooManyRequests,
                            "Too many failed attempts. Please wait 60 seconds."
                        )
                    }

                    val user = AuthService.login(
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password,
                        program = request.program
                    )
                    if (user == null) {
                        com.obsidianscout.auth.LoginRateLimiter.recordFailedAttempt(ipAddress, request.username)
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.Unauthorized,
                            "Invalid credentials"
                        )
                    }
                    com.obsidianscout.auth.LoginRateLimiter.clear(ipAddress, request.username)

                    val userUuid = UUID.fromString(user.id)
                    val userAgent = call.request.headers["User-Agent"] ?: ""
                    val deviceId = call.request.headers["X-Device-Id"]
                    val deviceName = call.request.headers["X-Device-Name"]
                    val sessionExpiresAt = if (request.keepMeLoggedIn) {
                        Instant.now().plus(30, ChronoUnit.DAYS)
                    } else {
                        Instant.now().plus(12, ChronoUnit.HOURS)
                    }
                    val sessionUuid = AuthService.createSession(
                        userId = userUuid,
                        clientType = "web",
                        userAgent = userAgent,
                        ipAddress = ipAddress,
                        customDeviceName = deviceName,
                        expiresAt = sessionExpiresAt,
                        deviceId = deviceId
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
                        nodeAlertsEnabled = user.nodeAlertsEnabled,
                        bugReportPreference = user.bugReportPreference,
                        localAiEnabled = user.localAiEnabled,
                        nonScoutingToolsEnabled = user.nonScoutingToolsEnabled,
                        sessionId = sessionUuid.toString()
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
                        nodeAlertsEnabled = user.nodeAlertsEnabled,
                        bugReportPreference = user.bugReportPreference,
                        localAiEnabled = user.localAiEnabled,
                        nonScoutingToolsEnabled = user.nonScoutingToolsEnabled,
                        sessionId = sessionUuid.toString()
                    )
                    call.respond(LoginResponse(responseSession))
                }
                post("/register") {
                    val request = call.receive<RegisterRequest>()
                    val ipAddress = call.clientIp()

                    if (!com.obsidianscout.auth.LoginRateLimiter.checkAllowed(ipAddress, request.username)) {
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.TooManyRequests,
                            "Too many failed attempts. Please wait 60 seconds."
                        )
                    }

                    val user = AuthService.register(
                        username = request.username,
                        teamNumber = request.teamNumber,
                        password = request.password,
                        program = request.program,
                        role = request.role,
                        email = request.email
                    )
                    com.obsidianscout.auth.LoginRateLimiter.clear(ipAddress, request.username)

                    val userUuid = UUID.fromString(user.id)
                    val userAgent = call.request.headers["User-Agent"] ?: ""
                    val deviceId = call.request.headers["X-Device-Id"]
                    val deviceName = call.request.headers["X-Device-Name"]
                    val sessionExpiresAt = if (request.keepMeLoggedIn) {
                        Instant.now().plus(30, ChronoUnit.DAYS)
                    } else {
                        Instant.now().plus(12, ChronoUnit.HOURS)
                    }
                    val sessionUuid = AuthService.createSession(
                        userId = userUuid,
                        clientType = "web",
                        userAgent = userAgent,
                        ipAddress = ipAddress,
                        customDeviceName = deviceName,
                        expiresAt = sessionExpiresAt,
                        deviceId = deviceId
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
                        nodeAlertsEnabled = user.nodeAlertsEnabled,
                        bugReportPreference = user.bugReportPreference,
                        localAiEnabled = user.localAiEnabled,
                        nonScoutingToolsEnabled = user.nonScoutingToolsEnabled,
                        sessionId = sessionUuid.toString()
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
                        nodeAlertsEnabled = user.nodeAlertsEnabled,
                        bugReportPreference = user.bugReportPreference,
                        localAiEnabled = user.localAiEnabled,
                        nonScoutingToolsEnabled = user.nonScoutingToolsEnabled,
                        sessionId = sessionUuid.toString()
                    )
                    call.respond(LoginResponse(responseSession))
                }
                post("/logout") {
                    val session = call.sessions.get<UserSession>()
                    if (session != null && !session.sessionId.isNullOrBlank()) {
                        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
                        val sessionUuid = runCatching { UUID.fromString(session.sessionId) }.getOrNull()
                        if (userUuid != null && sessionUuid != null) {
                            AuthService.revokeSession(userUuid, sessionUuid)
                        }
                    }
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
                        nodeAlertsEnabled = user.nodeAlertsEnabled,
                        bugReportPreference = user.bugReportPreference,
                        localAiEnabled = user.localAiEnabled,
                        nonScoutingToolsEnabled = user.nonScoutingToolsEnabled,
                        sessionId = session.sessionId
                    )
                    // If session attributes changed in DB (e.g. role, username, program, teamNumber, bugReportPreference), update cookie session WITHOUT bloated profilePicture
                    val cookieNeedsUpdate = session.role != user.role ||
                            session.username != user.username ||
                            session.teamNumber != user.teamNumber ||
                            session.program != user.program ||
                            session.bugReportPreference != user.bugReportPreference ||
                            session.localAiEnabled != user.localAiEnabled ||
                            session.nonScoutingToolsEnabled != user.nonScoutingToolsEnabled ||
                            session.profilePicture != null
                    if (cookieNeedsUpdate) {
                        call.sessions.set(responseSession.copy(profilePicture = null))
                    }
                    call.respond(MeResponse(responseSession))
                }
                get("/status") {
                    val session = call.sessions.get<UserSession>()
                    call.respond(LoginStatusResponse(session != null))
                }
                get("/providers") {
                    val providers = listOf(
                        AuthProviderInfo("local", true),
                        AuthProviderInfo("passkey", true),
                        AuthProviderInfo("oauth", false)
                    )
                    call.respond(AuthProvidersResponse(providers))
                }

                route("/passkey") {
                    post("/register/begin") {
                        val session = call.requireSession()
                        val origin = call.request.headers["Origin"]
                        val host = call.request.headers["Host"]
                        val optionsJson = com.obsidianscout.auth.PasskeyService.beginRegistrationJson(session, clientOrigin = origin, hostHeader = host)
                        call.respondText(optionsJson, io.ktor.http.ContentType.Application.Json)
                    }

                    post("/register/finish") {
                        val session = call.requireSession()
                        val request = call.receive<PasskeyRegisterFinishRequest>()
                        val origin = call.request.headers["Origin"]
                        val host = call.request.headers["Host"]
                        val userAgent = call.request.headers["User-Agent"]
                        val cred = com.obsidianscout.auth.PasskeyService.finishRegistration(
                            userSession = session,
                            credentialId = request.credentialId,
                            clientDataJSONBase64 = request.clientDataJSON,
                            attestationObjectBase64 = request.attestationObject,
                            friendlyName = request.friendlyName,
                            clientOrigin = origin,
                            hostHeader = host,
                            userAgent = userAgent
                        )
                        call.respond(cred)
                    }

                    post("/authenticate/begin") {
                        val request = try {
                            call.receive<PasskeyAuthBeginRequest>()
                        } catch (_: Exception) {
                            PasskeyAuthBeginRequest()
                        }
                        val origin = call.request.headers["Origin"]
                        val host = call.request.headers["Host"]
                        val optionsJson = com.obsidianscout.auth.PasskeyService.beginAuthenticationJson(
                            username = request.username,
                            teamNumber = request.teamNumber,
                            program = request.program,
                            clientOrigin = origin,
                            hostHeader = host
                        )
                        call.respondText(optionsJson, io.ktor.http.ContentType.Application.Json)
                    }

                    post("/authenticate/finish") {
                        val request = call.receive<PasskeyAuthFinishRequest>()
                        val origin = call.request.headers["Origin"]
                        val host = call.request.headers["Host"]
                        val user = com.obsidianscout.auth.PasskeyService.finishAuthentication(
                            credentialId = request.credentialId,
                            clientDataJSONBase64 = request.clientDataJSON,
                            authenticatorDataBase64 = request.authenticatorData,
                            signatureBase64 = request.signature,
                            userHandleBase64 = request.userHandle,
                            clientOrigin = origin,
                            hostHeader = host
                        )

                        val userUuid = UUID.fromString(user.id)
                        val ipAddress = call.clientIp()
                        val userAgent = call.request.headers["User-Agent"] ?: ""
                        val deviceId = call.request.headers["X-Device-Id"]
                        val deviceName = call.request.headers["X-Device-Name"]
                        val sessionExpiresAt = if (request.keepMeLoggedIn) {
                            Instant.now().plus(30, ChronoUnit.DAYS)
                        } else {
                            Instant.now().plus(12, ChronoUnit.HOURS)
                        }
                        val sessionUuid = AuthService.createSession(
                            userId = userUuid,
                            clientType = "web",
                            userAgent = userAgent,
                            ipAddress = ipAddress,
                            customDeviceName = deviceName,
                            expiresAt = sessionExpiresAt,
                            deviceId = deviceId
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
                            nodeAlertsEnabled = user.nodeAlertsEnabled,
                            bugReportPreference = user.bugReportPreference,
                            localAiEnabled = user.localAiEnabled,
                            nonScoutingToolsEnabled = user.nonScoutingToolsEnabled,
                            sessionId = sessionUuid.toString()
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
                            nodeAlertsEnabled = user.nodeAlertsEnabled,
                            bugReportPreference = user.bugReportPreference,
                            localAiEnabled = user.localAiEnabled,
                            nonScoutingToolsEnabled = user.nonScoutingToolsEnabled,
                            sessionId = sessionUuid.toString()
                        )
                        call.respond(LoginResponse(responseSession))
                    }

                    get("/credentials") {
                        val session = call.requireSession()
                        val creds = com.obsidianscout.auth.PasskeyService.listCredentials(session)
                        call.respond(creds)
                    }

                    delete("/credentials/{id}") {
                        val session = call.requireSession()
                        val credId = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.BadRequest, "Missing credential ID"
                        )
                        val deleted = com.obsidianscout.auth.PasskeyService.deleteCredential(session, credId)
                        if (!deleted) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Credential not found")
                        }
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                post("/forgot-password") {
                    val request = call.receive<ForgotPasswordRequest>()
                    checkForgotPasswordRateLimit(call.clientIp(), request.email ?: "${request.teamNumber}:${request.username}")
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
                        val recoverEmail = request.email!!.trim().lowercase()
                        val matchedUsers = com.obsidianscout.db.readTransaction {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.email.lowerCase() eq recoverEmail.lowercase() }
                                .toList()
                        }
                        if (matchedUsers.isEmpty()) {
                            call.respond(mapOf("message" to FORGOT_PASSWORD_GENERIC_MESSAGE))
                            return@post
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

                        val user = com.obsidianscout.db.readTransaction {
                            com.obsidianscout.db.Users
                                .selectAll().where { 
                                    (com.obsidianscout.db.Users.username eq username) and 
                                    (com.obsidianscout.db.Users.teamNumber eq teamNumber) 
                                }
                                .limit(1)
                                .firstOrNull()
                        }

                        // Respond identically whether or not the account exists or has an email,
                        // so this endpoint cannot be used to discover accounts.
                        val foundEmail = user?.get(com.obsidianscout.db.Users.email)
                        if (user == null || foundEmail.isNullOrBlank()) {
                            call.respond(mapOf("message" to FORGOT_PASSWORD_GENERIC_MESSAGE))
                            return@post
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
                                it[com.obsidianscout.db.PasswordResetTokens.email] = foundEmail
                                it[com.obsidianscout.db.PasswordResetTokens.token] = token
                                it[expiresAt] = expires
                            }
                        }
                    }

                    // Links must point at the configured site, never at a request header value.
                    val baseUrl = AppConfigLoader.load().getEffectiveSiteUrl()

                    try {
                        EmailService.sendForgotPasswordEmail(
                            to = userEmail,
                            username = if (isEmailRecovery) userEmail else request.username!!,
                            teamNumber = if (isEmailRecovery) -1 else request.teamNumber!!,
                            token = token,
                            baseUrl = baseUrl,
                            isApp = request.isApp || (call.request.headers["X-Client"] == "mobile")
                        )
                    } catch (e: Exception) {
                        call.application.environment.log.error("[forgot-password] Failed to send reset email: ${e.message}")
                        throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.InternalServerError,
                            "Failed to send the reset email. Please try again later or contact an administrator."
                        )
                    }

                    call.respond(mapOf("message" to FORGOT_PASSWORD_GENERIC_MESSAGE))
                }

                get("/verify-reset-token") {
                    val token = call.request.queryParameters["token"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing token")
                    
                    val tokenRow = com.obsidianscout.db.readTransaction {
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

                    val accounts = com.obsidianscout.db.readTransaction {
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
                    
                    val tokenRow = com.obsidianscout.db.readTransaction {
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
                        val isValidAccount = com.obsidianscout.db.readTransaction {
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
                        // Claim the token first so concurrent requests cannot both use it.
                        val claimed = com.obsidianscout.db.PasswordResetTokens.update({
                            (com.obsidianscout.db.PasswordResetTokens.id eq tokenRow[com.obsidianscout.db.PasswordResetTokens.id]) and
                            (com.obsidianscout.db.PasswordResetTokens.used eq false)
                        }) {
                            it[used] = true
                        }
                        if (claimed == 0) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid or expired reset token.")
                        }

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
                    }
                    // A reset usually means the old password may be compromised: sign out everywhere.
                    com.obsidianscout.auth.AuthService.revokeAllSessions(UUID.fromString(finalUserId))

                    call.respond(mapOf("message" to "Credentials have been reset successfully."))
                }
            }

            // Self-service profile and settings endpoint (any authenticated user)
            put("/user/profile-picture") {
                val session = call.requireSession()
                val request = call.receive<UpdateUserRequest>()
                val updated = AuthService.updateUser(
                    callerSession = session,
                    targetUserId = session.userId,
                    newUsername = request.username?.trim()?.takeIf { it.isNotBlank() },
                    newPassword = request.password?.takeIf { it.isNotBlank() },
                    newRole = null,
                    newEmail = request.email,
                    newProfilePicture = request.profilePicture,
                    clearProfilePicture = request.clearProfilePicture,
                    newNotificationPreference = request.notificationPreference,
                    newNodeAlertsEnabled = if (session.role == UserRole.SUPERADMIN) request.nodeAlertsEnabled else null,
                    newBugReportPreference = request.bugReportPreference,
                    newLocalAiEnabled = request.localAiEnabled,
                    newNonScoutingToolsEnabled = request.nonScoutingToolsEnabled
                )
                // Refresh the session so /api/auth/me returns the updated details
                val updatedSession = session.copy(
                    username = updated.username,
                    profilePicture = null,
                    email = updated.email,
                    notificationPreference = updated.notificationPreference,
                    tourProgress = updated.tourProgress,
                    nodeAlertsEnabled = updated.nodeAlertsEnabled,
                    bugReportPreference = updated.bugReportPreference,
                    localAiEnabled = updated.localAiEnabled,
                    nonScoutingToolsEnabled = updated.nonScoutingToolsEnabled
                )
                call.sessions.set(updatedSession)
                call.respond(updated)
            }

            put("/user") {
                val session = call.requireSession()
                val request = call.receive<UpdateUserRequest>()
                val updated = AuthService.updateUser(
                    callerSession = session,
                    targetUserId = session.userId,
                    newUsername = request.username?.trim()?.takeIf { it.isNotBlank() },
                    newPassword = request.password?.takeIf { it.isNotBlank() },
                    newRole = null,
                    newEmail = request.email,
                    newProfilePicture = request.profilePicture,
                    clearProfilePicture = request.clearProfilePicture,
                    newNotificationPreference = request.notificationPreference,
                    newNodeAlertsEnabled = if (session.role == UserRole.SUPERADMIN) request.nodeAlertsEnabled else null,
                    newBugReportPreference = request.bugReportPreference,
                    newLocalAiEnabled = request.localAiEnabled,
                    newNonScoutingToolsEnabled = request.nonScoutingToolsEnabled
                )
                // Refresh the session so /api/auth/me returns the updated details
                val updatedSession = session.copy(
                    username = updated.username,
                    profilePicture = null,
                    email = updated.email,
                    notificationPreference = updated.notificationPreference,
                    tourProgress = updated.tourProgress,
                    nodeAlertsEnabled = updated.nodeAlertsEnabled,
                    bugReportPreference = updated.bugReportPreference,
                    localAiEnabled = updated.localAiEnabled,
                    nonScoutingToolsEnabled = updated.nonScoutingToolsEnabled
                )
                call.sessions.set(updatedSession)
                call.respond(updated)
            }

            // Profile pictures of teammates, referenced by URL from chat messages.
            get("/users/{id}/avatar") {
                val session = call.requireSession()
                val targetUuid = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                    ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Not found")
                val row = com.obsidianscout.db.readTransaction {
                    com.obsidianscout.db.Users
                        .select(com.obsidianscout.db.Users.teamNumber, com.obsidianscout.db.Users.program, com.obsidianscout.db.Users.profilePicture)
                        .where { com.obsidianscout.db.Users.id eq targetUuid }
                        .firstOrNull()
                } ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Not found")
                val sameTeam = row[com.obsidianscout.db.Users.teamNumber] == session.teamNumber &&
                        row[com.obsidianscout.db.Users.program] == session.program
                if (!sameTeam && session.role != UserRole.SUPERADMIN) {
                    throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Not found")
                }
                val image = row[com.obsidianscout.db.Users.profilePicture]?.let { AuthService.decodeProfilePicture(it) }
                    ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Not found")
                call.respondBytes(image.second, ContentType.parse(image.first))
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

            get("/user/sessions") {
                val session = call.requireSession()
                val userUuid = UUID.fromString(session.userId)
                val sessions = AuthService.listSessions(userUuid, session.sessionId)
                call.respond(UserSessionsResponse(sessions))
            }

            delete("/user/sessions/{id}") {
                val session = call.requireSession()
                val targetIdStr = call.parameters["id"]
                    ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing session ID")
                val targetUuid = runCatching { UUID.fromString(targetIdStr) }.getOrElse {
                    throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid session ID format")
                }
                val userUuid = UUID.fromString(session.userId)
                val deleted = AuthService.revokeSession(userUuid, targetUuid)
                if (!deleted) {
                    throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Session not found")
                }
                if (targetIdStr == session.sessionId) {
                    call.sessions.clear<UserSession>()
                }
                call.respond(RevokeSessionResponse(success = true, message = "Session revoked successfully"))
            }

            delete("/user/sessions") {
                val session = call.requireSession()
                val userUuid = UUID.fromString(session.userId)
                val duplicatesOnly = call.request.queryParameters["duplicatesOnly"]?.toBooleanStrictOrNull() ?: false
                if (duplicatesOnly) {
                    val count = AuthService.cleanDuplicateSessions(userUuid)
                    call.respond(RevokeSessionResponse(success = true, revokedCount = count, message = "Cleaned up $count duplicate or stale device session(s)"))
                    return@delete
                }
                val othersOnly = call.request.queryParameters["othersOnly"]?.toBooleanStrictOrNull() ?: false
                if (othersOnly) {
                    val count = AuthService.revokeAllOtherSessions(userUuid, session.sessionId)
                    call.respond(RevokeSessionResponse(success = true, revokedCount = count, message = "All other sessions revoked successfully"))
                } else {
                    val count = AuthService.revokeAllSessions(userUuid)
                    call.sessions.clear<UserSession>()
                    call.respond(RevokeSessionResponse(success = true, revokedCount = count, message = "All sessions revoked successfully"))
                }
            }
}