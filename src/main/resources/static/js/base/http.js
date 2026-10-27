/**
 * Base HTTP Module - ObsidianScout
 * Fetch wrapper, CSRF token handling, timeout management, offline cache fallbacks, and button loading states.
 */

import { safeGetItem, safeSetItem, safeRemoveItem } from './storage.js';
import {
    getHttpCache,
    getHttpCacheSync,
    setHttpCache,
    removeHttpCache,
    purgeScoutingHttpCache,
    isScoutingDataPath
} from './idb-cache.js';

import { isLowBandwidth, recordTransfer, recordStall } from './net-quality.js';
import { schedule, priorityFor } from './request-scheduler.js';
import { serveFromEntryStore, noteEntryWrite, clearEntryStore } from '../services/entry-store.js';
import { isPublicMode, publicRequest } from './public-mode.js';

export { isScoutingDataPath };

/** How long a request may go without any progress (no headers yet, or no new body bytes) before it is dropped. */
export const DEFAULT_REQUEST_TIMEOUT_MS = 20000;
/**
 * Saves that can fall back to the offline queue give up sooner: a scout at an event is better off with the entry
 * saved on the device in a few seconds than watching a spinner. Large bodies (photos) get the normal allowance.
 */
export const QUEUEABLE_REQUEST_TIMEOUT_MS = 8000;
const QUEUEABLE_LARGE_BODY_BYTES = 256 * 1024;
/** Upload speed assumed when allowing time to send a request body (about 200 kbit/s, a poor venue link). */
const SLOW_UPLOAD_BYTES_PER_SEC = 25 * 1024;
/** On a slow link a single chunk can legitimately take this long. */
const LOW_BW_MIN_IDLE_MS = 30000;
/** Hard cap for one request, however steadily it is progressing. */
const MAX_REQUEST_MS = 10 * 60 * 1000;
/** JSON bodies at least this large are gzipped before upload. */
const GZIP_MIN_BYTES = 1024;
let gzipUploadsDisabled = false;

function nowMs() {
    return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
}

async function gzipText(text) {
    if (gzipUploadsDisabled || typeof CompressionStream === 'undefined') return null;
    try {
        const stream = new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"));
        return new Uint8Array(await new Response(stream).arrayBuffer());
    } catch (e) {
        return null;
    }
}

/**
 * fetch() that gives up only when the transfer stops moving: no response headers within `idleMs`, or no new body
 * bytes for `idleMs`. A large download on a slow link keeps going as long as bytes keep arriving.
 * Resolves to `{ response, text }`; rejects with an AbortError (`stalled: true` when it timed out).
 */
async function fetchWithProgress(url, init, idleMs, externalSignal, firstResponseMs = idleMs) {
    const controller = new AbortController();
    let stalled = false;
    let idleTimer = null;
    const arm = (ms = idleMs) => {
        if (idleTimer) clearTimeout(idleTimer);
        idleTimer = setTimeout(() => { stalled = true; controller.abort(); }, ms);
    };
    const totalTimer = setTimeout(() => { stalled = true; controller.abort(); }, MAX_REQUEST_MS);
    const onExternalAbort = () => controller.abort();
    if (externalSignal) {
        if (externalSignal.aborted) controller.abort();
        else externalSignal.addEventListener("abort", onExternalAbort);
    }

    const started = nowMs();
    // Until the response starts, the body may still be uploading: that wait gets the upload allowance too.
    arm(firstResponseMs);
    try {
        const response = await fetch(url, { ...init, signal: controller.signal });
        const headersAt = nowMs();
        arm();
        let text = "";
        if (response.status !== 204 && response.status !== 304) {
            if (response.body && typeof response.body.getReader === 'function' && typeof TextDecoder !== 'undefined') {
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                for (;;) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    text += decoder.decode(value, { stream: true });
                    arm();
                }
                text += decoder.decode();
            } else {
                text = await response.text();
            }
        }
        const encoding = (response.headers && response.headers.get && response.headers.get("Content-Encoding")) || "";
        recordTransfer({
            url,
            decodedBytes: text.length,
            gzipped: /gzip|deflate|br/i.test(encoding) || text.length > 4096,
            ttfbMs: headersAt - started,
            downloadMs: nowMs() - headersAt
        });
        return { response, text };
    } catch (error) {
        if (stalled) {
            recordStall(nowMs() - started);
            const err = new Error("Request stalled");
            err.name = "AbortError";
            err.stalled = true;
            throw err;
        }
        throw error;
    } finally {
        if (idleTimer) clearTimeout(idleTimer);
        clearTimeout(totalTimer);
        if (externalSignal) externalSignal.removeEventListener("abort", onExternalAbort);
    }
}

