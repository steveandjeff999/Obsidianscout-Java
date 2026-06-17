package com.obsidianscout.scouting

import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.ScoutingAlliances
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.ConcurrentHashMap

object AllianceCollaborationManager {
    
    @Serializable
    data class EditorInfo(val teamNumber: Int, val username: String, val role: String)

    @Serializable
    data class WSMessage(
        val type: String,
        val configJson: String? = null,
        val editors: List<EditorInfo>? = null,
        val editor: String? = null
    )

    private val rooms = ConcurrentHashMap<String, ConcurrentHashMap<DefaultWebSocketServerSession, EditorInfo>>()

    private fun getRoomKey(allianceId: Int, kind: String): String = "$allianceId:$kind"

    suspend fun handleConnection(
        session: DefaultWebSocketServerSession,
        allianceId: Int,
        kind: String,
        userSession: UserSession
    ) {
        val roomKey = getRoomKey(allianceId, kind)
        val room = rooms.computeIfAbsent(roomKey) { ConcurrentHashMap() }
        
        val editorInfo = EditorInfo(
            teamNumber = userSession.teamNumber,
            username = userSession.username,
            role = userSession.role.name
        )
        
        room[session] = editorInfo
        
        // Broadcast presence update
        broadcastPresence(allianceId, kind)
        
        // Send initial state to the newly connected client
        val initialConfig = transaction {
             val alliance = ScoutingAlliances
                .selectAll().where { ScoutingAlliances.id eq allianceId }
                .firstOrNull()
            if (alliance != null) {
                when (kind) {
                    "game" -> alliance[ScoutingAlliances.matchConfigJson]
                    "pit" -> alliance[ScoutingAlliances.pitConfigJson]
                    "qual" -> alliance[ScoutingAlliances.qualitativeConfigJson]
                    else -> null
                }
            } else null
        } ?: "{}"
        
        val initMsg = WSMessage(
            type = "init",
            configJson = initialConfig,
            editors = room.values.toList()
        )
        session.send(Frame.Text(Json.encodeToString(initMsg)))
        
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    val msg = runCatching { Json.decodeFromString<WSMessage>(text) }.getOrNull()
                    if (msg != null && msg.type == "edit" && msg.configJson != null) {
                        // Check if the user is an admin of the alliance
                        val isAdmin = AllianceService.isAllianceAdmin(userSession.teamNumber, allianceId)
                        if (isAdmin) {
                            // Update database
                            transaction {
                                ScoutingAlliances.update({ ScoutingAlliances.id eq allianceId }) {
                                    when (kind) {
                                        "game" -> it[matchConfigJson] = msg.configJson
                                        "pit" -> it[pitConfigJson] = msg.configJson
                                        "qual" -> it[qualitativeConfigJson] = msg.configJson
                                    }
                                    it[updatedAt] = java.time.Instant.now()
                                }
                            }
                            
                            // Broadcast update to everyone else in the room
                            val updateMsg = WSMessage(
                                type = "update",
                                configJson = msg.configJson,
                                editor = "${userSession.username} (Team ${userSession.teamNumber})"
                            )
                            val serialized = Json.encodeToString(updateMsg)
                            room.keys.forEach { client ->
                                if (client != session) {
                                    runCatching { client.send(Frame.Text(serialized)) }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // normal close
        } catch (e: Throwable) {
            // abnormal close
        } finally {
            room.remove(session)
            if (room.isEmpty()) {
                rooms.remove(roomKey)
            } else {
                broadcastPresence(allianceId, kind)
            }
        }
    }

    private suspend fun broadcastPresence(allianceId: Int, kind: String) {
        val roomKey = getRoomKey(allianceId, kind)
        val room = rooms[roomKey] ?: return
        val editors = room.values.toList()
        val msg = WSMessage(type = "presence", editors = editors)
        val serialized = Json.encodeToString(msg)
        room.keys.forEach { client ->
            runCatching { client.send(Frame.Text(serialized)) }
        }
    }
}
