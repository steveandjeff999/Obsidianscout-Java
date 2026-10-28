/**
 * Utility Wake Lock Module - ObsidianScout
 * Keeps the screen awake on scouting pages so phones don't dim or lock mid-match.
 */

import { safeGetItem } from '../base/storage.js';

export const KEEP_SCREEN_ON_KEY = "obsidianscout:keep_screen_on";

let sentinel = null;
let wanted = false;
let listenerAdded = false;

function isEnabledByUser() {
    return (safeGetItem(KEEP_SCREEN_ON_KEY) || "enabled") === "enabled";
}

async function acquire() {
    if (!wanted || sentinel || document.visibilityState !== "visible") return;
    try {
        sentinel = await navigator.wakeLock.request("screen");
        sentinel.addEventListener("release", () => {
            sentinel = null;
        });
    } catch (e) {
        // Denied (battery saver, insecure origin, etc.); nothing else to do.
        console.warn("Screen wake lock unavailable:", e);
    }
}

/**
 * Holds a screen wake lock for the rest of the page's life. The browser drops the
 * lock whenever the tab is hidden, so it is re-acquired when the tab is shown again.
 */
export function keepScreenAwake() {
    if (typeof navigator === "undefined" || !("wakeLock" in navigator)) return;
    if (!isEnabledByUser()) return;
    wanted = true;
    if (!listenerAdded) {
        listenerAdded = true;
        document.addEventListener("visibilitychange", acquire);
    }
    acquire();
}

export async function releaseScreenAwake() {
    wanted = false;
    if (sentinel) {
        try {
            await sentinel.release();
        } catch (e) {
            // Already released.
        }
        sentinel = null;
    }
}
