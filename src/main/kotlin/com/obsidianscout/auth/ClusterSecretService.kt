package com.obsidianscout.auth

import com.obsidianscout.config.AppConfig
import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.ClusterSecrets
import com.obsidianscout.utils.VapidKeyGenerator
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class KeyRegenerationResponse(
    val success: Boolean,
    val message: String,
    val updatedAt: String
)

object ClusterSecretService {

    private val sessionSecretRef = AtomicReference<String>("")
    private val vapidPublicKeyRef = AtomicReference<String>("")
    private val vapidPrivateKeyRef = AtomicReference<String>("")

    @Volatile
    private var syncJob: Job? = null

    /**
     * Initializes in-memory secret references from local AppConfig prior to DB connection.
     */
    fun initFromConfig(appConfig: AppConfig) {
        if (sessionSecretRef.get().isBlank()) {
            sessionSecretRef.set(appConfig.server.sessionSecret)
        }
        if (vapidPublicKeyRef.get().isBlank()) {
            vapidPublicKeyRef.set(appConfig.vapid.publicKey)
        }
        if (vapidPrivateKeyRef.get().isBlank()) {
            vapidPrivateKeyRef.set(appConfig.vapid.privateKey)
        }
    }

    fun getSessionSecret(): String {
        val current = sessionSecretRef.get()
        return if (current.isNull_or_blank()) "change-me" else current
    }

    fun getVapidPublicKey(): String = vapidPublicKeyRef.get() ?: ""

    fun getVapidPrivateKey(): String = vapidPrivateKeyRef.get() ?: ""

    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()

    /**
     * Synchronizes secrets with CockroachDB `cluster_secrets` table.
     * If secrets exist in DB, fetches and applies them locally, persisting to config/app-config.json.
     * If secrets are missing in DB, populates DB with current local secrets (or generates fresh ones).
     */
    fun syncSecrets(appConfig: AppConfig) {
        initFromConfig(appConfig)
        if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return
        try {
            val existing = com.obsidianscout.db.readTransaction {
                ClusterSecrets.selectAll().associate { 
                    it[ClusterSecrets.keyName] to it[ClusterSecrets.keyValue] 
                }
            }

            val dbSessionSecret = existing["session_secret"]
            val dbVapidPublic = existing["vapid_public_key"]
            val dbVapidPrivate = existing["vapid_private_key"]

            var changed = false
            val now = Instant.now()

            val masterSessionSecret = if (!dbSessionSecret.isNull_or_blank()) {
                dbSessionSecret!!
            } else {
                if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return
                val local = sessionSecretRef.get().ifBlank { AppConfigLoader.generateSecret() }
                transaction {
                    ClusterSecrets.insert {
                        it[keyName] = "session_secret"
                        it[keyValue] = local
                        it[updatedAt] = now
                    }
                }
                local
            }

            val (masterVapidPublic, masterVapidPrivate) = if (!dbVapidPublic.isNull_or_blank() && !dbVapidPrivate.isNull_or_blank()) {
                Pair(dbVapidPublic!!, dbVapidPrivate!!)
            } else {
                if (com.obsidianscout.db.orchestration.CockroachOrchestrator.isQuorumLost) return
                val (pub, priv) = if (vapidPublicKeyRef.get().isNotBlank() && vapidPrivateKeyRef.get().isNotBlank()) {
                    Pair(vapidPublicKeyRef.get(), vapidPrivateKeyRef.get())
                } else {
                    val generated = VapidKeyGenerator.generate()
                    Pair(generated.publicKey, generated.privateKey)
                }

                transaction {
                    if (dbVapidPublic.isNull_or_blank()) {
                        ClusterSecrets.insert {
                            it[keyName] = "vapid_public_key"
                            it[keyValue] = pub
                            it[updatedAt] = now
                        }
                    }
                    if (dbVapidPrivate.isNull_or_blank()) {
                        ClusterSecrets.insert {
                            it[keyName] = "vapid_private_key"
                            it[keyValue] = priv
                            it[updatedAt] = now
                        }
                    }
                }
                Pair(pub, priv)
            }

                // Check if in-memory keys need updating
                if (sessionSecretRef.get() != masterSessionSecret) {
                    sessionSecretRef.set(masterSessionSecret)
                    changed = true
                }
                if (vapidPublicKeyRef.get() != masterVapidPublic) {
                    vapidPublicKeyRef.set(masterVapidPublic)
                    changed = true
                }
                if (vapidPrivateKeyRef.get() != masterVapidPrivate) {
                    vapidPrivateKeyRef.set(masterVapidPrivate)
                    changed = true
                }

            // If keys were updated from DB or newly generated, persist to local app-config.json
            if (changed || appConfig.server.sessionSecret != masterSessionSecret || appConfig.vapid.publicKey != masterVapidPublic) {
                AppConfigLoader.saveSecretUpdates(
                    sessionSecret = masterSessionSecret,
                    vapidPublicKey = masterVapidPublic,
                    vapidPrivateKey = masterVapidPrivate
                )
            }
        } catch (e: Exception) {
            println("[ClusterSecretService] Warning: Failed to sync secrets with database cluster: ${e.message}")
        }
    }

