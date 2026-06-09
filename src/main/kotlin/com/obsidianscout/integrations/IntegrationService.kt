package com.obsidianscout.integrations

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ApiEvents
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.routes.EventRecord
import com.obsidianscout.routes.MatchRecord
import com.obsidianscout.routes.TeamRecord
import com.obsidianscout.routes.SummaryResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64

object IntegrationService {
    private val log = LoggerFactory.getLogger("IntegrationService")
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(JsonSupport.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15000L
            connectTimeoutMillis = 10000L
            socketTimeoutMillis = 10000L
        }
    }

    suspend fun syncEvents(settings: ApiSettings): Int {
        val events = fetchMergedEvents(settings)
        val now = Instant.now()
        transaction {
            events.forEach { event -> upsertEvent(event, now) }
        }
        return events.size
    }

    suspend fun syncEventData(settings: ApiSettings): SyncCounts {
        val eventKey = settings.resolvedEventKey()
        if (eventKey.isBlank()) {
            return SyncCounts(0, 0)
        }
        upsertEventRecord(settings, eventKey)
        val teams = fetchMergedTeams(settings, eventKey)
        val matches = fetchMergedMatches(settings, eventKey)
        val now = Instant.now()
        transaction {
            val removed = MatchCanonical.deduplicateDatabaseForEvent(eventKey)
            if (removed > 0) {
                log.info("Cleaned $removed duplicate match row(s) before sync for $eventKey")
            }
            teams.forEach { team ->
                val existing = ApiTeams.select {
                    (ApiTeams.eventKey eq team.eventKey) and (ApiTeams.teamKey eq team.teamKey)
                }.limit(1).firstOrNull()
                if (existing == null) {
                    ApiTeams.insert {
                        it[ApiTeams.eventKey] = team.eventKey
                        it[ApiTeams.teamKey] = team.teamKey
                        it[ApiTeams.teamNumber] = team.teamNumber
                        it[ApiTeams.name] = team.name.clipTeamText()
                        it[ApiTeams.nickname] = team.nickname.clipTeamText()
                        it[ApiTeams.city] = team.city.clipTeamLocation()
                        it[ApiTeams.state] = team.state.clipTeamLocation()
                        it[ApiTeams.country] = team.country.clipTeamLocation()
                        it[ApiTeams.opr] = team.opr
                        it[ApiTeams.epa] = team.epa
                        it[ApiTeams.dataJson] = team.dataJson
                        it[ApiTeams.updatedAt] = now
                    }
                } else {
                    ApiTeams.update({ ApiTeams.id eq existing[ApiTeams.id] }) {
                        it[ApiTeams.teamNumber] = team.teamNumber
                        it[ApiTeams.name] = team.name.clipTeamText()
                        it[ApiTeams.nickname] = team.nickname.clipTeamText()
                        it[ApiTeams.city] = team.city.clipTeamLocation()
                        it[ApiTeams.state] = team.state.clipTeamLocation()
                        it[ApiTeams.country] = team.country.clipTeamLocation()
                        it[ApiTeams.opr] = team.opr
                        it[ApiTeams.epa] = team.epa
                        it[ApiTeams.dataJson] = team.dataJson
                        it[ApiTeams.updatedAt] = now
                    }
                }
            }

            matches.forEach { match ->
                val canonical = MatchCanonical.canonicalize(match)
                val existing = ApiMatches.select { ApiMatches.matchKey eq canonical.matchKey }.limit(1).firstOrNull()
                if (existing == null) {
                    ApiMatches.insert {
                        it[ApiMatches.matchKey] = canonical.matchKey
                        it[ApiMatches.eventKey] = canonical.eventKey
                        it[ApiMatches.compLevel] = canonical.compLevel
                        it[ApiMatches.setNumber] = canonical.setNumber
                        it[ApiMatches.matchNumber] = canonical.matchNumber
                        it[ApiMatches.scheduledTime] = canonical.scheduledTime
                        it[ApiMatches.actualTime] = canonical.actualTime
                        it[ApiMatches.redTeams] = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.redTeams)
                        it[ApiMatches.blueTeams] = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.blueTeams)
                        it[ApiMatches.dataJson] = canonical.dataJson
                        it[ApiMatches.updatedAt] = now
                    }
                } else {
                    ApiMatches.update({ ApiMatches.id eq existing[ApiMatches.id] }) {
                        it[ApiMatches.eventKey] = canonical.eventKey
                        it[ApiMatches.compLevel] = canonical.compLevel
                        it[ApiMatches.setNumber] = canonical.setNumber
                        it[ApiMatches.matchNumber] = canonical.matchNumber
                        it[ApiMatches.scheduledTime] = canonical.scheduledTime
                        it[ApiMatches.actualTime] = canonical.actualTime
                        it[ApiMatches.redTeams] = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.redTeams)
                        it[ApiMatches.blueTeams] = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.blueTeams)
                        it[ApiMatches.dataJson] = canonical.dataJson
                        it[ApiMatches.updatedAt] = now
                    }
                }
            }
            MatchCanonical.deduplicateDatabaseForEvent(eventKey)
        }
        return SyncCounts(teams.size, matches.size)
    }

    suspend fun syncCustomEventData(settings: ApiSettings, eventKey: String): SyncCounts {
        val key = eventKey.lowercase().trim()
        if (key.isBlank()) {
            return SyncCounts(0, 0)
        }
        upsertEventRecord(settings, key)
        val teams = fetchMergedTeams(settings, key)
        val matches = fetchMergedMatches(settings, key)
        val now = Instant.now()
        transaction {
            val removed = MatchCanonical.deduplicateDatabaseForEvent(key)
            if (removed > 0) {
                log.info("Cleaned $removed duplicate match row(s) before sync for $key")
            }
            teams.forEach { team ->
                val existing = ApiTeams.select {
                    (ApiTeams.eventKey eq team.eventKey) and (ApiTeams.teamKey eq team.teamKey)
                }.limit(1).firstOrNull()
                if (existing == null) {
                    ApiTeams.insert {
                        it[ApiTeams.eventKey] = team.eventKey
                        it[ApiTeams.teamKey] = team.teamKey
                        it[ApiTeams.teamNumber] = team.teamNumber
                        it[ApiTeams.name] = team.name.clipTeamText()
                        it[ApiTeams.nickname] = team.nickname.clipTeamText()
                        it[ApiTeams.city] = team.city.clipTeamLocation()
                        it[ApiTeams.state] = team.state.clipTeamLocation()
                        it[ApiTeams.country] = team.country.clipTeamLocation()
                        it[ApiTeams.opr] = team.opr
                        it[ApiTeams.epa] = team.epa
                        it[ApiTeams.dataJson] = team.dataJson
                        it[ApiTeams.updatedAt] = now
                    }
                } else {
                    ApiTeams.update({ ApiTeams.id eq existing[ApiTeams.id] }) {
                        it[ApiTeams.teamNumber] = team.teamNumber
                        it[ApiTeams.name] = team.name.clipTeamText()
                        it[ApiTeams.nickname] = team.nickname.clipTeamText()
                        it[ApiTeams.city] = team.city.clipTeamLocation()
                        it[ApiTeams.state] = team.state.clipTeamLocation()
                        it[ApiTeams.country] = team.country.clipTeamLocation()
                        it[ApiTeams.opr] = team.opr
                        it[ApiTeams.epa] = team.epa
                        it[ApiTeams.dataJson] = team.dataJson
                        it[ApiTeams.updatedAt] = now
                    }
                }
            }

            matches.forEach { match ->
                val canonical = MatchCanonical.canonicalize(match)
                val existing = ApiMatches.select { ApiMatches.matchKey eq canonical.matchKey }.limit(1).firstOrNull()
                if (existing == null) {
                    ApiMatches.insert {
                        it[ApiMatches.matchKey] = canonical.matchKey
                        it[ApiMatches.eventKey] = canonical.eventKey
                        it[ApiMatches.compLevel] = canonical.compLevel
                        it[ApiMatches.setNumber] = canonical.setNumber
                        it[ApiMatches.matchNumber] = canonical.matchNumber
                        it[ApiMatches.scheduledTime] = canonical.scheduledTime
                        it[ApiMatches.actualTime] = canonical.actualTime
                        it[ApiMatches.redTeams] = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.redTeams)
                        it[ApiMatches.blueTeams] = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.blueTeams)
                        it[ApiMatches.dataJson] = canonical.dataJson
                        it[ApiMatches.updatedAt] = now
                    }
                } else {
                    ApiMatches.update({ ApiMatches.id eq existing[ApiMatches.id] }) {
                        it[ApiMatches.eventKey] = canonical.eventKey
                        it[ApiMatches.compLevel] = canonical.compLevel
                        it[ApiMatches.setNumber] = canonical.setNumber
                        it[ApiMatches.matchNumber] = canonical.matchNumber
                        it[ApiMatches.scheduledTime] = canonical.scheduledTime
                        it[ApiMatches.actualTime] = canonical.actualTime
                        it[ApiMatches.redTeams] = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.redTeams)
                        it[ApiMatches.blueTeams] = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), canonical.blueTeams)
                        it[ApiMatches.dataJson] = canonical.dataJson
                        it[ApiMatches.updatedAt] = now
                    }
                }
            }
            MatchCanonical.deduplicateDatabaseForEvent(key)
        }
        return SyncCounts(teams.size, matches.size)
    }

    suspend fun syncStats(settings: ApiSettings): Int {
        val eventKey = settings.resolvedEventKey()
        if (eventKey.isBlank()) {
            return 0
        }
        val oprs = if (settings.useTbaOpr && settings.apiKeys.tbaKey.isNotBlank()) {
            fetchTbaOprs(settings, eventKey)
        } else {
            emptyMap()
        }
        val epas = if (settings.useStatboticsEpa) {
            fetchStatboticsEpas(settings, eventKey)
        } else {
            emptyMap()
        }
        val now = Instant.now()
        transaction {
            ApiTeams.select { ApiTeams.eventKey eq eventKey }.forEach { row ->
                val teamKey = row[ApiTeams.teamKey]
                val opr = oprs[teamKey]
                val epa = epas[teamKey]
                if (opr != null || epa != null) {
                    ApiTeams.update({ ApiTeams.id eq row[ApiTeams.id] }) {
                        it[ApiTeams.opr] = opr
                        it[ApiTeams.epa] = epa
                        it[ApiTeams.updatedAt] = now
                    }
                }
            }
        }
        return maxOf(oprs.size, epas.size)
    }

    fun listEvents(
        year: Int?,
        cachedOnly: Boolean = false,
        activeKey: String? = null,
        activeSettings: ApiSettings? = null
    ): List<EventRecord> {
        return transaction {
            val normalizedActiveKey = activeKey?.lowercase()?.trim() ?: ""
            val requiredKeys = mutableSetOf<String>()
            val activeMatchesYear = normalizedActiveKey.isNotBlank() && (year == null || normalizedActiveKey.startsWith(year.toString()))
            if (activeMatchesYear) {
                requiredKeys.add(normalizedActiveKey)
            }

            if (cachedOnly) {
                val teamKeys = ApiTeams.slice(ApiTeams.eventKey)
                    .selectAll()
                    .withDistinct()
                    .map { it[ApiTeams.eventKey].lowercase().trim() }
                val matchKeys = ApiMatches.slice(ApiMatches.eventKey)
                    .selectAll()
                    .withDistinct()
                    .map { it[ApiMatches.eventKey].lowercase().trim() }
                val manualKeys = ApiEvents.slice(ApiEvents.eventKey, ApiEvents.dataJson)
                    .selectAll()
                    .mapNotNull { row ->
                        val k = row[ApiEvents.eventKey].lowercase().trim()
                        val json = row[ApiEvents.dataJson]
                        if (json.contains("manual") || json == "{}" || json.isBlank()) {
                            k
                        } else {
                            null
                        }
                    }
                requiredKeys.addAll(teamKeys)
                requiredKeys.addAll(matchKeys)
                requiredKeys.addAll(manualKeys)
            }

            val query = ApiEvents.selectAll()
            if (year != null) {
                query.andWhere { ApiEvents.year eq year }
            }
            val events = query.orderBy(ApiEvents.startDate, SortOrder.ASC).map { row ->
                val storedCode = row[ApiEvents.eventCode]
                val computedKey = if (!storedCode.isNullOrBlank()) {
                    "${row[ApiEvents.year]}${storedCode}".lowercase()
                } else {
                    row[ApiEvents.eventKey]
                }
                EventRecord(
                    eventKey = computedKey.lowercase().trim(),
                    name = row[ApiEvents.name],
                    year = row[ApiEvents.year],
                    eventCode = storedCode,
                    startDate = row[ApiEvents.startDate],
                    endDate = row[ApiEvents.endDate],
                    timezone = row[ApiEvents.timezone]
                )
            }.toMutableList()

            // Ensure all required/cached keys are represented in events (add fallback EventRecords if missing)
            val existingKeys = events.map { it.eventKey }.toSet()
            requiredKeys.forEach { key ->
                if (!existingKeys.contains(key)) {
                    val detectedYear = if (key.length >= 4 && key.take(4).all { it.isDigit() }) {
                        key.take(4).toIntOrNull()
                    } else null
                    
                    if (year == null || detectedYear == year) {
                        val eventYear = detectedYear ?: year ?: activeSettings?.year ?: java.time.Year.now().value
                        val eventCode = key.removePrefix(eventYear.toString())
                        
                        val isSelfActive = key == normalizedActiveKey
                        val recordName = if (isSelfActive) {
                            "Configured Event: $key"
                        } else {
                            "Event: $key"
                        }
                        val recordTimezone = if (isSelfActive) {
                            activeSettings?.timezone ?: "America/New_York"
                        } else {
                            "America/New_York"
                        }

                        events.add(
                            EventRecord(
                                eventKey = key,
                                name = recordName,
                                year = eventYear,
                                eventCode = eventCode,
                                startDate = null,
                                endDate = null,
                                timezone = recordTimezone
                            )
                        )
                    }
                }
            }

            if (cachedOnly) {
                events.filter { requiredKeys.contains(it.eventKey) }
            } else {
                events
            }
        }
    }

    fun listTeams(eventKey: String): List<TeamRecord> {
        return transaction {
            val bbotMappings = getBBotMappings(eventKey)
            val placeholderToBBot = bbotMappings.associate { it.placeholderKey.lowercase().trim() to it.bbotKey }

            val rows = ApiTeams.select { ApiTeams.eventKey eq eventKey }
                .orderBy(ApiTeams.teamNumber, SortOrder.ASC)
                .map { row ->
                    val originalKey = row[ApiTeams.teamKey].lowercase().trim()
                    val resolvedKey = placeholderToBBot[originalKey] ?: row[ApiTeams.teamKey]

                    TeamRecord(
                        eventKey = row[ApiTeams.eventKey],
                        teamKey = resolvedKey,
                        teamNumber = row[ApiTeams.teamNumber],
                        name = row[ApiTeams.name],
                        nickname = row[ApiTeams.nickname],
                        city = row[ApiTeams.city],
                        state = row[ApiTeams.state],
                        country = row[ApiTeams.country],
                        opr = row[ApiTeams.opr],
                        epa = row[ApiTeams.epa]
                    )
                }

            rows.groupBy { it.teamNumber }
                .map { (teamNumber, group) ->
                    if (group.size == 1) {
                        group.first()
                    } else {
                        val preferred = group.find { it.teamKey.removePrefix("frc").any { c -> !c.isDigit() } }
                            ?: group.first()
                        preferred.copy(
                            name = group.mapNotNull { it.name }.firstOrNull { it.isNotBlank() } ?: preferred.name,
                            nickname = group.mapNotNull { it.nickname }.firstOrNull { it.isNotBlank() } ?: preferred.nickname,
                            city = group.mapNotNull { it.city }.firstOrNull { it.isNotBlank() } ?: preferred.city,
                            state = group.mapNotNull { it.state }.firstOrNull { it.isNotBlank() } ?: preferred.state,
                            country = group.mapNotNull { it.country }.firstOrNull { it.isNotBlank() } ?: preferred.country,
                            opr = group.mapNotNull { it.opr }.firstOrNull() ?: preferred.opr,
                            epa = group.mapNotNull { it.epa }.firstOrNull() ?: preferred.epa
                        )
                    }
                }
                .sortedBy { it.teamNumber }
        }
    }

    fun listMatches(eventKey: String): List<MatchRecord> {
        return transaction {
            MatchCanonical.deduplicateDatabaseForEvent(eventKey)
            val allTeams = ApiTeams.select { ApiTeams.eventKey eq eventKey.lowercase() }.toList()
            val bbotMappings = getBBotMappings(eventKey)
            
            // Build bidirectional resolution maps for B-bots and normal teams
            val teamKeyByNumber = mutableMapOf<Int, String>()
            val teamNumberByKey = mutableMapOf<String, Int>()
            val canonicalKeyByKey = mutableMapOf<String, String>()

            // First populate from B-bot mappings
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

            // Then populate other teams
            allTeams.forEach { row ->
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

            val rows = ApiMatches.select { ApiMatches.eventKey eq eventKey.lowercase() }.toList()
            rows.sortedWith(
                compareBy(
                    { compLevelRank(it[ApiMatches.compLevel]) },
                    { it[ApiMatches.setNumber] ?: 0 },
                    { it[ApiMatches.matchNumber] ?: 0 },
                    { it[ApiMatches.scheduledTime] ?: Long.MAX_VALUE },
                    { it[ApiMatches.matchKey] }
                )
            ).map { row ->
                val compLevel = row[ApiMatches.compLevel]
                val setNumber = row[ApiMatches.setNumber]
                val matchNumber = row[ApiMatches.matchNumber]
                val resolveTeams = { teamKeysJson: String ->
                    val rawKeys = decodeTeams(teamKeysJson)
                    rawKeys.map { key ->
                        val trimmedKey = key.trim().lowercase()
                        val canonicalKey = canonicalKeyByKey[trimmedKey] 
                            ?: (if (trimmedKey.startsWith("frc")) trimmedKey else "frc$trimmedKey")
                        
                        val num = teamNumberByKey[trimmedKey]
                            ?: trimmedKey.removePrefix("frc").toIntOrNull()
                        
                        if (num != null && canonicalKey.removePrefix("frc").lowercase() != num.toString()) {
                            val cleanCanonical = if (canonicalKey.startsWith("frc")) canonicalKey else "frc$canonicalKey"
                            "$cleanCanonical/$num"
                        } else {
                            if (num != null && canonicalKey != trimmedKey) {
                                val cleanCanonical = if (canonicalKey.startsWith("frc")) canonicalKey else "frc$canonicalKey"
                                "$cleanCanonical/$num"
                            } else {
                                key
                            }
                        }
                    }
                }
                MatchRecord(
                    matchKey = row[ApiMatches.matchKey],
                    eventKey = row[ApiMatches.eventKey],
                    compLevel = compLevel,
                    setNumber = setNumber,
                    matchNumber = matchNumber,
                    scheduledTime = row[ApiMatches.scheduledTime],
                    actualTime = row[ApiMatches.actualTime],
                    redTeams = resolveTeams(row[ApiMatches.redTeams]),
                    blueTeams = resolveTeams(row[ApiMatches.blueTeams]),
                    label = MatchCanonical.displayLabel(compLevel, setNumber, matchNumber)
                )
            }
        }
    }

    data class BBotMapping(
        val bbotKey: String,
        val placeholderKey: String,
        val placeholderNumber: Int
    )

    fun getBBotMappings(eventKey: String): List<BBotMapping> {
        return transaction {
            val allTeams = ApiTeams.select { ApiTeams.eventKey eq eventKey.lowercase() }.toList()
            val allMatches = ApiMatches.select { ApiMatches.eventKey eq eventKey.lowercase() }.toList()

            val bbotKeysInMatches = mutableSetOf<String>()
            allMatches.forEach { row ->
                val red = decodeTeams(row[ApiMatches.redTeams])
                val blue = decodeTeams(row[ApiMatches.blueTeams])
                (red + blue).forEach { key ->
                    val trimmed = key.trim().lowercase()
                    if (trimmed.removePrefix("frc").any { it.isLetter() }) {
                        bbotKeysInMatches.add(trimmed)
                    }
                }
            }

            val mappings = mutableListOf<BBotMapping>()
            val mappedPlaceholders = mutableSetOf<String>()
            val mappedBBots = mutableSetOf<String>()

            // 1. Map by nickname if nickname matches B-bot pattern (e.g., "254B")
            allTeams.forEach { row ->
                val origKey = row[ApiTeams.teamKey].lowercase().trim()
                val num = row[ApiTeams.teamNumber]
                val nick = (row[ApiTeams.nickname] ?: "").trim()
                if (origKey.removePrefix("frc").all { it.isDigit() } &&
                    nick.matches(Regex("^[0-9]+[a-zA-Z]$"))) {
                    val bbotKey = "frc" + nick.lowercase()
                    mappings.add(BBotMapping(bbotKey, origKey, num))
                    mappedPlaceholders.add(origKey)
                    mappedBBots.add(bbotKey)
                }
            }

            // 2. Map remaining by sorted order
            val unmappedPlaceholders = allTeams
                .filter { (it[ApiTeams.teamNumber] >= 9900 || it[ApiTeams.teamKey].removePrefix("frc").startsWith("99")) &&
                    !mappedPlaceholders.contains(it[ApiTeams.teamKey].lowercase().trim()) }
                .sortedBy { it[ApiTeams.teamKey] }

            val unmappedBBots = bbotKeysInMatches
                .filter { !mappedBBots.contains(it) }
                .sorted()

            val count = minOf(unmappedPlaceholders.size, unmappedBBots.size)
            for (i in 0 until count) {
                val placeholder = unmappedPlaceholders[i]
                val bbotKey = unmappedBBots[i]
                mappings.add(
                    BBotMapping(
                        bbotKey = bbotKey,
                        placeholderKey = placeholder[ApiTeams.teamKey].lowercase().trim(),
                        placeholderNumber = placeholder[ApiTeams.teamNumber]
                    )
                )
            }

            mappings
        }
    }

    fun summary(): SummaryResponse {
        return transaction {
            SummaryResponse(
                entries = ScoutingEntries.selectAll().count().toInt(),
                events = ApiEvents.selectAll().count().toInt(),
                teams = ApiTeams.selectAll().count().toInt(),
                matches = ApiMatches.selectAll().count().toInt()
            )
        }
    }

    fun getEvent(eventKey: String): EventRecord? {
        return transaction {
            val key = eventKey.lowercase().trim()
            ApiEvents.select { ApiEvents.eventKey eq key }.limit(1).map { row ->
                val storedCode = row[ApiEvents.eventCode]
                val computedKey = if (!storedCode.isNullOrBlank()) {
                    "${row[ApiEvents.year]}${storedCode}".lowercase()
                } else {
                    row[ApiEvents.eventKey]
                }
                EventRecord(
                    eventKey = computedKey,
                    name = row[ApiEvents.name],
                    year = row[ApiEvents.year],
                    eventCode = storedCode,
                    startDate = row[ApiEvents.startDate],
                    endDate = row[ApiEvents.endDate],
                    timezone = row[ApiEvents.timezone]
                )
            }.firstOrNull()
        }
    }

    fun saveEvent(event: EventRecord): EventRecord {
        return transaction {
            val key = event.eventKey.lowercase().trim()
            val existing = ApiEvents.select { ApiEvents.eventKey eq key }.limit(1).firstOrNull()
            val now = Instant.now()
            if (existing == null) {
                ApiEvents.insert {
                    it[eventKey] = key
                    it[year] = event.year
                    it[eventCode] = event.eventCode ?: key.removePrefix(event.year.toString())
                    it[name] = event.name
                    it[startDate] = event.startDate
                    it[endDate] = event.endDate
                    it[timezone] = event.timezone
                    it[dataJson] = "{\"manual\":true}"
                    it[updatedAt] = now
                }
            } else {
                ApiEvents.update({ ApiEvents.id eq existing[ApiEvents.id] }) {
                    it[year] = event.year
                    it[eventCode] = event.eventCode ?: key.removePrefix(event.year.toString())
                    it[name] = event.name
                    it[startDate] = event.startDate
                    it[endDate] = event.endDate
                    it[timezone] = event.timezone
                    it[dataJson] = "{\"manual\":true}"
                    it[updatedAt] = now
                }
            }
            event.copy(eventKey = key)
        }
    }

    fun saveTeam(team: TeamRecord): TeamRecord {
        return transaction {
            val eventKey = team.eventKey.lowercase().trim()
            val teamKey = team.teamKey.ifBlank { "frc${team.teamNumber}" }.lowercase().trim()
            val existing = ApiTeams.select {
                (ApiTeams.eventKey eq eventKey) and (ApiTeams.teamKey eq teamKey)
            }.limit(1).firstOrNull()
            val now = Instant.now()
            if (existing == null) {
                ApiTeams.insert {
                    it[ApiTeams.eventKey] = eventKey
                    it[ApiTeams.teamKey] = teamKey
                    it[teamNumber] = team.teamNumber
                    it[name] = team.name ?: ""
                    it[nickname] = team.nickname ?: ""
                    it[city] = team.city
                    it[state] = team.state
                    it[country] = team.country
                    it[opr] = team.opr
                    it[epa] = team.epa
                    it[dataJson] = "{}"
                    it[updatedAt] = now
                }
            } else {
                ApiTeams.update({ ApiTeams.id eq existing[ApiTeams.id] }) {
                    it[teamNumber] = team.teamNumber
                    it[name] = team.name ?: ""
                    it[nickname] = team.nickname ?: ""
                    it[city] = team.city
                    it[state] = team.state
                    it[country] = team.country
                    it[opr] = team.opr
                    it[epa] = team.epa
                    it[updatedAt] = now
                }
            }
            team.copy(eventKey = eventKey, teamKey = teamKey)
        }
    }

    fun saveMatch(match: MatchRecord): MatchRecord {
        return transaction {
            val eventKey = match.eventKey.lowercase().trim()
            val compLevel = match.compLevel.ifBlank { "qm" }.lowercase().trim()
            val setNumber = match.setNumber ?: 1
            val matchNumber = match.matchNumber ?: 1
            val generatedKey = match.matchKey.ifBlank {
                "${eventKey}_${compLevel}_s${setNumber}_m${matchNumber}".lowercase()
            }.lowercase().trim()
            
            // Normalize team lists to prefix with frc
            val normalizedRed = match.redTeams.map { key ->
                val trimmed = key.trim().lowercase()
                when {
                    trimmed.contains('/') -> {
                        val parts = trimmed.split('/')
                        val num = parts.last().trim().removePrefix("frc")
                        "frc$num"
                    }
                    trimmed.startsWith("frc") -> trimmed
                    trimmed.all { it.isDigit() } -> "frc$trimmed"
                    else -> trimmed
                }
            }
            val normalizedBlue = match.blueTeams.map { key ->
                val trimmed = key.trim().lowercase()
                when {
                    trimmed.contains('/') -> {
                        val parts = trimmed.split('/')
                        val num = parts.last().trim().removePrefix("frc")
                        "frc$num"
                    }
                    trimmed.startsWith("frc") -> trimmed
                    trimmed.all { it.isDigit() } -> "frc$trimmed"
                    else -> trimmed
                }
            }

            val existing = ApiMatches.select { ApiMatches.matchKey eq generatedKey }.limit(1).firstOrNull()
            val now = Instant.now()
            val redTeamsJson = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), normalizedRed)
            val blueTeamsJson = JsonSupport.json.encodeToString(ListSerializer(String.serializer()), normalizedBlue)
            if (existing == null) {
                ApiMatches.insert {
                    it[matchKey] = generatedKey
                    it[ApiMatches.eventKey] = eventKey
                    it[ApiMatches.compLevel] = compLevel
                    it[ApiMatches.setNumber] = setNumber
                    it[ApiMatches.matchNumber] = matchNumber
                    it[scheduledTime] = match.scheduledTime
                    it[ApiMatches.actualTime] = match.actualTime
                    it[redTeams] = redTeamsJson
                    it[blueTeams] = blueTeamsJson
                    it[dataJson] = "{}"
                    it[updatedAt] = now
                }
            } else {
                ApiMatches.update({ ApiMatches.id eq existing[ApiMatches.id] }) {
                    it[ApiMatches.eventKey] = eventKey
                    it[ApiMatches.compLevel] = compLevel
                    it[ApiMatches.setNumber] = setNumber
                    it[ApiMatches.matchNumber] = matchNumber
                    it[scheduledTime] = match.scheduledTime
                    it[ApiMatches.actualTime] = match.actualTime
                    it[redTeams] = redTeamsJson
                    it[blueTeams] = blueTeamsJson
                    it[updatedAt] = now
                }
            }
            match.copy(
                matchKey = generatedKey,
                eventKey = eventKey,
                compLevel = compLevel,
                setNumber = setNumber,
                matchNumber = matchNumber,
                scheduledTime = match.scheduledTime,
                actualTime = match.actualTime,
                redTeams = normalizedRed,
                blueTeams = normalizedBlue
            )
        }
    }

    fun deleteEvent(eventKey: String): Boolean {
        return transaction {
            val key = eventKey.lowercase().trim()
            ScoutingEntries.deleteWhere { ScoutingEntries.eventKey eq key }
            PitScoutingEntries.deleteWhere { PitScoutingEntries.eventKey eq key }
            ApiTeams.deleteWhere { ApiTeams.eventKey eq key }
            ApiMatches.deleteWhere { ApiMatches.eventKey eq key }
            val count = ApiEvents.deleteWhere { ApiEvents.eventKey eq key }
            count > 0
        }
    }

    fun deleteTeam(eventKey: String, teamKey: String): Boolean {
        return transaction {
            val eKey = eventKey.lowercase().trim()
            val tKey = teamKey.lowercase().trim()
            val count = ApiTeams.deleteWhere { (ApiTeams.eventKey eq eKey) and (ApiTeams.teamKey eq tKey) }
            count > 0
        }
    }

    fun deleteMatch(matchKey: String): Boolean {
        return transaction {
            val mKey = matchKey.lowercase().trim()
            val count = ApiMatches.deleteWhere { ApiMatches.matchKey eq mKey }
            count > 0
        }
    }

    private fun decodeTeams(text: String): List<String> {
        return JsonSupport.json.decodeFromString(ListSerializer(String.serializer()), text)
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

    private fun hasFirstCredentials(settings: ApiSettings): Boolean {
        return settings.apiKeys.firstUsername.isNotBlank() && settings.apiKeys.firstKey.isNotBlank()
    }

    private fun hasTbaCredentials(settings: ApiSettings): Boolean {
        return settings.apiKeys.tbaKey.isNotBlank()
    }

    private suspend fun fetchMergedEvents(settings: ApiSettings): List<EventSyncRecord> {
        val events = mutableListOf<EventSyncRecord>()
        if (hasTbaCredentials(settings)) {
            events.addAll(fetchTbaEvents(settings))
        }
        if (hasFirstCredentials(settings)) {
            events.addAll(fetchFirstEvents(settings))
        }
        if (events.isEmpty()) {
            when (settings.preferredSource) {
                "first" -> events.addAll(fetchFirstEvents(settings))
                else -> events.addAll(fetchTbaEvents(settings))
            }
        }
        return events
            .map { it.copy(eventKey = it.eventKey.lowercase()) }
            .distinctBy { it.eventKey }
    }

    private suspend fun fetchMergedTeams(settings: ApiSettings, eventKey: String): List<TeamSyncRecord> {
        val teams = mutableListOf<TeamSyncRecord>()
        if (hasTbaCredentials(settings)) {
            teams.addAll(fetchTbaTeams(settings, eventKey))
        }
        if (hasFirstCredentials(settings)) {
            teams.addAll(fetchFirstTeams(settings, eventKey))
        }
        if (teams.isEmpty()) {
            when (settings.preferredSource) {
                "first" -> teams.addAll(fetchFirstTeams(settings, eventKey))
                else -> teams.addAll(fetchTbaTeams(settings, eventKey))
            }
        }
        return teams
            .groupBy { "${it.eventKey.lowercase()}_${it.teamKey.lowercase()}" }
            .map { (_, group) -> group.maxByOrNull { it.dataJson.length } ?: group.first() }
    }

    private suspend fun fetchMergedMatches(settings: ApiSettings, eventKey: String): List<MatchSyncRecord> {
        val matches = mutableListOf<MatchSyncRecord>()
        if (hasTbaCredentials(settings)) {
            matches.addAll(fetchTbaMatches(settings, eventKey).map { it.copy(source = "tba") })
        }
        if (hasFirstCredentials(settings)) {
            matches.addAll(fetchFirstMatches(settings, eventKey).map { it.copy(source = "first") })
        }
        if (matches.isEmpty()) {
            when (settings.preferredSource) {
                "first" -> matches.addAll(fetchFirstMatches(settings, eventKey).map { it.copy(source = "first") })
                else -> matches.addAll(fetchTbaMatches(settings, eventKey).map { it.copy(source = "tba") })
            }
        }
        return MatchCanonical.mergeAll(matches)
    }

    private suspend fun upsertEventRecord(settings: ApiSettings, eventKey: String) {
        val event = fetchTbaEventDetail(settings, eventKey)
            ?: fetchFirstEventDetail(settings, eventKey)
            ?: EventSyncRecord(
            eventKey = eventKey,
            year = settings.year,
            eventCode = settings.eventCode.ifBlank { eventKey.removePrefix(settings.year.toString()) },
            name = eventKey,
            startDate = null,
            endDate = null,
            timezone = settings.timezone,
            dataJson = "{}"
        )
        val now = Instant.now()
        transaction {
            upsertEvent(event, now)
        }
    }

    private fun upsertEvent(event: EventSyncRecord, now: Instant) {
        val resolvedCode = resolveEventCode(event).clipEventCode()
        val resolvedKey = resolveEventKey(event, resolvedCode)
        val clippedName = event.name.clipEventName()
        val clippedStart = event.startDate.clipEventDate()
        val clippedEnd = event.endDate.clipEventDate()
        val clippedTimezone = event.timezone.clipTimezone()
        val existing = ApiEvents.select { ApiEvents.eventKey eq resolvedKey }.limit(1).firstOrNull()
        if (existing == null) {
            ApiEvents.insert {
                it[ApiEvents.eventKey] = resolvedKey
                it[year] = event.year
                it[eventCode] = resolvedCode
                it[name] = clippedName
                it[startDate] = clippedStart
                it[endDate] = clippedEnd
                it[timezone] = clippedTimezone
                it[dataJson] = event.dataJson
                it[updatedAt] = now
            }
        } else {
            ApiEvents.update({ ApiEvents.id eq existing[ApiEvents.id] }) {
                it[ApiEvents.eventKey] = resolvedKey
                it[year] = event.year
                it[eventCode] = resolvedCode
                it[name] = clippedName
                it[startDate] = clippedStart
                it[endDate] = clippedEnd
                it[timezone] = clippedTimezone
                it[dataJson] = event.dataJson
                it[updatedAt] = now
            }
        }
    }

    private suspend fun fetchTbaEventDetail(settings: ApiSettings, eventKey: String): EventSyncRecord? {
        val key = settings.apiKeys.tbaKey
        if (key.isBlank()) {
            return null
        }
        val normalizedKey = eventKey.lowercase()
        val url = "https://www.thebluealliance.com/api/v3/event/$normalizedKey"
        return try {
            val element = client.get(url) {
                header("X-TBA-Auth-Key", key)
                header(HttpHeaders.Accept, "application/json")
            }.body<JsonElement>()
            val obj = element.jsonObject
            EventSyncRecord(
                eventKey = normalizedKey,
                year = obj.readInt("year") ?: settings.year,
                eventCode = obj.readString("event_code") ?: settings.eventCode,
                name = obj.readString("name") ?: normalizedKey,
                startDate = obj.readString("start_date"),
                endDate = obj.readString("end_date"),
                timezone = obj.readString("timezone"),
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), element)
            )
        } catch (error: Exception) {
            log.warn("TBA event fetch failed for $normalizedKey: ${error.message}")
            null
        }
    }

    private suspend fun fetchFirstEventDetail(settings: ApiSettings, eventKey: String): EventSyncRecord? {
        return fetchFirstEvents(settings).firstOrNull { it.eventKey.equals(eventKey, ignoreCase = true) }
    }

    private suspend fun fetchTbaEvents(settings: ApiSettings): List<EventSyncRecord> {
        val key = settings.apiKeys.tbaKey
        if (key.isBlank()) {
            return emptyList()
        }
        val url = "https://www.thebluealliance.com/api/v3/events/${settings.year}"
        val element = client.get(url) {
            header("X-TBA-Auth-Key", key)
        }.body<JsonElement>()
        val events = element.jsonArray
        return events.mapNotNull { item ->
            val obj = item.jsonObject
            val eventKey = obj.readString("key") ?: return@mapNotNull null
            EventSyncRecord(
                eventKey = eventKey,
                year = obj.readInt("year") ?: settings.year,
                eventCode = obj.readString("event_code"),
                name = obj.readString("name") ?: eventKey,
                startDate = obj.readString("start_date"),
                endDate = obj.readString("end_date"),
                timezone = obj.readString("timezone"),
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item)
            )
        }
    }

    private suspend fun fetchTbaTeams(settings: ApiSettings, eventKey: String): List<TeamSyncRecord> {
        val key = settings.apiKeys.tbaKey
        if (key.isBlank()) {
            return emptyList()
        }
        val normalizedKey = eventKey.lowercase()
        val url = "https://www.thebluealliance.com/api/v3/event/${normalizedKey}/teams/simple"
        val element = client.get(url) {
            header("X-TBA-Auth-Key", key)
            header(HttpHeaders.Accept, "application/json")
        }.body<JsonElement>()
        return element.jsonArray.mapNotNull { item ->
            val obj = item.jsonObject
            val teamKey = obj.readString("key") ?: return@mapNotNull null
            TeamSyncRecord(
                eventKey = normalizedKey,
                teamKey = teamKey,
                teamNumber = obj.readInt("team_number") ?: 0,
                name = obj.readString("name"),
                nickname = obj.readString("nickname"),
                city = obj.readString("city"),
                state = obj.readString("state_prov"),
                country = obj.readString("country"),
                opr = null,
                epa = null,
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item)
            )
        }
    }

    private suspend fun fetchTbaMatches(settings: ApiSettings, eventKey: String): List<MatchSyncRecord> {
        val key = settings.apiKeys.tbaKey
        if (key.isBlank()) {
            return emptyList()
        }
        val normalizedKey = eventKey.lowercase()
        val url = "https://www.thebluealliance.com/api/v3/event/${normalizedKey}/matches"
        val element = client.get(url) {
            header("X-TBA-Auth-Key", key)
            header(HttpHeaders.Accept, "application/json")
        }.body<JsonElement>()
        return element.jsonArray.mapNotNull { item ->
            val obj = item.jsonObject
            val matchKey = obj.readString("key") ?: return@mapNotNull null
            val alliances = obj["alliances"]?.jsonObject
            val redTeams = alliances?.get("red").teamKeys()
            val blueTeams = alliances?.get("blue").teamKeys()
            val scheduledTime = obj.readLong("time")
            val tbaActual = obj.readLong("actual_time")
            val actualTime = if (tbaActual != null && tbaActual > 0) tbaActual else scheduledTime
            MatchSyncRecord(
                matchKey = matchKey,
                eventKey = normalizedKey,
                compLevel = obj.readString("comp_level") ?: "",
                setNumber = obj.readInt("set_number"),
                matchNumber = obj.readInt("match_number"),
                scheduledTime = scheduledTime,
                actualTime = actualTime,
                redTeams = redTeams,
                blueTeams = blueTeams,
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item),
                source = "tba"
            )
        }
    }

    private suspend fun fetchTbaOprs(settings: ApiSettings, eventKey: String): Map<String, Double> {
        val key = settings.apiKeys.tbaKey
        if (key.isBlank()) {
            return emptyMap()
        }
        val url = "https://www.thebluealliance.com/api/v3/event/${eventKey}/oprs"
        val element = client.get(url) {
            header("X-TBA-Auth-Key", key)
        }.body<JsonElement>()
        val oprs = element.jsonObject["oprs"] as? JsonObject ?: return emptyMap()
        return oprs.entries.associate { entry ->
            val primitive = entry.value as? JsonPrimitive
            entry.key to primitive?.content?.toDoubleOrNull().orZero()
        }
    }

    private suspend fun fetchStatboticsEpas(settings: ApiSettings, eventKey: String): Map<String, Double> {
        val url = "https://api.statbotics.io/v3/event/${eventKey}/teams"
        val key = settings.apiKeys.statboticsKey
        val responseText = try {
            client.get(url) {
                if (key.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $key")
                }
            }.bodyAsText()
        } catch (error: Exception) {
            log.warn("Statbotics fetch failed: ${error.message}")
            if (key.isNotBlank()) {
                try {
                    client.get(url).bodyAsText()
                } catch (inner: Exception) {
                    log.warn("Statbotics fallback fetch failed: ${inner.message}")
                    return emptyMap()
                }
            } else {
                return emptyMap()
            }
        }
        if (responseText.isBlank()) {
            return emptyMap()
        }
        return try {
            val element = JsonSupport.json.parseToJsonElement(responseText)
            val array = element.jsonArray
            array.mapNotNull { item ->
                val obj = item.jsonObject
                val team = obj.readInt("team") ?: return@mapNotNull null
                val epa = obj.readDouble("epa") ?: return@mapNotNull null
                "frc${team}" to epa
            }.toMap()
        } catch (e: Exception) {
            log.warn("Failed to parse Statbotics response: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun fetchFirstEvents(settings: ApiSettings): List<EventSyncRecord> {
        val root = fetchFirstJson(settings, "events")
        val array = root.findArray(listOf("Events", "events"))
        return array.mapNotNull { item ->
            val obj = item.jsonObject
            val eventCode = obj.readString("code") ?: obj.readString("eventCode")
            val eventKey = if (!eventCode.isNullOrBlank()) {
                "${settings.year}${eventCode.trim().lowercase()}"
            } else {
                return@mapNotNull null
            }
            EventSyncRecord(
                eventKey = eventKey,
                year = settings.year,
                eventCode = eventCode,
                name = obj.readString("name") ?: eventKey,
                startDate = obj.readString("dateStart") ?: obj.readString("startDate"),
                endDate = obj.readString("dateEnd") ?: obj.readString("endDate"),
                timezone = obj.readString("timezone"),
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item)
            )
        }
    }

    private suspend fun fetchFirstTeams(settings: ApiSettings, eventKey: String): List<TeamSyncRecord> {
        val eventCode = settings.firstEventCode(eventKey)
        val root = fetchFirstJson(settings, "teams?eventCode=$eventCode")
        val array = root.findArray(listOf("Teams", "teams"))
        return array.mapNotNull { item ->
            val obj = item.jsonObject
            val teamNumber = obj.readInt("teamNumber") ?: obj.readInt("team_number") ?: return@mapNotNull null
            TeamSyncRecord(
                eventKey = eventKey,
                teamKey = "frc${teamNumber}",
                teamNumber = teamNumber,
                name = obj.readString("name") ?: obj.readString("nameFull"),
                nickname = obj.readString("nickname") ?: obj.readString("nameShort"),
                city = obj.readString("city"),
                state = obj.readString("stateProv") ?: obj.readString("state"),
                country = obj.readString("country"),
                opr = null,
                epa = null,
                dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item)
            )
        }
    }

    private suspend fun fetchFirstMatches(settings: ApiSettings, eventKey: String): List<MatchSyncRecord> {
        val eventCode = settings.firstEventCode(eventKey)
        if (eventCode.isBlank()) {
            return emptyList()
        }

        val normalizedEventKey = eventKey.lowercase()
        val matchLevels = listOf(
            "qual" to "Qualification",
            "playoff" to "Playoff"
        )
        val scheduleLevels = listOf(
            "practice" to "Practice",
            "qual" to "Qualification",
            "playoff" to "Playoff"
        )
        val results = linkedMapOf<String, MatchSyncRecord>()

        fun upsert(record: MatchSyncRecord) {
            val existing = results[record.matchKey]
            results[record.matchKey] = if (existing == null) {
                record
            } else {
                MatchCanonical.mergeRecords(existing, record)
            }
        }

        for ((apiLevel, defaultLevel) in matchLevels) {
            val matchesRoot = fetchFirstJson(settings, "matches/$eventCode?tournamentLevel=$apiLevel")
            parseFirstMatchItems(matchesRoot, normalizedEventKey, defaultLevel).forEach { upsert(it) }
        }

        for ((apiLevel, defaultLevel) in scheduleLevels) {
            val scheduleRoot = fetchFirstJson(settings, "schedule/$eventCode?tournamentLevel=$apiLevel")
            parseFirstMatchItems(scheduleRoot, normalizedEventKey, defaultLevel).forEach { upsert(it) }
        }

        if (results.isEmpty()) {
            parseFirstMatchItems(fetchFirstJson(settings, "matches/$eventCode"), normalizedEventKey, "")
                .forEach { upsert(it) }
        } else {
            val fallbackMatches = parseFirstMatchItems(fetchFirstJson(settings, "matches/$eventCode"), normalizedEventKey, "")
            fallbackMatches
                .filter { MatchCanonical.normalizeCompLevel(it.compLevel) == "practice" }
                .forEach { upsert(it) }
        }

        if (results.isNotEmpty()) {
            log.info("FIRST match sync for $eventCode: ${results.size} matches")
        } else {
            log.warn("FIRST match sync for $eventCode returned no matches")
        }
        return results.values.toList()
    }

    /**
     * Fetches JSON from the FIRST API. Handles non-JSON error responses gracefully
     * by returning an empty JsonArray instead of crashing.
     */
    private suspend fun fetchFirstJson(settings: ApiSettings, path: String): JsonElement {
        val user = settings.apiKeys.firstUsername
        val key = settings.apiKeys.firstKey
        if (user.isBlank() || key.isBlank()) {
            return JsonArray(emptyList())
        }
        val token = Base64.getEncoder().encodeToString("$user:$key".toByteArray())
        val url = "https://frc-api.firstinspires.org/v3.0/${settings.year}/$path"
        try {
            val response = client.get(url) {
                header(HttpHeaders.Authorization, "Basic $token")
                header(HttpHeaders.Accept, "application/json")
            }

            // Check HTTP status before attempting to parse
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                log.warn("FIRST API returned ${response.status} for $path: $errorBody")
                return JsonArray(emptyList())
            }

            val responseText = response.bodyAsText()
            if (responseText.isBlank()) {
                return JsonArray(emptyList())
            }

            return JsonSupport.json.parseToJsonElement(responseText)
        } catch (e: Exception) {
            log.warn("FIRST API request failed for $path: ${e.message}")
            return JsonArray(emptyList())
        }
    }
}

