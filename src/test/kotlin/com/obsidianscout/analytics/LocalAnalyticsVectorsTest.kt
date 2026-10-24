package com.obsidianscout.analytics

import com.obsidianscout.config.AnalyticsWidget
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.config.ScoutingField
import com.obsidianscout.config.ScoutingOption
import com.obsidianscout.integrations.RecencyBiasSettings
import com.obsidianscout.scouting.EntrySync
import com.obsidianscout.scouting.ScoutingEntryRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the server's analytics math to `src/test/resources/local-analytics-cases.json`, which the site
 * (src/test/js/low-bandwidth.test.mjs) and the app (test/local_analytics_test.dart) replay against their on-device ports.
 *
 * After an intentional change to the server math, regenerate the file:
 *   OBSIDIAN_VECTORS_OUT=<repo>/Site/Obsidianscout-Java/src/test/resources/local-analytics-cases.json ./gradlew test --tests '*LocalAnalyticsVectorsTest'
 * and copy it to App/Obsidianscout-Java-App/test/fixtures/.
 */
class LocalAnalyticsVectorsTest {

    private val config = ScoutingConfig(
        fields = listOf(
            ScoutingField(id = "auto_coral", label = "Auto Coral", type = "counter", phase = "auto", pointsPer = 3.0),
            ScoutingField(id = "leave", label = "Leave", type = "checkbox", phase = "auto", pointsPer = 2.0),
            ScoutingField(id = "teleop_coral", label = "Teleop Coral", type = "counter", phase = "teleop", pointsPer = 2.0),
            ScoutingField(id = "defense", label = "Defense", type = "rating", phase = "teleop"),
            ScoutingField(
                id = "climb", label = "Climb", type = "select", phase = "endgame",
                options = listOf(ScoutingOption("None", "none", 0.0), ScoutingOption("Shallow", "shallow", 6.0), ScoutingOption("Deep", "deep", 12.0))
            ),
            ScoutingField(id = "notes", label = "Notes", type = "text"),
            ScoutingField(id = "hdr", label = "", type = "header")
        )
    )

    private val customConfig = config.copy(
        analytics = listOf(
            AnalyticsWidget("w_min", "Min Auto", "min", "auto_coral"),
            AnalyticsWidget("w_max", "Max Teleop", "max", "teleop_coral"),
            AnalyticsWidget("w_pct", "Leave %", "pct", "leave"),
            AnalyticsWidget("w_bar", "Climbs", "bar", "climb"),
            AnalyticsWidget("w_unknown", "Unknown", "median", "auto_coral")
        )
    )

    private fun entry(
        id: String, team: Int, match: String?, matchNumber: Int?, createdAt: String,
        data: JsonObject, prescout: Boolean = false, event: String = "2026test", owner: Int = 254
    ) = ScoutingEntryRecord(
        id = id, ownerTeamNumber = owner, targetTeamNumber = team, eventKey = event, matchKey = match,
        matchNumber = matchNumber, data = data, createdAt = createdAt, isPrescout = prescout
    )

