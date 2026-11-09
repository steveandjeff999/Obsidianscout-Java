package com.obsidianscout.security

import com.obsidianscout.auth.isTrustedProxyAddress
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.IpBlocksTable
import com.obsidianscout.db.readTransaction
import com.obsidianscout.db.writeTransactionWithRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Temporary IP blocks. **No block ever lasts more than [MAX_BLOCK_MS] (one hour)**: every way of creating one goes
 * through [clampDuration], whatever the config, the API caller or the escalation step asks for. Blocks are checked
 * from memory on every request ([match]); the database is the record, refreshed every 30 seconds and on a cluster
 * message whenever a node adds or lifts one.
 */
object BlockList {
    private val log = org.slf4j.LoggerFactory.getLogger(BlockList::class.java)

    /** The hard ceiling on any block's length. Not configurable. */
    const val MAX_BLOCK_MS = 60 * 60_000L
    private const val MIN_BLOCK_MS = 60_000L
    private const val STRIKE_WINDOW_MS = 24 * 60 * 60_000L
    private val DEFAULT_STEPS = listOf(5, 15, 60)

    data class ActiveBlock(
        val id: UUID,
        val range: IpRange,
        val reason: String,
        val triggeringTypes: List<String>,
        val createdAt: Long,
        val expiresAt: Long,
        val createdBy: String,
        val strike: Int
    ) {
        fun remainingMs(now: Long = System.currentTimeMillis()) = (expiresAt - now).coerceAtLeast(0)
    }

    @Volatile private var exact: Map<String, ActiveBlock> = emptyMap()
    @Volatile private var ranges: List<ActiveBlock> = emptyList()
    /** Blocks created while the database wasn't ready; folded into every refresh until they expire. */
    private val memoryOnly = ConcurrentHashMap<UUID, ActiveBlock>()
    private val pendingHits = ConcurrentHashMap<UUID, AtomicLong>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null

    fun clampDuration(ms: Long): Long = ms.coerceIn(MIN_BLOCK_MS, MAX_BLOCK_MS)

    /** Length of the block for the [strike]th block within 24 hours: 5, 15, then 60 minutes by default, never over an hour. */
    fun durationForStrike(strike: Int, steps: List<Int> = SecuritySettings.config.block_minutes): Long {
        val list = steps.filter { it > 0 }.ifEmpty { DEFAULT_STEPS }
        val minutes = list[(strike - 1).coerceIn(0, list.size - 1)]
        return clampDuration(minutes.toLong() * 60_000L)
    }

    /** Loopback, Tailscale peers, the allowlist, verified crawlers and Cloudflare's shared addresses are never blocked automatically. */
    fun isExempt(ip: String): Boolean = isTrustedProxyAddress(ip) || Allowlist.isAllowed(ip) ||
        CloudflareNetwork.contains(ip) || VerifiedCrawlers.isVerified(ip)

    /** The active block covering [ip], if any. Called on every request, so it only reads memory. */
    fun match(ip: String, now: Long = System.currentTimeMillis(), countHit: Boolean = true): ActiveBlock? {
        // Stored blocks use the canonical spelling; an IPv6 address written another way must still match.
        val key = com.obsidianscout.auth.canonicalIp(ip)
        val hit = exact[key]?.takeIf { it.expiresAt > now }
            ?: ranges.firstOrNull { it.expiresAt > now && it.range.contains(key) }
            ?: return null
        if (countHit) pendingHits.computeIfAbsent(hit.id) { AtomicLong() }.incrementAndGet()
        return hit
    }

    fun activeBlocks(now: Long = System.currentTimeMillis()): List<ActiveBlock> =
        (exact.values + ranges).filter { it.expiresAt > now }.distinctBy { it.id }

    private fun install(blocks: Collection<ActiveBlock>, now: Long = System.currentTimeMillis()) {
        val live = blocks.filter { it.expiresAt > now }
        exact = live.filter { it.range.isSingleAddress }.associateBy { it.range.text }
        ranges = live.filter { !it.range.isSingleAddress }
    }

