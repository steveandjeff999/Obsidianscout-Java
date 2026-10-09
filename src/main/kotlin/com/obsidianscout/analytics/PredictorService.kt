package com.obsidianscout.analytics

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.UserSession
import com.obsidianscout.auth.UserRole
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.EpaOprHistoryCache
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.integrations.MatchCanonical
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.routes.AlliancePrediction
import com.obsidianscout.routes.MatchPredictionResponse
import com.obsidianscout.routes.MatchTeamPrediction
import com.obsidianscout.routes.Match13PredictionDetail
import com.obsidianscout.routes.Match13TeamExpDetail
import com.obsidianscout.scouting.ScoutingEntryRecord
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.obsidianscout.db.readTransaction
import com.obsidianscout.auth.ApiException

private fun JsonObject.readDouble(key: String): Double? {
    val elem = this[key] ?: return null
    return when (elem) {
        is JsonPrimitive -> elem.content.toDoubleOrNull()
        else -> null
    }
}

object PredictorService {
    suspend fun predict(session: UserSession, matchKey: String, forcePrescout: Boolean = false, eventKeyParam: String? = null): MatchPredictionResponse {
        val matchKeyLower = matchKey.lowercase().trim()
        val eventKey = readTransaction {
            ApiMatches.selectAll().where { ApiMatches.matchKey eq matchKeyLower }
                .limit(1)
                .map { it[ApiMatches.eventKey] }
                .firstOrNull()
        }

        if (eventKey == null) {
            throw ApiException(HttpStatusCode.NotFound, "Match not found")
        }

        val isFtc = session.program.equals("FTC", ignoreCase = true)
        val needsStatsSync = readTransaction {
            val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
            val allTeams = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }.toList()
            val checkEpa = !isFtc && settings.useStatboticsEpa && allTeams.isNotEmpty() && allTeams.all { it[ApiTeams.epa] == null || it[ApiTeams.epa] == 0.0 }
            val checkExp = !isFtc && settings.useMatch13Exp && allTeams.isNotEmpty() && allTeams.all { it[ApiTeams.match13Exp] == null || it[ApiTeams.match13Exp] == 0.0 }
            val checkOpr = settings.useTbaOpr && allTeams.isNotEmpty() && allTeams.all { it[ApiTeams.opr] == null || it[ApiTeams.opr] == 0.0 }
            checkEpa || checkExp || checkOpr
        }

