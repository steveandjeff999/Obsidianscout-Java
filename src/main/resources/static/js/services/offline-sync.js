/**
 * Service Offline-Sync Module - ObsidianScout
 * LocalStorage caching for scouting entries, background synchronizer, and comprehensive offline asset/endpoint pre-caching.
 */

import { safeGetItem, safeSetItem, safeRemoveItem, pruneHttpCaches } from '../base/storage.js';
import { request, purgeScoutingCache } from '../base/http.js';
import { showToast } from '../components/toast.js';
import { checkLoginStatus, getMe, isAdmin } from '../base/auth.js';
import { resolveEventKey } from '../utilities/helpers.js';
import { markMatchingEntryAsSynced } from './device-history.js';
import { syncAllKinds } from './entry-store.js';
import { isLowBandwidth } from '../base/net-quality.js';
import { PRIORITY } from '../base/request-scheduler.js';

export const CACHE_CONFIGS = {
    "match-scouting": {
        key: "pending_scouting_entries",
        endpoint: "/api/scouting",
        label: "Match Scouting",
        hasMatch: true
    },
    "pit-scouting": {
        key: "pending_pit_scouting_entries",
        endpoint: "/api/pit-scouting",
        label: "Pit Scouting",
        hasMatch: false
    },
    "qual-scouting": {
        key: "pending_qualitative_entries",
        endpoint: "/api/qual-scouting",
        label: "Qualitative Scouting",
        hasMatch: true
    },
    "prescout-scouting": {
        key: "pending_prescout_scouting_entries",
        endpoint: "/api/prescout/scouting",
        label: "Prescout Match",
        hasMatch: true
    },
    "prescout-pit-scouting": {
        key: "pending_prescout_pit_scouting_entries",
        endpoint: "/api/prescout/pit-scouting",
        label: "Prescout Pit",
        hasMatch: false
    },
    "prescout-qual-scouting": {
        key: "pending_prescout_qualitative_entries",
        endpoint: "/api/prescout/qual-scouting",
        label: "Prescout Qualitative",
        hasMatch: true
    }
};

export const FAILED_ENTRIES_KEY = "failed_offline_entries";
export const PENDING_CHANGED_EVENT = "obsidianscout:pending-entries-changed";
export const SYNC_PROGRESS_EVENT = "obsidianscout:offline-sync-progress";

let _syncInProgress = false;
let _syncProgress = null;

/*
 * Queue storage. Each queued entry lives under its own localStorage key, `osq:<queue key>:<clientId>`, and each
 * rejected entry under `osf:<clientId>`. Saving, uploading and deleting therefore each touch one key and never
 * rewrite a shared array, so two tabs (or a save during a sync) can't overwrite each other's changes.
 * The old layout, one JSON array per queue key, is still read and is moved into per-entry keys when found.
 */
const ITEM_PREFIX = "osq:";
const FAILED_PREFIX = "osf:";
export const ENTRY_QUEUED_EVENT = "obsidianscout:offline-entry-queued";

function itemPrefix(key) {
    return `${ITEM_PREFIX}${key}:`;
}

function allStorageKeys() {
    const out = [];
    try {
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k) out.push(k);
        }
    } catch (e) {}
    return out;
}

function parseJson(text, fallback) {
    try {
        const parsed = JSON.parse(text);
        return parsed === undefined ? fallback : parsed;
    } catch (e) {
        return fallback;
    }
}

function readLegacyArray(key) {
    const parsed = parseJson(safeGetItem(key) || "[]", []);
    return Array.isArray(parsed) ? parsed : [];
}

/**
 * Moves entries a page from an older version (or an old open tab) left in the shared array into per-entry keys.
 * Entries appended to the array while this runs stay there and move on the next read.
 */
function migrateLegacyQueue(key) {
    const legacy = readLegacyArray(key);
    if (!legacy.length) return;
    for (const item of legacy) {
        if (!item || typeof item !== 'object') continue;
        const clientId = item.clientId || newClientId();
        // Out of storage: leave the array as it is; it is still read below.
        if (!safeSetItem(itemPrefix(key) + clientId, JSON.stringify({ ...item, clientId }))) return;
    }
    const latest = readLegacyArray(key);
    const appendedMeanwhile = latest.slice(legacy.length);
    if (appendedMeanwhile.length) safeSetItem(key, JSON.stringify(appendedMeanwhile));
    else safeRemoveItem(key);
}

