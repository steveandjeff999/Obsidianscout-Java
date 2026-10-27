/**
 * Public Data directory: browse teams that made their scouting data public, by team or by event, and open the
 * analytics pages over it. Pages that need match (quantitative) data are greyed out when a team or event only has
 * pit or qualitative entries.
 */

import { t, applyTranslations, loadLocale } from './base/i18n.js';
import { safeGetItem } from './base/storage.js';
import { fetchPublicDirectory, pageAvailability, ONLY_QUALITATIVE_REASON, NOTES_PAGES, isSignedIn } from './base/public-mode.js';

/** Buttons shown for each team + event, in order. The team page needs a target team, so it's reached from these. */
const ACTIONS = [
    ["graphs", "Graphs"],
    ["analytics", "Analytics"],
    ["compare", "Compare Teams"],
    ["custom-analytics", "Custom Analytics"],
    ["rankings", "Rankings"],
    ["projected-rankings", "Projected Rankings"],
    ["predictor", "Match Predictor"],
    ["event-predictor", "Event Predictor"],
    ["match-data", "Match Data"],
    ["pit-data", "Pit Data"],
    ["qual-data", "Qual Data"],
    ["qual-rankings", "Qual Rankings"],
    ["all-data", "All Data"]
];

// Font Awesome chevron-right (solid).
const CHEVRON = '<svg class="public-chevron" width="10" height="16" viewBox="0 0 320 512" aria-hidden="true"><path fill="currentColor" d="M310.6 233.4c12.5 12.5 12.5 32.8 0 45.3l-192 192c-12.5 12.5-32.8 12.5-45.3 0s-12.5-32.8 0-45.3L242.7 256 73.4 86.6c-12.5-12.5-12.5-32.8 0-45.3s32.8-12.5 45.3 0l192 192z"/></svg>';

const state = {
    tab: "team",
    query: "",
    focusTeam: null,
    focusEvent: null,
    /** Season to show, or null for every year. */
    year: null,
    directory: { teams: [] }
};

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function teamLabel(team) {
    const prefix = team.program === "FTC" ? "FTC " : "";
    return `${prefix}${team.teamNumber}${team.teamName ? " · " + team.teamName : ""}`;
}

function pageUrl(team, eventKey, page) {
    const params = new URLSearchParams();
    if (eventKey) params.set("event", eventKey);
    if (team.program !== "FRC") params.set("program", team.program);
    return `/public/${team.teamNumber}/${page}?${params.toString()}`;
}

function countsHtml(ev) {
    const chip = (n, key, label) => `<span class="public-count">${n} ${escapeHtml(t(key, label))}</span>`;
    return `<div class="public-counts">
        ${chip(ev.matchEntries, "public.count_match", "match")}
        ${chip(ev.pitEntries, "public.count_pit", "pit")}
        ${chip(ev.qualEntries, "public.count_qual", "qualitative")}
    </div>`;
}

function actionsHtml(team, ev) {
    const available = pageAvailability(ev, team.notesIncluded);
    const reason = t("public.only_qualitative", ONLY_QUALITATIVE_REASON);
    // Notes pages are only offered by teams that share their notes.
    const actions = ACTIONS.filter(([page]) => team.notesIncluded || !NOTES_PAGES.includes(page));
    const buttons = actions.map(([page, label]) => {
        const text = escapeHtml(t("public.page." + page, label));
        if (available[page]) {
            return `<a class="btn ghost public-action" href="${escapeHtml(pageUrl(team, ev.eventKey, page))}">${text}</a>`;
        }
        const title = page === "pit-data" ? t("public.no_pit", "No pit data") :
            (page === "qual-data" || NOTES_PAGES.includes(page)) ? t("public.no_qual", "No qualitative data") : reason;
        return `<span class="btn ghost public-action disabled" role="link" aria-disabled="true" title="${escapeHtml(title)}">${text}</span>`;
    }).join("");
    const note = ev.analyticsAvailable ? "" : `<div class="public-note">${escapeHtml(reason)}</div>`;
    return `<div class="public-actions">${buttons}</div>${note}`;
}

