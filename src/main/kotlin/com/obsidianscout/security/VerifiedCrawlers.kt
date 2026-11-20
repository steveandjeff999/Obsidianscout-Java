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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    private const val DNS_PORT = 53
    private const val DNS_TIMEOUT_MS = 1000
    private const val DNS_RETRIES = 1
    private const val DNS_TYPE_A = 1
    private const val DNS_TYPE_PTR = 12
    private const val DNS_TYPE_AAAA = 28
    private const val DNS_SERVER_CACHE_MS = 5 * 60_000L
    /**
     * Only used when the machine has no DNS server we can find. Lookups never move on to these when a configured
     * server merely times out: that would send visitors' addresses to a third party and fail anyway where outbound
     * DNS is firewalled, and a lookup that can't be answered is already harmless (UNKNOWN, nothing gets flagged).
     */
    private val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
    private val IPV4_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    /** Candidate IPv6 literals (optionally %zone); each is then checked by [InetAddress], which never does DNS for a string containing ':'. */
    private val IPV6_REGEX = Regex("""(?<![0-9A-Za-z:.])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?:%[0-9A-Za-z_.-]+)?""")

    @Volatile private var cachedDnsServers: List<String> = emptyList()
    @Volatile private var cachedDnsServersUntil: Long = 0L
    private val dnsServersLock = Any()

    private fun resolveDnsServers(): List<String> {
        val current = cachedDnsServers
        if (current.isNotEmpty() && System.currentTimeMillis() < cachedDnsServersUntil) return current
        // One refresh at a time: lookups that arrive meanwhile wait for its answer instead of each running netsh.
        synchronized(dnsServersLock) {
            val again = cachedDnsServers
            if (again.isNotEmpty() && System.currentTimeMillis() < cachedDnsServersUntil) return again
            val discovered = discoverSystemDnsServers().ifEmpty { DEFAULT_DNS_SERVERS }
            cachedDnsServers = discovered
            cachedDnsServersUntil = System.currentTimeMillis() + DNS_SERVER_CACHE_MS
            return discovered
        }
    }

    private fun discoverSystemDnsServers(): List<String> = runCatching {
        val os = System.getProperty("os.name", "").lowercase()
        if (os.contains("win")) {
            // IPv4 servers first: they're the usual case and need no interface scope.
            (parseNetshDnsServers(runNetsh("ipv4")) + parseNetshDnsServers(runNetsh("ipv6"))).distinct()
        } else {
            val resolv = File("/etc/resolv.conf")
            if (!resolv.isFile) emptyList() else parseResolvConf(resolv.readLines())
        }
    }.getOrDefault(emptyList())

    /** Output of `netsh interface <family> show dnsservers`, or "" if it fails or takes too long. */
    private fun runNetsh(family: String): String {
        val proc = ProcessBuilder("netsh", "interface", family, "show", "dnsservers")
            .redirectErrorStream(true)
            .start()
        proc.outputStream.close()
        val out = java.util.concurrent.atomic.AtomicReference("")
        val reader = Thread { out.set(proc.inputStream.bufferedReader().readText()) }.apply {
            isDaemon = true
            start()
        }
        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return ""
        }
        reader.join(500)
        return out.get()
    }

    /** DNS server addresses in `netsh ... show dnsservers` output (any language: only the addresses are read). */
    internal fun parseNetshDnsServers(output: String): List<String> {
        val v4 = IPV4_REGEX.findAll(output).map { it.value }
            .filter { it != "0.0.0.0" && !it.startsWith("127.") }
        val v6 = IPV6_REGEX.findAll(output).map { it.value }
            .filter { usableIpv6Server(it) }
        return (v4 + v6).distinct().toList()
    }

    /** `nameserver` entries of a resolv.conf. */
    internal fun parseResolvConf(lines: List<String>): List<String> = lines.mapNotNull { line ->
        val trimmed = line.substringBefore('#').substringBefore(';').trim()
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size >= 2 && parts[0] == "nameserver") parts[1] else null
    }.filter { if (':' in it) usableIpv6Server(it) else IPV4_REGEX.matches(it) }.distinct()

    private fun usableIpv6Server(literal: String): Boolean {
        if (literal.count { it == ':' } < 2) return false
        val addr = runCatching { InetAddress.getByName(literal) }.getOrNull() as? java.net.Inet6Address ?: return false
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress) return false
        // fec0:0:0:ffff::1-3 are the placeholders Windows lists on adapters with no IPv6 DNS configured.
        if (addr.isSiteLocalAddress) return false
        // A link-local server is only reachable with its interface scope.
        if (addr.isLinkLocalAddress && '%' !in literal) return false
        return true
    }

    /**
     * The records of one [type] for [name]. One type per query on purpose: asking for several types at once
     * sends a single ANY query, and many nameservers (Microsoft's among them, so every search.msn.com host) answer
     * ANY with nothing (RFC 8482), which made every real Bingbot look fake.
     *
     * Implemented directly over UDP (RFC 1035) rather than JNDI [InitialDirContext] because JNDI's `DnsContextFactory`
     * invokes `sun.net.dns.ResolverConfigurationImpl.loadDNSconfig0()` via JNI, which segfaults in GraalVM native-image
     * on Windows.
     */
    private fun dnsQuery(name: String, type: String): List<String> {
        val qtype = when (type) {
            "A" -> DNS_TYPE_A
            "AAAA" -> DNS_TYPE_AAAA
            "PTR" -> DNS_TYPE_PTR
            else -> throw IllegalArgumentException("Unsupported DNS record type: $type")
        }
        val servers = resolveDnsServers()
        var lastError: Throwable? = null
        val attempts = 1 + DNS_RETRIES
        for (attempt in 0 until attempts) {
            val server = servers[attempt % servers.size]
            try {
                val id = ThreadLocalRandom.current().nextInt(0x10000)
                val query = buildDnsQuery(id, name, qtype)
                val serverAddr = InetAddress.getByName(server) // Always a literal address, so no lookup happens here.
                DatagramSocket().use { socket ->
                    socket.soTimeout = DNS_TIMEOUT_MS
                    socket.connect(serverAddr, DNS_PORT)
                    socket.send(DatagramPacket(query, query.size))
                    val buf = ByteArray(1536)
                    val packet = DatagramPacket(buf, buf.size)
                    val deadline = System.currentTimeMillis() + DNS_TIMEOUT_MS
                    while (true) {
                        // Stray packets mustn't keep extending the wait past one timeout.
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) throw java.net.SocketTimeoutException("DNS reply timed out")
                        socket.soTimeout = left.toInt()
                        packet.setLength(buf.size)
                        socket.receive(packet)
                        // Ignore anything that isn't the reply to this exact question (late or forged packets).
                        if (isReplyTo(buf, packet.length, query)) {
                            return parseDnsResponse(buf, packet.length, id, qtype, query)
                        }
                    }
                }
            } catch (e: LookupUnavailable) {
                lastError = e
            } catch (e: Exception) {
                lastError = LookupUnavailable("DNS $type lookup for $name failed: ${e.message}", e)
            }
        }
        throw (lastError as? LookupUnavailable ?: LookupUnavailable("DNS $type lookup for $name failed", lastError))
    }

    internal fun buildDnsQuery(id: Int, name: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream(64)
        // Header: ID, Flags (RD=1), QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        for (label in name.trimEnd('.').split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.size > 63) throw LookupUnavailable("DNS label too long: $label")
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        // QTYPE, QCLASS=IN (1)
        out.write((qtype shr 8) and 0xFF); out.write(qtype and 0xFF)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    /**
     * True when [buf] is a standard-query response (QR=1, OPCODE=0) with [query]'s transaction ID, carrying exactly
     * [query]'s question. Names compare case-insensitively, since servers may echo a different case.
     */
    internal fun isReplyTo(buf: ByteArray, length: Int, query: ByteArray): Boolean {
        if (length < query.size || query.size < 12) return false
        if (buf[0] != query[0] || buf[1] != query[1]) return false
        val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if ((flags and 0x8000) == 0) return false // QR: must be a response.
        if (((flags shr 11) and 0x0F) != 0) return false // OPCODE: must be a standard query.
        val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        if (qdCount != 1) return false
        for (i in 12 until query.size) {
            val a = buf[i].toInt() and 0xFF
            val b = query[i].toInt() and 0xFF
            if (a != b && asciiLower(a) != asciiLower(b)) return false
        }
        return true
    }

    private fun asciiLower(c: Int): Int = if (c in 'A'.code..'Z'.code) c + 32 else c

    internal fun parseDnsResponse(
        buf: ByteArray,
        length: Int,
        expectedId: Int,
        expectedType: Int,
        query: ByteArray? = null
    ): List<String> {
        if (length < 12) throw LookupUnavailable("Truncated DNS header")
        val id = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (id != expectedId) throw LookupUnavailable("Mismatched DNS transaction ID")
        if (query != null && !isReplyTo(buf, length, query)) throw LookupUnavailable("DNS reply doesn't match the question")
        val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if ((flags and 0x8000) == 0) throw LookupUnavailable("DNS packet is not a response")
        if (((flags shr 11) and 0x0F) != 0) throw LookupUnavailable("DNS reply has unexpected opcode")
        if ((flags and 0x0200) != 0) throw LookupUnavailable("Truncated DNS response")
        val rcode = flags and 0x0F
        if (rcode == 3) return emptyList() // NXDOMAIN: definite "no such name".
        if (rcode != 0) throw LookupUnavailable("DNS server returned RCODE $rcode")

        val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)

        var pos = 12
        repeat(qdCount) {
            pos = skipDnsName(buf, length, pos)
            if (pos + 4 > length) throw LookupUnavailable("Truncated DNS question")
            pos += 4
        }

        val results = mutableListOf<String>()
        repeat(anCount) {
            pos = skipDnsName(buf, length, pos)
            if (pos + 10 > length) throw LookupUnavailable("Truncated DNS answer")
            val rtype = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val rclass = ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
            val rdLength = ((buf[pos + 8].toInt() and 0xFF) shl 8) or (buf[pos + 9].toInt() and 0xFF)
            pos += 10
            if (pos + rdLength > length) throw LookupUnavailable("Truncated DNS rdata")

            if (rclass == 1 && rtype == expectedType) {
                when (rtype) {
                    DNS_TYPE_A -> if (rdLength == 4) {
                        results.add(InetAddress.getByAddress(buf.copyOfRange(pos, pos + 4)).hostAddress)
                    }
                    DNS_TYPE_AAAA -> if (rdLength == 16) {
                        results.add(InetAddress.getByAddress(buf.copyOfRange(pos, pos + 16)).hostAddress)
                    }
                    DNS_TYPE_PTR -> {
                        val ptrName = readDnsName(buf, length, pos)
                        if (ptrName.isNotEmpty()) results.add(ptrName)
                    }
                }
            }
            pos += rdLength
        }
        return results
    }

    private fun skipDnsName(buf: ByteArray, length: Int, start: Int): Int {
        var pos = start
        var jumps = 0
        while (pos < length) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= length) throw LookupUnavailable("Truncated DNS pointer")
                return pos + 2
            }
            if ((len and 0xC0) != 0) throw LookupUnavailable("Invalid DNS label")
            pos += 1 + len
            if (++jumps > 128) throw LookupUnavailable("Malformed DNS name")
        }
        throw LookupUnavailable("Truncated DNS name")
    }

    private fun readDnsName(buf: ByteArray, length: Int, start: Int): String {
        val sb = StringBuilder()
        var pos = start
        var jumps = 0
        while (pos < length) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) return sb.toString()
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= length) throw LookupUnavailable("Truncated DNS pointer")
                val offset = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                if (offset >= length || ++jumps > 16) throw LookupUnavailable("Invalid DNS pointer")
                pos = offset
            } else {
                if ((len and 0xC0) != 0) throw LookupUnavailable("Invalid DNS label")
                pos += 1
                if (pos + len > length) throw LookupUnavailable("Truncated DNS label")
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(buf, pos, len, Charsets.UTF_8))
                pos += len
            }
        }
        throw LookupUnavailable("Truncated DNS name")
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
