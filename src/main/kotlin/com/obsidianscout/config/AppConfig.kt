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
    val seed: SeedConfig = SeedConfig(),
    val vapid: VapidConfig = VapidConfig(),
    val database_type: String = "sqlite", // Supported options: "sqlite", "postgres", "cockroach"
    val db_username: String = "",
    val db_password: String = "",
    val google_sheet_url: String = "",
    val google_sheet_password: String = "",
    val cockroach_port: Int = 26257,
    val site_url: String = "https://kotlin.obsidianscout.com",
    val current_version: String = "0.4.8.5", // The version this server is running — update this on each release
    val gist_update: GistUpdateConfig = GistUpdateConfig()
) {
    fun getEffectiveSiteUrl(): String {
        val trimmed = site_url.trim()
        val base = if (trimmed.isBlank()) "https://kotlin.obsidianscout.com" else trimmed
        val withScheme = if (!base.startsWith("http://") && !base.startsWith("https://")) {
            "https://$base"
        } else {
            base
        }
        return withScheme.removeSuffix("/")
    }
}

@Serializable
data class GistUpdateConfig(
    val enabled: Boolean = false,
    val gist_url: String = "https://gist.githubusercontent.com/steveandjeff999/41b76376d064a6893ff2644b04447d9f/raw/Public-Version.json",
    val check_interval_minutes: Long = 10
)

@Serializable
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val sessionSecret: String = "change-me",
    val cookieSecure: Boolean = false,
    val https: HttpsConfig = HttpsConfig(),
    val logging: Boolean = false
)

@Serializable
data class HttpsConfig(
    val enabled: Boolean = false,
    val port: Int = 8443,
    val keystorePath: String = "config/obsidianscout.jks",
    val keystorePassword: String = "change-me",
    val keyAlias: String = "obsidianscout"
)

@Serializable
data class DatabaseConfig(
    /**
     * Database engine type.
     * Supported options:
     *   - "sqlite": Embedded SQLite local file (default for single-node standalone deployments)
     *   - "postgres": External or remote PostgreSQL server / cluster
     *   - "cockroach": External or remote CockroachDB cluster (uses PostgreSQL wire protocol on port 26257)
     */
    val type: String = "sqlite",
    val sqlite: SqliteConfig = SqliteConfig(),
    val postgres: PostgresConfig = PostgresConfig(),
    val cockroach: CockroachConfig = CockroachConfig(),
    /**
     * Optional direct JDBC connection URL (e.g., "jdbc:postgresql://<host>:26257/<database>?sslmode=require"
     * or "postgresql://<user>:<password>@<host>:26257/<database>?sslmode=verify-full").
     * When provided, this URL takes precedence over host/port/database settings.
     */
    val url: String = ""
)

@Serializable
data class SqliteConfig(
    val file: String = "data/obsidianscout.db"
)

@Serializable
data class PostgresConfig(
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "obsidianscoutjava",
    val user: String = "postgres",
    val password: String = "postgres",
    val ssl: Boolean = false,
    /** Optional direct JDBC connection string (e.g. "jdbc:postgresql://localhost:5432/obsidianscoutjava?sslmode=disable") */
    val url: String = ""
)

@Serializable
data class CockroachConfig(
    val host: String = "localhost",
    val port: Int = 26257,
    val database: String = "obsidianscoutjava",
    val user: String = "root",
    val password: String = "",
    val ssl: Boolean = false,
    /** Optional direct JDBC connection string (e.g. "jdbc:postgresql://remote-host:26257/obsidianscoutjava?sslmode=require") */
    val url: String = ""
)

@Serializable
data class SeedConfig(
    val adminUsername: String = "superadmin",
    val adminTeamNumber: Int = 0,
    val adminPassword: String = "change-me"
)

@Serializable
data class VapidConfig(
    val publicKey: String = "",
    val privateKey: String = "",
    val subject: String = "mailto:admin@obsidianscout.com"
)

object AppConfigLoader {
    private val defaultPath = Paths.get("config", "app-config.json")

    /** Values that indicate a secret has never been changed from its shipped placeholder. */
    private val DEFAULT_SECRET_VALUES = setOf("change-me", "changeme")

    @Volatile
    private var cachedConfig: AppConfig? = null

    fun load(path: Path = defaultPath, forceReload: Boolean = false): AppConfig {
        if (!forceReload && path == defaultPath && cachedConfig != null) {
            return cachedConfig!!
        }
        return synchronized(this) {
            if (!forceReload && path == defaultPath && cachedConfig != null) {
                return@synchronized cachedConfig!!
            }
            if (!Files.exists(path)) {
                path.parent?.let { Files.createDirectories(it) }
                val defaultText = JsonSupport.json.encodeToString(AppConfig())
                Files.writeString(path, defaultText)
            }
            val text = Files.readString(path)
            var config = JsonSupport.json.decodeFromString<AppConfig>(text)

            // If the configuration file is missing the new fields, write them back to disk.
            var needsWrite = false
            if (!text.contains("database_type")) {
                needsWrite = true
            }
            if (!text.contains("site_url")) {
                needsWrite = true
            }
            if (needsWrite) {
                val updatedText = JsonSupport.json.encodeToString(config)
                Files.writeString(path, updatedText)
            }

            // Auto-rotate any secrets that are still at their shipped default values.
            config = autoRotateSecrets(config, path)

            if (path == defaultPath) {
                cachedConfig = config
            }
            config
        }
    }

    fun updateCache(config: AppConfig) {
        cachedConfig = config
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

        val (vapidPublicKey, vapidPrivateKey) = if (config.vapid.publicKey.isBlank() || config.vapid.privateKey.isBlank()) {
            changed = true
            val keys = com.obsidianscout.utils.VapidKeyGenerator.generate()
            Pair(keys.publicKey, keys.privateKey)
        } else {
            Pair(config.vapid.publicKey, config.vapid.privateKey)
        }

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
            ),
            vapid = config.vapid.copy(
                publicKey = vapidPublicKey,
                privateKey = vapidPrivateKey
            )
        )

        val updatedText = JsonSupport.json.encodeToString(updated)
        Files.writeString(path, updatedText)

        println("[ObsidianScout] Default secrets or VAPID keys detected — auto-generated secure values and saved to ${path.toAbsolutePath()}")

        return updated
    }

    /**
     * Persists updated session secret and VAPID keys back to config/app-config.json on disk.
     */
    fun saveSecretUpdates(
        sessionSecret: String,
        vapidPublicKey: String,
        vapidPrivateKey: String,
        path: Path = defaultPath
    ) {
        try {
            val current = load(path)
            val updated = current.copy(
                server = current.server.copy(sessionSecret = sessionSecret),
                vapid = current.vapid.copy(
                    publicKey = vapidPublicKey,
                    privateKey = vapidPrivateKey
                )
            )
            val updatedText = JsonSupport.json.encodeToString(updated)
            Files.writeString(path, updatedText)
            if (path == defaultPath) {
                cachedConfig = updated
            }
            println("[ObsidianScout] Synchronized updated cluster secrets (Session & VAPID) to ${path.toAbsolutePath()}")
        } catch (e: Exception) {
            println("[ObsidianScout] Warning: Failed to save cluster secrets to config file: ${e.message}")
        }
    }

    /** Generates a cryptographically secure 32-byte random hex string. */
    fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
