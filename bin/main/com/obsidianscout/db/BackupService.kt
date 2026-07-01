package com.obsidianscout.db

import com.obsidianscout.config.JsonSupport
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.dao.id.EntityID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.obsidianscout.utils.CSVHelper

@Serializable
data class UserBackupDto(
    val username: String,
    val teamNumber: Int,
    val passwordHash: String,
    val role: String,
    val createdAt: Long,
    val email: String? = null,
    val profilePicture: String? = null,
    val notificationPreference: String = "all"
)

@Serializable
data class ConfigBackupDto(
    val teamNumber: Int,
    val configJson: String,
    val updatedAt: Long
)

@Serializable
data class AppSettingsBackupDto(
    val teamNumber: Int,
    val settingsJson: String,
    val updatedAt: Long
)

@Serializable
data class ScoutingEntryBackupDto(
    val ownerTeamNumber: Int,
    val targetTeamNumber: Int?,
    val eventKey: String?,
    val matchKey: String?,
    val matchNumber: Int?,
    val dataJson: String,
    val submittedByUsername: String,
    val createdAt: Long,
    val isPrescout: Boolean,
    val hasDiscrepancy: Boolean,
    val conflictingTeams: String
)

@Serializable
data class PitScoutingEntryBackupDto(
    val ownerTeamNumber: Int,
    val targetTeamNumber: Int?,
    val eventKey: String?,
    val dataJson: String,
    val submittedByUsername: String,
    val createdAt: Long,
    val isPrescout: Boolean,
    val hasDiscrepancy: Boolean,
    val conflictingTeams: String
)

@Serializable
data class QualitativeScoutingEntryBackupDto(
    val ownerTeamNumber: Int,
    val targetTeamNumber: Int?,
    val eventKey: String?,
    val matchKey: String?,
    val matchNumber: Int?,
    val dataJson: String,
    val submittedByUsername: String,
    val createdAt: Long,
    val isPrescout: Boolean,
    val hasDiscrepancy: Boolean,
    val conflictingTeams: String
)

@Serializable
data class ScoutingAllianceBackupDto(
    val id: Int,
    val name: String,
    val ownerTeamNumber: Int,
    val eventKey: String?,
    val notes: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val matchConfigJson: String?,
    val pitConfigJson: String?,
    val qualitativeConfigJson: String?,
    val year: Int?,
    val eventCode: String?
)

@Serializable
data class AllianceMembershipBackupDto(
    val allianceId: Int,
    val teamNumber: Int,
    val status: String,
    val invitedAt: Long,
    val respondedAt: Long?,
    val disabled: Boolean,
    val active: Boolean
)

