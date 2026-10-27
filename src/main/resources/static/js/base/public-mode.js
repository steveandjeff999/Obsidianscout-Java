/**
 * Public Mode - ObsidianScout
 * Runs the normal analytics pages read-only over another team's public data, at /public/{team}/{page}?event=...
 * Every /api/... call is sent to /api/public/{team}/{event}/..., which the server answers as that team (its own
 * entries and scouting config) for as long as the team keeps "Make data publicly available" turned on.
 */

/** Pages that can run in public mode; must match PUBLIC_DATA_PAGES on the server. */
export const PUBLIC_PAGES = [
    "graphs", "analytics", "compare", "custom-analytics", "rankings", "projected-rankings",
    "predictor", "event-predictor", "team", "match-data", "pit-data", "qual-data", "all-data",
    "qual-rankings"
];

/**
 * Pages built on qualitative answers and notes; must match PUBLIC_NOTES_PAGES on the server. Only offered when the
 * team also shares notes and comments, and only usable where it has qualitative entries.
 */
export const NOTES_PAGES = ["qual-rankings"];

/** Pages that need quantitative (match) entries. Greyed out when a team only has pit or qualitative data. */
export const QUANTITATIVE_PAGES = [
    "graphs", "analytics", "compare", "custom-analytics", "rankings", "projected-rankings",
    "predictor", "event-predictor", "match-data"
];

/** API reads the server allows on public data; must match PUBLIC_DATA_ALLOWED_PATHS on the server. */
const ALLOWED_API = new Set([
    "settings",
    "config", "pit-config", "qual-config",
    "scouting", "pit-scouting", "qual-scouting",
    "events", "teams", "matches",
    "stats/history",
    "analytics", "analytics/compare",
    "custom-analytics/dataset",
    "matches/predict", "matches/predict-all", "matches/projected-rankings",
    "field-images"
]);

const PAGE_LABELS = {
    "graphs": "Graphs",
    "analytics": "Analytics",
    "compare": "Compare Teams",
    "custom-analytics": "Custom Analytics",
    "rankings": "Rankings",
    "projected-rankings": "Projected Rankings",
    "predictor": "Match Predictor",
    "event-predictor": "Event Predictor",
    "team": "Team Page",
    "match-data": "Match Data",
    "pit-data": "Pit Data",
    "qual-data": "Qual Data",
    "all-data": "All Data",
    "qual-rankings": "Qual Rankings"
};

const PAGE_ICONS = {
    "graphs": '<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>',
    "analytics": '<line x1="18" y1="20" x2="18" y2="10"></line><line x1="12" y1="20" x2="12" y2="4"></line><line x1="6" y1="20" x2="6" y2="14"></line>',
    "compare": '<polyline points="16 3 21 3 21 8"></polyline><line x1="4" y1="20" x2="21" y2="3"></line><polyline points="21 16 21 21 16 21"></polyline><line x1="15" y1="15" x2="21" y2="21"></line><line x1="4" y1="4" x2="9" y2="9"></line>',
    "custom-analytics": '<path d="M21.21 15.89A10 10 0 1 1 8 2.83"></path><path d="M22 12A10 10 0 0 0 12 2v10z"></path>',
    "rankings": '<line x1="8" y1="6" x2="21" y2="6"></line><line x1="8" y1="12" x2="21" y2="12"></line><line x1="8" y1="18" x2="21" y2="18"></line><line x1="3" y1="6" x2="3.01" y2="6"></line><line x1="3" y1="12" x2="3.01" y2="12"></line><line x1="3" y1="18" x2="3.01" y2="18"></line>',
    "projected-rankings": '<polyline points="23 6 13.5 15.5 8.5 10.5 1 18"></polyline><polyline points="17 6 23 6 23 12"></polyline>',
    "predictor": '<circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline>',
    "event-predictor": '<rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect><line x1="16" y1="2" x2="16" y2="6"></line><line x1="8" y1="2" x2="8" y2="6"></line><line x1="3" y1="10" x2="21" y2="10"></line>',
    "team": '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle>',
    "match-data": '<rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><line x1="3" y1="9" x2="21" y2="9"></line><line x1="9" y1="21" x2="9" y2="9"></line>',
    "pit-data": '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path>',
    "qual-data": '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line>',
    "qual-rankings": '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon>',
    "all-data": '<ellipse cx="12" cy="5" rx="9" ry="3"></ellipse><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>'
};

