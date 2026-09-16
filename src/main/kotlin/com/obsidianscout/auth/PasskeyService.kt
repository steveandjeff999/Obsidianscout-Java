package com.obsidianscout.auth

import com.obsidianscout.config.AppConfigLoader
import com.obsidianscout.db.PasskeyChallenges
import com.obsidianscout.db.PasskeyCredentials
import com.obsidianscout.db.Users
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.authenticator.AuthenticatorImpl
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.*
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.COSEKey
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.CollectedClientData
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.util.*

@Serializable
data class PasskeyCredentialDto(
    val id: String,
    val credentialId: String,
    val friendlyName: String,
    val createdAt: String,
    val lastUsedAt: String? = null
)

object PasskeyService {
    private val objectConverter = ObjectConverter()
    private val jsonConverter = objectConverter.jsonConverter
    private val cborConverter = objectConverter.cborConverter
    private val webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
    private val secureRandom = SecureRandom()

    fun getEffectiveRpIdAndOrigin(clientOrigin: String? = null, hostHeader: String? = null): Pair<String, Origin> {
        // Auto-resolve dynamically from client Origin or Host header if present
        val candidate = clientOrigin?.takeIf { it.isNotBlank() } ?: hostHeader?.takeIf { it.isNotBlank() }
        if (!candidate.isNullOrBlank()) {
            val parsed = try {
                if (candidate.startsWith("http://") || candidate.startsWith("https://")) URI(candidate) else URI("http://$candidate")
            } catch (_: Exception) { null }

            val rawHost = parsed?.host ?: candidate.substringBefore(":").trim()
            if (rawHost.isNotBlank()) {
                val candidateLower = rawHost.lowercase()
                val isLocal = candidateLower == "localhost" || candidateLower == "127.0.0.1"
                val rpId = if (candidateLower == "127.0.0.1") "localhost" else rawHost

                // Determine scheme: clientOrigin scheme -> parsed scheme -> https (unless localhost)
                val scheme = if (isLocal) {
                    parsed?.scheme?.takeIf { it.isNotBlank() } ?: "http"
                } else {
                    if (clientOrigin?.startsWith("http://") == true) "http" else "https"
                }

                val port = parsed?.port?.takeIf { it != -1 && it != 80 && it != 443 }
                val portPart = if (port != null) ":$port" else ""
                val originStr = "$scheme://$rawHost$portPart"
                return Pair(rpId, Origin.create(originStr))
            }
        }

        // Fallback to configured site_url in config.json
        val appConfig = AppConfigLoader.load()
        val siteUrl = appConfig.getEffectiveSiteUrl()
        val uri = try {
            URI(siteUrl)
        } catch (_: Exception) {
            URI("https://kotlin.obsidianscout.com")
        }
        val rpId = uri.host ?: "localhost"
        val scheme = if (uri.scheme.isNullOrBlank()) "https" else uri.scheme
        val portPart = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
        val originStr = "$scheme://$rpId$portPart"
        return Pair(rpId, Origin.create(originStr))
    }

    private fun purgeExpiredChallenges() {
        try {
            transaction {
                PasskeyChallenges.deleteWhere { expiresAt less Instant.now() }
            }
        } catch (_: Exception) {}
    }

    /**
     * Phase 1 of Registration: generate creation options + challenge
     */
    fun beginRegistration(userSession: UserSession, clientOrigin: String? = null, hostHeader: String? = null): Map<String, Any?> {
        purgeExpiredChallenges()

        val rawChallenge = ByteArray(32).also { secureRandom.nextBytes(it) }
        val challengeBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rawChallenge)

        val userUuid = UUID.fromString(userSession.userId)
        transaction {
            PasskeyChallenges.insert {
                it[challenge] = challengeBase64Url
                it[userId] = userUuid
                it[flow] = "register"
                it[expiresAt] = Instant.now().plusSeconds(300)
            }
        }

        val (rpId, _) = getEffectiveRpIdAndOrigin(clientOrigin, hostHeader)
        val userIdBytes = userSession.userId.toByteArray(Charsets.UTF_8)
        val userHandleBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(userIdBytes)

        // List existing credentials to exclude
        val existingCredIds = transaction {
            PasskeyCredentials.selectAll().where { PasskeyCredentials.userId eq userUuid }
                .map { it[PasskeyCredentials.credentialId] }
        }

