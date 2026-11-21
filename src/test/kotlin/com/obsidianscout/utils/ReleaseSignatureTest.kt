package com.obsidianscout.utils

import java.net.http.HttpClient
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReleaseSignatureTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val checksums = "abc  obsidianscout-linux-x64.zip\n".toByteArray()

    private fun sign(data: ByteArray): String {
        val s = Signature.getInstance("Ed25519")
        s.initSign(pair.private)
        s.update(data)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    @Test
    fun verifiesAGoodSignature() {
        assertTrue(ReleaseSignature.verify(checksums, sign(checksums), publicKey))
    }

    @Test
    fun rejectsTamperedDataWrongKeyAndGarbage() {
        val sig = sign(checksums)
        assertFalse(ReleaseSignature.verify("evil  obsidianscout-linux-x64.zip\n".toByteArray(), sig, publicKey))
        val other = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)
        assertFalse(ReleaseSignature.verify(checksums, sig, other))
        assertFalse(ReleaseSignature.verify(checksums, "not base64!", publicKey))
    }

    @Test
    fun missingKeyOrSignatureDoesNotBlockUpdates() {
        val client = HttpClient.newHttpClient()
        assertIs<ReleaseSignature.Result.Unavailable>(ReleaseSignature.check(client, "https://github.com/x/checksums.txt", "https://github.com/x/checksums.txt.sig", "a.zip", publicKeyBase64 = ""))
        assertIs<ReleaseSignature.Result.Unavailable>(ReleaseSignature.check(client, "https://github.com/x/checksums.txt", null, "a.zip", publicKeyBase64 = publicKey))
        // Untrusted hosts are never fetched, so this is "unavailable", not a network call.
        assertIs<ReleaseSignature.Result.Unavailable>(ReleaseSignature.check(client, "https://evil.example/c.txt", "https://evil.example/c.sig", "a.zip", publicKeyBase64 = publicKey))
    }
}
