package com.obsidianscout.collab

import com.obsidianscout.admin.ServerLogService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Who is on the other end of a live-edit socket. Permission checks happen before a socket reaches the hub. */
data class CollabParticipant(
    val sid: String,
    val userId: String,
    val username: String,
    val teamNumber: Int,
    val role: String,
    val canEdit: Boolean
) {
    val displayName get() = "$username (Team $teamNumber)"

    companion object {
        private val SID_PATTERN = Regex("^[A-Za-z0-9_-]{6,40}$")

        /** The editor's own session id if it sent a valid one, otherwise a fresh one. */
        fun sanitizeSid(raw: String?): String =
            raw?.takeIf { SID_PATTERN.matches(it) } ?: UUID.randomUUID().toString().replace("-", "").take(16)
    }
}

/**
 * Live config editing. Each config being edited is a room owned by exactly one cluster node (see
 * [ConfigCollabLeases]); that node orders every edit, broadcasts it to all editors and autosaves.
 * Editors connected to other nodes are relayed to the owner by [ConfigCollabRelay].
 *
 * Protocol (JSON text frames):
 *  editor -> server  {"type":"ops","seq":N,"ops":[...]}  {"type":"commit","req":"id"}  {"type":"focus","key":K|null}  {"type":"ping"}
 *  server -> editor  init, ops (echoed to the sender as its acknowledgement), ack, reject, presence, committed, notice, error, pong
 */
object ConfigCollabHub {
    const val PROTOCOL = 1
    private const val MAX_DOC_BYTES = 512_000
    private const val MAX_OPS_PER_BATCH = 500
    private const val MAX_CLIENTS_PER_ROOM = 50
    private const val PERSIST_QUIET_MS = 800L
    private const val PERSIST_MAX_DELAY_MS = 5_000L
    private const val EXTERNAL_POLL_MS = 3_000L
    private const val TICK_MS = 400L
    private const val IDLE_ROOM_MS = 15_000L

    private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.collab.ConfigCollabHub")
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rooms = ConcurrentHashMap<String, Room>()
    private val roomsLock = Mutex()
    private val compactJson = Json { encodeDefaults = true }

    internal class Client(val participant: CollabParticipant, val session: DefaultWebSocketServerSession) {
        val outgoing = Channel<String>(capacity = 512)
        @Volatile var focus: String? = null

        fun offer(text: String): Boolean = outgoing.trySend(text).isSuccess
    }

    internal class Room(val scope: ConfigCollabScope) {
        val mutex = Mutex()
        val epoch: String = UUID.randomUUID().toString().replace("-", "").take(12)
        var version = 0L
        lateinit var state: KeyedDoc
        /** Highest batch applied per editor session, so a reconnecting editor doesn't resend it. */
        val ackSeq = HashMap<String, Long>()
        val clients = mutableListOf<Client>()
        /** The room's view of what is stored, in the room's key space. */
        lateinit var lastPersisted: KeyedDoc
        var lastPersistedRaw: String = ""
        var revisionBase: ScoutingConfig? = null
        var editedSinceRevision = false
        var lastEditor: String = ""
        var dirtySince = 0L
        var lastChangeAt = 0L
        var lastPollAt = 0L
        var emptySince = 0L
        var persistFailing = false
        var closed = false
        var ticker: Job? = null
        private var keyCounter = 0L

        val dirty get() = dirtySince > 0L

        /** Server-made field keys start with '~', which editor session ids can't contain. */
        fun newKey(): String = "~${keyCounter++}"
    }

    // ── Entry points ───────────────────────────────────────────────────────────

    /** An editor connected to this node. Serves it here if this node owns the room, otherwise relays it. */
    suspend fun connect(session: DefaultWebSocketServerSession, scope: ConfigCollabScope, participant: CollabParticipant) {
        when (val owner = ConfigCollabLeases.resolveOwner(scope.roomKey, isLocallyOpen = isOpen(scope.roomKey))) {
            is ConfigCollabLeases.Owner.Local -> serve(session, scope, participant)
            is ConfigCollabLeases.Owner.Remote -> ConfigCollabRelay.relay(session, owner.address, scope, participant)
        }
    }

