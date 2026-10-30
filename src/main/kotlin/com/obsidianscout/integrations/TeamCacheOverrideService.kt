package com.obsidianscout.integrations

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.MatchChecklists
import com.obsidianscout.db.MatchPlans
import com.obsidianscout.db.MatchPredictions
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.SharedLinks
import com.obsidianscout.db.TeamCacheOverrides
import com.obsidianscout.routes.EventRecord
import com.obsidianscout.routes.MatchRecord
import com.obsidianscout.routes.TeamRecord
import com.obsidianscout.scouting.AllianceSelectionService
import com.obsidianscout.scouting.EntrySync
import com.obsidianscout.scouting.MatchChecklistService
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

@Serializable
data class TeamCacheOverrideBackupDto(
    val teamNumber: Int,
    val program: String = "FRC",
    val kind: String,
    val eventKey: String,
    val recordKey: String,
    val removed: Boolean = false,
    val patchJson: String = "{}",
    val updatedAt: Long,
    val created: Boolean = false
)

/**
 * Lets each team change the event cache without touching what other teams see. Saves and deletes here write only to
 * [TeamCacheOverrides] and the calling team's own rows, and the reads lay that team's changes over the shared rows.
 * Row reads return ordinary [ResultRow]s, so callers read `row[ApiMatches.redTeams]` as they would a shared row.
 * Nothing here writes the shared tables: only API sync does.
 * Every read and helper here must run inside a transaction unless it opens its own.
 */
object TeamCacheOverrideService {
    const val KIND_EVENT = "event"
    const val KIND_TEAM = "team"
    const val KIND_MATCH = "match"

    internal class Override(
        val id: UUID,
        val eventKey: String,
        val recordKey: String,
        val removed: Boolean,
        val created: Boolean,
        val patch: JsonObject,
        val updatedAt: Instant
    )

    private val teamListSerializer = ListSerializer(String.serializer())
    private val emptyPatch = JsonObject(emptyMap())

    private fun normalizeProgram(program: String) = program.trim().uppercase()

    private fun scope(team: Int, program: String, kind: String): Op<Boolean> =
        (TeamCacheOverrides.teamNumber eq team) and
            (TeamCacheOverrides.program eq normalizeProgram(program)) and
            (TeamCacheOverrides.kind eq kind)

    private fun toOverride(row: ResultRow) = Override(
        id = row[TeamCacheOverrides.id].value,
        eventKey = row[TeamCacheOverrides.eventKey],
        recordKey = row[TeamCacheOverrides.recordKey],
        removed = row[TeamCacheOverrides.removed],
        created = row[TeamCacheOverrides.created],
        patch = runCatching { JsonSupport.json.parseToJsonElement(row[TeamCacheOverrides.patchJson]).jsonObject }.getOrDefault(emptyPatch),
        updatedAt = row[TeamCacheOverrides.updatedAt]
    )

    private fun overridesForEvents(team: Int, program: String, kind: String, eventKeys: Collection<String>): List<Override> =
        if (eventKeys.isEmpty()) emptyList()
        else TeamCacheOverrides.selectAll().where { scope(team, program, kind) and (TeamCacheOverrides.eventKey inList eventKeys) }.map(::toOverride)

    private fun overridesForRecords(team: Int, program: String, kind: String, recordKeys: Collection<String>): List<Override> =
        if (recordKeys.isEmpty()) emptyList()
        else TeamCacheOverrides.selectAll().where { scope(team, program, kind) and (TeamCacheOverrides.recordKey inList recordKeys) }.map(::toOverride)

    // ── Patch fields ─────────────────────────────────────────────────────────

