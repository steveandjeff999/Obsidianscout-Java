let currentUser = null;
let currentEventKey = "";
let currentTeamNumber = null;
let currentTeamKey = "";
let timezone = "America/New_York";

let state = {
    team: null,
    matches: [],
    configs: {
        match: null,
        pit: null,
        qualitative: null
    },
    entries: [] // Combined and filtered scouting entries
};

const RESERVED_FIELDS = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

function localize(value) {
    return (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(value) : value;
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

    // Read Query Params
    const params = new URLSearchParams(window.location.search);
    const numStr = params.get("teamNumber");
    currentTeamNumber = parseInt(numStr, 10);
    currentEventKey = params.get("eventKey");

    if (!currentTeamNumber || isNaN(currentTeamNumber)) {
        Obsidianscout.showToast("Invalid team number", "error");
        setTimeout(() => { window.location.href = "/teams"; }, 1500);
        return;
    }

    currentTeamKey = `${Obsidianscout.getProgramPrefix()}${currentTeamNumber}`;

    // Handle Back Button
    document.getElementById("back-btn").addEventListener("click", () => {
        if (currentEventKey) {
            window.location.href = `/teams?eventKey=${currentEventKey}`;
        } else {
            window.location.href = "/teams";
        }
    });

    await loadTeamProfile();
});

async function loadTeamProfile() {
    const loadingContainer = document.getElementById("loading-container");
    const profileContainer = document.getElementById("profile-container");

    try {
        // Fetch Settings & Event Key
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        state.settings = settings;
        timezone = settings.timezone;

        if (!currentEventKey) {
            currentEventKey = Obsidianscout.resolveEventKey(settings);
        }

        // Fetch configs, teams, matches, and scouting entries in parallel
        const [
            matchConfig,
            pitConfig,
            qualConfig,
            teamsList,
            allMatches,
            matchEntries,
            pitEntries,
            qualEntries
        ] = await Promise.all([
            Obsidianscout.request("/api/config"),
            Obsidianscout.request("/api/pit-config"),
            Obsidianscout.request("/api/qual-config"),
            Obsidianscout.request(`/api/teams?eventKey=${currentEventKey}`),
            Obsidianscout.request(`/api/matches?eventKey=${currentEventKey}`),
            Obsidianscout.request(`/api/scouting?includePrescout=true`),
            Obsidianscout.request(`/api/pit-scouting?includePrescout=true`),
            Obsidianscout.request(`/api/qual-scouting?includePrescout=true`)
        ]);

        state.configs.match = matchConfig;
        state.configs.pit = pitConfig;
        state.configs.qualitative = qualConfig;

        // Find current team
        state.team = teamsList.find(t => t.teamNumber === currentTeamNumber) || {
            teamNumber: currentTeamNumber,
            teamKey: currentTeamKey,
            nickname: `Team ${currentTeamNumber}`,
            name: `Team ${currentTeamNumber}`,
            city: null,
            state: null,
            country: null,
            opr: null,
            epa: null,
            averagePoints: null
        };

        // Filter and normalize matches
        state.matches = allMatches.filter(m => {
            const allTeamsInMatch = (m.redTeams || []).concat(m.blueTeams || []);
            return allTeamsInMatch.some(k => k.replace(/^(frc|ftc)/, "") === String(currentTeamNumber));
        });

        // Merge and filter entries for this team
        state.entries = mergeAndFilterEntries(
            matchEntries || [],
            pitEntries || [],
            qualEntries || [],
            currentTeamNumber
        );

        // Hide loading, show profile
        loadingContainer.classList.add("hidden");
        profileContainer.classList.remove("hidden");

        renderHeader();
        renderStats();
        renderAnalytics();
        renderOverview();
        renderPitDetails();
        renderMatches();
        renderScoutingRecords();
        setupTabs();

    } catch (error) {
        console.error("Failed to load team profile:", error);
        Obsidianscout.showRetryButton(loadingContainer, "Failed to load profile details: " + error.message, loadTeamProfile);
    }
}

