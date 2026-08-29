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
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and
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
data class CloudflaredSettings(
    val enabled: Boolean = false,
    val tunnelId: String = "",
    val tunnelToken: String = "",
    val targetUrl: String = "http://localhost:8080",
    val customHostname: String = ""
)

@Serializable
data class LoadBalancerSettings(
    val enabled: Boolean = false,
    val probeIntervalSeconds: Long = 15,      // how often to refresh peer load cache
    val forwardTimeoutSeconds: Long = 30,     // upstream proxy read timeout
    val localPreferenceMargin: Double = 0.1,  // peer must beat local score by this much to win
    val maxExpectedLatencyMs: Double = 150.0, // latency ceiling for normalization (LAN ≈ 1–20 ms)
    val excludedPathPrefixes: List<String> = listOf(
        "/api/admin",
        "/api/cluster",
        "/api/health",
        "/health",
        "/api/version",
        "/version",
        "/cluster-management"
    )
)

val DEFAULT_SCOUT_PAGES = listOf(
    "dashboard", "chat", "scout", "pit-scout", "qual-scout", "qr-scanner", "contact"
)

val DEFAULT_ANALYTICS_PAGES = listOf(
    "dashboard", "events", "scout", "pit-scout", "qual-scout", "qr-scanner",
    "all-data", "qual-data", "pit-data", "analytics", "custom-analytics", "data-validation", "graphs",
    "teams", "rankings", "qual-rankings", "matches", "predictor",
    "event-predictor", "alliances", "alliance-selection", "chat", "backup", "docs", "contact"
)

val DEFAULT_ADMIN_PAGES = listOf(
    "dashboard", "admin-settings", "users", "banners", "scout", "pit-scout", "qual-scout", "qr-scanner",
    "all-data", "qual-data", "pit-data", "analytics", "custom-analytics", "data-validation", "graphs",
    "events", "teams", "rankings", "qual-rankings", "matches", "predictor",
    "event-predictor", "alliances", "alliance-selection", "chat", "backup", "docs", "contact"
)

fun canonicalTbaEventCode(code: String): String = code.trim().lowercase().removePrefix("frc")

fun canonicalTbaEventKey(year: Int, codeOrKey: String): String {
    val clean = canonicalTbaEventCode(codeOrKey)
    if (clean.isBlank()) return ""
    val yearStr = year.toString()
    return if (clean.startsWith(yearStr)) clean else "$yearStr$clean"
}

fun canonicalStoredEventKey(year: Int, key: String): String = canonicalTbaEventKey(year, key)


@Serializable
data class ThemeSettings(
    val name: String = "Default",
    val lightAccent: String = "",
    val lightAccent2: String = "",
    val lightAccent3: String = "",
    val lightBg: String = "",
    val lightInk: String = "",
    val lightMuted: String = "",
    val darkAccent: String = "",
    val darkAccent2: String = "",
    val darkAccent3: String = "",
    val darkBg: String = "",
    val darkInk: String = "",
    val darkMuted: String = "",
    val btnRadius: String = "999px"
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
    val chatEnabled: Boolean = true,
    val apiKeys: ApiKeys = ApiKeys(),
    val scoutPages: List<String> = DEFAULT_SCOUT_PAGES,
    val analyticsPages: List<String> = DEFAULT_ANALYTICS_PAGES,
    val adminPages: List<String> = DEFAULT_ADMIN_PAGES,
    val theme: ThemeSettings = ThemeSettings(),
    val themes: List<ThemeSettings> = emptyList(),
    val activeThemeName: String = "",
    val setupWizardCompleted: Boolean = false,
    val program: String = "FRC",
    val statboticsBaseUrl: String = "https://api.statbotics.io"
) {
    fun resolvedEventKey(): String {
        val code = eventCode.trim()
        if (code.isNotBlank()) {
            return canonicalTbaEventKey(year, code)
        }
        return canonicalStoredEventKey(year, eventKey)
    }
}

object SettingsService {

