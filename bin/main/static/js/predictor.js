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

    const settingsResponse = await Obsidianscout.request("/api/settings");
    const settings = settingsResponse.settings;
    const currentEventKey = Obsidianscout.resolveEventKey(settings);

    const matchSelect = document.getElementById("match-select");
    const workspace = document.getElementById("predictor-workspace");
    const emptyState = document.getElementById("predictor-empty-state");

    if (!currentEventKey) {
        matchSelect.innerHTML = '<option value="">No event configured in settings</option>';
        return;
    }

    try {
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
                return;
            }

            try {
                workspace.classList.add("hidden");
                const prediction = await Obsidianscout.request(`/api/matches/predict?matchKey=${matchKey}`);
                renderPrediction(prediction);
                emptyState.classList.add("hidden");
                workspace.classList.remove("hidden");
            } catch (err) {
                console.error("Prediction fetch failed", err);
                Obsidianscout.showToast(err.message || "Failed to load prediction details", "error");
            }
        });
    } catch (err) {
        console.error("Matches load failed", err);
        matchSelect.innerHTML = '<option value="">Failed to load matches</option>';
    }
});

function renderPrediction(data) {
    const red = data.redAlliance;
    const blue = data.blueAlliance;

    // 1. Scouted Score Comparison Bar
    const redScouted = red.totalScoutedScore;
    const blueScouted = blue.totalScoutedScore;
    document.getElementById("lbl-scouted-red").textContent = `Red: ${redScouted.toFixed(1)} pts`;
    document.getElementById("lbl-scouted-blue").textContent = `Blue: ${blueScouted.toFixed(1)} pts`;
    updateBar("bar-scouted-red", "bar-scouted-blue", redScouted, blueScouted);

    // Scouted spotlight
    renderSpotlight(
        "spotlight-scouted-winner",
        "spotlight-scouted-subtext",
        redScouted,
        blueScouted,
        "Scouted"
    );

    // 2. EPA Comparison Bar
    const epaCard = document.getElementById("spotlight-epa-card");
    const epaComp = document.getElementById("epa-comparison");
    if (data.useStatboticsEpa) {
        epaComp.classList.remove("hidden");
        epaCard.classList.remove("hidden");
        const redEpa = red.totalEpa;
        const blueEpa = blue.totalEpa;
        document.getElementById("lbl-epa-red").textContent = `Red: ${redEpa.toFixed(1)}`;
        document.getElementById("lbl-epa-blue").textContent = `Blue: ${blueEpa.toFixed(1)}`;
        updateBar("bar-epa-red", "bar-epa-blue", redEpa, blueEpa);
        renderSpotlight("spotlight-epa-winner", "spotlight-epa-subtext", redEpa, blueEpa, "EPA");
    } else {
        epaComp.classList.add("hidden");
        epaCard.classList.add("hidden");
    }

    // 3. OPR Comparison Bar
    const oprCard = document.getElementById("spotlight-opr-card");
    const oprComp = document.getElementById("opr-comparison");
    if (data.useTbaOpr) {
        oprComp.classList.remove("hidden");
        oprCard.classList.remove("hidden");
        const redOpr = red.totalOpr;
        const blueOpr = blue.totalOpr;
        document.getElementById("lbl-opr-red").textContent = `Red: ${redOpr.toFixed(1)}`;
        document.getElementById("lbl-opr-blue").textContent = `Blue: ${blueOpr.toFixed(1)}`;
        updateBar("bar-opr-red", "bar-opr-blue", redOpr, blueOpr);
        renderSpotlight("spotlight-opr-winner", "spotlight-opr-subtext", redOpr, blueOpr, "OPR");
    } else {
        oprComp.classList.add("hidden");
        oprCard.classList.add("hidden");
    }

    // 4. Alliance Total Badges
    document.getElementById("red-total-scouted").textContent = `${redScouted.toFixed(1)} pts`;
    document.getElementById("blue-total-scouted").textContent = `${blueScouted.toFixed(1)} pts`;

    // 5. Build Team Breakdown Lists
    renderTeamList("red-team-list", red.teams);
    renderTeamList("blue-team-list", blue.teams);
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
        nameEl.textContent = "No Data";
        nameEl.classList.add("winner-draw");
        subEl.textContent = `Insufficient ${label} entries for predictions.`;
        return;
    }

    const diff = Math.abs(redVal - blueVal);
    if (redVal > blueVal) {
        nameEl.textContent = "Red Alliance";
        nameEl.classList.add("winner-red");
        subEl.textContent = `Predicted to win by ${diff.toFixed(1)} ${label.toLowerCase() === 'scouted' ? 'pts' : 'units'}`;
    } else if (blueVal > redVal) {
        nameEl.textContent = "Blue Alliance";
        nameEl.classList.add("winner-blue");
        subEl.textContent = `Predicted to win by ${diff.toFixed(1)} ${label.toLowerCase() === 'scouted' ? 'pts' : 'units'}`;
    } else {
        nameEl.textContent = "Dead Heat / Draw";
        nameEl.classList.add("winner-draw");
        subEl.textContent = `Alliances have identical combined ${label} values.`;
    }
}

function renderTeamList(listId, teams) {
    const list = document.getElementById(listId);
    list.innerHTML = "";

    teams.forEach((t) => {
        const row = document.createElement("div");
        row.className = "team-row-predictor";

        const scoutedLabel = t.averageScoutedScore !== null 
            ? `${t.averageScoutedScore.toFixed(1)} pts` 
            : "No scouted data";

        let metricsHtml = `<span class="metric-pill">${t.scoutedMatchesCount} matches</span>`;
        if (t.epa !== null) {
            metricsHtml += `<span class="metric-pill">EPA: ${t.epa.toFixed(1)}</span>`;
        }
        if (t.opr !== null) {
            metricsHtml += `<span class="metric-pill">OPR: ${t.opr.toFixed(1)}</span>`;
        }

        const displayNum = Obsidianscout.formatTeam(t.teamKey, t.teamNumber);
        row.innerHTML = `
            <div class="team-info-main">
                <div><span class="team-number-badge">${displayNum}</span></div>
                <span class="team-nickname" style="margin-top: 4px;">${t.nickname || ''}</span>
            </div>
            <div style="text-align: right;">
                <div style="font-weight: 700; font-size: 0.95rem;">${scoutedLabel}</div>
                <div class="metric-pill-container" style="justify-content: flex-end;">
                    ${metricsHtml}
                </div>
            </div>
        `;
        list.appendChild(row);
    });
}
