package com.obsidianscout.admin

import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NodeMonitoringServiceTest {

    private val testDbFile = File("build/test_node_monitoring_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, com.obsidianscout.db.ClusterNotificationLocks)
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testSuperAdminEnrollmentSetting() {
        val superAdmin = transaction {
            Users.deleteAll()
            AuthService.createUser(
                callerSession = UserSession("seed", "superadmin", 0, "FRC", UserRole.SUPERADMIN),
                username = "super_test_user",
                teamNumber = 254,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SUPERADMIN,
                email = "superadmin@example.com"
            )
        }

        assertFalse(superAdmin.nodeAlertsEnabled, "Default nodeAlertsEnabled should be false")

        val superSession = UserSession(superAdmin.id, superAdmin.username, superAdmin.teamNumber, superAdmin.program, superAdmin.role)

        val updated = AuthService.updateUser(
            callerSession = superSession,
            targetUserId = superAdmin.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newNodeAlertsEnabled = true
        )

        assertTrue(updated.nodeAlertsEnabled, "nodeAlertsEnabled should be updated to true")

        val reFetched = AuthService.getUserById(superAdmin.id)
        assertNotNull(reFetched)
        assertTrue(reFetched.nodeAlertsEnabled, "Persisted nodeAlertsEnabled should be true")
    }

    @Test
    fun testHasEnrolledSuperadminsOptimization() {
        transaction { Users.deleteAll() }
        assertFalse(NodeMonitoringService.hasEnrolledSuperadmins(), "Should return false when no superadmins are enrolled")

        val superAdmin = transaction {
            AuthService.createUser(
                callerSession = UserSession("seed", "superadmin", 0, "FRC", UserRole.SUPERADMIN),
                username = "super_enrolled_user",
                teamNumber = 254,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SUPERADMIN,
                email = "superadmin@example.com"
            )
        }

        assertFalse(NodeMonitoringService.hasEnrolledSuperadmins(), "Should return false before superadmin enables alerts")

        AuthService.updateUser(
            callerSession = UserSession(superAdmin.id, superAdmin.username, superAdmin.teamNumber, superAdmin.program, superAdmin.role),
            targetUserId = superAdmin.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newNodeAlertsEnabled = true
        )

        assertTrue(NodeMonitoringService.hasEnrolledSuperadmins(), "Should return true after superadmin enables alerts")
    }

    @Test
    fun testNotificationLockDeduplication() {
        val testLockKey = "node_down_node-peer-100.64.0.5"

        // 1. First server node claims lock — should succeed
        val firstClaim = NodeMonitoringService.claimNotificationLock(testLockKey, lockDurationMinutes = 60L)
        assertTrue(firstClaim, "First cluster server should successfully claim notification lock")

        // 2. Second server node attempts to claim same lock — should fail (suppressing duplicate notification)
        val secondClaim = NodeMonitoringService.claimNotificationLock(testLockKey, lockDurationMinutes = 60L)
        assertFalse(secondClaim, "Second cluster server should be blocked from duplicate notification dispatch")
    }

    @Test
    fun testRecoveryNotificationLockDeduplication() {
        val recoveryLockKey = "node_online_node-peer-100.64.0.5"

        val firstClaim = NodeMonitoringService.claimNotificationLock(recoveryLockKey, lockDurationMinutes = 60L)
        assertTrue(firstClaim, "First cluster server should claim recovery notification lock")

        val secondClaim = NodeMonitoringService.claimNotificationLock(recoveryLockKey, lockDurationMinutes = 60L)
        assertFalse(secondClaim, "Second cluster server should be blocked from sending duplicate recovery notification")
    }

    @Test
    fun testVerifyNodeDownWithPeersWhenNoPeersAvailable() {
        val targetNode = ClusterNodeInfo(
            nodeId = "node-peer-100.64.0.5",
            ip = "100.64.0.5",
            dbPort = 26257,
            appPort = 8080,
            isLocal = false,
            status = "offline",
            isDbActive = false
        )
        val nodesList = listOf(
            ClusterNodeInfo(
                nodeId = "node-local-100.64.0.1",
                ip = "100.64.0.1",
                dbPort = 26257,
                appPort = 8080,
                isLocal = true,
                status = "online",
                isDbActive = true
            ),
            targetNode
        )

        val confirmed = NodeMonitoringService.verifyNodeDownWithPeers(targetNode, nodesList)
        assertTrue(confirmed, "Should confirm node down when no other online peer nodes exist to cross-check")
    }
}