function inYear(ev) {
    return state.year === null || ev.year === state.year;
}

function matchesQuery(text) {
    return !state.query || text.toLowerCase().includes(state.query);
}

function renderByTeam() {
    const teams = state.directory.teams.filter((team) =>
        (state.focusTeam === null || team.teamNumber === state.focusTeam) &&
        // A team with no events that season has nothing to show for it.
        (state.year === null || team.events.some(inYear)) &&
        (matchesQuery(teamLabel(team)) || team.events.some((ev) => inYear(ev) && matchesQuery(ev.eventName + " " + ev.eventKey))));
    if (!teams.length) return emptyHtml();
    return teams.map((team) => {
        const events = team.events.filter((ev) => inYear(ev) && (state.focusEvent === null || ev.eventKey === state.focusEvent));
        const open = state.focusTeam !== null || teams.length === 1 || Boolean(state.query);
        const body = events.length
            ? events.map((ev) => `
                <div class="public-row">
                    <div class="public-row-head">
                        <span class="public-row-title">${escapeHtml(ev.eventName)} (${ev.year})</span>
                        <span class="public-group-sub">${escapeHtml(ev.eventKey)}</span>
                    </div>
                    ${countsHtml(ev)}
                    ${actionsHtml(team, ev)}
                </div>`).join("")
            : `<div class="public-row public-note">${escapeHtml(t("public.no_entries", "No scouting entries yet."))}</div>`;
        return `<details class="public-group"${open ? " open" : ""}>
            <summary>${CHEVRON}<span>${escapeHtml(teamLabel(team))}</span>
                <span class="public-group-sub">${escapeHtml(t("public.event_count", "{n} events").replace("{n}", events.length))}</span></summary>
            <div class="public-group-body">${body}</div>
        </details>`;
    }).join("");
}

function renderByEvent() {
    const byEvent = new Map();
    state.directory.teams.forEach((team) => {
        team.events.filter(inYear).forEach((ev) => {
            if (!byEvent.has(ev.eventKey)) byEvent.set(ev.eventKey, { ev, teams: [] });
            byEvent.get(ev.eventKey).teams.push({ team, ev });
        });
    });
    const events = [...byEvent.values()]
        .filter(({ ev, teams }) =>
            (state.focusEvent === null || ev.eventKey === state.focusEvent) &&
            (matchesQuery(ev.eventName + " " + ev.eventKey) || teams.some(({ team }) => matchesQuery(teamLabel(team)))))
        .sort((a, b) => (b.ev.year - a.ev.year) || a.ev.eventName.localeCompare(b.ev.eventName));
    if (!events.length) return emptyHtml();
    return events.map(({ ev, teams }) => {
        const open = state.focusEvent !== null || events.length === 1 || Boolean(state.query);
        const rows = teams
            .filter(({ team }) => state.focusTeam === null || team.teamNumber === state.focusTeam)
            .sort((a, b) => a.team.teamNumber - b.team.teamNumber)
            .map(({ team, ev: teamEv }) => `
                <div class="public-row">
                    <div class="public-row-head"><span class="public-row-title">${escapeHtml(teamLabel(team))}</span></div>
                    ${countsHtml(teamEv)}
                    ${actionsHtml(team, teamEv)}
                </div>`).join("");
        return `<details class="public-group"${open ? " open" : ""}>
            <summary>${CHEVRON}<span>${escapeHtml(ev.eventName)} (${ev.year})</span>
                <span class="public-group-sub">${escapeHtml(t("public.team_count", "{n} teams").replace("{n}", teams.length))}</span></summary>
            <div class="public-group-body">${rows}</div>
        </details>`;
    }).join("");
}

function emptyHtml() {
    const message = !state.directory.teams.length
        ? t("public.none", "No teams are sharing their data publicly yet.")
        : (state.year !== null && !state.query)
            ? t("public.no_year", "No public data for {year}.").replace("{year}", state.year)
            : t("public.no_matches", "Nothing matches your search.");
    return `<p class="public-empty">${escapeHtml(message)}</p>`;
}

