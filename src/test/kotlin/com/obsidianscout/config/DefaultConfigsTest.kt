package com.obsidianscout.config

import com.obsidianscout.db.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
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
            SchemaUtils.create(DefaultConfigs, DeletedDefaultConfigs, ScoutingConfigs, PitScoutingConfigs, QualitativeScoutingConfigs, ScoutingAlliances)
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
    fun testDefaultConfigsAutoLoadedFromDiskOnBoot() {
        val allDefaults = ConfigService.getAllDefaultConfigs()
        assertTrue(allDefaults.isNotEmpty(), "Default config presets from update bundle on disk should be loaded")
        assertTrue(allDefaults.any { it.name == "frc2026" && it.configType == "match" })
        assertTrue(allDefaults.any { it.name == "ftc2026" && it.configType == "match" })
    }

    @Test
    fun testProgramIsolationForDefaultConfigs() {
        val ftcPreset = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = "test_ftc_manual_1",
                program = "FTC",
                configType = "match",
                configJson = """{"version":1,"title":"FTC Manual 1","fields":[]}""",
                isDefault = false
            )
        )
        val frcPreset = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = "test_frc_manual_1",
                program = "FRC",
                configType = "match",
                configJson = """{"version":1,"title":"FRC Manual 1","fields":[]}""",
                isDefault = false
            )
        )

        try {
            val ftcDefaults = ConfigService.getDefaultConfigs("FTC")
            val frcDefaults = ConfigService.getDefaultConfigs("FRC")

            assertTrue(ftcDefaults.isNotEmpty(), "FTC default configs should not be empty")
            assertTrue(frcDefaults.isNotEmpty(), "FRC default configs should not be empty")

            // Assert all FTC returned defaults belong to FTC
            assertTrue(ftcDefaults.all { it.program == "FTC" }, "All FTC default configs must have program FTC")

            // Assert all FRC returned defaults belong to FRC
            assertTrue(frcDefaults.all { it.program == "FRC" }, "All FRC default configs must have program FRC")
        } finally {
            ftcPreset.id?.let { ConfigService.deleteDefaultConfig(it) }
            frcPreset.id?.let { ConfigService.deleteDefaultConfig(it) }
        }
    }

    @Test
    fun testCrossProgramPresetApplicationRejection() {
        val frcPreset = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = "test_frc_cross",
                program = "FRC",
                configType = "match",
                configJson = """{"version":1,"title":"FRC 2026 Reefscape","fields":[]}""",
                isDefault = false
            )
        )
        val ftcPreset = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = "test_ftc_cross",
                program = "FTC",
                configType = "match",
                configJson = """{"version":1,"title":"FTC 2026 Into The Deep","fields":[]}""",
                isDefault = false
            )
        )

        try {
            assertFailsWith<IllegalArgumentException> {
                // Team in FTC program trying to apply FRC preset
                ConfigService.applyDefaultConfig(teamNumber = 9999, program = "FTC", configType = "match", presetName = "test_frc_cross")
            }

            assertFailsWith<IllegalArgumentException> {
                // Team in FRC program trying to apply FTC preset
                ConfigService.applyDefaultConfig(teamNumber = 9999, program = "FRC", configType = "match", presetName = "test_ftc_cross")
            }
        } finally {
            frcPreset.id?.let { ConfigService.deleteDefaultConfig(it) }
            ftcPreset.id?.let { ConfigService.deleteDefaultConfig(it) }
        }
    }

    @Test
    fun testIndependentConfigTypeApplicationAndReset() {
        val teamNum = 1111
        val prog = "FTC"

        val preset = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = "test_ftc_independent",
                program = "FTC",
                configType = "match",
                configJson = """{"version":1,"title":"FTC 2026 Into The Deep Scouting","fields":[]}""",
                isDefault = true
            )
        )

        try {
            // 1. Update initial pit config to custom value
            ConfigService.updatePitConfig(teamNum, prog, """{"version":1,"title":"Custom Pit"}""")

            // 2. Apply match preset
            val matchResult = ConfigService.applyDefaultConfig(teamNum, prog, "match", "test_ftc_independent")
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
        } finally {
            preset.id?.let { ConfigService.deleteDefaultConfig(it) }
        }
    }

    @Test
    fun testActiveDefaultPresetSyncsToTeamZero() {
        val prog = "FRC"
        val customDefaultTitle = "Manually Created Active Default"
        val preset = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = "frc_manual_active",
                program = prog,
                configType = "match",
                configJson = """{"version":1,"title":"$customDefaultTitle","fields":[]}""",
                isDefault = true
            )
        )

        // Verify team 0 in ScoutingConfigs was updated
        val teamZeroConfig = transaction {
            ScoutingConfigs.selectAll()
                .where { (ScoutingConfigs.teamNumber eq 0) and (ScoutingConfigs.program eq prog) }
                .firstOrNull()?.get(ScoutingConfigs.configJson)
        }
        assertNotNull(teamZeroConfig)
        assertTrue(teamZeroConfig.contains(customDefaultTitle))

        // Verify unconfigured team automatically receives this active default
        val unconfiguredTeamJson = ConfigService.getConfigJson(8888, prog, local = true)
        assertTrue(unconfiguredTeamJson.contains(customDefaultTitle))

        ConfigService.deleteDefaultConfig(preset.id!!)
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

    @Test
    fun testDefaultConfigPointValuesAssignmentAndSerialization() {
        val configWithPoints = """
            {
                "version": 1,
                "title": "FTC Match Points Config",
                "fields": [
                    {
                        "id": "autoSampleScored",
                        "label": "Auto Sample Scored",
                        "type": "counter",
                        "phase": "auto",
                        "min": 0,
                        "max": 10,
                        "step": 1,
                        "pointsPer": 4.0
                    },
                    {
                        "id": "parkBonus",
                        "label": "Park Bonus",
                        "type": "checkbox",
                        "phase": "endgame",
                        "pointsPer": 3.0
                    },
                    {
                        "id": "ascentLevel",
                        "label": "Ascent Level",
                        "type": "select",
                        "phase": "endgame",
                        "options": [
                            { "label": "None", "value": "none", "points": 0.0 },
                            { "label": "Level 1", "value": "lvl1", "points": 3.0 },
                            { "label": "Level 2", "value": "lvl2", "points": 15.0 },
                            { "label": "Level 3", "value": "lvl3", "points": 30.0 }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val dto = DefaultConfigDTO(
            name = "test_points_preset",
            program = "FTC",
            configType = "match",
            configJson = configWithPoints,
            isDefault = false
        )

        val created = ConfigService.createDefaultConfig(dto)
        assertNotNull(created.id)

        // Verify deserialization into ScoutingConfig preserves points
        val parsed = JsonSupport.json.decodeFromString<ScoutingConfig>(created.configJson)
        val counterField = parsed.fields.find { it.id == "autoSampleScored" }
        assertNotNull(counterField)
        assertEquals(4.0, counterField.pointsPer)

        val checkboxField = parsed.fields.find { it.id == "parkBonus" }
        assertNotNull(checkboxField)
        assertEquals(3.0, checkboxField.pointsPer)

        val selectField = parsed.fields.find { it.id == "ascentLevel" }
        assertNotNull(selectField)
        assertEquals(4, selectField.options.size)
        assertEquals(30.0, selectField.options.find { it.value == "lvl3" }?.points)

        // Clean up
        ConfigService.deleteDefaultConfig(created.id)
    }

    @Test
    fun testDefaultConfigsStoredOnDisk() {
        val dto = DefaultConfigDTO(
            name = "disk_store_test",
            program = "FRC",
            configType = "match",
            configJson = """{"version":1,"title":"Disk Store Preset","fields":[]}""",
            isDefault = false
        )

        val created = ConfigService.createDefaultConfig(dto)
        assertNotNull(created.id)

        val defaultsDir = ConfigService.getDefaultsDirectory()
        val expectedFile = defaultsDir.resolve("disk_store_test-match.json").toFile()
        assertTrue(expectedFile.exists(), "Preset file should be created on server disk")
        assertTrue(expectedFile.readText().contains("Disk Store Preset"), "Disk file should contain preset content")

        // Clean up
        ConfigService.deleteDefaultConfig(created.id)
        assertFalse(expectedFile.exists(), "Preset file should be deleted from server disk when preset is deleted")
    }

    @Test
    fun testDefaultConfigsUpdatedFromSourceWithoutOverwritingTeamConfigs() {
        val teamNum = 5454
        val prog = "FRC"

        // 1. Team 5454 has a customized configuration
        val customTeamConfig = """{"version":1,"title":"Team 5454 Custom Match Config","fields":[{"id":"custom_metric","label":"Custom Metric","type":"counter"}]}"""
        ConfigService.updateConfig(teamNum, prog, customTeamConfig)

        // Verify team config is in place
        val beforeUpdate = ConfigService.getConfigJson(teamNum, prog, local = true)
        assertTrue(beforeUpdate.contains("Team 5454 Custom Match Config"))

        // 2. Simulate an update by calling ensureDefaultConfig()
        ConfigService.ensureDefaultConfig()

        // 3. Team config MUST be completely untouched!
        val afterUpdate = ConfigService.getConfigJson(teamNum, prog, local = true)
        assertTrue(afterUpdate.contains("Team 5454 Custom Match Config"), "Team config must NEVER be overwritten by update from source!")
        assertTrue(afterUpdate.contains("custom_metric"), "Team customized fields must be fully preserved!")
    }

    @Test
    fun testMultiServerClusterCloningFromDatabaseToLocalDisk() {
        val defaultsDir = ConfigService.getDefaultsDirectory()
        val clusterFile = defaultsDir.resolve("cluster_node_b_test-match.json").toFile()
        if (clusterFile.exists()) clusterFile.delete()

        // Simulate Node A writing directly to shared CockroachDB
        var insertedId: String? = null
        transaction {
            insertedId = DefaultConfigs.insert {
                it[name] = "cluster_node_b_test"
                it[program] = "FRC"
                it[configType] = "match"
                it[configJson] = """{"version":1,"title":"Node A Created Config","fields":[]}"""
                it[isDefault] = false
                it[updatedAt] = java.time.Instant.now()
            }[DefaultConfigs.id].value.toString()
        }

        assertFalse(clusterFile.exists(), "Before sync, Node B local disk should not have the file")

        // Simulate Node B running syncFromDatabaseToLocalDisk()
        ConfigService.syncFromDatabaseToLocalDisk()

        assertTrue(clusterFile.exists(), "After cluster sync, Node B local disk should have cloned the file from the database")
        assertTrue(clusterFile.readText().contains("Node A Created Config"))

        // Simulate Node A updating the config in the database
        transaction {
            DefaultConfigs.update({ DefaultConfigs.name eq "cluster_node_b_test" }) {
                it[configJson] = """{"version":2,"title":"Node A Updated Config","fields":[]}"""
                it[updatedAt] = java.time.Instant.now()
            }
        }

        // Simulate Node B running sync again
        ConfigService.syncFromDatabaseToLocalDisk()
        assertTrue(clusterFile.readText().contains("Node A Updated Config"), "After cluster sync, Node B local disk should have updated file content")

        // Clean up
        if (insertedId != null) {
            ConfigService.deleteDefaultConfig(insertedId!!)
        }
        if (clusterFile.exists()) clusterFile.delete()
    }

    @Test
    fun testDeleteDefaultConfigClusterSynchronizationAndRebootPersistence() {
        val defaultsDir = ConfigService.getDefaultsDirectory()
        val presetName = "cluster_del_test"
        val expectedFile = defaultsDir.resolve("$presetName-match.json").toFile()

        // 1. Create a default preset on Node A
        val created = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = presetName,
                program = "FRC",
                configType = "match",
                configJson = """{"version":1,"title":"Cluster Delete Test Preset","fields":[]}""",
                isDefault = false
            )
        )
        assertNotNull(created.id)
        assertTrue(expectedFile.exists(), "Local file should be created upon preset creation")

        // Verify tombstone does not exist initially
        val initialTombstone = transaction {
            DeletedDefaultConfigs.selectAll().where {
                (DeletedDefaultConfigs.name eq presetName) and
                (DeletedDefaultConfigs.program eq "FRC") and
                (DeletedDefaultConfigs.configType eq "match")
            }.count()
        }
        assertEquals(0, initialTombstone, "DeletedDefaultConfigs should not have tombstone before deletion")

        // 2. Node A deletes the preset via ConfigService.deleteDefaultConfig()
        ConfigService.deleteDefaultConfig(created.id!!)

        // Verify local file is immediately removed on the node executing the delete
        assertFalse(expectedFile.exists(), "Local file should be deleted immediately upon deleteDefaultConfig()")

        // Verify tombstone is recorded in the database
        val tombstoneCount = transaction {
            DeletedDefaultConfigs.selectAll().where {
                (DeletedDefaultConfigs.name eq presetName) and
                (DeletedDefaultConfigs.program eq "FRC") and
                (DeletedDefaultConfigs.configType eq "match")
            }.count()
        }
        assertEquals(1, tombstoneCount, "Tombstone must be recorded in DeletedDefaultConfigs")

        // 3. Simulate another cluster node (Node B) that still had the file on disk
        expectedFile.writeText("""{"version":1,"title":"Simulated stale file on Node B"}""")
        assertTrue(expectedFile.exists(), "Simulated file on Node B disk exists")

        // Node B runs syncFromDatabaseToLocalDisk()
        ConfigService.syncFromDatabaseToLocalDisk()

        // Verify Node B purged the file from disk because it's tombstoned/missing
        assertFalse(expectedFile.exists(), "syncFromDatabaseToLocalDisk() must purge tombstoned/missing files from other cluster nodes")

        // 4. Simulate server reboot or update bundle re-introducing the file on disk
        expectedFile.writeText("""{"version":1,"title":"Stale bundle file after reboot"}""")
        assertTrue(expectedFile.exists(), "Simulated bundle file reappeared on disk")

        // Server reboots and calls ensureDefaultConfig()
        ConfigService.ensureDefaultConfig()

        // Verify ensureDefaultConfig() purges the file and does NOT re-create the preset in the DB
        assertFalse(expectedFile.exists(), "ensureDefaultConfig() on boot must delete disk file if it was previously tombstoned")
        val reloadedInDb = transaction {
            DefaultConfigs.selectAll().where {
                (DefaultConfigs.name eq presetName) and
                (DefaultConfigs.program eq "FRC") and
                (DefaultConfigs.configType eq "match")
            }.count()
        }
        assertEquals(0, reloadedInDb, "ensureDefaultConfig() must not re-create deleted presets in DB on reboot")

        // 5. Test recreating the preset intentionally: verify tombstone is cleared
        val recreated = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = presetName,
                program = "FRC",
                configType = "match",
                configJson = """{"version":2,"title":"Intentionally Recreated Preset","fields":[]}""",
                isDefault = false
            )
        )
        assertNotNull(recreated.id)
        assertTrue(expectedFile.exists(), "Local file should exist after intentional recreation")

        val tombstoneAfterRecreate = transaction {
            DeletedDefaultConfigs.selectAll().where {
                (DeletedDefaultConfigs.name eq presetName) and
                (DeletedDefaultConfigs.program eq "FRC") and
                (DeletedDefaultConfigs.configType eq "match")
            }.count()
        }
        assertEquals(0, tombstoneAfterRecreate, "Tombstone must be cleared when preset is intentionally recreated")

        // Cleanup
        ConfigService.deleteDefaultConfig(recreated.id!!)
        if (expectedFile.exists()) expectedFile.delete()
    }

    @Test
    fun testResetToDefaultPreservesSectionHeadersAndAllFieldTypes() {
        val testTeam = 9999
        val configWithAllTypes = """
        {
            "version": 1,
            "title": "Config With All Element Types",
            "fields": [
                { "id": "sec_auto", "label": "Autonomous Phase", "type": "section", "phase": "auto" },
                { "id": "auto_notes", "label": "Auto Notes", "type": "counter", "min": 0, "max": 10, "step": 1, "phase": "auto" },
                { "id": "teleop_header", "label": "Teleoperated Phase", "type": "section", "phase": "teleop" },
                { "id": "teleop_score", "label": "Teleop Score", "type": "number", "min": 0, "max": 100, "step": 1, "phase": "teleop" },
                { "id": "driver_rating", "label": "Driver Skill", "type": "rating", "min": 1, "max": 5, "step": 1, "phase": "teleop" },
                { "id": "coopertition", "label": "Coopertition Met", "type": "checkbox", "phase": "teleop" },
                { "id": "climb_level", "label": "Climb Level", "type": "select", "options": [{"label": "None", "value": "none"}, {"label": "Deep", "value": "deep"}], "phase": "endgame" },
                { "id": "robot_photo", "label": "Robot Photo", "type": "image", "phase": "teleop" },
                { "id": "scout_comments", "label": "Comments", "type": "textarea", "phase": "teleop" },
                { "id": "static_notice", "label": "Inspection Complete", "type": "text", "phase": "teleop" }
            ]
        }
        """.trimIndent()

        val preset = ConfigService.createDefaultConfig(
            DefaultConfigDTO(
                name = "test_all_types_preset",
                program = "FRC",
                configType = "match",
                configJson = configWithAllTypes,
                isDefault = true
            )
        )

        try {
            // Reset team 9999 match config to default
            val resetConfig = ConfigService.resetToDefaultConfig(testTeam, "FRC", "match")
            assertNotNull(resetConfig, "Reset config should not be null")
            val fields = resetConfig.fields
            assertEquals(10, fields.size, "Should have all 10 fields preserved")

            // Verify Section Header 1
            val secAuto = fields.first { it.id == "sec_auto" }
            assertEquals("section", secAuto.type, "Section header type must be preserved as 'section'")
            assertEquals("Autonomous Phase", secAuto.label)
            assertEquals("auto", secAuto.phase)

            // Verify Counter
            val autoNotes = fields.first { it.id == "auto_notes" }
            assertEquals("counter", autoNotes.type)
            assertEquals(0, autoNotes.min)
            assertEquals(10, autoNotes.max)
            assertEquals(1, autoNotes.step)

            // Verify Section Header 2
            val secTeleop = fields.first { it.id == "teleop_header" }
            assertEquals("section", secTeleop.type, "Section header type must be preserved as 'section'")
            assertEquals("Teleoperated Phase", secTeleop.label)
            assertEquals("teleop", secTeleop.phase)

            // Verify Number
            val teleopScore = fields.first { it.id == "teleop_score" }
            assertEquals("number", teleopScore.type)

            // Verify Rating
            val driverRating = fields.first { it.id == "driver_rating" }
            assertEquals("rating", driverRating.type)

            // Verify Checkbox
            val coopertition = fields.first { it.id == "coopertition" }
            assertEquals("checkbox", coopertition.type)

            // Verify Select
            val climbLevel = fields.first { it.id == "climb_level" }
            assertEquals("select", climbLevel.type)
            assertEquals(2, climbLevel.options?.size)

            // Verify Image
            val robotPhoto = fields.first { it.id == "robot_photo" }
            assertEquals("image", robotPhoto.type)

            // Verify Textarea
            val scoutComments = fields.first { it.id == "scout_comments" }
            assertEquals("textarea", scoutComments.type)

            // Verify Text
            val staticNotice = fields.first { it.id == "static_notice" }
            assertEquals("text", staticNotice.type)
        } finally {
            preset.id?.let { ConfigService.deleteDefaultConfig(it) }
        }
    }
}

