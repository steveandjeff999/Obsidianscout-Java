package com.obsidianscout.collab

import com.obsidianscout.admin.ClusterManagementService
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.ClusterNotificationLocks
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.orchestration.CockroachOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * Decides which cluster node owns a live-edit room, using short leases in the shared
 * cluster_notification_locks table. The owner renews its leases while rooms are open; if it dies the
 * lease expires and the next editor to connect claims the room on its own node.
 *
 * Without a cluster (no Tailscale address) or while the database is unavailable every node serves its
 * rooms itself. Rooms then still converge, because each one folds in saves made elsewhere.
 */
object ConfigCollabLeases {
    sealed class Owner {
        object Local : Owner()
        data class Remote(val address: String) : Owner()
    }

    private const val PREFIX = "cfgcollab:"
    private const val TTL_SECONDS = 20L
    private const val RENEW_MS = 6_000L

    private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.collab.ConfigCollabLeases")
    private val keeperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var keeper: Job? = null

    fun localAddress(): String =
        "${ClusterManagementService.getLocalTailscaleIp()}:${AppConfigLoader.load().server.port}"

    private fun leasesAvailable(): Boolean =
        DatabaseFactory.isReady &&
            !CockroachOrchestrator.isQuorumLost &&
            ClusterManagementService.getLocalTailscaleIp() != "127.0.0.1"

    suspend fun resolveOwner(roomKey: String, isLocallyOpen: Boolean): Owner = withContext(Dispatchers.IO) {
        // An open room's lease is kept by the keeper; if it is lost the room is closed.
        if (isLocallyOpen || !leasesAvailable()) return@withContext Owner.Local
        ensureKeeper()
        repeat(2) {
            val owner = runCatching { claimOrFindOwner(roomKey) }
                .onFailure { log.debug("Lease check for $roomKey failed: ${it.message}") }
                .getOrNull()
            if (owner != null) return@withContext owner
        }
        Owner.Local
    }

    private fun claimOrFindOwner(roomKey: String): Owner = transaction {
        val lockKey = PREFIX + roomKey
        val me = localAddress()
        val now = Instant.now()
        val row = ClusterNotificationLocks.selectAll().where { ClusterNotificationLocks.lockKey eq lockKey }.firstOrNull()
        if (row != null && row[ClusterNotificationLocks.expiresAt].isAfter(now) && row[ClusterNotificationLocks.claimedByNode] != me) {
            return@transaction Owner.Remote(row[ClusterNotificationLocks.claimedByNode])
        }
        if (row == null) {
            ClusterNotificationLocks.insert {
                it[ClusterNotificationLocks.lockKey] = lockKey
                it[claimedByNode] = me
                it[claimedAt] = now
                it[expiresAt] = now.plusSeconds(TTL_SECONDS)
            }
        } else {
            ClusterNotificationLocks.update({ ClusterNotificationLocks.lockKey eq lockKey }) {
                it[claimedByNode] = me
                it[claimedAt] = now
                it[expiresAt] = now.plusSeconds(TTL_SECONDS)
            }
        }
        Owner.Local
    }

    /** Gives up [roomKey] after its room closed, so the next editor can claim it on any node right away. */
    fun release(roomKey: String) {
        if (!leasesAvailable()) return
        runCatching {
            val me = localAddress()
            transaction {
                ClusterNotificationLocks.deleteWhere {
                    (ClusterNotificationLocks.lockKey eq PREFIX + roomKey) and (ClusterNotificationLocks.claimedByNode eq me)
                }
            }
        }
    }

    private fun ensureKeeper() {
        if (keeper?.isActive == true) return
        synchronized(this) {
            if (keeper?.isActive == true) return
            keeper = keeperScope.launch {
                while (isActive) {
                    delay(RENEW_MS)
                    if (!leasesAvailable()) continue
                    for (roomKey in ConfigCollabHub.openRoomKeys()) {
                        val stillOurs = runCatching { renew(roomKey) }.getOrDefault(true)
                        if (!stillOurs) {
                            log.info("Live config room $roomKey moved to another node")
                            ConfigCollabHub.onLeaseLost(roomKey)
                        }
                    }
                }
            }
        }
    }

    /** Extends our lease on [roomKey]; false if another node holds it now. */
    private fun renew(roomKey: String): Boolean {
        val lockKey = PREFIX + roomKey
        val me = localAddress()
        val now = Instant.now()
        val updated = transaction {
            ClusterNotificationLocks.update({ (ClusterNotificationLocks.lockKey eq lockKey) and (ClusterNotificationLocks.claimedByNode eq me) }) {
                it[expiresAt] = now.plusSeconds(TTL_SECONDS)
            }
        }
        if (updated > 0) return true
        return claimOrFindOwner(roomKey) is Owner.Local
    }
}
