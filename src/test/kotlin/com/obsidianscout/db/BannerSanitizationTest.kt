package com.obsidianscout.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BannerSanitizationTest {

    @Test
    fun testSanitizeContentStripsScriptTags() {
        val raw = "Important notice <script>alert('xss')</script> please review."
        val sanitized = BannerService.sanitizeContent(raw)
        assertEquals("Important notice  please review.", sanitized)
        assertFalse(sanitized.contains("script", ignoreCase = true))
        assertFalse(sanitized.contains("alert", ignoreCase = true))
    }

    @Test
    fun testSanitizeContentStripsIframesAndStyles() {
        val raw = "<style>body{display:none}</style>Hello <iframe src='https://evil.com'></iframe>World"
        val sanitized = BannerService.sanitizeContent(raw)
        assertEquals("Hello World", sanitized)
        assertFalse(sanitized.contains("iframe", ignoreCase = true))
        assertFalse(sanitized.contains("style", ignoreCase = true))
    }

    @Test
    fun testSanitizeContentStripsEventHandlers() {
        val raw = "<img src=\"invalid.png\" onerror=\"alert('pwned')\" /> Click <b onclick=\"steal()\">Here</b>"
        val sanitized = BannerService.sanitizeContent(raw)
        assertFalse(sanitized.contains("onerror", ignoreCase = true))
        assertFalse(sanitized.contains("onclick", ignoreCase = true))
        assertTrue(sanitized.contains("Click <b >Here</b>") || sanitized.contains("Here"))
    }

    @Test
    fun testSanitizeContentStripsJavascriptProtocols() {
        val raw = "<a href=\"javascript:alert(1)\">Dangerous Link</a>"
        val sanitized = BannerService.sanitizeContent(raw)
        assertFalse(sanitized.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun testSanitizeBannerType() {
        assertEquals("info", BannerService.sanitizeBannerType("info"))
        assertEquals("warning", BannerService.sanitizeBannerType("WARNING"))
        assertEquals("danger", BannerService.sanitizeBannerType("danger"))
        assertEquals("success", BannerService.sanitizeBannerType("success"))
        assertEquals("info", BannerService.sanitizeBannerType("invalid-type"))
        assertEquals("info", BannerService.sanitizeBannerType("<script>alert(1)</script>"))
    }
}
