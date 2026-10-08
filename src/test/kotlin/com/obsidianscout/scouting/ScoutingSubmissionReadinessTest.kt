package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.Users
import com.obsidianscout.routes.ScoutingEntryRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Submits the payload an untouched web form produces (see buildPayload/readFieldValue in static/js/scout.js)
 * for every shipped 2026 preset, so a config that would flag valid entries as invalid fails the build.
 */
class ScoutingSubmissionReadinessTest {

    private val testDbFile = File("build/test_submission_readiness_${System.currentTimeMillis()}.db")
    private val userId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        DatabaseFactory.init(DatabaseConfig(type = "sqlite", sqlite = SqliteConfig(file = testDbFile.path)), runMigration = true, isCockroach = false)
        transaction {
            Users.insert {
                it[id] = userId
                it[username] = "scout1"
                it[teamNumber] = 9999
                it[program] = "FRC"
                it[passwordHash] = "hash"
                it[role] = "SCOUT"
                it[createdAt] = Instant.now()
            }
        }
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun session(program: String) =
        UserSession(userId = userId.toString(), username = "scout1", teamNumber = 9999, program = program, role = UserRole.SCOUT)

    private fun presetJson(name: String) = File("config/defaults/$name.json").readText()

    private fun preset(name: String): ScoutingConfig =
        JsonSupport.json.decodeFromString(presetJson(name))

    /** Mirrors the client's default form state: selects show their first option, counters/ratings start at min. */
    private fun untouchedFormPayload(config: ScoutingConfig, matchNumber: Int): JsonObject {
        val data = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        config.fields.forEach { f ->
            when (f.type.lowercase()) {
                "checkbox" -> data[f.id] = JsonPrimitive(false)
                "counter" -> data[f.id] = JsonPrimitive(f.min ?: 0)
                "rating" -> data[f.id] = JsonPrimitive(f.min ?: 1)
                "select" -> f.options.firstOrNull()?.let { data[f.id] = JsonPrimitive(it.value) }
                else -> {} // section/text/textarea/image are omitted when blank
            }
        }
        data["eventKey"] = JsonPrimitive("2026test")
        data["targetTeamNumber"] = JsonPrimitive(1000 + matchNumber)
        data["matchKey"] = JsonPrimitive("2026test_qm$matchNumber")
        data["matchNumber"] = JsonPrimitive(matchNumber)
        return JsonObject(data)
    }

    @Test
    fun untouchedFormsAreAcceptedForEveryShippedPreset() {
        listOf("frc2026" to "FRC", "ftc2026" to "FTC").forEachIndexed { i, (prefix, program) ->
            val s = session(program)
            val match = preset("$prefix-match")
            val pit = preset("$prefix-pit")
            val qual = preset("$prefix-qualitative")

            val m = ScoutingService.createEntry(s, ScoutingEntryRequest(untouchedFormPayload(match, 10 + i)), match)
            assertEquals(1010 + i, m.targetTeamNumber, "$prefix match entry not stored")
            PitScoutingService.createEntry(s, ScoutingEntryRequest(untouchedFormPayload(pit, 20 + i)), pit)
            QualitativeScoutingService.createEntry(s, ScoutingEntryRequest(untouchedFormPayload(qual, 30 + i)), qual)
        }
    }

    @Test
    fun configIsResolvedPerProgram() {
        ConfigService.updateConfig(0, "FRC", presetJson("frc2026-match"))
        ConfigService.updateConfig(0, "FTC", presetJson("ftc2026-match"))

        val ftcConfig = ConfigService.getConfig(9999, "FTC")
        assertEquals(preset("ftc2026-match").fields.map { it.id }, ftcConfig.fields.map { it.id })

        // An FTC form validated against the FRC config is rejected — the routes must pass session.program.
        val ftcPayload = untouchedFormPayload(ftcConfig, 40)
        assertFailsWith<ApiException> {
            ScoutingService.createEntry(session("FTC"), ScoutingEntryRequest(ftcPayload), ConfigService.getConfig(9999, "FRC"))
        }
        ScoutingService.createEntry(session("FTC"), ScoutingEntryRequest(ftcPayload), ftcConfig)
    }
}
