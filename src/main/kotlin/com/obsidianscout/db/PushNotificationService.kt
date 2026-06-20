package com.obsidianscout.db

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.routes.ChatMessageDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Subscription
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

@Serializable
data class PushPayload(
    val title: String,
    val body: String,
    val tag: String,
    val data: PushPayloadData
)

@Serializable
data class PushPayloadData(
    val url: String,
    val groupName: String
)

object PushNotificationService {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pushServiceInstance: PushService? = null

    init {
        // Ensure Bouncy Castle is registered
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun getPushService(): PushService {
        if (pushServiceInstance == null) {
            val appConfig = AppConfigLoader.load()
            pushServiceInstance = PushService(
                appConfig.vapid.publicKey,
                appConfig.vapid.privateKey,
                appConfig.vapid.subject
            )
        }
        return pushServiceInstance!!
    }

    fun sendChatNotification(message: ChatMessageDto) {
        scope.launch {
            try {
                // 1. Find all users in the same team except the sender
                val targetUsers = transaction {
                    (PushSubscriptions innerJoin Users).selectAll()
                        .where { (Users.teamNumber eq message.teamNumber) and (Users.id neq message.userId) }
                        .map { row ->
                            Triple(
                                row[Users.username],
                                row[PushSubscriptions.endpoint],
                                Subscription(
                                    row[PushSubscriptions.endpoint],
                                    Subscription.Keys(row[PushSubscriptions.p256dh], row[PushSubscriptions.auth])
                                )
                            )
                        }
                }

                if (targetUsers.isEmpty()) return@launch

                val pushService = getPushService()

                for ((username, endpoint, subscription) in targetUsers) {
                    val isMentioned = message.content.contains("@$username", ignoreCase = true) ||
                                      message.content.contains("@everyone", ignoreCase = true) ||
                                      message.content.contains("@channel", ignoreCase = true)

                    val title = if (isMentioned) {
                        "Mentioned in #${message.groupName}"
                    } else {
                        "New message in #${message.groupName}"
                    }

                    // Truncate message content if it is long
                    val truncatedContent = if (message.content.length > 100) {
                        message.content.take(97) + "..."
                    } else {
                        message.content
                    }
                    val body = "${message.username}: $truncatedContent"

                    val payload = JsonSupport.json.encodeToString(
                        PushPayload(
                            title = title,
                            body = body,
                            tag = "chat-${message.groupName}",
                            data = PushPayloadData(
                                url = "/chat",
                                groupName = message.groupName
                            )
                        )
                    )

                    try {
                        val notification = Notification(subscription, payload)
                        val response = pushService.send(notification)
                        val statusCode = response.statusLine.statusCode
                        if (statusCode == 410 || statusCode == 404) {
                            println("[Push] Subscription expired or invalid (HTTP $statusCode). Deleting: $endpoint")
                            transaction {
                                PushSubscriptions.deleteWhere { PushSubscriptions.endpoint eq endpoint }
                            }
                        }
                    } catch (e: Exception) {
                        println("[Push] Failed to send push to $endpoint: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
