package com.obsidianscout.db.orchestration

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PeerInfo(
    val ip: String,
    val port: Int = 26257,
    val name: String? = null,
    val server_name: String? = null,
    val hostname: String? = null
) {
    val displayName: String?
        get() = name?.takeIf { it.isNotBlank() }
            ?: server_name?.takeIf { it.isNotBlank() }
            ?: hostname?.takeIf { it.isNotBlank() }
}

data class PeerDetails(
    val ip: String,
    val port: Int,
    val name: String? = null
)

object GoogleSheetsManager {
    @Volatile
    private var cachedHttpClient: HttpClient? = null

    internal fun getHttpClient(): HttpClient {
        var c = cachedHttpClient
        if (c == null) {
            synchronized(this) {
                c = cachedHttpClient
                if (c == null) {
                    c = buildNewHttpClient()
                    cachedHttpClient = c
                }
            }
        }
        return c!!
    }

    private fun buildNewHttpClient(): HttpClient {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build()
    }

    internal fun resetHttpClient() {
        try {
            synchronized(this) {
                cachedHttpClient = buildNewHttpClient()
            }
        } catch (e: Exception) {
            println("[GoogleSheets] Failed to recreate HttpClient: ${e.message}")
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val ipRegex = """^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""".toRegex()

    /**
     * Normalizes standard Google Sheets sharing URLs into CSV export format.
     */
    fun normalizeUrl(sheetUrl: String): String {
        return if (sheetUrl.contains("docs.google.com/spreadsheets") && sheetUrl.contains("/edit")) {
            sheetUrl.substringBefore("/edit") + "/export?format=csv"
        } else {
            sheetUrl
        }
    }

    /**
     * Fetches the list of active peer IPs and target ports from the Google Sheet.
     */
    fun fetchPeers(sheetUrl: String, password: String): List<Pair<String, Int>> {
        return fetchPeerDetails(sheetUrl, password).map { Pair(it.ip, it.port) }
    }

    /**
     * Fetches the list of active peer details (IP, port, name) from the Google Sheet.
     */
    fun fetchPeerDetails(sheetUrl: String, password: String): List<PeerDetails> {
        if (sheetUrl.isBlank()) return emptyList()
        val normalizedUrl = normalizeUrl(sheetUrl)
        try {
            val uriWithAuth = if (password.isNotBlank()) {
                val separator = if (normalizedUrl.contains("?")) "&" else "?"
                URI.create("$normalizedUrl${separator}password=${java.net.URLEncoder.encode(password, "UTF-8")}")
            } else {
                URI.create(normalizedUrl)
            }

            val requestBuilder = HttpRequest.newBuilder()
                .uri(uriWithAuth)
                .timeout(Duration.ofSeconds(10))
                .GET()

            if (password.isNotBlank()) {
                requestBuilder.header("X-Sheet-Password", password)
            }

            val response = getHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                println("[GoogleSheets] Failed to fetch peers. Status code: ${response.statusCode()}")
                return emptyList()
            }

            val body = response.body()
            return parsePeerDetails(body, sheetUrl)
        } catch (e: Exception) {
            println("[GoogleSheets] Error fetching peers: ${e.message}")
            if (e.message?.contains("selector manager closed") == true || e is java.io.IOException) {
                resetHttpClient()
            }
            return emptyList()
        }
    }

    /**
     * Writes the local Tailscale host IP and configured port back to the Google Sheet.
     */
    fun registerSelf(sheetUrl: String, password: String, localIp: String, port: Int) {
        if (sheetUrl.isBlank()) return
        try {
            val uriWithAuth = if (password.isNotBlank()) {
                val separator = if (sheetUrl.contains("?")) "&" else "?"
                URI.create("$sheetUrl${separator}password=${java.net.URLEncoder.encode(password, "UTF-8")}")
            } else {
                URI.create(sheetUrl)
            }

            val jsonPayload = """{"ip":"$localIp","port":$port}"""
            val requestBuilder = HttpRequest.newBuilder()
                .uri(uriWithAuth)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))

            if (password.isNotBlank()) {
                requestBuilder.header("X-Sheet-Password", password)
            }

            val response = getHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                println("[GoogleSheets] Successfully registered self to Google Sheet: $localIp:$port")
            } else {
                println("[GoogleSheets] Failed to register self. Status code: ${response.statusCode()}, Response: ${response.body()}")
            }
        } catch (e: Exception) {
            println("[GoogleSheets] Error registering self: ${e.message}")
            if (e.message?.contains("selector manager closed") == true || e is java.io.IOException) {
                resetHttpClient()
            }
        }
    }

    /**
     * Legacy wrapper for parsePeerDetails, returning List<Pair<String, Int>>.
     */
    fun parseResponse(body: String, url: String): List<Pair<String, Int>> {
        return parsePeerDetails(body, url).map { Pair(it.ip, it.port) }
    }

    /**
     * Parses the response body into PeerDetails. Supports:
     * 1. CSV format (if exported from Google Sheets directly)
     * 2. JSON array (if fetched from Google Apps Script Web App)
     */
    fun parsePeerDetails(body: String, url: String): List<PeerDetails> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val looksLikeJson = trimmed.startsWith("[") || trimmed.startsWith("{") ||
                            url.contains("/macros/s/") || url.contains("/exec")
        if (looksLikeJson) {
            try {
                // Try parsing standard JSON list
                val peers = json.decodeFromString<List<PeerInfo>>(trimmed)
                return peers.map { PeerDetails(it.ip, it.port, it.displayName) }
            } catch (e: Exception) {
                println("[GoogleSheets] Failed to parse JSON, falling back to regex: ${e.message}")
                val regex = """"ip"\s*:\s*"([^"]+)"\s*,\s*"port"\s*:\s*(\d+)(?:\s*,\s*"(?:name|server_name|hostname)"\s*:\s*"([^"]+)")?""".toRegex()
                val matches = regex.findAll(trimmed).map { match ->
                    val ip = match.groupValues[1]
                    val port = match.groupValues[2].toInt()
                    val name = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
                    PeerDetails(ip, port, name)
                }.toList()
                if (matches.isNotEmpty()) {
                    return matches
                }
            }
        }

        // CSV format
        val peers = mutableListOf<PeerDetails>()
        val lines = trimmed.lines()
        if (lines.isEmpty()) return emptyList()

        val firstLine = lines.first()
        val firstLineParts = firstLine.split(",").map { it.trim().replace("\"", "").replace("'", "") }
        val hasHeader = firstLineParts.any { part ->
            part.equals("ip", ignoreCase = true) ||
            part.equals("port", ignoreCase = true) ||
            part.equals("name", ignoreCase = true) ||
            part.equals("server_name", ignoreCase = true) ||
            part.equals("server", ignoreCase = true) ||
            part.equals("hostname", ignoreCase = true)
        }

        if (hasHeader) {
            val ipIdx = firstLineParts.indexOfFirst { it.equals("ip", ignoreCase = true) }
            val portIdx = firstLineParts.indexOfFirst { it.equals("port", ignoreCase = true) }
            val nameIdx = firstLineParts.indexOfFirst {
                it.equals("name", ignoreCase = true) ||
                it.equals("server_name", ignoreCase = true) ||
                it.equals("server", ignoreCase = true) ||
                it.equals("hostname", ignoreCase = true)
            }

            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val parts = line.split(",").map { it.trim().replace("\"", "").replace("'", "") }
                val ip = if (ipIdx in parts.indices) parts[ipIdx] else ""
                if (ip.isBlank()) continue
                val port = if (portIdx in parts.indices) parts[portIdx].toIntOrNull() ?: 26257 else 26257
                val name = if (nameIdx in parts.indices && parts[nameIdx].isNotBlank()) parts[nameIdx] else null
                peers.add(PeerDetails(ip, port, name))
            }
        } else {
            for (line in lines) {
                if (line.isBlank()) continue
                val parts = line.split(",").map { it.trim().replace("\"", "").replace("'", "") }
                if (parts.isEmpty()) continue

                val ip = parts.firstOrNull { ipRegex.matches(it) } ?: ""
                if (ip.isBlank()) continue

                val ipIndex = parts.indexOf(ip)
                val port = parts.mapIndexedNotNull { idx, s -> if (idx != ipIndex) s.toIntOrNull() else null }.firstOrNull() ?: 26257
                val portStr = port.toString()
                val name = parts.firstOrNull { it != ip && it != portStr && it.isNotBlank() }

                peers.add(PeerDetails(ip, port, name))
            }
        }
        return peers
    }
}
