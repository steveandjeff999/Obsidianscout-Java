let currentValidationData = null;
let currentUser = null;
let currentTab = "matches-view";

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

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

    setupModalListeners();
    setupTabListeners();
    await loadEvents();
});

function setupModalListeners() {
    const modal = document.getElementById("validation-modal");
    const closeBtn = document.getElementById("modal-close-btn");
    if (modal && closeBtn) {
        closeBtn.addEventListener("click", () => modal.classList.remove("active"));
        modal.addEventListener("click", (e) => {
            if (e.target === modal) modal.classList.remove("active");
        });
        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape" && modal.classList.contains("active")) {
                modal.classList.remove("active");
            }
        });
    }
}

function setupTabListeners() {
    const tabs = document.querySelectorAll(".tab[data-tab]");
    tabs.forEach(tab => {
        tab.addEventListener("click", () => {
            tabs.forEach(t => t.classList.remove("active"));
            tab.classList.add("active");
            currentTab = tab.getAttribute("data-tab");

            const tabMatches = document.getElementById("tab-matches-view");
            const tabTeams = document.getElementById("tab-teams-view");

            if (currentTab === "matches-view") {
                tabMatches.classList.remove("hidden");
                tabTeams.classList.add("hidden");
            } else {
                tabMatches.classList.add("hidden");
                tabTeams.classList.remove("hidden");
            }
        });
    });
}

