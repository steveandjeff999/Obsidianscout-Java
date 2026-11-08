package com.obsidianscout.blob

import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.ObsidianDbBackup
import com.obsidianscout.db.PitScoutingEntryBackupDto
import com.obsidianscout.db.ScoutingEntryBackupDto
import com.obsidianscout.db.UserBackupDto
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupImagesTest {

    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
    )
    private val pngDataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)

    private fun pit(json: String, team: Int = 100) = PitScoutingEntryBackupDto(team, 254, "2026test", json, "alex", 1L, false, false, "")
    private fun match(json: String, team: Int = 100) = ScoutingEntryBackupDto(team, 254, "2026test", "qm1", 1, json, "alex", 1L, false, false, "")
    private fun user(pic: String?, team: Int = 100) = UserBackupDto("alex", team, "h", "SCOUT", 1L, profilePicture = pic)

    /** In-memory blob store standing in for BlobService. */
    private class FakeStore {
        val blobs = HashMap<UUID, Triple<String, ByteArray, Pair<Int?, String?>>>()
        val fetcher = BackupImages.Fetcher { id -> blobs[id]?.let { it.first to it.second } }
        val storer = BackupImages.Storer { bytes, ct, _, team, program ->
            UUID.randomUUID().also { blobs[it] = Triple(ct, bytes, team to program) }
        }
    }

    @Test
    fun `export replaces blob links with the image, import puts it back in the blob store`() {
        val src = FakeStore()
        val photo = src.storer.store(png, "image/png", BlobService.KIND_PIT_PHOTO, 100, "FRC")
        val avatar = src.storer.store(png, "image/png", BlobService.KIND_AVATAR, 100, "FRC")
        val backup = ObsidianDbBackup(
            teamNumber = 100, type = "entire",
            users = listOf(user(BlobService.ref(avatar))),
            pitScoutingEntries = listOf(pit("""{"robot":"${BlobService.url(photo)}","images":["${BlobService.url(photo)}"],"notes":"see /api/blobs/$photo"}"""))
        )

        val exported = BackupImages.inline(backup, src.fetcher)
        val exportedPit = JsonSupport.json.parseToJsonElement(exported.pitScoutingEntries[0].dataJson).jsonObject
        assertEquals(pngDataUrl, exportedPit["robot"]!!.jsonPrimitive.content)
        assertEquals(pngDataUrl, exportedPit["images"]!!.jsonArray[0].jsonPrimitive.content)
        // Free text that merely mentions a blob path is not a photo and stays as written.
        assertEquals("see /api/blobs/$photo", exportedPit["notes"]!!.jsonPrimitive.content)
        assertEquals(pngDataUrl, exported.users[0].profilePicture)

        // A different server: none of the source blob ids exist there.
        val dst = FakeStore()
        val result = BackupImages.offload(exported, teamFor = { 900 }, programFor = { "FTC" }, storer = dst.storer)
        assertEquals(3, result.created.size)
        val importedPit = JsonSupport.json.parseToJsonElement(result.backup.pitScoutingEntries[0].dataJson).jsonObject
        val newPhoto = BlobService.idFromUrl(importedPit["robot"]!!.jsonPrimitive.content)!!
        assertTrue(png.contentEquals(dst.blobs[newPhoto]!!.second))
        assertEquals(900 to "FTC", dst.blobs[newPhoto]!!.third)
        val newAvatar = BlobService.idFromRef(result.backup.users[0].profilePicture)!!
        assertTrue(png.contentEquals(dst.blobs[newAvatar]!!.second))
    }

    @Test
    fun `export keeps a link it cannot read, import leaves rows without images untouched`() {
        val missing = UUID.randomUUID()
        val json = """{"photo":"${BlobService.url(missing)}","score":3}"""
        val backup = ObsidianDbBackup(teamNumber = 100, type = "scouting", scoutingEntries = listOf(match(json)))
        val store = FakeStore()
        assertEquals(json, BackupImages.inline(backup, store.fetcher).scoutingEntries[0].dataJson)

        val result = BackupImages.offload(backup, { it }, { it }, store.storer)
        assertEquals(json, result.backup.scoutingEntries[0].dataJson)
        assertTrue(result.created.isEmpty())
    }

    @Test
    fun `export stops embedding images once the size limit is reached and keeps the rest as links`() {
        val src = FakeStore()
        val first = src.storer.store(png, "image/png", BlobService.KIND_PIT_PHOTO, 100, "FRC")
        val second = src.storer.store(png, "image/png", BlobService.KIND_PIT_PHOTO, 100, "FRC")
        val backup = ObsidianDbBackup(
            teamNumber = 100, type = "scouting",
            pitScoutingEntries = listOf(
                pit("""{"robot":"${BlobService.url(first)}","again":"${BlobService.url(first)}"}"""),
                pit("""{"robot":"${BlobService.url(second)}"}""")
            )
        )
        // Room for exactly one image: the first is embedded (both uses of it), the second stays a link.
        val exported = BackupImages.inline(backup, src.fetcher, maxImageBytes = png.size.toLong())
        val a = JsonSupport.json.parseToJsonElement(exported.pitScoutingEntries[0].dataJson).jsonObject
        assertEquals(pngDataUrl, a["robot"]!!.jsonPrimitive.content)
        assertEquals(pngDataUrl, a["again"]!!.jsonPrimitive.content)
        val b = JsonSupport.json.parseToJsonElement(exported.pitScoutingEntries[1].dataJson).jsonObject
        assertEquals(BlobService.url(second), b["robot"]!!.jsonPrimitive.content)
    }

    @Test
    fun `import keeps base64 when the blob store fails and reports what it already stored`() {
        var calls = 0
        val flaky = BackupImages.Storer { _, _, _, _, _ -> if (++calls == 1) UUID.randomUUID() else error("store down") }
        val json = """{"a":"$pngDataUrl","b":"$pngDataUrl"}"""
        val backup = ObsidianDbBackup(teamNumber = 100, type = "scouting", pitScoutingEntries = listOf(pit(json)))
        val result = BackupImages.offload(backup, { it }, { it }, flaky)
        assertEquals(json, result.backup.pitScoutingEntries[0].dataJson)
        assertEquals(1, result.created.size)
    }
}
