package com.obsidianscout.config

import com.obsidianscout.db.ConfigRevisions
import com.obsidianscout.db.PitScoutingEntries
import com.obsidianscout.db.QualitativeScoutingEntries
import com.obsidianscout.db.ScoutingEntries
import com.obsidianscout.routes.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

object ConfigMigrationService {

    private val METADATA_KEYS = setOf(
        "eventKey", "matchKey", "matchNumber", "targetTeamNumber",
        "scouterName", "scouter", "scoutName", "alliance", "id",
        "ownerTeamNumber", "createdAt", "isPrescout", "hasDiscrepancy", "conflictingTeams"
    )

    fun getSchemaStatus(teamNumber: Int, program: String, kind: String): ConfigSchemaStatusResponse {
        val normalizedKind = normalizeKind(kind)
        val config = when (normalizedKind) {
            "game" -> ConfigService.getConfig(teamNumber, program, local = true)
            "pit" -> ConfigService.getPitConfig(teamNumber, program, local = true)
            "qual" -> ConfigService.getQualitativeConfig(teamNumber, program, local = true)
            else -> ConfigService.getConfig(teamNumber, program, local = true)
        }

        val configFieldIds = config.fields.filter { it.type != "section" }.map { it.id }.toSet()

        return transaction {
            val (rows, count) = when (normalizedKind) {
                "game" -> {
                    val query = ScoutingEntries.selectAll().where {
                        (ScoutingEntries.ownerTeamNumber eq teamNumber) and (ScoutingEntries.program eq program)
                    }
                    val c = query.count().toInt()
                    val r = query.limit(200).map { it[ScoutingEntries.dataJson] }
                    Pair(r, c)
                }
                "pit" -> {
                    val query = PitScoutingEntries.selectAll().where {
                        (PitScoutingEntries.ownerTeamNumber eq teamNumber) and (PitScoutingEntries.program eq program)
                    }
                    val c = query.count().toInt()
                    val r = query.limit(200).map { it[PitScoutingEntries.dataJson] }
                    Pair(r, c)
                }
                "qual" -> {
                    val query = QualitativeScoutingEntries.selectAll().where {
                        (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and (QualitativeScoutingEntries.program eq program)
                    }
                    val c = query.count().toInt()
                    val r = query.limit(200).map { it[QualitativeScoutingEntries.dataJson] }
                    Pair(r, c)
                }
                else -> Pair(emptyList(), 0)
            }

            val dataKeys = mutableSetOf<String>()
            for (jsonText in rows) {
                try {
                    val obj = JsonSupport.json.parseToJsonElement(jsonText).jsonObject
                    obj.keys.filter { it !in METADATA_KEYS }.forEach { dataKeys.add(it) }
                } catch (_: Exception) {}
            }

            val unmatchedKeys = (dataKeys - configFieldIds).sorted()
            val newConfigKeys = (configFieldIds - dataKeys).sorted()

            ConfigSchemaStatusResponse(
                configKind = normalizedKind,
                entryCount = count,
                configVersion = config.version,
                configFields = config.fields.filter { it.type != "section" },
                dataKeys = dataKeys.sorted(),
                unmatchedDataKeys = unmatchedKeys,
                newConfigKeys = newConfigKeys
            )
        }
    }

    fun previewMigration(teamNumber: Int, program: String, request: ConfigMigrationRequest): ConfigMigrationPreviewResponse {
        val normalizedKind = normalizeKind(request.configKind)

        return transaction {
            val samples = mutableListOf<ConfigMigrationSample>()
            var totalCount = 0

            when (normalizedKind) {
                "game" -> {
                    val query = ScoutingEntries.selectAll().where {
                        (ScoutingEntries.ownerTeamNumber eq teamNumber) and (ScoutingEntries.program eq program)
                    }
                    totalCount = query.count().toInt()
                    query.limit(5).forEach { row ->
                        val id = row[ScoutingEntries.id].value.toString()
                        val before = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                        val after = transformEntryData(before, request.mappings, request.defaultValues)
                        samples.add(ConfigMigrationSample(id = id, before = before, after = after))
                    }
                }
                "pit" -> {
                    val query = PitScoutingEntries.selectAll().where {
                        (PitScoutingEntries.ownerTeamNumber eq teamNumber) and (PitScoutingEntries.program eq program)
                    }
                    totalCount = query.count().toInt()
                    query.limit(5).forEach { row ->
                        val id = row[PitScoutingEntries.id].value.toString()
                        val before = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                        val after = transformEntryData(before, request.mappings, request.defaultValues)
                        samples.add(ConfigMigrationSample(id = id, before = before, after = after))
                    }
                }
                "qual" -> {
                    val query = QualitativeScoutingEntries.selectAll().where {
                        (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and (QualitativeScoutingEntries.program eq program)
                    }
                    totalCount = query.count().toInt()
                    query.limit(5).forEach { row ->
                        val id = row[QualitativeScoutingEntries.id].value.toString()
                        val before = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                        val after = transformEntryData(before, request.mappings, request.defaultValues)
                        samples.add(ConfigMigrationSample(id = id, before = before, after = after))
                    }
                }
            }

            ConfigMigrationPreviewResponse(
                sampleEntries = samples,
                totalAffectedCount = totalCount
            )
        }
    }

    fun applyMigration(teamNumber: Int, program: String, request: ConfigMigrationRequest): ConfigMigrationResult {
        val normalizedKind = normalizeKind(request.configKind)

        return transaction {
            var updatedCount = 0

            when (normalizedKind) {
                "game" -> {
                    val entries = ScoutingEntries.selectAll().where {
                        (ScoutingEntries.ownerTeamNumber eq teamNumber) and (ScoutingEntries.program eq program)
                    }.toList()

                    for (row in entries) {
                        val id = row[ScoutingEntries.id].value
                        val before = JsonSupport.json.parseToJsonElement(row[ScoutingEntries.dataJson]).jsonObject
                        val after = transformEntryData(before, request.mappings, request.defaultValues)
                        val afterJson = JsonSupport.json.encodeToString(JsonElement.serializer(), after)
                        ScoutingEntries.update({ ScoutingEntries.id eq id }) {
                            it[dataJson] = afterJson
                        }
                        updatedCount++
                    }
                }
                "pit" -> {
                    val entries = PitScoutingEntries.selectAll().where {
                        (PitScoutingEntries.ownerTeamNumber eq teamNumber) and (PitScoutingEntries.program eq program)
                    }.toList()

                    for (row in entries) {
                        val id = row[PitScoutingEntries.id].value
                        val before = JsonSupport.json.parseToJsonElement(row[PitScoutingEntries.dataJson]).jsonObject
                        val after = transformEntryData(before, request.mappings, request.defaultValues)
                        val afterJson = JsonSupport.json.encodeToString(JsonElement.serializer(), after)
                        PitScoutingEntries.update({ PitScoutingEntries.id eq id }) {
                            it[dataJson] = afterJson
                        }
                        updatedCount++
                    }
                }
                "qual" -> {
                    val entries = QualitativeScoutingEntries.selectAll().where {
                        (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and (QualitativeScoutingEntries.program eq program)
                    }.toList()

                    for (row in entries) {
                        val id = row[QualitativeScoutingEntries.id].value
                        val before = JsonSupport.json.parseToJsonElement(row[QualitativeScoutingEntries.dataJson]).jsonObject
                        val after = transformEntryData(before, request.mappings, request.defaultValues)
                        val afterJson = JsonSupport.json.encodeToString(JsonElement.serializer(), after)
                        QualitativeScoutingEntries.update({ QualitativeScoutingEntries.id eq id }) {
                            it[dataJson] = afterJson
                        }
                        updatedCount++
                    }
                }
            }

            ConfigMigrationResult(
                success = true,
                count = updatedCount,
                message = "Successfully migrated $updatedCount entries to the new format."
            )
        }
    }

    fun detectFieldChanges(oldConfig: ScoutingConfig?, newConfig: ScoutingConfig, teamNumber: Int, program: String, kind: String): Pair<Boolean, List<String>> {
        if (oldConfig == null) {
            return Pair(false, emptyList())
        }

        val oldFieldMap = oldConfig.fields.filter { it.type != "section" }.associateBy { it.id }
        val newFieldMap = newConfig.fields.filter { it.type != "section" }.associateBy { it.id }

        val changedFields = mutableListOf<String>()

        // Check for removed or renamed fields
        for ((oldId, _) in oldFieldMap) {
            if (!newFieldMap.containsKey(oldId)) {
                changedFields.add("Removed/Renamed: $oldId")
            }
        }

        // Check for added fields
        for ((newId, _) in newFieldMap) {
            if (!oldFieldMap.containsKey(newId)) {
                changedFields.add("Added: $newId")
            }
        }

        // Check for modified field types or option value changes
        for ((id, oldField) in oldFieldMap) {
            val newField = newFieldMap[id] ?: continue
            if (oldField.type != newField.type) {
                changedFields.add("Type changed: $id (${oldField.type} -> ${newField.type})")
            } else if (oldField.options.map { it.value } != newField.options.map { it.value }) {
                changedFields.add("Options changed: $id")
            }
        }

        val hasChanges = changedFields.isNotEmpty()
        return Pair(hasChanges, changedFields)
    }

    fun countExistingEntries(teamNumber: Int, program: String, kind: String): Int {
        val normalizedKind = normalizeKind(kind)
        return transaction {
            when (normalizedKind) {
                "game" -> ScoutingEntries.selectAll().where {
                    (ScoutingEntries.ownerTeamNumber eq teamNumber) and (ScoutingEntries.program eq program)
                }.count().toInt()
                "pit" -> PitScoutingEntries.selectAll().where {
                    (PitScoutingEntries.ownerTeamNumber eq teamNumber) and (PitScoutingEntries.program eq program)
                }.count().toInt()
                "qual" -> QualitativeScoutingEntries.selectAll().where {
                    (QualitativeScoutingEntries.ownerTeamNumber eq teamNumber) and (QualitativeScoutingEntries.program eq program)
                }.count().toInt()
                else -> 0
            }
        }
    }

    private fun transformEntryData(
        data: JsonObject,
        mappings: List<FieldMappingDTO>,
        defaultValues: Map<String, JsonElement>
    ): JsonObject {
        val resultMap = data.toMutableMap()

        for (m in mappings) {
            val oldKey = m.oldKey
            val oldVal = resultMap.remove(oldKey) ?: continue

            when (m.action.lowercase()) {
                "delete" -> {
                    // Already removed from resultMap
                }
                "keep" -> {
                    resultMap[oldKey] = oldVal
                }
                "map" -> {
                    val targetKey = if (!m.newKey.isNullOrBlank()) m.newKey else oldKey
                    var finalVal = oldVal

                    if (m.valueMap != null && oldVal is JsonPrimitive) {
                        val str = oldVal.content
                        val mappedStr = m.valueMap[str] ?: str
                        finalVal = JsonPrimitive(mappedStr)
                    }

                    resultMap[targetKey] = finalVal
                }
                else -> {
                    resultMap[oldKey] = oldVal
                }
            }
        }

        for ((newKey, defVal) in defaultValues) {
            if (!resultMap.containsKey(newKey)) {
                resultMap[newKey] = defVal
            }
        }

        return JsonObject(resultMap)
    }

    fun saveRevision(
        teamNumber: Int,
        program: String,
        kind: String,
        config: ScoutingConfig,
        changeSummary: String,
        savedByUsername: String
    ) {
        val normalizedKind = normalizeKind(kind)
        transaction {
            ConfigRevisions.insert {
                it[ConfigRevisions.teamNumber] = teamNumber
                it[ConfigRevisions.program] = program
                it[ConfigRevisions.configKind] = normalizedKind
                it[ConfigRevisions.version] = config.version
                it[ConfigRevisions.title] = config.title
                it[ConfigRevisions.configJson] = JsonSupport.json.encodeToString(ScoutingConfig.serializer(), config)
                it[ConfigRevisions.changeSummary] = changeSummary
                it[ConfigRevisions.savedByUsername] = savedByUsername
                it[ConfigRevisions.createdAt] = Instant.now()
            }
        }
    }

    fun listRevisions(teamNumber: Int, program: String, kind: String): List<ConfigRevisionDTO> {
        val normalizedKind = normalizeKind(kind)
        return transaction {
            ConfigRevisions.selectAll().where {
                (ConfigRevisions.teamNumber eq teamNumber) and
                (ConfigRevisions.program eq program) and
                (ConfigRevisions.configKind eq normalizedKind)
            }.orderBy(ConfigRevisions.createdAt, SortOrder.DESC).limit(50).map { row ->
                val json = row[ConfigRevisions.configJson]
                val fieldCount = try {
                    val parsed = JsonSupport.json.decodeFromString(ScoutingConfig.serializer(), json)
                    parsed.fields.filter { it.type != "section" }.size
                } catch (_: Exception) { 0 }

                ConfigRevisionDTO(
                    id = row[ConfigRevisions.id].value.toString(),
                    teamNumber = row[ConfigRevisions.teamNumber],
                    program = row[ConfigRevisions.program],
                    configKind = row[ConfigRevisions.configKind],
                    version = row[ConfigRevisions.version],
                    title = row[ConfigRevisions.title],
                    changeSummary = row[ConfigRevisions.changeSummary],
                    savedByUsername = row[ConfigRevisions.savedByUsername],
                    createdAt = row[ConfigRevisions.createdAt].toString(),
                    fieldCount = fieldCount
                )
            }
        }
    }

    fun getRevision(id: String, teamNumber: Int, program: String): ConfigRevisionDetailDTO? {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
        return transaction {
            ConfigRevisions.selectAll().where {
                (ConfigRevisions.id eq uuid) and
                (ConfigRevisions.teamNumber eq teamNumber) and
                (ConfigRevisions.program eq program)
            }.firstOrNull()?.let { row ->
                ConfigRevisionDetailDTO(
                    id = row[ConfigRevisions.id].value.toString(),
                    teamNumber = row[ConfigRevisions.teamNumber],
                    program = row[ConfigRevisions.program],
                    configKind = row[ConfigRevisions.configKind],
                    version = row[ConfigRevisions.version],
                    title = row[ConfigRevisions.title],
                    configJson = row[ConfigRevisions.configJson],
                    changeSummary = row[ConfigRevisions.changeSummary],
                    savedByUsername = row[ConfigRevisions.savedByUsername],
                    createdAt = row[ConfigRevisions.createdAt].toString()
                )
            }
        }
    }

    fun restoreRevision(id: String, teamNumber: Int, program: String, restoredByUsername: String): ConfigUpdateResponse {
        val rev = getRevision(id, teamNumber, program) ?: throw IllegalArgumentException("Revision not found")
        val oldConfig = when (rev.configKind) {
            "game" -> runCatching { ConfigService.getConfig(teamNumber, program, local = true) }.getOrNull()
            "pit" -> runCatching { ConfigService.getPitConfig(teamNumber, program, local = true) }.getOrNull()
            "qual" -> runCatching { ConfigService.getQualitativeConfig(teamNumber, program, local = true) }.getOrNull()
            else -> null
        }

        val updated = when (rev.configKind) {
            "game" -> ConfigService.updateConfig(teamNumber, program, rev.configJson)
            "pit" -> ConfigService.updatePitConfig(teamNumber, program, rev.configJson)
            "qual" -> ConfigService.updateQualitativeConfig(teamNumber, program, rev.configJson)
            else -> ConfigService.updateConfig(teamNumber, program, rev.configJson)
        }

        val (hasChanges, changedFields) = detectFieldChanges(oldConfig, updated, teamNumber, program, rev.configKind)
        val entryCount = countExistingEntries(teamNumber, program, rev.configKind)

        saveRevision(
            teamNumber = teamNumber,
            program = program,
            kind = rev.configKind,
            config = updated,
            changeSummary = "Restored from version ${rev.version} (${rev.createdAt})",
            savedByUsername = restoredByUsername
        )

        return ConfigUpdateResponse(
            config = updated,
            hasFieldChanges = hasChanges,
            changedFields = changedFields,
            entryCount = entryCount,
            configKind = rev.configKind
        )
    }

    private fun normalizeKind(kind: String): String {
        return when (kind.lowercase()) {
            "match", "game" -> "game"
            "pit" -> "pit"
            "qual", "qualitative" -> "qual"
            else -> "game"
        }
    }
}
