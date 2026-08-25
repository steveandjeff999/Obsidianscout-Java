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
    val program: String = "FRC",
    val password: String,
    val keepMeLoggedIn: Boolean = false
)

@Serializable
data class RegisterRequest(
    val username: String,
    val teamNumber: Int,
    val program: String = "FRC",
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
data class VersionResponse(
    val version: String,
    val executionMode: String = "Unknown"
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
data class ApplyDefaultConfigRequest(
    val presetName: String,
    val configType: String = "match"
)

@Serializable
data class ResetConfigRequest(
    val configType: String = "match"
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val teamNumber: Int,
    val password: String,
    val program: String = "FRC",
    val role: UserRole = UserRole.SCOUT,
    val email: String? = null
)

@Serializable
data class TourProgress(
    val completed: List<String> = emptyList(),
    val active: String? = null,
    val stepIndex: Int = 0
)

@Serializable
data class UpdateUserRequest(
    val username: String? = null,
    val password: String? = null,
    val role: UserRole? = null,
    val email: String? = null,
    val profilePicture: String? = null,
    val clearProfilePicture: Boolean = false,
    val notificationPreference: String? = null,
    val nodeAlertsEnabled: Boolean? = null
)

@Serializable
data class NodeAlertsEnrollmentRequest(
    val enrolled: Boolean
)

@Serializable
data class NodeAlertsEnrollmentResponse(
    val success: Boolean,
    val enrolled: Boolean,
    val message: String
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
    val apiKeys: ApiKeysPayload = ApiKeysPayload(),
    val scoutPages: List<String> = emptyList(),
    val analyticsPages: List<String> = emptyList(),
    val adminPages: List<String> = emptyList(),
    val theme: com.obsidianscout.integrations.ThemeSettings = com.obsidianscout.integrations.ThemeSettings(),
    val themes: List<com.obsidianscout.integrations.ThemeSettings> = emptyList(),
    val activeThemeName: String = "",
    val setupWizardCompleted: Boolean = false,
    val program: String = "FRC"
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
    val label: String = "",
    /** IANA timezone name for the event venue (e.g. "America/New_York").
     *  All scheduledTime/actualTime values are UTC epoch seconds — this field
     *  is purely for display purposes so the browser can show a venue-time tooltip. */
    val eventTimezone: String? = null
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
    val userId: String,
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
    val userId: String? = null,
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
    val id: String,
    val teamNumber: Int,
    val message: String,
    val bannerType: String,
    val isDismissible: Boolean,
    val isExpandable: Boolean,
    val expandableMessage: String,
    val showOnLogin: Boolean = false,
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
    val showOnLogin: Boolean? = false,
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
    val showOnLogin: Boolean? = null,
    val isActive: Boolean? = null
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val teamNumber: Int,
    val groupName: String,
    val userId: String,
    val username: String,
    val content: String,
    val createdAt: String,
    val reactions: Map<String, List<String>>, // maps reaction emoji to list of usernames who reacted
    val profilePicture: String? = null,
    val isEdited: Boolean = false,
    val updatedAt: String? = null
)

@Serializable
data class SendMessageRequest(
    val groupName: String,
    val content: String
)

@Serializable
data class EditChatMessageRequest(
    val content: String
)

@Serializable
data class CreateGroupRequest(
    val groupName: String,
    val allowedRoles: List<String> = emptyList(),
    val allowedUserIds: List<String> = emptyList()
)

@Serializable
data class ChatGroupDetailsDto(
    val groupName: String,
    val isDefault: Boolean = false,
    val allowedRoles: List<String> = emptyList(),
    val allowedUserIds: List<String> = emptyList(),
    val createdByUserId: String? = null,
    val createdAt: String? = null
)

@Serializable
data class UpdateGroupPermissionsRequest(
    val allowedRoles: List<String> = emptyList(),
    val allowedUserIds: List<String> = emptyList()
)

@Serializable
data class ChatTeamMemberDto(
    val userId: String,
    val username: String,
    val role: String,
    val profilePicture: String? = null
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

@Serializable
data class ContactRequest(
    val type: String,
    val name: String,
    val replyToEmail: String? = null,
    val message: String
)

@Serializable
data class MigrationRequest(
    val sourceType: String,
    val sqliteInstancePath: String? = null,
    val pgConfig: com.obsidianscout.db.PostgresMigrationConfig? = null
)

@Serializable
data class ResetDatabaseRequest(
    val password: String
)

@Serializable
data class WipeTeamDataRequest(
    val password: String
)

@Serializable
data class FcmPublicConfigDto(
    val enabled: Boolean,
    val projectId: String,
    val apiKey: String,
    val appId: String,
    val messagingSenderId: String,
    val vapidKey: String
)

@Serializable
data class FcmAdminConfigDto(
    val enabled: Boolean,
    val projectId: String,
    val apiKey: String,
    val appId: String,
    val messagingSenderId: String,
    val vapidKey: String,
    val hasServiceAccountJson: Boolean,
    val isInitialized: Boolean
)

@Serializable
data class SaveFcmConfigRequest(
    val enabled: Boolean,
    val projectId: String,
    val apiKey: String = "",
    val appId: String = "",
    val messagingSenderId: String = "",
    val serviceAccountJson: String = "",
    val vapidKey: String = ""
)

@Serializable
data class RegisterFcmTokenRequest(
    val deviceToken: String,
    val platform: String = "android"
)

@Serializable
data class UnregisterFcmTokenRequest(
    val deviceToken: String
)

@Serializable
data class TestApiRequest(
    val api: String = "",
    val tbaKey: String? = null,
    val firstUsername: String? = null,
    val firstKey: String? = null,
    val statboticsBaseUrl: String? = null
)

@Serializable
data class TestApiResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class ConfigUpdateResponse(
    val config: com.obsidianscout.config.ScoutingConfig,
    val hasFieldChanges: Boolean = false,
    val changedFields: List<String> = emptyList(),
    val entryCount: Int = 0,
    val configKind: String = "game"
)

@Serializable
data class FieldMappingDTO(
    val oldKey: String,
    val newKey: String? = null,
    val action: String = "map", // "map", "keep", "delete"
    val targetType: String? = null,
    val valueMap: Map<String, String>? = null
)

@Serializable
data class ConfigMigrationRequest(
    val configKind: String, // "game", "pit", "qual"
    val mappings: List<FieldMappingDTO> = emptyList(),
    val defaultValues: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
)

@Serializable
data class ConfigMigrationSample(
    val id: String,
    val before: JsonObject,
    val after: JsonObject
)

@Serializable
data class ConfigMigrationPreviewResponse(
    val sampleEntries: List<ConfigMigrationSample>,
    val totalAffectedCount: Int
)

@Serializable
data class ConfigMigrationResult(
    val success: Boolean,
    val count: Int,
    val message: String
)

@Serializable
data class ConfigSchemaStatusResponse(
    val configKind: String,
    val entryCount: Int,
    val configVersion: Int,
    val configFields: List<com.obsidianscout.config.ScoutingField>,
    val dataKeys: List<String>,
    val unmatchedDataKeys: List<String>,
    val newConfigKeys: List<String>
)

@Serializable
data class ConfigRevisionDTO(
    val id: String,
    val teamNumber: Int,
    val program: String,
    val configKind: String,
    val version: Int,
    val title: String,
    val changeSummary: String,
    val savedByUsername: String,
    val createdAt: String,
    val fieldCount: Int
)

@Serializable
data class ConfigRevisionDetailDTO(
    val id: String,
    val teamNumber: Int,
    val program: String,
    val configKind: String,
    val version: Int,
    val title: String,
    val configJson: String,
    val changeSummary: String,
    val savedByUsername: String,
    val createdAt: String
)

@Serializable
data class TeamMatchEntryBreakdown(
    val teamNumber: Int,
    val teamKey: String,
    val scoutedScore: Double,
    val entryId: String,
    val scouterName: String? = null,
    val hasDiscrepancy: Boolean = false
)

@Serializable
data class AllianceValidationRecord(
    val allianceColor: String,
    val teams: List<Int>,
    val scoutedTeams: List<Int>,
    val missingTeams: List<Int>,
    val isFullyScouted: Boolean,
    val actualScore: Double?,
    val scoutedScoreSum: Double,
    val scoreDiff: Double?,
    val isAnomaly: Boolean,
    val warning: String? = null,
    val teamBreakdowns: List<TeamMatchEntryBreakdown> = emptyList()
)

@Serializable
data class MatchValidationRecord(
    val matchKey: String,
    val eventKey: String,
    val compLevel: String,
    val setNumber: Int?,
    val matchNumber: Int?,
    val label: String,
    val scheduledTime: Long?,
    val actualTime: Long?,
    val redAlliance: AllianceValidationRecord,
    val blueAlliance: AllianceValidationRecord,
    val isFullyScouted: Boolean,
    val hasAnomaly: Boolean,
    val matchWarning: String? = null
)

@Serializable
data class TeamValidationRecord(
    val teamNumber: Int,
    val teamKey: String,
    val nickname: String?,
    val scoutedMatchCount: Int,
    val averageScoutedScore: Double?,
    val epa: Double?,
    val opr: Double?,
    val epaDiff: Double?,
    val oprDiff: Double?,
    val isAnomaly: Boolean,
    val anomalyReason: String? = null,
    val hasDiscrepancy: Boolean = false
)

@Serializable
data class ValidationSummaryResponse(
    val eventKey: String,
    val totalMatches: Int,
    val fullyScoutedMatches: Int,
    val incompleteMatches: Int,
    val unscoutedMatches: Int,
    val matchesWithAnomalies: Int,
    val teamsAnalyzed: Int,
    val teamsWithAnomalies: Int,
    val useStatboticsEpa: Boolean,
    val useTbaOpr: Boolean,
    val threshold: Double,
    val matches: List<MatchValidationRecord>,
    val teams: List<TeamValidationRecord>
)

@Serializable
data class UserSessionsResponse(
    val sessions: List<com.obsidianscout.auth.UserSessionInfo>
)

@Serializable
data class RevokeSessionResponse(
    val success: Boolean = true,
    val message: String,
    val revokedCount: Int? = null
)

@Serializable
data class ClearEventCacheRequest(
    val eventKey: String
)

@Serializable
data class ClearOldEventCachesRequest(
    val olderThanYear: Int
)

@Serializable
data class DeleteEventScoutingDataRequest(
    val eventKey: String,
    val teamNumber: Int? = null,
    val program: String = "FRC",
    val confirmText: String = ""
)

@Serializable
data class DeleteTeamDataRequest(
    val teamNumber: Int,
    val program: String = "FRC",
    val confirmText: String = ""
)

@Serializable
data class PruneConfigRevisionsRequest(
    val keepLatestPerKind: Int = 10
)

@Serializable
data class PruneChatMessagesRequest(
    val olderThanDays: Int = 90
)










