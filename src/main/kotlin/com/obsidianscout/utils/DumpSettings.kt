package com.obsidianscout.utils

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.AppSettings
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun main(args: Array<String>) {
    val appConfig = AppConfigLoader.load()
    DatabaseFactory.init(appConfig.database)
    
    transaction {
        val rows = AppSettings.selectAll().toList()
        println("=== AppSettings Database Dump ===")
        for (row in rows) {
            println("Team Number: ${row[AppSettings.teamNumber]}")
            println("Settings JSON: ${row[AppSettings.settingsJson]}")
            println("------------------------------------------------")
        }
    }
}
