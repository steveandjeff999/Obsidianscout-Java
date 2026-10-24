package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.EntryTombstones
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.readTransaction
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Delta sync for scouting entries (`GET /api/sync/entries`).
 *
 * A client sends the cursor from its last sync and gets back only the entries created or changed since then,
 * plus the ids deleted since then. The full list (every visible entry, prescout included, no per-match
 * de-duplication — the `all=true&includePrescout=true` view) comes back when there is no usable cursor:
 * first sync, a cursor from a different scope (alliance membership changed), a cursor older than tombstones
 * are kept, or a bulk change (restore, bulk delete) since the cursor.
 *
 * `total` is the number of visible entries after the change, so a client whose merged copy has a different
 * count knows it missed something and asks for the full list.
 */
object EntrySync {
    const val KIND_MATCH = "match"
    const val KIND_PIT = "pit"
    const val KIND_QUAL = "qual"
    private const val KIND_ALL = "*"
    private val KINDS = setOf(KIND_MATCH, KIND_PIT, KIND_QUAL)

    /** Rows are re-sent if they changed this long before the cursor, covering commits in flight and clock skew between nodes. */
    private const val OVERLAP_MS = 120_000L
    private val TOMBSTONE_RETENTION: Duration = Duration.ofDays(45)
    private const val CURSOR_VERSION = "v1"

    @Volatile
    private var lastPruneAt = 0L

    /** Matches rows changed at or after [since]. Rows from before `updated_at` existed fall back to their creation time. */
    fun changedSince(updatedAt: Column<Instant?>, createdAt: Column<Instant>, since: Instant): Op<Boolean> =
        (updatedAt greaterEq since) or (updatedAt.isNull() and (createdAt greaterEq since))

    /** Records deleted entries. Call inside the transaction that deletes them. [ownerTeamNumber] 0 means "unknown, tell everyone". */
    fun recordDeletions(kind: String, ids: Collection<String>, ownerTeamNumber: Int, program: String) {
        if (ids.isEmpty()) return
        val now = Instant.now()
        for (entryId in ids) {
            EntryTombstones.insert {
                it[EntryTombstones.kind] = kind
                it[EntryTombstones.entryId] = entryId
                it[EntryTombstones.ownerTeamNumber] = ownerTeamNumber
                it[EntryTombstones.program] = program
                it[deletedAt] = now
            }
        }
    }

    /**
     * Marks a change too broad to describe entry by entry (restore, import, bulk delete, key rename). Every client
     * re-downloads its full list on its next sync. Never throws: a failed marker must not fail the operation itself.
     */
    fun markBulkChange() {
        try {
            transaction {
                EntryTombstones.insert {
                    it[kind] = KIND_ALL
                    it[entryId] = KIND_ALL
                    it[ownerTeamNumber] = 0
                    it[program] = KIND_ALL
                    it[deletedAt] = Instant.now()
                }
            }
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(EntrySync::class.java).warn("[EntrySync] Failed to record bulk change: ${e.message}")
        }
    }

    private data class Cursor(val time: Instant, val scope: String)

    private fun encodeCursor(cursor: Cursor): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$CURSOR_VERSION|${cursor.time.toEpochMilli()}|${cursor.scope}".toByteArray())

