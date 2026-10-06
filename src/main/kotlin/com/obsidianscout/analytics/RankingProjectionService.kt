package com.obsidianscout.analytics

import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.readTransaction
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.integrations.MatchCanonical
import com.obsidianscout.routes.MatchRecord
import com.obsidianscout.routes.TeamRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.SplittableRandom
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
data class TeamRankingProjection(
    val teamNumber: Int,
    val teamKey: String,
    val nickname: String? = null,
    /** Rank from qualification matches played so far; null before the team has played. */
    val currentRank: Int? = null,
    val currentRp: Double = 0.0,
    val currentRankingScore: Double? = null,
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    val matchesPlayed: Int = 0,
    val matchesRemaining: Int = 0,
    val expectedFinalRp: Double = 0.0,
    val expectedRankingScore: Double = 0.0,
    val projectedRank: Double = 0.0,
    val medianRank: Int = 0,
    /** 10th / 90th percentile finishing rank. */
    val bestLikelyRank: Int = 0,
    val worstLikelyRank: Int = 0,
    val probFirst: Double = 0.0,
    /** Probability of finishing inside the alliance-captain slots (top 8 FRC, top 4 FTC). */
    val probTopSeeds: Double = 0.0
)

@Serializable
data class RankingProjectionResponse(
    val eventKey: String,
    val simulations: Int,
    val qualMatchesTotal: Int,
    val qualMatchesPlayed: Int,
    val winRp: Int,
    /** "official" when played-match RP came from TBA score breakdowns, "win-loss" when estimated from results. */
    val rpSource: String,
    /** Metrics blended into each team's strength, e.g. ["Scouted", "EPA"]. */
    val strengthMetrics: List<String>,
    val captainSlots: Int,
    val generatedAt: Long,
    val teams: List<TeamRankingProjection>,
    val note: String? = null,
    /** True when every qualification match has been played, so the table is final, not a projection. */
    val qualsComplete: Boolean = false
)

/**
 * Projects final qualification rankings by Monte Carlo simulation of the remaining
 * qualification matches.
 *
 * Played matches count as they happened (using TBA's per-alliance RP when available).
 * Each unplayed match is simulated from blended team strengths, scaled and given noise
 * calibrated against the scores already played at the event. Teams are ranked by average
 * RP per match, with average match score as the tiebreaker.
 */
object RankingProjectionService {

    const val DEFAULT_SIMULATIONS = 2000
    const val MIN_SIMULATIONS = 200
    const val MAX_SIMULATIONS = 10000

    data class SimMatch(
        val red: List<Int>,
        val blue: List<Int>,
        val redScore: Int? = null,
        val blueScore: Int? = null,
        /** Official ranking points earned, when the data source publishes them. */
        val redRp: Int? = null,
        val blueRp: Int? = null
    ) {
        val isPlayed: Boolean get() = redScore != null && blueScore != null && redScore >= 0 && blueScore >= 0
    }

    data class SimInput(
        val teams: List<Int>,
        val matches: List<SimMatch>,
        /** Blended per-team scoring strength on any scale; teams without data get the median. */
        val strengths: Map<Int, Double>,
        /** RP for a win when it can't be inferred from official RP data. */
        val defaultWinRp: Int,
        val simulations: Int,
        val seed: Long,
        val captainSlots: Int
    )

    data class SimTeamResult(
        val teamNumber: Int,
        val currentRank: Int?,
        val currentRp: Double,
        val currentRankingScore: Double?,
        val wins: Int,
        val losses: Int,
        val ties: Int,
        val matchesPlayed: Int,
        val matchesRemaining: Int,
        val expectedFinalRp: Double,
        val expectedRankingScore: Double,
        val projectedRank: Double,
        val medianRank: Int,
        val bestLikelyRank: Int,
        val worstLikelyRank: Int,
        val probFirst: Double,
        val probTopSeeds: Double
    )

    data class SimResult(
        val winRp: Int,
        val rpSource: String,
        val playedCount: Int,
        /** True when strengths came from this event's scores because no scouting/stats data existed. */
        val usedEventOpr: Boolean,
        val teams: List<SimTeamResult>
    )

