package com.obsidianscout.db

import com.obsidianscout.auth.ApiException
import com.obsidianscout.config.JsonSupport
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupExportServiceTest {

    private val dbFile = File("build/test_backup_export_${System.nanoTime()}.db")
    private val admin = BackupUploadService.Owner("user-a", 254, "FRC", isSuperAdmin = false)
    private val otherAdmin = BackupUploadService.Owner("user-b", 254, "FRC", isSuperAdmin = false)
    private val notes = "y".repeat(BackupExportService.CHUNK_BYTES + 5000)
    private val dataJson = """{"auto":4,"notes":"$notes"}"""

    @BeforeTest
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(*DatabaseFactory.allTables.toTypedArray())
            val userId = UUID.randomUUID()
            Users.insert {
                it[Users.id] = userId
                it[username] = "alex"
                it[teamNumber] = 254
                it[passwordHash] = "x"
                it[role] = "ADMIN"
                it[createdAt] = Instant.now()
            }
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 1678
                it[eventKey] = "2026test"
                it[matchKey] = "2026test_qm1"
                it[matchNumber] = 1
                it[ScoutingEntries.dataJson] = this@BackupExportServiceTest.dataJson
                it[submittedByUserId] = EntityID(userId, Users)
                it[createdAt] = Instant.ofEpochMilli(1_700_000_000_000)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    private fun awaitBuilt(id: String, owner: BackupUploadService.Owner = admin): BackupExportService.ExportStatus {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val s = BackupExportService.status(owner, id)
            if (s.state != BackupExportService.STATE_BUILDING) return s
            Thread.sleep(100)
        }
        error("export $id did not finish")
    }

    private fun apiStatus(block: () -> Unit): HttpStatusCode = assertFailsWith<ApiException> { block() }.status

    @Test
    fun `an export is built in parts that join back into the full backup`() {
        val started = BackupExportService.start(admin, BackupExportService.StartRequest("scouting", "obsidiandb", "team"))
        assertEquals("team_254_backup_scouting.obsidiandb", started.fileName)
        val done = awaitBuilt(started.exportId)
        assertEquals(BackupExportService.STATE_DONE, done.state, done.error)
        assertTrue(done.totalChunks >= 2, "a >4 MB backup spans several parts")

        val joined = ByteArrayOutputStream()
        repeat(done.totalChunks) { joined.write(BackupExportService.chunk(admin, started.exportId, it)) }
        assertEquals(done.totalBytes, joined.size().toLong())
        val backup = JsonSupport.json.decodeFromString(ObsidianDbBackup.serializer(), joined.toString(Charsets.UTF_8))
        assertEquals(dataJson, backup.scoutingEntries.single().dataJson)

        // Only the person who asked can read it.
        assertEquals(HttpStatusCode.NotFound, apiStatus { BackupExportService.chunk(otherAdmin, started.exportId, 0) })
        assertEquals(HttpStatusCode.NotFound, apiStatus { BackupExportService.chunk(admin, started.exportId, done.totalChunks) })

        BackupExportService.delete(admin, started.exportId)
        assertEquals(0L, transaction { BackupExportChunks.selectAll().count() })
        assertEquals(HttpStatusCode.NotFound, apiStatus { BackupExportService.status(admin, started.exportId) })
    }

    @Test
    fun `a CSV export is a zip`() {
        val started = BackupExportService.start(admin, BackupExportService.StartRequest("scouting", "csv", "team"))
        val done = awaitBuilt(started.exportId)
        assertEquals(BackupExportService.STATE_DONE, done.state, done.error)
        assertEquals("application/zip", done.contentType)
        val first = BackupExportService.chunk(admin, started.exportId, 0)
        assertEquals('P'.code.toByte(), first[0])
        assertEquals('K'.code.toByte(), first[1])
    }

    @Test
    fun `requests are checked up front`() {
        assertEquals(HttpStatusCode.BadRequest, apiStatus { BackupExportService.start(admin, BackupExportService.StartRequest("everything", "obsidiandb")) })
        assertEquals(HttpStatusCode.BadRequest, apiStatus { BackupExportService.start(admin, BackupExportService.StartRequest("scouting", "pdf")) })
        assertEquals(HttpStatusCode.Forbidden, apiStatus { BackupExportService.start(admin, BackupExportService.StartRequest("scouting", "obsidiandb", "global")) })
        assertEquals(HttpStatusCode.NotFound, apiStatus { BackupExportService.status(admin, "nope") })
    }
}
