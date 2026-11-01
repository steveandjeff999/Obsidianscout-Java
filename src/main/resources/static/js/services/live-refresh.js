/**
 * Service Live-Refresh Module - ObsidianScout
 * Keeps an open page current: when the server says data under the page's topics changed, the page reloads that data
 * in the background (utilities/quiet-refresh.js) and shows it without moving.
 *
 * A burst of changes is one refresh: the page waits for them to settle (settleMs, at most maxGatherMs), then a random
 * time up to spreadMs, because a score sync reaches every page at an event at once. Refreshes also stay minIntervalMs
 * apart (four times that on a slow link) however fast changes arrive. One that's due waits for a moment that won't
 * disturb anyone: until the page can be seen (not a background tab, minimised, or covered by another app; a visible
 * window without focus still updates, so a pit display keeps up), the server can be reached, and the reader has
 * finished typing, selecting, dragging or using a dialog. Nothing is lost while it waits, and with no
 * connection the page simply keeps showing its saved data. Back online, or after the socket reconnects, it catches up
 * after a random wait: a server restart reconnects everyone at once, and they shouldn't all refetch at that moment.
 */

import { onInvalidate } from './notification-socket.js';
import { isLowBandwidth } from '../base/net-quality.js';
import { runQuietly } from '../utilities/quiet-refresh.js';

export const LIVE_REFRESHED_EVENT = "obsidianscout:live-refreshed";
const RECONNECTED_EVENT = "obsidianscout:socket-reconnected";

const DEFAULT_MIN_INTERVAL_MS = 5000;
const DEFAULT_SETTLE_MS = 5000;
const DEFAULT_MAX_GATHER_MS = 15000;
const DEFAULT_SPREAD_MS = 10000;
const SLOW_LINK_FACTOR = 4;
const BUSY_RETRY_MS = 1500;
const CATCH_UP_SPREAD_MS = 20000;
const OPEN_DIALOGS = `body.modal-open, .modal-backdrop.show, [id$="-modal"].active, [id$="-modal"].show, [id$="-modal"].open`;

let pointerHeld = false;
let pointerTracked = false;

function trackPointer() {
    if (pointerTracked) return;
    pointerTracked = true;
    window.addEventListener("pointerdown", () => { pointerHeld = true; }, true);
    window.addEventListener("pointerup", () => { pointerHeld = false; }, true);
    window.addEventListener("pointercancel", () => { pointerHeld = false; }, true);
}

function serverReachable() {
    if (navigator.onLine === false) return false;
    const api = window.Obsidianscout;
    return !(api && typeof api.isServerOnline === "function" && !api.isServerOnline());
}

function userIsBusy() {
    if (pointerHeld || document.querySelector(OPEN_DIALOGS)) return true;
    const active = document.activeElement;
    if (active && (active.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(active.tagName))) {
        const type = (active.type || "").toLowerCase();
        if (!["checkbox", "radio", "button", "submit"].includes(type)) return true;
    }
    const selection = typeof window.getSelection === "function" ? window.getSelection() : null;
    return Boolean(selection && !selection.isCollapsed);
}

/**
 * Calls refresh when data under any of [topics] changes. refresh reloads the page's data and re-renders it, with the
 * page's filters and selection left alone; a page's own load function is fine if it stays off the screen while
 * Obsidianscout.isQuietRefresh() is true (showLoadingSpinner and showRetryButton already do).
 *
 * options.eventKey: returns the event the page is showing, when it can differ from the team's active event.
 * options.isBusy: returns true while something on this page shouldn't be replaced.
 * options.catchUpSpreadMs: the longest random wait before catching up after a reconnect.
 * options.settleMs, maxGatherMs, spreadMs: how long a burst of changes is gathered, and the random wait after it.
 * Returns a function that stops the updates.
 */
export function liveRefresh(topics, refresh, options = {}) {
    const {
        minIntervalMs = DEFAULT_MIN_INTERVAL_MS,
        eventKey,
        isBusy,
        catchUpSpreadMs = CATCH_UP_SPREAD_MS,
        settleMs = DEFAULT_SETTLE_MS,
        maxGatherMs = DEFAULT_MAX_GATHER_MS,
        spreadMs = DEFAULT_SPREAD_MS
    } = options;
    let timer = null;
    let catchUpTimer = null;
    let running = false;
    let dirty = false;
    let lastRun = 0;
    let gatherStart = null;
    let lastChange = 0;
    let spread = 0;

    const schedule = () => {
        if (running || !dirty) return;
        const interval = minIntervalMs * (isLowBandwidth() ? SLOW_LINK_FACTOR : 1);
        const gathered = Math.min(lastChange + settleMs, gatherStart + maxGatherMs) + spread;
        clearTimeout(timer);
        timer = setTimeout(run, Math.max(0, gathered - Date.now(), lastRun + interval - Date.now()));
    };

    const run = async () => {
        timer = null;
        // The visibility and connection listeners below pick these back up.
        if (document.hidden || !serverReachable()) return;
        if (userIsBusy() || (isBusy && isBusy())) {
            timer = setTimeout(run, BUSY_RETRY_MS);
            return;
        }
        dirty = false;
        gatherStart = null;
        running = true;
        lastRun = Date.now();
        try {
            await runQuietly(refresh);
            window.dispatchEvent(new CustomEvent(LIVE_REFRESHED_EVENT));
        } catch (error) {
            // The page keeps what it's showing; the next change tries again.
            console.warn("[Live Refresh] Update failed", error);
        } finally {
            running = false;
            schedule();
        }
    };

    const markDirty = () => {
        const now = Date.now();
        if (gatherStart === null) {
            gatherStart = now;
            spread = Math.random() * spreadMs;
        }
        lastChange = now;
        dirty = true;
        schedule();
    };
    const catchUp = () => {
        if (catchUpTimer !== null) return;
        catchUpTimer = setTimeout(() => {
            catchUpTimer = null;
            // This wait was the random one, and there's no burst to gather.
            if (gatherStart === null) {
                gatherStart = 0;
                lastChange = 0;
                spread = 0;
            }
            dirty = true;
            schedule();
        }, Math.random() * catchUpSpreadMs);
    };
    const onConnectionChanged = (event) => {
        if (event.detail && event.detail.online) catchUp();
    };

    trackPointer();
    const stopListening = onInvalidate(topics, markDirty, 300, eventKey);
    document.addEventListener("visibilitychange", schedule);
    window.addEventListener("obsidianscout:connection-changed", onConnectionChanged);
    window.addEventListener(RECONNECTED_EVENT, catchUp);

    return () => {
        stopListening();
        clearTimeout(timer);
        clearTimeout(catchUpTimer);
        timer = null;
        catchUpTimer = null;
        dirty = false;
        document.removeEventListener("visibilitychange", schedule);
        window.removeEventListener("obsidianscout:connection-changed", onConnectionChanged);
        window.removeEventListener(RECONNECTED_EVENT, catchUp);
    };
}
