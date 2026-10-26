package com.obsidianscout.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
        val fields1 = data1.filterKeys { it !in excludedKeys }
        val fields2 = data2.filterKeys { it !in excludedKeys }
        return fields1 == fields2
    }
}