    /**
     * Blocks [ip] automatically after its threat score crossed the block threshold. Escalates with each block in
     * 24 hours. Returns null when nothing was blocked (exempt address, or already blocked).
     */
    fun autoBlock(ip: String, reason: String, triggeringTypes: List<String>, now: Long = System.currentTimeMillis()): ActiveBlock? {
        if (isExempt(ip)) return null
        val range = IpRange.parse(ip) ?: return null
        match(range.text, now, countHit = false)?.let { return null }
        val strike = SecurityStore.increment("strikes:${range.text}", STRIKE_WINDOW_MS, now, sliding = true)
        val block = create(range, reason, triggeringTypes, durationForStrike(strike), "system", strike, now)
        SecurityEvents.emit(
            SecurityEvent(
                type = SecurityEventType.BLOCK_APPLIED,
                ip = range.text,
                details = "Blocked for ${block.remainingMs(now) / 60_000} min (block $strike in 24 h): $reason"
            )
        )
        return block
    }

    /**
     * A superadmin's block. [minutes] is clamped to 1..60. Refuses our own proxies and cluster nodes, and anything
     * covering Cloudflare's addresses (shared by Cloudflare's own services and by every Worker).
     */
    fun manualBlock(target: String, minutes: Int, reason: String, by: String): ActiveBlock? {
        val range = IpRange.parse(target) ?: return null
        if (range.isSingleAddress && isTrustedProxyAddress(range.text)) return null
        if (CloudflareNetwork.overlaps(range)) return null
        val block = create(range, reason.ifBlank { "Blocked by $by" }, listOf("MANUAL"), clampDuration(minutes.toLong() * 60_000L), by, 1)
        SecurityEvents.emit(
            SecurityEvent(type = SecurityEventType.BLOCK_APPLIED, ip = range.text, username = by,
                details = "Manually blocked for ${block.remainingMs() / 60_000} min: ${block.reason}")
        )
        return block
    }

    private fun create(range: IpRange, reason: String, types: List<String>, durationMs: Long, by: String, strike: Int, now: Long = System.currentTimeMillis()): ActiveBlock {
        val block = ActiveBlock(
            id = UUID.randomUUID(),
            range = range,
            reason = SecurityEvents.clean(reason, 512),
            triggeringTypes = types.distinct().take(12),
            createdAt = now,
            expiresAt = now + clampDuration(durationMs),
            createdBy = by.take(128),
            strike = strike
        )
        var persisted = false
        if (DatabaseFactory.isReady) {
            persisted = runCatching {
                writeTransactionWithRetry {
                    IpBlocksTable.insert {
                        it[id] = block.id
                        it[ipOrCidr] = block.range.text
                        it[IpBlocksTable.reason] = block.reason
                        it[triggeringEventTypes] = block.triggeringTypes.joinToString(",")
                        it[createdAt] = Instant.ofEpochMilli(block.createdAt)
                        it[expiresAt] = Instant.ofEpochMilli(block.expiresAt)
                        it[createdBy] = block.createdBy
                        it[IpBlocksTable.strike] = block.strike
                    }
                }
            }.onFailure { log.warn("[BlockList] Couldn't save block for {}: {}", range.text, it.message) }.isSuccess
        }
        if (!persisted) memoryOnly[block.id] = block
        install(activeBlocks(now) + block, now)
        SecuritySync.publish(SecuritySync.BLOCKS)
        return block
    }

