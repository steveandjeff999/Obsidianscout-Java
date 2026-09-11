package com.obsidianscout.db

import com.obsidianscout.auth.AuthService
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.AutoBackupConfig
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class SnapshotInfoDto(
    val fileName: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
    val isAutoBackup: Boolean = false
)

@Serializable
data class SnapshotResult(
    val success: Boolean,
    val message: String,
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    val recordsCopied: Long = 0L,
    val prunedCount: Int = 0
)

@Serializable
data class DatabaseRestoreReport(
    val success: Boolean,
    val message: String,
    val sourceFile: String,
    val totalRecordsRestored: Long = 0L,
    val restoredTables: Map<String, Long> = emptyMap(),
    val durationMs: Long = 0L
)

@Serializable
data class AutoBackupNodeStatusDto(
    val nodeIp: String,
    val isLocal: Boolean,
    val enabled: Boolean,
    val isAvailable: Boolean,
    val targetTimeUtc: String = "02:54",
    val retentionDays: Int = 30,
    val storageDirectory: String = "data/snapshots",
    val lastBackupTimestamp: String? = null,
    val lastBackupStatus: String = "None",
    val nextScheduledRunUtc: String? = null,
    val snapshotCount: Int = 0,
    val totalStorageBytes: Long = 0L,
    val snapshots: List<SnapshotInfoDto> = emptyList()
)

object SnapshotService {

