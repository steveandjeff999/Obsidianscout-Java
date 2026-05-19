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
    val password: String
)

@Serializable
data class RegisterRequest(
    val username: String,
    val teamNumber: Int,
    val password: String,
    val role: UserRole = UserRole.SCOUT
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
    val role: UserRole = UserRole.SCOUT
)

@Serializable
data class ScoutingEntryRequest(
    val data: JsonObject
)

@Serializable
data class ApiKeysPayload(
    val tbaKey: String = "",
    val firstUsername: String = "",
    val firstKey: String = "",
    val statboticsKey: String = ""
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
    val eventKey: String = ""
)

@Serializable
data class SyncStatusResponse(
    val intervalMinutes: Double = 7.5,
    val lastSyncAt: String? = null,
    val lastSyncSummary: String? = null,
    val lastSyncError: String? = null
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
    val epa: Double? = null
)

@Serializable
data class MatchRecord(
    val matchKey: String,
    val eventKey: String,
    val compLevel: String,
    val setNumber: Int? = null,
    val matchNumber: Int? = null,
    val scheduledTime: Long? = null,
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
