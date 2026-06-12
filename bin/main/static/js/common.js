(function () {
    const toastRootId = "toast-root";
    const sidebarCollapseKey = "obsidian-sidebar-collapsed";
    const DEFAULT_REQUEST_TIMEOUT_MS = 20000;

    // Role hierarchy (lower index = higher privilege)
    const ROLE_HIERARCHY = ["SUPERADMIN", "ADMIN", "ANALYTICS", "SCOUT"];

    const CACHE_CONFIGS = {
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

    const servedFromCache = new Set();
    const activeFetches = new Set();

    function safeGetItem(key) {
        try {
            return localStorage.getItem(key);
        } catch (e) {
            console.warn("[Storage] Failed to read from localStorage:", e);
            return null;
        }
    }

    function safeSetItem(key, value) {
        try {
            localStorage.setItem(key, value);
        } catch (e) {
            console.warn("[Storage] Failed to write to localStorage:", e);
        }
    }

    function safeRemoveItem(key) {
        try {
            localStorage.removeItem(key);
        } catch (e) {
            console.warn("[Storage] Failed to remove from localStorage:", e);
        }
    }

    function clearAllCaches() {
        try {
            const keys = [];
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i);
                if (key && key.startsWith("cache:")) {
                    keys.push(key);
                }
            }
            keys.forEach(key => safeRemoveItem(key));
            console.log("[Offline Cache] Cleared all caches after write operation");
        } catch (e) {
            console.warn("[Offline Cache] Failed to clear caches:", e);
        }
    }

    function saveScrollPositions() {
        try {
            const scrolls = {
                windowX: window.scrollX || window.pageXOffset,
                windowY: window.scrollY || window.pageYOffset,
                elements: []
            };
            document.querySelectorAll('.table-scroll, .main-content, .sidebar').forEach((el, index) => {
                if (el.scrollTop > 0 || el.scrollLeft > 0) {
                    scrolls.elements.push({
                        index: index,
                        class: el.className,
                        id: el.id,
                        top: el.scrollTop,
                        left: el.scrollLeft
                    });
                }
            });
            safeSetItem("obsidianscout:scroll_positions", JSON.stringify(scrolls));
        } catch (e) {
            console.warn("Failed to save scroll positions:", e);
        }
    }

    function restoreScrollPositions() {
        try {
            const saved = safeGetItem("obsidianscout:scroll_positions");
            if (!saved) return;
            safeRemoveItem("obsidianscout:scroll_positions");
            const scrolls = JSON.parse(saved);
            if (!scrolls) return;

            setTimeout(() => {
                window.scrollTo(scrolls.windowX || 0, scrolls.windowY || 0);
                
                scrolls.elements.forEach(item => {
                    let el = null;
                    if (item.id) {
                        el = document.getElementById(item.id);
                    } else {
                        const candidates = document.querySelectorAll('.table-scroll, .main-content, .sidebar');
                        if (candidates[item.index]) {
                            el = candidates[item.index];
                        }
                    }
                    if (el) {
                        el.scrollTop = item.top;
                        el.scrollLeft = item.left;
                    }
                });
            }, 100);
        } catch (e) {
            console.warn("Failed to restore scroll positions:", e);
        }
    }

    function triggerBackgroundRevalidate(path, cachedText) {
        if (!navigator.onLine) return;
        if (activeFetches.has(path)) return;
        activeFetches.add(path);

        setTimeout(async () => {
            try {
                const controller = new AbortController();
                const timeout = setTimeout(() => controller.abort(), 10000);
                const response = await fetch(path, {
                    method: "GET",
                    headers: {},
                    credentials: "same-origin",
                    signal: controller.signal,
                    cache: "no-cache"
                });
                clearTimeout(timeout);

                if (response.status === 401) {
                    const isLoginPage = document.body && document.body.dataset.page === "login";
                    if (!isLoginPage) {
                        safeRemoveItem("cache:/api/auth/me");
                        window.location.href = "/";
                        return;
                    }
                }

                if (response.ok) {
                    const text = await response.text();
                    if (text !== cachedText) {
                        console.log(`[Offline Cache] Background revalidation succeeded for ${path}. Data changed, updating cache and refreshing.`);
                        safeSetItem("cache:" + path, text);
                        
                        const isDataPage = ['dashboard', 'qual-data', 'pit-data', 'all-data', 'analytics', 'graphs', 'events', 'teams', 'matches', 'predictor', 'alliances', 'users', 'config', 'settings'].includes(document.body.dataset.page);
                        const isUserEditing = document.querySelector('input:focus, textarea:focus, select:focus') !== null;
                        const isModalOpen = document.querySelector('.modal-backdrop.show, .modal.show, [role="dialog"].show') !== null;
                        
                        if (isDataPage && !isUserEditing && !isModalOpen) {
                            if (typeof saveScrollPositions === "function") {
                                saveScrollPositions();
                            }
                            window.location.reload();
                        }
                    } else {
                        console.log(`[Offline Cache] Background revalidation succeeded for ${path}. Data matches cache.`);
                    }
                }
            } catch (err) {
                console.warn(`[Offline Cache] Background revalidation failed for ${path}:`, err);
            } finally {
                activeFetches.delete(path);
            }
        }, 1000);
    }

    async function request(path, options = {}) {
        const method = options.method || "GET";

        if (method === "GET") {
            const cachedText = safeGetItem("cache:" + path);
            if (cachedText !== null) {
                console.log("[Offline Cache] Serving cached response instantly for:", path);
                servedFromCache.add(path);
                triggerBackgroundRevalidate(path, cachedText);
                return safeParse(cachedText);
            }
        }

        const controller = new AbortController();
        const defaultTimeout = options.timeoutMs || DEFAULT_REQUEST_TIMEOUT_MS;
        const timeoutMs = defaultTimeout;
        const timeout = window.setTimeout(() => controller.abort(), timeoutMs);

        // Listen to option signal to cancel request
        if (options.signal) {
            options.signal.addEventListener("abort", () => {
                controller.abort();
            });
            if (options.signal.aborted) {
                controller.abort();
            }
        }

        const opts = {
            method: method,
            headers: options.headers || {},
            credentials: "same-origin",
            signal: controller.signal
        };
        if (method === "GET") {
            opts.cache = "no-cache";
        }
        if (options.json !== undefined) {
            opts.headers["Content-Type"] = "application/json";
            opts.body = JSON.stringify(options.json);
        }

        try {
            const response = await fetch(path, opts);
            if (response.status === 204) {
                return null;
            }

            const text = await response.text();
            const data = text ? safeParse(text) : null;
            if (!response.ok) {
                if (response.status === 401) {
                    const isLoginPage = document.body && document.body.dataset.page === "login";
                    const isAuthRequest = path.includes("/api/auth/login") || path.includes("/api/auth/register");
                    if (!isLoginPage && !isAuthRequest) {
                        safeRemoveItem("cache:/api/auth/me");
                        window.location.href = "/";
                        const err = new Error("Session expired. Redirecting...");
                        err.status = 401;
                        throw err;
                    }
                }
                const message = data && data.error ? data.error : "Request failed";
                const err = new Error(message);
                err.status = response.status;
                throw err;
            }

            if (opts.method === "GET") {
                safeSetItem("cache:" + path, text);
            } else {
                clearAllCaches();
            }

            return data;
        } catch (error) {
            const isAbort = error && error.name === "AbortError";
            if (isAbort) {
                if (options.signal && options.signal.aborted) {
                    // Manual abort from caller signal
                    throw error;
                }
                throw new Error("Request timed out. Try refreshing this page.");
            }
            throw error;
        } finally {
            window.clearTimeout(timeout);
        }
    }

    function safeParse(text) {
        try {
            return JSON.parse(text);
        } catch (error) {
            return text;
        }
    }

    async function getMe() {
        try {
            const result = await request("/api/auth/me");
            return result.user;
        } catch (error) {
            return null;
        }
    }

    // i18n support
    const DEFAULT_LANG = safeGetItem("obsidianscout:lang") || "en";
    let currentLang = DEFAULT_LANG;
    const i18nCache = {};

    async function loadLocale(lang) {
        if (!lang) lang = "en";
        if (i18nCache[lang]) return i18nCache[lang];
        // Try localStorage first
        try {
            const cached = safeGetItem(`i18n:${lang}`);
            if (cached) {
                const parsed = JSON.parse(cached);
                i18nCache[lang] = parsed;
                return parsed;
            }
        } catch (e) {
            // ignore
        }

        try {
            const res = await fetch(`/i18n/${lang}.json`);
            if (!res.ok) throw new Error("Locale fetch failed");
            const json = await res.json();
            i18nCache[lang] = json;
            safeSetItem(`i18n:${lang}`, JSON.stringify(json));
            return json;
        } catch (error) {
            if (lang !== "en") return loadLocale("en");
            return {};
        }
    }

    function t(key, fallback) {
        const dict = i18nCache[currentLang] || {};
        return dict[key] || (i18nCache['en'] && i18nCache['en'][key]) || fallback || key;
    }

    /**
     * Localize a dynamic value which may be:
     * - a string (either literal or an i18n key)
     * - an object mapping language codes to translations { en: 'Label', es: 'Etiqueta' }
     */
    function localize(value) {
        if (value === null || value === undefined) return '';
        if (typeof value === 'object') {
            // prefer exact language, fallback to en then any available
            if (value[currentLang]) return value[currentLang];
            if (value.en) return value.en;
            // return first available value
            const keys = Object.keys(value);
            if (keys.length) return value[keys[0]];
            return '';
        }
        if (typeof value === 'string') {
            // if key exists in i18n dicts, return that translation
            const dict = i18nCache[currentLang] || {};
            if (dict[value]) return dict[value];
            if (i18nCache['en'] && i18nCache['en'][value]) return i18nCache['en'][value];
            return value;
        }
        return String(value);
    }

    async function setLanguage(lang) {
        currentLang = lang || 'en';
        safeSetItem('obsidianscout:lang', currentLang);
        await loadLocale(currentLang);
        applyTranslations();
        window.dispatchEvent(new CustomEvent('obsidianscout:languagechange', { detail: { lang: currentLang } }));
    }

    function applyTranslations() {
        // data-i18n attributes
        document.querySelectorAll('[data-i18n]').forEach((el) => {
            const key = el.dataset.i18n;
            if (!key) return;
            const text = t(key, el.textContent || '');
            el.textContent = text;
        });

        document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
            const key = el.dataset.i18nPlaceholder;
            if (!key) return;
            el.setAttribute('placeholder', t(key, el.getAttribute('placeholder') || ''));
        });

        // Sidebar links by data-page
        document.querySelectorAll('.sidebar-link[data-page]').forEach((link) => {
            const page = link.dataset.page;
            const key = `nav.${page}`;
            link.textContent = t(key, link.textContent);
            link.title = link.textContent;
        });

        // Theme toggle and logout
        const themeBtn = document.querySelector("[data-action='toggle-theme']");
        if (themeBtn) themeBtn.textContent = t('btn.toggle_theme', themeBtn.textContent);
        const logoutBtn = document.querySelector("[data-action='logout']");
        if (logoutBtn) logoutBtn.textContent = t('btn.logout', logoutBtn.textContent);

        // Connection widget status text
        const widget = document.getElementById('connection-status-widget');
        if (widget) {
            const textEl = widget.querySelector('.status-text');
            if (textEl) {
                const isOnline = navigator.onLine;
                textEl.textContent = isOnline ? t('connection.online') : t('connection.offline');
            }
        }

        // Direction for RTL languages
        if (currentLang === 'he') {
            document.documentElement.dir = 'rtl';
        } else {
            document.documentElement.dir = 'ltr';
        }
    }

    function injectLanguageSelector(sidebar) {
        try {
            const footer = sidebar.querySelector('.sidebar-footer');
            if (!footer) return;
            // Avoid duplicate
            if (document.getElementById('lang-select')) return;
            const wrap = document.createElement('div');
            wrap.className = 'field';
            wrap.style.marginTop = '8px';
            const sel = document.createElement('select');
            sel.id = 'lang-select';
            sel.style.padding = '6px';
            const opts = [
                { v: 'en', l: 'English' },
                { v: 'es', l: 'Español' },
                { v: 'tr', l: 'Türkçe' },
                { v: 'he', l: 'עברית' }
            ];
            opts.forEach((o) => {
                const opt = document.createElement('option');
                opt.value = o.v;
                opt.textContent = o.l;
                sel.appendChild(opt);
            });
            sel.value = safeGetItem('obsidianscout:lang') || 'en';
            sel.addEventListener('change', async (e) => {
                await setLanguage(e.target.value);
                updateConnectionStatus();
            });
            wrap.appendChild(sel);
            footer.insertBefore(wrap, footer.firstChild);
        } catch (e) {
            console.warn('Failed to inject language selector', e);
        }
    }

    async function requireAuth() {
        const me = await getMe();
        if (!me) {
            window.location.href = "/";
            return null;
        }
        return me;
    }

    /**
     * Checks if the user's role is at least the required level.
     * E.g. hasRole("ADMIN", "ADMIN") = true
     *      hasRole("SUPERADMIN", "ADMIN") = true
     *      hasRole("SCOUT", "ADMIN") = false
     */
    function hasRole(userRole, requiredRole) {
        const userIdx = ROLE_HIERARCHY.indexOf(userRole);
        const reqIdx = ROLE_HIERARCHY.indexOf(requiredRole);
        if (userIdx === -1 || reqIdx === -1) return false;
        return userIdx <= reqIdx;
    }

    function isAdmin(role) {
        return hasRole(role, "ADMIN");
    }

    function isSuperAdmin(role) {
        return role === "SUPERADMIN";
    }

    function canAccessAnalytics(role) {
        return hasRole(role, "ANALYTICS");
    }

    function showToast(message, tone = "info") {
        let root = document.getElementById(toastRootId);
        if (!root) {
            root = document.createElement("div");
            root.id = toastRootId;
            document.body.appendChild(root);
        }
        const toast = document.createElement("div");
        toast.className = `toast ${tone}`;
        toast.textContent = message;
        root.appendChild(toast);
        setTimeout(() => {
            toast.remove();
        }, 2800);
    }

    function setUserBadge(user) {
        const badge = document.getElementById("nav-user");
        if (!badge || !user) {
            return;
        }
        const roleLabel = user.role === "SUPERADMIN" ? "Super Admin" : user.role.charAt(0) + user.role.slice(1).toLowerCase();

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

        badge.innerHTML = `
            <a class="nav-avatar-link" href="/config" aria-label="Edit profile picture">${avatarHtml}</a>
            <span class="nav-user-text">${user.username} | Team ${user.teamNumber} | ${roleLabel}</span>
        `;
    }

    /**
     * Updates the sidebar avatar after a profile picture change without a full page reload.
     * @param {string|null} profilePicture - New picture data-URL, or null to revert to initials.
     */
    function refreshNavAvatar(profilePicture) {
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
            const textEl = badge.querySelector(".nav-user-text");
            const text = textEl ? textEl.textContent : "";
            const username = text.split("|")[0].trim();
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

    function setActiveNav() {
        const page = document.body.dataset.page;
        if (!page) {
            return;
        }
        document.querySelectorAll(".nav-link, .sidebar-link").forEach((link) => {
            if (link.dataset.page === page) {
                link.classList.add("active");
            }
        });
    }

    /**
     * Adjusts sidebar navigation visibility based on user role.
     */
    function adjustNavForRole(user) {
        if (!user) return;
        const role = user.role;

        // Hide Users link for SCOUT and ANALYTICS
        if (!isAdmin(role)) {
            document.querySelectorAll('.sidebar-link[data-page="users"]').forEach((link) => {
                link.style.display = "none";
            });
        }

        // Hide Analytics for SCOUT
        if (!canAccessAnalytics(role)) {
            document.querySelectorAll('.sidebar-link[data-page="analytics"]').forEach((link) => {
                link.style.display = "none";
            });
        }
    }

    function wireLogout() {
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
                showToast(error.message || "Failed to sign out", "error");
            }
        });
    }

    function initTheme() {
        const saved = safeGetItem("obsidian-theme") || "light";
        document.body.classList.toggle("theme-dark", saved === "dark");
    }

    function wireThemeToggle() {
        const toggle = document.querySelector("[data-action='toggle-theme']");
        if (!toggle) {
            return;
        }
        toggle.addEventListener("click", () => {
            const isDark = document.body.classList.toggle("theme-dark");
            safeSetItem("obsidian-theme", isDark ? "dark" : "light");
        });
    }

    function resolveEventKey(settings) {
        if (!settings) {
            return "";
        }
        const code = (settings.eventCode || "").trim();
        if (code) {
            return `${settings.year}${code}`.toLowerCase();
        }
        return (settings.eventKey || "").trim().toLowerCase();
    }

    function formatTimestamp(epochSeconds, timezone) {
        if (!epochSeconds) {
            return "";
        }
        const date = new Date(epochSeconds * 1000);
        try {
            return new Intl.DateTimeFormat("en-US", {
                dateStyle: "medium",
                timeStyle: "short",
                timeZone: timezone || undefined
            }).format(date);
        } catch (error) {
            return date.toLocaleString();
        }
    }

    function injectConnectionWidget(sidebar) {
        const brand = sidebar.querySelector(".sidebar-brand");
        if (!brand) return;

        const widget = document.createElement("div");
        widget.id = "connection-status-widget";
        widget.className = "connection-widget online";
        widget.innerHTML = `
            <span class="status-dot"></span>
            <span class="status-text">Online</span>
            <button id="btn-sync-offline" class="btn-sync-offline hidden">Sync (0)</button>
        `;

        const anchor = sidebar.querySelector(".sidebar-header") || brand;
        anchor.after(widget);

        const syncBtn = widget.querySelector("#btn-sync-offline");
        syncBtn.addEventListener("click", (e) => {
            e.stopPropagation();
            syncOfflineEntries();
        });

        updateConnectionStatus();
    }

    function wireSidebarToggle() {
        const sidebar = document.querySelector(".sidebar");
        if (!sidebar) {
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

    function injectMobileTopBar() {
        if (window.innerWidth >= 900) {
            return;
        }

        const sidebar = document.querySelector(".sidebar");
        const appShell = document.querySelector(".app-shell");
        if (!sidebar || !appShell) {
            return;
        }

        if (document.querySelector(".mobile-topbar")) {
            return;
        }

        const topBar = document.createElement("header");
        topBar.className = "mobile-topbar";
        topBar.innerHTML = `
            <button type="button" class="mobile-menu-button" aria-label="Open menu" aria-expanded="false">
                <span class="hamburger-icon">☰</span>
            </button>
            <div class="mobile-topbar-brand">ObsidianScout</div>
        `;

        appShell.parentNode.insertBefore(topBar, appShell);

        let overlay = document.querySelector(".sidebar-overlay");
        if (!overlay) {
            overlay = document.createElement("div");
            overlay.className = "sidebar-overlay";
            document.body.appendChild(overlay);
        }

        const button = topBar.querySelector(".mobile-menu-button");

        const setMobileOpen = (open) => {
            sidebar.classList.toggle("mobile-open", open);
            overlay.classList.toggle("visible", open);
            button.setAttribute("aria-expanded", open.toString());
            button.setAttribute("aria-label", open ? "Close menu" : "Open menu");
        };

        button.addEventListener("click", () => {
            setMobileOpen(!sidebar.classList.contains("mobile-open"));
        });

        overlay.addEventListener("click", () => setMobileOpen(false));

        sidebar.querySelectorAll(".sidebar-link").forEach((link) => {
            link.addEventListener("click", () => setMobileOpen(false));
        });

        window.addEventListener("resize", () => {
            if (window.innerWidth >= 900) {
                setMobileOpen(false);
            }
        });
    }

    function updateConnectionStatus() {
        const widget = document.getElementById("connection-status-widget");
        if (!widget) return;

        const dot = widget.querySelector(".status-dot");
        const text = widget.querySelector(".status-text");
        const syncBtn = widget.querySelector("#btn-sync-offline");

        let count = 0;
        for (const type in CACHE_CONFIGS) {
            const config = CACHE_CONFIGS[type];
            const pending = JSON.parse(safeGetItem(config.key) || "[]");
            count += pending.length;
        }

        const isOnline = navigator.onLine;

        if (isOnline) {
            widget.className = "connection-widget online";
            text.textContent = (typeof t === 'function') ? t('connection.online', 'Online') : 'Online';
            if (count > 0) {
                syncBtn.classList.remove("hidden");
                syncBtn.textContent = `${(typeof t === 'function' ? t('connection.sync','Sync') : 'Sync')} (${count})`;
                syncBtn.disabled = false;
            } else {
                syncBtn.classList.add("hidden");
            }
        } else {
            widget.className = "connection-widget offline";
            text.textContent = (typeof t === 'function') ? t('connection.offline','Offline') : 'Offline';
            if (count > 0) {
                syncBtn.classList.remove("hidden");
                syncBtn.textContent = `${(typeof t === 'function' ? t('connection.pending','Pending') : 'Pending')} (${count})`;
                syncBtn.disabled = true;
            } else {
                syncBtn.classList.add("hidden");
            }
        }
    }

    async function syncOfflineEntries() {
        if (!navigator.onLine) return;

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

        updateConnectionStatus();
    }

    function downloadJson(payload, filename) {
        try {
            const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            showToast("JSON exported successfully", "success");
        } catch (e) {
            console.error("JSON export failed:", e);
            showToast("Failed to export JSON", "error");
        }
    }

    async function compressData(dataStr) {
        if (typeof CompressionStream === 'undefined') {
            console.warn("CompressionStream is not supported in this browser. Falling back to raw JSON.");
            return dataStr;
        }
        try {
            const stream = new Blob([dataStr]).stream();
            const compressedStream = stream.pipeThrough(new CompressionStream("deflate"));
            const buffer = await new Response(compressedStream).arrayBuffer();
            const bytes = new Uint8Array(buffer);
            
            // Safe base64 encoding from Uint8Array
            let binary = "";
            const len = bytes.byteLength;
            const chunk = 8192;
            for (let i = 0; i < len; i += chunk) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
            }
            return "OSC:" + btoa(binary);
        } catch (e) {
            console.error("Compression failed, using raw data:", e);
            return dataStr;
        }
    }

    async function decompressData(compressedStr) {
        if (!compressedStr || !compressedStr.startsWith("OSC:")) {
            return compressedStr; // Not compressed, return raw
        }
        const base64 = compressedStr.substring(4);
        if (typeof DecompressionStream === 'undefined') {
            throw new Error("DecompressionStream is not supported by this browser.");
        }
        try {
            const binary = atob(base64);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            const stream = new Blob([bytes]).stream();
            const decompressedStream = stream.pipeThrough(new DecompressionStream("deflate"));
            return await new Response(decompressedStream).text();
        } catch (e) {
            console.error("Decompression failed:", e);
            throw e;
        }
    }

    async function showQrModal(payload, typeLabel, teamNum, matchKey) {
        if (typeof QRCode === 'undefined') {
            showToast("QR Library not loaded", "error");
            return;
        }

        let backdrop = document.getElementById("qr-modal-backdrop");
        if (!backdrop) {
            backdrop = document.createElement("div");
            backdrop.id = "qr-modal-backdrop";
            backdrop.className = "modal-backdrop";
            document.body.appendChild(backdrop);
        }

        const matchHtml = matchKey ? `<p><strong>Match:</strong> <span>${matchKey}</span></p>` : '';
        
        backdrop.innerHTML = `
            <div class="modal-container">
                <div class="modal-header">
                    <h3 class="modal-title">Scouting Entry QR Code</h3>
                    <button class="modal-close" id="qr-modal-close-btn">&times;</button>
                </div>
                <div class="modal-body qr-modal-body">
                    <div class="qr-code-wrapper" id="qr-code-canvas-container"></div>
                    <div class="qr-details">
                        <p><strong>Type:</strong> <span>${typeLabel}</span></p>
                        <p><strong>Team:</strong> <span>${teamNum}</span></p>
                        ${matchHtml}
                    </div>
                </div>
                <div class="modal-footer">
                    <button class="btn secondary" id="qr-modal-download-btn">Download QR Code</button>
                    <button class="btn ghost" id="qr-modal-close-footer-btn">Close</button>
                </div>
            </div>
        `;

        const container = document.getElementById("qr-code-canvas-container");
        const qrPayload = {
            type: payload.type || typeLabel.toLowerCase().replace(/\s+/g, '-'),
            data: payload
        };
        const qrString = JSON.stringify(qrPayload);
        const compressedString = await compressData(qrString);

        const qrcode = new QRCode(container, {
            text: compressedString,
            width: 384,
            height: 384,
            colorDark : "#000000",
            colorLight : "#ffffff",
            correctLevel : QRCode.CorrectLevel.M
        });

        backdrop.classList.add("show");

        const closeBtn = document.getElementById("qr-modal-close-btn");
        const closeFooterBtn = document.getElementById("qr-modal-close-footer-btn");
        const downloadBtn = document.getElementById("qr-modal-download-btn");

        const closeModal = () => {
            backdrop.classList.remove("show");
        };

        closeBtn.addEventListener("click", closeModal);
        closeFooterBtn.addEventListener("click", closeModal);

        downloadBtn.addEventListener("click", () => {
            const img = container.querySelector("img");
            const canvas = container.querySelector("canvas");
            let src = "";
            if (img && img.src) {
                src = img.src;
            } else if (canvas) {
                src = canvas.toDataURL("image/png");
            }
            if (src) {
                const a = document.createElement("a");
                a.href = src;
                a.download = `qr_${qrPayload.type}_${teamNum}${matchKey ? '_' + matchKey : ''}.png`;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
            } else {
                showToast("Could not download QR image yet", "error");
            }
        });
    }

    // Set up Service Worker and Global Connection Listeners
    document.addEventListener("DOMContentLoaded", () => {
        if ('serviceWorker' in navigator) {
            navigator.serviceWorker.register('/sw.js')
                .then(reg => console.log('[ServiceWorker] Scope:', reg.scope))
                .catch(err => console.error('[ServiceWorker] Registration failed:', err));
        }

        const sidebar = document.querySelector(".sidebar");
        if (sidebar) {
            injectConnectionWidget(sidebar);
            injectLanguageSelector(sidebar);
        }

        wireSidebarToggle();
        injectMobileTopBar();

        window.addEventListener("online", () => {
            updateConnectionStatus();
            const isCacheManager = document.body && document.body.dataset.page === "cache-manager";
            if (!isCacheManager) {
                syncOfflineEntries();
            }
            const isDataPage = ['dashboard', 'qual-data', 'pit-data', 'all-data', 'analytics', 'graphs', 'events', 'teams', 'matches', 'predictor', 'alliances', 'users', 'config', 'settings'].includes(document.body.dataset.page);
            const isUserEditing = document.querySelector('input:focus, textarea:focus') !== null;
            if (isDataPage && !isUserEditing) {
                saveScrollPositions();
                window.location.reload();
            }
        });
        window.addEventListener("offline", updateConnectionStatus);

        if (navigator.onLine) {
            const isCacheManager = document.body && document.body.dataset.page === "cache-manager";
            if (!isCacheManager) {
                syncOfflineEntries();
            }
        }
        // Load selected language bundle and apply translations
        loadLocale(safeGetItem('obsidianscout:lang') || 'en').then(() => applyTranslations());
        restoreScrollPositions();
    });

    window.addEventListener("beforeunload", (event) => {
        let count = 0;
        for (const type in CACHE_CONFIGS) {
            const config = CACHE_CONFIGS[type];
            const pending = JSON.parse(safeGetItem(config.key) || "[]");
            count += pending.length;
        }
        if (count > 0) {
            const message = (typeof t === 'function') ? t('unsynced_entries','You have unsynced offline scouting entries! If you leave, they might not be synced to the server.') : "You have unsynced offline scouting entries! If you leave, they might not be synced to the server.";
            event.returnValue = message;
            return message;
        }
    });

    function formatTeam(teamKey, teamNumber) {
        if (!teamKey) {
            return teamNumber !== undefined && teamNumber !== null ? String(teamNumber) : "";
        }
        
        // Remove 'frc' prefix
        const cleanKey = teamKey.replace(/^frc/, "");
        
        // Split if it's already a slash-merged format (e.g. 254b/9999 or frc254b/9999)
        const parts = cleanKey.split("/");
        const keyPart = parts[0];
        const numPart = parts.length > 1 ? parts[1] : (teamNumber !== undefined && teamNumber !== null ? String(teamNumber).replace(/^frc/, "") : "");
        
        if (!numPart || keyPart === numPart) {
            return keyPart;
        }
        
        const displayPref = safeGetItem("obsidianscout:team_display") || "merged";
        if (displayPref === "number") {
            return numPart;
        } else if (displayPref === "key") {
            return keyPart;
        } else {
            // "merged" or fallback
            return `${keyPart}/${numPart}`;
        }
    }

    function showLoadingSpinner(container, text) {
        if (!container) return;
        const spinnerText = text || (typeof t === 'function' ? t('status.loading', 'Loading data...') : 'Loading data...');
        container.innerHTML = `
            <div class="spinner-container">
                <div class="spinner"></div>
                <div class="spinner-text">${spinnerText}</div>
            </div>
        `;
    }

    function showRetryButton(container, message, onRetry) {
        if (!container) return;
        const errMessage = message || (typeof t === 'function' ? t('status.load_failed', 'Failed to load data.') : 'Failed to load data.');
        const btnText = typeof t === 'function' ? t('btn.retry', 'Retry') : 'Retry';
        container.innerHTML = `
            <div class="retry-container">
                <div class="retry-error-text">${errMessage}</div>
                <button class="retry-btn" type="button">${btnText}</button>
            </div>
        `;
        const btn = container.querySelector(".retry-btn");
        if (btn && typeof onRetry === "function") {
            btn.addEventListener("click", onRetry);
        }
    }

    window.Obsidianscout = {
        request,
        getMe,
        requireAuth,
        showToast,
        setUserBadge,
        refreshNavAvatar,
        setActiveNav,
        adjustNavForRole,
        wireLogout,
        initTheme,
        wireThemeToggle,
        formatTimestamp,
        resolveEventKey,
        hasRole,
        isAdmin,
        isSuperAdmin,
        canAccessAnalytics,
        ROLE_HIERARCHY,
        updateConnectionStatus,
        syncOfflineEntries,
        showLoadingSpinner,
        showRetryButton
        ,t
        ,setLanguage
        ,localize
        ,formatTeam
        ,safeGetItem
        ,safeSetItem
        ,safeRemoveItem
        ,downloadJson
        ,showQrModal
        ,compressData
        ,decompressData
        ,CACHE_CONFIGS
    };
})();
