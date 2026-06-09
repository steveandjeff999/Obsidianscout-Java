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
    await loadAnalyticsData(grid);
});

async function loadAnalyticsData(grid) {
    if (!grid) return;
    Obsidianscout.showLoadingSpinner(grid, "Loading analytics...");
    try {
        const response = await Obsidianscout.request("/api/analytics");
        renderWidgets(grid, response.widgets || []);
    } catch (error) {
        console.error("Failed to load analytics:", error);
        Obsidianscout.showRetryButton(grid, "Failed to load analytics data: " + error.message, () => loadAnalyticsData(grid));
    }
}

function renderWidgets(container, widgets) {
    container.innerHTML = "";
    widgets.forEach((widget) => {
        const card = document.createElement("div");
        card.className = "card";

        const title = document.createElement("h3");
        title.textContent = widget.title;
        card.appendChild(title);

        if (widget.type === "bar") {
            const chart = renderBarChart(widget.series || []);
            card.appendChild(chart);
        } else {
            const value = document.createElement("div");
            value.className = "metric-value";
            value.textContent = formatValue(widget.value);
            card.appendChild(value);
        }

        container.appendChild(card);
    });
}

function renderBarChart(series) {
    const wrapper = document.createElement("div");
    wrapper.className = "bar-chart";

    const max = Math.max(1, ...series.map((item) => item.value));
    series.forEach((item) => {
        const row = document.createElement("div");
        row.className = "bar-row";

        const label = document.createElement("div");
        label.className = "bar-label";
        label.textContent = `${(window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(item.label) : item.label} (${item.value})`;

        const track = document.createElement("div");
        track.className = "bar-track";

        const fill = document.createElement("div");
        fill.className = "bar-fill";
        fill.style.width = `${(item.value / max) * 100}%`;

        track.appendChild(fill);
        row.appendChild(label);
        row.appendChild(track);
        wrapper.appendChild(row);
    });

    return wrapper;
}

function formatValue(value) {
    if (value === null || value === undefined) {
        return "0";
    }
    return Number.isInteger(value) ? value.toString() : value.toFixed(2);
}
