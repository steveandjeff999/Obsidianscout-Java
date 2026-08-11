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
}
