package com.obsidianscout.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessRestartDelaysTest {
    @Test
    fun `a single server restarts soon after answering`() {
        val delays = ClusterManagementService.processRestartDelays(0)
        assertEquals(1, delays.size)
        assertTrue(delays.single() in 1_000L..10_000L, "The reset's response has to leave first")
    }

    @Test
    fun `nodes restart one at a time with the local node last`() {
        val delays = ClusterManagementService.processRestartDelays(2)
        assertEquals(3, delays.size)
        assertEquals(delays.sorted(), delays)
        delays.zipWithNext { first, next -> assertTrue(next - first >= 30_000L, "Nodes need time to rejoin before the next one stops") }
    }

    @Test
    fun `every delay fits under the limit peers accept`() {
        assertTrue(ClusterManagementService.processRestartDelays(20).last() <= ClusterManagementService.MAX_RESTART_DELAY_MS)
    }
}
