package com.obsidianscout.integrations

import com.obsidianscout.admin.ClusterManagementService
import com.obsidianscout.db.ClusterNotificationLocks
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.orchestration.CockroachOrchestrator
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Time-limited leases shared by every cluster node, kept in the cluster_notification_locks table.
 *
 * A lease is taken with a compare-and-set UPDATE (it only succeeds if the row still holds the values this
 * node just read), so exactly one node wins even when two see the same expired lease at once. A plain
 * read-then-write would let both claim it under Postgres' default READ COMMITTED isolation.
 */
object ClusterLease {
    enum class Result { ACQUIRED, HELD_ELSEWHERE, UNAVAILABLE }

    private val log = LoggerFactory.getLogger("ClusterLease")

    /** Unique per process, so two servers on one machine, or a restarted one, never share an identity. */
    val nodeId: String by lazy {
        val ip = runCatching { ClusterManagementService.getLocalTailscaleIp() }.getOrDefault("local")
        "$ip/${UUID.randomUUID().toString().take(8)}"
    }

    /** Tests swap this in; production uses the live database state. */
    @Volatile
    internal var availabilityOverride: (() -> Boolean)? = null

    private fun available(): Boolean =
        availabilityOverride?.invoke() ?: (DatabaseFactory.isReady && !CockroachOrchestrator.isQuorumLost)

    /** Takes or renews [key] for [ttlSeconds]. Renewing a lease this node already holds always succeeds. */
    fun tryAcquire(key: String, ttlSeconds: Long, holder: String = nodeId, now: Instant = Instant.now()): Result {
        if (!available()) return Result.UNAVAILABLE
        val at = now.truncatedTo(ChronoUnit.MILLIS)
        return try {
            if (transaction { claim(key, holder, ttlSeconds, at) }) Result.ACQUIRED else Result.HELD_ELSEWHERE
        } catch (e: Exception) {
            // Usually two nodes inserting the same new lease at once: the loser hits the unique index.
            // Look again to tell that apart from the database being unreachable.
            val current = runCatching { holderOf(key, at) }
            when {
                current.isFailure -> {
                    log.debug("Lease $key unavailable: ${e.message}")
                    Result.UNAVAILABLE
                }
                current.getOrNull() == holder -> Result.ACQUIRED
                else -> Result.HELD_ELSEWHERE
            }
        }
    }

    /** Must run inside a transaction. */
    internal fun claim(key: String, holder: String, ttlSeconds: Long, now: Instant): Boolean {
        val row = ClusterNotificationLocks.selectAll().where { ClusterNotificationLocks.lockKey eq key }.firstOrNull()
        val expires = now.plusSeconds(ttlSeconds)
        if (row == null) {
            ClusterNotificationLocks.insert {
                it[lockKey] = key
                it[claimedByNode] = holder
                it[claimedAt] = now
                it[expiresAt] = expires
            }
            return true
        }
        val currentHolder = row[ClusterNotificationLocks.claimedByNode]
        val currentClaimedAt = row[ClusterNotificationLocks.claimedAt]
        if (currentHolder != holder && row[ClusterNotificationLocks.expiresAt].isAfter(now)) return false
        val updated = ClusterNotificationLocks.update({
            (ClusterNotificationLocks.lockKey eq key) and
                (ClusterNotificationLocks.claimedByNode eq currentHolder) and
                (ClusterNotificationLocks.claimedAt eq currentClaimedAt)
        }) {
            it[claimedByNode] = holder
            it[claimedAt] = now
            it[expiresAt] = expires
        }
        return updated == 1
    }

    /** The node holding an unexpired [key], or null. Throws if the database can't be read. */
    fun holderOf(key: String, now: Instant = Instant.now()): String? = transaction {
        ClusterNotificationLocks.selectAll().where { ClusterNotificationLocks.lockKey eq key }.firstOrNull()
            ?.takeIf { it[ClusterNotificationLocks.expiresAt].isAfter(now) }
            ?.get(ClusterNotificationLocks.claimedByNode)
    }

    /** Gives [key] up early. Only the holder's own lease is removed. */
    fun release(key: String, holder: String = nodeId) {
        if (!available()) return
        runCatching {
            transaction {
                ClusterNotificationLocks.deleteWhere {
                    (ClusterNotificationLocks.lockKey eq key) and (ClusterNotificationLocks.claimedByNode eq holder)
                }
            }
        }.onFailure { log.debug("Releasing lease $key failed: ${it.message}") }
    }
}
