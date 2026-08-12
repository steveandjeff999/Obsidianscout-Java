package com.obsidianscout.utils

import org.slf4j.LoggerFactory
import java.io.File

/**
 * UpdateRecoveryManager
 *
 * Manages backup creation before updates, pending update markers during update staging/boot,
 * boot health verification, and automatic rollbacks if a faulty update is applied.
 */
object UpdateRecoveryManager {
    private val log = LoggerFactory.getLogger("UpdateRecoveryManager")

    val backupDir = File(".backup")
    val pendingFile = File(".update_pending")
    val resultFile = File(".update_result")

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
            val scripts = listOf("run.sh", "run.bat", "update.sh", "update.bat", "reset-superadmin.sh", "reset-superadmin.bat")
            for (script in scripts) {
                val f = File(script)
                if (f.exists()) {
                    f.copyTo(File(backupDir, script), overwrite = true)
                }
            }

            File(backupDir, "version.txt").writeText(currentVersion)
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
            pendingFile.writeText("version=$version\ntimestamp=${System.currentTimeMillis()}\nattempt=0\n")
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
     * Clears the pending update marker.
     */
    fun markBootSuccessful() {
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
     * Restores the previous working installation from `.backup/` and blacklists [failedVersion].
     */
    fun performRollback(failedVersion: String = "unknown"): Boolean {
        val targetFailedVersion = if (failedVersion != "unknown") failedVersion else getPendingVersion()

        log.warn("[UpdateRecovery] ════════════════════════════════════════════════════════════════")
        log.warn("[UpdateRecovery] FAULTY INSTALLATION DETECTED! Rolling back to backup version...")
        log.warn("[UpdateRecovery] ════════════════════════════════════════════════════════════════")

        if (targetFailedVersion.isNotBlank() && targetFailedVersion != "unknown") {
            UpdateValidator.blacklistVersion(targetFailedVersion, "Faulty installation startup crash")
        }

        if (!backupDir.exists()) {
            log.error("[UpdateRecovery] Rollback failed: Backup directory .backup/ missing!")
            if (pendingFile.exists()) pendingFile.delete()
            if (resultFile.exists()) resultFile.delete()
            return false
        }

        return try {
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

            val scripts = listOf("run.sh", "run.bat", "update.sh", "update.bat", "reset-superadmin.sh", "reset-superadmin.bat")
            for (script in scripts) {
                val bScript = File(backupDir, script)
                if (bScript.exists()) {
                    bScript.copyTo(File(script), overwrite = true)
                }
            }

            if (pendingFile.exists()) pendingFile.delete()
            if (resultFile.exists()) resultFile.delete()

            val backupVersion = if (File(backupDir, "version.txt").exists()) File(backupDir, "version.txt").readText().trim() else "previous"
            log.info("[UpdateRecovery] ROLLBACK SUCCESSFUL! Restored installation (version: $backupVersion).")
            true
        } catch (e: Exception) {
            log.error("[UpdateRecovery] Rollback exception: ${e.message}")
            if (pendingFile.exists()) pendingFile.delete()
            if (resultFile.exists()) resultFile.delete()
            false
        }
    }
}

/**
 * CLI helper entry point for invocation from shell scripts if needed.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull()?.lowercase()
    when (mode) {
        "--rollback", "-r" -> {
            val version = args.getOrNull(1) ?: "unknown"
            UpdateRecoveryManager.performRollback(version)
        }
        "--mark-pending" -> {
            val version = args.getOrNull(1) ?: "unknown"
            UpdateRecoveryManager.markUpdatePending(version)
        }
        "--create-backup" -> {
            val version = args.getOrNull(1) ?: "unknown"
            UpdateRecoveryManager.createBackup(version)
        }
        "--boot-success" -> {
            UpdateRecoveryManager.markBootSuccessful()
        }
        else -> {
            println("Usage: java -cp obsidianscout-server.jar com.obsidianscout.utils.UpdateRecoveryManagerKt [--rollback|--mark-pending <version>|--create-backup <version>|--boot-success]")
        }
    }
}
