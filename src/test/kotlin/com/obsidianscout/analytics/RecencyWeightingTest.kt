package com.obsidianscout.analytics

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.integrations.RecencyBiasSettings
import com.obsidianscout.scouting.ScoutingEntryRecord
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecencyWeightingTest {

    private val event = "2026test"
    private val base = Instant.parse("2026-03-01T12:00:00Z")

    private fun entry(match: Int, score: Double, prescout: Boolean = false, id: String = "e$match-$score") = ScoutingEntryRecord(
        id = id,
        ownerTeamNumber = 254,
        targetTeamNumber = 1,
        eventKey = if (prescout) "2026other" else event,
        matchKey = if (prescout) "2026other_qm$match" else "${event}_qm$match",
        matchNumber = match,
        data = JsonObject(mapOf("score" to kotlinx.serialization.json.JsonPrimitive(score))),
        createdAt = base.plusSeconds(match * 600L - if (prescout) 10_000_000L else 0L).toString(),
        isPrescout = prescout
    )

    private fun score(e: ScoutingEntryRecord) = (e.data["score"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble()

    private fun assertClose(expected: Double, actual: Double?, tolerance: Double = 1e-9) {
        assertTrue(actual != null && abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    private val climbing = listOf(10.0, 10.0, 10.0, 40.0, 40.0).mapIndexed { i, s -> entry(i + 1, s) }

    @Test
    fun disabledIsPlainAverage() {
        assertClose(22.0, RecencyWeighting.weightedAverage(climbing, RecencyBiasSettings()) { score(it) })
        assertFalse(RecencyWeighting.isActive(climbing, RecencyBiasSettings()))
    }

    @Test
    fun belowMinMatchesIsPlainAverage() {
        val settings = RecencyBiasSettings(enabled = true, minMatches = 6)
        assertClose(22.0, RecencyWeighting.weightedAverage(climbing, settings) { score(it) })
    }

    @Test
    fun exponentialFollowsHalfLife() {
        val settings = RecencyBiasSettings(enabled = true, halfLifeMatches = 2.0, minMatches = 1)
        val weights = RecencyWeighting.weights(climbing, settings)
        // Input is oldest -> newest, so ages are 4,3,2,1,0.
        listOf(4, 3, 2, 1, 0).forEachIndexed { i, age -> assertClose(Math.pow(0.5, age / 2.0), weights[i]) }
        val avg = RecencyWeighting.weightedAverage(climbing, settings) { score(it) }!!
        assertTrue(avg > 28.0, "recent 40s should dominate, got $avg")
    }

    @Test
    fun lastNOnlyCountsWindow() {
        val settings = RecencyBiasSettings(enabled = true, mode = RecencyBiasSettings.MODE_LAST_N, windowSize = 2, minMatches = 1)
        assertClose(40.0, RecencyWeighting.weightedAverage(climbing, settings) { score(it) })
    }

    @Test
    fun orderComesFromMatchNotInputOrder() {
        val settings = RecencyBiasSettings(enabled = true, mode = RecencyBiasSettings.MODE_LAST_N, windowSize = 2, minMatches = 1)
        assertClose(40.0, RecencyWeighting.weightedAverage(climbing.shuffled(java.util.Random(7)), settings) { score(it) })
    }

    @Test
    fun duplicateScoutsOfSameMatchShareAge() {
        val entries = climbing + entry(5, 40.0, id = "partner-scout")
        val settings = RecencyBiasSettings(enabled = true, mode = RecencyBiasSettings.MODE_LAST_N, windowSize = 2, minMatches = 1)
        val weights = RecencyWeighting.weights(entries, settings)
        // Matches 4 and 5 are in the window: three entries count, not just the two newest rows.
        assertEquals(3, weights.count { it == 1.0 })
    }

    @Test
    fun prescoutIsOlderAndScaled() {
        val entries = climbing + entry(99, 100.0, prescout = true)
        val settings = RecencyBiasSettings(enabled = true, halfLifeMatches = 1.0, minMatches = 1, prescoutWeight = 0.5)
        val weights = RecencyWeighting.weights(entries, settings)
        // Prescout match 99 is still the oldest (age 5), then halved.
        assertClose(Math.pow(0.5, 5.0) * 0.5, weights.last())
    }

    @Test
    fun legacyManualKeysGroupWithCanonicalKeys() {
        val legacy = entry(5, 40.0).copy(id = "legacy", matchKey = "${event}_qm_s1_m5")
        val settings = RecencyBiasSettings(enabled = true, mode = RecencyBiasSettings.MODE_LAST_N, windowSize = 1, minMatches = 1)
        val weights = RecencyWeighting.weights(climbing + legacy, settings)
        assertEquals(2, weights.count { it == 1.0 })
    }

    @Test
    fun settingsAreClampedAndOldJsonLoadsDefaults() {
        val clamped = RecencyBiasSettings(mode = "bogus", halfLifeMatches = 0.0, windowSize = 500, minMatches = -3, prescoutWeight = 4.0).normalized()
        assertEquals(RecencyBiasSettings.MODE_EXPONENTIAL, clamped.mode)
        assertEquals(1.0, clamped.halfLifeMatches)
        assertEquals(30, clamped.windowSize)
        assertEquals(1, clamped.minMatches)
        assertEquals(1.0, clamped.prescoutWeight)

        val legacy = JsonSupport.json.decodeFromString(ApiSettings.serializer(), """{"year":2026,"eventCode":"test"}""")
        assertEquals(RecencyBiasSettings(), legacy.recencyBias)
    }
}
