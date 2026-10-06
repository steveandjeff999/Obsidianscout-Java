package com.obsidianscout.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val consoleLog = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.ai.LocalAiModelService")

/** WebLLM (WebGPU) build of a model. The model library must match the web-llm runtime version. */
data class WebLlmSource(
    val repo: String,
    val revision: String,
    val mlcModelId: String,
    val modelLibFile: String,
    val vramMB: Int
)

/** Transformers.js ONNX build used as a CPU (WASM) fallback when the device has no usable WebGPU. */
data class OnnxCpuSource(
    val repo: String,
    val revision: String,
    val dtype: String,
    val files: List<String>
)

/**
 * A model tier the browser can run. Weights are pinned to exact Hugging Face revisions; the served URLs embed
 * [version], so they are immutable and browsers can cache them forever.
 */
data class LocalAiTier(
    val id: String,
    val name: String,
    val model: String,
    val params: String,
    val license: String,
    val webllm: WebLlmSource,
    val cpu: OnnxCpuSource? = null,
    val contextTokens: Int = 4096
) {
    val version: String get() = webllm.revision.take(12) + (cpu?.let { "-" + it.revision.take(12) } ?: "")
}

data class RuntimeFile(val name: String, val url: String)

@Serializable
data class LocalAiFileInfo(val path: String, val bytes: Long)

@Serializable
data class LocalAiInstallMarker(
    val tier: String,
    val version: String,
    val runtimeVersion: String,
    val files: List<LocalAiFileInfo>,
    val totalBytes: Long,
    val installedAt: String
)

@Serializable
data class LocalAiTierStatus(
    val id: String,
    val name: String,
    val model: String,
    val params: String,
    val license: String,
    val state: String,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String? = null,
    val error: String? = null,
    val installedBytes: Long = 0,
    val installedAt: String? = null
)

@Serializable
data class LocalAiAdminStatus(
    val modelDir: String,
    val freeDiskBytes: Long,
    val runtimeInstalled: Boolean,
    val tiers: List<LocalAiTierStatus>
)

object LocalAiModelService {

    private const val TRANSFORMERS_VERSION = "4.3.0"
    private const val ORT_WEB_VERSION = "1.31.0-dev.20260914-8d85527a0"
    private const val WEBLLM_VERSION = "0.2.85"
    /** web-llm 0.2.85's prebuilt config points at this model-library build. */
    private const val WEBLLM_LIB_PREFIX = "https://raw.githubusercontent.com/mlc-ai/binary-mlc-llm-libs/main/web-llm-models/v0_2_84/base/"
    const val RUNTIME_VERSION = "tjs$TRANSFORMERS_VERSION-webllm$WEBLLM_VERSION"

    private val EXCLUDED_REPO_FILES = setOf(".gitattributes", "README.md")
    private const val MARKER_FILE = "installed.json"
    private const val MLC_DIR = "mlc"
    private const val CPU_DIR = "cpu"
    private const val MODEL_LIB_PATH = "$MLC_DIR/lib/model.wasm"

