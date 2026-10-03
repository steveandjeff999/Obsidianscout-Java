package com.obsidianscout.scouting

import at.favre.lib.crypto.bcrypt.BCrypt
import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.integrations.IntegrationService
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.routes.TeamRecord
import com.obsidianscout.routes.StatsHistoryResponse
import com.obsidianscout.db.*
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import com.obsidianscout.analytics.AnalyticsReportService
import com.obsidianscout.analytics.AnalyticsDatasetResponse
import com.obsidianscout.analytics.PredictorService
import com.obsidianscout.routes.MatchPredictionResponse
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class CreateShareRequest(
    val title: String,
    val description: String? = null,
    val resourceType: String, // graph, predictor, event_predictor, all_data, qual_data, pit_data, match_data, custom_analytics, match_planning
    val targetEventKey: String? = null,
    val shareMode: String = "live_query", // live_query, frozen_snapshot
    val queryConfigJson: String = "{}",
    val snapshotDataJson: String? = null,
    val accessScope: String = "public", // public, team, alliance, pin
    val allowedTeams: String? = null, // e.g. "254,1678,1114"
    val pin: String? = null,
    val redactPrivateNotes: Boolean = true,
    val redactScoutNames: Boolean = false,
    val expiresAt: String? = null // ISO-8601 string or null for never
)

@Serializable
data class UpdateShareRequest(
    val title: String? = null,
    val description: String? = null,
    val accessScope: String? = null,
    val allowedTeams: String? = null,
    val pin: String? = null,
    val redactPrivateNotes: Boolean? = null,
    val redactScoutNames: Boolean? = null,
    val expiresAt: String? = null // ISO-8601 string, empty string to clear, or null to keep current
)

@Serializable
data class ShareLinkDTO(
    val id: String,
    val token: String,
    val ownerTeamNumber: Int,
    val program: String,
    val createdByUsername: String,
    val title: String,
    val description: String?,
    val resourceType: String,
    val targetEventKey: String?,
    val shareMode: String,
    val queryConfigJson: String,
    val accessScope: String,
    val allowedTeams: String?,
    val hasPin: Boolean,
    val redactPrivateNotes: Boolean,
    val redactScoutNames: Boolean,
    val createdAt: String,
    val expiresAt: String?,
    val isExpired: Boolean,
    val isRevoked: Boolean,
    val revokedAt: String?,
    val revokedByUsername: String?,
    val viewCount: Int,
    val lastAccessedAt: String?,
    val shareUrl: String? = null
)

@Serializable
data class ResolvedSharePayload(
    val token: String,
    val title: String,
    val description: String?,
    val resourceType: String,
    val targetEventKey: String?,
    val shareMode: String,
    val queryConfigJson: String,
    val snapshotDataJson: String?,
    val liveDataJson: String?,
    val ownerTeamNumber: Int,
    val program: String,
    val createdByUsername: String,
    val createdAt: String,
    val expiresAt: String?,
    val isExpired: Boolean,
    val isRevoked: Boolean,
    val accessScope: String,
    val requiresPin: Boolean,
    val pinVerified: Boolean = false,
    val isAllowed: Boolean = true,
    val errorMessage: String? = null
)

@Serializable
data class VerifyPinRequest(
    val pin: String
)

