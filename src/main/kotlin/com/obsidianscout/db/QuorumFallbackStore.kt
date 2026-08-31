package com.obsidianscout.db

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.QuorumFallbackConfig
import com.obsidianscout.db.orchestration.CockroachOrchestrator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class QuorumFallbackStatusDto(
    val nodeIp: String,
    val isLocal: Boolean,
    val enabled: Boolean,
    val isAvailable: Boolean,
    val isActiveServingReads: Boolean,
    val status: String,
    val databaseSizeBytes: Long,
    val freeDiskSpaceBytes: Long,
    val totalDiskSpaceBytes: Long,
    val lastSyncTimestamp: String?,
    val recordCounts: Map<String, Long> = emptyMap()
)

object QuorumFallbackStore {

    @Volatile
    var isEnabled: Boolean = false
        private set

    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var sqliteDb: Database? = null
        private set

    private var sqliteDataSource: HikariDataSource? = null
    private var config: QuorumFallbackConfig = QuorumFallbackConfig()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private val isSyncing = AtomicBoolean(false)

    @Volatile
    var lastSyncInstant: Instant? = null

    @Volatile
    var lastSyncStatus: String = "Not initialized"

    private val mirroredTables: Array<Table> = arrayOf(
        Users,
        UserSessions,
        ScoutingConfigs,
        PitScoutingConfigs,
        QualitativeScoutingConfigs,
        DefaultConfigs,
        ConfigRevisions,
        AppSettings,
        ApiEvents,
        ApiTeams,
        ApiMatches,
        ScoutingEntries,
        PitScoutingEntries,
        QualitativeScoutingEntries,
        ScoutingAlliances,
        AllianceMemberships,
        AllianceSelections,
        Banners,
        ChatGroups,
        ChatMessages,
        UserChatLastRead,
        ClusterSecrets,
        FcmConfigs,
        FcmDeviceTokens,
        PushSubscriptions
    )