function mergeAndFilterEntries(match, pit, qual, teamNum) {
    const list = [];
    
    match.forEach(e => {
        if (e.targetTeamNumber === teamNum) {
            list.push({
                id: `match-${e.id}`,
                originalId: e.id,
                type: "Match",
                ownerTeamNumber: e.ownerTeamNumber,
                targetTeamNumber: e.targetTeamNumber,
                eventKey: e.isPrescout ? "prescout" : e.eventKey,
                rawEventKey: e.eventKey,
                isPrescout: e.isPrescout || false,
                matchNumber: e.matchNumber,
                matchKey: e.matchKey,
                createdAt: e.createdAt,
                matchPlayedTime: e.matchPlayedTime || null,
                username: e.username,
                data: e.data
            });
        }
    });

    pit.forEach(e => {
        if (e.targetTeamNumber === teamNum) {
            list.push({
                id: `pit-${e.id}`,
                originalId: e.id,
                type: "Pit",
                ownerTeamNumber: e.ownerTeamNumber,
                targetTeamNumber: e.targetTeamNumber,
                eventKey: e.isPrescout ? "prescout" : e.eventKey,
                rawEventKey: e.eventKey,
                isPrescout: e.isPrescout || false,
                matchNumber: null,
                matchKey: null,
                createdAt: e.createdAt,
                matchPlayedTime: null,
                username: e.username,
                data: e.data
            });
        }
    });

    qual.forEach(e => {
        if (e.targetTeamNumber === teamNum) {
            list.push({
                id: `qualitative-${e.id}`,
                originalId: e.id,
                type: "Qualitative",
                ownerTeamNumber: e.ownerTeamNumber,
                targetTeamNumber: e.targetTeamNumber,
                eventKey: e.isPrescout ? "prescout" : e.eventKey,
                rawEventKey: e.eventKey,
                isPrescout: e.isPrescout || false,
                matchNumber: e.matchNumber,
                matchKey: e.matchKey,
                createdAt: e.createdAt,
                matchPlayedTime: e.matchPlayedTime || null,
                username: e.username,
                data: e.data
            });
        }
    });

    return list;
}

function renderHeader() {
    const t = state.team;
    const titleEl = document.getElementById("team-title-num-name");
    const locEl = document.getElementById("team-location");
    const pillsEl = document.getElementById("team-meta-pills");

    titleEl.textContent = `Team ${t.teamNumber} | ${t.nickname || t.name || `Team ${t.teamNumber}`}`;

    const locationStr = [t.city, t.state, t.country].filter(Boolean).join(", ");
    locEl.textContent = locationStr || "Location unknown";

    pillsEl.innerHTML = "";
    
    // Event Pill
    if (currentEventKey) {
        const eventPill = document.createElement("span");
        eventPill.className = "meta-pill";
        eventPill.innerHTML = `<strong>Event:</strong> ${currentEventKey.toUpperCase()}`;
        pillsEl.appendChild(eventPill);
    }

    // Role Pill (if team is from registered scouts)
    if (t.name && t.name !== t.nickname) {
        const namePill = document.createElement("span");
        namePill.className = "meta-pill";
        namePill.textContent = t.name;
        pillsEl.appendChild(namePill);
    }
}

function renderStats() {
    const t = state.team;
    
    // EPA
    const cardEpa = document.getElementById("card-stat-epa");
    if (cardEpa) {
        cardEpa.style.display = (state.settings && state.settings.useStatboticsEpa) ? "" : "none";
    }
    const epaEl = document.getElementById("stat-epa");
    if (epaEl) epaEl.textContent = t.epa !== null && t.epa !== undefined ? t.epa.toFixed(2) : "--";

    // OPR
    const cardOpr = document.getElementById("card-stat-opr");
    if (cardOpr) {
        cardOpr.style.display = (state.settings && state.settings.useTbaOpr) ? "" : "none";
    }
    const oprEl = document.getElementById("stat-opr");
    if (oprEl) oprEl.textContent = t.opr !== null && t.opr !== undefined ? t.opr.toFixed(2) : "--";

    // Avg Points
    const avgEl = document.getElementById("stat-avg-points");
    if (t.averagePoints !== null && t.averagePoints !== undefined) {
        avgEl.textContent = t.averagePoints.toFixed(1);
    } else {
        // Calculate dynamic average points if needed, or fallback
        avgEl.textContent = "--";
    }

    // Matches Scheduled
    const matchCountEl = document.getElementById("stat-matches-count");
    matchCountEl.textContent = state.matches.length.toString();
}

