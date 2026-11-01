package com.obsidianscout.db

import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.routes.EventInfoFileDto
import com.obsidianscout.routes.EventInfoPostRequest
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventInfoServiceTest {

    private val testDbFile = File("build/test_event_info_${System.nanoTime()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        DatabaseFactory.init(
            DatabaseConfig(type = "sqlite", sqlite = SqliteConfig(file = testDbFile.path)),
            runMigration = true,
            isCockroach = false
        )
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
        testDbFile.delete()
    }

    private fun newPost(): String =
        EventInfoService.create(1234, "FRC", "2026test", "admin", EventInfoPostRequest(title = "Files", allEvents = true)).id

    private fun roundTrip(name: String, upload: ByteArray): Pair<EventInfoFileDto, ByteArray> {
        val added = assertNotNull(EventInfoService.addFile(newPost(), 1234, "FRC", name, upload))
        return added to assertNotNull(EventInfoService.getFile(added.id)).bytes
    }

    private val repetitive = "ObsidianScout event info ".repeat(500).toByteArray(StandardCharsets.UTF_8)

    @Test
    fun testPlainFileIsCompressedAtRestAndServedIntact() {
        val (added, served) = roundTrip("notes.txt", repetitive)
        assertContentEquals(repetitive, served)
        assertEquals(repetitive.size.toLong(), added.sizeBytes)
    }

    @Test
    fun testClientGzippedFileIsServedUncompressed() {
        val (added, served) = roundTrip("notes.txt", EventInfoService.compressGzip(repetitive))
        assertContentEquals(repetitive, served)
        assertEquals(repetitive.size.toLong(), added.sizeBytes)
    }

    @Test
    fun testExplicitGzipFileKeepsItsExactBytes() {
        // Compressible, so wrapping it at rest would shrink it and then serve the wrapped copy.
        val (_, served) = roundTrip("logs.gz", repetitive)
        assertContentEquals(repetitive, served)

        val realGzip = EventInfoService.compressGzip(repetitive)
        assertContentEquals(realGzip, roundTrip("archive.tar.gz", realGzip).second)
    }

    @Test
    fun testGzipBombIsRefused() {
        val bomb = EventInfoService.compressGzip(ByteArray((EventInfoService.MAX_UNCOMPRESSED_BYTES + 1).toInt()))
        assertTrue(bomb.size < EventInfoService.MAX_FILE_BYTES, "Fits the upload limit, so only the cap stops it")
        assertFailsWith<IllegalStateException> { EventInfoService.addFile(newPost(), 1234, "FRC", "big.txt", bomb) }
    }

    @Test
    fun testMaxFileBytesIs15Mb() {
        assertEquals(15L * 1024 * 1024, EventInfoService.MAX_FILE_BYTES)
    }

    @Test
    fun testGzipDetectionAndRoundTrip() {
        val originalText = "ObsidianScout event announcement content that repeats for compression. ".repeat(100)
        val originalBytes = originalText.toByteArray(StandardCharsets.UTF_8)

        assertFalse(EventInfoService.isGzip(originalBytes))

        val compressed = EventInfoService.compressGzip(originalBytes)
        assertTrue(EventInfoService.isGzip(compressed))
        assertTrue(compressed.size < originalBytes.size, "Compressed should be smaller than original")

        val decompressed = EventInfoService.decompressGzip(compressed)
        assertEquals(originalText, String(decompressed, StandardCharsets.UTF_8))
    }

    @Test
    fun testExplicitGzipFileDetection() {
        assertTrue(EventInfoService.isExplicitGzipFile("archive.tar.gz"))
        assertTrue(EventInfoService.isExplicitGzipFile("data.csv.gz"))
        assertTrue(EventInfoService.isExplicitGzipFile("backup.tgz"))
        assertTrue(EventInfoService.isExplicitGzipFile("BACKUP.GZ"))
        assertTrue(EventInfoService.isExplicitGzipFile("export.gzip"))
        assertTrue(EventInfoService.isExplicitGzipFile("logo.svgz"))
        assertFalse(EventInfoService.isExplicitGzipFile("report.pdf"))
        assertFalse(EventInfoService.isExplicitGzipFile("schedule.png"))
        assertFalse(EventInfoService.isExplicitGzipFile("notes.txt"))
    }

    @Test
    fun testDecompressGzipIgnoresNonGzip() {
        val plain = "Not a gzip stream".toByteArray(StandardCharsets.UTF_8)
        val result = EventInfoService.decompressGzip(plain)
        assertEquals(plain.toList(), result.toList())
    }
}
