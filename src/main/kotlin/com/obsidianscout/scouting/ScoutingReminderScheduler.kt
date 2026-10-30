package com.obsidianscout.scouting

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.auth.EmailService
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.PushNotificationService
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.integrations.ClusterLease
import com.obsidianscout.integrations.SettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val consoleLog = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.scouting.ScoutingReminderScheduler")


object ScoutingReminderScheduler {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job != null && job?.isActive == true) return
        job = scope.launch {
            consoleLog.info("[ScoutingReminderScheduler] Background reminder scheduler started.")
            while (isActive) {
                try {
                    checkAndSendReminders()
                } catch (e: Exception) {
                    consoleLog.error("[ScoutingReminderScheduler] Error running reminder sweep: ${e.message}")
                }
                delay(60_000) // Run every 60 seconds
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        consoleLog.info("[ScoutingReminderScheduler] Background reminder scheduler stopped.")
    }

    /** One node sends reminders; the lease outlives a sweep so it stays put while the holder is alive. */
    private const val LEASE_KEY = "assignment-reminders"
    private const val LEASE_SECONDS = 150L

    fun checkAndSendReminders() {
        if (ClusterLease.tryAcquire(LEASE_KEY, LEASE_SECONDS) != ClusterLease.Result.ACQUIRED) return

        val now = Instant.now()
        val nowEpochSec = now.epochSecond

        // Only assignments whose match starts between 15 minutes ago and 2 hours from now, with a reminder still unsent.
        val due = readTransaction {
            val pending = ScoutingAssignments
                .select(
                    ScoutingAssignments.id, ScoutingAssignments.matchKey, ScoutingAssignments.eventKey,
                    ScoutingAssignments.ownerTeamNumber, ScoutingAssignments.program,
                    ScoutingAssignments.assignedUserId, ScoutingAssignments.reminderMinutesBefore,
                    ScoutingAssignments.pushReminderSentAt, ScoutingAssignments.emailReminderSentAt,
                    ScoutingAssignments.matchNumber, ScoutingAssignments.targetTeamNumber,
                    ScoutingAssignments.assignmentType
                )
                .where {
                    (ScoutingAssignments.status eq "PENDING") and
                    ScoutingAssignments.matchKey.isNotNull() and
                    (ScoutingAssignments.pushReminderSentAt.isNull() or ScoutingAssignments.emailReminderSentAt.isNull())
                }
                .toList()
            if (pending.isEmpty()) return@readTransaction emptyList()

            val minTime = nowEpochSec - 900
            val maxTime = nowEpochSec + 7200
            val matchesByTeam = pending.groupBy { it[ScoutingAssignments.ownerTeamNumber] to it[ScoutingAssignments.program] }
                .mapValues { (scope, rows) ->
                    val keys = rows.mapNotNull { it[ScoutingAssignments.matchKey] }.distinct()
                    val matchRows = runCatching {
                        com.obsidianscout.integrations.TeamCacheOverrideService.matchRowsByKeys(keys, scope.first, scope.second)
                    }.getOrElse {
                        ApiMatches.selectAll().where { ApiMatches.matchKey inList keys }.toList()
                    }
                    matchRows.associateBy { it[ApiMatches.matchKey].lowercase().trim() }
                }

            pending.mapNotNull { row ->
                val mKey = row[ScoutingAssignments.matchKey]?.lowercase()?.trim() ?: return@mapNotNull null
                val scope = row[ScoutingAssignments.ownerTeamNumber] to row[ScoutingAssignments.program]
                val matchRow = matchesByTeam[scope]?.get(mKey) ?: return@mapNotNull null
                val sched = matchRow[ApiMatches.scheduledTime] ?: return@mapNotNull null
                if (sched in minTime..maxTime) row to matchRow else null
            }
        }
        if (due.isEmpty()) return

        val userIds = due.map { (assignment, _) -> assignment[ScoutingAssignments.assignedUserId].value }.distinct()
        val usersMap = readTransaction {
            Users.select(Users.id, Users.username, Users.email)
                .where { Users.id inList userIds }
                .associateBy { it[Users.id].value }
        }

        val baseUrl = AppConfigLoader.load().getEffectiveSiteUrl()
        // Cache team settings by (teamNumber, program)
        val teamSettingsMap = mutableMapOf<Pair<Int, String>, com.obsidianscout.integrations.ApiSettings>()

        for ((assignment, matchRow) in due) {
            val mKey = assignment[ScoutingAssignments.matchKey] ?: continue
            val scheduledTimeSec = matchRow[ApiMatches.scheduledTime] ?: continue
            val diffSec = scheduledTimeSec - nowEpochSec

            val teamNumber = assignment[ScoutingAssignments.ownerTeamNumber]
            val program = assignment[ScoutingAssignments.program]
            val settings = teamSettingsMap.getOrPut(Pair(teamNumber, program)) {
                SettingsService.getSettings(teamNumber, program)
            }

            val reminderMinutes = assignment[ScoutingAssignments.reminderMinutesBefore] ?: settings.assignmentReminderMinutes
            val reminderWindowSec = reminderMinutes * 60L

            // Trigger when within the reminder window before match scheduled start
            if (diffSec > reminderWindowSec || diffSec <= -300) continue

            val userUuid = assignment[ScoutingAssignments.assignedUserId].value
            val userRow = usersMap[userUuid] ?: continue
            val assignmentId = assignment[ScoutingAssignments.id].value

            val matchNumber = assignment[ScoutingAssignments.matchNumber] ?: matchRow[ApiMatches.matchNumber] ?: 0
            val targetTeam = assignment[ScoutingAssignments.targetTeamNumber]
            val type = assignment[ScoutingAssignments.assignmentType]
            val eventKey = assignment[ScoutingAssignments.eventKey]

            val title = when (type) {
                "MATCH" -> "Match #$matchNumber starting soon!"
                "QUALITATIVE" -> "Qualitative Scouting Match #$matchNumber"
                else -> "Scouting Reminder"
            }

            val teamDetail = if (targetTeam != null) "Team $targetTeam" else "your assigned team"
            val body = "You are assigned to scout $teamDetail in Match #$matchNumber in approx ${maxOf(1, diffSec / 60)} min(s)."
            val scoutUrl = when (type) {
                "MATCH" -> "/scout?matchKey=$mKey&matchNumber=$matchNumber${if (targetTeam != null) "&team=$targetTeam" else ""}&event=$eventKey"
                "QUALITATIVE" -> "/qual-scout?matchKey=$mKey&matchNumber=$matchNumber${if (targetTeam != null) "&team=$targetTeam" else ""}&event=$eventKey"
                else -> "/my-assignments"
            }

            // Push reminder
            if (assignment[ScoutingAssignments.pushReminderSentAt] == null && settings.enableAssignmentPushReminders &&
                claim(assignmentId, ScoutingAssignments.pushReminderSentAt, now)
            ) {
                try {
                    PushNotificationService.sendAssignmentReminder(
                        userId = userUuid,
                        title = title,
                        body = body,
                        url = scoutUrl,
                        tag = "reminder-match-$matchNumber"
                    )
                    consoleLog.info("[ScoutingReminderScheduler] Sent push reminder for assignment $assignmentId to user ${userRow[Users.username]} for Match #$matchNumber")
                } catch (e: Exception) {
                    unclaim(assignmentId, ScoutingAssignments.pushReminderSentAt)
                    consoleLog.error("[ScoutingReminderScheduler] Failed to send push reminder for assignment $assignmentId: ${e.message}")
                }
            }

            // Email reminder
            val userEmail = userRow[Users.email]
            if (assignment[ScoutingAssignments.emailReminderSentAt] == null && settings.enableAssignmentEmailReminders &&
                !userEmail.isNullOrBlank() && claim(assignmentId, ScoutingAssignments.emailReminderSentAt, now)
            ) {
                try {
                    EmailService.sendAssignmentReminderEmail(
                        to = userEmail,
                        username = userRow[Users.username],
                        teamNumber = teamNumber,
                        title = title,
                        details = body,
                        actionUrl = "$baseUrl$scoutUrl"
                    )
                    consoleLog.info("[ScoutingReminderScheduler] Sent email reminder for assignment $assignmentId to user ${userRow[Users.username]} for Match #$matchNumber")
                } catch (e: Exception) {
                    unclaim(assignmentId, ScoutingAssignments.emailReminderSentAt)
                    consoleLog.error("[ScoutingReminderScheduler] Failed to send email reminder for assignment $assignmentId: ${e.message}")
                }
            }
        }
    }

    /**
     * Marks one reminder as sent before sending it, so it can only go out once even if two senders overlap (a lease
     * handover). False when it was already claimed.
     */
    private fun claim(assignmentId: UUID, sentAt: Column<Instant?>, now: Instant): Boolean = transaction {
        ScoutingAssignments.update({ (ScoutingAssignments.id eq assignmentId) and sentAt.isNull() }) {
            it[sentAt] = now
            it[ScoutingAssignments.updatedAt] = now
        } == 1
    }

    /** Releases a claim whose send failed, so the next sweep tries again. */
    private fun unclaim(assignmentId: UUID, sentAt: Column<Instant?>) {
        runCatching {
            transaction {
                ScoutingAssignments.update({ ScoutingAssignments.id eq assignmentId }) { it[sentAt] = null }
            }
        }
    }
}
