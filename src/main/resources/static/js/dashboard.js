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

function cleanTeamNumber(raw) {
    return String(raw || "").replace(/^(frc|ftc)/i, "").trim();
}

let originalDashboardHTML = "";
let dashboardContainer = null;
let currentUser = null;
let currentEventKey = "";
let currentSettings = null;

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

    // Populate Hero Section User Metadata
    populateHeroUserMeta(me);

    dashboardContainer = document.getElementById("dashboard-dynamic-container");
    if (dashboardContainer) {
        originalDashboardHTML = dashboardContainer.innerHTML;
    }

    await loadDashboardData();

    // Wire quick team lookup search
    setupTeamLookup();

    // Setup background auto-sync refresh interval
    setInterval(async () => {
        if (document.hidden || !currentUser) {
            return;
        }
        const syncStatus = document.getElementById("sync-status");
        if (!syncStatus) return;
        try {
            await refreshSummary();
            await refreshSyncStatus();
        } catch (e) {
            console.warn("Background auto-sync update failed", e);
        }
    }, AUTO_SYNC_MS);
});

function populateHeroUserMeta(user) {
    const usernameEl = document.getElementById("dash-username");
    if (usernameEl) {
        usernameEl.textContent = user.username || "Scout";
    }

    const teamBadge = document.getElementById("dash-team-badge");
    if (teamBadge) {
        teamBadge.textContent = `Team ${user.teamNumber || ""}`;
    }

    const programBadge = document.getElementById("dash-program-badge");
    if (programBadge) {
        programBadge.textContent = user.program || "FRC";
    }

    const roleBadge = document.getElementById("dash-role-badge");
    if (roleBadge) {
        const roleLabel = user.role === "SUPERADMIN"
            ? "Site Admin"
            : (user.role ? user.role.charAt(0) + user.role.slice(1).toLowerCase() : "Scout");
        roleBadge.textContent = roleLabel;
    }
}

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

        const settings = settingsResponse.settings;
        currentSettings = settings;
        currentEventKey = Obsidianscout.resolveEventKey(settings) || "";

        // Re-wire team lookup form after innerHTML restoration
        setupTeamLookup();

        // Update active event badge in Hero header
        const eventBadge = document.getElementById("dash-active-event-badge");
        if (eventBadge) {
            if (currentEventKey) {
                eventBadge.textContent = `Event: ${currentEventKey.toUpperCase()}`;
            } else {
                eventBadge.textContent = t("dashboard.not_set", "No Event Set");
            }
        }

        // Populate KPI Metrics
        populateMetrics(summary);

        // Populate Event Context details
        populateEventContext(settings, currentEventKey);

        // Populate External Links for active event
        renderEventExternalLinks(settings, currentEventKey);

        // Load and render upcoming matches
        await loadUpcomingMatches(currentEventKey);

        // Setup Sync Station
        setupSyncStation(status);

        window.addEventListener("obsidianscout:languagechange", async () => {
            await refreshSummary();
            await refreshSyncStatus();
        });

    } catch (error) {
        console.error("Failed to load dashboard data:", error);
        Obsidianscout.showRetryButton(
            dashboardContainer,
            t("status.load_failed", t('dashboard.failed_to_load_dashboard_data', "Failed to load dashboard data: ")) + error.message,
            loadDashboardData
        );
    }
}

function populateMetrics(summary) {
    const setVal = (id, val) => {
        const el = document.getElementById(id);
        if (el) el.textContent = Number(val || 0).toLocaleString();
    };

    setVal("summary-entries", summary.entries);
    setVal("summary-pit-entries", summary.pitEntries);
    setVal("summary-qual-entries", summary.qualEntries);
    setVal("summary-teams", summary.teams);
    setVal("summary-matches", summary.matches);
    setVal("summary-events", summary.events);
}

