let report = null;
let sortBy = "totalEntries";
let sortDir = "desc";
const expanded = new Set();

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

function esc(value) {
    return Obsidianscout.escapeHtml(value == null ? "" : String(value));
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

    if (!Obsidianscout.isAdmin(me.role)) {
        document.getElementById("admin-locked").classList.remove("hidden");
        document.getElementById("report-panel").classList.add("hidden");
        document.getElementById("table-card").classList.add("hidden");
        return;
    }

    const scopeSelect = document.getElementById("scope-select");
    const eventSelect = document.getElementById("event-select");
    const yearSelect = document.getElementById("year-select");
    const includePrescout = document.getElementById("include-prescout");
    const hideInactive = document.getElementById("hide-inactive");
    const searchInput = document.getElementById("search-input");

    let settings = {};
    let options = { years: [], events: [] };
    try {
        const [settingsResponse, opts] = await Promise.all([
            Obsidianscout.request("/api/settings").catch(() => null),
            Obsidianscout.request("/api/admin/scouter-report/options")
        ]);
        settings = (settingsResponse && settingsResponse.settings) || {};
        options = opts || options;
    } catch (error) {
        Obsidianscout.showToast(error.message || "Failed to load report options", "error");
    }

    const currentEvent = (settings.eventKey || "").toLowerCase();
    const events = options.events.slice();
    if (currentEvent && !events.some((e) => e.eventKey === currentEvent)) {
        events.unshift({ eventKey: currentEvent, name: null, year: settings.year || null });
    }
    eventSelect.innerHTML = events.length
        ? events.map((e) => `<option value="${esc(e.eventKey)}">${esc(e.name ? `${e.name} (${e.eventKey})` : e.eventKey)}</option>`).join("")
        : `<option value="">${esc(t("scouter-report.no_events", "No events with scouting data"))}</option>`;
    if (currentEvent) eventSelect.value = currentEvent;

    const years = options.years.slice();
    if (settings.year && !years.includes(settings.year)) years.unshift(settings.year);
    years.sort((a, b) => b - a);
    yearSelect.innerHTML = years.map((y) => `<option value="${y}">${y}</option>`).join("");
    if (settings.year) yearSelect.value = String(settings.year);

    function syncScopeFields() {
        const scope = scopeSelect.value;
        document.getElementById("event-field").classList.toggle("hidden", scope !== "event");
        document.getElementById("year-field").classList.toggle("hidden", scope !== "year");
    }

    function queryString(extra = {}) {
        const params = new URLSearchParams();
        const scope = scopeSelect.value;
        if (scope === "event" && eventSelect.value) params.set("eventKey", eventSelect.value);
        if (scope === "year" && yearSelect.value) params.set("year", yearSelect.value);
        if (includePrescout.checked) params.set("includePrescout", "true");
        Object.entries(extra).forEach(([k, v]) => params.set(k, v));
        const qs = params.toString();
        return qs ? `?${qs}` : "";
    }

    async function load() {
        if (scopeSelect.value === "event" && !eventSelect.value) {
            report = null;
            render();
            return;
        }
        const tableCard = document.getElementById("table-card");
        const tbody = document.querySelector("#scouter-table tbody");
        tbody.innerHTML = `<tr><td colspan="12" class="notice">${esc(t("scouter-report.loading", "Building report..."))}</td></tr>`;
        try {
            report = await Obsidianscout.request(`/api/admin/scouter-report${queryString()}`);
            expanded.clear();
        } catch (error) {
            report = null;
            Obsidianscout.showToast(error.message || "Failed to build report", "error");
        }
        tableCard.classList.remove("hidden");
        render();
    }

    function download(detail) {
        if (!report) return;
        const a = document.createElement("a");
        a.href = `/api/admin/scouter-report${queryString(detail ? { format: "csv", detail } : { format: "csv" })}`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        Obsidianscout.showToast(t("scouter-report.export_started", "Export download started"), "success");
    }

    scopeSelect.addEventListener("change", () => { syncScopeFields(); load(); });
    eventSelect.addEventListener("change", load);
    yearSelect.addEventListener("change", load);
    includePrescout.addEventListener("change", load);
    hideInactive.addEventListener("change", render);
    searchInput.addEventListener("input", render);
    document.getElementById("export-summary").addEventListener("click", () => download(null));
    document.getElementById("export-by-event").addEventListener("click", () => download("events"));
    document.getElementById("print-report").addEventListener("click", () => window.print());

    document.querySelectorAll("#scouter-table th.sortable").forEach((th) => {
        th.addEventListener("click", () => {
            const col = th.dataset.sort;
            if (sortBy === col) {
                sortDir = sortDir === "asc" ? "desc" : "asc";
            } else {
                sortBy = col;
                sortDir = (col === "username" || col === "role") ? "asc" : "desc";
            }
            render();
        });
    });

    document.querySelector("#scouter-table tbody").addEventListener("click", (e) => {
        const row = e.target.closest("tr.scouter-row.expandable");
        if (!row) return;
        const id = row.dataset.userId;
        if (expanded.has(id)) expanded.delete(id); else expanded.add(id);
        render();
    });

    syncScopeFields();
    // Open on the current event when it has data; otherwise the season is the more useful starting view.
    const currentEventHasData = options.events.some((e) => e.eventKey === currentEvent);
    if (!currentEventHasData && years.length) {
        scopeSelect.value = "year";
        syncScopeFields();
    }
    await load();
});

