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
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import com.obsidianscout.db.AppSettings
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.config.JsonSupport
import org.jetbrains.exposed.sql.insert
import com.obsidianscout.db.PasswordResetTokens
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.UserSessions
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    val id: String,
    val username: String,
    val teamNumber: Int,
    val program: String,
    val role: UserRole,
    val createdAt: String,
    val email: String? = null,
    val profilePicture: String? = null,
    val notificationPreference: String = "all",
    val tourProgress: String? = null,
    val nodeAlertsEnabled: Boolean = false
)

@Serializable
data class UserSessionInfo(
    val id: String,
    val clientType: String,
    val deviceName: String,
    val userAgent: String,
    val ipAddress: String,
    val createdAt: String,
    val lastActiveAt: String,
    val isCurrent: Boolean
)

object AuthService {

    /**
     * Ensures a SUPERADMIN exists at startup. If none exists, creates one from the seed config.
     */
    fun ensureSeedSuperAdmin(seed: SeedConfig) {
        transaction {
            val superAdmin = Users
                .selectAll().where { (Users.username eq seed.adminUsername) and (Users.teamNumber eq seed.adminTeamNumber) }
                .limit(1)
                .firstOrNull()
            if (superAdmin == null) {
                val hash = hashPassword(seed.adminPassword)
                Users.insertAndGetId {
                    it[username] = seed.adminUsername
                    it[teamNumber] = seed.adminTeamNumber
                    it[passwordHash] = hash
                    it[role] = UserRole.SUPERADMIN.name
                    it[createdAt] = Instant.now()
                }
            } else {
                val hash = hashPassword(seed.adminPassword)
                Users.update({ Users.id eq superAdmin[Users.id] }) {
                    it[passwordHash] = hash
                    it[role] = UserRole.SUPERADMIN.name
                }
            }
        }
    }

    fun login(username: String, teamNumber: Int, password: String, program: String = "FRC"): UserRecord? {
        val cleanUsername = username.trim()
        val cleanProgram = program.trim()

        // Fetch the stored hash first (short read transaction).
        val (hash, record) = readTransaction {
            val row = Users
                .selectAll().where {
                    (Users.username eq cleanUsername) and
                    (Users.teamNumber eq teamNumber) and
                    (Users.program.lowerCase() eq cleanProgram.lowercase())
                }
                .limit(1)
                .firstOrNull()
                ?: return@readTransaction null
            Pair(row[Users.passwordHash], rowToUser(row))
        } ?: return null

        // Support scrypt fallback and automatic BCrypt upgrading
        val verified = if (hash.startsWith("scrypt:")) {
            if (verifyScrypt(password, hash)) {
                // Re-hash to BCrypt and save if database writes are available
                val newHash = hashPassword(password)
                val userUuid = runCatching { UUID.fromString(record.id) }.getOrNull()
                if (userUuid != null && !com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
                    try {
                        transaction {
                            Users.update({ Users.id eq userUuid }) {
                                it[passwordHash] = newHash
                            }
                        }
                    } catch (_: Throwable) {}
                }
                true
            } else {
                false
            }
        } else {
            // BCrypt verification is CPU-heavy (~400 ms). Run it OUTSIDE the transaction
            // so it does not hold a HikariCP connection for its full duration.
            BCrypt.verifyer().verify(password.toCharArray(), hash).verified
        }
        return if (verified) record else null
    }

    private val selfRegisterRoles = setOf(UserRole.ADMIN, UserRole.ANALYTICS, UserRole.SCOUT)

