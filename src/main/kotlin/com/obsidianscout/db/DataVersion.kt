package com.obsidianscout.db

import com.obsidianscout.cache.RedisCache
import com.obsidianscout.cache.RedisClusterCoordinator
import com.obsidianscout.cache.RedisPubSubListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Per-table counters that move whenever stored data changes, so a cache that remembers which tables a result was
 * computed from can never serve it once one of those tables has been written.
 *
 * Every write the app makes goes through Exposed, so a global statement interceptor sees each one and, once its
 * transaction commits, bumps the written tables' counters in Redis. The same interceptor records which tables a
 * computation reads (see [ReadTracker]), so cached results depend on exactly what they read: nobody lists tables by
 * hand, and a write to one table leaves results built from others alone.
 *
 * Besides one counter per table there are two cluster-wide ones: [RAW] moves on raw SQL writes (whose tables are
 * unknown), at every node's startup (covering migrations, snapshot restores and bumps lost to a crash), and every
 * result depends on it; [ANY] moves on every write, for results whose reads couldn't all be identified.
 *
 * While Redis is down nothing is cached, so there is nothing to go stale. A bump that fails is retried every second,
 * and until it lands this node caches nothing.
 */
object DataVersion {
    private val log = LoggerFactory.getLogger("DataVersion")

    private const val VERSIONS_KEY = "obsidian:data:versions"
    private const val AUTH_KEY = "obsidian:auth:v"
    const val RAW = "~raw"
    const val ANY = "~any"

    /** Published after each data bump, followed by the tables written; nodes drop in-memory copies of derived data. */
    const val CHANGED_MESSAGE = "obsidian:data-changed"

    /**
     * High-churn tables that no cached result reads: sessions are touched on activity, chat and its read markers move
     * constantly, and the rest are tokens, logs and relay rows. Writes to them move no counter. A cached computation
     * that reads one anyway is never stored (see [ReadTracker.readIgnored]).
     */
    private val ignoredTableNames: Set<String> by lazy {
        listOf(
            UserSessions, LiveInvalidations, ChatMessages, UserChatLastRead, ChatGroups, PushSubscriptions,
            FcmDeviceTokens, PasswordResetTokens, WidgetTokens, ReportedErrors, PasskeyChallenges,
            SharedLinkAccessLogs, ClusterNotificationLocks, SlackNotifications, SyncStatuses, BackupUploads, BackupUploadChunks, BackupExports, BackupExportChunks
        ).map { it.tableName.lowercase() }.toSet()
    }

    private val authTableName: String by lazy { Users.tableName.lowercase() }
    private val sessionTableName: String by lazy { UserSessions.tableName.lowercase() }
    private val settingsTableName: String by lazy { AppSettings.tableName.lowercase() }

    /** Every table name the app knows, for spotting tables inside subqueries and raw SQL. */
    private val knownTableNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var knownTablePattern: Regex? = null

    private fun registerTables(names: Collection<String>) {
        if (knownTableNames.addAll(names)) {
            knownTablePattern = Regex(
                "\\b(" + knownTableNames.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b",
                RegexOption.IGNORE_CASE
            )
        }
    }

    private class Pending {
        val tables = mutableSetOf<String>()
        var raw = false
        var auth = false
        var settings = false
    }

    private val pending: MutableMap<Transaction, Pending> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Rolled-back transactions so far. Code that catches a database error and falls back to defaults would otherwise
     * have that fallback cached, so a result computed while this moved is not stored.
     */
    val failures = java.util.concurrent.atomic.AtomicLong()

    // Owed bumps; until they land this node caches nothing. The startup bump is owed from the start.
    private val owedTables: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var rawBumpOwed = true
    @Volatile private var authBumpOwed = true
    @Volatile private var settingsEvictionOwed = true
    @Volatile private var installed = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * The tables one computation has read. Reads land in the tracker of the coroutine doing them (and every tracker it
     * is nested in), carried across thread hops by [trackReads].
     */
    class ReadTracker internal constructor(private val parent: ReadTracker?) {
        val tables: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** A read whose tables couldn't be identified: the result must depend on every write. */
        @Volatile var unknown = false
        /** A read from a table whose writes move no counter: the result must not be cached at all. */
        @Volatile var readIgnored = false

        internal fun add(names: Collection<String>) {
            var t: ReadTracker? = this
            while (t != null) {
                t.tables.addAll(names)
                if (names.any { it in ignoredTableNames }) t.readIgnored = true
                t = t.parent
            }
        }

        internal fun markUnknown() {
            var t: ReadTracker? = this
            while (t != null) { t.unknown = true; t = t.parent }
        }
    }

    private val currentTracker = ThreadLocal<ReadTracker?>()

