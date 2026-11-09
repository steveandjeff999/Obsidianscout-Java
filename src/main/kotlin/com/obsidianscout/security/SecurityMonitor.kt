package com.obsidianscout.security

import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.clientIp
import com.obsidianscout.auth.isTrustedProxyAddress
import com.obsidianscout.routes.respondStaticHtml
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.util.AttributeKey
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Detectors that look at each finished request: scans for paths no real client asks for, floods of 404s, attack
 * strings in the URL, permission denials, rate-limit hits and scraping. Only the path and query are inspected, never
 * bodies. Requests from our own proxies and nodes, and requests a peer forwarded to us, are skipped (the node that
 * first received a request counts it).
 */
object SecurityMonitor {
    /** Set on a call whose security event was already recorded where it happened (CSRF refusal, block gate). */
    val Recorded = AttributeKey<Boolean>("SecurityEventRecorded")

    private const val ROUTE_SCAN_PER_MINUTE = 20

    private val scannerFragments = listOf(
        "/.env", "/.git", "/.svn", "/.hg/", "/.ds_store", "/.aws", "/.ssh", "/.htaccess", "/.htpasswd",
        "/wp-admin", "/wp-login", "/wp-content", "/wp-includes", "/xmlrpc.php", "/phpmyadmin", "/pma/", "/myadmin",
        "/actuator", "/cgi-bin", "/vendor/phpunit", "/server-status", "/boaform", "/hnap1", "/solr/", "/owa/",
        "/autodiscover", "/manager/html", "/jenkins", "/telescope", "/_ignition", "/setup.cgi", "/shell?", "/druid/",
        "/geoserver", "/remote/login", "/+cscoe+", "/sdk", "/evox/", "/.vscode", "/web.config", "/etc/passwd"
    )
    private val scannerSuffixes = listOf(".php", ".asp", ".aspx", ".jsp", ".cgi", ".env", ".sql", ".bak", ".ini", ".old", ".swp")

    private val injectionFragments = listOf(
        "union select", "union all select", "information_schema", "xp_cmdshell", "<script", "javascript:", "onerror=",
        "onload=", "\${jndi:", "\${env:", "../", "..\\", "\u0000", "{{7*7}}", "\${7*7}", "/etc/passwd", "<?php",
        "sleep(", "benchmark(", "pg_sleep", "waitfor delay", ";cat ", "|cat ", "\$(curl", "`curl"
    )
    private val sqlQuote = Regex("""['"]\s*(or|and)\s+['"\d(]""", RegexOption.IGNORE_CASE)
    private val sqlComment = Regex("""['"]\s*(--|#|/\*)""")

    // ----------------------------------------------------------------------------------------- scraping (local)
    private class PathWindow(@Volatile var start: Long, val paths: MutableSet<Int>)
    private val pathWindows = ConcurrentHashMap<String, PathWindow>()

    fun isScannerPath(path: String): Boolean {
        val p = path.lowercase()
        if (scannerFragments.any { p.contains(it) }) return true
        val last = p.substringAfterLast('/')
        return scannerSuffixes.any { last.endsWith(it) }
    }

    /** True when the URL (decoded once) carries a common injection or traversal payload. */
    fun hasInjectionPayload(uri: String): Boolean {
        val needsDecode = uri.contains('%') || uri.contains('+')
        val decoded = if (needsDecode) runCatching { URLDecoder.decode(uri, StandardCharsets.UTF_8) }.getOrDefault(uri) else uri
        val text = decoded.lowercase()
        if (injectionFragments.any { text.contains(it) }) return true
        if (text.contains('\'') || text.contains('"')) {
            if (sqlQuote.containsMatchIn(text) || sqlComment.containsMatchIn(text)) return true
        }
        return false
    }

    private fun skip(call: ApplicationCall, ip: String): Boolean =
        call.request.headers.contains("X-Forwarded-By-Peer") ||
            call.request.headers.contains("X-Cluster-Signature") ||
            call.request.headers[HttpHeaders.Upgrade] != null ||
            isTrustedProxyAddress(ip)

