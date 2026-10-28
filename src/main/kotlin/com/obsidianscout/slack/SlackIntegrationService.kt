package com.obsidianscout.slack

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.ClusterCryptoUtils
import com.obsidianscout.auth.ClusterSecretService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.SlackIntegrations
import com.obsidianscout.db.SlackNotifications
import com.obsidianscout.db.SlackUserPreferences
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A team's Slack connection with its tokens decrypted. Server-side only. */
data class SlackIntegrationRecord(
    val teamNumber: Int,
    val program: String,
    val enabled: Boolean,
    val botToken: String,
    val appToken: String,
    val workspaceName: String,
    val workspaceUrl: String,
    val botUserId: String,
    val botId: String,
    val rules: List<SlackChannelRule>,
    val userLinks: Map<String, String>,
    val status: String,
    val statusDetail: String,
    val lastEventAt: Instant?,
    val updatedAt: Instant
) {
    val ready: Boolean get() = enabled && botToken.isNotBlank() && appToken.isNotBlank()
}

data class SlackTeamMember(val id: String, val username: String, val role: String, val email: String)

/** Small time-limited cache. */
internal class TtlCache<K : Any, V : Any>(private val ttlMillis: Long) {
    private val map = ConcurrentHashMap<K, Pair<Long, V>>()
    fun get(key: K): V? = map[key]?.takeIf { System.currentTimeMillis() - it.first < ttlMillis }?.second
    fun put(key: K, value: V): V { map[key] = System.currentTimeMillis() to value; return value }
    suspend fun getOrLoad(key: K, load: suspend () -> V): V = get(key) ?: put(key, load())
    fun remove(key: K) { map.remove(key) }
    fun removeIf(predicate: (K) -> Boolean) { map.keys.removeIf(predicate) }
}

object SlackIntegrationService {
    private val log = LoggerFactory.getLogger("SlackIntegrationService")
    const val MASK = "********"
    private val rulesSerializer = ListSerializer(SlackChannelRule.serializer())
    private val linksSerializer = MapSerializer(String.serializer(), String.serializer())
    private val targetsSerializer = MapSerializer(String.serializer(), Boolean.serializer())

    private val recordCache = TtlCache<String, Pair<SlackIntegrationRecord?, Unit>>(30_000)
    private val autoLinkCache = TtlCache<String, Map<String, String>>(6 * 60 * 60_000L)
    private val slackUsersCache = TtlCache<String, List<SlackUserInfo>>(10 * 60_000L)
    private val userNameCache = TtlCache<String, String>(60 * 60_000L)
    private val channelNameCache = TtlCache<String, String>(60 * 60_000L)
    private val groupCache = TtlCache<String, List<SlackUserGroupInfo>>(60 * 60_000L)
    private val groupMembersCache = TtlCache<String, Set<String>>(10 * 60_000L)

    private fun key(team: Int, program: String) = "$team:$program"

    private fun encrypt(value: String): String =
        if (value.isBlank()) "" else ClusterCryptoUtils.encrypt(value, ClusterSecretService.getDataEncryptionKey())

    private fun decrypt(value: String): String =
        if (value.isBlank()) "" else runCatching { ClusterCryptoUtils.decrypt(value, ClusterSecretService.getDataEncryptionKey()) }
            .getOrElse { log.warn("Stored Slack token could not be decrypted"); "" }

    private fun rowToRecord(row: org.jetbrains.exposed.v1.core.ResultRow) = SlackIntegrationRecord(
        teamNumber = row[SlackIntegrations.teamNumber],
        program = row[SlackIntegrations.program],
        enabled = row[SlackIntegrations.enabled],
        botToken = decrypt(row[SlackIntegrations.botTokenEnc]),
        appToken = decrypt(row[SlackIntegrations.appTokenEnc]),
        workspaceName = row[SlackIntegrations.workspaceName],
        workspaceUrl = row[SlackIntegrations.workspaceUrl],
        botUserId = row[SlackIntegrations.botUserId],
        botId = row[SlackIntegrations.botId],
        rules = runCatching { JsonSupport.json.decodeFromString(rulesSerializer, row[SlackIntegrations.rulesJson]) }.getOrDefault(emptyList()),
        userLinks = runCatching { JsonSupport.json.decodeFromString(linksSerializer, row[SlackIntegrations.userLinksJson]) }.getOrDefault(emptyMap()),
        status = row[SlackIntegrations.status],
        statusDetail = row[SlackIntegrations.statusDetail],
        lastEventAt = row[SlackIntegrations.lastEventAt],
        updatedAt = row[SlackIntegrations.updatedAt]
    )

