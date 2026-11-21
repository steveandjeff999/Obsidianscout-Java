package com.obsidianscout.utils

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64

/**
 * Optional Ed25519 signature over a release's `checksums.txt`, published as `checksums.txt.sig` (base64).
 *
 * The SHA-256 checks alone come from the same GitHub release as the package, so they catch corruption but not a
 * tampered release. A signature made with an offline key closes that gap. It is best effort by design: when no key is
 * pinned in this build, or a release has no signature, updates proceed on the SHA-256 checks as before. Only a
 * signature that is present and does not verify stops an update, because that means the release was altered.
 */
object ReleaseSignature {
    const val SIGNATURE_ASSET = "checksums.txt.sig"

    /**
     * Base64 X.509 (SubjectPublicKeyInfo) Ed25519 public key that release signatures must match. Empty disables
     * verification. Generate a pair with `ReleaseSigner keygen` (see scripts/sign-release.*) and paste the public key.
     */
    const val PINNED_PUBLIC_KEY = ""

    private val TRUSTED_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    sealed class Result {
        /** The signature verified; [sha256] is the asset's hash from the signed checksums.txt (null if not listed). */
        data class Verified(val sha256: String?) : Result()
        /** Could not check (no key pinned, no signature published, download failed). The update may continue. */
        data class Unavailable(val reason: String) : Result()
        /** A signature is published but does not match: the release was altered. The update must stop. */
        data class Invalid(val reason: String) : Result()
    }

    fun verify(data: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(key)
        sig.update(data)
        sig.verify(Base64.getMimeDecoder().decode(signatureBase64.trim()))
    }.getOrDefault(false)

    /** Checks the signed checksums.txt for [assetName]. Never throws. */
    fun check(
        client: HttpClient,
        checksumsUrl: String?,
        signatureUrl: String?,
        assetName: String,
        publicKeyBase64: String = PINNED_PUBLIC_KEY
    ): Result {
        if (publicKeyBase64.isBlank()) return Result.Unavailable("no release signing key is pinned in this build")
        if (signatureUrl == null) return Result.Unavailable("the release publishes no $SIGNATURE_ASSET")
        if (checksumsUrl == null) return Result.Unavailable("the release publishes no checksums.txt")
        val checksums = fetch(client, checksumsUrl) ?: return Result.Unavailable("could not download checksums.txt")
        val signature = fetch(client, signatureUrl) ?: return Result.Unavailable("could not download $SIGNATURE_ASSET")
        if (!verify(checksums, String(signature, Charsets.UTF_8), publicKeyBase64)) {
            return Result.Invalid("$SIGNATURE_ASSET does not match checksums.txt for the pinned key")
        }
        return Result.Verified(UpdateValidator.findChecksumForAsset(String(checksums, Charsets.UTF_8), assetName))
    }

    private fun fetch(client: HttpClient, url: String): ByteArray? = runCatching {
        val uri = URI.create(url.trim())
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host?.lowercase() !in TRUSTED_HOSTS) return null
        val res = client.send(
            HttpRequest.newBuilder().uri(uri).header("User-Agent", "ObsidianScout-Updater").timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )
        if (res.statusCode() == 200) res.body() else null
    }.getOrNull()
}

/**
 * Release signing tool, run on the maintainer's machine (the private key never goes to CI or GitHub):
 *   keygen <private-key-file>            writes the private key, prints the public key to pin
 *   sign <private-key-file> <checksums>  writes <checksums>.sig next to it
 */
object ReleaseSigner {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "keygen" -> {
                val out = File(args.getOrNull(1) ?: usage())
                if (out.exists()) error("${out.path} already exists; refusing to overwrite a signing key")
                val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                out.writeText(Base64.getEncoder().encodeToString(pair.private.encoded) + "\n")
                runCatching { out.setReadable(false, false); out.setReadable(true, true) }
                println("Private key written to ${out.path}. Keep it offline and backed up.")
                println("Public key (paste into ReleaseSignature.PINNED_PUBLIC_KEY):")
                println(Base64.getEncoder().encodeToString(pair.public.encoded))
            }
            "sign" -> {
                val keyFile = File(args.getOrNull(1) ?: usage())
                val target = File(args.getOrNull(2) ?: usage())
                val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyFile.readText().trim())))
                val sig = Signature.getInstance("Ed25519")
                sig.initSign(key)
                sig.update(target.readBytes())
                val out = File(target.path + ".sig")
                out.writeText(Base64.getEncoder().encodeToString(sig.sign()) + "\n")
                println("Wrote ${out.path}")
            }
            else -> usage()
        }
    }

    private fun usage(): Nothing {
        System.err.println("Usage: ReleaseSigner keygen <private-key-file> | sign <private-key-file> <checksums.txt>")
        kotlin.system.exitProcess(2)
    }
}
