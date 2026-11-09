package com.obsidianscout.security

import com.obsidianscout.auth.ClusterCryptoUtils
import com.obsidianscout.auth.ClusterSecretService
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A small computing puzzle used in place of a CAPTCHA: no third party, nothing to show the user, and it works for the
 * app too. The server hands out a signed nonce; the client finds a counter such that SHA-256("nonce:counter") starts
 * with [PowChallengeDto.difficulty] zero bits (about a second at 16 bits) and sends "token:counter" back.
 *
 * Tokens are stateless (HMAC with the cluster session secret, bound to the client IP, valid five minutes) and each is
 * accepted once.
 */
object ProofOfWork {
    const val HEADER = "X-Security-Challenge"
    private const val TTL_MS = 5 * 60_000L
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    private fun ipTag(ip: String) = ClusterCryptoUtils.sha256Hex(ip.toByteArray()).take(16)

    fun issue(ip: String, difficulty: Int = SecuritySettings.config.pow_difficulty_bits, now: Long = System.currentTimeMillis()): PowChallengeDto {
        val nonceBytes = ByteArray(16).also { random.nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val bits = difficulty.coerceIn(8, 24)
        val expiresAt = now + TTL_MS
        val payload = "$nonce.$bits.$expiresAt.${ipTag(ip)}"
        val sig = ClusterCryptoUtils.hmacSha256("pow|$payload", ClusterSecretService.getSessionSecret())
        val token = b64.encodeToString(payload.toByteArray()) + "." + sig
        return PowChallengeDto(token = token, nonce = nonce, difficulty = bits, expiresAt = expiresAt)
    }

    enum class Result { OK, MISSING, INVALID, EXPIRED, WRONG_ANSWER, REUSED }

    fun verify(answer: String?, ip: String, now: Long = System.currentTimeMillis(), consume: Boolean = true): Result {
        if (answer.isNullOrBlank()) return Result.MISSING
        if (answer.length > 600) return Result.INVALID
        val colon = answer.lastIndexOf(':')
        if (colon <= 0) return Result.INVALID
        val token = answer.substring(0, colon)
        val counter = answer.substring(colon + 1)
        if (counter.isEmpty() || counter.length > 20 || !counter.all { it.isDigit() }) return Result.INVALID
        val dot = token.lastIndexOf('.')
        if (dot <= 0) return Result.INVALID
        val payload = runCatching { String(b64d.decode(token.substring(0, dot))) }.getOrNull() ?: return Result.INVALID
        val sig = token.substring(dot + 1)
        if (!ClusterCryptoUtils.verifyHmac("pow|$payload", sig, ClusterSecretService.getSessionSecret())) return Result.INVALID
        val parts = payload.split('.')
        if (parts.size != 4) return Result.INVALID
        val nonce = parts[0]
        val bits = parts[1].toIntOrNull() ?: return Result.INVALID
        val expiresAt = parts[2].toLongOrNull() ?: return Result.INVALID
        if (parts[3] != ipTag(ip)) return Result.INVALID
        if (now > expiresAt) return Result.EXPIRED
        if (!meetsDifficulty(nonce, counter, bits)) return Result.WRONG_ANSWER
        if (consume && !SecurityStore.claimOnce("pow:$sig", TTL_MS + 60_000L, now)) return Result.REUSED
        return Result.OK
    }

    fun meetsDifficulty(nonce: String, counter: String, bits: Int): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest("$nonce:$counter".toByteArray())
        var remaining = bits
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (remaining >= 8) {
                if (v != 0) return false
                remaining -= 8
            } else {
                return remaining == 0 || (v shr (8 - remaining)) == 0
            }
            if (remaining == 0) return true
        }
        return true
    }

    /** Solves a challenge (tests, and a reference for the clients). */
    fun solve(nonce: String, bits: Int): String {
        var i = 0L
        while (!meetsDifficulty(nonce, i.toString(), bits)) i++
        return i.toString()
    }
}
