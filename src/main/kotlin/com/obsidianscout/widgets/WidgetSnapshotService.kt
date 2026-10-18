package com.obsidianscout.widgets

import com.obsidianscout.analytics.PredictorService
import com.obsidianscout.analytics.RankingProjectionService
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ChatService
import com.obsidianscout.db.readTransaction
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.routes.AlliancePrediction
import com.obsidianscout.routes.MatchPredictionResponse
import com.obsidianscout.routes.MatchRecord
import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.scouting.BatteryTrackerService
import com.obsidianscout.scouting.MatchChecklistService
import com.obsidianscout.scouting.ScoutingAssignmentRecord
import com.obsidianscout.scouting.ScoutingAssignmentService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// ── Snapshot DTOs (served at GET /api/widgets/snapshot; the app and native widgets parse them) ──────────────

@Serializable
data class WidgetSnapshot(
    val v: Int = WidgetSnapshotService.VERSION,
    val generatedAt: Long,
    val teamNumber: Int,
    val program: String,
    val username: String,
    val event: WidgetEvent? = null,
    /** Page id -> whether this user can open it (role pages, chat switch, Non-Scouting Tools). */
    val pages: Map<String, Boolean>,
    /** Widget kind -> whether this user may have it. Widgets not allowed are hidden from the widget picker. */
    val widgets: Map<String, Boolean>,
    val schedule: WidgetSchedule? = null,
    /** Prediction for our upcoming match (the first of [predictions]; kept for older widgets). */
    val prediction: WidgetPrediction? = null,
    /**
     * Predictions for our next few matches. The widget shows the one for the match that's up *now* by the same rule
     * as the Next Match widget, so it moves on when a match starts without waiting for a new snapshot.
     */
    val predictions: List<WidgetPrediction> = emptyList(),
    val assignments: WidgetAssignments? = null,
    val battery: WidgetBattery? = null,
    val checklist: WidgetChecklist? = null,
    val rankings: WidgetRankings? = null,
    val chat: WidgetChat? = null,
)

@Serializable
data class WidgetEvent(val key: String, val name: String, val timezone: String)

@Serializable
data class WidgetMatch(
    val key: String,
    val compLevel: String,
    val set: Int? = null,
    val number: Int? = null,
    val label: String,
    /** UTC epoch seconds. [predicted] is the scheduled time shifted by how far ahead/behind the event is running. */
    val scheduled: Long? = null,
    val predicted: Long? = null,
    val actual: Long? = null,
    val red: List<String>,
    val blue: List<String>,
    val redScore: Int? = null,
    val blueScore: Int? = null,
    val played: Boolean,
    /** "red" / "blue" when our team plays in it. */
    val alliance: String? = null,
)

@Serializable
data class WidgetSchedule(
    /** Positive = running behind, negative = ahead (seconds), as of the next unplayed match. */
    val offsetSeconds: Long,
    /** The event's entire schedule, in play order. */
    val matches: List<WidgetMatch>,
)

@Serializable
data class WidgetPredictionTeam(val team: String, val alliance: String, val points: Double? = null)

@Serializable
data class WidgetPrediction(
    val matchKey: String,
    val label: String,
    val match: WidgetMatchRef? = null,
    val alliance: String? = null,
    val red: Double,
    val blue: Double,
    /** Chance red wins, when the predictor has one (Match13); otherwise only the scores are shown. */
    val redWinProb: Double? = null,
    val teams: List<WidgetPredictionTeam>,
)

@Serializable
data class WidgetAssignment(
    val id: String,
    val type: String,
    val matchKey: String? = null,
    val compLevel: String? = null,
    val number: Int? = null,
    val team: Int? = null,
    val alliance: String? = null,
    val predicted: Long? = null,
)

@Serializable
data class WidgetAssignments(
    /** Open match / qualitative assignments in event order, starting at the first not yet played. */
    val upcoming: List<WidgetAssignment>,
    val pitOpen: Int,
    val remaining: Int,
)

