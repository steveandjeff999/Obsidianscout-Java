/**
 * Layout Navigation Module - ObsidianScout
 * Nav link active state highlighting, role-based link filtering, avatar refresh, user badge rendering, and dynamic sidebar shell hydration.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';
import { isAdmin, isSuperAdmin } from '../base/auth.js';
import { wireThemeToggle } from './theme.js';

export let lastUser = null;

export function setUserBadge(user) {
    if (!user && lastUser) {
        user = lastUser;
    }
    if (!user) return;
    lastUser = user;

    const roleLabel = user.role === "SUPERADMIN" ? "Site Admin" : user.role.charAt(0) + user.role.slice(1).toLowerCase();

    // Update brand to show program type when in standard sidebar mode
    const brand = document.querySelector(".sidebar-brand");
    if (brand && !document.body.classList.contains("nav-layout-topbar") && !brand.textContent.endsWith(user.program)) {
        brand.textContent = `ObsidianScout ${user.program}`;
    }

    // Build avatar element
    const initials = (user.username || "?").slice(0, 2).toUpperCase();
    // Pick a deterministic hue from the username
    let hue = 0;
    for (let i = 0; i < (user.username || "").length; i++) {
        hue = (hue + (user.username || "").charCodeAt(i) * 37) % 360;
    }

    let avatarHtml;
    if (user.profilePicture) {
        avatarHtml = `<img class="nav-avatar" src="${user.profilePicture}" alt="${initials}" title="${user.username}">`;
    } else {
        avatarHtml = `<div class="nav-avatar nav-avatar-initials" style="--avatar-hue:${hue}deg" title="${user.username}">${initials}</div>`;
    }

    const badge = document.getElementById("nav-user");
    if (badge) {
        badge.innerHTML = `
            <a class="nav-avatar-link" href="/config" aria-label="Edit profile picture">${avatarHtml}</a>
            <div class="nav-user-text">
                <span class="nav-user-name" title="${user.username}">${user.username}</span>
                <span class="nav-user-meta">${user.program} Team ${user.teamNumber} • ${roleLabel}</span>
            </div>
        `;
    }

    const topbarUsername = document.getElementById("topbar-account-username");
    if (topbarUsername) {
        topbarUsername.textContent = user.username;
    }
    const topbarAvatar = document.getElementById("topbar-account-avatar");
    if (topbarAvatar) {
        topbarAvatar.innerHTML = avatarHtml;
    }
    const topbarUserCard = document.getElementById("topbar-user-card-content");
    if (topbarUserCard) {
        topbarUserCard.innerHTML = `
            <a class="nav-avatar-link" href="/config" aria-label="Edit profile picture">${avatarHtml}</a>
            <div class="topbar-user-details">
                <span class="topbar-user-name" title="${user.username}">${user.username}</span>
                <span class="topbar-user-meta">${user.program} Team ${user.teamNumber} • ${roleLabel}</span>
            </div>
        `;
    }

    const apiAttribution = document.getElementById("api-attribution");
    if (apiAttribution) {
        if (user.program === "FTC") {
            apiAttribution.innerHTML = `Match data provided by:<br><a href="https://ftc-events.firstinspires.org/services/API" target="_blank" rel="noopener noreferrer">FIRST FTC API</a> and <a href="https://ftcscout.org/api" target="_blank" rel="noopener noreferrer">FTC Scout API</a>`;
        } else {
            apiAttribution.innerHTML = `Match data provided by:<br><a href="https://frc-events.firstinspires.org/services/api" target="_blank" rel="noopener noreferrer">FIRST FRC API</a> and <a href="https://www.thebluealliance.com/apidocs" target="_blank" rel="noopener noreferrer">The Blue Alliance API</a>.<br>EPA provided by <a href="https://www.statbotics.io/docs/rest" target="_blank" rel="noopener noreferrer">Statbotics</a>.`;
        }
    }
}

/**
 * Updates the sidebar avatar after a profile picture change without a full page reload.
 * @param {string|null} profilePicture - New picture data-URL, or null to revert to initials.
 */
