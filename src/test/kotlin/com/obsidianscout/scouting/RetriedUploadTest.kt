package com.obsidianscout.scouting

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.routes.ScoutingEntryRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * A device whose upload timed out sends the same entry again from its offline queue, with the same clientId.
 * The server must hand back the entry it already created instead of storing a second copy.
 */
class RetriedUploadTest {

    private val testDbFile = File("build/test_retried_upload_${System.currentTimeMillis()}.db")
    private val userId = UUID.randomUUID()

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

    private fun session(team: Int = 9999) =
        UserSession(userId = userId.toString(), username = "scout1", teamNumber = team, program = "FRC", role = UserRole.SCOUT)

    private fun matchPayload(note: String) = JsonObject(mapOf(
        "eventKey" to JsonPrimitive("2026test"),
        "targetTeamNumber" to JsonPrimitive(1234),
        "matchKey" to JsonPrimitive("2026test_qm5"),
        "matchNumber" to JsonPrimitive(5),
        "notes" to JsonPrimitive(note),
        "autoScore" to JsonPrimitive(note.length)
    ))

    private fun pitPayload(note: String) = JsonObject(mapOf(
        "eventKey" to JsonPrimitive("2026test"),
        "targetTeamNumber" to JsonPrimitive(1234),
        "notes" to JsonPrimitive(note),
        "weight" to JsonPrimitive(note.length)
    ))

    private fun matchRows() = transaction { ScoutingEntries.selectAll().count() }
    private fun pitRows() = transaction { PitScoutingEntries.selectAll().count() }

    @Test
    fun aRetryWithTheSameClientIdReturnsTheFirstEntry() {
        val config = ScoutingConfig()
        val first = ScoutingService.createEntry(session(), ScoutingEntryRequest(matchPayload("first try"), clientId = "c-1"), config)
        // Different content (say a field the server normalises differently), same upload.
        val retry = ScoutingService.createEntry(session(), ScoutingEntryRequest(matchPayload("first try, resent"), clientId = "c-1"), config)
        assertEquals(first.id, retry.id)
        assertEquals(1, matchRows())
    }

    @Test
    fun differentEntriesAreStillStoredSeparately() {
        val config = ScoutingConfig()
        ScoutingService.createEntry(session(), ScoutingEntryRequest(matchPayload("scout A"), clientId = "a"), config)
        ScoutingService.createEntry(session(), ScoutingEntryRequest(matchPayload("scout B, other view"), clientId = "b"), config)
        ScoutingService.createEntry(session(), ScoutingEntryRequest(matchPayload("no id, also different")), config)
        assertEquals(3, matchRows())
    }

    @Test
    fun identicalContentWithoutAnIdIsStillRecognised() {
        val config = ScoutingConfig()
        val first = ScoutingService.createEntry(session(), ScoutingEntryRequest(matchPayload("same")), config)
        val again = ScoutingService.createEntry(session(), ScoutingEntryRequest(matchPayload("same")), config)
        assertEquals(first.id, again.id)
        assertEquals(1, matchRows())
    }

    @Test
    fun pitRetriesAreRecognisedToo() {
        val config = ScoutingConfig()
        val first = PitScoutingService.createEntry(session(), ScoutingEntryRequest(pitPayload("drivetrain swerve"), clientId = "p-1"), config)
        val retry = PitScoutingService.createEntry(session(), ScoutingEntryRequest(pitPayload("drivetrain swerve!"), clientId = "p-1"), config)
        assertEquals(first.id, retry.id)
        assertEquals(1, pitRows())
    }

    @Test
    fun clientIdsAreScopedPerTeamAndKind() {
        val s = session()
        RecentSubmissions.remember("match", s, "x", "entry-1")
        assertEquals("entry-1", RecentSubmissions.find("match", s, "x"))
        assertNull(RecentSubmissions.find("pit", s, "x"), "another form kind")
        assertNull(RecentSubmissions.find("match", session(team = 1111), "x"), "another team")
        assertNull(RecentSubmissions.find("match", s, null))
        assertNull(RecentSubmissions.find("match", s, "   "))
    }

    @Test
    fun rememberedIdsExpire() {
        val s = session()
        RecentSubmissions.remember("match", s, "old", "entry-old", now = 0L)
        assertNull(RecentSubmissions.find("match", s, "old", now = 25L * 60 * 60 * 1000))
    }
}
