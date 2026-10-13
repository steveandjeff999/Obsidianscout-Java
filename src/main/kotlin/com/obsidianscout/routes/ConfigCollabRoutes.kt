package com.obsidianscout.routes

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.hasValidClusterSignature
import com.obsidianscout.auth.requireSession
import com.obsidianscout.collab.CollabParticipant
import com.obsidianscout.collab.ConfigCollabHub
import com.obsidianscout.collab.ConfigCollabRelay
import com.obsidianscout.collab.ConfigCollabScope
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.scouting.BatteryTrackerService
import com.obsidianscout.scouting.MatchChecklistService
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

private suspend fun DefaultWebSocketServerSession.reject(reason: String) =
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))

/**
 * Live (multi-editor) config editing sockets. Mounted under /api.
 *  - /api/config-collab/team/{kind}       the signed-in team's game/pit/qual config (admins)
 *  - /api/config-collab/preset/{id}       a default config preset (superadmins)
 *  - /api/alliances/{id}/collaborate/{kind} an alliance's shared config (members; alliance admins edit)
 */
fun Route.configCollabRoutes() {
    webSocket("/config-collab/team/{kind}") {
        val session = runCatching { call.requireSession() }.getOrNull() ?: return@webSocket reject("No session")
        if (!session.role.isAtLeast(UserRole.ADMIN)) return@webSocket reject("Admin access required")
        val kind = ConfigCollabScope.normalizeKind(call.parameters["kind"]) ?: return@webSocket reject("Invalid config kind")
        val participant = CollabParticipant(
            sid = CollabParticipant.sanitizeSid(call.request.queryParameters["sid"]),
            userId = session.userId,
            username = session.username,
            teamNumber = session.teamNumber,
            role = session.role.name,
            canEdit = true
        )
        ConfigCollabHub.connect(this, ConfigCollabScope.Team(session.teamNumber, session.program, kind), participant)
    }

    webSocket("/config-collab/preset/{id}") {
        val session = runCatching { call.requireSession() }.getOrNull() ?: return@webSocket reject("No session")
        if (session.role != UserRole.SUPERADMIN) return@webSocket reject("Superadmin access required")
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@webSocket reject("Invalid preset id")
        val participant = CollabParticipant(
            sid = CollabParticipant.sanitizeSid(call.request.queryParameters["sid"]),
            userId = session.userId,
            username = session.username,
            teamNumber = session.teamNumber,
            role = session.role.name,
            canEdit = true
        )
        ConfigCollabHub.connect(this, ConfigCollabScope.Preset(id), participant)
    }
}

/**
 * Live drive-team checklists, mounted under /api. Any member of the team can edit.
 *  - /api/match-checklist/collab?template=1                       the team's checklist template
 *  - /api/match-checklist/collab?eventKey=E&matchKey=M            one match's filled-out checklist
 */
fun Route.matchChecklistCollabRoute() {
    webSocket("/match-checklist/collab") {
        val session = runCatching { call.requireSession() }.getOrNull() ?: return@webSocket reject("No session")
        val q = call.request.queryParameters
        val scope = if (q["template"] == "1") {
            ConfigCollabScope.Checklist(session.teamNumber, session.program, "", MatchChecklistService.TEMPLATE_KEY)
        } else {
            val eventKey = q["eventKey"].orEmpty()
            val matchKey = q["matchKey"].orEmpty()
            if (!MatchChecklistService.isValidKey(eventKey) || !MatchChecklistService.isValidKey(matchKey)) {
                return@webSocket reject("Invalid match")
            }
            ConfigCollabScope.Checklist(session.teamNumber, session.program, eventKey, matchKey)
        }
        val participant = CollabParticipant(
            sid = CollabParticipant.sanitizeSid(q["sid"]),
            userId = session.userId,
            username = session.username,
            teamNumber = session.teamNumber,
            role = session.role.name,
            canEdit = true
        )
        ConfigCollabHub.connect(this, scope, participant)
    }
}