object ShareService {
    private val secureRandom = SecureRandom()
    private const val TOKEN_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun generateSecureToken(length: Int = 22): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(TOKEN_ALPHABET[secureRandom.nextInt(TOKEN_ALPHABET.length)])
        }
        return sb.toString()
    }

    private fun hashPin(pin: String): String {
        return BCrypt.withDefaults().hashToString(10, pin.toCharArray())
    }

    private fun verifyPin(pin: String, hash: String): Boolean {
        return try {
            BCrypt.verifyer().verify(pin.toCharArray(), hash).verified
        } catch (_: Exception) {
            false
        }
    }

    fun createShare(session: UserSession, request: CreateShareRequest, baseUrl: String = ""): ShareLinkDTO {
        val trimmedTitle = request.title.trim()
        if (trimmedTitle.isEmpty()) {
            throw ApiException(HttpStatusCode.BadRequest, "Share title cannot be blank")
        }

        val parsedExpiresAt = request.expiresAt?.let {
            try {
                if (it.isNotBlank()) Instant.parse(it) else null
            } catch (e: Exception) {
                throw ApiException(HttpStatusCode.BadRequest, "Invalid ISO-8601 format for expiresAt")
            }
        }

        val pinHashVal = if (!request.pin.isNullOrBlank() && (request.accessScope == "pin" || request.accessScope == "pin_protected")) {
            hashPin(request.pin.trim())
        } else null

        val scope = if (pinHashVal != null) "pin" else request.accessScope.lowercase()

        val tokenStr = generateSecureToken()
        val now = Instant.now()

        val newId = transaction {
            SharedLinks.insertAndGetId {
                it[token] = tokenStr
                it[ownerTeamNumber] = session.teamNumber
                it[program] = session.program
                it[createdByUsername] = session.username
                it[title] = trimmedTitle.take(128)
                it[description] = request.description?.take(1000)
                it[resourceType] = request.resourceType.lowercase()
                it[targetEventKey] = request.targetEventKey
                it[shareMode] = request.shareMode.lowercase()
                it[queryConfigJson] = request.queryConfigJson
                it[snapshotDataJson] = request.snapshotDataJson
                it[accessScope] = scope
                it[allowedTeams] = request.allowedTeams
                it[pinHash] = pinHashVal
                it[redactPrivateNotes] = request.redactPrivateNotes
                it[redactScoutNames] = request.redactScoutNames
                it[createdAt] = now
                it[expiresAt] = parsedExpiresAt
                it[isRevoked] = false
                it[viewCount] = 0
            }
        }

        val shareUrl = if (baseUrl.isNotBlank()) {
            val cleanBase = baseUrl.trimEnd('/')
            "$cleanBase/shared/$tokenStr"
        } else "/shared/$tokenStr"

        return ShareLinkDTO(
            id = newId.value.toString(),
            token = tokenStr,
            ownerTeamNumber = session.teamNumber,
            program = session.program,
            createdByUsername = session.username,
            title = trimmedTitle,
            description = request.description,
            resourceType = request.resourceType.lowercase(),
            targetEventKey = request.targetEventKey,
            shareMode = request.shareMode.lowercase(),
            queryConfigJson = request.queryConfigJson,
            accessScope = scope,
            allowedTeams = request.allowedTeams,
            hasPin = pinHashVal != null,
            redactPrivateNotes = request.redactPrivateNotes,
            redactScoutNames = request.redactScoutNames,
            createdAt = now.toString(),
            expiresAt = parsedExpiresAt?.toString(),
            isExpired = parsedExpiresAt?.isBefore(now) == true,
            isRevoked = false,
            revokedAt = null,
            revokedByUsername = null,
            viewCount = 0,
            lastAccessedAt = null,
            shareUrl = shareUrl
        )
    }

    fun listTeamShares(
        teamNumber: Int,
        program: String,
        statusFilter: String? = null,
        baseUrl: String = ""
    ): List<ShareLinkDTO> {
        val now = Instant.now()
        return readTransaction {
            val query = SharedLinks.selectAll()
                .where { (SharedLinks.ownerTeamNumber eq teamNumber) and (SharedLinks.program eq program) }

            when (statusFilter?.lowercase()) {
                "active" -> {
                    query.andWhere {
                        (SharedLinks.isRevoked eq false) and
                        ((SharedLinks.expiresAt.isNull()) or (SharedLinks.expiresAt greater now))
                    }
                }
                "expired" -> {
                    query.andWhere {
                        (SharedLinks.isRevoked eq false) and
                        (SharedLinks.expiresAt.isNotNull()) and
                        (SharedLinks.expiresAt lessEq now)
                    }
                }
                "revoked" -> {
                    query.andWhere { SharedLinks.isRevoked eq true }
                }
            }

            query.orderBy(SharedLinks.createdAt to SortOrder.DESC).map { row ->
                val expiresAtInstant = row[SharedLinks.expiresAt]
                val isExpired = expiresAtInstant != null && expiresAtInstant.isBefore(now)
                val tokenStr = row[SharedLinks.token]
                val cleanBase = baseUrl.trimEnd('/')
                val shareUrl = if (cleanBase.isNotBlank()) "$cleanBase/shared/$tokenStr" else "/shared/$tokenStr"

                ShareLinkDTO(
                    id = row[SharedLinks.id].value.toString(),
                    token = tokenStr,
                    ownerTeamNumber = row[SharedLinks.ownerTeamNumber],
                    program = row[SharedLinks.program],
                    createdByUsername = row[SharedLinks.createdByUsername],
                    title = row[SharedLinks.title],
                    description = row[SharedLinks.description],
                    resourceType = row[SharedLinks.resourceType],
                    targetEventKey = row[SharedLinks.targetEventKey],
                    shareMode = row[SharedLinks.shareMode],
                    queryConfigJson = row[SharedLinks.queryConfigJson],
                    accessScope = row[SharedLinks.accessScope],
                    allowedTeams = row[SharedLinks.allowedTeams],
                    hasPin = row[SharedLinks.pinHash] != null,
                    redactPrivateNotes = row[SharedLinks.redactPrivateNotes],
                    redactScoutNames = row[SharedLinks.redactScoutNames],
                    createdAt = row[SharedLinks.createdAt].toString(),
                    expiresAt = expiresAtInstant?.toString(),
                    isExpired = isExpired,
                    isRevoked = row[SharedLinks.isRevoked],
                    revokedAt = row[SharedLinks.revokedAt]?.toString(),
                    revokedByUsername = row[SharedLinks.revokedByUsername],
                    viewCount = row[SharedLinks.viewCount],
                    lastAccessedAt = row[SharedLinks.lastAccessedAt]?.toString(),
                    shareUrl = shareUrl
                )
            }
        }
    }

    fun getShareByToken(token: String): ShareLinkDTO? {
        val now = Instant.now()
        return readTransaction {
            SharedLinks.selectAll().where { SharedLinks.token eq token }.firstOrNull()?.let { row ->
                val expiresAtInstant = row[SharedLinks.expiresAt]
                ShareLinkDTO(
                    id = row[SharedLinks.id].value.toString(),
                    token = row[SharedLinks.token],
                    ownerTeamNumber = row[SharedLinks.ownerTeamNumber],
                    program = row[SharedLinks.program],
                    createdByUsername = row[SharedLinks.createdByUsername],
                    title = row[SharedLinks.title],
                    description = row[SharedLinks.description],
                    resourceType = row[SharedLinks.resourceType],
                    targetEventKey = row[SharedLinks.targetEventKey],
                    shareMode = row[SharedLinks.shareMode],
                    queryConfigJson = row[SharedLinks.queryConfigJson],
                    accessScope = row[SharedLinks.accessScope],
                    allowedTeams = row[SharedLinks.allowedTeams],
                    hasPin = row[SharedLinks.pinHash] != null,
                    redactPrivateNotes = row[SharedLinks.redactPrivateNotes],
                    redactScoutNames = row[SharedLinks.redactScoutNames],
                    createdAt = row[SharedLinks.createdAt].toString(),
                    expiresAt = expiresAtInstant?.toString(),
                    isExpired = expiresAtInstant != null && expiresAtInstant.isBefore(now),
                    isRevoked = row[SharedLinks.isRevoked],
                    revokedAt = row[SharedLinks.revokedAt]?.toString(),
                    revokedByUsername = row[SharedLinks.revokedByUsername],
                    viewCount = row[SharedLinks.viewCount],
                    lastAccessedAt = row[SharedLinks.lastAccessedAt]?.toString()
                )
            }
        }
    }

    fun resolveShare(
        token: String,
        pin: String? = null,
        session: UserSession? = null,
        clientIp: String? = null,
        userAgent: String? = null
    ): ResolvedSharePayload {
        val now = Instant.now()
        val row = transaction {
            SharedLinks.selectAll().where { SharedLinks.token eq token }.firstOrNull()
        } ?: throw ApiException(HttpStatusCode.NotFound, "Shared link not found")

        val isRevoked = row[SharedLinks.isRevoked]
        if (isRevoked) {
            return ResolvedSharePayload(
                token = token,
                title = row[SharedLinks.title],
                description = row[SharedLinks.description],
                resourceType = row[SharedLinks.resourceType],
                targetEventKey = row[SharedLinks.targetEventKey],
                shareMode = row[SharedLinks.shareMode],
                queryConfigJson = "{}",
                snapshotDataJson = null,
                liveDataJson = null,
                ownerTeamNumber = row[SharedLinks.ownerTeamNumber],
                program = row[SharedLinks.program],
                createdByUsername = row[SharedLinks.createdByUsername],
                createdAt = row[SharedLinks.createdAt].toString(),
                expiresAt = row[SharedLinks.expiresAt]?.toString(),
                isExpired = false,
                isRevoked = true,
                accessScope = row[SharedLinks.accessScope],
                requiresPin = false,
                isAllowed = false,
                errorMessage = "This share link has been revoked by the owner team."
            )
        }

        val expiresAt = row[SharedLinks.expiresAt]
        val isExpired = expiresAt != null && expiresAt.isBefore(now)
        if (isExpired) {
            return ResolvedSharePayload(
                token = token,
                title = row[SharedLinks.title],
                description = row[SharedLinks.description],
                resourceType = row[SharedLinks.resourceType],
                targetEventKey = row[SharedLinks.targetEventKey],
                shareMode = row[SharedLinks.shareMode],
                queryConfigJson = "{}",
                snapshotDataJson = null,
                liveDataJson = null,
                ownerTeamNumber = row[SharedLinks.ownerTeamNumber],
                program = row[SharedLinks.program],
                createdByUsername = row[SharedLinks.createdByUsername],
                createdAt = row[SharedLinks.createdAt].toString(),
                expiresAt = expiresAt.toString(),
                isExpired = true,
                isRevoked = false,
                accessScope = row[SharedLinks.accessScope],
                requiresPin = false,
                isAllowed = false,
                errorMessage = "This shared link expired on $expiresAt."
            )
        }

        val accessScope = row[SharedLinks.accessScope]
        val pinHash = row[SharedLinks.pinHash]
        val ownerTeam = row[SharedLinks.ownerTeamNumber]
        val program = row[SharedLinks.program]

        // Check PIN requirement
        var pinVerified = false
        if (!pinHash.isNullOrBlank()) {
            if (pin.isNullOrBlank()) {
                return ResolvedSharePayload(
                    token = token,
                    title = row[SharedLinks.title],
                    description = row[SharedLinks.description],
                    resourceType = row[SharedLinks.resourceType],
                    targetEventKey = row[SharedLinks.targetEventKey],
                    shareMode = row[SharedLinks.shareMode],
                    queryConfigJson = "{}",
                    snapshotDataJson = null,
                    liveDataJson = null,
                    ownerTeamNumber = ownerTeam,
                    program = program,
                    createdByUsername = row[SharedLinks.createdByUsername],
                    createdAt = row[SharedLinks.createdAt].toString(),
                    expiresAt = expiresAt?.toString(),
                    isExpired = false,
                    isRevoked = false,
                    accessScope = accessScope,
                    requiresPin = true,
                    pinVerified = false,
                    isAllowed = false,
                    errorMessage = "This link is protected with a PIN. Please enter the PIN to view."
                )
            }

            if (!verifyPin(pin.trim(), pinHash)) {
                return ResolvedSharePayload(
                    token = token,
                    title = row[SharedLinks.title],
                    description = row[SharedLinks.description],
                    resourceType = row[SharedLinks.resourceType],
                    targetEventKey = row[SharedLinks.targetEventKey],
                    shareMode = row[SharedLinks.shareMode],
                    queryConfigJson = "{}",
                    snapshotDataJson = null,
                    liveDataJson = null,
                    ownerTeamNumber = ownerTeam,
                    program = program,
                    createdByUsername = row[SharedLinks.createdByUsername],
                    createdAt = row[SharedLinks.createdAt].toString(),
                    expiresAt = expiresAt?.toString(),
                    isExpired = false,
                    isRevoked = false,
                    accessScope = accessScope,
                    requiresPin = true,
                    pinVerified = false,
                    isAllowed = false,
                    errorMessage = "Incorrect PIN entered."
                )
            }
            pinVerified = true
        }

        // Check Team / Alliance Access scopes
        if (accessScope == "team") {
            if (session == null || session.teamNumber != ownerTeam || session.program != program) {
                return ResolvedSharePayload(
                    token = token,
                    title = row[SharedLinks.title],
                    description = row[SharedLinks.description],
                    resourceType = row[SharedLinks.resourceType],
                    targetEventKey = row[SharedLinks.targetEventKey],
                    shareMode = row[SharedLinks.shareMode],
                    queryConfigJson = "{}",
                    snapshotDataJson = null,
                    liveDataJson = null,
                    ownerTeamNumber = ownerTeam,
                    program = program,
                    createdByUsername = row[SharedLinks.createdByUsername],
                    createdAt = row[SharedLinks.createdAt].toString(),
                    expiresAt = expiresAt?.toString(),
                    isExpired = false,
                    isRevoked = false,
                    accessScope = accessScope,
                    requiresPin = false,
                    isAllowed = false,
                    errorMessage = "This link is restricted to members of Team $ownerTeam ($program). Please log in with an authorized account."
                )
            }
        } else if (accessScope == "alliance") {
            val allowedTeamsStr = row[SharedLinks.allowedTeams] ?: ""
            val allowedList = allowedTeamsStr.split(",", ";", " ")
                .mapNotNull { it.trim().toIntOrNull() }
                .toMutableSet()
            allowedList.add(ownerTeam)

            val userTeam = session?.teamNumber
            if (userTeam == null || !allowedList.contains(userTeam)) {
                return ResolvedSharePayload(
                    token = token,
                    title = row[SharedLinks.title],
                    description = row[SharedLinks.description],
                    resourceType = row[SharedLinks.resourceType],
                    targetEventKey = row[SharedLinks.targetEventKey],
                    shareMode = row[SharedLinks.shareMode],
                    queryConfigJson = "{}",
                    snapshotDataJson = null,
                    liveDataJson = null,
                    ownerTeamNumber = ownerTeam,
                    program = program,
                    createdByUsername = row[SharedLinks.createdByUsername],
                    createdAt = row[SharedLinks.createdAt].toString(),
                    expiresAt = expiresAt?.toString(),
                    isExpired = false,
                    isRevoked = false,
                    accessScope = accessScope,
                    requiresPin = false,
                    isAllowed = false,
                    errorMessage = "This link is restricted to authorized alliance teams. Please log in with a permitted team account."
                )
            }
        }

        // Access Granted - Record view count & access log
        val linkId = row[SharedLinks.id]
        transaction {
            SharedLinks.update({ SharedLinks.id eq linkId }) {
                with(SqlExpressionBuilder) {
                    it.update(SharedLinks.viewCount, SharedLinks.viewCount + 1)
                }
                it[SharedLinks.lastAccessedAt] = now
            }

            SharedLinkAccessLogs.insert {
                it[SharedLinkAccessLogs.sharedLinkId] = linkId
                it[SharedLinkAccessLogs.accessedAt] = now
                it[SharedLinkAccessLogs.ipAddress] = clientIp?.take(64)
                it[SharedLinkAccessLogs.userAgent] = userAgent?.take(255)
                it[SharedLinkAccessLogs.userTeamNumber] = session?.teamNumber
                it[SharedLinkAccessLogs.username] = session?.username
            }
        }

        val shareMode = row[SharedLinks.shareMode]
        val redactNotes = row[SharedLinks.redactPrivateNotes]
        val redactNames = row[SharedLinks.redactScoutNames]

        // If live_query mode, fetch and sanitize live scouting data if relevant
        val liveData = if (shareMode == "live_query") {
            fetchLiveDataForShare(
                ownerTeamNumber = ownerTeam,
                program = program,
                eventKey = row[SharedLinks.targetEventKey],
                resourceType = row[SharedLinks.resourceType],
                queryConfigJson = row[SharedLinks.queryConfigJson],
                redactNotes = redactNotes,
                redactNames = redactNames
            )
        } else null

        // If frozen snapshot mode, optionally redact fields in snapshot if needed
        val finalSnapshot = sanitizeJsonPayload(
            jsonStr = row[SharedLinks.snapshotDataJson],
            redactNotes = redactNotes,
            redactNames = redactNames
        )

        return ResolvedSharePayload(
            token = token,
            title = row[SharedLinks.title],
            description = row[SharedLinks.description],
            resourceType = row[SharedLinks.resourceType],
            targetEventKey = row[SharedLinks.targetEventKey],
            shareMode = shareMode,
            queryConfigJson = row[SharedLinks.queryConfigJson],
            snapshotDataJson = finalSnapshot,
            liveDataJson = liveData,
            ownerTeamNumber = ownerTeam,
            program = program,
            createdByUsername = row[SharedLinks.createdByUsername],
            createdAt = row[SharedLinks.createdAt].toString(),
            expiresAt = expiresAt?.toString(),
            isExpired = false,
            isRevoked = false,
            accessScope = accessScope,
            requiresPin = !pinHash.isNullOrBlank(),
            pinVerified = pinVerified,
            isAllowed = true,
            errorMessage = null
        )
    }

    fun revokeShare(token: String, session: UserSession): Boolean {
        val now = Instant.now()
        return transaction {
            val target = SharedLinks.selectAll().where { SharedLinks.token eq token }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Share link not found")

            // Team check
            if (target[SharedLinks.ownerTeamNumber] != session.teamNumber && session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to revoke this link")
            }

            SharedLinks.update({ SharedLinks.token eq token }) {
                it[isRevoked] = true
                it[revokedAt] = now
                it[revokedByUsername] = session.username
                it[snapshotDataJson] = null
            } > 0
        }
    }

    fun updateShare(token: String, req: UpdateShareRequest, session: UserSession, baseUrl: String = ""): ShareLinkDTO {
        transaction {
            val target = SharedLinks.selectAll().where { SharedLinks.token eq token }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Share link not found")

            if (target[SharedLinks.ownerTeamNumber] != session.teamNumber && session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to update this link")
            }

            SharedLinks.update({ SharedLinks.token eq token }) {
                if (req.title != null && req.title.isNotBlank()) {
                    it[title] = req.title.trim().take(128)
                }
                if (req.description != null) {
                    it[description] = req.description.take(1000)
                }
                if (req.accessScope != null && req.accessScope.isNotBlank()) {
                    it[accessScope] = req.accessScope.lowercase()
                }
                if (req.allowedTeams != null) {
                    it[allowedTeams] = req.allowedTeams
                }
                if (req.pin != null) {
                    if (req.pin.isBlank()) {
                        it[pinHash] = null
                    } else {
                        it[pinHash] = hashPin(req.pin.trim())
                    }
                }
                if (req.redactPrivateNotes != null) {
                    it[redactPrivateNotes] = req.redactPrivateNotes
                }
                if (req.redactScoutNames != null) {
                    it[redactScoutNames] = req.redactScoutNames
                }
                if (req.expiresAt != null) {
                    if (req.expiresAt.isBlank()) {
                        it[expiresAt] = null
                    } else {
                        try {
                            it[expiresAt] = Instant.parse(req.expiresAt)
                        } catch (_: Exception) {
                            throw ApiException(HttpStatusCode.BadRequest, "Invalid ISO-8601 expiresAt")
                        }
                    }
                }
            }
        }

        return getShareByToken(token) ?: throw ApiException(HttpStatusCode.NotFound, "Share link not found after update")
    }

    fun deleteShare(token: String, session: UserSession): Boolean {
        return transaction {
            val target = SharedLinks.selectAll().where { SharedLinks.token eq token }.firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Share link not found")

            if (target[SharedLinks.ownerTeamNumber] != session.teamNumber && session.role != com.obsidianscout.auth.UserRole.SUPERADMIN) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to delete this link")
            }

            val linkId = target[SharedLinks.id]
            SharedLinkAccessLogs.deleteWhere { SharedLinkAccessLogs.sharedLinkId eq linkId }
            SharedLinks.deleteWhere { SharedLinks.token eq token } > 0
        }
    }

    private fun fetchLiveDataForShare(
        ownerTeamNumber: Int,
        program: String,
        eventKey: String?,
        resourceType: String,
        queryConfigJson: String,
        redactNotes: Boolean,
        redactNames: Boolean
    ): String? {
        return try {
            val config = runCatching { ConfigService.getConfig(ownerTeamNumber, program, local = true) }.getOrNull()
            val pitConfig = runCatching { ConfigService.getPitConfig(ownerTeamNumber, program, local = true) }.getOrNull()
            val qualConfig = runCatching { ConfigService.getQualitativeConfig(ownerTeamNumber, program, local = true) }.getOrNull()
            val settings = runCatching { SettingsService.getSettings(ownerTeamNumber, program) }.getOrNull()
            val effectiveEventKey = if (!eventKey.isNullOrBlank()) eventKey else settings?.resolvedEventKey() ?: ""

            val (matchEntries, pitEntries, qualEntries) = readTransaction {
                // 1. Match Entries
                val matchQuery = ScoutingEntries.selectAll()
                    .where { (ScoutingEntries.ownerTeamNumber eq ownerTeamNumber) and (ScoutingEntries.program eq program) }
                if (effectiveEventKey.isNotBlank()) {
                    matchQuery.andWhere { (ScoutingEntries.eventKey eq effectiveEventKey) or (ScoutingEntries.isPrescout eq true) }
                }
                val matchRows = matchQuery.orderBy(ScoutingEntries.createdAt, SortOrder.DESC).toList()

                // 2. Pit Entries
                val pitQuery = PitScoutingEntries.selectAll()
                    .where { (PitScoutingEntries.ownerTeamNumber eq ownerTeamNumber) and (PitScoutingEntries.program eq program) }
                if (effectiveEventKey.isNotBlank()) {
                    pitQuery.andWhere { (PitScoutingEntries.eventKey eq effectiveEventKey) or (PitScoutingEntries.isPrescout eq true) }
                }
                val pitRows = pitQuery.orderBy(PitScoutingEntries.createdAt, SortOrder.DESC).toList()

                // 3. Qualitative Entries
                val qualQuery = QualitativeScoutingEntries.selectAll()
                    .where { (QualitativeScoutingEntries.ownerTeamNumber eq ownerTeamNumber) and (QualitativeScoutingEntries.program eq program) }
                if (effectiveEventKey.isNotBlank()) {
                    qualQuery.andWhere { (QualitativeScoutingEntries.eventKey eq effectiveEventKey) or (QualitativeScoutingEntries.isPrescout eq true) }
                }
                val qualRows = qualQuery.orderBy(QualitativeScoutingEntries.createdAt, SortOrder.DESC).toList()

                // Resolve Usernames
                val allUserIds = (
                    matchRows.map { it[ScoutingEntries.submittedByUserId].value } +
                    pitRows.map { it[PitScoutingEntries.submittedByUserId].value } +
                    qualRows.map { it[QualitativeScoutingEntries.submittedByUserId].value }
                ).distinct()

                val userNames = if (allUserIds.isNotEmpty()) {
                    Users.selectAll().where { Users.id inList allUserIds }
                        .associate { it[Users.id].value to it[Users.username] }
                } else {
                    emptyMap()
                }

                val formattedMatches = matchRows.map { row ->
                    val rawData = row[ScoutingEntries.dataJson]
                    val sanitizedData = sanitizeJsonPayload(rawData, redactNotes, redactNames)
                    val uid = row[ScoutingEntries.submittedByUserId].value
                    val actualName = userNames[uid] ?: "Scout"
                    val displayName = if (redactNames) "Anonymous Scout" else actualName

                    buildJsonObject {
                        put("id", row[ScoutingEntries.id].value.toString())
                        put("type", "Match")
                        put("targetTeamNumber", row[ScoutingEntries.targetTeamNumber])
                        put("teamNumber", row[ScoutingEntries.targetTeamNumber])
                        put("matchNumber", row[ScoutingEntries.matchNumber])
                        put("matchKey", row[ScoutingEntries.matchKey] ?: "")
                        put("eventKey", row[ScoutingEntries.eventKey] ?: "")
                        put("isPrescout", row[ScoutingEntries.isPrescout])
                        put("scoutName", displayName)
                        put("username", displayName)
                        put("hasDiscrepancy", row[ScoutingEntries.hasDiscrepancy])
                        put("data", try { JsonSupport.json.parseToJsonElement(sanitizedData ?: "{}") } catch (_: Exception) { JsonObject(emptyMap()) })
                        put("dataJson", sanitizedData ?: "{}")
                        put("createdAt", row[ScoutingEntries.createdAt].toString())
                    }
                }

                val formattedPits = pitRows.map { row ->
                    val rawData = row[PitScoutingEntries.dataJson]
                    val sanitizedData = sanitizeJsonPayload(rawData, redactNotes, redactNames)
                    val uid = row[PitScoutingEntries.submittedByUserId].value
                    val actualName = userNames[uid] ?: "Scout"
                    val displayName = if (redactNames) "Anonymous Scout" else actualName

                    buildJsonObject {
                        put("id", row[PitScoutingEntries.id].value.toString())
                        put("type", "Pit")
                        put("targetTeamNumber", row[PitScoutingEntries.targetTeamNumber])
                        put("teamNumber", row[PitScoutingEntries.targetTeamNumber])
                        put("matchNumber", null as Int?)
                        put("matchKey", "")
                        put("eventKey", row[PitScoutingEntries.eventKey] ?: "")
                        put("isPrescout", row[PitScoutingEntries.isPrescout])
                        put("scoutName", displayName)
                        put("username", displayName)
                        put("hasDiscrepancy", row[PitScoutingEntries.hasDiscrepancy])
                        put("data", try { JsonSupport.json.parseToJsonElement(sanitizedData ?: "{}") } catch (_: Exception) { JsonObject(emptyMap()) })
                        put("dataJson", sanitizedData ?: "{}")
                        put("createdAt", row[PitScoutingEntries.createdAt].toString())
                    }
                }

                val formattedQuals = qualRows.map { row ->
                    val rawData = row[QualitativeScoutingEntries.dataJson]
                    val sanitizedData = sanitizeJsonPayload(rawData, redactNotes, redactNames)
                    val uid = row[QualitativeScoutingEntries.submittedByUserId].value
                    val actualName = userNames[uid] ?: "Scout"
                    val displayName = if (redactNames) "Anonymous Scout" else actualName

                    buildJsonObject {
                        put("id", row[QualitativeScoutingEntries.id].value.toString())
                        put("type", "Qualitative")
                        put("targetTeamNumber", row[QualitativeScoutingEntries.targetTeamNumber])
                        put("teamNumber", row[QualitativeScoutingEntries.targetTeamNumber])
                        put("matchNumber", row[QualitativeScoutingEntries.matchNumber])
                        put("matchKey", row[QualitativeScoutingEntries.matchKey] ?: "")
                        put("eventKey", row[QualitativeScoutingEntries.eventKey] ?: "")
                        put("isPrescout", row[QualitativeScoutingEntries.isPrescout])
                        put("scoutName", displayName)
                        put("username", displayName)
                        put("hasDiscrepancy", row[QualitativeScoutingEntries.hasDiscrepancy])
                        put("data", try { JsonSupport.json.parseToJsonElement(sanitizedData ?: "{}") } catch (_: Exception) { JsonObject(emptyMap()) })
                        put("dataJson", sanitizedData ?: "{}")
                        put("createdAt", row[QualitativeScoutingEntries.createdAt].toString())
                    }
                }

                Triple(formattedMatches, formattedPits, formattedQuals)
            }

            val allUnifiedEntries = matchEntries + pitEntries + qualEntries

            val fakeSession = UserSession(
                userId = UUID.randomUUID().toString(),
                teamNumber = ownerTeamNumber,
                username = "shared_link",
                role = UserRole.SCOUT,
                program = program
            )

            val teamsList = if (effectiveEventKey.isNotBlank()) {
                runCatching {
                    IntegrationService.listTeams(effectiveEventKey.lowercase().trim(), fakeSession)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val statsHistory = if (settings != null && effectiveEventKey.isNotBlank()) {
                runCatching {
                    IntegrationService.getFullStatsHistory(settings, effectiveEventKey.lowercase().trim())
                }.getOrNull() ?: StatsHistoryResponse()
            } else {
                StatsHistoryResponse()
            }

            val predictionData = if (resourceType == "predictor" || resourceType == "event_predictor") {
                val parsedQuery = runCatching { JsonSupport.json.parseToJsonElement(queryConfigJson).jsonObject }.getOrNull()
                val matchKey = parsedQuery?.get("matchKey")?.jsonPrimitive?.contentOrNull
                if (!matchKey.isNullOrBlank()) {
                    runCatching {
                        runBlocking {
                            PredictorService.predict(fakeSession, matchKey, false, effectiveEventKey)
                        }
                    }.getOrNull()
                } else null
            } else null

            val datasetData = if (resourceType == "custom_analytics") {
                runCatching {
                    AnalyticsReportService.generateDataset(fakeSession, effectiveEventKey.ifBlank { null })
                }.getOrNull()
            } else null

            buildJsonObject {
                put("ownerTeamNumber", ownerTeamNumber)
                put("program", program)
                put("eventKey", effectiveEventKey)
                put("resourceType", resourceType)
                put("entriesCount", matchEntries.size)
                put("entries", JsonArray(matchEntries))
                put("matchEntries", JsonArray(matchEntries))
                put("pitEntries", JsonArray(pitEntries))
                put("qualEntries", JsonArray(qualEntries))
                put("allEntries", JsonArray(allUnifiedEntries))
                put("rows", JsonArray(allUnifiedEntries))
                put("teams", JsonSupport.json.encodeToJsonElement(ListSerializer(TeamRecord.serializer()), teamsList))
                put("statsHistory", JsonSupport.json.encodeToJsonElement(StatsHistoryResponse.serializer(), statsHistory))
                if (predictionData != null) {
                    put("prediction", JsonSupport.json.encodeToJsonElement(MatchPredictionResponse.serializer(), predictionData))
                }
                if (datasetData != null) {
                    put("dataset", JsonSupport.json.encodeToJsonElement(AnalyticsDatasetResponse.serializer(), datasetData))
                }
                if (config != null) {
                    put("config", JsonSupport.json.encodeToJsonElement(ScoutingConfig.serializer(), config))
                }
                if (pitConfig != null) {
                    put("pitConfig", JsonSupport.json.encodeToJsonElement(ScoutingConfig.serializer(), pitConfig))
                }
                if (qualConfig != null) {
                    put("qualConfig", JsonSupport.json.encodeToJsonElement(ScoutingConfig.serializer(), qualConfig))
                }
                if (settings != null) {
                    put("settings", JsonSupport.json.encodeToJsonElement(ApiSettings.serializer(), settings))
                }
            }.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitizeJsonPayload(jsonStr: String?, redactNotes: Boolean, redactNames: Boolean): String? {
        if (jsonStr.isNullOrBlank()) return jsonStr
        return try {
            val element = JsonSupport.json.parseToJsonElement(jsonStr)
            val sanitized = sanitizeElement(element, redactNotes, redactNames)
            sanitized.toString()
        } catch (_: Exception) {
            jsonStr
        }
    }

    private fun sanitizeElement(element: JsonElement, redactNotes: Boolean, redactNames: Boolean): JsonElement {
        return when (element) {
            is JsonObject -> {
                val map = mutableMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val lowerKey = key.lowercase()
                    if (redactNotes && (lowerKey.contains("note") || lowerKey.contains("comment") || lowerKey.contains("strategy_note") || lowerKey.contains("private"))) {
                        map[key] = JsonPrimitive("[Redacted in Shared View]")
                    } else if (redactNames && (lowerKey.contains("scout_name") || lowerKey == "scout" || lowerKey == "author" || lowerKey == "user")) {
                        map[key] = JsonPrimitive("Anonymous Scout")
                    } else {
                        map[key] = sanitizeElement(value, redactNotes, redactNames)
                    }
                }
                JsonObject(map)
            }
            is JsonArray -> {
                JsonArray(element.map { sanitizeElement(it, redactNotes, redactNames) })
            }
            else -> element
        }
    }
}
