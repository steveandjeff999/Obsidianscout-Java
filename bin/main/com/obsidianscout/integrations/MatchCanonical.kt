package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ScoutingEntries
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

data class MatchSyncRecord(
    val matchKey: String,
    val eventKey: String,
    val compLevel: String,
    val setNumber: Int? = null,
    val matchNumber: Int? = null,
    val scheduledTime: Long? = null,
    val actualTime: Long? = null,
    val redTeams: List<String> = emptyList(),
    val blueTeams: List<String> = emptyList(),
    val dataJson: String = "{}",
    val source: String = "unknown"
)

/**
 * Unifies TBA and FIRST match naming (e.g. TBA key `2026okok_qm4` vs FIRST "Qualification" match 4).
 */
object MatchCanonical {
    private val log = LoggerFactory.getLogger("MatchCanonical")

    private val TBA_KEY_SUFFIX = Regex("""^([a-z]+)(\d+)(?:m(\d+))?$""", RegexOption.IGNORE_CASE)
    private val KNOWN_COMP_LEVELS = setOf("qm", "qf", "sf", "f", "ef", "practice", "playoff")

    data class Identity(
        val eventKey: String,
        val compLevel: String,
        val setNumber: Int,
        val matchNumber: Int
    ) {
        fun toMatchKey(): String = when (compLevel) {
            "qm", "practice" -> "${eventKey}_${compLevel}$matchNumber"
            else -> "${eventKey}_${compLevel}${setNumber}m$matchNumber"
        }
    }

    fun normalizeCompLevel(raw: String): String {
        val compact = raw.trim().lowercase().replace(" ", "").replace("_", "")
        return when {
            compact.isEmpty() -> ""
            compact in setOf("qm", "qual", "qualification", "quals", "q") -> "qm"
            compact in setOf("m", "match", "matches") -> "qm"
            compact in setOf("qf", "quarterfinal", "quarterfinals") -> "qf"
            compact in setOf("sf", "semifinal", "semifinals") -> "sf"
            compact == "f" || compact == "final" || compact == "finals" -> "f"
            compact == "ef" || compact == "einstein" -> "ef"
            compact in setOf("practice", "prac", "pr", "prc") -> "practice"
            compact == "playoff" || compact == "playoffs" -> "playoff"
            else -> compact.take(16)
        }
    }

    /** FIRST often labels playoffs only as "Playoff"; map set number to TBA-style levels. */
    fun playoffSetToCompLevel(setNumber: Int): String = when (setNumber) {
        1 -> "qf"
        2 -> "sf"
        3 -> "f"
        else -> "playoff"
    }

    fun parseTbaMatchKey(matchKey: String): Identity? {
        val eventKey = matchKey.substringBeforeLast('_', "").lowercase()
        if (eventKey.isBlank()) {
            return null
        }
        val suffix = matchKey.substringAfterLast('_')
        val match = TBA_KEY_SUFFIX.matchEntire(suffix) ?: return null
        val (compRaw, firstNum, secondNum) = match.destructured
        val compLevel = normalizeCompLevel(compRaw)
        return if (secondNum.isNotEmpty()) {
            Identity(
                eventKey = eventKey,
                compLevel = compLevel,
                setNumber = firstNum.toIntOrNull() ?: 1,
                matchNumber = secondNum.toIntOrNull() ?: 1
            )
        } else {
            Identity(
                eventKey = eventKey,
                compLevel = compLevel,
                setNumber = 1,
                matchNumber = firstNum.toIntOrNull() ?: 1
            )
        }
    }

