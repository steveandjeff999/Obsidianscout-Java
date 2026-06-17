package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.Year

@Serializable
data class ApiKeys(
    val tbaKey: String = "",
    val firstUsername: String = "",
    val firstKey: String = ""
)

@Serializable
data class SmtpSettings(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    val passwordPlain: String = "",
    val fromAddress: String = "",
    val encryption: String = "STARTTLS" // SSL_TLS, STARTTLS, NONE
)

@Serializable
data class ApiSettings(
    val year: Int = Year.now().value,
    val eventCode: String = "",
    /** Computed from year + eventCode; kept for API responses and legacy stored JSON. */
    val eventKey: String = "",
    val timezone: String = "America/New_York",
    val preferredSource: String = "tba",
    val useStatboticsEpa: Boolean = false,
    val useTbaOpr: Boolean = false,
    val apiKeys: ApiKeys = ApiKeys()
) {
    fun resolvedEventKey(): String {
        val code = eventCode.trim().lowercase()
        if (code.isNotBlank()) {
            return "${year}$code"
        }
        return eventKey.trim().lowercase()
    }
}

object SettingsService {

    fun ensureDefaultSettings() {
        transaction {
            val existing = AppSettings
                .selectAll().where { AppSettings.teamNumber eq 0 }
                .limit(1)
                .firstOrNull() != null
            if (!existing) {
                val jsonText = JsonSupport.json.encodeToString(ApiSettings.serializer(), ApiSettings())
                AppSettings.insert {
                    it[teamNumber] = 0
                    it[settingsJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            }
        }
    }

    fun getSettings(teamNumber: Int): ApiSettings {
        val jsonText = transaction {
            // Try team-specific settings first
            val teamSettings = AppSettings
                .selectAll().where { AppSettings.teamNumber eq teamNumber }
                .limit(1)
                .firstOrNull()
                ?.get(AppSettings.settingsJson)

            if (teamSettings != null) {
                return@transaction teamSettings
            }

            // Fall back to team 0 (global default)
            AppSettings
                .selectAll().where { AppSettings.teamNumber eq 0 }
                .limit(1)
                .firstOrNull()
                ?.get(AppSettings.settingsJson)
        }
        val parsed = if (jsonText.isNullOrBlank()) {
            ApiSettings()
        } else {
            JsonSupport.json.decodeFromString(ApiSettings.serializer(), jsonText)
        }
        return normalize(parsed)
    }

    fun updateSettings(teamNumber: Int, settings: ApiSettings): ApiSettings {
        val normalized = normalize(settings)
        val jsonText = JsonSupport.json.encodeToString(ApiSettings.serializer(), normalized)
        transaction {
            val row = AppSettings
                .selectAll().where { AppSettings.teamNumber eq teamNumber }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                AppSettings.insert {
                    it[AppSettings.teamNumber] = teamNumber
                    it[settingsJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            } else {
                AppSettings.update({ AppSettings.id eq row[AppSettings.id] }) {
                    it[settingsJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            }
        }
        return normalized
    }

    private fun normalize(settings: ApiSettings): ApiSettings {
        val eventCode = resolveEventCode(settings)
        val resolvedKey = if (eventCode.isNotBlank()) {
            "${settings.year}${eventCode.lowercase()}"
        } else {
            settings.eventKey.trim().lowercase()
        }
        return settings.copy(
            eventCode = eventCode,
            eventKey = resolvedKey,
            timezone = settings.timezone.ifBlank { "America/New_York" },
            preferredSource = settings.preferredSource.lowercase()
        )
    }

    fun teamNumbersEligibleForAutoSync(): List<Int> {
        return transaction {
            AppSettings.selectAll()
                .map { it[AppSettings.teamNumber] }
                .distinct()
                .mapNotNull { teamNumber ->
                    val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(teamNumber)
                    if (settings.resolvedEventKey().isBlank()) {
                        return@mapNotNull null
                    }
                    val keys = settings.apiKeys
                    val hasApi = keys.tbaKey.isNotBlank() ||
                        (keys.firstUsername.isNotBlank() && keys.firstKey.isNotBlank())
                    if (!hasApi) {
                        return@mapNotNull null
                    }
                    teamNumber
                }
        }
    }

    private fun resolveEventCode(settings: ApiSettings): String {
        val trimmed = settings.eventCode.trim()
        if (trimmed.isNotBlank()) {
            return trimmed
        }
        val legacyKey = settings.eventKey.trim()
        if (legacyKey.length > 4 && legacyKey.take(4).all { it.isDigit() }) {
            return legacyKey.drop(4)
        }
        return ""
    }

    fun getSmtpSettings(): SmtpSettings {
        val jsonText = transaction {
            AppSettings
                .selectAll().where { AppSettings.teamNumber eq -1 }
                .limit(1)
                .firstOrNull()
                ?.get(AppSettings.settingsJson)
        }
        return if (jsonText.isNullOrBlank()) {
            SmtpSettings()
        } else {
            JsonSupport.json.decodeFromString(SmtpSettings.serializer(), jsonText)
        }
    }

    fun updateSmtpSettings(settings: SmtpSettings): SmtpSettings {
        val jsonText = JsonSupport.json.encodeToString(SmtpSettings.serializer(), settings)
        transaction {
            val row = AppSettings
                .selectAll().where { AppSettings.teamNumber eq -1 }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                AppSettings.insert {
                    it[AppSettings.teamNumber] = -1
                    it[settingsJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            } else {
                AppSettings.update({ AppSettings.id eq row[AppSettings.id] }) {
                    it[settingsJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            }
        }
        return settings
    }
}
