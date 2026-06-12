package com.obsidianscout.config

import com.obsidianscout.db.PitScoutingConfigs
import com.obsidianscout.db.ScoutingConfigs
import com.obsidianscout.db.QualitativeScoutingConfigs
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
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
    val analytics: List<AnalyticsWidget> = emptyList(),
    @SerialName("tba_key") val tbaKey: String? = null,
    @SerialName("first_username") val firstUsername: String? = null,
    @SerialName("first_key") val firstKey: String? = null,
    @SerialName("event_code") val eventCode: String? = null
)

@Serializable
data class ScoutingField(
    val id: String,
    val label: String,
    val type: String,
    val required: Boolean = false,
    val phase: String? = null,
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
    private val defaultPitConfigPath = Paths.get("config", "default-pit-scouting-config.json")
    private val defaultQualitativeConfigPath = Paths.get("config", "default-qualitative-scouting-config.json")

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

            val existingPit = PitScoutingConfigs
                .select { PitScoutingConfigs.teamNumber eq 0 }
                .limit(1)
                .firstOrNull() != null
            if (!existingPit) {
                val jsonText = loadDefaultPitConfigText()
                PitScoutingConfigs.insert {
                    it[teamNumber] = 0
                    it[configJson] = jsonText
                    it[updatedAt] = Instant.now()
                }
            }

            val existingQualitative = QualitativeScoutingConfigs
                .select { QualitativeScoutingConfigs.teamNumber eq 0 }
                .limit(1)
                .firstOrNull() != null
            if (!existingQualitative) {
                val jsonText = loadDefaultQualitativeConfigText()
                QualitativeScoutingConfigs.insert {
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

    fun getPitConfigJson(teamNumber: Int): String {
        return transaction {
            val teamConfig = PitScoutingConfigs
                .select { PitScoutingConfigs.teamNumber eq teamNumber }
                .limit(1)
                .firstOrNull()
                ?.get(PitScoutingConfigs.configJson)

            if (teamConfig != null) {
                return@transaction teamConfig
            }

            PitScoutingConfigs
                .select { PitScoutingConfigs.teamNumber eq 0 }
                .limit(1)
                .firstOrNull()
                ?.get(PitScoutingConfigs.configJson)
        } ?: loadDefaultPitConfigText()
    }

    fun getPitConfig(teamNumber: Int): ScoutingConfig {
        val jsonText = normalizeConfigJson(getPitConfigJson(teamNumber))
        return JsonSupport.json.decodeFromString(jsonText)
    }

    fun updatePitConfig(teamNumber: Int, newJson: String): ScoutingConfig {
        val normalizedJson = normalizeConfigJson(newJson)
        val parsed = JsonSupport.json.decodeFromString<ScoutingConfig>(normalizedJson)
        transaction {
            val row = PitScoutingConfigs
                .select { PitScoutingConfigs.teamNumber eq teamNumber }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                PitScoutingConfigs.insert {
                    it[PitScoutingConfigs.teamNumber] = teamNumber
                    it[configJson] = normalizedJson
                    it[updatedAt] = Instant.now()
                }
            } else {
                PitScoutingConfigs.update({ PitScoutingConfigs.id eq row[PitScoutingConfigs.id] }) {
                    it[configJson] = normalizedJson
                    it[updatedAt] = Instant.now()
                }
            }
        }
        return parsed
    }

    fun getQualitativeConfigJson(teamNumber: Int): String {
        return transaction {
            val teamConfig = QualitativeScoutingConfigs
                .select { QualitativeScoutingConfigs.teamNumber eq teamNumber }
                .limit(1)
                .firstOrNull()
                ?.get(QualitativeScoutingConfigs.configJson)

            if (teamConfig != null) {
                return@transaction teamConfig
            }

            QualitativeScoutingConfigs
                .select { QualitativeScoutingConfigs.teamNumber eq 0 }
                .limit(1)
                .firstOrNull()
                ?.get(QualitativeScoutingConfigs.configJson)
        } ?: loadDefaultQualitativeConfigText()
    }

    fun getQualitativeConfig(teamNumber: Int): ScoutingConfig {
        val jsonText = normalizeConfigJson(getQualitativeConfigJson(teamNumber))
        return JsonSupport.json.decodeFromString(jsonText)
    }

    fun updateQualitativeConfig(teamNumber: Int, newJson: String): ScoutingConfig {
        val normalizedJson = normalizeConfigJson(newJson)
        val parsed = JsonSupport.json.decodeFromString<ScoutingConfig>(normalizedJson)
        transaction {
            val row = QualitativeScoutingConfigs
                .select { QualitativeScoutingConfigs.teamNumber eq teamNumber }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                QualitativeScoutingConfigs.insert {
                    it[QualitativeScoutingConfigs.teamNumber] = teamNumber
                    it[configJson] = normalizedJson
                    it[updatedAt] = Instant.now()
                }
            } else {
                QualitativeScoutingConfigs.update({ QualitativeScoutingConfigs.id eq row[QualitativeScoutingConfigs.id] }) {
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

    private fun loadDefaultPitConfigText(): String {
        return if (Files.exists(defaultPitConfigPath)) {
            Files.readString(defaultPitConfigPath)
        } else {
            JsonSupport.json.encodeToString(defaultPitConfig())
        }
    }

    private fun loadDefaultQualitativeConfigText(): String {
        return if (Files.exists(defaultQualitativeConfigPath)) {
            Files.readString(defaultQualitativeConfigPath)
        } else {
            JsonSupport.json.encodeToString(defaultQualitativeConfig())
        }
    }

    private fun defaultConfig(): ScoutingConfig {
        return ScoutingConfig(
            version = 3,
            title = "ObsidianScout",
            fields = listOf(
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

    private fun defaultPitConfig(): ScoutingConfig {
        return ScoutingConfig(
            version = 1,
            title = "ObsidianScout Pit Scouting",
            fields = listOf(
                ScoutingField(
                    id = "sectionRobot",
                    label = "Robot",
                    type = "section"
                ),
                ScoutingField(
                    id = "teamName",
                    label = "Team Name",
                    type = "text"
                ),
                ScoutingField(
                    id = "driveTrain",
                    label = "Drive Train",
                    type = "select",
                    options = listOf(
                        ScoutingOption("Swerve", "swerve"),
                        ScoutingOption("Tank", "tank"),
                        ScoutingOption("Mecanum", "mecanum"),
                        ScoutingOption("Other", "other")
                    )
                ),
                ScoutingField(
                    id = "robotWeight",
                    label = "Robot Weight",
                    type = "number",
                    min = 0,
                    max = 150
                ),
                ScoutingField(
                    id = "sectionCapabilities",
                    label = "Capabilities",
                    type = "section"
                ),
                ScoutingField(
                    id = "hasAuto",
                    label = "Has Autonomous",
                    type = "checkbox"
                ),
                ScoutingField(
                    id = "spareBatteries",
                    label = "Spare Batteries",
                    type = "counter",
                    min = 0,
                    step = 1
                ),
                ScoutingField(
                    id = "pitNotes",
                    label = "Pit Notes",
                    type = "textarea"
                )
            ),
            analytics = listOf(
                AnalyticsWidget(
                    id = "pitEntryCount",
                    title = "Pit Entries Collected",
                    type = "count"
                )
            )
        )
    }

    private fun defaultQualitativeConfig(): ScoutingConfig {
        return ScoutingConfig(
            version = 1,
            title = "ObsidianScout Qualitative Scouting",
            fields = listOf(
                ScoutingField(
                    id = "sectionObservations",
                    label = "Observations",
                    type = "section"
                ),
                ScoutingField(
                    id = "overallRating",
                    label = "Overall Rating",
                    type = "rating",
                    min = 1,
                    max = 5
                ),
                ScoutingField(
                    id = "driveNotes",
                    label = "Drive Notes",
                    type = "textarea"
                ),
                ScoutingField(
                    id = "autoNotes",
                    label = "Auto Notes",
                    type = "textarea"
                ),
                ScoutingField(
                    id = "teleopNotes",
                    label = "Teleop Notes",
                    type = "textarea"
                ),
                ScoutingField(
                    id = "endgameNotes",
                    label = "Endgame Notes",
                    type = "textarea"
                ),
                ScoutingField(
                    id = "recommendation",
                    label = "Recommendation",
                    type = "textarea"
                )
            ),
            analytics = emptyList()
        )
    }

    private fun extractStringLabel(labelElement: JsonElement?): String {
        if (labelElement == null) return ""
        if (labelElement is JsonPrimitive) {
            return labelElement.content
        }
        if (labelElement is JsonObject) {
            val enLabel = labelElement["en"]
            if (enLabel is JsonPrimitive) {
                return enLabel.content
            }
            for (value in labelElement.values) {
                if (value is JsonPrimitive) {
                    return value.content
                }
            }
        }
        return ""
    }

    private fun normalizeConfigJson(text: String): String {
        val element = JsonSupport.json.parseToJsonElement(text)
        val obj = element as? JsonObject ?: return text
        val fields = obj["fields"] as? JsonArray ?: return text
        var anyFieldChanged = false
        val normalizedFields = fields.map { fieldElement ->
            val fieldObj = fieldElement as? JsonObject ?: return@map fieldElement

            val originalLabel = fieldObj["label"]
            val normalizedLabelStr = extractStringLabel(originalLabel)
            val labelChanged = originalLabel !is JsonPrimitive || originalLabel.content != normalizedLabelStr

            val options = fieldObj["options"] as? JsonArray
            var optionsChanged = false
            val transformedOptions = options?.map { option ->
                when (option) {
                    is JsonPrimitive -> {
                        optionsChanged = true
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
                        val originalOptLabel = option["label"]
                        val normalizedOptLabelStr = extractStringLabel(originalOptLabel)
                        val optLabelChanged = originalOptLabel !is JsonPrimitive || originalOptLabel.content != normalizedOptLabelStr

                        val value = (option["value"] as? JsonPrimitive)?.content
                        val pointsRaw = (option["points"] as? JsonPrimitive)?.content
                        val points = pointsRaw?.toDoubleOrNull() ?: 0.0

                        val finalLabel = if (normalizedOptLabelStr.isNotEmpty()) normalizedOptLabelStr else (value ?: "")
                        val finalValue = value ?: normalizedOptLabelStr

                        val needsUpdate = optLabelChanged || value == null || option["points"] == null
                        if (needsUpdate) {
                            optionsChanged = true
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

            val fieldChanged = labelChanged || optionsChanged
            if (fieldChanged) {
                anyFieldChanged = true
                val updatedFieldMap = fieldObj.toMutableMap()
                updatedFieldMap["label"] = JsonPrimitive(normalizedLabelStr)
                if (transformedOptions != null) {
                    updatedFieldMap["options"] = JsonArray(transformedOptions)
                }
                JsonObject(updatedFieldMap)
            } else {
                fieldElement
            }
        }

        if (!anyFieldChanged) {
            return text
        }

        val normalized = JsonObject(obj + ("fields" to JsonArray(normalizedFields)))
        return JsonSupport.json.encodeToString(JsonElement.serializer(), normalized)
    }
}
