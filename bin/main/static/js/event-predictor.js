let currentPredictions = [];
let currentUser = null;

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }
    currentUser = me;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    // Modal close listeners
    const modal = document.getElementById("predictor-modal");
    const closeBtn = document.getElementById("modal-close-btn");
    if (modal && closeBtn) {
        closeBtn.addEventListener("click", () => modal.classList.remove("active"));
        modal.addEventListener("click", (e) => {
            if (e.target === modal) modal.classList.remove("active");
        });
        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape" && modal.classList.contains("active")) {
                modal.classList.remove("active");
            }
        });
    }

    await loadEvents();
});

async function loadEvents() {
    const eventSelect = document.getElementById("event-select");
    if (!eventSelect) return;

    try {
        const events = await Obsidianscout.request("/api/events?cached=1");
        
        eventSelect.innerHTML = '<option value="">-- Select an Event --</option>';
        if (events.length === 0) {
            eventSelect.innerHTML = '<option value="">No events synced in database</option>';
            return;
        }

        events.forEach((event) => {
            const opt = document.createElement("option");
            opt.value = event.eventKey;
            opt.textContent = `${event.name} (${event.eventKey.toUpperCase()})`;
            eventSelect.appendChild(opt);
        });

        // Set up event listeners
        eventSelect.addEventListener("change", fetchPredictions);
        document.getElementById("datasource-select").addEventListener("change", renderPredictionsList);
        document.getElementById("force-prescout").addEventListener("change", fetchPredictions);
        document.getElementById("search-input").addEventListener("input", renderPredictionsList);

    } catch (error) {
        console.error("Failed to load events:", error);
        Obsidianscout.showToast("Failed to load events: " + error.message, "error");
    }
}

async function fetchPredictions() {
    const eventSelect = document.getElementById("event-select");
    const emptyState = document.getElementById("predictor-empty-state");
    const predictionsCard = document.getElementById("predictions-card");
    const tbody = document.getElementById("predictions-tbody");
    const loadingState = document.getElementById("predictor-loading-state");
    const loadingText = document.getElementById("predictor-loading-text");

    const eventKey = eventSelect.value;
    if (!eventKey) {
        emptyState.classList.remove("hidden");
        predictionsCard.classList.add("hidden");
        if (loadingState) loadingState.classList.add("hidden");
        currentPredictions = [];
        return;
    }

    try {
        emptyState.classList.add("hidden");
        predictionsCard.classList.add("hidden");
        
        if (loadingState && loadingText) {
            const datasourceSelect = document.getElementById("datasource-select");
            const selectedSource = datasourceSelect ? datasourceSelect.value : "scouted";
            if (selectedSource === "epa" || selectedSource === "opr") {
                loadingText.textContent = t("event_predictor.fetching", "Fetching data from API...");
            } else {
                loadingText.textContent = t("event_predictor.calculating", "Calculating predictions for all matches...");
            }
            loadingState.classList.remove("hidden");
        }

        const forcePrescout = document.getElementById("force-prescout").checked;
        const predictions = await Obsidianscout.request(`/api/matches/predict-all?eventKey=${eventKey}&usePrescout=${forcePrescout}`);
        currentPredictions = predictions;

        if (loadingState) loadingState.classList.add("hidden");
        predictionsCard.classList.remove("hidden");
        renderPredictionsList();

    } catch (error) {
        console.error("Failed to fetch predictions:", error);
        if (loadingState) loadingState.classList.add("hidden");
        predictionsCard.classList.add("hidden");
        emptyState.classList.remove("hidden");
        Obsidianscout.showToast(error.message || "Failed to calculate predictions", "error");
    }
}

