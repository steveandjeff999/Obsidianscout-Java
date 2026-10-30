package com.obsidianscout.integrations

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.AllianceSelections
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.BatteryTrackers
import com.obsidianscout.db.EntryTombstones
import com.obsidianscout.db.EpaOprHistoryCache
import com.obsidianscout.db.MatchChecklists
import com.obsidianscout.db.MatchPlans
import com.obsidianscout.db.MatchPredictions
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingConfigs
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.SharedLinks
import com.obsidianscout.db.TeamCacheOverrides
import com.obsidianscout.db.Users
import com.obsidianscout.routes.EventRecord
import com.obsidianscout.routes.MatchRecord
import com.obsidianscout.routes.TeamRecord
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One team's edits to the shared event cache must never reach another team, and must survive API sync. */
class TeamCacheOverrideTest {
    private lateinit var testDbFile: File
    private val event = "2026okok"
    private val matchKey = "2026okok_qm1"

    private val alpha = session(1111)
    private val bravo = session(2222)

    private fun session(team: Int) = UserSession("u-$team", "admin$team", team, "FRC", UserRole.ADMIN)

    @BeforeTest
    fun setup() {
        testDbFile = File("build/test_team_cache_overrides_${System.nanoTime()}.db")
        testDbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, AppSettings, ScoutingConfigs, ApiEvents, ApiTeams, ApiMatches, TeamCacheOverrides, EpaOprHistoryCache,
                ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries, ScoutingAssignments, ScoutingAlliances,
                AllianceMemberships, AllianceSelections, SharedLinks, MatchPlans, MatchChecklists, BatteryTrackers,
                MatchPredictions, EntryTombstones
            )
            val now = Instant.now()
            ApiEvents.insert {
                it[eventKey] = event
                it[year] = 2026
                it[eventCode] = "okok"
                it[name] = "Oklahoma Regional"
                it[timezone] = "America/Chicago"
                it[dataJson] = "{\"key\":\"2026okok\"}"
                it[updatedAt] = now
            }
            ApiTeams.insert {
                it[eventKey] = event
                it[teamKey] = "frc1111"
                it[teamNumber] = 1111
                it[name] = "Alpha Robotics"
                it[nickname] = "Alpha"
                it[city] = "Tulsa"
                it[dataJson] = "{\"teamNumber\":1111}"
                it[updatedAt] = now
            }
            ApiMatches.insert {
                it[ApiMatches.matchKey] = this@TeamCacheOverrideTest.matchKey
                it[eventKey] = event
                it[compLevel] = "qm"
                it[setNumber] = 1
                it[matchNumber] = 1
                it[scheduledTime] = 1000L
                it[redTeams] = "[\"frc1\",\"frc2\",\"frc3\"]"
                it[blueTeams] = "[\"frc4\",\"frc5\",\"frc6\"]"
                it[dataJson] = "{\"comp_level\":\"qm\",\"alliances\":{\"red\":{\"score\":50},\"blue\":{\"score\":40}}}"
                it[updatedAt] = now
            }
        }
    }

    @AfterTest
    fun cleanup() {
        testDbFile.delete()
    }

    private fun matchFor(s: UserSession) = IntegrationService.listMatches(event, s.program, s.teamNumber).first()

    private fun sharedMatch() = transaction { ApiMatches.selectAll().where { ApiMatches.matchKey eq matchKey }.first() }

    private fun addEntry(owner: Int, key: String = event, match: String = matchKey): UUID = transaction {
        val user = Users.selectAll().where { Users.teamNumber eq owner }.firstOrNull()?.get(Users.id)
            ?: Users.insertAndGetId {
                it[username] = "scout$owner"
                it[teamNumber] = owner
                it[passwordHash] = "x"
                it[role] = "SCOUT"
                it[createdAt] = Instant.now()
            }
        ScoutingEntries.insertAndGetId {
            it[ownerTeamNumber] = owner
            it[targetTeamNumber] = 1
            it[eventKey] = key
            it[matchKey] = match
            it[matchNumber] = 1
            it[dataJson] = "{}"
            it[submittedByUserId] = user
            it[createdAt] = Instant.now()
        }.value
    }

    private fun entryCount(owner: Int) = transaction {
        ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq owner }.count()
    }

    @Test
    fun `a match edit shows only for the team that made it`() {
        val edited = MatchRecord(
            matchKey = matchKey, eventKey = event, compLevel = "qm", setNumber = 1, matchNumber = 1,
            redTeams = listOf("frc1111", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6")
        )
        TeamCacheOverrideService.saveMatch(alpha, edited)

        assertTrue(matchFor(alpha).redTeams.any { it.contains("1111") })
        assertFalse(matchFor(bravo).redTeams.any { it.contains("1111") })
        assertEquals("[\"frc1\",\"frc2\",\"frc3\"]", sharedMatch()[ApiMatches.redTeams], "the shared row must not change")
    }

    @Test
    fun `an edit keeps fields the team did not change, including scores`() {
        TeamCacheOverrideService.saveMatch(
            alpha,
            MatchRecord(matchKey, event, "qm", 1, 1, redTeams = listOf("frc9", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        )
        val patch = transaction { TeamCacheOverrides.selectAll().first()[TeamCacheOverrides.patchJson] }
        assertTrue("redTeams" in patch)
        assertFalse("blueTeams" in patch, "only changed fields are stored: $patch")
        assertFalse("scheduledTime" in patch, "a time the form left empty is not an edit: $patch")

        val seen = matchFor(alpha)
        assertEquals(50, seen.redScore)
        assertEquals(1000L, seen.scheduledTime)
    }

    @Test
    fun `an API update still reaches fields the team left alone`() {
        TeamCacheOverrideService.saveMatch(
            alpha,
            MatchRecord(matchKey, event, "qm", 1, 1, redTeams = listOf("frc9", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        )
        transaction { ApiMatches.update({ ApiMatches.matchKey eq matchKey }) { it[scheduledTime] = 2000L; it[redTeams] = "[\"frc7\",\"frc2\",\"frc3\"]" } }

        val seen = matchFor(alpha)
        assertEquals(2000L, seen.scheduledTime, "the API's new time arrives")
        assertTrue(seen.redTeams.any { it.contains("frc9") }, "the team's own lineup stays")
        assertTrue(matchFor(bravo).redTeams.any { it.contains("frc7") }, "other teams see the API's lineup")
    }

    @Test
    fun `saving a match back to the shared values removes the override`() {
        val changed = MatchRecord(matchKey, event, "qm", 1, 1, redTeams = listOf("frc9", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        TeamCacheOverrideService.saveMatch(alpha, changed)
        TeamCacheOverrideService.saveMatch(alpha, changed.copy(redTeams = listOf("frc1", "frc2", "frc3")))
        assertEquals(0, transaction { TeamCacheOverrides.selectAll().count() })
    }

    @Test
    fun `deleting a match hides it from that team only`() {
        assertTrue(TeamCacheOverrideService.deleteMatch(alpha, matchKey))

        assertTrue(IntegrationService.listMatches(event, "FRC", 1111).isEmpty())
        assertEquals(1, IntegrationService.listMatches(event, "FRC", 2222).size)
        assertEquals(1, transaction { ApiMatches.selectAll().count() })
        assertNull(transaction { TeamCacheOverrideService.matchRow(matchKey, 1111, "FRC") })
    }

    @Test
    fun `a team's own match stays private to it`() {
        val saved = TeamCacheOverrideService.saveMatch(
            alpha,
            MatchRecord("", event, "qm", 1, 2, scheduledTime = 3000L, redTeams = listOf("frc1", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        )
        assertEquals(2, IntegrationService.listMatches(event, "FRC", 1111).size)
        assertEquals(1, IntegrationService.listMatches(event, "FRC", 2222).size)
        val row = transaction { TeamCacheOverrideService.matchRow(saved.matchKey, 1111, "FRC") }
        assertEquals(3000L, row?.get(ApiMatches.scheduledTime))
    }

    @Test
    fun `renumbering a match moves only that team's scouting`() {
        addEntry(1111)
        addEntry(2222)
        val saved = TeamCacheOverrideService.saveMatch(
            alpha,
            MatchRecord(matchKey, event, "qm", 1, 7, redTeams = listOf("frc1", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        )
        transaction {
            assertEquals(saved.matchKey, ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq 1111 }.first()[ScoutingEntries.matchKey])
            assertEquals(matchKey, ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq 2222 }.first()[ScoutingEntries.matchKey])
        }
        assertEquals(50, matchFor(alpha).redScore, "the renumbered match keeps its result")
    }

    @Test
    fun `a team edit shows only for that team`() {
        TeamCacheOverrideService.saveTeam(alpha, TeamRecord(event, "frc1111", 1111, nickname = "Renamed", city = null))

        assertEquals("Renamed", IntegrationService.listTeams(event, alpha).first().nickname)
        assertEquals("Tulsa", IntegrationService.listTeams(event, alpha).first().city, "a blank field is not an edit")
        assertEquals("Alpha", IntegrationService.listTeams(event, bravo).first().nickname)
    }

    @Test
    fun `deleting and adding teams stays with the team`() {
        TeamCacheOverrideService.deleteTeam(alpha, event, "frc1111")
        TeamCacheOverrideService.saveTeam(bravo, TeamRecord(event, "frc2222", 2222, nickname = "Bravo"))

        assertTrue(IntegrationService.listTeams(event, alpha).isEmpty())
        assertEquals(listOf(1111, 2222), IntegrationService.listTeams(event, bravo).map { it.teamNumber })
        assertEquals(1, transaction { ApiTeams.selectAll().count() })
    }

    @Test
    fun `an event edit shows only for that team`() {
        TeamCacheOverrideService.renameEvent(alpha, event, EventRecord(event, "Our Name For It", 2026, timezone = "America/Chicago"))

        assertEquals("Our Name For It", IntegrationService.getEvent(event, 1111, "FRC")?.name)
        assertEquals("Oklahoma Regional", IntegrationService.getEvent(event, 2222, "FRC")?.name)
        assertEquals("Oklahoma Regional", IntegrationService.getEvent(event)?.name)
    }

    @Test
    fun `a team can't change a shared event's key`() {
        assertFailsWith<ApiException> {
            TeamCacheOverrideService.renameEvent(alpha, event, EventRecord("2026elsewhere", "Oklahoma Regional", 2026))
        }
        assertEquals(1, transaction { ApiEvents.selectAll().where { ApiEvents.eventKey eq event }.count() })
    }

    @Test
    fun `a team's own event is private, and only its data moves on rename`() {
        val own = "2026scrim"
        TeamCacheOverrideService.saveEvent(alpha, EventRecord(own, "Our Scrimmage", 2026))
        TeamCacheOverrideService.saveMatch(alpha, MatchRecord("", own, "qm", 1, 1, redTeams = listOf("frc1"), blueTeams = listOf("frc2")))
        addEntry(1111, own, "${own}_qm1")
        addEntry(2222, own, "${own}_qm1")

        assertEquals(0, transaction { ApiEvents.selectAll().where { ApiEvents.eventKey eq own }.count() })
        assertEquals("Our Scrimmage", IntegrationService.getEvent(own, 1111, "FRC")?.name)
        assertNull(IntegrationService.getEvent(own, 2222, "FRC"))

        TeamCacheOverrideService.renameEvent(alpha, own, EventRecord("2026scrim2", "Our Scrimmage", 2026))

        assertEquals(1, IntegrationService.listMatches("2026scrim2", "FRC", 1111).size)
        transaction {
            val mine = ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq 1111 }.first()
            assertEquals("2026scrim2", mine[ScoutingEntries.eventKey])
            assertEquals("2026scrim2_qm1", mine[ScoutingEntries.matchKey])
            val theirs = ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq 2222 }.first()
            assertEquals(own, theirs[ScoutingEntries.eventKey])
            assertEquals("${own}_qm1", theirs[ScoutingEntries.matchKey])
        }
    }

    @Test
    fun `deleting an event never touches another team's scouting or the shared rows`() {
        addEntry(1111)
        addEntry(2222)

        assertTrue(TeamCacheOverrideService.deleteEvent(alpha, event))

        assertEquals(0, entryCount(1111))
        assertEquals(1, entryCount(2222))
        transaction {
            assertEquals(1, ApiEvents.selectAll().count())
            assertEquals(1, ApiMatches.selectAll().count())
            assertEquals(1, ApiTeams.selectAll().count())
            assertEquals(0, EntryTombstones.selectAll().where { EntryTombstones.kind eq "*" }.count(), "no full re-download for every team")
            assertEquals(1, EntryTombstones.selectAll().where { EntryTombstones.ownerTeamNumber eq 1111 }.count())
        }
    }

    @Test
    fun `a deleted event leaves that team's list but not another's`() {
        val listFor = { s: UserSession -> IntegrationService.listEvents(2026, cachedOnly = true, session = s).map { it.eventKey } }
        // Both teams would see it: they are on its team list.
        TeamCacheOverrideService.saveTeam(bravo, TeamRecord(event, "frc2222", 2222, nickname = "Bravo"))
        assertTrue(event in listFor(alpha))
        assertTrue(event in listFor(bravo))

        TeamCacheOverrideService.deleteEvent(alpha, event)

        assertFalse(event in listFor(alpha))
        assertTrue(event in listFor(bravo))

        TeamCacheOverrideService.saveEvent(alpha, EventRecord(event, "Oklahoma Regional", 2026))
        assertTrue(event in listFor(alpha), "saving it again brings it back")
    }

    @Test
    fun `overrides follow a shared match that changes key`() {
        TeamCacheOverrideService.saveMatch(
            alpha,
            MatchRecord(matchKey, event, "qm", 1, 1, redTeams = listOf("frc9", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        )
        transaction { MatchCanonical.remapMatchKey(matchKey, "2026okok_qm1x") }
        assertEquals("2026okok_qm1x", transaction { TeamCacheOverrides.selectAll().first()[TeamCacheOverrides.recordKey] })
    }

    private fun publishEvent(key: String, eventName: String, zone: String? = null) = transaction {
        val code = key.removePrefix("2026")
        EventTeamCanonical.adoptManualEvents(key, 2026, code)
        ApiEvents.insert {
            it[eventKey] = key
            it[year] = 2026
            it[eventCode] = code
            it[name] = eventName
            it[timezone] = zone
            it[dataJson] = "{\"key\":\"$key\"}"
            it[updatedAt] = Instant.now()
        }
    }

    private fun publishMatch(event: String, key: String, red: String, time: Long?) = transaction {
        ApiMatches.insert {
            it[ApiMatches.matchKey] = key
            it[eventKey] = event
            it[compLevel] = "qm"
            it[setNumber] = 1
            it[matchNumber] = 1
            it[scheduledTime] = time
            it[redTeams] = red
            it[blueTeams] = "[\"frc4\",\"frc5\",\"frc6\"]"
            it[dataJson] = "{\"comp_level\":\"qm\",\"alliances\":{\"red\":{\"score\":70},\"blue\":{\"score\":60}}}"
            it[updatedAt] = Instant.now()
        }
    }

    private fun ownScrimmage(s: UserSession, name: String = "Our Scrimmage"): String {
        TeamCacheOverrideService.saveEvent(s, EventRecord("scrim", name, 2026, eventCode = "scrim", startDate = "2026-03-01"))
        return TeamCacheOverrideService.saveMatch(
            s, MatchRecord("", "scrim", "qm", 1, 1, scheduledTime = 500L, redTeams = listOf("frc1", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        ).matchKey
    }

    @Test
    fun `an event the API publishes merges with a team's own copy`() {
        val manualMatch = ownScrimmage(alpha)
        addEntry(1111, "scrim", manualMatch)
        addEntry(2222, "scrim", manualMatch)

        publishEvent("2026scrim", "Official Scrimmage")
        publishMatch("2026scrim", "2026scrim_qm1", "[\"frc7\",\"frc2\",\"frc3\"]", 900L)

        val event = IntegrationService.getEvent("2026scrim", 1111, "FRC")
        assertEquals("Official Scrimmage", event?.name, "the API wins")
        assertEquals("2026-03-01", event?.startDate, "the team's copy fills what the API left blank")

        val matches = IntegrationService.listMatches("2026scrim", "FRC", 1111)
        assertEquals(1, matches.size, "one match, not a copy of each")
        assertTrue(matches.single().redTeams.any { it.contains("frc7") })
        assertEquals(900L, matches.single().scheduledTime)
        assertEquals(70, matches.single().redScore)

        transaction {
            val mine = ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq 1111 }.first()
            assertEquals("2026scrim", mine[ScoutingEntries.eventKey])
            assertEquals("2026scrim_qm1", mine[ScoutingEntries.matchKey])
            val theirs = ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq 2222 }.first()
            assertEquals("scrim", theirs[ScoutingEntries.eventKey], "another team's rows are never moved")
        }
    }

    @Test
    fun `an event published under the key a team typed merges without moving`() {
        TeamCacheOverrideService.saveEvent(alpha, EventRecord("2026scrim", "Ours", 2026, startDate = "2026-03-01"))

        publishEvent("2026scrim", "Official")

        val event = IntegrationService.getEvent("2026scrim", 1111, "FRC")
        assertEquals("Official", event?.name)
        assertEquals("2026-03-01", event?.startDate)
        val listed = IntegrationService.listEvents(2026, cachedOnly = true, session = alpha).single { it.eventKey == "2026scrim" }
        assertEquals("Official", listed.name)
        assertEquals("2026-03-01", listed.startDate)
    }

    @Test
    fun `edits after a merge win over the API, now and on later syncs`() {
        ownScrimmage(alpha)
        publishEvent("2026scrim", "Official")
        publishMatch("2026scrim", "2026scrim_qm1", "[\"frc7\",\"frc2\",\"frc3\"]", 900L)

        TeamCacheOverrideService.saveMatch(
            alpha, MatchRecord("2026scrim_qm1", "2026scrim", "qm", 1, 1, redTeams = listOf("frc1111", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        )
        TeamCacheOverrideService.renameEvent(alpha, "2026scrim", EventRecord("2026scrim", "Our Name", 2026, startDate = "2026-03-01"))
        transaction {
            ApiMatches.update({ ApiMatches.matchKey eq "2026scrim_qm1" }) { it[scheduledTime] = 1200L; it[redTeams] = "[\"frc8\",\"frc2\",\"frc3\"]" }
            ApiEvents.update({ ApiEvents.eventKey eq "2026scrim" }) { it[name] = "Renamed Upstream" }
        }

        val mine = IntegrationService.listMatches("2026scrim", "FRC", 1111).single()
        assertTrue(mine.redTeams.any { it.contains("1111") }, "the team's edit stays")
        assertEquals(1200L, mine.scheduledTime, "fields it left alone still follow the API")
        assertEquals("Our Name", IntegrationService.getEvent("2026scrim", 1111, "FRC")?.name)
        assertEquals("Renamed Upstream", IntegrationService.getEvent("2026scrim", 2222, "FRC")?.name)
    }

    @Test
    fun `a team's active event follows the merge`() {
        ownScrimmage(alpha)
        val settings = SettingsService.getSettings(1111, "FRC")
        SettingsService.updateSettings(1111, settings.copy(year = 2026, eventCode = "", eventKey = "scrim"))
        val before = SettingsService.getSettings(1111, "FRC").resolvedEventKey()

        publishEvent("2026scrim", "Official")

        if (before == "scrim") assertEquals("2026scrim", SettingsService.getSettings(1111, "FRC").resolvedEventKey())
        assertEquals(1, IntegrationService.listMatches("2026scrim", "FRC", 1111).size)
    }

    @Test
    fun `startup repair merges copies the API already published`() {
        ownScrimmage(alpha)
        transaction {
            ApiEvents.insert {
                it[eventKey] = "2026scrim"; it[year] = 2026; it[eventCode] = "scrim"; it[name] = "Official"
                it[dataJson] = "{\"key\":\"2026scrim\"}"; it[updatedAt] = Instant.now()
            }
        }
        EventTeamCanonical.repairAll()
        assertEquals("Official", IntegrationService.getEvent("2026scrim", 1111, "FRC")?.name)
        assertEquals(1, IntegrationService.listMatches("2026scrim", "FRC", 1111).size)
    }

    @Test
    fun `a superadmin's edits do not reach the main record either`() {
        val root = UserSession("u-root", "root", 0, "FRC", UserRole.SUPERADMIN)
        addEntry(1111)
        TeamCacheOverrideService.saveMatch(
            root, MatchRecord(matchKey, event, "qm", 1, 1, redTeams = listOf("frc9", "frc2", "frc3"), blueTeams = listOf("frc4", "frc5", "frc6"))
        )
        TeamCacheOverrideService.deleteEvent(root, event)

        assertEquals("[\"frc1\",\"frc2\",\"frc3\"]", sharedMatch()[ApiMatches.redTeams])
        assertEquals(1, transaction { ApiEvents.selectAll().count() })
        assertEquals(1, entryCount(1111))
    }

    @Test
    fun `saving over a published event edits it instead of making a second one`() {
        val saved = TeamCacheOverrideService.saveEvent(alpha, EventRecord("okok", "Renamed by hand", 2026))
        assertEquals(event, saved.eventKey)
        assertEquals("Renamed by hand", IntegrationService.getEvent(event, 1111, "FRC")?.name)
        assertEquals("Oklahoma Regional", transaction { ApiEvents.selectAll().single()[ApiEvents.name] })
    }

    @Test
    fun `backups carry a team's overrides`() {
        TeamCacheOverrideService.deleteMatch(alpha, matchKey)
        val backup = transaction { TeamCacheOverrideService.exportBackup(1111, "FRC") }
        assertEquals(1, backup.size)
        assertEquals(backup.single(), TeamCacheOverrideService.fromCsv(TeamCacheOverrideService.csvHeaders.zip(TeamCacheOverrideService.csvRow(backup.single())).toMap()))

        transaction { TeamCacheOverrides.deleteAll() }
        transaction { TeamCacheOverrideService.importBackup(backup, { it }, { it }) }

        assertTrue(IntegrationService.listMatches(event, "FRC", 1111).isEmpty())
        assertEquals(1, IntegrationService.listMatches(event, "FRC", 2222).size)
    }
}