function readItemKeys(prefix) {
    const out = [];
    for (const k of allStorageKeys()) {
        if (!k.startsWith(prefix)) continue;
        const item = parseJson(safeGetItem(k), null);
        if (item && typeof item === 'object') out.push(item);
    }
    return out;
}

function byCreatedAt(a, b) {
    return String(a.createdAt || a.failedAt || "").localeCompare(String(b.createdAt || b.failedAt || ""));
}

/** Every entry waiting in one queue (by its storage key), oldest first. Each has a `clientId`. */
export function readPendingQueue(key) {
    migrateLegacyQueue(key);
    const seen = new Set();
    const items = [];
    for (const item of readItemKeys(itemPrefix(key)).concat(readLegacyArray(key))) {
        if (!item || typeof item !== 'object') continue;
        if (item.clientId) {
            if (seen.has(item.clientId)) continue;
            seen.add(item.clientId);
        }
        items.push(item);
    }
    return items.sort(byCreatedAt);
}

const readQueue = readPendingQueue;

/** Config for a form type ("match-scouting") or a queue storage key ("pending_scouting_entries"). */
function resolveQueueKey(typeOrKey) {
    if (CACHE_CONFIGS[typeOrKey]) return CACHE_CONFIGS[typeOrKey].key;
    return typeOrKey;
}

/**
 * Saves entries for later upload. All or nothing: returns false (and stores none of them) when the device is out
 * of storage, so a page never reports a save that didn't happen.
 */
export function enqueueOfflineEntries(typeOrKey, items) {
    const key = resolveQueueKey(typeOrKey);
    const written = [];
    for (const raw of items) {
        const item = { ...raw, clientId: raw.clientId || newClientId(), createdAt: raw.createdAt || new Date().toISOString() };
        const storageKey = itemPrefix(key) + item.clientId;
        if (!safeSetItem(storageKey, JSON.stringify(item))) {
            written.forEach(k => safeRemoveItem(k));
            return false;
        }
        written.push(storageKey);
    }
    notifyPendingChanged();
    if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent(ENTRY_QUEUED_EVENT, { detail: { key, count: items.length } }));
    }
    return true;
}

export function enqueueOfflineEntry(typeOrKey, item) {
    return enqueueOfflineEntries(typeOrKey, [item]);
}

/** Removes one queued entry. Safe to call from any tab at any time. */
export function removePendingItem(typeOrKey, clientId) {
    const key = resolveQueueKey(typeOrKey);
    if (!clientId) return;
    safeRemoveItem(itemPrefix(key) + clientId);
    const legacy = readLegacyArray(key);
    const next = legacy.filter(item => !item || item.clientId !== clientId);
    if (next.length !== legacy.length) {
        if (next.length) safeSetItem(key, JSON.stringify(next));
        else safeRemoveItem(key);
    }
    notifyPendingChanged();
}

export function clearPendingQueue(typeOrKey) {
    const key = resolveQueueKey(typeOrKey);
    const prefix = itemPrefix(key);
    for (const k of allStorageKeys()) if (k.startsWith(prefix)) safeRemoveItem(k);
    safeRemoveItem(key);
    notifyPendingChanged();
}

/** True for localStorage keys that hold queued or rejected entries (for `storage` event listeners). */
export function isQueueStorageKey(storageKey) {
    return !!storageKey && (storageKey.startsWith(ITEM_PREFIX) || storageKey.startsWith(FAILED_PREFIX) ||
        storageKey.startsWith("pending_") || storageKey === FAILED_ENTRIES_KEY);
}

function newClientId() {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function notifyPendingChanged() {
    if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent(PENDING_CHANGED_EVENT));
    }
}

function setSyncProgress(progress) {
    _syncProgress = progress;
    if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent(SYNC_PROGRESS_EVENT, { detail: progress }));
    }
}

export function isOfflineSyncInProgress() {
    return _syncInProgress;
}

