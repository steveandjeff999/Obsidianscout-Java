package com.obsidianscout.security

import kotlinx.serialization.Serializable

enum class Severity { INFO, LOW, MEDIUM, HIGH }

/**
 * Everything the intrusion detection system records. [weight] is what one event adds to the threat score of the IP
 * (and of the account, when [userScoped]); zero-weight events are records only.
 */
enum class SecurityEventType(val weight: Int, val severity: Severity, val userScoped: Boolean = false) {
    AUTH_FAILURE(5, Severity.LOW, userScoped = true),
    AUTH_FAILURE_UNKNOWN_USER(8, Severity.LOW),
    PASSWORD_SPRAY(40, Severity.HIGH, userScoped = true),
    CREDENTIAL_STUFFING(0, Severity.HIGH),
    RATE_LIMITED(5, Severity.LOW),
    ROUTE_SCAN(15, Severity.MEDIUM),
    SCANNER_PATH(25, Severity.MEDIUM),
    INJECTION_PROBE(30, Severity.MEDIUM),
    CSRF_FAILURE(10, Severity.LOW),
    FORBIDDEN_ROLE(2, Severity.LOW),
    CROSS_TEAM_ACCESS(40, Severity.HIGH, userScoped = true),
    CLUSTER_SIG_INVALID(50, Severity.HIGH),
    SESSION_ANOMALY(30, Severity.HIGH, userScoped = true),
    TOKEN_REUSE_REVOKED(40, Severity.HIGH, userScoped = true),
    SCRAPING(30, Severity.MEDIUM),
    CHALLENGE_FAILED(10, Severity.LOW),
    CHALLENGE_REQUIRED(0, Severity.INFO),
    ACCOUNT_PROTECTED(0, Severity.MEDIUM),
    ACCOUNT_PROTECTION_CLEARED(0, Severity.INFO),
    BLOCK_APPLIED(0, Severity.MEDIUM),
    /** Observe mode: what enforce mode would have blocked. */
    WOULD_BLOCK(0, Severity.MEDIUM),
    /** The score called for a block, but the IP is a shared network or allowlisted, so it was only reported. */
    BLOCK_SKIPPED(0, Severity.MEDIUM),
    BLOCK_LIFTED(0, Severity.INFO),
    UNBLOCK_REQUESTED(0, Severity.INFO),
    UNBLOCK_APPROVED(0, Severity.INFO),
    UNBLOCK_DENIED(0, Severity.INFO),
    SESSIONS_REVOKED(0, Severity.MEDIUM),
    ADMIN_ACTION(0, Severity.INFO);

    /** Sent to superadmins straight away (subject to the alert cooldown). */
    val alerts: Boolean
        get() = severity == Severity.HIGH || this == BLOCK_APPLIED || this == WOULD_BLOCK ||
            this == BLOCK_SKIPPED || this == UNBLOCK_REQUESTED || this == SESSIONS_REVOKED
}

data class SecurityEvent(
    val type: SecurityEventType,
    val ip: String,
    val userId: String? = null,
    val username: String? = null,
    val teamNumber: Int? = null,
    val method: String = "",
    val path: String = "",
    val userAgent: String? = null,
    val details: String = "",
    val role: String? = null,
    val at: Long = System.currentTimeMillis(),
    /** The account the event is about ([AccountProtection.accountKey]); only events that carry one score an account. */
    val account: String? = null
)

enum class ThreatLevel { NORMAL, WATCH, CHALLENGE, BLOCK }

// ---------------------------------------------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------------------------------------------

@Serializable
data class SecurityEventDto(
    val id: String,
    val at: Long,
    val type: String,
    val severity: String,
    val ip: String,
    val userId: String? = null,
    val username: String? = null,
    val teamNumber: Int? = null,
    val method: String = "",
    val path: String = "",
    val userAgent: String? = null,
    val details: String = "",
    val node: String = ""
)

@Serializable
data class SecurityEventPage(val events: List<SecurityEventDto>, val total: Long, val offset: Int, val limit: Int)

@Serializable
data class IpBlockDto(
    val id: String,
    val ipOrCidr: String,
    val reason: String,
    val triggeringEventTypes: List<String> = emptyList(),
    val createdAt: Long,
    val expiresAt: Long,
    val createdBy: String,
    val strike: Int,
    val hits: Long,
    val active: Boolean,
    val liftedAt: Long? = null,
    val liftedBy: String? = null,
    val liftReason: String? = null,
    val pendingRequestId: String? = null,
    /** Distinct accounts that signed in from this IP in the last hour: more than a couple means a shared network. */
    val recentDistinctUsers: Int = 0
)

