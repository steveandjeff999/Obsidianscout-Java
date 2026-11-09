package com.obsidianscout.security

import com.obsidianscout.auth.ApiException
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.UnblockRequestsTable
import com.obsidianscout.db.readTransaction
import com.obsidianscout.db.writeTransactionWithRetry
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Requests from a blocked visitor to have their block lifted sooner. A request is tied to the block and the address
 * it came from; superadmins approve (which lifts the block) or deny it from Server Insights.
 */
object UnblockRequests {
    private const val DAY_MS = 24 * 3_600_000L
    private val highRiskTypes = setOf(
        SecurityEventType.CLUSTER_SIG_INVALID.name, SecurityEventType.CROSS_TEAM_ACCESS.name, SecurityEventType.INJECTION_PROBE.name
    )
    private val emailPattern = Regex("^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,}$")

    /** Requests kept while the database isn't ready (and in tests). */
    private val memory = ConcurrentHashMap<String, UnblockRequestDto>()

    private fun useDb() = DatabaseFactory.isReady

    fun submit(ip: String, submission: UnblockRequestSubmission, now: Long = System.currentTimeMillis()): UnblockRequestStatusDto {
        val config = SecuritySettings.config
        if (!config.unblock_requests_enabled) throw ApiException(HttpStatusCode.Forbidden, "Unblock requests are turned off on this server.")
        val block = BlockList.match(ip, now, countHit = false)
            ?: throw ApiException(HttpStatusCode.BadRequest, "Your network isn't blocked.")

        val name = SecurityEvents.clean(submission.name.trim(), 100)
        val message = SecurityEvents.clean(submission.message.trim(), config.unblock_message_max_chars.coerceIn(50, 1000))
        val email = submission.email?.trim()?.takeIf { it.isNotEmpty() }
        val team = submission.teamNumber
        if (name.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Please enter your name.")
        if (message.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Please say what you were doing when you were blocked.")
        if (email != null && (email.length > 255 || !emailPattern.matches(email))) throw ApiException(HttpStatusCode.BadRequest, "That email address doesn't look right.")
        if (team != null && (team < 0 || team > 99_999)) throw ApiException(HttpStatusCode.BadRequest, "That team number doesn't look right.")

        // One open request per block: a second submission gets the first one's status.
        openForBlock(block.id.toString())?.let { return UnblockRequestStatusDto(it.id, it.status, it.reviewNote) }

        when (ProofOfWork.verify(submission.challenge, ip, now)) {
            ProofOfWork.Result.OK -> Unit
            ProofOfWork.Result.EXPIRED -> throw ApiException(HttpStatusCode.BadRequest, "The security check expired. Please try again.")
            else -> throw ApiException(HttpStatusCode.BadRequest, "The security check didn't complete. Please try again.")
        }
        val perDay = config.unblock_requests_per_ip_per_day.coerceAtLeast(1)
        if (SecurityStore.increment("unblockreq:$ip", DAY_MS, now) > perDay) {
            throw ApiException(HttpStatusCode.TooManyRequests, "You've sent the most unblock requests allowed today. The block still lifts on its own.")
        }

        val id = UUID.randomUUID()
        val highRisk = block.triggeringTypes.any { it in highRiskTypes }
        val dto = UnblockRequestDto(
            id = id.toString(), blockId = block.id.toString(), ip = ip, name = name, teamNumber = team, email = email,
            message = message, status = "pending", highRisk = highRisk, createdAt = now
        )
        if (useDb()) {
            writeTransactionWithRetry {
                UnblockRequestsTable.insert {
                    it[UnblockRequestsTable.id] = id
                    it[blockId] = block.id
                    it[UnblockRequestsTable.ip] = ip.take(64)
                    it[UnblockRequestsTable.name] = name
                    it[teamNumber] = team
                    it[UnblockRequestsTable.email] = email
                    it[UnblockRequestsTable.message] = message
                    it[status] = "pending"
                    it[UnblockRequestsTable.highRisk] = highRisk
                    it[createdAt] = Instant.ofEpochMilli(now)
                }
            }
        } else {
            memory[dto.id] = dto
        }
        SecurityEvents.emit(
            SecurityEvent(SecurityEventType.UNBLOCK_REQUESTED, ip, username = name, teamNumber = team,
                details = (if (highRisk) "[high risk] " else "") + "Unblock request from $name" +
                    (team?.let { " (team $it)" } ?: "") + ": $message")
        )
        return UnblockRequestStatusDto(dto.id, dto.status)
    }

    /** The request's status, only for the address that sent it. */
    fun status(id: String, ip: String): UnblockRequestStatusDto? {
        val req = get(id) ?: return null
        if (req.ip != ip) return null
        return UnblockRequestStatusDto(req.id, req.status, req.reviewNote)
    }

    fun get(id: String): UnblockRequestDto? {
        memory[id]?.let { return it }
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        if (!useDb()) return null
        return readTransaction {
            UnblockRequestsTable.selectAll().where { UnblockRequestsTable.id eq uuid }.firstOrNull()?.let { rowToDto(it) }
        }
    }

    private fun openForBlock(blockId: String): UnblockRequestDto? {
        memory.values.firstOrNull { it.blockId == blockId && it.status == "pending" }?.let { return it }
        val uuid = runCatching { UUID.fromString(blockId) }.getOrNull() ?: return null
        if (!useDb()) return null
        return readTransaction {
            UnblockRequestsTable.selectAll()
                .where { (UnblockRequestsTable.blockId eq uuid) and (UnblockRequestsTable.status eq "pending") }
                .firstOrNull()?.let { rowToDto(it) }
        }
    }

    /** For the blocked page: the pending request for [blockId], if any. */
    fun forBlock(blockId: String): UnblockRequestDto? {
        memory.values.filter { it.blockId == blockId }.maxByOrNull { it.createdAt }?.let { return it }
        val uuid = runCatching { UUID.fromString(blockId) }.getOrNull() ?: return null
        if (!useDb()) return null
        return readTransaction {
            UnblockRequestsTable.selectAll().where { UnblockRequestsTable.blockId eq uuid }
                .orderBy(UnblockRequestsTable.createdAt to SortOrder.DESC).limit(1).firstOrNull()?.let { rowToDto(it) }
        }
    }

    private fun rowToDto(row: org.jetbrains.exposed.v1.core.ResultRow) = UnblockRequestDto(
        id = row[UnblockRequestsTable.id].value.toString(),
        blockId = row[UnblockRequestsTable.blockId].toString(),
        ip = row[UnblockRequestsTable.ip],
        name = row[UnblockRequestsTable.name],
        teamNumber = row[UnblockRequestsTable.teamNumber],
        email = row[UnblockRequestsTable.email],
        message = row[UnblockRequestsTable.message],
        status = row[UnblockRequestsTable.status],
        highRisk = row[UnblockRequestsTable.highRisk],
        createdAt = row[UnblockRequestsTable.createdAt].toEpochMilli(),
        reviewedBy = row[UnblockRequestsTable.reviewedBy],
        reviewedAt = row[UnblockRequestsTable.reviewedAt]?.toEpochMilli(),
        reviewNote = row[UnblockRequestsTable.reviewNote]
    )

    /** Requests with [status] (or all), newest first, with what the review queue needs about each block. */
    fun list(status: String?, limit: Int = 200): List<UnblockRequestDto> {
        val wanted = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "all" }
        val fromDb = if (useDb()) {
            readTransaction {
                val q = if (wanted != null) UnblockRequestsTable.selectAll().where { UnblockRequestsTable.status eq wanted }
                    else UnblockRequestsTable.selectAll()
                q.orderBy(UnblockRequestsTable.createdAt to SortOrder.DESC).limit(limit.coerceIn(1, 1000)).map { rowToDto(it) }
            }
        } else emptyList()
        val fromMemory = memory.values.filter { wanted == null || it.status == wanted }
        return (fromMemory + fromDb).distinctBy { it.id }.sortedByDescending { it.createdAt }.map { req ->
            val block = runCatching { BlockList.find(UUID.fromString(req.blockId)) }.getOrNull()
            req.copy(
                blockReason = block?.reason,
                blockTriggeringEventTypes = block?.triggeringEventTypes ?: emptyList(),
                blockExpiresAt = block?.expiresAt,
                recentDistinctUsers = SecurityStore.distinctCount("okusers:${req.ip}", 3_600_000L)
            )
        }
    }

    fun pendingByBlock(): Map<String, String> = list("pending").associate { it.blockId to it.id }

    private fun setStatus(id: String, status: String, by: String, note: String?) {
        val now = System.currentTimeMillis()
        memory[id]?.let { memory[id] = it.copy(status = status, reviewedBy = by, reviewedAt = now, reviewNote = note) }
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return
        if (!useDb()) return
        writeTransactionWithRetry {
            UnblockRequestsTable.update({ UnblockRequestsTable.id eq uuid }) {
                it[UnblockRequestsTable.status] = status
                it[reviewedBy] = by.take(128)
                it[reviewedAt] = Instant.ofEpochMilli(now)
                it[reviewNote] = note?.take(500)
            }
        }
    }

    fun approve(id: String, by: String, note: String, resetStrikes: Boolean, allowlistFor: String): UnblockRequestDto {
        val req = get(id) ?: throw ApiException(HttpStatusCode.NotFound, "Request not found")
        if (req.status != "pending") throw ApiException(HttpStatusCode.Conflict, "This request was already ${req.status}.")
        val cleanNote = SecurityEvents.clean(note, 500).ifBlank { null }
        setStatus(id, "approved", by, cleanNote)
        BlockList.lift(UUID.fromString(req.blockId), by, "Unblock request ${req.id} approved" + (cleanNote?.let { ": $it" } ?: ""),
            resetStrikes, allowlistFor, requestId = UUID.fromString(req.id))
        SecurityEvents.emit(SecurityEvent(SecurityEventType.UNBLOCK_APPROVED, req.ip, username = by, details = "Approved request from ${req.name}"))
        req.email?.let {
            SecurityAlerter.emailUser(it, "ObsidianScout: your network has been unblocked",
                "Hi ${req.name}, an administrator approved your request and your network is no longer blocked. You can reload ObsidianScout now." +
                    (cleanNote?.let { n -> "\n\nNote from the administrator: $n" } ?: ""))
        }
        return get(id) ?: req
    }

    fun deny(id: String, by: String, note: String): UnblockRequestDto {
        val req = get(id) ?: throw ApiException(HttpStatusCode.NotFound, "Request not found")
        if (req.status != "pending") throw ApiException(HttpStatusCode.Conflict, "This request was already ${req.status}.")
        val cleanNote = SecurityEvents.clean(note, 500).ifBlank { null }
        setStatus(id, "denied", by, cleanNote)
        SecurityEvents.emit(SecurityEvent(SecurityEventType.UNBLOCK_DENIED, req.ip, username = by, details = "Denied request from ${req.name}" + (cleanNote?.let { ": $it" } ?: "")))
        req.email?.let {
            SecurityAlerter.emailUser(it, "ObsidianScout: about your unblock request",
                "Hi ${req.name}, an administrator reviewed your request and left the block in place. It lifts on its own within the hour." +
                    (cleanNote?.let { n -> "\n\nNote from the administrator: $n" } ?: ""))
        }
        return get(id) ?: req
    }

    /** A block was lifted some other way: its waiting requests are answered. */
    fun closePendingForBlock(blockId: UUID, by: String, exceptRequestId: UUID?) {
        val pending = list("pending").filter { it.blockId == blockId.toString() && it.id != exceptRequestId?.toString() }
        pending.forEach { setStatus(it.id, "approved", by, "Block lifted by $by") }
    }

    /** Pending requests whose block has ended are marked expired: the visitor is already let through. */
    fun expireStale() {
        val pending = list("pending")
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        pending.filter { (it.blockExpiresAt ?: 0L) <= now || BlockList.find(UUID.fromString(it.blockId))?.active != true }
            .forEach { setStatus(it.id, "expired", "system", null) }
    }

    /** Tests only. */
    internal fun clearForTests() = memory.clear()
}