export function getOfflineSyncProgress() {
    return _syncProgress;
}

/** Returns the payload stored in a queued item, regardless of which page wrote it. */
export function getQueuedPayload(item) {
    return (item && item.data) ? item.data : (item || {});
}

/**
 * Every entry saved on this device that hasn't reached the server yet, tagged with its form type.
 * Optionally limited to one event.
 */
export function getPendingEntries(eventKey = null) {
    const out = [];
    for (const type in CACHE_CONFIGS) {
        for (const item of readQueue(CACHE_CONFIGS[type].key)) {
            const data = getQueuedPayload(item);
            if (eventKey && data.eventKey && String(data.eventKey).toLowerCase() !== String(eventKey).toLowerCase()) continue;
            out.push({ type, label: CACHE_CONFIGS[type].label, item, data });
        }
    }
    return out;
}

/** Entries the server refused (4xx), oldest first. They are kept for review instead of being retried forever. */
export function getFailedEntries() {
    // Move the old shared list into per-entry keys.
    const legacy = readLegacyArray(FAILED_ENTRIES_KEY);
    if (legacy.length) {
        let moved = true;
        for (const f of legacy) {
            const id = f.failedId || (f.item && f.item.clientId) || newClientId();
            if (!safeSetItem(FAILED_PREFIX + id, JSON.stringify({ ...f, failedId: id }))) { moved = false; break; }
        }
        if (moved) safeRemoveItem(FAILED_ENTRIES_KEY);
    }
    return readItemKeys(FAILED_PREFIX).sort(byCreatedAt);
}

function addFailedEntry(entry) {
    const failedId = (entry.item && entry.item.clientId) || newClientId();
    safeSetItem(FAILED_PREFIX + failedId, JSON.stringify({ ...entry, failedId }));
}

/** Looks up a rejected entry by its id (or, for older callers, its position in getFailedEntries()). */
function findFailed(idOrIndex) {
    const failed = getFailedEntries();
    if (typeof idOrIndex === 'number') return failed[idOrIndex] || null;
    return failed.find(f => f.failedId === idOrIndex) || null;
}

export function getPendingSummary(eventKey = null) {
    const byType = {};
    let total = 0;
    let oldestCreatedAt = null;
    for (const entry of getPendingEntries(eventKey)) {
        byType[entry.type] = (byType[entry.type] || 0) + 1;
        total++;
        const created = entry.item && entry.item.createdAt;
        if (created && (!oldestCreatedAt || created < oldestCreatedAt)) oldestCreatedAt = created;
    }
    return { total, byType, oldestCreatedAt, failed: getFailedEntries().length };
}

function withSyncLock(fn) {
    if (typeof navigator !== 'undefined' && navigator.locks && typeof navigator.locks.request === 'function') {
        // ifAvailable: another tab already uploading the same queues means this one has nothing to do.
        return navigator.locks.request('obsidianscout-offline-sync', { ifAvailable: true }, async (lock) => {
            if (!lock) return;
            return fn();
        });
    }
    return fn();
}

export async function syncOfflineEntries() {
    if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.isServerOnline === 'function') {
        if (!window.Obsidianscout.isServerOnline()) return;
    } else if (typeof navigator !== 'undefined' && !navigator.onLine) {
        return;
    }
    if (_syncInProgress) return;

    if (getPendingSummary().total === 0) return;

    _syncInProgress = true;
    try {
        await withSyncLock(runSync);
    } finally {
        _syncInProgress = false;
        setSyncProgress(null);
        notifyPendingChanged();
        if (window.Obsidianscout && typeof window.Obsidianscout.updateConnectionStatus === 'function') {
            window.Obsidianscout.updateConnectionStatus();
        }
    }
}

