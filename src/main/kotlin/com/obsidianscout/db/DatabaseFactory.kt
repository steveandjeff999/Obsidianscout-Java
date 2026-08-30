package com.obsidianscout.db

import com.obsidianscout.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.UUID

object DatabaseFactory {
    private val uuidMigratedTables = listOf(
        "users", "scouting_configs", "pit_scouting_configs",
        "qualitative_scouting_configs", "default_configs", "scouting_entries",
        "pit_scouting_entries", "qualitative_scouting_entries",
        "app_settings", "api_events", "api_teams", "api_matches",
        "scouting_alliances", "alliance_memberships",
        "epa_opr_history_cache", "password_reset_tokens",
        "alliance_selections", "banners", "chat_messages",
        "user_chat_last_read", "chat_groups", "push_subscriptions",
        "fcm_config", "fcm_device_tokens"
    )

    @Volatile
    var isReady = false
    @Volatile
    var isCockroach: Boolean = false
    @Volatile
    var orchestrator: com.obsidianscout.db.orchestration.CockroachOrchestrator? = null
    @Volatile
    private var lastQuorumProbeTime: Long = 0L
    internal var activeDataSource: HikariDataSource? = null

    @Volatile
    var lastHealthyQuorumInstant: java.time.Instant? = loadLastHealthyTimestamp()

    @Volatile
    var cachedWorkingAsOfSystemTime: String? = null

    private val lastHealthyTimestampFile: File by lazy {
        try {
            val uri = DatabaseFactory::class.java.protectionDomain.codeSource.location.toURI()
            val jarFile = File(uri)
            val parent = if (jarFile.isFile) jarFile.parentFile else File(".")
            val cockroachDir = File(parent, ".cockroach")
            cockroachDir.mkdirs()
            File(cockroachDir, ".last_healthy_quorum_ts")
        } catch (_: Exception) {
            File(".last_healthy_quorum_ts")
        }
    }

    private val candidateProbeLock = Any()

