package com.obsidianscout.slack

import com.obsidianscout.db.PushNotificationService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Turns a Slack `message` event into a stored banner for the users a channel rule picks. */
object SlackEventProcessor {
    /** Message subtypes that are real posts. Edits, joins, topic changes and the like are ignored. */
    private val postSubtypes = setOf(null, "thread_broadcast", "file_share", "bot_message", "me_message")

    /** One key per Slack message, so a resent envelope or a second socket during a handover stores it once. */
    fun messageKey(channelId: String, ts: String) = "$channelId:$ts"

    suspend fun handle(team: Int, program: String, payload: JsonObject) {
        val event = payload.obj("event") ?: return
        if (event.str("type") != "message") return
        val record = SlackIntegrationService.cached(team, program)?.takeIf { it.ready } ?: return
        val channelId = event.str("channel") ?: return
        val subtype = event.str("subtype")

        if (subtype == "message_deleted") {
            event.str("deleted_ts")?.let { SlackIntegrationService.deleteByEvent(team, program, messageKey(channelId, it)) }
            return
        }
        if (subtype !in postSubtypes) return
        if (record.botUserId.isNotEmpty() && event.str("user") == record.botUserId) return
        if (record.botId.isNotEmpty() && event.str("bot_id") == record.botId) return

        val rule = record.rules.firstOrNull { it.channelId == channelId } ?: return
        val ts = event.str("ts") ?: return
        val threadTs = event.str("thread_ts")
        val isReply = threadTs != null && threadTs != ts && subtype != "thread_broadcast"
        if (isReply && !rule.includeThreadReplies) return

        SlackIntegrationService.touchLastEvent(team, program)

        val raw = event.str("text").orEmpty()
        val hasFiles = (event["files"] as? JsonArray)?.isNotEmpty() == true
        if (raw.isBlank() && !hasFiles) return

        val mentions = SlackMatching.parseMentions(raw)
        val userNames = mentions.users.associateWith { SlackIntegrationService.userName(record, it) }
        val groupHandles = if (mentions.groups.isEmpty()) emptyMap()
            else SlackIntegrationService.userGroups(record).associate { it.id to it.handle }
        val plain = SlackMatching.render(raw, userNames, groupHandles).ifBlank { if (hasFiles) "Shared a file" else "" }

        val links = SlackIntegrationService.effectiveLinks(record)
        val groupMembers = mentions.groups.intersect(rule.userGroupIds.toSet())
            .associateWith { SlackIntegrationService.groupMembers(record, it) }
        val members = SlackIntegrationService.teamMembers(team, program)
        val authorSlackId = event.str("user")
        val targets = SlackMatching.resolveTargets(
            rule = rule,
            rawText = raw,
            plainText = plain,
            users = members.map { SlackTeamUser(it.id, it.role) },
            links = links,
            groupMembers = groupMembers,
            preferences = SlackIntegrationService.preferences(members.map { it.id }),
            authorSlackId = authorSlackId
        )
        if (targets.isEmpty()) return

        val authorName = authorSlackId?.let { SlackIntegrationService.userName(record, it) }
            ?: event.str("username")
            ?: event.obj("bot_profile")?.str("name")
            ?: "Slack"
        val stored = SlackIntegrationService.insertNotification(
            team = team,
            program = program,
            eventId = messageKey(channelId, ts),
            channelId = channelId,
            channelName = SlackIntegrationService.channelName(record, channelId),
            authorName = authorName,
            text = plain,
            permalink = SlackMatching.permalink(record.workspaceUrl, channelId, ts, threadTs),
            targets = targets,
            push = rule.push
        ) ?: return

        SlackNotificationRelay.deliver(stored)
        if (stored.push) PushNotificationService.sendSlackNotification(stored)
    }
}
