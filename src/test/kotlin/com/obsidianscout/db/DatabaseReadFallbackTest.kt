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
    fun testAsOfSystemTimeCandidatesLadderStructure() {
        val candidates = DatabaseFactory.buildAsOfSystemTimeCandidates()
        assertTrue(candidates.contains("follower_read_timestamp()"))
        assertTrue(candidates.contains("with_max_staleness(INTERVAL '10m')"))
        assertTrue(candidates.contains("'-24h'"))
    }

    @Test
    fun testBuildAsOfSystemTimeCandidatesWithAnchoredTimestamp() {
        val now = java.time.Instant.parse("2026-08-29T12:00:00Z")
        DatabaseFactory.saveLastHealthyTimestamp(now)

        val candidates = DatabaseFactory.buildAsOfSystemTimeCandidates()
        assertTrue(candidates.isNotEmpty())

        // First candidates should prioritize anchored fixed timestamps safe for local Pebble
        val firstCandidate = candidates.first()
        assertTrue(firstCandidate.startsWith("'2026-08-29 "), "First candidate should be anchored timestamp: $firstCandidate")

        // Check that relative intervals and follower reads are also included
        assertTrue(candidates.contains("'-5m'"))
        assertTrue(candidates.contains("with_max_staleness(INTERVAL '10m')"))
        assertTrue(candidates.contains("follower_read_timestamp()"))
    }

    @Test
    fun testWorkingCandidateCachingAndClearing() {
        DatabaseFactory.cachedWorkingAsOfSystemTime = "'2026-08-29 12:00:00.000000+00'"
        assertEquals("'2026-08-29 12:00:00.000000+00'", DatabaseFactory.cachedWorkingAsOfSystemTime)

        // Reset
        DatabaseFactory.cachedWorkingAsOfSystemTime = null
        assertEquals(null, DatabaseFactory.cachedWorkingAsOfSystemTime)
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
}