    fun identityFrom(record: MatchSyncRecord): Identity {
        val eventKey = record.eventKey.lowercase()
        val parsedFromKey = parseTbaMatchKey(record.matchKey)
        var compLevel = normalizeCompLevel(record.compLevel)
        var setNumber = record.setNumber ?: parsedFromKey?.setNumber ?: 1
        var matchNumber = record.matchNumber ?: parsedFromKey?.matchNumber ?: 1

        if (compLevel.isBlank()) {
            compLevel = parsedFromKey?.compLevel ?: "qm"
        }
        if (parsedFromKey != null && compLevel == "qm" && parsedFromKey.compLevel.isNotBlank()) {
            if (record.compLevel.isBlank() || normalizeCompLevel(record.compLevel) == "qm") {
                val parsedLevel = parsedFromKey.compLevel
                if (KNOWN_COMP_LEVELS.contains(parsedLevel)) {
                    compLevel = parsedLevel
                    setNumber = parsedFromKey.setNumber
                    matchNumber = parsedFromKey.matchNumber
                }
            }
        }

        if (compLevel == "playoff") {
            compLevel = playoffSetToCompLevel(setNumber)
        }

        return Identity(
            eventKey = eventKey,
            compLevel = compLevel,
            setNumber = setNumber.coerceAtLeast(1),
            matchNumber = matchNumber.coerceAtLeast(1)
        )
    }

    fun canonicalize(record: MatchSyncRecord): MatchSyncRecord {
        val identity = identityFrom(record)
        return record.copy(
            eventKey = identity.eventKey,
            matchKey = identity.toMatchKey(),
            compLevel = identity.compLevel,
            setNumber = identity.setNumber,
            matchNumber = identity.matchNumber,
            redTeams = normalizeTeamKeys(record.redTeams),
            blueTeams = normalizeTeamKeys(record.blueTeams)
        )
    }

    fun teamFingerprint(red: List<String>, blue: List<String>): String {
        val r = normalizeTeamKeys(red).sorted()
        val b = normalizeTeamKeys(blue).sorted()
        if (r.isEmpty() && b.isEmpty()) {
            return ""
        }
        return "${r.joinToString(",")}|${b.joinToString(",")}"
    }

    fun fingerprintsAlign(a: MatchSyncRecord, b: MatchSyncRecord): Boolean {
        val fpA = teamFingerprint(a.redTeams, a.blueTeams)
        val fpB = teamFingerprint(b.redTeams, b.blueTeams)
        if (fpA.isBlank() || fpB.isBlank()) {
            return false
        }
        return fpA == fpB
    }

    fun mergeRecords(existing: MatchSyncRecord, incoming: MatchSyncRecord): MatchSyncRecord {
        val preferred = if (recordScore(incoming) >= recordScore(existing)) incoming else existing
        val other = if (preferred === incoming) existing else incoming
        return preferred.copy(
            scheduledTime = preferred.scheduledTime ?: other.scheduledTime,
            actualTime = preferred.actualTime ?: other.actualTime,
            redTeams = preferred.redTeams.ifEmpty { other.redTeams },
            blueTeams = preferred.blueTeams.ifEmpty { other.blueTeams },
            dataJson = if (preferred.dataJson.length >= other.dataJson.length) preferred.dataJson else other.dataJson
        ).let { canonicalize(it) }
    }

    fun mergeAll(records: List<MatchSyncRecord>): List<MatchSyncRecord> {
        val byKey = linkedMapOf<String, MatchSyncRecord>()
        val byFingerprint = linkedMapOf<String, String>()

        records.map { canonicalize(it) }.forEach { record ->
            val key = record.matchKey
            val fingerprint = teamFingerprint(record.redTeams, record.blueTeams)

            val aliasKey = byFingerprint[fingerprint]?.takeIf { alias ->
                val existing = byKey[alias]
                existing != null && fingerprintsAlign(existing, record)
            }

            val targetKey = when {
                byKey.containsKey(key) -> key
                aliasKey != null -> aliasKey
                else -> key
            }

            val merged = byKey[targetKey]?.let { existing ->
                mergeRecords(existing, record)
            } ?: record
            byKey[targetKey] = merged
            if (fingerprint.isNotBlank()) {
                byFingerprint[fingerprint] = merged.matchKey
            }
        }
        return byKey.values.toList()
    }

