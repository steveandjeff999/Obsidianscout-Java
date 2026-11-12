package com.obsidianscout.integrations

import com.obsidianscout.cache.RedisCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Year
import java.util.concurrent.ConcurrentHashMap

/** One event in the settings page's "Don't know your event code?" picker. */
@Serializable
data class EventCatalogEntry(
    /** The code to put in the event code field (TBA's for FRC, FTC Scout's for FTC). */
    val code: String,
    /** FIRST's code when it differs from [code] (e.g. TBA "arc", FIRST "ARCHIMEDES"); blank otherwise. */
    val altCode: String = "",
    val name: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val location: String = "",
    val week: Int? = null,
    val type: String = ""
)

@Serializable
data class EventCatalog(
    val program: String,
    val year: Int,
    /** Epoch millis of the fetch these events came from. */
    val fetchedAt: Long,
    val events: List<EventCatalogEntry>
)

/**
 * Every event of a season for the settings event picker, per program and year.
 *
 * Lists are fetched with the server's shared API keys ([SharedApiKeyService]); a team without one still gets a
 * list from its own keys when nothing is shared. FTC uses FTC Scout, which needs no key. A list is kept in memory
 * and in Redis, so every node shares one copy. The sync leader refreshes the current seasons, and any season
 * someone looked at lately, every [REFRESH_AFTER_MS]; a stale list is still served while it refreshes, so the
 * picker never waits on the APIs once a season has been fetched. The HTTP layer revalidates with ETags, so an
 * unchanged list costs a 304.
 */
object EventCatalogService {
    private val log = LoggerFactory.getLogger("EventCatalogService")

    internal const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L
    private const val LOOP_MS = 30 * 60 * 1000L
    private const val FIRST_RUN_DELAY_MS = 3 * 60 * 1000L

    /** Long enough to outlive restarts and API outages; freshness is decided by [EventCatalog.fetchedAt]. */
    private const val REDIS_TTL_SECONDS = 14 * 24 * 60 * 60L

    /** A season someone opened in the picker keeps being refreshed for this long. */
    private const val REQUESTED_YEAR_TTL_MS = 3 * 24 * 60 * 60 * 1000L

    /** After a failed fetch, wait this long before the next try for that season. */
    private const val RETRY_AFTER_FAILURE_MS = 10 * 60 * 1000L

    private val memory = ConcurrentHashMap<String, EventCatalog>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val requestedYears = ConcurrentHashMap<String, Long>()

    private var scope: CoroutineScope? = null

    private fun normalizeProgram(program: String) = if (program.equals("FTC", ignoreCase = true)) "FTC" else "FRC"
    private fun key(program: String, year: Int) = "${normalizeProgram(program)}:$year"
    private fun redisKey(program: String, year: Int) = "obsidian:event-catalog:${key(program, year)}"

    private fun EventCatalog.isFresh(now: Long = System.currentTimeMillis()) = now - fetchedAt < REFRESH_AFTER_MS

    /** The newest copy this node can see, from memory or Redis. */
    private fun cached(program: String, year: Int): EventCatalog? {
        val k = key(program, year)
        val local = memory[k]
        if (local != null && local.isFresh()) return local
        val remote = RedisCache.readFromRedis<EventCatalog>(redisKey(program, year), countStats = false)
        val newest = listOfNotNull(local, remote).maxByOrNull { it.fetchedAt } ?: return null
        memory[k] = newest
        return newest
    }

    /**
     * The event list for [program] and [year]. [teamKeys] (the asking team's effective keys) fill in for any shared
     * key that isn't set. Returns null when no list can be had: no key for FRC, or the first fetch failed.
     */
    suspend fun get(program: String, year: Int, teamKeys: ApiKeys? = null): EventCatalog? {
        val prog = normalizeProgram(program)
        requestedYears[key(prog, year)] = System.currentTimeMillis()
        val current = cached(prog, year)
        if (current != null) {
            if (!current.isFresh()) refreshInBackground(prog, year, teamKeys)
            return current
        }
        return refresh(prog, year, teamKeys)
    }