@kotlinx.serialization.Serializable
data class SyncCounts(
    val teams: Int,
    val matches: Int
)

private data class EventSyncRecord(
    val eventKey: String,
    val year: Int,
    val eventCode: String?,
    val name: String,
    val startDate: String?,
    val endDate: String?,
    val timezone: String?,
    val dataJson: String
)

private data class TeamSyncRecord(
    val eventKey: String,
    val teamKey: String,
    val teamNumber: Int,
    val name: String?,
    val nickname: String?,
    val city: String?,
    val state: String?,
    val country: String?,
    val opr: Double?,
    val epa: Double?,
    val dataJson: String
)

private fun JsonObject.readString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.content
}

private fun JsonObject.readInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.readLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonObject.readDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonElement?.teamKeys(): List<String> {
    val obj = this as? JsonObject ?: return emptyList()
    val array = obj["team_keys"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.content }
}

private fun JsonObject.readTeamList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> {
                val asInt = item.content.toIntOrNull()
                if (asInt != null) {
                    "frc$asInt"
                } else {
                    item.content.takeIf { it.isNotBlank() }
                }
            }
            is JsonObject -> {
                val teamKey = item.readString("teamKey") ?: item.readString("team_key")
                if (!teamKey.isNullOrBlank()) {
                    teamKey
                } else {
                    val teamNumber = item.readInt("teamNumber") ?: item.readInt("team") ?: item.readInt("team_number")
                    teamNumber?.let { "frc$it" }
                }
            }
            else -> null
        }
    }
}

