package com.obsidianscout.db

import com.obsidianscout.auth.UserRole
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.concurrent.thread

import kotlinx.serialization.Serializable

@Serializable
data class PostgresMigrationConfig(
    val host: String,
    val port: Int,
    val user: String,
    val passwordPlain: String,
    val database: String,
    val databaseUsers: String,
    val databasePages: String,
    val databaseMisc: String,
    val databaseImages: String,
    val databaseStatboticsepa: String
)

@Serializable
data class MigrationStatusPayload(
    val running: Boolean,
    val success: Boolean,
    val message: String,
    val progress: Int,
    val usersMigrated: Int,
    val eventsMigrated: Int,
    val teamsMigrated: Int,
    val matchesMigrated: Int,
    val scoutingDataMigrated: Int,
    val alliancesMigrated: Int
)

fun normalizeEventCode(value: String?): String {
    if (value == null) return ""
    var code = value.trim().uppercase()
    if (code.isEmpty()) return ""
    // Heal repeated year prefixes: 20262026ARLI -> 2026ARLI
    while (code.length >= 8 && code.substring(0, 4).all { it.isDigit() } && code.substring(4, 8) == code.substring(0, 4)) {
        code = code.substring(0, 4) + code.substring(8)
    }
    return code
}

fun splitEventCode(code: String): Pair<Int?, String> {
    val normalized = normalizeEventCode(code)
    if (normalized.length > 4 && normalized.substring(0, 4).all { it.isDigit() }) {
        val year = normalized.substring(0, 4).toIntOrNull()
        if (year != null) {
            return Pair(year, normalized.substring(4))
        }
    }
    return Pair(null, normalized)
}

fun constructEventKey(eventCode: String, eventYear: Int): String {
    val normalized = normalizeEventCode(eventCode)
    val (_, rawCode) = splitEventCode(normalized)
    return "${eventYear}${rawCode.lowercase()}"
}

object MigrationService {
    @Volatile
    private var running = false
    @Volatile
    private var success = false
    @Volatile
    private var message = ""
    @Volatile
    private var progress = 0
    @Volatile
    private var usersMigrated = 0
    @Volatile
    private var eventsMigrated = 0
    @Volatile
    private var teamsMigrated = 0
    @Volatile
    private var matchesMigrated = 0
    @Volatile
    private var scoutingDataMigrated = 0
    @Volatile
    private var alliancesMigrated = 0

    fun getStatus(): MigrationStatusPayload {
        return MigrationStatusPayload(
            running = running,
            success = success,
            message = message,
            progress = progress,
            usersMigrated = usersMigrated,
            eventsMigrated = eventsMigrated,
            teamsMigrated = teamsMigrated,
            matchesMigrated = matchesMigrated,
            scoutingDataMigrated = scoutingDataMigrated,
            alliancesMigrated = alliancesMigrated
        )
    }

    private fun updateStatus(
        isRunning: Boolean = running,
        isSuccess: Boolean = success,
        msg: String = message,
        prog: Int = progress,
        users: Int = usersMigrated,
        events: Int = eventsMigrated,
        teams: Int = teamsMigrated,
        matches: Int = matchesMigrated,
        scouting: Int = scoutingDataMigrated,
        alliances: Int = alliancesMigrated
    ) {
        running = isRunning
        success = isSuccess
        message = msg
        progress = prog
        usersMigrated = users
        eventsMigrated = events
        teamsMigrated = teams
        matchesMigrated = matches
        scoutingDataMigrated = scouting
        alliancesMigrated = alliances
    }

    fun startMigration(
        sourceType: String,
        sqliteInstancePath: String?,
        pgConfig: PostgresMigrationConfig?
    ) {
        if (running) {
            return
        }

        updateStatus(
            isRunning = true,
            isSuccess = false,
            msg = "Initializing migration...",
            prog = 0,
            users = 0,
            events = 0,
            teams = 0,
            matches = 0,
            scouting = 0,
            alliances = 0
        )

        thread(name = "db-migration-worker") {
            try {
                runMigrationImpl(sourceType, sqliteInstancePath, pgConfig)
                updateStatus(
                    isRunning = false,
                    isSuccess = true,
                    msg = "Migration completed successfully!",
                    prog = 100
                )
            } catch (e: Exception) {
                e.printStackTrace()
                updateStatus(
                    isRunning = false,
                    isSuccess = false,
                    msg = "Migration failed: ${e.message}",
                    prog = 100
                )
            }
        }
    }

