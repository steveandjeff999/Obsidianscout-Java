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
            currentEventKey = Obsidianscout.resolveEventKey(settings);

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

            document.getElementById("metric-select").addEventListener("change", (e) => {
                selectedMetric = e.target.value;
                updateRecommendations();
            });

            document.getElementById("team-search").addEventListener("input", (e) => {
                searchQuery = e.target.value.toLowerCase().trim();
                updateRecommendations();
            });

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
        boardState = {};
        for (let i = 1; i <= 8; i++) {
            boardState[`alliance${i}`] = { captain: null, firstPick: null, secondPick: null, backup: null };
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
        if (team.epa !== null && team.epa !== undefined) {
            num += team.epa * 0.8;
            den += 0.8;
        }
        if (team.opr !== null && team.opr !== undefined) {
            num += team.opr * 0.6;
            den += 0.6;
        }
        return den > 0 ? num / den : 0;
    }

    function getAvailableTeams() {
        const picked = getPickedTeamNumbers();
        let available = allTeams.filter(team => !picked.has(team.teamNumber));

        // Compute scores
        available.forEach(t => {
            t.calculatedWeighted = getWeightedScore(t);
        });

        // Search Filter
        if (searchQuery) {
            available = available.filter(t => {
                const numMatch = t.teamNumber.toString().includes(searchQuery);
                const nicknameMatch = (t.nickname || "").toLowerCase().includes(searchQuery);
                const nameMatch = (t.name || "").toLowerCase().includes(searchQuery);
                return numMatch || nicknameMatch || nameMatch;
            });
        }

        // Sorting
        if (selectedMetric === "scouted") {
            available.sort((a, b) => (b.averagePoints || -999) - (a.averagePoints || -999));
        } else if (selectedMetric === "epa") {
            available.sort((a, b) => (b.epa || -999) - (a.epa || -999));
        } else if (selectedMetric === "opr") {
            available.sort((a, b) => (b.opr || -999) - (a.opr || -999));
        } else {
            available.sort((a, b) => b.calculatedWeighted - a.calculatedWeighted);
        }

        return available;
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
            item.className = "rec-item";
            item.addEventListener("click", () => {
                openTeamBreakdown(team.teamNumber);
            });

            let scoreVal = "";
            if (selectedMetric === "scouted") {
                scoreVal = team.averagePoints !== null && team.averagePoints !== undefined ? team.averagePoints.toFixed(1) : "-";
            } else if (selectedMetric === "epa") {
                scoreVal = team.epa !== null && team.epa !== undefined ? team.epa.toFixed(1) : "-";
            } else if (selectedMetric === "opr") {
                scoreVal = team.opr !== null && team.opr !== undefined ? team.opr.toFixed(1) : "-";
            } else {
                scoreVal = team.calculatedWeighted.toFixed(1);
            }

            item.innerHTML = `
                <div class="rec-left">
                    <span class="rec-rank">#${idx + 1}</span>
                    <span class="rec-team-number">${team.teamNumber}</span>
                    <span class="rec-nickname" title="${team.nickname || team.name || ""}">${team.nickname || team.name || `Team ${team.teamNumber}`}</span>
                </div>
                <div class="rec-right">
                    <span class="rec-score-badge">${scoreVal}</span>
                </div>
            `;
            container.appendChild(item);
        });
    }

    function renderAlliances() {
        const container = document.getElementById("alliances-grid-container");
        container.innerHTML = "";

        for (let i = 1; i <= 8; i++) {
            const allianceKey = `alliance${i}`;
            const alliance = boardState[allianceKey];
            const card = document.createElement("div");
            card.className = "alliance-card";

            card.innerHTML = `
                <div class="alliance-card-header">
                    <span>Alliance ${i}</span>
                </div>
                <div class="slot-container">
                    ${renderSlotHtml(i, "captain", alliance.captain)}
                    ${renderSlotHtml(i, "firstPick", alliance.firstPick)}
                    ${renderSlotHtml(i, "secondPick", alliance.secondPick)}
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

        // Compute scores
        list.forEach(t => {
            t.calculatedWeighted = getWeightedScore(t);
        });

        // Sort by the selected metric (descending)
        if (selectedMetric === "scouted") {
            list.sort((a, b) => (b.averagePoints || -999) - (a.averagePoints || -999));
        } else if (selectedMetric === "epa") {
            list.sort((a, b) => (b.epa || -999) - (a.epa || -999));
        } else if (selectedMetric === "opr") {
            list.sort((a, b) => (b.opr || -999) - (a.opr || -999));
        } else {
            list.sort((a, b) => b.calculatedWeighted - a.calculatedWeighted);
        }

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
            const opr = team.opr !== null && team.opr !== undefined ? team.opr.toFixed(1) : "-";
            const rank = teamRanks[team.teamNumber];

            item.innerHTML = `
                <div class="selector-team">
                    <span class="rec-rank" style="min-width: 28px; text-align: left;">#${rank}</span>
                    <span>${team.teamNumber}</span>
                    <span class="selector-nickname" title="${team.nickname || team.name || ""}">${team.nickname || team.name || ""}</span>
                </div>
                <div class="selector-metrics">
                    <span>Scouted: ${points}</span>
                    <span>EPA: ${epa}</span>
                    <span>OPR: ${opr}</span>
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

        const epa = team.epa !== null && team.epa !== undefined ? team.epa.toFixed(1) : "-";
        const opr = team.opr !== null && team.opr !== undefined ? team.opr.toFixed(1) : "-";
        const teamMatches = state.matches.filter(m => {
            const allTeamsInMatch = (m.redTeams || []).concat(m.blueTeams || []);
            return allTeamsInMatch.some(k => k.replace(/^frc/, "") === String(teamNumber));
        });

        document.getElementById("breakdown-stat-scouted").textContent = calculatedAvg;
        document.getElementById("breakdown-stat-epa").textContent = epa;
        document.getElementById("breakdown-stat-opr").textContent = opr;
        document.getElementById("breakdown-stat-matches").textContent = teamMatches.length;

        // 2. Render pit specs
        renderPitSpecs(teamNumber, scoped.pit);

        // 3. Render scouter notes
        renderScouterNotes(teamNumber, scoped);

        // 4. Render match schedule
        renderMatchSchedule(teamNumber, teamMatches);

        // 5. Draw Plotly Graph
        renderPerformanceGraph(teamNumber, scoped.match);

        modal.classList.add("open");
    }

    function closeBreakdownModal() {
        const modal = document.getElementById("breakdown-modal-backdrop");
        modal.classList.remove("open");
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

        notes.forEach(n => {
            const card = document.createElement("div");
            card.className = "note-card";
            card.innerHTML = `
                <div class="note-header">
                    <span>${n.type} | Scouter Team ${n.scouter}</span>
                    <span>${formatDateString(n.date)}</span>
                </div>
                <div class="note-body">
                    <strong>${n.label}:</strong> ${n.text}
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
            tr.appendChild(labelCell);

            // Red alliance
            const redCell = document.createElement("td");
            (m.redTeams || []).forEach(key => {
                const num = key.replace(/^frc/, "");
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
                const num = key.replace(/^frc/, "");
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
