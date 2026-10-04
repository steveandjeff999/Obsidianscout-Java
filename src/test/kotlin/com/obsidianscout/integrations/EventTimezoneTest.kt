package com.obsidianscout.integrations

import com.obsidianscout.routes.ApiSettingsPayload
import com.obsidianscout.routes.EventRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTimezoneTest {

    @Test
    fun testSettingsDefaultTimezoneIsUtc() {
        val settings = ApiSettings()
        assertEquals("UTC", settings.timezone)
    }

    @Test
    fun testApiSettingsPayloadDefaultTimezoneIsUtc() {
        val payload = ApiSettingsPayload()
        assertEquals("UTC", payload.timezone)
    }

    @Test
    fun testEventRecordTimezone() {
        val eventWithVenueTz = EventRecord(
            eventKey = "2026okok",
            name = "Oklahoma Regional",
            year = 2026,
            timezone = "America/Chicago"
        )
        assertEquals("America/Chicago", eventWithVenueTz.timezone)

        val eventWithoutTz = EventRecord(
            eventKey = "2026test",
            name = "Test Regional",
            year = 2026,
            timezone = null
        )
        val resolvedTz = eventWithoutTz.timezone?.takeIf { it.isNotBlank() } ?: "UTC"
        assertEquals("UTC", resolvedTz)
    }
}
