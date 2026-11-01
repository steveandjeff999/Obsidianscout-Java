package com.obsidianscout.admin

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadProfilerTest {
    private val minute = 60_000L
    private val now = 1_000_000 * minute

    @BeforeTest
    fun reset() = LoadProfiler.resetForTest()

    private fun record(
        path: String = "/api/scouting",
        status: Int = 200,
        micros: Long = 2_000,
        bytes: Long = 100,
        live: Boolean = false,
        at: Long = now
    ) = LoadProfiler.record("GET", path, status, micros, bytes, live, at)

    @Test
    fun `live refresh traffic is split from everything else`() {
        record(live = true)
        record(live = true)
        record()

        val row = LoadProfiler.minutesIn(1, now).single()
        assertEquals(3, row.requests)
        assertEquals(2, row.liveRequests)
        assertEquals(6.0, row.serverMs)
        assertEquals(4.0, row.liveServerMs)
        assertEquals(300, row.bytes)
        assertEquals(200, row.liveBytes)
    }

    @Test
    fun `every minute of the window has a row, quiet ones included`() {
        record(at = now - 2 * minute)
        record(at = now)

        assertEquals(listOf(1L, 0L, 1L), LoadProfiler.minutesIn(3, now).map { it.requests })
    }

    @Test
    fun `an hour later the old minute's slot has gone to the new one`() {
        record(at = now)
        val later = now + (LoadProfiler.WINDOW_MINUTES + 1) * minute
        record(at = later)

        assertEquals(1, LoadProfiler.minutesIn(LoadProfiler.WINDOW_MINUTES, later).sumOf { it.requests })
    }

    @Test
    fun `routes merge across the window with the worst time kept`() {
        record(micros = 1_000, at = now - minute)
        record(micros = 9_000, live = true, at = now)

        val route = LoadProfiler.routesIn(2, now).single()
        assertEquals("GET /api/scouting", route.route)
        assertEquals(2, route.requests)
        assertEquals(1, route.liveRequests)
        assertEquals(10.0, route.serverMs)
        assertEquals(9.0, route.maxMs)
    }

    @Test
    fun `ids, event keys and team numbers don't make new routes`() {
        assertEquals("/api/teams/:id/stats", LoadProfiler.normalize("/api/teams/5454/stats"))
        assertEquals("/api/events/:id", LoadProfiler.normalize("/api/events/2026cada"))
        assertEquals("/api/scouting", LoadProfiler.normalize("/api/scouting"))
    }

    @Test
    fun `the route list can't grow without limit`() {
        (0 until 500).forEach { record(path = "/api/${"x".repeat(it % 26 + 1)}${('a' + it % 26)}/${it.toString().map { d -> 'a' + (d - '0') }.joinToString("")}") }

        val routes = LoadProfiler.routesIn(1, now)
        assertTrue(routes.size <= 301, "saw ${routes.size} routes")
        assertEquals(500, routes.sumOf { it.requests })
    }

    @Test
    fun `an unchanged answer built the body but sent nothing`() {
        record(status = 304, bytes = 5_000)

        assertEquals(0, LoadProfiler.minutesIn(1, now).single().bytes)
    }

    @Test
    fun `server errors are counted`() {
        record(status = 500)
        record(status = 404)

        assertEquals(1, LoadProfiler.minutesIn(1, now).single().errors)
    }

    @Test
    fun `invalidations are counted by topic with the users they reached`() {
        LoadProfiler.recordInvalidation(listOf("scouting", "rankings"), 4, now)
        LoadProfiler.recordInvalidation(listOf("scouting"), 2, now)

        val topics = LoadProfiler.topicsIn(1, now).associateBy { it.topic }
        assertEquals(2, topics.getValue("scouting").messages)
        assertEquals(6, topics.getValue("scouting").usersReached)
        assertEquals(1, topics.getValue("rankings").messages)

        val row = LoadProfiler.minutesIn(1, now).single()
        assertEquals(2, row.invalidations)
        assertEquals(6, row.usersReached)
    }

    @Test
    fun `sockets and the profiler's own page aren't counted`() {
        assertTrue(LoadProfiler.tracks("/api/scouting"))
        assertFalse(LoadProfiler.tracks("/api/ws/notifications"))
        assertFalse(LoadProfiler.tracks("/api/admin/cluster/load-profile"))
        assertFalse(LoadProfiler.tracks("/api/admin/cluster/nodes/local/load-profile"))
        assertFalse(LoadProfiler.tracks("/dashboard"))
    }
}
