package com.obsidianscout.db

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.QuorumFallbackConfig
import com.obsidianscout.db.orchestration.CockroachOrchestrator
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private val consoleLog = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.db.QuorumFallbackStore")


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

    private const val MIRRORED_CONFIG_REVISIONS = 500
    private const val MIRRORED_REPORTED_ERRORS = 200
    private const val MIRRORED_CHAT_MESSAGES = 2000
    /** Ids per IN (...) list, well under SQLite's bound-parameter limit. */
    private const val ID_CHUNK = 500
    /** How often a sync compares full rows rather than only id + timestamp. */
    private val FULL_COMPARE_INTERVAL: java.time.Duration = java.time.Duration.ofMinutes(15)

    @Volatile
    var lastSyncInstant: Instant? = null

    @Volatile
    var lastSyncStatus: String = "Not initialized"

    private val mirroredTables: Array<Table> = arrayOf(
        BlobObjects,
        Users,
        UserSessions,
        ScoutingConfigs,
        PitScoutingConfigs,
        QualitativeScoutingConfigs,
        DefaultConfigs,
        DeletedDefaultConfigs,
        ConfigRevisions,
        AppSettings,
        ApiEvents,
        ApiTeams,
        ApiMatches,
        TeamCacheOverrides,
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
        PushSubscriptions,
        AnalyticsReports,
        ReportedErrors
    )

    @Synchronized
    fun init(appConfig: AppConfig) {
        this.config = appConfig.quorum_fallback
        this.isEnabled = config.enabled
        // A new file, an older release's file or a changed config: the first sync compares full rows.
        lastFullCompareInstant = null

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
                databaseConfig = org.jetbrains.exposed.v1.core.DatabaseConfig {
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
                SchemaUtils.create(*mirroredTables)
            }
            // An existing mirror file from an older release may lack newer columns.
            sqliteDataSource!!.connection.use { conn ->
                conn.autoCommit = true
                SqliteSchemaUpgrader.addMissingColumns(conn, mirroredTables.toList())
            }

            // CRITICAL FIX: Database.connect() in Exposed automatically overrides TransactionManager.defaultDatabase.
            // We MUST immediately restore TransactionManager.defaultDatabase to DatabaseFactory.primaryDatabase
            // so that all application transactions (AuthService, UserService, session verification, scouter forms)
            // continue targeting the real CockroachDB database and are never redirected to the SQLite mirror!
            if (DatabaseFactory.primaryDatabase != null) {
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
            }

            isAvailable = true
            lastSyncStatus = "Initialized, awaiting initial sync"
            consoleLog.info("[QuorumFallbackStore] Local SQLite mirror initialized at ${dbFile.absolutePath}")
        } catch (e: Throwable) {
            isAvailable = false
            lastSyncStatus = "Initialization failed: ${e.message}"
            consoleLog.error("[QuorumFallbackStore] Failed to initialize SQLite mirror: ${e.message}")
        } finally {
            if (DatabaseFactory.primaryDatabase != null) {
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
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
                        consoleLog.error("[QuorumFallbackStore] Background sync error: ${e.message}")
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
        consoleLog.info("[QuorumFallbackStore] Quorum fallback disabled and local database files purged.")

        if (updateConfigFile) {
            try {
                val current = AppConfigLoader.load(forceReload = true)
                val updated = current.copy(
                    quorum_fallback = current.quorum_fallback.copy(enabled = false)
                )
                val text = com.obsidianscout.config.JsonSupport.json.encodeToString(com.obsidianscout.config.AppConfig.serializer(), AppConfigLoader.stripSecrets(updated))
                Files.writeString(Paths.get("config", "app-config.json"), text)
                AppConfigLoader.updateCache(updated)
            } catch (e: Exception) {
                consoleLog.error("[QuorumFallbackStore] Error updating app-config.json: ${e.message}")
            }
        }

        if (DatabaseFactory.primaryDatabase != null) {
            org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
        }
    }

    @Synchronized
    fun enableAndInitialize() {
        val current = AppConfigLoader.load(forceReload = true)
        val updated = current.copy(
            quorum_fallback = current.quorum_fallback.copy(enabled = true)
        )
        val text = com.obsidianscout.config.JsonSupport.json.encodeToString(com.obsidianscout.config.AppConfig.serializer(), AppConfigLoader.stripSecrets(updated))
        Files.writeString(Paths.get("config", "app-config.json"), text)
        AppConfigLoader.updateCache(updated)

        init(updated)
        start()

        if (DatabaseFactory.primaryDatabase != null) {
            org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
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
                val text = com.obsidianscout.config.JsonSupport.json.encodeToString(com.obsidianscout.config.AppConfig.serializer(), AppConfigLoader.stripSecrets(updated))
                Files.writeString(Paths.get("config", "app-config.json"), text)
                AppConfigLoader.updateCache(updated)
            } catch (e: Exception) {
                consoleLog.error("[QuorumFallbackStore] Error updating app-config.json: ${e.message}")
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
            org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
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
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
            }
        }
    }

    /** What the last successful sync did. */
    data class SyncResult(val fullCompare: Boolean, val rowsWritten: Int, val rowsDeleted: Int)

    @Volatile
    var lastSyncResult: SyncResult? = null
        private set

    /** Set by the last sync that compared full rows; null until the first one after init. */
    @Volatile
    private var lastFullCompareInstant: Instant? = null

    /**
     * How one table is mirrored. The mirror holds exactly the rows that [where] (and [newest]/[limit], when set)
     * selects; anything else already in the mirror is removed.
     *
     * [version] is a timestamp most writes bump. Ordinary syncs read only `id` + [version] and fetch full rows just
     * for ids that are new or whose timestamp moved. Some updates leave the timestamp alone, so the periodic full
     * compare reads whole rows and fixes whatever the light syncs missed. Tables without one are small and are
     * compared in full on every sync.
     */
    private class MirrorSpec(
        val table: UUIDTable,
        val enabled: Boolean = true,
        val where: (() -> Op<Boolean>)? = null,
        val newest: Column<*>? = null,
        val limit: Int = 0,
        val version: Column<*>? = null,
        excluded: Set<Column<*>> = emptySet(),
    ) {
        val columns: List<Column<*>> = table.columns.filterNot { it in excluded }

        fun query(fields: List<Expression<*>>): Query {
            val base = table.select(fields)
            val filtered = where?.let { op -> base.where { op() } } ?: base
            return if (newest != null) filtered.orderBy(newest, SortOrder.DESC).limit(limit) else filtered
        }
    }

    private class TableChanges(val spec: MirrorSpec, val upserts: List<ResultRow>, val deletes: List<UUID>)

    /**
     * Brings the local SQLite mirror up to date with CockroachDB, writing only the rows that changed.
     *
     * Large tables are checked by `id` + timestamp, so a quiet sync reads little from CockroachDB and writes nothing
     * to SQLite. Every [FULL_COMPARE_INTERVAL], on the first sync after init, and when [forceFull] is set, full rows
     * are compared instead, which catches edits that didn't bump the timestamp. All changes go into one SQLite
     * transaction, so readers always see a consistent mirror.
     */
    fun syncFromCockroach(forceFull: Boolean = false): Boolean {
        if (!isEnabled || CockroachOrchestrator.isQuorumLost || !DatabaseFactory.isReady || sqliteDb == null) {
            return false
        }
        if (!isSyncing.compareAndSet(false, true)) {
            return false
        }

        try {
            val targetDb = sqliteDb ?: return false
            val startedAt = Instant.now()
            val lastFull = lastFullCompareInstant
            val fullCompare = forceFull || lastFull == null ||
                    java.time.Duration.between(lastFull, startedAt) >= FULL_COMPARE_INTERVAL

            val changes = buildMirrorSpecs(startedAt).map { diffTable(it, fullCompare, targetDb) }
            val written = changes.sumOf { it.upserts.size }
            val deleted = changes.sumOf { it.deletes.size }

            if (written > 0 || deleted > 0) {
                transaction(
                    transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                    readOnly = false,
                    db = targetDb
                ) {
                    // Remove children before parents, then write parents before children.
                    for (change in changes.asReversed()) {
                        val table = change.spec.table
                        for (chunk in change.deletes.chunked(ID_CHUNK)) {
                            table.deleteWhere { table.id inList chunk }
                        }
                    }
                    for (change in changes) {
                        if (change.upserts.isEmpty()) continue
                        val table = change.spec.table
                        val columns = change.spec.columns
                        for (chunk in change.upserts.chunked(ID_CHUNK)) {
                            table.batchUpsert(chunk, table.id, shouldReturnGeneratedValues = false) { row ->
                                copyColumns(this, row, columns)
                            }
                        }
                    }
                }
            }

            if (fullCompare) lastFullCompareInstant = startedAt
            lastSyncResult = SyncResult(fullCompare, written, deleted)
            lastSyncInstant = Instant.now()
            val mode = if (fullCompare) "full compare" else "quick check"
            lastSyncStatus = "Healthy (Synced ${java.time.format.DateTimeFormatter.ISO_INSTANT.format(lastSyncInstant)}, $mode: $written written, $deleted removed)"
            return true
        } catch (e: Throwable) {
            lastSyncStatus = "Sync failed: ${e.message}"
            consoleLog.error("[QuorumFallbackStore] Error during SQLite mirror sync: ${e.message}")
            return false
        } finally {
            isSyncing.set(false)
            if (DatabaseFactory.primaryDatabase != null) {
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
            }
        }
    }

    /** The mirrored tables in parent-before-child order, scoped by the current config and date window. */
    private fun buildMirrorSpecs(now: Instant): List<MirrorSpec> {
        val all = config.mirror_all_data
        val users = config.mirror_users || all
        val alliances = config.mirror_alliances || all
        val chat = config.mirror_chat || all
        val secrets = config.mirror_notifications_secrets || all
        val analytics = config.mirror_custom_analytics || all
        val apiData = config.mirror_api_data || all
        val scouting = config.mirror_scouting || all

        val retentionDays = config.scouting_retention_days.toLong().coerceAtLeast(1L)
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val minDate = today.minusDays(retentionDays)
        val maxDate = today.plusDays(retentionDays)
        val cutoff = now.minus(retentionDays, ChronoUnit.DAYS)

        // Active events are picked by date in Kotlin, from a read that leaves out the JSON payload.
        val eventsWanted = apiData || scouting
        val activeEvents = if (!eventsWanted) emptyList() else DatabaseFactory.readTransaction {
            ApiEvents.select(ApiEvents.id, ApiEvents.eventKey, ApiEvents.year, ApiEvents.startDate, ApiEvents.endDate, ApiEvents.updatedAt).toList()
        }.filter { all || isEventInWindow(it, today, minDate, maxDate, cutoff) }
        val activeEventIds = activeEvents.map { it[ApiEvents.id].value }
        val activeEventKeys = activeEvents.map { it[ApiEvents.eventKey] }.toSet()
        // With no active events the old behavior was to mirror every team and match; keep that.
        val eventScoped = !all && activeEventKeys.isNotEmpty()

        return listOf(
            // Profile pictures (up to 1 MB each) are left out: fallback reads can live without avatars.
            // Users stay on a full compare every sync: the table is small and role or password changes matter.
            MirrorSpec(Users, enabled = users, excluded = setOf(Users.profilePicture)),
            // Small index rows saying where each photo lives in the object store, so photos still load during quorum loss.
            MirrorSpec(BlobObjects),
            MirrorSpec(UserSessions, enabled = users, version = UserSessions.lastActiveAt),
            // Game, pit and qualitative configs are fundamental to app operation and always mirrored.
            MirrorSpec(ScoutingConfigs, version = ScoutingConfigs.updatedAt),
            MirrorSpec(PitScoutingConfigs, version = PitScoutingConfigs.updatedAt),
            MirrorSpec(QualitativeScoutingConfigs, version = QualitativeScoutingConfigs.updatedAt),
            MirrorSpec(DefaultConfigs, version = DefaultConfigs.updatedAt),
            // History only grows; an outage needs the recent end of it. Revisions never change once written.
            MirrorSpec(ConfigRevisions, newest = ConfigRevisions.createdAt, limit = MIRRORED_CONFIG_REVISIONS, version = ConfigRevisions.createdAt),
            MirrorSpec(AppSettings, version = AppSettings.updatedAt),
            MirrorSpec(TeamCacheOverrides, version = TeamCacheOverrides.updatedAt),
            MirrorSpec(Banners, version = Banners.updatedAt),
            MirrorSpec(ScoutingAlliances, enabled = alliances, version = ScoutingAlliances.updatedAt),
            MirrorSpec(AllianceMemberships, enabled = alliances),
            MirrorSpec(AllianceSelections, enabled = alliances, version = AllianceSelections.updatedAt),
            MirrorSpec(ChatGroups, enabled = chat),
            MirrorSpec(ChatMessages, enabled = chat, newest = ChatMessages.createdAt, limit = MIRRORED_CHAT_MESSAGES, version = ChatMessages.updatedAt),
            MirrorSpec(UserChatLastRead, enabled = chat, version = UserChatLastRead.lastReadAt),
            MirrorSpec(ClusterSecrets, enabled = secrets),
            MirrorSpec(FcmConfigs, enabled = secrets),
            MirrorSpec(FcmDeviceTokens, enabled = secrets, version = FcmDeviceTokens.updatedAt),
            MirrorSpec(PushSubscriptions, enabled = secrets),
            MirrorSpec(AnalyticsReports, enabled = analytics, version = AnalyticsReports.updatedAt),
            // Status changes don't touch a timestamp; the periodic full compare picks them up.
            MirrorSpec(ReportedErrors, newest = ReportedErrors.createdAt, limit = MIRRORED_REPORTED_ERRORS, version = ReportedErrors.createdAt),
            MirrorSpec(
                ApiEvents,
                enabled = eventsWanted && (all || activeEventIds.isNotEmpty()),
                where = if (all) null else ({ ApiEvents.id inList activeEventIds }),
                version = ApiEvents.updatedAt
            ),
            MirrorSpec(
                ApiTeams,
                enabled = apiData,
                where = if (eventScoped) ({ ApiTeams.eventKey inList activeEventKeys }) else null,
                version = ApiTeams.updatedAt
            ),
            MirrorSpec(
                ApiMatches,
                enabled = apiData,
                where = if (eventScoped) ({ ApiMatches.eventKey inList activeEventKeys }) else null,
                version = ApiMatches.updatedAt
            ),
            MirrorSpec(
                ScoutingEntries,
                enabled = scouting,
                where = when {
                    all -> null
                    activeEventKeys.isEmpty() -> ({ (ScoutingEntries.createdAt greaterEq cutoff) or (ScoutingEntries.isPrescout eq true) })
                    else -> ({
                        (ScoutingEntries.eventKey inList activeEventKeys) or
                                (ScoutingEntries.createdAt greaterEq cutoff) or
                                (ScoutingEntries.isPrescout eq true)
                    })
                },
                version = ScoutingEntries.updatedAt
            ),
            MirrorSpec(
                PitScoutingEntries,
                enabled = scouting,
                where = when {
                    all -> null
                    activeEventKeys.isEmpty() -> ({ (PitScoutingEntries.createdAt greaterEq cutoff) or (PitScoutingEntries.isPrescout eq true) })
                    else -> ({
                        (PitScoutingEntries.eventKey inList activeEventKeys) or
                                (PitScoutingEntries.createdAt greaterEq cutoff) or
                                (PitScoutingEntries.isPrescout eq true)
                    })
                },
                version = PitScoutingEntries.updatedAt
            ),
            MirrorSpec(
                QualitativeScoutingEntries,
                enabled = scouting,
                where = when {
                    all -> null
                    activeEventKeys.isEmpty() -> ({ (QualitativeScoutingEntries.createdAt greaterEq cutoff) or (QualitativeScoutingEntries.isPrescout eq true) })
                    else -> ({
                        (QualitativeScoutingEntries.eventKey inList activeEventKeys) or
                                (QualitativeScoutingEntries.createdAt greaterEq cutoff) or
                                (QualitativeScoutingEntries.isPrescout eq true)
                    })
                },
                version = QualitativeScoutingEntries.updatedAt
            ),
        )
    }

    /** Works out which rows of one table the mirror needs to write and which it should drop. */
    private fun diffTable(spec: MirrorSpec, fullCompare: Boolean, targetDb: Database): TableChanges {
        val table = spec.table
        if (!spec.enabled) {
            val stale = mirrorRead(targetDb) { table.select(table.id).map { it[table.id].value } }
            return TableChanges(spec, emptyList(), stale)
        }

        val version = spec.version
        if (fullCompare || version == null) {
            val source = DatabaseFactory.readTransaction { spec.query(spec.columns).toList() }
            val mirror = mirrorRead(targetDb) { table.select(spec.columns).associateBy { it[table.id].value } }
            val sourceIds = source.mapTo(HashSet(source.size)) { it[table.id].value }
            val upserts = source.filter { row ->
                val existing = mirror[row[table.id].value]
                existing == null || spec.columns.any { normalizeForCompare(row.getOrNull(it)) != normalizeForCompare(existing.getOrNull(it)) }
            }
            return TableChanges(spec, upserts, mirror.keys.filterNot { it in sourceIds })
        }

        val fields = listOf(table.id, version)
        val sourceVersions = DatabaseFactory.readTransaction {
            spec.query(fields).associate { it[table.id].value to normalizeForCompare(it.getOrNull(version)) }
        }
        val mirrorVersions = mirrorRead(targetDb) {
            table.select(fields).associate { it[table.id].value to normalizeForCompare(it.getOrNull(version)) }
        }
        val changedIds = sourceVersions.filter { (id, v) -> !mirrorVersions.containsKey(id) || mirrorVersions[id] != v }.keys.toList()
        val upserts = changedIds.chunked(ID_CHUNK).flatMap { chunk ->
            DatabaseFactory.readTransaction { table.select(spec.columns).where { table.id inList chunk }.toList() }
        }
        return TableChanges(spec, upserts, mirrorVersions.keys.filterNot { it in sourceVersions })
    }

    private fun <T> mirrorRead(db: Database, block: Transaction.() -> T): T = transaction(
        transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
        readOnly = false,
        db = db
    ) { block() }

    @Suppress("UNCHECKED_CAST")
    private fun copyColumns(target: UpdateBuilder<*>, row: ResultRow, columns: List<Column<*>>) {
        for (column in columns) {
            var value = row.getOrNull(column)
            if (value == null && !column.columnType.nullable) {
                // A legacy NULL in a column the schema now requires: use the column default, as the old copy did.
                value = column.defaultValueFun?.invoke() ?: continue
            }
            target[column as Column<Any?>] = value
        }
    }

    /**
     * Puts a value in a form that compares equal whether it came from CockroachDB or the SQLite mirror.
     * CockroachDB keeps microseconds and SQLite keeps milliseconds, so timestamps are compared to the millisecond.
     */
    internal fun normalizeForCompare(value: Any?): Any? = when (value) {
        is EntityID<*> -> value.value
        is Instant -> value.truncatedTo(ChronoUnit.MILLIS)
        else -> value
    }

    private fun isEventInWindow(row: ResultRow, today: LocalDate, minDate: LocalDate, maxDate: LocalDate, cutoff: Instant): Boolean {
        val start = parseDate(row[ApiEvents.startDate])
        val end = parseDate(row[ApiEvents.endDate]) ?: start
        if (start != null || end != null) {
            val effectiveStart = start ?: end!!
            val effectiveEnd = end ?: start!!
            return !effectiveStart.isAfter(maxDate) && !effectiveEnd.isBefore(minDate)
        }
        val year = row[ApiEvents.year]
        return year >= today.year - 1 && year <= today.year + 1 && row[ApiEvents.updatedAt].isAfter(cutoff)
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
                    counts["analyticsReports"] = AnalyticsReports.selectAll().count()
                    counts["reportedErrors"] = ReportedErrors.selectAll().count()
                }
            } catch (_: Exception) {
            } finally {
                if (DatabaseFactory.primaryDatabase != null) {
                    org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
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
                    tableCounts["analytics_reports"] = AnalyticsReports.selectAll().count()
                    tableCounts["reported_errors"] = ReportedErrors.selectAll().count()

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
                    org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.defaultDatabase = DatabaseFactory.primaryDatabase
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
