package com.obsidianscout.collab

import com.obsidianscout.config.ConfigMigrationService
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.config.ScoutingConfig
import com.obsidianscout.db.DefaultConfigs
import com.obsidianscout.db.ScoutingAlliances
import com.obsidianscout.db.readTransaction
import com.obsidianscout.scouting.BatteryTrackerService
import com.obsidianscout.scouting.MatchChecklistService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

data class CommitResult(
    val hasFieldChanges: Boolean = false,
    val changedFields: List<String> = emptyList(),
    val entryCount: Int = 0,
    val configKind: String = "game"
)

/**
 * Where a live-edited config lives. A scope round-trips through its room key so the node that owns a
 * room (which may not be the node the editor connected to) can load and save it.
 */
sealed class ConfigCollabScope {
    abstract val roomKey: String
    abstract val kind: String

    /** Current stored JSON, or null if it can't be read right now. */
    abstract fun loadRaw(): String?

    /** Stores [json] and returns the text as stored (storage may normalize it). */
    abstract fun persist(json: String): String

    /** Stores [json] on behalf of [editor] (the last person to change it); most scopes don't track that. */
    open fun persistAs(json: String, editor: String): String = persist(json)

    /** Records an explicit "save version" from [username]; [base] is the config at the previous version. */
    open fun commit(base: ScoutingConfig?, current: ScoutingConfig, username: String, summaryPrefix: String? = null): CommitResult =
        CommitResult(configKind = kind)

    data class Team(val teamNumber: Int, val program: String, override val kind: String) : ConfigCollabScope() {
        override val roomKey get() = "team:$teamNumber:$program:$kind"

        override fun loadRaw(): String? = runCatching {
            when (kind) {
                "pit" -> ConfigService.getPitConfigJson(teamNumber, program, local = true)
                "qual" -> ConfigService.getQualitativeConfigJson(teamNumber, program, local = true)
                else -> ConfigService.getConfigJson(teamNumber, program, local = true)
            }
        }.getOrNull()

        override fun persist(json: String): String {
            when (kind) {
                "pit" -> ConfigService.updatePitConfig(teamNumber, program, json)
                "qual" -> ConfigService.updateQualitativeConfig(teamNumber, program, json)
                else -> ConfigService.updateConfig(teamNumber, program, json)
            }
            return ConfigService.normalizeConfigJson(json)
        }

        override fun commit(base: ScoutingConfig?, current: ScoutingConfig, username: String, summaryPrefix: String?): CommitResult {
            val (hasChanges, changedFields) = ConfigMigrationService.detectFieldChanges(base, current, teamNumber, program, kind)
            val entryCount = ConfigMigrationService.countExistingEntries(teamNumber, program, kind)
            val summary = if (changedFields.isEmpty()) "Config updated (no field structure changes)" else changedFields.joinToString(", ")
            ConfigMigrationService.saveRevision(
                teamNumber = teamNumber,
                program = program,
                kind = kind,
                config = current,
                changeSummary = if (summaryPrefix != null) "$summaryPrefix: $summary" else summary,
                savedByUsername = username
            )
            return CommitResult(hasChanges, changedFields, entryCount, kind)
        }
    }

    data class Alliance(val allianceId: UUID, override val kind: String) : ConfigCollabScope() {
        override val roomKey get() = "alliance:$allianceId:$kind"

        override fun loadRaw(): String? = runCatching {
            readTransaction {
                val row = ScoutingAlliances.selectAll().where { ScoutingAlliances.id eq allianceId }.firstOrNull()
                    ?: return@readTransaction null
                when (kind) {
                    "pit" -> row[ScoutingAlliances.pitConfigJson]
                    "qual" -> row[ScoutingAlliances.qualitativeConfigJson]
                    else -> row[ScoutingAlliances.matchConfigJson]
                } ?: "{}"
            }
        }.getOrNull()

        override fun persist(json: String): String {
            transaction {
                ScoutingAlliances.update({ ScoutingAlliances.id eq allianceId }) {
                    when (kind) {
                        "pit" -> it[pitConfigJson] = json
                        "qual" -> it[qualitativeConfigJson] = json
                        else -> it[matchConfigJson] = json
                    }
                    it[updatedAt] = Instant.now()
                }
            }
            return json
        }
    }

