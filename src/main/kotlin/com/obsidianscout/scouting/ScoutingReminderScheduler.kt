package com.obsidianscout.scouting

import com.obsidianscout.auth.EmailService
import com.obsidianscout.db.ApiMatches
import com.obsidianscout.db.ApiTeams
import com.obsidianscout.db.PushNotificationService
import com.obsidianscout.db.ScoutingAssignments
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import com.obsidianscout.integrations.SettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

object ScoutingReminderScheduler {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    fun start() {
        if (job != null && job?.isActive == true) return
        job = scope.launch {
            println("[ScoutingReminderScheduler] Background reminder scheduler started.")
            while (isActive) {
                try {
                    checkAndSendReminders()
                } catch (e: Exception) {
                    println("[ScoutingReminderScheduler] Error running reminder sweep: ${e.message}")
                }
                delay(60_000) // Run every 60 seconds
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        println("[ScoutingReminderScheduler] Background reminder scheduler stopped.")
    }

    fun checkAndSendReminders() {
        val now = Instant.now()
        val nowEpochSec = now.epochSecond

        // 1. Fetch pending assignments with a matchKey
        val pendingAssignments = readTransaction {
            ScoutingAssignments.selectAll().where {
                (ScoutingAssignments.status eq "PENDING") and
                (ScoutingAssignments.matchKey.isNotNull())
            }.toList()
        }

        if (pendingAssignments.isEmpty()) return

        val matchKeys = pendingAssignments.mapNotNull { it[ScoutingAssignments.matchKey] }.distinct()
        val matchesMap = readTransaction {
            ApiMatches.selectAll().where { ApiMatches.matchKey inList matchKeys }
                .associateBy { it[ApiMatches.matchKey] }
        }

        val userIds = pendingAssignments.map { it[ScoutingAssignments.assignedUserId].value }.distinct()
        val usersMap = readTransaction {
            Users.selectAll().where { Users.id inList userIds }.associateBy { it[Users.id].value }
        }

        // Cache team settings by (teamNumber, program)
        val teamSettingsMap = mutableMapOf<Pair<Int, String>, com.obsidianscout.integrations.ApiSettings>()

        for (assignment in pendingAssignments) {
            val mKey = assignment[ScoutingAssignments.matchKey] ?: continue
            val matchRow = matchesMap[mKey] ?: continue
            val scheduledTimeSec = matchRow[ApiMatches.scheduledTime] ?: continue

            // Don't remind for matches older than 15 minutes or more than 2 hours in future
            val diffSec = scheduledTimeSec - nowEpochSec
            if (diffSec < -900 || diffSec > 7200) continue

            val teamNumber = assignment[ScoutingAssignments.ownerTeamNumber]
            val program = assignment[ScoutingAssignments.program]
            val settings = teamSettingsMap.getOrPut(Pair(teamNumber, program)) {
                SettingsService.getSettings(teamNumber, program)
            }

            val reminderMinutes = assignment[ScoutingAssignments.reminderMinutesBefore] ?: settings.assignmentReminderMinutes
            val reminderWindowSec = reminderMinutes * 60L

            // Trigger when within the reminder window before match scheduled start
            if (diffSec <= reminderWindowSec && diffSec > -300) {
                val userUuid = assignment[ScoutingAssignments.assignedUserId].value
                val userRow = usersMap[userUuid] ?: continue
                val assignmentId = assignment[ScoutingAssignments.id].value

                val pushSent = assignment[ScoutingAssignments.pushReminderSentAt] != null
                val emailSent = assignment[ScoutingAssignments.emailReminderSentAt] != null

                val matchNumber = assignment[ScoutingAssignments.matchNumber] ?: matchRow[ApiMatches.matchNumber] ?: 0
                val targetTeam = assignment[ScoutingAssignments.targetTeamNumber]
                val type = assignment[ScoutingAssignments.assignmentType]

                val title = when (type) {
                    "MATCH" -> "Match #$matchNumber starting soon!"
                    "QUALITATIVE" -> "Qualitative Scouting Match #$matchNumber"
                    else -> "Scouting Reminder"
                }

                val teamDetail = if (targetTeam != null) "Team $targetTeam" else "your assigned team"
                val body = "You are assigned to scout $teamDetail in Match #$matchNumber in approx ${maxOf(1, diffSec / 60)} min(s)."
                val scoutUrl = when (type) {
                    "MATCH" -> "/scout?matchKey=$mKey&matchNumber=$matchNumber${if (targetTeam != null) "&team=$targetTeam" else ""}&event=${assignment[ScoutingAssignments.eventKey]}"
                    "QUALITATIVE" -> "/qual-scout?matchKey=$mKey&matchNumber=$matchNumber${if (targetTeam != null) "&team=$targetTeam" else ""}&event=${assignment[ScoutingAssignments.eventKey]}"
                    else -> "/my-assignments"
                }

                var newlyPushSent = false
                var newlyEmailSent = false

                // Push reminder
                if (!pushSent && settings.enableAssignmentPushReminders) {
                    try {
                        PushNotificationService.sendAssignmentReminder(
                            userId = userUuid,
                            title = title,
                            body = body,
                            url = scoutUrl,
                            tag = "reminder-match-$matchNumber"
                        )
                        newlyPushSent = true
                    } catch (e: Exception) {
                        println("[ScoutingReminderScheduler] Failed to send push reminder for assignment $assignmentId: ${e.message}")
                    }
                }

                // Email reminder
                if (!emailSent && settings.enableAssignmentEmailReminders) {
                    val userEmail = userRow[Users.email]
                    if (!userEmail.isNullOrBlank()) {
                        try {
                            val baseUrl = "http://localhost:8080" // or system domain
                            EmailService.sendAssignmentReminderEmail(
                                to = userEmail,
                                username = userRow[Users.username],
                                teamNumber = teamNumber,
                                title = title,
                                details = body,
                                actionUrl = "$baseUrl$scoutUrl"
                            )
                            newlyEmailSent = true
                        } catch (e: Exception) {
                            println("[ScoutingReminderScheduler] Failed to send email reminder for assignment $assignmentId: ${e.message}")
                        }
                    }
                }

                if (newlyPushSent || newlyEmailSent) {
                    transaction {
                        ScoutingAssignments.update({ ScoutingAssignments.id eq assignmentId }) {
                            if (newlyPushSent) it[pushReminderSentAt] = now
                            if (newlyEmailSent) it[emailReminderSentAt] = now
                            it[updatedAt] = now
                        }
                    }
                    println("[ScoutingReminderScheduler] Sent reminders for assignment $assignmentId to user ${userRow[Users.username]} for Match #$matchNumber")
                }
            }
        }
    }
}
