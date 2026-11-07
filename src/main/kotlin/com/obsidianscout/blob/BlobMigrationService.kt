package com.obsidianscout.blob

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.BlobObjects
import com.obsidianscout.db.EventInfoFiles
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.db.Users
import com.obsidianscout.db.readTransaction
import com.obsidianscout.db.writeTransactionWithRetry
import com.obsidianscout.scouting.ImageMetadataStripper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.Base64
import java.util.UUID

private val log = org.slf4j.LoggerFactory.getLogger("com.obsidianscout.blob.BlobMigrationService")

/**
 * Moves base64 images and files out of database rows into the blob store, a batch at a time.
 *
 * It runs at startup and then every few minutes, so it also catches base64 that arrives later through
 * paths that still write it (old app versions, backup and snapshot restores, alliance imports).
 * Each row is swapped with a compare-and-set update: if the row changed meanwhile, the new blob is
 * deleted and the row is tried again on the next pass.
 */
object BlobMigrationService {

    /** Largest single image accepted from an old row; anything bigger is left in place and logged. */
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    /** Rows that can't be moved (bad base64, oversized), so they don't hold up the rest of the queue. Cleared on restart. */
    private val skipped: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun fetchLimit(batch: Int) = batch + skipped.size.coerceAtMost(5000)

    /** What the running pass is working on, for the progress page. Null when idle. */
    @Volatile var currentStep: String? = null
        private set

    /** Number of rows skipped because they can't be moved (bad base64, oversized). */
    val skippedCount: Int get() = skipped.size

    /** Runs one sweep and returns the number of rows moved. */
    fun runOnce(batchSize: Int, includeAllEntryTables: Boolean): Int {
        val batch = batchSize.coerceIn(1, 500)
        var total = 0
        total += drain("profile pictures") { migrateAvatars(batch) }
        total += drain("Event Info files") { migrateEventFiles(batch) }
        total += drain("pit scouting photos") {
            migrateEntryTable(PitScoutingEntries, PitScoutingEntries.ownerTeamNumber, PitScoutingEntries.program, PitScoutingEntries.dataJson, PitScoutingEntries.updatedAt, BlobService.KIND_PIT_PHOTO, batch)
        }
        if (includeAllEntryTables) {
            total += drain("match scouting photos") {
                migrateEntryTable(ScoutingEntries, ScoutingEntries.ownerTeamNumber, ScoutingEntries.program, ScoutingEntries.dataJson, ScoutingEntries.updatedAt, BlobService.KIND_SCOUTING_PHOTO, batch)
            }
            total += drain("qualitative scouting photos") {
                migrateEntryTable(QualitativeScoutingEntries, QualitativeScoutingEntries.ownerTeamNumber, QualitativeScoutingEntries.program, QualitativeScoutingEntries.dataJson, QualitativeScoutingEntries.updatedAt, BlobService.KIND_SCOUTING_PHOTO, batch)
            }
        }
        currentStep = null
        runCatching { backfillAvatarTeams() }.onFailure { log.debug("[Blobs] Avatar team backfill skipped: ${it.message}") }
        if (total > 0) log.info("[Blobs] Moved $total row(s) of images/files out of the database into the blob store.")
        return total
    }

    /**
     * Profile pictures uploaded before uploads recorded the team were stored without one; copy it from the user so
     * the Storage Manager can count them per team.
     */
    private fun backfillAvatarTeams() {
        val missing = readTransaction {
            BlobObjects.select(BlobObjects.id).where { (BlobObjects.kind eq BlobService.KIND_AVATAR) and BlobObjects.teamNumber.isNull() }
                .limit(1000).map { it[BlobObjects.id].value }.toSet()
        }
        if (missing.isEmpty()) return
        val owners = readTransaction {
            Users.select(Users.teamNumber, Users.program, Users.profilePicture).where { Users.profilePicture like "${BlobService.REF_PREFIX}%" }
                .mapNotNull { row -> BlobService.idFromRef(row[Users.profilePicture])?.takeIf { it in missing }?.let { it to (row[Users.teamNumber] to row[Users.program]) } }
        }
        if (owners.isEmpty()) return
        writeTransactionWithRetry {
            for ((id, owner) in owners) {
                BlobObjects.update({ (BlobObjects.id eq id) and BlobObjects.teamNumber.isNull() }) {
                    it[teamNumber] = owner.first
                    it[program] = owner.second
                }
            }
        }
    }

