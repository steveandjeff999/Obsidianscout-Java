package com.obsidianscout.db

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.AppConfigLoader
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object AutoBackupScheduler {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile
    var lastBackupInstant: Instant? = null
        internal set

    @Volatile
    var lastBackupStatus: String = "None"
        internal set

    @Volatile
    private var lastRunDate: LocalDate? = null

    fun computeNextRunUtc(targetTimeStr: String = "02:54"): String {
        return try {
            val parts = targetTimeStr.trim().split(":")
            val targetHour = parts.getOrNull(0)?.toIntOrNull() ?: 2
            val targetMin = parts.getOrNull(1)?.toIntOrNull() ?: 54
            val targetTime = LocalTime.of(targetHour, targetMin)

            val nowUtc = ZonedDateTime.now(ZoneOffset.UTC)
            val todayTarget = nowUtc.with(targetTime).withSecond(0).withNano(0)

            val nextRun = if (nowUtc.isBefore(todayTarget)) {
                todayTarget
            } else {
                todayTarget.plusDays(1)
            }
            nextRun.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (_: Exception) {
            "02:54 UTC"
        }
    }

    @Synchronized
    fun start(appConfig: AppConfig? = null) {
        if (job?.isActive == true) return

        println("[AutoBackupScheduler] Initializing automated database backup scheduler (Target: 02:54 UTC daily)...")

        job = scope.launch {
            // Initial brief delay on startup
            delay(10_000L)

            while (isActive) {
                try {
                    val config = AppConfigLoader.load().auto_backup
                    if (config.enabled && DatabaseFactory.isReady) {
                        val nowUtc = ZonedDateTime.now(ZoneOffset.UTC)
                        val parts = config.target_time_utc.trim().split(":")
                        val targetHour = parts.getOrNull(0)?.toIntOrNull() ?: 2
                        val targetMin = parts.getOrNull(1)?.toIntOrNull() ?: 54

                        val currentDate = nowUtc.toLocalDate()
                        if (nowUtc.hour == targetHour && nowUtc.minute == targetMin && lastRunDate != currentDate) {
                            lastRunDate = currentDate
                            println("[AutoBackupScheduler] ⏰ Scheduled trigger reached ($targetHour:${"%02d".format(targetMin)} UTC). Creating automated SQLite snapshot...")

                            val result = SnapshotService.createSnapshot(isAutoBackup = true)
                            lastBackupInstant = Instant.now()
                            lastBackupStatus = if (result.success) "Success (${result.fileName})" else "Failed: ${result.message}"

                            if (result.success) {
                                println("[AutoBackupScheduler] Scheduled auto backup completed: ${result.fileName} (${result.sizeBytes} bytes, ${result.recordsCopied} records).")
                            } else {
                                println("[AutoBackupScheduler] ⚠️ Scheduled auto backup failed: ${result.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("[AutoBackupScheduler] Background loop error: ${e.message}")
                }
                // Check every 25 seconds
                delay(25_000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        println("[AutoBackupScheduler] Automated database backup scheduler stopped.")
    }
}
