package com.obsidianscout.publicdata

import com.obsidianscout.integrations.ApiSettings
import io.ktor.util.AttributeKey

/**
 * Set for requests under /api/public/{team}/{event}: read-only, the owner team's own data only (never alliance
 * partners'), and the event taken from the URL instead of the team's settings.
 *
 * Handlers find it through [AttributeKey]; non-suspend service code (settings, alliance lookups) reads it from the
 * [local] thread-local, which the gate carries across coroutine thread hops with `asContextElement`.
 */
data class PublicViewContext(
    val teamNumber: Int,
    val program: String,
    /** Blank when the visitor didn't pick an event; the team's own event is used then. */
    val eventKey: String,
    val includeNotes: Boolean
) {
    companion object {
        val local = ThreadLocal<PublicViewContext?>()
        val AttributeKey = AttributeKey<PublicViewContext>("PublicViewContext")

        fun current(): PublicViewContext? = local.get()

        /** URL path segment meaning "no event picked". */
        const val NO_EVENT = "_"
    }

    /** [settings] for [team] with the event replaced by the one from the URL; unchanged for other teams. */
    fun applyEvent(team: Int, settingsProgram: String, settings: ApiSettings): ApiSettings {
        if (team != teamNumber || !settingsProgram.equals(program, ignoreCase = true) || eventKey.isBlank()) return settings
        val year = eventKey.take(4).toIntOrNull()
        return if (year != null && eventKey.length > 4) {
            settings.copy(year = year, eventCode = eventKey.drop(4), alternateEventCode = "", eventKey = eventKey)
        } else {
            settings.copy(eventCode = "", alternateEventCode = "", eventKey = eventKey)
        }
    }
}
