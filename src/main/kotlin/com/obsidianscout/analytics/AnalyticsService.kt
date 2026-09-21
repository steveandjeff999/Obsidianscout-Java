package com.obsidianscout.analytics

import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.AnalyticsWidget
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.ScoutingField
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.readTransaction
import com.obsidianscout.scouting.ScoutingEntryRecord
import com.obsidianscout.scouting.ScoutingService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import java.time.Instant

@Serializable
data class AnalyticsSeriesPoint(
    val label: String,
    val value: Double
)

@Serializable
data class AnalyticsWidgetResult(
    val id: String,
    val title: String,
    val type: String,
    val value: Double? = null,
    val series: List<AnalyticsSeriesPoint> = emptyList()
)

@Serializable
data class AnalyticsResponse(
    val generatedAt: String,
    val widgets: List<AnalyticsWidgetResult>
)

@Serializable
data class TeamComparisonMetric(
    val id: String,
    val title: String,
    val phase: String = "overview",
    val type: String = "number",
    val unit: String? = null,
    val pointsPer: Double? = null
)

@Serializable
data class TeamComparisonData(
    val teamNumber: Int,
    val nickname: String? = null,
    val matchesScouted: Int = 0,
    val epa: Double? = null,
    val opr: Double? = null,
    val avgTotalPoints: Double = 0.0,
    val avgAutoPoints: Double = 0.0,
    val avgTeleopPoints: Double = 0.0,
    val avgEndgamePoints: Double = 0.0,
    val maxPoints: Double = 0.0,
    val metrics: Map<String, Double> = emptyMap(),
    val metricSeries: Map<String, List<AnalyticsSeriesPoint>> = emptyMap(),
    val widgets: List<AnalyticsWidgetResult> = emptyList()
)

@Serializable
data class TeamComparisonResponse(
    val eventKey: String? = null,
    val metricDefinitions: List<TeamComparisonMetric> = emptyList(),
    val teams: Map<String, TeamComparisonData> = emptyMap()
)

object AnalyticsService {
    fun mergePrescoutEntries(
        currentEventEntries: List<ScoutingEntryRecord>,
        prescoutEntries: List<ScoutingEntryRecord>,
        currentEventKey: String,
        forceUsePrescout: Boolean = false
    ): List<ScoutingEntryRecord> {
        val groupedCurrent = currentEventEntries.filter { it.eventKey == currentEventKey && !it.isPrescout }.groupBy { it.targetTeamNumber }
        val groupedPrescout = prescoutEntries.filter { it.isPrescout }.groupBy { it.targetTeamNumber }
        
        val result = currentEventEntries.toMutableList()
        groupedPrescout.forEach { (teamNumber, pEntries) ->
            val currentCount = groupedCurrent[teamNumber]?.size ?: 0
            if (forceUsePrescout || currentCount < 3) {
                result.addAll(pEntries)
            }
        }
        return result
    }

