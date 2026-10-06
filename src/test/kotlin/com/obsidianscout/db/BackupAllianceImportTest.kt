package com.obsidianscout.db

import com.obsidianscout.scouting.AllianceService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupAllianceImportTest {

    private val testDbFile = File("build/test_backup_alliance_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) testDbFile.delete()
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(
                Users,
                AppSettings,
                ScoutingAlliances,
                AllianceMemberships,
                ScoutingConfigs,
                PitScoutingConfigs,
                QualitativeScoutingConfigs,
                ScoutingEntries,
                PitScoutingEntries,
                QualitativeScoutingEntries,
                Banners,
                ChatMessages
            )
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) testDbFile.delete()
    }

    private fun backupEnrollingTeam(victimTeam: Int, importerTeam: Int) = ObsidianDbBackup(
        teamNumber = importerTeam,
        type = "entire",
        scope = "team",
        alliances = listOf(
            ScoutingAllianceBackupDto(
                id = "a1", name = "Imported", ownerTeamNumber = importerTeam, eventKey = null, notes = null,
                createdAt = 0, updatedAt = 0, matchConfigJson = null, pitConfigJson = null,
                qualitativeConfigJson = null, year = null, eventCode = null, program = "FRC"
            )
        ),
        allianceMemberships = listOf(
            AllianceMembershipBackupDto("a1", importerTeam, "ADMIN", 0, 0, disabled = false, active = true, program = "FRC"),
            AllianceMembershipBackupDto("a1", victimTeam, "ACCEPTED", 0, 0, disabled = false, active = true, program = "FRC")
        )
    )

    @Test
    fun teamImportCannotMakeAnotherTeamAnAllianceMember() {
        BackupService.importBackup(6000, backupEnrollingTeam(victimTeam = 7000, importerTeam = 6000), "importer", isSuperAdmin = false, targetProgram = "FRC")

        assertNotNull(AllianceService.getActiveAllianceId(6000, "FRC"), "the importing team keeps its own membership")
        assertNull(AllianceService.getActiveAllianceId(7000, "FRC"), "the other team must not be put into the alliance")
        assertTrue(AllianceService.getAlliancePartnerTeams(6000, "FRC").isEmpty(), "no data is shared until the other team accepts")

        val victimRow = transaction {
            AllianceMemberships.selectAll().where {
                (AllianceMemberships.teamNumber eq 7000) and (AllianceMemberships.program eq "FRC")
            }.single()
        }
        assertEquals("INVITED", victimRow[AllianceMemberships.status])
        assertFalse(victimRow[AllianceMemberships.active])
        assertNull(victimRow[AllianceMemberships.respondedAt])
    }

    @Test
    fun teamImportCreatesAlliancesInTheImportersProgram() {
        BackupService.importBackup(6001, backupEnrollingTeam(victimTeam = 7001, importerTeam = 6001), "importer", isSuperAdmin = false, targetProgram = "FTC")

        val programs = transaction { ScoutingAlliances.selectAll().map { it[ScoutingAlliances.program] } }
        assertEquals(listOf("FTC"), programs)
        val memberPrograms = transaction { AllianceMemberships.selectAll().map { it[AllianceMemberships.program] }.toSet() }
        assertEquals(setOf("FTC"), memberPrograms)
    }

    @Test
    fun globalImportKeepsMembershipsAsExported() {
        val backup = backupEnrollingTeam(victimTeam = 7002, importerTeam = 6002).copy(scope = "global")
        BackupService.importBackup(6002, backup, "superadmin", isSuperAdmin = true)

        assertEquals(setOf(7002), AllianceService.getAlliancePartnerTeams(6002, "FRC"))
    }
}
