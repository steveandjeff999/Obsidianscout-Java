package com.obsidianscout.db

import com.obsidianscout.routes.EventInfoFileDto
import com.obsidianscout.routes.EventInfoPostDto
import com.obsidianscout.routes.EventInfoPostRequest
import com.obsidianscout.scouting.ImageMetadataStripper
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Event Info page: announcements, images and files a team's admins share with their scouts. */
object EventInfoService {

    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_FILES_PER_POST = 20
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_BODY_CHARS = 20_000

    /** Types the browser may show inline. Anything else is sent as a download so uploaded HTML/SVG can't run script. */
    private val INLINE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf")

    private val EXTENSION_TYPES = mapOf(
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "zip" to "application/zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime"
    )

    data class StoredFile(val fileName: String, val contentType: String, val bytes: ByteArray, val teamNumber: Int, val program: String)

    fun isInline(contentType: String): Boolean = contentType in INLINE_TYPES

    fun list(teamNumber: Int, program: String, eventKey: String): List<EventInfoPostDto> = readTransaction {
        val posts = EventInfoPosts.selectAll().where {
            (EventInfoPosts.teamNumber eq teamNumber) and (EventInfoPosts.program eq program) and
                ((EventInfoPosts.eventKey eq "") or (EventInfoPosts.eventKey eq eventKey))
        }.orderBy(EventInfoPosts.pinned to SortOrder.DESC, EventInfoPosts.createdAt to SortOrder.DESC).toList()
        if (posts.isEmpty()) return@readTransaction emptyList()

        val ids = posts.map { it[EventInfoPosts.id].value }
        val filesByPost = EventInfoFiles
            .select(EventInfoFiles.id, EventInfoFiles.postId, EventInfoFiles.fileName, EventInfoFiles.contentType, EventInfoFiles.sizeBytes, EventInfoFiles.createdAt)
            .where { EventInfoFiles.postId inList ids }
            .orderBy(EventInfoFiles.createdAt to SortOrder.ASC)
            .groupBy({ it[EventInfoFiles.postId].value }, { it.toFileDto() })
        posts.map { it.toPostDto(filesByPost[it[EventInfoPosts.id].value].orEmpty()) }
    }

    fun get(id: String, teamNumber: Int, program: String): EventInfoPostDto? {
        val uuid = parse(id) ?: return null
        return readTransaction {
            val row = EventInfoPosts.selectAll().where {
                (EventInfoPosts.id eq uuid) and (EventInfoPosts.teamNumber eq teamNumber) and (EventInfoPosts.program eq program)
            }.firstOrNull() ?: return@readTransaction null
            val files = EventInfoFiles
                .select(EventInfoFiles.id, EventInfoFiles.postId, EventInfoFiles.fileName, EventInfoFiles.contentType, EventInfoFiles.sizeBytes, EventInfoFiles.createdAt)
                .where { EventInfoFiles.postId eq uuid }
                .orderBy(EventInfoFiles.createdAt to SortOrder.ASC)
                .map { it.toFileDto() }
            row.toPostDto(files)
        }
    }

    fun create(teamNumber: Int, program: String, currentEventKey: String, author: String, request: EventInfoPostRequest): EventInfoPostDto {
        val title = request.title.orEmpty().trim().take(MAX_TITLE_CHARS)
        val body = request.body.orEmpty().trim().take(MAX_BODY_CHARS)
        val eventKey = if (request.allEvents == true) "" else currentEventKey
        val now = Instant.now()
        val id = writeTransactionWithRetry {
            EventInfoPosts.insertAndGetId {
                it[EventInfoPosts.teamNumber] = teamNumber
                it[EventInfoPosts.program] = program
                it[EventInfoPosts.eventKey] = eventKey
                it[EventInfoPosts.title] = title
                it[EventInfoPosts.body] = body
                it[EventInfoPosts.pinned] = request.pinned ?: false
                it[EventInfoPosts.authorUsername] = author.take(64)
                it[EventInfoPosts.createdAt] = now
                it[EventInfoPosts.updatedAt] = now
            }.value
        }
        return get(id.toString(), teamNumber, program)!!
    }

    fun update(id: String, teamNumber: Int, program: String, currentEventKey: String, request: EventInfoPostRequest): EventInfoPostDto? {
        val uuid = parse(id) ?: return null
        val count = writeTransactionWithRetry {
            EventInfoPosts.update({
                (EventInfoPosts.id eq uuid) and (EventInfoPosts.teamNumber eq teamNumber) and (EventInfoPosts.program eq program)
            }) {
                request.title?.let { v -> it[title] = v.trim().take(MAX_TITLE_CHARS) }
                request.body?.let { v -> it[body] = v.trim().take(MAX_BODY_CHARS) }
                request.pinned?.let { v -> it[pinned] = v }
                request.allEvents?.let { v -> it[eventKey] = if (v) "" else currentEventKey }
                it[updatedAt] = Instant.now()
            }
        }
        return if (count > 0) get(id, teamNumber, program) else null
    }

