package com.obsidianscout.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EarlyThemeTest {

    @Test
    fun scriptIsFirstThingInHeadBeforeStylesheets() {
        val input = """<!doctype html><html lang="en"><head><link rel="stylesheet" href="/css/app.css" /></head><body></body></html>"""
        val output = injectEarlyTheme(input)
        val head = output.indexOf("<head>")
        val script = output.indexOf("<script>")
        assertEquals(head + "<head>".length, script, "Theme script should open the <head>")
        assertTrue(script < output.indexOf("/css/app.css"), "Theme script should run before the stylesheet")
        assertTrue(output.contains("obsidian-theme"))
        assertTrue(output.contains("""/^#[0-9a-fA-F]{3,8}$/"""), "Raw-string dollar escape should produce a literal $")
    }

    @Test
    fun headWithAttributesIsHandledAndPageWithoutHeadIsUntouched() {
        assertTrue(injectEarlyTheme("<html><head data-x=\"1\"><title>x</title></head></html>")
            .contains("<head data-x=\"1\"><script>"))
        assertEquals("<div>no head</div>", injectEarlyTheme("<div>no head</div>"))
    }

    @Test
    fun injectedOnlyOnceAndNotIntoHeaderTags() {
        val output = injectEarlyTheme("<html><head></head><body><header>h</header></body></html>")
        assertEquals(1, Regex("obsidian-theme").findAll(output).count())
        assertTrue(output.contains("<header>h</header>"))
    }
}
