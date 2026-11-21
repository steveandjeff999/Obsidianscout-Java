package com.obsidianscout.scouting

import com.obsidianscout.analytics.AnalyticsService
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.integrations.TeamCacheOverrideService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlin.math.abs

/**
 * Per-scout accuracy: how close the alliances a scout worked on came to the official score.
 *
 * For every alliance where this team scouted all robots and the official score is known, the
 * alliance error is (sum of scouted points) - (official score minus foul points, when TBA reports
 * them). That error is credited to every scout who entered one of the alliance's robots. Averaged
 * over many alliances, scouts who over- or under-count stand out, even though one alliance's error
 * can't be split between its three scouts.
 *
 * Entries are scored with the team's current scouting form, so callers should pass only events
 * from the season that form belongs to.
 */
object ScouterAccuracyService {

    data class Accuracy(
        val alliances: Int,
        /** 0-100: 100 minus the mean absolute error as a percent of the official score. */
        val accuracyPct: Double?,
        /** Mean absolute points off per alliance. */
        val avgAbsError: Double?,
        /** Mean signed error: positive means the alliances this scout worked on were over-counted. */
        val avgBias: Double?
    )

    private data class Scored(val userId: String, val targetTeam: Int, val points: Double)

    /** Must run inside a transaction. Returns userId -> accuracy. */
    fun compute(teamNumber: Int, program: String, eventKeys: Collection<String>): Map<String, Accuracy> {
        val events = eventKeys.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (events.isEmpty()) return emptyMap()
        val config = runCatching { ConfigService.getConfig(teamNumber, program) }.getOrNull() ?: return emptyMap()
        if (config.fields.none { (it.pointsPer ?: 0.0) != 0.0 || it.options.any { o -> o.points != 0.0 } }) {
            // The form gives no points, so there's nothing to compare with the official score.
            return emptyMap()
        }

        // Match entries for these events, grouped by match identity then robot.
        val byMatch = mutableMapOf<String, MutableMap<Int, Scored>>()
        ScoutingEntries.select(
            ScoutingEntries.id, ScoutingEntries.submittedByUserId, ScoutingEntries.eventKey,
            ScoutingEntries.matchKey, ScoutingEntries.matchNumber, ScoutingEntries.targetTeamNumber,
            ScoutingEntries.dataJson, ScoutingEntries.createdAt
        ).where {
            (ScoutingEntries.ownerTeamNumber eq teamNumber) and
                (ScoutingEntries.program eq program) and
                (ScoutingEntries.isPrescout eq false) and
                (ScoutingEntries.eventKey inList events)
        }.forEach { row ->
            val target = row[ScoutingEntries.targetTeamNumber] ?: return@forEach
            val data = runCatching { JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject }
                .getOrNull() ?: return@forEach
            val record = ScoutingEntryRecord(
                id = row[ScoutingEntries.id].value.toString(),
                ownerTeamNumber = teamNumber,
                targetTeamNumber = target,
                eventKey = row[ScoutingEntries.eventKey],
                matchKey = row[ScoutingEntries.matchKey],
                matchNumber = row[ScoutingEntries.matchNumber],
                data = data,
                createdAt = row[ScoutingEntries.createdAt].toString()
            )
            val scored = Scored(row[ScoutingEntries.submittedByUserId].value.toString(), target, AnalyticsService.scoreEntry(config, record))
            val event = row[ScoutingEntries.eventKey]?.trim()?.lowercase().orEmpty()
            row[ScoutingEntries.matchKey]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
                byMatch.getOrPut(it) { mutableMapOf() }[target] = scored
            }
            row[ScoutingEntries.matchNumber]?.let {
                // Fallback for entries saved without a match key (qualification number only).
                byMatch.getOrPut("$event#qm$it") { mutableMapOf() }.putIfAbsent(target, scored)
            }
        }
        if (byMatch.isEmpty()) return emptyMap()

        val errors = mutableMapOf<String, MutableList<Pair<Double, Double>>>() // userId -> (signed error, official)
        val listSer = ListSerializer(String.serializer())

