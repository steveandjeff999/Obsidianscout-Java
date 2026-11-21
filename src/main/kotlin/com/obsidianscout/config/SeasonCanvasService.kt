package com.obsidianscout.config

import com.obsidianscout.auth.ApiException
import com.obsidianscout.db.SeasonCanvasDefaults
import com.obsidianscout.db.readTransaction
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

/** A season's default canvas. `layout.mode == "canvas"` means teams get it; anything else is a saved draft. */
@Serializable
data class SeasonCanvasDTO(
    val program: String,
    val year: Int,
    val layout: JsonObject,
    val active: Boolean,
    val updatedBy: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class SeasonCanvasUpdate(val layout: JsonObject)

/** What a team's scout page and Canvas Layout tab need: its season and that season's default canvas, if any. */
@Serializable
data class TeamSeasonCanvas(val year: Int, val layout: JsonObject? = null)

/**
 * Per-season canvas defaults, set by superadmins on the Default Configs page. A team follows its season's default
 * until its own match config carries a `layout` (its own canvas, or an explicit choice of the classic list).
 */
object SeasonCanvasService {
    private const val MAX_LAYOUT_CHARS = 400_000

    private fun programOf(raw: String?): String {
        val p = raw?.trim()?.uppercase().orEmpty()
        if (p != "FRC" && p != "FTC") throw ApiException(HttpStatusCode.BadRequest, "Program must be FRC or FTC")
        return p
    }

    private fun yearOf(raw: Int?): Int {
        val y = raw ?: throw ApiException(HttpStatusCode.BadRequest, "Missing season year")
        if (y < 1992 || y > 2100) throw ApiException(HttpStatusCode.BadRequest, "Season year looks wrong")
        return y
    }

    fun isActive(layout: JsonObject): Boolean =
        (layout["mode"] as? JsonPrimitive)?.content == "canvas" && ((layout["pages"] as? JsonArray)?.isNotEmpty() == true)

    private fun toDto(row: org.jetbrains.exposed.v1.core.ResultRow): SeasonCanvasDTO? {
        val layout = runCatching { JsonSupport.json.parseToJsonElement(row[SeasonCanvasDefaults.layoutJson]) as? JsonObject }.getOrNull() ?: return null
        return SeasonCanvasDTO(
            program = row[SeasonCanvasDefaults.program],
            year = row[SeasonCanvasDefaults.year],
            layout = layout,
            active = isActive(layout),
            updatedBy = row[SeasonCanvasDefaults.updatedBy],
            updatedAt = row[SeasonCanvasDefaults.updatedAt].toString()
        )
    }

    fun list(program: String): List<SeasonCanvasDTO> {
        val p = programOf(program)
        return readTransaction {
            SeasonCanvasDefaults.selectAll().where { SeasonCanvasDefaults.program eq p }
                .orderBy(SeasonCanvasDefaults.year to SortOrder.DESC)
                .mapNotNull { toDto(it) }
        }
    }

    fun get(program: String, year: Int?): SeasonCanvasDTO? {
        val p = programOf(program)
        val y = yearOf(year)
        return readTransaction {
            SeasonCanvasDefaults.selectAll().where { (SeasonCanvasDefaults.program eq p) and (SeasonCanvasDefaults.year eq y) }
                .firstOrNull()?.let { toDto(it) }
        }
    }

    fun put(program: String, year: Int?, layout: JsonObject, username: String?): SeasonCanvasDTO {
        val p = programOf(program)
        val y = yearOf(year)
        val text = JsonSupport.wireJson.encodeToString(JsonObject.serializer(), layout)
        if (text.length > MAX_LAYOUT_CHARS) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "This canvas is too large. Use uploaded images instead of very large embedded ones.")
        }
        val now = Instant.now()
        transaction {
            val updated = SeasonCanvasDefaults.update({ (SeasonCanvasDefaults.program eq p) and (SeasonCanvasDefaults.year eq y) }) {
                it[layoutJson] = text
                it[updatedBy] = username?.take(64)
                it[updatedAt] = now
            }
            if (updated == 0) {
                SeasonCanvasDefaults.insert {
                    it[SeasonCanvasDefaults.program] = p
                    it[SeasonCanvasDefaults.year] = y
                    it[layoutJson] = text
                    it[updatedBy] = username?.take(64)
                    it[updatedAt] = now
                }
            }
        }
        return SeasonCanvasDTO(p, y, layout, isActive(layout), username, now.toString())
    }

    fun delete(program: String, year: Int?): Boolean {
        val p = programOf(program)
        val y = yearOf(year)
        return transaction {
            SeasonCanvasDefaults.deleteWhere { (SeasonCanvasDefaults.program eq p) and (SeasonCanvasDefaults.year eq y) } > 0
        }
    }

    /** The canvas a team falls back to: its season's default, only while that default is switched on. */
    fun forTeam(teamNumber: Int, program: String): TeamSeasonCanvas {
        val year = runCatching { com.obsidianscout.integrations.SettingsService.getSettings(teamNumber, program).year }
            .getOrElse { java.time.Year.now().value }
        val layout = runCatching { get(program, year) }.getOrNull()?.takeIf { it.active }?.layout
        return TeamSeasonCanvas(year, layout)
    }
}
