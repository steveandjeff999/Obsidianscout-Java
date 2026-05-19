package com.obsidianscout.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.serialization.Serializable

@Serializable
data class UserSession(
    val userId: Int,
    val username: String,
    val teamNumber: Int,
    val role: UserRole
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
