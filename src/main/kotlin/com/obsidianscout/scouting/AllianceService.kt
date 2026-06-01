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

// ────────────────────────────────────────
// Record types returned to callers / API
// ────────────────────────────────────────

@Serializable
data class AllianceMemberRecord(
    val teamNumber: Int,
    val status: String,
    val invitedAt: String,
    val respondedAt: String?
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
    val updatedAt: String
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

private const val STATUS_OWNER    = "OWNER"
private const val STATUS_INVITED  = "INVITED"
private const val STATUS_ACCEPTED = "ACCEPTED"
private const val STATUS_DECLINED = "DECLINED"

// ────────────────────────────────────────
// Service
// ────────────────────────────────────────

object AllianceService {

    /**
     * Returns all alliances where the calling team is OWNER or ACCEPTED member,
     * along with every membership row for each alliance.
     * SUPERADMIN sees all alliances.
     */
    fun listAlliances(session: UserSession): List<AllianceRecord> = transaction {
        if (session.role == UserRole.SUPERADMIN) {
            ScoutingAlliances.selectAll().map { row ->
                buildRecord(row[ScoutingAlliances.id].value)
            }.filterNotNull()
        } else {
            // find all alliance IDs this team belongs to (owner or accepted)
            val allianceIds = AllianceMemberships
                .select {
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status inList listOf(STATUS_OWNER, STATUS_ACCEPTED))
                }
                .map { it[AllianceMemberships.allianceId].value }

            allianceIds.mapNotNull { buildRecord(it) }
        }
    }

    /**
     * Returns alliances where the calling team has a pending INVITED status.
     */
    fun listInvites(session: UserSession): List<AllianceRecord> = transaction {
        val allianceIds = AllianceMemberships
            .select {
                (AllianceMemberships.teamNumber eq session.teamNumber) and
                (AllianceMemberships.status eq STATUS_INVITED)
            }
            .map { it[AllianceMemberships.allianceId].value }

        allianceIds.mapNotNull { buildRecord(it) }
    }

    /**
     * Returns the count of pending invites for a team — used for the sidebar badge.
     */
    fun getInviteCount(teamNumber: Int): Int = transaction {
        AllianceMemberships
            .select {
                (AllianceMemberships.teamNumber eq teamNumber) and
                (AllianceMemberships.status eq STATUS_INVITED)
            }
            .count().toInt()
    }

    /**
     * Creates a new alliance. The calling team becomes the OWNER.
     * Requires ADMIN or above.
     */
    fun createAlliance(
        session: UserSession,
        name: String,
        eventKey: String?,
        notes: String?
    ): AllianceRecord {
        if (!session.role.isAtLeast(UserRole.ADMIN)) {
            throw ApiException(HttpStatusCode.Forbidden, "Admin access required to create an alliance")
        }
        if (name.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Alliance name is required")

        return transaction {
            val now = Instant.now()
            val allianceId = ScoutingAlliances.insertAndGetId {
                it[ScoutingAlliances.name] = name.trim()
                it[ownerTeamNumber] = session.teamNumber
                it[ScoutingAlliances.eventKey] = eventKey?.trim()?.takeIf { v -> v.isNotBlank() }
                it[ScoutingAlliances.notes] = notes?.trim()?.takeIf { v -> v.isNotBlank() }
                it[createdAt] = now
                it[updatedAt] = now
            }
            AllianceMemberships.insertAndGetId {
                it[AllianceMemberships.allianceId] = allianceId
                it[teamNumber] = session.teamNumber
                it[status] = STATUS_OWNER
                it[invitedAt] = now
                it[respondedAt] = now
            }
            buildRecord(allianceId.value)!!
        }
    }

    /**
     * Updates an alliance's name, eventKey, and notes.
     * Only the OWNER (ADMIN+) may call this.
     */
    fun updateAlliance(
        session: UserSession,
        allianceId: Int,
        name: String,
        eventKey: String?,
        notes: String?
    ): AllianceRecord {
        requireOwner(session, allianceId)
        if (name.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Alliance name is required")

        return transaction {
            val now = Instant.now()
            ScoutingAlliances.update({ ScoutingAlliances.id eq allianceId }) {
                it[ScoutingAlliances.name] = name.trim()
                it[ScoutingAlliances.eventKey] = eventKey?.trim()?.takeIf { v -> v.isNotBlank() }
                it[ScoutingAlliances.notes] = notes?.trim()?.takeIf { v -> v.isNotBlank() }
                it[updatedAt] = now
            }
            buildRecord(allianceId)!!
        }
    }

    /**
     * Deletes an alliance and all its membership rows.
     * Only the OWNER (ADMIN+) may call this.
     */
    fun deleteAlliance(session: UserSession, allianceId: Int) {
        requireOwner(session, allianceId)
        transaction {
            AllianceMemberships.deleteWhere { AllianceMemberships.allianceId eq allianceId }
            ScoutingAlliances.deleteWhere { ScoutingAlliances.id eq allianceId }
        }
    }

    /**
     * Sends an invitation to a partner team to join the alliance.
     * Only the OWNER (ADMIN+) may invite.
     */
    fun inviteTeam(session: UserSession, allianceId: Int, partnerTeamNumber: Int) {
        requireOwner(session, allianceId)
        if (partnerTeamNumber <= 0) throw ApiException(HttpStatusCode.BadRequest, "Invalid team number")
        if (partnerTeamNumber == session.teamNumber) {
            throw ApiException(HttpStatusCode.BadRequest, "You cannot invite your own team")
        }

        transaction {
            // Check if already a member
            val existing = AllianceMemberships
                .select {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq partnerTeamNumber)
                }
                .firstOrNull()

            if (existing != null) {
                val currentStatus = existing[AllianceMemberships.status]
                if (currentStatus == STATUS_OWNER || currentStatus == STATUS_ACCEPTED) {
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
        }
    }

    /**
     * Accepts or declines an alliance invitation.
     * The calling team must have a pending INVITED membership.
     */
    fun respondToInvite(session: UserSession, allianceId: Int, accept: Boolean) {
        transaction {
            val row = AllianceMemberships
                .select {
                    (AllianceMemberships.allianceId eq allianceId) and
                    (AllianceMemberships.teamNumber eq session.teamNumber) and
                    (AllianceMemberships.status eq STATUS_INVITED)
                }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "No pending invite found for your team in this alliance")

            AllianceMemberships.update({
                (AllianceMemberships.allianceId eq allianceId) and
                (AllianceMemberships.teamNumber eq session.teamNumber)
            }) {
                it[status] = if (accept) STATUS_ACCEPTED else STATUS_DECLINED
                it[respondedAt] = Instant.now()
            }
        }
    }

    /**
     * Removes a member from an alliance.
     * The owner can remove any non-owner member.
     * A member can remove themselves (leave alliance).
     */
    fun removeMember(session: UserSession, allianceId: Int, targetTeamNumber: Int) {
        transaction {
            val alliance = ScoutingAlliances.select { ScoutingAlliances.id eq allianceId }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Alliance not found")

            val isOwner = alliance[ScoutingAlliances.ownerTeamNumber] == session.teamNumber
            val isSelf = targetTeamNumber == session.teamNumber

            if (!isOwner && !isSelf) {
                throw ApiException(HttpStatusCode.Forbidden, "Only the alliance owner can remove other members")
            }
            if (isOwner && targetTeamNumber == session.teamNumber) {
                throw ApiException(HttpStatusCode.BadRequest, "The owner cannot leave. Delete the alliance instead.")
            }

            AllianceMemberships.deleteWhere {
                (AllianceMemberships.allianceId eq allianceId) and
                (AllianceMemberships.teamNumber eq targetTeamNumber)
            }
        }
    }

    /**
     * Imports existing scouting data from a source team already stored on this server
     * into the caller's team dataset so the data is immediately shared with accepted
     * alliance partners.
     * Only the alliance owner may import.
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
        requireOwner(session, allianceId)
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
                val sourceRows = when {
                    importOnlyNullEvent -> ScoutingEntries
                        .select {
                            (ScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            ScoutingEntries.eventKey.isNull()
                        }
                    selectedEventKey == null -> {
                    ScoutingEntries
                        .select { ScoutingEntries.ownerTeamNumber eq sourceTeamNumber }
                    }
                    else -> {
                    ScoutingEntries
                        .select {
                            (ScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            (ScoutingEntries.eventKey eq selectedEventKey)
                        }
                    }
                }
                val existing = ScoutingEntries
                    .select { ScoutingEntries.ownerTeamNumber eq session.teamNumber }
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
                val sourceRows = when {
                    importOnlyNullEvent -> PitScoutingEntries
                        .select {
                            (PitScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            PitScoutingEntries.eventKey.isNull()
                        }
                    selectedEventKey == null -> {
                    PitScoutingEntries
                        .select { PitScoutingEntries.ownerTeamNumber eq sourceTeamNumber }
                    }
                    else -> {
                    PitScoutingEntries
                        .select {
                            (PitScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            (PitScoutingEntries.eventKey eq selectedEventKey)
                        }
                    }
                }
                val existing = PitScoutingEntries
                    .select { PitScoutingEntries.ownerTeamNumber eq session.teamNumber }
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
                val sourceRows = when {
                    importOnlyNullEvent -> QualitativeScoutingEntries
                        .select {
                            (QualitativeScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            QualitativeScoutingEntries.eventKey.isNull()
                        }
                    selectedEventKey == null -> {
                    QualitativeScoutingEntries
                        .select { QualitativeScoutingEntries.ownerTeamNumber eq sourceTeamNumber }
                    }
                    else -> {
                    QualitativeScoutingEntries
                        .select {
                            (QualitativeScoutingEntries.ownerTeamNumber eq sourceTeamNumber) and
                            (QualitativeScoutingEntries.eventKey eq selectedEventKey)
                        }
                    }
                }
                val existing = QualitativeScoutingEntries
                    .select { QualitativeScoutingEntries.ownerTeamNumber eq session.teamNumber }
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
     * Returns all team numbers that are ACCEPTED alliance partners of the given team.
     * Used by ScoutingService / PitScoutingService / QualitativeScoutingService
     * to transparently include partner data in list queries.
     */
    fun getAlliancePartnerTeams(teamNumber: Int): Set<Int> = transaction {
        // Find all alliance IDs where this team is OWNER or ACCEPTED
        val myAllianceIds = AllianceMemberships
            .select {
                (AllianceMemberships.teamNumber eq teamNumber) and
                (AllianceMemberships.status inList listOf(STATUS_OWNER, STATUS_ACCEPTED))
            }
            .map { it[AllianceMemberships.allianceId].value }

        if (myAllianceIds.isEmpty()) return@transaction emptySet()

        // Find all other ACCEPTED/OWNER members in those alliances
        AllianceMemberships
            .select {
                (AllianceMemberships.allianceId inList myAllianceIds) and
                (AllianceMemberships.teamNumber neq teamNumber) and
                (AllianceMemberships.status inList listOf(STATUS_OWNER, STATUS_ACCEPTED))
            }
            .map { it[AllianceMemberships.teamNumber] }
            .toSet()
    }

    // ────────────────────────────────────
    // Internal helpers
    // ────────────────────────────────────

    private fun requireOwner(session: UserSession, allianceId: Int) {
        if (!session.role.isAtLeast(UserRole.ADMIN)) {
            throw ApiException(HttpStatusCode.Forbidden, "Admin access required")
        }
        if (session.role == UserRole.SUPERADMIN) return
        transaction {
            val alliance = ScoutingAlliances
                .select { ScoutingAlliances.id eq allianceId }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Alliance not found")
            if (alliance[ScoutingAlliances.ownerTeamNumber] != session.teamNumber) {
                throw ApiException(HttpStatusCode.Forbidden, "Only the alliance owner can perform this action")
            }
        }
    }

    /**
     * Builds a full AllianceRecord including all membership rows.
     * Must be called inside a transaction.
     */
    private fun buildRecord(allianceId: Int): AllianceRecord? {
        val alliance = ScoutingAlliances
            .select { ScoutingAlliances.id eq allianceId }
            .firstOrNull() ?: return null

        val members = AllianceMemberships
            .select { AllianceMemberships.allianceId eq allianceId }
            .map { m ->
                AllianceMemberRecord(
                    teamNumber = m[AllianceMemberships.teamNumber],
                    status = m[AllianceMemberships.status],
                    invitedAt = m[AllianceMemberships.invitedAt].toString(),
                    respondedAt = m[AllianceMemberships.respondedAt]?.toString()
                )
            }

        return AllianceRecord(
            id = allianceId,
            name = alliance[ScoutingAlliances.name],
            ownerTeamNumber = alliance[ScoutingAlliances.ownerTeamNumber],
            eventKey = alliance[ScoutingAlliances.eventKey],
            notes = alliance[ScoutingAlliances.notes],
            members = members,
            createdAt = alliance[ScoutingAlliances.createdAt].toString(),
            updatedAt = alliance[ScoutingAlliances.updatedAt].toString()
        )
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
