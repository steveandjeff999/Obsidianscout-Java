package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.readTransaction
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-wide API keys a superadmin sets up for teams that have none of their own. A team's own key always wins,
 * then a key borrowed from an alliance member, then the shared key ([applyFallback]). FTC only uses the FIRST
 * credentials (TBA and Match 13 have no FTC data).
 *
 * The keys live in the settings table under a reserved team number, one row per program, so every change to them
 * retires cached effective settings the same way a team's own settings change does.
 *
 * When a shared key is answered with HTTP 429, [noteResponse] remembers that for a while ([rateLimitedSources]),
 * so teams leaning on it are told to set up their own keys.
 */
object SharedApiKeyService {
    private val log = LoggerFactory.getLogger(SharedApiKeyService::class.java)

    /** The reserved settings row (-1 SMTP, -2 Cloudflared, -3 load balancer, -4 error alerts). */
    internal const val SETTINGS_TEAM = -5

    /** How long a 429 marks a shared key as rate-limited when the API doesn't say (Retry-After). */
    internal const val DEFAULT_RATE_LIMIT_SECONDS = 15 * 60L
    private const val MAX_RATE_LIMIT_SECONDS = 6 * 60 * 60L
    private const val CACHE_MS = 15_000L
    private const val MASK = "********"

    enum class Source(val id: String, val label: String) {
        TBA("tba", "The Blue Alliance"),
        FIRST("first", "FIRST API"),
        MATCH13("match13", "Match 13")
    }

    private fun normalizeProgram(program: String) = if (program.equals("FTC", ignoreCase = true)) "FTC" else "FRC"

    private fun isFtc(program: String) = normalizeProgram(program) == "FTC"

    /** The sources a program uses keys for. */
    fun sourcesFor(program: String): List<Source> =
        if (isFtc(program)) listOf(Source.FIRST) else listOf(Source.TBA, Source.FIRST, Source.MATCH13)

    // ---- Storage ----

    private class Cached(val keys: ApiKeys, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** The shared keys for [program], read from the database. */
    fun read(program: String): ApiKeys {
        val prog = normalizeProgram(program)
        val jsonText = try {
            readTransaction {
                AppSettings.selectAll()
                    .where { (AppSettings.teamNumber eq SETTINGS_TEAM) and (AppSettings.program eq prog) }
                    .limit(1)
                    .firstOrNull()
                    ?.get(AppSettings.settingsJson)
            }
        } catch (e: Exception) {
            if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLossException(e)) throw e
            log.warn("Couldn't read the shared API keys for $prog: ${e.message}")
            null
        }
        val keys = if (jsonText.isNullOrBlank()) {
            ApiKeys()
        } else {
            runCatching { JsonSupport.json.decodeFromString(ApiKeys.serializer(), jsonText) }.getOrElse { ApiKeys() }
        }
        return clean(prog, keys).also { cache[prog] = Cached(it, System.currentTimeMillis()) }
    }

    /** The shared keys for [program], at most [CACHE_MS] old. For per-request checks such as banners. */
    fun current(program: String): ApiKeys {
        val prog = normalizeProgram(program)
        cache[prog]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let { return it.keys }
        return read(prog)
    }

    /** Only the fields [program] uses, trimmed. */
    private fun clean(program: String, keys: ApiKeys): ApiKeys {
        val k = ApiKeys(
            tbaKey = keys.tbaKey.trim(),
            firstUsername = keys.firstUsername.trim(),
            firstKey = keys.firstKey.trim(),
            match13Key = keys.match13Key.trim()
        )
        return if (isFtc(program)) ApiKeys(firstUsername = k.firstUsername, firstKey = k.firstKey) else k
    }

