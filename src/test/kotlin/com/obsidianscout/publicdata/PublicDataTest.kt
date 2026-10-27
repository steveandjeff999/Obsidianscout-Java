package com.obsidianscout.publicdata

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.*
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.routes.PUBLIC_DATA_NOT_PUBLIC
import com.obsidianscout.routes.PublicGateResult
import com.obsidianscout.routes.checkPublicRequest
import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.scouting.ScoutingService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class PublicDataTest {

    private val testDbFile = File("build/test_public_data_${System.currentTimeMillis()}.db")
    private lateinit var scoutA: UUID
    private lateinit var scoutB: UUID

    private val teamA = 1111
    private val teamB = 2222

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, AppSettings, ScoutingConfigs, PitScoutingConfigs, QualitativeScoutingConfigs, DefaultConfigs,
                ApiEvents, ApiTeams, ApiMatches, ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries,
                ScoutingAlliances, AllianceMemberships
            )
            scoutA = insertUser("alice", teamA)
            scoutB = insertUser("bob", teamB)
        }
        SettingsService.ensureDefaultSettings()
        AllianceService.clearEffectiveSettingsCache()
        PublicDataService.clearDirectoryCache()
        PublicViewContext.local.remove()
    }

    @AfterTest
    fun tearDown() {
        PublicViewContext.local.remove()
        AllianceService.clearEffectiveSettingsCache()
        PublicDataService.clearDirectoryCache()
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun insertUser(name: String, team: Int): UUID = Users.insertAndGetId {
        it[username] = name
        it[teamNumber] = team
        it[program] = "FRC"
        it[passwordHash] = "x"
        it[role] = UserRole.SCOUT.name
        it[createdAt] = Instant.now()
    }.value

    private fun setPublic(team: Int, public: Boolean, includeNotes: Boolean = false, eventCode: String = "aaa", year: Int = 2026) {
        val current = SettingsService.getSettingsRaw(team, "FRC")
        SettingsService.updateSettings(team, current.copy(
            program = "FRC", year = year, eventCode = eventCode, dataPublic = public, dataPublicIncludeNotes = includeNotes
        ))
    }

    private fun insertMatch(owner: Int, user: UUID, event: String, data: String = """{"points":5}""", prescout: Boolean = false) = transaction {
        ScoutingEntries.insert {
            it[ownerTeamNumber] = owner
            it[program] = "FRC"
            it[targetTeamNumber] = 254
            it[eventKey] = event
            it[matchKey] = "${event}_qm1"
            it[matchNumber] = 1
            it[dataJson] = data
            it[submittedByUserId] = user
            it[createdAt] = Instant.now()
            it[isPrescout] = prescout
        }
    }

    private fun insertPit(owner: Int, user: UUID, event: String) = transaction {
        PitScoutingEntries.insert {
            it[ownerTeamNumber] = owner
            it[program] = "FRC"
            it[targetTeamNumber] = 254
            it[eventKey] = event
            it[dataJson] = "{}"
            it[submittedByUserId] = user
            it[createdAt] = Instant.now()
        }
    }

    private fun insertQual(owner: Int, user: UUID, event: String) = transaction {
        QualitativeScoutingEntries.insert {
            it[ownerTeamNumber] = owner
            it[program] = "FRC"
            it[targetTeamNumber] = 254
            it[eventKey] = event
            it[dataJson] = "{}"
            it[submittedByUserId] = user
            it[createdAt] = Instant.now()
        }
    }

    private fun allyTeams(vararg teams: Int) = transaction {
        val allianceId = ScoutingAlliances.insertAndGetId {
            it[name] = "Test alliance"
            it[ownerTeamNumber] = teams.first()
            it[program] = "FRC"
            it[createdAt] = Instant.now()
            it[updatedAt] = Instant.now()
        }
        teams.forEachIndexed { index, team ->
            AllianceMemberships.insert {
                it[AllianceMemberships.allianceId] = allianceId
                it[teamNumber] = team
                it[program] = "FRC"
                it[status] = if (index == 0) "ADMIN" else "ACCEPTED"
                it[invitedAt] = Instant.now()
                it[active] = true
            }
        }
    }

    private fun <T> asPublic(context: PublicViewContext, block: () -> T): T {
        PublicViewContext.local.set(context)
        try {
            return block()
        } finally {
            PublicViewContext.local.remove()
        }
    }

    private fun allowed(result: PublicGateResult): PublicViewContext =
        (result as? PublicGateResult.Allowed)?.context ?: fail("Expected the request to be allowed, got $result")

    // ---- Gate ----

    @Test
    fun writesAreRejected() {
        setPublic(teamA, true)
        val result = checkPublicRequest(HttpMethod.Post, "/scouting", "$teamA", "_", null)
        assertEquals(HttpStatusCode.MethodNotAllowed, (result as PublicGateResult.Refused).status)
    }

    @Test
    fun pathsOffTheAllowlistAreNotFound() {
        setPublic(teamA, true)
        listOf("/assignments/my", "/match-checklist/event", "/custom-analytics/reports", "/shares/team", "/settings/test-api").forEach { path ->
            val result = checkPublicRequest(HttpMethod.Get, path, "$teamA", "_", null)
            assertEquals(HttpStatusCode.NotFound, (result as PublicGateResult.Refused).status, path)
        }
    }

    @Test
    fun privateTeamIsNotFoundAndTurningSharingOffHidesItAtOnce() {
        val before = checkPublicRequest(HttpMethod.Get, "/scouting", "$teamA", "_", null)
        assertEquals(PUBLIC_DATA_NOT_PUBLIC, (before as PublicGateResult.Refused).message)

        setPublic(teamA, true, includeNotes = true)
        val context = allowed(checkPublicRequest(HttpMethod.Get, "/scouting", "$teamA", "2025XYZ", null))
        assertEquals(teamA, context.teamNumber)
        assertEquals("2025xyz", context.eventKey)
        assertTrue(context.includeNotes)

        setPublic(teamA, false)
        val after = checkPublicRequest(HttpMethod.Get, "/scouting", "$teamA", "_", null)
        assertEquals(PUBLIC_DATA_NOT_PUBLIC, (after as PublicGateResult.Refused).message)
    }

    @Test
    fun programMustMatchTheSharingTeam() {
        setPublic(teamA, true)
        val result = checkPublicRequest(HttpMethod.Get, "/scouting", "$teamA", "_", "FTC")
        assertEquals(PUBLIC_DATA_NOT_PUBLIC, (result as PublicGateResult.Refused).message)
    }

    @Test
    fun globalDefaultsNeverMakeATeamPublic() {
        // A superadmin (team 0) ticking the box must not publish every team that has no settings row of its own.
        val defaults = SettingsService.getSettingsRaw(0, "FRC")
        SettingsService.updateSettings(0, defaults.copy(program = "FRC", dataPublic = true))
        val result = checkPublicRequest(HttpMethod.Get, "/scouting", "$teamB", "_", null)
        assertEquals(PUBLIC_DATA_NOT_PUBLIC, (result as PublicGateResult.Refused).message)
        assertEquals(PUBLIC_DATA_NOT_PUBLIC, (checkPublicRequest(HttpMethod.Get, "/scouting", "0", "_", null) as PublicGateResult.Refused).message)
    }

    @Test
    fun readsThatCallOutsideApisOnlyTakeEventsTheTeamShares() {
        setPublic(teamA, true, eventCode = "aaa", year = 2026)
        insertMatch(teamA, scoutA, "2026bbb")
        fun check(path: String, event: String?) = checkPublicRequest(HttpMethod.Get, path, "$teamA", "2026ccc", null, event)

        assertEquals(HttpStatusCode.NotFound, (check("/stats/history", "2026zzz") as PublicGateResult.Refused).status)
        assertEquals(HttpStatusCode.NotFound, (check("/matches/projected-rankings", "2026zzz") as PublicGateResult.Refused).status)
        allowed(check("/stats/history", "2026bbb"))   // the team has entries there
        allowed(check("/stats/history", "2026AAA"))   // the team's own current event
        allowed(check("/matches/predict-all", "2026ccc")) // the event the visitor picked
        allowed(check("/stats/history", null))        // falls back to the picked event
        allowed(check("/scouting", "2026zzz"))        // cached reads never call out
    }

    // ---- Own data only, event from the URL ----

    @Test
    fun publicViewNeverIncludesAlliancePartnersData() {
        setPublic(teamA, true)
        allyTeams(teamA, teamB)
        insertMatch(teamA, scoutA, "2026aaa")
        insertMatch(teamB, scoutB, "2026aaa")
        val session = UserSession(PublicDataService.PUBLIC_USER_ID, "public", teamA, "FRC", UserRole.ANALYTICS, publicView = true)

        val signedIn = ScoutingService.listEntries(session.copy(publicView = false), all = true)
        assertEquals(setOf(teamA, teamB), signedIn.map { it.ownerTeamNumber }.toSet(), "the team itself still sees its alliance data")

        val public = asPublic(PublicViewContext(teamA, "FRC", "", false)) {
            ScoutingService.listEntries(session, all = true)
        }
        assertEquals(setOf(teamA), public.map { it.ownerTeamNumber }.toSet())
    }

    @Test
    fun eventFromUrlOverridesSettingsWithoutPoisoningTheCache() {
        setPublic(teamA, true, eventCode = "aaa", year = 2026)
        val context = PublicViewContext(teamA, "FRC", "2025bbb", false)

        val overridden = asPublic(context) { AllianceService.getEffectiveSettings(teamA, "FRC") }
        assertEquals("2025bbb", overridden.resolvedEventKey())
        assertEquals("2025bbb", asPublic(context) { SettingsService.getSettings(teamA, "FRC") }.resolvedEventKey())

        // The shared per-node cache still holds the team's real event.
        assertEquals("2026aaa", AllianceService.getEffectiveSettings(teamA, "FRC").resolvedEventKey())
        // Other teams' settings are never touched.
        assertEquals(SettingsService.getSettingsRaw(teamB, "FRC"), asPublic(context) { SettingsService.getSettings(teamB, "FRC") })
    }

    // ---- Sanitizing ----

    @Test
    fun sanitizeHidesNamesSecretsAndTypedNotesButKeepsCounts() {
        val input = JsonSupport.json.parseToJsonElement(
            """
            {
              "tba_key": "secret", "apiKeys": {"tbaKey": "secret"},
              "entries": [{
                "scoutName": "Alice", "username": "alice", "teamNumber": 254,
                "data": {"driveNotes": "drops a lot", "speaker_notes": 4, "auto_comment": "fast", "drivetrain": "swerve"},
                "dataJson": "{\"driveNotes\":\"drops a lot\",\"points\":5}"
              }]
            }
            """.trimIndent()
        )
        val out = PublicDataService.sanitize(input, includeNotes = false, freeTextIds = setOf("driveNotes")).jsonObject
        assertFalse("tba_key" in out)
        assertFalse("apiKeys" in out)
        val entry = out["entries"]!!.jsonArray[0].jsonObject
        assertEquals("Scout", entry["scoutName"]!!.jsonPrimitive.content)
        assertEquals("Scout", entry["username"]!!.jsonPrimitive.content)
        assertEquals("254", entry["teamNumber"]!!.jsonPrimitive.content)
        val data = entry["data"]!!.jsonObject
        assertEquals("", data["driveNotes"]!!.jsonPrimitive.content)
        assertEquals("", data["auto_comment"]!!.jsonPrimitive.content)
        assertEquals("4", data["speaker_notes"]!!.jsonPrimitive.content, "2024 game pieces are called notes; counts stay")
        assertEquals("swerve", data["drivetrain"]!!.jsonPrimitive.content)
        val embedded = JsonSupport.json.parseToJsonElement(entry["dataJson"]!!.jsonPrimitive.content) as JsonObject
        assertEquals("", embedded["driveNotes"]!!.jsonPrimitive.content)
        assertEquals("5", embedded["points"]!!.jsonPrimitive.content)

        val withNotes = PublicDataService.sanitize(input, includeNotes = true, freeTextIds = setOf("driveNotes")).jsonObject
        val keptData = withNotes["entries"]!!.jsonArray[0].jsonObject["data"]!!.jsonObject
        assertEquals("drops a lot", keptData["driveNotes"]!!.jsonPrimitive.content)
        assertEquals("Scout", withNotes["entries"]!!.jsonArray[0].jsonObject["scoutName"]!!.jsonPrimitive.content)
    }

    // ---- Directory ----

    @Test
    fun directoryListsOnlySharingTeamsAndFlagsEventsWithoutMatchData() {
        setPublic(teamA, true)
        insertMatch(teamA, scoutA, "2026aaa")
        insertPit(teamA, scoutA, "2026aaa")
        insertPit(teamA, scoutA, "2026ccc")
        insertQual(teamA, scoutA, "2026ccc")
        insertMatch(teamA, scoutA, "2026ccc", prescout = true)
        insertMatch(teamB, scoutB, "2026aaa")

        val directory = PublicDataService.directory()
        assertEquals(listOf(teamA), directory.teams.map { it.teamNumber }, "team B never turned sharing on")
        val events = directory.teams.single().events.associateBy { it.eventKey }
        assertEquals(1, events.getValue("2026aaa").matchEntries)
        assertTrue(events.getValue("2026aaa").analyticsAvailable)
        assertEquals(0, events.getValue("2026ccc").matchEntries, "prescout rows aren't counted")
        assertFalse(events.getValue("2026ccc").analyticsAvailable)
        assertEquals(1, events.getValue("2026ccc").pitEntries)
        assertEquals(1, events.getValue("2026ccc").qualEntries)

        setPublic(teamA, false)
        assertTrue(PublicDataService.directory().teams.isEmpty(), "saving the setting clears the directory cache")
    }

    @Test
    fun directoryReportsWhetherNotesAreShared() {
        setPublic(teamA, true)
        insertQual(teamA, scoutA, "2026aaa")
        assertFalse(PublicDataService.directory().teams.single().notesIncluded, "notes pages stay hidden without notes")

        setPublic(teamA, true, includeNotes = true)
        assertTrue(PublicDataService.directory().teams.single().notesIncluded, "changing only the notes setting refreshes the directory")
    }
}
