package com.obsidianscout.db

import com.obsidianscout.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.UUID

object DatabaseFactory {
    private val uuidMigratedTables = listOf(
        "users", "scouting_configs", "pit_scouting_configs",
        "qualitative_scouting_configs", "scouting_entries",
        "pit_scouting_entries", "qualitative_scouting_entries",
        "app_settings", "api_events", "api_teams", "api_matches",
        "scouting_alliances", "alliance_memberships",
        "epa_opr_history_cache", "password_reset_tokens",
        "alliance_selections", "banners", "chat_messages",
        "user_chat_last_read", "push_subscriptions"
    )

    @Volatile
    var isReady = false
    @Volatile
    var orchestrator: com.obsidianscout.db.orchestration.CockroachOrchestrator? = null
    private var activeDataSource: HikariDataSource? = null

    fun close() {
        try {
            activeDataSource?.close()
        } catch (e: Exception) {
            // ignore
        }
        activeDataSource = null
        isReady = false
    }

    fun init(config: DatabaseConfig, runMigration: Boolean = true, isCockroach: Boolean = false) {
        close()

        ensureJdbcDriverLoaded(config.type)

        if (config.type.lowercase() == "postgres") {
            ensurePostgresDatabaseExists(config)
        }

        val hikariConfig = HikariConfig().apply {
            val type = config.type.lowercase()
            val jdbcUrl = when (type) {
                "postgres" -> buildPostgresUrl(config)
                else -> buildSqliteUrl(config)
            }
            this.jdbcUrl = jdbcUrl
            driverClassName = if (type == "postgres") {
                "org.postgresql.Driver"
            } else {
                "org.sqlite.JDBC"
            }
            if (type == "postgres") {
                maximumPoolSize = 20
                minimumIdle = 2
                isAutoCommit = true
                username = config.postgres.user
                password = config.postgres.password
                transactionIsolation = if (isCockroach) "TRANSACTION_SERIALIZABLE" else "TRANSACTION_READ_COMMITTED"
            } else {
                maximumPoolSize = 16
                minimumIdle = 2
                isAutoCommit = true
            }
            connectionTimeout = 10_000  // fail fast after 10s instead of the 30s default
            leakDetectionThreshold = 60000L
        }

        val dataSource = HikariDataSource(hikariConfig)
        activeDataSource = dataSource
        Database.connect(dataSource)

        // Run the INT->UUID migration if the database still has the old schema
        if (runMigration) {
            migrateIntToUuidIfNeeded(config, isCockroach)

            val tables = listOf(
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

            if (isCockroach) {
                println("[Database] Executing raw DDL schema creation for CockroachDB...")
                dataSource.connection.use { conn ->
                    conn.autoCommit = true
                    conn.createStatement().use { stmt ->
                        for (ddl in cockroachDdlList) {
                            val tableNameExtract = ddl.trim().substringAfter("CREATE TABLE IF NOT EXISTS ").substringBefore(" (").trim()
                            if (tableNameExtract.isNotEmpty() && !ddl.trim().startsWith("CREATE INDEX") && ddl.contains("CREATE TABLE")) {
                                println("[Database] Ensuring table $tableNameExtract...")
                                try {
                                    stmt.executeUpdate(ddl)
                                } catch (e: Exception) {
                                    println("[Database] Warning/Error executing DDL statement: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } else {
                transaction {
                    SchemaUtils.createMissingTablesAndColumns(*tables.toTypedArray())
                }
            }
        } else {
            // For followers: verify that the database schema is already initialized by the leader.
            // Loop and wait for the tables to be created by the leader instead of failing and restarting the pool.
            val isPostgres = config.type.lowercase() == "postgres"
            var schemaReady = false
            val start = System.currentTimeMillis()
            val maxWaitMs = 600_000 // Wait up to 10 minutes for CockroachDB cluster DDLs
            
            while (System.currentTimeMillis() - start < maxWaitMs) {
                schemaReady = try {
                    val orch = orchestrator
                    if (orch != null) {
                        orch.isLeaderSchemaReady()
                    } else {
                        dataSource!!.connection.use { conn ->
                            conn.autoCommit = true
                            val existing = getExistingTables(conn)
                            existing.contains("users") && existing.contains("alliance_memberships")
                        }
                    }
                } catch (e: Exception) {
                    false
                }
                
                if (schemaReady) break
                println("[Database] Database schema is not initialized yet. Waiting for leader to complete migrations (elapsed: ${(System.currentTimeMillis() - start) / 1000}s)...")
                Thread.sleep(5000)
            }
            
            if (!schemaReady) {
                throw IllegalStateException("Database schema initialization by leader timed out after 600s.")
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
    private fun migrateIntToUuidIfNeeded(config: DatabaseConfig, isCockroach: Boolean) {
        val isPostgres = config.type.lowercase() == "postgres"
        ensureJdbcDriverLoaded(config.type)
        val jdbcUrl = if (isPostgres) buildPostgresUrl(config) else buildSqliteUrl(config)
        val user = if (isPostgres) config.postgres.user else null
        val pass = if (isPostgres) config.postgres.password else null

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

    private fun buildSqliteUrl(config: DatabaseConfig): String {
        val filePath = Paths.get(config.sqlite.file)
        filePath.parent?.let { Files.createDirectories(it) }
        return "jdbc:sqlite:${filePath.toString()}?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL"
    }

    private fun buildPostgresUrl(config: DatabaseConfig): String {
        val pg = config.postgres
        val hostPart = if (pg.host.contains(",") || pg.host.contains(":")) pg.host else "${pg.host}:${pg.port}"
        val base = "jdbc:postgresql://$hostPart/${pg.database}"
        val ssl = if (pg.ssl) "sslmode=require" else "sslmode=disable"
        return "$base?$ssl&reWriteBatchedInserts=true"
    }

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
            "postgres" -> Class.forName("org.postgresql.Driver")
            else -> Class.forName("org.sqlite.JDBC")
        }
    }

    private val cockroachDdlList = listOf(
        """
        CREATE TABLE IF NOT EXISTS users (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            username VARCHAR(64) NOT NULL,
            team_number INT NOT NULL,
            password_hash VARCHAR(255) NOT NULL,
            role VARCHAR(16) NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            email VARCHAR(255) NULL,
            profile_picture TEXT NULL,
            notification_preference VARCHAR(16) NOT NULL DEFAULT 'all',
            tour_progress TEXT NULL,
            CONSTRAINT ux_users_username_team UNIQUE (username, team_number)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS scouting_configs (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL DEFAULT 0,
            config_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_scouting_configs_team UNIQUE (team_number)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS pit_scouting_configs (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL DEFAULT 0,
            config_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_pit_scouting_configs_team UNIQUE (team_number)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS qualitative_scouting_configs (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            team_number INT NOT NULL DEFAULT 0,
            config_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_qualitative_scouting_configs_team UNIQUE (team_number)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS scouting_entries (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            owner_team_number INT NOT NULL,
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
            settings_json TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            CONSTRAINT ux_app_settings_team UNIQUE (team_number)
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
            status VARCHAR(16) NOT NULL,
            invited_at TIMESTAMPTZ NOT NULL,
            responded_at TIMESTAMPTZ NULL,
            disabled BOOL NOT NULL DEFAULT FALSE,
            active BOOL NOT NULL DEFAULT FALSE,
            CONSTRAINT ux_alliance_memberships_alliance_team UNIQUE (alliance_id, team_number),
            INDEX idx_alliance_memberships_team_active (team_number, active)
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