    val tiers: List<LocalAiTier> = listOf(
        LocalAiTier(
            id = "lite",
            name = "Lite",
            model = "Qwen2.5-0.5B-Instruct",
            params = "0.5B",
            license = "Apache-2.0",
            webllm = WebLlmSource(
                repo = "mlc-ai/Qwen2.5-0.5B-Instruct-q4f16_1-MLC",
                revision = "32ff081fe7e4dfe4ffb167b94c66fdf11e02b8ad",
                mlcModelId = "Qwen2.5-0.5B-Instruct-q4f16_1-MLC",
                modelLibFile = "Qwen2-0.5B-Instruct-q4f16_1_cs1k-webgpu.wasm",
                vramMB = 945
            ),
            cpu = OnnxCpuSource(
                repo = "onnx-community/Qwen2.5-0.5B-Instruct",
                revision = "cc5cc01a65cc3ff17bdb73a7de33d879f62599b0",
                dtype = "q8",
                files = listOf(
                    "config.json", "generation_config.json", "tokenizer.json", "tokenizer_config.json",
                    "special_tokens_map.json", "added_tokens.json", "onnx/model_quantized.onnx"
                )
            )
        ),
        LocalAiTier(
            id = "standard",
            name = "Standard",
            model = "Qwen2.5-1.5B-Instruct",
            params = "1.5B",
            license = "Apache-2.0",
            webllm = WebLlmSource(
                repo = "mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
                revision = "9bd564b064631febf14deadcac492efb761d60c3",
                mlcModelId = "Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
                modelLibFile = "Qwen2-1.5B-Instruct-q4f16_1_cs1k-webgpu.wasm",
                vramMB = 1630
            )
        ),
        LocalAiTier(
            id = "gemma2b",
            name = "Gemma 2B",
            model = "Gemma-2-2B-IT",
            params = "2B",
            license = "Gemma Terms of Use",
            webllm = WebLlmSource(
                repo = "mlc-ai/gemma-2-2b-it-q4f16_1-MLC",
                revision = "de9cc76f0d4b3a49a0f718df424944054bf1eec1",
                mlcModelId = "gemma-2-2b-it-q4f16_1-MLC",
                modelLibFile = "gemma-2-2b-it-q4f16_1_cs1k-webgpu.wasm",
                vramMB = 1895
            )
        ),
        LocalAiTier(
            id = "advanced",
            name = "Advanced",
            model = "Qwen2.5-3B-Instruct",
            params = "3B",
            license = "Qwen Research License (non-commercial use)",
            webllm = WebLlmSource(
                repo = "mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC",
                revision = "7690aaaa46df36b1be0fe93b9c9abac0497eff6c",
                mlcModelId = "Qwen2.5-3B-Instruct-q4f16_1-MLC",
                modelLibFile = "Qwen2.5-3B-Instruct-q4f16_1_cs1k-webgpu.wasm",
                vramMB = 2505
            )
        ),
        LocalAiTier(
            id = "gemma9b",
            name = "Gemma 9B",
            model = "Gemma-2-9B-IT",
            params = "9B",
            license = "Gemma Terms of Use",
            webllm = WebLlmSource(
                repo = "mlc-ai/gemma-2-9b-it-q4f16_1-MLC",
                revision = "e5cddd463237ecdd1249ef4d304d6c86a4701bd4",
                mlcModelId = "gemma-2-9b-it-q4f16_1-MLC",
                modelLibFile = "gemma-2-9b-it-q4f16_1_cs1k-webgpu.wasm",
                vramMB = 5750
            )
        )
    )

    private val runtimeFiles = listOf(
        RuntimeFile("transformers.min.js", "https://cdn.jsdelivr.net/npm/@huggingface/transformers@$TRANSFORMERS_VERSION/dist/transformers.min.js"),
        RuntimeFile("ort-wasm-simd-threaded.asyncify.mjs", "https://cdn.jsdelivr.net/npm/onnxruntime-web@$ORT_WEB_VERSION/dist/ort-wasm-simd-threaded.asyncify.mjs"),
        RuntimeFile("ort-wasm-simd-threaded.asyncify.wasm", "https://cdn.jsdelivr.net/npm/onnxruntime-web@$ORT_WEB_VERSION/dist/ort-wasm-simd-threaded.asyncify.wasm"),
        RuntimeFile("web-llm.js", "https://cdn.jsdelivr.net/npm/@mlc-ai/web-llm@$WEBLLM_VERSION/lib/index.js")
    )

