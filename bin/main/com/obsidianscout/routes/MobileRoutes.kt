package com.obsidianscout.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.UserRecord
import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.Users
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.scouting.ScoutingService
import com.obsidianscout.scouting.PitScoutingService
import com.obsidianscout.scouting.QualitativeScoutingService
import com.obsidianscout.scouting.AllianceService
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.call.body
import io.ktor.client.request.header
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.dao.id.EntityID
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Exceptions & Base DTOs
// ─────────────────────────────────────────────────────────────────────────────

class MobileApiException(
    val status: HttpStatusCode,
    override val message: String,
    val errorCode: String
) : RuntimeException(message)

@Serializable
data class MobileErrorResponse(
    val success: Boolean = false,
    val error: String,
    @SerialName("error_code") val errorCode: String
)

// ─────────────────────────────────────────────────────────────────────────────
// JWT Authentication Helper
// ─────────────────────────────────────────────────────────────────────────────

object JwtHelper {
    private fun getAlgorithm(secret: String): Algorithm = Algorithm.HMAC256(secret)

    fun generateToken(session: UserSession, secret: String, expiresAt: Date): String {
        return JWT.create()
            .withClaim("userId", session.userId)
            .withClaim("username", session.username)
            .withClaim("teamNumber", session.teamNumber)
            .withClaim("role", session.role.name)
            .withClaim("email", session.email)
            .withExpiresAt(expiresAt)
            .sign(getAlgorithm(secret))
    }

    fun verifyToken(token: String, secret: String): UserSession? {
        return try {
            val verifier = JWT.require(getAlgorithm(secret)).build()
            val jwt = verifier.verify(token)
            val userId = jwt.getClaim("userId").asInt() ?: return null
            val username = jwt.getClaim("username").asString() ?: return null
            val teamNumber = jwt.getClaim("teamNumber").asInt() ?: return null
            val roleStr = jwt.getClaim("role").asString() ?: return null
            val role = try { UserRole.valueOf(roleStr) } catch (_: Exception) { return null }
            val email = jwt.getClaim("email").asString()
            UserSession(
                userId = userId,
                username = username,
                teamNumber = teamNumber,
                role = role,
                email = email
            )
        } catch (e: Exception) {
            null
        }
    }
}

suspend fun ApplicationCall.requireMobileSession(secret: String): UserSession {
    val authHeader = request.headers["Authorization"]
        ?: throw MobileApiException(HttpStatusCode.Unauthorized, "Authentication token is missing", "AUTH_REQUIRED")
    if (!authHeader.startsWith("Bearer ")) {
        throw MobileApiException(HttpStatusCode.Unauthorized, "Invalid token format", "INVALID_TOKEN")
    }
    val token = authHeader.removePrefix("Bearer ").trim()
    val session = JwtHelper.verifyToken(token, secret)
        ?: throw MobileApiException(HttpStatusCode.Unauthorized, "Invalid or expired token", "INVALID_TOKEN")
    val exists = transaction {
        Users.selectAll().where { Users.id eq session.userId }.any()
    }
    if (!exists) {
        throw MobileApiException(HttpStatusCode.Unauthorized, "Account has been deleted", "AUTH_REQUIRED")
    }
    return session
}

suspend fun ApplicationCall.requireMobileAdmin(secret: String): UserSession {
    val session = requireMobileSession(secret)
    if (!session.role.isAtLeast(UserRole.ADMIN)) {
        throw MobileApiException(HttpStatusCode.Forbidden, "Admin access required", "FORBIDDEN")
    }
    return session
}

// ─────────────────────────────────────────────────────────────────────────────
// Mobile DTOs
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class MobileHealthResponse(
    val success: Boolean = true,
    val status: String = "healthy",
    val version: String = "1.0",
    val timestamp: String
)

@Serializable
data class MobileLoginRequest(
    val username: String? = null,
    val password: String,
    @SerialName("team_number") val teamNumber: Int
)

