package com.obsidianscout.utils

import com.obsidianscout.config.JsonSupport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@Serializable
data class PendingCrashReportDto(
    val errorType: String, // "UPDATE_STARTUP_FAILURE", "SERVER_CRASH", "OOM_CRASH"
    val errorMessage: String,
    val errorStack: String? = null,
    val failedVersion: String? = null,
    val restoredVersion: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val wasRolledBack: Boolean = false
)

/**
 * UpdateRecoveryManager
 *
 * Manages backup creation before updates, pending update markers during update staging/boot,
 * boot health verification, crash logging, and automatic rollbacks if a faulty update is applied.
 */
object UpdateRecoveryManager {
    private val log = LoggerFactory.getLogger("UpdateRecoveryManager")

    val backupDir = File(".backup")
    val pendingFile = File(".update_pending")
    val resultFile = File(".update_result")
    val crashReportFile = File(".pending_crash_report.json")

    /**
     * Tracks whether the server has completely finished its boot cycle and is now serving traffic.
     * Used to distinguish startup failures (which trigger rollbacks) from runtime crashes (which do not).
     */
    val isBootCompleted = AtomicBoolean(false)

    /**
     * Creates a backup of the current working server JAR, config, and scripts into `.backup/`.
     */
    fun createBackup(currentVersion: String = "unknown"): Boolean {
        return try {
            backupDir.mkdirs()

            val currentJar = File("obsidianscout-server.jar")
            if (currentJar.exists() && currentJar.length() > 0) {
                // Verify current JAR is valid before backing it up so we don't back up a corrupted state
                val valResult = UpdateValidator.validateJarStructure(currentJar)
                if (valResult is UpdateValidator.ValidationResult.Success) {
                    currentJar.copyTo(File(backupDir, "obsidianscout-server.jar"), overwrite = true)
                } else {
                    log.warn("[UpdateRecovery] Current JAR failed structure check, backing up anyway as fallback.")
                    currentJar.copyTo(File(backupDir, "obsidianscout-server.jar"), overwrite = true)
                }
            }

            // Backup native executables
            val nativeFiles = File(".").listFiles { _, name -> name.startsWith("obsidianscout-server-native", ignoreCase = true) } ?: emptyArray()
            for (nf in nativeFiles) {
                if (nf.isFile) nf.copyTo(File(backupDir, nf.name), overwrite = true)
            }

            // Backup dynamic libraries (.dll, .so, .dylib)
            val nativeLibs = File(".").listFiles { _, name -> name.endsWith(".dll", ignoreCase = true) || name.endsWith(".so", ignoreCase = true) || name.endsWith(".dylib", ignoreCase = true) } ?: emptyArray()
            for (nl in nativeLibs) {
                if (nl.isFile) nl.copyTo(File(backupDir, nl.name), overwrite = true)
            }

            // Backup configuration files
            val currentConfig = File("config/app-config.json")
            if (currentConfig.exists()) {
                val backupConfigDir = File(backupDir, "config")
                backupConfigDir.mkdirs()
                currentConfig.copyTo(File(backupConfigDir, "app-config.json"), overwrite = true)
            }

            // Backup launcher scripts if present
            val scripts = listOf("run.sh", "run.bat", "run.ps1", "update.sh", "update.bat", "reset-superadmin.sh", "reset-superadmin.bat")
            for (script in scripts) {
                val f = File(script)
                if (f.exists()) {
                    f.copyTo(File(backupDir, script), overwrite = true)
                }
            }

            SafeFileUtils.atomicWriteString(File(backupDir, "version.txt"), currentVersion, createBackup = false)
            log.info("[UpdateRecovery] Created backup of working installation (version: $currentVersion) in .backup/")
            true
        } catch (e: Exception) {
            log.error("[UpdateRecovery] Failed to create backup: ${e.message}")
            false
        }
    }

    /**
     * Marks an update as pending for target [version].
     */
    fun markUpdatePending(version: String) {
        try {
            SafeFileUtils.atomicWriteString(pendingFile, "version=$version\ntimestamp=${System.currentTimeMillis()}\nattempt=0\n", createBackup = false)
            log.info("[UpdateRecovery] Marked update to $version as pending.")
        } catch (e: Exception) {
            log.error("[UpdateRecovery] Failed to write .update_pending: ${e.message}")
        }
    }

