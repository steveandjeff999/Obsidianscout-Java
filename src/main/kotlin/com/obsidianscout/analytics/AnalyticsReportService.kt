package com.obsidianscout.analytics

import com.obsidianscout.auth.ApiException
import com.obsidianscout.auth.UserRole
import com.obsidianscout.auth.UserSession
import com.obsidianscout.config.ConfigService
import com.obsidianscout.config.JsonSupport
import com.obsidianscout.db.*
import com.obsidianscout.integrations.SettingsService
import com.obsidianscout.scouting.PitScoutingService
import com.obsidianscout.scouting.QualitativeScoutingService
import com.obsidianscout.scouting.ScoutingService
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

@Serializable
data class AnalyticsReportRecord(
    val id: String,
    val ownerTeamNumber: Int,
    val program: String,
    val userId: String,
    val title: String,
    val category: String,
    val description: String? = null,
    val configJson: String,
    val isShared: Boolean,
    val isDefault: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val authorUsername: String? = null,
    val isOwner: Boolean = false
)

@Serializable
data class CreateReportRequest(
    val title: String,
    val category: String = "General",
    val description: String? = null,
    val configJson: String,
    val isShared: Boolean = false,
    val isDefault: Boolean = false
)

@Serializable
data class UpdateReportRequest(
    val title: String? = null,
    val category: String? = null,
    val description: String? = null,
    val configJson: String? = null,
    val isShared: Boolean? = null,
    val isDefault: Boolean? = null
)

@Serializable
data class FieldMetadata(
    val id: String,
    val label: String,
    val type: String,
    val source: String, // match, pit, qual, calculated
    val section: String = "General",
    val pointsPer: Double? = null,
    val options: List<String> = emptyList()
)

@Serializable
data class TeamAnalyticsSummary(
    val teamNumber: Int,
    val name: String? = null,
    val nickname: String? = null,
    val city: String? = null,
    val state: String? = null,
    val opr: Double? = null,
    val epa: Double? = null
)

@Serializable
data class AnalyticsDatasetResponse(
    val generatedAt: String,
    val eventKey: String?,
    val fields: List<FieldMetadata>,
    val matchEntries: List<JsonObject>,
    val pitEntries: List<JsonObject>,
    val qualEntries: List<JsonObject>,
    val teams: List<TeamAnalyticsSummary>,
    val totalMatches: Int,
    val totalTeams: Int
)

object AnalyticsReportService {

    fun listReports(session: UserSession): List<AnalyticsReportRecord> = transaction {
        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
        val userTeam = session.teamNumber
        val program = session.program

        val query = if (session.role == UserRole.SUPERADMIN) {
            AnalyticsReports.selectAll()
        } else {
            AnalyticsReports.selectAll().where {
                (AnalyticsReports.program eq program) and (
                    (AnalyticsReports.ownerTeamNumber eq userTeam) or
                    (AnalyticsReports.isShared eq true) or
                    (userUuid?.let { AnalyticsReports.userId eq EntityID(it, Users) } ?: Op.FALSE)
                )
            }
        }.orderBy(AnalyticsReports.updatedAt, SortOrder.DESC)

        // Cache usernames for display
        val userIds = query.mapNotNull { runCatching { it[AnalyticsReports.userId].value }.getOrNull() }.distinct()
        val usernameMap = if (userIds.isNotEmpty()) {
            Users.selectAll().where { Users.id inList userIds }.associate { it[Users.id].value to it[Users.username] }
        } else emptyMap()

        query.map { row ->
            val reportUserId = row[AnalyticsReports.userId].value
            val isOwner = (userUuid != null && reportUserId == userUuid) || session.role == UserRole.SUPERADMIN
            AnalyticsReportRecord(
                id = row[AnalyticsReports.id].value.toString(),
                ownerTeamNumber = row[AnalyticsReports.ownerTeamNumber],
                program = row[AnalyticsReports.program],
                userId = reportUserId.toString(),
                title = row[AnalyticsReports.title],
                category = row[AnalyticsReports.category],
                description = row[AnalyticsReports.description],
                configJson = row[AnalyticsReports.configJson],
                isShared = row[AnalyticsReports.isShared],
                isDefault = row[AnalyticsReports.isDefault],
                createdAt = row[AnalyticsReports.createdAt].toString(),
                updatedAt = row[AnalyticsReports.updatedAt].toString(),
                authorUsername = usernameMap[reportUserId] ?: "Unknown",
                isOwner = isOwner
            )
        }
    }

