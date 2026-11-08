package com.obsidianscout.db

import com.obsidianscout.auth.ApiException
import com.obsidianscout.config.JsonSupport
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupUploadServiceTest {

    private val dbFile = File("build/test_backup_upload_${System.nanoTime()}.db")
    private val admin = BackupUploadService.Owner("user-a", 254, "FRC", isSuperAdmin = false)
    private val otherAdmin = BackupUploadService.Owner("user-b", 254, "FRC", isSuperAdmin = false)

    @BeforeTest
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction { SchemaUtils.create(*DatabaseFactory.allTables.toTypedArray()) }
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    private fun chunks(bytes: ByteArray, size: Int) = (0 until (bytes.size + size - 1) / size).map {
        bytes.copyOfRange(it * size, minOf(bytes.size, (it + 1) * size))
    }

    private fun awaitFinished(id: String, owner: BackupUploadService.Owner = admin): BackupUploadService.UploadStatus {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val s = BackupUploadService.status(owner, id)
            if (s.state != BackupUploadService.STATE_IMPORTING) return s
            Thread.sleep(100)
        }
        error("import $id did not finish")
    }

    private fun apiStatus(block: () -> Unit): HttpStatusCode = assertFailsWith<ApiException> { block() }.status

    @Test
    fun `a backup sent in parts, out of order and with a retried part, is imported in the background`() {
        // Big enough to need three parts: one entry whose notes pad the file past two chunks.
        val notes = "x".repeat(BackupUploadService.CHUNK_BYTES * 2 + 1000)
        val dataJson = """{"auto":3,"notes":"$notes"}"""
        val backup = ObsidianDbBackup(
            teamNumber = 254, type = "scouting",
            scoutingEntries = listOf(ScoutingEntryBackupDto(254, 1678, "2026test", "2026test_qm1", 1, dataJson, "alex", 1_700_000_000_000, false, false, ""))
        )
        val file = JsonSupport.json.encodeToString(ObsidianDbBackup.serializer(), backup).toByteArray()

        val started = BackupUploadService.start(admin, BackupUploadService.StartRequest("team_254_backup_scouting.obsidiandb", file.size.toLong()))
        assertEquals(3, started.totalChunks)
        val parts = chunks(file, started.chunkSize)

        BackupUploadService.putChunk(admin, started.uploadId, 2, parts[2])
        BackupUploadService.putChunk(admin, started.uploadId, 0, parts[0])
        // Not every part is in yet.
        assertEquals(HttpStatusCode.Conflict, apiStatus { BackupUploadService.complete(admin, started.uploadId) })
        assertEquals(2, BackupUploadService.status(admin, started.uploadId).receivedChunks)
        BackupUploadService.putChunk(admin, started.uploadId, 1, parts[1])
        BackupUploadService.putChunk(admin, started.uploadId, 1, parts[1]) // a retry replaces the part

        val running = BackupUploadService.complete(admin, started.uploadId)
        assertTrue(running.state in listOf(BackupUploadService.STATE_IMPORTING, BackupUploadService.STATE_DONE))
        // Asking again doesn't start a second import.
        BackupUploadService.complete(admin, started.uploadId)

        val done = awaitFinished(started.uploadId)
        assertEquals(BackupUploadService.STATE_DONE, done.state, done.error)
        assertEquals(1, assertNotNull(done.report).scoutingEntriesImported)
        val stored = transaction { ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq 254 }.single()[ScoutingEntries.dataJson] }
        assertEquals(dataJson, stored)
        // Parts are deleted once read.
        assertEquals(0L, transaction { BackupUploadChunks.selectAll().count() })
    }

    @Test
    fun `uploads are checked up front and parts must be the right size and belong to the caller`() {
        assertEquals(HttpStatusCode.BadRequest, apiStatus { BackupUploadService.start(admin, BackupUploadService.StartRequest("notes.txt", 10)) })
        assertEquals(HttpStatusCode.BadRequest, apiStatus { BackupUploadService.start(admin, BackupUploadService.StartRequest("b.zip", 0)) })
        assertEquals(HttpStatusCode.PayloadTooLarge, apiStatus {
            BackupUploadService.start(admin, BackupUploadService.StartRequest("b.zip", BackupService.MAX_IMPORT_BYTES + 1))
        })
        assertEquals(HttpStatusCode.Forbidden, apiStatus {
            BackupUploadService.start(admin, BackupUploadService.StartRequest("b.obsidiandb", 10, scope = "global"))
        })

        val up = BackupUploadService.start(admin, BackupUploadService.StartRequest("b.obsidiandb", 10))
        assertEquals(HttpStatusCode.BadRequest, apiStatus { BackupUploadService.putChunk(admin, up.uploadId, 0, ByteArray(9)) })
        assertEquals(HttpStatusCode.BadRequest, apiStatus { BackupUploadService.putChunk(admin, up.uploadId, 1, ByteArray(10)) })
        // Another admin, even on the same team, can't see or touch it.
        assertEquals(HttpStatusCode.NotFound, apiStatus { BackupUploadService.putChunk(otherAdmin, up.uploadId, 0, ByteArray(10)) })
        assertEquals(HttpStatusCode.NotFound, apiStatus { BackupUploadService.status(otherAdmin, up.uploadId) })
        assertEquals(HttpStatusCode.NotFound, apiStatus { BackupUploadService.status(admin, "not-a-uuid") })

        // At most two in progress per user.
        BackupUploadService.start(admin, BackupUploadService.StartRequest("c.obsidiandb", 10))
        assertEquals(HttpStatusCode.TooManyRequests, apiStatus { BackupUploadService.start(admin, BackupUploadService.StartRequest("d.obsidiandb", 10)) })
        BackupUploadService.cancel(admin, up.uploadId)
        BackupUploadService.start(admin, BackupUploadService.StartRequest("d.obsidiandb", 10))
    }

    @Test
    fun `a file that isn't a backup fails with a readable reason`() {
        val file = "this is not json".toByteArray()
        val up = BackupUploadService.start(admin, BackupUploadService.StartRequest("b.obsidiandb", file.size.toLong()))
        BackupUploadService.putChunk(admin, up.uploadId, 0, file)
        BackupUploadService.complete(admin, up.uploadId)
        val failed = awaitFinished(up.uploadId)
        assertEquals(BackupUploadService.STATE_FAILED, failed.state)
        assertTrue(failed.error!!.contains("not a valid ObsidianScout backup"), failed.error)
        // A finished upload takes no more parts.
        assertEquals(HttpStatusCode.Conflict, apiStatus { BackupUploadService.putChunk(admin, up.uploadId, 0, file) })
    }

    @Test
    fun `last part holds the remainder`() {
        assertEquals(4, BackupUploadService.expectedChunkBytes(10, 4, 0))
        assertEquals(2, BackupUploadService.expectedChunkBytes(10, 4, 2))
    }
}