    /** Saves [incoming] as the shared keys for [program]. A masked value ("********") keeps the stored one. */
    fun update(program: String, incoming: ApiKeys): ApiKeys {
        val prog = normalizeProgram(program)
        val existing = read(prog)
        val merged = clean(
            prog,
            ApiKeys(
                tbaKey = if (incoming.tbaKey == MASK) existing.tbaKey else incoming.tbaKey,
                firstUsername = incoming.firstUsername,
                firstKey = if (incoming.firstKey == MASK) existing.firstKey else incoming.firstKey,
                match13Key = if (incoming.match13Key == MASK) existing.match13Key else incoming.match13Key
            )
        )
        val jsonText = JsonSupport.json.encodeToString(ApiKeys.serializer(), merged)
        transaction {
            val row = AppSettings.selectAll()
                .where { (AppSettings.teamNumber eq SETTINGS_TEAM) and (AppSettings.program eq prog) }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                AppSettings.insert {
                    it[AppSettings.teamNumber] = SETTINGS_TEAM
                    it[AppSettings.program] = prog
                    it[settingsJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            } else {
                AppSettings.update({ AppSettings.id eq row[AppSettings.id] }) {
                    it[settingsJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            }
        }
        cache[prog] = Cached(merged, System.currentTimeMillis())
        // A replaced key starts with a clean slate.
        for (source in sourcesFor(prog)) {
            if (keyFor(source, existing) != keyFor(source, merged)) clearRateLimit(prog, source)
        }
        com.obsidianscout.scouting.AllianceService.clearEffectiveSettingsCache()
        SettingsService.clearSyncedEventsCache()
        EventCatalogService.onSharedKeysChanged(prog)
        return merged
    }

    /** The shared keys with secrets masked, for the superadmin settings form. */
    fun masked(program: String): ApiKeys {
        val keys = read(program)
        return keys.copy(
            tbaKey = if (keys.tbaKey.isNotBlank()) MASK else "",
            firstKey = if (keys.firstKey.isNotBlank()) MASK else "",
            match13Key = if (keys.match13Key.isNotBlank()) MASK else ""
        )
    }

    // ---- Fallback ----

    private fun keyFor(source: Source, keys: ApiKeys): String = when (source) {
        Source.TBA -> keys.tbaKey
        Source.FIRST -> if (keys.firstUsername.isNotBlank() && keys.firstKey.isNotBlank()) "${keys.firstUsername}:${keys.firstKey}" else ""
        Source.MATCH13 -> keys.match13Key.takeIf { it != MASK }.orEmpty()
    }.trim()

    /** The sources [program] has a shared key for. */
    fun configuredSources(program: String, shared: ApiKeys = current(program)): List<Source> =
        sourcesFor(program).filter { keyFor(it, shared).isNotBlank() }

    /** [keys] with every blank credential filled from [shared]. */
    fun fillBlanks(program: String, keys: ApiKeys, shared: ApiKeys): ApiKeys {
        if (shared == ApiKeys()) return keys
        var result = keys
        val ftc = isFtc(program)
        if (!ftc && keyFor(Source.TBA, keys).isBlank() && shared.tbaKey.isNotBlank()) {
            result = result.copy(tbaKey = shared.tbaKey)
        }
        if (keyFor(Source.FIRST, keys).isBlank() && keyFor(Source.FIRST, shared).isNotBlank()) {
            result = result.copy(firstUsername = shared.firstUsername, firstKey = shared.firstKey)
        }
        if (!ftc && keyFor(Source.MATCH13, keys).isBlank() && shared.match13Key.isNotBlank()) {
            result = result.copy(match13Key = shared.match13Key)
        }
        return result
    }

    /**
     * [settings] with the shared keys filling in for any it lacks. Only for settings that are used to call the
     * APIs: never save the result, or the shared key would become the team's own. Shared Match 13 keys are never
     * exposed to non-official custom Match 13 base URLs.
     */
    fun applyFallback(settings: ApiSettings): ApiSettings {
        val shared = read(settings.program)
        val scopedShared = if (isOfficialMatch13BaseUrl(settings.match13BaseUrl)) {
            shared
        } else {
            shared.copy(match13Key = "")
        }
        val filled = fillBlanks(settings.program, settings.apiKeys, scopedShared)
        return if (filled == settings.apiKeys) settings else settings.copy(apiKeys = filled)
    }

    /** The sources for which [keys] (a team's effective keys) are the shared ones. */
    fun sourcesUsingShared(program: String, keys: ApiKeys, shared: ApiKeys = current(program)): List<Source> =
        sourcesFor(program).filter { source ->
            val sharedKey = keyFor(source, shared)
            sharedKey.isNotBlank() && keyFor(source, keys) == sharedKey
        }

    /**
     * [keys] (a team's effective keys) as they may be shown to the team: a shared FIRST username is blanked. Secrets
     * are masked elsewhere; this keeps the shared account's username from reaching every team too.
     */
    fun hideSharedUsername(program: String, keys: ApiKeys, shared: ApiKeys = current(program)): ApiKeys =
        if (Source.FIRST in sourcesUsingShared(program, keys, shared)) keys.copy(firstUsername = "") else keys

    // ---- Rate limits ----

    @Serializable
    private data class RateLimitMark(val until: Long)

    private val localRateLimits = ConcurrentHashMap<String, Long>()

    private fun rateLimitKey(program: String, source: Source) = "obsidian:shared-key-rate-limit:${normalizeProgram(program)}:${source.id}"

    /**
     * Call after every response from a keyed API. When [status] is 429 and [credential] (the key, or for FIRST
     * "username:key") is the shared one for [source], the shared key is marked rate-limited.
     */
    fun noteResponse(program: String, source: Source, credential: String, status: Int, retryAfter: String?) {
        if (status != 429) return
        val shared = try { keyFor(source, current(program)) } catch (e: Exception) { "" }
        if (shared.isBlank() || credential.trim() != shared) return
        val seconds = retryAfter?.trim()?.toLongOrNull()?.coerceIn(60L, MAX_RATE_LIMIT_SECONDS) ?: DEFAULT_RATE_LIMIT_SECONDS
        markRateLimited(program, source, seconds)
    }

    internal fun markRateLimited(program: String, source: Source, seconds: Long) {
        val until = System.currentTimeMillis() + seconds * 1000
        val key = rateLimitKey(program, source)
        val wasLimited = isRateLimited(program, source)
        localRateLimits[key] = until
        com.obsidianscout.cache.RedisCache.writeToRedis(key, seconds, RateLimitMark(until))
        if (!wasLimited) {
            log.warn("Shared ${source.label} key for ${normalizeProgram(program)} hit its rate limit; marked for ${seconds}s")
        }
    }

    private fun clearRateLimit(program: String, source: Source) {
        val key = rateLimitKey(program, source)
        localRateLimits.remove(key)
        com.obsidianscout.cache.RedisCache.delete(key)
    }

    fun isRateLimited(program: String, source: Source): Boolean {
        val key = rateLimitKey(program, source)
        val now = System.currentTimeMillis()
        localRateLimits[key]?.let { if (it > now) return true else localRateLimits.remove(key) }
        // Another node may have hit the limit.
        val remote = com.obsidianscout.cache.RedisCache.readFromRedis<RateLimitMark>(key, countStats = false) ?: return false
        if (remote.until <= now) return false
        localRateLimits[key] = remote.until
        return true
    }

    /** The shared-key sources of [program] that are rate-limited right now. */
    fun rateLimitedSources(program: String): List<Source> = sourcesFor(program).filter { isRateLimited(program, it) }
}
