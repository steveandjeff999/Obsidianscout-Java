package com.obsidianscout.integrations

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.MatchChecklists
import com.obsidianscout.db.MatchPlans
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
    /** Key format older manual match saves produced, e.g. `2026okok_qm_s1_m5`. */
    private val LEGACY_MANUAL_KEY = Regex("""^(.+)_([a-z]+)_s(\d+)_m(\d+)$""", RegexOption.IGNORE_CASE)

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
            // "pm" is TBA's practice level (API 3.27.0+), e.g. 2026cttd_pm3.
            compact in setOf("practice", "prac", "pr", "prc", "pm") -> "practice"
            compact == "playoff" || compact == "playoffs" -> "playoff"
            else -> compact.take(16)
        }
    }

    /** Chronological order of competition levels within an event (practice first). */
    fun compLevelRank(compLevel: String): Int = when (normalizeCompLevel(compLevel)) {
        "practice" -> 0
        "qm" -> 1
        "qf" -> 2
        "sf" -> 3
        "f" -> 4
        "ef" -> 5
        "playoff" -> 6
        else -> 7
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

    /**
     * Parses the `{event}_{level}_s{set}_m{match}` keys that manual match saves used to generate.
     * [parseTbaMatchKey] can't handle these: it only sees the `m{match}` suffix and reads every one as a qual.
     */
    fun parseLegacyManualKey(matchKey: String): Identity? {
        val match = LEGACY_MANUAL_KEY.matchEntire(matchKey.trim()) ?: return null
        val (eventKey, compRaw, setRaw, matchRaw) = match.destructured
        val setNumber = (setRaw.toIntOrNull() ?: 1).coerceAtLeast(1)
        var compLevel = normalizeCompLevel(compRaw)
        if (compLevel == "playoff") {
            compLevel = playoffSetToCompLevel(setNumber)
        }
        return Identity(
            eventKey = eventKey.lowercase(),
            compLevel = compLevel,
            setNumber = setNumber,
            matchNumber = (matchRaw.toIntOrNull() ?: 1).coerceAtLeast(1)
        )
    }

    fun identityFrom(record: MatchSyncRecord): Identity {
        val eventKey = record.eventKey.lowercase()
        val parsedFromKey = parseLegacyManualKey(record.matchKey) ?: parseTbaMatchKey(record.matchKey)
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

    /**
     * Combines two copies of the same match. The copy [preferIncoming] picks supplies the JSON and teams,
     * the other fills gaps (times, teams, TBA videos). The choice depends only on the two copies' content and
     * sources, never on which arrived first, so merging the same data in any order gives the same row.
     */
    fun mergeRecords(existing: MatchSyncRecord, incoming: MatchSyncRecord): MatchSyncRecord {
        val preferred = if (preferIncoming(existing, incoming)) incoming else existing
        val other = if (preferred === incoming) existing else incoming
        // Only TBA publishes match videos. Keep them when the other source's JSON wins the merge.
        val chosenJson = carryVideos(preferred.dataJson, other.dataJson)
        return preferred.copy(
            scheduledTime = preferred.scheduledTime ?: other.scheduledTime,
            actualTime = preferred.actualTime ?: other.actualTime,
            redTeams = preferred.redTeams.ifEmpty { other.redTeams },
            blueTeams = preferred.blueTeams.ifEmpty { other.blueTeams },
            dataJson = chosenJson
        ).let { canonicalize(it) }
    }

    /**
     * Whether [incoming] should win over [stored]: a copy with scores beats one without, then TBA (or FTC
     * Scout) beats FIRST beats anything else, and on a full tie the newer copy wins so corrections from
     * the same source still land.
     */
    fun preferIncoming(stored: MatchSyncRecord, incoming: MatchSyncRecord): Boolean {
        val storedScored = hasScores(stored.dataJson)
        val incomingScored = hasScores(incoming.dataJson)
        if (storedScored != incomingScored) return incomingScored
        val storedRank = matchSourceRank(effectiveSource(stored))
        val incomingRank = matchSourceRank(effectiveSource(incoming))
        if (storedRank != incomingRank) return incomingRank > storedRank
        return true
    }

    /**
     * What a stored match row should hold once a sync brings [incoming]. Rows are shared by every team at
     * the event and teams sync with different keys, so an API row is merged rather than overwritten (see
     * [mergeRecords]). A row no API wrote (a manual entry) is replaced, as before.
     */
    fun resolveAgainstStored(stored: MatchSyncRecord, incoming: MatchSyncRecord): MatchSyncRecord =
        if (matchSourceRank(effectiveSource(stored)) == 0) incoming else mergeRecords(stored, incoming)

    /** A stored match row as a record, for [resolveAgainstStored]. */
    fun storedRecord(row: ResultRow): MatchSyncRecord = row.toSyncRecord()

    /** Which API a match's JSON came from, judged by the fields each one sends. */
    fun sourceOf(dataJson: String): String {
        val obj = runCatching { JsonSupport.json.parseToJsonElement(dataJson) as? kotlinx.serialization.json.JsonObject }
            .getOrNull() ?: return "unknown"
        return when {
            obj.containsKey("comp_level") && obj.containsKey("alliances") -> "tba"
            obj.containsKey("hasBeenPlayed") || obj.containsKey("matchNum") -> "ftcscout"
            obj.containsKey("scoreRedFinal") || obj.containsKey("tournamentLevel") || obj.containsKey("matchNumber") -> "first"
            else -> "unknown"
        }
    }

    private fun effectiveSource(record: MatchSyncRecord): String =
        if (record.source in setOf("tba", "first", "ftcscout")) record.source else sourceOf(record.dataJson)

    private fun matchSourceRank(source: String): Int = when (source) {
        "tba", "ftcscout" -> 2
        "first" -> 1
        else -> 0
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
            // A single row still goes through below so it gets its canonical key.
            if (rows.isEmpty()) {
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

    /**
     * Points every row that references [oldKey] at [newKey]. Must run inside a transaction.
     * Match plans and checklists are unique per owner/event/match, so when both keys already have one the newer is kept.
     */
    fun remapMatchKey(oldKey: String, newKey: String) {
        if (oldKey == newKey) {
            return
        }
        ScoutingEntries.update({ ScoutingEntries.matchKey eq oldKey }) {
            it[ScoutingEntries.matchKey] = newKey
            it[ScoutingEntries.updatedAt] = java.time.Instant.now()
        }
        QualitativeScoutingEntries.update({ QualitativeScoutingEntries.matchKey eq oldKey }) {
            it[QualitativeScoutingEntries.matchKey] = newKey
            it[QualitativeScoutingEntries.updatedAt] = java.time.Instant.now()
        }
        ScoutingAssignments.update({ ScoutingAssignments.matchKey eq oldKey }) {
            it[ScoutingAssignments.matchKey] = newKey
        }

        remapOwnerScoped(MatchPlans, MatchPlans.ownerKey, MatchPlans.eventKey, MatchPlans.matchKey, MatchPlans.updatedAt, oldKey, newKey, "Match plan")
        remapOwnerScoped(MatchChecklists, MatchChecklists.ownerKey, MatchChecklists.eventKey, MatchChecklists.matchKey, MatchChecklists.updatedAt, oldKey, newKey, "Match checklist")
        // Battery plans keep match keys inside their JSON.
        com.obsidianscout.scouting.BatteryTrackerService.remapMatchKey(oldKey, newKey)
        com.obsidianscout.scouting.MatchPredictionService.remapMatchKey(oldKey, newKey)
        TeamCacheOverrideService.remapMatchKey(oldKey, newKey)
    }

    /** Owner keys a team's plans, checklists and battery rows are stored under. */
    fun teamOwnerKeys(teamNumber: Int, program: String): List<String> =
        listOf("team_$teamNumber", com.obsidianscout.scouting.MatchChecklistService.ownerKey(teamNumber, program))

    /** [remapMatchKey] for one team's rows only, for a match key that changed for that team alone. */
    fun remapMatchKeyForTeam(oldKey: String, newKey: String, teamNumber: Int, program: String) {
        if (oldKey == newKey) {
            return
        }
        ScoutingEntries.update({ (ScoutingEntries.matchKey eq oldKey) and (ScoutingEntries.ownerTeamNumber eq teamNumber) and (ScoutingEntries.program eq program) }) {
            it[ScoutingEntries.matchKey] = newKey
            it[ScoutingEntries.updatedAt] = java.time.Instant.now()
        }
        QualitativeScoutingEntries.update({ (QualitativeScoutingEntries.matchKey eq oldKey) and (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and (QualitativeScoutingEntries.program eq program) }) {
            it[QualitativeScoutingEntries.matchKey] = newKey
            it[QualitativeScoutingEntries.updatedAt] = java.time.Instant.now()
        }
        ScoutingAssignments.update({ (ScoutingAssignments.matchKey eq oldKey) and (ScoutingAssignments.ownerTeamNumber eq teamNumber) and (ScoutingAssignments.program eq program) }) {
            it[ScoutingAssignments.matchKey] = newKey
        }
        val owners = teamOwnerKeys(teamNumber, program).toSet()
        remapOwnerScoped(MatchPlans, MatchPlans.ownerKey, MatchPlans.eventKey, MatchPlans.matchKey, MatchPlans.updatedAt, oldKey, newKey, "Match plan", owners)
        remapOwnerScoped(MatchChecklists, MatchChecklists.ownerKey, MatchChecklists.eventKey, MatchChecklists.matchKey, MatchChecklists.updatedAt, oldKey, newKey, "Match checklist", owners)
        com.obsidianscout.scouting.BatteryTrackerService.remapMatchKey(oldKey, newKey, owners)
        com.obsidianscout.scouting.MatchPredictionService.remapMatchKey(oldKey, newKey, teamNumber, program)
    }

    /** Rows unique per (owner, event, match): re-key to [newKey], keeping the newer row on a clash. */
    private fun remapOwnerScoped(
        table: org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable,
        owner: Column<String>,
        event: Column<String>,
        match: Column<String>,
        updatedAt: Column<java.time.Instant>,
        oldKey: String,
        newKey: String,
        label: String,
        owners: Set<String>? = null
    ) {
        table.selectAll().where { match eq oldKey }.toList().forEach { row ->
            val ownerKey = row[owner]
            if (owners != null && ownerKey !in owners) return@forEach
            val clash = table.selectAll().where {
                (owner eq ownerKey) and (event eq row[event]) and (match eq newKey)
            }.limit(1).firstOrNull()

            if (clash == null) {
                table.update({ table.id eq row[table.id] }) { it[match] = newKey }
            } else if (row[updatedAt] > clash[updatedAt]) {
                table.deleteWhere { table.id eq clash[table.id] }
                table.update({ table.id eq row[table.id] }) { it[match] = newKey }
                log.warn("$label conflict for $ownerKey on $newKey: kept newer one from $oldKey, dropped older one")
            } else {
                table.deleteWhere { table.id eq row[table.id] }
                log.warn("$label conflict for $ownerKey on $newKey: kept existing one, dropped older one from $oldKey")
            }
        }
    }

    /**
     * Reconnects rows left behind by older merges that only remapped match scouting entries.
     * Finds match keys in child tables with no ApiMatches row, works out the canonical key, and remaps
     * when that match exists. Safe to run repeatedly; returns how many keys were repaired.
     */
    fun repairOrphanedMatchKeys(): Int {
        // Canonicalize any stored rows still using the legacy manual key format first.
        val legacyEvents = transaction {
            ApiMatches.select(ApiMatches.matchKey, ApiMatches.eventKey).toList()
                .filter { LEGACY_MANUAL_KEY.matches(it[ApiMatches.matchKey]) }
                .map { it[ApiMatches.eventKey] }
                .distinct()
        }
        legacyEvents.forEach { deduplicateDatabaseForEvent(it) }

        return transaction {
            val knownKeys = ApiMatches.select(ApiMatches.matchKey).map { it[ApiMatches.matchKey] }.toHashSet()
            val referencedKeys = buildSet {
                ScoutingEntries.select(ScoutingEntries.matchKey).withDistinct()
                    .forEach { row -> row[ScoutingEntries.matchKey]?.let { add(it) } }
                QualitativeScoutingEntries.select(QualitativeScoutingEntries.matchKey).withDistinct()
                    .forEach { row -> row[QualitativeScoutingEntries.matchKey]?.let { add(it) } }
                ScoutingAssignments.select(ScoutingAssignments.matchKey).withDistinct()
                    .forEach { row -> row[ScoutingAssignments.matchKey]?.let { add(it) } }
                MatchPlans.select(MatchPlans.matchKey).withDistinct()
                    .forEach { row -> add(row[MatchPlans.matchKey]) }
                MatchChecklists.select(MatchChecklists.matchKey).withDistinct()
                    .forEach { row -> add(row[MatchChecklists.matchKey]) }
                addAll(com.obsidianscout.scouting.BatteryTrackerService.referencedMatchKeys())
                addAll(com.obsidianscout.scouting.MatchPredictionService.referencedMatchKeys())
            }

            var repaired = 0
            referencedKeys.filter { it !in knownKeys }.forEach { orphanKey ->
                val canonicalKey = parseLegacyManualKey(orphanKey)?.toMatchKey() ?: return@forEach
                if (canonicalKey in knownKeys) {
                    remapMatchKey(orphanKey, canonicalKey)
                    repaired++
                }
            }
            if (repaired > 0) {
                log.info("Repaired $repaired orphaned match key(s)")
            }
            repaired
        }
    }

    private val youtubeIdRegex = Regex("^[A-Za-z0-9_-]{6,32}$")
    private val youtubeStartRegex = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?$")

    /**
     * Reads the TBA `videos` array from a stored match JSON and returns playable links.
     * Only YouTube entries are returned; their IDs are validated so a bad key can't
     * produce an arbitrary URL.
     */
    fun extractVideos(json: kotlinx.serialization.json.JsonObject): List<com.obsidianscout.routes.MatchVideo> {
        val videos = json["videos"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return videos.mapNotNull { element ->
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.lowercase()
            val key = (obj["key"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
            if (type != "youtube" || key.isNullOrBlank()) return@mapNotNull null

            // TBA keys look like "abc123XYZ_-" or "abc123XYZ_-?t=1m30s" (sometimes "#t=").
            val id = key.substringBefore('?').substringBefore('#').substringBefore('&')
            if (!youtubeIdRegex.matches(id)) return@mapNotNull null
            val startParam = Regex("[?#&]t=([0-9hms]+)").find(key)?.groupValues?.get(1)
            val startSeconds = startParam?.let { youtubeStartRegex.matchEntire(it) }?.let { m ->
                val (h, mi, s) = m.destructured
                (h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
            }?.takeIf { it > 0 }
            val url = "https://www.youtube.com/watch?v=$id" + (startSeconds?.let { "&t=${it}s" } ?: "")
            com.obsidianscout.routes.MatchVideo(type = "youtube", url = url)
        }.distinctBy { it.url }
    }

    private fun carryVideos(chosenJson: String, otherJson: String): String {
        if (chosenJson.contains("\"videos\"") || !otherJson.contains("\"videos\"")) return chosenJson
        return try {
            val chosen = JsonSupport.json.parseToJsonElement(chosenJson) as? kotlinx.serialization.json.JsonObject
                ?: return chosenJson
            val other = JsonSupport.json.parseToJsonElement(otherJson) as? kotlinx.serialization.json.JsonObject
                ?: return chosenJson
            val videos = other["videos"] ?: return chosenJson
            JsonSupport.json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                kotlinx.serialization.json.JsonObject(chosen + ("videos" to videos))
            )
        } catch (_: Exception) {
            chosenJson
        }
    }

    /**
     * Whether a match's JSON carries a real result. TBA sends -1 for an unplayed alliance score, so the
     * value is checked, not just the field's presence. Covers TBA, FIRST (FRC and FTC) and FTC Scout.
     */
    fun hasScores(dataJson: String): Boolean {
        val obj = runCatching { JsonSupport.json.parseToJsonElement(dataJson) as? kotlinx.serialization.json.JsonObject }
            .getOrNull() ?: return false
        fun number(element: kotlinx.serialization.json.JsonElement?): Double? =
            (element as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        fun child(parent: kotlinx.serialization.json.JsonElement?, key: String) =
            (parent as? kotlinx.serialization.json.JsonObject)?.get(key)
        if (number(obj["scoreRedFinal"]) != null || number(obj["scoreBlueFinal"]) != null) return true
        if (number(obj["redScore"]) != null || number(obj["blueScore"]) != null) return true
        val alliances = obj["alliances"]
        val tbaRed = number(child(child(alliances, "red"), "score"))
        val tbaBlue = number(child(child(alliances, "blue"), "score"))
        if ((tbaRed != null && tbaRed >= 0) || (tbaBlue != null && tbaBlue >= 0)) return true
        val scores = obj["scores"]
        return number(child(child(scores, "red"), "totalPoints")) != null ||
            number(child(child(scores, "blue"), "totalPoints")) != null
    }

    private fun recordScore(record: MatchSyncRecord): Int {
        var score = 0
        if (hasScores(record.dataJson)) score += 10
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