const NOT_PUBLIC_MESSAGE = "This team's data is not public";
const DIRECTORY_CACHE_KEY = "obsidianscout:public-directory";
const DIRECTORY_CACHE_MS = 60_000;

let cachedContext;

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === 'function') ? window.Obsidianscout.t(key, fallback) : fallback;
}

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/** { team, page, event, program } when this page is a public view, otherwise null. */
export function getPublicContext() {
    if (cachedContext !== undefined) return cachedContext;
    cachedContext = null;
    try {
        const match = window.location.pathname.match(/^\/public\/(\d+)\/([a-z0-9-]+)\/?$/);
        if (match && PUBLIC_PAGES.includes(match[2])) {
            const params = new URLSearchParams(window.location.search);
            const event = (params.get("event") || "").trim().toLowerCase();
            const program = (params.get("program") || "FRC").trim().toUpperCase() === "FTC" ? "FTC" : "FRC";
            cachedContext = { team: Number(match[1]), page: match[2], event: /^[a-z0-9_-]{1,64}$/.test(event) ? event : "", program };
        }
    } catch (e) {
        cachedContext = null;
    }
    return cachedContext;
}

export function isPublicMode() {
    return getPublicContext() !== null;
}

/** URL of another public page for the same team, event and program. Extra query params are kept. */
export function publicPageUrl(page, extraParams = null, ctx = getPublicContext()) {
    const params = new URLSearchParams(extraParams || "");
    params.delete("event");
    params.delete("program");
    if (ctx.event) params.set("event", ctx.event);
    if (ctx.program !== "FRC") params.set("program", ctx.program);
    const query = params.toString();
    return `/public/${ctx.team}/${page}${query ? "?" + query : ""}`;
}

function relativeApiPath(path) {
    const clean = String(path).split("?")[0].split("#")[0];
    if (!clean.startsWith("/api/")) return null;
    return clean.slice("/api/".length).replace(/\/+$/, "");
}

export function publicApiPath(path, ctx = getPublicContext()) {
    const [base, query = ""] = String(path).split("?");
    const rel = base.slice("/api/".length);
    const params = new URLSearchParams(query);
    if (ctx.program !== "FRC") params.set("program", ctx.program);
    const qs = params.toString();
    return `/api/public/${ctx.team}/${ctx.event || "_"}/${rel}${qs ? "?" + qs : ""}`;
}

/** Signed-in user stand-in, so pages written for a session run unchanged. */
export function makePublicUser(ctx = getPublicContext()) {
    return {
        userId: "public",
        username: `Team ${ctx.team} public data`,
        teamNumber: ctx.team,
        role: "ANALYTICS",
        program: ctx.program,
        publicView: true
    };
}

function showToastSafe(message, tone) {
    if (window.Obsidianscout && typeof window.Obsidianscout.showToast === 'function') {
        window.Obsidianscout.showToast(message, tone);
    }
}

/** request() for public mode: GET only, never cached, never served from the signed-in user's offline stores. */
export async function publicRequest(path, options = {}) {
    const ctx = getPublicContext();
    const method = (options.method || "GET").toUpperCase();
    if (method !== "GET") {
        showToastSafe(t("public.read_only", "This is a read-only public view."), "info");
        const err = new Error(t("public.read_only", "This is a read-only public view."));
        err.status = 405;
        throw err;
    }
    const rel = relativeApiPath(path);
    if (rel === null || !ALLOWED_API.has(rel)) {
        // Session, chat, banners and the like have no public equivalent: answer as "nothing there".
        const err = new Error(t("public.not_available", "Not available on public data"));
        err.status = 404;
        err.publicUnavailable = true;
        if (rel && /^(auth|push|banners|chat|notifications|version)(\/|$)/.test(rel)) return null;
        throw err;
    }
    let response;
    try {
        response = await fetch(publicApiPath(path, ctx), {
            method: "GET",
            credentials: "omit",
            cache: "no-store",
            headers: { "Accept": "application/json" },
            signal: options.signal
        });
    } catch (e) {
        const err = new Error(t("public.network_error", "Could not reach the server."));
        err.network = true;
        throw err;
    }
    const text = await response.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
    if (!response.ok) {
        const message = data && data.error ? data.error : "Request failed";
        if (response.status === 403 && message === NOT_PUBLIC_MESSAGE) showNoLongerPublic(ctx);
        if (response.status === 429) showToastSafe(message, "error");
        const err = new Error(message);
        err.status = response.status;
        throw err;
    }
    return data;
}

