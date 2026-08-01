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
        assertEquals(1, config.gist_update.check_interval_minutes, "Default check interval should be 1 minute")
    }
}
