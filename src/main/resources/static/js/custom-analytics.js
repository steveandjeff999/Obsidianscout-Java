/**
 * ObsidianScout Custom Analytics Engine
 * An analytical dashboard builder for FRC/FTC scouting teams.
 */

(function () {
    // Current Global BI Studio State
    let dataset = {
        fields: [],
        matchEntries: [],
        pitEntries: [],
        qualEntries: [],
        teams: [],
        calculatedFields: []
    };

    let savedReports = [];
    let activeCrossFilterTeam = null;
    let editingWidgetIndex = -1;
    let kioskTimer = null;

    let currentReport = {
        id: null,
        title: "Match Strategy & Performance",
        category: "Strategy",
        description: "Interactive multi-metric performance dashboard",
        isShared: false,
        isDefault: false,
        slicers: {
            eventKey: "",
            teamNumbers: [],
            practice: true,
            quals: true,
            playoffs: true,
            includePrescout: true
        },
        calculatedMetrics: [],
        widgets: getDefaultWidgets()
    };

    function getDefaultWidgets() {
        return [
            {
                id: "w_kpi_total",
                type: "kpi",
                title: "Event Scoring Average",
                subtitle: "Mean Total Points per Match",
                width: "col-3",
                dimension: "teamNumber",
                measure: "calc_total_score",
                aggregation: "avg",
                secondaryMeasures: [],
                palette: "obsidian",
                sort: "val_desc",
                topN: 0
            },
            {
                id: "w_kpi_auto",
                type: "kpi",
                title: "Autonomous Average",
                subtitle: "Mean Auto Score",
                width: "col-3",
                dimension: "teamNumber",
                measure: "calc_auto_score",
                aggregation: "avg",
                secondaryMeasures: [],
                palette: "emerald",
                sort: "val_desc",
                topN: 0
            },
            {
                id: "w_kpi_teleop",
                type: "kpi",
                title: "Teleop Average",
                subtitle: "Mean Teleop Score",
                width: "col-3",
                dimension: "teamNumber",
                measure: "calc_teleop_score",
                aggregation: "avg",
                secondaryMeasures: [],
                palette: "sunset",
                sort: "val_desc",
                topN: 0
            },
            {
                id: "w_kpi_matches",
                type: "kpi",
                title: "Total Matches Scouted",
                subtitle: "Scouting Sample Size",
                width: "col-3",
                dimension: "teamNumber",
                measure: "matchNumber",
                aggregation: "count",
                secondaryMeasures: [],
                palette: "cyberpunk",
                sort: "val_desc",
                topN: 0
            },
            {
                id: "w_stacked_scoring",
                type: "stacked_bar",
                title: "Points Breakdown by Game Phase",
                subtitle: "Auto vs Teleop Points per Team",
                width: "col-8",
                dimension: "teamNumber",
                measure: "calc_auto_score",
                aggregation: "avg",
                secondaryMeasures: ["calc_teleop_score"],
                palette: "obsidian",
                sort: "val_desc",
                topN: 16
            },
            {
                id: "w_phase_donut",
                type: "donut",
                title: "Overall Points Distribution",
                subtitle: "Auto vs Teleop Proportion",
                width: "col-4",
                dimension: "teamNumber",
                measure: "calc_auto_score",
                aggregation: "sum",
                secondaryMeasures: ["calc_teleop_score"],
                palette: "sunset",
                sort: "val_desc",
                topN: 0
            },
            {
                id: "w_scatter_auto_tele",
                type: "scatter",
                title: "Auto vs Teleop Correlation",
                subtitle: "X: Auto Score, Y: Teleop Score",
                width: "col-6",
                dimension: "teamNumber",
                measure: "calc_auto_score",
                secondaryMeasures: ["calc_teleop_score"],
                aggregation: "avg",
                palette: "emerald",
                sort: "dim_asc",
                topN: 0
            },
            {
                id: "w_box_consistency",
                type: "box",
                title: "Match Score Variance & Outliers",
                subtitle: "Box & Whisker Distribution",
                width: "col-6",
                dimension: "teamNumber",
                measure: "calc_total_score",
                aggregation: "avg",
                palette: "cyberpunk",
                sort: "val_desc",
                topN: 12
            },
            {
                id: "w_ranking_matrix",
                type: "matrix",
                title: "Comprehensive Team Performance Matrix",
                subtitle: "Click columns to sort or filter",
                width: "col-12",
                dimension: "teamNumber",
                measure: "calc_total_score",
                aggregation: "avg",
                secondaryMeasures: ["calc_auto_score", "calc_teleop_score"],
                palette: "obsidian",
                sort: "val_desc",
                topN: 0
            }
        ];
    }

    // Color Palettes for Plotly
    const PALETTES = {
        obsidian: ["#2563eb", "#38bdf8", "#7c3aed", "#10b981", "#f59e0b", "#ef4444", "#ec4899", "#8b5cf6"],
        emerald: ["#10b981", "#059669", "#34d399", "#065f46", "#047857", "#6ee7b7", "#022c22", "#a7f3d0"],
        sunset: ["#f97316", "#ef4444", "#ec4899", "#f59e0b", "#fbbf24", "#db2777", "#ea580c", "#c2410c"],
        cyberpunk: ["#00f5d4", "#7b2cbf", "#f72585", "#4cc9f0", "#7209b7", "#3a0ca3", "#4361ee", "#4895ef"],
        alliance: ["#2563eb", "#ef4444", "#38bdf8", "#f87171", "#1d4ed8", "#b91c1c"]
    };

    document.addEventListener("DOMContentLoaded", async () => {
        if (window.Obsidianscout && typeof Obsidianscout.initTheme === "function") {
            Obsidianscout.initTheme();
        }

        const me = await (window.Obsidianscout && Obsidianscout.requireAuth ? Obsidianscout.requireAuth() : null);
        if (window.Obsidianscout) {
            Obsidianscout.setUserBadge(me);
            Obsidianscout.setActiveNav();
            Obsidianscout.adjustNavForRole(me);
            Obsidianscout.wireLogout();
            Obsidianscout.wireThemeToggle();
        }

        bindToolbarButtons();
        bindSlicerControls();
        bindEditorModals();

        await loadInitialData();
    });

    async function loadInitialData() {
        const grid = document.getElementById("bi-canvas-grid");
        if (grid) {
            grid.innerHTML = `<div class="bi-empty-canvas"><p class="notice">Loading Scouting Dataset & BI Studio...</p></div>`;
        }

        try {
            // Load reports list
            await fetchReportsList();

            // Load analytical dataset
            await fetchDataset();

            // Check if there is a default report to load
            const defaultRep = savedReports.find(r => r.isDefault);
            if (defaultRep) {
                try {
                    const parsed = JSON.parse(defaultRep.configJson);
                    currentReport = { ...parsed, id: defaultRep.id, title: defaultRep.title, category: defaultRep.category, isDefault: true };
                } catch (e) {
                    console.warn("Failed to parse default report JSON:", e);
                }
            }

            updateReportHeaderUI();
            populateSlicersUI();
            renderDashboard();
        } catch (err) {
            console.error("Failed to initialize BI Studio:", err);
            if (window.Obsidianscout && Obsidianscout.showToast) {
                Obsidianscout.showToast("Failed to load dataset: " + err.message, "error");
            }
        }
    }

    async function fetchReportsList() {
        try {
            const data = await Obsidianscout.request("/api/custom-analytics/reports");
            savedReports = Array.isArray(data) ? data : [];
        } catch (e) {
            console.warn("Failed to fetch reports list:", e);
            savedReports = [];
        }
    }

    async function fetchDataset(eventKey = null) {
        let url = "/api/custom-analytics/dataset";
        const params = [];
        if (eventKey) params.push(`eventKey=${encodeURIComponent(eventKey)}`);
        if (params.length) url += `?${params.join("&")}`;

        const data = await Obsidianscout.request(url);
        dataset = data;
        applyCalculatedMetrics();
    }

    function applyCalculatedMetrics() {
        if (!currentReport.calculatedMetrics || !Array.isArray(currentReport.calculatedMetrics)) return;

        currentReport.calculatedMetrics.forEach(metric => {
            if (!dataset.fields.some(f => f.id === metric.id)) {
                dataset.fields.push({
                    id: metric.id,
                    label: `Calc: ${metric.name}`,
                    type: "number",
                    source: "calculated",
                    section: "Custom Calculated"
                });
            }

            // Compute metric values across matchEntries
            dataset.matchEntries.forEach(entry => {
                try {
                    const formula = metric.formula.replace(/\[([a-zA-Z0-9_]+)\]/g, (_, fieldId) => {
                        const val = entry[fieldId];
                        return (val !== undefined && val !== null) ? Number(val) || 0 : 0;
                    });
                    // Safe evaluation
                    const result = Function(`"use strict"; return (${formula})`)();
                    entry[metric.id] = (typeof result === "number" && !isNaN(result) && isFinite(result)) ? result : 0;
                } catch (err) {
                    entry[metric.id] = 0;
                }
            });
        });
    }

    function bindToolbarButtons() {
        // Toggle Slicers
        const btnToggleSlicers = document.getElementById("btn-toggle-slicers");
        const slicersCard = document.getElementById("bi-slicers-card");
        if (btnToggleSlicers && slicersCard) {
            btnToggleSlicers.addEventListener("click", () => {
                slicersCard.classList.toggle("collapsed");
            });
        }

        // Add Visual
        const btnAddWidget = document.getElementById("btn-add-widget");
        if (btnAddWidget) {
            btnAddWidget.addEventListener("click", () => {
                openWidgetEditor(-1);
            });
        }

        // Save Report
        const btnSaveReport = document.getElementById("btn-save-report");
        if (btnSaveReport) {
            btnSaveReport.addEventListener("click", () => {
                openReportLibraryModal(true);
            });
        }

        // Report Library Modal
        const btnReportLibrary = document.getElementById("btn-report-library");
        if (btnReportLibrary) {
            btnReportLibrary.addEventListener("click", () => {
                openReportLibraryModal(false);
            });
        }

        // Template Library Modal
        const btnOpenTemplates = document.getElementById("btn-open-templates");
        if (btnOpenTemplates) {
            btnOpenTemplates.addEventListener("click", () => {
                openTemplatesModal();
            });
        }

        // Calculated Metric Modal
        const btnCalcMetric = document.getElementById("btn-calc-metric");
        if (btnCalcMetric) {
            btnCalcMetric.addEventListener("click", () => {
                openCalculatedMetricModal();
            });
        }

        // Report Title click to rename
        const reportTitleDisplay = document.getElementById("report-title-display");
        if (reportTitleDisplay) {
            reportTitleDisplay.addEventListener("click", () => {
                const newTitle = prompt("Enter new report title:", currentReport.title);
                if (newTitle && newTitle.trim()) {
                    currentReport.title = newTitle.trim();
                    updateReportHeaderUI();
                }
            });
        }

        // Clear Cross-Filter Button
        const btnClearCross = document.getElementById("btn-clear-cross-filter");
        if (btnClearCross) {
            btnClearCross.addEventListener("click", () => {
                activeCrossFilterTeam = null;
                updateCrossFilterBanner();
                renderDashboard();
            });
        }

        // Kiosk Mode Toggle
        const btnKiosk = document.getElementById("btn-kiosk-mode");
        if (btnKiosk) {
            btnKiosk.addEventListener("click", () => {
                toggleKioskMode();
            });
        }

        // Export Dropdown Menu
        const btnExportDrop = document.getElementById("btn-export-dropdown");
        const exportMenu = document.getElementById("export-dropdown-menu");
        if (btnExportDrop && exportMenu) {
            btnExportDrop.addEventListener("click", (e) => {
                e.stopPropagation();
                const rect = btnExportDrop.getBoundingClientRect();
                exportMenu.style.top = `${rect.bottom + window.scrollY + 4}px`;
                exportMenu.style.left = `${rect.left + window.scrollX - 40}px`;
                exportMenu.style.display = exportMenu.style.display === "none" ? "block" : "none";
            });

            document.addEventListener("click", () => {
                exportMenu.style.display = "none";
            });
        }

        // Export Actions
        const btnExportCsv = document.getElementById("btn-export-csv");
        if (btnExportCsv) {
            btnExportCsv.addEventListener("click", () => exportAggregatedCsv());
        }

        const btnExportJson = document.getElementById("btn-export-json");
        if (btnExportJson) {
            btnExportJson.addEventListener("click", () => exportReportJson());
        }
    }

    function toggleKioskMode() {
        const isKiosk = document.body.classList.toggle("bi-kiosk-mode");
        const btnKiosk = document.getElementById("btn-kiosk-mode");
        if (isKiosk) {
            if (btnKiosk) btnKiosk.innerHTML = `<span class="bi-kiosk-pulse"></span> Exit Kiosk`;
            // Auto refresh every 45 seconds
            kioskTimer = setInterval(async () => {
                await fetchDataset(currentReport.slicers.eventKey);
                renderDashboard();
            }, 45000);
            if (document.documentElement.requestFullscreen) {
                document.documentElement.requestFullscreen().catch(() => {});
            }
        } else {
            if (btnKiosk) btnKiosk.textContent = "📺 Kiosk";
            if (kioskTimer) clearInterval(kioskTimer);
            if (document.exitFullscreen) {
                document.exitFullscreen().catch(() => {});
            }
        }
    }

    function updateReportHeaderUI() {
        const titleEl = document.getElementById("report-title-display");
        const catEl = document.getElementById("report-category-display");
        if (titleEl) titleEl.textContent = currentReport.title || "Untitled Dashboard";
        if (catEl) catEl.textContent = currentReport.category || "General";
    }

    function updateCrossFilterBanner() {
        const banner = document.getElementById("cross-filter-banner");
        const textEl = document.getElementById("cross-filter-text");
        if (!banner) return;
        if (activeCrossFilterTeam !== null) {
            banner.style.display = "flex";
            if (textEl) textEl.textContent = `📌 Interactive Cross-Filter Active: Team ${activeCrossFilterTeam}`;
        } else {
            banner.style.display = "none";
        }
    }

    function bindSlicerControls() {
        const eventSelect = document.getElementById("slicer-event");
        if (eventSelect) {
            eventSelect.addEventListener("change", async (e) => {
                currentReport.slicers.eventKey = e.target.value;
                await fetchDataset(e.target.value);
                populateTeamSlicerPills();
                renderDashboard();
            });
        }

        const chkPractice = document.getElementById("slicer-practice");
        if (chkPractice) {
            chkPractice.addEventListener("change", (e) => {
                currentReport.slicers.practice = e.target.checked;
                renderDashboard();
            });
        }

        const chkQuals = document.getElementById("slicer-quals");
        if (chkQuals) {
            chkQuals.addEventListener("change", (e) => {
                currentReport.slicers.quals = e.target.checked;
                renderDashboard();
            });
        }

        const chkPlayoffs = document.getElementById("slicer-playoffs");
        if (chkPlayoffs) {
            chkPlayoffs.addEventListener("change", (e) => {
                currentReport.slicers.playoffs = e.target.checked;
                renderDashboard();
            });
        }

        const chkPrescout = document.getElementById("slicer-prescout");
        if (chkPrescout) {
            chkPrescout.addEventListener("change", (e) => {
                currentReport.slicers.includePrescout = e.target.checked;
                renderDashboard();
            });
        }

        const teamSearch = document.getElementById("slicer-team-search");
        if (teamSearch) {
            teamSearch.addEventListener("input", () => populateTeamSlicerPills());
        }

        const btnTeamAll = document.getElementById("slicer-team-all");
        if (btnTeamAll) {
            btnTeamAll.addEventListener("click", () => {
                const allTeams = [...new Set([
                    ...dataset.matchEntries.map(m => m.teamNumber),
                    ...dataset.teams.map(t => t.teamNumber)
                ])].filter(Boolean).sort((a, b) => a - b);
                currentReport.slicers.teamNumbers = allTeams;
                populateTeamSlicerPills();
                renderDashboard();
            });
        }

        const btnTeamTop8 = document.getElementById("slicer-team-top8");
        if (btnTeamTop8) {
            btnTeamTop8.addEventListener("click", () => {
                const uniqueTeams = getSortedTeamNumbersByAvgScore().slice(0, 8);
                currentReport.slicers.teamNumbers = uniqueTeams;
                populateTeamSlicerPills();
                renderDashboard();
            });
        }

        const btnTeamClear = document.getElementById("slicer-team-clear");
        if (btnTeamClear) {
            btnTeamClear.addEventListener("click", () => {
                currentReport.slicers.teamNumbers = [];
                populateTeamSlicerPills();
                renderDashboard();
            });
        }
    }

    function populateSlicersUI() {
        const eventSelect = document.getElementById("slicer-event");
        if (eventSelect) {
            const uniqueEvents = [...new Set(dataset.matchEntries.map(m => m.eventKey).filter(Boolean))];
            eventSelect.innerHTML = `<option value="">All Loaded Events</option>`;
            uniqueEvents.forEach(ev => {
                const opt = document.createElement("option");
                opt.value = ev;
                opt.textContent = ev.toUpperCase();
                eventSelect.appendChild(opt);
            });
            eventSelect.value = currentReport.slicers.eventKey || "";
        }

        const chkPractice = document.getElementById("slicer-practice");
        if (chkPractice) chkPractice.checked = currentReport.slicers.practice !== false;

        const chkQuals = document.getElementById("slicer-quals");
        if (chkQuals) chkQuals.checked = currentReport.slicers.quals !== false;

        const chkPlayoffs = document.getElementById("slicer-playoffs");
        if (chkPlayoffs) chkPlayoffs.checked = currentReport.slicers.playoffs !== false;

        const chkPrescout = document.getElementById("slicer-prescout");
        if (chkPrescout) chkPrescout.checked = currentReport.slicers.includePrescout !== false;

        populateTeamSlicerPills();
    }

    function getSortedTeamNumbersByAvgScore() {
        const teamScores = {};
        dataset.matchEntries.forEach(entry => {
            const team = entry.teamNumber;
            if (!team) return;
            if (!teamScores[team]) teamScores[team] = { sum: 0, count: 0 };
            teamScores[team].sum += Number(entry.calc_total_score || 0);
            teamScores[team].count++;
        });
        return Object.keys(teamScores)
            .map(t => Number(t))
            .sort((a, b) => (teamScores[b].sum / teamScores[b].count) - (teamScores[a].sum / teamScores[a].count));
    }

    function populateTeamSlicerPills() {
        const container = document.getElementById("slicer-team-pills");
        const searchInput = document.getElementById("slicer-team-search");
        if (!container) return;

        const query = searchInput ? searchInput.value.trim().toLowerCase() : "";
        const allTeams = [...new Set([
            ...dataset.matchEntries.map(m => m.teamNumber),
            ...dataset.teams.map(t => t.teamNumber)
        ])].filter(Boolean).sort((a, b) => a - b);

        const filtered = query
            ? allTeams.filter(t => t.toString().includes(query))
            : allTeams;

        container.innerHTML = "";
        filtered.forEach(team => {
            const isSelected = currentReport.slicers.teamNumbers.includes(team);
            const pill = document.createElement("span");
            pill.className = `bi-team-pill ${isSelected ? "active" : ""}`;
            pill.textContent = team;
            pill.addEventListener("click", () => {
                if (isSelected) {
                    currentReport.slicers.teamNumbers = currentReport.slicers.teamNumbers.filter(t => t !== team);
                } else {
                    currentReport.slicers.teamNumbers.push(team);
                }
                populateTeamSlicerPills();
                renderDashboard();
            });
            container.appendChild(pill);
        });
    }

    /**
     * Filters the raw match entries according to global slicers + interactive cross-filter
     */
    function getFilteredEntries() {
        let entries = dataset.matchEntries || [];

        // Slicer: Event Key
        if (currentReport.slicers.eventKey) {
            entries = entries.filter(e => e.eventKey === currentReport.slicers.eventKey);
        }

        // Slicer: Include Prescout
        if (!currentReport.slicers.includePrescout) {
            entries = entries.filter(e => !e.isPrescout);
        }

        // Slicer: Match scope (practice, quals, playoffs)
        entries = entries.filter(e => {
            const mKey = (e.matchKey || "").toLowerCase();
            const isPractice = mKey.includes("_practice") || mKey.includes("_pr") || mKey.includes("_pm");
            const isPlayoff = mKey.includes("_sf") || mKey.includes("_f") || mKey.includes("_qf") || mKey.includes("_ef");
            const isQual = !isPractice && !isPlayoff;

            if (isPractice && currentReport.slicers.practice === false) return false;
            if (isQual && currentReport.slicers.quals === false) return false;
            if (isPlayoff && currentReport.slicers.playoffs === false) return false;
            return true;
        });

        // Slicer: Selected Teams (if any specified)
        if (currentReport.slicers.teamNumbers && currentReport.slicers.teamNumbers.length > 0) {
            entries = entries.filter(e => currentReport.slicers.teamNumbers.includes(e.teamNumber));
        }

        // Interactive Cross-Filter
        if (activeCrossFilterTeam !== null) {
            entries = entries.filter(e => e.teamNumber === activeCrossFilterTeam);
        }

        return entries;
    }

    /**
     * Main Dashboard Render Pipeline
     */
    function renderDashboard() {
        const grid = document.getElementById("bi-canvas-grid");
        if (!grid) return;

        grid.innerHTML = "";

        if (!currentReport.widgets || currentReport.widgets.length === 0) {
            grid.innerHTML = `
                <div class="bi-empty-canvas">
                    <div class="bi-empty-icon">
                        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--muted)" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 16V10M12 16V6M17 16v-4"/></svg>
                    </div>
                    <h2>Your Dashboard is Empty</h2>
                    <p class="notice">Click "Add Visual" to add charts or pick a starter template below:</p>
                    <button class="btn mt-16" id="btn-empty-templates">Choose a Starter Template</button>
                </div>
            `;
            const btnT = document.getElementById("btn-empty-templates");
            if (btnT) btnT.addEventListener("click", () => openTemplatesModal());
            return;
        }

        const entries = getFilteredEntries();

        currentReport.widgets.forEach((widget, index) => {
            const card = createWidgetCard(widget, index, entries);
            grid.appendChild(card);
            renderWidgetContent(widget, card.querySelector(".bi-plot-container"), entries);
        });
        if (window.Obsidianscout && typeof Obsidianscout.localize === "function") {
            Obsidianscout.localize();
        }
    }

    function createWidgetCard(widget, index, entries) {
        const card = document.createElement("div");
        card.className = `bi-widget-card ${widget.width || "col-6"}`;
        card.id = `widget-card-${index}`;
        card.setAttribute("draggable", "true");

        card.innerHTML = `
            <div class="bi-widget-header">
                <div class="row items-center gap-6" style="flex: 1; min-width: 0;">
                    <span class="bi-drag-handle" title="Drag to reorder card">⋮⋮</span>
                    <div class="bi-widget-title-area">
                        <h3 class="bi-widget-title" title="${escapeHtml(widget.title)}">${escapeHtml(widget.title)}</h3>
                        ${widget.subtitle ? `<p class="bi-widget-subtitle">${escapeHtml(widget.subtitle)}</p>` : ""}
                    </div>
                </div>
                <div class="bi-widget-actions">
                    <button class="bi-widget-btn" data-action="move-left" title="Move Left / Up" ${index === 0 ? "disabled style='opacity:0.3; cursor:not-allowed;'" : ""}>‹</button>
                    <button class="bi-widget-btn" data-action="move-right" title="Move Right / Down" ${index === currentReport.widgets.length - 1 ? "disabled style='opacity:0.3; cursor:not-allowed;'" : ""}>›</button>
                    <select class="bi-quick-width" data-action="quick-width" title="Change Card Width">
                        <option value="col-3" ${widget.width === 'col-3' ? 'selected' : ''}>1/4</option>
                        <option value="col-4" ${widget.width === 'col-4' ? 'selected' : ''}>1/3</option>
                        <option value="col-6" ${(!widget.width || widget.width === 'col-6') ? 'selected' : ''}>1/2</option>
                        <option value="col-8" ${widget.width === 'col-8' ? 'selected' : ''}>2/3</option>
                        <option value="col-12" ${widget.width === 'col-12' ? 'selected' : ''}>Full</option>
                    </select>
                    <button class="bi-widget-btn" data-action="edit" title="Edit Visual Settings">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
                    </button>
                    <button class="bi-widget-btn" data-action="duplicate" title="Duplicate Visual">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    </button>
                    <button class="bi-widget-btn" data-action="delete" title="Delete Visual">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                    </button>
                </div>
            </div>
            <div class="bi-widget-body">
                <div class="bi-plot-container" id="plot-${widget.id || index}"></div>
            </div>
        `;

        // Bind Drag and Drop Events for ultra-easy reordering
        card.addEventListener("dragstart", (e) => {
            e.dataTransfer.setData("text/plain", index.toString());
            card.classList.add("dragging");
        });
        card.addEventListener("dragend", () => {
            card.classList.remove("dragging");
            document.querySelectorAll(".bi-widget-card").forEach(c => c.classList.remove("drag-over"));
        });
        card.addEventListener("dragover", (e) => {
            e.preventDefault();
            card.classList.add("drag-over");
        });
        card.addEventListener("dragleave", () => {
            card.classList.remove("drag-over");
        });
        card.addEventListener("drop", (e) => {
            e.preventDefault();
            card.classList.remove("drag-over");
            const fromIndex = parseInt(e.dataTransfer.getData("text/plain"), 10);
            const toIndex = index;
            if (!isNaN(fromIndex) && fromIndex !== toIndex) {
                const moved = currentReport.widgets.splice(fromIndex, 1)[0];
                currentReport.widgets.splice(toIndex, 0, moved);
                renderDashboard();
            }
        });

        // Bind Action Buttons
        const btnMoveLeft = card.querySelector("[data-action='move-left']");
        if (btnMoveLeft && index > 0) {
            btnMoveLeft.addEventListener("click", () => moveWidget(index, -1));
        }

        const btnMoveRight = card.querySelector("[data-action='move-right']");
        if (btnMoveRight && index < currentReport.widgets.length - 1) {
            btnMoveRight.addEventListener("click", () => moveWidget(index, 1));
        }

        const widthSelect = card.querySelector("[data-action='quick-width']");
        if (widthSelect) {
            widthSelect.addEventListener("change", (e) => {
                widget.width = e.target.value;
                renderDashboard();
            });
        }

        card.querySelector("[data-action='edit']").addEventListener("click", () => openWidgetEditor(index));
        card.querySelector("[data-action='duplicate']").addEventListener("click", () => duplicateWidget(index));
        card.querySelector("[data-action='delete']").addEventListener("click", () => deleteWidget(index));

        return card;
    }

    function moveWidget(index, direction) {
        const target = index + direction;
        if (target < 0 || target >= currentReport.widgets.length) return;
        const temp = currentReport.widgets[index];
        currentReport.widgets[index] = currentReport.widgets[target];
        currentReport.widgets[target] = temp;
        renderDashboard();
    }

    function duplicateWidget(index) {
        const original = currentReport.widgets[index];
        const copy = JSON.parse(JSON.stringify(original));
        copy.id = "w_" + Date.now();
        copy.title = `${original.title} (Copy)`;
        currentReport.widgets.splice(index + 1, 0, copy);
        renderDashboard();
    }

    function toggleWidgetWidth(index) {
        const widget = currentReport.widgets[index];
        if (widget.width === "col-12") {
            widget.width = "col-6";
        } else {
            widget.width = "col-12";
        }
        renderDashboard();
    }

    function deleteWidget(index) {
        if (confirm("Remove this visual from the dashboard?")) {
            currentReport.widgets.splice(index, 1);
            renderDashboard();
        }
    }

    /**
     * Visual Rendering Engine
     */
    function renderWidgetContent(widget, container, entries) {
        if (!container) return;

        const isDark = document.body.classList.contains("theme-dark");
        const colors = PALETTES[widget.palette] || PALETTES.obsidian;

        const layoutTheme = {
            paper_bgcolor: "rgba(0,0,0,0)",
            plot_bgcolor: "rgba(0,0,0,0)",
            font: {
                color: isDark ? "#f8fafc" : "#0f172a",
                family: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
            },
            margin: { t: 20, r: 20, l: 40, b: 40 },
            autosize: true,
            showlegend: widget.type !== "kpi" && widget.type !== "histogram",
            legend: {
                orientation: "h",
                y: -0.2,
                x: 0
            }
        };

        const config = {
            responsive: true,
            displayModeBar: true,
            displaylogo: false,
            modeBarButtonsToRemove: ["lasso2d", "select2d"]
        };

        if (entries.length === 0 && widget.type !== "kpi") {
            container.innerHTML = `<div class="row items-center justify-center h-100" style="height: 100%; color: var(--muted); font-size: 13px;">No data matching current filters</div>`;
            return;
        }

        switch (widget.type) {
            case "kpi":
                renderKpiWidget(widget, container, entries);
                break;
            case "matrix":
                renderMatrixWidget(widget, container, entries);
                break;
            case "bar":
            case "stacked_bar":
                renderBarWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            case "line":
                renderLineWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            case "scatter":
                renderScatterWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            case "box":
                renderBoxWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            case "violin":
                renderViolinWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            case "radar":
                renderRadarWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            case "donut":
                renderDonutWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            case "heatmap":
                renderHeatmapWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            case "histogram":
                renderHistogramWidget(widget, container, entries, layoutTheme, config, colors);
                break;
            default:
                renderBarWidget(widget, container, entries, layoutTheme, config, colors);
        }
    }

    // KPI Metric Card
    function renderKpiWidget(widget, container, entries) {
        const measure = widget.measure || "calc_total_score";
        const values = entries.map(e => Number(e[measure]) || 0);
        let calculatedVal = 0;

        if (values.length > 0) {
            calculatedVal = computeAggregation(values, widget.aggregation || "avg");
        }

        const formatted = Number.isInteger(calculatedVal) ? calculatedVal : calculatedVal.toFixed(1);
        const label = getFieldLabel(measure);

        // Calculate comparison delta vs all entries
        const allValues = dataset.matchEntries.map(e => Number(e[measure]) || 0);
        const overallAvg = allValues.length ? computeAggregation(allValues, "avg") : 0;
        const delta = calculatedVal - overallAvg;
        const deltaFormatted = (delta >= 0 ? "+" : "") + delta.toFixed(1);

        container.innerHTML = `
            <div class="bi-kpi-wrapper">
                <div class="bi-kpi-metric-val">${formatted}</div>
                <div class="bi-kpi-subtext">${label} (${(widget.aggregation || "avg").toUpperCase()})</div>
                ${overallAvg > 0 ? `
                    <div class="bi-kpi-delta ${delta >= 0 ? "positive" : "negative"}">
                        ${delta >= 0 ? "▲" : "▼"} ${deltaFormatted} vs Event Mean (${overallAvg.toFixed(1)})
                    </div>
                ` : ""}
            </div>
        `;
    }

    // Matrix / Pivot Table
    function renderMatrixWidget(widget, container, entries) {
        const dim = widget.dimension || "teamNumber";
        const primaryMeasure = widget.measure || "calc_total_score";
        const secondaryMeasures = widget.secondaryMeasures || [];
        const allMeasures = [primaryMeasure, ...secondaryMeasures];

        // Group entries by dimension
        const groups = {};
        entries.forEach(entry => {
            const key = entry[dim] || "Unknown";
            if (!groups[key]) groups[key] = [];
            groups[key].push(entry);
        });

        const rows = Object.entries(groups).map(([dimVal, items]) => {
            const rowData = { dimension: dimVal, rawTeam: Number(dimVal) || 0 };
            allMeasures.forEach(m => {
                const vals = items.map(i => Number(i[m]) || 0);
                rowData[m] = computeAggregation(vals, widget.aggregation || "avg");
            });
            rowData.matchCount = items.length;
            return rowData;
        });

        // Sort rows
        rows.sort((a, b) => (b[primaryMeasure] || 0) - (a[primaryMeasure] || 0));

        let tableHtml = `
            <div class="bi-table-wrapper">
                <table class="bi-table">
                    <thead>
                        <tr>
                            <th>${getFieldLabel(dim)}</th>
                            <th>Matches</th>
                            ${allMeasures.map(m => `<th>${getFieldLabel(m)} (${(widget.aggregation || "avg").toUpperCase()})</th>`).join("")}
                        </tr>
                    </thead>
                    <tbody>
                        ${rows.map(r => `
                            <tr data-team="${r.rawTeam}" style="cursor: pointer;">
                                <td><strong>${r.dimension}</strong></td>
                                <td>${r.matchCount}</td>
                                ${allMeasures.map(m => {
                                    const val = r[m];
                                    const formatted = Number.isInteger(val) ? val : val.toFixed(1);
                                    return `<td><span class="bi-heat-cell">${formatted}</span></td>`;
                                }).join("")}
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        `;

        container.innerHTML = tableHtml;

        // Drill-down click
        container.querySelectorAll("tbody tr").forEach(tr => {
            tr.addEventListener("click", () => {
                const teamNum = Number(tr.dataset.team);
                if (teamNum) openDrillDownModal(teamNum);
            });
        });
    }

    // Bar / Stacked Bar Chart
    function renderBarWidget(widget, container, entries, layoutTheme, config, colors) {
        const dim = widget.dimension || "teamNumber";
        const primaryMeasure = widget.measure || "calc_total_score";
        const secondaryMeasures = widget.secondaryMeasures || [];
        const isStacked = widget.type === "stacked_bar";

        // Group entries
        const groups = {};
        entries.forEach(entry => {
            const key = entry[dim] || "Unknown";
            if (!groups[key]) groups[key] = [];
            groups[key].push(entry);
        });

        let groupKeys = Object.keys(groups);

        // Sorting
        groupKeys.sort((a, b) => {
            const aVal = computeAggregation(groups[a].map(e => Number(e[primaryMeasure]) || 0), widget.aggregation || "avg");
            const bVal = computeAggregation(groups[b].map(e => Number(e[primaryMeasure]) || 0), widget.aggregation || "avg");
            if (widget.sort === "val_asc") return aVal - bVal;
            if (widget.sort === "dim_asc") return Number(a) ? Number(a) - Number(b) : a.localeCompare(b);
            if (widget.sort === "dim_desc") return Number(b) ? Number(b) - Number(a) : b.localeCompare(a);
            return bVal - aVal; // default val_desc
        });

        if (widget.topN && widget.topN > 0) {
            groupKeys = groupKeys.slice(0, widget.topN);
        }

        const traces = [];
        const allMeasures = [primaryMeasure, ...secondaryMeasures];

        allMeasures.forEach((m, mIdx) => {
            const yValues = groupKeys.map(k => {
                const vals = groups[k].map(e => Number(e[m]) || 0);
                return Number(computeAggregation(vals, widget.aggregation || "avg").toFixed(2));
            });

            traces.push({
                x: groupKeys.map(k => `Team ${k}`),
                y: yValues,
                name: getFieldLabel(m),
                type: "bar",
                marker: {
                    color: colors[mIdx % colors.length]
                }
            });
        });

        const layout = {
            ...layoutTheme,
            barmode: isStacked ? "stack" : "group",
            xaxis: { title: getFieldLabel(dim), tickangle: -45 },
            yaxis: { title: `${getFieldLabel(primaryMeasure)} (${(widget.aggregation || "avg").toUpperCase()})` }
        };

        if (widget.targetLine && widget.targetLine > 0) {
            layout.shapes = [{
                type: "line",
                x0: 0,
                x1: 1,
                xref: "paper",
                y0: widget.targetLine,
                y1: widget.targetLine,
                line: { color: "#ef4444", width: 2, dash: "dot" }
            }];
        }

        Plotly.newPlot(container, traces, layout, config);

        // Click event for interactive cross-filtering
        container.on("plotly_click", (data) => {
            if (data && data.points && data.points[0]) {
                const label = data.points[0].x;
                const teamMatch = label.toString().match(/\d+/);
                if (teamMatch) {
                    const teamNum = Number(teamMatch[0]);
                    activeCrossFilterTeam = (activeCrossFilterTeam === teamNum) ? null : teamNum;
                    updateCrossFilterBanner();
                    renderDashboard();
                }
            }
        });
    }

    // Line / Trend Chart
    function renderLineWidget(widget, container, entries, layoutTheme, config, colors) {
        const dim = widget.dimension || "matchNumber";
        const primaryMeasure = widget.measure || "calc_total_score";

        // Group by team for multi-line comparison
        const teams = [...new Set(entries.map(e => e.teamNumber))].slice(0, 8);
        const traces = [];

        teams.forEach((team, tIdx) => {
            const teamEntries = entries.filter(e => e.teamNumber === team).sort((a, b) => (a[dim] || 0) - (b[dim] || 0));
            traces.push({
                x: teamEntries.map(e => `M${e[dim] || 0}`),
                y: teamEntries.map(e => Number(e[primaryMeasure]) || 0),
                name: `Team ${team}`,
                type: "scatter",
                mode: "lines+markers",
                line: { shape: "spline", color: colors[tIdx % colors.length], width: 2.5 },
                marker: { size: 6 }
            });
        });

        const layout = {
            ...layoutTheme,
            xaxis: { title: getFieldLabel(dim) },
            yaxis: { title: getFieldLabel(primaryMeasure) }
        };

        Plotly.newPlot(container, traces, layout, config);
    }

    // Scatter / Bubble Chart
    function renderScatterWidget(widget, container, entries, layoutTheme, config, colors) {
        const xMeasure = widget.measure || "calc_auto_score";
        const yMeasure = (widget.secondaryMeasures && widget.secondaryMeasures[0]) || "calc_teleop_score";

        // Aggregate by team
        const groups = {};
        entries.forEach(e => {
            const team = e.teamNumber || "Unknown";
            if (!groups[team]) groups[team] = { x: [], y: [] };
            groups[team].x.push(Number(e[xMeasure]) || 0);
            groups[team].y.push(Number(e[yMeasure]) || 0);
        });

        const teams = Object.keys(groups);
        const xVals = teams.map(t => computeAggregation(groups[t].x, "avg"));
        const yVals = teams.map(t => computeAggregation(groups[t].y, "avg"));

        const trace = {
            x: xVals,
            y: yVals,
            text: teams.map(t => `Team ${t}`),
            mode: "markers+text",
            textposition: "top center",
            type: "scatter",
            marker: {
                size: 12,
                color: colors[0],
                opacity: 0.85
            }
        };

        const layout = {
            ...layoutTheme,
            xaxis: { title: `X: ${getFieldLabel(xMeasure)} (AVG)` },
            yaxis: { title: `Y: ${getFieldLabel(yMeasure)} (AVG)` }
        };

        Plotly.newPlot(container, [trace], layout, config);

        container.on("plotly_click", (data) => {
            if (data && data.points && data.points[0]) {
                const label = data.points[0].text;
                const teamMatch = label.match(/\d+/);
                if (teamMatch) {
                    openDrillDownModal(Number(teamMatch[0]));
                }
            }
        });
    }

    // Box Plot
    function renderBoxWidget(widget, container, entries, layoutTheme, config, colors) {
        const dim = widget.dimension || "teamNumber";
        const measure = widget.measure || "calc_total_score";

        const groups = {};
        entries.forEach(e => {
            const key = e[dim] || "Unknown";
            if (!groups[key]) groups[key] = [];
            groups[key].push(Number(e[measure]) || 0);
        });

        let groupKeys = Object.keys(groups);
        if (widget.topN && widget.topN > 0) {
            groupKeys = groupKeys.slice(0, widget.topN);
        }

        const traces = groupKeys.map((k, idx) => ({
            y: groups[k],
            name: `Team ${k}`,
            type: "box",
            boxpoints: "all",
            jitter: 0.3,
            pointpos: -1.8,
            marker: { color: colors[idx % colors.length] }
        }));

        const layout = {
            ...layoutTheme,
            yaxis: { title: getFieldLabel(measure) }
        };

        Plotly.newPlot(container, traces, layout, config);
    }

    // Violin Plot
    function renderViolinWidget(widget, container, entries, layoutTheme, config, colors) {
        const dim = widget.dimension || "teamNumber";
        const measure = widget.measure || "calc_total_score";

        const groups = {};
        entries.forEach(e => {
            const key = e[dim] || "Unknown";
            if (!groups[key]) groups[key] = [];
            groups[key].push(Number(e[measure]) || 0);
        });

        let groupKeys = Object.keys(groups).slice(0, 10);

        const traces = groupKeys.map((k, idx) => ({
            y: groups[k],
            name: `Team ${k}`,
            type: "violin",
            points: "none",
            box: { visible: true },
            line: { color: colors[idx % colors.length] }
        }));

        const layout = {
            ...layoutTheme,
            yaxis: { title: getFieldLabel(measure) }
        };

        Plotly.newPlot(container, traces, layout, config);
    }

    // Radar / Spider Chart
    function renderRadarWidget(widget, container, entries, layoutTheme, config, colors) {
        const primaryMeasure = widget.measure || "calc_auto_score";
        const secondaryMeasures = widget.secondaryMeasures || ["calc_teleop_score", "calc_total_score"];
        const dimensions = [primaryMeasure, ...secondaryMeasures];

        const teams = [...new Set(entries.map(e => e.teamNumber))].slice(0, 5);
        const traces = [];

        teams.forEach((team, tIdx) => {
            const teamEntries = entries.filter(e => e.teamNumber === team);
            const values = dimensions.map(d => {
                const vals = teamEntries.map(e => Number(e[d]) || 0);
                return computeAggregation(vals, "avg");
            });
            // Close the loop
            values.push(values[0]);

            traces.push({
                type: "scatterpolar",
                r: values,
                theta: [...dimensions.map(d => getFieldLabel(d)), getFieldLabel(dimensions[0])],
                fill: "toself",
                name: `Team ${team}`,
                line: { color: colors[tIdx % colors.length] }
            });
        });

        const layout = {
            ...layoutTheme,
            polar: {
                radialaxis: { visible: true, range: [0, undefined] }
            }
        };

        Plotly.newPlot(container, traces, layout, config);
    }

    // Donut / Pie Chart
    function renderDonutWidget(widget, container, entries, layoutTheme, config, colors) {
        const primaryMeasure = widget.measure || "calc_auto_score";
        const secondaryMeasures = widget.secondaryMeasures || ["calc_teleop_score"];
        const measures = [primaryMeasure, ...secondaryMeasures];

        const totals = measures.map(m => {
            const vals = entries.map(e => Number(e[m]) || 0);
            return computeAggregation(vals, "sum");
        });

        const trace = {
            values: totals,
            labels: measures.map(m => getFieldLabel(m)),
            type: "pie",
            hole: 0.5,
            marker: { colors: colors }
        };

        const layout = {
            ...layoutTheme,
            showlegend: true
        };

        Plotly.newPlot(container, [trace], layout, config);
    }

    // Heatmap Correlation Matrix
    function renderHeatmapWidget(widget, container, entries, layoutTheme, config, colors) {
        const numericFields = dataset.fields.filter(f => f.type === "number").slice(0, 8);
        const labels = numericFields.map(f => f.label);
        const matrix = [];

        for (let i = 0; i < numericFields.length; i++) {
            const row = [];
            for (let j = 0; j < numericFields.length; j++) {
                const f1 = numericFields[i].id;
                const f2 = numericFields[j].id;
                row.push(Number(computeCorrelation(entries, f1, f2).toFixed(2)));
            }
            matrix.push(row);
        }

        const trace = {
            z: matrix,
            x: labels,
            y: labels,
            type: "heatmap",
            colorscale: "Viridis"
        };

        const layout = {
            ...layoutTheme,
            xaxis: { tickangle: -45 }
        };

        Plotly.newPlot(container, [trace], layout, config);
    }

    // Histogram
    function renderHistogramWidget(widget, container, entries, layoutTheme, config, colors) {
        const measure = widget.measure || "calc_total_score";
        const values = entries.map(e => Number(e[measure]) || 0);

        const trace = {
            x: values,
            type: "histogram",
            marker: { color: colors[0] }
        };

        const layout = {
            ...layoutTheme,
            xaxis: { title: getFieldLabel(measure) },
            yaxis: { title: "Frequency Count" }
        };

        Plotly.newPlot(container, [trace], layout, config);
    }

    function computeCorrelation(entries, fieldA, fieldB) {
        const xs = entries.map(e => Number(e[fieldA]) || 0);
        const ys = entries.map(e => Number(e[fieldB]) || 0);
        if (xs.length < 2) return 1;

        const meanX = xs.reduce((a, b) => a + b, 0) / xs.length;
        const meanY = ys.reduce((a, b) => a + b, 0) / ys.length;

        let num = 0;
        let denX = 0;
        let denY = 0;

        for (let i = 0; i < xs.length; i++) {
            const dx = xs[i] - meanX;
            const dy = ys[i] - meanY;
            num += dx * dy;
            denX += dx * dx;
            denY += dy * dy;
        }

        if (denX === 0 || denY === 0) return 0;
        return num / Math.sqrt(denX * denY);
    }

    function computeAggregation(numbers, aggType) {
        if (!numbers || numbers.length === 0) return 0;
        switch (aggType) {
            case "sum":
                return numbers.reduce((a, b) => a + b, 0);
            case "max":
                return Math.max(...numbers);
            case "min":
                return Math.min(...numbers);
            case "count":
                return numbers.length;
            case "median": {
                const sorted = [...numbers].sort((a, b) => a - b);
                const mid = Math.floor(sorted.length / 2);
                return sorted.length % 2 !== 0 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
            }
            case "stdev": {
                const avg = numbers.reduce((a, b) => a + b, 0) / numbers.length;
                const squareDiffs = numbers.map(v => Math.pow(v - avg, 2));
                return Math.sqrt(squareDiffs.reduce((a, b) => a + b, 0) / numbers.length);
            }
            case "p75": {
                const sorted = [...numbers].sort((a, b) => a - b);
                const idx = Math.floor(sorted.length * 0.75);
                return sorted[Math.min(idx, sorted.length - 1)];
            }
            case "avg":
            default:
                return numbers.reduce((a, b) => a + b, 0) / numbers.length;
        }
    }

    function getFieldLabel(fieldId) {
        const f = dataset.fields.find(item => item.id === fieldId);
        return f ? f.label : fieldId;
    }

    /**
     * Widget Configuration Modal (Power BI Field Well Editor)
     */
    function openWidgetEditor(index) {
        editingWidgetIndex = index;
        const modal = document.getElementById("modal-widget-editor");
        if (!modal) return;

        let widget = null;
        if (index >= 0 && currentReport.widgets[index]) {
            widget = JSON.parse(JSON.stringify(currentReport.widgets[index]));
        } else {
            widget = {
                id: "w_" + Date.now(),
                type: "bar",
                title: "New Visual",
                subtitle: "",
                width: "col-6",
                dimension: "teamNumber",
                measure: "calc_total_score",
                aggregation: "avg",
                secondaryMeasures: [],
                palette: "obsidian",
                sort: "val_desc",
                topN: 0,
                targetLine: null
            };
        }

        // Fill Form Fields
        document.getElementById("editor-title").value = widget.title || "";
        document.getElementById("editor-subtitle").value = widget.subtitle || "";
        document.getElementById("editor-width").value = widget.width || "col-6";
        document.getElementById("editor-palette").value = widget.palette || "obsidian";
        document.getElementById("editor-sort").value = widget.sort || "val_desc";
        document.getElementById("editor-top-n").value = widget.topN || 0;
        document.getElementById("editor-target-line").value = widget.targetLine || "";
        document.getElementById("editor-aggregation").value = widget.aggregation || "avg";

        // Select Type Button
        document.querySelectorAll("#editor-visual-types .bi-visual-type-btn").forEach(btn => {
            btn.classList.toggle("active", btn.dataset.type === widget.type);
        });

        // Populate Dimension & Measure Dropdowns
        const dimSelect = document.getElementById("editor-dimension");
        const measSelect = document.getElementById("editor-measure");
        const secondaryContainer = document.getElementById("editor-secondary-measures-list");

        if (dimSelect) {
            dimSelect.innerHTML = `
                <option value="teamNumber">Team Number</option>
                <option value="matchNumber">Match Number</option>
                <option value="eventKey">Event Key</option>
            `;
            dimSelect.value = widget.dimension || "teamNumber";
        }

        if (measSelect) {
            populateMeasureDropdown(measSelect, widget.measure || "calc_total_score");
        }

        if (secondaryContainer) {
            secondaryContainer.innerHTML = "";
            dataset.fields.filter(f => f.type === "number").forEach(f => {
                const label = document.createElement("label");
                label.style.fontSize = "12px";
                label.style.display = "flex";
                label.style.alignItems = "center";
                label.style.gap = "6px";
                label.style.cursor = "pointer";

                const chk = document.createElement("input");
                chk.type = "checkbox";
                chk.value = f.id;
                chk.checked = (widget.secondaryMeasures || []).includes(f.id);
                chk.style.width = "auto";
                chk.style.margin = "0";

                label.appendChild(chk);
                label.appendChild(document.createTextNode(`${f.label} (${f.section || f.source})`));
                secondaryContainer.appendChild(label);
            });
        }
        updateSecondaryMeasuresVisibility(widget.type || "bar");
        modal.classList.add("show");
    }

    const TYPES_SUPPORTING_SECONDARY = ["stacked_bar", "radar", "matrix", "donut"];

    function updateSecondaryMeasuresVisibility(type) {
        const secSection = document.getElementById("well-secondary-section");
        if (secSection) {
            const supported = TYPES_SUPPORTING_SECONDARY.includes(type);
            secSection.style.display = supported ? "block" : "none";
        }
    }

    function populateMeasureDropdown(selectEl, selectedValue) {
        if (!selectEl) return;
        selectEl.innerHTML = "";

        const groups = {};
        dataset.fields.forEach(f => {
            let groupName = "General";
            const sec = (f.section || "").toLowerCase();
            const src = (f.source || "").toLowerCase();
            const id = f.id.toLowerCase();

            if (src === "calculated" && sec.includes("custom")) {
                groupName = "Custom Calculated Metrics";
            } else if (sec.includes("auto") || id.includes("auto")) {
                groupName = "Autonomous Scoring";
            } else if (sec.includes("teleop") || id.includes("teleop")) {
                groupName = "Teleop Scoring";
            } else if (sec.includes("endgame") || sec.includes("climb") || id.includes("endgame") || id.includes("climb")) {
                groupName = "Endgame & Climbing";
            } else if (sec.includes("score") || id.includes("score") || id.includes("pts")) {
                groupName = "Phase & Total Scores";
            } else if (id.includes("epa") || id.includes("opr") || sec.includes("statistics")) {
                groupName = "Statistics";
            } else if (src === "pit" || sec.includes("pit")) {
                groupName = "Pit Scouting";
            } else if (src === "qual" || sec.includes("qual")) {
                groupName = "Qualitative Scouting";
            } else if (src === "system") {
                groupName = "Identification & System";
            }

            if (!groups[groupName]) groups[groupName] = [];
            groups[groupName].push(f);
        });

        Object.keys(groups).forEach(groupName => {
            const optgroup = document.createElement("optgroup");
            optgroup.label = groupName;
            groups[groupName].forEach(f => {
                const opt = document.createElement("option");
                opt.value = f.id;
                opt.textContent = f.label;
                if (f.id === selectedValue) opt.selected = true;
                optgroup.appendChild(opt);
            });
            selectEl.appendChild(optgroup);
        });

        selectEl.value = selectedValue || "calc_total_score";
    }

    function bindEditorModals() {
        // Visual Type Buttons
        document.querySelectorAll("#editor-visual-types .bi-visual-type-btn").forEach(btn => {
            btn.addEventListener("click", () => {
                document.querySelectorAll("#editor-visual-types .bi-visual-type-btn").forEach(b => b.classList.remove("active"));
                btn.classList.add("active");
                updateSecondaryMeasuresVisibility(btn.dataset.type);
            });
        });

        // Close Editor Modal
        const btnCloseWidget = document.getElementById("btn-close-widget-editor");
        const btnCancelWidget = document.getElementById("btn-cancel-widget-editor");
        const modalWidget = document.getElementById("modal-widget-editor");
        if (btnCloseWidget && modalWidget) btnCloseWidget.addEventListener("click", () => modalWidget.classList.remove("show"));
        if (btnCancelWidget && modalWidget) btnCancelWidget.addEventListener("click", () => modalWidget.classList.remove("show"));

        // Save Widget
        const btnSaveWidget = document.getElementById("btn-save-widget");
        if (btnSaveWidget) {
            btnSaveWidget.addEventListener("click", () => {
                const activeTypeBtn = document.querySelector("#editor-visual-types .bi-visual-type-btn.active");
                const type = activeTypeBtn ? activeTypeBtn.dataset.type : "bar";

                const secondary = [];
                if (TYPES_SUPPORTING_SECONDARY.includes(type)) {
                    document.querySelectorAll("#editor-secondary-measures-list input[type='checkbox']:checked").forEach(chk => {
                        secondary.push(chk.value);
                    });
                }

                const updated = {
                    id: editingWidgetIndex >= 0 ? currentReport.widgets[editingWidgetIndex].id : "w_" + Date.now(),
                    type: type,
                    title: document.getElementById("editor-title").value.trim() || "Visual",
                    subtitle: document.getElementById("editor-subtitle").value.trim(),
                    width: document.getElementById("editor-width").value,
                    dimension: document.getElementById("editor-dimension").value,
                    measure: document.getElementById("editor-measure").value,
                    aggregation: document.getElementById("editor-aggregation").value,
                    secondaryMeasures: secondary,
                    palette: document.getElementById("editor-palette").value,
                    sort: document.getElementById("editor-sort").value,
                    topN: parseInt(document.getElementById("editor-top-n").value, 10) || 0,
                    targetLine: parseFloat(document.getElementById("editor-target-line").value) || null
                };

                if (editingWidgetIndex >= 0) {
                    currentReport.widgets[editingWidgetIndex] = updated;
                } else {
                    currentReport.widgets.push(updated);
                }

                modalWidget.classList.remove("show");
                renderDashboard();
            });
        }

        // Close Calculated Metric Modal
        const modalCalc = document.getElementById("modal-calc-measure");
        const btnCloseCalc = document.getElementById("btn-close-calc-measure");
        const btnCancelCalc = document.getElementById("btn-cancel-calc-measure");
        if (btnCloseCalc && modalCalc) btnCloseCalc.addEventListener("click", () => modalCalc.classList.remove("show"));
        if (btnCancelCalc && modalCalc) btnCancelCalc.addEventListener("click", () => modalCalc.classList.remove("show"));

        const btnSaveCalc = document.getElementById("btn-save-calc-measure");
        if (btnSaveCalc) {
            btnSaveCalc.addEventListener("click", () => {
                const name = document.getElementById("calc-metric-name").value.trim();
                const id = document.getElementById("calc-metric-id").value.trim().replace(/[^a-zA-Z0-9_]/g, "_");
                const formula = document.getElementById("calc-metric-formula").value.trim();

                if (!name || !id || !formula) {
                    alert("Please provide metric name, ID, and formula expression.");
                    return;
                }

                if (!currentReport.calculatedMetrics) currentReport.calculatedMetrics = [];
                currentReport.calculatedMetrics.push({ id, name, formula });

                applyCalculatedMetrics();
                modalCalc.classList.remove("show");
                if (window.Obsidianscout && Obsidianscout.showToast) {
                    Obsidianscout.showToast(`Calculated Metric "${name}" added!`, "success");
                }
                renderDashboard();
            });
        }

        // Close Report Library Modal
        const modalLib = document.getElementById("modal-report-library");
        const btnCloseLib = document.getElementById("btn-close-report-library");
        const btnCloseLibFooter = document.getElementById("btn-close-report-library-footer");
        if (btnCloseLib && modalLib) btnCloseLib.addEventListener("click", () => modalLib.classList.remove("show"));
        if (btnCloseLibFooter && modalLib) btnCloseLibFooter.addEventListener("click", () => modalLib.classList.remove("show"));

        // Save Report Action
        const btnExecSave = document.getElementById("btn-execute-save-report");
        if (btnExecSave) {
            btnExecSave.addEventListener("click", async () => {
                await executeSaveReport();
            });
        }

        // Close Template Modal
        const modalTemp = document.getElementById("modal-templates");
        const btnCloseTemp = document.getElementById("btn-close-templates");
        const btnCloseTempFooter = document.getElementById("btn-close-templates-footer");
        if (btnCloseTemp && modalTemp) btnCloseTemp.addEventListener("click", () => modalTemp.classList.remove("show"));
        if (btnCloseTempFooter && modalTemp) btnCloseTempFooter.addEventListener("click", () => modalTemp.classList.remove("show"));

        // Template Selection Cards
        document.querySelectorAll(".bi-template-card").forEach(card => {
            card.addEventListener("click", () => {
                const tempType = card.dataset.template;
                loadStarterTemplate(tempType);
                modalTemp.classList.remove("show");
            });
        });

        // Close Drill-Down Modal
        const modalDrill = document.getElementById("modal-drill-down");
        const btnCloseDrill = document.getElementById("btn-close-drill-down");
        const btnCloseDrillFooter = document.getElementById("btn-close-drill-down-footer");
        if (btnCloseDrill && modalDrill) btnCloseDrill.addEventListener("click", () => modalDrill.classList.remove("show"));
        if (btnCloseDrillFooter && modalDrill) btnCloseDrillFooter.addEventListener("click", () => modalDrill.classList.remove("show"));

        // JSON Config Import/Export
        const btnExportJsonConfig = document.getElementById("btn-export-json-config");
        if (btnExportJsonConfig) {
            btnExportJsonConfig.addEventListener("click", () => exportReportJson());
        }

        const inputImportJson = document.getElementById("input-import-json-config");
        if (inputImportJson) {
            inputImportJson.addEventListener("change", (e) => {
                const file = e.target.files[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = (evt) => {
                    try {
                        const parsed = JSON.parse(evt.target.result);
                        if (parsed && parsed.widgets) {
                            currentReport = parsed;
                            updateReportHeaderUI();
                            applyCalculatedMetrics();
                            renderDashboard();
                            modalLib.classList.remove("show");
                            if (window.Obsidianscout && Obsidianscout.showToast) {
                                Obsidianscout.showToast("Dashboard Configuration imported successfully!", "success");
                            }
                        }
                    } catch (err) {
                        alert("Invalid Report JSON format.");
                    }
                };
                reader.readAsText(file);
            });
        }
    }

    function openCalculatedMetricModal() {
        const modal = document.getElementById("modal-calc-measure");
        if (!modal) return;

        const pillsContainer = document.getElementById("calc-available-fields-pills");
        const formulaInput = document.getElementById("calc-metric-formula");
        if (pillsContainer && formulaInput) {
            pillsContainer.innerHTML = "";
            dataset.fields.forEach(f => {
                const pill = document.createElement("span");
                pill.className = "bi-team-pill";
                pill.textContent = `[${f.id}]`;
                pill.style.cursor = "pointer";
                pill.title = f.label;
                pill.addEventListener("click", () => {
                    formulaInput.value += ` [${f.id}] `;
                });
                pillsContainer.appendChild(pill);
            });
        }

        modal.classList.add("show");
    }

    function openReportLibraryModal(focusSave = false) {
        const modal = document.getElementById("modal-report-library");
        if (!modal) return;

        document.getElementById("save-report-title").value = currentReport.title || "";
        document.getElementById("save-report-category").value = currentReport.category || "Strategy";
        document.getElementById("save-report-desc").value = currentReport.description || "";
        document.getElementById("save-report-shared").checked = !!currentReport.isShared;
        document.getElementById("save-report-default").checked = !!currentReport.isDefault;

        renderReportsList();
        modal.classList.add("show");
    }

    function renderReportsList() {
        const listContainer = document.getElementById("reports-list-container");
        if (!listContainer) return;

        if (savedReports.length === 0) {
            listContainer.innerHTML = `<p class="notice">No saved reports found for your team. Save your first dashboard above!</p>`;
            return;
        }

        listContainer.innerHTML = "";
        savedReports.forEach(rep => {
            const item = document.createElement("div");
            item.className = "card";
            item.style.padding = "12px 16px";
            item.style.display = "flex";
            item.style.justifyContent = "space-between";
            item.style.alignItems = "center";
            item.style.gap = "12px";

            item.innerHTML = `
                <div>
                    <div class="row items-center gap-8">
                        <strong style="font-size: 14px;">${escapeHtml(rep.title)}</strong>
                        <span class="bi-category-badge">${escapeHtml(rep.category)}</span>
                        ${rep.isDefault ? `<span class="badge" style="background: var(--accent); color: white;">Default</span>` : ""}
                        ${rep.isShared ? `<span class="badge">Shared</span>` : ""}
                    </div>
                    <p class="notice" style="margin: 2px 0 0 0; font-size: 12px;">By ${escapeHtml(rep.authorUsername || "Unknown")} • Updated ${new Date(rep.updatedAt).toLocaleDateString()}</p>
                </div>
                <div class="row gap-6">
                    <button class="btn btn-sm" data-action="load">Load</button>
                    <button class="btn ghost btn-sm" data-action="dup">Clone</button>
                    ${rep.isOwner ? `<button class="btn ghost btn-sm" data-action="del" style="color: var(--danger);">Delete</button>` : ""}
                </div>
            `;

            item.querySelector("[data-action='load']").addEventListener("click", () => loadReport(rep));
            item.querySelector("[data-action='dup']").addEventListener("click", () => duplicateSavedReport(rep.id));
            const delBtn = item.querySelector("[data-action='del']");
            if (delBtn) delBtn.addEventListener("click", () => deleteSavedReport(rep.id));

            listContainer.appendChild(item);
        });
    }

    async function loadReport(reportRecord) {
        try {
            const parsed = JSON.parse(reportRecord.configJson);
            currentReport = {
                ...parsed,
                id: reportRecord.id,
                title: reportRecord.title,
                category: reportRecord.category,
                description: reportRecord.description,
                isShared: reportRecord.isShared,
                isDefault: reportRecord.isDefault
            };
            updateReportHeaderUI();
            applyCalculatedMetrics();
            renderDashboard();
            document.getElementById("modal-report-library").classList.remove("show");
            if (window.Obsidianscout && Obsidianscout.showToast) {
                Obsidianscout.showToast(`Report "${reportRecord.title}" loaded!`, "success");
            }
        } catch (e) {
            alert("Failed to load report: " + e.message);
        }
    }

    async function executeSaveReport() {
        const title = document.getElementById("save-report-title").value.trim() || "Scouting Dashboard";
        const category = document.getElementById("save-report-category").value;
        const description = document.getElementById("save-report-desc").value.trim();
        const isShared = document.getElementById("save-report-shared").checked;
        const isDefault = document.getElementById("save-report-default").checked;

        currentReport.title = title;
        currentReport.category = category;
        currentReport.description = description;
        currentReport.isShared = isShared;
        currentReport.isDefault = isDefault;

        const payload = {
            title: title,
            category: category,
            description: description,
            configJson: JSON.stringify(currentReport),
            isShared: isShared,
            isDefault: isDefault
        };

        try {
            let saved = null;
            if (currentReport.id) {
                saved = await Obsidianscout.request(`/api/custom-analytics/reports/${currentReport.id}`, {
                    method: "PUT",
                    json: payload
                });
            } else {
                saved = await Obsidianscout.request("/api/custom-analytics/reports", {
                    method: "POST",
                    json: payload
                });
                currentReport.id = saved.id;
            }

            await fetchReportsList();
            updateReportHeaderUI();
            document.getElementById("modal-report-library").classList.remove("show");
            if (window.Obsidianscout && Obsidianscout.showToast) {
                Obsidianscout.showToast(`Report "${title}" saved successfully!`, "success");
            }
        } catch (err) {
            alert("Failed to save report: " + err.message);
        }
    }

    async function duplicateSavedReport(reportId) {
        try {
            await Obsidianscout.request(`/api/custom-analytics/reports/${reportId}/duplicate`, { method: "POST" });
            await fetchReportsList();
            renderReportsList();
            if (window.Obsidianscout && Obsidianscout.showToast) {
                Obsidianscout.showToast("Report duplicated!", "success");
            }
        } catch (err) {
            alert("Failed to duplicate report: " + err.message);
        }
    }

    async function deleteSavedReport(reportId) {
        if (!confirm("Are you sure you want to delete this saved report?")) return;
        try {
            await Obsidianscout.request(`/api/custom-analytics/reports/${reportId}`, { method: "DELETE" });
            if (currentReport.id === reportId) currentReport.id = null;
            await fetchReportsList();
            renderReportsList();
            if (window.Obsidianscout && Obsidianscout.showToast) {
                Obsidianscout.showToast("Report deleted.", "info");
            }
        } catch (err) {
            alert("Failed to delete report: " + err.message);
        }
    }

    function openTemplatesModal() {
        const modal = document.getElementById("modal-templates");
        if (modal) modal.classList.add("show");
    }

    function loadStarterTemplate(templateName) {
        currentReport.id = null;
        switch (templateName) {
            case "blank":
                currentReport.title = "Custom Report";
                currentReport.category = "General";
                currentReport.widgets = [];
                break;
            case "pick_list":
                currentReport.title = "Pick List & Team Profiles";
                currentReport.category = "Pick List";
                currentReport.widgets = [
                    {
                        id: "w_pick_radar",
                        type: "radar",
                        title: "Multidimensional Team Profile",
                        subtitle: "Capability Radar across Top 5 Teams",
                        width: "col-6",
                        dimension: "teamNumber",
                        measure: "calc_auto_score",
                        secondaryMeasures: ["calc_teleop_score", "calc_total_score"],
                        palette: "obsidian"
                    },
                    {
                        id: "w_pick_scatter",
                        type: "scatter",
                        title: "Auto vs Teleop Scoring Scatter",
                        subtitle: "Correlation & Clustering",
                        width: "col-6",
                        dimension: "teamNumber",
                        measure: "calc_auto_score",
                        secondaryMeasures: ["calc_teleop_score"],
                        palette: "emerald"
                    },
                    {
                        id: "w_pick_matrix",
                        type: "matrix",
                        title: "Pick List Evaluation Table",
                        subtitle: "Sortable Multi-Metric Ranking Grid",
                        width: "col-12",
                        dimension: "teamNumber",
                        measure: "calc_total_score",
                        secondaryMeasures: ["calc_auto_score", "calc_teleop_score"],
                        palette: "obsidian"
                    }
                ];
                break;
            case "match_strategy":
            default:
                currentReport.title = "Match Strategy & Performance";
                currentReport.category = "Strategy";
                currentReport.widgets = getDefaultWidgets();
                break;
        }

        updateReportHeaderUI();
        renderDashboard();
        const modal = document.getElementById("modal-templates");
        if (modal) modal.classList.remove("show");
    }

    /**
     * Drill-Down Modal to inspect individual match records for a team
     */
    function openDrillDownModal(teamNumber) {
        const modal = document.getElementById("modal-drill-down");
        const title = document.getElementById("drill-down-title");
        const content = document.getElementById("drill-down-content");
        if (!modal || !content) return;

        if (title) title.textContent = `Team ${teamNumber} Match Breakdown & Logs`;

        const teamMatches = dataset.matchEntries.filter(m => m.teamNumber === teamNumber).sort((a, b) => (a.matchNumber || 0) - (b.matchNumber || 0));

        if (teamMatches.length === 0) {
            content.innerHTML = `<p class="notice">No match scouting records found for Team ${teamNumber}.</p>`;
        } else {
            let html = `
                <table class="bi-table">
                    <thead>
                        <tr>
                            <th>Match</th>
                            <th>Total Score</th>
                            <th>Auto Score</th>
                            <th>Teleop Score</th>
                            <th>Prescout</th>
                            <th>Recorded At</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${teamMatches.map(m => `
                            <tr>
                                <td><strong>M${m.matchNumber || "?"}</strong> (${escapeHtml(m.matchKey || "")})</td>
                                <td><strong>${Number(m.calc_total_score || 0).toFixed(1)}</strong></td>
                                <td>${Number(m.calc_auto_score || 0).toFixed(1)}</td>
                                <td>${Number(m.calc_teleop_score || 0).toFixed(1)}</td>
                                <td>${m.isPrescout ? "Yes" : "No"}</td>
                                <td>${new Date(m.createdAt).toLocaleTimeString()}</td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            `;
            content.innerHTML = html;
        }

        modal.classList.add("show");
    }

    /**
     * Export Utilities
     */
    function exportReportJson() {
        const jsonStr = JSON.stringify(currentReport, null, 2);
        downloadFile(`${currentReport.title.replace(/[^a-zA-Z0-9]/g, "_")}_report.json`, jsonStr, "application/json");
    }

    function exportAggregatedCsv() {
        const entries = getFilteredEntries();
        if (entries.length === 0) {
            alert("No data to export.");
            return;
        }

        const fields = dataset.fields.map(f => f.id);
        const headers = dataset.fields.map(f => f.label);

        let csv = headers.join(",") + "\n";
        entries.forEach(e => {
            const row = fields.map(f => {
                const val = e[f];
                if (val === null || val === undefined) return "";
                if (typeof val === "string") return `"${val.replace(/"/g, '""')}"`;
                return val;
            });
            csv += row.join(",") + "\n";
        });

        downloadFile(`${currentReport.title.replace(/[^a-zA-Z0-9]/g, "_")}_dataset.csv`, csv, "text/csv");
    }

    function downloadFile(filename, text, mimeType) {
        const blob = new Blob([text], { type: mimeType });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
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
})();
