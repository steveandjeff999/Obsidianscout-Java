package com.obsidianscout.analytics

import com.obsidianscout.analytics.RankingProjectionService.SimInput
import com.obsidianscout.analytics.RankingProjectionService.SimMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankingProjectionServiceTest {

    private fun input(
        matches: List<SimMatch>,
        strengths: Map<Int, Double> = emptyMap(),
        teams: List<Int> = matches.flatMap { it.red + it.blue }.distinct(),
        sims: Int = 500
    ) = SimInput(
        teams = teams,
        matches = matches,
        strengths = strengths,
        defaultWinRp = 3,
        simulations = sims,
        seed = 42L,
        captainSlots = 2
    )

    @Test
    fun `fully played schedule reproduces current standings with certainty`() {
        val matches = listOf(
            SimMatch(red = listOf(1, 2), blue = listOf(3, 4), redScore = 50, blueScore = 20),
            SimMatch(red = listOf(1, 3), blue = listOf(2, 4), redScore = 40, blueScore = 30),
            SimMatch(red = listOf(1, 4), blue = listOf(2, 3), redScore = 60, blueScore = 10)
        )
        val result = RankingProjectionService.simulate(input(matches))

        assertEquals("win-loss", result.rpSource)
        assertEquals(3, result.playedCount)
        val team1 = result.teams.first { it.teamNumber == 1 }
        assertEquals(1, team1.currentRank)
        assertEquals(9.0, team1.currentRp)
        assertEquals(3, team1.wins)
        assertEquals(1.0, team1.probFirst)
        assertEquals(1, team1.medianRank)
        assertEquals(0, team1.matchesRemaining)
        // Projected ranks match current ranks when nothing is left to play
        result.teams.forEach { assertEquals(it.currentRank, it.medianRank) }
    }

    @Test
    fun `stronger teams are projected higher for unplayed matches`() {
        val matches = (1..12).map { i ->
            if (i % 2 == 0) SimMatch(red = listOf(1, 3), blue = listOf(2, 4))
            else SimMatch(red = listOf(1, 4), blue = listOf(2, 3))
        }
        val strengths = mapOf(1 to 60.0, 2 to 10.0, 3 to 30.0, 4 to 30.0)
        val result = RankingProjectionService.simulate(input(matches, strengths))

        val team1 = result.teams.first { it.teamNumber == 1 }
        val team2 = result.teams.first { it.teamNumber == 2 }
        assertNull(team1.currentRank)
        assertEquals(12, team1.matchesRemaining)
        assertTrue(team1.projectedRank < team2.projectedRank)
        assertTrue(team1.probFirst > 0.5, "probFirst=${team1.probFirst}")
        assertTrue(team2.probTopSeeds < 0.5, "probTopSeeds=${team2.probTopSeeds}")
    }

    @Test
    fun `official rp is used and win rp is inferred from it`() {
        val matches = listOf(
            // Winners earned 2 RP with no bonus, so a win is worth 2
            SimMatch(red = listOf(1, 2), blue = listOf(3, 4), redScore = 50, blueScore = 20, redRp = 2, blueRp = 1),
            SimMatch(red = listOf(1, 3), blue = listOf(2, 4), redScore = 40, blueScore = 30, redRp = 4, blueRp = 0)
        )
        val result = RankingProjectionService.simulate(input(matches))

        assertEquals("official", result.rpSource)
        assertEquals(2, result.winRp)
        assertEquals(6.0, result.teams.first { it.teamNumber == 1 }.currentRp)
        assertEquals(1.0, result.teams.first { it.teamNumber == 4 }.currentRp)
    }

    @Test
    fun `unplayed tba matches with negative scores count as remaining`() {
        val matches = listOf(
            SimMatch(red = listOf(1, 2), blue = listOf(3, 4), redScore = -1, blueScore = -1)
        )
        val result = RankingProjectionService.simulate(input(matches))
        assertEquals(0, result.playedCount)
        assertTrue(result.teams.all { it.matchesRemaining == 1 && it.matchesPlayed == 0 })
    }

    @Test
    fun `rank probabilities sum to one across the event`() {
        val matches = (1..6).map { SimMatch(red = listOf(1, 2), blue = listOf(3, 4)) }
        val result = RankingProjectionService.simulate(input(matches, sims = 300))
        val firstTotal = result.teams.sumOf { it.probFirst }
        assertEquals(1.0, firstTotal, 1e-9)
    }

    @Test
    fun `event opr recovers team contributions from played scores`() {
        val truth = mapOf(1 to 40.0, 2 to 20.0, 3 to 10.0, 4 to 30.0, 5 to 5.0, 6 to 25.0)
        val teams = truth.keys.toList()
        val rnd = java.util.Random(3)
        val played = (1..60).map {
            val shuffled = teams.shuffled(rnd)
            val red = shuffled.take(3)
            val blue = shuffled.drop(3)
            SimMatch(red = red, blue = blue, redScore = red.sumOf { truth[it]!! }.toInt(), blueScore = blue.sumOf { truth[it]!! }.toInt())
        }
        val opr = RankingProjectionService.eventOpr(teams, teams.withIndex().associate { (i, t) -> t to i }, played)
        truth.forEach { (team, value) -> assertEquals(value, opr[team]!!, 2.0) }
    }

    @Test
    fun `event opr is used when no strengths are supplied`() {
        val played = (1..6).map { SimMatch(red = listOf(1, 2), blue = listOf(3, 4), redScore = 80, blueScore = 20) }
        val upcoming = (1..4).map { SimMatch(red = listOf(1, 3), blue = listOf(2, 4)) }
        val result = RankingProjectionService.simulate(input(played + upcoming))
        assertTrue(result.usedEventOpr)
        assertEquals(setOf(1, 2), result.teams.take(2).map { it.teamNumber }.toSet())
    }

    @Test
    fun `the same seed repeats and a new seed draws different simulations`() {
        val matches = (1..8).map { i ->
            if (i % 2 == 0) SimMatch(red = listOf(1, 2), blue = listOf(3, 4)) else SimMatch(red = listOf(1, 3), blue = listOf(2, 4))
        }
        val strengths = mapOf(1 to 30.0, 2 to 28.0, 3 to 26.0, 4 to 24.0)
        val a = RankingProjectionService.simulate(input(matches, strengths))
        val b = RankingProjectionService.simulate(input(matches, strengths))
        val c = RankingProjectionService.simulate(input(matches, strengths).copy(seed = 99L))
        assertEquals(a.teams.map { it.probFirst }, b.teams.map { it.probFirst })
        assertTrue(a.teams.map { it.probFirst } != c.teams.map { it.probFirst })
    }

    @Test
    fun `close teams with matches left are never shown as certain`() {
        val played = (1..4).map { SimMatch(red = listOf(1, 2), blue = listOf(3, 4), redScore = 60, blueScore = 55) }
        val upcoming = (1..6).map { SimMatch(red = listOf(1, 3), blue = listOf(2, 4)) }
        val result = RankingProjectionService.simulate(input(played + upcoming, mapOf(1 to 31.0, 2 to 29.0, 3 to 28.0, 4 to 27.0)))
        assertTrue(result.teams.all { it.probFirst < 1.0 })
    }

    @Test
    fun `team numbers are read from plain, prefixed and bbot keys`() {
        assertEquals(254, RankingProjectionService.teamNumberFromKey("frc254"))
        assertEquals(254, RankingProjectionService.teamNumberFromKey("254"))
        assertEquals(12345, RankingProjectionService.teamNumberFromKey("ftc12345"))
        assertEquals(9254, RankingProjectionService.teamNumberFromKey("frc254b/9254"))
        assertNull(RankingProjectionService.teamNumberFromKey("frc254b"))
    }
}
