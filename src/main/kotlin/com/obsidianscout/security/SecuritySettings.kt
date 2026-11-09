package com.obsidianscout.security

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.SecurityConfig
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.readTransaction
import com.obsidianscout.db.writeTransactionWithRetry
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/**
 * The security settings in effect. Thresholds and limits come from app-config.json; the two switches a superadmin
 * flips from Server Insights (mode, automatic session revocation) are stored cluster-wide in app_settings.
 */
object SecuritySettings {
    /** app_settings row for the cluster-wide switches (other negative team numbers are taken by other settings). */
    private const val SETTINGS_ROW = -5
    private const val CACHE_MS = 15_000L

    @Volatile private var configOverride: SecurityConfig? = null
    @Volatile private var cached: Pair<SecuritySettingsDto, Long>? = null

    /** Tests set a config without touching config/app-config.json. */
    fun setConfigForTests(config: SecurityConfig?) {
        configOverride = config
        cached = null
    }

    val config: SecurityConfig
        get() = configOverride ?: runCatching { AppConfigLoader.load().security }.getOrDefault(SecurityConfig())

    val enabled: Boolean get() = config.enabled

    fun runtime(): SecuritySettingsDto {
        val now = System.currentTimeMillis()
        cached?.let { if (now - it.second < CACHE_MS) return it.first }
        val defaults = SecuritySettingsDto(mode = normalizeMode(config.mode), autoRevokeSessions = config.auto_revoke_sessions)
        val stored = if (configOverride == null && DatabaseFactory.isReady) {
            runCatching {
                readTransaction {
                    AppSettings.selectAll().where { AppSettings.teamNumber eq SETTINGS_ROW }.limit(1).firstOrNull()?.get(AppSettings.settingsJson)
                }
            }.getOrNull()?.let { text ->
                runCatching { JsonSupport.json.decodeFromString(SecuritySettingsDto.serializer(), text) }.getOrNull()
            }
        } else null
        val effective = stored?.let { it.copy(mode = normalizeMode(it.mode)) } ?: defaults
        cached = effective to now
        return effective
    }

    val enforcing: Boolean get() = enabled && runtime().mode == "enforce"
    val autoRevokeSessions: Boolean get() = runtime().autoRevokeSessions

    fun update(settings: SecuritySettingsDto): SecuritySettingsDto {
        val normalized = settings.copy(mode = normalizeMode(settings.mode))
        val text = JsonSupport.json.encodeToString(SecuritySettingsDto.serializer(), normalized)
        if (configOverride == null && DatabaseFactory.isReady) {
            writeTransactionWithRetry {
                val row = AppSettings.selectAll().where { AppSettings.teamNumber eq SETTINGS_ROW }.limit(1).firstOrNull()
                if (row == null) {
                    AppSettings.insert {
                        it[teamNumber] = SETTINGS_ROW
                        it[program] = "SEC"
                        it[settingsJson] = text
                        it[updatedAt] = Instant.now()
                    }
                } else {
                    AppSettings.update({ AppSettings.id eq row[AppSettings.id] }) {
                        it[settingsJson] = text
                        it[updatedAt] = Instant.now()
                    }
                }
            }
        }
        cached = normalized to System.currentTimeMillis()
        return normalized
    }

    private fun normalizeMode(mode: String): String = if (mode.trim().equals("enforce", ignoreCase = true)) "enforce" else "observe"
}
