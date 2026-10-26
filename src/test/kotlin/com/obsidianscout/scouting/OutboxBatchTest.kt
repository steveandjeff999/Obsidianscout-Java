package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * `POST /api/sync/outbox` must store each item exactly as its single-entry route would, for every program, and must
 * stop at the first retryable failure so the device's queue stays in order.
 */
class OutboxBatchTest {

    private val testDbFile = File("build/test_outbox_batch_${System.currentTimeMillis()}.db")
    private val userId = UUID.randomUUID()

    private val programs = listOf("frc2026" to "FRC", "ftc2026" to "FTC")

    @BeforeTest
    fun setUp() {
        RecentSubmissions.clear()
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
        RecentSubmissions.clear()
    }

    private fun session(program: String) =
        UserSession(userId = userId.toString(), username = "scout1", teamNumber = 9999, program = program, role = UserRole.SCOUT)

    private fun preset(name: String): ScoutingConfig =
        JsonSupport.json.decodeFromString(File("config/defaults/$name.json").readText())

    /** The shipped preset for each form, as the server would load it for this program. */
    private fun presets(prefix: String): (String) -> ScoutingConfig {
        val match = preset("$prefix-match")
        val pit = preset("$prefix-pit")
        val qual = preset("$prefix-qualitative")
        return { form -> when (form) { "pit" -> pit; "qual" -> qual; else -> match } }
    }

    /** What an untouched form submits (see ScoutingSubmissionReadinessTest), for team [team] in match [match]. */
    private fun formData(config: ScoutingConfig, team: Int, match: Int): JsonObject {
        val data = mutableMapOf<String, JsonElement>()
        config.fields.forEach { f ->
            when (f.type.lowercase()) {
                "checkbox" -> data[f.id] = JsonPrimitive(false)
                "counter" -> data[f.id] = JsonPrimitive(f.min ?: 0)
                "rating" -> data[f.id] = JsonPrimitive(f.min ?: 1)
                "select" -> f.options.firstOrNull()?.let { data[f.id] = JsonPrimitive(it.value) }
                else -> {}
            }
        }
        data["eventKey"] = JsonPrimitive("2026test")
        data["targetTeamNumber"] = JsonPrimitive(team)
        data["matchKey"] = JsonPrimitive("2026test_qm$match")
        data["matchNumber"] = JsonPrimitive(match)
        return JsonObject(data)
    }

    private fun single(data: JsonObject) = JsonObject(mapOf("data" to data))

    private fun count(table: UUIDTable, program: Column<String>, prescout: Column<Boolean>, prog: String, isPrescout: Boolean) =
        transaction { table.selectAll().where { (program eq prog) and (prescout eq isPrescout) }.count() }

    private fun matchRows(prog: String, prescout: Boolean = false) =
        count(ScoutingEntries, ScoutingEntries.program, ScoutingEntries.isPrescout, prog, prescout)
    private fun pitRows(prog: String, prescout: Boolean = false) =
        count(PitScoutingEntries, PitScoutingEntries.program, PitScoutingEntries.isPrescout, prog, prescout)
    private fun qualRows(prog: String, prescout: Boolean = false) =
        count(QualitativeScoutingEntries, QualitativeScoutingEntries.program, QualitativeScoutingEntries.isPrescout, prog, prescout)

    private fun everyKind(configs: (String) -> ScoutingConfig, base: Int): List<OutboxBatchItem> {
        val match = configs("match")
        val pit = configs("pit")
        val qual = configs("qual")
        return listOf(
            OutboxBatchItem("m-$base", "match", single(formData(match, base + 1, 1))),
            OutboxBatchItem("pm-$base", "prescout_match", single(formData(match, base + 2, 2))),
            OutboxBatchItem("p-$base", "pit", single(formData(pit, base + 3, 3))),
            OutboxBatchItem("pp-$base", "prescout_pit", single(formData(pit, base + 4, 4))),
            OutboxBatchItem("q-$base", "qual", single(formData(qual, base + 5, 5))),
            OutboxBatchItem("pq-$base", "prescout_qual", single(formData(qual, base + 6, 6))),
            OutboxBatchItem(
                "qb-$base", "qual_batch",
                JsonObject(mapOf("entries" to JsonArray(listOf(formData(qual, base + 7, 7), formData(qual, base + 8, 7)))))
            ),
        )
    }

