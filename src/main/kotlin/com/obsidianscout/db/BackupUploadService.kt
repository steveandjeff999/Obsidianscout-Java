package com.obsidianscout.db

import com.obsidianscout.auth.ApiException
import com.obsidianscout.config.JsonSupport
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.db.BackupUploadService")

/**
 * Backup imports uploaded in parts and run in the background.
 *
 * Behind Cloudflare a request body can't exceed 100 MB and a response must start within 100 s, and the built-in
 * HTTPS proxy and Ktor's idle timeout are shorter still. So the browser sends the file as [CHUNK_BYTES] parts, asks for
 * the import once they are all in, and polls for the result: no request carries much data or runs for long.
 *
 * Upload state and parts live in the database ([BackupUploads], [BackupUploadChunks]) because each request may reach a
 * different node. The node that receives "complete" runs the import and keeps a heartbeat on the row; an import whose
 * heartbeat stops (its node went down) is reported as failed.
 */
object BackupUploadService {

    /** Size of each uploaded part: far below Cloudflare's 100 MB body limit, and quick to send even on a slow link. */
    const val CHUNK_BYTES = 4 * 1024 * 1024

    /** Uploads one user may have in progress at once, so abandoned ones can't fill the database. */
    private const val MAX_ACTIVE_UPLOADS_PER_USER = 2

    private const val HEARTBEAT_MS = 20_000L
    /** An import whose heartbeat is older than this is treated as dead (its node stopped). */
    private const val DEAD_IMPORT_MS = 3 * 60_000L
    private const val ABANDONED_UPLOAD_MS = 2 * 60 * 60_000L
    private const val KEEP_FINISHED_MS = 24 * 60 * 60_000L

    const val STATE_UPLOADING = "uploading"
    const val STATE_IMPORTING = "importing"
    const val STATE_DONE = "done"
    const val STATE_FAILED = "failed"

    private val ALLOWED_EXTENSIONS = listOf(".obsidiandb", ".json", ".zip")

    /**
     * A whole backup is held in memory while it is imported or exported, so this node runs one such job at a time
     * (shared with BackupExportService).
     */
    internal val heavyJobSlot = Semaphore(1, true)

    data class Owner(val userId: String, val teamNumber: Int, val program: String, val isSuperAdmin: Boolean)

    @Serializable
    data class StartRequest(val fileName: String, val totalBytes: Long, val scope: String = "team")

    @Serializable
    data class StartResponse(val uploadId: String, val chunkSize: Int, val totalChunks: Int, val maxBytes: Long)

    @Serializable
    data class UploadStatus(
        val uploadId: String,
        val state: String,
        val fileName: String,
        val receivedChunks: Int,
        val totalChunks: Int,
        val report: ImportReport? = null,
        val error: String? = null
    )

    // ---------------------------------------------------------------- upload

