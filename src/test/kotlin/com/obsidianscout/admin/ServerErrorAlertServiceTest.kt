package com.obsidianscout.admin

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.AuthService
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.db.AppSettings
import com.obsidianscout.db.ClusterNotificationLocks
import com.obsidianscout.db.ReportedErrors
import com.obsidianscout.db.Users
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.routes.ClientBugReportRequest
import com.obsidianscout.routes.ErrorAlertSettings
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.BadRequestException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.time.Instant
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import kotlin.test.*

class ServerErrorAlertServiceTest {

    private val testDbFile = File("build/test_error_alerts_${System.currentTimeMillis()}.db")

    @BeforeTest
    fun setUp() {
        testDbFile.parentFile?.mkdirs()
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
        Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Users, AppSettings, ClusterNotificationLocks, ReportedErrors)
        }
    }

    @AfterTest
    fun tearDown() {
        if (testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testIsIgnorableNetworkOrClientError() {
        // Ignorable client/network/sync errors
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(ApiException(HttpStatusCode.BadRequest, "Sync error")))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(NotFoundException("Not found")))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(BadRequestException("Bad input")))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(ClosedChannelException()))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(SSLException("SSL handshake aborted")))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(CancellationException("Job cancelled")))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(IOException("Connection reset by peer")))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(IOException("Broken pipe")))

        // Compile / build classpath "class not found" errors MUST be ignored
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(ClassNotFoundException("com.example.MissingCompileDep")))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(NoClassDefFoundError("com/example/MissingCompileDep")))
        assertTrue(ServerErrorAlertService.isIgnorableNetworkOrClientError(RuntimeException("Dependency resolution failure", ClassNotFoundException("com.example.MissingCompileDep"))))

        // GraalVM native image missing reflection errors MUST NOT be ignored
        val graalReflectionMsgError = ClassNotFoundException("Class com.example.ReflectiveModel was not registered in the reflection configuration.")
        assertFalse(ServerErrorAlertService.isIgnorableNetworkOrClientError(graalReflectionMsgError))

        val graalDynamicLoadMsgError = ClassNotFoundException("Classes cannot be loaded dynamically at runtime in native-image")
        assertFalse(ServerErrorAlertService.isIgnorableNetworkOrClientError(graalDynamicLoadMsgError))

        val graalStackError = ClassNotFoundException("com.example.NativeModel").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.oracle.svm.core.hub.ClassForNameSupport", "forName", "ClassForNameSupport.java", 55),
                StackTraceElement("com.obsidianscout.ApplicationKt", "main", "Application.kt", 10)
            )
        }
        assertFalse(ServerErrorAlertService.isIgnorableNetworkOrClientError(graalStackError))

        val wrappedGraalError = RuntimeException("Reflection invocation failed", ClassNotFoundException("Class com.example.Service was not registered in the reflection configuration."))
        assertFalse(ServerErrorAlertService.isIgnorableNetworkOrClientError(wrappedGraalError))

        // Real code errors MUST NOT be ignored
        assertFalse(ServerErrorAlertService.isIgnorableNetworkOrClientError(NullPointerException("Unexpected null reference")))
        assertFalse(ServerErrorAlertService.isIgnorableNetworkOrClientError(IllegalStateException("Internal server state corrupt")))
        assertFalse(ServerErrorAlertService.isIgnorableNetworkOrClientError(IndexOutOfBoundsException("Index 10 out of bounds")))
        assertFalse(ServerErrorAlertService.isIgnorableNetworkOrClientError(RuntimeException("Custom business logic exception")))
    }

    @Test
    fun testClusterSettingsPersistenceAndRetrieval() {
        // Initial settings should default to emailServerErrors = false
        val initial = SettingsService.getErrorAlertSettings()
        assertFalse(initial.emailServerErrors, "Default error alerting should be disabled")

        // Update settings
        val updated = SettingsService.updateErrorAlertSettings(
            ErrorAlertSettings(
                emailServerErrors = true,
                additionalEmails = listOf("alerts@example.com")
            )
        )
        assertTrue(updated.emailServerErrors, "Error alerting should be enabled")
        assertEquals(listOf("alerts@example.com"), updated.additionalEmails)

        // Read again to verify persistence
        val fetched = SettingsService.getErrorAlertSettings()
        assertTrue(fetched.emailServerErrors, "Persisted error alerting setting should be enabled")
        assertEquals(listOf("alerts@example.com"), fetched.additionalEmails)
    }

    @Test
    fun testGetEnrolledSuperadminMailingList() {
        transaction { Users.deleteAll() }

        val superCaller = UserSession("seed", "superadmin", 0, "FRC", UserRole.SUPERADMIN)

        // Create regular user
        val regularUser = transaction {
            AuthService.createUser(
                callerSession = superCaller,
                username = "regular_scout",
                teamNumber = 100,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SCOUT,
                email = "scout@example.com"
            )
        }

        // Create superadmin without email
        val superNoEmail = transaction {
            AuthService.createUser(
                callerSession = superCaller,
                username = "super_no_email",
                teamNumber = 0,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SUPERADMIN,
                email = null
            )
        }
        AuthService.updateUser(
            callerSession = superCaller,
            targetUserId = superNoEmail.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newNodeAlertsEnabled = true
        )

        // Create superadmin with email but alerts disabled
        val superAlertsDisabled = transaction {
            AuthService.createUser(
                callerSession = superCaller,
                username = "super_alerts_off",
                teamNumber = 0,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SUPERADMIN,
                email = "super_off@example.com"
            )
        }

        // Create enrolled superadmin
        val superEnrolled = transaction {
            AuthService.createUser(
                callerSession = superCaller,
                username = "super_enrolled",
                teamNumber = 0,
                password = "Password123!",
                program = "FRC",
                role = UserRole.SUPERADMIN,
                email = "super_on@example.com"
            )
        }
        AuthService.updateUser(
            callerSession = superCaller,
            targetUserId = superEnrolled.id,
            newUsername = null,
            newPassword = null,
            newRole = null,
            newNodeAlertsEnabled = true
        )

        val recipients = ServerErrorAlertService.getEnrolledSuperadminEmails()
        assertEquals(1, recipients.size)
        assertEquals("super_on@example.com", recipients.first())
    }

    @Test
    fun testClientBugReportRecording() {
        val report = ClientBugReportRequest(
            errorMessage = "TypeError: Cannot read properties of undefined (reading 'map')",
            errorStack = "TypeError: Cannot read properties of undefined\n    at renderList (dashboard.js:42)",
            errorUrl = "http://localhost:8080/dashboard",
            teamNumber = 254,
            program = "FRC",
            userRole = "SUPERADMIN"
        )

        // Verify recordClientBugReport does not throw
        ServerErrorAlertService.recordClientBugReport(report, session = null)
    }

    @Test
    fun testReportedErrorsListAndLifecycle() {
        transaction { ReportedErrors.deleteAll() }

        // Insert mock server error and client js error
        val serverErrorId = transaction {
            ReportedErrors.insert {
                it[errorType] = "SERVER"
                it[errorMessage] = "NullPointerException: target object was null"
                it[errorStack] = "java.lang.NullPointerException\n\tat com.example.Foo.bar(Foo.kt:10)"
                it[requestDetails] = "GET /api/scouting/entries"
                it[clientIp] = "192.168.1.50"
                it[teamNumber] = 254
                it[program] = "FRC"
                it[userRole] = "ADMIN"
                it[username] = "lead_scout"
                it[status] = "OPEN"
                it[createdAt] = Instant.now()
            }[ReportedErrors.id].value.toString()
        }

        val clientErrorId = transaction {
            ReportedErrors.insert {
                it[errorType] = "CLIENT_JS"
                it[errorMessage] = "ReferenceError: chartInstance is not defined"
                it[errorStack] = "ReferenceError: chartInstance is not defined\n\tat graphs.js:150"
                it[requestDetails] = "http://localhost:8080/graphs"
                it[clientIp] = "Client (web)"
                it[teamNumber] = 1678
                it[program] = "FRC"
                it[userRole] = "SCOUT"
                it[username] = "citrus_scouter"
                it[status] = "OPEN"
                it[createdAt] = Instant.now()
            }[ReportedErrors.id].value.toString()
        }

        // Test stats
        val stats = ServerErrorAlertService.getReportedErrorStats()
        assertEquals(2, stats.totalCount)
        assertEquals(2, stats.openCount)
        assertEquals(0, stats.resolvedCount)
        assertEquals(1, stats.serverCount)
        assertEquals(1, stats.clientCount)

        // Test list with filters
        val serverList = ServerErrorAlertService.listReportedErrors(typeFilter = "SERVER", statusFilter = "OPEN")
        assertEquals(1, serverList.errors.size)
        assertEquals(serverErrorId, serverList.errors.first().id)
        assertEquals("SERVER", serverList.errors.first().errorType)

        val clientList = ServerErrorAlertService.listReportedErrors(typeFilter = "CLIENT_JS", statusFilter = "OPEN")
        assertEquals(1, clientList.errors.size)
        assertEquals(clientErrorId, clientList.errors.first().id)
        assertEquals("CLIENT_JS", clientList.errors.first().errorType)

        // Test search filter
        val searchList = ServerErrorAlertService.listReportedErrors(search = "chartInstance")
        assertEquals(1, searchList.errors.size)
        assertEquals(clientErrorId, searchList.errors.first().id)

        // Test update status to RESOLVED
        val updateSuccess = ServerErrorAlertService.updateReportedErrorStatus(
            id = serverErrorId,
            newStatus = "RESOLVED",
            resolvedByUsername = "superadmin"
        )
        assertTrue(updateSuccess)

        val updatedStats = ServerErrorAlertService.getReportedErrorStats()
        assertEquals(1, updatedStats.openCount)
        assertEquals(1, updatedStats.resolvedCount)

        // Test delete error
        val deleteSuccess = ServerErrorAlertService.deleteReportedError(clientErrorId)
        assertTrue(deleteSuccess)

        val finalStats = ServerErrorAlertService.getReportedErrorStats()
        assertEquals(1, finalStats.totalCount)

        // Test clear
        val cleared = ServerErrorAlertService.clearReportedErrors(statusFilter = "ALL")
        assertEquals(1, cleared)
        assertEquals(0, ServerErrorAlertService.getReportedErrorStats().totalCount)
    }

    @Test
    fun testRecordServerError() {
        transaction { ReportedErrors.deleteAll() }

        ServerErrorAlertService.recordServerError(
            errorMessage = "Database schema migration error: Failed to add column 'test_col' to table 'users'",
            cause = RuntimeException("null value in column violates not-null constraint"),
            requestDetails = "Table: users, Column: test_col, DDL: ALTER TABLE users ADD COLUMN test_col VARCHAR(16) NOT NULL",
            errorType = "SERVER",
            sync = true
        )

        val stats = ServerErrorAlertService.getReportedErrorStats()
        assertEquals(1, stats.totalCount)
        assertEquals(1, stats.openCount)
        assertEquals(1, stats.serverCount)

        val list = ServerErrorAlertService.listReportedErrors(typeFilter = "SERVER", statusFilter = "OPEN")
        assertEquals(1, list.errors.size)
        val err = list.errors.first()
        assertEquals("Database schema migration error: Failed to add column 'test_col' to table 'users'", err.errorMessage)
        assertEquals("SERVER", err.errorType)
        assertEquals("OPEN", err.status)
        assertTrue(err.errorStack?.contains("not-null constraint") == true)
        assertEquals("Table: users, Column: test_col, DDL: ALTER TABLE users ADD COLUMN test_col VARCHAR(16) NOT NULL", err.requestDetails)
    }
}
