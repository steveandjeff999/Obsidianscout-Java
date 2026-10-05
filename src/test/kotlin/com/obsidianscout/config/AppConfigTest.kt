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
    fun testSecretsAreMovedOutOfAppConfigIntoSecretsFile() {
        val tempDir = Files.createTempDirectory("app_config_secrets_test")
        try {
            val configFile = tempDir.resolve("app-config.json")
            val sessionSecret = "a".repeat(64)
            val keystorePassword = "b".repeat(64)
            Files.writeString(configFile, """
                {
                    "server": {
                        "sessionSecret": "$sessionSecret",
                        "https": { "keystorePassword": "$keystorePassword", "keystorePath": "${tempDir.resolve("test.jks").toString().replace("\\", "/")}" }
                    },
                    "vapid": { "publicKey": "PUB", "privateKey": "PRIV" },
                    "database_type": "sqlite", "site_url": "https://example.test", "quorum_fallback": {}, "auto_backup": {}
                }
            """.trimIndent())

            val loaded = AppConfigLoader.load(configFile, forceReload = true)
            assertEquals(sessionSecret, loaded.server.sessionSecret)
            assertEquals(keystorePassword, loaded.server.https.keystorePassword)
            assertEquals("PRIV", loaded.vapid.privateKey)

            val onDisk = Files.readString(configFile)
            assertTrue(!onDisk.contains(sessionSecret), "app-config.json must no longer contain the session secret")
            assertTrue(!onDisk.contains(keystorePassword), "app-config.json must no longer contain the keystore password")
            assertTrue(!onDisk.contains("PRIV"), "app-config.json must no longer contain the VAPID private key")

            val secretsText = Files.readString(AppConfigLoader.secretsPathFor(configFile))
            assertTrue(secretsText.contains(sessionSecret) && secretsText.contains("PRIV"))

            // The .bak written alongside app-config.json must not keep the old secrets either.
            val backupFile = tempDir.resolve("app-config.json.bak")
            assertTrue(Files.exists(backupFile), "rewriting app-config.json should leave a .bak")
            val backupText = Files.readString(backupFile)
            assertTrue(!backupText.contains(sessionSecret) && !backupText.contains("PRIV"), "app-config.json.bak must not contain secrets")

            // A second load reads the secrets back from secrets.json.
            val reloaded = AppConfigLoader.load(configFile, forceReload = true)
            assertEquals(sessionSecret, reloaded.server.sessionSecret)
            assertEquals("PRIV", reloaded.vapid.privateKey)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
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

    @Test
    fun testDatabaseConfigCockroachAndCustomUrl() {
        val json = """
            {
                "database": {
                    "type": "cockroach",
                    "cockroach": {
                        "host": "crdb.example.com",
                        "port": 26257,
                        "database": "obsidianscout_prod",
                        "user": "appuser",
                        "password": "secretpassword",
                        "ssl": true,
                        "url": "jdbc:postgresql://crdb.example.com:26257/obsidianscout_prod?sslmode=require"
                    },
                    "url": "jdbc:postgresql://crdb.example.com:26257/obsidianscout_prod?sslmode=require"
                }
            }
        """.trimIndent()

        val config = JsonSupport.json.decodeFromString<AppConfig>(json)
        assertEquals("cockroach", config.database.type)
        assertEquals("crdb.example.com", config.database.cockroach.host)
        assertEquals(26257, config.database.cockroach.port)
        assertEquals("obsidianscout_prod", config.database.cockroach.database)
        assertEquals("appuser", config.database.cockroach.user)
        assertEquals("secretpassword", config.database.cockroach.password)
        assertTrue(config.database.cockroach.ssl)
        assertEquals("jdbc:postgresql://crdb.example.com:26257/obsidianscout_prod?sslmode=require", config.database.url)
    }

    @Test
    fun testQuorumFallbackDefaultsToFalse() {
        val config = AppConfig()
        assertEquals(false, config.quorum_fallback.enabled)
        assertEquals("data/quorum_fallback.db", config.quorum_fallback.sqlite_file)
        assertEquals(30L, config.quorum_fallback.sync_interval_seconds)
        assertEquals(7, config.quorum_fallback.scouting_retention_days)
    }
}
