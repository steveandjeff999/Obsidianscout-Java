
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
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

    let ui = {};
    const state = {
        settings: null,
        config: null,
        fields: [],
        groups: [],
        rankingMetrics: [],
        entriesRaw: [],
        entries: [],
        teamsByNumber: new Map(),
        events: [],
        selectedEvent: "all",
        teamQuery: "",
        selectedMetric: "__composite__",
        aggregateMode: "avg",
        minEntries: 1,
        includePending: true,
        entrySort: "createdAt-desc",
        rankRows: [],
        selectedTeam: null,
        selectedEntryId: null
    };

    // Setup wrapper for dynamic content
    const mainContent = document.querySelector(".main-content");
    const titleCard = mainContent.querySelector(".card");
    const dynamicContainer = document.createElement("div");
    dynamicContainer.id = "qual-dynamic-container";
    
    let nextSib = titleCard.nextElementSibling;
    while (nextSib) {
        const temp = nextSib.nextElementSibling;
        dynamicContainer.appendChild(nextSib);
        nextSib = temp;
    }
    mainContent.appendChild(dynamicContainer);
    const originalHTML = dynamicContainer.innerHTML;

    function getUIElements() {
        return {
            totalEntries: document.getElementById("qual-total-entries"),
            totalTeams: document.getElementById("qual-total-teams"),
            metricSummary: document.getElementById("qual-metric-summary"),
            lastUpdated: document.getElementById("qual-last-updated"),
            eventFilter: document.getElementById("qual-event-filter"),
            teamSearch: document.getElementById("qual-team-search"),
            metricField: document.getElementById("qual-rank-metric"),
            aggregateMode: document.getElementById("qual-rank-aggregate"),
            minEntries: document.getElementById("qual-min-entries"),
            entrySort: document.getElementById("qual-entry-sort"),
            includePending: document.getElementById("qual-include-pending"),
            resetFilters: document.getElementById("qual-reset-filters"),
            filterStatus: document.getElementById("qual-filter-status"),
            rankCount: document.getElementById("qual-rank-count"),
            rankBody: document.querySelector("#qual-rank-table tbody"),
            teamTitle: document.getElementById("qual-team-title"),
            teamStatus: document.getElementById("qual-team-status"),
            teamDetail: document.getElementById("qual-team-detail"),
            entryCount: document.getElementById("qual-entry-count"),
            entryBody: document.querySelector("#qual-entry-table tbody"),
            entryTitle: document.getElementById("qual-entry-title"),
            entryStatus: document.getElementById("qual-entry-status"),
            entryDetail: document.getElementById("qual-entry-detail")
        };
    }

    async function loadAllQualData() {
        Obsidianscout.showLoadingSpinner(dynamicContainer, "Loading qualitative data...");
        try {
            const settingsResponse = await Obsidianscout.request("/api/settings");
            state.settings = settingsResponse.settings;

            const [config, entries, events] = await Promise.all([
                Obsidianscout.request("/api/qual-config"),
                Obsidianscout.request("/api/qual-scouting?includePrescout=true"),
                Obsidianscout.request(`/api/events?year=${state.settings.year}&cached=1`)
            ]);

            dynamicContainer.innerHTML = originalHTML;
            ui = getUIElements();

            state.config = config;
            state.fields = normalizeFields(config.fields || []);
            state.groups = groupFields(config.fields || []);
            state.rankingMetrics = buildRankingMetrics(state.fields);
            state.entriesRaw = (Array.isArray(entries) ? entries : []).map(e => {
                if (e.isPrescout) {
                    return { ...e, eventKey: "prescout", rawEventKey: e.eventKey };
                }
                return { ...e, rawEventKey: e.eventKey };
            });
            state.events = buildEventList(events, state.entriesRaw, state.settings);

            initControls(state, ui);
            await refreshData(state, ui, { keepSelection: false });

            window.addEventListener("obsidianscout:qualitative-entries-changed", async () => {
                await refreshData(state, ui, { keepSelection: true });
            });
        } catch (error) {
            console.error("Failed to load qual data:", error);
            Obsidianscout.showRetryButton(dynamicContainer, "Failed to load qualitative performance data: " + error.message, loadAllQualData);
        }
    }

    await loadAllQualData();
});

