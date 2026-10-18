package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.MatchChecklists
import com.obsidianscout.db.readTransaction
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant

@Serializable
data class MatchChecklistDoc(
    val dataJson: String,
    val updatedBy: String = "",
    val updatedAt: Long = 0L
)

@Serializable
data class MatchChecklistSummary(
    val eventKey: String,
    val matchKey: String,
    val dataJson: String,
    val updatedBy: String,
    val updatedAt: Long
)

@Serializable
data class MatchChecklistTemplateRequest(val dataJson: String)

@Serializable
data class MatchChecklistUpdateRequest(
    val eventKey: String,
    val matchKey: String,
    val dataJson: String
)

/**
 * Drive-team pre-match / on-field checklists. Scoped to the user's own team (not the scouting alliance),
 * since the checklist is about the team's own robot. The template and per-match completions share a table;
 * the template lives at (eventKey = "", matchKey = TEMPLATE_KEY).
 *
 * Both are edited live through the config collaboration hub (see ConfigCollabScope.Checklist); the REST
 * endpoints here are the offline fallback and the source for per-event progress.
 *
 * Template JSON:   {"sections":{"pre":{"title":..},"field":{"title":..}},"fields":[{"id","section","text","priority"}]}
 * Completion JSON: {"checked":{"<itemId>":{"by","at"}},"notes":"..","fields":[]}
 */
object MatchChecklistService {
    const val TEMPLATE_KEY = "__template__"
    private const val MAX_JSON_LENGTH = 64 * 1024
    // Keys end up in collab room keys, which are ':'-separated.
    private val KEY_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun ownerKey(teamNumber: Int, program: String) = "team_${teamNumber}_$program"

    private fun ownerKey(session: UserSession) = ownerKey(session.teamNumber, session.program)

    private fun validate(json: String) {
        if (json.length > MAX_JSON_LENGTH) throw ApiException(HttpStatusCode.PayloadTooLarge, "Checklist is too large")
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }
            .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "Checklist data must be valid JSON") }
    }

    fun isValidKey(value: String) = KEY_PATTERN.matches(value) && value != TEMPLATE_KEY

    private fun validateKey(name: String, value: String) {
        if (!isValidKey(value)) throw ApiException(HttpStatusCode.BadRequest, "Invalid $name")
    }

    fun load(owner: String, eventKey: String, matchKey: String): MatchChecklistDoc? = readTransaction {
        MatchChecklists.selectAll().where {
            (MatchChecklists.ownerKey eq owner) and
            (MatchChecklists.eventKey eq eventKey) and
            (MatchChecklists.matchKey eq matchKey)
        }.firstOrNull()?.let {
            MatchChecklistDoc(it[MatchChecklists.dataJson], it[MatchChecklists.updatedBy], it[MatchChecklists.updatedAt].toEpochMilli())
        }
    }

    fun store(owner: String, eventKey: String, matchKey: String, json: String, editor: String): MatchChecklistDoc {
        validate(json)
        val now = Instant.now()
        transaction {
            MatchChecklists.upsert(MatchChecklists.ownerKey, MatchChecklists.eventKey, MatchChecklists.matchKey) {
                it[ownerKey] = owner
                it[this.eventKey] = eventKey
                it[this.matchKey] = matchKey
                it[dataJson] = json
                it[updatedBy] = editor.take(64)
                it[updatedAt] = now
            }
        }
        com.obsidianscout.widgets.WidgetPushService.notifyOwner(owner, "checklist")
        return MatchChecklistDoc(json, editor, now.toEpochMilli())
    }

    fun getTemplate(session: UserSession): MatchChecklistDoc =
        load(ownerKey(session), "", TEMPLATE_KEY) ?: MatchChecklistDoc("{}")

    fun saveTemplate(session: UserSession, request: MatchChecklistTemplateRequest): MatchChecklistDoc =
        store(ownerKey(session), "", TEMPLATE_KEY, request.dataJson, session.username)

    fun getMatch(session: UserSession, eventKey: String, matchKey: String): MatchChecklistDoc {
        validateKey("eventKey", eventKey)
        validateKey("matchKey", matchKey)
        return load(ownerKey(session), eventKey, matchKey) ?: MatchChecklistDoc("{}")
    }

    fun saveMatch(session: UserSession, request: MatchChecklistUpdateRequest): MatchChecklistDoc {
        validateKey("eventKey", request.eventKey)
        validateKey("matchKey", request.matchKey)
        return store(ownerKey(session), request.eventKey, request.matchKey, request.dataJson, session.username)
    }

    /** All filled-out checklists for an event, used to show per-match progress. */
    fun listForEvent(session: UserSession, eventKey: String): List<MatchChecklistSummary> {
        validateKey("eventKey", eventKey)
        val owner = ownerKey(session)
        return readTransaction {
            MatchChecklists.selectAll().where {
                (MatchChecklists.ownerKey eq owner) and (MatchChecklists.eventKey eq eventKey)
            }.map {
                MatchChecklistSummary(
                    eventKey = it[MatchChecklists.eventKey],
                    matchKey = it[MatchChecklists.matchKey],
                    dataJson = it[MatchChecklists.dataJson],
                    updatedBy = it[MatchChecklists.updatedBy],
                    updatedAt = it[MatchChecklists.updatedAt].toEpochMilli()
                )
            }
        }
    }
}
