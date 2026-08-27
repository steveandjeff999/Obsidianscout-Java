package com.obsidianscout.analytics

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.integrations.MatchCanonical
import com.obsidianscout.routes.*
import com.obsidianscout.scouting.ScoutingEntryRecord
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.obsidianscout.db.readTransaction
import kotlin.math.abs
import kotlin.math.round

object ValidationService {

    suspend fun validateEvent(
        session: UserSession,
        eventKeyParam: String,
        forcePrescout: Boolean = false,
        anomalyThreshold: Double = 15.0
    ): ValidationSummaryResponse {
        val eventKeyLower = eventKeyParam.lowercase().trim()

        val count = readTransaction {
            ApiMatches.selectAll().where { ApiMatches.eventKey eq eventKeyLower }.count()
        }
        if (count == 0L) {
            val settings = readTransaction {
                com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
            }
            try {
                com.obsidianscout.integrations.IntegrationService.syncCustomEventData(settings, eventKeyLower)
            } catch (e: Exception) {
                // Log or continue
            }
        }

        val isFtc = session.program.equals("FTC", ignoreCase = true)
        val needsStatsSync = readTransaction {
            val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
            val allTeams = ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKeyLower }.toList()
            val checkEpa = !isFtc && settings.useStatboticsEpa && allTeams.isNotEmpty() && allTeams.all { it[ApiTeams.epa] == null || it[ApiTeams.epa] == 0.0 }
            val checkOpr = settings.useTbaOpr && allTeams.isNotEmpty() && allTeams.all { it[ApiTeams.opr] == null || it[ApiTeams.opr] == 0.0 }
            checkEpa || checkOpr
        }

        if (needsStatsSync) {
            try {
                val settings = readTransaction {
                    com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
                }
                com.obsidianscout.integrations.IntegrationService.syncStats(settings, eventKeyLower)
            } catch (e: Exception) {
                // Non-fatal, EPA/OPR can still be null
            }
        }

