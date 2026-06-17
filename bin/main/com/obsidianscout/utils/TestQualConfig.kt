package com.obsidianscout.utils

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ConfigService
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.QualitativeScoutingConfigs
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    println("Current Working Directory: " + System.getProperty("user.dir"))
    
    val pathsToTry = listOf(
        java.nio.file.Paths.get("config", "app-config.json"),
        java.nio.file.Paths.get("Obsidianscout", "config", "app-config.json")
    )
    
    for (path in pathsToTry) {
        if (!java.nio.file.Files.exists(path)) {
            println("Config path does not exist: $path")
            continue
        }
        println("\n--- Testing config from: ${path.toAbsolutePath()} ---")
        try {
            val appConfig = AppConfigLoader.load(path)
            println("Database Type: ${appConfig.database.type}")
            if (appConfig.database.type == "postgres") {
                println("Postgres Database: ${appConfig.database.postgres.database} on ${appConfig.database.postgres.host}:${appConfig.database.postgres.port}")
            } else {
                println("SQLite File: ${appConfig.database.sqlite.file}")
            }
            
            DatabaseFactory.init(appConfig.database)
            
            transaction {
                val rows = QualitativeScoutingConfigs.selectAll().toList()
                println("Found ${rows.size} qualitative config rows.")
                for (row in rows) {
                    val team = row[QualitativeScoutingConfigs.teamNumber]
                    val raw = row[QualitativeScoutingConfigs.configJson]
                    println("  Team: $team (JSON length: ${raw.length})")
                    try {
                        val elem = JsonSupport.json.parseToJsonElement(raw)
                        val obj = elem as? JsonObject
                        if (obj != null) {
                            val fields = obj["fields"]
                            if (fields is kotlinx.serialization.json.JsonArray) {
                                val transformed = fields.map { f ->
                                    val fo = f as? JsonObject ?: return@map f
                                    val label = fo["label"]
                                    if (label is JsonPrimitive && label.isString) {
                                        val newField = buildJsonObject {
                                            fo.entries.forEach { (k, v) ->
                                                if (k == "label") {
                                                    put(k, JsonObject(mapOf("en" to JsonPrimitive(v.toString().trim('"')))))
                                                } else {
                                                    put(k, v)
                                                }
                                            }
                                        }
                                        return@map newField
                                    }
                                    f
                                }
                                val out = buildJsonObject {
                                    obj.entries.forEach { (k, v) ->
                                        if (k == "fields") {
                                            put(k, kotlinx.serialization.json.JsonArray(transformed))
                                        } else {
                                            put(k, v)
                                        }
                                    }
                                }
                                val output = JsonSupport.json.encodeToString(JsonElement.serializer(), out)
                                println("    TRANSFORMATION SUCCESSFUL. Output length: ${output.length}")
                            } else {
                                println("    WARNING: fields is not a JsonArray: $fields")
                            }
                        } else {
                            println("    WARNING: Root JSON element is not a JsonObject")
                        }
                    } catch (e: Throwable) {
                        println("    TRANSFORMATION FAILED WITH EXCEPTION:")
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Throwable) {
            println("Failed to run config at $path:")
            e.printStackTrace()
        }
    }
}