function render() {
    document.querySelectorAll(".public-tab").forEach((tab) => {
        tab.setAttribute("aria-selected", String(tab.dataset.tab === state.tab));
    });
    const results = document.getElementById("public-results");
    let filterNote = "";
    if (state.focusTeam !== null || state.focusEvent !== null) {
        const parts = [];
        if (state.focusTeam !== null) parts.push(`${t("public.team", "Team")} ${state.focusTeam}`);
        if (state.focusEvent !== null) parts.push(state.focusEvent);
        filterNote = `<p class="public-note">${escapeHtml(t("public.filtered", "Showing {what}.").replace("{what}", parts.join(", ")))}
            <a href="/public-data">${escapeHtml(t("public.show_all", "Show all"))}</a></p>`;
    }
    results.innerHTML = filterNote + (state.tab === "team" ? renderByTeam() : renderByEvent());
}

/** Fills the year menu with every season that has public data, newest first. */
function renderYearOptions() {
    const select = document.getElementById("public-year");
    const years = [...new Set(state.directory.teams.flatMap((team) => team.events.map((ev) => ev.year)))]
        .filter((year) => year > 0)
        .sort((a, b) => b - a);
    // Keep a year from the URL selectable even if no team has data for it any more.
    if (state.year !== null && !years.includes(state.year)) years.unshift(state.year);
    select.innerHTML = `<option value="">${escapeHtml(t("public.all_years", "All years"))}</option>` +
        years.map((year) => `<option value="${year}"${year === state.year ? " selected" : ""}>${year}</option>`).join("");
}

function syncUrl() {
    const params = new URLSearchParams(window.location.search);
    if (state.tab === "event") params.set("tab", "event"); else params.delete("tab");
    if (state.year !== null) params.set("year", String(state.year)); else params.delete("year");
    const query = params.toString();
    history.replaceState(null, "", `/public-data${query ? "?" + query : ""}`);
}

async function init() {
    await loadLocale(safeGetItem("obsidianscout:lang") || "en");
    applyTranslations();

    // The header offers "Sign in" only to visitors who aren't signed in already.
    isSignedIn().then((signedIn) => {
        if (!signedIn) return;
        const link = document.getElementById("public-account-link");
        link.href = "/dashboard";
        link.setAttribute("data-i18n", "public.go_to_dashboard");
        link.textContent = t("public.go_to_dashboard", "Go to your dashboard");
    });

    const params = new URLSearchParams(window.location.search);
    const team = parseInt(params.get("team") || "", 10);
    state.focusTeam = Number.isFinite(team) ? team : null;
    const event = (params.get("event") || "").trim().toLowerCase();
    state.focusEvent = event || null;
    const year = parseInt(params.get("year") || "", 10);
    state.year = Number.isFinite(year) ? year : null;
    state.tab = params.get("tab") === "event" || (state.focusEvent && state.focusTeam === null) ? "event" : "team";

    document.querySelectorAll(".public-tab").forEach((tab) => {
        tab.addEventListener("click", () => {
            state.tab = tab.dataset.tab;
            syncUrl();
            render();
        });
    });
    const yearSelect = document.getElementById("public-year");
    yearSelect.addEventListener("change", () => {
        const year = parseInt(yearSelect.value, 10);
        state.year = Number.isFinite(year) ? year : null;
        syncUrl();
        render();
    });
    const search = document.getElementById("public-search");
    search.addEventListener("input", () => {
        state.query = search.value.trim().toLowerCase();
        render();
    });

    try {
        state.directory = await fetchPublicDirectory({ fresh: true });
    } catch (e) {
        document.getElementById("public-results").innerHTML =
            `<p class="public-empty">${escapeHtml(t("public.load_failed", "Couldn't load public data. Try again in a moment."))}</p>`;
        return;
    }
    renderYearOptions();
    render();
}

init();
