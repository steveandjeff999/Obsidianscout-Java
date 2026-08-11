package com.obsidianscout.utils

import com.obsidianscout.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GistUpdateServiceTest {

    @Test
    fun testDefaultGistConfig() {
        val config = AppConfig()
        assertTrue(config.gist_update.gist_url.contains("raw"), "Gist URL should point to raw endpoint")
        assertEquals(10L, config.gist_update.check_interval_minutes, "Default check interval should be 10 minutes")
    }

    @Test
    fun testHttpClientResetAndRecovery() {
        val client1 = GistUpdateService.getHttpClient()
        kotlin.test.assertNotNull(client1, "Initial HttpClient should not be null")

        // Reset should create a new HttpClient instance
        GistUpdateService.resetHttpClient()
        val client2 = GistUpdateService.getHttpClient()
        kotlin.test.assertNotNull(client2, "Recreated HttpClient should not be null")
        kotlin.test.assertNotSame(client1, client2, "resetHttpClient should produce a new HttpClient instance")
    }
}
