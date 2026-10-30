package com.obsidianscout.routes

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.DatabaseFactory
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * The `/api/mobile` submit routes name teams and matches by the ids `/teams` and `/matches` hand out. Those ids must
 * resolve to the right team for FRC and FTC alike, including when both programs have a team with the same number at
 * an event key they share, and when a team or match has no ApiTeams/ApiMatches row (the id is then its number/key).
 */
class MobileSubmissionResolutionTest {

    private val testDbFile = File("build/test_mobile_resolution_${System.currentTimeMillis()}.db")
    private val eventKey = "2026shared"
    private val frcTeamId = UUID.randomUUID()
    private val ftcTeamId = UUID.randomUUID()
    private val matchId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        DatabaseFactory.init(DatabaseConfig(type = "sqlite", sqlite = SqliteConfig(file = testDbFile.path)), runMigration = true, isCockroach = false)
        val now = Instant.now()
        transaction {
            listOf(frcTeamId to "frc1234", ftcTeamId to "ftc1234").forEach { (rowId, key) ->
                ApiTeams.insert {
                    it[id] = rowId
                    it[ApiTeams.eventKey] = this@MobileSubmissionResolutionTest.eventKey
                    it[teamKey] = key
                    it[teamNumber] = 1234
                    it[name] = key
                    it[dataJson] = "{\"key\":\"$key\"}"
                    it[updatedAt] = now
                }
            }
            ApiMatches.insert {
                it[id] = matchId
                it[matchKey] = "${this@MobileSubmissionResolutionTest.eventKey}_qm12"
                it[ApiMatches.eventKey] = this@MobileSubmissionResolutionTest.eventKey
                it[compLevel] = "qm"
                it[matchNumber] = 12
                it[redTeams] = "ftc1234"
                it[blueTeams] = "ftc5678"
                it[dataJson] = "{}"
                it[updatedAt] = now
            }
            ApiEvents.insert {
                it[id] = eventId
                it[ApiEvents.eventKey] = this@MobileSubmissionResolutionTest.eventKey
                it[year] = 2026
                it[eventCode] = "shared"
                it[name] = "Shared Event"
                it[dataJson] = "{}"
                it[updatedAt] = now
            }
        }
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun session(program: String) =
        UserSession(userId = UUID.randomUUID().toString(), username = "scout", teamNumber = 9999, program = program, role = UserRole.SCOUT)

    @Test
    fun teamIdsComeFromTheSessionsOwnProgram() {
        assertEquals(frcTeamId, mobileTeamIds(eventKey, listOf(1234), session("FRC"))[1234])
        assertEquals(ftcTeamId, mobileTeamIds(eventKey, listOf(1234), session("FTC"))[1234])
        assertTrue(mobileTeamIds(eventKey, listOf(4321), session("FTC")).isEmpty())
    }

    @Test
    fun teamsResolveByUuidOrByNumberForBothPrograms() {
        for (program in listOf("FRC", "FTC")) {
            val byUuid = resolveMobileTeam(ftcTeamId.toString(), null, null, session(program))
            assertEquals(MobileTeamRef(1234, eventKey), byUuid, program)

            // `/teams` hands out the number as the id for a team without an ApiTeams row.
            assertEquals(MobileTeamRef(5678, eventKey), resolveMobileTeam("5678", null, eventKey, session(program)), program)
            assertEquals(MobileTeamRef(5678, eventKey), resolveMobileTeam("ftc5678", null, eventKey, session(program)), program)
            assertEquals(MobileTeamRef(42, eventKey), resolveMobileTeam(null, 42, eventKey, session(program)), program)
        }
        assertNull(resolveMobileTeam(UUID.randomUUID().toString(), null, eventKey, session("FTC")), "unknown row id")
        assertNull(resolveMobileTeam("not-a-team", null, eventKey, session("FTC")))
    }

    @Test
    fun matchesResolveByUuidOrByKey() {
        assertEquals("${eventKey}_qm12" to 12, resolveMobileMatch(session("FRC"), matchId.toString(), null, null))
        // `/matches` hands out the key as the id for a match without an ApiMatches row.
        assertEquals("${eventKey}_qm7" to 7, resolveMobileMatch(session("FRC"), "${eventKey}_qm7", null, null))
        assertEquals("${eventKey}_qm7" to 9, resolveMobileMatch(session("FRC"), null, "${eventKey}_qm7", 9))
        assertEquals("${eventKey}_qm12" to 12, resolveMobileMatch(session("FRC"), null, "${eventKey}_qm12", null))
        assertNull(resolveMobileMatch(session("FRC"), UUID.randomUUID().toString(), null, null))
        assertNull(resolveMobileMatch(session("FRC"), "", null, null))
    }

    @Test
    fun matchEventComesFromItsRowOrItsKey() {
        assertEquals(eventKey, mobileMatchEventKey("${eventKey}_qm12"))
        assertEquals("2026other", mobileMatchEventKey("2026other_qm3"))
    }

    @Test
    fun eventIdsFromTheEventsListResolveToTheirKey() {
        for (program in listOf("FRC", "FTC")) {
            assertEquals(eventKey, resolveEventKey(eventId.toString(), 9999, program), program)
            assertEquals("2026abcd", resolveEventKey("2026ABCD", 9999, program), program)
        }
    }
}