    /** A new tracker nested in the current one; install it with [trackReads] or [withTracker]. */
    fun newTracker(): ReadTracker = ReadTracker(currentTracker.get())

    /** Coroutine context that routes this coroutine's reads (on any thread) to [tracker]. */
    fun trackReads(tracker: ReadTracker): CoroutineContext = currentTracker.asContextElement(tracker)

    /** Runs blocking [block] with its reads routed to [tracker]. */
    inline fun <T> withTracker(tracker: ReadTracker, block: () -> T): T {
        val previous = swapTracker(tracker)
        try {
            return block()
        } finally {
            swapTracker(previous)
        }
    }

    @PublishedApi internal fun swapTracker(tracker: ReadTracker?): ReadTracker? {
        val previous = currentTracker.get()
        currentTracker.set(tracker)
        return previous
    }

    /**
     * Tells the running computation it depends on [tables] although it read them from a cache rather than the
     * database. Every cache consulted while computing a cached result must call this on a hit.
     */
    fun noteRead(tables: Collection<String>) {
        currentTracker.get()?.add(tables.map { it.lowercase() })
    }

    fun noteRead(vararg tables: Table) = noteRead(tables.map { it.tableName })

    /** [noteRead] for a cached result's recorded [dependencies], as returned by [dependencies]. */
    fun noteDependencies(dependencies: Map<String, Long>) {
        val tracker = currentTracker.get() ?: return
        if (ANY in dependencies) tracker.markUnknown()
        tracker.add(dependencies.keys.filter { it != ANY && it != RAW })
    }

    private val readOnlyTypes = setOf(StatementType.SELECT, StatementType.SHOW, StatementType.PRAGMA)

    private object Interceptor : GlobalStatementInterceptor {
        override fun beforeExecution(transaction: Transaction, context: StatementContext) {
            try {
                classify(transaction, context)
            } catch (e: Exception) {
                // Never fail a statement over bookkeeping; assume the worst instead.
                currentTracker.get()?.markUnknown()
                runCatching { mark(transaction) { raw = true; auth = true; settings = true } }
                    .onFailure { rawBumpOwed = true; authBumpOwed = true; settingsEvictionOwed = true }
            }
        }

        private fun classify(transaction: Transaction, context: StatementContext) {
            val statement = context.statement
            val targets = statement.targets.map { it.tableName.lowercase() }
            if (targets.isNotEmpty()) registerTables(targets)

            if (statement.type in readOnlyTypes) {
                val tracker = currentTracker.get() ?: return
                // Targets miss tables in subqueries, so the SQL itself is searched for every known table too.
                val inSql = knownTablePattern?.findAll(context.sql(transaction))?.map { it.value.lowercase() }?.toSet().orEmpty()
                val read = targets + inSql
                if (read.isEmpty()) tracker.markUnknown() else tracker.add(read)
                return
            }

            if (targets.isEmpty()) {
                // Raw SQL. Session settings like SET LOCAL change no data; anything else is assumed to change anything.
                if (statement.type == StatementType.OTHER &&
                    runCatching { context.sql(transaction).trimStart().startsWith("SET ", ignoreCase = true) }.getOrDefault(false)
                ) return
                mark(transaction) { raw = true; auth = true; settings = true }
                return
            }
            val dataTables = targets.filter { it !in ignoredTableNames }
            val touchesAuth = authTableName in targets ||
                (sessionTableName in targets && statement.type != StatementType.UPDATE && statement.type != StatementType.INSERT)
            val touchesSettings = settingsTableName in targets
            if (dataTables.isNotEmpty() || touchesAuth || touchesSettings) {
                mark(transaction) {
                    tables.addAll(dataTables)
                    if (touchesAuth) auth = true
                    if (touchesSettings) settings = true
                }
            }
        }

        override fun afterCommit(transaction: Transaction) {
            val changes = pending.remove(transaction) ?: return
            onCommittedChange?.invoke(changes.tables.toSet(), changes.raw, changes.auth, changes.settings)
            publish(changes.tables, changes.raw, changes.auth, changes.settings)
        }

        override fun afterRollback(transaction: Transaction) {
            pending.remove(transaction)
            failures.incrementAndGet()
        }
    }

    private inline fun mark(transaction: Transaction, update: Pending.() -> Unit) {
        synchronized(pending) { pending.getOrPut(transaction) { Pending() }.update() }
    }

    /** Hooks the interceptor into every Exposed transaction. Call once, before the server takes requests. */
    fun install() {
        if (installed) return
        installed = true
        registerTables(DatabaseFactory.allTables.map { it.tableName.lowercase() })
        JdbcTransaction.globalInterceptors.add(Interceptor)
        scope.launch {
            while (isActive) {
                if (owedTables.isNotEmpty() || rawBumpOwed || authBumpOwed || settingsEvictionOwed) {
                    runCatching { publish(owedTables.toSet(), rawBumpOwed, authBumpOwed, settingsEvictionOwed) }
                }
                delay(1_000L)
            }
        }
    }

