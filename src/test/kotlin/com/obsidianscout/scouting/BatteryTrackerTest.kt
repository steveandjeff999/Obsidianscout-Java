package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.collab.ConfigCollabScope
import com.obsidianscout.db.BatteryTrackers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.*

class BatteryTrackerTest {

    private val testDbFile = File("build/test_battery_tracker_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(BatteryTrackers)
            BatteryTrackers.deleteAll()
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun session(team: Int, username: String = "driver$team") =
        UserSession("u-$team", username, team, "FRC", UserRole.SCOUT)

    @Test
    fun testInventoryAndEventPlansAreScopedToTeam() {
        val ours = session(254)
        val theirs = session(1678)

        assertEquals("{}", BatteryTrackerService.getInventory(ours).dataJson)

        val inventory = """{"batteries":{"b1":{"name":"B1","type":"match","order":0}}}"""
        BatteryTrackerService.saveInventory(ours, BatteryInventoryRequest(inventory))
        assertEquals(inventory, BatteryTrackerService.getInventory(ours).dataJson)
        assertEquals("{}", BatteryTrackerService.getInventory(theirs).dataJson)

        val plan = """{"matches":{"2026casj_qm1":{"skipped":{"b1":{"by":"driver254","at":1}}}}}"""
        val saved = BatteryTrackerService.saveEventPlan(ours, BatteryEventPlanRequest("2026casj", plan))
        assertEquals("driver254", saved.updatedBy)
        assertEquals(plan, BatteryTrackerService.getEventPlan(ours, "2026casj").dataJson)
        assertEquals("{}", BatteryTrackerService.getEventPlan(theirs, "2026casj").dataJson)
        // The event plan and the inventory are separate rows.
        assertEquals(inventory, BatteryTrackerService.getInventory(ours).dataJson)
    }

    @Test
    fun testRejectsBadInput() {
        val ours = session(254)
        assertFailsWith<ApiException> { BatteryTrackerService.saveInventory(ours, BatteryInventoryRequest("not json")) }
        assertFailsWith<ApiException> { BatteryTrackerService.saveInventory(ours, BatteryInventoryRequest("\"" + "x".repeat(70_000) + "\"")) }
        assertFailsWith<ApiException> { BatteryTrackerService.getEventPlan(ours, "bad:key") }
        assertFailsWith<ApiException> { BatteryTrackerService.saveEventPlan(ours, BatteryEventPlanRequest("", "{}")) }
    }

    @Test
    fun testLiveEditScopeRoundTripsAndSaves() {
        val inventory = ConfigCollabScope.Batteries(254, "FRC", "")
        val plan = ConfigCollabScope.Batteries(254, "FRC", "2026casj")
        assertEquals(inventory, ConfigCollabScope.fromRoomKey(inventory.roomKey))
        assertEquals(plan, ConfigCollabScope.fromRoomKey(plan.roomKey))
        assertNotEquals(inventory.roomKey, plan.roomKey)
        assertNotEquals(inventory.roomKey, ConfigCollabScope.Batteries(1678, "FRC", "").roomKey)

        assertEquals("{}", plan.loadRaw())
        plan.persistAs("""{"matches":{},"fields":[]}""", "alex")
        val stored = BatteryTrackerService.getEventPlan(session(254), "2026casj")
        assertEquals("alex", stored.updatedBy)
        assertEquals(stored.dataJson, plan.loadRaw())
    }

    @Test
    fun testMatchKeyRemapRenamesEntriesAndPrefersUsed() {
        val ours = session(254)
        BatteryTrackerService.saveEventPlan(ours, BatteryEventPlanRequest("2026casj",
            """{"matches":{"old_qm1":{"used":"b2"},"new_qm1":{"pinned":"b1"},"x_qm2":{"pinned":"b3"}},"startAt":"b1"}"""))
        BatteryTrackerService.saveInventory(ours, BatteryInventoryRequest("""{"batteries":{"old_qm1":{"name":"weird"}}}"""))

        transaction { BatteryTrackerService.remapMatchKey("old_qm1", "new_qm1") }

        val doc = Json.parseToJsonElement(BatteryTrackerService.getEventPlan(ours, "2026casj").dataJson).jsonObject
        val matches = doc["matches"]!!.jsonObject
        assertEquals(setOf("new_qm1", "x_qm2"), matches.keys)
        assertEquals("b2", matches["new_qm1"]!!.jsonObject["used"].toString().trim('"'))
        assertEquals("\"b1\"", doc["startAt"].toString())
        // The inventory is never touched by match remaps.
        assertTrue(BatteryTrackerService.getInventory(ours).dataJson.contains("old_qm1"))
        assertEquals(setOf("new_qm1", "x_qm2"), transaction { BatteryTrackerService.referencedMatchKeys() })
        assertEquals(setOf("x_qm2"), transaction { BatteryTrackerService.referencedMatchKeys("x_") })
    }

    @Test
    fun testEventMoveMergesPlans() {
        val ours = session(254)
        BatteryTrackerService.saveEventPlan(ours, BatteryEventPlanRequest("2026new", """{"matches":{"a":{"pinned":"b1"},"b":{"pinned":"b1"}}}"""))
        Thread.sleep(5)
        BatteryTrackerService.saveEventPlan(ours, BatteryEventPlanRequest("2026old", """{"matches":{"b":{"pinned":"b2"},"c":{"used":"b3"}}}"""))
        BatteryTrackerService.saveEventPlan(session(1678), BatteryEventPlanRequest("2026old", """{"matches":{"z":{}}}"""))

        transaction { BatteryTrackerService.moveEvent("2026old", "2026new") }

        transaction {
            assertEquals(2, BatteryTrackers.selectAll().count())
            assertTrue(BatteryTrackers.selectAll().none { it[BatteryTrackers.eventKey] == "2026old" })
        }
        val matches = Json.parseToJsonElement(BatteryTrackerService.getEventPlan(ours, "2026new").dataJson).jsonObject["matches"]!!.jsonObject
        assertEquals(setOf("a", "b", "c"), matches.keys)
        // The newer doc (2026old) wins on the shared match key.
        assertTrue(matches["b"].toString().contains("b2"))
        assertTrue(BatteryTrackerService.getEventPlan(session(1678), "2026new").dataJson.contains("\"z\""))
    }
}
