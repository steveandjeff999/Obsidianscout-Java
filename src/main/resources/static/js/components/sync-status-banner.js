/**
 * Component Sync Status Banner Module - ObsidianScout
 * Shows, on every scouting page, how many entries are saved on this device only
 * and have not reached the server yet. Also exposes helpers for per-selection
 * "local only" chips and consistent save-result toasts.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';
import { showToast } from './toast.js';
import { isServerOnline } from './connection-widget.js';
import { faIcon } from '../utilities/fa-icons.js';
import {
    CACHE_CONFIGS,
    PENDING_CHANGED_EVENT,
    SYNC_PROGRESS_EVENT,
    getPendingEntries,
    getPendingSummary,
    getOfflineSyncProgress,
    isOfflineSyncInProgress,
    syncOfflineEntries,
    notifyPendingChanged
} from '../services/offline-sync.js';

export const SCOUTING_PAGES = ["scout", "pit-scout", "qual-scout", "prescout-scout", "prescout-pit", "prescout-qual"];

const ORIGIN_MARKER_KEY = "obsidianscout:last_origin";
const ORIGIN_NOTE_DISMISSED_KEY = "obsidianscout:origin_note_dismissed";

let _host = null;
let _expanded = false;
let _lastTotal = null;
let _showAllClearUntil = 0;
let _allClearTimer = null;
let _listenersWired = false;

function escapeHtml(value) {
    return String(value == null ? "" : value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function plural(n, one, many) {
    return `${n} ${n === 1 ? one : many}`;
}

function formatSavedAt(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

function describeEntry(entry) {
    const d = entry.data || {};
    const parts = [];
    const team = d.targetTeamNumber || d.teamNumber;
    if (team) parts.push(`Team ${team}`);
    if (d.matchKey) {
        const short = String(d.matchKey).split("_").pop();
        parts.push(short.toUpperCase());
    } else if (d.matchNumber) {
        parts.push(`Match ${d.matchNumber}`);
    }
    return parts.join(" · ") || "Entry";
}

function breakdownText(byType) {
    return Object.keys(byType)
        .map(type => `${byType[type]} ${(CACHE_CONFIGS[type] && CACHE_CONFIGS[type].label) || type}`)
        .join(", ");
}

function ensureHost() {
    if (_host && document.body.contains(_host)) return _host;
    const main = document.querySelector(".main-content");
    if (!main) return null;
    // "banner-container" keeps the page wrappers from moving it; "sync-status-host" keeps the
    // server banner renderer from treating it as its own container.
    _host = document.createElement("div");
    _host.className = "banner-container sync-status-host";
    _host.setAttribute("role", "status");
    _host.setAttribute("aria-live", "polite");
    const firstNonBanner = Array.from(main.children).find(c => !c.classList.contains("banner-container"));
    main.insertBefore(_host, firstNonBanner || null);

    _host.addEventListener("click", (e) => {
        const btn = e.target.closest("[data-sync-action]");
        if (!btn) return;
        const action = btn.dataset.syncAction;
        if (action === "sync") {
            syncOfflineEntries();
        } else if (action === "toggle") {
            _expanded = !_expanded;
            renderSyncStatusBanner();
        } else if (action === "dismiss-origin") {
            safeSetItem(ORIGIN_NOTE_DISMISSED_KEY, window.location.origin);
            renderSyncStatusBanner();
        }
    });
    return _host;
}

function originNoteHtml() {
    let previous = null;
    try {
        previous = safeGetItem(ORIGIN_MARKER_KEY);
    } catch (e) { /* ignore */ }
    const current = window.location.origin;
    // Saved entries live in this browser per address, so a different address shows a different queue.
    if (!previous || previous === current || safeGetItem(ORIGIN_NOTE_DISMISSED_KEY) === current) return "";
    return `
        <div class="banner-item banner-info sync-status-origin">
            <div class="banner-body">
                <div class="banner-message">${faIcon("triangle-exclamation", 15)}
                    You last scouted at <strong>${escapeHtml(previous)}</strong>. Entries saved there offline only show up at that address.
                    Open it once while online so they upload.</div>
            </div>
            <button class="btn-banner-close" type="button" data-sync-action="dismiss-origin" aria-label="Dismiss">${faIcon("xmark", 14)}</button>
        </div>`;
}

