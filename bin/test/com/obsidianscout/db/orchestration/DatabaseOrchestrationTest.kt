package com.obsidianscout.db.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseOrchestrationTest {

    @Test
    fun testParseGoogleSheetsCsv() {
        val csvData = """
            ip,port
            100.64.0.1,26257
            100.64.0.2,26258
        """.trimIndent()

        val peers = GoogleSheetsManager.parseResponse(csvData, "https://docs.google.com/spreadsheets/d/abc/pub?output=csv")
        assertEquals(2, peers.size)
        assertEquals("100.64.0.1", peers[0].first)
        assertEquals(26257, peers[0].second)
        assertEquals("100.64.0.2", peers[1].first)
        assertEquals(26258, peers[1].second)
    }

    @Test
    fun testParseGoogleSheetsCsvNoHeader() {
        val csvData = """
            100.64.0.5,26259
        """.trimIndent()

        val peers = GoogleSheetsManager.parseResponse(csvData, "https://docs.google.com/spreadsheets/d/abc/pub?output=csv")
        assertEquals(1, peers.size)
        assertEquals("100.64.0.5", peers[0].first)
        assertEquals(26259, peers[0].second)
    }

    @Test
    fun testParseGoogleSheetsJson() {
        val jsonData = """
            [
                {"ip": "100.64.0.10", "port": 26260},
                {"ip": "100.64.0.11", "port": 26261}
            ]
        """.trimIndent()

        val peers = GoogleSheetsManager.parseResponse(jsonData, "https://script.google.com/macros/s/123/exec")
        assertEquals(2, peers.size)
        assertEquals("100.64.0.10", peers[0].first)
        assertEquals(26260, peers[0].second)
        assertEquals("100.64.0.11", peers[1].first)
        assertEquals(26261, peers[1].second)
    }

    @Test
    fun testParseGoogleSheetsJsonMalformed() {
        val jsonData = """
            [
                {"ip": "100.64.0.12", "port": 26262}
        """.trimIndent() // missing closing bracket

        val peers = GoogleSheetsManager.parseResponse(jsonData, "https://script.google.com/macros/s/123/exec")
        // Should fallback to regex and parse successfully
        assertEquals(1, peers.size)
        assertEquals("100.64.0.12", peers[0].first)
        assertEquals(26262, peers[0].second)
    }

    @Test
    fun testUrlNormalization() {
        val originalUrl = "https://docs.google.com/spreadsheets/d/1utbduIlY5h5T0sucyn5P1O1HX60SVmBN2zgXu_fIxTk/edit?usp=sharing"
        val expectedUrl = "https://docs.google.com/spreadsheets/d/1utbduIlY5h5T0sucyn5P1O1HX60SVmBN2zgXu_fIxTk/export?format=csv"
        assertEquals(expectedUrl, GoogleSheetsManager.normalizeUrl(originalUrl))

        val directCsvUrl = "https://docs.google.com/spreadsheets/d/abc/export?format=csv"
        assertEquals(directCsvUrl, GoogleSheetsManager.normalizeUrl(directCsvUrl))

        val scriptUrl = "https://script.google.com/macros/s/123/exec"
        assertEquals(scriptUrl, GoogleSheetsManager.normalizeUrl(scriptUrl))
    }

    @Test
    fun testCheckForFatalDiskErrorIgnoresTransientDiskSlowness() {
        val appConfig = com.obsidianscout.config.AppConfig()
        val orchestrator = CockroachOrchestrator(appConfig)

        val transientLogs = listOf(
            "Jul 28, 2026 at 12:10:00 UTC INFO [n7,s7,pebble] disk slowness detected: syncdata on file /home/steve/obsidianscout/.cockroach/data/000003.log has been ongoing for 5.2s",
            "Jul 28, 2026 at 12:10:00 UTC WARNING [n7] {\"Timestamp\":1785240600589051260,\"EventType\":\"disk_slowness_detected\",\"NodeID\":7,\"StoreID\":7}",
            "Jul 28, 2026 at 12:10:21 UTC INFO [n7] {\"Timestamp\":1785240621184378538,\"EventType\":\"disk_slowness_cleared\",\"NodeID\":7,\"StoreID\":7}",
            "Jul 28, 2026 at 12:10:27 UTC WARNING [n7,liveness-hb] slow heartbeat took 3.000868019s; err=disk write failed while updating node liveness: interrupted during singleflight engine sync:0: context deadline exceeded"
        )
        kotlin.test.assertFalse(orchestrator.checkForFatalDiskError(transientLogs), "Transient disk slowness logs must NOT trigger fatal disk error")

        val fatalLogs = listOf(
            "F260728 12:00:00.000000 1 storage/pebble: fatal faulty hardware disk failure terminating due to a fatal error"
        )
        kotlin.test.assertTrue(orchestrator.checkForFatalDiskError(fatalLogs), "Fatal hardware disk failure logs MUST be detected as fatal")
    }
}

