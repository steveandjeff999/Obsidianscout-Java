package com.obsidianscout.integrations

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.db.AllianceSelections
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.EpaOprHistoryCache
import com.obsidianscout.db.MatchChecklists
import com.obsidianscout.db.MatchPlans
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.SharedLinks
import com.obsidianscout.config.JsonSupport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Keeps manually added events and teams joined up with their API copies.
 *
 * Manual events keep the key the user typed (custom/off-season keys are allowed), so when an API sync
 * brings in the same event under its canonical key, [moveEventData] moves every dependent row across.
 * Teams are identified by event + team number; [deduplicateTeamsForEvent] folds manual rows into the
 * API row for the same team.
 */
object EventTeamCanonical {
    private val log = LoggerFactory.getLogger("EventTeamCanonical")

    // ── Teams ────────────────────────────────────────────────────────────────

    fun teamPrefix(program: String?): String = if (program.equals("FTC", ignoreCase = true)) "ftc" else "frc"

    /**
     * Normalizes a team key to `{frc|ftc}{number}`. An explicit frc/ftc prefix is kept; bare numbers
     * (and the `name/number` form some clients send) get the program's prefix.
     */
    fun canonicalTeamKey(raw: String?, program: String?, teamNumber: Int? = null): String {
        val trimmed = raw.orEmpty().trim().lowercase().replace(" ", "")
        val prefix = teamPrefix(program)
        return when {
            trimmed.isBlank() -> if (teamNumber != null) "$prefix$teamNumber" else ""
            trimmed.contains('/') -> {
                val tail = trimmed.substringAfterLast('/')
                canonicalTeamKey(tail, program, teamNumber)
            }
            trimmed.startsWith("frc") || trimmed.startsWith("ftc") -> trimmed
            trimmed.all { it.isDigit() } -> "$prefix$trimmed"
            else -> trimmed
        }
    }

    private fun teamFamily(teamKey: String): String = if (teamKey.lowercase().startsWith("ftc")) "ftc" else "frc"

    /** Rows created by the manual team form (API rows carry the provider's JSON). */
    private fun isManualTeam(row: ResultRow): Boolean = row[ApiTeams.dataJson].trim().let { it.isEmpty() || it == "{}" }

    /**
     * Merges rows for the same team number at an event, keeping the API row and filling its blank
     * fields from the manual one. A manual row with the other program's prefix (the old manual-save
     * default was always `frc`) joins in only when the event's API teams are all one program, so FRC
     * and FTC events that share an event key never get mixed. Must run inside a transaction.
     */
    fun deduplicateTeamsForEvent(eventKey: String): Int {
        // Only plain `{prefix}{number}` rows (or bare numbers) are the same team as another row with that
        // number; keys with a letter suffix (e.g. a manually typed B-team) are left alone.
        val rows = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }.toList().filter { row ->
            val digits = row[ApiTeams.teamKey].lowercase().removePrefix("frc").removePrefix("ftc")
            digits.isNotEmpty() && digits.all { it.isDigit() } && digits.toIntOrNull() == row[ApiTeams.teamNumber]
        }
        if (rows.size <= 1) return normalizeMatchTeamPrefixes(eventKey)

        val apiFamilies = rows.filterNot { isManualTeam(it) }.map { teamFamily(it[ApiTeams.teamKey]) }.toSet()
        fun effectiveFamily(row: ResultRow): String =
            if (isManualTeam(row) && apiFamilies.size == 1) apiFamilies.single() else teamFamily(row[ApiTeams.teamKey])

