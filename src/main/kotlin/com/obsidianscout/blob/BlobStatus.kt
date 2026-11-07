package com.obsidianscout.blob

import com.obsidianscout.admin.ClusterManagementService
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.BlobObjects
import com.obsidianscout.db.EventInfoFiles
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.charLength
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select

@Serializable
data class BlobCategoryProgress(
    val key: String,
    val label: String,
    /** Rows still holding base64 in the database. */
    val remainingRows: Long,
    /** Approximate characters of base64 those rows still hold. */
    val remainingChars: Long,
    /** Objects of this kind in the blob store. */
    val storedObjects: Long,
    val storedBytes: Long,
    /** Match and qualitative tables are only swept hourly (or on "Run now"). */
    val sweptHourly: Boolean = false
)

@Serializable
data class BlobPassDto(
    val node: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val moved: Int,
    val promoted: Int,
    val outcome: String,
    val error: String? = null
)

@Serializable
data class BlobNodeDto(
    val ip: String,
    val serverVersion: String,
    val online: Boolean,
    val isLocal: Boolean,
    val onCurrentVersion: Boolean,
    /** This node's volume server is registered with the SeaweedFS master. */
    val volumeRegistered: Boolean,
    val isMaster: Boolean,
    val volumes: Int = 0
)

@Serializable
data class BlobStatusResponse(
    val enabled: Boolean,
    val mode: String,
    val reportingNode: String,
    val serverVersion: String,
    val seaweedVersion: String,
    val platformSupported: Boolean,
    val installed: Boolean,
    val seaweedReady: Boolean,
    val masterAddress: String? = null,
    val runsMaster: Boolean,
    val runsVolume: Boolean,
    val dataDirectory: String? = null,
    val volumeServers: Int,
    val categories: List<BlobCategoryProgress>,
    val pendingLocalObjects: Long,
    val singleCopyObjects: Long,
    val skippedRows: Int,
    val currentStep: String? = null,
    val runningPass: BlobPassDto? = null,
    val lastPass: BlobPassDto? = null,
    val movedSinceStart: Long,
    val nodes: List<BlobNodeDto>,
    val countsAt: String,
    val countsError: String? = null,
    /** Whether firewalls let the servers' SeaweedFS processes reach each other. */
    val network: BlobNetworkStatus? = null
)

/** Everything the Blob Storage page shows. Database counts scan tables, so they are cached for a short while. */
object BlobStatusService {
    private const val COUNTS_TTL_MS = 20_000L

    private data class Counts(
        val categories: List<BlobCategoryProgress>,
        val pendingLocal: Long,
        val singleCopy: Long,
        val at: java.time.Instant
    )

    @Volatile private var cached: Counts? = null
    @Volatile private var cachedAtMs = 0L

    suspend fun status(): BlobStatusResponse {
        val state = BlobService.nodeState()
        val appConfig = AppConfigLoader.load()
        val localIp = ClusterManagementService.getLocalTailscaleIp()

        var countsError: String? = null
        val counts = try {
            counts(force = false)
        } catch (e: Exception) {
            countsError = e.message ?: e.javaClass.simpleName
            cached
        }

        val dataNodes = BlobService.dataNodes()
        val masterIp = state.masterAddress?.substringBefore(':')
        val volumeIps = dataNodes.associateBy { it.url.substringBefore(':') }
        val clusterNodes = if (state.clusterMode) {
            runCatching { ClusterManagementService.getClusterNodes().nodes }.getOrDefault(emptyList())
        } else emptyList()
        val network = runCatching { BlobNetworkService.status(clusterNodes, state.clusterMode) }.getOrNull()
        val nodes = if (state.clusterMode) {
            clusterNodes.map { n ->
                BlobNodeDto(
                    ip = n.ip,
                    serverVersion = n.serverVersion,
                    online = n.status.equals("online", ignoreCase = true) || n.isLocal,
                    isLocal = n.isLocal,
                    onCurrentVersion = n.serverVersion == appConfig.current_version,
                    volumeRegistered = volumeIps.containsKey(n.ip),
                    isMaster = n.ip == masterIp,
                    volumes = volumeIps[n.ip]?.volumes ?: 0
                )
            }
        } else {
            listOf(
                BlobNodeDto(
                    ip = localIp,
                    serverVersion = appConfig.current_version,
                    online = true,
                    isLocal = true,
                    onCurrentVersion = true,
                    volumeRegistered = dataNodes.isNotEmpty(),
                    isMaster = state.runsMaster,
                    volumes = dataNodes.sumOf { it.volumes }
                )
            )
        }

        return BlobStatusResponse(
            enabled = BlobService.isEnabled,
            mode = if (state.clusterMode) "cluster" else "single",
            reportingNode = localIp,
            serverVersion = appConfig.current_version,
            seaweedVersion = state.seaweedVersion,
            platformSupported = state.platformSupported,
            installed = state.installed,
            seaweedReady = state.seaweedReady,
            masterAddress = state.masterAddress,
            runsMaster = state.runsMaster,
            runsVolume = state.runsVolume,
            dataDirectory = state.dataDirectory,
            volumeServers = dataNodes.size,
            categories = counts?.categories.orEmpty(),
            pendingLocalObjects = counts?.pendingLocal ?: 0,
            singleCopyObjects = counts?.singleCopy ?: 0,
            skippedRows = BlobMigrationService.skippedCount,
            currentStep = BlobMigrationService.currentStep,
            runningPass = BlobService.runningPass?.toDto(),
            lastPass = BlobService.lastPass?.toDto(),
            movedSinceStart = BlobService.movedSinceStart,
            nodes = nodes,
            countsAt = (counts?.at ?: java.time.Instant.now()).toString(),
            countsError = countsError,
            network = network
        )
    }

