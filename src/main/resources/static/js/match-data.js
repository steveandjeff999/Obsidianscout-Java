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
        config: null,
        entries: [],
        selectedEntryId: null,
        filters: {
            eventKey: "",
            teamQuery: "",
            matchNumber: "",
            sortBy: "match-asc"
        },
        reload: null
    };

    // Setup wrapper for dynamic content
    const mainContent = document.querySelector(".main-content");
    const titleCard = mainContent.querySelector(".card");
    const dynamicContainer = document.createElement("div");
    dynamicContainer.id = "match-dynamic-container";

    let nextSib = titleCard.nextElementSibling;
    while (nextSib) {
        const temp = nextSib.nextElementSibling;
        dynamicContainer.appendChild(nextSib);
        nextSib = temp;
    }
    mainContent.appendChild(dynamicContainer);
    const originalHTML = dynamicContainer.innerHTML;

    async function loadMatchScoutingData() {
        state.reload = loadMatchScoutingData;
        Obsidianscout.showLoadingSpinner(dynamicContainer, t('match_data.loading_scouting_data', "Loading match scouting data..."));
        try {
            const settingsResponse = await Obsidianscout.request("/api/settings");
            state.settings = settingsResponse ? (settingsResponse.settings || settingsResponse) : {};
            state.eventKey = Obsidianscout.resolveEventKey(state.settings);
            state.filters.eventKey = state.eventKey;

            const [matchConfig, matchEntries, events] = await Promise.all([
                Obsidianscout.request("/api/config"),
                Obsidianscout.request("/api/scouting?includePrescout=true&all=true"),
                Obsidianscout.request(`/api/events?year=${state.settings.year}&cached=1`)
            ]);

            dynamicContainer.innerHTML = originalHTML;

            state.config = matchConfig;
            state.events = normalizeEvents(events, state.eventKey, state.settings);

            // Normalize match entries with calculated score
            state.entries = (matchEntries || []).map(e => {
                const totalScore = computeEntryTotalScore(matchConfig, e.data);
                return {
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
                    data: e.data,
                    hasDiscrepancy: e.hasDiscrepancy || false,
                    conflictingTeams: e.conflictingTeams || [],
                    totalScore: totalScore
                };
            });

            // Parse URL parameters for deep-linking
            const urlParams = new URLSearchParams(window.location.search);
            const paramSearch = urlParams.get("search") || urlParams.get("team") || urlParams.get("teamNumber") || "";
            const paramMatch = urlParams.get("match") || urlParams.get("matchNumber") || "";
            const paramMatchKey = urlParams.get("matchKey") || "";
            const paramEvent = urlParams.get("eventKey") || urlParams.get("event");
            const paramEntryId = urlParams.get("entryId") || urlParams.get("id") || "";

            if (paramEvent !== null && paramEvent !== undefined) {
                state.filters.eventKey = (paramEvent === "all") ? "" : paramEvent;
            }
            if (paramSearch) {
                state.filters.teamQuery = paramSearch.trim().toLowerCase();
            }
            if (paramMatch) {
                state.filters.matchNumber = paramMatch.trim();
            } else if (paramMatchKey) {
                const matchNum = paramMatchKey.match(/_qm(\d+)/i) || paramMatchKey.match(/(\d+)$/);
                if (matchNum) {
                    state.filters.matchNumber = matchNum[1];
                }
            }
            if (paramEntryId) {
                state.selectedEntryId = paramEntryId.startsWith("match-") ? paramEntryId : `match-${paramEntryId}`;
            }

            initControls(state);
            await loadTeamsForEvent(state);
            renderAll(state);
        } catch (error) {
            console.error("Initialization error:", error);
            Obsidianscout.showRetryButton(dynamicContainer, "Failed to load match data: " + error.message, loadMatchScoutingData);
        }
    }

    await loadMatchScoutingData();
});

const RESERVED_FIELDS = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);