    fun displayLabel(compLevel: String, setNumber: Int?, matchNumber: Int?): String {
        val level = normalizeCompLevel(compLevel)
        val matchNum = matchNumber ?: 0
        val setNum = setNumber ?: 1
        val abbrev = when (level) {
            "qm" -> "QM"
            "qf" -> "QF"
            "sf" -> "SF"
            "f" -> "F"
            "ef" -> "EF"
            "practice" -> "Practice"
            else -> level.uppercase()
        }
        return if (level == "qm" || level == "practice") {
            "$abbrev $matchNum"
        } else {
            "$abbrev $setNum-$matchNum"
        }
    }

    /**
     * Removes duplicate rows for an event and rewrites scouting entry match keys to the kept canonical key.
     */
    fun deduplicateDatabaseForEvent(eventKey: String): Int {
        val normalizedEvent = eventKey.lowercase()
        return transaction {
            val rows = ApiMatches
                .selectAll().where { ApiMatches.eventKey eq normalizedEvent }
                .toList()
            if (rows.size <= 1) {
                return@transaction 0
            }

            var working = rows
            var removed = 0

            // Secondary pass: merge rows with identical alliances but different legacy keys.
            val fingerprintGroups = working
                .map { it to teamFingerprint(decodeTeams(it[ApiMatches.redTeams]), decodeTeams(it[ApiMatches.blueTeams])) }
                .filter { it.second.isNotBlank() }
                .groupBy { it.second }
                .filter { it.value.size > 1 }

            fingerprintGroups.values.forEach { entries ->
                val group = entries.map { it.first }
                val records = group.map { it.toSyncRecord() }
                val merged = records.reduce { acc, next -> mergeRecords(acc, next) }
                val keeperRow = group.maxByOrNull { recordScore(it.toSyncRecord()) } ?: group.first()
                val keeperId = keeperRow[ApiMatches.id].value
                group.filter { it[ApiMatches.id].value != keeperId }.forEach { dup ->
                    remapMatchKey(dup[ApiMatches.matchKey], merged.matchKey)
                    ApiMatches.deleteWhere { ApiMatches.id eq dup[ApiMatches.id] }
                    removed++
                }
                ApiMatches.update({ ApiMatches.id eq keeperId }) {
                    it[ApiMatches.matchKey] = merged.matchKey
                    it[ApiMatches.compLevel] = merged.compLevel
                    it[ApiMatches.setNumber] = merged.setNumber
                    it[ApiMatches.matchNumber] = merged.matchNumber
                    it[ApiMatches.scheduledTime] = merged.scheduledTime
                    it[ApiMatches.actualTime] = merged.actualTime
                    it[ApiMatches.redTeams] = encodeTeams(merged.redTeams)
                    it[ApiMatches.blueTeams] = encodeTeams(merged.blueTeams)
                }
                if (keeperRow[ApiMatches.matchKey] != merged.matchKey) {
                    remapMatchKey(keeperRow[ApiMatches.matchKey], merged.matchKey)
                }
            }

            working = ApiMatches.selectAll().where { ApiMatches.eventKey eq normalizedEvent }.toList()
            val groups = working.groupBy { row -> identityFrom(row.toSyncRecord()).toMatchKey() }

            groups.forEach { (canonicalKey, group) ->
                if (group.size <= 1) {
                    val single = group.first()
                    val currentKey = single[ApiMatches.matchKey]
                    if (currentKey != canonicalKey) {
                        remapMatchKey(currentKey, canonicalKey)
                        ApiMatches.update({ ApiMatches.id eq single[ApiMatches.id] }) {
                            it[ApiMatches.matchKey] = canonicalKey
                            it[ApiMatches.compLevel] = normalizeCompLevel(single[ApiMatches.compLevel])
                        }
                    }
                    return@forEach
                }

                val records = group.map { it.toSyncRecord() }
                val merged = records.reduce { acc, next -> mergeRecords(acc, next) }
                val keeperRow = group.maxByOrNull { recordScore(it.toSyncRecord()) } ?: group.first()
                val keeperId = keeperRow[ApiMatches.id].value

                group.filter { it[ApiMatches.id].value != keeperId }.forEach { dup ->
                    val oldKey = dup[ApiMatches.matchKey]
                    remapMatchKey(oldKey, merged.matchKey)
                    ApiMatches.deleteWhere { ApiMatches.id eq dup[ApiMatches.id] }
                    removed++
                }

                ApiMatches.update({ ApiMatches.id eq keeperId }) {
                    it[ApiMatches.matchKey] = merged.matchKey
                    it[ApiMatches.compLevel] = merged.compLevel
                    it[ApiMatches.setNumber] = merged.setNumber
                    it[ApiMatches.matchNumber] = merged.matchNumber
                    it[ApiMatches.scheduledTime] = merged.scheduledTime
                    it[ApiMatches.actualTime] = merged.actualTime
                    it[ApiMatches.redTeams] = encodeTeams(merged.redTeams)
                    it[ApiMatches.blueTeams] = encodeTeams(merged.blueTeams)
                    it[ApiMatches.dataJson] = merged.dataJson
                }

                if (keeperRow[ApiMatches.matchKey] != merged.matchKey) {
                    remapMatchKey(keeperRow[ApiMatches.matchKey], merged.matchKey)
                }
            }

            if (removed > 0) {
                log.info("Deduplicated $removed duplicate match(es) for event $normalizedEvent")
            }
            removed
        }
    }

