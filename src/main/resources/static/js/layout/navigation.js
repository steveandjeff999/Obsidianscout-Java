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
    if (brand && !document.body.classList.contains("nav-layout-topbar")) {
        const brandTextEl = brand.querySelector(".sidebar-brand-text");
        if (brandTextEl && !brandTextEl.textContent.endsWith(user.program)) {
            brandTextEl.textContent = `ObsidianScout ${user.program}`;
        } else if (!brandTextEl && !brand.textContent.endsWith(user.program)) {
            brand.textContent = `ObsidianScout ${user.program}`;
        }
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

export const PAGE_SEARCH_KEYWORDS = {
    "dashboard": ["home", "main", "overview", "summary", "inicio", "panel"],
    "my-assignments": ["tasks", "todo", "assigned", "schedule", "mis tareas", "asignaciones"],
    "scout": ["match scouting", "form", "entry", "submit", "partido", "formulario"],
    "pit-scout": ["pits", "inspection", "robot specs", "dimensions", "inspeccion", "foso"],
    "qual-scout": ["qualitative", "subjective", "notes", "defense", "driver", "cualitativo", "notas"],
    "prescout": ["pre-scouting", "past events", "videos", "research", "pre-scout"],
    "qr-scanner": ["camera", "scan", "jab", "qr code", "offline sync", "transfer", "escaner"],
    "cache-manager": ["history", "offline storage", "cache", "submissions", "entries", "historial"],
    "custom-analytics": ["custom charts", "builder", "metrics", "custom tables", "sql", "tablas", "graficos"],
    "data-validation": ["verify", "audit", "anomalies", "errors", "fix data", "validacion", "auditoria"],
    "all-data": ["raw entries", "table", "export", "csv", "spreadsheet", "todos los datos"],
    "match-data": ["breakdown", "match entries", "scores", "datos de partidos"],
    "qual-data": ["qualitative notes", "ratings", "comments", "datos cualitativos"],
    "pit-data": ["pit entries", "photos", "specifications", "datos de pits"],
    "analytics": ["graphs", "averages", "leaderboard", "opr", "epa", "analiticas"],
    "compare": ["comparison", "head to head", "compare teams", "side by side", "comparar"],
    "graphs": ["charts", "scatter", "box plot", "bar chart", "trends", "graficos"],
    "teams": ["team list", "roster", "profiles", "lookup", "equipos"],
    "rankings": ["standings", "leaderboard", "rank", "rp", "ranking points", "clasificacion"],
    "projected-rankings": ["projections", "forecast", "future standings", "simulations", "clasificacion proyectada"],
    "qual-rankings": ["driver rankings", "defense rankings", "qual ratings", "ranking cualitativo"],
    "matches": ["match schedule", "results", "scores", "partidos", "horario"],
    "predictor": ["match predictor", "win probability", "score prediction", "pronostico"],
    "event-predictor": ["event simulation", "playoffs", "bracket", "pronostico de evento"],
    "alliances": ["alliance selection", "partners", "captain", "picks", "alianzas"],
    "alliance-selection": ["draft board", "pick list", "captain board", "seleccion de alianzas"],
    "match-planning": ["strategy", "whiteboard", "game plan", "match plan", "planificacion"],
    "events": ["event list", "competitions", "regional", "district", "eventos"],
    "assignments": ["scout assignments", "matrix", "scheduler", "assign scouts", "asignar scouts"],
    "users": ["accounts", "roster", "roles", "permissions", "team members", "usuarios"],
    "chat": ["messages", "team chat", "conversations", "mensajes"],
    "assistant": ["ai", "local ai", "zachary", "bot", "scouting assistant", "llm", "asistente", "ia"],
    "banners": ["announcements", "notifications", "alerts", "notice", "avisos"],
    "shared-links": ["public links", "external sharing", "enlaces compartidos"],
    "admin-settings": ["admin", "system settings", "permissions", "security", "configuracion de admin"],
    "cluster-management": ["server nodes", "cluster", "hosts", "servidores"],
    "storage-manager": ["database storage", "files", "backups", "disk", "almacenamiento"],
    "error-reports": ["crash logs", "client errors", "diagnostics", "reportes de errores"],
    "fcm-settings": ["push notifications", "firebase", "fcm", "notificaciones"],
    "default-configs": ["schema presets", "game config", "form config", "configuraciones predeterminadas"],
    "settings": ["preferences", "config", "dark mode", "theme", "language", "color", "configuracion"],
    "backup": ["export", "import", "data sharing", "json export", "copia de seguridad"],
    "migration": ["database migration", "postgres", "sqlite", "transfer", "migracion"],
    "tutorials": ["onboarding", "guide", "walkthrough", "help", "tutoriales", "guias"],
    "docs": ["documentation", "api docs", "manual", "documentacion"],
    "contact": ["support", "email", "bug report", "feedback", "contacto"]
};

/**
 * Updates sidebar section header visibility based on whether they have visible link children.
 */
export function updateSectionHeaderVisibility(sidebar) {
    const root = sidebar || document;
    root.querySelectorAll('.sidebar-section-title').forEach((titleEl) => {
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
}

/**
 * Adjusts sidebar navigation visibility based on user role.
 */
export function adjustNavForRole(user) {
    if (!user) return;
    const role = user.role;
    const superAdminPages = ["cluster-management", "storage-manager", "error-reports", "fcm-settings", "migration", "default-configs"];

    // Reset all standard links to visible first before applying role restrictions
    document.querySelectorAll('.sidebar-link[data-page]').forEach((link) => {
        const page = link.dataset.page;
        if (!superAdminPages.includes(page)) {
            link.dataset.roleHidden = "false";
            link.style.display = "";
        }
    });

    // Superadmin-only pages: show only for SUPERADMIN
    superAdminPages.forEach((page) => {
        document.querySelectorAll(`.sidebar-link[data-page="${page}"]`).forEach((link) => {
            const allowed = isSuperAdmin(role);
            link.dataset.roleHidden = (!allowed).toString();
            link.style.display = allowed ? "" : "none";
        });
    });

    // Hide Admin-only links for SCOUT and ANALYTICS
    if (!isAdmin(role)) {
        ["users", "assignments", "banners", "admin-settings", "default-configs"].forEach((page) => {
            document.querySelectorAll(`.sidebar-link[data-page="${page}"]`).forEach((link) => {
                link.dataset.roleHidden = "true";
                link.style.display = "none";
            });
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
                        const bypassPages = ["settings", "login", "index", "theme-editor", "team", "reset-password", "config-migration", "schema-history", "tutorials", "my-assignments", "assistant"];
                        if (isAdmin(role) || isSuperAdmin(role)) {
                            bypassPages.push("assignments");
                        }
                        const isAllowed = bypassPages.includes(page) || allowedPages.includes(page) ||
                            (page === "cache-manager" && (allowedPages.includes("cache-manager") || allowedPages.includes("scout-history") || allowedPages.includes("history")));
                        if (!bypassPages.includes(page) && !superAdminPages.includes(page) && !isAllowed) {
                            link.dataset.roleHidden = "true";
                            link.style.display = "none";
                        }
                    });
                }
            }
        } catch (err) {
            console.error("Failed to parse settings for dynamic nav adjust:", err);
        }
    }

    // The Scouting Assistant is a per-user opt-in (Personal Settings > Local AI Assistant), not a role page.
    document.querySelectorAll('.sidebar-link[data-page="assistant"]').forEach((link) => {
        const allowed = Boolean(user.localAiEnabled);
        link.dataset.roleHidden = (!allowed).toString();
        link.style.display = allowed ? "" : "none";
    });

    // Clean up empty section headers in sidebar
    updateSectionHeaderVisibility(document);

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

    // If a search filter is currently active in the sidebar, re-run filtering to respect updated roles
    const searchInput = document.querySelector(".sidebar-search-input");
    if (searchInput && searchInput.value.trim()) {
        searchInput.dispatchEvent(new Event("input", { bubbles: true }));
    }
}