    fun simulate(input: SimInput): SimResult {
        val teamNumbers = (input.teams + input.matches.flatMap { it.red + it.blue }).distinct()
        val index = teamNumbers.withIndex().associate { (i, n) -> n to i }
        val teamCount = teamNumbers.size

        val played = input.matches.filter { it.isPlayed }
        val remaining = input.matches.filter { !it.isPlayed }
        val hasOfficialRp = played.any { it.redRp != null && it.blueRp != null }

        // Infer the RP awarded for a win from official data: the smallest RP any winner earned.
        val winRp = if (hasOfficialRp) {
            played.mapNotNull { m ->
                when {
                    m.redRp == null || m.blueRp == null -> null
                    m.redScore!! > m.blueScore!! -> m.redRp
                    m.blueScore!! > m.redScore!! -> m.blueRp
                    else -> null
                }
            }.minOrNull()?.takeIf { it > 0 } ?: input.defaultWinRp
        } else input.defaultWinRp
        val tieRp = max(1, winRp / 2)

        // ── Current standings ─────────────────────────────────────
        val rp = DoubleArray(teamCount)
        val scoreSum = DoubleArray(teamCount)
        val wins = IntArray(teamCount)
        val losses = IntArray(teamCount)
        val ties = IntArray(teamCount)
        val playedCount = IntArray(teamCount)
        val remainingCount = IntArray(teamCount)
        val bonusSum = DoubleArray(teamCount)
        val bonusN = IntArray(teamCount)
        val allBonuses = mutableListOf<Int>()

        fun winPart(own: Int, opp: Int) = when {
            own > opp -> winRp
            own == opp -> tieRp
            else -> 0
        }

        for (m in played) {
            val rs = m.redScore!!
            val bs = m.blueScore!!
            for ((alliance, own, opp, officialRp) in listOf(
                Quad(m.red, rs, bs, m.redRp),
                Quad(m.blue, bs, rs, m.blueRp)
            )) {
                val base = winPart(own, opp)
                val earned = officialRp ?: base
                val bonus = if (officialRp != null) (officialRp - base).coerceAtLeast(0) else null
                if (bonus != null) allBonuses += bonus
                for (team in alliance) {
                    val i = index[team] ?: continue
                    rp[i] += earned.toDouble()
                    scoreSum[i] += own.toDouble()
                    playedCount[i]++
                    when {
                        own > opp -> wins[i]++
                        own < opp -> losses[i]++
                        else -> ties[i]++
                    }
                    if (bonus != null) {
                        bonusSum[i] += bonus
                        bonusN[i]++
                    }
                }
            }
        }
        for (m in remaining) {
            for (team in m.red + m.blue) index[team]?.let { remainingCount[it]++ }
        }

        // ── Strength model ────────────────────────────────────────
        // With no scouting or stats data, fall back to OPR computed from this event's played scores.
        val usedEventOpr = input.strengths.isEmpty() && played.size >= 3
        val strengthInput = if (usedEventOpr) eventOpr(teamNumbers, index, played) else input.strengths
        val known = strengthInput.filterKeys { it in index }.values.filter { it.isFinite() }
        val median = if (known.isEmpty()) 1.0 else known.sorted()[known.size / 2]
        val strength = DoubleArray(teamCount) { i ->
            strengthInput[teamNumbers[i]]?.takeIf { it.isFinite() } ?: median
        }
        fun allianceStrength(teams: List<Int>) = teams.sumOf { t -> index[t]?.let { strength[it] } ?: median }

        // Scale strengths to the event's real scores, and measure how noisy scores are around that.
        val pairs = played.flatMap { m ->
            listOf(allianceStrength(m.red) to m.redScore!!.toDouble(), allianceStrength(m.blue) to m.blueScore!!.toDouble())
        }
        val predictedSum = pairs.sumOf { it.first }
        val scale = if (pairs.size >= 4 && predictedSum > 0) pairs.sumOf { it.second } / predictedSum else 1.0
        val meanAlliance = if (teamCount > 0) {
            remaining.flatMap { listOf(allianceStrength(it.red), allianceStrength(it.blue)) }
                .ifEmpty { pairs.map { it.first } }
                .ifEmpty { listOf(median * 3) }
                .average() * scale
        } else 0.0
        val noise = if (pairs.size >= 8) {
            sqrt(pairs.sumOf { (pred, actual) -> (actual - pred * scale).let { it * it } } / pairs.size)
        } else {
            0.25 * meanAlliance
        }.coerceAtLeast(max(1.0, 0.05 * meanAlliance))

        // Bonus RP: each team's bonus rate, shrunk toward the event average.
        val eventBonus = if (allBonuses.isEmpty()) 0.0 else allBonuses.average()
        val maxBonus = allBonuses.maxOrNull() ?: 0
        val shrink = 2.0
        val teamBonus = DoubleArray(teamCount) { i -> (bonusSum[i] + shrink * eventBonus) / (bonusN[i] + shrink) }

        // ── Monte Carlo ───────────────────────────────────────────
        val sims = input.simulations.coerceIn(1, MAX_SIMULATIONS)
        val rng = SplittableRandom(input.seed)
        val rankCounts = Array(teamCount) { IntArray(teamCount) }
        val finalRpTotal = DoubleArray(teamCount)
        val totalMatches = IntArray(teamCount) { playedCount[it] + remainingCount[it] }

        val remainingIdx = remaining.map { m ->
            m.red.mapNotNull { index[it] }.toIntArray() to m.blue.mapNotNull { index[it] }.toIntArray()
        }
        // We only estimate each team's strength, so each simulation draws its own value per team.
        // The spread shrinks as a team plays more matches: roughly the alliance-score noise
        // divided by the square root of matches seen (plus a small prior).
        val strengthSd = DoubleArray(teamCount) { i ->
            if (scale > 0) (noise / scale) / sqrt(playedCount[i] + 2.0) else 0.0
        }
        val simStrength = DoubleArray(teamCount)
        val simRp = DoubleArray(teamCount)
        val simScore = DoubleArray(teamCount)
        val tiebreak = DoubleArray(teamCount)
        val order = Array(teamCount) { it }

        fun gaussian(): Double {
            // Box–Muller
            val u1 = rng.nextDouble().coerceAtLeast(1e-12)
            val u2 = rng.nextDouble()
            return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        }

        fun sampleBonus(alliance: IntArray): Int {
            if (maxBonus <= 0 || alliance.isEmpty()) return 0
            val expected = alliance.sumOf { teamBonus[it] } / alliance.size
            val whole = floor(expected).toInt()
            val extra = if (rng.nextDouble() < expected - whole) 1 else 0
            return (whole + extra).coerceIn(0, maxBonus)
        }

        repeat(sims) {
            rp.copyInto(simRp)
            scoreSum.copyInto(simScore)
            if (remainingIdx.isNotEmpty()) {
                for (i in 0 until teamCount) simStrength[i] = (strength[i] + strengthSd[i] * gaussian()).coerceAtLeast(0.0)
            }
            for ((red, blue) in remainingIdx) {
                val r = (red.sumOf { simStrength[it] } * scale + noise * gaussian()).coerceAtLeast(0.0).roundToInt()
                val b = (blue.sumOf { simStrength[it] } * scale + noise * gaussian()).coerceAtLeast(0.0).roundToInt()
                val redEarned = winPart(r, b) + sampleBonus(red)
                val blueEarned = winPart(b, r) + sampleBonus(blue)
                for (i in red) { simRp[i] += redEarned.toDouble(); simScore[i] += r.toDouble() }
                for (i in blue) { simRp[i] += blueEarned.toDouble(); simScore[i] += b.toDouble() }
            }
            for (i in 0 until teamCount) {
                tiebreak[i] = rng.nextDouble()
                finalRpTotal[i] += simRp[i]
            }
            order.sortWith(
                compareByDescending<Int> { if (totalMatches[it] > 0) simRp[it] / totalMatches[it] else -1.0 }
                    .thenByDescending { if (totalMatches[it] > 0) simScore[it] / totalMatches[it] else -1.0 }
                    .thenBy { tiebreak[it] }
            )
            for ((rank, i) in order.withIndex()) rankCounts[i][rank]++
        }

        // ── Current ranks ─────────────────────────────────────────
        val currentOrder = (0 until teamCount)
            .filter { playedCount[it] > 0 }
            .sortedWith(
                compareByDescending<Int> { rp[it] / playedCount[it] }
                    .thenByDescending { scoreSum[it] / playedCount[it] }
                    .thenBy { teamNumbers[it] }
            )
        val currentRank = IntArray(teamCount)
        currentOrder.forEachIndexed { rank, i -> currentRank[i] = rank + 1 }

        fun percentileRank(counts: IntArray, fraction: Double): Int {
            val target = fraction * sims
            var cumulative = 0
            for (r in counts.indices) {
                cumulative += counts[r]
                if (cumulative >= target) return r + 1
            }
            return counts.size
        }

        val results = (0 until teamCount)
            .filter { totalMatches[it] > 0 }
            .map { i ->
                val counts = rankCounts[i]
                val meanRank = counts.withIndex().sumOf { (r, c) -> (r + 1).toDouble() * c } / sims
                val expectedRp = finalRpTotal[i] / sims
                SimTeamResult(
                    teamNumber = teamNumbers[i],
                    currentRank = currentRank[i].takeIf { it > 0 },
                    currentRp = rp[i],
                    currentRankingScore = if (playedCount[i] > 0) rp[i] / playedCount[i] else null,
                    wins = wins[i],
                    losses = losses[i],
                    ties = ties[i],
                    matchesPlayed = playedCount[i],
                    matchesRemaining = remainingCount[i],
                    expectedFinalRp = expectedRp,
                    expectedRankingScore = expectedRp / totalMatches[i],
                    projectedRank = meanRank,
                    medianRank = percentileRank(counts, 0.5),
                    bestLikelyRank = percentileRank(counts, 0.1),
                    worstLikelyRank = percentileRank(counts, 0.9),
                    probFirst = counts[0].toDouble() / sims,
                    probTopSeeds = counts.take(input.captainSlots.coerceAtMost(teamCount)).sum().toDouble() / sims
                )
            }
            .sortedWith(compareBy<SimTeamResult> { it.projectedRank }.thenBy { it.teamNumber })

        return SimResult(
            winRp = winRp,
            rpSource = if (hasOfficialRp) "official" else "win-loss",
            playedCount = played.size,
            usedEventOpr = usedEventOpr,
            teams = results
        )
    }

