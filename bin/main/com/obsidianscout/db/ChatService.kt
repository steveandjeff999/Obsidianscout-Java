package com.obsidianscout.db

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.routes.ChatMessageDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.innerJoin
import java.time.Instant

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
}
