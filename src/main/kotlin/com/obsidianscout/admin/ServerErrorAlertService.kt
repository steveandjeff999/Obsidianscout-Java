package com.obsidianscout.admin

import com.obsidianscout.auth.EmailService
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.ReportedErrors
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.routes.ClientBugReportRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

object ServerErrorAlertService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

    /**
     * Rate-limiting cache: maps error signature -> epoch millisecond when alert was last sent.
     * Prevents duplicate emails when errors trigger repeatedly.
     */
    private val recentAlertSignatures = ConcurrentHashMap<String, Long>()
    private const val ALERT_COOLDOWN_MS = 60_000L // 1 minute per unique error signature

    /**
     * Determines whether an exception is a GraalVM Native Image missing reflection error.
     * In GraalVM native images, missing reflective metadata (e.g. from reflect-config.json)
     * causes ClassNotFoundException, NoSuchMethodException, NoSuchFieldException, or InstantiationException,
     * often containing SubstrateVM runtime packages (e.g. com.oracle.svm, org.graalvm.nativeimage)
     * or explicit messages regarding reflection configuration registration.
     */
    fun isGraalNativeReflectionError(cause: Throwable): Boolean {
        var current: Throwable? = cause
        while (current != null) {
            val msg = (current.message ?: "").lowercase()
            val stackStr = current.stackTraceToString().lowercase()

            // 1. Explicit message cues regarding reflection or native image class loading
            if (msg.contains("reflection") ||
                msg.contains("reflect-config") ||
                msg.contains("not registered in the reflection") ||
                msg.contains("missing reflection") ||
                msg.contains("native-image") ||
                msg.contains("native image") ||
                msg.contains("classes cannot be loaded dynamically at runtime")
            ) {
                return true
            }

            // 2. GraalVM SubstrateVM (SVM) runtime stack frames indicating reflection/class loading at runtime
            if (stackStr.contains("com.oracle.svm") ||
                stackStr.contains("org.graalvm.nativeimage") ||
                stackStr.contains("svm.reflect") ||
                stackStr.contains("classfornamesupport")
            ) {
                // If it occurred during class lookup or reflection in native image
                if (current is ClassNotFoundException ||
                    current is NoClassDefFoundError ||
                    current is NoSuchMethodException ||
                    current is NoSuchFieldException ||
                    msg.contains("reflection") ||
                    msg.contains("reflect") ||
                    stackStr.contains("reflect")
                ) {
                    return true
                }
            }

            // 3. Execution inside a GraalVM Native Image binary (imagecode system property)
            val isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null
            if (isNativeImage && (
                current is NoSuchMethodException ||
                current is NoSuchFieldException ||
                msg.contains("reflection") ||
                msg.contains("reflect") ||
                stackStr.contains("reflect") ||
                stackStr.contains("classforname")
            )) {
                return true
            }

            current = current.cause
        }
        return false
    }

    /**
     * Checks whether an exception is an expected network abort, client disconnect, SSL handshake error,
     * ignorable channel closure, or standard Gradle/JVM build/compile class-not-found error that is NOT
     * a server code defect (while preserving GraalVM native image missing reflection errors).
     */
    fun isIgnorableNetworkOrClientError(cause: Throwable): Boolean {
        // If it's a GraalVM native image missing reflection error, it represents a real server code defect
        // that must NOT be ignored.
        if (isGraalNativeReflectionError(cause)) {
            return false
        }

        var current: Throwable? = cause
        while (current != null) {
            // Standard compile/classpath "class not found" errors are ignored as Gradle compile/build issues
            if (current is ClassNotFoundException ||
                current is NoClassDefFoundError ||
                current is TypeNotPresentException
            ) {
                return true
            }

            if (current is SSLHandshakeException || current is SSLException) {
                return true
            }
            if (current is io.ktor.util.cio.ChannelWriteException ||
                current is java.nio.channels.ClosedChannelException ||
                current is kotlinx.coroutines.CancellationException
            ) {
                return true
            }
            if (current is java.io.IOException) {
                val msg = current.message?.lowercase() ?: ""
                if (msg.contains("connection reset") ||
                    msg.contains("broken pipe") ||
                    msg.contains("aborted") ||
                    msg.contains("connection aborted")
                ) {
                    return true
                }
            }
            // Client-side API exceptions (4xx errors: unauthorized, forbidden, bad request, not found)
            if (current is com.obsidianscout.auth.ApiException) {
                return true
            }
            if (current.javaClass.simpleName == "MobileApiException") {
                return true
            }
            if (current is io.ktor.server.plugins.NotFoundException ||
                current is io.ktor.server.plugins.BadRequestException
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Dispatches an email notification to superadmins on the mailing list if:
     * 1. The database setting `emailServerErrors` is enabled.
     * 2. The error is an actual server code error (not network disconnects, quorum loss, or normal 4xx API errors).
     * 3. The error signature is not currently throttled by the rate-limiter.
     */
    fun dispatchServerErrorAlert(call: ApplicationCall, cause: Throwable) {
        // Quick filter for network/client errors
        if (isIgnorableNetworkOrClientError(cause)) {
            return
        }

        // Quorum loss is handled separately by CockroachOrchestrator
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLossException(cause)) {
            return
        }

        // Check if database setting is enabled across cluster
        val settings = SettingsService.getErrorAlertSettings()
        if (!settings.emailServerErrors) {
            return
        }

        val requestPath = try { call.request.path() } catch (_: Throwable) { "/unknown" }
        val requestMethod = try { call.request.httpMethod.value } catch (_: Throwable) { "UNKNOWN" }
        val requestUri = try { call.request.uri } catch (_: Throwable) { requestPath }
        val clientIp = try {
            call.request.headers["CF-Connecting-IP"]
                ?: call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
                ?: call.request.origin.remoteHost
        } catch (_: Throwable) { "Unknown" }

        val session = try { call.sessions.get<UserSession>() } catch (_: Throwable) { null }

        // Unique signature for rate-limiting
        val errorSig = "${cause.javaClass.name}:${cause.message?.take(80)}:$requestMethod:$requestPath"
        val now = System.currentTimeMillis()
        val lastSent = recentAlertSignatures[errorSig]
        if (lastSent != null && (now - lastSent) < ALERT_COOLDOWN_MS) {
            ServerLogService.appendLog("DEBUG", "ServerErrorAlertService", "Suppressed duplicate server error alert ($errorSig). Cooldown active.")
            return
        }
        recentAlertSignatures[errorSig] = now

        // Extract stack trace
        val sw = StringWriter()
        cause.printStackTrace(PrintWriter(sw))
        val fullStackTrace = sw.toString()
        val formattedTrace = fullStackTrace.lines().take(25).joinToString("\n")

        // Persist to ReportedErrors table asynchronously
        val resolvedClientIp = clientIp.take(64)
        scope.launch {
            try {
                org.jetbrains.exposed.sql.transactions.transaction {
                    com.obsidianscout.db.ReportedErrors.insert {
                        it[errorType] = "SERVER"
                        it[errorMessage] = cause.message?.take(2000) ?: cause.javaClass.name
                        it[errorStack] = fullStackTrace
                        it[requestDetails] = "$requestMethod $requestUri".take(500)
                        it[com.obsidianscout.db.ReportedErrors.clientIp] = resolvedClientIp
                        it[teamNumber] = session?.teamNumber
                        it[program] = session?.program?.take(16)
                        it[userRole] = session?.role?.name?.take(32)
                        it[username] = session?.username?.take(128)
                        it[status] = "OPEN"
                        it[createdAt] = Instant.now()
                    }
                }
            } catch (e: Exception) {
                ServerLogService.appendLog("ERROR", "ServerErrorAlertService", "Failed to persist server error to database: ${e.message}")
            }
        }

        scope.launch {
            try {
                sendAlertEmail(
                    subjectTitle = "Server Code Exception: ${cause.javaClass.simpleName}",
                    errorMessage = cause.message ?: "No message provided",
                    exceptionClass = cause.javaClass.name,
                    requestDetails = "$requestMethod $requestUri",
                    clientIp = clientIp,
                    session = session,
                    stackTrace = formattedTrace,
                    isTest = false
                )
            } catch (e: Exception) {
                ServerLogService.appendLog("ERROR", "ServerErrorAlertService", "Failed to send server error alert email: ${e.message}")
            }
        }
    }

    /**
     * Records an internal server or database error directly to the error reporting interface (ReportedErrors)
     * and logs it to the server logs. When configured, sends an email alert to superadmins.
     */
    fun recordServerError(
        errorMessage: String,
        cause: Throwable? = null,
        requestDetails: String? = null,
        errorType: String = "SERVER",
        sync: Boolean = false
    ) {
        ServerLogService.appendLog("ERROR", "ServerErrorAlertService", "$errorMessage${cause?.let { ": ${it.message}" } ?: ""}")

        val fullStackTrace = cause?.let {
            val sw = StringWriter()
            it.printStackTrace(PrintWriter(sw))
            sw.toString()
        }

        fun persist() {
            try {
                org.jetbrains.exposed.sql.transactions.transaction {
                    com.obsidianscout.db.ReportedErrors.insert {
                        it[ReportedErrors.errorType] = errorType
                        it[ReportedErrors.errorMessage] = errorMessage.take(2000)
                        it[ReportedErrors.errorStack] = fullStackTrace
                        it[ReportedErrors.requestDetails] = requestDetails?.take(500)
                        it[ReportedErrors.clientIp] = "Server (Internal)"
                        it[ReportedErrors.status] = "OPEN"
                        it[ReportedErrors.createdAt] = Instant.now()
                    }
                }
            } catch (e: Exception) {
                ServerLogService.appendLog("ERROR", "ServerErrorAlertService", "Failed to persist server error to database: ${e.message}")
            }
        }

        if (sync) {
            persist()
        } else {
            scope.launch { persist() }
        }

        // Check if database setting is enabled across cluster for email alerts
        val settings = try { SettingsService.getErrorAlertSettings() } catch (_: Throwable) { null }
        if (settings?.emailServerErrors == true) {
            val errorSig = "${cause?.javaClass?.name ?: "Error"}:${errorMessage.take(80)}:$requestDetails"
            val now = System.currentTimeMillis()
            val lastSent = recentAlertSignatures[errorSig]
            if (lastSent != null && (now - lastSent) < ALERT_COOLDOWN_MS) {
                return
            }
            recentAlertSignatures[errorSig] = now

            scope.launch {
                try {
                    sendAlertEmail(
                        subjectTitle = "Server Code Exception: ${cause?.javaClass?.simpleName ?: "Internal System Error"}",
                        errorMessage = errorMessage,
                        exceptionClass = cause?.javaClass?.name ?: "SystemError",
                        requestDetails = requestDetails ?: "Internal System Routine",
                        clientIp = "Server (Internal)",
                        session = null,
                        stackTrace = fullStackTrace?.lines()?.take(25)?.joinToString("\n") ?: "No stack trace available",
                        isTest = false
                    )
                } catch (e: Exception) {
                    ServerLogService.appendLog("ERROR", "ServerErrorAlertService", "Failed to send server error alert email: ${e.message}")
                }
            }
        }
    }

    /**
     * Handles recording a client-side JavaScript bug report submitted by the frontend.
     */
    fun recordClientBugReport(report: ClientBugReportRequest, session: UserSession?) {
        val userStr = if (session != null) {
            "User: ${session.username} (Team ${session.teamNumber}, Program ${session.program}, Role ${session.role})"
        } else if (!report.username.isNullOrBlank() || report.teamNumber != null) {
            "User: ${report.username ?: "Anonymous"} (Team ${report.teamNumber ?: "N/A"}, Program ${report.program ?: "N/A"}, Role ${report.userRole ?: "N/A"})"
        } else {
            "Anonymous Client"
        }

        val logMessage = "[Client Bug Report] $userStr - ${report.errorMessage} at ${report.errorUrl}:${report.lineNumber}:${report.columnNumber}"
        ServerLogService.appendLog("ERROR", "ClientBugReport", logMessage)

        val teamNum = session?.teamNumber ?: report.teamNumber
        val prog = session?.program ?: report.program
        val roleStr = session?.role?.name ?: report.userRole
        val user = session?.username ?: report.username
        val reqDetails = "${report.errorUrl ?: "App"} (line ${report.lineNumber ?: "?"}, col ${report.columnNumber ?: "?"})"

        // Persist to ReportedErrors table asynchronously
        scope.launch {
            try {
                org.jetbrains.exposed.sql.transactions.transaction {
                    com.obsidianscout.db.ReportedErrors.insert {
                        it[errorType] = "CLIENT_JS"
                        it[errorMessage] = report.errorMessage.take(2000)
                        it[errorStack] = report.errorStack
                        it[requestDetails] = reqDetails.take(500)
                        it[clientIp] = "Client (${report.clientType})".take(64)
                        it[teamNumber] = teamNum
                        it[program] = prog?.take(16)
                        it[userRole] = roleStr?.take(32)
                        it[username] = user?.take(128)
                        it[status] = "OPEN"
                        it[createdAt] = Instant.now()
                    }
                }
            } catch (e: Exception) {
                ServerLogService.appendLog("ERROR", "ServerErrorAlertService", "Failed to persist client bug report to database: ${e.message}")
            }
        }

        // If email alerts are enabled, also email superadmins about the client bug report
        val settings = SettingsService.getErrorAlertSettings()
        if (settings.emailServerErrors) {
            val errorSig = "ClientJS:${report.errorMessage.take(80)}:${report.errorUrl}:${report.lineNumber}"
            val now = System.currentTimeMillis()
            val lastSent = recentAlertSignatures[errorSig]
            if (lastSent != null && (now - lastSent) < ALERT_COOLDOWN_MS) {
                return
            }
            recentAlertSignatures[errorSig] = now

            scope.launch {
                try {
                    sendAlertEmail(
                        subjectTitle = "Client JS Error Report",
                        errorMessage = report.errorMessage,
                        exceptionClass = "JavaScript Error",
                        requestDetails = reqDetails,
                        clientIp = "Client (${report.clientType})",
                        session = session,
                        stackTrace = report.errorStack ?: "No stack trace provided",
                        isTest = false,
                        reportedUserMetadata = "Team: ${teamNum ?: "N/A"} | Program: ${prog ?: "N/A"} | Role: ${roleStr ?: "N/A"}"
                    )
                } catch (e: Exception) {
                    ServerLogService.appendLog("ERROR", "ServerErrorAlertService", "Failed to email client bug report: ${e.message}")
                }
            }
        }
    }

    /**
     * Lists reported errors with optional type and status filtering, search keyword, and pagination.
     */
    fun listReportedErrors(
        typeFilter: String? = null,
        statusFilter: String? = null,
        search: String? = null,
        limit: Int = 100,
        offset: Long = 0
    ): com.obsidianscout.routes.ReportedErrorsListResponse {
        return readTransaction {
            val query = com.obsidianscout.db.ReportedErrors.selectAll()

            if (!typeFilter.isNullOrBlank() && typeFilter.uppercase() != "ALL") {
                query.andWhere { com.obsidianscout.db.ReportedErrors.errorType eq typeFilter.uppercase() }
            }
            if (!statusFilter.isNullOrBlank() && statusFilter.uppercase() != "ALL") {
                query.andWhere { com.obsidianscout.db.ReportedErrors.status eq statusFilter.uppercase() }
            }
            if (!search.isNullOrBlank()) {
                val pattern = "%${search.trim().lowercase()}%"
                query.andWhere {
                    (com.obsidianscout.db.ReportedErrors.errorMessage.lowerCase() like pattern) or
                    (com.obsidianscout.db.ReportedErrors.requestDetails.lowerCase() like pattern) or
                    (com.obsidianscout.db.ReportedErrors.username.lowerCase() like pattern)
                }
            }

            val total = query.count()
            val items = query
                .orderBy(com.obsidianscout.db.ReportedErrors.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit.coerceIn(1, 500), offset)
                .map { row ->
                    com.obsidianscout.routes.ReportedErrorItem(
                        id = row[com.obsidianscout.db.ReportedErrors.id].value.toString(),
                        errorType = row[com.obsidianscout.db.ReportedErrors.errorType],
                        errorMessage = row[com.obsidianscout.db.ReportedErrors.errorMessage],
                        errorStack = row[com.obsidianscout.db.ReportedErrors.errorStack],
                        requestDetails = row[com.obsidianscout.db.ReportedErrors.requestDetails],
                        clientIp = row[com.obsidianscout.db.ReportedErrors.clientIp],
                        teamNumber = row[com.obsidianscout.db.ReportedErrors.teamNumber],
                        program = row[com.obsidianscout.db.ReportedErrors.program],
                        userRole = row[com.obsidianscout.db.ReportedErrors.userRole],
                        username = row[com.obsidianscout.db.ReportedErrors.username],
                        status = row[com.obsidianscout.db.ReportedErrors.status],
                        createdAt = row[com.obsidianscout.db.ReportedErrors.createdAt].toString(),
                        resolvedAt = row[com.obsidianscout.db.ReportedErrors.resolvedAt]?.toString(),
                        resolvedBy = row[com.obsidianscout.db.ReportedErrors.resolvedBy]
                    )
                }

            val stats = getReportedErrorStats()
            com.obsidianscout.routes.ReportedErrorsListResponse(
                success = true,
                errors = items,
                totalCount = total,
                openCount = stats.openCount,
                resolvedCount = stats.resolvedCount,
                serverCount = stats.serverCount,
                clientCount = stats.clientCount
            )
        }
    }

    /**
     * Computes summary metrics for reported errors.
     */
    fun getReportedErrorStats(): com.obsidianscout.routes.ReportedErrorStatsResponse {
        return readTransaction {
            val total = com.obsidianscout.db.ReportedErrors.selectAll().count()
            val open = com.obsidianscout.db.ReportedErrors.selectAll().where { com.obsidianscout.db.ReportedErrors.status eq "OPEN" }.count()
            val resolved = com.obsidianscout.db.ReportedErrors.selectAll().where { com.obsidianscout.db.ReportedErrors.status eq "RESOLVED" }.count()
            val server = com.obsidianscout.db.ReportedErrors.selectAll().where { com.obsidianscout.db.ReportedErrors.errorType eq "SERVER" }.count()
            val client = com.obsidianscout.db.ReportedErrors.selectAll().where { com.obsidianscout.db.ReportedErrors.errorType eq "CLIENT_JS" }.count()

            com.obsidianscout.routes.ReportedErrorStatsResponse(
                success = true,
                totalCount = total,
                openCount = open,
                resolvedCount = resolved,
                serverCount = server,
                clientCount = client
            )
        }
    }

    /**
     * Updates the status of a reported error (e.g. OPEN <-> RESOLVED).
     */
    fun updateReportedErrorStatus(id: String, newStatus: String, resolvedByUsername: String?): Boolean {
        val uuid = runCatching { java.util.UUID.fromString(id) }.getOrNull() ?: return false
        val statusUpper = if (newStatus.uppercase() == "RESOLVED") "RESOLVED" else "OPEN"
        return org.jetbrains.exposed.sql.transactions.transaction {
            val updated = com.obsidianscout.db.ReportedErrors.update({ com.obsidianscout.db.ReportedErrors.id eq uuid }) {
                it[status] = statusUpper
                if (statusUpper == "RESOLVED") {
                    it[resolvedAt] = Instant.now()
                    it[resolvedBy] = resolvedByUsername
                } else {
                    it[resolvedAt] = null
                    it[resolvedBy] = null
                }
            }
            updated > 0
        }
    }

    /**
     * Deletes a single reported error by ID.
     */
    fun deleteReportedError(id: String): Boolean {
        val uuid = runCatching { java.util.UUID.fromString(id) }.getOrNull() ?: return false
        return org.jetbrains.exposed.sql.transactions.transaction {
            val count = com.obsidianscout.db.ReportedErrors.deleteWhere { com.obsidianscout.db.ReportedErrors.id eq uuid }
            count > 0
        }
    }

    /**
     * Clears reported errors by status ("ALL", "RESOLVED", "OPEN").
     */
    fun clearReportedErrors(statusFilter: String?): Int {
        val upper = statusFilter?.uppercase() ?: "ALL"
        return org.jetbrains.exposed.sql.transactions.transaction {
            when (upper) {
                "RESOLVED" -> com.obsidianscout.db.ReportedErrors.deleteWhere { com.obsidianscout.db.ReportedErrors.status eq "RESOLVED" }
                "OPEN" -> com.obsidianscout.db.ReportedErrors.deleteWhere { com.obsidianscout.db.ReportedErrors.status eq "OPEN" }
                else -> com.obsidianscout.db.ReportedErrors.deleteAll()
            }
        }
    }

    /**
     * Sends a test alert email to verify the cluster server error notification system.
     */
    fun sendTestServerErrorAlert(targetEmail: String? = null): Pair<Boolean, String> {
        val recipients = if (!targetEmail.isNullOrBlank()) {
            listOf(targetEmail.trim())
        } else {
            getEnrolledSuperadminEmails()
        }

        if (recipients.isEmpty()) {
            return Pair(false, "No enrolled superadmins on the mailing list or recipient email not configured.")
        }

        val smtpConfigured = try {
            SettingsService.getSmtpSettings().host.isNotBlank()
        } catch (_: Exception) {
            false
        }

        if (!smtpConfigured) {
            return Pair(false, "SMTP server is not configured. Please configure SMTP in Admin Settings first.")
        }

        return try {
            sendAlertEmail(
                subjectTitle = "[TEST] Simulated Server Code Exception",
                errorMessage = "This is a simulated server code exception dispatched from Cluster Management to test notifications.",
                exceptionClass = "java.lang.RuntimeException: SimulatedTestException",
                requestDetails = "GET /api/admin/cluster/error-alerts/test",
                clientIp = "127.0.0.1",
                session = UserSession("test-id", "test_admin", 0, "FRC", UserRole.SUPERADMIN),
                stackTrace = "com.obsidianscout.admin.ServerErrorAlertServiceTest.simulatedCodeCrash(ServerErrorAlertServiceTest.kt:42)\n\tat com.obsidianscout.routes.RoutesKt.testRoute(Routes.kt:1234)",
                isTest = true,
                explicitRecipients = recipients
            )
            Pair(true, "Test server error alert successfully emailed to: ${recipients.joinToString(", ")}")
        } catch (e: Exception) {
            Pair(false, "Failed to send test email: ${e.message}")
        }
    }

    fun getEnrolledSuperadminEmails(): List<String> {
        return try {
            readTransaction {
                val dbEmails = Users.selectAll()
                    .where { (Users.role eq UserRole.SUPERADMIN.name) and (Users.nodeAlertsEnabled eq true) }
                    .mapNotNull { it[Users.email]?.takeIf { e -> e.isNotBlank() } }
                val additional = SettingsService.getErrorAlertSettings().additionalEmails.filter { it.isNotBlank() }
                (dbEmails + additional).distinct()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun sendAlertEmail(
        subjectTitle: String,
        errorMessage: String,
        exceptionClass: String,
        requestDetails: String,
        clientIp: String,
        session: UserSession?,
        stackTrace: String,
        isTest: Boolean = false,
        reportedUserMetadata: String? = null,
        explicitRecipients: List<String>? = null
    ) {
        val recipients = explicitRecipients ?: getEnrolledSuperadminEmails()
        if (recipients.isEmpty()) {
            ServerLogService.appendLog("INFO", "ServerErrorAlertService", "Server error occurred but no superadmin emails are enrolled on the mailing list.")
            return
        }

        val smtpConfigured = try {
            SettingsService.getSmtpSettings().host.isNotBlank()
        } catch (_: Exception) {
            false
        }
        if (!smtpConfigured) {
            ServerLogService.appendLog("WARN", "ServerErrorAlertService", "Server error alert triggered, but SMTP host is not configured.")
            return
        }

        val localIp = ClusterManagementService.getLocalTailscaleIp()
        val appConfig = AppConfigLoader.load()
        val nodeId = "Node-$localIp"
        val siteUrl = appConfig.getEffectiveSiteUrl()
        val timestamp = dtf.format(Instant.now())

        val userDetailsHtml = if (session != null) {
            """
            <tr>
                <td style="padding: 10px 14px; color: #94a3b8; font-weight: bold;">User Session</td>
                <td style="padding: 10px 14px; color: #f1f5f9;">
                    <strong>${session.username}</strong> (Team ${session.teamNumber}, Program ${session.program}, Role ${session.role})
                </td>
            </tr>
            """.trimIndent()
        } else if (!reportedUserMetadata.isNullOrBlank()) {
            """
            <tr>
                <td style="padding: 10px 14px; color: #94a3b8; font-weight: bold;">Client User</td>
                <td style="padding: 10px 14px; color: #f1f5f9;">
                    $reportedUserMetadata
                </td>
            </tr>
            """.trimIndent()
        } else {
            """
            <tr>
                <td style="padding: 10px 14px; color: #94a3b8; font-weight: bold;">User Session</td>
                <td style="padding: 10px 14px; color: #94a3b8; font-style: italic;">Unauthenticated / Anonymous</td>
            </tr>
            """.trimIndent()
        }

        val emailSubject = if (isTest) {
            "[TEST ALERT] ObsidianScout Server Error on $localIp"
        } else {
            "[SERVER ERROR ALERT] $exceptionClass on $localIp"
        }

        val emailHtml = """
            <!DOCTYPE html>
            <html>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; line-height: 1.6; color: #f8fafc; background-color: #0f172a; padding: 24px; margin: 0;">
                <div style="max-width: 680px; margin: 0 auto; background: #1e293b; border: 1px solid #ef4444; border-radius: 12px; padding: 28px; box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5);">
                    <div style="display: flex; align-items: center; border-bottom: 2px solid #334155; padding-bottom: 16px; margin-bottom: 20px;">
                        <h2 style="color: #ef4444; margin: 0; font-size: 20px; font-weight: 700;">
                            ObsidianScout Server Error Notification
                        </h2>
                    </div>

                    <p style="font-size: 15px; color: #cbd5e1; margin-top: 0;">
                        An unexpected server code error occurred on cluster node <strong>$localIp</strong> ($nodeId).
                    </p>

                    <div style="background: rgba(239, 68, 68, 0.1); border: 1px solid rgba(239, 68, 68, 0.3); border-radius: 8px; padding: 14px; margin: 16px 0;">
                        <div style="font-family: monospace; font-size: 15px; font-weight: bold; color: #fca5a5; word-break: break-word;">
                            $exceptionClass
                        </div>
                        <div style="color: #f1f5f9; font-size: 14px; margin-top: 6px; word-break: break-word;">
                            $errorMessage
                        </div>
                    </div>

                    <table style="width: 100%; border-collapse: collapse; margin: 20px 0; background: #0f172a; border-radius: 8px; font-size: 14px;">
                        <tr style="border-bottom: 1px solid #334155;">
                            <td style="padding: 10px 14px; color: #94a3b8; font-weight: bold; width: 140px;">Node ID</td>
                            <td style="padding: 10px 14px; color: #f1f5f9; font-family: monospace;">$nodeId ($localIp)</td>
                        </tr>
                        <tr style="border-bottom: 1px solid #334155;">
                            <td style="padding: 10px 14px; color: #94a3b8; font-weight: bold;">Request</td>
                            <td style="padding: 10px 14px; color: #f1f5f9; font-family: monospace;">$requestDetails</td>
                        </tr>
                        <tr style="border-bottom: 1px solid #334155;">
                            <td style="padding: 10px 14px; color: #94a3b8; font-weight: bold;">Client IP</td>
                            <td style="padding: 10px 14px; color: #f1f5f9; font-family: monospace;">$clientIp</td>
                        </tr>
                        $userDetailsHtml
                        <tr>
                            <td style="padding: 10px 14px; color: #94a3b8; font-weight: bold;">Timestamp</td>
                            <td style="padding: 10px 14px; color: #f1f5f9;">$timestamp</td>
                        </tr>
                    </table>

                    <div style="margin-top: 20px;">
                        <div style="font-weight: bold; color: #cbd5e1; font-size: 13px; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px;">
                            Stack Trace Preview
                        </div>
                        <pre style="background: #090d16; border: 1px solid #334155; border-radius: 8px; padding: 14px; color: #fca5a5; font-size: 12px; font-family: 'Consolas', 'Courier New', monospace; overflow-x: auto; white-space: pre-wrap; line-height: 1.4;">$stackTrace</pre>
                    </div>

                    <div style="text-align: center; margin-top: 24px;">
                        <a href="$siteUrl/cluster-management" style="background-color: #3b82f6; color: #ffffff; padding: 10px 20px; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 14px; display: inline-block;">
                            View Cluster Management
                        </a>
                    </div>

                    <div style="border-top: 1px solid #334155; padding-top: 14px; margin-top: 24px; font-size: 12px; color: #64748b; text-align: center;">
                        This automated notification was dispatched by ObsidianScout Cluster Monitoring to enrolled Superadmins on the mailing list.
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        for (email in recipients) {
            try {
                EmailService.sendEmail(email, emailSubject, emailHtml)
                ServerLogService.appendLog("INFO", "ServerErrorAlertService", "Dispatched server error email alert to $email.")
            } catch (e: Exception) {
                ServerLogService.appendLog("ERROR", "ServerErrorAlertService", "Failed to send server error alert to $email: ${e.message}")
            }
        }
    }
}