export function getOrCreateDeviceId() {
    let deviceId = safeGetItem("obsidian_device_id");
    if (!deviceId) {
        if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
            deviceId = crypto.randomUUID();
        } else {
            deviceId = 'dev-' + Math.random().toString(36).substring(2, 15) + Date.now().toString(36);
        }
        safeSetItem("obsidian_device_id", deviceId);
    }
    return deviceId;
}

export function getCsrfToken() {
    try {
        if (typeof document === 'undefined') return null;
        const meta = document.querySelector('meta[name="csrf-token"]');
        if (meta && meta.content) return meta.content;
        const value = `; ${document.cookie}`;
        const parts = value.split(`; XSRF-TOKEN=`);
        if (parts.length === 2) return parts.pop().split(';').shift();
    } catch (e) {}
    return null;
}

/**
 * True when a request failed because the server couldn't be reached in time (no connection, stalled transfer, or a
 * proxy saying the server is down), as opposed to the server refusing it. Saves that fail this way belong in the
 * offline queue; the server ignores an identical entry sent twice.
 */
export function isNetworkError(error) {
    if (!error) return false;
    if (error.network) return true;
    if (error.status) return false;
    if (typeof navigator !== 'undefined' && !navigator.onLine) return true;
    const message = String(error.message || "");
    return error.name === "TypeError" || /failed to fetch|networkerror|load failed|network request failed/i.test(message);
}

function networkError(message, cause) {
    const err = new Error(message);
    err.network = true;
    if (cause) err.cause = cause;
    return err;
}

/** Pages where a scout may be partway through a form. A lapsed sign-in there never navigates away on its own. */
const FORM_PAGES = ["scout", "pit-scout", "qual-scout", "prescout-scout", "prescout-pit", "prescout-qual"];
export const SESSION_EXPIRED_EVENT = "obsidianscout:session-expired";

function onFormPage() {
    try {
        const page = typeof document !== 'undefined' && document.body && document.body.getAttribute("data-page");
        return !!page && FORM_PAGES.includes(page);
    } catch (e) {
        return false;
    }
}

function announceSessionExpired(path) {
    try {
        if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
            window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT, { detail: { path } }));
        }
    } catch (e) { /* ignore */ }
}

/** Lets the page say it's showing a saved copy (components/stale-data-notice.js listens). */
function announceServedFromCache(path, record) {
    try {
        if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function') {
            window.dispatchEvent(new CustomEvent("obsidianscout:served-from-cache", {
                detail: { path, timestamp: record && record.timestamp }
            }));
        }
    } catch (e) { /* ignore */ }
}

function serverKnownOffline() {
    try {
        return typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.isServerOnline === 'function' &&
            !window.Obsidianscout.isServerOnline();
    } catch (e) {
        return false;
    }
}

export function safeParse(text) {
    try {
        return JSON.parse(text);
    } catch (error) {
        return text;
    }
}

export function setButtonLoading(button, isLoading, loadingTextOrOptions = null) {
    if (!button) return;
    const btn = typeof button === 'string' ? document.getElementById(button) || document.querySelector(button) : button;
    if (!btn) return;

    let loadingText = null;
    let useDots = false;

    if (typeof loadingTextOrOptions === 'string') {
        loadingText = loadingTextOrOptions;
    } else if (loadingTextOrOptions && typeof loadingTextOrOptions === 'object') {
        loadingText = loadingTextOrOptions.text || null;
        useDots = !!loadingTextOrOptions.dots;
    }

    if (isLoading) {
        if (btn.dataset.loading === "true") return;

        if (btn.dataset.originalHtml === undefined) {
            btn.dataset.originalHtml = btn.innerHTML;
        }
        if (btn.dataset.originalDisabled === undefined) {
            btn.dataset.originalDisabled = btn.disabled ? "true" : "false";
        }

        const currentWidth = btn.getBoundingClientRect().width;
        if (currentWidth > 0 && !btn.style.minWidth) {
            btn.dataset.originalMinWidth = btn.style.minWidth || "";
            btn.style.minWidth = `${currentWidth}px`;
        }

        btn.disabled = true;
        btn.setAttribute("data-loading", "true");
        btn.classList.add("is-loading");

        const indicatorHtml = useDots
            ? `<span class="btn-dots" aria-hidden="true"><span></span><span></span><span></span></span>`
            : `<span class="btn-spinner" aria-hidden="true"></span>`;

        if (loadingText) {
            btn.innerHTML = `${indicatorHtml}<span>${loadingText}</span>`;
        } else {
            btn.innerHTML = `${indicatorHtml}${btn.dataset.originalHtml}`;
        }
    } else {
        if (btn.dataset.originalHtml !== undefined) {
            btn.innerHTML = btn.dataset.originalHtml;
            delete btn.dataset.originalHtml;
        }

        if (btn.dataset.originalDisabled !== undefined) {
            btn.disabled = btn.dataset.originalDisabled === "true";
            delete btn.dataset.originalDisabled;
        } else {
            btn.disabled = false;
        }

        if (btn.dataset.originalMinWidth !== undefined) {
            btn.style.minWidth = btn.dataset.originalMinWidth;
            delete btn.dataset.originalMinWidth;
        }

        btn.removeAttribute("data-loading");
        btn.classList.remove("is-loading");
    }
}

