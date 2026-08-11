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
            println("[CloudflaredService] Cleaned up existing cloudflared background processes.")
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

    fun findExecutablePath(): String? {
        if (testBinary("cloudflared")) {
            return "cloudflared"
        }
        val localFile = getLocalBinaryFile()
        if (localFile.exists() && testBinary(localFile.absolutePath)) {
            return localFile.absolutePath
        }
        return null
    }

    fun isBinaryInstalled(): Boolean {
        return findExecutablePath() != null
    }

    @Synchronized
    fun autoInstallIfNeeded(): Boolean {
        if (isBinaryInstalled()) {
            return true
        }

        lastStatusMessage = "Downloading and installing cloudflared binary..."
        println("[CloudflaredService] cloudflared binary missing. Starting auto-installation...")

        try {
            val osName = System.getProperty("os.name").lowercase()
            val isWindows = osName.contains("win")
            val isMac = osName.contains("mac") || osName.contains("darwin")

            val archName = System.getProperty("os.arch").lowercase()
            val archKey = when {
                archName.contains("aarch64") || archName.contains("arm64") -> "arm64"
                archName.contains("arm") -> "arm"
                archName.contains("386") || archName.contains("i686") -> "386"
                else -> "amd64"
            }

            val binaryName = when {
                isWindows -> "cloudflared-windows-${archKey}.exe"
                isMac -> "cloudflared-darwin-${if (archKey == "arm64") "arm64" else "amd64"}"
                else -> "cloudflared-linux-${archKey}"
            }

            val downloadUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/$binaryName"
            val targetFile = getLocalBinaryFile()
            targetFile.parentFile?.mkdirs()

            println("[CloudflaredService] Downloading $downloadUrl -> ${targetFile.absolutePath}...")

            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build()

            val req = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("User-Agent", "ObsidianScout-CloudflaredInstaller")
                .timeout(Duration.ofSeconds(120))
                .build()

            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tempFile.toPath()))

            if (resp.statusCode() == 200) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                if (!isWindows) {
                    targetFile.setExecutable(true, false)
                }
                if (testBinary(targetFile.absolutePath)) {
                    println("[CloudflaredService] Successfully auto-installed and verified cloudflared at ${targetFile.absolutePath}")
                    lastStatusMessage = "cloudflared auto-installed successfully"
                    return true
                } else {
                    println("[CloudflaredService] Downloaded binary at ${targetFile.absolutePath} failed execution test.")
                    lastStatusMessage = "Error: Downloaded cloudflared binary failed execution test"
                    return false
                }
            } else {
                tempFile.delete()
                println("[CloudflaredService] Download failed with HTTP status ${resp.statusCode()}")
                lastStatusMessage = "Error downloading cloudflared: HTTP ${resp.statusCode()}"
                return false
            }
        } catch (e: Throwable) {
            println("[CloudflaredService] Auto-installation failed: ${e.message}")
            lastStatusMessage = "Error auto-installing cloudflared: ${e.message}"
            return false
        }
    }

    @Synchronized
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
            val logFile = File(getLocalBinaryFile().parentFile ?: File(".cloudflared"), "cloudflared.log")
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
            println("[CloudflaredService] Started cloudflared process (PID ${p.pid()}) with command: ${command.joinToString(" ")}")
        } catch (e: Exception) {
            lastStatusMessage = "Failed to start tunnel: ${e.message}"
            println("[CloudflaredService] Error starting cloudflared: ${e.message}")
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
                    println("[CloudflaredService] Stopped cloudflared process.")
                } catch (e: Exception) {
                    println("[CloudflaredService] Error stopping cloudflared: ${e.message}")
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
            println("[CloudflaredService] Cloudflared is enabled by configuration. Starting tunnel...")
            startTunnel()
        } else {
            println("[CloudflaredService] Cloudflared is disabled by configuration.")
        }
    }
}
