/**
 * Base Auth Module - ObsidianScout
 * Session verification, user info fetching, route authentication gating, role checks, and logout.
 */

import { safeGetItem, safeSetItem, safeRemoveItem } from './storage.js';
import { request, getCachedData } from './http.js';
import { clearAllHttpCaches } from './idb-cache.js';
import { loadAndRenderBanners } from '../components/banners.js';
import { isPublicMode, makePublicUser } from './public-mode.js';

export const ROLE_HIERARCHY = ["SUPERADMIN", "ADMIN", "ANALYTICS", "CUSTOM", "SCOUT"];

/** Roles whose pages come from the team settings: a role page list, or for CUSTOM the user's own picks. */
export const PAGE_LIST_ROLES = ["SCOUT", "CUSTOM", "ANALYTICS", "ADMIN"];

export function allowedPagesForRole(role, settings) {
    if (!settings) return null;
    if (role === "SCOUT") return settings.scoutPages;
    if (role === "CUSTOM") return settings.customPages || ["dashboard", "my-assignments"];
    if (role === "ANALYTICS") return settings.analyticsPages;
    return settings.adminPages;
}

export async function checkLoginStatus() {
    if (isPublicMode()) return true;
    // Server already known to be unreachable: don't hold the page for a check that can only time out. The cached
    // sign-in stands until the server answers again.
    try {
        const knownOffline = window.Obsidianscout && typeof window.Obsidianscout.isServerOnline === 'function' &&
            !window.Obsidianscout.isServerOnline();
        if (knownOffline && safeGetItem("cache:/api/auth/me")) return true;
    } catch (e) { /* fall through to the real check */ }
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        const response = await fetch("/api/auth/status", {
            method: "GET",
            credentials: "same-origin",
            headers: { "Accept": "application/json" },
            signal: controller.signal
        }).finally(() => clearTimeout(timeoutId));

        if (response.status === 401) {
            return false;
        }
        if (!response.ok) {
            const cachedMe = safeGetItem("cache:/api/auth/me");
            if (cachedMe) {
                console.warn("[Offline Cache] Server status check failed (not 401). Assuming logged in from cache.");
                return true;
            }
            return false;
        }
        const data = await response.json();
        return !!(data && data.loggedIn);
    } catch (error) {
        const cachedMe = safeGetItem("cache:/api/auth/me");
        if (cachedMe) {
            console.warn("[Offline Cache] Network check failed. Assuming logged in from cache.", error);
            return true;
        }
        return false;
    }
}

export async function getMe() {
    if (isPublicMode()) return makePublicUser();
    try {
        const result = await request("/api/auth/me");
        return result ? (result.user || result) : null;
    } catch (error) {
        return null;
    }
}

/**
 * Checks if the user's role is at least the required level.
 * E.g. hasRole("ADMIN", "ADMIN") = true
 *      hasRole("SUPERADMIN", "ADMIN") = true
 *      hasRole("SCOUT", "ADMIN") = false
 */
export function hasRole(userRole, requiredRole) {
    const userIdx = ROLE_HIERARCHY.indexOf(userRole);
    const reqIdx = ROLE_HIERARCHY.indexOf(requiredRole);
    if (userIdx === -1 || reqIdx === -1) return false;
    return userIdx <= reqIdx;
}

export function isAdmin(role) {
    return hasRole(role, "ADMIN");
}

/** Team admin pages a CUSTOM user can be given (matches CUSTOM_ROLE_ADMIN_PAGES on the server). */
export const CUSTOM_ADMIN_PAGES = ["assignments", "admin-settings", "users", "scouter-report", "banners"];

/** The cached team settings, or null. */
function cachedSettings() {
    try {
        const text = safeGetItem("cache:/api/settings");
        if (!text) return null;
        const parsed = JSON.parse(text);
        return parsed.settings || parsed;
    } catch (e) {
        return null;
    }
}

/** Whether [role] may use the team admin page [page]: admins always, a CUSTOM user when it was picked for them. */
export function canUseAdminPage(role, page) {
    if (isAdmin(role)) return true;
    if (role !== "CUSTOM" || !CUSTOM_ADMIN_PAGES.includes(page)) return false;
    const settings = cachedSettings();
    return Boolean(settings && Array.isArray(settings.customPages) && settings.customPages.includes(page));
}

export function isSuperAdmin(role) {
    return role === "SUPERADMIN";
}

export function canAccessAnalytics(role) {
    return hasRole(role, "ANALYTICS");
}