    fun saveLastHealthyTimestamp(instant: java.time.Instant, force: Boolean = false) {
        if (!force && com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return
        lastHealthyQuorumInstant = instant
        try {
            lastHealthyTimestampFile.writeText(instant.toString())
        } catch (_: Exception) {
            // ignore non-critical write error
        }
    }

    private fun loadLastHealthyTimestamp(): java.time.Instant? {
        return try {
            if (lastHealthyTimestampFile.exists()) {
                val text = lastHealthyTimestampFile.readText().trim()
                if (text.isNotBlank()) java.time.Instant.parse(text) else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun buildAsOfSystemTimeCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        val baseInstant = lastHealthyQuorumInstant ?: java.time.Instant.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS+00").withZone(java.time.ZoneOffset.UTC)

        // 1. Anchored fixed timestamps before quorum was lost (100% safe for local replica Pebble store without contacting leaseholders)
        val offsetsMs = listOf(30_000L, 60_000L, 120_000L, 300_000L, 900_000L, 1_800_000L, 7_200_000L, 86_400_000L)
        for (offset in offsetsMs) {
            val targetInstant = baseInstant.minusMillis(offset)
            val formatted = formatter.format(targetInstant)
            candidates.add("'$formatted'")
        }

        // 2. Relative interval fallbacks (guaranteed to be behind closed timestamps; 100% locally readable from Pebble without RPC)
        candidates.addAll(listOf(
            "'-5m'",
            "'-15m'",
            "'-30m'",
            "'-1h'",
            "'-2m'",
            "'-6h'",
            "'-24h'",
            "'-72h'"
        ))

        // 3. Dynamic bounded staleness follower reads
        candidates.addAll(listOf(
            "with_max_staleness(INTERVAL '10m')",
            "with_max_staleness(INTERVAL '1h')",
            "with_max_staleness(INTERVAL '24h')",
            "follower_read_timestamp()"
        ))

        return candidates.distinct()
    }

    private val isInsideAsOfSystemTimeTx = ThreadLocal.withInitial { false }

    /**
     * Executes a read-only transaction.
     * When CockroachDB is the active engine, automatically catches quorum loss and
     * falls back to follower reads (`SET TRANSACTION AS OF SYSTEM TIME ...`) so that
     * nodes can continue serving reads completely offline during network partitions or quorum loss.
     */
    fun <T> readTransaction(
        db: Database? = null,
        statement: org.jetbrains.exposed.sql.Transaction.() -> T
    ): T {
        val isCrdb = isCockroach && (db == null || !db.url.startsWith("jdbc:sqlite"))
        if (!isCrdb) {
            return transaction(db = db) { statement() }
        }

        val quorumCurrentlyLost = com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost

        if (quorumCurrentlyLost || isInsideAsOfSystemTimeTx.get()) {
            // When quorum is known to be lost, serve immediately via AS OF SYSTEM TIME follower reads without blocking user threads
            return executeAsOfSystemTime(db, statement)
        }

        try {
            val result = transaction(
                transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
                readOnly = true,
                db = db
            ) {
                try { exec("SET LOCAL statement_timeout = '1000ms';") } catch (_: Throwable) {}
                statement()
            }
            saveLastHealthyTimestamp(java.time.Instant.now())
            cachedWorkingAsOfSystemTime = null
            return result
        } catch (e: Throwable) {
            if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLossException(e)) {
                com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost = true
                com.obsidianscout.db.orchestration.CockroachOrchestrator.quorumLossDetails = e.message ?: "Database cluster quorum lost."
                if (lastHealthyQuorumInstant == null) {
                    saveLastHealthyTimestamp(java.time.Instant.now().minusSeconds(60), force = true)
                }
                println("[Database] ⚠️ CockroachDB quorum lost during read (${e.message?.substringBefore("\n")?.take(120)}). Automatically falling back to AS OF SYSTEM TIME reads to retrieve latest available data...")
                return executeAsOfSystemTime(db, statement, rootCause = e)
            }
            throw e
        }
    }

    private fun <T> runInAsOfTransaction(
        db: Database?,
        candidate: String,
        statement: org.jetbrains.exposed.sql.Transaction.() -> T
    ): T {
        return transaction(
            transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE,
            readOnly = true,
            db = db
        ) {
            exec("SET TRANSACTION AS OF SYSTEM TIME $candidate;")
            isInsideAsOfSystemTimeTx.set(true)
            try {
                statement()
            } finally {
                isInsideAsOfSystemTimeTx.set(false)
            }
        }
    }

    /**
     * Executes statement inside an AS OF SYSTEM TIME transaction block, iterating through staleness candidates
     * until the newest valid historical state is read.
     * In CockroachDB, 'SET TRANSACTION AS OF SYSTEM TIME' MUST be the very first statement inside the transaction block.
     */
    fun <T> executeAsOfSystemTime(
        db: Database? = null,
        statement: org.jetbrains.exposed.sql.Transaction.() -> T,
        rootCause: Throwable? = null
    ): T {
        val isCrdb = isCockroach && (db == null || !db.url.startsWith("jdbc:sqlite"))
        if (!isCrdb) {
            return transaction(db = db) { statement() }
        }

        // If we are already running inside an active AS OF SYSTEM TIME transaction block on this thread,
        // reuse the active transaction directly without re-executing 'SET TRANSACTION AS OF SYSTEM TIME'
        if (isInsideAsOfSystemTimeTx.get()) {
            val currentTx = org.jetbrains.exposed.sql.transactions.TransactionManager.currentOrNull()
            if (currentTx != null) {
                return currentTx.statement()
            }
        }

        // 1. Fast path: try previously verified working candidate for this outage window
        val cached = cachedWorkingAsOfSystemTime
        if (cached != null) {
            try {
                return runInAsOfTransaction(db, cached, statement)
            } catch (cachedEx: Throwable) {
                cachedWorkingAsOfSystemTime = null // Invalidate on failure and re-probe candidates
            }
        }

        // 2. Synchronized candidate probe ladder (ensures only 1 thread probes candidates while all other threads await the working candidate)
        synchronized(candidateProbeLock) {
            val doubleChecked = cachedWorkingAsOfSystemTime
            if (doubleChecked != null) {
                try {
                    return runInAsOfTransaction(db, doubleChecked, statement)
                } catch (_: Throwable) {
                    cachedWorkingAsOfSystemTime = null
                }
            }

            val candidates = buildAsOfSystemTimeCandidates()
            var lastException: Throwable? = rootCause

            for (candidate in candidates) {
                try {
                    val result = runInAsOfTransaction(db, candidate, statement)
                    cachedWorkingAsOfSystemTime = candidate
                    println("[Database] ✅ Successfully served read query using CockroachDB AS OF SYSTEM TIME ($candidate).")
                    return result
                } catch (candidateEx: Throwable) {
                    lastException = candidateEx
                    val isQuorumOrTimeErr = com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLossException(candidateEx) ||
                            candidateEx.message?.contains("as of system time", ignoreCase = true) == true ||
                            candidateEx.message?.contains("closed timestamp", ignoreCase = true) == true ||
                            candidateEx.message?.contains("timestamp", ignoreCase = true) == true ||
                            candidateEx.message?.contains("deadline", ignoreCase = true) == true ||
                            candidateEx.message?.contains("timeout", ignoreCase = true) == true ||
                            candidateEx.message?.contains("canceling statement", ignoreCase = true) == true ||
                            candidateEx.message?.contains("query execution canceled", ignoreCase = true) == true
                    if (!isQuorumOrTimeErr) {
                        throw candidateEx
                    }
                }
            }
            throw lastException ?: IllegalStateException("All CockroachDB AS OF SYSTEM TIME fallback candidates exhausted during quorum loss.")
        }
    }

    fun close() {
        try {
            activeDataSource?.close()
            activeDataSource = null
            isReady = false
            println("[Database] Database connection pool closed.")
        } catch (e: Exception) {
            println("[Database] Error closing database pool: ${e.message}")
        }
    }

    fun init(config: DatabaseConfig, runMigration: Boolean = true, isCockroach: Boolean = false) {
        close()
        val engineType = config.type.lowercase()
        val isCockroachEngine = isCockroach || engineType == "cockroach"
        this.isCockroach = isCockroachEngine

        ensureJdbcDriverLoaded(config.type)

        if (engineType == "postgres") {
            ensurePostgresDatabaseExists(config)
        }

        val isPostgresCompatible = engineType == "postgres" || engineType == "cockroach" || engineType == "postgresql"

        val hikariConfig = HikariConfig().apply {
            val jdbcUrl = when {
                isPostgresCompatible -> buildPostgresOrCockroachUrl(config)
                else -> buildSqliteUrl(config)
            }
            this.jdbcUrl = jdbcUrl
            driverClassName = if (isPostgresCompatible) {
                "org.postgresql.Driver"
            } else {
                "org.sqlite.JDBC"
            }
            val isLowMem = System.getenv("LOW_RAM") == "1" || System.getenv("LOW_MEM") == "1"
            if (isPostgresCompatible) {
                // Fixed pool size (minimumIdle == maximumPoolSize) ensures all connections stay perpetually
                // connected to the local CockroachDB daemon and never get closed/recycled during quorum failovers.
                val poolSize = if (isLowMem) 12 else 32
                maximumPoolSize = poolSize
                minimumIdle = poolSize
                idleTimeout = 600_000L
                maxLifetime = 1_800_000L
                isAutoCommit = true
                val (user, pass) = getCredentials(config)
                if (!user.isNullOrBlank()) username = user
                if (!pass.isNullOrBlank()) password = pass
                transactionIsolation = if (isCockroachEngine) "TRANSACTION_SERIALIZABLE" else "TRANSACTION_READ_COMMITTED"
                connectionInitSql = "SET statement_timeout = '1500ms';"
            } else {
                maximumPoolSize = if (isLowMem) 4 else 8
                minimumIdle = 1
                idleTimeout = 30_000L
                maxLifetime = 300_000L
                isAutoCommit = true
            }
            connectionTimeout = 5_000L   // 5s fail-fast timeout preventing thread exhaustion
            leakDetectionThreshold = 60000L // 60s threshold to avoid false connection leak warnings during transient CockroachDB leader elections
            validationTimeout = 1000L
        }

        val dataSource = HikariDataSource(hikariConfig)
        activeDataSource = dataSource

        // Pre-warm the pool to guarantee all connections are physically open and ready before any network/quorum disruption
        try {
            val conns = mutableListOf<java.sql.Connection>()
            val poolTarget = hikariConfig.maximumPoolSize
            for (i in 0 until poolTarget) {
                conns.add(dataSource.connection)
            }
            for (conn in conns) {
                conn.close()
            }
            println("[Database] Pre-warmed Hikari connection pool with $poolTarget connections.")
        } catch (e: Throwable) {
            println("[Database] Note pre-warming pool: ${e.message}")
        }

        Database.connect(dataSource)

        // Run the INT->UUID migration if the database still has the old schema
        if (runMigration) {
            migrateIntToUuidIfNeeded(config, isCockroach)
            dropOldIndicesIfNeeded(dataSource)

            val tables = listOf(
                Users,
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
                EpaOprHistoryCache,
                PasswordResetTokens,
                AllianceSelections,
                Banners,
                ChatMessages,
                UserChatLastRead,
                ChatGroups,
                PushSubscriptions,
                FcmConfigs,
                FcmDeviceTokens,
                ClusterSecrets,
                ClusterNotificationLocks,
                AnalyticsReports,
                UserSessions
            )

            if (isCockroach) {
                println("[Database] Executing raw DDL schema creation for CockroachDB...")
                dataSource.connection.use { conn ->
                    conn.autoCommit = true
                    val existingTables = getExistingTables(conn)
                    conn.createStatement().use { stmt ->
                        // 1. Auto-create any new tables in 'tables' list that do not exist yet
                        for (table in tables) {
                            val tableName = table.tableName.lowercase()
                            if (!existingTables.contains(tableName)) {
                                println("[Database] Table $tableName not found. Auto-creating using Exposed statements...")
                                val statements = transaction { SchemaUtils.createStatements(table) }
                                for (sql in statements) {
                                    try {
                                        stmt.executeUpdate(sql)
                                    } catch (e: Exception) {
                                        println("[Database] Error creating table $tableName with statement ($sql): ${e.message}")
                                    }
                                }
                            }
                        }

                        // 2. Auto-upgrade existing tables: check for missing columns and add them
                        for (table in tables) {
                            val tableName = table.tableName.lowercase()
                            if (existingTables.contains(tableName)) {
                                val dbColumns = mutableSetOf<String>()
                                conn.metaData.getColumns(null, null, tableName, null).use { rs ->
                                    while (rs.next()) {
                                        dbColumns.add(rs.getString("COLUMN_NAME").lowercase())
                                    }
                                }
                                for (column in table.columns) {
                                    val columnName = column.name.lowercase()
                                    if (!dbColumns.contains(columnName)) {
                                        println("[Database] Adding missing column $columnName to table $tableName...")
                                        // Generate column DDL description
                                        val ddlType = when {
                                            column.columnType is org.jetbrains.exposed.sql.UUIDColumnType -> "UUID"
                                            column.columnType is org.jetbrains.exposed.sql.VarCharColumnType -> {
                                                val len = (column.columnType as org.jetbrains.exposed.sql.VarCharColumnType).colLength
                                                "VARCHAR($len)"
                                            }
                                            column.columnType is org.jetbrains.exposed.sql.IntegerColumnType -> "INT"
                                            column.columnType is org.jetbrains.exposed.sql.LongColumnType -> "BIGINT"
                                            column.columnType is org.jetbrains.exposed.sql.DoubleColumnType -> "DOUBLE PRECISION"
                                            column.columnType is org.jetbrains.exposed.sql.TextColumnType -> "TEXT"
                                            column.columnType is org.jetbrains.exposed.sql.BooleanColumnType -> "BOOL"
                                            column.columnType is org.jetbrains.exposed.sql.javatime.JavaInstantColumnType || 
                                            column.columnType is org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType || 
                                            column.columnType is org.jetbrains.exposed.sql.javatime.JavaOffsetDateTimeColumnType -> "TIMESTAMPTZ"
                                            else -> "TEXT"
                                        }
                                        val defaultClause = when {
                                            columnName == "program" -> " DEFAULT 'FRC' NOT NULL"
                                            column.columnType is org.jetbrains.exposed.sql.BooleanColumnType -> " DEFAULT FALSE NOT NULL"
                                            column.columnType.nullable -> " NULL"
                                            else -> " NOT NULL"
                                        }
                                        val sql = "ALTER TABLE $tableName ADD COLUMN $columnName $ddlType$defaultClause"
                                        try {
                                            stmt.executeUpdate(sql)
                                        } catch (e: Exception) {
                                            println("[Database] Error adding column $columnName to $tableName: ${e.message}")
                                        }
                                    }
                                }
                            }
                        }

                        // Drop old constraints and create new constraints/indexes
                        val migrations = listOf(
                            "ALTER TABLE users DROP CONSTRAINT IF EXISTS ux_users_username_team",
                            "ALTER TABLE users ADD CONSTRAINT IF NOT EXISTS ux_users_username_team_program UNIQUE (username, team_number, program)",
                            
                            "ALTER TABLE scouting_configs DROP CONSTRAINT IF EXISTS ux_scouting_configs_team",
                            "ALTER TABLE scouting_configs ADD CONSTRAINT IF NOT EXISTS ux_scouting_configs_team_program UNIQUE (team_number, program)",
                            
                            "ALTER TABLE pit_scouting_configs DROP CONSTRAINT IF EXISTS ux_pit_scouting_configs_team",
                            "ALTER TABLE pit_scouting_configs ADD CONSTRAINT IF NOT EXISTS ux_pit_scouting_configs_team_program UNIQUE (team_number, program)",
                            
                            "ALTER TABLE qualitative_scouting_configs DROP CONSTRAINT IF EXISTS ux_qualitative_scouting_configs_team",
                            "ALTER TABLE qualitative_scouting_configs ADD CONSTRAINT IF NOT EXISTS ux_qualitative_scouting_configs_team_program UNIQUE (team_number, program)",
                            
                            "ALTER TABLE app_settings DROP CONSTRAINT IF EXISTS ux_app_settings_team",
                            "ALTER TABLE app_settings ADD CONSTRAINT IF NOT EXISTS ux_app_settings_team_program UNIQUE (team_number, program)",
                            
                            "ALTER TABLE alliance_memberships DROP CONSTRAINT IF EXISTS ux_alliance_memberships_alliance_team",
                            "ALTER TABLE alliance_memberships ADD CONSTRAINT IF NOT EXISTS ux_alliance_memberships_alliance_team_program UNIQUE (alliance_id, team_number, program)",
                            
                            "DROP INDEX IF EXISTS alliance_memberships@idx_alliance_memberships_team_active",
                            "CREATE INDEX IF NOT EXISTS idx_alliance_memberships_team_active_program ON alliance_memberships (team_number, active, program)"
                        )

                        for (sql in migrations) {
                            try {
                                stmt.executeUpdate(sql)
                            } catch (e: Exception) {
                                println("[Database] Note/Warning running Cockroach schema migration statement: ${e.message}")
                            }
                        }
                    }
                }
            } else {
                transaction {
                    SchemaUtils.createMissingTablesAndColumns(*tables.toTypedArray())
                }
                // Explicit column migrations for PostgreSQL (safe to run repeatedly with IF NOT EXISTS)
                dataSource.connection.use { conn ->
                    conn.autoCommit = true
                    conn.createStatement().use { stmt ->
                        val pgMigrations = listOf(
                            "ALTER TABLE chat_groups ADD COLUMN IF NOT EXISTS allowed_roles TEXT NOT NULL DEFAULT '[]'",
                            "ALTER TABLE chat_groups ADD COLUMN IF NOT EXISTS allowed_user_ids TEXT NOT NULL DEFAULT '[]'"
                        )
                        for (sql in pgMigrations) {
                            try {
                                stmt.executeUpdate(sql)
                                println("[Database] Ran PG migration: $sql")
                            } catch (e: Exception) {
                                println("[Database] Note running PG migration ($sql): ${e.message}")
                            }
                        }
                    }
                }
            }
        }

        isReady = true
    }

    /**
     * Detects whether the database still uses the old INTEGER primary-key schema and
     * migrates all data to UUID primary keys if so.
     *
     * Strategy:
     *   1. Check if the `users` table exists AND its `id` column is numeric (INTEGER/BIGINT/int4/serial…).
     *   2. If yes, rename every affected table to `old_<name>`.
     *   3. Create the new UUID-keyed tables via Exposed.
     *   4. Copy data row-by-row, minting new server-side UUIDs (UUID.randomUUID()) and
     *      maintaining old-id → new-uuid maps to preserve FK relationships.
     *   5. Drop the `old_` tables.
     *
     * UUIDs are always generated by the JVM (UUID.randomUUID()), never by a database
     * function such as gen_random_uuid(), so they are safe for multi-master replication.
     */
    private fun dropOldIndicesIfNeeded(dataSource: HikariDataSource) {
        val indices = listOf(
            "ux_users_username_team",
            "ux_scouting_configs_team",
            "ux_pit_scouting_configs_team",
            "ux_qualitative_scouting_configs_team",
            "ux_app_settings_team",
            "ux_alliance_memberships_alliance_team",
            "idx_alliance_memberships_team_active",
            "idx_chat_messages_team_group",
            "ux_default_configs_name_type"
        )
        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = true
                conn.createStatement().use { stmt ->
                    for (index in indices) {
                        try {
                            stmt.executeUpdate("DROP INDEX IF EXISTS $index")
                        } catch (e: Exception) {
                            // Ignore if not exist or error
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[Database] Warning: failed to drop old indices: ${e.message}")
        }
    }

    private fun migrateIntToUuidIfNeeded(config: DatabaseConfig, isCockroach: Boolean) {
        val type = config.type.lowercase()
        val isPostgres = type == "postgres" || type == "cockroach" || type == "postgresql"
        ensureJdbcDriverLoaded(config.type)
        val jdbcUrl = if (isPostgres) buildPostgresOrCockroachUrl(config) else buildSqliteUrl(config)
        val (user, pass) = if (isPostgres) getCredentials(config) else Pair(null, null)

        val conn = if (user != null) {
            DriverManager.getConnection(jdbcUrl, user, pass)
        } else {
            DriverManager.getConnection(jdbcUrl)
        }

        conn.use { c ->
            c.autoCommit = false
            try {
                // ── 1. Detect old schema ────────────────────────────────────────
                val needsMigration = c.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(
                        if (isPostgres) {
                            """
                            SELECT 1 FROM information_schema.columns
                            WHERE table_name = 'users'
                              AND column_name = 'id'
                              AND (data_type ILIKE '%int%' OR data_type ILIKE 'serial%' OR udt_name ILIKE '%int%')
                            """.trimIndent()
                        } else {
                            // SQLite: check column info; id type is INTEGER
                            "PRAGMA table_info(users)"
                        }
                    )
                    if (isPostgres) {
                        rs.next()
                    } else {
                        var found = false
                        while (rs.next()) {
                            if (rs.getString("name") == "id" && rs.getString("type").contains("INT", ignoreCase = true)) {
                                found = true
                                break
                            }
                        }
                        found
                    }
                }

                val hasInterruptedMigration = tableExists(c, "old_users", isPostgres)

                if (!needsMigration && !hasInterruptedMigration) {
                    if (isPostgres) {
                        dropOldPostgresConstraintsAndIndexes(c, uuidMigratedTables)
                        c.commit()
                    }
                    return  // Already on UUID schema
                }

                if (hasInterruptedMigration) {
                    println("[DB Migration] Found interrupted INT→UUID migration. Rebuilding UUID tables from old_* tables...")
                    dropTargetTablesForMigrationResume(c, uuidMigratedTables, isPostgres)
                    if (isPostgres) {
                        dropOldPostgresConstraintsAndIndexes(c, uuidMigratedTables)
                    }
                    c.commit()
                } else {
                    println("[DB Migration] INT→UUID migration required. Starting migration...")
                }

                // ── 2. Rename old tables ────────────────────────────────────────
                val tables = uuidMigratedTables

                if (!hasInterruptedMigration) {
                    c.createStatement().use { stmt ->
                        for (table in tables) {
                            if (tableExists(c, table, isPostgres)) {
                                println("[DB Migration] Renaming $table -> old_$table")
                                stmt.execute("ALTER TABLE ${quoteIdent(table)} RENAME TO ${quoteIdent("old_$table")}")
                            }
                        }
                    }
                    if (isPostgres) {
                        dropOldPostgresConstraintsAndIndexes(c, tables)
                    }
                    c.commit()
                }

                // ── 3. Create new UUID tables via Exposed ───────────────────────
                transaction {
                    if (isCockroach) {
                        SchemaUtils.create(
                            Users,
                            ScoutingConfigs,
                            PitScoutingConfigs,
                            QualitativeScoutingConfigs,
                            ScoutingEntries,
                            PitScoutingEntries,
                            QualitativeScoutingEntries,
                            AppSettings,
                            ApiEvents,
                            ApiTeams,
                            ApiMatches,
                            ScoutingAlliances,
                            AllianceMemberships,
                            EpaOprHistoryCache,
                            PasswordResetTokens,
                            AllianceSelections,
                            Banners,
                            ChatMessages,
                            UserChatLastRead,
                            PushSubscriptions
                        )
                    } else {
                        SchemaUtils.createMissingTablesAndColumns(
                            Users,
                            ScoutingConfigs,
                            PitScoutingConfigs,
                            QualitativeScoutingConfigs,
                            ScoutingEntries,
                            PitScoutingEntries,
                            QualitativeScoutingEntries,
                            AppSettings,
                            ApiEvents,
                            ApiTeams,
                            ApiMatches,
                            ScoutingAlliances,
                            AllianceMemberships,
                            EpaOprHistoryCache,
                            PasswordResetTokens,
                            AllianceSelections,
                            Banners,
                            ChatMessages,
                            UserChatLastRead,
                            PushSubscriptions
                        )
                    }
                }

                // ── 4. Copy data, minting UUIDs on the server ───────────────────
                //
                //  All UUIDs are generated via UUID.randomUUID() — never gen_random_uuid()
                //  so they are safe for multi-master pgEdge replication.
                //
                //  Approach: build old-int-id → new-UUID maps for tables that have FKs
                //  pointing to them, then use those maps when copying the FK columns.

                // --- users ---
                val userIdMap = mutableMapOf<Int, UUID>()   // old int id -> new UUID
                c.createStatement().executeQuery(
                    "SELECT id, username, team_number, password_hash, role, created_at, email, profile_picture, notification_preference, tour_progress FROM \"old_users\""
                ).use { rs ->
                    while (rs.next()) {
                        val oldId = rs.getInt("id")
                        val newUuid = UUID.randomUUID()
                        userIdMap[oldId] = newUuid
                        c.prepareStatement(
                            "INSERT INTO users (id, username, team_number, password_hash, role, created_at, email, profile_picture, notification_preference, tour_progress) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        ).use { ps ->
                            setUuidParam(ps, 1, newUuid, isPostgres)
                            ps.setString(2, rs.getString("username"))
                            ps.setInt(3, rs.getInt("team_number"))
                            ps.setString(4, rs.getString("password_hash"))
                            ps.setString(5, rs.getString("role"))
                            setTimestampParam(ps, 6, rs.getString("created_at"), isPostgres)
                            ps.setString(7, rs.getString("email"))
                            ps.setString(8, rs.getString("profile_picture"))
                            ps.setString(9, rs.getString("notification_preference") ?: "all")
                            ps.setString(10, rs.getString("tour_progress"))
                            ps.executeUpdate()
                        }
                    }
                }
                c.commit()
                println("[DB Migration] Migrated ${userIdMap.size} users")

                // --- scouting_alliances ---
                val allianceIdMap = mutableMapOf<Int, UUID>()
                tableExistsOld(c, "old_scouting_alliances", isPostgres) {
                    c.createStatement().executeQuery(
                        "SELECT id, name, owner_team_number, event_key, notes, created_at, updated_at, match_config_json, pit_config_json, qualitative_config_json, year, event_code FROM \"old_scouting_alliances\""
                    ).use { rs ->
                        while (rs.next()) {
                            val oldId = rs.getInt("id")
                            val newUuid = UUID.randomUUID()
                            allianceIdMap[oldId] = newUuid
                            c.prepareStatement(
                                "INSERT INTO scouting_alliances (id, name, owner_team_number, event_key, notes, created_at, updated_at, match_config_json, pit_config_json, qualitative_config_json, year, event_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            ).use { ps ->
                                setUuidParam(ps, 1, newUuid, isPostgres)
                                ps.setString(2, rs.getString("name"))
                                ps.setInt(3, rs.getInt("owner_team_number"))
                                ps.setString(4, rs.getString("event_key"))
                                ps.setString(5, rs.getString("notes"))
                                setTimestampParam(ps, 6, rs.getString("created_at"), isPostgres)
                                setTimestampParam(ps, 7, rs.getString("updated_at"), isPostgres)
                                ps.setString(8, rs.getString("match_config_json"))
                                ps.setString(9, rs.getString("pit_config_json"))
                                ps.setString(10, rs.getString("qualitative_config_json"))
                                val year = rs.getObject("year")
                                if (year != null) ps.setInt(11, rs.getInt("year")) else ps.setNull(11, java.sql.Types.INTEGER)
                                ps.setString(12, rs.getString("event_code"))
                                ps.executeUpdate()
                            }
                        }
                    }
                }
                c.commit()
                println("[DB Migration] Migrated ${allianceIdMap.size} alliances")

                // --- scouting_entries ---
                copyScoutingTable(c, "old_scouting_entries", "scouting_entries", userIdMap, isPostgres,
                    extraColumns = listOf("match_key", "match_number", "is_prescout", "has_discrepancy", "conflicting_teams"),
                    insertBlock = { ps, rs, submitterId ->
                        setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                        ps.setInt(2, rs.getInt("owner_team_number"))
                        val ttn = rs.getObject("target_team_number")
                        if (ttn != null) ps.setInt(3, rs.getInt("target_team_number")) else ps.setNull(3, java.sql.Types.INTEGER)
                        ps.setString(4, rs.getString("event_key"))
                        ps.setString(5, rs.getString("match_key"))
                        val mn = rs.getObject("match_number")
                        if (mn != null) ps.setInt(6, rs.getInt("match_number")) else ps.setNull(6, java.sql.Types.INTEGER)
                        ps.setString(7, rs.getString("data_json"))
                        setUuidParam(ps, 8, submitterId, isPostgres)
                        setTimestampParam(ps, 9, rs.getString("created_at"), isPostgres)
                        ps.setBoolean(10, rs.getBoolean("is_prescout"))
                        ps.setBoolean(11, rs.getBoolean("has_discrepancy"))
                        ps.setString(12, rs.getString("conflicting_teams") ?: "")
                    },
                    sql = "INSERT INTO scouting_entries (id, owner_team_number, target_team_number, event_key, match_key, match_number, data_json, submitted_by_user_id, created_at, is_prescout, has_discrepancy, conflicting_teams) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )
                c.commit()

                // --- pit_scouting_entries ---
                copyPitTable(c, "old_pit_scouting_entries", "pit_scouting_entries", userIdMap, isPostgres)
                c.commit()

                // --- qualitative_scouting_entries ---
                copyQualTable(c, "old_qualitative_scouting_entries", "qualitative_scouting_entries", userIdMap, isPostgres)
                c.commit()

                // --- scouting_configs ---
                copyConfigTable(c, "old_scouting_configs", "scouting_configs", isPostgres)
                copyConfigTable(c, "old_pit_scouting_configs", "pit_scouting_configs", isPostgres)
                copyConfigTable(c, "old_qualitative_scouting_configs", "qualitative_scouting_configs", isPostgres)
                c.commit()

                // --- app_settings ---
                tableExistsOld(c, "old_app_settings", isPostgres) {
                    c.createStatement().executeQuery("SELECT team_number, settings_json, updated_at FROM \"old_app_settings\"").use { rs ->
                        while (rs.next()) {
                            c.prepareStatement("INSERT INTO app_settings (id, team_number, settings_json, updated_at) VALUES (?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                ps.setInt(2, rs.getInt("team_number"))
                                ps.setString(3, rs.getString("settings_json"))
                                setTimestampParam(ps, 4, rs.getString("updated_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- api_events ---
                tableExistsOld(c, "old_api_events", isPostgres) {
                    c.createStatement().executeQuery("SELECT event_key, year, event_code, name, start_date, end_date, timezone, data_json, updated_at FROM \"old_api_events\"").use { rs ->
                        while (rs.next()) {
                            c.prepareStatement("INSERT INTO api_events (id, event_key, year, event_code, name, start_date, end_date, timezone, data_json, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                ps.setString(2, rs.getString("event_key"))
                                ps.setInt(3, rs.getInt("year"))
                                ps.setString(4, rs.getString("event_code"))
                                ps.setString(5, rs.getString("name"))
                                ps.setString(6, rs.getString("start_date"))
                                ps.setString(7, rs.getString("end_date"))
                                ps.setString(8, rs.getString("timezone"))
                                ps.setString(9, rs.getString("data_json"))
                                setTimestampParam(ps, 10, rs.getString("updated_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- api_teams ---
                tableExistsOld(c, "old_api_teams", isPostgres) {
                    c.createStatement().executeQuery("SELECT event_key, team_key, team_number, name, nickname, city, state, country, opr, epa, data_json, updated_at FROM \"old_api_teams\"").use { rs ->
                        while (rs.next()) {
                            c.prepareStatement("INSERT INTO api_teams (id, event_key, team_key, team_number, name, nickname, city, state, country, opr, epa, data_json, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                ps.setString(2, rs.getString("event_key"))
                                ps.setString(3, rs.getString("team_key"))
                                ps.setInt(4, rs.getInt("team_number"))
                                ps.setString(5, rs.getString("name"))
                                ps.setString(6, rs.getString("nickname"))
                                ps.setString(7, rs.getString("city"))
                                ps.setString(8, rs.getString("state"))
                                ps.setString(9, rs.getString("country"))
                                val opr = rs.getObject("opr"); if (opr != null) ps.setDouble(10, rs.getDouble("opr")) else ps.setNull(10, java.sql.Types.DOUBLE)
                                val epa = rs.getObject("epa"); if (epa != null) ps.setDouble(11, rs.getDouble("epa")) else ps.setNull(11, java.sql.Types.DOUBLE)
                                ps.setString(12, rs.getString("data_json"))
                                setTimestampParam(ps, 13, rs.getString("updated_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- api_matches ---
                tableExistsOld(c, "old_api_matches", isPostgres) {
                    c.createStatement().executeQuery("SELECT match_key, event_key, comp_level, set_number, match_number, scheduled_time, actual_time, red_teams, blue_teams, data_json, updated_at FROM \"old_api_matches\"").use { rs ->
                        while (rs.next()) {
                            c.prepareStatement("INSERT INTO api_matches (id, match_key, event_key, comp_level, set_number, match_number, scheduled_time, actual_time, red_teams, blue_teams, data_json, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                ps.setString(2, rs.getString("match_key"))
                                ps.setString(3, rs.getString("event_key"))
                                ps.setString(4, rs.getString("comp_level"))
                                val sn = rs.getObject("set_number"); if (sn != null) ps.setInt(5, rs.getInt("set_number")) else ps.setNull(5, java.sql.Types.INTEGER)
                                val mn = rs.getObject("match_number"); if (mn != null) ps.setInt(6, rs.getInt("match_number")) else ps.setNull(6, java.sql.Types.INTEGER)
                                val st = rs.getObject("scheduled_time"); if (st != null) ps.setLong(7, rs.getLong("scheduled_time")) else ps.setNull(7, java.sql.Types.BIGINT)
                                val at = rs.getObject("actual_time"); if (at != null) ps.setLong(8, rs.getLong("actual_time")) else ps.setNull(8, java.sql.Types.BIGINT)
                                ps.setString(9, rs.getString("red_teams"))
                                ps.setString(10, rs.getString("blue_teams"))
                                ps.setString(11, rs.getString("data_json"))
                                setTimestampParam(ps, 12, rs.getString("updated_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- alliance_memberships ---
                tableExistsOld(c, "old_alliance_memberships", isPostgres) {
                    c.createStatement().executeQuery("SELECT alliance_id, team_number, status, invited_at, responded_at, disabled, active FROM \"old_alliance_memberships\"").use { rs ->
                        while (rs.next()) {
                            val oldAllianceId = rs.getInt("alliance_id")
                            val newAllianceUuid = allianceIdMap[oldAllianceId] ?: UUID.randomUUID()
                            c.prepareStatement("INSERT INTO alliance_memberships (id, alliance_id, team_number, status, invited_at, responded_at, disabled, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                setUuidParam(ps, 2, newAllianceUuid, isPostgres)
                                ps.setInt(3, rs.getInt("team_number"))
                                ps.setString(4, rs.getString("status"))
                                setTimestampParam(ps, 5, rs.getString("invited_at"), isPostgres)
                                setTimestampParam(ps, 6, rs.getString("responded_at"), isPostgres, nullable = true)
                                ps.setBoolean(7, rs.getBoolean("disabled"))
                                ps.setBoolean(8, rs.getBoolean("active"))
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- epa_opr_history_cache ---
                tableExistsOld(c, "old_epa_opr_history_cache", isPostgres) {
                    c.createStatement().executeQuery("SELECT event_key, oprs_json, epa_history_json, updated_at FROM \"old_epa_opr_history_cache\"").use { rs ->
                        while (rs.next()) {
                            c.prepareStatement("INSERT INTO epa_opr_history_cache (id, event_key, oprs_json, epa_history_json, updated_at) VALUES (?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                ps.setString(2, rs.getString("event_key"))
                                ps.setString(3, rs.getString("oprs_json"))
                                ps.setString(4, rs.getString("epa_history_json"))
                                setTimestampParam(ps, 5, rs.getString("updated_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- password_reset_tokens ---
                tableExistsOld(c, "old_password_reset_tokens", isPostgres) {
                    c.createStatement().executeQuery("SELECT user_id, email, token, expires_at, used FROM \"old_password_reset_tokens\"").use { rs ->
                        while (rs.next()) {
                            val oldUserId = rs.getObject("user_id")?.let { rs.getInt("user_id") }
                            val newUserUuid = oldUserId?.let { userIdMap[it]?.toString() }
                            c.prepareStatement("INSERT INTO password_reset_tokens (id, user_id, email, token, expires_at, used) VALUES (?, ?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                if (newUserUuid != null) setUuidParam(ps, 2, newUserUuid, isPostgres) else ps.setNull(2, if (isPostgres) java.sql.Types.OTHER else java.sql.Types.VARCHAR)
                                ps.setString(3, rs.getString("email"))
                                ps.setString(4, rs.getString("token"))
                                setTimestampParam(ps, 5, rs.getString("expires_at"), isPostgres)
                                ps.setBoolean(6, rs.getBoolean("used"))
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- alliance_selections ---
                tableExistsOld(c, "old_alliance_selections", isPostgres) {
                    c.createStatement().executeQuery("SELECT owner_key, event_key, selection_json, updated_at FROM \"old_alliance_selections\"").use { rs ->
                        while (rs.next()) {
                            c.prepareStatement("INSERT INTO alliance_selections (id, owner_key, event_key, selection_json, updated_at) VALUES (?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                ps.setString(2, rs.getString("owner_key"))
                                ps.setString(3, rs.getString("event_key"))
                                ps.setString(4, rs.getString("selection_json"))
                                setTimestampParam(ps, 5, rs.getString("updated_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- banners ---
                tableExistsOld(c, "old_banners", isPostgres) {
                    c.createStatement().executeQuery("SELECT team_number, message, banner_type, is_dismissible, is_expandable, expandable_message, is_active, created_at, updated_at FROM \"old_banners\"").use { rs ->
                        while (rs.next()) {
                            c.prepareStatement("INSERT INTO banners (id, team_number, message, banner_type, is_dismissible, is_expandable, expandable_message, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                ps.setInt(2, rs.getInt("team_number"))
                                ps.setString(3, rs.getString("message"))
                                ps.setString(4, rs.getString("banner_type") ?: "info")
                                ps.setBoolean(5, rs.getBoolean("is_dismissible"))
                                ps.setBoolean(6, rs.getBoolean("is_expandable"))
                                ps.setString(7, rs.getString("expandable_message") ?: "")
                                ps.setBoolean(8, rs.getBoolean("is_active"))
                                setTimestampParam(ps, 9, rs.getString("created_at"), isPostgres)
                                setTimestampParam(ps, 10, rs.getString("updated_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- chat_messages ---
                tableExistsOld(c, "old_chat_messages", isPostgres) {
                    c.createStatement().executeQuery("SELECT team_number, group_name, user_id, username, content, created_at, reactions_json FROM \"old_chat_messages\"").use { rs ->
                        while (rs.next()) {
                            val oldUserId = rs.getInt("user_id")
                            val newUserUuid = userIdMap[oldUserId]?.toString() ?: UUID.randomUUID().toString()
                            c.prepareStatement("INSERT INTO chat_messages (id, team_number, group_name, user_id, username, content, created_at, reactions_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                ps.setInt(2, rs.getInt("team_number"))
                                ps.setString(3, rs.getString("group_name"))
                                setUuidParam(ps, 4, newUserUuid, isPostgres)
                                ps.setString(5, rs.getString("username"))
                                ps.setString(6, rs.getString("content"))
                                setTimestampParam(ps, 7, rs.getString("created_at"), isPostgres)
                                ps.setString(8, rs.getString("reactions_json") ?: "{}")
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- user_chat_last_read ---
                tableExistsOld(c, "old_user_chat_last_read", isPostgres) {
                    c.createStatement().executeQuery("SELECT user_id, group_name, last_read_at FROM \"old_user_chat_last_read\"").use query@ { rs ->
                        while (rs.next()) {
                            val oldUserId = rs.getInt("user_id")
                            val newUserUuid = userIdMap[oldUserId]?.toString() ?: return@query
                            c.prepareStatement("INSERT INTO user_chat_last_read (id, user_id, group_name, last_read_at) VALUES (?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                setUuidParam(ps, 2, newUserUuid, isPostgres)
                                ps.setString(3, rs.getString("group_name"))
                                setTimestampParam(ps, 4, rs.getString("last_read_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                // --- push_subscriptions ---
                tableExistsOld(c, "old_push_subscriptions", isPostgres) {
                    c.createStatement().executeQuery("SELECT user_id, endpoint, p256dh, auth, created_at FROM \"old_push_subscriptions\"").use query@ { rs ->
                        while (rs.next()) {
                            val oldUserId = rs.getInt("user_id")
                            val newUserUuid = userIdMap[oldUserId]?.toString() ?: return@query
                            c.prepareStatement("INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at) VALUES (?, ?, ?, ?, ?, ?)").use { ps ->
                                setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                                setUuidParam(ps, 2, newUserUuid, isPostgres)
                                ps.setString(3, rs.getString("endpoint"))
                                ps.setString(4, rs.getString("p256dh"))
                                ps.setString(5, rs.getString("auth"))
                                setTimestampParam(ps, 6, rs.getString("created_at"), isPostgres)
                                ps.executeUpdate()
                            }
                        }
                    }
                }

                c.commit()

                // ── 5. Drop old tables ──────────────────────────────────────────
                c.createStatement().use { stmt ->
                    for (table in tables.reversed()) {
                        try {
                            stmt.execute("DROP TABLE IF EXISTS \"old_$table\"")
                        } catch (_: Exception) {}
                    }
                }
                c.commit()

                println("[DB Migration] INT→UUID migration complete!")

            } catch (e: Exception) {
                c.rollback()
                println("[DB Migration] ERROR during migration: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun quoteIdent(identifier: String): String {
        return "\"" + identifier.replace("\"", "\"\"") + "\""
    }

    private fun setUuidParam(ps: java.sql.PreparedStatement, index: Int, uuid: UUID, isPostgres: Boolean) {
        if (isPostgres) {
            ps.setObject(index, uuid)
        } else {
            ps.setString(index, uuid.toString())
        }
    }

    private fun setUuidParam(ps: java.sql.PreparedStatement, index: Int, uuidText: String, isPostgres: Boolean) {
        if (isPostgres) {
            ps.setObject(index, UUID.fromString(uuidText))
        } else {
            ps.setString(index, uuidText)
        }
    }

    private fun setTimestampParam(
        ps: java.sql.PreparedStatement,
        index: Int,
        value: String?,
        isPostgres: Boolean,
        nullable: Boolean = false
    ) {
        val text = value?.trim()?.takeIf { it.isNotEmpty() }
        if (text == null && nullable) {
            ps.setNull(index, if (isPostgres) java.sql.Types.TIMESTAMP else java.sql.Types.VARCHAR)
            return
        }
        val fallback = java.time.Instant.now().toString()
        if (!isPostgres) {
            ps.setString(index, text ?: fallback)
            return
        }

        val timestamp = parseTimestamp(text ?: fallback)
            ?: java.sql.Timestamp.from(java.time.Instant.now())
        ps.setTimestamp(index, timestamp)
    }

    private fun parseTimestamp(value: String): java.sql.Timestamp? {
        val trimmed = value.trim()
        runCatching {
            return java.sql.Timestamp.from(java.time.Instant.parse(trimmed))
        }
        runCatching {
            return java.sql.Timestamp.valueOf(trimmed.replace('T', ' ').removeSuffix("Z"))
        }
        runCatching {
            val normalized = trimmed.replace(' ', 'T').removeSuffix("Z")
            return java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(normalized))
        }
        return null
    }
    private fun getExistingTables(conn: java.sql.Connection): Set<String> {
        val tables = mutableSetOf<String>()
        try {
            conn.prepareStatement(
                """
                SELECT c.relname FROM pg_class c 
                JOIN pg_namespace n ON n.oid = c.relnamespace 
                WHERE (n.nspname = current_schema() OR n.nspname = 'public') 
                  AND c.relkind = 'r'
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        tables.add(rs.getString(1).lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return tables
    }

    private fun tableExists(conn: java.sql.Connection, tableName: String, isPostgres: Boolean): Boolean {
        return if (isPostgres) {
            try {
                conn.prepareStatement(
                    """
                    SELECT 1 FROM pg_class c 
                    JOIN pg_namespace n ON n.oid = c.relnamespace 
                    WHERE (n.nspname = current_schema() OR n.nspname = 'public') 
                      AND c.relname = ? 
                      AND c.relkind = 'r'
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, tableName.lowercase())
                    ps.executeQuery().use { it.next() }
                }
            } catch (e: Exception) {
                false
            }
        } else {
            try {
                conn.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"
                ).use { ps ->
                    ps.setString(1, tableName)
                    ps.executeQuery().use { it.next() }
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun dropTargetTablesForMigrationResume(conn: java.sql.Connection, tables: List<String>, isPostgres: Boolean) {
        conn.createStatement().use { stmt ->
            for (table in tables.reversed()) {
                if (tableExists(conn, table, isPostgres)) {
                    val cascade = if (isPostgres) " CASCADE" else ""
                    stmt.execute("DROP TABLE IF EXISTS ${quoteIdent(table)}$cascade")
                }
            }
        }
    }

    private fun dropOldPostgresConstraintsAndIndexes(conn: java.sql.Connection, tables: List<String>) {
        for (table in tables) {
            val oldTable = "old_$table"
            if (!tableExists(conn, oldTable, isPostgres = true)) continue

            val constraints = mutableListOf<String>()
            conn.prepareStatement(
                """
                SELECT conname
                FROM pg_constraint
                WHERE conrelid = to_regclass(?)
                  AND contype <> 'n'
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, oldTable)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        constraints.add(rs.getString("conname"))
                    }
                }
            }

            conn.createStatement().use { stmt ->
                for (constraint in constraints) {
                    stmt.execute(
                        "ALTER TABLE ${quoteIdent(oldTable)} DROP CONSTRAINT IF EXISTS ${quoteIdent(constraint)} CASCADE"
                    )
                }
            }

            val indexes = mutableListOf<String>()
            conn.prepareStatement(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = ?
                  AND indexname NOT IN (
                      SELECT c.relname
                      FROM pg_constraint con
                      JOIN pg_class c ON c.oid = con.conindid
                      WHERE con.conrelid = to_regclass(?)
                  )
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, oldTable)
                ps.setString(2, oldTable)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        indexes.add(rs.getString("indexname"))
                    }
                }
            }

            conn.createStatement().use { stmt ->
                for (index in indexes) {
                    stmt.execute("DROP INDEX IF EXISTS ${quoteIdent(index)}")
                }
            }
        }
    }

    private fun tableExistsOld(conn: java.sql.Connection, tableName: String, isPostgres: Boolean, block: () -> Unit) {
        if (tableExists(conn, tableName, isPostgres)) block()
    }

    private fun copyScoutingTable(
        c: java.sql.Connection,
        srcTable: String,
        dstTable: String,
        userIdMap: Map<Int, UUID>,
        isPostgres: Boolean,
        extraColumns: List<String>,
        insertBlock: (java.sql.PreparedStatement, java.sql.ResultSet, String) -> Unit,
        sql: String
    ) {
        tableExistsOld(c, srcTable, isPostgres) {
            try {
                c.createStatement().executeQuery("SELECT * FROM \"$srcTable\"").use query@ { rs ->
                    var count = 0
                    while (rs.next()) {
                        val oldUserId = rs.getInt("submitted_by_user_id")
                        val newUserUuid = userIdMap[oldUserId]?.toString() ?: UUID.randomUUID().toString()
                        c.prepareStatement(sql).use { ps ->
                            insertBlock(ps, rs, newUserUuid)
                            ps.executeUpdate()
                        }
                        count++
                    }
                    println("[DB Migration] Migrated $count rows from $srcTable into $dstTable")
                    if (extraColumns.isNotEmpty()) {
                        println("[DB Migration] Extra columns preserved for $dstTable: ${extraColumns.joinToString(", ")}")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun copyPitTable(c: java.sql.Connection, srcTable: String, dstTable: String, userIdMap: Map<Int, UUID>, isPostgres: Boolean) {
        try {
            tableExistsOld(c, srcTable, isPostgres) {
                c.createStatement().executeQuery("SELECT * FROM \"$srcTable\"").use { rs ->
                    var count = 0
                    while (rs.next()) {
                        val oldUserId = rs.getInt("submitted_by_user_id")
                        val newUserUuid = userIdMap[oldUserId]?.toString() ?: UUID.randomUUID().toString()
                        c.prepareStatement("INSERT INTO $dstTable (id, owner_team_number, target_team_number, event_key, data_json, submitted_by_user_id, created_at, is_prescout, has_discrepancy, conflicting_teams) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
                            setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                            ps.setInt(2, rs.getInt("owner_team_number"))
                            val ttn = rs.getObject("target_team_number")
                            if (ttn != null) ps.setInt(3, rs.getInt("target_team_number")) else ps.setNull(3, java.sql.Types.INTEGER)
                            ps.setString(4, rs.getString("event_key"))
                            ps.setString(5, rs.getString("data_json"))
                            setUuidParam(ps, 6, newUserUuid, isPostgres)
                            setTimestampParam(ps, 7, rs.getString("created_at"), isPostgres)
                            ps.setBoolean(8, rs.getBoolean("is_prescout"))
                            ps.setBoolean(9, rs.getBoolean("has_discrepancy"))
                            ps.setString(10, rs.getString("conflicting_teams") ?: "")
                            ps.executeUpdate()
                        }
                        count++
                    }
                    println("[DB Migration] Migrated $count rows from $srcTable")
                }
            }
        } catch (_: Exception) {}
    }

    private fun copyQualTable(c: java.sql.Connection, srcTable: String, dstTable: String, userIdMap: Map<Int, UUID>, isPostgres: Boolean) {
        try {
            tableExistsOld(c, srcTable, isPostgres) {
                c.createStatement().executeQuery("SELECT * FROM \"$srcTable\"").use { rs ->
                    var count = 0
                    while (rs.next()) {
                        val oldUserId = rs.getInt("submitted_by_user_id")
                        val newUserUuid = userIdMap[oldUserId]?.toString() ?: UUID.randomUUID().toString()
                        c.prepareStatement("INSERT INTO $dstTable (id, owner_team_number, target_team_number, event_key, match_key, match_number, data_json, submitted_by_user_id, created_at, is_prescout, has_discrepancy, conflicting_teams) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
                            setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                            ps.setInt(2, rs.getInt("owner_team_number"))
                            val ttn = rs.getObject("target_team_number")
                            if (ttn != null) ps.setInt(3, rs.getInt("target_team_number")) else ps.setNull(3, java.sql.Types.INTEGER)
                            ps.setString(4, rs.getString("event_key"))
                            ps.setString(5, rs.getString("match_key"))
                            val mn = rs.getObject("match_number")
                            if (mn != null) ps.setInt(6, rs.getInt("match_number")) else ps.setNull(6, java.sql.Types.INTEGER)
                            ps.setString(7, rs.getString("data_json"))
                            setUuidParam(ps, 8, newUserUuid, isPostgres)
                            setTimestampParam(ps, 9, rs.getString("created_at"), isPostgres)
                            ps.setBoolean(10, rs.getBoolean("is_prescout"))
                            ps.setBoolean(11, rs.getBoolean("has_discrepancy"))
                            ps.setString(12, rs.getString("conflicting_teams") ?: "")
                            ps.executeUpdate()
                        }
                        count++
                    }
                    println("[DB Migration] Migrated $count rows from $srcTable")
                }
            }
        } catch (_: Exception) {}
    }

    private fun copyConfigTable(c: java.sql.Connection, srcTable: String, dstTable: String, isPostgres: Boolean) {
        try {
            tableExistsOld(c, srcTable, isPostgres) {
                c.createStatement().executeQuery("SELECT team_number, config_json, updated_at FROM \"$srcTable\"").use { rs ->
                    while (rs.next()) {
                        c.prepareStatement("INSERT INTO $dstTable (id, team_number, config_json, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT (team_number) DO NOTHING").use { ps ->
                            setUuidParam(ps, 1, UUID.randomUUID(), isPostgres)
                            ps.setInt(2, rs.getInt("team_number"))
                            ps.setString(3, rs.getString("config_json"))
                            setTimestampParam(ps, 4, rs.getString("updated_at"), isPostgres)
                            ps.executeUpdate()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun getCredentials(config: DatabaseConfig): Pair<String?, String?> {
        val type = config.type.lowercase()
        return when (type) {
            "cockroach" -> {
                val user = if (config.cockroach.user.isNotBlank()) config.cockroach.user else config.postgres.user
                val pass = if (config.cockroach.password.isNotBlank()) config.cockroach.password else config.postgres.password
                Pair(user, pass)
            }
            else -> Pair(config.postgres.user, config.postgres.password)
        }
    }

    private fun buildSqliteUrl(config: DatabaseConfig): String {
        val filePath = Paths.get(config.sqlite.file)
        filePath.parent?.let { Files.createDirectories(it) }
        return "jdbc:sqlite:${filePath.toString()}?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL"
    }

    private fun buildPostgresOrCockroachUrl(config: DatabaseConfig): String {
        // 1. Direct custom URL takes priority if provided
        val explicitUrl = when {
            config.url.isNotBlank() -> config.url.trim()
            config.type.lowercase() == "cockroach" && config.cockroach.url.isNotBlank() -> config.cockroach.url.trim()
            config.postgres.url.isNotBlank() -> config.postgres.url.trim()
            else -> ""
        }
        if (explicitUrl.isNotBlank()) {
            val normalized = if (explicitUrl.startsWith("jdbc:", ignoreCase = true)) {
                explicitUrl
            } else if (explicitUrl.startsWith("postgresql://", ignoreCase = true) || explicitUrl.startsWith("postgres://", ignoreCase = true)) {
                "jdbc:$explicitUrl"
            } else {
                explicitUrl
            }
            return normalized
        }

        // 2. Build URL from component fields
        val isCockroach = config.type.lowercase() == "cockroach"
        val host = if (isCockroach && config.cockroach.host.isNotBlank() && config.cockroach.host != "localhost") {
            config.cockroach.host
        } else if (isCockroach && config.postgres.host.isNotBlank() && config.postgres.host != "localhost") {
            config.postgres.host
        } else if (isCockroach) {
            config.cockroach.host
        } else {
            config.postgres.host
        }

        val port = if (isCockroach) {
            if (config.cockroach.port != 26257 && config.cockroach.port != 0) config.cockroach.port
            else if (config.postgres.port != 5432 && config.postgres.port != 0) config.postgres.port
            else config.cockroach.port
        } else {
            config.postgres.port
        }

        val database = if (isCockroach && config.cockroach.database.isNotBlank()) config.cockroach.database else config.postgres.database
        val ssl = if (isCockroach) config.cockroach.ssl || config.postgres.ssl else config.postgres.ssl

        val hostPart = if (host.contains(",") || host.contains(":")) host else "$host:$port"
        val base = "jdbc:postgresql://$hostPart/$database"
        val sslMode = if (ssl) "sslmode=require" else "sslmode=disable"
        return "$base?$sslMode&reWriteBatchedInserts=true&connectTimeout=10&socketTimeout=60&tcpKeepAlive=true"
    }

    private fun buildPostgresUrl(config: DatabaseConfig): String = buildPostgresOrCockroachUrl(config)

    /**
     * Connects to the default "postgres" maintenance database and creates the
     * target database if it does not already exist.  PostgreSQL does not support
     * CREATE DATABASE inside a transaction, so we use autoCommit = true on a
     * plain JDBC connection rather than going through Exposed/HikariCP.
     */
    private fun ensurePostgresDatabaseExists(config: DatabaseConfig) {
        val pg = config.postgres
        ensureJdbcDriverLoaded("postgres")
        // Identifiers in PostgreSQL are case-folded to lower-case unless quoted.
        val dbName = pg.database.lowercase()
        
        // Database name is validated to be lowercase alphanumeric+underscore
        // to prevent SQL injection or connection issues.
        require(dbName.matches(Regex("[a-z0-9_]+"))) {
            "Postgres database name must contain only lowercase letters, digits, and underscores."
        }

        val hostPart = if (pg.host.contains(",") || pg.host.contains(":")) pg.host else "${pg.host}:${pg.port}"
        val sslSuffix = if (pg.ssl) "?sslmode=require" else "?sslmode=disable"
        val maintenanceUrl = "jdbc:postgresql://$hostPart/postgres$sslSuffix"
        var retries = 5
        var lastException: Exception? = null
        while (retries > 0) {
            try {
                DriverManager.getConnection(maintenanceUrl, pg.user, pg.password).use { conn ->
                    conn.autoCommit = true
                    conn.createStatement().use { stmt ->
                        val exists = conn
                            .prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
                            .also { it.setString(1, dbName) }
                            .executeQuery()
                            .next()
                        if (!exists) {
                            stmt.execute("CREATE DATABASE \"$dbName\"")
                            println("Created PostgreSQL database: $dbName")
                        }
                    }
                }
                return // successfully connected and checked database
            } catch (e: Exception) {
                lastException = e
                retries--
                if (retries > 0) {
                    println("[Database] Connecting to database failed, retrying in 3 seconds ($retries attempts left): ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }
        throw IllegalStateException("Failed to connect to database after 5 attempts. Last error: ${lastException?.message}", lastException)
    }

    private fun ensureJdbcDriverLoaded(type: String) {
        when (type.lowercase()) {
            "postgres", "cockroach", "postgresql" -> Class.forName("org.postgresql.Driver")
            else -> Class.forName("org.sqlite.JDBC")
        }
    }

    private val cockroachDdlList = listOf(
        """
        CREATE TABLE IF NOT EXISTS users (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            username VARCHAR(64) NOT NULL,
            team_number INT NOT NULL,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            password_hash VARCHAR(255) NOT NULL,
            role VARCHAR(16) NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            email VARCHAR(255) NULL,
            profile_picture TEXT NULL,
            notification_preference VARCHAR(16) NOT NULL DEFAULT 'all',
            tour_progress TEXT NULL,
            CONSTRAINT ux_users_username_team_program UNIQUE (username, team_number, program)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS scouting_configs (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL DEFAULT 0,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            config_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_scouting_configs_team_program UNIQUE (team_number, program)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS pit_scouting_configs (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL DEFAULT 0,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            config_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_pit_scouting_configs_team_program UNIQUE (team_number, program)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS qualitative_scouting_configs (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL DEFAULT 0,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            config_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_qualitative_scouting_configs_team_program UNIQUE (team_number, program)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS scouting_entries (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_team_number INT NOT NULL,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            target_team_number INT NULL,
            event_key VARCHAR(64) NULL,
            match_key VARCHAR(64) NULL,
            match_number INT NULL,
            data_json TEXT NOT NULL,
            submitted_by_user_id UUID NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            is_prescout BOOL NOT NULL DEFAULT FALSE,
            has_discrepancy BOOL NOT NULL DEFAULT FALSE,
            conflicting_teams VARCHAR(255) NOT NULL DEFAULT ''
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS pit_scouting_entries (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_team_number INT NOT NULL,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            target_team_number INT NULL,
            event_key VARCHAR(64) NULL,
            data_json TEXT NOT NULL,
            submitted_by_user_id UUID NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            is_prescout BOOL NOT NULL DEFAULT FALSE,
            has_discrepancy BOOL NOT NULL DEFAULT FALSE,
            conflicting_teams VARCHAR(255) NOT NULL DEFAULT ''
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS qualitative_scouting_entries (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_team_number INT NOT NULL,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            target_team_number INT NULL,
            event_key VARCHAR(64) NULL,
            match_key VARCHAR(64) NULL,
            match_number INT NULL,
            data_json TEXT NOT NULL,
            submitted_by_user_id UUID NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            is_prescout BOOL NOT NULL DEFAULT FALSE,
            has_discrepancy BOOL NOT NULL DEFAULT FALSE,
            conflicting_teams VARCHAR(255) NOT NULL DEFAULT ''
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS app_settings (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL DEFAULT 0,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            settings_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_app_settings_team_program UNIQUE (team_number, program)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS api_events (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            event_key VARCHAR(64) NOT NULL,
            year INT NOT NULL,
            event_code VARCHAR(32) NULL,
            name VARCHAR(512) NOT NULL,
            start_date VARCHAR(32) NULL,
            end_date VARCHAR(32) NULL,
            timezone VARCHAR(64) NULL,
            data_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_api_events_key UNIQUE (event_key)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS api_teams (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            event_key VARCHAR(64) NOT NULL,
            team_key VARCHAR(32) NOT NULL,
            team_number INT NOT NULL,
            name VARCHAR(512) NULL,
            nickname VARCHAR(512) NULL,
            city VARCHAR(80) NULL,
            state VARCHAR(80) NULL,
            country VARCHAR(80) NULL,
            opr DOUBLE PRECISION NULL,
            epa DOUBLE PRECISION NULL,
            data_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_api_teams_event_team UNIQUE (event_key, team_key)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS api_matches (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            match_key VARCHAR(64) NOT NULL,
            event_key VARCHAR(64) NOT NULL,
            comp_level VARCHAR(16) NOT NULL,
            set_number INT NULL,
            match_number INT NULL,
            scheduled_time BIGINT NULL,
            actual_time BIGINT NULL,
            red_teams TEXT NOT NULL,
            blue_teams TEXT NOT NULL,
            data_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_api_matches_key UNIQUE (match_key)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS scouting_alliances (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            name VARCHAR(128) NOT NULL,
            owner_team_number INT NOT NULL,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            event_key VARCHAR(64) NULL,
            notes TEXT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            match_config_json TEXT NULL,
            pit_config_json TEXT NULL,
            qualitative_config_json TEXT NULL,
            year INT NULL,
            event_code VARCHAR(32) NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS alliance_memberships (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            alliance_id UUID NOT NULL,
            team_number INT NOT NULL,
            program VARCHAR(8) NOT NULL DEFAULT 'FRC',
            status VARCHAR(16) NOT NULL,
            invited_at TIMESTAMPTZ NOT NULL,
            responded_at TIMESTAMPTZ NULL,
            disabled BOOL NOT NULL DEFAULT FALSE,
            active BOOL NOT NULL DEFAULT FALSE,
            CONSTRAINT ux_alliance_memberships_alliance_team_program UNIQUE (alliance_id, team_number, program),
            INDEX idx_alliance_memberships_team_active_program (team_number, active, program)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS epa_opr_history_cache (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            event_key VARCHAR(64) NOT NULL,
            oprs_json TEXT NOT NULL,
            epa_history_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_epa_opr_history_cache_event UNIQUE (event_key)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS password_reset_tokens (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID NULL,
            email VARCHAR(255) NULL,
            token VARCHAR(128) NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            used BOOL NOT NULL DEFAULT FALSE,
            CONSTRAINT ux_password_reset_tokens_token UNIQUE (token)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS alliance_selections (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_key VARCHAR(64) NOT NULL,
            event_key VARCHAR(64) NOT NULL,
            selection_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_alliance_selections_owner_event UNIQUE (owner_key, event_key)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS banners (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL DEFAULT 0,
            message TEXT NOT NULL,
            banner_type VARCHAR(32) NOT NULL DEFAULT 'info',
            is_dismissible BOOL NOT NULL DEFAULT TRUE,
            is_expandable BOOL NOT NULL DEFAULT FALSE,
            expandable_message TEXT NOT NULL DEFAULT '',
            is_active BOOL NOT NULL DEFAULT TRUE,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS chat_messages (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL,
            group_name VARCHAR(64) NOT NULL,
            user_id UUID NOT NULL,
            username VARCHAR(64) NOT NULL,
            content TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            reactions_json TEXT NOT NULL DEFAULT '{}',
            INDEX idx_chat_messages_team_group (team_number, group_name)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS user_chat_last_read (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID NOT NULL,
            group_name VARCHAR(64) NOT NULL,
            last_read_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_user_chat_last_read_user_group UNIQUE (user_id, group_name)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS push_subscriptions (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID NOT NULL,
            endpoint TEXT NOT NULL,
            p256dh VARCHAR(255) NOT NULL,
            auth VARCHAR(255) NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_push_subscriptions_endpoint UNIQUE (endpoint)
        )
        """.trimIndent()
    )
}

/**
 * Top-level convenience delegate for DatabaseFactory.readTransaction.
 */
fun <T> readTransaction(
    db: org.jetbrains.exposed.sql.Database? = null,
    statement: org.jetbrains.exposed.sql.Transaction.() -> T
): T = DatabaseFactory.readTransaction(db, statement)