function computeEntryTotalScore(config, data) {
    if (!config || !config.fields || !data) return 0;
    let sum = 0;
    config.fields.forEach(field => {
        if (!RESERVED_FIELDS.has(field.id) && field.type !== "section") {
            sum += calculateFieldPoints(field, data[field.id]);
        }
    });
    return Number.isInteger(sum) ? sum : Number(sum.toFixed(2));
}

function calculateFieldPoints(field, value) {
    if (!field || value === null || value === undefined || value === "") {
        return 0;
    }
    const type = String(field.type || "").toLowerCase();
    if (type === "counter" || type === "number" || type === "rating") {
        const num = Number(value);
        if (isNaN(num)) return 0;
        const ptsPer = Number(field.pointsPer || 0);
        return num * ptsPer;
    }
    if (type === "checkbox") {
        const enabled = value === true || value === "true" || value === 1 || value === "1";
        if (!enabled) return 0;
        return Number(field.pointsPer || 0);
    }
    if (type === "select") {
        const options = field.options || [];
        const opt = options.find(o => o.value === value || o.label === value);
        if (!opt || opt.points === undefined || opt.points === null) return 0;
        return Number(opt.points) || 0;
    }
    return 0;
}

function formatFieldValue(field, value, isMatch = true) {
    if (value === null || value === undefined || value === "") {
        return "--";
    }
    if (field.type === "image" || field.type === "image_upload" || field.type === "photo" || (typeof value === "string" && value.startsWith("data:image/"))) {
        return "📷 [Photo]";
    }
    let displayVal;
    if (field.type === "checkbox") {
        displayVal = value ? "Yes" : "No";
    } else if (field.type === "select") {
        const options = field.options || [];
        const opt = options.find(o => o.value === value || o.label === value);
        if (opt) {
            displayVal = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(opt.label) : opt.label;
        } else {
            displayVal = String(value);
        }
    } else {
        displayVal = String(value);
    }

    if (isMatch) {
        const pts = calculateFieldPoints(field, value);
        if (pts !== 0) {
            const formattedPts = Number.isInteger(pts) ? String(pts) : String(Number(pts.toFixed(2)));
            const unit = Math.abs(pts) === 1 ? "pt" : "pts";
            return `${displayVal} (${formattedPts} ${unit})`;
        }
    }

    return displayVal;
}

function initControls(state) {
    const eventFilter = document.getElementById("event-filter");
    const teamSearch = document.getElementById("team-search");
    const matchFilter = document.getElementById("match-filter");
    const sortSelect = document.getElementById("sort-select");
    const resetBtn = document.getElementById("reset-filters");
    const exportBtn = document.getElementById("export-match-csv");

    if (eventFilter) {
        eventFilter.innerHTML = "";
        state.events.forEach(e => {
            const opt = document.createElement("option");
            opt.value = e.eventKey;
            opt.textContent = `${localize(e.name)}${e.eventKey ? ` (${e.eventKey})` : ""}`;
            if (e.eventKey === state.filters.eventKey) {
                opt.selected = true;
            }
            eventFilter.appendChild(opt);
        });

        eventFilter.addEventListener("change", async (e) => {
            state.filters.eventKey = e.target.value;
            await loadTeamsForEvent(state);
            renderAll(state);
        });
    }

    if (teamSearch) {
        teamSearch.value = state.filters.teamQuery;
        teamSearch.addEventListener("input", (e) => {
            state.filters.teamQuery = e.target.value.trim().toLowerCase();
            renderTableAndDetail(state);
        });
    }

    if (matchFilter) {
        matchFilter.value = state.filters.matchNumber;
        matchFilter.addEventListener("input", (e) => {
            state.filters.matchNumber = e.target.value.trim();
            renderTableAndDetail(state);
        });
    }

    if (sortSelect) {
        sortSelect.value = state.filters.sortBy;
        sortSelect.addEventListener("change", (e) => {
            state.filters.sortBy = e.target.value;
            renderTableAndDetail(state);
        });
    }

    if (resetBtn) {
        resetBtn.addEventListener("click", () => {
            state.filters.teamQuery = "";
            state.filters.matchNumber = "";
            state.filters.sortBy = "match-asc";
            if (teamSearch) teamSearch.value = "";
            if (matchFilter) matchFilter.value = "";
            if (sortSelect) sortSelect.value = "match-asc";
            renderTableAndDetail(state);
        });
    }

    if (exportBtn) {
        exportBtn.addEventListener("click", () => exportCsv(state));
    }
}