    fun delete(id: String, teamNumber: Int, program: String): Boolean {
        val uuid = parse(id) ?: return false
        return writeTransactionWithRetry {
            val owned = EventInfoPosts.selectAll().where {
                (EventInfoPosts.id eq uuid) and (EventInfoPosts.teamNumber eq teamNumber) and (EventInfoPosts.program eq program)
            }.any()
            if (!owned) return@writeTransactionWithRetry false
            EventInfoFiles.deleteWhere { EventInfoFiles.postId eq uuid }
            EventInfoPosts.deleteWhere { EventInfoPosts.id eq uuid } > 0
        }
    }

    /** Attaches [bytes] to the post. Images lose their EXIF/GPS metadata. Returns null when the post isn't the team's. */
    fun addFile(postId: String, teamNumber: Int, program: String, originalName: String, bytes: ByteArray): EventInfoFileDto? {
        val uuid = parse(postId) ?: return null
        val fileName = sanitizeFileName(originalName)
        val imageFormat = ImageMetadataStripper.detect(bytes)
        val (contentType, stored) = when {
            imageFormat != null -> imageFormat.mime to (ImageMetadataStripper.strip(bytes, imageFormat) ?: bytes)
            isGif(bytes) -> "image/gif" to bytes
            else -> {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                // A file claiming to be an image but not decoding as one is served as a plain download.
                (EXTENSION_TYPES[ext] ?: "application/octet-stream") to bytes
            }
        }
        return writeTransactionWithRetry {
            val owned = EventInfoPosts.selectAll().where {
                (EventInfoPosts.id eq uuid) and (EventInfoPosts.teamNumber eq teamNumber) and (EventInfoPosts.program eq program)
            }.any()
            if (!owned) return@writeTransactionWithRetry null
            val existing = EventInfoFiles.selectAll().where { EventInfoFiles.postId eq uuid }.count()
            if (existing >= MAX_FILES_PER_POST) {
                throw IllegalStateException("A post can have at most $MAX_FILES_PER_POST files")
            }
            val now = Instant.now()
            val fileId = EventInfoFiles.insertAndGetId {
                it[EventInfoFiles.postId] = uuid
                it[EventInfoFiles.teamNumber] = teamNumber
                it[EventInfoFiles.program] = program
                it[EventInfoFiles.fileName] = fileName
                it[EventInfoFiles.contentType] = contentType
                it[EventInfoFiles.sizeBytes] = stored.size.toLong()
                it[EventInfoFiles.data] = Base64.getEncoder().encodeToString(stored)
                it[EventInfoFiles.createdAt] = now
            }.value
            EventInfoPosts.update({ EventInfoPosts.id eq uuid }) { it[updatedAt] = now }
            EventInfoFileDto(fileId.toString(), fileName, contentType, stored.size.toLong(), contentType.startsWith("image/"), fileUrl(fileId))
        }
    }

    fun getFile(fileId: String): StoredFile? {
        val uuid = parse(fileId) ?: return null
        return readTransaction {
            EventInfoFiles.selectAll().where { EventInfoFiles.id eq uuid }.firstOrNull()?.let {
                StoredFile(
                    fileName = it[EventInfoFiles.fileName],
                    contentType = it[EventInfoFiles.contentType],
                    bytes = Base64.getDecoder().decode(it[EventInfoFiles.data]),
                    teamNumber = it[EventInfoFiles.teamNumber],
                    program = it[EventInfoFiles.program]
                )
            }
        }
    }

    fun deleteFile(fileId: String, teamNumber: Int, program: String): Boolean {
        val uuid = parse(fileId) ?: return false
        return writeTransactionWithRetry {
            EventInfoFiles.deleteWhere {
                (EventInfoFiles.id eq uuid) and (EventInfoFiles.teamNumber eq teamNumber) and (EventInfoFiles.program eq program)
            } > 0
        }
    }

    private fun parse(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

    private fun fileUrl(id: UUID) = "/api/event-info/files/$id"

    private fun isGif(bytes: ByteArray) =
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" }

    private fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it >= ' ' && it != '"' && it != '\u007f' }.trim()
        return cleaned.ifBlank { "file" }.take(255)
    }

    private fun ResultRow.toFileDto(): EventInfoFileDto {
        val type = this[EventInfoFiles.contentType]
        return EventInfoFileDto(
            id = this[EventInfoFiles.id].value.toString(),
            fileName = this[EventInfoFiles.fileName],
            contentType = type,
            sizeBytes = this[EventInfoFiles.sizeBytes],
            isImage = type.startsWith("image/"),
            url = fileUrl(this[EventInfoFiles.id].value)
        )
    }

    private fun ResultRow.toPostDto(files: List<EventInfoFileDto>) = EventInfoPostDto(
        id = this[EventInfoPosts.id].value.toString(),
        eventKey = this[EventInfoPosts.eventKey],
        title = this[EventInfoPosts.title],
        body = this[EventInfoPosts.body],
        pinned = this[EventInfoPosts.pinned],
        authorUsername = this[EventInfoPosts.authorUsername],
        createdAt = this[EventInfoPosts.createdAt].toString(),
        updatedAt = this[EventInfoPosts.updatedAt].toString(),
        files = files
    )
}
