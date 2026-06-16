package com.obsidianscout.scouting

import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.AllianceSelections
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
        val allianceId = AllianceService.getActiveAllianceId(session.teamNumber)
        return if (allianceId != null) {
            "alliance_$allianceId"
        } else {
            "team_${session.teamNumber}"
        }
    }

    fun getSelection(session: UserSession, eventKey: String): AllianceSelectionResponse {
        return transaction {
            val owner = resolveOwnerKey(session)
            val row = AllianceSelections
                .selectAll().where {
                    (AllianceSelections.ownerKey eq owner) and
                    (AllianceSelections.eventKey eq eventKey)
                }
                .firstOrNull()

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
            
            val exists = AllianceSelections
                .selectAll().where {
                    (AllianceSelections.ownerKey eq owner) and
                    (AllianceSelections.eventKey eq request.eventKey)
                }
                .any()

            if (exists) {
                AllianceSelections.update({
                    (AllianceSelections.ownerKey eq owner) and
                    (AllianceSelections.eventKey eq request.eventKey)
                }) {
                    it[selectionJson] = request.selectionJson
                    it[updatedAt] = now
                }
            } else {
                AllianceSelections.insert {
                    it[ownerKey] = owner
                    it[eventKey] = request.eventKey
                    it[selectionJson] = request.selectionJson
                    it[updatedAt] = now
                }
            }

            AllianceSelectionUpdateResponse(updatedAt = now.toEpochMilli())
        }
    }
}
