package com.obsidianscout.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.sessions.get
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import kotlinx.serialization.Serializable
import io.ktor.server.sessions.SessionTransport
import io.ktor.server.sessions.CookieConfiguration
import io.ktor.server.sessions.SessionTransportTransformer
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.transformRead
import io.ktor.server.sessions.transformWrite
import io.ktor.http.Cookie
import io.ktor.util.AttributeKey
import com.obsidianscout.db.DatabaseFactory
import com.obsidianscout.db.Users
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

import com.obsidianscout.db.UserSessions
import org.jetbrains.exposed.sql.and

@Serializable
data class UserSession(
    val userId: String,
    val username: String,
    val teamNumber: Int,
    val program: String = "FRC",
    val role: UserRole,
    val email: String? = null,
    val profilePicture: String? = null,
    val notificationPreference: String = "all",
    val tourProgress: String? = null,
    val nodeAlertsEnabled: Boolean = false,
    val sessionId: String? = null
)

class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

suspend fun ApplicationCall.requireSession(): UserSession {
    val session = sessions.get<UserSession>()
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Not signed in")
    if (DatabaseFactory.isReady) {
        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
        if (userUuid == null) {
            sessions.clear<UserSession>()
            throw ApiException(HttpStatusCode.Unauthorized, "Invalid user")
        }
        val (userExists, sessionValid) = runCatching {
            com.obsidianscout.db.readTransaction {
                val exists = Users.selectAll().where { Users.id eq userUuid }.any()
                val sessionOk = if (!session.sessionId.isNullOrBlank()) {
                    val sUuid = runCatching { UUID.fromString(session.sessionId) }.getOrNull()
                    if (sUuid != null) {
                        UserSessions.selectAll().where { (UserSessions.id eq sUuid) and (UserSessions.userId eq userUuid) }.any()
                    } else false
                } else {
                    true
                }
                Pair(exists, sessionOk)
            }
        }.getOrDefault(Pair(true, true))

        if (!userExists) {
            sessions.clear<UserSession>()
            throw ApiException(HttpStatusCode.Unauthorized, "Account has been deleted")
        }
        if (!sessionValid) {
            sessions.clear<UserSession>()
            throw ApiException(HttpStatusCode.Unauthorized, "Session has been revoked")
        }

        if (!session.sessionId.isNullOrBlank()) {
            AuthService.touchSession(session.sessionId)
        }
    }
    return session
}

/**
 * Requires ADMIN or SUPERADMIN role.
 */
suspend fun ApplicationCall.requireAdmin(): UserSession {
    val session = requireSession()
    if (!session.role.isAtLeast(UserRole.ADMIN)) {
        throw ApiException(HttpStatusCode.Forbidden, "Admin access required")
    }
    return session
}

/**
 * Requires ADMIN / SUPERADMIN role OR valid HMAC signed inter-node cluster request.
 */
suspend fun ApplicationCall.requireAdminOrClusterAuth(): Boolean {
    val timestampStr = request.headers["X-Cluster-Timestamp"]
    val signature = request.headers["X-Cluster-Signature"]

    if (!timestampStr.isNullOrBlank() && !signature.isNullOrBlank()) {
        val timestamp = timestampStr.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (Math.abs(now - timestamp) <= 300_000L) { // 5-minute replay guard
            val method = request.httpMethod.value
            val uriPath = request.uri
            val secret = ClusterSecretService.getSessionSecret()
            val dataToSign = "$timestamp:$method:$uriPath"
            if (ClusterCryptoUtils.verifyHmac(dataToSign, signature, secret)) {
                return true
            }
        }
    }

    requireAdmin()
    return true
}

/**
 * Requires SUPERADMIN role.
 */
suspend fun ApplicationCall.requireSuperAdmin(): UserSession {
    val session = requireSession()
    if (session.role != UserRole.SUPERADMIN) {
        throw ApiException(HttpStatusCode.Forbidden, "Superadmin access required")
    }
    return session
}

/**
 * Requires SUPERADMIN role OR valid HMAC signed inter-node cluster request.
 */
suspend fun ApplicationCall.requireSuperAdminOrClusterAuth(): Boolean {
    val timestampStr = request.headers["X-Cluster-Timestamp"]
    val signature = request.headers["X-Cluster-Signature"]

    if (!timestampStr.isNullOrBlank() && !signature.isNullOrBlank()) {
        val timestamp = timestampStr.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (Math.abs(now - timestamp) <= 300_000L) { // 5-minute replay guard
            val method = request.httpMethod.value
            val uriPath = request.uri
            val secret = ClusterSecretService.getSessionSecret()
            val dataToSign = "$timestamp:$method:$uriPath"
            if (ClusterCryptoUtils.verifyHmac(dataToSign, signature, secret)) {
                return true
            }
        }
    }

    requireSuperAdmin()
    return true
}

/**
 * Requires ANALYTICS, ADMIN, or SUPERADMIN role.
 */
suspend fun ApplicationCall.requireAnalyticsOrAbove(): UserSession {
    val session = requireSession()
    if (!session.role.isAtLeast(UserRole.ANALYTICS)) {
        throw ApiException(HttpStatusCode.Forbidden, "Analytics access required")
    }
    return session
}

class KeepMeLoggedInSessionTransport(
    private val delegate: io.ktor.server.sessions.SessionTransportCookie
) : SessionTransport {

    companion object {
        val KEEP_ME_LOGGED_IN_KEY = AttributeKey<Boolean>("KeepMeLoggedIn")
    }

    override fun receive(call: ApplicationCall): String? {
        return delegate.receive(call)
    }

    override fun send(call: ApplicationCall, value: String) {
        val transformed = delegate.transformers.transformWrite(value)
        val keepMeLoggedIn = call.attributes.getOrNull(KEEP_ME_LOGGED_IN_KEY) ?: false
        
        val maxAgeSeconds = if (keepMeLoggedIn) {
            60 * 60 * 24 * 30L // 30 days
        } else {
            delegate.configuration.maxAgeInSeconds
        }

        call.response.cookies.append(
            Cookie(
                name = delegate.name,
                value = transformed,
                encoding = delegate.configuration.encoding,
                maxAge = maxAgeSeconds.toInt(),
                path = delegate.configuration.path,
                domain = delegate.configuration.domain,
                secure = delegate.configuration.secure,
                httpOnly = delegate.configuration.httpOnly,
                extensions = delegate.configuration.extensions
            )
        )
    }

    override fun clear(call: ApplicationCall) {
        delegate.clear(call)
    }
}

class ClusterSessionTransformer(
    private val secretSupplier: () -> String
) : SessionTransportTransformer {
    override fun transformRead(transportValue: String): String? {
        val secret = secretSupplier()
        val transformer = SessionTransportTransformerMessageAuthentication(secret.toByteArray())
        return transformer.transformRead(transportValue)
    }

    override fun transformWrite(transportValue: String): String {
        val secret = secretSupplier()
        val transformer = SessionTransportTransformerMessageAuthentication(secret.toByteArray())
        return transformer.transformWrite(transportValue)
    }
}