private fun JsonObject.readTeamsByStation(): Pair<List<String>, List<String>> {
    val array = this["teams"] as? JsonArray ?: return emptyList<String>() to emptyList()
    val red = mutableListOf<String>()
    val blue = mutableListOf<String>()
    array.forEach { item ->
        val obj = item as? JsonObject ?: return@forEach
        val station = obj.readString("station") ?: obj.readString("Station") ?: ""
        val teamNumber = obj.readInt("teamNumber") ?: obj.readInt("team") ?: obj.readInt("team_number")
        val teamKey = obj.readString("teamKey") ?: obj.readString("team_key") ?: teamNumber?.let { "frc$it" }
        if (teamKey.isNullOrBlank()) {
            return@forEach
        }
        when {
            station.lowercase().startsWith("red") -> red.add(teamKey)
            station.lowercase().startsWith("blue") -> blue.add(teamKey)
            red.size < 3 -> red.add(teamKey)
            else -> blue.add(teamKey)
        }
    }
    return red to blue
}

private fun ApiSettings.firstEventCode(eventKey: String): String {
    val trimmed = eventCode.trim()
    if (trimmed.isNotBlank()) {
        return trimmed.uppercase()
    }
    val prefix = year.toString()
    return if (eventKey.lowercase().startsWith(prefix)) {
        eventKey.drop(prefix.length).uppercase()
    } else {
        eventKey.uppercase()
    }
}