async function refreshData(state, ui, options) {
    const keepSelection = Boolean(options && options.keepSelection);
    const currentTeam = keepSelection ? state.selectedTeam : null;
    const currentEntry = keepSelection ? state.selectedEntryId : null;

    const latestServerEntries = await Obsidianscout.request("/api/qual-scouting?includePrescout=true");
    state.entriesRaw = (Array.isArray(latestServerEntries) ? latestServerEntries : []).map(e => {
        if (e.isPrescout) {
            return { ...e, eventKey: "prescout", rawEventKey: e.eventKey };
        }
        return { ...e, rawEventKey: e.eventKey };
    });

    const pendingEntries = state.includePending ? loadPendingEntries() : [];
    state.entries = mergeEntries(state.entriesRaw, pendingEntries);

    await loadTeamMapForEvent(state);
    rebuildRankings(state);

    if (currentTeam && state.rankRows.some((row) => row.teamNumber === currentTeam)) {
        state.selectedTeam = currentTeam;
    } else {
        state.selectedTeam = state.rankRows.length ? state.rankRows[0].teamNumber : null;
    }

    const teamEntries = entriesForTeam(state, state.selectedTeam);
    if (currentEntry && teamEntries.some((entry) => entry.id === currentEntry)) {
        state.selectedEntryId = currentEntry;
    } else {
        state.selectedEntryId = teamEntries.length ? teamEntries[0].id : null;
    }

    renderAll(state, ui);
}

function initControls(state, ui) {
    populateEventFilter(state, ui.eventFilter);
    populateMetricFilter(state, ui.metricField);

    ui.eventFilter.value = state.selectedEvent;
    ui.metricField.value = state.selectedMetric;
    ui.aggregateMode.value = state.aggregateMode;
    ui.minEntries.value = String(state.minEntries);
    ui.includePending.checked = state.includePending;
    ui.entrySort.value = state.entrySort;

    ui.eventFilter.addEventListener("change", async () => {
        state.selectedEvent = ui.eventFilter.value || "all";
        await refreshData(state, ui, { keepSelection: false });
    });

    ui.teamSearch.addEventListener("input", () => {
        state.teamQuery = ui.teamSearch.value.trim();
        rebuildRankings(state);
        state.selectedTeam = state.rankRows.length ? state.rankRows[0].teamNumber : null;
        const teamEntries = entriesForTeam(state, state.selectedTeam);
        state.selectedEntryId = teamEntries.length ? teamEntries[0].id : null;
        renderAll(state, ui);
    });

    ui.metricField.addEventListener("change", () => {
        state.selectedMetric = ui.metricField.value;
        rebuildRankings(state);
        state.selectedTeam = state.rankRows.length ? state.rankRows[0].teamNumber : null;
        const teamEntries = entriesForTeam(state, state.selectedTeam);
        state.selectedEntryId = teamEntries.length ? teamEntries[0].id : null;
        renderAll(state, ui);
    });

    ui.aggregateMode.addEventListener("change", () => {
        state.aggregateMode = ui.aggregateMode.value;
        rebuildRankings(state);
        state.selectedTeam = state.rankRows.length ? state.rankRows[0].teamNumber : null;
        const teamEntries = entriesForTeam(state, state.selectedTeam);
        state.selectedEntryId = teamEntries.length ? teamEntries[0].id : null;
        renderAll(state, ui);
    });

    ui.minEntries.addEventListener("input", () => {
        const value = Number(ui.minEntries.value);
        state.minEntries = Number.isFinite(value) && value > 0 ? Math.floor(value) : 1;
        ui.minEntries.value = String(state.minEntries);
        rebuildRankings(state);
        state.selectedTeam = state.rankRows.length ? state.rankRows[0].teamNumber : null;
        const teamEntries = entriesForTeam(state, state.selectedTeam);
        state.selectedEntryId = teamEntries.length ? teamEntries[0].id : null;
        renderAll(state, ui);
    });

    ui.includePending.addEventListener("change", async () => {
        state.includePending = ui.includePending.checked;
        await refreshData(state, ui, { keepSelection: true });
    });

    ui.entrySort.addEventListener("change", () => {
        state.entrySort = ui.entrySort.value;
        renderEntrySection(state, ui);
    });

    ui.resetFilters.addEventListener("click", async () => {
        state.selectedEvent = "all";
        state.teamQuery = "";
        state.selectedMetric = "__composite__";
        state.aggregateMode = "avg";
        state.minEntries = 1;
        state.includePending = true;
        state.entrySort = "createdAt-desc";

        ui.eventFilter.value = state.selectedEvent;
        ui.teamSearch.value = "";
        ui.metricField.value = state.selectedMetric;
        ui.aggregateMode.value = state.aggregateMode;
        ui.minEntries.value = String(state.minEntries);
        ui.includePending.checked = state.includePending;
        ui.entrySort.value = state.entrySort;

        await refreshData(state, ui, { keepSelection: false });
    });
}

function renderAll(state, ui) {
    renderOverview(state, ui);
    renderRankingTable(state, ui);
    renderTeamDetail(state, ui);
    renderEntrySection(state, ui);
}

