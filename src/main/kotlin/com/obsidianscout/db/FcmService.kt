package com.obsidianscout.db

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.obsidianscout.routes.FcmAdminConfigDto
import com.obsidianscout.routes.FcmPublicConfigDto
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.UUID

object FcmService {
    @Volatile
    private var lastLoadedTimestamp: Instant = Instant.EPOCH

    @Volatile
    private var isInitialized: Boolean = false

    // Hold a reference to the scoped credentials so we can get fresh OAuth tokens
    @Volatile
    private var fcmCredentials: GoogleCredentials? = null

    @Volatile
    private var fcmProjectId: String = ""

    @Synchronized
    fun reload() {
        try {
            // Delete all existing FirebaseApp instances safely to prevent IllegalStateException crashes
            val apps = ArrayList(FirebaseApp.getApps())
            for (app in apps) {
                try {
                    app.delete()
                } catch (e: Exception) {
                    println("[FCM] Warning while deleting existing FirebaseApp instance ${app.name}: ${e.message}")
                }
            }
            isInitialized = false
            fcmCredentials = null
            fcmProjectId = ""

            val configRow = transaction {
                FcmConfigs.selectAll().firstOrNull()
            }

            if (configRow == null || !configRow[FcmConfigs.enabled]) {
                println("[FCM] Firebase Messaging is disabled or unconfigured.")
                return
            }

            val jsonStr = configRow[FcmConfigs.serviceAccountJson].trim()
            if (jsonStr.isEmpty()) {
                println("[FCM] Service Account JSON is empty.")
                return
            }

            val rawCredentials = GoogleCredentials.fromStream(ByteArrayInputStream(jsonStr.toByteArray(Charsets.UTF_8)))
            val fcmScopes = listOf(
                "https://www.googleapis.com/auth/firebase.messaging",
                "https://www.googleapis.com/auth/cloud-platform"
            )
            val credentials = if (rawCredentials.createScopedRequired()) {
                rawCredentials.createScoped(fcmScopes)
            } else {
                rawCredentials
            }

            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setHttpTransport(com.google.api.client.http.javanet.NetHttpTransport())
                .setJsonFactory(com.google.api.client.json.gson.GsonFactory.getDefaultInstance())
                .setProjectId(configRow[FcmConfigs.projectId].ifBlank { null })
                .build()

            FirebaseApp.initializeApp(options)
            credentials.refreshIfExpired()

            fcmCredentials = credentials
            fcmProjectId = configRow[FcmConfigs.projectId]
            isInitialized = true
            lastLoadedTimestamp = configRow[FcmConfigs.updatedAt]
            println("[FCM] Firebase Admin SDK successfully initialized and OAuth2 authenticated for project: ${configRow[FcmConfigs.projectId]}")
        } catch (e: Throwable) {
            val root = getRootCause(e)
            isInitialized = false
            println("[FCM] Failed to initialize Firebase Admin SDK: ${e.message} (Root Cause: ${root.javaClass.name}: ${root.message})")
            e.printStackTrace()
        }
    }

    private fun checkClusterSync() {
        try {
            val dbUpdatedAt = transaction {
                FcmConfigs.selectAll().firstOrNull()?.get(FcmConfigs.updatedAt)
            } ?: Instant.EPOCH

            if (dbUpdatedAt.isAfter(lastLoadedTimestamp) || (!isInitialized && dbUpdatedAt != Instant.EPOCH)) {
                reload()
            }
        } catch (e: Exception) {
            println("[FCM] Error checking cluster sync: ${e.message}")
        }
    }

    fun getPublicConfig(): FcmPublicConfigDto = transaction {
        val row = FcmConfigs.selectAll().firstOrNull()
        if (row == null) {
            FcmPublicConfigDto(
                enabled = false,
                projectId = "",
                apiKey = "",
                appId = "",
                messagingSenderId = "",
                vapidKey = ""
            )
        } else {
            FcmPublicConfigDto(
                enabled = row[FcmConfigs.enabled],
                projectId = row[FcmConfigs.projectId],
                apiKey = row[FcmConfigs.apiKey],
                appId = row[FcmConfigs.appId],
                messagingSenderId = row[FcmConfigs.messagingSenderId],
                vapidKey = row[FcmConfigs.vapidKey]
            )
        }
    }