        var removed = 0
        rows.groupBy { it[ApiTeams.teamNumber] to effectiveFamily(it) }.forEach { (key, group) ->
            if (group.size <= 1) return@forEach
            val (teamNumber, family) = key
            val canonicalKey = "$family$teamNumber"
            val keeper = group.sortedWith(
                compareBy<ResultRow>({ isManualTeam(it) }, { it[ApiTeams.teamKey] != canonicalKey }, { it[ApiTeams.updatedAt] })
            ).first()
            val others = group.filter { it[ApiTeams.id] != keeper[ApiTeams.id] }

            others.forEach { dup -> ApiTeams.deleteWhere { ApiTeams.id eq dup[ApiTeams.id] } }
            removed += others.size
            // Only claim the canonical key if no surviving row already holds it (all others are gone now).
            if (keeper[ApiTeams.teamKey] != canonicalKey) {
                ApiTeams.update({ ApiTeams.id eq keeper[ApiTeams.id] }) { it[ApiTeams.teamKey] = canonicalKey }
            }
            fillBlankTeamFields(keeper, others)
        }
        removed += normalizeMatchTeamPrefixes(eventKey)
        if (removed > 0) {
            log.info("Merged $removed duplicate team row(s) for event $eventKey")
        }
        return removed
    }

    /** Copies non-blank values from [sources] into [keeper]'s blank fields; [keeper]'s own values win. */
    private fun fillBlankTeamFields(keeper: ResultRow, sources: List<ResultRow>) {
        val all = listOf(keeper) + sources
        fun text(column: Column<String?>): String? = all.firstNotNullOfOrNull { row -> row[column]?.takeIf { it.isNotBlank() } }
        fun number(column: Column<Double?>): Double? = all.firstNotNullOfOrNull { row -> row[column]?.takeIf { it != 0.0 } } ?: keeper[column]
        ApiTeams.update({ ApiTeams.id eq keeper[ApiTeams.id] }) {
            it[ApiTeams.name] = text(ApiTeams.name)
            it[ApiTeams.nickname] = text(ApiTeams.nickname)
            it[ApiTeams.city] = text(ApiTeams.city)
            it[ApiTeams.state] = text(ApiTeams.state)
            it[ApiTeams.country] = text(ApiTeams.country)
            it[ApiTeams.opr] = number(ApiTeams.opr)
            it[ApiTeams.epa] = number(ApiTeams.epa)
            it[ApiTeams.match13Exp] = number(ApiTeams.match13Exp)
        }
    }

    /**
     * In an event whose teams are all FTC, rewrites `frcN` entries in match lineups (left by the old
     * manual-save default) to `ftcN`. Returns 0; it fixes keys without removing rows.
     */
    private fun normalizeMatchTeamPrefixes(eventKey: String): Int {
        val teamKeys = ApiTeams.select(ApiTeams.teamKey).where { ApiTeams.eventKey eq eventKey }.map { it[ApiTeams.teamKey] }
        if (teamKeys.isEmpty() || !teamKeys.all { it.startsWith("ftc") }) return 0
        val serializer = ListSerializer(String.serializer())
        fun fix(json: String): String? {
            val teams = runCatching { JsonSupport.json.decodeFromString(serializer, json) }.getOrNull() ?: return null
            if (teams.none { it.startsWith("frc") }) return null
            return JsonSupport.json.encodeToString(serializer, teams.map { if (it.startsWith("frc")) "ftc" + it.removePrefix("frc") else it })
        }
        ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKey }.toList().forEach { row ->
            val red = fix(row[ApiMatches.redTeams])
            val blue = fix(row[ApiMatches.blueTeams])
            if (red != null || blue != null) {
                ApiMatches.update({ ApiMatches.id eq row[ApiMatches.id] }) {
                    if (red != null) it[ApiMatches.redTeams] = red
                    if (blue != null) it[ApiMatches.blueTeams] = blue
                }
            }
        }
        return 0
    }

    // ── Events ───────────────────────────────────────────────────────────────

    private fun isManualEvent(row: ResultRow): Boolean = row[ApiEvents.dataJson].contains("\"manual\":true")

    private fun canonicalCode(code: String?): String = canonicalTbaEventCode(code.orEmpty())

    /** True when a stored event row is the same event as [targetKey] (same year, same code). */
    private fun sameEvent(row: ResultRow, targetKey: String, targetYear: Int, targetCode: String?): Boolean {
        val rowKey = row[ApiEvents.eventKey]
        if (rowKey == targetKey) return false
        if (canonicalTbaEventKey(row[ApiEvents.year], rowKey) == targetKey) return true
        val rowCode = canonicalCode(row[ApiEvents.eventCode])
        val wanted = canonicalCode(targetCode?.takeIf { it.isNotBlank() } ?: targetKey.removePrefix(targetYear.toString()))
        return row[ApiEvents.year] == targetYear && rowCode.isNotBlank() && rowCode == wanted
    }

    /**
     * Called before an API event is written under [targetKey]: moves any manually created copy of the
     * same event onto [targetKey]. Must run inside a transaction.
     */
    fun adoptManualEvents(targetKey: String, year: Int, code: String?) {
        val key = targetKey.lowercase().trim()
        if (key.isBlank()) return
        ApiEvents.selectAll().where { ApiEvents.dataJson like "%\"manual\":true%" }.toList()
            .filter { isManualEvent(it) && sameEvent(it, key, year, code) }
            .forEach { manual ->
                log.info("Merging manual event ${manual[ApiEvents.eventKey]} into synced event $key")
                moveEventData(manual[ApiEvents.eventKey], key)
            }
    }

    /**
     * [adoptManualEvents] for a whole season's event list at once. It reads the manual events a single time
     * instead of scanning the events table once per synced event (about 1,500 times for an FTC season).
     * Each target is (synced key, year, code). Must run inside a transaction.
     */
    fun adoptManualEventsInto(targets: List<Triple<String, Int, String?>>) {
        if (targets.isEmpty()) return
        val manuals = ApiEvents.selectAll().where { ApiEvents.dataJson like "%\"manual\":true%" }.toList()
            .filter { isManualEvent(it) }
        if (manuals.isEmpty()) return
        manuals.forEach { manual ->
            val target = targets.firstOrNull { (key, year, code) -> sameEvent(manual, key.lowercase().trim(), year, code) }
                ?: return@forEach
            val key = target.first.lowercase().trim()
            log.info("Merging manual event ${manual[ApiEvents.eventKey]} into synced event $key")
            moveEventData(manual[ApiEvents.eventKey], key)
        }
    }

    /** An existing event row that is the same event as a manually entered key, if any. */
    fun findEquivalentEvent(typedKey: String, year: Int, code: String?): ResultRow? {
        val canonicalKey = canonicalTbaEventKey(year, typedKey)
        return ApiEvents.selectAll().where { ApiEvents.eventKey eq canonicalKey }.limit(1).firstOrNull()
            ?.takeIf { it[ApiEvents.eventKey] != typedKey }
            ?: ApiEvents.selectAll().toList().firstOrNull { row ->
                row[ApiEvents.eventKey] != typedKey && !isManualEvent(row) && sameEvent(row, canonicalKey, year, code)
            }
    }

    /**
     * Moves everything stored under event [oldKey] to [newKey], merging when [newKey] already has data.
     * Unique-per-event rows keep the newer copy on a clash; matches are re-keyed and merged by
     * [MatchCanonical.deduplicateDatabaseForEvent], which also carries every match-keyed row along.
     * Must run inside a transaction.
     */
    fun moveEventData(oldKey: String, newKey: String) {
        if (oldKey == newKey || newKey.isBlank()) return

        // Teams: unique per (event, teamKey).
        ApiTeams.selectAll().where { ApiTeams.eventKey eq oldKey }.toList().forEach { row ->
            val clash = ApiTeams.selectAll().where {
                (ApiTeams.eventKey eq newKey) and (ApiTeams.teamKey eq row[ApiTeams.teamKey])
            }.limit(1).firstOrNull()
            if (clash == null) {
                ApiTeams.update({ ApiTeams.id eq row[ApiTeams.id] }) { it[ApiTeams.eventKey] = newKey }
            } else {
                fillBlankTeamFields(clash, listOf(row))
                ApiTeams.deleteWhere { ApiTeams.id eq row[ApiTeams.id] }
            }
        }

        // Plain event references.
        ScoutingEntries.update({ ScoutingEntries.eventKey eq oldKey }) { it[ScoutingEntries.eventKey] = newKey; it[ScoutingEntries.updatedAt] = java.time.Instant.now() }
        PitScoutingEntries.update({ PitScoutingEntries.eventKey eq oldKey }) { it[PitScoutingEntries.eventKey] = newKey; it[PitScoutingEntries.updatedAt] = java.time.Instant.now() }
        QualitativeScoutingEntries.update({ QualitativeScoutingEntries.eventKey eq oldKey }) { it[QualitativeScoutingEntries.eventKey] = newKey; it[QualitativeScoutingEntries.updatedAt] = java.time.Instant.now() }
        ScoutingAssignments.update({ ScoutingAssignments.eventKey eq oldKey }) { it[ScoutingAssignments.eventKey] = newKey }
        ScoutingAlliances.update({ ScoutingAlliances.eventKey eq oldKey }) { it[ScoutingAlliances.eventKey] = newKey }
        SharedLinks.update({ SharedLinks.targetEventKey eq oldKey }) { it[SharedLinks.targetEventKey] = newKey }

        // Unique per (owner, event): keep the newer selection.
        AllianceSelections.selectAll().where { AllianceSelections.eventKey eq oldKey }.toList().forEach { row ->
            val clash = AllianceSelections.selectAll().where {
                (AllianceSelections.ownerKey eq row[AllianceSelections.ownerKey]) and (AllianceSelections.eventKey eq newKey)
            }.limit(1).firstOrNull()
            when {
                clash == null -> AllianceSelections.update({ AllianceSelections.id eq row[AllianceSelections.id] }) { it[AllianceSelections.eventKey] = newKey }
                row[AllianceSelections.updatedAt] > clash[AllianceSelections.updatedAt] -> {
                    AllianceSelections.deleteWhere { AllianceSelections.id eq clash[AllianceSelections.id] }
                    AllianceSelections.update({ AllianceSelections.id eq row[AllianceSelections.id] }) { it[AllianceSelections.eventKey] = newKey }
                }
                else -> AllianceSelections.deleteWhere { AllianceSelections.id eq row[AllianceSelections.id] }
            }
        }

        // API-derived cache, unique per event: the synced event's copy is authoritative.
        if (EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq newKey }.any()) {
            EpaOprHistoryCache.deleteWhere { EpaOprHistoryCache.eventKey eq oldKey }
        } else {
            EpaOprHistoryCache.update({ EpaOprHistoryCache.eventKey eq oldKey }) { it[EpaOprHistoryCache.eventKey] = newKey }
        }

        // Unique per (owner, event, match). Match keys still carry the old event prefix here, so the
        // real clashes surface when the match keys are remapped below (handled there).
        moveOwnerScoped(MatchPlans, MatchPlans.ownerKey, MatchPlans.eventKey, MatchPlans.matchKey, MatchPlans.updatedAt, oldKey, newKey)
        moveOwnerScoped(MatchChecklists, MatchChecklists.ownerKey, MatchChecklists.eventKey, MatchChecklists.matchKey, MatchChecklists.updatedAt, oldKey, newKey)
        com.obsidianscout.scouting.BatteryTrackerService.moveEvent(oldKey, newKey)

        // Matches: move, then let match dedup re-key to `{newKey}_…` and merge with any API copy.
        ApiMatches.update({ ApiMatches.eventKey eq oldKey }) { it[ApiMatches.eventKey] = newKey }
        MatchCanonical.deduplicateDatabaseForEvent(newKey)
        rekeyMovedMatches(oldKey, newKey)
        remapLeftoverMatchKeys(oldKey, newKey)

        deduplicateTeamsForEvent(newKey)

        // Event row last.
        val target = ApiEvents.selectAll().where { ApiEvents.eventKey eq newKey }.limit(1).firstOrNull()
        if (target == null) {
            ApiEvents.update({ ApiEvents.eventKey eq oldKey }) { it[ApiEvents.eventKey] = newKey }
        } else {
            ApiEvents.deleteWhere { ApiEvents.eventKey eq oldKey }
        }
    }

    /** Rows scoped to (owner, event, match): move to [newKey], keeping the newer row on a clash. */
    private fun moveOwnerScoped(
        table: org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable,
        owner: Column<String>,
        event: Column<String>,
        match: Column<String>,
        updatedAt: Column<java.time.Instant>,
        oldKey: String,
        newKey: String
    ) {
        table.selectAll().where { event eq oldKey }.toList().forEach { row ->
            val clash = table.selectAll().where {
                (owner eq row[owner]) and (event eq newKey) and (match eq row[match])
            }.limit(1).firstOrNull()
            when {
                clash == null -> table.update({ table.id eq row[table.id] }) { it[event] = newKey }
                row[updatedAt] > clash[updatedAt] -> {
                    table.deleteWhere { table.id eq clash[table.id] }
                    table.update({ table.id eq row[table.id] }) { it[event] = newKey }
                }
                else -> table.deleteWhere { table.id eq row[table.id] }
            }
        }
    }

    /** Moved matches whose key still starts with the old event key get `{newKey}_…` (merge already ran). */
    private fun rekeyMovedMatches(oldKey: String, newKey: String) {
        ApiMatches.selectAll().where { (ApiMatches.eventKey eq newKey) and (ApiMatches.matchKey like "${oldKey}_%") }.toList().forEach { row ->
            val current = row[ApiMatches.matchKey]
            val target = MatchCanonical.identityFrom(
                MatchSyncRecord(
                    matchKey = newKey + current.removePrefix(oldKey),
                    eventKey = newKey,
                    compLevel = row[ApiMatches.compLevel],
                    setNumber = row[ApiMatches.setNumber],
                    matchNumber = row[ApiMatches.matchNumber]
                )
            ).toMatchKey()
            if (target != current && ApiMatches.selectAll().where { ApiMatches.matchKey eq target }.none()) {
                ApiMatches.update({ ApiMatches.id eq row[ApiMatches.id] }) { it[ApiMatches.matchKey] = target }
                MatchCanonical.remapMatchKey(current, target)
            }
        }
    }

    /** Match keys in child tables that still use the old event prefix (no ApiMatches row carried them). */
    private fun remapLeftoverMatchKeys(oldKey: String, newKey: String) {
        val prefix = "${oldKey}_"
        val keys = buildSet {
            ScoutingEntries.select(ScoutingEntries.matchKey).where { ScoutingEntries.matchKey like "$prefix%" }
                .forEach { row -> row[ScoutingEntries.matchKey]?.let { add(it) } }
            QualitativeScoutingEntries.select(QualitativeScoutingEntries.matchKey).where { QualitativeScoutingEntries.matchKey like "$prefix%" }
                .forEach { row -> row[QualitativeScoutingEntries.matchKey]?.let { add(it) } }
            ScoutingAssignments.select(ScoutingAssignments.matchKey).where { ScoutingAssignments.matchKey like "$prefix%" }
                .forEach { row -> row[ScoutingAssignments.matchKey]?.let { add(it) } }
            MatchPlans.select(MatchPlans.matchKey).where { MatchPlans.matchKey like "$prefix%" }
                .forEach { row -> add(row[MatchPlans.matchKey]) }
            MatchChecklists.select(MatchChecklists.matchKey).where { MatchChecklists.matchKey like "$prefix%" }
                .forEach { row -> add(row[MatchChecklists.matchKey]) }
            addAll(com.obsidianscout.scouting.BatteryTrackerService.referencedMatchKeys(prefix))
        }
        keys.filter { it.startsWith(prefix) }.forEach { old ->
            val moved = newKey + old.removePrefix(oldKey)
            val canonical = (MatchCanonical.parseLegacyManualKey(moved) ?: MatchCanonical.parseTbaMatchKey(moved))?.toMatchKey() ?: moved
            MatchCanonical.remapMatchKey(old, canonical)
        }
    }

    /**
     * Startup repair: folds manual events into an equivalent synced event that already exists, and
     * merges duplicate team rows everywhere. Safe to run repeatedly; returns how many rows changed.
     */
    fun repairAll(): Int = transaction {
        var changed = 0
        val events = ApiEvents.selectAll().toList()
        events.filter { isManualEvent(it) }.forEach { manual ->
            val manualKey = manual[ApiEvents.eventKey]
            val twin = events.firstOrNull { other ->
                !isManualEvent(other) && sameEvent(manual, other[ApiEvents.eventKey], other[ApiEvents.year], other[ApiEvents.eventCode])
            }
            if (twin != null) {
                log.info("Repair: merging manual event $manualKey into ${twin[ApiEvents.eventKey]}")
                moveEventData(manualKey, twin[ApiEvents.eventKey])
                changed++
            }
        }
        ApiTeams.select(ApiTeams.eventKey).withDistinct().map { it[ApiTeams.eventKey] }.forEach { eventKey ->
            changed += deduplicateTeamsForEvent(eventKey)
        }
        changed
    }
}
