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
        while (buffer.size > MAX_LOG_ENTRIES) {
            buffer.poll()
        }
    }

    fun getLogs(limit: Int = 500, filter: String? = null): List<LogEntry> {
        val safeLimit = limit.coerceIn(1, MAX_LOG_ENTRIES)
        val allLogs = buffer.toList()
        val filtered = if (!filter.isNullOrBlank()) {
            val q = filter.lowercase()
            allLogs.filter { 
                it.message.lowercase().contains(q) || 
                it.logger.lowercase().contains(q) || 
                it.level.lowercase().contains(q) 
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

    private fun readLastLines(file: File, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        RandomAccessFile(file, "r").use { raf ->
            var fileLength = raf.length() - 1
            if (fileLength < 0) return emptyList()

            val sb = StringBuilder()
            var lineCount = 0

            for (pointer in fileLength downTo 0) {
                raf.seek(pointer)
                val c = raf.readByte().toInt().toChar()
                if (c == '\n') {
                    if (pointer < fileLength) {
                        lines.add(0, sb.reverse().toString())
                        sb.setLength(0)
                        lineCount++
                        if (lineCount >= maxLines) break
                    }
                } else if (c != '\r') {
                    sb.append(c)
                }
            }

            if (lineCount < maxLines && sb.isNotEmpty()) {
                lines.add(0, sb.reverse().toString())
            }
        }
        return lines
    }
}
