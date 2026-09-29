package com.obsidianscout.scouting

import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.MatchPlans
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.sql.upsert
import java.time.Instant

@Serializable
data class MatchPlanResponse(
    val planJson: String,
    val updatedAt: Long
)

@Serializable
data class MatchPlanUpdateRequest(
    val eventKey: String,
    val matchKey: String,
    val planJson: String
)

@Serializable
data class MatchPlanUpdateResponse(
    val updatedAt: Long
)

object MatchPlanningService {

    private fun resolveOwnerKey(session: UserSession): String {
        val allianceId = AllianceService.getActiveAllianceId(session.teamNumber, session.program)
        return if (allianceId != null) {
            "alliance_$allianceId"
        } else {
            "team_${session.teamNumber}_${session.program}"
        }
    }

    fun getPlan(session: UserSession, eventKey: String, matchKey: String): MatchPlanResponse {
        return readTransaction {
            val owner = resolveOwnerKey(session)
            val row = MatchPlans
                .selectAll().where {
                    (MatchPlans.ownerKey eq owner) and
                    (MatchPlans.eventKey eq eventKey) and
                    (MatchPlans.matchKey eq matchKey)
                }
                .firstOrNull() ?: run {
                    if (owner.startsWith("team_")) {
                        MatchPlans.selectAll().where {
                            (MatchPlans.ownerKey eq "team_${session.teamNumber}") and
                            (MatchPlans.eventKey eq eventKey) and
                            (MatchPlans.matchKey eq matchKey)
                        }.firstOrNull()
                    } else null
                }

            if (row != null) {
                MatchPlanResponse(
                    planJson = row[MatchPlans.planJson],
                    updatedAt = row[MatchPlans.updatedAt].toEpochMilli()
                )
            } else {
                MatchPlanResponse(
                    planJson = "{}",
                    updatedAt = 0L
                )
            }
        }
    }

    fun updatePlan(session: UserSession, request: MatchPlanUpdateRequest): MatchPlanUpdateResponse {
        return transaction {
            val owner = resolveOwnerKey(session)
            val now = Instant.now()

            MatchPlans.upsert(
                MatchPlans.ownerKey,
                MatchPlans.eventKey,
                MatchPlans.matchKey
            ) {
                it[ownerKey] = owner
                it[eventKey] = request.eventKey
                it[matchKey] = request.matchKey
                it[planJson] = request.planJson
                it[updatedAt] = now
            }

            MatchPlanUpdateResponse(updatedAt = now.toEpochMilli())
        }
    }
}