async function loadEvents() {
    const eventSelect = document.getElementById("event-select");
    if (!eventSelect) return;

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        const defaultEventKey = Obsidianscout.resolveEventKey(settings);

        const events = await Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`);
        
        eventSelect.innerHTML = '<option value="">-- Select an Event --</option>';
        if (!events || events.length === 0) {
            eventSelect.innerHTML = '<option value="">No events synced in database</option>';
            return;
        }

        events.forEach((event) => {
            const opt = document.createElement("option");
            opt.value = event.eventKey;
            opt.textContent = `${event.name} (${event.eventKey.toUpperCase()})`;
            if (defaultEventKey && event.eventKey.toLowerCase() === defaultEventKey.toLowerCase()) {
                opt.selected = true;
            }
            eventSelect.appendChild(opt);
        });

        // Event listeners
        eventSelect.addEventListener("change", fetchValidationData);
        document.getElementById("threshold-select").addEventListener("change", fetchValidationData);
        document.getElementById("force-prescout").addEventListener("change", fetchValidationData);
        document.getElementById("filter-status").addEventListener("change", renderValidationViews);
        document.getElementById("search-input").addEventListener("input", renderValidationViews);

        if (eventSelect.value) {
            await fetchValidationData();
        }
    } catch (error) {
        console.error("Failed to load events:", error);
        Obsidianscout.showToast("Failed to load events: " + error.message, "error");
    }
}

async function fetchValidationData() {
    const eventSelect = document.getElementById("event-select");
    const emptyState = document.getElementById("validation-empty-state");
    const loadingState = document.getElementById("validation-loading-state");
    const validationCard = document.getElementById("validation-card");

    const eventKey = eventSelect.value;
    if (!eventKey) {
        emptyState.classList.remove("hidden");
        validationCard.classList.add("hidden");
        loadingState.classList.add("hidden");
        resetKpis();
        return;
    }

    const threshold = document.getElementById("threshold-select").value || "15";
    const forcePrescout = document.getElementById("force-prescout").checked;

    emptyState.classList.add("hidden");
    validationCard.classList.add("hidden");
    loadingState.classList.remove("hidden");

    try {
        const url = `/api/validation?eventKey=${encodeURIComponent(eventKey)}&threshold=${threshold}&forcePrescout=${forcePrescout}`;
        const data = await Obsidianscout.request(url);
        currentValidationData = data;

        updateKpis(data);
        renderValidationViews();

        loadingState.classList.add("hidden");
        validationCard.classList.remove("hidden");
    } catch (error) {
        console.error("Failed to fetch validation data:", error);
        loadingState.classList.add("hidden");
        emptyState.classList.remove("hidden");
        Obsidianscout.showToast("Validation check failed: " + error.message, "error");
    }
}

function resetKpis() {
    document.getElementById("kpi-total-matches").textContent = "-";
    document.getElementById("kpi-fully-scouted").textContent = "-";
    document.getElementById("kpi-incomplete").textContent = "-";
    document.getElementById("kpi-match-anomalies").textContent = "-";
    document.getElementById("kpi-team-anomalies").textContent = "-";
}

function updateKpis(data) {
    document.getElementById("kpi-total-matches").textContent = data.totalMatches || 0;
    document.getElementById("kpi-fully-scouted").textContent = data.fullyScoutedMatches || 0;
    document.getElementById("kpi-incomplete").textContent = data.incompleteMatches || 0;
    document.getElementById("kpi-match-anomalies").textContent = data.matchesWithAnomalies || 0;
    document.getElementById("kpi-team-anomalies").textContent = data.teamsWithAnomalies || 0;
}

function renderValidationViews() {
    if (!currentValidationData) return;
    renderMatchesTable();
    renderTeamsTable();
}

function renderMatchesTable() {
    const tbody = document.getElementById("matches-validation-tbody");
    const countBadge = document.getElementById("matches-count-badge");
    const filterStatus = document.getElementById("filter-status").value;
    const query = (document.getElementById("search-input").value || "").trim().toLowerCase();

    tbody.innerHTML = "";

    let matches = currentValidationData.matches || [];

    // Filter by status
    if (filterStatus === "anomalies") {
        matches = matches.filter(m => m.hasAnomaly);
    } else if (filterStatus === "incomplete") {
        matches = matches.filter(m => !m.isFullyScouted && (m.redAlliance.scoutedTeams.length > 0 || m.blueAlliance.scoutedTeams.length > 0));
    } else if (filterStatus === "complete") {
        matches = matches.filter(m => m.isFullyScouted);
    }

    // Filter by search query
    if (query) {
        matches = matches.filter(m => {
            const matchLabel = (m.label || "").toLowerCase();
            const matchKey = (m.matchKey || "").toLowerCase();
            const redTeams = m.redAlliance.teams.map(t => t.toString());
            const blueTeams = m.blueAlliance.teams.map(t => t.toString());
            return matchLabel.includes(query) ||
                matchKey.includes(query) ||
                redTeams.some(t => t.includes(query)) ||
                blueTeams.some(t => t.includes(query));
        });
    }

    countBadge.textContent = `${matches.length} matches`;

    if (matches.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--text-muted, #94a3b8); padding: 32px 16px;">No matches matching filter criteria</td></tr>`;
        return;
    }

    matches.forEach(match => {
        const tr = document.createElement("tr");

        // Match Column
        const matchTd = document.createElement("td");
        matchTd.innerHTML = `
            <div style="font-weight: 700; font-size: 0.95rem;">${escapeHtml(match.label)}</div>
            <div class="metric-subtext">${escapeHtml(match.matchKey)}</div>
        `;
        tr.appendChild(matchTd);

        // Red Alliance Column
        const redTd = document.createElement("td");
        redTd.appendChild(createAllianceBox(match.redAlliance, match.matchKey));
        tr.appendChild(redTd);

        // Blue Alliance Column
        const blueTd = document.createElement("td");
        blueTd.appendChild(createAllianceBox(match.blueAlliance, match.matchKey));
        tr.appendChild(blueTd);

        // Status Column
        const statusTd = document.createElement("td");
        statusTd.appendChild(createMatchStatusBadge(match));
        tr.appendChild(statusTd);

        // Actions Column
        const actionsTd = document.createElement("td");
        actionsTd.style.textAlign = "center";
        const btnInspect = document.createElement("button");
        btnInspect.className = "btn ghost";
        btnInspect.style.padding = "4px 10px";
        btnInspect.style.fontSize = "0.8rem";
        btnInspect.textContent = "Inspect";
        btnInspect.addEventListener("click", () => openMatchDetailsModal(match));
        actionsTd.appendChild(btnInspect);
        tr.appendChild(actionsTd);

        tbody.appendChild(tr);
    });
}