    @Test
    fun everyKindIsStoredForEveryProgram() {
        programs.forEachIndexed { i, (prefix, program) ->
            val configs = presets(prefix)
            val result = OutboxBatch.process(session(program), OutboxBatchRequest(everyKind(configs, 1000 * (i + 1))), configs)

            result.results.forEach { assertEquals(200, it.status, "$program ${it.id}: ${it.error}") }
            assertEquals(1, matchRows(program), "$program match")
            assertEquals(1, matchRows(program, prescout = true), "$program prescout match")
            assertEquals(1, pitRows(program), "$program pit")
            assertEquals(1, pitRows(program, prescout = true), "$program prescout pit")
            assertEquals(3, qualRows(program), "$program qual + qual batch")
            assertEquals(1, qualRows(program, prescout = true), "$program prescout qual")
        }
    }

    @Test
    fun resendingTheSameBatchStoresNothingNew() {
        val configs = presets("ftc2026")
        val items = everyKind(configs, 500)
        OutboxBatch.process(session("FTC"), OutboxBatchRequest(items), configs)
        val again = OutboxBatch.process(session("FTC"), OutboxBatchRequest(items), configs)

        again.results.forEach { assertEquals(200, it.status, "${it.id}: ${it.error}") }
        assertEquals(1, matchRows("FTC"))
        assertEquals(1, pitRows("FTC"))
        assertEquals(3, qualRows("FTC"))
    }

    @Test
    fun aRejectedItemDoesNotHoldUpTheRest() {
        val configs = presets("frc2026")
        val items = listOf(
            OutboxBatchItem("bad", "no_such_kind", single(formData(configs("match"), 1, 1))),
            OutboxBatchItem("noid", "assignment_status", JsonObject(mapOf("status" to JsonPrimitive("completed")))),
            OutboxBatchItem("ok", "match", single(formData(configs("match"), 2, 2))),
        )
        val results = OutboxBatch.process(session("FRC"), OutboxBatchRequest(items), configs).results

        assertEquals(400, results[0].status)
        assertEquals(400, results[1].status)
        assertEquals(200, results[2].status)
        assertEquals(1, matchRows("FRC"))
    }

    @Test
    fun aServerErrorStopsTheBatchSoTheQueueStaysInOrder() {
        val presets = presets("frc2026")
        val failingPit: (String) -> ScoutingConfig = { form -> if (form == "pit") error("config store down") else presets(form) }
        val items = listOf(
            OutboxBatchItem("first", "match", single(formData(presets("match"), 1, 1))),
            OutboxBatchItem("broken", "pit", single(formData(presets("pit"), 2, 2))),
            OutboxBatchItem("after", "match", single(formData(presets("match"), 3, 3))),
        )
        val results = OutboxBatch.process(session("FRC"), OutboxBatchRequest(items), failingPit).results

        assertEquals(listOf(200, 500, 503), results.map { it.status })
        assertEquals(listOf("first", "broken", "after"), results.map { it.id })
        assertEquals(1, matchRows("FRC"), "the item after the failure must not be stored")
    }

    @Test
    fun oversizedBatchesAreRefused() {
        val configs = presets("frc2026")
        val items = (0..OutboxBatch.MAX_ITEMS).map { OutboxBatchItem("i$it", "match", single(formData(configs("match"), it, it))) }
        val e = assertFailsWith<ApiException> { OutboxBatch.process(session("FRC"), OutboxBatchRequest(items), configs) }
        assertEquals(413, e.status.value)
    }
}
