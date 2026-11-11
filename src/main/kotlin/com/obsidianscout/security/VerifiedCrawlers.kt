package com.obsidianscout.security

import com.obsidianscout.auth.isTrustedProxyAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.Hashtable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.naming.Context
import javax.naming.NameNotFoundException
import javax.naming.directory.InitialDirContext

/**
 * Search engine crawlers crawl lots of missing pages, which looks like a scan. When a request's user agent claims to
 * be one of them, the address is checked the way the operators document it: the address's PTR name must end in the
 * operator's domain (Googlebot: googlebot.com / google.com, Applebot: applebot.apple.com, Bing: search.msn.com, ...),
 * *and* that name must resolve back to the same address. Anyone can claim to be Googlebot in a user agent, and anyone
 * can set a PTR record on their own IP to "crawl.googlebot.com"; only the operator controls the forward record.
 *
 * Only operators that verify by DNS are listed. Cloudflare's robots can't be verified by address (anyone's
 * Workers share Cloudflare's ranges), so Cloudflare is handled by [CloudflareNetwork] instead: never blocked, never
 * trusted.
 *
 * A verified address is never scored or blocked. A check that gets a definite answer which doesn't match is a fake
 * crawler, which is itself suspicious. A check that couldn't finish (DNS timeout or error, or too many checks queued)
 * proves nothing either way: the address is treated like any other and tried again later. Lookups run in the
 * background (never on a request), are cached for a day (an hour for fakes) and verified addresses are shared across
 * the cluster through the security store.
 */
object VerifiedCrawlers {
    private val log = org.slf4j.LoggerFactory.getLogger(VerifiedCrawlers::class.java)

    enum class Status { NOT_CLAIMED, PENDING, VERIFIED, FAILED, UNKNOWN }

    /** What a check found. */
    enum class Result { VERIFIED, FAKE, UNKNOWN }

    /**
     * @param fakeIsSuspicious true when the operator documents reverse DNS for every crawler address, so a failed
     *   check means the user agent is lying. False where verification can legitimately miss.
     */
    data class Crawler(
        val name: String,
        val userAgentTokens: List<String>,
        val dnsSuffixes: List<String>,
        val fakeIsSuspicious: Boolean = true
    )

    val crawlers: List<Crawler> = listOf(
        Crawler(
            "Google",
            listOf("googlebot", "google-inspectiontool", "googleother", "adsbot-google", "mediapartners-google",
                "storebot-google", "apis-google", "feedfetcher-google", "google-safety", "google-read-aloud",
                "duplexweb-google", "google favicon", "google-site-verification", "google-pagerenderer"),
            // Not plain googleusercontent.com: that's every Google Cloud VM, which anyone can rent.
            listOf(".googlebot.com", ".google.com", ".gae.googleusercontent.com")
        ),
        Crawler("Apple", listOf("applebot"), listOf(".applebot.apple.com")),
        Crawler("Bing", listOf("bingbot", "bingpreview", "msnbot", "adidxbot"), listOf(".search.msn.com")),
        // DuckDuckGo publishes an address list rather than promising reverse DNS, so a miss isn't proof of a fake.
        Crawler("DuckDuckGo", listOf("duckduckbot", "duckassistbot", "duckduckgo-favicons-bot"), listOf(".duckduckgo.com"),
            fakeIsSuspicious = false),
        Crawler("Yandex", listOf("yandexbot", "yandeximages", "yandexmobilebot"), listOf(".yandex.ru", ".yandex.net", ".yandex.com")),
        Crawler("Baidu", listOf("baiduspider"), listOf(".baidu.com", ".baidu.jp"))
    )

    /** Cheap first look at a user agent before matching every token. */
    private val prefilter = listOf("bot", "google", "spider", "bing", "duckduck", "yandex", "apple")

    private class Entry(val status: Status, val crawler: String, val until: Long)
    private val cache = ConcurrentHashMap<String, Entry>()
    private const val VERIFIED_TTL_MS = 24 * 3_600_000L
    private const val FAILED_TTL_MS = 3_600_000L
    private const val UNKNOWN_RETRY_MS = 5 * 60_000L
    /** Longer than [CHECK_TIMEOUT_MS], so a check always finishes before its pending mark lapses. */
    private const val PENDING_TTL_MS = 30_000L
    /** Whole check, including the wait for a lookup slot. */
    private const val CHECK_TIMEOUT_MS = 8_000L
    private const val MAX_CACHE = 50_000
    /** Checks waiting or running at once; beyond this, new claims are treated like any other client. */
    private const val MAX_QUEUED = 256

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lookups = Semaphore(8)
    private val queued = AtomicInteger(0)

