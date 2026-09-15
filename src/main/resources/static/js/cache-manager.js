
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    // Cache config mapping
    const cacheConfigs = Obsidianscout.CACHE_CONFIGS;

    // Tab Elements
    const tabBtnPending = document.getElementById("tab-btn-pending");
    const tabBtnHistory = document.getElementById("tab-btn-history");
    const sectionPending = document.getElementById("section-pending");
    const sectionHistory = document.getElementById("section-history");
    const pendingBadgeTotal = document.getElementById("pending-badge-total");
    const historyBadgeTotal = document.getElementById("history-badge-total");

    // Modal elements
    const modalBackdrop = document.getElementById("payload-modal-backdrop");
    const modalTitle = document.getElementById("payload-modal-title");
    const modalContent = document.getElementById("payload-modal-body-content");
    const modalCloseBtn = document.getElementById("payload-modal-close-btn");
    const modalCloseFooterBtn = document.getElementById("payload-modal-close-footer-btn");

    function showPayloadModal(title, data) {
        modalTitle.textContent = title;
        modalContent.textContent = JSON.stringify(data, null, 2);
        modalBackdrop.classList.add("show");
    }

    function closePayloadModal() {
        modalBackdrop.classList.remove("show");
    }

    modalCloseBtn.addEventListener("click", closePayloadModal);
    modalCloseFooterBtn.addEventListener("click", closePayloadModal);

    // =========================================================================
    // TAB SWITCHING
    // =========================================================================
    let activeTab = "history";

    function switchTab(tab) {
        activeTab = tab;
        if (tab === "pending") {
            tabBtnPending.classList.add("active");
            tabBtnHistory.classList.remove("active");
            sectionPending.classList.remove("hidden");
            sectionHistory.classList.add("hidden");
            loadAndRenderPendingEntries();
        } else {
            tabBtnHistory.classList.add("active");
            tabBtnPending.classList.remove("active");
            sectionHistory.classList.remove("hidden");
            sectionPending.classList.add("hidden");
            loadAndRenderHistoryEntries();
        }
    }

    tabBtnPending.addEventListener("click", () => switchTab("pending"));
    tabBtnHistory.addEventListener("click", () => switchTab("history"));

    // =========================================================================
    // SECTION 1: PENDING OFFLINE CACHE
    // =========================================================================
    const emptyNotice = document.getElementById("cache-empty-notice");
    const tableContainer = document.getElementById("cache-table-container");
    const entriesBody = document.getElementById("cache-entries-body");

    // Pending Filters
    const filterType = document.getElementById("filter-type");
    const filterTeam = document.getElementById("filter-team");
    const filterEvent = document.getElementById("filter-event");

    // Pending Actions
    const btnSyncAll = document.getElementById("btn-sync-all");
    const btnExportCache = document.getElementById("btn-export-cache");
    const btnImportCache = document.getElementById("btn-import-cache");
    const btnClearCache = document.getElementById("btn-clear-cache");
    const fileImportInput = document.getElementById("cache-import-file");

    function updatePendingStats() {
        const stats = {
            "match": "pending_scouting_entries",
            "pit": "pending_pit_scouting_entries",
            "qual": "pending_qualitative_entries",
            "prescout-match": "pending_prescout_scouting_entries",
            "prescout-pit": "pending_prescout_pit_scouting_entries",
            "prescout-qual": "pending_prescout_qualitative_entries"
        };

        let grandTotal = 0;
        for (const statId in stats) {
            const key = stats[statId];
            const pending = JSON.parse(Obsidianscout.safeGetItem(key) || "[]");
            const count = pending.length;
            grandTotal += count;
            const el = document.getElementById(`count-${statId}`);
            if (el) el.textContent = count;
        }

        if (pendingBadgeTotal) {
            pendingBadgeTotal.textContent = grandTotal;
        }
    }

    function loadAndRenderPendingEntries() {
        updatePendingStats();

        let allEntries = [];

        // Read all 6 caches
        for (const type in cacheConfigs) {
            const config = cacheConfigs[type];
            const pending = JSON.parse(Obsidianscout.safeGetItem(config.key) || "[]");
            pending.forEach((item, index) => {
                const itemData = item.data || item;
                allEntries.push({
                    type: type,
                    index: index,
                    config: config,
                    item: item,
                    itemData: itemData,
                    eventKey: itemData.eventKey || "",
                    teamNumber: itemData.targetTeamNumber || itemData.teamNumber || "",
                    matchKey: itemData.matchKey || itemData.matchNumber || "",
                    createdAt: item.createdAt || itemData.createdAt || ""
                });
            });
        }

        // Apply filters
        const typeVal = filterType.value;
        const teamVal = filterTeam.value.trim();
        const eventVal = filterEvent.value.trim().toLowerCase();

        const filtered = allEntries.filter(entry => {
            if (typeVal && entry.type !== typeVal) return false;
            if (teamVal && String(entry.teamNumber) !== teamVal) return false;
            if (eventVal && !String(entry.eventKey).toLowerCase().includes(eventVal)) return false;
            return true;
        });

        // Sort newest first
        filtered.sort((a, b) => {
            if (a.createdAt && b.createdAt) {
                return new Date(b.createdAt) - new Date(a.createdAt);
            }
            if (a.createdAt) return -1;
            if (b.createdAt) return 1;
            return 0;
        });

        // Render table
        entriesBody.innerHTML = "";

        if (filtered.length === 0) {
            emptyNotice.classList.remove("hidden");
            tableContainer.classList.add("hidden");
            return;
        }

        emptyNotice.classList.add("hidden");
        tableContainer.classList.remove("hidden");

        filtered.forEach(entry => {
            const tr = document.createElement("tr");

            // Server status cell
            const tdStatus = document.createElement("td");
            const statusBadge = document.createElement("span");
            statusBadge.className = "badge-status badge-pending";
            statusBadge.innerHTML = `⚠️ <span data-i18n="cache-manager.not_on_server">Not on Server</span>`;
            tdStatus.appendChild(statusBadge);
            tr.appendChild(tdStatus);

            // Type cell
            const tdType = document.createElement("td");
            const badge = document.createElement("span");
            badge.className = `badge-type badge-${getBadgeClass(entry.type)}`;
            badge.textContent = entry.config.label;
            tdType.appendChild(badge);
            tr.appendChild(tdType);

            // Event cell
            const tdEvent = document.createElement("td");
            tdEvent.textContent = entry.eventKey.toUpperCase();
            tr.appendChild(tdEvent);

            // Team cell
            const tdTeam = document.createElement("td");
            tdTeam.textContent = entry.teamNumber;
            tr.appendChild(tdTeam);

            // Match cell
            const tdMatch = document.createElement("td");
            tdMatch.textContent = entry.matchKey ? String(entry.matchKey).toUpperCase() : "N/A";
            tr.appendChild(tdMatch);

            // Timestamp cell
            const tdTime = document.createElement("td");
            tdTime.textContent = entry.createdAt ? new Date(entry.createdAt).toLocaleString() : "Saved Offline";
            tr.appendChild(tdTime);

            // Actions cell
            const tdActions = document.createElement("td");
            tdActions.className = "action-cell";

            // Upload button
            const isOnline = (Obsidianscout && typeof Obsidianscout.isServerOnline === 'function') ? Obsidianscout.isServerOnline() : navigator.onLine;
            const btnSync = document.createElement("button");
            btnSync.className = "btn-mini sync";
            btnSync.textContent = "Upload";
            btnSync.disabled = !isOnline;
            btnSync.addEventListener("click", () => syncPendingEntry(entry));
            tdActions.appendChild(btnSync);

            // Generate QR Code button
            const btnQr = document.createElement("button");
            btnQr.className = "btn-mini qr";
            btnQr.textContent = "QR Code";
            btnQr.addEventListener("click", () => {
                const qrData = entry.item.data || entry.item;
                Obsidianscout.showQrModal(qrData, entry.config.label, entry.teamNumber, entry.matchKey);
                Obsidianscout.recordDeviceHistory({
                    action: "qr_generated",
                    formType: entry.type,
                    eventKey: entry.eventKey,
                    teamNumber: entry.teamNumber,
                    matchKey: entry.matchKey,
                    matchNumber: qrData.matchNumber || null,
                    scoutName: me ? me.username : null,
                    payload: qrData,
                    serverSynced: false
                });
            });
            tdActions.appendChild(btnQr);

            // View button
            const btnView = document.createElement("button");
            btnView.className = "btn-mini view";
            btnView.textContent = "View";
            btnView.addEventListener("click", () => showPayloadModal(`${entry.config.label} - Team ${entry.teamNumber}`, entry.item));
            tdActions.appendChild(btnView);

            // Delete button
            const btnDelete = document.createElement("button");
            btnDelete.className = "btn-mini delete";
            btnDelete.textContent = "Delete";
            btnDelete.addEventListener("click", () => deletePendingEntry(entry));
            tdActions.appendChild(btnDelete);

            tr.appendChild(tdActions);
            entriesBody.appendChild(tr);
        });
    }

    async function syncPendingEntry(entry) {
        const isOnline = (Obsidianscout && typeof Obsidianscout.isServerOnline === 'function') ? Obsidianscout.isServerOnline() : navigator.onLine;
        if (!isOnline) {
            Obsidianscout.showToast("Server is offline", "error");
            return;
        }

        try {
            await Obsidianscout.request(entry.config.endpoint, {
                method: "POST",
                json: entry.item
            });
            
            // Remove from cache
            const pending = JSON.parse(Obsidianscout.safeGetItem(entry.config.key) || "[]");
            pending.splice(entry.index, 1);
            Obsidianscout.safeSetItem(entry.config.key, JSON.stringify(pending));

            // Mark matching record as synced in device history
            try {
                Obsidianscout.markMatchingEntryAsSynced(entry.type, entry.eventKey, entry.teamNumber, entry.matchKey);
            } catch (e) {
                console.warn("Failed to mark history entry as synced:", e);
            }

            Obsidianscout.showToast("Entry uploaded successfully!", "success");
            Obsidianscout.updateConnectionStatus();
            loadAndRenderPendingEntries();
            updateHistoryStats();
        } catch (error) {
            console.error("Failed to sync entry:", error);
            Obsidianscout.showToast(error.message || "Failed to upload entry", "error");
        }
    }

    function deletePendingEntry(entry) {
        if (confirm(`Are you sure you want to delete this cached ${entry.config.label} entry for Team ${entry.teamNumber}?`)) {
            const pending = JSON.parse(Obsidianscout.safeGetItem(entry.config.key) || "[]");
            pending.splice(entry.index, 1);
            Obsidianscout.safeSetItem(entry.config.key, JSON.stringify(pending));

            Obsidianscout.showToast("Entry deleted from cache", "success");
            Obsidianscout.updateConnectionStatus();
            loadAndRenderPendingEntries();
        }
    }

    btnSyncAll.addEventListener("click", async () => {
        const isOnline = (Obsidianscout && typeof Obsidianscout.isServerOnline === 'function') ? Obsidianscout.isServerOnline() : navigator.onLine;
        if (!isOnline) {
            Obsidianscout.showToast("Server is offline", "error");
            return;
        }

        Obsidianscout.setButtonLoading(btnSyncAll, true, "Uploading...");
        try {
            await Obsidianscout.syncOfflineEntries();
            loadAndRenderPendingEntries();
            updateHistoryStats();
        } catch (error) {
            console.error(error);
        } finally {
            Obsidianscout.setButtonLoading(btnSyncAll, false);
        }
    });

    btnExportCache.addEventListener("click", () => {
        const cacheData = {};
        let count = 0;

        for (const type in cacheConfigs) {
            const config = cacheConfigs[type];
            const pending = JSON.parse(Obsidianscout.safeGetItem(config.key) || "[]");
            cacheData[config.key] = pending;
            count += pending.length;
        }

        if (count === 0) {
            Obsidianscout.showToast("No cached data to export", "info");
            return;
        }

        const dateStr = new Date().toISOString().slice(0, 10);
        Obsidianscout.downloadJson(cacheData, `obsidianscout_cache_backup_${dateStr}.json`);
    });

    btnImportCache.addEventListener("click", () => {
        fileImportInput.click();
    });

    fileImportInput.addEventListener("change", (e) => {
        const file = e.target.files[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = function(evt) {
            try {
                const data = JSON.parse(evt.target.result);
                let importCount = 0;

                for (const key in data) {
                    const matchedConfig = Object.values(cacheConfigs).find(c => c.key === key);
                    if (matchedConfig && Array.isArray(data[key])) {
                        const existing = JSON.parse(Obsidianscout.safeGetItem(key) || "[]");
                        
                        data[key].forEach(newItem => {
                            const isDuplicate = existing.some(oldItem => 
                                (oldItem.data?.eventKey || oldItem.eventKey) === (newItem.data?.eventKey || newItem.eventKey) &&
                                (oldItem.data?.targetTeamNumber || oldItem.targetTeamNumber) === (newItem.data?.targetTeamNumber || newItem.targetTeamNumber) &&
                                (oldItem.data?.matchKey || oldItem.matchKey) === (newItem.data?.matchKey || newItem.matchKey)
                            );
                            if (!isDuplicate) {
                                existing.push(newItem);
                                importCount++;
                            }
                        });

                        Obsidianscout.safeSetItem(key, JSON.stringify(existing));
                    }
                }

                Obsidianscout.showToast(`Imported ${importCount} new entries from backup!`, "success");
                Obsidianscout.updateConnectionStatus();
                loadAndRenderPendingEntries();
            } catch (err) {
                console.error("Failed to parse import file:", err);
                Obsidianscout.showToast("Invalid cache JSON backup file", "error");
            }
            fileImportInput.value = "";
        };
        reader.readAsText(file);
    });

    btnClearCache.addEventListener("click", () => {
        if (confirm("WARNING: Are you sure you want to delete ALL offline cached entries? These entries will NOT be uploaded to the server.")) {
            for (const type in cacheConfigs) {
                const config = cacheConfigs[type];
                Obsidianscout.safeRemoveItem(config.key);
            }
            Obsidianscout.showToast("All cached entries cleared", "success");
            Obsidianscout.updateConnectionStatus();
            loadAndRenderPendingEntries();
        }
    });

    filterType.addEventListener("change", loadAndRenderPendingEntries);
    filterTeam.addEventListener("input", loadAndRenderPendingEntries);
    filterEvent.addEventListener("input", loadAndRenderPendingEntries);

    // =========================================================================
    // SECTION 2: DEVICE HISTORY & LOCAL BACKUP
    // =========================================================================
    const histEmptyNotice = document.getElementById("hist-empty-notice");
    const histTableContainer = document.getElementById("hist-table-container");
    const histEntriesBody = document.getElementById("hist-entries-body");

    // History Filters
    const histFilterStatus = document.getElementById("hist-filter-status");
    const histFilterAction = document.getElementById("hist-filter-action");
    const histFilterType = document.getElementById("hist-filter-type");
    const histFilterTeam = document.getElementById("hist-filter-team");
    const histFilterEvent = document.getElementById("hist-filter-event");

    // History Actions
    const btnExportHistory = document.getElementById("btn-export-history");
    const btnImportHistory = document.getElementById("btn-import-history");
    const btnClearHistory = document.getElementById("btn-clear-history");
    const historyImportInput = document.getElementById("history-import-file");

    function updateHistoryStats() {
        const history = Obsidianscout.getDeviceHistory();
        const total = history.length;
        const unsynced = history.filter(e => !e.serverSynced).length;
        const synced = history.filter(e => e.serverSynced).length;
        const qrs = history.filter(e => e.action === "qr_generated").length;
        const exports = history.filter(e => e.action === "json_export").length;

        const countTotalEl = document.getElementById("hist-count-total");
        const countPendingEl = document.getElementById("hist-count-pending");
        const countSyncedEl = document.getElementById("hist-count-synced");
        const countQrEl = document.getElementById("hist-count-qr");
        const countExportEl = document.getElementById("hist-count-export");

        if (countTotalEl) countTotalEl.textContent = total;
        if (countPendingEl) countPendingEl.textContent = unsynced;
        if (countSyncedEl) countSyncedEl.textContent = synced;
        if (countQrEl) countQrEl.textContent = qrs;
        if (countExportEl) countExportEl.textContent = exports;

        if (historyBadgeTotal) {
            historyBadgeTotal.textContent = total;
        }
    }

    function loadAndRenderHistoryEntries() {
        updateHistoryStats();

        const history = Obsidianscout.getDeviceHistory();

        // Apply filters
        const statusVal = histFilterStatus.value;
        const actionVal = histFilterAction.value;
        const typeVal = histFilterType.value;
        const teamVal = histFilterTeam.value.trim();
        const eventVal = histFilterEvent.value.trim().toLowerCase();

        const normalizeType = (t) => {
            if (!t) return "";
            const s = String(t).toLowerCase().replace(/_/g, '-');
            if (s.includes('pit')) return s.includes('prescout') ? 'prescout-pit' : 'pit';
            if (s.includes('qual')) return s.includes('prescout') ? 'prescout-qual' : 'qual';
            if (s.includes('prescout')) return 'prescout-scout';
            return 'scout';
        };

        const targetNormalizedType = normalizeType(typeVal);

        const filtered = history.filter(entry => {
            if (statusVal === "unsynced" && entry.serverSynced) return false;
            if (statusVal === "synced" && !entry.serverSynced) return false;
            if (actionVal && entry.action !== actionVal) return false;
            if (typeVal && normalizeType(entry.formType) !== targetNormalizedType) return false;
            if (teamVal && String(entry.teamNumber) !== teamVal) return false;
            if (eventVal && !String(entry.eventKey).toLowerCase().includes(eventVal)) return false;
            return true;
        });

        histEntriesBody.innerHTML = "";

        if (filtered.length === 0) {
            histEmptyNotice.classList.remove("hidden");
            histTableContainer.classList.add("hidden");
            return;
        }

        histEmptyNotice.classList.add("hidden");
        histTableContainer.classList.remove("hidden");

        filtered.forEach(entry => {
            const tr = document.createElement("tr");

            // Server status cell
            const tdStatus = document.createElement("td");
            const statusBadge = document.createElement("span");
            if (entry.serverSynced) {
                statusBadge.className = "badge-status badge-synced";
                statusBadge.innerHTML = `✓ <span data-i18n="cache-manager.synced_to_server">Synced to Server</span>`;
            } else {
                statusBadge.className = "badge-status badge-pending";
                statusBadge.innerHTML = `⚠️ <span data-i18n="cache-manager.not_on_server">Not on Server</span>`;
            }
            tdStatus.appendChild(statusBadge);
            tr.appendChild(tdStatus);

            // Action & Form cell
            const tdType = document.createElement("td");
            const typeBadge = document.createElement("span");
            typeBadge.className = `badge-type badge-${getBadgeClass(entry.formType)}`;
            typeBadge.textContent = entry.formLabel || entry.formType;
            tdType.appendChild(typeBadge);

            const actionBadge = document.createElement("div");
            actionBadge.className = "badge-action";
            actionBadge.textContent = entry.actionLabel || entry.action;
            tdType.appendChild(actionBadge);
            tr.appendChild(tdType);

            // Event cell
            const tdEvent = document.createElement("td");
            tdEvent.textContent = (entry.eventKey || "").toUpperCase();
            tr.appendChild(tdEvent);

            // Team cell
            const tdTeam = document.createElement("td");
            tdTeam.textContent = entry.teamNumber || "N/A";
            tr.appendChild(tdTeam);

            // Match cell
            const tdMatch = document.createElement("td");
            tdMatch.textContent = entry.matchKey ? String(entry.matchKey).toUpperCase() : (entry.matchNumber ? `M#${entry.matchNumber}` : "N/A");
            tr.appendChild(tdMatch);

            // Device Timestamp cell
            const tdTime = document.createElement("td");
            tdTime.textContent = entry.timestamp ? new Date(entry.timestamp).toLocaleString() : "Unknown";
            tr.appendChild(tdTime);

            // Actions cell
            const tdActions = document.createElement("td");
            tdActions.className = "action-cell";

            // Generate QR Code button
            const btnQr = document.createElement("button");
            btnQr.className = "btn-mini qr";
            btnQr.textContent = "QR Code";
            btnQr.addEventListener("click", () => {
                Obsidianscout.showQrModal(entry.payload, entry.formLabel || "Scouting Data", entry.teamNumber, entry.matchKey);
            });
            tdActions.appendChild(btnQr);

            // Upload / Re-Upload button
            const isOnline = (Obsidianscout && typeof Obsidianscout.isServerOnline === 'function') ? Obsidianscout.isServerOnline() : navigator.onLine;
            const btnUpload = document.createElement("button");
            btnUpload.className = "btn-mini sync";
            btnUpload.textContent = entry.serverSynced ? "Re-Upload" : "Upload";
            btnUpload.disabled = !isOnline;
            btnUpload.addEventListener("click", () => uploadHistoryEntry(entry));
            tdActions.appendChild(btnUpload);

            // Export single JSON button
            const btnExport = document.createElement("button");
            btnExport.className = "btn-mini export";
            btnExport.textContent = "Export";
            btnExport.addEventListener("click", () => {
                const filename = `history_${entry.eventKey || 'event'}_team${entry.teamNumber || 'team'}_${entry.id}.json`;
                Obsidianscout.downloadJson(entry.payload, filename);
            });
            tdActions.appendChild(btnExport);

            // View Raw JSON button
            const btnView = document.createElement("button");
            btnView.className = "btn-mini view";
            btnView.textContent = "View";
            btnView.addEventListener("click", () => {
                showPayloadModal(`${entry.formLabel || 'Scouting'} - Team ${entry.teamNumber} (${entry.actionLabel})`, entry);
            });
            tdActions.appendChild(btnView);

            // Delete from history button
            const btnDelete = document.createElement("button");
            btnDelete.className = "btn-mini delete";
            btnDelete.textContent = "Delete";
            btnDelete.addEventListener("click", () => {
                if (confirm(`Remove this record for Team ${entry.teamNumber} from device history?`)) {
                    Obsidianscout.deleteDeviceHistoryEntry(entry.id);
                    loadAndRenderHistoryEntries();
                    Obsidianscout.showToast("Record removed from history", "success");
                }
            });
            tdActions.appendChild(btnDelete);

            tr.appendChild(tdActions);
            histEntriesBody.appendChild(tr);
        });
    }

    async function uploadHistoryEntry(entry) {
        const isOnline = (Obsidianscout && typeof Obsidianscout.isServerOnline === 'function') ? Obsidianscout.isServerOnline() : navigator.onLine;
        if (!isOnline) {
            Obsidianscout.showToast("Server is offline", "error");
            return;
        }

        // Robustly resolve config and endpoint
        let config = cacheConfigs[entry.formType];
        if (!config) {
            const formKey = String(entry.formType || "").toLowerCase().replace(/_/g, '-');
            if (formKey.includes('pit')) config = cacheConfigs[formKey.includes('prescout') ? 'prescout-pit-scouting' : 'pit-scouting'];
            else if (formKey.includes('qual')) config = cacheConfigs[formKey.includes('prescout') ? 'prescout-qual-scouting' : 'qual-scouting'];
            else if (formKey.includes('prescout')) config = cacheConfigs['prescout-scouting'];
            else config = cacheConfigs['match-scouting'];
        }
        const endpoint = config ? config.endpoint : "/api/scouting";

        try {
            // Prepare payload according to backend expectations
            const body = (entry.payload && entry.payload.data) ? entry.payload : { data: entry.payload };
            await Obsidianscout.request(endpoint, {
                method: "POST",
                json: body
            });

            // Mark as synced in history
            Obsidianscout.markEntryAsSynced(entry.id);

            // If an identical entry exists in the pending offline cache, remove it as well
            if (config && config.key) {
                try {
                    const pending = JSON.parse(Obsidianscout.safeGetItem(config.key) || "[]");
                    const targetTeam = String(entry.teamNumber);
                    const targetEvent = String(entry.eventKey || "").toLowerCase();
                    const targetMatch = entry.matchKey ? String(entry.matchKey).toLowerCase() : null;

                    const matchIdx = pending.findIndex(item => {
                        const d = item.data || item;
                        const itemTeam = String(d.targetTeamNumber || d.teamNumber || "");
                        const itemEvent = String(d.eventKey || "").toLowerCase();
                        const itemMatch = d.matchKey ? String(d.matchKey).toLowerCase() : null;
                        return itemTeam === targetTeam && (!targetEvent || itemEvent === targetEvent) && (!targetMatch || itemMatch === targetMatch);
                    });

                    if (matchIdx >= 0) {
                        pending.splice(matchIdx, 1);
                        Obsidianscout.safeSetItem(config.key, JSON.stringify(pending));
                        Obsidianscout.updateConnectionStatus();
                    }
                } catch (e) {
                    console.warn("Failed to remove matching entry from pending cache:", e);
                }
            }

            Obsidianscout.showToast(`Uploaded to server successfully!`, "success");
            loadAndRenderHistoryEntries();
            updatePendingStats();
        } catch (error) {
            console.error("Failed to upload history entry:", error);
            Obsidianscout.showToast(error.message || "Failed to upload to server", "error");
        }
    }

    btnExportHistory.addEventListener("click", () => {
        Obsidianscout.exportDeviceHistory();
    });

    btnImportHistory.addEventListener("click", () => {
        historyImportInput.click();
    });

    historyImportInput.addEventListener("change", (e) => {
        const file = e.target.files[0];
        if (!file) return;

        const reader = new FileReader();
        reader.onload = function(evt) {
            try {
                const data = JSON.parse(evt.target.result);
                const count = Obsidianscout.importDeviceHistory(data);
                Obsidianscout.showToast(`Imported ${count} history backup entries!`, "success");
                loadAndRenderHistoryEntries();
            } catch (err) {
                console.error("Failed to import history file:", err);
                Obsidianscout.showToast("Invalid history backup JSON file", "error");
            }
            historyImportInput.value = "";
        };
        reader.readAsText(file);
    });

    btnClearHistory.addEventListener("click", () => {
        if (confirm("WARNING: Are you sure you want to delete ALL local device history records? This action cannot be undone.")) {
            Obsidianscout.clearDeviceHistory();
            loadAndRenderHistoryEntries();
            Obsidianscout.showToast("Device history cleared", "success");
        }
    });

    histFilterStatus.addEventListener("change", loadAndRenderHistoryEntries);
    histFilterAction.addEventListener("change", loadAndRenderHistoryEntries);
    histFilterType.addEventListener("change", loadAndRenderHistoryEntries);
    histFilterTeam.addEventListener("input", loadAndRenderHistoryEntries);
    histFilterEvent.addEventListener("input", loadAndRenderHistoryEntries);

    function getBadgeClass(type) {
        const t = String(type || "").toLowerCase().replace(/_/g, '-');
        if (t.includes('pit')) return t.includes('prescout') ? "prescout-pit" : "pit";
        if (t.includes('qual')) return t.includes('prescout') ? "prescout-qual" : "qual";
        if (t.includes('prescout')) return "prescout-match";
        return "match";
    }

    // =========================================================================
    // SYSTEM EVENTS
    // =========================================================================
    window.addEventListener("online", () => {
        if (activeTab === "pending") loadAndRenderPendingEntries();
        else loadAndRenderHistoryEntries();
    });

    window.addEventListener("offline", () => {
        if (activeTab === "pending") loadAndRenderPendingEntries();
        else loadAndRenderHistoryEntries();
    });

    window.addEventListener("obsidianscout:offline-entries-synced", () => {
        updatePendingStats();
        updateHistoryStats();
        if (activeTab === "pending") loadAndRenderPendingEntries();
        else loadAndRenderHistoryEntries();
    });

    window.addEventListener("obsidianscout:device-history-changed", () => {
        updateHistoryStats();
        if (activeTab === "history") loadAndRenderHistoryEntries();
    });

    window.addEventListener("obsidianscout:connection-changed", () => {
        if (activeTab === "pending") loadAndRenderPendingEntries();
        else loadAndRenderHistoryEntries();
    });

    // Initial load
    updatePendingStats();
    updateHistoryStats();
    loadAndRenderHistoryEntries();
});
