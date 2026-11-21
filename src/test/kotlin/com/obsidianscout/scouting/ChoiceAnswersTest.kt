package com.obsidianscout.scouting

import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.ScoutingField
import com.obsidianscout.config.ScoutingOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChoiceAnswersTest {
    private val config = ScoutingConfig(
        fields = listOf(
            ScoutingField(id = "climb", label = "Climb", type = "select", options = listOf(ScoutingOption("Deep", "deep"), ScoutingOption("Odd <b>", "odd <b>"))),
            ScoutingField(id = "pos", label = "Start", type = "radio"),
            ScoutingField(id = "notes", label = "Notes", type = "textarea")
        )
    )

    @Test
    fun stripsMarkupFromAnswersThatAreNotOptions() {
        val data = buildJsonObject { put("climb", "<img src=x onerror=alert(1)>"); put("pos", "\"left\"") }
        val out = ChoiceAnswers.sanitize(config, data)
        assertEquals("img src=x onerror=alert(1)", out["climb"]!!.jsonPrimitive.content)
        assertEquals("left", out["pos"]!!.jsonPrimitive.content)
    }

    @Test
    fun keepsConfiguredOptionsLegacyPlainAnswersAndOtherFields() {
        val data = buildJsonObject {
            put("climb", "odd <b>")
            put("pos", "an old option")
            put("notes", "<b>free text is escaped where it is shown</b>")
            put("team", 254)
        }
        assertSame(data, ChoiceAnswers.sanitize(config, data))
    }

    @Test
    fun cleansArrayAnswers() {
        val data = buildJsonObject { put("climb", JsonArray(listOf(JsonPrimitive("deep"), JsonPrimitive("<svg>")))) }
        val out = ChoiceAnswers.sanitize(config, data)
        assertEquals(JsonArray(listOf(JsonPrimitive("deep"), JsonPrimitive("svg"))), out["climb"])
    }
}