    /** Uncached read. */
    fun load(team: Int, program: String): SlackIntegrationRecord? = readTransaction {
        SlackIntegrations.selectAll()
            .where { (SlackIntegrations.teamNumber eq team) and (SlackIntegrations.program eq program) }
            .firstOrNull()?.let(::rowToRecord)
    }

    /** Read with a 30 s per-node cache, for the event path. */
    fun cached(team: Int, program: String): SlackIntegrationRecord? {
        val k = key(team, program)
        recordCache.get(k)?.let { return it.first }
        return load(team, program).also { recordCache.put(k, it to Unit) }
    }

    fun clearCache(team: Int, program: String) {
        val k = key(team, program)
        recordCache.remove(k)
        autoLinkCache.remove(k)
        slackUsersCache.remove(k)
        groupCache.remove(k)
        groupMembersCache.removeIf { it.startsWith("$k:") }
    }

    /** Forgets everything cached on this node, for every team (after a restore or import). */
    fun clearAllCaches() {
        listOf(recordCache, autoLinkCache, slackUsersCache, userNameCache, channelNameCache, groupCache, groupMembersCache)
            .forEach { it.removeIf { true } }
    }

    /** Every team with Slack switched on and both tokens stored. */
    fun allReady(): List<SlackIntegrationRecord> = readTransaction {
        SlackIntegrations.selectAll().where { SlackIntegrations.enabled eq true }.map(::rowToRecord)
    }.filter { it.ready }

    fun toResponse(record: SlackIntegrationRecord?): SlackIntegrationResponse = SlackIntegrationResponse(
        configured = record != null && record.botToken.isNotBlank(),
        enabled = record?.enabled ?: false,
        botTokenSet = record?.botToken?.isNotBlank() ?: false,
        appTokenSet = record?.appToken?.isNotBlank() ?: false,
        workspaceName = record?.workspaceName.orEmpty(),
        workspaceUrl = record?.workspaceUrl.orEmpty(),
        status = record?.status ?: "disconnected",
        statusDetail = record?.statusDetail.orEmpty(),
        lastEventAt = record?.lastEventAt?.toString(),
        rules = record?.rules.orEmpty(),
        userLinks = record?.userLinks.orEmpty(),
        manifestUrl = SlackMatching.manifestUrl
    )

    private fun incoming(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != MASK }

    /** Checks tokens with Slack; returns the workspace for the bot token. */
    suspend fun verifyTokens(botToken: String?, appToken: String?): SlackApiClient.AuthInfo? {
        if (botToken != null && !botToken.startsWith("xoxb-")) {
            throw ApiException(HttpStatusCode.BadRequest, "The bot token starts with xoxb- (OAuth & Permissions page).")
        }
        if (appToken != null && !appToken.startsWith("xapp-")) {
            throw ApiException(HttpStatusCode.BadRequest, "The app-level token starts with xapp- (Basic Information page).")
        }
        val info = botToken?.let {
            try { SlackApiClient.authTest(it) } catch (e: SlackApiException) { throw ApiException(HttpStatusCode.BadRequest, e.message ?: e.error) }
        }
        appToken?.let {
            try { SlackApiClient.openConnection(it) } catch (e: SlackApiException) {
                val msg = if (e.error == "invalid_auth") "Slack rejected the app-level token. Make sure Socket Mode is on and the token has connections:write." else e.message
                throw ApiException(HttpStatusCode.BadRequest, msg ?: e.error)
            }
        }
        return info
    }

