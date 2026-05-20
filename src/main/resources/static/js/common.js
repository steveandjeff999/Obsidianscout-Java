(function () {
    const toastRootId = "toast-root";
    const sidebarCollapseKey = "obsidian-sidebar-collapsed";

    // Role hierarchy (lower index = higher privilege)
    const ROLE_HIERARCHY = ["SUPERADMIN", "ADMIN", "ANALYTICS", "SCOUT"];

    async function request(path, options = {}) {
        const opts = {
            method: options.method || "GET",
            headers: options.headers || {},
            credentials: "same-origin"
        };
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
                const message = data && data.error ? data.error : "Request failed";
                throw new Error(message);
            }

            if (opts.method === "GET" && path !== "/api/auth/me") {
                try {
                    localStorage.setItem("cache:" + path, text);
                } catch (e) {
                    console.warn("[Offline Cache] Storage quota exceeded or unavailable:", e);
                }
            }

            return data;
        } catch (error) {
            if (opts.method === "GET" && path !== "/api/auth/me") {
                const cachedText = localStorage.getItem("cache:" + path);
                if (cachedText !== null) {
                    console.log("[Offline Cache] Serving cached response for:", path);
                    return safeParse(cachedText);
                }
            }
            throw error;
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
        badge.textContent = `${user.username} | Team ${user.teamNumber} | ${roleLabel}`;
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

        // Hide Users and Settings links for SCOUT and ANALYTICS
        if (!isAdmin(role)) {
            document.querySelectorAll('.sidebar-link[data-page="users"], .sidebar-link[data-page="settings"]').forEach((link) => {
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
                localStorage.removeItem("cache:/api/auth/me");
                window.location.href = "/";
            } catch (error) {
                showToast(error.message || "Failed to sign out", "error");
            }
        });
    }

    function initTheme() {
        const saved = localStorage.getItem("obsidian-theme") || "light";
        document.body.classList.toggle("theme-dark", saved === "dark");
    }

    function wireThemeToggle() {
        const toggle = document.querySelector("[data-action='toggle-theme']");
        if (!toggle) {
            return;
        }
        toggle.addEventListener("click", () => {
            const isDark = document.body.classList.toggle("theme-dark");
            localStorage.setItem("obsidian-theme", isDark ? "dark" : "light");
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

        const applyCollapsedState = (collapsed) => {
            sidebar.classList.toggle("collapsed", collapsed);
            toggle.textContent = collapsed ? ">>" : "<<";
            toggle.setAttribute("aria-expanded", (!collapsed).toString());
            localStorage.setItem(sidebarCollapseKey, collapsed ? "1" : "0");
        };

        const initial = localStorage.getItem(sidebarCollapseKey) === "1";
        applyCollapsedState(initial);

        toggle.addEventListener("click", () => {
            applyCollapsedState(!sidebar.classList.contains("collapsed"));
        });
    }

    function updateConnectionStatus() {
        const widget = document.getElementById("connection-status-widget");
        if (!widget) return;

        const dot = widget.querySelector(".status-dot");
        const text = widget.querySelector(".status-text");
        const syncBtn = widget.querySelector("#btn-sync-offline");

        const pending = JSON.parse(localStorage.getItem("pending_scouting_entries") || "[]");
        const count = pending.length;

        const isOnline = navigator.onLine;

        if (isOnline) {
            widget.className = "connection-widget online";
            text.textContent = "Online";
            if (count > 0) {
                syncBtn.classList.remove("hidden");
                syncBtn.textContent = `Sync (${count})`;
                syncBtn.disabled = false;
            } else {
                syncBtn.classList.add("hidden");
            }
        } else {
            widget.className = "connection-widget offline";
            text.textContent = "Offline";
            if (count > 0) {
                syncBtn.classList.remove("hidden");
                syncBtn.textContent = `Pending (${count})`;
                syncBtn.disabled = true;
            } else {
                syncBtn.classList.add("hidden");
            }
        }
    }

    async function syncOfflineEntries() {
        if (!navigator.onLine) return;
        const pending = JSON.parse(localStorage.getItem("pending_scouting_entries") || "[]");
        if (!pending.length) return;

        const syncBtn = document.querySelector("#btn-sync-offline");
        if (syncBtn) {
            syncBtn.disabled = true;
            syncBtn.textContent = "Syncing...";
        }

        let successCount = 0;
        const remaining = [];

        for (const item of pending) {
            try {
                await request("/api/scouting", {
                    method: "POST",
                    json: item
                });
                successCount++;
            } catch (error) {
                console.error("[Offline Sync] Failed to sync entry:", error);
                remaining.push(item);
            }
        }

        localStorage.setItem("pending_scouting_entries", JSON.stringify(remaining));

        if (successCount > 0) {
            showToast(`Successfully synced ${successCount} offline entries!`, "success");
        }

        updateConnectionStatus();
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
        }

        wireSidebarToggle();

        window.addEventListener("online", () => {
            updateConnectionStatus();
            syncOfflineEntries();
        });
        window.addEventListener("offline", updateConnectionStatus);

        if (navigator.onLine) {
            syncOfflineEntries();
        }
    });

    window.addEventListener("beforeunload", (event) => {
        const pending = JSON.parse(localStorage.getItem("pending_scouting_entries") || "[]");
        if (pending.length > 0) {
            const message = "You have unsynced offline scouting entries! If you leave, they might not be synced to the server.";
            event.returnValue = message;
            return message;
        }
    });

    window.Obsidianscout = {
        request,
        getMe,
        requireAuth,
        showToast,
        setUserBadge,
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
        syncOfflineEntries
    };
})();
