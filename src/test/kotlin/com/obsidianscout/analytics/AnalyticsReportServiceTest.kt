package com.obsidianscout.analytics

import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.ScoutingField
import com.obsidianscout.config.ScoutingOption
import com.obsidianscout.scouting.ScoutingEntryRecord
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsReportServiceTest {

    // Mirrors the 2026 "Rebuilt" layout: climbs are select fields scored via option points.
    private val config = ScoutingConfig(
        version = 1,
        title = "Rebuilt",
        fields = listOf(
            ScoutingField(id = "auto_scored", label = "Auto Fuel", type = "counter", phase = "auto", pointsPer = 1.0),
            ScoutingField(
                id = "auto_climb", label = "Auto Climb", type = "select", phase = "auto",
                options = listOf(ScoutingOption("No Climb", "n", 0.0), ScoutingOption("L1", "u", 15.0))
            ),
            ScoutingField(id = "teleop_fuel", label = "Teleop Fuel", type = "counter", phase = "teleop", pointsPer = 1.0),
            ScoutingField(
                id = "endgame_climb", label = "Climb", type = "select", phase = "endgame",
                options = listOf(ScoutingOption("None", "n", 0.0), ScoutingOption("L3", "l3", 30.0))
            )
        ),
        analytics = emptyList()
    )

    private val entry = ScoutingEntryRecord(
        id = "e1",
        ownerTeamNumber = 1,
        targetTeamNumber = 2,
        eventKey = "2026test",
        matchKey = "2026test_qm1",
        matchNumber = 1,
        data = buildJsonObject {
            put("auto_scored", 4)
            put("auto_climb", "u")
            put("teleop_fuel", 20)
            put("endgame_climb", "l3")
        },
        createdAt = Instant.now().toString()
    )

    @Test
    fun phaseScoresIncludeSelectOptionPointsAndSumToTotal() {
        val auto = AnalyticsReportService.scoreSection(config, entry, "auto")
        val teleop = AnalyticsReportService.scoreSection(config, entry, "teleop")
        val endgame = AnalyticsReportService.scoreSection(config, entry, "endgame", "climb")

        assertEquals(19.0, auto)
        assertEquals(20.0, teleop)
        // endgame_climb matches both "endgame" and "climb" but must only be counted once
        assertEquals(30.0, endgame)
        assertEquals(AnalyticsService.scoreEntry(config, entry), auto + teleop + endgame)
    }
}
