package com.obsidianscout.routes

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class LoginRequest(
    val username: String,
    val teamNumber: Int,
    val password: String,
    val keepMeLoggedIn: Boolean = false
)

@Serializable
data class RegisterRequest(
    val username: String,
    val teamNumber: Int,
    val password: String,
    val role: UserRole = UserRole.SCOUT,
    val email: String? = null,
    val keepMeLoggedIn: Boolean = false
)

@Serializable
data class LoginResponse(
    val user: UserSession
)

@Serializable
data class MeResponse(
    val user: UserSession
)

@Serializable
data class LoginStatusResponse(
    val loggedIn: Boolean
)

@Serializable
data class AuthProviderInfo(
    val type: String,
    val enabled: Boolean
)

@Serializable
data class AuthProvidersResponse(
    val providers: List<AuthProviderInfo>
)

@Serializable
data class ConfigUpdateRequest(
    val configJson: String
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val teamNumber: Int,
    val password: String,
    val role: UserRole = UserRole.SCOUT,
    val email: String? = null
)

@Serializable
data class UpdateUserRequest(
    val username: String? = null,
    val password: String? = null,
    val role: UserRole? = null,
    val email: String? = null,
    val profilePicture: String? = null,
    val clearProfilePicture: Boolean = false,
    val notificationPreference: String? = null
)

@Serializable
data class ScoutingEntryRequest(
    val data: JsonObject
)

@Serializable
data class ApiKeysPayload(
    val tbaKey: String = "",
    val firstUsername: String = "",
    val firstKey: String = ""
)

@Serializable
data class ApiSettingsPayload(
    val year: Int,
    val eventCode: String = "",
    val eventKey: String = "",
    val timezone: String = "America/New_York",
    val preferredSource: String = "tba",
    val useStatboticsEpa: Boolean = false,
    val useTbaOpr: Boolean = false,
    val chatEnabled: Boolean = true,
    val apiKeys: ApiKeysPayload = ApiKeysPayload()
)

@Serializable
data class SettingsResponse(
    val settings: ApiSettingsPayload
)

@Serializable
data class SyncResponse(
    val synced: Int,
    val source: String,
    val eventKey: String = "",
    val queued: Boolean = false,
    val message: String? = null
)

@Serializable
data class SyncStatusResponse(
    val intervalMinutes: Double = 7.5,
    val lastSyncAt: String? = null,
    val lastSyncSummary: String? = null,
    val lastSyncError: String? = null,
    val lastSyncTeams: Int? = null,
    val lastSyncMatches: Int? = null,
    val lastSyncTeamCount: Int? = null,
    val lastSyncFailedTeams: Int? = null,
    val syncInProgress: Boolean = false,
    val currentSyncLabel: String? = null
)

@Serializable
data class EventRecord(
    val eventKey: String,
    val name: String,
    val year: Int,
    val eventCode: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val timezone: String? = null
)

/**
 * Used by PUT /api/events to update an event, optionally renaming its key.
 * [oldKey] is the current event key; [event] carries the new data (including the potentially new eventKey).
 */
@Serializable
data class EventRenameRequest(
    val oldKey: String,
    val event: EventRecord
)

@Serializable
data class TeamRecord(
    val eventKey: String,
    val teamKey: String,
    val teamNumber: Int,
    val name: String? = null,
    val nickname: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    val opr: Double? = null,
    val epa: Double? = null,
    val averagePoints: Double? = null
)

@Serializable
data class MatchRecord(
    val matchKey: String,
    val eventKey: String,
    val compLevel: String,
    val setNumber: Int? = null,
    val matchNumber: Int? = null,
    val scheduledTime: Long? = null,
    val actualTime: Long? = null,
    val redTeams: List<String> = emptyList(),
    val blueTeams: List<String> = emptyList(),
    /** Human-readable label, e.g. "QM 4" (same match from TBA and FIRST). */
    val label: String = ""
)

@Serializable
data class SummaryResponse(
    val entries: Int,
    val events: Int,
    val teams: Int,
    val matches: Int
)

@Serializable
data class MatchTeamPrediction(
    val teamNumber: Int,
    val teamKey: String? = null,
    val nickname: String?,
    val averageScoutedScore: Double?,
    val scoutedMatchesCount: Int,
    val epa: Double?,
    val opr: Double?,
    val hasDiscrepancy: Boolean = false
)

@Serializable
data class AlliancePrediction(
    val teams: List<MatchTeamPrediction>,
    val totalScoutedScore: Double,
    val totalEpa: Double,
    val totalOpr: Double
)