export async function withButtonLoading(button, asyncFn, loadingTextOrOptions = null) {
    setButtonLoading(button, true, loadingTextOrOptions);
    try {
        return await asyncFn();
    } finally {
        setButtonLoading(button, false);
    }
}

export function getActiveUserRole() {
    try {
        const meText = safeGetItem("cache:/api/auth/me");
        if (meText) {
            const parsed = JSON.parse(meText);
            const user = parsed.user || parsed;
            if (user && user.role) {
                return String(user.role).toUpperCase();
            }
        }
    } catch (e) {}
    return null;
}

export function canRoleCacheScouting(role) {
    return role === "SUPERADMIN" || role === "ADMIN" || role === "ANALYTICS";
}

export function getActiveEventKey() {
    try {
        const settingsText = safeGetItem("cache:/api/settings");
        if (settingsText) {
            const parsed = JSON.parse(settingsText);
            const settings = parsed.settings || parsed;
            if (settings) {
                const code = (settings.eventCode || "").trim();
                if (code) {
                    return `${settings.year}${code}`.toLowerCase();
                }
                return (settings.eventKey || "").trim().toLowerCase();
            }
        }
    } catch (e) {}
    return "";
}

export function getCachedData(path) {
    // Saved responses are the signed-in user's own team; never show them on another team's public view.
    if (isPublicMode()) return null;
    try {
        const cached = getHttpCacheSync(path);
        if (cached && cached.text !== null) {
            return safeParse(cached.text);
        }
        const text = safeGetItem("cache:" + path);
        if (text !== null) {
            return safeParse(text);
        }
    } catch (e) {}
    return null;
}

export async function purgeScoutingCache() {
    try {
        await clearEntryStore();
    } catch (e) {
        console.warn("[Entry Store] Failed to clear:", e);
    }
    try {
        await purgeScoutingHttpCache();
    } catch (e) {
        console.warn("[HTTP Cache] Failed to purge scouting cache:", e);
    }
}