    suspend fun save(team: Int, program: String, update: SlackIntegrationUpdate, username: String): SlackIntegrationRecord {
        val newBot = incoming(update.botToken)
        val newApp = incoming(update.appToken)
        val info = verifyTokens(newBot, newApp)
        val previous = load(team, program)
        if (update.enabled == true && (newBot ?: previous?.botToken).isNullOrBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Add the bot token before turning Slack on.")
        }
        update.rules?.let(::validateRules)
        val now = Instant.now()
        val rulesJson = update.rules?.let { JsonSupport.json.encodeToString(rulesSerializer, it) }
        val linksJson = update.userLinks?.let { JsonSupport.json.encodeToString(linksSerializer, it) }
        val tokensChanged = newBot != null || newApp != null

        transaction {
            val exists = SlackIntegrations.selectAll()
                .where { (SlackIntegrations.teamNumber eq team) and (SlackIntegrations.program eq program) }.any()
            if (!exists) {
                SlackIntegrations.insert {
                    it[teamNumber] = team
                    it[SlackIntegrations.program] = program
                    it[updatedAt] = now
                }
            }
            SlackIntegrations.update({ (SlackIntegrations.teamNumber eq team) and (SlackIntegrations.program eq program) }) {
                update.enabled?.let { v -> it[enabled] = v }
                newBot?.let { v -> it[botTokenEnc] = encrypt(v) }
                newApp?.let { v -> it[appTokenEnc] = encrypt(v) }
                info?.let { i ->
                    it[workspaceId] = i.teamId
                    it[workspaceName] = i.teamName
                    it[workspaceUrl] = i.url
                    it[botUserId] = i.userId
                    it[botId] = i.botId
                }
                rulesJson?.let { v -> it[SlackIntegrations.rulesJson] = v }
                linksJson?.let { v -> it[userLinksJson] = v }
                // A fresh token clears an "error" left by the old one, so the supervisor tries again.
                if (tokensChanged || update.enabled != null) {
                    it[status] = "connecting"
                    it[statusDetail] = ""
                }
                it[updatedAt] = now
                it[updatedBy] = username
            }
        }
        clearCache(team, program)
        return load(team, program)!!
    }

    private fun validateRules(rules: List<SlackChannelRule>) {
        if (rules.size > 100) throw ApiException(HttpStatusCode.BadRequest, "At most 100 channels can be watched.")
        if (rules.map { it.channelId }.toSet().size != rules.size) throw ApiException(HttpStatusCode.BadRequest, "A channel is listed twice.")
        rules.forEach { r ->
            if (!Regex("[CG][A-Z0-9]{2,30}").matches(r.channelId)) throw ApiException(HttpStatusCode.BadRequest, "Invalid channel id: ${r.channelId}")
            if (r.keywords.size > 50 || r.keywords.any { it.length > 100 }) throw ApiException(HttpStatusCode.BadRequest, "Too many or too long keywords.")
            if (r.audience.roles.any { role -> runCatching { com.obsidianscout.auth.UserRole.valueOf(role) }.isFailure }) {
                throw ApiException(HttpStatusCode.BadRequest, "Unknown role in channel audience.")
            }
        }
    }

    fun delete(team: Int, program: String) {
        transaction {
            SlackIntegrations.deleteWhere { (SlackIntegrations.teamNumber eq team) and (SlackIntegrations.program eq program) }
        }
        clearCache(team, program)
    }

    fun setStatus(team: Int, program: String, status: String, detail: String = "") {
        runCatching {
            transaction {
                SlackIntegrations.update({ (SlackIntegrations.teamNumber eq team) and (SlackIntegrations.program eq program) }) {
                    it[SlackIntegrations.status] = status
                    it[statusDetail] = detail.take(500)
                }
            }
        }.onFailure { log.debug("Slack status update failed: ${it.message}") }
    }

    fun touchLastEvent(team: Int, program: String) {
        runCatching {
            transaction {
                SlackIntegrations.update({ (SlackIntegrations.teamNumber eq team) and (SlackIntegrations.program eq program) }) {
                    it[lastEventAt] = Instant.now()
                }
            }
        }
    }

    // ---- Team members and links ----

    fun teamMembers(team: Int, program: String): List<SlackTeamMember> = readTransaction {
        Users.selectAll().where { (Users.teamNumber eq team) and (Users.program eq program) }.map {
            SlackTeamMember(it[Users.id].value.toString(), it[Users.username], it[Users.role], it[Users.email].orEmpty())
        }
    }

    suspend fun slackUsers(record: SlackIntegrationRecord): List<SlackUserInfo> =
        slackUsersCache.getOrLoad(key(record.teamNumber, record.program)) { SlackApiClient.users(record.botToken) }