function showNoLongerPublic(ctx) {
    if (document.body.dataset.publicGone === "true") return;
    document.body.dataset.publicGone = "true";
    try { sessionStorage.removeItem(DIRECTORY_CACHE_KEY); } catch (e) {}
    const main = document.querySelector(".main-content");
    if (!main) return;
    main.innerHTML = `
        <div class="card public-gone">
            <h1>${escapeHtml(t("public.gone_title", "Not public"))}</h1>
            <p class="notice">${escapeHtml(t("public.gone_body", "Team {team} isn't sharing its scouting data publicly right now.").replace("{team}", ctx.team))}</p>
            <a class="btn" href="/public-data">${escapeHtml(t("public.back_to_directory", "Back to Public Data"))}</a>
        </div>`;
}

/** Whether the visitor is signed in to their own team (public data needs no sign-in, but they may have one). */
export async function isSignedIn() {
    try {
        const response = await fetch("/api/auth/status", { credentials: "same-origin", cache: "no-store", headers: { "Accept": "application/json" } });
        if (!response.ok) return false;
        const data = await response.json();
        return Boolean(data && data.loggedIn);
    } catch (e) {
        return false;
    }
}

/** Public directory, cached for a minute per tab so moving between pages doesn't refetch it. */
export async function fetchPublicDirectory({ fresh = false } = {}) {
    if (!fresh) {
        try {
            const raw = sessionStorage.getItem(DIRECTORY_CACHE_KEY);
            if (raw) {
                const parsed = JSON.parse(raw);
                if (parsed && Date.now() - parsed.at < DIRECTORY_CACHE_MS) return parsed.data;
            }
        } catch (e) {}
    }
    const response = await fetch("/api/public/directory", { credentials: "omit", cache: "no-store", headers: { "Accept": "application/json" } });
    if (!response.ok) throw new Error("Could not load public data");
    const data = await response.json();
    try { sessionStorage.setItem(DIRECTORY_CACHE_KEY, JSON.stringify({ at: Date.now(), data })); } catch (e) {}
    return data;
}

/** Which pages make sense for these counts. Notes pages need the team to share notes as well as qualitative entries. */
export function pageAvailability(summary, notesIncluded = false) {
    const match = summary ? summary.matchEntries : 0;
    const pit = summary ? summary.pitEntries : 0;
    const qual = summary ? summary.qualEntries : 0;
    const any = match + pit + qual > 0;
    const result = {};
    PUBLIC_PAGES.forEach((page) => {
        if (QUANTITATIVE_PAGES.includes(page)) result[page] = match > 0;
        else if (page === "pit-data") result[page] = pit > 0;
        else if (page === "qual-data") result[page] = qual > 0;
        else if (NOTES_PAGES.includes(page)) result[page] = notesIncluded && qual > 0;
        else result[page] = any;
    });
    return result;
}

export const ONLY_QUALITATIVE_REASON = "Only pit/qualitative data — no quantitative match data to analyze.";

function icon(paths) {
    return `<svg class="sidebar-link-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths}</svg>`;
}

/**
 * Replaces the signed-in sidebar with the public one: team, event switcher, the public pages for this team and event
 * (analytics greyed out without match data), and a way back to the directory.
 */