function renderAnalytics() {
    const matchEntries = state.entries.filter(e => e.type === "Match");
    const qualEntries = state.entries.filter(e => e.type === "Qualitative");
    const config = state.configs.match;

    let autoSum = 0;
    let teleopSum = 0;
    let endgameSum = 0;
    let totalSum = 0;
    let maxScore = 0;

    const progression = [];
    const fieldStats = {};

    if (config && config.fields) {
        config.fields.forEach(f => {
            if (f.type !== "section" && !RESERVED_FIELDS.has(f.id)) {
                fieldStats[f.id] = {
                    field: f,
                    count: 0,
                    sum: 0,
                    max: 0,
                    options: {}
                };
            }
        });
    }

    matchEntries.forEach(entry => {
        const d = entry.data || {};
        let mAuto = 0;
        let mTeleop = 0;
        let mEndgame = 0;

        if (config && config.fields) {
            config.fields.forEach(f => {
                if (RESERVED_FIELDS.has(f.id) || f.type === "section") return;
                const val = d[f.id];
                if (val === undefined || val === null) return;

                let pts = 0;
                let numVal = 0;

                if (["counter", "number", "slider", "rating"].includes(f.type)) {
                    numVal = parseFloat(val) || 0;
                    pts = numVal * (f.pointsPer || 0);
                    if (fieldStats[f.id]) {
                        fieldStats[f.id].count++;
                        fieldStats[f.id].sum += numVal;
                        fieldStats[f.id].max = Math.max(fieldStats[f.id].max, numVal);
                    }
                } else if (["checkbox", "toggle", "boolean"].includes(f.type)) {
                    const isTrue = val === true || val === "true" || val === 1 || val === "1";
                    if (isTrue) {
                        pts = f.pointsPer || 0;
                        if (fieldStats[f.id]) fieldStats[f.id].sum++;
                    }
                    if (fieldStats[f.id]) fieldStats[f.id].count++;
                } else if (["select", "radio"].includes(f.type)) {
                    const optVal = String(val);
                    if (fieldStats[f.id]) {
                        fieldStats[f.id].count++;
                        fieldStats[f.id].options[optVal] = (fieldStats[f.id].options[optVal] || 0) + 1;
                    }
                }

                const phase = (f.phase || "").toLowerCase();
                if (phase.includes("auto")) {
                    mAuto += pts;
                } else if (phase.includes("end")) {
                    mEndgame += pts;
                } else {
                    mTeleop += pts;
                }
            });
        }

        const mTotal = mAuto + mTeleop + mEndgame;
        autoSum += mAuto;
        teleopSum += mTeleop;
        endgameSum += mEndgame;
        totalSum += mTotal;
        maxScore = Math.max(maxScore, mTotal);

        progression.push({
            matchNumber: entry.matchNumber || 0,
            matchKey: entry.matchKey || `M${entry.matchNumber || ""}`,
            auto: mAuto,
            teleop: mTeleop,
            endgame: mEndgame,
            total: mTotal
        });
    });

    const count = matchEntries.length;
    const autoAvg = count > 0 ? (autoSum / count) : 0;
    const teleopAvg = count > 0 ? (teleopSum / count) : 0;
    const endgameAvg = count > 0 ? (endgameSum / count) : 0;
    const totalAvg = count > 0 ? (totalSum / count) : (state.team.averagePoints || 0);

    // Update stat card in header if computed total is greater than 0
    const avgEl = document.getElementById("stat-avg-points");
    if (avgEl && totalAvg > 0) {
        avgEl.textContent = totalAvg.toFixed(1);
    }

    // Populate phase breakdown cards
    const autoEl = document.getElementById("analytics-auto-avg");
    const teleopEl = document.getElementById("analytics-teleop-avg");
    const endgameEl = document.getElementById("analytics-endgame-avg");
    const maxEl = document.getElementById("analytics-max-score");

    if (autoEl) autoEl.textContent = count > 0 ? autoAvg.toFixed(1) : "--";
    if (teleopEl) teleopEl.textContent = count > 0 ? teleopAvg.toFixed(1) : "--";
    if (endgameEl) endgameEl.textContent = count > 0 ? endgameAvg.toFixed(1) : "--";
    if (maxEl) maxEl.textContent = count > 0 ? maxScore.toFixed(1) : "--";

    renderProgressionChart(progression);
    renderMetricsTable(fieldStats);
    renderQualFeedback(qualEntries);
}

