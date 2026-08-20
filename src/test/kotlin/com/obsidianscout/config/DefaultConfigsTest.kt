package com.obsidianscout.config

import com.obsidianscout.db.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultConfigsTest {

    private val testDbFile = File("build/test_default_configs_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(DefaultConfigs, ScoutingConfigs, PitScoutingConfigs, QualitativeScoutingConfigs, ScoutingAlliances)
        }
        ConfigService.ensureDefaultConfig()
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testProgramIsolationForDefaultConfigs() {
        val ftcDefaults = ConfigService.getDefaultConfigs("FTC")
        val frcDefaults = ConfigService.getDefaultConfigs("FRC")

        assertTrue(ftcDefaults.isNotEmpty(), "FTC default configs should not be empty")
        assertTrue(frcDefaults.isNotEmpty(), "FRC default configs should not be empty")

        // Assert all FTC returned defaults belong to FTC
        assertTrue(ftcDefaults.all { it.program == "FTC" }, "All FTC default configs must have program FTC")

        // Assert all FRC returned defaults belong to FRC
        assertTrue(frcDefaults.all { it.program == "FRC" }, "All FRC default configs must have program FRC")
    }

    @Test
    fun testCrossProgramPresetApplicationRejection() {
        assertFailsWith<IllegalArgumentException> {
            // Team in FTC program trying to apply FRC preset
            ConfigService.applyDefaultConfig(teamNumber = 9999, program = "FTC", configType = "match", presetName = "frc2026")
        }

        assertFailsWith<IllegalArgumentException> {
            // Team in FRC program trying to apply FTC preset
            ConfigService.applyDefaultConfig(teamNumber = 9999, program = "FRC", configType = "match", presetName = "ftc2026")
        }
    }

    @Test
    fun testIndependentConfigTypeApplicationAndReset() {
        val teamNum = 1111
        val prog = "FTC"

        // 1. Update initial pit config to custom value
        ConfigService.updatePitConfig(teamNum, prog, """{"version":1,"title":"Custom Pit"}""")

        // 2. Apply match preset ftc2026
        val matchResult = ConfigService.applyDefaultConfig(teamNum, prog, "match", "ftc2026")
        assertNotNull(matchResult)
        assertEquals("FTC 2026 Into The Deep Scouting", matchResult.title)

        // 3. Verify Pit config was NOT cleared or overwritten!
        val pitResult = ConfigService.getPitConfigJson(teamNum, prog, local = true)
        assertTrue("Custom Pit" in pitResult, "Applying match default config should leave pit config untouched")

        // 4. Reset match config to default
        val resetMatch = ConfigService.resetToDefaultConfig(teamNum, prog, "match")
        assertNotNull(resetMatch)

        // 5. Verify Pit config is still intact
        val pitResult2 = ConfigService.getPitConfigJson(teamNum, prog, local = true)
        assertTrue("Custom Pit" in pitResult2, "Resetting match config should leave pit config untouched")
    }

    @Test
    fun testGeneralPhaseMigratedToTeleop() {
        val teamNum = 2222
        val prog = "FRC"

        val rawGeneralConfig = """
            {
                "version": 1,
                "title": "Legacy Config with General Phase",
                "fields": [
                    {
                        "id": "driverNotes",
                        "label": "Driver Notes",
                        "type": "text",
                        "phase": "general"
                    },
                    {
                        "id": "autoSpeaker",
                        "label": "Auto Speaker",
                        "type": "counter",
                        "phase": "auto"
                    }
                ]
            }
        """.trimIndent()

        // 1. Verify updateConfig migrates "general" phase to "teleop"
        val updated = ConfigService.updateConfig(teamNum, prog, rawGeneralConfig)
        val driverNotesField = updated.fields.find { it.id == "driverNotes" }
        assertNotNull(driverNotesField)
        assertEquals("teleop", driverNotesField.phase)

        // 2. Verify server startup / ensureDefaultConfig migrates existing DB rows
        val legacyTeamNum = 3333
        transaction {
            ScoutingConfigs.insert {
                it[ScoutingConfigs.teamNumber] = legacyTeamNum
                it[ScoutingConfigs.program] = prog
                it[ScoutingConfigs.configJson] = rawGeneralConfig
                it[ScoutingConfigs.updatedAt] = java.time.Instant.now()
            }
        }

        ConfigService.ensureDefaultConfig()

        val migratedConfig = ConfigService.getConfig(legacyTeamNum, prog, local = true)
        val migratedNotes = migratedConfig.fields.find { it.id == "driverNotes" }
        assertNotNull(migratedNotes)
        assertEquals("teleop", migratedNotes.phase)
    }
}