export async function renderPublicChrome(sidebar) {
    const ctx = getPublicContext();
    document.body.classList.add("public-mode");
    if (!sidebar) return;

    const backIcon = icon('<polyline points="15 18 9 12 15 6"></polyline>');
    sidebar.innerHTML = `
        <div class="sidebar-header">
            <a class="sidebar-brand" href="/public-data" title="ObsidianScout">
                <img class="sidebar-brand-icon" src="/assets/images/obsidian/obsidian-192.png" alt="ObsidianScout Logo">
                <span class="sidebar-brand-text">ObsidianScout</span>
            </a>
        </div>
        <div class="public-sidebar-team">
            <div class="public-sidebar-label">${escapeHtml(t("public.viewing", "Public data"))}</div>
            <div class="public-sidebar-name" id="public-team-name">${escapeHtml(ctx.program === "FTC" ? "FTC " : "")}${escapeHtml(t("public.team", "Team"))} ${ctx.team}</div>
            <label class="public-sidebar-label" for="public-event-select">${escapeHtml(t("public.event", "Event"))}</label>
            <select id="public-event-select" class="select-wide"><option value="${escapeHtml(ctx.event)}">${escapeHtml(ctx.event || t("public.current_event", "Team's current event"))}</option></select>
            <div class="public-sidebar-note hidden" id="public-quant-note">${escapeHtml(t("public.only_qualitative", ONLY_QUALITATIVE_REASON))}</div>
        </div>
        <nav class="sidebar-nav" id="public-sidebar-nav">
            ${PUBLIC_PAGES.map((page) => `
                <a class="sidebar-link${page === ctx.page ? " active" : ""}${NOTES_PAGES.includes(page) && page !== ctx.page ? " hidden" : ""}" data-public-page="${page}" href="${escapeHtml(publicPageUrl(page))}" title="${escapeHtml(t("public.page." + page, PAGE_LABELS[page]))}">
                    ${icon(PAGE_ICONS[page] || "")}
                    <span class="sidebar-link-text">${escapeHtml(t("public.page." + page, PAGE_LABELS[page]))}</span>
                </a>`).join("")}
            <div class="sidebar-section-title"></div>
            <a class="sidebar-link" href="/public-data?team=${ctx.team}" title="${escapeHtml(t("public.back_to_directory", "Back to Public Data"))}">
                ${backIcon}
                <span class="sidebar-link-text">${escapeHtml(t("public.back_to_directory", "Back to Public Data"))}</span>
            </a>
        </nav>`;

    const main = document.querySelector(".main-content");
    if (main && !main.querySelector(".public-mode-banner")) {
        const banner = document.createElement("div");
        banner.className = "public-mode-banner";
        banner.innerHTML = `${icon('<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path><circle cx="12" cy="12" r="3"></circle>')}
            <span>${escapeHtml(t("public.banner", "Read-only public view of team {team}'s scouting data.").replace("{team}", ctx.team))}</span>`;
        main.prepend(banner);
    }

    // Signed-in visitors get a way back to their own team.
    isSignedIn().then((signedIn) => {
        if (!signedIn) return;
        const back = sidebar.querySelector('a[href^="/public-data"]');
        if (!back || sidebar.querySelector("#public-dashboard-link")) return;
        back.insertAdjacentHTML("afterend", `
            <a class="sidebar-link" id="public-dashboard-link" href="/dashboard" title="${escapeHtml(t("public.go_to_dashboard", "Go to your dashboard"))}">
                ${icon('<path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline>')}
                <span class="sidebar-link-text">${escapeHtml(t("public.go_to_dashboard", "Go to your dashboard"))}</span>
            </a>`);
    });

    const select = sidebar.querySelector("#public-event-select");
    select.addEventListener("change", () => {
        const next = { ...ctx, event: select.value };
        const params = new URLSearchParams(window.location.search);
        window.location.href = publicPageUrl(ctx.page, params, next);
    });

    try {
        const findTeam = (directory) => (directory.teams || []).find((x) => x.teamNumber === ctx.team && x.program === ctx.program);
        // A tab's cached copy may predate the team turning sharing on, so check a fresh one before giving up.
        let team = findTeam(await fetchPublicDirectory());
        if (!team) team = findTeam(await fetchPublicDirectory({ fresh: true }));
        // The API's 403 decides whether the data is public; the directory only adds names, events and counts.
        if (!team) return;
        // Notes pages stay out of the sidebar unless the team shares its notes.
        if (team.notesIncluded) {
            sidebar.querySelectorAll("#public-sidebar-nav .sidebar-link[data-public-page]").forEach((link) => {
                if (NOTES_PAGES.includes(link.dataset.publicPage)) link.classList.remove("hidden");
            });
        }
        if (team.teamName) {
            sidebar.querySelector("#public-team-name").textContent = `${ctx.program === "FTC" ? "FTC " : ""}${ctx.team} · ${team.teamName}`;
        }
        select.innerHTML = (ctx.event ? "" : `<option value="">${escapeHtml(t("public.current_event", "Team's current event"))}</option>`) +
            team.events.map((ev) => `<option value="${escapeHtml(ev.eventKey)}"${ev.eventKey === ctx.event ? " selected" : ""}>${escapeHtml(ev.eventName)} (${ev.year})</option>`).join("");
        if (ctx.event && !team.events.some((ev) => ev.eventKey === ctx.event)) {
            select.insertAdjacentHTML("afterbegin", `<option value="${escapeHtml(ctx.event)}" selected>${escapeHtml(ctx.event)}</option>`);
        }
        const summary = ctx.event ? team.events.find((ev) => ev.eventKey === ctx.event) : null;
        // Without a picked event the team's current event is used; only grey out when we know the counts.
        if (summary) applyAvailability(sidebar, pageAvailability(summary, team.notesIncluded));
    } catch (e) {
        console.warn("[Public] Could not load the public directory:", e);
    }
}

