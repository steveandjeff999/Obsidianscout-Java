package com.obsidianscout.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleLogFileTest {
    private val dir: File = Files.createTempDirectory("console-log-test").toFile()
    private var now = 1_700_000_000_123L

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun open(maxTotal: Long = 1L shl 20, segment: Long = 1L shl 16, freeDisk: () -> Long = { Long.MAX_VALUE }) =
        RollingLogFile(dir, maxTotal, segment, minFreeDiskBytes = 1000, clock = { now }, freeDiskBytes = freeDisk)

    private fun RollingLogFile.out(text: String) = text.toByteArray().let { write(RollingLogFile.MARK_OUT, it, 0, it.size) }
    private fun RollingLogFile.err(text: String) = text.toByteArray().let { write(RollingLogFile.MARK_ERR, it, 0, it.size) }

    private fun files() = RollingLogFile.segments(dir).map { it.second }
    private fun lines() = files().flatMap { it.readLines() }

    /** The text of each stored line with its timestamp removed, mark included. */
    private fun marked() = lines().map { it.substring(24) }

    @Test
    fun `every line is stored with a timestamp and the stream it came from`() {
        val log = open()
        log.out("first\nsecond\n")
        log.err("bad\n")
        log.note("HEARTBEAT ok")

        val lines = lines()
        assertEquals(listOf("| first", "| second", "! bad", "# HEARTBEAT ok"), marked())
        assertTrue(lines.all { Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.123 . .*""").matches(it) }, lines.toString())
    }

    @Test
    fun `a line printed in pieces is one line`() {
        val log = open()
        log.out("load")
        log.out("ing... ")
        log.out("done\n")
        assertEquals(listOf("| loading... done"), marked())
    }

    @Test
    fun `another stream cutting into an unfinished line starts its own`() {
        val log = open()
        log.out("working")
        log.err("failed\n")
        log.out(" still\n")
        assertEquals(listOf("| working", "! failed", "|  still"), marked())
    }

    @Test
    fun `the oldest segments are deleted to stay under the total size`() {
        val log = open(maxTotal = 10_000, segment = 1_000)
        repeat(2_000) { log.out("line number $it\n") }

        val files = files()
        assertTrue(files.sumOf { it.length() } <= 10_000, "kept ${files.sumOf { it.length() }} bytes")
        assertTrue(files.size > 5, "most of the allowance should be in use, got ${files.size} segments")
        assertTrue(files.first().name > "console-000001.log", "the first segments should be gone")
        val kept = marked()
        assertEquals("| line number 1999", kept.last())
        // What is left is one unbroken run of the newest lines.
        val firstKept = kept.first().removePrefix("| line number ").toInt()
        assertEquals((firstKept..1999).map { "| line number $it" }, kept)
    }

    @Test
    fun `a restart carries on in the newest segment`() {
        open().out("before\n")
        open().out("after\n")
        assertEquals(1, files().size)
        assertEquals(listOf("| before", "| after"), marked())
    }

    @Test
    fun `a restart after dying mid-line starts on a new line`() {
        open().out("half a li")
        open().out("fresh\n")
        assertEquals(listOf("| half a li", "| fresh"), marked())
    }

    @Test
    fun `the previous run counts as clean only when its shutdown record follows its start`() {
        assertNull(RollingLogFile.lastRun(dir))

        val first = open()
        first.note(RollingLogFile.START_MARKER + " pid=1")
        first.out("serving\n")
        assertEquals(RollingLogFile.LastRun(clean = false, lastOutput = lines().last().take(23)), RollingLogFile.lastRun(dir))

        first.note(RollingLogFile.SHUTDOWN_MARKER)
        assertTrue(RollingLogFile.lastRun(dir)!!.clean)

        // A run that starts and is then killed leaves the earlier shutdown record behind it.
        open().note(RollingLogFile.START_MARKER + " pid=2")
        assertFalse(RollingLogFile.lastRun(dir)!!.clean)
    }

    @Test
    fun `a nearly full disk deletes old segments and then pauses the log`() {
        var free = Long.MAX_VALUE
        val log = open(maxTotal = 100_000, segment = 1_000, freeDisk = { free })
        repeat(200) { log.out("line number $it\n") }
        assertTrue(files().size > 3)

        // Under the floor (1000 bytes): old segments go, the current one keeps recording.
        free = 900
        now += 60_000
        log.out("tight\n")
        assertEquals(1, files().size)
        assertEquals("| tight", marked().last())

        // Under a quarter of the floor: nothing more is written until space comes back.
        free = 100
        now += 60_000
        log.out("lost\n")
        assertEquals("| tight", marked().last())
        assertEquals(1, log.dropped)

        free = Long.MAX_VALUE
        now += 60_000
        log.out("back\n")
        assertEquals("| back", marked().last())
    }

    @Test
    fun `a console on another code page gets re-encoded text and the copy gets the UTF-8`() {
        val consoleBytes = ByteArrayOutputStream()
        val copy = ByteArrayOutputStream()
        val cp1252 = Charset.forName("windows-1252")
        val tee = ConsoleTee(PrintStream(consoleBytes, true, cp1252)) { bytes, off, len -> copy.write(bytes, off, len) }

        val utf8 = "caf\u00e9 ok\n".toByteArray()
        // Split inside the two-byte e-acute.
        tee.write(utf8, 0, 4)
        tee.write(utf8, 4, utf8.size - 4)

        assertEquals("caf\u00e9 ok\n", consoleBytes.toString(cp1252))
        assertEquals("caf\u00e9 ok\n", copy.toString(Charsets.UTF_8))
    }
}
