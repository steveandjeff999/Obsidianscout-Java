package com.obsidianscout.blob

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlobReferenceTest {

    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
    )

    @Test
    fun `refs and urls round-trip and reject lookalikes`() {
        val id = UUID.randomUUID()
        assertEquals(id, BlobService.idFromRef(BlobService.ref(id)))
        assertEquals(id, BlobService.idFromUrl(BlobService.url(id)))
        assertNull(BlobService.idFromRef("data:image/png;base64,AAAA"))
        assertNull(BlobService.idFromUrl("/api/blobs/../users"))
        assertNull(BlobService.idFromUrl("https://x.test/api/blobs/$id"))
        assertNull(BlobService.idFromUrl("/api/blobs/$id?x=1"))
    }

    @Test
    fun `decodeImage reads data urls and sniffs the real type`() {
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(png)
        val decoded = assertNotNull(BlobMigrationService.decodeImage(dataUrl))
        // Declared JPEG, actually PNG: the bytes win.
        assertEquals("image/png", decoded.first)
        assertEquals(png.size, decoded.second.size)
    }

    @Test
    fun `decodeImage ignores ordinary strings, svg and broken base64`() {
        assertNull(BlobMigrationService.decodeImage("swerve"))
        assertNull(BlobMigrationService.decodeImage("/api/blobs/${UUID.randomUUID()}"))
        assertNull(BlobMigrationService.decodeImage("data:image/svg+xml;base64," + Base64.getEncoder().encodeToString("<svg/>".toByteArray())))
        assertNull(BlobMigrationService.decodeImage("data:image/png;base64,***not base64***"))
        assertNull(BlobMigrationService.decodeImage("data:image/png;base64,"))
    }

    @Test
    fun `a re-sent entry with base64 matches the stored copy with a blob url`() {
        val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val stored = buildJsonObject {
            put("drivetrain", "swerve")
            put("robot_photo", BlobService.url(UUID.randomUUID()))
        }
        val resent = buildJsonObject {
            put("drivetrain", "swerve")
            put("robot_photo", dataUrl)
        }
        kotlin.test.assertTrue(com.obsidianscout.config.JsonSupport.scoutingDataAgrees(stored, resent))
        // Answers still have to match, and a missing photo is still a difference.
        val otherAnswer = buildJsonObject { put("drivetrain", "tank"); put("robot_photo", dataUrl) }
        val noPhoto = buildJsonObject { put("drivetrain", "swerve"); put("robot_photo", "") }
        kotlin.test.assertFalse(com.obsidianscout.config.JsonSupport.scoutingDataAgrees(stored, otherAnswer))
        kotlin.test.assertFalse(com.obsidianscout.config.JsonSupport.scoutingDataAgrees(stored, noPhoto))
    }

    @Test
    fun `blobIdsIn finds every photo url in entry json and nothing else`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val json = """{"robot_photo": "/api/blobs/$a", "images": ["/api/blobs/$b", "/api/blobs/$a"], "note": "blob:$b", "x": "/api/blobs/nope"}"""
        assertEquals(setOf(a, b), BlobMigrationService.blobIdsIn(json))
        assertEquals(emptySet(), BlobMigrationService.blobIdsIn("""{"drivetrain": "swerve"}"""))
        assertEquals(emptySet(), BlobMigrationService.blobIdsIn(null))
    }

    @Test
    fun `offloadImages leaves data without photos untouched`() {
        val data = buildJsonObject {
            put("targetTeamNumber", 254)
            put("drivetrain", "swerve")
            put("existing_photo", BlobService.url(UUID.randomUUID()))
        }
        val created = mutableListOf<UUID>()
        assertNull(BlobMigrationService.offloadImages(data, BlobService.KIND_PIT_PHOTO, 254, "FRC", created))
        assertEquals(0, created.size)
        assertEquals(JsonPrimitive("swerve"), data["drivetrain"])
    }
}
