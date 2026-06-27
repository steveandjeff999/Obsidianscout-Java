package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.AllianceMemberships
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.config.ConfigService
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// ────────────────────────────────────────
// Record types returned to callers / API
// ────────────────────────────────────────

@Serializable
data class AllianceMemberRecord(
    val teamNumber: Int,
    val status: String,
    val invitedAt: String,
    val respondedAt: String?,
    val disabled: Boolean = false,
    val active: Boolean = false
)

@Serializable
data class AllianceRecord(
    val id: Int,
    val name: String,
    val ownerTeamNumber: Int,
    val eventKey: String?,
    val notes: String?,
    val members: List<AllianceMemberRecord>,
    val createdAt: String,
    val updatedAt: String,
    val year: Int? = null,
    val eventCode: String? = null
)

@Serializable
data class AllianceImportResult(
    val importedMatchScouting: Int,
    val importedPitScouting: Int,
    val importedQualitativeScouting: Int,
    val sourceTeamNumber: Int,
    val eventKey: String?,
    val skippedDuplicates: Int
)

@Serializable
data class AllianceImportSourceRecord(
    val teamNumber: Int,
    val eventKey: String?,
    val matchScoutingCount: Int,
    val pitScoutingCount: Int,
    val qualitativeScoutingCount: Int
)

// ────────────────────────────────────────
// Membership status constants
// ────────────────────────────────────────

private const val STATUS_ADMIN    = "ADMIN"
private const val STATUS_INVITED  = "INVITED"
private const val STATUS_ACCEPTED = "ACCEPTED"
private const val STATUS_DECLINED = "DECLINED"

// ────────────────────────────────────────
// Service
// ────────────────────────────────────────

object AllianceService {

    private val effectiveSettingsCache = ConcurrentHashMap<Int, com.obsidianscout.integrations.ApiSettings>()

    fun clearEffectiveSettingsCache() {
        effectiveSettingsCache.clear()
    }

    fun evictEffectiveSettingsCache(teamNumber: Int) {
        effectiveSettingsCache.remove(teamNumber)
    }

    /**
     * Returns all alliances where the calling team is ADMIN or ACCEPTED member,
     * along with every membership row for each alliance.
     * SUPERADMIN sees all alliances.
     */
    fun listAlliances(session: UserSession): List<AllianceRecord> = transaction {
        if (session.role == UserRole.SUPERADMIN) {
            val ids = ScoutingAlliances
                .select(ScoutingAlliances.id)
                .map { it[ScoutingAlliances.id].value }
            buildRecords(ids)
        } else {
            // find all alliance IDs this team belongs to (admin or accepted)
            val allianceIds = AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status inList listOf(STATUS_ADMIN, STATUS_ACCEPTED))
                }
                .map { it[AllianceMemberships.allianceId].value }

