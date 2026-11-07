package com.obsidianscout.blob

import com.obsidianscout.admin.ClusterManagementService
import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.BlobStorageConfig
import com.obsidianscout.db.BlobObjects
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.writeTransactionWithRetry
import com.obsidianscout.integrations.ClusterLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.blob.BlobService")

/**
 * Photos and attachments, kept in SeaweedFS instead of as base64 in database rows.
 *
 * Every server downloads and runs a SeaweedFS volume server. In a CockroachDB cluster one node, picked with a
 * [ClusterLease], also runs the master; if it goes away another node takes the lease and the volume servers
 * re-register with it. A server running on its own (SQLite/Postgres) runs both itself on 127.0.0.1.
 * When SeaweedFS can't run or is briefly unreachable, writes land in a local directory and are moved into
 * SeaweedFS once it is back.
 *
 * Database columns hold a reference instead of the bytes: `blob:<id>` for avatars and Event Info files,
 * `/api/blobs/<id>` inside scouting JSON so clients can use it directly as an image URL.
 */
object BlobService {
    const val KIND_AVATAR = "avatar"
    const val KIND_EVENT_FILE = "event_file"
    const val KIND_PIT_PHOTO = "pit_photo"
    const val KIND_SCOUTING_PHOTO = "scouting_photo"
    /** Kinds served by GET /api/blobs/{id} to anyone holding the (unguessable) URL, like the base64 they replace. */
    val PUBLIC_URL_KINDS = setOf(KIND_PIT_PHOTO, KIND_SCOUTING_PHOTO)

    private const val BACKEND_SEAWEED = "seaweed"
    private const val BACKEND_LOCAL = "local"
    private const val MASTER_LEASE = "seaweedfs-master"
    private const val MASTER_LEASE_TTL = 45L
    private const val MAINTENANCE_LEASE = "blob-maintenance"

    const val REF_PREFIX = "blob:"
    const val URL_PREFIX = "/api/blobs/"
    private val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val URL_REGEX = Regex("^/api/blobs/($UUID_PATTERN)$")
    val URL_IN_TEXT_REGEX = Regex("/api/blobs/($UUID_PATTERN)")

    fun ref(id: UUID) = "$REF_PREFIX$id"
    fun url(id: UUID) = "$URL_PREFIX$id"
    fun idFromRef(value: String?): UUID? =
        value?.takeIf { it.startsWith(REF_PREFIX) }?.let { runCatching { UUID.fromString(it.removePrefix(REF_PREFIX)) }.getOrNull() }
    fun idFromUrl(value: String?): UUID? =
        value?.let { URL_REGEX.matchEntire(it) }?.let { runCatching { UUID.fromString(it.groupValues[1]) }.getOrNull() }

    data class BlobMeta(
        val id: UUID,
        val backend: String,
        val storeKey: String,
        val kind: String,
        val teamNumber: Int?,
        val program: String?,
        val contentType: String,
        val sizeBytes: Long,
        val replication: String
    )

    class Blob(val meta: BlobMeta, val bytes: ByteArray)

    @Volatile private var config: BlobStorageConfig = BlobStorageConfig()
    @Volatile private var clusterMode = false
    @Volatile private var orchestrator: SeaweedOrchestrator? = null
    @Volatile private var installed = false
    @Volatile private var lastMasterLeaseMs = 0L
    @Volatile private var seaweed: SeaweedBlobStore? = null
    @Volatile private var local: LocalBlobStore = LocalBlobStore(File(config.local_directory))
    @Volatile var seaweedReady = false
        private set

    private var scope: CoroutineScope? = null
    private var coordinatorJob: Job? = null
    private var maintenanceJob: Job? = null

    private val metaCache = ConcurrentHashMap<UUID, BlobMeta>()
    private val bytesCache = BytesLru(48L * 1024 * 1024)