/** Enough about a match to label it and know when it starts, for widgets that don't get the full schedule. */
@Serializable
data class WidgetMatchRef(
    val key: String,
    val compLevel: String,
    val set: Int? = null,
    val number: Int? = null,
    val label: String,
    val predicted: Long? = null,
)

@Serializable
data class WidgetBatterySlot(val match: WidgetMatchRef, val battery: String? = null, val source: String, val skipped: List<String>)

@Serializable
data class WidgetBattery(
    /** Battery for each of our upcoming matches (by display name), so the widget can move on by itself. */
    val slots: List<WidgetBatterySlot>,
    val batteryCount: Int,
)

@Serializable
data class WidgetChecklistMatch(val matchKey: String, val done: Int, val total: Int, val match: WidgetMatchRef? = null)

@Serializable
data class WidgetChecklist(
    /** Per-match checklist progress for our upcoming matches. */
    val matches: List<WidgetChecklistMatch>,
    val eventKey: String = "",
    /** Our upcoming match: the checklist always shows this one, due lists or not. */
    val match: WidgetMatchRef? = null,
    /** Every list with its items for that match (and today's / this event's lists). */
    val lists: List<WidgetChecklistList> = emptyList(),
    /**
     * The same for our next few matches. The widget shows the match that's up now (same rule as Next Match), so
     * it moves on as soon as a match is played or starts, not when its checklist is finished.
     */
    val upcoming: List<WidgetChecklistForMatch> = emptyList(),
)

@Serializable
data class WidgetChecklistForMatch(val match: WidgetMatchRef, val lists: List<WidgetChecklistList>)

@Serializable
data class WidgetRankRow(
    val team: Int,
    val rank: Int? = null,
    val projectedRank: Int? = null,
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    val rp: Double = 0.0,
)

@Serializable
data class WidgetRankings(
    val ours: WidgetRankRow? = null,
    val probTopSeeds: Double? = null,
    val bestLikelyRank: Int? = null,
    val worstLikelyRank: Int? = null,
    val top: List<WidgetRankRow>,
    val qualsComplete: Boolean,
)

@Serializable
data class WidgetChatGroup(val group: String, val unread: Int, val mentions: Int)

@Serializable
data class WidgetChat(val unread: Int, val mentions: Int, val groups: List<WidgetChatGroup>)

/**
 * Builds the single document every home-screen widget draws from. The server already knows the predicted match
 * times, predictions, assignments, rankings and chat state, so the widgets can refresh on their own (without the
 * app running) by fetching just this. Sections the user may not see are left out entirely.
 */
