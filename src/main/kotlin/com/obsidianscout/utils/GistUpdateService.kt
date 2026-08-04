package com.obsidianscout.utils

import com.obsidianscout.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream

/**
 * GistUpdateService
 *
 * Polls a GitHub Gist JSON endpoint on a configurable interval. When:
 *   - update.required == true AND latest_version > current_version: downloads and stages the
 *     update, then exits so the run script can apply it.
 *   - scheduled_restart.enabled == true AND restart_at_utc is in the past: exits cleanly so
 *     the run script can restart the process.
 *
 * The service is a no-op unless gist_update.enabled == true in app-config.json.
 */
object GistUpdateService {
    private val log = LoggerFactory.getLogger("GistUpdateService")
    private var scope: CoroutineScope? = null

    /** Lenient parser used for all network responses. */
    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Pretty printer used when writing merged config files. */
    private val prettyJson = Json { prettyPrint = true }

    // Tracks the boot time of the JVM to prevent restarting if the scheduled time is in the past
    // relative to when this process started.
    private val bootInstant = Instant.now()

    // Tracks the scheduled-restart timestamp we have already acted on so we don't
    // trigger the same restart more than once per configured window.
    @Volatile
    private var lastActedRestartTimestamp: String? = null

    fun start(appConfig: AppConfig) {
        if (!appConfig.gist_update.enabled) {
            log.info("[GistUpdate] Remote update polling is disabled. Set gist_update.enabled=true in app-config.json to enable.")
            return
        }
        log.info("[GistUpdate] Starting — will poll every ${appConfig.gist_update.check_interval_minutes} min. Current version: ${appConfig.current_version}")
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        newScope.launch {
            // Slight delay on first check to let the server fully start up.
            delay(30_000L)
            while (isActive) {
                try {
                    checkGist(appConfig)
                } catch (e: Exception) {
                    log.warn("[GistUpdate] Check failed: ${e.message}")
                }
                delay(appConfig.gist_update.check_interval_minutes * 60_000L)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    // Reusable single HttpClient instance to avoid thread & memory leaks from repeated instantiation
    private val sharedClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private fun checkGist(appConfig: AppConfig) {
        log.info("[GistUpdate] Polling gist...")

        val request = HttpRequest.newBuilder()
            .uri(URI.create(appConfig.gist_update.gist_url))
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "ObsidianScout-GistUpdater")
            .timeout(java.time.Duration.ofSeconds(15))
            .build()
        val response = try {
            sharedClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("[GistUpdate] Failed to reach gist URL: ${e.message}")
            return
        }

        if (response.statusCode() != 200) {
            log.warn("[GistUpdate] Gist returned HTTP ${response.statusCode()}, skipping.")
            return
        }

        val json = try {
            lenientJson.parseToJsonElement(response.body()).jsonObject
        } catch (e: Exception) {
            log.warn("[GistUpdate] Failed to parse gist JSON: ${e.message}")
            return
        }

        // ── Update check ──────────────────────────────────────────────────────────────────
        val updateBlock = json["update"]?.jsonObject
        val updateRequired = updateBlock?.get("required")?.jsonPrimitive?.boolean ?: false
        val latestVersion = updateBlock?.get("latest_version")?.jsonPrimitive?.content ?: ""

        if (updateRequired && latestVersion.isNotBlank()) {
            val current = appConfig.current_version
            if (isNewerVersion(latestVersion, current)) {
                log.info("[GistUpdate] Update available: $current -> $latestVersion. Starting download...")
                applyUpdate(latestVersion, sharedClient)
                return // applyUpdate calls exit() on success
            } else {
                log.info("[GistUpdate] Server is up to date (version $current, latest $latestVersion).")
            }
        }

        // ── Scheduled restart check ───────────────────────────────────────────────────────
        val restartBlock = json["scheduled_restart"]?.jsonObject
        val restartEnabled = restartBlock?.get("enabled")?.jsonPrimitive?.boolean ?: false
        val restartAtUtc = restartBlock?.get("restart_at_utc")?.jsonPrimitive?.content ?: ""

        if (restartEnabled && restartAtUtc.isNotBlank() && restartAtUtc != lastActedRestartTimestamp) {
            val restartInstant = try {
                Instant.parse(restartAtUtc)
            } catch (e: DateTimeParseException) {
                log.warn("[GistUpdate] Could not parse restart_at_utc: $restartAtUtc")
                null
            }
            if (restartInstant != null) {
                val now = Instant.now()
                // Only restart if:
                // 1. The scheduled time has passed (restartInstant <= now)
                // 2. The scheduled time was set for AFTER this server process booted (restartInstant > bootInstant)
                if (now.isAfter(restartInstant) && restartInstant.isAfter(bootInstant)) {
                    val reason = restartBlock?.get("reason")?.jsonPrimitive?.content ?: ""
                    val reasonText = if (reason.isNotBlank()) " Reason: $reason" else ""
                    log.info("[GistUpdate] Scheduled restart time ($restartAtUtc) has passed.$reasonText Shutting down for restart...")
                    lastActedRestartTimestamp = restartAtUtc
                    Thread.sleep(2000) // allow logs to flush
                    Runtime.getRuntime().exit(0)
                } else if (restartInstant.isBefore(bootInstant)) {
                    log.info("[GistUpdate] Scheduled restart time ($restartAtUtc) is in the past relative to server boot time ($bootInstant). Skipping to prevent loop.")
                    // Mark as acted so we don't spam the log about skipping it
                    lastActedRestartTimestamp = restartAtUtc
                }
            }
        }
    }

    /**
     * Queries the GitHub Releases API to find the zip asset URL for [version], then
     * downloads, extracts, merges config files, and writes .update_result for the
     * run script to pick up.
     */
    private fun applyUpdate(version: String, httpClient: HttpClient) {
        val zipUrl = resolveZipUrl(version, httpClient)
        if (zipUrl == null) {
            log.error("[GistUpdate] Could not resolve a downloadable zip for version $version. Update aborted.")
            return
        }

        val tempDir = try {
            Files.createTempDirectory("obsidianscout-update-").toFile()
        } catch (e: Exception) {
            log.error("[GistUpdate] Failed to create temp directory: ${e.message}")
            return
        }

        val zipFile = File(tempDir, "update.zip")
        log.info("[GistUpdate] Downloading $zipUrl ...")

        val zipRequest = HttpRequest.newBuilder().uri(URI.create(zipUrl)).build()
        val zipResponse = try {
            httpClient.send(zipRequest, HttpResponse.BodyHandlers.ofFile(zipFile.toPath()))
        } catch (e: Exception) {
            log.error("[GistUpdate] Download failed: ${e.message}")
            tempDir.deleteRecursively()
            return
        }

        if (zipResponse.statusCode() != 200) {
            log.error("[GistUpdate] Download returned HTTP ${zipResponse.statusCode()}")
            tempDir.deleteRecursively()
            return
        }

        log.info("[GistUpdate] Extracting bundle...")
        val extractDir = File(tempDir, "extracted")
        try {
            unzipFile(zipFile, extractDir)
        } catch (e: Exception) {
            log.error("[GistUpdate] Extraction failed: ${e.message}")
            tempDir.deleteRecursively()
            return
        }

        val srcRoot = extractDir.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals("obsidianscout-server.jar", ignoreCase = true) }
            ?.parentFile

        if (srcRoot == null) {
            log.error("[GistUpdate] obsidianscout-server.jar not found in extracted bundle.")
            tempDir.deleteRecursively()
            return
        }

        mergeConfigs(srcRoot)

        try {
            File(".update_result").writeText(srcRoot.absolutePath)
        } catch (e: Exception) {
            log.error("[GistUpdate] Failed to write .update_result: ${e.message}")
            tempDir.deleteRecursively()
            return
        }

        log.info("[GistUpdate] Update staged successfully. Shutting down so run script can apply version $version...")
        Thread.sleep(2000)
        Runtime.getRuntime().exit(0)
    }

    /**
     * Calls the GitHub Releases API to find a .zip asset for the given version tag.
     * Falls back to the zipball_url if no explicit asset is found.
     */
    private fun resolveZipUrl(version: String, httpClient: HttpClient): String? {
        val apiUrl = "https://api.github.com/repos/steveandjeff999/Obsidianscout-Java/releases/tags/$version"
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) return null

            val releaseJson = lenientJson.parseToJsonElement(res.body()).jsonObject

            // Look for a .zip asset in the assets array
            var zipUrl: String? = null
            val assetsRaw = releaseJson["assets"]?.toString() ?: "[]"
            val parts = assetsRaw.split("\"browser_download_url\":\"")
            for (part in parts.drop(1)) {
                val url = part.substringBefore("\"")
                if (url.endsWith(".zip")) {
                    zipUrl = url
                    break
                }
            }

            // Fallback to the GitHub-generated zipball_url
            if (zipUrl.isNullOrBlank()) {
                zipUrl = releaseJson["zipball_url"]?.jsonPrimitive?.content
            }
            zipUrl
        } catch (e: Exception) {
            log.warn("[GistUpdate] Could not resolve zip URL from GitHub API: ${e.message}")
            null
        }
    }

    private fun mergeConfigs(srcRoot: File) {
        val srcConfig = File(srcRoot, "config")
        if (!srcConfig.exists() || !srcConfig.isDirectory) return
        val destConfig = File("config")
        destConfig.mkdirs()

        srcConfig.walkTopDown().filter { it.isFile }.forEach { srcFile ->
            val relPath = srcFile.relativeTo(srcConfig).path
            val userFile = File(destConfig, relPath)
            userFile.parentFile?.mkdirs()

            if (srcFile.name.endsWith(".json")) {
                if (userFile.exists() && userFile.length() > 0) {
                    try {
                        val userJson = lenientJson.parseToJsonElement(userFile.readText())
                        val defaultJson = lenientJson.parseToJsonElement(srcFile.readText())
                        var merged = deepMergeJson(userJson, defaultJson)

                        // Force current_version to use the new default value so the server knows it updated
                        if (srcFile.name == "app-config.json" && merged is JsonObject) {
                            val newVersionVal = defaultJson.jsonObject["current_version"]
                            if (newVersionVal != null) {
                                val mutableMap = merged.toMutableMap()
                                mutableMap["current_version"] = newVersionVal
                                merged = JsonObject(mutableMap)
                            }
                        }

                        userFile.writeText(
                            prettyJson.encodeToString(JsonElement.serializer(), merged) + "\n"
                        )
                        log.info("[GistUpdate] Merged config/$relPath")
                    } catch (e: Exception) {
                        log.warn("[GistUpdate] Failed to merge config/$relPath, overwriting with default.")
                        srcFile.copyTo(userFile, overwrite = true)
                    }
                } else {
                    log.info("[GistUpdate] Adding new config config/$relPath")
                    srcFile.copyTo(userFile)
                }
            } else {
                if (userFile.exists()) {
                    srcFile.copyTo(File(destConfig, "$relPath.new"), overwrite = true)
                } else {
                    srcFile.copyTo(userFile)
                }
            }
        }
    }

    private fun unzipFile(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                val file = File(destDir, entryName)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun deepMergeJson(user: JsonElement, default: JsonElement): JsonElement {
        if (user is JsonObject && default is JsonObject) {
            val map = mutableMapOf<String, JsonElement>()
            for ((key, defaultValue) in default) {
                val userValue = user[key]
                map[key] = if (userValue == null) defaultValue else deepMergeJson(userValue, defaultValue)
            }
            for ((key, userValue) in user) {
                if (!map.containsKey(key)) map[key] = userValue
            }
            return JsonObject(map)
        }
        return user
    }

    /**
     * Returns true if [candidate] is strictly newer than [current] using
     * numeric segment-by-segment comparison (supports N.N.N.N style versions).
     */
    private fun isNewerVersion(candidate: String, current: String): Boolean {
        val c = candidate.trimStart('v').split(".").mapNotNull { it.toIntOrNull() }
        val cur = current.trimStart('v').split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(c.size, cur.size)
        for (i in 0 until maxLen) {
            val cv = c.getOrElse(i) { 0 }
            val curv = cur.getOrElse(i) { 0 }
            if (cv > curv) return true
            if (cv < curv) return false
        }
        return false
    }
}