    /** Thrown by a lookup when DNS gave no answer (timeout, server failure): nothing can be concluded. */
    class LookupUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * The records of one [type] for [name]. One type per query on purpose: asking JNDI for several types at once
     * sends a single ANY query, and many nameservers (Microsoft's among them, so every search.msn.com host) answer
     * ANY with nothing (RFC 8482), which made every real Bingbot look fake.
     */
    private fun dnsQuery(name: String, type: String): List<String> {
        val env = Hashtable<String, String>().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
            put("com.sun.jndi.dns.timeout.initial", "1000")
            put("com.sun.jndi.dns.timeout.retries", "1")
        }
        val ctx = try { InitialDirContext(env) } catch (e: Exception) { throw LookupUnavailable("DNS unavailable", e) }
        try {
            val attr = ctx.getAttributes(name, arrayOf(type)).get(type) ?: return emptyList()
            return (0 until attr.size()).map { attr.get(it).toString() }
        } catch (e: NameNotFoundException) {
            return emptyList() // NXDOMAIN: a definite "no such name".
        } catch (e: Exception) {
            throw LookupUnavailable("DNS $type lookup for $name failed: ${e.message}", e)
        } finally {
            runCatching { ctx.close() }
        }
    }

    /** "1.2.3.4" -> "4.3.2.1.in-addr.arpa"; IPv6 -> nibbles under ip6.arpa. */
    internal fun arpaName(ip: String): String? {
        val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return null
        return if (bytes.size == 4) {
            bytes.reversed().joinToString(".") { (it.toInt() and 0xFF).toString() } + ".in-addr.arpa"
        } else {
            bytes.reversed().joinToString(".") { b ->
                val v = b.toInt() and 0xFF
                "${Character.forDigit(v and 0xF, 16)}.${Character.forDigit(v shr 4, 16)}"
            } + ".ip6.arpa"
        }
    }

