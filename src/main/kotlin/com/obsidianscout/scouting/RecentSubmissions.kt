package com.obsidianscout.scouting

import com.obsidianscout.auth.UserSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which entry each recent upload created, keyed by the id the device gave it (`clientId`).
 *
 * A device that uploads, doesn't hear back in time, and sends the same entry again from its offline queue gets the
 * entry it already created instead of a second copy, even if the two copies don't compare equal field by field.
 * The content comparison in each service still catches retries this node hasn't seen (after a restart, or on another
 * cluster node).
 */
object RecentSubmissions {
    private const val TTL_MS = 24L * 60 * 60 * 1000
    private const val MAX_ENTRIES = 20_000
    private const val MAX_CLIENT_ID_LENGTH = 128

    private data class Seen(val entryId: String, val at: Long)

    private val seen = ConcurrentHashMap<String, Seen>()

    private fun key(kind: String, session: UserSession, clientId: String): String =
        "$kind|${session.program}|${session.teamNumber}|$clientId"

    private fun usable(clientId: String?): String? =
        clientId?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_CLIENT_ID_LENGTH }

    /** The entry an earlier upload with this clientId created, if this node saw it recently. */
    fun find(kind: String, session: UserSession, clientId: String?, now: Long = System.currentTimeMillis()): String? {
        val id = usable(clientId) ?: return null
        val k = key(kind, session, id)
        val hit = seen[k] ?: return null
        if (now - hit.at > TTL_MS) {
            seen.remove(k, hit)
            return null
        }
        return hit.entryId
    }

    fun remember(kind: String, session: UserSession, clientId: String?, entryId: String, now: Long = System.currentTimeMillis()) {
        val id = usable(clientId) ?: return
        seen[key(kind, session, id)] = Seen(entryId, now)
        if (seen.size > MAX_ENTRIES) prune(now)
    }

    private fun prune(now: Long) {
        seen.entries.removeIf { now - it.value.at > TTL_MS }
        if (seen.size > MAX_ENTRIES) {
            // Still too many: drop the oldest quarter.
            seen.entries.sortedBy { it.value.at }.take(seen.size / 4).forEach { seen.remove(it.key, it.value) }
        }
    }

    /** For tests. */
    internal fun clear() = seen.clear()
}
