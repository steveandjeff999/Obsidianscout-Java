package com.obsidianscout.analytics

import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.UserRole
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.integrations.MatchCanonical
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.routes.AlliancePrediction
import com.obsidianscout.routes.MatchPredictionResponse
import com.obsidianscout.routes.MatchTeamPrediction
import com.obsidianscout.scouting.ScoutingEntryRecord
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import com.obsidianscout.auth.ApiException

object PredictorService {
    fun predict(session: UserSession, matchKey: String): MatchPredictionResponse {
        return transaction {
            val matchRow = ApiMatches.select { ApiMatches.matchKey eq matchKey.lowercase() }
                .limit(1)
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Match not found")

            val redTeamKeys = JsonSupport.json.decodeFromString(
                ListSerializer(String.serializer()),
                matchRow[ApiMatches.redTeams]
            )
            val blueTeamKeys = JsonSupport.json.decodeFromString(
                ListSerializer(String.serializer()),
                matchRow[ApiMatches.blueTeams]
            )
            val eventKey = matchRow[ApiMatches.eventKey]
            val compLevel = matchRow[ApiMatches.compLevel]
            val setNumber = matchRow[ApiMatches.setNumber]
            val matchNumber = matchRow[ApiMatches.matchNumber]
            val label = MatchCanonical.displayLabel(compLevel, setNumber, matchNumber)

            val settings = SettingsService.getSettings(session.teamNumber)
            val useStatboticsEpa = settings.useStatboticsEpa
            val useTbaOpr = settings.useTbaOpr

            val allTeamKeys = redTeamKeys + blueTeamKeys
            val teamRows = ApiTeams.select {
                (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamKey inList allTeamKeys)
            }.toList()
            val teamInfoMap = teamRows.associateBy { it[ApiTeams.teamKey] }

            val teamNumbers = allTeamKeys.mapNotNull { key ->
                key.removePrefix("frc").toIntOrNull()
            }
            val config = ConfigService.getConfig(session.teamNumber)

            val entriesQuery = ScoutingEntries.select {
                (ScoutingEntries.eventKey eq eventKey) and (ScoutingEntries.targetTeamNumber inList teamNumbers)
            }
            if (session.role != UserRole.SUPERADMIN) {
                entriesQuery.andWhere { ScoutingEntries.ownerTeamNumber eq session.teamNumber }
            }
            
            val entries = entriesQuery.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                ScoutingEntryRecord(
                    id = row[ScoutingEntries.id].value,
                    ownerTeamNumber = row[ScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[ScoutingEntries.targetTeamNumber],
                    eventKey = row[ScoutingEntries.eventKey],
                    matchKey = row[ScoutingEntries.matchKey],
                    matchNumber = row[ScoutingEntries.matchNumber],
                    data = data,
                    createdAt = row[ScoutingEntries.createdAt].toString()
                )
            }
            val entriesByTeam = entries.groupBy { it.targetTeamNumber }

            fun predictTeam(teamKey: String): MatchTeamPrediction {
                val teamNumber = teamKey.removePrefix("frc").toIntOrNull() ?: 0
                val teamRow = teamInfoMap[teamKey]
                val nickname = teamRow?.get(ApiTeams.nickname) ?: teamRow?.get(ApiTeams.name) ?: "Team $teamNumber"
                val epa = teamRow?.get(ApiTeams.epa)
                val opr = teamRow?.get(ApiTeams.opr)

                val teamEntries = entriesByTeam[teamNumber] ?: emptyList()
                val avgScore = if (teamEntries.isNotEmpty()) {
                    teamEntries.map { entry ->
                        AnalyticsService.scoreEntry(config, entry)
                    }.average()
                } else {
                    null
                }

                return MatchTeamPrediction(
                    teamNumber = teamNumber,
                    nickname = nickname,
                    averageScoutedScore = avgScore,
                    scoutedMatchesCount = teamEntries.size,
                    epa = epa,
                    opr = opr
                )
            }

            val redPredictions = redTeamKeys.map { predictTeam(it) }
            val bluePredictions = blueTeamKeys.map { predictTeam(it) }

            val totalRedScouted = redPredictions.mapNotNull { it.averageScoutedScore }.sum()
            val totalBlueScouted = bluePredictions.mapNotNull { it.averageScoutedScore }.sum()

            val totalRedEpa = redPredictions.mapNotNull { it.epa }.sum()
            val totalBlueEpa = bluePredictions.mapNotNull { it.epa }.sum()

            val totalRedOpr = redPredictions.mapNotNull { it.opr }.sum()
            val totalBlueOpr = bluePredictions.mapNotNull { it.opr }.sum()

            MatchPredictionResponse(
                matchKey = matchKey,
                label = label,
                redAlliance = AlliancePrediction(
                    teams = redPredictions,
                    totalScoutedScore = totalRedScouted,
                    totalEpa = totalRedEpa,
                    totalOpr = totalRedOpr
                ),
                blueAlliance = AlliancePrediction(
                    teams = bluePredictions,
                    totalScoutedScore = totalBlueScouted,
                    totalEpa = totalBlueEpa,
                    totalOpr = totalBlueOpr
                ),
                useStatboticsEpa = useStatboticsEpa,
                useTbaOpr = useTbaOpr
            )
        }
    }
}
