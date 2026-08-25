package com.obsidianscout.admin

import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.SqliteConfig
import com.obsidianscout.db.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

class StorageManagementServiceTest {

    private val testDbFile = File("build/test_storage_mgmt_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        val config = DatabaseConfig(
            type = "sqlite",
            sqlite = SqliteConfig(file = testDbFile.path)
        )
        DatabaseFactory.init(config, runMigration = true, isCockroach = false)
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testStorageOverviewAndTeamUsage() {
        val userUuid = UUID.randomUUID()
        transaction {
            // Seed a test user
            Users.insert {
                it[id] = userUuid
                it[username] = "scout1"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[passwordHash] = "hash"
                it[role] = "SCOUT"
                it[createdAt] = Instant.now()
            }

            // Seed API Event and Matches
            ApiEvents.insert {
                it[id] = UUID.randomUUID()
                it[eventKey] = "2026test"
                it[year] = 2026
                it[name] = "2026 Test Regional"
                it[dataJson] = "{\"event\": \"data\"}"
                it[updatedAt] = Instant.now()
            }

            ApiMatches.insert {
                it[id] = UUID.randomUUID()
                it[matchKey] = "2026test_qm1"
                it[eventKey] = "2026test"
                it[compLevel] = "qm"
                it[matchNumber] = 1
                it[redTeams] = "frc254,frc1678"
                it[blueTeams] = "frc971,frc1323"
                it[dataJson] = "{\"score\": 100}"
                it[updatedAt] = Instant.now()
            }

            // Seed Scouting entries for Team 254
            ScoutingEntries.insert {
                it[id] = UUID.randomUUID()
                it[ownerTeamNumber] = 254
                it[targetTeamNumber] = 1678
                it[program] = "FRC"
                it[eventKey] = "2026test"
                it[matchKey] = "2026test_qm1"
                it[matchNumber] = 1
                it[dataJson] = "{\"auto\": 15, \"teleop\": 45}"
                it[submittedByUserId] = userUuid
                it[createdAt] = Instant.now()
            }

            PitScoutingEntries.insert {
                it[id] = UUID.randomUUID()
                it[ownerTeamNumber] = 254
                it[targetTeamNumber] = 1678
                it[program] = "FRC"
                it[eventKey] = "2026test"
                it[dataJson] = "{\"drive\": \"swerve\", \"weight\": 120}"
                it[submittedByUserId] = userUuid
                it[createdAt] = Instant.now()
            }
        }

        // 1. Verify Storage Overview
        val overview = StorageManagementService.getStorageOverview()
        assertEquals("sqlite", overview.databaseType)
        assertTrue(overview.totalRecords >= 4)
        assertTrue(overview.apiCacheRecords >= 2)
        assertTrue(overview.userScoutingRecords >= 2)

        // 2. Verify Event Cache Storage
        val events = StorageManagementService.getEventCacheStorage()
        val testEvent = events.find { it.eventKey == "2026test" }
        assertNotNull(testEvent)
        assertEquals("2026 Test Regional", testEvent.name)
        assertEquals(2026, testEvent.year)
        assertEquals(1, testEvent.matchCount)
        assertEquals(2, testEvent.userScoutingEntryCount) // 1 match + 1 pit

        // 3. Verify Team Storage Usage
        val teams = StorageManagementService.getTeamStorageUsage()
        val team254 = teams.find { it.teamNumber == 254 && it.program == "FRC" }
        assertNotNull(team254)
        assertEquals(1, team254.matchEntryCount)
        assertEquals(1, team254.pitEntryCount)
        assertEquals(0, team254.qualEntryCount)
        assertEquals(1, team254.userCount)

        // 4. Verify Team Detailed Storage
        val details = StorageManagementService.getTeamDetailedStorage(254, "FRC")
        assertNotNull(details)
        assertEquals(254, details.teamNumber)
        val eventDetail = details.events.find { it.eventKey == "2026test" }
        assertNotNull(eventDetail)
        assertEquals(1, eventDetail.matchCount)
        assertEquals(1, eventDetail.pitCount)

        // 5. Test Clear Event API Cache (Preserving scouting records)
        val clearCacheRes = StorageManagementService.clearEventCache("2026test")
        assertTrue(clearCacheRes.success)
        assertTrue(clearCacheRes.affectedRecords >= 2)

        // Verify API cache is empty for 2026test but user scouting entries remain intact
        val eventsAfter = StorageManagementService.getEventCacheStorage()
        val eventAfter = eventsAfter.find { it.eventKey == "2026test" }
        assertNull(eventAfter)

        val scoutCountAfter = transaction { ScoutingEntries.selectAll().count() }
        assertEquals(1, scoutCountAfter)

        // 6. Test Delete Event User Scouting Data
        val deleteScoutRes = StorageManagementService.deleteEventScoutingData("2026test", 254, "FRC")
        assertTrue(deleteScoutRes.success)
        assertEquals(2, deleteScoutRes.affectedRecords)

        val scoutCountFinal = transaction { ScoutingEntries.selectAll().count() }
        assertEquals(0, scoutCountFinal)
    }

