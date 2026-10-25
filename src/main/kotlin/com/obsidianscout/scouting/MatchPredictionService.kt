package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.MatchPredictions
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.matchScoresFromJson
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

@Serializable
data class MatchPredictionRequest(
    val eventKey: String = "",
    val matchKey: String,
    /** "red" or "blue"; blank clears the prediction. */
    val predictedWinner: String? = null
)

@Serializable
data class MatchPredictionStatus(
    val matchKey: String,
    /** The signed-in scout's pick, "red" or "blue", or null when they haven't picked. */
    val predictedWinner: String? = null,
    /** "red", "blue" or "tie" once the match has a result. */
    val result: String? = null,
    /** True once the match has a result; picks can no longer change. */
    val locked: Boolean = false,
    /** Whether the pick was right. Null without a pick, before the result, or on a tie. */
    val correct: Boolean? = null,
    val updatedAt: String? = null
)

@Serializable
data class MyMatchPredictions(
    val eventKey: String,
    val matches: List<MatchPredictionStatus>,
    val predicted: Int,
    val scored: Int,
    val correct: Int,
    val accuracyPct: Double? = null
)

@Serializable
data class PredictionLeaderboardRow(
    /** Shared by scouts with the same accuracy and correct count; null until a pick has been scored. */
    val rank: Int? = null,
    val userId: String,
    val username: String,
    val role: String,
    val predictions: Int,
    /** Picks on matches that ended with a winner (ties don't count either way). */
    val scored: Int,
    val correct: Int,
    val ties: Int,
    /** Picks on matches without a result yet. */
    val pending: Int,
    val accuracyPct: Double? = null,
    /** Correct picks in a row, ending at the scout's latest scored match. */
    val currentStreak: Int,
    val bestStreak: Int,
    val events: Int
)

@Serializable
data class PredictionLeaderboard(
    /** `event` or `year`. */
    val scope: String,
    val eventKey: String? = null,
    val eventName: String? = null,
    val year: Int? = null,
    val generatedAt: String,
    val matchesScored: Int,
    val rows: List<PredictionLeaderboardRow>
)

@Serializable
data class PredictionLeaderboardOptions(
    val years: List<Int>,
    val events: List<ScouterReportEvent>
)

/**
 * The match predictions game. Before a match, each scout calls red or blue, from the match / qualitative scouting
 * forms or the Match Predictions page; picks lock once the match result syncs. The leaderboard ranks the team's scouts by
 * how often they were right, for one event or a whole season. Off unless a team admin turns on
 * [com.obsidianscout.integrations.ApiSettings.matchPredictionsEnabled].
 */
object MatchPredictionService {
    private val log = LoggerFactory.getLogger(MatchPredictionService::class.java)
    private val KEY_PATTERN = Regex("^[A-Za-z0-9_.-]{1,64}$")
    const val RED = "red"
    const val BLUE = "blue"
    const val TIE = "tie"

    fun isEnabled(teamNumber: Int, program: String): Boolean =
        SettingsService.getSettings(teamNumber, program).matchPredictionsEnabled

    fun requireEnabled(session: UserSession) {
        if (!isEnabled(session.teamNumber, session.program)) {
            throw ApiException(HttpStatusCode.Forbidden, "Match predictions are turned off for this team")
        }
    }

    /** "red", "blue" or "tie" for a played match, from the stored API JSON; null before it has a result. */
    internal fun matchResult(dataJson: String): String? {
        val json = runCatching { JsonSupport.json.parseToJsonElement(dataJson) as? JsonObject }.getOrNull() ?: return null
        val (red, blue) = matchScoresFromJson(json)
        if (red == null || blue == null) return null
        // TBA names the winner itself, which covers playoff tiebreakers.
        when ((json["winning_alliance"] as? JsonPrimitive)?.content?.lowercase()) {
            RED -> return RED
            BLUE -> return BLUE
        }
        return when {
            red > blue -> RED
            blue > red -> BLUE
            else -> TIE
        }
    }

    /** [order] sorts an event's matches by when they were played (the sync falls back to the scheduled time). */
    internal data class MatchInfo(val matchKey: String, val eventKey: String, val order: Long, val result: String?) {
        // The APIs give no reliable "match started" signal before the result, so picks stay open until then.
        val locked get() = result != null
    }

