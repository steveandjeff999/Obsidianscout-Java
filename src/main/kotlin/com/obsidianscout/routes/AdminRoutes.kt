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
import com.obsidianscout.auth.requireAdminOrPage
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

fun Route.adminRoutes() {
            route("/cluster") {
                get("/status") {
                    val appConfig = AppConfigLoader.load()
                    val uptimeSec = com.obsidianscout.admin.ClusterManagementService.getLocalUptimeSeconds()
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
                            put("uptimeSeconds", uptimeSec)
                            put("uptimeFormatted", com.obsidianscout.admin.ClusterManagementService.formatUptime(uptimeSec))
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
                    // Probing arbitrary hosts is an inter-node function; never expose it anonymously.
                    call.requireSuperAdminOrClusterAuth()
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
                get("/load") {
                    val localLoad = com.obsidianscout.admin.PeerLoadRouter.getLocalNodeLoad()
                    call.respond(localLoad)
                }
                configCollabClusterRoutes()
            }

            route("/admin") {
                get("/users") {
                    val session = call.requireAdminOrPage("users")
                    val q = call.request.queryParameters["q"]
                    val teamNumber = call.request.queryParameters["teamNumber"]?.toIntOrNull()
                    val program = call.request.queryParameters["program"]
                    val roleStr = call.request.queryParameters["role"]
                    val role = roleStr?.takeIf { it.isNotBlank() }?.let {
                        runCatching { UserRole.valueOf(it) }.getOrNull()
                    }
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                    val sortBy = call.request.queryParameters["sortBy"]
                    val sortDir = call.request.queryParameters["sortDir"]

                    call.respond(
                        AuthService.listUsers(
                            callerSession = session,
                            search = q,
                            teamFilter = teamNumber,
                            roleFilter = role,
                            programFilter = program,
                            limit = limit,
                            offset = offset,
                            sortBy = sortBy,
                            sortDir = sortDir
                        )
                    )
                }
                post("/users") {
                    val session = call.requireAdminOrPage("users")
                    val request = call.receive<CreateUserRequest>()
                    val targetTeam = if (session.role == UserRole.SUPERADMIN) request.teamNumber else session.teamNumber
                    val targetProgram = if (session.role == UserRole.SUPERADMIN) request.program else session.program
                    val user = AuthService.createUser(
                        callerSession = session,
                        username = request.username,
                        teamNumber = targetTeam,
                        password = request.password,
                        program = targetProgram,
                        role = request.role,
                        email = request.email,
                        customPages = request.customPages
                    )
                    call.respond(user)
                }
                put("/users/{id}") {
                    val session = call.requireAdminOrPage("users")
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
                        clearProfilePicture = request.clearProfilePicture,
                        newCustomPages = request.customPages
                    )
                    if (userId == session.userId) {
                        val updatedSession = session.copy(
                            username = updated.username,
                            role = updated.role,
                            teamNumber = updated.teamNumber,
                            email = updated.email,
                            profilePicture = null,
                            notificationPreference = updated.notificationPreference,
                            tourProgress = updated.tourProgress,
                            nodeAlertsEnabled = updated.nodeAlertsEnabled
                        )
                        call.sessions.set(updatedSession)
                    }
                    call.respond(updated)
                }
                delete("/users/{id}") {
                    val session = call.requireAdminOrPage("users")
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
                    
                    val userRecord = com.obsidianscout.db.readTransaction {
                        val uuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
                        if (uuid != null) {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.id eq uuid }
                                .limit(1)
                                .firstOrNull()
                        } else null
                    } ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "Superadmin user not found")

                    val hash = userRecord[com.obsidianscout.db.Users.passwordHash]
                    val verified = runCatching { at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(req.password.toCharArray(), hash).verified }.getOrDefault(false)
                    if (!verified) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "Invalid password")
                    }

                    val originalId = session.userId
                    val originalUsername = userRecord[com.obsidianscout.db.Users.username]
                    val originalTeamNumber = userRecord[com.obsidianscout.db.Users.teamNumber]
                    val originalProgram = userRecord[com.obsidianscout.db.Users.program]
                    val originalPasswordHash = userRecord[com.obsidianscout.db.Users.passwordHash]
                    val originalRole = userRecord[com.obsidianscout.db.Users.role]
                    val originalEmail = userRecord[com.obsidianscout.db.Users.email]
                    val originalProfilePicture = userRecord[com.obsidianscout.db.Users.profilePicture]
                    val originalNotificationPreference = userRecord[com.obsidianscout.db.Users.notificationPreference]

                    transaction {
                        org.jetbrains.exposed.v1.jdbc.SchemaUtils.drop(
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

                        org.jetbrains.exposed.v1.jdbc.SchemaUtils.create(
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
                            it[program] = originalProgram
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
                    
                    val userRecord = com.obsidianscout.db.readTransaction {
                        val uuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
                        if (uuid != null) {
                            com.obsidianscout.db.Users
                                .selectAll().where { com.obsidianscout.db.Users.id eq uuid }
                                .limit(1)
                                .firstOrNull()
                        } else null
                    } ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "User not found")

                    val hash = userRecord[com.obsidianscout.db.Users.passwordHash]
                    val verified = runCatching { at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(req.password.toCharArray(), hash).verified }.getOrDefault(false)
                    if (!verified) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Unauthorized, "Invalid password")
                    }

                    transaction {
                        // Delete scouting entries only for this team and program
                        com.obsidianscout.db.ScoutingEntries.deleteWhere { 
                            (com.obsidianscout.db.ScoutingEntries.ownerTeamNumber eq session.teamNumber) and
                            (com.obsidianscout.db.ScoutingEntries.program eq session.program)
                        }
                        com.obsidianscout.db.PitScoutingEntries.deleteWhere { 
                            (com.obsidianscout.db.PitScoutingEntries.ownerTeamNumber eq session.teamNumber) and
                            (com.obsidianscout.db.PitScoutingEntries.program eq session.program)
                        }
                        com.obsidianscout.db.QualitativeScoutingEntries.deleteWhere { 
                            (com.obsidianscout.db.QualitativeScoutingEntries.ownerTeamNumber eq session.teamNumber) and
                            (com.obsidianscout.db.QualitativeScoutingEntries.program eq session.program)
                        }

                        // Delete team's alliance selection data
                        // Board rows are keyed "team_<n>_<program>" (legacy FRC rows "team_<n>"); pick lists have their own key.
                        val ownerKeys = listOfNotNull(
                            "team_${session.teamNumber}_${session.program}",
                            if (session.program.equals("FRC", ignoreCase = true)) "team_${session.teamNumber}" else null,
                            "${session.program}_${session.teamNumber}",
                            session.teamNumber.toString(),
                            com.obsidianscout.scouting.AllianceSelectionService.pickListOwnerKey(session.teamNumber, session.program)
                        )
                        com.obsidianscout.db.AllianceSelections.deleteWhere {
                            com.obsidianscout.db.AllianceSelections.ownerKey inList ownerKeys
                        }

                        // Clear the active event and setup wizard state in the settings for this team and program
                        val settings = SettingsService.getSettings(session.teamNumber, session.program)
                        val clearedSettings = settings.copy(
                            eventCode = "",
                            eventKey = "",
                            setupWizardCompleted = false
                        )
                        SettingsService.updateSettings(session.teamNumber, clearedSettings)
                    }
                    com.obsidianscout.scouting.EntrySync.markBulkChange()

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
                        val backup = com.obsidianscout.db.BackupService.exportBackup(session.teamNumber, type, scope, session.program)
                        val jsonString = JsonSupport.json.encodeToString(com.obsidianscout.db.ObsidianDbBackup.serializer(), backup)
                        val filename = if (scope == "global") "global_backup_${type}.obsidiandb" else "team_${session.teamNumber}_backup_${type}.obsidiandb"
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
                        call.respondText(jsonString, ContentType.Application.Json)
                    } else if (format == "csv") {
                        val zipBytes = com.obsidianscout.db.BackupService.exportCsv(session.teamNumber, type, scope, session.program)
                        val filename = if (scope == "global") "global_backup_${type}.zip" else "team_${session.teamNumber}_backup_${type}.zip"
                        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
                        call.respondBytes(zipBytes, ContentType.Application.Zip)
                    } else {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Unsupported format: $format")
                    }
                }

                get("/scouter-report/options") {
                    val session = call.requireAdminOrPage("scouter-report")
                    val teamNumber = scouterReportTeam(session, call.request.queryParameters["teamNumber"])
                    call.respond(com.obsidianscout.scouting.ScouterReportService.listOptions(teamNumber, session.program))
                }

                get("/scouter-report") {
                    val session = call.requireAdminOrPage("scouter-report")
                    val params = call.request.queryParameters
                    val teamNumber = scouterReportTeam(session, params["teamNumber"])
                    val eventKey = params["eventKey"]?.trim()?.takeIf { it.isNotEmpty() }
                    val year = params["year"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        it.toIntOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid year")
                    }
                    val includePrescout = params["includePrescout"] == "true"
                    val report = com.obsidianscout.scouting.ScouterReportService.buildReport(
                        teamNumber, session.program, eventKey, year, includePrescout
                    )
                    when (params["format"] ?: "json") {
                        "json" -> call.respond(report)
                        "csv" -> {
                            val byEvent = params["detail"] == "events"
                            val csv = if (byEvent) com.obsidianscout.scouting.ScouterReportService.toByEventCsv(report)
                                else com.obsidianscout.scouting.ScouterReportService.toSummaryCsv(report)
                            val scopeLabel = report.eventKey ?: report.year?.toString() ?: "all-time"
                            val safeScope = scopeLabel.replace(Regex("[^A-Za-z0-9_-]"), "")
                            val filename = "team_${teamNumber}_scouters_${safeScope}${if (byEvent) "_by_event" else ""}.csv"
                            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
                            // BOM so Excel opens the file as UTF-8 (usernames can contain non-ASCII characters).
                            call.respondText("﻿" + csv, ContentType.Text.CSV.withParameter("charset", "utf-8"))
                        }
                        else -> throw ApiException(HttpStatusCode.BadRequest, "Unsupported format")
                    }
                }

                post("/import") {
                    val session = call.requireAdmin()
                    val requestedScope = call.request.queryParameters["scope"] ?: "team"
                    val isSuperAdmin = session.role == com.obsidianscout.auth.UserRole.SUPERADMIN

                    if (requestedScope == "global" && !isSuperAdmin) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can perform global imports")
                    }

                    val multipart = call.receiveMultipart(formFieldLimit = com.obsidianscout.db.BackupService.MAX_IMPORT_BYTES)
                    var fileBytes: ByteArray? = null
                    var fileName = ""
                    while (true) {
                        val part = multipart.readPart() ?: break
                        if (part is PartData.FileItem) {
                            fileBytes = part.streamProvider().use { com.obsidianscout.db.BackupService.readLimited(it, com.obsidianscout.db.BackupService.MAX_IMPORT_BYTES) }
                            fileName = part.originalFileName ?: ""
                        }
                        part.release()
                    }

                    if (fileBytes == null) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "No file uploaded")
                    }

                    val report = if (fileName.endsWith(".obsidiandb") || fileName.endsWith(".json")) {
                        val jsonString = String(fileBytes!!, Charsets.UTF_8)
                        val backup = JsonSupport.json.decodeFromString<com.obsidianscout.db.ObsidianDbBackup>(jsonString)
                        val importAsGlobal = isSuperAdmin && requestedScope == "global" && backup.scope == "global"
                        com.obsidianscout.db.BackupService.importBackup(session.teamNumber, backup, session.userId, importAsGlobal, session.program)
                    } else if (fileName.endsWith(".zip")) {
                        val importAsGlobal = isSuperAdmin && requestedScope == "global"
                        com.obsidianscout.db.BackupService.importCsv(session.teamNumber, fileBytes!!, session.userId, importAsGlobal, session.program)
                    } else {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Unsupported file format. Please upload .obsidiandb or .zip")
                    }
                    com.obsidianscout.scouting.EntrySync.markBulkChange()

                    call.respond(report)
                }

                // SQLite Automated & Manual Snapshots and Database Restoration (Superadmin only)
                route("/snapshots") {
                    get {
                        call.requireSuperAdmin()
                        call.respond(com.obsidianscout.db.SnapshotService.getLocalNodeStatus())
                    }

                    put("/config") {
                        call.requireSuperAdmin()
                        val req = call.receive<UpdateAutoBackupConfigRequest>()
                        val currentConfig = AppConfigLoader.load()
                        val prevBackupConfig = currentConfig.auto_backup ?: com.obsidianscout.config.AutoBackupConfig()
                        val updatedBackupConfig = prevBackupConfig.copy(
                            enabled = req.enabled ?: prevBackupConfig.enabled,
                            retention_days = req.retentionDays?.coerceAtLeast(1) ?: prevBackupConfig.retention_days
                        )
                        AppConfigLoader.saveAutoBackupConfig(updatedBackupConfig)
                        com.obsidianscout.db.SnapshotService.pruneOldSnapshots()
                        call.respond(com.obsidianscout.db.SnapshotService.getLocalNodeStatus())
                    }

                    post("/create") {
                        call.requireSuperAdmin()
                        val snapshot = com.obsidianscout.db.SnapshotService.createSnapshot(isAutoBackup = false)
                        call.respond(snapshot)
                    }

                    get("/download/{fileName}") {
                        call.requireSuperAdmin()
                        val fileName = call.parameters["fileName"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing fileName")
                        val file = com.obsidianscout.db.SnapshotService.getSnapshotFile(fileName)
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Snapshot file not found")
                        call.response.headers.append(
                            HttpHeaders.ContentDisposition,
                            "attachment; filename=\"${file.name}\""
                        )
                        call.respondFile(file)
                    }

                    delete("/{fileName}") {
                        call.requireSuperAdmin()
                        val fileName = call.parameters["fileName"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing fileName")
                        val success = com.obsidianscout.db.SnapshotService.deleteSnapshot(fileName)
                        if (!success) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Snapshot file not found or could not be deleted")
                        }
                        call.respond(mapOf("success" to true))
                    }

                    post("/restore/{fileName}") {
                        call.requireSuperAdmin()
                        val fileName = call.parameters["fileName"]
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing fileName")
                        val file = com.obsidianscout.db.SnapshotService.getSnapshotFile(fileName)
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Snapshot file not found")
                        val report = com.obsidianscout.db.SnapshotService.restoreFromSnapshot(file)
                        com.obsidianscout.scouting.EntrySync.markBulkChange()
                        call.respond(report)
                    }

                    post("/restore-upload") {
                        call.requireSuperAdmin()
                        val multipart = call.receiveMultipart()
                        var uploadedFile: File? = null
                        while (true) {
                            val part = multipart.readPart() ?: break
                            if (part is PartData.FileItem) {
                                val tempFile = File.createTempFile("restore-upload-", ".db")
                                part.streamProvider().use { input ->
                                    tempFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                uploadedFile = tempFile
                            }
                            part.release()
                        }

                        val file = uploadedFile ?: throw com.obsidianscout.auth.ApiException(
                            HttpStatusCode.BadRequest,
                            "No SQLite backup file uploaded"
                        )

                        try {
                            val report = com.obsidianscout.db.SnapshotService.restoreFromSnapshot(file)
                            com.obsidianscout.scouting.EntrySync.markBulkChange()
                            call.respond(report)
                        } finally {
                            file.delete()
                        }
                    }
                }
            } // end /admin route

            route("/admin") {
                route("/default-configs") {
                    // Presets are shared by every team and written to disk on every node: superadmin only.
                    get {
                        call.requireSuperAdmin()
                        call.respond(ConfigService.getAllDefaultConfigs())
                    }
                    post {
                        call.requireSuperAdmin()
                        val dto = call.receive<com.obsidianscout.config.DefaultConfigDTO>()
                        ConfigService.requireNoBlankOptionValues(dto.configJson)
                        val created = ConfigService.createDefaultConfig(dto)
                        call.respond(HttpStatusCode.Created, created)
                    }
                    put("/{id}") {
                        call.requireSuperAdmin()
                        val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing id parameter")
                        val dto = call.receive<com.obsidianscout.config.DefaultConfigDTO>()
                        ConfigService.requireNoBlankOptionValues(dto.configJson)
                        val updated = ConfigService.updateDefaultConfig(id, dto)
                        call.respond(updated)
                    }
                    delete("/{id}") {
                        call.requireSuperAdmin()
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
                        val uptimeSec = com.obsidianscout.admin.ClusterManagementService.getLocalUptimeSeconds()
                        call.respond(
                            com.obsidianscout.admin.ClusterStatusResponse(
                                status = "online",
                                serverVersion = appConfig.current_version,
                                nodeIp = localIp,
                                dbActive = true,
                                executionMode = com.obsidianscout.admin.ClusterManagementService.getLocalExecutionMode(),
                                uptimeSeconds = uptimeSec,
                                uptimeFormatted = com.obsidianscout.admin.ClusterManagementService.formatUptime(uptimeSec)
                            )
                        )
                    }
                    get("/nodes") {
                        call.requireSuperAdminOrClusterAuth()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getClusterNodes())
                    }
                    get("/nodes/local/logs") {
                        call.requireSuperAdminOrClusterAuth()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                        val filter = call.request.queryParameters["filter"]
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getNodeLogs(localIp, limit, filter))
                    }
                    get("/nodes/local/app-config") {
                        call.requireSuperAdminOrClusterAuth()
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
                        call.requireSuperAdminOrClusterAuth()
                        val ip = call.parameters["ip"] ?: "local"
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                        val filter = call.request.queryParameters["filter"]
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getNodeLogs(ip, limit, filter))
                    }
                    get("/nodes/{ip}/app-config") {
                        call.requireSuperAdminOrClusterAuth()
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
                    get("/load-balancer/settings") {
                        call.requireSuperAdminOrClusterAuth()
                        call.respond(SettingsService.getLoadBalancerSettings())
                    }
                    put("/load-balancer/settings") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<com.obsidianscout.integrations.LoadBalancerSettings>()
                        val updated = SettingsService.updateLoadBalancerSettings(req)
                        com.obsidianscout.admin.PeerLoadRouter.updateCachedSettings(updated)
                        if (updated.enabled) {
                            com.obsidianscout.admin.PeerLoadRouter.refreshSettingsAndProbePeers()
                        }
                        call.respond(updated)
                    }
                    get("/load-balancer/status") {
                        call.requireSuperAdminOrClusterAuth()
                        call.respond(com.obsidianscout.admin.PeerLoadRouter.getStatus())
                    }
                    post("/stress/start") {
                        val session = call.requireSuperAdmin()
                        val result = com.obsidianscout.admin.ServerStressTestService.startStressTest(session.username)
                        call.respond(result)
                    }
                    post("/stress/stop") {
                        val session = call.requireSuperAdmin()
                        val result = com.obsidianscout.admin.ServerStressTestService.stopStressTest(session.username)
                        call.respond(result)
                    }
                    get("/stress/status") {
                        call.requireSuperAdmin()
                        val result = com.obsidianscout.admin.ServerStressTestService.getStatus()
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
                    get("/error-alerts") {
                        call.requireSuperAdmin()
                        val settings = SettingsService.getErrorAlertSettings()
                        val enrolled = com.obsidianscout.admin.ServerErrorAlertService.getEnrolledSuperadminEmails()
                        call.respond(
                            ErrorAlertsResponse(
                                success = true,
                                emailServerErrors = settings.emailServerErrors,
                                enabled = settings.emailServerErrors,
                                settings = settings,
                                additionalEmails = settings.additionalEmails,
                                enrolledSuperadmins = enrolled
                            )
                        )
                    }
                    put("/error-alerts") {
                        call.requireSuperAdmin()
                        val req = call.receive<UpdateErrorAlertsRequest>()
                        val isEnabled = req.isEnabled
                        val saved = SettingsService.updateErrorAlertSettings(
                            ErrorAlertSettings(
                                emailServerErrors = isEnabled,
                                additionalEmails = req.additionalEmails.map { it.trim() }.filter { it.isNotBlank() }
                            )
                        )
                        val enrolled = com.obsidianscout.admin.ServerErrorAlertService.getEnrolledSuperadminEmails()
                        call.respond(
                            ErrorAlertsResponse(
                                success = true,
                                emailServerErrors = saved.emailServerErrors,
                                enabled = saved.emailServerErrors,
                                settings = saved,
                                additionalEmails = saved.additionalEmails,
                                enrolledSuperadmins = enrolled,
                                message = if (saved.emailServerErrors) "Cluster server error email alerts enabled." else "Cluster server error email alerts disabled."
                            )
                        )
                    }
                    post("/error-alerts/test") {
                        call.requireSuperAdmin()
                        val (success, msg) = com.obsidianscout.admin.ServerErrorAlertService.sendTestServerErrorAlert()
                        call.respond(
                            buildJsonObject {
                                put("success", success)
                                put("message", msg)
                            }
                        )
                    }
                    get("/logs-all") {
                        call.requireSuperAdminOrClusterAuth()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                        val filter = call.request.queryParameters["filter"]
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getAllClusterLogs(limit, filter))
                    }

                    // Quorum Fallback Mirror Management
                    get("/quorum-fallback") {
                        call.requireSuperAdminOrClusterAuth()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getClusterQuorumFallbackStatus())
                    }
                    post("/quorum-fallback/toggle") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<ToggleQuorumFallbackRequest>()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.toggleQuorumFallback(req.targetIp, req.enabled))
                    }
                    post("/quorum-fallback/purge") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<TargetNodeActionRequest>()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.purgeQuorumFallback(req.targetIp))
                    }
                    post("/quorum-fallback/sync") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<TargetNodeActionRequest>()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.syncQuorumFallback(req.targetIp))
                    }
                    get("/quorum-fallback/inspect") {
                        call.requireSuperAdminOrClusterAuth()
                        val targetIp = call.request.queryParameters["targetIp"] ?: "local"
                        call.respond(com.obsidianscout.admin.ClusterManagementService.inspectNodeQuorumFallback(targetIp))
                    }
                    put("/quorum-fallback/config") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<UpdateQuorumFallbackConfigRequest>()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.updateNodeQuorumFallbackConfig(req))
                    }
                    get("/nodes/local/quorum-fallback/status") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.db.QuorumFallbackStore.getStatus(localIp))
                    }
                    get("/nodes/local/quorum-fallback/inspect") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.db.QuorumFallbackStore.inspect(localIp))
                    }
                    put("/nodes/local/quorum-fallback/config") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<UpdateQuorumFallbackConfigRequest>()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.updateNodeQuorumFallbackConfig(req.copy(targetIp = "local")))
                    }
                    post("/nodes/local/quorum-fallback/toggle") {
                        call.requireSuperAdminOrClusterAuth()
                        val enabled = call.request.queryParameters["enabled"]?.toBooleanStrictOrNull() ?: true
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.toggleQuorumFallback(localIp, enabled))
                    }
                    post("/nodes/local/quorum-fallback/purge") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.purgeQuorumFallback(localIp))
                    }
                    post("/nodes/local/quorum-fallback/sync") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.syncQuorumFallback(localIp))
                    }

                    // Automated SQLite Backup Management (Per-Server / Cluster-Wide)
                    get("/auto-backup") {
                        call.requireSuperAdminOrClusterAuth()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.getClusterAutoBackupStatus())
                    }
                    post("/auto-backup/toggle") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<ToggleAutoBackupRequest>()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.toggleNodeAutoBackup(req.targetIp, req.enabled))
                    }
                    put("/auto-backup/config") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<UpdateAutoBackupConfigRequest>()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.updateNodeAutoBackupConfig(req))
                    }
                    post("/auto-backup/create") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<TargetNodeActionRequest>()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.createNodeSnapshot(req.targetIp))
                    }
                    get("/nodes/local/auto-backup/status") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.db.SnapshotService.getLocalNodeStatus().copy(isLocal = true, nodeIp = localIp))
                    }
                    post("/nodes/local/auto-backup/toggle") {
                        call.requireSuperAdminOrClusterAuth()
                        val enabled = call.request.queryParameters["enabled"]?.toBooleanStrictOrNull() ?: true
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.toggleNodeAutoBackup(localIp, enabled))
                    }
                    put("/nodes/local/auto-backup/config") {
                        call.requireSuperAdminOrClusterAuth()
                        val req = call.receive<UpdateAutoBackupConfigRequest>()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.updateNodeAutoBackupConfig(req.copy(targetIp = localIp)))
                    }
                    post("/nodes/local/auto-backup/create") {
                        call.requireSuperAdminOrClusterAuth()
                        val localIp = com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
                        call.respond(com.obsidianscout.admin.ClusterManagementService.createNodeSnapshot(localIp))
                    }
                }

                route("/storage") {
                    get("/overview") {
                        call.requireSuperAdmin()
                        call.respond(com.obsidianscout.admin.StorageManagementService.getStorageOverview())
                    }
                    get("/events") {
                        call.requireSuperAdmin()
                        call.respond(com.obsidianscout.admin.StorageManagementService.getEventCacheStorage())
                    }
                    get("/teams") {
                        call.requireSuperAdmin()
                        call.respond(com.obsidianscout.admin.StorageManagementService.getTeamStorageUsage())
                    }
                    get("/teams/{teamNumber}") {
                        call.requireSuperAdmin()
                        val teamNumber = call.parameters["teamNumber"]?.toIntOrNull()
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Invalid team number")
                        val program = call.request.queryParameters["program"] ?: "FRC"
                        val details = com.obsidianscout.admin.StorageManagementService.getTeamDetailedStorage(teamNumber, program)
                            ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Team data not found")
                        call.respond(details)
                    }
                    post("/cache/clear-event") {
                        call.requireSuperAdmin()
                        val req = call.receive<ClearEventCacheRequest>()
                        call.respond(com.obsidianscout.admin.StorageManagementService.clearEventCache(req.eventKey))
                    }
                    post("/cache/clear-old-years") {
                        call.requireSuperAdmin()
                        val req = call.receive<ClearOldEventCachesRequest>()
                        call.respond(com.obsidianscout.admin.StorageManagementService.clearOldEventCaches(req.olderThanYear))
                    }
                    post("/cache/clear-all") {
                        call.requireSuperAdmin()
                        call.respond(com.obsidianscout.admin.StorageManagementService.clearAllApiCaches())
                    }
                    post("/user-data/delete-event") {
                        call.requireSuperAdmin()
                        val req = call.receive<DeleteEventScoutingDataRequest>()
                        if (req.confirmText.trim().uppercase() != "CONFIRM") {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Confirmation text must be 'CONFIRM' to delete team scouting data.")
                        }
                        call.respond(com.obsidianscout.admin.StorageManagementService.deleteEventScoutingData(req.eventKey, req.teamNumber, req.program))
                    }
                    post("/user-data/delete-team") {
                        call.requireSuperAdmin()
                        val req = call.receive<DeleteTeamDataRequest>()
                        val expected = "DELETE TEAM ${req.teamNumber}".trim().uppercase()
                        val actual = req.confirmText.trim().uppercase().replace(Regex("\\s+"), " ")
                        if (actual != expected) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Confirmation text must be '$expected' to permanently delete team dataset.")
                        }
                        call.respond(com.obsidianscout.admin.StorageManagementService.deleteTeamData(req.teamNumber, req.program))
                    }
                    post("/prune/config-revisions") {
                        call.requireSuperAdmin()
                        val req = call.receive<PruneConfigRevisionsRequest>()
                        call.respond(com.obsidianscout.admin.StorageManagementService.pruneConfigRevisions(req.keepLatestPerKind))
                    }
                    post("/prune/chat") {
                        call.requireSuperAdmin()
                        val req = call.receive<PruneChatMessagesRequest>()
                        call.respond(com.obsidianscout.admin.StorageManagementService.pruneChatMessages(req.olderThanDays))
                    }
                    post("/prune/sessions") {
                        call.requireSuperAdmin()
                        call.respond(com.obsidianscout.admin.StorageManagementService.pruneExpiredSessions())
                    }
                    post("/prune/errors") {
                        call.requireSuperAdmin()
                        val req = call.receive<PruneErrorReportsRequest>()
                        call.respond(com.obsidianscout.admin.StorageManagementService.pruneErrorReports(req.deleteOnlyResolved))
                    }
                    post("/maintenance/reclaim") {
                        call.requireSuperAdmin()
                        call.respond(com.obsidianscout.admin.StorageManagementService.reclaimDiskSpace())
                    }
                }

                route("/errors") {
                    get {
                        call.requireSuperAdmin()
                        val type = call.request.queryParameters["type"]
                        val status = call.request.queryParameters["status"]
                        val search = call.request.queryParameters["search"]
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                        val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                        val result = com.obsidianscout.admin.ServerErrorAlertService.listReportedErrors(
                            typeFilter = type,
                            statusFilter = status,
                            search = search,
                            limit = limit,
                            offset = offset
                        )
                        call.respond(result)
                    }
                    get("/stats") {
                        call.requireSuperAdmin()
                        val stats = com.obsidianscout.admin.ServerErrorAlertService.getReportedErrorStats()
                        call.respond(stats)
                    }
                    post("/{id}/status") {
                        val session = call.requireSuperAdmin()
                        val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing error id")
                        val req = call.receive<UpdateReportedErrorStatusRequest>()
                        val success = com.obsidianscout.admin.ServerErrorAlertService.updateReportedErrorStatus(
                            id = id,
                            newStatus = req.status,
                            resolvedByUsername = session.username
                        )
                        if (success) {
                            call.respond(UpdateReportedErrorStatusResponse(success = true, status = req.status))
                        } else {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Reported error not found")
                        }
                    }
                    delete("/{id}") {
                        call.requireSuperAdmin()
                        val id = call.parameters["id"] ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing error id")
                        val success = com.obsidianscout.admin.ServerErrorAlertService.deleteReportedError(id)
                        if (success) {
                            call.respond(DeleteReportedErrorResponse(success = true))
                        } else {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Reported error not found")
                        }
                    }
                    post("/clear") {
                        call.requireSuperAdmin()
                        val req = runCatching { call.receive<ClearReportedErrorsRequest>() }.getOrNull()
                        val cleared = com.obsidianscout.admin.ServerErrorAlertService.clearReportedErrors(req?.statusFilter)
                        call.respond(ClearReportedErrorsResponse(success = true, clearedCount = cleared))
                    }
                    post("/group/status") {
                        val session = call.requireSuperAdmin()
                        val req = call.receive<UpdateErrorGroupStatusRequest>()
                        val updated = com.obsidianscout.admin.ServerErrorAlertService.updateErrorGroupStatus(
                            errorIds = req.errorIds,
                            newStatus = req.status,
                            resolvedByUsername = session.username
                        )
                        call.respond(UpdateErrorGroupStatusResponse(success = true, updatedCount = updated, status = req.status))
                    }
                    post("/group/delete") {
                        call.requireSuperAdmin()
                        val req = call.receive<DeleteErrorGroupRequest>()
                        val deleted = com.obsidianscout.admin.ServerErrorAlertService.deleteErrorGroup(req.errorIds)
                        call.respond(DeleteErrorGroupResponse(success = true, deletedCount = deleted))
                    }
                }
            }

            post("/bug-reports") {
                val ip = call.clientIp()
                if (!com.obsidianscout.auth.BugReportRateLimiter.tryAcquire(ip)) {
                    throw com.obsidianscout.auth.ApiException(
                        HttpStatusCode.TooManyRequests,
                        "Too many bug reports submitted from this IP. Please wait before submitting more."
                    )
                }
                val session = call.sessions.get<UserSession>()
                val request = call.receive<ClientBugReportRequest>()
                com.obsidianscout.admin.ServerErrorAlertService.recordClientBugReport(request, session)
                call.respond(buildJsonObject {
                    put("success", true)
                    put("message", "Bug report received. Thank you!")
                })
            }
}
/** Admins report on their own team; superadmins may pick any team with `?teamNumber=`. */
private fun scouterReportTeam(session: UserSession, requested: String?): Int {
    val team = requested?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    return if (team != null && session.role == UserRole.SUPERADMIN) team else session.teamNumber
}
