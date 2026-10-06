package com.obsidianscout.scouting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PickListsTest {

    @Test
    fun `sanitize keeps each team in its first list only and drops bad numbers`() {
        val clean = AllianceSelectionService.sanitizePickLists(
            PickLists(
                want = listOf(254, 1678, 254, 0, -5),
                avoid = listOf(1678, 118),
                dnp = listOf(118, 971),
                notes = mapOf("254" to "  great auto  ", "971" to "", "999" to "not listed", "abc" to "bad key")
            )
        )
        assertEquals(listOf(254, 1678), clean.want)
        assertEquals(listOf(118), clean.avoid)
        assertEquals(listOf(971), clean.dnp)
        assertEquals(mapOf("254" to "great auto"), clean.notes)
    }

    @Test
    fun `notes are capped in length`() {
        val clean = AllianceSelectionService.sanitizePickLists(
            PickLists(want = listOf(1), notes = mapOf("1" to "x".repeat(500)))
        )
        assertEquals(200, clean.notes["1"]!!.length)
    }

    @Test
    fun `pick lists are keyed per team and program, not per alliance`() {
        assertEquals("picklist_team_254_FRC", AllianceSelectionService.pickListOwnerKey(254, "FRC"))
        assertNotEquals(
            AllianceSelectionService.pickListOwnerKey(254, "FRC"),
            AllianceSelectionService.pickListOwnerKey(254, "FTC")
        )
    }
}