    @Test
    fun testPruningOperations() {
        val userUuid = UUID.randomUUID()
        transaction {
            Users.insert {
                it[id] = userUuid
                it[username] = "scout_prune"
                it[teamNumber] = 999
                it[program] = "FRC"
                it[passwordHash] = "hash"
                it[role] = "SCOUT"
                it[createdAt] = Instant.now()
            }

            // Seed 15 revisions for Team 999
            for (i in 1..15) {
                ConfigRevisions.insert {
                    it[id] = UUID.randomUUID()
                    it[teamNumber] = 999
                    it[program] = "FRC"
                    it[configKind] = "game"
                    it[version] = i
                    it[configJson] = "{\"version\": $i}"
                    it[createdAt] = Instant.now().minus(i.toLong(), ChronoUnit.MINUTES)
                }
            }

            // Seed old and new chat messages
            ChatMessages.insert {
                it[id] = UUID.randomUUID()
                it[teamNumber] = 999
                it[program] = "FRC"
                it[groupName] = "general"
                it[userId] = userUuid
                it[username] = "scout_prune"
                it[content] = "Old message"
                it[createdAt] = Instant.now().minus(120, ChronoUnit.DAYS)
            }
            ChatMessages.insert {
                it[id] = UUID.randomUUID()
                it[teamNumber] = 999
                it[program] = "FRC"
                it[groupName] = "general"
                it[userId] = userUuid
                it[username] = "scout_prune"
                it[content] = "Recent message"
                it[createdAt] = Instant.now().minus(2, ChronoUnit.DAYS)
            }

            // Seed expired session
            UserSessions.insert {
                it[id] = UUID.randomUUID()
                it[userId] = userUuid
                it[clientType] = "web"
                it[deviceName] = "Chrome on Windows"
                it[userAgent] = "Mozilla"
                it[ipAddress] = "127.0.0.1"
                it[createdAt] = Instant.now().minus(40, ChronoUnit.DAYS)
                it[lastActiveAt] = Instant.now().minus(35, ChronoUnit.DAYS)
                it[expiresAt] = Instant.now().minus(5, ChronoUnit.DAYS)
            }
        }

        // Prune revisions (keep latest 5)
        val pruneRevsRes = StorageManagementService.pruneConfigRevisions(keepLatestPerKind = 5)
        assertTrue(pruneRevsRes.success)
        assertEquals(10, pruneRevsRes.affectedRecords)
        val remainingRevs = transaction { ConfigRevisions.selectAll().count() }
        assertEquals(5, remainingRevs)

        // Prune chat older than 90 days
        val pruneChatRes = StorageManagementService.pruneChatMessages(olderThanDays = 90)
        assertTrue(pruneChatRes.success)
        assertEquals(1, pruneChatRes.affectedRecords)
        val remainingChat = transaction { ChatMessages.selectAll().count() }
        assertEquals(1, remainingChat)

        // Prune expired sessions
        val pruneSessionsRes = StorageManagementService.pruneExpiredSessions()
        assertTrue(pruneSessionsRes.success)
        assertEquals(1, pruneSessionsRes.affectedRecords)
        val remainingSessions = transaction { UserSessions.selectAll().count() }
        assertEquals(0, remainingSessions)

        // Vacuum / Reclaim test
        val reclaimRes = StorageManagementService.reclaimDiskSpace()
        assertTrue(reclaimRes.success)
    }
}
