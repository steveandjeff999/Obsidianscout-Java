package com.obsidianscout.db

import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.EpaOprHistoryCache
import com.obsidianscout.db.PitScoutingConfigs
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingConfigs
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingConfigs
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
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
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            } else {
                maximumPoolSize = 1
                minimumIdle = 1
                isAutoCommit = true
            }
            connectionTimeout = 10_000  // fail fast after 10s instead of the 30s default
            leakDetectionThreshold = 5000L
        }

        val dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource)

        transaction {
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
                ChatMessages
            )
        }
    }

    private fun buildSqliteUrl(config: DatabaseConfig): String {
        val filePath = Paths.get(config.sqlite.file)
        filePath.parent?.let { Files.createDirectories(it) }
        return "jdbc:sqlite:${filePath.toString()}?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL"
    }

    private fun buildPostgresUrl(config: DatabaseConfig): String {
        val base = "jdbc:postgresql://${config.postgres.host}:${config.postgres.port}/${config.postgres.database}"
        return if (config.postgres.ssl) {
            "$base?sslmode=require"
        } else {
            base
        }
    }

    /**
     * Connects to the default "postgres" maintenance database and creates the
     * target database if it does not already exist.  PostgreSQL does not support
     * CREATE DATABASE inside a transaction, so we use autoCommit = true on a
     * plain JDBC connection rather than going through Exposed/HikariCP.
     */
    private fun ensurePostgresDatabaseExists(config: DatabaseConfig) {
        val pg = config.postgres
        val sslSuffix = if (pg.ssl) "?sslmode=require" else ""
        val maintenanceUrl = "jdbc:postgresql://${pg.host}:${pg.port}/postgres$sslSuffix"
        DriverManager.getConnection(maintenanceUrl, pg.user, pg.password).use { conn ->
            conn.autoCommit = true
            // Identifiers in PostgreSQL are case-folded to lower-case unless quoted.
            val dbName = pg.database.lowercase()
            conn.createStatement().use { stmt ->
                val exists = conn
                    .prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
                    .also { it.setString(1, dbName) }
                    .executeQuery()
                    .next()
                if (!exists) {
                    // Database name is validated to be lowercase alphanumeric+underscore
                    // to prevent SQL injection via the config file.
                    require(dbName.matches(Regex("[a-z0-9_]+"))) {
                        "Postgres database name must contain only lowercase letters, digits, and underscores."
                    }
                    stmt.execute("CREATE DATABASE \"$dbName\"")
                    println("Created PostgreSQL database: $dbName")
                }
            }
        }
    }
}