function createAllianceBox(alliance, matchKey) {
    const box = document.createElement("div");
    box.className = `alliance-box ${alliance.allianceColor}`;

    // Team chips
    const teamsRow = document.createElement("div");
    teamsRow.className = "alliance-teams-row";

    alliance.teams.forEach(teamNum => {
        const isScouted = alliance.scoutedTeams.includes(teamNum);
        const chip = document.createElement("span");
        chip.className = `team-chip ${isScouted ? 'scouted' : 'missing'}`;
        chip.innerHTML = isScouted
            ? `✓ ${teamNum}`
            : `⚠️ ${teamNum}`;
        chip.title = isScouted ? `Team ${teamNum} scouted` : `Team ${teamNum} NOT scouted yet`;
        teamsRow.appendChild(chip);
    });
    box.appendChild(teamsRow);

    // Score comparison
    const scoreRow = document.createElement("div");
    scoreRow.className = "score-comparison-row";

    const scoutedStat = document.createElement("div");
    scoutedStat.className = "score-stat";
    scoutedStat.innerHTML = `<span class="score-label">Scouted:</span> <span class="score-num">${alliance.scoutedScoreSum}</span>`;
    scoreRow.appendChild(scoutedStat);

    if (alliance.actualScore !== null) {
        const actualStat = document.createElement("div");
        actualStat.className = "score-stat";
        actualStat.innerHTML = `<span class="score-label">Official:</span> <span class="score-num">${alliance.actualScore}</span>`;
        scoreRow.appendChild(actualStat);

        // Delta chip
        if (alliance.scoreDiff !== null) {
            const deltaChip = document.createElement("span");
            const diff = alliance.scoreDiff;
            const sign = diff > 0 ? `+${diff}` : `${diff}`;
            const isAnomaly = alliance.isAnomaly && alliance.isFullyScouted;

            deltaChip.className = `delta-chip ${isAnomaly ? 'anomaly' : (Math.abs(diff) > 8 ? 'warning' : 'normal')}`;
            deltaChip.textContent = `Δ ${sign}`;
            deltaChip.title = `Difference: Scouted sum ${alliance.scoutedScoreSum} vs Official ${alliance.actualScore}`;
            scoreRow.appendChild(deltaChip);
        }
    } else {
        const unplayedStat = document.createElement("span");
        unplayedStat.className = "metric-subtext";
        unplayedStat.textContent = "Unplayed / No API Score";
        scoreRow.appendChild(unplayedStat);
    }
    box.appendChild(scoreRow);

    // Warning message
    if (alliance.missingTeams && alliance.missingTeams.length > 0) {
        const warn = document.createElement("div");
        warn.className = "warning-msg";
        warn.innerHTML = `⚠️ <span>Missing: ${alliance.missingTeams.join(", ")}</span>`;
        box.appendChild(warn);
    }

    return box;
}

function createMatchStatusBadge(match) {
    const container = document.createElement("div");
    container.style.display = "flex";
    container.style.flexDirection = "column";
    container.style.gap = "4px";

    const isUnscouted = match.redAlliance.scoutedTeams.length === 0 && match.blueAlliance.scoutedTeams.length === 0;

    if (match.hasAnomaly) {
        const badge = document.createElement("span");
        badge.className = "status-badge anomaly";
        badge.innerHTML = `🚨 Score Anomaly`;
        container.appendChild(badge);
    }

    if (match.isFullyScouted) {
        const badge = document.createElement("span");
        badge.className = "status-badge complete";
        badge.innerHTML = `✓ Fully Scouted`;
        container.appendChild(badge);
    } else if (isUnscouted) {
        const badge = document.createElement("span");
        badge.className = "status-badge unscouted";
        badge.innerHTML = `○ Unscouted`;
        container.appendChild(badge);
    } else {
        const badge = document.createElement("span");
        badge.className = "status-badge incomplete";
        const missingCount = match.redAlliance.missingTeams.length + match.blueAlliance.missingTeams.length;
        badge.innerHTML = `⚠️ Incomplete (${missingCount} missing)`;
        container.appendChild(badge);
    }

    return container;
}

