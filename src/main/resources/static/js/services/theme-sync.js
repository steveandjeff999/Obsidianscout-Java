/**
 * Service Theme-Sync Module - ObsidianScout
 * Keeps the team's custom theme current on open pages, so a theme an admin saves reaches
 * teammates without them having to reload or navigate.
 */

import { request } from '../base/http.js';
import { checkLoginStatus } from '../base/auth.js';
import { applyCustomTheme } from '../layout/theme.js';

const THEME_STORAGE_KEY = "obsidian-custom-theme-config";
const THEME_POLL_INTERVAL_MS = 15000;

export async function initThemeSync() {
    const page = document.body.dataset.page;
    if (page === "login" || page === "reset-password") return;

    // Another tab in this browser picked up a new theme: apply it here too.
    window.addEventListener("storage", (e) => {
        if (e.key !== THEME_STORAGE_KEY || !e.newValue) return;
        try {
            applyCustomTheme(JSON.parse(e.newValue));
        } catch (err) {
            console.warn("[Theme Sync] Ignoring unreadable theme from another tab:", err);
        }
    });

    const loggedIn = await checkLoginStatus();
    if (!loggedIn) return;

    let pollInterval = null;

    async function refreshTheme() {
        if (document.visibilityState !== "visible" || !navigator.onLine) return;
        try {
            // ETag-validated, so an unchanged theme costs a 304. request() only applies the theme
            // on a 200, so apply it here as well in case another tab refreshed the shared cache first.
            const response = await request("/api/settings", { timeoutMs: 5000 });
            const settings = response && (response.settings || response);
            if (settings && settings.theme !== undefined) {
                applyCustomTheme(settings);
            }
        } catch (e) {
            if (e.status === 401 && pollInterval) {
                clearInterval(pollInterval);
                pollInterval = null;
            }
        }
    }

    pollInterval = setInterval(refreshTheme, THEME_POLL_INTERVAL_MS);

    // Catch up as soon as a backgrounded tab is looked at again.
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible" && pollInterval) {
            refreshTheme();
        }
    });
}
