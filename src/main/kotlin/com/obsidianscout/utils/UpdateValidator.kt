package com.obsidianscout.utils

import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * UpdateValidator
 *
 * Provides static validation methods for software updates:
 * - ZIP archive integrity verification
 * - Zip-Slip path traversal prevention
 * - SHA-256 checksum verification
 * - Extracted bundle & JAR executable structure validation
 * - Blacklisting of failed update versions
 */
object UpdateValidator {
    private val log = LoggerFactory.getLogger("UpdateValidator")
    private val blacklistFile = File(".update_failed_versions")

    /**
     * Verifies that [zipFile] is a readable, non-corrupt ZIP archive.
     */
    fun validateZipIntegrity(zipFile: File): ValidationResult {
        if (!zipFile.exists() || !zipFile.isFile) {
            return ValidationResult.Error("ZIP file does not exist or is not a file: ${zipFile.absolutePath}")
        }
        if (zipFile.length() == 0L) {
            return ValidationResult.Error("ZIP file is empty (0 bytes).")
        }

        val header = ByteArray(2)
        try {
            zipFile.inputStream().use { it.read(header) }
        } catch (_: Exception) {}

        val isGzip = (header.size >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte())

        if (zipFile.name.endsWith(".tar.gz") || zipFile.name.endsWith(".tgz") || isGzip) {
            return try {
                java.util.zip.GZIPInputStream(zipFile.inputStream()).use { gz ->
                    val buf = ByteArray(64)
                    gz.read(buf)
                }
                ValidationResult.Success
            } catch (e: Exception) {
                ValidationResult.Error("TAR.GZ integrity check failed: ${e.message}")
            }
        }

        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                if (!entries.hasMoreElements()) {
                    return ValidationResult.Error("ZIP file contains no entries.")
                }
            }
        } catch (e: Exception) {
            return ValidationResult.Error("ZIP integrity check failed: ${e.message}")
        }

        return ValidationResult.Success
    }

    /**
     * Checks whether an entry path in a ZIP file targets outside the [destDir] (Zip-Slip attack).
     */
    fun isSafeZipPath(destDir: File, entryName: String): Boolean {
        val destCanonical = destDir.canonicalPath
        val sanitized = entryName.replace('\\', '/')
        val targetFile = File(destDir, sanitized).canonicalFile
        return targetFile.path.startsWith(destCanonical + File.separator) || targetFile == destDir.canonicalFile
    }

    /**
     * Computes the SHA-256 hex string of [file] and compares it against [expectedSha256].
     */
    fun validateChecksum(file: File, expectedSha256: String): ValidationResult {
        if (expectedSha256.isBlank()) return ValidationResult.Success

        if (!file.exists()) {
            return ValidationResult.Error("File to verify does not exist: ${file.absolutePath}")
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            val cleanExpected = expectedSha256.trim().lowercase()
            if (actualHash.equals(cleanExpected, ignoreCase = true)) {
                ValidationResult.Success
            } else {
                ValidationResult.Error("SHA-256 checksum mismatch. Expected: $cleanExpected, Actual: $actualHash")
            }
        } catch (e: Exception) {
            ValidationResult.Error("Failed to calculate SHA-256 hash: ${e.message}")
        }
    }

    /**
     * Validates that [jarFile] is a valid JAR containing required manifest and class entries.
     */
    fun validateJarStructure(jarFile: File, requiredMainClass: String = "com/obsidianscout/AppKt.class"): ValidationResult {
        if (!jarFile.exists() || !jarFile.isFile) {
            return ValidationResult.Error("JAR file does not exist: ${jarFile.absolutePath}")
        }
        if (jarFile.length() < 1024L) { // Server fat JAR should be significantly larger than 1KB
            return ValidationResult.Error("JAR file is suspiciously small (${jarFile.length()} bytes).")
        }

        var foundManifest = false
        var foundMainClass = false

        try {
            ZipFile(jarFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.replace('\\', '/')
                    if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
                        foundManifest = true
                    }
                    if (name.equals(requiredMainClass, ignoreCase = true) || name.contains("AppKt.class")) {
                        foundMainClass = true
                    }
                }
            }
        } catch (e: Exception) {
            return ValidationResult.Error("Failed to parse JAR structure: ${e.message}")
        }

        if (!foundManifest) {
            return ValidationResult.Error("JAR missing META-INF/MANIFEST.MF.")
        }
        if (!foundMainClass) {
            return ValidationResult.Error("JAR missing main application class entry ($requiredMainClass).")
        }

        return ValidationResult.Success
    }

    /**
     * Validates an extracted update root directory.
     * Verifies that `obsidianscout-server.jar` exists and passes structure validation.
     */
    fun validateExtractedBundle(srcRoot: File): ValidationResult {
        if (!srcRoot.exists() || !srcRoot.isDirectory) {
            return ValidationResult.Error("Extracted root is not a valid directory: ${srcRoot.absolutePath}")
        }

        val serverJar = File(srcRoot, "obsidianscout-server.jar")
        val nativeFiles = srcRoot.listFiles { _, name -> name.startsWith("obsidianscout-server-native", ignoreCase = true) } ?: emptyArray()

        if (!serverJar.exists() && nativeFiles.isEmpty()) {
            return ValidationResult.Error("Neither obsidianscout-server.jar nor native executable found in extracted update bundle.")
        }

        if (serverJar.exists()) {
            val jarCheck = validateJarStructure(serverJar)
            if (jarCheck is ValidationResult.Error && nativeFiles.isEmpty()) {
                return jarCheck
            }
        }

        return ValidationResult.Success
    }

    // ── Version Blacklist Management (Disabled) ───────────────────────────────────────

    /**
     * Checks whether [version] is in the failed versions blacklist.
     * Always returns false (version blacklisting disabled).
     */
    fun isBlacklistedVersion(version: String): Boolean {
        return false
    }

    /**
     * Records [version] into the failed versions blacklist file.
     * No-op (version blacklisting disabled).
     */
    fun blacklistVersion(version: String, reason: String = "Boot failure") {
        // No-op
    }

    /**
     * Reads all blacklisted version strings from disk.
     */
    fun getBlacklistedVersions(): Set<String> {
        return emptySet()
    }

    sealed class ValidationResult {
        object Success : ValidationResult()
        data class Error(val message: String) : ValidationResult()

        val isSuccess: Boolean get() = this is Success
    }
}
