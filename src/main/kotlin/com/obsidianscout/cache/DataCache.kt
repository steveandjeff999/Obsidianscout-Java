package com.obsidianscout.cache

import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.DataVersion
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Results computed from stored data, cached in Redis together with the version of every table they read. A write to
 * any of those tables retires the result; writes to other tables leave it be. A cached result is never older than the
 * last write to what it was computed from.
 *
 * Use it only for results that depend on nothing but the database and the arguments in the key (no clock, no
 * outside API calls, no files that change at runtime). Reads from in-memory or Redis caches inside the computation
 * must report their tables with [DataVersion.noteRead].
 */
object DataCache {
    /** A backstop only: results are retired by the versions long before this. */
    @PublishedApi internal const val TTL_SECONDS = 600L

    @Serializable
    data class Entry<T>(val dependencies: Map<String, Long>, val value: T)

    /**
     * [compute]'s result for [name] and [parts], reused until a table it read is written. Uncached while Redis is
     * down. Callers that depend on who is asking pass [scope] so one user's view is never served to another.
     */
    suspend inline fun <reified T> getOrCompute(name: String, vararg parts: Any?, crossinline compute: suspend () -> T): T {
        val key = "obsidian:cache:$name:${hash(parts.joinToString("|") { it.toString() })}"
        RedisCache.readFromRedis<Entry<T>>(key)
            ?.takeIf { DataVersion.stillCurrent(it.dependencies) }
            ?.let { DataVersion.noteDependencies(it.dependencies); return it.value }
        return SingleFlight.execute(key) {
            RedisCache.readFromRedis<Entry<T>>(key, countStats = false)
                ?.takeIf { DataVersion.stillCurrent(it.dependencies) }
                ?.let { DataVersion.noteDependencies(it.dependencies); return@execute it.value }
            // Versions are taken before any data is read, so a write landing mid-computation retires this result.
            val snapshot = DataVersion.snapshot() ?: return@execute compute()
            val failuresBefore = DataVersion.failures.get()
            val tracker = DataVersion.newTracker()
            val fresh = withContext(DataVersion.trackReads(tracker)) { compute() }
            val dependencies = DataVersion.dependencies(tracker, snapshot)
            // A database error during the computation may have been swallowed into a fallback: don't keep that.
            if (dependencies != null && DataVersion.failures.get() == failuresBefore) {
                RedisCache.writeToRedis(key, TTL_SECONDS, Entry(dependencies, fresh))
            }
            fresh
        }
    }

    /**
     * Everything about the caller that can change what they see: identity, team, role, page grants, and the public
     * view's team and event on a public data request.
     */
    fun scope(session: UserSession): String =
        "$session|${com.obsidianscout.publicdata.PublicViewContext.current()}"

    @PublishedApi internal fun hash(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
