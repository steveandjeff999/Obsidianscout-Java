package com.obsidianscout.db

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class DataVersionTest {
    private val testDbFile = File("build/test_data_version_${System.currentTimeMillis()}.db")
    /** (tables written, auth moved, settings evicted) per committed transaction. */
    private val seen = mutableListOf<Triple<Set<String>, Boolean, Boolean>>()

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction { SchemaUtils.create(Users, UserSessions, ScoutingEntries, ChatMessages, AppSettings) }
        DataVersion.install()
        DataVersion.onCommittedChange = { tables, _, auth, settings -> synchronized(seen) { seen.add(Triple(tables, auth, settings)) } }
    }

    @AfterTest
    fun tearDown() {
        DataVersion.onCommittedChange = null
        testDbFile.delete()
    }

    private fun changes(block: () -> Unit): List<Triple<Set<String>, Boolean, Boolean>> {
        synchronized(seen) { seen.clear() }
        block()
        return synchronized(seen) { seen.toList() }
    }

    private fun newUser(): UUID = transaction {
        Users.insertAndGetId {
            it[username] = "u${UUID.randomUUID()}"
            it[passwordHash] = "hash"
            it[teamNumber] = 100
            it[role] = "ADMIN"
            it[createdAt] = Instant.now()
        }.value
    }

    @Test
    fun readsChangeNothing() {
        newUser()
        assertEquals(emptyList(), changes { transaction { ScoutingEntries.selectAll().toList(); Users.selectAll().toList() } })
    }

    @Test
    fun dataWritesMoveTheDataVersion() {
        val user = newUser()
        val seenChanges = changes {
            transaction {
                ScoutingEntries.insert {
                    it[ownerTeamNumber] = 100
                    it[submittedByUserId] = EntityID(user, Users)
                    it[dataJson] = "{}"
                    it[createdAt] = Instant.now()
                    it[updatedAt] = Instant.now()
                }
            }
        }
        assertEquals(listOf(Triple(setOf("scouting_entries"), false, false)), seenChanges)
    }

    @Test
    fun housekeepingTablesAreIgnored() {
        val user = newUser()
        val seenChanges = changes {
            transaction {
                ChatMessages.insert {
                    it[teamNumber] = 100
                    it[groupName] = "general"
                    it[userId] = EntityID(user, Users)
                    it[username] = "u"
                    it[content] = "hi"
                    it[createdAt] = Instant.now()
                }
            }
        }
        assertEquals(emptyList(), seenChanges)
    }

    @Test
    fun userWritesMoveAuthAndSessionTouchesDoNot() {
        val user = newUser()
        val sessionId = UUID.randomUUID()
        val created = changes {
            transaction {
                UserSessions.insert {
                    it[id] = EntityID(sessionId, UserSessions)
                    it[userId] = EntityID(user, Users)
                    it[clientType] = "web"
                    it[createdAt] = Instant.now()
                    it[lastActiveAt] = Instant.now()
                }
            }
        }
        assertEquals(emptyList(), created, "a new session changes no cached data")

        val touched = changes { transaction { UserSessions.update({ UserSessions.id eq sessionId }) { it[lastActiveAt] = Instant.now() } } }
        assertEquals(emptyList(), touched, "touching a session changes no cached data")

        val revoked = changes { transaction { UserSessions.deleteWhere { UserSessions.id eq sessionId } } }
        assertEquals(listOf(Triple(emptySet<String>(), true, false)), revoked, "a revoked session must retire cached session checks")

        val renamed = changes { transaction { Users.update({ Users.id eq user }) { it[username] = "renamed${UUID.randomUUID()}" } } }
        assertEquals(listOf(Triple(setOf("users"), true, false)), renamed)
    }

    @Test
    fun settingsWritesEvictTeamSettings() {
        val seenChanges = changes {
            transaction {
                AppSettings.insert {
                    it[teamNumber] = 100
                    it[program] = "FRC"
                    it[settingsJson] = "{}"
                    it[updatedAt] = Instant.now()
                }
            }
        }
        assertEquals(listOf(Triple(setOf("app_settings"), false, true)), seenChanges)
    }

    @Test
    fun rolledBackWritesChangeNothingButCountAsFailures() {
        val user = newUser()
        val failuresBefore = DataVersion.failures.get()
        val seenChanges = changes {
            runCatching {
                transaction {
                    Users.update({ Users.id eq user }) { it[username] = "never" }
                    error("boom")
                }
            }
        }
        assertEquals(emptyList(), seenChanges)
        assertTrue(DataVersion.failures.get() > failuresBefore)
    }

    @Test
    fun trackerRecordsTablesReadIncludingSubqueries() {
        val user = newUser()
        val tracker = DataVersion.newTracker()
        DataVersion.withTracker(tracker) {
            transaction {
                ScoutingEntries.selectAll().where {
                    ScoutingEntries.submittedByUserId inSubQuery Users.select(Users.id).where { Users.id eq user }
                }.toList()
            }
        }
        assertEquals(setOf("scouting_entries", "users"), tracker.tables.toSet())
        assertFalse(tracker.readIgnored)

        val snapshot = mapOf("scouting_entries" to 4L, "users" to 9L, "app_settings" to 2L, DataVersion.RAW to 1L, DataVersion.ANY to 30L)
        assertEquals(
            mapOf("scouting_entries" to 4L, "users" to 9L, DataVersion.RAW to 1L),
            DataVersion.dependencies(tracker, snapshot),
            "a result depends on the tables it read and raw writes, not on unrelated tables"
        )
    }

    @Test
    fun readingAnIgnoredTableBlocksCaching() {
        val tracker = DataVersion.newTracker()
        DataVersion.withTracker(tracker) { transaction { ChatMessages.selectAll().toList() } }
        assertTrue(tracker.readIgnored)
        assertNull(DataVersion.dependencies(tracker, mapOf(DataVersion.ANY to 1L)))
    }

    @Test
    fun nestedTrackersReportToTheirParentAndCacheHitsCount() {
        val outer = DataVersion.newTracker()
        DataVersion.withTracker(outer) {
            val inner = DataVersion.newTracker()
            DataVersion.withTracker(inner) { transaction { Users.selectAll().toList() } }
            assertEquals(setOf("users"), inner.tables.toSet())
            // A cache hit inside the outer computation reports what that cached value was built from.
            DataVersion.noteRead(AppSettings)
        }
        assertEquals(setOf("users", "app_settings"), outer.tables.toSet())
    }

    @Test
    fun noTrackerMeansNothingRecorded() {
        val tracker = DataVersion.newTracker()
        transaction { Users.selectAll().toList() }
        assertTrue(tracker.tables.isEmpty())
    }
}
