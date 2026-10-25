/*
 * Match predictions game: the red / blue pick shown on the match and qualitative scouting forms and on the
 * Match Predictions page (match-predictions.html). Off unless a team admin turns on `matchPredictionsEnabled`
 * (Admin Settings > Permissions); the server (MatchPredictionService.kt) refuses every call while it's off.
 *
 * A pick saves as soon as it's tapped, separately from the scouting entry, and can change until the match
 * result syncs. Picks made offline wait in localStorage (`pending_match_predictions`) and upload once the
 * server is reachable; one the server refuses (the match already has a result) is dropped.
 */
(function (root) {
    "use strict";

    const QUEUE_KEY = "pending_match_predictions";
    const RED = "red";
    const BLUE = "blue";

    function os() {
        return root.Obsidianscout || {};
    }

    function t(key, fallback) {
        return typeof os().t === "function" ? os().t(key, fallback) : fallback;
    }

    function icon(name, size) {
        return typeof os().faIcon === "function" ? os().faIcon(name, size || 14) : "";
    }

    function readStorage(key) {
        try {
            return typeof os().safeGetItem === "function" ? os().safeGetItem(key) : root.localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function writeStorage(key, value) {
        try {
            if (typeof os().safeSetItem === "function") return os().safeSetItem(key, value) !== false;
            root.localStorage.setItem(key, value);
            return true;
        } catch (e) {
            return false;
        }
    }

    function isNetworkError(error) {
        if (typeof os().isNetworkError === "function") return os().isNetworkError(error);
        return !error || !error.status;
    }

    // ------------------------------------------------------------------ offline queue

    /** { [matchKey]: { eventKey, matchKey, predictedWinner, at } } */
    function readQueue() {
        try {
            const parsed = JSON.parse(readStorage(QUEUE_KEY) || "{}");
            return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
        } catch (e) {
            return {};
        }
    }

    function writeQueue(queue) {
        return writeStorage(QUEUE_KEY, JSON.stringify(queue));
    }

    function queuePick(eventKey, matchKey, predictedWinner) {
        const queue = readQueue();
        queue[matchKey] = { eventKey, matchKey, predictedWinner: predictedWinner || "", at: Date.now() };
        return writeQueue(queue);
    }

    function unqueuePick(matchKey) {
        const queue = readQueue();
        if (!(matchKey in queue)) return;
        delete queue[matchKey];
        writeQueue(queue);
    }

    let flushing = null;

    /** Uploads queued picks. Keeps the ones that hit a network error for the next try. */
    function flushQueue() {
        if (flushing) return flushing;
        flushing = (async () => {
            const entries = Object.values(readQueue());
            let uploaded = 0;
            for (const pick of entries) {
                try {
                    await os().request("/api/match-predictions", {
                        method: "PUT",
                        json: { eventKey: pick.eventKey, matchKey: pick.matchKey, predictedWinner: pick.predictedWinner }
                    });
                    unqueuePick(pick.matchKey);
                    uploaded++;
                } catch (error) {
                    if (isNetworkError(error)) break;
                    // Locked, unknown match or predictions turned off: nothing more this pick can do.
                    unqueuePick(pick.matchKey);
                }
            }
            if (uploaded > 0) {
                statusCache.clear();
                root.dispatchEvent(new CustomEvent("obsidianscout:predictions-synced", { detail: { uploaded } }));
            }
            return uploaded;
        })().finally(() => { flushing = null; });
        return flushing;
    }

    // ------------------------------------------------------------------ server data

    let enabledPromise = null;

    /** Whether the team has match predictions turned on. */
    function isEnabled() {
        if (!enabledPromise) {
            enabledPromise = os().request("/api/settings")
                .then((res) => Boolean(res && res.settings && res.settings.matchPredictionsEnabled === true))
                .catch(() => {
                    try {
                        const cached = JSON.parse(readStorage("cache:/api/settings") || "{}");
                        const settings = cached.settings || cached;
                        return settings.matchPredictionsEnabled === true;
                    } catch (e) {
                        return false;
                    }
                });
        }
        return enabledPromise;
    }

    /** eventKey -> Promise<{ summary, byMatch: Map(matchKey -> status) }> */
    const statusCache = new Map();

    function overlayQueue(byMatch) {
        Object.values(readQueue()).forEach((pick) => {
            const current = byMatch.get(pick.matchKey) || { matchKey: pick.matchKey, locked: false };
            if (current.locked) return;
            byMatch.set(pick.matchKey, Object.assign({}, current, {
                predictedWinner: pick.predictedWinner || null,
                pendingSync: true
            }));
        });
        return byMatch;
    }

    /** The signed-in scout's picks for an event, with each match's lock state and result. */
    function loadStatuses(eventKey, options) {
        const refresh = options && options.refresh;
        if (!eventKey) return Promise.resolve({ summary: null, byMatch: new Map() });
        if (refresh || !statusCache.has(eventKey)) {
            const promise = os().request(`/api/match-predictions?eventKey=${encodeURIComponent(eventKey)}`)
                .then((res) => {
                    const byMatch = new Map();
                    ((res && res.matches) || []).forEach((m) => byMatch.set(m.matchKey, m));
                    return { summary: res, byMatch };
                })
                .catch((error) => {
                    statusCache.delete(eventKey);
                    throw error;
                });
            statusCache.set(eventKey, promise);
        }
        return statusCache.get(eventKey).then((data) => ({ summary: data.summary, byMatch: overlayQueue(new Map(data.byMatch)) }));
    }

    function rememberStatus(eventKey, status) {
        const cached = statusCache.get(eventKey);
        if (!cached) return;
        statusCache.set(eventKey, cached.then((data) => {
            const byMatch = new Map(data.byMatch);
            byMatch.set(status.matchKey, Object.assign({}, byMatch.get(status.matchKey) || {}, status));
            return { summary: data.summary, byMatch };
        }));
    }

    /**
     * Saves a pick ("red", "blue", or "" to clear).
     * Resolves to { status, queued } or { locked: true, message }; other server errors reject.
     */
    async function savePick(eventKey, matchKey, predictedWinner) {
        const winner = predictedWinner || "";
        try {
            const status = await os().request("/api/match-predictions", {
                method: "PUT",
                queueable: true,
                json: { eventKey, matchKey, predictedWinner: winner }
            });
            unqueuePick(matchKey);
            rememberStatus(eventKey, status);
            return { status, queued: false };
        } catch (error) {
            if (isNetworkError(error)) {
                if (!queuePick(eventKey, matchKey, winner)) throw error;
                return { status: { matchKey, predictedWinner: winner || null, locked: false, pendingSync: true }, queued: true };
            }
            if (error.status === 409) {
                statusCache.delete(eventKey);
                return { locked: true, message: error.message };
            }
            throw error;
        }
    }

    // ------------------------------------------------------------------ UI

    function teamNumbers(teamKeys) {
        return (teamKeys || [])
            .map((k) => String(k == null ? "" : k).replace(/^(frc|ftc)/i, "").trim())
            .filter(Boolean);
    }

    function allianceName(winner) {
        return winner === RED ? t("predictions.red", "Red") : t("predictions.blue", "Blue");
    }

    /** One line describing a pick and, once it's in, the result. Returns { html, tone }. */
    function describe(status) {
        const pick = status && status.predictedWinner;
        const result = status && status.result;
        if (result) {
            if (result === "tie") {
                return { html: `${icon("lock", 12)} ${t("predictions.result_tie", "Tie - doesn't count")}`, tone: "" };
            }
            const won = t("predictions.result_won", "{alliance} won").replace("{alliance}", allianceName(result));
            if (!pick) return { html: `${icon("lock", 12)} ${won} - ${t("predictions.no_pick", "no pick")}`, tone: "" };
            return pick === result
                ? { html: `${icon("circle-check", 12)} ${won} - ${t("predictions.called_it", "you called it")}`, tone: "is-correct" }
                : { html: `${icon("circle-xmark", 12)} ${won} - ${t("predictions.missed", "not this time")}`, tone: "is-wrong" };
        }
        if (status && status.locked) {
            return { html: `${icon("lock", 12)} ${t("predictions.locked", "Locked")}`, tone: "" };
        }
        if (!pick) return { html: t("predictions.pick_hint", "Pick before the result comes in"), tone: "" };
        if (status.pendingSync) {
            return { html: `${icon("clock", 12)} ${t("predictions.saved_offline", "Saved on this device - will sync")}`, tone: "" };
        }
        return { html: `${icon("circle-check", 12)} ${t("predictions.saved", "Saved")}`, tone: "" };
    }

    /**
     * Red and blue buttons for one match. `onPick(winner)` runs when a button is tapped; tapping the current
     * pick again clears it. Returns { el, update(status) }.
     */
    function createChoices(match, onPick, options) {
        const wrap = document.createElement("div");
        wrap.className = "prediction-choices" + (options && options.compact ? " prediction-compact" : "");
        const buttons = {};
        [RED, BLUE].forEach((side) => {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = `prediction-choice ${side}`;
            btn.dataset.winner = side;
            const label = document.createElement("span");
            label.className = "prediction-choice-label";
            label.textContent = allianceName(side);
            const teams = document.createElement("span");
            teams.className = "prediction-choice-teams";
            const list = teamNumbers(side === RED ? match.redTeams : match.blueTeams);
            teams.textContent = list.length ? list.join(" · ") : t("predictions.teams_tbd", "Teams TBD");
            btn.append(label, teams);
            btn.setAttribute("aria-pressed", "false");
            btn.addEventListener("click", () => {
                if (btn.disabled) return;
                onPick(btn.classList.contains("selected") ? "" : side);
            });
            buttons[side] = btn;
            wrap.appendChild(btn);
        });

        function update(status) {
            const pick = status && status.predictedWinner;
            const locked = Boolean(status && status.locked);
            [RED, BLUE].forEach((side) => {
                const btn = buttons[side];
                const selected = pick === side;
                btn.classList.toggle("selected", selected);
                btn.classList.toggle("winner", Boolean(status && status.result === side));
                btn.setAttribute("aria-pressed", selected ? "true" : "false");
                btn.disabled = locked;
            });
        }

        return { el: wrap, update };
    }

    /**
     * Handles a tap: shows the pick at once, saves it, and rolls back if the server refuses.
     * `getStatus` / `setStatus` read and write the caller's copy of the match's status.
     */
    async function handlePick(eventKey, match, winner, getStatus, setStatus) {
        const before = getStatus() || { matchKey: match.matchKey };
        setStatus(Object.assign({}, before, { predictedWinner: winner || null, pendingSync: false }));
        if (typeof os().triggerHaptic === "function") os().triggerHaptic("light");
        try {
            const outcome = await savePick(eventKey, match.matchKey, winner);
            if (outcome.locked) {
                setStatus(Object.assign({}, before, { locked: true }));
                os().showToast?.(t("predictions.locked_toast", "This match already has a result, so picks are locked."), "error");
                return;
            }
            setStatus(Object.assign({}, before, outcome.status));
        } catch (error) {
            setStatus(before);
            os().showToast?.(error.message || t("predictions.save_failed", "Couldn't save your pick"), "error");
        }
    }

    /**
     * The prediction card on a scouting form. Hidden until a match is chosen and while predictions are off.
     * Returns { setMatch(match | null) }.
     */
    function mount(host, options) {
        const eventKey = options && options.eventKey;
        const card = document.createElement("div");
        card.className = "card soft prediction-card hidden";
        card.innerHTML = `
            <div class="prediction-head">
                <h3>${t("predictions.form_title", "Who wins this match?")}</h3>
                <span class="prediction-status" aria-live="polite"></span>
            </div>`;
        host.appendChild(card);
        const statusEl = card.querySelector(".prediction-status");

        let enabled = false;
        let current = null;
        let currentStatus = null;
        let choices = null;
        let token = 0;

        function render() {
            if (!choices) return;
            choices.update(currentStatus);
            const line = describe(currentStatus);
            statusEl.innerHTML = line.html;
            statusEl.className = `prediction-status ${line.tone}`.trim();
        }

        async function setMatch(match) {
            const mine = ++token;
            current = match || null;
            if (choices) {
                choices.el.remove();
                choices = null;
            }
            if (!current || !current.matchKey) {
                card.classList.add("hidden");
                return;
            }
            enabled = await isEnabled();
            if (mine !== token) return;
            if (!enabled) {
                card.classList.add("hidden");
                return;
            }
            currentStatus = { matchKey: current.matchKey };
            const shown = current;
            choices = createChoices(current, (winner) => handlePick(
                eventKey,
                shown,
                winner,
                () => currentStatus,
                (s) => { if (current === shown) { currentStatus = s; render(); } }
            ));
            card.appendChild(choices.el);
            card.classList.remove("hidden");
            render();
            try {
                const data = await loadStatuses(eventKey);
                if (mine !== token) return;
                currentStatus = data.byMatch.get(current.matchKey) || currentStatus;
                render();
            } catch (e) {
                // Offline with nothing cached: the buttons still work and the pick queues.
            }
        }

        root.addEventListener("obsidianscout:predictions-synced", () => {
            if (current) setMatch(current);
        });

        return { setMatch };
    }

    root.addEventListener("online", () => { flushQueue(); });
    if (typeof document !== "undefined") {
        document.addEventListener("DOMContentLoaded", () => {
            setTimeout(() => {
                if (Object.keys(readQueue()).length && os().request) flushQueue();
            }, 1500);
        });
    }

    root.MatchPredictionPicker = {
        mount,
        createChoices,
        describe,
        handlePick,
        isEnabled,
        loadStatuses,
        savePick,
        flushQueue,
        teamNumbers
    };
})(typeof window !== "undefined" ? window : globalThis);