    fun ensureDefaultSettings() {
        transaction {
            listOf("FRC", "FTC").forEach { prog ->
                val existing = AppSettings
                    .selectAll().where { (AppSettings.teamNumber eq 0) and (AppSettings.program eq prog) }
                    .limit(1)
                    .firstOrNull() != null
                if (!existing) {
                    val jsonText = JsonSupport.json.encodeToString(ApiSettings.serializer(), ApiSettings(program = prog))
                    AppSettings.insert {
                        it[teamNumber] = 0
                        it[program] = prog
                        it[settingsJson] = jsonText
                        it[updatedAt] = Instant.now()
                    }
                }
            }
        }
    }

    fun getSettings(teamNumber: Int, program: String = "FRC"): ApiSettings {
        val (jsonText, isTeamSpecific) = readTransaction {
            // Try team-specific settings first
            val teamSettings = AppSettings
                .selectAll().where { (AppSettings.teamNumber eq teamNumber) and (AppSettings.program eq program) }
                .limit(1)
                .firstOrNull()
                ?.get(AppSettings.settingsJson)

            if (teamSettings != null) {
                return@readTransaction Pair(teamSettings, true)
            }

            // Fall back to team 0 (global default)
            val defaultSettings = AppSettings
                .selectAll().where { (AppSettings.teamNumber eq 0) and (AppSettings.program eq program) }
                .limit(1)
                .firstOrNull()
                ?.get(AppSettings.settingsJson)

            Pair(defaultSettings, false)
        }
        val parsed = if (jsonText.isNullOrBlank()) {
            ApiSettings(program = program)
        } else {
            JsonSupport.json.decodeFromString(ApiSettings.serializer(), jsonText)
        }
        val normalized = normalize(parsed)
        return if (teamNumber != 0 && !isTeamSpecific) {
            normalized.copy(eventCode = "", eventKey = "")
        } else {
            normalized
        }
    }

