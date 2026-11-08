package com.obsidianscout.blob

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ObsidianDbBackup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import java.util.UUID

private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.blob.BackupImages")

/**
 * Makes backups carry their images.
 *
 * Export: every `/api/blobs/<id>` photo URL and `blob:<id>` profile picture is replaced with the image itself as a
 * base64 data URL, so the file restores on any server, even one that never had those blobs.
 *
 * Import: every base64 image in the file is put in the blob store straight away and replaced by its reference,
 * instead of sitting in the database until the background sweep moves it.
 *
 * Both must run outside a database transaction (blob reads and writes open their own).
 */
object BackupImages {

    /** Content type and bytes of a stored blob, or null if it can't be read. */
    fun interface Fetcher { fun fetch(id: UUID): Pair<String, ByteArray>? }

    /** Stores an image and returns its new blob id. */
    fun interface Storer { fun store(bytes: ByteArray, contentType: String, kind: String, teamNumber: Int?, program: String?): UUID }

    private val defaultFetcher = Fetcher { id -> BlobService.get(id)?.let { it.meta.contentType to it.bytes } }
    private val defaultStorer = Storer { bytes, ct, kind, team, program -> BlobService.put(bytes, ct, kind, team, program) }

    /** A whole quoted JSON string value that is exactly a blob photo URL. */
    private val QUOTED_BLOB_URL = Regex("\"" + Regex.escape(BlobService.URL_PREFIX) + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\"")

    // ---------------------------------------------------------------- export

    /**
     * [backup] with every blob reference replaced by the image as a data URL. References that can't be read are kept.
     * Once [maxImageBytes] of image data (before base64) has been inlined, the remaining references are kept as links,
     * so a backup of a large photo library can't exhaust the heap or outgrow what an import accepts.
     */
    fun inline(backup: ObsidianDbBackup, fetcher: Fetcher = defaultFetcher, maxImageBytes: Long = Long.MAX_VALUE): ObsidianDbBackup {
        val cache = HashMap<UUID, String?>()
        var missing = 0
        var overBudget = 0
        var inlinedBytes = 0L
        fun dataUrl(id: UUID): String? = cache.getOrPut(id) {
            val blob = runCatching { fetcher.fetch(id) }.getOrNull()
            // Only images become data URLs; the import side would never move anything else back into the blob store.
            if (blob == null || !blob.first.startsWith("image/")) { missing++; null }
            else if (inlinedBytes + blob.second.size > maxImageBytes) { overBudget++; null }
            else {
                inlinedBytes += blob.second.size
                "data:${blob.first};base64,${Base64.getEncoder().encodeToString(blob.second)}"
            }
        }
        fun inlineJson(json: String): String {
            if (!json.contains(BlobService.URL_PREFIX)) return json
            return QUOTED_BLOB_URL.replace(json) { m ->
                val id = runCatching { UUID.fromString(m.groupValues[1]) }.getOrNull()
                id?.let { dataUrl(it) }?.let { "\"$it\"" } ?: m.value
            }
        }
        val out = backup.copy(
            users = backup.users.map { u ->
                val id = BlobService.idFromRef(u.profilePicture) ?: return@map u
                dataUrl(id)?.let { u.copy(profilePicture = it) } ?: u
            },
            scoutingEntries = backup.scoutingEntries.map { it.copy(dataJson = inlineJson(it.dataJson)) },
            pitScoutingEntries = backup.pitScoutingEntries.map { it.copy(dataJson = inlineJson(it.dataJson)) },
            qualitativeScoutingEntries = backup.qualitativeScoutingEntries.map { it.copy(dataJson = inlineJson(it.dataJson)) }
        )
        if (missing > 0) log.warn("[Blobs] Backup export: $missing image(s) could not be read from the blob store and were left as links.")
        if (overBudget > 0) log.warn("[Blobs] Backup export: $overBudget image(s) left as links because the backup reached its ${maxImageBytes / (1024 * 1024)} MB image limit; they restore only on a server that has those blobs.")
        return out
    }

    // ---------------------------------------------------------------- import

    class Offloaded(val backup: ObsidianDbBackup, val created: List<UUID>)

    /**
     * [backup] with every base64 image moved into the blob store. [teamFor] / [programFor] give the team and program
     * the row will actually be written under. An entry whose images can't be stored keeps its base64 (the background
     * sweep retries it later). The ids in [Offloaded.created] that end up unused (skipped duplicates, failed import)
     * should be passed to [BlobService.freeIfUnreferenced] once the import has finished.
     */
    fun offload(
        backup: ObsidianDbBackup,
        teamFor: (Int) -> Int,
        programFor: (String) -> String,
        storer: Storer = defaultStorer
    ): Offloaded {
        val created = mutableListOf<UUID>()

        fun offloadJson(json: String, kind: String, team: Int, program: String): String {
            if (!json.contains("data:image/") && !json.contains("\"/9j/")) return json
            val parsed = runCatching { JsonSupport.json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return json
            val mine = mutableListOf<UUID>()
            var changed = false
            fun walk(e: JsonElement): JsonElement = when (e) {
                is JsonObject -> JsonObject(e.mapValues { walk(it.value) })
                is JsonArray -> JsonArray(e.map { walk(it) })
                is JsonPrimitive -> {
                    val image = if (e.isString) BlobMigrationService.decodeImage(e.content) else null
                    if (image == null) e else {
                        val id = storer.store(image.second, image.first, kind, team, program)
                        mine += id
                        changed = true
                        JsonPrimitive(BlobService.url(id))
                    }
                }
                else -> e
            }
            return try {
                val rewritten = walk(parsed)
                created += mine
                if (changed) JsonSupport.json.encodeToString(JsonElement.serializer(), rewritten) else json
            } catch (e: Exception) {
                log.warn("[Blobs] Backup import: keeping base64 images for one entry, the blob store refused them: ${e.message}")
                created += mine // freed with the rest once the import ends
                json
            }
        }

        val out = backup.copy(
            users = backup.users.map { u ->
                val pic = u.profilePicture ?: return@map u
                if (!pic.startsWith("data:")) return@map u
                val decoded = com.obsidianscout.auth.AuthService.decodeProfilePicture(pic) ?: return@map u
                try {
                    val id = storer.store(decoded.second, decoded.first, BlobService.KIND_AVATAR, teamFor(u.teamNumber), programFor(u.program))
                    created += id
                    u.copy(profilePicture = BlobService.ref(id))
                } catch (e: Exception) {
                    log.warn("[Blobs] Backup import: keeping a base64 profile picture, the blob store refused it: ${e.message}")
                    u
                }
            },
            scoutingEntries = backup.scoutingEntries.map {
                it.copy(dataJson = offloadJson(it.dataJson, BlobService.KIND_SCOUTING_PHOTO, teamFor(it.ownerTeamNumber), programFor(it.program)))
            },
            pitScoutingEntries = backup.pitScoutingEntries.map {
                it.copy(dataJson = offloadJson(it.dataJson, BlobService.KIND_PIT_PHOTO, teamFor(it.ownerTeamNumber), programFor(it.program)))
            },
            qualitativeScoutingEntries = backup.qualitativeScoutingEntries.map {
                it.copy(dataJson = offloadJson(it.dataJson, BlobService.KIND_SCOUTING_PHOTO, teamFor(it.ownerTeamNumber), programFor(it.program)))
            }
        )
        if (created.isNotEmpty()) log.info("[Blobs] Backup import: moved ${created.size} image(s) into the blob store.")
        return Offloaded(out, created)
    }
}
