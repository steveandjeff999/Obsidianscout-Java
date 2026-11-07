package com.obsidianscout.blob

import com.obsidianscout.config.BlobStorageConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.blob.SeaweedOrchestrator")

/**
 * Downloads the SeaweedFS `weed` binary for this platform and runs its master and volume server as child
 * processes, the same way CockroachOrchestrator runs CockroachDB. Everything lives in `.seaweedfs/` next to
 * the server binary, so an update that replaces the bundle keeps the stored data.
 */
class SeaweedOrchestrator(private val config: BlobStorageConfig) {

    val rootDir: File = try {
        val uri = SeaweedOrchestrator::class.java.protectionDomain.codeSource.location.toURI()
        val jarFile = File(uri)
        val parent = if (jarFile.isFile) jarFile.parentFile else File(".")
        File(parent, ".seaweedfs")
    } catch (e: Exception) {
        File(".seaweedfs")
    }

    private val osName = System.getProperty("os.name").lowercase()
    private val archName = System.getProperty("os.arch").lowercase()
    private val isWindows = osName.contains("win")
    private val isArm = archName.contains("arm") || archName.contains("aarch64")
    private val binaryFile = File(rootDir, if (isWindows) "weed.exe" else "weed")
    private val versionMarker = File(rootDir, "weed.version")

    @Volatile private var masterProcess: Process? = null
    @Volatile private var volumeProcess: Process? = null
    @Volatile var volumeMaster: String? = null
        private set
    @Volatile var masterIp: String? = null
        private set

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** SeaweedFS publishes these three builds; anything else (macOS, Windows ARM) keeps blobs on local disk. */
    private val assetName: String? = when {
        isWindows && !isArm -> "windows_amd64.zip"
        osName.contains("linux") && isArm -> "linux_arm64.tar.gz"
        osName.contains("linux") -> "linux_amd64.tar.gz"
        else -> null
    }

    val isPlatformSupported: Boolean get() = assetName != null

    @Volatile private var leftoversCleared = false

