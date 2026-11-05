package com.obsidianscout.auth

import com.obsidianscout.cache.RedisClusterCoordinator
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Without Redis (as in tests), each node's own counts must still enforce the limit. */
class LoginRateLimiterTest {

    private fun freshIp() = "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
    private fun freshUser() = "user-${UUID.randomUUID()}"

    @Test
    fun redisIsOffInTests() {
        assertFalse(RedisClusterCoordinator.isReady)
    }

    @Test
    fun blocksIpAfterTenFailures() {
        val ip = freshIp()
        repeat(9) { LoginRateLimiter.recordFailedAttempt(ip, freshUser(), isLogin = false) }
        assertTrue(LoginRateLimiter.checkAllowed(ip, freshUser()))
        LoginRateLimiter.recordFailedAttempt(ip, freshUser(), isLogin = false)
        assertFalse(LoginRateLimiter.checkAllowed(ip, freshUser()))
    }

    @Test
    fun blocksUsernameAcrossIps() {
        val user = freshUser()
        repeat(10) { LoginRateLimiter.recordFailedAttempt(freshIp(), user, isLogin = false) }
        assertFalse(LoginRateLimiter.checkAllowed(freshIp(), user))
        assertFalse(LoginRateLimiter.checkAllowed(freshIp(), "  ${user.uppercase()} "))
    }

    @Test
    fun clearLiftsTheBlock() {
        val ip = freshIp()
        val user = freshUser()
        repeat(10) { LoginRateLimiter.recordFailedAttempt(ip, user, isLogin = false) }
        assertFalse(LoginRateLimiter.checkAllowed(ip, user))
        LoginRateLimiter.clear(ip, user)
        assertTrue(LoginRateLimiter.checkAllowed(ip, user))
    }
}
