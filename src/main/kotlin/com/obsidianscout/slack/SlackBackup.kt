package com.obsidianscout.slack

import com.obsidianscout.auth.ClusterCryptoUtils
import com.obsidianscout.auth.ClusterSecretService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.SlackIntegrations
import com.obsidianscout.db.SlackUserPreferences
import com.obsidianscout.db.Users
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * A team's Slack connection in a backup file. Tokens are written decrypted, like the TBA and FIRST keys in
 * [com.obsidianscout.db.AppSettingsBackupDto], and encrypted again with the importing cluster's key, so a backup
 * restores on any server. User ids are replaced with usernames: rule audiences ([rules] `audience.userIds`) and
 * [userLinks] keys hold usernames, because imported users get new ids.
 */
@Serializable
data class SlackIntegrationBackupDto(
    val teamNumber: Int,
    val program: String = "FRC",
    val enabled: Boolean = false,
    val botToken: String = "",
    val appToken: String = "",
    val workspaceId: String = "",
    val workspaceName: String = "",
    val workspaceUrl: String = "",
    val botUserId: String = "",
    val botId: String = "",
    val rules: List<SlackChannelRule> = emptyList(),
    val userLinks: Map<String, String> = emptyMap(),
    val updatedAt: Long = 0
)

@Serializable
data class SlackUserPreferenceBackupDto(
    val username: String,
    val teamNumber: Int,
    val program: String = "FRC",
    val preference: String
)

/** Slack's part of [com.obsidianscout.db.BackupService]: export, import, and the CSV form of both. */
object SlackBackup {
    private val log = LoggerFactory.getLogger("SlackBackup")
    private val rulesSerializer = ListSerializer(SlackChannelRule.serializer())
    private val linksSerializer = MapSerializer(String.serializer(), String.serializer())

    /** The key for stored tokens, or null when it can't be read; then tokens are left out of the backup. */
    fun dataKey(): String? = runCatching { ClusterSecretService.getDataEncryptionKey() }
        .onFailure { log.warn("Slack tokens left out of backup: encryption key unavailable (${it.message})") }
        .getOrNull()

    private fun decrypt(value: String, key: String?): String =
        if (value.isBlank() || key == null) "" else runCatching { ClusterCryptoUtils.decrypt(value, key) }.getOrDefault("")

    private fun encrypt(value: String, key: String): String = if (value.isBlank()) "" else ClusterCryptoUtils.encrypt(value, key)

    private fun teamFilter(team: Int?, program: String?) =
        if (team == null) SlackIntegrations.selectAll()
        else SlackIntegrations.selectAll().where { (SlackIntegrations.teamNumber eq team) and (SlackIntegrations.program eq (program ?: "FRC")) }

    /** Must run inside a transaction. [team] null exports every team. */
    fun exportIntegrations(key: String?, team: Int?, program: String?): List<SlackIntegrationBackupDto> {
        val usernames = Users.selectAll().associate { it[Users.id].value.toString() to it[Users.username] }
        return teamFilter(team, program).map { row ->
            val rules = runCatching { JsonSupport.json.decodeFromString(rulesSerializer, row[SlackIntegrations.rulesJson]) }.getOrDefault(emptyList())
            val links = runCatching { JsonSupport.json.decodeFromString(linksSerializer, row[SlackIntegrations.userLinksJson]) }.getOrDefault(emptyMap())
            SlackIntegrationBackupDto(
                teamNumber = row[SlackIntegrations.teamNumber],
                program = row[SlackIntegrations.program],
                enabled = row[SlackIntegrations.enabled],
                botToken = decrypt(row[SlackIntegrations.botTokenEnc], key),
                appToken = decrypt(row[SlackIntegrations.appTokenEnc], key),
                workspaceId = row[SlackIntegrations.workspaceId],
                workspaceName = row[SlackIntegrations.workspaceName],
                workspaceUrl = row[SlackIntegrations.workspaceUrl],
                botUserId = row[SlackIntegrations.botUserId],
                botId = row[SlackIntegrations.botId],
                rules = rules.map { r ->
                    r.copy(audience = r.audience.copy(userIds = r.audience.userIds.mapNotNull { usernames[it] }))
                },
                userLinks = links.mapNotNull { (userId, slackId) -> usernames[userId]?.let { it to slackId } }.toMap(),
                updatedAt = row[SlackIntegrations.updatedAt].toEpochMilli()
            )
        }
    }

    /** Must run inside a transaction. [team] null exports every team. */
    fun exportPreferences(team: Int?, program: String?): List<SlackUserPreferenceBackupDto> {
        val users = Users.selectAll().associateBy { it[Users.id].value }
        return SlackUserPreferences.selectAll().mapNotNull { row ->
            val user = users[row[SlackUserPreferences.userId].value] ?: return@mapNotNull null
            if (team != null && (user[Users.teamNumber] != team || user[Users.program] != (program ?: "FRC"))) return@mapNotNull null
            SlackUserPreferenceBackupDto(user[Users.username], user[Users.teamNumber], user[Users.program], row[SlackUserPreferences.preference])
        }
    }

