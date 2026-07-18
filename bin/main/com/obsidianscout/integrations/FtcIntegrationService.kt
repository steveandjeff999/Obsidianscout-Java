package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.EpaOprHistoryCache
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and
import io.ktor.serialization.kotlinx.json.json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64

@Serializable
private data class GraphQLRequest(
    val query: String,
    val variables: JsonObject = JsonObject(emptyMap())
)

object FtcIntegrationService {
    private val log = LoggerFactory.getLogger(FtcIntegrationService::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(JsonSupport.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000L
            connectTimeoutMillis = 30000L
            socketTimeoutMillis = 30000L
        }
    }

    private fun hasFirstCredentials(settings: ApiSettings): Boolean {
        return settings.apiKeys.firstUsername.isNotBlank() && settings.apiKeys.firstKey.isNotBlank()
    }

    private fun extractYearAndCode(eventKey: String): Pair<Int, String> {
        val key = eventKey.trim().lowercase()
        val year = if (key.length >= 4 && key.take(4).all { it.isDigit() }) {
            key.take(4).toInt()
        } else {
            java.time.Year.now().value
        }
        val code = if (key.length > 4) key.drop(4).uppercase() else key.uppercase()
        return Pair(year, code)
    }

    suspend fun syncEvents(settings: ApiSettings): Int {
        val year = settings.year
        log.info("Syncing FTC events for season $year using FTC Scout GraphQL API")

        val query = """
            query {
              eventsSearch(season: $year, limit: 200) {
                code
                name
                start
                end
                timezone
              }
            }
        """.trimIndent()

        val response = try {
            client.post("https://api.ftcscout.org/graphql") {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(query))
            }
        } catch (e: Exception) {
            log.error("Failed to query FTC Scout events: ${e.message}")
            return 0
        }

        if (!response.status.isSuccess()) {
            log.error("FTC Scout events query failed with status: ${response.status}")
            return 0
        }

        val bodyText = response.bodyAsText()
        val json = JsonSupport.json.parseToJsonElement(bodyText).jsonObject
        val eventsArray = json["data"]?.jsonObject?.get("eventsSearch")?.jsonArray ?: return 0

