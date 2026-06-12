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

    const state = {
        settings: null,
        eventKey: "",
        events: [],
        teamsByNumber: new Map(),
        configs: {
            match: null,
            pit: null,
            qualitative: null
        },
        entries: [], // Combined normalized entries
        selectedEntryId: null,
        filters: {
            eventKey: "",
            teamQuery: "",
            type: "all",
            matchNumber: "",
            sortBy: "match-type"
        }
    };

    // Setup wrapper for dynamic content
    const mainContent = document.querySelector(".main-content");
    const titleCard = mainContent.querySelector(".card");
    const dynamicContainer = document.createElement("div");
    dynamicContainer.id = "all-dynamic-container";
    
    let nextSib = titleCard.nextElementSibling;
    while (nextSib) {
        const temp = nextSib.nextElementSibling;
        dynamicContainer.appendChild(nextSib);
        nextSib = temp;
    }
    mainContent.appendChild(dynamicContainer);
    const originalHTML = dynamicContainer.innerHTML;

    async function loadAllScoutingData() {
        Obsidianscout.showLoadingSpinner(dynamicContainer, "Loading all scouting data...");
        try {
            const settingsResponse = await Obsidianscout.request("/api/settings");
            state.settings = settingsResponse.settings;
            state.eventKey = Obsidianscout.resolveEventKey(state.settings);
            state.filters.eventKey = state.eventKey;

            const [matchConfig, pitConfig, qualConfig, matchEntries, pitEntries, qualEntries, events] = await Promise.all([
                Obsidianscout.request("/api/config"),
                Obsidianscout.request("/api/pit-config"),
                Obsidianscout.request("/api/qual-config"),
                Obsidianscout.request("/api/scouting?includePrescout=true"),
                Obsidianscout.request("/api/pit-scouting?includePrescout=true"),
                Obsidianscout.request("/api/qual-scouting?includePrescout=true"),
                Obsidianscout.request(`/api/events?year=${state.settings.year}&cached=1`)
            ]);

            dynamicContainer.innerHTML = originalHTML;

            state.configs.match = matchConfig;
            state.configs.pit = pitConfig;
            state.configs.qualitative = qualConfig;

            state.events = normalizeEvents(events, state.eventKey, state.settings);
            
            // Normalize and merge entries
            state.entries = mergeEntries(matchEntries || [], pitEntries || [], qualEntries || []);

            initControls(state);
            await loadTeamsForEvent(state);
            renderAll(state);
        } catch (error) {
            console.error("Initialization error:", error);
            Obsidianscout.showRetryButton(dynamicContainer, "Failed to load scouting data: " + error.message, loadAllScoutingData);
        }
    }

    await loadAllScoutingData();
});

const RESERVED_FIELDS = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);

function mergeEntries(match, pit, qual) {
    const list = [];
    
    match.forEach(e => {
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
    });

    pit.forEach(e => {
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
    });

    qual.forEach(e => {
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
    });

    return list;
}

function normalizeEvents(events, activeKey, settings) {
    const list = [{ eventKey: "", name: "All Events", year: settings.year }, { eventKey: "prescout", name: "Prescouting Data", year: settings.year }]
        .concat(Array.isArray(events) ? events.slice() : []);
    if (activeKey && !list.some((event) => event.eventKey === activeKey)) {
        list.splice(1, 0, {
            eventKey: activeKey,
            name: `Configured Event: ${activeKey}`,
            year: settings.year
        });
    }
    return list;
}

async function loadTeamsForEvent(state) {
    if (!state.filters.eventKey) {
        state.teamsByNumber.clear();
        return;
    }
    if (state.filters.eventKey === "prescout") {
        state.teamsByNumber.clear();
        const uniqueTeams = Array.from(new Set(state.entries.filter(e => e.isPrescout).map(e => e.targetTeamNumber).filter(Boolean)));
        uniqueTeams.forEach(teamNumber => {
            state.teamsByNumber.set(teamNumber, { teamNumber, nickname: `Team ${teamNumber}` });
        });
        return;
    }
    try {
        const teams = await Obsidianscout.request(`/api/teams?eventKey=${state.filters.eventKey}`);
        state.teamsByNumber.clear();
        if (Array.isArray(teams)) {
            teams.forEach(team => {
                state.teamsByNumber.set(team.teamNumber, team);
            });
        }
    } catch (error) {
        state.teamsByNumber.clear();
    }
}

