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

private fun syncInputsChanged(before: ApiSettings, after: ApiSettings): Boolean =
    before.resolvedEventKey() != after.resolvedEventKey() ||
        before.alternateEventCode != after.alternateEventCode ||
        before.timezone != after.timezone ||
        before.apiKeys != after.apiKeys ||
        before.useStatboticsEpa != after.useStatboticsEpa ||
        before.useTbaOpr != after.useTbaOpr ||
        before.useMatch13Exp != after.useMatch13Exp ||
        before.statboticsBaseUrl != after.statboticsBaseUrl ||
        before.match13BaseUrl != after.match13BaseUrl

private fun ApiSettings.toPayload(): ApiSettingsPayload {
    val activeTheme = themes.firstOrNull { it.name == activeThemeName } ?: themes.firstOrNull() ?: theme
    return ApiSettingsPayload(
        year = year,
        eventCode = eventCode,
        alternateEventCode = alternateEventCode,
        eventKey = eventKey,
        timezone = timezone,
        useStatboticsEpa = useStatboticsEpa,
        useTbaOpr = useTbaOpr,
        useMatch13Exp = useMatch13Exp,
        recencyBias = recencyBias,
        chatEnabled = chatEnabled,
        matchPredictionsEnabled = matchPredictionsEnabled,
        dataPublic = dataPublic,
        dataPublicIncludeNotes = dataPublicIncludeNotes,
        apiKeys = ApiKeysPayload(
            tbaKey = if (apiKeys.tbaKey.isNotBlank()) "********" else "",
            firstUsername = apiKeys.firstUsername,
            firstKey = if (apiKeys.firstKey.isNotBlank()) "********" else "",
            match13Key = if (apiKeys.match13Key.isNotBlank()) "********" else ""
        ),
        scoutPages = scoutPages,
        analyticsPages = analyticsPages,
        adminPages = adminPages,
        theme = activeTheme,
        themes = themes,
        activeThemeName = activeThemeName,
        setupWizardCompleted = setupWizardCompleted,
        registrationLocked = registrationLocked,
        selfRegisterRoles = selfRegisterRoles,
        assignmentReminderMinutes = assignmentReminderMinutes,
        enableAssignmentPushReminders = enableAssignmentPushReminders,
        enableAssignmentEmailReminders = enableAssignmentEmailReminders,
        program = program,
        statboticsBaseUrl = statboticsBaseUrl,
        match13BaseUrl = match13BaseUrl
    )
}

/** What a public data visitor may see of a team's settings: the event, stat sources and theme, nothing else. */
private fun ApiSettings.toPublicPayload(): ApiSettingsPayload {
    val full = toPayload()
    return ApiSettingsPayload(
        year = full.year,
        eventCode = full.eventCode,
        eventKey = full.eventKey.ifBlank { resolvedEventKey() },
        timezone = full.timezone,
        useStatboticsEpa = full.useStatboticsEpa,
        useTbaOpr = full.useTbaOpr,
        useMatch13Exp = full.useMatch13Exp,
        recencyBias = full.recencyBias,
        chatEnabled = false,
        matchPredictionsEnabled = false,
        dataPublic = true,
        theme = full.theme,
        themes = full.themes,
        activeThemeName = full.activeThemeName,
        setupWizardCompleted = true,
        program = full.program
    )
}

private fun ApiSettingsPayload.toSettings(): ApiSettings {
    return ApiSettings(
        year = year,
        eventCode = eventCode,
        alternateEventCode = alternateEventCode.orEmpty(),
        timezone = timezone,
        useStatboticsEpa = useStatboticsEpa,
        useTbaOpr = useTbaOpr,
        useMatch13Exp = useMatch13Exp,
        recencyBias = recencyBias ?: com.obsidianscout.integrations.RecencyBiasSettings(),
        chatEnabled = chatEnabled,
        matchPredictionsEnabled = matchPredictionsEnabled ?: false,
        dataPublic = dataPublic ?: false,
        dataPublicIncludeNotes = dataPublicIncludeNotes ?: false,
        apiKeys = com.obsidianscout.integrations.ApiKeys(
            tbaKey = apiKeys.tbaKey,
            firstUsername = apiKeys.firstUsername,
            firstKey = apiKeys.firstKey,
            match13Key = apiKeys.match13Key
        ),
        scoutPages = if (scoutPages.isEmpty()) com.obsidianscout.integrations.DEFAULT_SCOUT_PAGES else scoutPages,
        analyticsPages = if (analyticsPages.isEmpty()) com.obsidianscout.integrations.DEFAULT_ANALYTICS_PAGES else analyticsPages,
        adminPages = if (adminPages.isEmpty()) com.obsidianscout.integrations.DEFAULT_ADMIN_PAGES else adminPages,
        theme = theme,
        themes = themes,
        activeThemeName = activeThemeName,
        setupWizardCompleted = setupWizardCompleted,
        registrationLocked = registrationLocked,
        assignmentReminderMinutes = assignmentReminderMinutes ?: 15,
        enableAssignmentPushReminders = enableAssignmentPushReminders ?: true,
        enableAssignmentEmailReminders = enableAssignmentEmailReminders ?: true,
        program = program,
        statboticsBaseUrl = statboticsBaseUrl,
        match13BaseUrl = match13BaseUrl
    )
}