function populateEventContext(settings, eventKey) {
    const yearEl = document.getElementById("summary-year");
    if (yearEl) yearEl.textContent = settings.year || "-";

    const eventEl = document.getElementById("summary-event");
    if (eventEl) eventEl.textContent = eventKey || t("dashboard.not_set", "Not set");

    const tzEl = document.getElementById("summary-timezone");
    if (tzEl) tzEl.textContent = localizeTimezone(settings.timezone) || "-";

    const sourceEl = document.getElementById("summary-source");
    if (sourceEl) {
        sourceEl.textContent = settings.preferredSource
            ? settings.preferredSource.toUpperCase()
            : (currentUser && currentUser.program === "FTC" ? "FTC Scout" : "The Blue Alliance");
    }
}

function renderEventExternalLinks(settings, eventKey) {
    const container = document.getElementById("event-external-links");
    if (!container) return;
    container.innerHTML = "";

    if (!eventKey) return;

    const isFtc = currentUser && currentUser.program === "FTC";

    if (isFtc) {
        // FTC Links
        const ftcScoutLink = document.createElement("a");
        ftcScoutLink.className = "event-link-pill";
        ftcScoutLink.href = `https://ftcscout.org/events/${settings.year || ""}/${eventKey}`;
        ftcScoutLink.target = "_blank";
        ftcScoutLink.rel = "noopener noreferrer";
        ftcScoutLink.textContent = "FTC Scout ↗";
        container.appendChild(ftcScoutLink);

        const firstLink = document.createElement("a");
        firstLink.className = "event-link-pill";
        firstLink.href = `https://ftc-events.firstinspires.org/${settings.year || ""}/${eventKey}`;
        firstLink.target = "_blank";
        firstLink.rel = "noopener noreferrer";
        firstLink.textContent = "FIRST FTC Events ↗";
        container.appendChild(firstLink);
    } else {
        // FRC Links
        const tbaLink = document.createElement("a");
        tbaLink.className = "event-link-pill";
        tbaLink.href = `https://www.thebluealliance.com/event/${eventKey}`;
        tbaLink.target = "_blank";
        tbaLink.rel = "noopener noreferrer";
        tbaLink.textContent = "The Blue Alliance ↗";
        container.appendChild(tbaLink);

        const statboticsLink = document.createElement("a");
        statboticsLink.className = "event-link-pill";
        statboticsLink.href = `https://www.statbotics.io/event/${eventKey}`;
        statboticsLink.target = "_blank";
        statboticsLink.rel = "noopener noreferrer";
        statboticsLink.textContent = "Statbotics EPA ↗";
        container.appendChild(statboticsLink);

        const eventCode = eventKey.replace(/^\d{4}/, "");
        if (eventCode) {
            const firstLink = document.createElement("a");
            firstLink.className = "event-link-pill";
            firstLink.href = `https://frc-events.firstinspires.org/${settings.year || ""}/${eventCode.toUpperCase()}`;
            firstLink.target = "_blank";
            firstLink.rel = "noopener noreferrer";
            firstLink.textContent = "FIRST FRC Events ↗";
            container.appendChild(firstLink);
        }
    }
}

function isEventPassedPlusOneDay(event) {
    if (!event || !event.endDate) return false;
    const datePart = event.endDate.split("T")[0]; // YYYY-MM-DD
    const parts = datePart.split("-").map(p => parseInt(p, 10));
    if (parts.length < 3 || isNaN(parts[0]) || isNaN(parts[1]) || isNaN(parts[2])) {
        return false;
    }
    // End of the day after endDate
    const cutoff = new Date(parts[0], parts[1] - 1, parts[2] + 1, 23, 59, 59, 999);
    return Date.now() > cutoff.getTime();
}