    /** A socket relayed from another node. Served only if this node (still) owns the room. */
    suspend fun connectRelayed(session: DefaultWebSocketServerSession, scope: ConfigCollabScope, participant: CollabParticipant) {
        when (ConfigCollabLeases.resolveOwner(scope.roomKey, isLocallyOpen = isOpen(scope.roomKey))) {
            is ConfigCollabLeases.Owner.Local -> serve(session, scope, participant)
            is ConfigCollabLeases.Owner.Remote -> session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "moved"))
        }
    }

    private fun isOpen(roomKey: String) = rooms[roomKey]?.closed == false

    internal fun openRoomKeys(): Set<String> = rooms.filterValues { !it.closed }.keys

    private suspend fun serve(session: DefaultWebSocketServerSession, scope: ConfigCollabScope, participant: CollabParticipant) {
        val room = openRoom(scope) ?: run {
            session.send(Frame.Text(message("error") { put("message", "The config could not be loaded") }))
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "unavailable"))
            return
        }
        val client = Client(participant, session)
        val joined = room.mutex.withLock {
            if (room.closed || room.clients.size >= MAX_CLIENTS_PER_ROOM) return@withLock false
            room.clients.add(client)
            room.emptySince = 0L
            client.offer(initMessage(room, participant))
            broadcast(room, presenceMessage(room))
            true
        }
        if (!joined) {
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, if (room.closed) "moved" else "room full"))
            return
        }

        val writer = hubScope.launch {
            try {
                for (text in client.outgoing) session.send(Frame.Text(text))
            } catch (_: Throwable) {
            }
            // The queue closes when the client fell behind or the room closed; end the socket so it resyncs.
            runCatching { session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "resync")) }
        }
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (text.length > MAX_DOC_BYTES * 2) {
                    client.offer(message("error") { put("message", "Message too large") })
                    continue
                }
                handleMessage(room, client, text)
            }
        } catch (_: ClosedReceiveChannelException) {
        } catch (e: Throwable) {
            log.debug("Live config socket ended: ${e.message}")
        } finally {
            client.outgoing.close()
            writer.cancel()
            room.mutex.withLock {
                if (room.clients.remove(client)) {
                    if (room.clients.isEmpty()) room.emptySince = System.currentTimeMillis()
                    else broadcast(room, presenceMessage(room))
                }
            }
        }
    }

    // ── Rooms ──────────────────────────────────────────────────────────────────

    private suspend fun openRoom(scope: ConfigCollabScope): Room? {
        rooms[scope.roomKey]?.let { if (!it.closed) return it }
        return roomsLock.withLock {
            rooms[scope.roomKey]?.let { if (!it.closed) return@withLock it }
            val raw = withContext(Dispatchers.IO) { scope.loadRaw() } ?: return@withLock null
            val room = Room(scope)
            room.state = ConfigSyncEngine.keyed(parseObject(raw) ?: JsonObject(emptyMap()), room::newKey)
            room.lastPersisted = room.state
            room.lastPersistedRaw = raw
            room.revisionBase = ConfigCollabScope.parseConfig(raw)
            room.lastPollAt = System.currentTimeMillis()
            rooms[scope.roomKey] = room
            room.ticker = hubScope.launch { tick(room) }
            room
        }
    }

    private suspend fun tick(room: Room) {
        while (hubScope.isActive && !room.closed) {
            delay(TICK_MS)
            try {
                val now = System.currentTimeMillis()
                val idle = room.mutex.withLock {
                    if (room.closed) return
                    if (room.dirty && (now - room.lastChangeAt >= PERSIST_QUIET_MS || now - room.dirtySince >= PERSIST_MAX_DELAY_MS)) {
                        persist(room)
                    } else if (now - room.lastPollAt >= EXTERNAL_POLL_MS) {
                        room.lastPollAt = now
                        pullExternalChanges(room)
                    }
                    room.clients.isEmpty() && room.emptySince > 0 && now - room.emptySince >= IDLE_ROOM_MS
                }
                if (idle) closeRoom(room, reason = null)
            } catch (e: Throwable) {
                log.warn("Live config room ${room.scope.roomKey} tick failed: ${e.message}")
            }
        }
    }

    /** Called by the lease keeper when another node took over [roomKey]. */
    internal suspend fun onLeaseLost(roomKey: String) {
        val room = rooms[roomKey] ?: return
        closeRoom(room, reason = "moved")
    }

    private suspend fun closeRoom(room: Room, reason: String?) {
        val clients = room.mutex.withLock {
            if (room.closed) return
            if (room.dirty) persist(room)
            autoRevision(room)
            room.closed = true
            room.clients.toList().also { room.clients.clear() }
        }
        rooms.remove(room.scope.roomKey, room)
        room.ticker?.cancel()
        clients.forEach { c ->
            c.outgoing.close()
            runCatching { c.session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, reason ?: "closed")) }
        }
        if (reason == null) withContext(Dispatchers.IO) { ConfigCollabLeases.release(room.scope.roomKey) }
    }

    // ── Messages ───────────────────────────────────────────────────────────────

    private suspend fun handleMessage(room: Room, client: Client, text: String) {
        val msg = parseObject(text) ?: return
        when ((msg["type"] as? JsonPrimitive)?.content) {
            "ops" -> handleOps(room, client, msg)
            "commit" -> handleCommit(room, client, (msg["req"] as? JsonPrimitive)?.content?.take(64) ?: "")
            "focus" -> {
                val key = (msg["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(64)
                room.mutex.withLock {
                    if (client.focus != key) {
                        client.focus = key
                        broadcast(room, presenceMessage(room))
                    }
                }
            }
            "ping" -> client.offer(message("pong") {})
        }
    }

    private suspend fun handleOps(room: Room, client: Client, msg: JsonObject) {
        val seq = msg["seq"]?.jsonPrimitive?.longOrNull ?: return
        val ops = msg["ops"] as? JsonArray ?: return
        val p = client.participant
        room.mutex.withLock {
            if (room.closed) return
            if (seq <= (room.ackSeq[p.sid] ?: 0L)) {
                client.offer(message("ack") { put("seq", seq) })
                return
            }
            val rejection = when {
                !p.canEdit -> "You don't have permission to edit this config"
                ops.size > MAX_OPS_PER_BATCH -> "Too many changes at once"
                else -> runCatching { ops.forEach(ConfigSyncEngine::validate); null }.getOrElse { it.message ?: "Invalid change" }
            }
            if (rejection != null) {
                client.offer(message("reject") { put("seq", seq); put("reason", rejection) })
                return
            }
            val next = ConfigSyncEngine.applyOps(room.state, ops)
            if (encode(next.doc).length > MAX_DOC_BYTES) {
                client.offer(message("reject") { put("seq", seq); put("reason", "The config is too large") })
                return
            }
            room.state = next
            room.ackSeq[p.sid] = seq
            room.lastEditor = p.username
            room.editedSinceRevision = true
            markDirty(room)
            broadcastOps(room, p.sid, seq, p.displayName, ops)
        }
    }

    private suspend fun handleCommit(room: Room, client: Client, req: String) {
        val p = client.participant
        fun fail(error: String) = client.offer(message("committed") { put("req", req); put("ok", false); put("error", error) })
        if (!p.canEdit) {
            fail("You don't have permission to edit this config")
            return
        }
        room.mutex.withLock {
            if (room.closed) return
            if (room.dirty) persist(room)
            if (room.persistFailing) {
                fail("The config could not be saved; check it for errors")
                return
            }
            val current = ConfigCollabScope.parseConfig(encode(room.state.doc)) ?: run {
                fail("The config is not valid")
                return
            }
            val result = try {
                withContext(Dispatchers.IO) { room.scope.commit(room.revisionBase, current, p.username) }
            } catch (e: Throwable) {
                fail(e.message ?: "Save failed")
                return
            }
            room.revisionBase = current
            room.editedSinceRevision = false
            client.offer(message("committed") {
                put("req", req)
                put("ok", true)
                put("hasFieldChanges", result.hasFieldChanges)
                put("changedFields", JsonArray(result.changedFields.map { JsonPrimitive(it) }))
                put("entryCount", result.entryCount)
                put("configKind", result.configKind)
            })
            val notice = message("notice") { put("level", "info"); put("message", "${p.displayName} saved a version") }
            room.clients.forEach { if (it !== client) it.offer(notice) }
        }
    }

    // ── Persistence (callers hold room.mutex) ──────────────────────────────────

    private fun markDirty(room: Room) {
        val now = System.currentTimeMillis()
        if (room.dirtySince == 0L) room.dirtySince = now
        room.lastChangeAt = now
    }

    private suspend fun persist(room: Room) {
        pullExternalChanges(room)
        val stored = try {
            val json = encode(room.state.doc)
            withContext(Dispatchers.IO) { room.scope.persist(json) }
        } catch (e: Throwable) {
            if (!room.persistFailing) {
                room.persistFailing = true
                ServerLogService.appendLog("WARN", "ConfigCollabHub", "Autosave of ${room.scope.roomKey} failed: ${e.message}")
                broadcast(room, message("notice") {
                    put("level", "error")
                    put("message", "Changes can't be saved yet: ${e.message ?: "the config is not valid"}")
                })
            }
            // Retried on the next change rather than in a loop.
            room.dirtySince = 0L
            return
        }
        if (room.persistFailing) {
            room.persistFailing = false
            broadcast(room, message("notice") { put("level", "success"); put("message", "All changes saved") })
        }
        room.dirtySince = 0L
        room.lastPersistedRaw = stored
        room.lastPollAt = System.currentTimeMillis()
        // Storage may normalize the config (labels, option points); give every editor the stored form.
        parseObject(stored)?.let { storedDoc ->
            applyServerChange(room, ConfigSyncEngine.diff(room.state, storedDoc, room::newKey).ops, "Server")
        }
        room.lastPersisted = room.state
    }

    /** Folds in a save that bypassed this room (a REST save, a preset reset, another node) since the last check. */
    private suspend fun pullExternalChanges(room: Room) {
        val raw = withContext(Dispatchers.IO) { room.scope.loadRaw() } ?: return
        if (raw == room.lastPersistedRaw) return
        val theirs = parseObject(raw) ?: return
        val result = ConfigSyncEngine.diff(room.lastPersisted, theirs, room::newKey)
        room.lastPersistedRaw = raw
        room.lastPersisted = KeyedDoc(ConfigSyncEngine.ensureFields(theirs), result.keys)
        applyServerChange(room, result.ops, "Saved outside the live editor")
    }

    private fun applyServerChange(room: Room, ops: List<JsonObject>, label: String) {
        if (ops.isEmpty()) return
        room.state = ConfigSyncEngine.applyOps(room.state, ops)
        broadcastOps(room, "server", 0L, label, ops)
    }

    private suspend fun autoRevision(room: Room) {
        if (!room.editedSinceRevision || room.scope !is ConfigCollabScope.Team) return
        val current = ConfigCollabScope.parseConfig(encode(room.state.doc)) ?: return
        val base = room.revisionBase
        val unchanged = base != null &&
            JsonSupport.json.encodeToString(ScoutingConfig.serializer(), base) == JsonSupport.json.encodeToString(ScoutingConfig.serializer(), current)
        if (!unchanged) {
            runCatching {
                withContext(Dispatchers.IO) { room.scope.commit(base, current, room.lastEditor, summaryPrefix = "Live edit") }
            }.onFailure { log.warn("Auto revision for ${room.scope.roomKey} failed: ${it.message}") }
        }
        room.revisionBase = current
        room.editedSinceRevision = false
    }

    // ── Outgoing (callers hold room.mutex) ─────────────────────────────────────

    private fun initMessage(room: Room, p: CollabParticipant) = message("init") {
        put("proto", PROTOCOL)
        put("epoch", room.epoch)
        put("version", room.version)
        put("doc", room.state.doc)
        put("keys", JsonArray(room.state.keys.map { JsonPrimitive(it) }))
        put("ackSeq", room.ackSeq[p.sid] ?: 0L)
        put("canEdit", p.canEdit)
        put("sid", p.sid)
        put("editors", editorsJson(room))
    }

    private fun broadcastOps(room: Room, sid: String, seq: Long, user: String, ops: List<JsonElement>) {
        room.version++
        broadcast(room, message("ops") {
            put("version", room.version)
            put("sid", sid)
            put("seq", seq)
            put("user", user)
            put("ops", JsonArray(ops))
        })
    }

    private fun broadcast(room: Room, text: String) {
        room.clients.forEach { c ->
            // A client that can't keep up is disconnected and resyncs instead of holding up everyone else.
            if (!c.offer(text)) c.outgoing.close()
        }
    }

    private fun presenceMessage(room: Room) = message("presence") { put("editors", editorsJson(room)) }

    private fun editorsJson(room: Room) = buildJsonArray {
        room.clients.distinctBy { it.participant.sid }.forEach { c ->
            add(buildJsonObject {
                put("sid", c.participant.sid)
                put("username", c.participant.username)
                put("teamNumber", c.participant.teamNumber)
                put("role", c.participant.role)
                put("canEdit", c.participant.canEdit)
                put("focus", c.focus?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
    }

    private inline fun message(type: String, body: JsonObjectBuilder.() -> Unit): String =
        compactJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("type", type); body() })

    private fun encode(doc: JsonObject): String = compactJson.encodeToString(JsonObject.serializer(), doc)

    private fun parseObject(text: String): JsonObject? =
        runCatching { JsonSupport.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
