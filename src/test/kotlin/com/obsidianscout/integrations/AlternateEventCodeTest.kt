package com.obsidianscout.integrations

import com.obsidianscout.routes.ApiSettingsPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlternateEventCodeTest {

    private val settings = ApiSettings(year = 2026, eventCode = "cttd", alternateEventCode = "molee")

    @Test
    fun testAlternateAppliesToConfiguredEvent() {
        assertTrue(settings.usesAlternateEventCode("2026cttd"))
        assertTrue(settings.usesAlternateEventCode("2026CTTD"))
        assertTrue(settings.usesAlternateEventCode(""))
    }

    @Test
    fun testAlternateDoesNotApplyToOtherEvents() {
        assertFalse(settings.usesAlternateEventCode("2026okok"))
    }

    @Test
    fun testBlankAlternateIsNeverUsed() {
        assertFalse(settings.copy(alternateEventCode = "").usesAlternateEventCode("2026cttd"))
        assertFalse(settings.copy(alternateEventCode = "   ").usesAlternateEventCode("2026cttd"))
    }

    @Test
    fun testPayloadOmitsAlternateByDefault() {
        // Null means "keep the saved value", so clients that don't send the field can't clear it.
        assertNull(ApiSettingsPayload().alternateEventCode)
    }

    @Test
    fun testFullKeyIsReducedToCode() {
        assertEquals("molee", SettingsService.normalizeAlternateEventCode(" 2026molee "))
        assertEquals("MOLEE", SettingsService.normalizeAlternateEventCode("MOLEE"))
        assertEquals("", SettingsService.normalizeAlternateEventCode("  "))
    }

    @Test
    fun testFirstCodeDefaultsStayUnchanged() {
        assertEquals("CTTD", firstEventCodeFor(2026, "cttd", "2026cttd"))
    }
}
