package com.obsidianscout.utils

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Paths

fun main() {
    println("Regenerating config files matching class defaults...")

    // 1. Generate app-config.json
    val appConfigJson = JsonSupport.json.encodeToString(AppConfig())
    val innerConfigPath = Paths.get("config", "app-config.json")
    val outerConfigPath = Paths.get("../config", "app-config.json")

    Files.createDirectories(innerConfigPath.parent)
    Files.writeString(innerConfigPath, appConfigJson + "\n")
    println("Generated inner config: ${innerConfigPath.toAbsolutePath()}")

    if (Files.exists(outerConfigPath.parent)) {
        Files.writeString(outerConfigPath, appConfigJson + "\n")
        println("Generated outer config: ${outerConfigPath.toAbsolutePath()}")
    }

    // 2. Generate default-scouting-config.json
    val scoutingConfigJson = JsonSupport.json.encodeToString(ConfigService.defaultConfig())
    val scoutingConfigPath = Paths.get("config", "default-scouting-config.json")
    Files.writeString(scoutingConfigPath, scoutingConfigJson + "\n")
    println("Generated default-scouting-config: ${scoutingConfigPath.toAbsolutePath()}")

    // 3. Generate default-pit-scouting-config.json
    val pitConfigJson = JsonSupport.json.encodeToString(ConfigService.defaultPitConfig())
    val pitConfigPath = Paths.get("config", "default-pit-scouting-config.json")
    Files.writeString(pitConfigPath, pitConfigJson + "\n")
    println("Generated default-pit-scouting-config: ${pitConfigPath.toAbsolutePath()}")

    // 4. Generate default-qualitative-scouting-config.json
    val qualitativeConfigJson = JsonSupport.json.encodeToString(ConfigService.defaultQualitativeConfig())
    val qualitativeConfigPath = Paths.get("config", "default-qualitative-scouting-config.json")
    Files.writeString(qualitativeConfigPath, qualitativeConfigJson + "\n")
    println("Generated default-qualitative-scouting-config: ${qualitativeConfigPath.toAbsolutePath()}")

    println("All default configurations successfully regenerated!")
}
