/**
 * Service Offline-Sync Module - ObsidianScout
 * On-device queue of scouting entries waiting to upload, background synchronizer, and offline endpoint pre-caching.
 */

import { safeGetItem, safeSetItem, safeRemoveItem, pruneHttpCaches } from '../base/storage.js';
import { request, purgeScoutingCache, getCachedData } from '../base/http.js';
import { showToast } from '../components/toast.js';
import { checkLoginStatus, getMe, isAdmin } from '../base/auth.js';
import { resolveEventKey } from '../utilities/helpers.js';
import { markMatchingEntryAsSynced } from './device-history.js';
import { syncAllKinds } from './entry-store.js';
import { isLowBandwidth } from '../base/net-quality.js';
import { PRIORITY } from '../base/request-scheduler.js';
import {
    STORE_CHANGED_EVENT,
    queueReady,
    listPending,
    putPending,
    deletePending,
    clearPending,
    pendingStillStored,
    listFailed,
    putFailed,
    deleteFailed
} from './queue-store.js';

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
/** A sync was asked for while one was running (say, a new save): run once more when it ends. */
let _syncAgain = false;

/*
 * Queue storage lives in queue-store.js (IndexedDB, with localStorage as a fallback). Each entry is stored on its
 * own under its queue key and `clientId`, so saving, uploading and deleting never rewrite a shared list and two tabs
 * (or a save during a sync) can't overwrite each other's changes. Reads here are synchronous and complete once
 * queueReady() has resolved; writes resolve after storage has committed them.
 */
export const ENTRY_QUEUED_EVENT = "obsidianscout:offline-entry-queued";
/** Fired when uploads stop because the sign-in expired, and again when it is fixed. */
export const SIGN_IN_NEEDED_EVENT = "obsidianscout:upload-sign-in-needed";
const SIGN_IN_NEEDED_KEY = "obsidianscout:upload_sign_in_needed";

export { queueReady, storageMode } from './queue-store.js';

if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    // Another tab (or a migration) changed what's stored.
    window.addEventListener(STORE_CHANGED_EVENT, () => notifyPendingChanged());
    // Signed in again in another tab.
    window.addEventListener("storage", (e) => {
        if (e.key === SIGN_IN_NEEDED_KEY) {
            window.dispatchEvent(new CustomEvent(SIGN_IN_NEEDED_EVENT, { detail: { needed: e.newValue === "1" } }));
        }
    });
}

function byCreatedAt(a, b) {
    return String(a.createdAt || a.failedAt || "").localeCompare(String(b.createdAt || b.failedAt || ""));
}

