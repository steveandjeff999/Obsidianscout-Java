package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How rows settle when teams with different keys sync the same event, how scores are read from each
 * API's JSON, and how one failing source is reported without sinking the others.
 */
class SyncMergeTest {

    private val tbaUnplayed = """{"key":"2026cttd_qm1","comp_level":"qm","alliances":{"red":{"score":-1,"team_keys":["frc1","frc2","frc3"]},"blue":{"score":-1,"team_keys":["frc4","frc5","frc6"]}}}"""
    private val tbaPlayed = """{"key":"2026cttd_qm1","comp_level":"qm","alliances":{"red":{"score":88,"team_keys":["frc1","frc2","frc3"]},"blue":{"score":70,"team_keys":["frc4","frc5","frc6"]}},"videos":[{"type":"youtube","key":"abc"}]}"""
    private val firstPlayed = """{"description":"Qualification 1","tournamentLevel":"Qualification","matchNumber":1,"scoreRedFinal":88,"scoreBlueFinal":70,"teams":[{"teamNumber":1,"station":"Red1"}]}"""
    private val firstSchedule = """{"description":"Qualification 1","tournamentLevel":"Qualification","matchNumber":1,"teams":[{"teamNumber":1,"station":"Red1"}]}"""

    private fun match(json: String, source: String) = MatchSyncRecord(
        matchKey = "2026cttd_qm1", eventKey = "2026cttd", compLevel = "qm", setNumber = 1, matchNumber = 1,
        redTeams = listOf("frc1", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"),
        dataJson = json, source = source
    )

    // ---- Score detection ----

    @Test
    fun testUnplayedTbaMatchHasNoScore() {
        assertFalse(MatchCanonical.hasScores(tbaUnplayed))
        assertTrue(MatchCanonical.hasScores(tbaPlayed))
        assertTrue(MatchCanonical.hasScores(firstPlayed))
        assertFalse(MatchCanonical.hasScores(firstSchedule))
        assertTrue(MatchCanonical.hasScores("""{"matchNum":1,"hasBeenPlayed":true,"scores":{"red":{"totalPoints":120},"blue":{"totalPoints":95}}}"""))
        assertFalse(MatchCanonical.hasScores("""{"matchNum":1,"hasBeenPlayed":false,"scores":null}"""))
    }

    @Test
    fun testMatchScoresFromEveryApiFormat() {
        fun scores(json: String) = matchScoresFromJson(JsonSupport.json.parseToJsonElement(json) as JsonObject)
        assertEquals(88 to 70, scores(tbaPlayed))
        assertEquals(null to null, scores(tbaUnplayed))
        assertEquals(88 to 70, scores(firstPlayed))
        assertEquals(120 to 95, scores("""{"matchNum":1,"scores":{"red":{"totalPoints":120},"blue":{"totalPoints":95}}}"""))
        assertEquals(10 to 12, scores("""{"redScore":10,"blueScore":12}"""))
        assertEquals(null to null, scores("""{"matchNum":1,"scores":null}"""))
    }

    @Test
    fun testSourceDetection() {
        assertEquals("tba", MatchCanonical.sourceOf(tbaPlayed))
        assertEquals("first", MatchCanonical.sourceOf(firstPlayed))
        assertEquals("ftcscout", MatchCanonical.sourceOf("""{"matchNum":3,"hasBeenPlayed":true}"""))
        assertEquals("unknown", MatchCanonical.sourceOf("{}"))
    }

    // ---- Match rows settle on one version ----

    @Test
    fun testScoredCopyWinsThenTbaBeatsFirst() {
        assertTrue(MatchCanonical.preferIncoming(match(tbaUnplayed, "tba"), match(firstPlayed, "first")))
        assertFalse(MatchCanonical.preferIncoming(match(firstPlayed, "first"), match(tbaUnplayed, "tba")))
        assertTrue(MatchCanonical.preferIncoming(match(firstPlayed, "first"), match(tbaPlayed, "tba")))
        assertFalse(MatchCanonical.preferIncoming(match(tbaPlayed, "tba"), match(firstPlayed, "first")))
        // Same source: the newer copy, so corrections land.
        assertTrue(MatchCanonical.preferIncoming(match(tbaPlayed, "tba"), match(tbaPlayed.replace("88", "90"), "tba")))
    }

    @Test
    fun testTeamsWithDifferentKeysStopFlippingTheRow() {
        // Team A syncs TBA+FIRST, team B only FIRST, alternating as the scheduler walks them.
        var row = MatchCanonical.canonicalize(match(tbaPlayed, "tba"))
        val writes = mutableListOf<String>()
        repeat(4) { pass ->
            val incoming = if (pass % 2 == 0) match(firstPlayed, "first") else match(tbaPlayed, "tba")
            val next = MatchCanonical.resolveAgainstStored(row.copy(source = "db"), incoming)
            if (next.dataJson != row.dataJson) writes.add("pass $pass")
            row = next
        }
        assertEquals(emptyList(), writes, "the stored row should not change once settled")
        assertEquals("tba", MatchCanonical.sourceOf(row.dataJson))
    }

    @Test
    fun testOrderDoesNotChangeTheResult() {
        val a = match(tbaPlayed, "tba")
        val b = match(firstPlayed, "first")
        assertEquals(MatchCanonical.mergeRecords(a, b).dataJson, MatchCanonical.mergeRecords(b, a).dataJson)
    }

    @Test
    fun testResultBeatsScheduleWithinOneSource() {
        // FIRST's results and schedule calls both describe match 1; the schedule must not wipe the score.
        val merged = MatchCanonical.mergeRecords(match(firstPlayed, "first"), match(firstSchedule, "first"))
        assertTrue(MatchCanonical.hasScores(merged.dataJson))
    }

    @Test
    fun testVideosAreKeptWhenFirstJsonWins() {
        val merged = MatchCanonical.mergeRecords(match(tbaPlayed.replace("\"score\":88", "\"score\":-1").replace("\"score\":70", "\"score\":-1"), "tba"), match(firstPlayed, "first"))
        assertEquals("first", MatchCanonical.sourceOf(merged.dataJson))
        assertTrue(merged.dataJson.contains("\"videos\""))
    }

    @Test
    fun testManualRowIsReplacedBySync() {
        val manual = match("""{"manual":true,"redScore":1,"blueScore":2}""", "db")
        val incoming = match(tbaUnplayed, "tba")
        assertEquals(tbaUnplayed, MatchCanonical.resolveAgainstStored(manual, incoming).dataJson)
    }

    // ---- Team rows settle on one version ----

    private fun team(source: String, nickname: String?, city: String?, json: String) = TeamSyncRecord(
        eventKey = "2026cttd", teamKey = "frc254", teamNumber = 254, name = "Team 254", nickname = nickname,
        city = city, state = "CA", country = "USA", opr = null, epa = null, dataJson = json, source = source
    )

    private val tbaTeamJson = """{"key":"frc254","team_number":254,"nickname":"The Cheesy Poofs"}"""
    private val firstTeamJson = """{"teamNumber":254,"nameShort":"Cheesy Poofs","nameFull":"NASA Ames Research Center"}"""

    @Test
    fun testTeamSourceDetection() {
        assertEquals("tba", teamSourceOf(tbaTeamJson))
        assertEquals("first", teamSourceOf(firstTeamJson))
        assertEquals("unknown", teamSourceOf("{}"))
    }

    @Test
    fun testMergedRosterPrefersTbaAndFillsFromFirst() {
        val merged = mergeTeamRecords(listOf(
            team("first", "Cheesy Poofs", "San Jose", firstTeamJson),
            team("tba", "The Cheesy Poofs", null, tbaTeamJson)
        )).single()
        assertEquals("The Cheesy Poofs", merged.nickname)
        assertEquals("San Jose", merged.city)
        assertEquals(tbaTeamJson, merged.dataJson)
    }

    @Test
    fun testFirstOnlyTeamDoesNotUndoTbaTeamRow() {
        val stored = team("tba", "The Cheesy Poofs", "San Jose", tbaTeamJson)
        val kept = mergeTeamWithStored(stored, team("first", "Cheesy Poofs", "San Jose", firstTeamJson))
        assertEquals("The Cheesy Poofs", kept.nickname)
        assertEquals(tbaTeamJson, kept.dataJson)
        // And TBA data replaces a FIRST row.
        val upgraded = mergeTeamWithStored(team("first", "Cheesy Poofs", "San Jose", firstTeamJson), team("tba", "The Cheesy Poofs", null, tbaTeamJson))
        assertEquals("The Cheesy Poofs", upgraded.nickname)
        assertEquals("San Jose", upgraded.city)
        assertEquals(tbaTeamJson, upgraded.dataJson)
    }

    // ---- One failing source ----

    @Test
    fun testOneFailingSourceIsAWarningNotAFailure() = runBlocking {
        val warnings = mutableListOf<String>()
        val tba = IntegrationService.trySource<String>("TBA teams") { throw IllegalStateException("TBA HTTP 503") }
        val first = IntegrationService.trySource("FIRST teams") { listOf("frc1", "frc2") }
        val combined = IntegrationService.combineSources("Team", listOf(tba, first), warnings)
        assertEquals(listOf("frc1", "frc2"), combined)
        assertEquals(listOf("TBA teams: TBA HTTP 503"), warnings)
    }

    @Test
    fun testAllSourcesFailingFailsTheSync() = runBlocking {
        val tba = IntegrationService.trySource<String>("TBA teams") { throw IllegalStateException("TBA HTTP 404") }
        val first = IntegrationService.trySource<String>("FIRST teams") { throw IllegalStateException("FIRST HTTP 401") }
        val error = assertFailsWith<IllegalStateException> {
            IntegrationService.combineSources("Team", listOf(tba, first), mutableListOf())
        }
        assertTrue(error.message!!.contains("TBA HTTP 404") && error.message!!.contains("FIRST HTTP 401"))
    }

    @Test
    fun testNoConfiguredSourceIsEmptyNotAFailure() {
        assertEquals(emptyList(), IntegrationService.combineSources<String>("Team", emptyList(), mutableListOf()))
    }

    // ---- Conditional request cache ----

    @Test
    fun testConditionalCacheKeepsOnlyRevalidatableBodiesWithinItsBudget() {
        val cache = ConditionalHttpCache(maxTotalChars = 10, maxEntryChars = 8)
        cache.put("a", ConditionalHttpCache.Entry(null, null, "abc"))
        assertNull(cache.get("a"), "nothing to revalidate with")
        cache.put("big", ConditionalHttpCache.Entry("\"e\"", null, "123456789"))
        assertNull(cache.get("big"), "over the per-entry limit")
        cache.put("x", ConditionalHttpCache.Entry("\"1\"", null, "xxxxxx"))
        cache.put("y", ConditionalHttpCache.Entry(null, "Wed, 21 Oct 2026 07:28:00 GMT", "yyyyyy"))
        assertNull(cache.get("x"), "oldest entry evicted to stay under the total")
        assertEquals("yyyyyy", cache.get("y")?.body)
    }

    @Test
    fun testCredentialTagsKeepKeysApart() {
        assertTrue(ConditionalHttpCache.credentialTag("tba:key-a") != ConditionalHttpCache.credentialTag("tba:key-b"))
        assertEquals(ConditionalHttpCache.credentialTag("tba:key-a"), ConditionalHttpCache.credentialTag("tba:key-a"))
        assertFalse(ConditionalHttpCache.credentialTag("tba:key-a").contains("key-a"))
    }
}
