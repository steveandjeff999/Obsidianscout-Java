package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtcSyncTest {

    private fun json(text: String): JsonElement = JsonSupport.json.parseToJsonElement(text)

    @Test
    fun testScoreSelectionCoversEverySeasonButRemote() {
        val selection = FtcIntegrationService.scoresSelection(
            listOf("MatchScores2020Trad", "MatchScores2020Remote", "MatchScores2025", "MatchScores2026")
        )
        assertTrue(selection.contains("... on MatchScores2026 { red { totalPoints } blue { totalPoints } }"))
        assertTrue(selection.contains("... on MatchScores2025 "))
        assertTrue(selection.contains("... on MatchScores2020Trad "))
        // Remote seasons have no alliances; spreading red/blue on them is a GraphQL validation error.
        assertFalse(selection.contains("Remote"))
        assertTrue(selection.startsWith("__typename"))
    }

    @Test
    fun testEventStatsSelectionAsksForOprInEverySeason() {
        val selection = FtcIntegrationService.eventStatsSelection(listOf("TeamEventStats2021Remote", "TeamEventStats2026"))
        assertTrue(selection.contains("... on TeamEventStats2026 { opr { totalPoints } }"))
        assertTrue(selection.contains("... on TeamEventStats2021Remote { opr { totalPoints } }"))
    }

    private val scoutQual1 = json("""{"id":1,"matchNum":1,"series":1,"tournamentLevel":"Quals","hasBeenPlayed":true,"teams":[{"teamNumber":100,"alliance":"Red","station":"One"},{"teamNumber":200,"alliance":"Blue","station":"One"}],"scores":{"red":{"totalPoints":120},"blue":{"totalPoints":95}}}""")
    private val scoutQual2Unplayed = json("""{"id":2,"matchNum":2,"series":1,"tournamentLevel":"Quals","hasBeenPlayed":false,"teams":[{"teamNumber":100,"alliance":"Red","station":"One"},{"teamNumber":200,"alliance":"Blue","station":"One"}],"scores":null}""")
    private val firstQual1 = json("""{"description":"Qualification 1","tournamentLevel":"QUALIFICATION","series":0,"matchNumber":1,"scoreRedFinal":120,"scoreBlueFinal":95,"teams":[{"teamNumber":100,"station":"Red1"},{"teamNumber":200,"station":"Blue1"}]}""")
    private val firstQual2 = json("""{"description":"Qualification 2","tournamentLevel":"QUALIFICATION","series":0,"matchNumber":2,"scoreRedFinal":60,"scoreBlueFinal":61,"teams":[{"teamNumber":100,"station":"Red1"},{"teamNumber":200,"station":"Blue1"}]}""")

    @Test
    fun testBothSourcesBecomeOneRecordPerMatch() {
        val matches = FtcIntegrationService.buildMatches("2026ustxcmp", listOf(scoutQual1, scoutQual2Unplayed), listOf(firstQual1, firstQual2))
        assertEquals(listOf("2026ustxcmp_qm1", "2026ustxcmp_qm2"), matches.map { it.matchKey })
        // Both scored: FTC Scout's copy is kept.
        assertEquals("ftcscout", MatchCanonical.sourceOf(matches[0].dataJson))
        // Only FIRST has the result yet: its scored copy wins.
        assertEquals("first", MatchCanonical.sourceOf(matches[1].dataJson))
        assertEquals(listOf("ftc100"), matches[0].redTeams)
        assertEquals(listOf("ftc200"), matches[0].blueTeams)
    }

    @Test
    fun testDoubleElimFinalsStillMapAfterTheBoundary() {
        val semis = (1..5).map { s -> json("""{"matchNum":1,"series":$s,"tournamentLevel":"DoubleElim","hasBeenPlayed":true,"teams":[]}""") }
        val final1 = json("""{"matchNum":1,"series":6,"tournamentLevel":"DoubleElim","hasBeenPlayed":true,"teams":[]}""")
        val keys = FtcIntegrationService.buildMatches("2026ustxcmp", semis + final1, emptyList()).map { it.matchKey }
        assertTrue("2026ustxcmp_sf5m1" in keys)
        assertTrue("2026ustxcmp_f1m1" in keys)
    }

    @Test
    fun testSyncWithFirstDataUsesFirstNamesAndLocation() {
        val resolved = FtcIntegrationService.resolveFtcTeamFields(
            scout = FtcIntegrationService.FirstFtcTeam("Robo Raiders", "Austin", "TX", "USA"),
            first = FtcIntegrationService.FirstFtcTeam("Raiders", "Austin", "Texas", null),
            firstAvailable = true,
            stored = null
        )
        assertEquals("Raiders", resolved.nickname)
        assertEquals("Texas", resolved.state)
        assertEquals("USA", resolved.country, "blank FIRST value filled from FTC Scout")
    }

    @Test
    fun testSyncWithoutFirstKeepsWhatAFirstSyncStored() {
        val resolved = FtcIntegrationService.resolveFtcTeamFields(
            scout = FtcIntegrationService.FirstFtcTeam("Robo Raiders", "Austin", "TX", "USA"),
            first = null,
            firstAvailable = false,
            stored = FtcIntegrationService.FirstFtcTeam("Raiders", "Austin", "Texas", "USA")
        )
        assertEquals("Raiders", resolved.nickname)
        assertEquals("Texas", resolved.state)
    }

    @Test
    fun testNewTeamWithoutFirstUsesFtcScout() {
        val resolved = FtcIntegrationService.resolveFtcTeamFields(
            scout = FtcIntegrationService.FirstFtcTeam("Robo Raiders", "Austin", "TX", "USA"),
            first = null,
            firstAvailable = false,
            stored = null
        )
        assertEquals("Robo Raiders", resolved.nickname)
        assertEquals("TX", resolved.state)
    }
}