    /** True once [start] ran with blob storage enabled; until then callers keep writing base64 (the sweeper moves it later). */
    val isEnabled: Boolean get() = scope != null

    /** True once a store is usable for writes this node can share with the rest of the cluster. */
    fun canMigrate(): Boolean = if (clusterMode) seaweedReady else true

    @Synchronized
    fun start(appConfig: AppConfig, isCluster: Boolean) {
        if (scope != null) return
        config = appConfig.blob_storage
        clusterMode = isCluster
        local = LocalBlobStore(File(config.local_directory))
        if (!config.enabled) {
            log.info("[Blobs] blob_storage.enabled is false: photos stay where they are.")
            return
        }
        orchestrator = SeaweedOrchestrator(config)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        coordinatorJob = s.launch {
            while (isActive) {
                try {
                    coordinate()
                } catch (e: Exception) {
                    log.warn("[Blobs] SeaweedFS coordination error: ${e.message}")
                }
                delay(10_000)
            }
        }
        maintenanceJob = s.launch {
            // "Run now" on the progress page wakes the loop early instead of waiting out the delay.
            var forced = withTimeoutOrNull(30_000L) { wake.receive() } != null
            var tick = 0L
            while (isActive) {
                runMaintenancePass(includeAllEntryTables = forced || tick % 6 == 0L, collectGarbage = tick % 36 == 0L)
                tick++
                forced = withTimeoutOrNull(10 * 60_000L) { wake.receive() } != null
            }
        }
    }

    // ---------------------------------------------------------------- progress (for the Blob Storage page)

    data class PassInfo(
        val node: String,
        val startedAt: Instant,
        val finishedAt: Instant?,
        val moved: Int,
        val promoted: Int,
        val outcome: String,
        val error: String?
    )

    @Volatile var lastPass: PassInfo? = null
        private set
    @Volatile var runningPass: PassInfo? = null
        private set
    @Volatile var movedSinceStart = 0L
        private set
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Asks this node to run a maintenance pass now. False when blob storage isn't running here. */
    fun requestPass(): Boolean {
        if (!isEnabled) return false
        wake.trySend(Unit)
        return true
    }

    private fun runMaintenancePass(includeAllEntryTables: Boolean, collectGarbage: Boolean) {
        val node = ClusterManagementService.getLocalTailscaleIp()
        val started = Instant.now()
        runningPass = PassInfo(node, started, null, 0, 0, "running", null)
        var moved = 0
        var promoted = 0
        var outcome = "done"
        var error: String? = null
        try {
            promoted = promoteLocalBlobs()
            when {
                !config.migrate_existing -> outcome = "migration turned off (blob_storage.migrate_existing)"
                !canMigrate() -> outcome = "waiting for SeaweedFS"
                // Every node may promote its own local files; only one at a time sweeps the database.
                // Released when the pass ends; the TTL only matters if this node dies mid-pass. Two passes
                // overlapping is harmless (every row swap is compare-and-set), just wasted work.
                ClusterLease.tryAcquire(MAINTENANCE_LEASE, 10 * 60) != ClusterLease.Result.ACQUIRED ->
                    outcome = "another node is running the sweep"
                else -> try {
                    moved = BlobMigrationService.runOnce(config.migration_batch_size, includeAllEntryTables)
                    reReplicate()
                    if (collectGarbage) collectGarbage()
                } finally {
                    ClusterLease.release(MAINTENANCE_LEASE)
                }
            }
        } catch (e: Exception) {
            outcome = "failed"
            error = e.message ?: e.javaClass.simpleName
            log.warn("[Blobs] Maintenance pass failed: $error")
        }
        movedSinceStart += moved
        lastPass = PassInfo(node, started, Instant.now(), moved, promoted, outcome, error)
        runningPass = null
    }

    data class NodeState(
        val clusterMode: Boolean,
        val platformSupported: Boolean,
        val installed: Boolean,
        val seaweedVersion: String,
        val seaweedReady: Boolean,
        val masterAddress: String?,
        val runsMaster: Boolean,
        val runsVolume: Boolean,
        val dataDirectory: String?
    )

