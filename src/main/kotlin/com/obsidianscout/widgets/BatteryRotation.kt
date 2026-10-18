package com.obsidianscout.widgets

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Server copy of the battery rotation, so home-screen widgets can show which battery a match needs while the
 * app is closed. Same rules as the website's js/services/battery-rotation.js and the app's
 * lib/services/battery_rotation.dart; all three are checked against battery-rotation-cases.json.
 *
 * Each match gets the battery recorded as `used`, else its pinned battery (unless skipped), else the next
 * battery in the rotation after the previous match's battery that isn't skipped for it.
 */
object BatteryRotation {
    data class Battery(
        val id: String,
        val name: String,
        val type: String,
        val order: Double,
        val retired: Boolean,
    ) {
        val isPit get() = type == "pit"
        val displayName get() = name.trim().ifEmpty { id }
    }

    /** batteryId is null when every battery is skipped; source is "used" | "pinned" | "auto" | "none". */
    data class Slot(val batteryId: String?, val source: String, val skipped: List<String>)

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val chunk = Regex("\\d+|\\D+")

    /** "B2" before "B10"; otherwise plain code-unit order, the same as the website. */
    fun naturalCompare(a: String, b: String): Int {
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            if (x[0].isDigit() && y[0].isDigit()) {
                val d = x.toBigInteger().compareTo(y.toBigInteger())
                if (d != 0) return d
            } else if (x != y) {
                return if (x < y) -1 else 1
            }
        }
        return ca.size - cb.size
    }

    private fun levelRank(level: String): Int = when (level.trim().lowercase()) {
        "p", "pr", "pm", "practice" -> 0
        "q", "qm", "qual", "qualification" -> 1
        else -> 2
    }

    private val tbaSet = Regex("_[a-z]+(\\d+)m\\d+$")
    private val legacySet = Regex("_s(\\d+)_m\\d+$")

    fun setNumberOf(matchKey: String): Int {
        val m = tbaSet.find(matchKey) ?: legacySet.find(matchKey)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    /** Practice, then qualifications, then playoffs; by set and match number within each, then key. */
    fun compareMatchOrder(levelA: String, numberA: Int?, keyA: String, levelB: String, numberB: Int?, keyB: String): Int {
        val byLevel = levelRank(levelA) - levelRank(levelB)
        if (byLevel != 0) return byLevel
        val bySet = setNumberOf(keyA) - setNumberOf(keyB)
        if (bySet != 0) return bySet
        val byNumber = (numberA ?: 0) - (numberB ?: 0)
        if (byNumber != 0) return byNumber
        return keyA.compareTo(keyB)
    }

    fun batteriesOf(inventory: JsonObject?): List<Battery> {
        val map = inventory?.get("batteries").obj() ?: return emptyList()
        return map.mapNotNull { (id, raw) ->
            val b = raw.obj() ?: return@mapNotNull null
            val order = (b["order"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: 0.0
            Battery(
                id = id,
                name = b["name"].str() ?: "",
                type = if (b["type"].str() == "pit") "pit" else "match",
                order = order,
                retired = (b["retired"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }.sortedWith { a, b ->
            val byOrder = a.order.compareTo(b.order)
            if (byOrder != 0) return@sortedWith byOrder
            val byName = naturalCompare(a.name, b.name)
            if (byName != 0) return@sortedWith byName
            a.id.compareTo(b.id)
        }
    }

    fun rotationOf(inventory: JsonObject?): List<Battery> = batteriesOf(inventory).filter { !it.isPit && !it.retired }

    /** The battery for each of [matchKeys] (our matches, already in play order). */
    fun computeSchedule(inventory: JsonObject?, plan: JsonObject?, matchKeys: List<String>): Map<String, Slot> {
        val all = batteriesOf(inventory).associateBy { it.id }
        val rotation = rotationOf(inventory)
        val index = rotation.withIndex().associate { (i, b) -> b.id to i }
        var pointer = index[plan?.get("startAt").str()] ?: 0
        val matches = plan?.get("matches").obj()
        val result = LinkedHashMap<String, Slot>()

        for (matchKey in matchKeys) {
            val entry = matches?.get(matchKey).obj()
            val skipped = entry?.get("skipped").obj()?.keys?.toList() ?: emptyList()
            val skip = skipped.toSet()
            val used = entry?.get("used").str()
            val pinned = entry?.get("pinned").str()
            var batteryId: String? = null
            var source = "none"

            if (used != null && used in all) {
                batteryId = used
                source = "used"
            } else if (pinned != null && pinned in all && all[pinned]?.retired != true && pinned !in skip) {
                batteryId = pinned
                source = "pinned"
            } else {
                for (step in rotation.indices) {
                    val candidate = rotation[(pointer + step) % rotation.size]
                    if (candidate.id !in skip) {
                        batteryId = candidate.id
                        source = "auto"
                        break
                    }
                }
            }

            val at = batteryId?.let { index[it] }
            if (at != null) pointer = (at + 1) % rotation.size
            result[matchKey] = Slot(batteryId, source, skipped)
        }
        return result
    }
}