export function renderSyncStatusBanner() {
    const host = ensureHost();
    if (!host) return;

    const summary = getPendingSummary();
    const online = isServerOnline();
    const syncing = isOfflineSyncInProgress();
    const progress = getOfflineSyncProgress();

    if (_lastTotal !== null && _lastTotal > 0 && summary.total === 0 && summary.failed === 0) {
        _showAllClearUntil = Date.now() + 5000;
        clearTimeout(_allClearTimer);
        _allClearTimer = setTimeout(renderSyncStatusBanner, 5100);
    }
    _lastTotal = summary.total;
    if (summary.total === 0) _expanded = false;

    let html = originNoteHtml();

    if (syncing && progress && progress.total > 0) {
        html += `
            <div class="banner-item banner-info sync-status-item">
                <div class="banner-body">
                    <div class="banner-message">${faIcon("arrows-rotate", 15, "sync-status-spin")}
                        Uploading saved entries to the server&hellip; ${progress.done} of ${progress.total}</div>
                </div>
            </div>`;
    } else if (summary.total > 0) {
        const tone = online ? "banner-warning" : "banner-danger";
        const lead = online
            ? `<strong>${plural(summary.total, "entry is", "entries are")} saved on this device only</strong> and not on the server yet`
            : `<strong>Offline.</strong> ${plural(summary.total, "entry is", "entries are")} saved on this device only. They upload automatically when the connection returns`;
        const rows = _expanded
            ? `<ul class="sync-status-list">${getPendingEntries().map(entry => `
                    <li><span class="sync-status-type">${escapeHtml(entry.label)}</span>
                        <span>${escapeHtml(describeEntry(entry))}</span>
                        <span class="sync-status-time">${escapeHtml(formatSavedAt(entry.item && entry.item.createdAt))}</span></li>`).join("")}
               </ul>`
            : "";
        html += `
            <div class="banner-item ${tone} sync-status-item">
                <div class="banner-body">
                    <div class="banner-message">${faIcon("mobile-screen-button", 15)} ${lead} (${escapeHtml(breakdownText(summary.byType))}).</div>
                    ${rows}
                    <div class="sync-status-actions">
                        ${online ? `<button type="button" class="btn" data-sync-action="sync">${faIcon("cloud-arrow-up", 14)} Sync now</button>` : ""}
                        <button type="button" class="btn ghost" data-sync-action="toggle" aria-expanded="${_expanded}">
                            ${faIcon("chevron-down", 12, _expanded ? "sync-status-chevron-open" : "")} ${_expanded ? "Hide" : "Show"} entries</button>
                    </div>
                </div>
            </div>`;
    } else if (Date.now() < _showAllClearUntil) {
        html += `
            <div class="banner-item banner-success sync-status-item">
                <div class="banner-body">
                    <div class="banner-message">${faIcon("circle-check", 15)} All saved entries are on the server.</div>
                </div>
            </div>`;
    }

    if (summary.failed > 0) {
        html += `
            <div class="banner-item banner-danger sync-status-item">
                <div class="banner-body">
                    <div class="banner-message">${faIcon("circle-xmark", 15)}
                        ${plural(summary.failed, "entry was", "entries were")} rejected by the server and ${summary.failed === 1 ? "is" : "are"} not uploaded.
                        <a href="/cache-manager">Review in Cache Manager</a></div>
                </div>
            </div>`;
    }

    if (host.dataset.renderedHtml !== html) {
        host.dataset.renderedHtml = html;
        host.innerHTML = html;
    }
    host.style.display = html.trim() ? "" : "none";
}

/** Mounts the banner when the current page is a scouting page. Safe to call more than once. */
export function initSyncStatusBanner() {
    const page = document.body && document.body.dataset.page;
    if (!SCOUTING_PAGES.includes(page)) return;

    renderSyncStatusBanner();
    if (_listenersWired) return;
    _listenersWired = true;

    const rerender = () => renderSyncStatusBanner();
    window.addEventListener(PENDING_CHANGED_EVENT, rerender);
    window.addEventListener(SYNC_PROGRESS_EVENT, rerender);
    window.addEventListener("obsidianscout:connection-changed", rerender);
    window.addEventListener("obsidianscout:offline-entries-synced", rerender);
    window.addEventListener("online", rerender);
    window.addEventListener("offline", rerender);
    // Another tab saved or uploaded entries.
    window.addEventListener("storage", (e) => {
        if (!e.key || e.key.startsWith("pending_") || e.key === "failed_offline_entries") rerender();
    });

    // Remember the address used while online, for the "different address" note.
    window.addEventListener("obsidianscout:offline-entries-synced", () => {
        safeSetItem(ORIGIN_MARKER_KEY, window.location.origin);
    });
    if (isServerOnline() && getPendingSummary().total === 0) {
        safeSetItem(ORIGIN_MARKER_KEY, window.location.origin);
    }
}

/**
 * True when an entry for this selection exists only in this device's offline queue.
 * `type` is a CACHE_CONFIGS key such as "match-scouting".
 */
export function isPendingLocally(type, { eventKey, teamNumber, matchKey } = {}) {
    const config = CACHE_CONFIGS[type];
    if (!config) return false;
    return getPendingEntries(eventKey).some(entry => {
        if (entry.type !== type) return false;
        const d = entry.data || {};
        const team = d.targetTeamNumber || d.teamNumber;
        if (teamNumber != null && String(team) !== String(teamNumber)) return false;
        if (config.hasMatch && matchKey && String(d.matchKey) !== String(matchKey)) return false;
        return true;
    });
}

/**
 * Shows or hides a "Saved on this device only" chip next to a page's selectors.
 * `anchor` is the element the chip is placed after.
 */
export function updateLocalOnlyChip(anchor, type, selection) {
    if (!anchor || !anchor.parentNode) return;
    let chip = anchor.parentNode.querySelector(".local-only-chip");
    const show = isPendingLocally(type, selection);
    if (!show) {
        if (chip) chip.remove();
        return;
    }
    if (!chip) {
        chip = document.createElement("div");
        chip.className = "local-only-chip";
        anchor.after(chip);
    }
    chip.innerHTML = `${faIcon("mobile-screen-button", 13)} This selection is saved on this device only and not on the server yet`;
}

/** One wording for every scouting page: where did that save actually go? */
export function showSaveResult(where, detail = "") {
    const suffix = detail ? ` (${detail})` : "";
    if (where === "server") {
        showToast(`Uploaded to server${suffix}`, "success");
    } else {
        showToast(`Saved on this device only, not on the server yet${suffix}`, "warning");
    }
    notifyPendingChanged();
}