    /**
     * Lifts a block: every node lets the address through within a second, its threat score goes back to 0, and
     * optionally its strike count is cleared and it is allowlisted. [requestId] is the unblock request that asked for it.
     */
    fun lift(
        id: UUID,
        by: String,
        reason: String,
        resetStrikes: Boolean,
        allowlistFor: String,
        requestId: UUID? = null,
        now: Long = System.currentTimeMillis()
    ): IpBlockDto? {
        val existing = find(id) ?: return null
        if (!existing.active) return existing
        val cleanReason = SecurityEvents.clean(reason.ifBlank { "Lifted by $by" }, 512)
        memoryOnly.remove(id)
        if (DatabaseFactory.isReady) {
            runCatching {
                writeTransactionWithRetry {
                    IpBlocksTable.update({ IpBlocksTable.id eq id }) {
                        it[liftedAt] = Instant.ofEpochMilli(now)
                        it[liftedBy] = by.take(128)
                        it[liftReason] = cleanReason
                    }
                }
            }.onFailure { log.warn("[BlockList] Couldn't record lift of {}: {}", id, it.message) }
        }
        install(activeBlocks(now).filter { it.id != id }, now)
        SecurityStore.resetScore("ip:${existing.ipOrCidr}")
        if (resetStrikes) SecurityStore.resetCount("strikes:${existing.ipOrCidr}")
        allowlistExpiry(allowlistFor, now)?.let { expiry ->
            Allowlist.add(existing.ipOrCidr, "Allowlisted when block was lifted: $cleanReason", by, expiry.takeIf { it > 0 })
        }
        UnblockRequests.closePendingForBlock(id, by, requestId)
        SecuritySync.publish(SecuritySync.BLOCKS)
        SecurityEvents.emit(
            SecurityEvent(type = SecurityEventType.BLOCK_LIFTED, ip = existing.ipOrCidr, username = by,
                details = cleanReason + (if (resetStrikes) " (strikes reset)" else "") +
                    (if (allowlistFor != "none") " (allowlisted: $allowlistFor)" else ""))
        )
        return find(id) ?: existing.copy(active = false, liftedAt = now, liftedBy = by.take(128), liftReason = cleanReason)
    }

    /** Lifts every active block, for when a bad rule has caught everyone at an event. */
    fun liftAll(by: String, reason: String): Int =
        activeBlocks().count { lift(it.id, by, reason, resetStrikes = true, allowlistFor = "none") != null }

    /** Milliseconds since epoch the allowlist entry should last until; 0 for permanent; null for no allowlisting. */
    private fun allowlistExpiry(allowlistFor: String, now: Long): Long? = when (allowlistFor) {
        "24h" -> now + 24 * 3_600_000L
        "7d" -> now + 7 * 24 * 3_600_000L
        "permanent" -> 0L
        else -> null
    }

    fun find(id: UUID): IpBlockDto? {
        memoryOnly[id]?.let { return toDto(it, active = it.expiresAt > System.currentTimeMillis()) }
        if (!DatabaseFactory.isReady) return activeBlocks().firstOrNull { it.id == id }?.let { toDto(it, true) }
        return readTransaction {
            IpBlocksTable.selectAll().where { IpBlocksTable.id eq id }.firstOrNull()?.let { rowToDto(it) }
        }
    }

    fun list(activeOnly: Boolean, limit: Int = 200): List<IpBlockDto> {
        val now = System.currentTimeMillis()
        val rows = if (DatabaseFactory.isReady) {
            readTransaction {
                val query = if (activeOnly) {
                    IpBlocksTable.selectAll().where { IpBlocksTable.liftedAt.isNull() and (IpBlocksTable.expiresAt greater Instant.ofEpochMilli(now)) }
                } else IpBlocksTable.selectAll()
                query.orderBy(IpBlocksTable.createdAt to SortOrder.DESC).limit(limit.coerceIn(1, 1000)).map { rowToDto(it) }
            }
        } else emptyList()
        val memory = memoryOnly.values.filter { !activeOnly || it.expiresAt > now }.map { toDto(it, it.expiresAt > now) }
        val pending = UnblockRequests.pendingByBlock()
        return (memory + rows).distinctBy { it.id }.map { dto ->
            dto.copy(
                pendingRequestId = pending[dto.id],
                recentDistinctUsers = SecurityStore.distinctCount("okusers:${dto.ipOrCidr}", 3_600_000L)
            )
        }
    }

