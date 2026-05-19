package com.obsidianscout.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : IntIdTable("users") {
    val username = varchar("username", 64)
    val teamNumber = integer("team_number")
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 16)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("ux_users_username_team", username, teamNumber)
    }
}

object ScoutingConfigs : IntIdTable("scouting_configs") {
    val teamNumber = integer("team_number").default(0)
    val configJson = text("config_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_scouting_configs_team", teamNumber)
    }
}

object ScoutingEntries : IntIdTable("scouting_entries") {
    val ownerTeamNumber = integer("owner_team_number")
    val targetTeamNumber = integer("target_team_number").nullable()
    val eventKey = varchar("event_key", 64).nullable()
    val matchKey = varchar("match_key", 64).nullable()
    val matchNumber = integer("match_number").nullable()
    val dataJson = text("data_json")
    val submittedByUserId = reference("submitted_by_user_id", Users)
    val createdAt = timestamp("created_at")
}

object AppSettings : IntIdTable("app_settings") {
    val teamNumber = integer("team_number").default(0)
    val settingsJson = text("settings_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_app_settings_team", teamNumber)
    }
}

object ApiEvents : IntIdTable("api_events") {
    val eventKey = varchar("event_key", 64)
    val year = integer("year")
    val eventCode = varchar("event_code", 32).nullable()
    val name = varchar("name", 512)
    val startDate = varchar("start_date", 32).nullable()
    val endDate = varchar("end_date", 32).nullable()
    val timezone = varchar("timezone", 64).nullable()
    val dataJson = text("data_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_api_events_key", eventKey)
    }
}

object ApiTeams : IntIdTable("api_teams") {
    val eventKey = varchar("event_key", 64)
    val teamKey = varchar("team_key", 32)
    val teamNumber = integer("team_number")
    val name = varchar("name", 512).nullable()
    val nickname = varchar("nickname", 512).nullable()
    val city = varchar("city", 80).nullable()
    val state = varchar("state", 80).nullable()
    val country = varchar("country", 80).nullable()
    val opr = double("opr").nullable()
    val epa = double("epa").nullable()
    val dataJson = text("data_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_api_teams_event_team", eventKey, teamKey)
    }
}

object ApiMatches : IntIdTable("api_matches") {
    val matchKey = varchar("match_key", 64)
    val eventKey = varchar("event_key", 64)
    val compLevel = varchar("comp_level", 16)
    val setNumber = integer("set_number").nullable()
    val matchNumber = integer("match_number").nullable()
    val scheduledTime = long("scheduled_time").nullable()
    val redTeams = text("red_teams")
    val blueTeams = text("blue_teams")
    val dataJson = text("data_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_api_matches_key", matchKey)
    }
}
