/**
 * Error Reports Management - ObsidianScout
 * Allows superadmins to review, filter, inspect, and manage server code exceptions
 * and client-side JavaScript bug reports across the cluster.
 */
(function () {
    console.log("[ErrorReportsJS] Initialized.");

    let currentErrors = [];
    let currentGroups = [];
    let expandedGroupKeys = new Set();
    let isGroupedView = true;
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
        params.set("limit", "500");

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
            currentGroups = res.groups || [];

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

            renderCurrentView();
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

    function renderCurrentView() {
        if (isGroupedView) {
            renderGroupedTable(currentGroups);
        } else {
            renderFlatTable(currentErrors);
        }
    }

    function renderGroupedTable(groups) {
        const tbody = document.getElementById("errors-table-body");
        if (!tbody) return;

        if (!groups || groups.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="7" style="text-align: center; color: var(--muted); padding: 32px;">
                        No error reports match the current filter criteria.
                    </td>
                </tr>
            `;
            return;
        }

        let html = "";
        groups.forEach((group) => {
            const isServer = group.errorType === "SERVER";
            const isOpen = group.openCount > 0;
            const isExpanded = expandedGroupKeys.has(group.groupKey);

            const typeBadge = isServer
                ? `<span class="type-tag type-server">SERVER</span>`
                : `<span class="type-tag type-client">CLIENT JS</span>`;

            let statusBadge = "";
            if (group.openCount > 0 && group.resolvedCount > 0) {
                statusBadge = `<span class="status-pill status-open">${group.openCount} OPEN</span>`;
            } else if (group.openCount > 0) {
                statusBadge = `<span class="status-pill status-open">OPEN</span>`;
            } else {
                statusBadge = `<span class="status-pill status-resolved">RESOLVED</span>`;
            }

            let userContext = "";
            if (group.affectedTeams && group.affectedTeams.length > 0) {
                const teamsStr = group.affectedTeams.slice(0, 3).join(", ") + (group.affectedTeams.length > 3 ? ` (+${group.affectedTeams.length - 3})` : "");
                const usersCount = group.affectedUsers ? group.affectedUsers.length : 0;
                userContext = `Teams: ${escapeHtml(teamsStr)}${usersCount > 0 ? ` (${usersCount} user${usersCount === 1 ? "" : "s"})` : ""}`;
            } else if (group.affectedUsers && group.affectedUsers.length > 0) {
                userContext = `Users: ${escapeHtml(group.affectedUsers.slice(0, 3).join(", "))}`;
            } else {
                userContext = `<span style="color: var(--muted); font-style: italic;">Unauthenticated</span>`;
            }

            const badgeClass = group.count > 1 ? "occurrence-badge" : "occurrence-badge badge-count-low";
            const countLabel = group.count === 1 ? "1 occurrence" : `${group.count} occurrences`;

            // Main Group Row
            html += `
                <tr class="group-row" data-group-key="${escapeHtml(group.groupKey)}">
                    <td>${typeBadge}</td>
                    <td>${statusBadge}</td>
                    <td style="max-width: 340px;">
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <button type="button" class="btn-expand-group ${isExpanded ? 'expanded' : ''}" data-action="toggle-expand" data-group-key="${escapeHtml(group.groupKey)}" title="Click to view occurrences">
                                <span class="expand-icon">▶</span>
                            </button>
                            <div style="font-weight: 600; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1;" title="${escapeHtml(group.errorMessage)}">
                                ${escapeHtml(group.errorMessage)}
                            </div>
                            <span class="${badgeClass}">${countLabel}</span>
                        </div>
                    </td>
                    <td style="max-width: 220px; font-family: monospace; font-size: 12px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" title="${escapeHtml(group.location || "-")}">
                        ${escapeHtml(group.location || "-")}
                    </td>
                    <td>${userContext}</td>
                    <td style="color: var(--muted); font-size: 12px; white-space: nowrap;">
                        <div>${formatDate(group.latestCreatedAt)}</div>
                        ${group.count > 1 ? `<div style="font-size: 10px; color: var(--muted);">First: ${formatDate(group.firstCreatedAt)}</div>` : ""}
                    </td>
                    <td style="text-align: right; white-space: nowrap;">
                        <button class="btn-table-action btn-view" data-action="toggle-expand" data-group-key="${escapeHtml(group.groupKey)}">
                            ${isExpanded ? 'Hide' : 'View'} (${group.count})
                        </button>
                        ${
                            isOpen
                                ? `<button class="btn-table-action btn-resolve" data-action="resolve-group" data-group-key="${escapeHtml(group.groupKey)}">Resolve All</button>`
                                : `<button class="btn-table-action btn-reopen" data-action="reopen-group" data-group-key="${escapeHtml(group.groupKey)}">Reopen All</button>`
                        }
                        <button class="btn-table-action btn-del" data-action="delete-group" data-group-key="${escapeHtml(group.groupKey)}">Delete All</button>
                    </td>
                </tr>
            `;

            // Expanded Occurrences Sub-table Drawer
            if (isExpanded) {
                html += `
                    <tr class="group-occurrences-row" data-occurrences-for="${escapeHtml(group.groupKey)}">
                        <td colspan="7" class="occurrences-drawer-td">
                            <div class="occurrences-drawer">
                                <div class="occurrences-header">
                                    <span class="occurrences-title">Individual Occurrences (${group.occurrences.length})</span>
                                    <div style="display: flex; gap: 8px;">
                                        ${
                                            isOpen
                                                ? `<button class="btn-table-action btn-resolve" data-action="resolve-group" data-group-key="${escapeHtml(group.groupKey)}">Resolve All (${group.occurrences.length})</button>`
                                                : `<button class="btn-table-action btn-reopen" data-action="reopen-group" data-group-key="${escapeHtml(group.groupKey)}">Reopen All (${group.occurrences.length})</button>`
                                        }
                                        <button class="btn-table-action btn-del" data-action="delete-group" data-group-key="${escapeHtml(group.groupKey)}">Delete All (${group.occurrences.length})</button>
                                    </div>
                                </div>
                                <table class="occurrences-table">
                                    <thead>
                                        <tr>
                                            <th style="width: 50px;">#</th>
                                            <th style="width: 90px;">Status</th>
                                            <th>User / Team Context</th>
                                            <th>Origin / Request Details</th>
                                            <th>Timestamp</th>
                                            <th style="text-align: right;">Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        ${group.occurrences.map((item, idx) => {
                                            const itemOpen = item.status === "OPEN";
                                            const uContext = item.username
                                                ? `${escapeHtml(item.username)} (Team ${item.teamNumber ?? "?"} ${escapeHtml(item.program ?? "")})`
                                                : `<span style="color: var(--muted); font-style: italic;">Unauthenticated</span>`;
                                            const reqText = item.requestDetails || item.clientIp || "-";
                                            return `
                                                <tr data-id="${escapeHtml(item.id)}">
                                                    <td style="color: var(--muted); font-family: monospace;">#${idx + 1}</td>
                                                    <td>${itemOpen ? '<span class="status-pill status-open">OPEN</span>' : '<span class="status-pill status-resolved">RESOLVED</span>'}</td>
                                                    <td>${uContext}</td>
                                                    <td style="font-family: monospace; font-size: 11px; color: var(--muted); max-width: 240px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" title="${escapeHtml(reqText)}">
                                                        ${escapeHtml(reqText)}
                                                    </td>
                                                    <td style="color: var(--muted); font-size: 11px; white-space: nowrap;">${formatDate(item.createdAt)}</td>
                                                    <td style="text-align: right; white-space: nowrap;">
                                                        <button class="btn-table-action btn-view" data-action="view" data-id="${escapeHtml(item.id)}">View</button>
                                                        ${
                                                            itemOpen
                                                                ? `<button class="btn-table-action btn-resolve" data-action="toggle-status" data-id="${escapeHtml(item.id)}" data-status="RESOLVED">Resolve</button>`
                                                                : `<button class="btn-table-action btn-reopen" data-action="toggle-status" data-id="${escapeHtml(item.id)}" data-status="OPEN">Reopen</button>`
                                                        }
                                                        <button class="btn-table-action btn-del" data-action="delete" data-id="${escapeHtml(item.id)}">Delete</button>
                                                    </td>
                                                </tr>
                                            `;
                                        }).join("")}
                                    </tbody>
                                </table>
                            </div>
                        </td>
                    </tr>
                `;
            }
        });

        tbody.innerHTML = html;
    }

    function renderFlatTable(errors) {
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

    async function updateGroupStatus(groupKey, newStatus) {
        const group = currentGroups.find(g => g.groupKey === groupKey);
        if (!group || !group.occurrences || group.occurrences.length === 0) return;

        const errorIds = group.occurrences.map(o => o.id);
        const api = getApi();
        try {
            const requestFn = api.request || window.fetch;
            await requestFn("/api/admin/errors/group/status", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ errorIds, status: newStatus })
            });
            if (typeof api.showToast === "function") {
                api.showToast(`Marked ${errorIds.length} error(s) in group as ${newStatus.toLowerCase()}`, "success");
            }
            await loadErrorReportList();
            await loadErrorStats();
        } catch (err) {
            if (typeof api.showToast === "function") {
                api.showToast("Failed to update group status: " + err.message, "error");
            }
        }
    }

    async function deleteGroup(groupKey) {
        const group = currentGroups.find(g => g.groupKey === groupKey);
        if (!group || !group.occurrences || group.occurrences.length === 0) return;

        if (!confirm(`Are you sure you want to delete all ${group.occurrences.length} error report(s) in this group? This cannot be undone.`)) {
            return;
        }

        const errorIds = group.occurrences.map(o => o.id);
        const api = getApi();
        try {
            const requestFn = api.request || window.fetch;
            await requestFn("/api/admin/errors/group/delete", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ errorIds })
            });
            if (typeof api.showToast === "function") {
                api.showToast(`Deleted ${errorIds.length} error report(s)`, "success");
            }
            expandedGroupKeys.delete(groupKey);
            await loadErrorReportList();
            await loadErrorStats();
        } catch (err) {
            if (typeof api.showToast === "function") {
                api.showToast("Failed to delete group: " + err.message, "error");
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

            // Grouping toggle
            const toggleGroupEl = document.getElementById("toggle-group-errors");
            if (toggleGroupEl) {
                toggleGroupEl.checked = isGroupedView;
                toggleGroupEl.addEventListener("change", () => {
                    isGroupedView = toggleGroupEl.checked;
                    renderCurrentView();
                });
            }

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

            // Delegate table clicks (buttons + row click to expand)
            document.getElementById("errors-table-body")?.addEventListener("click", (e) => {
                const btn = e.target.closest("button[data-action]");
                if (btn) {
                    const action = btn.dataset.action;
                    const id = btn.dataset.id;
                    const groupKey = btn.dataset.groupKey;

                    if (action === "view" && id) {
                        const item = currentErrors.find((x) => x.id === id);
                        if (item) openErrorDetailModal(item);
                    } else if (action === "toggle-status" && id) {
                        const newStatus = btn.dataset.status;
                        updateErrorStatus(id, newStatus);
                    } else if (action === "delete" && id) {
                        deleteSingleError(id);
                    } else if (action === "toggle-expand" && groupKey) {
                        if (expandedGroupKeys.has(groupKey)) {
                            expandedGroupKeys.delete(groupKey);
                        } else {
                            expandedGroupKeys.add(groupKey);
                        }
                        renderCurrentView();
                    } else if (action === "resolve-group" && groupKey) {
                        updateGroupStatus(groupKey, "RESOLVED");
                    } else if (action === "reopen-group" && groupKey) {
                        updateGroupStatus(groupKey, "OPEN");
                    } else if (action === "delete-group" && groupKey) {
                        deleteGroup(groupKey);
                    }
                    return;
                }

                // If clicking anywhere on a group row (not on a button)
                const groupRow = e.target.closest("tr.group-row");
                if (groupRow && isGroupedView) {
                    const groupKey = groupRow.dataset.groupKey;
                    if (groupKey) {
                        if (expandedGroupKeys.has(groupKey)) {
                            expandedGroupKeys.delete(groupKey);
                        } else {
                            expandedGroupKeys.add(groupKey);
                        }
                        renderCurrentView();
                    }
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
