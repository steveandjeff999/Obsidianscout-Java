const AUTO_SYNC_MS = 7.5 * 60 * 1000;

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    await refreshSummary();

    const syncTeamsMatches = document.getElementById("sync-teams-matches");
    const syncEvents = document.getElementById("sync-events");
    const syncStats = document.getElementById("sync-stats");
    const syncStatus = document.getElementById("sync-status");

    if (!Obsidianscout.isAdmin(me.role)) {
        syncTeamsMatches.disabled = true;
        syncEvents.disabled = true;
        syncStats.disabled = true;
        if (syncStatus) {
            syncStatus.textContent = "Admin access required to sync.";
        }
        return;
    }

    await refreshSyncStatus(syncStatus);

    syncTeamsMatches.addEventListener("click", () =>
        runSync(syncTeamsMatches, "/api/integrations/sync/event", true)
    );
    syncEvents.addEventListener("click", () =>
        runSync(syncEvents, "/api/integrations/sync/events", true)
    );
    syncStats.addEventListener("click", () =>
        runSync(syncStats, "/api/integrations/sync/stats", true)
    );

    setInterval(async () => {
        if (document.hidden) {
            return;
        }
        await refreshSummary();
        await refreshSyncStatus(syncStatus);
    }, AUTO_SYNC_MS);
});

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
        Obsidianscout.resolveEventKey(settings) || "Not set";
    document.getElementById("summary-timezone").textContent = settings.timezone;
}

async function refreshSyncStatus(element) {
    if (!element) {
        return;
    }
    try {
        const status = await Obsidianscout.request("/api/integrations/sync/status");
        const parts = ["Auto-sync every 7.5 min."];
        if (status.lastSyncAt) {
            const when = new Date(status.lastSyncAt);
            parts.push(`Last sync: ${when.toLocaleString()}.`);
        }
        if (status.lastSyncSummary) {
            parts.push(status.lastSyncSummary);
        }
        if (status.lastSyncError) {
            parts.push(status.lastSyncError);
        }
        element.textContent = parts.join(" ");
    } catch (error) {
        element.textContent = "Auto-sync every 7.5 min.";
    }
}

async function runSync(button, path, refreshAfter) {
    button.disabled = true;
    try {
        await Obsidianscout.request(path, { method: "POST" });
        Obsidianscout.showToast("Sync complete", "success");
        if (refreshAfter) {
            await refreshSummary();
            await refreshSyncStatus(document.getElementById("sync-status"));
        }
    } catch (error) {
        Obsidianscout.showToast(error.message || "Sync failed", "error");
    } finally {
        button.disabled = false;
    }
}
