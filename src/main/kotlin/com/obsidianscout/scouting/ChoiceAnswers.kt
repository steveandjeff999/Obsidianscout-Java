package com.obsidianscout.scouting

import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.routes.ScoutingEntryRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Select, radio and multiselect answers are shown on analytics pages (some of them public) as option text. An answer
 * that isn't one of the field's options can't be trusted to be plain text, so markup characters are stripped from it.
 * Answers are not rejected: offline devices submit entries recorded against an older config, and those must still save.
 */
object ChoiceAnswers {
    private val CHOICE_TYPES = setOf("select", "radio", "multiselect", "multi-select", "dropdown", "checkboxes")
    private val MARKUP_CHARS = Regex("[<>\"'`]")

    fun sanitize(config: ScoutingConfig, data: JsonObject): JsonObject {
        var changed = false
        val out = LinkedHashMap<String, JsonElement>(data)
        for (field in config.fields) {
            if (field.type.lowercase() !in CHOICE_TYPES) continue
            val value = data[field.id] ?: continue
            val allowed = field.options.mapTo(HashSet()) { it.value }
            val cleaned = clean(value, allowed)
            if (cleaned !== value) {
                out[field.id] = cleaned
                changed = true
            }
        }
        return if (changed) JsonObject(out) else data
    }

    fun sanitize(config: ScoutingConfig, request: ScoutingEntryRequest): ScoutingEntryRequest {
        val cleaned = sanitize(config, request.data)
        return if (cleaned === request.data) request else request.copy(data = cleaned)
    }

    private fun clean(value: JsonElement, allowed: Set<String>): JsonElement = when {
        value is JsonPrimitive && value.isString -> cleanString(value, allowed)
        value is JsonArray -> {
            val items = value.map { clean(it, allowed) }
            if (items.indices.all { items[it] === value[it] }) value else JsonArray(items)
        }
        else -> value
    }

    private fun cleanString(value: JsonPrimitive, allowed: Set<String>): JsonPrimitive {
        val text = value.content
        if (text in allowed || !MARKUP_CHARS.containsMatchIn(text)) return value
        return JsonPrimitive(MARKUP_CHARS.replace(text, ""))
    }
}