function renderTeamsTable() {
    const tbody = document.getElementById("teams-validation-tbody");
    const countBadge = document.getElementById("teams-count-badge");
    const filterStatus = document.getElementById("filter-status").value;
    const query = (document.getElementById("search-input").value || "").trim().toLowerCase();

    tbody.innerHTML = "";

    let teams = currentValidationData.teams || [];

    // Filter by status
    if (filterStatus === "anomalies" || filterStatus === "incomplete") {
        teams = teams.filter(t => t.isAnomaly);
    } else if (filterStatus === "complete") {
        teams = teams.filter(t => t.scoutedMatchCount > 0 && !t.isAnomaly);
    }

    // Filter by search query
    if (query) {
        teams = teams.filter(t => {
            const teamNum = t.teamNumber.toString();
            const name = (t.nickname || "").toLowerCase();
            return teamNum.includes(query) || name.includes(query);
        });
    }

    countBadge.textContent = `${teams.length} teams`;

    if (teams.length === 0) {
        tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--text-muted, #94a3b8); padding: 32px 16px;">No teams matching filter criteria</td></tr>`;
        return;
    }

    const useEpa = currentValidationData.useStatboticsEpa;
    const useOpr = currentValidationData.useTbaOpr;

    teams.forEach(team => {
        const tr = document.createElement("tr");

        // Team Number
        const teamTd = document.createElement("td");
        teamTd.innerHTML = `<a href="/team?team=${team.teamNumber}" style="font-weight: 800; color: var(--primary, #38bdf8); text-decoration: none;">${team.teamNumber}</a>`;
        tr.appendChild(teamTd);

        // Name
        const nameTd = document.createElement("td");
        nameTd.textContent = team.nickname || "-";
        tr.appendChild(nameTd);

        // Scouted Matches Count
        const countTd = document.createElement("td");
        countTd.innerHTML = `<span class="badge">${team.scoutedMatchCount}</span>`;
        tr.appendChild(countTd);

        // Scouted Avg
        const avgTd = document.createElement("td");
        if (team.averageScoutedScore !== null) {
            avgTd.innerHTML = `<span style="font-weight: 800; font-size: 1rem;">${team.averageScoutedScore}</span>`;
        } else {
            avgTd.innerHTML = `<span class="metric-subtext">No scout data</span>`;
        }
        tr.appendChild(avgTd);

        // EPA & Delta
        const epaTd = document.createElement("td");
        if (useEpa && team.epa !== null && team.epa !== undefined) {
            const epaDiff = team.epaDiff;
            const diffHtml = epaDiff !== null
                ? `<span class="delta-chip ${Math.abs(epaDiff) >= (currentValidationData.threshold || 15) ? 'anomaly' : 'normal'}" style="margin-left: 6px;">Δ ${epaDiff > 0 ? '+' + epaDiff : epaDiff}</span>`
                : '';
            epaTd.innerHTML = `<span>${team.epa}</span> ${diffHtml}`;
        } else {
            epaTd.innerHTML = `<span class="metric-subtext">${useEpa ? 'N/A' : 'Disabled'}</span>`;
        }
        tr.appendChild(epaTd);

        // OPR & Delta
        const oprTd = document.createElement("td");
        if (useOpr && team.opr !== null && team.opr !== undefined) {
            const oprDiff = team.oprDiff;
            const diffHtml = oprDiff !== null
                ? `<span class="delta-chip ${Math.abs(oprDiff) >= (currentValidationData.threshold || 15) ? 'anomaly' : 'normal'}" style="margin-left: 6px;">Δ ${oprDiff > 0 ? '+' + oprDiff : oprDiff}</span>`
                : '';
            oprTd.innerHTML = `<span>${team.opr}</span> ${diffHtml}`;
        } else {
            oprTd.innerHTML = `<span class="metric-subtext">${useOpr ? 'N/A' : 'Disabled'}</span>`;
        }
        tr.appendChild(oprTd);

        // Anomaly Status
        const statusTd = document.createElement("td");
        if (team.isAnomaly) {
            statusTd.innerHTML = `<span class="status-badge anomaly" title="${escapeHtml(team.anomalyReason || 'Anomaly detected')}">🚨 ${escapeHtml(team.anomalyReason || 'Anomaly')}</span>`;
        } else if (team.scoutedMatchCount > 0) {
            statusTd.innerHTML = `<span class="status-badge complete">✓ In Range</span>`;
        } else {
            statusTd.innerHTML = `<span class="status-badge unscouted">○ No Entries</span>`;
        }
        tr.appendChild(statusTd);

        tbody.appendChild(tr);
    });
}

