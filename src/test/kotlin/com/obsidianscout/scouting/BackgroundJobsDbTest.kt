package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.ChatService
import com.obsidianscout.db.ClusterNotificationLocks
import com.obsidianscout.db.EntryTombstones
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.integrations.ClusterLease
import com.obsidianscout.integrations.SettingsService
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deduplication, assignment reminders and chat avatar URLs against a real SQLite database. */
class BackgroundJobsDbTest {

    private val testDbFile = File("build/test_background_jobs_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, AppSettings, ApiEvents, ApiTeams, ApiMatches, ScoutingAssignments, ScoutingEntries,
                PitScoutingEntries, QualitativeScoutingEntries, EntryTombstones, ClusterNotificationLocks
            )
        }
        SettingsService.ensureDefaultSettings()
        ClusterLease.availabilityOverride = { true }
    }

    @AfterTest
    fun tearDown() {
        ClusterLease.availabilityOverride = null
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun addUser(name: String, email: String? = null, picture: String? = null): UUID = transaction {
        Users.insertAndGetId {
            it[username] = name
            it[passwordHash] = "hash"
            it[teamNumber] = 254
            it[program] = "FRC"
            it[role] = "SCOUT"
            it[createdAt] = Instant.now()
            it[Users.email] = email
            it[profilePicture] = picture
        }.value
    }

    private fun addMatchEntry(user: UUID, eventKey: String?, json: String, createdAt: Instant): UUID = transaction {
        ScoutingEntries.insertAndGetId {
            it[ownerTeamNumber] = 254
            it[program] = "FRC"
            it[targetTeamNumber] = 1678
            it[ScoutingEntries.eventKey] = eventKey
            it[matchKey] = eventKey?.let { k -> "${k}_qm1" }
            it[dataJson] = json
            it[submittedByUserId] = EntityID(user, Users)
            it[ScoutingEntries.createdAt] = createdAt
        }.value
    }

    private fun addPitEntry(user: UUID, json: String, createdAt: Instant): UUID = transaction {
        PitScoutingEntries.insertAndGetId {
            it[ownerTeamNumber] = 254
            it[program] = "FRC"
            it[targetTeamNumber] = 1678
            it[eventKey] = "2026txhou"
            it[dataJson] = json
            it[submittedByUserId] = EntityID(user, Users)
            it[PitScoutingEntries.createdAt] = createdAt
        }.value
    }

    private fun matchIds(): Set<UUID> = transaction { ScoutingEntries.selectAll().map { it[ScoutingEntries.id].value }.toSet() }
    private fun pitIds(): Set<UUID> = transaction { PitScoutingEntries.selectAll().map { it[PitScoutingEntries.id].value }.toSet() }

    @Test
    fun deduplicationDeletesOnlyLaterIdenticalCopiesPerScout() {
        val scoutA = addUser("scoutA")
        val scoutB = addUser("scoutB")
        val t0 = Instant.parse("2026-03-01T10:00:00Z")

        // Same scout, same match, same data: the later copy goes.
        val first = addMatchEntry(scoutA, "2026txhou", """{"auto":3}""", t0)
        val resent = addMatchEntry(scoutA, "2026txhou", """{"auto":3}""", t0.plusSeconds(30))
        // Same scout and match but different data: kept.
        val changed = addMatchEntry(scoutA, "2026txhou", """{"auto":5}""", t0.plusSeconds(60))
        // Another scout with identical data: kept (different scouts are never duplicates).
        val otherScout = addMatchEntry(scoutB, "2026txhou", """{"auto":3}""", t0.plusSeconds(90))
        // No event key (NULL group column): duplicates must still be found, which needs IS NULL matching.
        val nullFirst = addMatchEntry(scoutA, null, """{"auto":1}""", t0)
        val nullResent = addMatchEntry(scoutA, null, """{"auto":1}""", t0.plusSeconds(10))
        // A lone entry: never touched.
        val lone = addMatchEntry(scoutB, "2026cttd", """{"auto":2}""", t0)

        val pitFirst = addPitEntry(scoutA, """{"drive":"swerve"}""", t0)
        val pitResent = addPitEntry(scoutA, """{"drive":"swerve"}""", t0.plusSeconds(5))

        DeduplicationScheduler.runCleanup()

        assertEquals(setOf(first, changed, otherScout, nullFirst, lone), matchIds())
        assertEquals(setOf(pitFirst), pitIds())
        val tombstones = transaction { EntryTombstones.selectAll().map { it[EntryTombstones.entryId] }.toSet() }
        assertTrue(tombstones.containsAll(listOf(resent.toString(), nullResent.toString(), pitResent.toString())))
    }

    private fun addMatch(key: String, startsInSeconds: Long) = transaction {
        ApiMatches.insert {
            it[matchKey] = key
            it[eventKey] = "2026txhou"
            it[compLevel] = "qm"
            it[matchNumber] = key.substringAfter("qm").toIntOrNull()
            it[scheduledTime] = Instant.now().epochSecond + startsInSeconds
            it[redTeams] = "[]"
            it[blueTeams] = "[]"
            it[dataJson] = "{}"
            it[updatedAt] = Instant.now()
        }
    }

    private fun addAssignment(user: UUID, matchKey: String, status: String = "PENDING"): UUID = transaction {
        ScoutingAssignments.insertAndGetId {
            it[ownerTeamNumber] = 254
            it[program] = "FRC"
            it[eventKey] = "2026txhou"
            it[assignedUserId] = EntityID(user, Users)
            it[assignmentType] = "MATCH"
            it[ScoutingAssignments.matchKey] = matchKey
            it[matchNumber] = matchKey.substringAfter("qm").toIntOrNull()
            it[targetTeamNumber] = 1678
            it[ScoutingAssignments.status] = status
            it[createdAt] = Instant.now()
            it[updatedAt] = Instant.now()
        }.value
    }

    private fun pushSentAt(id: UUID): Instant? = transaction {
        ScoutingAssignments.selectAll().where { ScoutingAssignments.id eq id }.first()[ScoutingAssignments.pushReminderSentAt]
    }

    @Test
    fun remindersGoOutOnceAndOnlyInsideTheWindow() {
        val scout = addUser("scout") // no email, so only the push reminder applies
        addMatch("2026txhou_qm1", startsInSeconds = 5 * 60)        // inside the 15-minute default window
        addMatch("2026txhou_qm2", startsInSeconds = 3 * 60 * 60)   // too far ahead
        addMatch("2026txhou_qm3", startsInSeconds = -60 * 60)      // long over
        val due = addAssignment(scout, "2026txhou_qm1")
        val tooEarly = addAssignment(scout, "2026txhou_qm2")
        val past = addAssignment(scout, "2026txhou_qm3")
        val done = addAssignment(scout, "2026txhou_qm1", status = "COMPLETED")

        ScoutingReminderScheduler.checkAndSendReminders()

        val firstSent = assertNotNull(pushSentAt(due))
        assertNull(pushSentAt(tooEarly))
        assertNull(pushSentAt(past))
        assertNull(pushSentAt(done))

        // A second sweep doesn't send (or re-stamp) the same reminder.
        ScoutingReminderScheduler.checkAndSendReminders()
        assertEquals(firstSent, pushSentAt(due))
    }

    @Test
    fun remindersSkipWhenAnotherNodeHoldsTheLease() {
        val scout = addUser("scout")
        addMatch("2026txhou_qm1", startsInSeconds = 5 * 60)
        val due = addAssignment(scout, "2026txhou_qm1")
        assertEquals(
            ClusterLease.Result.ACQUIRED,
            ClusterLease.tryAcquire("assignment-reminders", 150, holder = "other-node")
        )

        ScoutingReminderScheduler.checkAndSendReminders()

        assertNull(pushSentAt(due))
    }

    @Test
    fun avatarUrlsFollowPictureChangesWithoutReloadingUnchangedOnes() {
        val withPicture = addUser("pic", picture = "data:image/jpeg;base64,AAAA")
        val withoutPicture = addUser("nopic")

        val first = transaction { ChatService.avatarUrls(listOf(withPicture, withoutPicture)) }
        assertEquals(ChatService.avatarUrl(withPicture, "data:image/jpeg;base64,AAAA"), first[withPicture])
        assertNull(first[withoutPicture])

        // A new picture of a different length is picked up even with no explicit cache reset (another node's write).
        transaction { Users.update({ Users.id eq withPicture }) { it[profilePicture] = "data:image/jpeg;base64,BBBBBBBB" } }
        val second = transaction { ChatService.avatarUrls(listOf(withPicture)) }
        assertNotEquals(first[withPicture], second[withPicture])
        assertEquals(ChatService.avatarUrl(withPicture, "data:image/jpeg;base64,BBBBBBBB"), second[withPicture])

        // Same length, different picture: caught after forgetAvatar, which the picture update on this node calls.
        transaction { Users.update({ Users.id eq withPicture }) { it[profilePicture] = "data:image/jpeg;base64,CCCCCCCC" } }
        ChatService.forgetAvatar(withPicture)
        val third = transaction { ChatService.avatarUrls(listOf(withPicture)) }
        assertEquals(ChatService.avatarUrl(withPicture, "data:image/jpeg;base64,CCCCCCCC"), third[withPicture])

        // Removing the picture clears the URL.
        transaction { Users.update({ Users.id eq withPicture }) { it[profilePicture] = null } }
        assertNull(transaction { ChatService.avatarUrls(listOf(withPicture)) }[withPicture])
    }
}
