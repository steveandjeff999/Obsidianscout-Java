(function () {
    let allTeams = [];
    let currentEventKey = "";
    let selectedMetric = "weighted";
    let searchQuery = "";

    // Server Polling and Sync State
    let lastSyncedTime = 0;
    let pollIntervalId = null;

    // Selector Modal Context
    let targetAllianceNum = null;
    let targetSlotName = null;

    // Pick lists (Want / Avoid / Do Not Pick). Private to this team and stored apart from
    // the board, which can be shared with scouting-alliance partners.
    const PICK_LISTS = [
        { key: "want", label: "Want", chip: "W" },
        { key: "avoid", label: "Avoid", chip: "A" },
        { key: "dnp", label: "Do Not Pick", chip: "DNP" }
    ];
    let pickLists = emptyPickLists();
    let pickListsSyncedAt = 0;
    let pickListSaveChain = Promise.resolve();
    let activePickTab = "want";

    // Recommendation view: data source (selectedMetric), how to sort, which teams to show, and
    // the points each pick list adds to a team's data score. Remembered per browser.
    const VIEW_PREFS_KEY = "obsidian-alliance-rec-view";
    const DEFAULT_LIST_ADJUSTMENTS = { want: 10, avoid: -10, dnp: -50 };
    let sortMode = "combined"; // "combined" (score + list adjustment) | "score" | "list"
    let showFilter = "all";    // "all" | "no-dnp" | "want" | "avoid" | "dnp" | "unlisted"
    let listAdjustments = { ...DEFAULT_LIST_ADJUSTMENTS };
    let breakdownTeamNumber = null;
    let dragTeamNumber = null;

    // Rich data caches
    let state = {
        configs: { match: null, pit: null, qualitative: null },
        matches: [],
        matchEntries: [],
        pitEntries: [],
        qualEntries: []
    };

    // Alliance selection board state
    let boardState = {
        alliance1: { captain: null, firstPick: null, secondPick: null, backup: null },
        alliance2: { captain: null, firstPick: null, secondPick: null, backup: null },
        alliance3: { captain: null, firstPick: null, secondPick: null, backup: null },
        alliance4: { captain: null, firstPick: null, secondPick: null, backup: null },
        alliance5: { captain: null, firstPick: null, secondPick: null, backup: null },
        alliance6: { captain: null, firstPick: null, secondPick: null, backup: null },
        alliance7: { captain: null, firstPick: null, secondPick: null, backup: null },
        alliance8: { captain: null, firstPick: null, secondPick: null, backup: null }
    };

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

        await initAllianceSelectionPage();
    });

    window.addEventListener("beforeunload", () => {
        if (pollIntervalId) {
            clearInterval(pollIntervalId);
        }
    });

    async function initAllianceSelectionPage() {
        const grid = document.getElementById("alliances-grid-container");
        Obsidianscout.showLoadingSpinner(grid, "Loading event teams...");

        try {
            const settingsResponse = await Obsidianscout.request("/api/settings");
            const settings = settingsResponse.settings;
            state.settings = settings;
            currentEventKey = Obsidianscout.resolveEventKey(settings);

            const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function') 
                ? Obsidianscout.getProgram() === "FTC" 
                : (settings && settings.program === "FTC");
            const effectiveUseEpa = !isFtc && settings.useStatboticsEpa;
            const effectiveUseExp = !isFtc && settings.useMatch13Exp;
            const effectiveUseOpr = settings.useTbaOpr;

            loadViewPrefs();

            // Filter metric selector options
            const metricSelect = document.getElementById("metric-select");
            if (metricSelect) metricSelect.value = selectedMetric;
            if (metricSelect) {
                if (!effectiveUseEpa) {
                    const optEpa = metricSelect.querySelector('option[value="epa"]');
                    if (optEpa) optEpa.remove();
                }
                if (!effectiveUseExp) {
                    const optExp = metricSelect.querySelector('option[value="exp"]');
                    if (optExp) optExp.remove();
                }
                if (!effectiveUseOpr) {
                    const optOpr = metricSelect.querySelector('option[value="opr"]');
                    if (optOpr) optOpr.remove();
                } else if (isFtc) {
                    const optOpr = metricSelect.querySelector('option[value="opr"]');
                    if (optOpr) optOpr.textContent = "FTC Scout OPR";
                }
                if ((selectedMetric === "epa" && !effectiveUseEpa) ||
                    (selectedMetric === "exp" && !effectiveUseExp) ||
                    (selectedMetric === "opr" && !effectiveUseOpr)) {
                    selectedMetric = "weighted";
                    metricSelect.value = "weighted";
                }
            }

            // Populate event filter select dropdown
            const eventFilter = document.getElementById("event-filter");
            const events = await Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`);
            eventFilter.innerHTML = "";

            // Ensure the configured event key is in the dropdown and selected
            let currentEventInList = false;
            events.forEach(e => {
                const opt = document.createElement("option");
                opt.value = e.eventKey;
                opt.textContent = `${e.name} (${e.eventKey})`;
                if (e.eventKey === currentEventKey) {
                    opt.selected = true;
                    currentEventInList = true;
                }
                eventFilter.appendChild(opt);
            });

            if (!currentEventInList && currentEventKey) {
                const opt = document.createElement("option");
                opt.value = currentEventKey;
                opt.textContent = `Configured Event (${currentEventKey.toUpperCase()})`;
                opt.selected = true;
                eventFilter.prepend(opt);
            }

            eventFilter.addEventListener("change", async () => {
                currentEventKey = eventFilter.value;
                await loadEventTeams(currentEventKey);
            });

            // Initialize Controls
            document.getElementById("reset-board-btn").addEventListener("click", () => {
                if (confirm("Are you sure you want to clear the entire alliance board?")) {
                    resetBoard();
                }
            });

            document.getElementById("team-search").addEventListener("input", (e) => {
                searchQuery = e.target.value.toLowerCase().trim();
                updateRecommendations();
            });

            wireViewControls();

            const exportCsvBtn = document.getElementById("export-csv-btn");
            if (exportCsvBtn) {
                exportCsvBtn.addEventListener("click", () => {
                    if (!currentEventKey) {
                        if (window.Obsidianscout && typeof Obsidianscout.showToast === 'function') {
                            Obsidianscout.showToast("No event selected", "warning");
                        }
                        return;
                    }
                    window.location.href = `/api/alliance-selection/export/csv?eventKey=${encodeURIComponent(currentEventKey)}`;
                });
            }

            const printBtn = document.getElementById("print-board-btn");
            if (printBtn) {
                printBtn.addEventListener("click", () => {
                    window.print();
                });
            }

            // Setup Selector Modal Events
            document.getElementById("selector-modal-close").addEventListener("click", closeSelectorModal);
            document.getElementById("selector-modal-cancel").addEventListener("click", closeSelectorModal);
            document.getElementById("selector-search").addEventListener("input", (e) => {
                renderSelectorList(e.target.value.toLowerCase().trim());
            });

            // Setup Breakdown Modal Events
            document.getElementById("breakdown-modal-close").addEventListener("click", closeBreakdownModal);
            document.getElementById("breakdown-modal-close-footer").addEventListener("click", closeBreakdownModal);
            setupModalTabs();

            await loadEventTeams(currentEventKey);

        } catch (error) {
            console.error("Failed to initialize Alliance Selection page:", error);
            Obsidianscout.showRetryButton(grid, "Failed to load page: " + error.message, initAllianceSelectionPage);
        }
    }

    async function loadEventTeams(eventKey) {
        if (pollIntervalId) {
            clearInterval(pollIntervalId);
            pollIntervalId = null;
        }

        if (!eventKey) {
            const grid = document.getElementById("alliances-grid-container");
            grid.innerHTML = '<div class="empty-indicator">Please select an event key in settings.</div>';
            const pickContainer = document.getElementById("pick-list-container");
            if (pickContainer) pickContainer.innerHTML = '<div class="empty-indicator pick-empty">Select an event to use pick lists.</div>';
            return;
        }

        try {
            // Load all teams, configs, matches, and scouting entries in parallel
            const [
                teamsList,
                matchConfig,
                pitConfig,
                qualConfig,
                allMatches,
                matchEntries,
                pitEntries,
                qualEntries
            ] = await Promise.all([
                Obsidianscout.request(`/api/teams?eventKey=${eventKey}`),
                Obsidianscout.request("/api/config"),
                Obsidianscout.request("/api/pit-config"),
                Obsidianscout.request("/api/qual-config"),
                Obsidianscout.request(`/api/matches?eventKey=${eventKey}`),
                Obsidianscout.request(`/api/scouting?includePrescout=true`),
                Obsidianscout.request(`/api/pit-scouting?includePrescout=true`),
                Obsidianscout.request(`/api/qual-scouting?includePrescout=true`)
            ]);

            allTeams = teamsList;
            state.configs.match = matchConfig;
            state.configs.pit = pitConfig;
            state.configs.qualitative = qualConfig;
            state.matches = allMatches;
            state.matchEntries = matchEntries || [];
            state.pitEntries = pitEntries || [];
            state.qualEntries = qualEntries || [];

            // Fetch current selection board from the server
            try {
                const res = await Obsidianscout.request(`/api/alliance-selection?eventKey=${eventKey}`);
                if (res.updatedAt > 0) {
                    boardState = JSON.parse(res.selectionJson);
                    lastSyncedTime = res.updatedAt;
                } else {
                    const savedState = localStorage.getItem(`obsidian-alliance-selection-${eventKey}`);
                    if (savedState) {
                        boardState = JSON.parse(savedState);
                        lastSyncedTime = 0;
                        await pushBoardStateToServer();
                    } else {
                        resetBoardStateOnly();
                    }
                }
            } catch (e) {
                console.warn("Failed to load selection from server, falling back to local storage", e);
                const savedState = localStorage.getItem(`obsidian-alliance-selection-${eventKey}`);
                if (savedState) {
                    boardState = JSON.parse(savedState);
                } else {
                    resetBoardStateOnly();
                }
            }

            await loadPickLists(eventKey);

            renderAlliances();
            updateRecommendations();

            pollIntervalId = setInterval(pollServer, 3000);

        } catch (error) {
            console.error("Failed to load event teams:", error);
            const grid = document.getElementById("alliances-grid-container");
            Obsidianscout.showRetryButton(grid, "Failed to load event teams: " + error.message, () => loadEventTeams(eventKey));
        }
    }

    async function pollServer() {
        if (!currentEventKey) return;
        try {
            const res = await Obsidianscout.request(`/api/alliance-selection?eventKey=${currentEventKey}`);
            if (res.updatedAt > lastSyncedTime) {
                boardState = JSON.parse(res.selectionJson);
                lastSyncedTime = res.updatedAt;
                saveState();
                renderAlliances();
                updateRecommendations();
            }
        } catch (err) {
            console.warn("Polling sync failed:", err);
        }

        try {
            const res = await Obsidianscout.request(`/api/alliance-selection/pick-lists?eventKey=${encodeURIComponent(currentEventKey)}`);
            if (res.updatedAt > pickListsSyncedAt) {
                pickLists = normalizePickLists(res.pickLists);
                pickListsSyncedAt = res.updatedAt;
                cachePickListsLocally();
                refreshPickListViews();
            }
        } catch (err) {
            console.warn("Pick list sync failed:", err);
        }
    }

    // ── Pick Lists ──────────────────────────────────────────
    function emptyPickLists() {
        return { want: [], avoid: [], dnp: [], notes: {} };
    }

    function normalizePickLists(raw) {
        const out = emptyPickLists();
        if (!raw || typeof raw !== "object") return out;
        const seen = new Set();
        PICK_LISTS.forEach(({ key }) => {
            (Array.isArray(raw[key]) ? raw[key] : []).forEach(value => {
                const num = parseInt(value, 10);
                if (num > 0 && !seen.has(num)) {
                    seen.add(num);
                    out[key].push(num);
                }
            });
        });
        if (raw.notes && typeof raw.notes === "object") {
            Object.entries(raw.notes).forEach(([team, note]) => {
                if (seen.has(parseInt(team, 10)) && typeof note === "string" && note.trim()) {
                    out.notes[team] = note;
                }
            });
        }
        return out;
    }

    function getTeamPickList(teamNumber) {
        const found = PICK_LISTS.find(({ key }) => pickLists[key].includes(teamNumber));
        return found ? found.key : null;
    }

    function pickListLabel(listKey) {
        const found = PICK_LISTS.find(({ key }) => key === listKey);
        return found ? found.label : listKey;
    }

    function pickListStorageKey(eventKey) {
        return `obsidian-pick-lists-${eventKey}`;
    }

    function cachePickListsLocally() {
        if (!currentEventKey) return;
        try {
            localStorage.setItem(pickListStorageKey(currentEventKey), JSON.stringify(pickLists));
        } catch (_) { }
    }

    async function loadPickLists(eventKey) {
        pickLists = emptyPickLists();
        pickListsSyncedAt = 0;
        try {
            const res = await Obsidianscout.request(`/api/alliance-selection/pick-lists?eventKey=${encodeURIComponent(eventKey)}`);
            pickLists = normalizePickLists(res.pickLists);
            pickListsSyncedAt = res.updatedAt || 0;
            cachePickListsLocally();
        } catch (err) {
            console.warn("Failed to load pick lists from server, using local copy", err);
            try {
                const saved = localStorage.getItem(pickListStorageKey(eventKey));
                if (saved) pickLists = normalizePickLists(JSON.parse(saved));
            } catch (_) { }
        }
    }

    function savePickLists() {
        if (!currentEventKey) return pickListSaveChain;
        cachePickListsLocally();
        const payload = { eventKey: currentEventKey, pickLists: JSON.parse(JSON.stringify(pickLists)) };
        // Chain saves so rapid edits reach the server in the order they were made.
        pickListSaveChain = pickListSaveChain.then(async () => {
            try {
                const res = await Obsidianscout.request("/api/alliance-selection/pick-lists", {
                    method: "POST",
                    json: payload
                });
                pickListsSyncedAt = Math.max(pickListsSyncedAt, res.updatedAt || 0);
            } catch (err) {
                console.error("Failed to save pick lists:", err);
                Obsidianscout.showToast("Failed to save pick lists: " + err.message, "error");
            }
        });
        return pickListSaveChain;
    }

    function refreshPickListViews() {
        renderPickLists();
        updateRecommendations();
        updateBreakdownPickButtons();
    }

    /** Puts a team in a list, moving it out of any other. Choosing its current list removes it. */
    function setTeamPickList(teamNumber, listKey) {
        const current = getTeamPickList(teamNumber);
        PICK_LISTS.forEach(({ key }) => {
            pickLists[key] = pickLists[key].filter(n => n !== teamNumber);
        });
        if (listKey && listKey !== current) {
            pickLists[listKey].push(teamNumber);
            Obsidianscout.showToast(`Added ${teamNumber} to ${pickListLabel(listKey)}`, "success");
        } else {
            delete pickLists.notes[String(teamNumber)];
            Obsidianscout.showToast(`Removed ${teamNumber} from ${pickListLabel(current)}`, "success");
        }
        refreshPickListViews();
        savePickLists();
    }

    function moveInPickList(listKey, teamNumber, targetIndex) {
        const list = pickLists[listKey];
        const from = list.indexOf(teamNumber);
        if (from < 0) return;
        const to = Math.max(0, Math.min(list.length - 1, targetIndex));
        if (from === to) return;
        list.splice(from, 1);
        list.splice(to, 0, teamNumber);
        refreshPickListViews();
        savePickLists();
    }

    function setPickNote(teamNumber, text) {
        const key = String(teamNumber);
        const note = text.trim().slice(0, 200);
        if ((pickLists.notes[key] || "") === note) return;
        if (note) {
            pickLists.notes[key] = note;
        } else {
            delete pickLists.notes[key];
        }
        updateRecommendations();
        savePickLists();
    }

    /** Maps each team already on the board to a short label like "A3 First Pick". */
    function getPickedTeamLabels() {
        const labels = new Map();
        Object.entries(boardState).forEach(([allianceKey, alliance]) => {
            if (!alliance || typeof alliance !== "object") return;
            const num = allianceKey.replace("alliance", "");
            ["captain", "firstPick", "secondPick", "backup"].forEach(slot => {
                if (alliance[slot]) labels.set(alliance[slot], `A${num} ${formatSlotLabel(slot)}`);
            });
        });
        return labels;
    }

    function renderPickChipsHtml(teamNumber) {
        const current = getTeamPickList(teamNumber);
        const chips = PICK_LISTS.map(({ key, label, chip }) => {
            const active = current === key;
            const title = active ? `Remove from ${label}` : `Add to ${label}`;
            return `<button type="button" class="pick-chip pick-chip-${key}${active ? " active" : ""}" data-pick-list="${key}" title="${title}" aria-pressed="${active}">${chip}</button>`;
        }).join("");
        return `<span class="pick-chips">${chips}</span>`;
    }

    function wirePickChips(root, teamNumber) {
        root.querySelectorAll(".pick-chip").forEach(btn => {
            btn.addEventListener("click", (e) => {
                e.stopPropagation();
                setTeamPickList(teamNumber, btn.dataset.pickList);
            });
        });
    }

    function renderPickLists() {
        const tabs = document.getElementById("pick-list-tabs");
        const container = document.getElementById("pick-list-container");
        if (!tabs || !container) return;

        // Don't wipe a note someone is typing when a sync lands; the blur handler re-renders.
        const active = document.activeElement;
        if (active && active.classList && active.classList.contains("pick-note") && container.contains(active)) {
            return;
        }

        tabs.innerHTML = "";
        PICK_LISTS.forEach(({ key, label }) => {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = `pick-tab pick-tab-${key}${key === activePickTab ? " active" : ""}`;
            btn.textContent = `${label} (${pickLists[key].length})`;
            btn.addEventListener("click", () => {
                activePickTab = key;
                renderPickLists();
            });
            tabs.appendChild(btn);
        });

        container.innerHTML = "";
        const list = pickLists[activePickTab];
        if (list.length === 0) {
            const empty = document.createElement("div");
            empty.className = "empty-indicator pick-empty";
            empty.textContent = `No teams on your ${pickListLabel(activePickTab)} list. Use the W / A / DNP buttons on a team to add it.`;
            container.appendChild(empty);
            return;
        }

        const esc = Obsidianscout.escapeHtml;
        const picked = getPickedTeamLabels();
        const nextWant = activePickTab === "want" ? list.find(n => !picked.has(n)) : null;

        list.forEach((teamNumber, idx) => {
            const team = allTeams.find(t => t.teamNumber === teamNumber);
            const name = team ? (team.nickname || team.name || "") : "Not at this event";
            const pickedLabel = picked.get(teamNumber);
            const base = team ? getMetricValue(team) : null;
            const scoreBadge = `<span class="rec-score-badge pick-score" title="${esc(getMetricLabel())}">${base === null ? "–" : base.toFixed(1)}</span>`;

            const row = document.createElement("div");
            row.className = `pick-row pick-row-${activePickTab}` +
                (pickedLabel ? " is-picked" : "") +
                (teamNumber === nextWant ? " is-next" : "");
            row.draggable = true;

            row.innerHTML = `
                <div class="pick-row-main">
                    <span class="pick-handle" title="Drag to reorder" aria-hidden="true">&#8942;&#8942;</span>
                    <span class="rec-rank">${idx + 1}</span>
                    <span class="pick-team-number">${teamNumber}</span>
                    <span class="pick-team-name" title="${esc(name)}">${esc(name)}</span>
                    ${pickedLabel ? `<span class="pick-status picked" title="Already on the board">${esc(pickedLabel)}</span>` : ""}
                    ${teamNumber === nextWant ? `<span class="pick-status next" title="Highest team on this list that is still available">Next</span>` : ""}
                    <span class="pick-row-actions">
                        <button type="button" class="pick-icon-btn" data-act="up" title="Move up" ${idx === 0 ? "disabled" : ""}>&#9650;</button>
                        <button type="button" class="pick-icon-btn" data-act="down" title="Move down" ${idx === list.length - 1 ? "disabled" : ""}>&#9660;</button>
                    </span>
                </div>
                <div class="pick-row-sub">
                    <input type="text" class="pick-note" maxlength="200" placeholder="Add a note..." aria-label="Note for team ${teamNumber}" />
                    ${scoreBadge}
                    ${renderPickChipsHtml(teamNumber)}
                </div>
            `;

            const noteInput = row.querySelector(".pick-note");
            noteInput.value = pickLists.notes[String(teamNumber)] || "";
            noteInput.addEventListener("keydown", (e) => {
                if (e.key === "Enter") noteInput.blur();
            });
            noteInput.addEventListener("change", () => setPickNote(teamNumber, noteInput.value));
            noteInput.addEventListener("blur", () => {
                setPickNote(teamNumber, noteInput.value);
                // Apply any sync that arrived while typing.
                setTimeout(renderPickLists, 0);
            });

            row.querySelector('[data-act="up"]').addEventListener("click", () => moveInPickList(activePickTab, teamNumber, idx - 1));
            row.querySelector('[data-act="down"]').addEventListener("click", () => moveInPickList(activePickTab, teamNumber, idx + 1));
            row.querySelector(".pick-team-name").addEventListener("click", () => openTeamBreakdown(teamNumber));
            row.querySelector(".pick-team-number").addEventListener("click", () => openTeamBreakdown(teamNumber));
            wirePickChips(row, teamNumber);

            // Drag to reorder within the active list
            row.addEventListener("dragstart", (e) => {
                if (e.target.closest && e.target.closest("input")) {
                    e.preventDefault();
                    return;
                }
                dragTeamNumber = teamNumber;
                row.classList.add("dragging");
                if (e.dataTransfer) {
                    e.dataTransfer.effectAllowed = "move";
                    e.dataTransfer.setData("text/plain", String(teamNumber));
                }
            });
            row.addEventListener("dragend", () => {
                dragTeamNumber = null;
                row.classList.remove("dragging");
                container.querySelectorAll(".drag-over").forEach(el => el.classList.remove("drag-over"));
            });
            row.addEventListener("dragover", (e) => {
                if (dragTeamNumber === null || dragTeamNumber === teamNumber) return;
                e.preventDefault();
                row.classList.add("drag-over");
            });
            row.addEventListener("dragleave", () => row.classList.remove("drag-over"));
            row.addEventListener("drop", (e) => {
                e.preventDefault();
                row.classList.remove("drag-over");
                if (dragTeamNumber !== null && dragTeamNumber !== teamNumber) {
                    moveInPickList(activePickTab, dragTeamNumber, idx);
                }
            });

            container.appendChild(row);
        });
    }

    function updateBreakdownPickButtons() {
        const container = document.getElementById("breakdown-pick-buttons");
        if (!container) return;
        if (breakdownTeamNumber === null) {
            container.innerHTML = "";
            return;
        }
        const current = getTeamPickList(breakdownTeamNumber);
        container.innerHTML = "";
        PICK_LISTS.forEach(({ key, label }) => {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = `btn ghost pick-footer-btn pick-footer-${key}${current === key ? " active" : ""}`;
            btn.textContent = current === key ? `✓ ${label}` : label;
            btn.title = current === key ? `Remove from ${label}` : `Add to ${label}`;
            btn.setAttribute("aria-pressed", String(current === key));
            btn.addEventListener("click", () => setTeamPickList(breakdownTeamNumber, key));
            container.appendChild(btn);
        });
    }

    async function pushBoardStateToServer() {
        if (!currentEventKey) return;
        try {
            const payload = {
                eventKey: currentEventKey,
                selectionJson: JSON.stringify(boardState)
            };
            const res = await Obsidianscout.request("/api/alliance-selection", {
                method: "POST",
                json: payload
            });
            lastSyncedTime = res.updatedAt;
            saveState();
        } catch (err) {
            console.error("Failed to sync state to server:", err);
            Obsidianscout.showToast("Failed to save draft to server: " + err.message, "error");
        }
    }

    function resetBoardStateOnly() {
        const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function') 
            ? Obsidianscout.getProgram() === "FTC" 
            : false;
        const totalAlliances = isFtc ? 4 : 8;
        boardState = {};
        for (let i = 1; i <= totalAlliances; i++) {
            boardState[`alliance${i}`] = isFtc 
                ? { captain: null, firstPick: null, backup: null }
                : { captain: null, firstPick: null, secondPick: null, backup: null };
        }
    }

    async function resetBoard() {
        resetBoardStateOnly();
        if (currentEventKey) {
            localStorage.removeItem(`obsidian-alliance-selection-${currentEventKey}`);
        }
        await pushBoardStateToServer();
        renderAlliances();
        updateRecommendations();
        Obsidianscout.showToast("Board cleared successfully", "success");
    }

    function saveState() {
        if (currentEventKey) {
            localStorage.setItem(`obsidian-alliance-selection-${currentEventKey}`, JSON.stringify(boardState));
        }
    }

    function getPickedTeamNumbers() {
        const picked = new Set();
        Object.values(boardState).forEach(alliance => {
            if (alliance.captain) picked.add(alliance.captain);
            if (alliance.firstPick) picked.add(alliance.firstPick);
            if (alliance.secondPick) picked.add(alliance.secondPick);
            if (alliance.backup) picked.add(alliance.backup);
        });
        return picked;
    }

    function getWeightedScore(team) {
        let num = 0;
        let den = 0;
        if (team.averagePoints !== null && team.averagePoints !== undefined) {
            num += team.averagePoints * 1.0;
            den += 1.0;
        }
        const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function') 
            ? Obsidianscout.getProgram() === "FTC" 
            : (state.settings?.program === "FTC");
        const effectiveUseEpa = !isFtc && state.settings?.useStatboticsEpa;
        const effectiveUseExp = !isFtc && state.settings?.useMatch13Exp;
        const effectiveUseOpr = state.settings?.useTbaOpr;

        if (effectiveUseEpa && team.epa !== null && team.epa !== undefined) {
            num += team.epa * 0.8;
            den += 0.8;
        }
        if (effectiveUseExp && team.exp !== null && team.exp !== undefined) {
            num += team.exp * 0.8;
            den += 0.8;
        }
        if (effectiveUseOpr && team.opr !== null && team.opr !== undefined) {
            num += team.opr * 0.6;
            den += 0.6;
        }
        return den > 0 ? num / den : 0;
    }

    function getAvailableTeams() {
        const picked = getPickedTeamNumbers();
        let available = allTeams.filter(team => !picked.has(team.teamNumber));

        // Search Filter
        if (searchQuery) {
            available = available.filter(t => {
                const numMatch = t.teamNumber.toString().includes(searchQuery);
                const nicknameMatch = (t.nickname || "").toLowerCase().includes(searchQuery);
                const nameMatch = (t.name || "").toLowerCase().includes(searchQuery);
                return numMatch || nicknameMatch || nameMatch;
            });
        }

        available = available.filter(t => matchesShowFilter(getTeamPickList(t.teamNumber)));

        return sortTeams(available);
    }

    // ── Recommendation view: data source + sort + filter ────
    function loadViewPrefs() {
        try {
            const saved = JSON.parse(localStorage.getItem(VIEW_PREFS_KEY) || "null");
            if (!saved || typeof saved !== "object") return;
            if (["weighted", "scouted", "epa", "exp", "opr"].includes(saved.metric)) selectedMetric = saved.metric;
            if (["combined", "score", "list"].includes(saved.sortMode)) sortMode = saved.sortMode;
            if (["all", "no-dnp", "want", "avoid", "dnp", "unlisted"].includes(saved.showFilter)) showFilter = saved.showFilter;
            if (saved.adjustments && typeof saved.adjustments === "object") {
                PICK_LISTS.forEach(({ key }) => {
                    const value = Number(saved.adjustments[key]);
                    if (Number.isFinite(value)) listAdjustments[key] = value;
                });
            }
        } catch (_) { }
    }

    function saveViewPrefs() {
        try {
            localStorage.setItem(VIEW_PREFS_KEY, JSON.stringify({
                metric: selectedMetric,
                sortMode,
                showFilter,
                adjustments: listAdjustments
            }));
        } catch (_) { }
    }

    function formatAdjustment(value) {
        return value > 0 ? `+${value}` : `${value}`;
    }

    function updateAdjustmentSummary() {
        const summary = document.getElementById("list-adjust-summary");
        if (summary) {
            summary.textContent = `W ${formatAdjustment(listAdjustments.want)} · A ${formatAdjustment(listAdjustments.avoid)} · DNP ${formatAdjustment(listAdjustments.dnp)}`;
        }
        const details = document.getElementById("list-adjust-details");
        if (details) details.classList.toggle("inactive", sortMode !== "combined");
    }

    function wireViewControls() {
        const metricSelect = document.getElementById("metric-select");
        const sortSelect = document.getElementById("sort-mode-select");
        const showSelect = document.getElementById("show-filter-select");

        if (sortSelect) sortSelect.value = sortMode;
        if (showSelect) showSelect.value = showFilter;

        const refresh = () => {
            saveViewPrefs();
            updateAdjustmentSummary();
            updateRecommendations();
            renderPickLists();
        };

        if (metricSelect) metricSelect.addEventListener("change", (e) => { selectedMetric = e.target.value; refresh(); });
        if (sortSelect) sortSelect.addEventListener("change", (e) => { sortMode = e.target.value; refresh(); });
        if (showSelect) showSelect.addEventListener("change", (e) => { showFilter = e.target.value; refresh(); });

        PICK_LISTS.forEach(({ key }) => {
            const input = document.getElementById(`adj-${key}`);
            if (!input) return;
            input.value = listAdjustments[key];
            input.addEventListener("input", () => {
                const value = Number(input.value);
                if (input.value.trim() === "" || !Number.isFinite(value)) return;
                listAdjustments[key] = value;
                refresh();
            });
        });

        const resetBtn = document.getElementById("adj-reset");
        if (resetBtn) {
            resetBtn.addEventListener("click", () => {
                listAdjustments = { ...DEFAULT_LIST_ADJUSTMENTS };
                PICK_LISTS.forEach(({ key }) => {
                    const input = document.getElementById(`adj-${key}`);
                    if (input) input.value = listAdjustments[key];
                });
                refresh();
            });
        }

        updateAdjustmentSummary();
    }

    function matchesShowFilter(listKey) {
        switch (showFilter) {
            case "no-dnp": return listKey !== "dnp";
            case "want": return listKey === "want";
            case "avoid": return listKey === "avoid";
            case "dnp": return listKey === "dnp";
            case "unlisted": return listKey === null;
            default: return true;
        }
    }

    function finiteOrNull(value) {
        return value === null || value === undefined || !Number.isFinite(Number(value)) ? null : Number(value);
    }

    /** The team's value for the selected data source, or null when that source has no data. */
    function getMetricValue(team) {
        switch (selectedMetric) {
            case "scouted": return finiteOrNull(team.averagePoints);
            case "epa": return finiteOrNull(team.epa);
            case "exp": return finiteOrNull(team.exp);
            case "opr": return finiteOrNull(team.opr);
            default: return getWeightedScore(team);
        }
    }

    function getMetricLabel() {
        const select = document.getElementById("metric-select");
        const option = select ? select.querySelector(`option[value="${selectedMetric}"]`) : null;
        return option ? option.textContent : selectedMetric;
    }

    /** Data score, list adjustment and their total for one team. */
    function scoreTeam(team) {
        const listKey = getTeamPickList(team.teamNumber);
        const base = getMetricValue(team);
        const adjustment = listKey ? (Number(listAdjustments[listKey]) || 0) : 0;
        return { listKey, base, adjustment, total: (base ?? 0) + adjustment };
    }

    function compareNullableDesc(a, b) {
        if (a === null && b === null) return 0;
        if (a === null) return 1;
        if (b === null) return -1;
        return b - a;
    }

    /** Sorts teams for the current sort mode and stores each team's score as `_score`. */
    function sortTeams(teams) {
        teams.forEach(t => {
            t.calculatedWeighted = getWeightedScore(t);
            t._score = scoreTeam(t);
        });

        const byBase = (a, b) => compareNullableDesc(a._score.base, b._score.base) || a.teamNumber - b.teamNumber;

        if (sortMode === "list") {
            // Want list in its order, then unlisted teams by data score, then Avoid, then Do Not Pick
            const groupRank = { want: 0, avoid: 2, dnp: 3 };
            return teams.sort((a, b) => {
                const ga = a._score.listKey ? groupRank[a._score.listKey] : 1;
                const gb = b._score.listKey ? groupRank[b._score.listKey] : 1;
                if (ga !== gb) return ga - gb;
                if (ga === 1) return byBase(a, b);
                const list = pickLists[a._score.listKey];
                return list.indexOf(a.teamNumber) - list.indexOf(b.teamNumber);
            });
        }
        if (sortMode === "score") {
            return teams.sort(byBase);
        }
        return teams.sort((a, b) => (b._score.total - a._score.total) || byBase(a, b));
    }

    async function assignTeam(allianceNum, slotName, teamNumber) {
        const team = allTeams.find(t => t.teamNumber === teamNumber);
        if (!team) return;

        const picked = getPickedTeamNumbers();
        if (picked.has(teamNumber)) {
            Obsidianscout.showToast(`Team ${teamNumber} is already picked`, "error");
            return;
        }

        boardState[`alliance${allianceNum}`][slotName] = teamNumber;
        saveState();
        await pushBoardStateToServer();

        renderAlliances();
        updateRecommendations();
        Obsidianscout.showToast(`Assigned ${teamNumber} to Alliance ${allianceNum} ${formatSlotLabel(slotName)}`, "success");
    }

    async function clearSlot(allianceNum, slotName) {
        boardState[`alliance${allianceNum}`][slotName] = null;
        saveState();
        await pushBoardStateToServer();

        renderAlliances();
        updateRecommendations();
        Obsidianscout.showToast("Slot cleared", "success");
    }

    function formatSlotLabel(slot) {
        if (slot === "captain") return "Captain";
        if (slot === "firstPick") return "First Pick";
        if (slot === "secondPick") return "Second Pick";
        if (slot === "backup") return "Backup";
        return slot;
    }

    function updateRecommendations() {
        const container = document.getElementById("recommendations-list-container");
        const available = getAvailableTeams();

        container.innerHTML = "";
        if (available.length === 0) {
            container.innerHTML = '<div class="empty-indicator">No available teams match your filter.</div>';
            return;
        }

        available.forEach((team, idx) => {
            const item = document.createElement("div");
            const listKey = getTeamPickList(team.teamNumber);
            item.className = "rec-item" + (listKey ? ` in-list-${listKey}` : "");
            item.addEventListener("click", () => {
                openTeamBreakdown(team.teamNumber);
            });

            const esc = Obsidianscout.escapeHtml;
            const { base, adjustment, total } = team._score;
            const baseText = base === null ? "–" : base.toFixed(1);
            // The badge always shows the points the team is expected to add (selected data source).
            // List points only move the team up or down the ranking.
            const combined = sortMode === "combined";
            const scoreVal = baseText;
            const scoreTitle = combined && adjustment
                ? `Expected points (${getMetricLabel()}): ${baseText}. Ranked as ${total.toFixed(1)} with ${formatAdjustment(adjustment)} for ${pickListLabel(listKey)}.`
                : `Expected points (${getMetricLabel()}): ${baseText}`;
            const breakdown = combined && adjustment
                ? `<span class="rec-breakdown" title="Ranking adjustment from your ${esc(pickListLabel(listKey))} list"><span class="rec-adj rec-adj-${listKey}">${formatAdjustment(adjustment)} ${esc(PICK_LISTS.find(l => l.key === listKey).chip)} rank</span></span>`
                : "";

            const displayName = team.nickname || team.name || `Team ${team.teamNumber}`;
            const note = pickLists.notes[String(team.teamNumber)];
            item.innerHTML = `
                <div class="rec-left">
                    <span class="rec-rank">#${idx + 1}</span>
                    <span class="rec-team-number">${team.teamNumber}</span>
                    <div class="rec-name-block">
                        <span class="rec-nickname" title="${esc(displayName)}">${esc(displayName)}</span>
                        ${note ? `<span class="rec-note" title="${esc(note)}">${esc(note)}</span>` : ""}
                    </div>
                </div>
                <div class="rec-right">
                    ${renderPickChipsHtml(team.teamNumber)}
                    <div class="rec-score-block">
                        <span class="rec-score-badge" title="${esc(scoreTitle)}">${scoreVal}</span>
                        ${breakdown}
                    </div>
                </div>
            `;
            wirePickChips(item, team.teamNumber);
            container.appendChild(item);
        });
    }

    function renderAlliances() {
        const container = document.getElementById("alliances-grid-container");
        container.innerHTML = "";

        const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function') 
            ? Obsidianscout.getProgram() === "FTC" 
            : false;
        const totalAlliances = isFtc ? 4 : 8;

        for (let i = 1; i <= totalAlliances; i++) {
            const allianceKey = `alliance${i}`;
            const alliance = boardState[allianceKey] || { captain: null, firstPick: null, secondPick: null, backup: null };
            const card = document.createElement("div");
            card.className = "alliance-card";

            card.innerHTML = `
                <div class="alliance-card-header">
                    <span>Alliance ${i}</span>
                </div>
                <div class="slot-container">
                    ${renderSlotHtml(i, "captain", alliance.captain)}
                    ${renderSlotHtml(i, "firstPick", alliance.firstPick)}
                    ${!isFtc ? renderSlotHtml(i, "secondPick", alliance.secondPick) : ""}
                    ${renderSlotHtml(i, "backup", alliance.backup)}
                </div>
            `;

            card.querySelectorAll(".slot-row").forEach(row => {
                const slotName = row.getAttribute("data-slot");
                const allianceNum = parseInt(row.getAttribute("data-alliance"));

                row.addEventListener("click", (e) => {
                    if (e.target.classList.contains("slot-clear")) return;
                    openSelectorModal(allianceNum, slotName);
                });

                const clearBtn = row.querySelector(".slot-clear");
                if (clearBtn) {
                    clearBtn.addEventListener("click", (e) => {
                        e.stopPropagation();
                        clearSlot(allianceNum, slotName);
                    });
                }
            });

            container.appendChild(card);
        }

        // Pick list rows show which teams are already on the board.
        renderPickLists();
    }

    function renderSlotHtml(allianceNum, slotName, teamNumber) {
        const label = formatSlotLabel(slotName);
        let hasTeam = false;
        let displayVal = "Click to select...";

        if (teamNumber) {
            hasTeam = true;
            const team = allTeams.find(t => t.teamNumber === teamNumber);
            displayVal = `${teamNumber} - ${team ? (team.nickname || team.name || "") : ""}`;
        }

        return `
            <div class="slot-row ${hasTeam ? "has-team" : ""}" data-alliance="${allianceNum}" data-slot="${slotName}">
                <div class="slot-label">${label}</div>
                <div class="slot-team-display ${hasTeam ? "" : "empty"}">${displayVal}</div>
                <button class="slot-clear" title="Clear slot">&times;</button>
            </div>
        `;
    }

    // Helper: scopes team entries based on current event count, falling back to prescouting if < 3
    function getScoutingEntriesForTeam(teamNumber) {
        // Match Scouting
        const teamMatch = state.matchEntries.filter(e => e.targetTeamNumber === teamNumber);
        const currentMatch = teamMatch.filter(e => e.eventKey === currentEventKey && !e.isPrescout);
        const prescoutMatch = teamMatch.filter(e => e.isPrescout);
        const finalMatch = (currentMatch.length < 3) ? currentMatch.concat(prescoutMatch) : currentMatch;

        // Qualitative Scouting
        const teamQual = state.qualEntries.filter(e => e.targetTeamNumber === teamNumber);
        const currentQual = teamQual.filter(e => e.eventKey === currentEventKey && !e.isPrescout);
        const prescoutQual = teamQual.filter(e => e.isPrescout);
        const finalQual = (currentQual.length < 3) ? currentQual.concat(prescoutQual) : currentQual;

        // Pit Scouting
        const teamPit = state.pitEntries.filter(e => e.targetTeamNumber === teamNumber);
        const currentPit = teamPit.filter(e => e.eventKey === currentEventKey && !e.isPrescout);
        const prescoutPit = teamPit.filter(e => e.isPrescout);
        const finalPit = (currentPit.length < 3) ? currentPit.concat(prescoutPit) : currentPit;

        return {
            match: finalMatch,
            qualitative: finalQual,
            pit: finalPit
        };
    }

    // ── Popup Modal 1: Team Selector ────────────────────────
    function openSelectorModal(allianceNum, slotName) {
        targetAllianceNum = allianceNum;
        targetSlotName = slotName;

        const modal = document.getElementById("selector-modal-backdrop");
        const title = document.getElementById("selector-modal-title");
        title.textContent = `Select Team for Alliance ${allianceNum} ${formatSlotLabel(slotName)}`;

        const search = document.getElementById("selector-search");
        search.value = "";

        renderSelectorList("");
        modal.classList.add("open");
        search.focus();
    }

    function closeSelectorModal() {
        const modal = document.getElementById("selector-modal-backdrop");
        modal.classList.remove("open");
        targetAllianceNum = null;
        targetSlotName = null;
    }

    function renderSelectorList(filterText) {
        const container = document.getElementById("selector-list-container");
        container.innerHTML = "";

        const picked = getPickedTeamNumbers();
        const currentSelected = boardState[`alliance${targetAllianceNum}`][targetSlotName];

        let list = allTeams.filter(t => !picked.has(t.teamNumber) || t.teamNumber === currentSelected);

        // Same data source and sort as the Recommendations panel
        sortTeams(list);

        // Establish ranks based on sorted position
        const teamRanks = {};
        list.forEach((team, idx) => {
            teamRanks[team.teamNumber] = idx + 1;
        });

        // Apply search filter
        if (filterText) {
            list = list.filter(t =>
                t.teamNumber.toString().includes(filterText) ||
                (t.nickname || "").toLowerCase().includes(filterText) ||
                (t.name || "").toLowerCase().includes(filterText)
            );
        }

        if (list.length === 0) {
            container.innerHTML = '<div class="empty-indicator">No available teams match your filter.</div>';
            return;
        }

        list.forEach(team => {
            const item = document.createElement("div");
            item.className = "selector-item";
            item.addEventListener("click", () => {
                assignTeam(targetAllianceNum, targetSlotName, team.teamNumber);
                closeSelectorModal();
            });

            const points = team.averagePoints !== null && team.averagePoints !== undefined ? team.averagePoints.toFixed(1) : "-";
            const epa = team.epa !== null && team.epa !== undefined ? team.epa.toFixed(1) : "-";
            const exp = team.exp !== null && team.exp !== undefined ? team.exp.toFixed(1) : "-";
            const opr = team.opr !== null && team.opr !== undefined ? team.opr.toFixed(1) : "-";
            const rank = teamRanks[team.teamNumber];

            const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function') 
                ? Obsidianscout.getProgram() === "FTC" 
                : (state.settings?.program === "FTC");
            const effectiveUseEpa = !isFtc && state.settings?.useStatboticsEpa;
            const effectiveUseExp = !isFtc && state.settings?.useMatch13Exp;
            const effectiveUseOpr = state.settings?.useTbaOpr;

            const epaSpan = effectiveUseEpa ? `<span>EPA: ${epa}</span>` : "";
            const expSpan = effectiveUseExp ? `<span>EXP: ${exp}</span>` : "";
            const oprSpan = effectiveUseOpr ? `<span>${isFtc ? 'FTC OPR' : 'OPR'}: ${opr}</span>` : "";

            const esc = Obsidianscout.escapeHtml;
            const listKey = getTeamPickList(team.teamNumber);
            const listNote = pickLists.notes[String(team.teamNumber)];
            const listBadge = listKey
                ? `<span class="pick-badge pick-badge-${listKey}" title="${esc(`On your ${pickListLabel(listKey)} list${listNote ? `: ${listNote}` : ""}`)}">${PICK_LISTS.find(l => l.key === listKey).chip}</span>`
                : "";
            const noteLine = listNote ? `<span class="rec-note selector-note">${esc(listNote)}</span>` : "";

            item.innerHTML = `
                <div class="selector-team">
                    <span class="rec-rank" style="min-width: 28px; text-align: left;">#${rank}</span>
                    <span>${team.teamNumber}</span>
                    <span class="selector-nickname" title="${esc(team.nickname || team.name || "")}">${esc(team.nickname || team.name || "")}</span>
                    ${listBadge}
                    ${noteLine}
                </div>
                <div class="selector-metrics">
                    <span>Scouted: ${points}</span>
                    ${epaSpan}
                    ${expSpan}
                    ${oprSpan}
                </div>
            `;
            container.appendChild(item);
        });
    }

    // ── Popup Modal 2: Team Breakdown ───────────────────────
    function openTeamBreakdown(teamNumber) {
        const team = allTeams.find(t => t.teamNumber === teamNumber);
        if (!team) return;

        const modal = document.getElementById("breakdown-modal-backdrop");
        document.getElementById("breakdown-modal-title").textContent = `Team ${teamNumber} - ${team.nickname || team.name || ""} Profile`;

        document.querySelectorAll(".modal-tab-btn").forEach(b => b.classList.remove("active"));
        document.querySelector('[data-tab="overview"]').classList.add("active");
        document.querySelectorAll(".modal-tab-content").forEach(c => c.classList.remove("active"));
        document.getElementById("tab-overview").classList.add("active");

        const scoped = getScoutingEntriesForTeam(teamNumber);

        // Calculate dynamic average points based on the scoped (current/prescout) entries
        let calculatedAvg = "-";
        if (scoped.match.length > 0) {
            const sum = scoped.match.reduce((acc, e) => acc + scoreEntry(state.configs.match, e.data), 0);
            calculatedAvg = (sum / scoped.match.length).toFixed(1);
        } else if (team.averagePoints !== null && team.averagePoints !== undefined) {
            calculatedAvg = team.averagePoints.toFixed(1);
        }

        const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function') 
            ? Obsidianscout.getProgram() === "FTC" 
            : (state.settings?.program === "FTC");
        const effectiveUseEpa = !isFtc && state.settings?.useStatboticsEpa;
        const effectiveUseExp = !isFtc && state.settings?.useMatch13Exp;
        const effectiveUseOpr = state.settings?.useTbaOpr;

        const epa = team.epa !== null && team.epa !== undefined ? team.epa.toFixed(1) : "-";
        const exp = team.exp !== null && team.exp !== undefined ? team.exp.toFixed(1) : "-";
        const opr = team.opr !== null && team.opr !== undefined ? team.opr.toFixed(1) : "-";
        const teamMatches = state.matches.filter(m => {
            const allTeamsInMatch = (m.redTeams || []).concat(m.blueTeams || []);
            return allTeamsInMatch.some(k => k.replace(/^(frc|ftc)/, "") === String(teamNumber));
        });

        const cardEpa = document.getElementById("breakdown-card-epa");
        if (cardEpa) cardEpa.style.display = effectiveUseEpa ? "" : "none";

        const cardExp = document.getElementById("breakdown-card-exp");
        if (cardExp) cardExp.style.display = effectiveUseExp ? "" : "none";

        const cardOpr = document.getElementById("breakdown-card-opr");
        if (cardOpr) {
            cardOpr.style.display = effectiveUseOpr ? "" : "none";
            const oprLabel = cardOpr.querySelector(".breakdown-stat-label");
            if (oprLabel) oprLabel.textContent = isFtc ? "FTC Scout OPR" : (typeof t === 'function' ? t('alliance-selection.tba_opr', "TBA OPR") : "TBA OPR");
        }

        document.getElementById("breakdown-stat-scouted").textContent = calculatedAvg;
        document.getElementById("breakdown-stat-epa").textContent = epa;
        const statExp = document.getElementById("breakdown-stat-exp");
        if (statExp) statExp.textContent = exp;
        document.getElementById("breakdown-stat-opr").textContent = opr;
        document.getElementById("breakdown-stat-matches").textContent = teamMatches.length;

        // 2. Render pit specs
        renderPitSpecs(teamNumber, scoped.pit);

        // 3. Render scouter notes
        renderScouterNotes(teamNumber, scoped);

        // 3b. Render AI qualitative notes summary if enabled
        const aiContainer = document.getElementById("breakdown-ai-summary-container");
        if (aiContainer) {
            aiContainer.innerHTML = "";
            if (window.ObsidianscoutAIFeatures && typeof window.ObsidianscoutAIFeatures.mountSummaryCard === "function") {
                window.ObsidianscoutAIFeatures.mountSummaryCard(aiContainer, {
                    teamNumber,
                    eventKey: currentEventKey
                });
            } else {
                window.addEventListener("obsidianscout:ai-features-ready", () => {
                    if (breakdownTeamNumber === teamNumber && window.ObsidianscoutAIFeatures && typeof window.ObsidianscoutAIFeatures.mountSummaryCard === "function") {
                        window.ObsidianscoutAIFeatures.mountSummaryCard(aiContainer, {
                            teamNumber,
                            eventKey: currentEventKey
                        });
                    }
                }, { once: true });
            }
        }

        // 4. Render match schedule
        renderMatchSchedule(teamNumber, teamMatches);

        // 5. Draw Plotly Graph
        renderPerformanceGraph(teamNumber, scoped.match);

        // 6. Pick list buttons
        breakdownTeamNumber = teamNumber;
        updateBreakdownPickButtons();

        modal.classList.add("open");
    }

    function closeBreakdownModal() {
        const modal = document.getElementById("breakdown-modal-backdrop");
        modal.classList.remove("open");
        breakdownTeamNumber = null;
    }

    function setupModalTabs() {
        const tabBtns = document.querySelectorAll(".modal-tab-btn");
        const contents = document.querySelectorAll(".modal-tab-content");
        tabBtns.forEach(btn => {
            btn.addEventListener("click", () => {
                const target = btn.getAttribute("data-tab");
                tabBtns.forEach(b => b.classList.remove("active"));
                btn.classList.add("active");
                contents.forEach(c => {
                    c.classList.remove("active");
                    if (c.id === `tab-${target}`) {
                        c.classList.add("active");
                    }
                });
                if (target === "graph") {
                    Plotly.Plots.resize('breakdown-graph-container');
                }
            });
        });
    }

    function renderPitSpecs(teamNumber, scopedPit) {
        const container = document.getElementById("pit-specs-container");
        container.innerHTML = "";

        const entry = scopedPit.find(e => e.targetTeamNumber === teamNumber);
        if (!entry || !entry.data || !state.configs.pit || !state.configs.pit.fields) {
            container.innerHTML = '<div style="grid-column: 1/-1; color: var(--muted); font-style: italic;">No pit scouting specifications synced for this team.</div>';
            return;
        }

        let renderedCount = 0;
        state.configs.pit.fields.forEach(f => {
            if (f.type !== "text" && f.type !== "textarea") {
                const val = entry.data[f.id];
                if (val !== undefined && val !== null && val !== "") {
                    let formattedVal = val;
                    if (f.type === "checkbox") {
                        formattedVal = val === true || val === "true" || val === 1 ? "Yes" : "No";
                    }
                    const item = document.createElement("div");
                    item.className = "pit-spec-item";
                    item.innerHTML = `
                        <span class="pit-spec-label">${Obsidianscout.localize(f.label) || f.label}</span>
                        <span class="pit-spec-value">${formattedVal}</span>
                    `;
                    container.appendChild(item);
                    renderedCount++;
                }
            }
        });

        if (renderedCount === 0) {
            container.innerHTML = '<div style="grid-column: 1/-1; color: var(--muted); font-style: italic;">No specification values found in the synced pit data.</div>';
        }
    }

    function renderScouterNotes(teamNumber, scoped) {
        const container = document.getElementById("notes-list-container");
        container.innerHTML = "";

        const notes = [];

        // Parse Text Comments
        const addComments = (entries, config, typeLabel) => {
            if (!config || !config.fields) return;
            entries.forEach(e => {
                if (e.targetTeamNumber === teamNumber && e.data) {
                    config.fields.forEach(f => {
                        if ((f.type === "text" || f.type === "textarea") && e.data[f.id]) {
                            notes.push({
                                type: typeLabel + (e.matchNumber ? ` Match ${e.matchNumber}` : ""),
                                scouter: e.ownerTeamNumber,
                                date: e.createdAt,
                                label: Obsidianscout.localize(f.label) || f.label,
                                text: e.data[f.id]
                            });
                        }
                    });
                }
            });
        };

        addComments(scoped.match, state.configs.match, "Match");
        addComments(scoped.qualitative, state.configs.qualitative, "Qualitative");
        addComments(scoped.pit, state.configs.pit, "Pit");

        if (notes.length === 0) {
            container.innerHTML = '<div style="color: var(--muted); font-style: italic;">No scouter comments or qualitative notes logged.</div>';
            return;
        }

        // Sort by date descending
        notes.sort((a, b) => new Date(b.date) - new Date(a.date));

        // Notes are free text written by scouts (possibly from alliance partner teams): escape them.
        const esc = (v) => String(v ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");

        notes.forEach(n => {
            const card = document.createElement("div");
            card.className = "note-card";
            card.innerHTML = `
                <div class="note-header">
                    <span>${esc(n.type)} | Scouter Team ${esc(n.scouter)}</span>
                    <span>${formatDateString(n.date)}</span>
                </div>
                <div class="note-body">
                    <strong>${esc(n.label)}:</strong> ${esc(n.text)}
                </div>
            `;
            container.appendChild(card);
        });
    }

    function renderMatchSchedule(teamNumber, teamMatches) {
        const table = document.getElementById("breakdown-matches-table");
        const body = table.querySelector("tbody");
        body.innerHTML = "";

        if (teamMatches.length === 0) {
            body.innerHTML = '<tr><td colspan="3" style="text-align: center; color: var(--muted); padding: 16px;">No scheduled matches found.</td></tr>';
            return;
        }

        // Chronological sort
        teamMatches.sort((a, b) => (a.matchNumber || 0) - (b.matchNumber || 0));

        teamMatches.forEach(m => {
            const tr = document.createElement("tr");

            const labelCell = document.createElement("td");
            labelCell.textContent = m.label || `QM ${m.matchNumber || ""}`;
            const videoLinks = Obsidianscout.createMatchVideoLinks(m.videos);
            if (videoLinks) labelCell.appendChild(videoLinks);
            tr.appendChild(labelCell);

            // Red alliance
            const redCell = document.createElement("td");
            (m.redTeams || []).forEach(key => {
                const num = key.replace(/^(frc|ftc)/, "");
                const isSelf = num === String(teamNumber);
                const badge = document.createElement("span");
                badge.className = `alliance-member-badge red-team ${isSelf ? 'highlight-self' : ''}`;
                badge.textContent = num;
                redCell.appendChild(badge);
            });
            tr.appendChild(redCell);

            // Blue alliance
            const blueCell = document.createElement("td");
            (m.blueTeams || []).forEach(key => {
                const num = key.replace(/^(frc|ftc)/, "");
                const isSelf = num === String(teamNumber);
                const badge = document.createElement("span");
                badge.className = `alliance-member-badge blue-team ${isSelf ? 'highlight-self' : ''}`;
                badge.textContent = num;
                blueCell.appendChild(badge);
            });
            tr.appendChild(blueCell);

            body.appendChild(tr);
        });
    }

    function scoreEntry(config, entryData) {
        if (!config || !config.fields || !entryData) return 0;
        let score = 0;
        config.fields.forEach(field => {
            const value = entryData[field.id];
            if (value === undefined || value === null) return;
            const type = (field.type || "").toLowerCase();
            if (type === "counter" || type === "number" || type === "rating") {
                const numVal = parseFloat(value);
                if (!isNaN(numVal)) {
                    score += (field.pointsPer || 0) * numVal;
                }
            } else if (type === "checkbox") {
                if (value === true || value === "true" || value === 1 || value === "1") {
                    score += field.pointsPer || 0;
                }
            } else if (type === "select") {
                const selectedLabel = String(value);
                const option = (field.options || []).find(opt => opt.value === selectedLabel || opt.label === selectedLabel);
                if (option) {
                    score += option.points || 0;
                }
            }
        });
        return score;
    }

    function renderPerformanceGraph(teamNumber, scopedMatchEntries) {
        const container = document.getElementById("breakdown-graph-container");
        container.innerHTML = "";

        const teamMatchEntries = scopedMatchEntries.slice()
            .sort((a, b) => (a.matchNumber || 0) - (b.matchNumber || 0));

        if (teamMatchEntries.length === 0) {
            container.innerHTML = '<div class="empty-indicator">No match scouting records available to graph.</div>';
            return;
        }

        const xData = teamMatchEntries.map(e => "QM " + (e.matchNumber || ""));
        const yData = teamMatchEntries.map(e => scoreEntry(state.configs.match, e.data));

        const trace = {
            x: xData,
            y: yData,
            type: 'scatter',
            mode: 'lines+markers',
            marker: { color: '#0b8f88', size: 8 },
            line: { color: '#0b8f88', width: 3 },
            name: 'Scouted Score'
        };

        const isDark = document.body.classList.contains("theme-dark");

        const layout = {
            xaxis: {
                title: 'Match Number',
                gridcolor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'
            },
            yaxis: {
                title: 'Scouted Score (Pts)',
                gridcolor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'
            },
            paper_bgcolor: 'rgba(0,0,0,0)',
            plot_bgcolor: 'rgba(0,0,0,0)',
            font: {
                color: isDark ? '#f4f2ed' : '#1d1a17',
                family: 'Trebuchet MS, sans-serif'
            },
            margin: { t: 20, b: 50, l: 50, r: 20 },
            hovermode: 'closest'
        };

        Plotly.newPlot('breakdown-graph-container', [trace], layout, { displayModeBar: false, responsive: true });
    }

    function formatDateString(dateStr) {
        if (!dateStr) return "";
        try {
            const date = new Date(dateStr);
            return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
        } catch (_) {
            return dateStr;
        }
    }
})();