    fun nodeState(): NodeState {
        val orch = orchestrator
        return NodeState(
            clusterMode = clusterMode,
            platformSupported = orch?.isPlatformSupported ?: false,
            installed = installed,
            seaweedVersion = config.seaweed_version,
            seaweedReady = seaweedReady,
            masterAddress = seaweed?.masterAddress,
            runsMaster = orch?.isMasterRunning() ?: false,
            runsVolume = orch?.isVolumeRunning() ?: false,
            dataDirectory = orch?.rootDir?.absolutePath
        )
    }

    /** Volume servers the master can see, or empty when it can't be reached. */
    fun dataNodes(): List<SeaweedBlobStore.DataNode> = seaweed?.let { runCatching { it.topology() }.getOrNull() }.orEmpty()

    fun stop() {
        scope?.cancel()
        scope = null
        runCatching { ClusterLease.release(MASTER_LEASE, masterHolder()) }
        orchestrator?.stopAll()
    }

    private fun masterHolder(): String = "${ClusterManagementService.getLocalTailscaleIp()}:${config.master_port}"

    private fun coordinate() {
        val orch = orchestrator ?: return
        if (!installed && orch.isPlatformSupported) installed = orch.ensureInstalled()

        val masterAddress: String? = if (!clusterMode) {
            if (!installed) null else {
                orch.ensureMaster("127.0.0.1")
                orch.ensureVolume("127.0.0.1", "127.0.0.1:${config.master_port}")
                "127.0.0.1:${config.master_port}"
            }
        } else {
            val ip = ClusterManagementService.getLocalTailscaleIp()
            if (ip == "127.0.0.1") {
                // Advertising loopback would send every other node to its own localhost.
                seaweed?.masterAddress
            } else {
                val me = "$ip:${config.master_port}"
                val address = when (if (installed) ClusterLease.tryAcquire(MASTER_LEASE, MASTER_LEASE_TTL, holder = me) else ClusterLease.Result.HELD_ELSEWHERE) {
                    ClusterLease.Result.ACQUIRED -> {
                        lastMasterLeaseMs = System.currentTimeMillis()
                        orch.ensureMaster(ip)
                        me
                    }
                    ClusterLease.Result.HELD_ELSEWHERE -> {
                        if (orch.isMasterRunning()) {
                            log.info("[Blobs] Another node holds the SeaweedFS master lease; stopping the local master.")
                            orch.stopMaster()
                        }
                        runCatching { ClusterLease.holderOf(MASTER_LEASE) }.getOrNull() ?: seaweed?.masterAddress
                    }
                    ClusterLease.Result.UNAVAILABLE -> {
                        // Cut off from the database, this node can't tell whether its lease has passed to another
                        // node. Once it would have expired, step down: two masters handing out file ids for the
                        // same volumes could overwrite each other's files.
                        if (orch.isMasterRunning() && System.currentTimeMillis() - lastMasterLeaseMs > MASTER_LEASE_TTL * 1000) {
                            log.warn("[Blobs] Lost the database while running the SeaweedFS master; stepping down until it is back.")
                            orch.stopMaster()
                            null
                        } else {
                            seaweed?.masterAddress
                        }
                    }
                }
                // A node that can't run SeaweedFS itself still reads and writes through the others.
                if (address != null && installed) orch.ensureVolume(ip, address)
                address
            }
        }

        if (masterAddress == null) {
            seaweedReady = false
            return
        }
        val store = seaweed ?: SeaweedBlobStore(masterAddress).also { seaweed = it }
        if (store.masterAddress != masterAddress) {
            log.info("[Blobs] SeaweedFS master is now $masterAddress")
            store.masterAddress = masterAddress
            store.forgetStatus()
        }
        val ready = store.dataNodeCount() > 0
        if (ready != seaweedReady) log.info(if (ready) "[Blobs] SeaweedFS is ready (master $masterAddress)." else "[Blobs] SeaweedFS is not reachable; new files go to local disk for now.")
        seaweedReady = ready
    }

