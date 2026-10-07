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
    val modelLibFile: String
)

/**
 * Transformers.js ONNX build. Used for WebGPU when no WebLLM build exists (Gemma 4) and as Lite's CPU fallback.
 * [dtype] is either one dtype for every session or a per-session map (e.g. decoder_model_merged -> q2f16).
 */
data class OnnxSource(
    val repo: String,
    val revision: String,
    val files: List<String>,
    val dtype: Map<String, String>,
    /** Transformers.js model class to load, e.g. AutoModelForCausalLM or Gemma4ForConditionalGeneration. */
    val modelClass: String = "AutoModelForCausalLM"
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
    /** GPU build via WebLLM, or [gpuOnnx] via Transformers.js (exactly one of the two). */
    val webllm: WebLlmSource? = null,
    val gpuOnnx: OnnxSource? = null,
    /** Optional CPU (WASM) fallback. */
    val cpu: OnnxSource? = null,
    /** Approximate GPU memory needed while running. */
    val vramMB: Int,
    /** Devices reporting less memory than this (navigator.deviceMemory) are told the tier is unsupported. */
    val minDeviceMemoryGB: Int = 0,
    /** "ok", "slow" (warn) or "no" (unsupported) on phones/tablets. */
    val mobile: String = "ok",
    val contextTokens: Int = 4096
) {
    init {
        require((webllm == null) != (gpuOnnx == null)) { "Tier $id needs exactly one GPU source" }
    }

    val version: String
        get() = listOfNotNull(webllm?.revision, gpuOnnx?.revision, cpu?.revision).joinToString("-") { it.take(12) }
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
    val autoInstallTiers: List<String> = emptyList(),
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
    private const val GPU_DIR = "gpu"
    private const val CPU_DIR = "cpu"
    private const val MODEL_LIB_PATH = "$MLC_DIR/lib/model.wasm"
    private val SOURCE_DIRS = setOf(MLC_DIR, GPU_DIR, CPU_DIR)

    /** Gemma 4 QAT "mobile" ONNX builds: per-session dtypes from the repos' transformers.js_config. */
    private fun gemma4Qat(repo: String, revision: String) = OnnxSource(
        repo = repo,
        revision = revision,
        files = listOf(
            "config.json", "generation_config.json", "tokenizer.json", "tokenizer_config.json", "chat_template.jinja",
            "preprocessor_config.json", "processor_config.json",
            "onnx/embed_tokens_q2f16.onnx", "onnx/embed_tokens_q2f16.onnx_data",
            "onnx/decoder_model_merged_q2f16.onnx", "onnx/decoder_model_merged_q2f16.onnx_data",
            "onnx/audio_encoder_q2f16.onnx", "onnx/audio_encoder_q2f16.onnx_data",
            "onnx/vision_encoder_fp16.onnx", "onnx/vision_encoder_fp16.onnx_data"
        ),
        dtype = mapOf(
            "decoder_model_merged" to "q2f16", "embed_tokens" to "q2f16",
            "audio_encoder" to "q2f16", "vision_encoder" to "fp16"
        ),
        modelClass = "Gemma4ForConditionalGeneration"
    )

    val tiers: List<LocalAiTier> = listOf(
        LocalAiTier(
            id = "lite",
            name = "Lite (Llama 3.2 1B)",
            model = "Llama-3.2-1B-Instruct",
            params = "1B",
            license = "Llama 3.2 Community License",
            webllm = WebLlmSource(
                repo = "mlc-ai/Llama-3.2-1B-Instruct-q4f16_1-MLC",
                revision = "2a37b0a5ecb622d51ddc2fac74de0b95872affd7",
                mlcModelId = "Llama-3.2-1B-Instruct-q4f16_1-MLC",
                modelLibFile = "Llama-3.2-1B-Instruct-q4f16_1_cs1k-webgpu.wasm"
            ),
            contextTokens = 2048,
            // CPU (WASM) fallback for devices without usable WebGPU.
            cpu = OnnxSource(
                repo = "onnx-community/Llama-3.2-1B-Instruct-ONNX",
                revision = "14007543b6dc92de88daf96a9aa85d2f95ace6ef",
                files = listOf(
                    "config.json", "generation_config.json", "tokenizer.json", "tokenizer_config.json",
                    "special_tokens_map.json", "chat_template.jinja", "onnx/model_q4.onnx", "onnx/model_q4.onnx_data"
                ),
                dtype = mapOf("model" to "q4")
            ),
            vramMB = 780
        ),
        LocalAiTier(
            id = "gemma4e2b",
            name = "Standard (Gemma 4 E2B)",
            model = "gemma-4-E2B-it (QAT mobile)",
            params = "E2B",
            license = "Apache-2.0",
            gpuOnnx = gemma4Qat("onnx-community/gemma-4-E2B-it-qat-mobile-ONNX", "5cd5514efd375abf2801c856a3936b259cc00133"),
            vramMB = 2600,
            minDeviceMemoryGB = 4,
            mobile = "slow"
        ),
        LocalAiTier(
            id = "gemma4e4b",
            name = "Advanced (Gemma 4 E4B)",
            model = "gemma-4-E4B-it (QAT mobile)",
            params = "E4B",
            license = "Apache-2.0",
            gpuOnnx = gemma4Qat("onnx-community/gemma-4-E4B-it-qat-mobile-ONNX", "4d18aa8b54e354bec4705e4a4894f5bbf8956c3d"),
            vramMB = 3800,
            minDeviceMemoryGB = 8,
            mobile = "no"
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

    /** Overridable for tests; normally read from app-config.json (local_ai). */
    @Volatile var minFreeDiskMb: Long = 2048
    @Volatile var autoInstallTiers: List<String> = emptyList()

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

    private fun JsonObjectBuilder.putOnnx(source: OnnxSource, base: String, dir: String, files: List<LocalAiFileInfo>) {
        put("engine", "transformers")
        // Transformers.js resolves <localModelPath><modelId>/<file>; both must stay relative paths.
        put("localModelPath", base)
        put("modelId", dir)
        put("modelClass", source.modelClass)
        putJsonObject("dtype") { source.dtype.forEach { (k, v) -> put(k, v) } }
        put("downloadBytes", files.sumOf { it.bytes })
        putJsonArray("files") {
            files.forEach { f -> addJsonObject { put("url", base + f.path); put("bytes", f.bytes) } }
        }
    }

    /**
     * What a browser needs to load the installed tiers. Only fully installed tiers are listed in `tiers`;
     * `knownTiers` lists every current tier id and version so browsers can delete cached files of retired models.
     */
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
        putJsonObject("knownTiers") { tiers.forEach { put(it.id, it.version) } }
        putJsonArray("tiers") {
            for (tier in tiers) {
                val marker = installedMarker(tier) ?: continue
                val base = "/models/${tier.id}/${tier.version}/"
                val byDir = { dir: String -> marker.files.filter { it.path.startsWith("$dir/") } }
                addJsonObject {
                    put("id", tier.id)
                    put("name", tier.name)
                    put("model", tier.model)
                    put("params", tier.params)
                    put("license", tier.license)
                    put("version", tier.version)
                    put("contextTokens", tier.contextTokens)
                    put("vramMB", tier.vramMB)
                    put("minDeviceMemoryGB", tier.minDeviceMemoryGB)
                    put("mobile", tier.mobile)
                    putJsonObject("webgpu") {
                        if (tier.webllm != null) {
                            val mlcFiles = byDir(MLC_DIR)
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
                        } else if (tier.gpuOnnx != null) {
                            putOnnx(tier.gpuOnnx, base, GPU_DIR, byDir(GPU_DIR))
                        }
                    }
                    val cpuFiles = byDir(CPU_DIR)
                    if (tier.cpu != null && cpuFiles.isNotEmpty()) {
                        putJsonObject("cpu") { putOnnx(tier.cpu, base, CPU_DIR, cpuFiles) }
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
     *  - `/models/<tier>/<version>/{gpu,cpu}/<path>` (Transformers.js)
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
            if (rest.firstOrNull() !in SOURCE_DIRS) return null
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
        "txt", "jinja" -> "text/plain"
        else -> "application/octet-stream"
    }

    // ---------------------------------------------------------------- admin status / install / delete

    fun adminStatus(): LocalAiAdminStatus {
        val root = modelRoot.also { it.mkdirs() }
        return LocalAiAdminStatus(
            modelDir = root.absolutePath,
            freeDiskBytes = root.usableSpace,
            runtimeInstalled = isRuntimeInstalled(),
            autoInstallTiers = autoInstallTiers,
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
     * Deletes model files that the current code no longer uses: tiers that were removed or renamed (e.g. the old
     * Gemma 2 tiers), superseded versions of current tiers, and runtimes from previous releases.
     * Returns the deleted paths (relative to the model folder).
     */
    fun pruneObsoleteModels(): List<String> {
        val root = modelRoot
        if (!root.isDirectory) return emptyList()
        val removed = mutableListOf<String>()
        fun remove(dir: File) {
            if (dir.deleteRecursively()) removed += dir.relativeTo(root).invariantSeparatorsPath
            else consoleLog.warn("[LocalAI] Could not fully delete obsolete model folder ${dir.absolutePath}")
        }
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            when {
                dir.name == "runtime" ->
                    dir.listFiles()?.filter { it.isDirectory && it.name != RUNTIME_VERSION }?.forEach(::remove)
                tier(dir.name) == null -> remove(dir)
                else -> {
                    val tier = tier(dir.name)!!
                    if (jobs[tier.id]?.isActive == true) return@forEach
                    dir.listFiles()?.filter { it.isDirectory && it.name != tier.version }?.forEach(::remove)
                }
            }
        }
        if (removed.isNotEmpty()) {
            markerCache.clear()
            consoleLog.info("[LocalAI] Removed obsolete model files: ${removed.joinToString()}")
        }
        return removed
    }

    /**
     * Startup housekeeping: remove obsolete models, then install the tiers listed in app-config `local_ai.auto_install_tiers`
     * (nothing by default). Uninstalling a tier from Storage Manager is therefore permanent unless it is listed there.
     */
    fun startupMaintenance(): Job = scope.launch {
        try {
            pruneObsoleteModels()
            ensureRuntime()
            val wanted = autoInstallTiers.mapNotNull { id ->
                tier(id) ?: run { consoleLog.warn("[LocalAI] Unknown tier '$id' in local_ai.auto_install_tiers"); null }
            }
            for (tier in wanted) {
                if (installedMarker(tier) != null) continue
                consoleLog.info("[LocalAI] Auto-installing '${tier.id}' (${tier.model}) from local_ai.auto_install_tiers...")
                if (startInstall(tier.id)) jobs[tier.id]?.join()
            }
        } catch (e: CancellationException) {
            consoleLog.info("[LocalAI] Startup model maintenance cancelled.")
        } catch (e: Throwable) {
            consoleLog.warn("[LocalAI] Startup model maintenance failed: ${e.message}")
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

    /** Throws when downloading [neededBytes] more would leave less than [minFreeDiskMb] free. */
    internal fun checkDiskSpace(neededBytes: Long, freeBytes: Long = modelRoot.also { it.mkdirs() }.usableSpace) {
        val reserve = minFreeDiskMb * 1024 * 1024
        if (freeBytes - neededBytes < reserve) {
            val mb = { b: Long -> b / (1024 * 1024) }
            throw IllegalStateException(
                "Not enough disk space: needs ${mb(neededBytes)} MB plus ${mb(reserve)} MB reserve, but only ${mb(freeBytes)} MB is free"
            )
        }
    }

    private suspend fun install(tier: LocalAiTier) {
        update(tier) { baseStatus(tier, "downloading").copy(currentFile = "runtime") }
        ensureRuntime()

        val planned = planFiles(tier)
        val total = planned.sumOf { it.size ?: 0L }
        val dir = tierDir(tier).also { it.mkdirs() }
        // Remove older versions of this tier first so their space counts as free.
        File(modelRoot, tier.id).listFiles()?.filter { it.isDirectory && it.name != tier.version }?.forEach { it.deleteRecursively() }
        val alreadyHave = planned.sumOf { p -> File(dir, p.path).takeIf { it.isFile }?.length() ?: 0L }
        checkDiskSpace((total - alreadyHave).coerceAtLeast(0))

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

    private fun planOnnx(source: OnnxSource, dir: String): List<PlannedFile> {
        val tree = repoTree(source.repo, source.revision)
        return expandExternalData(source.files, tree.keys).map { path ->
            val entry = tree[path] ?: throw IllegalStateException("File $path not found in ${source.repo}@${source.revision}")
            plannedFrom(source.repo, source.revision, path, entry, "$dir/")
        }
    }

    /**
     * Large ONNX weights are split into `x.onnx_data`, `x.onnx_data_1`, ... (e.g. Gemma 4 E4B's decoder has two parts).
     * Listing `x.onnx_data` is enough: every numbered part present in the repo is added after it.
     */
    internal fun expandExternalData(files: List<String>, repoFiles: Set<String>): List<String> = files.flatMap { path ->
        if (!path.endsWith(".onnx_data")) return@flatMap listOf(path)
        val parts = repoFiles.mapNotNull { f ->
            f.removePrefix("${path}_").takeIf { f.startsWith("${path}_") }?.toIntOrNull()?.let { it to f }
        }.sortedBy { it.first }.map { it.second }
        listOf(path) + parts
    }

    private fun planFiles(tier: LocalAiTier): List<PlannedFile> {
        val planned = mutableListOf<PlannedFile>()
        tier.webllm?.let { w ->
            val mlc = repoTree(w.repo, w.revision)
            mlc.keys.filter { it !in EXCLUDED_REPO_FILES }.sorted().forEach { path ->
                planned += plannedFrom(w.repo, w.revision, path, mlc.getValue(path), "$MLC_DIR/")
            }
            planned += PlannedFile(MODEL_LIB_PATH, WEBLLM_LIB_PREFIX + w.modelLibFile, null, null)
        }
        tier.gpuOnnx?.let { planned += planOnnx(it, GPU_DIR) }
        tier.cpu?.let { planned += planOnnx(it, CPU_DIR) }
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
