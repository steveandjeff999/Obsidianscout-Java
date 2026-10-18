package com.obsidianscout.widgets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetChecklistServiceTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private val template = obj(
        """{"lists":{"pre":{"title":"Before queue","order":0,"frequency":"match"},
            "swap":{"title":"Every 2","order":1,"frequency":"every_n","every":2},
            "day":{"title":"Daily","order":2,"frequency":"daily"},
            "evt":{"title":"Event","order":3,"frequency":"event"},
            "empty":{"title":"Nothing yet","order":4,"frequency":"match"}},
          "fields":[{"id":"a","section":"pre","text":"Bumpers","priority":"low"},
                    {"id":"b","section":"pre","text":"Battery","priority":"critical"},
                    {"id":"","section":"pre","text":"no id"},
                    {"id":"c","section":"swap","text":"Swap wheels"},
                    {"id":"d","section":"day","text":"Charge radios"},
                    {"id":"e","section":"evt","text":"Inspection"}]}"""
    )

    @Test
    fun everyListComesWithItsItemsAndCheckedState() {
        val docs = mapOf(
            "m2" to obj("""{"checked":{"b":{"by":"alex","at":1}}}"""),
            "day_2026-10-17" to obj("""{"checked":{"d":{"by":"sam","at":2}}}"""),
        )
        val lists = WidgetChecklistService.lists(template, listOf("m1", "m2", "m3"), "m2", "day_2026-10-17") { docs[it] }
        assertEquals(listOf("pre", "swap", "day", "evt"), lists.map { it.id }, "lists with no items are left out")

        val pre = lists[0]
        assertEquals("m2", pre.record)
        assertEquals(listOf("b", "a"), pre.items.map { it.id }, "critical before low")
        assertTrue(pre.items[0].checked)
        assertEquals("alex", pre.items[0].by)
        assertFalse(pre.items[1].checked)

        // Our 2nd match: the every-2 list isn't due, but it's still shown.
        assertFalse(lists[1].due)
        assertEquals("day_2026-10-17", lists[2].record)
        assertTrue(lists[2].items.single().checked)
        assertEquals(WidgetChecklistService.EVENT_RECORD, lists[3].record)
    }

    @Test
    fun withoutAnUpcomingMatchOnlyDailyAndEventListsRemain() {
        val lists = WidgetChecklistService.lists(template, emptyList(), null, "day_2026-10-17") { null }
        assertEquals(listOf("day", "evt"), lists.map { it.id })
    }

    @Test
    fun dayKeyUsesTheEventTimezone() {
        val lateUtc = Instant.parse("2026-10-18T03:00:00Z")
        assertEquals("day_2026-10-17", WidgetChecklistService.dayKey("America/Chicago", lateUtc))
        assertEquals("day_2026-10-18", WidgetChecklistService.dayKey("UTC", lateUtc))
    }
}
