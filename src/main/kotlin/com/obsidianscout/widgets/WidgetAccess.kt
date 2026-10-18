package com.obsidianscout.widgets

import com.obsidianscout.auth.UserRole
import com.obsidianscout.integrations.ApiSettings

/**
 * Which home-screen widgets a user may have. Each widget belongs to app pages, and the user gets the widget
 * only when they can open those pages: the same rules as the app's `ApiService.hasPageAccess`
 * (role page lists from team settings, superadmin always, admin-only pages, the team chat switch,
 * and the personal Non-Scouting Tools opt-in for drive-team pages).
 */
object WidgetAccess {
    /** Personal opt-in pages (Settings > Non-Scouting Tools), not role pages. */
    val NON_SCOUTING_TOOL_PAGES = setOf("match-checklist", "battery-tracker")

    private val SUPERADMIN_ONLY_PAGES = setOf("cluster-management", "error-reports", "fcm-settings", "migration", "backup", "logs")
    private val ADMIN_ONLY_BASE_PAGES = setOf("users", "banners", "admin-settings", "default-configs", "config-editor")
    private val BYPASS_PAGES = setOf(
        "settings", "login", "index", "dashboard", "theme-editor", "team", "reset-password", "config-migration", "schema-history"
    )

    /** Widget kind -> pages; the widget is allowed when any one of its pages is. */
    val WIDGET_PAGES: Map<String, List<String>> = linkedMapOf(
        "next_match" to listOf("matches"),
        "schedule" to listOf("matches"),
        "predictor" to listOf("predictor"),
        "assignments" to listOf("my-assignments"),
        "battery" to listOf("battery-tracker"),
        "checklist" to listOf("match-checklist"),
        "rankings" to listOf("projected-rankings"),
        "chat" to listOf("chat"),
        "quick_actions" to listOf("scout", "pit-scout", "qual-scout", "qr-scanner"),
    )

    /** Pages whose access the snapshot reports, so widgets and their buttons can follow them. */
    /** Also reported: pages a widget opens when tapped, so it only links where the user may go. */
    val REPORTED_PAGES: List<String> = (WIDGET_PAGES.values.flatten() + listOf("rankings", "event-predictor")).distinct()

    fun canAccessPage(
        pageId: String,
        role: UserRole,
        settings: ApiSettings,
        nonScoutingToolsEnabled: Boolean,
    ): Boolean {
        if (pageId in NON_SCOUTING_TOOL_PAGES) return nonScoutingToolsEnabled
        // The server refuses chat to everyone when the team turns it off, superadmins included.
        if (pageId == "chat" && !settings.chatEnabled) return false
        if (role == UserRole.SUPERADMIN) return true
        if (pageId in SUPERADMIN_ONLY_PAGES) return false
        if (pageId in ADMIN_ONLY_BASE_PAGES && role != UserRole.ADMIN) return false
        if (pageId in BYPASS_PAGES) return true

        val allowed = when (role) {
            UserRole.ADMIN -> settings.adminPages
            UserRole.ANALYTICS -> settings.analyticsPages
            else -> settings.scoutPages
        }
        return aliasesOf(pageId).any { it in allowed }
    }

    /** Same aliases the app resolves between app and website page ids. */
    private fun aliasesOf(pageId: String): Set<String> {
        val out = mutableSetOf(pageId)
        when (pageId) {
            "config-editor" -> out += listOf("admin-settings", "default-configs")
            "admin-settings", "default-configs" -> out += "config-editor"
            "alliance-selection" -> out += "alliances"
            "alliances" -> out += "alliance-selection"
            "graphs" -> out += "analytics"
            "analytics" -> out += "graphs"
            "scout" -> out += "match-scout"
            "match-scout" -> out += "scout"
            "prescout" -> out += listOf("prescout-scout", "prescout-pit", "prescout-qual")
            "scout-history" -> out += listOf("history", "scouting-history", "cache-manager")
            "history", "scouting-history", "cache-manager" -> out += listOf("scout-history", "history", "cache-manager")
            "match-data" -> out += "all-data"
            "match-planning" -> out += listOf("match-planner", "matchplanning", "planning")
            "match-planner", "matchplanning", "planning" -> out += "match-planning"
        }
        if (pageId.startsWith("prescout-")) out += "prescout"
        return out
    }

    /** Page id -> allowed, for every page a widget depends on. */
    fun pageAccess(role: UserRole, settings: ApiSettings, nonScoutingToolsEnabled: Boolean): Map<String, Boolean> =
        REPORTED_PAGES.associateWith { canAccessPage(it, role, settings, nonScoutingToolsEnabled) }

    /** Widget kind -> allowed. */
    fun widgetAccess(pages: Map<String, Boolean>): Map<String, Boolean> =
        WIDGET_PAGES.mapValues { (_, needed) -> needed.any { pages[it] == true } }
}
