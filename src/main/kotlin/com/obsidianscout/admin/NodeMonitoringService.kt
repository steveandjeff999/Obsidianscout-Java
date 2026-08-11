package com.obsidianscout.admin

import com.obsidianscout.auth.EmailService
import com.obsidianscout.auth.UserRole
import com.obsidianscout.db.FcmService
import com.obsidianscout.db.Users
import com.obsidianscout.integrations.SettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.obsidianscout.db.ClusterNotificationLocks
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object NodeMonitoringService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null
    private val previousNodeStatuses = ConcurrentHashMap<String, String>()
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()

    @Volatile
    var isMonitoringActive: Boolean = false
        private set

    @Synchronized
    fun start(intervalMs: Long = 30_000L) {
        if (monitoringJob?.isActive == true) return

        isMonitoringActive = true
        ServerLogService.appendLog("INFO", "NodeMonitoringService", "Cluster Node Health Monitor started (check interval: ${intervalMs / 1000}s).")

        monitoringJob = scope.launch {
            // Give server initial boot time before first health sweep
            delay(60_000L)
            while (isActive) {
                try {
                    checkNodeHealth()
                } catch (e: Exception) {
                    ServerLogService.appendLog("ERROR", "NodeMonitoringService", "Error during cluster node health check: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        monitoringJob?.cancel()
        monitoringJob = null
        isMonitoringActive = false
        ServerLogService.appendLog("INFO", "NodeMonitoringService", "Cluster Node Health Monitor stopped.")
    }

    fun hasEnrolledSuperadmins(): Boolean {
        return try {
            transaction {
                Users.selectAll()
                    .where { (Users.role eq UserRole.SUPERADMIN.name) and (Users.nodeAlertsEnabled eq true) }
                    .any()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Attempts to acquire an atomic cluster notification lock in shared database.
     * Returns true if lock was successfully claimed by this node.
     * Returns false if an active (unexpired) lock key exists, preventing duplicate multi-node alerts.
     */
    fun claimNotificationLock(lockKey: String, lockDurationMinutes: Long = 60L): Boolean {
        val now = Instant.now()
        val expires = now.plusSeconds(lockDurationMinutes * 60L)
        val localIp = ClusterManagementService.getLocalTailscaleIp()

        return try {
            transaction {
                val existing = ClusterNotificationLocks.selectAll()
                    .where { ClusterNotificationLocks.lockKey eq lockKey }
                    .firstOrNull()

                if (existing != null) {
                    val currentExpires = existing[ClusterNotificationLocks.expiresAt]
                    if (currentExpires.isAfter(now)) {
                        val claimedBy = existing[ClusterNotificationLocks.claimedByNode]
                        ServerLogService.appendLog("INFO", "NodeMonitoringService", "Notification lock '$lockKey' already claimed by node $claimedBy (expires $currentExpires). Skipping duplicate dispatch.")
                        return@transaction false
                    }
                    // Lock expired — update and claim
                    ClusterNotificationLocks.update({ ClusterNotificationLocks.lockKey eq lockKey }) {
                        it[claimedByNode] = localIp
                        it[claimedAt] = now
                        it[expiresAt] = expires
                    }
                    true
                } else {
                    // New lock — insert and claim
                    ClusterNotificationLocks.insert {
                        it[ClusterNotificationLocks.lockKey] = lockKey
                        it[claimedByNode] = localIp
                        it[claimedAt] = now
                        it[expiresAt] = expires
                    }
                    true
                }
            }
        } catch (e: Exception) {
            ServerLogService.appendLog("INFO", "NodeMonitoringService", "Notification lock '$lockKey' race condition hit. Skipping duplicate dispatch.")
            false
        }
    }

    private suspend fun checkNodeHealth() {
        // Resource optimization: Skip node health sweeps if no superadmins are enrolled for alerts
        if (!hasEnrolledSuperadmins()) {
            previousNodeStatuses.clear()
            consecutiveFailures.clear()
            return
        }

        val cluster = try {
            ClusterManagementService.getClusterNodes()
        } catch (e: Exception) {
            ServerLogService.appendLog("WARN", "NodeMonitoringService", "Failed to retrieve cluster nodes: ${e.message}")
            return
        }

        for (node in cluster.nodes) {
            val prevStatus = previousNodeStatuses[node.nodeId]
            val currentStatus = node.status

            if (currentStatus == "offline") {
                val failCount = (consecutiveFailures[node.nodeId] ?: 0) + 1
                consecutiveFailures[node.nodeId] = failCount

                // Strict False-Positive Guard:
                // Require node to have previously been recorded as "online", AND fail 3 consecutive checks (90s)
                if (failCount == 3 && prevStatus == "online") {
                    val isConfirmedByPeers = verifyNodeDownWithPeers(node, cluster.nodes)
                    if (isConfirmedByPeers) {
                        ServerLogService.appendLog("WARN", "NodeMonitoringService", "Confirmed Node DOWN by cluster peer consensus! Node ${node.nodeId} (${node.ip}) failed 3 consecutive health checks.")
                        dispatchNodeDownAlert(node)
                        previousNodeStatuses[node.nodeId] = "offline"
                    } else {
                        // Peer node confirmed node is alive — reset fail count so local network glitch doesn't trigger false alert
                        consecutiveFailures[node.nodeId] = 0
                    }
                } else if (prevStatus == null) {
                    // Record initial baseline status without alerting on pre-existing offline nodes
                    previousNodeStatuses[node.nodeId] = "offline"
                }
            } else {
                // Node is online/booting
                if (prevStatus == "offline") {
                    ServerLogService.appendLog("INFO", "NodeMonitoringService", "Node RECOVERED! Node ${node.nodeId} (${node.ip}) is back online.")
                    dispatchNodeBackOnlineAlert(node)
                }
                // Reset failure counter and update status for online/booting nodes
                consecutiveFailures[node.nodeId] = 0
                previousNodeStatuses[node.nodeId] = currentStatus
            }
        }
    }

    /**
     * Cross-verifies with other online peer nodes in the cluster before declaring a node DOWN.
     * Returns true if peer consensus confirms the node is DOWN (or no other online peers exist).
     * Returns false if another online peer confirms the node is ONLINE (indicating a local network issue on this node).
     */
    fun verifyNodeDownWithPeers(targetNode: ClusterNodeInfo, clusterNodes: List<ClusterNodeInfo>): Boolean {
        val onlinePeers = clusterNodes.filter { peer ->
            !peer.isLocal && peer.ip != targetNode.ip && peer.status == "online"
        }

        if (onlinePeers.isEmpty()) {
            return true
        }

        ServerLogService.appendLog("INFO", "NodeMonitoringService", "Cross-verifying failure of Node ${targetNode.ip} with ${onlinePeers.size} online peer(s)...")

        for (peer in onlinePeers) {
            val peerViewIsOnline = ClusterManagementService.checkNodeResponsiveFromPeerNode(
                peerIp = peer.ip,
                targetIp = targetNode.ip,
                appPort = targetNode.appPort,
                dbPort = targetNode.dbPort
            )

            if (peerViewIsOnline == true) {
                ServerLogService.appendLog(
                    "WARN",
                    "NodeMonitoringService",
                    "Suppressed false Node DOWN alert for ${targetNode.ip}: Unreachable locally, but peer node ${peer.ip} confirmed Node ${targetNode.ip} is ONLINE!"
                )
                return false
            }
        }

        return true
    }

    fun dispatchNodeDownAlert(node: ClusterNodeInfo) {
        val lockKey = "node_down_${node.nodeId}"
        if (!claimNotificationLock(lockKey)) {
            return
        }
        // Find enrolled SUPERADMIN users
        val (enrolledUuids, enrolledEmails) = transaction {
            val rows = Users.selectAll()
                .where { (Users.role eq UserRole.SUPERADMIN.name) and (Users.nodeAlertsEnabled eq true) }
                .toList()
            val uuids = rows.map { it[Users.id].value }
            val emails = rows.mapNotNull { it[Users.email]?.takeIf { e -> e.isNotBlank() } }
            Pair(uuids, emails)
        }

        if (enrolledUuids.isEmpty()) {
            ServerLogService.appendLog("INFO", "NodeMonitoringService", "Node ${node.ip} went down, but no SUPERADMIN users are enrolled for node alerts.")
            return
        }

        ServerLogService.appendLog("WARN", "NodeMonitoringService", "Dispatching Node Down FCM & Email alerts to ${enrolledUuids.size} enrolled superadmin(s).")

        // 1. FCM Push Notifications
        val fcmTitle = "🚨 Cluster Node Down Alert"
        val fcmBody = "Node ${node.ip} (${node.nodeId}) is offline or unreachable."
        try {
            FcmService.sendNotificationToUsers(
                targetUserUuids = enrolledUuids,
                title = fcmTitle,
                body = fcmBody,
                groupName = "cluster-alerts",
                url = "/cluster-management"
            )
        } catch (e: Exception) {
            ServerLogService.appendLog("ERROR", "NodeMonitoringService", "Failed to dispatch FCM push notification for node down: ${e.message}")
        }

        // 2. Email Notifications
        if (enrolledEmails.isNotEmpty()) {
            val smtpConfigured = try {
                SettingsService.getSmtpSettings().host.isNotBlank()
            } catch (e: Exception) {
                false
            }

            if (smtpConfigured) {
                val emailSubject = "🚨 [ObsidianScout Alert] Cluster Node Down: ${node.ip}"
                val emailHtml = """
                    <html>
                    <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; background-color: #0f172a; padding: 20px;">
                        <div style="max-width: 600px; margin: 0 auto; background: #1e293b; border: 1px solid #ef4444; border-radius: 12px; padding: 24px; color: #f8fafc;">
                            <h2 style="color: #ef4444; border-bottom: 2px solid #334155; padding-bottom: 12px; margin-top: 0;">
                                🚨 Cluster Node Offline Alert
                            </h2>
                            <p style="font-size: 16px;">
                                Node <strong>${node.ip}</strong> (${node.nodeId}) on port <strong>${node.appPort}</strong> has gone <strong>OFFLINE</strong> or is unreachable.
                            </p>
                            <table style="width: 100%; border-collapse: collapse; margin: 20px 0; background: #0f172a; border-radius: 8px; overflow: hidden;">
                                <tr style="border-bottom: 1px solid #334155;">
                                    <td style="padding: 10px 16px; color: #94a3b8; font-weight: bold;">Node ID</td>
                                    <td style="padding: 10px 16px; color: #f1f5f9; font-family: monospace;">${node.nodeId}</td>
                                </tr>
                                <tr style="border-bottom: 1px solid #334155;">
                                    <td style="padding: 10px 16px; color: #94a3b8; font-weight: bold;">IP Address</td>
                                    <td style="padding: 10px 16px; color: #f1f5f9; font-family: monospace;">${node.ip}</td>
                                </tr>
                                <tr style="border-bottom: 1px solid #334155;">
                                    <td style="padding: 10px 16px; color: #94a3b8; font-weight: bold;">DB Port</td>
                                    <td style="padding: 10px 16px; color: #f1f5f9; font-family: monospace;">${node.dbPort}</td>
                                </tr>
                                <tr>
                                    <td style="padding: 10px 16px; color: #94a3b8; font-weight: bold;">Status</td>
                                    <td style="padding: 10px 16px; color: #ef4444; font-weight: bold;">OFFLINE</td>
                                </tr>
                            </table>
                            <p style="text-align: center; margin: 30px 0;">
                                <a href="/cluster-management" style="background-color: #ef4444; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: bold; display: inline-block;">Open Cluster Management UI</a>
                            </p>
                            <p style="color: #64748b; font-size: 13px; margin-top: 24px; border-top: 1px solid #334155; padding-top: 12px;">
                                You are receiving this automated alert because you are enrolled in ObsidianScout Superadmin Cluster Health Notifications.
                            </p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()

                for (toEmail in enrolledEmails) {
                    try {
                        EmailService.sendEmail(toEmail, emailSubject, emailHtml)
                        ServerLogService.appendLog("INFO", "NodeMonitoringService", "Sent Node Down alert email to $toEmail.")
                    } catch (e: Exception) {
                        ServerLogService.appendLog("ERROR", "NodeMonitoringService", "Failed to send Node Down alert email to $toEmail: ${e.message}")
                    }
                }
            } else {
                ServerLogService.appendLog("WARN", "NodeMonitoringService", "SMTP is not configured. Node Down email notification was skipped.")
            }
        }
    }

    fun dispatchNodeBackOnlineAlert(node: ClusterNodeInfo) {
        val lockKey = "node_online_${node.nodeId}"
        if (!claimNotificationLock(lockKey)) {
            return
        }
        // Find enrolled SUPERADMIN users
        val (enrolledUuids, enrolledEmails) = transaction {
            val rows = Users.selectAll()
                .where { (Users.role eq UserRole.SUPERADMIN.name) and (Users.nodeAlertsEnabled eq true) }
                .toList()
            val uuids = rows.map { it[Users.id].value }
            val emails = rows.mapNotNull { it[Users.email]?.takeIf { e -> e.isNotBlank() } }
            Pair(uuids, emails)
        }

        if (enrolledUuids.isEmpty()) {
            ServerLogService.appendLog("INFO", "NodeMonitoringService", "Node ${node.ip} recovered, but no SUPERADMIN users are enrolled for node alerts.")
            return
        }

        ServerLogService.appendLog("INFO", "NodeMonitoringService", "Dispatching Node Recovered FCM & Email alerts to ${enrolledUuids.size} enrolled superadmin(s).")

        // 1. FCM Push Notifications
        val fcmTitle = "🟢 Cluster Node Recovered"
        val fcmBody = "Node ${node.ip} (${node.nodeId}) is back online and healthy."
        try {
            FcmService.sendNotificationToUsers(
                targetUserUuids = enrolledUuids,
                title = fcmTitle,
                body = fcmBody,
                groupName = "cluster-alerts",
                url = "/cluster-management"
            )
        } catch (e: Exception) {
            ServerLogService.appendLog("ERROR", "NodeMonitoringService", "Failed to dispatch FCM push notification for node recovery: ${e.message}")
        }

        // 2. Email Notifications
        val smtpSettings = SettingsService.getSmtpSettings()
        if (smtpSettings.host.isNotBlank() && enrolledEmails.isNotEmpty()) {
            val emailSubject = "🟢 [RECOVERED] Cluster Node Back Online: Node ${node.ip}"
            val emailHtml = """
                <!DOCTYPE html>
                <html>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; background-color: #0f172a; padding: 20px;">
                    <div style="max-width: 600px; margin: 0 auto; background: #1e293b; border: 1px solid #22c55e; border-radius: 12px; padding: 24px; color: #f8fafc;">
                        <h2 style="color: #4ade80; border-bottom: 2px solid #334155; padding-bottom: 12px; margin-top: 0;">
                            🟢 Cluster Node Recovered
                        </h2>
                        <p style="font-size: 16px;">
                            Cluster node <strong>${node.nodeId}</strong> at IP <strong>${node.ip}</strong> has recovered and is back online.
                        </p>
                        <table style="width: 100%; border-collapse: collapse; margin: 20px 0; background: #0f172a; border-radius: 8px; overflow: hidden;">
                            <tr style="border-bottom: 1px solid #334155;">
                                <td style="padding: 10px 16px; color: #94a3b8; font-weight: bold;">Node ID</td>
                                <td style="padding: 10px 16px; color: #f8fafc;">${node.nodeId}</td>
                            </tr>
                            <tr style="border-bottom: 1px solid #334155;">
                                <td style="padding: 10px 16px; color: #94a3b8; font-weight: bold;">IP Address</td>
                                <td style="padding: 10px 16px; color: #f8fafc;">${node.ip}</td>
                            </tr>
                            <tr>
                                <td style="padding: 10px 16px; color: #94a3b8; font-weight: bold;">Status</td>
                                <td style="padding: 10px 16px; color: #4ade80; font-weight: bold;">ONLINE</td>
                            </tr>
                        </table>
                        <p style="text-align: center; margin: 30px 0;">
                            <a href="/cluster-management" style="background-color: #22c55e; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: bold; display: inline-block;">Open Cluster Management UI</a>
                        </p>
                        <p style="color: #64748b; font-size: 13px; margin-top: 24px; border-top: 1px solid #334155; padding-top: 12px;">
                            You are receiving this automated alert because you are enrolled in ObsidianScout Superadmin Cluster Health Notifications.
                        </p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            for (toEmail in enrolledEmails) {
                try {
                    EmailService.sendEmail(toEmail, emailSubject, emailHtml)
                    ServerLogService.appendLog("INFO", "NodeMonitoringService", "Sent Node Recovered alert email to $toEmail.")
                } catch (e: Exception) {
                    ServerLogService.appendLog("ERROR", "NodeMonitoringService", "Failed to send Node Recovered alert email to $toEmail: ${e.message}")
                }
            }
        } else {
            ServerLogService.appendLog("WARN", "NodeMonitoringService", "SMTP is not configured. Node Recovered email notification was skipped.")
        }
    }

    fun sendTestNodeDownAlert(superAdminUserId: UUID): Pair<Boolean, String> {
        val userRow = transaction {
            Users.selectAll().where { Users.id eq superAdminUserId }.firstOrNull()
        } ?: return Pair(false, "Superadmin user not found.")

        val userEmail = userRow[Users.email]
        val username = userRow[Users.username]

        var fcmSuccess = false
        var emailSuccess = false
        val logs = mutableListOf<String>()

        // 1. Send Test FCM Push
        val fcmTitle = "🧪 [TEST] Cluster Node Down Alert"
        val fcmBody = "This is a test node down alert for superadmin @$username. FCM Push Notifications are active!"
        try {
            FcmService.sendNotificationToUsers(
                targetUserUuids = listOf(superAdminUserId),
                title = fcmTitle,
                body = fcmBody,
                groupName = "cluster-alerts",
                url = "/cluster-management"
            )
            fcmSuccess = true
            logs.add("FCM Push notification dispatched to your registered device(s).")
        } catch (e: Exception) {
            logs.add("FCM Push dispatch error: ${e.message}")
        }

        // 2. Send Test Email Notification
        if (!userEmail.isNullOrBlank()) {
            val smtpConfigured = try {
                SettingsService.getSmtpSettings().host.isNotBlank()
            } catch (e: Exception) {
                false
            }

            if (smtpConfigured) {
                val emailSubject = "🧪 [TEST Alert] ObsidianScout Node Down Notification"
                val emailHtml = """
                    <html>
                    <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; background-color: #0f172a; padding: 20px;">
                        <div style="max-width: 600px; margin: 0 auto; background: #1e293b; border: 1px solid #3b82f6; border-radius: 12px; padding: 24px; color: #f8fafc;">
                            <h2 style="color: #60a5fa; border-bottom: 2px solid #334155; padding-bottom: 12px; margin-top: 0;">
                                🧪 Test Node Health Notification
                            </h2>
                            <p style="font-size: 16px;">
                                Hello <strong>$username</strong>, this is a test alert verifying your ObsidianScout Superadmin Cluster Node Down Notification enrollment!
                            </p>
                            <p style="color: #cbd5e1;">
                                If a CockroachDB or app cluster node transitions to <strong>OFFLINE</strong> status, you will immediately receive an alert like this via Email and FCM Push.
                            </p>
                            <p style="text-align: center; margin: 30px 0;">
                                <a href="/cluster-management" style="background-color: #3b82f6; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; font-weight: bold; display: inline-block;">Open Cluster Management</a>
                            </p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                try {
                    EmailService.sendEmail(userEmail, emailSubject, emailHtml)
                    emailSuccess = true
                    logs.add("Test Email successfully sent to $userEmail.")
                } catch (e: Exception) {
                    logs.add("Email dispatch failed: ${e.message}")
                }
            } else {
                logs.add("SMTP is not configured (Email skipped).")
            }
        } else {
            logs.add("Your account does not have a registered email address (Email skipped).")
        }

        val success = fcmSuccess || emailSuccess
        val summary = logs.joinToString(" ")
        return Pair(success, if (success) "Test alert sent! $summary" else "Test alert failed: $summary")
    }
}
