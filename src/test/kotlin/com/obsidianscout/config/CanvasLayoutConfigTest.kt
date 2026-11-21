package com.obsidianscout.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Canvas scouting forms keep their layout next to the fields; parsing and saving revisions must not drop it. */
class CanvasLayoutConfigTest {

    private val withLayout = """
        {"title":"Canvas","version":2,
         "fields":[{"id":"auto_scored","label":"Auto Fuel","type":"counter","phase":"auto"}],
         "layout":{"mode":"canvas","width":1280,"height":720,"pages":[
           {"id":"p1","name":"Auto","background":{"image":"/api/blobs/00000000-0000-0000-0000-000000000001"},
            "elements":[{"id":"e1","type":"button","x":10,"y":20,"w":160,"h":90,"fieldId":"auto_scored","action":"increment","amount":1}]}
         ]}}
    """.trimIndent()

    @Test
    fun layoutSurvivesParseAndEncode() {
        val parsed = JsonSupport.json.decodeFromString(ScoutingConfig.serializer(), ConfigService.normalizeConfigJson(withLayout))
        val layout = assertNotNull(parsed.layout)
        assertEquals("canvas", layout["mode"]?.jsonPrimitive?.content)

        // Revisions are stored by encoding the parsed config, so the layout must come back out too.
        val encoded = JsonSupport.json.parseToJsonElement(JsonSupport.json.encodeToString(ScoutingConfig.serializer(), parsed)).jsonObject
        val page = encoded["layout"]!!.jsonObject["pages"]!!.jsonArray[0].jsonObject
        assertEquals("auto_scored", page["elements"]!!.jsonArray[0].jsonObject["fieldId"]?.jsonPrimitive?.content)
    }

    @Test
    fun seasonDefaultOnlyCountsWhenSwitchedOnWithPages() {
        fun layout(text: String) = JsonSupport.json.parseToJsonElement(text) as JsonObject
        kotlin.test.assertTrue(SeasonCanvasService.isActive(layout("""{"mode":"canvas","pages":[{"id":"p","elements":[]}]}""")))
        assertFalse(SeasonCanvasService.isActive(layout("""{"mode":"classic","pages":[{"id":"p","elements":[]}]}""")), "a switched-off draft must not reach teams")
        assertFalse(SeasonCanvasService.isActive(layout("""{"mode":"canvas","pages":[]}""")), "an empty canvas must not replace a team's form")
    }

    @Test
    fun configsWithoutLayoutStayUnchanged() {
        val plain = """{"title":"Plain","version":1,"fields":[{"id":"a","label":"A","type":"counter"}]}"""
        val parsed = JsonSupport.json.decodeFromString(ScoutingConfig.serializer(), plain)
        assertNull(parsed.layout)
        val encoded = JsonSupport.json.parseToJsonElement(JsonSupport.json.encodeToString(ScoutingConfig.serializer(), parsed)) as JsonObject
        assertFalse("layout" in encoded, "older apps should never see a layout key on configs that don't have one")
    }
}