function initControls(state) {
    const eventFilter = document.getElementById("event-filter");
    const teamSearch = document.getElementById("team-search");
    const typeFilter = document.getElementById("type-filter");
    const matchFilter = document.getElementById("match-filter");
    const sortSelect = document.getElementById("sort-select");
    const resetButton = document.getElementById("reset-filters");
    const exportButton = document.getElementById("export-csv");

    eventFilter.innerHTML = "";
    state.events.forEach((event) => {
        const option = document.createElement("option");
        option.value = event.eventKey;
        option.textContent = event.eventKey ? `${event.name} (${event.eventKey})` : event.name;
        option.selected = event.eventKey === state.filters.eventKey;
        eventFilter.appendChild(option);
    });

    eventFilter.addEventListener("change", async () => {
        state.filters.eventKey = eventFilter.value;
        state.selectedEntryId = null;
        await loadTeamsForEvent(state);
        renderAll(state);
    });

    teamSearch.addEventListener("input", () => {
        state.filters.teamQuery = teamSearch.value.trim().toLowerCase();
        renderTableSection(state);
    });

    typeFilter.addEventListener("change", () => {
        state.filters.type = typeFilter.value;
        renderTableSection(state);
    });

    matchFilter.addEventListener("input", () => {
        state.filters.matchNumber = matchFilter.value.trim();
        renderTableSection(state);
    });

    sortSelect.addEventListener("change", () => {
        state.filters.sortBy = sortSelect.value;
        renderTableSection(state);
    });

    resetButton.addEventListener("click", () => {
        teamSearch.value = "";
        typeFilter.value = "all";
        matchFilter.value = "";
        sortSelect.value = "match-type";

        state.filters.teamQuery = "";
        state.filters.type = "all";
        state.filters.matchNumber = "";
        state.filters.sortBy = "match-type";

        renderTableSection(state);
    });

    exportButton.addEventListener("click", () => exportCsv(state));
}

function renderAll(state) {
    renderMetrics(state);
    renderTableSection(state);
}

function renderMetrics(state) {
    const list = state.entries;
    const pitList = list.filter(e => e.type === "Pit");
    const matchList = list.filter(e => e.type === "Match");
    const qualList = list.filter(e => e.type === "Qualitative");

    document.getElementById("all-entry-count").textContent = list.length.toString();
    document.getElementById("pit-entry-count").textContent = pitList.length.toString();
    document.getElementById("match-entry-count").textContent = matchList.length.toString();
    document.getElementById("qual-entry-count").textContent = qualList.length.toString();
}

function getFilteredAndSortedRows(state) {
    let rows = state.entries.slice();

    // 1. Event filter
    if (state.filters.eventKey) {
        rows = rows.filter(e => e.eventKey === state.filters.eventKey);
    }

    // 2. Type filter
    if (state.filters.type !== "all") {
        rows = rows.filter(e => e.type.toLowerCase() === state.filters.type);
    }

    // 3. Team Search
    if (state.filters.teamQuery) {
        rows = rows.filter(e => {
            const teamNum = String(e.targetTeamNumber);
            const teamObj = state.teamsByNumber.get(e.targetTeamNumber) || null;
            const teamName = teamObj ? (teamObj.nickname || teamObj.name || "").toLowerCase() : "";
            return teamNum.includes(state.filters.teamQuery) || teamName.includes(state.filters.teamQuery);
        });
    }

    // 4. Match Number
    if (state.filters.matchNumber) {
        const matchNumFilter = parseInt(state.filters.matchNumber, 10);
        if (!isNaN(matchNumFilter)) {
            rows = rows.filter(e => e.matchNumber === matchNumFilter);
        }
    }

    // 5. Sorting
    rows.sort((a, b) => {
        if (state.filters.sortBy === "newest") {
            return new Date(b.createdAt) - new Date(a.createdAt);
        }
        if (state.filters.sortBy === "oldest") {
            return new Date(a.createdAt) - new Date(b.createdAt);
        }
        if (state.filters.sortBy === "team") {
            if (a.targetTeamNumber !== b.targetTeamNumber) {
                return a.targetTeamNumber - b.targetTeamNumber;
            }
            if (a.matchPlayedTime !== null && b.matchPlayedTime !== null && a.matchPlayedTime !== undefined && b.matchPlayedTime !== undefined) {
                return a.matchPlayedTime - b.matchPlayedTime;
            }
            const aEvent = a.rawEventKey || a.eventKey || "";
            const bEvent = b.rawEventKey || b.eventKey || "";
            if (aEvent !== bEvent) {
                return aEvent.localeCompare(bEvent);
            }
            return (a.matchNumber || 0) - (b.matchNumber || 0);
        }
        
        // Default: match-type
        if (a.matchPlayedTime !== null && b.matchPlayedTime !== null && a.matchPlayedTime !== undefined && b.matchPlayedTime !== undefined) {
            if (a.matchPlayedTime !== b.matchPlayedTime) {
                return a.matchPlayedTime - b.matchPlayedTime;
            }
        } else {
            const aEvent = a.rawEventKey || a.eventKey || "";
            const bEvent = b.rawEventKey || b.eventKey || "";
            if (aEvent !== bEvent) {
                return aEvent.localeCompare(bEvent);
            }
            const aMatch = a.matchNumber !== null ? a.matchNumber : 0;
            const bMatch = b.matchNumber !== null ? b.matchNumber : 0;
            if (aMatch !== bMatch) {
                return aMatch - bMatch;
            }
        }
        if (a.type !== b.type) {
            return a.type.localeCompare(b.type);
        }
        return a.targetTeamNumber - b.targetTeamNumber;
    });

    return rows;
}