    private val systemReverse: (String) -> String? = { ip ->
        val arpa = arpaName(ip) ?: throw LookupUnavailable("Not an address: $ip")
        dnsQuery(arpa, "PTR").firstOrNull()
    }
    private val systemForward: (String) -> List<String> = { host ->
        // A and AAAA asked separately (see dnsQuery). One failing doesn't matter if the other answered; an empty
        // answer only counts as "no such address" when both types got a definite reply.
        val results = listOf("A", "AAAA").map { type -> runCatching { dnsQuery(host, type) } }
        val found = results.mapNotNull { it.getOrNull() }.flatten()
        if (found.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        found
    }

    /** Reverse lookup: the PTR name of [ip], or null when it has none. Throws [LookupUnavailable] when DNS can't answer. Tests replace it. */
    @Volatile var reverseLookup: (String) -> String? = systemReverse

    /** Forward lookup: every address [host] resolves to (empty when it doesn't exist). Throws [LookupUnavailable] when DNS can't answer. Tests replace it. */
    @Volatile var forwardLookup: (String) -> List<String> = systemForward

    /** Tests run checks inline. */
    @Volatile var synchronousForTests: Boolean = false

    /** Which crawler [userAgent] claims to be, if any. */
    fun claimed(userAgent: String?): Crawler? {
        if (userAgent.isNullOrBlank()) return null
        val ua = userAgent.lowercase()
        if (prefilter.none { ua.contains(it) }) return null
        return crawlers.firstOrNull { c -> c.userAgentTokens.any { ua.contains(it) } }
    }

    fun status(ip: String, now: Long = System.currentTimeMillis()): Status {
        cache[ip]?.takeIf { it.until > now }?.let { return it.status }
        if (SecurityStore.hasFlag("crawler-ok:$ip", now)) {
            cache[ip] = Entry(Status.VERIFIED, "", now + VERIFIED_TTL_MS)
            return Status.VERIFIED
        }
        return Status.NOT_CLAIMED
    }

    fun isVerified(ip: String): Boolean = status(ip) == Status.VERIFIED
    fun isPending(ip: String): Boolean = status(ip) == Status.PENDING

    /**
     * Notes a request from [ip] with [userAgent]. If it claims to be a known crawler and the address hasn't been
     * checked recently, a check starts in the background. Never blocks.
     */
    fun observe(ip: String, userAgent: String?, now: Long = System.currentTimeMillis()) {
        if (!SecuritySettings.config.verify_crawlers) return
        val crawler = claimed(userAgent) ?: return
        if (ip == "unknown" || isTrustedProxyAddress(ip)) return
        if (IpRange.toBigInteger(ip) == null) return
        if (status(ip, now) != Status.NOT_CLAIMED) return
        if (cache.size > MAX_CACHE) cache.entries.removeIf { it.value.until <= now }
        if (cache.size > MAX_CACHE) return
        if (synchronousForTests) {
            cache[ip] = Entry(Status.PENDING, crawler.name, now + PENDING_TTL_MS)
            finish(ip, crawler, check(ip, crawler))
            return
        }
        // Too many checks already waiting (a flood of fake crawlers): don't hold this one's blocks back for a slot.
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet()
            return
        }
        // Only one check per address: a request racing this one finds the pending mark and leaves it alone.
        var mine = false
        cache.compute(ip) { _, old ->
            if (old != null && old.until > now) old else Entry(Status.PENDING, crawler.name, now + PENDING_TTL_MS).also { mine = true }
        }
        if (!mine) {
            queued.decrementAndGet()
            return
        }
        scope.launch {
            try {
                val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                    lookups.withPermit { runInterruptible { check(ip, crawler) } }
                } ?: Result.UNKNOWN
                finish(ip, crawler, result)
            } finally {
                queued.decrementAndGet()
            }
        }
    }

    /** The check itself (blocking DNS): whether [ip] really belongs to [crawler]'s operator, or UNKNOWN if DNS didn't answer. */
    fun check(ip: String, crawler: Crawler): Result = try {
        if (verify(ip, crawler)) Result.VERIFIED else Result.FAKE
    } catch (e: LookupUnavailable) {
        Result.UNKNOWN
    } catch (e: Exception) {
        log.debug("[VerifiedCrawlers] Check of {} failed: {}", ip, e.message)
        Result.UNKNOWN
    }

    /** True when DNS confirms [ip] belongs to [crawler]'s operator, false when it definitely doesn't. Throws [LookupUnavailable]. */
    fun verify(ip: String, crawler: Crawler): Boolean {
        if (crawler.dnsSuffixes.isEmpty()) return false
        val host = reverseLookup(ip)?.lowercase()?.trimEnd('.') ?: return false
        if (crawler.dnsSuffixes.none { host.endsWith(it) }) return false
        val want = IpRange.toBigInteger(ip) ?: return false
        // Forward-confirm: the name must point back at the same address.
        return forwardLookup(host).any { IpRange.toBigInteger(it) == want }
    }

    private fun finish(ip: String, crawler: Crawler, result: Result, now: Long = System.currentTimeMillis()) {
        when (result) {
            Result.VERIFIED -> {
                cache[ip] = Entry(Status.VERIFIED, crawler.name, now + VERIFIED_TTL_MS)
                SecurityStore.setFlag("crawler-ok:$ip", VERIFIED_TTL_MS, now)
                // Anything it scored while the check was running doesn't count, and a block it picked up meanwhile goes.
                SecurityStore.resetScore(ThreatEngine.ipKey(ip))
                BlockList.match(ip, now, countHit = false)?.takeIf { it.createdBy == "system" }?.let {
                    BlockList.lift(it.id, "system", "Verified ${crawler.name} crawler", resetStrikes = true, allowlistFor = "none")
                }
                log.debug("[VerifiedCrawlers] {} verified as {}", ip, crawler.name)
            }
            Result.FAKE -> {
                cache[ip] = Entry(Status.FAILED, crawler.name, now + FAILED_TTL_MS)
                if (crawler.fakeIsSuspicious && SecurityStore.claimOnce("fakecrawler:$ip", FAILED_TTL_MS, now)) {
                    SecurityEvents.emit(SecurityEvent(SecurityEventType.FAKE_CRAWLER, ip,
                        details = "Claims to be ${crawler.name}'s crawler, but reverse DNS doesn't confirm it"))
                }
            }
            // Treated like any other client until the retry; never counted as fake.
            Result.UNKNOWN -> cache[ip] = Entry(Status.UNKNOWN, crawler.name, now + UNKNOWN_RETRY_MS)
        }
    }

    /** Tests only. */
    internal fun clearForTests() {
        cache.clear()
        queued.set(0)
        synchronousForTests = false
        reverseLookup = systemReverse
        forwardLookup = systemForward
    }
}
