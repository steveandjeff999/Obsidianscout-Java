package com.obsidianscout.db

import com.obsidianscout.auth.UserRole
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.routes.ChatMessageDto
import com.obsidianscout.routes.ChatGroupDetailsDto
import com.obsidianscout.routes.UnreadStatusDto
import com.obsidianscout.routes.GroupUnreadStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.innerJoin
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.dao.id.EntityID

object ChatService {

    fun canUserAccessGroup(allowedRolesJson: String, allowedUserIdsJson: String, userId: String, userRole: UserRole): Boolean {
        if (userRole == UserRole.SUPERADMIN) {
            return true
        }
        val allowedRoles: List<String> = try {
            JsonSupport.json.decodeFromString(allowedRolesJson)
        } catch (_: Exception) {
            emptyList()
        }
        val allowedUserIds: List<String> = try {
            JsonSupport.json.decodeFromString(allowedUserIdsJson)
        } catch (_: Exception) {
            emptyList()
        }

        // If no roles or users are specified, the channel is public to all team members
        if (allowedRoles.isEmpty() && allowedUserIds.isEmpty()) {
            return true
        }

        if (allowedRoles.contains(userRole.name)) {
            return true
        }

        if (allowedUserIds.contains(userId)) {
            return true
        }

        return false
    }

    private fun ensureDefaultGroup(teamNumber: Int) {
        val existing = ChatGroups.selectAll().where { ChatGroups.teamNumber eq teamNumber }.firstOrNull()
        if (existing == null) {
            val messageGroups = ChatMessages.select(ChatMessages.groupName)
                .where { ChatMessages.teamNumber eq teamNumber }
                .withDistinct()
                .map { it[ChatMessages.groupName] }

            if (messageGroups.isNotEmpty()) {
                messageGroups.forEach { grp ->
                    ChatGroups.insert {
                        it[ChatGroups.teamNumber] = teamNumber
                        it[ChatGroups.groupName] = grp
                        it[ChatGroups.createdAt] = Instant.now()
                        it[ChatGroups.allowedRoles] = "[]"
                        it[ChatGroups.allowedUserIds] = "[]"
                    }
                }
            } else {
                ChatGroups.insert {
                    it[ChatGroups.teamNumber] = teamNumber
                    it[ChatGroups.groupName] = "general"
                    it[ChatGroups.createdAt] = Instant.now()
                    it[ChatGroups.allowedRoles] = "[]"
                    it[ChatGroups.allowedUserIds] = "[]"
                }
            }
        }
    }

