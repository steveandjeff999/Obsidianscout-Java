const AUTO_SYNC_MS = 7.5 * 60 * 1000;

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

function formatTemplate(template, values) {
    return template.replace(/\{(\w+)\}/g, (_, key) => String(values[key] ?? ''));
}

function localizeTimezone(timezone) {
    const timezoneKeyMap = {
        'America/New_York': 'timezone.eastern',
        'America/Chicago': 'timezone.central',
        'America/Denver': 'timezone.mountain',
        'America/Los_Angeles': 'timezone.pacific',
        'America/Phoenix': 'timezone.arizona',
        'America/Anchorage': 'timezone.alaska',
        'Pacific/Honolulu': 'timezone.hawaii',
        UTC: 'timezone.utc',
        'Europe/London': 'timezone.london',
        'Europe/Paris': 'timezone.paris',
        'Asia/Tokyo': 'timezone.tokyo',
        'Australia/Sydney': 'timezone.sydney'
    };
    const key = timezoneKeyMap[timezone];
    return key ? t(key, timezone) : timezone;
}

let originalDashboardHTML = "";
let dashboardContainer = null;
let currentUser = null;

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }
    currentUser = me;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    // Create wrapper for the dynamic dashboard panels
    const metrics = document.querySelector(".metrics");
    if (metrics) {
        dashboardContainer = document.createElement("div");
        dashboardContainer.id = "dashboard-dynamic-container";
        metrics.parentNode.insertBefore(dashboardContainer, metrics);
        
        const split = document.querySelector(".split");
        dashboardContainer.appendChild(metrics);
        if (split) {
            dashboardContainer.appendChild(split);
        }
        originalDashboardHTML = dashboardContainer.innerHTML;
    }

    await loadDashboardData();

    setInterval(async () => {
        if (document.hidden || !currentUser) {
            return;
        }
        const syncStatus = document.getElementById("sync-status");
        if (!syncStatus) return; // if currently showing error or loading spinner
        try {
            await refreshSummary();
            await refreshSyncStatus(syncStatus);
        } catch (e) {
            console.warn("Background auto-sync update failed", e);
        }
    }, AUTO_SYNC_MS);
});

async function loadDashboardData() {
    if (!dashboardContainer) return;
    Obsidianscout.showLoadingSpinner(dashboardContainer, t("status.loading", t('dashboard.loading_dashboard_data', "Loading dashboard data...")));

    try {
        const [summary, settingsResponse, status] = await Promise.all([
            Obsidianscout.request("/api/summary"),
            Obsidianscout.request("/api/settings"),
            Obsidianscout.request("/api/integrations/sync/status").catch(() => null)
        ]);

        dashboardContainer.innerHTML = originalDashboardHTML;

        // Populate elements
        const settings = settingsResponse.settings;
        document.getElementById("summary-entries").textContent = summary.entries;
        document.getElementById("summary-events").textContent = summary.events;
        document.getElementById("summary-teams").textContent = summary.teams;
        document.getElementById("summary-matches").textContent = summary.matches;
        document.getElementById("summary-year").textContent = settings.year;
        document.getElementById("summary-event").textContent =
            Obsidianscout.resolveEventKey(settings) || t("dashboard.not_set", "Not set");
        document.getElementById("summary-timezone").textContent = localizeTimezone(settings.timezone);

        const syncAll = document.getElementById("sync-all");
        const syncStatus = document.getElementById("sync-status");

        window.addEventListener("obsidianscout:languagechange", async () => {
            const syncStatusEl = document.getElementById("sync-status");
            if (syncStatusEl) {
                await refreshSummary();
                await refreshSyncStatus(syncStatusEl);
            }
        });

        if (!Obsidianscout.isAdmin(currentUser.role)) {
            if (syncAll) {
                syncAll.disabled = true;
            }
            if (syncStatus) {
                syncStatus.textContent = t("dashboard.admin_sync_required", "Admin access required to sync.");
            }
        } else {
            if (syncStatus && status) {
                await refreshSyncStatus(syncStatus);
            }
            if (syncAll) {
                syncAll.addEventListener("click", () =>
                    runSync(syncAll, "/api/integrations/sync/all", true)
                );
            }
        }

    } catch (error) {
        console.error("Failed to load dashboard data:", error);
        Obsidianscout.showRetryButton(dashboardContainer, t("status.load_failed", t('dashboard.failed_to_load_dashboard_data', "Failed to load dashboard data: ")) + error.message, loadDashboardData);
    }
}

async function refreshSummary() {
    const [summary, settingsResponse] = await Promise.all([
        Obsidianscout.request("/api/summary"),
        Obsidianscout.request("/api/settings")
    ]);
    const settings = settingsResponse.settings;

    document.getElementById("summary-entries").textContent = summary.entries;
    document.getElementById("summary-events").textContent = summary.events;
    document.getElementById("summary-teams").textContent = summary.teams;
    document.getElementById("summary-matches").textContent = summary.matches;
    document.getElementById("summary-year").textContent = settings.year;
    document.getElementById("summary-event").textContent =
        Obsidianscout.resolveEventKey(settings) || t("dashboard.not_set", "Not set");
    document.getElementById("summary-timezone").textContent = localizeTimezone(settings.timezone);
}

async function refreshSyncStatus(element) {
    if (!element) {
        return;
    }
    try {
        const status = await Obsidianscout.request("/api/integrations/sync/status");
        const parts = [t("dashboard.auto_sync_every", "Auto-sync every 7.5 min.")];
        if (status.syncInProgress) {
            parts.push(`${status.currentSyncLabel || "Sync"} running.`);
        }
        if (status.lastSyncAt) {
            const when = new Date(status.lastSyncAt);
            if (status.lastSyncTeams !== null && status.lastSyncMatches !== null && status.lastSyncTeamCount !== null) {
                parts.push(formatTemplate(
                    t("dashboard.sync_summary", "Synced {teams} teams and {matches} matches for {teamCount} team(s)"),
                    {
                        teams: status.lastSyncTeams,
                        matches: status.lastSyncMatches,
                        teamCount: status.lastSyncTeamCount
                    }
                ));
            } else if (status.lastSyncError === null) {
                parts.push(t("dashboard.sync_no_teams", "No teams configured for auto-sync."));
            }
            parts.push(`${t("dashboard.last_sync", "Last sync:")} ${when.toLocaleString()}.`);
        }
        if (status.lastSyncFailedTeams) {
            parts.push(formatTemplate(
                t("dashboard.sync_failed_teams", "{count} team sync(s) failed"),
                { count: status.lastSyncFailedTeams }
            ));
        }
        element.textContent = parts.join(" ");
    } catch (error) {
        element.textContent = t("dashboard.auto_sync_every", "Auto-sync every 7.5 min.");
    }
}

async function runSync(button, path, refreshAfter) {
    button.disabled = true;
    try {
        const response = await Obsidianscout.request(path, { method: "POST" });
        Obsidianscout.showToast(response.message || t("dashboard.sync_complete", "Sync complete"), "success");
        if (refreshAfter) {
            await refreshSummary();
            await refreshSyncStatus(document.getElementById("sync-status"));
        }
    } catch (error) {
        Obsidianscout.showToast(error.message || t("dashboard.sync_failed", "Sync failed"), "error");
    } finally {
        button.disabled = false;
    }
}
