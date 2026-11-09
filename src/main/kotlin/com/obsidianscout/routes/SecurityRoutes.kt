package com.obsidianscout.routes

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.clientIp
import com.obsidianscout.auth.requireSuperAdmin
import com.obsidianscout.security.AccountProtection
import com.obsidianscout.security.Allowlist
import com.obsidianscout.security.AllowlistAddRequest
import com.obsidianscout.security.BlockList
import com.obsidianscout.security.BlockStatusDto
import com.obsidianscout.security.ClearProtectionRequest
import com.obsidianscout.security.LiftBlockRequest
import com.obsidianscout.security.ManualBlockRequest
import com.obsidianscout.security.ProofOfWork
import com.obsidianscout.security.ReasonRequest
import com.obsidianscout.security.ReviewUnblockRequest
import com.obsidianscout.security.SecurityLog
import com.obsidianscout.security.SecurityService
import com.obsidianscout.security.SecuritySettings
import com.obsidianscout.security.SecuritySettingsDto
import com.obsidianscout.security.SessionWatch
import com.obsidianscout.security.SimpleOk
import com.obsidianscout.security.UnblockRequestSubmission
import com.obsidianscout.security.UnblockRequests
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

private val allowlistChoices = setOf("none", "24h", "7d", "permanent")

private fun parseUuid(text: String?): UUID =
    runCatching { UUID.fromString(text) }.getOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid id")

/**
 * /api/security/... is reachable while blocked: a blocked visitor checks their status and asks to be unblocked.
 * /api/admin/security/... is the superadmin console behind Server Insights.
 */
