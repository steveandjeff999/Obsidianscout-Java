package com.obsidianscout.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object JsonSupport {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** [json] without indentation, for HTTP responses: indentation is pure overhead on the wire (and for gzip). */
    val wireJson: Json = Json(json) { prettyPrint = false }

    fun scoutingDataAgrees(data1: JsonObject, data2: JsonObject): Boolean {
        val excludedKeys = setOf(
            "eventKey", "matchKey", "matchNumber", "targetTeamNumber",
            "scouterName", "scouter", "scoutName", "alliance", "id",
            "ownerTeamNumber", "createdAt", "isPrescout"
        )
        val fields1 = data1.filterKeys { it !in excludedKeys }.mapValues { normalizePhoto(it.value) }
        val fields2 = data2.filterKeys { it !in excludedKeys }.mapValues { normalizePhoto(it.value) }
        return fields1 == fields2
    }

    private val PHOTO_PLACEHOLDER = JsonPrimitive("<photo>")
    private val BLOB_PHOTO_URL = Regex("^/api/blobs/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /**
     * Photos compare by presence, not content. A stored photo becomes a `/api/blobs/<id>` URL while a re-sent copy of
     * the same entry still carries base64, and two devices re-encode the same picture differently; comparing the
     * strings would make every re-sent entry with a photo look new and create duplicates.
     */
    private fun normalizePhoto(value: JsonElement): JsonElement = when {
        value is JsonPrimitive && value.isString && isPhotoString(value.content) -> PHOTO_PLACEHOLDER
        value is JsonArray -> JsonArray(value.map { normalizePhoto(it) })
        else -> value
    }

    private fun isPhotoString(s: String): Boolean =
        s.startsWith("data:image/") || BLOB_PHOTO_URL.matches(s) || (s.length > 5000 && s.startsWith("/9j/"))
}

