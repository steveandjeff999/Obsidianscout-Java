package com.obsidianscout.db

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.routes.ChatMessageDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.innerJoin
import java.time.Instant
import com.obsidianscout.routes.UnreadStatusDto
import com.obsidianscout.routes.GroupUnreadStatus


object ChatService {

    fun getMessages(teamNumber: Int, groupName: String): List<ChatMessageDto> = transaction {
        (ChatMessages innerJoin Users).selectAll()
            .where { (ChatMessages.teamNumber eq teamNumber) and (ChatMessages.groupName eq groupName) }
            .orderBy(ChatMessages.createdAt to SortOrder.ASC)
            .map { row ->
                val reactionsJsonStr = row[ChatMessages.reactionsJson]
                val parsedReactions: Map<String, List<String>> = try {
                    JsonSupport.json.decodeFromString(reactionsJsonStr)
                } catch (e: Exception) {
                    emptyMap()
                }

                ChatMessageDto(
                    id = row[ChatMessages.id].value,
                    teamNumber = row[ChatMessages.teamNumber],
                    groupName = row[ChatMessages.groupName],
                    userId = row[ChatMessages.userId].value,
                    username = row[ChatMessages.username],
                    content = row[ChatMessages.content],
                    createdAt = row[ChatMessages.createdAt].toString(),
                    reactions = parsedReactions,
                    profilePicture = row[Users.profilePicture]
                )
            }
    }

    fun getGroups(teamNumber: Int): List<String> = transaction {
        ChatMessages.selectAll()
            .where { ChatMessages.teamNumber eq teamNumber }
            .map { it[ChatMessages.groupName] }
            .distinct()
            .sorted()
    }

    fun sendMessage(teamNumber: Int, groupName: String, userId: Int, username: String, content: String): ChatMessageDto = transaction {
        val id = ChatMessages.insertAndGetId {
            it[ChatMessages.teamNumber] = teamNumber
            it[ChatMessages.groupName] = groupName
            it[ChatMessages.userId] = userId
            it[ChatMessages.username] = username
            it[ChatMessages.content] = content
            it[ChatMessages.createdAt] = Instant.now()
            it[ChatMessages.reactionsJson] = "{}"
        }
        val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
        val profilePic = user?.get(Users.profilePicture)

        val row = ChatMessages.selectAll().where { ChatMessages.id eq id }.first()
        val reactionsJsonStr = row[ChatMessages.reactionsJson]
        val parsedReactions: Map<String, List<String>> = try {
            JsonSupport.json.decodeFromString(reactionsJsonStr)
        } catch (e: Exception) {
            emptyMap()
        }

        ChatMessageDto(
            id = row[ChatMessages.id].value,
            teamNumber = row[ChatMessages.teamNumber],
            groupName = row[ChatMessages.groupName],
            userId = row[ChatMessages.userId].value,
            username = row[ChatMessages.username],
            content = row[ChatMessages.content],
            createdAt = row[ChatMessages.createdAt].toString(),
            reactions = parsedReactions,
            profilePicture = profilePic
        )
    }

    fun toggleReaction(id: Int, username: String, emoji: String): ChatMessageDto? = transaction {
        val row = ChatMessages.selectAll().where { ChatMessages.id eq id }.firstOrNull() ?: return@transaction null
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

        ChatMessages.update({ ChatMessages.id eq id }) {
            it[reactionsJson] = newReactionsJson
        }

        (ChatMessages innerJoin Users).selectAll().where { ChatMessages.id eq id }.firstOrNull()?.let { r ->
            val reactionsJsonStr = r[ChatMessages.reactionsJson]
            val parsedReactions: Map<String, List<String>> = try {
                JsonSupport.json.decodeFromString(reactionsJsonStr)
            } catch (e: Exception) {
                emptyMap()
            }

            ChatMessageDto(
                id = r[ChatMessages.id].value,
                teamNumber = r[ChatMessages.teamNumber],
                groupName = r[ChatMessages.groupName],
                userId = r[ChatMessages.userId].value,
                username = r[ChatMessages.username],
                content = r[ChatMessages.content],
                createdAt = r[ChatMessages.createdAt].toString(),
                reactions = parsedReactions,
                profilePicture = r[Users.profilePicture]
            )
        }
    }

    fun updateLastRead(userId: Int, groupName: String) = transaction {
        val existing = UserChatLastRead.selectAll()
            .where { (UserChatLastRead.userId eq userId) and (UserChatLastRead.groupName eq groupName) }
            .firstOrNull()
        if (existing != null) {
            UserChatLastRead.update({ (UserChatLastRead.userId eq userId) and (UserChatLastRead.groupName eq groupName) }) {
                it[lastReadAt] = Instant.now()
            }
        } else {
            UserChatLastRead.insert {
                it[UserChatLastRead.userId] = userId
                it[UserChatLastRead.groupName] = groupName
                it[lastReadAt] = Instant.now()
            }
        }
    }

    fun getUnreadStatus(userId: Int, teamNumber: Int, username: String): UnreadStatusDto = transaction {
        val lastReads = UserChatLastRead.selectAll()
            .where { UserChatLastRead.userId eq userId }
            .associate { it[UserChatLastRead.groupName] to it[UserChatLastRead.lastReadAt] }

        val dbGroups = ChatMessages.select(ChatMessages.groupName)
            .where { ChatMessages.teamNumber eq teamNumber }
            .withDistinct()
            .map { it[ChatMessages.groupName] }
        val groups = (dbGroups + "general").distinct()

        var totalUnreadCount = 0
        var totalMentionCount = 0

        val userMention = "@$username"
        val userMentionLower = "%${userMention.lowercase()}%"

        val groupStatuses = groups.map { groupName ->
            val lastRead = lastReads[groupName] ?: Instant.EPOCH

            val unreadCount = ChatMessages.selectAll().where {
                (ChatMessages.teamNumber eq teamNumber) and
                (ChatMessages.groupName eq groupName) and
                (ChatMessages.userId neq userId) and
                (ChatMessages.createdAt greater lastRead)
            }.count().toInt()

            val mentionCount = ChatMessages.selectAll().where {
                (ChatMessages.teamNumber eq teamNumber) and
                (ChatMessages.groupName eq groupName) and
                (ChatMessages.userId neq userId) and
                (ChatMessages.createdAt greater lastRead) and
                ((ChatMessages.content.lowerCase() like userMentionLower) or
                 (ChatMessages.content.lowerCase() like "%@everyone%") or
                 (ChatMessages.content.lowerCase() like "%@channel%"))
            }.count().toInt()

            totalUnreadCount += unreadCount
            totalMentionCount += mentionCount

            GroupUnreadStatus(groupName, unreadCount, mentionCount)
        }

        UnreadStatusDto(totalUnreadCount, totalMentionCount, groupStatuses)
    }
}