    /**
     * Returns true if an update is currently marked pending.
     */
    fun isUpdatePending(): Boolean {
        return pendingFile.exists()
    }

    /**
     * Reads the target version from `.update_pending`.
     */
    fun getPendingVersion(): String {
        if (!pendingFile.exists()) return "unknown"
        return try {
            pendingFile.readLines()
                .firstOrNull { it.startsWith("version=") }
                ?.substringAfter("version=")
                ?.trim() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Called when the server completes boot and health initialization successfully.
     * Clears the pending update marker and flags boot as completed.
     */
    fun markBootSuccessful() {
        isBootCompleted.set(true)
        if (pendingFile.exists()) {
            val version = getPendingVersion()
            try {
                pendingFile.delete()
                log.info("[UpdateRecovery] Boot verified successful for version $version. Cleared .update_pending.")
            } catch (e: Exception) {
                log.warn("[UpdateRecovery] Failed to delete .update_pending: ${e.message}")
            }
        }
    }

    /**
     * Atomically writes a crash report to `.pending_crash_report.json` to be ingested on next server boot.
     */
    fun recordCrashReport(report: PendingCrashReportDto) {
        try {
            val jsonText = JsonSupport.json.encodeToString(report)
            SafeFileUtils.atomicWriteString(crashReportFile, jsonText, createBackup = false)
            log.info("[UpdateRecovery] Recorded pending crash report (type: ${report.errorType}) to ${crashReportFile.name}")
        } catch (e: Exception) {
            log.error("[UpdateRecovery] Failed to record pending crash report: ${e.message}")
        }
    }

    /**
     * Restores the previous working installation from `.backup/` and blacklists [failedVersion].
     * Also records a crash report for the failed update so it will be reported to the admin error dashboard.
     */
    fun performRollback(
        failedVersion: String = "unknown",
        reason: String? = null,
        crashLog: String? = null
    ): Boolean {
        val targetFailedVersion = if (failedVersion != "unknown") failedVersion else getPendingVersion()

        log.warn("[UpdateRecovery] ════════════════════════════════════════════════════════════════")
        log.warn("[UpdateRecovery] FAULTY INSTALLATION DETECTED! Rolling back to backup version...")
        log.warn("[UpdateRecovery] ════════════════════════════════════════════════════════════════")

        if (targetFailedVersion.isNotBlank() && targetFailedVersion != "unknown") {
            UpdateValidator.blacklistVersion(targetFailedVersion, reason ?: "Faulty installation startup crash")
        }

        if (!backupDir.exists()) {
            log.error("[UpdateRecovery] Rollback failed: Backup directory .backup/ missing!")
            if (pendingFile.exists()) pendingFile.delete()
            if (resultFile.exists()) resultFile.delete()
            recordCrashReport(
                PendingCrashReportDto(
                    errorType = "UPDATE_STARTUP_FAILURE",
                    errorMessage = "Update to $targetFailedVersion failed on startup, but .backup directory was missing for rollback: ${reason ?: "No details"}",
                    errorStack = crashLog,
                    failedVersion = targetFailedVersion,
                    restoredVersion = null,
                    wasRolledBack = false
                )
            )
            return false
        }

        return try {
            val backupVersion = if (File(backupDir, "version.txt").exists()) File(backupDir, "version.txt").readText().trim() else "previous"

            val backupJar = File(backupDir, "obsidianscout-server.jar")
            if (backupJar.exists()) {
                backupJar.copyTo(File("obsidianscout-server.jar"), overwrite = true)
            }

            val backupNativeFiles = backupDir.listFiles { _, name -> name.startsWith("obsidianscout-server-native", ignoreCase = true) } ?: emptyArray()
            for (bf in backupNativeFiles) {
                if (bf.isFile) bf.copyTo(File(bf.name), overwrite = true)
            }

            val backupLibs = backupDir.listFiles { _, name -> name.endsWith(".dll", ignoreCase = true) || name.endsWith(".so", ignoreCase = true) || name.endsWith(".dylib", ignoreCase = true) } ?: emptyArray()
            for (bl in backupLibs) {
                if (bl.isFile) bl.copyTo(File(bl.name), overwrite = true)
            }

            val backupConfig = File(backupDir, "config/app-config.json")
            if (backupConfig.exists()) {
                File("config").mkdirs()
                backupConfig.copyTo(File("config/app-config.json"), overwrite = true)
            }

            val scripts = listOf("run.sh", "run.bat", "run.ps1", "update.sh", "update.bat", "reset-superadmin.sh", "reset-superadmin.bat")
            for (script in scripts) {
                val bScript = File(backupDir, script)
                if (bScript.exists()) {
                    bScript.copyTo(File(script), overwrite = true)
                }
            }

            if (pendingFile.exists()) pendingFile.delete()
            if (resultFile.exists()) resultFile.delete()

            // Record crash log to be reported to database error page upon restored server startup
            recordCrashReport(
                PendingCrashReportDto(
                    errorType = "UPDATE_STARTUP_FAILURE",
                    errorMessage = "Update to $targetFailedVersion failed to boot. Server was automatically rolled back to $backupVersion. ${reason ?: ""}".trim(),
                    errorStack = crashLog,
                    failedVersion = targetFailedVersion,
                    restoredVersion = backupVersion,
                    wasRolledBack = true
                )
            )

            log.info("[UpdateRecovery] ROLLBACK SUCCESSFUL! Restored installation (version: $backupVersion).")
            true
        } catch (e: Exception) {
            log.error("[UpdateRecovery] Rollback exception: ${e.message}")
            if (pendingFile.exists()) pendingFile.delete()
            if (resultFile.exists()) resultFile.delete()
            false
        }
    }

    /**
     * CLI helper entry point for invocation from shell / PowerShell scripts or Native Image main entrypoint.
     */
    fun handleCli(args: Array<String>) {
        val mode = args.firstOrNull()?.lowercase()
        when (mode) {
            "--rollback", "-r" -> {
                val version = args.getOrNull(1) ?: "unknown"
                val reason = args.getOrNull(2)
                performRollback(version, reason)
            }
            "--check-and-rollback" -> {
                val exitCode = args.getOrNull(1)?.toIntOrNull() ?: 1
                val logFilePath = args.getOrNull(2)
                val logFile = if (!logFilePath.isNullOrBlank()) File(logFilePath) else null
                val crashLog = if (logFile != null && logFile.exists()) logFile.readText() else null

                if (isUpdatePending()) {
                    val version = getPendingVersion()
                    println("[UpdateRecovery] Update to $version failed on boot (exit code $exitCode). Executing automatic rollback...")
                    performRollback(
                        failedVersion = version,
                        reason = "Process crashed during post-update boot with exit code $exitCode",
                        crashLog = crashLog
                    )
                    exitProcess(10) // 10 indicates rollback was performed
                } else {
                    if (crashLog != null && crashLog.isNotBlank()) {
                        recordCrashReport(
                            PendingCrashReportDto(
                                errorType = "SERVER_CRASH",
                                errorMessage = "Server terminated unexpectedly with exit code $exitCode",
                                errorStack = crashLog
                            )
                        )
                    }
                    exitProcess(0)
                }
            }
            "--mark-pending" -> {
                val version = args.getOrNull(1) ?: "unknown"
                markUpdatePending(version)
            }
            "--create-backup" -> {
                val version = args.getOrNull(1) ?: "unknown"
                createBackup(version)
            }
            "--boot-success" -> {
                markBootSuccessful()
            }
            else -> {
                println("Usage: java -cp obsidianscout-server.jar com.obsidianscout.utils.UpdateRecoveryManagerKt [--rollback <ver> [reason]|--check-and-rollback <exitCode> [logFile]|--mark-pending <ver>|--create-backup <ver>|--boot-success]")
            }
        }
    }
}

/**
 * CLI helper entry point for invocation from shell / PowerShell scripts.
 */
fun main(args: Array<String>) {
    UpdateRecoveryManager.handleCli(args)
}
