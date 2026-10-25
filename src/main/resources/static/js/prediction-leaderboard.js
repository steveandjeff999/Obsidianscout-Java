/*
 * Prediction Leaderboard: the team's scouts ranked by how often they picked the winning alliance, for one
 * event or a whole season. Data comes from MatchPredictionService.kt (/api/match-predictions/leaderboard).
 */
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;
}

function esc(value) {
    return Obsidianscout.escapeHtml(value == null ? "" : String(value));
}

function icon(name, size) {
    return typeof Obsidianscout.faIcon === "function" ? Obsidianscout.faIcon(name, size || 14) : "";
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

    const scopeSelect = document.getElementById("scope-select");
    const eventSelect = document.getElementById("event-select");
    const yearSelect = document.getElementById("year-select");
    const tbody = document.querySelector("#leaderboard-table tbody");
    const empty = document.getElementById("leaderboard-empty");
    const kpis = document.getElementById("leaderboard-kpis");
    const meta = document.getElementById("leaderboard-meta");

    function showOff() {
        document.getElementById("predictions-off").classList.remove("hidden");
        document.getElementById("leaderboard-panel").classList.add("hidden");
        document.getElementById("leaderboard-card").classList.add("hidden");
    }

    let settings = {};
    let options = { years: [], events: [] };
    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        settings = (settingsResponse && settingsResponse.settings) || {};
        if (settings.matchPredictionsEnabled !== true) {
            showOff();
            return;
        }
        options = (await Obsidianscout.request("/api/match-predictions/leaderboard/options")) || options;
    } catch (error) {
        if (error && error.status === 403) {
            showOff();
            return;
        }
        Obsidianscout.showToast(error.message || "Failed to load leaderboard options", "error");
    }

    const currentEvent = (Obsidianscout.resolveEventKey(settings) || "").toLowerCase();
    const events = (options.events || []).slice();
    if (currentEvent && !events.some((e) => e.eventKey === currentEvent)) {
        events.unshift({ eventKey: currentEvent, name: null, year: settings.year || null });
    }
    eventSelect.innerHTML = events.length
        ? events.map((e) => {
            const label = e.name && e.name.toLowerCase() !== e.eventKey ? `${e.name} (${e.eventKey})` : e.eventKey;
            return `<option value="${esc(e.eventKey)}">${esc(label)}</option>`;
        }).join("")
        : `<option value="">${esc(t("leaderboard.no_events", "No events with predictions"))}</option>`;
    if (currentEvent) eventSelect.value = currentEvent;

    const years = (options.years || []).slice();
    const thisYear = Number(settings.year) || new Date().getFullYear();
    if (!years.includes(thisYear)) years.push(thisYear);
    years.sort((a, b) => b - a);
    yearSelect.innerHTML = years.map((y) => `<option value="${y}">${y}</option>`).join("");
    yearSelect.value = String(thisYear);

    try {
        const saved = Obsidianscout.safeGetItem("obsidianscout:leaderboard-scope");
        if (saved === "event" || saved === "year") scopeSelect.value = saved;
    } catch (_) { /* ignore */ }

    function syncScopeFields() {
        const scope = scopeSelect.value;
        document.getElementById("event-field").classList.toggle("hidden", scope !== "event");
        document.getElementById("year-field").classList.toggle("hidden", scope !== "year");
        document.querySelectorAll("#leaderboard-table .season-only").forEach((el) => el.classList.toggle("hidden", scope !== "year"));
    }

    function renderKpis(board) {
        const ranked = board ? board.rows.filter((r) => r.rank != null) : [];
        const leader = ranked[0];
        const mine = board ? board.rows.find((r) => r.userId === me.userId || r.userId === me.id) : null;
        const tile = (title, value, sub) => `
            <div class="prediction-kpi">
                <span class="prediction-kpi-title">${esc(title)}</span>
                <span class="prediction-kpi-value">${value}</span>
                <span class="prediction-kpi-sub">${esc(sub)}</span>
            </div>`;
        kpis.innerHTML = [
            tile(t("leaderboard.kpi_leader", "Leader"),
                leader ? `${icon("trophy", 20)}<span class="kpi-name" title="${esc(leader.username)}">${esc(leader.username)}</span>` : "-",
                leader ? `${leader.accuracyPct}% (${leader.correct}/${leader.scored})` : t("leaderboard.kpi_leader_none", "No scored picks yet")),
            tile(t("leaderboard.kpi_you", "Your Rank"),
                mine && mine.rank != null ? `#${mine.rank}` : "-",
                mine ? (mine.accuracyPct != null ? `${mine.accuracyPct}% ${t("leaderboard.accurate", "accurate")}` : t("leaderboard.waiting", "Waiting on results")) : t("leaderboard.kpi_you_none", "No picks here yet")),
            tile(t("leaderboard.kpi_scouts", "Scouts Ranked"), String(ranked.length),
                t("leaderboard.kpi_scouts_sub", "with at least one scored pick")),
            tile(t("leaderboard.kpi_matches", "Matches Scored"), String(board ? board.matchesScored : 0),
                t("leaderboard.kpi_matches_sub", "with a winner and a pick"))
        ].join("");
    }

    function render(board) {
        renderKpis(board);
        const rows = board ? board.rows : [];
        empty.classList.toggle("hidden", rows.length > 0);
        const seasonScope = scopeSelect.value === "year";
        tbody.innerHTML = rows.map((r) => {
            const isMe = r.userId === me.userId || r.userId === me.id;
            const rank = r.rank != null
                ? `<span class="leaderboard-rank${r.rank <= 3 ? ` rank-${r.rank}` : ""}">${r.rank}</span>`
                : `<span class="leaderboard-rank" title="${esc(t("leaderboard.unranked", "Not ranked until a pick is scored"))}">-</span>`;
            const accuracy = r.accuracyPct != null
                ? `<span class="accuracy-bar"><span style="width:${Math.max(0, Math.min(100, r.accuracyPct))}%"></span></span>${r.accuracyPct}%`
                : "-";
            const streak = r.currentStreak >= 3
                ? `<span class="streak-chip hot">${icon("fire", 12)} ${r.currentStreak}</span>`
                : `<span class="streak-chip">${r.currentStreak}</span>`;
            const classes = [isMe ? "is-me" : "", r.rank == null ? "is-unranked" : ""].filter(Boolean).join(" ");
            return `<tr class="${classes}">
                <td>${rank}</td>
                <td>${esc(r.username)}${isMe ? ` <span class="badge">${esc(t("leaderboard.you", "You"))}</span>` : ""}</td>
                <td class="num">${accuracy}</td>
                <td class="num">${r.correct}/${r.scored}</td>
                <td class="num">${r.pending}</td>
                <td class="num">${r.ties}</td>
                <td class="num">${streak}</td>
                <td class="num">${r.bestStreak}</td>
                <td class="num season-only${seasonScope ? "" : " hidden"}">${r.events}</td>
            </tr>`;
        }).join("");
        meta.textContent = board
            ? `${t("leaderboard.updated", "Updated")} ${new Date(board.generatedAt).toLocaleString()}`
            : "";
    }

    async function load() {
        const params = new URLSearchParams();
        if (scopeSelect.value === "event") {
            if (!eventSelect.value) {
                render(null);
                return;
            }
            params.set("eventKey", eventSelect.value);
        } else {
            params.set("year", yearSelect.value);
        }
        tbody.innerHTML = `<tr><td colspan="9" class="notice">${esc(t("leaderboard.loading", "Loading leaderboard..."))}</td></tr>`;
        try {
            render(await Obsidianscout.request(`/api/match-predictions/leaderboard?${params.toString()}`));
        } catch (error) {
            if (error && error.status === 403) {
                showOff();
                return;
            }
            render(null);
            Obsidianscout.showToast(error.message || "Failed to load leaderboard", "error");
        }
    }

    scopeSelect.addEventListener("change", () => {
        Obsidianscout.safeSetItem("obsidianscout:leaderboard-scope", scopeSelect.value);
        syncScopeFields();
        load();
    });
    eventSelect.addEventListener("change", load);
    yearSelect.addEventListener("change", load);

    syncScopeFields();
    await load();
});
