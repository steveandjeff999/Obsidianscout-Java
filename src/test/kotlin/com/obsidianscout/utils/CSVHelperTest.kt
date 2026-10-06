package com.obsidianscout.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class CSVHelperTest {

    @Test
    fun formulaLikeTextIsNeutralizedButNumbersAreNot() {
        assertEquals("'=HYPERLINK(\"x\")", CSVHelper.neutralizeFormula("=HYPERLINK(\"x\")"))
        assertEquals("'@SUM(A1)", CSVHelper.neutralizeFormula("@SUM(A1)"))
        assertEquals("'-", CSVHelper.neutralizeFormula("-"))
        assertEquals("-5", CSVHelper.neutralizeFormula("-5"))
        assertEquals("+3.25", CSVHelper.neutralizeFormula("+3.25"))
        assertEquals("-1e5", CSVHelper.neutralizeFormula("-1e5"))
        assertEquals("plain text", CSVHelper.neutralizeFormula("plain text"))
    }

    @Test
    fun exportedValuesComeBackUnchangedOnImport() {
        val values = listOf("=cmd|' /C calc'!A0", "-5", "+notes", "@user", "'quoted already", "{\"a\":1}", "line1\nline2, \"q\"")
        val csv = CSVHelper.toCSV(listOf("v"), values.map { listOf(it) })

        val parsed = CSVHelper.parseCSV(csv).map { it.getValue("v") }

        assertEquals(values, parsed)
    }
}
