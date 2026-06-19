
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

    // Query UI elements
    const emptyNotice = document.getElementById("cache-empty-notice");
    const tableContainer = document.getElementById("cache-table-container");
    const entriesBody = document.getElementById("cache-entries-body");

    // Filter elements
    const filterType = document.getElementById("filter-type");
    const filterTeam = document.getElementById("filter-team");
    const filterEvent = document.getElementById("filter-event");

    // Modal elements
    const modalBackdrop = document.getElementById("payload-modal-backdrop");
    const modalTitle = document.getElementById("payload-modal-title");
    const modalContent = document.getElementById("payload-modal-body-content");
    const modalCloseBtn = document.getElementById("payload-modal-close-btn");
    const modalCloseFooterBtn = document.getElementById("payload-modal-close-footer-btn");

    // Action buttons
    const btnSyncAll = document.getElementById("btn-sync-all");
    const btnExportCache = document.getElementById("btn-export-cache");
    const btnImportCache = document.getElementById("btn-import-cache");
    const btnClearCache = document.getElementById("btn-clear-cache");
    const fileImportInput = document.getElementById("cache-import-file");

    // Cache config mapping
    const cacheConfigs = Obsidianscout.CACHE_CONFIGS;

    // Load and render cache entries
    function loadAndRenderEntries() {
        updateStats();

        let allEntries = [];

        // Read all 6 caches
        for (const type in cacheConfigs) {
            const config = cacheConfigs[type];
            const pending = JSON.parse(Obsidianscout.safeGetItem(config.key) || "[]");
            pending.forEach((item, index) => {
                allEntries.push({
                    type: type,
                    index: index,
                    config: config,
                    item: item,
                    // Parse values
                    eventKey: item.data?.eventKey || "",
                    teamNumber: item.data?.targetTeamNumber || "",
                    matchKey: item.data?.matchKey || item.data?.matchNumber || "",
                    createdAt: item.createdAt || ""
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

        // Sort by timestamp (newest first, falling back to original order)
        filtered.sort((a, b) => {
            if (a.createdAt && b.createdAt) {
                return new Date(b.createdAt) - new Date(a.createdAt);
            }
            if (a.createdAt) return -1;
            if (b.createdAt) return 1;
            return 0;
        });

        // Render to table
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

            // View button
            const btnView = document.createElement("button");
            btnView.className = "btn-mini view";
            btnView.textContent = "View";
            btnView.addEventListener("click", () => showPayloadModal(entry));
            tdActions.appendChild(btnView);

            // Sync button
            const btnSync = document.createElement("button");
            btnSync.className = "btn-mini sync";
            btnSync.textContent = "Upload";
            btnSync.disabled = !navigator.onLine;
            btnSync.addEventListener("click", () => syncEntry(entry));
            tdActions.appendChild(btnSync);

            // Delete button
            const btnDelete = document.createElement("button");
            btnDelete.className = "btn-mini delete";
            btnDelete.textContent = "Delete";
            btnDelete.addEventListener("click", () => deleteEntry(entry));
            tdActions.appendChild(btnDelete);

            tr.appendChild(tdActions);
            entriesBody.appendChild(tr);
        });
    }

    function getBadgeClass(type) {
        switch (type) {
            case "match-scouting": return "match";
            case "pit-scouting": return "pit";
            case "qual-scouting": return "qual";
            case "prescout-scouting": return "prescout-match";
            case "prescout-pit-scouting": return "prescout-pit";
            case "prescout-qual-scouting": return "prescout-qual";
            default: return "match";
        }
    }

    function updateStats() {
        const stats = {
            "match": "pending_scouting_entries",
            "pit": "pending_pit_scouting_entries",
            "qual": "pending_qualitative_entries",
            "prescout-match": "pending_prescout_scouting_entries",
            "prescout-pit": "pending_prescout_pit_scouting_entries",
            "prescout-qual": "pending_prescout_qualitative_entries"
        };

        for (const statId in stats) {
            const key = stats[statId];
            const pending = JSON.parse(Obsidianscout.safeGetItem(key) || "[]");
            const el = document.getElementById(`count-${statId}`);
            if (el) {
                el.textContent = pending.length;
            }
        }
    }

    // Individual action handlers
    function showPayloadModal(entry) {
        modalTitle.textContent = `${entry.config.label} - Team ${entry.teamNumber}`;
        modalContent.textContent = JSON.stringify(entry.item, null, 2);
        modalBackdrop.classList.add("show");
    }

    function closePayloadModal() {
        modalBackdrop.classList.remove("show");
    }

    modalCloseBtn.addEventListener("click", closePayloadModal);
    modalCloseFooterBtn.addEventListener("click", closePayloadModal);

    async function syncEntry(entry) {
        if (!navigator.onLine) {
            Obsidianscout.showToast("You are offline", "error");
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

            Obsidianscout.showToast("Entry uploaded successfully!", "success");
            Obsidianscout.updateConnectionStatus();
            loadAndRenderEntries();
        } catch (error) {
            console.error("Failed to sync entry:", error);
            Obsidianscout.showToast(error.message || "Failed to upload entry", "error");
        }
    }

    function deleteEntry(entry) {
        if (confirm(`Are you sure you want to delete this cached ${entry.config.label} entry for Team ${entry.teamNumber}?`)) {
            const pending = JSON.parse(Obsidianscout.safeGetItem(entry.config.key) || "[]");
            pending.splice(entry.index, 1);
            Obsidianscout.safeSetItem(entry.config.key, JSON.stringify(pending));

            Obsidianscout.showToast("Entry deleted from cache", "success");
            Obsidianscout.updateConnectionStatus();
            loadAndRenderEntries();
        }
    }

    // Global action handlers
    btnSyncAll.addEventListener("click", async () => {
        if (!navigator.onLine) {
            Obsidianscout.showToast("You are offline", "error");
            return;
        }

        btnSyncAll.disabled = true;
        btnSyncAll.textContent = "Uploading...";

        try {
            await Obsidianscout.syncOfflineEntries();
            loadAndRenderEntries();
        } catch (error) {
            console.error(error);
        } finally {
            btnSyncAll.disabled = false;
            btnSyncAll.textContent = "Upload All Caches";
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
                    // Check if it's one of our cache keys
                    const matchedConfig = Object.values(cacheConfigs).find(c => c.key === key);
                    if (matchedConfig && Array.isArray(data[key])) {
                        const existing = JSON.parse(Obsidianscout.safeGetItem(key) || "[]");
                        
                        // Merge and skip duplicate checks (can identify duplicates by matchKey & teamNumber or push all)
                        data[key].forEach(newItem => {
                            // Simple duplication check
                            const isDuplicate = existing.some(oldItem => 
                                oldItem.data?.eventKey === newItem.data?.eventKey &&
                                oldItem.data?.targetTeamNumber === newItem.data?.targetTeamNumber &&
                                oldItem.data?.matchKey === newItem.data?.matchKey
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
                loadAndRenderEntries();
            } catch (err) {
                console.error("Failed to parse import file:", err);
                Obsidianscout.showToast("Invalid cache JSON backup file", "error");
            }
            // Reset input
            fileImportInput.value = "";
        };
        reader.readAsText(file);
    });

    btnClearCache.addEventListener("click", () => {
        if (confirm("WARNING: Are you sure you want to delete ALL offline cached entries? This action cannot be undone and these entries will NOT be uploaded to the server.")) {
            for (const type in cacheConfigs) {
                const config = cacheConfigs[type];
                Obsidianscout.safeRemoveItem(config.key);
            }
            Obsidianscout.showToast("All cached entries cleared", "success");
            Obsidianscout.updateConnectionStatus();
            loadAndRenderEntries();
        }
    });

    // Wire up filter event listeners
    filterType.addEventListener("change", loadAndRenderEntries);
    filterTeam.addEventListener("input", loadAndRenderEntries);
    filterEvent.addEventListener("input", loadAndRenderEntries);

    // Sync online updates
    window.addEventListener("online", () => {
        loadAndRenderEntries();
    });

    window.addEventListener("offline", () => {
        loadAndRenderEntries();
    });

    // Custom event sync notifier
    window.addEventListener("obsidianscout:offline-entries-synced", () => {
        loadAndRenderEntries();
    });

    // Initial load
    loadAndRenderEntries();
});
