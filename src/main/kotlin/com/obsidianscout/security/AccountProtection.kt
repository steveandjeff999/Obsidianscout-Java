package com.obsidianscout.security

/**
 * An account under a password spray, or with more than 30 failed sign-ins in an hour, needs a passkey or a password
 * reset for its next sign-in. The account is never locked outright, since that would let an attacker lock a real
 * user out.
 *
 * **Protection is never permanent.** It ends on its own after [DURATION_MS] (one hour, like IP blocks) and is never
 * extended while it lasts, however many failures keep arriving. Once it ends it can't be put back for [COOLDOWN_MS],
 * so an attacker who keeps failing can't chain protections into a lockout: in that gap password sign-in is open
 * again, still behind the rate limit, the growing delays and the security check. A passkey sign-in, a password reset
 * or a superadmin lifts it sooner.
 *
 * Usernames are only unique within a team and program, so everything here is keyed by [accountKey], never by the
 * bare username: failures against team 254's "admin" must not touch team 1678's "admin".
 */
object AccountProtection {
    private const val PREFIX = "protected:"
    /** How long protection lasts. Fixed from when it starts; not configurable. */
    const val DURATION_MS = 60 * 60_000L
    /** After protection ends, how long before the account can be protected again. */
    const val COOLDOWN_MS = 60 * 60_000L
    const val FAILURES_PER_HOUR = 30

    /** Identifies one account: lowercase username, team number and uppercase program, e.g. "admin|254|FRC". */
    fun accountKey(username: String, teamNumber: Int, program: String): String =
        "${username.trim().lowercase()}|$teamNumber|${program.trim().uppercase().ifEmpty { "FRC" }}"

    /** Splits an [accountKey] back into username, team and program; null if it isn't one. */
    fun parseAccountKey(account: String): Triple<String, Int, String>? {
        val programSep = account.lastIndexOf('|')
        if (programSep <= 0) return null
        val teamSep = account.lastIndexOf('|', programSep - 1)
        if (teamSep <= 0) return null
        val team = account.substring(teamSep + 1, programSep).toIntOrNull() ?: return null
        return Triple(account.substring(0, teamSep), team, account.substring(programSep + 1))
    }

    private fun flagKey(account: String) = PREFIX + account

    fun isProtected(account: String, now: Long = System.currentTimeMillis()): Boolean =
        account.isNotBlank() && SecurityStore.hasFlag(flagKey(account), now)

    /** Turns protection on (enforce mode only; observe mode just records that it would have). */
    fun protect(account: String, username: String, reason: String, ip: String, teamNumber: Int? = null, now: Long = System.currentTimeMillis()) {
        if (account.isBlank()) return
        // Never extend a protection that's already running.
        if (isProtected(account, now)) return
        // One protection per account per duration + cooldown, so protections can't be chained back to back.
        if (!SecurityStore.claimOnce("protect:$account", DURATION_MS + COOLDOWN_MS, now)) return
        if (SecuritySettings.enforcing) {
            SecurityStore.setFlag(flagKey(account), DURATION_MS, now)
            SecurityEvents.emit(SecurityEvent(SecurityEventType.ACCOUNT_PROTECTED, ip, username = username, teamNumber = teamNumber,
                details = "Password sign-in needs a passkey or password reset for up to ${DURATION_MS / 60_000} min: $reason"))
        } else {
            SecurityEvents.emit(SecurityEvent(SecurityEventType.ACCOUNT_PROTECTED, ip, username = username, teamNumber = teamNumber,
                details = "Observe mode, not protected. Would require a passkey or reset: $reason"))
        }
    }

    fun clear(account: String, by: String, ip: String = "system") {
        if (account.isBlank()) return
        val was = isProtected(account)
        SecurityStore.clearFlag(flagKey(account))
        SecurityStore.resetScore(ThreatEngine.userKey(account))
        SecurityStore.resetCount("fail1h:user:$account")
        if (was) {
            val parsed = parseAccountKey(account)
            SecurityEvents.emit(SecurityEvent(SecurityEventType.ACCOUNT_PROTECTION_CLEARED, ip,
                username = parsed?.first ?: account, teamNumber = parsed?.second, details = "Cleared by $by"))
        }
    }

    fun clear(username: String, teamNumber: Int, program: String, by: String, ip: String = "system") =
        clear(accountKey(username, teamNumber, program), by, ip)

    fun list(): List<ProtectedAccountDto> {
        val merged = HashMap<String, Long>()
        SecurityStore.localFlags(PREFIX).forEach { (k, v) -> merged[k] = maxOf(merged[k] ?: 0L, v) }
        SecurityStore.sharedFlags(PREFIX).forEach { (k, v) -> merged[k] = maxOf(merged[k] ?: 0L, v) }
        return merged.entries.sortedByDescending { it.value }.map { (account, until) ->
            val parsed = parseAccountKey(account)
            ProtectedAccountDto(
                account = account,
                username = parsed?.first ?: account,
                teamNumber = parsed?.second,
                program = parsed?.third,
                protectedUntil = until
            )
        }
    }
}