async function runSync() {
    const work = [];
    for (const type in CACHE_CONFIGS) {
        const config = CACHE_CONFIGS[type];
        for (const item of readQueue(config.key)) {
            if (item && item.clientId) work.push({ type, config, item });
        }
    }
    if (!work.length) return;

    const syncBtn = document.querySelector("#btn-sync-offline");
    if (syncBtn) {
        syncBtn.disabled = true;
        syncBtn.textContent = "Syncing...";
    }

    let successCount = 0;
    let rejectedCount = 0;
    setSyncProgress({ done: 0, total: work.length });

    for (let i = 0; i < work.length; i++) {
        const { type, config, item } = work[i];
        // Another tab uploaded or deleted it since this sync started.
        if (safeGetItem(itemPrefix(config.key) + item.clientId) === null &&
            !readLegacyArray(config.key).some(x => x && x.clientId === item.clientId)) {
            setSyncProgress({ done: i + 1, total: work.length });
            continue;
        }
        try {
            await request(config.endpoint, {
                method: "POST",
                json: item
            });
            successCount++;
            removePendingItem(config.key, item.clientId);
            try {
                const itemData = getQueuedPayload(item);
                markMatchingEntryAsSynced(type, itemData.eventKey, itemData.targetTeamNumber || itemData.teamNumber, itemData.matchKey || itemData.matchNumber);
            } catch (histErr) {
                console.warn("[Offline Sync] Failed to update history sync status:", histErr);
            }
        } catch (error) {
            console.error(`[Offline Sync] Failed to sync ${config.label}:`, error);
            const status = error && error.status;
            // 4xx (other than auth / timeout / rate limit) means the server will never accept this as-is.
            if (status && status >= 400 && status < 500 && ![401, 403, 408, 429].includes(status)) {
                addFailedEntry({
                    type,
                    item,
                    status,
                    message: (error && error.message) || `HTTP ${status}`,
                    failedAt: new Date().toISOString()
                });
                removePendingItem(config.key, item.clientId);
                rejectedCount++;
            } else if (status === 401 || !status || error.network) {
                // Logged out or the connection dropped: stop and keep everything for the next attempt.
                setSyncProgress({ done: i + 1, total: work.length });
                break;
            }
        }
        setSyncProgress({ done: i + 1, total: work.length });
        notifyPendingChanged();
    }

    if (successCount > 0) {
        showToast(`Uploaded ${successCount} saved ${successCount === 1 ? "entry" : "entries"} to the server`, "success");
        window.dispatchEvent(new CustomEvent("obsidianscout:offline-entries-synced"));
        window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));
    }
    if (rejectedCount > 0) {
        showToast(`${rejectedCount} saved ${rejectedCount === 1 ? "entry was" : "entries were"} rejected by the server. Review them in Cache Manager.`, "error");
    }
}

/*
 * Automatic upload. Saved entries go up as soon as the server is reachable again (the browser's own "online" event
 * rarely fires at events, where the Wi-Fi stays connected but the server drops out), shortly after each save, and on
 * a backing-off timer while anything is still waiting, so a scout who stays on one page never has to press Sync.
 */
const AUTO_RETRY_SECONDS = [15, 30, 60, 120, 300];
let _autoTimer = null;
let _autoStep = 0;
let _autoStarted = false;

function autoUploadAllowed() {
    // Cache Manager lets the user review entries before they go up.
    return !(typeof document !== 'undefined' && document.body && document.body.dataset.page === "cache-manager");
}

function scheduleAutoUpload(delayMs) {
    if (_autoTimer) clearTimeout(_autoTimer);
    _autoTimer = setTimeout(async () => {
        _autoTimer = null;
        if (!autoUploadAllowed()) return;
        const before = getPendingSummary().total;
        if (before === 0) {
            _autoStep = 0;
            return;
        }
        try {
            await syncOfflineEntries();
        } catch (e) {
            console.warn("[Offline Sync] Automatic upload failed:", e);
        }
        const after = getPendingSummary().total;
        if (after === 0) {
            _autoStep = 0;
            return;
        }
        _autoStep = after < before ? 0 : _autoStep + 1;
        scheduleAutoUpload(AUTO_RETRY_SECONDS[Math.min(_autoStep, AUTO_RETRY_SECONDS.length - 1)] * 1000);
    }, delayMs);
}

export function startAutoUpload() {
    if (_autoStarted || typeof window === 'undefined') return;
    _autoStarted = true;
    window.addEventListener("obsidianscout:connection-changed", (e) => {
        if (e.detail && e.detail.online) {
            _autoStep = 0;
            scheduleAutoUpload(1000);
        }
    });
    window.addEventListener(ENTRY_QUEUED_EVENT, () => {
        _autoStep = 0;
        scheduleAutoUpload(2000);
    });
    if (getPendingSummary().total > 0) {
        scheduleAutoUpload(AUTO_RETRY_SECONDS[0] * 1000);
    }
}

