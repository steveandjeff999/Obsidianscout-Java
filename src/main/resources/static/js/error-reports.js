/**
 * Error Reports Management - ObsidianScout
 * Allows superadmins to review, filter, inspect, and manage server code exceptions
 * and client-side JavaScript bug reports across the cluster.
 */
(function () {
    console.log("[ErrorReportsJS] Initialized.");

    let currentErrors = [];
    let selectedError = null;

    function getApi() {
        return window.Obsidianscout || {};
    }

    function escapeHtml(str) {
        if (!str) return "";
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }

    function formatDate(dateStr) {
        if (!dateStr) return "-";
        try {
            const d = new Date(dateStr);
            if (isNaN(d.getTime())) return dateStr;
            return d.toLocaleString(undefined, {
                month: "short",
                day: "numeric",
                hour: "2-digit",
                minute: "2-digit",
                second: "2-digit"
            });
        } catch {
            return dateStr;
        }
    }

    function openModal(modalId) {
        const modal = typeof modalId === 'string' ? document.getElementById(modalId) : modalId;
        if (modal) {
            modal.classList.add("show");
            modal.classList.add("open");
        }
    }

    function closeModal(modalId) {
        const modal = typeof modalId === 'string' ? document.getElementById(modalId) : modalId;
        if (modal) {
            modal.classList.remove("show");
            modal.classList.remove("open");
        }
    }

    function closeAllModals() {
        document.querySelectorAll(".modal-backdrop").forEach(m => {
            m.classList.remove("show");
            m.classList.remove("open");
        });
    }

    async function loadErrorStats() {
        try {
            const api = getApi();
            const requestFn = api.request || window.fetch;
            const stats = await requestFn("/api/admin/errors/stats");
            if (!stats) return;

            const totalEl = document.getElementById("stat-total-errors");
            const openEl = document.getElementById("stat-open-errors");
            const resolvedEl = document.getElementById("stat-resolved-errors");
            const serverEl = document.getElementById("stat-server-errors");
            const clientEl = document.getElementById("stat-client-errors");

            if (totalEl) totalEl.textContent = stats.totalCount.toLocaleString();
            if (openEl) openEl.textContent = stats.openCount.toLocaleString();
            if (resolvedEl) resolvedEl.textContent = stats.resolvedCount.toLocaleString();
            if (serverEl) serverEl.textContent = stats.serverCount.toLocaleString();
            if (clientEl) clientEl.textContent = stats.clientCount.toLocaleString();
        } catch (e) {
            console.warn("Failed to load error stats:", e);
        }
    }

    async function loadErrorReportList() {
        const tbody = document.getElementById("errors-table-body");
        if (!tbody) return;

        const type = document.getElementById("filter-type")?.value || "ALL";
        const status = document.getElementById("filter-status")?.value || "OPEN";
        const search = document.getElementById("input-search-errors")?.value?.trim() || "";

        const params = new URLSearchParams();
        if (type !== "ALL") params.set("type", type);
        if (status !== "ALL") params.set("status", status);
        if (search) params.set("search", search);
        params.set("limit", "200");

        tbody.innerHTML = `
            <tr>
                <td colspan="7" style="text-align: center; color: var(--muted); padding: 24px;">
                    Loading error reports...
                </td>
            </tr>
        `;

        try {
            const api = getApi();
            const requestFn = api.request || window.fetch;
            const res = await requestFn(`/api/admin/errors?${params.toString()}`);
            currentErrors = res.errors || [];

            // Update stats if present in response
            if (res.totalCount !== undefined) {
                const totalEl = document.getElementById("stat-total-errors");
                const openEl = document.getElementById("stat-open-errors");
                const resolvedEl = document.getElementById("stat-resolved-errors");
                const serverEl = document.getElementById("stat-server-errors");
                const clientEl = document.getElementById("stat-client-errors");

                if (totalEl) totalEl.textContent = res.totalCount.toLocaleString();
                if (openEl) openEl.textContent = res.openCount.toLocaleString();
                if (resolvedEl) resolvedEl.textContent = res.resolvedCount.toLocaleString();
                if (serverEl) serverEl.textContent = res.serverCount.toLocaleString();
                if (clientEl) clientEl.textContent = res.clientCount.toLocaleString();
            }

            renderErrorsTable(currentErrors);
        } catch (err) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="7" style="text-align: center; color: #ef4444; padding: 24px;">
                        Failed to load error reports: ${escapeHtml(err.message)}
                    </td>
                </tr>
            `;
            const api = getApi();
            if (typeof api.showToast === "function") {
                api.showToast("Failed to fetch error reports: " + err.message, "error");
            }
        }
    }

    function renderErrorsTable(errors) {
        const tbody = document.getElementById("errors-table-body");
        if (!tbody) return;

        if (!errors || errors.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="7" style="text-align: center; color: var(--muted); padding: 32px;">
                        No error reports match the current filter criteria.
                    </td>
                </tr>
            `;
            return;
        }

        tbody.innerHTML = errors.map((item) => {
            const isServer = item.errorType === "SERVER";
            const isOpen = item.status === "OPEN";

            const typeBadge = isServer
                ? `<span class="type-tag type-server">SERVER</span>`
                : `<span class="type-tag type-client">CLIENT JS</span>`;

            const statusBadge = isOpen
                ? `<span class="status-pill status-open">OPEN</span>`
                : `<span class="status-pill status-resolved">RESOLVED</span>`;

            const userContext = item.username
                ? `${escapeHtml(item.username)} (Team ${item.teamNumber ?? "?"} ${escapeHtml(item.program ?? "")})`
                : `<span style="color: var(--muted); font-style: italic;">Unauthenticated</span>`;

            return `
                <tr data-id="${escapeHtml(item.id)}">
                    <td>${typeBadge}</td>
                    <td>${statusBadge}</td>
                    <td style="max-width: 320px;">
                        <div style="font-weight: 600; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" title="${escapeHtml(item.errorMessage)}">
                            ${escapeHtml(item.errorMessage)}
                        </div>
                    </td>
                    <td style="max-width: 240px; font-family: monospace; font-size: 12px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" title="${escapeHtml(item.requestDetails || "-")}">
                        ${escapeHtml(item.requestDetails || "-")}
                    </td>
                    <td>${userContext}</td>
                    <td style="color: var(--muted); font-size: 12px; white-space: nowrap;">${formatDate(item.createdAt)}</td>
                    <td style="text-align: right; white-space: nowrap;">
                        <button class="btn-table-action btn-view" data-action="view" data-id="${escapeHtml(item.id)}">View</button>
                        ${
                            isOpen
                                ? `<button class="btn-table-action btn-resolve" data-action="toggle-status" data-id="${escapeHtml(item.id)}" data-status="RESOLVED">Resolve</button>`
                                : `<button class="btn-table-action btn-reopen" data-action="toggle-status" data-id="${escapeHtml(item.id)}" data-status="OPEN">Reopen</button>`
                        }
                        <button class="btn-table-action btn-del" data-action="delete" data-id="${escapeHtml(item.id)}">Delete</button>
                    </td>
                </tr>
            `;
        }).join("");
    }

    function openErrorDetailModal(errItem) {
        selectedError = errItem;
        const modal = document.getElementById("modal-error-detail");
        if (!modal) return;

        document.getElementById("modal-detail-id").textContent = `ID: ${errItem.id}`;
        document.getElementById("modal-detail-msg").textContent = errItem.errorMessage;
        document.getElementById("modal-detail-request").textContent = errItem.requestDetails || "N/A";
        document.getElementById("modal-detail-ip").textContent = errItem.clientIp || "Unknown";

        const userStr = errItem.username
            ? `${errItem.username} (Team ${errItem.teamNumber ?? "N/A"}, Program ${errItem.program ?? "N/A"}, Role ${errItem.userRole ?? "N/A"})`
            : "Unauthenticated / Anonymous Client";
        document.getElementById("modal-detail-user").textContent = userStr;
        document.getElementById("modal-detail-created").textContent = formatDate(errItem.createdAt);

        const typeEl = document.getElementById("modal-detail-type");
        if (typeEl) {
            typeEl.className = errItem.errorType === "SERVER" ? "type-tag type-server" : "type-tag type-client";
            typeEl.textContent = errItem.errorType;
        }

        const statusEl = document.getElementById("modal-detail-status");
        if (statusEl) {
            statusEl.className = errItem.status === "OPEN" ? "status-pill status-open" : "status-pill status-resolved";
            statusEl.textContent = errItem.status === "OPEN" ? "OPEN" : "RESOLVED";
        }

        const resolvedRow = document.getElementById("modal-row-resolved");
        const resolvedText = document.getElementById("modal-detail-resolved");
        if (errItem.status === "RESOLVED") {
            resolvedRow.style.display = "";
            resolvedText.textContent = `Resolved by ${errItem.resolvedBy || "Superadmin"} on ${formatDate(errItem.resolvedAt)}`;
        } else {
            resolvedRow.style.display = "none";
        }

        const stackEl = document.getElementById("modal-detail-stack");
        stackEl.textContent = errItem.errorStack || "No stack trace provided.";

        const toggleBtn = document.getElementById("modal-btn-toggle-status");
        if (toggleBtn) {
            if (errItem.status === "OPEN") {
                toggleBtn.className = "btn-table-action btn-resolve";
                toggleBtn.textContent = "Mark as Resolved";
            } else {
                toggleBtn.className = "btn-table-action btn-reopen";
                toggleBtn.textContent = "Reopen Report";
            }
        }

        openModal("modal-error-detail");
    }

    async function updateErrorStatus(id, newStatus) {
        const api = getApi();
        try {
            const requestFn = api.request || window.fetch;
            await requestFn(`/api/admin/errors/${encodeURIComponent(id)}/status`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ status: newStatus })
            });
            if (typeof api.showToast === "function") {
                api.showToast(`Report marked as ${newStatus.toLowerCase()}`, "success");
            }

            // Refresh details if modal is open
            if (selectedError && selectedError.id === id) {
                selectedError.status = newStatus;
                openErrorDetailModal(selectedError);
            }

            await loadErrorReportList();
            await loadErrorStats();
        } catch (err) {
            if (typeof api.showToast === "function") {
                api.showToast("Failed to update status: " + err.message, "error");
            }
        }
    }

    async function deleteSingleError(id) {
        if (!confirm("Are you sure you want to delete this error report? This cannot be undone.")) {
            return;
        }

        const api = getApi();
        try {
            const requestFn = api.request || window.fetch;
            await requestFn(`/api/admin/errors/${encodeURIComponent(id)}`, {
                method: "DELETE"
            });
            if (typeof api.showToast === "function") {
                api.showToast("Error report deleted", "success");
            }

            closeModal("modal-error-detail");

            await loadErrorReportList();
            await loadErrorStats();
        } catch (err) {
            if (typeof api.showToast === "function") {
                api.showToast("Failed to delete error report: " + err.message, "error");
            }
        }
    }

    async function clearErrors(statusFilter) {
        const api = getApi();
        try {
            const requestFn = api.request || window.fetch;
            const res = await requestFn("/api/admin/errors/clear", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ statusFilter })
            });
            if (typeof api.showToast === "function") {
                api.showToast(`Cleared ${res.clearedCount ?? 0} error report(s)`, "success");
            }

            closeModal("modal-clear-errors");

            await loadErrorReportList();
            await loadErrorStats();
        } catch (err) {
            if (typeof api.showToast === "function") {
                api.showToast("Failed to clear error reports: " + err.message, "error");
            }
        }
    }

    document.addEventListener("DOMContentLoaded", async () => {
        try {
            if (window.Obsidianscout) {
                Obsidianscout.initTheme?.();
                const me = await Obsidianscout.requireAuth?.();
                if (!me) return;

                Obsidianscout.setUserBadge?.(me);
                Obsidianscout.setActiveNav?.();
                Obsidianscout.adjustNavForRole?.(me);
                Obsidianscout.wireLogout?.();
                Obsidianscout.wireThemeToggle?.();

                if (!Obsidianscout.isSuperAdmin?.(me.role)) {
                    document.getElementById("superadmin-locked")?.classList.remove("hidden");
                    document.getElementById("errors-panel")?.classList.add("hidden");
                    return;
                }
            }

            document.getElementById("superadmin-locked")?.classList.add("hidden");
            document.getElementById("errors-panel")?.classList.remove("hidden");

            // Close on backdrop click
            document.querySelectorAll(".modal-backdrop").forEach((modal) => {
                modal.addEventListener("click", (e) => {
                    if (e.target === modal) {
                        closeModal(modal);
                    }
                });
            });

            // Close on data-close-modal click
            document.querySelectorAll("[data-close-modal]").forEach((btn) => {
                btn.addEventListener("click", () => {
                    const targetId = btn.getAttribute("data-close-modal");
                    closeModal(targetId);
                });
            });

            // Close on Escape key
            document.addEventListener("keydown", (e) => {
                if (e.key === "Escape") {
                    closeAllModals();
                }
            });

            // Bind filters
            document.getElementById("btn-apply-filters")?.addEventListener("click", loadErrorReportList);
            document.getElementById("btn-refresh-errors")?.addEventListener("click", () => {
                loadErrorReportList();
                loadErrorStats();
            });

            document.getElementById("btn-reset-filters")?.addEventListener("click", () => {
                const typeSelect = document.getElementById("filter-type");
                const statusSelect = document.getElementById("filter-status");
                const searchInput = document.getElementById("input-search-errors");
                if (typeSelect) typeSelect.value = "ALL";
                if (statusSelect) statusSelect.value = "OPEN";
                if (searchInput) searchInput.value = "";
                loadErrorReportList();
            });

            document.getElementById("filter-type")?.addEventListener("change", loadErrorReportList);
            document.getElementById("filter-status")?.addEventListener("change", loadErrorReportList);
            document.getElementById("input-search-errors")?.addEventListener("keydown", (e) => {
                if (e.key === "Enter") loadErrorReportList();
            });

            // Delegate table button clicks
            document.getElementById("errors-table-body")?.addEventListener("click", (e) => {
                const btn = e.target.closest("button[data-action]");
                if (!btn) return;

                const action = btn.dataset.action;
                const id = btn.dataset.id;
                const item = currentErrors.find((x) => x.id === id);

                if (action === "view" && item) {
                    openErrorDetailModal(item);
                } else if (action === "toggle-status") {
                    const newStatus = btn.dataset.status;
                    updateErrorStatus(id, newStatus);
                } else if (action === "delete") {
                    deleteSingleError(id);
                }
            });

            // Detail Modal actions
            document.getElementById("modal-btn-toggle-status")?.addEventListener("click", () => {
                if (!selectedError) return;
                const nextStatus = selectedError.status === "OPEN" ? "RESOLVED" : "OPEN";
                updateErrorStatus(selectedError.id, nextStatus);
            });

            document.getElementById("modal-btn-delete")?.addEventListener("click", () => {
                if (!selectedError) return;
                deleteSingleError(selectedError.id);
            });

            // Clear Modal
            document.getElementById("btn-clear-errors")?.addEventListener("click", () => {
                openModal("modal-clear-errors");
            });

            document.getElementById("btn-confirm-clear")?.addEventListener("click", () => {
                const target = document.getElementById("select-clear-target")?.value || "RESOLVED";
                clearErrors(target);
            });

            // Initial load
            await loadErrorStats();
            await loadErrorReportList();
        } catch (err) {
            console.error("[ErrorReports] Page initialization error:", err);
        }
    });
})();