    internal data class Pick(
        val userId: String,
        val eventKey: String,
        val matchKey: String,
        val winner: String,
        val updatedAt: Instant
    )

    internal data class LeaderboardUser(val id: String, val username: String, val role: String)

    private fun rowToMatchInfo(row: ResultRow): MatchInfo {
        val order = row[ApiMatches.actualTime] ?: row[ApiMatches.scheduledTime] ?: Long.MAX_VALUE
        return MatchInfo(row[ApiMatches.matchKey], row[ApiMatches.eventKey], order, matchResult(row[ApiMatches.dataJson]))
    }

    private fun loadMatch(matchKey: String): MatchInfo? = readTransaction {
        ApiMatches.selectAll().where { ApiMatches.matchKey eq matchKey }.limit(1).firstOrNull()?.let(::rowToMatchInfo)
    }

    private fun loadMatchesForEvent(eventKey: String): List<MatchInfo> = readTransaction {
        ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKey }.map(::rowToMatchInfo)
    }

    private fun scoreOf(winner: String, result: String?): Boolean? = when (result) {
        null, TIE -> null
        else -> winner == result
    }

    // ------------------------------------------------------------------ the signed-in scout's picks

    fun listMine(session: UserSession, eventKey: String): MyMatchPredictions {
        requireEnabled(session)
        val key = eventKey.trim().lowercase()
        if (!KEY_PATTERN.matches(key)) throw ApiException(HttpStatusCode.BadRequest, "Invalid eventKey")
        val matches = loadMatchesForEvent(key)
        val picks = readTransaction {
            MatchPredictions.selectAll().where {
                (MatchPredictions.userId eq session.userId) and (MatchPredictions.eventKey eq key)
            }.associateBy { it[MatchPredictions.matchKey] }
        }
        val statuses = matches.map { m ->
            val pick = picks[m.matchKey]
            val winner = pick?.get(MatchPredictions.predictedWinner)
            MatchPredictionStatus(
                matchKey = m.matchKey,
                predictedWinner = winner,
                result = m.result,
                locked = m.locked,
                correct = winner?.let { scoreOf(it, m.result) },
                updatedAt = pick?.get(MatchPredictions.updatedAt)?.toString()
            )
        }
        val scored = statuses.filter { it.correct != null }
        val correct = scored.count { it.correct == true }
        return MyMatchPredictions(
            eventKey = key,
            matches = statuses,
            predicted = statuses.count { it.predictedWinner != null },
            scored = scored.size,
            correct = correct,
            accuracyPct = if (scored.isEmpty()) null else round1(correct * 100.0 / scored.size)
        )
    }

    fun save(session: UserSession, request: MatchPredictionRequest): MatchPredictionStatus {
        requireEnabled(session)
        val matchKey = request.matchKey.trim()
        if (!KEY_PATTERN.matches(matchKey)) throw ApiException(HttpStatusCode.BadRequest, "Invalid matchKey")
        val winner = request.predictedWinner?.trim()?.lowercase().orEmpty()
        if (winner.isNotEmpty() && winner != RED && winner != BLUE) {
            throw ApiException(HttpStatusCode.BadRequest, "predictedWinner must be red or blue")
        }
        val match = loadMatch(matchKey) ?: throw ApiException(HttpStatusCode.NotFound, "Match not found")
        if (match.locked) {
            throw ApiException(HttpStatusCode.Conflict, "Predictions for this match are locked")
        }
        val now = Instant.now()
        // One statement each way: a read-then-write transaction hits SQLITE_BUSY when a scout taps two matches at once.
        transaction {
            if (winner.isEmpty()) {
                MatchPredictions.deleteWhere {
                    (MatchPredictions.userId eq session.userId) and (MatchPredictions.matchKey eq matchKey)
                }
            } else {
                MatchPredictions.upsert(
                    MatchPredictions.userId,
                    MatchPredictions.matchKey,
                    onUpdateExclude = listOf(MatchPredictions.id, MatchPredictions.createdAt)
                ) {
                    it[ownerTeamNumber] = session.teamNumber
                    it[program] = session.program
                    it[userId] = session.userId
                    it[eventKey] = match.eventKey
                    it[this.matchKey] = matchKey
                    it[predictedWinner] = winner
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
        return MatchPredictionStatus(
            matchKey = matchKey,
            predictedWinner = winner.ifEmpty { null },
            result = match.result,
            locked = false,
            updatedAt = if (winner.isEmpty()) null else now.toString()
        )
    }

    // ------------------------------------------------------------------ leaderboard

    internal fun aggregate(
        eventKey: String?,
        year: Int?,
        users: List<LeaderboardUser>,
        picks: List<Pick>,
        matches: Map<String, MatchInfo>,
        eventNames: Map<String, String>,
        now: Instant = Instant.now()
    ): PredictionLeaderboard {
        val wantedEvent = eventKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val scoped = picks.filter { p ->
            when {
                wantedEvent != null -> p.eventKey.lowercase() == wantedEvent
                year != null -> ScouterReportService.resolveYear(p.eventKey, emptyMap(), p.updatedAt) == year
                else -> true
            }
        }
        val known = users.associateBy { it.id }
        val unranked = scoped.groupBy { it.userId }
            .mapNotNull { (userId, mine) ->
                val user = known[userId] ?: return@mapNotNull null
                val judged = mine.map { it to matches[it.matchKey] }
                val scoredInOrder = judged
                    .mapNotNull { (pick, match) ->
                        val ok = scoreOf(pick.winner, match?.result) ?: return@mapNotNull null
                        Triple(match!!.order, pick.matchKey, ok)
                    }
                    .sortedWith(compareBy<Triple<Long, String, Boolean>> { it.first }.thenBy { it.second })
                var best = 0
                var run = 0
                scoredInOrder.forEach { (_, _, ok) ->
                    run = if (ok) run + 1 else 0
                    best = maxOf(best, run)
                }
                val correct = scoredInOrder.count { it.third }
                PredictionLeaderboardRow(
                    userId = userId,
                    username = user.username,
                    role = user.role,
                    predictions = mine.size,
                    scored = scoredInOrder.size,
                    correct = correct,
                    ties = judged.count { it.second?.result == TIE },
                    pending = judged.count { it.second?.result == null },
                    accuracyPct = if (scoredInOrder.isEmpty()) null else round1(correct * 100.0 / scoredInOrder.size),
                    currentStreak = run,
                    bestStreak = best,
                    events = mine.map { it.eventKey.lowercase() }.distinct().size
                )
            }

        val rankedOrder = compareByDescending<PredictionLeaderboardRow> { it.accuracyPct ?: -1.0 }
            .thenByDescending { it.correct }
            .thenBy { it.username.lowercase() }
        val sorted = unranked.filter { it.scored > 0 }.sortedWith(rankedOrder)
        val ranked = sorted.mapIndexed { i, row ->
            // Standard competition ranking: equal accuracy and correct count share a place.
            val firstTied = sorted.indexOfFirst { it.accuracyPct == row.accuracyPct && it.correct == row.correct }
            row.copy(rank = (if (firstTied >= 0) firstTied else i) + 1)
        }
        val waiting = unranked.filter { it.scored == 0 }
            .sortedWith(compareByDescending<PredictionLeaderboardRow> { it.predictions }.thenBy { it.username.lowercase() })

        return PredictionLeaderboard(
            scope = if (wantedEvent != null) "event" else "year",
            eventKey = wantedEvent,
            eventName = wantedEvent?.let { eventNames[it] },
            year = if (wantedEvent != null) ScouterReportService.resolveYear(wantedEvent, emptyMap(), null) else year,
            generatedAt = now.toString(),
            matchesScored = scoped.map { it.matchKey }.distinct().count { key ->
                matches[key]?.result.let { it == RED || it == BLUE }
            },
            rows = ranked + waiting
        )
    }

    fun leaderboard(session: UserSession, eventKey: String?, year: Int?): PredictionLeaderboard {
        requireEnabled(session)
        val event = eventKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (event != null && !KEY_PATTERN.matches(event)) throw ApiException(HttpStatusCode.BadRequest, "Invalid eventKey")
        val season = if (event == null) (year ?: java.time.Year.now().value) else null
        return readTransaction {
            val picks = loadTeamPicks(session.teamNumber, session.program).filter { p ->
                if (event != null) p.eventKey.lowercase() == event
                else ScouterReportService.resolveYear(p.eventKey, emptyMap(), p.updatedAt) == season
            }
            val matchKeys = picks.map { it.matchKey }.distinct()
            val matches = matchKeys.chunked(500).flatMap { chunk ->
                ApiMatches.selectAll().where { ApiMatches.matchKey inList chunk }.map(::rowToMatchInfo)
            }.associateBy { it.matchKey }
            // Picks belong to whoever made them, so name everyone who did, even if they've since changed team.
            val userIds = picks.map { it.userId }.distinct().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            val users = userIds.chunked(500).flatMap { chunk ->
                Users.select(Users.id, Users.username, Users.role).where { Users.id inList chunk }
                    .map { LeaderboardUser(it[Users.id].value.toString(), it[Users.username], it[Users.role]) }
            }
            aggregate(event, season, users, picks, matches, loadEventNames())
        }
    }

    fun leaderboardOptions(session: UserSession): PredictionLeaderboardOptions {
        requireEnabled(session)
        return readTransaction {
            val names = loadEventNames()
            val picks = loadTeamPicks(session.teamNumber, session.program)
            val events = picks.map { it.eventKey.lowercase() }.distinct()
                .map { ScouterReportEvent(it, names[it], ScouterReportService.resolveYear(it, emptyMap(), null)) }
                .sortedWith(compareByDescending<ScouterReportEvent> { it.year ?: 0 }.thenBy { it.name ?: it.eventKey })
            val years = picks.mapNotNull { ScouterReportService.resolveYear(it.eventKey, emptyMap(), it.updatedAt) }
                .distinct().sortedDescending()
            PredictionLeaderboardOptions(years, events)
        }
    }

    private fun loadTeamPicks(teamNumber: Int, program: String): List<Pick> =
        MatchPredictions.selectAll().where {
            (MatchPredictions.ownerTeamNumber eq teamNumber) and (MatchPredictions.program eq program)
        }.map {
            Pick(
                it[MatchPredictions.userId],
                it[MatchPredictions.eventKey],
                it[MatchPredictions.matchKey],
                it[MatchPredictions.predictedWinner],
                it[MatchPredictions.updatedAt]
            )
        }

    private fun loadEventNames(): Map<String, String> =
        ApiEvents.select(ApiEvents.eventKey, ApiEvents.name).associate { it[ApiEvents.eventKey].lowercase() to it[ApiEvents.name] }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    // ------------------------------------------------------------------ match / event key maintenance
    // Called by MatchCanonical / EventTeamCanonical so picks follow a match or event when its key changes.

    /** Points picks on [oldKey] at [newKey]; a scout with picks on both keeps the newer one. Must run inside a transaction. */
    fun remapMatchKey(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        MatchPredictions.selectAll().where { MatchPredictions.matchKey eq oldKey }.toList().forEach { row ->
            val clash = MatchPredictions.selectAll().where {
                (MatchPredictions.userId eq row[MatchPredictions.userId]) and (MatchPredictions.matchKey eq newKey)
            }.limit(1).firstOrNull()
            when {
                clash == null -> MatchPredictions.update({ MatchPredictions.id eq row[MatchPredictions.id] }) { it[matchKey] = newKey }
                row[MatchPredictions.updatedAt] > clash[MatchPredictions.updatedAt] -> {
                    MatchPredictions.deleteWhere { MatchPredictions.id eq clash[MatchPredictions.id] }
                    MatchPredictions.update({ MatchPredictions.id eq row[MatchPredictions.id] }) { it[matchKey] = newKey }
                    log.warn("Match prediction conflict on $newKey: kept newer pick from $oldKey")
                }
                else -> MatchPredictions.deleteWhere { MatchPredictions.id eq row[MatchPredictions.id] }
            }
        }
    }

    /** Must run inside a transaction. Match keys are re-keyed afterwards by the match-key remap. */
    fun moveEvent(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        MatchPredictions.update({ MatchPredictions.eventKey eq oldKey }) { it[eventKey] = newKey }
    }

    /** Match keys starting with [prefix] that have picks. Must run inside a transaction. */
    fun referencedMatchKeys(prefix: String = ""): Set<String> =
        (if (prefix.isEmpty()) MatchPredictions.select(MatchPredictions.matchKey)
        else MatchPredictions.select(MatchPredictions.matchKey).where { MatchPredictions.matchKey like "$prefix%" })
            .withDistinct()
            .map { it[MatchPredictions.matchKey] }
            .toSet()
}