    fun getAdminConfig(): FcmAdminConfigDto = transaction {
        val row = FcmConfigs.selectAll().firstOrNull()
        val hasJson = row != null && row[FcmConfigs.serviceAccountJson].isNotBlank()
        if (row == null) {
            FcmAdminConfigDto(
                enabled = false,
                projectId = "",
                apiKey = "",
                appId = "",
                messagingSenderId = "",
                vapidKey = "",
                hasServiceAccountJson = false,
                isInitialized = isInitialized
            )
        } else {
            FcmAdminConfigDto(
                enabled = row[FcmConfigs.enabled],
                projectId = row[FcmConfigs.projectId],
                apiKey = row[FcmConfigs.apiKey],
                appId = row[FcmConfigs.appId],
                messagingSenderId = row[FcmConfigs.messagingSenderId],
                vapidKey = row[FcmConfigs.vapidKey],
                hasServiceAccountJson = hasJson,
                isInitialized = isInitialized
            )
        }
    }

    fun saveConfig(
        enabled: Boolean,
        projectId: String,
        apiKey: String,
        appId: String,
        messagingSenderId: String,
        serviceAccountJson: String,
        vapidKey: String
    ): Boolean {
        val now = Instant.now()
        transaction {
            val existing = FcmConfigs.selectAll().firstOrNull()
            val finalJson = if (serviceAccountJson.isBlank() && existing != null) {
                existing[FcmConfigs.serviceAccountJson]
            } else {
                serviceAccountJson
            }

            if (existing == null) {
                FcmConfigs.upsert {
                    it[FcmConfigs.enabled] = enabled
                    it[FcmConfigs.projectId] = projectId
                    it[FcmConfigs.apiKey] = apiKey
                    it[FcmConfigs.appId] = appId
                    it[FcmConfigs.messagingSenderId] = messagingSenderId
                    it[FcmConfigs.serviceAccountJson] = finalJson
                    it[FcmConfigs.vapidKey] = vapidKey
                    it[FcmConfigs.updatedAt] = now
                }
            } else {
                FcmConfigs.upsert {
                    it[id] = existing[id]
                    it[FcmConfigs.enabled] = enabled
                    it[FcmConfigs.projectId] = projectId
                    it[FcmConfigs.apiKey] = apiKey
                    it[FcmConfigs.appId] = appId
                    it[FcmConfigs.messagingSenderId] = messagingSenderId
                    it[FcmConfigs.serviceAccountJson] = finalJson
                    it[FcmConfigs.vapidKey] = vapidKey
                    it[FcmConfigs.updatedAt] = now
                }
            }
        }
        reload()
        return isInitialized || !enabled
    }

    fun registerDeviceToken(userId: UUID, deviceToken: String, platform: String) = transaction {
        val now = Instant.now()
        FcmDeviceTokens.upsert(FcmDeviceTokens.deviceToken) {
            it[FcmDeviceTokens.userId] = userId
            it[FcmDeviceTokens.deviceToken] = deviceToken
            it[FcmDeviceTokens.platform] = platform
            it[FcmDeviceTokens.updatedAt] = now
        }
    }

    fun unregisterDeviceToken(userId: UUID, deviceToken: String) = transaction {
        FcmDeviceTokens.deleteWhere {
            (FcmDeviceTokens.userId eq userId) and (FcmDeviceTokens.deviceToken eq deviceToken)
        }
    }

