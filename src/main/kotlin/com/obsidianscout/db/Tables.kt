package com.obsidianscout.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : UUIDTable("users") {
    val username = varchar("username", 64)
    val teamNumber = integer("team_number")
    val program = varchar("program", 8).default("FRC")
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 16)
    val createdAt = timestamp("created_at")
    val email = varchar("email", 255).nullable()
    val profilePicture = text("profile_picture").nullable()
    val notificationPreference = varchar("notification_preference", 16).default("all")
    val tourProgress = text("tour_progress").nullable()
    val nodeAlertsEnabled = bool("node_alerts_enabled").default(false)

    init {
        uniqueIndex("ux_users_username_team_program", username, teamNumber, program)
    }
}

object ScoutingConfigs : UUIDTable("scouting_configs") {
    val teamNumber = integer("team_number").default(0)
    val program = varchar("program", 8).default("FRC")
    val configJson = text("config_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_scouting_configs_team_program", teamNumber, program)
    }
}

object PitScoutingConfigs : UUIDTable("pit_scouting_configs") {
    val teamNumber = integer("team_number").default(0)
    val program = varchar("program", 8).default("FRC")
    val configJson = text("config_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_pit_scouting_configs_team_program", teamNumber, program)
    }
}

object QualitativeScoutingConfigs : UUIDTable("qualitative_scouting_configs") {
    val teamNumber = integer("team_number").default(0)
    val program = varchar("program", 8).default("FRC")
    val configJson = text("config_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_qualitative_scouting_configs_team_program", teamNumber, program)
    }
}

object DefaultConfigs : UUIDTable("default_configs") {
    val name = varchar("name", 64)
    val program = varchar("program", 8).default("FRC")
    val configType = varchar("config_type", 16).default("match")
    val configJson = text("config_json")
    val isDefault = bool("is_default").default(false)
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_default_configs_name_program_type", name, program, configType)
    }
}

object ScoutingEntries : UUIDTable("scouting_entries") {
    val ownerTeamNumber = integer("owner_team_number")
    val program = varchar("program", 8).default("FRC")
    val targetTeamNumber = integer("target_team_number").nullable()
    val eventKey = varchar("event_key", 64).nullable()
    val matchKey = varchar("match_key", 64).nullable()
    val matchNumber = integer("match_number").nullable()
    val dataJson = text("data_json")
    val submittedByUserId = reference("submitted_by_user_id", Users)
    val createdAt = timestamp("created_at")
    val isPrescout = bool("is_prescout").default(false)
    val hasDiscrepancy = bool("has_discrepancy").default(false)
    val conflictingTeams = varchar("conflicting_teams", 255).default("")
}

object PitScoutingEntries : UUIDTable("pit_scouting_entries") {
    val ownerTeamNumber = integer("owner_team_number")
    val program = varchar("program", 8).default("FRC")
    val targetTeamNumber = integer("target_team_number").nullable()
    val eventKey = varchar("event_key", 64).nullable()
    val dataJson = text("data_json")
    val submittedByUserId = reference("submitted_by_user_id", Users)
    val createdAt = timestamp("created_at")
    val isPrescout = bool("is_prescout").default(false)
    val hasDiscrepancy = bool("has_discrepancy").default(false)
    val conflictingTeams = varchar("conflicting_teams", 255).default("")
}

object QualitativeScoutingEntries : UUIDTable("qualitative_scouting_entries") {
    val ownerTeamNumber = integer("owner_team_number")
    val program = varchar("program", 8).default("FRC")
    val targetTeamNumber = integer("target_team_number").nullable()
    val eventKey = varchar("event_key", 64).nullable()
    val matchKey = varchar("match_key", 64).nullable()
    val matchNumber = integer("match_number").nullable()
    val dataJson = text("data_json")
    val submittedByUserId = reference("submitted_by_user_id", Users)
    val createdAt = timestamp("created_at")
    val isPrescout = bool("is_prescout").default(false)
    val hasDiscrepancy = bool("has_discrepancy").default(false)
    val conflictingTeams = varchar("conflicting_teams", 255).default("")
}

object AppSettings : UUIDTable("app_settings") {
    val teamNumber = integer("team_number").default(0)
    val program = varchar("program", 8).default("FRC")
    val settingsJson = text("settings_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_app_settings_team_program", teamNumber, program)
    }
}

object ApiEvents : UUIDTable("api_events") {
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

object ApiTeams : UUIDTable("api_teams") {
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

object ApiMatches : UUIDTable("api_matches") {
    val matchKey = varchar("match_key", 64)
    val eventKey = varchar("event_key", 64)
    val compLevel = varchar("comp_level", 16)
    val setNumber = integer("set_number").nullable()
    val matchNumber = integer("match_number").nullable()
    val scheduledTime = long("scheduled_time").nullable()
    val actualTime = long("actual_time").nullable()
    val redTeams = text("red_teams")
    val blueTeams = text("blue_teams")
    val dataJson = text("data_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_api_matches_key", matchKey)
    }
}

object ScoutingAlliances : UUIDTable("scouting_alliances") {
    val name = varchar("name", 128)
    val ownerTeamNumber = integer("owner_team_number")
    val program = varchar("program", 8).default("FRC")
    val eventKey = varchar("event_key", 64).nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val matchConfigJson = text("match_config_json").nullable()
    val pitConfigJson = text("pit_config_json").nullable()
    val qualitativeConfigJson = text("qualitative_config_json").nullable()
    val year = integer("year").nullable()
    val eventCode = varchar("event_code", 32).nullable()
}

object AllianceMemberships : UUIDTable("alliance_memberships") {
    val allianceId = reference("alliance_id", ScoutingAlliances)
    val teamNumber = integer("team_number")
    val program = varchar("program", 8).default("FRC")
    /** ADMIN | INVITED | ACCEPTED | DECLINED */
    val status = varchar("status", 16)
    val invitedAt = timestamp("invited_at")
    val respondedAt = timestamp("responded_at").nullable()
    val disabled = bool("disabled").default(false)
    val active = bool("active").default(false)