    /** Blob ids in `/api/blobs/<id>` URLs inside [text] (scouting entry JSON). */
    fun blobIdsIn(text: String?): Set<UUID> {
        if (text.isNullOrEmpty() || !text.contains(BlobService.URL_PREFIX)) return emptySet()
        return BlobService.URL_IN_TEXT_REGEX.findAll(text).mapNotNull { runCatching { UUID.fromString(it.groupValues[1]) }.getOrNull() }.toSet()
    }

    /** Runs [step] until it reports nothing left (or no progress), stopping early if the store goes away. */
    private fun drain(label: String, step: () -> Pair<Int, Int>): Int {
        var moved = 0
        currentStep = "Moving $label"
        while (BlobService.canMigrate()) {
            val (seen, done) = try {
                step()
            } catch (e: Exception) {
                log.warn("[Blobs] Moving $label paused: ${e.message}")
                break
            }
            moved += done
            if (seen == 0 || done == 0) break
        }
        return moved
    }

    // ---------------------------------------------------------------- avatars

    private fun migrateAvatars(batch: Int): Pair<Int, Int> {
        val rows = readTransaction {
            Users.select(Users.id, Users.teamNumber, Users.program, Users.profilePicture)
                .where { Users.profilePicture like "data:%" }
                .limit(fetchLimit(batch))
                .map { Row(it[Users.id].value, it[Users.teamNumber], it[Users.program], it[Users.profilePicture] ?: "") }
        }.filterNot { it.id in skipped }.take(batch)
        var done = 0
        for (row in rows) {
            val decoded = com.obsidianscout.auth.AuthService.decodeProfilePicture(row.value) ?: run { skipped += row.id; null } ?: continue
            val id = BlobService.put(decoded.second, decoded.first, BlobService.KIND_AVATAR, row.team, row.program)
            val updated = writeTransactionWithRetry {
                Users.update({ (Users.id eq row.id) and (Users.profilePicture eq row.value) }) {
                    it[profilePicture] = BlobService.ref(id)
                }
            }
            if (updated == 1) {
                com.obsidianscout.db.ChatService.forgetAvatar(row.id)
                done++
            } else {
                BlobService.delete(id)
            }
        }
        return rows.size to done
    }

    // ---------------------------------------------------------------- Event Info files

    private fun migrateEventFiles(batch: Int): Pair<Int, Int> {
        val rows = readTransaction {
            EventInfoFiles.select(EventInfoFiles.id, EventInfoFiles.teamNumber, EventInfoFiles.program, EventInfoFiles.contentType, EventInfoFiles.data)
                .where { (EventInfoFiles.data notLike "${BlobService.REF_PREFIX}%") and (EventInfoFiles.data neq "") }
                .limit(fetchLimit(batch))
                .map { Row(it[EventInfoFiles.id].value, it[EventInfoFiles.teamNumber], it[EventInfoFiles.program], it[EventInfoFiles.data], it[EventInfoFiles.contentType]) }
        }.filterNot { it.id in skipped }.take(batch)
        var done = 0
        for (row in rows) {
            // Kept byte-for-byte (including gzip at rest); EventInfoService.getFile unwraps it the same way as before.
            val bytes = runCatching { Base64.getDecoder().decode(row.value) }.getOrNull() ?: run { skipped += row.id; null } ?: continue
            val id = BlobService.put(bytes, row.contentType ?: "application/octet-stream", BlobService.KIND_EVENT_FILE, row.team, row.program)
            val updated = writeTransactionWithRetry {
                EventInfoFiles.update({ (EventInfoFiles.id eq row.id) and (EventInfoFiles.data notLike "${BlobService.REF_PREFIX}%") }) {
                    it[data] = BlobService.ref(id)
                }
            }
            if (updated == 1) done++ else BlobService.delete(id)
        }
        return rows.size to done
    }

    // ---------------------------------------------------------------- scouting entries