    private fun decodeCursor(raw: String?): Cursor? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parts = String(Base64.getUrlDecoder().decode(raw.trim())).split("|")
            if (parts.size != 3 || parts[0] != CURSOR_VERSION) return null
            Cursor(Instant.ofEpochMilli(parts[1].toLong()), parts[2])
        }.getOrNull()
    }

    /** Teams whose entries the caller sees, or null for SUPERADMIN (every team). */
    private fun visibleTeams(session: UserSession): List<Int>? {
        if (session.role == UserRole.SUPERADMIN) return null
        return (AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program) + session.teamNumber).distinct().sorted()
    }

    private fun scopeOf(session: UserSession, teams: List<Int>?, eventKey: String?): String {
        val text = "${session.program}|${teams?.joinToString(",") ?: "*"}|${eventKey.orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Changes for one kind of entry. [eventKeyParam] narrows the list to one event plus all prescout entries, which is what
     * a device at an event needs; without it the list covers every event.
     */
    fun changes(session: UserSession, kind: String, cursorParam: String?, compact: Boolean, eventKeyParam: String? = null): JsonObject {
        if (kind !in KINDS) throw ApiException(HttpStatusCode.BadRequest, "kind must be match, pit or qual")
        val now = Instant.now()
        pruneTombstones(now)

        val eventKey = eventKeyParam?.trim()?.ifBlank { null }
        val teams = visibleTeams(session)
        val scope = scopeOf(session, teams, eventKey)
        val cursor = decodeCursor(cursorParam)

        var since: Instant? = cursor?.takeIf { it.scope == scope }?.time?.minusMillis(OVERLAP_MS)
        if (since != null && since.isBefore(now.minus(TOMBSTONE_RETENTION))) since = null
        if (since != null && bulkChangeSince(since)) since = null

        val records: List<JsonObject> = when (kind) {
            KIND_MATCH -> ScoutingService.listChangedEntries(session, since, eventKey)
                .map { JsonSupport.json.encodeToJsonElement(ScoutingEntryRecord.serializer(), it).jsonObject }
            KIND_PIT -> PitScoutingService.listChangedEntries(session, since, eventKey)
                .map { JsonSupport.json.encodeToJsonElement(PitScoutingEntryRecord.serializer(), it).jsonObject }
            else -> QualitativeScoutingService.listChangedEntries(session, since, eventKey)
                .map { JsonSupport.json.encodeToJsonElement(QualitativeScoutingEntryRecord.serializer(), it).jsonObject }
        }
        val deleted = if (since == null) emptyList() else deletedSince(kind, since, session.program, teams)
        val total = countVisible(kind, session.program, teams, eventKey)

        return buildJsonObject {
            put("v", 1)
            put("kind", kind)
            eventKey?.let { put("eventKey", it) }
            put("cursor", encodeCursor(Cursor(now, scope)))
            put("full", since == null)
            put("total", total)
            put("deleted", JsonArray(deleted.map { JsonPrimitive(it) }))
            if (compact) {
                val packed = pack(records)
                put("cols", packed.cols)
                put("keys", packed.keys)
                put("rows", packed.rows)
            } else {
                put("entries", JsonArray(records))
            }
        }
    }

    private fun bulkChangeSince(since: Instant): Boolean = readTransaction {
        EntryTombstones.selectAll()
            .where { (EntryTombstones.kind eq KIND_ALL) and (EntryTombstones.deletedAt greaterEq since) }
            .limit(1)
            .any()
    }

    private fun deletedSince(kind: String, since: Instant, program: String, teams: List<Int>?): List<String> = readTransaction {
        val query = EntryTombstones.selectAll().where {
            (EntryTombstones.kind eq kind) and (EntryTombstones.deletedAt greaterEq since) and
                ((EntryTombstones.program eq program) or (EntryTombstones.program eq KIND_ALL))
        }
        if (teams != null) {
            query.andWhere { (EntryTombstones.ownerTeamNumber inList teams) or (EntryTombstones.ownerTeamNumber eq 0) }
        }
        query.map { it[EntryTombstones.entryId] }.distinct()
    }

    private fun countVisible(kind: String, program: String, teams: List<Int>?, eventKey: String?): Long = readTransaction {
        fun <T : org.jetbrains.exposed.v1.core.Table> count(
            table: T,
            programCol: Column<String>,
            ownerCol: Column<Int>,
            eventCol: Column<String?>,
            prescoutCol: Column<Boolean>
        ): Long {
            var op: Op<Boolean> = programCol eq program
            if (teams != null) op = op and (ownerCol inList teams)
            if (eventKey != null) op = op and ((eventCol.lowerCase() eq eventKey.lowercase()) or (prescoutCol eq true))
            return table.selectAll().where { op }.count()
        }
        when (kind) {
            KIND_MATCH -> count(ScoutingEntries, ScoutingEntries.program, ScoutingEntries.ownerTeamNumber, ScoutingEntries.eventKey, ScoutingEntries.isPrescout)
            KIND_PIT -> count(PitScoutingEntries, PitScoutingEntries.program, PitScoutingEntries.ownerTeamNumber, PitScoutingEntries.eventKey, PitScoutingEntries.isPrescout)
            else -> count(QualitativeScoutingEntries, QualitativeScoutingEntries.program, QualitativeScoutingEntries.ownerTeamNumber, QualitativeScoutingEntries.eventKey, QualitativeScoutingEntries.isPrescout)
        }
    }

    private fun pruneTombstones(now: Instant) {
        val nowMs = now.toEpochMilli()
        if (nowMs - lastPruneAt < 3_600_000L) return
        lastPruneAt = nowMs
        try {
            transaction {
                EntryTombstones.deleteWhere { deletedAt less now.minus(TOMBSTONE_RETENTION) }
            }
        } catch (_: Exception) {}
    }

    data class Packed(val cols: JsonArray, val keys: JsonArray, val rows: JsonArray)

    /**
     * Compact form: field names are sent once instead of in every entry. Each row is
     * `[meta values in cols order..., [data values in keys order], [indexes of data values that are really null]?]`.
     * A data key an entry doesn't have is null in its row and missing from the null-index list.
     */
    fun pack(records: List<JsonObject>): Packed {
        val cols = LinkedHashSet<String>()
        val keys = LinkedHashSet<String>()
        for (record in records) {
            for ((k, v) in record) {
                if (k == "data") (v as? JsonObject)?.keys?.let { keys.addAll(it) } else cols.add(k)
            }
        }
        val colList = cols.toList()
        val keyList = keys.toList()
        val rows = records.map { record ->
            val data = record["data"] as? JsonObject ?: JsonObject(emptyMap())
            val values = ArrayList<JsonElement>(keyList.size)
            val nulls = ArrayList<JsonElement>()
            keyList.forEachIndexed { i, key ->
                val v = data[key]
                if (v is JsonNull) nulls.add(JsonPrimitive(i))
                values.add(v ?: JsonNull)
            }
            val row = ArrayList<JsonElement>(colList.size + 2)
            colList.forEach { row.add(record[it] ?: JsonNull) }
            row.add(JsonArray(trimTrailingNulls(values)))
            if (nulls.isNotEmpty()) row.add(JsonArray(nulls))
            JsonArray(row)
        }
        return Packed(
            JsonArray(colList.map { JsonPrimitive(it) }),
            JsonArray(keyList.map { JsonPrimitive(it) }),
            JsonArray(rows)
        )
    }

    /** Inverse of [pack], used by tests and by anything server-side that reads compact payloads. */
    fun unpack(cols: List<String>, keys: List<String>, rows: JsonArray): List<JsonObject> = rows.map { rowEl ->
        val row = rowEl as JsonArray
        val out = LinkedHashMap<String, JsonElement>()
        cols.forEachIndexed { i, c -> out[c] = row.getOrNull(i) ?: JsonNull }
        val values = row.getOrNull(cols.size) as? JsonArray ?: JsonArray(emptyList())
        val nulls = (row.getOrNull(cols.size + 1) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }?.toSet() ?: emptySet()
        val data = LinkedHashMap<String, JsonElement>()
        keys.forEachIndexed { i, k ->
            val v = values.getOrNull(i)
            if (v != null && v !is JsonNull) data[k] = v else if (i in nulls) data[k] = JsonNull
        }
        out["data"] = JsonObject(data)
        JsonObject(out)
    }

    private fun trimTrailingNulls(values: MutableList<JsonElement>): List<JsonElement> {
        var end = values.size
        while (end > 0 && values[end - 1] is JsonNull) end--
        return values.subList(0, end)
    }
}