    init {
        uniqueIndex("ux_alliance_memberships_alliance_team_program", allianceId, teamNumber, program)
        index("idx_alliance_memberships_team_active_program", false, teamNumber, active, program)
    }
}

object EpaOprHistoryCache : UUIDTable("epa_opr_history_cache") {
    val eventKey = varchar("event_key", 64)
    val oprsJson = text("oprs_json")
    val epaHistoryJson = text("epa_history_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_epa_opr_history_cache_event", eventKey)
    }
}

object PasswordResetTokens : UUIDTable("password_reset_tokens") {
    val userId = reference("user_id", Users).nullable()
    val email = varchar("email", 255).nullable()
    val token = varchar("token", 128)
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)

    init {
        uniqueIndex("ux_password_reset_tokens_token", token)
    }
}

object AllianceSelections : UUIDTable("alliance_selections") {
    val ownerKey = varchar("owner_key", 64)
    val eventKey = varchar("event_key", 64)
    val selectionJson = text("selection_json")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_alliance_selections_owner_event", ownerKey, eventKey)
    }
}

object Banners : UUIDTable("banners") {
    val teamNumber = integer("team_number").default(0)
    val program = varchar("program", 8).default("FRC")
    val message = text("message")
    val bannerType = varchar("banner_type", 32).default("info")
    val isDismissible = bool("is_dismissible").default(true)
    val isExpandable = bool("is_expandable").default(false)
    val expandableMessage = text("expandable_message").default("")
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object ChatMessages : UUIDTable("chat_messages") {
    val teamNumber = integer("team_number")
    val program = varchar("program", 8).default("FRC")
    val groupName = varchar("group_name", 64)
    val userId = reference("user_id", Users)
    val username = varchar("username", 64)
    val content = text("content")
    val createdAt = timestamp("created_at")
    val reactionsJson = text("reactions_json").default("{}")

    init {
        index("idx_chat_messages_team_group_program", false, teamNumber, groupName, program)
        index("idx_chat_messages_team_group_created", false, teamNumber, groupName, createdAt)
    }
}

object UserChatLastRead : UUIDTable("user_chat_last_read") {
    val userId = reference("user_id", Users)
    val groupName = varchar("group_name", 64)
    val lastReadAt = timestamp("last_read_at")

    init {
        uniqueIndex("ux_user_chat_last_read_user_group", userId, groupName)
    }
}

object ChatGroups : UUIDTable("chat_groups") {
    val teamNumber = integer("team_number")
    val program = varchar("program", 8).default("FRC")
    val groupName = varchar("group_name", 64)
    val createdByUserId = reference("created_by_user_id", Users).nullable()
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("ux_chat_groups_team_program_name", teamNumber, program, groupName)
    }
}

object PushSubscriptions : UUIDTable("push_subscriptions") {
    val userId = reference("user_id", Users)
    val endpoint = text("endpoint")
    val p256dh = varchar("p256dh", 255)
    val auth = varchar("auth", 255)
    val createdAt = timestamp("created_at")

    init {
        uniqueIndex("ux_push_subscriptions_endpoint", endpoint)
    }
}

object FcmConfigs : UUIDTable("fcm_config") {
    val projectId = varchar("project_id", 128).default("")
    val apiKey = varchar("api_key", 128).default("")
    val appId = varchar("app_id", 128).default("")
    val messagingSenderId = varchar("messaging_sender_id", 128).default("")
    val serviceAccountJson = text("service_account_json").default("")
    val vapidKey = varchar("vapid_key", 255).default("")
    val enabled = bool("enabled").default(false)
    val updatedAt = timestamp("updated_at")
}

object FcmDeviceTokens : UUIDTable("fcm_device_tokens") {
    val userId = reference("user_id", Users)
    val deviceToken = varchar("device_token", 512)
    val platform = varchar("platform", 32).default("android")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_fcm_device_tokens_token", deviceToken)
        index("idx_fcm_device_tokens_user", false, userId)
    }
}

object ClusterSecrets : UUIDTable("cluster_secrets") {
    val keyName = varchar("key_name", 64)
    val keyValue = text("key_value")
    val updatedAt = timestamp("updated_at")

    init {
        uniqueIndex("ux_cluster_secrets_key_name", keyName)
    }
}

object ClusterNotificationLocks : UUIDTable("cluster_notification_locks") {
    val lockKey = varchar("lock_key", 128)
    val claimedByNode = varchar("claimed_by_node", 64)
    val claimedAt = timestamp("claimed_at")
    val expiresAt = timestamp("expires_at")

    init {
        uniqueIndex("ux_cluster_notification_locks_key", lockKey)
    }
}





