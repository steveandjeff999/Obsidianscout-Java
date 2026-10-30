package com.obsidianscout.integrations

import com.obsidianscout.db.TeamCacheOverrides
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.UserRole
import com.obsidianscout.db.AllianceSelections
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.EpaOprHistoryCache
import com.obsidianscout.db.BatteryTrackers
import com.obsidianscout.db.MatchChecklists
import com.obsidianscout.db.MatchPlans
import com.obsidianscout.db.MatchPredictions
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.SharedLinks
import com.obsidianscout.db.Users
import com.obsidianscout.routes.EventRecord
import com.obsidianscout.routes.MatchRecord
import com.obsidianscout.routes.TeamRecord
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualEventTeamMergeTest {

    private val testDbFile = File("build/test_manual_event_team_${System.currentTimeMillis()}.db")
    private val manualKey = "okok"
    private val apiKey = "2026okok"
    private lateinit var userId: UUID

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, ApiEvents, ApiTeams, ApiMatches, TeamCacheOverrides, ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries,
                ScoutingAssignments, MatchPlans, MatchChecklists, BatteryTrackers, MatchPredictions, AllianceSelections, EpaOprHistoryCache,
                ScoutingAlliances, SharedLinks
            )
            userId = Users.insertAndGetId {
                it[username] = "admin"
                it[passwordHash] = "hash"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[role] = UserRole.ADMIN.name
                it[createdAt] = Instant.now()
            }.value
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun apiEventRow(key: String = apiKey) = transaction {
        ApiEvents.insert {
            it[eventKey] = key
            it[year] = 2026
            it[eventCode] = "okok"
            it[name] = "Green Country Regional"
            it[dataJson] = """{"key":"$key"}"""
            it[updatedAt] = Instant.now()
        }
    }

    /** Writes a team the way a sync does: update the row with that key if present, otherwise insert. */
    private fun apiTeam(event: String, key: String, number: Int, nickname: String? = "API $number", epa: Double? = 50.0) = transaction {
        val existing = ApiTeams.selectAll().where { (ApiTeams.eventKey eq event) and (ApiTeams.teamKey eq key) }.firstOrNull()
        if (existing != null) {
            ApiTeams.update({ ApiTeams.id eq existing[ApiTeams.id] }) {
                it[ApiTeams.nickname] = nickname
                it[ApiTeams.epa] = epa
                it[dataJson] = """{"key":"$key"}"""
            }
            return@transaction
        }
        ApiTeams.insert {
            it[eventKey] = event
            it[teamKey] = key
            it[teamNumber] = number
            it[ApiTeams.nickname] = nickname
            it[city] = null
            it[ApiTeams.epa] = epa
            it[dataJson] = """{"key":"$key"}"""
            it[updatedAt] = Instant.now()
        }
    }

    private fun apiMatch(event: String, number: Int, red: List<String>, blue: List<String>) = transaction {
        ApiMatches.insert {
            it[matchKey] = "${event}_qm$number"
            it[eventKey] = event
            it[compLevel] = "qm"
            it[setNumber] = 1
            it[matchNumber] = number
            it[redTeams] = red.joinToString(",", "[", "]") { "\"$it\"" }
            it[blueTeams] = blue.joinToString(",", "[", "]") { "\"$it\"" }
            it[dataJson] = """{"score":1,"scoreRedFinal":50}"""
            it[updatedAt] = Instant.now()
        }
    }

    /** Every kind of row that hangs off an event, created under [event] for match [matchKey]. */
    private fun childRows(event: String, matchKey: String, stamp: Instant, tag: String) = transaction {
        ScoutingEntries.insert {
            it[ownerTeamNumber] = 254; it[targetTeamNumber] = 1; it[eventKey] = event; it[ScoutingEntries.matchKey] = matchKey
            it[dataJson] = "{}"; it[submittedByUserId] = userId; it[createdAt] = stamp
        }
        PitScoutingEntries.insert {
            it[ownerTeamNumber] = 254; it[eventKey] = event; it[dataJson] = "{}"; it[submittedByUserId] = userId; it[createdAt] = stamp
        }
        QualitativeScoutingEntries.insert {
            it[ownerTeamNumber] = 254; it[eventKey] = event; it[QualitativeScoutingEntries.matchKey] = matchKey
            it[dataJson] = "{}"; it[submittedByUserId] = userId; it[createdAt] = stamp
        }
        ScoutingAssignments.insert {
            it[ownerTeamNumber] = 254; it[eventKey] = event; it[assignedUserId] = userId; it[assignmentType] = "MATCH"
            it[ScoutingAssignments.matchKey] = matchKey; it[createdAt] = stamp; it[updatedAt] = stamp
        }
        MatchPlans.insert {
            it[ownerKey] = "254"; it[eventKey] = event; it[MatchPlans.matchKey] = matchKey; it[planJson] = """{"tag":"$tag"}"""; it[updatedAt] = stamp
        }
        MatchChecklists.insert {
            it[ownerKey] = "254"; it[eventKey] = event; it[MatchChecklists.matchKey] = matchKey; it[dataJson] = """{"tag":"$tag"}"""; it[updatedAt] = stamp
        }
        AllianceSelections.insert {
            it[ownerKey] = "254"; it[eventKey] = event; it[selectionJson] = """{"tag":"$tag"}"""; it[updatedAt] = stamp
        }
        EpaOprHistoryCache.insert {
            it[eventKey] = event; it[oprsJson] = """{"tag":"$tag"}"""; it[epaHistoryJson] = "{}"; it[updatedAt] = stamp
        }
        ScoutingAlliances.insert {
            it[name] = "Alliance $tag"; it[ownerTeamNumber] = 254; it[eventKey] = event; it[createdAt] = stamp; it[updatedAt] = stamp
        }
        SharedLinks.insert {
            it[token] = "tok-$tag"; it[ownerTeamNumber] = 254; it[createdByUsername] = "admin"; it[title] = "Link $tag"
            it[resourceType] = "team"; it[queryConfigJson] = "{}"; it[targetEventKey] = event; it[createdAt] = stamp
        }
    }

    private fun countByEvent(): Map<String, Map<String, Long>> = transaction {
        fun <T : Table> T.counts(col: Column<String?>): Map<String, Long> =
            selectAll().groupBy { it[col] ?: "null" }.mapValues { it.value.size.toLong() }
        fun <T : Table> T.countsNN(col: Column<String>): Map<String, Long> =
            selectAll().groupBy { it[col] }.mapValues { it.value.size.toLong() }
        mapOf(
            "events" to ApiEvents.countsNN(ApiEvents.eventKey),
            "scouting" to ScoutingEntries.counts(ScoutingEntries.eventKey),
            "pit" to PitScoutingEntries.counts(PitScoutingEntries.eventKey),
            "qual" to QualitativeScoutingEntries.counts(QualitativeScoutingEntries.eventKey),
            "assignments" to ScoutingAssignments.countsNN(ScoutingAssignments.eventKey),
            "plans" to MatchPlans.countsNN(MatchPlans.eventKey),
            "checklists" to MatchChecklists.countsNN(MatchChecklists.eventKey),
            "selections" to AllianceSelections.countsNN(AllianceSelections.eventKey),
            "epaCache" to EpaOprHistoryCache.countsNN(EpaOprHistoryCache.eventKey),
            "alliances" to ScoutingAlliances.counts(ScoutingAlliances.eventKey),
            "sharedLinks" to SharedLinks.counts(SharedLinks.targetEventKey),
        )
    }

    private fun childMatchKeys(): Set<String> = transaction {
        buildSet {
            ScoutingEntries.selectAll().forEach { r -> r[ScoutingEntries.matchKey]?.let { add(it) } }
            QualitativeScoutingEntries.selectAll().forEach { r -> r[QualitativeScoutingEntries.matchKey]?.let { add(it) } }
            ScoutingAssignments.selectAll().forEach { r -> r[ScoutingAssignments.matchKey]?.let { add(it) } }
            MatchPlans.selectAll().forEach { r -> add(r[MatchPlans.matchKey]) }
            MatchChecklists.selectAll().forEach { r -> add(r[MatchChecklists.matchKey]) }
        }
    }

    /** Manual rows made before team overrides existed still sit in the shared tables until the API adopts them. */
    private fun legacyManualEvent(key: String, eventName: String) = transaction {
        ApiEvents.insert {
            it[eventKey] = key; it[year] = 2026; it[eventCode] = key.removePrefix("2026"); it[name] = eventName
            it[dataJson] = "{\"manual\":true}"; it[updatedAt] = Instant.now()
        }
    }

    private fun legacyManualTeam(event: String, key: String, number: Int, nick: String) = transaction {
        ApiTeams.insert {
            it[eventKey] = event; it[teamKey] = key; it[teamNumber] = number; it[name] = ""; it[nickname] = nick
            it[dataJson] = "{}"; it[updatedAt] = Instant.now()
        }
    }

    private fun legacyManualMatch(event: String, key: String, number: Int, red: List<String>, blue: List<String>) = transaction {
        val serializer = kotlinx.serialization.builtins.ListSerializer(String.serializer())
        ApiMatches.insert {
            it[matchKey] = key; it[eventKey] = event; it[compLevel] = "qm"; it[setNumber] = 1; it[matchNumber] = number
            it[redTeams] = com.obsidianscout.config.JsonSupport.json.encodeToString(serializer, red)
            it[blueTeams] = com.obsidianscout.config.JsonSupport.json.encodeToString(serializer, blue)
            it[dataJson] = "{}"; it[updatedAt] = Instant.now()
        }
    }

    // ── events ───────────────────────────────────────────────────────────────

    @Test
    fun manualEventMovesOntoSyncedKeyWithEverything() {
        legacyManualEvent(manualKey, "My manual event")
        legacyManualTeam(manualKey, "frc254", 254, "Manual Poofs")
        legacyManualMatch(manualKey, "okok_qm5", 5, listOf("frc254", "frc1", "frc2"), listOf("frc3", "frc4", "frc5"))
        childRows(manualKey, "okok_qm5", Instant.now(), "manual")

        // What an API sync does: adopt the manual copy, then write the API event, team and match.
        transaction {
            EventTeamCanonical.adoptManualEvents(apiKey, 2026, "okok")
            ApiEvents.update({ ApiEvents.eventKey eq apiKey }) { it[dataJson] = """{"key":"$apiKey"}"""; it[name] = "Green Country Regional" }
        }
        apiTeam(apiKey, "frc1706", 1706)
        transaction {
            ApiTeams.update({ (ApiTeams.eventKey eq apiKey) and (ApiTeams.teamKey eq "frc254") }) { it[epa] = 99.0 }
            EventTeamCanonical.deduplicateTeamsForEvent(apiKey)
        }

        val counts = countByEvent()
        counts.forEach { (table, byEvent) -> assertEquals(setOf(apiKey), byEvent.keys, "$table should all be on $apiKey") }
        assertEquals(setOf("${apiKey}_qm5"), childMatchKeys())
        transaction {
            assertEquals(listOf("${apiKey}_qm5"), ApiMatches.selectAll().map { it[ApiMatches.matchKey] })
            assertEquals("Green Country Regional", ApiEvents.selectAll().single()[ApiEvents.name])
            val team = ApiTeams.selectAll().where { ApiTeams.teamKey eq "frc254" }.single()
            assertEquals("Manual Poofs", team[ApiTeams.nickname])
        }
    }

    @Test
    fun repairMergesManualEventIntoExistingSyncedEventKeepingNewerRows() {
        apiEventRow()
        apiTeam(apiKey, "frc254", 254, nickname = "The Cheesy Poofs", epa = 80.0)
        apiMatch(apiKey, 5, listOf("frc254", "frc1", "frc2"), listOf("frc3", "frc4", "frc5"))
        val older = Instant.now().minusSeconds(3600)
        childRows(apiKey, "${apiKey}_qm5", older, "api")

        transaction {
            ApiEvents.insert {
                it[eventKey] = manualKey; it[year] = 2026; it[eventCode] = "okok"; it[name] = "Typed by hand"
                it[dataJson] = "{\"manual\":true}"; it[updatedAt] = Instant.now()
            }
            ApiTeams.insert {
                it[eventKey] = manualKey; it[teamKey] = "frc254"; it[teamNumber] = 254; it[nickname] = "Manual"
                it[city] = "San Jose"; it[dataJson] = "{}"; it[updatedAt] = Instant.now()
            }
            ApiMatches.insert {
                it[matchKey] = "okok_qm5"; it[eventKey] = manualKey; it[compLevel] = "qm"; it[setNumber] = 1; it[matchNumber] = 5
                it[redTeams] = """["frc254","frc1","frc2"]"""; it[blueTeams] = """["frc3","frc4","frc5"]"""
                it[dataJson] = "{}"; it[updatedAt] = Instant.now()
            }
        }
        childRows(manualKey, "okok_qm5", Instant.now(), "manual")

        EventTeamCanonical.repairAll()

        transaction {
            assertEquals(listOf(apiKey), ApiEvents.selectAll().map { it[ApiEvents.eventKey] })
            assertEquals(listOf("${apiKey}_qm5"), ApiMatches.selectAll().map { it[ApiMatches.matchKey] })
            assertTrue(ApiMatches.selectAll().single()[ApiMatches.dataJson].contains("scoreRedFinal"), "API match data wins")
            val team = ApiTeams.selectAll().single()
            assertEquals("The Cheesy Poofs", team[ApiTeams.nickname], "API team fields win")
            assertEquals("San Jose", team[ApiTeams.city], "blank API fields filled from the manual row")
            // Unique per owner/event(/match): the newer (manual) copy is kept.
            assertEquals("""{"tag":"manual"}""", MatchPlans.selectAll().single()[MatchPlans.planJson])
            assertEquals("""{"tag":"manual"}""", MatchChecklists.selectAll().single()[MatchChecklists.dataJson])
            assertEquals("""{"tag":"manual"}""", AllianceSelections.selectAll().single()[AllianceSelections.selectionJson])
            // API-derived cache: the synced event's copy is authoritative.
            assertEquals("""{"tag":"api"}""", EpaOprHistoryCache.selectAll().single()[EpaOprHistoryCache.oprsJson])
            // Non-unique rows are all kept.
            assertEquals(2, ScoutingEntries.selectAll().count())
            assertEquals(2, PitScoutingEntries.selectAll().count())
            assertEquals(2, ScoutingAlliances.selectAll().count())
            assertEquals(2, SharedLinks.selectAll().count())
        }
        countByEvent().forEach { (table, byEvent) -> assertEquals(setOf(apiKey), byEvent.keys, "$table should all be on $apiKey") }
        assertEquals(setOf("${apiKey}_qm5"), childMatchKeys())
        assertEquals(0, EventTeamCanonical.repairAll(), "repair should be idempotent")
    }

    @Test
    fun customManualEventIsLeftAlone() {
        apiEventRow()
        legacyManualEvent("scrimmage", "Fall scrimmage")
        transaction { EventTeamCanonical.adoptManualEvents(apiKey, 2026, "okok") }
        EventTeamCanonical.repairAll()
        transaction { assertEquals(setOf(apiKey, "scrimmage"), ApiEvents.selectAll().map { it[ApiEvents.eventKey] }.toSet()) }
    }

    // ── teams ────────────────────────────────────────────────────────────────

    @Test
    fun manualFtcTeamMergesWithSyncedRow() {
        legacyManualTeam("2026ftcx", "ftc123", 123, "Manual")
        legacyManualMatch("2026ftcx", "2026ftcx_qm1", 1, listOf("ftc123", "ftc124"), listOf("ftc125", "ftc126"))

        // Legacy row from the old frc default, then the API row arrives.
        transaction {
            ApiTeams.insert {
                it[eventKey] = "2026ftcx"; it[teamKey] = "frc777"; it[teamNumber] = 777; it[nickname] = "Old default"
                it[city] = "Tulsa"; it[dataJson] = "{}"; it[updatedAt] = Instant.now()
            }
            ApiMatches.update({ ApiMatches.eventKey eq "2026ftcx" }) { it[redTeams] = """["frc123","frc777"]""" }
        }
        apiTeam("2026ftcx", "ftc123", 123, nickname = "API 123")
        apiTeam("2026ftcx", "ftc777", 777, nickname = "API 777")
        transaction { EventTeamCanonical.deduplicateTeamsForEvent("2026ftcx") }

        transaction {
            val rows = ApiTeams.selectAll().associateBy { it[ApiTeams.teamKey] }
            assertEquals(setOf("ftc123", "ftc777"), rows.keys)
            assertEquals("API 777", rows.getValue("ftc777")[ApiTeams.nickname])
            assertEquals("Tulsa", rows.getValue("ftc777")[ApiTeams.city])
            val red = com.obsidianscout.config.JsonSupport.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(String.serializer()),
                ApiMatches.selectAll().single()[ApiMatches.redTeams]
            )
            assertEquals(listOf("ftc123", "ftc777"), red)
        }
    }

    @Test
    fun frcAndFtcApiTeamsSharingAnEventKeyAreNotMerged() {
        apiTeam("2026shared", "frc123", 123)
        apiTeam("2026shared", "ftc123", 123)
        transaction { EventTeamCanonical.deduplicateTeamsForEvent("2026shared") }
        transaction { assertEquals(2, ApiTeams.selectAll().count()) }
    }

    @Test
    fun bareNumberManualTeamMergesButBTeamKeyDoesNot() {
        transaction {
            ApiTeams.insert {
                it[eventKey] = apiKey; it[teamKey] = "254"; it[teamNumber] = 254; it[nickname] = "Bare"; it[dataJson] = "{}"; it[updatedAt] = Instant.now()
            }
            ApiTeams.insert {
                it[eventKey] = apiKey; it[teamKey] = "frc254b"; it[teamNumber] = 254; it[nickname] = "254B"; it[dataJson] = "{}"; it[updatedAt] = Instant.now()
            }
        }
        apiTeam(apiKey, "frc254", 254, nickname = "The Cheesy Poofs")
        transaction { EventTeamCanonical.deduplicateTeamsForEvent(apiKey) }
        transaction {
            assertEquals(setOf("frc254", "frc254b"), ApiTeams.selectAll().map { it[ApiTeams.teamKey] }.toSet())
        }
    }

    @Test
    fun matchChecklistsFollowMatchKeyRemap() {
        val stamp = Instant.now()
        transaction {
            MatchChecklists.insert {
                it[ownerKey] = "254"; it[eventKey] = apiKey; it[matchKey] = "${apiKey}_qm_s1_m5"; it[dataJson] = "{}"; it[updatedAt] = stamp
            }
            MatchCanonical.remapMatchKey("${apiKey}_qm_s1_m5", "${apiKey}_qm5")
        }
        transaction { assertEquals("${apiKey}_qm5", MatchChecklists.selectAll().single()[MatchChecklists.matchKey]) }
    }
}