    private fun remapMatchKey(oldKey: String, newKey: String) {
        if (oldKey == newKey) {
            return
        }
        ScoutingEntries.update({ ScoutingEntries.matchKey eq oldKey }) {
            it[ScoutingEntries.matchKey] = newKey
        }
    }

    private fun recordScore(record: MatchSyncRecord): Int {
        var score = 0
        if (record.source == "tba") score += 4
        if (record.redTeams.isNotEmpty() && record.blueTeams.isNotEmpty()) score += 3
        if (record.scheduledTime != null) score += 2
        score += record.dataJson.length / 100
        return score
    }

    private fun ResultRow.toSyncRecord(): MatchSyncRecord {
        return MatchSyncRecord(
            matchKey = this[ApiMatches.matchKey],
            eventKey = this[ApiMatches.eventKey],
            compLevel = this[ApiMatches.compLevel],
            setNumber = this[ApiMatches.setNumber],
            matchNumber = this[ApiMatches.matchNumber],
            scheduledTime = this[ApiMatches.scheduledTime],
            actualTime = this[ApiMatches.actualTime],
            redTeams = decodeTeams(this[ApiMatches.redTeams]),
            blueTeams = decodeTeams(this[ApiMatches.blueTeams]),
            dataJson = this[ApiMatches.dataJson],
            source = "db"
        )
    }

    private fun decodeTeams(text: String): List<String> =
        JsonSupport.json.decodeFromString(ListSerializer(String.serializer()), text)

    private fun encodeTeams(teams: List<String>): String =
        JsonSupport.json.encodeToString(ListSerializer(String.serializer()), teams)

    private fun normalizeTeamKeys(teams: List<String>): List<String> =
        teams.mapNotNull { key ->
            val trimmed = key.trim().lowercase()
            when {
                trimmed.isBlank() -> null
                trimmed.contains('/') -> {
                    val parts = trimmed.split('/')
                    val num = parts.last().trim().removePrefix("frc")
                    "frc$num"
                }
                trimmed.startsWith("frc") -> trimmed
                trimmed.all { it.isDigit() } -> "frc$trimmed"
                else -> trimmed
            }
        }
}
