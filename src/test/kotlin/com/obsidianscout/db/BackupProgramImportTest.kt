package com.obsidianscout.db

import com.obsidianscout.auth.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupProgramImportTest {

    private val testDbFile = File("build/test_backup_program_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users, AppSettings, ScoutingAlliances, AllianceMemberships,
                ScoutingConfigs, PitScoutingConfigs, QualitativeScoutingConfigs,
                ScoutingEntries, PitScoutingEntries, QualitativeScoutingEntries,
                Banners, ChatMessages
            )
        }
    }

    @AfterTest
    fun tearDown() {
        testDbFile.delete()
    }

    @Test
    fun ftcImportDoesNotTouchTheFrcTeamWithTheSameNumber() {
        transaction {
            ScoutingConfigs.insert {
                it[teamNumber] = 8000
                it[program] = "FRC"
                it[configJson] = """{"title":"frc config"}"""
                it[updatedAt] = Instant.now()
            }
            Users.insert {
                it[username] = "alex"
                it[teamNumber] = 8000
                it[program] = "FRC"
                it[passwordHash] = "x"
                it[role] = "ADMIN"
                it[createdAt] = Instant.now()
            }
        }
        val backup = ObsidianDbBackup(
            teamNumber = 8000,
            type = "entire",
            scoutingConfigs = listOf(ConfigBackupDto(8000, """{"title":"imported"}""", 0)),
            users = listOf(UserBackupDto("alex", 8000, "y", "SCOUT", 0))
        )

        BackupService.importBackup(8000, backup, "importer", isSuperAdmin = false, targetProgram = "FTC")

        val configs = transaction {
            ScoutingConfigs.selectAll().where { ScoutingConfigs.teamNumber eq 8000 }
                .associate { it[ScoutingConfigs.program] to it[ScoutingConfigs.configJson] }
        }
        assertEquals("""{"title":"frc config"}""", configs["FRC"], "the FRC team's config must not be overwritten")
        assertEquals("""{"title":"imported"}""", configs["FTC"])

        val users = transaction {
            Users.selectAll().where { (Users.teamNumber eq 8000) and (Users.username eq "alex") }
                .associate { it[Users.program] to it[Users.role] }
        }
        assertEquals(mapOf("FRC" to "ADMIN", "FTC" to "SCOUT"), users)
    }

    @Test
    fun globalImportRestoresEachRowsOwnProgram() {
        val backup = ObsidianDbBackup(
            teamNumber = 0,
            type = "entire",
            scope = "global",
            scoutingConfigs = listOf(ConfigBackupDto(8001, """{"title":"ftc"}""", 0, program = "FTC")),
            users = listOf(UserBackupDto("sam", 8001, "y", "SCOUT", 0, program = "FTC"))
        )

        BackupService.importBackup(0, backup, "superadmin", isSuperAdmin = true)

        val configPrograms = transaction { ScoutingConfigs.selectAll().map { it[ScoutingConfigs.program] } }
        assertEquals(listOf("FTC"), configPrograms)
        val userPrograms = transaction { Users.selectAll().map { it[Users.program] } }
        assertEquals(listOf("FTC"), userPrograms)
    }

    @Test
    fun oversizedUploadsAreRefused() {
        val ex = assertFailsWith<ApiException> {
            BackupService.readLimited(ByteArrayInputStream(ByteArray(2048)), limit = 1024)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, ex.status)
        assertEquals(512, BackupService.readLimited(ByteArrayInputStream(ByteArray(512)), limit = 1024).size)
    }
}