function scopeLabel(r) {
    if (r.scope === "event") return r.eventName ? `${r.eventName} (${r.eventKey})` : r.eventKey;
    if (r.scope === "year") return t("scouter-report.season_label", "{year} season").replace("{year}", r.year);
    return t("scouter-report.all_time", "All time");
}

function formatDate(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { month: "short", day: "numeric", year: "numeric", hour: "numeric", minute: "2-digit" });
}

function kpi(title, value, sub) {
    return `<div class="report-kpi"><span class="report-kpi-title">${esc(title)}</span><span class="report-kpi-value">${esc(value)}</span>${sub ? `<span class="report-kpi-sub">${esc(sub)}</span>` : ""}</div>`;
}

function rateCell(pct, done, total) {
    if (pct == null) return `<span class="notice">-</span>`;
    const width = Math.max(0, Math.min(100, pct));
    return `<span class="rate-bar"><span style="width:${width}%"></span></span>${esc(pct)}% <span class="notice">(${done}/${total})</span>`;
}

function compareRows(a, b) {
    const av = a[sortBy];
    const bv = b[sortBy];
    let result;
    if (av == null && bv == null) result = 0;
    else if (av == null) return 1; // blanks always last
    else if (bv == null) return -1;
    else if (typeof av === "number" && typeof bv === "number") result = av - bv;
    else result = String(av).localeCompare(String(bv), undefined, { sensitivity: "base" });
    if (result === 0) result = a.username.localeCompare(b.username, undefined, { sensitivity: "base" });
    return sortDir === "asc" ? result : -result;
}

