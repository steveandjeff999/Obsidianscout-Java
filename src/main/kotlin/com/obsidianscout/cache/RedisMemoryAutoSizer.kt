package com.obsidianscout.cache

import com.obsidianscout.config.RedisConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.management.ManagementFactory

object RedisMemoryAutoSizer {
    private val log = LoggerFactory.getLogger("com.obsidianscout.cache.RedisMemoryAutoSizer")

    data class MemoryAssessment(
        val totalOsMb: Long,
        val availableOsMb: Long,
        val jvmMaxMb: Long,
        val recommendedRedisMb: Long,
        val evictionPolicy: String = "allkeys-lru"
    )

    fun calculateOptimalMaxMemory(config: RedisConfig = RedisConfig()): MemoryAssessment {
        val totalOsBytes = getSystemTotalMemory()
        val availableOsBytes = getSystemAvailableMemory()
        val jvmMaxBytes = Runtime.getRuntime().maxMemory()

        val totalOsMb = (totalOsBytes / (1024 * 1024)).coerceAtLeast(128L)
        val availableOsMb = (availableOsBytes / (1024 * 1024)).coerceAtLeast(64L)
        val jvmMaxMb = (jvmMaxBytes / (1024 * 1024)).coerceAtLeast(64L)

        // Minimum reservations for host OS and Database
        val osReserveMb = Math.max(1024L, (totalOsMb * 0.15).toLong())
        val dbReserveMb = 768L

        val safeCeilingMb = (totalOsMb - (jvmMaxMb + osReserveMb + dbReserveMb)).coerceAtLeast(config.min_memory_mb)
        val opportunisticMb = (availableOsMb * config.max_memory_percent).toLong()

        val calculatedMb = Math.min(opportunisticMb, safeCeilingMb)
        val boundedMb = calculatedMb.coerceIn(config.min_memory_mb, config.max_memory_mb)

        log.info(
            "[RedisMemoryAutoSizer] Detected RAM: Total={}MB, Avail={}MB, JVM={}MB -> Sizing Redis maxmemory to {}MB ({})",
            totalOsMb, availableOsMb, jvmMaxMb, boundedMb, "allkeys-lru"
        )

        return MemoryAssessment(
            totalOsMb = totalOsMb,
            availableOsMb = availableOsMb,
            jvmMaxMb = jvmMaxMb,
            recommendedRedisMb = boundedMb
        )
    }

    private fun getSystemTotalMemory(): Long {
        return try {
            val osBean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)
            osBean.totalMemorySize
        } catch (_: Throwable) {
            Runtime.getRuntime().totalMemory() * 2
        }
    }

    private fun getSystemAvailableMemory(): Long {
        // On Linux, cross-verify with /proc/meminfo for accurate MemAvailable
        try {
            val memInfo = File("/proc/meminfo")
            if (memInfo.exists()) {
                val line = memInfo.useLines { lines ->
                    lines.firstOrNull { it.startsWith("MemAvailable:") }
                }
                if (line != null) {
                    val parts = line.split("\\s+".toRegex())
                    val kb = parts.getOrNull(1)?.toLongOrNull()
                    if (kb != null) return kb * 1024L
                }
            }
        } catch (_: Throwable) {}

        return try {
            val osBean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)
            osBean.freeMemorySize
        } catch (_: Throwable) {
            Runtime.getRuntime().freeMemory()
        }
    }
}
