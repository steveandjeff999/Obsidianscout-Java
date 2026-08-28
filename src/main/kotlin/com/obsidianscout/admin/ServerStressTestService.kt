package com.obsidianscout.admin

import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

    private val isRunning = AtomicBoolean(false)
    private var startedAtEpochMs: Long = 0L
    private const val MAX_DURATION_MS = 60_000L // Hard limit 1 minute

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stress-test-timer").apply { isDaemon = true }
    }
    private var scheduledStopTask: ScheduledFuture<*>? = null

    private val cpuThreads = mutableListOf<Thread>()
    private val memoryPressureBuffers = ConcurrentLinkedQueue<ByteArray>()

    @Synchronized
    fun startStressTest(initiatedBy: String): StressStatusResponse {
        val now = System.currentTimeMillis()
        if (isRunning.get()) {
            if (now - startedAtEpochMs >= MAX_DURATION_MS) {
                stopInternal("Auto-stopped expired test")
            } else {
                return getStatus("Stress test is already active.")
            }
        }

        isRunning.set(true)
        startedAtEpochMs = now
        memoryPressureBuffers.clear()
        cpuThreads.clear()

        ServerLogService.appendLog(
            "WARN",
            "ServerStressTestService",
            "Simulated server overload started by user '$initiatedBy' (hard timeout: 60s)."
        )

        // 1. Memory stress: Consume up to ~75% of available heap safely (leaving headroom so server remains responsive)
        try {
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
        } catch (_: Throwable) {
        }

        // 2. CPU stress on dedicated raw daemon threads (prevents coroutine Dispatchers.Default pool starvation)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        for (i in 0 until cores) {
            val thread = Thread({
                var counter = 0.0
                while (isRunning.get()) {
                    for (j in 0..10_000) {
                        counter += kotlin.math.sin(j.toDouble()) * kotlin.math.cos(j.toDouble())
                    }
                    if (counter == 42.0) println(counter)
                }
            }, "stress-cpu-$i").apply {
                isDaemon = true
                start()
            }
            cpuThreads.add(thread)
        }

        // 3. Guaranteed hard stop scheduled via dedicated timer thread
        scheduledStopTask?.cancel(true)
        scheduledStopTask = scheduler.schedule({
            stopInternal("Completed 60s timeout")
        }, MAX_DURATION_MS, TimeUnit.MILLISECONDS)

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

    @Synchronized
    private fun stopInternal(reason: String) {
        if (!isRunning.getAndSet(false)) return

        scheduledStopTask?.cancel(true)
        scheduledStopTask = null

        cpuThreads.clear()
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
        val now = System.currentTimeMillis()
        if (isRunning.get() && (now - startedAtEpochMs >= MAX_DURATION_MS)) {
            stopInternal("Completed 60s timeout")
        }

        val running = isRunning.get()
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