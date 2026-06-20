package com.obsidianscout.utils

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

object VapidKeyGenerator {

    data class VapidKeys(val publicKey: String, val privateKey: String)

    fun generate(): VapidKeys {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        val ecPublicKey = kp.public as ECPublicKey
        val ecPrivateKey = kp.private as ECPrivateKey

        val w = ecPublicKey.w
        val xBytes = w.affineX.toUnsignedByteArray(32)
        val yBytes = w.affineY.toUnsignedByteArray(32)
        val publicKeyBytes = byteArrayOf(0x04) + xBytes + yBytes
        val publicKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKeyBytes)

        val privateKeyBytes = ecPrivateKey.s.toUnsignedByteArray(32)
        val privateKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(privateKeyBytes)

        return VapidKeys(publicKeyBase64, privateKeyBase64)
    }

    private fun BigInteger.toUnsignedByteArray(length: Int): ByteArray {
        val bytes = this.toByteArray()
        if (bytes.size == length) return bytes
        val result = ByteArray(length)
        if (bytes.size > length) {
            System.arraycopy(bytes, bytes.size - length, result, 0, length)
        } else {
            System.arraycopy(bytes, 0, result, length - bytes.size, bytes.size)
        }
        return result
    }
}