    /** Obsidianscout user id -> Slack user id, matched by email. */
    suspend fun autoLinks(record: SlackIntegrationRecord): Map<String, String> =
        autoLinkCache.getOrLoad(key(record.teamNumber, record.program)) {
            val byEmail = runCatching { slackUsers(record) }.getOrDefault(emptyList())
                .filter { it.email.isNotBlank() }.associate { it.email.lowercase() to it.id }
            teamMembers(record.teamNumber, record.program)
                .mapNotNull { m -> byEmail[m.email.lowercase().trim()]?.let { m.id to it } }.toMap()
        }

    /** Slack user id -> Obsidianscout user id, with the admin's overrides applied. */
    suspend fun effectiveLinks(record: SlackIntegrationRecord): Map<String, String> {
        val merged = autoLinks(record).toMutableMap()
        record.userLinks.forEach { (userId, slackId) -> if (slackId.isBlank()) merged.remove(userId) else merged[userId] = slackId }
        return merged.entries.associate { (userId, slackId) -> slackId to userId }
    }

    suspend fun userLinkRows(record: SlackIntegrationRecord): SlackUserLinksResponse {
        val auto = autoLinks(record)
        val users = teamMembers(record.teamNumber, record.program).sortedBy { it.username.lowercase() }.map { m ->
            val override = record.userLinks[m.id]
            val auto1 = auto[m.id].orEmpty()
            SlackUserLinkRow(m.id, m.username, m.role, m.email, auto1, override, override ?: auto1)
        }
        val slackUsers = slackUsers(record).map { it.copy(email = "") }.sortedBy { it.name.lowercase() }
        return SlackUserLinksResponse(users, slackUsers)
    }

    // ---- Name lookups ----

    suspend fun userName(record: SlackIntegrationRecord, slackUserId: String): String =
        userNameCache.getOrLoad("${record.teamNumber}:$slackUserId") {
            runCatching { SlackApiClient.userName(record.botToken, slackUserId) }.getOrDefault(slackUserId)
        }

    suspend fun channelName(record: SlackIntegrationRecord, channelId: String): String =
        record.rules.firstOrNull { it.channelId == channelId }?.channelName?.takeIf { it.isNotBlank() }
            ?: channelNameCache.getOrLoad("${record.teamNumber}:$channelId") {
                runCatching { SlackApiClient.channelName(record.botToken, channelId) }.getOrDefault(channelId)
            }

    suspend fun userGroups(record: SlackIntegrationRecord): List<SlackUserGroupInfo> =
        groupCache.getOrLoad(key(record.teamNumber, record.program)) {
            runCatching { SlackApiClient.userGroups(record.botToken) }.getOrDefault(emptyList())
        }

    suspend fun groupMembers(record: SlackIntegrationRecord, groupId: String): Set<String> =
        groupMembersCache.getOrLoad("${key(record.teamNumber, record.program)}:$groupId") {
            runCatching { SlackApiClient.userGroupMembers(record.botToken, groupId) }.getOrDefault(emptySet())
        }

    // ---- Preferences ----

    fun preferences(userIds: Collection<String>): Map<String, String> {
        val uuids = userIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (uuids.isEmpty()) return emptyMap()
        return readTransaction {
            SlackUserPreferences.selectAll().where { SlackUserPreferences.userId inList uuids }
                .associate { it[SlackUserPreferences.userId].value.toString() to it[SlackUserPreferences.preference] }
        }
    }

    fun preferenceOf(userId: String): String = preferences(listOf(userId))[userId] ?: SlackPreference.ALL

    fun setPreference(userId: String, preference: String) {
        if (preference !in SlackPreference.values) throw ApiException(HttpStatusCode.BadRequest, "Preference must be all, mentions or none")
        val uuid = UUID.fromString(userId)
        transaction {
            val updated = SlackUserPreferences.update({ SlackUserPreferences.userId eq uuid }) {
                it[SlackUserPreferences.preference] = preference
                it[updatedAt] = Instant.now()
            }
            if (updated == 0) {
                SlackUserPreferences.insert {
                    it[SlackUserPreferences.userId] = EntityID(uuid, Users)
                    it[SlackUserPreferences.preference] = preference
                    it[updatedAt] = Instant.now()
                }
            }
        }
    }

    // ---- Notifications ----