function render() {
    const kpis = document.getElementById("report-kpis");
    const meta = document.getElementById("report-meta");
    const tbody = document.querySelector("#scouter-table tbody");
    const empty = document.getElementById("empty-state");
    const printHeading = document.getElementById("print-heading");

    document.querySelectorAll("#scouter-table th.sortable").forEach((th) => {
        th.classList.toggle("sorted-asc", th.dataset.sort === sortBy && sortDir === "asc");
        th.classList.toggle("sorted-desc", th.dataset.sort === sortBy && sortDir === "desc");
    });

    if (!report) {
        kpis.innerHTML = "";
        meta.textContent = "";
        tbody.innerHTML = "";
        empty.classList.remove("hidden");
        return;
    }

    const totals = report.totals;
    const totalEntries = totals.matchEntries + totals.pitEntries + totals.qualEntries;
    const assignmentPct = totals.assignmentsTotal ? Math.round(totals.assignmentsCompleted * 1000 / totals.assignmentsTotal) / 10 : null;
    const active = report.scouters.filter((s) => s.totalEntries > 0);
    const avgPerScout = active.length ? Math.round(totalEntries * 10 / active.length) / 10 : 0;
    const top = active[0];
    kpis.innerHTML = [
        kpi(t("scouter-report.kpi_active", "Active Scouts"), totals.activeScouters, `${report.scouters.length} ${t("scouter-report.kpi_users_total", "users listed")}`),
        kpi(t("scouter-report.kpi_matches", "Matches Covered"), totals.matchesCovered, `${totals.matchEntries} ${t("scouter-report.kpi_match_entries", "match entries")}`),
        kpi(t("scouter-report.kpi_pit", "Pit Entries"), totals.pitEntries),
        kpi(t("scouter-report.kpi_qual", "Qual Entries"), totals.qualEntries),
        kpi(t("scouter-report.kpi_avg", "Entries per Active Scout"), avgPerScout),
        kpi(t("scouter-report.kpi_assignments", "Assignments Done"), assignmentPct == null ? "-" : `${assignmentPct}%`,
            totals.assignmentsTotal ? `${totals.assignmentsCompleted} / ${totals.assignmentsTotal}` : t("scouter-report.kpi_no_assignments", "No assignments")),
        kpi(t("scouter-report.kpi_top", "Top Scout"), top ? top.username : "-", top ? `${top.totalEntries} ${t("scouter-report.kpi_entries", "entries")}` : "")
    ].join("");

    const label = scopeLabel(report);
    meta.textContent = `${t("scouter-report.team", "Team")} ${report.teamNumber} · ${label} · ${t("scouter-report.generated", "Generated")} ${formatDate(report.generatedAt)}${report.includePrescout ? ` · ${t("scouter-report.with_prescout", "includes prescout")}` : ""}`;
    printHeading.textContent = `${t("scouter-report.title", "Scouter Report")} - ${label}`;

    const hideInactive = document.getElementById("hide-inactive").checked;
    const search = document.getElementById("search-input").value.trim().toLowerCase();
    const rows = report.scouters
        .filter((s) => !hideInactive || s.totalEntries > 0 || s.assignmentsTotal > 0)
        .filter((s) => !search || s.username.toLowerCase().includes(search) || (s.role || "").toLowerCase().includes(search))
        .sort(compareRows);

    empty.classList.toggle("hidden", rows.length > 0);
    const multiEvent = report.scope !== "event";

    tbody.innerHTML = rows.map((s) => {
        const canExpand = multiEvent && s.events.length > 0;
        const isOpen = canExpand && expanded.has(s.userId);
        const inactive = s.totalEntries === 0;
        const main = `<tr class="scouter-row${canExpand ? " expandable" : ""}${inactive ? " inactive" : ""}" data-user-id="${esc(s.userId)}"${canExpand ? ` title="${esc(t("scouter-report.expand_hint", "Click to see per-event counts"))}"` : ""}>
            <td><strong>${esc(s.username)}</strong></td>
            <td>${esc(s.role)}</td>
            <td class="num" title="${esc(s.matchEntries)} ${esc(t("scouter-report.kpi_match_entries", "match entries"))}">${s.matchesScouted}</td>
            <td class="num">${s.pitEntries}</td>
            <td class="num">${s.qualEntries}</td>
            <td class="num"><strong>${s.totalEntries}</strong></td>
            <td class="num">${s.eventsScouted}</td>
            <td class="num">${s.teamsScouted}</td>
            <td class="num">${s.avgCompletenessPct == null ? "-" : `${s.avgCompletenessPct}%`}</td>
            <td class="num">${s.discrepancyEntries}</td>
            <td class="num">${rateCell(s.assignmentCompletionPct, s.assignmentsCompleted, s.assignmentsTotal)}</td>
            <td>${esc(formatDate(s.lastSubmission))}</td>
        </tr>`;
        if (!isOpen) return main;
        const breakdown = s.events.map((e) => `<tr>
                <td>${esc(e.eventName ? `${e.eventName} (${e.eventKey})` : (e.eventKey || t("scouter-report.no_event", "No event")))}</td>
                <td class="num">${e.matchEntries}</td>
                <td class="num">${e.pitEntries}</td>
                <td class="num">${e.qualEntries}</td>
                <td class="num">${e.matchEntries + e.pitEntries + e.qualEntries}</td>
            </tr>`).join("");
        return `${main}<tr class="event-breakdown"><td colspan="12">
            <table class="breakdown-table">
                <thead><tr>
                    <th>${esc(t("scouter-report.event", "Event"))}</th>
                    <th class="num">${esc(t("scouter-report.col_match_entries", "Match Entries"))}</th>
                    <th class="num">${esc(t("scouter-report.col_pit", "Pit"))}</th>
                    <th class="num">${esc(t("scouter-report.col_qual", "Qual"))}</th>
                    <th class="num">${esc(t("scouter-report.col_total", "Total"))}</th>
                </tr></thead>
                <tbody>${breakdown}</tbody>
            </table>
        </td></tr>`;
    }).join("");
}
