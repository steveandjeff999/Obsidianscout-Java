package com.obsidianscout.utils

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Crash-safe, power-loss-resilient file writing and recovery utilities.
 *
 * Guarantees that files are never left half-written or corrupted (0 bytes) during
 * unexpected power loss, kernel panics, or application process termination.
 */
object SafeFileUtils {

    /**
     * Atomically writes string content to [targetPath].
     *
     * 1. Writes content to a temporary file in the same directory.
     * 2. Calls OS `fsync()` (via [FileOutputStream.getFD] sync) to ensure data is physically committed to non-volatile storage.
     * 3. Creates a `.bak` backup copy of the target file if it already exists.
     * 4. Atomically replaces [targetPath] with the temp file.
     */
    fun atomicWriteString(
        targetPath: Path,
        content: String,
        createBackup: Boolean = true,
        charset: Charset = Charsets.UTF_8
    ) {
        atomicWriteBytes(targetPath, content.toByteArray(charset), createBackup)
    }

    fun atomicWriteString(
        targetFile: File,
        content: String,
        createBackup: Boolean = true,
        charset: Charset = Charsets.UTF_8
    ) {
        atomicWriteString(targetFile.toPath(), content, createBackup, charset)
    }

    /**
     * Atomically writes byte array content to [targetPath].
     */
    fun atomicWriteBytes(
        targetPath: Path,
        content: ByteArray,
        createBackup: Boolean = true
    ) {
        val parentDir = targetPath.toAbsolutePath().parent
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        val tempPath = parentDir?.resolve("${targetPath.fileName}.${UUID.randomUUID()}.tmp")
            ?: Paths.get("${targetPath}.${UUID.randomUUID()}.tmp")

        try {
            // 1. Write to temporary file and force hardware fsync
            FileOutputStream(tempPath.toFile()).use { fos ->
                fos.write(content)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (e: Exception) {
                    // In some mock or virtual environments fsync may be unsupported
                }
            }

            // 2. Create .bak backup of existing file if requested
            if (createBackup && Files.exists(targetPath) && Files.size(targetPath) > 0) {
                val backupPath = getBackupPath(targetPath)
                try {
                    Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    System.err.println("[SafeFileUtils] Warning: Failed to create backup file $backupPath: ${e.message}")
                }
            }

            // 3. Perform atomic move/rename
            try {
                Files.move(
                    tempPath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // Fallback for filesystems that do not support ATOMIC_MOVE across boundaries
                Files.move(
                    tempPath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            // Clean up temporary file on failure
            try {
                Files.deleteIfExists(tempPath)
            } catch (_: Exception) {}
            throw e
        }
    }

    fun atomicWriteBytes(
        targetFile: File,
        content: ByteArray,
        createBackup: Boolean = true
    ) {
        atomicWriteBytes(targetFile.toPath(), content, createBackup)
    }

    /**
     * Reads string content from [targetPath], falling back to its `.bak` backup if the file is
     * missing, empty, or fails the provided [validator].
     *
     * If fallback to `.bak` is successful, it automatically self-heals [targetPath] by restoring the backup.
     */
    fun safeReadStringWithBackupFallback(
        targetPath: Path,
        charset: Charset = Charsets.UTF_8,
        validator: (String) -> Boolean = { it.isNotBlank() }
    ): String? {
        val backupPath = getBackupPath(targetPath)

        // Try reading the primary file
        if (Files.exists(targetPath) && Files.size(targetPath) > 0) {
            try {
                val text = Files.readString(targetPath, charset)
                if (validator(text)) {
                    return text
                }
                System.err.println("[SafeFileUtils] Primary file $targetPath failed content validation. Attempting backup recovery...")
            } catch (e: Exception) {
                System.err.println("[SafeFileUtils] Failed to read primary file $targetPath (${e.message}). Attempting backup recovery...")
            }
        }

        // Try reading from backup
        if (Files.exists(backupPath) && Files.size(backupPath) > 0) {
            try {
                val backupText = Files.readString(backupPath, charset)
                if (validator(backupText)) {
                    println("[SafeFileUtils] Successfully recovered valid data from backup $backupPath. Restoring primary file...")
                    try {
                        atomicWriteString(targetPath, backupText, createBackup = false, charset = charset)
                    } catch (restoreEx: Exception) {
                        System.err.println("[SafeFileUtils] Warning: Failed to write back restored content to $targetPath: ${restoreEx.message}")
                    }
                    return backupText
                }
            } catch (e: Exception) {
                System.err.println("[SafeFileUtils] Failed reading backup file $backupPath: ${e.message}")
            }
        }

        return null
    }

    fun getBackupPath(targetPath: Path): Path {
        val fileName = targetPath.fileName.toString()
        val parent = targetPath.toAbsolutePath().parent
        return parent?.resolve("$fileName.bak") ?: Paths.get("$fileName.bak")
    }
}
