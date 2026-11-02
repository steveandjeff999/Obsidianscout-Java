package com.obsidianscout.cache

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * SingleFlight prevents cache stampedes and thundering herds.
 * When multiple concurrent coroutines request the same key, only the leader coroutine
 * executes [block], while concurrent coroutines suspend on [CompletableDeferred.await]
 * and share the single result.
 *
 * Implements reference-counted auto-pruning with outer try/finally protection so that
 * coroutine cancellations (e.g. client drops HTTP connection) never leak memory or leave
 * orphaned waiter counts.
 */
object SingleFlight {
    private class Call<T> {
        val deferred = CompletableDeferred<T>()
        var waiters = 0
    }

    private val calls = ConcurrentHashMap<String, Call<Any?>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> execute(key: String, block: suspend () -> T): T {
        var isLeader = false
        val call = synchronized(calls) {
            val existing = calls[key] as? Call<T>
            if (existing != null) {
                existing.waiters++
                existing
            } else {
                isLeader = true
                val newCall = Call<T>()
                newCall.waiters = 1
                calls[key] = newCall as Call<Any?>
                newCall
            }
        }

        if (isLeader) {
            try {
                val result = block()
                call.deferred.complete(result)
                return result
            } catch (t: Throwable) {
                call.deferred.completeExceptionally(t)
                throw t
            } finally {
                synchronized(calls) {
                    call.waiters--
                    if (call.waiters == 0) {
                        calls.remove(key, call as Call<Any?>)
                    }
                }
            }
        } else {
            return try {
                call.deferred.await()
            } finally {
                synchronized(calls) {
                    call.waiters--
                    if (call.waiters == 0) {
                        calls.remove(key, call as Call<Any?>)
                    }
                }
            }
        }
    }

    /** For inspection and testing. */
    fun activeKeyCount(): Int = calls.size
}
