package com.obsidianscout.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.math.max

/**
 * Dedicated concurrency-limited dispatcher for CPU-intensive cryptographic operations (BCrypt).
 *
 * BCrypt is pure CPU-bound work with no I/O. By isolating it to a dedicated dispatcher with
 * bounded parallelism dynamically scaled to the machine's available CPU cores, we allow
 * high-throughput authentication on multi-core servers while safeguarding Netty network threads,
 * WebSocket broadcasts, and database query processing against CPU starvation.
 */
object CryptoDispatcher {
    val parallelism: Int = computeParallelism()

    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(parallelism)

    /**
     * Computes the crypto parallelism based on available cores or an optional env override.
     *
     * - Small machines (<= 4 cores): reserves 1 core for Netty/IO.
     * - Larger machines (> 4 cores): reserves ~25% of cores (minimum 2) for Netty/IO/DB,
     *   allowing the remaining cores to scale auth throughput linearly.
     */
    internal fun computeParallelism(
        availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        envOverride: String? = System.getenv("OBSIDIANSCOUT_CRYPTO_THREADS")
    ): Int {
        val configured = envOverride?.toIntOrNull()
        if (configured != null && configured > 0) {
            return configured
        }

        val reserved = if (availableProcessors <= 4) 1 else max(2, availableProcessors / 4)
        return max(1, availableProcessors - reserved)
    }
}
