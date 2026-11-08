package com.obsidianscout.db

import com.obsidianscout.auth.ApiException
import com.obsidianscout.config.JsonSupport
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToStream
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.db.BackupExportService")

/**
 * Backup exports built in the background and downloaded in parts.
 *
 * Building a large export (reading every row, embedding photos) can take longer than Cloudflare's 100 s response
 * limit, and downloading it can take longer than Ktor's 60 s idle read timeout, which cancels any response that
 * streams for a minute without the client sending anything. So the page asks for an export, polls until it is built,
 * then fetches it one [CHUNK_BYTES] part per request and saves the joined parts.
 *
 * The parts live in the database ([BackupExports], [BackupExportChunks]) because each request may reach a different
 * node. They are written as the file is serialized, so the finished file is never held in memory whole.
 */
object BackupExportService {

    const val CHUNK_BYTES = BackupUploadService.CHUNK_BYTES

    private const val MAX_BUILDING_PER_USER = 2
    private const val HEARTBEAT_MS = 20_000L
    private const val DEAD_BUILD_MS = 3 * 60_000L
    /** How long a finished export waits to be downloaded. */
    private const val KEEP_FINISHED_MS = 2 * 60 * 60_000L

    const val STATE_BUILDING = "building"
    const val STATE_DONE = "done"
    const val STATE_FAILED = "failed"

    @Serializable
    data class StartRequest(val type: String = "scouting", val format: String = "obsidiandb", val scope: String = "team")

    @Serializable
    data class ExportStatus(
        val exportId: String,
        val state: String,
        val fileName: String,
        val contentType: String,
        val totalBytes: Long,
        val totalChunks: Int,
        val error: String? = null
    )

    /** The file name the export is saved as. */
    fun fileName(teamNumber: Int, type: String, format: String, scope: String): String {
        val ext = if (format == "csv") "zip" else "obsidiandb"
        return if (scope == "global") "global_backup_$type.$ext" else "team_${teamNumber}_backup_$type.$ext"
    }

    fun start(owner: BackupUploadService.Owner, req: StartRequest): ExportStatus {
        val type = req.type.takeIf { it == "scouting" || it == "entire" }
            ?: throw ApiException(HttpStatusCode.BadRequest, "Unsupported export type: ${req.type}")
        val format = req.format.takeIf { it == "obsidiandb" || it == "csv" }
            ?: throw ApiException(HttpStatusCode.BadRequest, "Unsupported format: ${req.format}")
        val scope = if (req.scope == "global") "global" else "team"
        if (scope == "global" && !owner.isSuperAdmin) {
            throw ApiException(HttpStatusCode.Forbidden, "Only superadmins can perform global exports")
        }
        purgeExpired()

        val id = UUID.randomUUID()
        val now = Instant.now()
        val name = fileName(owner.teamNumber, type, format, scope)
        val contentType = if (format == "csv") "application/zip" else "application/json"
        writeTransactionWithRetry {
            val building = BackupExports.selectAll().where {
                (BackupExports.userId eq owner.userId) and (BackupExports.state eq STATE_BUILDING)
            }.count()
            if (building >= MAX_BUILDING_PER_USER) {
                throw ApiException(HttpStatusCode.TooManyRequests, "You already have $building exports being built. Wait for them to finish.")
            }
            BackupExports.insert {
                it[BackupExports.id] = id
                it[userId] = owner.userId
                it[teamNumber] = owner.teamNumber
                it[program] = owner.program
                it[exportType] = type
                it[BackupExports.format] = format
                it[BackupExports.scope] = scope
                it[BackupExports.fileName] = name
                it[BackupExports.contentType] = contentType
                it[state] = STATE_BUILDING
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        thread(name = "backup-export-$id", isDaemon = true) { build(id, owner, type, format, scope) }
        return ExportStatus(id.toString(), STATE_BUILDING, name, contentType, 0, 0)
    }

    fun status(owner: BackupUploadService.Owner, exportId: String): ExportStatus {
        val id = parseId(exportId)
        val row = transaction { ownedRow(owner, id) }
        var state = row[BackupExports.state]
        var error = row[BackupExports.error]
        if (state == STATE_BUILDING && row[BackupExports.updatedAt].isBefore(Instant.now().minusMillis(DEAD_BUILD_MS))) {
            error = "The server building this export stopped before it finished. Start the export again."
            state = STATE_FAILED
            runCatching { finish(id, STATE_FAILED, 0, 0, error, onlyIfStale = true) }
        }
        return ExportStatus(
            id.toString(), state, row[BackupExports.fileName], row[BackupExports.contentType],
            row[BackupExports.totalBytes], row[BackupExports.totalChunks], error
        )
    }

    /** Part [index] of a finished export. */
    fun chunk(owner: BackupUploadService.Owner, exportId: String, index: Int): ByteArray {
        val id = parseId(exportId)
        return writeTransactionWithRetry {
            val row = ownedRow(owner, id)
            if (row[BackupExports.state] != STATE_DONE) throw ApiException(HttpStatusCode.Conflict, "This export isn't ready")
            val bytes = BackupExportChunks.select(BackupExportChunks.data)
                .where { (BackupExportChunks.exportId eq id) and (BackupExportChunks.chunkIndex eq index) }
                .firstOrNull()?.get(BackupExportChunks.data)?.bytes
                ?: throw ApiException(HttpStatusCode.NotFound, "Part $index of this export doesn't exist")
            // Keeps a slow download from expiring part-way through.
            BackupExports.update({ BackupExports.id eq id }) { it[updatedAt] = Instant.now() }
            bytes
        }
    }

    /** Deletes an export and its parts (the page calls this once the download is saved). */
    fun delete(owner: BackupUploadService.Owner, exportId: String) {
        val id = parseId(exportId)
        writeTransactionWithRetry {
            ownedRow(owner, id)
            BackupExportChunks.deleteWhere { BackupExportChunks.exportId eq id }
            BackupExports.deleteWhere { BackupExports.id eq id }
        }
    }

    // ---------------------------------------------------------------- build

    private fun build(id: UUID, owner: BackupUploadService.Owner, type: String, format: String, scope: String) {
        val running = AtomicBoolean(true)
        val heartbeat = thread(name = "backup-export-heartbeat-$id", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                runCatching {
                    writeTransactionWithRetry {
                        BackupExports.update({ (BackupExports.id eq id) and (BackupExports.state eq STATE_BUILDING) }) { it[updatedAt] = Instant.now() }
                    }
                }
            }
        }
        try {
            BackupUploadService.heavyJobSlot.acquire()
            try {
                val out = ChunkWriter(id)
                out.use {
                    if (format == "csv") {
                        it.write(BackupService.exportCsv(owner.teamNumber, type, scope, owner.program))
                    } else {
                        val backup = BackupService.exportBackup(owner.teamNumber, type, scope, owner.program)
                        JsonSupport.json.encodeToStream(ObsidianDbBackup.serializer(), backup, it)
                    }
                }
                // Cancelled while it was building: its row is gone, so drop the parts just written.
                if (finish(id, STATE_DONE, out.totalBytes, out.chunks, null) == 0) deleteChunks(id)
                else log.info("[Backup] Built export $id (${out.totalBytes} bytes, ${out.chunks} part(s)) for team ${owner.teamNumber}.")
            } finally {
                BackupUploadService.heavyJobSlot.release()
            }
        } catch (e: Throwable) {
            log.warn("[Backup] Export $id failed: ${e.message}", e)
            deleteChunks(id)
            val message = when (e) {
                is ApiException -> e.message
                is OutOfMemoryError -> "The server ran out of memory building this export."
                else -> "Export failed: ${e.message?.take(500) ?: e.javaClass.simpleName}"
            }
            runCatching { finish(id, STATE_FAILED, 0, 0, message) }
            if (e is OutOfMemoryError) throw e
        } finally {
            running.set(false)
            heartbeat.interrupt()
        }
    }

    /** Writes everything it is given to [BackupExportChunks] in [CHUNK_BYTES] parts, holding at most one part. */
    private class ChunkWriter(private val exportUuid: UUID) : OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var filled = 0
        var chunks = 0
            private set
        var totalBytes = 0L
            private set

        override fun write(b: Int) {
            buffer[filled++] = b.toByte()
            totalBytes++
            if (filled == buffer.size) flushChunk()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var pos = off
            var left = len
            while (left > 0) {
                val n = minOf(left, buffer.size - filled)
                System.arraycopy(b, pos, buffer, filled, n)
                filled += n
                pos += n
                left -= n
                totalBytes += n
                if (filled == buffer.size) flushChunk()
            }
        }

        override fun close() {
            if (filled > 0) flushChunk()
        }

        private fun flushChunk() {
            val bytes = buffer.copyOf(filled)
            val index = chunks
            writeTransactionWithRetry {
                BackupExportChunks.insert {
                    it[exportId] = exportUuid
                    it[chunkIndex] = index
                    it[data] = ExposedBlob(bytes)
                }
            }
            chunks++
            filled = 0
        }
    }

