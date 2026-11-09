package com.obsidianscout.security

import com.obsidianscout.auth.isTrustedProxyAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Turns events into decaying threat scores per IP and per account, and acts when a score crosses a threshold:
 * blocks the IP in enforce mode, reports what it would have blocked in observe mode, and never blocks a shared network
 * (several accounts signed in from it recently) or an allowlisted one.
 */
object ThreatEngine {
    private const val SHARED_NETWORK_USERS = 3
    private const val HOUR_MS = 3_600_000L
    private const val REPORT_ONCE_MS = 30 * 60_000L

    /** The last few event types per IP, for the block reason. */
    private val recentTypes = ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>()

    fun ipKey(ip: String) = "ip:$ip"
    /** Score key for one account; [account] is an [AccountProtection.accountKey], never a bare username. */
    fun userKey(account: String) = "user:$account"

    fun level(score: Double): ThreatLevel {
        val c = SecuritySettings.config
        return when {
            score >= c.block_threshold -> ThreatLevel.BLOCK
            score >= c.challenge_threshold -> ThreatLevel.CHALLENGE
            score >= c.watch_threshold -> ThreatLevel.WATCH
            else -> ThreatLevel.NORMAL
        }
    }

    fun ipScore(ip: String): Double = SecurityStore.score(ipKey(ip))
    fun userScore(account: String): Double = SecurityStore.score(userKey(account))
    fun ipLevel(ip: String): ThreatLevel = level(ipScore(ip))
    fun userLevel(account: String): ThreatLevel = level(userScore(account))

    /** Several different accounts signed in from [ip] in the last hour: a school, venue or team network. */
    fun isSharedNetwork(ip: String): Boolean =
        SecurityStore.distinctCount("okusers:$ip", HOUR_MS) >= SHARED_NETWORK_USERS

    fun onEvent(event: SecurityEvent) {
        val weight = event.type.weight
        if (weight <= 0) return
        if (event.ip == "unknown" || isTrustedProxyAddress(event.ip)) return
        // A real search engine crawler hitting missing pages isn't an attack: never score it.
        VerifiedCrawlers.observe(event.ip, event.userAgent, event.at)
        if (VerifiedCrawlers.isVerified(event.ip)) return
        SecurityStore.halfLifeMs = SecuritySettings.config.score_half_life_minutes.coerceAtLeast(1) * 60_000L

        val types = recentTypes.computeIfAbsent(event.ip) { ConcurrentLinkedDeque() }
        types.addFirst(event.type.name)
        while (types.size > 8) types.pollLast()
        if (recentTypes.size > 50_000) recentTypes.clear()

        val ipScore = SecurityStore.addScore(ipKey(event.ip), weight.toDouble(), event.at)
        if (level(ipScore) == ThreatLevel.BLOCK) onBlockLevel(event.ip, ipScore)

        val account = event.account
        if (event.type.userScoped && !account.isNullOrBlank()) {
            SecurityStore.addScore(userKey(account), weight.toDouble(), event.at)
        }
    }

    private fun onBlockLevel(ip: String, score: Double) {
        val types = recentTypes[ip]?.toList()?.distinct() ?: emptyList()
        val reason = "Threat score ${score.toInt()} from ${types.joinToString(", ").ifBlank { "suspicious activity" }}"
        when {
            BlockList.match(ip, countHit = false) != null -> Unit
            // Claims to be a crawler and the reverse DNS check is still running: decide once it's answered.
            VerifiedCrawlers.isPending(ip) || VerifiedCrawlers.isVerified(ip) -> Unit
            CloudflareNetwork.contains(ip) -> {
                if (SecurityStore.claimOnce("skipblock:$ip", REPORT_ONCE_MS)) {
                    SecurityEvents.emit(SecurityEvent(SecurityEventType.BLOCK_SKIPPED, ip,
                        details = "Cloudflare address (shared by Cloudflare's services and every Worker), capped at challenge. $reason"))
                }
            }
            !SecuritySettings.enforcing -> {
                if (SecurityStore.claimOnce("wouldblock:$ip", REPORT_ONCE_MS)) {
                    SecurityEvents.emit(SecurityEvent(SecurityEventType.WOULD_BLOCK, ip, details = "Observe mode, not blocked. $reason"))
                }
            }
            Allowlist.isAllowed(ip) -> {
                if (SecurityStore.claimOnce("skipblock:$ip", REPORT_ONCE_MS)) {
                    SecurityEvents.emit(SecurityEvent(SecurityEventType.BLOCK_SKIPPED, ip, details = "Allowlisted, not blocked. $reason"))
                }
            }
            isSharedNetwork(ip) -> {
                if (SecurityStore.claimOnce("skipblock:$ip", REPORT_ONCE_MS)) {
                    SecurityEvents.emit(SecurityEvent(SecurityEventType.BLOCK_SKIPPED, ip,
                        details = "Shared network (several accounts signed in from it), capped at challenge. $reason"))
                }
            }
            else -> BlockList.autoBlock(ip, reason, types)
        }
    }

    /** IPs with the highest scores on this node, for Server Insights. */
    fun topOffenders(limit: Int = 10): List<Pair<String, Double>> =
        SecurityStore.topLocalScores("ip:", SecuritySettings.config.watch_threshold / 2.0, limit)

    /** Tests only. */
    internal fun clearForTests() = recentTypes.clear()
}
