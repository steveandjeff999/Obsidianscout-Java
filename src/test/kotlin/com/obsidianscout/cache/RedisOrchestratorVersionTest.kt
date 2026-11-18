package com.obsidianscout.cache

import com.obsidianscout.cache.orchestration.RedisOrchestrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedisOrchestratorVersionTest {
    @Test
    fun `parseRedisVersion parses standard redis-server --version output`() {
        assertEquals(
            Triple(7, 0, 15),
            RedisOrchestrator.parseRedisVersion("Redis server v=7.0.15 sha=00000000:0 malloc=jemalloc bits=64 build=abc")
        )
        assertEquals(
            Triple(7, 4, 2),
            RedisOrchestrator.parseRedisVersion("Redis server v=7.4.2 sha=00000000:0 malloc=jemalloc bits=64 build=def")
        )
        assertEquals(
            Triple(8, 0, 1),
            RedisOrchestrator.parseRedisVersion("Redis server v=8.0.1 sha=00000000:0 malloc=jemalloc bits=64 build=123")
        )
        assertNull(RedisOrchestrator.parseRedisVersion("not a redis version"))
    }

    @Test
    fun `isVersionSupported requires at least 7_4_0 for RDB v12`() {
        assertFalse(RedisOrchestrator.isVersionSupported(Triple(6, 2, 14)))
        assertFalse(RedisOrchestrator.isVersionSupported(Triple(7, 0, 15)))
        assertFalse(RedisOrchestrator.isVersionSupported(Triple(7, 2, 5)))
        assertTrue(RedisOrchestrator.isVersionSupported(Triple(7, 4, 0)))
        assertTrue(RedisOrchestrator.isVersionSupported(Triple(7, 4, 2)))
        assertTrue(RedisOrchestrator.isVersionSupported(Triple(8, 0, 0)))
    }
}