function renderProgressionChart(progression) {
    const container = document.getElementById("team-chart-container");
    if (!container) return;

    if (progression.length === 0) {
        container.innerHTML = '<div style="text-align: center; color: var(--muted); padding: 32px 16px;">No scouted match data available to display score trend.</div>';
        return;
    }

    progression.sort((a, b) => a.matchNumber - b.matchNumber);

    const maxPoints = Math.max(...progression.map(p => p.total), 10);
    const chartHeight = 160;

    let barsHtml = '<div class="chart-bars-row">';
    progression.forEach(item => {
        const autoPct = maxPoints > 0 ? ((item.auto / maxPoints) * chartHeight) : 0;
        const teleopPct = maxPoints > 0 ? ((item.teleop / maxPoints) * chartHeight) : 0;
        const endgamePct = maxPoints > 0 ? ((item.endgame / maxPoints) * chartHeight) : 0;

        barsHtml += `
            <div class="chart-bar-column" title="Match ${item.matchNumber}: Total ${item.total.toFixed(1)} (Auto: ${item.auto.toFixed(1)}, Teleop: ${item.teleop.toFixed(1)}, Endgame: ${item.endgame.toFixed(1)})">
                <div class="chart-bar-value">${item.total.toFixed(0)}</div>
                <div class="chart-bar-stack" style="height: ${chartHeight}px;">
                    ${item.endgame > 0 ? `<div class="bar-segment bar-endgame" style="height: ${endgamePct}px;"></div>` : ''}
                    ${item.teleop > 0 ? `<div class="bar-segment bar-teleop" style="height: ${teleopPct}px;"></div>` : ''}
                    ${item.auto > 0 ? `<div class="bar-segment bar-auto" style="height: ${autoPct}px;"></div>` : ''}
                </div>
                <div class="chart-bar-label">M${item.matchNumber || '-'}</div>
            </div>
        `;
    });
    barsHtml += '</div>';

    container.innerHTML = barsHtml;
}

function renderMetricsTable(fieldStats) {
    const tbody = document.getElementById("team-metrics-tbody");
    if (!tbody) return;

    const entries = Object.values(fieldStats).filter(st => st.count > 0);
    if (entries.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: var(--muted); padding: 24px;">No match scouting metrics recorded yet.</td></tr>';
        return;
    }

    tbody.innerHTML = entries.map(st => {
        const f = st.field;
        const phase = f.phase || "Teleop";
        let avgRate = "--";
        let maxVal = "--";
        let totalVal = "--";

        if (["counter", "number", "slider", "rating"].includes(f.type)) {
            const avg = st.count > 0 ? (st.sum / st.count) : 0;
            avgRate = avg.toFixed(1);
            maxVal = st.max.toFixed(0);
            totalVal = st.sum.toFixed(0);
        } else if (["checkbox", "toggle", "boolean"].includes(f.type)) {
            const pct = st.count > 0 ? ((st.sum / st.count) * 100) : 0;
            avgRate = `${pct.toFixed(0)}% (${st.sum}/${st.count})`;
            maxVal = "Yes";
            totalVal = `${st.sum} matches`;
        } else if (["select", "radio"].includes(f.type)) {
            avgRate = Object.entries(st.options).map(([k, v]) => `${k} (${v})`).join(", ") || "--";
            maxVal = "--";
            totalVal = `${st.count} entries`;
        }

        return `
            <tr>
                <td><strong>${localize(f.label)}</strong></td>
                <td><span class="meta-pill" style="font-size: 11px;">${phase.toUpperCase()}</span></td>
                <td><strong style="color: var(--accent);">${avgRate}</strong></td>
                <td>${maxVal}</td>
                <td>${totalVal}</td>
            </tr>
        `;
    }).join("");
}

