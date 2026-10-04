package com.obsidianscout.utils

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.AppSettings
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

fun main() {
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