export function refreshNavAvatar(profilePicture) {
    const badge = document.getElementById("nav-user");
    if (!badge) return;
    const link = badge.querySelector(".nav-avatar-link");
    if (!link) return;
    const existing = link.querySelector(".nav-avatar, .nav-avatar-initials");
    if (!existing) return;

    if (profilePicture) {
        const img = document.createElement("img");
        img.className = "nav-avatar";
        img.src = profilePicture;
        img.alt = "avatar";
        existing.replaceWith(img);
    } else {
        // Revert to initials bubble — read initials from current text
        const nameEl = badge.querySelector(".nav-user-name");
        const textEl = badge.querySelector(".nav-user-text");
        let username = "?";
        if (nameEl) {
            username = nameEl.textContent.trim();
        } else if (textEl) {
            username = textEl.textContent.split("|")[0].trim();
        }
        const initials = (username || "?").slice(0, 2).toUpperCase();
        let hue = 0;
        for (let i = 0; i < username.length; i++) {
            hue = (hue + username.charCodeAt(i) * 37) % 360;
        }
        const div = document.createElement("div");
        div.className = "nav-avatar nav-avatar-initials";
        div.style.setProperty("--avatar-hue", hue + "deg");
        div.title = username;
        div.textContent = initials;
        existing.replaceWith(div);
    }
}

export function setActiveNav() {
    const page = document.body.dataset.page;
    if (!page) {
        return;
    }
    document.querySelectorAll(".nav-link, .sidebar-link").forEach((link) => {
        if (link.dataset.page === page) {
            link.classList.add("active");
        }
    });
    document.querySelectorAll(".topbar-dropdown").forEach((dropdown) => {
        const hasActive = dropdown.querySelector(".sidebar-link.active") !== null;
        const btn = dropdown.querySelector(".topbar-dropdown-btn");
        if (btn) {
            btn.classList.toggle("active-category", hasActive);
        }
    });
}

/**
 * Adjusts sidebar navigation visibility based on user role.
 */
export function adjustNavForRole(user) {
    if (!user) return;
    const role = user.role;
    const superAdminPages = ["cluster-management", "storage-manager", "fcm-settings", "migration"];

    // Superadmin-only pages: show only for SUPERADMIN
    superAdminPages.forEach((page) => {
        document.querySelectorAll(`.sidebar-link[data-page="${page}"]`).forEach((link) => {
            link.style.display = isSuperAdmin(role) ? "" : "none";
        });
    });

    // Hide Admin-only links for SCOUT and ANALYTICS
    if (!isAdmin(role)) {
        document.querySelectorAll('.sidebar-link[data-page="users"]').forEach((link) => {
            link.style.display = "none";
        });
        document.querySelectorAll('.sidebar-link[data-page="banners"]').forEach((link) => {
            link.style.display = "none";
        });
        document.querySelectorAll('.sidebar-link[data-page="admin-settings"]').forEach((link) => {
            link.style.display = "none";
        });
        document.querySelectorAll('.sidebar-link[data-page="default-configs"]').forEach((link) => {
            link.style.display = "none";
        });
    }

    // Hide links based on dynamic role permissions list
    if (role === "SCOUT" || role === "ANALYTICS" || role === "ADMIN") {
        try {
            const settingsText = safeGetItem("cache:/api/settings");
            if (settingsText) {
                const parsed = JSON.parse(settingsText);
                const settings = parsed.settings || parsed;
                const allowedPages = role === "SCOUT" ? settings.scoutPages : (role === "ANALYTICS" ? settings.analyticsPages : settings.adminPages);
                if (allowedPages && Array.isArray(allowedPages)) {
                    document.querySelectorAll('.sidebar-link[data-page]').forEach((link) => {
                        const page = link.dataset.page;
                        const bypassPages = ["settings", "login", "index", "theme-editor", "team", "cache-manager", "prescout", "prescout-scout", "prescout-pit", "prescout-qual", "reset-password", "docs", "contact", "config-migration", "schema-history"];
                        if (!bypassPages.includes(page) && !superAdminPages.includes(page) && !allowedPages.includes(page)) {
                            link.style.display = "none";
                        }
                    });
                }
            }
        } catch (err) {
            console.error("Failed to parse settings for dynamic nav adjust:", err);
        }
    }

    // Clean up empty section headers in sidebar
    document.querySelectorAll('.sidebar-section-title').forEach((titleEl) => {
        let nextEl = titleEl.nextElementSibling;
        let hasVisibleLink = false;
        while (nextEl && !nextEl.classList.contains('sidebar-section-title')) {
            if (nextEl.classList.contains('sidebar-link') && nextEl.style.display !== "none") {
                hasVisibleLink = true;
                break;
            }
            nextEl = nextEl.nextElementSibling;
        }
        titleEl.style.display = hasVisibleLink ? "" : "none";
    });

    document.querySelectorAll('.topbar-dropdown').forEach((dropdown) => {
        const menu = dropdown.querySelector('.topbar-dropdown-menu');
        if (!menu) return;
        const visibleLinks = Array.from(menu.querySelectorAll('.sidebar-link')).filter((link) => link.style.display !== "none");
        if (visibleLinks.length === 0 && !dropdown.classList.contains('topbar-account-dropdown')) {
            dropdown.style.display = "none";
        } else {
            dropdown.style.display = "";
        }
    });
}

