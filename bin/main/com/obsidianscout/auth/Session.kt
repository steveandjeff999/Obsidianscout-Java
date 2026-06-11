package com.obsidianscout.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.serialization.Serializable
import io.ktor.server.sessions.SessionTransport
import io.ktor.server.sessions.CookieConfiguration
import io.ktor.server.sessions.SessionTransportTransformer
import io.ktor.server.sessions.transformRead
import io.ktor.server.sessions.transformWrite
import io.ktor.http.Cookie
import io.ktor.util.AttributeKey

@Serializable
data class UserSession(
    val userId: Int,
    val username: String,
    val teamNumber: Int,
    val role: UserRole,
    val email: String? = null
)

class ApiException(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

suspend fun ApplicationCall.requireSession(): UserSession {
    return sessions.get<UserSession>()
        ?: throw ApiException(HttpStatusCode.Unauthorized, "Not signed in")
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
    val name: String,
    val configuration: CookieConfiguration,
    val transformers: List<SessionTransportTransformer>
) : SessionTransport {

    companion object {
        val KEEP_ME_LOGGED_IN_KEY = AttributeKey<Boolean>("KeepMeLoggedIn")
    }

    override fun receive(call: ApplicationCall): String? {
        val rawValue = call.request.cookies[name, configuration.encoding] ?: return null
        return transformers.transformRead(rawValue)
    }

    override fun send(call: ApplicationCall, value: String) {
        val transformed = transformers.transformWrite(value)
        val keepMeLoggedIn = call.attributes.getOrNull(KEEP_ME_LOGGED_IN_KEY) ?: false
        
        val maxAgeSeconds = if (keepMeLoggedIn) {
            60 * 60 * 24 * 30L // 30 days
        } else {
            configuration.maxAgeInSeconds
        }

        call.response.cookies.append(
            Cookie(
                name = name,
                value = transformed,
                encoding = configuration.encoding,
                maxAge = maxAgeSeconds.toInt(),
                path = configuration.path,
                domain = configuration.domain,
                secure = configuration.secure,
                httpOnly = configuration.httpOnly,
                extensions = configuration.extensions
            )
        )
    }

    override fun clear(call: ApplicationCall) {
        call.response.cookies.append(
            Cookie(
                name = name,
                value = "",
                encoding = configuration.encoding,
                maxAge = 0,
                path = configuration.path,
                domain = configuration.domain,
                secure = configuration.secure,
                httpOnly = configuration.httpOnly,
                extensions = configuration.extensions
            )
        )
    }
}