function applyAvailability(sidebar, availability) {
    let anyQuantBlocked = false;
    sidebar.querySelectorAll("#public-sidebar-nav .sidebar-link[data-public-page]").forEach((link) => {
        const page = link.dataset.publicPage;
        if (availability[page] === false) {
            link.classList.add("disabled");
            link.setAttribute("aria-disabled", "true");
            link.removeAttribute("href");
            if (QUANTITATIVE_PAGES.includes(page)) {
                link.title = t("public.only_qualitative", ONLY_QUALITATIVE_REASON);
                anyQuantBlocked = true;
            } else if (NOTES_PAGES.includes(page) || page === "qual-data") {
                link.title = t("public.no_qual", "No qualitative data");
            }
        }
    });
    const note = sidebar.querySelector("#public-quant-note");
    if (note) note.classList.toggle("hidden", !anyQuantBlocked);
    const ctx = getPublicContext();
    if (availability[ctx.page] === false && QUANTITATIVE_PAGES.includes(ctx.page)) {
        const main = document.querySelector(".main-content");
        if (main && !main.querySelector(".public-quant-warning")) {
            const warn = document.createElement("div");
            warn.className = "public-quant-warning notice";
            warn.textContent = t("public.only_qualitative", ONLY_QUALITATIVE_REASON);
            const banner = main.querySelector(".public-mode-banner");
            if (banner) banner.after(warn); else main.prepend(warn);
        }
    }
}

/**
 * Keeps in-page links inside the public view: a link to another public page gets the /public/{team}/ prefix and the
 * event; a link to anything else (scouting forms, admin pages) is blocked, since it would need a sign-in.
 */
export function wirePublicLinks() {
    document.addEventListener("click", (event) => {
        const link = event.target.closest && event.target.closest("a[href]");
        if (!link || link.target === "_blank" || event.defaultPrevented) return;
        const href = link.getAttribute("href");
        if (!href || !href.startsWith("/") || href.startsWith("//") || href.startsWith("/public")) return;
        const url = new URL(href, window.location.origin);
        const page = url.pathname.replace(/^\/+|\/+$/g, "").replace(/\.html$/, "");
        event.preventDefault();
        if (PUBLIC_PAGES.includes(page)) {
            window.location.href = publicPageUrl(page, url.searchParams);
        } else {
            showToastSafe(t("public.page_unavailable", "That page isn't part of the public view."), "info");
        }
    }, true);
}
