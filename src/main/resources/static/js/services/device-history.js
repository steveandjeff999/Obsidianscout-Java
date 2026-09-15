/**
 * Service Device-History Module - ObsidianScout
 * Immutable local activity log and backup for all scouting submissions, offline saves,
 * QR code generations, and JSON exports created on this device.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';
import { downloadJson } from './data-compression.js';
import { showToast } from '../components/toast.js';

export const HISTORY_STORAGE_KEY = "obsidianscout_device_history";
export const MAX_HISTORY_ITEMS = 1500;

/**
 * Record a transaction in local device history.
 * @param {Object} options
 * @param {'upload'|'offline_save'|'qr_generated'|'json_export'} options.action
 * @param {string} [options.actionLabel]
 * @param {string} options.formType - e.g. "match-scouting", "pit-scouting", etc.
 * @param {string} [options.formLabel]
 * @param {string} [options.eventKey]
 * @param {number|string} [options.teamNumber]
 * @param {string|number} [options.matchKey]
 * @param {number} [options.matchNumber]
 * @param {string} [options.scoutName]
 * @param {Object} options.payload - Full scouting form payload
 * @param {boolean} [options.serverSynced=false] - True if confirmed saved to server
 * @param {string|null} [options.syncedAt=null]
 * @returns {Object} The recorded entry
 */
export function recordDeviceHistory({
    action,
    actionLabel,
    formType,
    formLabel,
    eventKey = "",
    teamNumber = "",
    matchKey = null,
    matchNumber = null,
    scoutName = null,
    payload = {},
    serverSynced = false,
    syncedAt = null
}) {
    try {
        const history = getDeviceHistory();
        const now = new Date().toISOString();

        // Standardize labels if not explicitly supplied
        const resolvedActionLabel = actionLabel || getActionLabel(action);
        const resolvedFormLabel = formLabel || getFormLabel(formType);

        const newEntry = {
            id: `hist_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`,
            timestamp: now,
            action,
            actionLabel: resolvedActionLabel,
            formType,
            formLabel: resolvedFormLabel,
            eventKey: eventKey ? String(eventKey).trim().toLowerCase() : "",
            teamNumber: teamNumber !== null && teamNumber !== undefined ? String(teamNumber).trim() : "",
            matchKey: matchKey ? String(matchKey).trim() : null,
            matchNumber: matchNumber !== null && matchNumber !== undefined ? Number(matchNumber) : null,
            scoutName: scoutName || null,
            payload: JSON.parse(JSON.stringify(payload || {})),
            serverSynced: Boolean(serverSynced),
            syncedAt: serverSynced ? (syncedAt || now) : null
        };

        // Prepend newest first
        history.unshift(newEntry);

        // Enforce maximum history capacity
        if (history.length > MAX_HISTORY_ITEMS) {
            history.splice(MAX_HISTORY_ITEMS);
        }

        saveDeviceHistory(history);
        window.dispatchEvent(new CustomEvent("obsidianscout:device-history-changed", { detail: newEntry }));
        return newEntry;
    } catch (e) {
        console.warn("[DeviceHistory] Failed to record device history entry:", e);
        return null;
    }
}

/**
 * Retrieve all history entries from localStorage.
 * @returns {Array<Object>}
 */
export function getDeviceHistory() {
    try {
        const raw = safeGetItem(HISTORY_STORAGE_KEY);
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    } catch (e) {
        console.warn("[DeviceHistory] Failed to parse device history:", e);
        return [];
    }
}

/**
 * Internal helper to save history array.
 * @param {Array<Object>} history
 */
function saveDeviceHistory(history) {
    safeSetItem(HISTORY_STORAGE_KEY, JSON.stringify(history));
}

/**
 * Mark a specific history entry as synced with the server.
 * @param {string} id - The entry id
 * @returns {boolean} True if found and updated
 */
export function markEntryAsSynced(id) {
    const history = getDeviceHistory();
    const entry = history.find(e => e.id === id);
    if (entry) {
        entry.serverSynced = true;
        entry.syncedAt = new Date().toISOString();
        saveDeviceHistory(history);
        window.dispatchEvent(new CustomEvent("obsidianscout:device-history-changed", { detail: entry }));
        return true;
    }
    return false;
}

/**
 * Find and mark matching entries as synced with the server.
 * Useful when offline queue syncer finishes an upload.
 * @param {string} formType
 * @param {string} eventKey
 * @param {string|number} teamNumber
 * @param {string|number} [matchKey]
 */