fun Route.securityRoutes() {
    route("/security") {
        get("/block-status") {
            val ip = call.clientIp()
            val block = BlockList.match(ip, countHit = false)
            val request = block?.let { UnblockRequests.forBlock(it.id.toString()) }
            call.respond(
                BlockStatusDto(
                    blocked = block != null,
                    ip = ip,
                    blockId = block?.id?.toString(),
                    expiresAt = block?.expiresAt,
                    retryAfterSeconds = block?.let { (it.remainingMs() / 1000).coerceAtLeast(1) },
                    unblockRequestsEnabled = SecuritySettings.config.unblock_requests_enabled,
                    pendingRequestId = request?.id,
                    pendingRequestStatus = request?.status
                )
            )
        }
        get("/challenge") {
            call.respond(ProofOfWork.issue(call.clientIp()))
        }
        post("/unblock-request") {
            val body = runCatching { call.receive<UnblockRequestSubmission>() }.getOrNull()
                ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid request")
            call.respond(UnblockRequests.submit(call.clientIp(), body))
        }
        get("/unblock-request/status") {
            val id = call.request.queryParameters["id"] ?: throw ApiException(HttpStatusCode.BadRequest, "Missing id")
            val status = UnblockRequests.status(id, call.clientIp()) ?: throw ApiException(HttpStatusCode.NotFound, "Request not found")
            call.respond(status)
        }
    }

    route("/admin/security") {
        get("/summary") {
            call.requireSuperAdmin()
            call.respond(SecurityService.summary(force = call.request.queryParameters["fresh"] == "1"))
        }
        get("/events") {
            call.requireSuperAdmin()
            val q = call.request.queryParameters
            call.respond(
                SecurityLog.query(
                    ip = q["ip"], user = q["user"], type = q["type"], severity = q["severity"],
                    since = q["since"]?.toLongOrNull(), until = q["until"]?.toLongOrNull(),
                    offset = q["offset"]?.toIntOrNull() ?: 0, limit = q["limit"]?.toIntOrNull() ?: 100
                )
            )
        }
        get("/settings") {
            call.requireSuperAdmin()
            call.respond(SecuritySettings.runtime())
        }
        post("/settings") {
            call.requireSuperAdmin()
            val body = call.receive<SecuritySettingsDto>()
            val saved = SecuritySettings.update(body)
            SecurityService.invalidateSummary()
            call.respond(saved)
        }

        // ------------------------------------------------------------------ blocks
        get("/blocks") {
            call.requireSuperAdmin()
            val activeOnly = call.request.queryParameters["active"] != "false"
            call.respond(BlockList.list(activeOnly))
        }
        post("/blocks") {
            val session = call.requireSuperAdmin()
            val body = call.receive<ManualBlockRequest>()
            val block = BlockList.manualBlock(body.ipOrCidr, body.minutes, body.reason, session.username)
                ?: throw ApiException(HttpStatusCode.BadRequest, "Enter a valid IP address or range (not one of this cluster's own addresses).")
            SecurityService.invalidateSummary()
            call.respond(BlockList.find(block.id) ?: throw ApiException(HttpStatusCode.InternalServerError, "Block not saved"))
        }
        post("/blocks/{id}/lift") {
            val session = call.requireSuperAdmin()
            val id = parseUuid(call.parameters["id"])
            val body = runCatching { call.receive<LiftBlockRequest>() }.getOrDefault(LiftBlockRequest())
            if (body.allowlistFor !in allowlistChoices) throw ApiException(HttpStatusCode.BadRequest, "Invalid allowlist choice")
            val lifted = BlockList.lift(id, session.username, body.reason, body.resetStrikes, body.allowlistFor)
                ?: throw ApiException(HttpStatusCode.NotFound, "Block not found")
            SecurityService.invalidateSummary()
            call.respond(lifted)
        }
        post("/blocks/unblock-all") {
            val session = call.requireSuperAdmin()
            val body = runCatching { call.receive<ReasonRequest>() }.getOrDefault(ReasonRequest())
            if (body.reason.isBlank()) throw ApiException(HttpStatusCode.BadRequest, "Give a reason for lifting every block.")
            val count = BlockList.liftAll(session.username, body.reason)
            SecurityService.invalidateSummary()
            call.respond(SimpleOk(count = count))
        }

        // ------------------------------------------------------------------ unblock requests
        get("/unblock-requests") {
            call.requireSuperAdmin()
            call.respond(UnblockRequests.list(call.request.queryParameters["status"] ?: "pending"))
        }
        post("/unblock-requests/{id}/approve") {
            val session = call.requireSuperAdmin()
            val body = runCatching { call.receive<ReviewUnblockRequest>() }.getOrDefault(ReviewUnblockRequest())
            if (body.allowlistFor !in allowlistChoices) throw ApiException(HttpStatusCode.BadRequest, "Invalid allowlist choice")
            val result = UnblockRequests.approve(call.parameters["id"].orEmpty(), session.username, body.note, body.resetStrikes, body.allowlistFor)
            SecurityService.invalidateSummary()
            call.respond(result)
        }
        post("/unblock-requests/{id}/deny") {
            val session = call.requireSuperAdmin()
            val body = runCatching { call.receive<ReviewUnblockRequest>() }.getOrDefault(ReviewUnblockRequest())
            val result = UnblockRequests.deny(call.parameters["id"].orEmpty(), session.username, body.note)
            SecurityService.invalidateSummary()
            call.respond(result)
        }

        // ------------------------------------------------------------------ allowlist
        get("/allowlist") {
            call.requireSuperAdmin()
            call.respond(Allowlist.list())
        }
        post("/allowlist") {
            val session = call.requireSuperAdmin()
            val body = call.receive<AllowlistAddRequest>()
            val expiresAt = body.expiresInHours?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it.coerceAtMost(24 * 365) * 3_600_000L }
            val entry = Allowlist.add(body.ipOrCidr, body.note, session.username, expiresAt)
                ?: throw ApiException(HttpStatusCode.BadRequest, "Enter a valid IP address or range (at least /8 for IPv4, /16 for IPv6).")
            call.respond(entry)
        }
        delete("/allowlist/{id}") {
            call.requireSuperAdmin()
            if (!Allowlist.remove(call.parameters["id"].orEmpty())) throw ApiException(HttpStatusCode.NotFound, "Entry not found")
            call.respond(SimpleOk())
        }

        // ------------------------------------------------------------------ accounts
        get("/protected-accounts") {
            call.requireSuperAdmin()
            call.respond(AccountProtection.list())
        }
        post("/protected-accounts/clear") {
            val session = call.requireSuperAdmin()
            val body = call.receive<ClearProtectionRequest>()
            if (AccountProtection.parseAccountKey(body.account) == null) throw ApiException(HttpStatusCode.BadRequest, "Invalid account")
            AccountProtection.clear(body.account, session.username, call.clientIp())
            SecurityService.invalidateSummary()
            call.respond(SimpleOk())
        }
        post("/users/{id}/revoke-sessions") {
            val session = call.requireSuperAdmin()
            val id = parseUuid(call.parameters["id"]).toString()
            val body = runCatching { call.receive<ReasonRequest>() }.getOrDefault(ReasonRequest())
            val count = SessionWatch.revokeForCompromise(id, body.reason.ifBlank { "Signed out by a superadmin" }, session.username, call.clientIp())
            call.respond(SimpleOk(count = count))
        }
    }
}