    // ---------------------------------------------------------------- read / write

    /** Stores [bytes] and returns the new blob's id. Must not be called inside a database transaction. */
    fun put(bytes: ByteArray, contentType: String, kind: String, teamNumber: Int?, program: String?): UUID {
        val id = UUID.randomUUID()
        val (backend, key, replication) = storeBytes(id, bytes, contentType)
        val meta = BlobMeta(id, backend, key, kind, teamNumber, program, contentType, bytes.size.toLong(), replication)
        try {
            writeTransactionWithRetry {
                BlobObjects.insert {
                    it[BlobObjects.id] = id
                    it[BlobObjects.backend] = backend
                    it[BlobObjects.storeKey] = key
                    it[BlobObjects.kind] = kind
                    it[BlobObjects.teamNumber] = teamNumber
                    it[BlobObjects.program] = program
                    it[BlobObjects.contentType] = contentType.take(128)
                    it[BlobObjects.sizeBytes] = bytes.size.toLong()
                    it[BlobObjects.sha256] = sha256(bytes)
                    it[BlobObjects.replication] = replication
                    it[BlobObjects.createdAt] = Instant.now()
                }
            }
        } catch (e: Exception) {
            runCatching { deleteBytes(backend, key) }
            throw e
        }
        metaCache[id] = meta
        bytesCache.put(id, bytes)
        return id
    }

    private fun storeBytes(id: UUID, bytes: ByteArray, contentType: String): Triple<String, String, String> {
        val store = seaweed
        if (store != null && seaweedReady) {
            try {
                val replication = store.replicationFor(store.dataNodeCount())
                return Triple(BACKEND_SEAWEED, store.put(bytes, contentType, replication), replication)
            } catch (e: Exception) {
                log.warn("[Blobs] SeaweedFS write failed, keeping the file on local disk until it is back: ${e.message}")
                store.forgetStatus()
            }
        }
        val key = local.keyFor(id)
        local.put(key, bytes)
        return Triple(BACKEND_LOCAL, key, "000")
    }

    fun meta(id: UUID): BlobMeta? = metaCache[id] ?: loadMeta(id)?.also { metaCache[id] = it }

    private fun loadMeta(id: UUID): BlobMeta? = com.obsidianscout.db.readTransaction {
        BlobObjects.selectAll().where { BlobObjects.id eq id }.firstOrNull()?.let {
            BlobMeta(
                id = id,
                backend = it[BlobObjects.backend],
                storeKey = it[BlobObjects.storeKey],
                kind = it[BlobObjects.kind],
                teamNumber = it[BlobObjects.teamNumber],
                program = it[BlobObjects.program],
                contentType = it[BlobObjects.contentType],
                sizeBytes = it[BlobObjects.sizeBytes],
                replication = it[BlobObjects.replication]
            )
        }
    }

    /** The blob's bytes and metadata, or null if it doesn't exist or no reachable store has it. */
    fun get(id: UUID): Blob? {
        val meta = meta(id) ?: return null
        bytesCache.get(id)?.let { return Blob(meta, it) }
        val bytes = readBytes(meta) ?: run {
            // The row may have moved (local -> SeaweedFS, or re-replicated) since it was cached.
            metaCache.remove(id)
            val fresh = loadMeta(id) ?: return null
            metaCache[id] = fresh
            if (fresh == meta) null else readBytes(fresh)
        } ?: return null
        bytesCache.put(id, bytes)
        return Blob(metaCache[id] ?: meta, bytes)
    }

    fun getByRef(ref: String?): Blob? = idFromRef(ref)?.let { get(it) }

