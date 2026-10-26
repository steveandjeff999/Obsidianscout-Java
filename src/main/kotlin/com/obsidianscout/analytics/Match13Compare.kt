package com.obsidianscout.analytics

import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.readTransaction
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.scouting.AllianceService
import com.obsidianscout.widgets.BatteryRotation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Team comparison by Match 13 xP (`/api/analytics/compare?source=match13`): current, starting and peak xP, the
 * change over the event, and the latest auto / teleop / endgame split, all read from the event's Match 13 match
 * history. Same response shape as [AnalyticsService.compareTeams], so the website and app render it the same way.
 */
object Match13Compare {
    const val SOURCE = "match13"

    suspend fun compare(session: UserSession, teamNumbers: List<Int>, eventKeyParam: String?): TeamComparisonResponse {
        val settings = AllianceService.getEffectiveSettings(session.teamNumber, session.program)
        if (settings.program == "FTC" || !settings.useMatch13Exp) {
            return TeamComparisonResponse(source = SOURCE, note = "Match 13 xP is turned off for this team.")
        }
        val eventKey = eventKeyParam?.trim()?.ifBlank { null }
            ?: return TeamComparisonResponse(source = SOURCE, note = "Pick an event to compare Match 13 xP.")

        val history = IntegrationService.getFullStatsHistory(settings, eventKey).match13History
        val teamRows = readTransaction {
            ApiTeams.selectAll()
                .where { ApiTeams.teamNumber inList teamNumbers }
                .andWhere { ApiTeams.eventKey eq eventKey.lowercase() }
                .toList()
                .associateBy { it[ApiTeams.teamNumber] }
        }
        val response = build(
            teamNumbers,
            history,
            nicknames = teamRows.mapValues { (_, row) -> row[ApiTeams.nickname] ?: row[ApiTeams.name] },
            storedExp = teamRows.mapValues { (_, row) -> row[ApiTeams.match13Exp] },
        )
        return response.copy(eventKey = eventKey)
    }

    /** Pure part of [compare], kept separate so it can be tested without a database. */
    fun build(
        teamNumbers: List<Int>,
        history: List<JsonElement>,
        nicknames: Map<Int, String?> = emptyMap(),
        storedExp: Map<Int, Double?> = emptyMap(),
    ): TeamComparisonResponse {
        var anyData = false
        val teams = teamNumbers.associate { team ->
            val played = history.mapNotNull { match ->
                val obj = match as? JsonObject ?: return@mapNotNull null
                val data = teamData(obj, team) ?: return@mapNotNull null
                matchKeyOf(obj) to data
            }.sortedWith { a, b -> BatteryRotation.compareMatchOrder("", null, a.first, "", null, b.first) }

            val metrics = mutableMapOf("matches_scouted" to played.size.toDouble())
            if (played.isNotEmpty()) {
                anyData = true
                val latest = played.last().second
                val current = total(latest)
                // xP going into the first match; its post-match value when the history has no pre-match number.
                val start = played.first().second.num("xpPre") ?: total(played.first().second)
                metrics["xp_current"] = current
                metrics["xp_start"] = start
                metrics["xp_change"] = current - start
                metrics["xp_peak"] = played.maxOf { total(it.second) }
                metrics["xp_auto"] = latest.firstNum("xAutoPost", "xAuto", "xAutoPre", "auto") ?: 0.0
                metrics["xp_teleop"] = latest.firstNum("xTelePost", "xTele", "xTelePre", "teleop") ?: 0.0
                metrics["xp_endgame"] = latest.firstNum("xEndPost", "xEnd", "xEndPre", "endgame") ?: 0.0
            }
            team.toString() to TeamComparisonData(
                teamNumber = team,
                nickname = nicknames[team],
                matchesScouted = played.size,
                exp = metrics["xp_current"] ?: storedExp[team],
                metrics = metrics,
            )
        }

        fun def(id: String, title: String, phase: String, unit: String = "xP") =
            TeamComparisonMetric(id = id, title = title, phase = phase, type = "number", unit = unit)
        val definitions = if (!anyData) emptyList() else listOf(
            def("xp_current", "Current xP", "overview"),
            def("xp_start", "Starting xP", "overview"),
            def("xp_change", "xP Change This Event", "overview"),
            def("xp_peak", "Peak xP", "overview"),
            def("matches_scouted", "Matches With xP", "overview", "matches"),
            def("xp_auto", "Auto xP", "auto"),
            def("xp_teleop", "Teleop xP", "teleop"),
            def("xp_endgame", "Endgame xP", "endgame"),
        )
        return TeamComparisonResponse(
            source = SOURCE,
            metricDefinitions = definitions,
            teams = teams,
            note = if (anyData) null else "No Match 13 match data for these teams at this event.",
        )
    }

    private fun matchKeyOf(obj: JsonObject): String =
        listOf("key", "matchKey", "match_key", "match").firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.content } ?: ""

    /** The team's entry in one match: `teams` is a map keyed by number / `frc254`, or a list of team objects. */
    private fun teamData(match: JsonObject, team: Int): JsonObject? {
        return when (val teams = match["teams"]) {
            is JsonObject -> listOf("$team", "frc$team", "ftc$team", "frc_$team").firstNotNullOfOrNull { teams[it] as? JsonObject }
            is JsonArray -> teams.firstOrNull { item ->
                val t = item as? JsonObject ?: return@firstOrNull false
                val raw = listOf("teamNumber", "team_number", "team", "teamKey")
                    .firstNotNullOfOrNull { (t[it] as? JsonPrimitive)?.content }
                raw?.filter { it.isDigit() }?.toIntOrNull() == team
            } as? JsonObject
            else -> null
        }
    }

    private fun total(data: JsonObject): Double = data.firstNum("xpPost", "xp", "xpPre", "exp", "total") ?: 0.0

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.firstNum(vararg keys: String): Double? = keys.firstNotNullOfOrNull { num(it) }
}