@Serializable
data class AllowlistEntryDto(
    val id: String,
    val ipOrCidr: String,
    val note: String,
    val createdAt: Long,
    val createdBy: String,
    val expiresAt: Long? = null,
    /** True for entries from app-config.json, which can't be removed from the UI. */
    val fromConfig: Boolean = false
)

@Serializable
data class UnblockRequestDto(
    val id: String,
    val blockId: String,
    val ip: String,
    val name: String,
    val teamNumber: Int? = null,
    val email: String? = null,
    val message: String,
    val status: String,
    val highRisk: Boolean,
    val createdAt: Long,
    val reviewedBy: String? = null,
    val reviewedAt: Long? = null,
    val reviewNote: String? = null,
    val blockReason: String? = null,
    val blockTriggeringEventTypes: List<String> = emptyList(),
    val blockExpiresAt: Long? = null,
    val recentDistinctUsers: Int = 0
)

@Serializable
data class ProtectedAccountDto(
    /** The account key ("username|team|program") to send back when clearing protection. */
    val account: String,
    val username: String,
    val teamNumber: Int? = null,
    val program: String? = null,
    val protectedUntil: Long
)

@Serializable
data class ThreatOffenderDto(val ip: String, val score: Double, val level: String, val lastEventAt: Long, val lastEventType: String)

@Serializable
data class SecurityTimelineBucket(val hourStart: Long, val low: Int = 0, val medium: Int = 0, val high: Int = 0, val info: Int = 0)

@Serializable
data class SecuritySettingsDto(val mode: String = "observe", val autoRevokeSessions: Boolean = false)

@Serializable
data class SecuritySummaryDto(
    val enabled: Boolean,
    val mode: String,
    val autoRevokeSessions: Boolean,
    val maxBlockMinutes: Int,
    val activeBlocks: Int,
    val pendingUnblockRequests: Int,
    val protectedAccounts: Int,
    val credentialStuffingActive: Boolean,
    val eventsLast24h: Map<String, Int>,
    val timeline: List<SecurityTimelineBucket>,
    val topOffenders: List<ThreatOffenderDto>,
    val droppedEvents: Long
)

@Serializable
data class PowChallengeDto(val token: String, val nonce: String, val difficulty: Int, val expiresAt: Long)

/** Body of every refusal the security layer sends, readable by both the web client and the app. */
@Serializable
data class SecurityDenialResponse(
    val success: Boolean = false,
    val error: String,
    val errorCode: String,
    val code: String,
    val blockId: String? = null,
    val expiresAt: Long? = null,
    val retryAfterSeconds: Long? = null,
    val unblockRequestsEnabled: Boolean? = null,
    val challenge: PowChallengeDto? = null
)

@Serializable
data class BlockStatusDto(
    val blocked: Boolean,
    val ip: String,
    val blockId: String? = null,
    val expiresAt: Long? = null,
    val retryAfterSeconds: Long? = null,
    val unblockRequestsEnabled: Boolean = true,
    val pendingRequestId: String? = null,
    val pendingRequestStatus: String? = null
)

@Serializable
data class UnblockRequestSubmission(
    val name: String = "",
    val teamNumber: Int? = null,
    val email: String? = null,
    val message: String = "",
    /** "<token>:<answer>" from a solved proof-of-work challenge. */
    val challenge: String = ""
)

@Serializable
data class UnblockRequestStatusDto(val id: String, val status: String, val reviewNote: String? = null)

@Serializable
data class LiftBlockRequest(
    val reason: String = "",
    val resetStrikes: Boolean = true,
    /** none | 24h | 7d | permanent */
    val allowlistFor: String = "none"
)

@Serializable
data class ManualBlockRequest(val ipOrCidr: String, val minutes: Int = 60, val reason: String = "")

@Serializable
data class ReasonRequest(val reason: String = "")

@Serializable
data class ReviewUnblockRequest(val note: String = "", val resetStrikes: Boolean = true, val allowlistFor: String = "none")

@Serializable
data class AllowlistAddRequest(val ipOrCidr: String, val note: String = "", val expiresInHours: Int? = null)

@Serializable
data class ClearProtectionRequest(
    /** The account key from [ProtectedAccountDto.account]. */
    val account: String
)

@Serializable
data class SimpleOk(val success: Boolean = true, val count: Int? = null, val message: String? = null)

/** Thrown by the security layer to refuse a request; StatusPages turns it into a [SecurityDenialResponse]. */
class SecurityDeniedException(val status: io.ktor.http.HttpStatusCode, val body: SecurityDenialResponse) :
    RuntimeException(body.error)