object WidgetSnapshotService {
    const val VERSION = 1
    private val log = LoggerFactory.getLogger(WidgetSnapshotService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** How many upcoming (not ours) event matches the schedule carries; our own are always all included. */
    /** How many of our upcoming matches get a prediction and checklist, so widgets can move on by themselves. */
    private const val LOOKAHEAD = 3
    private const val MAX_RANKED_TEAMS = 100
    private const val UPCOMING_LIMIT = 10
    /** An assignment whose match started longer ago than this counts as played (same as the app). */
    private const val PLAYED_GRACE_SECONDS = 10 * 60L

    private data class Cached<T>(val value: T, val at: Long)
    private val predictionCache = ConcurrentHashMap<String, Cached<WidgetPrediction?>>()
    private val rankingCache = ConcurrentHashMap<String, Cached<WidgetRankings?>>()
    private const val PREDICTION_TTL_MS = 60_000L
    private const val RANKINGS_TTL_MS = 5 * 60_000L

    suspend fun build(session: UserSession, now: Instant = Instant.now()): WidgetSnapshot {
        val settings = SettingsService.getSettings(session.teamNumber, session.program)
        val pages = WidgetAccess.pageAccess(session.role, settings, session.nonScoutingToolsEnabled)
        val widgets = WidgetAccess.widgetAccess(pages)
        fun can(page: String) = pages[page] == true

        val eventKey = runCatching {
            AllianceService.getEffectiveSettings(session.teamNumber, session.program).resolvedEventKey()
        }.getOrDefault("").lowercase().trim()

        val base = WidgetSnapshot(
            generatedAt = now.epochSecond,
            teamNumber = session.teamNumber,
            program = session.program,
            username = session.username,
            pages = pages,
            widgets = widgets,
        )
        if (eventKey.isBlank()) return base

        val needsMatches = can("matches") || can("predictor") || can("battery-tracker") || can("match-checklist") || can("my-assignments")
        val allMatches = if (needsMatches) runCatching { IntegrationService.listMatches(eventKey, session.program) }.getOrElse {
            log.warn("[widgets] listMatches failed for $eventKey: ${it.message}")
            emptyList()
        } else emptyList()
        val ordered = allMatches.sortedWith { a, b ->
            BatteryRotation.compareMatchOrder(a.compLevel, a.matchNumber, a.matchKey, b.compLevel, b.matchNumber, b.matchKey)
        }
        val ours = ordered.filter { allianceOf(it, session.teamNumber) != null }
        val oursUnplayed = ours.filter { !isPlayed(it) }

        val event = eventInfo(eventKey)
        val predictions = if (can("predictor")) section("prediction") { predictions(session, eventKey, oursUnplayed) }.orEmpty() else emptyList()
        return base.copy(
            event = event,
            schedule = if (can("matches")) section("schedule") { schedule(ordered, session.teamNumber) } else null,
            predictions = predictions,
            prediction = predictions.firstOrNull(),
            assignments = if (can("my-assignments")) section("assignments") { assignments(session, eventKey, now) } else null,
            battery = if (can("battery-tracker")) section("battery") { battery(session, eventKey, ours, oursUnplayed) } else null,
            checklist = if (can("match-checklist")) section("checklist") { checklist(session, eventKey, event.timezone, ours, oursUnplayed) } else null,
            rankings = if (can("projected-rankings")) section("rankings") { rankings(session, eventKey) } else null,
            chat = if (can("chat")) section("chat") { chat(session) } else null,
        )
    }

    /** One failing section must not take the whole widget snapshot down. */
    private inline fun <T> section(name: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        log.warn("[widgets] $name section failed: ${e.message}")
        null
    }

    // ── Matches ────────────────────────────────────────────────────────────────────────────────────────────

    fun teamNumberOf(key: String): Int? {
        val raw = key.substringAfter("/").lowercase().removePrefix("frc").removePrefix("ftc")
        return raw.filter { it.isDigit() }.toIntOrNull()
    }

    fun allianceOf(m: MatchRecord, team: Int): String? = when {
        m.redTeams.any { teamNumberOf(it) == team } -> "red"
        m.blueTeams.any { teamNumberOf(it) == team } -> "blue"
        else -> null
    }

    /** Same rule as IntegrationService.listMatches: a score, or an actual time different from the schedule. */
    fun isPlayed(m: MatchRecord): Boolean {
        val hasScore = (m.redScore ?: -1) >= 0 || (m.blueScore ?: -1) >= 0
        val act = m.actualTime
        return hasScore || (act != null && act > 0 && m.scheduledTime != null && act != m.scheduledTime)
    }

    fun refOf(m: MatchRecord) = WidgetMatchRef(m.matchKey, m.compLevel, m.setNumber, m.matchNumber, m.label, m.predictedTime ?: m.scheduledTime)

    private fun toWidget(m: MatchRecord, team: Int) = WidgetMatch(
        key = m.matchKey,
        compLevel = m.compLevel,
        set = m.setNumber,
        number = m.matchNumber,
        label = m.label,
        scheduled = m.scheduledTime,
        predicted = m.predictedTime ?: m.scheduledTime,
        actual = m.actualTime?.takeIf { isPlayed(m) },
        red = m.redTeams.map { teamNumberOf(it)?.toString() ?: it },
        blue = m.blueTeams.map { teamNumberOf(it)?.toString() ?: it },
        redScore = m.redScore?.takeIf { it >= 0 },
        blueScore = m.blueScore?.takeIf { it >= 0 },
        played = isPlayed(m),
        alliance = allianceOf(m, team),
    )

    fun schedule(ordered: List<MatchRecord>, team: Int): WidgetSchedule = WidgetSchedule(
        offsetSeconds = ordered.firstOrNull { !isPlayed(it) }?.scheduleOffsetSeconds ?: 0L,
        matches = ordered.map { toWidget(it, team) },
    )

    private fun eventInfo(eventKey: String): WidgetEvent = readTransaction {
        val row = ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.limit(1).firstOrNull()
        WidgetEvent(
            key = eventKey,
            name = row?.get(ApiEvents.name)?.takeIf { it.isNotBlank() } ?: eventKey,
            timezone = row?.get(ApiEvents.timezone)?.takeIf { it.isNotBlank() } ?: "UTC",
        )
    }

    // ── Prediction ─────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun predictions(session: UserSession, eventKey: String, upcoming: List<MatchRecord>): List<WidgetPrediction> =
        upcoming.take(LOOKAHEAD).mapNotNull { m ->
            try {
                prediction(session, eventKey, m)
            } catch (e: Exception) {
                log.warn("[widgets] prediction for ${m.matchKey} failed: ${e.message}")
                null
            }
        }

    private suspend fun prediction(session: UserSession, eventKey: String, next: MatchRecord?): WidgetPrediction? {
        next ?: return null
        val cacheKey = "${session.program}-${session.teamNumber}-${next.matchKey}"
        predictionCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.at < PREDICTION_TTL_MS }?.let { return it.value }
        val response = PredictorService.predict(session, next.matchKey, false, eventKey)
        val result = summarizePrediction(response, allianceOf(next, session.teamNumber)).copy(match = refOf(next))
        predictionCache[cacheKey] = Cached(result, System.currentTimeMillis())
        return result
    }

