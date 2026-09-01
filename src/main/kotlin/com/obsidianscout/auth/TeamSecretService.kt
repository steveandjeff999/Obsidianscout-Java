package com.obsidianscout.auth

import com.obsidianscout.db.ClusterSecrets
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

object TeamSecretService {

    /**
     * Returns the stable per-team secret, generating one if none exists.
     * Reuses the existing cluster_secrets table with key_name = "team_secret_<teamNumber>".
     */
    fun getOrCreateTeamSecret(teamNumber: Int): String {
        val key = "team_secret_$teamNumber"
        return transaction {
            val existing = ClusterSecrets
                .selectAll().where { ClusterSecrets.keyName eq key }
                .firstOrNull()
                ?.get(ClusterSecrets.keyValue)
            if (existing != null && existing.isNotBlank()) {
                existing
            } else {
                val secret = generateSecret()
                val now = Instant.now()
                val updatedRows = ClusterSecrets.update({ ClusterSecrets.keyName eq key }) {
                    it[keyValue] = secret
                    it[updatedAt] = now
                }
                if (updatedRows == 0) {
                    ClusterSecrets.insert {
                        it[keyName] = key
                        it[keyValue] = secret
                        it[updatedAt] = now
                    }
                }
                secret
            }
        }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
