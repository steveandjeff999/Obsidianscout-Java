package com.obsidianscout.publicdata

import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.readTransaction
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.integrations.SettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PublicEventSummary(
    val eventKey: String,
    val eventName: String,
    val year: Int,
    val matchEntries: Int,
    val pitEntries: Int,
    val qualEntries: Int,
    /** Match (quantitative) entries exist, so the analytics pages have something to work with. */
    val analyticsAvailable: Boolean
)

@Serializable
data class PublicTeamSummary(
    val teamNumber: Int,
    val program: String,
    val teamName: String? = null,
    /** The team also shares notes and comments, which unlocks the qualitative pages in [PUBLIC_NOTES_PAGES]. */
    val notesIncluded: Boolean = false,
    val events: List<PublicEventSummary>
)

@Serializable
data class PublicDirectoryResponse(val teams: List<PublicTeamSummary>)

/**
 * Public pages built mostly on qualitative answers and written notes. They're only offered when the team also
 * shares notes ([com.obsidianscout.integrations.ApiSettings.dataPublicIncludeNotes]); without them the page would
 * be a table of blanked-out text.
 */
val PUBLIC_NOTES_PAGES = setOf("qual-rankings")

object PublicDataService {

    private const val DIRECTORY_TTL_MS = 30_000L
    private const val RATE_WINDOW_MS = 60_000L
    /** An analytics page fires a dozen or so requests on load, so leave room for a visitor clicking around. */
    private const val RATE_MAX_PER_WINDOW = 600

    @Volatile private var directoryCache: Pair<Long, PublicDirectoryResponse>? = null
    private val requestCounts = ConcurrentHashMap<String, Pair<Long, Int>>()

    /**
     * The team's settings if it shares its data, read straight from the database (settings reads aren't cached), so
     * turning it off takes effect on the next request. Only the team's own row counts: [SettingsService.getSettings]
     * falls back to the global (team 0) defaults, and those must never make every team without a row public.
     */
    fun publicSettings(teamNumber: Int, program: String): ApiSettings? {
        if (teamNumber <= 0) return null
        val json = runCatching {
            readTransaction {
                AppSettings.select(AppSettings.settingsJson)
                    .where { (AppSettings.teamNumber eq teamNumber) and (AppSettings.program eq program) }
                    .limit(1)
                    .firstOrNull()
                    ?.get(AppSettings.settingsJson)
            }
        }.getOrNull() ?: return null
        if (runCatching { JsonSupport.json.decodeFromString(ApiSettings.serializer(), json) }.getOrNull()?.dataPublic != true) return null
        return runCatching { SettingsService.getSettingsRaw(teamNumber, program) }.getOrNull()?.takeIf { it.dataPublic }
    }

