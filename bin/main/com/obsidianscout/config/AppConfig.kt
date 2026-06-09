package com.obsidianscout.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom

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
    val adminUsername: String = "superadmin",
    val adminTeamNumber: Int = 0,
    val adminPassword: String = "change-me"
)

object AppConfigLoader {
    private val defaultPath = Paths.get("config", "app-config.json")

    /** Values that indicate a secret has never been changed from its shipped placeholder. */
    private val DEFAULT_SECRET_VALUES = setOf("change-me", "changeme")

    fun load(path: Path = defaultPath): AppConfig {
        if (!Files.exists(path)) {
            path.parent?.let { Files.createDirectories(it) }
            val defaultText = JsonSupport.json.encodeToString(AppConfig())
            Files.writeString(path, defaultText)
        }
        val text = Files.readString(path)
        var config = JsonSupport.json.decodeFromString<AppConfig>(text)

        // Auto-rotate any secrets that are still at their shipped default values.
        config = autoRotateSecrets(config, path)

        return config
    }

    /**
     * Checks each secret field. If it is still set to a known default placeholder,
     * replaces it with a cryptographically random value and persists the updated
     * config back to disk so the same secret is reused on subsequent startups.
     */
    private fun autoRotateSecrets(config: AppConfig, path: Path): AppConfig {
        var changed = false
        var keystorePasswordRotated = false

        val sessionSecret = if (config.server.sessionSecret in DEFAULT_SECRET_VALUES) {
            changed = true
            generateSecret()
        } else config.server.sessionSecret

        val keystorePassword = if (config.server.https.keystorePassword in DEFAULT_SECRET_VALUES) {
            changed = true
            keystorePasswordRotated = true
            generateSecret()
        } else config.server.https.keystorePassword

        // Admin password is intentionally excluded — it stays as "changeme" and
        // must be changed manually by the user after deploying.

        if (!changed) return config

        // If the keystore password changed, delete the existing keystore file so it is
        // regenerated fresh with the new password. Without this the server crashes
        // trying to open the old file with a mismatched key.
        if (keystorePasswordRotated) {
            val keystoreFile = File(config.server.https.keystorePath)
            if (keystoreFile.exists()) {
                keystoreFile.delete()
                println("[ObsidianScout] Deleted old keystore (${keystoreFile.path}) — it will be regenerated with the new password.")
            }
        }

        val updated = config.copy(
            server = config.server.copy(
                sessionSecret = sessionSecret,
                https = config.server.https.copy(
                    keystorePassword = keystorePassword
                )
            )
        )

        val updatedText = JsonSupport.json.encodeToString(updated)
        Files.writeString(path, updatedText)

        println("[ObsidianScout] Default secrets detected — auto-generated secure random values and saved to ${path.toAbsolutePath()}")

        return updated
    }

    /** Generates a cryptographically secure 32-byte random hex string. */
    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
