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
    fun predict(session: UserSession, matchKey: String, forcePrescout: Boolean = false): MatchPredictionResponse {
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

            val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber)
            val useStatboticsEpa = settings.useStatboticsEpa
            val useTbaOpr = settings.useTbaOpr

            val allTeamsInEvent = ApiTeams.select { ApiTeams.eventKey eq eventKey }.toList()
            val bbotMappings = com.obsidianscout.integrations.IntegrationService.getBBotMappings(eventKey)
            
            val teamKeyByNumber = mutableMapOf<Int, String>()
            val teamNumberByKey = mutableMapOf<String, Int>()
            val canonicalKeyByKey = mutableMapOf<String, String>()
            
            bbotMappings.forEach { m ->
                val bKey = if (m.bbotKey.startsWith("frc")) m.bbotKey else "frc${m.bbotKey}"
                val pKey = if (m.placeholderKey.startsWith("frc")) m.placeholderKey else "frc${m.placeholderKey}"
                val num = m.placeholderNumber

                teamKeyByNumber[num] = bKey
                
                teamNumberByKey[bKey] = num
                teamNumberByKey[bKey.removePrefix("frc")] = num
                teamNumberByKey[pKey] = num
                teamNumberByKey[pKey.removePrefix("frc")] = num
                
                canonicalKeyByKey[bKey] = bKey
                canonicalKeyByKey[bKey.removePrefix("frc")] = bKey
                canonicalKeyByKey[pKey] = bKey
                canonicalKeyByKey[pKey.removePrefix("frc")] = bKey
            }

            allTeamsInEvent.forEach { row ->
                val origKey = row[ApiTeams.teamKey].lowercase().trim()
                val num = row[ApiTeams.teamNumber]
                val oKey = if (origKey.startsWith("frc")) origKey else "frc$origKey"
                
                if (!teamNumberByKey.containsKey(oKey)) {
                    teamKeyByNumber[num] = oKey
                    
                    teamNumberByKey[oKey] = num
                    teamNumberByKey[oKey.removePrefix("frc")] = num
                    
                    canonicalKeyByKey[oKey] = oKey
                    canonicalKeyByKey[oKey.removePrefix("frc")] = oKey
                }
            }

            val allTeamKeys = redTeamKeys + blueTeamKeys
            val teamNumbers = allTeamKeys.mapNotNull { key ->
                val parts = key.split("/")
                if (parts.size > 1) {
                    parts[1].toIntOrNull()
                } else {
                    val primaryKey = parts[0].trim().lowercase()
                    val numFromKey = primaryKey.removePrefix("frc").toIntOrNull()
                    if (numFromKey != null) {
                        numFromKey
                    } else {
                        teamNumberByKey[primaryKey]
                    }
                }
            }

            val teamRows = ApiTeams.select {
                (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamNumber inList teamNumbers)
            }.toList()
            
            val teamInfoMap = mutableMapOf<String, org.jetbrains.exposed.sql.ResultRow>()
            teamRows.forEach { row ->
                val rowKey = row[ApiTeams.teamKey].lowercase().trim()
                val rowCanonical = canonicalKeyByKey[rowKey] ?: rowKey
                teamInfoMap[rowKey] = row
                teamInfoMap[rowCanonical] = row
            }

            val config = ConfigService.getConfig(session.teamNumber)

            val entriesQuery = ScoutingEntries.select {
                ScoutingEntries.targetTeamNumber inList teamNumbers
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = com.obsidianscout.scouting.AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                entriesQuery.andWhere { ScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            
            val rawEntries = entriesQuery.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                ScoutingEntryRecord(
                    id = row[ScoutingEntries.id].value,
                    ownerTeamNumber = row[ScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[ScoutingEntries.targetTeamNumber],
                    eventKey = row[ScoutingEntries.eventKey],
                    matchKey = row[ScoutingEntries.matchKey],
                    matchNumber = row[ScoutingEntries.matchNumber],
                    data = data,
                    createdAt = row[ScoutingEntries.createdAt].toString(),
                    isPrescout = row[ScoutingEntries.isPrescout]
                )
            }
            val entries = com.obsidianscout.scouting.ScoutingService.resolveEntriesList(rawEntries, session.teamNumber, all = false)
            val groupedEntries = entries.groupBy { it.targetTeamNumber }
            val entriesByTeam = teamNumbers.associateWith { teamNumber ->
                val teamEntries = groupedEntries[teamNumber] ?: emptyList()
                val currentEventEntries = teamEntries.filter { it.eventKey == eventKey && !it.isPrescout }
                val prescoutEntries = teamEntries.filter { it.isPrescout }
                
                if (forcePrescout || currentEventEntries.size < 3) {
                    currentEventEntries + prescoutEntries
                } else {
                    currentEventEntries
                }
            }

            fun predictTeam(teamKey: String): MatchTeamPrediction {
                val parts = teamKey.split("/")
                val primaryKey = parts[0].trim().lowercase()
                val teamNumber = if (parts.size > 1) {
                    parts[1].toIntOrNull() ?: 0
                } else {
                    primaryKey.removePrefix("frc").toIntOrNull() ?: teamNumberByKey[primaryKey] ?: 0
                }
                
                val resolvedKey = teamKeyByNumber[teamNumber] ?: primaryKey
                val teamRow = teamInfoMap[resolvedKey] ?: teamInfoMap[primaryKey]
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

                val hasDiscrepancy = teamEntries.any { it.hasDiscrepancy }

                return MatchTeamPrediction(
                    teamNumber = teamNumber,
                    teamKey = resolvedKey,
                    nickname = nickname,
                    averageScoutedScore = avgScore,
                    scoutedMatchesCount = teamEntries.size,
                    epa = epa,
                    opr = opr,
                    hasDiscrepancy = hasDiscrepancy
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
