package com.obsidianscout.routes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssetVersioningTest {

    @Test
    fun testInjectAssetVersioningLocalScriptsAndStyles() {
        val input = """
            <!doctype html>
            <html>
            <head>
                <link rel="stylesheet" href="/css/app.css" />
                <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css" />
            </head>
            <body>
                <script type="module" src="/js/common.js"></script>
                <script defer src="/js/dashboard.js"></script>
                <script src="/vendor/qrcode.min.js"></script>
                <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
            </body>
            </html>
        """.trimIndent()

        val output = injectAssetVersioning(input, "0.6.5.0")

        assertTrue(output.contains("""href="/css/app.css?v=0.6.5.0""""), "Local stylesheet should have version appended")
        assertTrue(output.contains("""href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.2/css/all.min.css""""), "External stylesheet should not be modified")
        assertTrue(output.contains("""src="/js/common.js?v=0.6.5.0""""), "Local module script should have version appended")
        assertTrue(output.contains("""src="/js/dashboard.js?v=0.6.5.0""""), "Local defer script should have version appended")
        assertTrue(output.contains("""src="/vendor/qrcode.min.js?v=0.6.5.0""""), "Local vendor script should have version appended")
        assertTrue(output.contains("""src="https://cdn.jsdelivr.net/npm/chart.js""""), "External script should not be modified")
    }

    @Test
    fun testInjectAssetVersioningWithExistingQueryParams() {
        val input = """
            <script src="/js/widget.js?theme=dark"></script>
            <script src="/js/already-versioned.js?v=1.2.3"></script>
            <script src="/js/sw-ver.js?_sw_ver=abc"></script>
            <script src="//cdn.example.com/lib.js"></script>
        """.trimIndent()

        val output = injectAssetVersioning(input, "0.6.5.0")

        assertTrue(output.contains("""src="/js/widget.js?theme=dark&v=0.6.5.0""""), "Existing query params should be preserved with &v=")
        assertTrue(output.contains("""src="/js/already-versioned.js?v=1.2.3""""), "Already versioned URL should not be touched")
        assertTrue(output.contains("""src="/js/sw-ver.js?_sw_ver=abc""""), "SW versioned URL should not be touched")
        assertTrue(output.contains("""src="//cdn.example.com/lib.js""""), "Protocol-relative URL should not be touched")
    }

    @Test
    fun testInjectAssetVersioningEmptyVersionReturnsUntouched() {
        val input = """<script src="/js/common.js"></script>"""
        val output = injectAssetVersioning(input, "")
        assertEquals(input, output)
    }

    @Test
    fun testDynamicCacheNameReplacement() {
        val sampleSw = """
            const CACHE_NAME = 'obsidianscout-shell-v0-6-4-0';
            const NAVIGATION_TIMEOUT_MS = 4000;
        """.trimIndent()

        val version = "0.6.5.0"
        val sanitizedVer = version.replace(".", "-")
        val dynamicCacheConst = "const CACHE_NAME = 'obsidianscout-shell-v$sanitizedVer';"
        val patchedSw = sampleSw.replace(
            Regex("""const CACHE_NAME = ['\"][^'\"]+['\"];"""),
            dynamicCacheConst
        )

        assertTrue(patchedSw.contains("const CACHE_NAME = 'obsidianscout-shell-v0-6-5-0';"))
        assertTrue(patchedSw.contains("const NAVIGATION_TIMEOUT_MS = 4000;"))
    }
}
