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
import java.util.UUID

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
    val id: String,
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
    val allianceId: String,
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

// Global backup DTOs
@Serializable
data class ApiEventBackupDto(
    val eventKey: String,
    val year: Int,
    val eventCode: String?,
    val name: String,
    val startDate: String?,
    val endDate: String?,
    val timezone: String?,
    val dataJson: String,
    val updatedAt: Long
)

@Serializable
data class ApiTeamBackupDto(
    val eventKey: String,
    val teamKey: String,
    val teamNumber: Int,
    val name: String?,
    val nickname: String?,
    val city: String?,
    val state: String?,
    val country: String?,
    val opr: Double?,
    val epa: Double?,
    val dataJson: String,
    val updatedAt: Long
)

@Serializable
data class ApiMatchBackupDto(
    val matchKey: String,
    val eventKey: String,
    val compLevel: String,
    val setNumber: Int?,
    val matchNumber: Int?,
    val scheduledTime: Long?,
    val actualTime: Long?,
    val redTeams: String,
    val blueTeams: String,
    val dataJson: String,
    val updatedAt: Long
)

@Serializable
data class EpaOprHistoryCacheBackupDto(
    val eventKey: String,
    val oprsJson: String,
    val epaHistoryJson: String,
    val updatedAt: Long
)

@Serializable
data class AllianceSelectionBackupDto(
    val ownerKey: String,
    val eventKey: String,
    val selectionJson: String,
    val updatedAt: Long
)

@Serializable
data class PushSubscriptionBackupDto(
    val username: String,
    val teamNumber: Int,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val createdAt: Long
)

@Serializable
data class UserChatLastReadBackupDto(
    val username: String,
    val teamNumber: Int,
    val groupName: String,
    val lastReadAt: Long
)

@Serializable
data class PasswordResetTokenBackupDto(
    val username: String?,
    val teamNumber: Int?,
    val email: String?,
    val token: String,
    val expiresAt: Long,
    val used: Boolean
)

@Serializable
data class ObsidianDbBackup(
    val teamNumber: Int,
    val type: String, // "entire" or "scouting"
    val version: Int = 2, // version 2 supports scope
    val timestamp: Long = System.currentTimeMillis(),
    val scope: String = "team", // "team" or "global"
    
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
    val chatMessages: List<ChatMessageBackupDto> = emptyList(),

    // Global scope tables
    val apiEvents: List<ApiEventBackupDto> = emptyList(),
    val apiTeams: List<ApiTeamBackupDto> = emptyList(),
    val apiMatches: List<ApiMatchBackupDto> = emptyList(),
    val epaOprHistoryCache: List<EpaOprHistoryCacheBackupDto> = emptyList(),
    val allianceSelections: List<AllianceSelectionBackupDto> = emptyList(),
    val pushSubscriptions: List<PushSubscriptionBackupDto> = emptyList(),
    val userChatLastReads: List<UserChatLastReadBackupDto> = emptyList(),
    val passwordResetTokens: List<PasswordResetTokenBackupDto> = emptyList()
)

@Serializable
data class ImportReport(
    val success: Boolean,
    val type: String,
    val scope: String = "team",
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
    // Global fields
    val apiEventsImported: Int = 0,
    val apiEventsSkipped: Int = 0,
    val apiTeamsImported: Int = 0,
    val apiTeamsSkipped: Int = 0,
    val apiMatchesImported: Int = 0,
    val apiMatchesSkipped: Int = 0,
    val epaOprHistoryCacheImported: Int = 0,
    val epaOprHistoryCacheSkipped: Int = 0,
    val allianceSelectionsImported: Int = 0,
    val allianceSelectionsSkipped: Int = 0,
    val pushSubscriptionsImported: Int = 0,
    val pushSubscriptionsSkipped: Int = 0,
    val chatLastReadsImported: Int = 0,
    val chatLastReadsSkipped: Int = 0,
    val passwordResetTokensImported: Int = 0,
    val passwordResetTokensSkipped: Int = 0,
    val message: String = ""
)

object BackupService {

