package com.obsidianscout.utils

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.db.ChatService
import kotlin.system.measureTimeMillis

fun main() {
    val appConfig = AppConfigLoader.load()
    DatabaseFactory.init(appConfig.database)

    println("Database initialized.")

    // Warm up
    try {
        AllianceService.getEffectiveSettings(5454)
    } catch (e: Exception) {
        println("Warm up error 1: ${e.message}")
    }
    try {
        ChatService.getUnreadStatus(1, 5454, "admin")
    } catch (e: Exception) {
        println("Warm up error 2: ${e.message}")
    }

    println("Benchmarking getEffectiveSettings...")
    val timeSettings = measureTimeMillis {
        for (i in 1..100) {
            AllianceService.getEffectiveSettings(5454)
        }
    }
    println("100 calls to getEffectiveSettings took: ${timeSettings}ms (Avg: ${timeSettings / 100.0}ms)")

    println("Benchmarking getUnreadStatus...")
    val timeUnread = measureTimeMillis {
        for (i in 1..100) {
            ChatService.getUnreadStatus(1, 5454, "admin")
        }
    }
    println("100 calls to getUnreadStatus took: ${timeUnread}ms (Avg: ${timeUnread / 100.0}ms)")
}
