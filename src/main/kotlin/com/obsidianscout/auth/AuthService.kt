package com.obsidianscout.auth

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import at.favre.lib.crypto.bcrypt.BCrypt
import com.obsidianscout.config.SeedConfig
import com.obsidianscout.db.Users
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.core.or
import com.obsidianscout.db.AppSettings
import com.obsidianscout.integrations.ApiSettings
import com.obsidianscout.config.JsonSupport
import org.jetbrains.exposed.v1.jdbc.insert
import com.obsidianscout.db.PasswordResetTokens
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.UserSessions
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.ChatMessages
import com.obsidianscout.db.UserChatLastRead
import com.obsidianscout.db.ChatGroups
import com.obsidianscout.db.PushSubscriptions
import com.obsidianscout.db.FcmDeviceTokens
import com.obsidianscout.db.AnalyticsReports
import com.obsidianscout.db.PasskeyCredentials
import com.obsidianscout.db.PasskeyChallenges
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val consoleLog = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.auth.AuthService")


@Serializable
enum class UserRole {
    SUPERADMIN,
    ADMIN,
    ANALYTICS,
    /** Scout-level account whose pages are picked one by one by an admin (see [com.obsidianscout.integrations.ApiSettings.customUserPages]). */
    CUSTOM,
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
    val nodeAlertsEnabled: Boolean = false,
    val bugReportPreference: String = "ask",
    val localAiEnabled: Boolean = false,
    val nonScoutingToolsEnabled: Boolean = false,
    val lastLogin: String? = null,
    /** For a CUSTOM user, the pages an admin picked (from the team settings); empty for other roles. */
    val customPages: List<String> = emptyList()
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
    val isCurrent: Boolean,
    val deviceId: String? = null,
    val expiresAt: String? = null
)

object AuthService {

    /**
     * Ensures a SUPERADMIN exists at startup. If none exists, creates one from the seed config.
     */
    fun ensureSeedSuperAdmin(seed: SeedConfig) {
        // Never touch an existing account here: this runs on every boot, and overwriting the
        // password would undo any change an operator made. Use --reset-superadmin to recover access.
        val exists = readTransaction {
            Users.selectAll().where { (Users.username eq seed.adminUsername) and (Users.teamNumber eq seed.adminTeamNumber) }
                .limit(1)
                .any()
        }
        if (exists) return

        val isDefaultPassword = seed.adminPassword.isBlank() || seed.adminPassword.lowercase() in DEFAULT_SEED_PASSWORDS
        val password = if (isDefaultPassword) generateInitialPassword() else seed.adminPassword
        val hash = hashPassword(password)
        transaction {
            Users.insertAndGetId {
                it[username] = seed.adminUsername
                it[teamNumber] = seed.adminTeamNumber
                it[passwordHash] = hash
                it[role] = UserRole.SUPERADMIN.name
                it[createdAt] = Instant.now()
            }
        }
        if (isDefaultPassword) {
            consoleLog.warn("[ObsidianScout] Created superadmin '${seed.adminUsername}' (team ${seed.adminTeamNumber}) with a generated one-time password: $password")
            consoleLog.warn("[ObsidianScout] Sign in and change it now. This password is not stored anywhere else.")
        }
    }

    private val DEFAULT_SEED_PASSWORDS = setOf("change-me", "changeme")

