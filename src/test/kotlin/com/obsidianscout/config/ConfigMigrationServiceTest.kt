package com.obsidianscout.config

import com.obsidianscout.db.*
import com.obsidianscout.routes.ConfigMigrationRequest
import com.obsidianscout.routes.FieldMappingDTO
import kotlinx.serialization.json.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ConfigMigrationServiceTest {

    private val testDbFile = File("build/test_config_migration_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users,
                DefaultConfigs,
                ScoutingConfigs,
                PitScoutingConfigs,
                QualitativeScoutingConfigs,
                ConfigRevisions,
                ScoutingEntries,
                PitScoutingEntries,
                QualitativeScoutingEntries
            )
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
    fun testDetectFieldChanges() {
        val oldConfig = ScoutingConfig(
            version = 1,
            title = "Test Form",
            fields = listOf(
                ScoutingField(id = "autoScore", label = "Auto Score", type = "counter"),
                ScoutingField(id = "endgame", label = "Endgame", type = "select", options = listOf(ScoutingOption("Park", "Park", 5.0)))
            )
        )

        val newConfig = ScoutingConfig(
            version = 2,
            title = "Test Form",
            fields = listOf(
                ScoutingField(id = "autoPoints", label = "Auto Points", type = "counter"),
                ScoutingField(id = "teleopCycles", label = "Teleop Cycles", type = "counter"),
                ScoutingField(id = "endgame", label = "Endgame", type = "select", options = listOf(ScoutingOption("Parked", "parked", 5.0)))
            )
        )

        val (hasChanges, changes) = ConfigMigrationService.detectFieldChanges(oldConfig, newConfig, 9999, "FRC", "game")
        assertTrue(hasChanges)
        assertTrue(changes.any { "Removed/Renamed: autoScore" in it })
        assertTrue(changes.any { "Added: autoPoints" in it })
        assertTrue(changes.any { "Added: teleopCycles" in it })
        assertTrue(changes.any { "Options changed: endgame" in it })
    }

    @Test
    fun testSchemaStatusAndMigrationExecution() {
        val teamNum = 254
        val prog = "FRC"

        // Insert a user for submittedByUserId
        val userId = transaction {
            Users.insertAndGetId {
                it[username] = "scout1"
                it[teamNumber] = teamNum
                it[program] = prog
                it[passwordHash] = "hash"
                it[role] = "SCOUT"
                it[createdAt] = Instant.now()
            }.value
        }

        // Insert older scouting entries with legacy keys "legacyAuto" and "oldNotes"
        val entry1Data = buildJsonObject {
            put("eventKey", "2026test")
            put("matchKey", "2026test_qm1")
            put("matchNumber", 1)
            put("targetTeamNumber", 1111)
            put("legacyAuto", 6)
            put("oldNotes", "Great performance")
            put("endgame", "Park")
        }

        transaction {
            ScoutingEntries.insert {
                it[ownerTeamNumber] = teamNum
                it[program] = prog
                it[targetTeamNumber] = 1111
                it[eventKey] = "2026test"
                it[matchKey] = "2026test_qm1"
                it[matchNumber] = 1
                it[dataJson] = JsonSupport.json.encodeToString(JsonElement.serializer(), entry1Data)
                it[submittedByUserId] = EntityID(userId, Users)
                it[createdAt] = Instant.now()
            }
        }

        // Check schema status
        val status = ConfigMigrationService.getSchemaStatus(teamNum, prog, "game")
        assertEquals(1, status.entryCount)
        assertTrue(status.dataKeys.contains("legacyAuto"))
        assertTrue(status.dataKeys.contains("oldNotes"))
        assertTrue(status.unmatchedDataKeys.contains("legacyAuto"))
        assertTrue(status.unmatchedDataKeys.contains("oldNotes"))

        // Preview migration
        val migrationRequest = ConfigMigrationRequest(
            configKind = "game",
            mappings = listOf(
                FieldMappingDTO(oldKey = "legacyAuto", newKey = "autoScore", action = "map"),
                FieldMappingDTO(oldKey = "oldNotes", newKey = "notes", action = "map"),
                FieldMappingDTO(oldKey = "endgame", newKey = "endgame", action = "map", valueMap = mapOf("Park" to "parked"))
            ),
            defaultValues = mapOf(
                "teleopCycles" to JsonPrimitive(0)
            )
        )

        val preview = ConfigMigrationService.previewMigration(teamNum, prog, migrationRequest)
        assertEquals(1, preview.sampleEntries.size)
        val transformed = preview.sampleEntries.first().after

        assertEquals(6, (transformed["autoScore"] as? JsonPrimitive)?.intOrNull)
        assertEquals("Great performance", (transformed["notes"] as? JsonPrimitive)?.content)
        assertEquals("parked", (transformed["endgame"] as? JsonPrimitive)?.content)
        assertEquals(0, (transformed["teleopCycles"] as? JsonPrimitive)?.intOrNull)
        assertFalse(transformed.containsKey("legacyAuto"))
        assertFalse(transformed.containsKey("oldNotes"))

        // Apply migration
        val result = ConfigMigrationService.applyMigration(teamNum, prog, migrationRequest)
        assertTrue(result.success)
        assertEquals(1, result.count)

        // Verify in DB
        val dbData = transaction {
            val row = ScoutingEntries.selectAll().where {
                (ScoutingEntries.ownerTeamNumber eq teamNum) and (ScoutingEntries.program eq prog)
            }.first()
            JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
        }

        assertEquals(6, (dbData["autoScore"] as? JsonPrimitive)?.intOrNull)
        assertEquals("Great performance", (dbData["notes"] as? JsonPrimitive)?.content)
        assertEquals("parked", (dbData["endgame"] as? JsonPrimitive)?.content)
        assertEquals(0, (dbData["teleopCycles"] as? JsonPrimitive)?.intOrNull)
        assertFalse(dbData.containsKey("legacyAuto"))
        assertFalse(dbData.containsKey("oldNotes"))
    }

    @Test
    fun testRevisionHistoryAndRestore() {
        val teamNum = 9999
        val prog = "FRC"

        val initialConfig = ScoutingConfig(
            version = 1,
            title = "V1 Config",
            fields = listOf(
                ScoutingField(id = "field1", label = "Field 1", type = "counter")
            )
        )

        // 1. Save revision 1
        ConfigMigrationService.saveRevision(
            teamNumber = teamNum,
            program = prog,
            kind = "game",
            config = initialConfig,
            changeSummary = "Initial creation",
            savedByUsername = "admin_user"
        )

        // 2. Save revision 2
        val updatedConfig = ScoutingConfig(
            version = 2,
            title = "V2 Config",
            fields = listOf(
                ScoutingField(id = "field1", label = "Field 1", type = "counter"),
                ScoutingField(id = "field2", label = "Field 2", type = "text")
            )
        )
        ConfigMigrationService.saveRevision(
            teamNumber = teamNum,
            program = prog,
            kind = "game",
            config = updatedConfig,
            changeSummary = "Added: field2",
            savedByUsername = "lead_mentor"
        )

        // 3. List revisions
        val revisions = ConfigMigrationService.listRevisions(teamNum, prog, "game")
        assertEquals(2, revisions.size)
        assertEquals("V2 Config", revisions[0].title)
        assertEquals(2, revisions[0].version)
        assertEquals("lead_mentor", revisions[0].savedByUsername)
        assertEquals("V1 Config", revisions[1].title)
        assertEquals(1, revisions[1].version)
        assertEquals("admin_user", revisions[1].savedByUsername)

        // 4. Get detail of revision 1
        val rev1Id = revisions[1].id
        val rev1Detail = ConfigMigrationService.getRevision(rev1Id, teamNum, prog)
        assertNotNull(rev1Detail)
        assertEquals("V1 Config", rev1Detail.title)
        assertTrue("field1" in rev1Detail.configJson)

        // 5. Restore revision 1
        val restoreResp = ConfigMigrationService.restoreRevision(rev1Id, teamNum, prog, "superadmin")
        assertEquals("V1 Config", restoreResp.config.title)
        assertEquals(1, restoreResp.config.fields.size)

        // Verify active config in ConfigService
        val current = ConfigService.getConfig(teamNum, prog, local = true)
        assertEquals("V1 Config", current.title)
        assertEquals(1, current.fields.size)
        assertEquals("field1", current.fields[0].id)
    }
}