    /**
     * Send a single FCM message via the HTTP v1 REST API.
     *
     * Using the REST API directly (instead of FirebaseMessaging.sendEachForMulticast) avoids the
     * Firebase Admin SDK's internal ApiFutures / ThreadManager machinery which fails under GraalVM
     * native image, causing spurious FirebaseMessagingException with the class name as message
     * even though the notification is actually delivered.
     *
     * Returns: Pair(httpStatusCode, responseBodyAsString)
     */
    private fun sendFcmHttpV1(token: String, bodyJson: String): Pair<Int, String> {
        val creds = fcmCredentials ?: throw IllegalStateException("FCM credentials not initialised")
        creds.refreshIfExpired()
        val accessToken = creds.accessToken?.tokenValue
            ?: throw IllegalStateException("Could not obtain FCM OAuth access token")

        val url = URI("https://fcm.googleapis.com/v1/projects/$fcmProjectId/messages:send").toURL()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", bodyBytes.size.toString())
            conn.outputStream.use { it.write(bodyBytes) }

            val code = conn.responseCode
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            } catch (_: Exception) { "" }
            return Pair(code, body)
        } finally {
            conn.disconnect()
        }
    }

    /** Build FCM HTTP v1 message JSON for a single device token. */
    private fun buildFcmJson(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): String {
        val dataJson = data.entries.joinToString(",") { (k, v) ->
            "\"${k.replace("\"", "\\\"")}\": \"${v.replace("\"", "\\\"")}\""
        }
        val escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedBody = body.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedToken = token.replace("\"", "\\\"")
        return """{
  "message": {
    "token": "$escapedToken",
    "notification": { "title": "$escapedTitle", "body": "$escapedBody" },
    "android": {
      "notification": { "click_action": "FLUTTER_NOTIFICATION_CLICK", "sound": "default" }
    },
    "data": { $dataJson }
  }
}"""
    }

    fun sendNotificationToUsers(
        targetUserUuids: List<UUID>,
        title: String,
        body: String,
        groupName: String,
        url: String
    ) {
        checkClusterSync()
        if (!isInitialized || targetUserUuids.isEmpty()) return

        val deviceTokens = transaction {
            FcmDeviceTokens.selectAll()
                .where { FcmDeviceTokens.userId inList targetUserUuids }
                .map { it[FcmDeviceTokens.deviceToken] }
        }.distinct()

        if (deviceTokens.isEmpty()) return

        val data = mapOf(
            "groupName" to groupName,
            "url" to url,
            "click_action" to "FLUTTER_NOTIFICATION_CLICK",
            "title" to title,
            "body" to body
        )

        var successCount = 0
        var failureCount = 0
        val tokensToDelete = mutableListOf<String>()

        for (token in deviceTokens) {
            try {
                val json = buildFcmJson(token, title, body, data)
                val (code, responseBody) = sendFcmHttpV1(token, json)
                if (code in 200..299) {
                    successCount++
                } else {
                    failureCount++
                    // Prune stale/unregistered tokens
                    if (responseBody.contains("UNREGISTERED") || responseBody.contains("INVALID_ARGUMENT")) {
                        tokensToDelete.add(token)
                    }
                    println("[FCM] Token send failed ($code): $responseBody")
                }
            } catch (e: Throwable) {
                failureCount++
                println("[FCM] Error sending to token: ${e.message}")
            }
        }

        println("[FCM] Sent push notifications: $successCount succeeded, $failureCount failed.")

        if (tokensToDelete.isNotEmpty()) {
            println("[FCM] Pruning ${tokensToDelete.size} stale/unregistered FCM tokens.")
            try {
                transaction {
                    FcmDeviceTokens.deleteWhere { FcmDeviceTokens.deviceToken inList tokensToDelete }
                }
            } catch (e: Exception) {
                println("[FCM] Error pruning stale tokens: ${e.message}")
            }
        }
    }

    fun sendTestNotification(adminUserId: UUID): Pair<Boolean, String> {
        checkClusterSync()

        if (!isInitialized) {
            return Pair(false, "Firebase Admin SDK is not initialized. Check your credentials.")
        }

        var tokens = transaction {
            FcmDeviceTokens.selectAll()
                .where { FcmDeviceTokens.userId eq adminUserId }
                .map { it[FcmDeviceTokens.deviceToken] }
        }

        if (tokens.isEmpty()) {
            tokens = transaction {
                FcmDeviceTokens.selectAll()
                    .map { it[FcmDeviceTokens.deviceToken] }
            }
        }

        if (tokens.isEmpty()) {
            return Pair(false, "No devices have registered for FCM push notifications yet. Log into the mobile app to register your device token automatically.")
        }

        var successCount = 0
        var failureCount = 0

        for (token in tokens) {
            try {
                val json = buildFcmJson(
                    token,
                    "ObsidianScout Test Notification",
                    "FCM Push Notifications are configured and working properly!",
                    mapOf("groupName" to "general", "url" to "/chat")
                )
                val (code, responseBody) = sendFcmHttpV1(token, json)
                if (code in 200..299) {
                    successCount++
                } else {
                    failureCount++
                    println("[FCM] Test send failed ($code): $responseBody")
                }
            } catch (e: Throwable) {
                failureCount++
                println("[FCM] Error sending test notification to token: ${e.message}")
            }
        }

        return if (successCount > 0) {
            Pair(true, "Test notification sent successfully to $successCount device(s).")
        } else {
            Pair(false, "Test notification failed to deliver to all $failureCount device(s).")
        }
    }

    private fun getRootCause(throwable: Throwable): Throwable {
        var root = throwable
        while (root.cause != null && root.cause != root) {
            root = root.cause!!
        }
        return root
    }
}
