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
        assertEquals(listOf("lite", "standard", "gemma2b", "advanced", "gemma9b"), status.tiers.map { it.id })
        assertTrue(status.tiers.all { it.state == "not_installed" })
    }
}
