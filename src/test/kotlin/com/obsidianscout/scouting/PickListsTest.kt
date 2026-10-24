package com.obsidianscout.scouting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PickListsTest {

    @Test
    fun `native image config registers every pick list serializer`() {
        // The release build is a GraalVM native image, which can only find serializers listed here.
        val config = javaClass.classLoader
            .getResource("META-INF/native-image/com.obsidianscout/obsidianscout-server/reflect-config.json")!!
            .readText()
        val classes = listOf(
            PickLists::class, PickListsResponse::class, PickListsUpdateRequest::class,
            RankedPickList::class, RankedPickListResponse::class, RankedPickListUpdateRequest::class,
            DoNotPickUpdateRequest::class
        )
        for (cls in classes) {
            for (suffix in listOf("", "\$\$serializer", "\$Companion")) {
                val name = cls.java.name + suffix
                assertTrue("\"$name\"" in config, "$name is missing from reflect-config.json")
            }
        }
    }

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
    fun `ranking drops do not pick teams, duplicates and bad numbers`() {
        val clean = AllianceSelectionService.sanitizeRanking(
            ranking = listOf(254, 118, 1678, 254, 0, -3, 971),
            dnp = listOf(118, 971)
        )
        assertEquals(listOf(254, 1678), clean)
    }

    @Test
    fun `ranked pick list is keyed apart from the want avoid dnp lists`() {
        assertEquals("rankedpicklist_team_254_FRC", AllianceSelectionService.rankedPickListOwnerKey(254, "FRC"))
        assertNotEquals(
            AllianceSelectionService.rankedPickListOwnerKey(254, "FRC"),
            AllianceSelectionService.pickListOwnerKey(254, "FRC")
        )
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