    private fun migrateEntryTable(
        table: UUIDTable,
        ownerCol: Column<Int>,
        programCol: Column<String>,
        dataCol: Column<String>,
        updatedCol: Column<Instant?>,
        kind: String,
        batch: Int
    ): Pair<Int, Int> {
        val rows = readTransaction {
            table.select(table.id, ownerCol, programCol, dataCol)
                .where { dataCol like "%\"data:image/%" }
                .limit(fetchLimit(batch))
                .map { Row(it[table.id].value, it[ownerCol], it[programCol], it[dataCol]) }
        }.filterNot { it.id in skipped }.take(batch)
        var done = 0
        for (row in rows) {
            val parsed = runCatching { JsonSupport.json.parseToJsonElement(row.value).jsonObject }.getOrNull() ?: run { skipped += row.id; null } ?: continue
            val created = mutableListOf<UUID>()
            val rewritten = try {
                offloadImages(parsed, kind, row.team, row.program, created)
            } catch (e: Exception) {
                created.forEach { BlobService.delete(it) }
                log.warn("[Blobs] Skipping images in ${table.tableName} ${row.id}: ${e.message}")
                continue
            } ?: run { skipped += row.id; null } ?: continue
            val newJson = JsonSupport.json.encodeToString(JsonElement.serializer(), rewritten)
            val updated = writeTransactionWithRetry {
                table.update({ (table.id eq row.id) and (dataCol eq row.value) }) {
                    it[dataCol] = newJson
                    // Delta-syncing clients then fetch the much smaller row and drop their base64 copy.
                    it[updatedCol] = Instant.now()
                }
            }
            if (updated == 1) done++ else created.forEach { BlobService.delete(it) }
        }
        return rows.size to done
    }

    /**
     * Replaces every top-level base64 image in [data] with a `/api/blobs/<id>` URL, adding each new blob id to
     * [created]. Returns null when there was nothing to replace. Must not be called inside a transaction.
     */
    fun offloadImages(data: JsonObject, kind: String, teamNumber: Int?, program: String?, created: MutableList<UUID>): JsonObject? {
        var changed = false
        val out = LinkedHashMap<String, JsonElement>(data.size)
        fun offload(value: JsonElement): JsonElement {
            val image = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { decodeImage(it) } ?: return value
            val id = BlobService.put(image.second, image.first, kind, teamNumber, program)
            created += id
            changed = true
            return JsonPrimitive(BlobService.url(id))
        }
        for ((key, value) in data) {
            // Top-level values, plus arrays of them (the legacy mobile pit API sends an "images" list).
            out[key] = if (value is JsonArray) JsonArray(value.map { offload(it) }) else offload(value)
        }
        return if (changed) JsonObject(out) else null
    }

    /** Content type and bytes of a base64 image value (data URL, or a bare JPEG base64 string), or null if it isn't one. */
    fun decodeImage(value: String): Pair<String, ByteArray>? {
        val isDataUrl = value.startsWith("data:image/")
        val isBareJpeg = !isDataUrl && value.length > 5000 && value.startsWith("/9j/")
        if (!isDataUrl && !isBareJpeg) return null
        val payload = if (isDataUrl) value.substringAfter(",", "") else value
        if (payload.isEmpty()) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(payload.trim()) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val declared = if (isDataUrl) value.substringAfter("data:").substringBefore(";").substringBefore(",") else ""
        val contentType = ImageMetadataStripper.detect(bytes)?.mime
            ?: declared.takeIf { it.matches(Regex("^image/[a-z0-9.+-]+$")) && it != "image/svg+xml" }
            ?: return null
        return contentType to bytes
    }

    // ---------------------------------------------------------------- references (for garbage collection)

    /** Every blob id some row still points at. Reads from [db] directly so a partial fallback snapshot is never used. */
    fun collectReferencedIds(db: Database): Set<UUID> {
        val ids = HashSet<UUID>()
        transaction(db = db) {
            Users.select(Users.profilePicture).where { Users.profilePicture like "${BlobService.REF_PREFIX}%" }
                .forEach { row -> BlobService.idFromRef(row[Users.profilePicture])?.let { ids += it } }
            EventInfoFiles.select(EventInfoFiles.data).where { EventInfoFiles.data like "${BlobService.REF_PREFIX}%" }
                .forEach { row -> BlobService.idFromRef(row[EventInfoFiles.data])?.let { ids += it } }
            for ((table, col) in listOf(
                PitScoutingEntries to PitScoutingEntries.dataJson,
                ScoutingEntries to ScoutingEntries.dataJson,
                QualitativeScoutingEntries to QualitativeScoutingEntries.dataJson
            )) {
                table.select(col).where { col like "%${BlobService.URL_PREFIX}%" }.forEach { row ->
                    BlobService.URL_IN_TEXT_REGEX.findAll(row[col]).forEach { m -> runCatching { UUID.fromString(m.groupValues[1]) }.getOrNull()?.let { ids += it } }
                }
            }
        }
        return ids
    }

    private data class Row(val id: UUID, val team: Int, val program: String, val value: String, val contentType: String? = null)
}