    // ---------------------------------------------------------------- housekeeping

    /** Returns the number of rows updated (0 when the export was deleted meanwhile, or no longer stale). */
    private fun finish(id: UUID, state: String, totalBytes: Long, totalChunks: Int, error: String?, onlyIfStale: Boolean = false): Int =
        writeTransactionWithRetry {
            val condition = if (onlyIfStale) {
                (BackupExports.id eq id) and (BackupExports.state eq STATE_BUILDING) and (BackupExports.updatedAt less Instant.now().minusMillis(DEAD_BUILD_MS))
            } else {
                BackupExports.id eq id
            }
            BackupExports.update({ condition }) {
                it[BackupExports.state] = state
                it[BackupExports.totalBytes] = totalBytes
                it[BackupExports.totalChunks] = totalChunks
                it[BackupExports.error] = error?.take(2000)
                it[updatedAt] = Instant.now()
            }
        }

    private fun deleteChunks(id: UUID) {
        runCatching { writeTransactionWithRetry { BackupExportChunks.deleteWhere { exportId eq id } } }
    }

    /** Removes exports nobody downloaded, dead builds and failures, after [KEEP_FINISHED_MS]. Safe on every node. */
    fun purgeExpired() {
        try {
            val cutoff = Instant.now().minusMillis(KEEP_FINISHED_MS)
            val expired = transaction {
                BackupExports.select(BackupExports.id).where { BackupExports.updatedAt less cutoff }.map { it[BackupExports.id].value }
            }
            if (expired.isEmpty()) return
            writeTransactionWithRetry {
                BackupExportChunks.deleteWhere { exportId inList expired }
                BackupExports.deleteWhere { BackupExports.id inList expired }
            }
            log.info("[Backup] Removed ${expired.size} expired backup export(s).")
        } catch (e: Exception) {
            log.debug("[Backup] Expired export cleanup skipped: ${e.message}")
        }
    }

    private fun parseId(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "Export not found")

    private fun ownedRow(owner: BackupUploadService.Owner, id: UUID): ResultRow =
        BackupExports.selectAll().where { (BackupExports.id eq id) and (BackupExports.userId eq owner.userId) }.firstOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "Export not found")
}
