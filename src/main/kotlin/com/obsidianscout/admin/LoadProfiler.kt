package com.obsidianscout.admin

import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.LongAccumulator
import java.util.concurrent.atomic.LongAdder

@Serializable
data class LoadMinute(
    val minute: Long,
    val requests: Long = 0,
    val liveRequests: Long = 0,
    val serverMs: Double = 0.0,
    val liveServerMs: Double = 0.0,
    val bytes: Long = 0,
    val liveBytes: Long = 0,
    val errors: Long = 0,
    val invalidations: Long = 0,
    val usersReached: Long = 0
)

@Serializable
data class LoadRoute(
    val route: String,
    val requests: Long,
    val liveRequests: Long,
    val serverMs: Double,
    val maxMs: Double,
    val bytes: Long
)

@Serializable
data class LoadTopic(val topic: String, val messages: Long, val usersReached: Long)

@Serializable
data class NodeLoadProfile(
    val nodeIp: String,
    val trackingSinceMs: Long,
    val usersConnected: Int,
    val cpuLoad: Double,
    val usedHeapMb: Long,
    val maxHeapMb: Long,
    val relayRowsWritten: Long,
    val relayRowsDelivered: Long,
    val minutes: List<LoadMinute>,
    val routes: List<LoadRoute>,
    val topics: List<LoadTopic>
)

@Serializable
data class ClusterLoadProfile(
    val windowMinutes: Int,
    val nodes: List<NodeLoadProfile>,
    val unreachableNodes: List<String>
)

/**
 * What the server is really doing, for the superadmin load profiler page: API requests by route and by minute, split
 * into live refresh traffic (requests marked with [LIVE_REFRESH_HEADER] by open pages updating themselves) and
 * everything else, plus the invalidations that set those refreshes off.
 *
 * Kept in memory for this node for the last hour and gone on restart. Recording is a few counter increments, so it can
 * stay on all the time; a forwarded request is counted on the node that served it, not the one that passed it on.
 */
object LoadProfiler {
    const val LIVE_REFRESH_HEADER = "X-Live-Refresh"
    const val WINDOW_MINUTES = 60

    private const val MS_PER_MINUTE = 60_000L
    private const val MAX_ROUTES_PER_MINUTE = 300
    private const val OTHER_ROUTE = "(other)"
    private const val MICROS_PER_MS = 1000.0

    /** Set on a call that was passed to a peer, so only the peer counts it. */
    val ForwardedToPeer = AttributeKey<Boolean>("LoadProfilerForwardedToPeer")

    /** The size of the JSON body built for a call, including one a 304 then didn't send. */
    val BodyBytes = AttributeKey<Long>("LoadProfilerBodyBytes")

    private class RouteStats {
        val requests = LongAdder()
        val liveRequests = LongAdder()
        val micros = LongAdder()
        val maxMicros = LongAccumulator(Math::max, 0L)
        val bytes = LongAdder()
    }

    private class TopicStats {
        val messages = LongAdder()
        val usersReached = LongAdder()
    }

    private class Minute(val minute: Long) {
        val requests = LongAdder()
        val liveRequests = LongAdder()
        val micros = LongAdder()
        val liveMicros = LongAdder()
        val bytes = LongAdder()
        val liveBytes = LongAdder()
        val errors = LongAdder()
        val invalidations = LongAdder()
        val usersReached = LongAdder()
        val routes = ConcurrentHashMap<String, RouteStats>()
        val topics = ConcurrentHashMap<String, TopicStats>()
    }

    private val minutes = AtomicReferenceArray<Minute?>(WINDOW_MINUTES + 1)
    private val relayWritten = LongAdder()
    private val relayDelivered = LongAdder()

    @Volatile
    private var trackingSinceMs = System.currentTimeMillis()

    /** The profiler's own page is left out, along with sockets, which stay open for hours and aren't requests. */
    fun tracks(path: String): Boolean =
        path.startsWith("/api/") && !path.startsWith("/api/ws") && !path.endsWith("/load-profile")

    fun record(
        method: String,
        path: String,
        status: Int,
        micros: Long,
        bodyBytes: Long,
        live: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val current = minuteAt(nowMs)
        val sentBytes = if (status == 304) 0L else bodyBytes
        current.requests.increment()
        current.micros.add(micros)
        current.bytes.add(sentBytes)
        if (status >= 500) current.errors.increment()
        if (live) {
            current.liveRequests.increment()
            current.liveMicros.add(micros)
            current.liveBytes.add(sentBytes)
        }
        val route = routeStats(current, "$method ${normalize(path)}")
        route.requests.increment()
        if (live) route.liveRequests.increment()
        route.micros.add(micros)
        route.maxMicros.accumulate(micros)
        route.bytes.add(sentBytes)
    }

    fun recordInvalidation(topics: Collection<String>, usersReached: Int, nowMs: Long = System.currentTimeMillis()) {
        val current = minuteAt(nowMs)
        current.invalidations.increment()
        current.usersReached.add(usersReached.toLong())
        topics.forEach { topic ->
            val stats = current.topics.computeIfAbsent(topic) { TopicStats() }
            stats.messages.increment()
            stats.usersReached.add(usersReached.toLong())
        }
    }