    private fun JsonObject.text(field: String): String? = (this[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonObject.int(field: String): Int? = text(field)?.toIntOrNull()
    private fun JsonObject.long(field: String): Long? = text(field)?.toLongOrNull()
    private fun JsonObject.double(field: String): Double? = text(field)?.toDoubleOrNull()
    private fun JsonObject.teamList(field: String): String =
        JsonSupport.json.encodeToString(teamListSerializer, (this[field] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content })

    private fun decodeTeamList(text: String): List<String> =
        runCatching { JsonSupport.json.decodeFromString(teamListSerializer, text) }.getOrDefault(emptyList())

    private fun teamListJson(teams: List<String>): JsonElement = JsonArray(teams.map(::JsonPrimitive))

    private val eventFields: Map<String, Column<*>> = mapOf(
        "name" to ApiEvents.name, "startDate" to ApiEvents.startDate, "endDate" to ApiEvents.endDate, "timezone" to ApiEvents.timezone
    )
    private val teamFields: Map<String, Column<*>> = mapOf(
        "name" to ApiTeams.name, "nickname" to ApiTeams.nickname, "city" to ApiTeams.city, "state" to ApiTeams.state,
        "country" to ApiTeams.country, "opr" to ApiTeams.opr, "epa" to ApiTeams.epa, "exp" to ApiTeams.match13Exp
    )
    private val matchFields: Map<String, Column<*>> = mapOf(
        "setNumber" to ApiMatches.setNumber, "matchNumber" to ApiMatches.matchNumber, "scheduledTime" to ApiMatches.scheduledTime,
        "actualTime" to ApiMatches.actualTime, "redTeams" to ApiMatches.redTeams, "blueTeams" to ApiMatches.blueTeams
    )

    private fun isBlank(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isBlank() || value == "[]" || value == "{}"
        is Double -> value == 0.0
        else -> false
    }

    /**
     * What an override changes on [base]. This is how a team's own record merges with the API's: from the moment the
     * API publishes it, on every read, the API wins and the team's copy only fills fields the API left blank.
     */
    private fun effectivePatch(base: ResultRow?, o: Override, fields: Map<String, Column<*>>): JsonObject {
        if (base == null || !o.created) return o.patch
        return JsonObject(o.patch.filterKeys { field -> fields[field]?.let { isBlank(base.getOrNull(it)) } ?: false })
    }

    /** A copy of [base] (or an empty row of [table]) with [values] replaced, readable like any selected row. */
    private fun rowOf(table: Table, base: ResultRow?, values: Map<Column<*>, Any?>): ResultRow {
        val data = HashMap<Expression<*>, Any?>()
        table.columns.forEach { column -> data[column] = base?.getOrNull(column) }
        data.putAll(values)
        return ResultRow.createAndFillValues(data)
    }

    private fun eventRowOf(base: ResultRow?, o: Override): ResultRow {
        val p = effectivePatch(base, o, eventFields)
        val values = HashMap<Column<*>, Any?>()
        values[ApiEvents.updatedAt] = o.updatedAt
        if (base == null) {
            values[ApiEvents.id] = EntityID(o.id, ApiEvents)
            values[ApiEvents.eventKey] = o.eventKey
            values[ApiEvents.name] = o.eventKey
            values[ApiEvents.year] = o.eventKey.take(4).toIntOrNull() ?: java.time.Year.now().value
            values[ApiEvents.dataJson] = "{\"manual\":true}"
        }
        if ("name" in p) values[ApiEvents.name] = p.text("name").orEmpty()
        if ("year" in p) p.int("year")?.let { values[ApiEvents.year] = it }
        if ("eventCode" in p) values[ApiEvents.eventCode] = p.text("eventCode")
        if ("startDate" in p) values[ApiEvents.startDate] = p.text("startDate")
        if ("endDate" in p) values[ApiEvents.endDate] = p.text("endDate")
        if ("timezone" in p) values[ApiEvents.timezone] = p.text("timezone")
        return rowOf(ApiEvents, base, values)
    }

    private fun teamRowOf(base: ResultRow?, o: Override): ResultRow {
        val p = effectivePatch(base, o, teamFields)
        val values = HashMap<Column<*>, Any?>()
        values[ApiTeams.updatedAt] = o.updatedAt
        if (base == null) {
            values[ApiTeams.id] = EntityID(o.id, ApiTeams)
            values[ApiTeams.eventKey] = o.eventKey
            values[ApiTeams.teamKey] = o.recordKey
            values[ApiTeams.teamNumber] = o.recordKey.filter { it.isDigit() }.toIntOrNull() ?: 0
            values[ApiTeams.dataJson] = "{}"
        }
        if ("teamNumber" in p) p.int("teamNumber")?.let { values[ApiTeams.teamNumber] = it }
        if ("name" in p) values[ApiTeams.name] = p.text("name")
        if ("nickname" in p) values[ApiTeams.nickname] = p.text("nickname")
        if ("city" in p) values[ApiTeams.city] = p.text("city")
        if ("state" in p) values[ApiTeams.state] = p.text("state")
        if ("country" in p) values[ApiTeams.country] = p.text("country")
        if ("opr" in p) values[ApiTeams.opr] = p.double("opr")
        if ("epa" in p) values[ApiTeams.epa] = p.double("epa")
        if ("exp" in p) values[ApiTeams.match13Exp] = p.double("exp")
        return rowOf(ApiTeams, base, values)
    }

    private fun matchRowOf(base: ResultRow?, o: Override): ResultRow {
        val p = effectivePatch(base, o, matchFields)
        val values = HashMap<Column<*>, Any?>()
        values[ApiMatches.updatedAt] = o.updatedAt
        if (base == null) {
            values[ApiMatches.id] = EntityID(o.id, ApiMatches)
            values[ApiMatches.matchKey] = o.recordKey
            values[ApiMatches.eventKey] = o.eventKey
            values[ApiMatches.compLevel] = "qm"
            values[ApiMatches.redTeams] = "[]"
            values[ApiMatches.blueTeams] = "[]"
            values[ApiMatches.dataJson] = "{}"
        }
        if ("compLevel" in p) p.text("compLevel")?.let { values[ApiMatches.compLevel] = it }
        if ("setNumber" in p) values[ApiMatches.setNumber] = p.int("setNumber")
        if ("matchNumber" in p) values[ApiMatches.matchNumber] = p.int("matchNumber")
        if ("scheduledTime" in p) values[ApiMatches.scheduledTime] = p.long("scheduledTime")
        if ("actualTime" in p) values[ApiMatches.actualTime] = p.long("actualTime")
        if ("redTeams" in p) values[ApiMatches.redTeams] = p.teamList("redTeams")
        if ("blueTeams" in p) values[ApiMatches.blueTeams] = p.teamList("blueTeams")
        if ("dataJson" in p) p.text("dataJson")?.let { values[ApiMatches.dataJson] = it }
        return rowOf(ApiMatches, base, values)
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** Lays [overrides] over [shared]. With [addOwn], records only this team has are appended. */
    private fun overlay(
        shared: List<ResultRow>,
        overrides: List<Override>,
        keyOf: (ResultRow) -> String,
        build: (ResultRow?, Override) -> ResultRow,
        addOwn: Boolean
    ): List<ResultRow> {
        if (overrides.isEmpty()) return shared
        val byKey = overrides.associateBy { it.recordKey }
        val seen = mutableSetOf<String>()
        val merged = shared.mapNotNull { row ->
            val key = keyOf(row).lowercase().trim()
            seen += key
            val override = byKey[key] ?: return@mapNotNull row
            if (override.removed) null else build(row, override)
        }
        if (!addOwn) return merged
        return merged + overrides.filter { !it.removed && it.recordKey !in seen }.map { build(null, it) }
    }

    /** This team's view of an event's matches. */
    fun matchRows(eventKey: String, team: Int, program: String): List<ResultRow> {
        val key = eventKey.lowercase().trim()
        return overlay(
            ApiMatches.selectAll().where { ApiMatches.eventKey eq key }.toList(),
            overridesForEvents(team, program, KIND_MATCH, listOf(key)),
            { it[ApiMatches.matchKey] }, ::matchRowOf, addOwn = true
        )
    }

    /** This team's view of the matches with these keys. */
    fun matchRowsByKeys(matchKeys: Collection<String>, team: Int, program: String): List<ResultRow> {
        if (matchKeys.isEmpty()) return emptyList()
        val keys = matchKeys.toList()
        return overlay(
            keys.chunked(500).flatMap { chunk -> ApiMatches.selectAll().where { ApiMatches.matchKey inList chunk }.toList() },
            keys.chunked(500).flatMap { chunk -> overridesForRecords(team, program, KIND_MATCH, chunk.map { it.lowercase().trim() }) },
            { it[ApiMatches.matchKey] }, ::matchRowOf, addOwn = true
        )
    }

    fun matchRow(matchKey: String, team: Int, program: String): ResultRow? =
        matchRowsByKeys(listOf(matchKey), team, program).firstOrNull()

    /** This team's view of an event's teams. */
    fun teamRows(eventKey: String, team: Int, program: String): List<ResultRow> {
        val key = eventKey.lowercase().trim()
        return overlay(
            ApiTeams.selectAll().where { ApiTeams.eventKey eq key }.toList(),
            overridesForEvents(team, program, KIND_TEAM, listOf(key)),
            { it[ApiTeams.teamKey] }, ::teamRowOf, addOwn = true
        ).sortedBy { it[ApiTeams.teamNumber] }
    }

    /** Applies this team's changes to team rows it already selected (records it added itself are not appended). */
    fun patchTeamRows(rows: List<ResultRow>, team: Int, program: String): List<ResultRow> {
        val events = rows.map { it[ApiTeams.eventKey].lowercase().trim() }.distinct()
        val keyed = rows.groupBy { it[ApiTeams.eventKey].lowercase().trim() }
        return events.flatMap { event ->
            overlay(keyed[event].orEmpty(), overridesForEvents(team, program, KIND_TEAM, listOf(event)), { it[ApiTeams.teamKey] }, ::teamRowOf, addOwn = false)
        }
    }

    /** This team's view of one event row, with no regard to whether it hid the event from its list. */
    fun eventRow(eventKey: String, team: Int, program: String): ResultRow? {
        val key = eventKey.lowercase().trim()
        val shared = ApiEvents.selectAll().where { ApiEvents.eventKey eq key }.limit(1).firstOrNull()
        return patchEventRow(shared, key, team, program)
    }

    /** [shared] (the row stored for [eventKey], if any) with this team's changes applied. */
    fun patchEventRow(shared: ResultRow?, eventKey: String, team: Int, program: String): ResultRow? {
        val key = shared?.get(ApiEvents.eventKey)?.lowercase()?.trim() ?: eventKey.lowercase().trim()
        val override = overridesForEvents(team, program, KIND_EVENT, listOf(key)).firstOrNull()?.takeUnless { it.removed } ?: return shared
        return eventRowOf(shared, override)
    }

    /** A team's own rows by the id they were handed out under: rows it added have no shared row to look up. */
    fun ownTeamRow(id: UUID, team: Int, program: String): ResultRow? =
        TeamCacheOverrides.selectAll().where { (TeamCacheOverrides.id eq id) and scope(team, program, KIND_TEAM) }
            .limit(1).firstOrNull()?.let { teamRowOf(null, toOverride(it)) }

    fun ownMatchRow(id: UUID, team: Int, program: String): ResultRow? =
        TeamCacheOverrides.selectAll().where { (TeamCacheOverrides.id eq id) and scope(team, program, KIND_MATCH) }
            .limit(1).firstOrNull()?.let { matchRowOf(null, toOverride(it)) }

    fun ownEventRow(id: UUID, team: Int, program: String): ResultRow? =
        TeamCacheOverrides.selectAll().where { (TeamCacheOverrides.id eq id) and scope(team, program, KIND_EVENT) }
            .limit(1).firstOrNull()?.takeUnless { it[TeamCacheOverrides.removed] }?.let { eventRowOf(null, toOverride(it)) }

    fun patchMatchRow(shared: ResultRow, team: Int, program: String): ResultRow? {
        val key = shared[ApiMatches.matchKey].lowercase().trim()
        return overlay(listOf(shared), overridesForRecords(team, program, KIND_MATCH, listOf(key)), { it[ApiMatches.matchKey] }, ::matchRowOf, addOwn = false).firstOrNull()
    }

    /** What a team changed in the event list: applied to the list, and the keys it adds to or hides from it. */
    class EventOverlay internal constructor(private val overrides: List<Override>, editedEvents: Set<String>) {
        /** Events on this team's list because of its changes, including any it edited a team or match in. */
        val visibleKeys: Set<String> = overrides.filterNot { it.removed }.map { it.recordKey }.toSet() + editedEvents
        val hiddenKeys: Set<String> = overrides.filter { it.removed }.map { it.recordKey }.toSet()

        fun apply(events: List<EventRecord>, year: Int?): List<EventRecord> {
            if (overrides.isEmpty()) return events
            val byKey = overrides.associateBy { it.recordKey }
            val seen = events.map { it.eventKey.lowercase().trim() }.toSet()
            val merged = events.mapNotNull { event ->
                val override = byKey[event.eventKey.lowercase().trim()] ?: return@mapNotNull event
                when {
                    override.removed -> null
                    override.created -> patchEvent(event, override.patch.filterBlanksOf(event))
                    else -> patchEvent(event, override.patch)
                }
            }
            val own = overrides.filter { !it.removed && it.recordKey !in seen }
                .map { patchEvent(EventRecord(eventKey = it.recordKey, name = it.recordKey, year = it.recordKey.take(4).toIntOrNull() ?: 0, timezone = "UTC"), it.patch) }
                .filter { year == null || it.year == year }
            return merged + own
        }
    }

    /** [effectivePatch] for an event record: only the fields the API left blank. */
    private fun JsonObject.filterBlanksOf(event: EventRecord): JsonObject = JsonObject(filterKeys { field ->
        when (field) {
            "name" -> event.name.isBlank()
            "startDate" -> event.startDate.isNullOrBlank()
            "endDate" -> event.endDate.isNullOrBlank()
            "timezone" -> event.timezone.isNullOrBlank() || event.timezone == "UTC"
            else -> false
        }
    })

    private fun patchEvent(event: EventRecord, p: JsonObject) = event.copy(
        name = if ("name" in p) p.text("name").orEmpty() else event.name,
        year = p.int("year") ?: event.year,
        eventCode = if ("eventCode" in p) p.text("eventCode") else event.eventCode,
        startDate = if ("startDate" in p) p.text("startDate") else event.startDate,
        endDate = if ("endDate" in p) p.text("endDate") else event.endDate,
        timezone = if ("timezone" in p) p.text("timezone")?.takeIf { it.isNotBlank() } ?: "UTC" else event.timezone
    )

    fun eventOverlay(team: Int, program: String): EventOverlay {
        val rows = TeamCacheOverrides.selectAll().where {
            (TeamCacheOverrides.teamNumber eq team) and (TeamCacheOverrides.program eq normalizeProgram(program))
        }.toList()
        val (events, records) = rows.partition { it[TeamCacheOverrides.kind] == KIND_EVENT }
        val edited = records.filterNot { it[TeamCacheOverrides.removed] }.map { it[TeamCacheOverrides.eventKey] }.toSet()
        return EventOverlay(events.map(::toOverride), edited)
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /** Saves a team's change. Nothing left to record (the edit matches the shared row) drops the override. */
    private fun store(
        team: Int,
        program: String,
        kind: String,
        eventKey: String,
        recordKey: String,
        patch: JsonObject,
        removed: Boolean = false,
        created: Boolean = false
    ) {
        val existing = TeamCacheOverrides.selectAll().where {
            scope(team, program, kind) and (TeamCacheOverrides.eventKey eq eventKey) and (TeamCacheOverrides.recordKey eq recordKey)
        }.limit(1).firstOrNull()
        val text = JsonSupport.wireJson.encodeToString(JsonObject.serializer(), patch)
        val now = Instant.now()
        when {
            !removed && patch.isEmpty() -> existing?.let { row -> TeamCacheOverrides.deleteWhere { TeamCacheOverrides.id eq row[TeamCacheOverrides.id] } }
            existing == null -> TeamCacheOverrides.insert {
                it[teamNumber] = team
                it[TeamCacheOverrides.program] = normalizeProgram(program)
                it[TeamCacheOverrides.kind] = kind
                it[TeamCacheOverrides.eventKey] = eventKey
                it[TeamCacheOverrides.recordKey] = recordKey
                it[TeamCacheOverrides.removed] = removed
                it[TeamCacheOverrides.created] = created
                it[patchJson] = text
                it[updatedAt] = now
            }
            else -> TeamCacheOverrides.update({ TeamCacheOverrides.id eq existing[TeamCacheOverrides.id] }) {
                it[TeamCacheOverrides.removed] = removed
                it[TeamCacheOverrides.created] = created
                it[patchJson] = text
                it[updatedAt] = now
            }
        }
    }

    /** Fields of [changes] that differ from [stored]. With no [shared] record everything is kept. */
    private fun diff(hasShared: Boolean, changes: List<Triple<String, JsonElement, Any?>>): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        changes.forEach { (field, value, stored) ->
            val incoming = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            val same = when (stored) {
                is List<*> -> value is JsonArray && value.mapNotNull { (it as? JsonPrimitive)?.content } == stored
                else -> incoming == stored?.toString()
            }
            if (!hasShared || !same) fields[field] = value
        }
        return JsonObject(fields)
    }

    private fun eventPatch(shared: ResultRow?, event: EventRecord): JsonObject {
        val has = shared != null
        val storedTimezone = shared?.get(ApiEvents.timezone)?.takeIf { it.isNotBlank() } ?: "UTC"
        return diff(has, listOfNotNull(
            Triple("name", JsonPrimitive(event.name), shared?.get(ApiEvents.name)),
            Triple("year", JsonPrimitive(event.year), shared?.get(ApiEvents.year)),
            if (has) null else Triple("eventCode", JsonPrimitive(event.eventCode), null),
            Triple("startDate", JsonPrimitive(event.startDate), shared?.get(ApiEvents.startDate)),
            Triple("endDate", JsonPrimitive(event.endDate), shared?.get(ApiEvents.endDate)),
            Triple("timezone", JsonPrimitive(event.timezone?.takeIf { it.isNotBlank() } ?: "UTC"), storedTimezone)
        ))
    }

    /** Blank form fields mean "not edited" for a shared team, so they never wipe what the API sent. */
    private fun teamPatch(shared: ResultRow?, team: TeamRecord): JsonObject {
        val has = shared != null
        return diff(has, listOfNotNull(
            if (has) null else Triple("teamNumber", JsonPrimitive(team.teamNumber), null),
            team.name.takeIf { !has || it != null }?.let { Triple("name", JsonPrimitive(it), shared?.get(ApiTeams.name)) },
            team.nickname.takeIf { !has || it != null }?.let { Triple("nickname", JsonPrimitive(it), shared?.get(ApiTeams.nickname)) },
            team.city.takeIf { !has || it != null }?.let { Triple("city", JsonPrimitive(it), shared?.get(ApiTeams.city)) },
            team.state.takeIf { !has || it != null }?.let { Triple("state", JsonPrimitive(it), shared?.get(ApiTeams.state)) },
            team.country.takeIf { !has || it != null }?.let { Triple("country", JsonPrimitive(it), shared?.get(ApiTeams.country)) },
            team.opr.takeIf { !has || it != null }?.let { Triple("opr", JsonPrimitive(it), shared?.get(ApiTeams.opr)) },
            team.epa.takeIf { !has || it != null }?.let { Triple("epa", JsonPrimitive(it), shared?.get(ApiTeams.epa)) },
            team.exp.takeIf { !has || it != null }?.let { Triple("exp", JsonPrimitive(it), shared?.get(ApiTeams.match13Exp)) }
        ))
    }

    /** As with teams: a time or number the form left empty keeps the API's value. */
    private fun matchPatch(shared: ResultRow?, match: MatchRecord): JsonObject {
        val has = shared != null
        return diff(has, listOfNotNull(
            Triple("compLevel", JsonPrimitive(match.compLevel), shared?.get(ApiMatches.compLevel)),
            match.setNumber.takeIf { !has || it != null }?.let { Triple("setNumber", JsonPrimitive(it), shared?.get(ApiMatches.setNumber)) },
            match.matchNumber.takeIf { !has || it != null }?.let { Triple("matchNumber", JsonPrimitive(it), shared?.get(ApiMatches.matchNumber)) },
            match.scheduledTime.takeIf { !has || it != null }?.let { Triple("scheduledTime", JsonPrimitive(it), shared?.get(ApiMatches.scheduledTime)) },
            match.actualTime.takeIf { !has || it != null }?.let { Triple("actualTime", JsonPrimitive(it), shared?.get(ApiMatches.actualTime)) },
            Triple("redTeams", teamListJson(match.redTeams), shared?.get(ApiMatches.redTeams)?.let(::decodeTeamList)),
            Triple("blueTeams", teamListJson(match.blueTeams), shared?.get(ApiMatches.blueTeams)?.let(::decodeTeamList))
        ))
    }

    private fun sharedEvent(key: String) = ApiEvents.selectAll().where { ApiEvents.eventKey eq key }.limit(1).firstOrNull()
    private fun sharedMatch(key: String) = ApiMatches.selectAll().where { ApiMatches.matchKey eq key }.limit(1).firstOrNull()

    /** A team admin's save of an event: their own copy, or their own edits to the shared one. */
    fun saveEvent(session: UserSession, event: EventRecord): EventRecord = transaction {
        val key = event.eventKey.lowercase().trim()
        val eventCode = event.eventCode ?: key.removePrefix(event.year.toString())
        // Typed "okok" while the API's "2026okok" exists: this is that event, not a second one.
        val shared = sharedEvent(key) ?: EventTeamCanonical.findEquivalentEvent(key, event.year, eventCode)
        val storedKey = shared?.get(ApiEvents.eventKey) ?: key
        val saved = event.copy(eventKey = storedKey, eventCode = shared?.get(ApiEvents.eventCode) ?: eventCode)
        store(session.teamNumber, session.program, KIND_EVENT, storedKey, storedKey, eventPatch(shared, saved), created = shared == null)
        saved
    }

    /**
     * Edits an event for this team. A shared event's key is what every team's data hangs on, so only a team's own event
     * can change key, and that moves only this team's rows.
     */
    fun renameEvent(session: UserSession, oldKey: String, event: EventRecord): EventRecord = transaction {
        val team = session.teamNumber
        val program = session.program
        val oldNorm = oldKey.lowercase().trim()
        val newNorm = event.eventKey.lowercase().trim()
        val newCode = event.eventCode ?: newNorm.removePrefix(event.year.toString())
        val shared = sharedEvent(oldNorm)
        val own = overridesForEvents(team, program, KIND_EVENT, listOf(oldNorm)).firstOrNull()
        if (shared == null && own == null) {
            throw ApiException(HttpStatusCode.NotFound, "Event not found: $oldNorm")
        }
        if (shared != null) {
            if (newNorm != oldNorm) {
                throw ApiException(HttpStatusCode.Conflict, "This event is shared with other teams, so its key can't be changed. You can still edit its name, dates and timezone.")
            }
            val saved = event.copy(eventKey = oldNorm, eventCode = shared[ApiEvents.eventCode] ?: newCode)
            store(team, program, KIND_EVENT, oldNorm, oldNorm, eventPatch(shared, saved))
            return@transaction saved
        }
        if (newNorm != oldNorm) {
            val taken = sharedEvent(newNorm) != null || overridesForEvents(team, program, KIND_EVENT, listOf(newNorm)).any { !it.removed }
            if (taken) throw ApiException(HttpStatusCode.Conflict, "An event with key '$newNorm' already exists")
            moveTeamEvent(team, program, oldNorm, newNorm)
        }
        val saved = event.copy(eventKey = newNorm, eventCode = newCode)
        store(team, program, KIND_EVENT, newNorm, newNorm, eventPatch(null, saved), created = true)
        saved
    }

    /** Hides an event from this team and deletes this team's scouting for it. Other teams keep theirs. */
    fun deleteEvent(session: UserSession, eventKey: String): Boolean {
        val team = session.teamNumber
        val program = normalizeProgram(session.program)
        return transaction {
            val key = eventKey.lowercase().trim()
            val matchIds = ScoutingEntries.select(ScoutingEntries.id)
                .where { (ScoutingEntries.eventKey eq key) and (ScoutingEntries.ownerTeamNumber eq team) and (ScoutingEntries.program eq program) }
                .map { it[ScoutingEntries.id].value.toString() }
            val pitIds = PitScoutingEntries.select(PitScoutingEntries.id)
                .where { (PitScoutingEntries.eventKey eq key) and (PitScoutingEntries.ownerTeamNumber eq team) and (PitScoutingEntries.program eq program) }
                .map { it[PitScoutingEntries.id].value.toString() }
            ScoutingEntries.deleteWhere { (ScoutingEntries.eventKey eq key) and (ScoutingEntries.ownerTeamNumber eq team) and (ScoutingEntries.program eq program) }
            PitScoutingEntries.deleteWhere { (PitScoutingEntries.eventKey eq key) and (PitScoutingEntries.ownerTeamNumber eq team) and (PitScoutingEntries.program eq program) }
            EntrySync.recordDeletions(EntrySync.KIND_MATCH, matchIds, team, program)
            EntrySync.recordDeletions(EntrySync.KIND_PIT, pitIds, team, program)

            val cleared = TeamCacheOverrides.deleteWhere {
                (TeamCacheOverrides.teamNumber eq team) and (TeamCacheOverrides.program eq program) and (TeamCacheOverrides.eventKey eq key)
            }
            val shared = sharedEvent(key)
            if (shared != null) store(team, program, KIND_EVENT, key, key, emptyPatch, removed = true)
            shared != null || cleared > 0 || matchIds.isNotEmpty() || pitIds.isNotEmpty()
        }
    }

    fun saveTeam(session: UserSession, team: TeamRecord): TeamRecord = transaction {
        val eventKey = team.eventKey.lowercase().trim()
        val teamKey = EventTeamCanonical.canonicalTeamKey(team.teamKey, session.program, team.teamNumber)
        val prefix = EventTeamCanonical.teamPrefix(session.program)
        val shared = ApiTeams.selectAll().where {
            (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamKey eq teamKey)
        }.limit(1).firstOrNull()
            // Same team stored under a legacy key (bare number, or the old frc default in an FTC event).
            ?: ApiTeams.selectAll().where {
                (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamNumber eq team.teamNumber)
            }.toList().firstOrNull { row ->
                val k = row[ApiTeams.teamKey].lowercase()
                k == team.teamNumber.toString() || (k == "frc${team.teamNumber}" && prefix == "ftc" && row[ApiTeams.dataJson].trim() == "{}")
            }
        val recordKey = shared?.get(ApiTeams.teamKey)?.lowercase()?.trim() ?: teamKey
        store(session.teamNumber, session.program, KIND_TEAM, eventKey, recordKey, teamPatch(shared, team), created = shared == null)
        team.copy(eventKey = eventKey, teamKey = teamKey)
    }

    fun deleteTeam(session: UserSession, eventKey: String, teamKey: String): Boolean = transaction {
        val eKey = eventKey.lowercase().trim()
        val tKey = teamKey.lowercase().trim()
        val shared = ApiTeams.selectAll().where { (ApiTeams.eventKey eq eKey) and (ApiTeams.teamKey eq tKey) }.limit(1).firstOrNull()
        val own = overridesForEvents(session.teamNumber, session.program, KIND_TEAM, listOf(eKey)).any { it.recordKey == tKey }
        when {
            shared != null -> store(session.teamNumber, session.program, KIND_TEAM, eKey, tKey, emptyPatch, removed = true)
            own -> store(session.teamNumber, session.program, KIND_TEAM, eKey, tKey, emptyPatch)
        }
        shared != null || own
    }

    fun saveMatch(session: UserSession, match: MatchRecord): MatchRecord = transaction {
        val team = session.teamNumber
        val program = session.program
        val eventKey = match.eventKey.lowercase().trim()
        val previousKey = match.matchKey.lowercase().trim()
        val identity = MatchCanonical.identityFrom(
            MatchSyncRecord(
                // Only fall back to the submitted key when no match number was sent; otherwise an edit
                // that changes the number would be overridden by the number embedded in the old key.
                matchKey = if (match.matchNumber == null) previousKey else "",
                eventKey = eventKey,
                compLevel = match.compLevel.ifBlank { "qm" },
                setNumber = match.setNumber,
                matchNumber = match.matchNumber
            )
        )
        val generatedKey = identity.toMatchKey()
        val saved = match.copy(
            matchKey = generatedKey,
            eventKey = eventKey,
            compLevel = identity.compLevel,
            setNumber = identity.setNumber,
            matchNumber = identity.matchNumber,
            redTeams = match.redTeams.map { EventTeamCanonical.canonicalTeamKey(it, program) }.filter { it.isNotBlank() },
            blueTeams = match.blueTeams.map { EventTeamCanonical.canonicalTeamKey(it, program) }.filter { it.isNotBlank() }
        )
        val shared = sharedMatch(generatedKey)
        val renamedFrom = previousKey.takeIf { it.isNotBlank() && it != generatedKey }
        val previous = renamedFrom?.let(::sharedMatch)

        // A renumbered match keeps the results its shared row carried.
        val patch = matchPatch(shared, saved).let { p ->
            if (shared == null && previous != null) JsonObject(p + ("dataJson" to JsonPrimitive(previous[ApiMatches.dataJson]))) else p
        }
        store(team, program, KIND_MATCH, eventKey, generatedKey, patch, created = shared == null)
        if (renamedFrom != null) {
            if (previous != null) {
                store(team, program, KIND_MATCH, previous[ApiMatches.eventKey], renamedFrom, emptyPatch, removed = true)
            } else {
                store(team, program, KIND_MATCH, eventKey, renamedFrom, emptyPatch)
            }
            MatchCanonical.remapMatchKeyForTeam(renamedFrom, generatedKey, team, program)
        }
        saved
    }

    fun deleteMatch(session: UserSession, matchKey: String): Boolean = transaction {
        val mKey = matchKey.lowercase().trim()
        val shared = sharedMatch(mKey)
        val own = overridesForRecords(session.teamNumber, session.program, KIND_MATCH, listOf(mKey)).firstOrNull()
        when {
            shared != null -> store(session.teamNumber, session.program, KIND_MATCH, shared[ApiMatches.eventKey], mKey, emptyPatch, removed = true)
            own != null -> store(session.teamNumber, session.program, KIND_MATCH, own.eventKey, mKey, emptyPatch)
        }
        shared != null || own != null
    }

    /** Moves one team's data for its own event to a new key. Nobody else's rows are touched. */
    private fun moveTeamEvent(team: Int, program: String, oldKey: String, newKey: String) {
        val prog = normalizeProgram(program)
        val now = Instant.now()
        ScoutingEntries.update({ (ScoutingEntries.eventKey eq oldKey) and (ScoutingEntries.ownerTeamNumber eq team) and (ScoutingEntries.program eq prog) }) {
            it[eventKey] = newKey; it[updatedAt] = now
        }
        PitScoutingEntries.update({ (PitScoutingEntries.eventKey eq oldKey) and (PitScoutingEntries.ownerTeamNumber eq team) and (PitScoutingEntries.program eq prog) }) {
            it[eventKey] = newKey; it[updatedAt] = now
        }
        QualitativeScoutingEntries.update({ (QualitativeScoutingEntries.eventKey eq oldKey) and (QualitativeScoutingEntries.ownerTeamNumber eq team) and (QualitativeScoutingEntries.program eq prog) }) {
            it[eventKey] = newKey; it[updatedAt] = now
        }
        ScoutingAssignments.update({ (ScoutingAssignments.eventKey eq oldKey) and (ScoutingAssignments.ownerTeamNumber eq team) and (ScoutingAssignments.program eq prog) }) {
            it[eventKey] = newKey
        }
        ScoutingAlliances.update({ (ScoutingAlliances.eventKey eq oldKey) and (ScoutingAlliances.ownerTeamNumber eq team) and (ScoutingAlliances.program eq prog) }) {
            it[eventKey] = newKey
        }
        SharedLinks.update({ (SharedLinks.targetEventKey eq oldKey) and (SharedLinks.ownerTeamNumber eq team) and (SharedLinks.program eq prog) }) {
            it[targetEventKey] = newKey
        }
        MatchPredictions.update({ (MatchPredictions.eventKey eq oldKey) and (MatchPredictions.ownerTeamNumber eq team) and (MatchPredictions.program eq prog) }) {
            it[eventKey] = newKey
        }
        val owners = MatchCanonical.teamOwnerKeys(team, prog)
        EventTeamCanonical.moveSelections(oldKey, newKey, owners + AllianceSelectionService.pickListOwnerKey(team, prog) + AllianceSelectionService.rankedPickListOwnerKey(team, prog))
        EventTeamCanonical.moveOwnerScoped(MatchPlans, MatchPlans.ownerKey, MatchPlans.eventKey, MatchPlans.matchKey, MatchPlans.updatedAt, oldKey, newKey, owners)
        EventTeamCanonical.moveOwnerScoped(MatchChecklists, MatchChecklists.ownerKey, MatchChecklists.eventKey, MatchChecklists.matchKey, MatchChecklists.updatedAt, oldKey, newKey, owners)
        com.obsidianscout.scouting.BatteryTrackerService.moveEvent(oldKey, newKey, owners)

        val prefix = "${oldKey}_"
        TeamCacheOverrides.selectAll().where {
            (TeamCacheOverrides.teamNumber eq team) and (TeamCacheOverrides.program eq prog) and (TeamCacheOverrides.eventKey eq oldKey)
        }.toList().forEach { row ->
            val record = row[TeamCacheOverrides.recordKey]
            // The event row itself is rewritten by the caller.
            if (row[TeamCacheOverrides.kind] == KIND_EVENT) {
                TeamCacheOverrides.deleteWhere { TeamCacheOverrides.id eq row[TeamCacheOverrides.id] }
            } else {
                rekey(row, newKey, if (record.startsWith(prefix)) newKey + record.removePrefix(oldKey) else record)
            }
        }

        buildSet {
            ScoutingEntries.select(ScoutingEntries.matchKey).where { (ScoutingEntries.matchKey like "$prefix%") and (ScoutingEntries.ownerTeamNumber eq team) and (ScoutingEntries.program eq prog) }
                .forEach { row -> row[ScoutingEntries.matchKey]?.let { add(it) } }
            QualitativeScoutingEntries.select(QualitativeScoutingEntries.matchKey).where { (QualitativeScoutingEntries.matchKey like "$prefix%") and (QualitativeScoutingEntries.ownerTeamNumber eq team) and (QualitativeScoutingEntries.program eq prog) }
                .forEach { row -> row[QualitativeScoutingEntries.matchKey]?.let { add(it) } }
            ScoutingAssignments.select(ScoutingAssignments.matchKey).where { (ScoutingAssignments.matchKey like "$prefix%") and (ScoutingAssignments.ownerTeamNumber eq team) and (ScoutingAssignments.program eq prog) }
                .forEach { row -> row[ScoutingAssignments.matchKey]?.let { add(it) } }
            MatchPlans.select(MatchPlans.matchKey).where { (MatchPlans.matchKey like "$prefix%") and (MatchPlans.ownerKey inList owners) }
                .forEach { row -> add(row[MatchPlans.matchKey]) }
            MatchChecklists.select(MatchChecklists.matchKey).where { (MatchChecklists.matchKey like "$prefix%") and (MatchChecklists.ownerKey inList owners) }
                .forEach { row -> add(row[MatchChecklists.matchKey]) }
            MatchPredictions.select(MatchPredictions.matchKey).where { (MatchPredictions.matchKey like "$prefix%") and (MatchPredictions.ownerTeamNumber eq team) and (MatchPredictions.program eq prog) }
                .forEach { row -> add(row[MatchPredictions.matchKey]) }
        }.forEach { old -> MatchCanonical.remapMatchKeyForTeam(old, newKey + old.removePrefix(oldKey), team, prog) }
    }

    // ── Keeping overrides attached when shared keys change ───────────────────

    /** Re-keys an override row, keeping the newer of two when the team already has one at the target. */
    private fun rekey(row: ResultRow, event: String, record: String) {
        val clash = TeamCacheOverrides.selectAll().where {
            scope(row[TeamCacheOverrides.teamNumber], row[TeamCacheOverrides.program], row[TeamCacheOverrides.kind]) and
                (TeamCacheOverrides.eventKey eq event) and (TeamCacheOverrides.recordKey eq record)
        }.limit(1).firstOrNull()
        if (clash != null && clash[TeamCacheOverrides.id] != row[TeamCacheOverrides.id]) {
            if (row[TeamCacheOverrides.updatedAt] <= clash[TeamCacheOverrides.updatedAt]) {
                TeamCacheOverrides.deleteWhere { TeamCacheOverrides.id eq row[TeamCacheOverrides.id] }
                return
            }
            TeamCacheOverrides.deleteWhere { TeamCacheOverrides.id eq clash[TeamCacheOverrides.id] }
        }
        TeamCacheOverrides.update({ TeamCacheOverrides.id eq row[TeamCacheOverrides.id] }) {
            it[eventKey] = event
            it[recordKey] = record
        }
    }

    /**
     * The API is about to publish these events, as (key, year, code). A team's own copy stored under another key (typed
     * "scrim", published as "2026scrim") moves onto the API's key with that team's scouting and everything else it
     * hangs on that key. Each team moves only its own data. After this the read rule in [effectivePatch] does the rest,
     * for this sync and every later one.
     */
    fun adoptOwnEvents(targets: List<Triple<String, Int, String?>>) {
        if (targets.isEmpty()) return
        val own = TeamCacheOverrides.selectAll().where {
            (TeamCacheOverrides.kind eq KIND_EVENT) and (TeamCacheOverrides.created eq true) and (TeamCacheOverrides.removed eq false)
        }.toList()
        own.forEach { row ->
            val override = toOverride(row)
            val view = eventRowOf(null, override)
            val key = targets.firstOrNull { (key, year, code) -> EventTeamCanonical.sameEvent(view, key.lowercase().trim(), year, code) }
                ?.first?.lowercase()?.trim() ?: return@forEach
            val team = row[TeamCacheOverrides.teamNumber]
            val program = row[TeamCacheOverrides.program]
            moveTeamEvent(team, program, override.eventKey, key)
            if (overridesForEvents(team, program, KIND_EVENT, listOf(key)).isEmpty()) {
                store(team, program, KIND_EVENT, key, key, override.patch, created = true)
            }
            val settings = SettingsService.getSettings(team, program)
            if (settings.resolvedEventKey().equals(override.eventKey, ignoreCase = true)) {
                SettingsService.updateSettings(team, settings.copy(eventKey = key, eventCode = ""))
            }
        }
    }

    /** A shared event moved to [newKey] (sync adopting an older manual event). */
    fun moveEvent(oldKey: String, newKey: String) {
        TeamCacheOverrides.selectAll().where { TeamCacheOverrides.eventKey eq oldKey }.toList().forEach { row ->
            rekey(row, newKey, if (row[TeamCacheOverrides.kind] == KIND_EVENT) newKey else row[TeamCacheOverrides.recordKey])
        }
    }

    /** A shared match changed key (sync merging duplicates). */
    fun remapMatchKey(oldKey: String, newKey: String) {
        TeamCacheOverrides.selectAll().where { (TeamCacheOverrides.kind eq KIND_MATCH) and (TeamCacheOverrides.recordKey eq oldKey) }.toList().forEach { row ->
            rekey(row, row[TeamCacheOverrides.eventKey], newKey)
        }
    }

    // ── Backups ──────────────────────────────────────────────────────────────

    val csvHeaders = listOf("team_number", "program", "kind", "event_key", "record_key", "removed", "patch_json", "updated_at", "created")
    const val CSV_FILE = "team_cache_overrides.csv"

    fun csvRow(d: TeamCacheOverrideBackupDto): List<String> = listOf(
        d.teamNumber.toString(), d.program, d.kind, d.eventKey, d.recordKey, d.removed.toString(), d.patchJson, d.updatedAt.toString(), d.created.toString()
    )

    fun fromCsv(r: Map<String, String>): TeamCacheOverrideBackupDto? {
        val team = r["team_number"]?.toIntOrNull() ?: return null
        return TeamCacheOverrideBackupDto(
            teamNumber = team,
            program = r["program"]?.takeIf { it.isNotBlank() } ?: "FRC",
            kind = r["kind"]?.takeIf { it.isNotBlank() } ?: return null,
            eventKey = r["event_key"] ?: return null,
            recordKey = r["record_key"] ?: return null,
            removed = r["removed"].toBoolean(),
            patchJson = r["patch_json"]?.takeIf { it.isNotBlank() } ?: "{}",
            updatedAt = r["updated_at"]?.toLongOrNull() ?: 0L,
            created = r["created"].toBoolean()
        )
    }

    /** [team] null exports every team's overrides (global backups). */
    fun exportBackup(team: Int?, program: String): List<TeamCacheOverrideBackupDto> {
        val query = TeamCacheOverrides.selectAll()
        if (team != null) {
            query.andWhere { (TeamCacheOverrides.teamNumber eq team) and (TeamCacheOverrides.program eq normalizeProgram(program)) }
        }
        return query.map { row ->
            TeamCacheOverrideBackupDto(
                teamNumber = row[TeamCacheOverrides.teamNumber],
                program = row[TeamCacheOverrides.program],
                kind = row[TeamCacheOverrides.kind],
                eventKey = row[TeamCacheOverrides.eventKey],
                recordKey = row[TeamCacheOverrides.recordKey],
                removed = row[TeamCacheOverrides.removed],
                patchJson = row[TeamCacheOverrides.patchJson],
                updatedAt = row[TeamCacheOverrides.updatedAt].toEpochMilli(),
                created = row[TeamCacheOverrides.created]
            )
        }
    }

    fun importBackup(rows: List<TeamCacheOverrideBackupDto>, targetTeam: (Int) -> Int, targetProgram: (String) -> String): Int {
        rows.forEach { row ->
            val team = targetTeam(row.teamNumber)
            val program = normalizeProgram(targetProgram(row.program))
            val existing = TeamCacheOverrides.selectAll().where {
                scope(team, program, row.kind) and (TeamCacheOverrides.eventKey eq row.eventKey) and (TeamCacheOverrides.recordKey eq row.recordKey)
            }.limit(1).firstOrNull()
            val stamp = Instant.ofEpochMilli(row.updatedAt)
            if (existing == null) {
                TeamCacheOverrides.insert {
                    it[teamNumber] = team
                    it[TeamCacheOverrides.program] = program
                    it[kind] = row.kind
                    it[eventKey] = row.eventKey
                    it[recordKey] = row.recordKey
                    it[removed] = row.removed
                    it[created] = row.created
                    it[patchJson] = row.patchJson
                    it[updatedAt] = stamp
                }
            } else {
                TeamCacheOverrides.update({ TeamCacheOverrides.id eq existing[TeamCacheOverrides.id] }) {
                    it[removed] = row.removed
                    it[created] = row.created
                    it[patchJson] = row.patchJson
                    it[updatedAt] = stamp
                }
            }
        }
        return rows.size
    }
}