    fun exportBackup(teamNumber: Int, type: String, scope: String = "team"): ObsidianDbBackup {
        return transaction {
            val userMap = Users.selectAll()
                .associate { it[Users.id].value to Pair(it[Users.username], it[Users.teamNumber]) }

            val users = if (scope == "global") {
                Users.selectAll().map { row ->
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
            } else if (type == "entire") {
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

            val scoutingConfigs = if (scope == "global") {
                ScoutingConfigs.selectAll().map { row ->
                    ConfigBackupDto(row[ScoutingConfigs.teamNumber], row[ScoutingConfigs.configJson], row[ScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else if (type == "entire") {
                ScoutingConfigs.selectAll().where { ScoutingConfigs.teamNumber eq teamNumber }.map { row ->
                    ConfigBackupDto(row[ScoutingConfigs.teamNumber], row[ScoutingConfigs.configJson], row[ScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else emptyList()

            val pitScoutingConfigs = if (scope == "global") {
                PitScoutingConfigs.selectAll().map { row ->
                    ConfigBackupDto(row[PitScoutingConfigs.teamNumber], row[PitScoutingConfigs.configJson], row[PitScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else if (type == "entire") {
                PitScoutingConfigs.selectAll().where { PitScoutingConfigs.teamNumber eq teamNumber }.map { row ->
                    ConfigBackupDto(row[PitScoutingConfigs.teamNumber], row[PitScoutingConfigs.configJson], row[PitScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else emptyList()

            val qualitativeScoutingConfigs = if (scope == "global") {
                QualitativeScoutingConfigs.selectAll().map { row ->
                    ConfigBackupDto(row[QualitativeScoutingConfigs.teamNumber], row[QualitativeScoutingConfigs.configJson], row[QualitativeScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else if (type == "entire") {
                QualitativeScoutingConfigs.selectAll().where { QualitativeScoutingConfigs.teamNumber eq teamNumber }.map { row ->
                    ConfigBackupDto(row[QualitativeScoutingConfigs.teamNumber], row[QualitativeScoutingConfigs.configJson], row[QualitativeScoutingConfigs.updatedAt].toEpochMilli())
                }
            } else emptyList()

            val appSettings = if (scope == "global") {
                AppSettings.selectAll().map { row ->
                    AppSettingsBackupDto(row[AppSettings.teamNumber], row[AppSettings.settingsJson], row[AppSettings.updatedAt].toEpochMilli())
                }
            } else if (type == "entire") {
                AppSettings.selectAll().where { AppSettings.teamNumber eq teamNumber }.map { row ->
                    AppSettingsBackupDto(row[AppSettings.teamNumber], row[AppSettings.settingsJson], row[AppSettings.updatedAt].toEpochMilli())
                }
            } else emptyList()

            // Scouting entries
            val scoutingEntriesQuery = if (scope == "global") ScoutingEntries.selectAll() else ScoutingEntries.selectAll().where { ScoutingEntries.ownerTeamNumber eq teamNumber }
            val scoutingEntries = scoutingEntriesQuery.map { row ->
                val userId = row[ScoutingEntries.submittedByUserId].value
                val username = userMap[userId]?.first ?: "unknown_scout"
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

            val pitScoutingEntriesQuery = if (scope == "global") PitScoutingEntries.selectAll() else PitScoutingEntries.selectAll().where { PitScoutingEntries.ownerTeamNumber eq teamNumber }
            val pitScoutingEntries = pitScoutingEntriesQuery.map { row ->
                val userId = row[PitScoutingEntries.submittedByUserId].value
                val username = userMap[userId]?.first ?: "unknown_scout"
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

            val qualitativeScoutingEntriesQuery = if (scope == "global") QualitativeScoutingEntries.selectAll() else QualitativeScoutingEntries.selectAll().where { QualitativeScoutingEntries.ownerTeamNumber eq teamNumber }
            val qualitativeScoutingEntries = qualitativeScoutingEntriesQuery.map { row ->
                val userId = row[QualitativeScoutingEntries.submittedByUserId].value
                val username = userMap[userId]?.first ?: "unknown_scout"
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

            val alliancesQuery = if (scope == "global") ScoutingAlliances.selectAll() else ScoutingAlliances.selectAll().where { ScoutingAlliances.ownerTeamNumber eq teamNumber }
            val alliances = if (scope == "global" || type == "entire") {
                alliancesQuery.map { row ->
                    ScoutingAllianceBackupDto(
                        id = row[ScoutingAlliances.id].value.toString(),
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
            val allianceMemberships = if (scope == "global") {
                AllianceMemberships.selectAll().map { row ->
                    AllianceMembershipBackupDto(
                        allianceId = row[AllianceMemberships.allianceId].value.toString(),
                        teamNumber = row[AllianceMemberships.teamNumber],
                        status = row[AllianceMemberships.status],
                        invitedAt = row[AllianceMemberships.invitedAt].toEpochMilli(),
                        respondedAt = row[AllianceMemberships.respondedAt]?.toEpochMilli(),
                        disabled = row[AllianceMemberships.disabled],
                        active = row[AllianceMemberships.active]
                    )
                }
            } else if (type == "entire" && allianceIds.isNotEmpty()) {
                AllianceMemberships.selectAll().where { AllianceMemberships.allianceId inList allianceIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() } }.map { row ->
                    AllianceMembershipBackupDto(
                        allianceId = row[AllianceMemberships.allianceId].value.toString(),
                        teamNumber = row[AllianceMemberships.teamNumber],
                        status = row[AllianceMemberships.status],
                        invitedAt = row[AllianceMemberships.invitedAt].toEpochMilli(),
                        respondedAt = row[AllianceMemberships.respondedAt]?.toEpochMilli(),
                        disabled = row[AllianceMemberships.disabled],
                        active = row[AllianceMemberships.active]
                    )
                }
            } else emptyList()

            val bannersQuery = if (scope == "global") Banners.selectAll() else Banners.selectAll().where { Banners.teamNumber eq teamNumber }
            val banners = if (scope == "global" || type == "entire") {
                bannersQuery.map { row ->
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

            val chatMessagesQuery = if (scope == "global") ChatMessages.selectAll() else ChatMessages.selectAll().where { ChatMessages.teamNumber eq teamNumber }
            val chatMessages = if (scope == "global" || type == "entire") {
                chatMessagesQuery.map { row ->
                    val userId = row[ChatMessages.userId].value
                    val username = userMap[userId]?.first ?: row[ChatMessages.username]
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

            // System global tables
            val apiEvents = if (scope == "global") {
                ApiEvents.selectAll().map { row ->
                    ApiEventBackupDto(
                        eventKey = row[ApiEvents.eventKey],
                        year = row[ApiEvents.year],
                        eventCode = row[ApiEvents.eventCode],
                        name = row[ApiEvents.name],
                        startDate = row[ApiEvents.startDate],
                        endDate = row[ApiEvents.endDate],
                        timezone = row[ApiEvents.timezone],
                        dataJson = row[ApiEvents.dataJson],
                        updatedAt = row[ApiEvents.updatedAt].toEpochMilli()
                    )
                }
            } else emptyList()

            val apiTeams = if (scope == "global") {
                ApiTeams.selectAll().map { row ->
                    ApiTeamBackupDto(
                        eventKey = row[ApiTeams.eventKey],
                        teamKey = row[ApiTeams.teamKey],
                        teamNumber = row[ApiTeams.teamNumber],
                        name = row[ApiTeams.name],
                        nickname = row[ApiTeams.nickname],
                        city = row[ApiTeams.city],
                        state = row[ApiTeams.state],
                        country = row[ApiTeams.country],
                        opr = row[ApiTeams.opr],
                        epa = row[ApiTeams.epa],
                        dataJson = row[ApiTeams.dataJson],
                        updatedAt = row[ApiTeams.updatedAt].toEpochMilli()
                    )
                }
            } else emptyList()

            val apiMatches = if (scope == "global") {
                ApiMatches.selectAll().map { row ->
                    ApiMatchBackupDto(
                        matchKey = row[ApiMatches.matchKey],
                        eventKey = row[ApiMatches.eventKey],
                        compLevel = row[ApiMatches.compLevel],
                        setNumber = row[ApiMatches.setNumber],
                        matchNumber = row[ApiMatches.matchNumber],
                        scheduledTime = row[ApiMatches.scheduledTime],
                        actualTime = row[ApiMatches.actualTime],
                        redTeams = row[ApiMatches.redTeams],
                        blueTeams = row[ApiMatches.blueTeams],
                        dataJson = row[ApiMatches.dataJson],
                        updatedAt = row[ApiMatches.updatedAt].toEpochMilli()
                    )
                }
            } else emptyList()

            val epaOprHistoryCache = if (scope == "global") {
                EpaOprHistoryCache.selectAll().map { row ->
                    EpaOprHistoryCacheBackupDto(
                        eventKey = row[EpaOprHistoryCache.eventKey],
                        oprsJson = row[EpaOprHistoryCache.oprsJson],
                        epaHistoryJson = row[EpaOprHistoryCache.epaHistoryJson],
                        updatedAt = row[EpaOprHistoryCache.updatedAt].toEpochMilli()
                    )
                }
            } else emptyList()

            val allianceSelections = if (scope == "global") {
                AllianceSelections.selectAll().map { row ->
                    AllianceSelectionBackupDto(
                        ownerKey = row[AllianceSelections.ownerKey],
                        eventKey = row[AllianceSelections.eventKey],
                        selectionJson = row[AllianceSelections.selectionJson],
                        updatedAt = row[AllianceSelections.updatedAt].toEpochMilli()
                    )
                }
            } else emptyList()

            val pushSubscriptions = if (scope == "global") {
                PushSubscriptions.selectAll().map { row ->
                    val uid = row[PushSubscriptions.userId].value
                    val userPair = userMap[uid] ?: Pair("unknown", 0)
                    PushSubscriptionBackupDto(
                        username = userPair.first,
                        teamNumber = userPair.second,
                        endpoint = row[PushSubscriptions.endpoint],
                        p256dh = row[PushSubscriptions.p256dh],
                        auth = row[PushSubscriptions.auth],
                        createdAt = row[PushSubscriptions.createdAt].toEpochMilli()
                    )
                }
            } else emptyList()

            val userChatLastReads = if (scope == "global") {
                UserChatLastRead.selectAll().map { row ->
                    val uid = row[UserChatLastRead.userId].value
                    val userPair = userMap[uid] ?: Pair("unknown", 0)
                    UserChatLastReadBackupDto(
                        username = userPair.first,
                        teamNumber = userPair.second,
                        groupName = row[UserChatLastRead.groupName],
                        lastReadAt = row[UserChatLastRead.lastReadAt].toEpochMilli()
                    )
                }
            } else emptyList()

            val passwordResetTokens = if (scope == "global") {
                PasswordResetTokens.selectAll().map { row ->
                    val uid = row[PasswordResetTokens.userId]?.value
                    val userPair = uid?.let { userMap[it] }
                    PasswordResetTokenBackupDto(
                        username = userPair?.first,
                        teamNumber = userPair?.second,
                        email = row[PasswordResetTokens.email],
                        token = row[PasswordResetTokens.token],
                        expiresAt = row[PasswordResetTokens.expiresAt].toEpochMilli(),
                        used = row[PasswordResetTokens.used]
                    )
                }
            } else emptyList()

            ObsidianDbBackup(
                teamNumber = teamNumber,
                type = type,
                scope = scope,
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
                chatMessages = chatMessages,
                apiEvents = apiEvents,
                apiTeams = apiTeams,
                apiMatches = apiMatches,
                epaOprHistoryCache = epaOprHistoryCache,
                allianceSelections = allianceSelections,
                pushSubscriptions = pushSubscriptions,
                userChatLastReads = userChatLastReads,
                passwordResetTokens = passwordResetTokens
            )
        }
    }

    fun importBackup(targetTeamNumber: Int, backup: ObsidianDbBackup, currentUserId: String, isSuperAdmin: Boolean = false): ImportReport {
        return transaction {
            val isGlobalImport = isSuperAdmin && backup.scope == "global"

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

            // Global counts
            var apiEventsImported = 0
            var apiEventsSkipped = 0
            var apiTeamsImported = 0
            var apiTeamsSkipped = 0
            var apiMatchesImported = 0
            var apiMatchesSkipped = 0
            var epaOprHistoryCacheImported = 0
            var epaOprHistoryCacheSkipped = 0
            var allianceSelectionsImported = 0
            var allianceSelectionsSkipped = 0
            var pushSubscriptionsImported = 0
            var pushSubscriptionsSkipped = 0
            var chatLastReadsImported = 0
            var chatLastReadsSkipped = 0
            var passwordResetTokensImported = 0
            var passwordResetTokensSkipped = 0

            // Helper to enforce team scope for regular admins
            fun getTargetTeam(originalTeam: Int): Int {
                return if (isGlobalImport) originalTeam else targetTeamNumber
            }

            // 1. Users mapping: (username, teamNumber) -> target UserId
            val userMap = mutableMapOf<Pair<String, Int>, UUID>()
            
            // Query current users in target scope to populate map
            val usersQuery = if (isGlobalImport) Users.selectAll() else Users.selectAll().where { Users.teamNumber eq targetTeamNumber }
            usersQuery.forEach { row ->
                userMap[Pair(row[Users.username], row[Users.teamNumber])] = row[Users.id].value
            }

            if (backup.type == "entire") {
                for (u in backup.users) {
                    val assignedTeam = getTargetTeam(u.teamNumber)
                    val mapKey = Pair(u.username, assignedTeam)
                    val existingId = userMap[mapKey]
                    if (existingId != null) {
                        usersSkipped++
                    } else {
                        val newId = Users.insertAndGetId {
                            it[username] = u.username
                            it[teamNumber] = assignedTeam
                            it[passwordHash] = u.passwordHash
                            it[role] = u.role
                            it[createdAt] = Instant.ofEpochMilli(u.createdAt)
                            it[email] = u.email
                            it[profilePicture] = u.profilePicture
                            it[notificationPreference] = u.notificationPreference
                        }.value
                        userMap[mapKey] = newId
                        usersImported++
                    }
                }

                // 2. Import Configs
                for (c in backup.scoutingConfigs) {
                    val assignedTeam = getTargetTeam(c.teamNumber)
                    val exists = ScoutingConfigs.selectAll().where { ScoutingConfigs.teamNumber eq assignedTeam }.any()
                    if (exists) {
                        ScoutingConfigs.update({ ScoutingConfigs.teamNumber eq assignedTeam }) {
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsUpdated++
                    } else {
                        ScoutingConfigs.insert {
                            it[teamNumber] = assignedTeam
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsImported++
                    }
                }
                
                // Pit scouting configs
                for (c in backup.pitScoutingConfigs) {
                    val assignedTeam = getTargetTeam(c.teamNumber)
                    val exists = PitScoutingConfigs.selectAll().where { PitScoutingConfigs.teamNumber eq assignedTeam }.any()
                    if (exists) {
                        PitScoutingConfigs.update({ PitScoutingConfigs.teamNumber eq assignedTeam }) {
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsUpdated++
                    } else {
                        PitScoutingConfigs.insert {
                            it[teamNumber] = assignedTeam
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsImported++
                    }
                }

                // Qualitative scouting configs
                for (c in backup.qualitativeScoutingConfigs) {
                    val assignedTeam = getTargetTeam(c.teamNumber)
                    val exists = QualitativeScoutingConfigs.selectAll().where { QualitativeScoutingConfigs.teamNumber eq assignedTeam }.any()
                    if (exists) {
                        QualitativeScoutingConfigs.update({ QualitativeScoutingConfigs.teamNumber eq assignedTeam }) {
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsUpdated++
                    } else {
                        QualitativeScoutingConfigs.insert {
                            it[teamNumber] = assignedTeam
                            it[configJson] = c.configJson
                            it[updatedAt] = Instant.ofEpochMilli(c.updatedAt)
                        }
                        configsImported++
                    }
                }

                // App settings
                for (s in backup.appSettings) {
                    val assignedTeam = getTargetTeam(s.teamNumber)
                    val exists = AppSettings.selectAll().where { AppSettings.teamNumber eq assignedTeam }.any()
                    if (exists) {
                        AppSettings.update({ AppSettings.teamNumber eq assignedTeam }) {
                            it[settingsJson] = s.settingsJson
                            it[updatedAt] = Instant.ofEpochMilli(s.updatedAt)
                        }
                        settingsUpdated++
                    } else {
                        AppSettings.insert {
                            it[teamNumber] = assignedTeam
                            it[settingsJson] = s.settingsJson
                            it[updatedAt] = Instant.ofEpochMilli(s.updatedAt)
                        }
                        settingsImported++
                    }
                }
            }

            // Helpers to resolve UserId
            fun getUserId(username: String, originalTeam: Int): UUID {
                val assignedTeam = getTargetTeam(originalTeam)
                return userMap[Pair(username, assignedTeam)] ?: runCatching { UUID.fromString(currentUserId) }.getOrElse { UUID.randomUUID() }
            }

            // 3. Scouting Entries import
            for (e in backup.scoutingEntries) {
                val assignedTeam = getTargetTeam(e.ownerTeamNumber)
                val userId = getUserId(e.submittedByUsername, e.ownerTeamNumber)
                val exists = ScoutingEntries.selectAll().where {
                    (ScoutingEntries.ownerTeamNumber eq assignedTeam) and
                    (ScoutingEntries.createdAt eq Instant.ofEpochMilli(e.createdAt)) and
                    (ScoutingEntries.targetTeamNumber eq e.targetTeamNumber) and
                    (ScoutingEntries.eventKey eq e.eventKey) and
                    (ScoutingEntries.matchNumber eq e.matchNumber)
                }.any()

                if (exists) {
                    scoutingEntriesSkipped++
                } else {
                    ScoutingEntries.insert {
                        it[ownerTeamNumber] = assignedTeam
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
                val assignedTeam = getTargetTeam(e.ownerTeamNumber)
                val userId = getUserId(e.submittedByUsername, e.ownerTeamNumber)
                val exists = PitScoutingEntries.selectAll().where {
                    (PitScoutingEntries.ownerTeamNumber eq assignedTeam) and
                    (PitScoutingEntries.createdAt eq Instant.ofEpochMilli(e.createdAt)) and
                    (PitScoutingEntries.targetTeamNumber eq e.targetTeamNumber) and
                    (PitScoutingEntries.eventKey eq e.eventKey)
                }.any()

                if (exists) {
                    pitEntriesSkipped++
                } else {
                    PitScoutingEntries.insert {
                        it[ownerTeamNumber] = assignedTeam
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
                val assignedTeam = getTargetTeam(e.ownerTeamNumber)
                val userId = getUserId(e.submittedByUsername, e.ownerTeamNumber)
                val exists = QualitativeScoutingEntries.selectAll().where {
                    (QualitativeScoutingEntries.ownerTeamNumber eq assignedTeam) and
                    (QualitativeScoutingEntries.createdAt eq Instant.ofEpochMilli(e.createdAt)) and
                    (QualitativeScoutingEntries.targetTeamNumber eq e.targetTeamNumber) and
                    (QualitativeScoutingEntries.eventKey eq e.eventKey) and
                    (QualitativeScoutingEntries.matchNumber eq e.matchNumber)
                }.any()

                if (exists) {
                    qualEntriesSkipped++
                } else {
                    QualitativeScoutingEntries.insert {
                        it[ownerTeamNumber] = assignedTeam
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

            // 4. Alliances and Alliance memberships
            if (backup.scope == "global" || backup.type == "entire") {
                val allianceIdMap = mutableMapOf<String, UUID>()

                for (a in backup.alliances) {
                    val assignedTeam = getTargetTeam(a.ownerTeamNumber)
                    val existingRow = ScoutingAlliances.selectAll().where {
                        (ScoutingAlliances.ownerTeamNumber eq assignedTeam) and
                        (ScoutingAlliances.name eq a.name) and
                        (ScoutingAlliances.eventKey eq a.eventKey)
                    }.firstOrNull()

                    if (existingRow != null) {
                        allianceIdMap[a.id] = existingRow[ScoutingAlliances.id].value
                        alliancesSkipped++
                    } else {
                        val newId = ScoutingAlliances.insertAndGetId {
                            it[name] = a.name
                            it[ownerTeamNumber] = assignedTeam
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
                    val assignedTeam = getTargetTeam(b.teamNumber)
                    val exists = Banners.selectAll().where {
                        (Banners.teamNumber eq assignedTeam) and
                        (Banners.message eq b.message) and
                        (Banners.createdAt eq Instant.ofEpochMilli(b.createdAt))
                    }.any()

                    if (exists) {
                        bannersSkipped++
                    } else {
                        Banners.insert {
                            it[teamNumber] = assignedTeam
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
                    val assignedTeam = getTargetTeam(c.teamNumber)
                    val userId = getUserId(c.username, c.teamNumber)
                    val exists = ChatMessages.selectAll().where {
                        (ChatMessages.teamNumber eq assignedTeam) and
                        (ChatMessages.content eq c.content) and
                        (ChatMessages.createdAt eq Instant.ofEpochMilli(c.createdAt)) and
                        (ChatMessages.userId eq userId)
                    }.any()

                    if (exists) {
                        chatsSkipped++
                    } else {
                        ChatMessages.insert {
                            it[teamNumber] = assignedTeam
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

            // Global System table inserts (only for superadmins performing a global import)
            if (isGlobalImport) {
                // 7. ApiEvents
                for (ae in backup.apiEvents) {
                    val exists = ApiEvents.selectAll().where { ApiEvents.eventKey eq ae.eventKey }.any()
                    if (exists) {
                        ApiEvents.update({ ApiEvents.eventKey eq ae.eventKey }) {
                            it[year] = ae.year
                            it[eventCode] = ae.eventCode
                            it[name] = ae.name
                            it[startDate] = ae.startDate
                            it[endDate] = ae.endDate
                            it[timezone] = ae.timezone
                            it[dataJson] = ae.dataJson
                            it[updatedAt] = Instant.ofEpochMilli(ae.updatedAt)
                        }
                        apiEventsSkipped++
                    } else {
                        ApiEvents.insert {
                            it[eventKey] = ae.eventKey
                            it[year] = ae.year
                            it[eventCode] = ae.eventCode
                            it[name] = ae.name
                            it[startDate] = ae.startDate
                            it[endDate] = ae.endDate
                            it[timezone] = ae.timezone
                            it[dataJson] = ae.dataJson
                            it[updatedAt] = Instant.ofEpochMilli(ae.updatedAt)
                        }
                        apiEventsImported++
                    }
                }

                // 8. ApiTeams
                for (at in backup.apiTeams) {
                    val exists = ApiTeams.selectAll().where { (ApiTeams.eventKey eq at.eventKey) and (ApiTeams.teamKey eq at.teamKey) }.any()
                    if (exists) {
                        ApiTeams.update({ (ApiTeams.eventKey eq at.eventKey) and (ApiTeams.teamKey eq at.teamKey) }) {
                            it[teamNumber] = at.teamNumber
                            it[name] = at.name
                            it[nickname] = at.nickname
                            it[city] = at.city
                            it[state] = at.state
                            it[country] = at.country
                            it[opr] = at.opr
                            it[epa] = at.epa
                            it[dataJson] = at.dataJson
                            it[updatedAt] = Instant.ofEpochMilli(at.updatedAt)
                        }
                        apiTeamsSkipped++
                    } else {
                        ApiTeams.insert {
                            it[eventKey] = at.eventKey
                            it[teamKey] = at.teamKey
                            it[teamNumber] = at.teamNumber
                            it[name] = at.name
                            it[nickname] = at.nickname
                            it[city] = at.city
                            it[state] = at.state
                            it[country] = at.country
                            it[opr] = at.opr
                            it[epa] = at.epa
                            it[dataJson] = at.dataJson
                            it[updatedAt] = Instant.ofEpochMilli(at.updatedAt)
                        }
                        apiTeamsImported++
                    }
                }

                // 9. ApiMatches
                for (am in backup.apiMatches) {
                    val exists = ApiMatches.selectAll().where { ApiMatches.matchKey eq am.matchKey }.any()
                    if (exists) {
                        ApiMatches.update({ ApiMatches.matchKey eq am.matchKey }) {
                            it[eventKey] = am.eventKey
                            it[compLevel] = am.compLevel
                            it[setNumber] = am.setNumber
                            it[matchNumber] = am.matchNumber
                            it[scheduledTime] = am.scheduledTime
                            it[actualTime] = am.actualTime
                            it[redTeams] = am.redTeams
                            it[blueTeams] = am.blueTeams
                            it[dataJson] = am.dataJson
                            it[updatedAt] = Instant.ofEpochMilli(am.updatedAt)
                        }
                        apiMatchesSkipped++
                    } else {
                        ApiMatches.insert {
                            it[matchKey] = am.matchKey
                            it[eventKey] = am.eventKey
                            it[compLevel] = am.compLevel
                            it[setNumber] = am.setNumber
                            it[matchNumber] = am.matchNumber
                            it[scheduledTime] = am.scheduledTime
                            it[actualTime] = am.actualTime
                            it[redTeams] = am.redTeams
                            it[blueTeams] = am.blueTeams
                            it[dataJson] = am.dataJson
                            it[updatedAt] = Instant.ofEpochMilli(am.updatedAt)
                        }
                        apiMatchesImported++
                    }
                }

                // 10. EpaOprHistoryCache
                for (e in backup.epaOprHistoryCache) {
                    val exists = EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq e.eventKey }.any()
                    if (exists) {
                        EpaOprHistoryCache.update({ EpaOprHistoryCache.eventKey eq e.eventKey }) {
                            it[oprsJson] = e.oprsJson
                            it[epaHistoryJson] = e.epaHistoryJson
                            it[updatedAt] = Instant.ofEpochMilli(e.updatedAt)
                        }
                        epaOprHistoryCacheSkipped++
                    } else {
                        EpaOprHistoryCache.insert {
                            it[eventKey] = e.eventKey
                            it[oprsJson] = e.oprsJson
                            it[epaHistoryJson] = e.epaHistoryJson
                            it[updatedAt] = Instant.ofEpochMilli(e.updatedAt)
                        }
                        epaOprHistoryCacheImported++
                    }
                }

                // 11. AllianceSelections
                for (als in backup.allianceSelections) {
                    val exists = AllianceSelections.selectAll().where { (AllianceSelections.ownerKey eq als.ownerKey) and (AllianceSelections.eventKey eq als.eventKey) }.any()
                    if (exists) {
                        AllianceSelections.update({ (AllianceSelections.ownerKey eq als.ownerKey) and (AllianceSelections.eventKey eq als.eventKey) }) {
                            it[selectionJson] = als.selectionJson
                            it[updatedAt] = Instant.ofEpochMilli(als.updatedAt)
                        }
                        allianceSelectionsSkipped++
                    } else {
                        AllianceSelections.insert {
                            it[ownerKey] = als.ownerKey
                            it[eventKey] = als.eventKey
                            it[selectionJson] = als.selectionJson
                            it[updatedAt] = Instant.ofEpochMilli(als.updatedAt)
                        }
                        allianceSelectionsImported++
                    }
                }

                // 12. PushSubscriptions
                for (p in backup.pushSubscriptions) {
                    val userId = getUserId(p.username, p.teamNumber)
                    val exists = PushSubscriptions.selectAll().where { PushSubscriptions.endpoint eq p.endpoint }.any()
                    if (exists) {
                        pushSubscriptionsSkipped++
                    } else {
                        PushSubscriptions.insert {
                            it[PushSubscriptions.userId] = EntityID(userId, Users)
                            it[endpoint] = p.endpoint
                            it[p256dh] = p.p256dh
                            it[auth] = p.auth
                            it[createdAt] = Instant.ofEpochMilli(p.createdAt)
                        }
                        pushSubscriptionsImported++
                    }
                }

                // 13. UserChatLastReads
                for (lr in backup.userChatLastReads) {
                    val userId = getUserId(lr.username, lr.teamNumber)
                    val exists = UserChatLastRead.selectAll().where { (UserChatLastRead.userId eq userId) and (UserChatLastRead.groupName eq lr.groupName) }.any()
                    if (exists) {
                        UserChatLastRead.update({ (UserChatLastRead.userId eq userId) and (UserChatLastRead.groupName eq lr.groupName) }) {
                            it[lastReadAt] = Instant.ofEpochMilli(lr.lastReadAt)
                        }
                        chatLastReadsSkipped++
                    } else {
                        UserChatLastRead.insert {
                            it[UserChatLastRead.userId] = EntityID(userId, Users)
                            it[groupName] = lr.groupName
                            it[lastReadAt] = Instant.ofEpochMilli(lr.lastReadAt)
                        }
                        chatLastReadsImported++
                    }
                }

                // 14. PasswordResetTokens
                for (t in backup.passwordResetTokens) {
                    val userId = t.username?.let { getUserId(it, t.teamNumber ?: 0) }
                    val exists = PasswordResetTokens.selectAll().where { PasswordResetTokens.token eq t.token }.any()
                    if (exists) {
                        passwordResetTokensSkipped++
                    } else {
                        PasswordResetTokens.insert {
                            it[PasswordResetTokens.userId] = userId?.let { EntityID(it, Users) }
                            it[email] = t.email
                            it[token] = t.token
                            it[expiresAt] = Instant.ofEpochMilli(t.expiresAt)
                            it[used] = t.used
                        }
                        passwordResetTokensImported++
                    }
                }
            }

            ImportReport(
                success = true,
                type = backup.type,
                scope = backup.scope,
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
                apiEventsImported = apiEventsImported,
                apiEventsSkipped = apiEventsSkipped,
                apiTeamsImported = apiTeamsImported,
                apiTeamsSkipped = apiTeamsSkipped,
                apiMatchesImported = apiMatchesImported,
                apiMatchesSkipped = apiMatchesSkipped,
                epaOprHistoryCacheImported = epaOprHistoryCacheImported,
                epaOprHistoryCacheSkipped = epaOprHistoryCacheSkipped,
                allianceSelectionsImported = allianceSelectionsImported,
                allianceSelectionsSkipped = allianceSelectionsSkipped,
                pushSubscriptionsImported = pushSubscriptionsImported,
                pushSubscriptionsSkipped = pushSubscriptionsSkipped,
                chatLastReadsImported = chatLastReadsImported,
                chatLastReadsSkipped = chatLastReadsSkipped,
                passwordResetTokensImported = passwordResetTokensImported,
                passwordResetTokensSkipped = passwordResetTokensSkipped,
                message = "Backup imported successfully"
            )
        }
    }

    fun exportCsv(teamNumber: Int, type: String, scope: String = "team"): ByteArray {
        val backup = exportBackup(teamNumber, type, scope)
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

        if (scope == "global" || type == "entire") {
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

        // Global System CSV Exports
        if (scope == "global") {
            val apiEventHeaders = listOf("event_key", "year", "event_code", "name", "start_date", "end_date", "timezone", "data_json", "updated_at")
            val apiEventRows = backup.apiEvents.map { ae ->
                listOf(ae.eventKey, ae.year.toString(), ae.eventCode ?: "", ae.name, ae.startDate ?: "", ae.endDate ?: "", ae.timezone ?: "", ae.dataJson, ae.updatedAt.toString())
            }
            files["api_events.csv"] = CSVHelper.toCSV(apiEventHeaders, apiEventRows)

            val apiTeamHeaders = listOf("event_key", "team_key", "team_number", "name", "nickname", "city", "state", "country", "opr", "epa", "data_json", "updated_at")
            val apiTeamRows = backup.apiTeams.map { at ->
                listOf(at.eventKey, at.teamKey, at.teamNumber.toString(), at.name ?: "", at.nickname ?: "", at.city ?: "", at.state ?: "", at.country ?: "", at.opr?.toString() ?: "", at.epa?.toString() ?: "", at.dataJson, at.updatedAt.toString())
            }
            files["api_teams.csv"] = CSVHelper.toCSV(apiTeamHeaders, apiTeamRows)

            val apiMatchHeaders = listOf("match_key", "event_key", "comp_level", "set_number", "match_number", "scheduled_time", "actual_time", "red_teams", "blue_teams", "data_json", "updated_at")
            val apiMatchRows = backup.apiMatches.map { am ->
                listOf(am.matchKey, am.eventKey, am.compLevel, am.setNumber?.toString() ?: "", am.matchNumber?.toString() ?: "", am.scheduledTime?.toString() ?: "", am.actualTime?.toString() ?: "", am.redTeams, am.blueTeams, am.dataJson, am.updatedAt.toString())
            }
            files["api_matches.csv"] = CSVHelper.toCSV(apiMatchHeaders, apiMatchRows)

            val epaOprHeaders = listOf("event_key", "oprs_json", "epa_history_json", "updated_at")
            val epaOprRows = backup.epaOprHistoryCache.map { e ->
                listOf(e.eventKey, e.oprsJson, e.epaHistoryJson, e.updatedAt.toString())
            }
            files["epa_opr_history_cache.csv"] = CSVHelper.toCSV(epaOprHeaders, epaOprRows)

            val allianceSelHeaders = listOf("owner_key", "event_key", "selection_json", "updated_at")
            val allianceSelRows = backup.allianceSelections.map { als ->
                listOf(als.ownerKey, als.eventKey, als.selectionJson, als.updatedAt.toString())
            }
            files["alliance_selections.csv"] = CSVHelper.toCSV(allianceSelHeaders, allianceSelRows)

            val pushSubHeaders = listOf("username", "team_number", "endpoint", "p256dh", "auth", "created_at")
            val pushSubRows = backup.pushSubscriptions.map { p ->
                listOf(p.username, p.teamNumber.toString(), p.endpoint, p.p256dh, p.auth, p.createdAt.toString())
            }
            files["push_subscriptions.csv"] = CSVHelper.toCSV(pushSubHeaders, pushSubRows)

            val lastReadHeaders = listOf("username", "team_number", "group_name", "last_read_at")
            val lastReadRows = backup.userChatLastReads.map { lr ->
                listOf(lr.username, lr.teamNumber.toString(), lr.groupName, lr.lastReadAt.toString())
            }
            files["user_chat_last_reads.csv"] = CSVHelper.toCSV(lastReadHeaders, lastReadRows)

            val tokenHeaders = listOf("username", "team_number", "email", "token", "expires_at", "used")
            val tokenRows = backup.passwordResetTokens.map { t ->
                listOf(t.username ?: "", t.teamNumber?.toString() ?: "", t.email ?: "", t.token, t.expiresAt.toString(), t.used.toString())
            }
            files["password_reset_tokens.csv"] = CSVHelper.toCSV(tokenHeaders, tokenRows)
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

    fun importCsv(targetTeamNumber: Int, zipBytes: ByteArray, currentUserId: String, isSuperAdmin: Boolean = false): ImportReport {
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
                    id = r["id"]!!,
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
                    allianceId = r["alliance_id"]!!, // UUID string
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

        // Global System CSV imports
        val apiEvents = files["api_events.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                ApiEventBackupDto(
                    eventKey = r["event_key"]!!,
                    year = r["year"]!!.toInt(),
                    eventCode = r["event_code"]?.takeIf { it.isNotBlank() },
                    name = r["name"]!!,
                    startDate = r["start_date"]?.takeIf { it.isNotBlank() },
                    endDate = r["end_date"]?.takeIf { it.isNotBlank() },
                    timezone = r["timezone"]?.takeIf { it.isNotBlank() },
                    dataJson = r["data_json"]!!,
                    updatedAt = r["updated_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val apiTeams = files["api_teams.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                ApiTeamBackupDto(
                    eventKey = r["event_key"]!!,
                    teamKey = r["team_key"]!!,
                    teamNumber = r["team_number"]!!.toInt(),
                    name = r["name"]?.takeIf { it.isNotBlank() },
                    nickname = r["nickname"]?.takeIf { it.isNotBlank() },
                    city = r["city"]?.takeIf { it.isNotBlank() },
                    state = r["state"]?.takeIf { it.isNotBlank() },
                    country = r["country"]?.takeIf { it.isNotBlank() },
                    opr = r["opr"]?.toDoubleOrNull(),
                    epa = r["epa"]?.toDoubleOrNull(),
                    dataJson = r["data_json"]!!,
                    updatedAt = r["updated_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val apiMatches = files["api_matches.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                ApiMatchBackupDto(
                    matchKey = r["match_key"]!!,
                    eventKey = r["event_key"]!!,
                    compLevel = r["comp_level"]!!,
                    setNumber = r["set_number"]?.toIntOrNull(),
                    matchNumber = r["match_number"]?.toIntOrNull(),
                    scheduledTime = r["scheduled_time"]?.toLongOrNull(),
                    actualTime = r["actual_time"]?.toLongOrNull(),
                    redTeams = r["red_teams"]!!,
                    blueTeams = r["blue_teams"]!!,
                    dataJson = r["data_json"]!!,
                    updatedAt = r["updated_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val epaOprHistoryCache = files["epa_opr_history_cache.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                EpaOprHistoryCacheBackupDto(
                    eventKey = r["event_key"]!!,
                    oprsJson = r["oprs_json"]!!,
                    epaHistoryJson = r["epa_history_json"]!!,
                    updatedAt = r["updated_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val allianceSelections = files["alliance_selections.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                AllianceSelectionBackupDto(
                    ownerKey = r["owner_key"]!!,
                    eventKey = r["event_key"]!!,
                    selectionJson = r["selection_json"]!!,
                    updatedAt = r["updated_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val pushSubscriptions = files["push_subscriptions.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                PushSubscriptionBackupDto(
                    username = r["username"]!!,
                    teamNumber = r["team_number"]!!.toInt(),
                    endpoint = r["endpoint"]!!,
                    p256dh = r["p256dh"]!!,
                    auth = r["auth"]!!,
                    createdAt = r["created_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val userChatLastReads = files["user_chat_last_reads.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                UserChatLastReadBackupDto(
                    username = r["username"]!!,
                    teamNumber = r["team_number"]!!.toInt(),
                    groupName = r["group_name"]!!,
                    lastReadAt = r["last_read_at"]!!.toLong()
                )
            }
        } ?: emptyList()

        val passwordResetTokens = files["password_reset_tokens.csv"]?.let { content ->
            CSVHelper.parseCSV(content).map { r ->
                PasswordResetTokenBackupDto(
                    username = r["username"]?.takeIf { it.isNotBlank() },
                    teamNumber = r["team_number"]?.toIntOrNull(),
                    email = r["email"]?.takeIf { it.isNotBlank() },
                    token = r["token"]!!,
                    expiresAt = r["expires_at"]!!.toLong(),
                    used = r["used"]!!.toBoolean()
                )
            }
        } ?: emptyList()

        val isGlobal = isSuperAdmin && apiEvents.isNotEmpty()
        val backup = ObsidianDbBackup(
            teamNumber = targetTeamNumber,
            type = if (users.isNotEmpty() || scoutingConfigs.isNotEmpty()) "entire" else "scouting",
            scope = if (isGlobal) "global" else "team",
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
            chatMessages = chatMessages,
            apiEvents = apiEvents,
            apiTeams = apiTeams,
            apiMatches = apiMatches,
            epaOprHistoryCache = epaOprHistoryCache,
            allianceSelections = allianceSelections,
            pushSubscriptions = pushSubscriptions,
            userChatLastReads = userChatLastReads,
            passwordResetTokens = passwordResetTokens
        )

        return importBackup(targetTeamNumber, backup, currentUserId, isSuperAdmin)
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
