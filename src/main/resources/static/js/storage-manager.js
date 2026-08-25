/**
 * Storage Manager Module - ObsidianScout
 * Superadmin Site Storage and Database Analytics dashboard controller.
 */

document.addEventListener("DOMContentLoaded", async () => {
    // 1. Superadmin Authentication Guard
    let me = null;
    try {
        me = await Obsidianscout.getMe();
    } catch (e) {
        console.warn("Failed to get current user info", e);
    }

    if (!me || !Obsidianscout.isSuperAdmin(me.role)) {
        const lockedEl = document.getElementById("superadmin-locked");
        const panelEl = document.getElementById("storage-panel");
        if (lockedEl) lockedEl.classList.remove("hidden");
        if (panelEl) panelEl.classList.add("hidden");
        return;
    }

    // State
    let overviewData = null;
    let eventsData = [];
    let teamsData = [];

    let activeDangerAction = null; // { confirmExpected: '...', onConfirm: async () => {} }
    let activeCachePurgeAction = null; // async () => {}

    // Helpers
    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return "0 B";
        const num = Number(bytes);
        if (num < 1024) return `${num} B`;
        if (num < 1024 * 1024) return `${(num / 1024).toFixed(1)} KB`;
        if (num < 1024 * 1024 * 1024) return `${(num / (1024 * 1024)).toFixed(2)} MB`;
        return `${(num / (1024 * 1024 * 1024)).toFixed(2)} GB`;
    }

    function formatNumber(num) {
        return Number(num || 0).toLocaleString();
    }

    // Modal helpers (uses standard app.css / modals.css .modal-backdrop + .show system)
    function openModal(modalId) {
        const modal = document.getElementById(modalId);
        if (modal) {
            modal.classList.add("show");
        }
    }

    function closeModal(modalId) {
        const modal = typeof modalId === 'string' ? document.getElementById(modalId) : modalId;
        if (modal) {
            modal.classList.remove("show");
        }
    }

    function closeAllModals() {
        document.querySelectorAll(".modal-backdrop.show").forEach(m => m.classList.remove("show"));
    }

    // Close on backdrop click
    document.querySelectorAll(".modal-backdrop").forEach(modal => {
        modal.addEventListener("click", (e) => {
            if (e.target === modal) {
                closeModal(modal);
            }
        });
    });

    // Close on data-close-modal click
    document.querySelectorAll("[data-close-modal]").forEach(btn => {
        btn.addEventListener("click", () => {
            const targetId = btn.dataset.closeModal;
            closeModal(targetId);
        });
    });

    // Close on Escape key
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
            closeAllModals();
        }
    });

    // Tab Switching
    const tabs = document.querySelectorAll("[data-storage-tab]");
    const panels = document.querySelectorAll("[data-storage-panel]");

    tabs.forEach(tab => {
        tab.addEventListener("click", () => {
            const target = tab.dataset.storageTab;
            tabs.forEach(t => t.classList.toggle("active", t === tab));
            panels.forEach(p => p.classList.toggle("hidden", p.dataset.storagePanel !== target));
        });
    });

    // ── Load All Data ────────────────────────────────────────────────────────
    async function loadAllStorageData() {
        const refreshBtn = document.getElementById("btn-refresh-storage");
        if (refreshBtn) Obsidianscout.setButtonLoading(refreshBtn, true, "Loading...");

        try {
            const [overview, events, teams] = await Promise.all([
                Obsidianscout.request("/api/admin/storage/overview"),
                Obsidianscout.request("/api/admin/storage/events"),
                Obsidianscout.request("/api/admin/storage/teams")
            ]);

            overviewData = overview;
            eventsData = events || [];
            teamsData = teams || [];

            renderOverview();
            renderEventsTable();
            renderTeamsTable();
            populateYearFilter();
        } catch (err) {
            console.error("Failed to load storage management data:", err);
            Obsidianscout.showToast("Failed to load storage data: " + (err.message || "Unknown error"), "error");
        } finally {
            if (refreshBtn) Obsidianscout.setButtonLoading(refreshBtn, false, "Refresh Storage Data");
        }
    }

    // ── Render High-Level Overview & Progress Bar ───────────────────────────
    function renderOverview() {
        if (!overviewData) return;

        // DB Engine Badge
        const engineBadge = document.getElementById("db-engine-badge");
        if (engineBadge) {
            let engineName = (overviewData.databaseType || "sqlite").toUpperCase();
            if (overviewData.isCockroach) engineName = "COCKROACHDB";
            engineBadge.textContent = engineName;
        }

        // Stats Cards
        const totalSizeEl = document.getElementById("stat-total-size");
        const totalRecordsEl = document.getElementById("stat-total-records");
        if (totalSizeEl) totalSizeEl.textContent = formatBytes(overviewData.totalPhysicalSizeBytes);
        if (totalRecordsEl) totalRecordsEl.textContent = `${formatNumber(overviewData.totalRecords)} database rows`;

        const apiSizeEl = document.getElementById("stat-api-cache-size");
        const apiRecordsEl = document.getElementById("stat-api-cache-records");
        if (apiSizeEl) apiSizeEl.textContent = formatBytes(overviewData.apiCacheBytes);
        if (apiRecordsEl) apiRecordsEl.textContent = `${formatNumber(overviewData.apiCacheRecords)} cached API rows`;

        const scoutSizeEl = document.getElementById("stat-scouting-size");
        const scoutRecordsEl = document.getElementById("stat-scouting-records");
        if (scoutSizeEl) scoutSizeEl.textContent = formatBytes(overviewData.userScoutingBytes);
        if (scoutRecordsEl) scoutRecordsEl.textContent = `${formatNumber(overviewData.userScoutingRecords)} user scouting forms`;

        const chatAccountsSizeEl = document.getElementById("stat-chat-accounts-size");
        const chatAccountsRecordsEl = document.getElementById("stat-chat-accounts-records");
        const chatAndAccountBytes = (overviewData.chatBytes || 0) + (overviewData.accountsBytes || 0);
        const chatAndAccountRecords = (overviewData.chatRecords || 0) + (overviewData.accountsRecords || 0);
        if (chatAccountsSizeEl) chatAccountsSizeEl.textContent = formatBytes(chatAndAccountBytes);
        if (chatAccountsRecordsEl) chatAccountsRecordsEl.textContent = `${formatNumber(chatAndAccountRecords)} chat & user records`;

        // Progress Bar Calculation
        const totalEstimated = overviewData.totalEstimatedBytes > 0 ? overviewData.totalEstimatedBytes : 1;
        const apiPct = Math.max(0, ((overviewData.apiCacheBytes / totalEstimated) * 100)).toFixed(1);
        const scoutPct = Math.max(0, ((overviewData.userScoutingBytes / totalEstimated) * 100)).toFixed(1);
        const chatPct = Math.max(0, ((overviewData.chatBytes / totalEstimated) * 100)).toFixed(1);
        const accountsPct = Math.max(0, ((overviewData.accountsBytes / totalEstimated) * 100)).toFixed(1);
        const configPct = Math.max(0, ((overviewData.systemConfigBytes / totalEstimated) * 100)).toFixed(1);

        document.getElementById("bar-api").style.width = `${apiPct}%`;
        document.getElementById("bar-scout").style.width = `${scoutPct}%`;
        document.getElementById("bar-chat").style.width = `${chatPct}%`;
        document.getElementById("bar-accounts").style.width = `${accountsPct}%`;
        document.getElementById("bar-config").style.width = `${configPct}%`;

        const barSummary = document.getElementById("storage-bar-summary");
        if (barSummary) {
            barSummary.textContent = `API Cache: ${apiPct}% • Scouting: ${scoutPct}% • Other: ${(100 - parseFloat(apiPct) - parseFloat(scoutPct)).toFixed(1)}%`;
        }
    }

    // ── Render API Event Caches Table ───────────────────────────────────────
    function populateYearFilter() {
        const yearSelect = document.getElementById("filter-event-year");
        if (!yearSelect) return;
        const years = Array.from(new Set(eventsData.map(e => e.year))).filter(y => y > 0).sort((a, b) => b - a);
        
        const currentSelected = yearSelect.value;
        yearSelect.innerHTML = `<option value="">All Years</option>` + years.map(y => `<option value="${y}">${y}</option>`).join("");
        if (currentSelected) yearSelect.value = currentSelected;
    }

    function renderEventsTable() {
        const tbody = document.getElementById("event-cache-table-body");
        if (!tbody) return;

        const search = (document.getElementById("search-event")?.value || "").trim().toLowerCase();
        const yearFilter = document.getElementById("filter-event-year")?.value;

        const filtered = eventsData.filter(e => {
            if (yearFilter && String(e.year) !== yearFilter) return false;
            if (search && !e.eventKey.toLowerCase().includes(search) && !e.name.toLowerCase().includes(search)) return false;
            return true;
        });

        if (filtered.length === 0) {
            tbody.innerHTML = `<tr><td colspan="9" style="text-align: center; color: var(--muted); padding: 24px;">No cached events matched your filter.</td></tr>`;
            return;
        }

        tbody.innerHTML = filtered.map(e => {
            const hasScouting = e.userScoutingEntryCount > 0;
            const scoutingBadge = hasScouting
                ? `<span class="stat-badge badge-danger" title="${e.userScoutingEntryCount} submitted scouting records">${e.userScoutingEntryCount} Forms</span>`
                : `<span class="stat-badge badge-safe" style="opacity: 0.7;">0 Forms</span>`;

            const epaBadge = e.hasEpaHistory
                ? `<span class="stat-badge badge-safe">Cached</span>`
                : `<span class="stat-badge" style="background: rgba(0,0,0,0.05); color: var(--muted);">None</span>`;

            return `
                <tr>
                    <td><strong>${escapeHtml(e.eventKey)}</strong></td>
                    <td title="${escapeHtml(e.name)}">${escapeHtml(e.name || "-")}</td>
                    <td>${e.year || "-"}</td>
                    <td>${formatNumber(e.matchCount)}</td>
                    <td>${formatNumber(e.teamCount)}</td>
                    <td>${epaBadge}</td>
                    <td>${formatBytes(e.cacheBytes)}</td>
                    <td>${scoutingBadge}</td>
                    <td>
                        <div style="display: flex; gap: 6px;">
                            <button class="btn-table-action purge" data-action="clear-cache" data-key="${escapeHtml(e.eventKey)}" data-name="${escapeHtml(e.name)}" title="Purge external API cache (Safe to re-sync)">Clear Cache</button>
                            ${hasScouting ? `<button class="btn-table-action danger" data-action="delete-event-scouting" data-key="${escapeHtml(e.eventKey)}" data-name="${escapeHtml(e.name)}" title="Permanently delete user scouting forms for this event">Delete Scouting</button>` : ''}
                        </div>
                    </td>
                </tr>
            `;
        }).join("");
    }

    // ── Render Team Storage Table ───────────────────────────────────────────
    function renderTeamsTable() {
        const tbody = document.getElementById("team-storage-table-body");
        if (!tbody) return;

        const search = (document.getElementById("search-team-number")?.value || "").trim();
        const programFilter = document.getElementById("filter-team-program")?.value;

        const filtered = teamsData.filter(t => {
            if (programFilter && t.program !== programFilter) return false;
            if (search && !String(t.teamNumber).includes(search)) return false;
            return true;
        });

        if (filtered.length === 0) {
            tbody.innerHTML = `<tr><td colspan="10" style="text-align: center; color: var(--muted); padding: 24px;">No scouting teams matched your filter.</td></tr>`;
            return;
        }

        tbody.innerHTML = filtered.map(t => {
            return `
                <tr>
                    <td><strong>Team ${t.teamNumber}</strong></td>
                    <td><span class="stat-badge badge-info">${escapeHtml(t.program)}</span></td>
                    <td><strong>${formatBytes(t.totalBytes)}</strong></td>
                    <td>${formatNumber(t.matchEntryCount)} <span style="font-size: 11px; color: var(--muted);">(${formatBytes(t.matchBytes)})</span></td>
                    <td>${formatNumber(t.pitEntryCount)} <span style="font-size: 11px; color: var(--muted);">(${formatBytes(t.pitBytes)})</span></td>
                    <td>${formatNumber(t.qualEntryCount)} <span style="font-size: 11px; color: var(--muted);">(${formatBytes(t.qualBytes)})</span></td>
                    <td>${formatNumber(t.configRevisionCount)} revs</td>
                    <td>${formatNumber(t.chatMessageCount)} msgs</td>
                    <td>${formatNumber(t.userCount)} users</td>
                    <td>
                        <div style="display: flex; gap: 6px;">
                            <button class="btn-table-action purge" data-action="inspect-team" data-team="${t.teamNumber}" data-program="${escapeHtml(t.program)}">Details</button>
                            <button class="btn-table-action danger" data-action="delete-team" data-team="${t.teamNumber}" data-program="${escapeHtml(t.program)}" title="Delete all team scouting data and configs">Wipe Team Data</button>
                        </div>
                    </td>
                </tr>
            `;
        }).join("");
    }

    // ── Table Action Delegation ─────────────────────────────────────────────
    document.addEventListener("click", async (e) => {
        const btn = e.target.closest("[data-action]");
        if (!btn) return;
        const action = btn.dataset.action;

        // 1. Single Event Safe Cache Clear
        if (action === "clear-cache") {
            const key = btn.dataset.key;
            const name = btn.dataset.name || key;
            openSafeCacheModal({
                title: "Clear API Event Cache",
                desc: `Are you sure you want to clear the cached match schedules, team rosters, and EPA stats for <strong>${escapeHtml(name)}</strong> (<code>${escapeHtml(key)}</code>)?`,
                onConfirm: async () => {
                    return Obsidianscout.request("/api/admin/storage/cache/clear-event", {
                        method: "POST",
                        json: { eventKey: key }
                    });
                }
            });
        }

        // 2. Delete User Event Scouting Data (DANGER)
        if (action === "delete-event-scouting") {
            const key = btn.dataset.key;
            const name = btn.dataset.name || key;
            openDangerModal({
                targetDescription: `You are about to permanently delete ALL submitted match, pit, and qualitative scouting entries for <strong>${escapeHtml(name)}</strong> (<code>${escapeHtml(key)}</code>) across all scouting teams.`,
                confirmExpected: "CONFIRM",
                onConfirm: async () => {
                    return Obsidianscout.request("/api/admin/storage/user-data/delete-event", {
                        method: "POST",
                        json: { eventKey: key, confirmText: "CONFIRM" }
                    });
                }
            });
        }

        // 3. Inspect Team Drilldown
        if (action === "inspect-team") {
            const teamNum = parseInt(btn.dataset.team, 10);
            const program = btn.dataset.program || "FRC";
            openTeamDetailModal(teamNum, program);
        }

        // 4. Delete Entire Team Dataset (DANGER)
        if (action === "delete-team") {
            const teamNum = parseInt(btn.dataset.team, 10);
            const program = btn.dataset.program || "FRC";
            const expected = `DELETE TEAM ${teamNum}`;
            openDangerModal({
                targetDescription: `You are about to permanently delete the entire dataset for <strong>Team ${teamNum} (${program})</strong>, including all match scouting, pit scouting, qualitative notes, custom form configurations, revision history, analytics reports, and chat channels.`,
                confirmExpected: expected,
                onConfirm: async () => {
                    return Obsidianscout.request("/api/admin/storage/user-data/delete-team", {
                        method: "POST",
                        json: { teamNumber: teamNum, program: program, confirmText: expected }
                    });
                }
            });
        }
    });

    // ── Bulk Actions: Event Caches ───────────────────────────────────────────
    const btnClearAllApi = document.getElementById("btn-clear-all-api");
    if (btnClearAllApi) {
        btnClearAllApi.addEventListener("click", () => {
            openSafeCacheModal({
                title: "Clear ALL API Event Caches",
                desc: "Are you sure you want to clear <strong>all cached events, match schedules, teams, and Statbotics EPA history</strong> across all seasons?",
                onConfirm: async () => {
                    return Obsidianscout.request("/api/admin/storage/cache/clear-all", {
                        method: "POST",
                        json: {}
                    });
                }
            });
        });
    }

    const btnClearOldEvents = document.getElementById("btn-clear-old-events");
    if (btnClearOldEvents) {
        btnClearOldEvents.addEventListener("click", () => {
            const defaultYear = new Date().getFullYear() - 1;
            const yearStr = prompt(`Enter cutoff competition year to clear API event caches (e.g. ${defaultYear}):`, String(defaultYear));
            if (!yearStr) return;
            const cutoffYear = parseInt(yearStr.trim(), 10);
            if (isNaN(cutoffYear) || cutoffYear < 2000 || cutoffYear > 2100) {
                Obsidianscout.showToast("Invalid year entered.", "error");
                return;
            }

            openSafeCacheModal({
                title: `Clear API Caches for Events \u2264 ${cutoffYear}`,
                desc: `Are you sure you want to purge external API caches for all events from year <strong>${cutoffYear} or earlier</strong>?`,
                onConfirm: async () => {
                    return Obsidianscout.request("/api/admin/storage/cache/clear-old-years", {
                        method: "POST",
                        json: { olderThanYear: cutoffYear }
                    });
                }
            });
        });
    }

    // ── Maintenance Actions ──────────────────────────────────────────────────
    const btnPruneRevisions = document.getElementById("btn-prune-revisions");
    if (btnPruneRevisions) {
        btnPruneRevisions.addEventListener("click", async () => {
            const keep = parseInt(document.getElementById("input-keep-revisions")?.value || "10", 10);
            Obsidianscout.setButtonLoading(btnPruneRevisions, true, "Pruning...");
            try {
                const res = await Obsidianscout.request("/api/admin/storage/prune/config-revisions", {
                    method: "POST",
                    json: { keepLatestPerKind: keep }
                });
                Obsidianscout.showToast(res.message || "Config revisions pruned successfully.", "success");
                loadAllStorageData();
            } catch (err) {
                Obsidianscout.showToast("Pruning failed: " + (err.message || "Unknown error"), "error");
            } finally {
                Obsidianscout.setButtonLoading(btnPruneRevisions, false, "Prune Older Revisions");
            }
        });
    }

    const btnPruneChat = document.getElementById("btn-prune-chat");
    if (btnPruneChat) {
        btnPruneChat.addEventListener("click", async () => {
            const days = parseInt(document.getElementById("input-chat-days")?.value || "90", 10);
            if (!confirm(`Are you sure you want to delete all team chat messages older than ${days} days?`)) return;

            Obsidianscout.setButtonLoading(btnPruneChat, true, "Pruning...");
            try {
                const res = await Obsidianscout.request("/api/admin/storage/prune/chat", {
                    method: "POST",
                    json: { olderThanDays: days }
                });
                Obsidianscout.showToast(res.message || "Chat messages pruned successfully.", "success");
                loadAllStorageData();
            } catch (err) {
                Obsidianscout.showToast("Pruning failed: " + (err.message || "Unknown error"), "error");
            } finally {
                Obsidianscout.setButtonLoading(btnPruneChat, false, "Prune Chat History");
            }
        });
    }

    const btnPruneSessions = document.getElementById("btn-prune-sessions");
    if (btnPruneSessions) {
        btnPruneSessions.addEventListener("click", async () => {
            Obsidianscout.setButtonLoading(btnPruneSessions, true, "Cleaning...");
            try {
                const res = await Obsidianscout.request("/api/admin/storage/prune/sessions", {
                    method: "POST",
                    json: {}
                });
                Obsidianscout.showToast(res.message || "Expired sessions cleaned successfully.", "success");
                loadAllStorageData();
            } catch (err) {
                Obsidianscout.showToast("Cleanup failed: " + (err.message || "Unknown error"), "error");
            } finally {
                Obsidianscout.setButtonLoading(btnPruneSessions, false, "Clean Expired Sessions");
            }
        });
    }

    const btnReclaimSpace = document.getElementById("btn-reclaim-space");
    if (btnReclaimSpace) {
        btnReclaimSpace.addEventListener("click", async () => {
            Obsidianscout.setButtonLoading(btnReclaimSpace, true, "Executing VACUUM...");
            try {
                const res = await Obsidianscout.request("/api/admin/storage/maintenance/reclaim", {
                    method: "POST",
                    json: {}
                });
                Obsidianscout.showToast(res.message || "Database defragmentation completed.", "success");
                loadAllStorageData();
            } catch (err) {
                Obsidianscout.showToast("Reclaim operation failed: " + (err.message || "Unknown error"), "error");
            } finally {
                Obsidianscout.setButtonLoading(btnReclaimSpace, false, "Execute Database VACUUM");
            }
        });
    }

    // ── Modals Management ────────────────────────────────────────────────────
    function openSafeCacheModal({ title, desc, onConfirm }) {
        const titleEl = document.querySelector("#modal-cache-purge .modal-title");
        const descEl = document.getElementById("cache-purge-desc");

        if (titleEl) titleEl.textContent = title;
        if (descEl) descEl.innerHTML = desc;
        activeCachePurgeAction = onConfirm;

        openModal("modal-cache-purge");
    }

    const confirmCachePurgeBtn = document.getElementById("btn-confirm-cache-purge");
    if (confirmCachePurgeBtn) {
        confirmCachePurgeBtn.addEventListener("click", async () => {
            if (!activeCachePurgeAction) return;
            Obsidianscout.setButtonLoading(confirmCachePurgeBtn, true, "Clearing...");
            try {
                const res = await activeCachePurgeAction();
                Obsidianscout.showToast((res && res.message) ? res.message : "API Cache cleared successfully.", "success");
                closeModal("modal-cache-purge");
                loadAllStorageData();
            } catch (err) {
                Obsidianscout.showToast("Cache clearing failed: " + (err.message || "Unknown error"), "error");
            } finally {
                Obsidianscout.setButtonLoading(confirmCachePurgeBtn, false, "Clear API Cache");
            }
        });
    }

    function openDangerModal({ targetDescription, confirmExpected, onConfirm }) {
        const descEl = document.getElementById("danger-target-description");
        const labelEl = document.getElementById("danger-confirm-label");
        const inputEl = document.getElementById("input-danger-confirm");
        const executeBtn = document.getElementById("btn-execute-danger-delete");

        if (descEl) descEl.innerHTML = targetDescription;
        if (labelEl) labelEl.innerHTML = `Type <code>${escapeHtml(confirmExpected)}</code> to proceed:`;
        if (inputEl) {
            inputEl.value = "";
            inputEl.placeholder = `Type '${confirmExpected}' here`;
        }
        if (executeBtn) executeBtn.disabled = true;

        activeDangerAction = {
            confirmExpected: confirmExpected.trim().toUpperCase().replace(/\s+/g, ' '),
            onConfirm: onConfirm
        };

        openModal("modal-danger-delete");
        setTimeout(() => {
            inputEl?.focus();
        }, 100);
    }

    const inputDangerConfirm = document.getElementById("input-danger-confirm");
    const btnExecuteDangerDelete = document.getElementById("btn-execute-danger-delete");

    if (inputDangerConfirm && btnExecuteDangerDelete) {
        inputDangerConfirm.addEventListener("input", () => {
            if (!activeDangerAction) return;
            const typed = inputDangerConfirm.value.trim().toUpperCase().replace(/\s+/g, ' ');
            btnExecuteDangerDelete.disabled = (typed !== activeDangerAction.confirmExpected);
        });

        inputDangerConfirm.addEventListener("keydown", (e) => {
            if (e.key === "Enter" && !btnExecuteDangerDelete.disabled) {
                btnExecuteDangerDelete.click();
            }
        });

        btnExecuteDangerDelete.addEventListener("click", async () => {
            if (!activeDangerAction || btnExecuteDangerDelete.disabled) return;
            Obsidianscout.setButtonLoading(btnExecuteDangerDelete, true, "Deleting...");
            try {
                const res = await activeDangerAction.onConfirm();
                if (res && res.success === false) {
                    Obsidianscout.showToast(res.message || "Deletion failed", "error");
                } else {
                    Obsidianscout.showToast((res && res.message) ? res.message : "Data deleted successfully.", "success");
                    closeModal("modal-danger-delete");
                    loadAllStorageData();
                }
            } catch (err) {
                Obsidianscout.showToast("Deletion failed: " + (err.message || "Unknown error"), "error");
            } finally {
                Obsidianscout.setButtonLoading(btnExecuteDangerDelete, false, "Permanently Delete");
            }
        });
    }

    async function openTeamDetailModal(teamNumber, program) {
        const titleEl = document.getElementById("team-detail-title");
        const summaryEl = document.getElementById("team-detail-summary");
        const eventsBody = document.getElementById("team-detail-events-body");

        if (titleEl) titleEl.textContent = `Team ${teamNumber} (${program}) Storage Breakdown`;
        if (summaryEl) summaryEl.innerHTML = `<div style="color: var(--muted); padding: 10px;">Loading team summary...</div>`;
        if (eventsBody) eventsBody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--muted); padding: 16px;">Loading event breakdown...</td></tr>`;
        
        openModal("modal-team-detail");

        try {
            const detail = await Obsidianscout.request(`/api/admin/storage/teams/${teamNumber}?program=${encodeURIComponent(program)}`);
            if (!detail) {
                if (summaryEl) summaryEl.innerHTML = `<div style="color: var(--danger); padding: 10px;">No details found for Team ${teamNumber}.</div>`;
                return;
            }

            const sum = detail.summary;
            if (summaryEl) {
                summaryEl.innerHTML = `
                    <div style="background: var(--surface-2); padding: 14px 18px; border-radius: 10px; font-size: 13px; display: grid; grid-template-columns: 1fr 1fr; gap: 10px;">
                        <div><strong>Total Data Size:</strong> ${formatBytes(sum.totalBytes)}</div>
                        <div><strong>Total Records:</strong> ${formatNumber(sum.totalRecords)}</div>
                        <div><strong>Match Forms:</strong> ${formatNumber(sum.matchEntryCount)} (${formatBytes(sum.matchBytes)})</div>
                        <div><strong>Pit Forms:</strong> ${formatNumber(sum.pitEntryCount)} (${formatBytes(sum.pitBytes)})</div>
                        <div><strong>Qual Forms:</strong> ${formatNumber(sum.qualEntryCount)} (${formatBytes(sum.qualBytes)})</div>
                        <div><strong>Config Revisions:</strong> ${formatNumber(sum.configRevisionCount)} (${formatBytes(sum.configRevisionBytes)})</div>
                        <div><strong>Chat Messages:</strong> ${formatNumber(sum.chatMessageCount)} (${formatBytes(sum.chatBytes)})</div>
                        <div><strong>Registered Users:</strong> ${formatNumber(sum.userCount)}</div>
                    </div>
                `;
            }

            if (eventsBody) {
                if (!detail.events || detail.events.length === 0) {
                    eventsBody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--muted); padding: 16px;">No event scouting entries recorded for Team ${teamNumber}.</td></tr>`;
                } else {
                    eventsBody.innerHTML = detail.events.map(ev => `
                        <tr>
                            <td><strong>${escapeHtml(ev.eventKey)}</strong></td>
                            <td>${formatNumber(ev.matchCount)}</td>
                            <td>${formatNumber(ev.pitCount)}</td>
                            <td>${formatNumber(ev.qualCount)}</td>
                            <td><strong>${formatBytes(ev.totalBytes)}</strong></td>
                        </tr>
                    `).join("");
                }
            }
        } catch (err) {
            console.error("Failed to load team detailed storage:", err);
            Obsidianscout.showToast("Failed to load team details: " + (err.message || "Unknown error"), "error");
            if (eventsBody) {
                eventsBody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--danger); padding: 16px;">Error loading event breakdown: ${escapeHtml(err.message)}</td></tr>`;
            }
        }
    }

    // Filter Listeners
    document.getElementById("search-event")?.addEventListener("input", renderEventsTable);
    document.getElementById("filter-event-year")?.addEventListener("change", renderEventsTable);
    document.getElementById("search-team-number")?.addEventListener("input", renderTeamsTable);
    document.getElementById("filter-team-program")?.addEventListener("change", renderTeamsTable);

    document.getElementById("btn-refresh-storage")?.addEventListener("click", loadAllStorageData);

    function escapeHtml(str) {
        if (!str) return "";
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }

    // Initial Load
    loadAllStorageData();
});
