
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

let originalMainContentHTML = "";
let mainContentWrapper = null;
let mainContent = null;
let currentPrediction = null;
let currentSettings = null;

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

    mainContent = document.querySelector(".main-content");
    if (mainContent) {
        const siblings = Array.from(mainContent.children);
        mainContentWrapper = document.createElement("div");
        mainContentWrapper.id = "predictor-wrapper";
        siblings.forEach(child => mainContentWrapper.appendChild(child));
        mainContent.appendChild(mainContentWrapper);
        originalMainContentHTML = mainContentWrapper.innerHTML;
        await loadPredictorData();
    }
});

async function loadPredictorData() {
    if (!mainContentWrapper) return;
    Obsidianscout.showLoadingSpinner(mainContentWrapper, "Loading predictor data...");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        currentSettings = settings;
        const currentEventKey = Obsidianscout.resolveEventKey(settings);

        mainContentWrapper.innerHTML = originalMainContentHTML;

        const matchSelect = document.getElementById("match-select");
        const workspace = document.getElementById("predictor-workspace");
        const emptyState = document.getElementById("predictor-empty-state");

        const datasourceSelect = document.getElementById("datasource-select");
        const datasourceField = document.getElementById("datasource-field");

        if (settings.useStatboticsEpa || settings.useTbaOpr) {
            datasourceField.classList.remove("hidden");
            datasourceSelect.innerHTML = "";

            if (settings.useStatboticsEpa && settings.useTbaOpr) {
                const optAll = document.createElement("option");
                optAll.value = "all";
                optAll.textContent = t('predictor.all_3', "All 3");
                datasourceSelect.appendChild(optAll);
            }

            const optScouted = document.createElement("option");
            optScouted.value = "scouted";
            optScouted.textContent = t('predictor.scouted_data', "Scouted Data");
            datasourceSelect.appendChild(optScouted);

            if (settings.useStatboticsEpa) {
                const optEpa = document.createElement("option");
                optEpa.value = "epa";
                optEpa.textContent = t('predictor.statbotics_epa', "Statbotics EPA");
                datasourceSelect.appendChild(optEpa);
            }

            if (settings.useTbaOpr) {
                const optOpr = document.createElement("option");
                optOpr.value = "opr";
                optOpr.textContent = t('predictor.tba_opr', "TBA OPR");
                datasourceSelect.appendChild(optOpr);
            }

            datasourceSelect.addEventListener("change", () => {
                if (currentPrediction) {
                    renderPrediction(currentPrediction);
                }
            });
        } else {
            datasourceField.classList.add("hidden");
        }

        if (!currentEventKey) {
            matchSelect.innerHTML = '<option value="">No event configured in settings</option>';
            return;
        }

        const matches = await Obsidianscout.request(`/api/matches?eventKey=${currentEventKey}`);
        matchSelect.innerHTML = '<option value="">-- Choose a Match --</option>';
        if (matches.length === 0) {
            matchSelect.innerHTML = '<option value="">No matches found for event</option>';
            return;
        }

        matches.forEach((match) => {
            const opt = document.createElement("option");
            opt.value = match.matchKey;
            opt.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? (Obsidianscout.localize(match.label) || `${match.compLevel.toUpperCase()} ${match.matchNumber || ""}`) : (match.label || `${match.compLevel.toUpperCase()} ${match.matchNumber || ""}`);
            matchSelect.appendChild(opt);
        });

        matchSelect.addEventListener("change", async () => {
            const matchKey = matchSelect.value;
            if (!matchKey) {
                workspace.classList.add("hidden");
                emptyState.classList.remove("hidden");
                currentPrediction = null;
                return;
            }

            try {
                workspace.classList.add("hidden");
                const prediction = await Obsidianscout.request(`/api/matches/predict?matchKey=${matchKey}`);
                currentPrediction = prediction;
                renderPrediction(prediction);
                emptyState.classList.add("hidden");
                workspace.classList.remove("hidden");
            } catch (err) {
                console.error("Prediction fetch failed", err);
                Obsidianscout.showToast(err.message || "Failed to load prediction details", "error");
            }
        });
    } catch (error) {
        console.error("Failed to load predictor data:", error);
        Obsidianscout.showRetryButton(mainContentWrapper, "Failed to load predictor data: " + error.message, loadPredictorData);
    }
}