async function loadTeamsForEvent(state) {
    state.teamsByNumber.clear();
    const eventKey = state.filters.eventKey || state.eventKey;
    if (!eventKey || eventKey === "prescout") {
        return;
    }
    try {
        const teams = await Obsidianscout.request(`/api/teams?eventKey=${eventKey}`);
        (teams || []).forEach(t => {
            state.teamsByNumber.set(t.teamNumber, t);
        });
    } catch (err) {
        console.warn("Failed to load teams for event:", err);
    }
}

function renderAll(state) {
    renderMetrics(state);
    renderTableAndDetail(state);
}

function renderMetrics(state) {
    const list = state.entries.filter(e => !state.filters.eventKey || e.eventKey === state.filters.eventKey);
    const uniqueTeams = new Set(list.map(e => e.targetTeamNumber).filter(Boolean));
    const totalScoreSum = list.reduce((acc, e) => acc + (e.totalScore || 0), 0);
    const avgScore = list.length > 0 ? (totalScoreSum / list.length).toFixed(1) : "0.0";

    let lastUpdated = "--";
    if (list.length > 0) {
        const sortedByDate = list.slice().sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
        lastUpdated = formatDateTime(sortedByDate[0].createdAt);
    }

    const entryCountEl = document.getElementById("match-entry-count");
    const teamCountEl = document.getElementById("match-team-count");
    const avgScoreEl = document.getElementById("match-avg-score");
    const lastUpdateEl = document.getElementById("match-last-updated");

    if (entryCountEl) entryCountEl.textContent = list.length.toString();
    if (teamCountEl) teamCountEl.textContent = uniqueTeams.size.toString();
    if (avgScoreEl) avgScoreEl.textContent = `${avgScore} pts`;
    if (lastUpdateEl) lastUpdateEl.textContent = lastUpdated;
}

function getFilteredAndSortedRows(state) {
    let rows = state.entries.slice();

    // 1. Event filter
    if (state.filters.eventKey) {
        rows = rows.filter(e => e.eventKey === state.filters.eventKey);
    }

    // 2. Team search
    if (state.filters.teamQuery) {
        rows = rows.filter(e => {
            const teamNum = String(e.targetTeamNumber);
            const teamObj = state.teamsByNumber.get(e.targetTeamNumber) || null;
            const teamName = teamObj ? (teamObj.nickname || teamObj.name || "").toLowerCase() : "";
            return teamNum.includes(state.filters.teamQuery) || teamName.includes(state.filters.teamQuery);
        });
    }

    // 3. Match filter
    if (state.filters.matchNumber) {
        const matchNumFilter = parseInt(state.filters.matchNumber, 10);
        if (!isNaN(matchNumFilter)) {
            rows = rows.filter(e => {
                const num = extractMatchNumber(e.matchNumber, e.matchKey);
                return num === matchNumFilter;
            });
        }
    }

    // 4. Sorting
    rows.sort((a, b) => {
        if (state.filters.sortBy === "newest") {
            return new Date(b.createdAt) - new Date(a.createdAt);
        }
        if (state.filters.sortBy === "oldest") {
            return new Date(a.createdAt) - new Date(b.createdAt);
        }
        if (state.filters.sortBy === "score-desc") {
            return (b.totalScore || 0) - (a.totalScore || 0);
        }
        if (state.filters.sortBy === "team") {
            if (a.targetTeamNumber !== b.targetTeamNumber) {
                return a.targetTeamNumber - b.targetTeamNumber;
            }
            return (a.matchNumber || 0) - (b.matchNumber || 0);
        }
        if (state.filters.sortBy === "match-desc") {
            const numA = extractMatchNumber(a.matchNumber, a.matchKey);
            const numB = extractMatchNumber(b.matchNumber, b.matchKey);
            return numB - numA;
        }
        // Default: match-asc
        const numA = extractMatchNumber(a.matchNumber, a.matchKey);
        const numB = extractMatchNumber(b.matchNumber, b.matchKey);
        if (numA !== numB) {
            return numA - numB;
        }
        return (a.targetTeamNumber || 0) - (b.targetTeamNumber || 0);
    });

    return rows;
}