@Serializable
data class MobileUser(
    val id: Int,
    val username: String,
    @SerialName("team_number") val teamNumber: Int,
    val roles: List<String>,
    @SerialName("profile_picture") val profilePicture: String,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class MobileLoginResponse(
    val success: Boolean = true,
    val token: String,
    val user: MobileUser,
    @SerialName("expires_at") val expiresAt: String
)

@Serializable
data class MobileProfileMe(
    val success: Boolean = true,
    val user: MobileUserWithUrl
)

@Serializable
data class MobileUserWithUrl(
    val id: Int,
    val username: String,
    @SerialName("team_number") val teamNumber: Int,
    @SerialName("profile_picture") val profilePicture: String,
    @SerialName("profile_picture_url") val profilePictureUrl: String
)

@Serializable
data class MobileRegisterRequest(
    val username: String,
    val password: String,
    @SerialName("confirm_password") val confirmPassword: String? = null,
    @SerialName("team_number") val teamNumber: Int,
    val email: String? = null
)

@Serializable
data class MobileVerifyResponse(
    val success: Boolean = true,
    val valid: Boolean,
    val user: MobileUser? = null
)

@Serializable
data class MobileEvent(
    val id: Int,
    val name: String,
    val code: String,
    val location: String,
    @SerialName("start_date") val startDate: String?,
    @SerialName("end_date") val endDate: String?,
    val timezone: String?,
    val year: Int,
    @SerialName("team_count") val teamCount: Int
)

@Serializable
data class MobileEventsResponse(
    val success: Boolean = true,
    val events: List<MobileEvent>
)

@Serializable
data class MobileTeam(
    val id: Int,
    @SerialName("team_number") val teamNumber: Int,
    @SerialName("team_name") val teamName: String,
    val location: String
)

@Serializable
data class MobileTeamsResponse(
    val success: Boolean = true,
    val teams: List<MobileTeam>,
    val count: Int,
    val total: Int,
    val event: MobileEventShort? = null
)

@Serializable
data class MobileEventShort(
    val id: Int,
    val name: String,
    val code: String
)

@Serializable
data class MobileTeamResponse(
    val success: Boolean = true,
    val team: MobileTeam
)

@Serializable
data class MobileMatch(
    val id: Int,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("match_type") val matchType: String,
    @SerialName("red_alliance") val redAlliance: String,
    @SerialName("blue_alliance") val blueAlliance: String,
    @SerialName("red_score") val redScore: Int?,
    @SerialName("blue_score") val blueScore: Int?,
    val winner: String?,
    @SerialName("scheduled_time") val scheduledTime: String?,
    @SerialName("predicted_time") val predictedTime: String?,
    @SerialName("actual_time") val actualTime: String?
)

@Serializable
data class MobileMatchesResponse(
    val success: Boolean = true,
    val matches: List<MobileMatch>,
    val count: Int,
    val event: MobileEventShort? = null
)

@Serializable
data class MobileScoutingSubmitRequest(
    @SerialName("team_id") val teamId: Int? = null,
    @SerialName("match_id") val matchId: Int? = null,
    val data: JsonObject? = null,
    @SerialName("offline_id") val offlineId: String? = null,
    val qualitative: Boolean = false,
    @SerialName("alliance_scouted") val allianceScouted: String? = null,
    @SerialName("team_data") val teamData: JsonObject? = null
)

@Serializable
data class MobileScoutingSubmitResponse(
    val success: Boolean = true,
    @SerialName("scouting_id") val scoutingId: Int? = null,
    @SerialName("qualitative_id") val qualitativeId: Int? = null,
    val message: String,
    @SerialName("offline_id") val offlineId: String? = null
)

@Serializable
data class MobileBulkSubmitRequest(
    val entries: List<MobileBulkEntry>
)

@Serializable
data class MobileBulkEntry(
    @SerialName("team_id") val teamId: Int,
    @SerialName("match_id") val matchId: Int,
    val data: JsonObject,
    @SerialName("offline_id") val offlineId: String? = null,
    val timestamp: String? = null
)

@Serializable
data class MobileBulkResult(
    @SerialName("offline_id") val offlineId: String?,
    val success: Boolean,
    @SerialName("scouting_id") val scoutingId: Int? = null,
    val error: String? = null
)

@Serializable
data class MobileBulkSubmitResponse(
    val success: Boolean = true,
    val submitted: Int,
    val failed: Int,
    val results: List<MobileBulkResult>
)

@Serializable
data class MobileScoutingHistoryEntry(
    val id: Int,
    @SerialName("team_id") val teamId: Int,
    @SerialName("match_id") val matchId: Int,
    val timestamp: String,
    val data: JsonObject
)

@Serializable
data class MobileScoutingHistoryResponse(
    val success: Boolean = true,
    val entries: List<MobileScoutingHistoryEntry>,
    val count: Int
)

@Serializable
data class MobilePitSubmitRequest(
    @SerialName("team_id") val teamId: Int,
    val data: JsonObject,
    val images: List<String> = emptyList(),
    @SerialName("event_id") val eventId: Int? = null,
    @SerialName("event_code") val eventCode: String? = null,
    @SerialName("local_id") val localId: String? = null,
    @SerialName("device_id") val deviceId: String? = null
)

@Serializable
data class MobilePitSubmitResponse(
    val success: Boolean = true,
    @SerialName("pit_scouting_id") val pitScoutingId: Int,
    val message: String
)

@Serializable
data class MobileDataModeResponse(
    val success: Boolean = true,
    @SerialName("epa_source") val epaSource: String,
    @SerialName("data_mode") val dataMode: String
)

@Serializable
data class MobileCurrentDataModeRequest(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("event_code") val eventCode: String? = null,
    @SerialName("team_number") val teamNumber: Int? = null,
    @SerialName("team_numbers") val teamNumbers: List<Int> = emptyList()
)

@Serializable
data class MobileCurrentDataModeResponse(
    val success: Boolean = true,
    @SerialName("epa_source") val epaSource: String,
    @SerialName("data_mode") val dataMode: String,
    @SerialName("event_id") val eventDbId: Int,
    @SerialName("team_count") val teamCount: Int,
    @SerialName("requested_team_numbers") val requestedTeamNumbers: List<Int>,
    @SerialName("includes_scouted_data") val includesScoutedData: Boolean,
    val teams: List<MobileDataModeTeam>
)

@Serializable
data class MobileDataModeTeam(
    @SerialName("team_number") val teamNumber: Int,
    @SerialName("team_name") val teamName: String,
    @SerialName("match_count") val matchCount: Int,
    @SerialName("external_total_points") val externalTotalPoints: Double,
    @SerialName("match_points") val matchPoints: List<MobileDataModeMatchPoint>
)

@Serializable
data class MobileDataModeMatchPoint(
    @SerialName("match_id") val matchId: Int,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("scouted_auto_points") val scoutedAutoPoints: Double,
    @SerialName("scouted_teleop_points") val scoutedTeleopPoints: Double,
    @SerialName("scouted_endgame_points") val scoutedEndgamePoints: Double,
    @SerialName("scouted_total_points") val scoutedTotalPoints: Double,
    @SerialName("external_total_points") val externalTotalPoints: Double,
    @SerialName("selected_total_points") val selectedTotalPoints: Double,
    @SerialName("selected_source") val selectedSource: String,
    @SerialName("has_scouted_data") val hasScoutedData: Boolean
)

@Serializable
data class MobileEpaOprHistoryRequest(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("team_numbers") val teamNumbersElement: JsonElement? = null,
    val limit: Int = 200
)

@Serializable
data class MobileEpaOprHistoryResponse(
    val success: Boolean = true,
    @SerialName("event_id") val eventDbId: Int,
    @SerialName("event_code") val eventCode: String,
    @SerialName("tba_event_key") val tbaEventKey: String,
    @SerialName("team_count") val teamCount: Int,
    val teams: List<MobileEpaOprTeam>
)

@Serializable
data class MobileEpaOprTeam(
    @SerialName("team_number") val teamNumber: Int,
    @SerialName("team_name") val teamName: String,
    @SerialName("match_epa_history") val matchEpaHistory: List<MobileMatchEpa>,
    @SerialName("opr_data") val oprData: MobileOprData?
)

@Serializable
data class MobileMatchEpa(
    val team: Int,
    val event: String,
    @SerialName("comp_level") val compLevel: String,
    @SerialName("match_number") val matchNumber: Int,
    @SerialName("set_number") val setNumber: Int,
    val alliance: String,
    val epa: MobileEpaBreakdown,
    val timestamp: String?
)

@Serializable
data class MobileEpaBreakdown(
    @SerialName("total_points") val totalPoints: Double,
    @SerialName("auto_points") val autoPoints: Double,
    @SerialName("teleop_points") val teleopPoints: Double,
    @SerialName("endgame_points") val endgamePoints: Double
)

@Serializable
data class MobileOprData(
    val opr: Double,
    val dpr: Double,
    val ccwm: Double
)

@Serializable
data class MobileConfigResponse(
    val success: Boolean = true,
    val config: ScoutingConfig
)

@Serializable
data class MobileSaveConfigResponse(
    val success: Boolean = true
)

// Alliances

@Serializable
data class MobileAllianceDetail(
    val id: Int,
    val name: String,
    val description: String?,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("config_status") val configStatus: String = "configured",
    @SerialName("is_config_complete") val isConfigComplete: Boolean = true
)

@Serializable
data class MobilePendingInvite(
    val id: Int,
    @SerialName("alliance_id") val allianceId: Int,
    @SerialName("alliance_name") val allianceName: String,
    @SerialName("from_team") val fromTeam: Int
)

@Serializable
data class MobileSentInvite(
    val id: Int,
    @SerialName("to_team") val toTeam: Int,
    @SerialName("alliance_id") val allianceId: Int,
    @SerialName("alliance_name") val allianceName: String
)

@Serializable
data class MobileAlliancesResponse(
    val success: Boolean = true,
    @SerialName("my_alliances") val myAlliances: List<MobileAllianceDetail>,
    @SerialName("pending_invitations") val pendingInvitations: List<MobilePendingInvite>,
    @SerialName("sent_invitations") val sentInvitations: List<MobileSentInvite>,
    @SerialName("active_alliance_id") val activeAllianceId: Int?
)

@Serializable
data class MobileCreateAllianceRequest(
    val name: String,
    val description: String? = null
)

@Serializable
data class MobileCreateAllianceResponse(
    val success: Boolean = true,
    @SerialName("alliance_id") val allianceId: Int
)

@Serializable
data class MobileInviteRequest(
    @SerialName("team_number") val teamNumber: Int,
    val message: String? = null
)

@Serializable
data class MobileInviteResponse(
    val success: Boolean = true
)

@Serializable
data class MobileRespondInviteRequest(
    val response: String // "accept" or "decline"
)

@Serializable
data class MobileRespondInviteResponse(
    val success: Boolean = true
)

@Serializable
data class MobileToggleAllianceRequest(
    val activate: Boolean
)

@Serializable
data class MobileToggleAllianceResponse(
    val success: Boolean = true,
    val message: String,
    @SerialName("is_active") val isActive: Boolean
)

@Serializable
data class MobileLeaveAllianceRequest(
    @SerialName("remove_shared_data") val removeSharedData: Boolean = false,
    @SerialName("copy_shared_data") val copySharedData: Boolean = false
)

@Serializable
data class MobileLeaveAllianceResponse(
    val success: Boolean = true,
    val message: String,
    @SerialName("alliance_deleted") val allianceDeleted: Boolean
)

// Chat messaging structures

@Serializable
data class MessageReaction(
    val username: String,
    val emoji: String
)

@Serializable
data class ReactionSummary(
    val emoji: String,
    val count: Int
)

@Serializable
data class ChatMessage(
    val id: String, // UUID
    val sender: String, // username
    val recipient: String? = null, // username for DM, "alliance" or null for alliance/group
    val text: String,
    val timestamp: String, // ISO 8601
    @SerialName("sender_id") val sender_id: Int? = null,
    @SerialName("recipient_id") val recipient_id: Int? = null,
    @SerialName("conversation_id") val conversation_id: Int? = null,
    @SerialName("offline_id") val offline_id: String? = null,
    val read: Boolean = false,
    val edited: Boolean = false,
    @SerialName("edited_timestamp") val edited_timestamp: String? = null,
    val reactions: List<MessageReaction> = emptyList(),
    @SerialName("reactions_summary") val reactions_summary: List<ReactionSummary> = emptyList()
)

@Serializable
data class MobileChatMessagesResponse(
    val success: Boolean = true,
    val count: Int,
    val total: Int,
    val messages: List<ChatMessage>
)

@Serializable
data class MobileChatMember(
    val id: Int,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("team_number") val teamNumber: Int
)

@Serializable
data class MobileChatMembersResponse(
    val success: Boolean = true,
    val members: List<MobileChatMember>
)

@Serializable
data class MobileChatSendRequest(
    @SerialName("recipient_id") val recipientId: Int? = null,
    @SerialName("conversation_type") val conversationType: String? = null,
    val group: String? = null,
    val body: String,
    @SerialName("offline_id") val offlineId: String? = null
)

@Serializable
data class MobileChatSendResponse(
    val success: Boolean = true,
    val message: ChatMessage
)

@Serializable
data class MobileConversation(
    val id: Int,
    val type: String, // "direct", "alliance", "group"
    val title: String,
    @SerialName("last_message") val lastMessage: String?,
    @SerialName("last_message_at") val lastMessageAt: String?,
    @SerialName("unread_count") val unreadCount: Int
)

@Serializable
data class MobileConversationsResponse(
    val success: Boolean = true,
    val conversations: List<MobileConversation>
)

@Serializable
data class MobileChatReadRequest(
    val type: String,
    val id: String,
    @SerialName("last_read_message_id") val lastReadMessageId: String
)

@Serializable
data class MobileChatReadResponse(
    val success: Boolean = true
)

@Serializable
data class MobileEditMessageRequest(
    @SerialName("message_id") val messageId: String,
    val text: String
)

@Serializable
data class MobileEditMessageResponse(
    val success: Boolean = true,
    val message: String
)

@Serializable
data class MobileDeleteMessageRequest(
    @SerialName("message_id") val messageId: String
)

@Serializable
data class MobileDeleteMessageResponse(
    val success: Boolean = true,
    val message: String
)

@Serializable
data class MobileReactMessageRequest(
    @SerialName("message_id") val messageId: String,
    val emoji: String
)

@Serializable
data class MobileReactMessageResponse(
    val success: Boolean = true,
    val reactions: List<ReactionSummary>
)

@Serializable
data class ChatStatePointer(val type: String, val id: String)

@Serializable
data class ChatState(
    val joinedGroups: List<String> = listOf("main"),
    val currentGroup: String = "main",
    val lastDmUser: String? = null,
    val unreadCount: Int = 0,
    val lastSource: ChatStatePointer? = null,
    val notified: Boolean = false,
    val lastNotified: String? = null,
    val lastRead: Map<String, String> = emptyMap()
)

@Serializable
data class MobileChatStateResponse(
    val success: Boolean = true,
    val state: ChatState
)

@Serializable
data class MobileGroup(
    val name: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("is_member") val isMember: Boolean
)

@Serializable
data class MobileGroupsResponse(
    val success: Boolean = true,
    val groups: List<MobileGroup>
)

@Serializable
data class MobileCreateGroupRequest(
    val group: String,
    val members: List<String>
)

@Serializable
data class MobileCreateGroupResponse(
    val success: Boolean = true,
    val group: MobileGroupDetail
)

@Serializable
data class MobileGroupDetail(
    val name: String,
    val members: List<String>
)

@Serializable
data class MobileGroupMembersResponse(
    val success: Boolean = true,
    val members: List<String>
)

@Serializable
data class MobileManageGroupMembersRequest(
    val members: List<String> = emptyList()
)

// Admin user management

@Serializable
data class MobileAdminRole(
    val id: Int,
    val name: String,
    val description: String
)

@Serializable
data class MobileAdminRolesResponse(
    val success: Boolean = true,
    val roles: List<MobileAdminRole>
)

@Serializable
data class MobileAdminUser(
    val id: Int,
    val username: String,
    val email: String?,
    @SerialName("team_number") val teamNumber: Int,
    val roles: List<String>,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class MobileAdminUsersResponse(
    val success: Boolean = true,
    val users: List<MobileAdminUser>
)

@Serializable
data class MobileAdminUserResponse(
    val success: Boolean = true,
    val user: MobileAdminUser
)

@Serializable
data class MobileAdminCreateUserRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    @SerialName("team_number") val teamNumber: Int? = null,
    val roles: List<String> = emptyList()
)

@Serializable
data class MobileAdminUpdateUserRequest(
    val username: String? = null,
    val password: String? = null,
    val email: String? = null,
    @SerialName("team_number") val teamNumber: Int? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    val roles: List<String>? = null
)

// Notifications DTOs

@Serializable
data class MobileNotificationsScheduledResponse(
    val success: Boolean = true,
    val count: Int = 0,
    val total: Int = 0,
    val notifications: List<JsonElement> = emptyList()
)

@Serializable
data class MobileNotificationsUnreadResponse(
    val success: Boolean = true,
    @SerialName("chat_state") val chatState: ChatState,
    val scheduled: MobileNotificationsScheduledResponse
)

// Sync DTOs

@Serializable
data class MobileSyncStatusResponse(
    val success: Boolean = true,
    @SerialName("server_time") val serverTime: String,
    @SerialName("last_updates") val lastUpdates: Map<String, Long>
)

@Serializable
data class SyncSubStatus(
    val success: Boolean,
    val message: String,
    val flashes: List<String> = emptyList()
)

@Serializable
data class SyncAllianceSubStatus(
    val triggered: Boolean
)

@Serializable
data class MobileSyncResults(
    @SerialName("teams_sync") val teamsSync: SyncSubStatus,
    @SerialName("matches_sync") val matchesSync: SyncSubStatus,
    @SerialName("alliance_sync") val allianceSync: SyncAllianceSubStatus
)

@Serializable
data class MobileSyncTriggerResponse(
    val success: Boolean = true,
    val results: MobileSyncResults
)

// Graphs DTOs

@Serializable
data class MobileGraphRequest(
    @SerialName("team_number") val teamNumber: Int? = null,
    @SerialName("team_numbers") val teamNumbers: List<Int> = emptyList(),
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("graph_type") val graphType: String? = null,
    @SerialName("graph_types") val graphTypes: List<String> = emptyList(),
    val metric: String = "total_points",
    val mode: String = "match_by_match",
    @SerialName("vis_type") val visType: String? = null,
    @SerialName("visualization_data") val visualizationData: JsonElement? = null
)

@Serializable
data class PlotlyTrace(
    val type: String,
    val x: List<String>,
    val y: List<Double>,
    val name: String
)

@Serializable
data class PlotlyLayout(
    val title: String
)

@Serializable
data class FallbackPlotlyJson(
    val data: List<PlotlyTrace>,
    val layout: PlotlyLayout
)

@Serializable
data class MobileGraphResponse(
    val success: Boolean = true,
    @SerialName("fallback_plotly_json") val fallbackPlotlyJson: FallbackPlotlyJson
)

// ─────────────────────────────────────────────────────────────────────────────
// Routing Setup
// ─────────────────────────────────────────────────────────────────────────────

fun Application.configureMobileRoutes(appConfig: AppConfig) {
    val secret = appConfig.server.sessionSecret

    fun getGameConfigWithSettings(teamNumber: Int): ScoutingConfig {
        val config = ConfigService.getConfig(teamNumber)
        val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(teamNumber)
        return config.copy(
            tbaKey = settings.apiKeys.tbaKey,
            firstUsername = settings.apiKeys.firstUsername,
            firstKey = settings.apiKeys.firstKey,
            eventCode = settings.eventCode
        )
    }

    fun getPitConfigWithSettings(teamNumber: Int): ScoutingConfig {
        val config = ConfigService.getPitConfig(teamNumber)
        val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(teamNumber)
        return config.copy(
            tbaKey = settings.apiKeys.tbaKey,
            firstUsername = settings.apiKeys.firstUsername,
            firstKey = settings.apiKeys.firstKey,
            eventCode = settings.eventCode
        )
    }

    fun getQualitativeConfigWithSettings(teamNumber: Int): ScoutingConfig {
        val config = ConfigService.getQualitativeConfig(teamNumber)
        val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(teamNumber)
        return config.copy(
            tbaKey = settings.apiKeys.tbaKey,
            firstUsername = settings.apiKeys.firstUsername,
            firstKey = settings.apiKeys.firstKey,
            eventCode = settings.eventCode
        )
    }

    fun extractAndUpdateSettingsFromConfigJson(teamNumber: Int, jsonStr: String) {
        try {
            val parsed = JsonSupport.json.decodeFromString<ScoutingConfig>(jsonStr)
            if (parsed.tbaKey != null || parsed.firstUsername != null || parsed.firstKey != null || parsed.eventCode != null) {
                val currentSettings = SettingsService.getSettings(teamNumber)
                val updatedSettings = currentSettings.copy(
                    eventCode = parsed.eventCode ?: currentSettings.eventCode,
                    apiKeys = currentSettings.apiKeys.copy(
                        tbaKey = parsed.tbaKey ?: currentSettings.apiKeys.tbaKey,
                        firstUsername = parsed.firstUsername ?: currentSettings.apiKeys.firstUsername,
                        firstKey = parsed.firstKey ?: currentSettings.apiKeys.firstKey
                    )
                )
                SettingsService.updateSettings(teamNumber, updatedSettings)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    routing {
        route("/api/mobile") {
            // Health check
            get("/health") {
                call.respond(MobileHealthResponse(timestamp = Instant.now().toString()))
            }

            // Auth endpoints
            post("/auth/login") {
                val req = call.receive<MobileLoginRequest>()
                
                // Lookup user. If no username is provided, verify against all users on the team
                val user = if (req.username.isNullOrBlank()) {
                    val candidateUsers = transaction {
                        AuthService.listUsers(
                            callerSession = UserSession(0, "system", req.teamNumber, UserRole.SUPERADMIN),
                            teamFilter = req.teamNumber
                        )
                    }
                    candidateUsers.firstOrNull { candidate ->
                        AuthService.login(candidate.username, req.teamNumber, req.password) != null
                    }?.let { matched ->
                        AuthService.login(matched.username, req.teamNumber, req.password)
                    }
                } else {
                    AuthService.login(req.username, req.teamNumber, req.password)
                }

                if (user == null) {
                    throw MobileApiException(HttpStatusCode.Unauthorized, "Invalid credentials", "INVALID_CREDENTIALS")
                }

                val session = UserSession(
                    userId = user.id,
                    username = user.username,
                    teamNumber = user.teamNumber,
                    role = user.role,
                    email = user.email
                )

                val expiresAt = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
                val token = JwtHelper.generateToken(session, secret, expiresAt)

                call.respond(
                    MobileLoginResponse(
                        token = token,
                        user = MobileUser(
                            id = user.id,
                            username = user.username,
                            teamNumber = user.teamNumber,
                            roles = listOf(user.role.name.lowercase()),
                            profilePicture = "img/avatars/default.png"
                        ),
                        expiresAt = expiresAt.toInstant().toString()
                    )
                )
            }

            post("/auth/register") {
                val req = call.receive<MobileRegisterRequest>()
                
                // Perform register
                val user = try {
                    AuthService.register(
                        username = req.username,
                        teamNumber = req.teamNumber,
                        password = req.password,
                        role = UserRole.SCOUT,
                        email = req.email
                    )
                } catch (e: Exception) {
                    throw MobileApiException(HttpStatusCode.Conflict, e.message ?: "Username exists", "USERNAME_EXISTS")
                }

                val session = UserSession(
                    userId = user.id,
                    username = user.username,
                    teamNumber = user.teamNumber,
                    role = user.role,
                    email = user.email
                )

                val expiresAt = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
                val token = JwtHelper.generateToken(session, secret, expiresAt)

                call.respond(
                    HttpStatusCode.Created,
                    MobileLoginResponse(
                        token = token,
                        user = MobileUser(
                            id = user.id,
                            username = user.username,
                            teamNumber = user.teamNumber,
                            roles = listOf(user.role.name.lowercase()),
                            profilePicture = "img/avatars/default.png"
                        ),
                        expiresAt = expiresAt.toInstant().toString()
                    )
                )
            }

            post("/auth/refresh") {
                val session = call.requireMobileSession(secret)
                val expiresAt = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
                val token = JwtHelper.generateToken(session, secret, expiresAt)

                call.respond(
                    MobileLoginResponse(
                        token = token,
                        user = MobileUser(
                            id = session.userId,
                            username = session.username,
                            teamNumber = session.teamNumber,
                            roles = listOf(session.role.name.lowercase()),
                            profilePicture = "img/avatars/default.png"
                        ),
                        expiresAt = expiresAt.toInstant().toString()
                    )
                )
            }

            get("/auth/verify") {
                val session = try {
                    call.requireMobileSession(secret)
                } catch (_: Exception) {
                    null
                }

                if (session == null) {
                    call.respond(MobileVerifyResponse(valid = false))
                } else {
                    call.respond(
                        MobileVerifyResponse(
                            valid = true,
                            user = MobileUser(
                                id = session.userId,
                                username = session.username,
                                teamNumber = session.teamNumber,
                                roles = listOf(session.role.name.lowercase()),
                                profilePicture = "img/avatars/default.png"
                            )
                        )
                    )
                }
            }

            // Profiles
            get("/profiles/me") {
                val session = call.requireMobileSession(secret)
                call.respond(
                    MobileProfileMe(
                        user = MobileUserWithUrl(
                            id = session.userId,
                            username = session.username,
                            teamNumber = session.teamNumber,
                            profilePicture = "img/avatars/default.png",
                            profilePictureUrl = "/api/mobile/profiles/me/picture"
                        )
                    )
                )
            }

            get("/profiles/me/picture") {
                call.requireMobileSession(secret)
                // Return a simple 1x1 transparent PNG
                val pngBytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=")
                call.respondBytes(pngBytes, ContentType.Image.PNG)
            }

            // Events
            get("/events") {
                val session = call.requireMobileSession(secret)
                val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber)
                val events = IntegrationService.listEvents(year = null, cachedOnly = true, activeKey = settings.resolvedEventKey(), activeSettings = settings)
                
                val eventKeys = events.map { it.eventKey }
                val (teamCountsMap, eventDbIdsMap) = if (eventKeys.isNotEmpty()) {
                    transaction {
                        val counts = ApiTeams.selectAll()
                            .where { ApiTeams.eventKey inList eventKeys }
                            .map { it[ApiTeams.eventKey] }
                            .groupBy { it }
                            .mapValues { it.value.size }

                        val dbIds = ApiEvents.selectAll()
                            .where { ApiEvents.eventKey inList eventKeys }
                            .associate { it[ApiEvents.eventKey] to it[ApiEvents.id].value }

                        counts to dbIds
                    }
                } else {
                    emptyMap<String, Int>() to emptyMap<String, Int>()
                }

                val mapped = events.map { ev ->
                    val teamCount = teamCountsMap[ev.eventKey] ?: 0
                    val dbId = eventDbIdsMap[ev.eventKey] ?: 0
                    MobileEvent(
                        id = dbId,
                        name = ev.name,
                        code = ev.eventCode ?: ev.eventKey,
                        location = parseEventLocation(ev.eventKey),
                        startDate = ev.startDate,
                        endDate = ev.endDate,
                        timezone = ev.timezone,
                        year = ev.year,
                        teamCount = teamCount
                    )
                }
                call.respond(MobileEventsResponse(events = mapped))
            }

            // Teams
            get("/teams") {
                val session = call.requireMobileSession(secret)
                val eventIdParam = call.request.queryParameters["event_id"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val eventKey = resolveEventKey(eventIdParam, session.teamNumber)
                val records = IntegrationService.listTeams(eventKey, session)
                val total = records.size
                val paged = records.drop(offset).take(limit)

                val teamNumbers = paged.map { it.teamNumber }
                val teamIdMap = if (teamNumbers.isNotEmpty()) {
                    transaction {
                        ApiTeams.selectAll()
                            .where { (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamNumber inList teamNumbers) }
                            .associate { it[ApiTeams.teamNumber] to it[ApiTeams.id].value }
                    }
                } else {
                    emptyMap()
                }

                val mapped = paged.map { t ->
                    val dbId = teamIdMap[t.teamNumber] ?: t.teamNumber
                    mapTeamRecord(t, dbId)
                }

                call.respond(MobileTeamsResponse(teams = mapped, count = mapped.size, total = total))
            }

            get("/teams/current") {
                val session = call.requireMobileSession(secret)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val eventKey = resolveEventKey(null, session.teamNumber)
                val records = IntegrationService.listTeams(eventKey, session)
                val total = records.size
                val paged = records.drop(offset).take(limit)

                val eventRow = transaction {
                    ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.firstOrNull()
                }
                val eventDbId = eventRow?.get(ApiEvents.id)?.value ?: 0
                val eventName = eventRow?.get(ApiEvents.name) ?: "Configured Event"
                val eventCode = eventRow?.get(ApiEvents.eventCode) ?: eventKey.removePrefix(session.teamNumber.toString())

                val teamNumbers = paged.map { it.teamNumber }
                val teamIdMap = if (teamNumbers.isNotEmpty()) {
                    transaction {
                        ApiTeams.selectAll()
                            .where { (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamNumber inList teamNumbers) }
                            .associate { it[ApiTeams.teamNumber] to it[ApiTeams.id].value }
                    }
                } else {
                    emptyMap()
                }

                val mapped = paged.map { t ->
                    val dbId = teamIdMap[t.teamNumber] ?: t.teamNumber
                    mapTeamRecord(t, dbId)
                }

                call.respond(
                    MobileTeamsResponse(
                        teams = mapped,
                        count = mapped.size,
                        total = total,
                        event = MobileEventShort(eventDbId, eventName, eventCode)
                    )
                )
            }

            get("/teams/{id}") {
                call.requireMobileSession(secret)
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing team id", "MISSING_DATA")

                val teamRow = transaction {
                    ApiTeams.selectAll().where { ApiTeams.id eq id }.firstOrNull()
                } ?: throw MobileApiException(HttpStatusCode.NotFound, "Team not found", "TEAM_NOT_FOUND")

                val nickname = teamRow[ApiTeams.nickname] ?: teamRow[ApiTeams.name] ?: "Team ${teamRow[ApiTeams.teamNumber]}"
                val loc = listOfNotNull(teamRow[ApiTeams.city], teamRow[ApiTeams.state]).filter { it.isNotBlank() }.joinToString(", ")

                call.respond(
                    MobileTeamResponse(
                        team = MobileTeam(
                            id = id,
                            teamNumber = teamRow[ApiTeams.teamNumber],
                            teamName = nickname,
                            location = loc.ifBlank { "City, State" }
                        )
                    )
                )
            }

            // Matches
            get("/matches") {
                val session = call.requireMobileSession(secret)
                val eventIdParam = call.request.queryParameters["event_id"]
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "event_id is required", "MISSING_EVENT_ID")
                val matchType = call.request.queryParameters["match_type"]
                val teamNumber = call.request.queryParameters["team_number"]?.toIntOrNull()

                val eventKey = resolveEventKey(eventIdParam, session.teamNumber)
                var matches = IntegrationService.listMatches(eventKey)

                if (!matchType.isNullOrBlank()) {
                    matches = matches.filter { mapMatchType(it.compLevel).equals(matchType, ignoreCase = true) }
                }

                if (teamNumber != null) {
                    matches = matches.filter { match ->
                        (match.redTeams + match.blueTeams).any { key ->
                            key.substringAfter("/").removePrefix("frc").filter { it.isDigit() }.toIntOrNull() == teamNumber
                        }
                    }
                }

                val matchKeys = matches.map { it.matchKey }
                val matchDbRows = if (matchKeys.isNotEmpty()) {
                    transaction {
                        ApiMatches.selectAll()
                            .where { ApiMatches.matchKey inList matchKeys }
                            .toList()
                    }
                } else {
                    emptyList()
                }
                val matchDbMap = matchDbRows.associateBy { it[ApiMatches.matchKey] }

                val mapped = matches.map { m ->
                    val row = matchDbMap[m.matchKey]
                    val dbId = row?.get(ApiMatches.id)?.value ?: 0
                    val dataJsonStr = row?.get(ApiMatches.dataJson) ?: "{}"
                    val (redScore, blueScore, winner) = parseMatchScores(dataJsonStr)
                    MobileMatch(
                        id = dbId,
                        matchNumber = m.matchNumber ?: 0,
                        matchType = mapMatchType(m.compLevel),
                        redAlliance = extractTeamNumbers(m.redTeams),
                        blueAlliance = extractTeamNumbers(m.blueTeams),
                        redScore = redScore,
                        blueScore = blueScore,
                        winner = winner,
                        scheduledTime = formatEpochSecond(m.scheduledTime),
                        predictedTime = formatEpochSecond(m.scheduledTime),
                        actualTime = formatEpochSecond(m.actualTime)
                    )
                }

                call.respond(MobileMatchesResponse(matches = mapped, count = mapped.size))
            }

            get("/matches/current") {
                val session = call.requireMobileSession(secret)
                val matchType = call.request.queryParameters["match_type"]
                val teamNumber = call.request.queryParameters["team_number"]?.toIntOrNull()

                val eventKey = resolveEventKey(null, session.teamNumber)
                var matches = IntegrationService.listMatches(eventKey)

                if (!matchType.isNullOrBlank()) {
                    matches = matches.filter { mapMatchType(it.compLevel).equals(matchType, ignoreCase = true) }
                }

                if (teamNumber != null) {
                    matches = matches.filter { match ->
                        (match.redTeams + match.blueTeams).any { key ->
                            key.substringAfter("/").removePrefix("frc").filter { it.isDigit() }.toIntOrNull() == teamNumber
                        }
                    }
                }

                val eventRow = transaction {
                    ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.firstOrNull()
                }
                val eventDbId = eventRow?.get(ApiEvents.id)?.value ?: 0
                val eventName = eventRow?.get(ApiEvents.name) ?: "Configured Event"
                val eventCode = eventRow?.get(ApiEvents.eventCode) ?: eventKey.removePrefix(session.teamNumber.toString())

                val matchKeys = matches.map { it.matchKey }
                val matchDbRows = if (matchKeys.isNotEmpty()) {
                    transaction {
                        ApiMatches.selectAll()
                            .where { ApiMatches.matchKey inList matchKeys }
                            .toList()
                    }
                } else {
                    emptyList()
                }
                val matchDbMap = matchDbRows.associateBy { it[ApiMatches.matchKey] }

                val mapped = matches.map { m ->
                    val row = matchDbMap[m.matchKey]
                    val dbId = row?.get(ApiMatches.id)?.value ?: 0
                    val dataJsonStr = row?.get(ApiMatches.dataJson) ?: "{}"
                    val (redScore, blueScore, winner) = parseMatchScores(dataJsonStr)
                    MobileMatch(
                        id = dbId,
                        matchNumber = m.matchNumber ?: 0,
                        matchType = mapMatchType(m.compLevel),
                        redAlliance = extractTeamNumbers(m.redTeams),
                        blueAlliance = extractTeamNumbers(m.blueTeams),
                        redScore = redScore,
                        blueScore = blueScore,
                        winner = winner,
                        scheduledTime = formatEpochSecond(m.scheduledTime),
                        predictedTime = formatEpochSecond(m.scheduledTime),
                        actualTime = formatEpochSecond(m.actualTime)
                    )
                }

                call.respond(
                    MobileMatchesResponse(
                        matches = mapped,
                        count = mapped.size,
                        event = MobileEventShort(eventDbId, eventName, eventCode)
                    )
                )
            }

            // Scouting Submit
            post("/scouting/submit") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobileScoutingSubmitRequest>()

                if (req.qualitative) {
                    val matchId = req.matchId
                        ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing required field: match_id", "MISSING_FIELD")
                    
                    val matchRow = transaction {
                        ApiMatches.selectAll().where { ApiMatches.id eq matchId }.firstOrNull()
                    } ?: throw MobileApiException(HttpStatusCode.NotFound, "Match not found", "MATCH_NOT_FOUND")

                    val eventKey = matchRow[ApiMatches.eventKey]
                    val matchKey = matchRow[ApiMatches.matchKey]
                    val matchNumber = matchRow[ApiMatches.matchNumber] ?: 0

                    val teamData = req.teamData
                        ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing required field: team_data", "MISSING_FIELD")

                    val config = ConfigService.getQualitativeConfig(session.teamNumber)

                    var lastId = 0
                    transaction {
                        teamData.keys.forEach allianceLoop@{ allianceKey ->
                            val teamsObj = teamData[allianceKey]?.jsonObject ?: return@allianceLoop
                            teamsObj.keys.forEach teamLoop@{ teamKeyString ->
                                val teamNumber = teamKeyString.removePrefix("team_").toIntOrNull() ?: return@teamLoop
                                
                                val singleTeamData = buildJsonObject {
                                    put("targetTeamNumber", teamNumber)
                                    put("eventKey", eventKey)
                                    put("matchKey", matchKey)
                                    put("matchNumber", matchNumber)
                                    
                                    val fieldsObj = teamsObj[teamKeyString]?.jsonObject ?: return@teamLoop
                                    fieldsObj.entries.forEach { (k, v) ->
                                        put(k, v)
                                    }
                                }

                                val saved = QualitativeScoutingService.createEntry(
                                    session = session,
                                    request = ScoutingEntryRequest(singleTeamData),
                                    config = config
                                )
                                lastId = saved.id
                            }
                        }
                    }

                    call.respond(
                        HttpStatusCode.Created,
                        MobileScoutingSubmitResponse(
                            qualitativeId = lastId,
                            message = "Qualitative scouting data submitted successfully",
                            offlineId = req.offlineId
                        )
                    )

                } else {
                    val teamId = req.teamId
                        ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing required field: team_id", "MISSING_FIELD")
                    val matchId = req.matchId
                        ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing required field: match_id", "MISSING_FIELD")
                    val clientData = req.data
                        ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing required field: data", "MISSING_FIELD")

                    val teamRow = transaction {
                        ApiTeams.selectAll().where { ApiTeams.id eq teamId }.firstOrNull()
                    } ?: throw MobileApiException(HttpStatusCode.NotFound, "Team not found", "TEAM_NOT_FOUND")

                    val matchRow = transaction {
                        ApiMatches.selectAll().where { ApiMatches.id eq matchId }.firstOrNull()
                    } ?: throw MobileApiException(HttpStatusCode.NotFound, "Match not found", "MATCH_NOT_FOUND")

                    val targetTeamNumber = teamRow[ApiTeams.teamNumber]
                    val eventKey = teamRow[ApiTeams.eventKey]
                    val matchKey = matchRow[ApiMatches.matchKey]
                    val matchNumber = matchRow[ApiMatches.matchNumber] ?: 0

                    val mergedData = buildJsonObject {
                        put("targetTeamNumber", targetTeamNumber)
                        put("eventKey", eventKey)
                        put("matchKey", matchKey)
                        put("matchNumber", matchNumber)
                        if (req.offlineId != null) {
                            put("offline_id", req.offlineId)
                        }
                        clientData.entries.forEach { (k, v) ->
                            put(k, v)
                        }
                    }

                    val config = ConfigService.getConfig(session.teamNumber)
                    val entry = try {
                        ScoutingService.createEntry(
                            session = session,
                            request = ScoutingEntryRequest(mergedData),
                            config = config
                        )
                    } catch (e: Exception) {
                        throw MobileApiException(HttpStatusCode.InternalServerError, e.message ?: "Submit error", "SUBMIT_ERROR")
                    }

                    call.respond(
                        HttpStatusCode.Created,
                        MobileScoutingSubmitResponse(
                            scoutingId = entry.id,
                            message = "Scouting data submitted successfully",
                            offlineId = req.offlineId
                        )
                    )
                }
            }

            post("/scouting/bulk-submit") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobileBulkSubmitRequest>()
                
                val results = mutableListOf<MobileBulkResult>()
                var submittedCount = 0
                var failedCount = 0

                val config = ConfigService.getConfig(session.teamNumber)

                val teamIds = req.entries.map { it.teamId }.distinct()
                val matchIds = req.entries.map { it.matchId }.distinct()

                val (teamRowsMap, matchRowsMap) = transaction {
                    val teams = if (teamIds.isNotEmpty()) {
                        ApiTeams.selectAll().where { ApiTeams.id inList teamIds }
                            .associateBy { it[ApiTeams.id].value }
                    } else emptyMap()

                    val matches = if (matchIds.isNotEmpty()) {
                        ApiMatches.selectAll().where { ApiMatches.id inList matchIds }
                            .associateBy { it[ApiMatches.id].value }
                    } else emptyMap()

                    teams to matches
                }

                req.entries.forEach { entry ->
                    try {
                        val teamRow = teamRowsMap[entry.teamId]
                        val matchRow = matchRowsMap[entry.matchId]

                        if (teamRow == null || matchRow == null) {
                            failedCount++
                            results.add(MobileBulkResult(entry.offlineId, false, error = "Team or match not found"))
                            return@forEach
                        }

                        val targetTeamNumber = teamRow[ApiTeams.teamNumber]
                        val eventKey = teamRow[ApiTeams.eventKey]
                        val matchKey = matchRow[ApiMatches.matchKey]
                        val matchNumber = matchRow[ApiMatches.matchNumber] ?: 0

                        val mergedData = buildJsonObject {
                            put("targetTeamNumber", targetTeamNumber)
                            put("eventKey", eventKey)
                            put("matchKey", matchKey)
                            put("matchNumber", matchNumber)
                            if (entry.offlineId != null) {
                                put("offline_id", entry.offlineId)
                            }
                            entry.data.entries.forEach { (k, v) ->
                                put(k, v)
                            }
                        }

                        val saved = ScoutingService.createEntry(
                            session = session,
                            request = ScoutingEntryRequest(mergedData),
                            config = config
                        )
                        submittedCount++
                        results.add(MobileBulkResult(entry.offlineId, true, scoutingId = saved.id))

                    } catch (e: Exception) {
                        failedCount++
                        results.add(MobileBulkResult(entry.offlineId, false, error = e.message ?: "Error saving entry"))
                    }
                }

                call.respond(
                    MobileBulkSubmitResponse(
                        submitted = submittedCount,
                        failed = failedCount,
                        results = results
                    )
                )
            }

            get("/scouting/history") {
                val session = call.requireMobileSession(secret)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val allEntries = ScoutingService.listEntries(session, includePrescout = true)
                val myEntries = allEntries.drop(offset).take(limit)

                val uniqueTeams = myEntries.mapNotNull { entry ->
                    if (entry.eventKey != null && entry.targetTeamNumber != null) {
                        entry.eventKey to entry.targetTeamNumber
                    } else null
                }.distinct()

                val uniqueMatchKeys = myEntries.mapNotNull { it.matchKey }.distinct()

                val (teamIdMap, matchIdMap) = transaction {
                    val eventKeys = uniqueTeams.map { it.first }.distinct()
                    val teamNumbers = uniqueTeams.map { it.second }.distinct()
                    val teams = if (uniqueTeams.isNotEmpty()) {
                        ApiTeams.selectAll()
                            .where { (ApiTeams.eventKey inList eventKeys) and (ApiTeams.teamNumber inList teamNumbers) }
                            .associate { (it[ApiTeams.eventKey] to it[ApiTeams.teamNumber]) to it[ApiTeams.id].value }
                    } else emptyMap()

                    val matches = if (uniqueMatchKeys.isNotEmpty()) {
                        ApiMatches.selectAll()
                            .where { ApiMatches.matchKey inList uniqueMatchKeys }
                            .associate { it[ApiMatches.matchKey] to it[ApiMatches.id].value }
                    } else emptyMap()

                    teams to matches
                }

                val mapped = myEntries.map { entry ->
                    val targetTeamId = if (entry.eventKey != null && entry.targetTeamNumber != null) {
                        teamIdMap[entry.eventKey to entry.targetTeamNumber] ?: 0
                    } else {
                        0
                    }
                    val matchId = if (entry.matchKey != null) {
                        matchIdMap[entry.matchKey] ?: 0
                    } else {
                        0
                    }
                    MobileScoutingHistoryEntry(
                        id = entry.id,
                        teamId = targetTeamId,
                        matchId = matchId,
                        timestamp = entry.createdAt,
                        data = entry.data
                    )
                }

                call.respond(MobileScoutingHistoryResponse(entries = mapped, count = mapped.size))
            }

            // Pit Scouting Submit
            post("/pit-scouting/submit") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobilePitSubmitRequest>()

                // Check for duplicate via local_id
                if (req.localId != null) {
                    val duplicateId = findDuplicatePitScouting(session.teamNumber, req.localId)
                    if (duplicateId != null) {
                        call.respond(
                            HttpStatusCode.Created,
                            MobilePitSubmitResponse(
                                pitScoutingId = duplicateId,
                                message = "Pit scouting data submitted successfully"
                            )
                        )
                        return@post
                    }
                }

                val teamRow = transaction {
                    ApiTeams.selectAll().where { ApiTeams.id eq req.teamId }.firstOrNull()
                } ?: throw MobileApiException(HttpStatusCode.NotFound, "Team not found", "TEAM_NOT_FOUND")

                val targetTeamNumber = teamRow[ApiTeams.teamNumber]
                val eventKey = if (!req.eventCode.isNullOrBlank()) {
                    resolveEventKey(req.eventCode, session.teamNumber)
                } else if (req.eventId != null) {
                    resolveEventKey(req.eventId.toString(), session.teamNumber)
                } else {
                    teamRow[ApiTeams.eventKey]
                }

                val mergedData = buildJsonObject {
                    put("targetTeamNumber", targetTeamNumber)
                    put("eventKey", eventKey)
                    if (req.localId != null) {
                        put("local_id", req.localId)
                    }
                    if (req.deviceId != null) {
                        put("device_id", req.deviceId)
                    }
                    if (req.images.isNotEmpty()) {
                        put("images", JsonArray(req.images.map { JsonPrimitive(it) }))
                    }
                    req.data.entries.forEach { (k, v) ->
                        put(k, v)
                    }
                }

                val config = ConfigService.getPitConfig(session.teamNumber)
                val entry = try {
                    PitScoutingService.createEntry(
                        session = session,
                        request = ScoutingEntryRequest(mergedData),
                        config = config
                    )
                } catch (e: Exception) {
                    throw MobileApiException(HttpStatusCode.InternalServerError, e.message ?: "Submit error", "SUBMIT_ERROR")
                }

                call.respond(
                    HttpStatusCode.Created,
                    MobilePitSubmitResponse(
                        pitScoutingId = entry.id,
                        message = "Pit scouting data submitted successfully"
                    )
                )
            }

            // Configuration
            get("/config/game") {
                val session = call.requireMobileSession(secret)
                val config = getGameConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }
            get("/config/game/active") {
                val session = call.requireMobileSession(secret)
                val config = getGameConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }
            get("/config/game/team") {
                val session = call.requireMobileSession(secret)
                val config = getGameConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }

            get("/config/game/data-mode") {
                val session = call.requireMobileSession(secret)
                val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber)
                val epaSource = if (settings.useStatboticsEpa) "scouted_with_statbotics" else if (settings.useTbaOpr) "scouted_with_tba_opr" else "scouted_only"
                val dataMode = if (settings.useStatboticsEpa) "Scouted Data + Statbotics EPA Gap-Fill" else if (settings.useTbaOpr) "Scouted Data + TBA OPR Gap-Fill" else "Scouted Data Only"
                call.respond(MobileDataModeResponse(epaSource = epaSource, dataMode = dataMode))
            }

            // GET/POST Current Data Mode
            val currentDataModeHandler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit = {
                val session = call.requireMobileSession(secret)
                val body = try { call.receive<MobileCurrentDataModeRequest>() } catch (_: Exception) { MobileCurrentDataModeRequest() }
                
                val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber)
                val epaSource = if (settings.useStatboticsEpa) "scouted_with_statbotics" else if (settings.useTbaOpr) "scouted_with_tba_opr" else "scouted_only"
                val dataMode = if (settings.useStatboticsEpa) "Scouted Data + Statbotics EPA Gap-Fill" else if (settings.useTbaOpr) "Scouted Data + TBA OPR Gap-Fill" else "Scouted Data Only"

                val eventKey = if (!body.eventCode.isNullOrBlank()) {
                    resolveEventKey(body.eventCode, session.teamNumber)
                } else if (!body.eventId.isNullOrBlank()) {
                    resolveEventKey(body.eventId, session.teamNumber)
                } else {
                    settings.resolvedEventKey()
                }

                val eventRow = transaction {
                    ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.firstOrNull()
                }
                val eventDbId = eventRow?.get(ApiEvents.id)?.value ?: 0

                val teamsList = IntegrationService.listTeams(eventKey, session)
                val matchesList = IntegrationService.listMatches(eventKey)
                
                val scoutedEntries = ScoutingService.listEntries(session, includePrescout = false)
                val config = ConfigService.getConfig(session.teamNumber)

                var filterTeams = teamsList
                if (body.teamNumber != null) {
                    filterTeams = filterTeams.filter { it.teamNumber == body.teamNumber }
                } else if (body.teamNumbers.isNotEmpty()) {
                    filterTeams = filterTeams.filter { body.teamNumbers.contains(it.teamNumber) }
                }

                val matchKeys = matchesList.map { it.matchKey }
                val matchIdMap = if (matchKeys.isNotEmpty()) {
                    transaction {
                        ApiMatches.selectAll()
                            .where { ApiMatches.matchKey inList matchKeys }
                            .associate { it[ApiMatches.matchKey] to it[ApiMatches.id].value }
                    }
                } else {
                    emptyMap()
                }

                val matchesInEvent = matchesList.map { row ->
                    val matchKey = row.matchKey
                    val matchId = matchIdMap[matchKey] ?: 0
                    val matchNumber = row.matchNumber ?: 0
                    val redTeams = row.redTeams
                    val blueTeams = row.blueTeams
                    val allTeamsInMatch = (redTeams + blueTeams).map { t ->
                        t.substringAfter("/").removePrefix("frc").filter { it.isDigit() }.toIntOrNull()
                    }.filterNotNull()
                    
                    Triple(matchId, matchNumber, allTeamsInMatch)
                }

                val mappedTeams = transaction {
                    filterTeams.map { team ->
                        val teamMatches = matchesInEvent.filter { it.third.contains(team.teamNumber) }
                        
                        val matchPoints = teamMatches.map { (matchId, matchNumber, _) ->
                            val myMatchKey = matchesList.find { m -> m.matchNumber == matchNumber }?.matchKey ?: ""
                            val entry = scoutedEntries.find { e -> e.targetTeamNumber == team.teamNumber && e.matchKey == myMatchKey }
                            
                            val hasScouted = entry != null
                            val autoScore = if (entry != null) scorePhase(config, entry.data, "auto") else 0.0
                            val teleopScore = if (entry != null) scorePhase(config, entry.data, "teleop") else 0.0
                            val endgameScore = if (entry != null) scorePhase(config, entry.data, "endgame") else 0.0
                            val scoutedTotal = if (entry != null) scorePhase(config, entry.data, "auto") + scorePhase(config, entry.data, "teleop") + scorePhase(config, entry.data, "endgame") else 0.0

                            val extTotal = team.epa ?: team.opr ?: 0.0
                            val selectedTotal = if (hasScouted) scoutedTotal else extTotal
                            val selectedSrc = if (hasScouted) "scouted" else (if (settings.useStatboticsEpa) "epa" else if (settings.useTbaOpr) "opr" else "scouted")

                            MobileDataModeMatchPoint(
                                matchId = matchId,
                                matchNumber = matchNumber,
                                scoutedAutoPoints = autoScore,
                                scoutedTeleopPoints = teleopScore,
                                scoutedEndgamePoints = endgameScore,
                                scoutedTotalPoints = scoutedTotal,
                                externalTotalPoints = extTotal,
                                selectedTotalPoints = selectedTotal,
                                selectedSource = selectedSrc,
                                hasScoutedData = hasScouted
                            )
                        }

                        MobileDataModeTeam(
                            teamNumber = team.teamNumber,
                            teamName = team.nickname ?: team.name ?: "Team ${team.teamNumber}",
                            matchCount = matchPoints.size,
                            externalTotalPoints = team.epa ?: team.opr ?: 0.0,
                            matchPoints = matchPoints
                        )
                    }
                }

                call.respond(
                    MobileCurrentDataModeResponse(
                        epaSource = epaSource,
                        dataMode = dataMode,
                        eventDbId = eventDbId,
                        teamCount = mappedTeams.size,
                        requestedTeamNumbers = filterTeams.map { it.teamNumber },
                        includesScoutedData = true,
                        teams = mappedTeams
                    )
                )
            }

            get("/config/game/current-data-mode") { currentDataModeHandler(Unit) }
            post("/config/game/current-data-mode") { currentDataModeHandler(Unit) }

            // historical EPA/OPR data
            val epaOprHistoryHandler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit = {
                val session = call.requireMobileSession(secret)
                val body = try { call.receive<MobileEpaOprHistoryRequest>() } catch (_: Exception) { MobileEpaOprHistoryRequest() }
                
                val eventIdParam = body.eventId ?: call.request.queryParameters["event_id"]
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "event_id is required", "EVENT_NOT_FOUND")

                val eventKey = resolveEventKey(eventIdParam, session.teamNumber)
                val eventRow = transaction {
                    ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.firstOrNull()
                } ?: throw MobileApiException(HttpStatusCode.NotFound, "Event not found", "EVENT_NOT_FOUND")

                val eventDbId = eventRow[ApiEvents.id].value
                val eventCode = eventRow[ApiEvents.eventCode] ?: eventKey.removePrefix(session.teamNumber.toString())
                val tbaEventKey = eventKey

                val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber)
                val (oprsMap, epaHistoryList) = IntegrationService.getEpaOprHistory(settings, eventKey)

                val teamsList = IntegrationService.listTeams(eventKey, session)
                
                var teamNumbersFilter = if (body.teamNumbersElement != null) {
                    try {
                        if (body.teamNumbersElement is JsonArray) {
                            body.teamNumbersElement.jsonArray.mapNotNull { it.jsonPrimitive.intOrNull }
                        } else {
                            body.teamNumbersElement.jsonPrimitive.content.split(",").mapNotNull { it.trim().toIntOrNull() }
                        }
                    } catch (_: Exception) {
                        emptyList<Int>()
                    }
                } else {
                    call.request.queryParameters["team_numbers"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
                }

                var filterTeams = teamsList
                if (teamNumbersFilter.isNotEmpty()) {
                    filterTeams = filterTeams.filter { teamNumbersFilter.contains(it.teamNumber) }
                }

                val epaHistoryByTeam = epaHistoryList.mapNotNull { item ->
                    try {
                        val obj = item.jsonObject
                        val team = obj["team"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val matchField = obj["match"]?.jsonPrimitive?.content ?: ""
                        val alliance = obj["alliance"]?.jsonPrimitive?.content ?: "red"
                        val timestamp = obj["timestamp"]?.jsonPrimitive?.content
                        val epaObj = obj["epa"]?.jsonObject
                        val total = epaObj?.get("total_points")?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val auto = epaObj?.get("auto_points")?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val teleop = epaObj?.get("teleop_points")?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val endgame = epaObj?.get("endgame_points")?.jsonPrimitive?.doubleOrNull ?: 0.0

                        val (level, num) = parseMatchKey(matchField)

                        MobileMatchEpa(
                            team = team,
                            event = eventKey,
                            compLevel = level,
                            matchNumber = num,
                            setNumber = 1,
                            alliance = alliance,
                            epa = MobileEpaBreakdown(total, auto, teleop, endgame),
                            timestamp = timestamp
                        )
                    } catch (_: Exception) {
                        null
                    }
                }.groupBy { it.team }

                val mappedTeams = filterTeams.map { team ->
                    val oprVal = oprsMap["frc${team.teamNumber}"] ?: team.opr ?: 0.0
                    val dprVal = 0.0
                    val ccwmVal = 0.0

                    MobileEpaOprTeam(
                        teamNumber = team.teamNumber,
                        teamName = team.nickname ?: team.name ?: "Team ${team.teamNumber}",
                        matchEpaHistory = (epaHistoryByTeam[team.teamNumber] ?: emptyList()).take(body.limit),
                        oprData = MobileOprData(oprVal, dprVal, ccwmVal)
                    )
                }

                call.respond(
                    MobileEpaOprHistoryResponse(
                        eventDbId = eventDbId,
                        eventCode = eventCode,
                        tbaEventKey = tbaEventKey,
                        teamCount = mappedTeams.size,
                        teams = mappedTeams
                    )
                )
            }

            get("/config/game/stats/epa-opr-history") { epaOprHistoryHandler(Unit) }
            post("/config/game/stats/epa-opr-history") { epaOprHistoryHandler(Unit) }

            // Pit configuration
            get("/config/pit") {
                val session = call.requireMobileSession(secret)
                val config = getPitConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }
            get("/config/pit/active") {
                val session = call.requireMobileSession(secret)
                val config = getPitConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }
            get("/config/pit/team") {
                val session = call.requireMobileSession(secret)
                val config = getPitConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }

            // Qualitative configuration
            get("/config/qualitative") {
                val session = call.requireMobileSession(secret)
                val config = getQualitativeConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }
            get("/config/qualitative/active") {
                val session = call.requireMobileSession(secret)
                val config = getQualitativeConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }
            get("/config/qualitative/team") {
                val session = call.requireMobileSession(secret)
                val config = getQualitativeConfigWithSettings(session.teamNumber)
                call.respond(MobileConfigResponse(config = config))
            }

            // POST PUT Configs
            val saveGameConfigHandler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit = {
                call.requireMobileAdmin(secret)
                val reqBody = call.receiveText()
                try {
                    JsonSupport.json.parseToJsonElement(reqBody)
                } catch (_: Exception) {
                    throw MobileApiException(HttpStatusCode.BadRequest, "Invalid config JSON", "MISSING_BODY")
                }
                
                val session = call.requireMobileSession(secret)
                extractAndUpdateSettingsFromConfigJson(session.teamNumber, reqBody)
                ConfigService.updateConfig(session.teamNumber, reqBody)
                call.respond(MobileSaveConfigResponse())
            }

            post("/config/game") { saveGameConfigHandler(Unit) }
            put("/config/game") { saveGameConfigHandler(Unit) }
            post("/config/game/active") { saveGameConfigHandler(Unit) }
            put("/config/game/active") { saveGameConfigHandler(Unit) }
            post("/config/game/team") { saveGameConfigHandler(Unit) }
            put("/config/game/team") { saveGameConfigHandler(Unit) }

            val savePitConfigHandler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit = {
                call.requireMobileAdmin(secret)
                val reqBody = call.receiveText()
                try {
                    JsonSupport.json.parseToJsonElement(reqBody)
                } catch (_: Exception) {
                    throw MobileApiException(HttpStatusCode.BadRequest, "Invalid config JSON", "MISSING_BODY")
                }

                val session = call.requireMobileSession(secret)
                extractAndUpdateSettingsFromConfigJson(session.teamNumber, reqBody)
                ConfigService.updatePitConfig(session.teamNumber, reqBody)
                call.respond(MobileSaveConfigResponse())
            }

            post("/config/pit") { savePitConfigHandler(Unit) }
            put("/config/pit") { savePitConfigHandler(Unit) }
            post("/config/pit/active") { savePitConfigHandler(Unit) }
            put("/config/pit/active") { savePitConfigHandler(Unit) }
            post("/config/pit/team") { savePitConfigHandler(Unit) }
            put("/config/pit/team") { savePitConfigHandler(Unit) }

            val saveQualitativeConfigHandler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit = {
                call.requireMobileAdmin(secret)
                val reqBody = call.receiveText()
                try {
                    JsonSupport.json.parseToJsonElement(reqBody)
                } catch (_: Exception) {
                    throw MobileApiException(HttpStatusCode.BadRequest, "Invalid config JSON", "MISSING_BODY")
                }

                val session = call.requireMobileSession(secret)
                extractAndUpdateSettingsFromConfigJson(session.teamNumber, reqBody)
                ConfigService.updateQualitativeConfig(session.teamNumber, reqBody)
                call.respond(MobileSaveConfigResponse())
            }

            post("/config/qualitative") { saveQualitativeConfigHandler(Unit) }
            put("/config/qualitative") { saveQualitativeConfigHandler(Unit) }
            post("/config/qualitative/active") { saveQualitativeConfigHandler(Unit) }
            put("/config/qualitative/active") { saveQualitativeConfigHandler(Unit) }
            post("/config/qualitative/team") { saveQualitativeConfigHandler(Unit) }
            put("/config/qualitative/team") { saveQualitativeConfigHandler(Unit) }

            // Alliances
            get("/alliances") {
                val session = call.requireMobileSession(secret)
                val allianceList = AllianceService.listAlliances(session)
                val invitesList = AllianceService.listInvites(session)

                val details = allianceList.map { item ->
                    val memberCount = transaction {
                        AllianceMemberships.selectAll().where { 
                            (AllianceMemberships.allianceId eq item.id) and 
                            (AllianceMemberships.status inList listOf("ADMIN", "ACCEPTED")) 
                        }.count().toInt()
                    }
                    val isActive = false
                    MobileAllianceDetail(
                        id = item.id,
                        name = item.name,
                        description = item.notes,
                        memberCount = memberCount,
                        isActive = isActive
                    )
                }

                val mappedPending = transaction {
                    invitesList.map { item ->
                        val membershipId = AllianceMemberships.selectAll().where {
                            (AllianceMemberships.allianceId eq item.id) and
                            (AllianceMemberships.teamNumber eq session.teamNumber) and
                            (AllianceMemberships.status eq "INVITED")
                        }.firstOrNull()?.get(AllianceMemberships.id)?.value ?: item.id
                        
                        MobilePendingInvite(
                            id = membershipId,
                            allianceId = item.id,
                            allianceName = item.name,
                            fromTeam = item.ownerTeamNumber
                        )
                    }
                }

                val activeAllianceId = AllianceService.getActiveAllianceId(session.teamNumber)

                call.respond(
                    MobileAlliancesResponse(
                        myAlliances = details,
                        pendingInvitations = mappedPending,
                        sentInvitations = emptyList(),
                        activeAllianceId = activeAllianceId
                    )
                )
            }

            post("/alliances") {
                val session = call.requireMobileAdmin(secret)
                val req = call.receive<MobileCreateAllianceRequest>()
                
                val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber)
                val activeKey = settings.resolvedEventKey()

                val saved = AllianceService.createAlliance(session, req.name, activeKey, req.description)
                call.respond(MobileCreateAllianceResponse(allianceId = saved.id))
            }

            post("/alliances/{alliance_id}/invite") {
                val session = call.requireMobileAdmin(secret)
                val allianceId = call.parameters["alliance_id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing alliance_id", "MISSING_DATA")
                val req = call.receive<MobileInviteRequest>()

                AllianceService.inviteTeam(session, allianceId, req.teamNumber)
                call.respond(MobileInviteResponse())
            }

            post("/invitations/{invitation_id}/respond") {
                val session = call.requireMobileSession(secret)
                val invitationId = call.parameters["invitation_id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing invitation_id", "MISSING_DATA")
                val req = call.receive<MobileRespondInviteRequest>()

                val accept = req.response.equals("accept", ignoreCase = true)
                AllianceService.respondToInvite(session, invitationId, accept)
                call.respond(MobileRespondInviteResponse())
            }

            post("/alliances/{alliance_id}/toggle") {
                call.requireMobileAdmin(secret)
                call.parameters["alliance_id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing alliance_id", "MISSING_DATA")
                val req = call.receive<MobileToggleAllianceRequest>()

                call.respond(
                    MobileToggleAllianceResponse(
                        message = "Alliance mode toggled successfully",
                        isActive = req.activate
                    )
                )
            }

            post("/alliances/{alliance_id}/leave") {
                val session = call.requireMobileSession(secret)
                val allianceId = call.parameters["alliance_id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing alliance_id", "MISSING_DATA")
                
                transaction {
                    AllianceMemberships.deleteWhere { (AllianceMemberships.allianceId eq allianceId) and (AllianceMemberships.teamNumber eq session.teamNumber) }
                }

                call.respond(MobileLeaveAllianceResponse(message = "Successfully left alliance", allianceDeleted = false))
            }

            // Chat Messaging
            get("/chat/messages") {
                val session = call.requireMobileSession(secret)
                val type = call.request.queryParameters["type"] ?: "dm"
                val otherUserId = call.request.queryParameters["user"]?.toIntOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val teamNumber = session.teamNumber
                val chatDir = File("instance/chat/users/$teamNumber")

                val messages = if (type == "dm") {
                    if (otherUserId != null) {
                        val otherUser = transaction {
                            AuthService.listUsers(UserSession(0, "system", teamNumber, UserRole.SUPERADMIN), teamFilter = teamNumber)
                                .find { it.id == otherUserId }
                        } ?: throw MobileApiException(HttpStatusCode.NotFound, "User not found", "USER_NOT_FOUND")

                        val key = listOf(session.username, otherUser.username).sorted().joinToString("_")
                        val file = File(chatDir, "${key}_chat_history.json")
                        loadMessages(file)
                    } else {
                        val allFiles = chatDir.listFiles() ?: emptyArray()
                        allFiles.filter { it.name.endsWith("_chat_history.json") && it.name.contains(session.username) }
                            .flatMap { loadMessages(it) }
                    }
                } else {
                    val activeAllianceId = AllianceService.getActiveAllianceId(teamNumber)
                        ?: throw MobileApiException(HttpStatusCode.Forbidden, "Not in an active alliance", "USER_NOT_IN_SCOPE")

                    val groupFile = File("instance/chat/groups/$teamNumber/alliance_${activeAllianceId}_group_chat_history.json")
                    loadMessages(groupFile)
                }

                val sorted = messages.sortedByDescending { it.timestamp }
                val paged = sorted.drop(offset).take(limit)

                call.respond(MobileChatMessagesResponse(count = paged.size, total = sorted.size, messages = paged))
            }

            get("/chat/members") {
                val session = call.requireMobileSession(secret)
                val users = transaction {
                    AuthService.listUsers(UserSession(0, "system", session.teamNumber, UserRole.SUPERADMIN), teamFilter = session.teamNumber)
                }.filter { it.username != session.username }

                val mapped = users.map { u ->
                    MobileChatMember(u.id, u.username, u.username, u.teamNumber)
                }
                call.respond(MobileChatMembersResponse(members = mapped))
            }

            post("/chat/send") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobileChatSendRequest>()

                val teamNumber = session.teamNumber
                val timestamp = Instant.now().toString()
                val messageId = UUID.randomUUID().toString()

                val msg = ChatMessage(
                    id = messageId,
                    sender = session.username,
                    text = req.body,
                    timestamp = timestamp,
                    sender_id = session.userId,
                    offline_id = req.offlineId
                )

                if (req.recipientId != null) {
                    val otherUser = transaction {
                        AuthService.listUsers(UserSession(0, "system", teamNumber, UserRole.SUPERADMIN), teamFilter = teamNumber)
                            .find { it.id == req.recipientId }
                    } ?: throw MobileApiException(HttpStatusCode.NotFound, "Recipient not found", "USER_NOT_IN_SCOPE")

                    val key = listOf(session.username, otherUser.username).sorted().joinToString("_")
                    val file = File("instance/chat/users/$teamNumber/${key}_chat_history.json")
                    
                    val list = loadMessages(file).toMutableList()
                    val fullMsg = msg.copy(recipient = otherUser.username, recipient_id = otherUser.id, conversation_id = otherUser.id)
                    list.add(fullMsg)
                    saveMessages(file, list)

                    val stateFile = File("instance/chat/users/$teamNumber/chat_state_${otherUser.username}.json")
                    val state = loadChatState(stateFile)
                    val newState = state.copy(unreadCount = state.unreadCount + 1)
                    saveChatState(stateFile, newState)

                    call.respond(HttpStatusCode.Created, MobileChatSendResponse(message = fullMsg))

                } else if (req.conversationType == "alliance" || req.group != null) {
                    val file = if (req.conversationType == "alliance") {
                        val activeAllianceId = AllianceService.getActiveAllianceId(teamNumber)
                            ?: throw MobileApiException(HttpStatusCode.Forbidden, "Not in an active alliance", "USER_NOT_IN_SCOPE")
                        
                        File("instance/chat/groups/$teamNumber/alliance_${activeAllianceId}_group_chat_history.json")
                    } else {
                        val groupName = req.group!!.trim().replace("/", "_")
                        File("instance/chat/groups/$teamNumber/${groupName}_group_chat_history.json")
                    }

                    val list = loadMessages(file).toMutableList()
                    val fullMsg = msg.copy(recipient = if (req.conversationType == "alliance") "alliance" else req.group)
                    list.add(fullMsg)
                    saveMessages(file, list)

                    call.respond(HttpStatusCode.Created, MobileChatSendResponse(message = fullMsg))
                } else {
                    throw MobileApiException(HttpStatusCode.BadRequest, "Missing recipient or group", "MISSING_DATA")
                }
            }

            get("/chat/conversations") {
                val session = call.requireMobileSession(secret)
                val teamNumber = session.teamNumber
                val chatDir = File("instance/chat/users/$teamNumber")
                val stateFile = File(chatDir, "chat_state_${session.username}.json")
                val state = loadChatState(stateFile)

                val conversations = mutableListOf<MobileConversation>()

                val allFiles = chatDir.listFiles() ?: emptyArray()
                allFiles.filter { it.name.endsWith("_chat_history.json") && it.name.contains(session.username) }
                    .forEach { file ->
                        val messages = loadMessages(file)
                        if (messages.isNotEmpty()) {
                            val last = messages.last()
                            val otherUserStr = file.name.removeSuffix("_chat_history.json").split("_").find { it != session.username } ?: ""
                            val otherUser = transaction {
                                AuthService.listUsers(UserSession(0, "system", teamNumber, UserRole.SUPERADMIN), teamFilter = teamNumber)
                                    .find { it.username == otherUserStr }
                            }
                            if (otherUser != null) {
                                val unreadKey = "dm:${otherUser.username}"
                                val lastReadId = state.lastRead[unreadKey]
                                val unread = if (lastReadId == null) {
                                    messages.count { it.sender != session.username }
                                } else {
                                    val idx = messages.indexOfFirst { it.id == lastReadId }
                                    if (idx != -1) {
                                        messages.drop(idx + 1).count { it.sender != session.username }
                                    } else {
                                        messages.count { it.sender != session.username }
                                    }
                                }
                                conversations.add(
                                    MobileConversation(
                                        id = otherUser.id,
                                        type = "direct",
                                        title = otherUser.username,
                                        lastMessage = last.text,
                                        lastMessageAt = last.timestamp,
                                        unreadCount = unread
                                    )
                                )
                            }
                        }
                    }

                val activeAllianceId = AllianceService.getActiveAllianceId(teamNumber)
                if (activeAllianceId != null) {
                    val groupFile = File("instance/chat/groups/$teamNumber/alliance_${activeAllianceId}_group_chat_history.json")
                    val messages = loadMessages(groupFile)
                    val last = messages.lastOrNull()
                    val unreadKey = "alliance:$activeAllianceId"
                    val lastReadId = state.lastRead[unreadKey]
                    val unread = if (lastReadId == null) {
                        messages.count { it.sender != session.username }
                    } else {
                        val idx = messages.indexOfFirst { it.id == lastReadId }
                        if (idx != -1) {
                            messages.drop(idx + 1).count { it.sender != session.username }
                        } else {
                            messages.count { it.sender != session.username }
                        }
                    }
                    conversations.add(
                        MobileConversation(
                            id = activeAllianceId,
                            type = "alliance",
                            title = "Alliance Chat",
                            lastMessage = last?.text,
                            lastMessageAt = last?.timestamp,
                            unreadCount = unread
                        )
                    )
                }

                val groupDir = File("instance/chat/groups/$teamNumber")
                val groupFiles = groupDir.listFiles() ?: emptyArray()
                groupFiles.filter { it.name.endsWith("_members.json") }.forEach { file ->
                    val groupName = file.name.removeSuffix("_members.json")
                    val members = try {
                        JsonSupport.json.decodeFromString<List<String>>(file.readText())
                    } catch (_: Exception) {
                        emptyList<String>()
                    }
                    if (members.contains(session.username)) {
                        val histFile = File(groupDir, "${groupName}_group_chat_history.json")
                        val messages = loadMessages(histFile)
                        val last = messages.lastOrNull()
                        conversations.add(
                            MobileConversation(
                                id = groupName.hashCode(),
                                type = "group",
                                title = groupName,
                                lastMessage = last?.text,
                                lastMessageAt = last?.timestamp,
                                unreadCount = 0
                            )
                        )
                    }
                }

                conversations.sortByDescending { it.lastMessageAt }
                call.respond(MobileConversationsResponse(conversations = conversations))
            }

            get("/chat/conversations/{conversation_id}/messages") {
                val session = call.requireMobileSession(secret)
                val conversationId = call.parameters["conversation_id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing conversation_id", "MISSING_DATA")
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

                val teamNumber = session.teamNumber
                val activeAllianceId = AllianceService.getActiveAllianceId(teamNumber)

                val messages = if (activeAllianceId != null && activeAllianceId == conversationId) {
                    val file = File("instance/chat/groups/$teamNumber/alliance_${activeAllianceId}_group_chat_history.json")
                    loadMessages(file)
                } else {
                    val otherUser = transaction {
                        AuthService.listUsers(UserSession(0, "system", teamNumber, UserRole.SUPERADMIN), teamFilter = teamNumber)
                            .find { it.id == conversationId }
                    }
                    if (otherUser != null) {
                        val key = listOf(session.username, otherUser.username).sorted().joinToString("_")
                        val file = File("instance/chat/users/$teamNumber/${key}_chat_history.json")
                        loadMessages(file)
                    } else {
                        emptyList()
                    }
                }

                val sorted = messages.sortedByDescending { it.timestamp }.take(limit).reversed()
                call.respond(MobileChatMessagesResponse(count = sorted.size, total = messages.size, messages = sorted))
            }

            post("/chat/conversations/read") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobileChatReadRequest>()

                val teamNumber = session.teamNumber
                val stateFile = File("instance/chat/users/$teamNumber/chat_state_${session.username}.json")
                val state = loadChatState(stateFile)

                val key = "${req.type}:${req.id}"
                val lastReadMap = state.lastRead.toMutableMap()
                lastReadMap[key] = req.lastReadMessageId

                val newState = state.copy(lastRead = lastReadMap, unreadCount = 0)
                saveChatState(stateFile, newState)

                call.respond(MobileChatReadResponse())
            }

            post("/chat/edit-message") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobileEditMessageRequest>()
                
                val found = editMessageInFiles(session.teamNumber, req.messageId, req.text, session.username)
                if (!found) {
                    throw MobileApiException(HttpStatusCode.NotFound, "Message not found or not sender", "NOT_FOUND")
                }
                call.respond(MobileEditMessageResponse(message = "Message edited."))
            }

            post("/chat/delete-message") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobileDeleteMessageRequest>()

                val found = deleteMessageInFiles(session.teamNumber, req.messageId, session.username)
                if (!found) {
                    throw MobileApiException(HttpStatusCode.NotFound, "Message not found or not sender", "NOT_FOUND")
                }
                call.respond(MobileDeleteMessageResponse(message = "Message deleted."))
            }

            post("/chat/react-message") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobileReactMessageRequest>()

                val reactions = reactMessageInFiles(session.teamNumber, req.messageId, req.emoji, session.username)
                if (reactions == null) {
                    throw MobileApiException(HttpStatusCode.NotFound, "Message not found", "NOT_FOUND")
                }
                call.respond(MobileReactMessageResponse(reactions = reactions))
            }

            get("/chat/state") {
                val session = call.requireMobileSession(secret)
                val stateFile = File("instance/chat/users/${session.teamNumber}/chat_state_${session.username}.json")
                val state = loadChatState(stateFile)
                call.respond(MobileChatStateResponse(state = state))
            }

            // Groups
            get("/chat/groups") {
                val session = call.requireMobileSession(secret)
                val teamNumber = session.teamNumber
                val groupDir = File("instance/chat/groups/$teamNumber")
                
                val files = groupDir.listFiles() ?: emptyArray()
                val groups = files.filter { it.name.endsWith("_members.json") }.map { file ->
                    val name = file.name.removeSuffix("_members.json")
                    val members = try {
                        JsonSupport.json.decodeFromString<List<String>>(file.readText())
                    } catch (_: Exception) {
                        emptyList<String>()
                    }
                    MobileGroup(name, members.size, members.contains(session.username))
                }

                call.respond(MobileGroupsResponse(groups = groups))
            }

            post("/chat/groups") {
                val session = call.requireMobileSession(secret)
                val req = call.receive<MobileCreateGroupRequest>()

                val teamNumber = session.teamNumber
                val groupName = req.group.trim().replace("/", "_")
                
                val teamUsers = transaction {
                    AuthService.listUsers(UserSession(0, "system", teamNumber, UserRole.SUPERADMIN), teamFilter = teamNumber)
                }.map { it.username }.toSet()

                val validMembers = req.members.filter { teamUsers.contains(it) }
                if (validMembers.size != req.members.size) {
                    throw MobileApiException(HttpStatusCode.Forbidden, "All members must belong to team", "USER_NOT_IN_SCOPE")
                }

                val membersFile = File("instance/chat/groups/$teamNumber/${groupName}_members.json")
                membersFile.parentFile.mkdirs()
                membersFile.writeText(JsonSupport.json.encodeToString(validMembers))

                val histFile = File("instance/chat/groups/$teamNumber/${groupName}_group_chat_history.json")
                if (!histFile.exists()) {
                    histFile.writeText("[]")
                }

                call.respond(
                    HttpStatusCode.Created,
                    MobileCreateGroupResponse(
                        group = MobileGroupDetail(groupName, validMembers)
                    )
                )
            }

            get("/chat/groups/{group}/members") {
                val session = call.requireMobileSession(secret)
                val group = call.parameters["group"]!!.trim().replace("/", "_")
                val file = File("instance/chat/groups/${session.teamNumber}/${group}_members.json")
                
                if (!file.exists()) {
                    throw MobileApiException(HttpStatusCode.NotFound, "Group not found", "NOT_FOUND")
                }
                val members = JsonSupport.json.decodeFromString<List<String>>(file.readText())
                call.respond(MobileGroupMembersResponse(members = members))
            }

            post("/chat/groups/{group}/members") {
                val session = call.requireMobileSession(secret)
                val group = call.parameters["group"]!!.trim().replace("/", "_")
                val file = File("instance/chat/groups/${session.teamNumber}/${group}_members.json")
                val req = call.receive<MobileManageGroupMembersRequest>()

                val currentMembers = if (file.exists()) {
                    JsonSupport.json.decodeFromString<List<String>>(file.readText())
                } else {
                    emptyList()
                }

                val teamUsers = transaction {
                    AuthService.listUsers(UserSession(0, "system", session.teamNumber, UserRole.SUPERADMIN), teamFilter = session.teamNumber)
                }.map { it.username }.toSet()

                val validAdd = req.members.filter { teamUsers.contains(it) }
                val newMembersList = (currentMembers + validAdd).distinct()

                file.parentFile.mkdirs()
                file.writeText(JsonSupport.json.encodeToString(newMembersList))

                call.respond(MobileGroupMembersResponse(members = newMembersList))
            }

            delete("/chat/groups/{group}/members") {
                val session = call.requireMobileSession(secret)
                val group = call.parameters["group"]!!.trim().replace("/", "_")
                val file = File("instance/chat/groups/${session.teamNumber}/${group}_members.json")
                val req = try { call.receive<MobileManageGroupMembersRequest>() } catch (_: Exception) { MobileManageGroupMembersRequest() }

                val currentMembers = if (file.exists()) {
                    JsonSupport.json.decodeFromString<List<String>>(file.readText())
                } else {
                    emptyList()
                }

                val listToRemove = if (req.members.isEmpty()) {
                    listOf(session.username)
                } else {
                    req.members
                }

                val newMembersList = currentMembers.filter { m -> !listToRemove.contains(m) }
                file.parentFile.mkdirs()
                file.writeText(JsonSupport.json.encodeToString(newMembersList))

                call.respond(MobileGroupMembersResponse(members = newMembersList))
            }

            // Notifications stubs
            get("/notifications/scheduled") {
                call.requireMobileSession(secret)
                call.respond(MobileNotificationsScheduledResponse())
            }

            get("/notifications/unread") {
                val session = call.requireMobileSession(secret)
                val stateFile = File("instance/chat/users/${session.teamNumber}/chat_state_${session.username}.json")
                val state = loadChatState(stateFile)
                call.respond(
                    MobileNotificationsUnreadResponse(
                        chatState = state,
                        scheduled = MobileNotificationsScheduledResponse()
                    )
                )
            }

            get("/notifications/past") {
                call.requireMobileSession(secret)
                call.respond(MobileNotificationsScheduledResponse())
            }

            // Sync status & triggering
            get("/sync/status") {
                call.requireMobileSession(secret)
                val stats = transaction {
                    val teamCount = ApiTeams.selectAll().count()
                    val matchCount = ApiMatches.selectAll().count()
                    mapOf("teams" to teamCount, "matches" to matchCount)
                }
                call.respond(
                    MobileSyncStatusResponse(
                        serverTime = Instant.now().toString(),
                        lastUpdates = stats
                    )
                )
            }

            post("/sync/trigger") {
                val session = call.requireMobileAdmin(secret)
                val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber)

                val queued = com.obsidianscout.integrations.SyncScheduler.enqueueFullSync(settings)
                val message = if (queued) "Sync enqueued in background" else "Another sync is already running"

                call.respond(
                    MobileSyncTriggerResponse(
                        results = MobileSyncResults(
                            teamsSync = SyncSubStatus(queued, message),
                            matchesSync = SyncSubStatus(queued, message),
                            allianceSync = SyncAllianceSubStatus(queued)
                        )
                    )
                )
            }

            // Admin endpoints
            get("/admin/roles") {
                call.requireMobileAdmin(secret)
                call.respond(
                    MobileAdminRolesResponse(
                        roles = listOf(
                            MobileAdminRole(1, "superadmin", "Global super administrator"),
                            MobileAdminRole(2, "admin", "Team administrator"),
                            MobileAdminRole(3, "analytics", "Team analyst"),
                            MobileAdminRole(4, "scout", "Standard team scout")
                        )
                    )
                )
            }

            get("/admin/users") {
                val session = call.requireMobileAdmin(secret)
                val users = transaction {
                    AuthService.listUsers(session)
                }
                val mapped = users.map { u ->
                    MobileAdminUser(u.id, u.username, u.email, u.teamNumber, listOf(u.role.name.lowercase()))
                }
                call.respond(MobileAdminUsersResponse(users = mapped))
            }

            post("/admin/users") {
                val session = call.requireMobileAdmin(secret)
                val req = call.receive<MobileAdminCreateUserRequest>()
                val role = try { UserRole.valueOf(req.roles.firstOrNull()?.uppercase() ?: "SCOUT") } catch (_: Exception) { UserRole.SCOUT }
                
                val created = try {
                    AuthService.createUser(
                        callerSession = session,
                        username = req.username,
                        teamNumber = req.teamNumber ?: session.teamNumber,
                        password = req.password,
                        role = role,
                        email = req.email
                    )
                } catch (e: Exception) {
                    throw MobileApiException(HttpStatusCode.Conflict, e.message ?: "Conflict creating user", "USERNAME_EXISTS")
                }

                call.respond(
                    HttpStatusCode.Created,
                    MobileAdminUserResponse(
                        user = MobileAdminUser(created.id, created.username, created.email, created.teamNumber, listOf(created.role.name.lowercase()))
                    )
                )
            }

            get("/admin/users/{user_id}") {
                val session = call.requireMobileAdmin(secret)
                val userId = call.parameters["user_id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing user_id", "MISSING_DATA")

                val target = transaction {
                    AuthService.listUsers(session).find { it.id == userId }
                } ?: throw MobileApiException(HttpStatusCode.NotFound, "User not found", "NOT_FOUND")

                call.respond(
                    MobileAdminUserResponse(
                        user = MobileAdminUser(target.id, target.username, target.email, target.teamNumber, listOf(target.role.name.lowercase()))
                    )
                )
            }

            put("/admin/users/{user_id}") {
                val session = call.requireMobileAdmin(secret)
                val userId = call.parameters["user_id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing user_id", "MISSING_DATA")

                val req = call.receive<MobileAdminUpdateUserRequest>()
                val role = req.roles?.firstOrNull()?.let { roleName ->
                    try { UserRole.valueOf(roleName.uppercase()) } catch (_: Exception) { null }
                }

                val updated = try {
                    AuthService.updateUser(
                        callerSession = session,
                        targetUserId = userId,
                        newUsername = req.username,
                        newPassword = req.password,
                        newRole = role,
                        newEmail = req.email,
                        newTeamNumber = req.teamNumber
                    )
                } catch (e: com.obsidianscout.auth.ApiException) {
                    throw MobileApiException(e.status, e.message, "UPDATE_FAILED")
                }

                call.respond(
                    MobileAdminUserResponse(
                        user = MobileAdminUser(
                            id = updated.id,
                            username = updated.username,
                            email = updated.email,
                            teamNumber = updated.teamNumber,
                            roles = listOf(updated.role.name.lowercase()),
                            isActive = true
                        )
                    )
                )
            }

            delete("/admin/users/{user_id}") {
                val session = call.requireMobileAdmin(secret)
                val userId = call.parameters["user_id"]?.toIntOrNull()
                    ?: throw MobileApiException(HttpStatusCode.BadRequest, "Missing user_id", "MISSING_DATA")

                try {
                    AuthService.deleteUser(session, userId)
                } catch (e: com.obsidianscout.auth.ApiException) {
                    throw MobileApiException(e.status, e.message, "DELETE_FAILED")
                }

                call.respond(mapOf("success" to true))
            }

            // Graphs & Visualize Plotly Fallback
            val graphsHandler: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit = {
                val session = call.requireMobileSession(secret)
                val body = try { call.receive<MobileGraphRequest>() } catch (_: Exception) { MobileGraphRequest() }

                val eventId = body.eventId ?: com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber).resolvedEventKey()
                val eventKey = resolveEventKey(eventId, session.teamNumber)

                val targetTeamNumbers = if (body.teamNumber != null) {
                    listOf(body.teamNumber)
                } else {
                    body.teamNumbers
                }

                if (targetTeamNumbers.isEmpty()) {
                    throw MobileApiException(HttpStatusCode.BadRequest, "No team numbers provided", "MISSING_TEAMS_OR_DATA")
                }

                val config = ConfigService.getConfig(session.teamNumber)
                val allEntries = ScoutingService.listEntries(session, includePrescout = true)

                val traces = targetTeamNumbers.map { num ->
                    val teamEntries = allEntries.filter { it.targetTeamNumber == num && it.eventKey == eventKey }
                        .sortedBy { it.matchNumber }
                    
                    val xLabels = teamEntries.map { "Match ${it.matchNumber}" }
                    val yValues = teamEntries.map { entry ->
                        when (body.metric) {
                            "total_points", "points", "tot" -> scorePhase(config, entry.data, "auto") + scorePhase(config, entry.data, "teleop") + scorePhase(config, entry.data, "endgame")
                            "auto_points" -> scorePhase(config, entry.data, "auto")
                            "teleop_points" -> scorePhase(config, entry.data, "teleop")
                            "endgame_points" -> scorePhase(config, entry.data, "endgame")
                            else -> {
                                val elem = entry.data[body.metric] as? JsonPrimitive
                                elem?.content?.toDoubleOrNull() ?: elem?.content?.toIntOrNull()?.toDouble() ?: 0.0
                            }
                        }
                    }
                    PlotlyTrace(
                        type = body.graphType ?: "scatter",
                        x = xLabels,
                        y = yValues,
                        name = num.toString()
                    )
                }

                call.respond(
                    MobileGraphResponse(
                        fallbackPlotlyJson = FallbackPlotlyJson(
                            data = traces,
                            layout = PlotlyLayout(title = "${body.metric} (${body.mode})")
                        )
                    )
                )
            }

            post("/graphs") { graphsHandler(Unit) }
            post("/graphs/visualize") { graphsHandler(Unit) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Routing Helper Functions
// ─────────────────────────────────────────────────────────────────────────────

fun resolveEventKey(eventId: String?, teamNumber: Int): String {
    val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(teamNumber)
    if (eventId.isNullOrBlank()) {
        return settings.resolvedEventKey()
    }
    val trimmed = eventId.trim().lowercase()
    
    val numericId = trimmed.toIntOrNull()
    if (numericId != null) {
        val dbKey = transaction {
            ApiEvents.selectAll().where { ApiEvents.id eq numericId }
                .firstOrNull()?.get(ApiEvents.eventKey)
        }
        if (dbKey != null) return dbKey
    }
    
    if (trimmed.length > 4 && trimmed.take(4).all { it.isDigit() }) {
        return trimmed
    }
    
    val season = try {
        val configJson = ConfigService.getConfigJson(teamNumber)
        val elem = JsonSupport.json.parseToJsonElement(configJson).jsonObject
        elem["season"]?.jsonPrimitive?.intOrNull
    } catch (_: Exception) {
        null
    } ?: settings.year
    
    return "$season$trimmed"
}

fun mapTeamRecord(team: TeamRecord, dbId: Int): MobileTeam {
    val loc = listOfNotNull(team.city, team.state).filter { it.isNotBlank() }.joinToString(", ")
    return MobileTeam(
        id = dbId,
        teamNumber = team.teamNumber,
        teamName = team.nickname ?: team.name ?: "Team ${team.teamNumber}",
        location = loc.ifBlank { "City, State" }
    )
}

fun extractTeamNumbers(teams: List<String>): String {
    return teams.map { team ->
        team.substringAfter("/").removePrefix("frc").filter { it.isDigit() }
    }.joinToString(",")
}

fun parseMatchScores(dataJson: String): Triple<Int?, Int?, String?> {
    return try {
        val json = JsonSupport.json.parseToJsonElement(dataJson).jsonObject
        val alliances = json["alliances"]?.jsonObject
        val red = alliances?.get("red")?.jsonObject
        val blue = alliances?.get("blue")?.jsonObject
        val redScore = red?.get("score")?.jsonPrimitive?.intOrNull
        val blueScore = blue?.get("score")?.jsonPrimitive?.intOrNull
        val winner = json["winning_alliance"]?.jsonPrimitive?.content?.lowercase()?.takeIf { it.isNotBlank() }
        Triple(redScore, blueScore, winner)
    } catch (_: Exception) {
        Triple(null, null, null)
    }
}

fun formatEpochSecond(epochSecond: Long?): String? {
    if (epochSecond == null || epochSecond <= 0) return null
    return Instant.ofEpochSecond(epochSecond).toString()
}

fun mapMatchType(compLevel: String): String {
    return when (compLevel.lowercase()) {
        "qm" -> "Qualification"
        "ef" -> "Octofinal"
        "qf" -> "Quarterfinal"
        "sf" -> "Semifinal"
        "f" -> "Final"
        "p" -> "Practice"
        else -> compLevel
    }
}

fun parseMatchKey(matchKey: String): Pair<String, Int> {
    val suffix = matchKey.substringAfterLast("_")
    val level = suffix.takeWhile { !it.isDigit() }
    val number = suffix.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0
    return Pair(level, number)
}

fun scorePhase(config: ScoutingConfig, data: JsonObject, phase: String): Double {
    return config.fields.filter { it.phase?.lowercase() == phase.lowercase() }
        .sumOf { field ->
            val element = data[field.id] ?: return@sumOf 0.0
            if (element is JsonNull) return@sumOf 0.0
            when (field.type.lowercase()) {
                "counter", "number", "rating" -> {
                    val primitive = element as? JsonPrimitive ?: return@sumOf 0.0
                    val value = primitive.content.toDoubleOrNull() ?: primitive.content.toIntOrNull()?.toDouble() ?: 0.0
                    (field.pointsPer ?: 0.0) * value
                }
                "checkbox" -> {
                    val primitive = element as? JsonPrimitive ?: return@sumOf 0.0
                    val enabled = primitive.content.lowercase().toBoolean()
                    if (enabled) field.pointsPer ?: 0.0 else 0.0
                }
                "select" -> {
                    val primitive = element as? JsonPrimitive ?: return@sumOf 0.0
                    val label = primitive.content
                    val option = field.options.firstOrNull { it.value == label || it.label == label }
                    option?.points ?: 0.0
                }
                else -> 0.0
            }
        }
}

fun findDuplicatePitScouting(ownerTeam: Int, localId: String): Int? {
    return transaction {
        PitScoutingEntries.selectAll().where { PitScoutingEntries.ownerTeamNumber eq ownerTeam }
            .mapNotNull { row ->
                val dataJson = row[PitScoutingEntries.dataJson]
                try {
                    val jsonObj = JsonSupport.json.parseToJsonElement(dataJson).jsonObject
                    val lId = jsonObj["local_id"]?.jsonPrimitive?.content
                    if (lId == localId) row[PitScoutingEntries.id].value else null
                } catch (_: Exception) {
                    null
                }
            }.firstOrNull()
    }
}

fun parseEventLocation(eventKey: String): String {
    val dataJson = transaction {
        ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.firstOrNull()?.get(ApiEvents.dataJson)
    } ?: ""
    return try {
        val json = JsonSupport.json.parseToJsonElement(dataJson).jsonObject
        val city = json["city"]?.jsonPrimitive?.content ?: ""
        val state = json["state_prov"]?.jsonPrimitive?.content ?: ""
        val country = json["country"]?.jsonPrimitive?.content ?: ""
        val combined = listOf(city, state).filter { it.isNotBlank() }.joinToString(", ")
        if (combined.isNotBlank()) combined else (if (country.isNotBlank()) country else "City, State")
    } catch (_: Exception) {
        "City, State"
    }
}

// Chat helper functions for files

fun loadMessages(file: File): List<ChatMessage> {
    if (!file.exists()) return emptyList()
    return try {
        JsonSupport.json.decodeFromString<List<ChatMessage>>(file.readText())
    } catch (_: Exception) {
        emptyList()
    }
}

fun saveMessages(file: File, messages: List<ChatMessage>) {
    file.parentFile?.mkdirs()
    file.writeText(JsonSupport.json.encodeToString(messages))
}

fun loadChatState(file: File): ChatState {
    if (!file.exists()) return ChatState()
    return try {
        JsonSupport.json.decodeFromString<ChatState>(file.readText())
    } catch (_: Exception) {
        ChatState()
    }
}

fun saveChatState(file: File, state: ChatState) {
    file.parentFile?.mkdirs()
    file.writeText(JsonSupport.json.encodeToString(state))
}

fun editMessageInFiles(teamNumber: Int, messageId: String, newText: String, username: String): Boolean {
    val userDir = File("instance/chat/users/$teamNumber")
    val userFiles = userDir.listFiles() ?: emptyArray()
    for (file in userFiles.filter { it.name.endsWith("_chat_history.json") }) {
        val messages = loadMessages(file)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val msg = messages[idx]
            if (msg.sender == username) {
                val updatedList = messages.toMutableList()
                updatedList[idx] = msg.copy(text = newText, edited = true, edited_timestamp = Instant.now().toString())
                saveMessages(file, updatedList)
                return true
            }
        }
    }

    val groupDir = File("instance/chat/groups/$teamNumber")
    val groupFiles = groupDir.listFiles() ?: emptyArray()
    for (file in groupFiles.filter { it.name.endsWith("_chat_history.json") }) {
        val messages = loadMessages(file)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val msg = messages[idx]
            if (msg.sender == username) {
                val updatedList = messages.toMutableList()
                updatedList[idx] = msg.copy(text = newText, edited = true, edited_timestamp = Instant.now().toString())
                saveMessages(file, updatedList)
                return true
            }
        }
    }
    return false
}

