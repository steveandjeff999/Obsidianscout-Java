let currentUser = null;
let currentEventKey = "";

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

    await initProjectedRankingsPage();
});

async function initProjectedRankingsPage() {
    const body = document.querySelector("#projection-table tbody");
    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        currentEventKey = Obsidianscout.resolveEventKey(settings);

        const eventFilter = document.getElementById("event-filter");
        const events = await Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`);
        eventFilter.innerHTML = "";
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
        if (!currentEventInList) {
            // No configured event yet: fall back to the first listed one.
            currentEventKey = currentEventKey || eventFilter.value || "";
        }
        eventFilter.addEventListener("change", () => {
            currentEventKey = eventFilter.value;
            loadProjection();
        });

        // Re-run draws a fresh set of simulations; other loads use the stable default.
        document.getElementById("projection-run-btn").addEventListener("click", () => loadProjection({ fresh: true }));
        document.getElementById("projection-sims").addEventListener("change", () => loadProjection());

        loadProjection();
    } catch (error) {
        console.error("Failed to load projected rankings page:", error);
        body.innerHTML = "";
        const row = document.createElement("tr");
        const cell = document.createElement("td");
        cell.colSpan = 11;
        cell.style.cssText = "text-align: center; color: var(--muted); padding: 24px;";
        cell.textContent = "Failed to load: " + error.message;
        row.appendChild(cell);
        body.appendChild(row);
    }
}

let projectionRequestId = 0;

async function loadProjection({ fresh = false } = {}) {
    const table = document.getElementById("projection-table");
    if (!table) return;
    const body = table.querySelector("tbody");
    const summary = document.getElementById("projection-summary");
    const runBtn = document.getElementById("projection-run-btn");

    if (!currentEventKey) {
        body.innerHTML = '<tr><td colspan="11" style="text-align: center; color: var(--muted); padding: 24px;">No event selected</td></tr>';
        return;
    }

    const requestId = ++projectionRequestId;
    const simsSelect = document.getElementById("projection-sims");
    const simulations = simsSelect ? simsSelect.value : "2000";
    const seedParam = fresh ? `&seed=${Math.floor(Math.random() * 2147483647)}` : "";
    body.innerHTML = '<tr><td colspan="11" style="text-align: center; padding: 24px;"><div class="spinner" style="margin: 0 auto 12px; width: 32px; height: 32px;"></div><div>Simulating remaining matches...</div></td></tr>';
    if (runBtn) runBtn.disabled = true;

    try {
        const data = await Obsidianscout.request(
            `/api/matches/projected-rankings?eventKey=${encodeURIComponent(currentEventKey)}&simulations=${encodeURIComponent(simulations)}${seedParam}`
        );
        if (requestId !== projectionRequestId) return;
        renderProjection(data);
    } catch (error) {
        if (requestId !== projectionRequestId) return;
        console.error("Failed to load ranking projection:", error);
        body.innerHTML = "";
        const row = document.createElement("tr");
        const cell = document.createElement("td");
        cell.colSpan = 11;
        cell.style.cssText = "text-align: center; color: var(--muted); padding: 24px;";
        cell.textContent = "Failed to project rankings: " + error.message;
        row.appendChild(cell);
        body.appendChild(row);
        if (summary) summary.textContent = "Simulates the remaining qualification matches to estimate where each team will finish.";
    } finally {
        if (requestId === projectionRequestId && runBtn) runBtn.disabled = false;
    }
}

function renderProjection(data) {
    const body = document.querySelector("#projection-table tbody");
    const summary = document.getElementById("projection-summary");
    const topHeader = document.getElementById("projection-top-header");
    const esc = Obsidianscout.escapeHtml;
    body.innerHTML = "";

    if (topHeader) {
        topHeader.textContent = `Top ${data.captainSlots}`;
        topHeader.title = `Chance of finishing in the top ${data.captainSlots} (alliance captain spots)`;
    }

    const runBtn = document.getElementById("projection-run-btn");
    const simsSelect = document.getElementById("projection-sims");
    // Nothing left to simulate once qualifications are over.
    if (runBtn) runBtn.style.display = data.qualsComplete ? "none" : "";
    if (simsSelect) simsSelect.style.display = data.qualsComplete ? "none" : "";
    const rankHeader = document.getElementById("projection-rank-header");
    if (rankHeader) {
        rankHeader.textContent = data.qualsComplete ? "Final Rank" : "Proj. Rank";
        rankHeader.title = data.qualsComplete
            ? "Final qualification rank"
            : "Projected final standing, ordered by average simulated finish";
    }

    if (summary) {
        const parts = [];
        if (data.qualsComplete && data.qualMatchesTotal > 0) {
            parts.push(`Qualifications are complete (${data.qualMatchesTotal} matches). These are the final standings, not a projection`);
            parts.push(data.rpSource === "official"
                ? "ranking points from official results"
                : "ranking points estimated from wins (bonus RP not included), so check official rankings for exact order");
        } else if (data.qualMatchesTotal > 0) {
            parts.push(`${data.qualMatchesPlayed} of ${data.qualMatchesTotal} qualification matches played`);
            if (data.simulations > 0 && data.qualMatchesPlayed < data.qualMatchesTotal) {
                parts.push(`${data.simulations.toLocaleString()} simulations`);
            }
            parts.push(data.rpSource === "official"
                ? `ranking points from official results (${data.winRp} RP per win)`
                : `ranking points estimated from wins (${data.winRp} RP per win, bonus RP not included)`);
            parts.push(`team strength from ${data.strengthMetrics.join(" + ")}`);
            parts.push("Re-run draws a new set of simulations, so small changes between runs are normal");
        }
        if (data.note) parts.push(data.note);
        summary.textContent = parts.length ? parts.join(" · ") + "." : "Simulates the remaining qualification matches to estimate where each team will finish.";
    }

    if (!data.teams || data.teams.length === 0) {
        body.innerHTML = `<tr><td colspan="11" style="text-align: center; color: var(--muted); padding: 24px;">${esc(data.note || "No qualification matches to project.")}</td></tr>`;
        return;
    }

    const myTeam = currentUser ? Number(currentUser.teamNumber) : null;
    // A simulation can't prove certainty, so only show 100% / 0% once qualifications are over.
    const pct = (p) => {
        if (data.qualsComplete) return `${Math.round(p * 100)}%`;
        if (p >= 0.995) return ">99%";
        if (p < 0.005) return "<1%";
        return `${Math.round(p * 100)}%`;
    };

    data.teams.forEach((team, idx) => {
        // Teams arrive ordered by average simulated finish; that order is the projected standing.
        const projected = idx + 1;
        const row = document.createElement("tr");
        if (myTeam && team.teamNumber === myTeam) row.className = "projection-own-team";

        let trend = "";
        if (team.currentRank) {
            const delta = team.currentRank - projected;
            if (delta > 0) trend = ` <span class="projection-trend up" title="Projected to rise ${delta} from current rank">&#9650;${delta}</span>`;
            else if (delta < 0) trend = ` <span class="projection-trend down" title="Projected to drop ${-delta} from current rank">&#9660;${-delta}</span>`;
        }

        const range = team.bestLikelyRank === team.worstLikelyRank
            ? `${team.bestLikelyRank}`
            : `${team.bestLikelyRank}–${team.worstLikelyRank}`;
        const record = team.matchesPlayed > 0 ? `${team.wins}-${team.losses}-${team.ties}` : "–";
        const rsNow = team.currentRankingScore !== null && team.currentRankingScore !== undefined
            ? team.currentRankingScore.toFixed(2)
            : "–";
        const displayNum = Obsidianscout.formatTeam(team.teamKey, team.teamNumber);
        const link = `/team?teamNumber=${encodeURIComponent(team.teamNumber)}&eventKey=${encodeURIComponent(currentEventKey)}`;

        row.innerHTML = `
            <td title="Median simulated rank ${team.medianRank}, average ${team.projectedRank.toFixed(1)}"><strong>${projected}</strong>${trend}</td>
            <td><a href="${link}" class="team-profile-link">${esc(displayNum)}</a></td>
            <td><a href="${link}" class="team-profile-link">${esc(team.nickname || "")}</a></td>
            <td>${team.currentRank || "–"}</td>
            <td>${record}</td>
            <td>${rsNow}</td>
            <td title="${team.expectedFinalRp.toFixed(1)} total RP expected">${team.expectedRankingScore.toFixed(2)}</td>
            <td>${range}</td>
            <td>${projectionBar(team.probTopSeeds, pct(team.probTopSeeds))}</td>
            <td>${pct(team.probFirst)}</td>
            <td>${team.matchesRemaining}</td>
        `;
        body.appendChild(row);
    });
}

function projectionBar(probability, label) {
    const width = Math.max(0, Math.min(100, Math.round(probability * 100)));
    return `<span class="projection-bar" title="${label}"><span class="projection-bar-fill" style="width: ${width}%"></span></span><span class="projection-bar-label">${label}</span>`;
}
