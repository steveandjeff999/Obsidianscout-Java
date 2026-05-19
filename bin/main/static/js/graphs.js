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

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        const [config, entries, events] = await Promise.all([
            Obsidianscout.request("/api/config"),
            Obsidianscout.request("/api/scouting"),
            Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`)
        ]);

        initGraphsPage({ config, entries, events });
    } catch (error) {
        Obsidianscout.showToast("Unable to load graph data", "error");
    }
});

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

function initGraphsPage({ config, entries, events }) {
    const state = {
        config,
        entries,
        events,
        eventKey: "",
        selectedTeams: new Set(),
        metricId: "",
        dataView: "averages",
        sort: "value_desc",
        selectedGraphTypes: new Set(["bar"])
    };

    renderSummary(entries);
    initMetricOptions(state);
    initEventFilter(state);
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
        option.textContent = metric.label;
        metricSelect.appendChild(option);
    });
    state.metricId = metrics.length ? metrics[0].id : "";
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
    allOption.textContent = "All events";
    eventFilter.appendChild(allOption);
    (state.events || []).forEach((event) => {
        const option = document.createElement("option");
        option.value = event.eventKey;
        option.textContent = `${event.name} (${event.year})`;
        eventFilter.appendChild(option);
    });
    eventFilter.addEventListener("change", () => {
        state.eventKey = eventFilter.value;
        updateTeamList(state);
        updateSelectionSummary(state);
        if (status) {
            status.textContent = state.eventKey ? `Filtered to ${eventFilter.selectedOptions[0].text}` : "Showing all events";
        }
    });
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
            const metric = state.metricMap.get(state.metricId);
            const filteredEntries = filterEntries(state.entries, state.eventKey);
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
            const filteredEntries = filterEntries(state.entries, state.eventKey);
            const teams = Array.from(new Set(filteredEntries.map((entry) => entry.targetTeamNumber).filter(Boolean)));
            teams.forEach((team) => state.selectedTeams.add(team));
            updateTeamList(state);
            updateSelectionSummary(state);
        });
    }
}

function initGraphTypeControls(state) {
    const checkboxes = document.querySelectorAll(".graph-type-checkbox");
    const countBadge = document.getElementById("graph-type-selected-count");
    const basicBtn = document.getElementById("select-basic-graphs");
    const distBtn = document.getElementById("select-distribution-graphs");
    const advBtn = document.getElementById("select-advanced-graphs");
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

    if (basicBtn) {
        basicBtn.addEventListener("click", () => setGraphTypes(state, ["bar", "line", "scatter", "area"], countBadge));
    }
    if (distBtn) {
        distBtn.addEventListener("click", () => setGraphTypes(state, ["box", "violin", "histogram"], countBadge));
    }
    if (advBtn) {
        advBtn.addEventListener("click", () => setGraphTypes(state, ["scatter", "area", "line"], countBadge));
    }
    if (clearBtn) {
        clearBtn.addEventListener("click", () => setGraphTypes(state, [], countBadge));
    }

    setGraphTypes(state, ["bar"], countBadge);
}

function wireGraphOptions(state) {
    const dataView = document.getElementById("graph-view");
    const sortSelect = document.getElementById("graph-sort");
    const generateButton = document.getElementById("graph-generate");

    if (dataView) {
        dataView.addEventListener("change", () => {
            state.dataView = dataView.value;
        });
    }
    if (sortSelect) {
        sortSelect.addEventListener("change", () => {
            state.sort = sortSelect.value;
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
    const filteredEntries = filterEntries(state.entries, state.eventKey);
    const teams = Array.from(new Set(filteredEntries.map((entry) => entry.targetTeamNumber).filter(Boolean)))
        .sort((a, b) => a - b);
    list.innerHTML = "";

    if (!teams.length) {
        const empty = document.createElement("p");
        empty.className = "notice";
        empty.textContent = "No teams found for this event yet.";
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
        title.textContent = `Team ${teamNumber}`;
        const sub = document.createElement("small");
        sub.textContent = "Scouted";
        meta.appendChild(title);
        meta.appendChild(sub);

        item.appendChild(checkbox);
        item.appendChild(meta);
        list.appendChild(item);
    });

    updateSelectionSummary(state);
    filterTeamList(document.getElementById("team-search-input")?.value || "");
}

function updateSelectionSummary(state) {
    const badge = document.getElementById("selection-summary-badge");
    const status = document.getElementById("team-selection-status");
    const pills = document.getElementById("selected-pills-container");
    const filteredEntries = filterEntries(state.entries, state.eventKey);
    const totalTeams = new Set(filteredEntries.map((entry) => entry.targetTeamNumber).filter(Boolean)).size;

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
    state.selectedGraphTypes = new Set(types);
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

    const metric = state.metricMap.get(state.metricId);
    const filteredEntries = filterEntries(state.entries, state.eventKey)
        .filter((entry) => selectedTeams.includes(entry.targetTeamNumber));

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

    selectedGraphTypes.forEach((graphType) => {
        const card = document.createElement("div");
        card.className = "card";
        const title = document.createElement("h3");
        title.textContent = `${metric.label} - ${graphType}`;
        card.appendChild(title);

        const chart = createPlotlyContainer(320);
        card.appendChild(chart);
        output.appendChild(card);

        renderGraphType(graphType, chart, filteredEntries, metric, state);
    });

    if (loading) {
        loading.classList.add("hidden");
    }
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
        const teamStats = buildTeamStats(entries, metric, state);
        const sorted = sortTeamStats(teamStats, state.sort);
        renderPlotlyBar(container, sorted.map((item) => ({ label: `Team ${item.teamNumber}`, value: item.value })), { orientation: "h" });
        return;
    }

    if (graphType === "line" || graphType === "scatter" || graphType === "area") {
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
    const card = document.createElement("div");
    card.className = "card";

    const title = document.createElement("h3");
    title.textContent = titleText;
    card.appendChild(title);

    if (noticeText) {
        const notice = document.createElement("p");
        notice.className = "notice";
        notice.textContent = noticeText;
        card.appendChild(notice);
    }

    if (!series.length) {
        const empty = document.createElement("p");
        empty.className = "notice";
        empty.textContent = "No data yet.";
        card.appendChild(empty);
        container.appendChild(card);
        return;
    }

    const height = chartHeightForBars(series, options);
    const chart = createPlotlyContainer(height);
    const host = series.length > 20 ? wrapChartScroll(chart) : chart;
    card.appendChild(host);
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
            ? { l: 140, r: 24, t: 10, b: 30 }
            : { l: 50, r: 20, t: 10, b: 70 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
        yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
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

    const layout = {
        height,
        margin: { l: 50, r: 20, t: 10, b: 50 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: { gridcolor: theme.grid, automargin: true },
        yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
        legend: { orientation: "h", y: -0.2 }
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
        margin: { l: 50, r: 20, t: 10, b: 50 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: { gridcolor: theme.grid, automargin: true },
        yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
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
        margin: { l: 50, r: 20, t: 10, b: 50 },
        paper_bgcolor: "rgba(0,0,0,0)",
        plot_bgcolor: "rgba(0,0,0,0)",
        font: { color: theme.text },
        xaxis: { gridcolor: theme.grid, automargin: true },
        yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
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

function buildMetricOptions(config) {
    const options = [
        { id: "count", label: "Entry count", kind: "count" },
        { id: "score_total", label: "Total points", kind: "score", scope: "total" },
        { id: "score_auto", label: "Auto points", kind: "score", scope: "auto" },
        { id: "score_teleop", label: "Teleop points", kind: "score", scope: "teleop" },
        { id: "score_endgame", label: "Endgame points", kind: "score", scope: "endgame" }
    ];

    (config.fields || []).forEach((field) => {
        if (RESERVED_FIELDS.has(field.id) || field.type === "section") {
            return;
        }
        const type = String(field.type || "").toLowerCase();
        const label = field.label || field.id;
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
    return entries.filter((entry) => entry.eventKey === eventKey);
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

function buildTeamSeries(entries, metric, state) {
    if (state.dataView === "averages") {
        const stats = sortTeamStats(buildTeamStats(entries, metric, state), state.sort);
        return [{
            name: metric.label,
            x: stats.map((item) => item.teamNumber),
            y: stats.map((item) => item.value)
        }];
    }

    const groups = new Map();
    entries.forEach((entry) => {
        const teamNumber = entry.targetTeamNumber;
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
            .sort((a, b) => (a.matchNumber || 0) - (b.matchNumber || 0));
        series.push({
            name: `Team ${teamNumber}`,
            x: sorted.map((entry, index) => entry.matchNumber || index + 1),
            y: sorted.map((entry) => metricValue(entry, metric, state))
        });
    });

    return series;
}

function buildCategoryCounts(entries, metric) {
    const counts = new Map();
    entries.forEach((entry) => {
        const value = readLabel(entry.data && entry.data[metric.fieldId]);
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
        const teamNumber = entry.targetTeamNumber;
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
    traces.push(buildDistributionTrace(graphType, metric.label, allValues));
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
    if (metric.kind === "numeric") {
        return readNumber(entry.data && entry.data[metric.fieldId]);
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

function entryScore(config, entry) {
    if (!config || !entry || !config.fields) {
        return 0;
    }
    return config.fields.reduce((total, field) => {
        if (RESERVED_FIELDS.has(field.id)) {
            return total;
        }
        return total + fieldPoints(field, entry.data && entry.data[field.id]);
    }, 0);
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