@Serializable
data class MatchPredictionResponse(
    val matchKey: String,
    val label: String,
    val redAlliance: AlliancePrediction,
    val blueAlliance: AlliancePrediction,
    val useStatboticsEpa: Boolean,
    val useTbaOpr: Boolean
)

// ─────────────────────────────────────
// Alliance models
// ─────────────────────────────────────

@Serializable
data class CreateAllianceRequest(
    val name: String,
    val eventKey: String? = null,
    val notes: String? = null,
    val year: Int? = null,
    val eventCode: String? = null
)

@Serializable
data class UpdateAllianceRequest(
    val name: String,
    val eventKey: String? = null,
    val notes: String? = null,
    val year: Int? = null,
    val eventCode: String? = null
)

@Serializable
data class InviteTeamRequest(
    val partnerTeamNumber: Int
)

@Serializable
data class RespondInviteRequest(
    val accept: Boolean
)

@Serializable
data class InviteCountResponse(
    val count: Int
)

@Serializable
data class AllianceImportDataRequest(
    val sourceTeamNumber: Int,
    val eventKey: String? = null,
    val includeMatchScouting: Boolean = true,
    val includePitScouting: Boolean = true,
    val includeQualitativeScouting: Boolean = true
)

@Serializable
data class AllianceImportDataResponse(
    val importedMatchScouting: Int,
    val importedPitScouting: Int,
    val importedQualitativeScouting: Int,
    val sourceTeamNumber: Int,
    val eventKey: String?,
    val skippedDuplicates: Int = 0
)

@Serializable
data class AllianceImportSourceRecord(
    val teamNumber: Int,
    val eventKey: String?,
    val matchScoutingCount: Int,
    val pitScoutingCount: Int,
    val qualitativeScoutingCount: Int
)

@Serializable
data class ToggleAllianceDisableRequest(
    val disabled: Boolean
)

@Serializable
data class ToggleAllianceActiveRequest(
    val active: Boolean
)

@Serializable
data class ForgotPasswordRequest(
    val username: String? = null,
    val teamNumber: Int? = null,
    val email: String? = null
)

@Serializable
data class AccountInfo(
    val userId: Int,
    val username: String,
    val teamNumber: Int
)

@Serializable
data class VerifyResetTokenResponse(
    val valid: Boolean,
    val accounts: List<AccountInfo> = emptyList()
)

@Serializable
data class ResetPasswordRequest(
    val token: String,
    val userId: Int? = null,
    val newUsername: String? = null,
    val newPassword: String
)

@Serializable
data class SmtpTestConnectionRequest(
    val host: String,
    val port: Int,
    val username: String,
    val passwordPlain: String,
    val fromAddress: String,
    val encryption: String,
    val testEmail: String
)

@Serializable
data class BannerDto(
    val id: Int,
    val teamNumber: Int,
    val message: String,
    val bannerType: String,
    val isDismissible: Boolean,
    val isExpandable: Boolean,
    val expandableMessage: String,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class BannerCreateRequest(
    val teamNumber: Int? = 0,
    val message: String,
    val bannerType: String? = "info",
    val isDismissible: Boolean? = true,
    val isExpandable: Boolean? = false,
    val expandableMessage: String? = "",
    val isActive: Boolean? = true
)

@Serializable
data class BannerUpdateRequest(
    val teamNumber: Int? = null,
    val message: String? = null,
    val bannerType: String? = null,
    val isDismissible: Boolean? = null,
    val isExpandable: Boolean? = null,
    val expandableMessage: String? = null,
    val isActive: Boolean? = null
)

@Serializable
data class ChatMessageDto(
    val id: Int,
    val teamNumber: Int,
    val groupName: String,
    val userId: Int,
    val username: String,
    val content: String,
    val createdAt: String,
    val reactions: Map<String, List<String>>, // maps reaction emoji to list of usernames who reacted
    val profilePicture: String? = null
)

@Serializable
data class SendMessageRequest(
    val groupName: String,
    val content: String
)

@Serializable
data class ReactMessageRequest(
    val emoji: String
)

@Serializable
data class GroupUnreadStatus(
    val groupName: String,
    val unreadCount: Int,
    val mentionCount: Int
)

@Serializable
data class UnreadStatusDto(
    val unreadCount: Int,
    val mentionCount: Int,
    val groups: List<GroupUnreadStatus> = emptyList()
)

@Serializable
data class ReadChatGroupRequest(
    val groupName: String
)

@Serializable
data class PushSubscriptionDto(
    val endpoint: String,
    val keys: PushKeysDto
)

@Serializable
data class PushKeysDto(
    val p256dh: String,
    val auth: String
)




