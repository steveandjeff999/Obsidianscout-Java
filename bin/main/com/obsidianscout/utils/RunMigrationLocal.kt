package com.obsidianscout.utils

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.MigrationService

fun main() {
    println("========================================================================")
    println("  ObsidianScout – Local Database Migration Utility")
    println("========================================================================")
    println()

    println("Loading configuration...")
    val appConfig = try {
        AppConfigLoader.load()
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to load config/app-config.json")
        return
    }

    println("Connecting to target database (${appConfig.database.type})...")
    try {
        DatabaseFactory.init(appConfig.database)
    } catch (e: Exception) {
        System.err.println("ERROR: Failed to connect to target database: ${e.message}")
        return
    }

    val sqliteInstancePath = "C:\\Users\\steve\\OneDrive\\Scout2026stuff\\Release\\OBSIDIAN-Scout Current\\Obsidian-Scout\\instance"
    println("Starting database migration from: $sqliteInstancePath")

    MigrationService.startMigration("sqlite", sqliteInstancePath, null)

    while (true) {
        val status = MigrationService.getStatus()
        println("Progress: ${status.progress}% | ${status.message}")
        println("  Migrated: Users: ${status.usersMigrated} | Events: ${status.eventsMigrated} | Teams: ${status.teamsMigrated} | Matches: ${status.matchesMigrated} | Scouting: ${status.scoutingDataMigrated} | Alliances: ${status.alliancesMigrated}")
        
        if (!status.running) {
            println()
            if (status.success) {
                println("SUCCESS: Migration completed successfully!")
            } else {
                System.err.println("ERROR: Migration failed!")
            }
            break
        }
        Thread.sleep(1000)
    }
    println("========================================================================")
}
