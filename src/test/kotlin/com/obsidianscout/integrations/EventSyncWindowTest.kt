package com.obsidianscout.integrations

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventSyncWindowTest {

    // 2026-03-17 06:00 UTC is still 2026-03-17 01:00 in Chicago.
    private val now = Instant.parse("2026-03-17T06:00:00Z")

    @Test
    fun testKeepsSyncingThroughTheDayAfterTheEvent() {
        assertFalse(isPastEventSyncWindow("2026-03-15", "America/Chicago", Instant.parse("2026-03-16T23:00:00Z")))
        assertFalse(isPastEventSyncWindow("2026-03-16", "America/Chicago", now))
    }

    @Test
    fun testStopsTwoDaysAfterTheEvent() {
        assertTrue(isPastEventSyncWindow("2026-03-15", "America/Chicago", now))
    }

    @Test
    fun testUsesTheEventsLocalDate() {
        // UTC has reached the 17th but Chicago is still on the 16th, the buffer day for an event ending the 15th.
        val lateOn16thLocal = Instant.parse("2026-03-17T04:30:00Z")
        assertFalse(isPastEventSyncWindow("2026-03-15", "America/Chicago", lateOn16thLocal))
        assertTrue(isPastEventSyncWindow("2026-03-15", "UTC", lateOn16thLocal))
    }

    @Test
    fun testAcceptsFirstApiFormats() {
        assertTrue(isPastEventSyncWindow("2026-03-15T00:00:00", "Eastern Standard Time", now))
    }

    @Test
    fun testUnknownDatesKeepSyncing() {
        assertFalse(isPastEventSyncWindow(null, "UTC", now))
        assertFalse(isPastEventSyncWindow("", "UTC", now))
        assertFalse(isPastEventSyncWindow("TBD", "UTC", now))
    }

    @Test
    fun testBadTimezoneFallsBackToUtc() {
        assertTrue(isPastEventSyncWindow("2026-03-15", "Not/AZone", now))
    }
}