    /** Drops the cached counts so the next status call reads fresh ones (after "Run now"). */
    fun invalidate() {
        cachedAtMs = 0L
    }

    private fun counts(force: Boolean): Counts {
        val now = System.currentTimeMillis()
        cached?.let { if (!force && now - cachedAtMs < COUNTS_TTL_MS) return it }

        val stored = readTransaction {
            val n = BlobObjects.id.count()
            val bytes = BlobObjects.sizeBytes.sum()
            BlobObjects.select(BlobObjects.kind, n, bytes).groupBy(BlobObjects.kind)
                .associate { it[BlobObjects.kind] to (it[n] to (it[bytes] ?: 0L)) }
        }
        fun storedOf(vararg kinds: String) = kinds.map { stored[it] ?: (0L to 0L) }
            .fold(0L to 0L) { acc, p -> (acc.first + p.first) to (acc.second + p.second) }

        val avatars = remaining(Users, Users.profilePicture) { Users.profilePicture like "data:%" }
        val files = remaining(EventInfoFiles, EventInfoFiles.data) {
            (EventInfoFiles.data notLike "${BlobService.REF_PREFIX}%") and (EventInfoFiles.data neq "")
        }
        val pit = remaining(PitScoutingEntries, PitScoutingEntries.dataJson) { PitScoutingEntries.dataJson like "%\"data:image/%" }
        val match = remaining(ScoutingEntries, ScoutingEntries.dataJson) { ScoutingEntries.dataJson like "%\"data:image/%" }
        val qual = remaining(QualitativeScoutingEntries, QualitativeScoutingEntries.dataJson) { QualitativeScoutingEntries.dataJson like "%\"data:image/%" }

        val avatarStored = storedOf(BlobService.KIND_AVATAR)
        val fileStored = storedOf(BlobService.KIND_EVENT_FILE)
        val pitStored = storedOf(BlobService.KIND_PIT_PHOTO)
        val scoutStored = storedOf(BlobService.KIND_SCOUTING_PHOTO)

        val categories = listOf(
            BlobCategoryProgress("avatars", "Profile pictures", avatars.first, avatars.second, avatarStored.first, avatarStored.second),
            BlobCategoryProgress("event_files", "Event Info files", files.first, files.second, fileStored.first, fileStored.second),
            BlobCategoryProgress("pit_photos", "Pit scouting photos", pit.first, pit.second, pitStored.first, pitStored.second),
            // Match and qualitative photos share one blob kind, so their stored totals are reported together.
            BlobCategoryProgress("scouting_photos", "Match and qualitative photos", match.first + qual.first, match.second + qual.second, scoutStored.first, scoutStored.second, sweptHourly = true)
        )

        val (pendingLocal, singleCopy) = readTransaction {
            val local = BlobObjects.select(BlobObjects.id).where { BlobObjects.backend eq "local" }.count()
            val single = BlobObjects.select(BlobObjects.id).where { (BlobObjects.backend eq "seaweed") and (BlobObjects.replication eq "000") }.count()
            local to single
        }

        val result = Counts(categories, pendingLocal, singleCopy, java.time.Instant.now())
        cached = result
        cachedAtMs = now
        return result
    }

    /** Row count and total characters of [column] for rows matching [where]. */
    private fun <C : String?> remaining(table: UUIDTable, column: Column<C>, where: () -> org.jetbrains.exposed.v1.core.Op<Boolean>): Pair<Long, Long> =
        readTransaction {
            val n = table.id.count()
            val chars = column.charLength().sum()
            table.select(n, chars).where { where() }.firstOrNull()
                ?.let { (it[n]) to (it[chars]?.toLong() ?: 0L) } ?: (0L to 0L)
        }

    private fun BlobService.PassInfo.toDto() = BlobPassDto(
        node = node,
        startedAt = startedAt.toString(),
        finishedAt = finishedAt?.toString(),
        moved = moved,
        promoted = promoted,
        outcome = outcome,
        error = error
    )
}
