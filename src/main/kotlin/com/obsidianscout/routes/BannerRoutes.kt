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

private val bannerLogger = org.slf4j.LoggerFactory.getLogger("BannerRoutes")

/**
 * The banner for a team without its own API credentials ([SettingsService.hasOwnApiCredentials]), or null when it
 * has them. Synced rows are shared by every team, so when another team syncs this event ([sharedByAnotherTeam]) the
 * banner says the data is shared instead of warning that syncing is off. FTC still syncs from FTC Scout without a
 * key; only FIRST's data needs one.
 */
internal suspend fun apiKeyBanner(
    teamNumber: Int,
    program: String,
    eventKey: String,
    keys: com.obsidianscout.integrations.ApiKeys,
    sharedByAnotherTeam: suspend () -> Boolean = { SettingsService.isEventSyncedByAnotherTeam(teamNumber, program, eventKey) }
): BannerDto? {
    if (SettingsService.hasOwnApiCredentials(program, keys)) return null
    val isFtc = program.equals("FTC", ignoreCase = true)
    val shared = try {
        sharedByAnotherTeam()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Quorum loss has its own banner, added by the caller.
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLossException(e)) throw e
        bannerLogger.warn("Couldn't check whether another team shares event $eventKey with team $teamNumber ($program); showing the no-key warning", e)
        false
    }
    val (id, type, message, details) = when {
        shared && isFtc -> listOf(
            "sys-shared-event-data",
            "info",
            "FIRST API teams and matches for this event are being shared by another team on this server. For better reliability, we highly recommend adding your own FIRST API key in Admin Settings.",
            "Your team still syncs from FTC Scout, which needs no key. Another team on this server has a FIRST API key and is set to this event, so its sync adds FIRST's teams and matches for you as well. " +
                "That part only stays up to date while that team keeps syncing this event. " +
                "With your own FIRST API key, your team gets FIRST's data on its own schedule, for any event."
        )
        shared -> listOf(
            "sys-shared-event-data",
            "info",
            "Teams and matches for this event are being shared by another team on this server. For better reliability, we highly recommend adding your own API key in Admin Settings.",
            "Another team on this server has an API key and is set to this event, so its sync fills in teams and matches for you as well. " +
                "That data only stays up to date while that team keeps syncing this event, and your team can't sync any other event. " +
                "With your own The Blue Alliance or FIRST API key, your team syncs on its own schedule."
        )
        isFtc -> listOf(
            "sys-no-api-key",
            "warning",
            "No FIRST API key is currently configured for your team. Teams and matches sync from FTC Scout only; add a FIRST API key in Admin Settings to include FIRST's data.",
            ""
        )
        else -> listOf(
            "sys-no-api-key",
            "warning",
            "No API Key is currently configured for your team. External data syncing (The Blue Alliance / FIRST) is disabled.",
            ""
        )
    }
    val now = java.time.Instant.now().toString()
    return BannerDto(
        id = id,
        teamNumber = teamNumber,
        program = if (isFtc) "FTC" else "FRC",
        message = message,
        bannerType = type,
        isDismissible = true,
        isExpandable = details.isNotEmpty(),
        expandableMessage = details,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )
}