/** Puts a rejected entry back in its queue so the next sync retries it. Takes its `failedId`. */
export function retryFailedEntry(idOrIndex) {
    const entry = findFailed(idOrIndex);
    if (!entry) return;
    const config = CACHE_CONFIGS[entry.type];
    if (config && entry.item && !enqueueOfflineEntry(config.key, entry.item)) return;
    safeRemoveItem(FAILED_PREFIX + entry.failedId);
    notifyPendingChanged();
}

export function discardFailedEntry(idOrIndex) {
    const entry = findFailed(idOrIndex);
    if (!entry) return;
    safeRemoveItem(FAILED_PREFIX + entry.failedId);
    notifyPendingChanged();
}

const LAST_SYNC_KEY = "obsidianscout:last_offline_sync_ts";
const SYNC_COOLDOWN_MS = 15 * 60 * 1000; // 15 minutes cooldown
/** On a slow link the warm-up runs less often. */
const LOW_BW_SYNC_COOLDOWN_MS = 45 * 60 * 1000;
/**
 * On a slow link, endpoints whose data rarely changes are refreshed at most this often. On a normal link they're
 * refreshed every round; the ETag makes an unchanged one a tiny 304.
 */
const STATIC_REFRESH_MS = 30 * 60 * 1000;
const STATIC_WARM_PREFIX = "obsidianscout:warm:";

/** Configs, event lists and similar: rarely change between matches. */
function isStaticEndpoint(endpoint) {
    const clean = endpoint.split("?")[0];
    return clean === "/api/config" || clean === "/api/pit-config" || clean === "/api/qual-config" ||
        clean === "/api/events" || clean === "/api/alliances/import-sources" || clean === "/api/admin/email-settings";
}

/** Large or derived responses: skipped on a slow link (analytics are computed on the device instead). */
function isBulkEndpoint(endpoint) {
    const clean = endpoint.split("?")[0];
    return clean === "/api/analytics" || clean.startsWith("/api/custom-analytics") ||
        clean === "/api/matches/predict-all" || clean === "/api/admin/users" || clean === "/api/summary";
}

function staticIsFresh(endpoint) {
    const last = parseInt(safeGetItem(STATIC_WARM_PREFIX + endpoint) || "0", 10);
    return Date.now() - last < STATIC_REFRESH_MS;
}