    fun onRequestFinished(call: ApplicationCall, status: Int, now: Long = System.currentTimeMillis()) {
        if (!SecuritySettings.enabled) return
        if (call.attributes.getOrNull(Recorded) == true) return
        val ip = call.clientIp()
        if (skip(call, ip)) return
        val path = call.request.path()
        if (path == "/health" || path == "/api/health") return
        // Start the reverse DNS check on a claimed crawler's first request, before it has scored anything.
        VerifiedCrawlers.observe(ip, call.request.headers[HttpHeaders.UserAgent], now)

        val uri = call.request.uri
        if (hasInjectionPayload(uri)) {
            // Once per distinct URL a minute: a retried request isn't counted twice, a stream of payloads is.
            if (SecurityStore.claimOnce("inject:$ip:${uri.hashCode()}", 60_000L, now)) {
                SecurityEvents.emit(call, SecurityEventType.INJECTION_PROBE, details = "Attack string in URL (status $status)", ip = ip)
            }
            return
        }
        // No real client asks for these, whatever the answer was (unknown pages fall back to the app shell with a 200).
        if (isScannerPath(path)) {
            if (SecurityStore.claimOnce("scanner:$ip:${path.lowercase().hashCode()}", 10 * 60_000L, now)) {
                SecurityEvents.emit(call, SecurityEventType.SCANNER_PATH, details = "Probe for $path (status $status)", ip = ip)
            }
            return
        }

        when (status) {
            HttpStatusCode.NotFound.value -> onNotFound(call, ip, path, now)
            HttpStatusCode.Forbidden.value -> onForbidden(call, ip, path, now)
            HttpStatusCode.TooManyRequests.value -> {
                if (SecurityStore.claimOnce("429:$ip", 60_000L, now)) {
                    SecurityEvents.emit(call, SecurityEventType.RATE_LIMITED, details = "Rate limit hit on $path", ip = ip)
                }
            }
            in 200..299 -> if (path.startsWith("/api/") && call.request.httpMethod.value == "GET") trackScraping(call, ip, path, now)
        }
    }

    private fun onNotFound(call: ApplicationCall, ip: String, path: String, now: Long) {
        val misses = SecurityStore.increment("404:$ip", 60_000L, now)
        if (misses > ROUTE_SCAN_PER_MINUTE && SecurityStore.claimOnce("routescan:$ip", 60_000L, now)) {
            SecurityEvents.emit(call, SecurityEventType.ROUTE_SCAN, details = "$misses missing paths in a minute, latest $path", ip = ip)
        }
    }

    private fun onForbidden(call: ApplicationCall, ip: String, path: String, now: Long) {
        if (!path.startsWith("/api/")) return
        val session = runCatching { call.sessions.get<UserSession>() }.getOrNull() ?: return
        // A page polling something its role can't see would otherwise score every few seconds.
        if (!SecurityStore.claimOnce("403:${session.sessionId ?: ip}:${path.hashCode()}", 10 * 60_000L, now)) return
        SecurityEvents.emit(
            call, SecurityEventType.FORBIDDEN_ROLE, details = "Refused ${call.request.httpMethod.value} $path",
            userId = session.userId, username = session.username, teamNumber = session.teamNumber, role = session.role.name, ip = ip
        )
    }

