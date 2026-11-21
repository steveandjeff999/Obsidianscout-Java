package com.obsidianscout.security

import java.security.MessageDigest
import java.util.Base64

/**
 * The Content-Security-Policy header. Scripts never get 'unsafe-inline': a page's own inline <script> blocks are
 * allowed by their SHA-256 hashes (computed when the page is rendered, see [forPage]), so markup injected into a page
 * can't run script, and inline event handlers (onclick="...") are refused everywhere.
 */
object ContentSecurityPolicy {
    private const val SCRIPT_SOURCES = "'self' 'wasm-unsafe-eval' https://static.cloudflareinsights.com"

    private const val REST = "worker-src 'self' blob:; style-src 'self' 'unsafe-inline' https://cdnjs.cloudflare.com; " +
        "img-src 'self' data: blob:; font-src 'self' data: https://cdnjs.cloudflare.com; " +
        "connect-src 'self' https://cloudflareinsights.com; frame-src https://www.youtube-nocookie.com; " +
        "frame-ancestors 'self'; object-src 'none'; " +
        "base-uri 'self'; form-action 'self';"

    /** For everything that isn't a rendered page (assets, errors, non-JSON API responses). No inline script at all. */
    const val DEFAULT = "default-src 'self'; script-src $SCRIPT_SOURCES; script-src-attr 'none'; $REST"

    /** The policy for a rendered page whose inline scripts hash to [hashes] (each already `'sha256-...'`). */
    fun forHashes(hashes: Collection<String>): String {
        if (hashes.isEmpty()) return DEFAULT
        return "default-src 'self'; script-src $SCRIPT_SOURCES ${hashes.joinToString(" ")}; script-src-attr 'none'; $REST"
    }

    // An executable script element without src: no type, a JavaScript MIME type, or a module. Data blocks
    // (application/ld+json, text/template, importmap is not used) don't run and need no hash.
    private val INLINE_SCRIPT = Regex("""<script\b([^>]*)>(.*?)</script\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val SRC_ATTR = Regex("""\bsrc\s*=""", RegexOption.IGNORE_CASE)
    private val TYPE_ATTR = Regex("""\btype\s*=\s*["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE)
    private val JS_TYPES = setOf("", "module", "text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript")

    /** `'sha256-...'` sources for every inline executable script in [html], in page order, without duplicates. */
    fun inlineScriptHashes(html: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in INLINE_SCRIPT.findAll(html)) {
            val attrs = m.groupValues[1]
            if (SRC_ATTR.containsMatchIn(attrs)) continue
            val type = TYPE_ATTR.find(attrs)?.groupValues?.get(1)?.lowercase().orEmpty()
            if (type !in JS_TYPES) continue
            out += hashSource(m.groupValues[2])
        }
        return out.toList()
    }

    /** The browser hashes the script text after HTML input normalisation, which turns CRLF and lone CR into LF. */
    fun hashSource(scriptText: String): String {
        val normalized = scriptText.replace("\r\n", "\n").replace('\r', '\n')
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return "'sha256-" + Base64.getEncoder().encodeToString(digest) + "'"
    }

    fun forPage(html: String): String = forHashes(inlineScriptHashes(html))
}
