package com.obsidianscout.config

import com.obsidianscout.db.PitScoutingConfigs
import com.obsidianscout.db.ScoutingConfigs
import com.obsidianscout.db.QualitativeScoutingConfigs
import com.obsidianscout.db.DefaultConfigs
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.scouting.AllianceService
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import com.obsidianscout.db.readTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.deleteWhere
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Serializable
data class DefaultConfigDTO(
    val id: String? = null,
    val name: String,
    val program: String,
    val configType: String,
    val configJson: String,
    val isDefault: Boolean = false,
    val updatedAt: String? = null
)

@Serializable
data class ScoutingConfig(
    val version: Int = 1,
    val title: String = "ObsidianScout",
    val fields: List<ScoutingField> = emptyList(),
    val analytics: List<AnalyticsWidget> = emptyList(),
    @SerialName("tba_key") val tbaKey: String? = null,
    @SerialName("first_username") val firstUsername: String? = null,
    @SerialName("first_key") val firstKey: String? = null,
    @SerialName("event_code") val eventCode: String? = null,
    @SerialName("enable_robot_role_collection") val enableRobotRoleCollection: Boolean = false
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
    val doubleStep: Int? = null,
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

    fun getDefaultsDirectory(): java.nio.file.Path {
        val dir = listOf(
            Paths.get("config", "defaults"),
            Paths.get("Obsidianscout", "config", "defaults"),
            Paths.get("..", "config", "defaults")
        ).firstOrNull { Files.exists(it) } ?: Paths.get("config", "defaults")
        if (!Files.exists(dir)) {
            try { Files.createDirectories(dir) } catch (_: Exception) {}
        }
        return dir
    }

    fun ensureDefaultConfig() {
        val defaultsDir = getDefaultsDirectory()

        transaction {
            SchemaUtils.createMissingTablesAndColumns(DefaultConfigs, ScoutingConfigs, PitScoutingConfigs, QualitativeScoutingConfigs)

            // Auto-update default presets from files physically included in the update bundle (config/defaults/*.json)
            if (Files.exists(defaultsDir) && Files.isDirectory(defaultsDir)) {
                val diskFiles = try {
                    Files.list(defaultsDir).use { stream ->
                        stream.filter { it.toString().endsWith(".json") }.toList()
                    }
                } catch (_: Exception) {
                    emptyList()
                }

                diskFiles.forEach { filePath ->
                    try {
                        val fileName = filePath.fileName.toString()
                        val baseName = fileName.removeSuffix(".json")
                        val lastDash = baseName.lastIndexOf('-')
                        if (lastDash > 0) {
                            val presetName = baseName.substring(0, lastDash)
                            val type = baseName.substring(lastDash + 1).lowercase()
                            if (type == "match" || type == "pit" || type == "qualitative") {
                                val prog = if (presetName.startsWith("ftc", ignoreCase = true)) "FTC" else "FRC"
                                val fileJson = Files.readString(filePath)
                                val normalizedSourceJson = normalizeConfigJson(fileJson)

                                val existing = DefaultConfigs
                                    .selectAll().where { (DefaultConfigs.name eq presetName) and (DefaultConfigs.configType eq type) }
                                    .firstOrNull()

                                if (existing == null) {
                                    val isDef = presetName.endsWith("2026")
                                    DefaultConfigs.insert {
                                        it[name] = presetName
                                        it[program] = prog
                                        it[configType] = type
                                        it[configJson] = normalizedSourceJson
                                        it[isDefault] = isDef
                                        it[updatedAt] = Instant.now()
                                    }
                                    if (isDef) {
                                        syncActiveDefaultToTeamZero(prog, type, normalizedSourceJson)
                                    }
                                } else {
                                    val existingNormalized = normalizeConfigJson(existing[DefaultConfigs.configJson])
                                    if (existingNormalized != normalizedSourceJson) {
                                        DefaultConfigs.update({ DefaultConfigs.id eq existing[DefaultConfigs.id] }) {
                                            it[configJson] = normalizedSourceJson
                                            it[updatedAt] = Instant.now()
                                        }
                                        println("[ConfigService] Updated default config '$presetName' ($type) to latest from update bundle.")
                                        if (existing[DefaultConfigs.isDefault]) {
                                            syncActiveDefaultToTeamZero(prog, type, normalizedSourceJson)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[ConfigService] Warning reading bundle file $filePath: ${e.message}")
                    }
                }
            }

            // Ensure active defaults in DefaultConfigs are synced to team 0
            listOf("FRC", "FTC").forEach { prog ->
                listOf("match", "pit", "qualitative").forEach { type ->
                    val activeRow = DefaultConfigs
                        .selectAll().where { (DefaultConfigs.program eq prog) and (DefaultConfigs.configType eq type) and (DefaultConfigs.isDefault eq true) }
                        .firstOrNull()
                    if (activeRow != null) {
                        val activeJson = activeRow[DefaultConfigs.configJson]
                        syncActiveDefaultToTeamZero(prog, type, activeJson)
                    }
                }
            }

            // Migrate all existing general phases across all server config tables to teleop
            try {
                ScoutingConfigs.selectAll().forEach { row ->
                    val oldJson = row[ScoutingConfigs.configJson]
                    val normalized = normalizeConfigJson(oldJson)
                    if (normalized != oldJson) {
                        ScoutingConfigs.update({ ScoutingConfigs.id eq row[ScoutingConfigs.id] }) {
                            it[configJson] = normalized
                            it[updatedAt] = Instant.now()
                        }
                    }
                }
            } catch (ignored: Exception) {}

            try {
                PitScoutingConfigs.selectAll().forEach { row ->
                    val oldJson = row[PitScoutingConfigs.configJson]
                    val normalized = normalizeConfigJson(oldJson)
                    if (normalized != oldJson) {
                        PitScoutingConfigs.update({ PitScoutingConfigs.id eq row[PitScoutingConfigs.id] }) {
                            it[configJson] = normalized
                            it[updatedAt] = Instant.now()
                        }
                    }
                }
            } catch (ignored: Exception) {}

            try {
                QualitativeScoutingConfigs.selectAll().forEach { row ->
                    val oldJson = row[QualitativeScoutingConfigs.configJson]
                    val normalized = normalizeConfigJson(oldJson)
                    if (normalized != oldJson) {
                        QualitativeScoutingConfigs.update({ QualitativeScoutingConfigs.id eq row[QualitativeScoutingConfigs.id] }) {
                            it[configJson] = normalized
                            it[updatedAt] = Instant.now()
                        }
                    }
                }
            } catch (ignored: Exception) {}

            try {
                DefaultConfigs.selectAll().forEach { row ->
                    val oldJson = row[DefaultConfigs.configJson]
                    val normalized = normalizeConfigJson(oldJson)
                    if (normalized != oldJson) {
                        DefaultConfigs.update({ DefaultConfigs.id eq row[DefaultConfigs.id] }) {
                            it[configJson] = normalized
                            it[updatedAt] = Instant.now()
                        }
                    }
                }
            } catch (ignored: Exception) {}

            try {
                ScoutingAlliances.selectAll().forEach { row ->
                    val matchOld = row[ScoutingAlliances.matchConfigJson]
                    val pitOld = row[ScoutingAlliances.pitConfigJson]
                    val qualOld = row[ScoutingAlliances.qualitativeConfigJson]
                    val matchNorm = matchOld?.let { normalizeConfigJson(it) }
                    val pitNorm = pitOld?.let { normalizeConfigJson(it) }
                    val qualNorm = qualOld?.let { normalizeConfigJson(it) }
                    if (matchNorm != matchOld || pitNorm != pitOld || qualNorm != qualOld) {
                        ScoutingAlliances.update({ ScoutingAlliances.id eq row[ScoutingAlliances.id] }) {
                            if (matchNorm != null) it[matchConfigJson] = matchNorm
                            if (pitNorm != null) it[pitConfigJson] = pitNorm
                            if (qualNorm != null) it[qualitativeConfigJson] = qualNorm
                            it[updatedAt] = Instant.now()
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        // Clone/sync all default configs from the database onto the local server disk
        syncFromDatabaseToLocalDisk()
    }

    fun getDefaultConfigs(program: String, configType: String? = null): List<DefaultConfigDTO> {
        return readTransaction {
            var query = DefaultConfigs.selectAll().where { DefaultConfigs.program eq program }
            if (!configType.isNullOrBlank()) {
                val filterType = if (configType.equals("game", ignoreCase = true)) "match" else if (configType.equals("qual", ignoreCase = true)) "qualitative" else configType.lowercase()
                query = DefaultConfigs.selectAll().where { (DefaultConfigs.program eq program) and (DefaultConfigs.configType eq filterType) }
            }
            query.orderBy(DefaultConfigs.name to SortOrder.ASC).map { row ->
                DefaultConfigDTO(
                    id = row[DefaultConfigs.id].value.toString(),
                    name = row[DefaultConfigs.name],
                    program = row[DefaultConfigs.program],
                    configType = row[DefaultConfigs.configType],
                    configJson = row[DefaultConfigs.configJson],
                    isDefault = row[DefaultConfigs.isDefault],
                    updatedAt = row[DefaultConfigs.updatedAt].toString()
                )
            }
        }
    }

    fun applyDefaultConfig(teamNumber: Int, program: String, configType: String, presetName: String): ScoutingConfig {
        val targetType = when (configType.lowercase()) {
            "game", "match" -> "match"
            "qual", "qualitative" -> "qualitative"
            else -> configType.lowercase()
        }
        val defaultRow = readTransaction {
            DefaultConfigs
                .selectAll().where { (DefaultConfigs.name eq presetName) and (DefaultConfigs.configType eq targetType) }
                .firstOrNull()
        } ?: throw IllegalArgumentException("Default configuration preset '$presetName' of type '$targetType' not found")

        if (defaultRow[DefaultConfigs.program] != program) {
            throw IllegalArgumentException("Cannot apply preset '$presetName' (${defaultRow[DefaultConfigs.program]}) to team in program $program")
        }

        val jsonContent = defaultRow[DefaultConfigs.configJson]

        return when (targetType) {
            "match" -> updateConfig(teamNumber, program, jsonContent)
            "pit" -> updatePitConfig(teamNumber, program, jsonContent)
            "qualitative" -> updateQualitativeConfig(teamNumber, program, jsonContent)
            else -> throw IllegalArgumentException("Unknown config type: $configType")
        }
    }

    fun resetToDefaultConfig(teamNumber: Int, program: String, configType: String): ScoutingConfig {
        val targetType = when (configType.lowercase()) {
            "game", "match" -> "match"
            "qual", "qualitative" -> "qualitative"
            else -> configType.lowercase()
        }
        val defaultJson = readTransaction {
            DefaultConfigs
                .selectAll().where { (DefaultConfigs.program eq program) and (DefaultConfigs.configType eq targetType) and (DefaultConfigs.isDefault eq true) }
                .firstOrNull()?.get(DefaultConfigs.configJson)
        } ?: when (targetType) {
            "match" -> getConfigJson(0, program, local = true)
            "pit" -> getPitConfigJson(0, program, local = true)
            "qualitative" -> getQualitativeConfigJson(0, program, local = true)
            else -> throw IllegalArgumentException("Unknown config type: $configType")
        }

        return when (targetType) {
            "match" -> updateConfig(teamNumber, program, defaultJson)
            "pit" -> updatePitConfig(teamNumber, program, defaultJson)
            "qualitative" -> updateQualitativeConfig(teamNumber, program, defaultJson)
            else -> throw IllegalArgumentException("Unknown config type: $configType")
        }
    }

    fun getAllDefaultConfigs(): List<DefaultConfigDTO> {
        return readTransaction {
            DefaultConfigs.selectAll().orderBy(DefaultConfigs.program to SortOrder.ASC, DefaultConfigs.name to SortOrder.ASC).map { row ->
                DefaultConfigDTO(
                    id = row[DefaultConfigs.id].value.toString(),
                    name = row[DefaultConfigs.name],
                    program = row[DefaultConfigs.program],
                    configType = row[DefaultConfigs.configType],
                    configJson = row[DefaultConfigs.configJson],
                    isDefault = row[DefaultConfigs.isDefault],
                    updatedAt = row[DefaultConfigs.updatedAt].toString()
                )
            }
        }
    }

    private fun savePresetFileOnDisk(name: String, configType: String, jsonText: String, isDefault: Boolean = false, program: String = "FRC") {
        try {
            val defaultsDir = getDefaultsDirectory()
            val targetType = when (configType.lowercase()) {
                "game", "match" -> "match"
                "qual", "qualitative" -> "qualitative"
                else -> configType.lowercase()
            }
            val filePath = defaultsDir.resolve("$name-$targetType.json")
            Files.writeString(filePath, jsonText.trim() + "\n")

            if (isDefault && program.equals("FRC", ignoreCase = true)) {
                val primaryFile = when (targetType) {
                    "match" -> defaultConfigPath
                    "pit" -> defaultPitConfigPath
                    "qualitative" -> defaultQualitativeConfigPath
                    else -> null
                }
                if (primaryFile != null) {
                    primaryFile.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                    Files.writeString(primaryFile, jsonText.trim() + "\n")
                }
            }
        } catch (e: Exception) {
            println("Warning: Could not save preset file to disk: ${e.message}")
        }
    }

    /**
     * Reads all default configs from the database and clones any added or modified presets to local disk.
     * Used on initial boot and in multi-server cluster replication.
     */
    fun syncFromDatabaseToLocalDisk() {
        try {
            val defaultsDir = getDefaultsDirectory()
            val dbPresets = readTransaction {
                DefaultConfigs.selectAll().map { row ->
                    Triple(
                        "${row[DefaultConfigs.name]}-${row[DefaultConfigs.configType]}.json",
                        row[DefaultConfigs.configJson],
                        row[DefaultConfigs.isDefault] to (row[DefaultConfigs.program] to row[DefaultConfigs.configType])
                    )
                }
            }

            dbPresets.forEach { (fileName, jsonContent, meta) ->
                val filePath = defaultsDir.resolve(fileName)
                val needsWrite = if (!Files.exists(filePath)) {
                    true
                } else {
                    try {
                        Files.readString(filePath).trim() != jsonContent.trim()
                    } catch (_: Exception) {
                        true
                    }
                }

                if (needsWrite) {
                    Files.writeString(filePath, jsonContent.trim() + "\n")
                    println("[ClusterConfigSync] Cloned/Updated default config '$fileName' to local disk.")
                }

                val (isDefault, progType) = meta
                val (prog, type) = progType
                if (isDefault && prog.equals("FRC", ignoreCase = true)) {
                    val primaryFile = when (type.lowercase()) {
                        "match" -> defaultConfigPath
                        "pit" -> defaultPitConfigPath
                        "qualitative" -> defaultQualitativeConfigPath
                        else -> null
                    }
                    if (primaryFile != null) {
                        val primaryNeedsWrite = if (!Files.exists(primaryFile)) {
                            true
                        } else {
                            try {
                                Files.readString(primaryFile).trim() != jsonContent.trim()
                            } catch (_: Exception) {
                                true
                            }
                        }
                        if (primaryNeedsWrite) {
                            try {
                                primaryFile.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                                Files.writeString(primaryFile, jsonContent.trim() + "\n")
                                println("[ClusterConfigSync] Synced active default config '${primaryFile.fileName}' to local disk.")
                            } catch (e: Exception) {
                                println("Warning: Could not sync active default config to $primaryFile: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[ClusterConfigSync] Warning: Failed to sync default configs from database to local disk: ${e.message}")
        }
    }

    @Volatile
    private var clusterSyncJob: Job? = null

    /**
     * Starts background polling of CockroachDB/PostgreSQL default_configs table for multi-server clusters.
     * Automatically clones new or changed default configs across all cluster nodes to their local disk.
     */
    fun startBackgroundClusterSync(intervalSeconds: Long = 30) {
        if (clusterSyncJob?.isActive == true) return

        clusterSyncJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            println("[ClusterConfigSync] Background cluster default configs sync monitor started (polling every $intervalSeconds seconds)...")
            while (isActive) {
                try {
                    delay(intervalSeconds * 1000)
                    syncFromDatabaseToLocalDisk()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    // Suppress and retry next cycle
                }
            }
        }
    }

    fun stopBackgroundClusterSync() {
        clusterSyncJob?.cancel()
        clusterSyncJob = null
    }

    private fun syncActiveDefaultToTeamZero(program: String, configType: String, json: String) {
        val targetType = when (configType.lowercase()) {
            "game", "match" -> "match"
            "qual", "qualitative" -> "qualitative"
            else -> configType.lowercase()
        }
        when (targetType) {
            "match" -> {
                val existing = ScoutingConfigs.selectAll().where { (ScoutingConfigs.teamNumber eq 0) and (ScoutingConfigs.program eq program) }.firstOrNull()
                if (existing == null) {
                    ScoutingConfigs.insert {
                        it[teamNumber] = 0
                        it[ScoutingConfigs.program] = program
                        it[configJson] = json
                        it[updatedAt] = Instant.now()
                    }
                } else {
                    ScoutingConfigs.update({ (ScoutingConfigs.teamNumber eq 0) and (ScoutingConfigs.program eq program) }) {
                        it[configJson] = json
                        it[updatedAt] = Instant.now()
                    }
                }
            }
            "pit" -> {
                val existing = PitScoutingConfigs.selectAll().where { (PitScoutingConfigs.teamNumber eq 0) and (PitScoutingConfigs.program eq program) }.firstOrNull()
                if (existing == null) {
                    PitScoutingConfigs.insert {
                        it[teamNumber] = 0
                        it[PitScoutingConfigs.program] = program
                        it[configJson] = json
                        it[updatedAt] = Instant.now()
                    }
                } else {
                    PitScoutingConfigs.update({ (PitScoutingConfigs.teamNumber eq 0) and (PitScoutingConfigs.program eq program) }) {
                        it[configJson] = json
                        it[updatedAt] = Instant.now()
                    }
                }
            }
            "qualitative" -> {
                val existing = QualitativeScoutingConfigs.selectAll().where { (QualitativeScoutingConfigs.teamNumber eq 0) and (QualitativeScoutingConfigs.program eq program) }.firstOrNull()
                if (existing == null) {
                    QualitativeScoutingConfigs.insert {
                        it[teamNumber] = 0
                        it[QualitativeScoutingConfigs.program] = program
                        it[configJson] = json
                        it[updatedAt] = Instant.now()
                    }
                } else {
                    QualitativeScoutingConfigs.update({ (QualitativeScoutingConfigs.teamNumber eq 0) and (QualitativeScoutingConfigs.program eq program) }) {
                        it[configJson] = json
                        it[updatedAt] = Instant.now()
                    }
                }
            }
        }
    }

    fun createDefaultConfig(dto: DefaultConfigDTO): DefaultConfigDTO {
        val normalizedJson = normalizeConfigJson(dto.configJson)
        val newId = transaction {
            val existingConflict = DefaultConfigs.selectAll()
                .where { (DefaultConfigs.name eq dto.name) and (DefaultConfigs.program eq dto.program) and (DefaultConfigs.configType eq dto.configType) }
                .firstOrNull()
            if (existingConflict != null) {
                throw com.obsidianscout.auth.ApiException(
                    io.ktor.http.HttpStatusCode.Conflict,
                    "A default config preset named '${dto.name}' already exists for ${dto.program} ${dto.configType}"
                )
            }
            if (dto.isDefault) {
                DefaultConfigs.update({ (DefaultConfigs.program eq dto.program) and (DefaultConfigs.configType eq dto.configType) }) {
                    it[isDefault] = false
                }
                syncActiveDefaultToTeamZero(dto.program, dto.configType, normalizedJson)
            }
            DefaultConfigs.insert {
                it[name] = dto.name
                it[program] = dto.program
                it[configType] = dto.configType
                it[configJson] = normalizedJson
                it[isDefault] = dto.isDefault
                it[updatedAt] = Instant.now()
            }[DefaultConfigs.id].value
        }
        savePresetFileOnDisk(dto.name, dto.configType, normalizedJson, dto.isDefault, dto.program)
        return dto.copy(id = newId.toString(), configJson = normalizedJson, updatedAt = Instant.now().toString())
    }

    fun updateDefaultConfig(id: String, dto: DefaultConfigDTO): DefaultConfigDTO {
        val normalizedJson = normalizeConfigJson(dto.configJson)
        val uuid = UUID.fromString(id)
        transaction {
            val existingConflict = DefaultConfigs.selectAll()
                .where { (DefaultConfigs.name eq dto.name) and (DefaultConfigs.program eq dto.program) and (DefaultConfigs.configType eq dto.configType) and (DefaultConfigs.id neq uuid) }
                .firstOrNull()
            if (existingConflict != null) {
                throw com.obsidianscout.auth.ApiException(
                    io.ktor.http.HttpStatusCode.Conflict,
                    "A default config preset named '${dto.name}' already exists for ${dto.program} ${dto.configType}"
                )
            }
            if (dto.isDefault) {
                DefaultConfigs.update({ (DefaultConfigs.program eq dto.program) and (DefaultConfigs.configType eq dto.configType) }) {
                    it[isDefault] = false
                }
                syncActiveDefaultToTeamZero(dto.program, dto.configType, normalizedJson)
            }
            DefaultConfigs.update({ DefaultConfigs.id eq uuid }) {
                it[name] = dto.name
                it[program] = dto.program
                it[configType] = dto.configType
                it[configJson] = normalizedJson
                it[isDefault] = dto.isDefault
                it[updatedAt] = Instant.now()
            }
        }
        savePresetFileOnDisk(dto.name, dto.configType, normalizedJson, dto.isDefault, dto.program)
        return dto.copy(id = id, configJson = normalizedJson, updatedAt = Instant.now().toString())
    }

    fun deleteDefaultConfig(id: String): Boolean {
        val uuid = UUID.fromString(id)
        return transaction {
            val existing = DefaultConfigs.selectAll().where { DefaultConfigs.id eq uuid }.firstOrNull()
            if (existing != null) {
                val name = existing[DefaultConfigs.name]
                val type = existing[DefaultConfigs.configType]
                try {
                    val filePath = getDefaultsDirectory().resolve("$name-$type.json")
                    Files.deleteIfExists(filePath)
                } catch (_: Exception) {}
            }
            DefaultConfigs.deleteWhere { DefaultConfigs.id eq uuid } > 0
        }
    }

    fun getConfigJson(teamNumber: Int, program: String = "FRC", local: Boolean = false): String {
        return try { readTransaction {
            if (!local) {
                val activeAllianceId = AllianceService.getActiveAllianceId(teamNumber, program)
                if (activeAllianceId != null) {
                    val allianceConfig = ScoutingAlliances
                        .selectAll().where { ScoutingAlliances.id eq activeAllianceId }
                        .firstOrNull()
                        ?.get(ScoutingAlliances.matchConfigJson)
                    if (!allianceConfig.isNullOrBlank()) {
                        return@readTransaction allianceConfig
                    }
                }
            }

            // Try team-specific config first
            val teamConfig = ScoutingConfigs
                .selectAll().where { (ScoutingConfigs.teamNumber eq teamNumber) and (ScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
                ?.get(ScoutingConfigs.configJson)

            if (teamConfig != null) {
                return@readTransaction teamConfig
            }

            // Fall back to active default preset if set
            val activeDefault = DefaultConfigs
                .selectAll().where { (DefaultConfigs.program eq program) and (DefaultConfigs.configType eq "match") and (DefaultConfigs.isDefault eq true) }
                .firstOrNull()
                ?.get(DefaultConfigs.configJson)
            if (activeDefault != null) {
                return@readTransaction activeDefault
            }

            // Fall back to team 0 (global default)
            ScoutingConfigs
                .selectAll().where { (ScoutingConfigs.teamNumber eq 0) and (ScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
                ?.get(ScoutingConfigs.configJson)
        } } catch (_: Throwable) { null } ?: loadDefaultConfigText(program)
    }

    fun getConfig(teamNumber: Int, program: String = "FRC", local: Boolean = false): ScoutingConfig {
        val jsonText = normalizeConfigJson(getConfigJson(teamNumber, program, local))
        return JsonSupport.json.decodeFromString(jsonText)
    }

    fun updateConfig(teamNumber: Int, program: String = "FRC", newJson: String): ScoutingConfig {
        val normalizedJson = normalizeConfigJson(newJson)
        val parsed = JsonSupport.json.decodeFromString<ScoutingConfig>(normalizedJson)
        transaction {

            val row = ScoutingConfigs
                .selectAll().where { (ScoutingConfigs.teamNumber eq teamNumber) and (ScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                ScoutingConfigs.insert {
                    it[ScoutingConfigs.teamNumber] = teamNumber
                    it[ScoutingConfigs.program] = program
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

    fun getPitConfigJson(teamNumber: Int, program: String = "FRC", local: Boolean = false): String {
        return try { readTransaction {
            if (!local) {
                val activeAllianceId = AllianceService.getActiveAllianceId(teamNumber, program)
                if (activeAllianceId != null) {
                    val allianceConfig = ScoutingAlliances
                        .selectAll().where { ScoutingAlliances.id eq activeAllianceId }
                        .firstOrNull()
                        ?.get(ScoutingAlliances.pitConfigJson)
                    if (!allianceConfig.isNullOrBlank()) {
                        return@readTransaction allianceConfig
                    }
                }
            }

            val teamConfig = PitScoutingConfigs
                .selectAll().where { (PitScoutingConfigs.teamNumber eq teamNumber) and (PitScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
                ?.get(PitScoutingConfigs.configJson)

            if (teamConfig != null) {
                return@readTransaction teamConfig
            }

            // Fall back to active default preset if set
            val activeDefault = DefaultConfigs
                .selectAll().where { (DefaultConfigs.program eq program) and (DefaultConfigs.configType eq "pit") and (DefaultConfigs.isDefault eq true) }
                .firstOrNull()
                ?.get(DefaultConfigs.configJson)
            if (activeDefault != null) {
                return@readTransaction activeDefault
            }

            PitScoutingConfigs
                .selectAll().where { (PitScoutingConfigs.teamNumber eq 0) and (PitScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
                ?.get(PitScoutingConfigs.configJson)
        } } catch (_: Throwable) { null } ?: loadDefaultPitConfigText(program)
    }

    fun getPitConfig(teamNumber: Int, program: String = "FRC", local: Boolean = false): ScoutingConfig {
        val jsonText = normalizeConfigJson(getPitConfigJson(teamNumber, program, local))
        return JsonSupport.json.decodeFromString(jsonText)
    }

    fun updatePitConfig(teamNumber: Int, program: String = "FRC", newJson: String): ScoutingConfig {
        val normalizedJson = normalizeConfigJson(newJson)
        val parsed = JsonSupport.json.decodeFromString<ScoutingConfig>(normalizedJson)
        transaction {

            val row = PitScoutingConfigs
                .selectAll().where { (PitScoutingConfigs.teamNumber eq teamNumber) and (PitScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                PitScoutingConfigs.insert {
                    it[PitScoutingConfigs.teamNumber] = teamNumber
                    it[PitScoutingConfigs.program] = program
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

    fun getQualitativeConfigJson(teamNumber: Int, program: String = "FRC", local: Boolean = false): String {
        return try { readTransaction {
            if (!local) {
                val activeAllianceId = AllianceService.getActiveAllianceId(teamNumber, program)
                if (activeAllianceId != null) {
                    val allianceConfig = ScoutingAlliances
                        .selectAll().where { ScoutingAlliances.id eq activeAllianceId }
                        .firstOrNull()
                        ?.get(ScoutingAlliances.qualitativeConfigJson)
                    if (!allianceConfig.isNullOrBlank()) {
                        return@readTransaction allianceConfig
                    }
                }
            }

            val teamConfig = QualitativeScoutingConfigs
                .selectAll().where { (QualitativeScoutingConfigs.teamNumber eq teamNumber) and (QualitativeScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
                ?.get(QualitativeScoutingConfigs.configJson)

            if (teamConfig != null) {
                return@readTransaction teamConfig
            }

            // Fall back to active default preset if set
            val activeDefault = DefaultConfigs
                .selectAll().where { (DefaultConfigs.program eq program) and (DefaultConfigs.configType eq "qualitative") and (DefaultConfigs.isDefault eq true) }
                .firstOrNull()
                ?.get(DefaultConfigs.configJson)
            if (activeDefault != null) {
                return@readTransaction activeDefault
            }

            QualitativeScoutingConfigs
                .selectAll().where { (QualitativeScoutingConfigs.teamNumber eq 0) and (QualitativeScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
                ?.get(QualitativeScoutingConfigs.configJson)
        } } catch (_: Throwable) { null } ?: loadDefaultQualitativeConfigText(program)
    }

    fun getQualitativeConfig(teamNumber: Int, program: String = "FRC", local: Boolean = false): ScoutingConfig {
        val jsonText = normalizeConfigJson(getQualitativeConfigJson(teamNumber, program, local))
        return JsonSupport.json.decodeFromString(jsonText)
    }

    fun updateQualitativeConfig(teamNumber: Int, program: String = "FRC", newJson: String): ScoutingConfig {
        val normalizedJson = normalizeConfigJson(newJson)
        val parsed = JsonSupport.json.decodeFromString<ScoutingConfig>(normalizedJson)
        transaction {

            val row = QualitativeScoutingConfigs
                .selectAll().where { (QualitativeScoutingConfigs.teamNumber eq teamNumber) and (QualitativeScoutingConfigs.program eq program) }
                .limit(1)
                .firstOrNull()
            if (row == null) {
                QualitativeScoutingConfigs.insert {
                    it[QualitativeScoutingConfigs.teamNumber] = teamNumber
                    it[QualitativeScoutingConfigs.program] = program
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

    private fun loadDefaultConfigText(program: String = "FRC"): String {
        return if (program == "FTC") {
            JsonSupport.json.encodeToString(defaultConfig().copy(title = "FTC 2026 Into The Deep Scouting"))
        } else if (Files.exists(defaultConfigPath)) {
            Files.readString(defaultConfigPath)
        } else {
            JsonSupport.json.encodeToString(defaultConfig())
        }
    }

    private fun loadDefaultPitConfigText(program: String = "FRC"): String {
        return if (program == "FTC") {
            JsonSupport.json.encodeToString(defaultPitConfig().copy(title = "FTC 2026 Into The Deep Pit Scouting"))
        } else if (Files.exists(defaultPitConfigPath)) {
            Files.readString(defaultPitConfigPath)
        } else {
            JsonSupport.json.encodeToString(defaultPitConfig())
        }
    }

    private fun loadDefaultQualitativeConfigText(program: String = "FRC"): String {
        return if (program == "FTC") {
            JsonSupport.json.encodeToString(defaultQualitativeConfig().copy(title = "FTC 2026 Into The Deep Qualitative Scouting"))
        } else if (Files.exists(defaultQualitativeConfigPath)) {
            Files.readString(defaultQualitativeConfigPath)
        } else {
            JsonSupport.json.encodeToString(defaultQualitativeConfig())
        }
    }

    internal fun defaultConfig(): ScoutingConfig {
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

    internal fun defaultPitConfig(): ScoutingConfig {
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

    internal fun defaultQualitativeConfig(): ScoutingConfig {
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

            // Phase migration: general or blank -> teleop
            val originalPhase = (fieldObj["phase"] as? JsonPrimitive)?.content
            var phaseChanged = false
            var finalPhase = originalPhase
            if (originalPhase != null && (originalPhase.equals("general", ignoreCase = true) || originalPhase.isBlank())) {
                finalPhase = "teleop"
                phaseChanged = true
            }

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

            val fieldChanged = labelChanged || optionsChanged || phaseChanged
            if (fieldChanged) {
                anyFieldChanged = true
                val updatedFieldMap = fieldObj.toMutableMap()
                updatedFieldMap["label"] = JsonPrimitive(normalizedLabelStr)
                if (finalPhase != null) {
                    updatedFieldMap["phase"] = JsonPrimitive(finalPhase)
                }
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