function renderOverview(state, ui) {
    const latest = state.entries
        .slice()
        .sort((a, b) => parseTime(b.createdAt) - parseTime(a.createdAt))[0] || null;
    const metric = metricById(state, state.selectedMetric);

    ui.totalEntries.textContent = String(state.entries.length);
    ui.totalTeams.textContent = String(state.rankRows.length);
    ui.metricSummary.textContent = metric.label;
    ui.lastUpdated.textContent = latest ? formatTimestamp(latest.createdAt) : "--";
    ui.rankCount.textContent = `${state.rankRows.length} ${state.rankRows.length === 1 ? "team" : "teams"}`;
    ui.filterStatus.textContent = summarizeFilterStatus(state);
}

function renderRankingTable(state, ui) {
    ui.rankBody.innerHTML = "";

    if (!state.rankRows.length) {
        const row = document.createElement("tr");
        const cell = document.createElement("td");
        cell.colSpan = 7;
        cell.textContent = t('qual_data.no_teams_match', "No teams match the current ranking filters.");
        row.appendChild(cell);
        ui.rankBody.appendChild(row);
        return;
    }

    state.rankRows.forEach((rowData, index) => {
        const row = document.createElement("tr");
        row.className = "clickable-row";
        if (state.selectedTeam === rowData.teamNumber) {
            row.classList.add("selected");
        }

        appendCell(row, String(index + 1));
        
        const tdTeam = document.createElement("td");
        tdTeam.textContent = teamLabel(state, rowData.teamNumber);
        const teamEntries = entriesForTeam(state, rowData.teamNumber);
        if (teamEntries.some(e => e.hasDiscrepancy)) {
            const warnSpan = document.createElement("span");
            warnSpan.textContent = " ⚠️";
            warnSpan.style.color = "#eab308";
            warnSpan.style.fontWeight = "bold";
            warnSpan.title = "Discrepancy detected in qualitative scouting data for this team";
            tdTeam.appendChild(warnSpan);
        }
        row.appendChild(tdTeam);

        appendCell(row, formatNumeric(rowData.score));
        appendCell(row, String(rowData.entriesCount));
        appendCell(row, String(rowData.matchesCount));
        appendCell(row, formatTrend(rowData.trend));
        appendCell(row, formatTimestamp(rowData.lastUpdated));

        row.addEventListener("click", () => {
            state.selectedTeam = rowData.teamNumber;
            const entries = entriesForTeam(state, rowData.teamNumber);
            state.selectedEntryId = entries.length ? entries[0].id : null;
            renderRankingTable(state, ui);
            renderTeamDetail(state, ui);
            renderEntrySection(state, ui);
        });

        ui.rankBody.appendChild(row);
    });
}

function renderTeamDetail(state, ui) {
    ui.teamDetail.innerHTML = "";
    const teamNumber = state.selectedTeam;
    const rowData = state.rankRows.find((row) => row.teamNumber === teamNumber);

    if (!rowData) {
        ui.teamTitle.textContent = t('qual_data.select_team', "Select a team");
        ui.teamStatus.textContent = t('qual_data.no_team_selected', "No team selected");
        const notice = document.createElement("p");
        notice.className = "notice";
        notice.textContent = "Choose a ranked team to inspect match-by-match qualitative performance.";
        ui.teamDetail.appendChild(notice);
        return;
    }

    ui.teamTitle.textContent = teamLabel(state, teamNumber);
    ui.teamStatus.textContent = `Score ${formatNumeric(rowData.score)} | ${rowData.entriesCount} entries`;

    const summary = document.createElement("div");
    summary.className = "pit-detail-group";
    const summaryTitle = document.createElement("h3");
    summaryTitle.textContent = t('qual_data.performance_summary', "Performance summary");
    summary.appendChild(summaryTitle);

    const consistency = rowData.consistency !== null ? `${formatNumeric(rowData.consistency)} stdev` : "--";
    const metric = metricById(state, state.selectedMetric);
    const summaryRows = [
        ["Ranking metric", metric.label],
        ["Aggregate mode", aggregateLabel(state.aggregateMode)],
        ["Score", formatNumeric(rowData.score)],
        ["Entries", String(rowData.entriesCount)],
        ["Matches", String(rowData.matchesCount)],
        ["Trend (last 3 vs prior 3)", formatTrend(rowData.trend)],
        ["Consistency", consistency],
        ["Last updated", formatTimestamp(rowData.lastUpdated)]
    ];
    summaryRows.forEach(([label, value]) => summary.appendChild(buildDetailItem(label, value)));
    ui.teamDetail.appendChild(summary);

    const byMatch = buildByMatchRows(state, teamNumber);
    const matchGroup = document.createElement("div");
    matchGroup.className = "pit-detail-group";
    const matchTitle = document.createElement("h3");
    matchTitle.textContent = t('qual_data.match_by_match', "Match-by-match");
    matchGroup.appendChild(matchTitle);

    if (!byMatch.length) {
        const empty = document.createElement("p");
        empty.className = "notice";
        empty.textContent = t('qual_data.no_match_records', "No match records available for this team under current filters.");
        matchGroup.appendChild(empty);
    } else {
        byMatch.forEach((item) => {
            const line = `${item.matchLabel} | ${formatTimestamp(item.createdAt)} | ${metric.label}: ${formatNumeric(item.metricValue)}`;
            matchGroup.appendChild(buildDetailItem("Entry", line));
        });
    }

    ui.teamDetail.appendChild(matchGroup);

    const notes = collectRecentNotes(state, teamNumber, 8);
    const notesGroup = document.createElement("div");
    notesGroup.className = "pit-detail-group";
    const notesTitle = document.createElement("h3");
    notesTitle.textContent = t('qual_data.recent_notes', "Recent notes");
    notesGroup.appendChild(notesTitle);

    if (!notes.length) {
        notesGroup.appendChild(buildDetailItem("Notes", "No qualitative text notes found."));
    } else {
        notes.forEach((item) => {
            notesGroup.appendChild(buildDetailItem(item.label, item.value));
        });
    }

    ui.teamDetail.appendChild(notesGroup);
}

