/**
 * "Don't know your event code?" picker on the settings API tab.
 *
 * Lists every event the server knows of for the chosen season (GET /api/settings/event-catalog, kept current
 * by the server with the shared API keys) and fills in the season, event code and, for events FIRST lists under
 * a different code than TBA (championship divisions, some offseasons), the alternate event code.
 *
 * window.ObsidianEventPicker.attach({ yearInput, codeInput, altInput }) wires the markup in #event-picker;
 * call it again after the panel is re-rendered.
 */
(function () {
    const MAX_RESULTS = 60;
    const catalogs = new Map(); // year -> Promise<response>

    function tr(key, fallback, vars) {
        let text = (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;
        if (vars) Object.keys(vars).forEach(k => { text = text.split("{" + k + "}").join(String(vars[k])); });
        return text;
    }

    function load(year) {
        if (!catalogs.has(year)) {
            const p = Obsidianscout.request("/api/settings/event-catalog?year=" + encodeURIComponent(year))
                .catch(err => { catalogs.delete(year); throw err; });
            catalogs.set(year, p);
        }
        return catalogs.get(year);
    }

    function formatDate(iso) {
        if (!iso) return "";
        const d = new Date(iso + "T12:00:00");
        if (isNaN(d)) return iso;
        return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
    }

    function timeAgo(iso) {
        const then = Date.parse(iso || "");
        if (isNaN(then)) return "";
        const mins = Math.max(0, Math.round((Date.now() - then) / 60000));
        if (mins < 1) return "just now";
        if (mins < 60) return mins + " min ago";
        const hours = Math.round(mins / 60);
        if (hours < 48) return hours + " h ago";
        return Math.round(hours / 24) + " days ago";
    }

    function haystack(e) {
        return [e.name, e.code, e.altCode, e.location, e.type, e.week ? "week " + e.week : ""].join(" ").toLowerCase();
    }

    function setField(input, value) {
        if (!input) return;
        input.value = value;
        input.dispatchEvent(new Event("input", { bubbles: true }));
        input.dispatchEvent(new Event("change", { bubbles: true }));
    }

    function attach({ yearInput, codeInput, altInput }) {
        const root = document.getElementById("event-picker");
        if (!root || root.dataset.wired === "1") return;
        root.dataset.wired = "1";

        const yearSelect = root.querySelector("#event-picker-year");
        const search = root.querySelector("#event-picker-search");
        const list = root.querySelector("#event-picker-list");
        const status = root.querySelector("#event-picker-status");

        let events = [];
        let shown = [];
        let active = -1;
        let response = null;

        // Seasons: next year down to six years back, plus the configured one if it's outside that.
        const thisYear = new Date().getFullYear();
        const configured = Number(yearInput && yearInput.value) || thisYear;
        const years = [];
        for (let y = thisYear + 1; y >= thisYear - 6; y--) years.push(y);
        if (!years.includes(configured)) years.push(configured);
        years.sort((a, b) => b - a);
        yearSelect.innerHTML = "";
        years.forEach(y => {
            const opt = document.createElement("option");
            opt.value = String(y);
            opt.textContent = String(y);
            yearSelect.appendChild(opt);
        });
        yearSelect.value = String(configured);

        function setStatus(text) { status.textContent = text || ""; }

        function summary() {
            if (!response) return "";
            if (!response.available) return tr("config.event_picker_unavailable", "The server can't list events for this season yet: no TBA or FIRST API key is set up.");
            let text = tr("config.event_picker_count", "{n} events. Pick one to fill in the event code.", { n: events.length });
            const ago = timeAgo(response.fetchedAt);
            if (ago) text += " " + tr("config.event_picker_updated", "List updated {when}.", { when: ago });
            return text;
        }

        function close() {
            list.classList.add("hidden");
            search.setAttribute("aria-expanded", "false");
            search.removeAttribute("aria-activedescendant");
            active = -1;
        }

        function highlight(index) {
            const items = list.querySelectorAll("li[role=option]");
            items.forEach((li, i) => li.setAttribute("aria-selected", i === index ? "true" : "false"));
            active = index;
            if (index >= 0 && items[index]) {
                search.setAttribute("aria-activedescendant", items[index].id);
                items[index].scrollIntoView({ block: "nearest" });
            }
        }

        function render() {
            const terms = search.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
            const matches = terms.length ? events.filter(e => { const h = e._h; return terms.every(t => h.includes(t)); }) : events;
            shown = matches.slice(0, MAX_RESULTS);
            list.innerHTML = "";
            if (!response) { close(); return; }
            if (!shown.length) {
                const li = document.createElement("li");
                li.className = "event-picker-empty";
                li.textContent = response.available ? tr("config.event_picker_none", "No events match.") : summary();
                list.appendChild(li);
            }
            shown.forEach((e, i) => {
                const li = document.createElement("li");
                li.id = "event-picker-opt-" + i;
                li.setAttribute("role", "option");
                li.setAttribute("aria-selected", "false");

                const top = document.createElement("div");
                top.className = "event-picker-row";
                const name = document.createElement("span");
                name.className = "event-picker-name";
                name.textContent = e.name;
                const codes = document.createElement("span");
                codes.className = "event-picker-codes";
                const code = document.createElement("code");
                code.textContent = e.code;
                codes.appendChild(code);
                if (e.altCode) {
                    const alt = document.createElement("code");
                    alt.className = "event-picker-alt";
                    alt.title = "FIRST code";
                    alt.textContent = "FIRST " + e.altCode;
                    codes.appendChild(alt);
                }
                top.append(name, codes);

                const meta = document.createElement("div");
                meta.className = "event-picker-meta";
                const dates = e.startDate ? formatDate(e.startDate) + (e.endDate && e.endDate !== e.startDate ? " – " + formatDate(e.endDate) : "") : "";
                meta.textContent = [dates, e.week ? "Week " + e.week : "", e.type, e.location].filter(Boolean).join(" · ");

                li.append(top, meta);
                li.addEventListener("mousedown", ev => ev.preventDefault()); // keep focus in the search box
                li.addEventListener("click", () => choose(e));
                list.appendChild(li);
            });
            if (matches.length > shown.length) {
                const more = document.createElement("li");
                more.className = "event-picker-empty";
                more.textContent = "+" + (matches.length - shown.length) + " more, keep typing to narrow down";
                list.appendChild(more);
            }
            list.classList.remove("hidden");
            search.setAttribute("aria-expanded", "true");
            highlight(shown.length && terms.length ? 0 : -1);
        }

        function choose(e) {
            const year = yearSelect.value;
            if (yearInput && String(yearInput.value) !== year) setField(yearInput, year);
            setField(codeInput, e.code);
            setField(altInput, e.altCode || "");
            search.value = "";
            close();
            setStatus(e.altCode
                ? tr("config.event_picker_filled_alt", "Filled in {code}, with FIRST's code {alt} as the alternate code.", { code: year + e.code.toLowerCase(), alt: e.altCode })
                : tr("config.event_picker_filled", "Filled in {code}.", { code: year + e.code.toLowerCase() }));
            codeInput && codeInput.focus();
        }

        async function loadYear() {
            const year = yearSelect.value;
            response = null;
            events = [];
            setStatus(tr("config.event_picker_loading", "Loading events..."));
            try {
                const res = await load(year);
                if (yearSelect.value !== year) return; // changed while loading
                response = res || { available: false, events: [] };
                events = (response.events || []).map(e => Object.assign({ _h: haystack(e) }, e));
                setStatus(summary());
                if (document.activeElement === search) render();
            } catch (err) {
                if (yearSelect.value !== year) return;
                setStatus(tr("config.event_picker_error", "Couldn't load the event list.") + (err && err.message ? " " + err.message : ""));
            }
        }

        yearSelect.addEventListener("change", () => { search.value = ""; loadYear(); });
        search.addEventListener("focus", () => { if (response) render(); });
        search.addEventListener("input", render);
        search.addEventListener("blur", () => setTimeout(close, 120));
        search.addEventListener("keydown", ev => {
            if (ev.key === "ArrowDown" || ev.key === "ArrowUp") {
                ev.preventDefault();
                if (list.classList.contains("hidden")) { render(); return; }
                if (!shown.length) return;
                const next = ev.key === "ArrowDown" ? Math.min(shown.length - 1, active + 1) : Math.max(0, active - 1);
                highlight(next);
            } else if (ev.key === "Enter") {
                if (active >= 0 && shown[active]) { ev.preventDefault(); choose(shown[active]); }
            } else if (ev.key === "Escape") {
                close();
            }
        });
        // Keep the picker on the season being configured.
        if (yearInput) {
            yearInput.addEventListener("change", () => {
                const y = String(Number(yearInput.value) || "");
                if (y && y !== yearSelect.value && years.includes(Number(y))) { yearSelect.value = y; loadYear(); }
            });
        }

        loadYear();
    }

    window.ObsidianEventPicker = { attach };
})();
