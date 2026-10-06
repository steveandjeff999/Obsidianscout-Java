package com.obsidianscout.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.sql.DriverManager
import kotlin.test.*

class SqliteSchemaUpgraderTest {

    private val dbFile = File("build/test_sqlite_upgrade_${System.currentTimeMillis()}.db")
    private val url get() = "jdbc:sqlite:${dbFile.absolutePath}"

    @BeforeTest
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    /** A users table as created by an older release: no bug_report_preference / local_ai_enabled / last_login. */
    private fun createLegacyUsersTable() {
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE users (
                        id BINARY(16) NOT NULL PRIMARY KEY,
                        username VARCHAR(64) NOT NULL,
                        team_number INT NOT NULL,
                        program VARCHAR(8) DEFAULT 'FRC' NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        role VARCHAR(16) NOT NULL,
                        created_at TEXT NOT NULL,
                        email VARCHAR(255) NULL,
                        profile_picture TEXT NULL,
                        notification_preference VARCHAR(16) DEFAULT 'all' NOT NULL,
                        tour_progress TEXT NULL,
                        node_alerts_enabled BOOLEAN DEFAULT 0 NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    "INSERT INTO users (id, username, team_number, password_hash, role, created_at) " +
                        "VALUES (X'0123456789abcdef0123456789abcdef', 'legacy', 254, 'x', 'SCOUT', '2025-01-01 00:00:00.000')"
                )
            }
        }
    }

    @Test
    fun addsMissingColumnsWithDefaultsToLegacyTable() {
        createLegacyUsersTable()

        val added = SqliteSchemaUpgrader.upgradeFile(url, listOf(Users))
        assertTrue("users.local_ai_enabled" in added, "local_ai_enabled should be added, got $added")
        assertTrue("users.bug_report_preference" in added)
        assertTrue("users.last_login" in added)

        // Exposed can now read the legacy row, and new columns carry their defaults.
        val db = Database.connect(url, driver = "org.sqlite.JDBC")
        val row = transaction(db) { Users.selectAll().single() }
        assertEquals("legacy", row[Users.username])
        assertFalse(row[Users.localAiEnabled], "existing users must get local AI = off")
        assertEquals("ask", row[Users.bugReportPreference])
        assertNull(row[Users.lastLogin])
    }

    @Test
    fun isIdempotentAndIgnoresMissingTables() {
        createLegacyUsersTable()
        SqliteSchemaUpgrader.upgradeFile(url, listOf(Users))
        val second = SqliteSchemaUpgrader.upgradeFile(url, listOf(Users, ChatGroups))
        assertTrue(second.isEmpty(), "second run should add nothing (and skip tables that don't exist), got $second")
    }

    @Test
    fun columnDefinitionsUseSqliteSafeDefaults() {
        assertEquals("BOOLEAN NOT NULL DEFAULT 0", SqliteSchemaUpgrader.columnDefinition(Users.localAiEnabled))
        assertEquals("VARCHAR(16) NOT NULL DEFAULT 'ask'", SqliteSchemaUpgrader.columnDefinition(Users.bugReportPreference))
        assertTrue(SqliteSchemaUpgrader.columnDefinition(Users.lastLogin).endsWith("NULL"))
    }
}