    fun compareTeams(session: UserSession, teamNumbers: List<Int>, eventKeyParam: String?): TeamComparisonResponse {
        val config = ConfigService.getConfig(session.teamNumber, session.program)
        val eventKey = eventKeyParam?.trim()?.ifBlank { null }
        
        val allEntries = ScoutingService.listEntries(session, includePrescout = true, all = true)
        
        val teamInfoMap = readTransaction {
            val query = ApiTeams.selectAll().where { ApiTeams.teamNumber inList teamNumbers }
            if (eventKey != null) {
                query.andWhere { ApiTeams.eventKey eq eventKey.lowercase() }
            }
            query.toList().associateBy { it[ApiTeams.teamNumber] }
        }

        val teamDataMap = mutableMapOf<String, TeamComparisonData>()

        for (teamNum in teamNumbers) {
            val allTeamEntries = allEntries.filter { it.targetTeamNumber == teamNum }
            val eventEntries = if (eventKey != null) {
                allTeamEntries.filter { it.eventKey?.equals(eventKey, ignoreCase = true) == true && !it.isPrescout }
            } else {
                allTeamEntries.filter { !it.isPrescout }
            }
            val prescoutEntries = allTeamEntries.filter { it.isPrescout }

            val entries = if (eventKey == null || eventEntries.size < 3) {
                eventEntries + prescoutEntries
            } else {
                eventEntries
            }

            val count = entries.size
            val avgTotal = if (count > 0) entries.map { scoreEntry(config, it) }.average() else 0.0
            val maxTotal = if (count > 0) entries.maxOf { scoreEntry(config, it) } else 0.0
            
            val autoFields = config.fields.filter { it.phase?.lowercase()?.contains("auto") == true }
            val endgameFields = config.fields.filter { it.phase?.lowercase()?.contains("end") == true }
            val teleopFields = config.fields.filter { f ->
                val p = f.phase?.lowercase() ?: ""
                !p.contains("auto") && !p.contains("end")
            }

            val avgAuto = if (count > 0) entries.map { e -> autoFields.sumOf { fieldScore(it, e.data[it.id]) } }.average() else 0.0
            val avgEndgame = if (count > 0) entries.map { e -> endgameFields.sumOf { fieldScore(it, e.data[it.id]) } }.average() else 0.0
            val avgTeleop = if (count > 0) entries.map { e -> teleopFields.sumOf { fieldScore(it, e.data[it.id]) } }.average() else 0.0

            val metrics = mutableMapOf<String, Double>()
            val metricSeries = mutableMapOf<String, List<AnalyticsSeriesPoint>>()

            metrics["avg_total"] = avgTotal
            metrics["avg_auto"] = avgAuto
            metrics["avg_teleop"] = avgTeleop
            metrics["avg_endgame"] = avgEndgame
            metrics["max_points"] = maxTotal
            metrics["matches_scouted"] = count.toDouble()

            config.fields.forEach { field ->
                val fType = field.type.lowercase()
                if (fType in listOf("label", "divider", "section", "header")) return@forEach
                if (fType in listOf("counter", "number", "slider", "rating")) {
                    val vals = entries.mapNotNull { readNumber(it.data[field.id]) }
                    metrics[field.id] = if (vals.isNotEmpty()) vals.average() else 0.0
                } else if (fType in listOf("checkbox", "toggle", "boolean")) {
                    val bools = entries.mapNotNull { readBoolean(it.data[field.id]) }
                    metrics[field.id] = if (bools.isNotEmpty()) (bools.count { it }.toDouble() / bools.size * 100.0) else 0.0
                } else if (fType in listOf("select", "radio")) {
                    val counts = mutableMapOf<String, Int>()
                    entries.forEach { e ->
                        val raw = readLabel(e.data[field.id]) ?: return@forEach
                        val optLabel = field.options.firstOrNull { it.value == raw || it.label == raw }?.label ?: raw
                        counts[optLabel] = (counts[optLabel] ?: 0) + 1
                    }
                    metricSeries[field.id] = counts.entries
                        .sortedByDescending { it.value }
                        .map { AnalyticsSeriesPoint(it.key, it.value.toDouble()) }
                }
            }

            val teamRow = teamInfoMap[teamNum]
            val nickname = teamRow?.get(ApiTeams.nickname) ?: teamRow?.get(ApiTeams.name)
            val epa = teamRow?.get(ApiTeams.epa)
            val opr = teamRow?.get(ApiTeams.opr)
            if (epa != null) metrics["epa"] = epa
            if (opr != null) metrics["opr"] = opr

            val customWidgets = generate(config, entries).widgets

            teamDataMap[teamNum.toString()] = TeamComparisonData(
                teamNumber = teamNum,
                nickname = nickname,
                matchesScouted = count,
                epa = epa,
                opr = opr,
                avgTotalPoints = avgTotal,
                avgAutoPoints = avgAuto,
                avgTeleopPoints = avgTeleop,
                avgEndgamePoints = avgEndgame,
                maxPoints = maxTotal,
                metrics = metrics,
                metricSeries = metricSeries,
                widgets = customWidgets
            )
        }

        val metricDefs = mutableListOf<TeamComparisonMetric>()
        metricDefs.add(TeamComparisonMetric("avg_total", "Average Total Points", "overview", "number", "pts", 1.0))
        metricDefs.add(TeamComparisonMetric("avg_auto", "Auto Points (Avg)", "overview", "number", "pts"))
        metricDefs.add(TeamComparisonMetric("avg_teleop", "Teleop Points (Avg)", "overview", "number", "pts"))
        metricDefs.add(TeamComparisonMetric("avg_endgame", "Endgame Points (Avg)", "overview", "number", "pts"))
        metricDefs.add(TeamComparisonMetric("max_points", "Max Match Points", "overview", "number", "pts"))
        metricDefs.add(TeamComparisonMetric("matches_scouted", "Matches Scouted", "overview", "number", "matches"))
        metricDefs.add(TeamComparisonMetric("epa", "Statbotics EPA", "overview", "number", "EPA"))
        metricDefs.add(TeamComparisonMetric("opr", "TBA OPR", "overview", "number", "OPR"))

        config.fields.forEach { field ->
            val fType = field.type.lowercase()
            if (fType in listOf("label", "divider", "section", "header")) return@forEach
            val phase = field.phase?.lowercase() ?: "teleop"
            val displayType = if (fType in listOf("checkbox", "toggle", "boolean")) "percentage" else if (fType in listOf("select", "radio")) "bar" else "number"
            val unit = if (displayType == "percentage") "%" else (if (field.pointsPer != null && field.pointsPer > 0) "${field.pointsPer} pts" else null)
            metricDefs.add(
                TeamComparisonMetric(
                    id = field.id,
                    title = field.label.ifBlank { field.id },
                    phase = phase,
                    type = displayType,
                    unit = unit,
                    pointsPer = field.pointsPer
                )
            )
        }

        config.analytics.forEach { w ->
            metricDefs.add(
                TeamComparisonMetric(
                    id = "custom_${w.id}",
                    title = w.title,
                    phase = "custom",
                    type = w.type.lowercase()
                )
            )
        }

        return TeamComparisonResponse(
            eventKey = eventKey,
            metricDefinitions = metricDefs,
            teams = teamDataMap
        )
    }