@Serializable
data class BannerBackupDto(
    val teamNumber: Int,
    val message: String,
    val bannerType: String,
    val isDismissible: Boolean,
    val isExpandable: Boolean,
    val expandableMessage: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class ChatMessageBackupDto(
    val teamNumber: Int,
    val groupName: String,
    val username: String,
    val content: String,
    val createdAt: Long,
    val reactionsJson: String
)

@Serializable
data class ObsidianDbBackup(
    val teamNumber: Int,
    val type: String, // "ENTIRE_TEAM" or "SCOUTING_DATA"
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    
    val scoutingConfigs: List<ConfigBackupDto> = emptyList(),
    val pitScoutingConfigs: List<ConfigBackupDto> = emptyList(),
    val qualitativeScoutingConfigs: List<ConfigBackupDto> = emptyList(),
    val appSettings: List<AppSettingsBackupDto> = emptyList(),
    val users: List<UserBackupDto> = emptyList(),
    val scoutingEntries: List<ScoutingEntryBackupDto> = emptyList(),
    val pitScoutingEntries: List<PitScoutingEntryBackupDto> = emptyList(),
    val qualitativeScoutingEntries: List<QualitativeScoutingEntryBackupDto> = emptyList(),
    val alliances: List<ScoutingAllianceBackupDto> = emptyList(),
    val allianceMemberships: List<AllianceMembershipBackupDto> = emptyList(),
    val banners: List<BannerBackupDto> = emptyList(),
    val chatMessages: List<ChatMessageBackupDto> = emptyList()
)

@Serializable
data class ImportReport(
    val success: Boolean,
    val type: String,
    val usersImported: Int = 0,
    val usersSkipped: Int = 0,
    val configsImported: Int = 0,
    val configsUpdated: Int = 0,
    val settingsImported: Int = 0,
    val settingsUpdated: Int = 0,
    val scoutingEntriesImported: Int = 0,
    val scoutingEntriesSkipped: Int = 0,
    val pitEntriesImported: Int = 0,
    val pitEntriesSkipped: Int = 0,
    val qualEntriesImported: Int = 0,
    val qualEntriesSkipped: Int = 0,
    val alliancesImported: Int = 0,
    val alliancesSkipped: Int = 0,
    val bannersImported: Int = 0,
    val bannersSkipped: Int = 0,
    val chatsImported: Int = 0,
    val chatsSkipped: Int = 0,
    val message: String = ""
)

object BackupService {

    fun exportBackup(teamNumber: Int, type: String): ObsidianDbBackup {
        return transaction {
            val userMap = Users.selectAll().where { Users.teamNumber eq teamNumber }
                .associate { it[Users.id].value to it[Users.username] }

            val users = if (type == "entire") {
                Users.selectAll().where { Users.teamNumber eq teamNumber }.map { row ->
                    UserBackupDto(
                        username = row[Users.username],
                        teamNumber = row[Users.teamNumber],
                        passwordHash = row[Users.passwordHash],
                        role = row[Users.role],
                        createdAt = row[Users.createdAt].toEpochMilli(),
                        email = row[Users.email],
                        profilePicture = row[Users.profilePicture],
                        notificationPreference = row[Users.notificationPreference]
                    )
                }
            } else emptyList()

            val scoutingConfigs = if (type == "entire") {
                ScoutingConfigs.selectAll().where { ScoutingConfigs.teamNumber eq teamNumber }.map { row ->
                    ConfigBackupDto(row[ScoutingConfigs.teamNumber], row[ScoutingConfigs.configJson], row[ScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else emptyList()

            val pitScoutingConfigs = if (type == "entire") {
                PitScoutingConfigs.selectAll().where { PitScoutingConfigs.teamNumber eq teamNumber }.map { row ->
                    ConfigBackupDto(row[PitScoutingConfigs.teamNumber], row[PitScoutingConfigs.configJson], row[PitScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else emptyList()

            val qualitativeScoutingConfigs = if (type == "entire") {
                QualitativeScoutingConfigs.selectAll().where { QualitativeScoutingConfigs.teamNumber eq teamNumber }.map { row ->
                    ConfigBackupDto(row[QualitativeScoutingConfigs.teamNumber], row[QualitativeScoutingConfigs.configJson], row[QualitativeScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else emptyList()

            val appSettings = if (type == "entire") {
                AppSettings.selectAll().where { AppSettings.teamNumber eq teamNumber }.map { row ->
                    AppSettingsBackupDto(row[AppSettings.teamNumber], row[AppSettings.settingsJson], row[AppSettings.updatedAt].toEpochMilli())
                }
            } else emptyList()

            // Scouting entries
            val scoutingEntries = ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq teamNumber }.map { row ->
                val userId = row[ScoutingEntries.submittedByUserId].value
                val username = userMap[userId] ?: "unknown_scout"
                ScoutingEntryBackupDto(
                    ownerTeamNumber = row[ScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[ScoutingEntries.targetTeamNumber],
                    eventKey = row[ScoutingEntries.eventKey],
                    matchKey = row[ScoutingEntries.matchKey],
                    matchNumber = row[ScoutingEntries.matchNumber],
                    dataJson = row[ScoutingEntries.dataJson],
                    submittedByUsername = username,
                    createdAt = row[ScoutingEntries.createdAt].toEpochMilli(),
                    isPrescout = row[ScoutingEntries.isPrescout],
                    hasDiscrepancy = row[ScoutingEntries.hasDiscrepancy],
                    conflictingTeams = row[ScoutingEntries.conflictingTeams]
                )
            }

            val pitScoutingEntries = PitScoutingEntries.selectAll().where { PitScoutingEntries.ownerTeamNumber eq teamNumber }.map { row ->
                val userId = row[PitScoutingEntries.submittedByUserId].value
                val username = userMap[userId] ?: "unknown_scout"
                PitScoutingEntryBackupDto(
                    ownerTeamNumber = row[PitScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[PitScoutingEntries.targetTeamNumber],
                    eventKey = row[PitScoutingEntries.eventKey],
                    dataJson = row[PitScoutingEntries.dataJson],
                    submittedByUsername = username,
                    createdAt = row[PitScoutingEntries.createdAt].toEpochMilli(),
                    isPrescout = row[PitScoutingEntries.isPrescout],
                    hasDiscrepancy = row[PitScoutingEntries.hasDiscrepancy],
                    conflictingTeams = row[PitScoutingEntries.conflictingTeams]
                )
            }

            val qualitativeScoutingEntries = QualitativeScoutingEntries.selectAll().where { QualitativeScoutingEntries.ownerTeamNumber eq teamNumber }.map { row ->
                val userId = row[QualitativeScoutingEntries.submittedByUserId].value
                val username = userMap[userId] ?: "unknown_scout"
                QualitativeScoutingEntryBackupDto(
                    ownerTeamNumber = row[QualitativeScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[QualitativeScoutingEntries.targetTeamNumber],
                    eventKey = row[QualitativeScoutingEntries.eventKey],
                    matchKey = row[QualitativeScoutingEntries.matchKey],
                    matchNumber = row[QualitativeScoutingEntries.matchNumber],
                    dataJson = row[QualitativeScoutingEntries.dataJson],
                    submittedByUsername = username,
                    createdAt = row[QualitativeScoutingEntries.createdAt].toEpochMilli(),
                    isPrescout = row[QualitativeScoutingEntries.isPrescout],
                    hasDiscrepancy = row[QualitativeScoutingEntries.hasDiscrepancy],
                    conflictingTeams = row[QualitativeScoutingEntries.conflictingTeams]
                )
            }

            val alliances = if (type == "entire") {
                ScoutingAlliances.selectAll().where { ScoutingAlliances.ownerTeamNumber eq teamNumber }.map { row ->
                    ScoutingAllianceBackupDto(
                        id = row[ScoutingAlliances.id].value,
                        name = row[ScoutingAlliances.name],
                        ownerTeamNumber = row[ScoutingAlliances.ownerTeamNumber],
                        eventKey = row[ScoutingAlliances.eventKey],
                        notes = row[ScoutingAlliances.notes],
                        createdAt = row[ScoutingAlliances.createdAt].toEpochMilli(),
                        updatedAt = row[ScoutingAlliances.updatedAt].toEpochMilli(),
                        matchConfigJson = row[ScoutingAlliances.matchConfigJson],
                        pitConfigJson = row[ScoutingAlliances.pitConfigJson],
                        qualitativeConfigJson = row[ScoutingAlliances.qualitativeConfigJson],
                        year = row[ScoutingAlliances.year],
                        eventCode = row[ScoutingAlliances.eventCode]
                    )
                }
            } else emptyList()

            val allianceIds = alliances.map { it.id }
            val allianceMemberships = if (type == "entire" && allianceIds.isNotEmpty()) {
                AllianceMemberships.selectAll().where { AllianceMemberships.allianceId inList allianceIds }.map { row ->
                    AllianceMembershipBackupDto(
                        allianceId = row[AllianceMemberships.allianceId].value,
                        teamNumber = row[AllianceMemberships.teamNumber],
                        status = row[AllianceMemberships.status],
                        invitedAt = row[AllianceMemberships.invitedAt].toEpochMilli(),
                        respondedAt = row[AllianceMemberships.respondedAt]?.toEpochMilli(),
                        disabled = row[AllianceMemberships.disabled],
                        active = row[AllianceMemberships.active]
                    )
                }
            } else emptyList()

            val banners = if (type == "entire") {
                Banners.selectAll().where { Banners.teamNumber eq teamNumber }.map { row ->
                    BannerBackupDto(
                        teamNumber = row[Banners.teamNumber],
                        message = row[Banners.message],
                        bannerType = row[Banners.bannerType],
                        isDismissible = row[Banners.isDismissible],
                        isExpandable = row[Banners.isExpandable],
                        expandableMessage = row[Banners.expandableMessage],
                        isActive = row[Banners.isActive],
                        createdAt = row[Banners.createdAt].toEpochMilli(),
                        updatedAt = row[Banners.updatedAt].toEpochMilli()
                    )
                }
            } else emptyList()

            val chatMessages = if (type == "entire") {
                ChatMessages.selectAll().where { ChatMessages.teamNumber eq teamNumber }.map { row ->
                    val userId = row[ChatMessages.userId].value
                    val username = userMap[userId] ?: row[ChatMessages.username]
                    ChatMessageBackupDto(
                        teamNumber = row[ChatMessages.teamNumber],
                        groupName = row[ChatMessages.groupName],
                        username = username,
                        content = row[ChatMessages.content],
                        createdAt = row[ChatMessages.createdAt].toEpochMilli(),
                        reactionsJson = row[ChatMessages.reactionsJson]
                    )
                }
            } else emptyList()

            ObsidianDbBackup(
                teamNumber = teamNumber,
                type = type,
                scoutingConfigs = scoutingConfigs,
                pitScoutingConfigs = pitScoutingConfigs,
                qualitativeScoutingConfigs = qualitativeScoutingConfigs,
                appSettings = appSettings,
                users = users,
                scoutingEntries = scoutingEntries,
                pitScoutingEntries = pitScoutingEntries,
                qualitativeScoutingEntries = qualitativeScoutingEntries,
                alliances = alliances,
                allianceMemberships = allianceMemberships,
                banners = banners,
                chatMessages = chatMessages
            )
        }
    }

    fun importBackup(targetTeamNumber: Int, backup: ObsidianDbBackup, currentUserId: Int): ImportReport {
        return transaction {
            var usersImported = 0
            var usersSkipped = 0
            var configsImported = 0
            var configsUpdated = 0
            var settingsImported = 0
            var settingsUpdated = 0
            var scoutingEntriesImported = 0
            var scoutingEntriesSkipped = 0
            var pitEntriesImported = 0
            var pitEntriesSkipped = 0
            var qualEntriesImported = 0
            var qualEntriesSkipped = 0
            var alliancesImported = 0
            var alliancesSkipped = 0
            var bannersImported = 0
            var bannersSkipped = 0
            var chatsImported = 0
            var chatsSkipped = 0

            // 1. Users mapping: (username) -> target server UserId
            val userMap = mutableMapOf<String, Int>()
            // Query current users for target team to populate initial mapping
            Users.selectAll().where { Users.teamNumber eq targetTeamNumber }.forEach { row ->
                userMap[row[Users.username]] = row[Users.id].value
            }

            if (backup.type == "entire") {
                for (u in backup.users) {
                    val existingId = userMap[u.username]
                    if (existingId != null) {
                        usersSkipped++
                    } else {
                        val newId = Users.insertAndGetId {
                            it[username] = u.username
                            it[teamNumber] = targetTeamNumber
                            it[passwordHash] = u.passwordHash
                            it[role] = u.role
                            it[createdAt] = Instant.ofEpochMilli(u.createdAt)
                            it[email] = u.email
                            it[profilePicture] = u.profilePicture
                            it[notificationPreference] = u.notificationPreference
                        }.value
                        userMap[u.username] = newId
                        usersImported++
                    }
                }

                // 2. Import Configs
                for (c in backup.scoutingConfigs) {
                    val exists = ScoutingConfigs.selectAll().where { ScoutingConfigs.teamNumber eq targetTeamNumber }.any()
                    if (exists) {
                        ScoutingConfigs.update({ ScoutingConfigs.teamNumber eq targetTeamNumber }) {
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsUpdated++
                    } else {
                        ScoutingConfigs.insert {
                            it[teamNumber] = targetTeamNumber
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsImported++
                    }
                }
                
                // Pit scouting configs
                for (c in backup.pitScoutingConfigs) {
                    val exists = PitScoutingConfigs.selectAll().where { PitScoutingConfigs.teamNumber eq targetTeamNumber }.any()
                    if (exists) {
                        PitScoutingConfigs.update({ PitScoutingConfigs.teamNumber eq targetTeamNumber }) {
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsUpdated++
                    } else {
                        PitScoutingConfigs.insert {
                            it[teamNumber] = targetTeamNumber
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsImported++
                    }
                }

                // Qualitative scouting configs
                for (c in backup.qualitativeScoutingConfigs) {
                    val exists = QualitativeScoutingConfigs.selectAll().where { QualitativeScoutingConfigs.teamNumber eq targetTeamNumber }.any()
                    if (exists) {
                        QualitativeScoutingConfigs.update({ QualitativeScoutingConfigs.teamNumber eq targetTeamNumber }) {
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsUpdated++
                    } else {
                        QualitativeScoutingConfigs.insert {
                            it[teamNumber] = targetTeamNumber
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsImported++
                    }
                }

                // App settings
                for (s in backup.appSettings) {
                    val exists = AppSettings.selectAll().where { AppSettings.teamNumber eq targetTeamNumber }.any()
                    if (exists) {
                        AppSettings.update({ AppSettings.teamNumber eq targetTeamNumber }) {
                            it[settingsJson] = s.settingsJson
                            it[updatedAt] = Instant.ofEpochMilli(s.updatedAt)
                        }
                        settingsUpdated++
                    } else {
                        AppSettings.insert {
                            it[teamNumber] = targetTeamNumber
                            it[settingsJson] = s.settingsJson
                            it[updatedAt] = Instant.ofEpochMilli(s.updatedAt)
                        }
                        settingsImported++
                    }
                }
            }

            // Helpers to resolve submittedByUserId
            fun getUserId(username: String): Int {
                return userMap[username] ?: currentUserId
            }

            // 3. Scouting Entries import
            for (e in backup.scoutingEntries) {
                val userId = getUserId(e.submittedByUsername)
                val exists = ScoutingEntries.selectAll().where {
                    (ScoutingEntries.ownerTeamNumber eq targetTeamNumber) and
                    (ScoutingEntries.createdAt eq Instant.ofEpochMilli(e.createdAt)) and
                    (ScoutingEntries.targetTeamNumber eq e.targetTeamNumber) and
                    (ScoutingEntries.eventKey eq e.eventKey) and
                    (ScoutingEntries.matchNumber eq e.matchNumber)
                }.any()

                if (exists) {
                    scoutingEntriesSkipped++
                } else {
                    ScoutingEntries.insert {
                        it[ownerTeamNumber] = targetTeamNumber
                        it[ScoutingEntries.targetTeamNumber] = e.targetTeamNumber
                        it[eventKey] = e.eventKey
                        it[matchKey] = e.matchKey
                        it[matchNumber] = e.matchNumber
                        it[dataJson] = e.dataJson
                        it[submittedByUserId] = EntityID(userId, Users)
                        it[createdAt] = Instant.ofEpochMilli(e.createdAt)
                        it[isPrescout] = e.isPrescout
                        it[hasDiscrepancy] = e.hasDiscrepancy
                        it[conflictingTeams] = e.conflictingTeams
                    }
                    scoutingEntriesImported++
                }
            }

            // Pit entries
            for (e in backup.pitScoutingEntries) {
                val userId = getUserId(e.submittedByUsername)
                val exists = PitScoutingEntries.selectAll().where {
                    (PitScoutingEntries.ownerTeamNumber eq targetTeamNumber) and
                    (PitScoutingEntries.createdAt eq Instant.ofEpochMilli(e.createdAt)) and
                    (PitScoutingEntries.targetTeamNumber eq e.targetTeamNumber) and
                    (PitScoutingEntries.eventKey eq e.eventKey)
                }.any()

                if (exists) {
                    pitEntriesSkipped++
                } else {
                    PitScoutingEntries.insert {
                        it[ownerTeamNumber] = targetTeamNumber
                        it[PitScoutingEntries.targetTeamNumber] = e.targetTeamNumber
                        it[eventKey] = e.eventKey
                        it[dataJson] = e.dataJson
                        it[submittedByUserId] = EntityID(userId, Users)
                        it[createdAt] = Instant.ofEpochMilli(e.createdAt)
                        it[isPrescout] = e.isPrescout
                        it[hasDiscrepancy] = e.hasDiscrepancy
                        it[conflictingTeams] = e.conflictingTeams
                    }
                    pitEntriesImported++
                }
            }

            // Qualitative entries
            for (e in backup.qualitativeScoutingEntries) {
                val userId = getUserId(e.submittedByUsername)
                val exists = QualitativeScoutingEntries.selectAll().where {
                    (QualitativeScoutingEntries.ownerTeamNumber eq targetTeamNumber) and
                    (QualitativeScoutingEntries.createdAt eq Instant.ofEpochMilli(e.createdAt)) and
                    (QualitativeScoutingEntries.targetTeamNumber eq e.targetTeamNumber) and
                    (QualitativeScoutingEntries.eventKey eq e.eventKey) and
                    (QualitativeScoutingEntries.matchNumber eq e.matchNumber)
                }.any()

                if (exists) {
                    qualEntriesSkipped++
                } else {
                    QualitativeScoutingEntries.insert {
                        it[ownerTeamNumber] = targetTeamNumber
                        it[QualitativeScoutingEntries.targetTeamNumber] = e.targetTeamNumber
                        it[eventKey] = e.eventKey
                        it[matchKey] = e.matchKey
                        it[matchNumber] = e.matchNumber
                        it[dataJson] = e.dataJson
                        it[submittedByUserId] = EntityID(userId, Users)
                        it[createdAt] = Instant.ofEpochMilli(e.createdAt)
                        it[isPrescout] = e.isPrescout
                        it[hasDiscrepancy] = e.hasDiscrepancy
                        it[conflictingTeams] = e.conflictingTeams
                    }
                    qualEntriesImported++
                }
            }

            // 4. Alliances and Alliances memberships (only entire)
            if (backup.type == "entire") {
                val allianceIdMap = mutableMapOf<Int, Int>()

                for (a in backup.alliances) {
                    // Check if exists
                    val existingRow = ScoutingAlliances.selectAll().where {
                        (ScoutingAlliances.ownerTeamNumber eq targetTeamNumber) and
                        (ScoutingAlliances.name eq a.name) and
                        (ScoutingAlliances.eventKey eq a.eventKey)
                    }.firstOrNull()

                    if (existingRow != null) {
                        allianceIdMap[a.id] = existingRow[ScoutingAlliances.id].value
                        alliancesSkipped++
                    } else {
                        val newId = ScoutingAlliances.insertAndGetId {
                            it[name] = a.name
                            it[ownerTeamNumber] = targetTeamNumber
                            it[eventKey] = a.eventKey
                            it[notes] = a.notes
                            it[createdAt] = Instant.ofEpochMilli(a.createdAt)
                            it[updatedAt] = Instant.ofEpochMilli(a.updatedAt)
                            it[matchConfigJson] = a.matchConfigJson
                            it[pitConfigJson] = a.pitConfigJson
                            it[qualitativeConfigJson] = a.qualitativeConfigJson
                            it[year] = a.year
                            it[eventCode] = a.eventCode
                        }.value
                        allianceIdMap[a.id] = newId
                        alliancesImported++
                    }
                }

                // Alliance Memberships
                for (m in backup.allianceMemberships) {
                    val targetAllianceId = allianceIdMap[m.allianceId] ?: continue
                    val exists = AllianceMemberships.selectAll().where {
                        (AllianceMemberships.allianceId eq targetAllianceId) and
                        (AllianceMemberships.teamNumber eq m.teamNumber)
                    }.any()

                    if (!exists) {
                        AllianceMemberships.insert {
                            it[allianceId] = targetAllianceId
                            it[teamNumber] = m.teamNumber
                            it[status] = m.status
                            it[invitedAt] = Instant.ofEpochMilli(m.invitedAt)
                            it[respondedAt] = m.respondedAt?.let { Instant.ofEpochMilli(it) }
                            it[disabled] = m.disabled
                            it[active] = m.active
                        }
                    }
                }

                // 5. Banners
                for (b in backup.banners) {
                    val exists = Banners.selectAll().where {
                        (Banners.teamNumber eq targetTeamNumber) and
                        (Banners.message eq b.message) and
                        (Banners.createdAt eq Instant.ofEpochMilli(b.createdAt))
                    }.any()

                    if (exists) {
                        bannersSkipped++
                    } else {
                        Banners.insert {
                            it[teamNumber] = targetTeamNumber
                            it[message] = b.message
                            it[bannerType] = b.bannerType
                            it[isDismissible] = b.isDismissible
                            it[isExpandable] = b.isExpandable
                            it[expandableMessage] = b.expandableMessage
                            it[isActive] = b.isActive
                            it[createdAt] = Instant.ofEpochMilli(b.createdAt)
                            it[updatedAt] = Instant.ofEpochMilli(b.updatedAt)
                        }
                        bannersImported++
                    }
                }

                // 6. Chat Messages
                for (c in backup.chatMessages) {
                    val userId = getUserId(c.username)
                    val exists = ChatMessages.selectAll().where {
                        (ChatMessages.teamNumber eq targetTeamNumber) and
                        (ChatMessages.content eq c.content) and
                        (ChatMessages.createdAt eq Instant.ofEpochMilli(c.createdAt)) and
                        (ChatMessages.userId eq userId)
                    }.any()

                    if (exists) {
                        chatsSkipped++
                    } else {
                        ChatMessages.insert {
                            it[teamNumber] = targetTeamNumber
                            it[groupName] = c.groupName
                            it[ChatMessages.userId] = EntityID(userId, Users)
                            it[username] = c.username
                            it[content] = c.content
                            it[createdAt] = Instant.ofEpochMilli(c.createdAt)
                            it[reactionsJson] = c.reactionsJson
                        }
                        chatsImported++
                    }
                }
            }

            ImportReport(
                success = true,
                type = backup.type,
                usersImported = usersImported,
                usersSkipped = usersSkipped,
                configsImported = configsImported,
                configsUpdated = configsUpdated,
                settingsImported = settingsImported,
                settingsUpdated = settingsUpdated,
                scoutingEntriesImported = scoutingEntriesImported,
                scoutingEntriesSkipped = scoutingEntriesSkipped,
                pitEntriesImported = pitEntriesImported,
                pitEntriesSkipped = pitEntriesSkipped,
                qualEntriesImported = qualEntriesImported,
                qualEntriesSkipped = qualEntriesSkipped,
                alliancesImported = alliancesImported,
                alliancesSkipped = alliancesSkipped,
                bannersImported = bannersImported,
                bannersSkipped = bannersSkipped,
                chatsImported = chatsImported,
                chatsSkipped = chatsSkipped,
                message = "Backup imported successfully"
            )
        }
    }

    fun exportCsv(teamNumber: Int, type: String): ByteArray {
        val backup = exportBackup(teamNumber, type)
        val files = mutableMapOf<String, String>()

        // Serialize Scouting Entries
        val scoutHeaders = listOf("owner_team_number", "target_team_number", "event_key", "match_key", "match_number", "data_json", "submitted_by_username", "created_at", "is_prescout", "has_discrepancy", "conflicting_teams")
        val scoutRows = backup.scoutingEntries.map { e ->
            listOf(
                e.ownerTeamNumber.toString(),
                e.targetTeamNumber?.toString() ?: "",
                e.eventKey ?: "",
                e.matchKey ?: "",
                e.matchNumber?.toString() ?: "",
                e.dataJson,
                e.submittedByUsername,
                e.createdAt.toString(),
                e.isPrescout.toString(),
                e.hasDiscrepancy.toString(),
                e.conflictingTeams
            )
        }
        files["scouting_entries.csv"] = CSVHelper.toCSV(scoutHeaders, scoutRows)

        // Serialize Pit Entries
        val pitHeaders = listOf("owner_team_number", "target_team_number", "event_key", "data_json", "submitted_by_username", "created_at", "is_prescout", "has_discrepancy", "conflicting_teams")
        val pitRows = backup.pitScoutingEntries.map { e ->
            listOf(
                e.ownerTeamNumber.toString(),
                e.targetTeamNumber?.toString() ?: "",
                e.eventKey ?: "",
                e.dataJson,
                e.submittedByUsername,
                e.createdAt.toString(),
                e.isPrescout.toString(),
                e.hasDiscrepancy.toString(),
                e.conflictingTeams
            )
        }
        files["pit_scouting_entries.csv"] = CSVHelper.toCSV(pitHeaders, pitRows)

        // Serialize Qualitative Entries
        val qualHeaders = listOf("owner_team_number", "target_team_number", "event_key", "match_key", "match_number", "data_json", "submitted_by_username", "created_at", "is_prescout", "has_discrepancy", "conflicting_teams")
        val qualRows = backup.qualitativeScoutingEntries.map { e ->
            listOf(
                e.ownerTeamNumber.toString(),
                e.targetTeamNumber?.toString() ?: "",
                e.eventKey ?: "",
                e.matchKey ?: "",
                e.matchNumber?.toString() ?: "",
                e.dataJson,
                e.submittedByUsername,
                e.createdAt.toString(),
                e.isPrescout.toString(),
                e.hasDiscrepancy.toString(),
                e.conflictingTeams
            )
        }
        files["qualitative_scouting_entries.csv"] = CSVHelper.toCSV(qualHeaders, qualRows)

        if (type == "entire") {
            // Include users
            val userHeaders = listOf("username", "team_number", "password_hash", "role", "created_at", "email", "profile_picture", "notification_preference")
            val userRows = backup.users.map { u ->
                listOf(
                    u.username,
                    u.teamNumber.toString(),
                    u.passwordHash,
                    u.role,
                    u.createdAt.toString(),
                    u.email ?: "",
                    u.profilePicture ?: "",
                    u.notificationPreference
                )
            }
            files["users.csv"] = CSVHelper.toCSV(userHeaders, userRows)

            // Include Configs
            val configHeaders = listOf("config_type", "team_number", "config_json", "updated_at")
            val configRows = mutableListOf<List<String>>()
            backup.scoutingConfigs.forEach { configRows.add(listOf("game", it.teamNumber.toString(), it.configJson, it.updatedAt.toString())) }
            backup.pitScoutingConfigs.forEach { configRows.add(listOf("pit", it.teamNumber.toString(), it.configJson, it.updatedAt.toString())) }
            backup.qualitativeScoutingConfigs.forEach { configRows.add(listOf("qual", it.teamNumber.toString(), it.configJson, it.updatedAt.toString())) }
            files["configs.csv"] = CSVHelper.toCSV(configHeaders, configRows)

            // Include AppSettings
            val settingsHeaders = listOf("team_number", "settings_json", "updated_at")
            val settingsRows = backup.appSettings.map { s ->
                listOf(s.teamNumber.toString(), s.settingsJson, s.updatedAt.toString())
            }
            files["app_settings.csv"] = CSVHelper.toCSV(settingsHeaders, settingsRows)

            // Include Alliances
            val allianceHeaders = listOf("id", "name", "owner_team_number", "event_key", "notes", "created_at", "updated_at", "match_config_json", "pit_config_json", "qualitative_config_json", "year", "event_code")
            val allianceRows = backup.alliances.map { a ->
                listOf(
                    a.id.toString(),
                    a.name,
                    a.ownerTeamNumber.toString(),
                    a.eventKey ?: "",
                    a.notes ?: "",
                    a.createdAt.toString(),
                    a.updatedAt.toString(),
                    a.matchConfigJson ?: "",
                    a.pitConfigJson ?: "",
                    a.qualitativeConfigJson ?: "",
                    a.year?.toString() ?: "",
                    a.eventCode ?: ""
                )
            }
            files["alliances.csv"] = CSVHelper.toCSV(allianceHeaders, allianceRows)

            // Include Alliance Memberships
            val memberHeaders = listOf("alliance_id", "team_number", "status", "invited_at", "responded_at", "disabled", "active")
            val memberRows = backup.allianceMemberships.map { m ->
                listOf(
                    m.allianceId.toString(),
                    m.teamNumber.toString(),
                    m.status,
                    m.invitedAt.toString(),
                    m.respondedAt?.toString() ?: "",
                    m.disabled.toString(),
                    m.active.toString()
                )
            }
            files["alliance_memberships.csv"] = CSVHelper.toCSV(memberHeaders, memberRows)

            // Include Banners
            val bannerHeaders = listOf("team_number", "message", "banner_type", "is_dismissible", "is_expandable", "expandable_message", "is_active", "created_at", "updated_at")
            val bannerRows = backup.banners.map { b ->
                listOf(
                    b.teamNumber.toString(),
                    b.message,
                    b.bannerType,
                    b.isDismissible.toString(),
                    b.isExpandable.toString(),
                    b.expandableMessage,
                    b.isActive.toString(),
                    b.createdAt.toString(),
                    b.updatedAt.toString()
                )
            }
            files["banners.csv"] = CSVHelper.toCSV(bannerHeaders, bannerRows)

            // Include Chats
            val chatHeaders = listOf("team_number", "group_name", "username", "content", "created_at", "reactions_json")
            val chatRows = backup.chatMessages.map { c ->
                listOf(
                    c.teamNumber.toString(),
                    c.groupName,
                    c.username,
                    c.content,
                    c.createdAt.toString(),
                    c.reactionsJson
                )
            }
            files["chat_messages.csv"] = CSVHelper.toCSV(chatHeaders, chatRows)
        }

        val bos = ByteArrayOutputStream()
        val zos = ZipOutputStream(bos)
        for ((name, content) in files) {
            val entry = ZipEntry(name)
            zos.putNextEntry(entry)
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        zos.close()
        return bos.toByteArray()
    }

    fun importCsv(targetTeamNumber: Int, zipBytes: ByteArray, currentUserId: Int): ImportReport {
        val files = readZip(zipBytes)
        
        // Parse CSV entries into objects
        val users = files["users.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                UserBackupDto(
                    username = r["username"]!!,
                    teamNumber = r["team_number"]!!.toInt(),
                    passwordHash = r["password_hash"]!!,
                    role = r["role"]!!,
                    createdAt = r["created_at"]!!.toLong(),
                    email = r["email"]?.takeIf { it.isNotBlank() },
                    profilePicture = r["profile_picture"]?.takeIf { it.isNotBlank() },
                    notificationPreference = r["notification_preference"] ?: "all"
                )
            }
        } ?: emptyList()

        val scoutingConfigs = mutableListOf<ConfigBackupDto>()
        val pitScoutingConfigs = mutableListOf<ConfigBackupDto>()
        val qualitativeScoutingConfigs = mutableListOf<ConfigBackupDto>()

        files["configs.csv"]?.let { content ->
            CSVHelper.parseCSV(content).forEach { r ->
                val type = r["config_type"]!!
                val dto = ConfigBackupDto(
                    teamNumber = r["team_number"]!!.toInt(),
                    configJson = r["config_json"]!!,
                    updatedAt = r["updated_at"]!!.toLong()
                )
                when (type) {
                    "game" -> scoutingConfigs.add(dto)
                    "pit" -> pitScoutingConfigs.add(dto)
                    "qual" -> qualitativeScoutingConfigs.add(dto)
                }
            }
        }

        val appSettings = files["app_settings.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                AppSettingsBackupDto(
                    teamNumber = r["team_number"]!!.toInt(),
                    settingsJson = r["settings_json"]!!,
                    updatedAt = r["updated_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val scoutingEntries = files["scouting_entries.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                ScoutingEntryBackupDto(
                    ownerTeamNumber = r["owner_team_number"]!!.toInt(),
                    targetTeamNumber = r["target_team_number"]?.toIntOrNull(),
                    eventKey = r["event_key"]?.takeIf { it.isNotBlank() },
                    matchKey = r["match_key"]?.takeIf { it.isNotBlank() },
                    matchNumber = r["match_number"]?.toIntOrNull(),
                    dataJson = r["data_json"]!!,
                    submittedByUsername = r["submitted_by_username"]!!,
                    createdAt = r["created_at"]!!.toLong(),
                    isPrescout = r["is_prescout"]!!.toBoolean(),
                    hasDiscrepancy = r["has_discrepancy"]!!.toBoolean(),
                    conflictingTeams = r["conflicting_teams"] ?: ""
                )
            }
        } ?: emptyList()

        val pitScoutingEntries = files["pit_scouting_entries.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                PitScoutingEntryBackupDto(
                    ownerTeamNumber = r["owner_team_number"]!!.toInt(),
                    targetTeamNumber = r["target_team_number"]?.toIntOrNull(),
                    eventKey = r["event_key"]?.takeIf { it.isNotBlank() },
                    dataJson = r["data_json"]!!,
                    submittedByUsername = r["submitted_by_username"]!!,
                    createdAt = r["created_at"]!!.toLong(),
                    isPrescout = r["is_prescout"]!!.toBoolean(),
                    hasDiscrepancy = r["has_discrepancy"]!!.toBoolean(),
                    conflictingTeams = r["conflicting_teams"] ?: ""
                )
            }
        } ?: emptyList()

        val qualitativeScoutingEntries = files["qualitative_scouting_entries.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                QualitativeScoutingEntryBackupDto(
                    ownerTeamNumber = r["owner_team_number"]!!.toInt(),
                    targetTeamNumber = r["target_team_number"]?.toIntOrNull(),
                    eventKey = r["event_key"]?.takeIf { it.isNotBlank() },
                    matchKey = r["match_key"]?.takeIf { it.isNotBlank() },
                    matchNumber = r["match_number"]?.toIntOrNull(),
                    dataJson = r["data_json"]!!,
                    submittedByUsername = r["submitted_by_username"]!!,
                    createdAt = r["created_at"]!!.toLong(),
                    isPrescout = r["is_prescout"]!!.toBoolean(),
                    hasDiscrepancy = r["has_discrepancy"]!!.toBoolean(),
                    conflictingTeams = r["conflicting_teams"] ?: ""
                )
            }
        } ?: emptyList()

        val alliances = files["alliances.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                ScoutingAllianceBackupDto(
                    id = r["id"]!!.toInt(),
                    name = r["name"]!!,
                    ownerTeamNumber = r["owner_team_number"]!!.toInt(),
                    eventKey = r["event_key"]?.takeIf { it.isNotBlank() },
                    notes = r["notes"]?.takeIf { it.isNotBlank() },
                    createdAt = r["created_at"]!!.toLong(),
                    updatedAt = r["updated_at"]!!.toLong(),
                    matchConfigJson = r["match_config_json"]?.takeIf { it.isNotBlank() },
                    pitConfigJson = r["pit_config_json"]?.takeIf { it.isNotBlank() },
                    qualitativeConfigJson = r["qualitative_config_json"]?.takeIf { it.isNotBlank() },
                    year = r["year"]?.toIntOrNull(),
                    eventCode = r["event_code"]?.takeIf { it.isNotBlank() }
                )
            }
        } ?: emptyList()

        val allianceMemberships = files["alliance_memberships.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                AllianceMembershipBackupDto(
                    allianceId = r["alliance_id"]!!.toInt(),
                    teamNumber = r["team_number"]!!.toInt(),
                    status = r["status"]!!,
                    invitedAt = r["invited_at"]!!.toLong(),
                    respondedAt = r["responded_at"]?.toLongOrNull(),
                    disabled = r["disabled"]!!.toBoolean(),
                    active = r["active"]!!.toBoolean()
                )
            }
        } ?: emptyList()

        val banners = files["banners.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                BannerBackupDto(
                    teamNumber = r["team_number"]!!.toInt(),
                    message = r["message"]!!,
                    bannerType = r["banner_type"]!!,
                    isDismissible = r["is_dismissible"]!!.toBoolean(),
                    isExpandable = r["is_expandable"]!!.toBoolean(),
                    expandableMessage = r["expandable_message"]!!,
                    isActive = r["is_active"]!!.toBoolean(),
                    createdAt = r["created_at"]!!.toLong(),
                    updatedAt = r["updated_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val chatMessages = files["chat_messages.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                ChatMessageBackupDto(
                    teamNumber = r["team_number"]!!.toInt(),
                    groupName = r["group_name"]!!,
                    username = r["username"]!!,
                    content = r["content"]!!,
                    createdAt = r["created_at"]!!.toLong(),
                    reactionsJson = r["reactions_json"]!!
                )
            }
        } ?: emptyList()

        val backup = ObsidianDbBackup(
            teamNumber = targetTeamNumber,
            type = if (users.isNotEmpty() || scoutingConfigs.isNotEmpty()) "entire" else "scouting",
            scoutingConfigs = scoutingConfigs,
            pitScoutingConfigs = pitScoutingConfigs,
            qualitativeScoutingConfigs = qualitativeScoutingConfigs,
            appSettings = appSettings,
            users = users,
            scoutingEntries = scoutingEntries,
            pitScoutingEntries = pitScoutingEntries,
            qualitativeScoutingEntries = qualitativeScoutingEntries,
            alliances = alliances,
            allianceMemberships = allianceMemberships,
            banners = banners,
            chatMessages = chatMessages
        )

        return importBackup(targetTeamNumber, backup, currentUserId)
    }

    private fun readZip(zipBytes: ByteArray): Map<String, String> {
        val bis = ByteArrayInputStream(zipBytes)
        val zis = ZipInputStream(bis)
        val result = mutableMapOf<String, String>()
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var len = zis.read(buffer)
                while (len > 0) {
                    out.write(buffer, 0, len)
                    len = zis.read(buffer)
                }
                result[entry.name] = out.toString("UTF-8")
            }
            entry = zis.nextEntry
        }
        zis.close()
        return result
    }
}
