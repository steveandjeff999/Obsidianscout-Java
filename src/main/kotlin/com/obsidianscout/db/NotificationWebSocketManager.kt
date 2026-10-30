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

    /** userId -> the team and program of a user with a socket open on this node, for team-wide invalidations. */
    private val socketTeams = ConcurrentHashMap<String, Pair<Int, String>>()

    fun registerSession(userId: String, session: DefaultWebSocketSession, teamNumber: Int, program: String) {
        activeSessions.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
        socketTeams[userId] = teamNumber to program.uppercase()
    }

    fun unregisterSession(userId: String, session: DefaultWebSocketSession) {
        val userSessions = activeSessions[userId]
        if (userSessions != null) {
            userSessions.remove(session)
            if (userSessions.isEmpty()) {
                activeSessions.remove(userId)
                socketTeams.remove(userId)
            }
        }
    }

    /*
     * Invalidations tell open pages that data they show changed, so they refetch now instead of polling for it.
     * They carry no data, only topic names (the widget sections: "assignments", "chat", "access", ... plus
     * "banners" and "alliance-selection"), so sending one to someone who doesn't show that data costs nothing.
     * They reach sockets on this node only; pages keep a slow poll for changes made through other nodes.
     */

    private fun invalidationPayload(topics: Collection<String>): String = buildJsonObject {
        put("type", "invalidate")
        put("topics", kotlinx.serialization.json.JsonArray(topics.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }.toString()

    /** Every user of [teamNumber]/[program] with a socket on this node. */
    fun invalidateTeam(teamNumber: Int, program: String, topics: Collection<String>) {
        if (topics.isEmpty()) return
        val prog = program.uppercase()
        val payload = invalidationPayload(topics)
        socketTeams.forEach { (userId, team) -> if (team.first == teamNumber && team.second == prog) sendToUser(userId, payload) }
    }

    /** The whole team of each of [userIds] (a change to one scout's assignments shows on the admins' pages too). */
    fun invalidateTeamsOf(userIds: Collection<String>, topics: Collection<String>) {
        userIds.mapNotNull { socketTeams[it] }.toSet().forEach { (team, program) -> invalidateTeam(team, program, topics) }
    }

    /** Everyone with a socket on this node. */
    fun invalidateAll(topics: Collection<String>) {
        if (topics.isEmpty()) return
        val payload = invalidationPayload(topics)
        activeSessions.keys.forEach { sendToUser(it, payload) }
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

        targetUserIds.forEach { sendToUser(it, jsonPayload) }
    }

    /** Sends a ready-made JSON message to every socket [userId] has open on this node. */
    fun sendToUser(userId: String, jsonPayload: String) {
        val sessions = activeSessions[userId] ?: return
        scope.launch {
            val iterator = sessions.iterator()
            while (iterator.hasNext()) {
                val session = iterator.next()
                try {
                    session.send(Frame.Text(jsonPayload))
                } catch (e: Exception) {
                    iterator.remove()
                }
            }
            if (sessions.isEmpty()) {
                activeSessions.remove(userId)
            }
        }
    }
}
