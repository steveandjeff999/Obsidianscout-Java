package com.obsidianscout.db

import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.SqliteConfig
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import kotlin.test.*

/** An invalidation written on one node reaches the others once, and never comes back to the node that wrote it. */
class LiveInvalidationRelayTest {
    private val dbFile = File("build/test_live_invalidations_${System.currentTimeMillis()}.db")
    private val received = mutableListOf<LiveInvalidationRelay.Delivery>()
    private val originalNode = LiveInvalidationRelay.nodeId

    @BeforeTest
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        DatabaseFactory.init(DatabaseConfig(type = "sqlite", sqlite = SqliteConfig(file = dbFile.absolutePath)), runMigration = true, isCockroach = false)
        LiveInvalidationRelay.enabled = true
    }

    @AfterTest
    fun tearDown() {
        LiveInvalidationRelay.stop()
        LiveInvalidationRelay.nodeId = originalNode
        runCatching { DatabaseFactory.close() }
        dbFile.delete()
    }

    private fun onNode(id: String, now: Instant = Instant.now()) {
        LiveInvalidationRelay.nodeId = id
        LiveInvalidationRelay.poll(now) { received.add(it) }
    }

    private fun rowCount() = transaction { LiveInvalidations.selectAll().count() }

    @Test
    fun reachesOtherNodesOnceAndNotItsOwn() {
        LiveInvalidationRelay.nodeId = "node-a"
        LiveInvalidationRelay.publish(LiveInvalidationRelay.Target(teamNumber = 5454, program = "FRC"), listOf("scouting"))
        LiveInvalidationRelay.flush()

        onNode("node-a")
        assertTrue(received.isEmpty(), "the writing node already told its own sockets")

        onNode("node-b")
        onNode("node-b")
        val delivery = received.single()
        assertEquals(5454, delivery.target.teamNumber)
        assertEquals("FRC", delivery.target.program)
        assertEquals(listOf("scouting"), delivery.topics)
    }

    @Test
    fun aBurstForOneTeamIsOneRow() {
        LiveInvalidationRelay.nodeId = "node-a"
        val team = LiveInvalidationRelay.Target(teamNumber = 5454, program = "FRC")
        repeat(25) { LiveInvalidationRelay.publish(team, listOf("scouting")) }
        LiveInvalidationRelay.publish(team, listOf("pit-scouting"))
        LiveInvalidationRelay.flush()

        assertEquals(1, rowCount())
        onNode("node-b")
        assertEquals(setOf("scouting", "pit-scouting"), received.single().topics.toSet())
    }

    @Test
    fun keepsEventTargets() {
        LiveInvalidationRelay.nodeId = "node-a"
        LiveInvalidationRelay.publish(LiveInvalidationRelay.Target(eventKey = "2026cada"), listOf("schedule", "rankings"))
        LiveInvalidationRelay.flush()

        onNode("node-b")
        val event = received.single()
        assertEquals("2026cada", event.target.eventKey)
        assertNull(event.target.teamNumber)
    }

    private fun createUser(name: String, team: Int, program: String): String = transaction {
        Users.insertAndGetId {
            it[username] = name
            it[teamNumber] = team
            it[Users.program] = program
            it[passwordHash] = "hash"
            it[role] = "SCOUT"
            it[createdAt] = Instant.now()
        }.value.toString()
    }

    @Test
    fun aScoutWithNoSocketStillReachesTheirTeam() {
        LiveInvalidationRelay.nodeId = "node-a"
        val scout = createUser("offline_scout", 5454, "FRC")
        val ftcScout = createUser("offline_ftc_scout", 7777, "FTC")

        NotificationWebSocketManager.invalidateTeamsOf(listOf(scout, scout, ftcScout, "not-a-uuid"), listOf("assignments"))
        LiveInvalidationRelay.flush()

        onNode("node-b")
        assertEquals(
            setOf(5454 to "FRC", 7777 to "FTC"),
            received.map { it.target.teamNumber to it.target.program }.toSet()
        )
        assertTrue(received.all { it.topics == listOf("assignments") })
    }

    @Test
    fun oldRowsAreIgnoredAndCleanedUp() {
        LiveInvalidationRelay.nodeId = "node-a"
        val written = Instant.now().minusSeconds(600)
        LiveInvalidationRelay.publish(LiveInvalidationRelay.Target(teamNumber = 1, program = "FRC"), listOf("chat"))
        LiveInvalidationRelay.flush(written)

        onNode("node-b")
        assertTrue(received.isEmpty())
        LiveInvalidationRelay.cleanUp()
        assertEquals(0, rowCount())
    }

    @Test
    fun nothingQueuesWhileDisabled() {
        LiveInvalidationRelay.enabled = false
        LiveInvalidationRelay.publish(LiveInvalidationRelay.Target(), listOf("banners"))
        LiveInvalidationRelay.flush()
        assertEquals(0, rowCount())
    }
}
