package com.obsidianscout.blob

import com.obsidianscout.config.JsonSupport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Talks to SeaweedFS's own HTTP API (no S3 layer, no filer): the master hands out a file id and the volume
 * server that should hold it, and the bytes go straight to that volume server. The database row is the index,
 * so nothing else has to keep metadata in sync between nodes.
 */
class SeaweedBlobStore(@Volatile var masterAddress: String) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** volumeId -> (volume server urls, expiry). Volumes move rarely; a failed read clears the entry. */
    private val locationCache = ConcurrentHashMap<String, Pair<List<String>, Long>>()

    @Volatile private var statusCache: Pair<Int, Long>? = null

    /** Number of volume servers the master can see, cached for 30 seconds. Returns 0 when the master is unreachable. */
    fun dataNodeCount(): Int {
        statusCache?.let { (n, until) -> if (System.currentTimeMillis() < until) return n }
        val n = try {
            val body = getJson("http://$masterAddress/dir/status")
            body["Topology"]?.jsonObject?.get("DataCenters")?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray
                ?.sumOf { dc ->
                    dc.jsonObject["Racks"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray?.sumOf { rack ->
                        rack.jsonObject["DataNodes"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray?.size ?: 0
                    } ?: 0
                } ?: 0
        } catch (e: Exception) {
            0
        }
        // Only a positive answer is cached, so a volume server that just registered is noticed on the next check.
        statusCache = if (n > 0) n to (System.currentTimeMillis() + 30_000) else null
        return n
    }

    data class DataNode(val url: String, val volumes: Int, val maxVolumes: Int)

    /** Every volume server registered with the master. Throws when the master can't be reached. */
    fun topology(): List<DataNode> {
        val body = getJson("http://$masterAddress/dir/status")
        val out = mutableListOf<DataNode>()
        fun arr(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonArray).orEmpty()
        for (dc in arr(body["Topology"]?.jsonObject?.get("DataCenters"))) {
            for (rack in arr(dc.jsonObject["Racks"])) {
                for (node in arr(rack.jsonObject["DataNodes"])) {
                    val o = node.jsonObject
                    val url = o["Url"]?.jsonPrimitive?.contentOrNull ?: continue
                    out += DataNode(
                        url = url,
                        volumes = o["Volumes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        maxVolumes = o["Max"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    )
                }
            }
        }
        return out
    }

    fun forgetStatus() {
        statusCache = null
    }

    /** One extra copy on another server once there are two or more; a single server can only keep one. */
    fun replicationFor(nodeCount: Int): String = if (nodeCount >= 2) "001" else "000"

    /** Stores [bytes] and returns the SeaweedFS file id. */
    fun put(bytes: ByteArray, contentType: String, replication: String): String {
        val assign = getJson("http://$masterAddress/dir/assign?replication=$replication")
        assign["error"]?.jsonPrimitive?.contentOrNull?.let { throw IllegalStateException("SeaweedFS assign failed: $it") }
        val fid = assign["fid"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("SeaweedFS assign returned no fid")
        val url = assign["url"]?.jsonPrimitive?.contentOrNull ?: throw IllegalStateException("SeaweedFS assign returned no url")
        val req = HttpRequest.newBuilder(URI.create("http://$url/$fid"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", contentType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) throw IllegalStateException("SeaweedFS upload failed: HTTP ${resp.statusCode()} ${resp.body().take(200)}")
        val written = runCatching { JsonSupport.json.parseToJsonElement(resp.body()).jsonObject["size"]?.jsonPrimitive?.int }.getOrNull()
        if (written != null && written != bytes.size) throw IllegalStateException("SeaweedFS stored $written of ${bytes.size} bytes")
        return fid
    }

    /** Returns the bytes for [fid], or null when no volume server has it. Throws when SeaweedFS can't be reached. */
    fun get(fid: String): ByteArray? {
        val volumeId = fid.substringBefore(',')
        var lastError: Exception? = null
        for (attempt in 0..1) {
            val locations = lookup(volumeId, refresh = attempt > 0)
            if (locations.isEmpty()) return null
            for (location in locations) {
                try {
                    val req = HttpRequest.newBuilder(URI.create("http://$location/$fid")).timeout(Duration.ofSeconds(30)).GET().build()
                    val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                    when (resp.statusCode()) {
                        200 -> return resp.body()
                        404 -> continue
                        else -> lastError = IllegalStateException("HTTP ${resp.statusCode()} from $location")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            locationCache.remove(volumeId)
        }
        if (lastError != null) throw lastError
        return null
    }

    fun delete(fid: String) {
        val volumeId = fid.substringBefore(',')
        for (location in lookup(volumeId, refresh = false)) {
            val req = HttpRequest.newBuilder(URI.create("http://$location/$fid")).timeout(Duration.ofSeconds(30)).DELETE().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
            // A replicated volume forwards the delete to its replicas, so one success is enough.
            if (resp.statusCode() in 200..299 || resp.statusCode() == 404) return
        }
    }

    private fun lookup(volumeId: String, refresh: Boolean): List<String> {
        if (!refresh) {
            locationCache[volumeId]?.let { (urls, until) -> if (System.currentTimeMillis() < until) return urls }
        }
        val body = getJson("http://$masterAddress/dir/lookup?volumeId=$volumeId")
        val urls = body["locations"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray
            ?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
        if (urls.isNotEmpty()) locationCache[volumeId] = urls to (System.currentTimeMillis() + 120_000)
        return urls
    }

    private fun getJson(url: String): JsonObject {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        return JsonSupport.json.parseToJsonElement(resp.body()).jsonObject
    }
}

/** Blobs as files under one directory, for servers that can't run SeaweedFS and for writes made while it is down. */
class LocalBlobStore(private val root: File) {

    private fun fileFor(key: String): File {
        require(key.matches(Regex("^[0-9a-f]{2}/[0-9a-f-]{36}$"))) { "Bad local blob key" }
        return File(root, key)
    }

    fun keyFor(id: java.util.UUID): String = "${id.toString().take(2)}/$id"

    fun put(key: String, bytes: ByteArray) {
        val target = fileFor(key)
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun get(key: String): ByteArray? = fileFor(key).takeIf { it.isFile }?.readBytes()

    fun delete(key: String) {
        fileFor(key).delete()
    }
}
