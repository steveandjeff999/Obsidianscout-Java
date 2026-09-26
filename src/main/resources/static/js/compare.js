document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    function t(key, fallback) {
        return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : (fallback || key);
    }

    const state = {
        settings: null,
        currentEventKey: "",
        teams: [],
        teamsByNumber: new Map()
    };

    const eventSelect = document.getElementById("event-select");
    const team1Select = document.getElementById("team-1-select");
    const team2Select = document.getElementById("team-2-select");
    const team3Select = document.getElementById("team-3-select");
    const resultsContainer = document.getElementById("compare-results-container");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        state.settings = settingsResponse ? (settingsResponse.settings || settingsResponse) : {};
        state.currentEventKey = Obsidianscout.resolveEventKey(state.settings);

        // Load events
        const events = await Obsidianscout.request(`/api/events?year=${state.settings.year || new Date().getFullYear()}&cached=1`);
        if (eventSelect && Array.isArray(events)) {
            eventSelect.innerHTML = "";
            const allOpt = document.createElement("option");
            allOpt.value = "";
            allOpt.textContent = t("compare.select_event", "All Events (Prescout)");
            eventSelect.appendChild(allOpt);

            events.forEach(ev => {
                const opt = document.createElement("option");
                opt.value = ev.eventKey;
                opt.textContent = `${ev.year} ${ev.name || ev.eventCode}`;
                if (ev.eventKey === state.currentEventKey) opt.selected = true;
                eventSelect.appendChild(opt);
            });
        }

        await loadTeamsForEvent(state.currentEventKey);

        // URL query parameter deep linking
        const urlParams = new URLSearchParams(window.location.search);
        const pTeam1 = urlParams.get("team1") || urlParams.get("t1");
        const pTeam2 = urlParams.get("team2") || urlParams.get("t2");
        const pTeam3 = urlParams.get("team3") || urlParams.get("t3");
        const pEvent = urlParams.get("eventKey");

        if (pEvent !== null && eventSelect) {
            eventSelect.value = pEvent;
            state.currentEventKey = pEvent;
            await loadTeamsForEvent(state.currentEventKey);
        }

        if (pTeam1 && team1Select) team1Select.value = pTeam1;
        if (pTeam2 && team2Select) team2Select.value = pTeam2;
        if (pTeam3 && team3Select) team3Select.value = pTeam3;

        if (team1Select.value && team2Select.value) {
            runComparison();
        }

        // Change listeners
        if (eventSelect) {
            eventSelect.addEventListener("change", async (e) => {
                state.currentEventKey = e.target.value;
                await loadTeamsForEvent(state.currentEventKey);
                runComparison();
            });
        }

        [team1Select, team2Select, team3Select].forEach(sel => {
            if (sel) sel.addEventListener("change", runComparison);
        });

    } catch (e) {
        console.error("Failed to initialize compare page:", e);
        if (resultsContainer) {
            resultsContainer.innerHTML = `<div class="card error">Failed to load comparison data: ${Obsidianscout.escapeHtml(e.message)}</div>`;
        }
    }

    async function loadTeamsForEvent(eventKey) {
        try {
            const url = eventKey ? `/api/teams?eventKey=${encodeURIComponent(eventKey)}` : "/api/teams";
            const teamList = await Obsidianscout.request(url) || [];
            state.teams = teamList;
            state.teamsByNumber.clear();
            teamList.forEach(t => state.teamsByNumber.set(t.teamNumber, t));

            populateTeamSelects(teamList);
        } catch (e) {
            console.warn("Failed to load teams:", e);
        }
    }

    function populateTeamSelects(teamList) {
        const sorted = teamList.slice().sort((a, b) => a.teamNumber - b.teamNumber);

        [team1Select, team2Select, team3Select].forEach((sel, idx) => {
            if (!sel) return;
            const currentVal = sel.value;
            sel.innerHTML = idx === 2 ? `<option value="">${t("compare.none_compare_2", "None (Compare 2)")}</option>` : `<option value="">${t(`compare.select_team_${idx + 1}`, `Select Team ${idx + 1}...`)}</option>`;

            sorted.forEach(t => {
                const opt = document.createElement("option");
                opt.value = t.teamNumber;
                opt.textContent = `${t.teamNumber} - ${t.nickname || t.name || ''}`;
                sel.appendChild(opt);
            });

            if (currentVal) sel.value = currentVal;
        });
    }

    async function runComparison() {
        const t1 = team1Select?.value ? parseInt(team1Select.value, 10) : null;
        const t2 = team2Select?.value ? parseInt(team2Select.value, 10) : null;
        const t3 = team3Select?.value ? parseInt(team3Select.value, 10) : null;

        const teams = [t1, t2, t3].filter(t => t !== null && !isNaN(t));
        if (teams.length < 2) {
            resultsContainer.innerHTML = `
                <div class="card" style="text-align: center; color: var(--muted); padding: 48px 16px;" data-i18n="compare.select_at_least_two">
                    ${t("compare.select_at_least_two", "Select at least two teams above to view a side-by-side comparison.")}
                </div>
            `;
            return;
        }

        Obsidianscout.showLoadingSpinner(resultsContainer, t("compare.comparing_teams", "Comparing teams..."));

        try {
            const eventQuery = state.currentEventKey ? `&eventKey=${encodeURIComponent(state.currentEventKey)}` : "";
            const response = await Obsidianscout.request(`/api/analytics/compare?teams=${teams.join(",")}${eventQuery}`);

            renderComparisonResults(teams, response || {});
        } catch (e) {
            resultsContainer.innerHTML = `<div class="card error">Error comparing teams: ${Obsidianscout.escapeHtml(e.message)}</div>`;
        }
    }

    function renderComparisonResults(selectedTeams, compResponse) {
        resultsContainer.innerHTML = "";

        const teamsData = compResponse.teams || {};
        const metricDefs = compResponse.metricDefinitions || [];

        // 1. Team Hero Header Cards
        const heroRow = document.createElement("div");
        heroRow.style.cssText = `display: grid; grid-template-columns: repeat(${selectedTeams.length}, 1fr); gap: 16px; margin-bottom: 20px;`;

        const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function') 
            ? Obsidianscout.getProgram() === "FTC" 
            : (state.settings && state.settings.program === "FTC");
        const effectiveUseEpa = !isFtc && state.settings?.useStatboticsEpa;
        const effectiveUseExp = !isFtc && state.settings?.useMatch13Exp;
        const effectiveUseOpr = state.settings?.useTbaOpr;

        selectedTeams.forEach(tNum => {
            const teamObj = state.teamsByNumber.get(tNum) || {};
            const tData = teamsData[tNum.toString()] || {};
            const card = document.createElement("div");
            card.className = "team-hero-card";

            const countBadge = (tData.matchesScouted !== undefined)
                ? `<div style="font-size: 0.8rem; margin-top: 8px; color: var(--muted);"><span style="background: var(--surface-3, rgba(255,255,255,0.06)); padding: 2px 8px; border-radius: 999px;">${tData.matchesScouted} matches</span></div>`
                : "";

            const epaBadge = (effectiveUseEpa && tData.epa != null)
                ? `<span style="font-size: 0.75rem; background: rgba(56,189,248,0.15); color: #38bdf8; padding: 2px 6px; border-radius: 4px; margin-right: 4px;">EPA ${tData.epa.toFixed(1)}</span>`
                : "";
            const expBadge = (effectiveUseExp && tData.exp != null)
                ? `<span style="font-size: 0.75rem; background: rgba(16,185,129,0.15); color: #10b981; padding: 2px 6px; border-radius: 4px; margin-right: 4px;">EXP ${tData.exp.toFixed(1)}</span>`
                : "";
            const oprBadge = (effectiveUseOpr && tData.opr != null)
                ? `<span style="font-size: 0.75rem; background: rgba(168,85,247,0.15); color: #c084fc; padding: 2px 6px; border-radius: 4px;">OPR ${tData.opr.toFixed(1)}</span>`
                : "";

            card.innerHTML = `
                <div class="team-hero-num">${tNum}</div>
                <div class="team-hero-name">${Obsidianscout.escapeHtml(tData.nickname || teamObj.nickname || teamObj.name || `Team ${tNum}`)}</div>
                ${(epaBadge || expBadge || oprBadge) ? `<div style="margin-top: 6px;">${epaBadge}${expBadge}${oprBadge}</div>` : ""}
                ${countBadge}
            `;
            heroRow.appendChild(card);
        });
        resultsContainer.appendChild(heroRow);

        if (metricDefs.length === 0) {
            const emptyCard = document.createElement("div");
            emptyCard.className = "card";
            emptyCard.style.textAlign = "center";
            emptyCard.style.color = "var(--muted)";
            emptyCard.style.padding = "32px 16px";
            emptyCard.textContent = t("compare.no_data", "No scouting data available for these teams at this event.");
            resultsContainer.appendChild(emptyCard);
            return;
        }

        // Group metrics by phase
        const phaseGroups = [
            { id: "overview", title: t("compare.overview", "Performance Overview"), filter: m => m.phase === "overview" },
            { id: "auto", title: t("compare.avg_auto", "Autonomous Phase"), filter: m => m.phase === "auto" },
            { id: "teleop", title: t("compare.avg_teleop", "Teleoperated Phase"), filter: m => m.phase === "teleop" },
            { id: "endgame", title: t("compare.avg_endgame", "Endgame Phase"), filter: m => m.phase === "endgame" },
            { id: "other", title: t("compare.breakdown", "Additional Elements"), filter: m => !["overview", "auto", "teleop", "endgame", "custom"].includes(m.phase) },
            { id: "custom", title: "Custom Analytics", filter: m => m.phase === "custom" }
        ];

        phaseGroups.forEach(group => {
            const groupMetrics = metricDefs.filter(group.filter);
            if (groupMetrics.length === 0) return;

            const sectionWrapper = document.createElement("div");
            sectionWrapper.style.marginBottom = "24px";

            const sectionHeading = document.createElement("h3");
            sectionHeading.style.cssText = "font-size: 1.05rem; font-weight: 700; margin-bottom: 8px; color: var(--text-color, #f8fafc);";
            sectionHeading.textContent = group.title;
            sectionWrapper.appendChild(sectionHeading);

            const tableCard = document.createElement("div");
            tableCard.className = "card";
            tableCard.style.padding = "0";
            tableCard.style.overflow = "hidden";

            const table = document.createElement("table");
            table.className = "comparison-table";

            // Head
            let theadHtml = `<thead><tr><th>${t("compare.metric", "Metric")}</th>`;
            selectedTeams.forEach(tNum => {
                theadHtml += `<th style="text-align: right;">Team ${tNum}</th>`;
            });
            theadHtml += `</tr></thead>`;
            table.innerHTML = theadHtml;

            const tbody = document.createElement("tbody");

            groupMetrics.forEach(m => {
                const tr = document.createElement("tr");

                const tdMetric = document.createElement("td");
                const unitSuffix = m.unit ? ` <span style="font-size: 0.75rem; color: var(--muted);">(${Obsidianscout.escapeHtml(m.unit)})</span>` : "";
                tdMetric.innerHTML = `<strong>${Obsidianscout.escapeHtml(m.title)}</strong>${unitSuffix}`;
                tr.appendChild(tdMetric);

                // Collect values across teams
                const values = selectedTeams.map(tNum => {
                    const tData = teamsData[tNum.toString()];
                    if (!tData) return null;
                    if (m.phase === "custom") {
                        const rawWidgetId = m.id.replace(/^custom_/, "");
                        const foundW = (tData.widgets || []).find(w => w.id === rawWidgetId);
                        return foundW?.value != null ? foundW.value : null;
                    }
                    if (tData.metrics && tData.metrics[m.id] !== undefined) {
                        return tData.metrics[m.id];
                    }
                    return null;
                });

                const validNumericVals = values.filter(v => v !== null && !isNaN(v));
                const maxVal = validNumericVals.length > 0 ? Math.max(...validNumericVals) : null;

                selectedTeams.forEach((tNum, idx) => {
                    const tdVal = document.createElement("td");
                    tdVal.style.textAlign = "right";

                    const val = values[idx];
                    const tData = teamsData[tNum.toString()];

                    let series = null;
                    if (m.type === "bar") {
                        if (m.phase === "custom") {
                            const rawWidgetId = m.id.replace(/^custom_/, "");
                            const foundW = (tData?.widgets || []).find(w => w.id === rawWidgetId);
                            series = foundW?.series || [];
                        } else if (tData?.metricSeries && tData.metricSeries[m.id]) {
                            series = tData.metricSeries[m.id];
                        }
                    }

                    if (m.type === "bar" && series) {
                        if (series.length > 0) {
                            const seriesHtml = series.map(s => `<div><span style="color:var(--muted);">${Obsidianscout.escapeHtml(s.label)}:</span> <strong>${s.value}</strong></div>`).join("");
                            tdVal.innerHTML = `<div style="font-size:0.85rem;">${seriesHtml}</div>`;
                        } else {
                            tdVal.innerHTML = `<span style="color: var(--muted);">--</span>`;
                        }
                    } else if (val != null) {
                        let formatted = Number.isInteger(val) ? val : val.toFixed(2);
                        if (m.type === "percentage") {
                            formatted = `${val.toFixed(1)}%`;
                        }
                        const isBest = validNumericVals.length > 1 && maxVal !== null && val === maxVal && maxVal > 0 && m.id !== "matches_scouted";
                        tdVal.innerHTML = `<span class="${isBest ? 'best-val' : ''}">${formatted}</span>`;
                    } else {
                        tdVal.innerHTML = `<span style="color: var(--muted);">--</span>`;
                    }
                    tr.appendChild(tdVal);
                });

                tbody.appendChild(tr);
            });

            table.appendChild(tbody);
            tableCard.appendChild(table);
            sectionWrapper.appendChild(tableCard);
            resultsContainer.appendChild(sectionWrapper);
        });
    }
});

