package com.obsidianscout.analytics

import com.obsidianscout.config.AnalyticsWidget
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.ScoutingField
import com.obsidianscout.config.ScoutingOption
import com.obsidianscout.scouting.ScoutingEntryRecord
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsServiceTest {

    private val testConfig = ScoutingConfig(
        version = 1,
        title = "Test Config",
        fields = listOf(
            ScoutingField(
                id = "sec_auto",
                label = "Autonomous",
                type = "section"
            ),
            ScoutingField(
                id = "auto_scored",
                label = "Auto Scored",
                type = "counter",
                pointsPer = 2.0
            ),
            ScoutingField(
                id = "climb_success",
                label = "Climbed",
                type = "checkbox",
                pointsPer = 10.0
            ),
            ScoutingField(
                id = "endgame_result",
                label = "Endgame Result",
                type = "select",
                options = listOf(
                    ScoutingOption("None", "none", 0.0),
                    ScoutingOption("Park", "park", 5.0),
                    ScoutingOption("Deep", "deep", 15.0)
                )
            ),
            ScoutingField(
                id = "driver_rating",
                label = "Driver Rating",
                type = "rating",
                min = 1,
                max = 5
            )
        ),
        analytics = emptyList()
    )

    private val sampleEntries = listOf(
        ScoutingEntryRecord(
            id = "entry-1",
            ownerTeamNumber = 254,
            targetTeamNumber = 1678,
            eventKey = "2026test",
            matchKey = "qm1",
            matchNumber = 1,
            data = buildJsonObject {
                put("auto_scored", 5)
                put("climb_success", true)
                put("endgame_result", "park")
                put("driver_rating", 4)
            },
            createdAt = Instant.now().toString()
        ),
        ScoutingEntryRecord(
            id = "entry-2",
            ownerTeamNumber = 254,
            targetTeamNumber = 1678,
            eventKey = "2026test",
            matchKey = "qm2",
            matchNumber = 2,
            data = buildJsonObject {
                put("auto_scored", 3)
                put("climb_success", false)
                put("endgame_result", "deep")
                put("driver_rating", 5)
            },
            createdAt = Instant.now().toString()
        )
    )

    @Test
    fun testGenerateDefaultWidgetsWhenAnalyticsIsEmpty() {
        val response = AnalyticsService.generate(testConfig, sampleEntries)
        assertTrue(response.widgets.isNotEmpty(), "Default widgets should be generated when config.analytics is empty")

        val countWidget = response.widgets.firstOrNull { it.id == "entryCount" }
        assertEquals(2.0, countWidget?.value)

        val autoAvgWidget = response.widgets.firstOrNull { it.id == "avg_auto_scored" }
        assertEquals(4.0, autoAvgWidget?.value)

        val autoSumWidget = response.widgets.firstOrNull { it.id == "sum_auto_scored" }
        assertEquals(8.0, autoSumWidget?.value)

        val climbRateWidget = response.widgets.firstOrNull { it.id == "rate_climb_success" }
        assertEquals(50.0, climbRateWidget?.value)

        val endgameBarWidget = response.widgets.firstOrNull { it.id == "bar_endgame_result" }
        assertEquals("bar", endgameBarWidget?.type)
        assertEquals(3, endgameBarWidget?.series?.size)
    }

    @Test
    fun testGenerateExplicitAnalyticsWidgets() {
        val configWithWidgets = testConfig.copy(
            analytics = listOf(
                AnalyticsWidget(id = "w1", title = "Total Entries", type = "count"),
                AnalyticsWidget(id = "w2", title = "Min Auto", type = "min", fieldId = "auto_scored"),
                AnalyticsWidget(id = "w3", title = "Max Auto", type = "max", fieldId = "auto_scored"),
                AnalyticsWidget(id = "w4", title = "Avg Rating", type = "avg", fieldId = "driver_rating")
            )
        )

        val response = AnalyticsService.generate(configWithWidgets, sampleEntries)
        assertEquals(4, response.widgets.size)

        assertEquals(2.0, response.widgets.find { it.id == "w1" }?.value)
        assertEquals(3.0, response.widgets.find { it.id == "w2" }?.value)
        assertEquals(5.0, response.widgets.find { it.id == "w3" }?.value)
        assertEquals(4.5, response.widgets.find { it.id == "w4" }?.value)
    }

    @Test
    fun testScoreCalculation() {
        // Entry 1 score: auto_scored: 5 * 2.0 = 10, climb_success: true (10.0) = 10, endgame: park (5.0) = 5 -> Total 25.0
        // Entry 2 score: auto_scored: 3 * 2.0 = 6, climb_success: false = 0, endgame: deep (15.0) = 15 -> Total 21.0
        assertEquals(25.0, AnalyticsService.scoreEntry(testConfig, sampleEntries[0]))
        assertEquals(21.0, AnalyticsService.scoreEntry(testConfig, sampleEntries[1]))

        val response = AnalyticsService.generate(testConfig, sampleEntries)
        val avgScoreWidget = response.widgets.firstOrNull { it.id == "avgScore" }
        assertEquals(23.0, avgScoreWidget?.value)

        val totalScoreWidget = response.widgets.firstOrNull { it.id == "totalScore" }
        assertEquals(46.0, totalScoreWidget?.value)
    }
}