async function loadUpcomingMatches(eventKey) {
    const container = document.getElementById("dash-matches-container");
    if (!container) return;

    if (!eventKey) {
        container.innerHTML = `<div class="dash-matches-empty">${t("dashboard.no_upcoming_matches", "No upcoming matches")}</div>`;
        return;
    }

    try {
        // Check event date if available
        let eventRecord = null;
        try {
            eventRecord = await Obsidianscout.request(`/api/events?eventKey=${encodeURIComponent(eventKey)}`);
        } catch (e) {
            console.debug("Could not fetch event record for date check:", e);
        }

        if (isEventPassedPlusOneDay(eventRecord)) {
            container.innerHTML = `
                <div class="dash-matches-empty">
                    <p style="margin-bottom: 8px;">${t("dashboard.no_upcoming_matches", "No upcoming matches")}</p>
                </div>
            `;
            return;
        }

        const matches = await Obsidianscout.request(`/api/matches?eventKey=${encodeURIComponent(eventKey)}`);
        if (!matches || matches.length === 0) {
            container.innerHTML = `
                <div class="dash-matches-empty">
                    <p style="margin-bottom: 8px;">${t("dashboard.no_upcoming_matches", "No upcoming matches")}</p>
                    ${Obsidianscout.isAdmin(currentUser.role) ? '<button class="btn btn-sm" id="btn-sync-empty-matches" type="button">Sync Matches</button>' : ''}
                </div>
            `;
            const syncBtn = document.getElementById("btn-sync-empty-matches");
            if (syncBtn) {
                syncBtn.addEventListener("click", () => runSync(syncBtn, "/api/integrations/sync/event", true));
            }
            return;
        }

        const ONE_DAY_MS = 24 * 60 * 60 * 1000;
        const nowMs = Date.now();

        function hasApiScore(m) {
            return (m.redScore !== null && m.redScore !== undefined && m.redScore >= 0 &&
                    m.blueScore !== null && m.blueScore !== undefined && m.blueScore >= 0);
        }

        // Filter for matches that the API does NOT have a score for, and check scheduled dates
        const upcomingMatches = matches.filter(m => {
            // Exclude matches that already have an API score
            if (hasApiScore(m)) return false;

            // Check scheduled dates: if a match was scheduled more than 1 day in the past, it has already passed
            if (m.scheduledTime && m.scheduledTime > 0) {
                const matchTimeMs = m.scheduledTime * 1000;
                if (nowMs - matchTimeMs > ONE_DAY_MS) {
                    return false;
                }
            }
            return true;
        });

        if (upcomingMatches.length === 0) {
            container.innerHTML = `
                <div class="dash-matches-empty">
                    <p style="margin-bottom: 8px;">${t("dashboard.no_upcoming_matches", "No upcoming matches")}</p>
                    ${Obsidianscout.isAdmin(currentUser.role) ? '<button class="btn btn-sm" id="btn-sync-empty-matches" type="button">Sync Matches</button>' : ''}
                </div>
            `;
            const syncBtn = document.getElementById("btn-sync-empty-matches");
            if (syncBtn) {
                syncBtn.addEventListener("click", () => runSync(syncBtn, "/api/integrations/sync/event", true));
            }
            return;
        }

        // Sort matches by scheduledTime (if available) then compLevel and matchNumber
        const compOrder = { "qm": 1, "qf": 2, "sf": 3, "f": 4 };
        const sorted = [...upcomingMatches].sort((a, b) => {
            const timeA = (a.scheduledTime && a.scheduledTime > 0) ? a.scheduledTime : null;
            const timeB = (b.scheduledTime && b.scheduledTime > 0) ? b.scheduledTime : null;

            if (timeA && timeB) {
                if (timeA !== timeB) return timeA - timeB;
            } else if (timeA && !timeB) {
                return -1;
            } else if (!timeA && timeB) {
                return 1;
            }

            const levelA = compOrder[a.compLevel?.toLowerCase()] || 99;
            const levelB = compOrder[b.compLevel?.toLowerCase()] || 99;
            if (levelA !== levelB) return levelA - levelB;

            const setA = a.setNumber || 0;
            const setB = b.setNumber || 0;
            if (setA !== setB) return setA - setB;

            return (a.matchNumber || 0) - (b.matchNumber || 0);
        });

        // Take up to 5 upcoming matches
        const displayMatches = sorted.slice(0, 5);

        container.innerHTML = "";
        displayMatches.forEach(match => {
            const card = document.createElement("div");
            card.className = "dash-match-card";

            const label = match.label || (match.compLevel ? `${match.compLevel.toUpperCase()} ${match.matchNumber || ""}` : `Match ${match.matchNumber || ""}`);

            let timeStr = "";
            if (match.scheduledTime) {
                const d = new Date(match.scheduledTime * 1000);
                timeStr = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }

            // Build Red Alliance HTML
            const redTeamsHtml = (match.redTeams || []).map(raw => {
                const num = cleanTeamNumber(raw);
                const eventParam = eventKey ? `&eventKey=${encodeURIComponent(eventKey)}` : "";
                return `<a href="/team?teamNumber=${encodeURIComponent(num)}${eventParam}" class="dash-team-pill" title="Team ${num}">${num}</a>`;
            }).join("");

            // Build Blue Alliance HTML
            const blueTeamsHtml = (match.blueTeams || []).map(raw => {
                const num = cleanTeamNumber(raw);
                const eventParam = eventKey ? `&eventKey=${encodeURIComponent(eventKey)}` : "";
                return `<a href="/team?teamNumber=${encodeURIComponent(num)}${eventParam}" class="dash-team-pill" title="Team ${num}">${num}</a>`;
            }).join("");

            const matchNumParam = match.matchNumber ? `&match=${match.matchNumber}` : "";
            const scoutHref = `/scout?event=${encodeURIComponent(eventKey)}${matchNumParam}`;
            const matchKeyVal = match.matchKey || match.matchNumber || "";
            const predictHref = `/predictor?match=${encodeURIComponent(matchKeyVal)}&matchKey=${encodeURIComponent(matchKeyVal)}&eventKey=${encodeURIComponent(eventKey)}`;

            card.innerHTML = `
                <div class="dash-match-meta">
                    <span class="dash-match-name">${label}</span>
                    <span class="dash-match-time">${timeStr || "Scheduled"}</span>
                </div>
                <div class="dash-match-alliances">
                    <div class="dash-alliance-group red">
                        <span>R:</span> ${redTeamsHtml || "—"}
                    </div>
                    <div class="dash-alliance-group blue">
                        <span>B:</span> ${blueTeamsHtml || "—"}
                    </div>
                </div>
                <div class="dash-match-actions">
                    <a href="${scoutHref}" class="dash-btn-mini" title="Scout match ${label}">${t("dashboard.scout_match_action", "Scout")}</a>
                    <a href="${predictHref}" class="dash-btn-mini" title="Predict match ${label}">${t("dashboard.predict_match_action", "Predict")}</a>
                </div>
            `;

            container.appendChild(card);
        });

    } catch (e) {
        console.warn("Failed to load upcoming matches preview:", e);
        container.innerHTML = `<div class="dash-matches-empty">${t("dashboard.no_upcoming_matches", "No upcoming matches")}</div>`;
    }
}