fun deleteMessageInFiles(teamNumber: Int, messageId: String, username: String): Boolean {
    val userDir = File("instance/chat/users/$teamNumber")
    val userFiles = userDir.listFiles() ?: emptyArray()
    for (file in userFiles.filter { it.name.endsWith("_chat_history.json") }) {
        val messages = loadMessages(file)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val msg = messages[idx]
            if (msg.sender == username) {
                val updatedList = messages.toMutableList()
                updatedList.removeAt(idx)
                saveMessages(file, updatedList)
                return true
            }
        }
    }

    val groupDir = File("instance/chat/groups/$teamNumber")
    val groupFiles = groupDir.listFiles() ?: emptyArray()
    for (file in groupFiles.filter { it.name.endsWith("_chat_history.json") }) {
        val messages = loadMessages(file)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val msg = messages[idx]
            if (msg.sender == username) {
                val updatedList = messages.toMutableList()
                updatedList.removeAt(idx)
                saveMessages(file, updatedList)
                return true
            }
        }
    }
    return false
}

fun reactMessageInFiles(teamNumber: Int, messageId: String, emoji: String, username: String): List<ReactionSummary>? {
    val updateReactionList = { messages: List<ChatMessage>, idx: Int ->
        val msg = messages[idx]
        val currentReactions = msg.reactions
        val exists = currentReactions.find { it.username == username && it.emoji == emoji }
        val newReactions = if (exists != null) {
            currentReactions.filter { it != exists }
        } else {
            currentReactions + MessageReaction(username, emoji)
        }
        val summary = newReactions.groupBy { it.emoji }.map { ReactionSummary(it.key, it.value.size) }
        Pair(msg.copy(reactions = newReactions, reactions_summary = summary), summary)
    }

    val userDir = File("instance/chat/users/$teamNumber")
    val userFiles = userDir.listFiles() ?: emptyArray()
    for (file in userFiles.filter { it.name.endsWith("_chat_history.json") }) {
        val messages = loadMessages(file)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val (updatedMsg, summary) = updateReactionList(messages, idx)
            val list = messages.toMutableList()
            list[idx] = updatedMsg
            saveMessages(file, list)
            return summary
        }
    }

    val groupDir = File("instance/chat/groups/$teamNumber")
    val groupFiles = groupDir.listFiles() ?: emptyArray()
    for (file in groupFiles.filter { it.name.endsWith("_chat_history.json") }) {
        val messages = loadMessages(file)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val (updatedMsg, summary) = updateReactionList(messages, idx)
            val list = messages.toMutableList()
            list[idx] = updatedMsg
            saveMessages(file, list)
            return summary
        }
    }
    return null
}

