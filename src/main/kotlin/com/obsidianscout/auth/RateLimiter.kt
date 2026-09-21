package com.obsidianscout.auth

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object LoginRateLimiter {

    private data class AttemptWindow(
        val count: AtomicInteger = AtomicInteger(0),
        val windowStart: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    private val ipAttempts = ConcurrentHashMap<String, AttemptWindow>()
    private val usernameAttempts = ConcurrentHashMap<String, AttemptWindow>()

    private const val MAX_ATTEMPTS = 10
    private const val WINDOW_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null

    /**
     * Checks whether the given IP and username are allowed to attempt login.
     * Returns true if allowed, false if rate limited.
     */
    fun checkAllowed(ip: String, username: String): Boolean {
        val now = System.currentTimeMillis()

        if (ip.isNotBlank()) {
            val ipWindow = ipAttempts.computeIfAbsent(ip) { AttemptWindow(AtomicInteger(0), AtomicLong(now)) }
            if (now - ipWindow.windowStart.get() > WINDOW_MS) {
                ipWindow.windowStart.set(now)
                ipWindow.count.set(0)
            }
            if (ipWindow.count.get() >= MAX_ATTEMPTS) {
                return false
            }
        }

        if (username.isNotBlank()) {
            val userKey = username.trim().lowercase()
            val userWindow = usernameAttempts.computeIfAbsent(userKey) { AttemptWindow(AtomicInteger(0), AtomicLong(now)) }
            if (now - userWindow.windowStart.get() > WINDOW_MS) {
                userWindow.windowStart.set(now)
                userWindow.count.set(0)
            }
            if (userWindow.count.get() >= MAX_ATTEMPTS) {
                return false
            }
        }

        return true
    }

    /**
     * Records a failed attempt for the given IP and username.
     */
    fun recordFailedAttempt(ip: String, username: String) {
        val now = System.currentTimeMillis()

        if (ip.isNotBlank()) {
            val ipWindow = ipAttempts.computeIfAbsent(ip) { AttemptWindow(AtomicInteger(0), AtomicLong(now)) }
            if (now - ipWindow.windowStart.get() > WINDOW_MS) {
                ipWindow.windowStart.set(now)
                ipWindow.count.set(0)
            }
            ipWindow.count.incrementAndGet()
        }

        if (username.isNotBlank()) {
            val userKey = username.trim().lowercase()
            val userWindow = usernameAttempts.computeIfAbsent(userKey) { AttemptWindow(AtomicInteger(0), AtomicLong(now)) }
            if (now - userWindow.windowStart.get() > WINDOW_MS) {
                userWindow.windowStart.set(now)
                userWindow.count.set(0)
            }
            userWindow.count.incrementAndGet()
        }
    }

    /**
     * Clears recorded attempts on successful authentication.
     */
    fun clear(ip: String, username: String) {
        if (ip.isNotBlank()) {
            ipAttempts.remove(ip)
        }
        if (username.isNotBlank()) {
            usernameAttempts.remove(username.trim().lowercase())
        }
    }

    @Synchronized
    fun startCleanupJob() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch {
            while (isActive) {
                delay(5 * 60_000L) // Cleanup every 5 minutes
                try {
                    val now = System.currentTimeMillis()
                    ipAttempts.entries.removeIf { now - it.value.windowStart.get() > WINDOW_MS * 2 }
                    usernameAttempts.entries.removeIf { now - it.value.windowStart.get() > WINDOW_MS * 2 }
                } catch (_: Exception) {}
            }
        }
    }
}