private fun parseFirstMatchItems(
    root: JsonElement,
    eventKey: String,
    defaultLevel: String
): List<MatchSyncRecord> {
    val array = root.findArray(listOf("Matches", "matches", "Schedule", "schedule", "MatchScores"))
    return array.mapIndexed { index, item ->
        val obj = item.jsonObject
        val matchNumber = obj.readInt("matchNumber") ?: obj.readInt("match_number")
        val setNumber = obj.readInt("setNumber") ?: obj.readInt("set_number")
        val rawLevel = (
            obj.readString("tournamentLevel")
                ?: obj.readString("compLevel")
                ?: defaultLevel
            ).ifBlank { defaultLevel }
        val setNum = setNumber ?: 1
        val matchNum = matchNumber ?: (index + 1)
        var compLevel = MatchCanonical.normalizeCompLevel(rawLevel)
        if (compLevel.isBlank()) {
            compLevel = MatchCanonical.normalizeCompLevel(defaultLevel)
        }
        if (compLevel == "playoff") {
            compLevel = MatchCanonical.playoffSetToCompLevel(setNum)
        }
        if (compLevel.isBlank()) {
            compLevel = "qm"
        }
        val matchKey = obj.readString("matchKey")
            ?: obj.readString("matchKeyShort")
            ?: "${eventKey}_${compLevel}_s${setNum}_m$matchNum"
        val scheduledTime = obj.readEpochSeconds("startTime")
            ?: obj.readEpochSeconds("time")
        val firstActual = obj.readEpochSeconds("actualStartTime")
        val actualTime = if (firstActual != null && firstActual > 0) firstActual else scheduledTime
        var redTeams = obj.readTeamList("redTeams")
        var blueTeams = obj.readTeamList("blueTeams")
        if (redTeams.isEmpty() && blueTeams.isEmpty()) {
            val alliances = obj.readFirstAlliances()
            redTeams = alliances.first
            blueTeams = alliances.second
        }
        if (redTeams.isEmpty() && blueTeams.isEmpty()) {
            val stationTeams = obj.readTeamsByStation()
            redTeams = stationTeams.first
            blueTeams = stationTeams.second
        }
        MatchSyncRecord(
            matchKey = matchKey.lowercase(),
            eventKey = eventKey,
            compLevel = compLevel,
            setNumber = setNum,
            matchNumber = matchNum,
            scheduledTime = scheduledTime,
            actualTime = actualTime,
            redTeams = redTeams,
            blueTeams = blueTeams,
            dataJson = JsonSupport.json.encodeToString(JsonElement.serializer(), item),
            source = "first"
        )
    }
}

