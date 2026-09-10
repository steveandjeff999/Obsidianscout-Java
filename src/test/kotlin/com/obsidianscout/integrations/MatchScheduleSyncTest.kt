package com.obsidianscout.integrations

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatchScheduleSyncTest {

    @Test
    fun testReadEpochSecondsWithVariousFormats() {
        // Standard UTC ISO-8601
        val jsonUtc = buildJsonObject { put("time", "2024-03-08T14:30:00Z") }
        val epochUtc = jsonUtc.readEpochSeconds("time")
        assertNotNull(epochUtc)
        assertEquals(1709908200L, epochUtc)

        // FIRST API local format without timezone offset (e.g., 2024-03-08T14:30:00)
        val jsonLocal = buildJsonObject { put("startTime", "2024-03-08T14:30:00") }
        val epochLocal = jsonLocal.readEpochSeconds("startTime")
        assertNotNull(epochLocal)
        assertEquals(1709908200L, epochLocal)

        // Space-separated date time (e.g. 2024-03-08 14:30:00)
        val jsonSpace = buildJsonObject { put("actualStartTime", "2024-03-08 14:30:00") }
        val epochSpace = jsonSpace.readEpochSeconds("actualStartTime")
        assertNotNull(epochSpace)
        assertEquals(1709908200L, epochSpace)

        // Epoch millisecond string
        val jsonMillis = buildJsonObject { put("time", "1709908200000") }
        val epochMillis = jsonMillis.readEpochSeconds("time")
        assertNotNull(epochMillis)
        assertEquals(1709908200L, epochMillis)

        // Epoch second string
        val jsonSeconds = buildJsonObject { put("time", "1709908200") }
        val epochSeconds = jsonSeconds.readEpochSeconds("time")
        assertNotNull(epochSeconds)
        assertEquals(1709908200L, epochSeconds)
    }

    @Test
    fun testMergeRecordsPreservesScores() {
        val scoredMatch = MatchSyncRecord(
            matchKey = "2024mndu_qm1",
            eventKey = "2024mndu",
            compLevel = "qm",
            setNumber = 1,
            matchNumber = 1,
            scheduledTime = 1709908200L,
            actualTime = 1709908500L,
            redTeams = listOf("frc254", "frc1323", "frc971"),
            blueTeams = listOf("frc1678", "frc2056", "frc118"),
            dataJson = """{"matchNumber":1,"scoreRedFinal":75,"scoreBlueFinal":80}""",
            source = "first"
        )

        val scheduleMatch = MatchSyncRecord(
            matchKey = "2024mndu_qm1",
            eventKey = "2024mndu",
            compLevel = "qm",
            setNumber = 1,
            matchNumber = 1,
            scheduledTime = 1709908200L,
            actualTime = null,
            redTeams = listOf("frc254", "frc1323", "frc971"),
            blueTeams = listOf("frc1678", "frc2056", "frc118"),
            dataJson = """{"description":"Qualification 1","matchNumber":1,"field":"Primary","startTime":"2024-03-08T14:30:00"}""",
            source = "first"
        )

        // When merging schedule incoming into existing scored match:
        val merged1 = MatchCanonical.mergeRecords(scoredMatch, scheduleMatch)
        assertTrue(merged1.dataJson.contains("scoreRedFinal"), "Should keep scored dataJson when schedule comes incoming")
        assertEquals(1709908500L, merged1.actualTime)

        // When merging scored match incoming into existing schedule:
        val merged2 = MatchCanonical.mergeRecords(scheduleMatch, scoredMatch)
        assertTrue(merged2.dataJson.contains("scoreRedFinal"), "Should keep scored dataJson when scored match comes incoming")
        assertEquals(1709908500L, merged2.actualTime)
    }

    @Test
    fun testGetSourcesLabel() {
        // FRC both keys configured
        val settingsBothKeys = ApiSettings(
            apiKeys = ApiKeys(tbaKey = "secret_tba", firstUsername = "user", firstKey = "secret_first")
        )
        assertEquals("TBA & FIRST Robotics API", SyncScheduler.getSourcesLabel(settingsBothKeys))

        // FRC only FIRST keys configured
        val settingsFirstOnly = ApiSettings(
            apiKeys = ApiKeys(tbaKey = "", firstUsername = "user", firstKey = "secret_first")
        )
        assertEquals("FIRST Robotics API", SyncScheduler.getSourcesLabel(settingsFirstOnly))

        // FRC only TBA key configured
        val settingsTbaOnly = ApiSettings(
            apiKeys = ApiKeys(tbaKey = "secret_tba", firstUsername = "", firstKey = "")
        )
        assertEquals("The Blue Alliance API", SyncScheduler.getSourcesLabel(settingsTbaOnly))

        // FRC no keys, preferredSource = "both"
        val settingsPrefBoth = ApiSettings(
            preferredSource = "both",
            apiKeys = ApiKeys()
        )
        assertEquals("TBA & FIRST Robotics API", SyncScheduler.getSourcesLabel(settingsPrefBoth))

        // FRC no keys, preferredSource = "first"
        val settingsPrefFirst = ApiSettings(
            preferredSource = "first",
            apiKeys = ApiKeys()
        )
        assertEquals("FIRST Robotics API", SyncScheduler.getSourcesLabel(settingsPrefFirst))

        // FTC preferredSource = "both"
        val settingsFtcBoth = ApiSettings(
            program = "FTC",
            preferredSource = "both",
            apiKeys = ApiKeys()
        )
        assertEquals("FTC Scout & FIRST FTC API", SyncScheduler.getSourcesLabel(settingsFtcBoth))
    }
}
