package com.obsidianscout.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two options sharing a value are stored the same way, so the answers can never be told apart afterwards. */
class DefaultConfigFilesTest {
    private val shipped = listOf(
        "config/default-scouting-config.json",
        "config/default-pit-scouting-config.json",
        "config/default-qualitative-scouting-config.json",
        "config/defaults/frc2026-match.json",
        "config/defaults/frc2026-pit.json",
        "config/defaults/frc2026-qualitative.json",
        "config/defaults/ftc2026-match.json",
        "config/defaults/ftc2026-pit.json",
        "config/defaults/ftc2026-qualitative.json"
    )

    @Test
    fun `options in a shipped form have different values`() {
        val duplicates = shipped.flatMap { path ->
            val file = File(path)
            assertTrue(file.exists(), "Missing $path")
            val fields = JsonSupport.json.parseToJsonElement(file.readText()) as JsonObject
            (fields["fields"] as JsonArray).mapNotNull { field ->
                val values = ((field as JsonObject)["options"] as? JsonArray).orEmpty().map { (it as JsonObject)["value"]!!.jsonPrimitive.content }
                val repeated = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                if (repeated.isEmpty()) null else "$path ${field["id"]}: $repeated"
            }
        }
        assertEquals(emptyList(), duplicates)
    }
}
