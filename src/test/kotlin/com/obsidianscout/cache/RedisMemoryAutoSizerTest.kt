package com.obsidianscout.cache

import com.obsidianscout.config.RedisConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedisMemoryAutoSizerTest {

    @Test
    fun testOptimalMemoryCalculationBounds() {
        val config = RedisConfig(
            min_memory_mb = 128L,
            max_memory_mb = 4096L,
            max_memory_percent = 0.40
        )

        val assessment = RedisMemoryAutoSizer.calculateOptimalMaxMemory(config)

        assertTrue(assessment.totalOsMb > 0, "Total OS MB should be positive")
        assertTrue(assessment.availableOsMb > 0, "Available OS MB should be positive")
        assertTrue(assessment.recommendedRedisMb in 128L..4096L, "Recommended MB should be clamped within bounds")
        assertEquals("allkeys-lru", assessment.evictionPolicy, "Eviction policy must be allkeys-lru")
    }

    @Test
    fun testFallbackHandlingWhenClamped() {
        val smallConfig = RedisConfig(
            min_memory_mb = 64L,
            max_memory_mb = 128L,
            max_memory_percent = 0.10
        )

        val assessment = RedisMemoryAutoSizer.calculateOptimalMaxMemory(smallConfig)
        assertTrue(assessment.recommendedRedisMb <= 128L)
        assertTrue(assessment.recommendedRedisMb >= 64L)
    }
}
