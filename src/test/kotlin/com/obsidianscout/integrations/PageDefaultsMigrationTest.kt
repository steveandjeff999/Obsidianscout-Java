package com.obsidianscout.integrations

import com.obsidianscout.auth.UserRole
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.AllianceMemberships
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageDefaultsMigrationTest {

    private val testDbFile = File("build/test_page_defaults_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction { SchemaUtils.create(AppSettings, ScoutingAlliances, AllianceMemberships) }
        SettingsService.ensureDefaultSettings()
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    /** Stores settings JSON as an older version of the app would have, without the new page or version field. */
    private fun storeLegacySettings(teamNumber: Int) {
        val legacy = ApiSettings(
            analyticsPages = listOf("dashboard", "rankings"),
            adminPages = listOf("dashboard", "rankings"),
            scoutPages = listOf("dashboard", "scout")
        )
        val json = JsonSupport.json.encodeToString(ApiSettings.serializer(), legacy)
            .replace(Regex(",?\\s*\"pageDefaultsVersion\"\\s*:\\s*\\d+"), "")
        transaction {
            AppSettings.insert {
                it[AppSettings.teamNumber] = teamNumber
                it[program] = "FRC"
                it[settingsJson] = json
                it[updatedAt] = Instant.now()
            }
        }
    }

    @Test
    fun `new teams get projected rankings for analytics and admin only`() {
        val settings = SettingsService.getSettings(1234, "FRC")
        assertTrue("projected-rankings" in settings.analyticsPages)
        assertTrue("projected-rankings" in settings.adminPages)
        assertFalse("projected-rankings" in settings.scoutPages)
    }

    @Test
    fun `existing teams are granted the page once`() {
        storeLegacySettings(254)
        val settings = SettingsService.getSettings(254, "FRC")
        assertTrue("projected-rankings" in settings.analyticsPages)
        assertTrue("projected-rankings" in settings.adminPages)
        assertFalse("projected-rankings" in settings.scoutPages)
    }

    @Test
    fun `an admin removing the page is respected after saving`() {
        storeLegacySettings(254)
        val loaded = SettingsService.getSettings(254, "FRC")
        // Simulate the admin settings page, which doesn't send pageDefaultsVersion
        SettingsService.updateSettings(
            254,
            loaded.copy(analyticsPages = loaded.analyticsPages - "projected-rankings", pageDefaultsVersion = 0)
        )
        val reloaded = SettingsService.getSettings(254, "FRC")
        assertFalse("projected-rankings" in reloaded.analyticsPages)
        assertTrue("projected-rankings" in reloaded.adminPages)
    }

    @Test
    fun `page access follows the role page lists`() {
        storeLegacySettings(254)
        assertTrue(SettingsService.canAccessPage(254, "FRC", UserRole.ANALYTICS, "projected-rankings"))
        assertTrue(SettingsService.canAccessPage(254, "FRC", UserRole.ADMIN, "projected-rankings"))
        assertFalse(SettingsService.canAccessPage(254, "FRC", UserRole.SCOUT, "projected-rankings"))
        assertTrue(SettingsService.canAccessPage(254, "FRC", UserRole.SUPERADMIN, "projected-rankings"))
    }
}
