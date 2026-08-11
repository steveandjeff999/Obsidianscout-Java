package com.obsidianscout.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ClusterCryptoUtils {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    private fun deriveKey(secret: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(secret.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts plaintext string using AES-256-GCM with a key derived from secret.
     * Returns Base64-encoded string containing [IV (12 bytes) + CipherText + AuthTag].
     */
    fun encrypt(plainText: String, secret: String): String {
        if (plainText.isEmpty()) return ""
        val key = deriveKey(secret)
        val iv = ByteArray(IV_LENGTH_BYTE)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance(ALGORITHM)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = iv + cipherText
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts Base64-encoded payload produced by [encrypt].
     */
    fun decrypt(encryptedBase64: String, secret: String): String {
        if (encryptedBase64.isEmpty()) return ""
        val combined = Base64.getDecoder().decode(encryptedBase64)
        if (combined.size < IV_LENGTH_BYTE) throw IllegalArgumentException("Invalid encrypted payload size")
        val iv = combined.copyOfRange(0, IV_LENGTH_BYTE)
        val cipherText = combined.copyOfRange(IV_LENGTH_BYTE, combined.size)
        val key = deriveKey(secret)
        val cipher = Cipher.getInstance(ALGORITHM)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)
        val plainTextBytes = cipher.doFinal(cipherText)
        return String(plainTextBytes, StandardCharsets.UTF_8)
    }

    /**
     * Generates HMAC-SHA256 signature for data string using secret key.
     */
    fun hmacSha256(data: String, secret: String): String {
        val sha256HMAC = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        sha256HMAC.init(secretKey)
        val bytes = sha256HMAC.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies HMAC-SHA256 signature in constant-time.
     */
    fun verifyHmac(data: String, signature: String, secret: String): Boolean {
        if (signature.isBlank()) return false
        val expected = hmacSha256(data, secret)
        return MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), signature.toByteArray(StandardCharsets.UTF_8))
    }
}