        events.forEach { event ->
            val matches = runCatching { TeamCacheOverrideService.matchRows(event, teamNumber, program) }.getOrDefault(emptyList())
            matches.forEach { m ->
                val matchKey = m[ApiMatches.matchKey].trim().lowercase()
                val compLevel = m[ApiMatches.compLevel].lowercase()
                val entries = byMatch[matchKey]
                    ?: m[ApiMatches.matchNumber]?.takeIf { compLevel == "qm" }?.let { byMatch["$event#qm$it"] }
                    ?: return@forEach
                val official = officialScores(m[ApiMatches.dataJson])
                listOf(
                    m[ApiMatches.redTeams] to official.first,
                    m[ApiMatches.blueTeams] to official.second
                ).forEach { (teamsJson, actual) ->
                    if (actual == null || actual <= 0.0) return@forEach
                    val teams = runCatching { JsonSupport.json.decodeFromString(listSer, teamsJson) }.getOrDefault(emptyList())
                        .mapNotNull { teamNumberOf(it) }
                    if (teams.isEmpty()) return@forEach
                    val scored = teams.map { entries[it] ?: return@forEach } // need every robot scouted
                    val diff = scored.sumOf { it.points } - actual
                    scored.map { it.userId }.distinct().forEach { uid ->
                        errors.getOrPut(uid) { mutableListOf() }.add(diff to actual)
                    }
                }
            }
        }

        return errors.mapValues { (_, list) -> summarize(list) }
    }

    /** [errors]: (scouted total minus official score, official score) for each alliance a scout worked on. */
    internal fun summarize(errors: List<Pair<Double, Double>>): Accuracy {
        if (errors.isEmpty()) return Accuracy(0, null, null, null)
        val absErr = errors.map { abs(it.first) }.average()
        val bias = errors.map { it.first }.average()
        val pctErr = errors.map { abs(it.first) / it.second }.average() * 100.0
        return Accuracy(
            alliances = errors.size,
            accuracyPct = round1((100.0 - pctErr).coerceIn(0.0, 100.0)),
            avgAbsError = round1(absErr),
            avgBias = round1(bias)
        )
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private fun teamNumberOf(key: String): Int? {
        val parts = key.split("/")
        val raw = if (parts.size > 1) parts[1] else parts[0]
        return raw.trim().lowercase().removePrefix("frc").removePrefix("ftc").toIntOrNull()
    }

    private fun num(e: Any?): Double? = (e as? JsonPrimitive)?.content?.toDoubleOrNull()

    /**
     * Official (red, blue) scores with foul points removed when the source reports them, since
     * scouts don't record penalties.
     */
    internal fun officialScores(dataJson: String): Pair<Double?, Double?> {
        if (dataJson.isBlank() || dataJson == "{}") return null to null
        return try {
            val root = JsonSupport.json.parseToJsonElement(dataJson).jsonObject
            val breakdown = root["score_breakdown"] as? JsonObject
            fun fouls(color: String): Double = (breakdown?.get(color) as? JsonObject)?.get("foulPoints")?.let { num(it) } ?: 0.0

            val alliances = root["alliances"] as? JsonObject
            val tbaRed = ((alliances?.get("red") as? JsonObject)?.get("score"))?.let { num(it) }
            val tbaBlue = ((alliances?.get("blue") as? JsonObject)?.get("score"))?.let { num(it) }
            if (tbaRed != null || tbaBlue != null) {
                return tbaRed?.takeIf { it >= 0 }?.let { it - fouls("red") } to
                    tbaBlue?.takeIf { it >= 0 }?.let { it - fouls("blue") }
            }
            val firstRed = num(root["scoreRedFinal"]) ?: num(root["scoreRed"])
            val firstBlue = num(root["scoreBlueFinal"]) ?: num(root["scoreBlue"])
            val firstRedFoul = num(root["scoreRedFoul"]) ?: 0.0
            val firstBlueFoul = num(root["scoreBlueFoul"]) ?: 0.0
            if (firstRed != null || firstBlue != null) {
                return firstRed?.let { it - firstRedFoul } to firstBlue?.let { it - firstBlueFoul }
            }
            val scores = root["scores"] as? JsonObject
            val ftcRed = ((scores?.get("red") as? JsonObject)?.get("totalPoints"))?.let { num(it) } ?: num(root["red_score"])
            val ftcBlue = ((scores?.get("blue") as? JsonObject)?.get("totalPoints"))?.let { num(it) } ?: num(root["blue_score"])
            ftcRed to ftcBlue
        } catch (_: Exception) {
            null to null
        }
    }
}