    fun updateSettings(teamNumber: Int, settings: ApiSettings): ApiSettings {
        val program = settings.program
        val existing = getSettings(teamNumber, program)
        val mergedKeys = settings.apiKeys.copy(
            tbaKey = if (settings.apiKeys.tbaKey == "********") existing.apiKeys.tbaKey else settings.apiKeys.tbaKey,
            firstKey = if (settings.apiKeys.firstKey == "********") existing.apiKeys.firstKey else settings.apiKeys.firstKey
        )
        val settingsWithMergedKeys = settings.copy(apiKeys = mergedKeys)
        val normalized = normalize(settingsWithMergedKeys)
        val jsonText = JsonSupport.json.encodeToString(ApiSettings.serializer(), normalized)
        transaction {
            val row = AppSettings
                .selectAll().where { (AppSettings.teamNumber eq teamNumber) and (AppSettings.program eq program) }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                AppSettings.insert {
                    it[AppSettings.teamNumber] = teamNumber
                    it[AppSettings.program] = program
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
        com.obsidianscout.scouting.AllianceService.clearEffectiveSettingsCache()
        return normalized
    }

    private fun normalize(settings: ApiSettings): ApiSettings {
        val eventCode = resolveEventCode(settings)
        val resolvedKey = if (eventCode.isNotBlank()) {
            canonicalTbaEventKey(settings.year, eventCode)
        } else {
            canonicalStoredEventKey(settings.year, settings.eventKey)
        }
        val normalizedScoutPages = if ("dashboard" !in settings.scoutPages) settings.scoutPages + "dashboard" else settings.scoutPages
        val normalizedAnalyticsPages = settings.analyticsPages.toMutableList().apply {
            if ("dashboard" !in this) add("dashboard")
            if ("events" !in this) add("events")
            if ("custom-analytics" !in this) add("custom-analytics")
            if ("data-validation" !in this) add("data-validation")
        }
        val normalizedAdminPages = settings.adminPages.toMutableList().apply {
            if ("dashboard" !in this) add("dashboard")
            if ("admin-settings" !in this) add("admin-settings")
            if ("events" !in this) add("events")
            if ("custom-analytics" !in this) add("custom-analytics")
            if ("data-validation" !in this) add("data-validation")
        }
        val isFtc = settings.program.equals("FTC", ignoreCase = true)
        val normalizedUseStatboticsEpa = if (isFtc) false else settings.useStatboticsEpa
        val rawStatboticsUrl = settings.statboticsBaseUrl.trim()
        val statboticsUrl = (if (rawStatboticsUrl.isBlank()) "https://api.statbotics.io" else rawStatboticsUrl).removeSuffix("/")
        return settings.copy(
            eventCode = canonicalTbaEventCode(eventCode),
            eventKey = resolvedKey,
            timezone = settings.timezone.ifBlank { "America/New_York" },
            preferredSource = settings.preferredSource.lowercase(),
            useStatboticsEpa = normalizedUseStatboticsEpa,
            scoutPages = normalizedScoutPages,
            analyticsPages = normalizedAnalyticsPages,
            adminPages = normalizedAdminPages,
            statboticsBaseUrl = statboticsUrl
        )
    }

    fun teamNumbersEligibleForAutoSync(): List<Pair<Int, String>> {
        return readTransaction {
            AppSettings.selectAll()
                .map { Pair(it[AppSettings.teamNumber], it[AppSettings.program]) }
                .distinct()
                .mapNotNull { (teamNumber, program) ->
                    val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(teamNumber, program)
                    if (settings.resolvedEventKey().isBlank()) {
                        return@mapNotNull null
                    }
                    val keys = settings.apiKeys
                    val isFtc = program.equals("FTC", ignoreCase = true)
                    val hasApi = if (isFtc) {
                        true
                    } else {
                        keys.tbaKey.isNotBlank() || (keys.firstUsername.isNotBlank() && keys.firstKey.isNotBlank())
                    }
                    if (!hasApi) {
                        return@mapNotNull null
                    }
                    Pair(teamNumber, program)
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
            return canonicalTbaEventCode(legacyKey.drop(4))
        }
        return ""
    }

    fun getSmtpSettings(): SmtpSettings {
        val jsonText = readTransaction {
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

    fun getCloudflaredSettings(): CloudflaredSettings {
        val jsonText = try {
            readTransaction {
                AppSettings
                    .selectAll().where { AppSettings.teamNumber eq -2 }
                    .limit(1)
                    .firstOrNull()
                    ?.get(AppSettings.settingsJson)
            }
        } catch (e: Throwable) {
            null
        }
        return if (jsonText.isNullOrBlank()) {
            CloudflaredSettings()
        } else {
            try {
                JsonSupport.json.decodeFromString(CloudflaredSettings.serializer(), jsonText)
            } catch (e: Throwable) {
                CloudflaredSettings()
            }
        }
    }

    fun updateCloudflaredSettings(settings: CloudflaredSettings): CloudflaredSettings {
        val jsonText = JsonSupport.json.encodeToString(CloudflaredSettings.serializer(), settings)
        transaction {
            val row = AppSettings
                .selectAll().where { AppSettings.teamNumber eq -2 }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                AppSettings.insert {
                    it[AppSettings.teamNumber] = -2
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

    fun getLoadBalancerSettings(): LoadBalancerSettings {
        val jsonText = try {
            readTransaction {
                AppSettings
                    .selectAll().where { AppSettings.teamNumber eq -3 }
                    .limit(1)
                    .firstOrNull()
                    ?.get(AppSettings.settingsJson)
            }
        } catch (e: Throwable) {
            null
        }
        return if (jsonText.isNullOrBlank()) {
            LoadBalancerSettings()
        } else {
            try {
                JsonSupport.json.decodeFromString(LoadBalancerSettings.serializer(), jsonText)
            } catch (e: Throwable) {
                LoadBalancerSettings()
            }
        }
    }

    fun updateLoadBalancerSettings(settings: LoadBalancerSettings): LoadBalancerSettings {
        val jsonText = JsonSupport.json.encodeToString(LoadBalancerSettings.serializer(), settings)
        transaction {
            val row = AppSettings
                .selectAll().where { AppSettings.teamNumber eq -3 }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                AppSettings.insert {
                    it[AppSettings.teamNumber] = -3
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
