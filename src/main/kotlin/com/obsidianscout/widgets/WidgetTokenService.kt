package com.obsidianscout.widgets

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.UserSessions
import com.obsidianscout.db.Users
import com.obsidianscout.db.WidgetTokens
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tokens that let a device's home-screen widgets fetch [WidgetSnapshotService] snapshots while the app is closed.
 * A token can do nothing else (only `GET /api/widgets/snapshot` accepts it), is tied to the login session that
 * created it (signing out, revoking the device, or deleting the user ends it), and expires after [LIFETIME].
 */
object WidgetTokenService {
    val LIFETIME: Duration = Duration.ofDays(30)
    private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)
    private val random = SecureRandom()

    /** Each token's last good lookup, answered from for up to [FALLBACK_MAX_AGE] when the database is too slow to check. */
    private val FALLBACK_MAX_AGE: Duration = Duration.ofMinutes(10)
    private data class Cached(val at: Instant, val resolved: Resolved)
    private val lastResolved = ConcurrentHashMap<String, Cached>()

    data class Issued(val token: String, val expiresAt: Instant)

    data class Resolved(val tokenId: UUID, val session: UserSession, val placedWidgets: Set<String>, val lastUsedAt: Instant?)

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Issues a new token for this login session, replacing any earlier one the session had. */
    fun issue(session: UserSession, platform: String, now: Instant = Instant.now()): Issued {
        val sessionUuid = session.sessionId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw IllegalStateException("Widget tokens need a server-side login session")
        val userUuid = UUID.fromString(session.userId)
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = "wt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val expiresAt = now.plus(LIFETIME)
        transaction {
            WidgetTokens.deleteWhere { (WidgetTokens.sessionId eq sessionUuid) or (WidgetTokens.expiresAt lessEq now) }
            WidgetTokens.insert {
                it[userId] = userUuid
                it[sessionId] = sessionUuid
                it[tokenHash] = hash(token)
                it[WidgetTokens.platform] = platform.lowercase().take(16)
                it[createdAt] = now
                it[WidgetTokens.expiresAt] = expiresAt
            }
        }
        return Issued(token, expiresAt)
    }

    fun revokeForSession(sessionId: String?) {
        val sessionUuid = sessionId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        lastResolved.values.removeIf { it.resolved.session.sessionId == sessionUuid.toString() }
        transaction { WidgetTokens.deleteWhere { WidgetTokens.sessionId eq sessionUuid } }
    }

    /**
     * The user a token acts for, with their current role and settings, or null when the token is unknown, expired,
     * or its login session no longer exists. Throws [Unavailable] when the database can't answer in time and there is
     * no recent result to fall back on.
     */
    fun resolve(token: String, now: Instant = Instant.now()): Resolved? {
        if (!token.startsWith("wt_") || token.length > 128) return null
        val tokenHash = hash(token)
        val resolved = try {
            lookup(tokenHash, now)
        } catch (e: Exception) {
            val cached = lastResolved[tokenHash]
            if (cached != null && Duration.between(cached.at, now) < FALLBACK_MAX_AGE) return cached.resolved
            throw Unavailable(e)
        }
        if (resolved == null) lastResolved.remove(tokenHash)
        else {
            lastResolved[tokenHash] = Cached(now, resolved)
            if (lastResolved.size > 5_000) lastResolved.values.removeIf { Duration.between(it.at, now) >= FALLBACK_MAX_AGE }
        }
        return resolved
    }

    class Unavailable(cause: Throwable) : RuntimeException("Widget token lookup timed out", cause)

    private fun lookup(tokenHash: String, now: Instant): Resolved? =
        readTransaction {
            // Widget refreshes can wait; don't let one hold a pooled connection for the full default timeout.
            if (DatabaseFactory.isPostgresCompatible && !com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
                (this as? JdbcTransaction)?.exec("SET LOCAL statement_timeout = '3000ms';")
            }
            val row = WidgetTokens.selectAll().where {
                (WidgetTokens.tokenHash eq tokenHash) and (WidgetTokens.expiresAt greater now)
            }.firstOrNull() ?: return@readTransaction null
            val userUuid = row[WidgetTokens.userId].value
            val sessionUuid = row[WidgetTokens.sessionId]
            val sessionAlive = UserSessions.selectAll().where {
                (UserSessions.id eq sessionUuid) and (UserSessions.userId eq userUuid) and
                    (UserSessions.expiresAt.isNull() or (UserSessions.expiresAt greater now))
            }.any()
            if (!sessionAlive) return@readTransaction null
            val user = Users.selectAll().where { Users.id eq userUuid }.firstOrNull() ?: return@readTransaction null
            val role = runCatching { UserRole.valueOf(user[Users.role]) }.getOrNull() ?: return@readTransaction null
            Resolved(
                tokenId = row[WidgetTokens.id].value,
                session = UserSession(
                    userId = userUuid.toString(),
                    username = user[Users.username],
                    teamNumber = user[Users.teamNumber],
                    program = user[Users.program],
                    role = role,
                    nonScoutingToolsEnabled = user.getOrNull(Users.nonScoutingToolsEnabled) ?: false,
                    sessionId = sessionUuid.toString(),
                ),
                placedWidgets = row[WidgetTokens.placedWidgets].split(',').filter { it.isNotBlank() }.toSet(),
                lastUsedAt = row[WidgetTokens.lastUsedAt],
            )
        }

    /** Records which widgets the device has placed and which event it is showing (written at most once a minute unless they change). */
    fun touch(resolved: Resolved, placed: Set<String>?, eventKey: String, now: Instant = Instant.now()) {
        val placedChanged = placed != null && placed != resolved.placedWidgets
        val stale = resolved.lastUsedAt == null || Duration.between(resolved.lastUsedAt, now) >= TOUCH_INTERVAL
        if (!placedChanged && !stale) return
        transaction {
            WidgetTokens.update({ WidgetTokens.id eq resolved.tokenId }) {
                it[lastUsedAt] = now
                it[WidgetTokens.eventKey] = eventKey.take(64)
                if (placed != null) it[placedWidgets] = placed.filter { k -> k in WidgetAccess.WIDGET_PAGES }.sorted().joinToString(",").take(512)
            }
        }
    }

    /** Same as [touch] for the token belonging to a login session (when the app itself fetches for its widgets). */
    fun touchForSession(sessionId: String?, placed: Set<String>, eventKey: String, now: Instant = Instant.now()) {
        val sessionUuid = sessionId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val kinds = placed.filter { it in WidgetAccess.WIDGET_PAGES }.sorted().joinToString(",").take(512)
        transaction {
            WidgetTokens.update({ (WidgetTokens.sessionId eq sessionUuid) and (WidgetTokens.expiresAt greater now) }) {
                it[placedWidgets] = kinds
                it[WidgetTokens.eventKey] = eventKey.take(64)
                it[lastUsedAt] = now
            }
        }
    }

    /** Live tokens with at least one placed widget, for deciding who to push refreshes to. */
    data class Holder(val userId: UUID, val teamNumber: Int, val program: String, val eventKey: String, val placed: Set<String>)

    fun activeHolders(now: Instant = Instant.now()): List<Holder> = readTransaction {
        WidgetTokens.innerJoin(Users).selectAll().where {
            (WidgetTokens.expiresAt greater now) and (WidgetTokens.lastUsedAt greater now.minus(Duration.ofDays(3)))
        }.mapNotNull { row ->
            val placed = row[WidgetTokens.placedWidgets].split(',').filter { it.isNotBlank() }.toSet()
            if (placed.isEmpty()) return@mapNotNull null
            Holder(row[WidgetTokens.userId].value, row[Users.teamNumber], row[Users.program], row[WidgetTokens.eventKey], placed)
        }
    }
}
