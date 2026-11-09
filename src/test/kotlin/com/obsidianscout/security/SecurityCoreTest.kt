package com.obsidianscout.security

import com.obsidianscout.config.SecurityConfig
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityCoreTest {
    private val hour = 60 * 60_000L

    @BeforeTest
    fun setUp() = SecurityTestSupport.reset()

    // ------------------------------------------------------------------ the one-hour ceiling

    @Test
    fun `no block is ever longer than an hour, whatever asks for it`() {
        assertEquals(hour, BlockList.clampDuration(10 * hour))
        assertEquals(hour, BlockList.clampDuration(Long.MAX_VALUE))
        assertEquals(hour, BlockList.durationForStrike(1, listOf(600)))
        assertEquals(hour, BlockList.durationForStrike(9, listOf(5, 15, 60, 240, 100_000)))

        val manual = assertNotNull(BlockList.manualBlock("203.0.113.10", minutes = 10_000, reason = "test", by = "admin"))
        assertTrue(manual.expiresAt - manual.createdAt <= hour)

        SecuritySettings.setConfigForTests(SecurityConfig(mode = "enforce", block_minutes = listOf(1_000, 10_000)))
        repeat(4) { i ->
            val block = assertNotNull(BlockList.autoBlock("203.0.113.20", "test", listOf("TEST")))
            assertTrue(block.expiresAt - block.createdAt <= hour, "block ${i + 1} was longer than an hour")
            BlockList.lift(block.id, "admin", "next", resetStrikes = false, allowlistFor = "none")
        }
    }

    @Test
    fun `blocks escalate 5, 15, then 60 minutes and a lift can reset the count`() {
        val ip = "203.0.113.30"
        val lengths = (1..4).map {
            val block = assertNotNull(BlockList.autoBlock(ip, "test", listOf("TEST")))
            BlockList.lift(block.id, "admin", "next", resetStrikes = false, allowlistFor = "none")
            (block.expiresAt - block.createdAt) / 60_000
        }
        assertEquals(listOf(5L, 15L, 60L, 60L), lengths)

        val last = assertNotNull(BlockList.autoBlock(ip, "test", listOf("TEST")))
        BlockList.lift(last.id, "admin", "reset", resetStrikes = true, allowlistFor = "none")
        val afterReset = assertNotNull(BlockList.autoBlock(ip, "test", listOf("TEST")))
        assertEquals(5L, (afterReset.expiresAt - afterReset.createdAt) / 60_000)
    }

    // ------------------------------------------------------------------ never blocked

    @Test
    fun `our own proxies, cluster peers and the allowlist are never blocked automatically`() {
        assertNull(BlockList.autoBlock("127.0.0.1", "test", emptyList()))
        assertNull(BlockList.autoBlock("::1", "test", emptyList()))
        assertNull(BlockList.autoBlock("100.64.3.4", "test", emptyList()))
        assertNotNull(Allowlist.add("198.51.100.0/24", "venue", "admin", null))
        assertNull(BlockList.autoBlock("198.51.100.77", "test", emptyList()))
        assertNull(BlockList.manualBlock("127.0.0.1", 10, "no", "admin"))
    }

    @Test
    fun `the health check, unblock endpoints, blocked page and assets get through a block`() {
        listOf(
            "/health", "/api/health", "/api/security/unblock-request", "/api/security/block-status",
            "/api/security/challenge", "/blocked", "/blocked.html", "/css/app.css", "/js/blocked.js",
            "/js/security/pow.js", "/vendor/fonts/material-symbols-outlined.woff2", "/assets/images/obsidian/obsidian-192.png"
        ).forEach { assertTrue(BlockGate.isExemptPath(it), it) }
        listOf("/dashboard", "/", "/api/auth/login", "/api/mobile/auth/login", "/scout", "/models/weights.js", "/api/admin/security/blocks")
            .forEach { assertFalse(BlockGate.isExemptPath(it), it) }
    }

    // ------------------------------------------------------------------ scoring and remediation

    @Test
    fun `observe mode reports what it would block without blocking`() {
        SecuritySettings.setConfigForTests(SecurityConfig(mode = "observe"))
        SecurityTestSupport.makeHostile("203.0.113.40")
        assertNull(BlockList.match("203.0.113.40"))
        assertTrue(SecurityEventType.WOULD_BLOCK in SecurityTestSupport.types())
    }

    @Test
    fun `enforce mode blocks a hostile address and alerts superadmins`() {
        SecurityTestSupport.makeHostile("203.0.113.41")
        val block = assertNotNull(BlockList.match("203.0.113.41"))
        assertEquals(5L, (block.expiresAt - block.createdAt) / 60_000)
        assertTrue(SecurityEventType.BLOCK_APPLIED in SecurityTestSupport.types())
        assertTrue(SecurityTestSupport.alerts.any { it.second.type == SecurityEventType.BLOCK_APPLIED })
    }

    @Test
    fun `a shared network is reported, not blocked`() {
        val ip = "203.0.113.42"
        listOf("u1", "u2", "u3").forEach { LoginGuard.onSuccess(ip, it, it, 1234, "FRC") }
        SecurityTestSupport.makeHostile(ip)
        assertNull(BlockList.match(ip))
        assertTrue(SecurityEventType.BLOCK_SKIPPED in SecurityTestSupport.types())
    }

    @Test
    fun `an IPv6 block matches however the address is written`() {
        val block = assertNotNull(BlockList.manualBlock("2001:db8::1", 10, "test", "admin"))
        assertEquals("2001:db8:0:0:0:0:0:1", block.range.text)
        listOf("2001:db8::1", "2001:DB8:0:0:0:0:0:1", "[2001:db8::1]", "2001:db8:0::1")
            .forEach { assertNotNull(BlockList.match(it, countHit = false), it) }
        assertNull(BlockList.match("2001:db8::2", countHit = false))
    }

    @Test
    fun `client addresses get one spelling`() {
        assertEquals("2001:db8:0:0:0:0:0:1", com.obsidianscout.auth.canonicalIp("2001:db8::1"))
        assertEquals("2001:db8:0:0:0:0:0:1", com.obsidianscout.auth.canonicalIp(" [2001:DB8::1] "))
        assertEquals("203.0.113.7", com.obsidianscout.auth.canonicalIp("::ffff:203.0.113.7"))
        assertEquals("203.0.113.7", com.obsidianscout.auth.canonicalIp("203.0.113.7"))
        // Not a literal: returned as given, never looked up over DNS.
        assertEquals("example.com", com.obsidianscout.auth.canonicalIp("example.com"))
        assertEquals("evil:host", com.obsidianscout.auth.canonicalIp("evil:host"))
    }

    @Test
    fun `lifting a block lets the address through, resets its score and can allowlist it`() {
        val ip = "203.0.113.43"
        SecurityTestSupport.makeHostile(ip)
        val block = assertNotNull(BlockList.match(ip))
        assertTrue(ThreatEngine.ipScore(ip) > 0)

        val lifted = assertNotNull(BlockList.lift(block.id, "admin", "venue wifi", resetStrikes = true, allowlistFor = "24h"))
        assertFalse(lifted.active)
        assertNull(BlockList.match(ip))
        assertEquals(0.0, ThreatEngine.ipScore(ip))
        assertTrue(Allowlist.isAllowed(ip))
        assertTrue(SecurityEventType.BLOCK_LIFTED in SecurityTestSupport.types())

        // Allowlisted now, so even more hostility only gets reported.
        SecurityTestSupport.makeHostile(ip)
        assertNull(BlockList.match(ip))
    }

    @Test
    fun `unblock all lifts every block`() {
        BlockList.manualBlock("203.0.113.50", 30, "a", "admin")
        BlockList.manualBlock("203.0.113.0/28", 30, "b", "admin")
        assertNotNull(BlockList.match("203.0.113.3"))
        assertEquals(2, BlockList.liftAll("admin", "bad rule at event"))
        assertNull(BlockList.match("203.0.113.50"))
        assertNull(BlockList.match("203.0.113.3"))
    }

    // ------------------------------------------------------------------ unblock requests

    private fun solved(ip: String): String {
        val challenge = ProofOfWork.issue(ip, difficulty = 8)
        return "${challenge.token}:${ProofOfWork.solve(challenge.nonce, challenge.difficulty)}"
    }

    private fun submission(ip: String, challenge: String = solved(ip)) =
        UnblockRequestSubmission(name = "Pat", teamNumber = 1234, message = "Scouting at the event", challenge = challenge)

    @Test
    fun `an unblock request needs a block and a solved security check`() {
        val ip = "203.0.113.60"
        assertFailsWith<com.obsidianscout.auth.ApiException> { UnblockRequests.submit(ip, submission(ip)) }
        BlockList.manualBlock(ip, 60, "test", "admin")
        assertFailsWith<com.obsidianscout.auth.ApiException> { UnblockRequests.submit(ip, submission(ip, challenge = "")) }
        assertFailsWith<com.obsidianscout.auth.ApiException> { UnblockRequests.submit(ip, submission(ip, challenge = solved("203.0.113.61"))) }

        val first = UnblockRequests.submit(ip, submission(ip))
        assertEquals("pending", first.status)
        // A second submission for the same block gets the first one back.
        assertEquals(first.id, UnblockRequests.submit(ip, submission(ip)).id)
        assertTrue(SecurityTestSupport.alerts.any { it.second.type == SecurityEventType.UNBLOCK_REQUESTED })
        assertEquals("pending", UnblockRequests.status(first.id, ip)?.status)
        assertNull(UnblockRequests.status(first.id, "203.0.113.99"), "status is only for the address that sent it")
    }

    @Test
    fun `approving a request lifts the block, denying keeps it`() {
        val approvedIp = "203.0.113.62"
        BlockList.manualBlock(approvedIp, 60, "test", "admin")
        val approved = UnblockRequests.submit(approvedIp, submission(approvedIp))
        UnblockRequests.approve(approved.id, "admin", "ok", resetStrikes = true, allowlistFor = "none")
        assertNull(BlockList.match(approvedIp))
        assertEquals("approved", UnblockRequests.status(approved.id, approvedIp)?.status)

        val deniedIp = "203.0.113.63"
        BlockList.manualBlock(deniedIp, 60, "test", "admin")
        val denied = UnblockRequests.submit(deniedIp, submission(deniedIp))
        UnblockRequests.deny(denied.id, "admin", "wait it out")
        assertNotNull(BlockList.match(deniedIp))
        assertEquals("denied", UnblockRequests.status(denied.id, deniedIp)?.status)
        assertEquals("wait it out", UnblockRequests.status(denied.id, deniedIp)?.reviewNote)
        assertFailsWith<com.obsidianscout.auth.ApiException> { UnblockRequests.approve(denied.id, "admin", "", true, "none") }
    }

    @Test
    fun `lifting a block answers its waiting request`() {
        val ip = "203.0.113.64"
        val block = assertNotNull(BlockList.manualBlock(ip, 60, "test", "admin"))
        val request = UnblockRequests.submit(ip, submission(ip))
        BlockList.lift(block.id, "admin", "manual", resetStrikes = true, allowlistFor = "none")
        assertEquals("approved", UnblockRequests.status(request.id, ip)?.status)
    }

    @Test
    fun `each address may send only a few requests a day`() {
        SecuritySettings.setConfigForTests(SecurityConfig(mode = "enforce", unblock_requests_per_ip_per_day = 2))
        val ip = "203.0.113.65"
        repeat(2) {
            val block = assertNotNull(BlockList.manualBlock(ip, 60, "test", "admin"))
            UnblockRequests.submit(ip, submission(ip))
            BlockList.lift(block.id, "admin", "x", resetStrikes = true, allowlistFor = "none")
        }
        BlockList.manualBlock(ip, 60, "test", "admin")
        val error = assertFailsWith<com.obsidianscout.auth.ApiException> { UnblockRequests.submit(ip, submission(ip)) }
        assertEquals(429, error.status.value)
    }

    @Test
    fun `unblock requests can be switched off`() {
        SecuritySettings.setConfigForTests(SecurityConfig(mode = "enforce", unblock_requests_enabled = false))
        val ip = "203.0.113.66"
        BlockList.manualBlock(ip, 60, "test", "admin")
        assertFailsWith<com.obsidianscout.auth.ApiException> { UnblockRequests.submit(ip, submission(ip)) }
    }

    // ------------------------------------------------------------------ proof of work

    @Test
    fun `a security check answer works once, only from the address it was issued to`() {
        val ip = "203.0.113.70"
        val answer = solved(ip)
        assertEquals(ProofOfWork.Result.INVALID, ProofOfWork.verify(answer, "203.0.113.71"))
        assertEquals(ProofOfWork.Result.OK, ProofOfWork.verify(answer, ip))
        assertEquals(ProofOfWork.Result.REUSED, ProofOfWork.verify(answer, ip))
        assertEquals(ProofOfWork.Result.MISSING, ProofOfWork.verify(null, ip))

        val challenge = ProofOfWork.issue(ip, difficulty = 8)
        assertEquals(ProofOfWork.Result.EXPIRED,
            ProofOfWork.verify("${challenge.token}:${ProofOfWork.solve(challenge.nonce, 8)}", ip, now = challenge.expiresAt + 1))
        assertEquals(ProofOfWork.Result.INVALID, ProofOfWork.verify("${challenge.token}x:1", ip))
        var wrong = 0
        while (ProofOfWork.meetsDifficulty(challenge.nonce, wrong.toString(), 8)) wrong++
        assertEquals(ProofOfWork.Result.WRONG_ANSWER, ProofOfWork.verify("${challenge.token}:$wrong", ip))
    }

    // ------------------------------------------------------------------ sign-in protection

    @Test
    fun `many accounts failing from one address is a password spray`() {
        val ip = "203.0.113.80"
        (1..5).forEach { LoginGuard.afterFailure(ip, "user$it", 1234, "FRC") }
        assertTrue(SecurityEventType.PASSWORD_SPRAY in SecurityTestSupport.types())
    }

    @Test
    fun `one account failing from many addresses is protected`() {
        (1..5).forEach { LoginGuard.afterFailure("203.0.113.${100 + it}", "target", 1234, "FRC") }
        assertTrue(AccountProtection.isProtected(AccountProtection.accountKey("target", 1234, "FRC")))
        val denied = assertFailsWith<SecurityDeniedException> { LoginGuard.check("203.0.113.200", "target", 1234, "FRC", null) }
        assertEquals("account_protected", denied.body.code)
        AccountProtection.clear("target", 1234, "FRC", "passkey sign-in")
        LoginGuard.check("203.0.113.200", "target", 1234, "FRC", null)
    }

    @Test
    fun `the same username on another team or program is a different account`() {
        (1..5).forEach { LoginGuard.afterFailure("203.0.113.${120 + it}", "Admin", 254, "FRC") }
        assertTrue(AccountProtection.isProtected(AccountProtection.accountKey("admin", 254, "frc")))
        // Team 1678's admin and FTC team 254's admin are untouched: no protection, no delay.
        LoginGuard.check("203.0.113.201", "admin", 1678, "FRC", null)
        LoginGuard.check("203.0.113.201", "admin", 254, "FTC", null)
        assertEquals(0L, LoginGuard.afterFailure("203.0.113.202", "admin", 1678, "FRC"))
        val listed = AccountProtection.list().single()
        assertEquals("admin|254|FRC", listed.account)
        assertEquals(254, listed.teamNumber)
        assertEquals("FRC", listed.program)
    }

    @Test
    fun `account keys survive usernames containing the separator`() {
        val key = AccountProtection.accountKey("a|b", 99, "ftc")
        assertEquals(Triple("a|b", 99, "FTC"), AccountProtection.parseAccountKey(key))
        assertNull(AccountProtection.parseAccountKey("no-team"))
    }

    @Test
    fun `account protection ends on its own and repeated failures never extend it`() {
        val account = AccountProtection.accountKey("victim", 1234, "FRC")
        val t0 = 1_000_000_000_000L
        AccountProtection.protect(account, "victim", "test", "203.0.113.130", 1234, now = t0)
        assertTrue(AccountProtection.isProtected(account, t0))
        // Failures keep coming in the middle of it: the end time doesn't move.
        AccountProtection.protect(account, "victim", "test", "203.0.113.131", 1234, now = t0 + 30 * 60_000L)
        assertTrue(AccountProtection.isProtected(account, t0 + AccountProtection.DURATION_MS - 1))
        assertFalse(AccountProtection.isProtected(account, t0 + AccountProtection.DURATION_MS + 1))
        LoginGuard.check("203.0.113.132", "victim", 1234, "FRC", null)
    }

    @Test
    fun `protections can't be chained back to back into a lockout`() {
        val account = AccountProtection.accountKey("chained", 1234, "FRC")
        val t0 = 1_000_000_000_000L
        val end = t0 + AccountProtection.DURATION_MS
        AccountProtection.protect(account, "chained", "test", "203.0.113.140", 1234, now = t0)
        // Right after it ends, more failures don't bring it back: password sign-in is open for the cooldown.
        AccountProtection.protect(account, "chained", "test", "203.0.113.141", 1234, now = end + 1)
        assertFalse(AccountProtection.isProtected(account, end + 1))
        assertFalse(AccountProtection.isProtected(account, end + AccountProtection.COOLDOWN_MS - 1))
        // After the cooldown a fresh attack can protect it again, and that protection ends too.
        val again = end + AccountProtection.COOLDOWN_MS + 1
        AccountProtection.protect(account, "chained", "test", "203.0.113.142", 1234, now = again)
        assertTrue(AccountProtection.isProtected(account, again))
        assertFalse(AccountProtection.isProtected(account, again + AccountProtection.DURATION_MS + 1))
    }

    @Test
    fun `users already signed in make an address a shared network, so it isn't blocked`() {
        SecuritySettings.setConfigForTests(SecurityConfig(mode = "enforce"))
        val ip = "203.0.113.150"
        assertFalse(ThreatEngine.isSharedNetwork(ip))
        listOf("u1", "u2", "u3").forEach { SessionWatch.noteSignedInUser(ip, it) }
        assertTrue(ThreatEngine.isSharedNetwork(ip))
        repeat(5) { SecurityEvents.emit(SecurityEvent(SecurityEventType.INJECTION_PROBE, ip)) }
        assertNull(BlockList.match(ip, countHit = false))
        assertTrue(SecurityEventType.BLOCK_SKIPPED in SecurityTestSupport.types())
    }

    @Test
    fun `account protection only records in observe mode`() {
        SecuritySettings.setConfigForTests(SecurityConfig(mode = "observe"))
        (1..5).forEach { LoginGuard.afterFailure("203.0.113.${110 + it}", "observed", 1234, "FRC") }
        assertFalse(AccountProtection.isProtected(AccountProtection.accountKey("observed", 1234, "FRC")))
        assertTrue(SecurityEventType.ACCOUNT_PROTECTED in SecurityTestSupport.types())
    }

    @Test
    fun `failed sign-ins against one account are slowed down, up to 8 seconds`() {
        val delays = (1..9).map { LoginGuard.afterFailure("203.0.113.90", "slow", 1234, "FRC") }
        assertEquals(listOf(0L, 0L, 0L, 1000L, 2000L, 4000L, 8000L, 8000L, 8000L), delays)
        LoginGuard.onSuccess("203.0.113.90", "id", "slow", 1234, "FRC")
        assertEquals(0L, LoginGuard.afterFailure("203.0.113.90", "slow", 1234, "FRC"))
    }

    @Test
    fun `a hostile address must pass a security check to sign in`() {
        val ip = "203.0.113.95"
        repeat(3) { SecurityEvents.emit(SecurityEvent(SecurityEventType.INJECTION_PROBE, ip)) }
        val denied = assertFailsWith<SecurityDeniedException> { LoginGuard.check(ip, "someone", 1234, "FRC", null) }
        assertEquals(428, denied.status.value)
        val challenge = assertNotNull(denied.body.challenge)
        LoginGuard.check(ip, "someone", 1234, "FRC", "${challenge.token}:${ProofOfWork.solve(challenge.nonce, challenge.difficulty)}")

        SecuritySettings.setConfigForTests(SecurityConfig(mode = "observe"))
        LoginGuard.check(ip, "someone", 1234, "FRC", null)
    }

    // ------------------------------------------------------------------ detectors

    @Test
    fun `scanner paths are recognized`() {
        listOf("/.env", "/wp-login.php", "/admin/config.php", "/.git/config", "/phpmyadmin/index.html", "/cgi-bin/x", "/actuator/health")
            .forEach { assertTrue(SecurityMonitor.isScannerPath(it), it) }
        listOf("/dashboard", "/api/teams", "/js/app.js", "/scout", "/assets/images/obsidian/obsidian-192.png", "/config")
            .forEach { assertFalse(SecurityMonitor.isScannerPath(it), it) }
    }

    @Test
    fun `attack strings in URLs are recognized without flagging ordinary queries`() {
        listOf(
            "/api/teams?id=1%27%20OR%20%271%27%3D%271", "/api/x?q=1' or 1=1--", "/api/x?q=%3Cscript%3Ealert(1)%3C/script%3E",
            "/api/x?u=\${jndi:ldap://x}", "/api/files?path=../../etc/passwd", "/api/x?q=1 UNION SELECT password FROM users"
        ).forEach { assertTrue(SecurityMonitor.hasInjectionPayload(it), it) }
        listOf(
            "/api/teams?q=Bob's%20robot", "/api/assistant?q=what+is+team+254's+or+1678's+best+auto", "/js/app.js?v=0.7.1.8",
            "/api/matches?event=2026txhou&team=118", "/api/x?q=it's%20and%20that"
        ).forEach { assertFalse(SecurityMonitor.hasInjectionPayload(it), it) }
    }

    @Test
    fun `ip ranges match only literal addresses inside them`() {
        val range = assertNotNull(IpRange.parse("203.0.113.0/24"))
        assertTrue(range.contains("203.0.113.255"))
        assertFalse(range.contains("203.0.114.1"))
        assertTrue(assertNotNull(IpRange.parse("2001:db8::/32")).contains("2001:db8:1::5"))
        assertNull(IpRange.parse("example.com"))
        assertNull(IpRange.parse("0.0.0.0/0"), "too wide")
        assertNull(IpRange.parse("10.0.0.0/7"), "too wide")
        assertEquals("203.0.113.0/24", IpRange.parse("203.0.113.77/24")?.text)
    }

    @Test
    fun `user supplied fields are capped and stripped of control characters`() {
        val clean = SecurityEvents.sanitize(
            SecurityEvent(SecurityEventType.AUTH_FAILURE, "1.2.3.4", username = "evil\nname\u0000" + "x".repeat(500), path = "/a\r\nb")
        )
        assertEquals(128, clean.username!!.length)
        assertFalse(clean.username!!.any { it.isISOControl() })
        assertEquals("/a  b", clean.path)
    }

    @Test
    fun `a session that switches operating system or client kind is a conflict, an IP change is not`() {
        val windows = SessionWatch.fingerprint("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130")
        val android = SessionWatch.fingerprint("Mozilla/5.0 (Linux; Android 14) Chrome/130")
        val app = SessionWatch.fingerprint("Dart/3.5 (dart:io)", mobileApp = true)
        val cronet = SessionWatch.fingerprint("Cronet/120 Android", mobileApp = true)
        assertTrue(windows.conflictsWith(android))
        assertTrue(windows.conflictsWith(app))
        assertFalse(app.conflictsWith(cronet), "the app's two HTTP stacks are the same client")
        assertFalse(windows.conflictsWith(SessionWatch.fingerprint("Mozilla/5.0 (Windows NT 10.0) Firefox/130")))
    }

    @Test
    fun `alerts are rate limited per type and subject, unblock requests are not`() {
        repeat(3) { SecurityEvents.emit(SecurityEvent(SecurityEventType.CROSS_TEAM_ACCESS, "203.0.113.120", username = "u")) }
        assertEquals(1, SecurityTestSupport.alerts.count { it.second.type == SecurityEventType.CROSS_TEAM_ACCESS })
        repeat(2) { SecurityEvents.emit(SecurityEvent(SecurityEventType.UNBLOCK_REQUESTED, "203.0.113.121", username = "p")) }
        assertEquals(2, SecurityTestSupport.alerts.count { it.second.type == SecurityEventType.UNBLOCK_REQUESTED })
    }
}