export async function requireAuth() {
    // Public data views need no sign-in: pages run as a read-only stand-in for the owner team.
    if (isPublicMode()) return makePublicUser();
    const loggedIn = await checkLoginStatus();
    if (!loggedIn) {
        window.location.href = "/";
        return null;
    }
    const me = await getMe();
    if (!me) {
        return null;
    }

    // Pre-fetch settings to ensure local cache is populated for role-based navigation checks
    let settings = null;
    try {
        const response = await request("/api/settings");
        settings = response.settings || response;
    } catch (e) {
        console.warn("Failed to pre-fetch settings for role adjustments:", e);
        try {
            const cachedText = safeGetItem("cache:/api/settings");
            if (cachedText) {
                const parsed = JSON.parse(cachedText);
                settings = parsed.settings || parsed;
            }
        } catch (err) {}
    }

    // Verify page-level access permissions
    const currentPage = typeof document !== 'undefined' && document.body && document.body.getAttribute("data-page");
    const superAdminPages = ["cluster-management", "storage-manager", "error-reports", "fcm-settings", "migration"];

    const showToast = (msg, tone) => {
        if (window.Obsidianscout && typeof window.Obsidianscout.showToast === 'function') {
            window.Obsidianscout.showToast(msg, tone);
        }
    };

    if (currentPage && me) {
        if (superAdminPages.includes(currentPage) && !isSuperAdmin(me.role)) {
            showToast("Superadmin access required for this page", "error");
            const fallback = "/dashboard";
            setTimeout(() => {
                window.location.href = fallback;
            }, 500);
            return null;
        }
    }

    if (currentPage && settings && PAGE_LIST_ROLES.includes(me.role)) {
        const allowedPages = allowedPagesForRole(me.role, settings);
        if (allowedPages && Array.isArray(allowedPages)) {
            const bypassPages = ["settings", "login", "index", "dashboard", "theme-editor", "team", "reset-password", "config-migration", "schema-history", "tutorials", "my-assignments", "assistant", "match-checklist", "battery-tracker", "match-predictions", "prediction-leaderboard"];
            if (isAdmin(me.role) || isSuperAdmin(me.role)) {
                bypassPages.push("assignments");
            }
            const isAllowed = bypassPages.includes(currentPage) || allowedPages.includes(currentPage) ||
                (currentPage === "cache-manager" && (allowedPages.includes("cache-manager") || allowedPages.includes("scout-history") || allowedPages.includes("history")));
            if (!superAdminPages.includes(currentPage) && !isAllowed) {
                showToast("You do not have access to this page", "error");
                const fallback = allowedPages.includes("dashboard") ? "/dashboard" : "/config";
                setTimeout(() => {
                    window.location.href = fallback;
                }, 500);
                return null;
            }
        }
    }

    try {
        if (window.Obsidianscout && typeof window.Obsidianscout.initTour === 'function') {
            window.Obsidianscout.initTour(me);
        }
    } catch (e) {
        console.warn("Failed to initialize ObsidianScout Tour:", e);
    }

    // Setup Wizard Auto Trigger
    // Re-sync localStorage with the server's authoritative flag. If the server already
    // knows setup is complete, write the dismiss key now so a cleared cache (or a new
    // device/browser) never causes the wizard to re-appear spuriously.
    if (settings && settings.setupWizardCompleted) {
        safeSetItem("obsidianscout:setup-wizard-dismissed", "true");
    }
    const wizardDismissedLocally = safeGetItem("obsidianscout:setup-wizard-dismissed") === "true";
    if (settings && isAdmin(me.role) && !settings.setupWizardCompleted && !wizardDismissedLocally) {
        const bypassPages = ["login", "index", "reset-password", "migration"];
        if (currentPage && !bypassPages.includes(currentPage)) {
            setTimeout(() => {
                if (!document.getElementById("setup-wizard-backdrop")) {
                    if (window.Obsidianscout && typeof window.Obsidianscout.showSetupWizardModal === 'function') {
                        window.Obsidianscout.showSetupWizardModal(me, settings);
                    }
                }
            }, 400);
        }
    }

    // Re-verify banners once authenticated
    loadAndRenderBanners();

    return me;
}

/**
 * Call after a successful sign-in with the response from the login endpoint. If a different
 * account than the last cached one signed in, cached API responses from the previous account
 * are cleared so they can't be shown offline. The same account keeps its offline cache.
 */
export async function resetCachesForUser(loginResponse) {
    try {
        const newUserId = loginResponse?.user?.userId;
        const previousUserId = getCachedData("/api/auth/me")?.user?.userId;
        if (!newUserId || newUserId !== previousUserId) {
            await clearAllHttpCaches(false);
        }
    } catch (e) {
        console.warn("[Auth] Failed to reset caches after sign-in:", e);
    }
}

export function wireLogout() {
    const button = document.querySelector("[data-action='logout']");
    if (!button) {
        return;
    }
    button.addEventListener("click", async () => {
        // Entries still on this device stay queued after signing out; say so before the scout walks away.
        try {
            const summary = window.Obsidianscout && typeof window.Obsidianscout.getPendingSummary === 'function'
                ? window.Obsidianscout.getPendingSummary() : null;
            const waiting = summary ? summary.total - (summary.otherTeam || 0) : 0;
            if (waiting > 0) {
                const message = `${waiting} ${waiting === 1 ? "entry saved on this device hasn't" : "entries saved on this device haven't"} uploaded yet.\n\n` +
                    `If you sign out now, ${waiting === 1 ? "it stays" : "they stay"} on this device and ${waiting === 1 ? "uploads" : "upload"} the next time someone from your team signs in here. ` +
                    `To upload ${waiting === 1 ? "it" : "them"} now, cancel and press Sync while connected.\n\nSign out anyway?`;
                if (!window.confirm(message)) return;
            }
        } catch (e) { /* never block signing out */ }
        try {
            await request("/api/auth/logout", { method: "POST" });
            // Shared tablets: don't leave this account's cached data for the next person.
            // Offline submission queues and form drafts are stored separately and are kept.
            await clearAllHttpCaches(false);
            safeRemoveItem("cache:/api/auth/me");
            window.location.href = "/";
        } catch (error) {
            if (window.Obsidianscout && typeof window.Obsidianscout.showToast === 'function') {
                window.Obsidianscout.showToast(error.message || "Failed to sign out", "error");
            }
        }
    });
}