function renderPredictionsList() {
    const tbody = document.getElementById("predictions-tbody");
    const countBadge = document.getElementById("predictions-count");
    const model = document.getElementById("datasource-select").value;
    const searchQuery = document.getElementById("search-input").value.trim().toLowerCase();

    if (!tbody || currentPredictions.length === 0) {
        return;
    }

    const filtered = currentPredictions.filter(match => {
        if (!searchQuery) return true;

        // Match label check
        if (match.label.toLowerCase().includes(searchQuery)) return true;

        // Team numbers check
        const redTeams = match.redAlliance.teams.map(t => t.teamNumber.toString());
        const blueTeams = match.blueAlliance.teams.map(t => t.teamNumber.toString());
        
        return redTeams.some(num => num.includes(searchQuery)) || 
               blueTeams.some(num => num.includes(searchQuery));
    });

    countBadge.textContent = `${filtered.length} matches`;

    if (filtered.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; padding: 24px;">
            No matches found matching search criteria.
        </td></tr>`;
        return;
    }

    tbody.innerHTML = "";
    filtered.forEach(match => {
        const row = document.createElement("tr");

        // 1. Match Label cell
        const matchCell = document.createElement("td");
        const matchLink = document.createElement("a");
        matchLink.href = "#";
        matchLink.className = "match-link";
        matchLink.innerHTML = `${match.label} <span style="font-size: 0.75rem; font-weight: normal; text-decoration: none;">🔍</span>`;
        matchLink.addEventListener("click", (e) => {
            e.preventDefault();
            openPredictorModal(match.matchKey, match.label);
        });
        matchCell.appendChild(matchLink);

        // Helper to format teams list
        const buildAllianceTeamsHtml = (allianceTeams, color) => {
            return allianceTeams.map(t => {
                const teamNum = t.teamNumber;
                return `<span class="alliance-badge ${color}" title="${t.nickname || ''}">${teamNum}</span>`;
            }).join(" ");
        };

        // 2. Red Alliance cell
        const redCell = document.createElement("td");
        redCell.className = "alliance-teams";
        redCell.innerHTML = buildAllianceTeamsHtml(match.redAlliance.teams, "red");

        // 3. Blue Alliance cell
        const blueCell = document.createElement("td");
        blueCell.className = "alliance-teams";
        blueCell.innerHTML = buildAllianceTeamsHtml(match.blueAlliance.teams, "blue");

        // 4. Predicted Winner and 5. Details
        const winnerCell = document.createElement("td");
        const detailsCell = document.createElement("td");
        detailsCell.className = "col-prediction-details";

        let winnerBadgeHtml = "";
        let detailsHtml = "";

        if (model === "all") {
            let redVotes = 0;
            let blueVotes = 0;

            const redScouted = match.redAlliance.totalScoutedScore;
            const blueScouted = match.blueAlliance.totalScoutedScore;
            if (redScouted > 0 || blueScouted > 0) {
                if (redScouted > blueScouted) redVotes++;
                else if (blueScouted > redScouted) blueVotes++;
            }

            const redEpa = match.redAlliance.totalEpa;
            const blueEpa = match.blueAlliance.totalEpa;
            if (redEpa > 0 || blueEpa > 0) {
                if (redEpa > blueEpa) redVotes++;
                else if (blueEpa > redEpa) blueVotes++;
            }

            const redOpr = match.redAlliance.totalOpr;
            const blueOpr = match.blueAlliance.totalOpr;
            if (redOpr > 0 || blueOpr > 0) {
                if (redOpr > blueOpr) redVotes++;
                else if (blueOpr > redOpr) blueVotes++;
            }

            if (redVotes === 0 && blueVotes === 0) {
                winnerBadgeHtml = `<span class="winner-badge draw-win">No Data</span>`;
                detailsHtml = `<span class="diff-text">Insufficient data to predict</span>`;
            } else {
                if (redVotes > blueVotes) {
                    winnerBadgeHtml = `<span class="winner-badge red-win">Red (${redVotes}/${redVotes + blueVotes})</span>`;
                } else if (blueVotes > redVotes) {
                    winnerBadgeHtml = `<span class="winner-badge blue-win">Blue (${blueVotes}/${redVotes + blueVotes})</span>`;
                } else {
                    winnerBadgeHtml = `<span class="winner-badge draw-win">Split / Tie</span>`;
                }

                detailsHtml = `
                    <div class="score-comp">
                        <div class="score-row" style="font-size: 0.75rem;">
                            <span style="color: #f87171; font-weight: 500;">Scouted:</span>
                            <span style="font-weight: 600; color: #e2e8f0;">R ${redScouted.toFixed(1)} - B ${blueScouted.toFixed(1)}</span>
                        </div>
                        <div class="score-row" style="font-size: 0.75rem;">
                            <span style="color: #f59e0b; font-weight: 500;">EPA:</span>
                            <span style="font-weight: 600; color: #e2e8f0;">R ${redEpa.toFixed(1)} - B ${blueEpa.toFixed(1)}</span>
                        </div>
                        <div class="score-row" style="font-size: 0.75rem;">
                            <span style="color: #3b82f6; font-weight: 500;">OPR:</span>
                            <span style="font-weight: 600; color: #e2e8f0;">R ${redOpr.toFixed(1)} - B ${blueOpr.toFixed(1)}</span>
                        </div>
                    </div>
                `;
            }
        } else {
            let redScore = 0;
            let blueScore = 0;
            let unit = "pts";

            if (model === "scouted") {
                redScore = match.redAlliance.totalScoutedScore;
                blueScore = match.blueAlliance.totalScoutedScore;
                unit = "pts";
            } else if (model === "epa") {
                redScore = match.redAlliance.totalEpa;
                blueScore = match.blueAlliance.totalEpa;
                unit = "EPA";
            } else if (model === "opr") {
                redScore = match.redAlliance.totalOpr;
                blueScore = match.blueAlliance.totalOpr;
                unit = "OPR";
            }

            const diff = Math.abs(redScore - blueScore);

            if (redScore === 0 && blueScore === 0) {
                winnerBadgeHtml = `<span class="winner-badge draw-win">No Data</span>`;
                detailsHtml = `<span class="diff-text">Insufficient data to predict</span>`;
            } else {
                if (redScore > blueScore) {
                    winnerBadgeHtml = `<span class="winner-badge red-win">Red +${diff.toFixed(1)}</span>`;
                } else if (blueScore > redScore) {
                    winnerBadgeHtml = `<span class="winner-badge blue-win">Blue +${diff.toFixed(1)}</span>`;
                } else {
                    winnerBadgeHtml = `<span class="winner-badge draw-win">Dead Heat</span>`;
                }

                const redClass = redScore >= blueScore ? "win" : "";
                const blueClass = blueScore >= redScore ? "win" : "";
                detailsHtml = `
                    <div class="score-comp">
                        <div class="score-row ${redClass}">
                            <span style="color: #f87171;">Red Alliance:</span>
                            <span class="score-val">${redScore.toFixed(1)} ${unit}</span>
                        </div>
                        <div class="score-row ${blueClass}">
                            <span style="color: #60a5fa;">Blue Alliance:</span>
                            <span class="score-val">${blueScore.toFixed(1)} ${unit}</span>
                        </div>
                    </div>
                `;
            }
        }

        winnerCell.innerHTML = winnerBadgeHtml;
        detailsCell.innerHTML = detailsHtml;

        row.appendChild(matchCell);
        row.appendChild(redCell);
        row.appendChild(blueCell);
        row.appendChild(winnerCell);
        row.appendChild(detailsCell);

        tbody.appendChild(row);
    });
}

async function openPredictorModal(matchKey, matchLabel) {
    const modal = document.getElementById("predictor-modal");
    const modalLabel = document.getElementById("modal-match-label");
    const loadingState = document.getElementById("modal-loading-state");
    const workspace = document.getElementById("modal-workspace");
    const eventSelect = document.getElementById("event-select");
    const eventKey = eventSelect ? eventSelect.value : "";

    if (!modal) return;

    modalLabel.textContent = matchLabel || "Match Prediction";
    modal.classList.add("active");
    loadingState.classList.remove("hidden");
    workspace.classList.add("hidden");

    try {
        const prediction = await Obsidianscout.request(`/api/matches/predict?matchKey=${matchKey}&eventKey=${eventKey}`);
        
        renderModalPrediction(prediction);
        
        loadingState.classList.add("hidden");
        workspace.classList.remove("hidden");
    } catch (err) {
        console.error("Failed to load match prediction:", err);
        modal.classList.remove("active");
        Obsidianscout.showToast(err.message || "Failed to load prediction details", "error");
    }
}

function renderModalPrediction(data) {
    const red = data.redAlliance;
    const blue = data.blueAlliance;

    // Scouted Score Comparison Bar
    const redScouted = red.totalScoutedScore;
    const blueScouted = blue.totalScoutedScore;
    document.getElementById("modal-lbl-scouted-red").textContent = `Red: ${redScouted.toFixed(1)} pts`;
    document.getElementById("modal-lbl-scouted-blue").textContent = `Blue: ${blueScouted.toFixed(1)} pts`;
    updateModalBar("modal-bar-scouted-red", "modal-bar-scouted-blue", redScouted, blueScouted);
    renderModalSpotlight("modal-spotlight-scouted-winner", "modal-spotlight-scouted-subtext", redScouted, blueScouted, "Scouted");

    // EPA Comparison Bar
    const redEpa = red.totalEpa;
    const blueEpa = blue.totalEpa;
    document.getElementById("modal-lbl-epa-red").textContent = `Red: ${redEpa.toFixed(1)}`;
    document.getElementById("modal-lbl-epa-blue").textContent = `Blue: ${blueEpa.toFixed(1)}`;
    updateModalBar("modal-bar-epa-red", "modal-bar-epa-blue", redEpa, blueEpa);
    renderModalSpotlight("modal-spotlight-epa-winner", "modal-spotlight-epa-subtext", redEpa, blueEpa, "EPA");

    // OPR Comparison Bar
    const redOpr = red.totalOpr;
    const blueOpr = blue.totalOpr;
    document.getElementById("modal-lbl-opr-red").textContent = `Red: ${redOpr.toFixed(1)}`;
    document.getElementById("modal-lbl-opr-blue").textContent = `Blue: ${blueOpr.toFixed(1)}`;
    updateModalBar("modal-bar-opr-red", "modal-bar-opr-blue", redOpr, blueOpr);
    renderModalSpotlight("modal-spotlight-opr-winner", "modal-spotlight-opr-subtext", redOpr, blueOpr, "OPR");

    // Red/Blue Alliance totals
    document.getElementById("modal-red-total-scouted").textContent = `${redScouted.toFixed(1)} pts`;
    document.getElementById("modal-blue-total-scouted").textContent = `${blueScouted.toFixed(1)} pts`;

    // Render team lists
    renderModalTeamList("modal-red-team-list", red.teams);
    renderModalTeamList("modal-blue-team-list", blue.teams);
}

function updateModalBar(redBarId, blueBarId, redVal, blueVal) {
    const total = redVal + blueVal;
    const redBar = document.getElementById(redBarId);
    const blueBar = document.getElementById(blueBarId);

    if (!redBar || !blueBar) return;

    if (total === 0) {
        redBar.style.width = "50%";
        blueBar.style.width = "50%";
    } else {
        const redPercent = (redVal / total) * 100;
        const bluePercent = (blueVal / total) * 100;
        redBar.style.width = `${redPercent}%`;
        blueBar.style.width = `${bluePercent}%`;
    }
}

function renderModalSpotlight(winnerId, subtextId, redVal, blueVal, label) {
    const nameEl = document.getElementById(winnerId);
    const subEl = document.getElementById(subtextId);

    if (!nameEl || !subEl) return;

    nameEl.className = "winner-name"; // reset
    if (redVal === 0 && blueVal === 0) {
        nameEl.textContent = t('predictor.no_data', "No Data");
        nameEl.classList.add("winner-draw");
        subEl.textContent = `Insufficient ${label} entries for predictions.`;
        return;
    }

    const diff = Math.abs(redVal - blueVal);
    if (redVal > blueVal) {
        nameEl.textContent = t('predictor.red_alliance', "Red Alliance");
        nameEl.classList.add("winner-red");
        subEl.textContent = `Predicted to win by ${diff.toFixed(1)} ${label.toLowerCase() === 'scouted' ? 'pts' : 'units'}`;
    } else if (blueVal > redVal) {
        nameEl.textContent = t('predictor.blue_alliance', "Blue Alliance");
        nameEl.classList.add("winner-blue");
        subEl.textContent = `Predicted to win by ${diff.toFixed(1)} ${label.toLowerCase() === 'scouted' ? 'pts' : 'units'}`;
    } else {
        nameEl.textContent = t('predictor.dead_heat', "Dead Heat / Draw");
        nameEl.classList.add("winner-draw");
        subEl.textContent = `Alliances have identical combined ${label} values.`;
    }
}

function renderModalTeamList(listId, teams) {
    const list = document.getElementById(listId);
    if (!list) return;
    list.innerHTML = "";

    teams.forEach((t) => {
        const row = document.createElement("div");
        row.className = "team-row-predictor";

        let primaryLabel = t.averageScoutedScore !== null 
            ? `${t.averageScoutedScore.toFixed(1)} pts` 
            : "No scouted data";
        
        let metricsHtml = `<span class="metric-pill">${t.scoutedMatchesCount} matches</span>`;
        if (t.epa !== null) {
            metricsHtml += `<span class="metric-pill">EPA: ${t.epa.toFixed(1)}</span>`;
        }
        if (t.opr !== null) {
            metricsHtml += `<span class="metric-pill">OPR: ${t.opr.toFixed(1)}</span>`;
        }

        const displayNum = (window.Obsidianscout && typeof Obsidianscout.formatTeam === 'function')
            ? Obsidianscout.formatTeam(t.teamKey, t.teamNumber)
            : t.teamNumber;
            
        let warnIcon = "";
        if (t.hasDiscrepancy) {
            warnIcon = `<span style="color: #eab308; margin-left: 6px; font-weight: bold; cursor: help;" title="Discrepancy warning: Partner teams disagree on scouting values for this team.">⚠️</span>`;
        }

        row.innerHTML = `
            <div class="team-info-main">
                <div style="display: flex; align-items: center;"><span class="team-number-badge">${displayNum}</span>${warnIcon}</div>
                <span class="team-nickname" style="margin-top: 4px;">${t.nickname || ''}</span>
            </div>
            <div style="text-align: right;">
                <div style="font-weight: 700; font-size: 0.95rem;">${primaryLabel}</div>
                <div class="metric-pill-container" style="justify-content: flex-end;">
                    ${metricsHtml}
                </div>
            </div>
        `;
        list.appendChild(row);
    });
}
