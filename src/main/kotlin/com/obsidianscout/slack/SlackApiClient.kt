package com.obsidianscout.slack

import com.obsidianscout.config.JsonSupport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Thin wrapper over the Slack Web API calls the integration needs. Tokens are never logged. */
object SlackApiClient {
    private const val BASE = "https://slack.com/api/"

    val client = HttpClient(CIO) {
        install(WebSockets) { pingIntervalMillis = 20_000 }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
    }

    /** Calls [method]; throws [SlackApiException] when Slack answers `ok: false`. Waits out one rate limit. */
    suspend fun call(token: String, method: String, params: Map<String, String> = emptyMap()): JsonObject {
        repeat(2) { attempt ->
            val response = client.post(BASE + method) {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(FormDataContent(Parameters.build { params.forEach { (k, v) -> append(k, v) } }))
            }
            if (response.status == HttpStatusCode.TooManyRequests && attempt == 0) {
                val wait = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.coerceIn(1, 30) ?: 5
                delay(wait * 1000)
                return@repeat
            }
            val body = runCatching { JsonSupport.json.parseToJsonElement(response.bodyAsText()).jsonObject }
                .getOrElse { throw SlackApiException("bad_response", "Slack returned HTTP ${response.status.value}") }
            if (!body.bool("ok")) {
                val error = body.str("error") ?: "unknown_error"
                throw SlackApiException(error, describeError(error, body))
            }
            return body
        }
        throw SlackApiException("ratelimited", "Slack is rate limiting requests; try again in a minute.")
    }

    /** Follows `response_metadata.next_cursor` and collects [arrayKey] across pages, up to [maxItems]. */
    suspend fun paged(
        token: String, method: String, arrayKey: String, params: Map<String, String>, maxItems: Int = 5000
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var cursor = ""
        do {
            val page = call(token, method, params + ("limit" to "200") + (if (cursor.isNotEmpty()) mapOf("cursor" to cursor) else emptyMap()))
            page[arrayKey]?.jsonArray?.mapTo(out) { it.jsonObject }
            cursor = page.obj("response_metadata")?.str("next_cursor").orEmpty()
        } while (cursor.isNotEmpty() && out.size < maxItems)
        return out
    }

    data class AuthInfo(val teamId: String, val teamName: String, val url: String, val userId: String, val botId: String)

    suspend fun authTest(botToken: String): AuthInfo {
        val r = call(botToken, "auth.test")
        return AuthInfo(
            teamId = r.str("team_id").orEmpty(),
            teamName = r.str("team").orEmpty(),
            url = r.str("url").orEmpty(),
            userId = r.str("user_id").orEmpty(),
            botId = r.str("bot_id").orEmpty()
        )
    }

    /** A one-time WebSocket URL for Socket Mode. Needs the app-level token. */
    suspend fun openConnection(appToken: String): String =
        call(appToken, "apps.connections.open").str("url") ?: throw SlackApiException("no_url", "Slack did not return a socket URL")

    suspend fun channels(botToken: String): List<SlackChannelInfo> =
        paged(botToken, "conversations.list", "channels", mapOf("types" to "public_channel,private_channel", "exclude_archived" to "true"))
            .map {
                SlackChannelInfo(
                    id = it.str("id").orEmpty(),
                    name = it.str("name").orEmpty(),
                    isPrivate = it.bool("is_private"),
                    isMember = it.bool("is_member"),
                    memberCount = (it["num_members"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
                )
            }
            .sortedBy { it.name }

    suspend fun join(botToken: String, channelId: String) {
        call(botToken, "conversations.join", mapOf("channel" to channelId))
    }

    suspend fun userGroups(botToken: String): List<SlackUserGroupInfo> =
        call(botToken, "usergroups.list", mapOf("include_disabled" to "false"))["usergroups"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            SlackUserGroupInfo(o.str("id").orEmpty(), o.str("handle").orEmpty(), o.str("name").orEmpty())
        }

    suspend fun userGroupMembers(botToken: String, groupId: String): Set<String> =
        call(botToken, "usergroups.users.list", mapOf("usergroup" to groupId))["users"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()

    suspend fun users(botToken: String): List<SlackUserInfo> =
        paged(botToken, "users.list", "members", emptyMap())
            .filter { !it.bool("deleted") && !it.bool("is_bot") && it.str("id") != "USLACKBOT" }
            .map {
                val profile = it.obj("profile")
                SlackUserInfo(
                    id = it.str("id").orEmpty(),
                    name = profile?.str("display_name")?.ifBlank { null } ?: it.str("name").orEmpty(),
                    realName = profile?.str("real_name").orEmpty(),
                    email = profile?.str("email").orEmpty()
                )
            }

    suspend fun userName(botToken: String, userId: String): String {
        val user = call(botToken, "users.info", mapOf("user" to userId)).obj("user") ?: return userId
        val profile = user.obj("profile")
        return profile?.str("display_name")?.ifBlank { null }
            ?: profile?.str("real_name")?.ifBlank { null }
            ?: user.str("name") ?: userId
    }

    suspend fun channelName(botToken: String, channelId: String): String =
        call(botToken, "conversations.info", mapOf("channel" to channelId)).obj("channel")?.str("name") ?: channelId

    /** Errors after which reconnecting cannot help until an admin changes something. */
    val fatalErrors = setOf(
        "invalid_auth", "not_authed", "account_inactive", "token_revoked", "token_expired",
        "missing_scope", "not_allowed_token_type", "invalid_token", "team_access_not_granted", "app_uninstalled"
    )

    private fun describeError(error: String, body: JsonObject): String = when (error) {
        "invalid_auth", "not_authed", "invalid_token" -> "Slack rejected the token. Check that it was copied in full."
        "not_allowed_token_type" -> "Wrong kind of token: the bot token starts with xoxb- and the app-level token with xapp-."
        "missing_scope" -> "The Slack app is missing a permission (${body.str("needed") ?: "see manifest"}). Recreate it from the manifest, or add the scope and reinstall."
        "token_revoked", "app_uninstalled", "account_inactive" -> "The Slack app was uninstalled or its token revoked. Reinstall it and paste the new tokens."
        "method_not_supported_for_channel_type" -> "Private channels can't be joined by the app. In Slack, type /invite @Obsidianscout in the channel."
        "paid_teams_only", "plan_upgrade_required" -> "Slack user groups need a paid Slack plan."
        "channel_not_found" -> "Slack channel not found."
        else -> "Slack error: $error"
    }
}