function renderTableSection(state) {
    const table = document.getElementById("data-table");
    const body = table.querySelector("tbody");
    const countBadge = document.getElementById("table-count");
    const statusText = document.getElementById("filter-status");

    const rows = getFilteredAndSortedRows(state);
    
    countBadge.textContent = `${rows.length} entries`;
    statusText.textContent = summarizeFilterStatus(state, rows.length);
    body.innerHTML = "";

    if (!rows.length) {
        const tr = document.createElement("tr");
        const td = document.createElement("td");
        td.colSpan = 4;
        td.textContent = "No entries match the current filters.";
        tr.appendChild(td);
        body.appendChild(tr);
        renderDetail(state, null);
        return;
    }

    // Auto-select first row if none selected or if selected is not in filtered rows
    if (!state.selectedEntryId || !rows.some(r => r.id === state.selectedEntryId)) {
        state.selectedEntryId = rows[0].id;
    }

    rows.forEach(row => {
        const tr = document.createElement("tr");
        tr.className = "clickable-row";
        if (row.id === state.selectedEntryId) {
            tr.classList.add("selected");
        }

        const teamObj = state.teamsByNumber.get(row.targetTeamNumber) || null;
        const name = teamObj ? (teamObj.nickname || teamObj.name) : null;
        const formattedNum = teamObj ? Obsidianscout.formatTeam(teamObj.teamKey, teamObj.teamNumber) : row.targetTeamNumber;
        const teamLabel = name ? `${formattedNum} ${name}` : `${formattedNum}`;

        const matchLabel = getMatchLabel(row.matchKey, row.matchNumber);
        
        // Add cells
        appendCell(tr, teamLabel);
        appendCell(tr, matchLabel);
        appendTypeCell(tr, row.type);
        appendCell(tr, formatDateTime(row.createdAt));

        tr.addEventListener("click", () => {
            state.selectedEntryId = row.id;
            // Highlight selected row
            table.querySelectorAll("tbody tr").forEach(el => el.classList.remove("selected"));
            tr.classList.add("selected");
            renderDetail(state, row);
        });

        body.appendChild(tr);
    });

    const activeEntry = rows.find(r => r.id === state.selectedEntryId) || null;
    renderDetail(state, activeEntry);
}