private fun JsonObject.readFirstAlliances(): Pair<List<String>, List<String>> {
    val alliances = this["alliances"] as? JsonObject ?: return emptyList<String>() to emptyList()
    val red = alliances.readTeamList("red").ifEmpty { alliances.readTeamList("Red") }
    val blue = alliances.readTeamList("blue").ifEmpty { alliances.readTeamList("Blue") }
    if (red.isNotEmpty() || blue.isNotEmpty()) {
        return red to blue
    }
    val redAlliance = alliances["0"] as? JsonObject ?: alliances["red"] as? JsonObject
    val blueAlliance = alliances["1"] as? JsonObject ?: alliances["blue"] as? JsonObject
    val redTeams = redAlliance?.readTeamList("teamNumbers")
        ?: redAlliance?.readTeamList("teams")
        ?: emptyList()
    val blueTeams = blueAlliance?.readTeamList("teamNumbers")
        ?: blueAlliance?.readTeamList("teams")
        ?: emptyList()
    return redTeams to blueTeams
}

private fun JsonObject.readEpochSeconds(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    val content = primitive.content
    content.toLongOrNull()?.let { value ->
        return if (content.length >= 13) value / 1000 else value
    }
    return try {
        java.time.Instant.parse(content).epochSecond
    } catch (_: Exception) {
        null
    }
}

