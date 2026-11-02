package com.obsidianscout.cache

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RedisCacheTest {

    @Test
    fun testTransparentFallbackWhenNotReady() = runBlocking {
        var loaderExecuted = false
        val result = RedisCache.getOrSet("test:key:fallback", 60L) {
            loaderExecuted = true
            "fallback_value"
        }

        assertEquals("fallback_value", result)
        assertEquals(true, loaderExecuted, "Loader must execute when Redis is not ready")
    }

    @Test
    fun testEventVersionDefault() {
        val version = RedisCache.getEventVersion("2026testevent")
        assertEquals(1L, version, "Default event version should be 1L when uninitialized")
    }
}