        if (needsStatsSync) {
            try {
                val settings = readTransaction { com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program) }
                com.obsidianscout.integrations.IntegrationService.syncStats(settings, eventKey)
            } catch (e: Exception) {
                throw ApiException(HttpStatusCode.BadGateway, "Failed to fetch EPA/OPR/EXP stats from API: ${e.message}")
            }
        }

        return readTransaction {
            val matchRow = ApiMatches.selectAll().where { ApiMatches.matchKey eq matchKey.lowercase() }
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

            val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
            val useStatboticsEpa = !isFtc && settings.useStatboticsEpa
            val useMatch13Exp = !isFtc && settings.useMatch13Exp
            val useTbaOpr = settings.useTbaOpr

            val allTeamsInEvent = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }.toList()
            val bbotMappings = com.obsidianscout.integrations.IntegrationService.getBBotMappings(eventKey)
            
            val teamKeyByNumber = mutableMapOf<Int, String>()
            val teamNumberByKey = mutableMapOf<String, Int>()
            val canonicalKeyByKey = mutableMapOf<String, String>()
            
            val progPrefix = session.program.lowercase()
            bbotMappings.forEach { m ->
                val bKey = if (m.bbotKey.startsWith("frc") || m.bbotKey.startsWith("ftc")) m.bbotKey else "$progPrefix${m.bbotKey}"
                val pKey = if (m.placeholderKey.startsWith("frc") || m.placeholderKey.startsWith("ftc")) m.placeholderKey else "$progPrefix${m.placeholderKey}"
                val num = m.placeholderNumber

                teamKeyByNumber[num] = bKey
                
                teamNumberByKey[bKey] = num
                teamNumberByKey[bKey.removePrefix("frc").removePrefix("ftc")] = num
                teamNumberByKey[pKey] = num
                teamNumberByKey[pKey.removePrefix("frc").removePrefix("ftc")] = num
                
                canonicalKeyByKey[bKey] = bKey
                canonicalKeyByKey[bKey.removePrefix("frc").removePrefix("ftc")] = bKey
                canonicalKeyByKey[pKey] = bKey
                canonicalKeyByKey[pKey.removePrefix("frc").removePrefix("ftc")] = bKey
            }

            allTeamsInEvent.forEach { row ->
                val origKey = row[ApiTeams.teamKey].lowercase().trim()
                val num = row[ApiTeams.teamNumber]
                val oKey = if (origKey.startsWith("frc") || origKey.startsWith("ftc")) origKey else "$progPrefix$origKey"
                
                if (!teamNumberByKey.containsKey(oKey)) {
                    teamKeyByNumber[num] = oKey
                    
                    teamNumberByKey[oKey] = num
                    teamNumberByKey[oKey.removePrefix("frc").removePrefix("ftc")] = num
                    
                    canonicalKeyByKey[oKey] = oKey
                    canonicalKeyByKey[oKey.removePrefix("frc").removePrefix("ftc")] = oKey
                }
            }

            val allTeamKeys = redTeamKeys + blueTeamKeys
            val teamNumbers = allTeamKeys.mapNotNull { key ->
                val parts = key.split("/")
                if (parts.size > 1) {
                    parts[1].toIntOrNull()
                } else {
                    val primaryKey = parts[0].trim().lowercase()
                    val numFromKey = primaryKey.removePrefix("frc").removePrefix("ftc").toIntOrNull()
                    if (numFromKey != null) {
                        numFromKey
                    } else {
                        teamNumberByKey[primaryKey]
                    }
                }
            }

            val teamRows = ApiTeams.selectAll().where {
                (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamNumber inList teamNumbers)
            }.toList()
            
            val teamInfoMap = mutableMapOf<String, org.jetbrains.exposed.v1.core.ResultRow>()
            teamRows.forEach { row ->
                val rowKey = row[ApiTeams.teamKey].lowercase().trim()
                val rowCanonical = canonicalKeyByKey[rowKey] ?: rowKey
                teamInfoMap[rowKey] = row
                teamInfoMap[rowCanonical] = row
            }

            val config = ConfigService.getConfig(session.teamNumber, session.program)

            val entriesQuery = ScoutingEntries.selectAll().where {
                (ScoutingEntries.program eq session.program) and
                (ScoutingEntries.targetTeamNumber inList teamNumbers)
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = com.obsidianscout.scouting.AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
                val visibleTeams = partnerTeams + session.teamNumber
                entriesQuery.andWhere { ScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            
            val rawEntries = entriesQuery.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                ScoutingEntryRecord(
                    id = row[ScoutingEntries.id].value.toString(),
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

            val cachedHistory = if (useMatch13Exp) {
                EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq eventKey }.firstOrNull()
            } else null
            val match13List: List<JsonObject> = if (cachedHistory != null) {
                try {
                    JsonSupport.json.decodeFromString<List<JsonObject>>(cachedHistory[EpaOprHistoryCache.match13HistoryJson])
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()

            val matchKeyClean = matchKey.lowercase().trim()
            val match13MatchObj = match13List.firstOrNull { elem ->
                val k = (elem["key"] as? JsonPrimitive)?.content
                    ?: (elem["matchKey"] as? JsonPrimitive)?.content
                    ?: ""
                k.lowercase().trim() == matchKeyClean
            }

            val match13PredObj = match13MatchObj?.get("pred") as? JsonObject
            val match13Pred = if (match13PredObj != null) {
                Match13PredictionDetail(
                    redScore = match13PredObj.readDouble("redScore"),
                    blueScore = match13PredObj.readDouble("blueScore"),
                    winProb = match13PredObj.readDouble("winProb"),
                    redRp1 = match13PredObj.readDouble("redRp1"),
                    redRp2 = match13PredObj.readDouble("redRp2"),
                    redRp3 = match13PredObj.readDouble("redRp3"),
                    blueRp1 = match13PredObj.readDouble("blueRp1"),
                    blueRp2 = match13PredObj.readDouble("blueRp2"),
                    blueRp3 = match13PredObj.readDouble("blueRp3"),
                    redVar = match13PredObj.readDouble("redVar"),
                    blueVar = match13PredObj.readDouble("blueVar")
                )
            } else null

            val match13TeamsObj = match13MatchObj?.get("teams") as? JsonObject

            fun predictTeam(teamKey: String): MatchTeamPrediction {
                val parts = teamKey.split("/")
                val primaryKey = parts[0].trim().lowercase()
                val teamNumber = if (parts.size > 1) {
                    parts[1].toIntOrNull() ?: 0
                } else {
                    primaryKey.removePrefix("frc").removePrefix("ftc").toIntOrNull() ?: teamNumberByKey[primaryKey] ?: 0
                }
                
                val resolvedKey = teamKeyByNumber[teamNumber] ?: primaryKey
                val teamRow = teamInfoMap[resolvedKey] ?: teamInfoMap[primaryKey]
                val nickname = teamRow?.get(ApiTeams.nickname) ?: teamRow?.get(ApiTeams.name) ?: "Team $teamNumber"
                val epa = teamRow?.get(ApiTeams.epa)
                val exp = teamRow?.get(ApiTeams.match13Exp)
                val opr = teamRow?.get(ApiTeams.opr)

                val teamExpObj = (match13TeamsObj?.get(teamNumber.toString()) as? JsonObject)
                    ?: (match13TeamsObj?.get("frc$teamNumber") as? JsonObject)
                val match13TeamExp = if (teamExpObj != null) {
                    Match13TeamExpDetail(
                        xpPost = teamExpObj.readDouble("xpPost") ?: teamExpObj.readDouble("xp"),
                        xpPre = teamExpObj.readDouble("xpPre"),
                        xAutoPost = teamExpObj.readDouble("xAutoPost") ?: teamExpObj.readDouble("xAuto"),
                        xAutoPre = teamExpObj.readDouble("xAutoPre"),
                        xTelePost = teamExpObj.readDouble("xTelePost") ?: teamExpObj.readDouble("xTele"),
                        xTelePre = teamExpObj.readDouble("xTelePre"),
                        xEndPost = teamExpObj.readDouble("xEndPost") ?: teamExpObj.readDouble("xEnd"),
                        xEndPre = teamExpObj.readDouble("xEndPre")
                    )
                } else null

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
                    exp = exp,
                    opr = opr,
                    match13TeamExp = match13TeamExp,
                    hasDiscrepancy = hasDiscrepancy
                )
            }

            val redPredictions = redTeamKeys.map { predictTeam(it) }
            val bluePredictions = blueTeamKeys.map { predictTeam(it) }

            val totalRedScouted = redPredictions.mapNotNull { it.averageScoutedScore }.sum()
            val totalBlueScouted = bluePredictions.mapNotNull { it.averageScoutedScore }.sum()

            val totalRedEpa = redPredictions.mapNotNull { it.epa }.sum()
            val totalBlueEpa = bluePredictions.mapNotNull { it.epa }.sum()

            val totalRedExp = (if (useMatch13Exp && match13Pred?.redScore != null) match13Pred.redScore else null)
                ?: redPredictions.mapNotNull { it.exp }.sum()
            val totalBlueExp = (if (useMatch13Exp && match13Pred?.blueScore != null) match13Pred.blueScore else null)
                ?: bluePredictions.mapNotNull { it.exp }.sum()

            val totalRedOpr = redPredictions.mapNotNull { it.opr }.sum()
            val totalBlueOpr = bluePredictions.mapNotNull { it.opr }.sum()

            MatchPredictionResponse(
                matchKey = matchKey,
                label = label,
                redAlliance = AlliancePrediction(
                    teams = redPredictions,
                    totalScoutedScore = totalRedScouted,
                    totalEpa = totalRedEpa,
                    totalExp = totalRedExp,
                    totalOpr = totalRedOpr
                ),
                blueAlliance = AlliancePrediction(
                    teams = bluePredictions,
                    totalScoutedScore = totalBlueScouted,
                    totalEpa = totalBlueEpa,
                    totalExp = totalBlueExp,
                    totalOpr = totalBlueOpr
                ),
                useStatboticsEpa = useStatboticsEpa,
                useMatch13Exp = useMatch13Exp,
                useTbaOpr = useTbaOpr,
                match13Pred = match13Pred
            )
        }
    }

    suspend fun predictAll(session: UserSession, eventKeyParam: String, forcePrescout: Boolean = false): List<MatchPredictionResponse> {
        val eventKeyLower = eventKeyParam.lowercase().trim()

        return readTransaction {
            val matches = ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKeyLower }
                .toList()
                .sortedWith(
                    compareBy(
                        { compLevelRank(it[ApiMatches.compLevel]) },
                        { it[ApiMatches.setNumber] ?: 0 },
                        { it[ApiMatches.matchNumber] ?: 0 },
                        { it[ApiMatches.scheduledTime] ?: Long.MAX_VALUE },
                        { it[ApiMatches.matchKey] }
                    )
                )

            if (matches.isEmpty()) {
                return@readTransaction emptyList<MatchPredictionResponse>()
            }

            val isFtc = session.program.equals("FTC", ignoreCase = true)
            val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
            val useStatboticsEpa = !isFtc && settings.useStatboticsEpa
            val useMatch13Exp = !isFtc && settings.useMatch13Exp
            val useTbaOpr = settings.useTbaOpr

            val cachedHistory = if (useMatch13Exp) {
                EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq eventKeyLower }.firstOrNull()
            } else null
            val match13List: List<JsonObject> = if (cachedHistory != null) {
                try {
                    JsonSupport.json.decodeFromString<List<JsonObject>>(cachedHistory[EpaOprHistoryCache.match13HistoryJson])
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()

            val match13Map = match13List.associateBy { elem ->
                ((elem["key"] as? JsonPrimitive)?.content
                    ?: (elem["matchKey"] as? JsonPrimitive)?.content
                    ?: "").lowercase().trim()
            }

            val allTeamsInEvent = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKeyLower }.toList()
            val bbotMappings = com.obsidianscout.integrations.IntegrationService.getBBotMappings(eventKeyLower)

            val teamKeyByNumber = mutableMapOf<Int, String>()
            val teamNumberByKey = mutableMapOf<String, Int>()
            val canonicalKeyByKey = mutableMapOf<String, String>()
            
            val progPrefix = session.program.lowercase()
            bbotMappings.forEach { m ->
                val bKey = if (m.bbotKey.startsWith("frc") || m.bbotKey.startsWith("ftc")) m.bbotKey else "$progPrefix${m.bbotKey}"
                val pKey = if (m.placeholderKey.startsWith("frc") || m.placeholderKey.startsWith("ftc")) m.placeholderKey else "$progPrefix${m.placeholderKey}"
                val num = m.placeholderNumber

                teamKeyByNumber[num] = bKey
                
                teamNumberByKey[bKey] = num
                teamNumberByKey[bKey.removePrefix("frc").removePrefix("ftc")] = num
                teamNumberByKey[pKey] = num
                teamNumberByKey[pKey.removePrefix("frc").removePrefix("ftc")] = num
                
                canonicalKeyByKey[bKey] = bKey
                canonicalKeyByKey[bKey.removePrefix("frc").removePrefix("ftc")] = bKey
                canonicalKeyByKey[pKey] = bKey
                canonicalKeyByKey[pKey.removePrefix("frc").removePrefix("ftc")] = bKey
            }

            allTeamsInEvent.forEach { row ->
                val origKey = row[ApiTeams.teamKey].lowercase().trim()
                val num = row[ApiTeams.teamNumber]
                val oKey = if (origKey.startsWith("frc") || origKey.startsWith("ftc")) origKey else "$progPrefix$origKey"
                
                if (!teamNumberByKey.containsKey(oKey)) {
                    teamKeyByNumber[num] = oKey
                    
                    teamNumberByKey[oKey] = num
                    teamNumberByKey[oKey.removePrefix("frc").removePrefix("ftc")] = num
                    
                    canonicalKeyByKey[oKey] = oKey
                    canonicalKeyByKey[oKey.removePrefix("frc").removePrefix("ftc")] = oKey
                }
            }

            val teamInfoMap = mutableMapOf<String, org.jetbrains.exposed.v1.core.ResultRow>()
            allTeamsInEvent.forEach { row ->
                val rowKey = row[ApiTeams.teamKey].lowercase().trim()
                val rowCanonical = canonicalKeyByKey[rowKey] ?: rowKey
                teamInfoMap[rowKey] = row
                teamInfoMap[rowCanonical] = row
            }

            val teamNumbers = allTeamsInEvent.map { it[ApiTeams.teamNumber] }
            val config = ConfigService.getConfig(session.teamNumber, session.program)

            val entriesQuery = ScoutingEntries.selectAll().where {
                (ScoutingEntries.program eq session.program) and
                (ScoutingEntries.targetTeamNumber inList teamNumbers)
            }
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = com.obsidianscout.scouting.AllianceService.getAlliancePartnerTeams(session.teamNumber, session.program)
                val visibleTeams = partnerTeams + session.teamNumber
                entriesQuery.andWhere { ScoutingEntries.ownerTeamNumber inList visibleTeams }
            }
            
            val rawEntries = entriesQuery.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                ScoutingEntryRecord(
                    id = row[ScoutingEntries.id].value.toString(),
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
                val currentEventEntries = teamEntries.filter { it.eventKey == eventKeyLower && !it.isPrescout }
                val prescoutEntries = teamEntries.filter { it.isPrescout }
                
                if (forcePrescout || currentEventEntries.size < 3) {
                    currentEventEntries + prescoutEntries
                } else {
                    currentEventEntries
                }
            }

            val baseTeamPredictions = mutableMapOf<String, MatchTeamPrediction>()

            fun getOrCreateBaseTeamPrediction(teamKey: String): MatchTeamPrediction {
                val normalizedKey = teamKey.lowercase().trim()
                val canonicalKey = canonicalKeyByKey[normalizedKey] ?: normalizedKey
                val lookupKey = if (baseTeamPredictions.containsKey(canonicalKey)) canonicalKey else normalizedKey

                return baseTeamPredictions.getOrPut(lookupKey) {
                    val parts = teamKey.split("/")
                    val primaryKey = parts[0].trim().lowercase()
                    val teamNumber = if (parts.size > 1) {
                        parts[1].toIntOrNull() ?: 0
                    } else {
                        primaryKey.removePrefix("frc").removePrefix("ftc").toIntOrNull() ?: teamNumberByKey[primaryKey] ?: 0
                    }
                    
                    val resolvedKey = teamKeyByNumber[teamNumber] ?: primaryKey
                    val teamRow = teamInfoMap[resolvedKey] ?: teamInfoMap[primaryKey]
                    val nickname = teamRow?.get(ApiTeams.nickname) ?: teamRow?.get(ApiTeams.name) ?: "Team $teamNumber"
                    val epa = teamRow?.get(ApiTeams.epa)
                    val exp = teamRow?.get(ApiTeams.match13Exp)
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

                    MatchTeamPrediction(
                        teamNumber = teamNumber,
                        teamKey = resolvedKey,
                        nickname = nickname,
                        averageScoutedScore = avgScore,
                        scoutedMatchesCount = teamEntries.size,
                        epa = epa,
                        exp = exp,
                        opr = opr,
                        hasDiscrepancy = hasDiscrepancy
                    )
                }
            }

            matches.map { matchRow ->
                val matchKey = matchRow[ApiMatches.matchKey]
                val matchKeyLowerClean = matchKey.lowercase().trim()
                val match13MatchObj = match13Map[matchKeyLowerClean]
                val match13PredObj = match13MatchObj?.get("pred") as? JsonObject
                val match13Pred = if (match13PredObj != null) {
                    Match13PredictionDetail(
                        redScore = match13PredObj.readDouble("redScore"),
                        blueScore = match13PredObj.readDouble("blueScore"),
                        winProb = match13PredObj.readDouble("winProb"),
                        redRp1 = match13PredObj.readDouble("redRp1"),
                        redRp2 = match13PredObj.readDouble("redRp2"),
                        redRp3 = match13PredObj.readDouble("redRp3"),
                        blueRp1 = match13PredObj.readDouble("blueRp1"),
                        blueRp2 = match13PredObj.readDouble("blueRp2"),
                        blueRp3 = match13PredObj.readDouble("blueRp3"),
                        redVar = match13PredObj.readDouble("redVar"),
                        blueVar = match13PredObj.readDouble("blueVar")
                    )
                } else null

                val match13TeamsObj = match13MatchObj?.get("teams") as? JsonObject

                fun enrichTeamPrediction(base: MatchTeamPrediction): MatchTeamPrediction {
                    val teamExpObj = (match13TeamsObj?.get(base.teamNumber.toString()) as? JsonObject)
                        ?: (match13TeamsObj?.get("frc${base.teamNumber}") as? JsonObject)
                    val match13TeamExp = if (teamExpObj != null) {
                        Match13TeamExpDetail(
                            xpPost = teamExpObj.readDouble("xpPost") ?: teamExpObj.readDouble("xp"),
                            xpPre = teamExpObj.readDouble("xpPre"),
                            xAutoPost = teamExpObj.readDouble("xAutoPost") ?: teamExpObj.readDouble("xAuto"),
                            xAutoPre = teamExpObj.readDouble("xAutoPre"),
                            xTelePost = teamExpObj.readDouble("xTelePost") ?: teamExpObj.readDouble("xTele"),
                            xTelePre = teamExpObj.readDouble("xTelePre"),
                            xEndPost = teamExpObj.readDouble("xEndPost") ?: teamExpObj.readDouble("xEnd"),
                            xEndPre = teamExpObj.readDouble("xEndPre")
                        )
                    } else null

                    return base.copy(match13TeamExp = match13TeamExp)
                }

                val redTeamKeys = JsonSupport.json.decodeFromString(
                    ListSerializer(String.serializer()),
                    matchRow[ApiMatches.redTeams]
                )
                val blueTeamKeys = JsonSupport.json.decodeFromString(
                    ListSerializer(String.serializer()),
                    matchRow[ApiMatches.blueTeams]
                )
                val compLevel = matchRow[ApiMatches.compLevel]
                val setNumber = matchRow[ApiMatches.setNumber]
                val matchNumber = matchRow[ApiMatches.matchNumber]
                val label = MatchCanonical.displayLabel(compLevel, setNumber, matchNumber)

                val redPredictions = redTeamKeys.map { enrichTeamPrediction(getOrCreateBaseTeamPrediction(it)) }
                val bluePredictions = blueTeamKeys.map { enrichTeamPrediction(getOrCreateBaseTeamPrediction(it)) }

                val totalRedScouted = redPredictions.mapNotNull { it.averageScoutedScore }.sum()
                val totalBlueScouted = bluePredictions.mapNotNull { it.averageScoutedScore }.sum()

                val totalRedEpa = redPredictions.mapNotNull { it.epa }.sum()
                val totalBlueEpa = bluePredictions.mapNotNull { it.epa }.sum()

                val totalRedExp = (if (useMatch13Exp && match13Pred?.redScore != null) match13Pred.redScore else null)
                    ?: redPredictions.mapNotNull { it.exp }.sum()
                val totalBlueExp = (if (useMatch13Exp && match13Pred?.blueScore != null) match13Pred.blueScore else null)
                    ?: bluePredictions.mapNotNull { it.exp }.sum()

                val totalRedOpr = redPredictions.mapNotNull { it.opr }.sum()
                val totalBlueOpr = bluePredictions.mapNotNull { it.opr }.sum()

                MatchPredictionResponse(
                    matchKey = matchKey,
                    label = label,
                    redAlliance = AlliancePrediction(
                        teams = redPredictions,
                        totalScoutedScore = totalRedScouted,
                        totalEpa = totalRedEpa,
                        totalExp = totalRedExp,
                        totalOpr = totalRedOpr
                    ),
                    blueAlliance = AlliancePrediction(
                        teams = bluePredictions,
                        totalScoutedScore = totalBlueScouted,
                        totalEpa = totalBlueEpa,
                        totalExp = totalBlueExp,
                        totalOpr = totalBlueOpr
                    ),
                    useStatboticsEpa = useStatboticsEpa,
                    useMatch13Exp = useMatch13Exp,
                    useTbaOpr = useTbaOpr,
                    match13Pred = match13Pred
                )
            }
        }
    }

    private fun compLevelRank(compLevel: String): Int {
        return when (MatchCanonical.normalizeCompLevel(compLevel)) {
            "practice" -> 0
            "qm" -> 1
            "qf" -> 2
            "sf" -> 3
            "f" -> 4
            "ef" -> 5
            "playoff" -> 6
            else -> 7
        }
    }
}
