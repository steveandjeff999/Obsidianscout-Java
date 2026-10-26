package com.obsidianscout.analytics

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Match13CompareTest {

    private fun match(key: String, teams: Map<Int, Map<String, Double>>): JsonElement = buildJsonObject {
        put("key", key)
        putJsonObject("teams") {
            teams.forEach { (team, values) ->
                putJsonObject("$team") { values.forEach { (k, v) -> put(k, v) } }
            }
        }
    }

    // Out of order on purpose: qm10 must count as later than qm2.
    private val history = listOf(
        match("2026test_qm10", mapOf(254 to mapOf("xpPre" to 40.0, "xpPost" to 55.0, "xAutoPost" to 12.0, "xTelePost" to 30.0, "xEndPost" to 13.0))),
        match("2026test_qm2", mapOf(
            254 to mapOf("xpPre" to 30.0, "xpPost" to 60.0),
            1678 to mapOf("xpPre" to 50.0, "xpPost" to 45.0),
        )),
    )

    @Test
    fun `current xP comes from the latest match and the change from the first pre-match value`() {
        val result = Match13Compare.build(listOf(254, 1678), history)
        val t254 = result.teams.getValue("254").metrics
        assertEquals(55.0, t254["xp_current"])
        assertEquals(30.0, t254["xp_start"])
        assertEquals(25.0, t254["xp_change"])
        assertEquals(60.0, t254["xp_peak"])
        assertEquals(12.0, t254["xp_auto"])
        assertEquals(30.0, t254["xp_teleop"])
        assertEquals(13.0, t254["xp_endgame"])
        assertEquals(2.0, t254["matches_scouted"])

        val t1678 = result.teams.getValue("1678").metrics
        assertEquals(-5.0, t1678["xp_change"])
        assertEquals(1, result.teams.getValue("1678").matchesScouted)
        assertEquals("match13", result.source)
        assertNull(result.note)
        assertTrue(result.metricDefinitions.any { it.id == "xp_current" })
    }

    @Test
    fun `teams given as a list are found by team key`() {
        val listHistory = listOf<JsonElement>(buildJsonObject {
            put("key", "2026test_qm1")
            put("teams", buildJsonArray {
                add(buildJsonObject { put("teamKey", "frc971"); put("xp", 42.0) })
            })
        })
        val result = Match13Compare.build(listOf(971), listHistory)
        assertEquals(42.0, result.teams.getValue("971").metrics["xp_current"])
    }

    @Test
    fun `no history gives no metrics and a note`() {
        val result = Match13Compare.build(listOf(254, 1678), emptyList(), storedExp = mapOf(254 to 48.0))
        assertTrue(result.metricDefinitions.isEmpty())
        assertEquals(48.0, result.teams.getValue("254").exp)
        assertTrue(result.note!!.contains("No Match 13"))
    }
}