        val excludeCredentials = existingCredIds.map { credId ->
            mapOf(
                "type" to "public-key",
                "id" to credId,
                "transports" to listOf("internal", "hybrid", "usb", "nfc", "ble")
            )
        }

        return mapOf(
            "challenge" to challengeBase64Url,
            "rp" to mapOf(
                "name" to "ObsidianScout",
                "id" to rpId
            ),
            "user" to mapOf(
                "id" to userHandleBase64Url,
                "name" to userSession.username,
                "displayName" to "${userSession.username} (Team ${userSession.teamNumber})"
            ),
            "pubKeyCredParams" to listOf(
                mapOf("type" to "public-key", "alg" to -7),   // ES256
                mapOf("type" to "public-key", "alg" to -257)  // RS256
            ),
            "timeout" to 60000,
            "attestation" to "none",
            "excludeCredentials" to excludeCredentials,
            "authenticatorSelection" to mapOf(
                "residentKey" to "preferred",
                "requireResidentKey" to false,
                "userVerification" to "preferred"
            )
        )
    }

    fun beginRegistrationJson(userSession: UserSession, clientOrigin: String? = null, hostHeader: String? = null): String {
        val options = beginRegistration(userSession, clientOrigin, hostHeader)
        return jsonConverter.writeValueAsString(options)
    }

    /**
     * Phase 2 of Registration: finish attestation verification and store credential
     */
    fun finishRegistration(
        userSession: UserSession,
        credentialId: String,
        clientDataJSONBase64: String,
        attestationObjectBase64: String,
        friendlyName: String? = null,
        clientOrigin: String? = null,
        hostHeader: String? = null
    ): PasskeyCredentialDto {
        purgeExpiredChallenges()

        val clientDataBytes = Base64.getUrlDecoder().decode(clientDataJSONBase64)
        val attestationBytes = Base64.getUrlDecoder().decode(attestationObjectBase64)

        val clientData = jsonConverter.readValue(ByteArrayInputStream(clientDataBytes), CollectedClientData::class.java)
            ?: throw ApiException(HttpStatusCode.BadRequest, "Failed to parse clientDataJSON")
        val challengeBytes = clientData.challenge?.value
            ?: throw ApiException(HttpStatusCode.BadRequest, "Missing challenge in clientDataJSON")
        val challengeBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)

        val userUuid = UUID.fromString(userSession.userId)

        // Validate challenge from DB
        val challengeRow = transaction {
            PasskeyChallenges.selectAll().where {
                (PasskeyChallenges.challenge eq challengeBase64Url) and
                (PasskeyChallenges.flow eq "register") and
                (PasskeyChallenges.userId eq userUuid)
            }.firstOrNull()
        } ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid or expired registration challenge")

        // Consume challenge immediately
        transaction {
            PasskeyChallenges.deleteWhere { PasskeyChallenges.id eq challengeRow[PasskeyChallenges.id] }
        }

        val (rpId, origin) = getEffectiveRpIdAndOrigin(clientOrigin, hostHeader)
        val serverProperty = ServerProperty(
            origin,
            rpId,
            DefaultChallenge(Base64.getUrlDecoder().decode(challengeBase64Url)),
            null
        )

        val registrationRequest = RegistrationRequest(
            attestationBytes,
            clientDataBytes
        )
        val registrationParameters = RegistrationParameters(
            serverProperty,
            null,
            false
        )

        val registrationData = try {
            webAuthnManager.validate(registrationRequest, registrationParameters)
        } catch (e: Exception) {
            throw ApiException(HttpStatusCode.BadRequest, "WebAuthn attestation validation failed: ${e.message}")
        }

        val authData = registrationData.attestationObject?.authenticatorData
        val attestedCredentialData = authData?.attestedCredentialData
            ?: throw ApiException(HttpStatusCode.BadRequest, "Missing attested credential data")

        val coseKeyBytes = cborConverter.writeValueAsBytes(attestedCredentialData.coseKey)
        val coseKeyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(coseKeyBytes)
        val aaguidStr = attestedCredentialData.aaguid.value.toString()
        val name = friendlyName?.takeIf { it.isNotBlank() } ?: "Passkey (${Instant.now().toString().take(10)})"

        val now = Instant.now()
        val insertedId = transaction {
            PasskeyCredentials.insertAndGetId {
                it[PasskeyCredentials.userId] = userUuid
                it[PasskeyCredentials.credentialId] = credentialId
                it[publicKeyCose] = coseKeyBase64Url
                it[signCount] = authData.signCount
                it[aaguid] = aaguidStr
                it[PasskeyCredentials.friendlyName] = name
                it[createdAt] = now
                it[lastUsedAt] = now
            }
        }

        return PasskeyCredentialDto(
            id = insertedId.value.toString(),
            credentialId = credentialId,
            friendlyName = name,
            createdAt = now.toString(),
            lastUsedAt = now.toString()
        )
    }

    /**
     * Phase 1 of Authentication: generate request options + challenge
     */
    fun beginAuthentication(
        username: String? = null,
        teamNumber: Int? = null,
        program: String = "FRC",
        clientOrigin: String? = null,
        hostHeader: String? = null
    ): Map<String, Any?> {
        purgeExpiredChallenges()

        val rawChallenge = ByteArray(32).also { secureRandom.nextBytes(it) }
        val challengeBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rawChallenge)

        val (userUuid, allowCredentials) = if (!username.isNullOrBlank() && teamNumber != null && teamNumber > 0) {
            val userRow = transaction {
                Users.selectAll().where {
                    (Users.username eq username.trim()) and
                    (Users.teamNumber eq teamNumber) and
                    (Users.program.lowerCase() eq program.trim().lowercase())
                }.firstOrNull()
            }
            if (userRow != null) {
                val uId = userRow[Users.id].value
                val creds = transaction {
                    PasskeyCredentials.selectAll().where { PasskeyCredentials.userId eq uId }
                        .map { it[PasskeyCredentials.credentialId] }
                }
                Pair(uId, creds.map { mapOf("type" to "public-key", "id" to it) })
            } else {
                Pair(null, emptyList<Map<String, String>>())
            }
        } else {
            Pair(null, emptyList<Map<String, String>>())
        }

        transaction {
            PasskeyChallenges.insert {
                it[challenge] = challengeBase64Url
                it[userId] = userUuid
                it[flow] = "authenticate"
                it[expiresAt] = Instant.now().plusSeconds(300)
            }
        }

        val (rpId, _) = getEffectiveRpIdAndOrigin(clientOrigin, hostHeader)
        return mapOf(
            "challenge" to challengeBase64Url,
            "rpId" to rpId,
            "timeout" to 60000,
            "userVerification" to "preferred",
            "allowCredentials" to allowCredentials
        )
    }

    fun beginAuthenticationJson(
        username: String? = null,
        teamNumber: Int? = null,
        program: String? = null,
        clientOrigin: String? = null,
        hostHeader: String? = null
    ): String {
        val options = beginAuthentication(username, teamNumber, program ?: "FRC", clientOrigin, hostHeader)
        return jsonConverter.writeValueAsString(options)
    }

    /**
     * Phase 2 of Authentication: verify assertion signature, update sign count, return UserRecord
     */
    fun finishAuthentication(
        credentialId: String,
        clientDataJSONBase64: String,
        authenticatorDataBase64: String,
        signatureBase64: String,
        userHandleBase64: String? = null,
        clientOrigin: String? = null,
        hostHeader: String? = null
    ): UserRecord {
        purgeExpiredChallenges()

        val clientDataBytes = Base64.getUrlDecoder().decode(clientDataJSONBase64)
        val authDataBytes = Base64.getUrlDecoder().decode(authenticatorDataBase64)
        val signatureBytes = Base64.getUrlDecoder().decode(signatureBase64)
        val credIdBytes = Base64.getUrlDecoder().decode(credentialId)

        val clientData = jsonConverter.readValue(ByteArrayInputStream(clientDataBytes), CollectedClientData::class.java)
            ?: throw ApiException(HttpStatusCode.BadRequest, "Failed to parse clientDataJSON")
        val challengeBytes = clientData.challenge?.value
            ?: throw ApiException(HttpStatusCode.BadRequest, "Missing challenge in clientDataJSON")
        val challengeBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)

        // Find and consume challenge
        val challengeRow = transaction {
            PasskeyChallenges.selectAll().where {
                (PasskeyChallenges.challenge eq challengeBase64Url) and
                (PasskeyChallenges.flow eq "authenticate")
            }.firstOrNull()
        } ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid or expired authentication challenge")

        transaction {
            PasskeyChallenges.deleteWhere { PasskeyChallenges.id eq challengeRow[PasskeyChallenges.id] }
        }

        // Fetch stored credential
        val credRow = transaction {
            PasskeyCredentials.selectAll().where { PasskeyCredentials.credentialId eq credentialId }.firstOrNull()
        } ?: throw ApiException(HttpStatusCode.Unauthorized, "Unrecognized passkey credential")

        val storedUserId = credRow[PasskeyCredentials.userId].value
        val storedCoseKeyBytes = Base64.getUrlDecoder().decode(credRow[PasskeyCredentials.publicKeyCose])
        val storedCoseKey = cborConverter.readValue(ByteArrayInputStream(storedCoseKeyBytes), COSEKey::class.java)
            ?: throw ApiException(HttpStatusCode.InternalServerError, "Corrupted stored passkey public key")
        val storedSignCount = credRow[PasskeyCredentials.signCount]
        val storedAaguidStr = credRow[PasskeyCredentials.aaguid]
        val aaguid = if (storedAaguidStr.isNotBlank()) runCatching { AAGUID(storedAaguidStr) }.getOrDefault(AAGUID.ZERO) else AAGUID.ZERO

        val attestedCredData = AttestedCredentialData(aaguid, credIdBytes, storedCoseKey)
        val authenticator = AuthenticatorImpl(
            attestedCredData,
            NoneAttestationStatement(),
            storedSignCount
        )

        val (rpId, origin) = getEffectiveRpIdAndOrigin(clientOrigin, hostHeader)
        val serverProperty = ServerProperty(
            origin,
            rpId,
            DefaultChallenge(Base64.getUrlDecoder().decode(challengeBase64Url)),
            null
        )

        val authenticationRequest = AuthenticationRequest(
            credIdBytes,
            userHandleBase64?.let { Base64.getUrlDecoder().decode(it) },
            authDataBytes,
            clientDataBytes,
            signatureBytes
        )

        val authenticationParameters = AuthenticationParameters(
            serverProperty,
            authenticator,
            null,
            false
        )

        val authData = try {
            webAuthnManager.validate(authenticationRequest, authenticationParameters)
        } catch (e: Exception) {
            throw ApiException(HttpStatusCode.Unauthorized, "WebAuthn authentication failed: ${e.message}")
        }

        // Update signCount and lastUsedAt
        val newSignCount = authData.authenticatorData?.signCount ?: (storedSignCount + 1)
        val now = Instant.now()
        transaction {
            PasskeyCredentials.update({ PasskeyCredentials.id eq credRow[PasskeyCredentials.id] }) {
                it[signCount] = newSignCount
                it[lastUsedAt] = now
            }
        }

        // Return user record
        val userRow = transaction {
            Users.selectAll().where { Users.id eq storedUserId }.firstOrNull()
        } ?: throw ApiException(HttpStatusCode.Unauthorized, "User for passkey no longer exists")

        return AuthService.rowToUser(userRow).copy(lastLogin = now.toString())
    }

    /**
     * Lists all registered passkeys for a given user session
     */
    fun listCredentials(userSession: UserSession): List<PasskeyCredentialDto> {
        val userUuid = UUID.fromString(userSession.userId)
        return transaction {
            PasskeyCredentials.selectAll().where { PasskeyCredentials.userId eq userUuid }
                .orderBy(PasskeyCredentials.createdAt to SortOrder.DESC)
                .map {
                    PasskeyCredentialDto(
                        id = it[PasskeyCredentials.id].value.toString(),
                        credentialId = it[PasskeyCredentials.credentialId],
                        friendlyName = it[PasskeyCredentials.friendlyName],
                        createdAt = it[PasskeyCredentials.createdAt].toString(),
                        lastUsedAt = it[PasskeyCredentials.lastUsedAt]?.toString()
                    )
                }
        }
    }

    /**
     * Deletes a passkey for a user.
     */
    fun deleteCredential(userSession: UserSession, credentialRecordId: String): Boolean {
        val userUuid = UUID.fromString(userSession.userId)
        val credUuid = runCatching { UUID.fromString(credentialRecordId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid credential record ID")

        return transaction {
            val count = PasskeyCredentials.deleteWhere {
                (PasskeyCredentials.id eq credUuid) and (PasskeyCredentials.userId eq userUuid)
            }
            count > 0
        }
    }
}
