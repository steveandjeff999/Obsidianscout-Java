package com.obsidianscout.admin

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class StressStatusResponse(
    val isRunning: Boolean,
    val startedAtEpochMs: Long = 0L,
    val durationSeconds: Long = 60L,
    val remainingSeconds: Long = 0L,
    val message: String = ""
)

object ServerStressTestService {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private var stressJob: Job? = null
    private var startedAtEpochMs: Long = 0L
    private const val MAX_DURATION_MS = 60_000L // Hard limit 1 minute

    // Buffer to hold memory pressure without crashing JVM
    private val memoryPressureBuffers = ConcurrentLinkedQueue<ByteArray>()

    @Synchronized
    fun startStressTest(initiatedBy: String): StressStatusResponse {
        if (isRunning.get()) {
            return getStatus("Stress test is already active.")
        }

        isRunning.set(true)
        startedAtEpochMs = System.currentTimeMillis()
        memoryPressureBuffers.clear()

        ServerLogService.appendLog(
            "WARN",
            "ServerStressTestService",
            "Simulated server overload started by user '$initiatedBy' (hard timeout: 60s)."
        )

        stressJob = scope.launch {
            try {
                // 1. Memory stress: Consume up to ~75% of available heap safely (leaving headroom so the server remains responsive for stop/admin requests)
                val memBean = ManagementFactory.getMemoryMXBean()
                val heap = memBean.heapMemoryUsage
                val maxHeap = if (heap.max > 0) heap.max else heap.committed
                val currentUsed = heap.used
                val targetToAllocate = ((maxHeap * 0.75) - currentUsed).toLong().coerceIn(0L, 2L * 1024 * 1024 * 1024)

                if (targetToAllocate > 0) {
                    val chunkSize = 16 * 1024 * 1024 // 16MB chunks
                    var allocated = 0L
                    while (allocated < targetToAllocate && isRunning.get()) {
                        try {
                            val chunk = ByteArray(chunkSize) { (it % 255).toByte() }
                            memoryPressureBuffers.add(chunk)
                            allocated += chunkSize
                        } catch (_: OutOfMemoryError) {
                            break
                        }
                    }
                }

                // 2. CPU stress: Launch computation loops across CPU cores
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                val cpuJobs = (0 until cores).map { _ ->
                    launch(Dispatchers.Default) {
                        var counter = 0.0
                        while (isActive && isRunning.get()) {
                            // Tight math loop with frequent yielding/interruption checks
                            for (i in 0..10_000) {
                                counter += kotlin.math.sin(i.toDouble()) * kotlin.math.cos(i.toDouble())
                            }
                            if (counter == 42.0) println(counter)
                        }
                    }
                }

                // Wait for either auto-timeout or cancellation
                val t0 = System.currentTimeMillis()
                while (isRunning.get() && (System.currentTimeMillis() - t0) < MAX_DURATION_MS) {
                    delay(500)
                }

                cpuJobs.forEach { it.cancel() }
            } catch (e: Exception) {
                ServerLogService.appendLog("ERROR", "ServerStressTestService", "Stress test error: ${e.message}")
            } finally {
                stopInternal("Completed 60s timeout.")
            }
        }

        return getStatus("Simulated overload started. It will automatically stop in 60 seconds.")
    }

    @Synchronized
    fun stopStressTest(stoppedBy: String): StressStatusResponse {
        if (!isRunning.get()) {
            return getStatus("No stress test is currently running.")
        }
        stopInternal("Manually stopped by user '$stoppedBy'.")
        return getStatus("Stress test stopped successfully.")
    }

    private fun stopInternal(reason: String) {
        if (!isRunning.getAndSet(false)) return

        stressJob?.cancel()
        stressJob = null
        memoryPressureBuffers.clear()

        // Suggest garbage collection to promptly free up allocated memory
        System.gc()

        ServerLogService.appendLog(
            "INFO",
            "ServerStressTestService",
            "Simulated server overload finished ($reason). Memory and CPU stress released."
        )
    }

    fun getStatus(customMessage: String = ""): StressStatusResponse {
        val running = isRunning.get()
        val now = System.currentTimeMillis()
        val elapsed = if (running) (now - startedAtEpochMs).coerceAtLeast(0L) else 0L
        val remaining = if (running) ((MAX_DURATION_MS - elapsed) / 1000L).coerceAtLeast(0L) else 0L

        return StressStatusResponse(
            isRunning = running,
            startedAtEpochMs = if (running) startedAtEpochMs else 0L,
            durationSeconds = 60L,
            remainingSeconds = remaining,
            message = if (customMessage.isNotEmpty()) customMessage else if (running) "Stress test in progress (${remaining}s remaining)" else "Idle"
        )
    }
}