    data class Preset(val presetId: UUID) : ConfigCollabScope() {
        override val roomKey get() = "preset:$presetId"
        override val kind: String
            get() = when (presetType()) {
                "pit" -> "pit"
                "qualitative" -> "qual"
                else -> "game"
            }

        private fun presetType(): String? = runCatching {
            readTransaction {
                DefaultConfigs.selectAll().where { DefaultConfigs.id eq presetId }.firstOrNull()?.get(DefaultConfigs.configType)
            }
        }.getOrNull()

        override fun loadRaw(): String? = runCatching {
            readTransaction {
                DefaultConfigs.selectAll().where { DefaultConfigs.id eq presetId }.firstOrNull()?.get(DefaultConfigs.configJson)
            }
        }.getOrNull()

        override fun persist(json: String): String {
            val existing = ConfigService.getAllDefaultConfigs().firstOrNull { it.id == presetId.toString() }
                ?: throw IllegalStateException("Preset no longer exists")
            return ConfigService.updateDefaultConfig(presetId.toString(), existing.copy(configJson = json)).configJson
        }
    }

    /**
     * A drive-team match checklist: the team's template ([matchKey] = TEMPLATE_KEY, [eventKey] = "") or one
     * match's filled-out copy. Not a scouting config, so it has no revisions or migration checks.
     */
    data class Checklist(val teamNumber: Int, val program: String, val eventKey: String, val matchKey: String) : ConfigCollabScope() {
        override val roomKey get() = "checklist:$teamNumber:$program:${eventKey.ifEmpty { "-" }}:$matchKey"
        override val kind: String get() = "checklist"
        private val owner get() = MatchChecklistService.ownerKey(teamNumber, program)

        override fun loadRaw(): String? = runCatching {
            MatchChecklistService.load(owner, eventKey, matchKey)?.dataJson ?: "{}"
        }.getOrNull()

        override fun persist(json: String): String = persistAs(json, "")

        override fun persistAs(json: String, editor: String): String {
            MatchChecklistService.store(owner, eventKey, matchKey, json, editor)
            return json
        }
    }

    /**
     * The drive team's battery tracker: the battery inventory ([eventKey] = "") or one event's rotation plan.
     * Like checklists, it has no revisions or migration checks.
     */
    data class Batteries(val teamNumber: Int, val program: String, val eventKey: String) : ConfigCollabScope() {
        override val roomKey get() = "battery:$teamNumber:$program:${eventKey.ifEmpty { "-" }}"
        override val kind: String get() = "battery"
        private val owner get() = BatteryTrackerService.ownerKey(teamNumber, program)

        override fun loadRaw(): String? = runCatching {
            BatteryTrackerService.load(owner, eventKey)?.dataJson ?: "{}"
        }.getOrNull()

        override fun persist(json: String): String = persistAs(json, "")

        override fun persistAs(json: String, editor: String): String {
            BatteryTrackerService.store(owner, eventKey, json, editor)
            return json
        }
    }

    companion object {
        val KINDS = setOf("game", "pit", "qual")

        fun normalizeKind(kind: String?): String? = when (kind?.lowercase()) {
            "game", "match" -> "game"
            "pit" -> "pit"
            "qual", "qualitative" -> "qual"
            else -> null
        }

        fun fromRoomKey(roomKey: String): ConfigCollabScope? {
            val parts = roomKey.split(':')
            return runCatching {
                when (parts.firstOrNull()) {
                    "team" -> if (parts.size == 4) Team(parts[1].toInt(), parts[2], normalizeKind(parts[3])!!) else null
                    "alliance" -> if (parts.size == 3) Alliance(UUID.fromString(parts[1]), normalizeKind(parts[2])!!) else null
                    "preset" -> if (parts.size == 2) Preset(UUID.fromString(parts[1])) else null
                    "checklist" -> if (parts.size == 5) {
                        Checklist(parts[1].toInt(), parts[2], parts[3].takeUnless { it == "-" } ?: "", parts[4])
                    } else null
                    "battery" -> if (parts.size == 4) {
                        Batteries(parts[1].toInt(), parts[2], parts[3].takeUnless { it == "-" } ?: "")
                    } else null
                    else -> null
                }
            }.getOrNull()
        }

        fun parseConfig(json: String): ScoutingConfig? = runCatching {
            JsonSupport.json.decodeFromString(ScoutingConfig.serializer(), ConfigService.normalizeConfigJson(json))
        }.getOrNull()
    }
}
