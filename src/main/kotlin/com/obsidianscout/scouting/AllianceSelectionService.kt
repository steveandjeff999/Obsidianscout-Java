package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.UserSession
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

object AllianceSelectionService {

    private fun resolveOwnerKey(session: UserSession): String {
        val allianceId = AllianceService.getActiveAllianceId(session.teamNumber, session.program)
        return if (allianceId != null) {
            "alliance_$allianceId"
        } else {
            "team_${session.teamNumber}_${session.program}"
        }
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
