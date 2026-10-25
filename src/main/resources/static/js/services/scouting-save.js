/**
 * Service Scouting-Save Module - ObsidianScout
 * The one save path every scouting form uses: try the server, and when it can't take the entry right now keep it in
 * the on-device queue instead, then tell the scout exactly where it went.
 */

import { request, isNetworkError } from '../base/http.js';
// Namespace import: right after a deploy the previous service worker can still hand out the previous offline-sync.js,
// which lacks the newest exports; a missing named export would stop every page from loading.
import * as offlineSync from './offline-sync.js';
import { recordDeviceHistory } from './device-history.js';
import { showSaveReceipt } from '../components/save-receipt.js';
import { updateConnectionStatus } from '../components/connection-widget.js';

const { CACHE_CONFIGS, enqueueOfflineEntries } = offlineSync;
const newClientId = offlineSync.newClientId || (() => (typeof crypto !== 'undefined' && crypto.randomUUID)
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`);
const setSignInNeededForUpload = offlineSync.setSignInNeededForUpload || (() => {});
const isSignInNeededForUpload = offlineSync.isSignInNeededForUpload || (() => false);

/**
 * True when a failed upload should be kept on the device and retried rather than shown as an error: the server
 * couldn't be reached, timed out, was overloaded or broke (5xx), or the sign-in expired. Other 4xx answers mean the
 * entry itself was refused.
 */
export function shouldKeepOnDevice(error) {
    if (!error) return false;
    if (isNetworkError(error)) return true;
    const status = error.status;
    if (!status) return error.name === "AbortError";
    return status === 401 || status === 408 || status === 429 || status >= 500;
}

function historyFor(kind, data, scoutName, action, extra) {
    return {
        action,
        formType: kind,
        eventKey: data.eventKey,
        teamNumber: data.targetTeamNumber != null ? data.targetTeamNumber : data.teamNumber,
        matchKey: data.matchKey || null,
        matchNumber: data.matchNumber != null ? data.matchNumber : null,
        scoutName: scoutName || null,
        payload: data,
        ...extra
    };
}

/**
 * Saves one or more entries of one kind ("match-scouting", "pit-scouting", ...).
 *
 * options:
 *   kind       CACHE_CONFIGS key; picks the endpoint and the queue.
 *   entries    payloads (what goes in `data`).
 *   mode       "auto" (server first, device as fallback) or "local" (device only).
 *   endpoint   overrides the kind's endpoint for the direct upload (e.g. a batch endpoint).
 *   body       (items) => JSON to send for the direct upload; defaults to the single queued item.
 *   itemExtras fields stored next to `data` on each queued item.
 *   scoutName  for the device history.
 *   button     submit button to show as busy while uploading.
 *   loadingText
 *   quiet      skip the receipt (the caller shows its own).
 *
 * Resolves to { where, response, error, items }, where `where` is "server", "local", "rejected" or "not-saved".
 * The form should be cleared only for "server" and "local".
 */
export async function saveScoutingEntries(options) {
    const {
        kind,
        entries,
        mode = "auto",
        endpoint,
        body,
        itemExtras = {},
        scoutName = null,
        button = null,
        loadingText = null,
        quiet = false
    } = options || {};
    const config = CACHE_CONFIGS[kind];
    if (!config) throw new Error(`Unknown scouting kind: ${kind}`);
    const list = (entries || []).filter(Boolean);
    const createdAt = new Date().toISOString();
    // The id goes up with the first attempt too, so a retry of an upload that timed out is recognised.
    const items = list.map((data) => ({ ...itemExtras, data, clientId: newClientId(), createdAt }));
    const receipt = (where, extra = {}) => {
        if (!quiet) showSaveReceipt({ where, kindLabel: config.label, entries: list, ...extra });
    };

    const keepOnDevice = async (reason) => {
        const signInNeeded = (reason && reason.status === 401) || isSignInNeededForUpload();
        if (reason && reason.status === 401) setSignInNeededForUpload(true);
        if (!(await enqueueOfflineEntries(config.key, items))) {
            receipt("not-saved");
            return { where: "not-saved", error: reason || null, items };
        }
        list.forEach((data) => {
            try {
                recordDeviceHistory(historyFor(kind, data, scoutName, "offline_save", {
                    actionLabel: mode === "local" ? undefined : "Offline Fallback",
                    serverSynced: false
                }));
            } catch (e) { /* history is a convenience */ }
        });
        receipt("local", { signInNeeded });
        try { updateConnectionStatus(); } catch (e) { /* ignore */ }
        return { where: "local", error: reason || null, items };
    };

    if (!items.length) return { where: "not-saved", items };
    if (mode === "local") return keepOnDevice(null);

    let response;
    try {
        response = await request(endpoint || config.endpoint, {
            method: "POST",
            queueable: true,
            json: body ? body(items) : items[0],
            button: button || undefined,
            loadingText: loadingText || undefined
        });
    } catch (error) {
        if (shouldKeepOnDevice(error)) return keepOnDevice(error);
        receipt("rejected", { message: (error && error.message) || "" });
        return { where: "rejected", error, items };
    }

    setSignInNeededForUpload(false);
    const syncedAt = new Date().toISOString();
    list.forEach((data) => {
        try {
            recordDeviceHistory(historyFor(kind, data, scoutName, "upload", { serverSynced: true, syncedAt }));
        } catch (e) { /* history is a convenience */ }
    });
    receipt("server");
    return { where: "server", response, items };
}

export function saveScoutingEntry(options) {
    const { entry, ...rest } = options || {};
    return saveScoutingEntries({ ...rest, entries: [entry] });
}
