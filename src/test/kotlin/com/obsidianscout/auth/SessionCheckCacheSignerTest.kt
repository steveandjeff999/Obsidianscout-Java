package com.obsidianscout.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionCheckCacheSignerTest {
    private val key = "obsidian:auth:session:11111111-1111-1111-1111-111111111111"
    private val scout = UserSession(
        userId = "22222222-2222-2222-2222-222222222222", username = "scout", teamNumber = 1234,
        role = UserRole.SCOUT, sessionId = "11111111-1111-1111-1111-111111111111"
    )

    @Test
    fun signedEntryVerifies() {
        val signed = assertNotNull(SessionCheckCacheSigner.sign(key, CachedSessionCheck(scout, false, 7)))
        assertTrue(SessionCheckCacheSigner.verify(key, signed))
    }

    @Test
    fun unsignedOrTamperedEntryIsRejected() {
        val signed = assertNotNull(SessionCheckCacheSigner.sign(key, CachedSessionCheck(scout, false, 7)))
        assertFalse(SessionCheckCacheSigner.verify(key, CachedSessionCheck(scout.copy(role = UserRole.SUPERADMIN), false, 7)))
        assertFalse(SessionCheckCacheSigner.verify(key, signed.copy(session = scout.copy(role = UserRole.SUPERADMIN))))
        assertFalse(SessionCheckCacheSigner.verify(key, signed.copy(authVersion = 8)))
        assertFalse(SessionCheckCacheSigner.verify(key, signed.copy(keepMeLoggedIn = true)))
    }

    @Test
    fun entryCannotMoveToAnotherSessionKey() {
        val signed = assertNotNull(SessionCheckCacheSigner.sign(key, CachedSessionCheck(scout, false, 7)))
        assertFalse(SessionCheckCacheSigner.verify("obsidian:auth:session:other", signed))
    }
}