fun Route.bannerRoutes() {
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
                                message = "Database Quorum Lost: CockroachDB cluster has lost quorum (majority of nodes offline). Database read/write operations are temporarily restricted until quorum is restored.",
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
                        val dbBanners = com.obsidianscout.db.BannerService.getActive(session.teamNumber, session.program)
                        active.addAll(dbBanners)

                        // If user is an admin on an affected team with an active alliance, validate the alliance configuration
                        if (session.role.isAtLeast(com.obsidianscout.auth.UserRole.ADMIN)) {
                            val activeAlliance = com.obsidianscout.scouting.AllianceService.getActiveAllianceValidation(session.teamNumber, session.program)
                            if (activeAlliance != null) {
                                val (activeAllianceId, validation) = activeAlliance
                                if (validation != null && validation.isMisconfigured) {
                                    val count = validation.issues.size
                                    val issuesSummary = validation.issues.joinToString(", ") { it.category }
                                    val detailsBuilder = StringBuilder()
                                    detailsBuilder.append("Active Alliance \"${validation.allianceName}\" is missing required configuration:\n\n")
                                    validation.issues.forEachIndexed { index, issue ->
                                        detailsBuilder.append("${index + 1}. ${issue.category} [Location: ${issue.location}]\n")
                                        detailsBuilder.append("   • Error: ${issue.error}\n")
                                        detailsBuilder.append("   • Fix: ${issue.fixAction}\n")
                                        detailsBuilder.append("   • Impact: ${issue.impact}\n\n")
                                    }
                                    detailsBuilder.append("👉 Go to Alliances (/alliances) to complete configuration and use shared alliance data.")

                                    active.add(
                                        0,
                                        com.obsidianscout.routes.BannerDto(
                                            id = "sys-alliance-misconfigured-${activeAllianceId}",
                                            teamNumber = session.teamNumber,
                                            message = "⚠️ Active Alliance \"${validation.allianceName}\" is not properly configured (${count} issue${if (count == 1) "" else "s"}: ${issuesSummary}). Scouting forms and schedule syncing are falling back to local team settings.",
                                            bannerType = "warning",
                                            isDismissible = true,
                                            isExpandable = true,
                                            expandableMessage = detailsBuilder.toString().trimEnd(),
                                            isActive = true,
                                            createdAt = java.time.Instant.now().toString(),
                                            updatedAt = java.time.Instant.now().toString()
                                        )
                                    )
                                }
                            }
                        }

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

                        apiKeyBanner(session.teamNumber, session.program, eventKey, settings.apiKeys)?.let { active.add(0, it) }
                    } catch (e: Exception) {
                        if (orchestrator.isQuorumLossException(e)) {
                            orchestrator.isQuorumLost = true
                            if (!active.any { it.id == "sys-db-quorum-lost" }) {
                                active.add(
                                    0,
                                    BannerDto(
                                        id = "sys-db-quorum-lost",
                                        teamNumber = session.teamNumber,
                                        message = "Database Quorum Lost: CockroachDB cluster has lost quorum (majority of nodes offline). Database read/write operations are temporarily restricted until quorum is restored.",
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
                    val session = call.requireAdminOrPage("banners")
                    val all = if (session.role == com.obsidianscout.auth.UserRole.SUPERADMIN) {
                        com.obsidianscout.db.BannerService.getAll(program = session.program)
                    } else {
                        com.obsidianscout.db.BannerService.getAll(session.teamNumber, program = session.program)
                    }
                    call.respond(all)
                }
                post {
                    val session = call.requireAdminOrPage("banners")
                    val request = call.receive<com.obsidianscout.routes.BannerCreateRequest>()
                    
                    val targetTeam = request.teamNumber ?: 0
                    if (targetTeam == 0) {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can create sitewide banners")
                        }
                    } else {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && targetTeam != session.teamNumber) {
                            com.obsidianscout.security.SecurityMonitor.crossTeam(call, session, targetTeam, "banner (create)")
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only create banners for your own team")
                        }
                    }

                    val created = com.obsidianscout.db.BannerService.create(request, defaultProgram = session.program)
                    com.obsidianscout.db.NotificationWebSocketManager.invalidateAll(listOf("banners"))
                    call.respond(created)
                }
                put("/{id}") {
                    val session = call.requireAdminOrPage("banners")
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")
                    val request = call.receive<com.obsidianscout.routes.BannerUpdateRequest>()

                    val existing = com.obsidianscout.db.BannerService.getById(id)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Banner not found")

                    if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && existing.program != session.program) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only modify banners for your own program")
                    }

                    if (existing.teamNumber == 0) {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can modify sitewide banners")
                        }
                    } else {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && existing.teamNumber != session.teamNumber) {
                            com.obsidianscout.security.SecurityMonitor.crossTeam(call, session, existing.teamNumber, "banner (modify)")
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
                                com.obsidianscout.security.SecurityMonitor.crossTeam(call, session, targetTeam, "banner (retarget)")
                                throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only target banners to your own team")
                            }
                        }
                    }

                    val updated = com.obsidianscout.db.BannerService.update(id, request)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Banner not found")
                    com.obsidianscout.db.NotificationWebSocketManager.invalidateAll(listOf("banners"))
                    call.respond(updated)
                }
                delete("/{id}") {
                    val session = call.requireAdminOrPage("banners")
                    val id = call.parameters["id"]
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.BadRequest, "Missing or invalid id")

                    val existing = com.obsidianscout.db.BannerService.getById(id)
                        ?: throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Banner not found")

                    if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && existing.program != session.program) {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only delete banners for your own program")
                    }

                    if (existing.teamNumber == 0) {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "Only superadmins can delete sitewide banners")
                        }
                    } else {
                        if (session.role != com.obsidianscout.auth.UserRole.SUPERADMIN && existing.teamNumber != session.teamNumber) {
                            com.obsidianscout.security.SecurityMonitor.crossTeam(call, session, existing.teamNumber, "banner (delete)")
                            throw com.obsidianscout.auth.ApiException(HttpStatusCode.Forbidden, "You can only delete banners for your own team")
                        }
                    }

                    val deleted = com.obsidianscout.db.BannerService.delete(id)
                    if (deleted) {
                        com.obsidianscout.db.NotificationWebSocketManager.invalidateAll(listOf("banners"))
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        throw com.obsidianscout.auth.ApiException(HttpStatusCode.NotFound, "Banner not found")
                    }
                }
            }
}