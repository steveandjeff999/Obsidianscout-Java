package com.obsidianscout.integrations

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.EpaOprHistoryCache
import com.obsidianscout.routes.StatsHistoryResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.and
import io.ktor.serialization.kotlinx.json.json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64

@Serializable
private data class GraphQLRequest(
    val query: String,
    val variables: JsonObject = JsonObject(emptyMap())
)

private val JsonElement.safeJsonObject: JsonObject?
    get() = this as? JsonObject

/** An FTC source answered, but not usefully: HTTP error, GraphQL error, or an event it doesn't have. */
class FtcSyncException(message: String) : Exception(message)

object FtcIntegrationService {
    private val log = LoggerFactory.getLogger(FtcIntegrationService::class.java)

    private const val FTC_SCOUT_URL = "https://api.ftcscout.org/graphql"

    /** FTC Scout has about 1,500-2,000 events a season; the old limit of 200 silently dropped most of them. */
    private const val EVENT_SEARCH_LIMIT = 10_000

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(JsonSupport.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000L
            connectTimeoutMillis = 30000L
            socketTimeoutMillis = 30000L
        }
    }

    private val httpCache = ConditionalHttpCache()

    private fun hasFirstCredentials(settings: ApiSettings): Boolean {
        return settings.apiKeys.firstUsername.isNotBlank() && settings.apiKeys.firstKey.isNotBlank()
    }

    private fun extractYearAndCode(eventKey: String): Pair<Int, String> {
        val key = eventKey.trim().lowercase()
        val year = if (key.length >= 4 && key.take(4).all { it.isDigit() }) {
            key.take(4).toInt()
        } else {
            java.time.Year.now().value
        }
        val code = if (key.length > 4) key.drop(4).uppercase() else key.uppercase()
        return Pair(year, code)
    }

    /**
     * Runs an FTC Scout GraphQL query. Values always go in [variables], never into the query text, so an
     * event code can't break or change the query. Throws [FtcSyncException] when there is no data.
     */
    private suspend fun ftcScoutQuery(query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val response = client.post(FTC_SCOUT_URL) {
            contentType(ContentType.Application.Json)
            setBody(GraphQLRequest(query, variables))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw FtcSyncException("FTC Scout HTTP ${response.status.value}${errorDetail(body)}")
        }
        val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw FtcSyncException("FTC Scout sent unreadable data")
        val errors = (root["errors"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("message") as? JsonPrimitive)?.content }
        val data = root["data"] as? JsonObject
            ?: throw FtcSyncException("FTC Scout query failed: ${errors.firstOrNull() ?: "no data"}")
        if (errors.isNotEmpty()) log.warn("FTC Scout returned partial data: ${errors.first()}")
        return data
    }

    private fun errorDetail(body: String): String =
        body.trim().replace(Regex("\\s+"), " ").take(160).let { if (it.isBlank()) "" else ": $it" }

    // ---- Season-specific GraphQL types ----------------------------------------------------------------

    /**
     * FTC Scout gives every season its own score and stats types (MatchScores2026, TeamEventStats2026, ...)
     * inside the MatchScores / TeamEventStats unions. They are read from the live schema so a new season
     * works without a code change; hardcoding them is how 2026 scores came back empty.
     */
    internal data class SeasonTypes(val matchScoreTypes: List<String>, val eventStatsTypes: List<String>)

    @Volatile
    private var seasonTypesCache: Pair<Instant, SeasonTypes>? = null

    private suspend fun seasonTypes(year: Int): SeasonTypes {
        seasonTypesCache?.takeIf { it.first.isAfter(Instant.now().minusSeconds(6 * 3600)) }?.let { return it.second }
        return try {
            val data = ftcScoutQuery(
                "{ m: __type(name: \"MatchScores\") { possibleTypes { name } } s: __type(name: \"TeamEventStats\") { possibleTypes { name } } }"
            )
            fun names(alias: String) = ((data[alias] as? JsonObject)?.get("possibleTypes") as? JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
            val types = SeasonTypes(names("m"), names("s"))
            if (types.matchScoreTypes.isEmpty() || types.eventStatsTypes.isEmpty()) {
                throw FtcSyncException("FTC Scout schema listed no season types")
            }
            seasonTypesCache = Instant.now() to types
            types
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not cached: the next sync looks again.
            log.warn("Could not read FTC Scout season types (${e.message}); assuming the $year names")
            SeasonTypes(listOf("MatchScores$year"), listOf("TeamEventStats$year"))
        }
    }

    /**
     * Alliance totals for every traditional season. Remote seasons (2020/2021 "Remote" types) scored one
     * team at a time and have no red/blue alliances, so they get no fragment.
     */
    internal fun scoresSelection(types: List<String>): String =
        (listOf("__typename") + types.filterNot { it.endsWith("Remote") }
            .map { "... on $it { red { totalPoints } blue { totalPoints } }" }).joinToString(" ")

    /** Event OPR (total points) for every season's stats type; every season has the same opr group. */
    internal fun eventStatsSelection(types: List<String>): String =
        (listOf("__typename") + types.map { "... on $it { opr { totalPoints } }" }).joinToString(" ")

    // ---- Event list -------------------------------------------------------------------------------------

    suspend fun syncEvents(settings: ApiSettings): Int {
        val year = settings.year
        log.info("Syncing FTC events for season $year using FTC Scout GraphQL API")

        val data = ftcScoutQuery(
            "query(\$season: Int!, \$limit: Int!) { eventsSearch(season: \$season, limit: \$limit) { code name start end timezone } }",
            buildJsonObject {
                put("season", year)
                put("limit", EVENT_SEARCH_LIMIT)
            }
        )
        val eventsArray = data["eventsSearch"] as? JsonArray ?: throw FtcSyncException("FTC Scout returned no event list for $year")
        if (eventsArray.size >= EVENT_SEARCH_LIMIT) {
            log.warn("FTC Scout returned $EVENT_SEARCH_LIMIT events for $year, the request limit; some may be missing")
        }

        val now = Instant.now()
        withContext(Dispatchers.IO) {
            transaction {
                val events = eventsArray.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val code = (obj["code"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    obj to code
                }
                EventTeamCanonical.adoptManualEventsInto(events.map { (_, code) -> Triple("${year}${code.lowercase()}", year, code) })
                val existingByKey = ApiEvents.selectAll().where { ApiEvents.year eq year }.associateBy { it[ApiEvents.eventKey] }
                events.forEach { (obj, code) ->
                    val name = (obj["name"] as? JsonPrimitive)?.content ?: ""
                    val start = (obj["start"] as? JsonPrimitive)?.contentOrNull
                    val end = (obj["end"] as? JsonPrimitive)?.contentOrNull
                    val timezone = (obj["timezone"] as? JsonPrimitive)?.contentOrNull ?: "UTC"
                    val eventKey = "${year}${code.lowercase()}"
                    val existing = existingByKey[eventKey]
                    if (existing == null) {
                        ApiEvents.insert {
                            it[ApiEvents.eventKey] = eventKey
                            it[ApiEvents.name] = name
                            it[ApiEvents.year] = year
                            it[ApiEvents.eventCode] = code
                            it[ApiEvents.startDate] = start
                            it[ApiEvents.endDate] = end
                            it[ApiEvents.timezone] = timezone
                            it[ApiEvents.dataJson] = JsonSupport.json.encodeToString(JsonElement.serializer(), obj)
                            it[ApiEvents.updatedAt] = now
                        }
                    } else if (
                        existing[ApiEvents.name] != name || existing[ApiEvents.eventCode] != code ||
                        existing[ApiEvents.startDate] != start || existing[ApiEvents.endDate] != end ||
                        existing[ApiEvents.timezone] != timezone
                    ) {
                        // The list only carries these fields. Its JSON is left alone so it doesn't replace the
                        // fuller record an event sync stored, and unchanged events aren't rewritten at all.
                        ApiEvents.update({ ApiEvents.id eq existing[ApiEvents.id] }) {
                            it[ApiEvents.name] = name
                            it[ApiEvents.eventCode] = code
                            it[ApiEvents.startDate] = start
                            it[ApiEvents.endDate] = end
                            it[ApiEvents.timezone] = timezone
                            it[ApiEvents.updatedAt] = now
                        }
                    }
                }
            }
        }
        return eventsArray.size
    }

    // ---- Event data -------------------------------------------------------------------------------------

    suspend fun syncEventData(settings: ApiSettings): SyncCounts {
        val eventKey = "${settings.year}${settings.eventCode.lowercase()}"
        return syncCustomEventData(settings, eventKey)
    }

    suspend fun syncCustomEventData(settings: ApiSettings, eventKey: String): SyncCounts {
        val (year, code) = extractYearAndCode(eventKey)

        // Guard: FTC event codes from FTC Scout are bare alphanumeric strings (e.g. "MNMI2",
        // "OHOW"). If the code portion itself starts with 4 digits it almost certainly means
        // the user typed a full TBA/FRC event key (e.g. "2026arc") into the FTC event code
        // field. Reject it so we don't query FTC Scout with an FRC-program code and
        // potentially find a coincidentally matching FTC event.
        if (code.length > 4 && code.take(4).all { it.isDigit() }) {
            throw FtcSyncException(
                "Event code '$code' looks like an FRC/TBA event key. Please set the FTC event code (e.g. USTXCMP)."
            )
        }

        log.info("Syncing FTC event data for $eventKey (season $year, code $code)")
        val warnings = java.util.Collections.synchronizedList(mutableListOf<String>())
        val types = seasonTypes(year)
        // FIRST may list this event under a different code than FTC Scout does.
        val firstCode = if (settings.usesAlternateEventCode(eventKey)) settings.alternateEventCode.trim().uppercase() else code

        val (eventObj, firstTeams, firstMatches) = coroutineScope {
            val first = hasFirstCredentials(settings)
            val firstTeamsDeferred = if (first) async { tryFirst("FIRST teams", warnings) { fetchFirstFtcTeams(settings, year, firstCode) } } else null
            val firstMatchesDeferred = if (first) async { tryFirst("FIRST matches", warnings) { fetchFirstFtcMatches(settings, year, firstCode) } } else null
            val event = fetchFtcScoutEvent(year, code, types)
            Triple(event, firstTeamsDeferred?.await(), firstMatchesDeferred?.await())
        }

        val now = Instant.now()
        val teamsArray = (eventObj["teams"] as? JsonArray).orEmpty()
        if (firstTeams?.isEmpty() == true && teamsArray.isNotEmpty()) {
            warnings.add(
                "FIRST has no teams for event code $firstCode; if FIRST lists this event under another code, " +
                    "set it as the alternate event code"
            )
        }

        val matches = buildMatches(eventKey, (eventObj["matches"] as? JsonArray).orEmpty(), firstMatches.orEmpty())
        var teamCount = 0
        withContext(Dispatchers.IO) {
            transaction {
                writeEvent(eventKey, year, code, eventObj, now)
                teamCount = writeTeams(eventKey, teamsArray, firstTeams, now)
                writeMatches(eventKey, matches, now)
            }
        }

        val byLevel = matches.groupingBy { it.compLevel }.eachCount()
        log.info(
            "FTC Match Sync complete for $eventKey: Qualifications=${byLevel["qm"] ?: 0}, " +
                "Semifinals/Playoffs=${byLevel["sf"] ?: 0}, Finals=${byLevel["f"] ?: 0}, Practice=${byLevel["practice"] ?: 0}"
        )
        return SyncCounts(teamCount, matches.size, warnings.toList())
    }

    private suspend fun <T> tryFirst(name: String, warnings: MutableList<String>, block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("$name failed: ${e.message}")
        warnings.add("$name: ${e.message}")
        null
    }

    private suspend fun fetchFtcScoutEvent(year: Int, code: String, types: SeasonTypes): JsonObject {
        val query = """
            query(${'$'}season: Int!, ${'$'}code: String!) {
              eventByCode(season: ${'$'}season, code: ${'$'}code) {
                name
                timezone
                start
                end
                teams {
                  teamNumber
                  team {
                    name
                    schoolName
                    sponsors
                    rookieYear
                    website
                    location {
                      city
                      state
                      country
                    }
                  }
                }
                matches {
                  id
                  matchNum
                  series
                  tournamentLevel
                  hasBeenPlayed
                  teams {
                    teamNumber
                    alliance
                    station
                  }
                  scores { ${scoresSelection(types.matchScoreTypes)} }
                }
              }
            }
        """.trimIndent()
        log.info("FTC Sync: Querying event details, teams, and matches for $year $code from FTC Scout API (api.ftcscout.org)...")
        val data = ftcScoutQuery(query, buildJsonObject {
            put("season", year)
            put("code", code)
        })
        return data["eventByCode"] as? JsonObject
            ?: throw FtcSyncException("FTC Scout has no event $code in the $year season")
    }

    /** Must run inside a transaction. */
    private fun writeEvent(eventKey: String, year: Int, code: String, eventObj: JsonObject, now: Instant) {
        val name = (eventObj["name"] as? JsonPrimitive)?.content ?: code
        val timezone = (eventObj["timezone"] as? JsonPrimitive)?.contentOrNull ?: "UTC"
        val start = (eventObj["start"] as? JsonPrimitive)?.contentOrNull
        val end = (eventObj["end"] as? JsonPrimitive)?.contentOrNull
        // Teams and matches have their own tables; keeping them out of the event row keeps it small
        // (the events page reads every event row's JSON).
        val eventJson = JsonSupport.json.encodeToString(JsonElement.serializer(), JsonObject(eventObj - "teams" - "matches"))
        EventTeamCanonical.adoptManualEvents(eventKey, year, code)
        val existing = ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.limit(1).firstOrNull()
        if (existing == null) {
            ApiEvents.insert {
                it[ApiEvents.eventKey] = eventKey
                it[ApiEvents.name] = name
                it[ApiEvents.year] = year
                it[ApiEvents.eventCode] = code
                it[ApiEvents.startDate] = start
                it[ApiEvents.endDate] = end
                it[ApiEvents.timezone] = timezone
                it[ApiEvents.dataJson] = eventJson
                it[ApiEvents.updatedAt] = now
            }
        } else {
            // Always written: updatedAt is when the event last synced, which the ended-event daily refresh reads.
            ApiEvents.update({ ApiEvents.id eq existing[ApiEvents.id] }) {
                it[ApiEvents.name] = name
                it[ApiEvents.year] = year
                it[ApiEvents.eventCode] = code
                it[ApiEvents.startDate] = start
                it[ApiEvents.endDate] = end
                it[ApiEvents.timezone] = timezone
                it[ApiEvents.dataJson] = eventJson
                it[ApiEvents.updatedAt] = now
            }
        }
    }

    /** A FIRST FTC API team, reduced to the fields it adds. */
    internal data class FirstFtcTeam(val nickname: String?, val city: String?, val state: String?, val country: String?)

    /**
     * Writes the roster. Name and JSON come from FTC Scout. Nickname and location come from FIRST when this
     * sync has FIRST data; otherwise a stored value is kept, because rows are shared by every team at the
     * event and a team without FIRST credentials shouldn't undo what a team with them stored.
     * Unchanged rows aren't written. Returns the number of teams. Must run inside a transaction.
     */
    private fun writeTeams(eventKey: String, teamsArray: List<JsonElement>, firstTeams: Map<Int, FirstFtcTeam>?, now: Instant): Int {
        val existingTeams = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }.associateBy { it[ApiTeams.teamKey] }
        val seen = mutableSetOf<String>()
        teamsArray.forEach { participation ->
            val partObj = participation.safeJsonObject ?: return@forEach
            val teamNumber = (partObj["teamNumber"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val teamObj = partObj["team"]?.safeJsonObject ?: return@forEach
            val teamKey = "ftc$teamNumber"
            if (!seen.add(teamKey)) return@forEach
            val location = teamObj["location"]?.safeJsonObject
            val scout = FirstFtcTeam(
                nickname = (teamObj["name"] as? JsonPrimitive)?.contentOrNull,
                city = (location?.get("city") as? JsonPrimitive)?.contentOrNull,
                state = (location?.get("state") as? JsonPrimitive)?.contentOrNull,
                country = (location?.get("country") as? JsonPrimitive)?.contentOrNull
            )
            val teamName = scout.nickname ?: "Team $teamNumber"
            val teamJson = JsonSupport.json.encodeToString(JsonElement.serializer(), teamObj)
            val existing = existingTeams[teamKey]
            val resolved = resolveFtcTeamFields(
                scout = scout,
                first = firstTeams?.get(teamNumber),
                firstAvailable = firstTeams != null,
                stored = existing?.let {
                    FirstFtcTeam(it[ApiTeams.nickname], it[ApiTeams.city], it[ApiTeams.state], it[ApiTeams.country])
                }
            )
            if (existing == null) {
                ApiTeams.insert {
                    it[ApiTeams.eventKey] = eventKey
                    it[ApiTeams.teamKey] = teamKey
                    it[ApiTeams.teamNumber] = teamNumber
                    it[ApiTeams.name] = teamName
                    it[ApiTeams.nickname] = resolved.nickname ?: teamName
                    it[ApiTeams.city] = resolved.city ?: ""
                    it[ApiTeams.state] = resolved.state ?: ""
                    it[ApiTeams.country] = resolved.country ?: ""
                    it[ApiTeams.opr] = 0.0
                    it[ApiTeams.epa] = 0.0
                    it[ApiTeams.dataJson] = teamJson
                    it[ApiTeams.updatedAt] = now
                }
            } else {
                val nickname = resolved.nickname ?: teamName
                val city = resolved.city ?: ""
                val state = resolved.state ?: ""
                val country = resolved.country ?: ""
                if (existing[ApiTeams.name] != teamName || existing[ApiTeams.nickname] != nickname ||
                    existing[ApiTeams.city] != city || existing[ApiTeams.state] != state ||
                    existing[ApiTeams.country] != country || existing[ApiTeams.dataJson] != teamJson
                ) {
                    ApiTeams.update({ ApiTeams.id eq existing[ApiTeams.id] }) {
                        it[ApiTeams.name] = teamName
                        it[ApiTeams.nickname] = nickname
                        it[ApiTeams.city] = city
                        it[ApiTeams.state] = state
                        it[ApiTeams.country] = country
                        it[ApiTeams.dataJson] = teamJson
                        it[ApiTeams.updatedAt] = now
                    }
                }
            }
        }
        EventTeamCanonical.deduplicateTeamsForEvent(eventKey)
        return seen.size
    }

    /**
     * Picks nickname and location: FIRST's non-blank values when this sync has FIRST data, otherwise the
     * stored non-blank values, and FTC Scout's to fill whatever is still blank.
     */
    internal fun resolveFtcTeamFields(
        scout: FirstFtcTeam,
        first: FirstFtcTeam?,
        firstAvailable: Boolean,
        stored: FirstFtcTeam?
    ): FirstFtcTeam {
        fun pick(firstValue: String?, storedValue: String?, scoutValue: String?): String? {
            val preferred = if (firstAvailable) firstValue else storedValue
            return preferred?.takeIf { it.isNotBlank() } ?: scoutValue?.takeIf { it.isNotBlank() } ?: storedValue?.takeIf { it.isNotBlank() }
        }
        return FirstFtcTeam(
            nickname = pick(first?.nickname, stored?.nickname, scout.nickname),
            city = pick(first?.city, stored?.city, scout.city),
            state = pick(first?.state, stored?.state, scout.state),
            country = pick(first?.country, stored?.country, scout.country)
        )
    }

    /**
     * Turns FTC Scout's and FIRST's match lists into one record per match. Both describe the same matches,
     * so each pair is merged with [MatchCanonical.mergeRecords]: the copy with scores wins, FTC Scout on a tie.
     */
    internal fun buildMatches(eventKey: String, scoutMatches: List<JsonElement>, firstMatches: List<JsonElement>): List<MatchSyncRecord> {
        val combined = scoutMatches.map { it to "ftcscout" } + firstMatches.map { it to "first" }
        // Find max series number for DoubleElim matches to identify finals transition
        val maxDoubleElimSeries = combined.mapNotNull { (match, _) ->
            val m = match.safeJsonObject
            val level = (m?.get("tournamentLevel") as? JsonPrimitive)?.content?.uppercase() ?: ""
            if (level == "DOUBLEELIM" || level == "PLAYOFF") (m?.get("series") as? JsonPrimitive)?.intOrNull else null
        }.maxOrNull() ?: 0

        val finalsBoundary = when {
            maxDoubleElimSeries > 12 -> 14  // 8 alliances (finals at 14+)
            maxDoubleElimSeries > 8  -> 10  // 6 alliances (finals at 10+)
            else -> 6                       // 4 alliances (finals at 6+)
        }

        val byKey = linkedMapOf<String, MatchSyncRecord>()
        combined.forEach { (match, source) ->
            val mObj = match.safeJsonObject ?: return@forEach
            val matchNum = (mObj["matchNum"] as? JsonPrimitive)?.intOrNull
                ?: (mObj["matchNumber"] as? JsonPrimitive)?.intOrNull
                ?: return@forEach
            val rawLevelUpper = ((mObj["tournamentLevel"] as? JsonPrimitive)?.content ?: "QUALS").uppercase()
            val series = (mObj["series"] as? JsonPrimitive)?.intOrNull ?: 1

            val compLevel = when (rawLevelUpper) {
                "QUALS", "QUALIFIER", "QUALIFICATION" -> "qm"
                "SEMIS", "SEMIFINAL" -> "sf"
                "FINALS", "FINAL" -> "f"
                "DOUBLEELIM", "PLAYOFF" -> if (series >= finalsBoundary) "f" else "sf"
                "PRACTICE" -> "practice"
                else -> "qm"
            }
            val mappedMatchNum = if (compLevel == "f" && (rawLevelUpper == "DOUBLEELIM" || rawLevelUpper == "PLAYOFF")) {
                series - finalsBoundary + matchNum
            } else {
                matchNum
            }
            val setNum = if (compLevel == "sf") series else 1

            val red = mutableListOf<String>()
            val blue = mutableListOf<String>()
            (mObj["teams"] as? JsonArray).orEmpty().forEach { part ->
                val pObj = part.safeJsonObject ?: return@forEach
                val num = (pObj["teamNumber"] as? JsonPrimitive)?.intOrNull ?: return@forEach
                val alliance = (pObj["alliance"] as? JsonPrimitive)?.contentOrNull
                    ?: (pObj["station"] as? JsonPrimitive)?.contentOrNull
                    ?: "Red"
                if (alliance.startsWith("red", ignoreCase = true)) red.add("ftc$num") else blue.add("ftc$num")
            }

            val matchKey = when (compLevel) {
                "qm", "practice" -> "${eventKey}_${compLevel}$mappedMatchNum"
                else -> "${eventKey}_${compLevel}${setNum}m$mappedMatchNum"
            }
            val record = MatchSyncRecord(
                matchKey = matchKey,
                eventKey = eventKey,
                compLevel = compLevel,
                setNumber = setNum,
                matchNumber = mappedMatchNum,
                redTeams = red,
                blueTeams = blue,
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), match),
                source = source
            )
            byKey[matchKey] = byKey[matchKey]?.let { MatchCanonical.mergeRecords(it, record) } ?: record
        }
        return byKey.values.toList()
    }

    /** Writes merged matches, resolving each against the stored row and skipping unchanged ones. Must run inside a transaction. */
    private fun writeMatches(eventKey: String, matches: List<MatchSyncRecord>, now: Instant) {
        MatchCanonical.deduplicateDatabaseForEvent(eventKey)
        val existingMatches = ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKey }.associateBy { it[ApiMatches.matchKey] }
        val teamsSerializer = ListSerializer(String.serializer())
        matches.forEach { incoming ->
            val existing = existingMatches[incoming.matchKey]
            val record = if (existing == null) incoming else MatchCanonical.resolveAgainstStored(MatchCanonical.storedRecord(existing), incoming)
            val redJson = JsonSupport.json.encodeToString(teamsSerializer, record.redTeams)
            val blueJson = JsonSupport.json.encodeToString(teamsSerializer, record.blueTeams)
            if (existing == null) {
                ApiMatches.insert {
                    it[ApiMatches.matchKey] = record.matchKey
                    it[ApiMatches.eventKey] = eventKey
                    it[ApiMatches.compLevel] = record.compLevel
                    it[ApiMatches.setNumber] = record.setNumber
                    it[ApiMatches.matchNumber] = record.matchNumber
                    it[ApiMatches.redTeams] = redJson
                    it[ApiMatches.blueTeams] = blueJson
                    it[ApiMatches.dataJson] = record.dataJson
                    it[ApiMatches.updatedAt] = now
                }
            } else if (
                existing[ApiMatches.setNumber] != record.setNumber ||
                existing[ApiMatches.matchNumber] != record.matchNumber ||
                existing[ApiMatches.redTeams] != redJson ||
                existing[ApiMatches.blueTeams] != blueJson ||
                existing[ApiMatches.dataJson] != record.dataJson
            ) {
                ApiMatches.update({ ApiMatches.id eq existing[ApiMatches.id] }) {
                    it[ApiMatches.setNumber] = record.setNumber
                    it[ApiMatches.matchNumber] = record.matchNumber
                    it[ApiMatches.redTeams] = redJson
                    it[ApiMatches.blueTeams] = blueJson
                    it[ApiMatches.dataJson] = record.dataJson
                    it[ApiMatches.updatedAt] = now
                }
            }
        }
    }

    // ---- Stats ------------------------------------------------------------------------------------------

    suspend fun syncStats(settings: ApiSettings, eventKey: String) {
        val (year, code) = extractYearAndCode(eventKey)
        log.info("Syncing FTC stats / OPRs for event $eventKey")

        val teams = withContext(Dispatchers.IO) {
            transaction {
                ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }.map { it[ApiTeams.teamNumber] }
            }
        }
        if (teams.isEmpty()) return

        val oprs = fetchFtcOprs(year, code, teams) ?: return
        val now = Instant.now()
        // Can be reached from a request thread (predictor on-demand sync); keep JDBC off it.
        withContext(Dispatchers.IO) {
            transaction {
                val rows = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }.associateBy { it[ApiTeams.teamNumber] }
                oprs.forEach { (teamNum, opr) ->
                    val row = rows[teamNum] ?: return@forEach
                    if (row[ApiTeams.opr] == opr && row[ApiTeams.epa] == opr) return@forEach
                    ApiTeams.update({ ApiTeams.id eq row[ApiTeams.id] }) {
                        it[ApiTeams.opr] = opr
                        it[ApiTeams.epa] = opr
                        it[ApiTeams.updatedAt] = now
                    }
                }
            }
        }
    }

    /**
     * OPR per team: the team's OPR at this event once it has played here (FTC Scout event stats), otherwise
     * its season OPR (quickStats) so there is still a number before the event starts. Null when neither
     * could be read.
     */
    private suspend fun fetchFtcOprs(year: Int, code: String, teams: List<Int>): Map<Int, Double>? {
        val eventOprs = try {
            fetchEventOprs(year, code)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("FTC Scout event OPRs failed for $year $code: ${e.message}")
            null
        }
        val missing = teams.distinct().filterNot { eventOprs?.containsKey(it) == true }
        val seasonOprs = if (missing.isEmpty()) emptyMap() else fetchQuickStatsOprs(missing, year)
        if (eventOprs == null && seasonOprs == null) return null
        return seasonOprs.orEmpty() + eventOprs.orEmpty()
    }

    private suspend fun fetchEventOprs(year: Int, code: String): Map<Int, Double> {
        val types = seasonTypes(year)
        val data = ftcScoutQuery(
            "query(\$season: Int!, \$code: String!) { eventByCode(season: \$season, code: \$code) { teams { teamNumber stats { ${eventStatsSelection(types.eventStatsTypes)} } } } }",
            buildJsonObject {
                put("season", year)
                put("code", code)
            }
        )
        val event = data["eventByCode"] as? JsonObject ?: throw FtcSyncException("FTC Scout has no event $code in the $year season")
        return (event["teams"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val teamNumber = (obj["teamNumber"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val opr = (((obj["stats"] as? JsonObject)?.get("opr") as? JsonObject)?.get("totalPoints") as? JsonPrimitive)?.doubleOrNull
                ?: return@mapNotNull null
            teamNumber to opr
        }.toMap()
    }

    /**
     * Season OPR (quickStats tot) per team. Teams are batched into aliased GraphQL queries, so a
     * 40-team event is 2 requests instead of 40 sequential round trips. Null when every batch failed.
     */
    private suspend fun fetchQuickStatsOprs(teams: List<Int>, year: Int): Map<Int, Double>? {
        val oprs = mutableMapOf<Int, Double>()
        var anySucceeded = false
        teams.distinct().chunked(25).forEach { chunk ->
            // Team numbers are Ints we read ourselves, so building the aliases into the text is safe.
            val fields = chunk.joinToString("\n") { teamNum ->
                "t$teamNum: teamByNumber(number: $teamNum) { quickStats(season: \$season) { tot { value } } }"
            }
            val data = try {
                ftcScoutQuery("query(\$season: Int!) {\n$fields\n}", buildJsonObject { put("season", year) })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("FTC Scout quickStats query failed: ${e.message}")
                return@forEach
            }
            anySucceeded = true
            chunk.forEach { teamNum ->
                if (!data.containsKey("t$teamNum")) return@forEach
                val quickStats = data["t$teamNum"]?.safeJsonObject?.get("quickStats")?.safeJsonObject
                oprs[teamNum] = (quickStats?.get("tot")?.safeJsonObject?.get("value") as? JsonPrimitive)?.doubleOrNull ?: 0.0
            }
        }
        return if (anySucceeded) oprs else null
    }

    fun getEpaOprHistory(settings: ApiSettings, eventKey: String): Pair<Map<String, Double>, List<JsonElement>> {
        val (year, code) = extractYearAndCode(eventKey)
        val normalizedKey = "${year}${code.lowercase()}"

        val cached = transaction {
            EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq normalizedKey }.firstOrNull()
        }

        if (cached != null) {
            val oprsMap = try {
                JsonSupport.json.decodeFromString<Map<String, Double>>(cached[EpaOprHistoryCache.oprsJson])
            } catch (_: Exception) {
                emptyMap()
            }
            val epaHistoryList = try {
                JsonSupport.json.decodeFromString<List<JsonElement>>(cached[EpaOprHistoryCache.epaHistoryJson])
            } catch (_: Exception) {
                emptyList()
            }

            val updatedAt = cached[EpaOprHistoryCache.updatedAt]
            if (historyRefreshDue(normalizedKey, updatedAt, 900)) {
                SyncScheduler.triggerBackgroundHistorySync(settings, normalizedKey)
            }

            return Pair(oprsMap, epaHistoryList)
        } else {
            SyncScheduler.triggerBackgroundHistorySync(settings, normalizedKey)
            return Pair(emptyMap(), emptyList())
        }
    }

    fun getFullStatsHistory(settings: ApiSettings, eventKey: String): StatsHistoryResponse {
        val (year, code) = extractYearAndCode(eventKey)
        val normalizedKey = "${year}${code.lowercase()}"

        val cached = transaction {
            EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq normalizedKey }.firstOrNull()
        }

        if (cached != null) {
            val oprsMap = try {
                JsonSupport.json.decodeFromString<Map<String, Double>>(cached[EpaOprHistoryCache.oprsJson])
            } catch (_: Exception) {
                emptyMap()
            }
            val epaHistoryList = try {
                JsonSupport.json.decodeFromString<List<JsonElement>>(cached[EpaOprHistoryCache.epaHistoryJson])
            } catch (_: Exception) {
                emptyList()
            }
            val match13HistoryList = try {
                JsonSupport.json.decodeFromString<List<JsonElement>>(cached[EpaOprHistoryCache.match13HistoryJson])
            } catch (_: Exception) {
                emptyList()
            }

            val updatedAt = cached[EpaOprHistoryCache.updatedAt]
            if (historyRefreshDue(normalizedKey, updatedAt, 900)) {
                SyncScheduler.triggerBackgroundHistorySync(settings, normalizedKey)
            }

            return StatsHistoryResponse(
                oprs = oprsMap,
                epaHistory = epaHistoryList,
                match13History = match13HistoryList
            )
        } else {
            SyncScheduler.triggerBackgroundHistorySync(settings, normalizedKey)
            return StatsHistoryResponse()
        }
    }

    suspend fun syncEpaOprHistory(settings: ApiSettings, eventKey: String) {
        val (year, code) = extractYearAndCode(eventKey)
        val normalizedKey = "${year}${code.lowercase()}"

        val teams = withContext(Dispatchers.IO) {
            transaction {
                ApiTeams.selectAll().where { ApiTeams.eventKey eq normalizedKey }.map { it[ApiTeams.teamNumber] }
            }
        }
        // Null (nothing could be read) leaves the shared cache as it is.
        val oprs = fetchFtcOprs(year, code, teams)?.mapKeys { (teamNum, _) -> "ftc$teamNum" }
        withContext(Dispatchers.IO) {
            IntegrationService.writeStatsHistory(normalizedKey, oprs, epaHistory = null, match13History = null)
        }
    }

    // ---- FIRST FTC API ----------------------------------------------------------------------------------

    /**
     * FIRST FTC API GET, revalidated with If-Modified-Since. 404 (nothing at that path) gives null; any
     * other failure throws so it is reported as a warning instead of looking like an empty event.
     */
    private suspend fun firstFtcGet(settings: ApiSettings, year: Int, path: String): JsonObject? {
        val user = settings.apiKeys.firstUsername
        val key = settings.apiKeys.firstKey
        val url = "https://ftc-api.firstinspires.org/v2.0/$year/$path"
        val credentials = Base64.getEncoder().encodeToString("$user:$key".toByteArray())
        val cacheKey = "${ConditionalHttpCache.credentialTag("first-ftc:$user:$key")}|$url"
        val cached = httpCache.get(cacheKey)
        val response = try {
            client.get(url) {
                header(HttpHeaders.Authorization, "Basic $credentials")
                header(HttpHeaders.Accept, "application/json")
                cached?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
                cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FtcSyncException("FIRST FTC API unreachable for $path: ${e.message}")
        }
        val body = if (response.status == HttpStatusCode.NotModified && cached != null) {
            httpCache.revalidatedCount.incrementAndGet()
            cached.body
        } else {
            val text = response.bodyAsText()
            if (response.status == HttpStatusCode.NotFound) return null
            if (!response.status.isSuccess()) {
                httpCache.remove(cacheKey)
                throw FtcSyncException("FIRST FTC HTTP ${response.status.value} for $path${errorDetail(text)}")
            }
            httpCache.put(cacheKey, ConditionalHttpCache.Entry(response.headers[HttpHeaders.ETag], response.headers[HttpHeaders.LastModified], text))
            text
        }
        return runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw FtcSyncException("FIRST FTC API sent unreadable data for $path")
    }

    private suspend fun fetchFirstFtcTeams(settings: ApiSettings, year: Int, eventCode: String): Map<Int, FirstFtcTeam> {
        log.info("FTC Sync: Querying team details for $eventCode from FIRST FTC Events API (ftc-api.firstinspires.org)...")
        val root = firstFtcGet(settings, year, "teams?eventCode=$eventCode") ?: return emptyMap()
        return (root["teams"] as? JsonArray).orEmpty().mapNotNull { teamItem ->
            val obj = teamItem as? JsonObject ?: return@mapNotNull null
            val teamNumber = (obj["teamNumber"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            teamNumber to FirstFtcTeam(
                nickname = text("nameShort") ?: text("nameOnTablet") ?: text("nameFull"),
                city = text("city"),
                state = text("stateProv"),
                country = text("country")
            )
        }.toMap()
    }

    private suspend fun fetchFirstFtcMatches(settings: ApiSettings, year: Int, eventCode: String): List<JsonElement> {
        log.info("FTC Sync: Querying match results for $eventCode from FIRST FTC Events API (ftc-api.firstinspires.org)...")
        val root = firstFtcGet(settings, year, "matches/$eventCode") ?: return emptyList()
        return (root["matches"] as? JsonArray)?.toList() ?: emptyList()
    }
}