    fun start(owner: Owner, req: StartRequest): StartResponse {
        val fileName = req.fileName.substringAfterLast('/').substringAfterLast('\\').trim().take(255)
        if (ALLOWED_EXTENSIONS.none { fileName.lowercase().endsWith(it) }) {
            throw ApiException(HttpStatusCode.BadRequest, "Unsupported file format. Please upload .obsidiandb or .zip")
        }
        if (req.totalBytes <= 0) throw ApiException(HttpStatusCode.BadRequest, "The file is empty")
        if (req.totalBytes > BackupService.MAX_IMPORT_BYTES) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "Backup file is too large (limit ${BackupService.MAX_IMPORT_BYTES / (1024 * 1024)} MB)")
        }
        val scope = if (req.scope == "global") "global" else "team"
        if (scope == "global" && !owner.isSuperAdmin) {
            throw ApiException(HttpStatusCode.Forbidden, "Only superadmins can perform global imports")
        }
        purgeExpired()

        val totalChunks = ((req.totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        val id = UUID.randomUUID()
        val now = Instant.now()
        writeTransactionWithRetry {
            val active = BackupUploads.selectAll().where {
                (BackupUploads.userId eq owner.userId) and (BackupUploads.state inList listOf(STATE_UPLOADING, STATE_IMPORTING))
            }.count()
            if (active >= MAX_ACTIVE_UPLOADS_PER_USER) {
                throw ApiException(HttpStatusCode.TooManyRequests, "You already have $active backup imports in progress. Wait for them to finish, or try again in a few hours.")
            }
            BackupUploads.insert {
                it[BackupUploads.id] = id
                it[userId] = owner.userId
                it[teamNumber] = owner.teamNumber
                it[program] = owner.program
                it[isSuperAdmin] = owner.isSuperAdmin
                it[requestedScope] = scope
                it[BackupUploads.fileName] = fileName
                it[BackupUploads.totalBytes] = req.totalBytes
                it[chunkSize] = CHUNK_BYTES
                it[BackupUploads.totalChunks] = totalChunks
                it[state] = STATE_UPLOADING
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        return StartResponse(id.toString(), CHUNK_BYTES, totalChunks, BackupService.MAX_IMPORT_BYTES)
    }

    /** Size part [index] must have: [CHUNK_BYTES], except the last, which holds the remainder. */
    fun expectedChunkBytes(totalBytes: Long, chunkSize: Int, index: Int): Int {
        val start = index.toLong() * chunkSize
        return minOf(chunkSize.toLong(), totalBytes - start).toInt()
    }

    /** Stores part [index]. Sending the same part again replaces it, so the browser can retry freely. */
    fun putChunk(owner: Owner, uploadId: String, index: Int, bytes: ByteArray) {
        val id = parseId(uploadId)
        writeTransactionWithRetry {
            val row = ownedRow(owner, id)
            if (row[BackupUploads.state] != STATE_UPLOADING) {
                throw ApiException(HttpStatusCode.Conflict, "This upload is no longer accepting parts")
            }
            val total = row[BackupUploads.totalChunks]
            if (index !in 0 until total) throw ApiException(HttpStatusCode.BadRequest, "Part $index is out of range (0-${total - 1})")
            val expected = expectedChunkBytes(row[BackupUploads.totalBytes], row[BackupUploads.chunkSize], index)
            if (bytes.size != expected) {
                throw ApiException(HttpStatusCode.BadRequest, "Part $index has ${bytes.size} bytes; expected $expected")
            }
            BackupUploadChunks.deleteWhere { (BackupUploadChunks.uploadId eq id) and (BackupUploadChunks.chunkIndex eq index) }
            BackupUploadChunks.insert {
                it[BackupUploadChunks.uploadId] = id
                it[chunkIndex] = index
                it[data] = ExposedBlob(bytes)
            }
            BackupUploads.update({ BackupUploads.id eq id }) { it[updatedAt] = Instant.now() }
        }
    }

    /** Starts the import once every part is in. Calling it again returns the running (or finished) import's status. */
    fun complete(owner: Owner, uploadId: String): UploadStatus {
        val id = parseId(uploadId)
        val claimed = writeTransactionWithRetry {
            val row = ownedRow(owner, id)
            if (row[BackupUploads.state] != STATE_UPLOADING) return@writeTransactionWithRetry false
            val received = receivedChunks(id)
            val total = row[BackupUploads.totalChunks]
            if (received != total) {
                throw ApiException(HttpStatusCode.Conflict, "Only $received of $total parts have arrived; send the rest first")
            }
            BackupUploads.update({ (BackupUploads.id eq id) and (BackupUploads.state eq STATE_UPLOADING) }) {
                it[state] = STATE_IMPORTING
                it[updatedAt] = Instant.now()
            } == 1
        }
        if (claimed) {
            thread(name = "backup-import-$id", isDaemon = true) { runImport(id) }
        }
        return status(owner, uploadId)
    }

    fun status(owner: Owner, uploadId: String): UploadStatus {
        val id = parseId(uploadId)
        val row = transaction { ownedRow(owner, id) }
        var state = row[BackupUploads.state]
        var error = row[BackupUploads.error]
        if (state == STATE_IMPORTING && row[BackupUploads.updatedAt].isBefore(Instant.now().minusMillis(DEAD_IMPORT_MS))) {
            // The node running it stopped (restart, crash, out of memory) without recording an outcome.
            error = "The server running this import stopped before it finished. Upload the file again; entries already imported are skipped as duplicates."
            state = STATE_FAILED
            runCatching { finish(id, STATE_FAILED, null, error, onlyIfStale = true) }
        }
        val received = if (state == STATE_UPLOADING) transaction { receivedChunks(id) } else row[BackupUploads.totalChunks]
        val report = row[BackupUploads.reportJson]?.let { runCatching { JsonSupport.json.decodeFromString(ImportReport.serializer(), it) }.getOrNull() }
        return UploadStatus(id.toString(), state, row[BackupUploads.fileName], received, row[BackupUploads.totalChunks], report, error)
    }

    /** Drops an upload and its parts. An import already running finishes; only its result is lost. */
    fun cancel(owner: Owner, uploadId: String) {
        val id = parseId(uploadId)
        writeTransactionWithRetry {
            ownedRow(owner, id)
            BackupUploadChunks.deleteWhere { BackupUploadChunks.uploadId eq id }
            BackupUploads.deleteWhere { BackupUploads.id eq id }
        }
    }

    // ---------------------------------------------------------------- import

    private fun runImport(id: UUID) {
        val running = AtomicBoolean(true)
        val heartbeat = thread(name = "backup-import-heartbeat-$id", isDaemon = true) {
            while (running.get()) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                runCatching {
                    writeTransactionWithRetry {
                        BackupUploads.update({ (BackupUploads.id eq id) and (BackupUploads.state eq STATE_IMPORTING) }) { it[updatedAt] = Instant.now() }
                    }
                }
            }
        }
        try {
            heavyJobSlot.acquire()
            try {
                val row = transaction { BackupUploads.selectAll().where { BackupUploads.id eq id }.firstOrNull() }
                    ?: return // cancelled before it started
                val owner = Owner(row[BackupUploads.userId], row[BackupUploads.teamNumber], row[BackupUploads.program], row[BackupUploads.isSuperAdmin])
                val bytes = AtomicReference<ByteArray?>(assemble(id, row[BackupUploads.totalBytes]))
                // The parts are no longer needed; free the space before the (long) import itself.
                runCatching { writeTransactionWithRetry { BackupUploadChunks.deleteWhere { uploadId eq id } } }
                val report = importFileLocked(row[BackupUploads.fileName], bytes, owner, row[BackupUploads.requestedScope])
                finish(id, STATE_DONE, JsonSupport.json.encodeToString(ImportReport.serializer(), report), null)
                log.info("[Backup] Imported ${row[BackupUploads.fileName]} (${row[BackupUploads.totalBytes]} bytes) for team ${owner.teamNumber}.")
            } finally {
                heavyJobSlot.release()
            }
        } catch (e: ApiException) {
            runCatching { finish(id, STATE_FAILED, null, e.message ?: "Import failed") }
        } catch (e: Throwable) {
            log.warn("[Backup] Import $id failed: ${e.message}", e)
            val message = when (e) {
                is OutOfMemoryError -> "The server ran out of memory importing this backup."
                is kotlinx.serialization.SerializationException -> "The file is not a valid ObsidianScout backup: ${e.message?.take(300)}"
                else -> "Import failed: ${e.message?.take(500) ?: e.javaClass.simpleName}"
            }
            runCatching { finish(id, STATE_FAILED, null, message) }
            if (e is OutOfMemoryError) throw e
        } finally {
            running.set(false)
            heartbeat.interrupt()
            runCatching { writeTransactionWithRetry { BackupUploadChunks.deleteWhere { uploadId eq id } } }
        }
    }

    /** The uploaded file, from its parts in order. */
    private fun assemble(id: UUID, totalBytes: Long): ByteArray {
        val out = ByteArray(totalBytes.toInt())
        var offset = 0
        val indices = transaction {
            BackupUploadChunks.select(BackupUploadChunks.chunkIndex).where { BackupUploadChunks.uploadId eq id }
                .orderBy(BackupUploadChunks.chunkIndex).map { it[BackupUploadChunks.chunkIndex] }
        }
        // One part per read keeps a single 4 MB value in flight instead of the whole file twice.
        for ((expected, index) in indices.withIndex()) {
            if (index != expected) throw IllegalStateException("Part $expected of the upload is missing")
            val bytes = transaction {
                BackupUploadChunks.select(BackupUploadChunks.data)
                    .where { (BackupUploadChunks.uploadId eq id) and (BackupUploadChunks.chunkIndex eq index) }
                    .first()[BackupUploadChunks.data].bytes
            }
            if (offset + bytes.size > out.size) throw IllegalStateException("The upload is larger than the $totalBytes bytes announced")
            System.arraycopy(bytes, 0, out, offset, bytes.size)
            offset += bytes.size
        }
        if (offset.toLong() != totalBytes) throw IllegalStateException("The upload has $offset bytes; expected $totalBytes")
        return out
    }

    /**
     * Imports a whole backup file (`.obsidiandb`/`.json` or a CSV `.zip`) for the single-request `/api/admin/import`,
     * waiting for this node's import slot first. [fileBytes] is cleared once decoded so the raw file can be collected
     * while the import runs. Must not be called inside a transaction.
     */
    fun importFile(fileName: String, fileBytes: AtomicReference<ByteArray?>, owner: Owner, requestedScope: String): ImportReport {
        heavyJobSlot.acquire()
        try {
            return importFileLocked(fileName, fileBytes, owner, requestedScope)
        } finally {
            heavyJobSlot.release()
        }
    }

    /** [importFile] for a caller that already holds [heavyJobSlot]. */
    private fun importFileLocked(fileName: String, fileBytes: AtomicReference<ByteArray?>, owner: Owner, requestedScope: String): ImportReport {
        val lower = fileName.lowercase()
        val report = if (lower.endsWith(".obsidiandb") || lower.endsWith(".json")) {
            val backup = decodeBackup(fileBytes)
            val importAsGlobal = owner.isSuperAdmin && requestedScope == "global" && backup.scope == "global"
            BackupService.importBackup(owner.teamNumber, backup, owner.userId, importAsGlobal, owner.program)
        } else if (lower.endsWith(".zip")) {
            val importAsGlobal = owner.isSuperAdmin && requestedScope == "global"
            val raw = fileBytes.getAndSet(null) ?: throw IllegalStateException("The uploaded file is no longer available")
            BackupService.importCsv(owner.teamNumber, raw, owner.userId, importAsGlobal, owner.program)
        } else {
            throw ApiException(HttpStatusCode.BadRequest, "Unsupported file format. Please upload .obsidiandb or .zip")
        }
        com.obsidianscout.scouting.EntrySync.markBulkChange()
        return report
    }

    /**
     * Decodes and releases [fileBytes]. A separate function so the raw file is unreachable once it returns, rather
     * than held in the caller's frame for the whole import.
     */
    private fun decodeBackup(fileBytes: AtomicReference<ByteArray?>): ObsidianDbBackup {
        val raw = fileBytes.getAndSet(null) ?: throw IllegalStateException("The uploaded file is no longer available")
        // Decoded from the bytes directly: a String copy of a large backup would double the memory used.
        return java.io.ByteArrayInputStream(raw).use { JsonSupport.json.decodeFromStream(ObsidianDbBackup.serializer(), it) }
    }

    // ---------------------------------------------------------------- housekeeping

    private fun finish(id: UUID, state: String, reportJson: String?, error: String?, onlyIfStale: Boolean = false) {
        writeTransactionWithRetry {
            val condition = if (onlyIfStale) {
                (BackupUploads.id eq id) and (BackupUploads.state eq STATE_IMPORTING) and (BackupUploads.updatedAt less Instant.now().minusMillis(DEAD_IMPORT_MS))
            } else {
                BackupUploads.id eq id
            }
            BackupUploads.update({ condition }) {
                it[BackupUploads.state] = state
                it[BackupUploads.reportJson] = reportJson
                it[BackupUploads.error] = error?.take(2000)
                it[updatedAt] = Instant.now()
            }
        }
    }

    /** Removes abandoned uploads, dead imports and finished results older than a day. Safe on every node. */
    fun purgeExpired() {
        try {
            val now = Instant.now()
            val expired = transaction {
                BackupUploads.select(BackupUploads.id).where {
                    ((BackupUploads.state eq STATE_UPLOADING) and (BackupUploads.updatedAt less now.minusMillis(ABANDONED_UPLOAD_MS))) or
                        ((BackupUploads.state eq STATE_IMPORTING) and (BackupUploads.updatedAt less now.minusMillis(ABANDONED_UPLOAD_MS))) or
                        ((BackupUploads.state inList listOf(STATE_DONE, STATE_FAILED)) and (BackupUploads.updatedAt less now.minusMillis(KEEP_FINISHED_MS)))
                }.map { it[BackupUploads.id].value }
            }
            if (expired.isEmpty()) return
            writeTransactionWithRetry {
                BackupUploadChunks.deleteWhere { uploadId inList expired }
                BackupUploads.deleteWhere { BackupUploads.id inList expired }
            }
            log.info("[Backup] Removed ${expired.size} expired backup upload(s).")
        } catch (e: Exception) {
            log.debug("[Backup] Expired upload cleanup skipped: ${e.message}")
        }
    }

    /**
     * Every 30 minutes, removes expired uploads and exports (see [purgeExpired] and [BackupExportService.purgeExpired]),
     * so abandoned parts don't sit in the database until someone next imports or exports.
     */
    fun startCleanup() {
        thread(name = "backup-transfer-cleanup", isDaemon = true) {
            while (true) {
                purgeExpired()
                BackupExportService.purgeExpired()
                try {
                    Thread.sleep(30 * 60_000L)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun parseId(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "Upload not found")

    /** The upload row, if [owner] started it; anyone else gets the same 404 as for a missing upload. */
    private fun ownedRow(owner: Owner, id: UUID): ResultRow =
        BackupUploads.selectAll().where { (BackupUploads.id eq id) and (BackupUploads.userId eq owner.userId) }.firstOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "Upload not found")

    private fun receivedChunks(id: UUID): Int =
        BackupUploadChunks.select(BackupUploadChunks.chunkIndex).where { BackupUploadChunks.uploadId eq id }.count().toInt()
}