/**
 * Live battery tracker, mounted under /api. Any member of the team can edit.
 *  - /api/battery-tracker/collab?inventory=1        the team's batteries
 *  - /api/battery-tracker/collab?eventKey=E         one event's rotation plan (skips, pins, used)
 */
fun Route.batteryTrackerCollabRoute() {
    webSocket("/battery-tracker/collab") {
        val session = runCatching { call.requireSession() }.getOrNull() ?: return@webSocket reject("No session")
        val q = call.request.queryParameters
        val scope = if (q["inventory"] == "1") {
            ConfigCollabScope.Batteries(session.teamNumber, session.program, "")
        } else {
            val eventKey = q["eventKey"].orEmpty()
            if (!BatteryTrackerService.isValidEventKey(eventKey)) return@webSocket reject("Invalid event")
            ConfigCollabScope.Batteries(session.teamNumber, session.program, eventKey)
        }
        val participant = CollabParticipant(
            sid = CollabParticipant.sanitizeSid(q["sid"]),
            userId = session.userId,
            username = session.username,
            teamNumber = session.teamNumber,
            role = session.role.name,
            canEdit = true
        )
        ConfigCollabHub.connect(this, scope, participant)
    }
}

/** The alliance editor's socket; kept at its original path under /api/alliances. */
fun Route.allianceConfigCollabRoute() {
    webSocket("/{id}/collaborate/{kind}") {
        val session = runCatching { call.requireSession() }.getOrNull() ?: return@webSocket reject("No session")
        val allianceId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return@webSocket reject("Invalid alliance ID")
        val kind = ConfigCollabScope.normalizeKind(call.parameters["kind"]) ?: return@webSocket reject("Invalid config kind")

        val (isMember, isAllianceAdmin) = withContext(Dispatchers.IO) {
            val member = com.obsidianscout.db.readTransaction {
                AllianceMemberships.selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                        (AllianceMemberships.teamNumber eq session.teamNumber) and
                        (AllianceMemberships.status inList listOf("ADMIN", "ACCEPTED"))
                }.any()
            }
            member to (member && AllianceService.isAllianceAdmin(session.teamNumber, allianceId))
        }
        if (!isMember) return@webSocket reject("Not a member")

        val participant = CollabParticipant(
            sid = CollabParticipant.sanitizeSid(call.request.queryParameters["sid"]),
            userId = session.userId,
            username = session.username,
            teamNumber = session.teamNumber,
            role = session.role.name,
            canEdit = isAllianceAdmin
        )
        ConfigCollabHub.connect(this, ConfigCollabScope.Alliance(allianceId, kind), participant)
    }
}

/** Inter-node relay endpoint; mounted under /api/cluster. */
fun Route.configCollabClusterRoutes() {
    webSocket("/config-collab/relay") {
        if (!call.hasValidClusterSignature()) return@webSocket reject("Invalid cluster signature")
        val q = call.request.queryParameters
        val roomKey = ConfigCollabRelay.decode(q["room"]) ?: return@webSocket reject("Missing room")
        val scope = ConfigCollabScope.fromRoomKey(roomKey) ?: return@webSocket reject("Invalid room")
        val participant = CollabParticipant(
            sid = CollabParticipant.sanitizeSid(ConfigCollabRelay.decode(q["sid"])),
            userId = ConfigCollabRelay.decode(q["uid"]) ?: "",
            username = ConfigCollabRelay.decode(q["user"]) ?: "",
            teamNumber = ConfigCollabRelay.decode(q["team"])?.toIntOrNull() ?: 0,
            role = ConfigCollabRelay.decode(q["role"]) ?: "",
            canEdit = ConfigCollabRelay.decode(q["edit"]) == "1"
        )
        ConfigCollabHub.connectRelayed(this, scope, participant)
    }
}
