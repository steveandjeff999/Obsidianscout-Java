package com.obsidianscout.routes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The site must work offline at an event: everything a page needs to start has to be served locally and precached. */
class StaticAssetsTest {
    private val static = File(StaticAssetsTest::class.java.getResource("/static/sw.js")!!.toURI()).parentFile.canonicalFile
    private val serviceWorker = File(static, "sw.js").readText()
    private val precached = Regex("""^\s*'(/[^']+)',\s*$""", RegexOption.MULTILINE).findAll(serviceWorker).map { it.groupValues[1] }.toSet()

    private fun sitePath(file: File) = "/" + file.relativeTo(static).invariantSeparatorsPath

    /** [start] and every local file it pulls in through [references]. */
    private fun reachableFrom(start: File, references: Regex): Set<File> {
        val seen = linkedSetOf<File>()
        val pending = ArrayDeque(listOf(start.canonicalFile))
        while (pending.isNotEmpty()) {
            val file = pending.removeFirst()
            if (!seen.add(file)) continue
            references.findAll(file.readText()).forEach { match ->
                pending.add(File(file.parentFile, match.groupValues[1]).canonicalFile)
            }
        }
        return seen
    }

    private val siteFiles: List<File>
        get() = static.walkTopDown().onEnter { it.name != "vendor" }.filter { it.isFile && it.extension in setOf("html", "js", "css") }.toList()

    @Test
    fun `every module common js loads is precached`() {
        val imports = Regex("""^\s*(?:import|export)\b[^;'"]*?['"](\.{1,2}/[^'"]+\.js)['"]""", RegexOption.MULTILINE)
        val modules = reachableFrom(File(static, "js/common.js"), imports)
        assertTrue(modules.size > 10, "Expected common.js to import its modules, found ${modules.size}")
        assertEquals(emptyList(), modules.map(::sitePath).filter { it !in precached })
    }

    @Test
    fun `every stylesheet and font app css loads is precached`() {
        val stylesheets = reachableFrom(File(static, "css/app.css"), Regex("""@import url\('(\./[^']+)'\)"""))
        val fonts = stylesheets.flatMap { css ->
            Regex("""url\('(\.\./[^']+\.woff2)'\)""").findAll(css.readText()).map { File(css.parentFile, it.groupValues[1]).canonicalFile }.toList()
        }
        assertTrue(fonts.isNotEmpty(), "Expected the stylesheets to declare fonts")
        fonts.forEach { assertTrue(it.exists(), "Missing font file $it") }
        assertEquals(emptyList(), (stylesheets + fonts).map(::sitePath).filter { it !in precached })
    }

    @Test
    fun `pages load no fonts from other hosts`() {
        val offenders = siteFiles.filter { Regex("""fonts\.(googleapis|gstatic)\.com""").containsMatchIn(it.readText()) }.map { sitePath(it) }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `no script puts a Font Awesome class on an existing icon`() {
        // common.js upgrades icons when they're added, not when their class changes, and only the brands font is loaded.
        val swap = Regex("""\.className\s*=[^;\n]*\bfa-(?!brands|spin)""")
        val offenders = siteFiles.filter { it.extension == "js" && it.name != "common.js" }
            .filter { swap.containsMatchIn(it.readText()) }
            .map { sitePath(it) }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `every Font Awesome icon has a Material replacement`() {
        val mapBody = Regex("""const FA_TO_MATERIAL = \{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).find(File(static, "js/common.js").readText())!!.groupValues[1]
        val mapped = Regex(""""(fa-[a-z0-9-]+)"\s*:""").findAll(mapBody).map { it.groupValues[1] }.toSet()
        // Style and size modifiers, plus the brand icons Font Awesome still serves.
        val notIcons = setOf("fa-solid", "fa-regular", "fa-brands", "fa-spin", "fa-2x", "fa-svg", "fa-svg-", "fa-apple")

        val used = siteFiles.filter { it.extension != "css" }
            .flatMap { file -> Regex("""["'` .](fa-[a-z0-9-]+)""").findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet()

        assertEquals(emptyList(), (used - mapped - notIcons).sorted())
    }
}