function renderDetail(state, entry) {
    const container = document.getElementById("entry-detail");
    const title = document.getElementById("detail-title");
    const status = document.getElementById("detail-status");

    container.innerHTML = "";
    if (!entry) {
        title.textContent = "Select an entry";
        status.textContent = "No entry selected";
        const notice = document.createElement("p");
        notice.className = "notice";
        notice.textContent = "Choose an entry from the list to inspect its configuration-driven answers and notes.";
        container.appendChild(notice);
        return;
    }

    const teamObj = state.teamsByNumber.get(entry.targetTeamNumber) || null;
    const name = teamObj ? (teamObj.nickname || teamObj.name) : null;
    const formattedNum = teamObj ? Obsidianscout.formatTeam(teamObj.teamKey, teamObj.teamNumber) : entry.targetTeamNumber;
    const teamLabel = name ? `${formattedNum} ${name}` : `${formattedNum}`;

    title.textContent = `${entry.type} Entry: ${teamLabel}`;
    status.textContent = `Scouter Team: ${entry.ownerTeamNumber}`;

    // Meta Group
    const metaGroup = document.createElement("div");
    metaGroup.className = "pit-detail-group";
    const metaTitle = document.createElement("h3");
    metaTitle.textContent = "Metadata";
    metaGroup.appendChild(metaTitle);

    metaGroup.appendChild(buildDetailItem("Event", entry.eventKey || "N/A"));
    metaGroup.appendChild(buildDetailItem("Scouter Team", String(entry.ownerTeamNumber)));
    metaGroup.appendChild(buildDetailItem("Match", entry.matchNumber !== null ? getMatchLabel(entry.matchKey, entry.matchNumber) : "N/A"));
    metaGroup.appendChild(buildDetailItem("Created At", formatDateTime(entry.createdAt)));
    container.appendChild(metaGroup);

    // Load type configuration
    const configKey = entry.type.toLowerCase();
    const config = state.configs[configKey];
    
    if (!config || !config.fields) {
        const errorMsg = document.createElement("p");
        errorMsg.className = "notice";
        errorMsg.textContent = "No configuration layout found for this type.";
        container.appendChild(errorMsg);
        return;
    }

    const groups = groupFields(config.fields);
    groups.forEach(group => {
        const groupNode = document.createElement("div");
        groupNode.className = "pit-detail-group";

        if (group.title) {
            const groupTitle = document.createElement("h3");
            groupTitle.textContent = group.title;
            groupNode.appendChild(groupTitle);
        }

        group.fields.forEach(field => {
            const value = entry.data ? entry.data[field.id] : undefined;
            const item = buildDetailItem(
                (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label,
                formatFieldValue(field, value)
            );
            groupNode.appendChild(item);
        });

        if (group.fields.length) {
            container.appendChild(groupNode);
        }
    });
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
                title: (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label, 
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
            return (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(opt.label) : opt.label;
        }
        return String(value);
    }
    return String(value);
}

function buildDetailItem(label, value) {
    const item = document.createElement("div");
    item.className = "pit-detail-item";
    const span = document.createElement("span");
    span.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    item.appendChild(span);
    item.appendChild(strong);
    return item;
}

function appendCell(tr, text) {
    const td = document.createElement("td");
    td.textContent = text;
    tr.appendChild(td);
}

function appendTypeCell(tr, type) {
    const td = document.createElement("td");
    const badge = document.createElement("span");
    badge.className = `status-badge ${type === "Pit" ? "complete" : type === "Match" ? "missing" : "warning"}`;
    // Add custom styling class/override for qualitative if needed
    badge.textContent = type;
    td.appendChild(badge);
    tr.appendChild(td);
}

function formatDateTime(value) {
    if (!value) return "--";
    const date = new Date(value);
    if (isNaN(date.getTime())) return "--";
    return date.toLocaleString();
}

function summarizeFilterStatus(state, count) {
    const parts = [`${count} visible`];
    if (state.filters.type !== "all") {
        parts.push(`type: ${state.filters.type}`);
    }
    if (state.filters.teamQuery) {
        parts.push(`search: ${state.filters.teamQuery}`);
    }
    if (state.filters.matchNumber) {
        parts.push(`match: ${state.filters.matchNumber}`);
    }
    return parts.join(" | ");
}

function exportCsv(state) {
    const rows = getFilteredAndSortedRows(state);
    const header = ["Team Number", "Team Name", "Match Number", "Type", "Scouter Team", "Updated At", "Details"];
    const csvRows = [header];

    rows.forEach(row => {
        const teamObj = state.teamsByNumber.get(row.targetTeamNumber) || null;
        const teamName = teamObj ? (teamObj.nickname || teamObj.name || "") : "";
        const matchNum = row.matchNumber !== null ? String(row.matchNumber) : "N/A";
        const typeLabel = row.type;
        const scouter = row.ownerTeamNumber;
        const updatedAt = formatDateTime(row.createdAt);

        // Gather all configuration-driven key-values for details
        const configKey = row.type.toLowerCase();
        const config = state.configs[configKey];
        const detailsList = [];

        if (config && config.fields) {
            config.fields.forEach(field => {
                if (field.type !== "section" && !RESERVED_FIELDS.has(field.id)) {
                    const val = row.data ? row.data[field.id] : undefined;
                    if (val !== undefined && val !== null && val !== "") {
                        const label = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;
                        const formatted = formatFieldValue(field, val);
                        detailsList.push(`${label}: ${formatted}`);
                    }
                }
            });
        }

        const detailsStr = detailsList.join(" | ");
        const formattedNum = teamObj ? Obsidianscout.formatTeam(teamObj.teamKey, teamObj.teamNumber) : row.targetTeamNumber;
        csvRows.push([formattedNum, teamName, matchNum, typeLabel, scouter, updatedAt, detailsStr]);
    });

    const csvText = csvRows.map(row => row.map(csvCell).join(",")).join("\r\n");
    const blob = new Blob([csvText], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `all-scouting-data-${state.filters.eventKey || "all"}.csv`;
    link.click();
    URL.revokeObjectURL(url);
}

function csvCell(value) {
    const text = value === null || value === undefined ? "" : String(value);
    return `"${text.replace(/"/g, '""')}"`;
}

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
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