    /**
     * The predictor page's "All" view: each team's points are the average of its available metrics (scouted, and
     * EXP / EPA / OPR when the team turned those on), summed per alliance; Match13's own score and win chance win
     * when present.
     */
    fun summarizePrediction(p: MatchPredictionResponse, ourAlliance: String?): WidgetPrediction {
        fun teamPoints(t: com.obsidianscout.routes.MatchTeamPrediction): Double? {
            val values = buildList {
                t.averageScoutedScore?.takeIf { it > 0 }?.let(::add)
                if (p.useMatch13Exp) t.exp?.takeIf { it > 0 }?.let(::add)
                if (p.useStatboticsEpa) t.epa?.takeIf { it > 0 }?.let(::add)
                if (p.useTbaOpr) t.opr?.takeIf { it > 0 }?.let(::add)
            }
            return if (values.isEmpty()) null else values.average()
        }

        fun total(a: AlliancePrediction): Double {
            val points = a.teams.mapNotNull(::teamPoints)
            if (points.isNotEmpty()) return points.sum()
            val totals = buildList {
                if (a.totalScoutedScore > 0) add(a.totalScoutedScore)
                if (p.useMatch13Exp && a.totalExp > 0) add(a.totalExp)
                if (p.useStatboticsEpa && a.totalEpa > 0) add(a.totalEpa)
                if (p.useTbaOpr && a.totalOpr > 0) add(a.totalOpr)
            }
            return if (totals.isEmpty()) 0.0 else totals.average()
        }

        val m13 = p.match13Pred
        return WidgetPrediction(
            matchKey = p.matchKey,
            label = p.label,
            alliance = ourAlliance,
            red = m13?.redScore ?: total(p.redAlliance),
            blue = m13?.blueScore ?: total(p.blueAlliance),
            redWinProb = m13?.winProb,
            teams = p.redAlliance.teams.map { WidgetPredictionTeam(it.teamNumber.toString(), "red", teamPoints(it)) } +
                p.blueAlliance.teams.map { WidgetPredictionTeam(it.teamNumber.toString(), "blue", teamPoints(it)) },
        )
    }

