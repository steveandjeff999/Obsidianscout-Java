package com.obsidianscout.config

import kotlinx.serialization.json.Json

object JsonSupport {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
}