private fun JsonElement.findArray(keys: List<String>): JsonArray {
    return when (this) {
        is JsonArray -> this
        is JsonObject -> keys.firstNotNullOfOrNull { key -> this[key] as? JsonArray } ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
}

private fun Double?.orZero(): Double = this ?: 0.0

private const val EVENT_NAME_MAX = 160
private const val EVENT_CODE_MAX = 32
private const val EVENT_KEY_MAX = 64
private const val TEAM_NAME_MAX = 160
private const val TEAM_LOCATION_MAX = 80
private const val EVENT_DATE_MAX = 32
private const val TIMEZONE_MAX = 64

private fun resolveEventCode(event: EventSyncRecord): String? {
    val trimmed = event.eventCode?.trim()
    if (!trimmed.isNullOrBlank()) {
        return trimmed
    }
    val prefix = event.year.toString()
    return if (event.eventKey.startsWith(prefix)) {
        event.eventKey.drop(prefix.length)
    } else {
        null
    }
}

private fun resolveEventKey(event: EventSyncRecord, eventCode: String?): String {
    val computed = if (!eventCode.isNullOrBlank()) {
        "${event.year}${eventCode}".lowercase()
    } else {
        event.eventKey.lowercase()
    }
    return computed.take(EVENT_KEY_MAX)
}

private fun String.clipEventName(): String = take(EVENT_NAME_MAX)

private fun String?.clipTeamText(): String? = this?.take(TEAM_NAME_MAX)

private fun String?.clipTeamLocation(): String? = this?.take(TEAM_LOCATION_MAX)

private fun String?.clipEventCode(): String? = this?.trim()?.take(EVENT_CODE_MAX)

private fun String?.clipEventDate(): String? = this?.take(EVENT_DATE_MAX)

private fun String?.clipTimezone(): String? = this?.take(TIMEZONE_MAX)
