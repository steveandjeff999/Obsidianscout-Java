package com.obsidianscout.config

import com.obsidianscout.auth.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlankOptionValuesTest {

    @Test
    fun findsOptionsWithoutAValue() {
        val json = """
            {"fields":[
              {"id":"climb","label":"Climb","type":"select","options":[
                {"label":"None","value":""},{"label":"L1","value":"l1"},{"label":{"en":"L2"},"value":"  "},{"label":"L3","value":null}
              ]},
              {"id":"park","label":"Park","type":"select","options":["Yes",""]},
              {"id":"notes","label":"Notes","type":"textarea"}
            ]}
        """.trimIndent()
        assertEquals(listOf("Climb: None", "Climb: L2", "Climb: L3", "Park: (no label)"), ConfigService.blankOptionValues(json))
    }

    @Test
    fun acceptsConfigsWhereEveryOptionHasAValue() {
        val json = """{"fields":[{"id":"climb","label":"Climb","type":"select","options":[{"label":"None","value":"n"}]}]}"""
        assertTrue(ConfigService.blankOptionValues(json).isEmpty())
        ConfigService.requireNoBlankOptionValues(json)
    }

    @Test
    fun rejectsSavingABlankOptionValue() {
        val json = """{"fields":[{"id":"climb","label":"Climb","type":"select","options":[{"label":"None","value":""}]}]}"""
        val error = assertFailsWith<ApiException> { ConfigService.requireNoBlankOptionValues(json) }
        assertTrue(error.message!!.contains("Climb: None"))
    }
}
