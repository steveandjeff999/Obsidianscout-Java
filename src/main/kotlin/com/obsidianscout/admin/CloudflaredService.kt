package com.obsidianscout.admin

import com.obsidianscout.integrations.CloudflaredSettings
import com.obsidianscout.integrations.SettingsService
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

private val consoleLog = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.admin.CloudflaredService")


@Serializable
data class CloudflaredStatus(
    val enabled: Boolean,
    val isInstalled: Boolean,
    val isRunning: Boolean,
    val statusMessage: String,
    val pid: Long? = null,
    val tunnelId: String = ""
)

@Serializable
data class CloudflaredResponse(
    val settings: CloudflaredSettings,
    val status: CloudflaredStatus
)

@Serializable
data class CloudflaredRestartResponse(
    val success: Boolean,
    val status: CloudflaredStatus
)

object CloudflaredService {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var lastStatusMessage: String = "Stopped"

    init {
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                stopTunnel()
            })
        } catch (_: Throwable) {}
    }

    private fun getHostLabel(): String {
        return runCatching {
            val netHost = java.net.InetAddress.getLocalHost().hostName
            if (netHost.isNotBlank() && netHost != "localhost") netHost else null
        }.getOrNull()
            ?: System.getenv("COMPUTERNAME")
            ?: System.getenv("HOSTNAME")
            ?: "ObsidianScoutNode"
    }

    private fun killExistingProcesses() {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        try {
            if (isWindows) {
                val pb = ProcessBuilder("taskkill", "/F", "/IM", "cloudflared.exe")
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                val p = pb.start()
                p.waitFor(3, TimeUnit.SECONDS)
            } else {
                val pb = ProcessBuilder("pkill", "-9", "-f", "cloudflared")
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                pb.redirectError(ProcessBuilder.Redirect.DISCARD)
                val p = pb.start()
                p.waitFor(3, TimeUnit.SECONDS)
            }
            consoleLog.info("[CloudflaredService] Cleaned up existing cloudflared background processes.")
        } catch (e: Throwable) {
            // Ignore if no process was running
        }
    }

    private fun getLocalBinaryFile(): File {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val fileName = if (isWindows) "cloudflared.exe" else "cloudflared"
        val folder = File(".cloudflared")
        return File(folder, fileName)
    }

    private fun testBinary(cmdPath: String): Boolean {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val cmd = if (isWindows && !cmdPath.endsWith(".exe")) {
                listOf("cmd.exe", "/c", "$cmdPath --version")
            } else {
                listOf(cmdPath, "--version")
            }
            val p = ProcessBuilder(cmd).start()
            val exited = p.waitFor(5, TimeUnit.SECONDS)
            exited && p.exitValue() == 0
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * The managed copy in `.cloudflared/` wins over one on PATH: it only exists because this server installed or
     * upgraded it, so preferring it is what lets an upgrade from the cluster page take effect.
     */
    fun findExecutablePath(): String? {
        val localFile = getLocalBinaryFile()
        if (localFile.exists() && testBinary(localFile.absolutePath)) {
            return localFile.absolutePath
        }
        if (testBinary("cloudflared")) {
            return "cloudflared"
        }
        return null
    }

    private val versionRegex = Regex("""(\d{4}\.\d+\.\d+)""")
    private val exactVersionRegex = Regex("""^\d{4}\.\d+\.\d+$""")

    fun isValidVersion(v: String): Boolean = exactVersionRegex.matches(v)

    @Volatile
    private var cachedInstalledVersion: Pair<String?, Long>? = null

    /** The active binary's version, e.g. "2025.10.1"; null when none is installed. Cached for 5 minutes. */
    fun getInstalledVersion(): String? {
        val now = System.currentTimeMillis()
        cachedInstalledVersion?.let { (ver, at) -> if (now - at < 5 * 60_000L) return ver }
        val ver = findExecutablePath()?.let { readBinaryVersion(it) }
        cachedInstalledVersion = ver to now
        return ver
    }

    private fun readBinaryVersion(cmdPath: String): String? = try {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val cmd = if (isWindows && !cmdPath.endsWith(".exe")) listOf("cmd.exe", "/c", "$cmdPath --version") else listOf(cmdPath, "--version")
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(5, TimeUnit.SECONDS)
        versionRegex.find(out)?.groupValues?.get(1)
    } catch (_: Throwable) {
        null
    }

    /** Negative when [a] is older than [b]. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    // ---------------------------------------------------------------- releases

    /** One published cloudflared release: download URL and SHA256 for each file. */
    internal data class ReleaseAsset(val url: String, val githubSha256: String?, val notesSha256: String?)
    internal data class ReleaseInfo(val version: String, val publishedAt: java.time.Instant, val assets: Map<String, ReleaseAsset>)

    /**
     * Cloudflare ships releases almost daily. A release has to be this old before the cluster page offers it, so a
     * broken build has time to be pulled or fixed before it reaches these servers. Any version can still be typed in.
     */
    internal val MIN_RELEASE_AGE: Duration = Duration.ofDays(3)

    private val notesShaLine = Regex("""(?m)^\s*(cloudflared-[A-Za-z0-9_.-]+)\s*:\s*([0-9a-fA-F]{64})\s*$""")

    /** Parses the GitHub releases API response, dropping drafts and pre-releases. */
    internal fun parseReleases(json: String): List<ReleaseInfo> {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> parseRelease(el) }
    }

    internal fun parseRelease(el: kotlinx.serialization.json.JsonElement): ReleaseInfo? {
        val o = el as? kotlinx.serialization.json.JsonObject ?: return null
        fun str(obj: kotlinx.serialization.json.JsonObject, k: String) =
            (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        fun bool(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        if (bool("draft") || bool("prerelease")) return null
        val version = str(o, "tag_name")?.let { versionRegex.find(it)?.groupValues?.get(1) } ?: return null
        val published = str(o, "published_at")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return null
        val notes = notesShaLine.findAll(str(o, "body") ?: "").associate { it.groupValues[1] to it.groupValues[2].lowercase() }
        val assets = (o["assets"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { a ->
            val ao = a as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val name = str(ao, "name") ?: return@mapNotNull null
            val url = str(ao, "browser_download_url") ?: return@mapNotNull null
            val digest = str(ao, "digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
            name to ReleaseAsset(url, digest, notes[name])
        }.toMap()
        return ReleaseInfo(version, published, assets)
    }

    /** Newest release at least [MIN_RELEASE_AGE] old; the oldest listed one if every release is newer than that. */
    internal fun pickRecommended(releases: List<ReleaseInfo>, now: java.time.Instant): ReleaseInfo? {
        val sorted = releases.sortedWith { a, b -> compareVersions(b.version, a.version) }
        return sorted.firstOrNull { Duration.between(it.publishedAt, now) >= MIN_RELEASE_AGE } ?: sorted.lastOrNull()
    }

    @Volatile
    private var cachedReleases: Pair<List<ReleaseInfo>, Long>? = null

    @Volatile
    private var lastReleaseFetchFailureAt: Long = 0L

    /**
     * Recent releases, cached for an hour. A failed fetch isn't retried for 10 minutes, so the cluster page's
     * refreshes don't each wait on GitHub when it can't be reached.
     */
    private fun getReleases(): List<ReleaseInfo> {
        val now = System.currentTimeMillis()
        cachedReleases?.let { (list, at) -> if (now - at < 60 * 60_000L) return list }
        if (now - lastReleaseFetchFailureAt < 10 * 60_000L) return cachedReleases?.first.orEmpty()
        val fetched = githubGet("https://api.github.com/repos/cloudflare/cloudflared/releases?per_page=40")
            ?.let { runCatching { parseReleases(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        if (fetched == null) {
            lastReleaseFetchFailureAt = now
            return cachedReleases?.first.orEmpty()
        }
        cachedReleases = fetched to now
        return fetched
    }

    private fun findRelease(version: String): ReleaseInfo? =
        getReleases().firstOrNull { it.version == version }
            ?: githubGet("https://api.github.com/repos/cloudflare/cloudflared/releases/tags/$version")
                ?.let { runCatching { parseRelease(kotlinx.serialization.json.Json.parseToJsonElement(it)) }.getOrNull() }

    private fun githubGet(url: String): String? = try {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "ObsidianScout-CloudflaredInstaller")
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(10))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) resp.body() else {
            consoleLog.warn("[CloudflaredService] GitHub returned HTTP ${resp.statusCode()} for $url")
            null
        }
    } catch (e: Throwable) {
        consoleLog.warn("[CloudflaredService] Could not reach GitHub: ${e.message}")
        null
    }

    /** Newest published release; null when GitHub can't be reached. */
    fun getLatestVersion(): String? = getReleases().maxWithOrNull { a, b -> compareVersions(a.version, b.version) }?.version

    /** The release the cluster page offers by default (see [MIN_RELEASE_AGE]); null when GitHub can't be reached. */
    fun getRecommendedVersion(): String? = pickRecommended(getReleases(), java.time.Instant.now())?.version

    /**
     * The release file for an OS and CPU. Windows on ARM gets the amd64 build, which it runs under emulation, because
     * Cloudflare publishes no ARM build for Windows. macOS builds only come as `.tgz` archives.
     */
    internal fun assetNameFor(osName: String, osArch: String): String {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        val archKey = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("arm") -> "arm"
            arch.contains("386") || arch.contains("i686") || arch == "x86" -> "386"
            else -> "amd64"
        }
        return when {
            os.contains("win") -> "cloudflared-windows-${if (archKey == "386") "386" else "amd64"}.exe"
            os.contains("mac") || os.contains("darwin") -> "cloudflared-darwin-${if (archKey == "arm64") "arm64" else "amd64"}.tgz"
            else -> "cloudflared-linux-$archKey"
        }
    }

    private fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Downloads [version] for this machine into [dest] and checks its SHA256 against what GitHub and Cloudflare's
     * release notes publish. Refuses a file with no published checksum, or one where the two sources disagree.
     * Returns an error message, or null on success.
     */
    private fun downloadVerified(version: String, dest: File): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val assetName = assetNameFor(System.getProperty("os.name"), System.getProperty("os.arch"))
        val release = findRelease(version) ?: return "release $version not found on GitHub"
        val asset = release.assets[assetName] ?: return "release $version has no $assetName"
        if (asset.githubSha256 != null && asset.notesSha256 != null && asset.githubSha256 != asset.notesSha256) {
            return "GitHub and the release notes list different checksums for $assetName; refusing to install"
        }
        val expected = asset.githubSha256 ?: asset.notesSha256 ?: return "no checksum is published for $assetName; refusing to install"

        dest.parentFile?.mkdirs()
        val isArchive = assetName.endsWith(".tgz")
        val download = if (isArchive) File(dest.parentFile, "${dest.name}.tgz") else dest
        consoleLog.info("[CloudflaredService] Downloading ${asset.url} -> ${download.absolutePath}...")
        try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build()
            val req = HttpRequest.newBuilder()
                .uri(URI.create(asset.url))
                .header("User-Agent", "ObsidianScout-CloudflaredInstaller")
                .timeout(Duration.ofSeconds(180))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(download.toPath()))
            if (resp.statusCode() != 200) {
                download.delete()
                return "download failed with HTTP ${resp.statusCode()}"
            }
        } catch (e: Throwable) {
            download.delete()
            return "download failed: ${e.message ?: e.javaClass.simpleName}"
        }

        val actual = sha256Hex(download)
        if (!actual.equals(expected, ignoreCase = true)) {
            download.delete()
            consoleLog.error("[CloudflaredService] Checksum mismatch for $assetName: expected $expected, got $actual")
            return "checksum mismatch for $assetName; the download was discarded"
        }

        if (isArchive) {
            val unpackDir = File(dest.parentFile, "${dest.name}-unpack")
            unpackDir.deleteRecursively()
            unpackDir.mkdirs()
            try {
                val p = ProcessBuilder("tar", "-xzf", download.absolutePath, "-C", unpackDir.absolutePath)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) return "could not unpack $assetName: $out"
                val bin = unpackDir.walkTopDown().firstOrNull { it.isFile && it.name == "cloudflared" }
                    ?: return "$assetName did not contain a cloudflared binary"
                java.nio.file.Files.move(bin.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally {
                download.delete()
                unpackDir.deleteRecursively()
            }
        }
        if (!isWindows) dest.setExecutable(true, false)
        return null
    }

    // ---------------------------------------------------------------- upgrades

    // Every upgrade state starts with one of these, so peers and the rollout coordinator can read the outcome.
    const val STATE_QUEUED = "Queued"
    const val STATE_RUNNING = "Upgrading"
    const val STATE_UPGRADED = "Upgraded to"
    const val STATE_ALREADY = "Already on"
    const val STATE_FAILED = "Upgrade failed"
    const val STATE_ROLLED_BACK = "Rolled back"

    fun isUpgradeBusy(state: String?): Boolean = state != null && (state.startsWith(STATE_RUNNING) || state.startsWith(STATE_QUEUED))
    fun isUpgradeSuccess(state: String?): Boolean = state != null && (state.startsWith(STATE_UPGRADED) || state.startsWith(STATE_ALREADY))

    /** Short text for the cluster page: blank when idle, otherwise what the last or running upgrade is doing. */
    @Volatile
    var upgradeState: String = ""
        private set

    /** How long a restarted tunnel gets to register a connection with Cloudflare before the upgrade is rolled back. */
    private val HEALTH_TIMEOUT: Duration = Duration.ofSeconds(90)

    /** Starts installing [version] in the background. Returns false if an upgrade is already running here. */
    fun startUpgradeAsync(version: String): Boolean {
        require(isValidVersion(version)) { "Invalid cloudflared version: $version" }
        synchronized(this) {
            if (isUpgradeBusy(upgradeState)) return false
            upgradeState = "$STATE_RUNNING to $version..."
        }
        Thread({
            upgradeState = try {
                installVersion(version)
            } catch (e: Throwable) {
                "$STATE_FAILED: ${e.message}"
            }
            consoleLog.info("[CloudflaredService] Upgrade finished: $upgradeState")
        }, "cloudflared-upgrade").apply { isDaemon = true }.start()
        return true
    }

    /**
     * Installs [version] as the managed binary. When the tunnel is enabled it is restarted on the new version and must
     * register a connection within [HEALTH_TIMEOUT], or the previous binary is put back and the tunnel restarted on it.
     * Returns the resulting state text.
     */
    internal fun installVersion(version: String): String {
        val current = getInstalledVersion()
        if (current == version) return "$STATE_ALREADY $version"

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val target = getLocalBinaryFile()
        target.parentFile?.mkdirs()
        // Keep the .exe extension on Windows so the new binary can be test-run before it replaces the old one.
        val staged = File(target.parentFile, if (isWindows) "cloudflared-new.exe" else "cloudflared-new")
        val previous = File(target.parentFile, if (isWindows) "cloudflared-previous.exe" else "cloudflared-previous")

        downloadVerified(version, staged)?.let { err -> staged.delete(); return "$STATE_FAILED: $err" }
        val stagedVersion = readBinaryVersion(staged.absolutePath)
        if (stagedVersion != version) {
            staged.delete()
            return "$STATE_FAILED: the downloaded binary reported ${stagedVersion ?: "nothing"} instead of $version"
        }

        val enabled = SettingsService.getCloudflaredSettings().enabled
        val hadManagedBinary = target.exists()
        // Swaps happen under the same lock as startTunnel and the first-run install, so they can't interleave.
        synchronized(this) {
            // Windows can't overwrite a running exe, so the tunnel goes down for the swap.
            if (process?.isAlive == true || enabled) stopTunnel()
            try {
                if (hadManagedBinary) {
                    java.nio.file.Files.move(target.toPath(), previous.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                java.nio.file.Files.move(staged.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                if (!isWindows) target.setExecutable(true, false)
            } catch (e: Throwable) {
                if (!target.exists() && hadManagedBinary && previous.exists()) {
                    runCatching { java.nio.file.Files.move(previous.toPath(), target.toPath()) }
                }
                staged.delete()
                cachedInstalledVersion = null
                if (enabled) startTunnel()
                return "$STATE_FAILED while swapping binaries: ${e.message}"
            }
            cachedInstalledVersion = null
        }

        if (!enabled) {
            consoleLog.info("[CloudflaredService] Installed cloudflared $version (tunnel disabled, not started).")
            return "$STATE_UPGRADED $version"
        }

        val healthError = startAndAwaitHealthy()
        if (healthError == null) {
            consoleLog.info("[CloudflaredService] Upgraded cloudflared ${current ?: "(none)"} -> $version; tunnel connected.")
            return "$STATE_UPGRADED $version"
        }

        consoleLog.error("[CloudflaredService] cloudflared $version failed its health check ($healthError); rolling back.")
        synchronized(this) {
            stopTunnel()
            try {
                if (hadManagedBinary && previous.exists()) {
                    java.nio.file.Files.move(previous.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else {
                    // There was no managed copy before, so removing it falls back to the one on PATH.
                    target.delete()
                }
            } catch (e: Throwable) {
                cachedInstalledVersion = null
                return "$STATE_FAILED: $version $healthError, and restoring ${current ?: "the previous binary"} failed: ${e.message}"
            }
            cachedInstalledVersion = null
        }
        val rollbackError = startAndAwaitHealthy()
        val restored = current ?: "the previous binary"
        return if (rollbackError == null) {
            "$STATE_ROLLED_BACK to $restored: $version $healthError"
        } else {
            "$STATE_ROLLED_BACK to $restored: $version $healthError. The tunnel on $restored has not connected yet either ($rollbackError)"
        }
    }

    /** Starts the tunnel and waits for it to register with Cloudflare. Returns null when healthy, else what went wrong. */
    private fun startAndAwaitHealthy(): String? {
        val log = tunnelLogFile()
        val offset = if (log.exists()) log.length() else 0L
        startTunnel()
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT.toMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000)
            if (logShowsConnected(readLogSince(log, offset))) return null
            if (process?.isAlive != true) {
                return "exited right after starting (see ${log.absolutePath})"
            }
        }
        return "did not connect within ${HEALTH_TIMEOUT.seconds}s"
    }

    private fun readLogSince(log: File, offset: Long): String = try {
        java.io.RandomAccessFile(log, "r").use { raf ->
            if (raf.length() <= offset) return ""
            raf.seek(offset)
            val bytes = ByteArray((raf.length() - offset).coerceAtMost(4L * 1024 * 1024).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    } catch (_: Throwable) {
        ""
    }

    /** cloudflared logs this line once per edge connection it opens; one is enough to serve traffic. */
    internal fun logShowsConnected(logText: String): Boolean = logText.contains("Registered tunnel connection")

    private fun tunnelLogFile(): File = File(getLocalBinaryFile().parentFile ?: File(".cloudflared"), "cloudflared.log")

    fun isBinaryInstalled(): Boolean {
        return findExecutablePath() != null
    }

    @Synchronized
    fun autoInstallIfNeeded(): Boolean {
        if (isBinaryInstalled()) {
            return true
        }

        lastStatusMessage = "Downloading and installing cloudflared binary..."
        consoleLog.warn("[CloudflaredService] cloudflared binary missing. Starting auto-installation...")

        val version = getRecommendedVersion()
        if (version == null) {
            lastStatusMessage = "Error: could not reach GitHub to find a cloudflared release"
            return false
        }
        val targetFile = getLocalBinaryFile()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val tempFile = File(targetFile.parentFile, if (isWindows) "cloudflared-install.exe" else "cloudflared-install")
        val err = downloadVerified(version, tempFile)
        if (err != null) {
            tempFile.delete()
            lastStatusMessage = "Error installing cloudflared: $err"
            return false
        }
        try {
            java.nio.file.Files.move(tempFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            tempFile.delete()
            lastStatusMessage = "Error auto-installing cloudflared: ${e.message}"
            return false
        }
        if (!isWindows) targetFile.setExecutable(true, false)
        cachedInstalledVersion = null
        return if (testBinary(targetFile.absolutePath)) {
            consoleLog.info("[CloudflaredService] Installed and verified cloudflared $version at ${targetFile.absolutePath}")
            lastStatusMessage = "cloudflared $version installed"
            true
        } else {
            consoleLog.error("[CloudflaredService] Downloaded binary at ${targetFile.absolutePath} failed execution test.")
            lastStatusMessage = "Error: Downloaded cloudflared binary failed execution test"
            false
        }
    }

    @Synchronized
    /** Cheap per-request check (no settings or PATH lookups) for whether our tunnel process is up. */
    fun isTunnelProcessAlive(): Boolean = process?.isAlive == true

    fun getStatus(): CloudflaredStatus {
        return try {
            val settings = SettingsService.getCloudflaredSettings()
            val installed = isBinaryInstalled()
            val running = process?.isAlive == true
            val pid = if (running) {
                try { process?.pid() } catch (e: Throwable) { null }
            } else null

            val msg = when {
                !settings.enabled -> "Disabled"
                !installed -> lastStatusMessage.ifBlank { "Error: cloudflared binary not found on system PATH" }
                running -> "Running (Tunnel active)"
                else -> lastStatusMessage
            }

            CloudflaredStatus(
                enabled = settings.enabled,
                isInstalled = installed,
                isRunning = running,
                statusMessage = msg,
                pid = pid,
                tunnelId = settings.tunnelId
            )
        } catch (e: Throwable) {
            CloudflaredStatus(
                enabled = false,
                isInstalled = false,
                isRunning = false,
                statusMessage = "Error checking status: ${e.message}"
            )
        }
    }

    @Synchronized
    fun startTunnel(): CloudflaredStatus {
        val settings = SettingsService.getCloudflaredSettings()
        if (!settings.enabled) {
            stopTunnel()
            lastStatusMessage = "Disabled"
            return getStatus()
        }

        if (!isBinaryInstalled()) {
            autoInstallIfNeeded()
        }

        val execPath = findExecutablePath()
        if (execPath == null) {
            stopTunnel()
            if (lastStatusMessage.isBlank() || lastStatusMessage == "Stopped") {
                lastStatusMessage = "Error: cloudflared executable not found and auto-install failed"
            }
            return getStatus()
        }

        // Always clean up existing / orphaned processes first before starting a fresh tunnel
        stopTunnel()
        killExistingProcesses()

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val hostLabel = getHostLabel()
        val command = mutableListOf<String>()

        val baseArgs = listOf(
            execPath,
            "--label", hostLabel
        )

        if (settings.tunnelToken.isNotBlank()) {
            command.addAll(baseArgs)
            command.addAll(listOf("tunnel", "run", "--protocol", "http2", "--token", settings.tunnelToken.trim()))
        } else if (settings.tunnelId.isNotBlank()) {
            val targetUrl = settings.targetUrl.ifBlank { "http://localhost:8080" }
            command.addAll(baseArgs)
            command.addAll(listOf("tunnel", "--url", targetUrl, "run", "--protocol", "http2", settings.tunnelId.trim()))
        } else {
            lastStatusMessage = "Error: Tunnel ID or Tunnel Token must be configured"
            return getStatus()
        }

        try {
            val logFile = tunnelLogFile()
            logFile.parentFile?.mkdirs()

            val pb = if (isWindows && !execPath.endsWith(".exe") && !execPath.contains("\\") && !execPath.contains("/")) {
                ProcessBuilder(listOf("cmd.exe", "/c") + command)
            } else {
                ProcessBuilder(command)
            }
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            val p = pb.start()
            process = p
            lastStatusMessage = "Tunnel process launched (PID ${p.pid()})"
            val loggedCommand = command.mapIndexed { i, arg -> if (i > 0 && command[i - 1] == "--token") "<redacted>" else arg }
            consoleLog.info("[CloudflaredService] Started cloudflared process (PID ${p.pid()}) with command: ${loggedCommand.joinToString(" ")}")
        } catch (e: Exception) {
            lastStatusMessage = "Failed to start tunnel: ${e.message}"
            consoleLog.error("[CloudflaredService] Error starting cloudflared: ${e.message}")
        }

        return getStatus()
    }

    @Synchronized
    fun stopTunnel(): CloudflaredStatus {
        process?.let { p ->
            if (p.isAlive) {
                try {
                    p.destroy()
                    p.waitFor(3, TimeUnit.SECONDS)
                    if (p.isAlive) {
                        p.destroyForcibly()
                    }
                    consoleLog.info("[CloudflaredService] Stopped cloudflared process.")
                } catch (e: Exception) {
                    consoleLog.error("[CloudflaredService] Error stopping cloudflared: ${e.message}")
                }
            }
        }
        process = null
        killExistingProcesses()
        lastStatusMessage = "Stopped"
        return getStatus()
    }

    @Synchronized
    fun updateSettingsAndApply(newSettings: CloudflaredSettings): CloudflaredStatus {
        val updated = SettingsService.updateCloudflaredSettings(newSettings)
        if (updated.enabled) {
            startTunnel()
        } else {
            stopTunnel()
        }
        return getStatus()
    }

    fun initOnStartup() {
        val settings = SettingsService.getCloudflaredSettings()
        if (settings.enabled) {
            consoleLog.info("[CloudflaredService] Cloudflared is enabled by configuration. Starting tunnel...")
            startTunnel()
        } else {
            consoleLog.info("[CloudflaredService] Cloudflared is disabled by configuration.")
        }
    }
}
