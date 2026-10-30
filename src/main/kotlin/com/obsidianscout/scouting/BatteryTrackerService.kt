package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.BatteryTrackers
import com.obsidianscout.db.readTransaction
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant

@Serializable
data class BatteryTrackerDoc(
    val dataJson: String,
    val updatedBy: String = "",
    val updatedAt: Long = 0L
)

@Serializable
data class BatteryInventoryRequest(val dataJson: String)

@Serializable
data class BatteryEventPlanRequest(val eventKey: String, val dataJson: String)

/**
 * Drive-team battery tracker. Scoped to the user's own team, like the match checklist.
 * The team's batteries live in one inventory doc (eventKey = ""); each event has a plan doc holding
 * per-match skips, pins and the battery actually used. The match-by-match rotation is computed by the
 * clients (js/services/battery-rotation.js, lib/services/battery_rotation.dart) from those two docs.
 *
 * Both docs are edited live through the config collaboration hub (see ConfigCollabScope.Batteries); the REST
 * endpoints here are the offline fallback.
 *
 * Inventory JSON:  {"batteries":{"<id>":{"name","type":"match|pit","order","retired","notes","createdAt"}}}
 * Event plan JSON: {"matches":{"<matchKey>":{"skipped":{"<id>":{"by","at"}},"pinned":"<id>","used":"<id>"}},
 *                   "startAt":"<id>","autoMarkUsed":true}
 */
object BatteryTrackerService {
    private val log = LoggerFactory.getLogger(BatteryTrackerService::class.java)
    private const val MAX_JSON_LENGTH = 64 * 1024
    private val KEY_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun ownerKey(teamNumber: Int, program: String) = MatchChecklistService.ownerKey(teamNumber, program)

    private fun ownerKey(session: UserSession) = ownerKey(session.teamNumber, session.program)