function renderTableAndDetail(state) {
    const rows = getFilteredAndSortedRows(state);
    const table = document.getElementById("match-data-table");
    if (!table) return;
    const body = table.querySelector("tbody");
    body.innerHTML = "";

    const countBadge = document.getElementById("match-table-count");
    if (countBadge) {
        countBadge.textContent = `${rows.length} ${rows.length === 1 ? 'entry' : 'entries'}`;
    }

    const filterStatus = document.getElementById("match-filter-status");
    if (filterStatus) {
        filterStatus.textContent = summarizeFilterStatus(state, rows.length);
    }

    if (rows.length === 0) {
        body.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--muted); padding: 32px 16px;">${t('match_data.no_entries_found', "No match scouting entries found matching your filters.")}</td></tr>`;
        renderDetail(state, null);
        return;
    }

    // Auto-select row if selectedEntryId matches
    if (state.selectedEntryId) {
        const found = rows.find(r => r.id === state.selectedEntryId || String(r.originalId) === String(state.selectedEntryId));
        if (found) {
            state.selectedEntryId = found.id;
        } else if (!rows.some(r => r.id === state.selectedEntryId)) {
            state.selectedEntryId = rows[0].id;
        }
    } else {
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
        const scouterName = (row.data && (row.data.username || row.data.scouter || row.data.scout_name)) || (row.ownerTeamNumber ? `Team ${row.ownerTeamNumber}` : "Scout");

        // Team cell
        const tdTeam = document.createElement("td");
        tdTeam.textContent = teamLabel;
        if (row.hasDiscrepancy) {
            const warnSpan = document.createElement("span");
            warnSpan.textContent = " ⚠️";
            warnSpan.style.color = "#eab308";
            warnSpan.style.fontWeight = "bold";
            warnSpan.title = "Discrepancy detected between partner teams: " + (row.conflictingTeams || []).join(", ");
            tdTeam.appendChild(warnSpan);
        }
        tr.appendChild(tdTeam);

        appendCell(tr, matchLabel);

        // Score cell
        const tdScore = document.createElement("td");
        tdScore.innerHTML = `<strong style="color: var(--primary, #38bdf8);">${row.totalScore}</strong> <span class="metric-subtext">pts</span>`;
        tr.appendChild(tdScore);

        appendCell(tr, scouterName);
        appendCell(tr, formatDateTime(row.createdAt));

        tr.addEventListener("click", () => {
            state.selectedEntryId = row.id;
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
    const container = document.getElementById("match-detail");
    const title = document.getElementById("match-detail-title");
    const status = document.getElementById("match-detail-status");
    if (!container || !title || !status) return;

    container.innerHTML = "";

    if (!entry) {
        title.textContent = t('all_data.select_an_entry', "Select an entry");
        status.textContent = t('all_data.no_entry_selected', "No entry selected");
        container.innerHTML = `<p class="notice">${t('all_data.choose_an_entry_from_the_list_', "Choose an entry from the list to inspect its configuration-driven answers and notes.")}</p>`;
        return;
    }

    const teamObj = state.teamsByNumber.get(entry.targetTeamNumber) || null;
    const name = teamObj ? (teamObj.nickname || teamObj.name) : null;
    const formattedNum = teamObj ? Obsidianscout.formatTeam(teamObj.teamKey, teamObj.teamNumber) : entry.targetTeamNumber;
    const teamLabel = name ? `${formattedNum} ${name}` : `${formattedNum}`;
    const matchLabel = getMatchLabel(entry.matchKey, entry.matchNumber);

    title.textContent = `${matchLabel}: ${teamLabel}`;
    status.textContent = `Scouter Team: ${entry.ownerTeamNumber}`;

    if (entry.hasDiscrepancy) {
        const warnBanner = document.createElement("div");
        warnBanner.className = "sharing-notice mb-12";
        warnBanner.style.borderColor = "#eab308";
        warnBanner.style.background = "rgba(234, 179, 8, 0.08)";
        warnBanner.style.color = "#854d0e";
        warnBanner.style.padding = "12px 16px";
        warnBanner.style.borderRadius = "8px";
        warnBanner.style.border = "1px solid #eab308";
        warnBanner.innerHTML = `
            <div style="display:flex; align-items:flex-start; gap:10px;">
                <span class="icon" style="font-size:1.2rem;">⚠️</span>
                <div style="flex:1;">
                    <div style="font-weight:700; color:#fbbf24;">Discrepancy Detected</div>
                    <div style="font-size:0.85rem; margin-top:2px; color:#cbd5e1;">
                        Different scouting data exists for this entry from partner teams: <strong>${(entry.conflictingTeams || []).join(", ")}</strong>.
                    </div>
                    <button id="btn-resolve-conflict" class="btn primary btn-sm" style="margin-top:8px; background:#eab308; color:#0f172a; font-weight:700; border:none; padding:5px 12px; border-radius:6px; cursor:pointer; font-size:0.8rem;">
                        ⚡ Resolve Discrepancy
                    </button>
                </div>
            </div>
        `;
        const resolveBtn = warnBanner.querySelector("#btn-resolve-conflict");
        if (resolveBtn) {
            resolveBtn.addEventListener("click", () => {
                const conflicting = state.entries.filter(e =>
                    e.targetTeamNumber === entry.targetTeamNumber &&
                    e.eventKey === entry.eventKey &&
                    e.matchNumber === entry.matchNumber
                );
                const config = state.config;
                Obsidianscout.openConflictResolutionModal({
                    type: "match",
                    fields: config ? config.fields || [] : [],
                    conflictingEntries: conflicting.length > 0 ? conflicting : [entry],
                    onResolved: () => {
                        if (typeof state.reload === 'function') state.reload();
                    }
                });
            });
        }
        container.appendChild(warnBanner);
    }

    // Metadata Group
    const metaGroup = document.createElement("div");
    metaGroup.className = "pit-detail-group";
    const metaTitle = document.createElement("h3");
    metaTitle.textContent = "Metadata";
    metaGroup.appendChild(metaTitle);

    metaGroup.appendChild(buildDetailItem("Event", entry.eventKey || "N/A"));
    metaGroup.appendChild(buildDetailItem("Scouter Team", String(entry.ownerTeamNumber)));
    metaGroup.appendChild(buildDetailItem("Match", matchLabel));
    metaGroup.appendChild(buildDetailItem("Total Score", `${entry.totalScore} pts`));
    metaGroup.appendChild(buildDetailItem("Created At", formatDateTime(entry.createdAt)));
    container.appendChild(metaGroup);

    // Configuration Fields
    const config = state.config;
    if (!config || !config.fields) {
        const errorMsg = document.createElement("p");
        errorMsg.className = "notice";
        errorMsg.textContent = "No match scouting form configuration found.";
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
            const isImage = (field.type === "image" || field.type === "image_upload" || field.type === "photo" || (typeof value === "string" && value.startsWith("data:image/")));
            if (isImage && value) {
                const item = document.createElement("div");
                item.className = "pit-detail-item";
                const label = document.createElement("span");
                label.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;
                item.appendChild(label);

                const imageCard = document.createElement("div");
                imageCard.style.cssText = "margin-top:6px;padding:8px;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.1);border-radius:10px;display:flex;flex-direction:column;align-items:center;gap:6px;";
                const imgEl = document.createElement("img");
                imgEl.src = value;
                imgEl.style.cssText = "max-width:100%;max-height:200px;object-fit:contain;border-radius:8px;cursor:pointer;";
                imgEl.title = "Click to inspect full image";
                imgEl.addEventListener("click", () => {
                    Obsidianscout.showImageModal(value, `${(window.Obsidianscout && typeof Obsidianscout.localize === 'function' ? Obsidianscout.localize(field.label) : field.label)} - Team ${entry.targetTeamNumber}`);
                });
                imageCard.appendChild(imgEl);
                item.appendChild(imageCard);
                groupNode.appendChild(item);
            } else {
                const item = buildDetailItem(
                    (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label,
                    formatFieldValue(field, value, true)
                );
                groupNode.appendChild(item);
            }
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

function formatDateTime(value) {
    if (!value) return "--";
    const date = new Date(value);
    if (isNaN(date.getTime())) return "--";
    return date.toLocaleString();
}

function extractMatchNumber(matchNumber, matchKey) {
    if (matchNumber !== null && matchNumber !== undefined && matchNumber !== "") {
        const parsed = parseInt(matchNumber, 10);
        if (!isNaN(parsed)) return parsed;
    }
    if (matchKey) {
        const m = matchKey.match(/_qm(\d+)/i) || matchKey.match(/(\d+)$/);
        if (m) return parseInt(m[1], 10);
    }
    return 0;
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

function normalizeEvents(events, activeKey, settings) {
    const list = [{ eventKey: "", name: "All events", year: settings.year }, { eventKey: "prescout", name: "Prescouting Data", year: settings.year }]
        .concat(Array.isArray(events) ? events.slice() : []);
    if (activeKey && !list.some((event) => event.eventKey === activeKey)) {
        list.splice(1, 0, {
            eventKey: activeKey,
            name: `${activeKey.toUpperCase()} (Current)`,
            year: settings.year
        });
    }
    return list;
}

function summarizeFilterStatus(state, count) {
    const parts = [`${count} visible`];
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
    const header = ["Team Number", "Team Name", "Match Number", "Total Score", "Scouter Team", "Updated At", "Details"];
    const csvRows = [header];

    rows.forEach(row => {
        const teamObj = state.teamsByNumber.get(row.targetTeamNumber) || null;
        const teamName = teamObj ? (teamObj.nickname || teamObj.name || "") : "";
        const matchNum = row.matchNumber !== null ? String(row.matchNumber) : "N/A";
        const scouter = row.ownerTeamNumber;
        const score = row.totalScore;
        const updatedAt = formatDateTime(row.createdAt);

        const config = state.config;
        const detailsList = [];

        if (config && config.fields) {
            config.fields.forEach(field => {
                if (field.type !== "section" && !RESERVED_FIELDS.has(field.id)) {
                    const val = row.data ? row.data[field.id] : undefined;
                    if (val !== undefined && val !== null && val !== "") {
                        const label = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;
                        const formatted = formatFieldValue(field, val, true);
                        detailsList.push(`${label}: ${formatted}`);
                    }
                }
            });
        }

        const detailsStr = detailsList.join(" | ");
        const formattedNum = teamObj ? Obsidianscout.formatTeam(teamObj.teamKey, teamObj.teamNumber) : row.targetTeamNumber;

        csvRows.push([
            csvEscape(formattedNum),
            csvEscape(teamName),
            csvEscape(matchNum),
            csvEscape(String(score)),
            csvEscape(String(scouter)),
            csvEscape(updatedAt),
            csvEscape(detailsStr)
        ]);
    });

    const csvContent = csvRows.map(r => r.join(",")).join("\n");
    const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `match-scouting-data-${state.filters.eventKey || "all"}-${new Date().toISOString().slice(0,10)}.csv`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
}

function csvEscape(value) {
    const text = value === null || value === undefined ? "" : String(value);
    return `"${text.replace(/"/g, '""')}"`;
}
