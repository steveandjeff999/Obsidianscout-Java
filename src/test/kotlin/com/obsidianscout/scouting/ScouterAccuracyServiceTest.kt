package com.obsidianscout.scouting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScouterAccuracyServiceTest {

    @Test
    fun officialScoresRemoveTbaFoulPoints() {
        val json = """{"alliances":{"red":{"score":120},"blue":{"score":95}},
            "score_breakdown":{"red":{"foulPoints":10},"blue":{"foulPoints":0}}}"""
        assertEquals(110.0 to 95.0, ScouterAccuracyService.officialScores(json))
    }

    @Test
    fun officialScoresTreatUnplayedTbaMatchesAsUnknown() {
        val json = """{"alliances":{"red":{"score":-1},"blue":{"score":-1}}}"""
        assertEquals(null to null, ScouterAccuracyService.officialScores(json))
    }

    @Test
    fun officialScoresReadFirstApiFinalsMinusFouls() {
        val json = """{"scoreRedFinal":80,"scoreBlueFinal":70,"scoreRedFoul":5}"""
        assertEquals(75.0 to 70.0, ScouterAccuracyService.officialScores(json))
    }

    @Test
    fun officialScoresReadFtcTotals() {
        val json = """{"scores":{"red":{"totalPoints":44},"blue":{"totalPoints":51}}}"""
        assertEquals(44.0 to 51.0, ScouterAccuracyService.officialScores(json))
    }

    @Test
    fun officialScoresHandleEmptyOrBadJson() {
        assertEquals(null to null, ScouterAccuracyService.officialScores(""))
        assertEquals(null to null, ScouterAccuracyService.officialScores("{}"))
        assertEquals(null to null, ScouterAccuracyService.officialScores("not json"))
    }

    @Test
    fun summarizeReportsAccuracyErrorAndBias() {
        // Over by 10 on a 100-point alliance, under by 5 on a 50-point one.
        val a = ScouterAccuracyService.summarize(listOf(10.0 to 100.0, -5.0 to 50.0))
        assertEquals(2, a.alliances)
        assertEquals(7.5, a.avgAbsError)   // (10 + 5) / 2
        assertEquals(2.5, a.avgBias)       // (10 - 5) / 2
        assertEquals(90.0, a.accuracyPct)  // 100 - mean(10%, 10%)
    }

    @Test
    fun summarizeClampsAccuracyAtZero() {
        val a = ScouterAccuracyService.summarize(listOf(300.0 to 100.0))
        assertEquals(0.0, a.accuracyPct)
    }

    @Test
    fun summarizeOfNothingIsBlank() {
        val a = ScouterAccuracyService.summarize(emptyList())
        assertEquals(0, a.alliances)
        assertNull(a.accuracyPct)
    }
}