    /**
     * Starts a background coroutine worker that periodically polls CockroachDB for secret updates.
     * Ensures offline/unreachable nodes catch up automatically upon restoring connection.
     */
    fun startBackgroundSync(appConfig: AppConfig, intervalSeconds: Long = 30) {
        if (syncJob?.isActive == true) return

        syncJob = CoroutineScope(Dispatchers.IO).launch {
            println("[ClusterSecretService] Background cluster secret sync monitor started (polling every $intervalSeconds seconds)...")
            while (isActive) {
                try {
                    delay(intervalSeconds * 1000)
                    syncSecrets(appConfig)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    // Suppress and retry next cycle
                }
            }
        }
    }

    fun stopBackgroundSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * SuperAdmin action: Regenerates sessionSecret and VAPID key pairs across the CockroachDB cluster.
     * Updates CockroachDB `cluster_secrets` table, local in-memory refs, and local config file.
     */
    fun regenerateClusterKeys(appConfig: AppConfig): KeyRegenerationResponse {
        val newSessionSecret = AppConfigLoader.generateSecret()
        val newVapidKeys = VapidKeyGenerator.generate()
        val now = Instant.now()

        transaction {
            upsertSecret("session_secret", newSessionSecret, now)
            upsertSecret("vapid_public_key", newVapidKeys.publicKey, now)
            upsertSecret("vapid_private_key", newVapidKeys.privateKey, now)
        }

        sessionSecretRef.set(newSessionSecret)
        vapidPublicKeyRef.set(newVapidKeys.publicKey)
        vapidPrivateKeyRef.set(newVapidKeys.privateKey)

        AppConfigLoader.saveSecretUpdates(
            sessionSecret = newSessionSecret,
            vapidPublicKey = newVapidKeys.publicKey,
            vapidPrivateKey = newVapidKeys.privateKey
        )

        println("[ClusterSecretService] SuperAdmin regenerated cluster keys (Session & VAPID) across CockroachDB cluster.")

        return KeyRegenerationResponse(
            success = true,
            message = "Successfully regenerated cluster keys (Session Secret & VAPID Keys) across all cluster nodes.",
            updatedAt = now.toString()
        )
    }

    private fun upsertSecret(key: String, value: String, now: Instant) {
        val updatedRows = ClusterSecrets.update({ ClusterSecrets.keyName eq key }) {
            it[keyValue] = value
            it[updatedAt] = now
        }
        if (updatedRows == 0) {
            ClusterSecrets.insert {
                it[keyName] = key
                it[keyValue] = value
                it[updatedAt] = now
            }
        }
    }
}
