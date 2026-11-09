package com.obsidianscout.security

import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.IpBlocksTable
import com.obsidianscout.db.SecurityEventsTable
import com.obsidianscout.db.UnblockRequestsTable
import com.obsidianscout.db.readTransaction
import com.obsidianscout.db.writeTransactionWithRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The lasting security log. Events are queued in memory and written in batches (every 2 seconds, or sooner once 200
 * are waiting), so a login handler never waits on a database write. Old rows are removed by a retention job.
 */
object SecurityLog {
    private val log = org.slf4j.LoggerFactory.getLogger(SecurityLog::class.java)
    private const val MAX_QUEUE = 5_000
    private const val BATCH = 200
    private const val FLUSH_MS = 2_000L
    private const val RETENTION_CHECK_MS = 60 * 60_000L

    private val queue = ConcurrentLinkedQueue<SecurityEvent>()
    private val queued = AtomicInteger(0)
    val droppedWrites = AtomicLong(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null
    private var retentionJob: Job? = null
    @Volatile private var nodeId: String? = null

    fun enqueue(event: SecurityEvent) {
        if (queued.get() >= MAX_QUEUE) {
            droppedWrites.incrementAndGet()
            return
        }
        queue.add(event)
        queued.incrementAndGet()
    }

    @Synchronized
    fun start() {
        if (flushJob?.isActive != true) {
            flushJob = scope.launch {
                while (isActive) {
                    delay(if (queued.get() >= BATCH) 50L else FLUSH_MS)
                    runCatching { flush() }.onFailure { log.warn("[SecurityLog] Flush failed: {}", it.message) }
                }
            }
        }
        if (retentionJob?.isActive != true) {
            retentionJob = scope.launch {
                delay(5 * 60_000L)
                while (isActive) {
                    runCatching { applyRetention() }.onFailure { log.warn("[SecurityLog] Retention failed: {}", it.message) }
                    delay(RETENTION_CHECK_MS)
                }
            }
        }
    }

    private fun node(): String = nodeId ?: runCatching {
        com.obsidianscout.admin.ClusterManagementService.getLocalTailscaleIp()
    }.getOrDefault("local").also { nodeId = it }

    /** Writes what is queued. Rows wait in memory while the database isn't ready. */
    fun flush() {
        if (!DatabaseFactory.isReady || queue.isEmpty()) return
        val batch = ArrayList<SecurityEvent>(BATCH)
        while (batch.size < BATCH) {
            val next = queue.poll() ?: break
            queued.decrementAndGet()
            batch.add(next)
        }
        if (batch.isEmpty()) return
        val nodeName = node()
        try {
            writeTransactionWithRetry {
                SecurityEventsTable.batchInsert(batch, shouldReturnGeneratedValues = false) { e ->
                    this[SecurityEventsTable.at] = Instant.ofEpochMilli(e.at)
                    this[SecurityEventsTable.type] = e.type.name
                    this[SecurityEventsTable.severity] = e.type.severity.name
                    this[SecurityEventsTable.ip] = e.ip
                    this[SecurityEventsTable.userId] = e.userId
                    this[SecurityEventsTable.username] = e.username
                    this[SecurityEventsTable.teamNumber] = e.teamNumber
                    this[SecurityEventsTable.method] = e.method
                    this[SecurityEventsTable.path] = e.path
                    this[SecurityEventsTable.userAgent] = e.userAgent
                    this[SecurityEventsTable.details] = e.details
                    this[SecurityEventsTable.node] = nodeName
                }
            }
        } catch (e: Exception) {
            // Put them back once; if the database keeps failing the queue cap drops the overflow.
            batch.forEach { enqueue(it) }
            throw e
        }
    }

    fun applyRetention(now: Instant = Instant.now()) {
        if (!DatabaseFactory.isReady) return
        val config = SecuritySettings.config
        val cutoff = now.minusSeconds(config.retention_days.coerceAtLeast(1) * 86_400L)
        val adminCutoff = now.minusSeconds(config.admin_action_retention_days.coerceAtLeast(config.retention_days) * 86_400L)
        writeTransactionWithRetry {
            SecurityEventsTable.deleteWhere {
                (SecurityEventsTable.at less cutoff) and (SecurityEventsTable.type neq SecurityEventType.ADMIN_ACTION.name)
            }
            SecurityEventsTable.deleteWhere {
                (SecurityEventsTable.at less adminCutoff) and (SecurityEventsTable.type eq SecurityEventType.ADMIN_ACTION.name)
            }
            UnblockRequestsTable.deleteWhere { UnblockRequestsTable.createdAt less cutoff }
            IpBlocksTable.deleteWhere { IpBlocksTable.expiresAt less cutoff }
        }
    }

    // ------------------------------------------------------------------------------------------------ queries

    fun query(
        ip: String? = null,
        user: String? = null,
        type: String? = null,
        severity: String? = null,
        since: Long? = null,
        until: Long? = null,
        offset: Int = 0,
        limit: Int = 100
    ): SecurityEventPage {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        if (!DatabaseFactory.isReady) return SecurityEventPage(emptyList(), 0, safeOffset, safeLimit)
        return readTransaction {
            var cond: Op<Boolean> = Op.TRUE
            ip?.trim()?.takeIf { it.isNotEmpty() }?.let { cond = cond and (SecurityEventsTable.ip eq it.take(64)) }
            user?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val needle = it.take(128).lowercase().replace("%", "").replace("_", "")
                cond = cond and ((SecurityEventsTable.username.lowerCase() like "%$needle%") or (SecurityEventsTable.userId eq it.take(64)))
            }
            type?.trim()?.takeIf { it.isNotEmpty() }?.let { cond = cond and (SecurityEventsTable.type eq it.take(40).uppercase()) }
            severity?.trim()?.takeIf { it.isNotEmpty() }?.let { cond = cond and (SecurityEventsTable.severity eq it.take(10).uppercase()) }
            since?.let { cond = cond and (SecurityEventsTable.at greaterEq Instant.ofEpochMilli(it)) }
            until?.let { cond = cond and (SecurityEventsTable.at less Instant.ofEpochMilli(it)) }
            val total = SecurityEventsTable.selectAll().where { cond }.count()
            val rows = SecurityEventsTable.selectAll().where { cond }
                .orderBy(SecurityEventsTable.at to SortOrder.DESC)
                .limit(safeLimit).offset(safeOffset.toLong())
                .map { row ->
                    SecurityEventDto(
                        id = row[SecurityEventsTable.id].value.toString(),
                        at = row[SecurityEventsTable.at].toEpochMilli(),
                        type = row[SecurityEventsTable.type],
                        severity = row[SecurityEventsTable.severity],
                        ip = row[SecurityEventsTable.ip],
                        userId = row[SecurityEventsTable.userId],
                        username = row[SecurityEventsTable.username],
                        teamNumber = row[SecurityEventsTable.teamNumber],
                        method = row[SecurityEventsTable.method],
                        path = row[SecurityEventsTable.path],
                        userAgent = row[SecurityEventsTable.userAgent],
                        details = row[SecurityEventsTable.details],
                        node = row[SecurityEventsTable.node]
                    )
                }
            SecurityEventPage(rows, total, safeOffset, safeLimit)
        }
    }

