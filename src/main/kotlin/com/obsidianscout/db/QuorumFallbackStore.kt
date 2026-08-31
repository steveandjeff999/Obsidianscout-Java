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
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class QuorumFallbackEventDetailDto(
    val eventKey: String,
    val name: String,
    val startDate: String?,
    val endDate: String?,
    val matchCount: Int,
    val teamCount: Int,
    val matchScoutingCount: Int,
    val pitScoutingCount: Int,
    val qualScoutingCount: Int
)

@Serializable
data class QuorumFallbackInspectionDto(
    val nodeIp: String,
    val isLocal: Boolean,
    val enabled: Boolean,
    val isAvailable: Boolean,
    val status: String,
    val databaseSizeBytes: Long,
    val freeDiskSpaceBytes: Long,
    val totalDiskSpaceBytes: Long,
    val lastSyncTimestamp: String?,
    val tableCounts: Map<String, Long> = emptyMap(),
    val activeEvents: List<QuorumFallbackEventDetailDto> = emptyList(),
    val config: QuorumFallbackConfig = QuorumFallbackConfig()
)

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
    val recordCounts: Map<String, Long> = emptyMap(),
    val config: QuorumFallbackConfig = QuorumFallbackConfig()
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
    @Volatile
    var config: QuorumFallbackConfig = QuorumFallbackConfig()
        private set

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
            val db = Database.connect(
                sqliteDataSource!!,
                databaseConfig = org.jetbrains.exposed.sql.DatabaseConfig {
                    defaultMaxAttempts = 1
                    defaultMinRetryDelay = 0
                    defaultMaxRetryDelay = 0
                    defaultReadOnly = false
                    defaultIsolationLevel = java.sql.Connection.TRANSACTION_SERIALIZABLE
                }
            )
            this.sqliteDb = db

            // Create or update schema in local SQLite mirror
            transaction(
                transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                readOnly = false,
                db = db
            ) {
                SchemaUtils.createMissingTablesAndColumns(*mirroredTables)
            }

            // CRITICAL FIX: Database.connect() in Exposed automatically overrides TransactionManager.defaultDatabase.
            // We MUST immediately restore TransactionManager.defaultDatabase to DatabaseFactory.primaryDatabase
            // so that all application transactions (AuthService, UserService, session verification, scouter forms)
            // continue targeting the real CockroachDB database and are never redirected to the SQLite mirror!
            if (DatabaseFactory.primaryDatabase != null) {
                org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
            }

            isAvailable = true
            lastSyncStatus = "Initialized, awaiting initial sync"
            println("[QuorumFallbackStore] Local SQLite mirror initialized at ${dbFile.absolutePath}")
        } catch (e: Throwable) {
            isAvailable = false
            lastSyncStatus = "Initialization failed: ${e.message}"
            println("[QuorumFallbackStore] Failed to initialize SQLite mirror: ${e.message}")
        } finally {
            if (DatabaseFactory.primaryDatabase != null) {
                org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
            }
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

        if (DatabaseFactory.primaryDatabase != null) {
            org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
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

        if (DatabaseFactory.primaryDatabase != null) {
            org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
        }

        scope.launch {
            delay(1000L)
            syncFromCockroach()
        }
    }

    @Synchronized
    fun updateConfiguration(newConfig: QuorumFallbackConfig, updateConfigFile: Boolean = true) {
        val current = AppConfigLoader.load(forceReload = true)
        val updated = current.copy(quorum_fallback = newConfig)

        if (updateConfigFile) {
            try {
                val text = com.obsidianscout.config.JsonSupport.json.encodeToString(com.obsidianscout.config.AppConfig.serializer(), updated)
                Files.writeString(Paths.get("config", "app-config.json"), text)
                AppConfigLoader.updateCache(updated)
            } catch (e: Exception) {
                println("[QuorumFallbackStore] Error updating app-config.json: ${e.message}")
            }
        }

        if (newConfig.enabled) {
            init(updated)
            start()
            scope.launch {
                delay(1000L)
                syncFromCockroach()
            }
        } else {
            disableAndPurge(updateConfigFile = false)
        }

        if (DatabaseFactory.primaryDatabase != null) {
            org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
        }
    }

    /**
     * Executes a read transaction against the local SQLite snapshot.
     */
    fun <T> executeRead(statement: Transaction.() -> T): T {
        val db = sqliteDb ?: throw QuorumLostException("Local SQLite fallback is not available on this server.")
        try {
            return transaction(
                transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                readOnly = false,
                db = db
            ) {
                statement()
            }
        } finally {
            if (DatabaseFactory.primaryDatabase != null) {
                org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
            }
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
            val isMirrorAll = config.mirror_all_data
            val retentionDays = config.scouting_retention_days.toLong().coerceAtLeast(1L)
            val today = LocalDate.now(ZoneOffset.UTC)
            val minDate = today.minusDays(retentionDays)
            val maxDate = today.plusDays(retentionDays)
            val cutoffInstant = Instant.now().minus(retentionDays, java.time.temporal.ChronoUnit.DAYS)

            // 1. Read datasets from CockroachDB based on configured categories
            val usersList = if (config.mirror_users || isMirrorAll) {
                DatabaseFactory.readTransaction { Users.selectAll().toList() }
            } else emptyList()

            val userSessionsList = if (config.mirror_users || isMirrorAll) {
                DatabaseFactory.readTransaction { UserSessions.selectAll().toList() }
            } else emptyList()

            val scoutingConfigsList = if (config.mirror_configs || isMirrorAll) {
                DatabaseFactory.readTransaction { ScoutingConfigs.selectAll().toList() }
            } else emptyList()

            val pitConfigsList = if (config.mirror_configs || isMirrorAll) {
                DatabaseFactory.readTransaction { PitScoutingConfigs.selectAll().toList() }
            } else emptyList()

            val qualConfigsList = if (config.mirror_configs || isMirrorAll) {
                DatabaseFactory.readTransaction { QualitativeScoutingConfigs.selectAll().toList() }
            } else emptyList()

            val defaultConfigsList = if (config.mirror_configs || isMirrorAll) {
                DatabaseFactory.readTransaction { DefaultConfigs.selectAll().toList() }
            } else emptyList()

            val configRevisionsList = if (config.mirror_configs || isMirrorAll) {
                DatabaseFactory.readTransaction { ConfigRevisions.selectAll().toList() }
            } else emptyList()

            val appSettingsList = if (config.mirror_configs || isMirrorAll) {
                DatabaseFactory.readTransaction { AppSettings.selectAll().toList() }
            } else emptyList()

            val bannersList = if (config.mirror_configs || isMirrorAll) {
                DatabaseFactory.readTransaction { Banners.selectAll().toList() }
            } else emptyList()

            val alliancesList = if (config.mirror_alliances || isMirrorAll) {
                DatabaseFactory.readTransaction { ScoutingAlliances.selectAll().toList() }
            } else emptyList()

            val allianceMembershipsList = if (config.mirror_alliances || isMirrorAll) {
                DatabaseFactory.readTransaction { AllianceMemberships.selectAll().toList() }
            } else emptyList()

            val allianceSelectionsList = if (config.mirror_alliances || isMirrorAll) {
                DatabaseFactory.readTransaction { AllianceSelections.selectAll().toList() }
            } else emptyList()

            val chatGroupsList = if (config.mirror_chat || isMirrorAll) {
                DatabaseFactory.readTransaction { ChatGroups.selectAll().toList() }
            } else emptyList()

            val chatMessagesList = if (config.mirror_chat || isMirrorAll) {
                DatabaseFactory.readTransaction { 
                    ChatMessages.selectAll().orderBy(ChatMessages.createdAt, SortOrder.DESC).limit(2000).toList() 
                }
            } else emptyList()

            val lastReadsList = if (config.mirror_chat || isMirrorAll) {
                DatabaseFactory.readTransaction { UserChatLastRead.selectAll().toList() }
            } else emptyList()

            val clusterSecretsList = if (config.mirror_notifications_secrets || isMirrorAll) {
                DatabaseFactory.readTransaction { ClusterSecrets.selectAll().toList() }
            } else emptyList()

            val fcmConfigsList = if (config.mirror_notifications_secrets || isMirrorAll) {
                DatabaseFactory.readTransaction { FcmConfigs.selectAll().toList() }
            } else emptyList()

            val fcmTokensList = if (config.mirror_notifications_secrets || isMirrorAll) {
                DatabaseFactory.readTransaction { FcmDeviceTokens.selectAll().toList() }
            } else emptyList()

            val pushSubsList = if (config.mirror_notifications_secrets || isMirrorAll) {
                DatabaseFactory.readTransaction { PushSubscriptions.selectAll().toList() }
            } else emptyList()

            // Events, Teams & Matches API data
            val allEvents = if (config.mirror_api_data || config.mirror_scouting || isMirrorAll) {
                DatabaseFactory.readTransaction { ApiEvents.selectAll().toList() }
            } else emptyList()

            val activeEventsList = if (isMirrorAll) {
                allEvents
            } else if (config.mirror_api_data || config.mirror_scouting) {
                allEvents.filter { row ->
                    val startStr = row[ApiEvents.startDate]
                    val endStr = row[ApiEvents.endDate]
                    val start = parseDate(startStr)
                    val end = parseDate(endStr) ?: start
                    if (start != null || end != null) {
                        val effectiveStart = start ?: end!!
                        val effectiveEnd = end ?: start!!
                        !effectiveStart.isAfter(maxDate) && !effectiveEnd.isBefore(minDate)
                    } else {
                        val year = row[ApiEvents.year]
                        val updated = row[ApiEvents.updatedAt]
                        (year >= today.year - 1 && year <= today.year + 1) && updated.isAfter(cutoffInstant)
                    }
                }
            } else emptyList()

            val activeEventKeys = activeEventsList.map { it[ApiEvents.eventKey] }.toSet()

            val teamsList = if (config.mirror_api_data || isMirrorAll) {
                DatabaseFactory.readTransaction {
                    if (isMirrorAll || activeEventKeys.isEmpty()) {
                        ApiTeams.selectAll().toList()
                    } else {
                        ApiTeams.selectAll().where { ApiTeams.eventKey inList activeEventKeys }.toList()
                    }
                }
            } else emptyList()

            val matchesList = if (config.mirror_api_data || isMirrorAll) {
                DatabaseFactory.readTransaction {
                    if (isMirrorAll || activeEventKeys.isEmpty()) {
                        ApiMatches.selectAll().toList()
                    } else {
                        ApiMatches.selectAll().where { ApiMatches.eventKey inList activeEventKeys }.toList()
                    }
                }
            } else emptyList()

            // Scouting entries
            val matchEntriesList = if (config.mirror_scouting || isMirrorAll) {
                DatabaseFactory.readTransaction {
                    if (isMirrorAll) {
                        ScoutingEntries.selectAll().toList()
                    } else if (activeEventKeys.isEmpty()) {
                        ScoutingEntries.selectAll()
                            .where { (ScoutingEntries.createdAt greaterEq cutoffInstant) or (ScoutingEntries.isPrescout eq true) }
                            .toList()
                    } else {
                        ScoutingEntries.selectAll()
                            .where { 
                                (ScoutingEntries.eventKey inList activeEventKeys) or 
                                (ScoutingEntries.createdAt greaterEq cutoffInstant) or 
                                (ScoutingEntries.isPrescout eq true) 
                            }
                            .toList()
                    }
                }
            } else emptyList()

            val pitEntriesList = if (config.mirror_scouting || isMirrorAll) {
                DatabaseFactory.readTransaction {
                    if (isMirrorAll) {
                        PitScoutingEntries.selectAll().toList()
                    } else if (activeEventKeys.isEmpty()) {
                        PitScoutingEntries.selectAll()
                            .where { (PitScoutingEntries.createdAt greaterEq cutoffInstant) or (PitScoutingEntries.isPrescout eq true) }
                            .toList()
                    } else {
                        PitScoutingEntries.selectAll()
                            .where { 
                                (PitScoutingEntries.eventKey inList activeEventKeys) or 
                                (PitScoutingEntries.createdAt greaterEq cutoffInstant) or 
                                (PitScoutingEntries.isPrescout eq true) 
                            }
                            .toList()
                    }
                }
            } else emptyList()

            val qualEntriesList = if (config.mirror_scouting || isMirrorAll) {
                DatabaseFactory.readTransaction {
                    if (isMirrorAll) {
                        QualitativeScoutingEntries.selectAll().toList()
                    } else if (activeEventKeys.isEmpty()) {
                        QualitativeScoutingEntries.selectAll()
                            .where { (QualitativeScoutingEntries.createdAt greaterEq cutoffInstant) or (QualitativeScoutingEntries.isPrescout eq true) }
                            .toList()
                    } else {
                        QualitativeScoutingEntries.selectAll()
                            .where { 
                                (QualitativeScoutingEntries.eventKey inList activeEventKeys) or 
                                (QualitativeScoutingEntries.createdAt greaterEq cutoffInstant) or 
                                (QualitativeScoutingEntries.isPrescout eq true) 
                            }
                            .toList()
                    }
                }
            } else emptyList()

            // 2. Batch mirror into local SQLite inside a single atomic transaction
            transaction(
                transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                readOnly = false,
                db = targetDb
            ) {
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
                for (row in activeEventsList) {
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
            if (DatabaseFactory.primaryDatabase != null) {
                org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
            }
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
                transaction(
                    transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                    readOnly = false,
                    db = sqliteDb!!
                ) {
                    counts["users"] = Users.selectAll().count()
                    counts["scoutingEntries"] = ScoutingEntries.selectAll().count()
                    counts["pitEntries"] = PitScoutingEntries.selectAll().count()
                    counts["qualEntries"] = QualitativeScoutingEntries.selectAll().count()
                    counts["events"] = ApiEvents.selectAll().count()
                    counts["matches"] = ApiMatches.selectAll().count()
                    counts["teams"] = ApiTeams.selectAll().count()
                }
            } catch (_: Exception) {
            } finally {
                if (DatabaseFactory.primaryDatabase != null) {
                    org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
                }
            }
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
            recordCounts = counts,
            config = this.config
        )
    }

    /**
     * Inspects all tables and active mirrored events in the local SQLite fallback database.
     */
    fun inspect(localIp: String = "127.0.0.1"): QuorumFallbackInspectionDto {
        val status = getStatus(localIp)
        val tableCounts = mutableMapOf<String, Long>()
        val eventsList = mutableListOf<QuorumFallbackEventDetailDto>()

        if (isAvailable && sqliteDb != null) {
            try {
                transaction(
                    transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                    readOnly = false,
                    db = sqliteDb!!
                ) {
                    tableCounts["users"] = Users.selectAll().count()
                    tableCounts["user_sessions"] = UserSessions.selectAll().count()
                    tableCounts["scouting_entries"] = ScoutingEntries.selectAll().count()
                    tableCounts["pit_scouting_entries"] = PitScoutingEntries.selectAll().count()
                    tableCounts["qualitative_scouting_entries"] = QualitativeScoutingEntries.selectAll().count()
                    tableCounts["api_events"] = ApiEvents.selectAll().count()
                    tableCounts["api_teams"] = ApiTeams.selectAll().count()
                    tableCounts["api_matches"] = ApiMatches.selectAll().count()
                    tableCounts["scouting_configs"] = ScoutingConfigs.selectAll().count()
                    tableCounts["pit_scouting_configs"] = PitScoutingConfigs.selectAll().count()
                    tableCounts["qualitative_scouting_configs"] = QualitativeScoutingConfigs.selectAll().count()
                    tableCounts["default_configs"] = DefaultConfigs.selectAll().count()
                    tableCounts["config_revisions"] = ConfigRevisions.selectAll().count()
                    tableCounts["app_settings"] = AppSettings.selectAll().count()
                    tableCounts["scouting_alliances"] = ScoutingAlliances.selectAll().count()
                    tableCounts["alliance_memberships"] = AllianceMemberships.selectAll().count()
                    tableCounts["alliance_selections"] = AllianceSelections.selectAll().count()
                    tableCounts["banners"] = Banners.selectAll().count()
                    tableCounts["chat_groups"] = ChatGroups.selectAll().count()
                    tableCounts["chat_messages"] = ChatMessages.selectAll().count()
                    tableCounts["user_chat_last_read"] = UserChatLastRead.selectAll().count()
                    tableCounts["cluster_secrets"] = ClusterSecrets.selectAll().count()
                    tableCounts["fcm_config"] = FcmConfigs.selectAll().count()
                    tableCounts["fcm_device_tokens"] = FcmDeviceTokens.selectAll().count()
                    tableCounts["push_subscriptions"] = PushSubscriptions.selectAll().count()

                    // Load mirrored events with their breakdown
                    val allEvents = ApiEvents.selectAll().toList()
                    for (ev in allEvents) {
                        val eKey = ev[ApiEvents.eventKey]
                        val matchesCount = ApiMatches.selectAll().where { ApiMatches.eventKey eq eKey }.count().toInt()
                        val teamsCount = ApiTeams.selectAll().where { ApiTeams.eventKey eq eKey }.count().toInt()
                        val scCount = ScoutingEntries.selectAll().where { ScoutingEntries.eventKey eq eKey }.count().toInt()
                        val pitCount = PitScoutingEntries.selectAll().where { PitScoutingEntries.eventKey eq eKey }.count().toInt()
                        val qualCount = QualitativeScoutingEntries.selectAll().where { QualitativeScoutingEntries.eventKey eq eKey }.count().toInt()

                        eventsList.add(
                            QuorumFallbackEventDetailDto(
                                eventKey = eKey,
                                name = ev[ApiEvents.name],
                                startDate = ev[ApiEvents.startDate],
                                endDate = ev[ApiEvents.endDate],
                                matchCount = matchesCount,
                                teamCount = teamsCount,
                                matchScoutingCount = scCount,
                                pitScoutingCount = pitCount,
                                qualScoutingCount = qualCount
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (DatabaseFactory.primaryDatabase != null) {
                    org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
                }
            }
        }

        return QuorumFallbackInspectionDto(
            nodeIp = status.nodeIp,
            isLocal = status.isLocal,
            enabled = status.enabled,
            isAvailable = status.isAvailable,
            status = status.status,
            databaseSizeBytes = status.databaseSizeBytes,
            freeDiskSpaceBytes = status.freeDiskSpaceBytes,
            totalDiskSpaceBytes = status.totalDiskSpaceBytes,
            lastSyncTimestamp = status.lastSyncTimestamp,
            tableCounts = tableCounts,
            activeEvents = eventsList,
            config = this.config
        )
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            val clean = dateStr.trim().take(10)
            LocalDate.parse(clean)
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureDriverLoaded() {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (_: Exception) {}
    }
}
