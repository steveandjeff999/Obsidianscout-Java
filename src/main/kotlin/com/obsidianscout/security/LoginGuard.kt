package com.obsidianscout.security

import com.obsidianscout.auth.clientIp
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.select

/**
 * The checks around every password sign-in (web, app and the shared limiter):
 * - before: a proof-of-work challenge when the IP or account looks hostile or credential stuffing is under way, and
 *   refusal of password sign-in for a protected account;
 * - on failure: spray and stuffing detection, account protection, and growing delays for the targeted account;
 * - on success: remembers which accounts sign in from the IP, which is how shared networks are recognized.
 *
 * Per-account state is keyed by [AccountProtection.accountKey] (username, team and program), since the same username
 * exists on many teams.
 */
object LoginGuard {
    private const val SPRAY_WINDOW_MS = 10 * 60_000L
    private const val SPRAY_DISTINCT = 5
    private const val STUFFING_WINDOW_MS = 5 * 60_000L
    private const val STUFFING_FAILURES = 50
    private const val STUFFING_FLAG = "stuffing"
    private const val STUFFING_FLAG_MS = 15 * 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DELAY_WINDOW_MS = 15 * 60_000L
    private const val MAX_DELAY_MS = 8_000L

    /** Tests turn the failure delay off so they don't sleep. */
    @Volatile var delaysEnabled: Boolean = true

    val credentialStuffingActive: Boolean get() = SecurityStore.hasFlag(STUFFING_FLAG)

    /** Throws [SecurityDeniedException] when this password sign-in must not go ahead. */
    fun check(call: ApplicationCall, username: String, teamNumber: Int, program: String) {
        if (!SecuritySettings.enabled) return
        val ip = call.clientIp()
        check(ip, username, teamNumber, program, call.request.headers[ProofOfWork.HEADER])
    }

    internal fun check(ip: String, username: String, teamNumber: Int, program: String, challengeAnswer: String?) {
        if (!SecuritySettings.enforcing) return
        if (com.obsidianscout.auth.isTrustedProxyAddress(ip)) return
        val account = if (username.isBlank()) "" else AccountProtection.accountKey(username, teamNumber, program)
        val needsChallenge = ThreatEngine.ipLevel(ip) >= ThreatLevel.CHALLENGE ||
            (account.isNotEmpty() && ThreatEngine.userLevel(account) >= ThreatLevel.CHALLENGE) ||
            credentialStuffingActive
        if (needsChallenge) {
            when (val result = ProofOfWork.verify(challengeAnswer, ip)) {
                ProofOfWork.Result.OK -> Unit
                else -> {
                    if (result != ProofOfWork.Result.MISSING) {
                        SecurityEvents.emit(SecurityEvent(SecurityEventType.CHALLENGE_FAILED, ip, username = username.takeIf { it.isNotBlank() },
                            details = "Sign-in challenge answer rejected: $result"))
                    }
                    throw SecurityDeniedException(
                        HttpStatusCode.PreconditionRequired,
                        SecurityDenialResponse(
                            error = "Please wait while your device completes a quick security check, then try again.",
                            errorCode = "CHALLENGE_REQUIRED",
                            code = "challenge_required",
                            challenge = ProofOfWork.issue(ip)
                        )
                    )
                }
            }
        }
        if (account.isNotEmpty() && AccountProtection.isProtected(account)) {
            throw SecurityDeniedException(
                HttpStatusCode.Forbidden,
                SecurityDenialResponse(
                    error = "This account is temporarily protected after many failed sign-in attempts. " +
                        "Sign in with a passkey or reset your password to continue now, or try again within an hour.",
                    errorCode = "ACCOUNT_PROTECTED",
                    code = "account_protected"
                )
            )
        }
    }

    /**
     * Records a failed password sign-in and waits a little before the caller answers, longer with each failure
     * against the same account (1, 2, 4, then 8 seconds at most, from the fourth failure in 15 minutes).
     */
    suspend fun onFailure(call: ApplicationCall, username: String, teamNumber: Int, program: String) {
        val ip = call.clientIp()
        val unknownUser = !userExists(username, teamNumber, program)
        val account = username.takeIf { it.isNotBlank() }?.let { AccountProtection.accountKey(it, teamNumber, program) }
        com.obsidianscout.auth.LoginRateLimiter.recordFailedAttempt(
            ip, username, unknownUser = unknownUser, teamNumber = teamNumber, account = account.takeIf { !unknownUser }
        )
        val wait = afterFailure(ip, username, teamNumber, program)
        if (wait > 0 && delaysEnabled) delay(wait)
    }

