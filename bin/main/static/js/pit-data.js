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
        config: null,
        fields: [],
        events: [],
        teams: [],
        entries: [],
        eventKey: "",
        selectedTeamNumber: null,
        quickFieldId: ""
    };

    // Setup wrapper for dynamic content
    const mainContent = document.querySelector(".main-content");
    const titleCard = mainContent.querySelector(".card");
    const dynamicContainer = document.createElement("div");
    dynamicContainer.id = "pit-dynamic-container";
    
    let nextSib = titleCard.nextElementSibling;
    while (nextSib) {
        const temp = nextSib.nextElementSibling;
        dynamicContainer.appendChild(nextSib);
        nextSib = temp;
    }
    mainContent.appendChild(dynamicContainer);
    const originalHTML = dynamicContainer.innerHTML;

    async function loadAllPitData() {
        Obsidianscout.showLoadingSpinner(dynamicContainer, "Loading pit scouting data...");
        try {
            const settingsResponse = await Obsidianscout.request("/api/settings");
            state.settings = settingsResponse.settings;
            state.eventKey = Obsidianscout.resolveEventKey(state.settings);

            const [config, entries, events] = await Promise.all([
                Obsidianscout.request("/api/pit-config"),
                Obsidianscout.request("/api/pit-scouting?includePrescout=true"),
                Obsidianscout.request(`/api/events?year=${state.settings.year}&cached=1`)
            ]);

            dynamicContainer.innerHTML = originalHTML;

            state.config = config;
            state.fields = buildDisplayFields(config.fields || []);
            state.entries = (Array.isArray(entries) ? entries : []).map(e => {
                if (e.isPrescout) {
                    return { ...e, eventKey: "prescout" };
                }
                return e;
            });
            state.events = normalizeEvents(events, state.eventKey, state.settings);
            state.quickFieldId = pickDefaultQuickField(state.fields);

            initControls(state);
            await loadTeamsForEvent(state);
            renderAll(state);
        } catch (error) {
            console.error("Failed to load pit data:", error);
            Obsidianscout.showRetryButton(dynamicContainer, "Failed to load pit scouting data: " + error.message, loadAllPitData);
        }
    }

    await loadAllPitData();
});

const RESERVED_FIELDS = new Set(["eventKey", "targetTeamNumber"]);

function initControls(state) {
    const eventFilter = document.getElementById("event-filter");
    const teamSearch = document.getElementById("team-search");
    const fieldFilter = document.getElementById("field-filter");
    const missingOnly = document.getElementById("missing-only");
    const exportButton = document.getElementById("export-pit-csv");

    eventFilter.innerHTML = "";
    state.events.forEach((event) => {
        const option = document.createElement("option");
        option.value = event.eventKey;
        option.textContent = `${event.name} (${event.eventKey})`;
        option.selected = event.eventKey === state.eventKey;
        eventFilter.appendChild(option);
    });
    eventFilter.addEventListener("change", async () => {
        state.eventKey = eventFilter.value;
        state.selectedTeamNumber = null;
        await loadTeamsForEvent(state);
        renderAll(state);
    });

    fieldFilter.innerHTML = "";
    state.fields.forEach((field) => {
        const option = document.createElement("option");
        option.value = field.id;
        option.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;
        option.selected = field.id === state.quickFieldId;
        fieldFilter.appendChild(option);
    });
    fieldFilter.addEventListener("change", () => {
        state.quickFieldId = fieldFilter.value;
        renderTable(state);
    });

    teamSearch.addEventListener("input", () => renderTable(state));
    missingOnly.addEventListener("change", () => renderTable(state));
    exportButton.addEventListener("click", () => exportCsv(state));
}

async function loadTeamsForEvent(state) {
    if (!state.eventKey) {
        state.teams = [];
        return;
    }
    if (state.eventKey === "prescout") {
        const uniqueTeams = Array.from(new Set(state.entries.filter(e => e.isPrescout).map(e => e.targetTeamNumber).filter(Boolean)));
        state.teams = uniqueTeams.map(teamNumber => ({ teamNumber, teamKey: `frc${teamNumber}`, nickname: `Team ${teamNumber}` }));
        return;
    }
    try {
        const teams = await Obsidianscout.request(`/api/teams?eventKey=${state.eventKey}`);
        state.teams = Array.isArray(teams) ? teams : [];
    } catch (error) {
        state.teams = [];
    }
}

