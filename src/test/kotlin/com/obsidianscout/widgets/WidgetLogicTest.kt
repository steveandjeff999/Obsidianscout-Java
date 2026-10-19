package com.obsidianscout.widgets

import com.obsidianscout.auth.UserRole
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.routes.MatchRecord
import com.obsidianscout.scouting.ScoutingAssignmentRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetLogicTest {

    // ── Access ───────────────────────────────────────────────────────────────────────────────────────────

    private fun access(role: UserRole, settings: ApiSettings = ApiSettings(), nonScouting: Boolean = false) =
        WidgetAccess.widgetAccess(WidgetAccess.pageAccess(role, settings, nonScouting))

    @Test
    fun scoutsGetOnlyTheWidgetsForTheirPages() {
        val w = access(UserRole.SCOUT)
        assertTrue(w.getValue("assignments"))
        assertTrue(w.getValue("chat"))
        assertTrue(w.getValue("quick_actions"))
        // Default scout pages don't include matches, predictor or projected rankings.
        assertFalse(w.getValue("next_match"))
        assertFalse(w.getValue("schedule"))
        assertFalse(w.getValue("predictor"))
        assertFalse(w.getValue("rankings"))
        // Drive-team tools need the Non-Scouting Tools opt-in.
        assertFalse(w.getValue("battery"))
        assertFalse(w.getValue("checklist"))
    }

    @Test
    fun analyticsGetMatchWidgets() {
        val w = access(UserRole.ANALYTICS)
        assertTrue(w.getValue("next_match"))
        assertTrue(w.getValue("schedule"))
        assertTrue(w.getValue("predictor"))
        assertTrue(w.getValue("rankings"))
        assertFalse(w.getValue("battery"))
    }

    @Test
    fun nonScoutingToolsFollowThePersonalOptInForEveryRole() {
        for (role in UserRole.entries) {
            assertFalse(access(role).getValue("battery"), "$role without opt-in")
            assertTrue(access(role, nonScouting = true).getValue("battery"), "$role with opt-in")
            assertTrue(access(role, nonScouting = true).getValue("checklist"), "$role with opt-in")
        }
    }

    @Test
    fun teamPageListsAndChatSwitchAreRespected() {
        val settings = ApiSettings(scoutPages = listOf("dashboard", "matches", "chat"), chatEnabled = false)
        val w = access(UserRole.SCOUT, settings)
        assertTrue(w.getValue("next_match"))
        assertFalse(w.getValue("assignments"))
        assertFalse(w.getValue("quick_actions"))
        assertFalse(w.getValue("chat"), "chat is off for the team")
        val superadmin = access(UserRole.SUPERADMIN, settings)
        assertTrue(superadmin.getValue("predictor"), "superadmins see every role page")
        assertFalse(superadmin.getValue("chat"), "chat switch applies to superadmins too")
    }

    @Test
    fun scoutAliasCountsForQuickActions() {
        val w = access(UserRole.SCOUT, ApiSettings(scoutPages = listOf("match-scout")))
        assertTrue(w.getValue("quick_actions"))
    }

    // ── Battery rotation (same fixture as the website and app) ─────────────────────────────────────────────

    @Test
    fun batteryRotationMatchesSharedFixture() {
        val text = javaClass.classLoader.getResource("battery-rotation-cases.json")!!.readText()
        val cases = Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray
        for (raw in cases) {
            val case = raw.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val matches = case.getValue("matches").jsonArray.map { it.jsonPrimitive.content }
            val result = BatteryRotation.computeSchedule(
                case["inventory"] as? JsonObject,
                case["plan"] as? JsonObject,
                matches,
            )
            case.getValue("expected").jsonObject.forEach { (key, value) ->
                val pair = value.jsonArray
                val id = pair[0].takeIf { it !is JsonNull }?.jsonPrimitive?.content
                assertEquals(id, result.getValue(key).batteryId, "$name: $key battery")
                assertEquals(pair[1].jsonPrimitive.content, result.getValue(key).source, "$name: $key source")
            }
        }
    }

    @Test
    fun batteryMatchOrderMatchesTheAppAndWebsite() {
        // Same keys and expected order as the app's battery_tracker_test.dart.
        val keys = mutableListOf("e_sf2m1", "e_f1m1", "e_qm10", "e_qf1m1", "e_sf1m1", "e_qm2", "custom_Practice_2", "e_pr1")
        val level = mapOf("e_sf2m1" to "sf", "e_f1m1" to "f", "e_qm10" to "qm", "e_qf1m1" to "qf", "e_sf1m1" to "sf", "e_qm2" to "qm", "custom_Practice_2" to "", "e_pr1" to "pr")
        val number = mapOf("e_sf2m1" to 1, "e_f1m1" to 1, "e_qm10" to 10, "e_qf1m1" to 1, "e_sf1m1" to 1, "e_qm2" to 2, "custom_Practice_2" to null, "e_pr1" to 1)
        keys.sortWith { a, b -> BatteryRotation.compareMatchOrder(level.getValue(a), number[a], a, level.getValue(b), number[b], b) }
        assertEquals(listOf("e_pr1", "custom_Practice_2", "e_qm2", "e_qm10", "e_qf1m1", "e_sf1m1", "e_sf2m1", "e_f1m1"), keys)
    }

    // ── Schedule ─────────────────────────────────────────────────────────────────────────────────────────

    private fun match(n: Int, red: List<String>, blue: List<String> = listOf("frc1", "frc2", "frc3"), played: Boolean = false, offset: Long = 0) =
        MatchRecord(
            matchKey = "2026test_qm$n", eventKey = "2026test", compLevel = "qm", matchNumber = n,
            scheduledTime = 1_000L * n, actualTime = if (played) 1_000L * n + 60 else null,
            predictedTime = 1_000L * n + offset, scheduleOffsetSeconds = offset,
            redTeams = red, blueTeams = blue, label = "QM $n",
            redScore = if (played) 100 else null, blueScore = if (played) 90 else null,
        )

    @Test
    fun scheduleUsesPredictedTimesAndKeepsAllOurMatches() {
        val matches = (1..40).map { n ->
            match(n, if (n == 3 || n == 38) listOf("frc254", "frc4", "frc5") else listOf("frc6", "frc7", "frc8"), played = n <= 4, offset = 420)
        }
        val s = WidgetSnapshotService.schedule(matches, 254)
        assertEquals(420, s.offsetSeconds)
        val keys = s.matches.map { it.key }
        // The entire schedule, in play order.
        assertEquals((1..40).map { "2026test_qm$it" }, keys)
        val ours = s.matches.first { it.key == "2026test_qm38" }
        assertEquals("red", ours.alliance)
        assertEquals(38_420L, ours.predicted)
        assertEquals(listOf("254", "4", "5"), ours.red)
        assertTrue(s.matches.first { it.key == "2026test_qm4" }.played)
    }

    @Test
    fun scheduleIsInPlayOrderPracticeQualsThenPlayoffRounds() {
        fun m(key: String, level: String, n: Int) = MatchRecord(
            matchKey = "2026test_$key", eventKey = "2026test", compLevel = level, matchNumber = n, label = key,
            redTeams = listOf("frc1"), blueTeams = listOf("frc2"),
        )
        val shuffled = listOf(
            m("f1m2", "f", 2), m("sf1m1", "sf", 1), m("qm10", "qm", 10), m("qf2m1", "qf", 1), m("f1m1", "f", 1),
            m("qm2", "qm", 2), m("pr1", "pr", 1), m("qf1m1", "qf", 1), m("sf2m1", "sf", 1), m("qf1m2", "qf", 2),
        )
        val keys = WidgetSnapshotService.schedule(shuffled.sortedWith(WidgetSnapshotService.playOrder), 254).matches.map { it.key.removePrefix("2026test_") }
        assertEquals(listOf("pr1", "qm2", "qm10", "qf1m1", "qf1m2", "qf2m1", "sf1m1", "sf2m1", "f1m1", "f1m2"), keys)
    }

    @Test
    fun teamNumbersParseFromResolvedKeys() {
        assertEquals(254, WidgetSnapshotService.teamNumberOf("frc254"))
        assertEquals(9254, WidgetSnapshotService.teamNumberOf("frc254b/9254"))
        assertEquals(16072, WidgetSnapshotService.teamNumberOf("ftc16072"))
        assertNull(WidgetSnapshotService.teamNumberOf("frc"))
    }

    // ── Assignments ──────────────────────────────────────────────────────────────────────────────────────

    private fun assignment(id: String, n: Int?, type: String = "MATCH", status: String = "PENDING", time: Long? = null) =
        ScoutingAssignmentRecord(
            id = id, ownerTeamNumber = 254, program = "FRC", eventKey = "2026test", assignedUserId = "u",
            assignedUsername = "scout", assignmentType = type, matchKey = n?.let { "2026test_qm$it" }, matchNumber = n,
            compLevel = n?.let { "qm" }, status = status, createdAt = "", updatedAt = "", predictedTime = time,
        )

    @Test
    fun assignmentsSkipPlayedMatchesAndCountPit() {
        val list = listOf(
            assignment("a3", 3, time = 3_000),
            assignment("a1", 1, time = 1_000),
            assignment("a2", 2, status = "COMPLETED", time = 2_000),
            assignment("a5", 5, time = 5_000),
            assignment("p1", null, type = "PIT"),
            assignment("p2", null, type = "PIT", status = "SKIPPED"),
        )
        // Matches 1 and 3 started more than the 10-minute grace ago.
        val now = 3_000L + 601
        val w = WidgetSnapshotService.summarizeAssignments(list, now)
        assertEquals(listOf("a5"), w.upcoming.map { it.id })
        assertEquals(1, w.pitOpen)
        assertEquals(4, w.remaining)

        val early = WidgetSnapshotService.summarizeAssignments(list, 0)
        assertEquals(listOf("a1", "a3", "a5"), early.upcoming.map { it.id })
    }

    // ── Checklist ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun checklistCountsItemsDueForEachMatch() {
        val template = Json.parseToJsonElement(
            """{"lists":{"pre":{"title":"Pre","order":0,"frequency":"match"},
                "every":{"title":"Every 2","order":1,"frequency":"every_n","every":2},
                "day":{"title":"Daily","order":2,"frequency":"daily"}},
              "fields":[{"id":"a","section":"pre","text":"Bumpers"},{"id":"b","section":"pre","text":"Battery"},
                        {"id":"","section":"pre","text":"no id"},{"id":"c","section":"every","text":"Swap wheels"},
                        {"id":"d","section":"day","text":"Daily check"}]}"""
        ).jsonObject
        val completions = mapOf(
            "m1" to Json.parseToJsonElement("""{"checked":{"a":{},"c":{}}}""").jsonObject,
            "m2" to null,
        )
        val progress = WidgetSnapshotService.checklistProgress(template, completions, listOf("m1", "m2", "m3"), listOf("m1", "m2"))
        assertEquals(WidgetChecklistMatch("m1", 2, 3), progress[0])
        // Our 2nd match: the every-2 list isn't due.
        assertEquals(WidgetChecklistMatch("m2", 0, 2), progress[1])

        val legacy = Json.parseToJsonElement("""{"fields":[{"id":"x","section":"field","text":"Plug in"}]}""").jsonObject
        assertEquals(listOf(WidgetChecklistMatch("m1", 0, 1)), WidgetSnapshotService.checklistProgress(legacy, emptyMap(), emptyList(), listOf("m1")))
        assertTrue(WidgetSnapshotService.checklistProgress(JsonObject(mapOf("fields" to JsonArray(emptyList()))), emptyMap(), emptyList(), listOf("m1")).isEmpty())
    }
}