    val modelRoot: File
        get() = File(System.getProperty("obsidianscout.modelsDir") ?: System.getenv("OBSIDIANSCOUT_MODELS_DIR") ?: "data/models")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, LocalAiTierStatus>()
    private val markerCache = ConcurrentHashMap<String, Pair<Long, LocalAiInstallMarker?>>()

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    }

    fun tier(id: String): LocalAiTier? = tiers.firstOrNull { it.id == id }

    private fun tierDir(tier: LocalAiTier) = File(modelRoot, "${tier.id}/${tier.version}")
    private fun runtimeDir() = File(modelRoot, "runtime/$RUNTIME_VERSION")

    fun isRuntimeInstalled(): Boolean = runtimeFiles.all { File(runtimeDir(), it.name).isFile }

    fun installedMarker(tier: LocalAiTier): LocalAiInstallMarker? {
        val file = File(tierDir(tier), MARKER_FILE)
        if (!file.isFile) return null
        val cached = markerCache[tier.id]
        if (cached != null && cached.first == file.lastModified()) return cached.second
        val marker = runCatching { json.decodeFromString(LocalAiInstallMarker.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.version == tier.version }
        markerCache[tier.id] = file.lastModified() to marker
        return marker
    }

    fun isInstalled(tier: LocalAiTier): Boolean = installedMarker(tier) != null && isRuntimeInstalled()

    // ---------------------------------------------------------------- client manifest

    /** What a browser needs to load the installed tiers. Only fully installed tiers are listed. */
    fun clientManifest(): JsonObject = buildJsonObject {
        val runtimeBase = "/models/runtime/$RUNTIME_VERSION/"
        val runtimeOk = isRuntimeInstalled()
        putJsonObject("runtime") {
            put("version", RUNTIME_VERSION)
            put("installed", runtimeOk)
            put("transformers", runtimeBase + "transformers.min.js")
            put("ortMjs", runtimeBase + "ort-wasm-simd-threaded.asyncify.mjs")
            put("ortWasm", runtimeBase + "ort-wasm-simd-threaded.asyncify.wasm")
            put("webllm", runtimeBase + "web-llm.js")
        }
        putJsonArray("tiers") {
            if (!runtimeOk) return@putJsonArray
            for (tier in tiers) {
                val marker = installedMarker(tier) ?: continue
                val base = "/models/${tier.id}/${tier.version}/"
                val mlcFiles = marker.files.filter { it.path.startsWith("$MLC_DIR/") }
                val cpuFiles = marker.files.filter { it.path.startsWith("$CPU_DIR/") }
                addJsonObject {
                    put("id", tier.id)
                    put("name", tier.name)
                    put("model", tier.model)
                    put("params", tier.params)
                    put("license", tier.license)
                    put("version", tier.version)
                    put("contextTokens", tier.contextTokens)
                    put("vramMB", tier.webllm.vramMB)
                    putJsonObject("webgpu") {
                        put("engine", "webllm")
                        put("mlcModelId", tier.webllm.mlcModelId)
                        put("modelUrl", base + "$MLC_DIR/resolve/main/")
                        put("modelLibUrl", base + MODEL_LIB_PATH)
                        put("downloadBytes", mlcFiles.sumOf { it.bytes })
                        putJsonArray("files") {
                            mlcFiles.forEach { f ->
                                val rel = f.path.removePrefix("$MLC_DIR/")
                                // Absolute URL path the engine fetches for this file.
                                add(if (f.path == MODEL_LIB_PATH) base + MODEL_LIB_PATH else base + "$MLC_DIR/resolve/main/" + rel)
                            }
                        }
                    }
                    if (tier.cpu != null && cpuFiles.isNotEmpty()) {
                        putJsonObject("cpu") {
                            put("engine", "transformers")
                            put("dtype", tier.cpu.dtype)
                            put("localModelPath", base)
                            put("modelId", CPU_DIR)
                            put("downloadBytes", cpuFiles.sumOf { it.bytes })
                            putJsonArray("files") {
                                cpuFiles.forEach { f ->
                                    addJsonObject { put("url", base + f.path); put("bytes", f.bytes) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- file serving

    /**
     * Maps a `/models/...` request to a file on disk, or null when it is not an installed, servable file.
     *  - `/models/runtime/<runtimeVersion>/<file>`
     *  - `/models/<tier>/<version>/mlc/[resolve/main/]<path>` (WebLLM appends `resolve/main/` to model URLs)
     *  - `/models/<tier>/<version>/cpu/<path>`
     */
    fun resolveServedFile(segments: List<String>): File? {
        if (segments.size < 3) return null
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') || it.contains(':') || it.contains('\u0000') }) return null

        val (baseDir, rest) = if (segments[0] == "runtime") {
            if (segments[1] != RUNTIME_VERSION || segments.size != 3) return null
            if (runtimeFiles.none { it.name == segments[2] }) return null
            runtimeDir() to segments.drop(2)
        } else {
            val tier = tier(segments[0]) ?: return null
            if (segments[1] != tier.version || installedMarker(tier) == null) return null
            var rest = segments.drop(2)
            if (rest.size > 3 && rest[0] == MLC_DIR && rest[1] == "resolve" && rest[2] == "main") {
                rest = listOf(MLC_DIR) + rest.drop(3)
            }
            if (rest.firstOrNull() != MLC_DIR && rest.firstOrNull() != CPU_DIR) return null
            tierDir(tier) to rest
        }

        val file = File(baseDir, rest.joinToString("/"))
        val base = baseDir.canonicalFile
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(base.path + File.separator)) return null
        if (!canonical.isFile || canonical.name.endsWith(".part") || canonical.name == MARKER_FILE) return null
        return canonical
    }

    fun contentTypeFor(file: File): String = when (file.extension.lowercase()) {
        "json" -> "application/json"
        "wasm" -> "application/wasm"
        "js", "mjs" -> "text/javascript"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    // ---------------------------------------------------------------- admin status / install / delete

    fun adminStatus(): LocalAiAdminStatus {
        val root = modelRoot.also { it.mkdirs() }
        return LocalAiAdminStatus(
            modelDir = root.absolutePath,
            freeDiskBytes = root.usableSpace,
            runtimeInstalled = isRuntimeInstalled(),
            tiers = tiers.map { tierStatus(it) }
        )
    }

    private fun baseStatus(tier: LocalAiTier, state: String) =
        LocalAiTierStatus(tier.id, tier.name, tier.model, tier.params, tier.license, state = state)

    private fun tierStatus(tier: LocalAiTier): LocalAiTierStatus {
        progress[tier.id]?.let { live ->
            if (live.state == "downloading" || live.state == "error" || live.state == "cancelled") return live
        }
        val marker = installedMarker(tier)
        return baseStatus(tier, if (marker != null) "installed" else "not_installed").copy(
            installedBytes = marker?.totalBytes ?: 0,
            totalBytes = marker?.totalBytes ?: 0,
            downloadedBytes = marker?.totalBytes ?: 0,
            installedAt = marker?.installedAt
        )
    }

    /** Starts (or resumes) an install in the background. Returns false if one is already running. */
    fun startInstall(tierId: String): Boolean {
        val tier = tier(tierId) ?: throw IllegalArgumentException("Unknown model tier: $tierId")
        val existing = jobs[tier.id]
        if (existing != null && existing.isActive) return false
        progress[tier.id] = baseStatus(tier, "downloading").copy(currentFile = "starting")
        val job = scope.launch {
            try {
                install(tier)
            } catch (e: CancellationException) {
                update(tier) { it.copy(state = "cancelled", currentFile = null) }
                consoleLog.info("[LocalAI] Install of ${tier.id} cancelled")
            } catch (e: Throwable) {
                consoleLog.error("[LocalAI] Install of ${tier.id} failed: ${e.message}", e)
                update(tier) { it.copy(state = "error", error = e.message ?: e.javaClass.simpleName, currentFile = null) }
            }
        }
        jobs[tier.id] = job
        return true
    }

    /** Blocking install for CLI usage. */
    fun installBlocking(tierId: String) {
        val tier = tier(tierId) ?: throw IllegalArgumentException("Unknown model tier: $tierId (expected one of ${tiers.joinToString { it.id }})")
        runBlocking(Dispatchers.IO) { install(tier) }
    }

    fun cancelInstall(tierId: String): Boolean {
        val job = jobs[tierId] ?: return false
        job.cancel()
        return true
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
    }

    /**
     * Checks all model tiers on startup and automatically downloads any missing model tiers in the background.
     */
    fun autoInstallMissingModelsOnStartup(): Job = scope.launch {
        try {
            consoleLog.info("[LocalAI] Checking for missing model tiers on startup...")
            if (!isRuntimeInstalled()) {
                consoleLog.info("[LocalAI] Web runtime is not installed. Downloading runtime...")
                ensureRuntime()
                consoleLog.info("[LocalAI] Web runtime download completed.")
            }
            for (tier in tiers) {
                if (installedMarker(tier) == null) {
                    consoleLog.info("[LocalAI] Auto-downloading missing tier '${tier.id}' (${tier.name} - ${tier.model})...")
                    startInstall(tier.id)
                    jobs[tier.id]?.join()
                }
            }
            consoleLog.info("[LocalAI] Startup model check completed.")
        } catch (e: CancellationException) {
            consoleLog.info("[LocalAI] Auto-install on startup cancelled.")
        } catch (e: Throwable) {
            consoleLog.warn("[LocalAI] Auto-install on startup encountered error: ${e.message}")
        }
    }

    fun deleteTier(tierId: String) {
        val tier = tier(tierId) ?: throw IllegalArgumentException("Unknown model tier: $tierId")
        jobs[tier.id]?.cancel()
        File(modelRoot, tier.id).deleteRecursively()
        markerCache.remove(tier.id)
        progress.remove(tier.id)
    }

    private fun update(tier: LocalAiTier, block: (LocalAiTierStatus) -> LocalAiTierStatus) {
        progress.compute(tier.id) { _, old -> block(old ?: baseStatus(tier, "downloading")) }
    }

    private data class PlannedFile(val path: String, val url: String, val size: Long?, val sha256: String?)

    private suspend fun install(tier: LocalAiTier) {
        update(tier) { baseStatus(tier, "downloading").copy(currentFile = "runtime") }
        ensureRuntime()

        val planned = planFiles(tier)
        val total = planned.sumOf { it.size ?: 0L }
        val dir = tierDir(tier).also { it.mkdirs() }
        // Remove older versions of this tier.
        File(modelRoot, tier.id).listFiles()?.filter { it.isDirectory && it.name != tier.version }?.forEach { it.deleteRecursively() }

        var completedBytes = 0L
        update(tier) { it.copy(totalBytes = total, downloadedBytes = 0) }
        val infos = mutableListOf<LocalAiFileInfo>()
        for ((index, file) in planned.withIndex()) {
            currentCoroutineContext().ensureActive()
            val target = File(dir, file.path)
            val base = completedBytes
            update(tier) { it.copy(currentFile = file.path) }
            val percent = if (total > 0) (completedBytes * 100 / total) else 0
            consoleLog.info("[LocalAI] [${tier.id}] Downloading file ${index + 1}/${planned.size} ($percent%): ${file.path}")
            downloadFile(file.url, target, file.size, file.sha256) { written ->
                update(tier) { it.copy(downloadedBytes = base + written) }
            }
            completedBytes += target.length()
            infos += LocalAiFileInfo(file.path, target.length())
        }

        val marker = LocalAiInstallMarker(
            tier = tier.id,
            version = tier.version,
            runtimeVersion = RUNTIME_VERSION,
            files = infos,
            totalBytes = infos.sumOf { it.bytes },
            installedAt = Instant.now().toString()
        )
        File(dir, MARKER_FILE).writeText(json.encodeToString(LocalAiInstallMarker.serializer(), marker))
        markerCache.remove(tier.id)
        progress.remove(tier.id)
        consoleLog.info("[LocalAI] Installed ${tier.id} (${tier.model}) — ${marker.totalBytes / (1024 * 1024)} MB")
    }

    private suspend fun ensureRuntime() = runtimeMutex.withLock {
        val dir = runtimeDir().also { it.mkdirs() }
        for (rf in runtimeFiles) {
            val target = File(dir, rf.name)
            if (target.isFile && target.length() > 0) continue
            downloadFile(rf.url, target, null, null) {}
        }
        // Drop runtimes from previous releases.
        File(modelRoot, "runtime").listFiles()?.filter { it.isDirectory && it.name != RUNTIME_VERSION }?.forEach { it.deleteRecursively() }
    }

    private fun planFiles(tier: LocalAiTier): List<PlannedFile> {
        val planned = mutableListOf<PlannedFile>()
        val mlc = repoTree(tier.webllm.repo, tier.webllm.revision)
        mlc.keys.filter { it !in EXCLUDED_REPO_FILES }.sorted().forEach { path ->
            planned += plannedFrom(tier.webllm.repo, tier.webllm.revision, path, mlc.getValue(path), "$MLC_DIR/")
        }
        planned += PlannedFile(MODEL_LIB_PATH, WEBLLM_LIB_PREFIX + tier.webllm.modelLibFile, null, null)

        tier.cpu?.let { cpu ->
            val onnx = repoTree(cpu.repo, cpu.revision)
            cpu.files.forEach { path ->
                val entry = onnx[path] ?: throw IllegalStateException("File $path not found in ${cpu.repo}@${cpu.revision}")
                planned += plannedFrom(cpu.repo, cpu.revision, path, entry, "$CPU_DIR/")
            }
        }
        return planned
    }

    private fun repoTree(repo: String, revision: String): Map<String, JsonObject> {
        val body = httpGetString("https://huggingface.co/api/models/$repo/tree/$revision?recursive=1")
        return Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "file" }
            .associateBy { it["path"]!!.jsonPrimitive.content }
    }

    private fun plannedFrom(repo: String, revision: String, path: String, entry: JsonObject, prefix: String): PlannedFile {
        val lfs = entry["lfs"]?.jsonObject
        return PlannedFile(
            path = prefix + path,
            url = "https://huggingface.co/$repo/resolve/$revision/$path",
            size = (lfs?.get("size") ?: entry["size"])?.jsonPrimitive?.longOrNull,
            sha256 = lfs?.get("oid")?.jsonPrimitive?.content
        )
    }

    private fun httpGetString(url: String): String {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "ObsidianScout-LocalAI").GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) throw IllegalStateException("HTTP ${res.statusCode()} fetching $url")
        return res.body()
    }

    /**
     * Downloads [url] to [target] via a `.part` file. Resumes a partial download with a Range request,
     * verifies size and (for LFS files) SHA-256, then renames into place.
     * Skips the download when [target] already exists with the expected size.
     */
    private suspend fun downloadFile(url: String, target: File, expectedSize: Long?, sha256: String?, onProgress: (Long) -> Unit) {
        if (target.isFile && (expectedSize == null || target.length() == expectedSize) && target.length() > 0) {
            onProgress(target.length())
            return
        }
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        if (expectedSize != null && part.length() > expectedSize) part.delete()

        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                fetchInto(url, part, onProgress)
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= 5) throw IllegalStateException("Download failed for ${target.name}: ${e.message}", e)
                consoleLog.warn("[LocalAI] Retrying ${target.name} after error: ${e.message}")
                delay(2000L * attempt)
            }
        }

        if (expectedSize != null && part.length() != expectedSize) {
            part.delete()
            throw IllegalStateException("Size mismatch for ${target.name}: expected $expectedSize bytes")
        }
        if (sha256 != null) {
            val actual = sha256Of(part)
            if (!actual.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw IllegalStateException("Checksum mismatch for ${target.name}")
            }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IllegalStateException("Could not move ${part.name} into place")
    }

    private suspend fun fetchInto(url: String, part: File, onProgress: (Long) -> Unit) {
        val existing = if (part.isFile) part.length() else 0L
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(30))
            .header("User-Agent", "ObsidianScout-LocalAI")
            .GET()
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val res = withContext(Dispatchers.IO) { http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream()) }
        val code = res.statusCode()
        if (code == 416) { res.body().close(); return } // already complete
        if (code !in 200..299) {
            res.body().close()
            throw IllegalStateException("HTTP $code")
        }
        val append = code == 206 && existing > 0
        RandomAccessFile(part, "rw").use { raf ->
            if (append) raf.seek(existing) else raf.setLength(0)
            var written = if (append) existing else 0L
            var lastReport = 0L
            res.body().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    raf.write(buffer, 0, n)
                    written += n
                    if (written - lastReport > (4 shl 20)) {
                        onProgress(written)
                        lastReport = written
                    }
                }
            }
            onProgress(written)
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