    private fun readBytes(meta: BlobMeta): ByteArray? = when (meta.backend) {
        BACKEND_SEAWEED -> seaweed?.let { store ->
            try {
                store.get(meta.storeKey)
            } catch (e: Exception) {
                log.debug("[Blobs] SeaweedFS read of ${meta.id} failed: ${e.message}")
                null
            }
        }
        BACKEND_LOCAL -> local.get(meta.storeKey)
        else -> null
    }

    /** Removes the blob row and its bytes. Safe to call for ids that no longer exist. */
    fun delete(id: UUID) {
        val meta = meta(id)
        runCatching { writeTransactionWithRetry { BlobObjects.deleteWhere { BlobObjects.id eq id } } }
            .onFailure { log.warn("[Blobs] Could not delete blob row $id: ${it.message}"); return }
        metaCache.remove(id)
        bytesCache.remove(id)
        if (meta != null) runCatching { deleteBytes(meta.backend, meta.storeKey) }
            .onFailure { log.debug("[Blobs] Could not delete bytes of $id: ${it.message}") }
    }

    fun deleteRef(ref: String?) {
        idFromRef(ref)?.let { delete(it) }
    }

    private fun deleteBytes(backend: String, key: String) {
        when (backend) {
            BACKEND_SEAWEED -> seaweed?.delete(key)
            BACKEND_LOCAL -> local.delete(key)
        }
    }

    // ---------------------------------------------------------------- maintenance

    /** Moves files written to local disk while SeaweedFS was down into SeaweedFS. Runs on every node. */
    private fun promoteLocalBlobs(): Int {
        val store = seaweed ?: return 0
        if (!seaweedReady) return 0
        val rows = com.obsidianscout.db.readTransaction {
            BlobObjects.select(BlobObjects.id, BlobObjects.storeKey, BlobObjects.contentType)
                .where { BlobObjects.backend eq BACKEND_LOCAL }
                .limit(200)
                .map { Triple(it[BlobObjects.id].value, it[BlobObjects.storeKey], it[BlobObjects.contentType]) }
        }
        var moved = 0
        for ((id, key, contentType) in rows) {
            val bytes = local.get(key) ?: continue // another node wrote this one
            val replication = store.replicationFor(store.dataNodeCount())
            val fid = store.put(bytes, contentType, replication)
            val updated = writeTransactionWithRetry {
                BlobObjects.update({ (BlobObjects.id eq id) and (BlobObjects.backend eq BACKEND_LOCAL) }) {
                    it[BlobObjects.backend] = BACKEND_SEAWEED
                    it[BlobObjects.storeKey] = fid
                    it[BlobObjects.replication] = replication
                }
            }
            if (updated == 1) {
                local.delete(key)
                metaCache.remove(id)
                moved++
            } else {
                runCatching { store.delete(fid) }
            }
        }
        if (moved > 0) log.info("[Blobs] Moved $moved locally stored file(s) into SeaweedFS.")
        return moved
    }

    /** Objects written while the cluster had one server have a single copy; give them a second once there are two. */
    private fun reReplicate() {
        val store = seaweed ?: return
        if (!seaweedReady || store.dataNodeCount() < 2) return
        val rows = com.obsidianscout.db.readTransaction {
            BlobObjects.select(BlobObjects.id, BlobObjects.storeKey, BlobObjects.contentType)
                .where { (BlobObjects.backend eq BACKEND_SEAWEED) and (BlobObjects.replication eq "000") }
                .limit(200)
                .map { Triple(it[BlobObjects.id].value, it[BlobObjects.storeKey], it[BlobObjects.contentType]) }
        }
        var copied = 0
        for ((id, oldFid, contentType) in rows) {
            val bytes = runCatching { store.get(oldFid) }.getOrNull() ?: continue
            val newFid = store.put(bytes, contentType, "001")
            val updated = writeTransactionWithRetry {
                BlobObjects.update({ (BlobObjects.id eq id) and (BlobObjects.storeKey eq oldFid) }) {
                    it[BlobObjects.storeKey] = newFid
                    it[BlobObjects.replication] = "001"
                }
            }
            if (updated == 1) {
                runCatching { store.delete(oldFid) }
                metaCache.remove(id)
                copied++
            } else {
                runCatching { store.delete(newFid) }
            }
        }
        if (copied > 0) log.info("[Blobs] Gave $copied file(s) a second copy on another server.")
    }

