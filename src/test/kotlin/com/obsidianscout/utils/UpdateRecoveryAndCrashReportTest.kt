package com.obsidianscout.utils

import com.obsidianscout.admin.ServerErrorAlertService
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.ReportedErrors
import com.obsidianscout.db.readTransaction
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.selectAll
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class UpdateRecoveryAndCrashReportTest {

    private val testDbFile = File("build/test_crash_report_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        if (UpdateRecoveryManager.pendingFile.exists()) UpdateRecoveryManager.pendingFile.delete()
        if (UpdateRecoveryManager.crashReportFile.exists()) UpdateRecoveryManager.crashReportFile.delete()
        if (UpdateRecoveryManager.backupDir.exists()) UpdateRecoveryManager.backupDir.deleteRecursively()
        UpdateRecoveryManager.isBootCompleted.set(false)
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
        if (testDbFile.exists()) testDbFile.delete()
        if (UpdateRecoveryManager.pendingFile.exists()) UpdateRecoveryManager.pendingFile.delete()
        if (UpdateRecoveryManager.crashReportFile.exists()) UpdateRecoveryManager.crashReportFile.delete()
        if (UpdateRecoveryManager.backupDir.exists()) UpdateRecoveryManager.backupDir.deleteRecursively()
        val oomFile = File(".oom_occurred")
        if (oomFile.exists()) oomFile.delete()
    }

    @Test
    fun testUpdateFailureRollbackAndCrashReportGeneration() {
        // 1. Stage backup files
        UpdateRecoveryManager.backupDir.mkdirs()
        File(UpdateRecoveryManager.backupDir, "version.txt").writeText("0.5.6.5")
        File(UpdateRecoveryManager.backupDir, "obsidianscout-server.jar").writeText("mock jar 0.5.6.5")

        // 2. Mark update to 0.5.6.6 as pending
        UpdateRecoveryManager.markUpdatePending("0.5.6.6")
        assertTrue(UpdateRecoveryManager.isUpdatePending(), "Update should be marked pending")
        assertEquals("0.5.6.6", UpdateRecoveryManager.getPendingVersion())

        // 3. Simulate startup crash and trigger rollback
        val crashLog = "java.lang.RuntimeException: Database schema mismatch at App.kt:123"
        val rollbackSuccess = UpdateRecoveryManager.performRollback(
            failedVersion = "0.5.6.6",
            reason = "Failed database migration on boot",
            crashLog = crashLog
        )

        assertTrue(rollbackSuccess, "Rollback should succeed")
        assertFalse(UpdateRecoveryManager.isUpdatePending(), "Pending update marker should be cleared after rollback")

        // 4. Verify crash report file is written
        assertTrue(UpdateRecoveryManager.crashReportFile.exists(), "Crash report file should exist on disk")
        val reportJson = SafeFileUtils.safeReadStringWithBackupFallback(UpdateRecoveryManager.crashReportFile.toPath())
        assertNotNull(reportJson)

        val report = JsonSupport.json.decodeFromString<PendingCrashReportDto>(reportJson)
        assertEquals("UPDATE_STARTUP_FAILURE", report.errorType)
        assertEquals("0.5.6.6", report.failedVersion)
        assertEquals("0.5.6.5", report.restoredVersion)
        assertTrue(report.wasRolledBack)
        assertTrue(report.errorMessage.contains("0.5.6.6"))
        assertEquals(crashLog, report.errorStack)
    }

    @Test
    fun testNativeBinariesAndDynamicLibrariesBackupAndRollback() {
        val testNativeExe = File("obsidianscout-server-native-test.exe")
        val testDll = File("sqlitejdbc.dll")
        val testSo = File("libsqlitejdbc.so")
        val testDylib = File("libsqlitejdbc.dylib")

        try {
            // 1. Create mock native files and shared libraries
            testNativeExe.writeText("native binary content v1")
            testDll.writeText("windows dll content v1")
            testSo.writeText("linux so content v1")
            testDylib.writeText("macos dylib content v1")

            // 2. Create backup
            val backupOk = UpdateRecoveryManager.createBackup("1.0.0")
            assertTrue(backupOk)
            assertTrue(File(UpdateRecoveryManager.backupDir, testNativeExe.name).exists())
            assertTrue(File(UpdateRecoveryManager.backupDir, testDll.name).exists())
            assertTrue(File(UpdateRecoveryManager.backupDir, testSo.name).exists())
            assertTrue(File(UpdateRecoveryManager.backupDir, testDylib.name).exists())

            // 3. Simulate new broken update overwriting files
            testNativeExe.writeText("broken native binary content v2")
            testDll.writeText("broken dll v2")
            testSo.writeText("broken so v2")
            testDylib.writeText("broken dylib v2")
            UpdateRecoveryManager.markUpdatePending("2.0.0")

            // 4. Perform rollback
            val rollbackOk = UpdateRecoveryManager.performRollback("2.0.0", "Native startup crash")
            assertTrue(rollbackOk)

            // 5. Verify restored content
            assertEquals("native binary content v1", testNativeExe.readText())
            assertEquals("windows dll content v1", testDll.readText())
            assertEquals("linux so content v1", testSo.readText())
            assertEquals("macos dylib content v1", testDylib.readText())
        } finally {
            testNativeExe.delete()
            testDll.delete()
            testSo.delete()
            testDylib.delete()
        }
    }

    @Test
    fun testServerErrorAlertServiceIngestsCrashReportAndDeletesFile() {
        // 1. Initialize test database
        val config = DatabaseConfig(
            type = "sqlite",
            sqlite = SqliteConfig(file = testDbFile.path)
        )
        DatabaseFactory.init(config, runMigration = true)

        // 2. Write a pending crash report to disk
        val report = PendingCrashReportDto(
            errorType = "UPDATE_STARTUP_FAILURE",
            errorMessage = "Server failed to start after update to v0.5.7.0",
            errorStack = "java.lang.NullPointerException: config is null",
            failedVersion = "0.5.7.0",
            restoredVersion = "0.5.6.6",
            wasRolledBack = true
        )
        UpdateRecoveryManager.recordCrashReport(report)
        assertTrue(UpdateRecoveryManager.crashReportFile.exists())

        // 3. Ingest pending crash reports
        ServerErrorAlertService.processPendingCrashReports()

        // 4. Verify the report is in the database ReportedErrors table
        val errors = readTransaction {
            ReportedErrors.selectAll().map {
                Triple(
                    it[ReportedErrors.errorType],
                    it[ReportedErrors.errorMessage],
                    it[ReportedErrors.status]
                )
            }
        }

        assertEquals(1, errors.size)
        assertEquals("UPDATE_STARTUP_FAILURE", errors[0].first)
        assertTrue(errors[0].second.contains("0.5.7.0"))
        assertEquals("OPEN", errors[0].third)

        // 5. Verify local crash report file was deleted to avoid re-uploading
        assertFalse(UpdateRecoveryManager.crashReportFile.exists(), "Local crash report file should be deleted after ingestion")
    }

    @Test
    fun testRuntimeCrashReportIngestion() {
        val config = DatabaseConfig(
            type = "sqlite",
            sqlite = SqliteConfig(file = testDbFile.path)
        )
        DatabaseFactory.init(config, runMigration = true)

        // Mark boot as completed (runtime mode)
        UpdateRecoveryManager.markBootSuccessful()
        assertTrue(UpdateRecoveryManager.isBootCompleted.get())
        assertFalse(UpdateRecoveryManager.isUpdatePending())

        // Record a runtime crash
        UpdateRecoveryManager.recordCrashReport(
            PendingCrashReportDto(
                errorType = "SERVER_CRASH",
                errorMessage = "Unhandled exception in background worker",
                errorStack = "java.lang.IllegalStateException: Worker failed"
            )
        )

        // Ingest on restart
        ServerErrorAlertService.processPendingCrashReports()

        val errors = readTransaction {
            ReportedErrors.selectAll().map { it[ReportedErrors.errorType] to it[ReportedErrors.errorMessage] }
        }

        assertEquals(1, errors.size)
        assertEquals("SERVER_CRASH", errors[0].first)
        assertTrue(errors[0].second.contains("Unhandled exception"))
        assertFalse(UpdateRecoveryManager.crashReportFile.exists())
    }
}
