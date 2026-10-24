(function () {
    // Ranked pick list for one event. The Do Not Pick list is the same one Alliance Selection
    // uses: the server keeps those teams out of the ranking and returns them separately.
    let settings = null;
    let currentEventKey = "";
    let allTeams = [];
    let ranking = [];
    let dnp = [];
    let notes = {};
    let pickedLabels = new Map();
    let syncedAt = 0;
    let boardSyncedAt = null;
    let selectedMetric = "weighted";
    let searchQuery = "";
    let saveChain = Promise.resolve();
    let pendingSaves = 0;
    let pollIntervalId = null;
    let dragTeamNumber = null;

    const METRIC_PREF_KEY = "obsidian-pick-list-metric";

    // Font Awesome Free 6 icons.
    const ICONS = {
        grip: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 512" width="0.7em" height="1em" fill="currentColor" aria-hidden="true" focusable="false"><path d="M40 352h48c22.1 0 40 17.9 40 40v48c0 22.1-17.9 40-40 40H40c-22.1 0-40-17.9-40-40v-48c0-22.1 17.9-40 40-40zm192 0h48c22.1 0 40 17.9 40 40v48c0 22.1-17.9 40-40 40h-48c-22.1 0-40-17.9-40-40v-48c0-22.1 17.9-40 40-40zM40 192h48c22.1 0 40 17.9 40 40v48c0 22.1-17.9 40-40 40H40c-22.1 0-40-17.9-40-40v-48c0-22.1 17.9-40 40-40zm192 0h48c22.1 0 40 17.9 40 40v48c0 22.1-17.9 40-40 40h-48c-22.1 0-40-17.9-40-40v-48c0-22.1 17.9-40 40-40zM40 32h48c22.1 0 40 17.9 40 40v48c0 22.1-17.9 40-40 40H40c-22.1 0-40-17.9-40-40V72c0-22.1 17.9-40 40-40zm192 0h48c22.1 0 40 17.9 40 40v48c0 22.1-17.9 40-40 40h-48c-22.1 0-40-17.9-40-40V72c0-22.1 17.9-40 40-40z"/></svg>',
        up: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M233.4 105.4c12.5-12.5 32.8-12.5 45.3 0l192 192c12.5 12.5 12.5 32.8 0 45.3s-32.8 12.5-45.3 0L256 173.3 86.6 342.6c-12.5 12.5-32.8 12.5-45.3 0s-12.5-32.8 0-45.3l192-192z"/></svg>',
        down: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M233.4 406.6c12.5 12.5 32.8 12.5 45.3 0l192-192c12.5-12.5 12.5-32.8 0-45.3s-32.8-12.5-45.3 0L256 338.7 86.6 169.4c-12.5-12.5-32.8-12.5-45.3 0s-12.5 32.8 0 45.3l192 192z"/></svg>',
        remove: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 384 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M342.6 150.6c12.5-12.5 12.5-32.8 0-45.3s-32.8-12.5-45.3 0L192 210.7 86.6 105.4c-12.5-12.5-32.8-12.5-45.3 0s-12.5 32.8 0 45.3L146.7 256 41.4 361.4c-12.5 12.5-12.5 32.8 0 45.3s32.8 12.5 45.3 0L192 301.3 297.4 406.6c12.5 12.5 32.8 12.5 45.3 0s12.5-32.8 0-45.3L237.3 256 342.6 150.6z"/></svg>',
        add: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 448 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M256 80c0-17.7-14.3-32-32-32s-32 14.3-32 32V224H48c-17.7 0-32 14.3-32 32s14.3 32 32 32H192V432c0 17.7 14.3 32 32 32s32-14.3 32-32V288H400c17.7 0 32-14.3 32-32s-14.3-32-32-32H256V80z"/></svg>',
        ban: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M367.2 412.5L99.5 144.8C77.1 176.1 64 214.5 64 256c0 106 86 192 192 192c41.5 0 79.9-13.1 111.2-35.5zm45.3-45.3C434.9 335.9 448 297.5 448 256c0-106-86-192-192-192c-41.5 0-79.9 13.1-111.2 35.5L412.5 367.2zM0 256a256 256 0 1 1 512 0A256 256 0 1 1 0 256z"/></svg>',
        restore: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M125.7 160H176c17.7 0 32 14.3 32 32s-14.3 32-32 32H48c-17.7 0-32-14.3-32-32V64c0-17.7 14.3-32 32-32s32 14.3 32 32v51.2L97.6 97.6c87.5-87.5 229.3-87.5 316.8 0s87.5 229.3 0 316.8s-229.3 87.5-316.8 0c-12.5-12.5-12.5-32.8 0-45.3s32.8-12.5 45.3 0c62.5 62.5 163.8 62.5 226.3 0s62.5-163.8 0-226.3s-163.8-62.5-226.3 0L125.7 160z"/></svg>'
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

        await initPickListPage();
    });

    window.addEventListener("beforeunload", () => {
        if (pollIntervalId) clearInterval(pollIntervalId);
    });

    async function initPickListPage() {
        const rankingList = document.getElementById("ranking-list");
        try {
            const settingsResponse = await Obsidianscout.request("/api/settings");
            settings = settingsResponse.settings;
            currentEventKey = Obsidianscout.resolveEventKey(settings);

            setupMetricSelect();
            await setupEventSelect();

            document.getElementById("team-search").addEventListener("input", (e) => {
                searchQuery = e.target.value.toLowerCase().trim();
                renderUnranked();
            });
            document.getElementById("fill-btn").addEventListener("click", addAllByScore);
            document.getElementById("clear-btn").addEventListener("click", () => {
                if (ranking.length === 0) return;
                if (confirm("Clear the whole ranking? Your Do Not Pick list is not changed.")) {
                    ranking = [];
                    renderAll();
                    saveRanking();
                }
            });

            await loadEvent(currentEventKey);
        } catch (error) {
            console.error("Failed to initialize Pick List page:", error);
            Obsidianscout.showRetryButton(rankingList, "Failed to load page: " + error.message, initPickListPage);
        }
    }

    function isFtc() {
        return (window.Obsidianscout && typeof Obsidianscout.getProgram === "function")
            ? Obsidianscout.getProgram() === "FTC"
            : (settings && settings.program === "FTC");
    }

    function setupMetricSelect() {
        const select = document.getElementById("metric-select");
        const useEpa = !isFtc() && settings.useStatboticsEpa;
        const useExp = !isFtc() && settings.useMatch13Exp;
        const useOpr = settings.useTbaOpr;
        if (!useEpa) select.querySelector('option[value="epa"]')?.remove();
        if (!useExp) select.querySelector('option[value="exp"]')?.remove();
        if (!useOpr) select.querySelector('option[value="opr"]')?.remove();
        else if (isFtc()) select.querySelector('option[value="opr"]').textContent = "FTC Scout OPR";

        try {
            const saved = localStorage.getItem(METRIC_PREF_KEY);
            if (saved && select.querySelector(`option[value="${saved}"]`)) selectedMetric = saved;
        } catch (_) { }
        select.value = selectedMetric;
        select.addEventListener("change", () => {
            selectedMetric = select.value;
            try { localStorage.setItem(METRIC_PREF_KEY, selectedMetric); } catch (_) { }
            renderAll();
        });
    }

    async function setupEventSelect() {
        const eventFilter = document.getElementById("event-filter");
        const events = await Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`);
        eventFilter.innerHTML = "";
        let found = false;
        events.forEach(e => {
            const opt = document.createElement("option");
            opt.value = e.eventKey;
            opt.textContent = `${e.name} (${e.eventKey})`;
            if (e.eventKey === currentEventKey) {
                opt.selected = true;
                found = true;
            }
            eventFilter.appendChild(opt);
        });
        if (!found && currentEventKey) {
            const opt = document.createElement("option");
            opt.value = currentEventKey;
            opt.textContent = `Configured Event (${currentEventKey.toUpperCase()})`;
            opt.selected = true;
            eventFilter.prepend(opt);
        }
        eventFilter.addEventListener("change", async () => {
            currentEventKey = eventFilter.value;
            await loadEvent(currentEventKey);
        });
    }

    async function loadEvent(eventKey) {
        if (pollIntervalId) {
            clearInterval(pollIntervalId);
            pollIntervalId = null;
        }
        if (!eventKey) {
            document.getElementById("ranking-list").innerHTML = '<div class="empty-indicator pl-empty">Select an event in settings to build a pick list.</div>';
            return;
        }

        Obsidianscout.showLoadingSpinner(document.getElementById("ranking-list"), "Loading pick list...");
        allTeams = await Obsidianscout.request(`/api/teams?eventKey=${encodeURIComponent(eventKey)}`).catch(() => []);
        syncedAt = 0;
        boardSyncedAt = null;
        await Promise.all([refreshPickList(true), refreshBoard()]);
        renderAll();
        pollIntervalId = setInterval(poll, 4000);
    }

    async function refreshPickList(force) {
        const res = await Obsidianscout.request(`/api/alliance-selection/picklist?eventKey=${encodeURIComponent(currentEventKey)}`);
        // Don't let a poll overwrite edits that are still on their way to the server.
        if (!force && pendingSaves > 0) return false;
        if (force || res.updatedAt > syncedAt) {
            applyResponse(res);
            return true;
        }
        return false;
    }

    async function refreshBoard() {
        try {
            const res = await Obsidianscout.request(`/api/alliance-selection?eventKey=${encodeURIComponent(currentEventKey)}`);
            // An event with no saved board reports updatedAt 0; null marks "not loaded yet".
            if (boardSyncedAt !== null && res.updatedAt <= boardSyncedAt) return false;
            boardSyncedAt = res.updatedAt;
            pickedLabels = parsePickedLabels(res.selectionJson);
            return true;
        } catch (err) {
            console.warn("Failed to load alliance board:", err);
            return false;
        }
    }

    async function poll() {
        if (!currentEventKey) return;
        try {
            const [listChanged, boardChanged] = await Promise.all([refreshPickList(false), refreshBoard()]);
            if (listChanged || boardChanged) renderAll();
        } catch (err) {
            console.warn("Pick list sync failed:", err);
        }
    }

    function applyResponse(res) {
        ranking = Array.isArray(res.ranking) ? res.ranking.slice() : [];
        dnp = Array.isArray(res.dnp) ? res.dnp.slice() : [];
        notes = res.notes && typeof res.notes === "object" ? res.notes : {};
        syncedAt = Math.max(syncedAt, res.updatedAt || 0);
    }

    function parsePickedLabels(selectionJson) {
        const labels = new Map();
        let board = {};
        try { board = JSON.parse(selectionJson || "{}"); } catch (_) { }
        const slotNames = { captain: "Captain", firstPick: "First Pick", secondPick: "Second Pick", backup: "Backup" };
        Object.entries(board).forEach(([allianceKey, alliance]) => {
            if (!alliance || typeof alliance !== "object") return;
            const num = allianceKey.replace("alliance", "");
            Object.entries(slotNames).forEach(([slot, label]) => {
                if (alliance[slot]) labels.set(Number(alliance[slot]), `A${num} ${label}`);
            });
        });
        return labels;
    }

    // ── Saving ──────────────────────────────────────────────
    function saveRanking() {
        if (!currentEventKey) return saveChain;
        const payload = { eventKey: currentEventKey, ranking: ranking.slice() };
        pendingSaves++;
        // Chain saves so rapid edits reach the server in the order they were made.
        saveChain = saveChain.then(async () => {
            try {
                const res = await Obsidianscout.request("/api/alliance-selection/picklist", { method: "POST", json: payload });
                syncedAt = Math.max(syncedAt, res.updatedAt || 0);
            } catch (err) {
                console.error("Failed to save pick list:", err);
                Obsidianscout.showToast("Failed to save pick list: " + err.message, "error");
            } finally {
                pendingSaves--;
            }
        });
        return saveChain;
    }

    function setDoNotPick(teamNumber, isDnp) {
        if (isDnp) {
            ranking = ranking.filter(n => n !== teamNumber);
            if (!dnp.includes(teamNumber)) dnp.push(teamNumber);
        } else {
            dnp = dnp.filter(n => n !== teamNumber);
        }
        renderAll();
        const payload = { eventKey: currentEventKey, teamNumber, dnp: isDnp };
        pendingSaves++;
        saveChain = saveChain.then(async () => {
            try {
                const res = await Obsidianscout.request("/api/alliance-selection/picklist/dnp", { method: "POST", json: payload });
                // The server may put a restored team back at its old rank.
                if (pendingSaves === 1) {
                    applyResponse(res);
                    renderAll();
                }
                Obsidianscout.showToast(isDnp ? `${teamNumber} added to Do Not Pick` : `${teamNumber} removed from Do Not Pick`, "success");
            } catch (err) {
                console.error("Failed to update Do Not Pick:", err);
                Obsidianscout.showToast("Failed to update Do Not Pick: " + err.message, "error");
            } finally {
                pendingSaves--;
            }
        });
    }

    // ── Editing ─────────────────────────────────────────────
    function addToRanking(teamNumber) {
        if (ranking.includes(teamNumber) || dnp.includes(teamNumber)) return;
        ranking.push(teamNumber);
        renderAll();
        saveRanking();
    }

    function removeFromRanking(teamNumber) {
        ranking = ranking.filter(n => n !== teamNumber);
        renderAll();
        saveRanking();
    }

    function moveTeam(teamNumber, targetIndex) {
        const from = ranking.indexOf(teamNumber);
        if (from < 0) return;
        const to = Math.max(0, Math.min(ranking.length - 1, targetIndex));
        if (from === to) return;
        ranking.splice(from, 1);
        ranking.splice(to, 0, teamNumber);
        renderAll();
        saveRanking();
    }

    function addAllByScore() {
        const toAdd = getUnrankedTeams().map(t => t.teamNumber);
        if (toAdd.length === 0) {
            Obsidianscout.showToast("Every team is already ranked or on Do Not Pick", "info");
            return;
        }
        ranking = ranking.concat(toAdd);
        renderAll();
        saveRanking();
        Obsidianscout.showToast(`Added ${toAdd.length} teams by ${getMetricLabel()}`, "success");
    }

    // ── Data ────────────────────────────────────────────────
    function finiteOrNull(value) {
        return value === null || value === undefined || !Number.isFinite(Number(value)) ? null : Number(value);
    }

    /** Same blend as the Weighted metric on Alliance Selection. */
    function getWeightedScore(team) {
        let num = 0;
        let den = 0;
        const add = (value, weight) => {
            const v = finiteOrNull(value);
            if (v !== null) { num += v * weight; den += weight; }
        };
        add(team.averagePoints, 1.0);
        if (!isFtc() && settings.useStatboticsEpa) add(team.epa, 0.8);
        if (!isFtc() && settings.useMatch13Exp) add(team.exp, 0.8);
        if (settings.useTbaOpr) add(team.opr, 0.6);
        return den > 0 ? num / den : null;
    }

    function getMetricValue(team) {
        if (!team) return null;
        switch (selectedMetric) {
            case "scouted": return finiteOrNull(team.averagePoints);
            case "epa": return finiteOrNull(team.epa);
            case "exp": return finiteOrNull(team.exp);
            case "opr": return finiteOrNull(team.opr);
            default: return getWeightedScore(team);
        }
    }

    function getMetricLabel() {
        const option = document.querySelector(`#metric-select option[value="${selectedMetric}"]`);
        return option ? option.textContent : selectedMetric;
    }

    function findTeam(teamNumber) {
        return allTeams.find(t => t.teamNumber === teamNumber);
    }

    function teamName(teamNumber) {
        const team = findTeam(teamNumber);
        return team ? (team.nickname || team.name || "") : "Not at this event";
    }

    /** Event teams that are neither ranked nor on Do Not Pick, best score first. */
    function getUnrankedTeams() {
        const excluded = new Set([...ranking, ...dnp]);
        return allTeams
            .filter(t => !excluded.has(t.teamNumber))
            .sort((a, b) => {
                const va = getMetricValue(a);
                const vb = getMetricValue(b);
                if (va === null && vb === null) return a.teamNumber - b.teamNumber;
                if (va === null) return 1;
                if (vb === null) return -1;
                return vb - va || a.teamNumber - b.teamNumber;
            });
    }

    // ── Rendering ───────────────────────────────────────────
    function renderAll() {
        renderRanking();
        renderUnranked();
        renderDnp();
    }

    function teamCount(n) {
        return `${n} ${n === 1 ? "team" : "teams"}`;
    }

    function scoreBadge(teamNumber) {
        const value = getMetricValue(findTeam(teamNumber));
        return `<span class="pl-score" title="${Obsidianscout.escapeHtml(getMetricLabel())}">${value === null ? "–" : value.toFixed(1)}</span>`;
    }

    function teamInfo(teamNumber) {
        const esc = Obsidianscout.escapeHtml;
        const name = teamName(teamNumber);
        const note = notes[String(teamNumber)];
        return `
            <a class="pl-team-number" href="/team?teamNumber=${teamNumber}&eventKey=${encodeURIComponent(currentEventKey)}">${teamNumber}</a>
            <span class="pl-team-info">
                <span class="pl-team-name" title="${esc(name)}">${esc(name)}</span>
                ${note ? `<span class="pl-note" title="${esc(note)}">${esc(note)}</span>` : ""}
            </span>`;
    }

    function iconButton(act, icon, title, extra = "") {
        return `<button type="button" class="pl-icon-btn${act === "dnp" || act === "remove" ? " danger" : ""}" data-act="${act}" title="${title}" aria-label="${title}" ${extra}>${icon}</button>`;
    }

    function renderRanking() {
        const container = document.getElementById("ranking-list");
        const summary = document.getElementById("ranking-summary");
        container.innerHTML = "";

        const available = ranking.filter(n => !pickedLabels.has(n));
        summary.textContent = ranking.length === 0
            ? ""
            : `${ranking.length} ranked · ${available.length} still available · ${dnp.length} on Do Not Pick`;

        if (ranking.length === 0) {
            container.innerHTML = '<div class="empty-indicator pl-empty">No teams ranked yet. Add teams from the Unranked list, or use "Add all by score".</div>';
            return;
        }

        const nextTeam = available[0];
        ranking.forEach((teamNumber, idx) => {
            const pickedLabel = pickedLabels.get(teamNumber);
            const row = document.createElement("div");
            row.className = "pl-row" + (pickedLabel ? " is-picked" : "") + (teamNumber === nextTeam ? " is-next" : "");
            row.draggable = true;
            row.innerHTML = `
                <span class="pl-handle" title="Drag to reorder">${ICONS.grip}</span>
                <span class="pl-rank">${idx + 1}</span>
                ${teamInfo(teamNumber)}
                ${pickedLabel ? `<span class="pl-status picked" title="Already on the alliance board">${Obsidianscout.escapeHtml(pickedLabel)}</span>` : ""}
                ${teamNumber === nextTeam ? '<span class="pl-status next" title="Highest-ranked team that is still available">Next</span>' : ""}
                ${scoreBadge(teamNumber)}
                <span class="pl-actions">
                    ${iconButton("up", ICONS.up, "Move up", idx === 0 ? "disabled" : "")}
                    ${iconButton("down", ICONS.down, "Move down", idx === ranking.length - 1 ? "disabled" : "")}
                    ${iconButton("remove", ICONS.remove, "Remove from ranking")}
                    ${iconButton("dnp", ICONS.ban, "Add to Do Not Pick")}
                </span>`;

            row.querySelector('[data-act="up"]').addEventListener("click", () => moveTeam(teamNumber, idx - 1));
            row.querySelector('[data-act="down"]').addEventListener("click", () => moveTeam(teamNumber, idx + 1));
            row.querySelector('[data-act="remove"]').addEventListener("click", () => removeFromRanking(teamNumber));
            row.querySelector('[data-act="dnp"]').addEventListener("click", () => setDoNotPick(teamNumber, true));

            row.addEventListener("dragstart", (e) => {
                dragTeamNumber = teamNumber;
                row.classList.add("dragging");
                e.dataTransfer.effectAllowed = "move";
                e.dataTransfer.setData("text/plain", String(teamNumber));
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
                    moveTeam(dragTeamNumber, ranking.indexOf(teamNumber));
                }
            });

            container.appendChild(row);
        });
    }

    function renderUnranked() {
        const container = document.getElementById("unranked-list");
        const unranked = getUnrankedTeams();
        document.getElementById("unranked-count").textContent = teamCount(unranked.length);

        const shown = searchQuery
            ? unranked.filter(t => String(t.teamNumber).includes(searchQuery) ||
                (t.nickname || t.name || "").toLowerCase().includes(searchQuery))
            : unranked;

        container.innerHTML = "";
        if (shown.length === 0) {
            container.innerHTML = `<div class="empty-indicator pl-empty">${allTeams.length === 0 ? "No teams found for this event." : searchQuery ? "No matching teams." : "Every team is ranked or on Do Not Pick."}</div>`;
            return;
        }

        shown.forEach(team => {
            const teamNumber = team.teamNumber;
            const pickedLabel = pickedLabels.get(teamNumber);
            const row = document.createElement("div");
            row.className = "pl-row unranked" + (pickedLabel ? " is-picked" : "");
            row.innerHTML = `
                ${teamInfo(teamNumber)}
                ${pickedLabel ? `<span class="pl-status picked">${Obsidianscout.escapeHtml(pickedLabel)}</span>` : ""}
                ${scoreBadge(teamNumber)}
                <span class="pl-actions">
                    ${iconButton("add", ICONS.add, "Add to end of ranking")}
                    ${iconButton("dnp", ICONS.ban, "Add to Do Not Pick")}
                </span>`;
            row.querySelector('[data-act="add"]').addEventListener("click", () => addToRanking(teamNumber));
            row.querySelector('[data-act="dnp"]').addEventListener("click", () => setDoNotPick(teamNumber, true));
            container.appendChild(row);
        });
    }

    function renderDnp() {
        const container = document.getElementById("dnp-list");
        document.getElementById("dnp-count").textContent = teamCount(dnp.length);
        container.innerHTML = "";
        if (dnp.length === 0) {
            container.innerHTML = '<div class="empty-indicator pl-empty">No teams on Do Not Pick.</div>';
            return;
        }
        dnp.forEach(teamNumber => {
            const row = document.createElement("div");
            row.className = "pl-row dnp";
            row.innerHTML = `
                ${teamInfo(teamNumber)}
                <span class="pl-dnp-tag">DNP</span>
                <span class="pl-actions">
                    ${iconButton("restore", ICONS.restore, "Remove from Do Not Pick")}
                </span>`;
            row.querySelector('[data-act="restore"]').addEventListener("click", () => setDoNotPick(teamNumber, false));
            container.appendChild(row);
        });
    }
})();
