package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchVideoTest {

    private fun parse(json: String): JsonObject = JsonSupport.json.parseToJsonElement(json).jsonObject

    @Test
    fun `youtube videos become watch links`() {
        val videos = MatchCanonical.extractVideos(parse(
            """{"videos":[{"type":"youtube","key":"dQw4w9WgXcQ"},{"type":"youtube","key":"abcDEF12345?t=1m30s"}]}"""
        ))
        assertEquals(
            listOf(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=abcDEF12345&t=90s"
            ),
            videos.map { it.url }
        )
    }

    @Test
    fun `non youtube and malformed keys are dropped`() {
        val videos = MatchCanonical.extractVideos(parse(
            """{"videos":[{"type":"tba","key":"2024abc_qm1.mp4"},{"type":"youtube","key":"\"><script>"},{"type":"youtube","key":""}]}"""
        ))
        assertTrue(videos.isEmpty())
    }

    @Test
    fun `missing videos yields empty list`() {
        assertTrue(MatchCanonical.extractVideos(parse("""{"alliances":{}}""")).isEmpty())
    }

    @Test
    fun `merging keeps tba videos when first api json wins`() {
        val tba = MatchSyncRecord(
            matchKey = "2026test_qm1", eventKey = "2026test", compLevel = "qm", matchNumber = 1,
            redTeams = listOf("frc1", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"),
            dataJson = """{"videos":[{"type":"youtube","key":"dQw4w9WgXcQ"}]}""",
            source = "tba"
        )
        val first = tba.copy(
            dataJson = """{"scoreRedFinal":10,"scoreBlueFinal":20}""",
            source = "first"
        )
        val merged = MatchCanonical.mergeRecords(tba, first)
        val json = parse(merged.dataJson)
        assertTrue(json.containsKey("scoreRedFinal"))
        assertEquals(1, MatchCanonical.extractVideos(json).size)
    }
}