    private fun trackScraping(call: ApplicationCall, ip: String, path: String, now: Long) {
        val limit = SecuritySettings.config.scrape_paths_per_minute.coerceAtLeast(50)
        val sessionKey = runCatching { call.sessions.get<UserSession>()?.sessionId }.getOrNull()
        val key = sessionKey ?: ip
        if (pathWindows.size > 50_000) pathWindows.entries.removeIf { now - it.value.start > 60_000L }
        val window = pathWindows.compute(key) { _, old ->
            if (old == null || now - old.start > 60_000L) PathWindow(now, ConcurrentHashMap.newKeySet()) else old
        }!!
        if (window.paths.size > limit) return
        window.paths.add((path + "?" + (call.request.uri.substringAfter('?', ""))).hashCode())
        if (window.paths.size > limit && SecurityStore.claimOnce("scrape:$key", 10 * 60_000L, now)) {
            SecurityEvents.emit(call, SecurityEventType.SCRAPING, details = "More than $limit different API requests in a minute", ip = ip)
        }
    }

    /** Records a cross-team access attempt: a signed-in user asking for another team's data. */
    fun crossTeam(call: ApplicationCall, session: UserSession, targetTeam: Int?, resource: String) {
        if (session.publicView) return
        val ip = call.clientIp()
        if (!SecurityStore.claimOnce("crossteam:${session.userId}:${resource.hashCode()}", 10 * 60_000L)) return
        SecurityEvents.emit(
            call, SecurityEventType.CROSS_TEAM_ACCESS,
            details = "Team ${session.teamNumber} user asked for ${targetTeam?.let { "team $it's " } ?: "another team's "}$resource",
            userId = session.userId, username = session.username, teamNumber = session.teamNumber, role = session.role.name, ip = ip,
            account = AccountProtection.accountKey(session.username, session.teamNumber, session.program)
        )
    }

    fun cleanup(now: Long = System.currentTimeMillis()) {
        pathWindows.entries.removeIf { now - it.value.start > 120_000L }
    }
}

/**
 * Refuses every request from a blocked IP, first thing in the pipeline. The health check, the unblock request
 * endpoints, the blocked page and static assets always get through, so a blocked user can always ask for help.
 */
object BlockGate {
    private val assetSuffixes = listOf(".css", ".js", ".mjs", ".png", ".ico", ".svg", ".woff", ".woff2", ".ttf", ".webmanifest", ".map")

    fun isExemptPath(path: String): Boolean =
        path == "/health" || path == "/api/health" || path == "/version" || path == "/api/version" ||
            path.startsWith("/api/security/") || path == "/blocked" || path == "/blocked.html" ||
            path.startsWith("/vendor/") || path.startsWith("/assets/") || path.startsWith("/css/") ||
            (path.startsWith("/i18n/") && path.endsWith(".json")) || path == "/manifest.json" ||
            (!path.startsWith("/models/") && !path.startsWith("/api/") && assetSuffixes.any { path.endsWith(it) })

    /** Responds and returns true when the caller is blocked. */
    suspend fun check(call: ApplicationCall): Boolean {
        if (!SecuritySettings.enabled) return false
        val path = call.request.path()
        if (isExemptPath(path)) return false
        if (call.request.headers.contains("X-Forwarded-By-Peer")) return false
        val ip = call.clientIp()
        if (isTrustedProxyAddress(ip)) return false
        val block = BlockList.match(ip) ?: return false
        call.attributes.put(SecurityMonitor.Recorded, true)
        val retryAfter = (block.remainingMs() / 1000).coerceAtLeast(1)
        call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (path.startsWith("/api/") || call.request.headers[HttpHeaders.Upgrade] != null) {
            call.respond(
                HttpStatusCode.Forbidden,
                SecurityDenialResponse(
                    error = "Your network is temporarily blocked because of unusual activity. " +
                        "It is lifted automatically in ${(retryAfter + 59) / 60} minute(s), or you can ask an administrator to lift it sooner.",
                    errorCode = "IP_BLOCKED",
                    code = "ip_blocked",
                    blockId = block.id.toString(),
                    expiresAt = block.expiresAt,
                    retryAfterSeconds = retryAfter,
                    unblockRequestsEnabled = SecuritySettings.config.unblock_requests_enabled
                )
            )
        } else {
            call.respondStaticHtml("blocked.html", HttpStatusCode.Forbidden)
        }
        return true
    }
}