function renderEntrySection(state, ui) {
    const entries = sortEntries(entriesForTeam(state, state.selectedTeam), state);
    ui.entryBody.innerHTML = "";
    ui.entryCount.textContent = `${entries.length} ${entries.length === 1 ? "entry" : "entries"}`;

    if (!entries.length) {
        ui.entryTitle.textContent = t('qual_data.select_entry', "Select an entry");
        ui.entryStatus.textContent = t('qual_data.no_entry_selected', "No entry selected");
        ui.entryDetail.innerHTML = "";
        const notice = document.createElement("p");
        notice.className = "notice";
        notice.textContent = "Select a team with entries to inspect full qualitative payload values.";
        ui.entryDetail.appendChild(notice);

        const row = document.createElement("tr");
        const cell = document.createElement("td");
        cell.colSpan = 5;
        cell.textContent = t('qual_data.no_entries_selected_team', "No entries for the selected team.");
        row.appendChild(cell);
        ui.entryBody.appendChild(row);
        return;
    }

    if (!state.selectedEntryId || !entries.some((entry) => entry.id === state.selectedEntryId)) {
        state.selectedEntryId = entries[0].id;
    }

    const metric = metricById(state, state.selectedMetric);

    entries.forEach((entry) => {
        const row = document.createElement("tr");
        row.className = "clickable-row";
        if (entry.id === state.selectedEntryId) {
            row.classList.add("selected");
        }

        const tdTeam = document.createElement("td");
        tdTeam.textContent = teamLabel(state, entry.targetTeamNumber);
        if (entry.hasDiscrepancy) {
            const warnSpan = document.createElement("span");
            warnSpan.textContent = " ⚠️";
            warnSpan.style.color = "#eab308";
            warnSpan.style.fontWeight = "bold";
            warnSpan.title = "Discrepancy detected between partner teams: " + (entry.conflictingTeams || []).join(", ");
            tdTeam.appendChild(warnSpan);
        }
        row.appendChild(tdTeam);

        appendCell(row, matchLabel(entry));
        appendCell(row, formatMetricValue(metric, entry));
        appendCell(row, formatTimestamp(entry.createdAt));
        appendCell(row, entry.pending ? "Pending" : "Synced");

        row.addEventListener("click", () => {
            state.selectedEntryId = entry.id;
            renderEntrySection(state, ui);
        });

        ui.entryBody.appendChild(row);
    });

    renderEntryDetail(state, ui, entries.find((entry) => entry.id === state.selectedEntryId));
}