function renderAll(state) {
    renderMetrics(state);
    renderTable(state);
    renderDetail(state);
}

function renderMetrics(state) {
    const entries = entriesForEvent(state);
    const latestByTeam = latestEntriesByTeam(entries);
    const totalTeams = buildRows(state, latestByTeam).length;
    const scoutedTeams = latestByTeam.size;
    const lastUpdated = entries
        .map((entry) => new Date(entry.createdAt))
        .filter((date) => !Number.isNaN(date.getTime()))
        .sort((a, b) => b - a)[0];

    document.getElementById("pit-entry-count").textContent = entries.length.toString();
    document.getElementById("pit-team-count").textContent = scoutedTeams.toString();
    document.getElementById("pit-coverage").textContent = totalTeams ? `${Math.round((scoutedTeams / totalTeams) * 100)}%` : "0%";
    document.getElementById("pit-last-updated").textContent = lastUpdated ? lastUpdated.toLocaleDateString() : "--";
}

function renderTable(state) {
    const table = document.getElementById("pit-data-table");
    const body = table.querySelector("tbody");
    const rows = filteredRows(state);
    const quickField = fieldById(state, state.quickFieldId);
    const header = document.getElementById("quick-field-header");
    const countBadge = document.getElementById("pit-table-count");
    const status = document.getElementById("pit-filter-status");

    header.textContent = quickField ? ((window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(quickField.label) : quickField.label) : ((window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('quick_field','Quick Field') : 'Quick Field');
    countBadge.textContent = `${rows.length} ${(window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(rows.length === 1 ? 'team' : 'teams', rows.length === 1 ? 'team' : 'teams') : (rows.length === 1 ? 'team' : 'teams')}`;
    status.textContent = summarizeFilters(state, rows.length);
    body.innerHTML = "";

    if (!rows.length) {
        const row = document.createElement("tr");
        const cell = document.createElement("td");
        cell.colSpan = 4;
        cell.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.no_teams_match','No teams match the current filters.') : 'No teams match the current filters.';
        row.appendChild(cell);
        body.appendChild(row);
        return;
    }

    rows.forEach((rowData) => {
        const row = document.createElement("tr");
        row.className = "clickable-row";
        if (rowData.teamNumber === state.selectedTeamNumber) {
            row.classList.add("selected");
        }

        const tdTeam = document.createElement("td");
        tdTeam.textContent = teamLabel(rowData);
        if (rowData.entry && rowData.entry.hasDiscrepancy) {
            const warnSpan = document.createElement("span");
            warnSpan.textContent = " ⚠️";
            warnSpan.style.color = "#eab308";
            warnSpan.style.fontWeight = "bold";
            warnSpan.title = "Discrepancy detected between partner teams: " + (rowData.entry.conflictingTeams || []).join(", ");
            tdTeam.appendChild(warnSpan);
        }
        row.appendChild(tdTeam);

        appendStatusCell(row, (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? (rowData.entry ? Obsidianscout.t('scouted','Scouted') : Obsidianscout.t('missing','Missing')) : (rowData.entry ? 'Scouted' : 'Missing'), Boolean(rowData.entry));
        appendCell(row, rowData.entry ? formatDateTime(rowData.entry.createdAt) : "--");
        appendCell(row, rowData.entry && quickField ? formatValue(quickField, rowData.entry.data[quickField.id]) : "--");

        row.addEventListener("click", () => {
            state.selectedTeamNumber = rowData.teamNumber;
            renderTable(state);
            renderDetail(state);
        });
        body.appendChild(row);
    });
}

function renderDetail(state) {
    const detail = document.getElementById("pit-detail");
    const title = document.getElementById("pit-detail-title");
    const status = document.getElementById("pit-detail-status");
    const latestByTeam = latestEntriesByTeam(entriesForEvent(state));
    const rows = buildRows(state, latestByTeam);
    const selected = rows.find((row) => row.teamNumber === state.selectedTeamNumber);

    detail.innerHTML = "";
    if (!selected) {
        title.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.select_team','Select a team') : 'Select a team';
        status.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.no_team_selected','No team selected') : 'No team selected';
        const notice = document.createElement("p");
        notice.className = "notice";
        notice.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.choose_team_notice','Choose a team from the coverage table to see its pit scouting answers.') : 'Choose a team from the coverage table to see its pit scouting answers.';
        detail.appendChild(notice);
        return;
    }

    title.textContent = teamLabel(selected);
    status.textContent = selected.entry ? `${(window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.updated','Updated') : 'Updated'} ${formatDateTime(selected.entry.createdAt)}` : ((window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.needs_pit','Needs pit scouting') : 'Needs pit scouting');

    if (selected.entry && selected.entry.hasDiscrepancy) {
        const warnBanner = document.createElement("div");
        warnBanner.className = "sharing-notice mb-12";
        warnBanner.style.borderColor = "#eab308";
        warnBanner.style.background = "rgba(234, 179, 8, 0.08)";
        warnBanner.style.color = "#854d0e";
        warnBanner.style.padding = "10px 16px";
        warnBanner.style.borderRadius = "6px";
        warnBanner.style.border = "1px solid";
        warnBanner.innerHTML = `
            <span class="icon">⚠️</span>
            <div style="flex:1;">
                <strong>Discrepancy Warning:</strong> Different pit scouting data exists for this team from: <strong>${(selected.entry.conflictingTeams || []).join(", ")}</strong>. You can view or resolve this discrepancy in the Alliance Scouting Data page.
            </div>
        `;
        detail.appendChild(warnBanner);
    }

    if (!selected.entry) {
        const notice = document.createElement("p");
        notice.className = "notice";
        notice.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.no_pit_entry','No pit entry has been submitted for this team at the selected event.') : 'No pit entry has been submitted for this team at the selected event.';
        detail.appendChild(notice);
        return;
    }

    const groups = groupFields(state.config.fields || []);
    groups.forEach((group) => {
        const groupNode = document.createElement("div");
        groupNode.className = "pit-detail-group";

        if (group.title) {
            const groupTitle = document.createElement("h3");
            groupTitle.textContent = group.title;
            groupNode.appendChild(groupTitle);
        }

        group.fields.forEach((field) => {
            const item = document.createElement("div");
            item.className = "pit-detail-item";
            const label = document.createElement("span");
            label.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;
            const value = document.createElement("strong");
            value.textContent = formatValue(field, selected.entry.data[field.id]);
            item.appendChild(label);
            item.appendChild(value);
            groupNode.appendChild(item);
        });

        if (group.fields.length) {
            detail.appendChild(groupNode);
        }
    });
}

function filteredRows(state) {
    const latestByTeam = latestEntriesByTeam(entriesForEvent(state));
    const query = document.getElementById("team-search").value.trim().toLowerCase();
    const missingOnly = document.getElementById("missing-only").checked;
    return buildRows(state, latestByTeam)
        .filter((row) => !missingOnly || !row.entry)
        .filter((row) => {
            if (!query) {
                return true;
            }
            return [
                row.teamNumber,
                row.team?.nickname,
                row.team?.name,
                row.team?.city,
                row.team?.state
            ].filter(Boolean).join(" ").toLowerCase().includes(query);
        });
}

function buildRows(state, latestByTeam) {
    const rows = [];
    const seen = new Set();
    state.teams.forEach((team) => {
        seen.add(team.teamNumber);
        rows.push({
            team,
            teamNumber: team.teamNumber,
            entry: latestByTeam.get(team.teamNumber) || null
        });
    });

    latestByTeam.forEach((entry, teamNumber) => {
        if (!seen.has(teamNumber)) {
            rows.push({
                team: null,
                teamNumber,
                entry
            });
        }
    });

    return rows.sort((a, b) => a.teamNumber - b.teamNumber);
}

function latestEntriesByTeam(entries) {
    const map = new Map();
    entries
        .slice()
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
        .forEach((entry) => {
            if (entry.targetTeamNumber && !map.has(entry.targetTeamNumber)) {
                map.set(entry.targetTeamNumber, entry);
            }
        });
    return map;
}

function entriesForEvent(state) {
    return state.entries.filter((entry) => !state.eventKey || entry.eventKey === state.eventKey);
}

function buildDisplayFields(fields) {
    return fields.filter((field) =>
        field.type !== "section" && !RESERVED_FIELDS.has(field.id)
    );
}

function groupFields(fields) {
    const groups = [];
    let current = { title: "", fields: [] };
    fields.forEach((field) => {
        if (field.type === "section") {
            if (current.title || current.fields.length) {
                groups.push(current);
            }
            current = { title: field.label, fields: [] };
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

function pickDefaultQuickField(fields) {
    const preferred = fields.find((field) => ["select", "number", "counter", "rating", "checkbox"].includes(field.type));
    return (preferred || fields[0] || {}).id || "";
}

function fieldById(state, id) {
    return state.fields.find((field) => field.id === id);
}

function normalizeEvents(events, activeKey, settings) {
    const list = [{ eventKey: "", name: "All events", year: settings.year }, { eventKey: "prescout", name: "Prescouting Data", year: settings.year }]
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

function teamLabel(row) {
    const displayNum = row.team ? Obsidianscout.formatTeam(row.team.teamKey, row.teamNumber) : row.teamNumber;
    const name = row.team?.nickname || row.team?.name || "";
    return `${displayNum}${name ? ` ${name}` : ""}`;
}

function appendCell(row, text) {
    const cell = document.createElement("td");
    cell.textContent = text;
    row.appendChild(cell);
}

function appendStatusCell(row, text, complete) {
    const cell = document.createElement("td");
    const badge = document.createElement("span");
    badge.className = complete ? "status-badge complete" : "status-badge missing";
    badge.textContent = text;
    cell.appendChild(badge);
    row.appendChild(cell);
}

function formatValue(field, value) {
    if (value === null || value === undefined || value === "") {
        return "--";
    }
    if (field.type === "checkbox") {
        return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? (value ? Obsidianscout.t('yes','Yes') : Obsidianscout.t('no','No')) : (value ? 'Yes' : 'No');
    }
    if (field.type === "select") {
        const option = (field.options || []).find((item) => item.value === value || item.label === value);
        return option ? ((window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(option.label) : option.label) : String(value);
    }
    return String(value);
}

function formatDateTime(value) {
    if (!value) {
        return "--";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return "--";
    }
    return date.toLocaleString();
}

function summarizeFilters(state, count) {
    const pieces = [`${count} visible`];
    if (document.getElementById("missing-only").checked) {
        pieces.push("missing only");
    }
    const query = document.getElementById("team-search").value.trim();
    if (query) {
        pieces.push(`search: ${query}`);
    }
    return pieces.join(" | ");
}

function exportCsv(state) {
    const rows = filteredRows(state);
    const header = [
        (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.csv.team_number','Team Number') : 'Team Number',
        (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.csv.team_name','Team Name') : 'Team Name',
        (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.csv.status','Status') : 'Status',
        (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('pitdata.csv.updated','Updated') : 'Updated'
    ].concat(state.fields.map((field) => (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label));
    const csvRows = [header];

    rows.forEach((row) => {
        const entry = row.entry;
        csvRows.push([
            row.teamNumber,
            row.team?.nickname || row.team?.name || "",
            entry ? ((window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('scouted','Scouted') : 'Scouted') : ((window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('missing','Missing') : 'Missing'),
            entry ? formatDateTime(entry.createdAt) : ""
        ].concat(state.fields.map((field) => entry ? formatValue(field, entry.data[field.id]) : "")));
    });

    const csvText = csvRows.map((row) => row.map(csvCell).join(",")).join("\r\n");
    const blob = new Blob([csvText], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `pit-data-${state.eventKey || "all"}.csv`;
    link.click();
    URL.revokeObjectURL(url);
}

function csvCell(value) {
    const text = value === null || value === undefined ? "" : String(value);
    return `"${text.replace(/"/g, '""')}"`;
}
