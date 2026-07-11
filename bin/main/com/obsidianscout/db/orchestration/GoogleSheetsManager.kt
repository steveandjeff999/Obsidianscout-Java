package com.obsidianscout.db.orchestration

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PeerInfo(val ip: String, val port: Int)

object GoogleSheetsManager {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

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

            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                println("[GoogleSheets] Failed to fetch peers. Status code: ${response.statusCode()}")
                return emptyList()
            }

            val body = response.body()
            return parseResponse(body, sheetUrl)
        } catch (e: Exception) {
            println("[GoogleSheets] Error fetching peers: ${e.message}")
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

            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                println("[GoogleSheets] Successfully registered self to Google Sheet: $localIp:$port")
            } else {
                println("[GoogleSheets] Failed to register self. Status code: ${response.statusCode()}, Response: ${response.body()}")
            }
        } catch (e: Exception) {
            println("[GoogleSheets] Error registering self: ${e.message}")
        }
    }

    /**
     * Parses the response body. Supports:
     * 1. CSV format (if exported from Google Sheets directly)
     * 2. JSON array (if fetched from Google Apps Script Web App)
     */
    fun parseResponse(body: String, url: String): List<Pair<String, Int>> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        
        val looksLikeJson = trimmed.startsWith("[") || trimmed.startsWith("{") || 
                            url.contains("/macros/s/") || url.contains("/exec")
        if (looksLikeJson) {
            try {
                // Try parsing standard JSON list
                val peers = json.decodeFromString<List<PeerInfo>>(trimmed)
                return peers.map { Pair(it.ip, it.port) }
            } catch (e: Exception) {
                println("[GoogleSheets] Failed to parse JSON, falling back to regex: ${e.message}")
                val regex = """"ip"\s*:\s*"([^"]+)"\s*,\s*"port"\s*:\s*(\d+)""".toRegex()
                val matches = regex.findAll(trimmed).map { match ->
                    val ip = match.groupValues[1]
                    val port = match.groupValues[2].toInt()
                    Pair(ip, port)
                }.toList()
                if (matches.isNotEmpty()) {
                    return matches
                }
            }
        }

        // CSV format
        val peers = mutableListOf<Pair<String, Int>>()
        val lines = trimmed.lines()
        if (lines.isEmpty()) return emptyList()

        // Detect if first line is a header
        val firstLine = lines.first()
        val hasHeader = firstLine.contains("ip", ignoreCase = true) || firstLine.contains("port", ignoreCase = true)
        val dataLines = if (hasHeader) lines.drop(1) else lines

        for (line in dataLines) {
            if (line.isBlank()) continue
            val parts = line.split(",")
            if (parts.isNotEmpty()) {
                val ip = parts[0].trim().replace("\"", "").replace("'", "")
                if (ip.isBlank()) continue
                val port = if (parts.size > 1) {
                    parts[1].trim().replace("\"", "").replace("'", "").toIntOrNull() ?: 26257
                } else {
                    26257
                }
                peers.add(Pair(ip, port))
            }
        }
        return peers
    }
}