    fun recordRelayWritten(rows: Int) = relayWritten.add(rows.toLong())

    fun recordRelayDelivered() = relayDelivered.increment()

    /** Ids, event keys and team numbers all hold a digit, so one route is one row however many teams use it. */
    internal fun normalize(path: String): String =
        path.split("/").joinToString("/") { segment -> if (segment.any(Char::isDigit)) ":id" else segment }

    private fun minuteAt(nowMs: Long): Minute {
        val minute = nowMs / MS_PER_MINUTE
        val slot = (minute % minutes.length()).toInt()
        while (true) {
            val existing = minutes.get(slot)
            if (existing != null && existing.minute >= minute) return existing
            val fresh = Minute(minute)
            if (minutes.compareAndSet(slot, existing, fresh)) return fresh
        }
    }

    private fun routeStats(current: Minute, key: String): RouteStats {
        current.routes[key]?.let { return it }
        val capped = if (current.routes.size >= MAX_ROUTES_PER_MINUTE) OTHER_ROUTE else key
        return current.routes.computeIfAbsent(capped) { RouteStats() }
    }

    private fun recorded(windowMinutes: Int, nowMs: Long): List<Minute> {
        val newest = nowMs / MS_PER_MINUTE
        return ((newest - windowMinutes + 1)..newest).mapNotNull { minute ->
            minutes.get((minute % minutes.length()).toInt())?.takeIf { it.minute == minute }
        }
    }

    /** One row for every minute of the window, quiet ones included, oldest first. */
    internal fun minutesIn(windowMinutes: Int, nowMs: Long): List<LoadMinute> {
        val newest = nowMs / MS_PER_MINUTE
        val byMinute = recorded(windowMinutes, nowMs).associateBy { it.minute }
        return ((newest - windowMinutes + 1)..newest).map { minute ->
            val row = byMinute[minute] ?: return@map LoadMinute(minute * MS_PER_MINUTE)
            LoadMinute(
                minute = minute * MS_PER_MINUTE,
                requests = row.requests.sum(),
                liveRequests = row.liveRequests.sum(),
                serverMs = row.micros.sum() / MICROS_PER_MS,
                liveServerMs = row.liveMicros.sum() / MICROS_PER_MS,
                bytes = row.bytes.sum(),
                liveBytes = row.liveBytes.sum(),
                errors = row.errors.sum(),
                invalidations = row.invalidations.sum(),
                usersReached = row.usersReached.sum()
            )
        }
    }

    internal fun routesIn(windowMinutes: Int, nowMs: Long): List<LoadRoute> {
        val merged = HashMap<String, LoadRoute>()
        recorded(windowMinutes, nowMs).forEach { minute ->
            minute.routes.forEach { (route, stats) ->
                val row = LoadRoute(
                    route = route,
                    requests = stats.requests.sum(),
                    liveRequests = stats.liveRequests.sum(),
                    serverMs = stats.micros.sum() / MICROS_PER_MS,
                    maxMs = stats.maxMicros.get() / MICROS_PER_MS,
                    bytes = stats.bytes.sum()
                )
                merged.merge(route, row) { a, b ->
                    a.copy(
                        requests = a.requests + b.requests,
                        liveRequests = a.liveRequests + b.liveRequests,
                        serverMs = a.serverMs + b.serverMs,
                        maxMs = maxOf(a.maxMs, b.maxMs),
                        bytes = a.bytes + b.bytes
                    )
                }
            }
        }
        return merged.values.sortedByDescending { it.serverMs }
    }

    internal fun topicsIn(windowMinutes: Int, nowMs: Long): List<LoadTopic> {
        val merged = HashMap<String, LoadTopic>()
        recorded(windowMinutes, nowMs).forEach { minute ->
            minute.topics.forEach { (topic, stats) ->
                val row = LoadTopic(topic, stats.messages.sum(), stats.usersReached.sum())
                merged.merge(topic, row) { a, b ->
                    a.copy(messages = a.messages + b.messages, usersReached = a.usersReached + b.usersReached)
                }
            }
        }
        return merged.values.sortedByDescending { it.messages }
    }

    fun localProfile(windowMinutes: Int, nowMs: Long = System.currentTimeMillis()): NodeLoadProfile {
        val window = windowMinutes.coerceIn(1, WINDOW_MINUTES)
        val load = PeerLoadRouter.getLocalNodeLoad()
        return NodeLoadProfile(
            nodeIp = load.ip,
            trackingSinceMs = trackingSinceMs,
            usersConnected = com.obsidianscout.db.NotificationWebSocketManager.connectedUsers(),
            cpuLoad = load.cpuLoad,
            usedHeapMb = load.usedHeapMb,
            maxHeapMb = load.maxHeapMb,
            relayRowsWritten = relayWritten.sum(),
            relayRowsDelivered = relayDelivered.sum(),
            minutes = minutesIn(window, nowMs),
            routes = routesIn(window, nowMs),
            topics = topicsIn(window, nowMs)
        )
    }

    internal fun resetForTest() {
        for (i in 0 until minutes.length()) minutes.set(i, null)
        relayWritten.reset()
        relayDelivered.reset()
        trackingSinceMs = System.currentTimeMillis()
    }
}
