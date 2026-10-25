/*
 * Match Predictions page: the scout's red / blue pick for every match at the current event, using the same
 * picker as the scouting forms (js/services/match-prediction-picker.js).
 */
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;
}

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    const Picker = window.MatchPredictionPicker;
    const Utils = window.ObsidianMatchUtils;
    const panel = document.getElementById("predictions-panel");
    const listCard = document.getElementById("prediction-list-card");
    const offCard = document.getElementById("predictions-off");
    const list = document.getElementById("prediction-list");
    const empty = document.getElementById("prediction-empty");
    const kpis = document.getElementById("prediction-kpis");
    const showSelect = document.getElementById("show-select");
    const teamSearch = document.getElementById("team-search");
    const refreshBtn = document.getElementById("refresh-predictions");

    function showOff() {
        offCard.classList.remove("hidden");
        panel.classList.add("hidden");
        listCard.classList.add("hidden");
    }

    let settings;
    try {
        settings = (await Obsidianscout.request("/api/settings")).settings;
    } catch (error) {
        Obsidianscout.showRetryButton(list, "Failed to load settings: " + error.message, () => window.location.reload());
        return;
    }
    if (!settings || settings.matchPredictionsEnabled !== true) {
        showOff();
        return;
    }

    const eventKey = Obsidianscout.resolveEventKey(settings);
    document.getElementById("event-badge").textContent = eventKey || t("predictions.no_event", "Not set");
    if (!eventKey) {
        empty.textContent = t("predictions.no_event_notice", "Set the current event in Admin Settings to start predicting.");
        empty.classList.remove("hidden");
        return;
    }

    const eventTz = await Obsidianscout.getEventTimezone(eventKey);
    const ourTeam = Number(me.teamNumber);
    let matches = [];
    let statuses = new Map();
    const rows = new Map();

    try {
        const showPref = Obsidianscout.safeGetItem("obsidianscout:predictions-show");
        if (showPref && Array.from(showSelect.options).some(o => o.value === showPref)) showSelect.value = showPref;
    } catch (_) { /* ignore */ }

    function statusOf(matchKey) {
        return statuses.get(matchKey) || { matchKey };
    }

    function renderKpis() {
        const all = matches.map(m => statusOf(m.matchKey));
        const picked = all.filter(s => s.predictedWinner).length;
        const open = all.filter(s => !s.result && !s.locked);
        const needs = open.filter(s => !s.predictedWinner).length;
        const scored = all.filter(s => s.predictedWinner && (s.result === "red" || s.result === "blue"));
        const correct = scored.filter(s => s.predictedWinner === s.result).length;
        const accuracy = scored.length ? Math.round((correct * 1000) / scored.length) / 10 : null;
        const tile = (title, value, sub) => `
            <div class="prediction-kpi">
                <span class="prediction-kpi-title">${Obsidianscout.escapeHtml(title)}</span>
                <span class="prediction-kpi-value">${Obsidianscout.escapeHtml(String(value))}</span>
                <span class="prediction-kpi-sub">${Obsidianscout.escapeHtml(sub)}</span>
            </div>`;
        kpis.innerHTML = [
            tile(t("predictions.kpi_picks", "Picks Made"), picked, t("predictions.kpi_picks_sub", "of {n} matches").replace("{n}", matches.length)),
            tile(t("predictions.kpi_needs", "Still To Pick"), needs, t("predictions.kpi_needs_sub", "open matches without a pick")),
            tile(t("predictions.kpi_accuracy", "Accuracy"), accuracy === null ? "-" : `${accuracy}%`,
                t("predictions.kpi_accuracy_sub", "{c} right of {s} scored").replace("{c}", correct).replace("{s}", scored.length))
        ].join("");
    }

    function matchesFilter(match) {
        const status = statusOf(match.matchKey);
        const done = Boolean(status.result || status.locked);
        switch (showSelect.value) {
            case "needs": if (done || status.predictedWinner) return false; break;
            case "done": if (!done) return false; break;
            case "all": break;
            default: if (done) return false;
        }
        const query = teamSearch.value.trim().replace(/^(frc|ftc)/i, "");
        if (query) {
            const teams = Picker.teamNumbers([...(match.redTeams || []), ...(match.blueTeams || [])]);
            if (!teams.some(n => n.startsWith(query))) return false;
        }
        return true;
    }

    function buildRow(match) {
        const row = document.createElement("div");
        row.className = "prediction-row";
        if (ourTeam && Utils && Utils.isTeamInMatch(match, ourTeam)) row.classList.add("is-ours");

        const info = document.createElement("div");
        info.className = "prediction-row-match";
        const label = document.createElement("span");
        label.className = "prediction-row-label";
        label.textContent = Utils ? Utils.matchLabel(match) : match.matchKey;
        const time = document.createElement("span");
        time.className = "prediction-row-time";
        const epoch = match.predictedTime || match.scheduledTime;
        time.textContent = epoch ? Obsidianscout.formatTimestamp(epoch, eventTz) : "";
        info.append(label, time);

        const result = document.createElement("span");
        result.className = "prediction-row-result";
        result.setAttribute("aria-live", "polite");

        const choices = Picker.createChoices(match, (winner) => Picker.handlePick(
            eventKey,
            match,
            winner,
            () => statusOf(match.matchKey),
            (s) => {
                statuses.set(match.matchKey, s);
                updateRow(match.matchKey);
                renderKpis();
            }
        ), { compact: true });

        row.append(info, choices.el, result);
        rows.set(match.matchKey, { row, choices, result });
        updateRow(match.matchKey);
        return row;
    }

    function updateRow(matchKey) {
        const entry = rows.get(matchKey);
        if (!entry) return;
        const status = statusOf(matchKey);
        entry.choices.update(status);
        const line = Picker.describe(status);
        entry.result.innerHTML = line.html;
        entry.result.className = `prediction-row-result ${line.tone}`.trim();
    }

    function renderList() {
        list.innerHTML = "";
        rows.clear();
        const shown = matches.filter(matchesFilter);
        shown.forEach(m => list.appendChild(buildRow(m)));
        if (shown.length === 0) {
            empty.textContent = matches.length === 0
                ? t("predictions.no_matches", "No matches for this event yet. They'll show up once the schedule syncs.")
                : t("predictions.nothing_here", "No matches fit these filters.");
            empty.classList.remove("hidden");
        } else {
            empty.classList.add("hidden");
        }
        renderKpis();
    }

    async function load(refresh) {
        try {
            const [matchList, data] = await Promise.all([
                Obsidianscout.request(`/api/matches?eventKey=${encodeURIComponent(eventKey)}`),
                Picker.loadStatuses(eventKey, { refresh })
            ]);
            matches = (matchList || []).slice();
            if (Utils) matches.sort(Utils.compareMatches);
            statuses = data.byMatch;
            renderList();
        } catch (error) {
            if (error && error.status === 403) {
                showOff();
                return;
            }
            Obsidianscout.showRetryButton(list, "Failed to load predictions: " + error.message, () => load(true));
        }
    }

    showSelect.addEventListener("change", () => {
        Obsidianscout.safeSetItem("obsidianscout:predictions-show", showSelect.value);
        renderList();
    });
    teamSearch.addEventListener("input", renderList);
    refreshBtn.addEventListener("click", () => Obsidianscout.withButtonLoading(refreshBtn, async () => {
        await Picker.flushQueue();
        await load(true);
    }));
    window.addEventListener("obsidianscout:predictions-synced", () => load(true));

    Obsidianscout.showLoadingSpinner(list, t("predictions.loading", "Loading matches..."));
    await load(true);
});
