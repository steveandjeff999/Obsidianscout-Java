(function () {
    let currentYear = null;
    let currentEventKey = "";
    let currentMatch = null;
    let allTeams = [];
    let fieldImageObj = null;

    // Drawing state
    let annotations = []; // Array of strokes: { tool, color, width, points: [{x, y}] }
    let redoStack = []; // Stack for redo actions
    let isDrawing = false;
    let isDraggingMarker = false;
    let activeTool = "pen"; // "pen" | "eraser"
    let currentColor = "#ffffff";
    let penStrokeWidth = 6;
    let eraserStrokeWidth = 22;

    function getCurrentStrokeWidth() {
        return activeTool === "eraser" ? eraserStrokeWidth : penStrokeWidth;
    }
    let autoSaveTimeout = null;
    let pollIntervalId = null;
    let lastSyncedTime = 0;

    let canvas = null;
    let ctx = null;
    let drawingCanvas = null;
    let drawingCtx = null;

    // Stylus / S Pen / Palm Rejection State
    let isPenActive = false; // True whenever a pen is drawing/in contact
    let barrelWasDown = false;
    let barrelDownTime = 0;
    let barrelHoldActive = false;
    let previousToolBeforeErase = "pen";
    let quickPopupOpen = false;
    let lastPenCoords = { clientX: 0, clientY: 0, normX: 0.5, normY: 0.5 };
    let barrelHoldTimer = null;
    let lastColorCycleTime = 0;
    let strokeDrawnWhileBarrelDown = false;

    // Comp Level Rank for standard FIRST/FRC sorting: Practice -> Qualification -> Playoff
    function getCompLevelRank(compLevel) {
        if (!compLevel) return 1;
        const level = String(compLevel).trim().toLowerCase();
        switch (level) {
            case "p":
            case "pr":
            case "pm":
            case "practice":
                return 0;
            case "q":
            case "qm":
            case "qual":
            case "quals":
            case "qualification":
                return 1;
            case "ef":
            case "octofinal":
            case "octofinals":
                return 2;
            case "qf":
            case "quarterfinal":
            case "quarterfinals":
                return 3;
            case "sf":
            case "semifinal":
            case "semifinals":
                return 4;
            case "f":
            case "final":
            case "finals":
                return 5;
            case "playoff":
            case "playoffs":
                return 6;
            default:
                return 1;
        }
    }

    function compareMatches(a, b) {
        const rankA = getCompLevelRank(a.compLevel);
        const rankB = getCompLevelRank(b.compLevel);
        if (rankA !== rankB) {
            return rankA - rankB;
        }
        const setA = a.setNumber || 1;
        const setB = b.setNumber || 1;
        if (setA !== setB) {
            return setA - setB;
        }
        const matchA = a.matchNumber || 0;
        const matchB = b.matchNumber || 0;
        return matchA - matchB;
    }

    // Draggable team markers relative positions (xRatio, yRatio in [0..1])
    let teamMarkerPositions = {
        b1: { xRatio: 0.12, yRatio: 0.20 },
        b2: { xRatio: 0.12, yRatio: 0.50 },
        b3: { xRatio: 0.12, yRatio: 0.80 },
        r1: { xRatio: 0.88, yRatio: 0.20 },
        r2: { xRatio: 0.88, yRatio: 0.50 },
        r3: { xRatio: 0.88, yRatio: 0.80 }
    };

    // Rich data cache
    let state = {
        settings: null,
        configs: { match: null, pit: null, qualitative: null },
        matches: [],
        matchEntries: [],
        pitEntries: [],
        qualEntries: [],
        statsHistory: { oprs: {}, epaHistory: [], match13History: [] }
    };

    let teamGraphDatasource = {}; // teamNumber -> "scouted" | "statbotics" | "match13"

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

        canvas = document.getElementById("field-canvas");
        if (canvas) {
            ctx = canvas.getContext("2d");
        }

        wireDrawingTools();
        await initMatchPlanningPage();

        const container = document.querySelector(".canvas-container");
        if (window.ResizeObserver && container) {
            const ro = new ResizeObserver(() => {
                if (currentMatch) {
                    resizeCanvas();
                    renderCanvas();
                    updateFieldMarkerPositions();
                }
            });
            ro.observe(container);
        }

        window.addEventListener("resize", () => {
            if (currentMatch) {
                resizeCanvas();
                renderCanvas();
                updateFieldMarkerPositions();
            }
        });
    });

    async function initMatchPlanningPage() {
        try {
            const settingsResponse = await Obsidianscout.request("/api/settings");
            state.settings = settingsResponse.settings;
            currentYear = state.settings.year || new Date().getFullYear();
            currentEventKey = Obsidianscout.resolveEventKey(state.settings);

            // Populate event filter select dropdown
            const eventFilter = document.getElementById("event-filter");
            const events = await Obsidianscout.request(`/api/events?year=${currentYear}&cached=1`);
            eventFilter.innerHTML = "";
            events.forEach(e => {
                const opt = document.createElement("option");
                opt.value = e.eventKey;
                opt.textContent = `${Obsidianscout.localize(e.name) || e.name} (${e.eventKey})`;
                if (e.eventKey === currentEventKey) {
                    opt.selected = true;
                }
                eventFilter.appendChild(opt);
            });

            eventFilter.addEventListener("change", async () => {
                if (pollIntervalId) {
                    clearInterval(pollIntervalId);
                    pollIntervalId = null;
                }
                currentEventKey = eventFilter.value;
                await loadMatches(currentEventKey);
            });

            window.addEventListener("beforeunload", () => {
                if (pollIntervalId) {
                    clearInterval(pollIntervalId);
                    pollIntervalId = null;
                }
            });

            await loadMatches(currentEventKey);
        } catch (error) {
            console.error("Failed to initialize match planning page:", error);
            Obsidianscout.showToast("Failed to initialize match planning: " + error.message, "error");
        }
    }

    async function loadMatches(eventKey) {
        const matchSelect = document.getElementById("match-select");
        matchSelect.innerHTML = '<option value="">Loading matches...</option>';

        try {
            const [matchesList, teamsList, statsHistory] = await Promise.all([
                Obsidianscout.request(`/api/matches?eventKey=${eventKey}`),
                Obsidianscout.request(`/api/teams?eventKey=${eventKey}`).catch(() => []),
                Obsidianscout.request(`/api/stats/history?eventKey=${encodeURIComponent(eventKey)}`).catch(() => ({ oprs: {}, epaHistory: [], match13History: [] }))
            ]);

            state.matches = matchesList || [];
            allTeams = teamsList || [];
            state.statsHistory = statsHistory || { oprs: {}, epaHistory: [], match13History: [] };

            matchSelect.innerHTML = '<option value="">— Select Match —</option>';

            if (state.matches.length === 0) {
                matchSelect.innerHTML = '<option value="">No matches found</option>';
                return;
            }

            // Sort: Practice -> Qualification -> Playoff, then by set/match number
            state.matches.sort(compareMatches);

            state.matches.forEach(m => {
                const opt = document.createElement("option");
                opt.value = m.matchKey;
                const label = Obsidianscout.localize(m.label) || (m.compLevel ? `${m.compLevel.toUpperCase()} ${m.matchNumber || ""}` : m.matchKey);
                opt.textContent = label;
                matchSelect.appendChild(opt);
            });

            matchSelect.addEventListener("change", async () => {
                const selectedKey = matchSelect.value;
                if (!selectedKey) {
                    clearLoadedMatch();
                    return;
                }
                const match = state.matches.find(m => m.matchKey === selectedKey);
                if (match) {
                    await selectMatch(match);
                }
            });

            // If there's a match query param, select it automatically
            const urlParams = new URLSearchParams(window.location.search);
            const queryMatchKey = urlParams.get("match") || urlParams.get("matchKey");
            if (queryMatchKey) {
                const found = state.matches.find(m => m.matchKey === queryMatchKey);
                if (found) {
                    matchSelect.value = queryMatchKey;
                    await selectMatch(found);
                }
            }

        } catch (error) {
            console.error("Failed to load matches for planning:", error);
            matchSelect.innerHTML = '<option value="">Error loading matches</option>';
        }
    }

    function clearLoadedMatch() {
        if (pollIntervalId) {
            clearInterval(pollIntervalId);
            pollIntervalId = null;
        }
        currentMatch = null;
        annotations = [];
        const emptyState = document.getElementById("canvas-empty-state");
        if (emptyState) emptyState.style.display = "flex";
        if (ctx && canvas) {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
        }
        if (drawingCtx && drawingCanvas) {
            drawingCtx.clearRect(0, 0, drawingCanvas.width, drawingCanvas.height);
        }
        const markersLayer = document.getElementById("field-markers-layer");
        if (markersLayer) markersLayer.innerHTML = "";

        document.getElementById("stats-card").style.display = "none";
        resetDriverStationCards();
    }

    function resetDriverStationCards() {
        ["b1", "b2", "b3", "r1", "r2", "r3"].forEach(st => {
            const teamEl = document.getElementById(`${st}-team-number`);
            const nickEl = document.getElementById(`${st}-nickname`);
            if (teamEl) teamEl.textContent = "—";
            if (nickEl) nickEl.textContent = "";
        });
    }

    function defaultMarkerPositions() {
        return {
            b1: { xRatio: 0.12, yRatio: 0.20 },
            b2: { xRatio: 0.12, yRatio: 0.50 },
            b3: { xRatio: 0.12, yRatio: 0.80 },
            r1: { xRatio: 0.88, yRatio: 0.20 },
            r2: { xRatio: 0.88, yRatio: 0.50 },
            r3: { xRatio: 0.88, yRatio: 0.80 }
        };
    }

    function resetMarkerPositions() {
        teamMarkerPositions = defaultMarkerPositions();
    }

    function updateSaveBadge(status) {
        const badge = document.getElementById("save-status-indicator");
        if (!badge) return;
        badge.className = "save-status-badge";
        if (status === "saving") {
            badge.classList.add("saving");
            badge.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Saving...';
        } else if (status === "saved") {
            badge.classList.add("saved");
            badge.innerHTML = '<i class="fa-solid fa-cloud"></i> Saved';
        } else if (status === "error") {
            badge.classList.add("saving");
            badge.innerHTML = '<i class="fa-solid fa-triangle-exclamation"></i> Save failed';
        }
    }

    function scheduleAutoSave() {
        if (!currentMatch || !currentEventKey) return;
        updateSaveBadge("saving");
        if (autoSaveTimeout) {
            clearTimeout(autoSaveTimeout);
        }
        autoSaveTimeout = setTimeout(async () => {
            try {
                const payload = {
                    eventKey: currentEventKey,
                    matchKey: currentMatch.matchKey,
                    planJson: JSON.stringify({
                        annotations: annotations,
                        teamMarkerPositions: teamMarkerPositions
                    })
                };
                const res = await Obsidianscout.request("/api/match-planning", {
                    method: "POST",
                    body: JSON.stringify(payload)
                });
                if (res && res.updatedAt) {
                    lastSyncedTime = res.updatedAt;
                }
                updateSaveBadge("saved");
            } catch (err) {
                console.error("Auto-save match plan failed:", err);
                updateSaveBadge("error");
            }
        }, 500);
    }

    async function loadSavedPlan(eventKey, matchKey) {
        try {
            const res = await Obsidianscout.request(`/api/match-planning?eventKey=${encodeURIComponent(eventKey)}&matchKey=${encodeURIComponent(matchKey)}`);
            if (res && res.updatedAt) {
                lastSyncedTime = res.updatedAt;
            }
            if (res && res.planJson && res.planJson !== "{}" && res.planJson !== "") {
                const data = JSON.parse(res.planJson);
                if (Array.isArray(data.annotations)) {
                    annotations = data.annotations;
                }
                if (data.teamMarkerPositions && typeof data.teamMarkerPositions === "object") {
                    teamMarkerPositions = Object.assign({}, defaultMarkerPositions(), data.teamMarkerPositions);
                }
                updateSaveBadge("saved");
                return true;
            }
        } catch (e) {
            console.warn("No existing saved plan found or failed to load:", e);
        }
        updateSaveBadge("saved");
        return false;
    }

    async function selectMatch(match) {
        if (pollIntervalId) {
            clearInterval(pollIntervalId);
            pollIntervalId = null;
        }
        lastSyncedTime = 0;
        currentMatch = match;
        annotations = [];
        redoStack = [];
        resetMarkerPositions();

        const emptyState = document.getElementById("canvas-empty-state");
        if (emptyState) emptyState.style.display = "none";

        // 1. Update Driver Station side columns
        updateDriverStations(match);

        // 2. Load saved drawings and team positions from the database
        await loadSavedPlan(currentEventKey, match.matchKey);

        // 3. Fetch field image dynamically for this year
        try {
            const imageInfo = await Obsidianscout.request(`/api/field-images?year=${currentYear}`);
            await loadFieldImage(imageInfo.imagePath);
        } catch (e) {
            console.warn(`No specific field image found for year ${currentYear}, attempting fallback...`, e);
            try {
                await loadFieldImage(`/assets/images/field-images/${currentYear}/rebuilt.png`);
            } catch (err2) {
                try {
                    await loadFieldImage(`/assets/images/field-images/${currentYear}/reefscape.png`);
                } catch (err3) {
                    console.error("Failed to load any field image:", err3);
                    renderPlaceholderField();
                }
            }
        }

        // 4. Render Draggable Square Team Markers on field
        renderFieldMarkers();

        // 5. Load stats for all 6 teams in this match
        await loadAllTeamStats(match);

        // 6. Start real-time polling sync (every 3 seconds)
        pollIntervalId = setInterval(pollServer, 3000);
    }

    async function pollServer() {
        if (!currentEventKey || !currentMatch || isDrawing || isDraggingMarker) return;
        try {
            // 1. Poll match plan drawings & marker positions
            const res = await Obsidianscout.request(`/api/match-planning?eventKey=${encodeURIComponent(currentEventKey)}&matchKey=${encodeURIComponent(currentMatch.matchKey)}`);
            if (res && res.updatedAt > lastSyncedTime) {
                if (res.planJson && res.planJson !== "{}" && res.planJson !== "") {
                    const data = JSON.parse(res.planJson);
                    if (Array.isArray(data.annotations)) {
                        annotations = data.annotations;
                    }
                    if (data.teamMarkerPositions && typeof data.teamMarkerPositions === "object") {
                        teamMarkerPositions = Object.assign({}, defaultMarkerPositions(), data.teamMarkerPositions);
                    }
                    renderCanvas();
                    updateFieldMarkerPositions();
                }
                lastSyncedTime = res.updatedAt;
                updateSaveBadge("saved");
            }

            // 2. Poll scouting entries for real-time team stats updates
            const [matchEntries, pitEntries, qualEntries] = await Promise.all([
                Obsidianscout.request(`/api/scouting?includePrescout=true`),
                Obsidianscout.request(`/api/pit-scouting?includePrescout=true`),
                Obsidianscout.request(`/api/qual-scouting?includePrescout=true`)
            ]);

            const currentMatchCount = state.matchEntries?.length || 0;
            const newMatchCount = matchEntries?.length || 0;
            const currentPitCount = state.pitEntries?.length || 0;
            const newPitCount = pitEntries?.length || 0;
            const currentQualCount = state.qualEntries?.length || 0;
            const newQualCount = qualEntries?.length || 0;

            const hasEntriesChanged = (currentMatchCount !== newMatchCount) ||
                (currentPitCount !== newPitCount) ||
                (currentQualCount !== newQualCount);

            if (hasEntriesChanged) {
                state.matchEntries = matchEntries || [];
                state.pitEntries = pitEntries || [];
                state.qualEntries = qualEntries || [];
                await loadAllTeamStats(currentMatch);
            }
        } catch (err) {
            console.warn("Match planning polling sync failed:", err);
        }
    }

    function updateDriverStations(match) {
        const formatTeamNum = (rawKey) => {
            if (!rawKey) return null;
            const clean = String(rawKey).replace(/^(frc|ftc)/i, "").trim();
            const teamObj = allTeams.find(t => String(t.teamNumber) === clean);
            return {
                number: clean,
                nickname: teamObj ? (teamObj.nickname || teamObj.name || "") : ""
            };
        };

        const blueInfo = (match.blueTeams || []).map(formatTeamNum);
        const redInfo = (match.redTeams || []).map(formatTeamNum);

        ["b1", "b2", "b3"].forEach((st, idx) => {
            const info = blueInfo[idx];
            const teamEl = document.getElementById(`${st}-team-number`);
            const nickEl = document.getElementById(`${st}-nickname`);
            if (teamEl) teamEl.textContent = info ? info.number : "—";
            if (nickEl) nickEl.textContent = info ? info.nickname : "";
        });

        ["r1", "r2", "r3"].forEach((st, idx) => {
            const info = redInfo[idx];
            const teamEl = document.getElementById(`${st}-team-number`);
            const nickEl = document.getElementById(`${st}-nickname`);
            if (teamEl) teamEl.textContent = info ? info.number : "—";
            if (nickEl) nickEl.textContent = info ? info.nickname : "";
        });
    }

    function loadFieldImage(src) {
        return new Promise((resolve, reject) => {
            const img = new Image();
            img.crossOrigin = "anonymous";
            img.onload = () => {
                fieldImageObj = img;
                resizeCanvas();
                renderCanvas();
                updateFieldMarkerPositions();
                resolve();
            };
            img.onerror = (err) => {
                reject(err);
            };
            img.src = src;
        });
    }

    function renderPlaceholderField() {
        fieldImageObj = null;
        const container = canvas.parentElement;
        canvas.width = container.clientWidth || 800;
        canvas.height = Math.round(canvas.width * 0.5);
        renderCanvas();
        updateFieldMarkerPositions();
    }

    function resizeCanvas() {
        if (!canvas) return;
        const container = canvas.closest('.canvas-container') || canvas.parentElement;
        const containerWidth = container.clientWidth || 800;

        let w = containerWidth;
        let h = Math.round(containerWidth * 0.5);

        if (fieldImageObj && fieldImageObj.naturalWidth > 0) {
            const aspect = fieldImageObj.naturalHeight / fieldImageObj.naturalWidth;
            h = Math.round(containerWidth * aspect);
        }

        canvas.width = w;
        canvas.height = h;

        const viewport = document.getElementById("canvas-viewport");
        if (viewport) {
            viewport.style.width = `${w}px`;
            viewport.style.height = `${h}px`;
        }

        if (!drawingCanvas) {
            drawingCanvas = document.createElement("canvas");
        }
        drawingCanvas.width = w;
        drawingCanvas.height = h;
        drawingCtx = drawingCanvas.getContext("2d");
    }

    function renderCanvas() {
        if (!ctx || !canvas) return;
        const W = canvas.width;
        const H = canvas.height;

        ctx.clearRect(0, 0, W, H);

        // 1. Draw Field Image or gradient fallback onto main canvas
        if (fieldImageObj) {
            ctx.drawImage(fieldImageObj, 0, 0, W, H);
        } else {
            // Gradient fallback
            const grad = ctx.createLinearGradient(0, 0, W, 0);
            grad.addColorStop(0, "#1e3a8a");
            grad.addColorStop(0.5, "#1e293b");
            grad.addColorStop(1, "#7f1d1d");
            ctx.fillStyle = grad;
            ctx.fillRect(0, 0, W, H);

            ctx.strokeStyle = "rgba(255, 255, 255, 0.2)";
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.moveTo(W / 2, 0);
            ctx.lineTo(W / 2, H);
            ctx.stroke();
        }

        // 2. Replay User Annotations on isolated drawing layer (so eraser never affects the field image)
        replayAnnotations();

        // 3. Composite drawing layer onto the main canvas
        if (drawingCanvas) {
            ctx.drawImage(drawingCanvas, 0, 0);
        }
    }

    // ── Draggable Square Team Markers ────────────────────────
    function renderFieldMarkers() {
        const layer = document.getElementById("field-markers-layer");
        if (!layer) return;
        layer.innerHTML = "";

        if (!currentMatch || !canvas) return;

        const W = canvas.clientWidth || canvas.width;
        const H = canvas.clientHeight || canvas.height;

        const stations = [
            { id: "b1", label: "B1", alliance: "blue", teamKey: (currentMatch.blueTeams || [])[0] },
            { id: "b2", label: "B2", alliance: "blue", teamKey: (currentMatch.blueTeams || [])[1] },
            { id: "b3", label: "B3", alliance: "blue", teamKey: (currentMatch.blueTeams || [])[2] },
            { id: "r1", label: "R1", alliance: "red", teamKey: (currentMatch.redTeams || [])[0] },
            { id: "r2", label: "R2", alliance: "red", teamKey: (currentMatch.redTeams || [])[1] },
            { id: "r3", label: "R3", alliance: "red", teamKey: (currentMatch.redTeams || [])[2] }
        ];

        stations.forEach(st => {
            if (!st.teamKey) return;
            const teamNum = String(st.teamKey).replace(/^(frc|ftc)/i, "").trim();
            const pos = teamMarkerPositions[st.id] || { xRatio: 0.5, yRatio: 0.5 };

            const el = document.createElement("div");
            el.className = `field-team-marker marker-${st.alliance}`;
            el.dataset.station = st.id;
            el.style.left = `${pos.xRatio * W}px`;
            el.style.top = `${pos.yRatio * H}px`;

            el.innerHTML = `
                <span class="marker-station">${st.label}</span>
                <span class="marker-number">${teamNum}</span>
            `;

            makeMarkerDraggable(el, st.id);
            layer.appendChild(el);
        });
    }

    function makeMarkerDraggable(el, stationId) {
        let dragStartX = 0;
        let dragStartY = 0;
        let markerStartLeft = 0;
        let markerStartTop = 0;

        el.addEventListener("pointerdown", (e) => {
            e.stopPropagation(); // Don't trigger drawing on canvas underneath
            el.setPointerCapture(e.pointerId);
            isDraggingMarker = true;
            el.classList.add("dragging");

            dragStartX = e.clientX;
            dragStartY = e.clientY;

            const W = canvas.clientWidth || canvas.width;
            const H = canvas.clientHeight || canvas.height;
            const curPos = teamMarkerPositions[stationId] || { xRatio: 0.5, yRatio: 0.5 };
            markerStartLeft = curPos.xRatio * W;
            markerStartTop = curPos.yRatio * H;
        });

        el.addEventListener("pointermove", (e) => {
            if (!isDraggingMarker) return;
            e.stopPropagation();

            const dx = e.clientX - dragStartX;
            const dy = e.clientY - dragStartY;

            const W = canvas.clientWidth || canvas.width;
            const H = canvas.clientHeight || canvas.height;

            const markerHalfW = el.offsetWidth / 2 || 26;
            const markerHalfH = el.offsetHeight / 2 || 26;

            let newX = Math.max(markerHalfW, Math.min(W - markerHalfW, markerStartLeft + dx));
            let newY = Math.max(markerHalfH, Math.min(H - markerHalfH, markerStartTop + dy));

            el.style.left = `${newX}px`;
            el.style.top = `${newY}px`;

            teamMarkerPositions[stationId] = {
                xRatio: Math.max(0, Math.min(1, newX / W)),
                yRatio: Math.max(0, Math.min(1, newY / H))
            };
        });

        const endDrag = (e) => {
            if (isDraggingMarker) {
                isDraggingMarker = false;
                el.classList.remove("dragging");
                try {
                    el.releasePointerCapture(e.pointerId);
                } catch (err) {}
                scheduleAutoSave();
            }
        };

        el.addEventListener("pointerup", endDrag);
        el.addEventListener("pointercancel", endDrag);
    }

    function updateFieldMarkerPositions() {
        const layer = document.getElementById("field-markers-layer");
        if (!layer || !canvas) return;
        const W = canvas.clientWidth || canvas.width;
        const H = canvas.clientHeight || canvas.height;

        layer.querySelectorAll(".field-team-marker").forEach(el => {
            const stationId = el.dataset.station;
            const pos = teamMarkerPositions[stationId];
            if (pos) {
                el.style.left = `${pos.xRatio * W}px`;
                el.style.top = `${pos.yRatio * H}px`;
            }
        });
    }

    function drawSquareMarkersOnCanvas(exportCtx, W, H) {
        if (!currentMatch) return;

        const stations = [
            { id: "b1", label: "B1", alliance: "blue", teamKey: (currentMatch.blueTeams || [])[0] },
            { id: "b2", label: "B2", alliance: "blue", teamKey: (currentMatch.blueTeams || [])[1] },
            { id: "b3", label: "B3", alliance: "blue", teamKey: (currentMatch.blueTeams || [])[2] },
            { id: "r1", label: "R1", alliance: "red", teamKey: (currentMatch.redTeams || [])[0] },
            { id: "r2", label: "R2", alliance: "red", teamKey: (currentMatch.redTeams || [])[1] },
            { id: "r3", label: "R3", alliance: "red", teamKey: (currentMatch.redTeams || [])[2] }
        ];

        const boxSize = Math.max(42, Math.round(W * 0.056));
        const fontSize = Math.max(14, Math.round(boxSize * 0.38));
        const tagFontSize = Math.max(10, Math.round(boxSize * 0.24));

        exportCtx.save();
        exportCtx.textAlign = "center";
        exportCtx.textBaseline = "middle";

        stations.forEach(st => {
            if (!st.teamKey) return;
            const teamNum = String(st.teamKey).replace(/^(frc|ftc)/i, "").trim();
            const pos = teamMarkerPositions[st.id] || { xRatio: 0.5, yRatio: 0.5 };
            const cx = pos.xRatio * W;
            const cy = pos.yRatio * H;
            const isBlue = st.alliance === "blue";

            // Draw square box
            exportCtx.fillStyle = "rgba(15, 23, 42, 0.92)";
            exportCtx.strokeStyle = isBlue ? "#3b82f6" : "#ef4444";
            exportCtx.lineWidth = 3;
            roundRect(exportCtx, cx - boxSize / 2, cy - boxSize / 2, boxSize, boxSize, 4, true, true);

            // Draw station tag (B1/R1)
            exportCtx.fillStyle = isBlue ? "#93c5fd" : "#fca5a5";
            exportCtx.font = `800 ${tagFontSize}px sans-serif`;
            exportCtx.fillText(st.label, cx, cy - boxSize * 0.22);

            // Draw team number
            exportCtx.fillStyle = "#ffffff";
            exportCtx.font = `900 ${fontSize}px sans-serif`;
            exportCtx.shadowColor = "rgba(0,0,0,0.9)";
            exportCtx.shadowBlur = 4;
            exportCtx.fillText(teamNum, cx, cy + boxSize * 0.18);
            exportCtx.shadowBlur = 0;
        });

        exportCtx.restore();
    }

    function roundRect(context, x, y, width, height, radius, fill, stroke) {
        context.beginPath();
        context.moveTo(x + radius, y);
        context.lineTo(x + width - radius, y);
        context.quadraticCurveTo(x + width, y, x + width, y + radius);
        context.lineTo(x + width, y + height - radius);
        context.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
        context.lineTo(x + radius, y + height);
        context.quadraticCurveTo(x, y + height, x, y + height - radius);
        context.lineTo(x, y + radius);
        context.quadraticCurveTo(x, y, x + radius, y);
        context.closePath();
        if (fill) context.fill();
        if (stroke) context.stroke();
    }

    // ── Drawing Engine ───────────────────────────────────────
    function undoAction() {
        if (annotations.length > 0) {
            const last = annotations.pop();
            redoStack.push(last);
            renderCanvas();
            scheduleAutoSave();
        }
    }

    function redoAction() {
        if (redoStack.length > 0) {
            const restored = redoStack.pop();
            annotations.push(restored);
            renderCanvas();
            scheduleAutoSave();
        }
    }

        function syncStrokeSlider() {
            const currentWidth = getCurrentStrokeWidth();
            const slider = document.getElementById("stroke-slider");
            const popupSlider = document.getElementById("popup-stroke-slider");
            if (slider && parseInt(slider.value) !== currentWidth) slider.value = currentWidth;
            if (popupSlider && parseInt(popupSlider.value) !== currentWidth) popupSlider.value = currentWidth;
        }

        // Helper setters to keep toolbar & popup in sync
        function setTool(toolName) {
            activeTool = toolName;
            const penBtn = document.getElementById("tool-pen");
            const eraserBtn = document.getElementById("tool-eraser");
            const popupPen = document.getElementById("popup-tool-pen");
            const popupEraser = document.getElementById("popup-tool-eraser");

            if (toolName === "pen") {
                if (penBtn) penBtn.classList.add("active");
                if (eraserBtn) eraserBtn.classList.remove("active");
                if (popupPen) popupPen.classList.add("active");
                if (popupEraser) popupEraser.classList.remove("active");
            } else {
                if (penBtn) penBtn.classList.remove("active");
                if (eraserBtn) eraserBtn.classList.add("active");
                if (popupPen) popupPen.classList.remove("active");
                if (popupEraser) popupEraser.classList.add("active");
            }
            syncStrokeSlider();
            updateStrokePreview();
        }

        function setColor(hex) {
            currentColor = hex;
            document.querySelectorAll(".swatch-btn").forEach(b => {
                b.classList.toggle("active", b.getAttribute("data-color").toLowerCase() === hex.toLowerCase());
            });
            document.querySelectorAll(".stylus-popup-swatch").forEach(b => {
                b.classList.toggle("active", b.getAttribute("data-color").toLowerCase() === hex.toLowerCase());
            });
            updateStrokePreview();
        }

        function setStrokeWidth(val) {
            const parsed = Math.max(2, Math.min(48, parseInt(val) || 6));
            if (activeTool === "eraser") {
                eraserStrokeWidth = parsed;
            } else {
                penStrokeWidth = parsed;
            }
            syncStrokeSlider();
            updateStrokePreview();
        }

        function cycleNextColor() {
            const swatches = Array.from(document.querySelectorAll(".swatch-btn")).map(b => b.getAttribute("data-color"));
            if (swatches.length === 0) return;
            const idx = swatches.indexOf(currentColor);
            const nextIdx = (idx + 1) % swatches.length;
            setColor(swatches[nextIdx]);
            Obsidianscout.showToast?.(`Color: ${swatches[nextIdx]}`, "info");
        }

        // Quick Popup Management
        const quickPopup = document.getElementById("stylus-quick-popup");
        function showQuickPopup(clientX, clientY) {
            if (!quickPopup || !canvas) return;
            const viewport = document.getElementById("canvas-viewport") || canvas.parentElement;
            const vpRect = viewport.getBoundingClientRect();

            let x = clientX - vpRect.left;
            let y = clientY - vpRect.top;

            // Offset slightly so it doesn't block the pen tip
            x += 16;
            y -= 30;

            quickPopup.style.display = "flex";
            const popupW = quickPopup.offsetWidth || 210;
            const popupH = quickPopup.offsetHeight || 140;

            // Keep within viewport boundaries
            if (x + popupW > vpRect.width) x = vpRect.width - popupW - 10;
            if (x < 10) x = 10;
            if (y + popupH > vpRect.height) y = vpRect.height - popupH - 10;
            if (y < 10) y = 10;

            quickPopup.style.left = `${x}px`;
            quickPopup.style.top = `${y}px`;
            quickPopupOpen = true;

            // Sync values in popup
            setTool(activeTool);
            setColor(currentColor);
            setStrokeWidth(currentStrokeWidth);
        }

        function hideQuickPopup() {
            if (quickPopup && quickPopupOpen) {
                quickPopup.style.display = "none";
                quickPopupOpen = false;
            }
        }

        // Wire popup controls
        const popupPen = document.getElementById("popup-tool-pen");
        if (popupPen) popupPen.addEventListener("click", () => setTool("pen"));
        const popupEraser = document.getElementById("popup-tool-eraser");
        if (popupEraser) popupEraser.addEventListener("click", () => setTool("eraser"));

        document.querySelectorAll(".stylus-popup-swatch").forEach(btn => {
            btn.addEventListener("click", () => {
                setColor(btn.getAttribute("data-color"));
                if (activeTool !== "pen") setTool("pen");
            });
        });

        const popupSlider = document.getElementById("popup-stroke-slider");
        if (popupSlider) {
            popupSlider.addEventListener("input", (e) => setStrokeWidth(e.target.value));
        }

        // Close popup when clicking outside
        window.addEventListener("pointerdown", (e) => {
            if (quickPopupOpen && quickPopup && !quickPopup.contains(e.target) && e.target !== canvas) {
                hideQuickPopup();
            }
        });

        // Wire Toolbar Color Swatches
        document.querySelectorAll(".swatch-btn").forEach(btn => {
            btn.addEventListener("click", () => {
                setColor(btn.getAttribute("data-color"));
                setTool("pen");
            });
        });

        // Pen Tool
        const penBtn = document.getElementById("tool-pen");
        if (penBtn) penBtn.addEventListener("click", () => setTool("pen"));

        // Eraser Tool
        const eraserBtn = document.getElementById("tool-eraser");
        if (eraserBtn) eraserBtn.addEventListener("click", () => setTool("eraser"));

        // Stroke Size Slider
        const strokeSlider = document.getElementById("stroke-slider");
        if (strokeSlider) {
            strokeSlider.addEventListener("input", (e) => setStrokeWidth(e.target.value));
        }

        // Undo Button
        const undoBtn = document.getElementById("undo-canvas-btn");
        if (undoBtn) undoBtn.addEventListener("click", undoAction);

        // Redo Button
        const redoBtn = document.getElementById("redo-canvas-btn");
        if (redoBtn) redoBtn.addEventListener("click", redoAction);

        // Clear Button
        const clearBtn = document.getElementById("clear-canvas-btn");
        if (clearBtn) {
            clearBtn.addEventListener("click", () => {
                if (annotations.length === 0) return;
                redoStack = [...annotations];
                annotations = [];
                renderCanvas();
                scheduleAutoSave();
                Obsidianscout.showToast("Canvas cleared (Undo available)", "info");
            });
        }

        // Fullscreen Toggle
        const fullscreenBtn = document.getElementById("fullscreen-btn");
        const pageContainer = document.getElementById("match-planning-container") || document.querySelector(".page-container");
        if (fullscreenBtn && pageContainer) {
            fullscreenBtn.addEventListener("click", () => {
                if (!document.fullscreenElement && !document.webkitFullscreenElement) {
                    if (pageContainer.requestFullscreen) {
                        pageContainer.requestFullscreen().catch(err => console.warn(err));
                    } else if (pageContainer.webkitRequestFullscreen) {
                        pageContainer.webkitRequestFullscreen();
                    }
                } else {
                    if (document.exitFullscreen) {
                        document.exitFullscreen().catch(err => console.warn(err));
                    } else if (document.webkitExitFullscreen) {
                        document.webkitExitFullscreen();
                    }
                }
            });

            const onFullscreenChange = () => {
                const isFs = !!(document.fullscreenElement || document.webkitFullscreenElement);
                fullscreenBtn.innerHTML = isFs ? '<i class="fa-solid fa-compress"></i> Exit' : '<i class="fa-solid fa-expand"></i> Fullscreen';
                setTimeout(() => {
                    resizeCanvas();
                    renderCanvas();
                    updateFieldMarkerPositions();
                }, 100);
            };

            document.addEventListener("fullscreenchange", onFullscreenChange);
            document.addEventListener("webkitfullscreenchange", onFullscreenChange);
        }

        // Download Plan
        const downloadBtn = document.getElementById("download-canvas-btn");
        if (downloadBtn) {
            downloadBtn.addEventListener("click", () => {
                if (!canvas) return;
                renderCanvas();
                drawSquareMarkersOnCanvas(ctx, canvas.width, canvas.height);
                const link = document.createElement("a");
                const matchLabel = currentMatch ? (currentMatch.label || `match-${currentMatch.matchNumber}`) : "match-plan";
                link.download = `obsidianscout-plan-${matchLabel}.png`;
                link.href = canvas.toDataURL("image/png");
                link.click();
                renderCanvas();
            });
        }

        // Keyboard Shortcuts (Undo, Redo, P, E, X, [, ])
        window.addEventListener("keydown", (e) => {
            const tag = (e.target && e.target.tagName) ? e.target.tagName.toLowerCase() : "";
            if (tag === "input" || tag === "textarea" || tag === "select") return;

            if ((e.ctrlKey || e.metaKey) && !e.shiftKey && (e.key === "z" || e.key === "Z")) {
                e.preventDefault();
                undoAction();
            } else if (((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === "z" || e.key === "Z")) || ((e.ctrlKey || e.metaKey) && (e.key === "y" || e.key === "Y"))) {
                e.preventDefault();
                redoAction();
            } else if (!e.ctrlKey && !e.metaKey && !e.altKey) {
                if (e.key === "p" || e.key === "P") {
                    setTool("pen");
                } else if (e.key === "e" || e.key === "E") {
                    setTool("eraser");
                } else if (e.key === "x" || e.key === "X") {
                    setTool(activeTool === "pen" ? "eraser" : "pen");
                } else if (e.key === "[") {
                    setStrokeWidth(getCurrentStrokeWidth() - 2);
                } else if (e.key === "]") {
                    setStrokeWidth(getCurrentStrokeWidth() + 2);
                }
            }
        });

        // Pointer Events (Mouse, Touch, Stylus) on Canvas with S Pen and Palm Rejection
        if (canvas) {
            canvas.style.touchAction = "none";
            // Prevent native context menu on right click or stylus barrel button
            canvas.addEventListener("contextmenu", (e) => e.preventDefault());

            canvas.addEventListener("pointerdown", handlePointerDown);
            canvas.addEventListener("pointermove", handlePointerMove);
            canvas.addEventListener("pointerup", handlePointerUp);
            canvas.addEventListener("pointercancel", handlePointerUp);
            canvas.addEventListener("pointerleave", handlePointerLeave);

            // High frequency pointer raw update if supported
            if (window.PointerEvent && "onpointerrawupdate" in window) {
                canvas.addEventListener("pointerrawupdate", handlePointerMove);
            }
        }

        updateStrokePreview();
    }

    function updateStrokePreview() {
        const preview = document.getElementById("stroke-preview-circle");
        const popupPreview = document.getElementById("popup-stroke-preview");
        const currentWidth = getCurrentStrokeWidth();
        const size = Math.max(3, Math.min(24, Math.round(currentWidth * 0.55)));
        const color = (activeTool === "eraser") ? "#cbd5e1" : currentColor;

        if (preview) {
            preview.style.width = `${size}px`;
            preview.style.height = `${size}px`;
            preview.style.backgroundColor = color;
        }
        if (popupPreview) {
            popupPreview.style.width = `${size}px`;
            popupPreview.style.height = `${size}px`;
            popupPreview.style.backgroundColor = color;
        }
    }

    function getNormalizedCoords(e) {
        const rect = canvas.getBoundingClientRect();
        return {
            x: Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width)),
            y: Math.max(0, Math.min(1, (e.clientY - rect.top) / rect.height))
        };
    }

    // Process S Pen / Stylus Barrel Button edge transitions
    function checkStylusBarrelButton(e) {
        if (e.pointerType !== "pen") return;
        lastPenCoords = { clientX: e.clientX, clientY: e.clientY };

        // Barrel button (buttons & 2)
        const isBarrelDown = (e.buttons & 2) !== 0 || (e.button === 2);

        // Hardware inverted eraser tip (buttons & 32)
        const isEraserTip = (e.buttons & 32) !== 0;
        if (isEraserTip && activeTool !== "eraser") {
            previousToolBeforeErase = activeTool;
            activeTool = "eraser";
            barrelHoldActive = true;
            updateStrokePreview();
        }

        // RISING EDGE: Button just pressed
        if (isBarrelDown && !barrelWasDown) {
            barrelDownTime = performance.now();
            barrelWasDown = true;
            strokeDrawnWhileBarrelDown = false;

            // Immediately switch to eraser so there is ZERO delay or pen-tool flash!
            if (!barrelHoldActive) {
                previousToolBeforeErase = activeTool === "eraser" ? "pen" : activeTool;
                activeTool = "eraser";
                barrelHoldActive = true;
                updateStrokePreview();
            }

            // If currently drawing, switch ongoing stroke tool to eraser
            if (isDrawing && annotations.length > 0) {
                strokeDrawnWhileBarrelDown = true;
                const cur = annotations[annotations.length - 1];
                cur.tool = "eraser";
                cur.widthRatio = eraserStrokeWidth / 1000;
                renderCanvas();
            }
        }
        // FALLING EDGE: Button released
        else if (!isBarrelDown && barrelWasDown) {
            barrelWasDown = false;
            const pressDuration = performance.now() - barrelDownTime;

            // If held for >= 450ms OR if an erase stroke was drawn during the press -> this was a HOLD TO ERASE action!
            if (strokeDrawnWhileBarrelDown || pressDuration >= 450) {
                if (barrelHoldActive) {
                    barrelHoldActive = false;
                    setTool(previousToolBeforeErase);
                }
            } else {
                // Quick tap release (<450ms without drawing an erase stroke) -> This was a CLICK to cycle color!
                if (barrelHoldActive) {
                    barrelHoldActive = false;
                    setTool(previousToolBeforeErase === "eraser" ? "pen" : previousToolBeforeErase);
                }

                // If tap initiated a 1-point stroke, discard it so button click doesn't leave stray mark
                if (annotations.length > 0) {
                    const lastStroke = annotations[annotations.length - 1];
                    if (lastStroke && lastStroke.points.length <= 2) {
                        annotations.pop();
                        renderCanvas();
                    }
                }

                // Debounce: prevent single physical click from cycling multiple times
                const now = performance.now();
                if (now - lastColorCycleTime >= 350) {
                    lastColorCycleTime = now;
                    cycleNextColor();
                    if (activeTool !== "pen") {
                        setTool("pen");
                    }
                }
            }
        }
    }

    function handlePointerDown(e) {
        if (!currentMatch) return;

        // Active Palm Rejection: If pen is active/drawing, swallow all finger touches!
        if (isPenActive && e.pointerType === "touch") {
            e.preventDefault();
            return;
        }

        // If clicking outside popup, dismiss it
        hideQuickPopup();

        // Check stylus button edges
        checkStylusBarrelButton(e);

        if (e.pointerType === "pen") {
            isPenActive = true;
        }

        const isBarrelDown = (e.buttons & 2) !== 0 || (e.button === 2);
        if (isBarrelDown) {
            strokeDrawnWhileBarrelDown = true;
            if (!barrelHoldActive) {
                previousToolBeforeErase = activeTool === "eraser" ? "pen" : activeTool;
                activeTool = "eraser";
                barrelHoldActive = true;
                updateStrokePreview();
            }
        }

        try {
            canvas.setPointerCapture(e.pointerId);
        } catch (err) {}

        isDrawing = true;
        const normPos = getNormalizedCoords(e);
        // Uniform stroke size dictated by tool-specific width directly (no variable pressure curves)
        const effectiveTool = (isBarrelDown || activeTool === "eraser") ? "eraser" : activeTool;
        const effectiveWidth = (effectiveTool === "eraser" ? eraserStrokeWidth : penStrokeWidth);
        const strokeRatio = effectiveWidth / 1000;
        annotations.push({
            tool: effectiveTool,
            color: currentColor,
            widthRatio: strokeRatio,
            points: [normPos]
        });
        renderCanvas();
    }

    function handlePointerMove(e) {
        // Active Palm Rejection: discard touch events while pen is drawing
        if (isPenActive && e.pointerType === "touch") {
            e.preventDefault();
            return;
        }

        checkStylusBarrelButton(e);

        const isBarrelDown = (e.buttons & 2) !== 0 || (e.button === 2);
        if (isBarrelDown && isDrawing) {
            strokeDrawnWhileBarrelDown = true;
        }

        if (!isDrawing) return;
        const normPos = getNormalizedCoords(e);
        const curStroke = annotations[annotations.length - 1];
        if (curStroke) {
            curStroke.points.push(normPos);
            renderCanvas();
        }
    }

    function handlePointerUp(e) {
        checkStylusBarrelButton(e);

        if (e.pointerType === "pen") {
            isPenActive = false;
        }

        if (isDrawing) {
            isDrawing = false;
            try {
                canvas.releasePointerCapture(e.pointerId);
            } catch (err) {}
            redoStack = []; // Reset redo stack when a new stroke is completed
            scheduleAutoSave();
        }
    }

    function handlePointerLeave(e) {
        if (barrelHoldTimer) {
            clearTimeout(barrelHoldTimer);
            barrelHoldTimer = null;
        }
        if (barrelHoldActive) {
            barrelHoldActive = false;
            activeTool = previousToolBeforeErase;
            updateStrokePreview();
        }
        barrelWasDown = false;
        if (e.pointerType === "pen") {
            isPenActive = false;
        }
        handlePointerUp(e);
    }

    function replayAnnotations() {
        if (!drawingCtx || !drawingCanvas) return;
        const W = drawingCanvas.width;
        const H = drawingCanvas.height;

        drawingCtx.clearRect(0, 0, W, H);

        if (annotations.length === 0) return;

        annotations.forEach(stroke => {
            if (!stroke.points || stroke.points.length < 1) return;

            drawingCtx.save();
            if (stroke.tool === "eraser") {
                drawingCtx.globalCompositeOperation = "destination-out";
                drawingCtx.strokeStyle = "rgba(0,0,0,1)";
                drawingCtx.fillStyle = "rgba(0,0,0,1)";
            } else {
                drawingCtx.globalCompositeOperation = "source-over";
                drawingCtx.strokeStyle = stroke.color;
                drawingCtx.fillStyle = stroke.color;
            }

            const strokeWidth = Math.max(1, (stroke.widthRatio ? stroke.widthRatio * W : (stroke.width ? (stroke.width / 1000) * W : 6)));
            drawingCtx.lineWidth = strokeWidth;
            drawingCtx.lineCap = "round";
            drawingCtx.lineJoin = "round";

            const getPtCoords = (pt) => {
                let nx = pt.xRatio !== undefined ? pt.xRatio : pt.x;
                let ny = pt.yRatio !== undefined ? pt.yRatio : pt.y;
                if (nx > 1) nx = nx / 1000;
                if (ny > 1) ny = ny / 500;
                return { x: nx * W, y: ny * H };
            };

            const firstPt = getPtCoords(stroke.points[0]);

            if (stroke.points.length === 1) {
                drawingCtx.beginPath();
                drawingCtx.arc(firstPt.x, firstPt.y, strokeWidth / 2, 0, Math.PI * 2);
                drawingCtx.fill();
            } else {
                drawingCtx.beginPath();
                drawingCtx.moveTo(firstPt.x, firstPt.y);
                for (let i = 1; i < stroke.points.length; i++) {
                    const pt = getPtCoords(stroke.points[i]);
                    drawingCtx.lineTo(pt.x, pt.y);
                }
                drawingCtx.stroke();
            }
            drawingCtx.restore();
        });
    }

    // ── Team Stats Section ───────────────────────────────────
    async function loadAllTeamStats(match) {
        const statsCard = document.getElementById("stats-card");
        const blueContainer = document.getElementById("blue-team-cards");
        const redContainer = document.getElementById("red-team-cards");

        blueContainer.innerHTML = '<div class="empty-indicator">Loading blue alliance data...</div>';
        redContainer.innerHTML = '<div class="empty-indicator">Loading red alliance data...</div>';
        statsCard.style.display = "block";

        try {
            // Load all entries and configs in parallel
            const [
                matchConfig,
                pitConfig,
                qualConfig,
                matchEntries,
                pitEntries,
                qualEntries
            ] = await Promise.all([
                Obsidianscout.request("/api/config"),
                Obsidianscout.request("/api/pit-config"),
                Obsidianscout.request("/api/qual-config"),
                Obsidianscout.request(`/api/scouting?includePrescout=true`),
                Obsidianscout.request(`/api/pit-scouting?includePrescout=true`),
                Obsidianscout.request(`/api/qual-scouting?includePrescout=true`)
            ]);

            state.configs.match = matchConfig;
            state.configs.pit = pitConfig;
            state.configs.qualitative = qualConfig;
            state.matchEntries = matchEntries || [];
            state.pitEntries = pitEntries || [];
            state.qualEntries = qualEntries || [];

            blueContainer.innerHTML = "";
            redContainer.innerHTML = "";

            const blue = match.blueTeams || [];
            const red = match.redTeams || [];

            blue.forEach((teamKey, idx) => {
                const teamNum = parseInt(String(teamKey).replace(/^(frc|ftc)/i, "")) || 0;
                const card = createTeamProfileCard(teamNum, "blue", idx + 1);
                blueContainer.appendChild(card);
            });

            red.forEach((teamKey, idx) => {
                const teamNum = parseInt(String(teamKey).replace(/^(frc|ftc)/i, "")) || 0;
                const card = createTeamProfileCard(teamNum, "red", idx + 1);
                redContainer.appendChild(card);
            });

        } catch (error) {
            console.error("Failed to load team performance stats:", error);
            blueContainer.innerHTML = `<div class="empty-indicator">Error loading stats: ${error.message}</div>`;
            redContainer.innerHTML = "";
        }
    }

    function getScoutingEntriesForTeam(teamNumber) {
        const teamMatch = state.matchEntries.filter(e => e.targetTeamNumber === teamNumber);
        const currentMatch = teamMatch.filter(e => e.eventKey === currentEventKey && !e.isPrescout);
        const prescoutMatch = teamMatch.filter(e => e.isPrescout);
        const finalMatch = (currentMatch.length < 3) ? currentMatch.concat(prescoutMatch) : currentMatch;

        const teamQual = state.qualEntries.filter(e => e.targetTeamNumber === teamNumber);
        const currentQual = teamQual.filter(e => e.eventKey === currentEventKey && !e.isPrescout);
        const prescoutQual = teamQual.filter(e => e.isPrescout);
        const finalQual = (currentQual.length < 3) ? currentQual.concat(prescoutQual) : currentQual;

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

    function createTeamProfileCard(teamNumber, alliance, stationNum) {
        const team = allTeams.find(t => t.teamNumber === teamNumber) || { teamNumber };
        const scoped = getScoutingEntriesForTeam(teamNumber);

        const card = document.createElement("div");
        card.className = `team-profile-card ${alliance}-card`;

        // Calculate dynamic average points
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

        const epa = (team.epa !== null && team.epa !== undefined) ? team.epa.toFixed(1) : "-";
        const exp = (team.exp !== null && team.exp !== undefined) ? team.exp.toFixed(1) : "-";
        const opr = (team.opr !== null && team.opr !== undefined) ? team.opr.toFixed(1) : "-";

        const teamMatches = state.matches.filter(m => {
            const allTeamsInMatch = (m.redTeams || []).concat(m.blueTeams || []);
            return allTeamsInMatch.some(k => k.replace(/^(frc|ftc)/i, "") === String(teamNumber));
        });

        const stationLabel = `${alliance.toUpperCase()[0]}${stationNum}`;
        const uniqueTabId = `team-${teamNumber}`;

        if (!teamGraphDatasource[teamNumber]) {
            teamGraphDatasource[teamNumber] = "scouted";
        }
        const currentDatasource = teamGraphDatasource[teamNumber];

        card.innerHTML = `
            <div class="card-team-header">
                <div class="card-team-number">
                    <span>#${teamNumber}</span>
                    <span class="card-team-nickname" title="${team.nickname || team.name || ""}">${team.nickname || team.name || ""}</span>
                </div>
                <span class="card-station-pill ${alliance}">${stationLabel}</span>
            </div>

            <!-- Summary Badges -->
            <div class="breakdown-summary-grid">
                <div class="breakdown-stat-card">
                    <div class="breakdown-stat-label">Scouted Avg</div>
                    <div class="breakdown-stat-val highlight-avg">${calculatedAvg}</div>
                </div>
                ${effectiveUseEpa ? `
                <div class="breakdown-stat-card">
                    <div class="breakdown-stat-label">Statbotics EPA</div>
                    <div class="breakdown-stat-val highlight-epa">${epa}</div>
                </div>` : ''}
                ${effectiveUseExp ? `
                <div class="breakdown-stat-card">
                    <div class="breakdown-stat-label">Match 13 EXP</div>
                    <div class="breakdown-stat-val highlight-avg">${exp}</div>
                </div>` : ''}
                ${effectiveUseOpr ? `
                <div class="breakdown-stat-card">
                    <div class="breakdown-stat-label">${isFtc ? 'FTC OPR' : 'TBA OPR'}</div>
                    <div class="breakdown-stat-val highlight-opr">${opr}</div>
                </div>` : ''}
                <div class="breakdown-stat-card">
                    <div class="breakdown-stat-label">Matches</div>
                    <div class="breakdown-stat-val">${teamMatches.length}</div>
                </div>
            </div>

            <!-- Tabs -->
            <div class="card-tabs">
                <button class="card-tab-btn active" data-tab="overview-${uniqueTabId}">Overview & Notes</button>
                <button class="card-tab-btn" data-tab="history-${uniqueTabId}">History</button>
                <button class="card-tab-btn" data-tab="graph-${uniqueTabId}">Graph</button>
            </div>

            <!-- Tab 1: Overview & Notes -->
            <div class="card-tab-content active" id="tab-overview-${uniqueTabId}">
                <div class="pit-specs-grid" id="pit-specs-${uniqueTabId}"></div>
                <div class="notes-list" id="notes-list-${uniqueTabId}"></div>
            </div>

            <!-- Tab 2: Match History -->
            <div class="card-tab-content" id="tab-history-${uniqueTabId}">
                <table class="table" style="font-size: 0.8rem;">
                    <thead>
                        <tr>
                            <th>Match</th>
                            <th>Red</th>
                            <th>Blue</th>
                        </tr>
                    </thead>
                    <tbody id="history-tbody-${uniqueTabId}"></tbody>
                </table>
            </div>

            <!-- Tab 3: Performance Graph -->
            <div class="card-tab-content" id="tab-graph-${uniqueTabId}">
                <div class="graph-tab-header">
                    <div class="graph-datasource-pills" id="datasource-pills-${uniqueTabId}">
                        <button type="button" class="graph-datasource-btn ${currentDatasource === 'scouted' ? 'active' : ''}" data-source="scouted" data-team="${teamNumber}">Scouted</button>
                        ${effectiveUseEpa ? `<button type="button" class="graph-datasource-btn ${currentDatasource === 'statbotics' ? 'active' : ''}" data-source="statbotics" data-team="${teamNumber}">Statbotics</button>` : ''}
                        ${effectiveUseExp ? `<button type="button" class="graph-datasource-btn ${currentDatasource === 'match13' ? 'active' : ''}" data-source="match13" data-team="${teamNumber}">Match 13</button>` : ''}
                    </div>
                    <button type="button" class="graph-fullscreen-btn-inline" id="btn-fullscreen-${uniqueTabId}" title="View graph in fullscreen">
                        <i class="fa-solid fa-expand"></i> Fullscreen
                    </button>
                </div>
                <div id="graph-container-${uniqueTabId}" class="team-graph-container" title="Click to view fullscreen"></div>
                <div class="graph-click-hint"><i class="fa-solid fa-up-right-and-down-left-from-center"></i> Click graph to enlarge</div>
            </div>
        `;

        // Wire Tab Switching
        card.querySelectorAll(".card-tab-btn").forEach(btn => {
            btn.addEventListener("click", () => {
                const targetTab = btn.getAttribute("data-tab");
                card.querySelectorAll(".card-tab-btn").forEach(b => b.classList.remove("active"));
                btn.classList.add("active");
                card.querySelectorAll(".card-tab-content").forEach(c => {
                    c.classList.remove("active");
                    if (c.id === `tab-${targetTab}`) {
                        c.classList.add("active");
                    }
                });
                if (targetTab.startsWith("graph-")) {
                    if (window.Plotly) {
                        Plotly.Plots.resize(`graph-container-${uniqueTabId}`);
                    }
                }
            });
        });

        // Wire Datasource Selector Buttons in Card
        const pillsContainer = card.querySelector(`#datasource-pills-${uniqueTabId}`);
        if (pillsContainer) {
            pillsContainer.querySelectorAll(".graph-datasource-btn").forEach(dsBtn => {
                dsBtn.addEventListener("click", (e) => {
                    e.stopPropagation();
                    const source = dsBtn.getAttribute("data-source");
                    teamGraphDatasource[teamNumber] = source;
                    pillsContainer.querySelectorAll(".graph-datasource-btn").forEach(b => b.classList.remove("active"));
                    dsBtn.classList.add("active");
                    renderPerformanceGraphInline(`graph-container-${uniqueTabId}`, teamNumber, scoped.match, source, team);
                });
            });
        }

        // Wire Fullscreen Expand Button
        const fullscreenBtn = card.querySelector(`#btn-fullscreen-${uniqueTabId}`);
        if (fullscreenBtn) {
            fullscreenBtn.addEventListener("click", (e) => {
                e.stopPropagation();
                openFullscreenGraphModal(teamNumber, teamGraphDatasource[teamNumber] || "scouted", scoped.match, team);
            });
        }

        // Fill in Pit Specs
        renderPitSpecsInline(card.querySelector(`#pit-specs-${uniqueTabId}`), teamNumber, scoped.pit);

        // Fill in Scouter Notes
        renderScouterNotesInline(card.querySelector(`#notes-list-${uniqueTabId}`), teamNumber, scoped);

        // Fill in Match History
        renderMatchHistoryInline(card.querySelector(`#history-tbody-${uniqueTabId}`), teamNumber, teamMatches);

        // Fill in Plotly Graph
        setTimeout(() => {
            renderPerformanceGraphInline(`graph-container-${uniqueTabId}`, teamNumber, scoped.match, teamGraphDatasource[teamNumber] || "scouted", team);
        }, 50);

        return card;
    }

    function renderPitSpecsInline(container, teamNumber, scopedPit) {
        container.innerHTML = "";
        const entry = scopedPit.find(e => e.targetTeamNumber === teamNumber);
        if (!entry || !entry.data || !state.configs.pit || !state.configs.pit.fields) {
            container.innerHTML = '<div style="grid-column: 1/-1; color: var(--muted); font-size: 0.75rem; font-style: italic;">No pit specs recorded.</div>';
            return;
        }

        let count = 0;
        state.configs.pit.fields.forEach(f => {
            if (f.type !== "text" && f.type !== "textarea") {
                const val = entry.data[f.id];
                if (val !== undefined && val !== null && val !== "") {
                    let formattedVal = val;
                    if (f.type === "checkbox") {
                        formattedVal = (val === true || val === "true" || val === 1) ? "Yes" : "No";
                    }
                    const item = document.createElement("div");
                    item.className = "pit-spec-item";
                    item.innerHTML = `
                        <span class="pit-spec-label">${Obsidianscout.localize(f.label) || f.label}</span>
                        <span class="pit-spec-value">${formattedVal}</span>
                    `;
                    container.appendChild(item);
                    count++;
                }
            }
        });

        if (count === 0) {
            container.innerHTML = '<div style="grid-column: 1/-1; color: var(--muted); font-size: 0.75rem; font-style: italic;">No specification values found.</div>';
        }
    }

    function renderScouterNotesInline(container, teamNumber, scoped) {
        container.innerHTML = "";
        const notes = [];

        const addComments = (entries, config, typeLabel) => {
            if (!config || !config.fields) return;
            entries.forEach(e => {
                if (e.targetTeamNumber === teamNumber && e.data) {
                    config.fields.forEach(f => {
                        if ((f.type === "text" || f.type === "textarea") && e.data[f.id]) {
                            notes.push({
                                type: typeLabel + (e.matchNumber ? ` M${e.matchNumber}` : ""),
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
        addComments(scoped.qualitative, state.configs.qualitative, "Qual");
        addComments(scoped.pit, state.configs.pit, "Pit");

        if (notes.length === 0) {
            container.innerHTML = '<div style="color: var(--muted); font-size: 0.75rem; font-style: italic;">No qualitative comments logged.</div>';
            return;
        }

        notes.sort((a, b) => new Date(b.date) - new Date(a.date));

        notes.forEach(n => {
            const item = document.createElement("div");
            item.className = "note-card";
            item.innerHTML = `
                <div class="note-header">
                    <span>${n.type} | Scouter ${n.scouter || ''}</span>
                    <span>${formatDateString(n.date)}</span>
                </div>
                <div class="note-body">
                    <strong>${n.label}:</strong> ${n.text}
                </div>
            `;
            container.appendChild(item);
        });
    }

    function renderMatchHistoryInline(tbody, teamNumber, teamMatches) {
        tbody.innerHTML = "";
        if (teamMatches.length === 0) {
            tbody.innerHTML = '<tr><td colspan="3" style="text-align: center; color: var(--muted); padding: 8px;">No matches.</td></tr>';
            return;
        }

        teamMatches.sort(compareMatches);

        teamMatches.forEach(m => {
            const tr = document.createElement("tr");

            const labelCell = document.createElement("td");
            labelCell.textContent = m.label || `Q${m.matchNumber || ""}`;
            tr.appendChild(labelCell);

            const redCell = document.createElement("td");
            (m.redTeams || []).forEach(key => {
                const num = key.replace(/^(frc|ftc)/i, "");
                const isSelf = num === String(teamNumber);
                const badge = document.createElement("span");
                badge.className = `alliance-member-badge red-team ${isSelf ? 'highlight-self' : ''}`;
                badge.textContent = num;
                redCell.appendChild(badge);
            });
            tr.appendChild(redCell);

            const blueCell = document.createElement("td");
            (m.blueTeams || []).forEach(key => {
                const num = key.replace(/^(frc|ftc)/i, "");
                const isSelf = num === String(teamNumber);
                const badge = document.createElement("span");
                badge.className = `alliance-member-badge blue-team ${isSelf ? 'highlight-self' : ''}`;
                badge.textContent = num;
                blueCell.appendChild(badge);
            });
            tr.appendChild(blueCell);

            tbody.appendChild(tr);
        });
    }

    // ── Graph Data Extraction & Helpers ──────────────────────────
    function formatMatchKeyToLabel(matchKey) {
        if (!matchKey) return "Match";
        const str = String(matchKey).trim();
        const parts = str.split('_');
        const compPart = parts.length > 1 ? parts[1].toLowerCase() : str.toLowerCase();
        if (compPart.startsWith("qm")) {
            return `QM ${compPart.replace("qm", "")}`;
        }
        if (compPart.startsWith("qf")) {
            const match = compPart.match(/qf(\d+)m(\d+)/);
            return match ? `QF ${match[1]}-${match[2]}` : compPart.toUpperCase();
        }
        if (compPart.startsWith("sf")) {
            const match = compPart.match(/sf(\d+)m(\d+)/);
            return match ? `SF ${match[1]}-${match[2]}` : compPart.toUpperCase();
        }
        if (compPart.startsWith("f")) {
            const match = compPart.match(/f(\d+)m(\d+)/);
            return match ? `Final ${match[2]}` : compPart.toUpperCase();
        }
        return compPart.toUpperCase();
    }

    function getMatchSortWeightFromLabel(label) {
        if (!label) return 999999;
        const upper = String(label).toUpperCase();
        let base = 100000;
        if (upper.startsWith("P ") || upper.startsWith("PR") || upper.startsWith("PRACTICE")) base = 10000;
        else if (upper.startsWith("QM")) base = 20000;
        else if (upper.startsWith("EF") || upper.startsWith("OCTO")) base = 30000;
        else if (upper.startsWith("QF")) base = 40000;
        else if (upper.startsWith("SF")) base = 50000;
        else if (upper.startsWith("FINAL") || upper.startsWith("F ")) base = 60000;

        const digits = label.match(/\d+/g);
        if (digits) {
            const num = digits.reduce((acc, d, i) => acc + parseInt(d) * Math.pow(10, (digits.length - 1 - i) * 2), 0);
            return base + num;
        }
        return base;
    }

    function extractTeamExpData(matchObj, teamNumber) {
        if (!matchObj || typeof matchObj !== "object") return null;
        const teams = matchObj.teams;
        if (!teams) return null;
        const num = Number(teamNumber);
        if (Array.isArray(teams)) {
            return teams.find(t => {
                const tNum = t.teamNumber || t.team_number || t.team || String(t.teamKey || "").replace(/\D/g, "");
                return Number(tNum) === num;
            }) || null;
        }
        if (typeof teams === "object") {
            return teams[num]
                || teams[String(num)]
                || teams[`frc${num}`]
                || teams[`ftc${num}`]
                || teams[`frc_${num}`]
                || null;
        }
        return null;
    }

    function getTeamGraphData(teamNumber, datasource, scopedMatch) {
        if (datasource === "statbotics") {
            const epaHistory = state.statsHistory?.epaHistory || [];
            const num = Number(teamNumber);
            const teamMatches = [];

            epaHistory.forEach(item => {
                const itemTeam = Number(item.team || item.team_number || String(item.teamKey || "").replace(/\D/g, ""));
                if (itemTeam === num) {
                    const matchKey = item.match || item.matchKey || "";
                    const label = formatMatchKeyToLabel(matchKey);
                    const sortWeight = getMatchSortWeightFromLabel(label);
                    const epaObj = item.epa;
                    const val = (typeof epaObj === "object" && epaObj !== null)
                        ? Number(epaObj.total_points ?? epaObj.epa ?? epaObj.total ?? 0)
                        : Number(item.epa ?? 0);
                    teamMatches.push({ label, val, sortWeight });
                }
            });

            teamMatches.sort((a, b) => a.sortWeight - b.sortWeight);

            return {
                x: teamMatches.map(m => m.label),
                y: teamMatches.map(m => m.val),
                name: `Team ${teamNumber} Statbotics EPA`,
                label: "Statbotics EPA",
                color: "#10b981",
                unit: "EPA"
            };
        }

        if (datasource === "match13") {
            const match13History = state.statsHistory?.match13History || [];
            const teamMatches = [];

            match13History.forEach(matchObj => {
                const teamData = extractTeamExpData(matchObj, teamNumber);
                if (!teamData) return;
                const matchKey = matchObj.key || matchObj.matchKey || matchObj.match_key || matchObj.match || "";
                const label = formatMatchKeyToLabel(matchKey);
                const sortWeight = getMatchSortWeightFromLabel(label);
                const val = Number(teamData.xpPost ?? teamData.xp ?? teamData.total ?? teamData.exp ?? 0);
                teamMatches.push({ label, val, sortWeight });
            });

            teamMatches.sort((a, b) => a.sortWeight - b.sortWeight);

            return {
                x: teamMatches.map(m => m.label),
                y: teamMatches.map(m => m.val),
                name: `Team ${teamNumber} Match 13 EXP`,
                label: "Match 13 EXP",
                color: "#f59e0b",
                unit: "EXP"
            };
        }

        // Default: Scouted data
        const sorted = [...(scopedMatch || [])].sort((a, b) => {
            const numA = a.matchNumber || 0;
            const numB = b.matchNumber || 0;
            return numA - numB;
        });

        const xValues = sorted.map((e, idx) => {
            if (e.matchNumber) return `QM ${e.matchNumber}`;
            if (e.matchKey) return formatMatchKeyToLabel(e.matchKey);
            return `#${idx + 1}`;
        });
        const yValues = sorted.map(e => scoreEntry(state.configs.match, e.data));

        return {
            x: xValues,
            y: yValues,
            name: `Team ${teamNumber} Scouted Points`,
            label: "Scouted Points",
            color: "#6366f1",
            unit: "pts"
        };
    }

    function renderPerformanceGraphInline(containerId, teamNumber, scopedMatch, datasource = "scouted", team = null) {
        const container = document.getElementById(containerId);
        if (!container || !window.Plotly) return;

        // Cleanly clear container and purge any existing Plotly graph
        if (window.Plotly) {
            try {
                Plotly.purge(container);
            } catch (e) {}
        }
        container.innerHTML = "";

        const resolvedTeam = team || allTeams.find(t => t.teamNumber === teamNumber) || { teamNumber };
        const dataSeries = getTeamGraphData(teamNumber, datasource, scopedMatch);

        if (!dataSeries.x || dataSeries.x.length === 0) {
            container.innerHTML = `
                <div style="height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--muted); font-size: 0.75rem; font-style: italic; gap: 4px;">
                    <i class="fa-solid fa-chart-line" style="font-size: 1.2rem; opacity: 0.4;"></i>
                    <span>No ${dataSeries.label} data recorded for Team #${teamNumber}.</span>
                </div>
            `;
            // Make clickable even if empty to see full modal/options
            container.onclick = () => {
                openFullscreenGraphModal(teamNumber, datasource, scopedMatch, resolvedTeam);
            };
            return;
        }

        const isDark = document.body.classList.contains("theme-dark");

        const trace = {
            x: dataSeries.x,
            y: dataSeries.y,
            mode: 'lines+markers',
            type: 'scatter',
            name: dataSeries.name,
            line: { color: dataSeries.color, width: 2.5 },
            marker: { size: 6, color: dataSeries.color },
            hovertemplate: `%{x}: <b>%{y:.1f}</b> ${dataSeries.unit}<extra></extra>`
        };

        const layout = {
            margin: { t: 10, r: 15, l: 30, b: 30 },
            paper_bgcolor: 'transparent',
            plot_bgcolor: 'transparent',
            showlegend: false,
            xaxis: {
                tickfont: { color: isDark ? '#94a3b8' : '#64748b', size: 10 },
                gridcolor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'
            },
            yaxis: {
                tickfont: { color: isDark ? '#94a3b8' : '#64748b', size: 10 },
                gridcolor: isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)'
            }
        };

        const config = {
            responsive: true,
            displayModeBar: false
        };

        Plotly.newPlot(containerId, [trace], layout, config);

        // Wire click on container to open fullscreen modal
        container.onclick = () => {
            openFullscreenGraphModal(teamNumber, datasource, scopedMatch, resolvedTeam);
        };
    }

    // ── Fullscreen Graph Modal ──────────────────────────────────
    let activeModalResizeHandler = null;

    function openFullscreenGraphModal(teamNumber, initialDatasource = "scouted", scopedMatch = null, team = null) {
        const modal = document.getElementById("graph-fullscreen-modal");
        if (!modal) return;

        // If the workspace is currently in HTML5 Fullscreen mode, ensure modal is inside it so it renders on top
        const fsEl = document.fullscreenElement || document.webkitFullscreenElement;
        if (fsEl && !fsEl.contains(modal)) {
            fsEl.appendChild(modal);
        }

        const resolvedTeam = team || allTeams.find(t => t.teamNumber === teamNumber) || { teamNumber };
        const teamScoped = scopedMatch || getScoutingEntriesForTeam(teamNumber).match;
        const teamNick = resolvedTeam.nickname || resolvedTeam.name || "";

        const titleEl = document.getElementById("graph-fullscreen-title");
        const subTitleEl = document.getElementById("graph-fullscreen-subtitle");
        const pillsContainer = document.getElementById("graph-modal-datasource-pills");
        const chartContainer = document.getElementById("graph-fullscreen-plot");

        if (titleEl) {
            titleEl.textContent = `Team #${teamNumber} ${teamNick ? '— ' + teamNick : ''}`;
        }
        if (subTitleEl) {
            subTitleEl.textContent = `Performance Progression Across Matches`;
        }

        const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function')
            ? Obsidianscout.getProgram() === "FTC"
            : (state.settings?.program === "FTC");
        const effectiveUseEpa = !isFtc && state.settings?.useStatboticsEpa;
        const effectiveUseExp = !isFtc && state.settings?.useMatch13Exp;

        let currentModalSource = initialDatasource;

        const renderModalPlot = (source) => {
            currentModalSource = source;
            teamGraphDatasource[teamNumber] = source;

            // Clear modal plot container first
            if (chartContainer && window.Plotly) {
                try {
                    Plotly.purge(chartContainer);
                } catch (e) {}
            }
            if (chartContainer) {
                chartContainer.innerHTML = "";
            }

            // Update modal pill buttons
            if (pillsContainer) {
                pillsContainer.innerHTML = `
                    <button type="button" class="graph-datasource-btn ${source === 'scouted' ? 'active' : ''}" data-source="scouted">Scouted</button>
                    ${effectiveUseEpa ? `<button type="button" class="graph-datasource-btn ${source === 'statbotics' ? 'active' : ''}" data-source="statbotics">Statbotics</button>` : ''}
                    ${effectiveUseExp ? `<button type="button" class="graph-datasource-btn ${source === 'match13' ? 'active' : ''}" data-source="match13">Match 13</button>` : ''}
                `;
                pillsContainer.querySelectorAll(".graph-datasource-btn").forEach(btn => {
                    btn.addEventListener("click", () => {
                        const s = btn.getAttribute("data-source");
                        renderModalPlot(s);
                        // Also sync card graph if present
                        renderPerformanceGraphInline(`graph-container-team-${teamNumber}`, teamNumber, teamScoped, s, resolvedTeam);
                        const cardPills = document.getElementById(`datasource-pills-team-${teamNumber}`);
                        if (cardPills) {
                            cardPills.querySelectorAll(".graph-datasource-btn").forEach(cb => {
                                cb.classList.toggle("active", cb.getAttribute("data-source") === s);
                            });
                        }
                    });
                });
            }

            const dataSeries = getTeamGraphData(teamNumber, source, teamScoped);
            const isDark = document.body.classList.contains("theme-dark");

            if (!dataSeries.x || dataSeries.x.length === 0) {
                chartContainer.innerHTML = `
                    <div style="height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--muted); font-size: 1rem; font-style: italic; gap: 8px;">
                        <i class="fa-solid fa-chart-line" style="font-size: 2.2rem; opacity: 0.35;"></i>
                        <span>No ${dataSeries.label} data recorded for Team #${teamNumber} at this event.</span>
                    </div>
                `;
                return;
            }

            const trace = {
                x: dataSeries.x,
                y: dataSeries.y,
                mode: 'lines+markers',
                type: 'scatter',
                name: dataSeries.name,
                line: { color: dataSeries.color, width: 3.5, shape: 'spline' },
                marker: { size: 9, color: dataSeries.color, line: { color: isDark ? '#1e293b' : '#ffffff', width: 2 } },
                hovertemplate: `<b>%{x}</b><br>${dataSeries.label}: <b>%{y:.2f}</b> ${dataSeries.unit}<extra></extra>`
            };

            const layout = {
                autosize: true,
                margin: { t: 40, r: 40, l: 60, b: 60 },
                paper_bgcolor: 'transparent',
                plot_bgcolor: 'transparent',
                showlegend: false,
                title: {
                    text: `${dataSeries.label} Progression`,
                    font: { size: 16, color: isDark ? '#f8fafc' : '#0f172a', family: 'inherit' }
                },
                xaxis: {
                    title: { text: 'Match', font: { size: 13, color: isDark ? '#94a3b8' : '#64748b' } },
                    tickfont: { color: isDark ? '#94a3b8' : '#64748b', size: 12 },
                    gridcolor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)'
                },
                yaxis: {
                    title: { text: `${dataSeries.label} (${dataSeries.unit})`, font: { size: 13, color: isDark ? '#94a3b8' : '#64748b' } },
                    tickfont: { color: isDark ? '#94a3b8' : '#64748b', size: 12 },
                    gridcolor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)'
                }
            };

            const config = {
                responsive: true,
                displayModeBar: true,
                displaylogo: false,
                modeBarButtonsToRemove: ['lasso2d', 'select2d']
            };

            Plotly.newPlot(chartContainer, [trace], layout, config).then(() => {
                Plotly.Plots.resize(chartContainer);
            });
        };

        modal.classList.remove("hidden");
        document.body.classList.add("modal-open");

        requestAnimationFrame(() => {
            renderModalPlot(currentModalSource);
        });

        const closeModal = () => {
            modal.classList.add("hidden");
            document.body.classList.remove("modal-open");
            if (chartContainer && window.Plotly) {
                Plotly.purge(chartContainer);
            }
            if (activeModalResizeHandler) {
                window.removeEventListener("resize", activeModalResizeHandler);
                activeModalResizeHandler = null;
            }
        };

        const closeBtn = modal.querySelector(".graphs-fullscreen-close");
        const backdrop = modal.querySelector(".graphs-fullscreen-backdrop");

        if (closeBtn) closeBtn.onclick = closeModal;
        if (backdrop) backdrop.onclick = closeModal;

        const escHandler = (e) => {
            if (e.key === "Escape" && !modal.classList.contains("hidden")) {
                closeModal();
                document.removeEventListener("keydown", escHandler);
            }
        };
        document.addEventListener("keydown", escHandler);

        if (activeModalResizeHandler) {
            window.removeEventListener("resize", activeModalResizeHandler);
        }
        activeModalResizeHandler = () => {
            if (!modal.classList.contains("hidden") && chartContainer && window.Plotly) {
                Plotly.Plots.resize(chartContainer);
            }
        };
        window.addEventListener("resize", activeModalResizeHandler);
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
                if (value === true || value === "true" || value === 1) {
                    score += (field.pointsPer || 0);
                }
            } else if (type === "select" || type === "radio") {
                if (field.options) {
                    const opt = field.options.find(o => o.value === value || o.label === value);
                    if (opt && opt.points) {
                        score += opt.points;
                    }
                }
            }
        });
        return score;
    }

    function formatDateString(isoString) {
        if (!isoString) return "";
        try {
            const d = new Date(isoString);
            return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours()}:${d.getMinutes().toString().padStart(2, '0')}`;
        } catch (e) {
            return isoString;
        }
    }

})();