export async function syncOfflineCache(clearOldOthers = false, force = false) {
    if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.isServerOnline === 'function') {
        if (!window.Obsidianscout.isServerOnline()) return;
    } else if (typeof navigator !== 'undefined' && !navigator.onLine) {
        return;
    }

    const lowBandwidth = isLowBandwidth();
    if (!force) {
        const lastSync = parseInt(safeGetItem(LAST_SYNC_KEY) || "0", 10);
        if (Date.now() - lastSync < (lowBandwidth ? LOW_BW_SYNC_COOLDOWN_MS : SYNC_COOLDOWN_MS)) {
            console.log("[Offline Cache] Cooldown active, skipping background sync.");
            return;
        }
    }

    const loggedIn = await checkLoginStatus();
    if (!loggedIn) return;

    try {
        const settingsResponse = await request("/api/settings", { timeoutMs: 5000 }).catch(() => null);
        const user = await getMe().catch(() => null);
        if (!settingsResponse || !settingsResponse.settings) {
            return;
        }

        const settings = settingsResponse.settings;
        const eventKey = resolveEventKey(settings);
        const isAdminUser = user && isAdmin(user.role);
        const canCacheScoutingData = user && (user.role === "ANALYTICS" || user.role === "ADMIN" || user.role === "SUPERADMIN");

        // Base shell and metadata endpoints cached for all authenticated users
        const endpoints = [
            "/api/auth/me",
            "/api/settings",
            "/api/settings?local=true",
            "/api/config",
            "/api/pit-config",
            "/api/qual-config",
            "/api/events?cached=1",
            "/api/summary",
            "/api/alliances",
            "/api/alliances/invites",
            "/api/alliances/invites/count",
            "/api/alliances/import-sources",
            "/api/assignments/my"
        ];

        if (settings.year) {
            endpoints.push(`/api/events?year=${settings.year}&cached=1`);
        }
        if (eventKey) {
            endpoints.push(`/api/teams?eventKey=${eventKey}`);
            endpoints.push(`/api/matches?eventKey=${eventKey}`);
            endpoints.push(`/api/alliance-selection?eventKey=${eventKey}`);
            endpoints.push(`/api/assignments/my?eventKey=${eventKey}`);
            endpoints.push(`/api/assignments?eventKey=${eventKey}`);
            endpoints.push(`/api/assignments/coverage?eventKey=${eventKey}`);
        }
        if (isAdminUser) {
            endpoints.push("/api/admin/users");
            if (user && user.role === "SUPERADMIN") {
                endpoints.push("/api/admin/email-settings");
            }
        }

        if (canCacheScoutingData) {
            // Match, pit and qualitative entries come through the delta-synced entry store (one small request per
            // kind when little changed), which also answers every scouting list and analytics request offline.
            endpoints.push(
                "/api/analytics",
                "/api/analytics?usePrescout=true",
                "/api/custom-analytics/reports",
                "/api/custom-analytics/dataset"
            );
            if (eventKey) {
                endpoints.push(`/api/custom-analytics/dataset?eventKey=${eventKey}`);
                endpoints.push(`/api/matches/predict-all?eventKey=${eventKey}&usePrescout=false`);
                endpoints.push(`/api/matches/predict-all?eventKey=${eventKey}&usePrescout=true`);
            }
        } else {
            // Clean up any scouting data caches for non-analytics roles (e.g. SCOUT)
            purgeScoutingCache();
        }

        if (canCacheScoutingData) {
            const results = await syncAllKinds();
            console.log("[Offline Cache] Entry store sync:", results);
        }

        // Kept even when skipped this round, so pruning below doesn't throw away still-valid copies.
        const updatedKeys = new Set(endpoints);
        let successCount = 0;
        const toFetch = endpoints.filter(endpoint => {
            if (lowBandwidth && isBulkEndpoint(endpoint)) return false;
            if (!force && lowBandwidth && isStaticEndpoint(endpoint) && staticIsFresh(endpoint)) return false;
            return true;
        });
        console.log(`[Offline Cache] Refreshing ${toFetch.length} of ${endpoints.length} endpoints${lowBandwidth ? " (slow link)" : ""}...`);

        for (const endpoint of toFetch) {
            try {
                await request(endpoint, { timeoutMs: 8000, priority: isBulkEndpoint(endpoint) ? PRIORITY.LOW : PRIORITY.NORMAL });
                if (isStaticEndpoint(endpoint)) safeSetItem(STATIC_WARM_PREFIX + endpoint, String(Date.now()));
                successCount++;
            } catch (e) {
                console.warn("[Offline Cache] Sync failed for " + endpoint + ":", e.message || e);
            }
        }
        safeSetItem(LAST_SYNC_KEY, String(Date.now()));
        console.log("[Offline Cache] Background sync complete. Successfully updated " + successCount + " endpoints.");

        if (clearOldOthers && successCount > 0) {
            try {
                await pruneHttpCaches(updatedKeys);
            } catch (pruneErr) {
                console.warn("[Offline Cache] Failed to prune IDB caches:", pruneErr);
            }

            const keysToRemove = [];
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i);
                if (key && (key.startsWith("cache:") || key.startsWith("etag:"))) {
                    const subKey = key.startsWith("cache:") ? key.substring(6) : key.substring(5);
                    if (subKey !== "/api/auth/me" && subKey !== "/api/settings" && !updatedKeys.has(subKey)) {
                        keysToRemove.push(key);
                    }
                }
            }
            keysToRemove.forEach(key => safeRemoveItem(key));
            console.log("[Offline Cache] Cleared " + keysToRemove.length + " old/stale cache keys.");
        }
    } catch (err) {
        console.warn("[Offline Cache] Sync loop failed:", err);
    }
}