export function markMatchingEntryAsSynced(formType, eventKey, teamNumber, matchKey) {
    const history = getDeviceHistory();
    let updated = false;
    const now = new Date().toISOString();

    const normalizeType = (t) => {
        if (!t) return "";
        const s = String(t).toLowerCase().replace(/_/g, '-');
        if (s.includes('pit')) return s.includes('prescout') ? 'prescout-pit' : 'pit';
        if (s.includes('qual')) return s.includes('prescout') ? 'prescout-qual' : 'qual';
        if (s.includes('prescout')) return 'prescout-scout';
        return 'scout';
    };

    const targetType = normalizeType(formType);

    for (const entry of history) {
        if (!entry.serverSynced && (normalizeType(entry.formType) === targetType || !formType)) {
            const matchesEvent = !eventKey || (entry.eventKey && entry.eventKey.toLowerCase() === String(eventKey).toLowerCase());
            const matchesTeam = String(entry.teamNumber) === String(teamNumber);
            const matchesMatch = !matchKey || String(entry.matchKey).toLowerCase() === String(matchKey).toLowerCase();

            if (matchesEvent && matchesTeam && matchesMatch) {
                entry.serverSynced = true;
                entry.syncedAt = now;
                updated = true;
                break; // Match the earliest unsynced entry
            }
        }
    }

    if (updated) {
        saveDeviceHistory(history);
        window.dispatchEvent(new CustomEvent("obsidianscout:device-history-changed"));
    }
}

/**
 * Delete a single entry by ID.
 * @param {string} id
 * @returns {boolean}
 */
export function deleteDeviceHistoryEntry(id) {
    const history = getDeviceHistory();
    const idx = history.findIndex(e => e.id === id);
    if (idx >= 0) {
        history.splice(idx, 1);
        saveDeviceHistory(history);
        window.dispatchEvent(new CustomEvent("obsidianscout:device-history-changed"));
        return true;
    }
    return false;
}

/**
 * Clear all history entries.
 */
export function clearDeviceHistory() {
    saveDeviceHistory([]);
    window.dispatchEvent(new CustomEvent("obsidianscout:device-history-changed"));
}

/**
 * Download complete device history as a JSON file backup.
 */
export function exportDeviceHistory() {
    const history = getDeviceHistory();
    if (history.length === 0) {
        showToast("No history entries to export", "info");
        return;
    }
    const dateStr = new Date().toISOString().slice(0, 10);
    downloadJson(history, `obsidianscout_device_history_backup_${dateStr}.json`);
}

/**
 * Merge/import history entries from an external backup JSON array.
 * @param {Array<Object>} importedList
 * @returns {number} Count of newly imported items
 */
export function importDeviceHistory(importedList) {
    if (!Array.isArray(importedList)) {
        throw new Error("Invalid backup format: expected an array of history entries.");
    }
    const existing = getDeviceHistory();
    const existingIds = new Set(existing.map(e => e.id));
    let addedCount = 0;

    for (const item of importedList) {
        if (!item || typeof item !== 'object') continue;
        // Generate an ID if not present
        if (!item.id) {
            item.id = `hist_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
        }
        if (!existingIds.has(item.id)) {
            existing.push(item);
            existingIds.add(item.id);
            addedCount++;
        }
    }

    // Sort newest first
    existing.sort((a, b) => new Date(b.timestamp || 0) - new Date(a.timestamp || 0));

    if (existing.length > MAX_HISTORY_ITEMS) {
        existing.splice(MAX_HISTORY_ITEMS);
    }

    saveDeviceHistory(existing);
    window.dispatchEvent(new CustomEvent("obsidianscout:device-history-changed"));
    return addedCount;
}

function getActionLabel(action) {
    switch (action) {
        case "upload": return "Online Upload";
        case "offline_save": return "Saved Offline";
        case "qr_generated": return "QR Generated";
        case "json_export": return "Exported JSON";
        case "qr_scanned": return "QR Scanned";
        default: return action;
    }
}

function getFormLabel(formType) {
    switch (formType) {
        case "scout":
        case "match":
        case "match-scout":
        case "match-scouting": return "Match Scouting";
        case "pit":
        case "pit-scout":
        case "pit-scouting": return "Pit Scouting";
        case "qual":
        case "qual-scout":
        case "qualitative-scouting":
        case "qual-scouting": return "Qualitative Scouting";
        case "prescout-scout":
        case "prescout-match":
        case "prescout-scouting": return "Prescout Match";
        case "prescout-pit":
        case "prescout-pit-scouting": return "Prescout Pit";
        case "prescout-qual":
        case "prescout-qual-scouting": return "Prescout Qualitative";
        default: return formType;
    }
}
