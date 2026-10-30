/**
 * Service Notification Socket Module - ObsidianScout
 * Listens on /api/ws/notifications for Slack banners.
 *
 * Only one tab holds the socket (a Web Lock); it shares every message with the other tabs over a
 * BroadcastChannel. A banner shows in the focused tab. When no tab is focused it waits in a short queue in
 * localStorage and shows when the user comes back (if it is still under five minutes old). After a reconnect the
 * socket owner asks the server for anything it missed.
 */

import { request } from '../base/http.js';
import { checkLoginStatus } from '../base/auth.js';
import { isPublicMode } from '../base/public-mode.js';
import { showSlackBanner } from '../components/slack-banner.js';

const LOCK_NAME = "obsidianscout-notification-socket";
const CHANNEL_NAME = "obsidianscout-notifications";
const PENDING_KEY = "obsidianscout.slack.pending";
const SEEN_KEY = "obsidianscout.slack.seen";
const LAST_AT_KEY = "obsidianscout.slack.lastAt";
const MAX_AGE_MS = 5 * 60 * 1000;
const INVALIDATE_EVENT = "obsidianscout:invalidate";

let channel = null;
let started = false;

/**
 * Runs [handler] when the server says data under any of [topics] changed (e.g. "chat", "assignments",
 * "alliance-selection", "banners", "access"). Calls arriving close together are combined into one. Pages keep a slow
 * poll as well: invalidations only come from the node this browser's socket is on. Returns an unsubscribe function.
 */
export function onInvalidate(topics, handler, debounceMs = 300) {
    const wanted = new Set(topics);
    let timer = null;
    const listener = (event) => {
        const changed = (event.detail && event.detail.topics) || [];
        if (!changed.some((t) => wanted.has(t))) return;
        clearTimeout(timer);
        timer = setTimeout(() => handler(changed), debounceMs);
    };
    window.addEventListener(INVALIDATE_EVENT, listener);
    return () => {
        clearTimeout(timer);
        window.removeEventListener(INVALIDATE_EVENT, listener);
    };
}

function readJson(key, fallback) {
    try {
        const raw = localStorage.getItem(key);
        return raw ? JSON.parse(raw) : fallback;
    } catch (e) {
        return fallback;
    }
}

function writeJson(key, value) {
    try {
        localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {}
}

function isFresh(n) {
    const at = Date.parse(n.createdAt || "");
    return !Number.isNaN(at) && Date.now() - at < MAX_AGE_MS;
}

/** Shows queued banners in this tab if it has focus. */
function flush() {
    if (document.visibilityState !== "visible" || !document.hasFocus()) return;
    const pending = readJson(PENDING_KEY, []);
    if (!pending.length) return;
    writeJson(PENDING_KEY, []);
    const seen = new Set(readJson(SEEN_KEY, []));
    pending.filter((n) => !seen.has(n.id) && isFresh(n)).forEach((n) => {
        seen.add(n.id);
        showSlackBanner(n);
    });
    writeJson(SEEN_KEY, Array.from(seen).slice(-200));
}

function receive(n) {
    if (n && n.type === "invalidate" && Array.isArray(n.topics)) {
        window.dispatchEvent(new CustomEvent(INVALIDATE_EVENT, { detail: { topics: n.topics } }));
        return;
    }
    if (!n || n.type !== "slack_notification" || !n.id) return;
    const seen = readJson(SEEN_KEY, []);
    if (seen.includes(n.id)) return;
    const pending = readJson(PENDING_KEY, []).filter((p) => p.id !== n.id && isFresh(p));
    pending.push(n);
    writeJson(PENDING_KEY, pending.slice(-20));
    flush();
}

/** Called in the socket-owning tab for every message: handle here and pass to the other tabs. */
function dispatch(n) {
    if (n && n.createdAt) {
        const last = readJson(LAST_AT_KEY, null);
        if (!last || n.createdAt > last) writeJson(LAST_AT_KEY, n.createdAt);
    }
    if (channel) channel.postMessage(n);
    receive(n);
}

async function catchUp() {
    const since = readJson(LAST_AT_KEY, null) || new Date(Date.now() - MAX_AGE_MS).toISOString();
    try {
        const missed = await request(`/api/slack/notifications?since=${encodeURIComponent(since)}`, { noCache: true, timeoutMs: 5000 });
        if (Array.isArray(missed)) missed.forEach(dispatch);
    } catch (e) {}
}

/** Keeps a socket open until the user is signed out. Resolves only when it gives up. */
function runSocket() {
    return new Promise((resolve) => {
        let backoff = 5000;
        let stopped = false;
        const url = `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/ws/notifications`;

        const connect = () => {
            if (stopped) return;
            let socket;
            try {
                socket = new WebSocket(url);
            } catch (e) {
                setTimeout(connect, backoff);
                return;
            }
            socket.addEventListener("open", () => {
                backoff = 5000;
                catchUp();
            });
            socket.addEventListener("message", (event) => {
                try {
                    dispatch(JSON.parse(event.data));
                } catch (e) {}
            });
            socket.addEventListener("close", async (event) => {
                // 1008 = policy violation: the server closes the socket when there is no session.
                if (event.code === 1008 && !(await checkLoginStatus().catch(() => true))) {
                    stopped = true;
                    resolve();
                    return;
                }
                setTimeout(connect, backoff);
                backoff = Math.min(backoff * 2, 60000);
            });
        };
        connect();
    });
}

export async function initNotificationSocket() {
    if (started || typeof WebSocket === "undefined") return;
    const page = document.body && document.body.dataset.page;
    if (["login", "reset-password", "landing", "docs", "contact"].includes(page)) return;
    if (isPublicMode() || location.pathname.startsWith("/shared/") || location.pathname.startsWith("/public")) return;
    if (!(await checkLoginStatus().catch(() => false))) return;
    started = true;

    if (typeof BroadcastChannel !== "undefined") {
        channel = new BroadcastChannel(CHANNEL_NAME);
        channel.addEventListener("message", (event) => receive(event.data));
    }
    window.addEventListener("focus", flush);
    document.addEventListener("visibilitychange", flush);

    if (navigator.locks && typeof navigator.locks.request === "function") {
        navigator.locks.request(LOCK_NAME, () => runSocket()).catch(() => {});
    } else {
        runSocket();
    }
}

/** Lets other modules (the admin "Send test banner" button) show a banner without the socket. */
export { showSlackBanner };