    /**
     * Deletes blobs nothing references any more (a replaced avatar, a deleted entry). Only blobs older than a
     * week are considered, and every read goes to the primary database: during quorum loss the fallback
     * snapshot holds only some rows, and treating it as complete would delete live photos.
     */
    private fun collectGarbage() {
        val primary = DatabaseFactory.primaryDatabase ?: return
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return
        val cutoff = Instant.now().minusSeconds(7L * 24 * 3600)
        val referenced = BlobMigrationService.collectReferencedIds(primary)
        val candidates = transaction(db = primary) {
            BlobObjects.select(BlobObjects.id).where { BlobObjects.createdAt less cutoff }.map { it[BlobObjects.id].value }
        }
        val orphans = candidates.filterNot { it in referenced }
        if (orphans.isEmpty()) return
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return
        log.info("[Blobs] Removing ${orphans.size} unreferenced file(s).")
        orphans.forEach { delete(it) }
    }

    data class FreedBlobs(val count: Int, val bytes: Long, val skippedReason: String? = null)

    /**
     * Deletes those of [candidates] that no row references any more, right away rather than at the weekly garbage
     * collection. Call after the rows that pointed at them are committed as deleted. A photo copied to an alliance
     * partner (or otherwise still used) is kept. Reads go to the primary database only, and nothing is deleted
     * during quorum loss; the regular garbage collection catches anything skipped.
     */
    fun freeIfUnreferenced(candidates: Collection<UUID>): FreedBlobs {
        if (candidates.isEmpty()) return FreedBlobs(0, 0)
        if (!isEnabled) return FreedBlobs(0, 0, "blob storage isn't running on this server")
        val primary = DatabaseFactory.primaryDatabase ?: return FreedBlobs(0, 0, "database not ready")
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return FreedBlobs(0, 0, "cluster quorum lost")
        return try {
            val referenced = BlobMigrationService.collectReferencedIds(primary)
            val orphans = candidates.toSet().filterNot { it in referenced }
            if (orphans.isEmpty() || com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return FreedBlobs(0, 0)
            var bytes = 0L
            var count = 0
            for (id in orphans) {
                val size = meta(id)?.sizeBytes ?: continue
                delete(id)
                bytes += size
                count++
            }
            if (count > 0) log.info("[Blobs] Freed $count file(s) (${bytes} bytes) no longer used after a delete.")
            FreedBlobs(count, bytes)
        } catch (e: Exception) {
            log.warn("[Blobs] Freeing files after a delete failed; the weekly cleanup will retry: ${e.message}")
            FreedBlobs(0, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Recently read blobs, so chat avatars and photo grids don't go to SeaweedFS on every request. */
    private class BytesLru(private val maxBytes: Long) {
        private val map = object : LinkedHashMap<UUID, ByteArray>(64, 0.75f, true) {}
        private var total = 0L

        @Synchronized fun get(id: UUID): ByteArray? = map[id]

        @Synchronized fun put(id: UUID, bytes: ByteArray) {
            if (bytes.size > 2 * 1024 * 1024) return
            map.put(id, bytes)?.let { total -= it.size }
            total += bytes.size
            val it = map.entries.iterator()
            while (total > maxBytes && it.hasNext()) {
                total -= it.next().value.size
                it.remove()
            }
        }

        @Synchronized fun remove(id: UUID) {
            map.remove(id)?.let { total -= it.size }
        }
    }
}
