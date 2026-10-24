package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.AllianceSelections
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

@Serializable
data class AllianceSelectionResponse(
    val selectionJson: String,
    val updatedAt: Long
)

@Serializable
data class AllianceSelectionUpdateRequest(
    val eventKey: String,
    val selectionJson: String
)

@Serializable
data class AllianceSelectionUpdateResponse(
    val updatedAt: Long
)

/**
 * A team's private Want / Avoid / Do Not Pick lists for one event.
 * Each team number appears in at most one list. Order within each list is the team's priority.
 */
@Serializable
data class PickLists(
    val want: List<Int> = emptyList(),
    val avoid: List<Int> = emptyList(),
    val dnp: List<Int> = emptyList(),
    /** Optional short reason per team, keyed by team number. */
    val notes: Map<String, String> = emptyMap()
)

@Serializable
data class PickListsResponse(
    val pickLists: PickLists,
    val updatedAt: Long
)

@Serializable
data class PickListsUpdateRequest(
    val eventKey: String,
    val pickLists: PickLists
)

/** A team's ranked pick list for one event, best pick first. */
@Serializable
data class RankedPickList(
    val ranking: List<Int> = emptyList()
)

/**
 * The ranked pick list as clients see it: [ranking] never contains a team on the
 * Do Not Pick list from Alliance Selection, which is returned alongside as [dnp].
 */
@Serializable
data class RankedPickListResponse(
    val ranking: List<Int>,
    val dnp: List<Int>,
    val notes: Map<String, String>,
    /** Latest change to either the ranking or the pick lists, for polling. */
    val updatedAt: Long
)

@Serializable
data class RankedPickListUpdateRequest(
    val eventKey: String,
    val ranking: List<Int>
)

@Serializable
data class DoNotPickUpdateRequest(
    val eventKey: String,
    val teamNumber: Int,
    val dnp: Boolean
)

object AllianceSelectionService {

    private fun resolveOwnerKey(session: UserSession): String {
        val allianceId = AllianceService.getActiveAllianceId(session.teamNumber, session.program)
        return if (allianceId != null) {
            "alliance_$allianceId"
        } else {
            "team_${session.teamNumber}_${session.program}"
        }
    }

    /**
     * Pick lists are stored per team, never per scouting alliance: a team's
     * do-not-pick list can name its own alliance partners.
     */
    fun pickListOwnerKey(teamNumber: Int, program: String): String = "picklist_team_${teamNumber}_$program"

    private const val MAX_LIST_SIZE = 200
    private const val MAX_NOTE_LENGTH = 200

    /** Dedupes, drops invalid team numbers, keeps each team in only its first list, and trims notes. */
    fun sanitizePickLists(input: PickLists): PickLists {
        val seen = mutableSetOf<Int>()
        fun clean(list: List<Int>) = list.filter { it > 0 && seen.add(it) }.take(MAX_LIST_SIZE)
        val want = clean(input.want)
        val avoid = clean(input.avoid)
        val dnp = clean(input.dnp)
        val notes = input.notes
            .mapNotNull { (key, value) ->
                val team = key.trim().toIntOrNull() ?: return@mapNotNull null
                val note = value.trim().take(MAX_NOTE_LENGTH)
                if (team in seen && note.isNotEmpty()) team.toString() to note else null
            }
            .toMap()
        return PickLists(want = want, avoid = avoid, dnp = dnp, notes = notes)
    }

    fun getPickLists(session: UserSession, eventKey: String): PickListsResponse {
        return readTransaction {
            val row = AllianceSelections.selectAll().where {
                (AllianceSelections.ownerKey eq pickListOwnerKey(session.teamNumber, session.program)) and
                (AllianceSelections.eventKey eq eventKey)
            }.firstOrNull()

            if (row == null) {
                PickListsResponse(PickLists(), 0L)
            } else {
                val lists = runCatching {
                    JsonSupport.json.decodeFromString(PickLists.serializer(), row[AllianceSelections.selectionJson])
                }.getOrDefault(PickLists())
                PickListsResponse(sanitizePickLists(lists), row[AllianceSelections.updatedAt].toEpochMilli())
            }
        }
    }

    fun updatePickLists(session: UserSession, request: PickListsUpdateRequest): PickListsResponse {
        val clean = sanitizePickLists(request.pickLists)
        return transaction {
            val now = Instant.now()
            AllianceSelections.upsert(
                AllianceSelections.ownerKey,
                AllianceSelections.eventKey
            ) {
                it[ownerKey] = pickListOwnerKey(session.teamNumber, session.program)
                it[eventKey] = request.eventKey
                it[selectionJson] = JsonSupport.json.encodeToString(PickLists.serializer(), clean)
                it[updatedAt] = now
            }
            PickListsResponse(clean, now.toEpochMilli())
        }
    }

    /** Ranked pick lists are private to the team, like the Want / Avoid / Do Not Pick lists. */
    fun rankedPickListOwnerKey(teamNumber: Int, program: String): String = "rankedpicklist_team_${teamNumber}_$program"