    private fun validate(json: String) {
        if (json.length > MAX_JSON_LENGTH) throw ApiException(HttpStatusCode.PayloadTooLarge, "Battery data is too large")
        runCatching { Json.parseToJsonElement(json) }
            .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "Battery data must be valid JSON") }
    }

    fun isValidEventKey(value: String) = KEY_PATTERN.matches(value)

    fun load(owner: String, eventKey: String): BatteryTrackerDoc? = readTransaction {
        BatteryTrackers.selectAll().where {
            (BatteryTrackers.ownerKey eq owner) and (BatteryTrackers.eventKey eq eventKey)
        }.firstOrNull()?.let {
            BatteryTrackerDoc(it[BatteryTrackers.dataJson], it[BatteryTrackers.updatedBy], it[BatteryTrackers.updatedAt].toEpochMilli())
        }
    }

    fun store(owner: String, eventKey: String, json: String, editor: String): BatteryTrackerDoc {
        validate(json)
        val now = Instant.now()
        transaction {
            BatteryTrackers.upsert(BatteryTrackers.ownerKey, BatteryTrackers.eventKey) {
                it[ownerKey] = owner
                it[this.eventKey] = eventKey
                it[dataJson] = json
                it[updatedBy] = editor.take(64)
                it[updatedAt] = now
            }
        }
        com.obsidianscout.widgets.WidgetPushService.notifyOwner(owner, "battery")
        return BatteryTrackerDoc(json, editor, now.toEpochMilli())
    }

    fun getInventory(session: UserSession): BatteryTrackerDoc =
        load(ownerKey(session), "") ?: BatteryTrackerDoc("{}")

    fun saveInventory(session: UserSession, request: BatteryInventoryRequest): BatteryTrackerDoc =
        store(ownerKey(session), "", request.dataJson, session.username)

    fun getEventPlan(session: UserSession, eventKey: String): BatteryTrackerDoc {
        if (!isValidEventKey(eventKey)) throw ApiException(HttpStatusCode.BadRequest, "Invalid eventKey")
        return load(ownerKey(session), eventKey) ?: BatteryTrackerDoc("{}")
    }

    fun saveEventPlan(session: UserSession, request: BatteryEventPlanRequest): BatteryTrackerDoc {
        if (!isValidEventKey(request.eventKey)) throw ApiException(HttpStatusCode.BadRequest, "Invalid eventKey")
        return store(ownerKey(session), request.eventKey, request.dataJson, session.username)
    }

    // ------------------------------------------------------------------ match / event key maintenance
    // Event plans store match keys inside their JSON, so the canonical-key repairs in MatchCanonical and
    // EventTeamCanonical call these to keep skips and used batteries attached to the right match.

    private fun matchesOf(doc: JsonObject): JsonObject? = doc["matches"] as? JsonObject

    private fun parseObject(json: String): JsonObject? = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()

    /** Match keys starting with [prefix] referenced by any event plan. Must run inside a transaction. */
    fun referencedMatchKeys(prefix: String = ""): Set<String> = buildSet {
        BatteryTrackers.selectAll().where { BatteryTrackers.eventKey neq "" }.forEach { row ->
            matchesOf(parseObject(row[BatteryTrackers.dataJson]) ?: return@forEach)?.keys
                ?.filter { it.startsWith(prefix) }
                ?.forEach { add(it) }
        }
    }

    /**
     * Renames the entry for [oldKey] to [newKey] in every event plan. When both exist, the one with a
     * recorded `used` battery wins, otherwise the existing [newKey] entry is kept. Must run inside a transaction.
     */
    fun remapMatchKey(oldKey: String, newKey: String, owners: Set<String>? = null) {
        if (oldKey == newKey) return
        val needle = "\"$oldKey\""
        BatteryTrackers.selectAll().where { BatteryTrackers.eventKey neq "" }.toList()
            .filter { it[BatteryTrackers.dataJson].contains(needle) && (owners == null || it[BatteryTrackers.ownerKey] in owners) }
            .forEach { row ->
                val doc = parseObject(row[BatteryTrackers.dataJson]) ?: return@forEach
                val matches = matchesOf(doc) ?: return@forEach
                val moving = matches[oldKey] ?: return@forEach
                val existing = matches[newKey]
                val keep = when {
                    existing == null -> moving
                    hasUsed(moving) && !hasUsed(existing) -> moving
                    else -> existing
                }
                if (existing != null) log.warn("Battery plan conflict on $newKey: kept ${if (keep === moving) "entry from $oldKey" else "existing entry"}")
                val renamed = buildJsonObject {
                    matches.forEach { (k, v) -> if (k != oldKey && k != newKey) put(k, v) }
                    put(newKey, keep)
                }
                val next = JsonObject(doc + ("matches" to renamed))
                BatteryTrackers.update({ BatteryTrackers.id eq row[BatteryTrackers.id] }) {
                    it[dataJson] = next.toString()
                }
            }
    }

    private fun hasUsed(entry: JsonElement): Boolean =
        ((entry as? JsonObject)?.get("used") as? kotlinx.serialization.json.JsonPrimitive)?.content?.isNotEmpty() == true

    /**
     * Moves event plans from [oldKey] to [newKey]. When the owner already has a plan for [newKey] the two are
     * merged: per-match entries from both are kept, with the newer doc winning on the same match key.
     * Match keys inside are re-keyed afterwards by the match-key remap. Must run inside a transaction.
     */
    fun moveEvent(oldKey: String, newKey: String, owners: Collection<String>? = null) {
        if (oldKey == newKey || oldKey.isEmpty() || newKey.isEmpty()) return
        BatteryTrackers.selectAll().where { BatteryTrackers.eventKey eq oldKey }.toList()
            .filter { owners == null || it[BatteryTrackers.ownerKey] in owners }
            .forEach { row ->
            val clash = BatteryTrackers.selectAll().where {
                (BatteryTrackers.ownerKey eq row[BatteryTrackers.ownerKey]) and (BatteryTrackers.eventKey eq newKey)
            }.limit(1).firstOrNull()
            if (clash == null) {
                BatteryTrackers.update({ BatteryTrackers.id eq row[BatteryTrackers.id] }) { it[eventKey] = newKey }
                return@forEach
            }
            val rowNewer = row[BatteryTrackers.updatedAt] > clash[BatteryTrackers.updatedAt]
            val (older, newer) = if (rowNewer) clash to row else row to clash
            val olderDoc = parseObject(older[BatteryTrackers.dataJson]) ?: JsonObject(emptyMap())
            val newerDoc = parseObject(newer[BatteryTrackers.dataJson]) ?: JsonObject(emptyMap())
            val mergedMatches = JsonObject((matchesOf(olderDoc) ?: emptyMap()) + (matchesOf(newerDoc) ?: emptyMap()))
            val merged = JsonObject(olderDoc + newerDoc + ("matches" to mergedMatches))
            BatteryTrackers.update({ BatteryTrackers.id eq clash[BatteryTrackers.id] }) {
                it[dataJson] = merged.toString()
                it[updatedAt] = maxOf(row[BatteryTrackers.updatedAt], clash[BatteryTrackers.updatedAt])
            }
            BatteryTrackers.deleteWhere { BatteryTrackers.id eq row[BatteryTrackers.id] }
        }
    }
}
