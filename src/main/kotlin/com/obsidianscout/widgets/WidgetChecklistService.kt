package com.obsidianscout.widgets

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class WidgetChecklistItem(
    val id: String,
    val text: String,
    val priority: String,
    val checked: Boolean,
    val by: String? = null,
)

@Serializable
data class WidgetChecklistList(
    val id: String,
    /** Empty for the old fixed lists ("pre" / "field"); the widget shows its own translated name for those. */
    val title: String,
    /** "match" | "every_n" | "daily" | "event" */
    val frequency: String,
    val every: Int,
    /** Which completion record the list is filled out in: a match key, "day_YYYY-MM-DD" or "event". */
    val record: String,
    /** Whether the list is due for the upcoming match ("every N" lists skip some matches). */
    val due: Boolean,
    val items: List<WidgetChecklistItem>,
)

/**
 * Drive-team checklists as the Match Checklist page shows them: every list the team built, with its items and
 * what's checked, for our upcoming match (plus today's daily lists and the event lists).
 * Same documents and rules as the app (lib/screens/match_checklist_screen.dart) and the website.
 */
object WidgetChecklistService {
    private val FREQUENCIES = setOf("match", "every_n", "daily", "event")
    private val PRIORITIES = listOf("critical", "high", "normal", "low")
    private val LEGACY_LISTS = listOf("pre", "field")
    const val EVENT_RECORD = "event"

    private data class ListDef(val id: String, val title: String, val order: Int, val frequency: String, val every: Int)
    private data class ItemDef(val id: String, val text: String, val priority: String, val index: Int, val list: String)

    private fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun listsOf(template: JsonObject?): List<ListDef> {
        val defined = template?.get("lists") as? JsonObject
        if (defined != null) {
            return defined.mapNotNull { (id, raw) ->
                val l = raw as? JsonObject ?: return@mapNotNull null
                val freq = (l["frequency"] as? JsonPrimitive)?.content
                ListDef(
                    id = id,
                    title = str(l, "title").orEmpty(),
                    order = (l["order"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0,
                    frequency = if (freq in FREQUENCIES) freq!! else "match",
                    every = ((l["every"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 2).coerceIn(2, 99),
                )
            }.sortedWith(compareBy<ListDef> { it.order }.thenBy { it.id })
        }
        // Older templates: the fixed Pre-Match / On the Field lists, titled by `sections`.
        val sections = template?.get("sections") as? JsonObject
        return LEGACY_LISTS.mapIndexed { i, id ->
            ListDef(id, (sections?.get(id) as? JsonObject)?.let { str(it, "title") }.orEmpty(), i, "match", 2)
        }
    }

    /** Fillable items (an id and some text), sorted by priority, then the order set in Edit Checklists. */
    private fun itemsOf(template: JsonObject?): List<ItemDef> =
        (template?.get("fields") as? JsonArray).orEmpty().mapIndexedNotNull { i, raw ->
            val f = raw as? JsonObject ?: return@mapIndexedNotNull null
            val id = str(f, "id").orEmpty()
            val text = str(f, "text").orEmpty()
            if (id.isEmpty() || text.isBlank()) return@mapIndexedNotNull null
            val priority = str(f, "priority")?.takeIf { it in PRIORITIES } ?: "normal"
            ItemDef(id, text, priority, i, (f["section"] as? JsonPrimitive)?.content ?: "pre")
        }.sortedWith(compareBy<ItemDef> { PRIORITIES.indexOf(it.priority) }.thenBy { it.index })

    fun dayKey(zone: String, now: Instant = Instant.now()): String {
        val date = LocalDate.ofInstant(now, runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC")))
        return "day_$date"
    }

    private fun recordFor(def: ListDef, matchKey: String?, dayKey: String): String? = when (def.frequency) {
        "daily" -> dayKey
        "event" -> EVENT_RECORD
        else -> matchKey
    }

    /** "Every N" lists are due on our 1st, (N+1)th, ... match; matches outside our schedule always count. */
    private fun isDue(def: ListDef, ourKeys: List<String>, matchKey: String?): Boolean {
        if (def.frequency != "every_n") return true
        if (matchKey == null) return false
        val idx = ourKeys.indexOf(matchKey)
        return idx < 0 || idx % def.every == 0
    }

    private fun checkedOf(doc: JsonObject?): JsonObject = (doc?.get("checked") as? JsonObject) ?: JsonObject(emptyMap())

    /**
     * Every list for [matchKey] (our upcoming match, or null when we have none left; per-match lists are then
     * left out), with each item's checked state from its completion record.
     */
    fun lists(
        template: JsonObject?,
        ourKeys: List<String>,
        matchKey: String?,
        dayKey: String,
        load: (record: String) -> JsonObject?,
    ): List<WidgetChecklistList> {
        val items = itemsOf(template).groupBy { it.list }
        val loaded = HashMap<String, JsonObject>()
        return listsOf(template).mapNotNull { def ->
            val listItems = items[def.id].orEmpty()
            if (listItems.isEmpty()) return@mapNotNull null
            val record = recordFor(def, matchKey, dayKey) ?: return@mapNotNull null
            val checked = loaded.getOrPut(record) { checkedOf(load(record)) }
            WidgetChecklistList(
                id = def.id,
                title = def.title,
                frequency = def.frequency,
                every = def.every,
                record = record,
                due = isDue(def, ourKeys, matchKey),
                items = listItems.map { item ->
                    val mark = checked[item.id] as? JsonObject
                    WidgetChecklistItem(item.id, item.text, item.priority, checked = checked.containsKey(item.id), by = mark?.let { str(it, "by") })
                },
            )
        }
    }
}
