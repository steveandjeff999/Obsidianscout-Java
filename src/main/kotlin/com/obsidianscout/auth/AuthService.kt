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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import com.obsidianscout.db.PasswordResetTokens
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
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
    val createdAt: String,
    val email: String? = null,
    val profilePicture: String? = null
)

object AuthService {

    /**
     * Ensures a SUPERADMIN exists at startup. If none exists, creates one from the seed config.
     */
    fun ensureSeedSuperAdmin(seed: SeedConfig) {
        transaction {
            val superAdminExists = Users
                .selectAll().where { Users.role eq UserRole.SUPERADMIN.name }
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
                .selectAll().where { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
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
    fun register(username: String, teamNumber: Int, password: String, role: UserRole = UserRole.SCOUT, email: String? = null): UserRecord {
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
                .selectAll().where { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
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
                it[Users.email] = email?.takeIf { it.isNotBlank() }
            }
            val row = Users.selectAll().where { Users.id eq id }.first()
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
                    val q = Users.selectAll().where { Users.username neq "Deleted User" }
                    if (teamFilter != null) {
                        q.andWhere { Users.teamNumber eq teamFilter }
                    }
                    q
                }
                UserRole.ADMIN -> {
                    val q = Users.selectAll().where { (Users.teamNumber eq callerSession.teamNumber) and (Users.username neq "Deleted User") }
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
        role: UserRole,
        email: String? = null
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
                .selectAll().where { (Users.username eq username) and (Users.teamNumber eq teamNumber) }
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
                it[Users.email] = email?.takeIf { it.isNotBlank() }
            }
            val row = Users.selectAll().where { Users.id eq id }.first()
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
        newRole: UserRole?,
        newEmail: String? = null,
        newTeamNumber: Int? = null,
        newProfilePicture: String? = null,
        clearProfilePicture: Boolean = false
    ): UserRecord {
        // Hash outside the transaction if needed
        val newHash = newPassword?.takeIf { it.isNotBlank() }
            ?.let { hashPassword(it) }

        return transaction {
            val targetRow = Users.selectAll().where { Users.id eq targetUserId }
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
                if (newTeamNumber != null) {
                    throw ApiException(HttpStatusCode.Forbidden, "Only superadmins can change team numbers")
                }
                if (!newUsername.isNullOrBlank() && newUsername != targetRow[Users.username]) {
                    throw ApiException(HttpStatusCode.Forbidden, "Only superadmins can change usernames")
                }
            }

            val targetTeamFinal = newTeamNumber ?: targetTeam
            val checkUsername = newUsername ?: targetRow[Users.username]
            if (newUsername != null || newTeamNumber != null) {
                val exists = Users.selectAll().where { 
                    (Users.username eq checkUsername) and 
                    (Users.teamNumber eq targetTeamFinal) and 
                    (Users.id neq targetUserId) 
                }.any()
                if (exists) {
                    throw ApiException(HttpStatusCode.Conflict, "Username is already taken on this team")
                }
            }

            Users.update({ Users.id eq targetUserId }) { stmt ->
                if (!newUsername.isNullOrBlank()) stmt[username] = newUsername
                if (newHash != null)             stmt[passwordHash] = newHash
                if (newRole != null)             stmt[role] = newRole.name
                if (newEmail != null)            stmt[email] = newEmail.takeIf { it.isNotBlank() }
                if (newTeamNumber != null && callerSession.role == UserRole.SUPERADMIN) {
                    stmt[teamNumber] = newTeamNumber
                }
                if (clearProfilePicture) {
                    stmt[profilePicture] = null
                } else if (newProfilePicture != null) {
                    stmt[profilePicture] = newProfilePicture
                }
            }

            val updated = Users.selectAll().where { Users.id eq targetUserId }.first()
            rowToUser(updated)
        }
    }

    fun deleteUser(callerSession: UserSession, targetUserId: Int) {
        val (targetRole, targetTeam) = transaction {
            val targetRow = Users.selectAll().where { Users.id eq targetUserId }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "User not found")
            val role = try { UserRole.valueOf(targetRow[Users.role]) } catch (_: Exception) { UserRole.SCOUT }
            val team = targetRow[Users.teamNumber]
            Pair(role, team)
        }

        val isSelfDelete = callerSession.userId == targetUserId

        if (!isSelfDelete) {
            // Permissions check for deleting someone else
            if (!callerSession.role.isAtLeast(UserRole.ADMIN)) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to delete this account")
            }
            if (callerSession.role == UserRole.ADMIN) {
                if (targetTeam != callerSession.teamNumber) {
                    throw ApiException(HttpStatusCode.Forbidden, "Admins can only delete users on their own team")
                }
                if (targetRole == UserRole.SUPERADMIN) {
                    throw ApiException(HttpStatusCode.Forbidden, "Admins cannot delete superadmin accounts")
                }
            }
        }

        // Prevent deleting the last superadmin
        if (targetRole == UserRole.SUPERADMIN) {
            val superAdminCount = transaction {
                Users.selectAll().where { Users.role eq UserRole.SUPERADMIN.name }.count()
            }
            if (superAdminCount <= 1) {
                throw ApiException(HttpStatusCode.Forbidden, "Cannot delete the last superadmin account")
            }
        }

        // Perform updates and deletion in a transaction
        transaction {
            // Ensure the team-specific "Deleted User" placeholder exists
            val deletedUserEntityId = Users.selectAll().where { (Users.username eq "Deleted User") and (Users.teamNumber eq targetTeam) }
                .limit(1)
                .firstOrNull()
                ?.let { it[Users.id] }
                ?: Users.insertAndGetId {
                    it[username] = "Deleted User"
                    it[teamNumber] = targetTeam
                    it[passwordHash] = "DELETED_USER_PLACEHOLDER"
                    it[role] = UserRole.SCOUT.name
                    it[createdAt] = Instant.now()
                }

            // Transfer scouting entries to the placeholder user
            ScoutingEntries.update({ ScoutingEntries.submittedByUserId eq targetUserId }) {
                it[submittedByUserId] = deletedUserEntityId
            }
            PitScoutingEntries.update({ PitScoutingEntries.submittedByUserId eq targetUserId }) {
                it[submittedByUserId] = deletedUserEntityId
            }
            QualitativeScoutingEntries.update({ QualitativeScoutingEntries.submittedByUserId eq targetUserId }) {
                it[submittedByUserId] = deletedUserEntityId
            }

            // Delete password reset tokens for the target user
            PasswordResetTokens.deleteWhere { userId eq targetUserId }

            // Delete the target user
            Users.deleteWhere { Users.id eq targetUserId }
        }
    }

    fun getUserById(userId: Int): UserRecord? {
        return transaction {
            Users.selectAll().where { Users.id eq userId }
                .limit(1)
                .map { rowToUser(it) }
                .firstOrNull()
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
            createdAt = row[Users.createdAt].toString(),
            email = row[Users.email],
            profilePicture = row[Users.profilePicture]
        )
    }

    private fun hashPassword(password: String): String {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray())
    }
}
