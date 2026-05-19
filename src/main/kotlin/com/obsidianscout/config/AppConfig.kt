package com.obsidianscout.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class AppConfig(
    val server: ServerConfig = ServerConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val seed: SeedConfig = SeedConfig()
)

@Serializable
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val sessionSecret: String = "change-me",
    val cookieSecure: Boolean = false,
    val https: HttpsConfig = HttpsConfig()
)

@Serializable
data class HttpsConfig(
    val enabled: Boolean = true,
    val port: Int = 8443,
    val keystorePath: String = "config/obsidianscout.jks",
    val keystorePassword: String = "change-me",
    val keyAlias: String = "obsidianscout"
)

@Serializable
data class DatabaseConfig(
    val type: String = "sqlite",
    val sqlite: SqliteConfig = SqliteConfig(),
    val postgres: PostgresConfig = PostgresConfig()
)

@Serializable
data class SqliteConfig(
    val file: String = "data/obsidianscout.db"
)

@Serializable
data class PostgresConfig(
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "obsidianscout",
    val user: String = "postgres",
    val password: String = "postgres",
    val ssl: Boolean = false
)

@Serializable
data class SeedConfig(
    val adminUsername: String = "admin",
    val adminTeamNumber: Int = 0,
    val adminPassword: String = "change-me"
)

object AppConfigLoader {
    private val defaultPath = Paths.get("config", "app-config.json")

    fun load(path: Path = defaultPath): AppConfig {
        if (!Files.exists(path)) {
            path.parent?.let { Files.createDirectories(it) }
            val defaultText = JsonSupport.json.encodeToString(AppConfig())
            Files.writeString(path, defaultText)
        }
        val text = Files.readString(path)
        return JsonSupport.json.decodeFromString(text)
    }
}
