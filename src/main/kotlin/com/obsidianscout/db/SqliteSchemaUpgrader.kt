package com.obsidianscout.db

import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import java.sql.Connection

private val consoleLog = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.db.SqliteSchemaUpgrader")

/**
 * Adds columns that exist in the Exposed table definitions but are missing from an existing SQLite file.
 *
 * `SchemaUtils.create` only creates missing tables, so a database (or fallback mirror / snapshot) created by an
 * older release would otherwise fail every `selectAll()` on a table that has gained a column. SQLite cannot add a
 * NOT NULL column without a constant default, so every added column gets one (or is added as nullable).
 */
object SqliteSchemaUpgrader {

    fun addMissingColumns(conn: Connection, tables: Collection<Table>): List<String> {
        val added = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            for (table in tables) {
                val tableName = table.tableName
                val existing = mutableSetOf<String>()
                stmt.executeQuery("PRAGMA table_info(\"$tableName\")").use { rs ->
                    while (rs.next()) existing.add(rs.getString("name").lowercase())
                }
                if (existing.isEmpty()) continue // table doesn't exist yet; SchemaUtils.create handles it

                for (column in table.columns) {
                    if (existing.contains(column.name.lowercase())) continue
                    val sql = "ALTER TABLE \"$tableName\" ADD COLUMN \"${column.name}\" ${columnDefinition(column)}"
                    try {
                        stmt.executeUpdate(sql)
                        added.add("$tableName.${column.name}")
                        consoleLog.warn("[Database] Added missing SQLite column $tableName.${column.name}")
                    } catch (e: Exception) {
                        consoleLog.error("[Database] Failed to add SQLite column $tableName.${column.name}: ${e.message}")
                    }
                }
            }
        }
        return added
    }

    /** Opens a raw JDBC connection to a SQLite file and upgrades it. Used for snapshot files before restore. */
    fun upgradeFile(jdbcUrl: String, tables: Collection<Table>): List<String> {
        return java.sql.DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.autoCommit = true
            addMissingColumns(conn, tables)
        }
    }

    internal fun columnDefinition(column: Column<*>): String {
        val type = column.columnType
        val sqlType = when (type) {
            is BooleanColumnType -> "BOOLEAN"
            is IntegerColumnType, is LongColumnType -> "INTEGER"
            is DoubleColumnType, is FloatColumnType -> "REAL"
            is VarCharColumnType -> "VARCHAR(${type.colLength})"
            is TextColumnType -> "TEXT"
            else -> "TEXT"
        }

        val default = runCatching { column.defaultValueFun?.invoke() }.getOrNull()
        val defaultLiteral = when (default) {
            null -> null
            is Boolean -> if (default) "1" else "0"
            is Number -> default.toString()
            is String -> "'" + default.replace("'", "''") + "'"
            is Enum<*> -> "'" + default.name + "'"
            else -> null
        }

        return when {
            defaultLiteral != null -> "$sqlType NOT NULL DEFAULT $defaultLiteral"
            type.nullable -> "$sqlType NULL"
            type is BooleanColumnType -> "$sqlType NOT NULL DEFAULT 0"
            type is IntegerColumnType || type is LongColumnType -> "$sqlType NOT NULL DEFAULT 0"
            type is DoubleColumnType || type is FloatColumnType -> "$sqlType NOT NULL DEFAULT 0.0"
            type is VarCharColumnType || type is TextColumnType -> "$sqlType NOT NULL DEFAULT ''"
            // Timestamps/UUIDs have no constant default SQLite accepts for ADD COLUMN; add as nullable.
            else -> "$sqlType NULL"
        }
    }
}
