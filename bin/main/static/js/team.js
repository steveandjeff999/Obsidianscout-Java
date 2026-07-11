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

    currentTeamKey = `frc${currentTeamNumber}`;

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
            return allTeamsInMatch.some(k => k.replace(/^frc/, "") === String(currentTeamNumber));
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
        renderOverview();
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
    const epaEl = document.getElementById("stat-epa");
    epaEl.textContent = t.epa !== null && t.epa !== undefined ? t.epa.toFixed(2) : "--";

    // OPR
    const oprEl = document.getElementById("stat-opr");
    oprEl.textContent = t.opr !== null && t.opr !== undefined ? t.opr.toFixed(2) : "--";

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

function renderOverview() {
    const t = state.team;
    document.getElementById("info-team-key").value = t.teamKey || `frc${t.teamNumber}`;
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
            const isSelf = key.replace(/^frc/, "") === String(currentTeamNumber);
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
            const isSelf = key.replace(/^frc/, "") === String(currentTeamNumber);
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

        card.innerHTML = `
            <div class="record-card-header">
                <div class="record-card-header-left">
                    <span class="record-type-badge ${recordTypeClass === "qual" ? "qual" : recordTypeClass}">${entry.type}</span>
                    <span class="record-meta-text">${metaText}</span>
                    <span class="record-date-text">| Scouter Team: ${entry.ownerTeamNumber} | ${formatDateTime(entry.createdAt)}</span>
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
            if (current.title || current.fields.length) {
                groups.push(current);
            }
            current = { 
                title: localize(field.label), 
                fields: [] 
            };
            return;
        }
        if (!RESERVED_FIELDS.has(field.id)) {
            current.fields.push(field);
        }
    });
    if (current.title || current.fields.length) {
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
