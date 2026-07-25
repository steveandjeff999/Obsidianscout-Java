package com.obsidianscout.db

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification
import com.google.firebase.messaging.MessagingErrorCode
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
import java.time.Instant
import java.util.UUID

object FcmService {
    @Volatile
    private var lastLoadedTimestamp: Instant = Instant.EPOCH

    @Volatile
    private var isInitialized: Boolean = false

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

            val credentials = GoogleCredentials.fromStream(ByteArrayInputStream(jsonStr.toByteArray(Charsets.UTF_8)))
            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(configRow[FcmConfigs.projectId].ifBlank { null })
                .build()

            FirebaseApp.initializeApp(options)
            isInitialized = true
            lastLoadedTimestamp = configRow[FcmConfigs.updatedAt]
            println("[FCM] Firebase Admin SDK successfully initialized for project: ${configRow[FcmConfigs.projectId]}")
        } catch (e: Exception) {
            isInitialized = false
            println("[FCM] Failed to initialize Firebase Admin SDK: ${e.message}")
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

        // Chunk tokens in batches of 500 for Firebase Multicast
        deviceTokens.chunked(500).forEach { tokenBatch ->
            try {
                val message = MulticastMessage.builder()
                    .addAllTokens(tokenBatch)
                    .setNotification(
                        Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                    .putData("groupName", groupName)
                    .putData("url", url)
                    .putData("title", title)
                    .putData("body", body)
                    .build()

                val batchResponse = FirebaseMessaging.getInstance().sendEachForMulticast(message)
                println("[FCM] Sent multicast push: ${batchResponse.successCount} succeeded, ${batchResponse.failureCount} failed.")

                // Prune stale or invalid tokens
                if (batchResponse.failureCount > 0) {
                    val tokensToDelete = mutableListOf<String>()
                    val responses = batchResponse.responses
                    for (i in responses.indices) {
                        val resp = responses[i]
                        if (!resp.isSuccessful) {
                            val errorCode = resp.exception?.messagingErrorCode
                            if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                                tokensToDelete.add(tokenBatch[i])
                            }
                        }
                    }

                    if (tokensToDelete.isNotEmpty()) {
                        println("[FCM] Pruning ${tokensToDelete.size} stale/unregistered FCM tokens.")
                        transaction {
                            FcmDeviceTokens.deleteWhere { FcmDeviceTokens.deviceToken inList tokensToDelete }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[FCM] Error sending multicast notification batch: ${e.message}")
            }
        }
    }

    fun sendTestNotification(adminUserId: UUID): Pair<Boolean, String> {
        checkClusterSync()

        if (!isInitialized) {
            return Pair(false, "Firebase Admin SDK is not initialized. Check your credentials.")
        }

        val tokens = transaction {
            FcmDeviceTokens.selectAll()
                .where { FcmDeviceTokens.userId eq adminUserId }
                .map { it[FcmDeviceTokens.deviceToken] }
        }

        if (tokens.isEmpty()) {
            return Pair(false, "No registered FCM device tokens found for your admin account. Ensure your device is registered.")
        }

        try {
            val message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(
                    Notification.builder()
                        .setTitle("ObsidianScout Test Notification")
                        .setBody("FCM Push Notifications are configured and working properly!")
                        .build()
                )
                .putData("groupName", "general")
                .putData("url", "/chat")
                .build()

            val response = FirebaseMessaging.getInstance().sendEachForMulticast(message)
            return if (response.successCount > 0) {
                Pair(true, "Test notification sent successfully to ${response.successCount} device(s).")
            } else {
                Pair(false, "Test notification failed to deliver. ${response.failureCount} failed.")
            }
        } catch (e: Exception) {
            return Pair(false, "Failed to send test notification: ${e.message}")
        }
    }
}
