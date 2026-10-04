package com.obsidianscout.config

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import com.obsidianscout.db.GamepadProfiles
import com.obsidianscout.db.readTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class GamepadBindingDTO(
    val id: String,
    val inputKey: String,
    val customLabel: String? = null,
    val actionType: String,
    val targetFieldId: String? = null,
    val targetValue: String? = null,
    val phase: String = "global",
    val triggerMode: String = "singlePress",
    val repeatFrequencyHz: Double = 6.0,
    val triggerMinHz: Double = 2.0,
    val triggerMaxHz: Double = 16.0,
    val triggerThreshold: Double = 0.15,
    val stepValue: Double = 1.0
)

@Serializable
data class GamepadProfileDTO(
    val id: String,
    val name: String,
    val description: String = "",
    val controllerType: String = "xbox",
    val selectedGamepadId: String? = null,
    val enabled: Boolean = true,
    val showTooltips: Boolean = true,
    val hapticEnabled: Boolean = true,
    val hapticStrength: Double = 1.0,
    val updatedAt: String? = null,
    val bindings: List<GamepadBindingDTO> = emptyList()
)

@Serializable
data class GamepadProfileListResponse(
    val success: Boolean = true,
    val profiles: List<GamepadProfileDTO>
)

@Serializable
data class GamepadProfileResponse(
    val success: Boolean = true,
    val profile: GamepadProfileDTO
)

object GamepadProfileService {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val profileCache = ConcurrentHashMap<String, List<GamepadProfileDTO>>()

    private fun cacheKey(teamNumber: Int, program: String) = "${teamNumber}_${program.uppercase()}"

    fun invalidateCache(teamNumber: Int, program: String) {
        profileCache.remove(cacheKey(teamNumber, program))
        profileCache.remove(cacheKey(0, program)) // Also clear team 0 fallback
    }

    fun listProfiles(teamNumber: Int, program: String): List<GamepadProfileDTO> {
        val key = cacheKey(teamNumber, program)
        profileCache[key]?.let { return it }

        val profiles = readTransaction {
            val rows = GamepadProfiles
                .selectAll()
                .where {
                    (GamepadProfiles.ownerTeamNumber eq teamNumber) and
                    (GamepadProfiles.program eq program)
                }
                .orderBy(GamepadProfiles.updatedAt to SortOrder.DESC)
                .toList()

            if (rows.isNotEmpty()) {
                rows.mapNotNull { row -> parseProfileRow(row) }
            } else if (teamNumber != 0) {
                // Fallback to team 0 default profiles if team has none defined yet
                GamepadProfiles
                    .selectAll()
                    .where {
                        (GamepadProfiles.ownerTeamNumber eq 0) and
                        (GamepadProfiles.program eq program)
                    }
                    .orderBy(GamepadProfiles.updatedAt to SortOrder.DESC)
                    .mapNotNull { row -> parseProfileRow(row) }
            } else {
                emptyList()
            }
        }

        profileCache[key] = profiles
        return profiles
    }

    fun getProfile(profileId: String, teamNumber: Int, program: String): GamepadProfileDTO? {
        val cached = listProfiles(teamNumber, program).firstOrNull { it.id == profileId }
        if (cached != null) return cached

        return readTransaction {
            val row = GamepadProfiles
                .selectAll()
                .where {
                    (GamepadProfiles.profileId eq profileId) and
                    (GamepadProfiles.ownerTeamNumber eq teamNumber) and
                    (GamepadProfiles.program eq program)
                }
                .firstOrNull() ?: GamepadProfiles
                .selectAll()
                .where {
                    (GamepadProfiles.profileId eq profileId) and
                    (GamepadProfiles.ownerTeamNumber eq 0) and
                    (GamepadProfiles.program eq program)
                }
                .firstOrNull()

            row?.let { parseProfileRow(it) }
        }
    }

    fun saveProfile(
        teamNumber: Int,
        program: String,
        profile: GamepadProfileDTO,
        updatedBy: String
    ): GamepadProfileDTO {
        val now = Instant.now()
        val profileToSave = profile.copy(updatedAt = now.toString())
        val profileJsonString = json.encodeToString(profileToSave)

        transaction {
            val existing = GamepadProfiles
                .selectAll()
                .where {
                    (GamepadProfiles.profileId eq profile.id) and
                    (GamepadProfiles.ownerTeamNumber eq teamNumber) and
                    (GamepadProfiles.program eq program)
                }
                .firstOrNull()

            if (existing != null) {
                GamepadProfiles.update({
                    (GamepadProfiles.profileId eq profile.id) and
                    (GamepadProfiles.ownerTeamNumber eq teamNumber) and
                    (GamepadProfiles.program eq program)
                }) {
                    it[name] = profile.name
                    it[description] = profile.description
                    it[controllerType] = profile.controllerType
                    it[selectedGamepadId] = profile.selectedGamepadId
                    it[enabled] = profile.enabled
                    it[showTooltips] = profile.showTooltips
                    it[hapticEnabled] = profile.hapticEnabled
                    it[hapticStrength] = profile.hapticStrength
                    it[profileJson] = profileJsonString
                    it[updatedByUsername] = updatedBy
                    it[updatedAt] = now
                }
            } else {
                GamepadProfiles.insert {
                    it[ownerTeamNumber] = teamNumber
                    it[this.program] = program
                    it[profileId] = profile.id
                    it[name] = profile.name
                    it[description] = profile.description
                    it[controllerType] = profile.controllerType
                    it[selectedGamepadId] = profile.selectedGamepadId
                    it[enabled] = profile.enabled
                    it[showTooltips] = profile.showTooltips
                    it[hapticEnabled] = profile.hapticEnabled
                    it[hapticStrength] = profile.hapticStrength
                    it[profileJson] = profileJsonString
                    it[updatedByUsername] = updatedBy
                    it[updatedAt] = now
                }
            }
        }

        invalidateCache(teamNumber, program)
        return profileToSave
    }

    fun deleteProfile(profileId: String, teamNumber: Int, program: String): Boolean {
        val deletedCount = transaction {
            GamepadProfiles.deleteWhere {
                (GamepadProfiles.profileId eq profileId) and
                (GamepadProfiles.ownerTeamNumber eq teamNumber) and
                (GamepadProfiles.program eq program)
            }
        }

        invalidateCache(teamNumber, program)
        return deletedCount > 0
    }

    private fun parseProfileRow(row: org.jetbrains.exposed.v1.core.ResultRow): GamepadProfileDTO? {
        val rawJson = row[GamepadProfiles.profileJson]
        return try {
            json.decodeFromString<GamepadProfileDTO>(rawJson)
        } catch (_: Throwable) {
            // Fallback to building directly from columns if raw JSON fails parsing
            GamepadProfileDTO(
                id = row[GamepadProfiles.profileId],
                name = row[GamepadProfiles.name],
                description = row[GamepadProfiles.description],
                controllerType = row[GamepadProfiles.controllerType],
                selectedGamepadId = row[GamepadProfiles.selectedGamepadId],
                enabled = row[GamepadProfiles.enabled],
                showTooltips = row[GamepadProfiles.showTooltips],
                hapticEnabled = row[GamepadProfiles.hapticEnabled],
                hapticStrength = row[GamepadProfiles.hapticStrength],
                updatedAt = row[GamepadProfiles.updatedAt].toString(),
                bindings = emptyList()
            )
        }
    }
}