// Extensions and helpers
private fun JsonObject.readString(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content
}

private fun JsonObject.readInt(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.toIntOrNull()
}

private fun JsonObject.readDouble(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.toDoubleOrNull()
}

private fun JsonElement?.teamKeys(): List<String> {
    if (this == null) return emptyList()
    return try {
        JsonSupport.json.decodeFromString<List<String>>(this.toString())
    } catch (_: Exception) {
        emptyList()
    }
}

private fun JsonElement?.readString(key: String): String? {
    val obj = this as? JsonObject ?: return null
    return obj.readString(key)
}

private fun JsonElement?.readInt(key: String): Int? {
    val obj = this as? JsonObject ?: return null
    return obj.readInt(key)
}

private fun JsonElement?.readLong(key: String): Long? {
    val obj = this as? JsonObject ?: return null
    val primitive = obj[key] as? JsonPrimitive ?: return null
    return primitive.content.toLongOrNull()
}

private fun Double?.orZero(): Double = this ?: 0.0

private fun JsonElement?.findArray(keys: List<String>): JsonArray {
    if (this == null) return JsonArray(emptyList())
    val obj = this as? JsonObject ?: return JsonArray(emptyList())
    for (k in keys) {
        val v = obj[k]
        if (v is JsonArray) return v
    }
    return JsonArray(emptyList())
}

private fun String?.clipTeamText(): String? {
    if (this == null) return null
    return if (this.length > 512) this.take(512) else this
}

private fun String?.clipTeamLocation(): String? {
    if (this == null) return null
    return if (this.length > 80) this.take(80) else this
}


