package com.obsidianscout.db

import com.obsidianscout.config.DatabaseConfig
import com.obsidianscout.config.SqliteConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseDurabilityTest {

    object DurabilityTestTable : Table("durability_test_items") {
        val id = integer("id").autoIncrement()
        val title = varchar("title", 100)
        override val primaryKey = PrimaryKey(id)
    }

    private val testDbFile = File("build/test_durability_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @AfterTest
    fun tearDown() {
        DatabaseFactory.close()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        val wal = File(testDbFile.path + "-wal")
        val shm = File(testDbFile.path + "-shm")
        if (wal.exists()) wal.delete()
        if (shm.exists()) shm.delete()
    }

    @Test
    fun testSqliteSynchronousFullInitAndWalCheckpointOnClose() {
        val config = DatabaseConfig(
            type = "sqlite",
            sqlite = SqliteConfig(
                file = testDbFile.path,
                synchronous = "FULL"
            )
        )

        // 1. Initialize DB with synchronous=FULL
        DatabaseFactory.init(config, runMigration = false)
        assertTrue(DatabaseFactory.primaryDatabase != null)

        transaction {
            SchemaUtils.create(DurabilityTestTable)
            DurabilityTestTable.insert {
                it[title] = "Critical Entry 1"
            }
            DurabilityTestTable.insert {
                it[title] = "Critical Entry 2"
            }
        }

        // 2. Close database (triggers PRAGMA wal_checkpoint(TRUNCATE))
        DatabaseFactory.close()

        // 3. Re-open database to verify all committed transactions persist cleanly
        DatabaseFactory.init(config, runMigration = false)
        val items = readTransaction {
            DurabilityTestTable.selectAll().map { it[DurabilityTestTable.title] }
        }

        assertEquals(2, items.size)
        assertTrue(items.contains("Critical Entry 1"))
        assertTrue(items.contains("Critical Entry 2"))
    }
}
