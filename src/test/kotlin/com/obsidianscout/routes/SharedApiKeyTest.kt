package com.obsidianscout.routes

import com.obsidianscout.integrations.ApiKeys
import com.obsidianscout.integrations.SharedApiKeyService
import com.obsidianscout.integrations.SharedApiKeyService.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [SharedApiKeyService] fallback rules and [sharedApiKeyBanner]. */
class SharedApiKeyTest {

    private val shared = ApiKeys(tbaKey = "shared-tba", firstUsername = "shared-user", firstKey = "shared-first", match13Key = "shared-m13")

    @Test
    fun blankKeysAreFilledFromShared() {
        val filled = SharedApiKeyService.fillBlanks("FRC", ApiKeys(), shared)
        assertEquals(shared, filled)
    }

    @Test
    fun ownKeysAlwaysWin() {
        val own = ApiKeys(tbaKey = "own-tba", firstUsername = "me", firstKey = "own-first", match13Key = "own-m13")
        assertEquals(own, SharedApiKeyService.fillBlanks("FRC", own, shared))
    }

    @Test
    fun halfFirstCredentialIsReplacedAsAPair() {
        val filled = SharedApiKeyService.fillBlanks("FRC", ApiKeys(tbaKey = "own", firstUsername = "me"), shared)
        assertEquals("own", filled.tbaKey)
        assertEquals("shared-user", filled.firstUsername)
        assertEquals("shared-first", filled.firstKey)
    }

    @Test
    fun maskedMatch13KeyCountsAsBlank() {
        val filled = SharedApiKeyService.fillBlanks("FRC", ApiKeys(match13Key = "********"), shared)
        assertEquals("shared-m13", filled.match13Key)
    }

    @Test
    fun ftcOnlyGetsFirstCredentials() {
        val filled = SharedApiKeyService.fillBlanks("FTC", ApiKeys(), shared)
        assertEquals("", filled.tbaKey)
        assertEquals("", filled.match13Key)
        assertEquals("shared-user", filled.firstUsername)
        assertEquals("shared-first", filled.firstKey)
    }

    @Test
    fun sourcesUsingSharedOnlyListsSharedOnes() {
        val keys = SharedApiKeyService.fillBlanks("FRC", ApiKeys(tbaKey = "own"), shared)
        assertEquals(listOf(Source.FIRST, Source.MATCH13), SharedApiKeyService.sourcesUsingShared("FRC", keys, shared))
        assertEquals(emptyList(), SharedApiKeyService.sourcesUsingShared("FRC", keys, ApiKeys()))
    }

    @Test
    fun sharedFirstUsernameIsHidden() {
        val effective = SharedApiKeyService.fillBlanks("FRC", ApiKeys(tbaKey = "own"), shared)
        val shown = SharedApiKeyService.hideSharedUsername("FRC", effective, shared)
        assertEquals("", shown.firstUsername)
        assertEquals("shared-first", shown.firstKey, "the key is still there, to be masked by the payload")
        assertEquals("own", shown.tbaKey)
    }

    @Test
    fun ownFirstUsernameIsKept() {
        val own = ApiKeys(firstUsername = "me", firstKey = "own-first")
        assertEquals(own, SharedApiKeyService.hideSharedUsername("FRC", own, shared))
        assertEquals(own, SharedApiKeyService.hideSharedUsername("FTC", own, shared))
    }

    @Test
    fun noBannerWithoutSharedKey() {
        assertNull(sharedApiKeyBanner(1234, "FRC", ApiKeys(tbaKey = "own"), shared) { false })
        assertNull(sharedApiKeyBanner(1234, "FRC", ApiKeys(), ApiKeys()) { false })
    }

    @Test
    fun usingSharedKeyShowsInfoBanner() {
        val b = assertNotNull(sharedApiKeyBanner(1234, "FRC", ApiKeys(tbaKey = "shared-tba"), shared) { false })
        assertEquals("sys-shared-api-key", b.id)
        assertEquals("info", b.bannerType)
        assertTrue(b.message.startsWith("Using a shared API key"))
        assertTrue(b.message.contains("for best results"))
        assertTrue(b.expandableMessage.contains("The Blue Alliance"))
    }

    @Test
    fun rateLimitedSharedKeyShowsWarning() {
        val keys = ApiKeys(tbaKey = "shared-tba", firstUsername = "shared-user", firstKey = "shared-first")
        val b = assertNotNull(sharedApiKeyBanner(1234, "FRC", keys, shared) { it == Source.FIRST })
        assertEquals("sys-shared-api-key-rate-limited", b.id)
        assertEquals("warning", b.bannerType)
        assertTrue(b.message.startsWith("The shared API key reached its rate limit"))
        assertTrue(b.expandableMessage.contains("FIRST API"))
        assertFalse(b.expandableMessage.contains("The Blue Alliance"))
    }

    @Test
    fun rateLimitOnASourceTheTeamDoesNotShareIsIgnored() {
        // Own TBA key; the shared TBA key being limited doesn't affect this team.
        val b = assertNotNull(sharedApiKeyBanner(1234, "FRC", ApiKeys(tbaKey = "own", match13Key = "shared-m13"), shared) { it == Source.TBA })
        assertEquals("sys-shared-api-key", b.id)
    }

    @Test
    fun markedRateLimitIsRemembered() {
        assertFalse(SharedApiKeyService.isRateLimited("FTC", Source.FIRST))
        SharedApiKeyService.markRateLimited("FTC", Source.FIRST, 60)
        assertTrue(SharedApiKeyService.isRateLimited("FTC", Source.FIRST))
        assertFalse(SharedApiKeyService.isRateLimited("FRC", Source.FIRST))
    }
}
