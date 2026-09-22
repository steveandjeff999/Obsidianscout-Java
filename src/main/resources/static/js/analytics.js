function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

let currentUsePrescout = false;

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

    const grid = document.getElementById("analytics-grid");
    const togglePrescout = document.getElementById("toggle-prescout");
    const btnRefresh = document.getElementById("btn-refresh-analytics");

    if (togglePrescout) {
        togglePrescout.addEventListener("change", () => {
            currentUsePrescout = togglePrescout.checked;
            loadAnalyticsData(grid);
        });
    }

    if (btnRefresh) {
        btnRefresh.addEventListener("click", () => {
            loadAnalyticsData(grid);
        });
    }

    await loadAnalyticsData(grid);
});

async function loadAnalyticsData(grid) {
    if (!grid) return;
    Obsidianscout.showLoadingSpinner(grid, t('analytics.loading_analytics', "Loading analytics..."));
    try {
        const url = currentUsePrescout ? "/api/analytics?usePrescout=true" : "/api/analytics";
        const response = await Obsidianscout.request(url);
        renderWidgets(grid, response.widgets || []);
    } catch (error) {
        console.error("Failed to load analytics:", error);
        Obsidianscout.showRetryButton(grid, t('analytics.unable_to_load_analytics', "Unable to load analytics data") + ": " + error.message, () => loadAnalyticsData(grid));
    }
}

function renderWidgets(container, widgets) {
    container.innerHTML = "";
    if (!widgets || widgets.length === 0) {
        const empty = document.createElement("div");
        empty.className = "card";
        empty.style.gridColumn = "1 / -1";
        empty.style.textAlign = "center";
        empty.style.padding = "48px 24px";
        empty.innerHTML = `
            <div style="font-size: 3rem; margin-bottom: 12px; opacity: 0.6;">📊</div>
            <h3 style="margin-bottom: 8px;">${t('analytics.no_widgets_configured', 'No analytics widgets configured.')}</h3>
            <p class="notice" style="max-width: 480px; margin: 0 auto;">${t('analytics.no_widgets_hint', 'Configure fields in your scouting settings or collect match data to see live analytics.')}</p>
        `;
        container.appendChild(empty);
        return;
    }

    widgets.forEach((widget) => {
        const card = document.createElement("div");
        const isBar = widget.type === "bar";
        card.className = isBar ? "card metric-card-wide" : "card metric-card";
        if (isBar) {
            card.style.gridColumn = "span 2";
            card.style.minWidth = "280px";
        }

        const title = document.createElement("h3");
        title.className = "metric-title";
        title.style.fontSize = "15px";
        title.style.fontWeight = "600";
        title.style.marginBottom = "12px";
        title.textContent = widget.title;
        card.appendChild(title);

        if (isBar) {
            const chart = renderBarChart(widget.series || []);
            card.appendChild(chart);
        } else {
            const value = document.createElement("div");
            value.className = "metric-value";
            value.style.fontSize = "2rem";
            value.style.fontWeight = "700";
            value.style.color = "var(--accent)";
            value.textContent = formatValue(widget.value, widget.type);
            card.appendChild(value);
        }

        container.appendChild(card);
    });
}

function renderBarChart(series) {
    const wrapper = document.createElement("div");
    wrapper.className = "bar-chart";

    if (!series || series.length === 0) {
        const empty = document.createElement("p");
        empty.className = "notice";
        empty.style.margin = "8px 0 0 0";
        empty.style.fontSize = "13px";
        empty.textContent = t('analytics.no_data_recorded', "No data recorded yet");
        wrapper.appendChild(empty);
        return wrapper;
    }

    const max = Math.max(1, ...series.map((item) => item.value));
    series.forEach((item) => {
        const row = document.createElement("div");
        row.className = "bar-row";

        const label = document.createElement("div");
        label.className = "bar-label";
        const localizedLabel = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(item.label) : item.label;
        label.textContent = `${localizedLabel} (${item.value})`;

        const track = document.createElement("div");
        track.className = "bar-track";

        const fill = document.createElement("div");
        fill.className = "bar-fill";
        fill.style.width = `${Math.min(100, Math.max(0, (item.value / max) * 100))}%`;

        track.appendChild(fill);
        row.appendChild(label);
        row.appendChild(track);
        wrapper.appendChild(row);
    });

    return wrapper;
}

function formatValue(value, type) {
    if (value === null || value === undefined) {
        return "0";
    }
    if (type === "rate" || type === "pct" || type === "percentage") {
        return Number.isInteger(value) ? `${value}%` : `${value.toFixed(1)}%`;
    }
    return Number.isInteger(value) ? value.toString() : value.toFixed(2);
}