    /** Dedupes, drops invalid team numbers, and removes every team on the Do Not Pick list. */
    fun sanitizeRanking(ranking: List<Int>, dnp: Collection<Int>): List<Int> {
        val excluded = dnp.toSet()
        val seen = mutableSetOf<Int>()
        return ranking.filter { it > 0 && it !in excluded && seen.add(it) }.take(MAX_LIST_SIZE)
    }

    private fun readRanking(session: UserSession, eventKey: String): Pair<List<Int>, Long> {
        val row = AllianceSelections.selectAll().where {
            (AllianceSelections.ownerKey eq rankedPickListOwnerKey(session.teamNumber, session.program)) and
            (AllianceSelections.eventKey eq eventKey)
        }.firstOrNull() ?: return emptyList<Int>() to 0L
        val ranking = runCatching {
            JsonSupport.json.decodeFromString(RankedPickList.serializer(), row[AllianceSelections.selectionJson]).ranking
        }.getOrDefault(emptyList())
        return ranking to row[AllianceSelections.updatedAt].toEpochMilli()
    }

    private fun buildRankedResponse(ranking: List<Int>, rankingUpdatedAt: Long, lists: PickListsResponse) =
        RankedPickListResponse(
            ranking = sanitizeRanking(ranking, lists.pickLists.dnp),
            dnp = lists.pickLists.dnp,
            notes = lists.pickLists.notes,
            updatedAt = maxOf(rankingUpdatedAt, lists.updatedAt)
        )

    fun getRankedPickList(session: UserSession, eventKey: String): RankedPickListResponse {
        val lists = getPickLists(session, eventKey)
        val (ranking, updatedAt) = readTransaction { readRanking(session, eventKey) }
        return buildRankedResponse(ranking, updatedAt, lists)
    }

    fun updateRankedPickList(session: UserSession, request: RankedPickListUpdateRequest): RankedPickListResponse {
        val lists = getPickLists(session, request.eventKey)
        val clean = sanitizeRanking(request.ranking, lists.pickLists.dnp)
        val now = transaction {
            val now = Instant.now()
            AllianceSelections.upsert(
                AllianceSelections.ownerKey,
                AllianceSelections.eventKey
            ) {
                it[ownerKey] = rankedPickListOwnerKey(session.teamNumber, session.program)
                it[eventKey] = request.eventKey
                it[selectionJson] = JsonSupport.json.encodeToString(RankedPickList.serializer(), RankedPickList(clean))
                it[updatedAt] = now
            }
            now
        }
        return buildRankedResponse(clean, now.toEpochMilli(), lists)
    }

    /**
     * Adds a team to, or removes it from, the Do Not Pick list shared with Alliance Selection.
     * A team can only be on one of Want / Avoid / Do Not Pick, so adding it takes it off the others.
     * The stored ranking is left alone, so taking a team off Do Not Pick puts it back where it was
     * unless the ranking has been saved since (saves drop Do Not Pick teams).
     */
    fun setDoNotPick(session: UserSession, request: DoNotPickUpdateRequest): RankedPickListResponse {
        val current = getPickLists(session, request.eventKey).pickLists
        val team = request.teamNumber
        val updated = if (request.dnp) {
            current.copy(
                want = current.want - team,
                avoid = current.avoid - team,
                dnp = if (team in current.dnp) current.dnp else current.dnp + team
            )
        } else {
            current.copy(dnp = current.dnp - team, notes = current.notes - team.toString())
        }
        val lists = updatePickLists(session, PickListsUpdateRequest(request.eventKey, updated))
        val (ranking, updatedAt) = readTransaction { readRanking(session, request.eventKey) }
        return buildRankedResponse(ranking, updatedAt, lists)
    }

    fun getSelection(session: UserSession, eventKey: String): AllianceSelectionResponse {
        return readTransaction {
            val owner = resolveOwnerKey(session)
            val row = AllianceSelections
                .selectAll().where {
                    (AllianceSelections.ownerKey eq owner) and
                    (AllianceSelections.eventKey eq eventKey)
                }
                .firstOrNull() ?: run {
                    // Backward-compatibility: check legacy un-scoped team key if this is an FRC team
                    if (owner.startsWith("team_")) {
                        AllianceSelections.selectAll().where {
                            (AllianceSelections.ownerKey eq "team_${session.teamNumber}") and
                            (AllianceSelections.eventKey eq eventKey)
                        }.firstOrNull()
                    } else null
                }

            if (row != null) {
                AllianceSelectionResponse(
                    selectionJson = row[AllianceSelections.selectionJson],
                    updatedAt = row[AllianceSelections.updatedAt].toEpochMilli()
                )
            } else {
                AllianceSelectionResponse(
                    selectionJson = "{}",
                    updatedAt = 0L
                )
            }
        }
    }

    fun updateSelection(session: UserSession, request: AllianceSelectionUpdateRequest): AllianceSelectionUpdateResponse {
        return transaction {
            val owner = resolveOwnerKey(session)
            val now = Instant.now()

            AllianceSelections.upsert(
                AllianceSelections.ownerKey,
                AllianceSelections.eventKey
            ) {
                it[ownerKey] = owner
                it[eventKey] = request.eventKey
                it[selectionJson] = request.selectionJson
                it[updatedAt] = now
            }

            AllianceSelectionUpdateResponse(updatedAt = now.toEpochMilli())
        }
    }
}
