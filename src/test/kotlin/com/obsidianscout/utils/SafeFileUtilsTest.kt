package com.obsidianscout.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SafeFileUtilsTest {

    @Test
    fun testAtomicWriteStringAndBackup() {
        val tempDir = Files.createTempDirectory("safe_file_test")
        try {
            val targetFile = tempDir.resolve("test-config.json")

            // 1. Initial write
            SafeFileUtils.atomicWriteString(targetFile, "{\"version\": 1}")
            assertTrue(Files.exists(targetFile))
            assertEquals("{\"version\": 1}", Files.readString(targetFile))

            // 2. Overwrite and verify backup creation
            SafeFileUtils.atomicWriteString(targetFile, "{\"version\": 2}")
            assertEquals("{\"version\": 2}", Files.readString(targetFile))

            val backupFile = SafeFileUtils.getBackupPath(targetFile)
            assertTrue(Files.exists(backupFile), "Backup .bak file should exist after overwrite")
            assertEquals("{\"version\": 1}", Files.readString(backupFile), "Backup should contain previous version")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testSafeReadStringWithBackupFallbackRecovery() {
        val tempDir = Files.createTempDirectory("safe_file_recovery_test")
        try {
            val targetFile = tempDir.resolve("critical-config.json")

            // Create initial valid file and overwrite to populate .bak
            SafeFileUtils.atomicWriteString(targetFile, "{\"status\": \"healthy\"}")
            SafeFileUtils.atomicWriteString(targetFile, "{\"status\": \"updated\"}")

            // Simulate corrupted / 0-byte primary file (e.g. from power cut during naive write)
            Files.writeString(targetFile, "") // Truncated / empty

            val recovered = SafeFileUtils.safeReadStringWithBackupFallback(targetFile) { content ->
                content.contains("healthy") || content.contains("updated")
            }

            assertNotNull(recovered, "Should recover valid content from backup")
            assertTrue(recovered.contains("healthy"), "Should restore from .bak content")

            // Verify the primary file was automatically self-healed
            val primaryContent = Files.readString(targetFile)
            assertTrue(primaryContent.contains("healthy"), "Primary file should be automatically self-healed from backup")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testAtomicWriteBytes() {
        val tempDir = Files.createTempDirectory("safe_file_bytes_test")
        try {
            val targetFile = tempDir.resolve("data.bin")
            val sampleData = byteArrayOf(1, 2, 3, 4, 5, 42)

            SafeFileUtils.atomicWriteBytes(targetFile, sampleData)
            assertTrue(Files.exists(targetFile))
            assertEquals(sampleData.size.toLong(), Files.size(targetFile))
            assertTrue(Files.readAllBytes(targetFile).contentEquals(sampleData))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