    // ── Assignments ────────────────────────────────────────────────────────────────────────────────────────

    private val levelRank = mapOf(
        "practice" to 0, "prac" to 0, "pr" to 0, "prc" to 0, "p" to 0,
        "qm" to 1, "q" to 1, "qual" to 1, "quals" to 1, "qualification" to 1,
        "qf" to 2, "quarterfinal" to 2, "quarterfinals" to 2,
        "sf" to 3, "semifinal" to 3, "semifinals" to 3,
        "f" to 4, "final" to 4, "finals" to 4,
        "ef" to 5, "einstein" to 5,
        "playoff" to 6, "playoffs" to 6,
    )
    private val keySuffix = Regex("^([a-z]+?)(\\d+)(?:m(\\d+))?$")

    /** Sortable (level, set, match), the same as the app's AssignmentAdvanceService.orderKey. */
    fun orderKey(matchKey: String?, compLevel: String?, matchNumber: Int?): List<Int> {
        var level = compLevel
        var set = 0
        var match = matchNumber ?: 0
        val suffix = (matchKey ?: "").trim().lowercase().split('_').last()
        keySuffix.find(suffix)?.let { m ->
            if (level.isNullOrEmpty()) level = m.groupValues[1]
            if (m.groupValues[3].isNotEmpty()) {
                set = m.groupValues[2].toIntOrNull() ?: 0
                match = matchNumber ?: m.groupValues[3].toIntOrNull() ?: 0
            } else {
                match = matchNumber ?: m.groupValues[2].toIntOrNull() ?: 0
            }
        }
        return listOf(levelRank[(level ?: "qm").lowercase()] ?: 7, set, match)
    }

    private val orderComparator = Comparator<List<Int>> { x, y ->
        (0 until 3).firstNotNullOfOrNull { i -> x[i].compareTo(y[i]).takeIf { it != 0 } } ?: 0
    }

    fun isOpen(status: String) = status.uppercase() !in setOf("COMPLETED", "SKIPPED", "MISSED")

    fun summarizeAssignments(all: List<ScoutingAssignmentRecord>, nowEpoch: Long): WidgetAssignments {
        val open = all.filter { isOpen(it.status) }
        val byMatch = open.filter { it.assignmentType.uppercase() != "PIT" }
            .sortedWith { a, b -> orderComparator.compare(orderKey(a.matchKey, a.compLevel, a.matchNumber), orderKey(b.matchKey, b.compLevel, b.matchNumber)) }
        fun timeOf(a: ScoutingAssignmentRecord) = (a.predictedTime ?: a.scheduledTime)?.let { if (it > 100_000_000_000L) it / 1000 else it }
        val firstUnplayed = byMatch.indexOfFirst { a -> timeOf(a)?.let { it + PLAYED_GRACE_SECONDS >= nowEpoch } ?: true }
        val from = if (firstUnplayed >= 0) firstUnplayed else 0
        return WidgetAssignments(
            upcoming = byMatch.drop(from).take(UPCOMING_LIMIT).map {
                WidgetAssignment(
                    id = it.id,
                    type = it.assignmentType.uppercase(),
                    matchKey = it.matchKey,
                    compLevel = it.compLevel,
                    number = it.matchNumber,
                    team = it.targetTeamNumber,
                    alliance = it.allianceColor,
                    predicted = timeOf(it),
                )
            },
            pitOpen = open.count { it.assignmentType.uppercase() == "PIT" },
            remaining = open.size,
        )
    }