            buildRecords(allianceIds)
        }
    }

    /**
     * Returns alliances where the calling team has a pending INVITED status.
     */
    fun listInvites(session: UserSession): List<AllianceRecord> = transaction {
        val allianceIds = AllianceMemberships
            .selectAll().where {
                (AllianceMemberships.teamNumber eq session.teamNumber) and
                (AllianceMemberships.status eq STATUS_INVITED)
            }
            .map { it[AllianceMemberships.allianceId].value }

        buildRecords(allianceIds)
    }

    /**
     * Returns the count of pending invites for a team — used for the sidebar badge.
     */
    fun getInviteCount(teamNumber: Int): Int = transaction {
        AllianceMemberships
            .selectAll().where {
                (AllianceMemberships.teamNumber eq teamNumber) and
                (AllianceMemberships.status eq STATUS_INVITED)
            }
            .count().toInt()
    }

    /**
     * Creates a new alliance. The calling team becomes the ADMIN.
     * Requires ADMIN or above.
     */
    fun createAlliance(
        session: UserSession,
        name: String,
        eventKey: String?,
        notes: String?,
        year: Int? = null,
        eventCode: String? = null
    ): AllianceRecord {
        if (!session.role.isAtLeast(UserRole.ADMIN)) {
            throw ApiException(HttpStatusCode.Forbidden, "Admin access required to create an alliance")
        }
        if (name.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Alliance name is required")

        return transaction {
            val now = Instant.now()
            
            // Get current configurations to initialize the alliance configs
            val creatorMatch = ConfigService.getConfigJson(session.teamNumber)
            val creatorPit = ConfigService.getPitConfigJson(session.teamNumber)
            val creatorQual = ConfigService.getQualitativeConfigJson(session.teamNumber)

            // Deactivate all other memberships for this team first
            AllianceMemberships.update({
                (AllianceMemberships.teamNumber eq session.teamNumber)
            }) {
                it[AllianceMemberships.active] = false
            }

            var finalYear = year
            var finalCode = eventCode?.trim()?.takeIf { it.isNotBlank() }
            var computedKey = eventKey?.trim()?.takeIf { it.isNotBlank() }

            if (finalYear != null && finalCode != null) {
                computedKey = "${finalYear}${finalCode.lowercase()}"
            } else if (computedKey != null && computedKey.length > 4 && computedKey.take(4).all { it.isDigit() }) {
                finalYear = computedKey.take(4).toIntOrNull()
                finalCode = computedKey.drop(4)
            }

            val allianceId = ScoutingAlliances.insertAndGetId {
                it[ScoutingAlliances.name] = name.trim()
                it[ownerTeamNumber] = session.teamNumber
                it[ScoutingAlliances.eventKey] = computedKey
                it[ScoutingAlliances.notes] = notes?.trim()?.takeIf { v -> v.isNotBlank() }
                it[ScoutingAlliances.year] = finalYear
                it[ScoutingAlliances.eventCode] = finalCode
                it[matchConfigJson] = creatorMatch
                it[pitConfigJson] = creatorPit
                it[qualitativeConfigJson] = creatorQual
                it[createdAt] = now
                it[updatedAt] = now
            }
            AllianceMemberships.insertAndGetId {
                it[AllianceMemberships.allianceId] = allianceId
                it[teamNumber] = session.teamNumber
                it[status] = STATUS_ADMIN
                it[invitedAt] = now
                it[respondedAt] = now
                it[active] = true
            }
            clearEffectiveSettingsCache()
            buildRecord(allianceId.value)!!
        }
    }

    /**
     * Updates an alliance's name, eventKey, and notes.
     * Only an alliance admin may call this.
     */
    fun updateAlliance(
        session: UserSession,
        allianceId: Int,
        name: String,
        eventKey: String?,
        notes: String?,
        year: Int? = null,
        eventCode: String? = null
    ): AllianceRecord {
        requireAdmin(session, allianceId)
        if (name.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Alliance name is required")

        return transaction {
            val now = Instant.now()
            var finalYear = year
            var finalCode = eventCode?.trim()?.takeIf { it.isNotBlank() }
            var computedKey = eventKey?.trim()?.takeIf { it.isNotBlank() }

            if (finalYear != null && finalCode != null) {
                computedKey = "${finalYear}${finalCode.lowercase()}"
            } else if (computedKey != null && computedKey.length > 4 && computedKey.take(4).all { it.isDigit() }) {
                finalYear = computedKey.take(4).toIntOrNull()
                finalCode = computedKey.drop(4)
            }

            ScoutingAlliances.update({ ScoutingAlliances.id eq allianceId }) {
                it[ScoutingAlliances.name] = name.trim()
                it[ScoutingAlliances.eventKey] = computedKey
                it[ScoutingAlliances.notes] = notes?.trim()?.takeIf { v -> v.isNotBlank() }
                it[ScoutingAlliances.year] = finalYear
                it[ScoutingAlliances.eventCode] = finalCode
                it[updatedAt] = now
            }
            clearEffectiveSettingsCache()
            buildRecord(allianceId)!!
        }
    }

    /**
     * Deletes an alliance and all its membership rows.
     * Only an alliance admin may call this.
     */
    fun deleteAlliance(session: UserSession, allianceId: Int) {
        requireAdmin(session, allianceId)
        transaction {
            AllianceMemberships.deleteWhere { AllianceMemberships.allianceId eq allianceId }
            ScoutingAlliances.deleteWhere { ScoutingAlliances.id eq allianceId }
            clearEffectiveSettingsCache()
        }
    }

    /**
     * Sends an invitation to a partner team to join the alliance.
     * Only an alliance admin may invite.
     */
    fun inviteTeam(session: UserSession, allianceId: Int, partnerTeamNumber: Int) {
        requireAdmin(session, allianceId)
        if (partnerTeamNumber <= 0) throw ApiException(HttpStatusCode.BadRequest, "Invalid team number")
        if (partnerTeamNumber == session.teamNumber) {
            throw ApiException(HttpStatusCode.BadRequest, "You cannot invite your own team")
        }

        transaction {
            // Check if already a member
            val existing = AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq partnerTeamNumber)
                }
                .firstOrNull()

            if (existing != null) {
                val currentStatus = existing[AllianceMemberships.status]
                if (currentStatus == STATUS_ADMIN || currentStatus == STATUS_ACCEPTED) {
                    throw ApiException(HttpStatusCode.Conflict, "Team $partnerTeamNumber is already a member")
                }
                if (currentStatus == STATUS_INVITED) {
                    throw ApiException(HttpStatusCode.Conflict, "Team $partnerTeamNumber already has a pending invite")
                }
                // Re-invite a declined team
                AllianceMemberships.update({
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq partnerTeamNumber)
                }) {
                    it[status] = STATUS_INVITED
                    it[invitedAt] = Instant.now()
                    it[respondedAt] = null
                }
            } else {
                val now = Instant.now()
                AllianceMemberships.insertAndGetId {
                    it[AllianceMemberships.allianceId] = EntityID(allianceId, ScoutingAlliances)
                    it[teamNumber] = partnerTeamNumber
                    it[status] = STATUS_INVITED
                    it[invitedAt] = now
                    it[respondedAt] = null
                }
            }
            clearEffectiveSettingsCache()
        }
    }

    /**
     * Accepts or declines an alliance invitation.
     * The calling team must have a pending INVITED membership.
     */
    fun respondToInvite(session: UserSession, allianceId: Int, accept: Boolean) {
        transaction {
            AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status eq STATUS_INVITED)
                }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "No pending invite found for your team in this alliance")

            if (accept) {
                val hasAnyActive = AllianceMemberships.selectAll().where {
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status inList listOf(STATUS_ADMIN, STATUS_ACCEPTED)) and
                    (AllianceMemberships.active eq true)
                }.any()

                AllianceMemberships.update({
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq session.teamNumber)
                }) {
                    it[status] = STATUS_ACCEPTED
                    it[respondedAt] = Instant.now()
                    it[active] = !hasAnyActive
                }
            } else {
                AllianceMemberships.update({
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq session.teamNumber)
                }) {
                    it[status] = STATUS_DECLINED
                    it[respondedAt] = Instant.now()
                }
            }
            clearEffectiveSettingsCache()
        }
    }

    /**
     * Removes a member from an alliance.
     * An admin can remove any member.
     * A member can remove themselves (leave alliance).
     */
    fun removeMember(session: UserSession, allianceId: Int, targetTeamNumber: Int) {
        transaction {
            ScoutingAlliances.selectAll().where { ScoutingAlliances.id eq allianceId }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Alliance not found")

            // Check if caller is admin in the alliance
            val isCallerAdmin = AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status eq STATUS_ADMIN)
                }
                .any()
            val isSelf = targetTeamNumber == session.teamNumber

            if (!isCallerAdmin && !isSelf) {
                throw ApiException(HttpStatusCode.Forbidden, "Only alliance admins can remove other members")
            }

            // Check target status
            val targetMembership = AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq targetTeamNumber)
                }
                .firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "Member not found")

            val isTargetAdmin = targetMembership[AllianceMemberships.status] == STATUS_ADMIN
            if (isTargetAdmin) {
                val adminCount = AllianceMemberships
                    .selectAll().where {
                        (AllianceMemberships.allianceId eq allianceId) and
                        (AllianceMemberships.status eq STATUS_ADMIN)
                    }
                    .count().toInt()
                if (adminCount <= 1) {
                    throw ApiException(HttpStatusCode.BadRequest, "The last admin cannot leave or be removed. Delete the alliance instead.")
                }
            }

            AllianceMemberships.deleteWhere {
                (AllianceMemberships.allianceId eq allianceId) and
                (AllianceMemberships.teamNumber eq targetTeamNumber)
            }
            clearEffectiveSettingsCache()
        }
    }

    /**
     * Imports existing scouting data from a source team already stored on this server
     * into the caller's team dataset so the data is immediately shared with accepted
     * alliance partners.
     * Only an alliance admin may import.
     */
    fun importAllianceData(
        session: UserSession,
        allianceId: Int,
        sourceTeamNumber: Int,
        eventKey: String?,
        includeMatchScouting: Boolean,
        includePitScouting: Boolean,
        includeQualitativeScouting: Boolean
    ): AllianceImportResult {
        requireAdmin(session, allianceId)
        if (sourceTeamNumber <= 0) {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid source team number")
        }
        if (!includeMatchScouting && !includePitScouting && !includeQualitativeScouting) {
            throw ApiException(HttpStatusCode.BadRequest, "Select at least one scouting data type to import")
        }

        val normalizedEventKey = eventKey?.trim()?.takeIf { it.isNotBlank() }
        val importOnlyNullEvent = normalizedEventKey == "__NO_EVENT__"
        val selectedEventKey = normalizedEventKey?.takeUnless { it == "__NO_EVENT__" }
        val now = Instant.now()

        return transaction {
            var skippedDuplicates = 0

            val importedMatch = if (includeMatchScouting) {
                val sourceRows = (when {
                    importOnlyNullEvent -> ScoutingEntries
                        .selectAll().where {
                            (ScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            ScoutingEntries.eventKey.isNull()
                        }
                    selectedEventKey == null -> {
                    ScoutingEntries
                        .selectAll().where { ScoutingEntries.ownerTeamNumber eq sourceTeamNumber }
                    }
                    else -> {
                    ScoutingEntries
                        .selectAll().where {
                            (ScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            (ScoutingEntries.eventKey eq selectedEventKey)
                        }
                    }
                }).toList()
                val existing = ScoutingEntries
                    .selectAll().where { ScoutingEntries.ownerTeamNumber eq session.teamNumber }
                    .map { row ->
                        scoutingFingerprint(
                            row[ScoutingEntries.targetTeamNumber],
                            row[ScoutingEntries.eventKey],
                            row[ScoutingEntries.matchKey],
                            row[ScoutingEntries.matchNumber],
                            row[ScoutingEntries.dataJson]
                        )
                    }
                    .toMutableSet()

                var imported = 0
                sourceRows.forEach { row ->
                    val destinationEventKey = selectedEventKey ?: row[ScoutingEntries.eventKey]
                    val fingerprint = scoutingFingerprint(
                        row[ScoutingEntries.targetTeamNumber],
                        destinationEventKey,
                        row[ScoutingEntries.matchKey],
                        row[ScoutingEntries.matchNumber],
                        row[ScoutingEntries.dataJson]
                    )
                    if (existing.add(fingerprint)) {
                        ScoutingEntries.insertAndGetId {
                            it[ownerTeamNumber] = session.teamNumber
                            it[targetTeamNumber] = row[ScoutingEntries.targetTeamNumber]
                            it[ScoutingEntries.eventKey] = destinationEventKey
                            it[ScoutingEntries.matchKey] = row[ScoutingEntries.matchKey]
                            it[ScoutingEntries.matchNumber] = row[ScoutingEntries.matchNumber]
                            it[dataJson] = row[ScoutingEntries.dataJson]
                            it[submittedByUserId] = EntityID(session.userId, Users)
                            it[createdAt] = now
                        }
                        imported++
                    } else {
                        skippedDuplicates++
                    }
                }
                imported
            } else {
                0
            }

            val importedPit = if (includePitScouting) {
                val sourceRows = (when {
                    importOnlyNullEvent -> PitScoutingEntries
                        .selectAll().where {
                            (PitScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            PitScoutingEntries.eventKey.isNull()
                        }
                    selectedEventKey == null -> {
                    PitScoutingEntries
                        .selectAll().where { PitScoutingEntries.ownerTeamNumber eq sourceTeamNumber }
                    }
                    else -> {
                    PitScoutingEntries
                        .selectAll().where {
                            (PitScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            (PitScoutingEntries.eventKey eq selectedEventKey)
                        }
                    }
                }).toList()
                val existing = PitScoutingEntries
                    .selectAll().where { PitScoutingEntries.ownerTeamNumber eq session.teamNumber }
                    .map { row ->
                        pitFingerprint(
                            row[PitScoutingEntries.targetTeamNumber],
                            row[PitScoutingEntries.eventKey],
                            row[PitScoutingEntries.dataJson]
                        )
                    }
                    .toMutableSet()

                var imported = 0
                sourceRows.forEach { row ->
                    val destinationEventKey = selectedEventKey ?: row[PitScoutingEntries.eventKey]
                    val fingerprint = pitFingerprint(
                        row[PitScoutingEntries.targetTeamNumber],
                        destinationEventKey,
                        row[PitScoutingEntries.dataJson]
                    )
                    if (existing.add(fingerprint)) {
                        PitScoutingEntries.insertAndGetId {
                            it[ownerTeamNumber] = session.teamNumber
                            it[targetTeamNumber] = row[PitScoutingEntries.targetTeamNumber]
                            it[PitScoutingEntries.eventKey] = destinationEventKey
                            it[dataJson] = row[PitScoutingEntries.dataJson]
                            it[submittedByUserId] = EntityID(session.userId, Users)
                            it[createdAt] = now
                        }
                        imported++
                    } else {
                        skippedDuplicates++
                    }
                }
                imported
            } else {
                0
            }

            val importedQualitative = if (includeQualitativeScouting) {
                val sourceRows = (when {
                    importOnlyNullEvent -> QualitativeScoutingEntries
                        .selectAll().where {
                            (QualitativeScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            QualitativeScoutingEntries.eventKey.isNull()
                        }
                    selectedEventKey == null -> {
                    QualitativeScoutingEntries
                        .selectAll().where { QualitativeScoutingEntries.ownerTeamNumber eq sourceTeamNumber }
                    }
                    else -> {
                    QualitativeScoutingEntries
                        .selectAll().where {
                            (QualitativeScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            (QualitativeScoutingEntries.eventKey eq selectedEventKey)
                        }
                    }
                }).toList()
                val existing = QualitativeScoutingEntries
                    .selectAll().where { QualitativeScoutingEntries.ownerTeamNumber eq session.teamNumber }
                    .map { row ->
                        scoutingFingerprint(
                            row[QualitativeScoutingEntries.targetTeamNumber],
                            row[QualitativeScoutingEntries.eventKey],
                            row[QualitativeScoutingEntries.matchKey],
                            row[QualitativeScoutingEntries.matchNumber],
                            row[QualitativeScoutingEntries.dataJson]
                        )
                    }
                    .toMutableSet()

                var imported = 0
                sourceRows.forEach { row ->
                    val destinationEventKey = selectedEventKey ?: row[QualitativeScoutingEntries.eventKey]
                    val fingerprint = scoutingFingerprint(
                        row[QualitativeScoutingEntries.targetTeamNumber],
                        destinationEventKey,
                        row[QualitativeScoutingEntries.matchKey],
                        row[QualitativeScoutingEntries.matchNumber],
                        row[QualitativeScoutingEntries.dataJson]
                    )
                    if (existing.add(fingerprint)) {
                        QualitativeScoutingEntries.insertAndGetId {
                            it[ownerTeamNumber] = session.teamNumber
                            it[targetTeamNumber] = row[QualitativeScoutingEntries.targetTeamNumber]
                            it[QualitativeScoutingEntries.eventKey] = destinationEventKey
                            it[QualitativeScoutingEntries.matchKey] = row[QualitativeScoutingEntries.matchKey]
                            it[QualitativeScoutingEntries.matchNumber] = row[QualitativeScoutingEntries.matchNumber]
                            it[dataJson] = row[QualitativeScoutingEntries.dataJson]
                            it[submittedByUserId] = EntityID(session.userId, Users)
                            it[createdAt] = now
                        }
                        imported++
                    } else {
                        skippedDuplicates++
                    }
                }
                imported
            } else {
                0
            }

            AllianceImportResult(
                importedMatchScouting = importedMatch,
                importedPitScouting = importedPit,
                importedQualitativeScouting = importedQualitative,
                sourceTeamNumber = sourceTeamNumber,
                eventKey = selectedEventKey,
                skippedDuplicates = skippedDuplicates
            )
        }
    }

    fun listImportSources(session: UserSession): List<AllianceImportSourceRecord> {
        if (!session.role.isAtLeast(UserRole.ADMIN)) {
            throw ApiException(HttpStatusCode.Forbidden, "Admin access required")
        }

        return transaction {
            val sources = mutableMapOf<Pair<Int, String?>, MutableImportSourceCounts>()

            ScoutingEntries.selectAll().forEach { row ->
                sources.getOrPut(row[ScoutingEntries.ownerTeamNumber] to row[ScoutingEntries.eventKey]) {
                    MutableImportSourceCounts()
                }.matchScoutingCount++
            }
            PitScoutingEntries.selectAll().forEach { row ->
                sources.getOrPut(row[PitScoutingEntries.ownerTeamNumber] to row[PitScoutingEntries.eventKey]) {
                    MutableImportSourceCounts()
                }.pitScoutingCount++
            }
            QualitativeScoutingEntries.selectAll().forEach { row ->
                sources.getOrPut(row[QualitativeScoutingEntries.ownerTeamNumber] to row[QualitativeScoutingEntries.eventKey]) {
                    MutableImportSourceCounts()
                }.qualitativeScoutingCount++
            }

            sources.map { (key, counts) ->
                AllianceImportSourceRecord(
                    teamNumber = key.first,
                    eventKey = key.second,
                    matchScoutingCount = counts.matchScoutingCount,
                    pitScoutingCount = counts.pitScoutingCount,
                    qualitativeScoutingCount = counts.qualitativeScoutingCount
                )
            }.sortedWith(compareBy<AllianceImportSourceRecord> { it.teamNumber }.thenBy { it.eventKey ?: "" })
        }
    }

    /**
     * Returns all team numbers that are ACCEPTED/ADMIN alliance partners of the given team.
     * Used by ScoutingService / PitScoutingService / QualitativeScoutingService
     * to transparently include partner data in list queries.
     */
    fun getAlliancePartnerTeams(teamNumber: Int): Set<Int> = transaction {
        // Find all alliance IDs where this team is ADMIN or ACCEPTED and active
        val myAllianceIds = AllianceMemberships
            .selectAll().where {
                (AllianceMemberships.teamNumber eq teamNumber) and
                (AllianceMemberships.status inList listOf(STATUS_ADMIN, STATUS_ACCEPTED)) and
                (AllianceMemberships.active eq true)
            }
            .map { it[AllianceMemberships.allianceId].value }

        if (myAllianceIds.isEmpty()) return@transaction emptySet()

        // Find all other ACCEPTED/ADMIN members in those alliances who are also active
        AllianceMemberships
            .selectAll().where {
                (AllianceMemberships.allianceId inList myAllianceIds) and
                (AllianceMemberships.teamNumber neq teamNumber) and
                (AllianceMemberships.status inList listOf(STATUS_ADMIN, STATUS_ACCEPTED)) and
                (AllianceMemberships.active eq true)
            }
            .map { it[AllianceMemberships.teamNumber] }
            .toSet()
    }

    /**
     * Checks if a team has an active (ACCEPTED or ADMIN) alliance membership
     * and returns the alliance ID.
     */
    fun getActiveAllianceId(teamNumber: Int): Int? = transaction {
        AllianceMemberships
            .selectAll().where {
                (AllianceMemberships.teamNumber eq teamNumber) and
                (AllianceMemberships.status inList listOf(STATUS_ADMIN, STATUS_ACCEPTED)) and
                (AllianceMemberships.active eq true)
            }
            .firstOrNull()
            ?.get(AllianceMemberships.allianceId)
            ?.value
    }

    /**
     * Checks if the team is an ADMIN in the alliance.
     */
    fun isAllianceAdmin(teamNumber: Int, allianceId: Int): Boolean = transaction {
        AllianceMemberships
            .selectAll().where {
                (AllianceMemberships.allianceId eq allianceId) and
                (AllianceMemberships.teamNumber eq teamNumber) and
                (AllianceMemberships.status eq STATUS_ADMIN)
            }
            .any()
    }

    /**
     * Promotes an ACCEPTED member to ADMIN.
     */
    fun promoteMember(session: UserSession, allianceId: Int, targetTeamNumber: Int) {
        requireAdmin(session, allianceId)
        transaction {
            val membership = AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq targetTeamNumber)
                }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Member not found")
            
            val currentStatus = membership[AllianceMemberships.status]
            if (currentStatus != STATUS_ACCEPTED) {
                throw ApiException(HttpStatusCode.BadRequest, "Only accepted members can be promoted to admin")
            }
            
            AllianceMemberships.update({
                (AllianceMemberships.allianceId eq allianceId) and
                (AllianceMemberships.teamNumber eq targetTeamNumber)
            }) {
                it[status] = STATUS_ADMIN
            }
            clearEffectiveSettingsCache()
        }
    }

    /**
     * Gets a single alliance by ID, checking membership.
     */
    fun getAlliance(session: UserSession, allianceId: Int): AllianceRecord {
        return transaction {
            val isMember = AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status inList listOf(STATUS_ADMIN, STATUS_ACCEPTED))
                }
                .any()
            if (!isMember && session.role != UserRole.SUPERADMIN) {
                throw ApiException(HttpStatusCode.Forbidden, "You are not a member of this alliance")
            }

            buildRecord(allianceId) ?: throw ApiException(HttpStatusCode.NotFound, "Alliance not found")
        }
    }

    /**
     * Toggles the active status for a team's membership in an alliance, deactivating others.
     */
    fun toggleActiveMembership(session: UserSession, allianceId: Int, active: Boolean) {
        transaction {
            AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status inList listOf(STATUS_ADMIN, STATUS_ACCEPTED))
                }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "No accepted membership found for your team in this alliance")

            if (active) {
                // Deactivate all other memberships for this team first
                AllianceMemberships.update({
                    (AllianceMemberships.teamNumber eq session.teamNumber)
                }) {
                    it[AllianceMemberships.active] = false
                }
            }

            AllianceMemberships.update({
                (AllianceMemberships.allianceId eq allianceId) and
                (AllianceMemberships.teamNumber eq session.teamNumber)
            }) {
                it[AllianceMemberships.active] = active
            }
            clearEffectiveSettingsCache()
        }
    }

    // ────────────────────────────────────
    // Internal helpers
    // ────────────────────────────────────

    private fun requireAdmin(session: UserSession, allianceId: Int) {
        if (!session.role.isAtLeast(UserRole.ADMIN)) {
            throw ApiException(HttpStatusCode.Forbidden, "Admin access required")
        }
        if (session.role == UserRole.SUPERADMIN) return
        transaction {
            val isAdmin = AllianceMemberships
                .selectAll().where {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status eq STATUS_ADMIN)
                }
                .any()
            if (!isAdmin) {
                throw ApiException(HttpStatusCode.Forbidden, "Only alliance admins can perform this action")
            }
        }
    }

    /**
     * Builds a list of AllianceRecord including all membership rows in exactly 2 queries.
     * Slices out the large config JSON columns to avoid performance slowdown.
     * Must be called inside a transaction.
     */
    private fun buildRecords(allianceIds: List<Int>): List<AllianceRecord> {
        if (allianceIds.isEmpty()) return emptyList()

        val alliancesMap = ScoutingAlliances
            .select(
                ScoutingAlliances.id,
                ScoutingAlliances.name,
                ScoutingAlliances.ownerTeamNumber,
                ScoutingAlliances.eventKey,
                ScoutingAlliances.notes,
                ScoutingAlliances.createdAt,
                ScoutingAlliances.updatedAt,
                ScoutingAlliances.year,
                ScoutingAlliances.eventCode
            )
            .where { ScoutingAlliances.id inList allianceIds }
            .associateBy { it[ScoutingAlliances.id].value }

        val membershipsMap = AllianceMemberships
            .selectAll().where { AllianceMemberships.allianceId inList allianceIds }
            .groupBy { it[AllianceMemberships.allianceId].value }

        return allianceIds.mapNotNull { id ->
            val alliance = alliancesMap[id] ?: return@mapNotNull null
            val members = (membershipsMap[id] ?: emptyList()).map { m ->
                AllianceMemberRecord(
                    teamNumber = m[AllianceMemberships.teamNumber],
                    status = m[AllianceMemberships.status],
                    invitedAt = m[AllianceMemberships.invitedAt].toString(),
                    respondedAt = m[AllianceMemberships.respondedAt]?.toString(),
                    disabled = m[AllianceMemberships.disabled],
                    active = m[AllianceMemberships.active]
                )
            }
            AllianceRecord(
                id = id,
                name = alliance[ScoutingAlliances.name],
                ownerTeamNumber = alliance[ScoutingAlliances.ownerTeamNumber],
                eventKey = alliance[ScoutingAlliances.eventKey],
                notes = alliance[ScoutingAlliances.notes],
                members = members,
                createdAt = alliance[ScoutingAlliances.createdAt].toString(),
                updatedAt = alliance[ScoutingAlliances.updatedAt].toString(),
                year = alliance[ScoutingAlliances.year],
                eventCode = alliance[ScoutingAlliances.eventCode]
            )
        }
    }

    /**
     * Builds a full AllianceRecord including all membership rows.
     * Must be called inside a transaction.
     */
    private fun buildRecord(allianceId: Int): AllianceRecord? {
        return buildRecords(listOf(allianceId)).firstOrNull()
    }

    fun getEffectiveSettings(teamNumber: Int): com.obsidianscout.integrations.ApiSettings {
        return effectiveSettingsCache.computeIfAbsent(teamNumber) {
            transaction {
                val localSettings = com.obsidianscout.integrations.SettingsService.getSettings(teamNumber)
                val activeAllianceId = getActiveAllianceId(teamNumber) ?: return@transaction localSettings

                val allianceRow = ScoutingAlliances
                    .select(ScoutingAlliances.year, ScoutingAlliances.eventCode, ScoutingAlliances.eventKey)
                    .where { ScoutingAlliances.id eq activeAllianceId }
                    .firstOrNull() ?: return@transaction localSettings

                val allianceYear = allianceRow[ScoutingAlliances.year]
                val allianceEventCode = allianceRow[ScoutingAlliances.eventCode]
                val allianceEventKey = allianceRow[ScoutingAlliances.eventKey]

                val (effYear, effCode, effKey) = when {
                    allianceYear != null && !allianceEventCode.isNullOrBlank() -> {
                        Triple(allianceYear, allianceEventCode, "${allianceYear}${allianceEventCode.trim().lowercase()}")
                    }
                    !allianceEventKey.isNullOrBlank() -> {
                        val parsedYear = allianceEventKey.take(4).toIntOrNull() ?: localSettings.year
                        val parsedCode = if (allianceEventKey.length > 4) allianceEventKey.drop(4) else ""
                        Triple(parsedYear, parsedCode, allianceEventKey.trim().lowercase())
                    }
                    else -> {
                        Triple(localSettings.year, localSettings.eventCode, localSettings.eventKey)
                    }
                }

                // Active member team numbers (including ourselves)
                val memberTeamNumbers = AllianceMemberships
                    .selectAll().where {
                        (AllianceMemberships.allianceId eq activeAllianceId) and
                        (AllianceMemberships.status inList listOf(STATUS_ADMIN, STATUS_ACCEPTED)) and
                        (AllianceMemberships.active eq true)
                    }
                    .map { it[AllianceMemberships.teamNumber] }

                var tbaKey = localSettings.apiKeys.tbaKey
                var firstUsername = localSettings.apiKeys.firstUsername
                var firstKey = localSettings.apiKeys.firstKey

                if (tbaKey.isBlank() || firstUsername.isBlank() || firstKey.isBlank()) {
                    for (memberTeam in memberTeamNumbers) {
                        if (memberTeam == teamNumber) continue
                        val memberSettings = com.obsidianscout.integrations.SettingsService.getSettings(memberTeam)
                        if (tbaKey.isBlank() && memberSettings.apiKeys.tbaKey.isNotBlank()) {
                            tbaKey = memberSettings.apiKeys.tbaKey
                        }
                        if ((firstUsername.isBlank() || firstKey.isBlank()) &&
                            memberSettings.apiKeys.firstUsername.isNotBlank() &&
                            memberSettings.apiKeys.firstKey.isNotBlank()) {
                            firstUsername = memberSettings.apiKeys.firstUsername
                            firstKey = memberSettings.apiKeys.firstKey
                        }
                    }
                }

                localSettings.copy(
                    year = effYear,
                    eventCode = effCode,
                    eventKey = effKey,
                    apiKeys = localSettings.apiKeys.copy(
                        tbaKey = tbaKey,
                        firstUsername = firstUsername,
                        firstKey = firstKey
                    )
                )
            }
        }
    }

}

private data class MutableImportSourceCounts(
    var matchScoutingCount: Int = 0,
    var pitScoutingCount: Int = 0,
    var qualitativeScoutingCount: Int = 0
)

private fun scoutingFingerprint(
    targetTeamNumber: Int?,
    eventKey: String?,
    matchKey: String?,
    matchNumber: Int?,
    dataJson: String
): String = listOf(targetTeamNumber, eventKey, matchKey, matchNumber, dataJson).joinToString("\u001F") { it?.toString() ?: "" }

private fun pitFingerprint(
    targetTeamNumber: Int?,
    eventKey: String?,
    dataJson: String
): String = listOf(targetTeamNumber, eventKey, dataJson).joinToString("\u001F") { it?.toString() ?: "" }
