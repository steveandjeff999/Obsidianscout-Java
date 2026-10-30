package com.obsidianscout.integrations

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.AllianceSelections
import com.obsidianscout.db.EpaOprHistoryCache
import com.obsidianscout.db.MatchPlans
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.db.AppSettings
import com.obsidianscout.routes.EventRecord
import com.obsidianscout.routes.MatchRecord
import com.obsidianscout.routes.TeamRecord
import com.obsidianscout.routes.SummaryResponse
import com.obsidianscout.routes.StatsHistoryResponse
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.UserRole
import com.obsidianscout.config.ConfigService
import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.scouting.ScoutingEntryRecord
import com.obsidianscout.analytics.AnalyticsService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


private val firstWorldDivisionToTba = mapOf(
    "milstein" to "mil",
    "curie" to "cur",
    "archimedes" to "arc",
    "daly" to "dal",
    "galileo" to "gal",
    "hopper" to "hop",
    "johnson" to "joh",
    "newton" to "new"
)

private val tbaWorldDivisionToFirst = firstWorldDivisionToTba.entries.associate { (first, tba) -> tba to first }

internal fun canonicalTbaEventCode(code: String?): String {
    val normalized = code.orEmpty().trim().lowercase()
    return firstWorldDivisionToTba[normalized] ?: normalized
}

internal fun canonicalTbaEventKey(year: Int, eventKeyOrCode: String?): String {
    val normalized = eventKeyOrCode.orEmpty().trim().lowercase()
    if (normalized.isBlank()) {
        return ""
    }
    val keyYear = normalized.take(4)
    return if (keyYear.all { it.isDigit() } && normalized.length > 4) {
        "$keyYear${canonicalTbaEventCode(normalized.drop(4))}"
    } else {
        "$year${canonicalTbaEventCode(normalized)}"
    }
}

internal fun canonicalStoredEventKey(year: Int, eventKey: String?): String {
    val normalized = eventKey.orEmpty().trim().lowercase()
    if (normalized.isBlank()) {
        return ""
    }
    val keyYear = normalized.take(4)
    if (keyYear.all { it.isDigit() } && normalized.length > 4) {
        return canonicalTbaEventKey(year, normalized)
    }
    val canonicalCode = canonicalTbaEventCode(normalized)
    return if (canonicalCode != normalized || tbaWorldDivisionToFirst.containsKey(canonicalCode)) {
        "$year$canonicalCode"
    } else {
        normalized
    }
}

internal fun firstEventCodeFor(year: Int, eventCode: String?, eventKey: String?): String {
    val rawCode = eventCode.orEmpty().trim().ifBlank {
        val normalizedKey = eventKey.orEmpty().trim()
        if (normalizedKey.length > 4 && normalizedKey.take(4).all { it.isDigit() }) {
            normalizedKey.drop(4)
        } else {
            normalizedKey
        }
    }
    val canonicalTbaCode = canonicalTbaEventCode(rawCode)
    return (tbaWorldDivisionToFirst[canonicalTbaCode] ?: canonicalTbaCode).uppercase()
}

object IntegrationService {
    private val log = LoggerFactory.getLogger("IntegrationService")
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

    suspend fun syncEvents(settings: ApiSettings): Int {
        if (settings.program == "FTC") {
            return FtcIntegrationService.syncEvents(settings)
        }
        val events = fetchMergedEvents(settings)
        val now = Instant.now()
        withContext(Dispatchers.IO) {
            transaction {
                EventTeamCanonical.adoptManualEventsInto(events.map { event ->
                    val code = resolveEventCode(event).clipEventCode()
                    Triple(resolveEventKey(event, code), event.year, code)
                })
                events.forEach { event -> upsertEvent(event, now, fromEventList = true) }
            }
        }
        return events.size
    }

    /**
     * @param tbaAndFirstOnly skip the Statbotics and Match 13 stat lookups. Used for the daily refresh of
     *   ended events: TBA and FIRST can still add late data, while the rating sites have nothing new to give.
     */
    suspend fun syncEventData(settings: ApiSettings, tbaAndFirstOnly: Boolean = false): SyncCounts {
        if (settings.program == "FTC") {
            return FtcIntegrationService.syncEventData(settings).also {
                applyAssignmentShifts(settings, settings.resolvedEventKey(), "${settings.year}${settings.eventCode.lowercase()}")
            }
        }
        val eventKey = canonicalTbaEventKey(settings.year, settings.resolvedEventKey())
        if (eventKey.isBlank()) {
            return SyncCounts(0, 0)
        }
        return syncFrcEventData(settings, eventKey, tbaAndFirstOnly).also {
            applyAssignmentShifts(settings, eventKey, settings.resolvedEventKey())
        }
    }

    suspend fun syncCustomEventData(settings: ApiSettings, eventKey: String): SyncCounts {
        if (settings.program == "FTC") {
            return FtcIntegrationService.syncCustomEventData(settings, eventKey).also {
                applyAssignmentShifts(settings, eventKey)
            }
        }
        val key = canonicalStoredEventKey(settings.year, eventKey)
        if (key.isBlank()) {
            return SyncCounts(0, 0)
        }
        return syncFrcEventData(settings, key, tbaAndFirstOnly = false).also {
            applyAssignmentShifts(settings, key, eventKey)
        }
    }

    /** Shifts planned before the schedule existed get their scouts once the matches arrive. */
    private fun applyAssignmentShifts(settings: ApiSettings, vararg eventKeys: String) {
        com.obsidianscout.scouting.ScoutingAssignmentService.applyShiftsAfterSync(settings.program, eventKeys.toList())
    }

    /**
     * One FRC event's teams, matches and stats from every configured source. A source that fails (TBA down,
     * FIRST credentials wrong, event missing from one site) no longer sinks the others: its data is skipped,
     * the rows it would have refreshed keep their stored values, and the failure comes back as a warning.
     * Only when every configured source fails does the sync itself fail.
     */
    private suspend fun syncFrcEventData(settings: ApiSettings, eventKey: String, tbaAndFirstOnly: Boolean): SyncCounts {
        val warnings = java.util.Collections.synchronizedList(mutableListOf<String>())
        val (event, teams, matches) = coroutineScope {
            val eventDeferred = async { fetchEventDetail(settings, eventKey) }
            val teamsDeferred = async { fetchMergedTeams(settings, eventKey, warnings) }
            val eventRecord = eventDeferred.await()
            val timezone = eventRecord.timezone?.takeIf { it.isNotBlank() } ?: settings.timezone.takeIf { it.isNotBlank() } ?: "UTC"
            val matchesDeferred = async { fetchMergedMatches(settings, eventKey, timezone, warnings) }
            Triple(eventRecord, teamsDeferred.await(), matchesDeferred.await())
        }

        // Guard: if every team returned by the FRC APIs has an "ftc"-prefixed key, we
        // accidentally matched an FTC event. Abort rather than polluting the database.
        if (teams.isNotEmpty() && teams.all { it.teamKey.lowercase().startsWith("ftc") }) {
            log.warn(
                "FRC sync for $eventKey received only FTC-prefixed team keys — " +
                "the event code likely belongs to the FTC program. Aborting write."
            )
            return SyncCounts(0, 0)
        }

        writeEventData(event, eventKey, teams, matches)
        try {
            syncStats(settings, eventKey, tbaOnly = tbaAndFirstOnly)
        } catch (e: Exception) {
            log.warn("Failed to automatically sync stats after event data sync: ${e.message}")
            warnings.add("Stats: ${e.message}")
        }
        return SyncCounts(teams.size, matches.size, warnings.toList())
    }

    private suspend fun fetchEventDetail(settings: ApiSettings, eventKey: String): EventSyncRecord {
        val fromFirst = suspend {
            try {
                fetchFirstEventDetail(settings, eventKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("FIRST event detail failed for $eventKey: ${e.message}")
                null
            }
        }
        // When neither API answers, keep what is stored: a placeholder would wipe the event's name and dates
        // (the ended-event refresh reads the end date).
        val stored = suspend {
            readTransaction {
                ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey.lowercase().trim() }.limit(1).firstOrNull()
            }?.let { row ->
                EventSyncRecord(
                    eventKey = row[ApiEvents.eventKey],
                    year = row[ApiEvents.year],
                    eventCode = row[ApiEvents.eventCode],
                    name = row[ApiEvents.name],
                    startDate = row[ApiEvents.startDate],
                    endDate = row[ApiEvents.endDate],
                    timezone = row[ApiEvents.timezone],
                    dataJson = row[ApiEvents.dataJson]
                )
            }
        }
        return fetchTbaEventDetail(settings, eventKey)
            ?: fromFirst()
            ?: stored()
            ?: EventSyncRecord(
                eventKey = eventKey,
                year = settings.year,
                eventCode = settings.eventCode.ifBlank { eventKey.removePrefix(settings.year.toString()) },
                name = eventKey,
                startDate = null,
                endDate = null,
                timezone = settings.timezone.takeIf { it.isNotBlank() } ?: "UTC",
                dataJson = "{}"
            )
    }

