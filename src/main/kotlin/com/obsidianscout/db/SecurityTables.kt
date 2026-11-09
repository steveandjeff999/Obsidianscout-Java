package com.obsidianscout.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Security events kept for investigation: failed logins, scans, probes, blocks, unblocks, superadmin actions.
 * Written in batches by [com.obsidianscout.security.SecurityLog]; old rows are deleted by its retention job.
 */
object SecurityEventsTable : UUIDTable("security_events") {
    val at = timestamp("at")
    val type = varchar("type", 40)
    val severity = varchar("severity", 10)
    val ip = varchar("ip", 64)
    val userId = varchar("user_id", 64).nullable()
    val username = varchar("username", 128).nullable()
    val teamNumber = integer("team_number").nullable()
    val method = varchar("method", 10).default("")
    val path = varchar("path", 512).default("")
    val userAgent = varchar("user_agent", 256).nullable()
    val details = varchar("details", 1024).default("")
    val node = varchar("node", 64).default("")

    init {
        index("idx_security_events_at", false, at)
        index("idx_security_events_ip_at", false, ip, at)
        index("idx_security_events_user_at", false, userId, at)
        index("idx_security_events_type_at", false, type, at)
    }
}

/**
 * IP blocks, active and past. A block is active while [liftedAt] is null and [expiresAt] is in the future.
 * [expiresAt] is never more than one hour after [createdAt] (see [com.obsidianscout.security.BlockList.MAX_BLOCK_MS]).
 */
object IpBlocksTable : UUIDTable("ip_blocks") {
    val ipOrCidr = varchar("ip_or_cidr", 64)
    val reason = varchar("reason", 512)
    val triggeringEventTypes = varchar("triggering_event_types", 512).default("")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val createdBy = varchar("created_by", 128)
    val strike = integer("strike").default(1)
    val hits = long("hits").default(0)
    val liftedAt = timestamp("lifted_at").nullable()
    val liftedBy = varchar("lifted_by", 128).nullable()
    val liftReason = varchar("lift_reason", 512).nullable()

    init {
        index("idx_ip_blocks_ip", false, ipOrCidr)
        index("idx_ip_blocks_expires", false, expiresAt)
    }
}

/** Networks that are never blocked automatically (schools, venues). [expiresAt] null means until removed. */
object IpAllowlistTable : UUIDTable("ip_allowlist") {
    val ipOrCidr = varchar("ip_or_cidr", 64)
    val note = varchar("note", 256).default("")
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 128)
    val expiresAt = timestamp("expires_at").nullable()

    init {
        index("idx_ip_allowlist_ip", false, ipOrCidr)
    }
}

/** Requests from a blocked visitor to have their block lifted early. */
object UnblockRequestsTable : UUIDTable("unblock_requests") {
    val blockId = javaUUID("block_id")
    val ip = varchar("ip", 64)
    val name = varchar("name", 100)
    val teamNumber = integer("team_number").nullable()
    val email = varchar("email", 255).nullable()
    val message = varchar("message", 1000)
    /** pending | approved | denied | expired */
    val status = varchar("status", 16).default("pending")
    val highRisk = bool("high_risk").default(false)
    val createdAt = timestamp("created_at")
    val reviewedBy = varchar("reviewed_by", 128).nullable()
    val reviewedAt = timestamp("reviewed_at").nullable()
    val reviewNote = varchar("review_note", 500).nullable()

    init {
        index("idx_unblock_requests_status", false, status, createdAt)
        index("idx_unblock_requests_block", false, blockId)
    }
}