function renderEntryDetail(state, ui, entry) {
    ui.entryDetail.innerHTML = "";

    if (!entry) {
        ui.entryTitle.textContent = t('qual_data.select_entry', "Select an entry");
        ui.entryStatus.textContent = t('qual_data.no_entry_selected', "No entry selected");
        const notice = document.createElement("p");
        notice.className = "notice";
        notice.textContent = "Select an entry row to inspect complete qualitative values and notes.";
        ui.entryDetail.appendChild(notice);
        return;
    }

    ui.entryTitle.textContent = `${teamLabel(state, entry.targetTeamNumber)} | ${matchLabel(entry)}`;
    ui.entryStatus.textContent = `${entry.pending ? "Pending" : "Synced"} | ${formatTimestamp(entry.createdAt)}`;

    if (entry.hasDiscrepancy) {
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
                <strong>Discrepancy Warning:</strong> Different qualitative scouting data exists for this match from: <strong>${(entry.conflictingTeams || []).join(", ")}</strong>. You can view or resolve this discrepancy in the Alliance Scouting Data page.
            </div>
        `;
        ui.entryDetail.appendChild(warnBanner);
    }

    const metaGroup = document.createElement("div");
    metaGroup.className = "pit-detail-group";
    const metaTitle = document.createElement("h3");
    metaTitle.textContent = t('qual_data.entry_metadata', "Entry metadata");
    metaGroup.appendChild(metaTitle);

    const metaRows = [
        ["Event", entry.eventKey || "--"],
        ["Team", String(entry.targetTeamNumber ?? "--")],
        ["Match", entry.matchKey || "--"],
        ["Match number", String(entry.matchNumber ?? "--")],
        ["Owner team", String(entry.ownerTeamNumber ?? "--")],
        ["Updated", formatTimestamp(entry.createdAt)],
        ["State", entry.pending ? "Pending" : "Synced"]
    ];

    metaRows.forEach(([label, value]) => metaGroup.appendChild(buildDetailItem(label, value)));
    ui.entryDetail.appendChild(metaGroup);

    state.groups.forEach((group) => {
        const groupNode = document.createElement("div");
        groupNode.className = "pit-detail-group";

        if (group.title) {
            const title = document.createElement("h3");
            title.textContent = group.title;
            groupNode.appendChild(title);
        }

        group.fields.forEach((field) => {
            const value = entry.data ? entry.data[field.id] : undefined;
            groupNode.appendChild(buildDetailItem(localizeLabel(field.label), formatFieldValue(field, value)));
        });

        if (group.fields.length) {
            ui.entryDetail.appendChild(groupNode);
        }
    });
}

function rebuildRankings(state) {
    const metric = metricById(state, state.selectedMetric);
    const grouped = new Map();

    filteredEntries(state).forEach((entry) => {
        if (entry.targetTeamNumber === null || entry.targetTeamNumber === undefined) {
            return;
        }
        const teamNumber = Number(entry.targetTeamNumber);
        if (!grouped.has(teamNumber)) {
            grouped.set(teamNumber, []);
        }
        grouped.get(teamNumber).push(entry);
    });

    const query = state.teamQuery.trim().toLowerCase();

    state.rankRows = Array.from(grouped.entries())
        .map(([teamNumber, entries]) => buildTeamRow(teamNumber, entries, metric, state.aggregateMode))
        .filter((row) => row.entriesCount >= state.minEntries)
        .filter((row) => {
            if (!query) {
                return true;
            }
            const label = teamLabel(state, row.teamNumber).toLowerCase();
            return label.includes(query);
        })
        .sort((left, right) => {
            if (right.score !== left.score) {
                return right.score - left.score;
            }
            if (right.entriesCount !== left.entriesCount) {
                return right.entriesCount - left.entriesCount;
            }
            return left.teamNumber - right.teamNumber;
        });
}

function buildTeamRow(teamNumber, entries, metric, aggregateMode) {
    const ordered = entries
        .slice()
        .sort((a, b) => parseTime(a.createdAt) - parseTime(b.createdAt));
    const values = ordered
        .map((entry) => metricValue(metric, entry))
        .filter((value) => value !== null && value !== undefined && Number.isFinite(value));

    const score = aggregateMetric(values, aggregateMode);
    const matchesCount = new Set(
        ordered.map((entry) => entry.matchKey || String(entry.matchNumber || ""))
            .filter(Boolean)
    ).size;
    const recentValues = values.slice(-3);
    const priorValues = values.slice(-6, -3);
    const recentAvg = recentValues.length ? average(recentValues) : null;
    const priorAvg = priorValues.length ? average(priorValues) : null;
    const trend = recentAvg !== null && priorAvg !== null ? recentAvg - priorAvg : null;

    return {
        teamNumber,
        score,
        entriesCount: ordered.length,
        matchesCount,
        trend,
        lastUpdated: ordered.length ? ordered[ordered.length - 1].createdAt : null,
        consistency: values.length > 1 ? standardDeviation(values) : null
    };
}

function aggregateMetric(values, mode) {
    if (!values.length) {
        return Number.NEGATIVE_INFINITY;
    }
    switch (mode) {
        case "median": {
            const sorted = values.slice().sort((a, b) => a - b);
            const mid = Math.floor(sorted.length / 2);
            if (sorted.length % 2 === 0) {
                return (sorted[mid - 1] + sorted[mid]) / 2;
            }
            return sorted[mid];
        }
        case "latest":
            return values[values.length - 1];
        case "max":
            return Math.max(...values);
        case "min":
            return Math.min(...values);
        case "avg":
        default:
            return average(values);
    }
}

function average(values) {
    if (!values.length) {
        return Number.NaN;
    }
    return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function standardDeviation(values) {
    const mean = average(values);
    const variance = values.reduce((sum, value) => sum + ((value - mean) ** 2), 0) / values.length;
    return Math.sqrt(variance);
}

function entriesForTeam(state, teamNumber) {
    if (teamNumber === null || teamNumber === undefined) {
        return [];
    }
    return filteredEntries(state)
        .filter((entry) => Number(entry.targetTeamNumber) === Number(teamNumber));
}

function filteredEntries(state) {
    return state.entries.filter((entry) => {
        if (state.selectedEvent !== "all" && entry.eventKey !== state.selectedEvent) {
            return false;
        }
        if (!state.includePending && entry.pending) {
            return false;
        }
        return true;
    });
}

function sortEntries(entries, state) {
    const metric = metricById(state, state.selectedMetric);
    const sortValue = state.entrySort;

    return entries.slice().sort((left, right) => {
        if (sortValue === "createdAt-desc") {
            return parseTime(right.createdAt) - parseTime(left.createdAt);
        }
        if (sortValue === "createdAt-asc") {
            return parseTime(left.createdAt) - parseTime(right.createdAt);
        }
        if (sortValue === "metric-desc") {
            return compareNumbers(metricValue(metric, right), metricValue(metric, left));
        }
        if (sortValue === "metric-asc") {
            return compareNumbers(metricValue(metric, left), metricValue(metric, right));
        }
        if (sortValue === "team-asc") {
            return compareNumbers(left.targetTeamNumber, right.targetTeamNumber);
        }
        if (sortValue === "match-asc") {
            if (left.matchPlayedTime !== null && right.matchPlayedTime !== null && left.matchPlayedTime !== undefined && right.matchPlayedTime !== undefined) {
                if (left.matchPlayedTime !== right.matchPlayedTime) {
                    return left.matchPlayedTime - right.matchPlayedTime;
                }
            } else {
                const aEvent = left.rawEventKey || left.eventKey || "";
                const bEvent = right.rawEventKey || right.eventKey || "";
                if (aEvent !== bEvent) {
                    return aEvent.localeCompare(bEvent);
                }
            }
            return compareNumbers(left.matchNumber, right.matchNumber);
        }
        return parseTime(right.createdAt) - parseTime(left.createdAt);
    });
}

function compareNumbers(left, right) {
    const leftVal = Number(left);
    const rightVal = Number(right);
    const leftMissing = !Number.isFinite(leftVal);
    const rightMissing = !Number.isFinite(rightVal);
    if (leftMissing && rightMissing) return 0;
    if (leftMissing) return 1;
    if (rightMissing) return -1;
    return leftVal - rightVal;
}

function buildByMatchRows(state, teamNumber) {
    const metric = metricById(state, state.selectedMetric);
    return entriesForTeam(state, teamNumber)
        .slice()
        .sort((a, b) => {
            if (a.matchPlayedTime !== null && b.matchPlayedTime !== null && a.matchPlayedTime !== undefined && b.matchPlayedTime !== undefined) {
                return b.matchPlayedTime - a.matchPlayedTime;
            }
            return parseTime(b.createdAt) - parseTime(a.createdAt);
        })
        .map((entry) => ({
            matchLabel: matchLabel(entry),
            metricValue: metricValue(metric, entry),
            createdAt: entry.createdAt
        }));
}

function collectRecentNotes(state, teamNumber, limit) {
    const textFields = state.fields.filter((field) => field.type === "textarea" || field.type === "text");
    const entries = entriesForTeam(state, teamNumber)
        .slice()
        .sort((a, b) => parseTime(b.createdAt) - parseTime(a.createdAt));

    const notes = [];
    entries.forEach((entry) => {
        textFields.forEach((field) => {
            const value = entry.data ? entry.data[field.id] : "";
            if (typeof value !== "string") {
                return;
            }
            const trimmed = value.trim();
            if (!trimmed) {
                return;
            }
            notes.push({
                label: `${localizeLabel(field.label)} | ${matchLabel(entry)}`,
                value: trimmed
            });
        });
    });

    return notes.slice(0, limit);
}

function metricById(state, id) {
    return state.rankingMetrics.find((metric) => metric.id === id) || state.rankingMetrics[0];
}

function metricValue(metric, entry) {
    if (!metric || !entry) {
        return null;
    }

    if (metric.id === "__composite__") {
        const values = metric.fields
            .map((field) => fieldNumericValue(field, entry.data ? entry.data[field.id] : undefined))
            .filter((value) => value !== null && Number.isFinite(value));
        return values.length ? average(values) : null;
    }

    if (metric.source === "meta") {
        if (metric.id === "matchNumber") {
            return entry.matchNumber;
        }
        return null;
    }

    const value = entry.data ? entry.data[metric.id] : undefined;
    return fieldNumericValue(metric.field, value);
}

function fieldNumericValue(field, rawValue) {
    if (rawValue === null || rawValue === undefined || rawValue === "") {
        return null;
    }
    if (!field) {
        const asNumber = Number(rawValue);
        return Number.isFinite(asNumber) ? asNumber : null;
    }
    if (field.type === "checkbox") {
        return rawValue ? 1 : 0;
    }
    if (field.type === "select") {
        const options = Array.isArray(field.options) ? field.options : [];
        const index = options.findIndex((option) => {
            if (typeof option === "string") {
                return option === rawValue;
            }
            return option.value === rawValue || option.label === rawValue;
        });
        if (index >= 0) {
            return index + 1;
        }
        const asNumber = Number(rawValue);
        return Number.isFinite(asNumber) ? asNumber : null;
    }
    if (field.type === "number" || field.type === "counter" || field.type === "rating") {
        const asNumber = Number(rawValue);
        return Number.isFinite(asNumber) ? asNumber : null;
    }
    const asNumber = Number(rawValue);
    return Number.isFinite(asNumber) ? asNumber : null;
}

function buildRankingMetrics(fields) {
    const numericFields = fields.filter((field) => ["rating", "number", "counter", "checkbox", "select"].includes(field.type));
    const metrics = [
        {
            id: "__composite__",
            label: "Composite average (all numeric qualitative fields)",
            source: "computed",
            fields: numericFields
        }
    ];

    numericFields.forEach((field) => {
        metrics.push({
            id: field.id,
            label: localizeLabel(field.label),
            source: "field",
            field
        });
    });

    metrics.push({
        id: "matchNumber",
        label: "Match number",
        source: "meta",
        field: { id: "matchNumber", type: "number", label: "Match number" }
    });

    return metrics;
}

function normalizeFields(fields) {
    return fields.filter((field) => field.type !== "section" && !["eventKey", "matchKey", "matchNumber", "targetTeamNumber"].includes(field.id));
}

function groupFields(fields) {
    const groups = [];
    let current = { title: "", fields: [] };

    fields.forEach((field) => {
        if (field.type === "section") {
            if (current.title || current.fields.length) {
                groups.push(current);
            }
            current = { title: localizeLabel(field.label), fields: [] };
            return;
        }
        if (["eventKey", "matchKey", "matchNumber", "targetTeamNumber"].includes(field.id)) {
            return;
        }
        current.fields.push(field);
    });

    if (current.title || current.fields.length) {
        groups.push(current);
    }

    return groups;
}

function loadPendingEntries() {
    try {
        const raw = JSON.parse(Obsidianscout.safeGetItem("pending_qualitative_entries") || "[]");
        return Array.isArray(raw) ? raw : [];
    } catch (error) {
        return [];
    }
}

function mergeEntries(serverEntries, pendingEntries) {
    const normalized = serverEntries.map((entry) => ({
        ...entry,
        pending: false
    }));

    pendingEntries.forEach((entry, index) => {
        const data = entry && entry.data ? entry.data : {};
        const isPrescout = entry.isPrescout || data.isPrescout || false;
        normalized.push({
            id: entry && entry.id ? entry.id : `pending-qual-${index}-${entry.createdAt || Date.now()}`,
            ownerTeamNumber: entry && entry.ownerTeamNumber !== undefined ? entry.ownerTeamNumber : null,
            targetTeamNumber: data.targetTeamNumber ?? null,
            eventKey: isPrescout ? "prescout" : (data.eventKey ?? null),
            rawEventKey: data.eventKey ?? null,
            isPrescout: isPrescout,
            matchKey: data.matchKey ?? null,
            matchNumber: data.matchNumber ?? null,
            data,
            createdAt: entry && entry.createdAt ? entry.createdAt : new Date().toISOString(),
            pending: true
        });
    });

    return normalized;
}

function populateEventFilter(state, select) {
    select.innerHTML = "";

    const allOption = document.createElement("option");
    allOption.value = "all";
    allOption.textContent = t('qual_data.all_events', "All events");
    select.appendChild(allOption);

    state.events.forEach((event) => {
        const option = document.createElement("option");
        option.value = event.eventKey;
        option.textContent = event.label;
        select.appendChild(option);
    });
}

function populateMetricFilter(state, select) {
    select.innerHTML = "";
    state.rankingMetrics.forEach((metric) => {
        const option = document.createElement("option");
        option.value = metric.id;
        option.textContent = metric.label;
        select.appendChild(option);
    });
}

function buildEventList(events, entries, settings) {
    const map = new Map();
    
    map.set("prescout", { eventKey: "prescout", label: "Prescouting Data" });

    const addEvent = (eventKey, label) => {
        if (!eventKey || map.has(eventKey)) {
            return;
        }
        map.set(eventKey, { eventKey, label });
    };

    if (Array.isArray(events)) {
        events.forEach((event) => {
            if (!event || !event.eventKey) return;
            const label = event.name ? `${event.name} (${event.eventKey})` : event.eventKey;
            addEvent(event.eventKey, label);
        });
    }

    entries.forEach((entry) => {
        if (entry && entry.eventKey) {
            addEvent(entry.eventKey, `Event ${entry.eventKey}`);
        }
    });

    const currentEvent = Obsidianscout.resolveEventKey(settings);
    if (currentEvent) {
        addEvent(currentEvent, `Current event (${currentEvent})`);
    }

    return Array.from(map.values()).sort((a, b) => a.label.localeCompare(b.label));
}

async function loadTeamMapForEvent(state) {
    state.teamsByNumber.clear();
    if (state.selectedEvent === "all") {
        return;
    }
    if (state.selectedEvent === "prescout") {
        const uniqueTeams = Array.from(new Set(state.entries.filter(e => e.isPrescout).map(e => e.targetTeamNumber).filter(Boolean)));
        uniqueTeams.forEach(teamNumber => {
            state.teamsByNumber.set(teamNumber, { teamNumber, nickname: `Team ${teamNumber}` });
        });
        return;
    }
    try {
        const teams = await Obsidianscout.request(`/api/teams?eventKey=${state.selectedEvent}`);
        if (Array.isArray(teams)) {
            teams.forEach((team) => {
                state.teamsByNumber.set(team.teamNumber, team);
            });
        }
    } catch (error) {
        // ignore
    }
}

function summarizeFilterStatus(state) {
    const parts = [];
    parts.push(state.selectedEvent === "all" ? t('qual_data.all_events', "All events") : state.selectedEvent);
    parts.push(`Metric: ${metricById(state, state.selectedMetric).label}`);
    parts.push(`Aggregate: ${aggregateLabel(state.aggregateMode)}`);
    parts.push(`Min entries: ${state.minEntries}`);
    if (state.teamQuery) {
        parts.push(`Search: ${state.teamQuery}`);
    }
    if (!state.includePending) {
        parts.push("Pending excluded");
    }
    return parts.join(" | ");
}

function aggregateLabel(mode) {
    switch (mode) {
        case "median": return "Median";
        case "latest": return "Latest";
        case "max": return "Max";
        case "min": return "Min";
        case "avg":
        default:
            return "Average";
    }
}

function formatMetricValue(metric, entry) {
    const value = metricValue(metric, entry);
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return "--";
    }
    return formatNumeric(value);
}

function formatFieldValue(field, value) {
    if (value === null || value === undefined || value === "") {
        return "--";
    }
    if (field.type === "checkbox") {
        return value ? "Yes" : "No";
    }
    if (field.type === "select") {
        const options = Array.isArray(field.options) ? field.options : [];
        const option = options.find((item) => {
            if (typeof item === "string") {
                return item === value;
            }
            return item.value === value || item.label === value;
        });
        if (option) {
            return typeof option === "string" ? option : localizeLabel(option.label);
        }
    }
    return String(value);
}

function parseTime(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}

function formatTimestamp(value) {
    if (!value) return "--";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "--";
    return date.toLocaleString();
}

function formatNumeric(value) {
    if (!Number.isFinite(value)) {
        return "--";
    }
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

function formatTrend(value) {
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return "--";
    }
    const prefix = value > 0 ? "+" : "";
    return `${prefix}${formatNumeric(value)}`;
}

function appendCell(row, text) {
    const cell = document.createElement("td");
    cell.textContent = text;
    row.appendChild(cell);
}

function buildDetailItem(label, value) {
    const item = document.createElement("div");
    item.className = "pit-detail-item";
    const labelNode = document.createElement("span");
    labelNode.textContent = label;
    const valueNode = document.createElement("strong");
    valueNode.textContent = value;
    item.appendChild(labelNode);
    item.appendChild(valueNode);
    return item;
}

function teamLabel(state, teamNumber) {
    if (teamNumber === null || teamNumber === undefined) {
        return "--";
    }
    const numeric = Number(teamNumber);
    const team = state.teamsByNumber.get(numeric);
    const displayNum = team ? Obsidianscout.formatTeam(team.teamKey, numeric) : String(numeric);
    if (!team) {
        return displayNum;
    }
    const name = team.nickname || team.name || "";
    return name ? `${displayNum} ${name}` : displayNum;
}

function matchLabel(entry) {
    if (entry.matchKey) {
        if (entry.matchNumber !== null && entry.matchNumber !== undefined) {
            return `${entry.matchKey} (#${entry.matchNumber})`;
        }
        return entry.matchKey;
    }
    if (entry.matchNumber !== null && entry.matchNumber !== undefined) {
        return `Match ${entry.matchNumber}`;
    }
    return "--";
}

function localizeLabel(value) {
    return (window.Obsidianscout && typeof Obsidianscout.localize === "function")
        ? Obsidianscout.localize(value)
        : value;
}
