package com.obsidianscout.integrations

import kotlin.test.Test
import kotlin.test.assertEquals

class EventCatalogTest {

    @Test
    fun testFirstEventsMatchingTbaAreNotListedTwice() {
        val tba = listOf(
            EventCatalogEntry(code = "okok", name = "Oklahoma Regional", startDate = "2026-03-05"),
            EventCatalogEntry(code = "arc", altCode = "ARCHIMEDES", name = "Archimedes Division", startDate = "2026-04-29"),
            EventCatalogEntry(code = "cttd", altCode = "MOLEE", name = "Offseason", startDate = "2026-10-01")
        )
        val first = listOf(
            EventCatalogEntry(code = "okok", name = "Oklahoma Regional", location = "Oklahoma City, OK, USA"),
            EventCatalogEntry(code = "arc", altCode = "ARCHIMEDES", name = "FIRST Championship - Archimedes"),
            EventCatalogEntry(code = "molee", name = "Offseason (FIRST)"),
            EventCatalogEntry(code = "txhou", name = "Houston District", startDate = "2026-03-12")
        )
        val merged = mergeEventCatalog(tba, first)
        assertEquals(listOf("okok", "txhou", "arc", "cttd"), merged.map { it.code })
        assertEquals("Oklahoma City, OK, USA", merged.first { it.code == "okok" }.location)
        assertEquals("Archimedes Division", merged.first { it.code == "arc" }.name)
        assertEquals("MOLEE", merged.first { it.code == "cttd" }.altCode)
    }

    @Test
    fun testDivisionWithoutTbaFirstCodeStillGetsAlternate() {
        // What fetchEventCatalog derives when TBA leaves first_event_code blank.
        assertEquals("ARCHIMEDES", firstEventCodeFor(2026, "arc", null))
        assertEquals("OKOK", firstEventCodeFor(2026, "okok", null))
    }
}
