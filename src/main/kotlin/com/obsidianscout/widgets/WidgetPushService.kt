package com.obsidianscout.widgets

import com.obsidianscout.db.FcmService
import com.obsidianscout.integrations.IntegrationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells phones to refresh their home-screen widgets when something they show changes, so widgets stay current
 * while the app is closed. Only devices that have a widget showing the changed data placed get a push; changes
 * are gathered for a moment and each user gets at most one push per [USER_MIN_INTERVAL_MS].
 *
 * A sweep every [SWEEP_INTERVAL_MS] also watches the schedule of every event a widget is showing: a new result or a shifted
 * schedule changes the predicted times on every remaining match, whichever way the data arrived.
 */
object WidgetPushService {
    private val log = LoggerFactory.getLogger(WidgetPushService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sweepJob: Job? = null

    // Near real time: changes go out within ~2 seconds, a busy user gets at most one push every few seconds
    // (more changes in between are combined), and schedules are checked every 15 seconds.
    private const val DEBOUNCE_MS = 1_500L
    private const val USER_MIN_INTERVAL_MS = 8_000L
    private const val SWEEP_INTERVAL_MS = 15_000L

    /** Snapshot section -> widget kinds that show it. */
    val SECTION_WIDGETS: Map<String, Set<String>> = mapOf(
        "schedule" to setOf("next_match", "schedule", "predictor", "assignments", "battery", "checklist"),
        "prediction" to setOf("predictor"),
        "assignments" to setOf("assignments"),
        "battery" to setOf("battery"),
        "checklist" to setOf("checklist"),
        "rankings" to setOf("rankings"),
        "chat" to setOf("chat"),
        // Role, page permissions or Non-Scouting Tools changed: every widget may need to appear or go away.
        "access" to WidgetAccess.WIDGET_PAGES.keys,
    )

    private val pending = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val lastSent = ConcurrentHashMap<UUID, Long>()
    private val flushScheduled = ConcurrentHashMap<UUID, Boolean>()
    private val scheduleFingerprints = ConcurrentHashMap<String, Int>()

    fun start() {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MS)
                try {
                    sweepSchedules()
                } catch (e: Exception) {
                    log.warn("[widgets] schedule sweep failed: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        sweepJob?.cancel()
        sweepJob = null
    }

    /** Something changed for a whole team (drive-team tools, settings, chat). */
    fun notifyTeam(teamNumber: Int, program: String, vararg sections: String) {
        launchSafely {
            val holders = WidgetTokenService.activeHolders()
                .filter { it.teamNumber == teamNumber && it.program.equals(program, ignoreCase = true) }
            queue(holders, sections.toSet())
        }
    }

    private val ownerPattern = Regex("^team_(\\d+)_(\\w+)$")

    /** A drive-team document changed; [owner] is the `team_<number>_<program>` key those documents are stored under. */
    fun notifyOwner(owner: String, vararg sections: String) {
        val m = ownerPattern.find(owner) ?: return
        notifyTeam(m.groupValues[1].toInt(), m.groupValues[2], *sections)
    }

    /** Something changed for specific users (their assignments, their account). */
    fun notifyUsers(userIds: Collection<String>, vararg sections: String) {
        if (userIds.isEmpty()) return
        val ids = userIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        launchSafely {
            queue(WidgetTokenService.activeHolders().filter { it.userId in ids }, sections.toSet())
        }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                log.warn("[widgets] refresh push failed: ${e.message}")
            }
        }
    }

    private fun queue(holders: List<WidgetTokenService.Holder>, sections: Set<String>) {
        val affected = sections.flatMap { SECTION_WIDGETS[it].orEmpty() }.toSet()
        holders.filter { h -> h.placed.any { it in affected } }.map { it.userId }.toSet().forEach { user ->
            pending.computeIfAbsent(user) { ConcurrentHashMap.newKeySet() }.addAll(sections)
            if (flushScheduled.putIfAbsent(user, true) == null) {
                scope.launch {
                    val wait = maxOf(DEBOUNCE_MS, (lastSent[user] ?: 0L) + USER_MIN_INTERVAL_MS - System.currentTimeMillis())
                    delay(wait)
                    flushScheduled.remove(user)
                    val toSend = pending.remove(user)?.toSet().orEmpty()
                    if (toSend.isEmpty()) return@launch
                    lastSent[user] = System.currentTimeMillis()
                    try {
                        FcmService.sendWidgetRefresh(listOf(user), toSend)
                    } catch (e: Exception) {
                        log.warn("[widgets] widget refresh push failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun sweepSchedules() {
        val scheduleKinds = SECTION_WIDGETS.getValue("schedule") + "rankings"
        val holders = WidgetTokenService.activeHolders().filter { it.eventKey.isNotBlank() && it.placed.any { k -> k in scheduleKinds } }
        holders.groupBy { it.eventKey.lowercase() to it.program.uppercase() }.forEach { (key, group) ->
            val (eventKey, program) = key
            val fingerprint = WidgetSnapshotService.scheduleFingerprint(IntegrationService.listMatches(eventKey, program))
            val previous = scheduleFingerprints.put("$program:$eventKey", fingerprint)
            if (previous != null && previous != fingerprint) queue(group, setOf("schedule", "prediction", "rankings"))
        }
    }
}
