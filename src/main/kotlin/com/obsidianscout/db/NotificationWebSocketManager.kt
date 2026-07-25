package com.obsidianscout.db

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

object NotificationWebSocketManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Map: userId -> Set<DefaultWebSocketSession>
    private val activeSessions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketSession>>()

    fun registerSession(userId: String, session: DefaultWebSocketSession) {
        activeSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun unregisterSession(userId: String, session: DefaultWebSocketSession) {
        val userSessions = activeSessions[userId]
        if (userSessions != null) {
            userSessions.remove(session)
            if (userSessions.isEmpty()) {
                activeSessions.remove(userId)
            }
        }
    }

    fun broadcastChatNotification(
        targetUserIds: List<String>,
        groupName: String,
        title: String,
        body: String,
        senderUsername: String
    ) {
        val jsonPayload = buildJsonObject {
            put("type", "chat_notification")
            put("groupName", groupName)
            put("title", title)
            put("body", body)
            put("sender", senderUsername)
            put("url", "/chat?group=$groupName")
        }.toString()

        scope.launch {
            for (userId in targetUserIds) {
                val sessions = activeSessions[userId] ?: continue
                for (session in sessions) {
                    try {
                        session.send(Frame.Text(jsonPayload))
                    } catch (e: Exception) {
                        // Session disconnected or error
                    }
                }
            }
        }
    }
}
