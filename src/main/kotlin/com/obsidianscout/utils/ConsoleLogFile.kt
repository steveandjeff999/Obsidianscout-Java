package com.obsidianscout.utils

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Append-only log made of numbered segment files (`console-000001.log`, ...) that never grows past [maxTotalBytes]:
 * a new segment starts once the newest reaches [segmentBytes], and the oldest are deleted until the set fits again.
 *
 * Every line is stored as `yyyy-MM-dd HH:mm:ss.SSS <mark> <text>`, where the mark says where the line came from
 * ([MARK_OUT], [MARK_ERR], or [MARK_META] for records this logger adds itself). scripts/log-viewer.ps1 reads this
 * format, so the two have to change together.
 *
 * Each write goes straight to the operating system, with no buffer in this process, so what was printed just before
 * a crash, kill or halt is still in the file. A write never throws: after an I/O error lines are dropped (and
 * counted in [dropped]) until the file can be opened again. Logging also gives way to a nearly full disk, first by
 * deleting old segments and then by pausing, so the log can't be what fills it.
 */
class RollingLogFile(
    private val dir: File,
    private val maxTotalBytes: Long,
    private val segmentBytes: Long,
    private val minFreeDiskBytes: Long = 1L shl 30,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freeDiskBytes: () -> Long = { dir.usableSpace },
) {
    /** How the run before this one left the log. [lastOutput] is the timestamp of its final line. */
    data class LastRun(val clean: Boolean, val lastOutput: String?)

    private val lock = Any()
    private var stream: FileOutputStream? = null
    private var segmentSeq = 0L
    private var segmentSize = 0L
    private var midLine = false
    private var lastMark: Byte = 0
    private var retryAt = 0L
    private var nextDiskCheckAt = 0L
    private var lowDisk = false
    private var scratch = ByteArray(SCRATCH_BYTES)
    private var scratchLen = 0
    private val prefix = ByteArray(PREFIX_LENGTH)
    private var prefixSecond = Long.MIN_VALUE

    /** Writes skipped because the file couldn't be written or the disk was nearly full. */
    @Volatile
    var dropped = 0L
        private set

    @Volatile
    private var dirty = false

    init {
        require(segmentBytes in 1..maxTotalBytes) { "A segment has to fit inside the total size" }
        val newest = segments(dir).lastOrNull()
        if (newest != null && newest.second.length() < segmentBytes) {
            open(newest.first)
            // The last run may have died halfway through a line; start ours on a fresh one.
            if (segmentSize > 0 && !endsWithNewline(newest.second)) {
                stream?.write(NEWLINE.toInt())
                segmentSize++
            }
        } else {
            open((newest?.first ?: 0L) + 1)
        }
        prune()
    }

    /** Appends console output from the stream [mark] names, stamping the start of every line. */
    fun write(mark: Byte, src: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            try {
                if (!ready()) {
                    dropped++
                    return
                }
                scratchLen = 0
                var i = off
                val end = off + len
                while (i < end) {
                    // A different stream cut into an unfinished line: close that line so the two don't run together.
                    if (midLine && mark != lastMark) {
                        put(NEWLINE)
                        midLine = false
                    }
                    if (!midLine) putPrefix(mark)
                    var lineEnd = i
                    while (lineEnd < end && src[lineEnd] != NEWLINE) lineEnd++
                    val complete = lineEnd < end
                    val next = if (complete) lineEnd + 1 else end
                    put(src, i, next - i)
                    midLine = !complete
                    lastMark = mark
                    i = next
                }
                val out = stream ?: return
                out.write(scratch, 0, scratchLen)
                segmentSize += scratchLen
                dirty = true
                if (scratch.size > SCRATCH_KEEP_BYTES) scratch = ByteArray(SCRATCH_BYTES)
            } catch (_: Exception) {
                runCatching { stream?.close() }
                stream = null
                midLine = false
                retryAt = clock() + RETRY_MS
                dropped++
            }
        }
    }

    /** Adds one record of the logger's own (start, heartbeat, shutdown...) on a line to itself. */
    fun note(text: String) {
        val bytes = (text.replace('\n', ' ').replace('\r', ' ') + "\n").toByteArray(StandardCharsets.UTF_8)
        write(MARK_META, bytes, 0, bytes.size)
    }

    /** Asks the operating system to put what was written on the disk itself, so a power cut or bluescreen loses little. */
    fun force() {
        if (!dirty) return
        dirty = false
        val out = synchronized(lock) { stream } ?: return
        runCatching { out.fd.sync() }
    }

    private fun ready(): Boolean {
        val now = clock()
        if (now >= nextDiskCheckAt) {
            nextDiskCheckAt = now + DISK_CHECK_MS
            // The folder can be removed while the file is open (not on Windows); reopen so the log comes back.
            if (!dir.isDirectory) {
                runCatching { stream?.close() }
                stream = null
            }
            if (diskBelow(minFreeDiskBytes)) prune()
            lowDisk = diskBelow(minFreeDiskBytes / 4)
        }
        if (lowDisk) return false
        if (stream == null) {
            if (now < retryAt) return false
            open(segmentSeq)
        }
        // Segments change between lines. One that never ends its line is cut at twice the size instead of growing forever.
        if (segmentSize >= segmentBytes && (!midLine || segmentSize >= 2 * segmentBytes)) {
            if (midLine) stream?.write(NEWLINE.toInt())
            midLine = false
            stream?.close()
            stream = null
            open(segmentSeq + 1)
            prune()
        }
        return true
    }

    private fun open(seq: Long) {
        dir.mkdirs()
        val file = File(dir, FILE_PREFIX + seq.toString().padStart(6, '0') + FILE_SUFFIX)
        // FileOutputStream rather than a FileChannel: a channel closes itself when the writing thread is interrupted,
        // and request threads are interrupted routinely.
        stream = FileOutputStream(file, true)
        segmentSeq = seq
        segmentSize = file.length()
    }

    /** Deletes the oldest finished segments while the set is over its size, or the disk is under its free-space floor. */
    private fun prune() {
        val finished = segments(dir).filter { it.first != segmentSeq }
        var total = finished.sumOf { it.second.length() }
        val budget = maxTotalBytes - maxOf(segmentBytes, segmentSize)
        for ((_, file) in finished) {
            if (total <= budget && !diskBelow(minFreeDiskBytes)) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** Zero free space means the figure couldn't be read (the folder is missing or the file system won't say), not a full disk. */
    private fun diskBelow(floor: Long): Boolean = freeDiskBytes() in 1 until floor

    private fun putPrefix(mark: Byte) {
        val now = clock()
        val second = Math.floorDiv(now, 1000L)
        if (second != prefixSecond) {
            val text = SECOND_FORMAT.format(Instant.ofEpochSecond(second))
            for (i in 0 until 19) prefix[i] = text[i].code.toByte()
            prefixSecond = second
        }
        val millis = Math.floorMod(now, 1000L).toInt()
        prefix[19] = '.'.code.toByte()
        prefix[20] = ('0'.code + millis / 100).toByte()
        prefix[21] = ('0'.code + millis / 10 % 10).toByte()
        prefix[22] = ('0'.code + millis % 10).toByte()
        prefix[23] = ' '.code.toByte()
        prefix[24] = mark
        prefix[25] = ' '.code.toByte()
        put(prefix, 0, PREFIX_LENGTH)
    }

    private fun put(b: Byte) {
        if (scratchLen == scratch.size) scratch = scratch.copyOf(scratch.size * 2)
        scratch[scratchLen++] = b
    }

    private fun put(src: ByteArray, off: Int, len: Int) {
        if (scratchLen + len > scratch.size) scratch = scratch.copyOf(maxOf(scratch.size * 2, scratchLen + len))
        System.arraycopy(src, off, scratch, scratchLen, len)
        scratchLen += len
    }

    companion object {
        val MARK_OUT = '|'.code.toByte()
        val MARK_ERR = '!'.code.toByte()
        val MARK_META = '#'.code.toByte()
        const val PREFIX_LENGTH = 26
        const val START_MARKER = "==== SERVER START ===="
        const val SHUTDOWN_MARKER = "==== SERVER SHUTDOWN ===="

        private const val FILE_PREFIX = "console-"
        private const val FILE_SUFFIX = ".log"
        private const val NEWLINE: Byte = 10
        private const val SCRATCH_BYTES = 8 * 1024
        private const val SCRATCH_KEEP_BYTES = 1024 * 1024
        private const val RETRY_MS = 10_000L
        private const val DISK_CHECK_MS = 10_000L
        private const val TAIL_BYTES = 256 * 1024L
        private val SECOND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        private val TIMESTAMP = Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}""")

        /** The segment files in [dir], oldest first, with their sequence numbers. */
        fun segments(dir: File): List<Pair<Long, File>> =
            (dir.listFiles() ?: emptyArray())
                .mapNotNull { file ->
                    val name = file.name
                    if (!file.isFile || !name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) return@mapNotNull null
                    name.substring(FILE_PREFIX.length, name.length - FILE_SUFFIX.length).toLongOrNull()?.let { it to file }
                }
                .sortedBy { it.first }

        /**
         * Reads the end of the log in [dir] to tell how the previous run finished: cleanly if its shutdown record
         * comes after its start record. Null when there is no earlier output.
         */
        fun lastRun(dir: File): LastRun? {
            val newest = segments(dir).lastOrNull { it.second.length() > 0 }?.second ?: return null
            val tail = runCatching {
                RandomAccessFile(newest, "r").use { raf ->
                    val count = minOf(raf.length(), TAIL_BYTES).toInt()
                    raf.seek(raf.length() - count)
                    ByteArray(count).also { raf.readFully(it) }
                }
            }.getOrNull() ?: return null
            // One char per byte keeps this simple; the records and timestamps looked for are plain ASCII.
            val text = String(tail, StandardCharsets.ISO_8859_1).trimEnd('\n', '\r')
            val started = text.lastIndexOf(" # $START_MARKER")
            val stopped = text.lastIndexOf(" # $SHUTDOWN_MARKER")
            val lastLine = text.substring(text.lastIndexOf('\n') + 1)
            val lastOutput = lastLine.take(23).takeIf { TIMESTAMP.matches(it) }
            return LastRun(clean = stopped > started, lastOutput = lastOutput)
        }

        private fun endsWithNewline(file: File): Boolean =
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(raf.length() - 1)
                    raf.read() == NEWLINE.toInt()
                }
            }.getOrDefault(true)
    }
}

/**
 * Records everything the server prints to its console in `logs/` (a [RollingLogFile] holding the newest 5 GB), so
 * there is something to read after a crash. Windows keeps no console history once the window closes or scrolls.
 *
 * Output reaches the file two ways. System.out and System.err are replaced with streams that copy to the file and
 * then to the real console (println, stack traces, the uncaught-exception handler). Log lines are written by
 * [AsyncConsoleLogging]'s file appender on the thread that logs them, because the console itself is fed through a
 * queue that a crash would lose and a stalled console window would stop.
 *
 * Besides the output it adds records of its own: a start banner, how the previous run ended, a heartbeat with
 * memory figures every minute, and a shutdown record. A run with no shutdown record died without running its
 * shutdown hooks (killed, crashed natively, halted, or the machine went down), and the heartbeats before it show
 * when and in what state.
 *
 * OBSIDIANSCOUT_LOG_MAX_MB changes the size kept; 0 turns the file off.
 */
object ConsoleLogFile {
    const val DIR_NAME = "logs"

    /** The launcher scripts leave the exit code of the run that just ended here for the next start to record. */
    const val EXIT_CODE_FILE = "last-exit-code.txt"

    private const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024 * 1024
    private const val MAX_SEGMENT_BYTES = 64L * 1024 * 1024
    private const val MIN_SEGMENT_BYTES = 64L * 1024
    private const val FLUSH_INTERVAL_MS = 5_000L
    private const val HEARTBEAT_EVERY_FLUSHES = 12
    private const val MB = 1024L * 1024

    @Volatile
    private var log: RollingLogFile? = null
    private val skipFile = ThreadLocal<Boolean>()
    private val startedNanos = System.nanoTime()

    val isActive: Boolean get() = log != null

    fun install(dir: File = File(DIR_NAME)) {
        if (log != null) return
        val maxBytes = System.getenv("OBSIDIANSCOUT_LOG_MAX_MB")?.trim()?.toLongOrNull()?.let { it * MB } ?: DEFAULT_MAX_BYTES
        if (maxBytes <= 0) return
        val lastRun = RollingLogFile.lastRun(dir)
        val exitCodeFile = File(dir, EXIT_CODE_FILE)
        val lastExitCode = runCatching { exitCodeFile.readText().trim().toIntOrNull() }.getOrNull()
        runCatching { exitCodeFile.delete() }
        val file = try {
            RollingLogFile(dir, maxBytes, (maxBytes / 8).coerceIn(MIN_SEGMENT_BYTES, MAX_SEGMENT_BYTES))
        } catch (e: Exception) {
            System.err.println("[ConsoleLog] Console output will not be saved: cannot write to ${dir.absolutePath} (${e.message})")
            return
        }
        log = file
        System.setOut(PrintStream(tee(System.out, RollingLogFile.MARK_OUT, file), true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(tee(System.err, RollingLogFile.MARK_ERR, file), true, StandardCharsets.UTF_8))

        file.note(startRecord())
        runCatching { ManagementFactory.getRuntimeMXBean().inputArguments }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { file.note("JVM ARGS " + it.joinToString(" ")) }
        if (lastRun != null) {
            file.note("PREVIOUS RUN clean=${lastRun.clean} exitCode=${lastExitCode ?: "unknown"} lastOutput=\"${lastRun.lastOutput ?: "unknown"}\"")
        }
        println(
            "[ConsoleLog] Saving console output to ${dir.absolutePath} (the newest ${maxBytes / MB} MB is kept)." +
                if (File("log-viewer.bat").exists()) " Open log-viewer.bat to read it." else ""
        )
        if (lastRun != null && !lastRun.clean) {
            println(
                "[ConsoleLog] The previous run did not shut down cleanly. Its last output was at ${lastRun.lastOutput ?: "an unknown time"}" +
                    (lastExitCode?.let { ", and the launcher saw exit code $it." } ?: ".")
            )
        }

        Thread({
            var flushes = 0
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching {
                    if (++flushes % HEARTBEAT_EVERY_FLUSHES == 0) file.note(heartbeatRecord(file))
                    file.force()
                }
            }
        }, "console-log-heartbeat").apply {
            isDaemon = true
            start()
        }
        Runtime.getRuntime().addShutdownHook(Thread({
            file.note("${RollingLogFile.SHUTDOWN_MARKER} uptime=${uptime()}")
            file.force()
        }, "console-log-shutdown"))
    }

    /** Adds a record of the server's own to the file only (nothing is printed). */
    fun note(text: String) {
        log?.note(text)
    }

    /** Writes an already formatted log line to the file; used by the logging appender. */
    fun writeLogLine(bytes: ByteArray) {
        log?.write(RollingLogFile.MARK_OUT, bytes, 0, bytes.size)
    }

    /** Runs [block] with the file copy of System.out and System.err switched off for this thread. */
    fun consoleOnly(block: () -> Unit) {
        skipFile.set(true)
        try {
            block()
        } finally {
            skipFile.set(false)
        }
    }

    private fun tee(console: PrintStream, mark: Byte, file: RollingLogFile) =
        ConsoleTee(console) { bytes, off, len -> if (skipFile.get() != true) file.write(mark, bytes, off, len) }

    private fun startRecord(): String {
        val runtime = if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            "native-image"
        } else {
            "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}"
        }
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.now())
        return "${RollingLogFile.START_MARKER} pid=${runCatching { ProcessHandle.current().pid() }.getOrDefault(-1)}" +
            " runtime=\"$runtime\"" +
            " os=\"${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}\"" +
            " cpus=${Runtime.getRuntime().availableProcessors()}" +
            " maxHeapMB=${Runtime.getRuntime().maxMemory() / MB}" +
            " utcOffset=$offset" +
            " dir=\"${System.getProperty("user.dir")}\""
    }

    private fun heartbeatRecord(file: RollingLogFile): String {
        val runtime = Runtime.getRuntime()
        val record = StringBuilder("HEARTBEAT uptime=").append(uptime())
            .append(" heapUsedMB=").append((runtime.totalMemory() - runtime.freeMemory()) / MB)
            .append(" heapCommittedMB=").append(runtime.totalMemory() / MB)
            .append(" heapMaxMB=").append(runtime.maxMemory() / MB)
        // The management beans are not all available in a native image; leave out what can't be read.
        runCatching { ManagementFactory.getThreadMXBean().threadCount }.getOrNull()?.let { record.append(" threads=").append(it) }
        runCatching {
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)
        }.getOrNull()?.let { os ->
            runCatching { os.freeMemorySize / MB }.getOrNull()?.let { record.append(" systemFreeMemMB=").append(it) }
            runCatching { os.processCpuLoad }.getOrNull()?.takeIf { it >= 0 }?.let { record.append(" processCpuPct=").append((it * 100).toInt()) }
        }
        if (file.dropped > 0) record.append(" logWritesDropped=").append(file.dropped)
        return record.toString()
    }

    private fun uptime(): String {
        val seconds = (System.nanoTime() - startedNanos) / 1_000_000_000L
        return "%dh%02dm%02ds".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

}

/**
 * Takes System.out's or System.err's place: hands each write to [copy] (the log file), then passes it on to [console].
 * The copy comes first because the console can block for as long as someone has text selected in its window.
 */
internal class ConsoleTee(
    private val console: PrintStream,
    private val copy: (ByteArray, Int, Int) -> Unit,
) : OutputStream() {
    // What arrives here is UTF-8 (the wrapping PrintStream's charset, and what the log encoder produces). A console
    // on another code page gets the text re-encoded, as it did when println went to it directly.
    private val consoleIsUtf8 = console.charset() == StandardCharsets.UTF_8
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)
    private val single = ByteArray(1)

    @Synchronized
    override fun write(b: Int) {
        single[0] = b.toByte()
        write(single, 0, 1)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        copy(b, off, len)
        if (consoleIsUtf8 || (carry.isEmpty() && isAscii(b, off, len))) {
            console.write(b, off, len)
            return
        }
        // A multi-byte character can be split across two writes: keep the unfinished bytes for the next one.
        val input = ByteBuffer.wrap(carry + b.copyOfRange(off, off + len))
        val chars = CharBuffer.allocate(input.remaining())
        decoder.reset()
        decoder.decode(input, chars, false)
        carry = ByteArray(input.remaining()).also { input.get(it) }
        chars.flip()
        console.print(chars.toString())
        console.flush()
    }

    override fun flush() = console.flush()

    private fun isAscii(b: ByteArray, off: Int, len: Int): Boolean {
        for (i in off until off + len) if (b[i] < 0) return false
        return true
    }
}