export function isPageAccessible(page, role) {
    if (isSuperAdmin(role)) return true;
    const bypassPages = ["dashboard", "settings", "login", "index", "theme-editor", "tutorials", "my-assignments", "assistant"];
    if (isAdmin(role)) {
        bypassPages.push("assignments");
    }
    if (bypassPages.includes(page)) return true;

    if (["users", "banners", "admin-settings", "default-configs", "assignments"].includes(page) && !isAdmin(role)) {
        return false;
    }
    if ((page === "migration" || page === "default-configs") && !isSuperAdmin(role)) {
        return false;
    }

    try {
        const settingsText = safeGetItem("cache:/api/settings");
        if (settingsText) {
            const parsed = JSON.parse(settingsText);
            const settings = parsed.settings || parsed;
            const allowedPages = role === "SCOUT" ? settings.scoutPages : (role === "ANALYTICS" ? settings.analyticsPages : settings.adminPages);
            if (allowedPages && Array.isArray(allowedPages)) {
                if (page === "cache-manager" && (allowedPages.includes("cache-manager") || allowedPages.includes("scout-history") || allowedPages.includes("history"))) {
                    return true;
                }
                if (page.startsWith("prescout") && allowedPages.includes("prescout")) {
                    return true;
                }
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

/**
 * Initializes and wires the real-time instant search bar in the sidebar.
 */
export function wireSidebarSearch(sidebar) {
    if (!sidebar) return;
    const searchContainer = sidebar.querySelector(".sidebar-search");
    if (!searchContainer) return;
    if (searchContainer.dataset.wired === "true") return;
    searchContainer.dataset.wired = "true";

    const input = searchContainer.querySelector(".sidebar-search-input");
    const clearBtn = searchContainer.querySelector(".sidebar-search-clear");
    const kbd = searchContainer.querySelector(".sidebar-search-kbd");
    const nav = sidebar.querySelector(".sidebar-nav");
    const emptyEl = sidebar.querySelector(".sidebar-search-empty");

    if (!input || !nav) return;

    // Platform-specific keyboard shortcut indicator
    if (kbd) {
        const isMac = typeof navigator !== "undefined" && /Mac|iPod|iPhone|iPad/.test(navigator.platform || "");
        kbd.textContent = isMac ? "⌘K" : "/";
        kbd.title = isMac ? "Press ⌘K or / to search" : "Press / or Ctrl+K to search";
    }

    // Clicking collapsed sidebar search should expand sidebar and focus input
    searchContainer.addEventListener("click", (e) => {
        if (sidebar.classList.contains("collapsed")) {
            const toggle = sidebar.querySelector(".sidebar-toggle");
            if (toggle) {
                toggle.click();
            } else {
                sidebar.classList.remove("collapsed");
            }
            setTimeout(() => input.focus(), 150);
        }
    });

    let selectedIndex = -1;

    const getVisibleMatches = () => {
        return Array.from(nav.querySelectorAll(".sidebar-link:not([data-role-hidden='true']):not(.sidebar-search-hidden)"));
    };

    const updateSelection = (matches, newIndex) => {
        matches.forEach((link, idx) => {
            if (idx === newIndex) {
                link.classList.add("sidebar-search-selected");
                link.scrollIntoView({ block: "nearest" });
            } else {
                link.classList.remove("sidebar-search-selected");
            }
        });
        selectedIndex = newIndex;
    };

    const performFilter = () => {
        const rawQuery = input.value.trim().toLowerCase();
        selectedIndex = -1;

        if (clearBtn) {
            clearBtn.style.display = rawQuery ? "inline-flex" : "none";
        }

        if (!rawQuery) {
            sidebar.classList.remove("sidebar-searching");
            nav.querySelectorAll(".sidebar-link").forEach((link) => {
                link.classList.remove("sidebar-search-match", "sidebar-search-hidden", "sidebar-search-selected");
                if (link.dataset.roleHidden === "true") {
                    link.style.display = "none";
                } else {
                    link.style.display = "";
                }
            });
            if (emptyEl) emptyEl.style.display = "none";
            updateSectionHeaderVisibility(sidebar);
            return;
        }

        sidebar.classList.add("sidebar-searching");
        const queryTokens = rawQuery.split(/\s+/).filter(Boolean);
        let matchCount = 0;

        nav.querySelectorAll(".sidebar-link").forEach((link) => {
            if (link.dataset.roleHidden === "true") {
                link.classList.add("sidebar-search-hidden");
                link.classList.remove("sidebar-search-match", "sidebar-search-selected");
                link.style.display = "none";
                return;
            }

            const page = link.dataset.page || "";
            const textSpan = link.querySelector(".sidebar-link-text");
            const label = (textSpan ? textSpan.textContent : link.textContent || "").trim().toLowerCase();
            const title = (link.title || "").toLowerCase();
            const keywords = (PAGE_SEARCH_KEYWORDS[page] || []).join(" ").toLowerCase();
            const combinedSearchText = `${page} ${label} ${title} ${keywords}`;

            const matches = queryTokens.every((token) => combinedSearchText.includes(token));

            if (matches) {
                link.classList.add("sidebar-search-match");
                link.classList.remove("sidebar-search-hidden", "sidebar-search-selected");
                link.style.display = "";
                matchCount++;
            } else {
                link.classList.remove("sidebar-search-match", "sidebar-search-selected");
                link.classList.add("sidebar-search-hidden");
                link.style.display = "none";
            }
        });

        if (emptyEl) {
            emptyEl.style.display = matchCount === 0 ? "block" : "none";
        }

        updateSectionHeaderVisibility(sidebar);
    };

    input.addEventListener("input", performFilter);

    if (clearBtn) {
        clearBtn.addEventListener("click", (e) => {
            e.stopPropagation();
            input.value = "";
            performFilter();
            input.focus();
        });
    }

    input.addEventListener("keydown", (e) => {
        const matches = getVisibleMatches();
        if (e.key === "ArrowDown") {
            e.preventDefault();
            if (matches.length > 0) {
                const nextIdx = (selectedIndex + 1) % matches.length;
                updateSelection(matches, nextIdx);
            }
        } else if (e.key === "ArrowUp") {
            e.preventDefault();
            if (matches.length > 0) {
                const prevIdx = selectedIndex <= 0 ? matches.length - 1 : selectedIndex - 1;
                updateSelection(matches, prevIdx);
            }
        } else if (e.key === "Enter") {
            e.preventDefault();
            if (matches.length > 0) {
                const target = selectedIndex >= 0 && selectedIndex < matches.length ? matches[selectedIndex] : matches[0];
                if (target && target.href) {
                    target.click();
                }
            }
        } else if (e.key === "Escape") {
            e.preventDefault();
            input.value = "";
            performFilter();
            input.blur();
        }
    });

    // Global keyboard shortcut listener for / and Ctrl+K / Cmd+K
    if (!window._sidebarSearchGlobalKeyListenerAdded) {
        window._sidebarSearchGlobalKeyListenerAdded = true;
        document.addEventListener("keydown", (e) => {
            const activeTag = document.activeElement ? document.activeElement.tagName.toLowerCase() : "";
            const isEditing = activeTag === "input" || activeTag === "textarea" || activeTag === "select" || (document.activeElement && document.activeElement.isContentEditable);

            // Ctrl+K / Cmd+K works globally
            const isCmdK = (e.ctrlKey || e.metaKey) && (e.key === "k" || e.key === "K");
            // Slash key works when not actively typing into an input
            const isSlash = e.key === "/" && !isEditing && !e.ctrlKey && !e.metaKey && !e.altKey;

            if (isCmdK || isSlash) {
                const currentSidebar = document.querySelector(".sidebar");
                if (!currentSidebar) return;
                const searchInput = currentSidebar.querySelector(".sidebar-search-input");
                if (searchInput) {
                    e.preventDefault();
                    if (currentSidebar.classList.contains("collapsed")) {
                        const toggle = currentSidebar.querySelector(".sidebar-toggle");
                        if (toggle) toggle.click();
                        else currentSidebar.classList.remove("collapsed");
                    }
                    setTimeout(() => {
                        searchInput.focus();
                        searchInput.select();
                    }, 100);
                }
            }
        });
    }
}

export async function ensureSidebarAndFooter(sidebar) {
    if (!sidebar) return;
    if (!sidebar.querySelector(".sidebar-nav")) {
        console.log("[Sidebar] Sidebar is empty, loading base template...");
        let baseHtml = sessionStorage.getItem("obsidianscout:base_html");
        if (!baseHtml) {
            baseHtml = safeGetItem("obsidianscout:base_html");
        }
        if (baseHtml && (!baseHtml.includes("sidebar-link-icon") || !baseHtml.includes("sidebar-search"))) {
            sessionStorage.removeItem("obsidianscout:base_html");
            try { localStorage.removeItem("obsidianscout:base_html"); } catch (e) {}
            baseHtml = null;
        }
        // Refetch the template after a server update so new sidebar links appear. If the fetch
        // fails (offline), keep using the cached copy.
        const serverVersion = safeGetItem("obsidianscout:server_version") || "";
        const templateIsStale = baseHtml && safeGetItem("obsidianscout:base_html_version") !== serverVersion;
        if (!baseHtml || templateIsStale) {
            try {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 5000);
                const res = await fetch("/base.html", { signal: controller.signal }).finally(() => clearTimeout(timer));
                if (res.ok) {
                    baseHtml = await res.text();
                    sessionStorage.setItem("obsidianscout:base_html", baseHtml);
                    safeSetItem("obsidianscout:base_html", baseHtml);
                    safeSetItem("obsidianscout:base_html_version", serverVersion);
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
                wireSidebarSearch(sidebar);
                if (window.Obsidianscout && typeof window.Obsidianscout.renderServerVersion === 'function') {
                    window.Obsidianscout.renderServerVersion(sidebar);
                }
            }
        }
    } else {
        wireSidebarSearch(sidebar);
    }
}