    /**
     * Stops every process still running this install's own weed binary, once per run. After a crash or a forced
     * kill they keep the ports, and only this server ever starts that file, so any copy running now is a leftover.
     */
    private fun killLeftovers() {
        if (leftoversCleared) return
        leftoversCleared = true
        val ours = runCatching { binaryFile.canonicalPath }.getOrNull() ?: return
        ProcessHandle.allProcesses().filter { h ->
            h.info().command().map { cmd -> runCatching { File(cmd).canonicalPath.equals(ours, ignoreCase = isWindows) }.getOrDefault(false) }.orElse(false)
        }.forEach { h ->
            log.info("[SeaweedFS] Stopping leftover weed process ${h.pid()}")
            h.destroy()
            runCatching { h.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS) }
            if (h.isAlive) h.destroyForcibly()
        }
    }

    /** Downloads the pinned release if it isn't installed yet. Returns false when SeaweedFS can't run here. */
    @Synchronized
    fun ensureInstalled(): Boolean {
        val asset = assetName ?: run {
            log.warn("[SeaweedFS] No SeaweedFS build for $osName/$archName. Photos and files will be stored on local disk.")
            return false
        }
        rootDir.mkdirs()
        if (binaryFile.exists() && versionMarker.exists() && versionMarker.readText().trim() == config.seaweed_version && testBinary()) {
            killLeftovers()
            return true
        }
        // Stop leftovers of the previous version before its file is replaced.
        if (binaryFile.exists()) killLeftovers()

        val base = "https://github.com/seaweedfs/seaweedfs/releases/download/${config.seaweed_version}/$asset"
        val archive = File(rootDir, asset)
        log.info("[SeaweedFS] Downloading SeaweedFS ${config.seaweed_version} from $base ...")
        try {
            download(base, archive)
            val expectedMd5 = runCatching { fetchText("$base.md5").trim().split(Regex("\\s+")).first().lowercase() }.getOrNull()
            if (!expectedMd5.isNullOrBlank()) {
                val actual = md5(archive)
                if (actual != expectedMd5) {
                    archive.delete()
                    throw IllegalStateException("Checksum mismatch for $asset (expected $expectedMd5, got $actual)")
                }
            } else {
                log.warn("[SeaweedFS] Could not fetch the checksum for $asset; continuing without verification.")
            }

            val staged = File(rootDir, binaryFile.name + ".new")
            staged.delete()
            if (asset.endsWith(".zip")) extractFromZip(archive, binaryFile.name, staged) else extractFromTarGz(archive, binaryFile.name, staged)
            archive.delete()
            if (!staged.exists()) throw IllegalStateException("${binaryFile.name} was not found in $asset")
            staged.setExecutable(true, false)

            // The old binary may still be running (an upgrade): stop it before swapping the file.
            stopAll()
            Files.move(staged.toPath(), binaryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            binaryFile.setExecutable(true, false)
            if (!testBinary()) throw IllegalStateException("Downloaded weed binary does not run on this machine")
            versionMarker.writeText(config.seaweed_version)
            log.info("[SeaweedFS] Installed SeaweedFS ${config.seaweed_version} at ${binaryFile.absolutePath}")
            return true
        } catch (e: Exception) {
            archive.delete()
            log.error("[SeaweedFS] Installing SeaweedFS failed: ${e.message}")
            return binaryFile.exists() && testBinary()
        }
    }

    private fun testBinary(): Boolean = try {
        val p = ProcessBuilder(binaryFile.absolutePath, "version").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && out.contains("version")
    } catch (e: Exception) {
        false
    }

    /** Starts (or keeps) the master bound to [ip]. A master holds almost no state: volume servers re-report their volumes to a new one. */
    @Synchronized
    fun ensureMaster(ip: String) {
        val running = masterProcess
        if (running != null && running.isAlive && masterIp == ip) return
        stopMaster()
        killStale("master")
        val dir = File(rootDir, "master").apply { mkdirs() }
        val cmd = listOf(
            binaryFile.absolutePath, "master",
            "-ip=$ip", "-ip.bind=$ip",
            "-port=${config.master_port}",
            "-mdir=${dir.absolutePath}",
            "-peers=none",
            "-volumeSizeLimitMB=1024",
            "-defaultReplication=000"
        )
        masterProcess = launch(cmd, "master")
        masterIp = ip
        log.info("[SeaweedFS] Master started on $ip:${config.master_port}")
    }

    /** Starts (or restarts) the local volume server so it reports to [master] ("ip:port"). */
    @Synchronized
    fun ensureVolume(ip: String, master: String) {
        val running = volumeProcess
        if (running != null && running.isAlive && volumeMaster == master) return
        stopVolume()
        killStale("volume")
        val dir = File(rootDir, "volume").apply { mkdirs() }
        val cmd = listOf(
            binaryFile.absolutePath, "volume",
            "-ip=$ip", "-ip.bind=$ip",
            "-port=${config.volume_port}",
            "-dir=${dir.absolutePath}",
            "-max=0",
            "-minFreeSpace=2",
            "-master=$master"
        )
        volumeProcess = launch(cmd, "volume")
        volumeMaster = master
        log.info("[SeaweedFS] Volume server started on $ip:${config.volume_port}, reporting to master $master")
    }

    fun isMasterRunning(): Boolean = masterProcess?.isAlive == true
    fun isVolumeRunning(): Boolean = volumeProcess?.isAlive == true

    @Synchronized
    fun stopMaster() {
        // The pid file is only ours to remove when this run started the process; otherwise it names a leftover to kill.
        masterProcess?.let { stopProcess(it, "master"); pidFile("master").delete() }
        masterProcess = null
        masterIp = null
    }

    @Synchronized
    fun stopVolume() {
        // The pid file is only ours to remove when this run started the process; otherwise it names a leftover to kill.
        volumeProcess?.let { stopProcess(it, "volume"); pidFile("volume").delete() }
        volumeProcess = null
        volumeMaster = null
    }

    fun stopAll() {
        stopVolume()
        stopMaster()
    }

    private fun launch(cmd: List<String>, role: String): Process {
        val logFile = File(rootDir, "$role.log")
        if (logFile.exists() && logFile.length() > 20L * 1024 * 1024) {
            val old = File(rootDir, "$role.log.1")
            old.delete()
            logFile.renameTo(old)
        }
        val process = ProcessBuilder(cmd)
            .directory(rootDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        pidFile(role).writeText(process.pid().toString())
        return process
    }

    private fun stopProcess(p: Process, role: String) {
        if (!p.isAlive) return
        p.destroy()
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            log.warn("[SeaweedFS] $role did not stop in time; forcing.")
            p.destroyForcibly()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun pidFile(role: String) = File(rootDir, "$role.pid")

    /** Ends a weed process a crashed or force-killed earlier run left behind, so its port is free. Only touches our own binary. */
    private fun killStale(role: String) {
        val file = pidFile(role)
        val pid = file.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: return
        file.delete()
        ProcessHandle.of(pid).ifPresent { handle ->
            val command = handle.info().command().orElse("")
            if (handle.isAlive && command.contains("weed", ignoreCase = true)) {
                log.info("[SeaweedFS] Stopping leftover $role process $pid")
                handle.destroy()
                runCatching { handle.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS) }
                if (handle.isAlive) handle.destroyForcibly()
            }
        }
    }

    fun isPortOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 1000); true }
    } catch (e: Exception) {
        false
    }

    private fun download(url: String, target: File) {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofFile(target.toPath()))
        if (resp.statusCode() != 200) {
            target.delete()
            throw IllegalStateException("Download of $url failed with HTTP ${resp.statusCode()}")
        }
    }

    private fun fetchText(url: String): String {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) throw IllegalStateException("HTTP ${resp.statusCode()}")
        return resp.body()
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractFromZip(archive: File, entryName: String, target: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == entryName) {
                    target.outputStream().use { zis.copyTo(it) }
                    return
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun extractFromTarGz(archive: File, entryName: String, target: File) {
        val tmp = File(rootDir, "extract-tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val p = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", tmp.absolutePath)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (p.waitFor() != 0) throw IllegalStateException("tar failed: $out")
            val found = tmp.walkTopDown().firstOrNull { it.isFile && it.name == entryName }
                ?: return
            Files.move(found.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