    private fun runMigrationImpl(
        sourceType: String,
        sqliteInstancePath: String?,
        pgConfig: PostgresMigrationConfig?
    ) {
        val useSqlite = sourceType.lowercase() == "sqlite"
        if (useSqlite) {
            require(!sqliteInstancePath.isNullOrBlank()) { "SQLite instance path is required" }
            val dir = File(sqliteInstancePath)
            require(dir.exists() && dir.isDirectory) { "SQLite instance path must be a valid directory" }
        } else {
            require(pgConfig != null) { "PostgreSQL configuration is required" }
        }

        // Helper to get connection
        fun connect(dbName: String): Connection {
            return if (useSqlite) {
                val dbFile = File(sqliteInstancePath, dbName)
                require(dbFile.exists()) { "SQLite database file not found: ${dbFile.absolutePath}" }
                Class.forName("org.sqlite.JDBC")
                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            } else {
                val targetDb = when (dbName) {
                    "users.db" -> pgConfig!!.databaseUsers
                    "pages.db" -> pgConfig!!.databasePages
                    "misc.db" -> pgConfig!!.databaseMisc
                    "images.db" -> pgConfig!!.databaseImages
                    "statboticsepa.db" -> pgConfig!!.databaseStatboticsepa
                    else -> pgConfig!!.database
                }
                val url = "jdbc:postgresql://${pgConfig.host}:${pgConfig.port}/$targetDb"
                Class.forName("org.postgresql.Driver")
                DriverManager.getConnection(url, pgConfig.user, pgConfig.passwordPlain)
            }
        }

        // Helper to parse dates
        fun parseInstant(str: String?): Instant {
            if (str.isNullOrBlank()) return Instant.now()
            return try {
                Instant.parse(str)
            } catch (_: Exception) {
                try {
                    val clean = str.replace(" ", "T")
                    val iso = if (clean.contains(".")) {
                        val parts = clean.split(".")
                        val frac = parts[1].take(3)
                        parts[0] + "." + frac + "Z"
                    } else {
                        clean + "Z"
                    }
                    Instant.parse(iso)
                } catch (_: Exception) {
                    Instant.now()
                }
            }
        }

        // 1. Migrate Users
        updateStatus(msg = "Migrating users...", prog = 10)
        val oldToNewUserId = mutableMapOf<Int, UUID>()
        connect("users.db").use { conn ->
            val stmt = conn.createStatement()
            val query = """
                SELECT u.id, u.username, u.email, u.password_hash, u.scouting_team_number, u.created_at, u.profile_picture, u.only_password_reset_emails, r.name as role_name
                FROM "user" u
                LEFT JOIN user_roles ur ON u.id = ur.user_id
                LEFT JOIN role r ON ur.role_id = r.id
            """.trimIndent()
            stmt.executeQuery(query).use { rs ->
                while (rs.next()) {
                    val oldId = rs.getInt("id")
                    val oldUsername = rs.getString("username")
                    val emailVal = rs.getString("email")
                    val pwdHash = rs.getString("password_hash")
                    val teamNumVal = rs.getInt("scouting_team_number")
                    val teamNum = if (rs.wasNull()) 0 else teamNumVal
                    val createdAtStr = rs.getString("created_at")
                    val profPic = rs.getString("profile_picture")
                    val onlyPwdReset = rs.getBoolean("only_password_reset_emails")
                    val roleName = rs.getString("role_name")

                    val targetRole = when (roleName?.lowercase()) {
                        "superadmin" -> UserRole.SUPERADMIN.name
                        "admin" -> UserRole.ADMIN.name
                        "analytics" -> UserRole.ANALYTICS.name
                        "scout" -> UserRole.SCOUT.name
                        else -> UserRole.SCOUT.name
                    }
                    val targetPref = if (onlyPwdReset) "none" else "all"
                    val createdInstant = parseInstant(createdAtStr)

                    // Insert or select existing
                    val newUserId = transaction {
                        val existing = Users.selectAll().where { (Users.username eq oldUsername) and (Users.teamNumber eq teamNum) }
                            .limit(1)
                            .firstOrNull()
                        if (existing != null) {
                            existing[Users.id].value
                        } else {
                            Users.insertAndGetId {
                                it[username] = oldUsername
                                it[teamNumber] = teamNum
                                it[passwordHash] = pwdHash ?: ""
                                it[role] = targetRole
                                it[createdAt] = createdInstant
                                it[email] = emailVal
                                it[profilePicture] = profPic
                                it[notificationPreference] = targetPref
                            }.value
                        }
                    }
                    oldToNewUserId[oldId] = newUserId
                    usersMigrated++
                }
            }
        }
        updateStatus(users = usersMigrated, prog = 30)

        // 2. Migrate Events
        updateStatus(msg = "Migrating events...", prog = 35)
        val oldToNewEventKey = mutableMapOf<Int, String>()
        val defaultEventKeyRef = mutableListOf<String>()

        connect("scouting.db").use { conn ->
            val stmt = conn.createStatement()
            stmt.executeQuery("SELECT id, name, code, timezone, start_date, end_date, year FROM event").use { rs ->
                while (rs.next()) {
                    val oldId = rs.getInt("id")
                    val nameVal = rs.getString("name")
                    val codeVal = rs.getString("code") ?: "EVENT"
                    val yearVal = rs.getInt("year")
                    val tz = rs.getString("timezone")
                    val start = rs.getString("start_date")
                    val end = rs.getString("end_date")

                    val generatedKey = constructEventKey(codeVal, yearVal)
                    val (_, rawCode) = splitEventCode(codeVal)
                    
                    transaction {
                        val exists = ApiEvents.selectAll().where { ApiEvents.eventKey eq generatedKey }.any()
                        if (!exists) {
                            ApiEvents.insert {
                                it[eventKey] = generatedKey
                                it[year] = yearVal
                                it[eventCode] = rawCode.lowercase()
                                it[name] = nameVal
                                it[startDate] = start
                                it[endDate] = end
                                it[timezone] = tz
                                it[dataJson] = "{}"
                                it[updatedAt] = Instant.now()
                            }
                        }
                    }
                    oldToNewEventKey[oldId] = generatedKey
                    defaultEventKeyRef.add(generatedKey)
                    eventsMigrated++
                }
            }
        }
        updateStatus(events = eventsMigrated, prog = 45)

        val defaultEventKey = defaultEventKeyRef.firstOrNull() ?: "2026cmode"

        // 3. Migrate Teams
        updateStatus(msg = "Migrating teams...", prog = 50)
        connect("scouting.db").use { conn ->
            // First load all teams
            data class TempTeam(val id: Int, val number: Int, val name: String?, val location: String?)
            val teamsList = mutableListOf<TempTeam>()
            val teamStmt = conn.createStatement()
            teamStmt.executeQuery("SELECT id, team_number, team_name, location FROM team").use { rs ->
                while (rs.next()) {
                    teamsList.add(
                        TempTeam(
                            id = rs.getInt("id"),
                            number = rs.getInt("team_number"),
                            name = rs.getString("team_name"),
                            location = rs.getString("location")
                        )
                    )
                }
            }

            // Load team to event mappings
            val teamToEventsMap = mutableMapOf<Int, MutableList<Int>>()
            try {
                val linkStmt = conn.createStatement()
                linkStmt.executeQuery("SELECT team_id, event_id FROM team_event").use { rs ->
                    while (rs.next()) {
                        val tid = rs.getInt("team_id")
                        val eid = rs.getInt("event_id")
                        teamToEventsMap.getOrPut(tid) { mutableListOf() }.add(eid)
                    }
                }
            } catch (_: Exception) {}

            for (t in teamsList) {
                val teamKeyVal = "frc${t.number}"
                val associatedEvents = teamToEventsMap[t.id]?.mapNotNull { oldToNewEventKey[it] }?.distinct()
                    ?: listOf(defaultEventKey)

                for (evKey in associatedEvents) {
                    transaction {
                        val exists = ApiTeams.selectAll().where { (ApiTeams.eventKey eq evKey) and (ApiTeams.teamKey eq teamKeyVal) }.any()
                        if (!exists) {
                            val cityVal = t.location?.split(",")?.firstOrNull()?.trim()
                            val stateVal = t.location?.split(",")?.getOrNull(1)?.trim()
                            val countryVal = t.location?.split(",")?.getOrNull(2)?.trim()
                            ApiTeams.insert {
                                it[eventKey] = evKey
                                it[teamKey] = teamKeyVal
                                it[teamNumber] = t.number
                                it[name] = t.name
                                it[nickname] = t.name
                                it[city] = cityVal
                                it[state] = stateVal
                                it[country] = countryVal
                                it[opr] = null
                                it[epa] = null
                                it[dataJson] = "{}"
                                it[updatedAt] = Instant.now()
                            }
                        }
                    }
                }
                teamsMigrated++
            }
        }
        updateStatus(teams = teamsMigrated, prog = 65)

        // 4. Migrate Matches
        updateStatus(msg = "Migrating matches...", prog = 70)
        val oldToNewMatchKey = mutableMapOf<Int, String>()
        connect("scouting.db").use { conn ->
            val stmt = conn.createStatement()
            val query = """
                SELECT id, match_number, match_type, event_id, red_alliance, blue_alliance, 
                       scheduled_time, actual_time, comp_level, set_number 
                FROM match
            """.trimIndent()
            stmt.executeQuery(query).use { rs ->
                while (rs.next()) {
                    val oldId = rs.getInt("id")
                    val matchNum = rs.getInt("match_number")
                    val mType = rs.getString("match_type") ?: "Qualification"
                    val eventId = rs.getInt("event_id")
                    val redStr = rs.getString("red_alliance") ?: ""
                    val blueStr = rs.getString("blue_alliance") ?: ""
                    val schedTimeStr = rs.getString("scheduled_time")
                    val actTimeStr = rs.getString("actual_time")
                    val compLevelVal = rs.getString("comp_level")
                    val setNumVal = rs.getInt("set_number")
                    val setNum = if (rs.wasNull() || setNumVal <= 0) 1 else setNumVal

                    val eventKeyVal = oldToNewEventKey[eventId] ?: defaultEventKey
                    val compLvl = compLevelVal?.lowercase() ?: when (mType.lowercase()) {
                        "qualification" -> "qm"
                        "quarterfinal" -> "qf"
                        "semifinal" -> "sf"
                        "final" -> "f"
                        else -> "qm"
                    }

                    // FRC format match key
                    val generatedMatchKey = if (compLvl == "qm") {
                        "${eventKeyVal}_qm${matchNum}"
                    } else {
                        "${eventKeyVal}_${compLvl}${setNum}m${matchNum}"
                    }

                    // Parse comma lists to FRC JSON string arrays: ["frc1111", ...]
                    fun parseAlliance(allianceStr: String): String {
                        if (allianceStr.isBlank()) return "[]"
                        val list = allianceStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        val frcList = list.map { if (it.startsWith("frc")) it else "frc$it" }
                        return frcList.joinToString(prefix = "[\n    \"", postfix = "\"\n]", separator = "\",\n    \"")
                    }

                    val redJson = parseAlliance(redStr)
                    val blueJson = parseAlliance(blueStr)

                    val schedTimeSecs = parseInstant(schedTimeStr).epochSecond
                    val actTimeSecs = parseInstant(actTimeStr).epochSecond

                    transaction {
                        val exists = ApiMatches.selectAll().where { ApiMatches.matchKey eq generatedMatchKey }.any()
                        if (!exists) {
                            ApiMatches.insert {
                                it[matchKey] = generatedMatchKey
                                it[eventKey] = eventKeyVal
                                it[compLevel] = compLvl
                                it[setNumber] = setNum
                                it[matchNumber] = matchNum
                                it[scheduledTime] = schedTimeSecs
                                it[actualTime] = actTimeSecs
                                it[redTeams] = redJson
                                it[blueTeams] = blueJson
                                it[dataJson] = "{}"
                                it[updatedAt] = Instant.now()
                            }
                        }
                    }
                    oldToNewMatchKey[oldId] = generatedMatchKey
                    matchesMigrated++
                }
            }
        }
        updateStatus(matches = matchesMigrated, prog = 80)

        // Helper to find the first superadmin user ID for fallbacks
        val fallbackUserId = transaction {
            Users.selectAll().where { Users.role eq UserRole.SUPERADMIN.name }
                .limit(1)
                .map { it[Users.id].value }
                .firstOrNull() ?: Users.insertAndGetId {
                    it[username] = "migration-fallback"
                    it[teamNumber] = 0
                    it[passwordHash] = ""
                    it[role] = UserRole.SUPERADMIN.name
                    it[createdAt] = Instant.now()
                    it[email] = null
                    it[profilePicture] = null
                    it[notificationPreference] = "none"
                    it[tourProgress] = null
                }.value
        }

        // 5. Migrate Scouting Entries
        updateStatus(msg = "Migrating scouting entries...", prog = 85)
        connect("scouting.db").use { conn ->
            val stmt = conn.createStatement()
            val query = """
                SELECT sd.id, sd.match_id, sd.team_id, sd.scouting_team_number, sd.scout_id, sd.timestamp, sd.data_json, t.team_number 
                FROM scouting_data sd
                LEFT JOIN team t ON sd.team_id = t.id
            """.trimIndent()
            stmt.executeQuery(query).use { rs ->
                while (rs.next()) {
                    val matchId = rs.getInt("match_id")
                    val scoutingTeamVal = rs.getInt("scouting_team_number")
                    val ownerTeam = if (rs.wasNull()) 0 else scoutingTeamVal
                    val targetTeam = rs.getInt("team_number")
                    val scoutId = rs.getInt("scout_id")
                    val tsStr = rs.getString("timestamp")
                    val dataVal = rs.getString("data_json") ?: "{}"

                    val newScoutId = oldToNewUserId[scoutId] ?: fallbackUserId
                    val matchKeyVal = oldToNewMatchKey[matchId]
                    val eventKeyVal = matchKeyVal?.split("_")?.firstOrNull() ?: defaultEventKey

                    val (matchNumberVal, compLevel) = transaction {
                        matchKeyVal?.let { mk ->
                            ApiMatches.selectAll().where { ApiMatches.matchKey eq mk }
                                .limit(1)
                                .map { Pair(it[ApiMatches.matchNumber], it[ApiMatches.compLevel]) }
                                .firstOrNull()
                        } ?: Pair(1, "qm")
                    }

                    // Insert if not already exists (prevent duplicate migration runs)
                    transaction {
                        val exists = ScoutingEntries.selectAll().where {
                            (ScoutingEntries.ownerTeamNumber eq ownerTeam) and
                            (ScoutingEntries.targetTeamNumber eq targetTeam) and
                            (ScoutingEntries.matchKey eq matchKeyVal) and
                            (ScoutingEntries.submittedByUserId eq newScoutId)
                        }.any()
                        
                        if (!exists) {
                            ScoutingEntries.insert {
                                it[ownerTeamNumber] = ownerTeam
                                it[targetTeamNumber] = targetTeam
                                it[eventKey] = eventKeyVal
                                it[matchKey] = matchKeyVal
                                it[matchNumber] = matchNumberVal
                                it[dataJson] = dataVal
                                it[submittedByUserId] = EntityID(newScoutId, Users)
                                it[createdAt] = parseInstant(tsStr)
                                it[isPrescout] = false
                                it[hasDiscrepancy] = false
                                it[conflictingTeams] = ""
                            }
                        }
                    }
                    scoutingDataMigrated++
                }
            }
        }
        updateStatus(scouting = scoutingDataMigrated, prog = 92)

        // 6. Migrate Scouting Alliances & Memberships
        updateStatus(msg = "Migrating alliances...", prog = 95)
        val oldToNewAllianceId = mutableMapOf<Int, UUID>()

        connect("scouting.db").use { conn ->
            // Query event code and year for alliances from link table scouting_alliance_event
            val allianceEvents = mutableMapOf<Int, Pair<String, String>>()
            try {
                val evStmt = conn.createStatement()
                evStmt.executeQuery("SELECT alliance_id, event_code, event_name FROM scouting_alliance_event").use { rs ->
                    while (rs.next()) {
                        val aid = rs.getInt("alliance_id")
                        val code = rs.getString("event_code") ?: ""
                        allianceEvents[aid] = Pair(code, "2026")
                    }
                }
            } catch (_: Exception) {}

            val stmt = conn.createStatement()
            val query = """
                SELECT id, alliance_name, description, created_at, updated_at, game_config_team, pit_config_team, shared_game_config, shared_pit_config 
                FROM scouting_alliance
            """.trimIndent()
            stmt.executeQuery(query).use { rs ->
                while (rs.next()) {
                    val oldId = rs.getInt("id")
                    val nameVal = rs.getString("alliance_name")
                    val desc = rs.getString("description")
                    val created = rs.getString("created_at")
                    val updated = rs.getString("updated_at")
                    val gcTeam = rs.getInt("game_config_team")
                    val owner = if (rs.wasNull()) 0 else gcTeam
                    val sharedGc = rs.getString("shared_game_config")
                    val sharedPit = rs.getString("shared_pit_config")

                    val (evCode, evYear) = allianceEvents[oldId] ?: Pair("", "2026")
                    val evKey = if (evCode.isNotBlank()) constructEventKey(evCode, evYear.toIntOrNull() ?: 2026) else ""

                    val newAllianceId = transaction {
                        val existing = ScoutingAlliances.selectAll().where { (ScoutingAlliances.name eq nameVal) and (ScoutingAlliances.ownerTeamNumber eq owner) }
                            .limit(1)
                            .firstOrNull()
                        if (existing != null) {
                            existing[ScoutingAlliances.id].value
                        } else {
                            ScoutingAlliances.insertAndGetId {
                                it[name] = nameVal
                                it[ownerTeamNumber] = owner
                                it[eventKey] = evKey.ifBlank { null }
                                it[notes] = desc
                                it[createdAt] = parseInstant(created)
                                it[updatedAt] = parseInstant(updated)
                                it[matchConfigJson] = sharedGc
                                it[pitConfigJson] = sharedPit
                                it[qualitativeConfigJson] = null
                                it[year] = evYear.toIntOrNull()
                                it[eventCode] = evCode.ifBlank { null }
                            }.value
                        }
                    }
                    oldToNewAllianceId[oldId] = newAllianceId
                    alliancesMigrated++
                }
            }

            // Migrate alliance memberships
            val memberStmt = conn.createStatement()
            val memberQuery = "SELECT alliance_id, team_number, status, joined_at, is_data_sharing_active FROM scouting_alliance_member"
            try {
                memberStmt.executeQuery(memberQuery).use { rs ->
                    while (rs.next()) {
                        val oldAllianceId = rs.getInt("alliance_id")
                        val teamNum = rs.getInt("team_number")
                        val statusVal = rs.getString("status") ?: "ACCEPTED"
                        val joined = rs.getString("joined_at")
                        val activeVal = rs.getBoolean("is_data_sharing_active")

                        val newAllianceId = oldToNewAllianceId[oldAllianceId]
                        if (newAllianceId != null) {
                            transaction {
                                val exists = AllianceMemberships.selectAll().where {
                                    (AllianceMemberships.allianceId eq newAllianceId) and
                                    (AllianceMemberships.teamNumber eq teamNum)
                                }.any()

                                if (!exists) {
                                    AllianceMemberships.insert {
                                        it[allianceId] = EntityID(newAllianceId, ScoutingAlliances)
                                        it[teamNumber] = teamNum
                                        it[status] = statusVal.uppercase()
                                        it[invitedAt] = parseInstant(joined)
                                        it[respondedAt] = parseInstant(joined)
                                        it[disabled] = false
                                        it[active] = activeVal
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        updateStatus(alliances = alliancesMigrated, prog = 98)
    }
}