    private val isSnapshotInProgress = AtomicBoolean(false)
    private val isRestoreInProgress = AtomicBoolean(false)

    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC)

    val allTables: Array<Table> = arrayOf(
        Users,
        UserSessions,
        ScoutingConfigs,
        PitScoutingConfigs,
        QualitativeScoutingConfigs,
        ConfigRevisions,
        DefaultConfigs,
        ScoutingEntries,
        PitScoutingEntries,
        QualitativeScoutingEntries,
        AppSettings,
        ApiEvents,
        ApiTeams,
        ApiMatches,
        ScoutingAlliances,
        AllianceMemberships,
        AllianceSelections,
        EpaOprHistoryCache,
        PasswordResetTokens,
        Banners,
        ChatGroups,
        ChatMessages,
        UserChatLastRead,
        PushSubscriptions,
        FcmConfigs,
        FcmDeviceTokens,
        ClusterSecrets,
        ClusterNotificationLocks,
        AnalyticsReports,
        ReportedErrors
    )

    var customStorageDirectory: File? = null

    private fun getStorageDir(): File {
        val dir = customStorageDirectory ?: File(AppConfigLoader.load().auto_backup.storage_directory.ifBlank { "data/snapshots" })
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun ensureSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (_: Exception) {}
    }

    /**
     * Creates a complete snapshot of the database and stores it locally as SQLite.
     * Automatically prunes snapshots older than retention_days if configured.
     */
    fun createSnapshot(isAutoBackup: Boolean = false): SnapshotResult {
        if (!DatabaseFactory.isReady) {
            return SnapshotResult(false, "Database is not ready")
        }
        if (!isSnapshotInProgress.compareAndSet(false, true)) {
            return SnapshotResult(false, "Another snapshot operation is already in progress")
        }

        try {
            val storageDir = getStorageDir()
            val nowUtc = Instant.now()
            val prefix = if (isAutoBackup) "auto_snapshot" else "manual_snapshot"
            val timestampStr = fileNameFormatter.format(nowUtc)
            val snapshotFile = File(storageDir, "${prefix}_${timestampStr}.db")

            println("[SnapshotService] Creating full SQLite database snapshot at ${snapshotFile.absolutePath}...")

            val recordsCopied = copyDatabaseToSqlite(snapshotFile)
            val sizeBytes = if (snapshotFile.exists()) snapshotFile.length() else 0L

            // Auto-prune old snapshots based on retention days
            val config = AppConfigLoader.load().auto_backup
            val pruned = pruneOldSnapshots(config.retention_days)

            println("[SnapshotService] Snapshot created successfully: ${snapshotFile.name} ($sizeBytes bytes, $recordsCopied records). Pruned $pruned old snapshots.")
            return SnapshotResult(
                success = true,
                message = "Snapshot created successfully",
                fileName = snapshotFile.name,
                sizeBytes = sizeBytes,
                recordsCopied = recordsCopied,
                prunedCount = pruned
            )
        } catch (e: Exception) {
            println("[SnapshotService] Error creating snapshot: ${e.message}")
            e.printStackTrace()
            return SnapshotResult(false, "Snapshot failed: ${e.message}")
        } finally {
            isSnapshotInProgress.set(false)
        }
    }

    /**
     * Writes all tables and rows into the destination SQLite file.
     */
    private fun copyDatabaseToSqlite(targetFile: File): Long {
        ensureSqliteDriver()
        if (targetFile.exists()) {
            targetFile.delete()
        }

        // Fast-path: if primary database is SQLite, try VACUUM INTO
        if (!DatabaseFactory.isPostgresCompatible) {
            try {
                val escapedPath = targetFile.absolutePath.replace("'", "''")
                val vacuumSuccess = transaction {
                    exec("VACUUM INTO '$escapedPath'")
                    true
                } ?: false
                if (vacuumSuccess && targetFile.exists() && targetFile.length() > 0) {
                    println("[SnapshotService] VACUUM INTO succeeded for SQLite primary database.")
                    return countTotalRecords()
                }
            } catch (e: Exception) {
                println("[SnapshotService] VACUUM INTO failed (${e.message}), falling back to table-by-table copy.")
            }
        }

        // Universal table-by-table copy into standalone SQLite database
        val previousDefaultDb = DatabaseFactory.primaryDatabase
        val sqliteUrl = "jdbc:sqlite:${targetFile.absolutePath}?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL"
        val snapshotDb = Database.connect(
            sqliteUrl,
            driver = "org.sqlite.JDBC",
            databaseConfig = org.jetbrains.exposed.sql.DatabaseConfig {
                defaultMaxAttempts = 1
                defaultIsolationLevel = java.sql.Connection.TRANSACTION_SERIALIZABLE
            }
        )

        try {
            // 1. Create tables in the new SQLite database
            transaction(
                transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                readOnly = false,
                db = snapshotDb
            ) {
                SchemaUtils.createMissingTablesAndColumns(*allTables)
            }

            // 2. Read all datasets from the primary database
            val usersList = DatabaseFactory.readTransaction { Users.selectAll().toList() }
            val userSessionsList = DatabaseFactory.readTransaction { UserSessions.selectAll().toList() }
            val scoutingConfigsList = DatabaseFactory.readTransaction { ScoutingConfigs.selectAll().toList() }
            val pitConfigsList = DatabaseFactory.readTransaction { PitScoutingConfigs.selectAll().toList() }
            val qualConfigsList = DatabaseFactory.readTransaction { QualitativeScoutingConfigs.selectAll().toList() }
            val defaultConfigsList = DatabaseFactory.readTransaction { DefaultConfigs.selectAll().toList() }
            val configRevisionsList = DatabaseFactory.readTransaction { ConfigRevisions.selectAll().toList() }
            val appSettingsList = DatabaseFactory.readTransaction { AppSettings.selectAll().toList() }
            val apiEventsList = DatabaseFactory.readTransaction { ApiEvents.selectAll().toList() }
            val apiTeamsList = DatabaseFactory.readTransaction { ApiTeams.selectAll().toList() }
            val apiMatchesList = DatabaseFactory.readTransaction { ApiMatches.selectAll().toList() }
            val scoutingEntriesList = DatabaseFactory.readTransaction { ScoutingEntries.selectAll().toList() }
            val pitEntriesList = DatabaseFactory.readTransaction { PitScoutingEntries.selectAll().toList() }
            val qualEntriesList = DatabaseFactory.readTransaction { QualitativeScoutingEntries.selectAll().toList() }
            val alliancesList = DatabaseFactory.readTransaction { ScoutingAlliances.selectAll().toList() }
            val allianceMembershipsList = DatabaseFactory.readTransaction { AllianceMemberships.selectAll().toList() }
            val allianceSelectionsList = DatabaseFactory.readTransaction { AllianceSelections.selectAll().toList() }
            val epaOprList = DatabaseFactory.readTransaction { EpaOprHistoryCache.selectAll().toList() }
            val passwordResetTokensList = DatabaseFactory.readTransaction { PasswordResetTokens.selectAll().toList() }
            val bannersList = DatabaseFactory.readTransaction { Banners.selectAll().toList() }
            val chatGroupsList = DatabaseFactory.readTransaction { ChatGroups.selectAll().toList() }
            val chatMessagesList = DatabaseFactory.readTransaction { ChatMessages.selectAll().toList() }
            val lastReadsList = DatabaseFactory.readTransaction { UserChatLastRead.selectAll().toList() }
            val pushSubsList = DatabaseFactory.readTransaction { PushSubscriptions.selectAll().toList() }
            val fcmConfigsList = DatabaseFactory.readTransaction { FcmConfigs.selectAll().toList() }
            val fcmTokensList = DatabaseFactory.readTransaction { FcmDeviceTokens.selectAll().toList() }
            val clusterSecretsList = DatabaseFactory.readTransaction { ClusterSecrets.selectAll().toList() }
            val clusterLocksList = DatabaseFactory.readTransaction { ClusterNotificationLocks.selectAll().toList() }
            val analyticsReportsList = DatabaseFactory.readTransaction { AnalyticsReports.selectAll().toList() }
            val reportedErrorsList = DatabaseFactory.readTransaction { ReportedErrors.selectAll().toList() }

            // 3. Batch insert into SQLite inside an atomic transaction
            transaction(
                transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                readOnly = false,
                db = snapshotDb
            ) {
                // Users
                for (row in usersList) {
                    Users.insert {
                        it[id] = EntityID(row[Users.id].value, Users)
                        it[username] = row[Users.username]
                        it[teamNumber] = row[Users.teamNumber]
                        it[program] = row[Users.program]
                        it[passwordHash] = row[Users.passwordHash]
                        it[role] = row[Users.role]
                        it[createdAt] = row[Users.createdAt]
                        it[email] = row[Users.email]
                        it[profilePicture] = row[Users.profilePicture]
                        it[notificationPreference] = row[Users.notificationPreference]
                        it[tourProgress] = row[Users.tourProgress]
                        it[nodeAlertsEnabled] = row[Users.nodeAlertsEnabled]
                        it[bugReportPreference] = row[Users.bugReportPreference]
                        it[lastLogin] = row[Users.lastLogin]
                    }
                }

                // UserSessions
                for (row in userSessionsList) {
                    UserSessions.insert {
                        it[id] = EntityID(row[UserSessions.id].value, UserSessions)
                        it[userId] = EntityID(row[UserSessions.userId].value, Users)
                        it[clientType] = row[UserSessions.clientType]
                        it[deviceName] = row[UserSessions.deviceName]
                        it[userAgent] = row[UserSessions.userAgent]
                        it[ipAddress] = row[UserSessions.ipAddress]
                        it[createdAt] = row[UserSessions.createdAt]
                        it[lastActiveAt] = row[UserSessions.lastActiveAt]
                        it[expiresAt] = row[UserSessions.expiresAt]
                    }
                }

                // Configs
                for (row in scoutingConfigsList) {
                    ScoutingConfigs.insert {
                        it[id] = EntityID(row[ScoutingConfigs.id].value, ScoutingConfigs)
                        it[teamNumber] = row[ScoutingConfigs.teamNumber]
                        it[program] = row[ScoutingConfigs.program]
                        it[configJson] = row[ScoutingConfigs.configJson]
                        it[updatedAt] = row[ScoutingConfigs.updatedAt]
                    }
                }
                for (row in pitConfigsList) {
                    PitScoutingConfigs.insert {
                        it[id] = EntityID(row[PitScoutingConfigs.id].value, PitScoutingConfigs)
                        it[teamNumber] = row[PitScoutingConfigs.teamNumber]
                        it[program] = row[PitScoutingConfigs.program]
                        it[configJson] = row[PitScoutingConfigs.configJson]
                        it[updatedAt] = row[PitScoutingConfigs.updatedAt]
                    }
                }
                for (row in qualConfigsList) {
                    QualitativeScoutingConfigs.insert {
                        it[id] = EntityID(row[QualitativeScoutingConfigs.id].value, QualitativeScoutingConfigs)
                        it[teamNumber] = row[QualitativeScoutingConfigs.teamNumber]
                        it[program] = row[QualitativeScoutingConfigs.program]
                        it[configJson] = row[QualitativeScoutingConfigs.configJson]
                        it[updatedAt] = row[QualitativeScoutingConfigs.updatedAt]
                    }
                }
                for (row in defaultConfigsList) {
                    DefaultConfigs.insert {
                        it[id] = EntityID(row[DefaultConfigs.id].value, DefaultConfigs)
                        it[name] = row[DefaultConfigs.name]
                        it[program] = row[DefaultConfigs.program]
                        it[configType] = row[DefaultConfigs.configType]
                        it[configJson] = row[DefaultConfigs.configJson]
                        it[isDefault] = row[DefaultConfigs.isDefault]
                        it[updatedAt] = row[DefaultConfigs.updatedAt]
                    }
                }
                for (row in configRevisionsList) {
                    ConfigRevisions.insert {
                        it[id] = EntityID(row[ConfigRevisions.id].value, ConfigRevisions)
                        it[teamNumber] = row[ConfigRevisions.teamNumber]
                        it[program] = row[ConfigRevisions.program]
                        it[configKind] = row[ConfigRevisions.configKind]
                        it[version] = row[ConfigRevisions.version]
                        it[title] = row[ConfigRevisions.title]
                        it[configJson] = row[ConfigRevisions.configJson]
                        it[changeSummary] = row[ConfigRevisions.changeSummary]
                        it[savedByUsername] = row[ConfigRevisions.savedByUsername]
                        it[createdAt] = row[ConfigRevisions.createdAt]
                    }
                }
                for (row in appSettingsList) {
                    AppSettings.insert {
                        it[id] = EntityID(row[AppSettings.id].value, AppSettings)
                        it[teamNumber] = row[AppSettings.teamNumber]
                        it[program] = row[AppSettings.program]
                        it[settingsJson] = row[AppSettings.settingsJson]
                        it[updatedAt] = row[AppSettings.updatedAt]
                    }
                }

                // API Data
                for (row in apiEventsList) {
                    ApiEvents.insert {
                        it[id] = EntityID(row[ApiEvents.id].value, ApiEvents)
                        it[eventKey] = row[ApiEvents.eventKey]
                        it[year] = row[ApiEvents.year]
                        it[eventCode] = row[ApiEvents.eventCode]
                        it[name] = row[ApiEvents.name]
                        it[startDate] = row[ApiEvents.startDate]
                        it[endDate] = row[ApiEvents.endDate]
                        it[timezone] = row[ApiEvents.timezone]
                        it[dataJson] = row[ApiEvents.dataJson]
                        it[updatedAt] = row[ApiEvents.updatedAt]
                    }
                }
                for (row in apiTeamsList) {
                    ApiTeams.insert {
                        it[id] = EntityID(row[ApiTeams.id].value, ApiTeams)
                        it[eventKey] = row[ApiTeams.eventKey]
                        it[teamKey] = row[ApiTeams.teamKey]
                        it[teamNumber] = row[ApiTeams.teamNumber]
                        it[name] = row[ApiTeams.name]
                        it[nickname] = row[ApiTeams.nickname]
                        it[city] = row[ApiTeams.city]
                        it[state] = row[ApiTeams.state]
                        it[country] = row[ApiTeams.country]
                        it[opr] = row[ApiTeams.opr]
                        it[epa] = row[ApiTeams.epa]
                        it[dataJson] = row[ApiTeams.dataJson]
                        it[updatedAt] = row[ApiTeams.updatedAt]
                    }
                }
                for (row in apiMatchesList) {
                    ApiMatches.insert {
                        it[id] = EntityID(row[ApiMatches.id].value, ApiMatches)
                        it[matchKey] = row[ApiMatches.matchKey]
                        it[eventKey] = row[ApiMatches.eventKey]
                        it[compLevel] = row[ApiMatches.compLevel]
                        it[setNumber] = row[ApiMatches.setNumber]
                        it[matchNumber] = row[ApiMatches.matchNumber]
                        it[scheduledTime] = row[ApiMatches.scheduledTime]
                        it[actualTime] = row[ApiMatches.actualTime]
                        it[redTeams] = row[ApiMatches.redTeams]
                        it[blueTeams] = row[ApiMatches.blueTeams]
                        it[dataJson] = row[ApiMatches.dataJson]
                        it[updatedAt] = row[ApiMatches.updatedAt]
                    }
                }

                // Scouting entries
                for (row in scoutingEntriesList) {
                    ScoutingEntries.insert {
                        it[id] = EntityID(row[ScoutingEntries.id].value, ScoutingEntries)
                        it[ownerTeamNumber] = row[ScoutingEntries.ownerTeamNumber]
                        it[program] = row[ScoutingEntries.program]
                        it[targetTeamNumber] = row[ScoutingEntries.targetTeamNumber]
                        it[eventKey] = row[ScoutingEntries.eventKey]
                        it[matchKey] = row[ScoutingEntries.matchKey]
                        it[matchNumber] = row[ScoutingEntries.matchNumber]
                        it[dataJson] = row[ScoutingEntries.dataJson]
                        it[submittedByUserId] = EntityID(row[ScoutingEntries.submittedByUserId].value, Users)
                        it[createdAt] = row[ScoutingEntries.createdAt]
                        it[isPrescout] = row[ScoutingEntries.isPrescout]
                        it[hasDiscrepancy] = row[ScoutingEntries.hasDiscrepancy]
                        it[conflictingTeams] = row[ScoutingEntries.conflictingTeams]
                    }
                }
                for (row in pitEntriesList) {
                    PitScoutingEntries.insert {
                        it[id] = EntityID(row[PitScoutingEntries.id].value, PitScoutingEntries)
                        it[ownerTeamNumber] = row[PitScoutingEntries.ownerTeamNumber]
                        it[program] = row[PitScoutingEntries.program]
                        it[targetTeamNumber] = row[PitScoutingEntries.targetTeamNumber]
                        it[eventKey] = row[PitScoutingEntries.eventKey]
                        it[dataJson] = row[PitScoutingEntries.dataJson]
                        it[submittedByUserId] = EntityID(row[PitScoutingEntries.submittedByUserId].value, Users)
                        it[createdAt] = row[PitScoutingEntries.createdAt]
                        it[isPrescout] = row[PitScoutingEntries.isPrescout]
                        it[hasDiscrepancy] = row[PitScoutingEntries.hasDiscrepancy]
                        it[conflictingTeams] = row[PitScoutingEntries.conflictingTeams]
                    }
                }
                for (row in qualEntriesList) {
                    QualitativeScoutingEntries.insert {
                        it[id] = EntityID(row[QualitativeScoutingEntries.id].value, QualitativeScoutingEntries)
                        it[ownerTeamNumber] = row[QualitativeScoutingEntries.ownerTeamNumber]
                        it[program] = row[QualitativeScoutingEntries.program]
                        it[targetTeamNumber] = row[QualitativeScoutingEntries.targetTeamNumber]
                        it[eventKey] = row[QualitativeScoutingEntries.eventKey]
                        it[matchKey] = row[QualitativeScoutingEntries.matchKey]
                        it[matchNumber] = row[QualitativeScoutingEntries.matchNumber]
                        it[dataJson] = row[QualitativeScoutingEntries.dataJson]
                        it[submittedByUserId] = EntityID(row[QualitativeScoutingEntries.submittedByUserId].value, Users)
                        it[createdAt] = row[QualitativeScoutingEntries.createdAt]
                        it[isPrescout] = row[QualitativeScoutingEntries.isPrescout]
                        it[hasDiscrepancy] = row[QualitativeScoutingEntries.hasDiscrepancy]
                        it[conflictingTeams] = row[QualitativeScoutingEntries.conflictingTeams]
                    }
                }

                // Alliances
                for (row in alliancesList) {
                    ScoutingAlliances.insert {
                        it[id] = EntityID(row[ScoutingAlliances.id].value, ScoutingAlliances)
                        it[name] = row[ScoutingAlliances.name]
                        it[ownerTeamNumber] = row[ScoutingAlliances.ownerTeamNumber]
                        it[program] = row[ScoutingAlliances.program]
                        it[eventKey] = row[ScoutingAlliances.eventKey]
                        it[notes] = row[ScoutingAlliances.notes]
                        it[createdAt] = row[ScoutingAlliances.createdAt]
                        it[updatedAt] = row[ScoutingAlliances.updatedAt]
                        it[matchConfigJson] = row[ScoutingAlliances.matchConfigJson]
                        it[pitConfigJson] = row[ScoutingAlliances.pitConfigJson]
                        it[qualitativeConfigJson] = row[ScoutingAlliances.qualitativeConfigJson]
                        it[year] = row[ScoutingAlliances.year]
                        it[eventCode] = row[ScoutingAlliances.eventCode]
                    }
                }
                for (row in allianceMembershipsList) {
                    AllianceMemberships.insert {
                        it[id] = EntityID(row[AllianceMemberships.id].value, AllianceMemberships)
                        it[allianceId] = EntityID(row[AllianceMemberships.allianceId].value, ScoutingAlliances)
                        it[teamNumber] = row[AllianceMemberships.teamNumber]
                        it[program] = row[AllianceMemberships.program]
                        it[status] = row[AllianceMemberships.status]
                        it[invitedAt] = row[AllianceMemberships.invitedAt]
                        it[respondedAt] = row[AllianceMemberships.respondedAt]
                        it[disabled] = row[AllianceMemberships.disabled]
                        it[active] = row[AllianceMemberships.active]
                    }
                }
                for (row in allianceSelectionsList) {
                    AllianceSelections.insert {
                        it[id] = EntityID(row[AllianceSelections.id].value, AllianceSelections)
                        it[ownerKey] = row[AllianceSelections.ownerKey]
                        it[eventKey] = row[AllianceSelections.eventKey]
                        it[selectionJson] = row[AllianceSelections.selectionJson]
                        it[updatedAt] = row[AllianceSelections.updatedAt]
                    }
                }

                // Analytics & Stats
                for (row in epaOprList) {
                    EpaOprHistoryCache.insert {
                        it[id] = EntityID(row[EpaOprHistoryCache.id].value, EpaOprHistoryCache)
                        it[eventKey] = row[EpaOprHistoryCache.eventKey]
                        it[oprsJson] = row[EpaOprHistoryCache.oprsJson]
                        it[epaHistoryJson] = row[EpaOprHistoryCache.epaHistoryJson]
                        it[updatedAt] = row[EpaOprHistoryCache.updatedAt]
                    }
                }
                for (row in passwordResetTokensList) {
                    PasswordResetTokens.insert {
                        it[id] = EntityID(row[PasswordResetTokens.id].value, PasswordResetTokens)
                        it[userId] = row[PasswordResetTokens.userId]?.value?.let { uid -> EntityID(uid, Users) }
                        it[email] = row[PasswordResetTokens.email]
                        it[token] = row[PasswordResetTokens.token]
                        it[expiresAt] = row[PasswordResetTokens.expiresAt]
                        it[used] = row[PasswordResetTokens.used]
                    }
                }

                // Banners & Chat
                for (row in bannersList) {
                    Banners.insert {
                        it[id] = EntityID(row[Banners.id].value, Banners)
                        it[teamNumber] = row[Banners.teamNumber]
                        it[program] = row[Banners.program]
                        it[message] = row[Banners.message]
                        it[bannerType] = row[Banners.bannerType]
                        it[isDismissible] = row[Banners.isDismissible]
                        it[isExpandable] = row[Banners.isExpandable]
                        it[expandableMessage] = row[Banners.expandableMessage]
                        it[showOnLogin] = row[Banners.showOnLogin]
                        it[isActive] = row[Banners.isActive]
                        it[createdAt] = row[Banners.createdAt]
                        it[updatedAt] = row[Banners.updatedAt]
                    }
                }
                for (row in chatGroupsList) {
                    ChatGroups.insert {
                        it[id] = EntityID(row[ChatGroups.id].value, ChatGroups)
                        it[teamNumber] = row[ChatGroups.teamNumber]
                        it[program] = row[ChatGroups.program]
                        it[groupName] = row[ChatGroups.groupName]
                        it[createdByUserId] = row[ChatGroups.createdByUserId]?.value?.let { uid -> EntityID(uid, Users) }
                        it[createdAt] = row[ChatGroups.createdAt]
                        it[allowedRoles] = row[ChatGroups.allowedRoles]
                        it[allowedUserIds] = row[ChatGroups.allowedUserIds]
                    }
                }
                for (row in chatMessagesList) {
                    ChatMessages.insert {
                        it[id] = EntityID(row[ChatMessages.id].value, ChatMessages)
                        it[teamNumber] = row[ChatMessages.teamNumber]
                        it[program] = row[ChatMessages.program]
                        it[groupName] = row[ChatMessages.groupName]
                        it[userId] = EntityID(row[ChatMessages.userId].value, Users)
                        it[username] = row[ChatMessages.username]
                        it[content] = row[ChatMessages.content]
                        it[createdAt] = row[ChatMessages.createdAt]
                        it[reactionsJson] = row[ChatMessages.reactionsJson]
                        it[isEdited] = row[ChatMessages.isEdited]
                        it[updatedAt] = row[ChatMessages.updatedAt]
                    }
                }
                for (row in lastReadsList) {
                    UserChatLastRead.insert {
                        it[id] = EntityID(row[UserChatLastRead.id].value, UserChatLastRead)
                        it[userId] = EntityID(row[UserChatLastRead.userId].value, Users)
                        it[groupName] = row[UserChatLastRead.groupName]
                        it[lastReadAt] = row[UserChatLastRead.lastReadAt]
                    }
                }

                // Secrets & Push Notifications
                for (row in pushSubsList) {
                    PushSubscriptions.insert {
                        it[id] = EntityID(row[PushSubscriptions.id].value, PushSubscriptions)
                        it[userId] = EntityID(row[PushSubscriptions.userId].value, Users)
                        it[endpoint] = row[PushSubscriptions.endpoint]
                        it[p256dh] = row[PushSubscriptions.p256dh]
                        it[auth] = row[PushSubscriptions.auth]
                        it[createdAt] = row[PushSubscriptions.createdAt]
                    }
                }
                for (row in fcmConfigsList) {
                    FcmConfigs.insert {
                        it[id] = EntityID(row[FcmConfigs.id].value, FcmConfigs)
                        it[projectId] = row[FcmConfigs.projectId]
                        it[apiKey] = row[FcmConfigs.apiKey]
                        it[appId] = row[FcmConfigs.appId]
                        it[messagingSenderId] = row[FcmConfigs.messagingSenderId]
                        it[serviceAccountJson] = row[FcmConfigs.serviceAccountJson]
                        it[vapidKey] = row[FcmConfigs.vapidKey]
                        it[enabled] = row[FcmConfigs.enabled]
                        it[updatedAt] = row[FcmConfigs.updatedAt]
                    }
                }
                for (row in fcmTokensList) {
                    FcmDeviceTokens.insert {
                        it[id] = EntityID(row[FcmDeviceTokens.id].value, FcmDeviceTokens)
                        it[userId] = EntityID(row[FcmDeviceTokens.userId].value, Users)
                        it[deviceToken] = row[FcmDeviceTokens.deviceToken]
                        it[platform] = row[FcmDeviceTokens.platform]
                        it[updatedAt] = row[FcmDeviceTokens.updatedAt]
                    }
                }
                for (row in clusterSecretsList) {
                    ClusterSecrets.insert {
                        it[id] = EntityID(row[ClusterSecrets.id].value, ClusterSecrets)
                        it[keyName] = row[ClusterSecrets.keyName]
                        it[keyValue] = row[ClusterSecrets.keyValue]
                        it[updatedAt] = row[ClusterSecrets.updatedAt]
                    }
                }
                for (row in clusterLocksList) {
                    ClusterNotificationLocks.insert {
                        it[id] = EntityID(row[ClusterNotificationLocks.id].value, ClusterNotificationLocks)
                        it[lockKey] = row[ClusterNotificationLocks.lockKey]
                        it[claimedByNode] = row[ClusterNotificationLocks.claimedByNode]
                        it[claimedAt] = row[ClusterNotificationLocks.claimedAt]
                        it[expiresAt] = row[ClusterNotificationLocks.expiresAt]
                    }
                }
                for (row in analyticsReportsList) {
                    AnalyticsReports.insert {
                        it[id] = EntityID(row[AnalyticsReports.id].value, AnalyticsReports)
                        it[ownerTeamNumber] = row[AnalyticsReports.ownerTeamNumber]
                        it[program] = row[AnalyticsReports.program]
                        it[userId] = EntityID(row[AnalyticsReports.userId].value, Users)
                        it[title] = row[AnalyticsReports.title]
                        it[category] = row[AnalyticsReports.category]
                        it[description] = row[AnalyticsReports.description]
                        it[configJson] = row[AnalyticsReports.configJson]
                        it[isShared] = row[AnalyticsReports.isShared]
                        it[isDefault] = row[AnalyticsReports.isDefault]
                        it[createdAt] = row[AnalyticsReports.createdAt]
                        it[updatedAt] = row[AnalyticsReports.updatedAt]
                    }
                }
                for (row in reportedErrorsList) {
                    ReportedErrors.insert {
                        it[id] = EntityID(row[ReportedErrors.id].value, ReportedErrors)
                        it[errorType] = row[ReportedErrors.errorType]
                        it[errorMessage] = row[ReportedErrors.errorMessage]
                        it[errorStack] = row[ReportedErrors.errorStack]
                        it[requestDetails] = row[ReportedErrors.requestDetails]
                        it[clientIp] = row[ReportedErrors.clientIp]
                        it[teamNumber] = row[ReportedErrors.teamNumber]
                        it[program] = row[ReportedErrors.program]
                        it[userRole] = row[ReportedErrors.userRole]
                        it[username] = row[ReportedErrors.username]
                        it[status] = row[ReportedErrors.status]
                        it[createdAt] = row[ReportedErrors.createdAt]
                        it[resolvedAt] = row[ReportedErrors.resolvedAt]
                        it[resolvedBy] = row[ReportedErrors.resolvedBy]
                    }
                }
            }

            val total = (usersList.size + userSessionsList.size + scoutingConfigsList.size + pitConfigsList.size +
                    qualConfigsList.size + defaultConfigsList.size + configRevisionsList.size + appSettingsList.size +
                    apiEventsList.size + apiTeamsList.size + apiMatchesList.size + scoutingEntriesList.size +
                    pitEntriesList.size + qualEntriesList.size + alliancesList.size + allianceMembershipsList.size +
                    allianceSelectionsList.size + epaOprList.size + passwordResetTokensList.size + bannersList.size +
                    chatGroupsList.size + chatMessagesList.size + lastReadsList.size + pushSubsList.size +
                    fcmConfigsList.size + fcmTokensList.size + clusterSecretsList.size + clusterLocksList.size +
                    analyticsReportsList.size + reportedErrorsList.size).toLong()

            return total
        } finally {
            // Restore default database pointer in Exposed
            if (previousDefaultDb != null) {
                TransactionManager.defaultDatabase = previousDefaultDb
            }
        }
    }

    private fun countTotalRecords(): Long {
        return try {
            DatabaseFactory.readTransaction {
                allTables.sumOf { table -> table.selectAll().count() }
            }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Restores the primary database completely from an SQLite snapshot file.
     * Respects foreign keys during deletion and insertion.
     */
    fun restoreFromSnapshot(snapshotFile: File): DatabaseRestoreReport {
        if (!DatabaseFactory.isReady) {
            return DatabaseRestoreReport(false, "Primary database is not ready", snapshotFile.name)
        }
        if (!snapshotFile.exists() || snapshotFile.length() == 0L) {
            return DatabaseRestoreReport(false, "Snapshot file does not exist or is empty", snapshotFile.name)
        }
        if (!isRestoreInProgress.compareAndSet(false, true)) {
            return DatabaseRestoreReport(false, "Another restoration is currently in progress", snapshotFile.name)
        }

        val startTime = System.currentTimeMillis()
        val previousDefaultDb = DatabaseFactory.primaryDatabase
        ensureSqliteDriver()

        try {
            println("[SnapshotService] ⚠️ Starting full database restoration from ${snapshotFile.absolutePath}...")

            val snapshotUrl = "jdbc:sqlite:${snapshotFile.absolutePath}?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL"
            val snapshotDb = Database.connect(
                snapshotUrl,
                driver = "org.sqlite.JDBC",
                databaseConfig = org.jetbrains.exposed.sql.DatabaseConfig {
                    defaultMaxAttempts = 1
                    defaultIsolationLevel = java.sql.Connection.TRANSACTION_SERIALIZABLE
                }
            )

            // Read all snapshot data into memory first
            val users: List<ResultRow> = transaction(snapshotDb) { Users.selectAll().toList() }
            val sessions: List<ResultRow> = transaction(snapshotDb) { UserSessions.selectAll().toList() }
            val sConfigs: List<ResultRow> = transaction(snapshotDb) { ScoutingConfigs.selectAll().toList() }
            val pConfigs: List<ResultRow> = transaction(snapshotDb) { PitScoutingConfigs.selectAll().toList() }
            val qConfigs: List<ResultRow> = transaction(snapshotDb) { QualitativeScoutingConfigs.selectAll().toList() }
            val dConfigs: List<ResultRow> = transaction(snapshotDb) { DefaultConfigs.selectAll().toList() }
            val cRevs: List<ResultRow> = transaction(snapshotDb) { ConfigRevisions.selectAll().toList() }
            val settings: List<ResultRow> = transaction(snapshotDb) { AppSettings.selectAll().toList() }

            val events: List<ResultRow> = transaction(snapshotDb) { ApiEvents.selectAll().toList() }
            val teams: List<ResultRow> = transaction(snapshotDb) { ApiTeams.selectAll().toList() }
            val matches: List<ResultRow> = transaction(snapshotDb) { ApiMatches.selectAll().toList() }
            val sEntries: List<ResultRow> = transaction(snapshotDb) { ScoutingEntries.selectAll().toList() }
            val pEntries: List<ResultRow> = transaction(snapshotDb) { PitScoutingEntries.selectAll().toList() }
            val qEntries: List<ResultRow> = transaction(snapshotDb) { QualitativeScoutingEntries.selectAll().toList() }

            val alliances: List<ResultRow> = transaction(snapshotDb) { ScoutingAlliances.selectAll().toList() }
            val memberships: List<ResultRow> = transaction(snapshotDb) { AllianceMemberships.selectAll().toList() }
            val selections: List<ResultRow> = transaction(snapshotDb) { AllianceSelections.selectAll().toList() }
            val epaOpr: List<ResultRow> = transaction(snapshotDb) { EpaOprHistoryCache.selectAll().toList() }
            val pwdTokens: List<ResultRow> = transaction(snapshotDb) { PasswordResetTokens.selectAll().toList() }

            val banners: List<ResultRow> = transaction(snapshotDb) { Banners.selectAll().toList() }
            val chatGrps: List<ResultRow> = transaction(snapshotDb) { ChatGroups.selectAll().toList() }
            val chatMsgs: List<ResultRow> = transaction(snapshotDb) { ChatMessages.selectAll().toList() }
            val lastReads: List<ResultRow> = transaction(snapshotDb) { UserChatLastRead.selectAll().toList() }
            val pushSubs: List<ResultRow> = transaction(snapshotDb) { PushSubscriptions.selectAll().toList() }

            val fcmCfgs: List<ResultRow> = transaction(snapshotDb) { FcmConfigs.selectAll().toList() }
            val fcmDevs: List<ResultRow> = transaction(snapshotDb) { FcmDeviceTokens.selectAll().toList() }
            val secrets: List<ResultRow> = transaction(snapshotDb) { ClusterSecrets.selectAll().toList() }
            val locks: List<ResultRow> = transaction(snapshotDb) { ClusterNotificationLocks.selectAll().toList() }
            val reports: List<ResultRow> = transaction(snapshotDb) { AnalyticsReports.selectAll().toList() }
            val errors: List<ResultRow> = transaction(snapshotDb) { ReportedErrors.selectAll().toList() }

            // Restore primary DB pointer
            if (previousDefaultDb != null) {
                TransactionManager.defaultDatabase = previousDefaultDb
            }

            val restoredCounts = mutableMapOf<String, Long>()

            // Execute primary database wipe and repopulation inside atomic transaction
            transaction(db = previousDefaultDb) {
                // 1. Delete in reverse dependency order (children first)
                UserSessions.deleteAll()
                ChatMessages.deleteAll()
                UserChatLastRead.deleteAll()
                PushSubscriptions.deleteAll()
                FcmDeviceTokens.deleteAll()
                AnalyticsReports.deleteAll()
                AllianceMemberships.deleteAll()
                ScoutingEntries.deleteAll()
                PitScoutingEntries.deleteAll()
                QualitativeScoutingEntries.deleteAll()
                PasswordResetTokens.deleteAll()

                // Independent tables
                ScoutingConfigs.deleteAll()
                PitScoutingConfigs.deleteAll()
                QualitativeScoutingConfigs.deleteAll()
                ConfigRevisions.deleteAll()
                DefaultConfigs.deleteAll()
                AppSettings.deleteAll()
                ApiTeams.deleteAll()
                ApiMatches.deleteAll()
                AllianceSelections.deleteAll()
                EpaOprHistoryCache.deleteAll()
                Banners.deleteAll()
                ChatGroups.deleteAll()
                ClusterSecrets.deleteAll()
                ClusterNotificationLocks.deleteAll()
                ReportedErrors.deleteAll()
                FcmConfigs.deleteAll()

                // Parent tables last
                ScoutingAlliances.deleteAll()
                ApiEvents.deleteAll()
                Users.deleteAll()

                // 2. Insert in dependency order (parents first)
                for (row in users) {
                    Users.insert {
                        it[id] = EntityID(row[Users.id].value, Users)
                        it[username] = row[Users.username]
                        it[teamNumber] = row[Users.teamNumber]
                        it[program] = row[Users.program]
                        it[passwordHash] = row[Users.passwordHash]
                        it[role] = row[Users.role]
                        it[createdAt] = row[Users.createdAt]
                        it[email] = row[Users.email]
                        it[profilePicture] = row[Users.profilePicture]
                        it[notificationPreference] = row[Users.notificationPreference]
                        it[tourProgress] = row[Users.tourProgress]
                        it[nodeAlertsEnabled] = row[Users.nodeAlertsEnabled]
                        it[bugReportPreference] = row[Users.bugReportPreference]
                        it[lastLogin] = row[Users.lastLogin]
                    }
                }
                restoredCounts["users"] = users.size.toLong()

                for (row in alliances) {
                    ScoutingAlliances.insert {
                        it[id] = EntityID(row[ScoutingAlliances.id].value, ScoutingAlliances)
                        it[name] = row[ScoutingAlliances.name]
                        it[ownerTeamNumber] = row[ScoutingAlliances.ownerTeamNumber]
                        it[program] = row[ScoutingAlliances.program]
                        it[eventKey] = row[ScoutingAlliances.eventKey]
                        it[notes] = row[ScoutingAlliances.notes]
                        it[createdAt] = row[ScoutingAlliances.createdAt]
                        it[updatedAt] = row[ScoutingAlliances.updatedAt]
                        it[matchConfigJson] = row[ScoutingAlliances.matchConfigJson]
                        it[pitConfigJson] = row[ScoutingAlliances.pitConfigJson]
                        it[qualitativeConfigJson] = row[ScoutingAlliances.qualitativeConfigJson]
                        it[year] = row[ScoutingAlliances.year]
                        it[eventCode] = row[ScoutingAlliances.eventCode]
                    }
                }
                restoredCounts["scouting_alliances"] = alliances.size.toLong()

                for (row in events) {
                    ApiEvents.insert {
                        it[id] = EntityID(row[ApiEvents.id].value, ApiEvents)
                        it[eventKey] = row[ApiEvents.eventKey]
                        it[year] = row[ApiEvents.year]
                        it[eventCode] = row[ApiEvents.eventCode]
                        it[name] = row[ApiEvents.name]
                        it[startDate] = row[ApiEvents.startDate]
                        it[endDate] = row[ApiEvents.endDate]
                        it[timezone] = row[ApiEvents.timezone]
                        it[dataJson] = row[ApiEvents.dataJson]
                        it[updatedAt] = row[ApiEvents.updatedAt]
                    }
                }
                restoredCounts["api_events"] = events.size.toLong()

                // Independent tables
                for (row in sConfigs) {
                    ScoutingConfigs.insert {
                        it[id] = EntityID(row[ScoutingConfigs.id].value, ScoutingConfigs)
                        it[teamNumber] = row[ScoutingConfigs.teamNumber]
                        it[program] = row[ScoutingConfigs.program]
                        it[configJson] = row[ScoutingConfigs.configJson]
                        it[updatedAt] = row[ScoutingConfigs.updatedAt]
                    }
                }
                restoredCounts["scouting_configs"] = sConfigs.size.toLong()

                for (row in pConfigs) {
                    PitScoutingConfigs.insert {
                        it[id] = EntityID(row[PitScoutingConfigs.id].value, PitScoutingConfigs)
                        it[teamNumber] = row[PitScoutingConfigs.teamNumber]
                        it[program] = row[PitScoutingConfigs.program]
                        it[configJson] = row[PitScoutingConfigs.configJson]
                        it[updatedAt] = row[PitScoutingConfigs.updatedAt]
                    }
                }
                restoredCounts["pit_scouting_configs"] = pConfigs.size.toLong()

                for (row in qConfigs) {
                    QualitativeScoutingConfigs.insert {
                        it[id] = EntityID(row[QualitativeScoutingConfigs.id].value, QualitativeScoutingConfigs)
                        it[teamNumber] = row[QualitativeScoutingConfigs.teamNumber]
                        it[program] = row[QualitativeScoutingConfigs.program]
                        it[configJson] = row[QualitativeScoutingConfigs.configJson]
                        it[updatedAt] = row[QualitativeScoutingConfigs.updatedAt]
                    }
                }
                restoredCounts["qualitative_scouting_configs"] = qConfigs.size.toLong()

                for (row in dConfigs) {
                    DefaultConfigs.insert {
                        it[id] = EntityID(row[DefaultConfigs.id].value, DefaultConfigs)
                        it[name] = row[DefaultConfigs.name]
                        it[program] = row[DefaultConfigs.program]
                        it[configType] = row[DefaultConfigs.configType]
                        it[configJson] = row[DefaultConfigs.configJson]
                        it[isDefault] = row[DefaultConfigs.isDefault]
                        it[updatedAt] = row[DefaultConfigs.updatedAt]
                    }
                }
                restoredCounts["default_configs"] = dConfigs.size.toLong()

                for (row in cRevs) {
                    ConfigRevisions.insert {
                        it[id] = EntityID(row[ConfigRevisions.id].value, ConfigRevisions)
                        it[teamNumber] = row[ConfigRevisions.teamNumber]
                        it[program] = row[ConfigRevisions.program]
                        it[configKind] = row[ConfigRevisions.configKind]
                        it[version] = row[ConfigRevisions.version]
                        it[title] = row[ConfigRevisions.title]
                        it[configJson] = row[ConfigRevisions.configJson]
                        it[changeSummary] = row[ConfigRevisions.changeSummary]
                        it[savedByUsername] = row[ConfigRevisions.savedByUsername]
                        it[createdAt] = row[ConfigRevisions.createdAt]
                    }
                }
                restoredCounts["config_revisions"] = cRevs.size.toLong()

                for (row in settings) {
                    AppSettings.insert {
                        it[id] = EntityID(row[AppSettings.id].value, AppSettings)
                        it[teamNumber] = row[AppSettings.teamNumber]
                        it[program] = row[AppSettings.program]
                        it[settingsJson] = row[AppSettings.settingsJson]
                        it[updatedAt] = row[AppSettings.updatedAt]
                    }
                }
                restoredCounts["app_settings"] = settings.size.toLong()

                for (row in teams) {
                    ApiTeams.insert {
                        it[id] = EntityID(row[ApiTeams.id].value, ApiTeams)
                        it[eventKey] = row[ApiTeams.eventKey]
                        it[teamKey] = row[ApiTeams.teamKey]
                        it[teamNumber] = row[ApiTeams.teamNumber]
                        it[name] = row[ApiTeams.name]
                        it[nickname] = row[ApiTeams.nickname]
                        it[city] = row[ApiTeams.city]
                        it[state] = row[ApiTeams.state]
                        it[country] = row[ApiTeams.country]
                        it[opr] = row[ApiTeams.opr]
                        it[epa] = row[ApiTeams.epa]
                        it[dataJson] = row[ApiTeams.dataJson]
                        it[updatedAt] = row[ApiTeams.updatedAt]
                    }
                }
                restoredCounts["api_teams"] = teams.size.toLong()

                for (row in matches) {
                    ApiMatches.insert {
                        it[id] = EntityID(row[ApiMatches.id].value, ApiMatches)
                        it[matchKey] = row[ApiMatches.matchKey]
                        it[eventKey] = row[ApiMatches.eventKey]
                        it[compLevel] = row[ApiMatches.compLevel]
                        it[setNumber] = row[ApiMatches.setNumber]
                        it[matchNumber] = row[ApiMatches.matchNumber]
                        it[scheduledTime] = row[ApiMatches.scheduledTime]
                        it[actualTime] = row[ApiMatches.actualTime]
                        it[redTeams] = row[ApiMatches.redTeams]
                        it[blueTeams] = row[ApiMatches.blueTeams]
                        it[dataJson] = row[ApiMatches.dataJson]
                        it[updatedAt] = row[ApiMatches.updatedAt]
                    }
                }
                restoredCounts["api_matches"] = matches.size.toLong()

                for (row in selections) {
                    AllianceSelections.insert {
                        it[id] = EntityID(row[AllianceSelections.id].value, AllianceSelections)
                        it[ownerKey] = row[AllianceSelections.ownerKey]
                        it[eventKey] = row[AllianceSelections.eventKey]
                        it[selectionJson] = row[AllianceSelections.selectionJson]
                        it[updatedAt] = row[AllianceSelections.updatedAt]
                    }
                }
                restoredCounts["alliance_selections"] = selections.size.toLong()

                for (row in epaOpr) {
                    EpaOprHistoryCache.insert {
                        it[id] = EntityID(row[EpaOprHistoryCache.id].value, EpaOprHistoryCache)
                        it[eventKey] = row[EpaOprHistoryCache.eventKey]
                        it[oprsJson] = row[EpaOprHistoryCache.oprsJson]
                        it[epaHistoryJson] = row[EpaOprHistoryCache.epaHistoryJson]
                        it[updatedAt] = row[EpaOprHistoryCache.updatedAt]
                    }
                }
                restoredCounts["epa_opr_history_cache"] = epaOpr.size.toLong()

                for (row in banners) {
                    Banners.insert {
                        it[id] = EntityID(row[Banners.id].value, Banners)
                        it[teamNumber] = row[Banners.teamNumber]
                        it[program] = row[Banners.program]
                        it[message] = row[Banners.message]
                        it[bannerType] = row[Banners.bannerType]
                        it[isDismissible] = row[Banners.isDismissible]
                        it[isExpandable] = row[Banners.isExpandable]
                        it[expandableMessage] = row[Banners.expandableMessage]
                        it[showOnLogin] = row[Banners.showOnLogin]
                        it[isActive] = row[Banners.isActive]
                        it[createdAt] = row[Banners.createdAt]
                        it[updatedAt] = row[Banners.updatedAt]
                    }
                }
                restoredCounts["banners"] = banners.size.toLong()

                for (row in chatGrps) {
                    ChatGroups.insert {
                        it[id] = EntityID(row[ChatGroups.id].value, ChatGroups)
                        it[teamNumber] = row[ChatGroups.teamNumber]
                        it[program] = row[ChatGroups.program]
                        it[groupName] = row[ChatGroups.groupName]
                        it[createdByUserId] = row[ChatGroups.createdByUserId]?.value?.let { uid -> EntityID(uid, Users) }
                        it[createdAt] = row[ChatGroups.createdAt]
                        it[allowedRoles] = row[ChatGroups.allowedRoles]
                        it[allowedUserIds] = row[ChatGroups.allowedUserIds]
                    }
                }
                restoredCounts["chat_groups"] = chatGrps.size.toLong()

                for (row in secrets) {
                    ClusterSecrets.insert {
                        it[id] = EntityID(row[ClusterSecrets.id].value, ClusterSecrets)
                        it[keyName] = row[ClusterSecrets.keyName]
                        it[keyValue] = row[ClusterSecrets.keyValue]
                        it[updatedAt] = row[ClusterSecrets.updatedAt]
                    }
                }
                restoredCounts["cluster_secrets"] = secrets.size.toLong()

                for (row in locks) {
                    ClusterNotificationLocks.insert {
                        it[id] = EntityID(row[ClusterNotificationLocks.id].value, ClusterNotificationLocks)
                        it[lockKey] = row[ClusterNotificationLocks.lockKey]
                        it[claimedByNode] = row[ClusterNotificationLocks.claimedByNode]
                        it[claimedAt] = row[ClusterNotificationLocks.claimedAt]
                        it[expiresAt] = row[ClusterNotificationLocks.expiresAt]
                    }
                }
                restoredCounts["cluster_notification_locks"] = locks.size.toLong()

                for (row in fcmCfgs) {
                    FcmConfigs.insert {
                        it[id] = EntityID(row[FcmConfigs.id].value, FcmConfigs)
                        it[projectId] = row[FcmConfigs.projectId]
                        it[apiKey] = row[FcmConfigs.apiKey]
                        it[appId] = row[FcmConfigs.appId]
                        it[messagingSenderId] = row[FcmConfigs.messagingSenderId]
                        it[serviceAccountJson] = row[FcmConfigs.serviceAccountJson]
                        it[vapidKey] = row[FcmConfigs.vapidKey]
                        it[enabled] = row[FcmConfigs.enabled]
                        it[updatedAt] = row[FcmConfigs.updatedAt]
                    }
                }
                restoredCounts["fcm_config"] = fcmCfgs.size.toLong()

                for (row in errors) {
                    ReportedErrors.insert {
                        it[id] = EntityID(row[ReportedErrors.id].value, ReportedErrors)
                        it[errorType] = row[ReportedErrors.errorType]
                        it[errorMessage] = row[ReportedErrors.errorMessage]
                        it[errorStack] = row[ReportedErrors.errorStack]
                        it[requestDetails] = row[ReportedErrors.requestDetails]
                        it[clientIp] = row[ReportedErrors.clientIp]
                        it[teamNumber] = row[ReportedErrors.teamNumber]
                        it[program] = row[ReportedErrors.program]
                        it[userRole] = row[ReportedErrors.userRole]
                        it[username] = row[ReportedErrors.username]
                        it[status] = row[ReportedErrors.status]
                        it[createdAt] = row[ReportedErrors.createdAt]
                        it[resolvedAt] = row[ReportedErrors.resolvedAt]
                        it[resolvedBy] = row[ReportedErrors.resolvedBy]
                    }
                }
                restoredCounts["reported_errors"] = errors.size.toLong()

                // 3. Child tables
                for (row in sessions) {
                    UserSessions.insert {
                        it[id] = EntityID(row[UserSessions.id].value, UserSessions)
                        it[userId] = EntityID(row[UserSessions.userId].value, Users)
                        it[clientType] = row[UserSessions.clientType]
                        it[deviceName] = row[UserSessions.deviceName]
                        it[userAgent] = row[UserSessions.userAgent]
                        it[ipAddress] = row[UserSessions.ipAddress]
                        it[createdAt] = row[UserSessions.createdAt]
                        it[lastActiveAt] = row[UserSessions.lastActiveAt]
                        it[expiresAt] = row[UserSessions.expiresAt]
                    }
                }
                restoredCounts["user_sessions"] = sessions.size.toLong()

                for (row in sEntries) {
                    ScoutingEntries.insert {
                        it[id] = EntityID(row[ScoutingEntries.id].value, ScoutingEntries)
                        it[ownerTeamNumber] = row[ScoutingEntries.ownerTeamNumber]
                        it[program] = row[ScoutingEntries.program]
                        it[targetTeamNumber] = row[ScoutingEntries.targetTeamNumber]
                        it[eventKey] = row[ScoutingEntries.eventKey]
                        it[matchKey] = row[ScoutingEntries.matchKey]
                        it[matchNumber] = row[ScoutingEntries.matchNumber]
                        it[dataJson] = row[ScoutingEntries.dataJson]
                        it[submittedByUserId] = EntityID(row[ScoutingEntries.submittedByUserId].value, Users)
                        it[createdAt] = row[ScoutingEntries.createdAt]
                        it[isPrescout] = row[ScoutingEntries.isPrescout]
                        it[hasDiscrepancy] = row[ScoutingEntries.hasDiscrepancy]
                        it[conflictingTeams] = row[ScoutingEntries.conflictingTeams]
                    }
                }
                restoredCounts["scouting_entries"] = sEntries.size.toLong()

                for (row in pEntries) {
                    PitScoutingEntries.insert {
                        it[id] = EntityID(row[PitScoutingEntries.id].value, PitScoutingEntries)
                        it[ownerTeamNumber] = row[PitScoutingEntries.ownerTeamNumber]
                        it[program] = row[PitScoutingEntries.program]
                        it[targetTeamNumber] = row[PitScoutingEntries.targetTeamNumber]
                        it[eventKey] = row[PitScoutingEntries.eventKey]
                        it[dataJson] = row[PitScoutingEntries.dataJson]
                        it[submittedByUserId] = EntityID(row[PitScoutingEntries.submittedByUserId].value, Users)
                        it[createdAt] = row[PitScoutingEntries.createdAt]
                        it[isPrescout] = row[PitScoutingEntries.isPrescout]
                        it[hasDiscrepancy] = row[PitScoutingEntries.hasDiscrepancy]
                        it[conflictingTeams] = row[PitScoutingEntries.conflictingTeams]
                    }
                }
                restoredCounts["pit_scouting_entries"] = pEntries.size.toLong()

                for (row in qEntries) {
                    QualitativeScoutingEntries.insert {
                        it[id] = EntityID(row[QualitativeScoutingEntries.id].value, QualitativeScoutingEntries)
                        it[ownerTeamNumber] = row[QualitativeScoutingEntries.ownerTeamNumber]
                        it[program] = row[QualitativeScoutingEntries.program]
                        it[targetTeamNumber] = row[QualitativeScoutingEntries.targetTeamNumber]
                        it[eventKey] = row[QualitativeScoutingEntries.eventKey]
                        it[matchKey] = row[QualitativeScoutingEntries.matchKey]
                        it[matchNumber] = row[QualitativeScoutingEntries.matchNumber]
                        it[dataJson] = row[QualitativeScoutingEntries.dataJson]
                        it[submittedByUserId] = EntityID(row[QualitativeScoutingEntries.submittedByUserId].value, Users)
                        it[createdAt] = row[QualitativeScoutingEntries.createdAt]
                        it[isPrescout] = row[QualitativeScoutingEntries.isPrescout]
                        it[hasDiscrepancy] = row[QualitativeScoutingEntries.hasDiscrepancy]
                        it[conflictingTeams] = row[QualitativeScoutingEntries.conflictingTeams]
                    }
                }
                restoredCounts["qualitative_scouting_entries"] = qEntries.size.toLong()

                for (row in memberships) {
                    AllianceMemberships.insert {
                        it[id] = EntityID(row[AllianceMemberships.id].value, AllianceMemberships)
                        it[allianceId] = EntityID(row[AllianceMemberships.allianceId].value, ScoutingAlliances)
                        it[teamNumber] = row[AllianceMemberships.teamNumber]
                        it[program] = row[AllianceMemberships.program]
                        it[status] = row[AllianceMemberships.status]
                        it[invitedAt] = row[AllianceMemberships.invitedAt]
                        it[respondedAt] = row[AllianceMemberships.respondedAt]
                        it[disabled] = row[AllianceMemberships.disabled]
                        it[active] = row[AllianceMemberships.active]
                    }
                }
                restoredCounts["alliance_memberships"] = memberships.size.toLong()

                for (row in chatMsgs) {
                    ChatMessages.insert {
                        it[id] = EntityID(row[ChatMessages.id].value, ChatMessages)
                        it[teamNumber] = row[ChatMessages.teamNumber]
                        it[program] = row[ChatMessages.program]
                        it[groupName] = row[ChatMessages.groupName]
                        it[userId] = EntityID(row[ChatMessages.userId].value, Users)
                        it[username] = row[ChatMessages.username]
                        it[content] = row[ChatMessages.content]
                        it[createdAt] = row[ChatMessages.createdAt]
                        it[reactionsJson] = row[ChatMessages.reactionsJson]
                        it[isEdited] = row[ChatMessages.isEdited]
                        it[updatedAt] = row[ChatMessages.updatedAt]
                    }
                }
                restoredCounts["chat_messages"] = chatMsgs.size.toLong()

                for (row in lastReads) {
                    UserChatLastRead.insert {
                        it[id] = EntityID(row[UserChatLastRead.id].value, UserChatLastRead)
                        it[userId] = EntityID(row[UserChatLastRead.userId].value, Users)
                        it[groupName] = row[UserChatLastRead.groupName]
                        it[lastReadAt] = row[UserChatLastRead.lastReadAt]
                    }
                }
                restoredCounts["user_chat_last_read"] = lastReads.size.toLong()

                for (row in pushSubs) {
                    PushSubscriptions.insert {
                        it[id] = EntityID(row[PushSubscriptions.id].value, PushSubscriptions)
                        it[userId] = EntityID(row[PushSubscriptions.userId].value, Users)
                        it[endpoint] = row[PushSubscriptions.endpoint]
                        it[p256dh] = row[PushSubscriptions.p256dh]
                        it[auth] = row[PushSubscriptions.auth]
                        it[createdAt] = row[PushSubscriptions.createdAt]
                    }
                }
                restoredCounts["push_subscriptions"] = pushSubs.size.toLong()

                for (row in fcmDevs) {
                    FcmDeviceTokens.insert {
                        it[id] = EntityID(row[FcmDeviceTokens.id].value, FcmDeviceTokens)
                        it[userId] = EntityID(row[FcmDeviceTokens.userId].value, Users)
                        it[deviceToken] = row[FcmDeviceTokens.deviceToken]
                        it[platform] = row[FcmDeviceTokens.platform]
                        it[updatedAt] = row[FcmDeviceTokens.updatedAt]
                    }
                }
                restoredCounts["fcm_device_tokens"] = fcmDevs.size.toLong()

                for (row in pwdTokens) {
                    PasswordResetTokens.insert {
                        it[id] = EntityID(row[PasswordResetTokens.id].value, PasswordResetTokens)
                        it[userId] = row[PasswordResetTokens.userId]?.value?.let { uid -> EntityID(uid, Users) }
                        it[email] = row[PasswordResetTokens.email]
                        it[token] = row[PasswordResetTokens.token]
                        it[expiresAt] = row[PasswordResetTokens.expiresAt]
                        it[used] = row[PasswordResetTokens.used]
                    }
                }
                restoredCounts["password_reset_tokens"] = pwdTokens.size.toLong()

                for (row in reports) {
                    AnalyticsReports.insert {
                        it[id] = EntityID(row[AnalyticsReports.id].value, AnalyticsReports)
                        it[ownerTeamNumber] = row[AnalyticsReports.ownerTeamNumber]
                        it[program] = row[AnalyticsReports.program]
                        it[userId] = EntityID(row[AnalyticsReports.userId].value, Users)
                        it[title] = row[AnalyticsReports.title]
                        it[category] = row[AnalyticsReports.category]
                        it[description] = row[AnalyticsReports.description]
                        it[configJson] = row[AnalyticsReports.configJson]
                        it[isShared] = row[AnalyticsReports.isShared]
                        it[isDefault] = row[AnalyticsReports.isDefault]
                        it[createdAt] = row[AnalyticsReports.createdAt]
                        it[updatedAt] = row[AnalyticsReports.updatedAt]
                    }
                }
                restoredCounts["analytics_reports"] = reports.size.toLong()
            }

            // Ensure superadmin exists from seed config in case the restored snapshot didn't have one
            try {
                AuthService.ensureSeedSuperAdmin(AppConfigLoader.load().seed)
            } catch (_: Exception) {}

            val durationMs = System.currentTimeMillis() - startTime
            val totalRecords = restoredCounts.values.sum()
            println("[SnapshotService] Database restoration completed successfully: $totalRecords records restored in ${durationMs}ms.")

            return DatabaseRestoreReport(
                success = true,
                message = "Database restored successfully from snapshot.",
                sourceFile = snapshotFile.name,
                totalRecordsRestored = totalRecords,
                restoredTables = restoredCounts,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            println("[SnapshotService] Database restoration failed: ${e.message}")
            e.printStackTrace()
            return DatabaseRestoreReport(
                success = false,
                message = "Restoration failed: ${e.message}",
                sourceFile = snapshotFile.name
            )
        } finally {
            if (previousDefaultDb != null) {
                TransactionManager.defaultDatabase = previousDefaultDb
            }
            isRestoreInProgress.set(false)
        }
    }

    /**
     * Lists existing snapshots in the storage directory, newest first.
     */
    fun listSnapshots(): List<SnapshotInfoDto> {
        val dir = getStorageDir()
        val files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".db") || f.name.endsWith(".sqlite")) &&
                    !f.name.endsWith("-wal") && !f.name.endsWith("-shm")
        } ?: return emptyList()

        return files.map { file ->
            val isAuto = file.name.startsWith("auto_snapshot")
            val epochMs = try {
                val timestampPart = file.nameWithoutExtension.substringAfterLast("_")
                val datePart = file.nameWithoutExtension.substringBeforeLast("_").substringAfter("_")
                val combined = "${datePart}_${timestampPart}"
                val parsed = java.time.LocalDateTime.parse(combined, DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                parsed.toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: Exception) {
                file.lastModified()
            }

            SnapshotInfoDto(
                fileName = file.name,
                sizeBytes = file.length(),
                createdAtEpochMs = epochMs,
                isAutoBackup = isAuto
            )
        }.sortedByDescending { it.createdAtEpochMs }
    }

    /**
     * Returns a specific snapshot file ensuring it stays within the storage directory.
     */
    fun getSnapshotFile(fileName: String): File? {
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return null
        }
        val dir = getStorageDir()
        val file = File(dir, fileName)
        if (!file.exists() || !file.isFile) {
            return null
        }
        if (!file.canonicalPath.startsWith(dir.canonicalPath)) {
            return null
        }
        return file
    }

    /**
     * Deletes a specific snapshot file and associated temporary files.
     */
    fun deleteSnapshot(fileName: String): Boolean {
        val file = getSnapshotFile(fileName) ?: return false
        val deleted = file.delete()
        val walFile = File(file.parentFile, "${file.name}-wal")
        val shmFile = File(file.parentFile, "${file.name}-shm")
        if (walFile.exists()) walFile.delete()
        if (shmFile.exists()) shmFile.delete()
        return deleted
    }

    /**
     * Deletes snapshots older than the configured retention period.
     * If retentionDays is <= 0, no snapshots are deleted (indefinite retention).
     */
    fun pruneOldSnapshots(retentionDays: Int? = null): Int {
        val days = retentionDays ?: AppConfigLoader.load().auto_backup.retention_days
        if (days <= 0) return 0

        val cutoff = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()
        val dir = getStorageDir()
        val files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".db") || f.name.endsWith(".sqlite")) &&
                    !f.name.endsWith("-wal") && !f.name.endsWith("-shm")
        } ?: return 0

        var deletedCount = 0
        for (file in files) {
            val epochMs = try {
                val timestampPart = file.nameWithoutExtension.substringAfterLast("_")
                val datePart = file.nameWithoutExtension.substringBeforeLast("_").substringAfter("_")
                val combined = "${datePart}_${timestampPart}"
                val parsed = java.time.LocalDateTime.parse(combined, DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                parsed.toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: Exception) {
                file.lastModified()
            }

            if (epochMs < cutoff) {
                println("[SnapshotService] Pruning expired snapshot: ${file.name} (age > $days days)")
                if (file.delete()) {
                    deletedCount++
                    File(file.parentFile, "${file.name}-wal").takeIf { it.exists() }?.delete()
                    File(file.parentFile, "${file.name}-shm").takeIf { it.exists() }?.delete()
                }
            }
        }
        return deletedCount
    }

    /**
     * Compiles status DTO for this server node.
     */
    fun getLocalNodeStatus(nodeIp: String = "127.0.0.1"): AutoBackupNodeStatusDto {
        val config = AppConfigLoader.load().auto_backup
        val snapshots = listSnapshots()
        val totalBytes = snapshots.sumOf { it.sizeBytes }

        return AutoBackupNodeStatusDto(
            nodeIp = nodeIp,
            isLocal = true,
            enabled = config.enabled,
            isAvailable = DatabaseFactory.isReady,
            targetTimeUtc = config.target_time_utc,
            retentionDays = config.retention_days,
            storageDirectory = config.storage_directory,
            lastBackupTimestamp = AutoBackupScheduler.lastBackupInstant?.toString(),
            lastBackupStatus = AutoBackupScheduler.lastBackupStatus,
            nextScheduledRunUtc = AutoBackupScheduler.computeNextRunUtc(config.target_time_utc),
            snapshotCount = snapshots.size,
            totalStorageBytes = totalBytes,
            snapshots = snapshots
        )
    }
}