function renderQualFeedback(qualEntries) {
    const container = document.getElementById("team-qual-list");
    if (!container) return;

    if (qualEntries.length === 0) {
        container.innerHTML = '<div style="text-align: center; color: var(--muted); padding: 20px;">No qualitative scouting observations recorded for this team.</div>';
        return;
    }

    container.innerHTML = qualEntries.map(e => {
        const d = e.data || {};
        const matchLabel = e.matchNumber ? `Match ${e.matchNumber}` : "General Observation";
        const scouterName = e.username || (d && (d.username || d.scouter || d.scout_name)) || (e.ownerTeamNumber ? `Team ${e.ownerTeamNumber}` : "Scout");
        const dateStr = formatDateTime(e.createdAt);
        
        let notes = d.notes || d.comments || d.driver_skill || d.defense || "";
        if (!notes) {
            notes = Object.entries(d).filter(([k]) => !RESERVED_FIELDS.has(k)).map(([k, v]) => `${k}: ${v}`).join(" | ");
        }

        return `
            <div class="qual-feedback-card">
                <div class="qual-feedback-header">
                    <span class="qual-match-badge">${matchLabel}</span>
                    <span class="qual-meta">Scouted by ${scouterName} • ${dateStr}</span>
                </div>
                <div class="qual-feedback-body">${notes}</div>
            </div>
        `;
    }).join("");
}

function renderPitDetails() {
    const container = document.getElementById("pit-profile-container");
    if (!container) return;

    const pitEntries = state.entries.filter(e => e.type === "Pit");
    if (pitEntries.length === 0) {
        container.innerHTML = '<div style="text-align: center; color: var(--muted); padding: 32px 16px;">No pit scouting profile recorded for this team.</div>';
        return;
    }

    const latest = pitEntries[0];
    const d = latest.data || {};
    const config = state.configs.pit;

    if (config && config.fields) {
        const groups = groupFields(config.fields);
        container.innerHTML = groups.map(group => {
            const fieldsHtml = group.fields.map(f => {
                const val = d[f.id];
                const formatted = formatFieldValue(f, val);
                return `
                    <div class="pit-profile-item">
                        <span class="pit-profile-label">${localize(f.label)}</span>
                        <strong class="pit-profile-value">${formatted}</strong>
                    </div>
                `;
            }).join("");

            return `
                <div class="pit-profile-section">
                    ${group.title ? `<h3 class="pit-section-header">${group.title}</h3>` : ''}
                    <div class="pit-section-grid">
                        ${fieldsHtml}
                    </div>
                </div>
            `;
        }).join("");
    } else {
        const items = Object.entries(d).filter(([k]) => !RESERVED_FIELDS.has(k)).map(([k, v]) => `
            <div class="pit-profile-item">
                <span class="pit-profile-label">${k}</span>
                <strong class="pit-profile-value">${v}</strong>
            </div>
        `).join("");
        container.innerHTML = `<div class="pit-section-grid">${items}</div>`;
    }
}

function renderOverview() {
    const t = state.team;
    document.getElementById("info-team-key").value = t.teamKey || `${Obsidianscout.getProgramPrefix()}${t.teamNumber}`;
    document.getElementById("info-team-number").value = t.teamNumber;
    document.getElementById("info-nickname").value = t.nickname || "";
    document.getElementById("info-formal-name").value = t.name || "";
    document.getElementById("info-city").value = t.city || "";
    document.getElementById("info-state").value = t.state || "";
    document.getElementById("info-country").value = t.country || "";
    document.getElementById("info-event-key").value = currentEventKey || "";
}

