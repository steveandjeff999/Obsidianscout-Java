package com.obsidianscout.admin

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit

import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull

@Serializable
data class StorageCategoryBreakdown(
    val category: String,
    val description: String,
    val isApiCache: Boolean,
    val recordCount: Long,
    val estimatedBytes: Long
)

@Serializable
data class StorageOverviewDto(
    val databaseType: String,
    val isCockroach: Boolean,
    val totalPhysicalSizeBytes: Long,
    val totalEstimatedBytes: Long,
    val totalRecords: Long,
    val apiCacheBytes: Long,
    val apiCacheRecords: Long,
    val userScoutingBytes: Long,
    val userScoutingRecords: Long,
    val chatBytes: Long,
    val chatRecords: Long,
    val accountsBytes: Long,
    val accountsRecords: Long,
    val systemConfigBytes: Long,
    val systemConfigRecords: Long,
    val categories: List<StorageCategoryBreakdown>
)

@Serializable
data class EventCacheStorageDto(
    val eventKey: String,
    val name: String,
    val year: Int,
    val matchCount: Long,
    val teamCount: Long,
    val hasEpaHistory: Boolean,
    val cacheBytes: Long,
    val userScoutingEntryCount: Long,
    val lastUpdatedEpochMs: Long
)

@Serializable
data class TeamStorageUsageDto(
    val teamNumber: Int,
    val program: String,
    val matchEntryCount: Long = 0L,
    val matchBytes: Long = 0L,
    val pitEntryCount: Long = 0L,
    val pitBytes: Long = 0L,
    val qualEntryCount: Long = 0L,
    val qualBytes: Long = 0L,
    val configCount: Long = 0L,
    val configBytes: Long = 0L,
    val configRevisionCount: Long = 0L,
    val configRevisionBytes: Long = 0L,
    val reportCount: Long = 0L,
    val chatMessageCount: Long = 0L,
    val chatBytes: Long = 0L,
    val userCount: Long = 0L,
    val totalBytes: Long = 0L,
    val totalRecords: Long = 0L
)

@Serializable
data class TeamEventUsageDto(
    val eventKey: String,
    val matchCount: Long,
    val pitCount: Long,
    val qualCount: Long,
    val totalBytes: Long
)

@Serializable
data class TeamDetailStorageDto(
    val teamNumber: Int,
    val program: String,
    val summary: TeamStorageUsageDto,
    val events: List<TeamEventUsageDto>
)

@Serializable
data class StorageActionResultDto(
    val success: Boolean,
    val message: String,
    val affectedRecords: Long = 0,
    val freedBytesEstimate: Long = 0
)

object StorageManagementService {

    /**
     * Executes a raw SQL query and returns a mapped list using the provided ResultSet extractor.
     */
    private fun <T> executeQuery(sql: String, mapper: (ResultSet) -> T): List<T> {
        val results = mutableListOf<T>()
        try {
            DatabaseFactory.activeDataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) {
                            results.add(mapper(rs))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[StorageManagement] Query error: ${e.message}")
        }
        return results
    }