function setupTeamLookup() {
    const form = document.getElementById("dash-team-search-form");
    if (!form || form.dataset.bound) return;
    form.dataset.bound = "true";

    form.addEventListener("submit", (e) => {
        e.preventDefault();
        const input = document.getElementById("dash-team-input");
        const val = input ? input.value.trim() : "";
        if (!val) return;
        const num = parseInt(val, 10);
        if (isNaN(num)) return;
        const eventParam = currentEventKey ? `&eventKey=${encodeURIComponent(currentEventKey)}` : "";
        window.location.href = `/team?teamNumber=${encodeURIComponent(num)}${eventParam}`;
    });
}

function setupSyncStation(status) {
    const syncAll = document.getElementById("sync-all");
    const syncEvent = document.getElementById("sync-event");
    const syncStats = document.getElementById("sync-stats");
    const syncStatus = document.getElementById("sync-status");

    const isAdmin = currentUser && Obsidianscout.isAdmin(currentUser.role);

    if (!isAdmin) {
        if (syncAll) syncAll.disabled = true;
        if (syncEvent) syncEvent.disabled = true;
        if (syncStats) syncStats.disabled = true;
        if (syncStatus) {
            syncStatus.textContent = t("dashboard.admin_sync_required", "Admin access required to sync.");
        }
    } else {
        if (status) {
            applySyncStatusToDOM(status);
        }
        if (syncAll) {
            syncAll.addEventListener("click", () =>
                runSync(syncAll, "/api/integrations/sync/all", true)
            );
        }
        if (syncEvent) {
            syncEvent.addEventListener("click", () =>
                runSync(syncEvent, "/api/integrations/sync/event", true)
            );
        }
        if (syncStats) {
            syncStats.addEventListener("click", () =>
                runSync(syncStats, "/api/integrations/sync/stats", true)
            );
        }
    }
}

