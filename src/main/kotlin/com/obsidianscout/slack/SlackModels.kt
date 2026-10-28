package com.obsidianscout.slack

import kotlinx.serialization.Serializable

/** What in a watched channel raises a banner. */
@Serializable
enum class SlackTrigger { ALL_MESSAGES, MENTIONS }

/** Who sees a channel's banners. Directly @mentioned linked users always see their own. */
@Serializable
data class SlackAudience(
    val everyone: Boolean = true,
    /** [com.obsidianscout.auth.UserRole] names. */
    val roles: List<String> = emptyList(),
    val userIds: List<String> = emptyList()
)

/** One watched Slack channel and what in it notifies whom. */
@Serializable
data class SlackChannelRule(
    val channelId: String,
    /** Cached for display; refreshed from Slack when the admin opens the channel list. */
    val channelName: String = "",
    val isPrivate: Boolean = false,
    val trigger: SlackTrigger = SlackTrigger.MENTIONS,
    /** `@channel`, `@here` and `@everyone`. */
    val mentionChannel: Boolean = true,
    val userGroupIds: List<String> = emptyList(),
    /** A direct `@someone` notifies that person when they are linked to an Obsidianscout user. */
    val mentionLinkedUsers: Boolean = true,
    /** Case-insensitive substrings that also count as a match in [SlackTrigger.MENTIONS] mode. */
    val keywords: List<String> = emptyList(),
    val includeThreadReplies: Boolean = false,
    val audience: SlackAudience = SlackAudience(),
    /** Also send Web Push / FCM, for users who don't have the site or app open. */
    val push: Boolean = false
)

object SlackPreference {
    const val ALL = "all"
    const val MENTIONS = "mentions"
    const val NONE = "none"
    val values = setOf(ALL, MENTIONS, NONE)
}

// ---- API payloads ----

@Serializable
data class SlackIntegrationResponse(
    val configured: Boolean,
    val enabled: Boolean,
    val botTokenSet: Boolean,
    val appTokenSet: Boolean,
    val workspaceName: String,
    val workspaceUrl: String,
    val status: String,
    val statusDetail: String,
    val lastEventAt: String?,
    val rules: List<SlackChannelRule>,
    /** Admin overrides: Obsidianscout user id -> Slack user id ("" = never link). */
    val userLinks: Map<String, String>,
    val manifestUrl: String
)

@Serializable
data class SlackIntegrationUpdate(
    val enabled: Boolean? = null,
    /** Null, blank or the mask keeps the stored token. */
    val botToken: String? = null,
    val appToken: String? = null,
    val rules: List<SlackChannelRule>? = null,
    val userLinks: Map<String, String>? = null
)

@Serializable
data class SlackTestRequest(val botToken: String? = null, val appToken: String? = null)

@Serializable
data class SlackTestResponse(val ok: Boolean, val workspaceName: String = "", val message: String = "")

@Serializable
data class SlackChannelInfo(
    val id: String,
    val name: String,
    val isPrivate: Boolean,
    val isMember: Boolean,
    val memberCount: Int
)

@Serializable
data class SlackUserGroupInfo(val id: String, val handle: String, val name: String)

@Serializable
data class SlackUserGroupsResponse(val groups: List<SlackUserGroupInfo>, val message: String = "")

@Serializable
data class SlackUserInfo(val id: String, val name: String, val realName: String, val email: String = "")

@Serializable
data class SlackUserLinkRow(
    val userId: String,
    val username: String,
    val role: String,
    val email: String,
    /** Matched by email. */
    val autoSlackUserId: String,
    /** Admin override, or null when the email match is used. "" means never link. */
    val overrideSlackUserId: String?,
    /** The link in effect. */
    val slackUserId: String
)

@Serializable
data class SlackUserLinksResponse(val users: List<SlackUserLinkRow>, val slackUsers: List<SlackUserInfo>)

@Serializable
data class SlackBannerDto(
    val type: String = "slack_notification",
    val id: String,
    val channel: String,
    val author: String,
    val text: String,
    val url: String,
    val mentioned: Boolean,
    val createdAt: String
)

@Serializable
data class SlackPreferenceDto(val preference: String)

class SlackApiException(val error: String, message: String = error) : RuntimeException(message)
