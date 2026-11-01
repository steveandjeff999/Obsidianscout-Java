package com.obsidianscout.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Gets invalidations to sockets on every node. [NotificationWebSocketManager] sends to this node's sockets straight
 * away and hands the same invalidation here; other nodes tail the live_invalidations table and send to theirs, the
 * way SlackNotificationRelay gets Slack banners around.
 *
 * Writes are gathered for [FLUSH_MS] so a burst of submissions is one row per team. A row that doesn't make it (a
 * database blip, a node going down) only costs the pages on other nodes this change: the next one catches them up.
 * The tail looks back a fixed window rather than keeping a high-water mark, so rows from a node whose clock is a
 * little behind are not skipped; [delivered] stops a row going out twice.
 */
object LiveInvalidationRelay {
    /** Who a row is for: a team, or, without one, everyone. */
    data class Target(val teamNumber: Int? = null, val program: String = "", val eventKey: String? = null)

    data class Delivery(val id: String, val target: Target, val topics: List<String>)

    private val log = LoggerFactory.getLogger("LiveInvalidationRelay")
    private const val FLUSH_MS = 500L
    private const val POLL_MS = 2_000L
    private const val LOOKBACK_SECONDS = 30L
    private const val RETENTION_SECONDS = 300L
    private const val MAX_ROWS_PER_POLL = 1_000
    private const val FIELD_SEPARATOR = ","

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushLoop: Job? = null
    private var pollLoop: Job? = null
    private val pending = ConcurrentHashMap<Target, Set<String>>()
    private val delivered = ConcurrentHashMap<String, Long>()
    private var lastCleanup = 0L

    /** Rows this node wrote are skipped when tailing: its sockets already have them. */
    internal var nodeId: String = UUID.randomUUID().toString()

    /** Off until [start] sees a database other nodes can share, so nothing queues up on a single SQLite server. */
    @Volatile
    internal var enabled = false

    fun start() {
        if (!DatabaseFactory.isPostgresCompatible || pollLoop?.isActive == true) return
        enabled = true
        flushLoop = repeating(FLUSH_MS, ::flush)
        pollLoop = repeating(POLL_MS) { poll() }
    }

    fun stop() {
        enabled = false
        flushLoop?.cancel()
        pollLoop?.cancel()
        flushLoop = null
        pollLoop = null
        pending.clear()
    }

    private fun repeating(intervalMs: Long, step: () -> Unit): Job = scope.launch {
        while (isActive) {
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("Live invalidation relay failed: ${e.message}")
            }
            delay(intervalMs)
        }
    }

    fun publish(target: Target, topics: Collection<String>) {
        if (!enabled || topics.isEmpty()) return
        pending.merge(target, topics.toSet()) { a, b -> a + b }
    }

    internal fun flush(now: Instant = Instant.now()) {
        val batch = pending.keys.toList().mapNotNull { key -> pending.remove(key)?.let { key to it } }
        if (batch.isEmpty()) return
        transaction {
            LiveInvalidations.batchInsert(batch, shouldReturnGeneratedValues = false) { (target, topics) ->
                this[LiveInvalidations.originNode] = nodeId
                this[LiveInvalidations.teamNumber] = target.teamNumber
                this[LiveInvalidations.program] = target.program
                this[LiveInvalidations.eventKey] = target.eventKey
                this[LiveInvalidations.topics] = topics.joinToString(FIELD_SEPARATOR)
                this[LiveInvalidations.createdAt] = now
            }
        }
        com.obsidianscout.admin.LoadProfiler.recordRelayWritten(batch.size)
    }

    internal fun poll(now: Instant = Instant.now(), deliver: (Delivery) -> Unit = ::deliverToSockets) {
        val since = now.minusSeconds(LOOKBACK_SECONDS)
        val rows = transaction {
            LiveInvalidations.selectAll()
                .where { (LiveInvalidations.createdAt greater since) and (LiveInvalidations.originNode neq nodeId) }
                .orderBy(LiveInvalidations.createdAt, SortOrder.DESC)
                .limit(MAX_ROWS_PER_POLL)
                .map { row ->
                    Delivery(
                        id = row[LiveInvalidations.id].value.toString(),
                        target = Target(
                            teamNumber = row[LiveInvalidations.teamNumber],
                            program = row[LiveInvalidations.program],
                            eventKey = row[LiveInvalidations.eventKey]
                        ),
                        topics = splitField(row[LiveInvalidations.topics])
                    )
                }
        }
        val nowMs = now.toEpochMilli()
        rows.forEach {
            if (delivered.putIfAbsent(it.id, nowMs) == null) {
                deliver(it)
                com.obsidianscout.admin.LoadProfiler.recordRelayDelivered()
            }
        }
        delivered.entries.removeIf { nowMs - it.value > (LOOKBACK_SECONDS + 60) * 1000 }
        if (nowMs - lastCleanup > RETENTION_SECONDS * 1000) {
            lastCleanup = nowMs
            cleanUp(now)
        }
    }

    internal fun cleanUp(now: Instant = Instant.now()) {
        val cutoff = now.minusSeconds(RETENTION_SECONDS)
        transaction { LiveInvalidations.deleteWhere { LiveInvalidations.createdAt less cutoff } }
    }

    private fun splitField(value: String): List<String> = value.split(FIELD_SEPARATOR).filter { it.isNotEmpty() }

    private fun deliverToSockets(delivery: Delivery) {
        val target = delivery.target
        if (target.teamNumber != null) {
            NotificationWebSocketManager.deliverToTeam(target.teamNumber, target.program, delivery.topics)
        } else {
            NotificationWebSocketManager.deliverToAll(delivery.topics, target.eventKey)
        }
    }
}
