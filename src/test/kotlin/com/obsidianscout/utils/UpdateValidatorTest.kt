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

    // checksums.txt exactly as the release workflow's sha256sum writes it on a Linux runner ("hash  name"),
    // plus the binary-mode marker other sha256sum builds emit ("hash *name").
    private val releaseChecksums = """
        efcc31d61f002ec6b096379cf2fcb849ea2cb59da6c0fc81ef2061eab62fb23b  obsidianscout-0.7.3.4-1.x86_64.rpm
        fae7c33cde564c82e9f7f2c00412ba98bea12937ee0a67ab53860b691e93af11  obsidianscout-v0.7.3.4-fatjar.zip
        e4b35e9ec91ad797c3bc36236b1ea19755e9b5238467ef045bde8ce60739834e  obsidianscout-v0.7.3.4-linux-arm64.tar.gz
        F94ED06224C385EE7F8B04454F0680331B808A89B02241E42AA416461ACE25B3  obsidianscout-v0.7.3.4-linux-x86_64.tar.gz
        e2fbc8bdab1f2e2328e6eb0e2a7aa5ada3bfb3977c1ad954fd63fcf75a7b3b7f *obsidianscout-v0.7.3.4-windows-x86_64.zip
    """.trimIndent()

    @Test
    fun `findChecksumForAsset resolves every platform package in checksums txt`() {
        val expected = mapOf(
            "obsidianscout-v0.7.3.4-windows-x86_64.zip" to "e2fbc8bdab1f2e2328e6eb0e2a7aa5ada3bfb3977c1ad954fd63fcf75a7b3b7f",
            "obsidianscout-v0.7.3.4-linux-x86_64.tar.gz" to "f94ed06224c385ee7f8b04454f0680331b808a89b02241e42aa416461ace25b3",
            "obsidianscout-v0.7.3.4-linux-arm64.tar.gz" to "e4b35e9ec91ad797c3bc36236b1ea19755e9b5238467ef045bde8ce60739834e",
            "obsidianscout-v0.7.3.4-fatjar.zip" to "fae7c33cde564c82e9f7f2c00412ba98bea12937ee0a67ab53860b691e93af11"
        )
        expected.forEach { (asset, hash) ->
            assertEquals(hash, UpdateValidator.findChecksumForAsset(releaseChecksums, asset), asset)
        }
        assertEquals(null, UpdateValidator.findChecksumForAsset(releaseChecksums, "obsidianscout-v0.7.3.4-macos-arm64.zip"))
        // A name that's a prefix of another asset must not match it.
        assertEquals(null, UpdateValidator.findChecksumForAsset(releaseChecksums, "obsidianscout-v0.7.3.4-linux"))
    }

    @Test
    fun `normalizeSha256 accepts GitHub digest format and rejects junk`() {
        val hex = "e2fbc8bdab1f2e2328e6eb0e2a7aa5ada3bfb3977c1ad954fd63fcf75a7b3b7f"
        assertEquals(hex, UpdateValidator.normalizeSha256("sha256:$hex"))
        assertEquals(hex, UpdateValidator.normalizeSha256(" SHA256:${hex.uppercase()} "))
        assertEquals(hex, UpdateValidator.normalizeSha256(hex))
        assertEquals(null, UpdateValidator.normalizeSha256(null))
        assertEquals(null, UpdateValidator.normalizeSha256(""))
        assertEquals(null, UpdateValidator.normalizeSha256("sha1:abc"))
    }

    @Test
    fun `validateChecksum accepts digest from checksums txt for the downloaded file`() {
        val file = File(tempDir, "obsidianscout-v1-fatjar.zip").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        val checksums = "$hash  ${file.name}" + System.lineSeparator()
        val resolved = UpdateValidator.findChecksumForAsset(checksums, file.name)
        assertEquals(hash, resolved)
        assertTrue(UpdateValidator.validateChecksum(file, resolved!!) is UpdateValidator.ValidationResult.Success)
        assertTrue(UpdateValidator.validateChecksum(file, "") is UpdateValidator.ValidationResult.Error)
    }
}
