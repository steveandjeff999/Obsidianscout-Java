package com.obsidianscout.scouting

import com.obsidianscout.auth.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageMetadataStripperTest {

    private val gpsMarker = "GPSLatitude-SECRET".toByteArray(Charsets.US_ASCII)

    private fun ByteArray.containsSeq(seq: ByteArray): Boolean =
        (0..size - seq.size).any { i -> seq.indices.all { this[i + it] == seq[it] } }

    private fun realJpeg(): ByteArray {
        val img = BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 16) for (y in 0 until 12) img.setRGB(x, y, (x * 15 shl 16) or (y * 20 shl 8))
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    @Test
    fun `jpeg exif and comment segments are removed and image still decodes`() {
        val original = realJpeg()
        val exif = segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + gpsMarker)
        val comment = segment(0xFE, gpsMarker)
        val tagged = original.copyOfRange(0, 2) + exif + comment + original.copyOfRange(2, original.size)
        assertTrue(tagged.containsSeq(gpsMarker))

        val stripped = assertNotNull(ImageMetadataStripper.strip(tagged, ImageMetadataStripper.Format.JPEG))
        assertFalse(stripped.containsSeq(gpsMarker))
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(stripped)))
        assertEquals(16, decoded.width)
        assertEquals(12, decoded.height)
    }

    @Test
    fun `png text chunks are removed and image still decodes`() {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        val original = out.toByteArray()
        // Insert a tEXt chunk right after IHDR (8-byte signature + 25-byte IHDR chunk).
        val text = "Comment\u0000".toByteArray(Charsets.US_ASCII) + gpsMarker
        val chunk = byteArrayOf(0, 0, 0, text.size.toByte()) + "tEXt".toByteArray(Charsets.US_ASCII) + text + byteArrayOf(0, 0, 0, 0)
        val tagged = original.copyOfRange(0, 33) + chunk + original.copyOfRange(33, original.size)

        val stripped = assertNotNull(ImageMetadataStripper.strip(tagged, ImageMetadataStripper.Format.PNG))
        assertFalse(stripped.containsSeq(gpsMarker))
        assertTrue(stripped.contentEquals(original))
        assertNotNull(ImageIO.read(ByteArrayInputStream(stripped)))
    }

    @Test
    fun `webp exif chunk is removed, vp8x flags cleared and riff size rewritten`() {
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun chunk(fourcc: String, data: ByteArray): ByteArray {
            val padded = if (data.size % 2 == 1) data + byteArrayOf(0) else data
            return fourcc.toByteArray(Charsets.US_ASCII) + le(data.size) + padded
        }
        val vp8x = chunk("VP8X", byteArrayOf(0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        val vp8 = chunk("VP8 ", ByteArray(11) { 0x55 })
        val exif = chunk("EXIF", gpsMarker)
        val xmp = chunk("XMP ", gpsMarker)
        val body = "WEBP".toByteArray(Charsets.US_ASCII) + vp8x + vp8 + exif + xmp
        val tagged = "RIFF".toByteArray(Charsets.US_ASCII) + le(body.size) + body

        val stripped = assertNotNull(ImageMetadataStripper.strip(tagged, ImageMetadataStripper.Format.WEBP))
        assertFalse(stripped.containsSeq(gpsMarker))
        val expectedBody = "WEBP".toByteArray(Charsets.US_ASCII) + chunk("VP8X", ByteArray(10)) + vp8
        assertTrue(stripped.contentEquals("RIFF".toByteArray(Charsets.US_ASCII) + le(expectedBody.size) + expectedBody))
    }

    @Test
    fun `truncated jpeg is reported as malformed`() {
        val jpeg = realJpeg()
        assertNull(ImageMetadataStripper.strip(jpeg.copyOfRange(0, 10), ImageMetadataStripper.Format.JPEG))
    }

    @Test
    fun `sanitizeBase64Image returns a data uri with metadata removed`() {
        val original = realJpeg()
        val exif = segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + gpsMarker)
        val tagged = original.copyOfRange(0, 2) + exif + original.copyOfRange(2, original.size)
        val result = sanitizeBase64Image(Base64.getEncoder().encodeToString(tagged))

        assertTrue(result.startsWith("data:image/jpeg;base64,"))
        val decoded = Base64.getDecoder().decode(result.substringAfter(","))
        assertFalse(decoded.containsSeq(gpsMarker))
        assertTrue(decoded.contentEquals(original))
    }

    @Test
    fun `oversized pit photos are rejected and normal ones are kept`() {
        val normal = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(realJpeg())
        val ok = sanitizePitData(JsonObject(mapOf("photo" to JsonPrimitive(normal), "notes" to JsonPrimitive("fast"))))
        assertTrue(ok["photo"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertEquals("fast", ok["notes"]!!.jsonPrimitive.content)

        val huge = "data:image/jpeg;base64," + "A".repeat(3 * 1024 * 1024)
        val err = assertFailsWith<ApiException> {
            sanitizePitData(JsonObject(mapOf("photo" to JsonPrimitive(huge))))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, err.status)
    }

    @Test
    fun `non image strings pass through untouched`() {
        val text = Base64.getEncoder().encodeToString("just some notes".toByteArray())
        assertEquals(text, sanitizeBase64Image(text))
    }
}
