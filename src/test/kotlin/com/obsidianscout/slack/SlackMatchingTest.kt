package com.obsidianscout.slack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackMatchingTest {
    private val alice = SlackTeamUser("a", "ADMIN")
    private val bob = SlackTeamUser("b", "SCOUT")
    private val cara = SlackTeamUser("c", "ANALYTICS")
    private val users = listOf(alice, bob, cara)
    /** Slack id -> Obsidianscout id. */
    private val links = mapOf("UALICE" to "a", "UBOB" to "b")

    private fun targets(
        rule: SlackChannelRule,
        raw: String,
        prefs: Map<String, String> = emptyMap(),
        groupMembers: Map<String, Set<String>> = emptyMap(),
        author: String? = "UOTHER"
    ) = SlackMatching.resolveTargets(rule, raw, SlackMatching.render(raw), users, links, groupMembers, prefs, author)

    @Test
    fun parsesAllMentionKinds() {
        val m = SlackMatching.parseMentions("hi <@UALICE> <@UBOB|bob> <!subteam^S123|@drive> <!here> <!channel>")
        assertEquals(setOf("UALICE", "UBOB"), m.users)
        assertEquals(setOf("S123"), m.groups)
        assertEquals(setOf("here", "channel"), m.special)
    }

    @Test
    fun rendersMarkupAsPlainText() {
        val raw = "<@UALICE> see <https://x.org/a|the doc> in <#C1|strategy> &amp; <!here> <!subteam^S1|@drive> <mailto:a@b.c|a@b.c>"
        val out = SlackMatching.render(raw, userNames = mapOf("UALICE" to "Alice"))
        assertEquals("@Alice see the doc in #strategy & @here @drive a@b.c", out)
    }

    @Test
    fun rendersUnknownUserAndClipsLongText() {
        assertEquals("@someone hi", SlackMatching.render("<@UX> hi"))
        val long = "x".repeat(SlackMatching.MAX_TEXT + 50)
        assertEquals(SlackMatching.MAX_TEXT, SlackMatching.render(long).length)
    }

    @Test
    fun allMessagesGoesToAudience() {
        val rule = SlackChannelRule("C1", trigger = SlackTrigger.ALL_MESSAGES)
        assertEquals(mapOf("a" to false, "b" to false, "c" to false), targets(rule, "hello"))
    }

    @Test
    fun mentionsModeIgnoresPlainMessages() {
        assertTrue(targets(SlackChannelRule("C1"), "hello").isEmpty())
    }

    @Test
    fun channelMentionNotifiesAudienceAsMentioned() {
        val rule = SlackChannelRule("C1", audience = SlackAudience(everyone = false, roles = listOf("SCOUT")))
        assertEquals(mapOf("b" to true), targets(rule, "<!channel> queue up"))
        assertTrue(targets(rule.copy(mentionChannel = false), "<!channel> queue up").isEmpty())
    }

    @Test
    fun directMentionReachesLinkedUserOutsideAudience() {
        val rule = SlackChannelRule("C1", audience = SlackAudience(everyone = false, userIds = listOf("c")))
        assertEquals(mapOf("a" to true), targets(rule, "<@UALICE> can you check?"))
        assertTrue(targets(rule.copy(mentionLinkedUsers = false), "<@UALICE> can you check?").isEmpty())
        // Unlinked Slack users don't map to anyone.
        assertTrue(targets(rule, "<@UNOBODY> hi").isEmpty())
    }

    @Test
    fun selectedGroupMentionNotifiesAudienceAndMarksMembers() {
        val rule = SlackChannelRule("C1", userGroupIds = listOf("S1"), audience = SlackAudience(everyone = false, roles = listOf("ANALYTICS")))
        val result = targets(rule, "<!subteam^S1|@drive> go", groupMembers = mapOf("S1" to setOf("UBOB")))
        assertEquals(mapOf("c" to false, "b" to true), result)
        // A group that wasn't selected does nothing.
        assertTrue(targets(rule, "<!subteam^S9|@pit> go").isEmpty())
    }

    @Test
    fun keywordsMatchCaseInsensitively() {
        val rule = SlackChannelRule("C1", keywords = listOf("Queue"), audience = SlackAudience(everyone = false, userIds = listOf("a")))
        assertEquals(mapOf("a" to false), targets(rule, "time to QUEUE for Q12"))
    }

    @Test
    fun authorNeverGetsOwnMessage() {
        val rule = SlackChannelRule("C1", trigger = SlackTrigger.ALL_MESSAGES)
        assertEquals(setOf("b", "c"), targets(rule, "hi", author = "UALICE").keys)
    }

    @Test
    fun personalPreferencesFilter() {
        val rule = SlackChannelRule("C1", trigger = SlackTrigger.ALL_MESSAGES)
        val prefs = mapOf("a" to SlackPreference.NONE, "b" to SlackPreference.MENTIONS)
        assertEquals(mapOf("c" to false), targets(rule, "hello", prefs))
        assertEquals(mapOf("b" to true, "c" to false), targets(rule, "<@UBOB> hello <@UALICE>", prefs))
    }

    @Test
    fun permalinks() {
        assertEquals("https://t.slack.com/archives/C1/p1700000000123456",
            SlackMatching.permalink("https://t.slack.com/", "C1", "1700000000.123456", null))
        assertEquals("https://t.slack.com/archives/C1/p1700000001000000?thread_ts=1700000000.123456&cid=C1",
            SlackMatching.permalink("https://t.slack.com/", "C1", "1700000001.000000", "1700000000.123456"))
        assertEquals("", SlackMatching.permalink("javascript:alert(1)", "C1", "1.2", null))
    }
}
