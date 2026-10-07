let currentUser = null;

function t(key, fallback, values) {
    const text = (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;
    return values ? String(text).replace(/\{(\w+)\}/g, (_, k) => (values[k] !== undefined ? values[k] : `{${k}}`)) : text;
}
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
        cell.textContent = t("projected.failed_load", "Failed to load: {error}", { error: error.message });
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
        body.innerHTML = `<tr><td colspan="11" style="text-align: center; color: var(--muted); padding: 24px;">${Obsidianscout.escapeHtml(t("projected.no_event", "No event selected"))}</td></tr>`;
        return;
    }

    const requestId = ++projectionRequestId;
    const simsSelect = document.getElementById("projection-sims");
    const simulations = simsSelect ? simsSelect.value : "2000";
    const seedParam = fresh ? `&seed=${Math.floor(Math.random() * 2147483647)}` : "";
    body.innerHTML = `<tr><td colspan="11" style="text-align: center; padding: 24px;"><div class="spinner" style="margin: 0 auto 12px; width: 32px; height: 32px;"></div><div>${Obsidianscout.escapeHtml(t("projected.simulating", "Simulating remaining matches..."))}</div></td></tr>`;
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
        cell.textContent = t("projected.failed_project", "Failed to project rankings: {error}", { error: error.message });
        row.appendChild(cell);
        body.appendChild(row);
        if (summary) summary.textContent = t("projected.notice", "Simulates the remaining qualification matches to estimate where each team will finish.");
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
        topHeader.textContent = t("projected.col.top", "Top {n}", { n: data.captainSlots });
        topHeader.title = t("projected.tip.top", "Chance of finishing in the top {n} (alliance captain spots)", { n: data.captainSlots });
    }

    const runBtn = document.getElementById("projection-run-btn");
    const simsSelect = document.getElementById("projection-sims");
    // Nothing left to simulate once qualifications are over.
    if (runBtn) runBtn.style.display = data.qualsComplete ? "none" : "";
    if (simsSelect) simsSelect.style.display = data.qualsComplete ? "none" : "";
    const rankHeader = document.getElementById("projection-rank-header");
    if (rankHeader) {
        rankHeader.textContent = data.qualsComplete ? t("projected.col.final_rank", "Final Rank") : t("projected.col.proj_rank", "Proj. Rank");
        rankHeader.title = data.qualsComplete
            ? t("projected.tip.final_rank", "Final qualification rank")
            : t("projected.tip.proj_rank", "Projected final standing, ordered by average simulated finish");
    }

    if (summary) {
        const parts = [];
        if (data.qualsComplete && data.qualMatchesTotal > 0) {
            parts.push(t("projected.complete", "Qualifications are complete ({n} matches). These are the final standings, not a projection", { n: data.qualMatchesTotal }));
            parts.push(data.rpSource === "official"
                ? t("projected.rp_official", "ranking points from official results")
                : t("projected.rp_estimated_final", "ranking points estimated from wins (bonus RP not included), so check official rankings for exact order"));
        } else if (data.qualMatchesTotal > 0) {
            parts.push(t("projected.played", "{played} of {total} qualification matches played", { played: data.qualMatchesPlayed, total: data.qualMatchesTotal }));
            if (data.simulations > 0 && data.qualMatchesPlayed < data.qualMatchesTotal) {
                parts.push(t("projected.sims", "{n} simulations", { n: data.simulations.toLocaleString() }));
            }
            parts.push(data.rpSource === "official"
                ? t("projected.rp_official_win", "ranking points from official results ({rp} RP per win)", { rp: data.winRp })
                : t("projected.rp_estimated", "ranking points estimated from wins ({rp} RP per win, bonus RP not included)", { rp: data.winRp }));
            parts.push(t("projected.strength", "team strength from {metrics}", { metrics: data.strengthMetrics.join(" + ") }));
            parts.push(t("projected.rerun_note", "Re-run draws a new set of simulations, so small changes between runs are normal"));
        }
        if (data.note) parts.push(data.note);
        summary.textContent = parts.length ? parts.join(" · ") + "." : t("projected.notice", "Simulates the remaining qualification matches to estimate where each team will finish.");
    }

    if (!data.teams || data.teams.length === 0) {
        body.innerHTML = `<tr><td colspan="11" style="text-align: center; color: var(--muted); padding: 24px;">${esc(data.note || t("projected.no_matches", "No qualification matches to project."))}</td></tr>`;
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
            if (delta > 0) trend = ` <span class="projection-trend up" title="${esc(t("projected.tip.rise", "Projected to rise {n} from current rank", { n: delta }))}">&#9650;${delta}</span>`;
            else if (delta < 0) trend = ` <span class="projection-trend down" title="${esc(t("projected.tip.drop", "Projected to drop {n} from current rank", { n: -delta }))}">&#9660;${-delta}</span>`;
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
            <td title="${esc(t("projected.tip.median", "Median simulated rank {median}, average {avg}", { median: team.medianRank, avg: team.projectedRank.toFixed(1) }))}"><strong>${projected}</strong>${trend}</td>
            <td><a href="${link}" class="team-profile-link">${esc(displayNum)}</a></td>
            <td><a href="${link}" class="team-profile-link">${esc(team.nickname || "")}</a></td>
            <td>${team.currentRank || "–"}</td>
            <td>${record}</td>
            <td>${rsNow}</td>
            <td title="${esc(t("projected.tip.total_rp", "{rp} total RP expected", { rp: team.expectedFinalRp.toFixed(1) }))}">${team.expectedRankingScore.toFixed(2)}</td>
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
