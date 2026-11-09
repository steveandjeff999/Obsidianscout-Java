package com.obsidianscout.security

import com.obsidianscout.config.SecurityConfig
import com.obsidianscout.db.DatabaseFactory

/** Puts every part of the security layer into a clean, synchronous, Redis-free state. */
object SecurityTestSupport {
    val events = java.util.concurrent.CopyOnWriteArrayList<SecurityEvent>()
    val alerts = java.util.concurrent.CopyOnWriteArrayList<Pair<String, SecurityEvent>>()
    private val listener: (SecurityEvent) -> Unit = { events.add(it) }

    fun reset(config: SecurityConfig = SecurityConfig(mode = "enforce"), closeDatabase: Boolean = true) {
        if (closeDatabase && DatabaseFactory.isReady) DatabaseFactory.close()
        SecurityStore.redisEnabled = false
        SecurityStore.resetForTests()
        SecuritySettings.setConfigForTests(config)
        SecurityEvents.synchronousForTests = true
        SecurityEvents.removeListener(listener)
        SecurityEvents.addListener(listener)
        SecurityAlerter.sink = { title, _, event -> alerts.add(title to event) }
        LoginGuard.delaysEnabled = false
        BlockList.clearForTests()
        Allowlist.clearForTests()
        UnblockRequests.clearForTests()
        ThreatEngine.clearForTests()
        SessionWatch.clearForTests()
        SecurityLog.clearForTests()
        events.clear()
        alerts.clear()
    }

    fun types(): List<SecurityEventType> = events.map { it.type }

    /** Adds enough score to push [ip] over the block threshold. */
    fun makeHostile(ip: String) {
        repeat(5) { SecurityEvents.emit(SecurityEvent(SecurityEventType.INJECTION_PROBE, ip, details = "test")) }
    }
}