    /**
     * Must run inside a transaction. Replaces the target team's Slack connection with the one in the backup;
     * tokens missing from the file keep the ones already stored. Returns how many connections were written.
     */
    fun importIntegrations(
        items: List<SlackIntegrationBackupDto>,
        key: String,
        targetTeam: (Int) -> Int,
        targetProgram: (String) -> String,
        userIdOf: (username: String, team: Int, program: String) -> UUID?
    ): Int {
        var written = 0
        for (dto in items) {
            val team = targetTeam(dto.teamNumber)
            val program = targetProgram(dto.program)
            val rules = dto.rules.map { r ->
                r.copy(audience = r.audience.copy(userIds = r.audience.userIds.mapNotNull { userIdOf(it, team, program)?.toString() }))
            }
            val links = dto.userLinks.mapNotNull { (username, slackId) -> userIdOf(username, team, program)?.let { it.toString() to slackId } }.toMap()
            val where = (SlackIntegrations.teamNumber eq team) and (SlackIntegrations.program eq program)
            if (SlackIntegrations.selectAll().where { where }.none()) {
                SlackIntegrations.insert {
                    it[teamNumber] = team
                    it[SlackIntegrations.program] = program
                    it[updatedAt] = Instant.now()
                }
            }
            SlackIntegrations.update({ where }) {
                it[enabled] = dto.enabled && (dto.botToken.isNotBlank() || dto.appToken.isNotBlank())
                if (dto.botToken.isNotBlank()) it[botTokenEnc] = encrypt(dto.botToken, key)
                if (dto.appToken.isNotBlank()) it[appTokenEnc] = encrypt(dto.appToken, key)
                it[workspaceId] = dto.workspaceId
                it[workspaceName] = dto.workspaceName
                it[workspaceUrl] = dto.workspaceUrl
                it[botUserId] = dto.botUserId
                it[botId] = dto.botId
                it[rulesJson] = JsonSupport.json.encodeToString(rulesSerializer, rules)
                it[userLinksJson] = JsonSupport.json.encodeToString(linksSerializer, links)
                it[status] = "connecting"
                it[statusDetail] = ""
                // A new timestamp makes the socket supervisor reconnect with the imported tokens.
                it[updatedAt] = Instant.now()
                it[updatedBy] = "backup import"
            }
            written++
        }
        return written
    }

    /** Must run inside a transaction. Returns how many preferences were written. */
    fun importPreferences(
        items: List<SlackUserPreferenceBackupDto>,
        targetTeam: (Int) -> Int,
        targetProgram: (String) -> String,
        userIdOf: (username: String, team: Int, program: String) -> UUID?
    ): Int {
        var written = 0
        for (p in items) {
            if (p.preference !in SlackPreference.values) continue
            val userId = userIdOf(p.username, targetTeam(p.teamNumber), targetProgram(p.program)) ?: continue
            val updated = SlackUserPreferences.update({ SlackUserPreferences.userId eq userId }) {
                it[preference] = p.preference
                it[updatedAt] = Instant.now()
            }
            if (updated == 0) {
                SlackUserPreferences.insert {
                    it[SlackUserPreferences.userId] = EntityID(userId, Users)
                    it[preference] = p.preference
                    it[updatedAt] = Instant.now()
                }
            }
            written++
        }
        return written
    }

    // ---- CSV backups ----

    const val INTEGRATIONS_CSV = "slack_integrations.csv"
    const val PREFERENCES_CSV = "slack_user_preferences.csv"

    val integrationHeaders = listOf(
        "team_number", "program", "enabled", "bot_token", "app_token", "workspace_id", "workspace_name",
        "workspace_url", "bot_user_id", "bot_id", "rules_json", "user_links_json", "updated_at"
    )
    val preferenceHeaders = listOf("username", "team_number", "program", "preference")

    fun integrationRow(d: SlackIntegrationBackupDto): List<String> = listOf(
        d.teamNumber.toString(), d.program, d.enabled.toString(), d.botToken, d.appToken, d.workspaceId, d.workspaceName,
        d.workspaceUrl, d.botUserId, d.botId, JsonSupport.wireJson.encodeToString(rulesSerializer, d.rules),
        JsonSupport.wireJson.encodeToString(linksSerializer, d.userLinks), d.updatedAt.toString()
    )

    fun integrationFromCsv(r: Map<String, String>): SlackIntegrationBackupDto? {
        val team = r["team_number"]?.toIntOrNull() ?: return null
        return SlackIntegrationBackupDto(
            teamNumber = team,
            program = r["program"]?.takeIf { it.isNotBlank() } ?: "FRC",
            enabled = r["enabled"].toBoolean(),
            botToken = r["bot_token"].orEmpty(),
            appToken = r["app_token"].orEmpty(),
            workspaceId = r["workspace_id"].orEmpty(),
            workspaceName = r["workspace_name"].orEmpty(),
            workspaceUrl = r["workspace_url"].orEmpty(),
            botUserId = r["bot_user_id"].orEmpty(),
            botId = r["bot_id"].orEmpty(),
            rules = runCatching { JsonSupport.json.decodeFromString(rulesSerializer, r["rules_json"].orEmpty()) }.getOrDefault(emptyList()),
            userLinks = runCatching { JsonSupport.json.decodeFromString(linksSerializer, r["user_links_json"].orEmpty()) }.getOrDefault(emptyMap()),
            updatedAt = r["updated_at"]?.toLongOrNull() ?: 0
        )
    }

    fun preferenceRow(p: SlackUserPreferenceBackupDto): List<String> = listOf(p.username, p.teamNumber.toString(), p.program, p.preference)

    fun preferenceFromCsv(r: Map<String, String>): SlackUserPreferenceBackupDto? {
        val username = r["username"]?.takeIf { it.isNotBlank() } ?: return null
        val team = r["team_number"]?.toIntOrNull() ?: return null
        return SlackUserPreferenceBackupDto(username, team, r["program"]?.takeIf { it.isNotBlank() } ?: "FRC", r["preference"].orEmpty())
    }
}