    /**
     * Upserts one event's synced teams and matches.
     *
     * Rows are shared by every team at the event, and teams sync with different keys: one may have TBA and
     * FIRST, another only FIRST. Writing whatever each sync fetched would flip a row between the two
     * sources' versions every pass. Instead each row keeps the better of what is stored and what came in
     * ([MatchCanonical.preferIncoming] for matches, [mergeTeamWithStored] for teams), which settles on one
     * version whatever order teams sync in. Rows that end up unchanged are not written at all.
     */
    private suspend fun writeEventData(
        event: EventSyncRecord,
        eventKey: String,
        teams: List<TeamSyncRecord>,
        matches: List<MatchSyncRecord>
    ) {
        val now = Instant.now()
        withContext(Dispatchers.IO) {
            transaction {
                upsertEvent(event, now)
                val removed = MatchCanonical.deduplicateDatabaseForEvent(eventKey)
                if (removed > 0) {
                    log.info("Cleaned $removed duplicate match row(s) before sync for $eventKey")
                }
                val existingTeamsMap = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }
                    .associateBy { it[ApiTeams.teamKey] }
                teams.forEach { incoming ->
                    val existing = existingTeamsMap[incoming.teamKey]
                    if (existing == null) {
                        ApiTeams.insert {
                            it[ApiTeams.eventKey] = incoming.eventKey
                            it[ApiTeams.teamKey] = incoming.teamKey
                            it[ApiTeams.teamNumber] = incoming.teamNumber
                            it[ApiTeams.name] = incoming.name.clipTeamText()
                            it[ApiTeams.nickname] = incoming.nickname.clipTeamText()
                            it[ApiTeams.city] = incoming.city.clipTeamLocation()
                            it[ApiTeams.state] = incoming.state.clipTeamLocation()
                            it[ApiTeams.country] = incoming.country.clipTeamLocation()
                            it[ApiTeams.opr] = incoming.opr
                            it[ApiTeams.epa] = incoming.epa
                            it[ApiTeams.match13Exp] = incoming.exp
                            it[ApiTeams.dataJson] = incoming.dataJson
                            it[ApiTeams.updatedAt] = now
                        }
                        return@forEach
                    }
                    val team = mergeTeamWithStored(storedTeamRecord(existing, incoming), incoming)
                    if (teamRowDiffers(existing, team)) {
                        ApiTeams.update({ ApiTeams.id eq existing[ApiTeams.id] }) {
                            it[ApiTeams.teamNumber] = team.teamNumber
                            it[ApiTeams.name] = team.name.clipTeamText()
                            it[ApiTeams.nickname] = team.nickname.clipTeamText()
                            it[ApiTeams.city] = team.city.clipTeamLocation()
                            it[ApiTeams.state] = team.state.clipTeamLocation()
                            it[ApiTeams.country] = team.country.clipTeamLocation()
                            if (team.opr != null) {
                                it[ApiTeams.opr] = team.opr
                            }
                            if (team.epa != null) {
                                it[ApiTeams.epa] = team.epa
                            }
                            if (team.exp != null) {
                                it[ApiTeams.match13Exp] = team.exp
                            }
                            it[ApiTeams.dataJson] = team.dataJson
                            it[ApiTeams.updatedAt] = now
                        }
                    }
                }
                EventTeamCanonical.deduplicateTeamsForEvent(eventKey)

                val existingMatchesMap = ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKey }
                    .associateBy { it[ApiMatches.matchKey] }
                matches.forEach { match ->
                    val incoming = MatchCanonical.canonicalize(match)
                    val existing = existingMatchesMap[incoming.matchKey]
                    val canonical = if (existing == null) {
                        incoming
                    } else {
                        MatchCanonical.resolveAgainstStored(MatchCanonical.storedRecord(existing), incoming)
                    }
                    val redJson = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.redTeams)
                    val blueJson = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.blueTeams)
                    if (existing == null) {
                        ApiMatches.insert {
                            it[ApiMatches.matchKey] = canonical.matchKey
                            it[ApiMatches.eventKey] = canonical.eventKey
                            it[ApiMatches.compLevel] = canonical.compLevel
                            it[ApiMatches.setNumber] = canonical.setNumber
                            it[ApiMatches.matchNumber] = canonical.matchNumber
                            it[ApiMatches.scheduledTime] = canonical.scheduledTime
                            it[ApiMatches.actualTime] = canonical.actualTime
                            it[ApiMatches.redTeams] = redJson
                            it[ApiMatches.blueTeams] = blueJson
                            it[ApiMatches.dataJson] = canonical.dataJson
                            it[ApiMatches.updatedAt] = now
                        }
                    } else if (
                        existing[ApiMatches.eventKey] != canonical.eventKey ||
                        existing[ApiMatches.compLevel] != canonical.compLevel ||
                        existing[ApiMatches.setNumber] != canonical.setNumber ||
                        existing[ApiMatches.matchNumber] != canonical.matchNumber ||
                        existing[ApiMatches.scheduledTime] != canonical.scheduledTime ||
                        existing[ApiMatches.actualTime] != canonical.actualTime ||
                        existing[ApiMatches.redTeams] != redJson ||
                        existing[ApiMatches.blueTeams] != blueJson ||
                        existing[ApiMatches.dataJson] != canonical.dataJson
                    ) {
                        ApiMatches.update({ ApiMatches.id eq existing[ApiMatches.id] }) {
                            it[ApiMatches.eventKey] = canonical.eventKey
                            it[ApiMatches.compLevel] = canonical.compLevel
                            it[ApiMatches.setNumber] = canonical.setNumber
                            it[ApiMatches.matchNumber] = canonical.matchNumber
                            it[ApiMatches.scheduledTime] = canonical.scheduledTime
                            it[ApiMatches.actualTime] = canonical.actualTime
                            it[ApiMatches.redTeams] = redJson
                            it[ApiMatches.blueTeams] = blueJson
                            it[ApiMatches.dataJson] = canonical.dataJson
                            it[ApiMatches.updatedAt] = now
                        }
                    }
                }
                MatchCanonical.deduplicateDatabaseForEvent(eventKey)
            }
        }
    }

    private fun storedTeamRecord(row: ResultRow, incoming: TeamSyncRecord) = TeamSyncRecord(
        eventKey = row[ApiTeams.eventKey],
        teamKey = row[ApiTeams.teamKey],
        teamNumber = row[ApiTeams.teamNumber],
        name = row[ApiTeams.name],
        nickname = row[ApiTeams.nickname],
        city = row[ApiTeams.city],
        state = row[ApiTeams.state],
        country = row[ApiTeams.country],
        opr = incoming.opr,
        epa = incoming.epa,
        exp = incoming.exp,
        dataJson = row[ApiTeams.dataJson],
        source = teamSourceOf(row[ApiTeams.dataJson])
    )

    private fun teamRowDiffers(existing: ResultRow, team: TeamSyncRecord): Boolean =
        existing[ApiTeams.teamNumber] != team.teamNumber ||
            existing[ApiTeams.name] != team.name.clipTeamText() ||
            existing[ApiTeams.nickname] != team.nickname.clipTeamText() ||
            existing[ApiTeams.city] != team.city.clipTeamLocation() ||
            existing[ApiTeams.state] != team.state.clipTeamLocation() ||
            existing[ApiTeams.country] != team.country.clipTeamLocation() ||
            (team.opr != null && existing[ApiTeams.opr] != team.opr) ||
            (team.epa != null && existing[ApiTeams.epa] != team.epa) ||
            (team.exp != null && existing[ApiTeams.match13Exp] != team.exp) ||
            existing[ApiTeams.dataJson] != team.dataJson

    /**
     * @param tbaOnly fetch only TBA OPRs, leaving Statbotics EPA and Match 13 xP as stored.
     *
     * Statbotics and Match 13 are only asked when the team uses them (the EPA / xP switches in settings):
     * every page that shows those numbers checks the same switch, and turning one on triggers a sync.
     */
    suspend fun syncStats(settings: ApiSettings, customEventKey: String? = null, tbaOnly: Boolean = false): Int {
        if (settings.program == "FTC") {
            val key = customEventKey ?: settings.resolvedEventKey()
            FtcIntegrationService.syncStats(settings, key)
            return 1
        }
        val eventKey = canonicalStoredEventKey(settings.year, customEventKey ?: settings.resolvedEventKey())
        if (eventKey.isBlank()) {
            return 0
        }
        val tbaEnabled = settings.apiKeys.tbaKey.isNotBlank()
        val statboticsEnabled = !tbaOnly && settings.useStatboticsEpa
        val match13Enabled = !tbaOnly && settings.useMatch13Exp && settings.apiKeys.match13Key.isNotBlank()

        val (oprs, epas, exps) = coroutineScope {
            val oprsDeferred = if (tbaEnabled) async { fetchTbaOprs(settings, eventKey) } else null
            val epasDeferred = if (statboticsEnabled) async { fetchStatboticsEpas(settings, eventKey) } else null
            val expsDeferred = if (match13Enabled) async { fetchMatch13Exps(settings, eventKey) } else null
            Triple(oprsDeferred?.await(), epasDeferred?.await(), expsDeferred?.await())
        }

        if (oprs == null && epas == null && exps == null) {
            return 0
        }
        val now = Instant.now()
        withContext(Dispatchers.IO) {
            transaction {
                val teamsList = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }.toList()
                teamsList.forEach { row ->
                    val teamKey = row[ApiTeams.teamKey]
                    val opr = oprs?.get(teamKey)
                    val epa = epas?.get(teamKey)
                    val exp = exps?.get(teamKey)
                    // Only stats that actually moved count; unchanged rows skip the write.
                    val hasOpr = oprs != null && oprs.containsKey(teamKey) && row[ApiTeams.opr] != opr
                    val hasEpa = epas != null && epas.containsKey(teamKey) && row[ApiTeams.epa] != epa
                    val hasExp = exps != null && exps.containsKey(teamKey) && row[ApiTeams.match13Exp] != exp
                    if (hasOpr || hasEpa || hasExp) {
                        ApiTeams.update({ ApiTeams.id eq row[ApiTeams.id] }) {
                            if (hasOpr) {
                                it[ApiTeams.opr] = opr
                            }
                            if (hasEpa) {
                                it[ApiTeams.epa] = epa
                            }
                            if (hasExp) {
                                it[ApiTeams.match13Exp] = exp
                            }
                            it[ApiTeams.updatedAt] = now
                        }
                    }
                }
            }
        }
        return maxOf(oprs?.size ?: 0, epas?.size ?: 0, exps?.size ?: 0)
    }

    private val lastOnDemandStatsSync = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /**
     * Stats sync for request paths (e.g. the predictor). Before an event starts TBA has no OPRs, so
     * "stats look empty" is true on every view; this caps it at one upstream fetch per event per 5 minutes,
     * on this node (the map) and across the cluster (a lease that is left to expire rather than released).
     */
    suspend fun syncStatsOnDemand(settings: ApiSettings, eventKey: String): Boolean {
        val now = Instant.now()
        val cutoff = now.minusSeconds(300)
        val throttleKey = "${settings.program}-${eventKey.lowercase()}"
        var claimed = false
        lastOnDemandStatsSync.compute(throttleKey) { _, last ->
            if (last != null && last.isAfter(cutoff)) last else now.also { claimed = true }
        }
        if (!claimed) return false
        if (ClusterLease.tryAcquire("stats-on-demand:$throttleKey", 300) == ClusterLease.Result.HELD_ELSEWHERE) return false
        syncStats(settings, eventKey)
        return true
    }

    fun listEvents(
        year: Int?,
        cachedOnly: Boolean = false,
        activeKey: String? = null,
        activeSettings: ApiSettings? = null,
        session: UserSession? = null
    ): List<EventRecord> {
        return readTransaction {
            val normalizedActiveKey = activeKey?.lowercase()?.trim() ?: ""
            val requiredKeys = mutableSetOf<String>()
            val activeMatchesYear = normalizedActiveKey.isNotBlank() && (year == null || normalizedActiveKey.startsWith(year.toString()))
            if (activeMatchesYear) {
                requiredKeys.add(normalizedActiveKey)
            }

            if (cachedOnly) {
                val isFtcSession = session?.program?.equals("FTC", ignoreCase = true) == true
                // Only collect event keys that have teams belonging to the correct program.
                // FTC sessions see events with ftc-prefixed teams; FRC sessions see events
                // with non-ftc-prefixed teams. This prevents cross-program contamination when
                // an FRC and FTC event share the same event key in the database.
                val teamKeyPrefix = if (isFtcSession) "ftc%" else "frc%"
                val teamKeys = ApiTeams.select(ApiTeams.eventKey)
                    .where { ApiTeams.teamKey like teamKeyPrefix }
                    .withDistinct()
                    .map { it[ApiTeams.eventKey].lowercase().trim() }
                val matchKeys = ApiMatches.select(ApiMatches.eventKey)
                    .withDistinct()
                    .map { it[ApiMatches.eventKey].lowercase().trim() }
                // Include manually added events (bare stored key, no year+code computation).
                val manualKeys = ApiEvents.select(ApiEvents.eventKey, ApiEvents.dataJson)
                    .mapNotNull { row ->
                        val json = row[ApiEvents.dataJson]
                        if (json.contains("manual") || json == "{}" || json.isBlank()) {
                            row[ApiEvents.eventKey].lowercase().trim()
                        } else {
                            null
                        }
                    }
                requiredKeys.addAll(teamKeys)
                requiredKeys.addAll(matchKeys)
                requiredKeys.addAll(manualKeys)
            }

            // This team's own events and edits, and the shared events it removed from its list.
            val overlay = session?.let { TeamCacheOverrideService.eventOverlay(it.teamNumber, it.program) }
            if (overlay != null && cachedOnly) {
                requiredKeys.addAll(overlay.visibleKeys)
                requiredKeys.removeAll(overlay.hiddenKeys - normalizedActiveKey)
            }

            val query = ApiEvents.selectAll()
            if (year != null) {
                query.andWhere { ApiEvents.year eq year }
            }
            val sharedEvents = query.orderBy(ApiEvents.startDate, SortOrder.ASC).map { row ->
                // Use the stored eventKey as-is. TBA synced events already have the canonical
                // year-prefixed key (e.g. "2026arc"). Manually added events keep exactly what
                // the user typed. No year+code concatenation here.
                EventRecord(
                    eventKey = row[ApiEvents.eventKey].lowercase().trim(),
                    name = row[ApiEvents.name],
                    year = row[ApiEvents.year],
                    eventCode = row[ApiEvents.eventCode],
                    startDate = row[ApiEvents.startDate],
                    endDate = row[ApiEvents.endDate],
                    timezone = row[ApiEvents.timezone]?.takeIf { it.isNotBlank() } ?: "UTC"
                )
            }
            val events = (overlay?.apply(sharedEvents, year) ?: sharedEvents).toMutableList()

            // Ensure all required/cached keys are represented in events (add fallback EventRecords if missing)
            val existingKeys = events.map { it.eventKey }.toSet()
            requiredKeys.forEach { key ->
                if (!existingKeys.contains(key)) {
                    val detectedYear = if (key.length >= 4 && key.take(4).all { it.isDigit() }) {
                        key.take(4).toIntOrNull()
                    } else null
                    
                    if (year == null || detectedYear == year) {
                        val eventYear = detectedYear ?: year ?: activeSettings?.year ?: java.time.Year.now().value
                        val eventCode = key.removePrefix(eventYear.toString())
                        
                        val isSelfActive = key == normalizedActiveKey
                        val recordName = if (isSelfActive) {
                            "Configured Event: $key"
                        } else {
                            "Event: $key"
                        }
                        val recordTimezone = if (isSelfActive) {
                            activeSettings?.timezone?.takeIf { it.isNotBlank() } ?: "UTC"
                        } else {
                            "UTC"
                        }

                        events.add(
                            EventRecord(
                                eventKey = key,
                                name = recordName,
                                year = eventYear,
                                eventCode = eventCode,
                                startDate = null,
                                endDate = null,
                                timezone = recordTimezone
                            )
                        )
                    }
                }
            }

            val resultEvents = if (cachedOnly) {
                events.filter { requiredKeys.contains(it.eventKey) }
            } else {
                events
            }

            if (session != null) {
                val teamNumber = session.teamNumber
                val partnerTeams = AllianceService.getAlliancePartnerTeams(teamNumber, session.program)
                val visibleTeams = partnerTeams + teamNumber

                val teamAllowedKeys = mutableSetOf<String>()
                if (normalizedActiveKey.isNotBlank()) {
                    teamAllowedKeys.add(normalizedActiveKey)
                }

                val participatingKeys = ApiTeams.select(ApiTeams.eventKey).where {
                    (ApiTeams.teamNumber eq teamNumber) or
                    (ApiTeams.teamKey inList listOf("$teamNumber", "frc$teamNumber", "ftc$teamNumber"))
                }.map { it[ApiTeams.eventKey].lowercase().trim() }
                teamAllowedKeys.addAll(participatingKeys)

                val scoutedMatchKeys = ScoutingEntries.select(ScoutingEntries.eventKey)
                    .where { ScoutingEntries.ownerTeamNumber inList visibleTeams }
                    .mapNotNull { it[ScoutingEntries.eventKey]?.lowercase()?.trim() }
                val scoutedPitKeys = PitScoutingEntries.select(PitScoutingEntries.eventKey)
                    .where { PitScoutingEntries.ownerTeamNumber inList visibleTeams }
                    .mapNotNull { it[PitScoutingEntries.eventKey]?.lowercase()?.trim() }
                val scoutedQualKeys = QualitativeScoutingEntries.select(QualitativeScoutingEntries.eventKey)
                    .where { QualitativeScoutingEntries.ownerTeamNumber inList visibleTeams }
                    .mapNotNull { it[QualitativeScoutingEntries.eventKey]?.lowercase()?.trim() }
                teamAllowedKeys.addAll(scoutedMatchKeys)
                teamAllowedKeys.addAll(scoutedPitKeys)
                teamAllowedKeys.addAll(scoutedQualKeys)

                val allianceEvents = AllianceService.listAlliances(session)
                    .mapNotNull { it.eventKey?.lowercase()?.trim() }
                teamAllowedKeys.addAll(allianceEvents)
                overlay?.let {
                    teamAllowedKeys.addAll(it.visibleKeys)
                    teamAllowedKeys.removeAll(it.hiddenKeys - normalizedActiveKey)
                }

                resultEvents.filter { teamAllowedKeys.contains(it.eventKey.lowercase().trim()) }
            } else {
                resultEvents
            }
        }
    }

    fun listTeams(eventKey: String, session: UserSession): List<TeamRecord> {
        return readTransaction {
            val isFtcSession = session.program.equals("FTC", ignoreCase = true)
            val allTeamRows = TeamCacheOverrideService.teamRows(eventKey, session.teamNumber, session.program)
            val bbotMappings = getBBotMappings(eventKey, cachedTeams = allTeamRows)
            val placeholderToBBot = bbotMappings.associate { it.placeholderKey.lowercase().trim() to it.bbotKey }
            // Filter by program: FTC sessions only see ftc-prefixed team keys; FRC sessions only
            // see non-ftc-prefixed keys. This prevents cross-program contamination when an FRC
            // and FTC event share the same resolved event key string in the database.
            val teamRows = allTeamRows.filter { row ->
                val key = row[ApiTeams.teamKey].lowercase().trim()
                if (isFtcSession) key.startsWith("ftc") else !key.startsWith("ftc")
            }

            val teamNumbers = teamRows.map { it[ApiTeams.teamNumber] }

            val config = ConfigService.getConfig(session.teamNumber, session.program)
            val recencyBias = AllianceService.getEffectiveSettings(session.teamNumber, session.program).recencyBias
            val partnerTeams = AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
            val visibleTeams = partnerTeams + session.teamNumber

            val groupedEntries = if (teamNumbers.isNotEmpty()) {
                val entriesQuery = ScoutingEntries.selectAll().where {
                    (ScoutingEntries.program eq session.program) and
                    (ScoutingEntries.targetTeamNumber inList teamNumbers)
                }
                if (session.role != UserRole.SUPERADMIN) {
                    entriesQuery.andWhere { ScoutingEntries.ownerTeamNumber inList visibleTeams }
                }
                entriesQuery.andWhere { (ScoutingEntries.eventKey eq eventKey) or (ScoutingEntries.isPrescout eq true) }

                val entries = entriesQuery.map { row ->
                    val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                    ScoutingEntryRecord(
                        id = row[ScoutingEntries.id].value.toString(),
                        ownerTeamNumber = row[ScoutingEntries.ownerTeamNumber],
                        targetTeamNumber = row[ScoutingEntries.targetTeamNumber],
                        eventKey = row[ScoutingEntries.eventKey],
                        matchKey = row[ScoutingEntries.matchKey],
                        matchNumber = row[ScoutingEntries.matchNumber],
                        data = data,
                        createdAt = row[ScoutingEntries.createdAt].toString(),
                        isPrescout = row[ScoutingEntries.isPrescout]
                    )
                }
                entries.filter { it.targetTeamNumber != null }.groupBy { it.targetTeamNumber!! }
            } else {
                emptyMap()
            }

            val rows = teamRows.map { row ->
                val originalKey = row[ApiTeams.teamKey].lowercase().trim()
                val resolvedKey = placeholderToBBot[originalKey] ?: row[ApiTeams.teamKey]
                val teamNumber = row[ApiTeams.teamNumber]

                val teamEntries = groupedEntries[teamNumber] ?: emptyList()
                val currentEventEntries = teamEntries.filter { it.eventKey == eventKey && !it.isPrescout }
                val prescoutEntries = teamEntries.filter { it.isPrescout }

                val mergedEntries = if (currentEventEntries.size < 3) {
                    currentEventEntries + prescoutEntries
                } else {
                    currentEventEntries
                }

                val avgScore = com.obsidianscout.analytics.RecencyWeighting.weightedAverage(mergedEntries, recencyBias) { entry ->
                    AnalyticsService.scoreEntry(config, entry)
                }

                TeamRecord(
                    eventKey = row[ApiTeams.eventKey],
                    teamKey = resolvedKey,
                    teamNumber = teamNumber,
                    name = row[ApiTeams.name],
                    nickname = row[ApiTeams.nickname],
                    city = row[ApiTeams.city],
                    state = row[ApiTeams.state],
                    country = row[ApiTeams.country],
                    opr = row[ApiTeams.opr],
                    epa = row[ApiTeams.epa],
                    exp = row[ApiTeams.match13Exp],
                    averagePoints = avgScore
                )
            }

            rows.groupBy { it.teamNumber }
                .map { (_, group) ->
                    if (group.size == 1) {
                        group.first()
                    } else {
                        val preferred = group.find { it.teamKey.removePrefix("frc").any { c -> !c.isDigit() } }
                            ?: group.first()
                        preferred.copy(
                            name = group.mapNotNull { it.name }.firstOrNull { it.isNotBlank() } ?: preferred.name,
                            nickname = group.mapNotNull { it.nickname }.firstOrNull { it.isNotBlank() } ?: preferred.nickname,
                            city = group.mapNotNull { it.city }.firstOrNull { it.isNotBlank() } ?: preferred.city,
                            state = group.mapNotNull { it.state }.firstOrNull { it.isNotBlank() } ?: preferred.state,
                            country = group.mapNotNull { it.country }.firstOrNull { it.isNotBlank() } ?: preferred.country,
                            opr = group.mapNotNull { it.opr }.firstOrNull() ?: preferred.opr,
                            epa = group.mapNotNull { it.epa }.firstOrNull() ?: preferred.epa,
                            averagePoints = group.mapNotNull { it.averagePoints }.firstOrNull() ?: preferred.averagePoints
                        )
                    }
                }
                .sortedBy { it.teamNumber }
        }
    }

    /** [teamNumber] shows that team's own changes; null is the shared schedule, for work on no team's behalf. */
    fun listMatches(eventKey: String, program: String = "FRC", teamNumber: Int? = null): List<MatchRecord> {
        return readTransaction {
            // Fetch the event venue timezone once so every MatchRecord can carry it.
            // All scheduledTime/actualTime values stored in the DB are UTC epoch seconds.
            val eventRow = if (teamNumber != null) {
                TeamCacheOverrideService.eventRow(eventKey, teamNumber, program)
            } else {
                ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey.lowercase() }.limit(1).firstOrNull()
            }
            val eventTimezone = eventRow
                ?.get(ApiEvents.timezone)
                ?.takeIf { it.isNotBlank() }
                ?: "UTC"

            val isFtcProgram = program.equals("FTC", ignoreCase = true)
            val allTeams = if (teamNumber != null) {
                TeamCacheOverrideService.teamRows(eventKey, teamNumber, program)
            } else {
                ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey.lowercase() }.toList()
            }
            val allMatchRows = if (teamNumber != null) {
                TeamCacheOverrideService.matchRows(eventKey, teamNumber, program)
            } else {
                ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKey.lowercase() }.toList()
            }
            val bbotMappings = getBBotMappings(eventKey, cachedTeams = allTeams, cachedMatches = allMatchRows)

            // Use the caller's program to determine the correct prefix. Fall back to
            // the heuristic (event key contains "ftc" or teams have ftc keys) only
            // when program is ambiguous. This prevents cross-program key pollution.
            val isFtcEvent = isFtcProgram || eventKey.contains("ftc") || allTeams.any { it[ApiTeams.teamKey].startsWith("ftc") }
            val defaultPrefix = if (isFtcEvent) "ftc" else "frc"

            // Build bidirectional resolution maps for B-bots and normal teams
            val teamKeyByNumber = mutableMapOf<Int, String>()
            val teamNumberByKey = mutableMapOf<String, Int>()
            val canonicalKeyByKey = mutableMapOf<String, String>()

            // First populate from B-bot mappings
            bbotMappings.forEach { m ->
                val bKey = if (m.bbotKey.startsWith(defaultPrefix)) m.bbotKey else "$defaultPrefix${m.bbotKey}"
                val pKey = if (m.placeholderKey.startsWith(defaultPrefix)) m.placeholderKey else "$defaultPrefix${m.placeholderKey}"
                val num = m.placeholderNumber

                teamKeyByNumber[num] = bKey
                
                teamNumberByKey[bKey] = num
                teamNumberByKey[bKey.removePrefix(defaultPrefix)] = num
                teamNumberByKey[pKey] = num
                teamNumberByKey[pKey.removePrefix(defaultPrefix)] = num
                
                canonicalKeyByKey[bKey] = bKey
                canonicalKeyByKey[bKey.removePrefix(defaultPrefix)] = bKey
                canonicalKeyByKey[pKey] = bKey
                canonicalKeyByKey[pKey.removePrefix(defaultPrefix)] = bKey
            }

            // Then populate other teams
            allTeams.forEach { row ->
                val origKey = row[ApiTeams.teamKey].lowercase().trim()
                val num = row[ApiTeams.teamNumber]
                val oKey = if (origKey.startsWith(defaultPrefix)) origKey else "$defaultPrefix$origKey"
                
                if (!teamNumberByKey.containsKey(oKey)) {
                    teamKeyByNumber[num] = oKey
                    
                    teamNumberByKey[oKey] = num
                    teamNumberByKey[oKey.removePrefix(defaultPrefix)] = num
                    
                    canonicalKeyByKey[oKey] = oKey
                    canonicalKeyByKey[oKey.removePrefix(defaultPrefix)] = oKey
                }
            }

            // Filter match rows by program: only include matches that contain at least one
            // team key with the correct prefix. This prevents cross-program match leakage
            // when an FRC and FTC event share the same event key in the database.
            val wrongPrefix = if (isFtcProgram) "frc" else "ftc"
            val rows = allMatchRows.filter { row ->
                val redKeys = decodeTeams(row[ApiMatches.redTeams])
                val blueKeys = decodeTeams(row[ApiMatches.blueTeams])
                val allKeys = (redKeys + blueKeys).map { it.trim().lowercase() }
                // Keep the match if NOT all team keys belong to the wrong program.
                // An empty match (no team keys) is kept as-is.
                allKeys.isEmpty() || !allKeys.all { it.startsWith(wrongPrefix) }
            }
            val sortedRows = rows.sortedWith(
                compareBy(
                    { compLevelRank(it[ApiMatches.compLevel]) },
                    { it[ApiMatches.setNumber] ?: 0 },
                    { it[ApiMatches.matchNumber] ?: 0 },
                    { it[ApiMatches.scheduledTime] ?: Long.MAX_VALUE },
                    { it[ApiMatches.matchKey] }
                )
            )

            data class MatchParsed(
                val row: org.jetbrains.exposed.v1.core.ResultRow,
                val compLevel: String,
                val setNumber: Int?,
                val matchNumber: Int?,
                val scheduledTime: Long?,
                val actualTime: Long?,
                val rScore: Int?,
                val bScore: Int?,
                val isPlayed: Boolean,
                val videos: List<com.obsidianscout.routes.MatchVideo>
            )

            val parsedMatches = sortedRows.map { row ->
                val compLevel = row[ApiMatches.compLevel]
                val setNumber = row[ApiMatches.setNumber]
                val matchNumber = row[ApiMatches.matchNumber]
                val sched = row[ApiMatches.scheduledTime]
                val act = row[ApiMatches.actualTime]
                var videos = emptyList<com.obsidianscout.routes.MatchVideo>()
                var reportedPlayed = false
                val (rScore, bScore) = try {
                    val rawDataJson = row[ApiMatches.dataJson]
                    if (rawDataJson.isNotBlank()) {
                        val json = JsonSupport.json.parseToJsonElement(rawDataJson).jsonObject
                        videos = MatchCanonical.extractVideos(json)
                        val scores = matchScoresFromJson(json)
                        reportedPlayed = (json["hasBeenPlayed"] as? JsonPrimitive)?.content == "true"
                        scores
                    } else {
                        Pair(null, null)
                    }
                } catch (_: Exception) {
                    Pair(null, null)
                }
                val hasScore = rScore != null || bScore != null
                // FTC has no match times, so FTC Scout's own played flag stands in for them.
                val isPlayed = hasScore || reportedPlayed || (act != null && act > 0 && sched != null && act != sched)
                MatchParsed(row, compLevel, setNumber, matchNumber, sched, act, rScore, bScore, isPlayed, videos)
            }

            val resolveTeams = { teamKeysJson: String ->
                val rawKeys = decodeTeams(teamKeysJson)
                rawKeys.map { key ->
                    val trimmedKey = key.trim().lowercase()
                    val prefix = if (trimmedKey.startsWith("ftc") || (canonicalKeyByKey[trimmedKey]?.startsWith("ftc") == true)) "ftc" else "frc"
                    val canonicalKey = canonicalKeyByKey[trimmedKey] 
                        ?: (if (trimmedKey.startsWith(prefix)) trimmedKey else "$prefix$trimmedKey")
                    
                    val num = teamNumberByKey[trimmedKey]
                        ?: trimmedKey.removePrefix(prefix).toIntOrNull()
                    
                    if (num != null && canonicalKey.removePrefix(prefix).lowercase() != num.toString()) {
                        val cleanCanonical = if (canonicalKey.startsWith(prefix)) canonicalKey else "$prefix$canonicalKey"
                        "$cleanCanonical/$num"
                    } else {
                        if (num != null && canonicalKey != trimmedKey) {
                            val cleanCanonical = if (canonicalKey.startsWith(prefix)) canonicalKey else "$prefix$canonicalKey"
                            "$cleanCanonical/$num"
                        } else {
                            key
                        }
                    }
                }
            }

            var runningOffsetSeconds: Long = 0L
            var lastPlayedScheduledTime: Long? = null

            parsedMatches.map { m ->
                val sched = m.scheduledTime
                val act = m.actualTime

                // Reset offset across tournament days or long session breaks (> 6h gap between scheduled match times)
                if (sched != null && lastPlayedScheduledTime != null) {
                    val hoursGap = (sched - lastPlayedScheduledTime!!) / 3600
                    if (hoursGap > 6 || hoursGap < -6) {
                        runningOffsetSeconds = 0L
                    }
                }

                // The progressive historical offset available BEFORE this match was played (using prior matches 1..N-1)
                val historicalOffset = runningOffsetSeconds
                val predictedTime = if (sched != null && historicalOffset != 0L) {
                    sched + historicalOffset
                } else {
                    sched
                }

                // If this match was played with a recorded actual time, update the running offset for subsequent matches (N+1...)
                if (m.isPlayed && sched != null && act != null && act > 0 && act != sched) {
                    val matchDrift = act - sched
                    if (matchDrift in -28800..28800) {
                        runningOffsetSeconds = matchDrift
                        lastPlayedScheduledTime = sched
                    }
                } else if (m.isPlayed && sched != null && lastPlayedScheduledTime == null) {
                    lastPlayedScheduledTime = sched
                }

                MatchRecord(
                    matchKey = m.row[ApiMatches.matchKey],
                    eventKey = m.row[ApiMatches.eventKey],
                    compLevel = m.compLevel,
                    setNumber = m.setNumber,
                    matchNumber = m.matchNumber,
                    scheduledTime = sched,
                    actualTime = act,
                    predictedTime = predictedTime,
                    scheduleOffsetSeconds = historicalOffset,
                    redTeams = resolveTeams(m.row[ApiMatches.redTeams]),
                    blueTeams = resolveTeams(m.row[ApiMatches.blueTeams]),
                    label = MatchCanonical.displayLabel(m.compLevel, m.setNumber, m.matchNumber),
                    eventTimezone = eventTimezone,
                    redScore = m.rScore,
                    blueScore = m.bScore,
                    videos = m.videos
                )
            }
        }
    }

    data class BBotMapping(
        val bbotKey: String,
        val placeholderKey: String,
        val placeholderNumber: Int
    )

    fun getBBotMappings(
        eventKey: String,
        cachedTeams: List<org.jetbrains.exposed.v1.core.ResultRow>? = null,
        cachedMatches: List<org.jetbrains.exposed.v1.core.ResultRow>? = null
    ): List<BBotMapping> {
        return readTransaction {
            val allTeams = cachedTeams ?: ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey.lowercase() }.toList()
            val isFtcEvent = eventKey.contains("ftc", ignoreCase = true) ||
                allTeams.any { it[ApiTeams.teamKey].startsWith("ftc", ignoreCase = true) }
            if (isFtcEvent) {
                return@readTransaction emptyList()
            }
            val allMatches = cachedMatches ?: ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKey.lowercase() }.toList()

            val bbotKeysInMatches = mutableSetOf<String>()
            allMatches.forEach { row ->
                val red = decodeTeams(row[ApiMatches.redTeams])
                val blue = decodeTeams(row[ApiMatches.blueTeams])
                (red + blue).forEach { key ->
                    val trimmed = key.trim().lowercase()
                    if (trimmed.removePrefix("frc").any { it.isLetter() }) {
                        bbotKeysInMatches.add(trimmed)
                    }
                }
            }

            val mappings = mutableListOf<BBotMapping>()
            val mappedPlaceholders = mutableSetOf<String>()
            val mappedBBots = mutableSetOf<String>()

            // 1. Map by nickname if nickname matches B-bot pattern (e.g., "254B")
            allTeams.forEach { row ->
                val origKey = row[ApiTeams.teamKey].lowercase().trim()
                val num = row[ApiTeams.teamNumber]
                val nick = (row[ApiTeams.nickname] ?: "").trim()
                if (origKey.removePrefix("frc").all { it.isDigit() } &&
                    nick.matches(Regex("^[0-9]+[a-zA-Z]$"))) {
                    val bbotKey = "frc" + nick.lowercase()
                    mappings.add(BBotMapping(bbotKey, origKey, num))
                    mappedPlaceholders.add(origKey)
                    mappedBBots.add(bbotKey)
                }
            }

            // 2. Map remaining by sorted order
            val unmappedPlaceholders = allTeams
                .filter { (it[ApiTeams.teamNumber] >= 9900 || it[ApiTeams.teamKey].removePrefix("frc").startsWith("99")) &&
                    !mappedPlaceholders.contains(it[ApiTeams.teamKey].lowercase().trim()) }
                .sortedBy { it[ApiTeams.teamKey] }

            val unmappedBBots = bbotKeysInMatches
                .filter { !mappedBBots.contains(it) }
                .sorted()

            val count = minOf(unmappedPlaceholders.size, unmappedBBots.size)
            for (i in 0 until count) {
                val placeholder = unmappedPlaceholders[i]
                val bbotKey = unmappedBBots[i]
                mappings.add(
                    BBotMapping(
                        bbotKey = bbotKey,
                        placeholderKey = placeholder[ApiTeams.teamKey].lowercase().trim(),
                        placeholderNumber = placeholder[ApiTeams.teamNumber]
                    )
                )
            }

            mappings
        }
    }

    fun summary(
        session: UserSession? = null,
        targetTeamNumber: Int? = null,
        eventKey: String? = null
    ): SummaryResponse {
        return try {
            readTransaction {
                val isSuperAdmin = session?.role == UserRole.SUPERADMIN
                val userTeamNumber = session?.teamNumber ?: 0
                val program = session?.program ?: "FRC"

                // Available teams calculation
                val availableTeams: List<Int> = if (isSuperAdmin) {
                    val teamsFromUsers = Users.select(Users.teamNumber).mapNotNull { it[Users.teamNumber].takeIf { t -> t > 0 } }
                    val teamsFromEntries = ScoutingEntries.select(ScoutingEntries.ownerTeamNumber).mapNotNull { it[ScoutingEntries.ownerTeamNumber].takeIf { t -> t > 0 } }
                    val teamsFromPit = PitScoutingEntries.select(PitScoutingEntries.ownerTeamNumber).mapNotNull { it[PitScoutingEntries.ownerTeamNumber].takeIf { t -> t > 0 } }
                    val teamsFromQual = QualitativeScoutingEntries.select(QualitativeScoutingEntries.ownerTeamNumber).mapNotNull { it[QualitativeScoutingEntries.ownerTeamNumber].takeIf { t -> t > 0 } }
                    val teamsFromSettings = AppSettings.select(AppSettings.teamNumber).mapNotNull { it[AppSettings.teamNumber].takeIf { t -> t > 0 } }
                    (teamsFromUsers + teamsFromEntries + teamsFromPit + teamsFromQual + teamsFromSettings)
                        .distinct()
                        .sorted()
                } else if (session != null && userTeamNumber > 0) {
                    val partnerTeams = AllianceService.getAlliancePartnerTeams(userTeamNumber, session.program)
                    (listOf(userTeamNumber) + partnerTeams).distinct().sorted()
                } else if (targetTeamNumber != null && targetTeamNumber > 0) {
                    listOf(targetTeamNumber)
                } else {
                    emptyList()
                }

                // Determine whether to run in global mode or scoped to specific scouting team(s)
                val isGlobal = if (isSuperAdmin) {
                    targetTeamNumber == 0 || (targetTeamNumber == null && userTeamNumber == 0)
                } else {
                    session == null && targetTeamNumber == null
                }

                val activeScoutingTeam: Int? = if (isGlobal) {
                    null
                } else if (targetTeamNumber != null && targetTeamNumber > 0) {
                    if (isSuperAdmin || availableTeams.contains(targetTeamNumber)) targetTeamNumber else userTeamNumber
                } else if (userTeamNumber > 0) {
                    userTeamNumber
                } else {
                    availableTeams.firstOrNull()
                }

                val targetTeams = if (isGlobal) {
                    emptyList()
                } else if (activeScoutingTeam != null) {
                    listOf(activeScoutingTeam)
                } else if (availableTeams.isNotEmpty()) {
                    availableTeams
                } else {
                    emptyList()
                }

                if (isGlobal) {
                    val entriesCount = ScoutingEntries.select(ScoutingEntries.id).count().toInt()
                    val pitCount = PitScoutingEntries.select(PitScoutingEntries.id).count().toInt()
                    val qualCount = QualitativeScoutingEntries.select(QualitativeScoutingEntries.id).count().toInt()
                    val eventsCount = ApiEvents.select(ApiEvents.id).count().toInt()
                    val teamsCount = ApiTeams.select(ApiTeams.id).count().toInt()
                    val matchesCount = ApiMatches.select(ApiMatches.id).count().toInt()

                    SummaryResponse(
                        entries = entriesCount,
                        events = eventsCount,
                        teams = teamsCount,
                        matches = matchesCount,
                        pitEntries = pitCount,
                        qualEntries = qualCount,
                        scoutingTeamNumber = null,
                        availableTeams = availableTeams
                    )
                } else {
                    val entriesCount = ScoutingEntries.select(ScoutingEntries.id).where {
                        (ScoutingEntries.ownerTeamNumber inList targetTeams) and (ScoutingEntries.program eq program)
                    }.count().toInt()

                    val pitCount = PitScoutingEntries.select(PitScoutingEntries.id).where {
                        (PitScoutingEntries.ownerTeamNumber inList targetTeams) and (PitScoutingEntries.program eq program)
                    }.count().toInt()

                    val qualCount = QualitativeScoutingEntries.select(QualitativeScoutingEntries.id).where {
                        (QualitativeScoutingEntries.ownerTeamNumber inList targetTeams) and (QualitativeScoutingEntries.program eq program)
                    }.count().toInt()

                    // Resolve team's active event and participated/scouted events
                    val effectiveTeam = activeScoutingTeam ?: userTeamNumber
                    val effectiveSettings = if (effectiveTeam > 0) {
                        AllianceService.getEffectiveSettings(effectiveTeam, program)
                    } else null
                    val normalizedActiveKey = (eventKey?.takeIf { it.isNotBlank() } ?: effectiveSettings?.resolvedEventKey() ?: "").lowercase().trim()

                    val teamAllowedKeys = mutableSetOf<String>()
                    if (normalizedActiveKey.isNotBlank()) {
                        teamAllowedKeys.add(normalizedActiveKey)
                    }

                    if (targetTeams.isNotEmpty()) {
                        val participatingKeys = ApiTeams.select(ApiTeams.eventKey).where {
                            (ApiTeams.teamNumber inList targetTeams) or
                            (ApiTeams.teamKey inList targetTeams.flatMap { listOf("$it", "frc$it", "ftc$it") })
                        }.map { it[ApiTeams.eventKey].lowercase().trim() }
                        teamAllowedKeys.addAll(participatingKeys)

                        val scoutedMatchKeys = ScoutingEntries.select(ScoutingEntries.eventKey)
                            .where { (ScoutingEntries.ownerTeamNumber inList targetTeams) and (ScoutingEntries.program eq program) }
                            .mapNotNull { it[ScoutingEntries.eventKey]?.lowercase()?.trim() }
                        val scoutedPitKeys = PitScoutingEntries.select(PitScoutingEntries.eventKey)
                            .where { (PitScoutingEntries.ownerTeamNumber inList targetTeams) and (PitScoutingEntries.program eq program) }
                            .mapNotNull { it[PitScoutingEntries.eventKey]?.lowercase()?.trim() }
                        val scoutedQualKeys = QualitativeScoutingEntries.select(QualitativeScoutingEntries.eventKey)
                            .where { (QualitativeScoutingEntries.ownerTeamNumber inList targetTeams) and (QualitativeScoutingEntries.program eq program) }
                            .mapNotNull { it[QualitativeScoutingEntries.eventKey]?.lowercase()?.trim() }
                        teamAllowedKeys.addAll(scoutedMatchKeys)
                        teamAllowedKeys.addAll(scoutedPitKeys)
                        teamAllowedKeys.addAll(scoutedQualKeys)
                    }

                    if (session != null) {
                        val allianceEvents = AllianceService.listAlliances(session)
                            .mapNotNull { it.eventKey?.lowercase()?.trim() }
                        teamAllowedKeys.addAll(allianceEvents)
                    }

                    val overlay = if (activeScoutingTeam != null && activeScoutingTeam > 0) {
                        TeamCacheOverrideService.eventOverlay(activeScoutingTeam, program)
                    } else null
                    if (overlay != null) {
                        teamAllowedKeys.addAll(overlay.visibleKeys)
                        teamAllowedKeys.removeAll(overlay.hiddenKeys)
                    }

                    val validEventKeys = teamAllowedKeys.filter { it.isNotBlank() }
                    val eventsCount = validEventKeys.size

                    val teamsCount = if (normalizedActiveKey.isNotBlank()) {
                        if (activeScoutingTeam != null && activeScoutingTeam > 0) {
                            TeamCacheOverrideService.teamRows(normalizedActiveKey, activeScoutingTeam, program).size
                        } else {
                            ApiTeams.selectAll().where { ApiTeams.eventKey eq normalizedActiveKey }.count().toInt()
                        }
                    } else if (validEventKeys.isNotEmpty()) {
                        if (activeScoutingTeam != null && activeScoutingTeam > 0) {
                            validEventKeys.flatMap { ek ->
                                TeamCacheOverrideService.teamRows(ek, activeScoutingTeam, program).map { it[ApiTeams.teamNumber] }
                            }.distinct().size
                        } else {
                            ApiTeams.select(ApiTeams.teamNumber).where { ApiTeams.eventKey inList validEventKeys }.map { it[ApiTeams.teamNumber] }.distinct().size
                        }
                    } else {
                        0
                    }

                    val matchesCount = if (normalizedActiveKey.isNotBlank()) {
                        if (activeScoutingTeam != null && activeScoutingTeam > 0) {
                            TeamCacheOverrideService.matchRows(normalizedActiveKey, activeScoutingTeam, program).size
                        } else {
                            ApiMatches.selectAll().where { ApiMatches.eventKey eq normalizedActiveKey }.count().toInt()
                        }
                    } else if (validEventKeys.isNotEmpty()) {
                        if (activeScoutingTeam != null && activeScoutingTeam > 0) {
                            validEventKeys.sumOf { ek ->
                                TeamCacheOverrideService.matchRows(ek, activeScoutingTeam, program).size
                            }
                        } else {
                            ApiMatches.select(ApiMatches.id).where { ApiMatches.eventKey inList validEventKeys }.count().toInt()
                        }
                    } else {
                        0
                    }

                    SummaryResponse(
                        entries = entriesCount,
                        events = eventsCount,
                        teams = teamsCount,
                        matches = matchesCount,
                        pitEntries = pitCount,
                        qualEntries = qualCount,
                        scoutingTeamNumber = activeScoutingTeam,
                        availableTeams = availableTeams
                    )
                }
            }
        } catch (_: Throwable) {
            SummaryResponse(entries = 0, events = 0, teams = 0, matches = 0, pitEntries = 0, qualEntries = 0)
        }
    }

    /** [teamNumber] shows that team's own event and edits; null is the shared record. */
    fun getEvent(eventKey: String, teamNumber: Int? = null, program: String = "FRC"): EventRecord? {
        return readTransaction {
            val key = eventKey.lowercase().trim()
            val parsedYear = if (key.length >= 4 && key.take(4).all { it.isDigit() }) key.take(4).toIntOrNull() else null
            val parsedCode = if (parsedYear != null && key.length > 4) key.drop(4) else null

            val shared = ApiEvents.selectAll().where {
                var cond = (ApiEvents.eventKey eq key) or (ApiEvents.eventCode eq key)
                if (parsedYear != null && parsedCode != null) {
                    cond = cond or ((ApiEvents.year eq parsedYear) and (ApiEvents.eventCode eq parsedCode))
                }
                cond
            }.limit(1).firstOrNull()
            val resolved = if (teamNumber != null) TeamCacheOverrideService.patchEventRow(shared, key, teamNumber, program) else shared
            resolved?.let { row ->
                val storedCode = row[ApiEvents.eventCode]
                val computedKey = if (!storedCode.isNullOrBlank()) {
                    "${row[ApiEvents.year]}${storedCode}".lowercase()
                } else {
                    row[ApiEvents.eventKey]
                }
                EventRecord(
                    eventKey = computedKey,
                    name = row[ApiEvents.name],
                    year = row[ApiEvents.year],
                    eventCode = storedCode,
                    startDate = row[ApiEvents.startDate],
                    endDate = row[ApiEvents.endDate],
                    timezone = row[ApiEvents.timezone]?.takeIf { it.isNotBlank() } ?: "UTC"
                )
            }
        }
    }

    /** When [eventKey] last came in from a sync: every sync rewrites the event row, from any node. */
    fun eventSyncedAt(eventKey: String): Instant? = readTransaction {
        ApiEvents.select(ApiEvents.updatedAt)
            .where { ApiEvents.eventKey eq eventKey.lowercase().trim() }
            .limit(1)
            .firstOrNull()
            ?.get(ApiEvents.updatedAt)
    }

    private fun decodeTeams(text: String): List<String> {
        return JsonSupport.json.decodeFromString(ListSerializer(String.serializer()), text)
    }

    private fun compLevelRank(compLevel: String): Int {
        return when (MatchCanonical.normalizeCompLevel(compLevel)) {
            "practice" -> 0
            "qm" -> 1
            "qf" -> 2
            "sf" -> 3
            "f" -> 4
            "ef" -> 5
            "playoff" -> 6
            else -> 7
        }
    }

    private fun hasFirstCredentials(settings: ApiSettings): Boolean {
        return settings.apiKeys.firstUsername.isNotBlank() && settings.apiKeys.firstKey.isNotBlank()
    }

    private fun hasTbaCredentials(settings: ApiSettings): Boolean {
        return settings.apiKeys.tbaKey.isNotBlank()
    }

    /** One source's answer: its records, or why it failed. */
    internal class SourceResult<T>(val name: String, val records: List<T>?, val error: String?)

    internal suspend fun <T> trySource(name: String, block: suspend () -> List<T>): SourceResult<T> = try {
        SourceResult(name, block(), null)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("$name failed: ${e.message}")
        SourceResult(name, null, e.message ?: e::class.simpleName)
    }

    /**
     * Combines the configured sources. Some failing is a warning and the rest still count; all failing
     * throws, so the sync is reported as failed instead of as a quiet success with nothing in it.
     */
    internal fun <T> combineSources(what: String, results: List<SourceResult<T>>, warnings: MutableList<String>): List<T> {
        val failed = results.filter { it.records == null }
        if (results.isNotEmpty() && failed.size == results.size) {
            throw IllegalStateException("$what sync failed: " + failed.joinToString("; ") { "${it.name}: ${it.error}" })
        }
        failed.forEach { warnings.add("${it.name}: ${it.error}") }
        return results.flatMap { it.records.orEmpty() }
    }

    private suspend fun fetchMergedEvents(settings: ApiSettings): List<EventSyncRecord> = coroutineScope {
        val tba = if (hasTbaCredentials(settings)) async { trySource("TBA events") { fetchTbaEvents(settings) } } else null
        val first = if (hasFirstCredentials(settings)) async { trySource("FIRST events") { fetchFirstEvents(settings) } } else null
        // Event lists have nowhere to show a partial warning; one source answering is enough.
        combineSources("Event list", listOfNotNull(tba?.await(), first?.await()), mutableListOf())
            .map {
                it.copy(
                    eventKey = canonicalTbaEventKey(it.year, it.eventKey),
                    eventCode = canonicalTbaEventCode(it.eventCode)
                )
            }
            .distinctBy { it.eventKey }
    }

    private suspend fun fetchMergedTeams(
        settings: ApiSettings,
        eventKey: String,
        warnings: MutableList<String>
    ): List<TeamSyncRecord> = coroutineScope {
        val tba = if (hasTbaCredentials(settings)) async { trySource("TBA teams") { fetchTbaTeams(settings, eventKey) } } else null
        val first = if (hasFirstCredentials(settings)) async { trySource("FIRST teams") { fetchFirstTeams(settings, eventKey) } } else null
        val tbaResult = tba?.await()
        val firstResult = first?.await()
        val teams = combineSources("Team", listOfNotNull(tbaResult, firstResult), warnings)
        // FIRST answering with an empty roster while TBA has one almost always means FIRST knows the
        // event under another code (offseason events especially).
        if (firstResult?.records?.isEmpty() == true && tbaResult?.records?.isNotEmpty() == true) {
            warnings.add(
                "FIRST has no teams for event code ${settings.firstEventCode(eventKey)}; " +
                    "if FIRST lists this event under another code, set it as the alternate event code"
            )
        }
        log.info("FRC team sync for $eventKey: TBA ${tbaResult?.records?.size ?: "-"}, FIRST ${firstResult?.records?.size ?: "-"}")
        mergeTeamRecords(teams)
    }

    private suspend fun fetchMergedMatches(
        settings: ApiSettings,
        eventKey: String,
        eventTimezone: String?,
        warnings: MutableList<String>
    ): List<MatchSyncRecord> = coroutineScope {
        val tba = if (hasTbaCredentials(settings)) {
            async { trySource("TBA matches") { fetchTbaMatches(settings, eventKey).map { it.copy(source = "tba") } } }
        } else null
        val first = if (hasFirstCredentials(settings)) {
            async { trySource("FIRST matches") { fetchFirstMatches(settings, eventKey, eventTimezone).map { it.copy(source = "first") } } }
        } else null
        val tbaResult = tba?.await()
        val firstResult = first?.await()
        val matches = combineSources("Match", listOfNotNull(tbaResult, firstResult), warnings)
        log.info("FRC match sync for $eventKey: TBA ${tbaResult?.records?.size ?: "-"}, FIRST ${firstResult?.records?.size ?: "-"}")
        MatchCanonical.mergeAll(matches)
    }

    suspend fun fetchAndUpsertEvent(settings: ApiSettings, eventKey: String): EventRecord? {
        val event = fetchTbaEventDetail(settings, eventKey)
            ?: fetchFirstEventDetail(settings, eventKey)
            ?: EventSyncRecord(
                eventKey = eventKey,
                year = settings.year,
                eventCode = settings.eventCode.ifBlank { eventKey.removePrefix(settings.year.toString()) },
                name = eventKey,
                startDate = null,
                endDate = null,
                timezone = settings.timezone.takeIf { it.isNotBlank() } ?: "UTC",
                dataJson = "{}"
            )
        val now = Instant.now()
        transaction {
            upsertEvent(event, now)
        }
        return getEvent(eventKey)
    }

    private suspend fun upsertEventRecord(settings: ApiSettings, eventKey: String) {
        fetchAndUpsertEvent(settings, eventKey)
    }

    /**
     * @param fromEventList the season list sync: it adopts manual events in one batch beforehand and leaves
     *   unchanged rows alone, so it doesn't rewrite hundreds of events or reset their last-synced time.
     */
    private fun upsertEvent(event: EventSyncRecord, now: Instant, fromEventList: Boolean = false) {
        val resolvedCode = resolveEventCode(event).clipEventCode()
        val resolvedKey = resolveEventKey(event, resolvedCode)
        val clippedName = event.name.clipEventName()
        val clippedStart = event.startDate.clipEventDate()
        val clippedEnd = event.endDate.clipEventDate()
        val clippedTimezone = event.timezone.clipTimezone()
        // A manually created copy of this event (e.g. typed as "okok") moves onto the synced key first.
        if (!fromEventList) {
            EventTeamCanonical.adoptManualEvents(resolvedKey, event.year, resolvedCode)
        }
        val existing = ApiEvents.selectAll().where { ApiEvents.eventKey eq resolvedKey }.limit(1).firstOrNull()
        if (fromEventList && existing != null &&
            existing[ApiEvents.year] == event.year &&
            existing[ApiEvents.eventCode] == resolvedCode &&
            existing[ApiEvents.name] == clippedName &&
            existing[ApiEvents.startDate] == clippedStart &&
            existing[ApiEvents.endDate] == clippedEnd &&
            existing[ApiEvents.timezone] == clippedTimezone &&
            existing[ApiEvents.dataJson] == event.dataJson
        ) {
            return
        }
        if (existing == null) {
            ApiEvents.insert {
                it[ApiEvents.eventKey] = resolvedKey
                it[year] = event.year
                it[eventCode] = resolvedCode
                it[name] = clippedName
                it[startDate] = clippedStart
                it[endDate] = clippedEnd
                it[timezone] = clippedTimezone
                it[dataJson] = event.dataJson
                it[updatedAt] = now
            }
        } else {
            ApiEvents.update({ ApiEvents.id eq existing[ApiEvents.id] }) {
                it[ApiEvents.eventKey] = resolvedKey
                it[year] = event.year
                it[eventCode] = resolvedCode
                it[name] = clippedName
                it[startDate] = clippedStart
                it[endDate] = clippedEnd
                it[timezone] = clippedTimezone
                it[dataJson] = event.dataJson
                it[updatedAt] = now
            }
        }
    }

    private class ApiResponse(val status: HttpStatusCode, val body: String)

    /** A source answered, but not usefully (bad credentials, outage, unknown event). */
    private class ApiSourceException(message: String) : Exception(message)

    internal val httpCache = ConditionalHttpCache()

    /**
     * GET that revalidates against [httpCache]: when the API says 304 (unchanged) the cached body is used.
     * [credential] keeps cached answers apart per key.
     */
    private suspend fun cachedGet(url: String, credential: String, configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit): ApiResponse {
        val cacheKey = "${ConditionalHttpCache.credentialTag(credential)}|$url"
        val cached = httpCache.get(cacheKey)
        val response = client.get(url) {
            configure()
            cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            cached?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }
        if (response.status == HttpStatusCode.NotModified && cached != null) {
            httpCache.revalidatedCount.incrementAndGet()
            log.debug("Unchanged (304): $url")
            return ApiResponse(HttpStatusCode.OK, cached.body)
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            httpCache.put(
                cacheKey,
                ConditionalHttpCache.Entry(response.headers[HttpHeaders.ETag], response.headers[HttpHeaders.LastModified], body)
            )
        } else {
            httpCache.remove(cacheKey)
        }
        return ApiResponse(response.status, body)
    }

    private fun errorDetail(body: String): String =
        body.trim().replace(Regex("\\s+"), " ").take(160).let { if (it.isBlank()) "" else ": $it" }

    /** TBA v3 GET. Throws [ApiSourceException] on any non-success answer, including 404 for an unknown event. */
    private suspend fun tbaGet(settings: ApiSettings, path: String): JsonElement {
        val key = settings.apiKeys.tbaKey
        val response = cachedGet("https://www.thebluealliance.com/api/v3/$path", "tba:$key") {
            header("X-TBA-Auth-Key", key)
            header(HttpHeaders.Accept, "application/json")
        }
        if (!response.status.isSuccess()) {
            throw ApiSourceException("TBA HTTP ${response.status.value} for /$path${errorDetail(response.body)}")
        }
        return JsonSupport.json.parseToJsonElement(response.body.ifBlank { "null" })
    }

    private suspend fun fetchTbaEventDetail(settings: ApiSettings, eventKey: String): EventSyncRecord? {
        if (settings.apiKeys.tbaKey.isBlank()) {
            return null
        }
        val normalizedKey = canonicalTbaEventKey(settings.year, eventKey)
        return try {
            val element = tbaGet(settings, "event/$normalizedKey")
            val obj = element as? JsonObject ?: return null
            EventSyncRecord(
                eventKey = normalizedKey,
                year = obj.readInt("year") ?: settings.year,
                eventCode = obj.readString("event_code") ?: settings.eventCode,
                name = obj.readString("name") ?: normalizedKey,
                startDate = obj.readString("start_date"),
                endDate = obj.readString("end_date"),
                timezone = obj.readString("timezone"),
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), element)
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            log.warn("TBA event fetch failed for $normalizedKey: ${error.message}")
            null
        }
    }

    private suspend fun fetchFirstEventDetail(settings: ApiSettings, eventKey: String): EventSyncRecord? {
        val detectedYear = if (eventKey.length >= 4 && eventKey.take(4).all { it.isDigit() }) {
            eventKey.take(4).toIntOrNull()
        } else null
        val year = detectedYear ?: settings.year
        val eventCode = settings.firstEventCode(eventKey)
        if (eventCode.isBlank()) return null
        val root = fetchFirstJson(settings, year, "events?eventCode=$eventCode")
        val single = root.findArray(listOf("Events", "events")).firstOrNull() as? JsonObject ?: return null
        // Under an alternate code the event still lives at its TBA key, not FIRST's.
        val code = if (settings.usesAlternateEventCode(eventKey)) {
            eventKey.lowercase().trim().removePrefix(year.toString())
        } else {
            single.readString("code") ?: single.readString("eventCode") ?: eventCode
        }
        return EventSyncRecord(
            eventKey = canonicalTbaEventKey(year, code),
            year = year,
            eventCode = code,
            name = single.readString("name") ?: eventKey,
            startDate = single.readString("dateStart") ?: single.readString("startDate"),
            endDate = single.readString("dateEnd") ?: single.readString("endDate"),
            timezone = single.readString("timezone") ?: single.readString("timeZone") ?: single.readString("venueTimeZone"),
            dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), single)
        )
    }

    private suspend fun fetchTbaEvents(settings: ApiSettings): List<EventSyncRecord> {
        if (settings.apiKeys.tbaKey.isBlank()) {
            return emptyList()
        }
        val events = tbaGet(settings, "events/${settings.year}") as? JsonArray
            ?: throw ApiSourceException("TBA returned no event list for ${settings.year}")
        return events.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val eventKey = obj.readString("key") ?: return@mapNotNull null
            EventSyncRecord(
                eventKey = eventKey,
                year = obj.readInt("year") ?: settings.year,
                eventCode = obj.readString("event_code"),
                name = obj.readString("name") ?: eventKey,
                startDate = obj.readString("start_date"),
                endDate = obj.readString("end_date"),
                timezone = obj.readString("timezone"),
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item)
            )
        }
    }

    private suspend fun fetchTbaTeams(settings: ApiSettings, eventKey: String): List<TeamSyncRecord> {
        if (settings.apiKeys.tbaKey.isBlank()) {
            return emptyList()
        }
        val normalizedKey = canonicalTbaEventKey(settings.year, eventKey)
        val teams = tbaGet(settings, "event/$normalizedKey/teams/simple") as? JsonArray
            ?: throw ApiSourceException("TBA returned no team list for $normalizedKey")
        return teams.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val teamKey = obj.readString("key") ?: return@mapNotNull null
            TeamSyncRecord(
                eventKey = normalizedKey,
                teamKey = teamKey,
                teamNumber = obj.readInt("team_number") ?: 0,
                name = obj.readString("name"),
                nickname = obj.readString("nickname"),
                city = obj.readString("city"),
                state = obj.readString("state_prov"),
                country = obj.readString("country"),
                opr = null,
                epa = null,
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item),
                source = "tba"
            )
        }
    }

    private suspend fun fetchTbaMatches(settings: ApiSettings, eventKey: String): List<MatchSyncRecord> {
        if (settings.apiKeys.tbaKey.isBlank()) {
            return emptyList()
        }
        val normalizedKey = canonicalTbaEventKey(settings.year, eventKey)
        // TBA serves practice matches only from their own endpoint; /matches leaves them out.
        val (element, practice) = coroutineScope {
            val practiceDeferred = async { fetchTbaPracticeMatches(settings, normalizedKey) }
            tbaGet(settings, "event/$normalizedKey/matches") to practiceDeferred.await()
        }
        val array = element as? JsonArray ?: throw ApiSourceException("TBA returned no match list for $normalizedKey")
        return practice + parseTbaMatches(array, normalizedKey)
    }

    /** Practice matches are extra: a failure here is logged and never fails the rest of the match sync. */
    private suspend fun fetchTbaPracticeMatches(settings: ApiSettings, normalizedKey: String): List<MatchSyncRecord> {
        return try {
            val array = tbaGet(settings, "event/$normalizedKey/matches/practice") as? JsonArray ?: return emptyList()
            parseTbaMatches(array, normalizedKey)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.message?.contains("HTTP 404") != true) {
                log.warn("TBA practice match fetch failed for $normalizedKey: ${error.message}")
            }
            emptyList()
        }
    }

    private fun parseTbaMatches(array: JsonArray, normalizedKey: String): List<MatchSyncRecord> {
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val matchKey = obj.readString("key") ?: return@mapNotNull null
            val alliances = obj["alliances"] as? JsonObject
            val redTeams = alliances?.get("red").teamKeys()
            val blueTeams = alliances?.get("blue").teamKeys()
            val scheduledTime = obj.readLong("time")
            val tbaActual = obj.readLong("actual_time")
            val actualTime = if (tbaActual != null && tbaActual > 0) tbaActual else scheduledTime
            MatchSyncRecord(
                matchKey = matchKey,
                eventKey = normalizedKey,
                compLevel = obj.readString("comp_level") ?: "",
                setNumber = obj.readInt("set_number"),
                matchNumber = obj.readInt("match_number"),
                scheduledTime = scheduledTime,
                actualTime = actualTime,
                redTeams = redTeams,
                blueTeams = blueTeams,
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item),
                source = "tba"
            )
        }
    }

    // Stats sync and history sync both ask TBA for OPRs back to back; TBA caches them for 60s anyway.
    private val recentTbaOprs = java.util.concurrent.ConcurrentHashMap<String, Pair<Instant, Map<String, Double>>>()

    /**
     * TBA OPRs, or null when they're unknown (no key, or TBA failed). An empty map is a real answer:
     * TBA has no OPRs until matches are played.
     */
    private suspend fun fetchTbaOprs(settings: ApiSettings, eventKey: String): Map<String, Double>? {
        val key = settings.apiKeys.tbaKey
        if (key.isBlank()) {
            return null
        }
        val normalizedKey = canonicalTbaEventKey(settings.year, eventKey)
        val memoKey = "${ConditionalHttpCache.credentialTag(key)}|$normalizedKey"
        recentTbaOprs[memoKey]?.takeIf { it.first.isAfter(Instant.now().minusSeconds(60)) }?.let { return it.second }
        return try {
            val element = tbaGet(settings, "event/$normalizedKey/oprs")
            // TBA returns a bare `null` until the event has played matches.
            val oprs = ((element as? JsonObject)?.get("oprs") as? JsonObject)?.entries?.associate { entry ->
                entry.key to (entry.value as? JsonPrimitive)?.content?.toDoubleOrNull().orZero()
            } ?: emptyMap()
            recentTbaOprs[memoKey] = Instant.now() to oprs
            oprs
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            log.warn("TBA OPR fetch failed for $normalizedKey: ${error.message}")
            null
        }
    }

    private suspend fun fetchStatboticsEpas(settings: ApiSettings, eventKey: String): Map<String, Double> {
        val baseUrl = settings.statboticsBaseUrl.ifBlank { "https://api.statbotics.io" }.trimEnd('/')
        val cleanBaseUrl = baseUrl.removeSuffix("/v3")
        val url = "$cleanBaseUrl/v3/team_events?event=${eventKey}&limit=1000"
        val response = try {
            client.get(url)
        } catch (error: Exception) {
            log.warn("Statbotics fetch failed: ${error.message}")
            return emptyMap()
        }
        if (!response.status.isSuccess()) {
            log.warn("Statbotics fetch failed with status ${response.status}")
            return emptyMap()
        }
        val responseText = response.bodyAsText()
        if (responseText.isBlank()) {
            return emptyMap()
        }
        return try {
            val element = JsonSupport.json.parseToJsonElement(responseText)
            val array = element as? JsonArray ?: return emptyMap()
            array.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val team = obj.readInt("team") ?: return@mapNotNull null
                val epaObj = obj["epa"] as? JsonObject
                val epa = epaObj?.readDouble("total_points")
                    ?: (epaObj?.get("stats") as? JsonObject)?.readDouble("mean")
                    ?: (epaObj?.get("breakdown") as? JsonObject)?.readDouble("total_points")
                    ?: return@mapNotNull null
                "frc${team}" to epa
            }.toMap()
        } catch (e: Exception) {
            log.warn("Failed to parse Statbotics response: ${e.message}")
            emptyMap()
        }
    }

    suspend fun syncEpaOprHistory(settings: ApiSettings, eventKey: String) {
        if (settings.program == "FTC") {
            FtcIntegrationService.syncEpaOprHistory(settings, eventKey)
            return
        }
        val normalizedKey = canonicalStoredEventKey(settings.year, eventKey)
        if (normalizedKey.isBlank()) return
        
        // Each part is null when this team couldn't learn it: no key, the switch is off, or the source failed.
        // The cache is shared by every team at the event, so a null part keeps what another team stored.
        val (oprs, epaHistory, match13History) = coroutineScope {
            val oprsDeferred = async { fetchTbaOprs(settings, normalizedKey) }
            val epaDeferred = async { if (settings.useStatboticsEpa) fetchStatboticsMatchEpaHistory(settings, normalizedKey) else null }
            val match13Deferred = async { if (settings.useMatch13Exp) fetchMatch13MatchHistory(settings, normalizedKey) else null }
            Triple(oprsDeferred.await(), epaDeferred.await(), match13Deferred.await())
        }
        writeStatsHistory(normalizedKey, oprs, epaHistory, match13History)
    }

    /**
     * Stores whichever history parts are known. With none known nothing is written, so the cache keeps its
     * age and page views keep retrying. Shared with FTC.
     */
    internal fun writeStatsHistory(
        eventKey: String,
        oprs: Map<String, Double>?,
        epaHistory: List<JsonElement>?,
        match13History: List<JsonElement>?
    ) {
        if (oprs == null && epaHistory == null && match13History == null) return
        val oprsJson = oprs?.let { JsonSupport.json.encodeToString(it) }
        val epaHistoryJson = epaHistory?.let { JsonSupport.json.encodeToString(it) }
        val match13HistoryJson = match13History?.let { JsonSupport.json.encodeToString(it) }
        val now = java.time.Instant.now()

        transaction {
            val existing = EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq eventKey }.firstOrNull()
            if (existing == null) {
                EpaOprHistoryCache.insert {
                    it[EpaOprHistoryCache.eventKey] = eventKey
                    it[EpaOprHistoryCache.oprsJson] = oprsJson ?: "{}"
                    it[EpaOprHistoryCache.epaHistoryJson] = epaHistoryJson ?: "[]"
                    it[EpaOprHistoryCache.match13HistoryJson] = match13HistoryJson ?: "[]"
                    it[EpaOprHistoryCache.updatedAt] = now
                }
            } else {
                EpaOprHistoryCache.update({ EpaOprHistoryCache.eventKey eq eventKey }) {
                    if (oprsJson != null) it[EpaOprHistoryCache.oprsJson] = oprsJson
                    if (epaHistoryJson != null) it[EpaOprHistoryCache.epaHistoryJson] = epaHistoryJson
                    if (match13HistoryJson != null) it[EpaOprHistoryCache.match13HistoryJson] = match13HistoryJson
                    it[EpaOprHistoryCache.updatedAt] = now
                }
            }
        }
    }

    fun getEpaOprHistory(settings: ApiSettings, eventKey: String): Pair<Map<String, Double>, List<JsonElement>> {
        if (settings.program == "FTC") {
            return FtcIntegrationService.getEpaOprHistory(settings, eventKey)
        }
        val normalizedKey = canonicalStoredEventKey(settings.year, eventKey)
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
            
            // If the cache is older than 15 minutes (a day once the event has ended), refresh in the background
            val updatedAt = cached[EpaOprHistoryCache.updatedAt]
            if (historyRefreshDue(normalizedKey, updatedAt, 900)) {
                SyncScheduler.triggerBackgroundHistorySync(settings, normalizedKey)
            }
            
            return Pair(oprsMap, epaHistoryList)
        } else {
            // No cache at all. Trigger background refresh and return empty/defaults
            SyncScheduler.triggerBackgroundHistorySync(settings, normalizedKey)
            return Pair(emptyMap(), emptyList())
        }
    }

    suspend fun getFullStatsHistory(settings: ApiSettings, eventKey: String): StatsHistoryResponse {
        if (settings.program == "FTC") {
            return FtcIntegrationService.getFullStatsHistory(settings, eventKey)
        }
        val normalizedKey = canonicalStoredEventKey(settings.year, eventKey)
        if (normalizedKey.isBlank()) return StatsHistoryResponse()

        var cached = transaction {
            EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq normalizedKey }.firstOrNull()
        }

        val needsMatch13 = settings.useMatch13Exp
        val isCachedMatch13Missing = cached != null && (cached[EpaOprHistoryCache.match13HistoryJson].isNullOrBlank() || cached[EpaOprHistoryCache.match13HistoryJson] == "[]")

        // Only block the request when there is nothing cached at all. An event Match 13 does not
        // carry yet always has empty Match 13 history, and refetching inline would stall every page view.
        if (cached == null) {
            try {
                syncEpaOprHistory(settings, normalizedKey)
                cached = transaction {
                    EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq normalizedKey }.firstOrNull()
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch initial stats history for $normalizedKey: ${e.message}")
            }
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
            val refreshAfterSeconds = if (needsMatch13 && isCachedMatch13Missing) 120L else 900L
            if (historyRefreshDue(normalizedKey, updatedAt, refreshAfterSeconds)) {
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

    private suspend fun fetchMatch13Exps(settings: ApiSettings, eventKey: String): Map<String, Double> {
        val cleanEventKey = canonicalStoredEventKey(settings.year, eventKey)
        val rawKey = settings.apiKeys.match13Key.trim()
        val key = if (rawKey == "********") "" else rawKey
        // Every Match 13 route needs a key; without one each call is a guaranteed 401.
        if (key.isBlank()) return emptyMap()
        val eventExps = fetchMatch13EventExps(settings, cleanEventKey, key)

        // Match 13 picks up new events after FIRST/TBA do, so a freshly created event can be
        // missing there (404) or only partly filled. Cover the gap from each team's season rating.
        val roster = readTransaction {
            ApiTeams.selectAll().where { ApiTeams.eventKey eq cleanEventKey }.map { it[ApiTeams.teamKey] }
        }
        val missing = roster.filter { it.startsWith("frc") && !eventExps.containsKey(it) }
        if (missing.isEmpty()) return eventExps
        val year = cleanEventKey.take(4).toIntOrNull() ?: settings.year
        val fallback = try {
            fetchMatch13FallbackExps(settings, key, year, missing)
        } catch (e: Exception) {
            log.warn("Match 13 season fallback failed for $cleanEventKey: ${e.message}")
            emptyMap()
        }
        if (fallback.isNotEmpty()) {
            log.info("Match 13: filled xP for ${fallback.size}/${missing.size} team(s) at $cleanEventKey from season ratings")
        }
        return eventExps + fallback
    }

    private data class Match13SeasonRating(val xp: Double, val normXp: Double?)
    private data class Match13SeasonCache(val ratings: Map<Int, Match13SeasonRating>, val fetchedAt: Instant)

    // Season ratings are the same for every account, so one cached copy serves every team on the server
    // and keeps us well inside Match 13's shared 60/min, 1000/hr allowance.
    private val match13SeasonCache = java.util.concurrent.ConcurrentHashMap<Int, Match13SeasonCache>()
    private val match13SeasonLock = Mutex()

    /**
     * xP for teams the event endpoint did not cover: this season's rating first (it tracks the team's
     * most recent event), then last season's normalized xP mapped onto this season's points scale.
     */
    private suspend fun fetchMatch13FallbackExps(
        settings: ApiSettings,
        key: String,
        year: Int,
        teamKeys: List<String>
    ): Map<String, Double> {
        val current = fetchMatch13SeasonRatings(settings, key, year)
        val result = mutableMapOf<String, Double>()
        val withoutSeason = mutableListOf<Int>()
        teamKeys.forEach { teamKey ->
            val teamNumber = teamKey.removePrefix("frc").toIntOrNull() ?: return@forEach
            val rating = current[teamNumber]
            if (rating != null) result[teamKey] = rating.xp else withoutSeason.add(teamNumber)
        }
        if (withoutSeason.isEmpty()) return result

        // xP is in this game's points, so last season's raw number means nothing now. normXp is
        // comparable across seasons; fit this season's xp ~ normXp line and project through it.
        val scale = fitNormXpScale(current.values) ?: return result
        val previous = fetchMatch13SeasonRatings(settings, key, year - 1)
        withoutSeason.forEach { teamNumber ->
            val normXp = previous[teamNumber]?.normXp ?: return@forEach
            result["frc$teamNumber"] = scale.first * normXp + scale.second
        }
        return result
    }

    private fun fitNormXpScale(ratings: Collection<Match13SeasonRating>): Pair<Double, Double>? {
        val points = ratings.mapNotNull { rating -> rating.normXp?.let { it to rating.xp } }
        if (points.size < 20) return null
        val meanX = points.sumOf { it.first } / points.size
        val meanY = points.sumOf { it.second } / points.size
        val varX = points.sumOf { (x, _) -> (x - meanX) * (x - meanX) }
        if (varX < 1e-9) return null
        val slope = points.sumOf { (x, y) -> (x - meanX) * (y - meanY) } / varX
        return slope to (meanY - slope * meanX)
    }

    private suspend fun fetchMatch13SeasonRatings(settings: ApiSettings, key: String, year: Int): Map<Int, Match13SeasonRating> {
        // Match 13 itself marks the live season max-age=60 and finished seasons max-age=86400.
        val ttlSeconds = if (year >= java.time.Year.now().value) 600L else 86_400L
        // An empty result (404, error or timeout) is remembered briefly too, so a Match 13 outage costs
        // one timeout per couple of minutes instead of one per team the scheduler walks through.
        fun fresh() = match13SeasonCache[year]
            ?.takeIf { it.fetchedAt.isAfter(Instant.now().minusSeconds(if (it.ratings.isEmpty()) 120L else ttlSeconds)) }
            ?.ratings
        fresh()?.let { return it }
        return match13SeasonLock.withLock {
            fresh()?.let { return@withLock it }
            val baseUrl = settings.match13BaseUrl.ifBlank { "https://actions.match13.com" }.trimEnd('/')
            val ratings = mutableMapOf<Int, Match13SeasonRating>()
            var page: Int? = 1
            var pagesFetched = 0
            while (page != null && pagesFetched < 10) {
                val response = try {
                    client.get("$baseUrl/v1/years/$year/teams?page=$page&limit=1000") {
                        header(HttpHeaders.Accept, "application/json")
                        header(HttpHeaders.Authorization, "Bearer $key")
                    }
                } catch (error: Exception) {
                    log.warn("Match 13 season ratings fetch failed for $year: ${error.message}")
                    break
                }
                pagesFetched++
                if (!response.status.isSuccess()) {
                    // 404 just means Match 13 has no ratings for that season yet.
                    if (response.status != HttpStatusCode.NotFound) {
                        log.warn("Match 13 season ratings fetch for $year returned ${response.status}")
                    }
                    break
                }
                val body = JsonSupport.json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: break
                (body["teams"] as? JsonArray)?.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val teamNumber = obj.readInt("teamNumber") ?: return@forEach
                    val xp = obj.readDouble("xp") ?: return@forEach
                    ratings[teamNumber] = Match13SeasonRating(xp, obj.readDouble("normXp"))
                }
                page = body.readInt("nextPage")
            }
            match13SeasonCache[year] = Match13SeasonCache(ratings, Instant.now())
            ratings
        }
    }

    private suspend fun fetchMatch13EventExps(settings: ApiSettings, cleanEventKey: String, key: String): Map<String, Double> {
        val rawUrl = settings.match13BaseUrl.ifBlank { "https://actions.match13.com" }.trimEnd('/')
        val url = "$rawUrl/v1/events/${cleanEventKey}/teams"
        val response = try {
            client.get(url) {
                header(HttpHeaders.Accept, "application/json")
                header("X-Match13-Key", key)
                header(HttpHeaders.Authorization, "Bearer $key")
            }
        } catch (error: Exception) {
            log.warn("Match 13 fetch failed: ${error.message}")
            return emptyMap()
        }
        if (!response.status.isSuccess()) {
            if (response.status == HttpStatusCode.NotFound) {
                log.info("Match 13 does not carry $cleanEventKey yet; using season ratings")
            } else {
                log.warn("Match 13 fetch failed with status ${response.status}")
            }
            return emptyMap()
        }
        val responseText = response.bodyAsText()
        if (responseText.isBlank()) {
            return emptyMap()
        }
        return try {
            val element = JsonSupport.json.parseToJsonElement(responseText)
            
            fun extractTeamAndExp(obj: JsonObject): Pair<String, Double>? {
                val teamNumber = obj.readInt("teamNumber")
                    ?: obj.readInt("team_number")
                    ?: obj.readInt("team")
                    ?: (obj.readString("teamKey") ?: obj.readString("team_key"))?.removePrefix("frc")?.toIntOrNull()
                    ?: return null
                val exp = obj.readDouble("xpEnd")
                    ?: obj.readDouble("xpMean")
                    ?: obj.readDouble("xp")
                    ?: obj.readDouble("exp")
                    ?: obj.readDouble("expectedPoints")
                    ?: obj.readDouble("expected_points")
                    ?: (obj["exp"] as? JsonObject)?.readDouble("total_points")
                    ?: (obj["exp"] as? JsonObject)?.readDouble("exp")
                    ?: (obj["exp"] as? JsonObject)?.readDouble("total")
                    ?: (obj["expectedPoints"] as? JsonObject)?.readDouble("total_points")
                    ?: return null
                return "frc$teamNumber" to exp
            }

            when (element) {
                is JsonArray -> {
                    element.mapNotNull { item -> (item as? JsonObject)?.let { extractTeamAndExp(it) } }.toMap()
                }
                is JsonObject -> {
                    val teamsArray = element["teams"] as? JsonArray
                    if (teamsArray != null) {
                        teamsArray.mapNotNull { item -> (item as? JsonObject)?.let { extractTeamAndExp(it) } }.toMap()
                    } else {
                        val expsObj = (element["exps"] as? JsonObject) ?: element
                        expsObj.entries.mapNotNull { (k, v) ->
                            val teamNum = k.removePrefix("frc").toIntOrNull() ?: return@mapNotNull null
                            val expVal = (v as? JsonPrimitive)?.content?.toDoubleOrNull()
                                ?: (v as? JsonObject)?.readDouble("xpEnd")
                                ?: (v as? JsonObject)?.readDouble("xpMean")
                                ?: (v as? JsonObject)?.readDouble("xp")
                                ?: (v as? JsonObject)?.readDouble("exp")
                                ?: return@mapNotNull null
                            "frc$teamNum" to expVal
                        }.toMap()
                    }
                }
                else -> emptyMap()
            }
        } catch (e: Exception) {
            log.warn("Failed to parse Match 13 response: ${e.message}")
            emptyMap()
        }
    }

    /** Null when unknown (no key, or Match 13 failed); empty when Match 13 doesn't carry the event (404). */
    private suspend fun fetchMatch13MatchHistory(settings: ApiSettings, eventKey: String): List<JsonElement>? {
        val cleanEventKey = canonicalStoredEventKey(settings.year, eventKey)
        val rawUrl = settings.match13BaseUrl.ifBlank { "https://actions.match13.com" }.trimEnd('/')
        val rawKey = settings.apiKeys.match13Key.trim()
        val key = if (rawKey == "********") "" else rawKey
        // Keyless calls are always 401s; skipping them matters because history refreshes every 2 min while empty.
        if (key.isBlank()) return null
        val url = "$rawUrl/v1/events/${cleanEventKey}/matches"
        return try {
            val response = client.get(url) {
                header(HttpHeaders.Accept, "application/json")
                if (key.isNotBlank()) {
                    header("X-Match13-Key", key)
                    header(HttpHeaders.Authorization, "Bearer $key")
                }
            }
            if (response.status == HttpStatusCode.NotFound) return emptyList()
            if (!response.status.isSuccess()) {
                log.warn("Match 13 match history fetch failed for $cleanEventKey with status ${response.status}")
                return null
            }
            val text = response.bodyAsText()
            val parsed = JsonSupport.json.parseToJsonElement(text)
            val array = when (parsed) {
                is JsonArray -> parsed
                is JsonObject -> (parsed["matches"] as? JsonArray) ?: (parsed["data"] as? JsonArray) ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            array.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val matchKey = (obj["key"] as? JsonPrimitive)?.content
                    ?: (obj["matchKey"] as? JsonPrimitive)?.content
                    ?: (obj["match"] as? JsonPrimitive)?.content
                    ?: ""
                if (matchKey.isBlank()) return@mapNotNull null
                buildJsonObject {
                    put("key", matchKey)
                    put("matchKey", matchKey)
                    put("eventKey", cleanEventKey)
                    obj["pred"]?.let { put("pred", it) }
                    obj["teams"]?.let { put("teams", it) }
                    obj.forEach { (k, v) ->
                        if (k !in listOf("key", "matchKey", "eventKey", "pred", "teams")) {
                            put(k, v)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            log.warn("Match 13 match history fetch failed for $cleanEventKey: ${error.message}")
            null
        }
    }

    /** Null when Statbotics failed, so the stored history is kept. */
    private suspend fun fetchStatboticsMatchEpaHistory(settings: ApiSettings, eventKey: String): List<JsonElement>? {
        val baseUrl = settings.statboticsBaseUrl.ifBlank { "https://api.statbotics.io" }.trimEnd('/')
        val cleanBaseUrl = baseUrl.removeSuffix("/v3")
        val url = "$cleanBaseUrl/v3/matches?event=${eventKey}&limit=1000"
        return try {
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                log.warn("Statbotics match EPA history fetch failed for $eventKey with status ${response.status}")
                return null
            }
            val text = response.bodyAsText()
            val array = (JsonSupport.json.parseToJsonElement(text) as? JsonArray) ?: return null
            val teamMatches = mutableListOf<JsonElement>()
            array.forEach { matchElem ->
                val matchObj = matchElem as? JsonObject ?: return@forEach
                val matchKey = (matchObj["key"] as? JsonPrimitive)?.content
                    ?: (matchObj["match"] as? JsonPrimitive)?.content ?: ""
                val timestamp = (matchObj["time"] as? JsonPrimitive)?.content
                val alliancesObj = matchObj["alliances"] as? JsonObject
                val redTeams = (alliancesObj?.get("red") as? JsonObject)?.let { it.get("team_keys") as? JsonArray ?: it.get("teams") as? JsonArray }
                val blueTeams = (alliancesObj?.get("blue") as? JsonObject)?.let { it.get("team_keys") as? JsonArray ?: it.get("teams") as? JsonArray }
                val epasMap = (matchObj["post_epas"] as? JsonObject)
                    ?: (matchObj["epas"] as? JsonObject)
                    ?: (matchObj["pre_epas"] as? JsonObject)
                    ?: (matchObj["epa"] as? JsonObject)

                fun processTeam(teamNum: Int, alliance: String) {
                    val epaData = (epasMap?.get(teamNum.toString()) as? JsonObject)
                        ?: (epasMap?.get("frc$teamNum") as? JsonObject)
                    val total = epaData?.readDouble("epa")
                        ?: epaData?.readDouble("total_points")
                        ?: epaData?.readDouble("total")
                        ?: (epaData?.get("breakdown") as? JsonObject)?.readDouble("total_points")
                        ?: ((epasMap?.get(teamNum.toString()) as? JsonPrimitive)?.content?.toDoubleOrNull())
                        ?: 0.0
                    val auto = epaData?.readDouble("auto_epa")
                        ?: epaData?.readDouble("auto_points")
                        ?: epaData?.readDouble("auto")
                        ?: (epaData?.get("breakdown") as? JsonObject)?.readDouble("auto_points")
                        ?: 0.0
                    val teleop = epaData?.readDouble("teleop_epa")
                        ?: epaData?.readDouble("teleop_points")
                        ?: epaData?.readDouble("teleop")
                        ?: (epaData?.get("breakdown") as? JsonObject)?.readDouble("teleop_points")
                        ?: 0.0
                    val endgame = epaData?.readDouble("endgame_epa")
                        ?: epaData?.readDouble("endgame_points")
                        ?: epaData?.readDouble("endgame")
                        ?: (epaData?.get("breakdown") as? JsonObject)?.readDouble("endgame_points")
                        ?: 0.0

                    val teamMatchObj = buildJsonObject {
                        put("team", teamNum)
                        put("match", matchKey)
                        put("alliance", alliance)
                        if (timestamp != null) put("timestamp", timestamp)
                        put("epa", buildJsonObject {
                            put("total_points", total)
                            put("auto_points", auto)
                            put("teleop_points", teleop)
                            put("endgame_points", endgame)
                            put("epa", total)
                            put("auto_epa", auto)
                            put("teleop_epa", teleop)
                            put("endgame_epa", endgame)
                        })
                    }
                    teamMatches.add(teamMatchObj)
                }

                redTeams?.forEach { t ->
                    val num = (t as? JsonPrimitive)?.content?.replace("frc", "")?.toIntOrNull()
                    if (num != null) processTeam(num, "red")
                }
                blueTeams?.forEach { t ->
                    val num = (t as? JsonPrimitive)?.content?.replace("frc", "")?.toIntOrNull()
                    if (num != null) processTeam(num, "blue")
                }
            }
            teamMatches
        } catch (error: Exception) {
            log.warn("Statbotics match EPA history fetch failed for $eventKey: ${error.message}")
            null
        }
    }

    private suspend fun fetchFirstEvents(settings: ApiSettings): List<EventSyncRecord> {
        val root = fetchFirstJson(settings, settings.year, "events")
        val array = root.findArray(listOf("Events", "events"))
        return array.mapNotNull { item ->
            val obj = item.jsonObject
            val eventCode = obj.readString("code") ?: obj.readString("eventCode")
            val eventKey = if (!eventCode.isNullOrBlank()) {
                canonicalTbaEventKey(settings.year, eventCode)
            } else {
                return@mapNotNull null
            }
            EventSyncRecord(
                eventKey = eventKey,
                year = settings.year,
                eventCode = eventCode,
                name = obj.readString("name") ?: eventKey,
                startDate = obj.readString("dateStart") ?: obj.readString("startDate"),
                endDate = obj.readString("dateEnd") ?: obj.readString("endDate"),
                timezone = obj.readString("timezone") ?: obj.readString("timeZone") ?: obj.readString("venueTimeZone"),
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item)
            )
        }
    }

    private suspend fun fetchFirstTeams(settings: ApiSettings, eventKey: String): List<TeamSyncRecord> {
        val detectedYear = if (eventKey.length >= 4 && eventKey.take(4).all { it.isDigit() }) {
            eventKey.take(4).toIntOrNull()
        } else null
        val year = detectedYear ?: settings.year
        val eventCode = settings.firstEventCode(eventKey)
        val root = fetchFirstJson(settings, year, "teams?eventCode=$eventCode")
        val array = root.findArray(listOf("Teams", "teams"))
        return array.mapNotNull { item ->
            val obj = item.jsonObject
            val teamNumber = obj.readInt("teamNumber") ?: obj.readInt("team_number") ?: return@mapNotNull null
            TeamSyncRecord(
                eventKey = eventKey,
                teamKey = "frc${teamNumber}",
                teamNumber = teamNumber,
                name = obj.readString("name") ?: obj.readString("nameFull"),
                nickname = obj.readString("nickname") ?: obj.readString("nameShort"),
                city = obj.readString("city"),
                state = obj.readString("stateProv") ?: obj.readString("state"),
                country = obj.readString("country"),
                opr = null,
                epa = null,
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item),
                source = "first"
            )
        }
    }

    private suspend fun fetchFirstMatches(settings: ApiSettings, eventKey: String, eventTimezone: String? = null): List<MatchSyncRecord> {
        val detectedYear = if (eventKey.length >= 4 && eventKey.take(4).all { it.isDigit() }) {
            eventKey.take(4).toIntOrNull()
        } else null
        val year = detectedYear ?: settings.year
        val eventCode = settings.firstEventCode(eventKey)
        if (eventCode.isBlank()) {
            return emptyList()
        }

        val normalizedEventKey = eventKey.lowercase()
        val tzToUse = eventTimezone?.takeIf { it.isNotBlank() }
            ?: readTransaction {
                ApiEvents.selectAll().where { ApiEvents.eventKey eq normalizedEventKey }.limit(1).firstOrNull()?.get(ApiEvents.timezone)
            }?.takeIf { it.isNotBlank() }
            ?: settings.timezone.takeIf { it.isNotBlank() }
            ?: "UTC"
        val zoneId = resolveZoneId(tzToUse)

        val matchLevels = listOf(
            "Qualification" to "Qualification",
            "Playoff" to "Playoff"
        )
        val scheduleLevels = listOf(
            "Practice" to "Practice",
            "Qualification" to "Qualification",
            "Playoff" to "Playoff"
        )
        val results = linkedMapOf<String, MatchSyncRecord>()

        fun upsert(record: MatchSyncRecord) {
            val existing = results[record.matchKey]
            results[record.matchKey] = if (existing == null) {
                record
            } else {
                MatchCanonical.mergeRecords(existing, record)
            }
        }

        coroutineScope {
            val matchesJobs = matchLevels.map { (apiLevel, defaultLevel) ->
                async {
                    val matchesRoot = fetchFirstJson(settings, year, "matches/$eventCode?tournamentLevel=$apiLevel")
                    parseFirstMatchItems(matchesRoot, normalizedEventKey, defaultLevel, zoneId)
                }
            }
            val scheduleJobs = scheduleLevels.map { (apiLevel, defaultLevel) ->
                async {
                    val scheduleRoot = fetchFirstJson(settings, year, "schedule/$eventCode?tournamentLevel=$apiLevel")
                    parseFirstMatchItems(scheduleRoot, normalizedEventKey, defaultLevel, zoneId)
                }
            }
            // Wait for all fetches to complete concurrently
            val matchResults = matchesJobs.awaitAll()
            val scheduleResults = scheduleJobs.awaitAll()
            (matchResults + scheduleResults).flatten().forEach { upsert(it) }

            // The per-level results calls above cover qualifications and playoffs; the unfiltered call only
            // adds practice results, or stands in when the per-level calls found nothing. Skip it otherwise.
            val hasPracticeSchedule = scheduleResults.first().isNotEmpty()
            if (results.isEmpty() || hasPracticeSchedule) {
                val fallbackMatches = parseFirstMatchItems(
                    fetchFirstJson(settings, year, "matches/$eventCode"), normalizedEventKey, "", zoneId
                )
                if (results.isEmpty()) {
                    fallbackMatches.forEach { upsert(it) }
                } else {
                    fallbackMatches
                        .filter { MatchCanonical.normalizeCompLevel(it.compLevel) == "practice" }
                        .forEach { upsert(it) }
                }
            }
        }

        if (results.isNotEmpty()) {
            log.info("FIRST match sync for $eventCode: ${results.size} matches")
        } else {
            log.warn("FIRST match sync for $eventCode returned no matches")
        }
        return results.values.toList()
    }

    /**
     * FIRST FRC API GET, revalidated with If-Modified-Since. Missing credentials and 404 (nothing at that
     * path) give an empty array; any other failure throws [ApiSourceException] so bad credentials or a
     * wrong event code show up in the sync status instead of looking like an empty event.
     */
    private suspend fun fetchFirstJson(settings: ApiSettings, year: Int, path: String): JsonElement {
        val user = settings.apiKeys.firstUsername
        val key = settings.apiKeys.firstKey
        if (user.isBlank() || key.isBlank()) {
            return JsonArray(emptyList())
        }
        val token = Base64.getEncoder().encodeToString("$user:$key".toByteArray())
        val url = "https://frc-api.firstinspires.org/v3.0/$year/$path"
        val response = try {
            cachedGet(url, "first:$user:$key") {
                header(HttpHeaders.Authorization, "Basic $token")
                header(HttpHeaders.Accept, "application/json")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiSourceException("FIRST API unreachable for $path: ${e.message}")
        }
        if (response.status == HttpStatusCode.NotFound) {
            return JsonArray(emptyList())
        }
        if (!response.status.isSuccess()) {
            throw ApiSourceException("FIRST HTTP ${response.status.value} for $path${errorDetail(response.body)}")
        }
        if (response.body.isBlank()) {
            return JsonArray(emptyList())
        }
        return try {
            JsonSupport.json.parseToJsonElement(response.body)
        } catch (e: Exception) {
            throw ApiSourceException("FIRST API sent unreadable data for $path")
        }
    }

    suspend fun testApiKey(session: UserSession, request: com.obsidianscout.routes.TestApiRequest): com.obsidianscout.routes.TestApiResponse {
        val currentSettings = SettingsService.getSettings(session.teamNumber, session.program)
        return when (request.api.lowercase().trim()) {
            "tba" -> {
                val keyInput = request.tbaKey?.trim()
                val effectiveKey = if (keyInput.isNullOrBlank() || keyInput == "********") {
                    currentSettings.apiKeys.tbaKey
                } else {
                    keyInput
                }
                if (effectiveKey.isBlank()) {
                    return com.obsidianscout.routes.TestApiResponse(false, "TBA Key is empty. Please enter a key.")
                }
                try {
                    val response = client.get("https://www.thebluealliance.com/api/v3/status") {
                        header("X-TBA-Auth-Key", effectiveKey)
                    }
                    if (response.status.isSuccess()) {
                        com.obsidianscout.routes.TestApiResponse(true, "The Blue Alliance API key is valid!")
                    } else if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                        com.obsidianscout.routes.TestApiResponse(false, "Invalid TBA API Key (${response.status.value} ${response.status.description}).")
                    } else {
                        com.obsidianscout.routes.TestApiResponse(false, "TBA API returned HTTP ${response.status.value}.")
                    }
                } catch (e: Exception) {
                    com.obsidianscout.routes.TestApiResponse(false, "Failed to connect to TBA API: ${e.message}")
                }
            }
            "ftcscout" -> {
                try {
                    val response = client.post("https://api.ftcscout.org/graphql") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"query":"query { eventsSearch(season: 2025, limit: 1) { code } }"}""")
                    }
                    if (response.status.isSuccess()) {
                        com.obsidianscout.routes.TestApiResponse(true, "FTC Scout API connection successful! (No API key required)")
                    } else {
                        com.obsidianscout.routes.TestApiResponse(false, "FTC Scout API returned HTTP ${response.status.value}.")
                    }
                } catch (e: Exception) {
                    com.obsidianscout.routes.TestApiResponse(false, "Failed to connect to FTC Scout API: ${e.message}")
                }
            }
            "first" -> {
                val userInput = request.firstUsername?.trim()
                val keyInput = request.firstKey?.trim()
                val effectiveUser = if (userInput.isNullOrBlank()) currentSettings.apiKeys.firstUsername else userInput
                val effectiveKey = if (keyInput.isNullOrBlank() || keyInput == "********") currentSettings.apiKeys.firstKey else keyInput

                if (effectiveUser.isBlank() || effectiveKey.isBlank()) {
                    return com.obsidianscout.routes.TestApiResponse(false, "FIRST Username and FIRST Key are required.")
                }

                val credentials = Base64.getEncoder().encodeToString("$effectiveUser:$effectiveKey".toByteArray())
                val isFtc = session.program.equals("FTC", ignoreCase = true)
                val testUrl = if (isFtc) {
                    "https://ftc-api.firstinspires.org/v2.0/2025/events"
                } else {
                    "https://frc-api.firstinspires.org/v2.0/2025/events"
                }

                try {
                    val response = client.get(testUrl) {
                        header(HttpHeaders.Authorization, "Basic $credentials")
                    }
                    if (response.status.isSuccess()) {
                        com.obsidianscout.routes.TestApiResponse(true, "FIRST API credentials are valid!")
                    } else if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                        com.obsidianscout.routes.TestApiResponse(false, "Invalid FIRST API credentials (${response.status.value} ${response.status.description}).")
                    } else {
                        com.obsidianscout.routes.TestApiResponse(false, "FIRST API returned HTTP ${response.status.value}.")
                    }
                } catch (e: Exception) {
                    com.obsidianscout.routes.TestApiResponse(false, "Failed to connect to FIRST API: ${e.message}")
                }
            }
            "statbotics" -> {
                val rawUrl = request.statboticsBaseUrl?.trim() ?: currentSettings.statboticsBaseUrl
                val baseUrl = (if (rawUrl.isBlank()) "https://api.statbotics.io" else rawUrl).trimEnd('/')
                val cleanBaseUrl = baseUrl.removeSuffix("/v3")
                try {
                    val response = client.get("$cleanBaseUrl/v3/")
                    if (response.status.isSuccess()) {
                        com.obsidianscout.routes.TestApiResponse(true, "Statbotics API connection successful!")
                    } else {
                        com.obsidianscout.routes.TestApiResponse(false, "Statbotics API returned HTTP ${response.status.value}.")
                    }
                } catch (e: Exception) {
                    com.obsidianscout.routes.TestApiResponse(false, "Failed to connect to Statbotics API: ${e.message}")
                }
            }
            "match13" -> {
                val rawUrl = request.match13BaseUrl?.trim() ?: currentSettings.match13BaseUrl
                val baseUrl = (if (rawUrl.isBlank()) "https://actions.match13.com" else rawUrl).trimEnd('/')
                val keyInput = request.match13Key?.trim()
                val effectiveKey = if (keyInput.isNullOrBlank() || keyInput == "********") {
                    if (currentSettings.apiKeys.match13Key == "********") "" else currentSettings.apiKeys.match13Key
                } else {
                    keyInput
                }
                if (effectiveKey.isBlank()) {
                    return com.obsidianscout.routes.TestApiResponse(false, "Match 13 API Key is required. Please enter an API key.")
                }
                try {
                    val response = client.get("$baseUrl/v1/years") {
                        header("X-Match13-Key", effectiveKey)
                        header(HttpHeaders.Authorization, "Bearer $effectiveKey")
                    }
                    if (response.status.isSuccess()) {
                        com.obsidianscout.routes.TestApiResponse(true, "Match 13 API connection successful!")
                    } else if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                        com.obsidianscout.routes.TestApiResponse(false, "Invalid Match 13 API Key (${response.status.value} ${response.status.description}).")
                    } else {
                        com.obsidianscout.routes.TestApiResponse(false, "Match 13 API returned HTTP ${response.status.value}.")
                    }
                } catch (e: Exception) {
                    com.obsidianscout.routes.TestApiResponse(false, "Failed to connect to Match 13 API: ${e.message}")
                }
            }
            else -> com.obsidianscout.routes.TestApiResponse(false, "Unknown API specified: ${request.api}")
        }
    }
}

@kotlinx.serialization.Serializable
data class SyncCounts(
    val teams: Int,
    val matches: Int,
    /** Sources that failed while others succeeded, e.g. "TBA matches: HTTP 503". Empty on a clean sync. */
    val warnings: List<String> = emptyList()
)

private data class EventSyncRecord(
    val eventKey: String,
    val year: Int,
    val eventCode: String?,
    val name: String,
    val startDate: String?,
    val endDate: String?,
    val timezone: String?,
    val dataJson: String
)

internal data class TeamSyncRecord(
    val eventKey: String,
    val teamKey: String,
    val teamNumber: Int,
    val name: String?,
    val nickname: String?,
    val city: String?,
    val state: String?,
    val country: String?,
    val opr: Double?,
    val epa: Double?,
    val exp: Double? = null,
    val dataJson: String,
    /** "tba", "first", or "unknown" (manual rows and anything older). */
    val source: String = "unknown"
)

/**
 * Red and blue scores from a stored match's JSON, whichever API wrote it: TBA (`alliances.red.score`, -1
 * until played), FIRST FRC/FTC (`scoreRedFinal`), FTC Scout (`scores.red.totalPoints`), or a manual entry
 * (`redScore`). Null for an alliance without a result.
 */
internal fun matchScoresFromJson(json: JsonObject): Pair<Int?, Int?> {
    fun number(element: JsonElement?): Int? =
        (element as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { kotlin.math.round(it).toInt() }
    fun child(parent: JsonElement?, key: String): JsonElement? = (parent as? JsonObject)?.get(key)
    fun side(color: String): Int? =
        number(child(child(json["alliances"], color), "score"))?.takeIf { it >= 0 }
            ?: number(json["${color}Score"])
            ?: number(json["score${color.replaceFirstChar { it.uppercase() }}Final"])
            ?: number(child(child(json["scores"], color), "totalPoints"))
    return side("red") to side("blue")
}

/** TBA's team record is preferred over FIRST's; anything else (manual rows) ranks last. */
internal fun teamSourceRank(source: String): Int = when (source) {
    "tba" -> 2
    "first" -> 1
    else -> 0
}

/** Which API a stored team row's JSON came from, judged by the fields each API sends. */
internal fun teamSourceOf(dataJson: String): String {
    val obj = runCatching { JsonSupport.json.parseToJsonElement(dataJson) as? JsonObject }.getOrNull() ?: return "unknown"
    return when {
        obj.containsKey("team_number") && obj.containsKey("key") -> "tba"
        obj.containsKey("teamNumber") -> "first"
        else -> "unknown"
    }
}

private fun String?.orIfBlank(other: String?): String? = if (this.isNullOrBlank()) other else this

/** Fills [primary]'s blank fields from [other]; [primary]'s values, JSON and source win. */
private fun TeamSyncRecord.fillBlanksFrom(other: TeamSyncRecord): TeamSyncRecord = copy(
    name = name.orIfBlank(other.name),
    nickname = nickname.orIfBlank(other.nickname),
    city = city.orIfBlank(other.city),
    state = state.orIfBlank(other.state),
    country = country.orIfBlank(other.country),
    opr = opr ?: other.opr,
    epa = epa ?: other.epa,
    exp = exp ?: other.exp
)

/** One record per team from TBA's and FIRST's rosters: TBA's record, with gaps filled from FIRST's. */
internal fun mergeTeamRecords(records: List<TeamSyncRecord>): List<TeamSyncRecord> =
    records.groupBy { "${it.eventKey.lowercase()}_${it.teamKey.lowercase()}" }.map { (_, group) ->
        val ordered = group.sortedByDescending { teamSourceRank(it.source) }
        ordered.drop(1).fold(ordered.first()) { merged, other -> merged.fillBlanksFrom(other) }
    }

/**
 * What a team row should hold after [incoming] meets what is [stored]. The better-ranked source wins,
 * the newer copy on a tie, and blanks are filled from the other, so teams syncing with different keys
 * settle on the same row instead of overwriting each other. Stats always come from [incoming]
 * (null there means "leave the stored value").
 */
internal fun mergeTeamWithStored(stored: TeamSyncRecord, incoming: TeamSyncRecord): TeamSyncRecord {
    val merged = if (teamSourceRank(incoming.source) >= teamSourceRank(stored.source)) {
        incoming.fillBlanksFrom(stored)
    } else {
        stored.fillBlanksFrom(incoming)
    }
    return merged.copy(teamNumber = incoming.teamNumber, opr = incoming.opr, epa = incoming.epa, exp = incoming.exp)
}

private fun JsonObject.readString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.content
}

private fun JsonObject.readInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.readLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonObject.readDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonElement?.teamKeys(): List<String> {
    val obj = this as? JsonObject ?: return emptyList()
    val array = obj["team_keys"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.content }
}

private fun JsonObject.readTeamList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> {
                val asInt = item.content.toIntOrNull()
                if (asInt != null) {
                    "frc$asInt"
                } else {
                    item.content.takeIf { it.isNotBlank() }
                }
            }
            is JsonObject -> {
                val teamKey = item.readString("teamKey") ?: item.readString("team_key")
                if (!teamKey.isNullOrBlank()) {
                    teamKey
                } else {
                    val teamNumber = item.readInt("teamNumber") ?: item.readInt("team") ?: item.readInt("team_number")
                    teamNumber?.let { "frc$it" }
                }
            }
            else -> null
        }
    }
}

private fun JsonObject.readTeamsByStation(): Pair<List<String>, List<String>> {
    val array = this["teams"] as? JsonArray ?: return emptyList<String>() to emptyList()
    val red = mutableListOf<String>()
    val blue = mutableListOf<String>()
    array.forEach { item ->
        val obj = item as? JsonObject ?: return@forEach
        val station = obj.readString("station") ?: obj.readString("Station") ?: ""
        val teamNumber = obj.readInt("teamNumber") ?: obj.readInt("team") ?: obj.readInt("team_number")
        val teamKey = obj.readString("teamKey") ?: obj.readString("team_key") ?: teamNumber?.let { "frc$it" }
        if (teamKey.isNullOrBlank()) {
            return@forEach
        }
        when {
            station.lowercase().startsWith("red") -> red.add(teamKey)
            station.lowercase().startsWith("blue") -> blue.add(teamKey)
            red.size < 3 -> red.add(teamKey)
            else -> blue.add(teamKey)
        }
    }
    return red to blue
}

private fun ApiSettings.firstEventCode(eventKey: String): String {
    val resolvedConfiguredKey = this.resolvedEventKey().lowercase().trim()
    val targetKey = eventKey.lowercase().trim()
    val isCustomSync = targetKey.isNotBlank() && targetKey != resolvedConfiguredKey

    if (usesAlternateEventCode(eventKey)) return alternateEventCode.trim().uppercase()
    val codeToUse = if (isCustomSync) "" else this.eventCode
    return firstEventCodeFor(this.year, codeToUse, eventKey)
}

/** True when FIRST knows [eventKey] (the configured event) under the admin-set alternate code. */
internal fun ApiSettings.usesAlternateEventCode(eventKey: String): Boolean {
    if (alternateEventCode.isBlank()) return false
    val targetKey = eventKey.lowercase().trim()
    return targetKey.isBlank() || targetKey == resolvedEventKey().lowercase().trim()
}

private fun parseFirstMatchItems(
    root: JsonElement,
    eventKey: String,
    defaultLevel: String,
    zoneId: java.time.ZoneId = java.time.ZoneOffset.UTC
): List<MatchSyncRecord> {
    val array = root.findArray(listOf("Matches", "matches", "Schedule", "schedule", "MatchScores"))
    return array.mapIndexed { index, item ->
        val obj = item.jsonObject
        val matchNumber = obj.readInt("matchNumber") ?: obj.readInt("match_number")
        val setNumber = obj.readInt("setNumber") ?: obj.readInt("set_number")
        val rawLevel = (
            obj.readString("tournamentLevel")
                ?: obj.readString("compLevel")
                ?: defaultLevel
            ).ifBlank { defaultLevel }
        val setNum = setNumber ?: 1
        val matchNum = matchNumber ?: (index + 1)
        var compLevel = MatchCanonical.normalizeCompLevel(rawLevel)
        if (compLevel.isBlank()) {
            compLevel = MatchCanonical.normalizeCompLevel(defaultLevel)
        }
        if (compLevel == "playoff") {
            compLevel = MatchCanonical.playoffSetToCompLevel(setNum)
        }
        if (compLevel.isBlank()) {
            compLevel = "qm"
        }
        val matchKey = obj.readString("matchKey")
            ?: obj.readString("matchKeyShort")
            ?: "${eventKey}_${compLevel}_s${setNum}_m$matchNum"
        val scheduledTime = obj.readEpochSeconds("startTime", zoneId)
            ?: obj.readEpochSeconds("time", zoneId)
        val firstActual = obj.readEpochSeconds("actualStartTime", zoneId)
        val actualTime = if (firstActual != null && firstActual > 0) firstActual else scheduledTime
        var redTeams = obj.readTeamList("redTeams")
        var blueTeams = obj.readTeamList("blueTeams")
        if (redTeams.isEmpty() && blueTeams.isEmpty()) {
            val alliances = obj.readFirstAlliances()
            redTeams = alliances.first
            blueTeams = alliances.second
        }
        if (redTeams.isEmpty() && blueTeams.isEmpty()) {
            val stationTeams = obj.readTeamsByStation()
            redTeams = stationTeams.first
            blueTeams = stationTeams.second
        }
        MatchSyncRecord(
            matchKey = matchKey.lowercase(),
            eventKey = eventKey,
            compLevel = compLevel,
            setNumber = setNum,
            matchNumber = matchNum,
            scheduledTime = scheduledTime,
            actualTime = actualTime,
            redTeams = redTeams,
            blueTeams = blueTeams,
            dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item),
            source = "first"
        )
    }
}

private fun JsonObject.readFirstAlliances(): Pair<List<String>, List<String>> {
    val alliances = this["alliances"] as? JsonObject ?: return emptyList<String>() to emptyList()
    val red = alliances.readTeamList("red").ifEmpty { alliances.readTeamList("Red") }
    val blue = alliances.readTeamList("blue").ifEmpty { alliances.readTeamList("Blue") }
    if (red.isNotEmpty() || blue.isNotEmpty()) {
        return red to blue
    }
    val redAlliance = alliances["0"] as? JsonObject ?: alliances["red"] as? JsonObject
    val blueAlliance = alliances["1"] as? JsonObject ?: alliances["blue"] as? JsonObject
    val redTeams = redAlliance?.readTeamList("teamNumbers")
        ?: redAlliance?.readTeamList("teams")
        ?: emptyList()
    val blueTeams = blueAlliance?.readTeamList("teamNumbers")
        ?: blueAlliance?.readTeamList("teams")
        ?: emptyList()
    return redTeams to blueTeams
}

/**
 * True once the event's local date is past the day after [endDate], so late score and ranking fixes
 * still sync. Accepts TBA dates ("2026-03-15") and FIRST's ("2026-03-15T00:00:00") and FIRST zone names
 * ("Eastern Standard Time"). A missing or unreadable date counts as not ended, keeping the old behavior.
 */
internal fun isPastEventSyncWindow(endDate: String?, timezone: String?, now: Instant = Instant.now()): Boolean {
    val datePart = endDate?.trim()?.take(10)
    if (datePart.isNullOrBlank()) return false
    val end = try {
        java.time.LocalDate.parse(datePart)
    } catch (_: java.time.DateTimeException) {
        return false
    }
    val today = now.atZone(resolveZoneId(timezone)).toLocalDate()
    return today.isAfter(end.plusDays(1))
}

/**
 * Whether a page view should refresh an event's cached stats history. Live events refresh after
 * [refreshAfterSeconds]; ended events at most once a day, so browsing an old event doesn't keep
 * calling the stats APIs. The event lookup only happens once the cache is already stale.
 */
internal fun historyRefreshDue(eventKey: String, updatedAt: Instant, refreshAfterSeconds: Long): Boolean {
    val now = Instant.now()
    if (!updatedAt.isBefore(now.minusSeconds(refreshAfterSeconds))) return false
    val event = IntegrationService.getEvent(eventKey) ?: return true
    if (!isPastEventSyncWindow(event.endDate, event.timezone, now)) return true
    return updatedAt.isBefore(now.minusSeconds(86_400))
}

internal fun resolveZoneId(tzString: String?): java.time.ZoneId {
    if (tzString.isNullOrBlank()) return java.time.ZoneOffset.UTC
    val trimmed = tzString.trim()
    return try {
        java.time.ZoneId.of(trimmed)
    } catch (_: Exception) {
        try {
            when (trimmed.lowercase()) {
                "central standard time", "central daylight time", "central", "cst", "cdt", "america/chicago" -> java.time.ZoneId.of("America/Chicago")
                "eastern standard time", "eastern daylight time", "eastern", "est", "edt", "america/new_york" -> java.time.ZoneId.of("America/New_York")
                "pacific standard time", "pacific daylight time", "pacific", "pst", "pdt", "america/los_angeles" -> java.time.ZoneId.of("America/Los_Angeles")
                "mountain standard time", "mountain daylight time", "mountain", "mst", "mdt", "america/denver" -> java.time.ZoneId.of("America/Denver")
                "hawaii standard time", "hst", "pacific/honolulu" -> java.time.ZoneId.of("Pacific/Honolulu")
                "alaska standard time", "akst", "akdt", "america/anchorage" -> java.time.ZoneId.of("America/Anchorage")
                else -> java.time.ZoneId.of(trimmed, java.time.ZoneId.SHORT_IDS)
            }
        } catch (_: Exception) {
            java.time.ZoneOffset.UTC
        }
    }
}

internal fun JsonObject.readEpochSeconds(key: String, zoneId: java.time.ZoneId = java.time.ZoneOffset.UTC): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    val content = primitive.content.trim()
    if (content.isBlank()) return null
    content.toLongOrNull()?.let { value ->
        return if (content.length >= 13) value / 1000 else value
    }
    return try {
        java.time.Instant.parse(content).epochSecond
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(content).toEpochSecond()
        } catch (_: Exception) {
            try {
                val normalized = if (!content.contains('T') && content.contains(' ')) {
                    content.replace(' ', 'T')
                } else {
                    content
                }
                val ldt = java.time.LocalDateTime.parse(normalized, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
                ldt.atZone(zoneId).toEpochSecond()
            } catch (_: Exception) {
                null
            }
        }
    }
}

private fun JsonElement.findArray(keys: List<String>): JsonArray {
    return when (this) {
        is JsonArray -> this
        is JsonObject -> keys.firstNotNullOfOrNull { key -> this[key] as? JsonArray } ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
}

private fun Double?.orZero(): Double = this ?: 0.0

private const val EVENT_NAME_MAX = 160
private const val EVENT_CODE_MAX = 32
private const val EVENT_KEY_MAX = 64
private const val TEAM_NAME_MAX = 160
private const val TEAM_LOCATION_MAX = 80
private const val EVENT_DATE_MAX = 32
private const val TIMEZONE_MAX = 64

private fun resolveEventCode(event: EventSyncRecord): String? {
    val trimmed = event.eventCode?.trim()
    if (!trimmed.isNullOrBlank()) {
        return canonicalTbaEventCode(trimmed)
    }
    val prefix = event.year.toString()
    return if (event.eventKey.startsWith(prefix)) {
        canonicalTbaEventCode(event.eventKey.drop(prefix.length))
    } else {
        null
    }
}

private fun resolveEventKey(event: EventSyncRecord, eventCode: String?): String {
    val computed = if (!eventCode.isNullOrBlank()) {
        canonicalTbaEventKey(event.year, eventCode)
    } else {
        canonicalTbaEventKey(event.year, event.eventKey)
    }
    return computed.take(EVENT_KEY_MAX)
}

private fun String.clipEventName(): String = take(EVENT_NAME_MAX)

private fun String?.clipTeamText(): String? = this?.take(TEAM_NAME_MAX)

private fun String?.clipTeamLocation(): String? = this?.take(TEAM_LOCATION_MAX)

private fun String?.clipEventCode(): String? = this?.trim()?.take(EVENT_CODE_MAX)

private fun String?.clipEventDate(): String? = this?.take(EVENT_DATE_MAX)

private fun String?.clipTimezone(): String? = this?.take(TIMEZONE_MAX)
