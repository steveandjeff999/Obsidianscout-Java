package com.obsidianscout.slack

/** Mentions found in a Slack message's raw text. */
data class SlackMentions(
    val users: Set<String>,
    val groups: Set<String>,
    /** "here", "channel", "everyone". */
    val special: Set<String>
)

/** A team member, as far as targeting needs to know. */
data class SlackTeamUser(val id: String, val role: String)

/**
 * Pure helpers for reading Slack message markup and deciding who gets a banner. No I/O, so they are unit tested
 * directly (see SlackMatchingTest).
 */
object SlackMatching {
    private val userRe = Regex("""<@([UW][A-Z0-9]+)(?:\|([^>]*))?>""")
    private val groupRe = Regex("""<!subteam\^([A-Z0-9]+)(?:\|([^>]*))?>""")
    private val specialRe = Regex("""<!(here|channel|everyone)(?:\|[^>]*)?>""")
    private val channelRe = Regex("""<#([CG][A-Z0-9]+)(?:\|([^>]*))?>""")
    private val dateRe = Regex("""<!date\^[^|>]*\|([^>]*)>""")
    private val linkRe = Regex("""<([^@#!][^|>]*)(?:\|([^>]*))?>""")
    const val MAX_TEXT = 500

    fun parseMentions(text: String): SlackMentions = SlackMentions(
        users = userRe.findAll(text).map { it.groupValues[1] }.toSet(),
        groups = groupRe.findAll(text).map { it.groupValues[1] }.toSet(),
        special = specialRe.findAll(text).map { it.groupValues[1] }.toSet()
    )

    /** Slack markup to plain text: mentions become names, links become their labels. Clipped to [MAX_TEXT]. */
    fun render(
        text: String,
        userNames: Map<String, String> = emptyMap(),
        groupHandles: Map<String, String> = emptyMap(),
        channelNames: Map<String, String> = emptyMap()
    ): String {
        var out = text
        out = userRe.replace(out) { m ->
            "@" + (userNames[m.groupValues[1]] ?: m.groupValues[2].ifBlank { "someone" })
        }
        out = groupRe.replace(out) { m ->
            val label = m.groupValues[2].removePrefix("@")
            "@" + (groupHandles[m.groupValues[1]] ?: label.ifBlank { "group" })
        }
        out = specialRe.replace(out) { m -> "@" + m.groupValues[1] }
        out = channelRe.replace(out) { m ->
            "#" + (channelNames[m.groupValues[1]] ?: m.groupValues[2].ifBlank { "channel" })
        }
        out = dateRe.replace(out) { m -> m.groupValues[1] }
        out = linkRe.replace(out) { m -> m.groupValues[2].ifBlank { m.groupValues[1] }.removePrefix("mailto:") }
        out = out.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim()
        return if (out.length > MAX_TEXT) out.take(MAX_TEXT - 1).trimEnd() + "…" else out
    }

    fun inAudience(user: SlackTeamUser, audience: SlackAudience): Boolean =
        audience.everyone || user.role in audience.roles || user.id in audience.userIds

    /**
     * Who should see a banner for this message, with whether each one was mentioned. Empty when the message
     * doesn't match the rule.
     *
     * @param rawText the message as Slack sent it (with `<@U..>` markup)
     * @param plainText [render]ed text, for keyword matching
     * @param links Slack user id -> Obsidianscout user id
     * @param groupMembers user group id -> Slack user ids, for the selected groups that were mentioned
     * @param preferences Obsidianscout user id -> [SlackPreference]; missing means "all"
     * @param authorSlackId the poster, who never gets a banner for their own message
     */
    fun resolveTargets(
        rule: SlackChannelRule,
        rawText: String,
        plainText: String,
        users: List<SlackTeamUser>,
        links: Map<String, String>,
        groupMembers: Map<String, Set<String>>,
        preferences: Map<String, String>,
        authorSlackId: String?
    ): Map<String, Boolean> {
        val mentions = parseMentions(rawText)
        val teamIds = users.map { it.id }.toSet()
        val audience = users.filter { inAudience(it, rule.audience) }.map { it.id }
        val result = LinkedHashMap<String, Boolean>()
        fun add(id: String, mentioned: Boolean) {
            if (id in teamIds) result[id] = (result[id] ?: false) || mentioned
        }

        if (rule.trigger == SlackTrigger.ALL_MESSAGES) audience.forEach { add(it, false) }

        if (mentions.special.isNotEmpty() && (rule.mentionChannel || rule.trigger == SlackTrigger.ALL_MESSAGES)) {
            audience.forEach { add(it, true) }
        }

        val groupsHit = mentions.groups.intersect(rule.userGroupIds.toSet())
        if (groupsHit.isNotEmpty()) {
            audience.forEach { add(it, false) }
            groupsHit.flatMap { groupMembers[it].orEmpty() }.mapNotNull { links[it] }.forEach { add(it, true) }
        }

        val lower = plainText.lowercase()
        if (rule.keywords.any { it.isNotBlank() && lower.contains(it.trim().lowercase()) }) {
            audience.forEach { add(it, false) }
        }

        if (rule.mentionLinkedUsers) mentions.users.mapNotNull { links[it] }.forEach { add(it, true) }

        authorSlackId?.let { links[it] }?.let { result.remove(it) }

        return result.filter { (id, mentioned) ->
            when (preferences[id] ?: SlackPreference.ALL) {
                SlackPreference.NONE -> false
                SlackPreference.MENTIONS -> mentioned
                else -> true
            }
        }
    }

    /** Link to a message in the Slack web/desktop client. [workspaceUrl] is auth.test's `url`. */
    fun permalink(workspaceUrl: String, channelId: String, ts: String, threadTs: String?): String {
        val base = workspaceUrl.trimEnd('/')
        if (!base.startsWith("https://")) return ""
        val link = "$base/archives/$channelId/p${ts.replace(".", "")}"
        return if (threadTs != null && threadTs != ts) "$link?thread_ts=$threadTs&cid=$channelId" else link
    }

    /** The app manifest teams paste into Slack's "Create app from manifest". */
    val manifestJson: String = """
        {"display_information":{"name":"Obsidianscout","description":"Shows Slack messages as banners in Obsidianscout"},
        "features":{"bot_user":{"display_name":"Obsidianscout","always_online":true}},
        "oauth_config":{"scopes":{"bot":["channels:read","channels:history","channels:join","groups:read","groups:history","usergroups:read","users:read","users:read.email","team:read"]}},
        "settings":{"event_subscriptions":{"bot_events":["message.channels","message.groups"]},"interactivity":{"is_enabled":false},"org_deploy_enabled":false,"socket_mode_enabled":true,"token_rotation_enabled":false}}
    """.trimIndent().replace("\n", "")

    val manifestUrl: String =
        "https://api.slack.com/apps?new_app=1&manifest_json=" + java.net.URLEncoder.encode(manifestJson, Charsets.UTF_8)
}
