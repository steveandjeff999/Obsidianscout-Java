package com.obsidianscout.routes

import com.obsidianscout.publicdata.PublicDirectoryResponse
import com.obsidianscout.publicdata.PublicEventSummary
import com.obsidianscout.publicdata.PublicTeamSummary
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SitemapTest {

    private fun event(key: String, matches: Int) = PublicEventSummary(
        eventKey = key, eventName = key, year = 2026,
        matchEntries = matches, pitEntries = 0, qualEntries = 0, analyticsAvailable = matches > 0
    )

    @Test
    fun listsPublicPagesAndSharedTeamData() {
        val directory = PublicDirectoryResponse(
            listOf(
                PublicTeamSummary(5454, "FRC", "Team", events = listOf(event("2026casj", 12), event("2026empty", 0))),
                PublicTeamSummary(16000, "FTC", null, events = listOf(event("2026ftc&x", 3)))
            )
        )
        val xml = buildSitemapXml("https://example.com", directory)

        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<loc>https://example.com/</loc>"))
        assertTrue(xml.contains("<loc>https://example.com/about</loc>"))
        assertTrue(xml.contains("<loc>https://example.com/public/5454/analytics?event=2026casj</loc>"))
        assertTrue(xml.contains("<loc>https://example.com/public/16000/rankings?event=2026ftc%26x&amp;program=FTC</loc>"))
        assertFalse(xml.contains("2026empty"), "events without match data have no analytics to index")
        assertFalse(xml.contains("/dashboard"), "pages behind login stay out of the sitemap")
    }
}