function renderPrediction(data) {
    const red = data.redAlliance;
    const blue = data.blueAlliance;

    const datasourceSelect = document.getElementById("datasource-select");
    const selectedSource = (currentSettings && (currentSettings.useStatboticsEpa || currentSettings.useTbaOpr) && datasourceSelect) 
        ? datasourceSelect.value 
        : "scouted";

    const scoutedComp = document.getElementById("scouted-comparison");
    const epaComp = document.getElementById("epa-comparison");
    const oprComp = document.getElementById("opr-comparison");

    const scoutedCard = document.getElementById("spotlight-scouted-card");
    const epaCard = document.getElementById("spotlight-epa-card");
    const oprCard = document.getElementById("spotlight-opr-card");

    // 1. Scouted Score Comparison Bar
    const redScouted = red.totalScoutedScore;
    const blueScouted = blue.totalScoutedScore;
    document.getElementById("lbl-scouted-red").textContent = `Red: ${redScouted.toFixed(1)} pts`;
    document.getElementById("lbl-scouted-blue").textContent = `Blue: ${blueScouted.toFixed(1)} pts`;
    updateBar("bar-scouted-red", "bar-scouted-blue", redScouted, blueScouted);
    renderSpotlight("spotlight-scouted-winner", "spotlight-scouted-subtext", redScouted, blueScouted, "Scouted");

    // 2. EPA Comparison Bar
    const redEpa = red.totalEpa;
    const blueEpa = blue.totalEpa;
    document.getElementById("lbl-epa-red").textContent = `Red: ${redEpa.toFixed(1)}`;
    document.getElementById("lbl-epa-blue").textContent = `Blue: ${blueEpa.toFixed(1)}`;
    updateBar("bar-epa-red", "bar-epa-blue", redEpa, blueEpa);
    renderSpotlight("spotlight-epa-winner", "spotlight-epa-subtext", redEpa, blueEpa, "EPA");

    // 3. OPR Comparison Bar
    const redOpr = red.totalOpr;
    const blueOpr = blue.totalOpr;
    document.getElementById("lbl-opr-red").textContent = `Red: ${redOpr.toFixed(1)}`;
    document.getElementById("lbl-opr-blue").textContent = `Blue: ${blueOpr.toFixed(1)}`;
    updateBar("bar-opr-red", "bar-opr-blue", redOpr, blueOpr);
    renderSpotlight("spotlight-opr-winner", "spotlight-opr-subtext", redOpr, blueOpr, "OPR");

    // Toggle Visibility
    if (selectedSource === "all") {
        scoutedComp.classList.remove("hidden");
        scoutedCard.classList.remove("hidden");
        epaComp.classList.remove("hidden");
        epaCard.classList.remove("hidden");
        oprComp.classList.remove("hidden");
        oprCard.classList.remove("hidden");

        document.getElementById("red-total-scouted").textContent = `${redScouted.toFixed(1)} pts`;
        document.getElementById("blue-total-scouted").textContent = `${blueScouted.toFixed(1)} pts`;
    } else if (selectedSource === "scouted") {
        scoutedComp.classList.remove("hidden");
        scoutedCard.classList.remove("hidden");
        epaComp.classList.add("hidden");
        epaCard.classList.add("hidden");
        oprComp.classList.add("hidden");
        oprCard.classList.add("hidden");

        document.getElementById("red-total-scouted").textContent = `${redScouted.toFixed(1)} pts`;
        document.getElementById("blue-total-scouted").textContent = `${blueScouted.toFixed(1)} pts`;
    } else if (selectedSource === "epa") {
        scoutedComp.classList.add("hidden");
        scoutedCard.classList.add("hidden");
        epaComp.classList.remove("hidden");
        epaCard.classList.remove("hidden");
        oprComp.classList.add("hidden");
        oprCard.classList.add("hidden");

        document.getElementById("red-total-scouted").textContent = `${redEpa.toFixed(1)} EPA`;
        document.getElementById("blue-total-scouted").textContent = `${blueEpa.toFixed(1)} EPA`;
    } else if (selectedSource === "opr") {
        scoutedComp.classList.add("hidden");
        scoutedCard.classList.add("hidden");
        epaComp.classList.add("hidden");
        epaCard.classList.add("hidden");
        oprComp.classList.remove("hidden");
        oprCard.classList.remove("hidden");

        document.getElementById("red-total-scouted").textContent = `${redOpr.toFixed(1)} OPR`;
        document.getElementById("blue-total-scouted").textContent = `${blueOpr.toFixed(1)} OPR`;
    }

    // 5. Build Team Breakdown Lists
    renderTeamList("red-team-list", red.teams, selectedSource);
    renderTeamList("blue-team-list", blue.teams, selectedSource);
}