    /** Whether the team has any (non-prescout or prescout) entries at [eventKey]. */
    fun hasEntriesAt(teamNumber: Int, program: String, eventKey: String): Boolean = runCatching {
        readTransaction {
            ScoutingEntries.select(ScoutingEntries.id)
                .where { (ScoutingEntries.ownerTeamNumber eq teamNumber) and (ScoutingEntries.program eq program) and (ScoutingEntries.eventKey eq eventKey) }
                .limit(1).any() ||
            PitScoutingEntries.select(PitScoutingEntries.id)
                .where { (PitScoutingEntries.ownerTeamNumber eq teamNumber) and (PitScoutingEntries.program eq program) and (PitScoutingEntries.eventKey eq eventKey) }
                .limit(1).any() ||
            QualitativeScoutingEntries.select(QualitativeScoutingEntries.id)
                .where { (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and (QualitativeScoutingEntries.program eq program) and (QualitativeScoutingEntries.eventKey eq eventKey) }
                .limit(1).any()
        }
    }.getOrDefault(false)

    fun clearDirectoryCache() {
        directoryCache = null
    }

    /** Fixed-window per-IP limit for anonymous public requests. */
    fun tryAcquire(ip: String): Boolean {
        val now = System.currentTimeMillis()
        if (requestCounts.size > 10_000) requestCounts.entries.removeIf { now - it.value.first > RATE_WINDOW_MS }
        var allowed = true
        requestCounts.compute(ip) { _, prev ->
            if (prev == null || now - prev.first > RATE_WINDOW_MS) Pair(now, 1)
            else {
                allowed = prev.second < RATE_MAX_PER_WINDOW
                if (allowed) prev.copy(second = prev.second + 1) else prev
            }
        }
        return allowed
    }

    fun directory(): PublicDirectoryResponse {
        val now = System.currentTimeMillis()
        directoryCache?.let { (at, value) -> if (now - at < DIRECTORY_TTL_MS) return value }
        val built = buildDirectory()
        directoryCache = now to built
        return built
    }

    private data class CountKey(val team: Int, val program: String, val eventKey: String)

    private fun buildDirectory(): PublicDirectoryResponse = readTransaction {
        val publicTeams = AppSettings.selectAll()
            .where { AppSettings.teamNumber neq 0 }
            .mapNotNull { row ->
                val parsed = runCatching {
                    JsonSupport.json.decodeFromString(ApiSettings.serializer(), row[AppSettings.settingsJson])
                }.getOrNull() ?: return@mapNotNull null
                if (!parsed.dataPublic) null
                else Triple(row[AppSettings.teamNumber], row[AppSettings.program], parsed.dataPublicIncludeNotes)
            }
        if (publicTeams.isEmpty()) return@readTransaction PublicDirectoryResponse(emptyList())
        val teamNumbers = publicTeams.map { it.first }.distinct()

        fun counts(
            table: UUIDTable,
            owner: Column<Int>,
            program: Column<String>,
            eventKey: Column<String?>,
            isPrescout: Column<Boolean>
        ): Map<CountKey, Int> {
            val cnt = table.id.count()
            return table.select(owner, program, eventKey, cnt)
                .where { (owner inList teamNumbers) and (isPrescout eq false) and eventKey.isNotNull() }
                .groupBy(owner, program, eventKey)
                .mapNotNull { row ->
                    val key = row[eventKey]?.trim()?.lowercase().orEmpty()
                    if (key.isBlank()) null else CountKey(row[owner], row[program], key) to row[cnt].toInt()
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }
        }

        val matchCounts = counts(ScoutingEntries, ScoutingEntries.ownerTeamNumber, ScoutingEntries.program, ScoutingEntries.eventKey, ScoutingEntries.isPrescout)
        val pitCounts = counts(PitScoutingEntries, PitScoutingEntries.ownerTeamNumber, PitScoutingEntries.program, PitScoutingEntries.eventKey, PitScoutingEntries.isPrescout)
        val qualCounts = counts(QualitativeScoutingEntries, QualitativeScoutingEntries.ownerTeamNumber, QualitativeScoutingEntries.program, QualitativeScoutingEntries.eventKey, QualitativeScoutingEntries.isPrescout)
        val allKeys = matchCounts.keys + pitCounts.keys + qualCounts.keys

        val eventKeys = allKeys.map { it.eventKey }.distinct()
        val events = if (eventKeys.isEmpty()) emptyMap() else ApiEvents.selectAll()
            .where { ApiEvents.eventKey inList eventKeys }
            .associate { it[ApiEvents.eventKey].lowercase() to (it[ApiEvents.name] to it[ApiEvents.year]) }
        val teamNames = ApiTeams.select(ApiTeams.teamNumber, ApiTeams.nickname, ApiTeams.name)
            .where { ApiTeams.teamNumber inList teamNumbers }
            .associate { it[ApiTeams.teamNumber] to (it[ApiTeams.nickname] ?: it[ApiTeams.name]) }

        val teams = publicTeams.map { (team, program, notesIncluded) ->
            val teamEvents = allKeys.filter { it.team == team && it.program.equals(program, ignoreCase = true) }
                .map { it.eventKey }
                .distinct()
                .map { key ->
                    val ck = CountKey(team, program, key)
                    val m = matchCounts[ck] ?: 0
                    val (name, year) = events[key] ?: (key to (key.take(4).toIntOrNull() ?: 0))
                    PublicEventSummary(
                        eventKey = key,
                        eventName = name,
                        year = year,
                        matchEntries = m,
                        pitEntries = pitCounts[ck] ?: 0,
                        qualEntries = qualCounts[ck] ?: 0,
                        analyticsAvailable = m > 0
                    )
                }
                .sortedWith(compareByDescending<PublicEventSummary> { it.year }.thenBy { it.eventName })
            PublicTeamSummary(team, program, teamNames[team], notesIncluded, teamEvents)
        }.sortedWith(compareBy<PublicTeamSummary> { it.program }.thenBy { it.teamNumber })
        PublicDirectoryResponse(teams)
    }

    // ---- Response sanitizing -------------------------------------------------------------------------------------

    /** Keys whose values name a person. Public responses never show who scouted. */
    private val NAME_KEYS = setOf(
        "scoutname", "scouter", "scoutername", "scout", "username", "submittedby", "submittedbyusername",
        "author", "createdbyusername", "updatedbyusername", "lasteditedby", "editedby", "user", "displayname"
    )

    /** Keys that hold credentials. Configs can still carry legacy API keys, so these are always stripped. */
    private val SECRET_KEYS = setOf("tba_key", "first_key", "first_username", "match13_key", "apikeys", "tbakey", "firstkey", "match13key", "firstusername")

    /** Field ids of free-text answers in the team's three configs: textarea fields, and text fields named as notes. */
    fun freeTextFieldIds(teamNumber: Int, program: String): Set<String> {
        val configs = listOfNotNull(
            runCatching { ConfigService.getConfig(teamNumber, program) }.getOrNull(),
            runCatching { ConfigService.getPitConfig(teamNumber, program) }.getOrNull(),
            runCatching { ConfigService.getQualitativeConfig(teamNumber, program) }.getOrNull()
        )
        return configs.flatMap { it.fields }
            .filter { f ->
                val type = f.type.lowercase()
                type == "textarea" || (type == "text" && looksLikeNotes(f.id + " " + f.label))
            }
            .map { it.id }
            .toSet()
    }

    private fun looksLikeNotes(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("note") || n.contains("comment")
    }

    /** True for a string that isn't a number or boolean, i.e. something a person typed. */
    private fun isFreeText(value: JsonElement): Boolean {
        val p = value as? JsonPrimitive ?: return false
        return p.isString && p.content.isNotBlank() && p.doubleOrNull == null && p.booleanOrNull == null
    }

    fun sanitize(element: JsonElement, includeNotes: Boolean, freeTextIds: Set<String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapNotNull { (key, value) ->
            val lower = key.lowercase()
            when {
                lower in SECRET_KEYS -> null
                lower in NAME_KEYS && value is JsonPrimitive && value.isString -> key to JsonPrimitive("Scout")
                // Game pieces can be called "notes" (2024), so only redact typed text, never counts.
                !includeNotes && isFreeText(value) && (key in freeTextIds || looksLikeNotes(lower)) -> key to JsonPrimitive("")
                // Entries often carry their answers as an embedded JSON string; sanitize inside it too.
                lower == "datajson" && value is JsonPrimitive && value.isString -> key to sanitizeJsonString(value.content, includeNotes, freeTextIds)
                else -> key to sanitize(value, includeNotes, freeTextIds)
            }
        }.toMap())
        is JsonArray -> JsonArray(element.map { sanitize(it, includeNotes, freeTextIds) })
        else -> element
    }

    private fun sanitizeJsonString(text: String, includeNotes: Boolean, freeTextIds: Set<String>): JsonPrimitive {
        val parsed = runCatching { JsonSupport.json.parseToJsonElement(text) }.getOrNull() ?: return JsonPrimitive(text)
        return JsonPrimitive(sanitize(parsed, includeNotes, freeTextIds).toString())
    }

    /** User id of the synthetic read-only session public requests run as. Matches no real user. */
    val PUBLIC_USER_ID: String = UUID(0L, 0L).toString()
}