    /**
     * Self-registration with a team role (admin, analytics, or scout).
     */
    fun register(username: String, teamNumber: Int, password: String, program: String = "FRC", role: UserRole = UserRole.SCOUT, email: String? = null): UserRecord {
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
                .selectAll().where { (Users.username eq username) and (Users.teamNumber eq teamNumber) and (Users.program eq program) }
                .limit(1)
                .any()
            if (existing) {
                throw ApiException(HttpStatusCode.Conflict, "User already exists on this team")
            }
            val id = Users.insertAndGetId {
                it[Users.username] = username
                it[Users.teamNumber] = teamNumber
                it[Users.program] = program
                it[Users.passwordHash] = hash
                it[Users.role] = role.name
                it[Users.createdAt] = Instant.now()
                it[Users.email] = email?.takeIf { it.isNotBlank() }
            }

            val existingTeamSettings = AppSettings
                .selectAll().where { (AppSettings.teamNumber eq teamNumber) and (AppSettings.program eq program) }
                .limit(1)
                .any()
            if (!existingTeamSettings) {
                val jsonText = JsonSupport.json.encodeToString(ApiSettings.serializer(), ApiSettings(program = program, eventCode = "", eventKey = ""))
                AppSettings.insert {
                    it[AppSettings.teamNumber] = teamNumber
                    it[AppSettings.program] = program
                    it[settingsJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
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
        programFilter: String? = null,
        limit: Int = 50,
        offset: Long = 0L
    ): List<UserRecord> {
        println("listUsers: search=$search, teamFilter=$teamFilter, roleFilter=$roleFilter, programFilter=$programFilter, limit=$limit, offset=$offset")
        return readTransaction {
            addLogger(StdOutSqlLogger)
            val query = when (callerSession.role) {
                UserRole.SUPERADMIN -> {
                    val q = Users.selectAll().where { Users.username neq "Deleted User" }
                    if (teamFilter != null) {
                        q.andWhere { Users.teamNumber eq teamFilter }
                    }
                    if (!programFilter.isNullOrBlank()) {
                        q.andWhere { Users.program eq programFilter }
                    }
                    q
                }
                UserRole.ADMIN -> {
                    val q = Users.selectAll().where { (Users.teamNumber eq callerSession.teamNumber) and (Users.program eq callerSession.program) and (Users.username neq "Deleted User") }
                    if (teamFilter != null && teamFilter != callerSession.teamNumber) {
                        q.andWhere { Users.teamNumber eq -1 }
                    }
                    q
                }
                else -> return@readTransaction emptyList()
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
        program: String = callerSession.program,
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

        // ADMIN can only create users on their own team and program
        if (callerSession.role == UserRole.ADMIN && (teamNumber != callerSession.teamNumber || program != callerSession.program)) {
            throw ApiException(HttpStatusCode.Forbidden, "Admins can only create users on their own team and program")
        }

        // Hash the password BEFORE opening a transaction so the slow CPU work
        // does not hold a HikariCP connection.
        val hash = hashPassword(password)
        return transaction {
            val existing = Users
                .selectAll().where { (Users.username eq username) and (Users.teamNumber eq teamNumber) and (Users.program eq program) }
                .limit(1)
                .any()
            if (existing) {
                throw ApiException(HttpStatusCode.Conflict, "User already exists")
            }
            val id = Users.insertAndGetId {
                it[Users.username] = username
                it[Users.teamNumber] = teamNumber
                it[Users.program] = program
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
        targetUserId: String,
        newUsername: String?,
        newPassword: String?,
        newRole: UserRole?,
        newEmail: String? = null,
        newTeamNumber: Int? = null,
        newProfilePicture: String? = null,
        clearProfilePicture: Boolean = false,
        newNotificationPreference: String? = null,
        newTourProgress: String? = null,
        newNodeAlertsEnabled: Boolean? = null
    ): UserRecord {
        val targetUuid = runCatching { UUID.fromString(targetUserId) }.getOrElse {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID format")
        }

        if (newPassword != null && newPassword.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Password cannot be blank")
        }

        // Hash outside the transaction if needed
        val newHash = newPassword?.takeIf { it.isNotBlank() }
            ?.let { hashPassword(it) }

        return transaction {
            val targetRow = Users.selectAll().where { Users.id eq targetUuid }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "User not found")

            val targetRole = try { UserRole.valueOf(targetRow[Users.role]) } catch (_: Exception) { UserRole.SCOUT }
            val targetTeam = targetRow[Users.teamNumber]
            val targetProgram = targetRow[Users.program]
            val isSelfUpdate = callerSession.userId == targetUserId

            // Non-admin callers cannot edit other users
            if (!isSelfUpdate && !callerSession.role.isAtLeast(UserRole.ADMIN)) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to edit this user")
            }

            // Self-service restrictions for non-superadmins: cannot change role or team number
            if (isSelfUpdate && callerSession.role != UserRole.SUPERADMIN) {
                if (newRole != null && newRole != targetRole) {
                    throw ApiException(HttpStatusCode.Forbidden, "You cannot change your own role")
                }
                if (newTeamNumber != null && newTeamNumber != targetTeam) {
                    throw ApiException(HttpStatusCode.Forbidden, "You cannot change your own team number")
                }
            }

            // ADMIN restrictions when editing other users
            if (callerSession.role == UserRole.ADMIN && !isSelfUpdate) {
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
                    throw ApiException(HttpStatusCode.Forbidden, "Only superadmins can change other users' usernames")
                }
            }

            val targetTeamFinal = newTeamNumber ?: targetTeam
            val checkUsername = newUsername?.trim() ?: targetRow[Users.username]

            if (newUsername != null) {
                if (checkUsername.isBlank()) {
                    throw ApiException(HttpStatusCode.BadRequest, "Username cannot be blank")
                }
                if (checkUsername.equals("Deleted User", ignoreCase = true)) {
                    throw ApiException(HttpStatusCode.BadRequest, "Invalid username")
                }
            }

            if (newUsername != null || newTeamNumber != null) {
                val exists = Users.selectAll().where { 
                    (Users.username eq checkUsername) and 
                    (Users.teamNumber eq targetTeamFinal) and 
                    (Users.program eq targetProgram) and
                    (Users.id neq targetUuid) 
                }.any()
                if (exists) {
                    throw ApiException(HttpStatusCode.Conflict, "Username is already taken on this team")
                }
            }

            Users.update({ Users.id eq targetUuid }) { stmt ->
                if (!newUsername.isNullOrBlank()) stmt[username] = newUsername.trim()
                if (newHash != null)             stmt[passwordHash] = newHash
                if (newRole != null)             stmt[role] = newRole.name
                if (newEmail != null)            stmt[email] = newEmail.takeIf { it.isNotBlank() }
                if (newNotificationPreference != null) stmt[notificationPreference] = newNotificationPreference
                if (newNodeAlertsEnabled != null) stmt[nodeAlertsEnabled] = newNodeAlertsEnabled
                if (newTeamNumber != null && callerSession.role == UserRole.SUPERADMIN) {
                    stmt[teamNumber] = newTeamNumber
                }
                if (clearProfilePicture) {
                    stmt[profilePicture] = null
                } else if (newProfilePicture != null) {
                    stmt[profilePicture] = newProfilePicture
                }
                if (newTourProgress != null) {
                    stmt[tourProgress] = newTourProgress
                }
            }

            val updated = Users.selectAll().where { Users.id eq targetUuid }.first()
            rowToUser(updated)
        }
    }

    fun deleteUser(callerSession: UserSession, targetUserId: String) {
        val targetUuid = runCatching { UUID.fromString(targetUserId) }.getOrElse {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID format")
        }

        val (targetRole, targetTeam) = readTransaction {
            val targetRow = Users.selectAll().where { Users.id eq targetUuid }
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
            val superAdminCount = readTransaction {
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
            ScoutingEntries.update({ ScoutingEntries.submittedByUserId eq targetUuid }) {
                it[submittedByUserId] = deletedUserEntityId
            }
            PitScoutingEntries.update({ PitScoutingEntries.submittedByUserId eq targetUuid }) {
                it[submittedByUserId] = deletedUserEntityId
            }
            QualitativeScoutingEntries.update({ QualitativeScoutingEntries.submittedByUserId eq targetUuid }) {
                it[submittedByUserId] = deletedUserEntityId
            }

            // Delete password reset tokens for the target user
            PasswordResetTokens.deleteWhere { userId eq targetUuid }

            // Delete active sessions for the target user
            UserSessions.deleteWhere { userId eq targetUuid }

            // Delete the target user
            Users.deleteWhere { Users.id eq targetUuid }
        }
    }

    private val sessionTouchCache = ConcurrentHashMap<UUID, Long>()

    fun createSession(
        userId: UUID,
        clientType: String = "web",
        userAgent: String = "",
        ipAddress: String = "",
        customDeviceName: String? = null,
        expiresAt: Instant? = null
    ): UUID {
        val device = customDeviceName?.takeIf { it.isNotBlank() } ?: parseDeviceName(userAgent, clientType)
        val now = Instant.now()
        val generatedId = UUID.randomUUID()
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
            return generatedId
        }
        return try {
            transaction {
                UserSessions.insertAndGetId {
                    it[UserSessions.id] = generatedId
                    it[UserSessions.userId] = userId
                    it[UserSessions.clientType] = clientType
                    it[UserSessions.deviceName] = device
                    it[UserSessions.userAgent] = userAgent
                    it[UserSessions.ipAddress] = ipAddress
                    it[UserSessions.createdAt] = now
                    it[UserSessions.lastActiveAt] = now
                    it[UserSessions.expiresAt] = expiresAt
                }.value
            }
        } catch (_: Throwable) {
            generatedId
        }
    }

    fun listSessions(userId: UUID, currentSessionId: String?): List<UserSessionInfo> {
        return readTransaction {
            UserSessions.selectAll().where { UserSessions.userId eq userId }
                .orderBy(UserSessions.lastActiveAt to SortOrder.DESC)
                .map { row ->
                    val sId = row[UserSessions.id].value.toString()
                    UserSessionInfo(
                        id = sId,
                        clientType = row[UserSessions.clientType],
                        deviceName = row[UserSessions.deviceName],
                        userAgent = row[UserSessions.userAgent],
                        ipAddress = row[UserSessions.ipAddress],
                        createdAt = row[UserSessions.createdAt].toString(),
                        lastActiveAt = row[UserSessions.lastActiveAt].toString(),
                        isCurrent = (sId == currentSessionId)
                    )
                }
        }
    }

    fun revokeSession(userId: UUID, sessionId: UUID): Boolean {
        return transaction {
            UserSessions.deleteWhere {
                (UserSessions.id eq sessionId) and (UserSessions.userId eq userId)
            } > 0
        }
    }

    fun revokeAllOtherSessions(userId: UUID, currentSessionId: String?): Int {
        val currentUuid = currentSessionId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return transaction {
            if (currentUuid != null) {
                UserSessions.deleteWhere {
                    (UserSessions.userId eq userId) and (UserSessions.id neq currentUuid)
                }
            } else {
                UserSessions.deleteWhere {
                    UserSessions.userId eq userId
                }
            }
        }
    }

    fun revokeAllSessions(userId: UUID): Int {
        return transaction {
            UserSessions.deleteWhere {
                UserSessions.userId eq userId
            }
        }
    }

    fun touchSession(sessionIdStr: String) {
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return
        val sessionUuid = runCatching { UUID.fromString(sessionIdStr) }.getOrNull() ?: return
        val nowMs = System.currentTimeMillis()
        val lastTouch = sessionTouchCache[sessionUuid] ?: 0L
        if (nowMs - lastTouch > 300_000L) { // 5 minutes throttle
            sessionTouchCache[sessionUuid] = nowMs
            try {
                transaction {
                    exec("SET statement_timeout = '1500ms';")
                    UserSessions.update({ UserSessions.id eq sessionUuid }) {
                        it[lastActiveAt] = Instant.ofEpochMilli(nowMs)
                    }
                }
            } catch (e: Throwable) {
                if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLossException(e)) {
                    com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost = true
                }
                // Non-critical background update: do not fail user requests during quorum loss or timeout
            }
        }
    }

    fun parseDeviceName(userAgent: String?, clientType: String = "web"): String {
        if (userAgent.isNullOrBlank()) {
            return if (clientType.equals("mobile", ignoreCase = true)) "Mobile App" else "Web Browser"
        }
        val ua = userAgent.trim()

        if (ua.contains("ObsidianScout", ignoreCase = true) || ua.contains("Dart/", ignoreCase = true) || clientType.equals("mobile", ignoreCase = true)) {
            val os = when {
                ua.contains("Android", ignoreCase = true) -> "Android"
                ua.contains("iPhone", ignoreCase = true) || ua.contains("iPad", ignoreCase = true) || ua.contains("iOS", ignoreCase = true) -> "iOS"
                ua.contains("Windows", ignoreCase = true) -> "Windows"
                ua.contains("Macintosh", ignoreCase = true) || ua.contains("Mac OS", ignoreCase = true) -> "macOS"
                else -> "Mobile"
            }
            return "ObsidianScout App on $os"
        }

        val browser = when {
            ua.contains("Edg/", ignoreCase = true) || ua.contains("Edge/", ignoreCase = true) -> "Edge"
            ua.contains("OPR/", ignoreCase = true) || ua.contains("Opera", ignoreCase = true) -> "Opera"
            ua.contains("SamsungBrowser", ignoreCase = true) -> "Samsung Internet"
            ua.contains("Chrome", ignoreCase = true) && !ua.contains("Chromium", ignoreCase = true) -> "Chrome"
            ua.contains("Firefox", ignoreCase = true) -> "Firefox"
            ua.contains("Safari", ignoreCase = true) && !ua.contains("Chrome", ignoreCase = true) -> "Safari"
            ua.contains("Trident", ignoreCase = true) || ua.contains("MSIE", ignoreCase = true) -> "Internet Explorer"
            else -> "Web Browser"
        }

        val os = when {
            ua.contains("iPhone", ignoreCase = true) -> "iOS (iPhone)"
            ua.contains("iPad", ignoreCase = true) -> "iOS (iPad)"
            ua.contains("Android", ignoreCase = true) -> "Android"
            ua.contains("Windows NT 10.0", ignoreCase = true) || ua.contains("Windows", ignoreCase = true) -> "Windows"
            ua.contains("Mac OS X", ignoreCase = true) || ua.contains("Macintosh", ignoreCase = true) -> "macOS"
            ua.contains("CrOS", ignoreCase = true) -> "ChromeOS"
            ua.contains("Linux", ignoreCase = true) -> "Linux"
            else -> "Unknown OS"
        }

        return "$browser on $os"
    }

    fun getUserById(userId: String): UserRecord? {
        val uuid = runCatching { UUID.fromString(userId) }.getOrNull() ?: return null
        return readTransaction {
            Users.selectAll().where { Users.id eq uuid }
                .limit(1)
                .map { rowToUser(it) }
                .firstOrNull()
        }
    }

    private fun rowToUser(row: ResultRow): UserRecord {
        return UserRecord(
            id = row[Users.id].value.toString(),
            username = row[Users.username],
            teamNumber = row[Users.teamNumber],
            program = row[Users.program],
            role = try {
                UserRole.valueOf(row[Users.role])
            } catch (_: IllegalArgumentException) {
                UserRole.SCOUT
            },
            createdAt = row[Users.createdAt].toString(),
            email = row[Users.email],
            profilePicture = row[Users.profilePicture],
            notificationPreference = row[Users.notificationPreference],
            tourProgress = row[Users.tourProgress],
            nodeAlertsEnabled = row.getOrNull(Users.nodeAlertsEnabled) ?: false
        )
    }

    private fun hashPassword(password: String): String {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray())
    }

    private fun verifyScrypt(password: String, hash: String): Boolean {
        return try {
            val parts = hash.split("$")
            if (parts.size != 3) return false
            val params = parts[0].split(":")
            if (params.size != 4 || params[0] != "scrypt") return false
            val n = params[1].toInt()
            val r = params[2].toInt()
            val p = params[3].toInt()
            val salt = parts[1].toByteArray(Charsets.UTF_8)
            val expectedHashBytes = org.bouncycastle.util.encoders.Hex.decode(parts[2])
            val derived = org.bouncycastle.crypto.generators.SCrypt.generate(
                password.toByteArray(Charsets.UTF_8),
                salt,
                n,
                r,
                p,
                expectedHashBytes.size
            )
            derived.contentEquals(expectedHashBytes)
        } catch (e: Exception) {
            false
        }
    }
}
