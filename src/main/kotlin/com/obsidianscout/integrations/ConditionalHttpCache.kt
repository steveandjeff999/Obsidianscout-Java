package com.obsidianscout.integrations

import java.security.MessageDigest

/**
 * Remembers API response bodies with their ETag / Last-Modified validators, so the next request for the
 * same URL can ask "has this changed?" (If-None-Match / If-Modified-Since). An unchanged answer then comes
 * back as a bodiless 304, which TBA asks clients to use and the FIRST API supports.
 *
 * Entries are keyed by a hash of the credential as well as the URL, so one team's cached answers are never
 * served to another team's key. The cache is bounded by total body size (characters) and evicts least
 * recently used entries first.
 */
internal class ConditionalHttpCache(
    private val maxTotalChars: Long = 16L * 1024 * 1024,
    private val maxEntryChars: Int = 4 * 1024 * 1024
) {
    data class Entry(val etag: String?, val lastModified: String?, val body: String)

    private val entries = LinkedHashMap<String, Entry>(64, 0.75f, true)

    /** How many requests the API answered "unchanged" (304) from this cache. */
    val revalidatedCount = java.util.concurrent.atomic.AtomicLong()
    private var totalChars = 0L

    @Synchronized
    fun get(key: String): Entry? = entries[key]

    @Synchronized
    fun put(key: String, entry: Entry) {
        remove(key)
        // Nothing to revalidate with, or too big to be worth holding.
        if (entry.etag.isNullOrBlank() && entry.lastModified.isNullOrBlank()) return
        if (entry.body.length > maxEntryChars) return
        entries[key] = entry
        totalChars += entry.body.length
        val iterator = entries.entries.iterator()
        while (totalChars > maxTotalChars && iterator.hasNext()) {
            totalChars -= iterator.next().value.body.length
            iterator.remove()
        }
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)?.let { totalChars -= it.body.length }
    }

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        /** A short, one-way tag for a credential: enough to keep keys apart without holding the secret. */
        fun credentialTag(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
