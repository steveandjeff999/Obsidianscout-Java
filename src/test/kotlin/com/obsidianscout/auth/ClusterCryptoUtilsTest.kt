package com.obsidianscout.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClusterCryptoUtilsTest {

    @Test
    fun testEncryptionAndDecryptionRoundtrip() {
        val originalSecret = "my-super-secret-cluster-key-12345"
        val sampleJsonConfig = """
            {
                "server": {
                    "port": 8080,
                    "sessionSecret": "secret-abc-xyz"
                },
                "db_password": "super-secret-db-password"
            }
        """.trimIndent()

        val encrypted = ClusterCryptoUtils.encrypt(sampleJsonConfig, originalSecret)
        assertNotEquals(sampleJsonConfig, encrypted, "Encrypted payload should not match plaintext")
        assertTrue(encrypted.isNotBlank(), "Encrypted payload should not be blank")

        val decrypted = ClusterCryptoUtils.decrypt(encrypted, originalSecret)
        assertEquals(sampleJsonConfig, decrypted, "Decrypted payload should match original plaintext configuration")
    }

    @Test
    fun testHmacSignatureVerification() {
        val secret = "cluster-hmac-secret-999"
        val timestamp = System.currentTimeMillis().toString()
        val method = "POST"
        val path = "/api/admin/cluster/nodes/local/reboot"

        val dataToSign = "$timestamp:$method:$path"
        val signature = ClusterCryptoUtils.hmacSha256(dataToSign, secret)

        assertTrue(ClusterCryptoUtils.verifyHmac(dataToSign, signature, secret), "Valid HMAC signature should be verified successfully")

        val tamperedData = "$timestamp:$method:/api/admin/cluster/nodes/local/reinstall-update"
        assertFalse(ClusterCryptoUtils.verifyHmac(tamperedData, signature, secret), "Signature check should fail for tampered path")

        assertFalse(ClusterCryptoUtils.verifyHmac(dataToSign, signature, "wrong-secret"), "Signature check should fail with invalid secret key")
    }

    @Test
    fun testPayloadHmacVerificationAndTampering() {
        val secret = "cluster-hmac-secret-999"
        val timestamp = System.currentTimeMillis().toString()
        val method = "PUT"
        val path = "/api/admin/cluster/nodes/local/quorum-fallback/config"
        val payload = """{"enabled": true, "retentionDays": 7}""".toByteArray(Charsets.UTF_8)

        val payloadSha256 = ClusterCryptoUtils.sha256Hex(payload)
        val dataToSign = ClusterCryptoUtils.buildSigningData(timestamp, method, path, payloadSha256)
        val signature = ClusterCryptoUtils.hmacSha256(dataToSign, secret)

        // Valid signature with unchanged payload
        assertTrue(
            ClusterCryptoUtils.verifyHmac(dataToSign, signature, secret),
            "Valid payload HMAC signature should be verified successfully"
        )

        // Tampered payload in flight
        val tamperedPayload = """{"enabled": false, "retentionDays": 7}""".toByteArray(Charsets.UTF_8)
        val tamperedPayloadSha256 = ClusterCryptoUtils.sha256Hex(tamperedPayload)
        val tamperedData = ClusterCryptoUtils.buildSigningData(timestamp, method, path, tamperedPayloadSha256)

        assertFalse(
            ClusterCryptoUtils.verifyHmac(tamperedData, signature, secret),
            "Tampered payload must cause HMAC verification to fail"
        )
    }

    @Test
    fun testEmptyPayloadHmac() {
        val emptyBytes = ByteArray(0)
        val emptySha256 = ClusterCryptoUtils.sha256Hex(emptyBytes)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            emptySha256,
            "Empty byte array must match standard SHA-256 digest"
        )
        assertEquals(
            emptySha256,
            ClusterCryptoUtils.EMPTY_BODY_SHA256,
            "EMPTY_BODY_SHA256 constant must match empty byte array SHA-256"
        )
    }
}
