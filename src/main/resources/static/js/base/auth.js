/**
 * Base Auth Module - ObsidianScout
 * Session verification, user info fetching, route authentication gating, role checks, and logout.
 */

import { safeGetItem, safeRemoveItem } from './storage.js';
import { request } from './http.js';

export const ROLE_HIERARCHY = ["SUPERADMIN", "ADMIN", "ANALYTICS", "SCOUT"];

export async function checkLoginStatus() {
    try {
        const response = await fetch("/api/auth/status", {
            method: "GET",
            credentials: "same-origin",
            headers: { "Accept": "application/json" }
        });
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
    try {
        const result = await request("/api/auth/me");
        return result.user;
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

export function isSuperAdmin(role) {
    return role === "SUPERADMIN";
}

export function canAccessAnalytics(role) {
    return hasRole(role, "ANALYTICS");
}

export async function requireAuth() {
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
    const superAdminPages = ["cluster-management", "fcm-settings", "migration"];

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

    if (currentPage && settings && (me.role === "SCOUT" || me.role === "ANALYTICS" || me.role === "ADMIN")) {
        const allowedPages = me.role === "SCOUT" ? settings.scoutPages : (me.role === "ANALYTICS" ? settings.analyticsPages : settings.adminPages);
        if (allowedPages && Array.isArray(allowedPages)) {
            const bypassPages = ["settings", "login", "index", "dashboard", "theme-editor", "team", "cache-manager", "prescout", "prescout-scout", "prescout-pit", "prescout-qual", "reset-password", "docs", "contact", "config-migration", "schema-history"];
            if (!bypassPages.includes(currentPage) && !superAdminPages.includes(currentPage) && !allowedPages.includes(currentPage)) {
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
    if (settings && isAdmin(me.role) && !settings.setupWizardCompleted) {
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

    return me;
}

export function wireLogout() {
    const button = document.querySelector("[data-action='logout']");
    if (!button) {
        return;
    }
    button.addEventListener("click", async () => {
        try {
            await request("/api/auth/logout", { method: "POST" });
            safeRemoveItem("cache:/api/auth/me");
            window.location.href = "/";
        } catch (error) {
            if (window.Obsidianscout && typeof window.Obsidianscout.showToast === 'function') {
                window.Obsidianscout.showToast(error.message || "Failed to sign out", "error");
            }
        }
    });
}
