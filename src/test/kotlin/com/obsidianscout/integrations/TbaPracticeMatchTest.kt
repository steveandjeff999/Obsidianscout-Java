package com.obsidianscout.integrations

import kotlin.test.Test
import kotlin.test.assertEquals

class TbaPracticeMatchTest {

    private val tbaPractice = MatchSyncRecord(
        matchKey = "2026cttd_pm3",
        eventKey = "2026cttd",
        compLevel = "pm",
        setNumber = 1,
        matchNumber = 3,
        redTeams = listOf("frc1", "frc2", "frc3"),
        blueTeams = listOf("frc4", "frc5", "frc6"),
        source = "tba"
    )

    @Test
    fun testPmIsPractice() {
        assertEquals("practice", MatchCanonical.normalizeCompLevel("pm"))
        assertEquals("practice", MatchCanonical.normalizeCompLevel("PM"))
    }

    @Test
    fun testTbaPracticeKeyMatchesFirstPracticeKey() {
        val canonical = MatchCanonical.canonicalize(tbaPractice)
        assertEquals("2026cttd_practice3", canonical.matchKey)
        assertEquals("practice", canonical.compLevel)
        assertEquals(3, canonical.matchNumber)
    }

    @Test
    fun testKeyAloneIsEnoughToFindPractice() {
        val canonical = MatchCanonical.canonicalize(tbaPractice.copy(compLevel = "", setNumber = null, matchNumber = null))
        assertEquals("2026cttd_practice3", canonical.matchKey)
    }

    @Test
    fun testTbaAndFirstPracticeMergeIntoOneMatch() {
        val firstPractice = MatchSyncRecord(
            matchKey = "2026cttd_practice3",
            eventKey = "2026cttd",
            compLevel = "Practice",
            matchNumber = 3,
            redTeams = listOf("frc1", "frc2", "frc3"),
            blueTeams = listOf("frc4", "frc5", "frc6"),
            source = "first"
        )
        val merged = MatchCanonical.mergeAll(listOf(tbaPractice, firstPractice))
        assertEquals(listOf("2026cttd_practice3"), merged.map { it.matchKey })
    }
}
