package com.obsidianscout.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.obsidianscout.config.SeedConfig
import com.obsidianscout.db.Users
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
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
        // Fetch the stored hash first (short transaction — just a DB read).
        val (hash, record) = transaction {
            val row = Users
                .select { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
                .limit(1)
                .firstOrNull()
                ?: return@transaction null
            Pair(row[Users.passwordHash], rowToUser(row))
        } ?: return null

        // BCrypt verification is CPU-heavy (~400 ms). Run it OUTSIDE the transaction
        // so it does not hold a HikariCP connection for its full duration.
        val verified = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
        return if (verified) record else null
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
        // Hash the password BEFORE opening a transaction so the slow CPU work
        // does not hold a HikariCP connection.
        val hash = hashPassword(password)
        return transaction {
            val existing = Users
                .select { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
                .limit(1)
                .any()
            if (existing) {
                throw ApiException(HttpStatusCode.Conflict, "User already exists on this team")
            }
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
    fun listUsers(
        callerSession: UserSession,
        search: String? = null,
        teamFilter: Int? = null,
        roleFilter: UserRole? = null,
        limit: Int = 50,
        offset: Long = 0L
    ): List<UserRecord> {
        println("listUsers: search=$search, teamFilter=$teamFilter, roleFilter=$roleFilter, limit=$limit, offset=$offset")
        return transaction {
            addLogger(StdOutSqlLogger)
            val query = when (callerSession.role) {
                UserRole.SUPERADMIN -> {
                    val q = Users.selectAll()
                    if (teamFilter != null) {
                        q.andWhere { Users.teamNumber eq teamFilter }
                    }
                    q
                }
                UserRole.ADMIN -> {
                    val q = Users.select { Users.teamNumber eq callerSession.teamNumber }
                    if (teamFilter != null && teamFilter != callerSession.teamNumber) {
                        q.andWhere { Users.teamNumber eq -1 }
                    }
                    q
                }
                else -> return@transaction emptyList()
            }

            if (!search.isNullOrBlank()) {
                val cleanSearch = "%${search.lowercase()}%"
                query.andWhere { Users.username.lowerCase() like cleanSearch }
            }

            if (roleFilter != null) {
                query.andWhere { Users.role eq roleFilter.name }
            }

            query
                .orderBy(Users.teamNumber to SortOrder.ASC, Users.username to SortOrder.ASC)
                .limit(limit, offset = offset)
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

        // Hash the password BEFORE opening a transaction so the slow CPU work
        // does not hold a HikariCP connection.
        val hash = hashPassword(password)
        return transaction {
            val existing = Users
                .select { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
                .limit(1)
                .any()
            if (existing) {
                throw ApiException(HttpStatusCode.Conflict, "User already exists")
            }
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
     * Updates a user's username, password, and/or role.
     * - SUPERADMIN can edit any user.
     * - ADMIN can only edit users on their own team and cannot touch SUPERADMIN accounts
     *   or grant the SUPERADMIN role.
     */
    fun updateUser(
        callerSession: UserSession,
        targetUserId: Int,
        newUsername: String?,
        newPassword: String?,
        newRole: UserRole?
    ): UserRecord {
        // Hash outside the transaction if needed
        val newHash = newPassword?.takeIf { it.isNotBlank() }
            ?.let { hashPassword(it) }

        return transaction {
            val targetRow = Users.select { Users.id eq targetUserId }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "User not found")

            val targetRole = try { UserRole.valueOf(targetRow[Users.role]) } catch (_: Exception) { UserRole.SCOUT }
            val targetTeam = targetRow[Users.teamNumber]

            // ADMIN restrictions
            if (callerSession.role == UserRole.ADMIN) {
                if (targetTeam != callerSession.teamNumber) {
                    throw ApiException(HttpStatusCode.Forbidden, "Admins can only edit users on their own team")
                }
                if (targetRole == UserRole.SUPERADMIN) {
                    throw ApiException(HttpStatusCode.Forbidden, "Admins cannot edit superadmin accounts")
                }
                if (newRole == UserRole.SUPERADMIN) {
                    throw ApiException(HttpStatusCode.Forbidden, "Admins cannot grant the superadmin role")
                }
            }

            Users.update({ Users.id eq targetUserId }) { stmt ->
                if (!newUsername.isNullOrBlank()) stmt[username] = newUsername
                if (newHash != null)             stmt[passwordHash] = newHash
                if (newRole != null)             stmt[role] = newRole.name
            }

            val updated = Users.select { Users.id eq targetUserId }.first()
            rowToUser(updated)
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