    fun getReport(id: String, session: UserSession): AnalyticsReportRecord = transaction {
        val reportUuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid report ID")

        val row = AnalyticsReports.selectAll().where { AnalyticsReports.id eq EntityID(reportUuid, AnalyticsReports) }.singleOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "Report not found")

        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
        val reportOwnerTeam = row[AnalyticsReports.ownerTeamNumber]
        val reportUserId = row[AnalyticsReports.userId].value
        val isShared = row[AnalyticsReports.isShared]

        val canView = session.role == UserRole.SUPERADMIN ||
                reportOwnerTeam == session.teamNumber ||
                (userUuid != null && reportUserId == userUuid) ||
                isShared

        if (!canView) {
            throw ApiException(HttpStatusCode.Forbidden, "You do not have permission to view this report")
        }

        val author = Users.selectAll().where { Users.id eq reportUserId }.singleOrNull()?.get(Users.username) ?: "Unknown"
        val isOwner = (userUuid != null && reportUserId == userUuid) || session.role == UserRole.SUPERADMIN

        AnalyticsReportRecord(
            id = row[AnalyticsReports.id].value.toString(),
            ownerTeamNumber = reportOwnerTeam,
            program = row[AnalyticsReports.program],
            userId = reportUserId.toString(),
            title = row[AnalyticsReports.title],
            category = row[AnalyticsReports.category],
            description = row[AnalyticsReports.description],
            configJson = row[AnalyticsReports.configJson],
            isShared = isShared,
            isDefault = row[AnalyticsReports.isDefault],
            createdAt = row[AnalyticsReports.createdAt].toString(),
            updatedAt = row[AnalyticsReports.updatedAt].toString(),
            authorUsername = author,
            isOwner = isOwner
        )
    }

    fun createReport(session: UserSession, request: CreateReportRequest): AnalyticsReportRecord = transaction {
        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID")

        val now = Instant.now()
        val trimmedTitle = request.title.trim().ifEmpty { "Untitled Report" }

        // If this report is marked as default, unset other defaults for this user
        if (request.isDefault) {
            AnalyticsReports.update({
                (AnalyticsReports.userId eq EntityID(userUuid, Users)) and (AnalyticsReports.program eq session.program)
            }) {
                it[isDefault] = false
            }
        }

        val id = AnalyticsReports.insertAndGetId {
            it[ownerTeamNumber] = session.teamNumber
            it[program] = session.program
            it[userId] = EntityID(userUuid, Users)
            it[title] = trimmedTitle
            it[category] = request.category.trim().ifEmpty { "General" }
            it[description] = request.description?.trim()
            it[configJson] = request.configJson
            it[isShared] = request.isShared
            it[isDefault] = request.isDefault
            it[createdAt] = now
            it[updatedAt] = now
        }

        AnalyticsReportRecord(
            id = id.value.toString(),
            ownerTeamNumber = session.teamNumber,
            program = session.program,
            userId = userUuid.toString(),
            title = trimmedTitle,
            category = request.category.trim().ifEmpty { "General" },
            description = request.description?.trim(),
            configJson = request.configJson,
            isShared = request.isShared,
            isDefault = request.isDefault,
            createdAt = now.toString(),
            updatedAt = now.toString(),
            authorUsername = session.username,
            isOwner = true
        )
    }

    fun updateReport(id: String, session: UserSession, request: UpdateReportRequest): AnalyticsReportRecord = transaction {
        val reportUuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid report ID")

        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID")

        val row = AnalyticsReports.selectAll().where { AnalyticsReports.id eq EntityID(reportUuid, AnalyticsReports) }.singleOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "Report not found")

        val reportUserId = row[AnalyticsReports.userId].value
        val isOwner = (reportUserId == userUuid) || session.role.isAtLeast(UserRole.ADMIN)

        if (!isOwner) {
            throw ApiException(HttpStatusCode.Forbidden, "Only the author or an admin can update this report")
        }

        if (request.isDefault == true) {
            AnalyticsReports.update({
                (AnalyticsReports.userId eq EntityID(userUuid, Users)) and (AnalyticsReports.program eq session.program)
            }) {
                it[isDefault] = false
            }
        }

        val now = Instant.now()
        AnalyticsReports.update({ AnalyticsReports.id eq EntityID(reportUuid, AnalyticsReports) }) {
            if (request.title != null) it[title] = request.title.trim().ifEmpty { "Untitled Report" }
            if (request.category != null) it[category] = request.category.trim().ifEmpty { "General" }
            if (request.description != null) it[description] = request.description.trim()
            if (request.configJson != null) it[configJson] = request.configJson
            if (request.isShared != null) it[isShared] = request.isShared
            if (request.isDefault != null) it[isDefault] = request.isDefault
            it[updatedAt] = now
        }

        getReport(id, session)
    }

    fun deleteReport(id: String, session: UserSession): Boolean = transaction {
        val reportUuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid report ID")

        val userUuid = runCatching { UUID.fromString(session.userId) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "Invalid user ID")

        val row = AnalyticsReports.selectAll().where { AnalyticsReports.id eq EntityID(reportUuid, AnalyticsReports) }.singleOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "Report not found")

        val reportUserId = row[AnalyticsReports.userId].value
        val isOwner = (reportUserId == userUuid) || session.role.isAtLeast(UserRole.ADMIN)

        if (!isOwner) {
            throw ApiException(HttpStatusCode.Forbidden, "Only the author or an admin can delete this report")
        }

        AnalyticsReports.deleteWhere { AnalyticsReports.id eq EntityID(reportUuid, AnalyticsReports) } > 0
    }

    fun duplicateReport(id: String, session: UserSession): AnalyticsReportRecord = transaction {
        val original = getReport(id, session)
        createReport(
            session,
            CreateReportRequest(
                title = "${original.title} (Copy)",
                category = original.category,
                description = original.description,
                configJson = original.configJson,
                isShared = false,
                isDefault = false
            )
        )
    }

    /**
     * Generates a high-performance flattened analytics dataset merging match, pit, and qual scouting data
     * with field definitions, team statistics, and API metadata for interactive exploration.
     */
    fun generateDataset(
        session: UserSession,
        eventKeyFilter: String? = null,
        includePrescout: Boolean = true
    ): AnalyticsDatasetResponse {
        val matchConfig = ConfigService.getConfig(session.teamNumber, session.program)
        val pitConfig = ConfigService.getPitConfig(session.teamNumber, session.program)
        val qualConfig = ConfigService.getQualitativeConfig(session.teamNumber, session.program)

        val rawMatchEntries = ScoutingService.listEntries(session, includePrescout = includePrescout)
        val rawPitEntries = PitScoutingService.listEntries(session, includePrescout = includePrescout)
        val rawQualEntries = QualitativeScoutingService.listEntries(session, includePrescout = includePrescout)

        val matchEntries = if (!eventKeyFilter.isNullOrBlank()) rawMatchEntries.filter { it.eventKey == eventKeyFilter } else rawMatchEntries
        val pitEntries = if (!eventKeyFilter.isNullOrBlank()) rawPitEntries.filter { it.eventKey == eventKeyFilter } else rawPitEntries
        val qualEntries = if (!eventKeyFilter.isNullOrBlank()) rawQualEntries.filter { it.eventKey == eventKeyFilter } else rawQualEntries

        val configuredEventKey: String? = SettingsService.getSettings(session.teamNumber, session.program).resolvedEventKey().ifBlank { null }
        val scoutingEventKeys: List<String> = (rawMatchEntries.mapNotNull { it.eventKey } + rawPitEntries.mapNotNull { it.eventKey } + rawQualEntries.mapNotNull { it.eventKey }).filter { it.isNotBlank() }.distinct()
        val visibleEventKeys: List<String> = (listOfNotNull(configuredEventKey) + scoutingEventKeys).distinct()

        val teamsList = transaction {
            val query = if (!eventKeyFilter.isNullOrBlank()) {
                ApiTeams.selectAll().where { ApiTeams.eventKey eq eventKeyFilter }
            } else if (visibleEventKeys.isNotEmpty()) {
                ApiTeams.selectAll().where { ApiTeams.eventKey inList visibleEventKeys }
            } else {
                val entryTeams: List<Int> = (rawMatchEntries.mapNotNull { it.targetTeamNumber } + rawPitEntries.mapNotNull { it.targetTeamNumber } + rawQualEntries.mapNotNull { it.targetTeamNumber }).distinct()
                if (entryTeams.isNotEmpty()) {
                    ApiTeams.selectAll().where { ApiTeams.teamNumber inList entryTeams }
                } else {
                    ApiTeams.selectAll().where { ApiTeams.teamNumber eq session.teamNumber }
                }
            }
            query.map { row ->
                TeamAnalyticsSummary(
                    teamNumber = row[ApiTeams.teamNumber],
                    name = row[ApiTeams.name],
                    nickname = row[ApiTeams.nickname],
                    city = row[ApiTeams.city],
                    state = row[ApiTeams.state],
                    opr = row[ApiTeams.opr],
                    epa = row[ApiTeams.epa]
                )
            }.distinctBy { it.teamNumber }
        }

        // Build rich field metadata
        val fields = mutableListOf<FieldMetadata>()

        // Core system dimensions
        fields.add(FieldMetadata("teamNumber", "Team Number", "number", "system", "Identification"))
        fields.add(FieldMetadata("matchNumber", "Match Number", "number", "system", "Identification"))
        fields.add(FieldMetadata("matchKey", "Match Key", "string", "system", "Identification"))
        fields.add(FieldMetadata("eventKey", "Event Key", "string", "system", "Identification"))
        fields.add(FieldMetadata("isPrescout", "Is Prescout", "boolean", "system", "Identification"))
        fields.add(FieldMetadata("createdAt", "Recorded At", "string", "system", "Metadata"))

        // Match config fields
        matchConfig.fields.forEach { field ->
            val phaseName = field.phase ?: "Match Scouting"
            fields.add(
                FieldMetadata(
                    id = "match_${field.id}",
                    label = "${phaseName}: ${field.label}",
                    type = mapFieldType(field.type),
                    source = "match",
                    section = phaseName,
                    pointsPer = field.pointsPer,
                    options = field.options.map { it.label }
                )
            )
            // If field has pointsPer configured, also auto-generate a Points measure
            if (field.pointsPer != null && field.pointsPer != 0.0) {
                fields.add(
                    FieldMetadata(
                        id = "calc_pts_${field.id}",
                        label = "${phaseName}: ${field.label} Points",
                        type = "number",
                        source = "calculated",
                        section = "Scoring Elements (${phaseName})",
                        pointsPer = field.pointsPer
                    )
                )
            }
        }

        // Calculated Total Score and Auto/Teleop/Endgame Scores
        fields.add(FieldMetadata("calc_total_score", "Total Score", "number", "calculated", "Overall Scores"))
        fields.add(FieldMetadata("calc_auto_score", "Auto Score", "number", "calculated", "Phase Scores"))
        fields.add(FieldMetadata("calc_teleop_score", "Teleop Score", "number", "calculated", "Phase Scores"))
        fields.add(FieldMetadata("calc_endgame_score", "Endgame Score", "number", "calculated", "Phase Scores"))
        fields.add(FieldMetadata("statbotics_epa", "EPA", "number", "calculated", "Statistics"))
        fields.add(FieldMetadata("tba_opr", "OPR", "number", "calculated", "Statistics"))

        // Pit config fields
        pitConfig.fields.forEach { field ->
            val phaseName = field.phase ?: "Pit Scouting"
            fields.add(
                FieldMetadata(
                    id = "pit_${field.id}",
                    label = "Pit: ${field.label}",
                    type = mapFieldType(field.type),
                    source = "pit",
                    section = "Pit: $phaseName",
                    options = field.options.map { it.label }
                )
            )
        }

        // Qualitative config fields
        qualConfig.fields.forEach { field ->
            val phaseName = field.phase ?: "Qualitative"
            fields.add(
                FieldMetadata(
                    id = "qual_${field.id}",
                    label = "Qual: ${field.label}",
                    type = mapFieldType(field.type),
                    source = "qual",
                    section = "Qual: $phaseName",
                    pointsPer = field.pointsPer,
                    options = field.options.map { it.label }
                )
            )
        }

        val teamOprMap = teamsList.associate { it.teamNumber to it.opr }
        val teamEpaMap = teamsList.associate { it.teamNumber to it.epa }

        // Transform and flatten match records
        val flattenedMatches = matchEntries.map { entry ->
            val totalScore = AnalyticsService.scoreEntry(matchConfig, entry)
            val autoScore = scoreSection(matchConfig, entry, "auto")
            val teleopScore = scoreSection(matchConfig, entry, "teleop")
            val endgameScore = scoreSection(matchConfig, entry, "endgame") + scoreSection(matchConfig, entry, "climb")
            val tNum = entry.targetTeamNumber ?: 0

            buildJsonObject {
                put("id", entry.id)
                put("teamNumber", tNum)
                put("matchNumber", entry.matchNumber ?: 0)
                put("matchKey", entry.matchKey ?: "")
                put("eventKey", entry.eventKey ?: "")
                put("isPrescout", entry.isPrescout)
                put("createdAt", entry.createdAt)
                put("calc_total_score", totalScore)
                put("calc_auto_score", autoScore)
                put("calc_teleop_score", teleopScore)
                put("calc_endgame_score", endgameScore)
                put("statbotics_epa", teamEpaMap[tNum] ?: 0.0)
                put("tba_opr", teamOprMap[tNum] ?: 0.0)
                put("hasDiscrepancy", entry.hasDiscrepancy)

                // Add all custom form field values with match_ prefix and points if applicable
                entry.data.forEach { (k, v) ->
                    put("match_$k", v)
                    val field = matchConfig.fields.find { it.id == k }
                    if (field?.pointsPer != null) {
                        val num = (v as? JsonPrimitive)?.content?.toDoubleOrNull()
                        val boolVal = (v as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        val pts = if (num != null) num * field.pointsPer
                        else if (boolVal == true) field.pointsPer
                        else 0.0
                        put("calc_pts_$k", pts)
                    }
                }
            }
        }

        // Transform pit records
        val flattenedPits = pitEntries.map { entry ->
            buildJsonObject {
                put("id", entry.id)
                put("teamNumber", entry.targetTeamNumber ?: 0)
                put("eventKey", entry.eventKey ?: "")
                put("isPrescout", entry.isPrescout)
                put("createdAt", entry.createdAt)
                entry.data.forEach { (k, v) ->
                    put("pit_$k", v)
                }
            }
        }

        // Transform qual records
        val flattenedQuals = qualEntries.map { entry ->
            buildJsonObject {
                put("id", entry.id)
                put("teamNumber", entry.targetTeamNumber ?: 0)
                put("matchNumber", entry.matchNumber ?: 0)
                put("matchKey", entry.matchKey ?: "")
                put("eventKey", entry.eventKey ?: "")
                put("isPrescout", entry.isPrescout)
                put("createdAt", entry.createdAt)
                entry.data.forEach { (k, v) ->
                    put("qual_$k", v)
                }
            }
        }

        val allUniqueTeams = (flattenedMatches.mapNotNull { it["teamNumber"]?.jsonPrimitive?.intOrNull } +
                teamsList.map { it.teamNumber }).distinct()

        return AnalyticsDatasetResponse(
            generatedAt = Instant.now().toString(),
            eventKey = eventKeyFilter,
            fields = fields,
            matchEntries = flattenedMatches,
            pitEntries = flattenedPits,
            qualEntries = flattenedQuals,
            teams = teamsList,
            totalMatches = flattenedMatches.size,
            totalTeams = allUniqueTeams.size
        )
    }

    private fun mapFieldType(rawType: String): String = when (rawType.lowercase()) {
        "counter", "number", "rating", "timer", "slider" -> "number"
        "checkbox", "toggle", "switch" -> "boolean"
        "select", "radio", "text", "textarea", "comment" -> "string"
        else -> "string"
    }

    private fun scoreSection(config: com.obsidianscout.config.ScoutingConfig, entry: com.obsidianscout.scouting.ScoutingEntryRecord, sectionSubstring: String): Double {
        return config.fields
            .filter { (it.phase ?: "").contains(sectionSubstring, ignoreCase = true) || it.id.contains(sectionSubstring, ignoreCase = true) }
            .sumOf { field ->
                val elem = entry.data[field.id] ?: return@sumOf 0.0
                val num = (elem as? JsonPrimitive)?.content?.toDoubleOrNull()
                val boolVal = (elem as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                if (num != null) (field.pointsPer ?: 0.0) * num
                else if (boolVal == true) field.pointsPer ?: 0.0
                else 0.0
            }
    }
}
