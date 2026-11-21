/*
 * Timer form element for the match scouting forms.
 *
 * Config:  { "id": "climb_time", "label": "Climb time", "type": "timer", "mode": "stopwatch" }
 *   mode "stopwatch" (default): Start / Stop accumulates total seconds.
 *   mode "cycle":               Start, then Lap at the end of each cycle; the value is the
 *                               average seconds per completed lap.
 *
 * The value lives in a read-only number input (seconds, one decimal) so the existing
 * payload, draft, QR and analytics code treat it like any other number field.
 * Anything outside the component that changes the input's value (draft restore, clear
 * after submit, loading an entry for edit) must dispatch an "input" event; the timer
 * then stops and shows the new value.
 */
(function () {
    "use strict";

    const t = (key, fallback) =>
        (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;

    function formatSeconds(sec) {
        const s = Math.max(0, Number(sec) || 0);
        const m = Math.floor(s / 60);
        const rest = s - m * 60;
        const restText = rest.toFixed(1).padStart(4, "0");
        return m > 0 ? `${m}:${restText}` : `${rest.toFixed(1)}s`;
    }

    function round1(n) {
        return Math.round(n * 10) / 10;
    }

    function icon(name) {
        const span = document.createElement("span");
        span.className = "material-symbols-outlined";
        span.setAttribute("aria-hidden", "true");
        span.textContent = name;
        return span;
    }

    function makeButton(className, iconName, text) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = `timer-btn ${className}`;
        btn.appendChild(icon(iconName));
        const label = document.createElement("span");
        label.textContent = text;
        btn.appendChild(label);
        btn._label = label;
        btn._icon = btn.firstChild;
        return btn;
    }

    function build(field) {
        const mode = String(field.mode || "stopwatch").toLowerCase() === "cycle" ? "cycle" : "stopwatch";

        const wrapper = document.createElement("div");
        wrapper.className = `timer-field timer-mode-${mode}`;

        const input = document.createElement("input");
        input.type = "number";
        input.step = "0.1";
        input.min = "0";
        input.readOnly = true;
        input.tabIndex = -1;
        input.className = "timer-value-input";
        input.setAttribute("aria-hidden", "true");

        const display = document.createElement("div");
        display.className = "timer-display";
        display.setAttribute("aria-live", "polite");
        display.textContent = formatSeconds(0);

        const sub = document.createElement("div");
        sub.className = "timer-sub";

        const startBtn = makeButton("timer-start", "play_arrow", t("timer.start", "Start"));
        const lapBtn = makeButton("timer-lap", "flag", t("timer.lap", "Lap"));
        const resetBtn = makeButton("timer-reset", "restart_alt", t("timer.reset", "Reset"));
        if (mode !== "cycle") lapBtn.classList.add("hidden");

        const controls = document.createElement("div");
        controls.className = "timer-controls";
        controls.append(startBtn, lapBtn, resetBtn);

        const readout = document.createElement("div");
        readout.className = "timer-readout";
        readout.append(display, sub);

        wrapper.append(readout, controls, input);

        // State. elapsedMs is time banked while stopped; runStart is when the current run began.
        let running = false;
        let runStart = 0;
        let elapsedMs = 0;
        let lapStart = 0;          // cycle mode: elapsed ms at which the current lap began
        let laps = [];             // cycle mode: completed lap durations in ms
        let tick = null;
        let writing = false;       // true while the component itself sets input.value
        let restored = false;      // cycle mode: laps holds one saved average, not real laps

        const now = () => (typeof performance !== "undefined" ? performance.now() : Date.now());
        const currentElapsed = () => elapsedMs + (running ? now() - runStart : 0);

        function storedValue() {
            if (mode === "cycle") {
                if (!laps.length) return null;
                return round1(laps.reduce((a, b) => a + b, 0) / laps.length / 1000);
            }
            const total = currentElapsed();
            return total > 0 ? round1(total / 1000) : null;
        }

        function writeValue() {
            const v = storedValue();
            const next = v === null ? "" : String(v);
            if (input.value === next) return;
            writing = true;
            input.value = next;
            input.dispatchEvent(new Event("input", { bubbles: true }));
            writing = false;
        }

        function render() {
            if (mode === "cycle") {
                const lapNow = currentElapsed() - lapStart;
                display.textContent = formatSeconds(running ? lapNow / 1000 : (laps.length ? storedValue() : 0));
                if (laps.length) {
                    const avg = storedValue();
                    sub.textContent = `${laps.length} ${laps.length === 1 ? t("timer.cycle", "cycle") : t("timer.cycles", "cycles")} · ${t("timer.avg", "avg")} ${formatSeconds(avg)}`;
                } else {
                    sub.textContent = running ? t("timer.tap_lap", "Tap Lap at the end of each cycle") : "";
                }
            } else {
                display.textContent = formatSeconds(currentElapsed() / 1000);
                sub.textContent = "";
            }
            wrapper.classList.toggle("is-running", running);
            startBtn._icon.textContent = running ? "pause" : "play_arrow";
            startBtn._label.textContent = running ? t("timer.stop", "Stop")
                : (currentElapsed() > 0 ? t("timer.resume", "Resume") : t("timer.start", "Start"));
            lapBtn.disabled = !running;
        }

        let lastWrite = 0;
        function startTick() {
            if (tick) return;
            tick = setInterval(() => {
                // Stop the clock if the field left the page (form rebuilt for a new match).
                if (!wrapper.isConnected) {
                    stopTick();
                    return;
                }
                render();
                // Keep the saved value current about once a second, so submitting or a
                // draft save while the stopwatch is still running keeps the time so far.
                if (mode === "stopwatch" && now() - lastWrite >= 1000) {
                    lastWrite = now();
                    writeValue();
                }
            }, 100);
        }

        function stopTick() {
            if (tick) clearInterval(tick);
            tick = null;
        }

        function start() {
            if (running) return;
            running = true;
            runStart = now();
            startTick();
            render();
        }

        function stop() {
            if (!running) return;
            elapsedMs += now() - runStart;
            running = false;
            stopTick();
            writeValue();
            render();
        }

        function lap() {
            if (!running || mode !== "cycle") return;
            const at = currentElapsed();
            const dur = at - lapStart;
            if (dur < 200) return; // ignore accidental double taps
            if (restored) {
                // The saved average was from an earlier session; start counting fresh.
                laps = [];
                restored = false;
            }
            laps.push(dur);
            lapStart = at;
            if (navigator.vibrate) {
                try { navigator.vibrate(15); } catch (_) { /* ignore */ }
            }
            writeValue();
            render();
        }

        function reset(silent) {
            running = false;
            stopTick();
            elapsedMs = 0;
            lapStart = 0;
            laps = [];
            restored = false;
            if (!silent) writeValue();
            render();
        }

        startBtn.addEventListener("click", () => (running ? stop() : start()));
        lapBtn.addEventListener("click", lap);
        resetBtn.addEventListener("click", () => reset(false));

        // Someone else set the value (draft restore, clear, edit). Stop and show it.
        input.addEventListener("input", () => {
            if (writing) return;
            reset(true);
            const v = input.value === "" ? 0 : Number(input.value) || 0;
            if (mode === "cycle") {
                if (v > 0) {
                    laps = [v * 1000];
                    lapStart = 0;
                    restored = true;
                }
                display.textContent = formatSeconds(v);
                sub.textContent = v > 0 ? t("timer.restored", "Restored value") : "";
            } else {
                elapsedMs = v * 1000;
                render();
            }
        });

        // Public hooks for gamepad bindings and other callers.
        wrapper.timerControls = { start, stop, lap, reset: () => reset(false), toggle: () => (running ? stop() : start()) };

        render();
        return { wrapper, input };
    }

    window.ObsidianTimerField = { build, formatSeconds };
})();
