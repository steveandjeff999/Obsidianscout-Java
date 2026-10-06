package com.obsidianscout.scouting

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DeduplicationTest {

    private data class Row(val id: UUID, val key: String, val json: String)

    private fun run(rows: List<Row>): Pair<List<UUID>, List<String>> {
        val deleted = mutableListOf<UUID>()
        val recalculated = mutableListOf<String>()
        DeduplicationScheduler.dedupe(
            rows = rows,
            id = { it.id },
            dataJson = { it.json },
            keyOf = { it.key },
            delete = { deleted += it },
            recalculate = { recalculated += it }
        )
        return deleted to recalculated
    }

    @Test
    fun keepsTheFirstCopyAndDeletesLaterIdenticalOnes() {
        val first = Row(UUID.randomUUID(), "scoutA", """{"auto":3,"scouterName":"A"}""")
        val repeat = Row(UUID.randomUUID(), "scoutA", """{"auto":3,"scouterName":"A (resent)"}""")
        val different = Row(UUID.randomUUID(), "scoutA", """{"auto":4}""")

        val (deleted, recalculated) = run(listOf(first, repeat, different))

        assertEquals(listOf(repeat.id), deleted)
        assertEquals(listOf("scoutA"), recalculated)
    }

    @Test
    fun groupsWithoutDeletionsAreNotRecalculated() {
        val (deleted, recalculated) = run(
            listOf(
                Row(UUID.randomUUID(), "scoutA", """{"auto":3}"""),
                Row(UUID.randomUUID(), "scoutB", """{"auto":3}""")
            )
        )
        assertEquals(emptyList(), deleted, "identical data from different scouts (different keys) is kept")
        assertEquals(emptyList(), recalculated)
    }

    @Test
    fun unparsableRowsAreNeverDeleted() {
        val good = Row(UUID.randomUUID(), "k", """{"auto":1}""")
        val broken1 = Row(UUID.randomUUID(), "k", "not json")
        val broken2 = Row(UUID.randomUUID(), "k", "not json")

        val (deleted, _) = run(listOf(good, broken1, broken2))

        assertEquals(emptyList(), deleted)
    }
}