fun Route.configRoutes() {
            route("/config") {
                get("/defaults") {
                    val session = call.requireSession()
                    val type = call.request.queryParameters["type"]
                    val presets = ConfigService.getDefaultConfigs(session.program, type)
                    call.respond(presets)
                }
                post("/apply-default") {
                    val session = call.requireAdminOrPage("admin-settings")
                    val request = call.receive<ApplyDefaultConfigRequest>()
                    val updated = ConfigService.applyDefaultConfig(session.teamNumber, session.program, request.configType, request.presetName)
                    call.respond(updated)
                }
                post("/reset") {
                    val session = call.requireAdminOrPage("admin-settings")
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
                    val session = call.requireAdminOrPage("admin-settings")
                    val request = call.receive<ConfigUpdateRequest>()
                    val oldConfig = runCatching { ConfigService.getConfig(session.teamNumber, session.program, local = true) }.getOrNull()
                    ConfigService.requireNoBlankOptionValues(request.configJson)
                    val updated = ConfigService.updateConfig(session.teamNumber, session.program, request.configJson)
                    val (hasChanges, changedFields) = ConfigMigrationService.detectFieldChanges(oldConfig, updated, session.teamNumber, session.program, "game")
                    val entryCount = ConfigMigrationService.countExistingEntries(session.teamNumber, session.program, "game")

                    val changeSummary = if (changedFields.isEmpty()) "Config updated (no field structure changes)" else changedFields.joinToString(", ")
                    ConfigMigrationService.saveRevision(
                        teamNumber = session.teamNumber,
                        program = session.program,
                        kind = "game",
                        config = updated,
                        changeSummary = changeSummary,
                        savedByUsername = session.username
                    )

                    call.respond(ConfigUpdateResponse(
                        config = updated,
                        hasFieldChanges = hasChanges,
                        changedFields = changedFields,
                        entryCount = entryCount,
                        configKind = "game"
                    ))
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
                    val session = call.requireAdminOrPage("admin-settings")
                    val request = call.receive<ConfigUpdateRequest>()
                    val oldConfig = runCatching { ConfigService.getPitConfig(session.teamNumber, session.program, local = true) }.getOrNull()
                    ConfigService.requireNoBlankOptionValues(request.configJson)
                    val updated = ConfigService.updatePitConfig(session.teamNumber, session.program, request.configJson)
                    val (hasChanges, changedFields) = ConfigMigrationService.detectFieldChanges(oldConfig, updated, session.teamNumber, session.program, "pit")
                    val entryCount = ConfigMigrationService.countExistingEntries(session.teamNumber, session.program, "pit")

                    val changeSummary = if (changedFields.isEmpty()) "Pit config updated" else changedFields.joinToString(", ")
                    ConfigMigrationService.saveRevision(
                        teamNumber = session.teamNumber,
                        program = session.program,
                        kind = "pit",
                        config = updated,
                        changeSummary = changeSummary,
                        savedByUsername = session.username
                    )

                    call.respond(ConfigUpdateResponse(
                        config = updated,
                        hasFieldChanges = hasChanges,
                        changedFields = changedFields,
                        entryCount = entryCount,
                        configKind = "pit"
                    ))
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
                    val session = call.requireAdminOrPage("admin-settings")
                    val request = call.receive<ConfigUpdateRequest>()
                    val oldConfig = runCatching { ConfigService.getQualitativeConfig(session.teamNumber, session.program, local = true) }.getOrNull()
                    ConfigService.requireNoBlankOptionValues(request.configJson)
                    val updated = ConfigService.updateQualitativeConfig(session.teamNumber, session.program, request.configJson)
                    val (hasChanges, changedFields) = ConfigMigrationService.detectFieldChanges(oldConfig, updated, session.teamNumber, session.program, "qual")
                    val entryCount = ConfigMigrationService.countExistingEntries(session.teamNumber, session.program, "qual")

                    val changeSummary = if (changedFields.isEmpty()) "Qualitative config updated" else changedFields.joinToString(", ")
                    ConfigMigrationService.saveRevision(
                        teamNumber = session.teamNumber,
                        program = session.program,
                        kind = "qual",
                        config = updated,
                        changeSummary = changeSummary,
                        savedByUsername = session.username
                    )

                    call.respond(ConfigUpdateResponse(
                        config = updated,
                        hasFieldChanges = hasChanges,
                        changedFields = changedFields,
                        entryCount = entryCount,
                        configKind = "qual"
                    ))
                }
            }

            route("/config-migration") {
                get("/status") {
                    val session = call.requireAdminOrPage("admin-settings")
                    val kind = call.request.queryParameters["kind"] ?: "game"
                    val status = ConfigMigrationService.getSchemaStatus(session.teamNumber, session.program, kind)
                    call.respond(status)
                }
                post("/preview") {
                    val session = call.requireAdminOrPage("admin-settings")
                    val request = call.receive<ConfigMigrationRequest>()
                    val preview = ConfigMigrationService.previewMigration(session.teamNumber, session.program, request)
                    call.respond(preview)
                }
                post("/apply") {
                    val session = call.requireAdminOrPage("admin-settings")
                    val request = call.receive<ConfigMigrationRequest>()
                    val result = ConfigMigrationService.applyMigration(session.teamNumber, session.program, request)
                    call.respond(result)
                }
            }

            route("/config-history") {
                get {
                    val session = call.requireAdminOrPage("admin-settings")
                    val kind = call.request.queryParameters["kind"] ?: "game"
                    val revisions = ConfigMigrationService.listRevisions(session.teamNumber, session.program, kind)
                    call.respond(revisions)
                }
                get("/{id}") {
                    val session = call.requireAdminOrPage("admin-settings")
                    val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing revision id")
                    val revision = ConfigMigrationService.getRevision(id, session.teamNumber, session.program)
                        ?: throw ApiException(HttpStatusCode.NotFound, "Revision not found")
                    call.respond(revision)
                }
                post("/{id}/restore") {
                    val session = call.requireAdminOrPage("admin-settings")
                    val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing revision id")
                    val result = ConfigMigrationService.restoreRevision(id, session.teamNumber, session.program, session.username)
                    call.respond(result)
                }
            }

            route("/gamepad/profiles") {
                get {
                    val session = call.requireSession()
                    val profiles = GamepadProfileService.listProfiles(session.teamNumber, session.program)
                    call.respond(GamepadProfileListResponse(profiles = profiles))
                }
                get("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing profile id")
                    val profile = GamepadProfileService.getProfile(id, session.teamNumber, session.program)
                        ?: throw ApiException(HttpStatusCode.NotFound, "Gamepad profile not found")
                    call.respond(GamepadProfileResponse(profile = profile))
                }
                post {
                    val session = call.requireSession()
                    val profileDto = call.receive<GamepadProfileDTO>()
                    val saved = GamepadProfileService.saveProfile(
                        teamNumber = session.teamNumber,
                        program = session.program,
                        profile = profileDto,
                        updatedBy = session.username
                    )
                    call.respond(GamepadProfileResponse(profile = saved))
                }
                delete("/{id}") {
                    val session = call.requireSession()
                    val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing profile id")
                    val success = GamepadProfileService.deleteProfile(id, session.teamNumber, session.program)
                    if (!success) {
                        throw ApiException(HttpStatusCode.NotFound, "Gamepad profile not found")
                    }
                    call.respond(HttpStatusCode.OK, mapOf("success" to true))
                }
            }

            route("/team") {
                get("/secret-key") {
                    val session = call.requireSession()
                    val secret = TeamSecretService.getOrCreateTeamSecret(session.teamNumber)
                    call.respond(TeamSecretResponse(teamSecret = secret, teamNumber = session.teamNumber))
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
                            // Effective settings are cached per node and a save only clears the cache on the
                            // node that handled it, so read the theme fresh: open pages poll this to pick up a
                            // theme change, and other nodes would otherwise serve the old one for up to a minute.
                            val fresh = SettingsService.getSettings(session.teamNumber, session.program)
                            AllianceService.getEffectiveSettings(session.teamNumber, session.program).copy(
                                theme = fresh.theme,
                                themes = fresh.themes,
                                activeThemeName = fresh.activeThemeName
                            )
                        }
                    }
                    val payload = when {
                        session.publicView -> settings.toPublicPayload()
                        // A CUSTOM user only learns their own pages, never anyone else's.
                        session.role == UserRole.CUSTOM -> settings.toPayload().copy(
                            customPages = SettingsService.customPagesFor(session.teamNumber, session.program, session.userId)
                        )
                        else -> settings.toPayload()
                    }
                    call.respond(SettingsResponse(payload))
                }
                put {
                    val session = call.requireAdminOrPage("admin-settings")
                    val payload = call.receive<ApiSettingsPayload>()
                    val targetProgram = if (payload.program.isNotBlank()) payload.program else session.program
                    // Pages that save only part of the settings don't send selfRegisterRoles; keep the saved value then.
                    val previous = SettingsService.getSettings(session.teamNumber, targetProgram)
                    val selfRegisterRoles = payload.selfRegisterRoles ?: previous.selfRegisterRoles
                    val recencyBias = payload.recencyBias ?: previous.recencyBias
                    val alternateEventCode = payload.alternateEventCode ?: previous.alternateEventCode
                    val matchPredictionsEnabled = payload.matchPredictionsEnabled ?: previous.matchPredictionsEnabled
                    val dataPublic = payload.dataPublic ?: previous.dataPublic
                    val dataPublicIncludeNotes = payload.dataPublicIncludeNotes ?: previous.dataPublicIncludeNotes
                    val assignmentReminderMinutes = payload.assignmentReminderMinutes ?: previous.assignmentReminderMinutes
                    val enableAssignmentPushReminders = payload.enableAssignmentPushReminders ?: previous.enableAssignmentPushReminders
                    val enableAssignmentEmailReminders = payload.enableAssignmentEmailReminders ?: previous.enableAssignmentEmailReminders
                    val updated = SettingsService.updateSettings(
                        session.teamNumber,
                        payload.toSettings().copy(
                            program = targetProgram,
                            selfRegisterRoles = selfRegisterRoles,
                            recencyBias = recencyBias,
                            alternateEventCode = alternateEventCode,
                            matchPredictionsEnabled = matchPredictionsEnabled,
                            dataPublic = dataPublic,
                            dataPublicIncludeNotes = dataPublicIncludeNotes,
                            assignmentReminderMinutes = assignmentReminderMinutes,
                            enableAssignmentPushReminders = enableAssignmentPushReminders,
                            enableAssignmentEmailReminders = enableAssignmentEmailReminders,
                            customUserPages = previous.customUserPages,
                            // Edited on the assignments page, never sent by the settings form
                            pitScoutGroups = previous.pitScoutGroups,
                            assignmentShifts = previous.assignmentShifts
                        )
                    )
                    val activeAllianceId = AllianceService.getActiveAllianceId(session.teamNumber, targetProgram)
                    if (activeAllianceId != null && AllianceService.isAllianceAdmin(session.teamNumber, activeAllianceId)) {
                        AllianceService.updateAllianceEvent(
                            activeAllianceId,
                            updated.year,
                            updated.eventCode,
                            updated.eventKey
                        )
                    }
                    if (syncInputsChanged(previous, updated) && !com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
                        val effective = AllianceService.getEffectiveSettings(session.teamNumber, targetProgram)
                        SyncScheduler.enqueueEventDataSync(session.teamNumber, effective)
                    }
                    call.respond(SettingsResponse(updated.toPayload()))
                }
                post("/dismiss-wizard") {
                    val session = call.requireAdminOrPage("admin-settings")
                    val updated = SettingsService.dismissSetupWizard(session.teamNumber, session.program)
                    call.respond(SettingsResponse(updated.toPayload()))
                }
                post("/test-api") {
                    val session = call.requireAdminOrPage("admin-settings")
                    val request = call.receive<TestApiRequest>()
                    val response = IntegrationService.testApiKey(session, request)
                    call.respond(response)
                }
            }
}