async function refreshSummary() {
    try {
        const [summary, settingsResponse] = await Promise.all([
            Obsidianscout.request("/api/summary"),
            Obsidianscout.request("/api/settings")
        ]);
        const settings = settingsResponse.settings;

        populateMetrics(summary);
        populateEventContext(settings, Obsidianscout.resolveEventKey(settings));
    } catch (e) {
        console.warn("Failed to refresh summary:", e);
    }
}

async function refreshSyncStatus() {
    try {
        const status = await Obsidianscout.request("/api/integrations/sync/status");
        applySyncStatusToDOM(status);
    } catch (e) {
        const syncStatus = document.getElementById("sync-status");
        if (syncStatus) {
            syncStatus.textContent = t("dashboard.auto_sync_every", "Auto-sync every 7.5 min.");
        }
    }
}

function applySyncStatusToDOM(status) {
    const statusEl = document.getElementById("sync-status");
    const subtitleEl = document.getElementById("sync-subtitle");
    const indicatorEl = document.getElementById("dash-sync-indicator");

    if (!statusEl) return;

    if (indicatorEl) {
        indicatorEl.className = "dash-sync-indicator";
        if (status.syncInProgress) {
            indicatorEl.classList.add("syncing");
        } else if (status.lastSyncFailedTeams && status.lastSyncFailedTeams > 0) {
            indicatorEl.classList.add("error");
        }
    }

    if (status.syncInProgress) {
        statusEl.textContent = `${status.currentSyncLabel || "Sync"} in progress...`;
        if (subtitleEl) subtitleEl.textContent = "Pulling live match and team data";
        return;
    }

    if (status.lastSyncAt) {
        const when = new Date(status.lastSyncAt);
        const timeAgoStr = when.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        statusEl.textContent = `${t("dashboard.last_sync", "Last sync:")} ${timeAgoStr} (${when.toLocaleDateString()})`;

        if (status.lastSyncTeams !== null && status.lastSyncMatches !== null && status.lastSyncTeamCount !== null) {
            if (subtitleEl) {
                subtitleEl.textContent = formatTemplate(
                    t("dashboard.sync_summary", "Synced {teams} teams and {matches} matches for {teamCount} team(s)"),
                    {
                        teams: status.lastSyncTeams,
                        matches: status.lastSyncMatches,
                        teamCount: status.lastSyncTeamCount
                    }
                );
            }
        } else if (status.lastSyncError === null) {
            if (subtitleEl) subtitleEl.textContent = t("dashboard.sync_no_teams", "No teams configured for auto-sync.");
        }
    } else {
        statusEl.textContent = t("dashboard.auto_sync_every", "Auto-sync every 7.5 min.");
        if (subtitleEl) subtitleEl.textContent = "Background worker running";
    }

    if (status.lastSyncFailedTeams && status.lastSyncFailedTeams > 0 && subtitleEl) {
        subtitleEl.textContent += ` • ${formatTemplate(t("dashboard.sync_failed_teams", "{count} team sync(s) failed"), { count: status.lastSyncFailedTeams })}`;
    }
}

async function runSync(button, path, refreshAfter) {
    button.disabled = true;
    const originalText = button.textContent;
    button.textContent = "Syncing...";

    const indicatorEl = document.getElementById("dash-sync-indicator");
    if (indicatorEl) indicatorEl.classList.add("syncing");

    try {
        const response = await Obsidianscout.request(path, { method: "POST" });
        Obsidianscout.showToast(response.message || t("dashboard.sync_complete", "Sync complete"), "success");
        if (refreshAfter) {
            await refreshSummary();
            await refreshSyncStatus();
            if (currentEventKey) {
                await loadUpcomingMatches(currentEventKey);
            }
        }
    } catch (error) {
        Obsidianscout.showToast(error.message || t("dashboard.sync_failed", "Sync failed"), "error");
    } finally {
        button.disabled = false;
        button.textContent = originalText;
        if (indicatorEl) indicatorEl.classList.remove("syncing");
    }
}
