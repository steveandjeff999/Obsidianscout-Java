package com.obsidianscout.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.obsidianscout.config.SeedConfig
import com.obsidianscout.db.Users
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

@Serializable
enum class UserRole {
    SUPERADMIN,
    ADMIN,
    ANALYTICS,
    SCOUT;

    fun isAtLeast(required: UserRole): Boolean {
        return this.ordinal <= required.ordinal
    }
}

@Serializable
data class UserRecord(
    val id: Int,
    val username: String,
    val teamNumber: Int,
    val role: UserRole,
    val createdAt: String
)

object AuthService {

    /**
     * Ensures a SUPERADMIN exists at startup. If none exists, creates one from the seed config.
     */
    fun ensureSeedSuperAdmin(seed: SeedConfig) {
        transaction {
            val superAdminExists = Users
                .select { Users.role eq UserRole.SUPERADMIN.name }
                .limit(1)
                .firstOrNull() != null
            if (!superAdminExists) {
                val hash = hashPassword(seed.adminPassword)
                Users.insertAndGetId {
                    it[username] = seed.adminUsername
                    it[teamNumber] = seed.adminTeamNumber
                    it[passwordHash] = hash
                    it[role] = UserRole.SUPERADMIN.name
                    it[createdAt] = Instant.now()
                }
            }
        }
    }

    fun login(username: String, teamNumber: Int, password: String): UserRecord? {
        return transaction {
            val row = Users
                .select { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
                .limit(1)
                .firstOrNull()
                ?: return@transaction null
            val hash = row[Users.passwordHash]
            val verified = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
            if (!verified) {
                return@transaction null
            }
            rowToUser(row)
        }
    }

    private val selfRegisterRoles = setOf(UserRole.ADMIN, UserRole.ANALYTICS, UserRole.SCOUT)

    /**
     * Self-registration with a team role (admin, analytics, or scout).
     */
    fun register(username: String, teamNumber: Int, password: String, role: UserRole = UserRole.SCOUT): UserRecord {
        if (username.isBlank() || password.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Username and password are required")
        }
        if (teamNumber <= 0) {
            throw ApiException(HttpStatusCode.BadRequest, "A valid team number is required")
        }
        if (role !in selfRegisterRoles) {
            throw ApiException(HttpStatusCode.BadRequest, "Role must be admin, analytics, or scout")
        }
        return transaction {
            val existing = Users
                .select { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
                .limit(1)
                .any()
            if (existing) {
                throw ApiException(HttpStatusCode.Conflict, "User already exists on this team")
            }
            val hash = hashPassword(password)
            val id = Users.insertAndGetId {
                it[Users.username] = username
                it[Users.teamNumber] = teamNumber
                it[Users.passwordHash] = hash
                it[Users.role] = role.name
                it[Users.createdAt] = Instant.now()
            }
            val row = Users.select { Users.id eq id }.first()
            rowToUser(row)
        }
    }

    /**
     * Lists users visible to the caller.
     * SUPERADMIN sees all users.
     * ADMIN sees only users on their team.
     * Others get an empty list.
     */
    fun listUsers(callerSession: UserSession): List<UserRecord> {
        return transaction {
            val query = when (callerSession.role) {
                UserRole.SUPERADMIN -> Users.selectAll()
                UserRole.ADMIN -> Users.select { Users.teamNumber eq callerSession.teamNumber }
                else -> return@transaction emptyList()
            }
            query
                .orderBy(Users.teamNumber, SortOrder.ASC)
                .map { rowToUser(it) }
        }
    }

    /**
     * Creates a user. Enforces role hierarchy:
     * - Only SUPERADMIN can create SUPERADMIN users
     * - ADMIN can create SCOUT, ANALYTICS, ADMIN on their own team
     * - SUPERADMIN can create any role on any team
     */
    fun createUser(
        callerSession: UserSession,
        username: String,
        teamNumber: Int,
        password: String,
        role: UserRole
    ): UserRecord {
        if (username.isBlank() || password.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Username and password are required")
        }

        // Only SUPERADMIN can create another SUPERADMIN
        if (role == UserRole.SUPERADMIN && callerSession.role != UserRole.SUPERADMIN) {
            throw ApiException(HttpStatusCode.Forbidden, "Only a superadmin can create superadmin accounts")
        }

        // ADMIN can only create users on their own team
        if (callerSession.role == UserRole.ADMIN && teamNumber != callerSession.teamNumber) {
            throw ApiException(HttpStatusCode.Forbidden, "Admins can only create users on their own team")
        }

        return transaction {
            val existing = Users
                .select { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
                .limit(1)
                .any()
            if (existing) {
                throw ApiException(HttpStatusCode.Conflict, "User already exists")
            }
            val hash = hashPassword(password)
            val id = Users.insertAndGetId {
                it[Users.username] = username
                it[Users.teamNumber] = teamNumber
                it[Users.passwordHash] = hash
                it[Users.role] = role.name
                it[Users.createdAt] = Instant.now()
            }
            val row = Users.select { Users.id eq id }.first()
            rowToUser(row)
        }
    }

    private fun rowToUser(row: ResultRow): UserRecord {
        return UserRecord(
            id = row[Users.id].value,
            username = row[Users.username],
            teamNumber = row[Users.teamNumber],
            role = try {
                UserRole.valueOf(row[Users.role])
            } catch (_: IllegalArgumentException) {
                UserRole.SCOUT
            },
            createdAt = row[Users.createdAt].toString()
        )
    }

    private fun hashPassword(password: String): String {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray())
    }
}