    /** Sees each committed change set before it is published. Tests only. */
    @Volatile internal var onCommittedChange: ((tables: Set<String>, raw: Boolean, auth: Boolean, settings: Boolean) -> Unit)? = null

    private fun publish(tables: Set<String>, raw: Boolean, auth: Boolean, settings: Boolean) {
        // Owed first: if Redis is unreachable the retry loop finishes the job.
        owedTables.addAll(tables)
        if (raw) rawBumpOwed = true
        if (auth) authBumpOwed = true
        if (settings) settingsEvictionOwed = true
        if (!RedisClusterCoordinator.isReady) return
        try {
            RedisClusterCoordinator.getJedis().use { jedis ->
                if (settings) {
                    // Team settings are cached in Redis apart from these counters; a direct write must not leave them behind.
                    RedisCache.deleteByPattern("obsidian:settings:*")
                    settingsEvictionOwed = false
                }
                if (auth) {
                    jedis.incr(AUTH_KEY)
                    authBumpOwed = false
                }
                if (tables.isNotEmpty() || raw) {
                    val pipe = jedis.pipelined()
                    tables.forEach { pipe.hincrBy(VERSIONS_KEY, it, 1) }
                    if (raw) pipe.hincrBy(VERSIONS_KEY, RAW, 1)
                    pipe.hincrBy(VERSIONS_KEY, ANY, 1)
                    pipe.sync()
                    owedTables.removeAll(tables)
                    if (raw) rawBumpOwed = false
                    jedis.publish(RedisPubSubListener.INVALIDATION_CHANNEL, "$CHANGED_MESSAGE:" + (if (raw) RAW else tables.joinToString(",")))
                }
            }
        } catch (e: Exception) {
            log.warn("Data version bump failed, retrying: {}", e.message)
        }
    }

    private fun cacheable(): Boolean =
        installed && owedTables.isEmpty() && !rawBumpOwed && !settingsEvictionOwed && RedisClusterCoordinator.isReady &&
            // Reads are served from a local snapshot while the cluster is under quorum: never cache those.
            !com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost

    /**
     * Every counter as it stands, or null when nothing may be cached right now. Take it before reading any data: a
     * write that lands mid-computation then leaves the result recorded against a version that has already moved.
     */
    fun snapshot(): Map<String, Long>? {
        if (!cacheable()) return null
        return try {
            RedisClusterCoordinator.getJedis().use { jedis -> jedis.hgetAll(VERSIONS_KEY).mapValues { it.value.toLongOrNull() ?: 0L } }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * What a result computed by [tracker] after [snapshot] depends on, or null if it must not be cached (it read a
     * table whose writes aren't counted).
     */
    fun dependencies(tracker: ReadTracker, snapshot: Map<String, Long>): Map<String, Long>? {
        if (tracker.readIgnored) return null
        val names = if (tracker.unknown || tracker.tables.isEmpty()) setOf(ANY) else tracker.tables + RAW
        return names.associateWith { snapshot[it] ?: 0L }
    }

    /** Whether nothing [dependencies] names has been written since. False when that can't be checked. */
    fun stillCurrent(dependencies: Map<String, Long>): Boolean {
        if (dependencies.isEmpty() || !cacheable()) return false
        val names = dependencies.keys.toList()
        return try {
            val now = RedisClusterCoordinator.getJedis().use { jedis -> jedis.hmget(VERSIONS_KEY, *names.toTypedArray()) }
            names.indices.all { (now[it]?.toLongOrNull() ?: 0L) == dependencies.getValue(names[it]) }
        } catch (_: Exception) {
            false
        }
    }

    /** Moves on every write; for in-memory caches that don't track their reads. Null when nothing may be cached. */
    fun current(): Long? {
        if (!cacheable()) return null
        return try {
            RedisClusterCoordinator.getJedis().use { jedis -> jedis.hget(VERSIONS_KEY, ANY)?.toLongOrNull() ?: 0L }
        } catch (_: Exception) {
            null
        }
    }

    /** Like [current], for cached session checks: moves on writes to users and on deleted sessions. */
    fun authVersion(): Long? {
        if (!installed || authBumpOwed || !RedisClusterCoordinator.isReady) return null
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return null
        return try {
            RedisClusterCoordinator.getJedis().use { jedis -> jedis.get(AUTH_KEY)?.toLongOrNull() ?: 0L }
        } catch (_: Exception) {
            null
        }
    }
}
