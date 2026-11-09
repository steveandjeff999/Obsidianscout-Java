package com.obsidianscout.security

import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.clientIp
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.userAgent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Signs that a session cookie is in the wrong hands:
 * - the same session suddenly used from a different kind of client or operating system (Windows browser, then an
 *   Android one) - IP changes are ignored, since phones switch between venue Wi-Fi and cellular all the time;
 * - a session that was revoked because it was thought compromised being presented again.
 *
 * Automatic revocation is off until a superadmin turns it on (it only runs in enforce mode); until then these are
 * alerts.
 */
object SessionWatch {
    private const val REVOKED_TTL_MS = 30L * 24 * 3_600_000L
    private const val MAX_TRACKED = 100_000

    private data class Seen(val uaHash: Int, val fingerprint: ClientFingerprint)
    private val seen = ConcurrentHashMap<String, Seen>()

    data class ClientFingerprint(val kind: String, val os: String) {
        /** Unknown parts match anything; only two known, different values count as a change. */
        fun conflictsWith(other: ClientFingerprint): Boolean =
            (kind != "unknown" && other.kind != "unknown" && kind != other.kind) ||
                (os != "unknown" && other.os != "unknown" && os != other.os)
    }

    fun fingerprint(userAgent: String?, mobileApp: Boolean = false): ClientFingerprint {
        val ua = userAgent.orEmpty().lowercase()
        val isApp = mobileApp || ua.startsWith("dart/") || ua.contains("cronet") || ua.contains("okhttp") ||
            ua.contains("cfnetwork") || ua.contains("obsidianscout")
        val kind = when {
            ua.isBlank() && !mobileApp -> "unknown"
            isApp -> "app"
            else -> "browser"
        }
        val os = when {
            ua.contains("android") -> "android"
            ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios") || ua.contains("cfnetwork") -> "ios"
            ua.contains("windows") -> "windows"
            ua.contains("macintosh") || ua.contains("mac os x") -> "macos"
            ua.contains("cros") -> "chromeos"
            ua.contains("linux") || ua.contains("x11") -> "linux"
            else -> "unknown"
        }
        return ClientFingerprint(kind, os)
    }

    /** Last time each (ip, user) pair was counted toward [ThreatEngine.isSharedNetwork], so it costs one write per pair per [SIGNED_IN_NOTE_MS]. */
    private val signedInNoted = ConcurrentHashMap<String, Long>()
    private const val SIGNED_IN_NOTE_MS = 10 * 60_000L

    /**
     * Counts a signed-in user as active on [ip]. Without this only fresh password sign-ins count, and at an event most
     * people are already signed in, so a venue network would look like one client and could be blocked.
     */
    internal fun noteSignedInUser(ip: String, userId: String, now: Long = System.currentTimeMillis()) {
        if (ip.isBlank() || ip == "unknown") return
        val key = "$ip|$userId"
        val last = signedInNoted[key]
        if (last != null && now - last < SIGNED_IN_NOTE_MS) return
        if (signedInNoted.size > MAX_TRACKED) signedInNoted.entries.removeIf { now - it.value >= SIGNED_IN_NOTE_MS }
        signedInNoted[key] = now
        SecurityStore.addDistinct("okusers:$ip", userId, 3_600_000L, now)
    }

    /** Called for every authenticated request; cheap unless the user agent string changed. */
    fun observe(call: ApplicationCall, session: UserSession) {
        if (!SecuritySettings.enabled || session.publicView) return
        runCatching { noteSignedInUser(call.clientIp(), session.userId) }
        val sessionId = session.sessionId ?: return
        val ua = runCatching { call.request.userAgent() }.getOrNull() ?: return
        val mobile = call.request.headers["X-Mobile-App"] != null
        val hash = ua.hashCode() * 31 + (if (mobile) 1 else 0)
        val previous = seen[sessionId]
        if (previous != null && previous.uaHash == hash) return
        val current = fingerprint(ua, mobile)
        if (previous == null) {
            if (seen.size > MAX_TRACKED) seen.clear()
            seen[sessionId] = Seen(hash, current)
            return
        }
        // Keep the first fingerprint as the reference, so flipping back and forth keeps being noticed.
        if (!previous.fingerprint.conflictsWith(current)) {
            seen[sessionId] = Seen(hash, previous.fingerprint)
            return
        }
        if (!SecurityStore.claimOnce("anomaly:$sessionId", 60 * 60_000L)) return
        val ip = call.clientIp()
        SecurityEvents.emit(
            call, SecurityEventType.SESSION_ANOMALY,
            details = "Session used from ${current.kind}/${current.os} after ${previous.fingerprint.kind}/${previous.fingerprint.os}",
            userId = session.userId, username = session.username, teamNumber = session.teamNumber, role = session.role.name, ip = ip,
            account = AccountProtection.accountKey(session.username, session.teamNumber, session.program)
        )
        if (SecuritySettings.enforcing && SecuritySettings.autoRevokeSessions) {
            revokeForCompromise(session.userId, "Session anomaly: client changed mid-session", "system", ip)
        }
    }

    /**
     * Signs the user out everywhere because their session looks stolen, remembers the revoked sessions so their reuse
     * raises an alert, and emails the user.
     */
    fun revokeForCompromise(userId: String, reason: String, by: String, ip: String): Int {
        val uuid = runCatching { UUID.fromString(userId) }.getOrNull() ?: return 0
        val sessionIds = AuthService.listSessionIds(uuid)
        sessionIds.forEach { SecurityStore.setFlag("revoked:$it", REVOKED_TTL_MS) }
        val count = AuthService.revokeAllSessions(uuid)
        val user = runCatching { AuthService.getUserById(userId) }.getOrNull()
        SecurityEvents.emit(
            SecurityEvent(SecurityEventType.SESSIONS_REVOKED, ip, userId = userId, username = user?.username, teamNumber = user?.teamNumber,
                details = "$count session(s) signed out by $by: $reason")
        )
        user?.email?.takeIf { it.isNotBlank() }?.let { email ->
            SecurityAlerter.emailUser(
                email,
                "ObsidianScout: you were signed out for your security",
                "We signed your ObsidianScout account (${user.username}, team ${user.teamNumber}) out on every device because " +
                    "of unusual activity: $reason. Sign in again; if you didn't expect this, reset your password."
            )
        }
        return count
    }

    /** Called when a session cookie points at a session that no longer exists. */
    fun onMissingSession(call: ApplicationCall, session: UserSession) {
        val sessionId = session.sessionId ?: return
        if (!SecurityStore.hasFlag("revoked:$sessionId")) return
        if (!SecurityStore.claimOnce("reuse:$sessionId", 10 * 60_000L)) return
        SecurityEvents.emit(
            call, SecurityEventType.TOKEN_REUSE_REVOKED,
            details = "A session revoked as compromised was presented again",
            userId = session.userId, username = session.username, teamNumber = session.teamNumber, role = session.role.name,
            account = AccountProtection.accountKey(session.username, session.teamNumber, session.program)
        )
    }

    /** Tests only. */
    internal fun clearForTests() {
        seen.clear()
        signedInNoted.clear()
    }
}