    data class StoredNotification(
        val id: String,
        val teamNumber: Int,
        val program: String,
        val channelName: String,
        val authorName: String,
        val text: String,
        val permalink: String,
        val targets: Map<String, Boolean>,
        val push: Boolean,
        val createdAt: Instant
    ) {
        fun bannerFor(userId: String) = SlackBannerDto(
            id = id,
            channel = if (channelName.startsWith("#")) channelName else "#$channelName",
            author = authorName,
            text = text,
            url = permalink,
            mentioned = targets[userId] == true,
            createdAt = createdAt.toString()
        )
    }

    /** Inserts once per Slack event; returns the row, or null when another node (or a retry) already stored it. */
    fun insertNotification(
        team: Int, program: String, eventId: String, channelId: String, channelName: String,
        authorName: String, text: String, permalink: String, targets: Map<String, Boolean>, push: Boolean
    ): StoredNotification? {
        val now = Instant.now()
        return runCatching {
            transaction {
                val exists = SlackNotifications.selectAll().where {
                    (SlackNotifications.teamNumber eq team) and (SlackNotifications.program eq program) and
                        (SlackNotifications.slackEventId eq eventId)
                }.any()
                if (exists) return@transaction null
                val id = SlackNotifications.insert {
                    it[teamNumber] = team
                    it[SlackNotifications.program] = program
                    it[slackEventId] = eventId.take(128)
                    it[SlackNotifications.channelId] = channelId
                    it[SlackNotifications.channelName] = channelName.take(128)
                    it[SlackNotifications.authorName] = authorName.take(128)
                    it[SlackNotifications.text] = text
                    it[SlackNotifications.permalink] = permalink
                    it[targetsJson] = JsonSupport.json.encodeToString(targetsSerializer, targets)
                    it[SlackNotifications.push] = push
                    it[createdAt] = now
                } get SlackNotifications.id
                StoredNotification(id.value.toString(), team, program, channelName, authorName, text, permalink, targets, push, now)
            }
        }.getOrElse {
            // The unique index caught a concurrent insert of the same event.
            log.debug("Slack notification insert skipped: ${it.message}")
            null
        }
    }

    private fun rowToNotification(row: org.jetbrains.exposed.v1.core.ResultRow) = StoredNotification(
        id = row[SlackNotifications.id].value.toString(),
        teamNumber = row[SlackNotifications.teamNumber],
        program = row[SlackNotifications.program],
        channelName = row[SlackNotifications.channelName],
        authorName = row[SlackNotifications.authorName],
        text = row[SlackNotifications.text],
        permalink = row[SlackNotifications.permalink],
        targets = runCatching { JsonSupport.json.decodeFromString(targetsSerializer, row[SlackNotifications.targetsJson]) }.getOrDefault(emptyMap()),
        push = row[SlackNotifications.push],
        createdAt = row[SlackNotifications.createdAt]
    )

    fun notificationsSince(since: Instant): List<StoredNotification> = readTransaction {
        SlackNotifications.selectAll().where { SlackNotifications.createdAt greater since }
            .orderBy(SlackNotifications.createdAt).limit(500).map(::rowToNotification)
    }

    /** The caller's banners since [since], at most 10 minutes back. */
    fun recentFor(userId: String, since: Instant): List<SlackBannerDto> {
        val floor = Instant.now().minusSeconds(600)
        val from = if (since.isBefore(floor)) floor else since
        return readTransaction {
            SlackNotifications.selectAll().where {
                (SlackNotifications.createdAt greater from) and (SlackNotifications.targetsJson like "%\"$userId\"%")
            }.orderBy(SlackNotifications.createdAt).limit(50).map(::rowToNotification)
        }.filter { userId in it.targets }.map { it.bannerFor(userId) }
    }

    fun deleteOlderThan(cutoff: Instant): Int = transaction {
        SlackNotifications.deleteWhere { SlackNotifications.createdAt less cutoff }
    }

    fun deleteByEvent(team: Int, program: String, eventIdPrefix: String) {
        runCatching {
            transaction {
                SlackNotifications.deleteWhere {
                    (SlackNotifications.teamNumber eq team) and (SlackNotifications.program eq program) and
                        (SlackNotifications.slackEventId like "$eventIdPrefix%")
                }
            }
        }
    }
}
