package com.obsidianscout.scouting

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.db.*
import com.obsidianscout.routes.ScoutingEntryRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

class EntrySyncTest {

    private val testDbFile = File("build/test_entry_sync_${System.currentTimeMillis()}.db")
    private lateinit var userId: UUID
    private lateinit var session: UserSession

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, AppSettings, ApiMatches, ScoutingAlliances, AllianceMemberships,
                ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries, EntryTombstones
            )
            userId = Users.insertAndGetId {
                it[username] = "scout"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[passwordHash] = "hash"
                it[role] = "ADMIN"
                it[createdAt] = Instant.now()
            }.value
        }
        session = UserSession(userId = userId.toString(), username = "scout", teamNumber = 254, program = "FRC", role = UserRole.ADMIN)
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    /** Inserts an entry as if it had been written an hour ago, so it falls outside the cursor overlap. */
    private fun insertOld(team: Int, match: Int, auto: Int, owner: Int = 254): String = transaction {
        val old = Instant.now().minus(1, ChronoUnit.HOURS)
        ScoutingEntries.insertAndGetId {
            it[ownerTeamNumber] = owner
            it[program] = "FRC"
            it[targetTeamNumber] = team
            it[eventKey] = "2026test"
            it[matchKey] = "2026test_qm$match"
            it[matchNumber] = match
            it[dataJson] = """{"eventKey":"2026test","matchKey":"2026test_qm$match","matchNumber":$match,"targetTeamNumber":$team,"auto":$auto}"""
            it[submittedByUserId] = EntityID(userId, Users)
            it[createdAt] = old
            it[updatedAt] = old
        }.value.toString()
    }

    private fun ids(response: JsonObject): Set<String> =
        response["entries"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()

    @Test
    fun firstSyncIsFullAndLaterSyncsCarryOnlyChanges() {
        val a = insertOld(1678, 1, 3)
        val b = insertOld(118, 1, 5)
        insertOld(4414, 2, 1, owner = 9999) // another team's entry, not visible

        val first = EntrySync.changes(session, "match", null, compact = false)
        assertTrue(first["full"]!!.jsonPrimitive.boolean)
        assertEquals(setOf(a, b), ids(first))
        assertEquals(2, first["total"]!!.jsonPrimitive.long)
        val cursor = first["cursor"]!!.jsonPrimitive.content

        val unchanged = EntrySync.changes(session, "match", cursor, compact = false)
        assertFalse(unchanged["full"]!!.jsonPrimitive.boolean)
        assertEquals(emptySet(), ids(unchanged), "nothing changed, so nothing is re-sent")

        val config = ScoutingConfig()
        ScoutingService.updateEntry(
            session, a,
            ScoutingEntryRequest(buildJsonObject {
                put("eventKey", "2026test"); put("matchKey", "2026test_qm1"); put("matchNumber", 1)
                put("targetTeamNumber", 1678); put("auto", 7)
            }),
            config
        )
        ScoutingService.deleteEntry(session, b)

        val delta = EntrySync.changes(session, "match", cursor, compact = false)
        assertFalse(delta["full"]!!.jsonPrimitive.boolean)
        assertEquals(setOf(a), ids(delta))
        assertEquals(listOf(b), delta["deleted"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, delta["total"]!!.jsonPrimitive.long)
        val edited = delta["entries"]!!.jsonArray.first().jsonObject
        assertEquals("7", edited["data"]!!.jsonObject["auto"]!!.jsonPrimitive.content)
    }

    @Test
    fun rowsWrittenBeforeTheColumnExistedUseCreatedAt() {
        val a = insertOld(1678, 1, 3)
        val cursor = EntrySync.changes(session, "match", null, compact = false)["cursor"]!!.jsonPrimitive.content
        transaction {
            ScoutingEntries.update({ ScoutingEntries.id eq UUID.fromString(a) }) {
                it[updatedAt] = null
                it[createdAt] = Instant.now()
            }
        }
        assertEquals(setOf(a), ids(EntrySync.changes(session, "match", cursor, compact = false)))
    }

    @Test
    fun bulkChangeAndForeignCursorForceFullSync() {
        insertOld(1678, 1, 3)
        val cursor = EntrySync.changes(session, "match", null, compact = false)["cursor"]!!.jsonPrimitive.content

        val otherTeam = UserSession(userId = userId.toString(), username = "scout", teamNumber = 9999, program = "FRC", role = UserRole.ADMIN)
        assertTrue(EntrySync.changes(otherTeam, "match", cursor, compact = false)["full"]!!.jsonPrimitive.boolean,
            "a cursor issued for a different set of visible teams is not reused")
        assertTrue(EntrySync.changes(session, "match", "garbage", compact = false)["full"]!!.jsonPrimitive.boolean)

        EntrySync.markBulkChange()
        val afterBulk = EntrySync.changes(session, "match", cursor, compact = false)
        assertTrue(afterBulk["full"]!!.jsonPrimitive.boolean)
        assertEquals(1, ids(afterBulk).size)
    }

    @Test
    fun dedupDeletionsReachEveryTeam() {
        val a = insertOld(1678, 1, 3)
        val cursor = EntrySync.changes(session, "match", null, compact = false)["cursor"]!!.jsonPrimitive.content
        transaction {
            ScoutingEntries.deleteWhere { ScoutingEntries.id eq UUID.fromString(a) }
            EntrySync.recordDeletions(EntrySync.KIND_MATCH, listOf(a), 0, "*")
        }
        val delta = EntrySync.changes(session, "match", cursor, compact = false)
        assertEquals(listOf(a), delta["deleted"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun compactFormRoundTrips() {
        insertOld(1678, 1, 3)
        insertOld(118, 2, 5)
        transaction {
            ScoutingEntries.insert {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 33
                it[eventKey] = "2026test"
                it[matchKey] = "2026test_qm3"
                it[matchNumber] = 3
                it[dataJson] = """{"auto":null,"notes":"fast","climb":true,"nested":{"a":[1,2]}}"""
                it[submittedByUserId] = EntityID(userId, Users)
                it[createdAt] = Instant.now()
            }
        }
        val plain = EntrySync.changes(session, "match", null, compact = false)["entries"]!!.jsonArray.map { it.jsonObject }
        val compact = EntrySync.changes(session, "match", null, compact = true)
        val cols = compact["cols"]!!.jsonArray.map { it.jsonPrimitive.content }
        val keys = compact["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
        val unpacked = EntrySync.unpack(cols, keys, compact["rows"]!!.jsonArray)
        assertEquals(plain, unpacked)

        val withNull = unpacked.first { it["targetTeamNumber"] == JsonPrimitive(33) }
        assertEquals(JsonNull, withNull["data"]!!.jsonObject["auto"], "an explicit null survives")
        assertFalse(withNull["data"]!!.jsonObject.containsKey("matchKey"), "a missing key stays missing")

        val plainBytes = JsonSupport.json.encodeToString(JsonArray.serializer(), JsonArray(plain)).length
        val compactBytes = compact["rows"].toString().length + compact["cols"].toString().length + compact["keys"].toString().length
        assertTrue(compactBytes < plainBytes, "compact ($compactBytes) should be smaller than plain ($plainBytes)")
    }

    @Test
    fun eventScopeKeepsThatEventAndPrescout() {
        val here = insertOld(1678, 1, 3)
        val elsewhere = transaction {
            ScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 1678
                it[eventKey] = "2026other"
                it[matchKey] = "2026other_qm1"
                it[dataJson] = "{}"
                it[submittedByUserId] = EntityID(userId, Users)
                it[createdAt] = Instant.now()
            }.value.toString()
        }
        val prescout = transaction {
            ScoutingEntries.insertAndGetId {
                it[ownerTeamNumber] = 254
                it[program] = "FRC"
                it[targetTeamNumber] = 1678
                it[eventKey] = "2026other"
                it[matchKey] = "2026other_qm2"
                it[dataJson] = "{}"
                it[submittedByUserId] = EntityID(userId, Users)
                it[createdAt] = Instant.now()
                it[isPrescout] = true
            }.value.toString()
        }
        val scoped = EntrySync.changes(session, "match", null, compact = false, eventKeyParam = "2026test")
        assertEquals(setOf(here, prescout), ids(scoped))
        assertEquals(2, scoped["total"]!!.jsonPrimitive.long)
        val cursor = scoped["cursor"]!!.jsonPrimitive.content
        assertTrue(EntrySync.changes(session, "match", cursor, compact = false)["full"]!!.jsonPrimitive.boolean,
            "an event-scoped cursor is not reused for the unscoped list")
        assertTrue(elsewhere !in ids(scoped))
    }
}
