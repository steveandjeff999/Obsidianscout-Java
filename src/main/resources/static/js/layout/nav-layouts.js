/**
 * Layout Nav-Layouts Module - ObsidianScout
 * Multi-mode navigation switcher (sidebar, topbar, compact, horizontal), collapsible sidebar drawer, and topbar dropdown menus.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';
import { wireThemeToggle } from './theme.js';
import { wireLogout } from '../base/auth.js';
import { setUserBadge, setActiveNav, lastUser } from './navigation.js';

export const sidebarCollapseKey = "obsidian-sidebar-collapsed";

export const CATEGORY_ICONS = {
    "Scouting": `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"></path><rect x="8" y="2" width="8" height="4" rx="1" ry="1"></rect><path d="M9 14l2 2 4-4"></path></svg>`,
    "Data & Analytics": `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"></line><line x1="12" y1="20" x2="12" y2="4"></line><line x1="6" y1="20" x2="6" y2="14"></line></svg>`,
    "Strategy": `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><circle cx="12" cy="12" r="6"></circle><circle cx="12" cy="12" r="2"></circle></svg>`,
    "Admin & System": `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>`
};
export const DEFAULT_ICON = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 2 7 12 12 22 7 12 2"></polygon><polyline points="2 17 12 22 22 17"></polyline><polyline points="2 12 12 17 22 12"></polyline></svg>`;
export const LINK_ICONS = {
    "Dashboard": `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline></svg>`
};

export function setupTopbarDropdowns(sidebar) {
    if (!sidebar) return;
    if (sidebar.classList.contains("topbar-dropdowns-ready")) return;

    if (!sidebar.dataset.rawHtml) {
        sidebar.dataset.rawHtml = sidebar.innerHTML;
    }

    const brand = sidebar.querySelector(".sidebar-brand");
    if (brand) {
        brand.title = "ObsidianScout";
        brand.innerHTML = `<img class="topbar-brand-icon" src="/assets/images/obsidian/obsidian-192.png" onerror="this.src='/favicon.ico'" alt="ObsidianScout">`;
    }

    const nav = sidebar.querySelector(".sidebar-nav");
    if (!nav) return;

    const children = Array.from(nav.children);
    let currentDropdown = null;
    let currentMenu = null;

    children.forEach((child) => {
        if (child.classList.contains("sidebar-section-title")) {
            const sectionName = (child.textContent || "").trim();
            const sectionI18n = child.getAttribute("data-i18n");

            currentDropdown = document.createElement("div");
            currentDropdown.className = "topbar-dropdown";

            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "topbar-dropdown-btn";
            btn.title = sectionName;

            const iconSpan = document.createElement("span");
            iconSpan.className = "topbar-dropdown-icon";
            iconSpan.innerHTML = CATEGORY_ICONS[sectionName] || DEFAULT_ICON;

            const label = document.createElement("span");
            label.className = "topbar-dropdown-label";
            label.textContent = sectionName;
            if (sectionI18n) label.setAttribute("data-i18n", sectionI18n);

            const arrow = document.createElement("span");
            arrow.className = "topbar-dropdown-arrow";
            arrow.textContent = " ▾";

            btn.appendChild(iconSpan);
            btn.appendChild(label);
            btn.appendChild(arrow);

            currentMenu = document.createElement("div");
            currentMenu.className = "topbar-dropdown-menu";

            currentDropdown.appendChild(btn);
            currentDropdown.appendChild(currentMenu);

            nav.replaceChild(currentDropdown, child);
        } else if (child.classList.contains("sidebar-link")) {
            if (currentMenu) {
                currentMenu.appendChild(child);
            } else {
                // Standalone nav link before sections (e.g., Dashboard)
                const linkText = (child.textContent || "").trim();
                child.title = linkText;
                if (!child.querySelector(".sidebar-link-icon")) {
                    const iconSpan = document.createElement("span");
                    iconSpan.className = "sidebar-link-icon";
                    iconSpan.innerHTML = LINK_ICONS[linkText] || DEFAULT_ICON;
                    child.insertBefore(iconSpan, child.firstChild);
                }
            }
        }
    });

    // Account Dropdown & Action Buttons in Sidebar Footer
    const footer = sidebar.querySelector(".sidebar-footer");
    if (footer) {
        footer.innerHTML = "";

        const accountDropdown = document.createElement("div");
        accountDropdown.className = "topbar-dropdown topbar-account-dropdown";

        const acctBtn = document.createElement("button");
        acctBtn.type = "button";
        acctBtn.className = "topbar-dropdown-btn topbar-account-btn";
        acctBtn.title = "Account";

        const avatarWrap = document.createElement("div");
        avatarWrap.id = "topbar-account-avatar";
        avatarWrap.innerHTML = `<div class="nav-avatar nav-avatar-initials" style="--avatar-hue:200deg" title="Account">👤</div>`;

        const usernameSpan = document.createElement("span");
        usernameSpan.id = "topbar-account-username";
        usernameSpan.textContent = "Account";

        const arrow = document.createElement("span");
        arrow.className = "topbar-dropdown-arrow";
        arrow.textContent = " ▾";

        acctBtn.appendChild(avatarWrap);
        acctBtn.appendChild(usernameSpan);
        acctBtn.appendChild(arrow);

        const acctMenu = document.createElement("div");
        acctMenu.className = "topbar-dropdown-menu topbar-account-menu";

        const userCard = document.createElement("div");
        userCard.className = "topbar-user-card";
        userCard.id = "topbar-user-card-content";
        acctMenu.appendChild(userCard);

        const divider = document.createElement("div");
        divider.className = "topbar-menu-divider";
        acctMenu.appendChild(divider);

        const tourBtn = document.getElementById("btn-take-tour");
        if (tourBtn) {
            acctMenu.appendChild(tourBtn);
        }

        const themeBtn = document.createElement("button");
        themeBtn.className = "btn ghost";
        themeBtn.type = "button";
        themeBtn.setAttribute("data-action", "toggle-theme");
        themeBtn.textContent = "Toggle theme";
        acctMenu.appendChild(themeBtn);

        const logoutBtn = document.createElement("button");
        logoutBtn.className = "btn ghost";
        logoutBtn.type = "button";
        logoutBtn.setAttribute("data-action", "logout");
        logoutBtn.textContent = "Sign out";
        acctMenu.appendChild(logoutBtn);

        accountDropdown.appendChild(acctBtn);
        accountDropdown.appendChild(acctMenu);

        // Standalone action buttons for compact sidebars (Theme & Logout)
        const footerActions = document.createElement("div");
        footerActions.className = "topbar-footer-actions";

        const standaloneThemeBtn = document.createElement("button");
        standaloneThemeBtn.className = "topbar-footer-btn theme-toggle-btn";
        standaloneThemeBtn.type = "button";
        standaloneThemeBtn.setAttribute("data-action", "toggle-theme");
        standaloneThemeBtn.setAttribute("title", "Toggle theme");
        standaloneThemeBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>`;

        const standaloneLogoutBtn = document.createElement("button");
        standaloneLogoutBtn.className = "topbar-footer-btn logout-btn";
        standaloneLogoutBtn.type = "button";
        standaloneLogoutBtn.setAttribute("data-action", "logout");
        standaloneLogoutBtn.setAttribute("title", "Sign out");
        standaloneLogoutBtn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>`;

        footerActions.appendChild(standaloneThemeBtn);
        footerActions.appendChild(standaloneLogoutBtn);

        footer.appendChild(accountDropdown);
        footer.appendChild(footerActions);

        wireThemeToggle(acctMenu);
        wireLogout();
    }

    // Toggle listeners for click
    sidebar.querySelectorAll(".topbar-dropdown-btn").forEach((btn) => {
        btn.addEventListener("click", (e) => {
            e.stopPropagation();
            const parent = btn.closest(".topbar-dropdown");
            const wasOpen = parent.classList.contains("open");
            document.querySelectorAll(".topbar-dropdown.open").forEach((d) => d.classList.remove("open"));
            if (!wasOpen) {
                parent.classList.add("open");
            }
        });
    });

    // Close dropdowns on clicking links
    sidebar.querySelectorAll(".sidebar-link").forEach((link) => {
        link.addEventListener("click", () => {
            document.querySelectorAll(".topbar-dropdown.open").forEach((d) => d.classList.remove("open"));
        });
    });

    if (!window._topbarClickListenerAdded) {
        window._topbarClickListenerAdded = true;
        document.addEventListener("click", (e) => {
            if (!e.target.closest(".topbar-dropdown")) {
                document.querySelectorAll(".topbar-dropdown.open").forEach((d) => d.classList.remove("open"));
            }
        });
    }

    sidebar.classList.add("topbar-dropdowns-ready");

    try {
        if (lastUser) {
            setUserBadge(lastUser);
        } else {
            const meText = safeGetItem("cache:/api/auth/me");
            if (meText) {
                const parsed = JSON.parse(meText);
                const user = parsed.user || parsed;
                if (user && user.username) {
                    setUserBadge(user);
                }
            }
        }
    } catch (e) {}

    setActiveNav();
}

export function restoreSidebarLayout(sidebar) {
    if (!sidebar || !sidebar.classList.contains("topbar-dropdowns-ready")) return;
    if (sidebar.dataset.rawHtml) {
        sidebar.innerHTML = sidebar.dataset.rawHtml;
        delete sidebar.dataset.rawHtml;
    }
    sidebar.classList.remove("topbar-dropdowns-ready");
    wireThemeToggle(sidebar);
    wireLogout();
    setActiveNav();
}

export function applyNavLayout(layout) {
    let pref = layout || safeGetItem("obsidianscout:nav_layout") || "sidebar-left";
    if (window.innerWidth < 900) {
        pref = "sidebar-left";
    }
    if (pref === "sidebar") pref = "sidebar-left";
    if (pref === "topbar") pref = "topbar-top";

    const validLayouts = ["sidebar-left", "sidebar-right", "topbar-top", "topbar-bottom", "topbar-left", "topbar-right"];
    if (!validLayouts.includes(pref)) {
        pref = "sidebar-left";
    }

    document.body.classList.remove(
        "nav-layout-sidebar-left",
        "nav-layout-sidebar-right",
        "nav-layout-topbar-top",
        "nav-layout-topbar-bottom",
        "nav-layout-topbar-left",
        "nav-layout-topbar-right",
        "nav-layout-topbar"
    );

    const isTopbar = pref.startsWith("topbar-");
    document.body.classList.add(`nav-layout-${pref}`);
    if (isTopbar) {
        document.body.classList.add("nav-layout-topbar");
    }

    const sidebar = document.querySelector(".sidebar");
    if (sidebar) {
        if (isTopbar) {
            sidebar.classList.remove("collapsed");
            setupTopbarDropdowns(sidebar);
        } else {
            restoreSidebarLayout(sidebar);
        }
    }
}

if (typeof window !== 'undefined' && !window._navLayoutResizeListenerAdded) {
    window._navLayoutResizeListenerAdded = true;
    window.addEventListener("resize", () => {
        const isMobile = window.innerWidth < 900;
        if (isMobile !== window._lastWasMobile) {
            window._lastWasMobile = isMobile;
            applyNavLayout();
        }
    });
}

export function wireSidebarToggle() {
    const sidebar = document.querySelector(".sidebar");
    if (!sidebar) {
        return;
    }

    if (document.body.classList.contains("nav-layout-topbar")) {
        sidebar.classList.remove("collapsed");
        const existingToggle = sidebar.querySelector(".sidebar-toggle");
        if (existingToggle) {
            existingToggle.remove();
        }
        return;
    }

    if (window.innerWidth < 900) {
        const existingMobileToggle = sidebar.querySelector(".sidebar-toggle");
        if (existingMobileToggle) {
            existingMobileToggle.remove();
        }
        return;
    }

    const brand = sidebar.querySelector(".sidebar-brand");
    if (!brand) {
        return;
    }

    let header = sidebar.querySelector(".sidebar-header");
    if (!header) {
        header = document.createElement("div");
        header.className = "sidebar-header";
        brand.parentNode.insertBefore(header, brand);
        header.appendChild(brand);
    }

    if (!brand.dataset.short) {
        const brandText = (brand.textContent || "").trim();
        const compact = brandText.replace(/[^a-z0-9]/gi, "");
        brand.dataset.short = (compact.slice(0, 2) || brandText.slice(0, 2) || "OS").toUpperCase();
        brand.title = brandText;
    }

    sidebar.querySelectorAll(".sidebar-link").forEach((link) => {
        const label = (link.textContent || "").trim();
        if (!link.dataset.short) {
            const compact = label.replace(/[^a-z0-9]/gi, "");
            link.dataset.short = (compact.slice(0, 2) || label.slice(0, 2) || "?").toUpperCase();
        }
        if (!link.title) {
            link.title = label;
        }
    });

    let toggle = sidebar.querySelector(".sidebar-toggle");
    if (!toggle) {
        toggle = document.createElement("button");
        toggle.type = "button";
        toggle.className = "sidebar-toggle";
        header.appendChild(toggle);
    }

    const applyCollapsedState = (collapsed, persist = true) => {
        sidebar.classList.toggle("collapsed", collapsed);
        toggle.textContent = collapsed ? ">>" : "<<";
        toggle.setAttribute("aria-expanded", (!collapsed).toString());
        if (persist) {
            safeSetItem(sidebarCollapseKey, collapsed ? "1" : "0");
        }
    };

    const stored = safeGetItem(sidebarCollapseKey);
    // If user has previously chosen a state, respect it. Otherwise default to
    // collapsed on narrow viewports for better mobile UX.
    const initial = (stored !== null) ? (stored === "1") : (window.innerWidth < 900);
    applyCollapsedState(initial);

    toggle.addEventListener("click", () => {
        applyCollapsedState(!sidebar.classList.contains("collapsed"), true);
    });
}
