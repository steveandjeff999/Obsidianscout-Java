function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

let currentTeams = [];
let currentUser = null;
let currentEventKey = "";
let currentMetric = "scouted"; // 'scouted', 'epa', 'opr', 'all'
let activeSortMetric = "scouted"; // actual column we are sorting by ('scouted', 'epa', 'opr')

let originalCardHTML = "";
let card = null;

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

    card = document.querySelector(".card");
    originalCardHTML = card.innerHTML;

    await initRankingsPage();
});

let appSettings = null;

async function initRankingsPage() {
    Obsidianscout.showLoadingSpinner(card, "Loading rankings...");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        appSettings = settings;
        currentEventKey = Obsidianscout.resolveEventKey(settings);

        // Restore HTML
        card.innerHTML = originalCardHTML;

        const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function')
            ? Obsidianscout.getProgram() === "FTC"
            : (settings && settings.program === "FTC");
        const effectiveUseEpa = !isFtc && settings.useStatboticsEpa;
        const effectiveUseExp = !isFtc && settings.useMatch13Exp;
        const effectiveUseOpr = settings.useTbaOpr;

        // Metric selector options filtering based on team settings
        const metricSelect = document.getElementById("metric-select");
        if (!effectiveUseEpa) {
            const optEpa = metricSelect.querySelector('option[value="epa"]');
            if (optEpa) optEpa.remove();
        }
        if (!effectiveUseExp) {
            const optExp = metricSelect.querySelector('option[value="exp"]');
            if (optExp) optExp.remove();
        }
        if (!effectiveUseOpr) {
            const optOpr = metricSelect.querySelector('option[value="opr"]');
            if (optOpr) optOpr.remove();
        } else if (isFtc) {
            const optOpr = metricSelect.querySelector('option[value="opr"]');
            if (optOpr) optOpr.textContent = t('predictor.ftcscout_opr', "FTC Scout OPR");
        }
        const activeCount = (effectiveUseEpa ? 1 : 0) + (effectiveUseExp ? 1 : 0) + (effectiveUseOpr ? 1 : 0);
        if (activeCount === 0) {
            const optAll = metricSelect.querySelector('option[value="all"]');
            if (optAll) optAll.remove();
        }
        if ((currentMetric === "epa" && !effectiveUseEpa) ||
            (currentMetric === "exp" && !effectiveUseExp) ||
            (currentMetric === "opr" && !effectiveUseOpr) ||
            (currentMetric === "all" && activeCount === 0)) {
            currentMetric = "scouted";
            activeSortMetric = "scouted";
        }
        metricSelect.value = currentMetric;

        // Populate event filter select dropdown
        const eventFilter = document.getElementById("event-filter");
        const events = await Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`);
        eventFilter.innerHTML = "";
        events.forEach(e => {
            const opt = document.createElement("option");
            opt.value = e.eventKey;
            opt.textContent = `${e.name} (${e.eventKey})`;
            if (e.eventKey === currentEventKey) {
                opt.selected = true;
            }
            eventFilter.appendChild(opt);
        });

        eventFilter.addEventListener("change", async () => {
            currentEventKey = eventFilter.value;
            await loadRankings();
        });

        // Metric selector listener
        metricSelect.addEventListener("change", () => {
            currentMetric = metricSelect.value;
            if (currentMetric !== "all") {
                activeSortMetric = currentMetric;
            }
            renderTable();
        });

        // Header clicks for sorting
        document.getElementById("header-scouted").addEventListener("click", () => {
            sortByColumn("scouted");
        });
        const headerOprEl = document.getElementById("header-opr");
        if (headerOprEl) {
            headerOprEl.addEventListener("click", () => {
                if (appSettings && appSettings.useTbaOpr) sortByColumn("opr");
            });
        }
        const headerEpaEl = document.getElementById("header-epa");
        if (headerEpaEl) {
            headerEpaEl.addEventListener("click", () => {
                if (appSettings && appSettings.useStatboticsEpa) sortByColumn("epa");
            });
        }
        const headerExpEl = document.getElementById("header-exp");
        if (headerExpEl) {
            headerExpEl.addEventListener("click", () => {
                if (appSettings && appSettings.useMatch13Exp) sortByColumn("exp");
            });
        }

        await loadRankings();

    } catch (error) {
        console.error("Failed to load rankings page data:", error);
        Obsidianscout.showRetryButton(card, "Failed to load rankings data: " + error.message, initRankingsPage);
    }
}

async function loadRankings() {
    if (!currentEventKey) {
        const table = document.getElementById("rankings-table");
        const body = table.querySelector("tbody");
        body.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--muted); padding: 24px;">No event selected</td></tr>';
        return;
    }

    const table = document.getElementById("rankings-table");
    const body = table.querySelector("tbody");
    body.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 24px;"><div class="spinner" style="margin: 0 auto 12px; width: 32px; height: 32px;"></div><div>Loading teams...</div></td></tr>';

    try {
        const teams = await Obsidianscout.request(`/api/teams?eventKey=${currentEventKey}`);
        currentTeams = teams;
        renderTable();
    } catch (error) {
        console.error("Failed to load teams:", error);
        Obsidianscout.showToast("Failed to load teams for event", "error");
        body.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--muted); padding: 24px;">Failed to load teams.</td></tr>';
    }
}

function sortByColumn(metric) {
    activeSortMetric = metric;
    // If selecting via header click and currently not showing all, we can update the select dropdown
    const metricSelect = document.getElementById("metric-select");
    if (currentMetric !== "all") {
        currentMetric = metric;
        metricSelect.value = metric;
    }
    renderTable();
}

function renderTable() {
    const table = document.getElementById("rankings-table");
    const body = table.querySelector("tbody");
    body.innerHTML = "";

    if (!currentTeams || currentTeams.length === 0) {
        body.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--muted); padding: 24px;">No teams found for this event.</td></tr>';
        return;
    }

    // Sort teams based on activeSortMetric
    const sortedTeams = [...currentTeams].sort((a, b) => {
        let valA = 0;
        let valB = 0;

        if (activeSortMetric === "scouted") {
            valA = a.averagePoints !== null && a.averagePoints !== undefined ? a.averagePoints : -999999;
            valB = b.averagePoints !== null && b.averagePoints !== undefined ? b.averagePoints : -999999;
        } else if (activeSortMetric === "epa") {
            valA = a.epa !== null && a.epa !== undefined ? a.epa : -999999;
            valB = b.epa !== null && b.epa !== undefined ? b.epa : -999999;
        } else if (activeSortMetric === "exp") {
            valA = a.exp !== null && a.exp !== undefined ? a.exp : -999999;
            valB = b.exp !== null && b.exp !== undefined ? b.exp : -999999;
        } else if (activeSortMetric === "opr") {
            valA = a.opr !== null && a.opr !== undefined ? a.opr : -999999;
            valB = b.opr !== null && b.opr !== undefined ? b.opr : -999999;
        }

        return valB - valA; // Descending
    });

    // Update headers visibility & active indicators
    const headerScouted = document.getElementById("header-scouted");
    const headerOpr = document.getElementById("header-opr");
    const headerEpa = document.getElementById("header-epa");
    const headerExp = document.getElementById("header-exp");

    // Clear active sorting classes/indicators
    [headerScouted, headerOpr, headerEpa, headerExp].filter(Boolean).forEach(h => {
        h.style.fontWeight = "normal";
        h.style.textDecoration = "none";
        const indicator = h.querySelector(".sort-indicator");
        if (indicator) indicator.remove();
    });

    // Show/hide columns based on currentMetric selection and appSettings
    if (currentMetric === "scouted") {
        headerScouted.style.display = "";
        if (headerOpr) headerOpr.style.display = "none";
        if (headerEpa) headerEpa.style.display = "none";
        if (headerExp) headerExp.style.display = "none";
    } else if (currentMetric === "epa") {
        headerScouted.style.display = "none";
        if (headerOpr) headerOpr.style.display = "none";
        if (headerEpa) headerEpa.style.display = "";
        if (headerExp) headerExp.style.display = "none";
    } else if (currentMetric === "exp") {
        headerScouted.style.display = "none";
        if (headerOpr) headerOpr.style.display = "none";
        if (headerEpa) headerEpa.style.display = "none";
        if (headerExp) headerExp.style.display = "";
    } else if (currentMetric === "opr") {
        headerScouted.style.display = "none";
        if (headerOpr) headerOpr.style.display = "";
        if (headerEpa) headerEpa.style.display = "none";
        if (headerExp) headerExp.style.display = "none";
    } else {
        // all
        headerScouted.style.display = "";
        if (headerOpr) headerOpr.style.display = "";
        if (headerEpa) headerEpa.style.display = "";
        if (headerExp) headerExp.style.display = "";
    }

    const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function')
        ? Obsidianscout.getProgram() === "FTC"
        : (appSettings && appSettings.program === "FTC");
    const effectiveUseEpa = !isFtc && appSettings && appSettings.useStatboticsEpa;
    const effectiveUseExp = !isFtc && appSettings && appSettings.useMatch13Exp;
    const effectiveUseOpr = appSettings && appSettings.useTbaOpr;

    if (headerOpr && isFtc) {
        headerOpr.textContent = "FTC OPR";
    }

    if (!effectiveUseOpr && headerOpr) {
        headerOpr.style.display = "none";
    }
    if (!effectiveUseEpa && headerEpa) {
        headerEpa.style.display = "none";
    }
    if (!effectiveUseExp && headerExp) {
        headerExp.style.display = "none";
    }

    // Highlight active sorting column
    let activeHeader = null;
    if (activeSortMetric === "scouted") activeHeader = headerScouted;
    else if (activeSortMetric === "opr" && effectiveUseOpr) activeHeader = headerOpr;
    else if (activeSortMetric === "epa" && effectiveUseEpa) activeHeader = headerEpa;
    else if (activeSortMetric === "exp" && effectiveUseExp) activeHeader = headerExp;

    if (activeHeader && activeHeader.style.display !== "none") {
        activeHeader.style.fontWeight = "bold";
        activeHeader.innerHTML = activeHeader.textContent.replace(" ▼", "") + ' <span class="sort-indicator">▼</span>';
    }

    // Populate rows
    sortedTeams.forEach((team, index) => {
        const row = document.createElement("tr");
        const displayNum = Obsidianscout.formatTeam(team.teamKey, team.teamNumber);
        
        let scoutedCell = `<td class="col-scouted">${team.averagePoints !== null && team.averagePoints !== undefined ? team.averagePoints.toFixed(1) : ""}</td>`;
        let oprCell = `<td class="col-opr">${team.opr !== null ? team.opr.toFixed(2) : ""}</td>`;
        let epaCell = `<td class="col-epa">${team.epa !== null ? team.epa.toFixed(2) : ""}</td>`;
        let expCell = `<td class="col-exp">${team.exp !== null && team.exp !== undefined ? team.exp.toFixed(2) : ""}</td>`;

        if (currentMetric === "scouted") {
            oprCell = "";
            epaCell = "";
            expCell = "";
        } else if (currentMetric === "epa") {
            scoutedCell = "";
            oprCell = "";
            expCell = "";
        } else if (currentMetric === "exp") {
            scoutedCell = "";
            oprCell = "";
            epaCell = "";
        } else if (currentMetric === "opr") {
            scoutedCell = "";
            epaCell = "";
            expCell = "";
        }

        if (!effectiveUseOpr) {
            oprCell = "";
        }
        if (!effectiveUseEpa) {
            epaCell = "";
        }
        if (!effectiveUseExp) {
            expCell = "";
        }

        row.innerHTML = `
            <td><strong>${index + 1}</strong></td>
            <td><a href="/team?teamNumber=${team.teamNumber}&eventKey=${currentEventKey}" class="team-profile-link">${displayNum}</a></td>
            <td><a href="/team?teamNumber=${team.teamNumber}&eventKey=${currentEventKey}" class="team-profile-link">${team.nickname || team.name || ""}</a></td>
            ${scoutedCell}
            ${oprCell}
            ${epaCell}
            ${expCell}
        `;
        body.appendChild(row);
    });
}
