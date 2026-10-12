package com.obsidianscout.analytics

import com.obsidianscout.integrations.MatchCanonical
import com.obsidianscout.integrations.RecencyBiasSettings
import com.obsidianscout.scouting.ScoutingEntryRecord
import java.time.Instant
import kotlin.math.pow

/**
 * Turns [RecencyBiasSettings] into per-entry weights for averaging a team's scouted performance.
 *
 * Entries are grouped by match, so several scouts of the same match share one age and duplicate
 * partner scouting doesn't skew the result. Age is how many of the team's matches are newer
 * (newest match = 0). Prescout entries always count as older than event entries.
 */
object RecencyWeighting {

    private data class MatchOrder(
        val isPrescout: Boolean,
        val eventFirstSeen: Long,
        val compRank: Int,
        val setNumber: Int,
        val matchNumber: Int,
        val createdAt: Long
    )

    private val chronological = compareBy<MatchOrder>(
        { !it.isPrescout },
        { it.eventFirstSeen },
        { it.compRank },
        { it.setNumber },
        { it.matchNumber },
        { it.createdAt }
    )

    /** True when [settings] would change the result for these entries (otherwise every weight is 1). */
    fun isActive(entries: List<ScoutingEntryRecord>, settings: RecencyBiasSettings): Boolean =
        settings.enabled && distinctEventMatches(entries) >= settings.minMatches

    /** Weights aligned with [entries]. */
    fun weights(entries: List<ScoutingEntryRecord>, settings: RecencyBiasSettings): DoubleArray {
        if (!isActive(entries, settings)) {
            return DoubleArray(entries.size) { 1.0 }
        }

        val createdAt = entries.map { parseMillis(it.createdAt) }
        val eventFirstSeen = entries.indices
            .groupBy { entries[it].eventKey.orEmpty().lowercase() }
            .mapValues { (_, idx) -> idx.minOf { createdAt[it] } }
        val matchIds = entries.mapIndexed { i, entry -> matchId(entry, i) }

        val orderByMatch = entries.indices.groupBy { matchIds[it] }.mapValues { (_, idx) ->
            val entry = entries[idx.first()]
            val identity = entry.matchKey?.takeIf { it.isNotBlank() }?.let { key ->
                MatchCanonical.parseLegacyManualKey(key) ?: MatchCanonical.parseTbaMatchKey(key)
            }
            MatchOrder(
                isPrescout = entry.isPrescout,
                eventFirstSeen = eventFirstSeen[entry.eventKey.orEmpty().lowercase()] ?: 0L,
                compRank = identity?.let { MatchCanonical.compLevelRank(it.compLevel) } ?: if (entry.matchNumber != null) 1 else -1,
                setNumber = identity?.setNumber ?: 1,
                matchNumber = identity?.matchNumber ?: entry.matchNumber ?: 0,
                createdAt = idx.minOf { createdAt[it] }
            )
        }

        val newestFirst = orderByMatch.entries.sortedWith(compareBy(chronological) { it.value }).reversed()
        val ageByMatch = newestFirst.withIndex().associate { (age, match) -> match.key to age }

        return DoubleArray(entries.size) { i ->
            val age = ageByMatch.getValue(matchIds[i])
            val base = when (settings.mode) {
                RecencyBiasSettings.MODE_LAST_N -> if (age < settings.windowSize) 1.0 else 0.0
                else -> 0.5.pow(age / settings.halfLifeMatches)
            }
            if (entries[i].isPrescout) base * settings.prescoutWeight else base
        }
    }

    /** Recency-weighted mean of [value] over [entries], or null when there are none. */
    fun weightedAverage(
        entries: List<ScoutingEntryRecord>,
        settings: RecencyBiasSettings,
        value: (ScoutingEntryRecord) -> Double
    ): Double? {
        if (entries.isEmpty()) return null
        val weights = weights(entries, settings)
        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) {
            return entries.map(value).average()
        }
        return entries.indices.sumOf { value(entries[it]) * weights[it] } / totalWeight
    }

    private fun distinctEventMatches(entries: List<ScoutingEntryRecord>): Int =
        entries.withIndex().filter { !it.value.isPrescout }.map { matchId(it.value, it.index) }.distinct().size

    private fun matchId(entry: ScoutingEntryRecord, index: Int): String {
        val event = entry.eventKey.orEmpty().lowercase()
        val key = entry.matchKey?.trim()?.lowercase()
        return when {
            !key.isNullOrBlank() -> "$event|${MatchCanonical.parseLegacyManualKey(key)?.toMatchKey() ?: key}"
            entry.matchNumber != null -> "$event|#${entry.matchNumber}"
            else -> "entry|${entry.id.ifBlank { index.toString() }}"
        }
    }

    private fun parseMillis(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}