    /** The detection half of [onFailure]; returns how long to delay the answer. */
    internal fun afterFailure(ip: String, username: String, teamNumber: Int, program: String, now: Long = System.currentTimeMillis()): Long {
        if (!SecuritySettings.enabled || com.obsidianscout.auth.isTrustedProxyAddress(ip)) return 0
        val user = if (username.isBlank()) "" else AccountProtection.accountKey(username, teamNumber, program)

        // One address trying many accounts, or one account tried from many addresses.
        if (user.isNotEmpty()) {
            val accountsFromIp = SecurityStore.addDistinct("fails-users:$ip", user, SPRAY_WINDOW_MS, now)
            if (accountsFromIp >= SPRAY_DISTINCT && SecurityStore.claimOnce("spray-ip:$ip", SPRAY_WINDOW_MS, now)) {
                SecurityEvents.emit(SecurityEvent(SecurityEventType.PASSWORD_SPRAY, ip,
                    details = "$accountsFromIp different accounts failed from this address in 10 minutes"))
            }
            val ipsForUser = SecurityStore.addDistinct("fails-ips:$user", ip, SPRAY_WINDOW_MS, now)
            if (ipsForUser >= SPRAY_DISTINCT && SecurityStore.claimOnce("spray-user:$user", SPRAY_WINDOW_MS, now)) {
                SecurityEvents.emit(SecurityEvent(SecurityEventType.PASSWORD_SPRAY, ip, username = username, teamNumber = teamNumber,
                    details = "Account failed from $ipsForUser different addresses in 10 minutes", account = user))
                AccountProtection.protect(user, username, "targeted from $ipsForUser addresses", ip, teamNumber)
            }
            val hourly = SecurityStore.increment("fail1h:user:$user", HOUR_MS, now)
            if (hourly > AccountProtection.FAILURES_PER_HOUR) {
                AccountProtection.protect(user, username, "$hourly failed sign-ins in an hour", ip, teamNumber)
            }
        }

        // Many failures across all addresses: distributed credential stuffing. Everyone gets the challenge for a while.
        val global = SecurityStore.increment("fails-global", STUFFING_WINDOW_MS, now)
        if (global > STUFFING_FAILURES) {
            SecurityStore.setFlag(STUFFING_FLAG, STUFFING_FLAG_MS, now)
            if (SecurityStore.claimOnce("stuffing-report", STUFFING_FLAG_MS, now)) {
                SecurityEvents.emit(SecurityEvent(SecurityEventType.CREDENTIAL_STUFFING, ip,
                    details = "$global failed sign-ins across all addresses in 5 minutes. " +
                        (if (SecuritySettings.enforcing) "Every sign-in needs a security check for 15 minutes." else "Observe mode, no challenge.")))
            }
        }

        if (user.isEmpty()) return 0
        val recent = SecurityStore.increment("delay:$user", DELAY_WINDOW_MS, now, sliding = true)
        return if (recent <= 3) 0 else minOf(MAX_DELAY_MS, 1000L shl (recent - 4).coerceAtMost(4))
    }

    fun onSuccess(call: ApplicationCall, userId: String, username: String, teamNumber: Int, program: String) =
        onSuccess(call.clientIp(), userId, username, teamNumber, program)

    internal fun onSuccess(ip: String, userId: String, username: String, teamNumber: Int, program: String, now: Long = System.currentTimeMillis()) {
        if (!SecuritySettings.enabled) return
        SecurityStore.addDistinct("okusers:$ip", userId, HOUR_MS, now)
        SecurityStore.resetCount("delay:${AccountProtection.accountKey(username, teamNumber, program)}")
    }

    /** A passkey sign-in proves the account holder: clear its protection. */
    fun onPasskeySuccess(call: ApplicationCall, userId: String, username: String, teamNumber: Int, program: String) {
        onSuccess(call, userId, username, teamNumber, program)
        AccountProtection.clear(username, teamNumber, program, "passkey sign-in", call.clientIp())
    }

    private fun userExists(username: String, teamNumber: Int, program: String): Boolean {
        if (!DatabaseFactory.isReady) return true
        return runCatching {
            readTransaction {
                Users.select(Users.id).where {
                    (Users.username eq username.trim()) and (Users.teamNumber eq teamNumber) and
                        (Users.program.lowerCase() eq program.trim().lowercase())
                }.limit(1).any()
            }
        }.getOrDefault(true)
    }
}
