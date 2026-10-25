package com.obsidianscout.scouting

import com.obsidianscout.scouting.MatchPredictionService.LeaderboardUser
import com.obsidianscout.scouting.MatchPredictionService.MatchInfo
import com.obsidianscout.scouting.MatchPredictionService.Pick
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchPredictionServiceTest {

    @Test
    fun `reads the winner from each API's match JSON`() {
        assertEquals("red", MatchPredictionService.matchResult("""{"alliances":{"red":{"score":90},"blue":{"score":70}}}"""))
        assertEquals("blue", MatchPredictionService.matchResult("""{"scoreRedFinal":40,"scoreBlueFinal":41}"""))
        assertEquals("tie", MatchPredictionService.matchResult("""{"redScore":50,"blueScore":50}"""))
        // TBA's winning_alliance settles playoff tiebreakers on equal scores.
        assertEquals("blue", MatchPredictionService.matchResult(
            """{"winning_alliance":"blue","alliances":{"red":{"score":60},"blue":{"score":60}}}"""
        ))
        // TBA sends -1 until the match is played.
        assertNull(MatchPredictionService.matchResult("""{"alliances":{"red":{"score":-1},"blue":{"score":-1}}}"""))
        assertNull(MatchPredictionService.matchResult("""{}"""))
        assertNull(MatchPredictionService.matchResult("not json"))
    }

    private val users = listOf(
        LeaderboardUser("a", "alice", "SCOUT"),
        LeaderboardUser("b", "bob", "SCOUT"),
        LeaderboardUser("c", "carol", "ADMIN"),
        LeaderboardUser("d", "dave", "SCOUT")
    )

    private fun match(key: String, order: Long, result: String?) =
        key to MatchInfo(key, key.substringBefore('_'), order, result)

    private val matches = mapOf(
        match("2026abc_qm1", 1, "red"),
        match("2026abc_qm2", 2, "blue"),
        match("2026abc_qm3", 3, "red"),
        match("2026abc_qm4", 4, "tie"),
        match("2026abc_qm5", 5, null),
        match("2026xyz_qm1", 10, "blue")
    )

    private fun pick(user: String, match: String, winner: String) =
        Pick(user, match.substringBefore('_'), match, winner, Instant.parse("2026-03-10T15:00:00Z"))

    private val picks = listOf(
        // alice: right, right, wrong, tie, pending -> 2/3
        pick("a", "2026abc_qm1", "red"),
        pick("a", "2026abc_qm2", "blue"),
        pick("a", "2026abc_qm3", "blue"),
        pick("a", "2026abc_qm4", "red"),
        pick("a", "2026abc_qm5", "red"),
        // bob: wrong, right, right -> 2/3, streak of 2 at the end
        pick("b", "2026abc_qm1", "blue"),
        pick("b", "2026abc_qm2", "blue"),
        pick("b", "2026abc_qm3", "red"),
        // carol: 1/1
        pick("c", "2026abc_qm1", "red"),
        // dave: only a pending pick
        pick("d", "2026abc_qm5", "blue"),
        // alice at another event
        pick("a", "2026xyz_qm1", "blue"),
        // someone no longer known
        pick("zz", "2026abc_qm1", "red")
    )

    @Test
    fun `ranks one event by accuracy then correct picks`() {
        val board = MatchPredictionService.aggregate("2026abc", null, users, picks, matches, mapOf("2026abc" to "ABC Regional"))
        assertEquals("event", board.scope)
        assertEquals("ABC Regional", board.eventName)
        assertEquals(2026, board.year)
        assertEquals(listOf("carol", "alice", "bob", "dave"), board.rows.map { it.username })

        val carol = board.rows[0]
        assertEquals(1, carol.rank)
        assertEquals(100.0, carol.accuracyPct)

        val alice = board.rows[1]
        val bob = board.rows[2]
        // Same accuracy and correct count share a place.
        assertEquals(2, alice.rank)
        assertEquals(2, bob.rank)
        assertEquals(5, alice.predictions)
        assertEquals(3, alice.scored)
        assertEquals(2, alice.correct)
        assertEquals(1, alice.ties)
        assertEquals(1, alice.pending)
        assertEquals(66.7, alice.accuracyPct)
        assertEquals(0, alice.currentStreak)
        assertEquals(2, alice.bestStreak)
        assertEquals(2, bob.currentStreak)

        val dave = board.rows[3]
        assertNull(dave.rank)
        assertNull(dave.accuracyPct)
        assertEquals(1, dave.pending)

        // qm1-qm3 had a winner; the tie and the unplayed match don't count.
        assertEquals(3, board.matchesScored)
    }

    @Test
    fun `season scope covers every event that year`() {
        val board = MatchPredictionService.aggregate(null, 2026, users, picks, matches, emptyMap())
        assertEquals("year", board.scope)
        val alice = board.rows.first { it.username == "alice" }
        assertEquals(6, alice.predictions)
        assertEquals(3, alice.correct)
        assertEquals(4, alice.scored)
        assertEquals(75.0, alice.accuracyPct)
        assertEquals(2, alice.events)
        assertEquals(1, alice.currentStreak)

        val other = MatchPredictionService.aggregate(null, 2025, users, picks, matches, emptyMap())
        assertEquals(emptyList(), other.rows)
    }
}
