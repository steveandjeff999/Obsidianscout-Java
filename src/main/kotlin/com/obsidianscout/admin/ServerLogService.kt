package com.obsidianscout.admin

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.serialization.Serializable

@Serializable
data class LogEntry(
    val timestamp: String,
    val level: String,
    val logger: String,
    val message: String
)

@Serializable
data class ServerLogsPayload(
    val nodeIp: String,
    val isLocal: Boolean,
    val totalEntries: Int,
    val logCapNotice: String = "Log storage is capped at 1,000 entries to prevent resource consumption.",
    val logs: List<LogEntry>
)

object ServerLogService {
    private const val MAX_LOG_ENTRIES = 1000
    private val buffer = ConcurrentLinkedQueue<LogEntry>()
    private val sizeCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    init {
        appendLog("INFO", "ServerLogService", "Log buffer initialized with capacity of $MAX_LOG_ENTRIES entries.")
    }

    fun appendLog(level: String, logger: String, message: String) {
        val entry = LogEntry(
            timestamp = dtf.format(Instant.now()),
            level = level.uppercase(),
            logger = logger,
            message = message
        )
        buffer.add(entry)
        if (sizeCount.incrementAndGet() > MAX_LOG_ENTRIES) {
            if (buffer.poll() != null) {
                sizeCount.decrementAndGet()
            }
        }
    }

    fun getLogs(limit: Int = 500, filter: String? = null): List<LogEntry> {
        val safeLimit = limit.coerceIn(1, MAX_LOG_ENTRIES)
        val allLogs = buffer.toList()
        val filtered = if (!filter.isNullOrBlank()) {
            val q = filter.trim()
            allLogs.filter { 
                it.message.contains(q, ignoreCase = true) || 
                it.logger.contains(q, ignoreCase = true) || 
                it.level.contains(q, ignoreCase = true) 
            }
        } else {
            allLogs
        }
        return filtered.takeLast(safeLimit)
    }

    fun getCockroachLogs(maxLines: Int = 500): List<String> {
        val safeMax = maxLines.coerceIn(1, MAX_LOG_ENTRIES)
        val cockroachLogFile = File(".cockroach/cockroach.log")
        if (!cockroachLogFile.exists()) {
            return listOf("[CockroachDB Log] File '.cockroach/cockroach.log' does not exist yet.")
        }

        return try {
            readLastLines(cockroachLogFile, safeMax)
        } catch (e: Exception) {
            listOf("[CockroachDB Log] Error reading log file: ${e.message}")
        }
    }

    /**
     * Efficiently reads only the tail end of a log file in 8KB chunks.
     * Prevents loading multi-megabyte log files into JVM heap memory.
     */
    private fun readLastLines(file: File, maxLines: Int): List<String> {
        val safeMax = maxLines.coerceIn(1, 1000)
        val fileLength = file.length()
        if (fileLength <= 0L) return emptyList()

        val lines = mutableListOf<String>()
        val chunkSize = 8192
        var pointer = fileLength

        RandomAccessFile(file, "r").use { raf ->
            val lineBuffer = java.io.ByteArrayOutputStream()
            while (pointer > 0 && lines.size < safeMax) {
                val readLen = Math.min(chunkSize.toLong(), pointer).toInt()
                pointer -= readLen
                raf.seek(pointer)
                val chunk = ByteArray(readLen)
                raf.readFully(chunk)

                for (i in chunk.size - 1 downTo 0) {
                    val b = chunk[i]
                    if (b == '\n'.code.toByte()) {
                        if (lineBuffer.size() > 0) {
                            val lineBytes = lineBuffer.toByteArray()
                            lineBytes.reverse()
                            lines.add(String(lineBytes, Charsets.UTF_8).trimEnd('\r'))
                            lineBuffer.reset()
                            if (lines.size >= safeMax) break
                        }
                    } else if (b != '\r'.code.toByte()) {
                        lineBuffer.write(b.toInt())
                    }
                }
            }
            if (lines.size < safeMax && lineBuffer.size() > 0) {
                val lineBytes = lineBuffer.toByteArray()
                lineBytes.reverse()
                lines.add(String(lineBytes, Charsets.UTF_8).trimEnd('\r'))
            }
        }
        return lines.asReversed()
    }
}