        val now = Instant.now()
        transaction {
            eventsArray.forEach { item ->
                val obj = item.jsonObject
                val code = obj["code"]?.jsonPrimitive?.content ?: ""
                val name = obj["name"]?.jsonPrimitive?.content ?: ""
                val start = obj["start"]?.jsonPrimitive?.content
                val end = obj["end"]?.jsonPrimitive?.content
                val timezone = obj["timezone"]?.jsonPrimitive?.content ?: "America/New_York"
                val eventKey = "${year}${code.lowercase()}"

                val existing = ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.limit(1).firstOrNull()
                if (existing == null) {
                    ApiEvents.insert {
                        it[ApiEvents.eventKey] = eventKey
                        it[ApiEvents.name] = name
                        it[ApiEvents.year] = year
                        it[ApiEvents.eventCode] = code
                        it[ApiEvents.startDate] = start
                        it[ApiEvents.endDate] = end
                        it[ApiEvents.timezone] = timezone
                        it[ApiEvents.dataJson] = JsonSupport.json.encodeToString(item)
                        it[ApiEvents.updatedAt] = now
                    }
                } else {
                    ApiEvents.update({ ApiEvents.id eq existing[ApiEvents.id] }) {
                        it[ApiEvents.name] = name
                        it[ApiEvents.year] = year
                        it[ApiEvents.eventCode] = code
                        it[ApiEvents.startDate] = start
                        it[ApiEvents.endDate] = end
                        it[ApiEvents.timezone] = timezone
                        it[ApiEvents.dataJson] = JsonSupport.json.encodeToString(item)
                        it[ApiEvents.updatedAt] = now
                    }
                }
            }
        }
        return eventsArray.size
    }

    suspend fun syncEventData(settings: ApiSettings): SyncCounts {
        val eventKey = "${settings.year}${settings.eventCode.lowercase()}"
        return syncCustomEventData(settings, eventKey)
    }

    suspend fun syncCustomEventData(settings: ApiSettings, eventKey: String): SyncCounts {
        val (year, code) = extractYearAndCode(eventKey)
        log.info("Syncing FTC event data for $eventKey (season $year, code $code)")

        val scoresFragment = when (year) {
            2019 -> "... on MatchScores2019 { red { totalPoints } blue { totalPoints } }"
            2020 -> "... on MatchScores2020Trad { red { totalPoints } blue { totalPoints } } ... on MatchScores2020Remote { red { totalPoints } blue { totalPoints } }"
            2021 -> "... on MatchScores2021Trad { red { totalPoints } blue { totalPoints } } ... on MatchScores2021Remote { red { totalPoints } blue { totalPoints } }"
            2022 -> "... on MatchScores2022 { red { totalPoints } blue { totalPoints } }"
            2023 -> "... on MatchScores2023 { red { totalPoints } blue { totalPoints } }"
            2024 -> "... on MatchScores2024 { red { totalPoints } blue { totalPoints } }"
            2025 -> "... on MatchScores2025 { red { totalPoints } blue { totalPoints } }"
            else -> "... on MatchScores2025 { red { totalPoints } blue { totalPoints } }"
        }

        // Primary source: FTC Scout GraphQL (location field removed as it doesn't exist on Team type)
        val query = """
            query {
              eventByCode(season: $year, code: "$code") {
                name
                timezone
                start
                end
                teams {
                  teamNumber
                  team {
                    name
                    rookieYear
                    website
                  }
                }
                matches {
                  id
                  matchNum
                  tournamentLevel
                  hasBeenPlayed
                  teams {
                    teamNumber
                    alliance
                    station
                  }
                  scores {
                    $scoresFragment
                  }
                }
              }
            }
        """.trimIndent()

        val response = try {
            client.post("https://api.ftcscout.org/graphql") {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(query))
            }
        } catch (e: Exception) {
            log.error("Failed to query FTC Scout event details: ${e.message}")
            return SyncCounts(0, 0)
        }

        if (!response.status.isSuccess()) {
            log.error("FTC Scout event details query failed with status: ${response.status}")
            return SyncCounts(0, 0)
        }

        val bodyText = response.bodyAsText()
        val json = JsonSupport.json.parseToJsonElement(bodyText).jsonObject
        val eventObj = json["data"]?.jsonObject?.get("eventByCode")?.jsonObject

        if (eventObj == null) {
            log.warn("FTC event not found on FTC Scout: $eventKey")
            return SyncCounts(0, 0)
        }

        val name = eventObj["name"]?.jsonPrimitive?.content ?: code
        val timezone = eventObj["timezone"]?.jsonPrimitive?.content ?: "America/New_York"
        val start = eventObj["start"]?.jsonPrimitive?.content
        val end = eventObj["end"]?.jsonPrimitive?.content

        val now = Instant.now()
        transaction {
            val existing = ApiEvents.selectAll().where { ApiEvents.eventKey eq eventKey }.limit(1).firstOrNull()
            if (existing == null) {
                ApiEvents.insert {
                    it[ApiEvents.eventKey] = eventKey
                    it[ApiEvents.name] = name
                    it[ApiEvents.year] = year
                    it[ApiEvents.eventCode] = code
                    it[ApiEvents.startDate] = start
                    it[ApiEvents.endDate] = end
                    it[ApiEvents.timezone] = timezone
                    it[ApiEvents.dataJson] = JsonSupport.json.encodeToString(eventObj)
                    it[ApiEvents.updatedAt] = now
                }
            } else {
                ApiEvents.update({ ApiEvents.id eq existing[ApiEvents.id] }) {
                    it[ApiEvents.name] = name
                    it[ApiEvents.year] = year
                    it[ApiEvents.eventCode] = code
                    it[ApiEvents.startDate] = start
                    it[ApiEvents.endDate] = end
                    it[ApiEvents.timezone] = timezone
                    it[ApiEvents.dataJson] = JsonSupport.json.encodeToString(eventObj)
                    it[ApiEvents.updatedAt] = now
                }
            }
        }

        // Teams Sync
        val teamsArray = eventObj["teams"]?.jsonArray ?: JsonArray(emptyList())
        transaction {
            teamsArray.forEach { participation ->
                val partObj = participation.jsonObject
                val teamNumber = partObj["teamNumber"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val teamObj = partObj["team"]?.jsonObject ?: return@forEach
                val teamName = teamObj["name"]?.jsonPrimitive?.content ?: "Team $teamNumber"
                val location = teamObj["location"]?.jsonObject
                val city = location?.get("city")?.jsonPrimitive?.content ?: ""
                val state = location?.get("state")?.jsonPrimitive?.content ?: ""
                val country = location?.get("country")?.jsonPrimitive?.content ?: ""

                val teamKey = "ftc$teamNumber"
                val existingTeam = ApiTeams.selectAll().where { (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamKey eq teamKey) }.limit(1).firstOrNull()
                if (existingTeam == null) {
                    ApiTeams.insert {
                        it[ApiTeams.eventKey] = eventKey
                        it[ApiTeams.teamKey] = teamKey
                        it[ApiTeams.teamNumber] = teamNumber
                        it[ApiTeams.name] = teamName
                        it[ApiTeams.nickname] = teamName
                        it[ApiTeams.city] = city
                        it[ApiTeams.state] = state
                        it[ApiTeams.country] = country
                        it[ApiTeams.opr] = 0.0
                        it[ApiTeams.epa] = 0.0
                        it[ApiTeams.dataJson] = JsonSupport.json.encodeToString(teamObj)
                        it[ApiTeams.updatedAt] = now
                    }
                } else {
                    ApiTeams.update({ ApiTeams.id eq existingTeam[ApiTeams.id] }) {
                        it[ApiTeams.name] = teamName
                        it[ApiTeams.nickname] = teamName
                        it[ApiTeams.city] = city
                        it[ApiTeams.state] = state
                        it[ApiTeams.country] = country
                        it[ApiTeams.dataJson] = JsonSupport.json.encodeToString(teamObj)
                        it[ApiTeams.updatedAt] = now
                    }
                }
            }
        }

        // Matches Sync
        val matchesArray = eventObj["matches"]?.jsonArray ?: JsonArray(emptyList())
        val compLevelMap = mapOf(
            "QUALS" to "qm", "QUALIFIER" to "qm", "SEMIS" to "sf", "FINALS" to "f"
        )
        transaction {
            MatchCanonical.deduplicateDatabaseForEvent(eventKey)
            matchesArray.forEach { match ->
                val mObj = match.jsonObject
                val matchNum = mObj["matchNum"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val rawLevel = mObj["tournamentLevel"]?.jsonPrimitive?.content ?: "QUALS"
                val compLevel = compLevelMap[rawLevel.uppercase()] ?: "qm"

                val teamsList = mObj["teams"]?.jsonArray ?: JsonArray(emptyList())
                val red = mutableListOf<String>()
                val blue = mutableListOf<String>()
                teamsList.forEach { part ->
                    val pObj = part.jsonObject
                    val num = pObj["teamNumber"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val alliance = pObj["alliance"]?.jsonPrimitive?.content ?: "Red"
                    if (alliance.equals("red", ignoreCase = true)) {
                        red.add("ftc$num")
                    } else {
                        blue.add("ftc$num")
                    }
                }

                val scores = mObj["scores"]?.jsonObject
                val redScore = scores?.get("red")?.jsonObject?.get("totalPoints")?.jsonPrimitive?.intOrNull ?: 0
                val blueScore = scores?.get("blue")?.jsonObject?.get("totalPoints")?.jsonPrimitive?.intOrNull ?: 0

                val matchKey = "${eventKey}_${compLevel}_m$matchNum"
                val existingMatch = ApiMatches.selectAll().where { ApiMatches.matchKey eq matchKey }.limit(1).firstOrNull()
                if (existingMatch == null) {
                    ApiMatches.insert {
                        it[ApiMatches.matchKey] = matchKey
                        it[ApiMatches.eventKey] = eventKey
                        it[ApiMatches.compLevel] = compLevel
                        it[ApiMatches.setNumber] = 1
                        it[ApiMatches.matchNumber] = matchNum
                        it[ApiMatches.redTeams] = JsonSupport.json.encodeToString(red)
                        it[ApiMatches.blueTeams] = JsonSupport.json.encodeToString(blue)
                        it[ApiMatches.dataJson] = JsonSupport.json.encodeToString(match)
                        it[ApiMatches.updatedAt] = now
                    }
                } else {
                    ApiMatches.update({ ApiMatches.id eq existingMatch[ApiMatches.id] }) {
                        it[ApiMatches.redTeams] = JsonSupport.json.encodeToString(red)
                        it[ApiMatches.blueTeams] = JsonSupport.json.encodeToString(blue)
                        it[ApiMatches.dataJson] = JsonSupport.json.encodeToString(match)
                        it[ApiMatches.updatedAt] = now
                    }
                }
            }
        }

        // If credentials for FIRST FTC API are provided, fetch/overwrite with team list to fetch rookie year, website etc.
        if (hasFirstCredentials(settings)) {
            try {
                fetchAndMergeFirstFtcData(settings, eventKey, code, now)
            } catch (e: Exception) {
                log.warn("Failed to merge FIRST FTC API event data: ${e.message}")
            }
        }

        return SyncCounts(teamsArray.size, matchesArray.size)
    }

    suspend fun syncStats(settings: ApiSettings, eventKey: String) {
        val (year, code) = extractYearAndCode(eventKey)
        log.info("Syncing FTC stats / OPRs for event $eventKey")

        val teams = transaction {
            ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKey }.map { it[ApiTeams.teamNumber] }
        }

        if (teams.isEmpty()) return

        val now = Instant.now()
        withContext(Dispatchers.IO) {
            teams.forEach { teamNum ->
                val query = """
                    query {
                      teamByNumber(number: $teamNum) {
                        quickStats(season: $year) {
                          tot { value }
                          auto { value }
                          dc { value }
                          eg { value }
                        }
                      }
                    }
                """.trimIndent()

                val response = try {
                    client.post("https://api.ftcscout.org/graphql") {
                        contentType(ContentType.Application.Json)
                        setBody(GraphQLRequest(query))
                    }
                } catch (_: Exception) {
                    null
                }

                if (response != null && response.status.isSuccess()) {
                    val bodyText = response.bodyAsText()
                    val json = JsonSupport.json.parseToJsonElement(bodyText).jsonObject
                    val teamObj = json["data"]?.jsonObject?.get("teamByNumber")?.jsonObject
                    val quickStats = teamObj?.get("quickStats")?.jsonObject
                    val opr = quickStats?.get("tot")?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull ?: 0.0

                    transaction {
                        ApiTeams.update({ (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamKey eq "ftc$teamNum") }) {
                            it[ApiTeams.opr] = opr
                            it[ApiTeams.epa] = opr
                            it[ApiTeams.updatedAt] = now
                        }
                    }
                }
            }
        }
    }

    fun getEpaOprHistory(settings: ApiSettings, eventKey: String): Pair<Map<String, Double>, List<JsonElement>> {
        val (year, code) = extractYearAndCode(eventKey)
        val normalizedKey = "${year}${code.lowercase()}"

        val cached = transaction {
            EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq normalizedKey }.firstOrNull()
        }

        if (cached != null) {
            val oprsMap = try {
                JsonSupport.json.decodeFromString<Map<String, Double>>(cached[EpaOprHistoryCache.oprsJson])
            } catch (_: Exception) {
                emptyMap()
            }
            val epaHistoryList = try {
                JsonSupport.json.decodeFromString<List<JsonElement>>(cached[EpaOprHistoryCache.epaHistoryJson])
            } catch (_: Exception) {
                emptyList()
            }

            val updatedAt = cached[EpaOprHistoryCache.updatedAt]
            if (updatedAt.isBefore(Instant.now().minusSeconds(900))) {
                SyncScheduler.triggerBackgroundHistorySync(settings, normalizedKey)
            }

            return Pair(oprsMap, epaHistoryList)
        } else {
            SyncScheduler.triggerBackgroundHistorySync(settings, normalizedKey)
            return Pair(emptyMap(), emptyList())
        }
    }

    suspend fun syncEpaOprHistory(settings: ApiSettings, eventKey: String) {
        val (year, code) = extractYearAndCode(eventKey)
        val normalizedKey = "${year}${code.lowercase()}"

        val teams = transaction {
            ApiTeams.selectAll().where { ApiTeams.eventKey eq normalizedKey }.map { it[ApiTeams.teamNumber] }
        }

        val oprs = mutableMapOf<String, Double>()
        withContext(Dispatchers.IO) {
            teams.forEach { teamNum ->
                val query = """
                    query {
                      teamByNumber(number: $teamNum) {
                        quickStats(season: $year) {
                          tot { value }
                        }
                      }
                    }
                """.trimIndent()

                val response = try {
                    client.post("https://api.ftcscout.org/graphql") {
                        contentType(ContentType.Application.Json)
                        setBody(GraphQLRequest(query))
                    }
                } catch (_: Exception) {
                    null
                }

                if (response != null && response.status.isSuccess()) {
                    val bodyText = response.bodyAsText()
                    val json = JsonSupport.json.parseToJsonElement(bodyText).jsonObject
                    val teamObj = json["data"]?.jsonObject?.get("teamByNumber")?.jsonObject
                    val quickStats = teamObj?.get("quickStats")?.jsonObject
                    val opr = quickStats?.get("tot")?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    oprs["ftc$teamNum"] = opr
                }
            }
        }

        val oprsJson = JsonSupport.json.encodeToString(oprs)
        val epaHistoryJson = "[]"
        val now = Instant.now()

        transaction {
            val existing = EpaOprHistoryCache.selectAll().where { EpaOprHistoryCache.eventKey eq normalizedKey }.firstOrNull()
            if (existing == null) {
                EpaOprHistoryCache.insert {
                    it[EpaOprHistoryCache.eventKey] = normalizedKey
                    it[EpaOprHistoryCache.oprsJson] = oprsJson
                    it[EpaOprHistoryCache.epaHistoryJson] = epaHistoryJson
                    it[EpaOprHistoryCache.updatedAt] = now
                }
            } else {
                EpaOprHistoryCache.update({ EpaOprHistoryCache.eventKey eq normalizedKey }) {
                    it[EpaOprHistoryCache.oprsJson] = oprsJson
                    it[EpaOprHistoryCache.epaHistoryJson] = epaHistoryJson
                    it[EpaOprHistoryCache.updatedAt] = now
                }
            }
        }
    }

    private suspend fun fetchAndMergeFirstFtcData(settings: ApiSettings, eventKey: String, eventCode: String, now: Instant) {
        val user = settings.apiKeys.firstUsername
        val key = settings.apiKeys.firstKey
        val credentials = Base64.getEncoder().encodeToString("$user:$key".toByteArray())

        val url = "https://ftc-api.firstinspires.org/v3.0/${settings.year}/teams?eventCode=$eventCode"
        val response = try {
            client.get(url) {
                header("Authorization", "Basic $credentials")
            }
        } catch (e: Exception) {
            log.warn("Failed to fetch teams from FIRST FTC API: ${e.message}")
            return
        }

        if (response.status == HttpStatusCode.OK) {
            val bodyText = response.bodyAsText()
            val root = JsonSupport.json.parseToJsonElement(bodyText).jsonObject
            val teamsArray = root["teams"]?.jsonArray ?: return
            transaction {
                teamsArray.forEach { teamItem ->
                    val obj = teamItem.jsonObject
                    val teamNumber = obj["teamNumber"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val name = obj["nameOnTablet"]?.jsonPrimitive?.content ?: obj["nameFull"]?.jsonPrimitive?.content ?: ""
                    val city = obj["city"]?.jsonPrimitive?.content ?: ""
                    val state = obj["stateProv"]?.jsonPrimitive?.content ?: ""
                    val country = obj["country"]?.jsonPrimitive?.content ?: ""

                    val teamKey = "ftc$teamNumber"
                    val existing = ApiTeams.selectAll().where { (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamKey eq teamKey) }.firstOrNull()
                    if (existing != null) {
                        ApiTeams.update({ ApiTeams.id eq existing[ApiTeams.id] }) {
                            if (name.isNotBlank()) it[ApiTeams.nickname] = name
                            if (city.isNotBlank()) it[ApiTeams.city] = city
                            if (state.isNotBlank()) it[ApiTeams.state] = state
                            if (country.isNotBlank()) it[ApiTeams.country] = country
                            it[ApiTeams.updatedAt] = now
                        }
                    }
                }
            }
        }
    }
}
