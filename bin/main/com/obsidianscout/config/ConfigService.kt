package com.obsidianscout.config

import com.obsidianscout.db.ScoutingConfigs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

@Serializable
data class ScoutingConfig(
    val version: Int = 1,
    val title: String = "ObsidianScout",
    val fields: List<ScoutingField> = emptyList(),
    val analytics: List<AnalyticsWidget> = emptyList()
)

@Serializable
data class ScoutingField(
    val id: String,
    val label: String,
    val type: String,
    val required: Boolean = false,
    val options: List<ScoutingOption> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
    val pointsPer: Double? = null
)

@Serializable
data class ScoutingOption(
    val label: String,
    val value: String,
    val points: Double = 0.0
)

@Serializable
data class AnalyticsWidget(
    val id: String,
    val title: String,
    val type: String,
    val fieldId: String? = null
)

object ConfigService {
    private val defaultConfigPath = Paths.get("config", "default-scouting-config.json")

    fun ensureDefaultConfig() {
        transaction {
            val existing = ScoutingConfigs
                .select { ScoutingConfigs.teamNumber eq 0 }
                .limit(1)
                .firstOrNull() != null
            if (!existing) {
                val jsonText = loadDefaultConfigText()
                ScoutingConfigs.insert {
                    it[teamNumber] = 0
                    it[configJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            }
        }
    }

    fun getConfigJson(teamNumber: Int): String {
        return transaction {
            // Try team-specific config first
            val teamConfig = ScoutingConfigs
                .select { ScoutingConfigs.teamNumber eq teamNumber }
                .limit(1)
                .firstOrNull()
                ?.get(ScoutingConfigs.configJson)

            if (teamConfig != null) {
                return@transaction teamConfig
            }

            // Fall back to team 0 (global default)
            ScoutingConfigs
                .select { ScoutingConfigs.teamNumber eq 0 }
                .limit(1)
                .firstOrNull()
                ?.get(ScoutingConfigs.configJson)
        } ?: loadDefaultConfigText()
    }

    fun getConfig(teamNumber: Int): ScoutingConfig {
        val jsonText = normalizeConfigJson(getConfigJson(teamNumber))
        return JsonSupport.json.decodeFromString(jsonText)
    }

    fun updateConfig(teamNumber: Int, newJson: String): ScoutingConfig {
        val normalizedJson = normalizeConfigJson(newJson)
        val parsed = JsonSupport.json.decodeFromString<ScoutingConfig>(normalizedJson)
        transaction {
            val row = ScoutingConfigs
                .select { ScoutingConfigs.teamNumber eq teamNumber }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                ScoutingConfigs.insert {
                    it[ScoutingConfigs.teamNumber] = teamNumber
                    it[configJson] = normalizedJson
                    it[updatedAt] = Instant.now()
                }
            } else {
                ScoutingConfigs.update({ ScoutingConfigs.id eq row[ScoutingConfigs.id] }) {
                    it[configJson] = normalizedJson
                    it[updatedAt] = Instant.now()
                }
            }
        }
        return parsed
    }

    private fun loadDefaultConfigText(): String {
        return if (Files.exists(defaultConfigPath)) {
            Files.readString(defaultConfigPath)
        } else {
            JsonSupport.json.encodeToString(defaultConfig())
        }
    }

    private fun defaultConfig(): ScoutingConfig {
        return ScoutingConfig(
            version = 3,
            title = "ObsidianScout",
            fields = listOf(
                ScoutingField(
                    id = "matchNumber",
                    label = "Match Number",
                    type = "number",
                    required = true,
                    min = 1,
                    max = 200
                ),
                ScoutingField(
                    id = "targetTeamNumber",
                    label = "Scouted Team Number",
                    type = "number",
                    required = true,
                    min = 1,
                    max = 9999
                ),
                ScoutingField(
                    id = "sectionAuto",
                    label = "Auto",
                    type = "section"
                ),
                ScoutingField(
                    id = "autoScore",
                    label = "Auto Score",
                    type = "counter",
                    min = 0,
                    max = 10,
                    step = 1,
                    pointsPer = 3.0
                ),
                ScoutingField(
                    id = "sectionTeleop",
                    label = "Teleop",
                    type = "section"
                ),
                ScoutingField(
                    id = "teleopCycles",
                    label = "Teleop Cycles",
                    type = "counter",
                    min = 0,
                    max = 30,
                    step = 1,
                    pointsPer = 1.0
                ),
                ScoutingField(
                    id = "sectionEndgame",
                    label = "Endgame",
                    type = "section"
                ),
                ScoutingField(
                    id = "endgame",
                    label = "Endgame Result",
                    type = "select",
                    options = listOf(
                        ScoutingOption("Park", "Park", 5.0),
                        ScoutingOption("Climb", "Climb", 12.0),
                        ScoutingOption("Fail", "Fail", 0.0)
                    )
                ),
                ScoutingField(
                    id = "driverRating",
                    label = "Driver Rating",
                    type = "rating",
                    min = 1,
                    max = 5,
                    pointsPer = 2.0
                ),
                ScoutingField(
                    id = "notes",
                    label = "Notes",
                    type = "text"
                )
            ),
            analytics = listOf(
                AnalyticsWidget(
                    id = "entryCount",
                    title = "Entries Collected",
                    type = "count"
                ),
                AnalyticsWidget(
                    id = "avgScore",
                    title = "Average Score",
                    type = "score_avg"
                ),
                AnalyticsWidget(
                    id = "autoAvg",
                    title = "Auto Score Avg",
                    type = "avg",
                    fieldId = "autoScore"
                ),
                AnalyticsWidget(
                    id = "teleopAvg",
                    title = "Teleop Cycles Avg",
                    type = "avg",
                    fieldId = "teleopCycles"
                ),
                AnalyticsWidget(
                    id = "totalScore",
                    title = "Total Points",
                    type = "score_total"
                )
            )
        )
    }

    private fun normalizeConfigJson(text: String): String {
        val element = JsonSupport.json.parseToJsonElement(text)
        val obj = element as? JsonObject ?: return text
        val fields = obj["fields"] as? JsonArray ?: return text
        val normalizedFields = fields.map { fieldElement ->
            val fieldObj = fieldElement as? JsonObject ?: return@map fieldElement
            val options = fieldObj["options"] as? JsonArray ?: return@map fieldElement
            var changed = false
            val transformed = options.map { option ->
                when (option) {
                    is JsonPrimitive -> {
                        changed = true
                        val value = option.content
                        JsonObject(
                            mapOf(
                                "label" to JsonPrimitive(value),
                                "value" to JsonPrimitive(value),
                                "points" to JsonPrimitive(0)
                            )
                        )
                    }
                    is JsonObject -> {
                        val label = (option["label"] as? JsonPrimitive)?.content
                        val value = (option["value"] as? JsonPrimitive)?.content
                        val pointsRaw = (option["points"] as? JsonPrimitive)?.content
                        val points = pointsRaw?.toDoubleOrNull() ?: 0.0
                        val finalLabel = label ?: value ?: ""
                        val finalValue = value ?: label ?: ""
                        val needsUpdate = label == null || value == null || option["points"] == null
                        if (needsUpdate) {
                            changed = true
                        }
                        JsonObject(
                            option + mapOf(
                                "label" to JsonPrimitive(finalLabel),
                                "value" to JsonPrimitive(finalValue),
                                "points" to JsonPrimitive(points)
                            )
                        )
                    }
                    else -> option
                }
            }
            if (!changed) {
                return@map fieldElement
            }
            JsonObject(fieldObj + ("options" to JsonArray(transformed)))
        }
        val normalized = JsonObject(obj + ("fields" to JsonArray(normalizedFields)))
        return JsonSupport.json.encodeToString(JsonElement.serializer(), normalized)
    }
}