/** Every entry waiting in one queue (by its storage key), oldest first. Each has a `clientId`. */
export function readPendingQueue(key) {
    const seen = new Set();
    const items = [];
    for (const item of listPending(resolveQueueKey(key))) {
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

/** A new id for an entry. Sent with the first upload attempt too, so the server can recognise a retry. */
export function newClientId() {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** The signed-in account as last seen by this device, or null. */
function currentAccount() {
    try {
        const me = getCachedData("/api/auth/me");
        const user = me && (me.user || me);
        if (!user || user.teamNumber == null) return null;
        return {
            userId: user.userId || user.id || null,
            username: user.username || null,
            teamNumber: user.teamNumber,
            program: user.program ? String(user.program).toUpperCase() : null
        };
    } catch (e) {
        return null;
    }
}

/**
 * True when a queued entry was saved by another team's account (shared tablets). It stays on the device until
 * someone from that team signs in, so it is never uploaded into the wrong team's data.
 */
export function isFromOtherTeam(item, account = currentAccount()) {
    const owner = item && item.savedBy;
    if (!owner || owner.teamNumber == null || !account) return false;
    if (String(owner.teamNumber) !== String(account.teamNumber)) return true;
    return !!(owner.program && account.program && owner.program !== account.program);
}

/**
 * Saves entries for later upload. All or nothing: resolves false (and stores none of them) when the device is out
 * of storage, so a page never reports a save that didn't happen.
 */
export async function enqueueOfflineEntries(typeOrKey, items) {
    const key = resolveQueueKey(typeOrKey);
    const savedBy = currentAccount();
    const prepared = items.map(raw => ({
        ...(savedBy && !raw.savedBy ? { savedBy } : {}),
        ...raw,
        clientId: raw.clientId || newClientId(),
        createdAt: raw.createdAt || new Date().toISOString()
    }));
    if (!(await putPending(key, prepared))) return false;
    notifyPendingChanged();
    if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent(ENTRY_QUEUED_EVENT, { detail: { key, count: prepared.length } }));
    }
    return true;
}

export function enqueueOfflineEntry(typeOrKey, item) {
    return enqueueOfflineEntries(typeOrKey, [item]);
}

/** Removes one queued entry. Safe to call from any tab at any time. */
export async function removePendingItem(typeOrKey, clientId) {
    if (!clientId) return;
    const done = deletePending(resolveQueueKey(typeOrKey), clientId);
    notifyPendingChanged();
    await done;
}

export async function clearPendingQueue(typeOrKey) {
    const done = clearPending(resolveQueueKey(typeOrKey));
    notifyPendingChanged();
    await done;
}

/** True for localStorage keys that hold queued or rejected entries (for `storage` event listeners). */
export function isQueueStorageKey(storageKey) {
    return !!storageKey && (storageKey.startsWith("osq:") || storageKey.startsWith("osf:") ||
        storageKey.startsWith("pending_") || storageKey === FAILED_ENTRIES_KEY);
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

/** True after an upload was refused because the sign-in expired; cleared by the next upload that gets through. */
export function isSignInNeededForUpload() {
    return safeGetItem(SIGN_IN_NEEDED_KEY) === "1";
}

export function setSignInNeededForUpload(needed) {
    const was = isSignInNeededForUpload();
    if (needed) safeSetItem(SIGN_IN_NEEDED_KEY, "1");
    else safeRemoveItem(SIGN_IN_NEEDED_KEY);
    if (was !== !!needed && typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent(SIGN_IN_NEEDED_EVENT, { detail: { needed: !!needed } }));
    }
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
    return listFailed().sort(byCreatedAt);
}

function addFailedEntry(entry) {
    return putFailed(entry);
}

/** Looks up a rejected entry by its id (or, for older callers, its position in getFailedEntries()). */
function findFailed(idOrIndex) {
    const failedList = getFailedEntries();
    if (typeof idOrIndex === 'number') return failedList[idOrIndex] || null;
    return failedList.find(f => f.failedId === idOrIndex) || null;
}

export function getPendingSummary(eventKey = null) {
    const byType = {};
    let total = 0;
    let otherTeam = 0;
    let oldestCreatedAt = null;
    const account = currentAccount();
    for (const entry of getPendingEntries(eventKey)) {
        if (isFromOtherTeam(entry.item, account)) otherTeam++;
        byType[entry.type] = (byType[entry.type] || 0) + 1;
        total++;
        const created = entry.item && entry.item.createdAt;
        if (created && (!oldestCreatedAt || created < oldestCreatedAt)) oldestCreatedAt = created;
    }
    return { total, byType, oldestCreatedAt, otherTeam, failed: getFailedEntries().length };
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
    if (_syncInProgress) {
        _syncAgain = true;
        return;
    }

    await queueReady();
    const before = getPendingSummary();
    if (before.total - before.otherTeam === 0) return;

    _syncInProgress = true;
    let stoppedEarly = false;
    _syncAgain = false;
    try {
        stoppedEarly = (await withSyncLock(runSync)) === "stopped";
    } finally {
        _syncInProgress = false;
        setSyncProgress(null);
        notifyPendingChanged();
        if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.updateConnectionStatus === 'function') {
            window.Obsidianscout.updateConnectionStatus();
        }
    }
    // Entries saved during that round go up now, unless the round stopped because the server can't take them.
    if (_syncAgain && !stoppedEarly) {
        _syncAgain = false;
        await syncOfflineEntries();
    }
}

