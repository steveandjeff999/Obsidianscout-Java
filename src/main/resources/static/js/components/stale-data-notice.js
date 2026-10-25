/**
 * Component Stale-Data-Notice Module - ObsidianScout
 * When a page has to show saved copies of server data because the server can't be reached, a small note says so
 * and how old the copy is, so nobody mistakes it for live numbers.
 */

import { faIcon } from '../utilities/fa-icons.js';

export const SERVED_FROM_CACHE_EVENT = "obsidianscout:served-from-cache";

const PILL_ID = "stale-data-pill";
// Session, settings and config requests happen on every page and aren't the data people are reading.
const IGNORED = [/^\/api\/auth\//, /^\/api\/settings/, /^\/api\/(pit-|qual-)?config/, /^\/api\/version/, /^\/api\/banners/,
    /^\/api\/chat/, /^\/api\/alliances\/invites/, /^\/api\/push/, /^\/api\/theme/];
// Scouting forms have their own offline banner.
const SKIP_PAGES = ["scout", "pit-scout", "qual-scout", "prescout-scout", "prescout-pit", "prescout-qual", "login", "landing", "docs", "contact"];

let _oldest = null;      // oldest known timestamp among copies shown
let _unknownAge = false; // a copy whose age isn't known
let _dismissed = false;
let _started = false;

function formatTime(ts) {
    const d = new Date(ts);
    const sameDay = d.toDateString() === new Date().toDateString();
    const time = d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    return sameDay ? time : `${d.toLocaleDateString([], { month: "short", day: "numeric" })} ${time}`;
}

function render(state = "offline") {
    if (_dismissed || !document.body) return;
    let pill = document.getElementById(PILL_ID);
    if (!pill) {
        pill = document.createElement("div");
        pill.id = PILL_ID;
        pill.className = "stale-data-pill";
        pill.setAttribute("role", "status");
        pill.setAttribute("aria-live", "polite");
        pill.addEventListener("click", (e) => {
            const action = e.target.closest("[data-stale-action]");
            if (!action) return;
            if (action.dataset.staleAction === "refresh") window.location.reload();
            else {
                _dismissed = true;
                pill.remove();
            }
        });
        document.body.appendChild(pill);
    }
    let text;
    if (state === "online") {
        text = "Back online. Refresh to see the latest data.";
    } else if (_oldest && !_unknownAge) {
        text = `Can't reach the server. Showing data saved ${formatTime(_oldest)}.`;
    } else if (_oldest) {
        text = `Can't reach the server. Showing saved data (some from ${formatTime(_oldest)}).`;
    } else {
        text = "Can't reach the server. Showing saved data, which may be out of date.";
    }
    pill.innerHTML = `${faIcon(state === "online" ? "arrows-rotate" : "clock", 14)}<span></span>
        ${state === "online" ? `<button type="button" data-stale-action="refresh">Refresh</button>` : ""}
        <button type="button" data-stale-action="dismiss" aria-label="Dismiss">${faIcon("xmark", 12)}</button>`;
    pill.querySelector("span").textContent = text;
}

export function initStaleDataNotice() {
    if (_started || typeof window === "undefined") return;
    _started = true;
    window.addEventListener(SERVED_FROM_CACHE_EVENT, (e) => {
        const page = document.body && document.body.dataset.page;
        if (SKIP_PAGES.includes(page)) return;
        const detail = e.detail || {};
        const path = String(detail.path || "").split("?")[0];
        if (!path || IGNORED.some((re) => re.test(path))) return;
        const ts = Number(detail.timestamp) || 0;
        // Copies read back from localStorage carry the read time, not the save time.
        if (!ts || Date.now() - ts < 3000) _unknownAge = true;
        else if (!_oldest || ts < _oldest) _oldest = ts;
        render("offline");
    });
    window.addEventListener("obsidianscout:connection-changed", (e) => {
        if (document.getElementById(PILL_ID) && e.detail && e.detail.online) render("online");
    });
}

/** Called by the HTTP layer each time it answers a request from a saved copy instead of the server. */
export function noteServedFromCache(path, timestamp) {
    try {
        if (typeof window !== "undefined" && typeof window.dispatchEvent === "function") {
            window.dispatchEvent(new CustomEvent(SERVED_FROM_CACHE_EVENT, { detail: { path, timestamp } }));
        }
    } catch (e) { /* ignore */ }
}