function updateBar(redBarId, blueBarId, redVal, blueVal) {
    const total = redVal + blueVal;
    const redBar = document.getElementById(redBarId);
    const blueBar = document.getElementById(blueBarId);

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

function renderSpotlight(winnerId, subtextId, redVal, blueVal, label) {
    const nameEl = document.getElementById(winnerId);
    const subEl = document.getElementById(subtextId);

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

function renderTeamList(listId, teams, selectedSource) {
    const list = document.getElementById(listId);
    list.innerHTML = "";

    teams.forEach((t) => {
        const row = document.createElement("div");
        row.className = "team-row-predictor";

        let primaryLabel = "";
        let metricsHtml = "";

        if (selectedSource === "scouted" || selectedSource === "all") {
            primaryLabel = t.averageScoutedScore !== null 
                ? `${t.averageScoutedScore.toFixed(1)} pts` 
                : "No scouted data";
            
            metricsHtml += `<span class="metric-pill">${t.scoutedMatchesCount} matches</span>`;
            if (t.epa !== null) {
                metricsHtml += `<span class="metric-pill">EPA: ${t.epa.toFixed(1)}</span>`;
            }
            if (t.opr !== null) {
                metricsHtml += `<span class="metric-pill">OPR: ${t.opr.toFixed(1)}</span>`;
            }
        } else if (selectedSource === "epa") {
            primaryLabel = t.epa !== null 
                ? `${t.epa.toFixed(1)} EPA` 
                : "No EPA data";
            
            metricsHtml += `<span class="metric-pill">${t.scoutedMatchesCount} matches</span>`;
            if (t.averageScoutedScore !== null) {
                metricsHtml += `<span class="metric-pill">Scouted: ${t.averageScoutedScore.toFixed(1)} pts</span>`;
            }
            if (t.opr !== null) {
                metricsHtml += `<span class="metric-pill">OPR: ${t.opr.toFixed(1)}</span>`;
            }
        } else if (selectedSource === "opr") {
            primaryLabel = t.opr !== null 
                ? `${t.opr.toFixed(1)} OPR` 
                : "No OPR data";
            
            metricsHtml += `<span class="metric-pill">${t.scoutedMatchesCount} matches</span>`;
            if (t.averageScoutedScore !== null) {
                metricsHtml += `<span class="metric-pill">Scouted: ${t.averageScoutedScore.toFixed(1)} pts</span>`;
            }
            if (t.epa !== null) {
                metricsHtml += `<span class="metric-pill">EPA: ${t.epa.toFixed(1)}</span>`;
            }
        }

        const displayNum = Obsidianscout.formatTeam(t.teamKey, t.teamNumber);
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