export function isPageAccessible(page, role) {
    if (isSuperAdmin(role)) return true;
    const bypassPages = ["dashboard", "settings", "login", "index", "theme-editor"];
    if (bypassPages.includes(page)) return true;

    if (["users", "banners", "admin-settings", "default-configs"].includes(page) && !isAdmin(role)) {
        return false;
    }
    if (page === "migration" && !isSuperAdmin(role)) {
        return false;
    }

    try {
        const settingsText = safeGetItem("cache:/api/settings");
        if (settingsText) {
            const parsed = JSON.parse(settingsText);
            const settings = parsed.settings || parsed;
            const allowedPages = role === "SCOUT" ? settings.scoutPages : (role === "ANALYTICS" ? settings.analyticsPages : settings.adminPages);
            if (allowedPages && Array.isArray(allowedPages)) {
                return allowedPages.includes(page);
            }
        }
    } catch (e) {}

    const link = document.querySelector(`.sidebar-link[data-page="${page}"]`);
    if (link && link.style.display === "none") {
        return false;
    }

    return true;
}

export async function ensureSidebarAndFooter(sidebar) {
    if (!sidebar) return;
    if (!sidebar.querySelector(".sidebar-nav")) {
        console.log("[Sidebar] Sidebar is empty, loading base template...");
        let baseHtml = sessionStorage.getItem("obsidianscout:base_html");
        if (!baseHtml) {
            baseHtml = safeGetItem("obsidianscout:base_html");
        }
        if (!baseHtml) {
            try {
                const res = await fetch("/base.html");
                if (res.ok) {
                    baseHtml = await res.text();
                    sessionStorage.setItem("obsidianscout:base_html", baseHtml);
                    safeSetItem("obsidianscout:base_html", baseHtml);
                }
            } catch (e) {
                console.warn("[Sidebar] Failed to fetch sidebar base template:", e);
            }
        }

        if (baseHtml) {
            console.log("[Sidebar] Successfully acquired baseHtml template.");
            const tempDiv = document.createElement("div");
            tempDiv.innerHTML = baseHtml;
            const templateSidebar = tempDiv.querySelector(".sidebar");
            if (templateSidebar) {
                sidebar.innerHTML = templateSidebar.innerHTML;
                console.log("[Sidebar] Injected base template innerHTML into sidebar.");
                
                // Re-apply user badge if cached user info is available
                try {
                    const meText = safeGetItem("cache:/api/auth/me");
                    if (meText) {
                        const parsed = JSON.parse(meText);
                        const user = parsed.user || parsed;
                        if (user) {
                            setUserBadge(user);
                        }
                    }
                } catch (e) {
                    console.warn("Failed to restore user badge on dynamic sidebar load", e);
                }
                
                // Restore active nav highlight
                setActiveNav();
                wireThemeToggle(sidebar);
                if (window.Obsidianscout && typeof window.Obsidianscout.renderServerVersion === 'function') {
                    window.Obsidianscout.renderServerVersion(sidebar);
                }
            }
        }
    }
}