    /** Fetches [program]/[year] now, unless another caller just did. Returns the newest list, or null. */
    suspend fun refresh(program: String, year: Int, teamKeys: ApiKeys? = null, force: Boolean = false): EventCatalog? {
        val prog = normalizeProgram(program)
        val k = key(prog, year)
        return locks.computeIfAbsent(k) { Mutex() }.withLock {
            val existing = cached(prog, year)
            if (!force && existing != null && existing.isFresh()) return@withLock existing
            val lastFailure = failedAt[k]
            if (!force && lastFailure != null && System.currentTimeMillis() - lastFailure < RETRY_AFTER_FAILURE_MS) {
                return@withLock existing
            }
            val events = try {
                fetch(prog, year, teamKeys)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failedAt[k] = System.currentTimeMillis()
                log.warn("Event list for $prog $year couldn't be fetched: ${e.message}")
                return@withLock existing
            }
            if (events == null) return@withLock existing
            failedAt.remove(k)
            val catalog = EventCatalog(prog, year, System.currentTimeMillis(), events)
            memory[k] = catalog
            RedisCache.writeToRedis(redisKey(prog, year), REDIS_TTL_SECONDS, catalog)
            if (existing == null || existing.events.size != events.size) {
                log.info("Event list for $prog $year: ${events.size} events")
            }
            catalog
        }
    }

    private suspend fun fetch(program: String, year: Int, teamKeys: ApiKeys?): List<EventCatalogEntry>? {
        if (program == "FTC") return FtcIntegrationService.fetchEventCatalog(year)
        val shared = SharedApiKeyService.read(program)
        val keys = if (teamKeys != null) SharedApiKeyService.fillBlanks(program, shared, teamKeys) else shared
        return IntegrationService.fetchEventCatalog(ApiSettings(year = year, program = program, apiKeys = keys))
    }

    private fun refreshInBackground(program: String, year: Int, teamKeys: ApiKeys?) {
        val active = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        active.launch {
            runCatching { refresh(program, year, teamKeys) }
                .onFailure { log.debug("Background event list refresh for $program $year failed: ${it.message}") }
        }
    }

    /** New shared keys: forget past failures and refetch [program]'s current seasons with them. */
    fun onSharedKeysChanged(program: String) {
        val prog = normalizeProgram(program)
        failedAt.keys.removeIf { it.startsWith("$prog:") }
        seasonsToRefresh().filter { it.first == prog }.forEach { (p, y) ->
            val active = scope ?: return@forEach
            active.launch { runCatching { refresh(p, y, force = true) } }
        }
    }

    /** The seasons kept current: this year's and next year's for FRC, last and this year's for FTC (fall start). */
    internal fun seasonsToRefresh(now: Long = System.currentTimeMillis()): Set<Pair<String, Int>> {
        val year = Year.now().value
        val seasons = mutableSetOf("FRC" to year, "FRC" to year + 1, "FTC" to year - 1, "FTC" to year)
        requestedYears.entries.removeIf { now - it.value > REQUESTED_YEAR_TTL_MS }
        requestedYears.keys.forEach { k ->
            val (prog, y) = k.split(":")
            y.toIntOrNull()?.let { seasons += prog to it }
        }
        return seasons
    }

    /** Starts the periodic refresh. Every node runs the loop; only the sync leader fetches. */
    fun start() {
        if (scope != null) return
        val job = SupervisorJob()
        scope = CoroutineScope(job + Dispatchers.IO).also { s ->
            s.launch {
                delay(FIRST_RUN_DELAY_MS)
                while (isActive) {
                    if (SyncLeadership.isLeader()) {
                        for ((program, year) in seasonsToRefresh()) {
                            try {
                                refresh(program, year)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("Scheduled event list refresh for $program $year failed: ${e.message}")
                            }
                        }
                    }
                    delay(LOOP_MS)
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }
}
