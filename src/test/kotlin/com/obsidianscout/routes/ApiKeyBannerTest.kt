package com.obsidianscout.routes

import com.obsidianscout.integrations.ApiKeys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [apiKeyBanner]: which banner a team sees about its API keys. */
class ApiKeyBannerTest {

    private val noKeys = ApiKeys()
    private val tbaOnly = ApiKeys(tbaKey = "tba")
    private val firstOnly = ApiKeys(firstUsername = "user", firstKey = "first")

    private fun banner(program: String, keys: ApiKeys, shared: suspend () -> Boolean): BannerDto? =
        runBlocking { apiKeyBanner(1234, program, "2026txhou", keys, shared) }

    @Test
    fun teamWithOwnCredentialsGetsNoBanner() {
        assertNull(banner("FRC", tbaOnly) { error("must not be checked") })
        assertNull(banner("FRC", firstOnly) { error("must not be checked") })
        assertNull(banner("FTC", firstOnly) { error("must not be checked") })
    }

    @Test
    fun frcWithoutKeyAndNoSharingGetsWarning() {
        val b = assertNotNull(banner("FRC", noKeys) { false })
        assertEquals("sys-no-api-key", b.id)
        assertEquals("warning", b.bannerType)
        assertTrue(b.message.contains("External data syncing"))
        assertFalse(b.isExpandable)
        assertEquals(1234, b.teamNumber)
    }

    @Test
    fun frcWithoutKeyAndSharingGetsInfo() {
        val b = assertNotNull(banner("FRC", noKeys) { true })
        assertEquals("sys-shared-event-data", b.id)
        assertEquals("info", b.bannerType)
        assertTrue(b.message.startsWith("Teams and matches for this event are being shared by another team"))
        assertTrue(b.isExpandable)
        assertTrue(b.expandableMessage.isNotBlank())
    }

    @Test
    fun ftcWithoutFirstKeyAndNoSharingGetsFtcWarning() {
        val b = assertNotNull(banner("FTC", noKeys) { false })
        assertEquals("sys-no-api-key", b.id)
        assertEquals("warning", b.bannerType)
        assertTrue(b.message.contains("FTC Scout only"))
    }

    @Test
    fun ftcWithOnlyTbaKeyStillGetsFtcWarning() {
        val b = assertNotNull(banner("FTC", tbaOnly) { false })
        assertEquals("sys-no-api-key", b.id)
        assertTrue(b.message.contains("FIRST API key"))
    }

    @Test
    fun ftcWithoutFirstKeyAndSharingGetsFtcInfo() {
        val b = assertNotNull(banner("FTC", noKeys) { true })
        assertEquals("sys-shared-event-data", b.id)
        assertEquals("info", b.bannerType)
        assertTrue(b.message.startsWith("FIRST API teams and matches"))
        assertTrue(b.expandableMessage.contains("FTC Scout"))
    }

    @Test
    fun ftcBannersAreMarkedFtc() {
        assertEquals("FTC", assertNotNull(banner("FTC", noKeys) { true }).program)
        assertEquals("FRC", assertNotNull(banner("FRC", noKeys) { true }).program)
    }

    @Test
    fun failedCheckFallsBackToWarning() {
        val b = assertNotNull(banner("FRC", noKeys) { throw IllegalStateException("test failure") })
        assertEquals("sys-no-api-key", b.id)
        assertEquals("warning", b.bannerType)
    }

    @Test
    fun quorumLossIsLeftForTheCallersBanner() {
        assertFailsWith<IllegalStateException> {
            banner("FRC", noKeys) { throw IllegalStateException("range unavailable") }
        }
    }
}
