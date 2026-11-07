package com.obsidianscout.integrations

import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.Users
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SettingsService.isEventSyncedByAnotherTeam], which picks the banner a team without API keys sees. */
class SharedEventSyncTest {

    private val testDbFile = File("build/test_shared_event_sync_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, AppSettings, ScoutingAlliances, AllianceMemberships)
        }
        SettingsService.ensureDefaultSettings()
        SettingsService.clearSyncedEventsCache()
    }

    @AfterTest
    fun tearDown() {
        SettingsService.clearSyncedEventsCache()
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun configure(team: Int, eventCode: String, tbaKey: String = "", firstKey: String = "", program: String = "FRC") {
        val current = SettingsService.getSettings(team, program)
        val keys = ApiKeys(tbaKey = tbaKey, firstUsername = if (firstKey.isBlank()) "" else "user$team", firstKey = firstKey)
        SettingsService.updateSettings(
            team,
            current.copy(program = program, year = 2026, eventCode = eventCode, apiKeys = keys)
        )
    }

    private fun shared(team: Int, program: String, eventKey: String) =
        runBlocking { SettingsService.isEventSyncedByAnotherTeam(team, program, eventKey) }

    @Test
    fun keylessTeamAtAnEventAnotherTeamSyncsIsShared() {
        configure(9101, "txhou", tbaKey = "key-9101")
        configure(9102, "txhou")

        assertTrue(shared(9102, "FRC", "2026txhou"))
        // The team doing the syncing isn't reading someone else's data.
        assertFalse(shared(9101, "FRC", "2026txhou"))
    }

    @Test
    fun differentEventIsNotShared() {
        configure(9111, "txhou", tbaKey = "key-9111")
        configure(9112, "txdal")

        assertFalse(shared(9112, "FRC", "2026txdal"))
    }

    @Test
    fun anotherKeylessTeamDoesNotCountAsSharing() {
        configure(9121, "txhou")
        configure(9122, "txhou")

        assertFalse(shared(9122, "FRC", "2026txhou"))
    }

    @Test
    fun ftcTeamAtTheSameKeyDoesNotCountForFrc() {
        configure(9131, "txhou", firstKey = "first-9131", program = "FTC")
        configure(9132, "txhou")

        assertFalse(shared(9132, "FRC", "2026txhou"))
    }

    @Test
    fun frcTeamAtTheSameKeyDoesNotCountForFtc() {
        configure(9133, "ustxcmp", firstKey = "first-9133")
        configure(9134, "ustxcmp", program = "FTC")

        assertFalse(shared(9134, "FTC", "2026ustxcmp"))
    }

    @Test
    fun ftcTeamWithFirstKeySharesWithKeylessFtcTeam() {
        configure(9161, "ustxcmp", firstKey = "first-9161", program = "FTC")
        configure(9162, "ustxcmp", program = "FTC")

        assertTrue(shared(9162, "FTC", "2026ustxcmp"))
        assertFalse(shared(9161, "FTC", "2026ustxcmp"))
    }

    @Test
    fun ftcTeamsSyncingFromFtcScoutOnlyDoNotCountAsSharing() {
        // Both are synced (FTC Scout needs no key), but neither brings FIRST data.
        configure(9171, "ustxcmp", program = "FTC")
        configure(9172, "ustxcmp", program = "FTC")
        assertFalse(shared(9172, "FTC", "2026ustxcmp"))

        // A TBA key is never used for FTC.
        configure(9171, "ustxcmp", tbaKey = "tba-9171", program = "FTC")
        assertFalse(shared(9172, "FTC", "2026ustxcmp"))
    }

    @Test
    fun ownCredentialsDependOnProgram() {
        val tbaOnly = ApiKeys(tbaKey = "tba")
        val firstOnly = ApiKeys(firstUsername = "user", firstKey = "first")
        val firstUserWithoutKey = ApiKeys(firstUsername = "user")

        assertTrue(SettingsService.hasOwnApiCredentials("FRC", tbaOnly))
        assertTrue(SettingsService.hasOwnApiCredentials("FRC", firstOnly))
        assertFalse(SettingsService.hasOwnApiCredentials("FRC", firstUserWithoutKey))
        assertFalse(SettingsService.hasOwnApiCredentials("FRC", ApiKeys()))

        assertFalse(SettingsService.hasOwnApiCredentials("FTC", tbaOnly))
        assertTrue(SettingsService.hasOwnApiCredentials("FTC", firstOnly))
        assertFalse(SettingsService.hasOwnApiCredentials("FTC", firstUserWithoutKey))
    }

    @Test
    fun blankEventIsNeverShared() {
        configure(9141, "txhou", tbaKey = "key-9141")

        assertFalse(shared(9142, "FRC", ""))
    }

    @Test
    fun settingsSaveRefreshesTheCachedAnswer() {
        configure(9151, "txhou")
        configure(9152, "txhou")
        assertFalse(shared(9152, "FRC", "2026txhou"))

        // Another team adds a key: the next banner load must see it without waiting for the cache to expire.
        configure(9151, "txhou", tbaKey = "key-9151")
        assertTrue(shared(9152, "FRC", "2026txhou"))

        // And moves to a different event.
        configure(9151, "txdal", tbaKey = "key-9151")
        assertFalse(shared(9152, "FRC", "2026txhou"))
    }

    @Test
    fun cachedAnswerIsReusedUntilSettingsChange() {
        configure(9191, "txhou", tbaKey = "key-9191")
        configure(9192, "txhou")
        val before = SettingsService.syncedEventsBuilds.get()

        repeat(5) { assertTrue(shared(9192, "FRC", "2026txhou")) }
        assertEquals(1, SettingsService.syncedEventsBuilds.get() - before)

        configure(9192, "txhou")
        assertTrue(shared(9192, "FRC", "2026txhou"))
        assertEquals(2, SettingsService.syncedEventsBuilds.get() - before)
    }

    @Test
    fun concurrentChecksShareOneRebuild() = runBlocking<Unit> {
        configure(9181, "txhou", tbaKey = "key-9181")
        configure(9182, "txhou")
        SettingsService.clearSyncedEventsCache()
        val before = SettingsService.syncedEventsBuilds.get()

        val results = (1..25).map {
            async(Dispatchers.Default) { SettingsService.isEventSyncedByAnotherTeam(9182, "FRC", "2026txhou") }
        }.awaitAll()

        assertTrue(results.all { it })
        assertEquals(1, SettingsService.syncedEventsBuilds.get() - before, "concurrent checks must share one rebuild")
    }
}