    private fun assignments(session: UserSession, eventKey: String, now: Instant): WidgetAssignments =
        summarizeAssignments(ScoutingAssignmentService.getMyAssignments(session, eventKey), now.epochSecond)

    // ── Drive-team tools ───────────────────────────────────────────────────────────────────────────────────

    private fun parseObject(raw: String?): JsonObject? =
        raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun battery(session: UserSession, eventKey: String, ours: List<MatchRecord>, oursUnplayed: List<MatchRecord>): WidgetBattery? {
        val owner = BatteryTrackerService.ownerKey(session.teamNumber, session.program)
        val inventory = parseObject(BatteryTrackerService.load(owner, "")?.dataJson)
        val plan = parseObject(BatteryTrackerService.load(owner, eventKey)?.dataJson)
        val batteries = BatteryRotation.batteriesOf(inventory)
        if (batteries.isEmpty()) return WidgetBattery(emptyList(), 0)
        val names = batteries.associate { it.id to it.displayName }
        val schedule = BatteryRotation.computeSchedule(inventory, plan, ours.map { it.matchKey })
        return WidgetBattery(
            slots = oursUnplayed.take(UPCOMING_LIMIT).mapNotNull { m ->
                schedule[m.matchKey]?.let { s ->
                    WidgetBatterySlot(refOf(m), s.batteryId?.let { names[it] ?: it }, s.source, s.skipped.map { names[it] ?: it })
                }
            },
            batteryCount = BatteryRotation.rotationOf(inventory).size,
        )
    }

