/**
 * Service Offline-Sync Module - ObsidianScout
 * LocalStorage caching for scouting entries, background synchronizer, and comprehensive offline asset/endpoint pre-caching.
 */

import { safeGetItem, safeSetItem, safeRemoveItem } from '../base/storage.js';
import { request, purgeScoutingCache } from '../base/http.js';
import { showToast } from '../components/toast.js';
import { checkLoginStatus, getMe, isAdmin } from '../base/auth.js';
import { resolveEventKey } from '../utilities/helpers.js';

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

export async function syncOfflineEntries() {
    if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.isServerOnline === 'function') {
        if (!window.Obsidianscout.isServerOnline()) return;
    } else if (typeof navigator !== 'undefined' && !navigator.onLine) {
        return;
    }

    let totalPending = 0;
    for (const type in CACHE_CONFIGS) {
        const config = CACHE_CONFIGS[type];
        const pending = JSON.parse(safeGetItem(config.key) || "[]");
        totalPending += pending.length;
    }
    if (totalPending === 0) return;

    const syncBtn = document.querySelector("#btn-sync-offline");
    if (syncBtn) {
        syncBtn.disabled = true;
        syncBtn.textContent = "Syncing...";
    }

    let successCount = 0;

    for (const type in CACHE_CONFIGS) {
        const config = CACHE_CONFIGS[type];
        const pending = JSON.parse(safeGetItem(config.key) || "[]");
        if (!pending.length) continue;

        const remaining = [];
        for (const item of pending) {
            try {
                await request(config.endpoint, {
                    method: "POST",
                    json: item
                });
                successCount++;
            } catch (error) {
                console.error(`[Offline Sync] Failed to sync ${config.label}:`, error);
                remaining.push(item);
            }
        }
        safeSetItem(config.key, JSON.stringify(remaining));
    }

    if (successCount > 0) {
        showToast(`Successfully synced ${successCount} offline entries!`, "success");
        window.dispatchEvent(new CustomEvent("obsidianscout:offline-entries-synced"));
        window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));
    }

    if (window.Obsidianscout && typeof window.Obsidianscout.updateConnectionStatus === 'function') {
        window.Obsidianscout.updateConnectionStatus();
    }
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
            "/api/alliances/import-sources"
        ];

        if (settings.year) {
            endpoints.push(`/api/events?year=${settings.year}&cached=1`);
        }
        if (eventKey) {
            endpoints.push(`/api/teams?eventKey=${eventKey}`);
            endpoints.push(`/api/matches?eventKey=${eventKey}`);
            endpoints.push(`/api/alliance-selection?eventKey=${eventKey}`);
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
                updatedKeys.add("cache:" + endpoint);
                successCount++;
            } catch (e) {
                console.warn("[Offline Cache] Sync failed for " + endpoint + ":", e.message || e);
            }
        }
        safeSetItem(LAST_SYNC_KEY, String(Date.now()));
        console.log("[Offline Cache] Background sync complete. Successfully updated " + successCount + " endpoints.");

        if (clearOldOthers && successCount > 0) {
            const keysToRemove = [];
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i);
                if (key && key.startsWith("cache:") && key !== "cache:/api/auth/me" && !updatedKeys.has(key)) {
                    keysToRemove.push(key);
                }
            }
            keysToRemove.forEach(key => safeRemoveItem(key));
            console.log("[Offline Cache] Cleared " + keysToRemove.length + " old/stale cache keys.");
        }
    } catch (err) {
        console.warn("[Offline Cache] Sync loop failed:", err);
    }
}
