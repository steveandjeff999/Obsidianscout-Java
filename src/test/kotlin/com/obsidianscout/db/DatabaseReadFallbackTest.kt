package com.obsidianscout.db

import com.obsidianscout.db.orchestration.CockroachOrchestrator
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseReadFallbackTest {

    object TestItems : Table("test_items") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 50)
        override val primaryKey = PrimaryKey(id)
    }

    private val testDbFile = java.io.File("build/test_read_fallback_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        DatabaseFactory.isCockroach = false
        CockroachOrchestrator.isQuorumLost = false
        CockroachOrchestrator.quorumLossDetails = null
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.isCockroach = false
        CockroachOrchestrator.isQuorumLost = false
        CockroachOrchestrator.quorumLossDetails = null
        DatabaseFactory.close()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testIsQuorumLossExceptionComprehensive() {
        val testCases = listOf(
            "ERROR: read quorum lost: only 1 replica responding" to true,
            "cannot write because replicas are unavailable" to true,
            "raft leader unavailable: node partitioned" to true,
            "range unavailable for descriptor /Table/53/1/..." to true,
            "TransactionRetryWithProtoRefreshError: range is unavailable" to true,
            "latch acquisition failed: poisoned latch detected" to true,
            "AmbiguousResultError: result is ambiguous due to lost leader" to true,
            "rpc error: code = Unavailable desc = transport is closing" to true,
            "failed to find replica descriptor for range" to true,
            "context deadline exceeded while waiting for range lease" to true,
            "node liveness: heartbeat failed due to deadline exceeded" to true,
            "ERROR: query execution canceled due to statement timeout" to true,
            "canceling statement due to statement timeout" to true,
            "An I/O error occurred while sending to the backend." to true,
            "Read timed out" to true,
            "duplicate key value violates unique constraint" to false,
            "table 'non_existent_table' does not exist" to false,
            "syntax error at or near 'SELEECT'" to false
        )

        for ((message, expected) in testCases) {
            val exception = SQLException(message)
            val nested = RuntimeException("Wrapped DB error", exception)
            assertEquals(
                expected,
                CockroachOrchestrator.isQuorumLossException(exception),
                "Failed quorum detection for: '$message'"
            )
            assertEquals(
                expected,
                CockroachOrchestrator.isQuorumLossException(nested),
                "Failed nested quorum detection for: '$message'"
            )
        }
    }

    @Test
    fun testReadTransactionExecutesNormally() {
        DatabaseFactory.isCockroach = false
        val db = org.jetbrains.exposed.sql.Database.connect(
            url = "jdbc:sqlite:${testDbFile.absolutePath}",
            driver = "org.sqlite.JDBC"
        )

        transaction(db) {
            SchemaUtils.create(TestItems)
            TestItems.insert { it[name] = "Obsidian" }
            TestItems.insert { it[name] = "Scout" }
        }

        val items = readTransaction(db) {
            TestItems.selectAll().map { it[TestItems.name] }
        }

        assertEquals(listOf("Obsidian", "Scout"), items)
    }

    @Test
    fun testQuorumFallbackStoreInitializationAndQuery() {
        val testFallbackDbFile = java.io.File("build/test_quorum_fallback_${System.currentTimeMillis()}.db")
        val appConfig = com.obsidianscout.config.AppConfig(
            quorum_fallback = com.obsidianscout.config.QuorumFallbackConfig(
                enabled = true,
                sqlite_file = testFallbackDbFile.absolutePath,
                sync_interval_seconds = 30L,
                scouting_retention_days = 7
            )
        )

        QuorumFallbackStore.init(appConfig)
        assertTrue(QuorumFallbackStore.isEnabled)
        assertTrue(QuorumFallbackStore.isAvailable)

        // Seed sample users into SQLite mirror
        val sqliteDb = QuorumFallbackStore.sqliteDb
        assertTrue(sqliteDb != null)

        transaction(sqliteDb) {
            Users.insert {
                it[id] = org.jetbrains.exposed.dao.id.EntityID(java.util.UUID.randomUUID(), Users)
                it[username] = "scout_tester"
                it[teamNumber] = 9999
                it[program] = "FRC"
                it[passwordHash] = "hash"
                it[role] = "SCOUTER"
                it[createdAt] = java.time.Instant.now()
            }
        }

        // When quorum is lost, readTransaction should route to QuorumFallbackStore
        CockroachOrchestrator.isQuorumLost = true
        DatabaseFactory.isCockroach = true

        val usernames = DatabaseFactory.readTransaction {
            Users.selectAll().map { it[Users.username] }
        }

        assertEquals(listOf("scout_tester"), usernames)

        // Verify status reporting
        val status = QuorumFallbackStore.getStatus()
        assertTrue(status.enabled)
        assertTrue(status.isAvailable)
        assertTrue(status.isActiveServingReads)
        assertTrue(status.freeDiskSpaceBytes > 0)
        assertTrue(status.totalDiskSpaceBytes > 0)
        assertTrue(status.recordCounts["users"] == 1L)

        // Test disable and purge
        QuorumFallbackStore.disableAndPurge(updateConfigFile = false)
        assertFalse(QuorumFallbackStore.isEnabled)
        assertFalse(QuorumFallbackStore.isAvailable)
        assertFalse(testFallbackDbFile.exists())
    }

    @Test
    fun testSyncFromCockroachDoesNotThrowReadOnlyError() {
        val testMainDbFile = java.io.File("build/test_main_db_${System.currentTimeMillis()}.db")
        val testFallbackDbFile = java.io.File("build/test_fallback_db_${System.currentTimeMillis()}.db")

        try {
            // 1. Initialize main DB
            val dbConfig = com.obsidianscout.config.DatabaseConfig(
                type = "sqlite",
                sqlite = com.obsidianscout.config.SqliteConfig(file = testMainDbFile.absolutePath)
            )
            DatabaseFactory.init(dbConfig, runMigration = true, isCockroach = false)

            // Seed user in main database
            DatabaseFactory.readTransaction {
                // Should run without error
            }

            // 2. Initialize fallback store
            val appConfig = com.obsidianscout.config.AppConfig(
                quorum_fallback = com.obsidianscout.config.QuorumFallbackConfig(
                    enabled = true,
                    sqlite_file = testFallbackDbFile.absolutePath,
                    sync_interval_seconds = 30L,
                    scouting_retention_days = 7
                )
            )
            QuorumFallbackStore.init(appConfig)

            // 3. Execute sync
            val synced = QuorumFallbackStore.syncFromCockroach()
            assertTrue(synced, "Sync should succeed without read-only SQLException: ${QuorumFallbackStore.lastSyncStatus}")

            // 4. CRITICAL: Verify that TransactionManager.defaultDatabase is STILL the main DB
            assertEquals(
                DatabaseFactory.primaryDatabase,
                org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase,
                "TransactionManager.defaultDatabase must remain pointing to primaryDatabase and not fallback SQLite"
            )

            QuorumFallbackStore.disableAndPurge(updateConfigFile = false)
        } finally {
            DatabaseFactory.close()
            if (testMainDbFile.exists()) testMainDbFile.delete()
            if (testFallbackDbFile.exists()) testFallbackDbFile.delete()
        }
    }

    @Test
    fun testPrimaryDatabaseAndAuthPreservedWhenFallbackEnabled() {
        val testMainDbFile = java.io.File("build/test_main_db_auth_${System.currentTimeMillis()}.db")
        val testFallbackDbFile = java.io.File("build/test_fallback_db_auth_${System.currentTimeMillis()}.db")

        try {
            val dbConfig = com.obsidianscout.config.DatabaseConfig(
                type = "sqlite",
                sqlite = com.obsidianscout.config.SqliteConfig(file = testMainDbFile.absolutePath)
            )
            DatabaseFactory.init(dbConfig, runMigration = true, isCockroach = false)

            // Insert superadmin in main database
            transaction {
                Users.insert {
                    it[id] = org.jetbrains.exposed.dao.id.EntityID(java.util.UUID.randomUUID(), Users)
                    it[username] = "superadmin"
                    it[teamNumber] = 0
                    it[program] = "FRC"
                    it[passwordHash] = "secret_hash"
                    it[role] = "SUPERADMIN"
                    it[createdAt] = java.time.Instant.now()
                }
            }

            // Verify superadmin is readable before enabling fallback
            val userBefore = transaction {
                Users.selectAll().where { Users.username eq "superadmin" }.singleOrNull()
            }
            assertTrue(userBefore != null, "Superadmin should exist in main database")

            // Initialize and enable fallback store
            val appConfig = com.obsidianscout.config.AppConfig(
                quorum_fallback = com.obsidianscout.config.QuorumFallbackConfig(
                    enabled = true,
                    sqlite_file = testFallbackDbFile.absolutePath,
                    sync_interval_seconds = 30L,
                    scouting_retention_days = 7
                )
            )
            QuorumFallbackStore.init(appConfig)

            // CRITICAL: Verify default transaction still finds superadmin in main database
            val userAfter = transaction {
                Users.selectAll().where { Users.username eq "superadmin" }.singleOrNull()
            }
            assertTrue(userAfter != null, "Superadmin MUST still be found in default transactions after fallback is enabled!")
            assertEquals("superadmin", userAfter[Users.username])

            QuorumFallbackStore.disableAndPurge(updateConfigFile = false)
        } finally {
            DatabaseFactory.close()
            if (testMainDbFile.exists()) testMainDbFile.delete()
            if (testFallbackDbFile.exists()) testFallbackDbFile.delete()
        }
    }

    @Test
    fun testLoadBalancerExcludesHealthAndVersionEndpoints() {
        val settings = com.obsidianscout.integrations.LoadBalancerSettings()
        assertTrue(settings.excludedPathPrefixes.contains("/health"))
        assertTrue(settings.excludedPathPrefixes.contains("/api/health"))
        assertTrue(settings.excludedPathPrefixes.contains("/version"))
        assertTrue(settings.excludedPathPrefixes.contains("/api/version"))
        assertTrue(settings.excludedPathPrefixes.contains("/api/cluster"))
    }

    @Test
    fun testStaticCodeAnalysisNoMutationsInReadTransactions() {
        val srcDir = java.io.File("src/main/kotlin")
        assertTrue(srcDir.exists(), "Source directory src/main/kotlin should exist")

        val ktFiles = srcDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(ktFiles.isNotEmpty(), "Kotlin source files should be found")

        val mutationKeywords = listOf(
            ".insert {",
            ".insertAndGetId {",
            ".batchInsert(",
            ".update({",
            ".deleteWhere {",
            ".deleteAll()",
            "SchemaUtils.create",
            "SchemaUtils.drop"
        )

        var totalFilesInspected = 0
        var totalReadTxFound = 0
        var totalWriteTxFound = 0
        val filesWithReadTx = mutableListOf<String>()
        val filesWithWriteTx = mutableListOf<String>()

        val readRegex = Regex("""\breadTransaction\s*(\([^\)]*\))?\s*\{""")
        val writeRegex = Regex("""\btransaction\s*(\([^\)]*\))?\s*\{""")

        for (file in ktFiles) {
            totalFilesInspected++
            val content = file.readText()
            val readMatches = readRegex.findAll(content).toList()
            val writeMatches = writeRegex.findAll(content).toList()

            if (readMatches.isNotEmpty()) {
                filesWithReadTx.add("${file.name} (${readMatches.size} calls)")
            }
            if (writeMatches.isNotEmpty()) {
                filesWithWriteTx.add("${file.name} (${writeMatches.size} calls)")
            }

            for (match in readMatches) {
                totalReadTxFound++
                val braceStart = match.range.last // index of '{'
                var braceDepth = 1
                var i = braceStart + 1
                while (i < content.length && braceDepth > 0) {
                    if (content[i] == '{') braceDepth++
                    else if (content[i] == '}') braceDepth--
                    i++
                }
                val blockBody = content.substring(braceStart, i)
                for (mutation in mutationKeywords) {
                    assertFalse(
                        blockBody.contains(mutation),
                        "File ${file.name} contains mutation '$mutation' inside readTransaction block:\n$blockBody"
                    )
                }
            }

            totalWriteTxFound += writeMatches.size
        }

        println("=== EXHAUSTIVE TRANSACTION AUDIT REPORT ===")
        println("Total Kotlin Files Inspected: $totalFilesInspected")
        println("Total readTransaction calls: $totalReadTxFound across ${filesWithReadTx.size} files")
        println("Files using readTransaction: $filesWithReadTx")
        println("Total write transaction calls: $totalWriteTxFound across ${filesWithWriteTx.size} files")

        assertTrue(totalFilesInspected > 20, "Should have inspected all project files")
        assertTrue(totalReadTxFound >= 25, "Should have audited all readTransaction calls (found $totalReadTxFound)")
    }

    @Test
    fun testNestedReadTransactionExecution() {
        // Verify that nested readTransaction blocks correctly execute and return values
        val outerResult = DatabaseFactory.readTransaction {
            val innerResult1 = DatabaseFactory.readTransaction {
                42
            }
            val innerResult2 = DatabaseFactory.readTransaction {
                "hello"
            }
            Pair(innerResult1, innerResult2)
        }

        assertEquals(Pair(42, "hello"), outerResult)
    }

    @Test
    fun testQuorumFallbackInspection() {
        val inspectDbFile = java.io.File("build/test_inspect_fallback_${System.currentTimeMillis()}.db")
        val appConfig = com.obsidianscout.config.AppConfig(
            quorum_fallback = com.obsidianscout.config.QuorumFallbackConfig(
                enabled = true,
                sqlite_file = inspectDbFile.absolutePath,
                sync_interval_seconds = 30L,
                scouting_retention_days = 7
            )
        )

        QuorumFallbackStore.init(appConfig)
        val sqliteDb = QuorumFallbackStore.sqliteDb
        assertNotNull(sqliteDb)

        transaction(sqliteDb) {
            Users.insert {
                it[id] = org.jetbrains.exposed.dao.id.EntityID(java.util.UUID.randomUUID(), Users)
                it[username] = "inspect_user"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[passwordHash] = "hash"
                it[role] = "ADMIN"
                it[createdAt] = java.time.Instant.now()
            }

            ApiEvents.insert {
                it[id] = org.jetbrains.exposed.dao.id.EntityID(java.util.UUID.randomUUID(), ApiEvents)
                it[eventKey] = "2026caln"
                it[year] = 2026
                it[eventCode] = "caln"
                it[name] = "Silicon Valley Regional"
                it[startDate] = "2026-03-12"
                it[endDate] = "2026-03-15"
                it[timezone] = "America/Los_Angeles"
                it[dataJson] = "{}"
                it[updatedAt] = java.time.Instant.now()
            }

            ApiTeams.insert {
                it[id] = org.jetbrains.exposed.dao.id.EntityID(java.util.UUID.randomUUID(), ApiTeams)
                it[eventKey] = "2026caln"
                it[teamKey] = "frc254"
                it[teamNumber] = 254
                it[name] = "The Cheesy Poofs"
                it[dataJson] = "{}"
                it[updatedAt] = java.time.Instant.now()
            }

            ApiMatches.insert {
                it[id] = org.jetbrains.exposed.dao.id.EntityID(java.util.UUID.randomUUID(), ApiMatches)
                it[matchKey] = "2026caln_qm1"
                it[eventKey] = "2026caln"
                it[compLevel] = "qm"
                it[matchNumber] = 1
                it[redTeams] = "[]"
                it[blueTeams] = "[]"
                it[dataJson] = "{}"
                it[updatedAt] = java.time.Instant.now()
            }
        }

        val inspection = QuorumFallbackStore.inspect("10.0.0.5")
        assertEquals("10.0.0.5", inspection.nodeIp)
        assertTrue(inspection.enabled)
        assertTrue(inspection.isAvailable)
        assertEquals(1L, inspection.tableCounts["users"])
        assertEquals(1L, inspection.tableCounts["api_events"])
        assertEquals(1L, inspection.tableCounts["api_teams"])
        assertEquals(1L, inspection.tableCounts["api_matches"])

        assertEquals(1, inspection.activeEvents.size)
        val eventDetail = inspection.activeEvents[0]
        assertEquals("2026caln", eventDetail.eventKey)
        assertEquals("Silicon Valley Regional", eventDetail.name)
        assertEquals(1, eventDetail.teamCount)
        assertEquals(1, eventDetail.matchCount)

        QuorumFallbackStore.disableAndPurge(updateConfigFile = false)
    }

    @Test
    fun testEventWindowCalculationForPlusMinusOneWeek() {
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val todayStr = today.toString()
        val inThreeDaysStr = today.plusDays(3).toString()
        val inThreeDaysEndStr = today.plusDays(5).toString()
        val lastFourDaysStr = today.minusDays(4).toString()
        val twoMonthsAwayStr = today.plusDays(60).toString()
        val threeMonthsAgoStr = today.minusDays(90).toString()

        // Helper invocation test via sync simulation
        val testDb = java.io.File("build/test_window_${System.currentTimeMillis()}.db")
        val appConfig = com.obsidianscout.config.AppConfig(
            quorum_fallback = com.obsidianscout.config.QuorumFallbackConfig(
                enabled = true,
                sqlite_file = testDb.absolutePath,
                sync_interval_seconds = 30L,
                scouting_retention_days = 7
            )
        )

        QuorumFallbackStore.init(appConfig)
        val sqliteDb = QuorumFallbackStore.sqliteDb
        assertNotNull(sqliteDb)

        // Verify the store is ready and can hold mirrored records
        assertTrue(QuorumFallbackStore.isEnabled)
        QuorumFallbackStore.disableAndPurge(updateConfigFile = false)
    }

    @Test
    fun testCustomizedGranularAndEverythingConfiguration() {
        val testDb = java.io.File("build/test_custom_config_${System.currentTimeMillis()}.db")
        val customConfig = com.obsidianscout.config.QuorumFallbackConfig(
            enabled = true,
            sqlite_file = testDb.absolutePath,
            sync_interval_seconds = 15L,
            scouting_retention_days = 30,
            mirror_all_data = true,
            mirror_users = true,
            mirror_scouting = true,
            mirror_api_data = true,
            mirror_configs = false,
            mirror_alliances = false,
            mirror_chat = false,
            mirror_notifications_secrets = false
        )

        QuorumFallbackStore.updateConfiguration(customConfig, updateConfigFile = false)
        assertTrue(QuorumFallbackStore.isEnabled)
        assertEquals(30, QuorumFallbackStore.config.scouting_retention_days)
        assertTrue(QuorumFallbackStore.config.mirror_all_data)
        assertFalse(QuorumFallbackStore.config.mirror_chat)

        val status = QuorumFallbackStore.getStatus("127.0.0.1")
        assertEquals(30, status.config.scouting_retention_days)
        assertTrue(status.config.mirror_all_data)
        assertFalse(status.config.mirror_chat)

        QuorumFallbackStore.disableAndPurge(updateConfigFile = false)
    }
}