    fun getMessages(teamNumber: Int, groupName: String, userId: String, userRole: UserRole, limit: Int = 200): List<ChatMessageDto> = transaction {
        ensureDefaultGroup(teamNumber)
        val sanitized = groupName.lowercase().replace(Regex("[^a-z0-9_-]"), "").trim().ifEmpty { "general" }
        val groupRow = ChatGroups.selectAll().where {
            (ChatGroups.teamNumber eq teamNumber) and (ChatGroups.groupName eq sanitized)
        }.firstOrNull()

        if (groupRow != null) {
            val allowedRolesJson = groupRow[ChatGroups.allowedRoles]
            val allowedUserIdsJson = groupRow[ChatGroups.allowedUserIds]
            if (!canUserAccessGroup(allowedRolesJson, allowedUserIdsJson, userId, userRole)) {
                throw IllegalArgumentException("You do not have permission to view channel #$sanitized")
            }
        }

        (ChatMessages innerJoin Users)
            .select(
                ChatMessages.id,
                ChatMessages.teamNumber,
                ChatMessages.groupName,
                ChatMessages.userId,
                ChatMessages.username,
                ChatMessages.content,
                ChatMessages.createdAt,
                ChatMessages.reactionsJson,
                ChatMessages.isEdited,
                ChatMessages.updatedAt,
                Users.profilePicture
            )
            .where { (ChatMessages.teamNumber eq teamNumber) and (ChatMessages.groupName eq sanitized) }
            .orderBy(ChatMessages.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { row ->
                val reactionsJsonStr = row[ChatMessages.reactionsJson]
                val parsedReactions: Map<String, List<String>> = try {
                    JsonSupport.json.decodeFromString(reactionsJsonStr)
                } catch (e: Exception) {
                    emptyMap()
                }

                ChatMessageDto(
                    id = row[ChatMessages.id].value.toString(),
                    teamNumber = row[ChatMessages.teamNumber],
                    groupName = row[ChatMessages.groupName],
                    userId = row[ChatMessages.userId].value.toString(),
                    username = row[ChatMessages.username],
                    content = row[ChatMessages.content],
                    createdAt = row[ChatMessages.createdAt].toString(),
                    reactions = parsedReactions,
                    profilePicture = row[Users.profilePicture],
                    isEdited = row[ChatMessages.isEdited],
                    updatedAt = row[ChatMessages.updatedAt]?.toString()
                )
            }
            .reversed()
    }

    fun getGroups(teamNumber: Int, userId: String, userRole: UserRole): List<String> = transaction {
        ensureDefaultGroup(teamNumber)
        val allGroups = ChatGroups.selectAll()
            .where { ChatGroups.teamNumber eq teamNumber }
            .mapNotNull { row ->
                val groupName = row[ChatGroups.groupName]
                val allowedRolesJson = row[ChatGroups.allowedRoles]
                val allowedUserIdsJson = row[ChatGroups.allowedUserIds]
                if (canUserAccessGroup(allowedRolesJson, allowedUserIdsJson, userId, userRole)) {
                    groupName
                } else null
            }

        if (allGroups.isEmpty()) {
            listOf("general")
        } else {
            allGroups.distinct().sorted()
        }
    }

    fun getAllGroupDetails(teamNumber: Int, userId: String, userRole: UserRole): List<ChatGroupDetailsDto> = transaction {
        ensureDefaultGroup(teamNumber)
        val allGroups = ChatGroups.selectAll().where { ChatGroups.teamNumber eq teamNumber }
        val isAdmin = userRole == UserRole.ADMIN || userRole == UserRole.SUPERADMIN

        allGroups.mapNotNull { row ->
            val groupName = row[ChatGroups.groupName]
            val allowedRolesJson = row[ChatGroups.allowedRoles]
            val allowedUserIdsJson = row[ChatGroups.allowedUserIds]

            if (isAdmin || canUserAccessGroup(allowedRolesJson, allowedUserIdsJson, userId, userRole)) {
                val roles: List<String> = try { JsonSupport.json.decodeFromString(allowedRolesJson) } catch (_: Exception) { emptyList() }
                val userIds: List<String> = try { JsonSupport.json.decodeFromString(allowedUserIdsJson) } catch (_: Exception) { emptyList() }

                ChatGroupDetailsDto(
                    groupName = groupName,
                    isDefault = (groupName == "general"),
                    allowedRoles = roles,
                    allowedUserIds = userIds,
                    createdByUserId = row[ChatGroups.createdByUserId]?.value?.toString(),
                    createdAt = row[ChatGroups.createdAt].toString()
                )
            } else null
        }.sortedBy { it.groupName }
    }

    fun getGroupDetails(teamNumber: Int, groupName: String, userId: String, userRole: UserRole): ChatGroupDetailsDto? = transaction {
        ensureDefaultGroup(teamNumber)
        val sanitized = groupName.lowercase().replace(Regex("[^a-z0-9_-]"), "").trim()
        val row = ChatGroups.selectAll().where {
            (ChatGroups.teamNumber eq teamNumber) and (ChatGroups.groupName eq sanitized)
        }.firstOrNull() ?: return@transaction null

        val allowedRolesJson = row[ChatGroups.allowedRoles]
        val allowedUserIdsJson = row[ChatGroups.allowedUserIds]
        val isAdmin = userRole == UserRole.ADMIN || userRole == UserRole.SUPERADMIN

        if (!isAdmin && !canUserAccessGroup(allowedRolesJson, allowedUserIdsJson, userId, userRole)) {
            throw IllegalArgumentException("You do not have permission to view channel #$sanitized")
        }

        val roles: List<String> = try { JsonSupport.json.decodeFromString(allowedRolesJson) } catch (_: Exception) { emptyList() }
        val userIds: List<String> = try { JsonSupport.json.decodeFromString(allowedUserIdsJson) } catch (_: Exception) { emptyList() }

        ChatGroupDetailsDto(
            groupName = row[ChatGroups.groupName],
            isDefault = (row[ChatGroups.groupName] == "general"),
            allowedRoles = roles,
            allowedUserIds = userIds,
            createdByUserId = row[ChatGroups.createdByUserId]?.value?.toString(),
            createdAt = row[ChatGroups.createdAt].toString()
        )
    }

    fun createGroup(
        teamNumber: Int,
        groupName: String,
        userId: String? = null,
        allowedRoles: List<String> = emptyList(),
        allowedUserIds: List<String> = emptyList()
    ): Boolean = transaction {
        val sanitized = groupName.lowercase().replace(Regex("[^a-z0-9_-]"), "").trim()
        if (sanitized.isEmpty()) return@transaction false
        val userUuid = userId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        val hasAdminRole = allowedRoles.contains("ADMIN") || allowedRoles.contains("SUPERADMIN")
        val hasAdminUser = if (!hasAdminRole && allowedUserIds.isNotEmpty()) {
            val userUuids = allowedUserIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            if (userUuids.isNotEmpty()) {
                Users.selectAll().where {
                    (Users.teamNumber eq teamNumber) and
                    (Users.id inList userUuids) and
                    ((Users.role eq UserRole.ADMIN.name) or (Users.role eq UserRole.SUPERADMIN.name))
                }.count() > 0
            } else false
        } else false

        if ((allowedRoles.isNotEmpty() || allowedUserIds.isNotEmpty()) && !hasAdminRole && !hasAdminUser) {
            throw IllegalArgumentException("A channel must include either the Admin role or at least one Admin team member.")
        }

        val existing = ChatGroups.selectAll().where {
            (ChatGroups.teamNumber eq teamNumber) and (ChatGroups.groupName eq sanitized)
        }.firstOrNull()

        val rolesJson = JsonSupport.json.encodeToString(allowedRoles)
        val usersJson = JsonSupport.json.encodeToString(allowedUserIds)

        if (existing == null) {
            ChatGroups.insert {
                it[ChatGroups.teamNumber] = teamNumber
                it[ChatGroups.groupName] = sanitized
                it[ChatGroups.createdByUserId] = userUuid?.let { u -> EntityID(u, Users) }
                it[ChatGroups.createdAt] = Instant.now()
                it[ChatGroups.allowedRoles] = rolesJson
                it[ChatGroups.allowedUserIds] = usersJson
            }
        }
        true
    }

    fun updateGroupPermissions(
        teamNumber: Int,
        groupName: String,
        allowedRoles: List<String>,
        allowedUserIds: List<String>,
        userRole: UserRole
    ): Boolean = transaction {
        val isAdmin = userRole == UserRole.ADMIN || userRole == UserRole.SUPERADMIN
        if (!isAdmin) {
            throw IllegalArgumentException("Only administrators can update channel permissions")
        }

        val sanitized = groupName.lowercase().replace(Regex("[^a-z0-9_-]"), "").trim()
        if (sanitized.isEmpty()) return@transaction false

        ensureDefaultGroup(teamNumber)

        val hasAdminRole = allowedRoles.contains("ADMIN") || allowedRoles.contains("SUPERADMIN")
        val hasAdminUser = if (!hasAdminRole && allowedUserIds.isNotEmpty()) {
            val userUuids = allowedUserIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            if (userUuids.isNotEmpty()) {
                Users.selectAll().where {
                    (Users.teamNumber eq teamNumber) and
                    (Users.id inList userUuids) and
                    ((Users.role eq UserRole.ADMIN.name) or (Users.role eq UserRole.SUPERADMIN.name))
                }.count() > 0
            } else false
        } else false

        if ((allowedRoles.isNotEmpty() || allowedUserIds.isNotEmpty()) && !hasAdminRole && !hasAdminUser) {
            throw IllegalArgumentException("A channel must include either the Admin role or at least one Admin team member.")
        }

        val rolesJson = JsonSupport.json.encodeToString(allowedRoles)
        val usersJson = JsonSupport.json.encodeToString(allowedUserIds)

        val updated = ChatGroups.update({ (ChatGroups.teamNumber eq teamNumber) and (ChatGroups.groupName eq sanitized) }) {
            it[ChatGroups.allowedRoles] = rolesJson
            it[ChatGroups.allowedUserIds] = usersJson
        }

        if (updated == 0) {
            ChatGroups.insert {
                it[ChatGroups.teamNumber] = teamNumber
                it[ChatGroups.groupName] = sanitized
                it[ChatGroups.createdAt] = Instant.now()
                it[ChatGroups.allowedRoles] = rolesJson
                it[ChatGroups.allowedUserIds] = usersJson
            }
        }
        true
    }

    fun clearGroupMessages(teamNumber: Int, groupName: String, userRole: UserRole): Boolean = transaction {
        val isAdmin = userRole == UserRole.ADMIN || userRole == UserRole.SUPERADMIN
        if (!isAdmin) {
            throw IllegalArgumentException("Only administrators can clear channel messages")
        }

        val sanitized = groupName.lowercase().replace(Regex("[^a-z0-9_-]"), "").trim()
        if (sanitized.isEmpty()) return@transaction false

        ChatMessages.deleteWhere {
            (ChatMessages.teamNumber eq teamNumber) and (ChatMessages.groupName eq sanitized)
        }

        UserChatLastRead.deleteWhere {
            UserChatLastRead.groupName eq sanitized
        }

        true
    }

    fun deleteGroup(teamNumber: Int, groupName: String, userRole: UserRole): Boolean = transaction {
        val sanitized = groupName.lowercase().replace(Regex("[^a-z0-9_-]"), "").trim()
        if (sanitized.isEmpty()) {
            throw IllegalArgumentException("Invalid channel name")
        }

        val isAdmin = userRole == UserRole.ADMIN || userRole == UserRole.SUPERADMIN
        if (!isAdmin) {
            throw IllegalArgumentException("Only administrators can delete channels")
        }

        ensureDefaultGroup(teamNumber)

        val existingGroups = ChatGroups.selectAll().where { ChatGroups.teamNumber eq teamNumber }.map { it[ChatGroups.groupName] }
        if (existingGroups.size <= 1) {
            throw IllegalArgumentException("Cannot delete the only remaining channel. At least one channel must exist.")
        }

        ChatGroups.deleteWhere {
            (ChatGroups.teamNumber eq teamNumber) and (ChatGroups.groupName eq sanitized)
        }

        ChatMessages.deleteWhere {
            (ChatMessages.teamNumber eq teamNumber) and (ChatMessages.groupName eq sanitized)
        }

        UserChatLastRead.deleteWhere {
            UserChatLastRead.groupName eq sanitized
        }

        true
    }

    fun sendMessage(
        teamNumber: Int,
        groupName: String,
        userId: String,
        username: String,
        content: String,
        userRole: UserRole
    ): ChatMessageDto = transaction {
        val userUuid = UUID.fromString(userId)
        val sanitizedGroup = groupName.lowercase().replace(Regex("[^a-z0-9_-]"), "").trim().ifEmpty { "general" }

        ensureDefaultGroup(teamNumber)

        val groupRow = ChatGroups.selectAll().where {
            (ChatGroups.teamNumber eq teamNumber) and (ChatGroups.groupName eq sanitizedGroup)
        }.firstOrNull()

        if (groupRow != null) {
            val allowedRolesJson = groupRow[ChatGroups.allowedRoles]
            val allowedUserIdsJson = groupRow[ChatGroups.allowedUserIds]
            if (!canUserAccessGroup(allowedRolesJson, allowedUserIdsJson, userId, userRole)) {
                throw IllegalArgumentException("You do not have permission to post in channel #$sanitizedGroup")
            }
        } else {
            createGroup(teamNumber, sanitizedGroup, userId)
        }

        val id = ChatMessages.insertAndGetId {
            it[ChatMessages.teamNumber] = teamNumber
            it[ChatMessages.groupName] = sanitizedGroup
            it[ChatMessages.userId] = EntityID(userUuid, Users)
            it[ChatMessages.username] = username
            it[ChatMessages.content] = content
            it[ChatMessages.createdAt] = Instant.now()
            it[ChatMessages.reactionsJson] = "{}"
            it[ChatMessages.isEdited] = false
            it[ChatMessages.updatedAt] = null
        }
        val user = Users.select(Users.profilePicture).where { Users.id eq userUuid }.firstOrNull()
        val profilePic = user?.get(Users.profilePicture)

        val row = ChatMessages.selectAll().where { ChatMessages.id eq id }.first()
        val reactionsJsonStr = row[ChatMessages.reactionsJson]
        val parsedReactions: Map<String, List<String>> = try {
            JsonSupport.json.decodeFromString(reactionsJsonStr)
        } catch (e: Exception) {
            emptyMap()
        }

        ChatMessageDto(
            id = row[ChatMessages.id].value.toString(),
            teamNumber = row[ChatMessages.teamNumber],
            groupName = row[ChatMessages.groupName],
            userId = row[ChatMessages.userId].value.toString(),
            username = row[ChatMessages.username],
            content = row[ChatMessages.content],
            createdAt = row[ChatMessages.createdAt].toString(),
            reactions = parsedReactions,
            profilePicture = profilePic,
            isEdited = row[ChatMessages.isEdited],
            updatedAt = row[ChatMessages.updatedAt]?.toString()
        )
    }

    fun editMessage(id: String, userId: String, teamNumber: Int, newContent: String): ChatMessageDto? = transaction {
        val msgUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction null
        val userUuid = runCatching { UUID.fromString(userId) }.getOrNull() ?: return@transaction null

        val row = ChatMessages.selectAll().where {
            (ChatMessages.id eq msgUuid) and (ChatMessages.teamNumber eq teamNumber)
        }.firstOrNull() ?: return@transaction null

        if (row[ChatMessages.userId].value != userUuid) {
            throw IllegalArgumentException("You can only edit your own messages")
        }

        val now = Instant.now()
        ChatMessages.update({ ChatMessages.id eq msgUuid }) {
            it[content] = newContent
            it[isEdited] = true
            it[updatedAt] = now
        }

        (ChatMessages innerJoin Users)
            .select(
                ChatMessages.id,
                ChatMessages.teamNumber,
                ChatMessages.groupName,
                ChatMessages.userId,
                ChatMessages.username,
                ChatMessages.content,
                ChatMessages.createdAt,
                ChatMessages.reactionsJson,
                ChatMessages.isEdited,
                ChatMessages.updatedAt,
                Users.profilePicture
            )
            .where { ChatMessages.id eq msgUuid }
            .firstOrNull()?.let { r ->
                val reactionsJsonStr = r[ChatMessages.reactionsJson]
                val parsedReactions: Map<String, List<String>> = try {
                    JsonSupport.json.decodeFromString(reactionsJsonStr)
                } catch (e: Exception) {
                    emptyMap()
                }

                ChatMessageDto(
                    id = r[ChatMessages.id].value.toString(),
                    teamNumber = r[ChatMessages.teamNumber],
                    groupName = r[ChatMessages.groupName],
                    userId = r[ChatMessages.userId].value.toString(),
                    username = r[ChatMessages.username],
                    content = r[ChatMessages.content],
                    createdAt = r[ChatMessages.createdAt].toString(),
                    reactions = parsedReactions,
                    profilePicture = r[Users.profilePicture],
                    isEdited = r[ChatMessages.isEdited],
                    updatedAt = r[ChatMessages.updatedAt]?.toString()
                )
            }
    }

    fun deleteMessage(id: String, userId: String, userRole: UserRole, teamNumber: Int): Boolean = transaction {
        val msgUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction false
        val userUuid = runCatching { UUID.fromString(userId) }.getOrNull() ?: return@transaction false

        val row = ChatMessages.selectAll().where {
            (ChatMessages.id eq msgUuid) and (ChatMessages.teamNumber eq teamNumber)
        }.firstOrNull() ?: return@transaction false

        val isOwner = row[ChatMessages.userId].value == userUuid
        val isAdmin = userRole == UserRole.ADMIN || userRole == UserRole.SUPERADMIN

        if (!isOwner && !isAdmin) {
            throw IllegalArgumentException("You do not have permission to delete this message")
        }

        ChatMessages.deleteWhere { ChatMessages.id eq msgUuid } > 0
    }

    fun toggleReaction(id: String, username: String, emoji: String): ChatMessageDto? = transaction {
        val msgUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction null
        val row = ChatMessages.selectAll().where { ChatMessages.id eq msgUuid }.firstOrNull() ?: return@transaction null
        if (row[ChatMessages.username] == username) {
            throw IllegalArgumentException("Cannot react to your own message")
        }
        val currentReactionsJson = row[ChatMessages.reactionsJson]
        val currentReactions: Map<String, List<String>> = try {
            JsonSupport.json.decodeFromString(currentReactionsJson)
        } catch (e: Exception) {
            emptyMap()
        }

        val updatedReactions = currentReactions.toMutableMap()
        val list = updatedReactions[emoji]?.toMutableList() ?: mutableListOf()

        if (list.contains(username)) {
            list.remove(username)
        } else {
            list.add(username)
        }

        if (list.isEmpty()) {
            updatedReactions.remove(emoji)
        } else {
            updatedReactions[emoji] = list
        }

        val newReactionsJson = JsonSupport.json.encodeToString(updatedReactions)

        ChatMessages.update({ ChatMessages.id eq msgUuid }) {
            it[reactionsJson] = newReactionsJson
        }

        (ChatMessages innerJoin Users)
            .select(
                ChatMessages.id,
                ChatMessages.teamNumber,
                ChatMessages.groupName,
                ChatMessages.userId,
                ChatMessages.username,
                ChatMessages.content,
                ChatMessages.createdAt,
                ChatMessages.reactionsJson,
                ChatMessages.isEdited,
                ChatMessages.updatedAt,
                Users.profilePicture
            )
            .where { ChatMessages.id eq msgUuid }
            .firstOrNull()?.let { r ->
                val reactionsJsonStr = r[ChatMessages.reactionsJson]
                val parsedReactions: Map<String, List<String>> = try {
                    JsonSupport.json.decodeFromString(reactionsJsonStr)
                } catch (e: Exception) {
                    emptyMap()
                }

                ChatMessageDto(
                    id = r[ChatMessages.id].value.toString(),
                    teamNumber = r[ChatMessages.teamNumber],
                    groupName = r[ChatMessages.groupName],
                    userId = r[ChatMessages.userId].value.toString(),
                    username = r[ChatMessages.username],
                    content = r[ChatMessages.content],
                    createdAt = r[ChatMessages.createdAt].toString(),
                    reactions = parsedReactions,
                    profilePicture = r[Users.profilePicture],
                    isEdited = r[ChatMessages.isEdited],
                    updatedAt = r[ChatMessages.updatedAt]?.toString()
                )
            }
    }

    fun updateLastRead(userId: String, groupName: String) = transaction {
        val userUuid = UUID.fromString(userId)
        val existing = UserChatLastRead.selectAll()
            .where { (UserChatLastRead.userId eq userUuid) and (UserChatLastRead.groupName eq groupName) }
            .firstOrNull()
        if (existing != null) {
            UserChatLastRead.update({ (UserChatLastRead.userId eq userUuid) and (UserChatLastRead.groupName eq groupName) }) {
                it[lastReadAt] = Instant.now()
            }
        } else {
            UserChatLastRead.insert {
                it[UserChatLastRead.userId] = EntityID(userUuid, Users)
                it[UserChatLastRead.groupName] = groupName
                it[lastReadAt] = Instant.now()
            }
        }
    }

    fun getUnreadStatus(userId: String, teamNumber: Int, username: String, userRole: UserRole): UnreadStatusDto = transaction {
        val userUuid = UUID.fromString(userId)
        val lastReads = UserChatLastRead.selectAll()
            .where { UserChatLastRead.userId eq userUuid }
            .associate { it[UserChatLastRead.groupName] to it[UserChatLastRead.lastReadAt] }

        val groups = getGroups(teamNumber, userId, userRole)
        val userMentionLower = "@${username.lowercase()}"
        val sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS)

        var totalUnreadCount = 0
        var totalMentionCount = 0

        val groupStatuses = groups.map { groupName ->
            val lastRead = lastReads[groupName] ?: sevenDaysAgo
            val groupUnreadCount = ChatMessages.selectAll().where {
                (ChatMessages.teamNumber eq teamNumber) and
                (ChatMessages.groupName eq groupName) and
                (ChatMessages.userId neq userUuid) and
                (ChatMessages.createdAt greater lastRead)
            }.count().toInt()

            var groupMentionCount = 0
            if (groupUnreadCount > 0) {
                val unreadContents = ChatMessages.select(ChatMessages.content).where {
                    (ChatMessages.teamNumber eq teamNumber) and
                    (ChatMessages.groupName eq groupName) and
                    (ChatMessages.userId neq userUuid) and
                    (ChatMessages.createdAt greater lastRead)
                }.map { it[ChatMessages.content].lowercase() }

                for (contentLower in unreadContents) {
                    if (contentLower.contains(userMentionLower) ||
                        contentLower.contains("@everyone") ||
                        contentLower.contains("@channel")) {
                        groupMentionCount++
                    }
                }
            }

            totalUnreadCount += groupUnreadCount
            totalMentionCount += groupMentionCount
            GroupUnreadStatus(groupName, groupUnreadCount, groupMentionCount)
        }

        UnreadStatusDto(totalUnreadCount, totalMentionCount, groupStatuses)
    }
}