/** How an upload failure is handled: "reject" keeps it for review, "stop" ends this round, "skip" tries the next. */
export function classifyUploadFailure(error) {
    const status = error && error.status;
    if (status === 401) return "stop";
    // 408 timeout / 429 too many requests: the server is busy, so the rest would fail too.
    if (status === 408 || status === 429) return "stop";
    // 403 can be a stale CSRF token or a role change; keep it and retry later.
    if (status === 403) return "skip";
    // Any other 4xx means the server will never accept this as-is.
    if (status && status >= 400 && status < 500) return "reject";
    // 5xx can be specific to one entry; carry on with the others. Unreachable server: stop.
    if (status && status >= 500 && !error.network) return "skip";
    return "stop";
}

async function runSync() {
    const work = [];
    const account = currentAccount();
    for (const type in CACHE_CONFIGS) {
        const config = CACHE_CONFIGS[type];
        for (const item of readQueue(config.key)) {
            if (item && item.clientId && !isFromOtherTeam(item, account)) work.push({ type, config, item });
        }
    }
    if (!work.length) return;

    let successCount = 0;
    let rejectedCount = 0;
    let stopped = false;
    setSyncProgress({ done: 0, total: work.length });

    for (let i = 0; i < work.length; i++) {
        const { type, config, item } = work[i];
        // Another tab uploaded or deleted it since this sync started.
        if (!(await pendingStillStored(config.key, item.clientId))) {
            setSyncProgress({ done: i + 1, total: work.length });
            continue;
        }
        try {
            await request(config.endpoint, {
                method: "POST",
                json: item,
                // Uploading in the background must never navigate away from a form being filled in.
                authRedirect: false
            });
            successCount++;
            setSignInNeededForUpload(false);
            await removePendingItem(config.key, item.clientId);
            try {
                const itemData = getQueuedPayload(item);
                markMatchingEntryAsSynced(type, itemData.eventKey, itemData.targetTeamNumber || itemData.teamNumber, itemData.matchKey || itemData.matchNumber);
            } catch (histErr) {
                console.warn("[Offline Sync] Failed to update history sync status:", histErr);
            }
        } catch (error) {
            console.error(`[Offline Sync] Failed to sync ${config.label}:`, error);
            const outcome = classifyUploadFailure(error);
            if (outcome === "reject") {
                const status = error.status;
                if (await addFailedEntry({
                    type,
                    item,
                    status,
                    message: (error && error.message) || `HTTP ${status}`,
                    failedAt: new Date().toISOString()
                })) {
                    await removePendingItem(config.key, item.clientId);
                    rejectedCount++;
                }
            } else if (outcome === "stop") {
                if (error && error.status === 401) setSignInNeededForUpload(true);
                setSyncProgress({ done: i + 1, total: work.length });
                stopped = true;
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
    return stopped ? "stopped" : "done";
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
        const uploadable = () => {
            const summary = getPendingSummary();
            return summary.total - summary.otherTeam;
        };
        const before = uploadable();
        if (before === 0) {
            _autoStep = 0;
            return;
        }
        try {
            await syncOfflineEntries();
        } catch (e) {
            console.warn("[Offline Sync] Automatic upload failed:", e);
        }
        const after = uploadable();
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
    // Signing in again (in this tab or another) lets the waiting entries go up straight away.
    window.addEventListener(SIGN_IN_NEEDED_EVENT, (e) => {
        if (e.detail && !e.detail.needed) {
            _autoStep = 0;
            scheduleAutoUpload(1000);
        }
    });
    queueReady().then(() => {
        const summary = getPendingSummary();
        if (summary.total - summary.otherTeam > 0) {
            scheduleAutoUpload(AUTO_RETRY_SECONDS[0] * 1000);
        }
    });
}

/** Puts a rejected entry back in its queue so the next sync retries it. Takes its `failedId`. Resolves true on success. */
export async function retryFailedEntry(idOrIndex) {
    const entry = findFailed(idOrIndex);
    if (!entry) return false;
    const config = CACHE_CONFIGS[entry.type];
    if (config && entry.item && !(await enqueueOfflineEntry(config.key, entry.item))) return false;
    await deleteFailed(entry.failedId);
    notifyPendingChanged();
    return true;
}

export async function discardFailedEntry(idOrIndex) {
    const entry = findFailed(idOrIndex);
    if (!entry) return;
    await deleteFailed(entry.failedId);
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
            "/api/alliances/import-sources"
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
