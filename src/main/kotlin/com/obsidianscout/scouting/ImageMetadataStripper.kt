package com.obsidianscout.scouting

import java.io.ByteArrayOutputStream

/**
 * Strips EXIF/GPS, XMP, IPTC and text metadata from JPEG, PNG and WebP files by walking their
 * container structure, without decoding pixels. This avoids javax.imageio / java.awt, which do not
 * initialize in the GraalVM native image (Toolkit's JNI setup fails), so every photo upload crashed.
 */
internal object ImageMetadataStripper {

    enum class Format(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp") }

    fun detect(bytes: ByteArray): Format? {
        if (bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF) return Format.JPEG
        if (bytes.size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) return Format.PNG
        // RIFF container whose form type is WEBP (plain RIFF would also match WAV/AVI).
        if (bytes.size >= 12 && ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WEBP") return Format.WEBP
        return null
    }

    /** Returns the image with metadata removed, or null if the file is malformed. */
    fun strip(bytes: ByteArray, format: Format): ByteArray? = try {
        when (format) {
            Format.JPEG -> stripJpeg(bytes)
            Format.PNG -> stripPng(bytes)
            Format.WEBP -> stripWebp(bytes)
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun stripJpeg(bytes: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2) // SOI
        var pos = 2
        while (pos < bytes.size) {
            if (u8(bytes, pos) != 0xFF) return null
            // Markers may be preceded by any number of 0xFF fill bytes.
            while (pos < bytes.size && u8(bytes, pos) == 0xFF) pos++
            val marker = u8(bytes, pos)
            pos++
            when {
                marker == 0xD9 -> { // EOI
                    out.write(0xFF); out.write(marker)
                    return out.toByteArray()
                }
                marker == 0x01 || marker in 0xD0..0xD7 -> { // standalone markers
                    out.write(0xFF); out.write(marker)
                }
                marker == 0xDA -> { // SOS: copy the scan data and everything after it unchanged
                    out.write(0xFF); out.write(marker)
                    out.write(bytes, pos, bytes.size - pos)
                    return out.toByteArray()
                }
                else -> {
                    val length = (u8(bytes, pos) shl 8) or u8(bytes, pos + 1)
                    if (length < 2 || pos + length > bytes.size) return null
                    if (!isDroppedJpegSegment(marker)) {
                        out.write(0xFF); out.write(marker)
                        out.write(bytes, pos, length)
                    }
                    pos += length
                }
            }
        }
        return null
    }

    // APP1 (EXIF/XMP), APP3..APP13 (incl. Photoshop/IPTC), APP15 and COM. APP0 (JFIF),
    // APP2 (ICC profile) and APP14 (Adobe colour transform) are needed to render correctly.
    private fun isDroppedJpegSegment(marker: Int): Boolean =
        marker == 0xE1 || marker in 0xE3..0xED || marker == 0xEF || marker == 0xFE

    private fun stripPng(bytes: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, PNG_SIGNATURE.size)
        var pos = PNG_SIGNATURE.size
        while (pos + 12 <= bytes.size) {
            val length = u32be(bytes, pos)
            if (length < 0 || length > bytes.size - pos - 12) return null
            val type = ascii(bytes, pos + 4)
            val chunkSize = 12 + length
            if (type !in PNG_DROPPED_CHUNKS) out.write(bytes, pos, chunkSize)
            pos += chunkSize
            if (type == "IEND") return out.toByteArray()
        }
        return null
    }

    private fun stripWebp(bytes: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 12) // RIFF, size (rewritten below), WEBP
        val end = minOf(bytes.size.toLong(), 8L + u32le(bytes, 4)).toInt()
        var pos = 12
        while (pos + 8 <= end) {
            val fourcc = ascii(bytes, pos)
            val size = u32le(bytes, pos + 4)
            val dataEnd = pos + 8L + size
            if (dataEnd > end) return null
            // Chunks are padded to an even length; tolerate a missing pad byte on the last one.
            val chunkEnd = minOf(dataEnd + (size and 1L), end.toLong()).toInt()
            if (fourcc != "EXIF" && fourcc != "XMP ") {
                val chunk = bytes.copyOfRange(pos, chunkEnd)
                if (fourcc == "VP8X" && chunk.size > 8) {
                    // Clear the EXIF (0x08) and XMP (0x04) presence flags.
                    chunk[8] = (chunk[8].toInt() and 0x0C.inv()).toByte()
                }
                out.write(chunk)
            }
            pos = chunkEnd
        }
        if (pos != end) return null
        val result = out.toByteArray()
        val riffSize = result.size - 8
        result[4] = riffSize.toByte()
        result[5] = (riffSize shr 8).toByte()
        result[6] = (riffSize shr 16).toByte()
        result[7] = (riffSize shr 24).toByte()
        return result
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_DROPPED_CHUNKS = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u32be(b: ByteArray, i: Int): Int =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun u32le(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
    private fun ascii(b: ByteArray, i: Int): String = String(b, i, 4, Charsets.US_ASCII)
}
