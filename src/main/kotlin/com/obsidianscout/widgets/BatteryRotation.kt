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

    /** Same aliases as the app (battery_rotation.dart) and website (services/match-utils.js). */
    fun normalizeCompLevel(level: String): String = when (val compact = level.trim().lowercase().replace(Regex("[\\s_-]+"), "")) {
        "p", "pr", "pm", "prac", "prc", "practice", "practicematch" -> "practice"
        "q", "qm", "qual", "quals", "qualification", "qualificationmatch", "m", "match", "matches" -> "qm"
        "qf", "quarterfinal", "quarterfinals" -> "qf"
        "sf", "semifinal", "semifinals" -> "sf"
        "f", "final", "finals" -> "f"
        "ef", "einstein", "eighthfinal", "eighthfinals" -> "ef"
        "po", "playoff", "playoffs", "playoffmatch" -> "playoff"
        else -> compact
    }

    private data class KeyParts(val compLevel: String, val setNumber: Int, val matchNumber: Int)

    private val legacyKey = Regex("_([a-z]+)_s(\\d+)_m(\\d+)$", RegexOption.IGNORE_CASE)
    private val tbaSuffix = Regex("^([a-z]+)(\\d+)(?:m(\\d+))?$", RegexOption.IGNORE_CASE)
    private val customMatch = Regex(
        "^(practice|prac|pr|pm|p|qualification|quals|qual|qm|q|quarterfinals|quarterfinal|qf|semifinals|semifinal|sf|finals|final|f|einstein|ef|playoffs|playoff|po)(?:\\s+match)?(?:[\\s#:-]+(\\d+)(?:[\\s/-]+(\\d+))?)?$",
        RegexOption.IGNORE_CASE,
    )

    private fun parseMatchKey(matchKey: String): KeyParts {
        val key = matchKey.trim()
        if (key.isEmpty()) return KeyParts("", 1, 0)
        legacyKey.find(key)?.let { m ->
            return KeyParts(normalizeCompLevel(m.groupValues[1]), m.groupValues[2].toIntOrNull() ?: 1, m.groupValues[3].toIntOrNull() ?: 0)
        }
        if (key.lowercase().startsWith("custom_")) {
            val text = key.substring(7).replace('_', ' ').trim()
            val m = customMatch.find(text) ?: return KeyParts("", 1, 0)
            val hasSetAndMatch = m.groupValues[3].isNotEmpty()
            return KeyParts(
                normalizeCompLevel(m.groupValues[1]),
                if (hasSetAndMatch) m.groupValues[2].toIntOrNull() ?: 1 else 1,
                if (hasSetAndMatch) m.groupValues[3].toIntOrNull() ?: 0 else m.groupValues[2].toIntOrNull() ?: 0,
            )
        }
        val suffix = if ('_' in key) key.substring(key.lastIndexOf('_') + 1) else key
        val m = tbaSuffix.find(suffix) ?: return KeyParts("", 1, 0)
        val hasSetAndMatch = m.groupValues[3].isNotEmpty()
        return KeyParts(
            normalizeCompLevel(m.groupValues[1]),
            if (hasSetAndMatch) m.groupValues[2].toIntOrNull() ?: 1 else 1,
            if (hasSetAndMatch) m.groupValues[3].toIntOrNull() ?: 0 else m.groupValues[2].toIntOrNull() ?: 0,
        )
    }

    private fun levelRank(level: String, matchKey: String): Int {
        val parsed = parseMatchKey(matchKey)
        var norm = normalizeCompLevel(level)
        if (norm.isEmpty() ||
            (norm == "playoff" && parsed.compLevel.isNotEmpty() && parsed.compLevel != "playoff") ||
            (norm == "qm" && parsed.compLevel.isNotEmpty() && parsed.compLevel != "qm")
        ) {
            norm = parsed.compLevel.ifEmpty { norm }
        }
        return when (norm) {
            "practice" -> 0
            "qm" -> 1
            "qf" -> 2
            "sf" -> 3
            "f" -> 4
            "ef" -> 5
            "playoff" -> 6
            else -> 7
        }
    }

    /** Playoff set number from a match key (`..._sf2m1`, `..._sf_s2_m1`); 1 otherwise. */
    fun setNumberOf(matchKey: String): Int = parseMatchKey(matchKey).setNumber

    /** Match number from a match key (`..._sf2m1` -> 1, `..._qm10` -> 10); 0 otherwise. */
    fun matchNumberOf(matchKey: String): Int = parseMatchKey(matchKey).matchNumber

    /**
     * Practice, then qualifications, then playoffs (QF, SF, F); by set and match number within each, then key.
     * Same order as the app (battery_rotation.dart) and the website (ObsidianMatchUtils.compareMatches), so every
     * surface hands out the same battery per match.
     */
    fun compareMatchOrder(levelA: String, numberA: Int?, keyA: String, levelB: String, numberB: Int?, keyB: String): Int {
        val byLevel = levelRank(levelA, keyA) - levelRank(levelB, keyB)
        if (byLevel != 0) return byLevel
        val bySet = setNumberOf(keyA) - setNumberOf(keyB)
        if (bySet != 0) return bySet
        val numA = numberA?.takeIf { it > 0 } ?: matchNumberOf(keyA)
        val numB = numberB?.takeIf { it > 0 } ?: matchNumberOf(keyB)
        val byNumber = numA - numB
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