    private fun toDto(b: ActiveBlock, active: Boolean) = IpBlockDto(
        id = b.id.toString(), ipOrCidr = b.range.text, reason = b.reason, triggeringEventTypes = b.triggeringTypes,
        createdAt = b.createdAt, expiresAt = b.expiresAt, createdBy = b.createdBy, strike = b.strike,
        hits = pendingHits[b.id]?.get() ?: 0, active = active
    )

    private fun rowToDto(row: org.jetbrains.exposed.v1.core.ResultRow): IpBlockDto {
        val now = Instant.now()
        val lifted = row[IpBlocksTable.liftedAt]
        val expires = row[IpBlocksTable.expiresAt]
        val id = row[IpBlocksTable.id].value
        return IpBlockDto(
            id = id.toString(),
            ipOrCidr = row[IpBlocksTable.ipOrCidr],
            reason = row[IpBlocksTable.reason],
            triggeringEventTypes = row[IpBlocksTable.triggeringEventTypes].split(',').filter { it.isNotBlank() },
            createdAt = row[IpBlocksTable.createdAt].toEpochMilli(),
            expiresAt = expires.toEpochMilli(),
            createdBy = row[IpBlocksTable.createdBy],
            strike = row[IpBlocksTable.strike],
            hits = row[IpBlocksTable.hits] + (pendingHits[id]?.get() ?: 0),
            active = lifted == null && expires.isAfter(now),
            liftedAt = lifted?.toEpochMilli(),
            liftedBy = row[IpBlocksTable.liftedBy],
            liftReason = row[IpBlocksTable.liftReason]
        )
    }

    /** Reloads active blocks from the database (plus any memory-only ones). */
    fun refresh() {
        val now = System.currentTimeMillis()
        memoryOnly.entries.removeIf { it.value.expiresAt <= now }
        if (!DatabaseFactory.isReady) {
            install(activeBlocks(now) + memoryOnly.values, now)
            return
        }
        val fromDb = readTransaction {
            IpBlocksTable.selectAll()
                .where { IpBlocksTable.liftedAt.isNull() and (IpBlocksTable.expiresAt greater Instant.ofEpochMilli(now)) }
                .mapNotNull { row ->
                    val range = IpRange.parse(row[IpBlocksTable.ipOrCidr]) ?: return@mapNotNull null
                    val created = row[IpBlocksTable.createdAt].toEpochMilli()
                    ActiveBlock(
                        id = row[IpBlocksTable.id].value,
                        range = range,
                        reason = row[IpBlocksTable.reason],
                        triggeringTypes = row[IpBlocksTable.triggeringEventTypes].split(',').filter { it.isNotBlank() },
                        createdAt = created,
                        // Rows written by anything else are held to the same ceiling.
                        expiresAt = minOf(row[IpBlocksTable.expiresAt].toEpochMilli(), created + MAX_BLOCK_MS),
                        createdBy = row[IpBlocksTable.createdBy],
                        strike = row[IpBlocksTable.strike]
                    )
                }
        }
        install(fromDb + memoryOnly.values, now)
    }

    private fun flushHits() {
        if (!DatabaseFactory.isReady || pendingHits.isEmpty()) return
        val snapshot = pendingHits.entries.map { it.key to it.value.getAndSet(0) }.filter { it.second > 0 }
        pendingHits.entries.removeIf { it.value.get() == 0L && memoryOnly[it.key] == null && activeBlocks().none { b -> b.id == it.key } }
        if (snapshot.isEmpty()) return
        runCatching {
            writeTransactionWithRetry {
                for ((id, n) in snapshot) {
                    IpBlocksTable.update({ IpBlocksTable.id eq id }) { it[hits] = hits + n }
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                runCatching { refresh() }.onFailure { log.debug("[BlockList] Refresh failed: {}", it.message) }
                runCatching { Allowlist.refresh() }
                runCatching { flushHits() }
                runCatching { UnblockRequests.expireStale() }
                delay(30_000L)
            }
        }
    }

    /** Tests only. */
    internal fun clearForTests() {
        memoryOnly.clear()
        pendingHits.clear()
        install(emptyList())
    }
}