function openMatchDetailsModal(match) {
    const modal = document.getElementById("validation-modal");
    const title = document.getElementById("modal-match-title");
    const subtitle = document.getElementById("modal-match-subtitle");
    const body = document.getElementById("modal-match-body");

    title.textContent = `${match.label} Validation Breakdown`;
    subtitle.textContent = `Match Key: ${match.matchKey} | Event: ${match.eventKey.toUpperCase()}`;

    body.innerHTML = "";

    // Red Alliance Breakdown
    body.appendChild(createAllianceModalBreakdown(match.redAlliance, match));

    // Blue Alliance Breakdown
    body.appendChild(createAllianceModalBreakdown(match.blueAlliance, match));

    modal.classList.add("active");
}

function createAllianceModalBreakdown(alliance, match) {
    const card = document.createElement("div");
    card.className = `alliance-breakdown-card ${alliance.allianceColor}`;

    const header = document.createElement("div");
    header.style.display = "flex";
    header.style.justifyContent = "space-between";
    header.style.alignItems = "center";
    header.style.marginBottom = "14px";
    header.innerHTML = `
        <h4 style="margin: 0; text-transform: uppercase; letter-spacing: 0.5px;">${alliance.allianceColor} Alliance</h4>
        <div style="display: flex; gap: 12px; align-items: baseline;">
            <span class="score-stat"><span class="score-label">Scouted Sum:</span> <span class="score-num">${alliance.scoutedScoreSum}</span></span>
            <span class="score-stat"><span class="score-label">Official Score:</span> <span class="score-num">${alliance.actualScore !== null ? alliance.actualScore : 'N/A'}</span></span>
            ${alliance.scoreDiff !== null ? `<span class="delta-chip ${alliance.isAnomaly ? 'anomaly' : 'normal'}">Δ ${alliance.scoreDiff > 0 ? '+' + alliance.scoreDiff : alliance.scoreDiff}</span>` : ''}
        </div>
    `;
    card.appendChild(header);

    // List of teams and breakdown
    const list = document.createElement("div");
    list.style.display = "flex";
    list.style.flexDirection = "column";
    list.style.gap = "8px";

    alliance.teams.forEach(teamNum => {
        const breakdown = (alliance.teamBreakdowns || []).find(b => b.teamNumber === teamNum);
        const row = document.createElement("div");
        row.className = "team-entry-row";

        if (breakdown) {
            const entryUrl = `/all-data?search=${teamNum}&matchNumber=${match.matchNumber || ''}&eventKey=${encodeURIComponent(match.eventKey)}&type=match&entryId=${encodeURIComponent(breakdown.entryId)}`;
            row.innerHTML = `
                <div style="display: flex; align-items: center; gap: 10px;">
                    <a href="/team?team=${teamNum}" style="font-weight: 800; font-size: 1rem; color: var(--primary, #38bdf8); text-decoration: none;">Team ${teamNum}</a>
                    <span class="team-chip scouted">✓ Scouted</span>
                    ${breakdown.scouterName ? `<span class="metric-subtext">Scout: ${escapeHtml(breakdown.scouterName)}</span>` : ''}
                    ${breakdown.hasDiscrepancy ? `<span class="status-badge incomplete" style="font-size: 0.7rem;">⚠️ Discrepancy</span>` : ''}
                </div>
                <div style="display: flex; align-items: center; gap: 12px;">
                    <span style="font-weight: 800; font-size: 1.1rem;">${breakdown.scoutedScore} pts</span>
                    <a href="${entryUrl}" class="btn ghost" style="padding: 2px 8px; font-size: 0.75rem;">View Entry</a>
                </div>
            `;
        } else {
            row.innerHTML = `
                <div style="display: flex; align-items: center; gap: 10px;">
                    <a href="/team?team=${teamNum}" style="font-weight: 800; font-size: 1rem; color: #fbbf24; text-decoration: none;">Team ${teamNum}</a>
                    <span class="team-chip missing">⚠️ Missing Record</span>
                </div>
                <div style="display: flex; align-items: center; gap: 12px;">
                    <span class="metric-subtext">No data recorded for this match</span>
                    <a href="/scout?matchKey=${encodeURIComponent(match.matchKey)}&teamNumber=${teamNum}" class="btn primary" style="padding: 4px 10px; font-size: 0.75rem;">Scout Now</a>
                </div>
            `;
        }
        list.appendChild(row);
    });

    card.appendChild(list);
    return card;
}

function escapeHtml(str) {
    if (!str) return "";
    return str
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}
