package com.obsidianscout.analytics

import com.obsidianscout.config.AnalyticsWidget
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.ScoutingField
import com.obsidianscout.scouting.ScoutingEntryRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

object AnalyticsService {
    fun generate(config: ScoutingConfig, entries: List<ScoutingEntryRecord>): AnalyticsResponse {
        val widgets = config.analytics.map { widget ->
            when (widget.type.lowercase()) {
                "count" -> widgetResult(widget, value = entries.size.toDouble())
                "avg" -> widgetResult(widget, value = average(widget, entries))
                "sum" -> widgetResult(widget, value = sum(widget, entries))
                "bar" -> widgetResult(widget, series = barSeries(widget, entries))
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

    private fun barSeries(widget: AnalyticsWidget, entries: List<ScoutingEntryRecord>): List<AnalyticsSeriesPoint> {
        val fieldId = widget.fieldId ?: return emptyList()
        val counts = mutableMapOf<String, Int>()
        entries.forEach { entry ->
            val label = readLabel(entry.data[fieldId]) ?: return@forEach
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
