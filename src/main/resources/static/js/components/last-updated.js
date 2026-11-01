/**
 * Component Last-Updated Module - ObsidianScout
 * A line at the bottom of each page saying when its data last came from the server. An answer of "nothing changed"
 * counts, because the page is still current as of then. Showing a saved copy says so instead.
 *
 * The line is drawn by .main-content::after from an attribute, so pages that rebuild or move their content can't lose it.
 */

import { IGNORED_DATA_PATHS, SERVED_FROM_CACHE_EVENT } from './stale-data-notice.js';
import { t } from '../base/i18n.js';

export const DATA_FETCHED_EVENT = "obsidianscout:data-fetched";

const ATTRIBUTE = "data-last-updated";
const SKIP_PAGES = ["login", "landing", "docs", "contact", "reset-password"];
const SETTINGS_PATH = /^\/api\/(settings|(pit-|qual-)?config)/;
// Requests every page makes in the background, unless they're what the page is about.
const OWN_DATA = { chat: /^\/api\/chat/, banners: /^\/api\/banners/ };

let fetchedAt = null;      // the page's data, from the server
let settingsAt = null;     // for pages that only show settings
let savedAt = null;        // oldest saved copy on show
let pageLoadedAt = null;
let started = false;

function isOffline() {
    const api = window.Obsidianscout;
    return navigator.onLine === false || Boolean(api && typeof api.isServerOnline === "function" && !api.isServerOnline());
}

export function formatUpdatedTime(ts, now = new Date()) {
    const d = new Date(ts);
    const time = d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit", second: "2-digit" });
    return d.toDateString() === now.toDateString() ? time : `${d.toLocaleDateString([], { month: "short", day: "numeric" })} ${time}`;
}

function label() {
    const updated = t("common.last_updated", "Last updated");
    const saved = t("common.saved_data_from", "Showing saved data from");
    if (savedAt && (!fetchedAt || savedAt > fetchedAt)) return `${saved} ${formatUpdatedTime(savedAt)}`;
    const at = fetchedAt || settingsAt;
    if (at) return `${updated} ${formatUpdatedTime(at)}`;
    if (!pageLoadedAt) return "";
    if (isOffline()) return t("common.showing_saved_data", "Showing saved data");
    return `${updated} ${formatUpdatedTime(pageLoadedAt)}`;
}

function render() {
    const main = document.querySelector(".main-content");
    if (main) main.setAttribute(ATTRIBUTE, label());
}

function isPageData(path) {
    const page = document.body && document.body.dataset.page;
    const own = OWN_DATA[page];
    return Boolean(own && own.test(path)) || !IGNORED_DATA_PATHS.some((re) => re.test(path));
}

export function initLastUpdated() {
    if (started || typeof window === "undefined") return;
    started = true;
    const page = document.body && document.body.dataset.page;
    if (SKIP_PAGES.includes(page)) return;
    render();

    window.addEventListener(DATA_FETCHED_EVENT, (e) => {
        const path = String((e.detail && e.detail.path) || "").split("?")[0];
        if (isPageData(path)) {
            fetchedAt = Date.now();
            savedAt = null;
        } else if (SETTINGS_PATH.test(path)) {
            // Settings are re-read in the background on every page, so only the first read counts.
            if (settingsAt) return;
            settingsAt = Date.now();
        } else {
            return;
        }
        render();
    });
    window.addEventListener(SERVED_FROM_CACHE_EVENT, (e) => {
        const detail = e.detail || {};
        const path = String(detail.path || "").split("?")[0];
        const ts = Number(detail.timestamp) || 0;
        if (!ts || !isPageData(path)) return;
        if (!savedAt || ts < savedAt) savedAt = ts;
        render();
    });
    window.addEventListener("obsidianscout:languagechange", render);

    const pageLoaded = () => {
        pageLoadedAt = Date.now();
        render();
    };
    if (document.readyState === "complete") pageLoaded();
    else window.addEventListener("load", pageLoaded, { once: true });
}
