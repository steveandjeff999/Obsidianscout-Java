package com.obsidianscout.db

import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
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
        com.obsidianscout.admin.ServerAnalyticsService.recordWebSocketConnect()
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
        com.obsidianscout.admin.ServerAnalyticsService.recordWebSocketDisconnect()
    }

    /*
     * Invalidations tell open pages that data they show changed, so they refetch now instead of polling for it.
     * They carry no data, only topic names (the widget sections: "assignments", "chat", "access", ... plus
     * "banners", "alliance-selection", "scouting", "pit-scouting" and "qual-scouting"), so sending one to someone who
     * doesn't show that data costs nothing. The invalidate functions reach sockets on every node: this node's straight
     * away and the others through [LiveInvalidationRelay]. The deliverTo functions are this node's half of that.
     */

    private fun invalidationPayload(topics: Collection<String>, eventKey: String? = null): String = buildJsonObject {
        put("type", "invalidate")
        put("topics", kotlinx.serialization.json.JsonArray(topics.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        if (eventKey != null) put("eventKey", eventKey)
    }.toString()

    /** Every user of [teamNumber]/[program] with a socket. */
    fun invalidateTeam(teamNumber: Int, program: String, topics: Collection<String>) {
        if (topics.isEmpty()) return
        val prog = program.uppercase()
        deliverToTeam(teamNumber, prog, topics)
        LiveInvalidationRelay.publish(LiveInvalidationRelay.Target(teamNumber = teamNumber, program = prog), topics)
    }

    /** The team and the teams sharing its data through a scouting alliance: for entries and what's worked out from them. */
    fun invalidateTeamData(teamNumber: Int, program: String, topics: Collection<String>) {
        invalidateTeam(teamNumber, program, topics)
        runCatching { com.obsidianscout.scouting.AllianceService.getAlliancePartnerTeams(teamNumber, program) }
            .getOrDefault(emptySet())
            .forEach { invalidateTeam(it, program, topics) }
    }

    /**
     * The whole team of each of [userIds] (a change to one scout's assignments shows on the admins' pages too). Teams
     * come from the database, because the scout in question is often offline with no socket to tell us theirs.
     */
    fun invalidateTeamsOf(userIds: Collection<String>, topics: Collection<String>) {
        if (topics.isEmpty()) return
        val connected = userIds.mapNotNull { socketTeams[it] }
        (connected + teamsOfUsers(userIds)).toSet().forEach { (team, program) -> invalidateTeam(team, program, topics) }
    }

    private fun teamsOfUsers(userIds: Collection<String>): List<Pair<Int, String>> {
        val ids = userIds.mapNotNull { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
        if (ids.isEmpty()) return emptyList()
        return runCatching {
            readTransaction {
                Users.select(Users.teamNumber, Users.program).where { Users.id inList ids.distinct() }
                    .map { it[Users.teamNumber] to it[Users.program].uppercase() }
            }
        }.getOrDefault(emptyList())
    }

    /** Everyone with a socket. */
    fun invalidateAll(topics: Collection<String>) {
        if (topics.isEmpty()) return
        deliverToAll(topics)
        LiveInvalidationRelay.publish(LiveInvalidationRelay.Target(), topics)
    }

    /** Shared event data changed; goes to everyone and pages not showing [eventKey] drop it without a request. */
    fun invalidateEvent(eventKey: String, topics: Collection<String>) {
        if (eventKey.isBlank() || topics.isEmpty()) return
        deliverToAll(topics, eventKey)
        LiveInvalidationRelay.publish(LiveInvalidationRelay.Target(eventKey = eventKey), topics)
    }

    internal fun deliverToTeam(teamNumber: Int, program: String, topics: Collection<String>) {
        val payload = invalidationPayload(topics)
        var reached = 0
        socketTeams.forEach { (userId, team) ->
            if (team.first == teamNumber && team.second == program) {
                sendToUser(userId, payload)
                reached++
            }
        }
        com.obsidianscout.admin.LoadProfiler.recordInvalidation(topics, reached)
        com.obsidianscout.admin.ServerAnalyticsService.recordTopicBroadcast()
    }

    internal fun deliverToAll(topics: Collection<String>, eventKey: String? = null) {
        val payload = invalidationPayload(topics, eventKey)
        activeSessions.keys.forEach { sendToUser(it, payload) }
        com.obsidianscout.admin.LoadProfiler.recordInvalidation(topics, activeSessions.size)
        com.obsidianscout.admin.ServerAnalyticsService.recordTopicBroadcast()
    }

    /** Users with a socket open on this node. */
    fun connectedUsers(): Int = activeSessions.size

    fun connectedSocketsCount(): Int = activeSessions.values.sumOf { it.size }

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
                    com.obsidianscout.admin.ServerAnalyticsService.recordDroppedBroadcast()
                }
            }
            if (sessions.isEmpty()) {
                activeSessions.remove(userId)
            }
        }
    }
}