    private fun d(auto: Any?, leave: Any?, teleop: Any?, defense: Any?, climb: Any?, notes: String? = null) = buildJsonObject {
        fun putAny(k: String, v: Any?) = when (v) {
            null -> {}
            is JsonElement -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, v.toString())
        }
        putAny("auto_coral", auto); putAny("leave", leave); putAny("teleop_coral", teleop)
        putAny("defense", defense); putAny("climb", climb); putAny("notes", notes)
    }

    private val entries = listOf(
        entry("a1", 1678, "2026test_qm1", 1, "2026-03-01T10:00:00Z", d(3, true, 8, 4, "deep", "fast")),
        entry("a2", 1678, "2026test_qm9", 9, "2026-03-01T12:00:00Z", d("2", "false", 5, "3", "shallow")),
        entry("a3", 1678, "2026test_sf2m1", 1, "2026-03-02T09:00:00Z", d(4, true, 10, 5, "Deep")),
        entry("a4", 1678, "2026test_qm_s1_m14", 14, "2026-03-01T15:00:00Z", d(1, false, 2, null, "none")),
        entry("a5", 1678, "2026test_qm14", 14, "2026-03-01T15:05:00Z", d(2, true, 3, 2, JsonPrimitive("bogus")), owner = 9999),
        entry("p1", 1678, "2026old_qm3", 3, "2026-02-01T10:00:00Z", d(6, true, 12, 5, "deep"), prescout = true, event = "2026old"),
        entry("b1", 118, "2026test_qm2", 2, "2026-03-01T10:30:00Z", d(" 5", true, "x", 1, null)),
        entry("b2", 118, null, 7, "2026-03-01T11:30:00Z", d(0, "TRUE", 1.5, kotlinx.serialization.json.JsonNull, "shallow")),
        entry("b3", 118, null, null, "not-a-date", d(1, null, 0, 0, "deep"))
    )

    private val recencyCases = listOf(
        "disabled" to RecencyBiasSettings(),
        "exponential" to RecencyBiasSettings(enabled = true, halfLifeMatches = 2.0, minMatches = 2, prescoutWeight = 0.25),
        "lastN" to RecencyBiasSettings(enabled = true, mode = RecencyBiasSettings.MODE_LAST_N, windowSize = 2, minMatches = 1),
        "tooFew" to RecencyBiasSettings(enabled = true, minMatches = 15)
    )

    private fun encode(records: List<ScoutingEntryRecord>) = JsonArray(records.map {
        JsonSupport.json.encodeToJsonElement(ScoutingEntryRecord.serializer(), it)
    })

    private fun widgetsWithoutTime(response: AnalyticsResponse): JsonElement =
        JsonSupport.json.encodeToJsonElement(AnalyticsResponse.serializer(), response).jsonObject["widgets"]!!

    private fun build(): JsonObject {
        val team1678 = entries.filter { it.targetTeamNumber == 1678 }
        val merged = AnalyticsService.mergePrescoutEntries(
            entries.filter { !it.isPrescout }, entries.filter { it.isPrescout }, "2026test", forceUsePrescout = false
        )
        return buildJsonObject {
            put("config", JsonSupport.json.encodeToJsonElement(ScoutingConfig.serializer(), config))
            put("customConfig", JsonSupport.json.encodeToJsonElement(ScoutingConfig.serializer(), customConfig))
            put("entries", encode(entries))
            put("expected", buildJsonObject {
                put("scores", buildJsonObject { entries.forEach { put(it.id, AnalyticsService.scoreEntry(config, it)) } })
                put("defaultWidgets", widgetsWithoutTime(AnalyticsService.generate(config, entries)))
                put("customWidgets", widgetsWithoutTime(AnalyticsService.generate(customConfig, team1678)))
                put("emptyWidgets", widgetsWithoutTime(AnalyticsService.generate(customConfig, emptyList())))
                put("mergedIds", buildJsonArray { merged.forEach { add(JsonPrimitive(it.id)) } })
                put("recency", buildJsonArray {
                    for ((name, settings) in recencyCases) {
                        add(buildJsonObject {
                            put("name", name)
                            put("settings", JsonSupport.json.encodeToJsonElement(RecencyBiasSettings.serializer(), settings))
                            put("weights", buildJsonArray { RecencyWeighting.weights(team1678, settings).forEach { add(JsonPrimitive(it)) } })
                        })
                    }
                })
                val packed = EntrySync.pack(encode(entries).map { it.jsonObject })
                put("packed", buildJsonObject {
                    put("cols", packed.cols)
                    put("keys", packed.keys)
                    put("rows", packed.rows)
                })
            })
        }
    }

    @Test
    fun serverMathMatchesSharedCases() {
        val actual = build()
        val out = System.getenv("OBSIDIAN_VECTORS_OUT")
        if (!out.isNullOrBlank()) {
            File(out).writeText(JsonSupport.json.encodeToString(JsonObject.serializer(), actual))
            return
        }
        val text = javaClass.getResource("/local-analytics-cases.json")?.readText()
            ?: error("local-analytics-cases.json missing; regenerate it (see class comment)")
        assertEquals(JsonSupport.json.parseToJsonElement(text), actual)
    }
}
