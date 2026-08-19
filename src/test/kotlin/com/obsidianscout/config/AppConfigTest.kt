package com.obsidianscout.config

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {

    @Test
    fun testDefaultSiteUrl() {
        val config = AppConfig()
        assertEquals("https://kotlin.obsidianscout.com", config.site_url)
        assertEquals("https://kotlin.obsidianscout.com", config.getEffectiveSiteUrl())
    }

    @Test
    fun testGetEffectiveSiteUrlFormatting() {
        // Without scheme
        val noScheme = AppConfig(site_url = "kotlin.obsidianscout.com")
        assertEquals("https://kotlin.obsidianscout.com", noScheme.getEffectiveSiteUrl())

        // With trailing slash
        val trailingSlash = AppConfig(site_url = "https://kotlin.obsidianscout.com/")
        assertEquals("https://kotlin.obsidianscout.com", trailingSlash.getEffectiveSiteUrl())

        // HTTP scheme
        val httpScheme = AppConfig(site_url = "http://192.168.1.50:8080/")
        assertEquals("http://192.168.1.50:8080", httpScheme.getEffectiveSiteUrl())

        // Blank
        val blank = AppConfig(site_url = "   ")
        assertEquals("https://kotlin.obsidianscout.com", blank.getEffectiveSiteUrl())
    }

    @Test
    fun testAppConfigLoaderAutoMigratesMissingSiteUrlOnBoot() {
        val tempDir = Files.createTempDirectory("app_config_test")
        try {
            val configFile = tempDir.resolve("app-config.json")
            // Config file missing site_url
            val legacyJson = """
                {
                    "server": { "host": "0.0.0.0", "port": 8080 },
                    "database": { "type": "sqlite" },
                    "database_type": "sqlite"
                }
            """.trimIndent()
            Files.writeString(configFile, legacyJson)

            val loaded = AppConfigLoader.load(configFile, forceReload = true)
            assertEquals("https://kotlin.obsidianscout.com", loaded.site_url)

            // Verify it was written back to disk
            val savedText = Files.readString(configFile)
            assertTrue(savedText.contains("site_url"), "Config file on disk should now contain site_url after boot")
            assertTrue(savedText.contains("https://kotlin.obsidianscout.com"), "Config file on disk should have default site_url")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