    /**
     * Progress of the per-match checklists due for each of our upcoming matches, counted like the app's
     * Match Checklist page (items with an id and text, in lists due that match; "every N" lists are due on our
     * 1st, (N+1)th, ... match).
     */
    fun checklistProgress(template: JsonObject?, completions: Map<String, JsonObject?>, ourKeys: List<String>, keys: List<String>): List<WidgetChecklistMatch> {
        data class ListDef(val id: String, val frequency: String, val every: Int)
        val lists: List<ListDef> = run {
            val defined = template?.get("lists") as? JsonObject
            if (defined != null) {
                defined.mapNotNull { (id, raw) ->
                    val l = raw as? JsonObject ?: return@mapNotNull null
                    val freq = (l["frequency"] as? JsonPrimitive)?.content
                    val every = (l["every"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 2
                    ListDef(id, if (freq in setOf("match", "every_n", "daily", "event")) freq!! else "match", every.coerceIn(2, 99))
                }
            } else {
                listOf(ListDef("pre", "match", 2), ListDef("field", "match", 2))
            }
        }
        val fields = (template?.get("fields") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun itemsOf(listId: String) = fields.filter {
            ((it["section"] as? JsonPrimitive)?.content ?: "pre") == listId
        }.mapNotNull { f ->
            val id = (f["id"] as? JsonPrimitive)?.content.orEmpty()
            val text = (f["text"] as? JsonPrimitive)?.content.orEmpty()
            id.takeIf { it.isNotEmpty() && text.isNotBlank() }
        }
        val perMatch = lists.filter { it.frequency == "match" || it.frequency == "every_n" }
        return keys.mapNotNull { key ->
            val idx = ourKeys.indexOf(key)
            val due = perMatch.filter { it.frequency != "every_n" || idx < 0 || idx % it.every == 0 }
            val ids = due.flatMap { itemsOf(it.id) }
            if (ids.isEmpty()) return@mapNotNull null
            val checked = (completions[key]?.get("checked") as? JsonObject)?.keys.orEmpty()
            WidgetChecklistMatch(key, ids.count { it in checked }, ids.size)
        }
    }

    private fun checklist(session: UserSession, eventKey: String, timezone: String, ours: List<MatchRecord>, oursUnplayed: List<MatchRecord>): WidgetChecklist? {
        val owner = MatchChecklistService.ownerKey(session.teamNumber, session.program)
        val template = parseObject(MatchChecklistService.load(owner, "", MatchChecklistService.TEMPLATE_KEY)?.dataJson)
        val keys = oursUnplayed.take(UPCOMING_LIMIT).map { it.matchKey }
        val completions = keys.associateWith { parseObject(MatchChecklistService.load(owner, eventKey, it)?.dataJson) }
        val refs = oursUnplayed.associate { it.matchKey to refOf(it) }
        val next = oursUnplayed.firstOrNull()
        val dayKey = WidgetChecklistService.dayKey(timezone)
        val loaded = HashMap<String, JsonObject?>()
        val load = { record: String ->
            loaded.getOrPut(record) { completions[record] ?: parseObject(MatchChecklistService.load(owner, eventKey, record)?.dataJson) }
        }
        fun listsFor(matchKey: String?) = WidgetChecklistService.lists(template, ours.map { it.matchKey }, matchKey, dayKey, load)
        return WidgetChecklist(
            matches = checklistProgress(template, completions, ours.map { it.matchKey }, keys).map { it.copy(match = refs[it.matchKey]) },
            eventKey = eventKey,
            match = next?.let(::refOf),
            lists = listsFor(next?.matchKey),
            upcoming = oursUnplayed.take(LOOKAHEAD).map { WidgetChecklistForMatch(refOf(it), listsFor(it.matchKey)) },
        )
    }

    // ── Rankings and chat ──────────────────────────────────────────────────────────────────────────────────

    private fun rankings(session: UserSession, eventKey: String): WidgetRankings? {
        val cacheKey = "${session.program}-${session.teamNumber}-$eventKey"
        rankingCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.at < RANKINGS_TTL_MS }?.let { return it.value }
        val projection = RankingProjectionService.project(session, eventKey, RankingProjectionService.DEFAULT_SIMULATIONS, seed = null)
        fun row(t: com.obsidianscout.analytics.TeamRankingProjection) = WidgetRankRow(
            team = t.teamNumber,
            rank = t.currentRank,
            projectedRank = t.medianRank.takeIf { it > 0 },
            wins = t.wins,
            losses = t.losses,
            ties = t.ties,
            rp = t.currentRp,
        )
        val ours = projection.teams.firstOrNull { it.teamNumber == session.teamNumber }
        val anyPlayed = projection.teams.any { it.currentRank != null }
        val top = projection.teams
            .sortedBy { if (anyPlayed) it.currentRank ?: Int.MAX_VALUE else it.medianRank }
            // Every team (events are under ~80), so a taller widget can show as many as fit.
            .take(MAX_RANKED_TEAMS)
            .map(::row)
        val result = WidgetRankings(
            ours = ours?.let(::row),
            probTopSeeds = ours?.probTopSeeds,
            bestLikelyRank = ours?.bestLikelyRank?.takeIf { it > 0 },
            worstLikelyRank = ours?.worstLikelyRank?.takeIf { it > 0 },
            top = top,
            qualsComplete = projection.qualsComplete,
        )
        rankingCache[cacheKey] = Cached(result, System.currentTimeMillis())
        return result
    }

    private fun chat(session: UserSession): WidgetChat {
        val status = ChatService.getUnreadStatus(session.userId, session.teamNumber, session.username, session.role, session.program)
        return WidgetChat(
            unread = status.unreadCount,
            mentions = status.mentionCount,
            groups = status.groups.filter { it.unreadCount > 0 }.map { WidgetChatGroup(it.groupName, it.unreadCount, it.mentionCount) },
        )
    }

    /** Fingerprint of an event's schedule state; a change means widgets showing it need fresh data. */
    fun scheduleFingerprint(matches: List<MatchRecord>): Int = matches.map {
        listOf(it.matchKey, it.predictedTime, it.actualTime, it.redScore, it.blueScore, it.redTeams, it.blueTeams)
    }.hashCode()
}
