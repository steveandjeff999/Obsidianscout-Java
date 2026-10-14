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

function readQueue(key) {
    try {
        const parsed = JSON.parse(safeGetItem(key) || "[]");
        return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
        return [];
    }
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

/** Entries the server refused (4xx). They are kept for review instead of being retried forever. */
export function getFailedEntries() {
    return readQueue(FAILED_ENTRIES_KEY);
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

/** Gives every queued item a stable clientId so a sync can remove exactly what it uploaded. */
function ensureClientIds(key) {
    const queue = readQueue(key);
    let changed = false;
    for (const item of queue) {
        if (item && typeof item === 'object' && !item.clientId) {
            item.clientId = newClientId();
            changed = true;
        }
    }
    if (changed) safeSetItem(key, JSON.stringify(queue));
    return queue;
}

/** Removes one item by clientId, re-reading the queue so entries added mid-sync survive. */
function removeFromQueue(key, clientId) {
    const queue = readQueue(key);
    const next = queue.filter(item => !item || item.clientId !== clientId);
    if (next.length !== queue.length) safeSetItem(key, JSON.stringify(next));
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
        for (const item of ensureClientIds(config.key)) {
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
        try {
            await request(config.endpoint, {
                method: "POST",
                json: item
            });
            successCount++;
            removeFromQueue(config.key, item.clientId);
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
                const failed = getFailedEntries();
                failed.push({
                    type,
                    item,
                    status,
                    message: (error && error.message) || `HTTP ${status}`,
                    failedAt: new Date().toISOString()
                });
                safeSetItem(FAILED_ENTRIES_KEY, JSON.stringify(failed));
                removeFromQueue(config.key, item.clientId);
                rejectedCount++;
            } else if (status === 401 || !status) {
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

/** Puts a rejected entry back in its queue so the next sync retries it. */
export function retryFailedEntry(index) {
    const failed = getFailedEntries();
    const entry = failed[index];
    if (!entry) return;
    const config = CACHE_CONFIGS[entry.type];
    if (config) {
        const queue = readQueue(config.key);
        queue.push(entry.item);
        safeSetItem(config.key, JSON.stringify(queue));
    }
    failed.splice(index, 1);
    safeSetItem(FAILED_ENTRIES_KEY, JSON.stringify(failed));
    notifyPendingChanged();
}

export function discardFailedEntry(index) {
    const failed = getFailedEntries();
    failed.splice(index, 1);
    safeSetItem(FAILED_ENTRIES_KEY, JSON.stringify(failed));
    notifyPendingChanged();
}

const LAST_SYNC_KEY = "obsidianscout:last_offline_sync_ts";
const SYNC_COOLDOWN_MS = 15 * 60 * 1000; // 15 minutes cooldown

export async function syncOfflineCache(clearOldOthers = false, force = false) {
    if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.isServerOnline === 'function') {
        if (!window.Obsidianscout.isServerOnline()) return;
    } else if (typeof navigator !== 'undefined' && !navigator.onLine) {
        return;
    }

    if (!force) {
        const lastSync = parseInt(safeGetItem(LAST_SYNC_KEY) || "0", 10);
        if (Date.now() - lastSync < SYNC_COOLDOWN_MS) {
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
            // Endpoints required so that offline data and analytics pages work for current event
            endpoints.push(
                "/api/analytics",
                "/api/analytics?usePrescout=true",
                "/api/custom-analytics/reports",
                "/api/custom-analytics/dataset",
                "/api/scouting",
                "/api/scouting?includePrescout=true",
                "/api/scouting?includePrescout=true&all=true",
                "/api/pit-scouting",
                "/api/pit-scouting?includePrescout=true",
                "/api/pit-scouting?includePrescout=true&all=true",
                "/api/qual-scouting",
                "/api/qual-scouting?includePrescout=true",
                "/api/qual-scouting?includePrescout=true&all=true",
                "/api/prescout/scouting",
                "/api/prescout/scouting?all=true",
                "/api/prescout/pit-scouting",
                "/api/prescout/pit-scouting?all=true",
                "/api/prescout/qual-scouting",
                "/api/prescout/qual-scouting?all=true"
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

        console.log("[Offline Cache] Starting background sync of " + endpoints.length + " endpoints...");
        
        const updatedKeys = new Set();
        let successCount = 0;

        for (const endpoint of endpoints) {
            try {
                await request(endpoint, { timeoutMs: 8000 });
                updatedKeys.add(endpoint);
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
