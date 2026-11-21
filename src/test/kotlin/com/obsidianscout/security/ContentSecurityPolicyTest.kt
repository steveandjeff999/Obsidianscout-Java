package com.obsidianscout.security

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentSecurityPolicyTest {
    private fun sha(text: String) =
        "'sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())) + "'"

    @Test
    fun noPolicyAllowsInlineScriptsWholesale() {
        assertFalse(ContentSecurityPolicy.DEFAULT.contains("'unsafe-inline' 'wasm"))
        val scriptSrc = ContentSecurityPolicy.forHashes(listOf("'sha256-abc'")).substringAfter("script-src ").substringBefore(";")
        assertFalse(scriptSrc.contains("unsafe-inline"))
        assertTrue(scriptSrc.contains("'sha256-abc'"))
        assertTrue(ContentSecurityPolicy.DEFAULT.contains("script-src-attr 'none'"))
    }

    @Test
    fun hashesOnlyInlineExecutableScriptsWithLineEndingsNormalised() {
        val html = "<head><script>a()\r\nb()</script><script src=\"/x.js\"></script>" +
            "<script type=\"module\">import './y.js'</script><script type=\"application/ld+json\">{}</script>" +
            "<script>a()\nb()</script></head>"
        assertEquals(listOf(sha("a()\nb()"), sha("import './y.js'")), ContentSecurityPolicy.inlineScriptHashes(html))
    }

    @Test
    fun staticPagesHaveNoInlineEventHandlers() {
        val projectDir = System.getProperty("obsidianscout.projectDir")?.let(::File) ?: File(".")
        val static = File(projectDir, "src/main/resources/static")
        val handler = Regex("""\son[a-z]+\s*=\s*["']""", RegexOption.IGNORE_CASE)
        val offenders = (static.listFiles { f -> f.extension == "html" }.orEmpty().toList() +
            File(static, "js").walkTopDown().filter { it.extension == "js" }.toList())
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (handler.containsMatchIn(line)) "${f.name}:${i + 1}" else null } }
        assertTrue(offenders.isEmpty(), "Inline event handlers are blocked by the CSP; use addEventListener instead: $offenders")
    }
}
