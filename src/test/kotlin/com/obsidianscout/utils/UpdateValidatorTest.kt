package com.obsidianscout.utils

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateValidatorTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("test-validator-").toFile()
        File(".update_failed_versions").delete()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
        File(".update_failed_versions").delete()
    }

    @Test
    fun testZipIntegrityValid() {
        val zipFile = File(tempDir, "valid.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("test.txt"))
            zip.write("Hello World".toByteArray())
            zip.closeEntry()
        }

        val result = UpdateValidator.validateZipIntegrity(zipFile)
        assertTrue(result is UpdateValidator.ValidationResult.Success, "Valid ZIP should pass integrity check.")
    }

    @Test
    fun testZipIntegrityCorrupt() {
        val corruptFile = File(tempDir, "corrupt.zip")
        corruptFile.writeText("This is not a zip file content")

        val result = UpdateValidator.validateZipIntegrity(corruptFile)
        assertTrue(result is UpdateValidator.ValidationResult.Error, "Corrupt file should fail integrity check.")
    }

    @Test
    fun testZipPathSecurity() {
        val destDir = File(tempDir, "dest")
        destDir.mkdirs()

        assertTrue(UpdateValidator.isSafeZipPath(destDir, "normal_file.txt"))
        assertTrue(UpdateValidator.isSafeZipPath(destDir, "subfolder/file.txt"))

        assertFalse(UpdateValidator.isSafeZipPath(destDir, "../outside.txt"))
        assertFalse(UpdateValidator.isSafeZipPath(destDir, "../../etc/passwd"))
    }

    @Test
    fun testChecksumVerification() {
        val dummyFile = File(tempDir, "data.bin")
        dummyFile.writeText("ObsidianScout Test Payload")

        // Expected SHA-256 for "ObsidianScout Test Payload"
        // Echo -n "ObsidianScout Test Payload" | sha256sum
        // e4d3606b533cb1719b222956cf91c49b6b7a54bd2ca447b856cf2822a15f0eb7
        val javaDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("ObsidianScout Test Payload".toByteArray())
            .joinToString("") { "%02x".format(it) }

        val success = UpdateValidator.validateChecksum(dummyFile, javaDigest)
        assertTrue(success is UpdateValidator.ValidationResult.Success, "Matching checksum should pass.")

        val mismatch = UpdateValidator.validateChecksum(dummyFile, "0000000000000000000000000000000000000000000000000000000000000000")
        assertTrue(mismatch is UpdateValidator.ValidationResult.Error, "Mismatched checksum should fail.")
    }

    @Test
    fun testVersionBlacklisting() {
        val version = "0.9.9.9"
        assertFalse(UpdateValidator.isBlacklistedVersion(version))

        UpdateValidator.blacklistVersion(version, "Boot crash test")
        assertTrue(UpdateValidator.isBlacklistedVersion(version))
        assertTrue(UpdateValidator.isBlacklistedVersion("v0.9.9.9"))
    }
}
