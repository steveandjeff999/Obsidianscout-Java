
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

let originalMainContentHTML = "";
let mainContentWrapper = null;
let mainContent = null;

const SVG_ICONS = {
    expand: `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"></path></svg>`,
    close: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`,
    warning: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>`
};

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

    mainContent = document.querySelector(".main-content");
    if (mainContent) {
        const siblings = Array.from(mainContent.children).filter(child => !child.classList.contains("graphs-hero") && !child.classList.contains("banner-container"));
        mainContentWrapper = document.createElement("div");
        mainContentWrapper.id = "graphs-wrapper";
        siblings.forEach(child => mainContentWrapper.appendChild(child));
        mainContent.appendChild(mainContentWrapper);
        originalMainContentHTML = mainContentWrapper.innerHTML;
        await loadGraphsPageData();
    }
});

async function loadGraphsPageData() {
    if (!mainContentWrapper) return;
    Obsidianscout.showLoadingSpinner(mainContentWrapper, "Loading graphs data...");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        const [config, entries, events] = await Promise.all([
            Obsidianscout.request("/api/config"),
            Obsidianscout.request("/api/scouting?includePrescout=true"),
            Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`)
        ]);

        mainContentWrapper.innerHTML = originalMainContentHTML;
        await initGraphsPage({ config, entries, events, settings });
    } catch (error) {
        console.error("Failed to load graphs data:", error);
        Obsidianscout.showRetryButton(mainContentWrapper, "Failed to load graphs data: " + error.message, loadGraphsPageData);
    }
}

const RESERVED_FIELDS = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);
const PLOTLY_CONFIG = {
    responsive: true,
    displayModeBar: false,
    displaylogo: false
};

const GRAPH_TYPES = [
    { id: "bar", label: "Bar", group: "basic" },
    { id: "line", label: "Line", group: "basic" },
    { id: "scatter", label: "Scatter", group: "basic" },
    { id: "area", label: "Area", group: "basic" },
    { id: "box", label: "Box", group: "distribution" },
    { id: "violin", label: "Violin", group: "distribution" },
    { id: "histogram", label: "Histogram", group: "distribution" }
];

async function initGraphsPage({ config, entries, events, settings }) {
    const state = {
        config,
        entries,
        events,
        settings,
        eventKey: "",
        selectedTeams: new Set(),
        metricId: "score_total",
        dataView: "averages",
        sort: "value_desc",
        selectedGraphTypes: new Set(["bar"]),
        includePrescout: false,
        eventTeams: new Set(),
        eventTeamsMap: new Map(),
        datasource: "scouted"
    };

    renderSummary(entries);
    initMetricOptions(state);
    initEventFilter(state);
    initDatasource(state);

    if (state.eventKey) {
        await loadTeamsForEvent(state);
    }

    initTeamSelection(state);
    initGraphTypeControls(state);
    wireGraphOptions(state);
    updateSelectionSummary(state);
}

function initMetricOptions(state) {
    const metricSelect = document.getElementById("graph-metric");
    if (!metricSelect) {
        return;
    }
    const metrics = buildMetricOptions(state.config || {});
    state.metrics = metrics;
    state.metricMap = new Map(metrics.map((metric) => [metric.id, metric]));
    metricSelect.innerHTML = "";
    metrics.forEach((metric) => {
        const option = document.createElement("option");
        option.value = metric.id;
        option.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(metric.label) : metric.label;
        metricSelect.appendChild(option);
    });
    state.metricId = metrics.length ? metrics[0].id : "score_total";
    metricSelect.addEventListener("change", () => {
        state.metricId = metricSelect.value;
    });
}

function initEventFilter(state) {
    const eventFilter = document.getElementById("event-filter");
    const status = document.getElementById("event-filter-status");
    if (!eventFilter) {
        return;
    }
    eventFilter.innerHTML = "";
    const allOption = document.createElement("option");
    allOption.value = "";
    allOption.textContent = t('graphs.all_events', "All events");
    eventFilter.appendChild(allOption);

    const defaultEventKey = state.settings ? (Obsidianscout.resolveEventKey(state.settings) || "") : "";

    (state.events || []).forEach((event) => {
        const option = document.createElement("option");
        option.value = event.eventKey;
        option.textContent = `${event.name} (${event.year})`;
        if (event.eventKey === defaultEventKey) {
            option.selected = true;
        }
        eventFilter.appendChild(option);
    });

    if (defaultEventKey) {
        state.eventKey = defaultEventKey;
    }

    eventFilter.addEventListener("change", async () => {
        state.eventKey = eventFilter.value;
        await loadTeamsForEvent(state);
        updateTeamList(state);
        updateSelectionSummary(state);
        if (status) {
            status.textContent = state.eventKey ? `Filtered to ${eventFilter.selectedOptions[0].text}` : "Showing all events";
        }
    });

    if (status && defaultEventKey) {
        const selectedOpt = eventFilter.selectedOptions[0];
        if (selectedOpt) {
            status.textContent = `Filtered to ${selectedOpt.text}`;
        }
    }
}

function initDatasource(state) {
    const datasourceField = document.getElementById("datasource-field");
    const datasourceSelect = document.getElementById("datasource-select");
    if (!datasourceField || !datasourceSelect) {
        return;
    }

    const settings = state.settings;
    const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function')
        ? Obsidianscout.getProgram() === "FTC"
        : (settings?.program === "FTC");
    const effectiveUseEpa = !isFtc && settings?.useStatboticsEpa;
    const effectiveUseExp = !isFtc && settings?.useMatch13Exp;
    const effectiveUseOpr = settings?.useTbaOpr;

    const hasAnyExternal = effectiveUseEpa || effectiveUseExp || effectiveUseOpr;

    if (settings && hasAnyExternal) {
        datasourceField.classList.remove("hidden");
        datasourceSelect.innerHTML = "";

        const optAll = document.createElement("option");
        optAll.value = "all";
        optAll.textContent = t('rankings.metric.all', "All Sources");
        datasourceSelect.appendChild(optAll);

        const optScouted = document.createElement("option");
        optScouted.value = "scouted";
        optScouted.textContent = t('predictor.scouted_data', "Scouted Data");
        optScouted.selected = true;
        datasourceSelect.appendChild(optScouted);

        if (effectiveUseEpa) {
            const optEpa = document.createElement("option");
            optEpa.value = "epa";
            optEpa.textContent = t('predictor.statbotics_epa', "Statbotics EPA");
            datasourceSelect.appendChild(optEpa);
        }

        if (effectiveUseExp) {
            const optExp = document.createElement("option");
            optExp.value = "exp";
            optExp.textContent = t('alliance-selection.match13_exp', "Match 13 EXP");
            datasourceSelect.appendChild(optExp);
        }

        if (effectiveUseOpr) {
            const optOpr = document.createElement("option");
            optOpr.value = "opr";
            optOpr.textContent = isFtc ? t('predictor.ftcscout_opr', "FTC Scout OPR") : t('predictor.tba_opr', "TBA OPR");
            datasourceSelect.appendChild(optOpr);
        }

        datasourceSelect.addEventListener("change", () => {
            state.datasource = datasourceSelect.value;
            toggleFieldsForDatasource(state);
        });
    } else {
        datasourceField.classList.add("hidden");
        state.datasource = "scouted";
    }

    toggleFieldsForDatasource(state);
}

function toggleFieldsForDatasource(state) {
    const metricField = document.getElementById("metric-field");
    const viewField = document.getElementById("view-field");
    const prescoutField = document.getElementById("prescout-field");
    const viewSelect = document.getElementById("graph-view");

    // Match-by-match and custom metrics are strictly for Scouted Data
    if (state.datasource === "scouted") {
        viewField?.classList.remove("hidden");
        metricField?.classList.remove("hidden");
        prescoutField?.classList.remove("hidden");
    } else {
        viewField?.classList.add("hidden");
        metricField?.classList.add("hidden");
        prescoutField?.classList.add("hidden");
        state.dataView = "averages";
        if (viewSelect) {
            viewSelect.value = "averages";
        }
    }
    updateGraphTypeAvailability(state);
}

function initTeamSelection(state) {
    const searchInput = document.getElementById("team-search-input");
    const clearSearch = document.getElementById("clear-search-btn");
    const selectAll = document.getElementById("select-all-teams");
    const clearAll = document.getElementById("clear-teams");
    const topTeams = document.getElementById("select-top-teams");
    const addEvent = document.getElementById("add-event-teams");

    updateTeamList(state);

    if (searchInput) {
        searchInput.addEventListener("input", () => filterTeamList(searchInput.value));
    }

    if (clearSearch) {
        clearSearch.addEventListener("click", () => {
            if (searchInput) {
                searchInput.value = "";
            }
            filterTeamList("");
        });
    }

    if (selectAll) {
        selectAll.addEventListener("click", () => {
            const visibleTeams = getVisibleTeams();
            visibleTeams.forEach((team) => state.selectedTeams.add(team));
            updateTeamList(state);
            updateSelectionSummary(state);
        });
    }

    if (clearAll) {
        clearAll.addEventListener("click", () => {
            state.selectedTeams.clear();
            updateTeamList(state);
            updateSelectionSummary(state);
        });
    }

    if (topTeams) {
        topTeams.addEventListener("click", () => {
            const metricSelect = document.getElementById("graph-metric");
            if (metricSelect && metricSelect.value) {
                state.metricId = metricSelect.value;
            }
            const metric = state.metricMap.get(state.metricId) || state.metrics[0];
            const filteredEntries = getFilteredEntriesForEvent(state);
            const teamStats = buildTeamStats(filteredEntries, metric, state);
            const top = teamStats.sort((a, b) => b.value - a.value).slice(0, 8).map((item) => item.teamNumber);
            state.selectedTeams.clear();
            top.forEach((team) => state.selectedTeams.add(team));
            updateTeamList(state);
            updateSelectionSummary(state);
        });
    }

    if (addEvent) {
        addEvent.addEventListener("click", () => {
            const filteredEntries = getFilteredEntriesForEvent(state);
            const teams = Array.from(new Set(filteredEntries.map((entry) => entry.targetTeamNumber).filter(Boolean)));
            teams.forEach((team) => state.selectedTeams.add(team));
            updateTeamList(state);
            updateSelectionSummary(state);
        });
    }
}

function updateGraphTypeAvailability(state) {
    const isAverages = state.dataView === "averages" || (state.datasource && state.datasource !== "scouted");
    const lineCheckbox = document.querySelector('.graph-type-checkbox[value="line"]');
    const lineItem = lineCheckbox?.closest(".graph-type-item");
    const countBadge = document.getElementById("graph-type-selected-count");

    if (lineCheckbox && lineItem) {
        if (isAverages) {
            lineCheckbox.disabled = true;
            lineItem.classList.add("disabled");
            lineItem.title = t('graphs.line_requires_matches', "Line graphs require multiple data points across matches and are only available in Match-by-match view.");
            if (state.selectedGraphTypes.has("line")) {
                state.selectedGraphTypes.delete("line");
                lineCheckbox.checked = false;
                updateGraphTypeBadge(state, countBadge);
            }
        } else {
            lineCheckbox.disabled = false;
            lineItem.classList.remove("disabled");
            lineItem.title = "";
        }
    }
}

function initGraphTypeControls(state) {
    const checkboxes = document.querySelectorAll(".graph-type-checkbox");
    const countBadge = document.getElementById("graph-type-selected-count");
    const selectAllBtn = document.getElementById("select-all-graph-types");
    const clearBtn = document.getElementById("clear-graph-types");

    checkboxes.forEach((checkbox) => {
        checkbox.addEventListener("change", () => {
            if (checkbox.checked) {
                state.selectedGraphTypes.add(checkbox.value);
            } else {
                state.selectedGraphTypes.delete(checkbox.value);
            }
            updateGraphTypeBadge(state, countBadge);
        });
    });

    if (selectAllBtn) {
        selectAllBtn.addEventListener("click", () => {
            const isAverages = state.dataView === "averages" || (state.datasource && state.datasource !== "scouted");
            const allTypes = GRAPH_TYPES
                .map((g) => g.id)
                .filter((id) => !isAverages || id !== "line");
            setGraphTypes(state, allTypes, countBadge);
        });
    }
    if (clearBtn) {
        clearBtn.addEventListener("click", () => setGraphTypes(state, [], countBadge));
    }

    setGraphTypes(state, ["bar"], countBadge);
    updateGraphTypeAvailability(state);
}

function wireGraphOptions(state) {
    const dataView = document.getElementById("graph-view");
    const sortSelect = document.getElementById("graph-sort");
    const generateButton = document.getElementById("graph-generate");
    const includePrescoutCheckbox = document.getElementById("include-prescout-checkbox");

    if (dataView) {
        dataView.addEventListener("change", () => {
            state.dataView = dataView.value;
            updateGraphTypeAvailability(state);
        });
    }
    if (sortSelect) {
        sortSelect.addEventListener("change", () => {
            state.sort = sortSelect.value;
        });
    }
    if (includePrescoutCheckbox) {
        includePrescoutCheckbox.addEventListener("change", () => {
            state.includePrescout = includePrescoutCheckbox.checked;
            updateTeamList(state);
            updateSelectionSummary(state);
        });
    }
    if (generateButton) {
        generateButton.addEventListener("click", () => generateGraphs(state));
    }
}

function updateTeamList(state) {
    const list = document.getElementById("team-list");
    if (!list) {
        return;
    }

    let teams;
    if (state.eventKey && state.eventTeams && state.eventTeams.size > 0) {
        teams = Array.from(state.eventTeams);
        state.selectedTeams.forEach(teamNumber => {
            if (!state.eventTeams.has(teamNumber)) {
                state.selectedTeams.delete(teamNumber);
            }
        });
    } else {
        const filteredEntries = getFilteredEntriesForEvent(state);
        teams = Array.from(new Set(filteredEntries.map((entry) => Number(entry.targetTeamNumber)).filter(Boolean)));
    }

    const scoutedTeamNumbers = new Set(
        state.entries
            .filter((entry) => (!state.eventKey || isMatchingEvent(entry.eventKey, state.eventKey)) && (state.includePrescout || !entry.isPrescout))
            .map((entry) => Number(entry.targetTeamNumber))
            .filter(Boolean)
    );

    teams.sort((a, b) => a - b);
    list.innerHTML = "";

    if (!teams.length) {
        const empty = document.createElement("p");
        empty.className = "notice";
        empty.textContent = t('graphs.no_teams_found', "No teams found for this event yet.");
        list.appendChild(empty);
        updateSelectionSummary(state);
        return;
    }

    teams.forEach((teamNumber) => {
        const item = document.createElement("label");
        item.className = "team-list-item";
        item.dataset.teamNumber = String(teamNumber);

        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.checked = state.selectedTeams.has(teamNumber);
        checkbox.addEventListener("change", () => {
            if (checkbox.checked) {
                state.selectedTeams.add(teamNumber);
            } else {
                state.selectedTeams.delete(teamNumber);
            }
            updateSelectionSummary(state);
        });

        const meta = document.createElement("div");
        meta.className = "team-list-meta";
        const title = document.createElement("strong");

        const teamRecord = state.eventTeamsMap ? state.eventTeamsMap.get(teamNumber) : null;
        const nickname = teamRecord ? (teamRecord.nickname || teamRecord.name) : "";
        title.textContent = nickname ? `Team ${teamNumber} - ${nickname}` : `Team ${teamNumber}`;

        const sub = document.createElement("small");
        const isScouted = scoutedTeamNumbers.has(teamNumber);
        sub.textContent = isScouted ? "Scouted" : "Not Scouted";
        if (!isScouted) {
            sub.style.color = "var(--ink-muted, #737373)";
        }

        meta.appendChild(title);
        meta.appendChild(sub);

        item.appendChild(checkbox);
        item.appendChild(meta);
        list.appendChild(item);
    });

    updateSelectionSummary(state);
    filterTeamList(document.getElementById("team-search-input")?.value || "");
}

async function loadTeamsForEvent(state) {
    state.eventTeams = new Set();
    state.eventTeamsMap = new Map();
    state.matches = [];
    state.statsHistory = { oprs: {}, epaHistory: [], match13History: [] };

    const eventKeyToFetch = state.eventKey || (state.settings ? (Obsidianscout.resolveEventKey(state.settings) || "") : "") || "";
    if (!eventKeyToFetch && !state.eventKey) {
        return;
    }
    const targetKey = state.eventKey || eventKeyToFetch;
    try {
        const [teams, matches, statsHistory] = await Promise.all([
            Obsidianscout.request(`/api/teams?eventKey=${encodeURIComponent(targetKey)}`).catch(() => []),
            Obsidianscout.request(`/api/matches?eventKey=${encodeURIComponent(targetKey)}`).catch(() => []),
            Obsidianscout.request(`/api/stats/history?eventKey=${encodeURIComponent(targetKey)}`).catch(() => ({ oprs: {}, epaHistory: [], match13History: [] }))
        ]);
        if (Array.isArray(teams)) {
            teams.forEach(team => {
                if (team.teamNumber) {
                    state.eventTeams.add(Number(team.teamNumber));
                    state.eventTeamsMap.set(Number(team.teamNumber), team);
                }
            });
        }
        state.matches = Array.isArray(matches) ? matches : [];
        state.statsHistory = statsHistory || { oprs: {}, epaHistory: [], match13History: [] };
    } catch (error) {
        console.error("Failed to load teams for event:", error);
    }
}

function updateSelectionSummary(state) {
    const badge = document.getElementById("selection-summary-badge");
    const status = document.getElementById("team-selection-status");
    const pills = document.getElementById("selected-pills-container");
    const filteredEntries = getFilteredEntriesForEvent(state);

    const totalTeams = (state.eventKey && state.eventTeams && state.eventTeams.size > 0)
        ? state.eventTeams.size
        : new Set(filteredEntries.map((entry) => Number(entry.targetTeamNumber)).filter(Boolean)).size;

    if (badge) {
        badge.textContent = state.selectedTeams.size ? `${state.selectedTeams.size} selected` : "No teams selected";
    }
    if (status) {
        status.textContent = `${state.selectedTeams.size} teams selected from ${totalTeams} available`;
    }
    if (pills) {
        pills.innerHTML = "";
        const sorted = Array.from(state.selectedTeams).sort((a, b) => a - b);
        sorted.forEach((teamNumber) => {
            const pill = document.createElement("span");
            pill.className = "team-pill";
            pill.textContent = `Team ${teamNumber}`;
            const remove = document.createElement("button");
            remove.type = "button";
            remove.textContent = "×";
            remove.addEventListener("click", () => {
                state.selectedTeams.delete(teamNumber);
                updateTeamList(state);
                updateSelectionSummary(state);
            });
            pill.appendChild(remove);
            pills.appendChild(pill);
        });
    }
}

function filterTeamList(query) {
    const list = document.getElementById("team-list");
    if (!list) {
        return;
    }
    const term = query.trim().toLowerCase();
    list.querySelectorAll(".team-list-item").forEach((item) => {
        const label = item.textContent.toLowerCase();
        item.style.display = label.includes(term) ? "flex" : "none";
    });
}

function getVisibleTeams() {
    const list = document.getElementById("team-list");
    if (!list) {
        return [];
    }
    const visible = [];
    list.querySelectorAll(".team-list-item").forEach((item) => {
        if (item.style.display === "none") {
            return;
        }
        const teamNumber = Number(item.dataset.teamNumber);
        if (!Number.isNaN(teamNumber)) {
            visible.push(teamNumber);
        }
    });
    return visible;
}

function setGraphTypes(state, types, badge) {
    const isAverages = state.dataView === "averages" || (state.datasource && state.datasource !== "scouted");
    const filteredTypes = isAverages ? types.filter((t) => t !== "line") : types;
    state.selectedGraphTypes = new Set(filteredTypes);
    document.querySelectorAll(".graph-type-checkbox").forEach((checkbox) => {
        checkbox.checked = state.selectedGraphTypes.has(checkbox.value);
    });
    updateGraphTypeBadge(state, badge);
}

function updateGraphTypeBadge(state, badge) {
    if (!badge) {
        return;
    }
    badge.textContent = `${state.selectedGraphTypes.size} selected`;
}

function createGraphCard(titleText, chartElement, noticeText) {
    const card = document.createElement("div");
    card.className = "card";

    const header = document.createElement("div");
    header.className = "graphs-card-header";

    const title = document.createElement("h3");
    title.className = "graphs-card-title";
    title.textContent = titleText;
    header.appendChild(title);

    if (chartElement) {
        const actions = document.createElement("div");
        actions.className = "graphs-card-actions";

        const expandBtn = document.createElement("button");
        expandBtn.type = "button";
        expandBtn.className = "btn ghost btn-sm graph-fullscreen-btn";
        expandBtn.title = "View Fullscreen";
        expandBtn.setAttribute("aria-label", "View Fullscreen");
        expandBtn.innerHTML = SVG_ICONS.expand;
        expandBtn.addEventListener("click", () => {
            openGraphFullscreen(titleText, chartElement);
        });
        actions.appendChild(expandBtn);
        header.appendChild(actions);
    }

    card.appendChild(header);

    if (noticeText) {
        const notice = document.createElement("p");
        notice.className = "notice";
        notice.textContent = noticeText;
        card.appendChild(notice);
    }

    if (chartElement) {
        card.appendChild(chartElement);
    }

    return card;
}

let activeFullscreenResizeHandler = null;

function openGraphFullscreen(titleText, sourceChart) {
    let modal = document.getElementById("graph-fullscreen-modal");
    if (!modal) {
        modal = document.createElement("div");
        modal.id = "graph-fullscreen-modal";
        modal.className = "graphs-fullscreen-overlay hidden";
        modal.innerHTML = `
            <div class="graphs-fullscreen-backdrop"></div>
            <div class="graphs-fullscreen-dialog">
                <div class="graphs-fullscreen-header">
                    <h2 id="graph-fullscreen-title" class="graphs-fullscreen-title"></h2>
                    <button type="button" class="btn ghost btn-sm graphs-fullscreen-close" aria-label="Close fullscreen view">
                        ${SVG_ICONS.close}
                    </button>
                </div>
                <div class="graphs-fullscreen-body">
                    <div id="graph-fullscreen-chart" class="plotly-chart"></div>
                </div>
            </div>
        `;
        document.body.appendChild(modal);

        const closeBtn = modal.querySelector(".graphs-fullscreen-close");
        const backdrop = modal.querySelector(".graphs-fullscreen-backdrop");
        const closeModal = () => {
            modal.classList.add("hidden");
            document.body.classList.remove("modal-open");
            const chartContainer = document.getElementById("graph-fullscreen-chart");
            if (chartContainer && window.Plotly) {
                window.Plotly.purge(chartContainer);
            }
            if (activeFullscreenResizeHandler) {
                window.removeEventListener("resize", activeFullscreenResizeHandler);
                activeFullscreenResizeHandler = null;
            }
        };

        closeBtn.addEventListener("click", closeModal);
        backdrop.addEventListener("click", closeModal);
        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape" && !modal.classList.contains("hidden")) {
                closeModal();
            }
        });
    }

    const titleEl = document.getElementById("graph-fullscreen-title");
    if (titleEl) {
        titleEl.textContent = titleText;
    }

    const chartContainer = document.getElementById("graph-fullscreen-chart");
    if (!chartContainer || !sourceChart) {
        return;
    }

    // Resolve actual .plotly-chart element if wrapped in .chart-scroll
    const actualChart = sourceChart.classList?.contains("plotly-chart")
        ? sourceChart
        : (sourceChart.querySelector?.(".plotly-chart") || sourceChart);

    modal.classList.remove("hidden");
    document.body.classList.add("modal-open");

    // Allow browser to render layout geometry before measuring and rendering Plotly
    requestAnimationFrame(() => {
        if (window.Plotly && actualChart.data && actualChart.layout) {
            const fullLayout = JSON.parse(JSON.stringify(actualChart.layout));
            delete fullLayout.height;
            delete fullLayout.width;
            fullLayout.autosize = true;
            fullLayout.margin = {
                l: Math.max(fullLayout.margin?.l || 60, 60),
                r: Math.max(fullLayout.margin?.r || 30, 30),
                t: 50,
                b: Math.max(fullLayout.margin?.b || 75, 75)
            };

            const fullData = JSON.parse(JSON.stringify(actualChart.data));

            window.Plotly.newPlot(chartContainer, fullData, fullLayout, {
                ...PLOTLY_CONFIG,
                responsive: true
            }).then(() => {
                window.Plotly.Plots.resize(chartContainer);
            });

            if (activeFullscreenResizeHandler) {
                window.removeEventListener("resize", activeFullscreenResizeHandler);
            }
            activeFullscreenResizeHandler = () => {
                if (!modal.classList.contains("hidden")) {
                    window.Plotly.Plots.resize(chartContainer);
                }
            };
            window.addEventListener("resize", activeFullscreenResizeHandler);
        }
    });
}

function generateGraphs(state) {
    const output = document.getElementById("graphs-output");
    const empty = document.getElementById("graphs-empty");
    const loading = document.getElementById("graph-loading");
    if (!output) {
        return;
    }

    if (loading) {
        loading.classList.remove("hidden");
    }

    output.innerHTML = "";
    if (empty) {
        empty.classList.add("hidden");
    }

    const selectedTeams = Array.from(state.selectedTeams);
    if (!selectedTeams.length) {
        output.appendChild(buildNotice("Select at least one team to generate graphs."));
        if (empty) {
            empty.classList.remove("hidden");
        }
        if (loading) {
            loading.classList.add("hidden");
        }
        return;
    }

    const selectedGraphTypes = Array.from(state.selectedGraphTypes);
    if (!selectedGraphTypes.length) {
        output.appendChild(buildNotice("Select at least one graph type."));
        if (empty) {
            empty.classList.remove("hidden");
        }
        if (loading) {
            loading.classList.add("hidden");
        }
        return;
    }

    // External / All data sources: only render Team Averages
    if (state.datasource && state.datasource !== "scouted") {
        selectedGraphTypes.forEach((graphType) => {
            if (graphType === "line") {
                const card = createGraphCard(`${getDatasourceLabel(state.datasource, state)} - Line`, null, t('graphs.line_not_supported_averages', "Line graphs are not supported for team averages as they require multiple data points across matches. Line graphs are only available for Scouted Match-by-match view."));
                output.appendChild(card);
                return;
            }
            if (graphType === "box" || graphType === "violin" || graphType === "histogram") {
                const card = createGraphCard(`${getDatasourceLabel(state.datasource, state)} - ${graphType}`, null, "Distribution graphs are only supported for Scouted Data.");
                output.appendChild(card);
                return;
            }

            const chart = createPlotlyContainer(320);
            const card = createGraphCard(`${getDatasourceLabel(state.datasource, state)} - ${graphType} (Team averages)`, chart);
            output.appendChild(card);

            renderNonScoutedGraph(graphType, chart, selectedTeams, state);
        });

        if (loading) {
            loading.classList.add("hidden");
        }
        return;
    }

    // Ensure state.metricId matches active selector value
    const metricSelect = document.getElementById("graph-metric");
    if (metricSelect && metricSelect.value) {
        state.metricId = metricSelect.value;
    }
    const metric = state.metricMap.get(state.metricId) || state.metrics[0];
    const filteredEntries = getFilteredEntriesForTeams(state);

    if (!filteredEntries.length) {
        output.appendChild(buildNotice("No entries found for the selected teams."));
        if (empty) {
            empty.classList.remove("hidden");
        }
        if (loading) {
            loading.classList.add("hidden");
        }
        return;
    }

    // Check for discrepancies in selected data
    const hasDiscrepancy = filteredEntries.some(e => e.hasDiscrepancy);
    if (hasDiscrepancy) {
        const warnBanner = document.createElement("div");
        warnBanner.className = "sharing-notice mb-24";
        warnBanner.style.borderColor = "#eab308";
        warnBanner.style.background = "rgba(234, 179, 8, 0.08)";
        warnBanner.style.color = "#854d0e";
        warnBanner.style.padding = "10px 16px";
        warnBanner.style.borderRadius = "6px";
        warnBanner.style.border = "1px solid";
        warnBanner.style.display = "flex";
        warnBanner.style.alignItems = "center";
        warnBanner.style.gap = "10px";
        warnBanner.innerHTML = `
            <span class="icon">${SVG_ICONS.warning}</span>
            <div style="flex:1;">
                <strong>Discrepancy Warning:</strong> Some of the data used in these graphs contains conflicting inputs from partner teams. You can review or resolve this in the Alliance Scouting Data page.
            </div>
        `;
        output.appendChild(warnBanner);
    }

    selectedGraphTypes.forEach((graphType) => {
        if (graphType === "line" && state.dataView === "averages") {
            const cardTitle = `${(window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(metric.label) : metric.label} - Line`;
            const card = createGraphCard(cardTitle, null, t('graphs.line_not_supported_averages', "Line graphs are not supported for Team averages because they require multiple data points across matches. Switch to Match-by-match view to use Line graphs."));
            output.appendChild(card);
            return;
        }

        const chart = createPlotlyContainer(320);
        const cardTitle = `${(window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(metric.label) : metric.label} - ${graphType}`;
        const card = createGraphCard(cardTitle, chart);
        output.appendChild(card);

        renderGraphType(graphType, chart, filteredEntries, metric, state);
    });

    if (loading) {
        loading.classList.add("hidden");
    }
}

function getDatasourceLabel(datasource, state) {
    const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function')
        ? Obsidianscout.getProgram() === "FTC"
        : (state?.settings?.program === "FTC");
    if (datasource === "epa") return t('predictor.statbotics_epa', "Statbotics EPA");
    if (datasource === "exp") return t('alliance-selection.match13_exp', "Match 13 EXP");
    if (datasource === "opr") return isFtc ? t('predictor.ftcscout_opr', "FTC Scout OPR") : t('predictor.tba_opr', "TBA OPR");
    if (datasource === "all") return t('rankings.metric.all', "All Sources");
    return t('predictor.scouted_data', "Scouted Data");
}

function renderNonScoutedGraph(graphType, container, selectedTeams, state) {
    const theme = resolveThemeTokens();
    const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function')
        ? Obsidianscout.getProgram() === "FTC"
        : (state.settings?.program === "FTC");
    const effectiveUseEpa = !isFtc && state.settings?.useStatboticsEpa;
    const effectiveUseExp = !isFtc && state.settings?.useMatch13Exp;
    const effectiveUseOpr = state.settings?.useTbaOpr;

    // 1. Build the data series
    const data = selectedTeams.map(teamNumber => {
        const team = state.eventTeamsMap ? state.eventTeamsMap.get(teamNumber) : null;
        return {
            teamNumber,
            label: `Team ${teamNumber}`,
            epa: team ? (team.epa !== null && team.epa !== undefined ? team.epa : 0) : 0,
            exp: team ? (team.exp !== null && team.exp !== undefined ? team.exp : (team.match13Exp !== null && team.match13Exp !== undefined ? team.match13Exp : 0)) : 0,
            opr: team ? (team.opr !== null && team.opr !== undefined ? team.opr : 0) : 0,
            scouted: team ? (team.averagePoints !== null && team.averagePoints !== undefined ? team.averagePoints : 0) : 0
        };
    });

    // 2. Sort the data based on state.sort
    const sortField = state.datasource === "all"
        ? (effectiveUseEpa ? "epa" : (effectiveUseExp ? "exp" : (effectiveUseOpr ? "opr" : "scouted")))
        : state.datasource;

    data.sort((a, b) => {
        if (state.sort === "team_asc") return a.teamNumber - b.teamNumber;
        if (state.sort === "team_desc") return b.teamNumber - a.teamNumber;
        if (state.sort === "value_asc") return a[sortField] - b[sortField];
        return b[sortField] - a[sortField]; // value_desc
    });

    const labels = data.map(item => item.label);

    if (graphType === "bar") {
        if (state.datasource === "epa" && effectiveUseEpa) {
            const series = data.map(item => ({ label: item.label, value: item.epa }));
            renderPlotlyBar(container, series, { orientation: "h" });
        } else if (state.datasource === "exp" && effectiveUseExp) {
            const series = data.map(item => ({ label: item.label, value: item.exp }));
            renderPlotlyBar(container, series, { orientation: "h" });
        } else if (state.datasource === "opr" && effectiveUseOpr) {
            const series = data.map(item => ({ label: item.label, value: item.opr }));
            renderPlotlyBar(container, series, { orientation: "h" });
        } else if (state.datasource === "all") {
            // Grouped bar chart comparing Scouted, EPA, EXP, and OPR
            const series = [];
            series.push({
                name: "Scouted Average",
                x: labels,
                y: data.map(item => item.scouted)
            });
            if (effectiveUseEpa) {
                series.push({
                    name: "Statbotics EPA",
                    x: labels,
                    y: data.map(item => item.epa)
                });
            }
            if (effectiveUseExp) {
                series.push({
                    name: "Match 13 EXP",
                    x: labels,
                    y: data.map(item => item.exp)
                });
            }
            if (effectiveUseOpr) {
                series.push({
                    name: isFtc ? "FTC Scout OPR" : "TBA OPR",
                    x: labels,
                    y: data.map(item => item.opr)
                });
            }
            renderPlotlyMultiBar(container, series);
        }
        return;
    }

    if (graphType === "line") {
        container.appendChild(buildNotice(t('graphs.line_not_supported_averages', "Line graphs are not supported for team averages as they require multiple data points across matches. Line graphs are only available for Scouted Match-by-match view.")));
        return;
    }

    if (graphType === "scatter" || graphType === "area") {
        const series = [];
        if (state.datasource === "scouted" || state.datasource === "all") {
            series.push({
                name: "Scouted Average",
                x: labels,
                y: data.map(item => item.scouted)
            });
        }
        if (state.datasource === "epa" || state.datasource === "all") {
            if (effectiveUseEpa) {
                series.push({
                    name: "Statbotics EPA",
                    x: labels,
                    y: data.map(item => item.epa)
                });
            }
        }
        if (state.datasource === "exp" || state.datasource === "all") {
            if (effectiveUseExp) {
                series.push({
                    name: "Match 13 EXP",
                    x: labels,
                    y: data.map(item => item.exp)
                });
            }
        }
        if (state.datasource === "opr" || state.datasource === "all") {
            if (effectiveUseOpr) {
                series.push({
                    name: isFtc ? "FTC Scout OPR" : "TBA OPR",
                    x: labels,
                    y: data.map(item => item.opr)
                });
            }
        }
        renderPlotlyMultiLine(container, series, { mode: graphType, dataView: "averages" });
        return;
    }
}

function getMatchSortWeightFromEntry(entry) {
    if (!entry) return 0;
    if (entry.isPrescout) {
        return (entry.matchNumber || 0);
    }
    const matchKey = String(entry.matchKey || "").toLowerCase();
    const isPractice = entry.isPractice || matchKey.includes("practice") || matchKey.includes("_pm") || matchKey.includes("_pr");

    let levelWeight = 200000; // default Qualification Match
    if (isPractice) {
        levelWeight = 100000;
    } else if (matchKey.includes("_ef") || matchKey.startsWith("ef")) {
        levelWeight = 300000;
    } else if (matchKey.includes("_qf") || matchKey.startsWith("qf")) {
        levelWeight = 400000;
    } else if (matchKey.includes("_sf") || matchKey.startsWith("sf")) {
        levelWeight = 500000;
    } else if (matchKey.includes("_f") || matchKey.startsWith("f")) {
        levelWeight = 600000;
    }

    const matchNum = entry.matchNumber || 0;
    return levelWeight + (matchNum * 100);
}

function getMatchSortWeightFromLabel(label) {
    if (!label) return 0;
    const str = String(label).trim();
    if (/prescout/i.test(str)) {
        const num = (str.match(/\d+/) || [])[0];
        return num ? parseInt(num, 10) : 0;
    }
    if (/^practice/i.test(str) || /^pm/i.test(str)) {
        const num = (str.match(/\d+/) || [])[0];
        return 100000 + (num ? parseInt(num, 10) * 100 : 0);
    }
    if (/^qm/i.test(str) || /^q\s/i.test(str) || /^qual/i.test(str) || /^match/i.test(str)) {
        const num = (str.match(/\d+/) || [])[0];
        return 200000 + (num ? parseInt(num, 10) * 100 : 0);
    }
    if (/^ef/i.test(str)) {
        const nums = str.match(/\d+/g) || [];
        const setNum = nums[0] ? parseInt(nums[0], 10) : 0;
        const matchNum = nums[1] ? parseInt(nums[1], 10) : 0;
        return 300000 + (setNum * 1000) + matchNum;
    }
    if (/^qf/i.test(str)) {
        const nums = str.match(/\d+/g) || [];
        const setNum = nums[0] ? parseInt(nums[0], 10) : 0;
        const matchNum = nums[1] ? parseInt(nums[1], 10) : 0;
        return 400000 + (setNum * 1000) + matchNum;
    }
    if (/^sf/i.test(str) || /^semi/i.test(str)) {
        const nums = str.match(/\d+/g) || [];
        const setNum = nums[0] ? parseInt(nums[0], 10) : 0;
        const matchNum = nums[1] ? parseInt(nums[1], 10) : 0;
        return 500000 + (setNum * 1000) + matchNum;
    }
    if (/^f\s/i.test(str) || /^final/i.test(str) || /^f\d/i.test(str)) {
        const nums = str.match(/\d+/g) || [];
        const setNum = nums[0] ? parseInt(nums[0], 10) : 0;
        const matchNum = nums[1] ? parseInt(nums[1], 10) : 0;
        return 600000 + (setNum * 1000) + matchNum;
    }
    const anyNum = (str.match(/\d+/) || [])[0];
    return 200000 + (anyNum ? parseInt(anyNum, 10) * 100 : 0);
}

function getSortedCategoriesFromSeries(series) {
    const allLabels = new Set();
    series.forEach((s) => {
        if (Array.isArray(s.x)) {
            s.x.forEach((label) => {
                if (label !== null && label !== undefined) {
                    allLabels.add(String(label));
                }
            });
        }
    });
    return Array.from(allLabels).sort((a, b) => {
        const weightA = getMatchSortWeightFromLabel(a);
        const weightB = getMatchSortWeightFromLabel(b);
        if (weightA !== weightB) {
            return weightA - weightB;
        }
        return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
    });
}

function renderGraphType(graphType, container, entries, metric, state) {
    if (!metric) {
        container.appendChild(buildNotice("Metric is unavailable."));
        return;
    }

    if (metric.kind === "category" && graphType !== "bar") {
        container.appendChild(buildNotice("This metric only supports bar charts."));
        return;
    }

    if (graphType === "bar") {
        if (metric.kind === "category") {
            const series = buildCategoryCounts(entries, metric);
            renderPlotlyBar(container, series, { orientation: "h" });
            return;
        }
        if (state.dataView === "matches") {
            const series = buildTeamSeries(entries, metric, state);
            renderPlotlyMultiBar(container, series);
            return;
        }
        const teamStats = buildTeamStats(entries, metric, state);
        const sorted = sortTeamStats(teamStats, state.sort);
        renderPlotlyBar(container, sorted.map((item) => ({ label: `Team ${item.teamNumber}`, value: item.value })), { orientation: "h" });
        return;
    }

    if (graphType === "line") {
        if (state.dataView === "averages") {
            container.appendChild(buildNotice(t('graphs.line_not_supported_averages', "Line graphs are not supported for Team averages because they require multiple data points across matches. Switch to Match-by-match view to use Line graphs.")));
            return;
        }
        const series = buildTeamSeries(entries, metric, state);
        renderPlotlyMultiLine(container, series, { mode: "line", dataView: state.dataView });
        return;
    }

    if (graphType === "scatter" || graphType === "area") {
        const series = buildTeamSeries(entries, metric, state);
        renderPlotlyMultiLine(container, series, { mode: graphType, dataView: state.dataView });
        return;
    }

    if (graphType === "box" || graphType === "violin") {
        const traces = buildDistributionTraces(entries, metric, state, graphType);
        renderPlotlyDistribution(container, traces);
        return;
    }

    if (graphType === "histogram") {
        const values = buildNumericValues(entries, metric, state);
        renderPlotlyHistogram(container, values);
        return;
    }

    container.appendChild(buildNotice("Unsupported graph type."));
}

function renderSummary(entries) {
    const container = document.getElementById("graphs-summary");
    container.innerHTML = "";

    const teams = new Set(entries.map((entry) => entry.targetTeamNumber).filter((value) => value));
    const matches = new Set(entries.map((entry) => entry.matchKey).filter((value) => value));
    const events = new Set(entries.map((entry) => entry.eventKey).filter((value) => value));

    container.appendChild(buildMetricCard("Entries", entries.length));
    container.appendChild(buildMetricCard("Events", events.size));
    container.appendChild(buildMetricCard("Teams", teams.size));
    container.appendChild(buildMetricCard("Matches", matches.size));
}

function buildMetricCard(label, value) {
    const card = document.createElement("div");
    card.className = "card";

    const title = document.createElement("h3");
    title.textContent = label;
    card.appendChild(title);

    const metric = document.createElement("div");
    metric.className = "metric-value";
    metric.textContent = value;
    card.appendChild(metric);

    return card;
}

function appendPlotlyBarCard(container, titleText, series, noticeText, options = {}) {
    if (!series.length) {
        const card = createGraphCard(titleText, null, noticeText || t('graphs.no_data_yet', "No data yet."));
        container.appendChild(card);
        return;
    }

    const height = chartHeightForBars(series, options);
    const chart = createPlotlyContainer(height);
    const host = series.length > 20 ? wrapChartScroll(chart) : chart;
    const card = createGraphCard(titleText, host, noticeText);
    container.appendChild(card);
    renderPlotlyBar(chart, series, options);
}

function createPlotlyContainer(height) {
    const container = document.createElement("div");
    container.className = "plotly-chart";
    if (height) {
        container.dataset.height = String(height);
    }
    return container;
}

function wrapChartScroll(element) {
    const scroll = document.createElement("div");
    scroll.className = "chart-scroll";
    scroll.appendChild(element);
    return scroll;
}

function renderPlotlyBar(container, series, options = {}) {
    if (!window.Plotly) {
        container.appendChild(buildNotice("Plotly failed to load."));
        return;
    }
    const theme = resolveThemeTokens();
    const orientation = options.orientation || "h";
    const labels = series.map((item) => item.label);
    const values = series.map((item) => item.value);
    const height = Number(container.dataset.height) || chartHeightForBars(series, options);

    const trace = orientation === "h"
        ? {
            type: "bar",
            orientation: "h",
            x: values,
            y: labels,
            marker: { color: theme.accent },
            text: values.map(formatNumber),
            textposition: "auto",
            hovertemplate: "%{y}: %{x}<extra></extra>"
        }
        : {
            type: "bar",
            x: labels,
            y: values,
            marker: { color: theme.accent },
            text: values.map(formatNumber),
            textposition: "auto",
            hovertemplate: "%{x}: %{y}<extra></extra>"
        };

    const layout = {
        height,
        margin: orientation === "h"
            ? { l: 140, r: 24, t: 15, b: 40 }
            : { l: 55, r: 20, t: 40, b: 65 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: {
            gridcolor: theme.grid,
            zerolinecolor: theme.grid,
            automargin: true,
            tickangle: orientation === "h" ? 0 : -45
        },
        yaxis: {
            gridcolor: theme.grid,
            zerolinecolor: theme.grid,
            automargin: true
        },
        legend: {
            orientation: "h",
            yanchor: "bottom",
            y: 1.05,
            xanchor: "center",
            x: 0.5
        }
    };

    window.Plotly.react(container, [trace], layout, PLOTLY_CONFIG);
}

function renderPlotlyMultiLine(container, series, options = {}) {
    if (!window.Plotly) {
        container.appendChild(buildNotice("Plotly failed to load."));
        return;
    }
    const theme = resolveThemeTokens();
    const height = Number(container.dataset.height) || 320;
    const mode = options.mode || "line";
    const traces = series.map((item) => {
        const base = {
            type: "scatter",
            x: item.x,
            y: item.y,
            name: item.name,
            mode: mode === "scatter" ? "markers" : "lines+markers"
        };
        if (mode === "area") {
            base.mode = "lines";
            base.fill = "tozeroy";
        }
        base.line = { width: 2.2 };
        base.marker = { size: 6 };
        return base;
    });

    const sortedCategories = getSortedCategoriesFromSeries(series);

    const layout = {
        height,
        margin: { l: 55, r: 20, t: 45, b: 65 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: {
            gridcolor: theme.grid,
            automargin: true,
            tickangle: -45,
            categoryorder: "array",
            categoryarray: sortedCategories
        },
        yaxis: {
            gridcolor: theme.grid,
            zerolinecolor: theme.grid,
            automargin: true
        },
        legend: {
            orientation: "h",
            yanchor: "bottom",
            y: 1.05,
            xanchor: "center",
            x: 0.5
        }
    };

    window.Plotly.react(container, traces, layout, PLOTLY_CONFIG);
}

function renderPlotlyMultiBar(container, series, options = {}) {
    if (!window.Plotly) {
        container.appendChild(buildNotice("Plotly failed to load."));
        return;
    }
    const theme = resolveThemeTokens();
    const height = Number(container.dataset.height) || 320;
    const traces = series.map((item) => {
        return {
            type: "bar",
            name: item.name,
            x: item.x,
            y: item.y
        };
    });

    const sortedCategories = getSortedCategoriesFromSeries(series);

    const layout = {
        height,
        margin: { l: 55, r: 20, t: 45, b: 65 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: {
            gridcolor: theme.grid,
            automargin: true,
            tickangle: -45,
            categoryorder: "array",
            categoryarray: sortedCategories
        },
        yaxis: {
            gridcolor: theme.grid,
            zerolinecolor: theme.grid,
            automargin: true
        },
        legend: {
            orientation: "h",
            yanchor: "bottom",
            y: 1.05,
            xanchor: "center",
            x: 0.5
        },
        barmode: "group"
    };

    window.Plotly.react(container, traces, layout, PLOTLY_CONFIG);
}

function renderPlotlyDistribution(container, traces) {
    if (!window.Plotly) {
        container.appendChild(buildNotice("Plotly failed to load."));
        return;
    }
    const theme = resolveThemeTokens();
    const height = Number(container.dataset.height) || 320;
    const layout = {
        height,
        margin: { l: 55, r: 20, t: 45, b: 65 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: {
            gridcolor: theme.grid,
            automargin: true,
            tickangle: -45
        },
        yaxis: {
            gridcolor: theme.grid,
            zerolinecolor: theme.grid,
            automargin: true
        },
        legend: {
            orientation: "h",
            yanchor: "bottom",
            y: 1.05,
            xanchor: "center",
            x: 0.5
        }
    };
    window.Plotly.react(container, traces, layout, PLOTLY_CONFIG);
}

function renderPlotlyHistogram(container, values) {
    if (!window.Plotly) {
        container.appendChild(buildNotice("Plotly failed to load."));
        return;
    }
    const theme = resolveThemeTokens();
    const height = Number(container.dataset.height) || 320;
    const trace = {
        type: "histogram",
        x: values,
        marker: { color: theme.accent }
    };
    const layout = {
        height,
        margin: { l: 55, r: 20, t: 30, b: 65 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: {
            gridcolor: theme.grid,
            automargin: true,
            tickangle: -45
        },
        yaxis: {
            gridcolor: theme.grid,
            zerolinecolor: theme.grid,
            automargin: true
        }
    };
    window.Plotly.react(container, [trace], layout, PLOTLY_CONFIG);
}

function chartHeightForBars(series, options = {}) {
    const base = options.baseHeight || 140;
    const perRow = options.rowHeight || 24;
    const desired = base + perRow * series.length;
    const maxHeight = options.maxHeight || 700;
    return Math.min(Math.max(desired, 240), maxHeight);
}

function resolveThemeTokens() {
    const styles = getComputedStyle(document.body);
    const text = styles.getPropertyValue("--ink").trim() || "#1d1a17";
    const accent = styles.getPropertyValue("--accent").trim() || "#0b8f88";
    const accent3 = styles.getPropertyValue("--accent-3").trim() || "#255a9c";
    const grid = document.body.classList.contains("theme-dark")
        ? "rgba(255, 255, 255, 0.12)"
        : "rgba(0, 0, 0, 0.08)";
    return { text, accent, accent3, grid };
}

function normalizePhase(rawPhase) {
    if (!rawPhase) return null;
    const p = String(rawPhase).toLowerCase().trim();
    if (p.includes("auto") || p.includes("autónomo") || p.includes("autonomo")) return "auto";
    if (p.includes("teleop") || p.includes("teleoperado") || p.includes("general")) return "teleop";
    if (p.includes("endgame") || p.includes("end") || p.includes("fin")) return "endgame";
    if (p.includes("post")) return "postmatch";
    return p;
}

function buildMetricOptions(config) {
    const options = [
        { id: "score_total", label: t('graphs.total_points', "Total points"), kind: "score", scope: "total" },
        { id: "score_auto", label: t('graphs.auto_points', "Auto points"), kind: "score", scope: "auto" },
        { id: "score_teleop", label: t('graphs.teleop_points', "Teleop points"), kind: "score", scope: "teleop" },
        { id: "score_endgame", label: t('graphs.endgame_points', "Endgame points"), kind: "score", scope: "endgame" },
        { id: "count", label: t('graphs.entry_count', "Entry count"), kind: "count" }
    ];

    (config.fields || []).forEach((field) => {
        if (RESERVED_FIELDS.has(field.id) || field.type === "section") {
            return;
        }
        const type = String(field.type || "").toLowerCase();
        const label = (window.Obsidianscout && typeof window.Obsidianscout.localize === 'function') 
            ? (Obsidianscout.localize(field.label) || field.id) 
            : (field.label || field.id);

        if (type === "number" || type === "counter" || type === "rating") {
            options.push({ id: `field:${field.id}`, label, kind: "numeric", fieldId: field.id, field });
        } else if (type === "select" || type === "checkbox") {
            options.push({ id: `category:${field.id}`, label, kind: "category", fieldId: field.id, field });
        }
    });

    return options;
}

function filterEntries(entries, eventKey) {
    if (!eventKey) {
        return entries;
    }
    return entries.filter((entry) => isMatchingEvent(entry.eventKey, eventKey));
}

function buildTeamStats(entries, metric, state) {
    const map = new Map();
    entries.forEach((entry) => {
        const teamNumber = entry.targetTeamNumber;
        if (!teamNumber) {
            return;
        }
        if (!map.has(teamNumber)) {
            map.set(teamNumber, { teamNumber, total: 0, count: 0 });
        }
        const value = metricValue(entry, metric, state);
        if (value === null || value === undefined) {
            return;
        }
        const slot = map.get(teamNumber);
        slot.total += value;
        slot.count += 1;
    });
    return Array.from(map.values()).map((item) => ({
        teamNumber: item.teamNumber,
        value: metric.kind === "count" ? item.count : item.count ? item.total / item.count : 0
    }));
}

function sortTeamStats(stats, sort) {
    if (sort === "team_asc") {
        return stats.sort((a, b) => a.teamNumber - b.teamNumber);
    }
    if (sort === "team_desc") {
        return stats.sort((a, b) => b.teamNumber - a.teamNumber);
    }
    if (sort === "value_asc") {
        return stats.sort((a, b) => a.value - b.value);
    }
    return stats.sort((a, b) => b.value - a.value);
}

function isMatchingEvent(keyA, keyB) {
    if (!keyA || !keyB) return false;
    return String(keyA).trim().toLowerCase() === String(keyB).trim().toLowerCase();
}

function buildTeamSeries(entries, metric, state) {
    if (state.dataView === "averages") {
        const stats = sortTeamStats(buildTeamStats(entries, metric, state), state.sort);
        return [{
            name: (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(metric.label) : metric.label,
            x: stats.map((item) => `Team ${item.teamNumber}`),
            y: stats.map((item) => item.value)
        }];
    }

    const groups = new Map();
    entries.forEach((entry) => {
        const teamNumber = Number(entry.targetTeamNumber);
        if (!teamNumber) {
            return;
        }
        if (!groups.has(teamNumber)) {
            groups.set(teamNumber, []);
        }
        groups.get(teamNumber).push(entry);
    });

    const series = [];
    groups.forEach((teamEntries, teamNumber) => {
        const sorted = teamEntries
            .filter((entry) => metricValue(entry, metric, state) !== null)
            .sort((a, b) => {
                const weightA = getMatchSortWeightFromEntry(a);
                const weightB = getMatchSortWeightFromEntry(b);
                if (weightA !== weightB) {
                    return weightA - weightB;
                }
                const aEvent = a.eventKey || "";
                const bEvent = b.eventKey || "";
                if (aEvent !== bEvent) {
                    return aEvent.localeCompare(bEvent);
                }
                if (a.matchPlayedTime !== null && b.matchPlayedTime !== null && a.matchPlayedTime !== undefined && b.matchPlayedTime !== undefined) {
                    return a.matchPlayedTime - b.matchPlayedTime;
                }
                return (a.matchNumber || 0) - (b.matchNumber || 0);
            });
        series.push({
            name: `Team ${teamNumber}`,
            x: sorted.map((entry, index) => {
                let levelAbbrev = "QM";
                if (entry.isPrescout) {
                    levelAbbrev = "Prescout";
                } else if (entry.isPractice) {
                    levelAbbrev = "Practice";
                } else if (entry.matchKey) {
                    const parts = entry.matchKey.split('_');
                    if (parts.length > 1) {
                        const rawLevel = parts[1].replace(/[0-9]/g, "");
                        levelAbbrev = rawLevel.toUpperCase();
                        if (levelAbbrev === "PM" || levelAbbrev === "PR") {
                            levelAbbrev = "Practice";
                        }
                    }
                }
                const num = entry.matchNumber || (index + 1);
                const eventLabel = entry.isPrescout ? (entry.eventKey || "") : "";
                if (levelAbbrev === "Prescout") {
                    return eventLabel ? `Prescout (${eventLabel})` : `Prescout ${num}`;
                }
                return eventLabel ? `${levelAbbrev} ${num} (${eventLabel})` : `${levelAbbrev} ${num}`;
            }),
            y: sorted.map((entry) => metricValue(entry, metric, state))
        });
    });

    return series;
}

function buildCategoryCounts(entries, metric) {
    const counts = new Map();
    entries.forEach((entry) => {
        const data = getEntryData(entry);
        const value = readLabel(data[metric.fieldId]);
        if (!value) {
            return;
        }
        counts.set(value, (counts.get(value) || 0) + 1);
    });
    return Array.from(counts.entries()).map(([label, value]) => ({ label, value }))
        .sort((a, b) => b.value - a.value);
}

function buildNumericValues(entries, metric, state) {
    return entries
        .map((entry) => metricValue(entry, metric, state))
        .filter((value) => value !== null && value !== undefined);
}

function buildDistributionTraces(entries, metric, state, graphType) {
    const valuesByTeam = new Map();
    entries.forEach((entry) => {
        const teamNumber = Number(entry.targetTeamNumber);
        if (!teamNumber) {
            return;
        }
        const value = metricValue(entry, metric, state);
        if (value === null || value === undefined) {
            return;
        }
        if (!valuesByTeam.has(teamNumber)) {
            valuesByTeam.set(teamNumber, []);
        }
        valuesByTeam.get(teamNumber).push(value);
    });

    const traces = [];
    const entriesList = Array.from(valuesByTeam.entries());
    if (entriesList.length <= 8) {
        entriesList.forEach(([teamNumber, values]) => {
            traces.push(buildDistributionTrace(graphType, `Team ${teamNumber}`, values));
        });
        return traces;
    }
    const allValues = entriesList.flatMap(([, values]) => values);
    traces.push(buildDistributionTrace(graphType, (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(metric.label) : metric.label, allValues));
    return traces;
}

function buildDistributionTrace(graphType, name, values) {
    const trace = {
        type: graphType,
        name,
        y: values,
        boxpoints: graphType === "box" ? "outliers" : undefined,
        meanline: { visible: true }
    };
    return trace;
}

function getEntryData(entry) {
    if (!entry) return {};
    if (typeof entry.data === "object" && entry.data !== null) {
        return entry.data;
    }
    if (typeof entry.data === "string") {
        try {
            return JSON.parse(entry.data);
        } catch (_) {
            return {};
        }
    }
    return {};
}

function metricValue(entry, metric, state) {
    if (!metric) {
        return null;
    }
    if (metric.kind === "count") {
        return 1;
    }
    if (metric.kind === "score") {
        return entryScore(state.config, entry, metric.scope);
    }
    const data = getEntryData(entry);
    if (metric.kind === "numeric") {
        return readNumber(data[metric.fieldId]);
    }
    if (metric.kind === "category") {
        return readLabel(data[metric.fieldId]);
    }
    return null;
}

function readNumber(value) {
    if (value === null || value === undefined) {
        return null;
    }
    if (typeof value === "number") {
        return value;
    }
    if (typeof value === "string") {
        const parsed = Number(value);
        return Number.isNaN(parsed) ? null : parsed;
    }
    return null;
}

function readLabel(value) {
    if (value === null || value === undefined) {
        return null;
    }
    if (typeof value === "string") {
        return value;
    }
    if (typeof value === "number") {
        return String(value);
    }
    return null;
}

function readBoolean(value) {
    if (value === null || value === undefined) {
        return null;
    }
    if (typeof value === "boolean") {
        return value;
    }
    if (typeof value === "string") {
        if (value.toLowerCase() === "true") {
            return true;
        }
        if (value.toLowerCase() === "false") {
            return false;
        }
    }
    return null;
}

function fieldPoints(field, value) {
    if (!field || value === null || value === undefined) {
        return 0;
    }
    const type = String(field.type || "").toLowerCase();
    const pointsPer = Number(field.pointsPer || 0);

    if (type === "counter" || type === "number" || type === "rating") {
        const number = readNumber(value) || 0;
        return number * pointsPer;
    }

    if (type === "checkbox") {
        const enabled = readBoolean(value) || false;
        return enabled ? pointsPer : 0;
    }

    if (type === "select") {
        const label = readLabel(value);
        const options = field.options || [];
        const match = options.find((option) => option.value === label || option.label === label);
        return match ? Number(match.points || 0) : 0;
    }

    return 0;
}

function entryScore(config, entry, scope = "total") {
    if (!config || !entry || !config.fields) {
        return 0;
    }
    const data = getEntryData(entry);
    const targetScope = normalizePhase(scope) || "total";
    let currentSectionPhase = "auto";

    return config.fields.reduce((total, field) => {
        if (RESERVED_FIELDS.has(field.id)) {
            return total;
        }
        if (field.type === "section") {
            const secPhase = normalizePhase(field.phase) || normalizePhase(field.label);
            if (secPhase) {
                currentSectionPhase = secPhase;
            }
            return total;
        }

        // Determine field phase
        let fieldPhase = normalizePhase(field.phase);
        if (!fieldPhase) {
            const id = String(field.id || "").toLowerCase();
            if (id.startsWith("auto")) fieldPhase = "auto";
            else if (id.startsWith("teleop")) fieldPhase = "teleop";
            else if (id.startsWith("endgame")) fieldPhase = "endgame";
            else if (id.startsWith("post")) fieldPhase = "postmatch";
            else fieldPhase = currentSectionPhase;
        }

        // Filter by scope
        if (targetScope !== "total" && fieldPhase !== targetScope) {
            return total;
        }

        return total + fieldPoints(field, data[field.id]);
    }, 0);
}

function isMatchingEvent(entryEventKey, filterEventKey) {
    if (!filterEventKey) return true;
    if (!entryEventKey) return false;
    return String(entryEventKey).trim().toLowerCase() === String(filterEventKey).trim().toLowerCase();
}

function getFilteredEntriesForTeams(state) {
    const selectedTeams = Array.from(state.selectedTeams).map(Number);
    const result = [];
    selectedTeams.forEach(teamNumber => {
        const currentEventEntries = state.entries.filter(entry =>
            Number(entry.targetTeamNumber) === teamNumber &&
            (!state.eventKey || isMatchingEvent(entry.eventKey, state.eventKey)) &&
            !entry.isPrescout
        );
        result.push(...currentEventEntries);
        if (state.includePrescout) {
            const prescoutEntries = state.entries.filter(entry =>
                Number(entry.targetTeamNumber) === teamNumber &&
                entry.isPrescout
            );
            result.push(...prescoutEntries);
        }
    });
    return result;
}

function getFilteredEntriesForEvent(state) {
    if (!state.eventKey) {
        return state.includePrescout ? state.entries : state.entries.filter(e => !e.isPrescout);
    }
    const teams = Array.from(new Set(state.entries.map(e => Number(e.targetTeamNumber)).filter(Boolean)));
    const result = [];
    teams.forEach(teamNumber => {
        const currentEventEntries = state.entries.filter(entry =>
            Number(entry.targetTeamNumber) === teamNumber &&
            isMatchingEvent(entry.eventKey, state.eventKey) &&
            !entry.isPrescout
        );
        result.push(...currentEventEntries);
        if (state.includePrescout) {
            const prescoutEntries = state.entries.filter(entry =>
                Number(entry.targetTeamNumber) === teamNumber &&
                entry.isPrescout
            );
            result.push(...prescoutEntries);
        }
    });
    return result;
}

function formatNumber(value) {
    if (Number.isInteger(value)) {
        return value.toString();
    }
    return Number(value).toFixed(2);
}

function buildNotice(message) {
    const notice = document.createElement("p");
    notice.className = "notice";
    notice.textContent = message;
    return notice;
}