    private fun generateInitialPassword(): String {
        val bytes = ByteArray(18)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
            // Over-long passwords can never have been stored, and the verifier throws on them.
            password.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES &&
                BCrypt.verifyer().verify(password.toCharArray(), hash).verified
        }
        if (verified) {
            val userUuid = runCatching { UUID.fromString(record.id) }.getOrNull()
            val now = Instant.now()
            if (userUuid != null && !com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
                try {
                    transaction {
                        Users.update({ Users.id eq userUuid }) {
                            it[lastLogin] = now
                        }
                    }
                } catch (_: Throwable) {}
            }
            return record.copy(lastLogin = now.toString())
        }
        return null
    }

    private val selfRegisterRoles = setOf(UserRole.ADMIN, UserRole.ANALYTICS, UserRole.SCOUT)

    /**
     * Self-registration with a team role (admin, analytics, or scout). The first account on a team
     * may pick any of these; after that, the team's registration lock and its allowed
     * self-registration roles (admin settings, all roles by default) apply.
     */
    fun register(username: String, teamNumber: Int, password: String, program: String = "FRC", role: UserRole = UserRole.SCOUT, email: String? = null): UserRecord {
        if (username.isBlank() || password.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Username and password are required")
        }
        validateUsername(username)
        validatePassword(password)
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
            val teamHasUsers = Users
                .selectAll().where { (Users.teamNumber eq teamNumber) and (Users.program eq program) }
                .limit(1)
                .any()
            if (teamHasUsers) {
                val teamSettings = com.obsidianscout.integrations.SettingsService.getSettings(teamNumber, program)
                if (teamSettings.registrationLocked) {
                    throw ApiException(
                        HttpStatusCode.Forbidden,
                        "Registration is locked for this team. Please contact a team administrator to create an account."
                    )
                }
                // Team admins choose which roles can be picked on the create-account page (all by default).
                val allowedRoles = com.obsidianscout.integrations.normalizeSelfRegisterRoles(teamSettings.selfRegisterRoles)
                if (role.name !in allowedRoles) {
                    val allowedText = allowedRoles.joinToString(", ") { it.lowercase().replaceFirstChar(Char::uppercase) }
                    throw ApiException(
                        HttpStatusCode.Forbidden,
                        if (allowedRoles.isEmpty()) {
                            "This team does not allow creating accounts here. Please contact a team administrator."
                        } else {
                            "This team only allows creating accounts with these roles: $allowedText. Ask a team admin if you need a different role."
                        }
                    )
                }
            }

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
                it[Users.lastLogin] = Instant.now()
                it[Users.email] = email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
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
        offset: Long = 0L,
        sortBy: String? = null,
        sortDir: String? = null
    ): List<UserRecord> {
        return withCustomPages(readTransaction {
            val query = when (callerSession.role) {
                UserRole.SUPERADMIN -> {
                    val q = Users.selectAll().where { Users.username neq "Deleted User" }
                    if (teamFilter != null) {
                        q.andWhere { Users.teamNumber eq teamFilter }
                    }
                    if (!programFilter.isNullOrBlank()) {
                        q.andWhere { Users.program.lowerCase() eq programFilter.lowercase().trim() }
                    }
                    q
                }
                UserRole.ADMIN -> {
                    val q = Users.selectAll().where { 
                        (Users.teamNumber eq callerSession.teamNumber) and 
                        (Users.program.lowerCase() eq callerSession.program.lowercase().trim()) and 
                        (Users.username neq "Deleted User") 
                    }
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

            val isDesc = sortDir?.equals("desc", ignoreCase = true) == true
            val order = if (isDesc) SortOrder.DESC else SortOrder.ASC

            val orderPairs = mutableListOf<Pair<org.jetbrains.exposed.v1.core.Expression<*>, SortOrder>>()
            when (sortBy?.lowercase()) {
                "username" -> orderPairs.add(Users.username.lowerCase() to order)
                "email" -> orderPairs.add(Users.email.lowerCase() to order)
                "team", "teamnumber" -> {
                    orderPairs.add(Users.teamNumber to order)
                    orderPairs.add(Users.username.lowerCase() to SortOrder.ASC)
                }
                "role" -> orderPairs.add(Users.role to order)
                "created", "createdat" -> orderPairs.add(Users.createdAt to order)
                "lastlogin" -> orderPairs.add(Users.lastLogin to order)
                else -> {
                    orderPairs.add(Users.teamNumber to SortOrder.ASC)
                    orderPairs.add(Users.username.lowerCase() to SortOrder.ASC)
                }
            }
            // Always append primary key id as tie-breaker for deterministic pagination
            orderPairs.add(Users.id to SortOrder.ASC)

            query
                .orderBy(*orderPairs.toTypedArray())
                .limit(limit).offset(offset.toLong())
                .map { rowToUser(it) }
        })
    }

    /**
     * A CUSTOM user managing accounts may only hand out pages they have themselves; pages the target
     * already had ([keptPages]) can stay even if the caller lacks them.
     */
    private fun requireCustomGrantWithin(requested: List<String>, callerPages: List<String>, keptPages: List<String>) {
        val extra = com.obsidianscout.integrations.normalizeCustomPages(requested) - callerPages.toSet() - keptPages.toSet()
        if (extra.isNotEmpty()) {
            throw ApiException(HttpStatusCode.Forbidden, "You can only give pages you have yourself (not: ${extra.joinToString(", ")})")
        }
    }

    /** Fills in [UserRecord.customPages] for CUSTOM users, reading each team's settings once. */
    fun withCustomPages(users: List<UserRecord>): List<UserRecord> {
        val byTeam = mutableMapOf<Pair<Int, String>, Map<String, List<String>>>()
        return users.map { user ->
            if (user.role != UserRole.CUSTOM) return@map user
            val grants = byTeam.getOrPut(user.teamNumber to user.program) {
                com.obsidianscout.integrations.SettingsService.getSettingsRaw(user.teamNumber, user.program).customUserPages
            }
            user.copy(customPages = com.obsidianscout.integrations.normalizeCustomPages(grants[user.id].orEmpty()))
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
        email: String? = null,
        customPages: List<String>? = null
    ): UserRecord {
        if (username.isBlank() || password.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Username and password are required")
        }
        validateUsername(username)
        validatePassword(password)

        // Only SUPERADMIN can create another SUPERADMIN
        if (role == UserRole.SUPERADMIN && callerSession.role != UserRole.SUPERADMIN) {
            throw ApiException(HttpStatusCode.Forbidden, "Only a superadmin can create superadmin accounts")
        }

        // Non-SUPERADMIN (e.g. ADMIN) can only create users on their own team and program
        if (callerSession.role != UserRole.SUPERADMIN && (teamNumber != callerSession.teamNumber || program != callerSession.program)) {
            throw ApiException(HttpStatusCode.Forbidden, "Admins can only create users on their own team and program")
        }

        callerSession.actingCustomPages?.let { callerPages ->
            if (role == UserRole.ADMIN || role == UserRole.SUPERADMIN) {
                throw ApiException(HttpStatusCode.Forbidden, "Custom users cannot create admin accounts")
            }
            if (role == UserRole.CUSTOM) requireCustomGrantWithin(customPages.orEmpty(), callerPages, emptyList())
        }

        // Hash the password BEFORE opening a transaction so the slow CPU work
        // does not hold a HikariCP connection.
        val hash = hashPassword(password)
        val created = transaction {
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
                it[Users.email] = email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            }
            val row = Users.selectAll().where { Users.id eq id }.first()
            rowToUser(row)
        }
        if (role == UserRole.CUSTOM) {
            com.obsidianscout.integrations.SettingsService.setCustomUserPages(teamNumber, program, created.id, customPages.orEmpty())
        }
        return withCustomPages(listOf(created)).first()
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
        newNodeAlertsEnabled: Boolean? = null,
        newBugReportPreference: String? = null,
        newLocalAiEnabled: Boolean? = null,
        newNonScoutingToolsEnabled: Boolean? = null,
        newCustomPages: List<String>? = null
    ): UserRecord {
        val targetUuid = runCatching { UUID.fromString(targetUserId) }.getOrElse {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID format")
        }

        if (newPassword != null && newPassword.isBlank()) {
            throw ApiException(HttpStatusCode.BadRequest, "Password cannot be blank")
        }
        newPassword?.let { validatePassword(it) }
        if (newProfilePicture != null && !clearProfilePicture) validateProfilePicture(newProfilePicture)

        // Hash outside the transaction if needed
        val newHash = newPassword?.takeIf { it.isNotBlank() }
            ?.let { hashPassword(it) }

        var customPagesChange: Pair<Boolean, UserRecord>? = null
        val result = transaction {
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
                if (targetTeam != callerSession.teamNumber || !targetProgram.equals(callerSession.program, ignoreCase = true)) {
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

            // Page picks are an admin's call, never the user's own.
            if (newCustomPages != null && !callerSession.role.isAtLeast(UserRole.ADMIN)) {
                throw ApiException(HttpStatusCode.Forbidden, "Only admins can choose a user's pages")
            }

            // A CUSTOM user given the Users page manages scout-level accounts only, and can't widen anyone's access
            // (their own included) past what they have themselves.
            callerSession.actingCustomPages?.let { callerPages ->
                if (targetRole == UserRole.ADMIN || targetRole == UserRole.SUPERADMIN) {
                    throw ApiException(HttpStatusCode.Forbidden, "Custom users cannot edit admin accounts")
                }
                if (newRole == UserRole.ADMIN || newRole == UserRole.SUPERADMIN) {
                    throw ApiException(HttpStatusCode.Forbidden, "Custom users cannot grant admin roles")
                }
                if (newCustomPages != null) {
                    val currentPages = com.obsidianscout.integrations.normalizeCustomPages(
                        com.obsidianscout.integrations.SettingsService.getSettingsRaw(targetTeam, targetProgram).customUserPages[targetUserId].orEmpty()
                    )
                    if (isSelfUpdate) {
                        if (com.obsidianscout.integrations.normalizeCustomPages(newCustomPages).toSet() != currentPages.toSet()) {
                            throw ApiException(HttpStatusCode.Forbidden, "You cannot change your own pages")
                        }
                    } else {
                        requireCustomGrantWithin(newCustomPages, callerPages, currentPages)
                    }
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
                if (checkUsername != targetRow[Users.username]) {
                    validateUsername(checkUsername)
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

            // A request can change only the custom pages (kept in team settings), which leaves no column to update.
            val changesUserRow = !newUsername.isNullOrBlank() || newHash != null || newRole != null || newEmail != null ||
                newNotificationPreference != null || newNodeAlertsEnabled != null || newBugReportPreference != null ||
                newLocalAiEnabled != null || newNonScoutingToolsEnabled != null ||
                (newTeamNumber != null && callerSession.role == UserRole.SUPERADMIN) ||
                clearProfilePicture || newProfilePicture != null || newTourProgress != null
            if (changesUserRow) Users.update({ Users.id eq targetUuid }) { stmt ->
                if (!newUsername.isNullOrBlank()) stmt[username] = newUsername.trim()
                if (newHash != null)             stmt[passwordHash] = newHash
                if (newRole != null)             stmt[role] = newRole.name
                if (newEmail != null)            stmt[email] = newEmail.trim().lowercase().takeIf { it.isNotBlank() }
                if (newNotificationPreference != null) stmt[notificationPreference] = newNotificationPreference
                if (newNodeAlertsEnabled != null) stmt[nodeAlertsEnabled] = newNodeAlertsEnabled
                if (newBugReportPreference != null) stmt[bugReportPreference] = newBugReportPreference
                if (newLocalAiEnabled != null) stmt[localAiEnabled] = newLocalAiEnabled
                if (newNonScoutingToolsEnabled != null) stmt[nonScoutingToolsEnabled] = newNonScoutingToolsEnabled
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
            if (newRole != null || newNonScoutingToolsEnabled != null || newTeamNumber != null) {
                // Role or Non-Scouting Tools changed: the user's widgets may need to appear or go away.
                com.obsidianscout.widgets.WidgetPushService.notifyUsers(listOf(targetUuid.toString()), "access")
            }
            val record = rowToUser(updated)
            // Grants live in team settings, written after this transaction. Leaving CUSTOM drops them.
            if (record.role == UserRole.CUSTOM && (newCustomPages != null || targetRole != UserRole.CUSTOM || newTeamNumber != null)) {
                customPagesChange = true to record
            } else if (record.role != UserRole.CUSTOM && targetRole == UserRole.CUSTOM) {
                customPagesChange = false to record
            }
            record
        }
        customPagesChange?.let { (isCustom, record) ->
            val settingsService = com.obsidianscout.integrations.SettingsService
            if (isCustom) {
                val pages = newCustomPages ?: settingsService.getSettingsRaw(record.teamNumber, record.program).customUserPages[record.id].orEmpty()
                settingsService.setCustomUserPages(record.teamNumber, record.program, record.id, pages)
            } else {
                settingsService.setCustomUserPages(record.teamNumber, record.program, record.id, null)
            }
            com.obsidianscout.widgets.WidgetPushService.notifyUsers(listOf(record.id), "access")
        }
        return withCustomPages(listOf(result)).first()
    }

    fun deleteUser(callerSession: UserSession, targetUserId: String) {
        val targetUuid = runCatching { UUID.fromString(targetUserId) }.getOrElse {
            throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID format")
        }

        val (targetRole, targetTeam, targetProgram) = readTransaction {
            val targetRow = Users.selectAll().where { Users.id eq targetUuid }
                .firstOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "User not found")
            val role = try { UserRole.valueOf(targetRow[Users.role]) } catch (_: Exception) { UserRole.SCOUT }
            Triple(role, targetRow[Users.teamNumber], targetRow[Users.program])
        }

        val isSelfDelete = callerSession.userId == targetUserId

        if (!isSelfDelete) {
            // Permissions check for deleting someone else
            if (!callerSession.role.isAtLeast(UserRole.ADMIN)) {
                throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to delete this account")
            }
            if (callerSession.role == UserRole.ADMIN) {
                if (targetTeam != callerSession.teamNumber || !targetProgram.equals(callerSession.program, ignoreCase = true)) {
                    throw ApiException(HttpStatusCode.Forbidden, "Admins can only delete users on their own team")
                }
                if (targetRole == UserRole.SUPERADMIN) {
                    throw ApiException(HttpStatusCode.Forbidden, "Admins cannot delete superadmin accounts")
                }
                if (callerSession.actingCustomPages != null && targetRole == UserRole.ADMIN) {
                    throw ApiException(HttpStatusCode.Forbidden, "Custom users cannot delete admin accounts")
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
                it[ScoutingEntries.updatedAt] = java.time.Instant.now()
            }
            PitScoutingEntries.update({ PitScoutingEntries.submittedByUserId eq targetUuid }) {
                it[submittedByUserId] = deletedUserEntityId
                it[PitScoutingEntries.updatedAt] = java.time.Instant.now()
            }
            QualitativeScoutingEntries.update({ QualitativeScoutingEntries.submittedByUserId eq targetUuid }) {
                it[submittedByUserId] = deletedUserEntityId
                it[QualitativeScoutingEntries.updatedAt] = java.time.Instant.now()
            }

            // Transfer or clear scouting assignment references
            ScoutingAssignments.update({ ScoutingAssignments.assignedUserId eq targetUuid }) {
                it[assignedUserId] = deletedUserEntityId
            }
            ScoutingAssignments.update({ ScoutingAssignments.createdByUserId eq targetUuid }) {
                it[createdByUserId] = null
            }

            // Transfer chat messages and reports to placeholder user, clear created channel ownership
            ChatMessages.update({ ChatMessages.userId eq targetUuid }) {
                it[userId] = deletedUserEntityId
            }
            UserChatLastRead.deleteWhere { userId eq targetUuid }
            ChatGroups.update({ ChatGroups.createdByUserId eq targetUuid }) {
                it[createdByUserId] = null
            }
            AnalyticsReports.update({ AnalyticsReports.userId eq targetUuid }) {
                it[userId] = deletedUserEntityId
            }

            // Delete password reset tokens, sessions, push tokens, and passkeys for the target user
            PasswordResetTokens.deleteWhere { userId eq targetUuid }
            com.obsidianscout.db.WidgetTokens.deleteWhere { com.obsidianscout.db.WidgetTokens.userId eq targetUuid }
            UserSessions.deleteWhere { userId eq targetUuid }
            PushSubscriptions.deleteWhere { userId eq targetUuid }
            FcmDeviceTokens.deleteWhere { userId eq targetUuid }
            PasskeyCredentials.deleteWhere { userId eq targetUuid }
            PasskeyChallenges.deleteWhere { userId eq targetUuid }

            // Delete the target user
            Users.deleteWhere { Users.id eq targetUuid }
        }
        if (targetRole == UserRole.CUSTOM) {
            com.obsidianscout.integrations.SettingsService.setCustomUserPages(targetTeam, targetProgram, targetUserId, null)
        }
    }

    private val sessionTouchCache = ConcurrentHashMap<UUID, Long>()

    fun createSession(
        userId: UUID,
        clientType: String = "web",
        userAgent: String = "",
        ipAddress: String = "",
        customDeviceName: String? = null,
        expiresAt: Instant? = null,
        deviceId: String? = null
    ): UUID {
        val device = customDeviceName?.takeIf { it.isNotBlank() } ?: parseDeviceName(userAgent, clientType)
        val now = Instant.now()
        val generatedId = UUID.randomUUID()
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) {
            return generatedId
        }
        return try {
            transaction {
                // 1. Clean up already expired sessions for this user
                UserSessions.deleteWhere {
                    (UserSessions.userId eq userId) and (UserSessions.expiresAt.isNotNull() and (UserSessions.expiresAt lessEq now))
                }

                // 2. Deduplicate / supersede previous session for this physical device
                val cleanDeviceId = deviceId?.trim()?.takeIf { it.isNotBlank() }
                if (cleanDeviceId != null) {
                    UserSessions.deleteWhere {
                        (UserSessions.userId eq userId) and (UserSessions.deviceId eq cleanDeviceId)
                    }
                }

                // 3. Insert new active session
                UserSessions.insertAndGetId {
                    it[UserSessions.id] = generatedId
                    it[UserSessions.userId] = userId
                    it[UserSessions.clientType] = clientType
                    it[UserSessions.deviceName] = device
                    it[UserSessions.userAgent] = userAgent
                    it[UserSessions.ipAddress] = ipAddress
                    it[UserSessions.deviceId] = cleanDeviceId
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
        val now = Instant.now()
        return readTransaction {
            val rows = UserSessions.selectAll().where {
                (UserSessions.userId eq userId) and
                (UserSessions.expiresAt.isNull() or (UserSessions.expiresAt greater now))
            }
            .orderBy(UserSessions.lastActiveAt to SortOrder.DESC)
            .toList()

            val seenKeys = mutableSetOf<String>()
            val result = mutableListOf<UserSessionInfo>()

            for (row in rows) {
                val sId = row[UserSessions.id].value.toString()
                val isCurr = (sId == currentSessionId)
                val dId = row[UserSessions.deviceId]?.trim()?.takeIf { it.isNotBlank() }
                val devKey = when {
                    dId != null -> "id:$dId"
                    else -> "session:$sId"
                }

                if (isCurr || seenKeys.add(devKey)) {
                    if (isCurr) seenKeys.add(devKey)
                    result.add(
                        UserSessionInfo(
                            id = sId,
                            clientType = row[UserSessions.clientType],
                            deviceName = row[UserSessions.deviceName],
                            userAgent = row[UserSessions.userAgent],
                            ipAddress = row[UserSessions.ipAddress],
                            createdAt = row[UserSessions.createdAt].toString(),
                            lastActiveAt = row[UserSessions.lastActiveAt].toString(),
                            isCurrent = isCurr,
                            deviceId = dId,
                            expiresAt = row[UserSessions.expiresAt]?.toString()
                        )
                    )
                }
            }
            result
        }
    }

    fun cleanDuplicateSessions(userId: UUID): Int {
        val now = Instant.now()
        return transaction {
            var deleted = UserSessions.deleteWhere {
                (UserSessions.userId eq userId) and (UserSessions.expiresAt.isNotNull() and (UserSessions.expiresAt lessEq now))
            }

            val rows = UserSessions.selectAll().where { UserSessions.userId eq userId }
                .orderBy(UserSessions.lastActiveAt to SortOrder.DESC)
                .toList()

            val seenKeys = mutableSetOf<String>()
            val toDelete = mutableListOf<UUID>()

            for (row in rows) {
                val sUuid = row[UserSessions.id].value
                val dId = row[UserSessions.deviceId]?.trim()?.takeIf { it.isNotBlank() }
                val key = when {
                    dId != null -> "id:$dId"
                    else -> "session:$sUuid"
                }
                if (!seenKeys.add(key)) {
                    toDelete.add(sUuid)
                }
            }

            if (toDelete.isNotEmpty()) {
                deleted += UserSessions.deleteWhere { UserSessions.id inList toDelete }
            }
            deleted
        }
    }

    fun revokeSession(userId: UUID, sessionId: UUID): Boolean {
        return transaction {
            // The device's home-screen widget token dies with its login.
            com.obsidianscout.db.WidgetTokens.deleteWhere { com.obsidianscout.db.WidgetTokens.sessionId eq sessionId }
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

    /**
     * A "keep me logged in" sign-in gets a 30-day row, a normal one 12 hours; rows with no expiry never lapse.
     * The row's lifetime is the only server-side record of which one the user picked.
     */
    fun isLongLivedSession(createdAt: Instant, expiresAt: Instant?): Boolean =
        expiresAt == null || java.time.Duration.between(createdAt, expiresAt).toHours() > 48

    /** Pushes the session's expiry forward (at most every 5 minutes). Returns true when it did. */
    fun touchSession(sessionIdStr: String): Boolean {
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return false
        val sessionUuid = runCatching { UUID.fromString(sessionIdStr) }.getOrNull() ?: return false
        val nowMs = System.currentTimeMillis()
        val lastTouch = sessionTouchCache[sessionUuid] ?: 0L
        if (nowMs - lastTouch <= 300_000L) return false // 5 minutes throttle
        sessionTouchCache[sessionUuid] = nowMs
        return try {
            transaction {
                if (com.obsidianscout.db.DatabaseFactory.isPostgresCompatible) {
                    exec("SET LOCAL statement_timeout = '3000ms';")
                }
                val row = UserSessions.selectAll().where { UserSessions.id eq sessionUuid }.firstOrNull()
                if (row != null) {
                    val currentExpires = row[UserSessions.expiresAt]
                    val nowInstant = Instant.ofEpochMilli(nowMs)
                    val newExpires = if (currentExpires != null) {
                        if (isLongLivedSession(row[UserSessions.createdAt], currentExpires)) {
                            nowInstant.plus(30, ChronoUnit.DAYS)
                        } else {
                            nowInstant.plus(12, ChronoUnit.HOURS)
                        }
                    } else null

                    UserSessions.update({ UserSessions.id eq sessionUuid }) {
                        it[lastActiveAt] = nowInstant
                        if (newExpires != null) {
                            it[expiresAt] = newExpires
                        }
                    } > 0
                } else false
            }
        } catch (e: Throwable) {
            if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLossException(e)) {
                com.obsidianscout.db.orchestration.CockroachOrchestrator.markQuorumLost(e.message)
            }
            // Non-critical background update: do not fail user requests during quorum loss or timeout
            false
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

    internal fun rowToUser(row: ResultRow): UserRecord {
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
            nodeAlertsEnabled = row.getOrNull(Users.nodeAlertsEnabled) ?: false,
            bugReportPreference = row.getOrNull(Users.bugReportPreference) ?: "ask",
            localAiEnabled = row.getOrNull(Users.localAiEnabled) ?: false,
            nonScoutingToolsEnabled = row.getOrNull(Users.nonScoutingToolsEnabled) ?: false,
            lastLogin = row.getOrNull(Users.lastLogin)?.toString()
        )
    }

    private val FORBIDDEN_USERNAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '`')

    /**
     * Usernames are used in chat file names and rendered in the UI, so reject path separators,
     * markup characters and control characters. Spaces and other punctuation remain allowed.
     */
    fun validateUsername(username: String) {
        val trimmed = username.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) {
            throw ApiException(HttpStatusCode.BadRequest, "Username must be between 1 and 64 characters")
        }
        if (trimmed.contains("..") || trimmed.any { it in FORBIDDEN_USERNAME_CHARS || it.isISOControl() }) {
            throw ApiException(HttpStatusCode.BadRequest, "Username cannot contain '..' or any of: / \\ : * ? \" < > | `")
        }
    }

    /** The web app uploads 384px JPEGs (tens of KB); this leaves plenty of headroom. */
    const val MAX_PROFILE_PICTURE_CHARS = 1_000_000
    private val PROFILE_PICTURE_PATTERN = Regex("^data:(image/(?:png|jpeg|webp|gif));base64,([A-Za-z0-9+/]+={0,2})$")

    fun validateProfilePicture(picture: String) {
        if (picture.length > MAX_PROFILE_PICTURE_CHARS) {
            throw ApiException(HttpStatusCode.BadRequest, "Profile picture is too large. Please choose a smaller image.")
        }
        if (decodeProfilePicture(picture) == null) {
            throw ApiException(HttpStatusCode.BadRequest, "Profile picture must be a PNG, JPEG, WebP or GIF image")
        }
    }

    /** Returns the content type and bytes of a stored profile picture data URL, or null if it isn't one. */
    fun decodeProfilePicture(picture: String): Pair<String, ByteArray>? {
        val match = PROFILE_PICTURE_PATTERN.matchEntire(picture.trim()) ?: return null
        val bytes = runCatching { java.util.Base64.getDecoder().decode(match.groupValues[2]) }.getOrNull() ?: return null
        return match.groupValues[1] to bytes
    }

    const val MIN_PASSWORD_LENGTH = 8

    /** BCrypt only accepts up to 72 bytes; longer passwords make the hasher throw. */
    const val MAX_PASSWORD_BYTES = 72

    fun validatePassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw ApiException(HttpStatusCode.BadRequest, "Password must be at least $MIN_PASSWORD_LENGTH characters long")
        }
        if (password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) {
            throw ApiException(HttpStatusCode.BadRequest, "Password is too long (maximum $MAX_PASSWORD_BYTES bytes; fewer if it uses emoji or accented characters)")
        }
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