    /** Event counts per hour by severity for the last [hours] hours, plus totals by type. */
    fun timeline(hours: Int = 24, now: Long = System.currentTimeMillis()): Pair<List<SecurityTimelineBucket>, Map<String, Int>> {
        val hourMs = 3_600_000L
        val firstHour = (now / hourMs - (hours - 1)) * hourMs
        val buckets = LinkedHashMap<Long, IntArray>()
        for (i in 0 until hours) buckets[firstHour + i * hourMs] = IntArray(4)
        val byType = HashMap<String, Int>()
        if (DatabaseFactory.isReady) {
            readTransaction {
                SecurityEventsTable.select(SecurityEventsTable.at, SecurityEventsTable.type, SecurityEventsTable.severity)
                    .where { SecurityEventsTable.at greaterEq Instant.ofEpochMilli(firstHour) }
                    .limit(200_000)
                    .forEach { row ->
                        val at = row[SecurityEventsTable.at].toEpochMilli()
                        val bucket = buckets[(at / hourMs) * hourMs] ?: return@forEach
                        val index = when (row[SecurityEventsTable.severity]) {
                            "LOW" -> 0; "MEDIUM" -> 1; "HIGH" -> 2; else -> 3
                        }
                        bucket[index]++
                        byType.merge(row[SecurityEventsTable.type], 1, Int::plus)
                    }
            }
        }
        return buckets.map { (start, c) -> SecurityTimelineBucket(start, low = c[0], medium = c[1], high = c[2], info = c[3]) } to byType
    }

    /** The most recent event per IP among [ips] (for the offenders list). */
    fun lastEventFor(ips: Collection<String>): Map<String, Pair<Long, String>> {
        if (ips.isEmpty() || !DatabaseFactory.isReady) return emptyMap()
        val out = HashMap<String, Pair<Long, String>>()
        readTransaction {
            for (ip in ips.take(25)) {
                SecurityEventsTable.select(SecurityEventsTable.at, SecurityEventsTable.type)
                    .where { (SecurityEventsTable.ip eq ip) and SecurityEventsTable.type.isNotNull() }
                    .orderBy(SecurityEventsTable.at to SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
                    ?.let { out[ip] = it[SecurityEventsTable.at].toEpochMilli() to it[SecurityEventsTable.type] }
            }
        }
        return out
    }

    /** Counts of each event type at or after [since] (for the daily digest). */
    fun countsSince(since: Long): Map<String, Int> {
        if (!DatabaseFactory.isReady) return emptyMap()
        val out = HashMap<String, Int>()
        readTransaction {
            SecurityEventsTable.select(SecurityEventsTable.type)
                .where { SecurityEventsTable.at greaterEq Instant.ofEpochMilli(since) }
                .limit(500_000)
                .forEach { out.merge(it[SecurityEventsTable.type], 1, Int::plus) }
        }
        return out
    }

    /** IPs with the most events since [since], excluding records-only types. */
    fun topIpsSince(since: Long, limit: Int = 10): List<Pair<String, Int>> {
        if (!DatabaseFactory.isReady) return emptyList()
        val counts = HashMap<String, Int>()
        val scored = SecurityEventType.entries.filter { it.weight > 0 }.map { it.name }.toSet()
        readTransaction {
            SecurityEventsTable.select(SecurityEventsTable.ip, SecurityEventsTable.type)
                .where { SecurityEventsTable.at greaterEq Instant.ofEpochMilli(since) }
                .limit(500_000)
                .forEach { if (it[SecurityEventsTable.type] in scored) counts.merge(it[SecurityEventsTable.ip], 1, Int::plus) }
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    /** Tests only. */
    internal fun pendingForTests(): List<SecurityEvent> = queue.toList()
    internal fun clearForTests() {
        queue.clear()
        queued.set(0)
    }
}