    @Synchronized
    fun init(appConfig: AppConfig) {
        this.config = appConfig.quorum_fallback
        this.isEnabled = config.enabled

        if (!isEnabled) {
            disableAndPurge(updateConfigFile = false)
            return
        }

        try {
            ensureDriverLoaded()
            val dbFile = File(config.sqlite_file)
            dbFile.parentFile?.mkdirs()

            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 8
                minimumIdle = 2
                idleTimeout = 30_000L
                maxLifetime = 300_000L
                isAutoCommit = true
            }

            sqliteDataSource = HikariDataSource(hikariConfig)
            val db = Database.connect(sqliteDataSource!!)
            this.sqliteDb = db

            // Create or update schema in local SQLite mirror
            transaction(db) {
                SchemaUtils.createMissingTablesAndColumns(*mirroredTables)
            }

            isAvailable = true
            lastSyncStatus = "Initialized, awaiting initial sync"
            println("[QuorumFallbackStore] Local SQLite mirror initialized at ${dbFile.absolutePath}")
        } catch (e: Throwable) {
            isAvailable = false
            lastSyncStatus = "Initialization failed: ${e.message}"
            println("[QuorumFallbackStore] Failed to initialize SQLite mirror: ${e.message}")
        }
    }

    @Synchronized
    fun start() {
        if (!isEnabled || syncJob?.isActive == true) return

        syncJob = scope.launch {
            // Initial sync shortly after boot
            delay(5_000L)
            while (isActive) {
                if (isEnabled && !CockroachOrchestrator.isQuorumLost && DatabaseFactory.isReady) {
                    try {
                        syncFromCockroach()
                    } catch (e: Exception) {
                        lastSyncStatus = "Sync error: ${e.message}"
                        println("[QuorumFallbackStore] Background sync error: ${e.message}")
                    }
                }
                delay(config.sync_interval_seconds.coerceAtLeast(10L) * 1000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        syncJob?.cancel()
        syncJob = null
    }

    @Synchronized
    fun disableAndPurge(updateConfigFile: Boolean = true) {
        isEnabled = false
        isAvailable = false
        stop()

        try {
            sqliteDataSource?.close()
        } catch (_: Exception) {}
        sqliteDataSource = null
        sqliteDb = null

        val dbFile = File(config.sqlite_file)
        val walFile = File("${config.sqlite_file}-wal")
        val shmFile = File("${config.sqlite_file}-shm")

        if (dbFile.exists()) dbFile.delete()
        if (walFile.exists()) walFile.delete()
        if (shmFile.exists()) shmFile.delete()

        lastSyncStatus = "Disabled (Storage purged)"
        println("[QuorumFallbackStore] Quorum fallback disabled and local database files purged.")

        if (updateConfigFile) {
            try {
                val current = AppConfigLoader.load(forceReload = true)
                val updated = current.copy(
                    quorum_fallback = current.quorum_fallback.copy(enabled = false)
                )
                val text = com.obsidianscout.config.JsonSupport.json.encodeToString(com.obsidianscout.config.AppConfig.serializer(), updated)
                Files.writeString(Paths.get("config", "app-config.json"), text)
                AppConfigLoader.updateCache(updated)
            } catch (e: Exception) {
                println("[QuorumFallbackStore] Error updating app-config.json: ${e.message}")
            }
        }
    }

    @Synchronized
    fun enableAndInitialize() {
        val current = AppConfigLoader.load(forceReload = true)
        val updated = current.copy(
            quorum_fallback = current.quorum_fallback.copy(enabled = true)
        )
        val text = com.obsidianscout.config.JsonSupport.json.encodeToString(com.obsidianscout.config.AppConfig.serializer(), updated)
        Files.writeString(Paths.get("config", "app-config.json"), text)
        AppConfigLoader.updateCache(updated)

        init(updated)
        start()
        scope.launch {
            delay(1000L)
            syncFromCockroach()
        }
    }

    /**
     * Executes a read transaction against the local SQLite snapshot.
     */
    fun <T> executeRead(statement: Transaction.() -> T): T {
        val db = sqliteDb ?: throw QuorumLostException("Local SQLite fallback is not available on this server.")
        return transaction(db = db) {
            statement()
        }
    }

    /**
     * Snapshots critical data from CockroachDB to local SQLite.
     */
    fun syncFromCockroach(): Boolean {
        if (!isEnabled || CockroachOrchestrator.isQuorumLost || !DatabaseFactory.isReady || sqliteDb == null) {
            return false
        }
        if (!isSyncing.compareAndSet(false, true)) {
            return false
        }

        try {
            val targetDb = sqliteDb ?: return false
            val cutoffInstant = Instant.now().minus(config.scouting_retention_days.toLong(), ChronoUnit.DAYS)

            // 1. Read datasets from CockroachDB (in standard read transaction)
            val usersList = DatabaseFactory.readTransaction { Users.selectAll().toList() }
            val userSessionsList = DatabaseFactory.readTransaction { UserSessions.selectAll().toList() }
            val scoutingConfigsList = DatabaseFactory.readTransaction { ScoutingConfigs.selectAll().toList() }
            val pitConfigsList = DatabaseFactory.readTransaction { PitScoutingConfigs.selectAll().toList() }
            val qualConfigsList = DatabaseFactory.readTransaction { QualitativeScoutingConfigs.selectAll().toList() }
            val defaultConfigsList = DatabaseFactory.readTransaction { DefaultConfigs.selectAll().toList() }
            val configRevisionsList = DatabaseFactory.readTransaction { ConfigRevisions.selectAll().toList() }
            val appSettingsList = DatabaseFactory.readTransaction { AppSettings.selectAll().toList() }
            val bannersList = DatabaseFactory.readTransaction { Banners.selectAll().toList() }
            val alliancesList = DatabaseFactory.readTransaction { ScoutingAlliances.selectAll().toList() }
            val allianceMembershipsList = DatabaseFactory.readTransaction { AllianceMemberships.selectAll().toList() }
            val allianceSelectionsList = DatabaseFactory.readTransaction { AllianceSelections.selectAll().toList() }
            val chatGroupsList = DatabaseFactory.readTransaction { ChatGroups.selectAll().toList() }
            val chatMessagesList = DatabaseFactory.readTransaction { 
                ChatMessages.selectAll().orderBy(ChatMessages.createdAt, SortOrder.DESC).limit(2000).toList() 
            }
            val lastReadsList = DatabaseFactory.readTransaction { UserChatLastRead.selectAll().toList() }
            val clusterSecretsList = DatabaseFactory.readTransaction { ClusterSecrets.selectAll().toList() }
            val fcmConfigsList = DatabaseFactory.readTransaction { FcmConfigs.selectAll().toList() }
            val fcmTokensList = DatabaseFactory.readTransaction { FcmDeviceTokens.selectAll().toList() }
            val pushSubsList = DatabaseFactory.readTransaction { PushSubscriptions.selectAll().toList() }

            // Recent Events, Teams, Matches (within ±7 days or current season)
            val eventsList = DatabaseFactory.readTransaction { ApiEvents.selectAll().toList() }
            val teamsList = DatabaseFactory.readTransaction { ApiTeams.selectAll().toList() }
            val matchesList = DatabaseFactory.readTransaction { ApiMatches.selectAll().toList() }

            // 7-day scouting entries + prescout
            val matchEntriesList = DatabaseFactory.readTransaction {
                ScoutingEntries.selectAll()
                    .where { (ScoutingEntries.createdAt greaterEq cutoffInstant) or (ScoutingEntries.isPrescout eq true) }
                    .toList()
            }
            val pitEntriesList = DatabaseFactory.readTransaction {
                PitScoutingEntries.selectAll()
                    .where { (PitScoutingEntries.createdAt greaterEq cutoffInstant) or (PitScoutingEntries.isPrescout eq true) }
                    .toList()
            }
            val qualEntriesList = DatabaseFactory.readTransaction {
                QualitativeScoutingEntries.selectAll()
                    .where { (QualitativeScoutingEntries.createdAt greaterEq cutoffInstant) or (QualitativeScoutingEntries.isPrescout eq true) }
                    .toList()
            }

            // 2. Batch mirror into local SQLite inside a single atomic transaction
            transaction(targetDb) {
                // Users
                Users.deleteAll()
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
                    }
                }

                // UserSessions
                UserSessions.deleteAll()
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
                ScoutingConfigs.deleteAll()
                for (row in scoutingConfigsList) {
                    ScoutingConfigs.insert {
                        it[id] = EntityID(row[ScoutingConfigs.id].value, ScoutingConfigs)
                        it[teamNumber] = row[ScoutingConfigs.teamNumber]
                        it[program] = row[ScoutingConfigs.program]
                        it[configJson] = row[ScoutingConfigs.configJson]
                        it[updatedAt] = row[ScoutingConfigs.updatedAt]
                    }
                }

                PitScoutingConfigs.deleteAll()
                for (row in pitConfigsList) {
                    PitScoutingConfigs.insert {
                        it[id] = EntityID(row[PitScoutingConfigs.id].value, PitScoutingConfigs)
                        it[teamNumber] = row[PitScoutingConfigs.teamNumber]
                        it[program] = row[PitScoutingConfigs.program]
                        it[configJson] = row[PitScoutingConfigs.configJson]
                        it[updatedAt] = row[PitScoutingConfigs.updatedAt]
                    }
                }

                QualitativeScoutingConfigs.deleteAll()
                for (row in qualConfigsList) {
                    QualitativeScoutingConfigs.insert {
                        it[id] = EntityID(row[QualitativeScoutingConfigs.id].value, QualitativeScoutingConfigs)
                        it[teamNumber] = row[QualitativeScoutingConfigs.teamNumber]
                        it[program] = row[QualitativeScoutingConfigs.program]
                        it[configJson] = row[QualitativeScoutingConfigs.configJson]
                        it[updatedAt] = row[QualitativeScoutingConfigs.updatedAt]
                    }
                }

                DefaultConfigs.deleteAll()
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

                ConfigRevisions.deleteAll()
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

                // App Settings
                AppSettings.deleteAll()
                for (row in appSettingsList) {
                    AppSettings.insert {
                        it[id] = EntityID(row[AppSettings.id].value, AppSettings)
                        it[teamNumber] = row[AppSettings.teamNumber]
                        it[program] = row[AppSettings.program]
                        it[settingsJson] = row[AppSettings.settingsJson]
                        it[updatedAt] = row[AppSettings.updatedAt]
                    }
                }

                // Banners
                Banners.deleteAll()
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

                // Alliances
                ScoutingAlliances.deleteAll()
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

                AllianceMemberships.deleteAll()
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

                AllianceSelections.deleteAll()
                for (row in allianceSelectionsList) {
                    AllianceSelections.insert {
                        it[id] = EntityID(row[AllianceSelections.id].value, AllianceSelections)
                        it[ownerKey] = row[AllianceSelections.ownerKey]
                        it[eventKey] = row[AllianceSelections.eventKey]
                        it[selectionJson] = row[AllianceSelections.selectionJson]
                        it[updatedAt] = row[AllianceSelections.updatedAt]
                    }
                }

                // Chat
                ChatGroups.deleteAll()
                for (row in chatGroupsList) {
                    ChatGroups.insert {
                        it[id] = EntityID(row[ChatGroups.id].value, ChatGroups)
                        it[teamNumber] = row[ChatGroups.teamNumber]
                        it[program] = row[ChatGroups.program]
                        it[groupName] = row[ChatGroups.groupName]
                        it[createdByUserId] = row[ChatGroups.createdByUserId]?.value?.let { EntityID(it, Users) }
                        it[createdAt] = row[ChatGroups.createdAt]
                        it[allowedRoles] = row[ChatGroups.allowedRoles]
                        it[allowedUserIds] = row[ChatGroups.allowedUserIds]
                    }
                }

                ChatMessages.deleteAll()
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

                UserChatLastRead.deleteAll()
                for (row in lastReadsList) {
                    UserChatLastRead.insert {
                        it[id] = EntityID(row[UserChatLastRead.id].value, UserChatLastRead)
                        it[userId] = EntityID(row[UserChatLastRead.userId].value, Users)
                        it[groupName] = row[UserChatLastRead.groupName]
                        it[lastReadAt] = row[UserChatLastRead.lastReadAt]
                    }
                }

                // Secrets & Notifications
                ClusterSecrets.deleteAll()
                for (row in clusterSecretsList) {
                    ClusterSecrets.insert {
                        it[id] = EntityID(row[ClusterSecrets.id].value, ClusterSecrets)
                        it[keyName] = row[ClusterSecrets.keyName]
                        it[keyValue] = row[ClusterSecrets.keyValue]
                        it[updatedAt] = row[ClusterSecrets.updatedAt]
                    }
                }

                FcmConfigs.deleteAll()
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

                FcmDeviceTokens.deleteAll()
                for (row in fcmTokensList) {
                    FcmDeviceTokens.insert {
                        it[id] = EntityID(row[FcmDeviceTokens.id].value, FcmDeviceTokens)
                        it[userId] = EntityID(row[FcmDeviceTokens.userId].value, Users)
                        it[deviceToken] = row[FcmDeviceTokens.deviceToken]
                        it[platform] = row[FcmDeviceTokens.platform]
                        it[updatedAt] = row[FcmDeviceTokens.updatedAt]
                    }
                }

                PushSubscriptions.deleteAll()
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

                // Events, Teams & Matches
                ApiEvents.deleteAll()
                for (row in eventsList) {
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

                ApiTeams.deleteAll()
                for (row in teamsList) {
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

                ApiMatches.deleteAll()
                for (row in matchesList) {
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

                // 7-day scouting data
                ScoutingEntries.deleteAll()
                for (row in matchEntriesList) {
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

                PitScoutingEntries.deleteAll()
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

                QualitativeScoutingEntries.deleteAll()
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
            }

            lastSyncInstant = Instant.now()
            lastSyncStatus = "Healthy (Synced ${java.time.format.DateTimeFormatter.ISO_INSTANT.format(lastSyncInstant)})"
            return true
        } catch (e: Throwable) {
            lastSyncStatus = "Sync failed: ${e.message}"
            println("[QuorumFallbackStore] Error during SQLite mirror sync: ${e.message}")
            return false
        } finally {
            isSyncing.set(false)
        }
    }

    fun getStatus(localIp: String = "127.0.0.1"): QuorumFallbackStatusDto {
        val dbFile = File(config.sqlite_file)
        val walFile = File("${config.sqlite_file}-wal")
        val shmFile = File("${config.sqlite_file}-shm")

        val totalSize = (if (dbFile.exists()) dbFile.length() else 0L) +
                (if (walFile.exists()) walFile.length() else 0L) +
                (if (shmFile.exists()) shmFile.length() else 0L)

        val rootFile = if (dbFile.exists()) dbFile else File(".")
        val freeDisk = rootFile.freeSpace
        val totalDisk = rootFile.totalSpace

        val counts = mutableMapOf<String, Long>()
        if (isAvailable && sqliteDb != null) {
            try {
                transaction(sqliteDb!!) {
                    counts["users"] = Users.selectAll().count()
                    counts["scoutingEntries"] = ScoutingEntries.selectAll().count()
                    counts["pitEntries"] = PitScoutingEntries.selectAll().count()
                    counts["qualEntries"] = QualitativeScoutingEntries.selectAll().count()
                    counts["events"] = ApiEvents.selectAll().count()
                    counts["matches"] = ApiMatches.selectAll().count()
                    counts["teams"] = ApiTeams.selectAll().count()
                }
            } catch (_: Exception) {}
        }

        return QuorumFallbackStatusDto(
            nodeIp = localIp,
            isLocal = true,
            enabled = isEnabled,
            isAvailable = isAvailable,
            isActiveServingReads = CockroachOrchestrator.isQuorumLost && isAvailable,
            status = when {
                !isEnabled -> "Disabled"
                CockroachOrchestrator.isQuorumLost -> "Active (Serving Offline Reads)"
                lastSyncInstant != null -> "Healthy"
                else -> lastSyncStatus
            },
            databaseSizeBytes = totalSize,
            freeDiskSpaceBytes = freeDisk,
            totalDiskSpaceBytes = totalDisk,
            lastSyncTimestamp = lastSyncInstant?.toString(),
            recordCounts = counts
        )
    }

    private fun ensureDriverLoaded() {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (_: Exception) {}
    }
}
