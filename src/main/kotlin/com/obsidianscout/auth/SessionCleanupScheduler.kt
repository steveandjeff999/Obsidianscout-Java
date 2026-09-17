package com.obsidianscout.auth

import com.obsidianscout.db.PasswordResetTokens
import com.obsidianscout.db.UserSessions
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

object SessionCleanupScheduler {
    private val log = LoggerFactory.getLogger("SessionCleanupScheduler")
    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        val handler = CoroutineExceptionHandler { _, throwable ->
            log.error("[SessionCleanupScheduler] Error in coroutine", throwable)
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
        scope?.launch {
            log.info("Background session cleanup scheduler started")
            // Wait 2 minutes after boot before running first prune
            delay(120_000L)
            while (isActive) {
                try {
                    runCleanup()
                } catch (e: Throwable) {
                    log.error("Failed to run background session cleanup: ${e.message}", e)
                }
                // Run every 60 minutes
                delay(3_600_000L)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    fun runCleanup(): Long {
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
            return 0L
        }
        val now = Instant.now()
        val staleCutoff = now.minus(30, ChronoUnit.DAYS)
        var deletedCount = 0L

        try {
            transaction {
                deletedCount = UserSessions.deleteWhere {
                    (UserSessions.expiresAt.isNotNull() and (UserSessions.expiresAt lessEq now)) or
                            (UserSessions.lastActiveAt lessEq staleCutoff)
                }.toLong()

                PasswordResetTokens.deleteWhere {
                    PasswordResetTokens.expiresAt lessEq now
                }
            }
            if (deletedCount > 0) {
                log.info("[SessionCleanupScheduler] Pruned $deletedCount expired or stale sessions.")
            }
        } catch (e: Throwable) {
            log.warn("[SessionCleanupScheduler] Could not prune sessions: ${e.message}")
        }
        return deletedCount
    }
}
