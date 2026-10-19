package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.collab.ConfigCollabScope
import com.obsidianscout.collab.ConfigSyncEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.MatchChecklists
import com.obsidianscout.db.Users
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.*

class MatchChecklistTest {

    private val testDbFile = File("build/test_match_checklist_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, AppSettings, MatchChecklists)
            Users.deleteAll()
            MatchChecklists.deleteAll()
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun session(team: Int, username: String = "driver$team") =
        UserSession("u-$team", username, team, "FRC", UserRole.SCOUT)

    @Test
    fun testNonScoutingToolsPreferenceDefaultsOffAndToggles() {
        val user = transaction {
            AuthService.createUser(
                callerSession = UserSession("seed", "superadmin", 0, "FRC", UserRole.SUPERADMIN),
                username = "drive_coach",
                teamNumber = 254,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SCOUT
            )
        }
        assertFalse(user.nonScoutingToolsEnabled, "Non-scouting tools must be off by default")

        val self = UserSession(user.id, user.username, user.teamNumber, user.program, user.role)
        val enabled = AuthService.updateUser(self, user.id, null, null, null, newNonScoutingToolsEnabled = true)
        assertTrue(enabled.nonScoutingToolsEnabled)

        // Updating an unrelated preference must not reset it.
        val unrelated = AuthService.updateUser(self, user.id, null, null, null, newLocalAiEnabled = true)
        assertTrue(unrelated.nonScoutingToolsEnabled)

        val disabled = AuthService.updateUser(self, user.id, null, null, null, newNonScoutingToolsEnabled = false)
        assertFalse(disabled.nonScoutingToolsEnabled)
        assertEquals(false, AuthService.getUserById(user.id)?.nonScoutingToolsEnabled)
    }

    @Test
    fun testTemplateAndMatchCompletionsAreScopedToTeam() {
        val ours = session(254)
        val theirs = session(1678)

        assertEquals("{}", MatchChecklistService.getTemplate(ours).dataJson)

        val template = """{"sections":[{"id":"pre","title":"Pre-Match","items":[{"id":"a","text":"Battery"}]}]}"""
        MatchChecklistService.saveTemplate(ours, MatchChecklistTemplateRequest(template))
        assertEquals(template, MatchChecklistService.getTemplate(ours).dataJson)
        assertEquals("{}", MatchChecklistService.getTemplate(theirs).dataJson)

        val done = """{"checked":{"a":{"by":"driver254","at":1}},"notes":""}"""
        val saved = MatchChecklistService.saveMatch(ours, MatchChecklistUpdateRequest("2026casj", "2026casj_qm1", done))
        assertEquals("driver254", saved.updatedBy)
        assertEquals(done, MatchChecklistService.getMatch(ours, "2026casj", "2026casj_qm1").dataJson)
        assertEquals("{}", MatchChecklistService.getMatch(theirs, "2026casj", "2026casj_qm1").dataJson)

        // Overwrites the same match rather than adding a second row.
        MatchChecklistService.saveMatch(ours, MatchChecklistUpdateRequest("2026casj", "2026casj_qm1", "{}"))
        val list = MatchChecklistService.listForEvent(ours, "2026casj")
        assertEquals(1, list.size)
        assertEquals("{}", list.single().dataJson)
        assertTrue(MatchChecklistService.listForEvent(theirs, "2026casj").isEmpty())
    }

    @Test
    fun testLiveEditScopeRoundTripsAndSaves() {
        val template = ConfigCollabScope.Checklist(254, "FRC", "", MatchChecklistService.TEMPLATE_KEY)
        val match = ConfigCollabScope.Checklist(254, "FRC", "2026casj", "2026casj_qm1")
        // Relayed sockets rebuild the scope from its room key on the owning node.
        assertEquals(template, ConfigCollabScope.fromRoomKey(template.roomKey))
        assertEquals(match, ConfigCollabScope.fromRoomKey(match.roomKey))
        assertNotEquals(template.roomKey, ConfigCollabScope.Checklist(1678, "FRC", "", MatchChecklistService.TEMPLATE_KEY).roomKey)

        assertEquals("{}", match.loadRaw())
        match.persistAs("""{"checked":{},"notes":"hi","fields":[]}""", "alex")
        val stored = MatchChecklistService.getMatch(session(254), "2026casj", "2026casj_qm1")
        assertEquals("alex", stored.updatedBy)
        assertEquals(stored.dataJson, match.loadRaw())
    }

    @Test
    fun testConcurrentChecksMergeOnServer() {
        var n = 0
        val base = ConfigSyncEngine.keyed(Json.parseToJsonElement("""{"checked":{},"notes":""}""").jsonObject) { "k${n++}" }
        val alex = Json.parseToJsonElement("""[{"op":"set","path":["checked","a"],"value":{"by":"alex","at":1}}]""").jsonArray
        val sam = Json.parseToJsonElement("""[{"op":"set","path":["checked","b"],"value":{"by":"sam","at":2}}]""").jsonArray
        (alex + sam).forEach(ConfigSyncEngine::validate)
        val merged = ConfigSyncEngine.applyOps(ConfigSyncEngine.applyOps(base, alex), sam)
        val checked = merged.doc["checked"]!!.jsonObject
        assertEquals(setOf("a", "b"), checked.keys)
    }

    @Test
    fun testRejectsInvalidInput() {
        val s = session(254)
        assertFailsWith<ApiException> {
            MatchChecklistService.saveMatch(s, MatchChecklistUpdateRequest("2026casj", "qm1", "not json"))
        }
        assertFailsWith<ApiException> {
            MatchChecklistService.saveMatch(s, MatchChecklistUpdateRequest("2026casj", MatchChecklistService.TEMPLATE_KEY, "{}"))
        }
        assertFailsWith<ApiException> {
            MatchChecklistService.getMatch(s, "", "qm1")
        }
        // ':' would break collab room keys.
        assertFailsWith<ApiException> {
            MatchChecklistService.getMatch(s, "2026casj", "qm:1")
        }
    }

    @Test
    fun testToggleItemPreservesOtherFieldsAndChecks() {
        val s = session(254, "alex")
        MatchChecklistService.saveMatch(
            s,
            MatchChecklistUpdateRequest("2026casj", "2026casj_qm1", """{"checked":{"existing":{"by":"sam","at":10}},"notes":"keep me"}"""),
        )
        val afterCheck = MatchChecklistService.toggleItem(s, "2026casj", "2026casj_qm1", "bumpers", checked = true)
        val obj1 = Json.parseToJsonElement(afterCheck.dataJson).jsonObject
        assertEquals("keep me", obj1["notes"]?.toString()?.trim('"'))
        assertEquals(setOf("existing", "bumpers"), obj1["checked"]!!.jsonObject.keys)

        val afterUncheck = MatchChecklistService.toggleItem(s, "2026casj", "2026casj_qm1", "existing", checked = false)
        val obj2 = Json.parseToJsonElement(afterUncheck.dataJson).jsonObject
        assertEquals("keep me", obj2["notes"]?.toString()?.trim('"'))
        assertEquals(setOf("bumpers"), obj2["checked"]!!.jsonObject.keys)
    }
}
