package com.obsidianscout.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BugReportRateLimiterTest {

    @BeforeTest
    fun setUp() {
        BugReportRateLimiter.reset()
    }

    @AfterTest
    fun tearDown() {
        BugReportRateLimiter.reset()
    }

    @Test
    fun testAllowsUpToLimit() {
        BugReportRateLimiter.maxReports = 5
        val ip = "192.168.1.100"

        for (i in 1..5) {
            assertTrue(BugReportRateLimiter.tryAcquire(ip), "Request $i should be allowed")
        }
    }

    @Test
    fun testBlocksWhenExceedingLimit() {
        BugReportRateLimiter.maxReports = 3
        val ip = "192.168.1.101"

        assertTrue(BugReportRateLimiter.tryAcquire(ip))
        assertTrue(BugReportRateLimiter.tryAcquire(ip))
        assertTrue(BugReportRateLimiter.tryAcquire(ip))

        assertFalse(BugReportRateLimiter.tryAcquire(ip), "4th request should be blocked")
        assertFalse(BugReportRateLimiter.checkAllowed(ip), "checkAllowed should report false when limit is reached")
    }

    @Test
    fun testDifferentIpsAreIsolated() {
        BugReportRateLimiter.maxReports = 2
        val ip1 = "10.0.0.1"
        val ip2 = "10.0.0.2"

        assertTrue(BugReportRateLimiter.tryAcquire(ip1))
        assertTrue(BugReportRateLimiter.tryAcquire(ip1))
        assertFalse(BugReportRateLimiter.tryAcquire(ip1), "ip1 should be blocked")

        // ip2 should still be completely allowed
        assertTrue(BugReportRateLimiter.checkAllowed(ip2))
        assertTrue(BugReportRateLimiter.tryAcquire(ip2))
        assertTrue(BugReportRateLimiter.tryAcquire(ip2))
        assertFalse(BugReportRateLimiter.tryAcquire(ip2), "ip2 should now be blocked")
    }

    @Test
    fun testWindowExpiration() {
        BugReportRateLimiter.maxReports = 2
        BugReportRateLimiter.windowMs = 50L // 50 ms window for fast test
        val ip = "172.16.0.5"

        assertTrue(BugReportRateLimiter.tryAcquire(ip))
        assertTrue(BugReportRateLimiter.tryAcquire(ip))
        assertFalse(BugReportRateLimiter.tryAcquire(ip), "Should be blocked before window expires")

        Thread.sleep(60L) // Wait for window to expire

        assertTrue(BugReportRateLimiter.tryAcquire(ip), "Should be allowed in new window")
    }

    @Test
    fun testClearResetsIp() {
        BugReportRateLimiter.maxReports = 1
        val ip = "10.10.10.10"

        assertTrue(BugReportRateLimiter.tryAcquire(ip))
        assertFalse(BugReportRateLimiter.tryAcquire(ip))

        BugReportRateLimiter.clear(ip)

        assertTrue(BugReportRateLimiter.tryAcquire(ip), "Should be allowed after clear")
    }

    @Test
    fun testBlankIpHandledSafely() {
        BugReportRateLimiter.maxReports = 2

        assertTrue(BugReportRateLimiter.tryAcquire(""))
        assertTrue(BugReportRateLimiter.tryAcquire("   "))
        assertFalse(BugReportRateLimiter.tryAcquire(""), "Blank IPs grouped under unknown should be limited")
    }

    @Test
    fun testConcurrentRequests() = runBlocking {
        BugReportRateLimiter.maxReports = 10
        val ip = "192.168.100.50"
        val totalAttempts = 50

        val results = (1..totalAttempts).map {
            async(Dispatchers.Default) {
                BugReportRateLimiter.tryAcquire(ip)
            }
        }.awaitAll()

        val allowedCount = results.count { it }
        val blockedCount = results.count { !it }

        assertEquals(10, allowedCount, "Exactly maxReports (10) requests should have been allowed")
        assertEquals(40, blockedCount, "Exactly 40 requests should have been blocked")
    }
}
