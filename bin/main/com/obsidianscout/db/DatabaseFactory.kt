package com.obsidianscout.db

import com.obsidianscout.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Paths

object DatabaseFactory {
    fun init(config: DatabaseConfig) {
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
            maximumPoolSize = 10
            isAutoCommit = false
            if (type == "postgres") {
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            }
        }

        val dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                ScoutingConfigs,
                ScoutingEntries,
                AppSettings,
                ApiEvents,
                ApiTeams,
                ApiMatches
            )
        }
    }

    private fun buildSqliteUrl(config: DatabaseConfig): String {
        val filePath = Paths.get(config.sqlite.file)
        filePath.parent?.let { Files.createDirectories(it) }
        return "jdbc:sqlite:${filePath.toString()}"
    }

    private fun buildPostgresUrl(config: DatabaseConfig): String {
        val base = "jdbc:postgresql://${config.postgres.host}:${config.postgres.port}/${config.postgres.database}"
        return if (config.postgres.ssl) {
            "$base?sslmode=require"
        } else {
            base
        }
    }
}