export async function request(path, options = {}) {
    // Public data views read another team's data through /api/public/...: no session, caches or offline stores.
    if (isPublicMode()) {
        if (options.button) setButtonLoading(options.button, true, options.loadingText);
        try {
            return await publicRequest(path, options);
        } finally {
            if (options.button) setButtonLoading(options.button, false);
        }
    }
    const method = options.method || "GET";
    let isPublicPage = false;

    if (options.button) {
        setButtonLoading(options.button, true, options.loadingText);
    }

    try {
        if (typeof document !== 'undefined') {
            const dataPage = (document.body && document.body.getAttribute("data-page")) || "";
            const pathname = (typeof window !== 'undefined' && window.location && window.location.pathname) || "";
            const cleanPath = pathname.replace(/\/+$/, "");
            isPublicPage = dataPage === "login" ||
                           dataPage === "landing" ||
                           dataPage === "docs" ||
                           dataPage === "contact" ||
                           cleanPath === "" ||
                           cleanPath === "/docs" ||
                           pathname.startsWith("/docs/") ||
                           cleanPath === "/contact" ||
                           pathname.startsWith("/contact/") ||
                           cleanPath === "/login" ||
                           pathname.startsWith("/login/");
        }
    } catch (e) {}

    // Scouting lists and analytics come from the delta-synced entry store when the link is slow or down.
    if (method === "GET" && !options.skipEntryStore) {
        try {
            const served = await serveFromEntryStore(path);
            if (served !== undefined) {
                if (options.button) setButtonLoading(options.button, false);
                return served;
            }
        } catch (e) {
            console.warn("[Entry Store] Falling back to the network for", path, e);
        }
    }

    // Saves that can wait in the offline queue skip the network when the server is known to be unreachable, or the
    // link is too slow to wait on, instead of holding the scout on a spinner. The queue uploads them in the background.
    if (method !== "GET" && options.queueable && (serverKnownOffline() || isLowBandwidth())) {
        if (options.button) setButtonLoading(options.button, false);
        throw networkError(serverKnownOffline() ? "Server unreachable" : "Slow connection");
    }

    // No connection, or the server is already known to be unreachable: answer from the saved copy straight away
    // instead of making every page wait on a request that will fail. The connection check flips this back as soon
    // as the server answers again.
    if (method === "GET" && !options.noCache && (!navigator.onLine || serverKnownOffline())) {
        const cached = await getHttpCache(path);
        if (cached && cached.text !== null) {
            console.log("[Offline Cache] Offline mode: Serving cached response for:", path);
            announceServedFromCache(path, cached);
            return safeParse(cached.text);
        }
    }

    let defaultIdleMs = DEFAULT_REQUEST_TIMEOUT_MS;
    let bodyBytes = 0;
    if (method !== "GET") {
        try {
            if (options.json !== undefined) bodyBytes = JSON.stringify(options.json).length;
            else if (typeof options.body === "string") bodyBytes = options.body.length;
            else if (options.body && typeof options.body.size === "number") bodyBytes = options.body.size;
        } catch (e) { bodyBytes = 0; }
        if (options.queueable && bodyBytes < QUEUEABLE_LARGE_BODY_BYTES) defaultIdleMs = QUEUEABLE_REQUEST_TIMEOUT_MS;
    }
    const idleMs = Math.max(options.timeoutMs || defaultIdleMs, isLowBandwidth() ? LOW_BW_MIN_IDLE_MS : 0);
    // fetch() can't report upload progress, so a large body (photos) gets time to send at a slow link's pace before
    // the wait for a reply counts as stalled. Otherwise it would time out on every retry and never arrive.
    const firstResponseMs = idleMs + Math.round((bodyBytes / SLOW_UPLOAD_BYTES_PER_SEC) * 1000);
    const priority = options.priority !== undefined ? options.priority : priorityFor(method, path);
    const send = (init) => schedule(priority, () => fetchWithProgress(path, init, idleMs, options.signal, firstResponseMs));

    const opts = {
        method: method,
        headers: options.headers || {},
        credentials: "same-origin"
    };
    opts.headers["X-Requested-With"] = "XMLHttpRequest";
    if (!opts.headers["Accept"]) {
        opts.headers["Accept"] = "application/json, text/plain, */*";
    }
    const csrfToken = getCsrfToken();
    if (csrfToken) {
        opts.headers["X-CSRF-Token"] = csrfToken;
    }
    const devId = getOrCreateDeviceId();
    if (devId && !opts.headers["X-Device-Id"]) {
        opts.headers["X-Device-Id"] = devId;
    }

    let cached = null;
    if (method === "GET" && !options.noCache) {
        cached = await getHttpCache(path);
        if (cached && cached.etag && cached.text !== null && !opts.headers["If-None-Match"]) {
            opts.headers["If-None-Match"] = cached.etag;
        }
    }
    let plainJsonBody = null;
    if (options.json !== undefined) {
        opts.headers["Content-Type"] = "application/json";
        plainJsonBody = JSON.stringify(options.json);
        opts.body = plainJsonBody;
        if (plainJsonBody.length >= GZIP_MIN_BYTES) {
            const gz = await gzipText(plainJsonBody);
            if (gz && gz.byteLength < plainJsonBody.length) {
                opts.body = gz;
                opts.headers["Content-Encoding"] = "gzip";
            }
        }
    } else if (options.body !== undefined) {
        opts.body = options.body;
    }

    try {
        let { response, text } = await send(opts);
        // A proxy that can't pass compressed bodies through: resend as plain JSON and stop compressing.
        if (opts.headers["Content-Encoding"] === "gzip" && (response.status === 415 || (response.status === 400 && /json|parse|unexpected|malformed|illegal/i.test(text || "")))) {
            gzipUploadsDisabled = true;
            delete opts.headers["Content-Encoding"];
            opts.body = plainJsonBody;
            ({ response, text } = await send(opts));
        }
        if (response.status >= 502 && response.status <= 504) {
            if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.setServerOnline === 'function') {
                window.Obsidianscout.setServerOnline(false);
            }
        } else {
            if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.setServerOnline === 'function') {
                window.Obsidianscout.setServerOnline(true);
            }
        }

        if (response.status === 304) {
            if (!cached) {
                cached = await getHttpCache(path);
            }
            if (cached && cached.text !== null) {
                const parsed = safeParse(cached.text);
                if (parsed !== null && parsed !== undefined) {
                    return parsed;
                }
            }
            // 304 received, but cache was missing or corrupt:
            // Remove orphan ETag and re-fetch unconditionally with reload to get fresh 200 OK data
            console.warn(`[HTTP Cache] 304 Not Modified received for ${path}, but cached data is missing. Re-fetching unconditionally...`);
            await removeHttpCache(path);
            const retryHeaders = { ...opts.headers };
            delete retryHeaders["If-None-Match"];
            retryHeaders["Cache-Control"] = "no-cache";
            const { response: retryResponse, text: freshText } = await send({
                ...opts,
                headers: retryHeaders,
                cache: "reload"
            });
            if (!retryResponse.ok) {
                const retryText = freshText;
                const retryData = retryText ? safeParse(retryText) : null;
                const message = retryData && retryData.error ? retryData.error : "Request failed";
                const err = new Error(message);
                err.status = retryResponse.status;
                throw err;
            }
            const freshData = freshText ? safeParse(freshText) : null;
            const freshEtag = retryResponse.headers && retryResponse.headers.get && retryResponse.headers.get("ETag");
            await setHttpCache(path, freshText, freshEtag);
            return freshData;
        }

        if (response.status === 204) {
            return null;
        }

        const data = text ? safeParse(text) : null;
        if (!response.ok) {
            if (response.status === 401) {
                const isAuthRequest = path.includes("/api/auth/login") ||
                                      path.includes("/api/auth/register") ||
                                      path.includes("/api/auth/status") ||
                                      path.includes("/api/auth/me") ||
                                      path.includes("/api/push");
                // Saves that can wait in the offline queue, background uploads, and anything on a scouting form keep
                // the scout where they are: the entry is kept and a banner asks them to sign in again.
                const allowRedirect = (options.authRedirect !== undefined ? options.authRedirect : !options.queueable) && !onFormPage();
                if (!isPublicPage && !isAuthRequest && !allowRedirect) {
                    announceSessionExpired(path);
                    const err = new Error("Your sign-in has expired. Sign in again to upload.");
                    err.status = 401;
                    err.authExpired = true;
                    throw err;
                }
                if (!isPublicPage && !isAuthRequest) {
                    if (window.Obsidianscout && typeof window.Obsidianscout.checkLoginStatus === 'function') {
                        window.Obsidianscout.checkLoginStatus().then(loggedIn => {
                            if (!loggedIn) {
                                safeRemoveItem("cache:/api/auth/me");
                                window.location.href = "/";
                            }
                        });
                    } else {
                        safeRemoveItem("cache:/api/auth/me");
                        window.location.href = "/";
                    }
                    const err = new Error("Session expired. Redirecting...");
                    err.status = 401;
                    throw err;
                } else {
                    safeRemoveItem("cache:/api/auth/me");
                }
            }
            const message = data && data.error ? data.error : "Request failed";
            const err = new Error(message);
            err.status = response.status;
            // Bad gateway / unavailable / gateway timeout: the request most likely never reached the app.
            if (response.status >= 502 && response.status <= 504) err.network = true;
            throw err;
        }

        if (opts.method === "GET" && options.noCache) {
            // Caller manages its own storage (e.g. the entry store's delta responses).
        } else if (opts.method === "GET") {
            const isScoutingData = isScoutingDataPath(path);
            let shouldCache = true;
            let textToCache = text;

            if (isScoutingData) {
                const userRole = getActiveUserRole();
                if (!canRoleCacheScouting(userRole)) {
                    // Do not cache scouting data on non-analytics/admin/superadmin roles (e.g. SCOUT)
                    shouldCache = false;
                    safeRemoveItem("cache:" + path);
                    safeRemoveItem("etag:" + path);
                } else {
                    // Only cache scouting data for the current event
                    const currentEventKey = getActiveEventKey();
                    if (currentEventKey && data) {
                        const cleanPath = path.split("?")[0];
                        if (cleanPath === "/api/custom-analytics/dataset") {
                            if (typeof data === "object" && !Array.isArray(data)) {
                                const filtered = { ...data };
                                if (Array.isArray(data.matchEntries)) {
                                    filtered.matchEntries = data.matchEntries.filter(e => {
                                        const ek = (e.eventKey || e.event_key || "").trim().toLowerCase();
                                        return ek === currentEventKey;
                                    });
                                }
                                if (Array.isArray(data.pitEntries)) {
                                    filtered.pitEntries = data.pitEntries.filter(e => {
                                        const ek = (e.eventKey || e.event_key || "").trim().toLowerCase();
                                        return ek === currentEventKey;
                                    });
                                }
                                if (Array.isArray(data.qualEntries)) {
                                    filtered.qualEntries = data.qualEntries.filter(e => {
                                        const ek = (e.eventKey || e.event_key || "").trim().toLowerCase();
                                        return ek === currentEventKey;
                                    });
                                }
                                textToCache = JSON.stringify(filtered);
                            }
                        } else if (Array.isArray(data)) {
                            const filteredEntries = data.filter(entry => {
                                if (!entry) return false;
                                const ek = (entry.eventKey || "").trim().toLowerCase();
                                return ek === currentEventKey;
                            });
                            textToCache = JSON.stringify(filteredEntries);
                        }
                    }
                }
            }

            if (shouldCache) {
                const etag = response.headers && response.headers.get && response.headers.get("ETag");
                await setHttpCache(path, textToCache, etag || null);
            }

            if (path && (path === "/api/auth/me" || path.startsWith("/api/auth/me?"))) {
                try {
                    const user = data.user || data;
                    if (user && user.role && !canRoleCacheScouting(String(user.role).toUpperCase())) {
                        purgeScoutingCache();
                    }
                } catch (e) {}
            }

            if (path && (path === "/api/settings" || path.startsWith("/api/settings?"))) {
                try {
                    const settings = data.settings || data;
                    if (window.Obsidianscout && typeof window.Obsidianscout.applyCustomTheme === 'function') {
                        window.Obsidianscout.applyCustomTheme(settings);
                    }
                } catch (e) {
                    console.error("Failed to apply custom theme from settings fetch:", e);
                }
            }
        } else {
            await removeHttpCache(path);
            const basePath = path.split("?")[0];
            noteEntryWrite(basePath);
            await removeHttpCache(basePath);
            if (basePath === "/api/auth/logout") {
                await removeHttpCache("/api/auth/me");
                purgeScoutingCache();
            }
            if (basePath.includes("scouting") || basePath.includes("team") || basePath.includes("event")) {
                await removeHttpCache("/api/summary");
            }
            if (basePath.includes("/admin/users") || basePath.includes("/user") || basePath.includes("/admin/")) {
                await removeHttpCache("/api/auth/me");
                await removeHttpCache("/api/settings");
            }
            if (basePath.includes("/settings") || basePath.includes("/config")) {
                await removeHttpCache("/api/settings");
            }
        }

        return data;
    } catch (error) {
        // Only a failure to reach the server says anything about the connection; a 400 or 403 doesn't.
        const unreachable = !(options.signal && options.signal.aborted) && (isNetworkError(error) || (error && error.name === "AbortError"));
        if (unreachable && typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.setServerOnline === 'function') {
            window.Obsidianscout.setServerOnline(false);
        }

        if (method === "GET") {
            const cachedFallback = await getHttpCache(path);
            if (cachedFallback && cachedFallback.text !== null) {
                console.warn("[Offline Cache] Network fetch failed, falling back to cache for:", path, error);
                announceServedFromCache(path, cachedFallback);
                return safeParse(cachedFallback.text);
            }
        }

        const isAbort = error && error.name === "AbortError";
        if (isAbort) {
            if (options.signal && options.signal.aborted) {
                throw error;
            }
            throw networkError("Request timed out. Try refreshing this page.", error);
        }
        if (isNetworkError(error)) error.network = true;
        throw error;
    } finally {
        if (options.button) {
            setButtonLoading(options.button, false);
        }
    }
}
