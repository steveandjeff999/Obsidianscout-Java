package com.obsidianscout.collab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ConfigSyncEngineTest {
    private val vectors: JsonObject by lazy {
        val text = javaClass.getResource("/config-collab-vectors.json")?.readText() ?: fail("vectors missing")
        Json.parseToJsonElement(text).jsonObject
    }

    private fun keyedOf(e: JsonElement): KeyedDoc {
        val o = e.jsonObject
        return KeyedDoc(o.getValue("doc").jsonObject, o.getValue("keys").jsonArray.map { it.jsonPrimitive.content })
    }

    private fun counterKeys(): () -> String {
        var n = 0
        return { "n${n++}" }
    }

    private fun assertJson(expected: JsonElement?, actual: JsonElement?, message: String) {
        assertTrue(ConfigSyncEngine.deepEqual(expected, actual), "$message\nexpected: $expected\nactual:   $actual")
    }

    @Test
    fun applyVectorsMatchTheBrowserEngine() {
        for (case in vectors.getValue("apply").jsonArray) {
            val c = case.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val result = ConfigSyncEngine.applyOps(keyedOf(c.getValue("state")), c.getValue("ops").jsonArray)
            val expect = keyedOf(c.getValue("expect"))
            assertJson(expect.doc, result.doc, "apply '$name' doc")
            assertEquals(expect.keys, result.keys, "apply '$name' keys")
        }
    }

    @Test
    fun diffVectorsMatchTheBrowserEngine() {
        for (case in vectors.getValue("diff").jsonArray) {
            val c = case.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val result = ConfigSyncEngine.diff(keyedOf(c.getValue("base")), c.getValue("target").jsonObject, counterKeys())
            assertJson(c.getValue("ops"), JsonArray(result.ops), "diff '$name' ops")
            assertEquals(c.getValue("keys").jsonArray.map { it.jsonPrimitive.content }, result.keys, "diff '$name' keys")
        }
    }

    @Test
    fun diffAlwaysRoundTrips() {
        val random = Random(42)
        repeat(500) { iteration ->
            val base = ConfigSyncEngine.keyed(randomConfig(random), counterKeys())
            val target = mutate(base.doc, random)
            val result = ConfigSyncEngine.diff(base, target) { "x${random.nextInt(1_000_000)}-$iteration" }
            val applied = ConfigSyncEngine.applyOps(base, result.ops)
            assertJson(ConfigSyncEngine.ensureFields(target), applied.doc, "round trip #$iteration")
            assertEquals(result.keys, applied.keys, "keys #$iteration")
        }
    }

    @Test
    fun concurrentEditsToDifferentFieldsBothSurvive() {
        val keys = counterKeys()
        val server = ConfigSyncEngine.keyed(config(field("a"), field("b"), field("c")), keys)

        // Two editors start from the same state and change different things at the same time.
        val aliceOps = ConfigSyncEngine.diff(server, config(field("a", "Alpha"), field("b"), field("c")), keys).ops
        val bobOps = ConfigSyncEngine.diff(server, config(field("b", "Bravo"), field("c"), field("a"), field("d")), keys).ops

        // The server applies them in arrival order; every editor replays the same order.
        val merged = ConfigSyncEngine.applyOps(ConfigSyncEngine.applyOps(server, aliceOps), bobOps)
        val labels = merged.fields.map { (it as JsonObject)["label"]!!.jsonPrimitive.content }
        assertEquals(listOf("Bravo", "C", "Alpha", "D"), labels)
    }

    @Test
    fun rebaseKeepsBothSidesOfAnOfflineEdit() {
        val keys = counterKeys()
        val base = ConfigSyncEngine.keyed(config(field("a"), field("b")), keys)
        // Offline: we renamed b and added c. Meanwhile someone else deleted a and added z (saved over REST).
        val mine = ConfigSyncEngine.diff(base, config(field("a"), field("b", "Bee"), field("c")), keys).ops
        val theirs = config(field("b"), field("z"))
        val merged = ConfigSyncEngine.rebase(base, theirs, mine, keys)
        val labels = merged.fields.map { (it as JsonObject)["label"]!!.jsonPrimitive.content }
        assertEquals(listOf("Bee", "C", "Z"), labels)
    }

    @Test
    fun rejectsMalformedOps() {
        val bad = listOf(
            """{"op":"set","path":[]}""",
            """{"op":"set","path":["title"]}""",
            """{"op":"set","path":[1],"value":2}""",
            """{"op":"insert","key":"","value":{}}""",
            """{"op":"move","key":"k","after":5}""",
            """{"op":"nuke"}""",
            """[1,2]"""
        )
        for (text in bad) {
            val failed = runCatching { ConfigSyncEngine.validate(Json.parseToJsonElement(text)) }.isFailure
            assertTrue(failed, "should reject $text")
        }
        ConfigSyncEngine.validate(Json.parseToJsonElement("""{"op":"move","key":"k","after":null}"""))
        ConfigSyncEngine.validate(Json.parseToJsonElement("""{"op":"set","path":["fields","k","label"],"value":null}"""))
    }

    // ── helpers ──

    private fun field(id: String, label: String = id.uppercase()) =
        JsonObject(mapOf("id" to JsonPrimitive(id), "label" to JsonPrimitive(label), "type" to JsonPrimitive("counter")))

    private fun config(vararg fields: JsonObject) =
        JsonObject(mapOf("title" to JsonPrimitive("T"), "fields" to JsonArray(fields.toList())))

    private fun randomConfig(random: Random): JsonObject {
        val n = random.nextInt(0, 8)
        val fields = (0 until n).map { randomField(random, "f$it") }
        return JsonObject(mapOf("title" to JsonPrimitive("T${random.nextInt(3)}"), "version" to JsonPrimitive(1), "fields" to JsonArray(fields)))
    }

    private fun randomField(random: Random, id: String): JsonObject {
        val m = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id), "label" to JsonPrimitive("L${random.nextInt(4)}"))
        m["type"] = JsonPrimitive(listOf("counter", "select", "text").random(random))
        if (random.nextBoolean()) m["min"] = JsonPrimitive(random.nextInt(3))
        if (random.nextBoolean()) m["meta"] = JsonObject(mapOf("x" to JsonPrimitive(random.nextInt(3))))
        return JsonObject(m)
    }

    private fun mutate(doc: JsonObject, random: Random): JsonObject {
        val fields = (doc["fields"] as JsonArray).toMutableList()
        repeat(random.nextInt(0, 5)) {
            when (random.nextInt(5)) {
                0 -> fields.add(random.nextInt(fields.size + 1), randomField(random, "n${random.nextInt(100)}"))
                1 -> if (fields.isNotEmpty()) fields.removeAt(random.nextInt(fields.size))
                2 -> if (fields.size > 1) { val i = random.nextInt(fields.size); val v = fields.removeAt(i); fields.add(random.nextInt(fields.size + 1), v) }
                3 -> if (fields.isNotEmpty()) {
                    val i = random.nextInt(fields.size)
                    fields[i] = JsonObject((fields[i] as JsonObject) + ("label" to JsonPrimitive("E${random.nextInt(9)}")))
                }
                else -> if (fields.isNotEmpty()) {
                    val i = random.nextInt(fields.size)
                    fields[i] = JsonObject((fields[i] as JsonObject) - "min" + ("id" to JsonPrimitive("r${random.nextInt(9)}")))
                }
            }
        }
        val top = doc.toMutableMap()
        top["fields"] = JsonArray(fields)
        if (random.nextInt(4) == 0) top["title"] = JsonPrimitive("New")
        if (random.nextInt(6) == 0) top.remove("version")
        return JsonObject(top)
    }
}
