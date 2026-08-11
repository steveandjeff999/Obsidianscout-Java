package com.obsidianscout.utils

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateRecoveryManagerTest {

    @BeforeTest
    fun setUp() {
        cleanState()
    }

    @AfterTest
    fun tearDown() {
        cleanState()
    }

    private fun cleanState() {
        UpdateRecoveryManager.backupDir.deleteRecursively()
        UpdateRecoveryManager.pendingFile.delete()
        UpdateRecoveryManager.resultFile.delete()
        File(".update_failed_versions").delete()
    }

    @Test
    fun testPendingUpdateLifecycle() {
        assertFalse(UpdateRecoveryManager.isUpdatePending())

        UpdateRecoveryManager.markUpdatePending("0.3.5.0")
        assertTrue(UpdateRecoveryManager.isUpdatePending())
        assertEquals("0.3.5.0", UpdateRecoveryManager.getPendingVersion())

        UpdateRecoveryManager.markBootSuccessful()
        assertFalse(UpdateRecoveryManager.isUpdatePending())
    }

    @Test
    fun testBackupAndRollback() {
        val testJar = File("obsidianscout-server.jar")
        val originalText = "Original Server Jar Content v0.1"
        testJar.writeText(originalText)

        try {
            val backupOk = UpdateRecoveryManager.createBackup("0.1.0.0")
            assertTrue(backupOk, "Backup creation should succeed.")
            assertTrue(File(UpdateRecoveryManager.backupDir, "obsidianscout-server.jar").exists())

            // Simulate corrupted update overwriting the JAR
            testJar.writeText("Corrupted Update Content v0.2")
            UpdateRecoveryManager.markUpdatePending("0.2.0.0")

            val rollbackOk = UpdateRecoveryManager.performRollback("0.2.0.0")
            assertTrue(rollbackOk, "Rollback should succeed.")
            assertEquals(originalText, testJar.readText(), "Restored JAR should match original backup.")
            assertFalse(UpdateRecoveryManager.isUpdatePending(), "Pending file should be cleared after rollback.")
            assertTrue(UpdateValidator.isBlacklistedVersion("0.2.0.0"), "Failed version should be blacklisted.")
        } finally {
            testJar.delete()
        }
    }
}