function renderMatches() {
    const table = document.getElementById("team-matches-table");
    const body = table.querySelector("tbody");
    body.innerHTML = "";

    if (state.matches.length === 0) {
        body.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--muted); padding: 24px;">No matches scheduled for this team at this event.</td></tr>`;
        return;
    }

    // Sort matches chronologically and logically:
    // 1. By competition level priority (practice < qm < qf < sf < f)
    // 2. By set number (if applicable)
    // 3. By match number
    // 4. By scheduled time (fallback)
    const LEVEL_ORDER = {
        "practice": 0,
        "qm": 1,
        "qual": 1,
        "qf": 2,
        "sf": 3,
        "f": 4
    };

    const sortedMatches = state.matches.slice().sort((a, b) => {
        const levelA = LEVEL_ORDER[a.compLevel.toLowerCase()] !== undefined ? LEVEL_ORDER[a.compLevel.toLowerCase()] : 99;
        const levelB = LEVEL_ORDER[b.compLevel.toLowerCase()] !== undefined ? LEVEL_ORDER[b.compLevel.toLowerCase()] : 99;

        if (levelA !== levelB) {
            return levelA - levelB;
        }

        const setA = a.setNumber || 0;
        const setB = b.setNumber || 0;
        if (setA !== setB) {
            return setA - setB;
        }

        const numA = a.matchNumber || 0;
        const numB = b.matchNumber || 0;
        if (numA !== numB) {
            return numA - numB;
        }

        const timeA = a.scheduledTime || 0;
        const timeB = b.scheduledTime || 0;
        return timeA - timeB;
    });

    sortedMatches.forEach(match => {
        const tr = document.createElement("tr");

        // Match Label
        const matchLabel = getMatchLabel(match.matchKey, match.matchNumber);
        const matchCell = document.createElement("td");
        matchCell.textContent = matchLabel;
        tr.appendChild(matchCell);

        // Time
        const timeCell = document.createElement("td");
        timeCell.textContent = Obsidianscout.formatTimestamp(match.scheduledTime, timezone);
        tr.appendChild(timeCell);

        // Red Alliance
        const redCell = document.createElement("td");
        redCell.className = "alliance-cell";
        match.redTeams.forEach(key => {
            const formatted = Obsidianscout.formatTeam(key);
            const isSelf = key.replace(/^(frc|ftc)/, "") === String(currentTeamNumber);
            const badge = document.createElement("span");
            badge.className = `alliance-member-badge red-team ${isSelf ? 'highlight-self' : ''}`;
            badge.textContent = formatted;
            redCell.appendChild(badge);
        });
        tr.appendChild(redCell);

        // Blue Alliance
        const blueCell = document.createElement("td");
        blueCell.className = "alliance-cell";
        match.blueTeams.forEach(key => {
            const formatted = Obsidianscout.formatTeam(key);
            const isSelf = key.replace(/^(frc|ftc)/, "") === String(currentTeamNumber);
            const badge = document.createElement("span");
            badge.className = `alliance-member-badge blue-team ${isSelf ? 'highlight-self' : ''}`;
            badge.textContent = formatted;
            blueCell.appendChild(badge);
        });
        tr.appendChild(blueCell);

        body.appendChild(tr);
    });
}

function renderScoutingRecords() {
    const listContainer = document.getElementById("records-list");
    const filter = document.getElementById("record-type-filter").value;

    listContainer.innerHTML = "";

    // Apply Filter
    let filtered = state.entries;
    if (filter === "match") {
        filtered = state.entries.filter(e => e.type === "Match");
    } else if (filter === "pit") {
        filtered = state.entries.filter(e => e.type === "Pit");
    } else if (filter === "qual") {
        filtered = state.entries.filter(e => e.type === "Qualitative");
    }

    if (filtered.length === 0) {
        listContainer.innerHTML = `<div class="no-records-notice">No scouting records found for the selected type.</div>`;
        return;
    }

    // Sort records: Newest first
    filtered.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));

    filtered.forEach(entry => {
        const card = document.createElement("div");
        card.className = "record-card";

        const matchLabel = entry.matchNumber !== null ? getMatchLabel(entry.matchKey, entry.matchNumber) : "";
        const metaText = entry.type === "Pit" ? "Pit Scouting" : `${matchLabel} (${entry.type})`;
        const recordTypeClass = entry.type.toLowerCase().substring(0, 4);
        const scouterName = entry.username || (entry.data && (entry.data.username || entry.data.scouter || entry.data.scout_name)) || (entry.ownerTeamNumber ? `Team ${entry.ownerTeamNumber}` : "Scout");

        card.innerHTML = `
            <div class="record-card-header">
                <div class="record-card-header-left">
                    <span class="record-type-badge ${recordTypeClass === "qual" ? "qual" : recordTypeClass}">${entry.type}</span>
                    <span class="record-meta-text">${metaText}</span>
                    <span class="record-date-text">| Scouter: ${scouterName} | ${formatDateTime(entry.createdAt)}</span>
                </div>
                <div class="record-card-expand-icon">&#9662;</div>
            </div>
            <div class="record-card-body">
                <div class="record-details-grid" id="details-grid-${entry.id}"></div>
            </div>
        `;

        const header = card.querySelector(".record-card-header");
        header.addEventListener("click", () => {
            const isExpanded = card.classList.toggle("expanded");
            header.querySelector(".record-card-expand-icon").innerHTML = isExpanded ? "&#9652;" : "&#9662;";
        });

        // Render Configuration Fields inside Card Body
        const grid = card.querySelector(`#details-grid-${entry.id}`);
        const configKey = entry.type.toLowerCase();
        const config = state.configs[configKey];

        if (config && config.fields) {
            const groups = groupFields(config.fields);
            groups.forEach(group => {
                if (group.title) {
                    const sectionTitle = document.createElement("h4");
                    sectionTitle.className = "record-section-title";
                    sectionTitle.textContent = group.title;
                    grid.appendChild(sectionTitle);
                }

                group.fields.forEach(field => {
                    const value = entry.data ? entry.data[field.id] : undefined;
                    const item = document.createElement("div");
                    item.className = "record-field-item";
                    
                    const label = document.createElement("span");
                    label.className = "record-field-label";
                    label.textContent = localize(field.label);
                    
                    const valEl = document.createElement("span");
                    valEl.className = "record-field-value";
                    valEl.textContent = formatFieldValue(field, value);

                    item.appendChild(label);
                    item.appendChild(valEl);
                    grid.appendChild(item);
                });
            });
        } else {
            grid.innerHTML = `<div style="grid-column: 1/-1; color: var(--muted);">No form configuration layout available for this entry.</div>`;
        }

        listContainer.appendChild(card);
    });
}

