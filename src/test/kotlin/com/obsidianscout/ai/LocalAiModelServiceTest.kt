package com.obsidianscout.ai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.*

class LocalAiModelServiceTest {

    private val root = File("build/test_models_${System.currentTimeMillis()}")
    private val lite = LocalAiModelService.tier("lite")!!
    private val standard = LocalAiModelService.tier("standard")!!

    @BeforeTest
    fun setUp() {
        root.mkdirs()
        System.setProperty("obsidianscout.modelsDir", root.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("obsidianscout.modelsDir")
        root.deleteRecursively()
    }

    private fun installFake(tier: LocalAiTier, files: Map<String, String>) {
        val dir = File(root, "${tier.id}/${tier.version}")
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs(); writeText(content) } }
        val list = files.entries.joinToString(",") { """{"path":"${it.key}","bytes":${it.value.length}}""" }
        File(dir, "installed.json").writeText(
            """{"tier":"${tier.id}","version":"${tier.version}","runtimeVersion":"${LocalAiModelService.RUNTIME_VERSION}","files":[$list],"totalBytes":1,"installedAt":"now"}"""
        )
        val runtime = File(root, "runtime/${LocalAiModelService.RUNTIME_VERSION}").apply { mkdirs() }
        listOf("transformers.min.js", "ort-wasm-simd-threaded.asyncify.mjs", "ort-wasm-simd-threaded.asyncify.wasm", "web-llm.js")
            .forEach { File(runtime, it).writeText("x") }
    }

    @Test
    fun servesInstalledFilesIncludingWebLlmResolvePaths() {
        installFake(standard, mapOf("mlc/params_shard_0.bin" to "abc", "mlc/lib/model.wasm" to "w", "mlc/mlc-chat-config.json" to "{}"))
        val v = standard.version

        val shard = LocalAiModelService.resolveServedFile(listOf("standard", v, "mlc", "resolve", "main", "params_shard_0.bin"))
        assertNotNull(shard)
        assertEquals("abc", shard.readText())
        assertNotNull(LocalAiModelService.resolveServedFile(listOf("standard", v, "mlc", "lib", "model.wasm")))
        assertEquals("application/wasm", LocalAiModelService.contentTypeFor(File("model.wasm")))
        assertNotNull(LocalAiModelService.resolveServedFile(listOf("runtime", LocalAiModelService.RUNTIME_VERSION, "web-llm.js")))
    }

    @Test
    fun rejectsTraversalWrongVersionsMarkerAndUninstalledTiers() {
        installFake(standard, mapOf("mlc/params_shard_0.bin" to "abc"))
        val v = standard.version
        assertNull(LocalAiModelService.resolveServedFile(listOf("standard", v, "mlc", "..", "..", "installed.json")))
        assertNull(LocalAiModelService.resolveServedFile(listOf("standard", v, "installed.json")))
        assertNull(LocalAiModelService.resolveServedFile(listOf("standard", "deadbeef", "mlc", "params_shard_0.bin")))
        assertNull(LocalAiModelService.resolveServedFile(listOf("advanced", LocalAiModelService.tier("advanced")!!.version, "mlc", "x")))
        assertNull(LocalAiModelService.resolveServedFile(listOf("runtime", LocalAiModelService.RUNTIME_VERSION, "secrets.json")))
        assertNull(LocalAiModelService.resolveServedFile(listOf("nope", "1", "mlc", "x")))
    }

    @Test
    fun manifestListsOnlyInstalledTiersWithBothBackendsForLite() {
        installFake(lite, mapOf("mlc/params_shard_0.bin" to "abcd", "mlc/lib/model.wasm" to "w", "cpu/config.json" to "{}", "cpu/onnx/model_quantized.onnx" to "12345"))
        val manifest = LocalAiModelService.clientManifest()
        val tiers = manifest["tiers"]!!.jsonArray
        assertEquals(listOf("lite"), tiers.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val liteJson = tiers[0].jsonObject
        assertEquals("webllm", liteJson["webgpu"]!!.jsonObject["engine"]!!.jsonPrimitive.content)
        val cpu = liteJson["cpu"]!!.jsonObject
        assertEquals("transformers", cpu["engine"]!!.jsonPrimitive.content)
        assertEquals("/models/lite/${lite.version}/", cpu["localModelPath"]!!.jsonPrimitive.content)
        assertEquals(2, cpu["files"]!!.jsonArray.size)
    }

    @Test
    fun adminStatusReportsNotInstalledByDefault() {
        val status = LocalAiModelService.adminStatus()
        assertEquals(listOf("lite", "standard", "gemma4e2b", "advanced", "gemma4e4b"), status.tiers.map { it.id })
        assertTrue(status.tiers.all { it.state == "not_installed" })
    }

    @Test
    fun gemma4TiersUseTransformersOnWebGpu() {
        val e2b = LocalAiModelService.tier("gemma4e2b")!!
        installFake(e2b, mapOf("gpu/config.json" to "{}", "gpu/onnx/decoder_model_merged_q2f16.onnx_data" to "123456"))
        val json = LocalAiModelService.clientManifest()["tiers"]!!.jsonArray.single().jsonObject
        val gpu = json["webgpu"]!!.jsonObject
        assertEquals("transformers", gpu["engine"]!!.jsonPrimitive.content)
        assertEquals("gpu", gpu["modelId"]!!.jsonPrimitive.content)
        assertEquals("Gemma4ForConditionalGeneration", gpu["modelClass"]!!.jsonPrimitive.content)
        assertEquals("q2f16", gpu["dtype"]!!.jsonObject["decoder_model_merged"]!!.jsonPrimitive.content)
        assertNull(json["cpu"])
        assertNotNull(LocalAiModelService.resolveServedFile(listOf("gemma4e2b", e2b.version, "gpu", "config.json")))
    }

    @Test
    fun pruneRemovesRetiredTiersAndOldVersionsButKeepsCurrentOnes() {
        installFake(standard, mapOf("mlc/params_shard_0.bin" to "abc"))
        File(root, "gemma2b/de9cc76f0d4b/mlc").mkdirs()             // retired Gemma 2 tier
        File(root, "gemma9b/e5cddd463237/mlc").mkdirs()
        File(root, "standard/0000oldversion/mlc").mkdirs()          // superseded version
        File(root, "runtime/tjs1.0.0-webllm0.1.0").mkdirs()         // old runtime

        val removed = LocalAiModelService.pruneObsoleteModels()

        assertTrue("gemma2b" in removed && "gemma9b" in removed, "retired tiers removed: $removed")
        assertTrue("standard/0000oldversion" in removed)
        assertTrue("runtime/tjs1.0.0-webllm0.1.0" in removed)
        assertFalse(File(root, "gemma2b").exists())
        assertTrue(File(root, "standard/${standard.version}/installed.json").isFile, "current install kept")
        assertTrue(File(root, "runtime/${LocalAiModelService.RUNTIME_VERSION}").isDirectory, "current runtime kept")
    }

    @Test
    fun diskSpaceGuardKeepsTheConfiguredReserve() {
        val previous = LocalAiModelService.minFreeDiskMb
        try {
            LocalAiModelService.minFreeDiskMb = 1024
            val mb = 1024L * 1024
            LocalAiModelService.checkDiskSpace(neededBytes = 500 * mb, freeBytes = 2000 * mb)
            assertFailsWith<IllegalStateException> {
                LocalAiModelService.checkDiskSpace(neededBytes = 1500 * mb, freeBytes = 2000 * mb)
            }
        } finally {
            LocalAiModelService.minFreeDiskMb = previous
        }
    }

    @Test
    fun externalDataPartsAreAddedAutomatically() {
        val repo = setOf(
            "onnx/decoder_model_merged_q2f16.onnx", "onnx/decoder_model_merged_q2f16.onnx_data",
            "onnx/decoder_model_merged_q2f16.onnx_data_1", "onnx/decoder_model_merged_q2f16.onnx_data_2",
            "onnx/embed_tokens_q2f16.onnx_data", "onnx/embed_tokens_q4.onnx_data_1"
        )
        val planned = LocalAiModelService.expandExternalData(
            listOf("config.json", "onnx/decoder_model_merged_q2f16.onnx_data", "onnx/embed_tokens_q2f16.onnx_data"), repo
        )
        assertEquals(
            listOf(
                "config.json", "onnx/decoder_model_merged_q2f16.onnx_data", "onnx/decoder_model_merged_q2f16.onnx_data_1",
                "onnx/decoder_model_merged_q2f16.onnx_data_2", "onnx/embed_tokens_q2f16.onnx_data"
            ),
            planned
        )
    }
}
