package com.obsidianscout.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClusterUptimeTest {

    @Test
    fun testFormatUptime() {
        assertEquals("N/A", ClusterManagementService.formatUptime(null))
        assertEquals("N/A", ClusterManagementService.formatUptime(-5L))
        assertEquals("45s", ClusterManagementService.formatUptime(45L))
        assertEquals("12m 30s", ClusterManagementService.formatUptime(750L))
        assertEquals("2h 15m 10s", ClusterManagementService.formatUptime(8110L))
        assertEquals("3d 5h 20m", ClusterManagementService.formatUptime(278400L))
    }

    @Test
    fun testGetLocalUptimeSeconds() {
        val uptime = ClusterManagementService.getLocalUptimeSeconds()
        assertTrue(uptime >= 0, "Local uptime should be non-negative")
        val formatted = ClusterManagementService.formatUptime(uptime)
        assertNotNull(formatted)
        assertTrue(formatted != "N/A", "Formatted uptime should not be N/A for valid uptime")
    }

    @Test
    fun testClusterStatusResponseContainsUptime() {
        val uptimeSec = 38881L
        val formatted = ClusterManagementService.formatUptime(uptimeSec)
        
        val jsonPayload = buildJsonObject {
            put("status", "online")
            put("dbReady", true)
            put("isDbActive", true)
            put("isQuorumLost", false)
            put("quorumDetails", "")
            put("serverVersion", "0.6.1.1")
            put("executionMode", "Native")
            put("nodeIp", "100.92.132.35")
            put("uptimeSeconds", uptimeSec)
            put("uptimeFormatted", formatted)
        }.toString()

        val jsonElem = Json.parseToJsonElement(jsonPayload).jsonObject
        val parsedUptimeSec = jsonElem["uptimeSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
        val parsedUptimeFmt = jsonElem["uptimeFormatted"]?.jsonPrimitive?.content

        assertEquals(38881L, parsedUptimeSec)
        assertEquals("10h 48m 1s", parsedUptimeFmt)
    }
}