function setupTabs() {
    const tabBtns = document.querySelectorAll(".team-tab-btn");
    const contents = document.querySelectorAll(".team-tab-content");

    tabBtns.forEach(btn => {
        btn.addEventListener("click", () => {
            const target = btn.getAttribute("data-tab");

            // Active Tab Button
            tabBtns.forEach(b => b.classList.remove("active"));
            btn.classList.add("active");

            // Active Content
            contents.forEach(c => {
                c.classList.remove("active");
                if (c.id === `tab-${target}`) {
                    c.classList.add("active");
                }
            });
        });
    });

    // Wire Scouting Record Dropdown Filter
    document.getElementById("record-type-filter").addEventListener("change", renderScoutingRecords);
}

function getMatchLabel(matchKey, matchNumber) {
    if (matchNumber === null || matchNumber === undefined) {
        return "N/A";
    }
    if (!matchKey) {
        return `${t("matches.match", "Match")} ${matchNumber}`;
    }
    const parts = matchKey.split('_');
    if (parts.length < 2) {
        return `${t("matches.match", "Match")} ${matchNumber}`;
    }
    const suffix = parts[parts.length - 1].toLowerCase();
    
    if (suffix.startsWith('practice')) {
        return `${t("matches.comp.practice", "Practice")} ${t("matches.match", "Match")} ${matchNumber}`;
    } else if (suffix.startsWith('qm') || suffix.startsWith('qual')) {
        return `${t("matches.comp.qm", "Qualification")} ${t("matches.match", "Match")} ${matchNumber}`;
    } else if (suffix.startsWith('sf') || suffix.startsWith('qf') || suffix.startsWith('f') || suffix.startsWith('ef') || suffix.startsWith('playoff')) {
        return `${t("matches.comp.playoff", "Playoff")} ${t("matches.match", "Match")} ${matchNumber}`;
    }
    
    return `${t("matches.match", "Match")} ${matchNumber}`;
}

function formatDateTime(value) {
    if (!value) return "--";
    const date = new Date(value);
    if (isNaN(date.getTime())) return "--";
    return date.toLocaleString();
}

function groupFields(fields) {
    const groups = [];
    let current = { title: "", fields: [] };
    fields.forEach((field) => {
        if (field.type === "section") {
            return;
        }
        if (!RESERVED_FIELDS.has(field.id)) {
            current.fields.push(field);
        }
    });
    if (current.fields.length) {
        groups.push(current);
    }
    return groups;
}

function formatFieldValue(field, value) {
    if (value === null || value === undefined || value === "") {
        return "--";
    }
    if (field.type === "checkbox") {
        return value ? "Yes" : "No";
    }
    if (field.type === "select") {
        const options = field.options || [];
        const opt = options.find(o => o.value === value || o.label === value);
        if (opt) {
            return localize(opt.label);
        }
        return String(value);
    }
    return String(value);
}