    /**
     * Estimates table size in bytes and row counts using ANSI SQL LENGTH() aggregation.
     */
    private fun getTableStats(tableName: String, lengthColumns: List<String>): Pair<Long, Long> {
        return try {
            val colSumExpr = if (lengthColumns.isEmpty()) "0" else lengthColumns.joinToString(" + ") { "COALESCE(LENGTH(\"$it\"), 0)" }
            val sql = "SELECT COUNT(*) AS row_cnt, SUM($colSumExpr) AS byte_cnt FROM \"$tableName\""
            var rowCount = 0L
            var byteCount = 0L
            DatabaseFactory.activeDataSource?.connection?.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        if (rs.next()) {
                            rowCount = rs.getLong("row_cnt")
                            byteCount = rs.getLong("byte_cnt")
                        }
                    }
                }
            }
            Pair(rowCount, byteCount)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }

    /**
     * Computes the total physical size of the database on disk where applicable.
     */
    private fun getPhysicalDatabaseSize(): Long {
        val config = AppConfigLoader.load().database
        val type = config.type.lowercase()
        if (type == "sqlite") {
            try {
                val dbPath = config.sqlite.file
                val mainFile = File(dbPath)
                var total = if (mainFile.exists()) mainFile.length() else 0L
                val walFile = File("$dbPath-wal")
                if (walFile.exists()) total += walFile.length()
                val shmFile = File("$dbPath-shm")
                if (shmFile.exists()) total += shmFile.length()
                return total
            } catch (e: Exception) {
                // ignore
            }
        } else if (type == "postgres" && !DatabaseFactory.isCockroach) {
            try {
                var size = 0L
                DatabaseFactory.activeDataSource?.connection?.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("SELECT pg_database_size(current_database()) AS db_size").use { rs ->
                            if (rs.next()) {
                                size = rs.getLong("db_size")
                            }
                        }
                    }
                }
                if (size > 0) return size
            } catch (e: Exception) {
                // ignore
            }
        }
        return 0L
    }

    /**
     * Compiles a high-level system storage overview.
     */
    fun getStorageOverview(): StorageOverviewDto {
        val config = AppConfigLoader.load().database
        val physicalSize = getPhysicalDatabaseSize()

        // 1. API Cache Tables
        val (apiEventsCount, apiEventsBytes) = getTableStats("api_events", listOf("data_json", "name"))
        val (apiTeamsCount, apiTeamsBytes) = getTableStats("api_teams", listOf("data_json", "name", "nickname"))
        val (apiMatchesCount, apiMatchesBytes) = getTableStats("api_matches", listOf("data_json", "red_teams", "blue_teams"))
        val (epaOprCount, epaOprBytes) = getTableStats("epa_opr_history_cache", listOf("oprs_json", "epa_history_json"))

        val totalApiRecords = apiEventsCount + apiTeamsCount + apiMatchesCount + epaOprCount
        val totalApiBytes = apiEventsBytes + apiTeamsBytes + apiMatchesBytes + epaOprBytes

        // 2. User Scouting Tables
        val (scoutingCount, scoutingBytes) = getTableStats("scouting_entries", listOf("data_json"))
        val (pitCount, pitBytes) = getTableStats("pit_scouting_entries", listOf("data_json"))
        val (qualCount, qualBytes) = getTableStats("qualitative_scouting_entries", listOf("data_json"))
        val (analyticsCount, analyticsBytes) = getTableStats("analytics_reports", listOf("config_json", "description"))

        val totalScoutingRecords = scoutingCount + pitCount + qualCount + analyticsCount
        val totalScoutingBytes = scoutingBytes + pitBytes + qualBytes + analyticsBytes

        // 3. Chat Tables
        val (chatMsgCount, chatMsgBytes) = getTableStats("chat_messages", listOf("content", "reactions_json"))
        val (chatGroupCount, chatGroupBytes) = getTableStats("chat_groups", listOf("allowed_roles", "allowed_user_ids"))

        val totalChatRecords = chatMsgCount + chatGroupCount
        val totalChatBytes = chatMsgBytes + chatGroupBytes

        // 4. Accounts and Sessions
        val (usersCount, usersBytes) = getTableStats("users", listOf("profile_picture", "tour_progress"))
        val (sessionsCount, sessionsBytes) = getTableStats("user_sessions", listOf("user_agent", "ip_address"))
        val (pushCount, pushBytes) = getTableStats("push_subscriptions", listOf("endpoint", "p256dh", "auth"))
        val (fcmCount, fcmBytes) = getTableStats("fcm_device_tokens", listOf("device_token"))

        val totalAccountRecords = usersCount + sessionsCount + pushCount + fcmCount
        val totalAccountBytes = usersBytes + sessionsBytes + pushBytes + fcmBytes

        // 5. Configs, Revisions & System Tables
        val (configsCount, configsBytes) = getTableStats("scouting_configs", listOf("config_json"))
        val (pitConfigsCount, pitConfigsBytes) = getTableStats("pit_scouting_configs", listOf("config_json"))
        val (qualConfigsCount, qualConfigsBytes) = getTableStats("qualitative_scouting_configs", listOf("config_json"))
        val (revisionsCount, revisionsBytes) = getTableStats("config_revisions", listOf("config_json", "change_summary"))
        val (defaultConfigsCount, defaultConfigsBytes) = getTableStats("default_configs", listOf("config_json"))
        val (settingsCount, settingsBytes) = getTableStats("app_settings", listOf("settings_json"))
        val (alliancesCount, alliancesBytes) = getTableStats("scouting_alliances", listOf("match_config_json", "pit_config_json", "qualitative_config_json", "notes"))
        val (allianceMembershipsCount, allianceMembershipsBytes) = getTableStats("alliance_memberships", listOf())
        val (allianceSelectionsCount, allianceSelectionsBytes) = getTableStats("alliance_selections", listOf("selection_json"))
        val (bannersCount, bannersBytes) = getTableStats("banners", listOf("message", "expandable_message"))

        val totalConfigRecords = configsCount + pitConfigsCount + qualConfigsCount + revisionsCount + defaultConfigsCount +
                settingsCount + alliancesCount + allianceMembershipsCount + allianceSelectionsCount + bannersCount
        val totalConfigBytes = configsBytes + pitConfigsBytes + qualConfigsBytes + revisionsBytes + defaultConfigsBytes +
                settingsBytes + alliancesBytes + allianceMembershipsBytes + allianceSelectionsBytes + bannersBytes

        val totalEstimatedBytes = totalApiBytes + totalScoutingBytes + totalChatBytes + totalAccountBytes + totalConfigBytes
        val totalRecords = totalApiRecords + totalScoutingRecords + totalChatRecords + totalAccountRecords + totalConfigRecords

        val categories = listOf(
            StorageCategoryBreakdown(
                category = "API Event Caches",
                description = "Events, match schedules, teams & Statbotics EPA/OPR history synced from external APIs. Safe to clear; will re-sync on demand.",
                isApiCache = true,
                recordCount = totalApiRecords,
                estimatedBytes = totalApiBytes
            ),
            StorageCategoryBreakdown(
                category = "User Scouting Data",
                description = "Match scouting forms, pit scouting entries, qualitative notes, and custom analytics reports submitted by teams.",
                isApiCache = false,
                recordCount = totalScoutingRecords,
                estimatedBytes = totalScoutingBytes
            ),
            StorageCategoryBreakdown(
                category = "Team Chat & Groups",
                description = "Team chat messages, group channels, reactions, and media references.",
                isApiCache = false,
                recordCount = totalChatRecords,
                estimatedBytes = totalChatBytes
            ),
            StorageCategoryBreakdown(
                category = "User Accounts & Sessions",
                description = "Registered team accounts, user profile pictures, active login sessions, and web push tokens.",
                isApiCache = false,
                recordCount = totalAccountRecords,
                estimatedBytes = totalAccountBytes
            ),
            StorageCategoryBreakdown(
                category = "Configurations & System",
                description = "Scouting form definitions, revision histories, alliance pick lists, team settings, and banners.",
                isApiCache = false,
                recordCount = totalConfigRecords,
                estimatedBytes = totalConfigBytes
            )
        )

        return StorageOverviewDto(
            databaseType = config.type.lowercase(),
            isCockroach = DatabaseFactory.isCockroach,
            totalPhysicalSizeBytes = if (physicalSize > 0) physicalSize else totalEstimatedBytes,
            totalEstimatedBytes = totalEstimatedBytes,
            totalRecords = totalRecords,
            apiCacheBytes = totalApiBytes,
            apiCacheRecords = totalApiRecords,
            userScoutingBytes = totalScoutingBytes,
            userScoutingRecords = totalScoutingRecords,
            chatBytes = totalChatBytes,
            chatRecords = totalChatRecords,
            accountsBytes = totalAccountBytes,
            accountsRecords = totalAccountRecords,
            systemConfigBytes = totalConfigBytes,
            systemConfigRecords = totalConfigRecords,
            categories = categories
        )
    }

    /**
     * Retrieves all cached API events with their match/team counts, byte size, and user scouting presence.
     */
    fun getEventCacheStorage(): List<EventCacheStorageDto> {
        val sql = """
            SELECT 
                e.event_key, 
                e.name, 
                e.year, 
                e.updated_at,
                COALESCE(LENGTH(e.data_json), 0) AS event_bytes,
                COALESCE(m.match_cnt, 0) AS match_cnt,
                COALESCE(m.match_bytes, 0) AS match_bytes,
                COALESCE(t.team_cnt, 0) AS team_cnt,
                COALESCE(t.team_bytes, 0) AS team_bytes,
                CASE WHEN epa.event_key IS NOT NULL THEN 1 ELSE 0 END AS has_epa,
                COALESCE(LENGTH(epa.oprs_json), 0) + COALESCE(LENGTH(epa.epa_history_json), 0) AS epa_bytes,
                COALESCE(s.scout_cnt, 0) AS scout_cnt
            FROM api_events e
            LEFT JOIN (
                SELECT event_key, COUNT(*) AS match_cnt, SUM(COALESCE(LENGTH(data_json), 0)) AS match_bytes
                FROM api_matches GROUP BY event_key
            ) m ON e.event_key = m.event_key
            LEFT JOIN (
                SELECT event_key, COUNT(*) AS team_cnt, SUM(COALESCE(LENGTH(data_json), 0)) AS team_bytes
                FROM api_teams GROUP BY event_key
            ) t ON e.event_key = t.event_key
            LEFT JOIN epa_opr_history_cache epa ON e.event_key = epa.event_key
            LEFT JOIN (
                SELECT event_key, COUNT(*) AS scout_cnt FROM (
                    SELECT event_key FROM scouting_entries WHERE event_key IS NOT NULL
                    UNION ALL
                    SELECT event_key FROM pit_scouting_entries WHERE event_key IS NOT NULL
                    UNION ALL
                    SELECT event_key FROM qualitative_scouting_entries WHERE event_key IS NOT NULL
                ) combined GROUP BY event_key
            ) s ON e.event_key = s.event_key
            ORDER BY e.year DESC, e.name ASC
        """.trimIndent()

        return try {
            executeQuery(sql) { rs ->
                val eventKey = rs.getString("event_key") ?: ""
                val name = rs.getString("name") ?: ""
                val year = rs.getInt("year")
                val matchCnt = rs.getLong("match_cnt")
                val teamCnt = rs.getLong("team_cnt")
                val hasEpa = rs.getInt("has_epa") == 1
                val eventBytes = rs.getLong("event_bytes")
                val matchBytes = rs.getLong("match_bytes")
                val teamBytes = rs.getLong("team_bytes")
                val epaBytes = rs.getLong("epa_bytes")
                val scoutCnt = rs.getLong("scout_cnt")
                val updatedAt = rs.getTimestamp("updated_at")?.time ?: 0L

                EventCacheStorageDto(
                    eventKey = eventKey,
                    name = name,
                    year = year,
                    matchCount = matchCnt,
                    teamCount = teamCnt,
                    hasEpaHistory = hasEpa,
                    cacheBytes = eventBytes + matchBytes + teamBytes + epaBytes,
                    userScoutingEntryCount = scoutCnt,
                    lastUpdatedEpochMs = updatedAt
                )
            }
        } catch (e: Exception) {
            println("[StorageManagement] Error retrieving event cache storage: ${e.message}")
            emptyList()
        }
    }

    /**
     * Retrieves storage usage broken down by scouting team number.
     */
    fun getTeamStorageUsage(): List<TeamStorageUsageDto> {
        val sql = """
            WITH all_teams AS (
                SELECT team_number, program FROM users
                UNION
                SELECT owner_team_number AS team_number, program FROM scouting_entries
                UNION
                SELECT owner_team_number AS team_number, program FROM pit_scouting_entries
                UNION
                SELECT owner_team_number AS team_number, program FROM qualitative_scouting_entries
                UNION
                SELECT team_number, program FROM scouting_configs
                UNION
                SELECT team_number, program FROM pit_scouting_configs
                UNION
                SELECT team_number, program FROM qualitative_scouting_configs
                UNION
                SELECT team_number, program FROM config_revisions
                UNION
                SELECT team_number, program FROM chat_messages
                UNION
                SELECT owner_team_number AS team_number, program FROM analytics_reports
            )
            SELECT 
                t.team_number,
                t.program,
                COALESCE(m.cnt, 0) AS match_cnt,
                COALESCE(m.bytes, 0) AS match_bytes,
                COALESCE(p.cnt, 0) AS pit_cnt,
                COALESCE(p.bytes, 0) AS pit_bytes,
                COALESCE(q.cnt, 0) AS qual_cnt,
                COALESCE(q.bytes, 0) AS qual_bytes,
                COALESCE(cfg.cnt, 0) AS cfg_cnt,
                COALESCE(cfg.bytes, 0) AS cfg_bytes,
                COALESCE(rev.cnt, 0) AS rev_cnt,
                COALESCE(rev.bytes, 0) AS rev_bytes,
                COALESCE(rep.cnt, 0) AS rep_cnt,
                COALESCE(rep.bytes, 0) AS rep_bytes,
                COALESCE(c.cnt, 0) AS chat_cnt,
                COALESCE(c.bytes, 0) AS chat_bytes,
                COALESCE(u.cnt, 0) AS user_cnt,
                COALESCE(u.bytes, 0) AS user_bytes
            FROM (SELECT DISTINCT team_number, program FROM all_teams) t
            LEFT JOIN (
                SELECT owner_team_number, program, COUNT(*) AS cnt, SUM(COALESCE(LENGTH(data_json), 0)) AS bytes
                FROM scouting_entries GROUP BY owner_team_number, program
            ) m ON t.team_number = m.owner_team_number AND t.program = m.program
            LEFT JOIN (
                SELECT owner_team_number, program, COUNT(*) AS cnt, SUM(COALESCE(LENGTH(data_json), 0)) AS bytes
                FROM pit_scouting_entries GROUP BY owner_team_number, program
            ) p ON t.team_number = p.owner_team_number AND t.program = p.program
            LEFT JOIN (
                SELECT owner_team_number, program, COUNT(*) AS cnt, SUM(COALESCE(LENGTH(data_json), 0)) AS bytes
                FROM qualitative_scouting_entries GROUP BY owner_team_number, program
            ) q ON t.team_number = q.owner_team_number AND t.program = q.program
            LEFT JOIN (
                SELECT team_number, program, COUNT(*) AS cnt, SUM(COALESCE(LENGTH(config_json), 0)) AS bytes
                FROM (
                    SELECT team_number, program, config_json FROM scouting_configs
                    UNION ALL
                    SELECT team_number, program, config_json FROM pit_scouting_configs
                    UNION ALL
                    SELECT team_number, program, config_json FROM qualitative_scouting_configs
                ) c_all GROUP BY team_number, program
            ) cfg ON t.team_number = cfg.team_number AND t.program = cfg.program
            LEFT JOIN (
                SELECT team_number, program, COUNT(*) AS cnt, SUM(COALESCE(LENGTH(config_json), 0) + COALESCE(LENGTH(change_summary), 0)) AS bytes
                FROM config_revisions GROUP BY team_number, program
            ) rev ON t.team_number = rev.team_number AND t.program = rev.program
            LEFT JOIN (
                SELECT owner_team_number, program, COUNT(*) AS cnt, SUM(COALESCE(LENGTH(config_json), 0)) AS bytes
                FROM analytics_reports GROUP BY owner_team_number, program
            ) rep ON t.team_number = rep.owner_team_number AND t.program = rep.program
            LEFT JOIN (
                SELECT team_number, program, COUNT(*) AS cnt, SUM(COALESCE(LENGTH(content), 0) + COALESCE(LENGTH(reactions_json), 0)) AS bytes
                FROM chat_messages GROUP BY team_number, program
            ) c ON t.team_number = c.team_number AND t.program = c.program
            LEFT JOIN (
                SELECT team_number, program, COUNT(*) AS cnt, SUM(COALESCE(LENGTH(profile_picture), 0) + COALESCE(LENGTH(tour_progress), 0)) AS bytes
                FROM users GROUP BY team_number, program
            ) u ON t.team_number = u.team_number AND t.program = u.program
            ORDER BY (COALESCE(m.bytes, 0) + COALESCE(p.bytes, 0) + COALESCE(q.bytes, 0) + COALESCE(rev.bytes, 0) + COALESCE(c.bytes, 0)) DESC, t.team_number ASC
        """.trimIndent()

        return try {
            executeQuery(sql) { rs ->
                val teamNumber = rs.getInt("team_number")
                val program = rs.getString("program") ?: "FRC"
                val matchCnt = rs.getLong("match_cnt")
                val matchBytes = rs.getLong("match_bytes")
                val pitCnt = rs.getLong("pit_cnt")
                val pitBytes = rs.getLong("pit_bytes")
                val qualCnt = rs.getLong("qual_cnt")
                val qualBytes = rs.getLong("qual_bytes")
                val cfgCnt = rs.getLong("cfg_cnt")
                val cfgBytes = rs.getLong("cfg_bytes")
                val revCnt = rs.getLong("rev_cnt")
                val revBytes = rs.getLong("rev_bytes")
                val repCnt = rs.getLong("rep_cnt")
                val repBytes = rs.getLong("rep_bytes")
                val chatCnt = rs.getLong("chat_cnt")
                val chatBytes = rs.getLong("chat_bytes")
                val userCnt = rs.getLong("user_cnt")
                val userBytes = rs.getLong("user_bytes")

                val totalBytes = matchBytes + pitBytes + qualBytes + cfgBytes + revBytes + repBytes + chatBytes + userBytes
                val totalRecords = matchCnt + pitCnt + qualCnt + cfgCnt + revCnt + repCnt + chatCnt + userCnt

                TeamStorageUsageDto(
                    teamNumber = teamNumber,
                    program = program,
                    matchEntryCount = matchCnt,
                    matchBytes = matchBytes,
                    pitEntryCount = pitCnt,
                    pitBytes = pitBytes,
                    qualEntryCount = qualCnt,
                    qualBytes = qualBytes,
                    configCount = cfgCnt,
                    configBytes = cfgBytes,
                    configRevisionCount = revCnt,
                    configRevisionBytes = revBytes,
                    reportCount = repCnt,
                    chatMessageCount = chatCnt,
                    chatBytes = chatBytes,
                    userCount = userCnt,
                    totalBytes = totalBytes,
                    totalRecords = totalRecords
                )
            }
        } catch (e: Exception) {
            println("[StorageManagement] Error retrieving team storage usage: ${e.message}")
            emptyList()
        }
    }

    /**
     * Retrieves detailed event-level storage usage for a specific team.
     */
    fun getTeamDetailedStorage(teamNumber: Int, program: String = "FRC"): TeamDetailStorageDto? {
        val summary = getTeamStorageUsage().find { it.teamNumber == teamNumber && it.program.equals(program, ignoreCase = true) }
            ?: TeamStorageUsageDto(
                teamNumber = teamNumber,
                program = program.uppercase(),
                totalBytes = 0L,
                matchEntryCount = 0L,
                matchBytes = 0L,
                pitEntryCount = 0L,
                pitBytes = 0L,
                qualEntryCount = 0L,
                qualBytes = 0L,
                configRevisionCount = 0L,
                configRevisionBytes = 0L,
                chatMessageCount = 0L,
                chatBytes = 0L,
                userCount = 0L
            )

        val sql = """
            SELECT 
                combined.event_key,
                SUM(CASE WHEN combined.entry_type = 'match' THEN 1 ELSE 0 END) AS match_cnt,
                SUM(CASE WHEN combined.entry_type = 'pit' THEN 1 ELSE 0 END) AS pit_cnt,
                SUM(CASE WHEN combined.entry_type = 'qual' THEN 1 ELSE 0 END) AS qual_cnt,
                SUM(combined.bytes) AS total_bytes
            FROM (
                SELECT event_key, 'match' AS entry_type, COALESCE(LENGTH(data_json), 0) AS bytes
                FROM scouting_entries WHERE owner_team_number = ? AND program = ? AND event_key IS NOT NULL
                UNION ALL
                SELECT event_key, 'pit' AS entry_type, COALESCE(LENGTH(data_json), 0) AS bytes
                FROM pit_scouting_entries WHERE owner_team_number = ? AND program = ? AND event_key IS NOT NULL
                UNION ALL
                SELECT event_key, 'qual' AS entry_type, COALESCE(LENGTH(data_json), 0) AS bytes
                FROM qualitative_scouting_entries WHERE owner_team_number = ? AND program = ? AND event_key IS NOT NULL
            ) combined
            GROUP BY combined.event_key
            ORDER BY total_bytes DESC
        """.trimIndent()

        val eventUsages = mutableListOf<TeamEventUsageDto>()
        try {
            DatabaseFactory.activeDataSource?.connection?.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setInt(1, teamNumber)
                    stmt.setString(2, program)
                    stmt.setInt(3, teamNumber)
                    stmt.setString(4, program)
                    stmt.setInt(5, teamNumber)
                    stmt.setString(6, program)

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val eventKey = rs.getString("event_key") ?: "Unknown"
                            val matchCnt = rs.getLong("match_cnt")
                            val pitCnt = rs.getLong("pit_cnt")
                            val qualCnt = rs.getLong("qual_cnt")
                            val totalBytes = rs.getLong("total_bytes")
                            eventUsages.add(
                                TeamEventUsageDto(
                                    eventKey = eventKey,
                                    matchCount = matchCnt,
                                    pitCount = pitCnt,
                                    qualCount = qualCnt,
                                    totalBytes = totalBytes
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[StorageManagement] Error retrieving team event details: ${e.message}")
        }

        return TeamDetailStorageDto(
            teamNumber = teamNumber,
            program = program,
            summary = summary,
            events = eventUsages
        )
    }

    // ── Safe API Cache Purge Actions ─────────────────────────────────────────

    fun clearEventCache(eventKey: String): StorageActionResultDto {
        val trimmedKey = eventKey.trim()
        if (trimmedKey.isBlank()) {
            return StorageActionResultDto(false, "Event key cannot be blank")
        }

        var deletedRecords = 0L
        transaction {
            deletedRecords += ApiMatches.deleteWhere { ApiMatches.eventKey eq trimmedKey }.toLong()
            deletedRecords += ApiTeams.deleteWhere { ApiTeams.eventKey eq trimmedKey }.toLong()
            deletedRecords += EpaOprHistoryCache.deleteWhere { EpaOprHistoryCache.eventKey eq trimmedKey }.toLong()
            deletedRecords += ApiEvents.deleteWhere { ApiEvents.eventKey eq trimmedKey }.toLong()
        }

        println("[StorageManagement] Purged API cache for event $trimmedKey: $deletedRecords records deleted.")
        return StorageActionResultDto(
            success = true,
            message = "Successfully cleared API cache for event '$trimmedKey'. Data will re-sync automatically from the API on demand.",
            affectedRecords = deletedRecords
        )
    }

    fun clearOldEventCaches(olderThanYear: Int): StorageActionResultDto {
        if (olderThanYear < 2000 || olderThanYear > 2100) {
            return StorageActionResultDto(false, "Invalid year threshold")
        }

        val eventKeysToDelete = mutableListOf<String>()
        readTransaction {
            ApiEvents.selectAll().where { ApiEvents.year lessEq olderThanYear }.forEach {
                eventKeysToDelete.add(it[ApiEvents.eventKey])
            }
        }

        if (eventKeysToDelete.isEmpty()) {
            return StorageActionResultDto(true, "No API events found for year $olderThanYear or older.", 0)
        }

        var deletedRecords = 0L
        transaction {
            deletedRecords += ApiMatches.deleteWhere { ApiMatches.eventKey inList eventKeysToDelete }.toLong()
            deletedRecords += ApiTeams.deleteWhere { ApiTeams.eventKey inList eventKeysToDelete }.toLong()
            deletedRecords += EpaOprHistoryCache.deleteWhere { EpaOprHistoryCache.eventKey inList eventKeysToDelete }.toLong()
            deletedRecords += ApiEvents.deleteWhere { ApiEvents.eventKey inList eventKeysToDelete }.toLong()
        }

        println("[StorageManagement] Purged API caches for ${eventKeysToDelete.size} events in/before $olderThanYear ($deletedRecords records).")
        return StorageActionResultDto(
            success = true,
            message = "Successfully cleared API caches for ${eventKeysToDelete.size} events from $olderThanYear or earlier ($deletedRecords records deleted).",
            affectedRecords = deletedRecords
        )
    }

    fun clearAllApiCaches(): StorageActionResultDto {
        var deletedRecords = 0L
        transaction {
            deletedRecords += ApiMatches.deleteAll().toLong()
            deletedRecords += ApiTeams.deleteAll().toLong()
            deletedRecords += EpaOprHistoryCache.deleteAll().toLong()
            deletedRecords += ApiEvents.deleteAll().toLong()
        }

        println("[StorageManagement] Purged ALL API caches ($deletedRecords total records).")
        return StorageActionResultDto(
            success = true,
            message = "Successfully cleared all external API caches ($deletedRecords total records deleted). Match and team schedules will re-sync on demand.",
            affectedRecords = deletedRecords
        )
    }

    // ── Destructive User Data Deletions (Permanent Team Data Loss) ────────────

    fun deleteEventScoutingData(eventKey: String, teamNumber: Int? = null, program: String = "FRC"): StorageActionResultDto {
        val trimmedKey = eventKey.trim()
        if (trimmedKey.isBlank()) {
            return StorageActionResultDto(false, "Event key cannot be blank")
        }

        var deletedRecords = 0L
        transaction {
            if (teamNumber != null) {
                deletedRecords += ScoutingEntries.deleteWhere {
                    (ScoutingEntries.eventKey eq trimmedKey) and (ScoutingEntries.ownerTeamNumber eq teamNumber) and (ScoutingEntries.program eq program)
                }.toLong()
                deletedRecords += PitScoutingEntries.deleteWhere {
                    (PitScoutingEntries.eventKey eq trimmedKey) and (PitScoutingEntries.ownerTeamNumber eq teamNumber) and (PitScoutingEntries.program eq program)
                }.toLong()
                deletedRecords += QualitativeScoutingEntries.deleteWhere {
                    (QualitativeScoutingEntries.eventKey eq trimmedKey) and (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and (QualitativeScoutingEntries.program eq program)
                }.toLong()
            } else {
                deletedRecords += ScoutingEntries.deleteWhere {
                    (ScoutingEntries.eventKey eq trimmedKey) and (ScoutingEntries.program eq program)
                }.toLong()
                deletedRecords += PitScoutingEntries.deleteWhere {
                    (PitScoutingEntries.eventKey eq trimmedKey) and (PitScoutingEntries.program eq program)
                }.toLong()
                deletedRecords += QualitativeScoutingEntries.deleteWhere {
                    (QualitativeScoutingEntries.eventKey eq trimmedKey) and (QualitativeScoutingEntries.program eq program)
                }.toLong()
            }
        }

        val targetDesc = if (teamNumber != null) "Team $teamNumber ($program)" else "all teams ($program)"
        println("[StorageManagement] DELETED scouting data for event $trimmedKey ($targetDesc): $deletedRecords entries permanently deleted.")
        return StorageActionResultDto(
            success = true,
            message = "Permanently deleted $deletedRecords scouting records for event '$trimmedKey' ($targetDesc).",
            affectedRecords = deletedRecords
        )
    }

    fun deleteTeamData(teamNumber: Int, program: String = "FRC"): StorageActionResultDto {
        if (teamNumber < 0) {
            return StorageActionResultDto(false, "Invalid team number: must be non-negative")
        }

        val progUpper = program.trim().uppercase()
        val progLower = program.trim().lowercase()

        var deletedRecords = 0L
        transaction {
            // Delete Scouting Entries
            deletedRecords += ScoutingEntries.deleteWhere {
                (ScoutingEntries.ownerTeamNumber eq teamNumber) and ((ScoutingEntries.program eq progUpper) or (ScoutingEntries.program eq progLower))
            }.toLong()
            deletedRecords += PitScoutingEntries.deleteWhere {
                (PitScoutingEntries.ownerTeamNumber eq teamNumber) and ((PitScoutingEntries.program eq progUpper) or (PitScoutingEntries.program eq progLower))
            }.toLong()
            deletedRecords += QualitativeScoutingEntries.deleteWhere {
                (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and ((QualitativeScoutingEntries.program eq progUpper) or (QualitativeScoutingEntries.program eq progLower))
            }.toLong()

            // Delete Configs and Revisions
            deletedRecords += ScoutingConfigs.deleteWhere {
                (ScoutingConfigs.teamNumber eq teamNumber) and ((ScoutingConfigs.program eq progUpper) or (ScoutingConfigs.program eq progLower))
            }.toLong()
            deletedRecords += PitScoutingConfigs.deleteWhere {
                (PitScoutingConfigs.teamNumber eq teamNumber) and ((PitScoutingConfigs.program eq progUpper) or (PitScoutingConfigs.program eq progLower))
            }.toLong()
            deletedRecords += QualitativeScoutingConfigs.deleteWhere {
                (QualitativeScoutingConfigs.teamNumber eq teamNumber) and ((QualitativeScoutingConfigs.program eq progUpper) or (QualitativeScoutingConfigs.program eq progLower))
            }.toLong()
            deletedRecords += ConfigRevisions.deleteWhere {
                (ConfigRevisions.teamNumber eq teamNumber) and ((ConfigRevisions.program eq progUpper) or (ConfigRevisions.program eq progLower))
            }.toLong()

            // Delete Analytics Reports
            deletedRecords += AnalyticsReports.deleteWhere {
                (AnalyticsReports.ownerTeamNumber eq teamNumber) and ((AnalyticsReports.program eq progUpper) or (AnalyticsReports.program eq progLower))
            }.toLong()

            // Delete Chat
            deletedRecords += ChatMessages.deleteWhere {
                (ChatMessages.teamNumber eq teamNumber) and ((ChatMessages.program eq progUpper) or (ChatMessages.program eq progLower))
            }.toLong()
            deletedRecords += ChatGroups.deleteWhere {
                (ChatGroups.teamNumber eq teamNumber) and ((ChatGroups.program eq progUpper) or (ChatGroups.program eq progLower))
            }.toLong()

            // Delete Team Settings
            deletedRecords += AppSettings.deleteWhere {
                (AppSettings.teamNumber eq teamNumber) and ((AppSettings.program eq progUpper) or (AppSettings.program eq progLower))
            }.toLong()

            // Delete Alliance Memberships
            deletedRecords += AllianceMemberships.deleteWhere {
                (AllianceMemberships.teamNumber eq teamNumber) and ((AllianceMemberships.program eq progUpper) or (AllianceMemberships.program eq progLower))
            }.toLong()
        }

        println("[StorageManagement] DELETED entire dataset for Team $teamNumber ($program): $deletedRecords records permanently deleted.")
        return StorageActionResultDto(
            success = true,
            message = "Permanently deleted all scouting entries, configs, revisions, chat, and reports for Team $teamNumber ($program) ($deletedRecords records deleted).",
            affectedRecords = deletedRecords
        )
    }

    fun pruneConfigRevisions(keepLatestPerKind: Int = 10): StorageActionResultDto {
        val keep = if (keepLatestPerKind < 1) 5 else keepLatestPerKind
        var deletedCount = 0L

        // Find revision IDs to delete per team and form kind
        val idsToDelete = mutableListOf<java.util.UUID>()
        readTransaction {
            val revisions = ConfigRevisions.selectAll()
                .orderBy(ConfigRevisions.teamNumber to SortOrder.ASC)
                .orderBy(ConfigRevisions.program to SortOrder.ASC)
                .orderBy(ConfigRevisions.configKind to SortOrder.ASC)
                .orderBy(ConfigRevisions.createdAt to SortOrder.DESC)
                .toList()

            val grouped = revisions.groupBy { Triple(it[ConfigRevisions.teamNumber], it[ConfigRevisions.program], it[ConfigRevisions.configKind]) }
            for ((_, list) in grouped) {
                if (list.size > keep) {
                    val excess = list.drop(keep)
                    idsToDelete.addAll(excess.map { it[ConfigRevisions.id].value })
                }
            }
        }

        if (idsToDelete.isNotEmpty()) {
            transaction {
                deletedCount = ConfigRevisions.deleteWhere { ConfigRevisions.id inList idsToDelete }.toLong()
            }
        }

        println("[StorageManagement] Pruned config revisions (kept top $keep): $deletedCount revisions deleted.")
        return StorageActionResultDto(
            success = true,
            message = "Successfully pruned historical config revisions (retained latest $keep per form). Deleted $deletedCount older revisions.",
            affectedRecords = deletedCount
        )
    }

    fun pruneChatMessages(olderThanDays: Int = 90): StorageActionResultDto {
        if (olderThanDays < 1) {
            return StorageActionResultDto(false, "Days threshold must be at least 1")
        }

        val cutoff = Instant.now().minus(olderThanDays.toLong(), ChronoUnit.DAYS)
        var deletedCount = 0L
        transaction {
            deletedCount = ChatMessages.deleteWhere { ChatMessages.createdAt lessEq cutoff }.toLong()
        }

        println("[StorageManagement] Pruned chat messages older than $olderThanDays days: $deletedCount deleted.")
        return StorageActionResultDto(
            success = true,
            message = "Successfully pruned chat messages older than $olderThanDays days ($deletedCount messages deleted).",
            affectedRecords = deletedCount
        )
    }

    fun pruneExpiredSessions(): StorageActionResultDto {
        val now = Instant.now()
        var deletedCount = 0L
        transaction {
            // Delete sessions where expiresAt is past or lastActiveAt is older than 30 days
            val staleCutoff = now.minus(30, ChronoUnit.DAYS)
            deletedCount += UserSessions.deleteWhere {
                (UserSessions.expiresAt.isNotNull() and (UserSessions.expiresAt lessEq now)) or
                        (UserSessions.lastActiveAt lessEq staleCutoff)
            }.toLong()

            // Also clean up expired password reset tokens
            PasswordResetTokens.deleteWhere {
                PasswordResetTokens.expiresAt lessEq now
            }
        }

        println("[StorageManagement] Pruned expired sessions: $deletedCount deleted.")
        return StorageActionResultDto(
            success = true,
            message = "Successfully cleaned up $deletedCount expired or inactive user sessions and tokens.",
            affectedRecords = deletedCount
        )
    }

    fun reclaimDiskSpace(): StorageActionResultDto {
        val config = AppConfigLoader.load().database
        val type = config.type.lowercase()

        return try {
            if (type == "sqlite") {
                DatabaseFactory.activeDataSource?.connection?.use { conn ->
                    conn.autoCommit = true
                    conn.createStatement().use { stmt ->
                        stmt.execute("VACUUM;")
                    }
                }
                StorageActionResultDto(
                    success = true,
                    message = "Successfully executed SQLite VACUUM to defragment database and reclaim physical disk space."
                )
            } else if (type == "postgres" && !DatabaseFactory.isCockroach) {
                // In Postgres autocommit is required for VACUUM
                DatabaseFactory.activeDataSource?.connection?.use { conn ->
                    conn.autoCommit = true
                    conn.createStatement().use { stmt ->
                        stmt.execute("VACUUM;")
                    }
                }
                StorageActionResultDto(
                    success = true,
                    message = "Successfully triggered PostgreSQL VACUUM to reclaim space from deleted rows."
                )
            } else {
                StorageActionResultDto(
                    success = true,
                    message = "CockroachDB automatically handles disk space reclamation using background MVCC garbage collection."
                )
            }
        } catch (e: Exception) {
            println("[StorageManagement] Error executing database reclaim/vacuum: ${e.message}")
            StorageActionResultDto(false, "Reclaim operation failed: ${e.message}")
        }
    }
}