    fun generate(config: ScoutingConfig, entries: List<ScoutingEntryRecord>): AnalyticsResponse {
        val widgets = config.analytics.map { widget ->
            when (widget.type.lowercase()) {
                "count" -> widgetResult(widget, value = entries.size.toDouble())
                "avg" -> widgetResult(widget, value = average(widget, entries))
                "sum" -> widgetResult(widget, value = sum(widget, entries))
                "bar" -> widgetResult(widget, series = barSeries(config, widget, entries))
                "score_total" -> widgetResult(widget, value = totalScore(config, entries))
                "score_avg" -> widgetResult(widget, value = averageScore(config, entries))
                else -> widgetResult(widget, value = 0.0)
            }
        }
        return AnalyticsResponse(
            generatedAt = Instant.now().toString(),
            widgets = widgets
        )
    }

    private fun average(widget: AnalyticsWidget, entries: List<ScoutingEntryRecord>): Double {
        val values = collectNumbers(widget, entries)
        return if (values.isEmpty()) 0.0 else values.average()
    }

    private fun sum(widget: AnalyticsWidget, entries: List<ScoutingEntryRecord>): Double {
        val values = collectNumbers(widget, entries)
        return values.sum()
    }

    private fun collectNumbers(widget: AnalyticsWidget, entries: List<ScoutingEntryRecord>): List<Double> {
        val fieldId = widget.fieldId ?: return emptyList()
        return entries.mapNotNull { entry ->
            readNumber(entry.data[fieldId])
        }
    }

    private fun barSeries(config: ScoutingConfig, widget: AnalyticsWidget, entries: List<ScoutingEntryRecord>): List<AnalyticsSeriesPoint> {
        val fieldId = widget.fieldId ?: return emptyList()
        val field = config.fields.firstOrNull { it.id == fieldId }
        val counts = mutableMapOf<String, Int>()
        entries.forEach { entry ->
            val raw = readLabel(entry.data[fieldId]) ?: return@forEach
            val label = field?.options?.firstOrNull { it.value == raw || it.label == raw }?.label ?: raw
            counts[label] = (counts[label] ?: 0) + 1
        }
        return counts.entries
            .sortedByDescending { it.value }
            .map { AnalyticsSeriesPoint(it.key, it.value.toDouble()) }
    }

    private fun widgetResult(
        widget: AnalyticsWidget,
        value: Double? = null,
        series: List<AnalyticsSeriesPoint> = emptyList()
    ): AnalyticsWidgetResult {
        return AnalyticsWidgetResult(
            id = widget.id,
            title = widget.title,
            type = widget.type.lowercase(),
            value = value,
            series = series
        )
    }

    private fun readLabel(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content
    }

    private fun readNumber(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content.toDoubleOrNull() ?: primitive.content.toIntOrNull()?.toDouble()
    }

    private fun readBoolean(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        return when (primitive.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun totalScore(config: ScoutingConfig, entries: List<ScoutingEntryRecord>): Double {
        return entries.sumOf { entry -> scoreEntry(config, entry) }
    }

    private fun averageScore(config: ScoutingConfig, entries: List<ScoutingEntryRecord>): Double {
        if (entries.isEmpty()) {
            return 0.0
        }
        return totalScore(config, entries) / entries.size
    }

    fun scoreEntry(config: ScoutingConfig, entry: ScoutingEntryRecord): Double {
        return config.fields.sumOf { field -> fieldScore(field, entry.data[field.id]) }
    }

    private fun fieldScore(field: ScoutingField, element: JsonElement?): Double {
        if (element == null) {
            return 0.0
        }
        return when (field.type.lowercase()) {
            "counter", "number", "rating" -> {
                val value = readNumber(element) ?: return 0.0
                (field.pointsPer ?: 0.0) * value
            }
            "checkbox" -> {
                val enabled = readBoolean(element) ?: false
                if (enabled) field.pointsPer ?: 0.0 else 0.0
            }
            "select" -> {
                val label = readLabel(element) ?: return 0.0
                val option = field.options.firstOrNull { it.value == label || it.label == label }
                option?.points ?: 0.0
            }
            else -> 0.0
        }
    }
}