    /**
     * Ridge-regularised OPR from played scores: each alliance score is modelled as the sum of its
     * teams' contributions. Contributions are shrunk toward the event average so teams with few
     * matches don't get extreme values.
     */
    internal fun eventOpr(teamNumbers: List<Int>, index: Map<Int, Int>, played: List<SimMatch>): Map<Int, Double> {
        val n = teamNumbers.size
        val rows = played.flatMap { m -> listOf(m.red to m.redScore!!.toDouble(), m.blue to m.blueScore!!.toDouble()) }
            .map { (teams, score) -> teams.mapNotNull { index[it] } to score }
            .filter { it.first.isNotEmpty() }
        if (rows.isEmpty() || n == 0) return emptyMap()

        val prior = rows.sumOf { it.second } / rows.sumOf { it.first.size }
        val lambda = 2.0
        val ata = Array(n) { DoubleArray(n) }
        val atb = DoubleArray(n)
        for ((teams, score) in rows) {
            val residual = score - prior * teams.size
            for (i in teams) {
                atb[i] += residual
                for (j in teams) ata[i][j] += 1.0
            }
        }
        for (i in 0 until n) ata[i][i] += lambda

        // Gaussian elimination with partial pivoting; the ridge term keeps the system well conditioned.
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { kotlin.math.abs(ata[it][col]) } ?: col
            if (pivot != col) {
                val tmpRow = ata[pivot]; ata[pivot] = ata[col]; ata[col] = tmpRow
                val tmpB = atb[pivot]; atb[pivot] = atb[col]; atb[col] = tmpB
            }
            val diag = ata[col][col]
            for (r in col + 1 until n) {
                val factor = ata[r][col] / diag
                if (factor == 0.0) continue
                for (c in col until n) ata[r][c] -= factor * ata[col][c]
                atb[r] -= factor * atb[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var sum = atb[r]
            for (c in r + 1 until n) sum -= ata[r][c] * x[c]
            x[r] = sum / ata[r][r]
        }
        return teamNumbers.indices.associate { teamNumbers[it] to (prior + x[it]).coerceAtLeast(0.0) }
    }

    private data class Quad(val alliance: List<Int>, val own: Int, val opp: Int, val officialRp: Int?)

    /** Team number from a match team key such as "frc254", "254" or a B-bot "frc254b/9254". */
    internal fun teamNumberFromKey(key: String): Int? {
        val trimmed = key.trim().lowercase()
        if ("/" in trimmed) return trimmed.substringAfter("/").toIntOrNull()
        return trimmed.removePrefix("frc").removePrefix("ftc").toIntOrNull()
    }

    /**
     * @param seed random seed for the simulation; null uses a seed derived from the event and the
     *   number of played matches, so reloading the page shows the same table until results change.
     */
    fun project(
        session: UserSession,
        eventKeyParam: String,
        simulations: Int = DEFAULT_SIMULATIONS,
        seed: Long? = null
    ): RankingProjectionResponse {
        val eventKey = eventKeyParam.lowercase().trim()
        val isFtc = session.program.equals("FTC", ignoreCase = true)
        val sims = simulations.coerceIn(MIN_SIMULATIONS, MAX_SIMULATIONS)

        val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
        val useEpa = !isFtc && settings.useStatboticsEpa
        val useExp = !isFtc && settings.useMatch13Exp
        val useOpr = settings.useTbaOpr

        val teams: List<TeamRecord> = IntegrationService.listTeams(eventKey, session)
        val matches: List<MatchRecord> = IntegrationService.listMatches(eventKey, session.program)
            .filter { MatchCanonical.normalizeCompLevel(it.compLevel) == "qm" }

        // TBA's score breakdown carries the RP each alliance earned (bonus RPs included).
        val (officialRp, eventYear) = readTransaction {
            val rpByMatch = ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKey }.associate { row ->
                row[ApiMatches.matchKey] to readAllianceRp(row[ApiMatches.dataJson])
            }
            val year = ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.firstOrNull()?.get(ApiEvents.year)
            rpByMatch to year
        }
        val year = eventYear ?: eventKey.take(4).toIntOrNull() ?: java.time.Year.now().value
        // FRC (2025 on) and FTC (2025-26 on) award 3 RP per win; earlier seasons awarded 2.
        val defaultWinRp = if (year >= 2025) 3 else 2

        val simMatches = matches.map { m ->
            val rp = officialRp[m.matchKey]
            SimMatch(
                red = m.redTeams.mapNotNull { teamNumberFromKey(it) },
                blue = m.blueTeams.mapNotNull { teamNumberFromKey(it) },
                redScore = m.redScore,
                blueScore = m.blueScore,
                redRp = rp?.first,
                blueRp = rp?.second
            )
        }

        // Same blend as the alliance-selection "Weighted" metric.
        val metrics = mutableListOf("Scouted")
        if (useEpa) metrics += "EPA"
        if (useExp) metrics += "xP"
        if (useOpr) metrics += if (isFtc) "FTC Scout OPR" else "OPR"
        val strengths = teams.mapNotNull { t ->
            var num = 0.0
            var den = 0.0
            t.averagePoints?.let { num += it; den += 1.0 }
            if (useEpa) t.epa?.let { num += it * 0.8; den += 0.8 }
            if (useExp) t.exp?.let { num += it * 0.8; den += 0.8 }
            if (useOpr) t.opr?.let { num += it * 0.6; den += 0.6 }
            if (den > 0) t.teamNumber to num / den else null
        }.toMap()

        val teamCount = (teams.map { it.teamNumber } + simMatches.flatMap { it.red + it.blue }).distinct().size
        val captainSlots = (if (isFtc) 4 else 8).coerceAtMost(max(1, teamCount / 3))
        val played = simMatches.count { it.isPlayed }

        if (simMatches.isEmpty()) {
            return RankingProjectionResponse(
                eventKey = eventKey,
                simulations = 0,
                qualMatchesTotal = 0,
                qualMatchesPlayed = 0,
                winRp = defaultWinRp,
                rpSource = "win-loss",
                strengthMetrics = metrics,
                captainSlots = captainSlots,
                generatedAt = System.currentTimeMillis(),
                teams = emptyList(),
                note = "No qualification schedule has been published for this event yet."
            )
        }

        val result = simulate(
            SimInput(
                teams = teams.map { it.teamNumber },
                matches = simMatches,
                strengths = strengths,
                defaultWinRp = defaultWinRp,
                simulations = sims,
                // Default seed is stable for a given set of results, so refreshing doesn't reshuffle
                // the table; "Re-run" passes a fresh seed to draw a new set of simulations.
                seed = seed ?: (eventKey.hashCode().toLong() * 31 + played),
                captainSlots = captainSlots
            )
        )

        val teamInfo = teams.associateBy { it.teamNumber }
        val keyByNumber = simMatches.indices.flatMap { i ->
            (matches[i].redTeams + matches[i].blueTeams).mapNotNull { k -> teamNumberFromKey(k)?.let { it to k } }
        }.toMap()

        return RankingProjectionResponse(
            eventKey = eventKey,
            simulations = sims,
            qualMatchesTotal = simMatches.size,
            qualMatchesPlayed = result.playedCount,
            qualsComplete = result.playedCount == simMatches.size,
            winRp = result.winRp,
            rpSource = result.rpSource,
            strengthMetrics = if (result.usedEventOpr) listOf("OPR from this event's scores") else metrics,
            captainSlots = captainSlots,
            generatedAt = System.currentTimeMillis(),
            teams = result.teams.map { r ->
                val info = teamInfo[r.teamNumber]
                TeamRankingProjection(
                    teamNumber = r.teamNumber,
                    teamKey = info?.teamKey ?: keyByNumber[r.teamNumber] ?: r.teamNumber.toString(),
                    nickname = info?.nickname ?: info?.name,
                    currentRank = r.currentRank,
                    currentRp = r.currentRp,
                    currentRankingScore = r.currentRankingScore,
                    wins = r.wins,
                    losses = r.losses,
                    ties = r.ties,
                    matchesPlayed = r.matchesPlayed,
                    matchesRemaining = r.matchesRemaining,
                    expectedFinalRp = r.expectedFinalRp,
                    expectedRankingScore = r.expectedRankingScore,
                    projectedRank = r.projectedRank,
                    medianRank = r.medianRank,
                    bestLikelyRank = r.bestLikelyRank,
                    worstLikelyRank = r.worstLikelyRank,
                    probFirst = r.probFirst,
                    probTopSeeds = r.probTopSeeds
                )
            },
            note = if (strengths.isEmpty() && !result.usedEventOpr) {
                "No scouting, stats or played scores yet, so unplayed matches are treated as even."
            } else null
        )
    }

    /** Reads `score_breakdown.{red,blue}.rp` from a stored TBA match. */
    private fun readAllianceRp(dataJson: String): Pair<Int, Int>? {
        if (!dataJson.contains("\"rp\"")) return null
        return try {
            val json = JsonSupport.json.parseToJsonElement(dataJson) as? JsonObject ?: return null
            val breakdown = json["score_breakdown"] as? JsonObject ?: return null
            val red = ((breakdown["red"] as? JsonObject)?.get("rp") as? JsonPrimitive)?.intOrNull
            val blue = ((breakdown["blue"] as? JsonObject)?.get("rp") as? JsonPrimitive)?.intOrNull
            if (red != null && blue != null) red to blue else null
        } catch (_: Exception) {
            null
        }
    }
}