        return readTransaction {
            val settings = com.obsidianscout.scouting.AllianceService.getEffectiveSettings(session.teamNumber, session.program)
            val useStatboticsEpa = !isFtc && settings.useStatboticsEpa
            val useTbaOpr = settings.useTbaOpr

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

            val teamInfoMap = mutableMapOf<String, org.jetbrains.exposed.sql.ResultRow>()
            allTeamsInEvent.forEach { row ->
                val rowKey = row[ApiTeams.teamKey].lowercase().trim()
                val rowCanonical = canonicalKeyByKey[rowKey] ?: rowKey
                teamInfoMap[rowKey] = row
                teamInfoMap[rowCanonical] = row
            }

            val config = ConfigService.getConfig(session.teamNumber)

            // Fetch scouter usernames map
            val usersMap = Users.selectAll().associate { it[Users.id].value to it[Users.username] }

            // Fetch scouting entries
            val entriesQuery = ScoutingEntries.selectAll()
            if (session.role != UserRole.SUPERADMIN) {
                val partnerTeams = com.obsidianscout.scouting.AllianceService.getAlliancePartnerTeams(session.teamNumber)
                val visibleTeams = partnerTeams + session.teamNumber
                entriesQuery.andWhere { ScoutingEntries.ownerTeamNumber inList visibleTeams }
            }

            val rawEntries = entriesQuery.map { row ->
                val data = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                val scouterName = usersMap[row[ScoutingEntries.submittedByUserId].value]
                val conflictStr = row[ScoutingEntries.conflictingTeams]
                val conflicting = if (conflictStr.isBlank()) emptyList() else conflictStr.split(",").mapNotNull { it.toIntOrNull() }
                ScoutingEntryRecord(
                    id = row[ScoutingEntries.id].value.toString(),
                    ownerTeamNumber = row[ScoutingEntries.ownerTeamNumber],
                    targetTeamNumber = row[ScoutingEntries.targetTeamNumber],
                    eventKey = row[ScoutingEntries.eventKey],
                    matchKey = row[ScoutingEntries.matchKey],
                    matchNumber = row[ScoutingEntries.matchNumber],
                    data = data,
                    createdAt = row[ScoutingEntries.createdAt].toString(),
                    isPrescout = row[ScoutingEntries.isPrescout],
                    hasDiscrepancy = row[ScoutingEntries.hasDiscrepancy],
                    conflictingTeams = conflicting
                ) to scouterName
            }

            val rawRecordsOnly = rawEntries.map { it.first }
            val resolvedRecords = com.obsidianscout.scouting.ScoutingService.resolveEntriesList(rawRecordsOnly, session.teamNumber, all = false)
            val scouterNameByEntryId = rawEntries.associate { it.first.id to it.second }

            // Index entries by matchKey -> targetTeamNumber -> Entry
            val entriesByMatchKey = mutableMapOf<String, MutableMap<Int, ScoutingEntryRecord>>()
            val entriesByMatchNumber = mutableMapOf<Int, MutableMap<Int, ScoutingEntryRecord>>()
            val entriesByTeam = mutableMapOf<Int, MutableList<ScoutingEntryRecord>>()

            resolvedRecords.forEach { entry ->
                val teamNum = entry.targetTeamNumber ?: return@forEach
                val isCurrentEvent = (entry.eventKey == null || entry.eventKey.equals(eventKeyLower, ignoreCase = true))

                if (isCurrentEvent && !entry.isPrescout) {
                    if (!entry.matchKey.isNullOrBlank()) {
                        entriesByMatchKey.getOrPut(entry.matchKey.lowercase().trim()) { mutableMapOf() }[teamNum] = entry
                    }
                    if (entry.matchNumber != null) {
                        entriesByMatchNumber.getOrPut(entry.matchNumber) { mutableMapOf() }[teamNum] = entry
                    }
                }

                if (isCurrentEvent && (!entry.isPrescout || forcePrescout)) {
                    entriesByTeam.getOrPut(teamNum) { mutableListOf() }.add(entry)
                }
            }

            // Fetch matches
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

            fun extractTeamNumber(teamKey: String): Int {
                val parts = teamKey.split("/")
                return if (parts.size > 1) {
                    parts[1].toIntOrNull() ?: 0
                } else {
                    val primaryKey = parts[0].trim().lowercase()
                    primaryKey.removePrefix("frc").removePrefix("ftc").toIntOrNull()
                        ?: teamNumberByKey[primaryKey]
                        ?: 0
                }
            }

            fun processAlliance(
                allianceColor: String,
                teamKeys: List<String>,
                matchKey: String,
                matchNumber: Int?,
                actualScore: Double?
            ): AllianceValidationRecord {
                val teams = teamKeys.map { extractTeamNumber(it) }.filter { it > 0 }
                val matchEntriesMap = entriesByMatchKey[matchKey.lowercase().trim()]
                    ?: matchNumber?.let { entriesByMatchNumber[it] }
                    ?: emptyMap()

                val scoutedTeams = mutableListOf<Int>()
                val missingTeams = mutableListOf<Int>()
                val breakdowns = mutableListOf<TeamMatchEntryBreakdown>()
                var scoutedScoreSum = 0.0

                teams.forEach { teamNum ->
                    val entry = matchEntriesMap[teamNum]
                    if (entry != null) {
                        scoutedTeams.add(teamNum)
                        val score = AnalyticsService.scoreEntry(config, entry)
                        val roundedScore = round(score * 10.0) / 10.0
                        scoutedScoreSum += roundedScore
                        breakdowns.add(
                            TeamMatchEntryBreakdown(
                                teamNumber = teamNum,
                                teamKey = teamKeyByNumber[teamNum] ?: "$progPrefix$teamNum",
                                scoutedScore = roundedScore,
                                entryId = entry.id,
                                scouterName = scouterNameByEntryId[entry.id],
                                hasDiscrepancy = entry.hasDiscrepancy
                            )
                        )
                    } else {
                        missingTeams.add(teamNum)
                    }
                }

                val isFullyScouted = missingTeams.isEmpty() && teams.isNotEmpty()
                val scoreDiff = if (actualScore != null) {
                    round((scoutedScoreSum - actualScore) * 10.0) / 10.0
                } else {
                    null
                }

                val isScoreAnomaly = (actualScore != null && isFullyScouted && abs((scoreDiff ?: 0.0)) >= anomalyThreshold)
                val isMissingAnomaly = missingTeams.isNotEmpty() && scoutedTeams.isNotEmpty()

                val warningText = when {
                    missingTeams.isNotEmpty() && scoutedTeams.isEmpty() -> "No teams scouted yet"
                    missingTeams.isNotEmpty() -> "Missing scouting for team(s): ${missingTeams.joinToString(", ")}"
                    isScoreAnomaly -> "Score discrepancy: scouted sum $scoutedScoreSum vs official $actualScore (diff: ${if ((scoreDiff ?: 0.0) > 0) "+$scoreDiff" else "$scoreDiff"})"
                    else -> null
                }

                return AllianceValidationRecord(
                    allianceColor = allianceColor,
                    teams = teams,
                    scoutedTeams = scoutedTeams,
                    missingTeams = missingTeams,
                    isFullyScouted = isFullyScouted,
                    actualScore = actualScore,
                    scoutedScoreSum = round(scoutedScoreSum * 10.0) / 10.0,
                    scoreDiff = scoreDiff,
                    isAnomaly = isScoreAnomaly || isMissingAnomaly,
                    warning = warningText,
                    teamBreakdowns = breakdowns
                )
            }

            var totalFullyScoutedMatches = 0
            var totalIncompleteMatches = 0
            var totalUnscoutedMatches = 0
            var totalMatchesWithAnomalies = 0

            val matchRecords = matches.map { matchRow ->
                val matchKey = matchRow[ApiMatches.matchKey]
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
                val scheduledTime = matchRow[ApiMatches.scheduledTime]
                val actualTime = matchRow[ApiMatches.actualTime]
                val dataJson = matchRow[ApiMatches.dataJson]

                val (officialRedScore, officialBlueScore) = extractMatchScores(dataJson)

                val redAlliance = processAlliance("red", redTeamKeys, matchKey, matchNumber, officialRedScore)
                val blueAlliance = processAlliance("blue", blueTeamKeys, matchKey, matchNumber, officialBlueScore)

                val isMatchFullyScouted = redAlliance.isFullyScouted && blueAlliance.isFullyScouted
                val isMatchUnscouted = redAlliance.scoutedTeams.isEmpty() && blueAlliance.scoutedTeams.isEmpty()
                val isMatchIncomplete = !isMatchFullyScouted && !isMatchUnscouted
                val hasMatchAnomaly = redAlliance.isAnomaly || blueAlliance.isAnomaly

                if (isMatchFullyScouted) totalFullyScoutedMatches++
                if (isMatchIncomplete) totalIncompleteMatches++
                if (isMatchUnscouted) totalUnscoutedMatches++
                if (hasMatchAnomaly) totalMatchesWithAnomalies++

                val warnings = listOfNotNull(
                    redAlliance.warning?.takeIf { redAlliance.missingTeams.isNotEmpty() }?.let { "Red: $it" },
                    blueAlliance.warning?.takeIf { blueAlliance.missingTeams.isNotEmpty() }?.let { "Blue: $it" }
                )
                val matchWarning = if (warnings.isNotEmpty()) warnings.joinToString("; ") else null

                MatchValidationRecord(
                    matchKey = matchKey,
                    eventKey = eventKeyLower,
                    compLevel = compLevel,
                    setNumber = setNumber,
                    matchNumber = matchNumber,
                    label = label,
                    scheduledTime = scheduledTime,
                    actualTime = actualTime,
                    redAlliance = redAlliance,
                    blueAlliance = blueAlliance,
                    isFullyScouted = isMatchFullyScouted,
                    hasAnomaly = hasMatchAnomaly,
                    matchWarning = matchWarning
                )
            }

            // Per-Team EPA / OPR validation
            var totalTeamsWithAnomalies = 0
            val allTeamNumbers = (allTeamsInEvent.map { it[ApiTeams.teamNumber] } + entriesByTeam.keys).distinct().sorted()

            val teamRecords = allTeamNumbers.map { teamNumber ->
                val resolvedKey = teamKeyByNumber[teamNumber] ?: "$progPrefix$teamNumber"
                val teamRow = teamInfoMap[resolvedKey] ?: teamInfoMap["$progPrefix$teamNumber"]
                val nickname = teamRow?.get(ApiTeams.nickname) ?: teamRow?.get(ApiTeams.name) ?: "Team $teamNumber"
                val epa = teamRow?.get(ApiTeams.epa)
                val opr = teamRow?.get(ApiTeams.opr)

                val teamEntries = entriesByTeam[teamNumber] ?: emptyList()
                val scoutedCount = teamEntries.size
                val avgScoutedScore = if (teamEntries.isNotEmpty()) {
                    val avg = teamEntries.map { entry -> AnalyticsService.scoreEntry(config, entry) }.average()
                    round(avg * 10.0) / 10.0
                } else {
                    null
                }

                val epaDiff = if (avgScoutedScore != null && epa != null && useStatboticsEpa && epa > 0) {
                    round((avgScoutedScore - epa) * 10.0) / 10.0
                } else null

                val oprDiff = if (avgScoutedScore != null && opr != null && useTbaOpr && opr > 0) {
                    round((avgScoutedScore - opr) * 10.0) / 10.0
                } else null

                val isEpaAnomaly = (epaDiff != null && abs(epaDiff) >= anomalyThreshold)
                val isOprAnomaly = (oprDiff != null && abs(oprDiff) >= anomalyThreshold)
                val hasDiscrepancy = teamEntries.any { it.hasDiscrepancy }
                val isAnomaly = isEpaAnomaly || isOprAnomaly || hasDiscrepancy

                if (isAnomaly) totalTeamsWithAnomalies++

                val anomalyReasons = mutableListOf<String>()
                if (isEpaAnomaly) {
                    val sign = if (epaDiff!! > 0) "+$epaDiff" else "$epaDiff"
                    anomalyReasons.add("Scouted avg (${avgScoutedScore}) deviates from EPA (${epa}) by $sign")
                }
                if (isOprAnomaly) {
                    val sign = if (oprDiff!! > 0) "+$oprDiff" else "$oprDiff"
                    anomalyReasons.add("Scouted avg (${avgScoutedScore}) deviates from OPR (${opr}) by $sign")
                }
                if (hasDiscrepancy) {
                    anomalyReasons.add("Has conflicting scouting entries from partners")
                }

                TeamValidationRecord(
                    teamNumber = teamNumber,
                    teamKey = resolvedKey,
                    nickname = nickname,
                    scoutedMatchCount = scoutedCount,
                    averageScoutedScore = avgScoutedScore,
                    epa = epa,
                    opr = opr,
                    epaDiff = epaDiff,
                    oprDiff = oprDiff,
                    isAnomaly = isAnomaly,
                    anomalyReason = if (anomalyReasons.isNotEmpty()) anomalyReasons.joinToString("; ") else null,
                    hasDiscrepancy = hasDiscrepancy
                )
            }

            ValidationSummaryResponse(
                eventKey = eventKeyLower,
                totalMatches = matches.size,
                fullyScoutedMatches = totalFullyScoutedMatches,
                incompleteMatches = totalIncompleteMatches,
                unscoutedMatches = totalUnscoutedMatches,
                matchesWithAnomalies = totalMatchesWithAnomalies,
                teamsAnalyzed = teamRecords.size,
                teamsWithAnomalies = totalTeamsWithAnomalies,
                useStatboticsEpa = useStatboticsEpa,
                useTbaOpr = useTbaOpr,
                threshold = anomalyThreshold,
                matches = matchRecords,
                teams = teamRecords
            )
        }
    }

    private fun extractMatchScores(dataJson: String): Pair<Double?, Double?> {
        if (dataJson.isBlank() || dataJson == "{}") return null to null
        return try {
            val root = JsonSupport.json.parseToJsonElement(dataJson).jsonObject

            // 1. TBA format: alliances.red.score / alliances.blue.score
            val alliances = root["alliances"] as? JsonObject
            val tbaRedScore = (alliances?.get("red") as? JsonObject)?.get("score")?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            val tbaBlueScore = (alliances?.get("blue") as? JsonObject)?.get("score")?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            if (tbaRedScore != null && tbaBlueScore != null) {
                val red = if (tbaRedScore >= 0) tbaRedScore else null
                val blue = if (tbaBlueScore >= 0) tbaBlueScore else null
                if (red != null || blue != null) return red to blue
            }

            // 2. FIRST API format: scoreRedFinal / scoreBlueFinal
            val firstRed = root["scoreRedFinal"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                ?: root["scoreRed"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            val firstBlue = root["scoreBlueFinal"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                ?: root["scoreBlue"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            if (firstRed != null || firstBlue != null) {
                return firstRed to firstBlue
            }

            // 3. FTC format: scores.red.totalPoints / scores.blue.totalPoints or red_score / blue_score
            val scores = root["scores"] as? JsonObject
            val ftcRed = (scores?.get("red") as? JsonObject)?.get("totalPoints")?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                ?: root["red_score"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            val ftcBlue = (scores?.get("blue") as? JsonObject)?.get("totalPoints")?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                ?: root["blue_score"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            if (ftcRed != null || ftcBlue != null) {
                return ftcRed to ftcBlue
            }

            null to null
        } catch (e: Exception) {
            null to null
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
