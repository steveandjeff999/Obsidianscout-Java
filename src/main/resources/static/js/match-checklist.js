/**
 * Match Checklist page - ObsidianScout
 * Drive-team pre-match and on-the-field checklists. The team edits one template; each match gets its own
 * completion record. Shown only when "Non-Scouting Tools" is enabled in Personal Settings.
 *
 * Both documents are edited live, the same way as the config editors (js/services/config-collab.js):
 * everyone's edits merge as they happen and autosave on the server.
 *
 * Template doc:   { sections: { pre: { title }, field: { title } }, fields: [{ id, section, text, priority }] }
 * Completion doc: { checked: { [itemId]: { by, at } }, notes, fields: [] }
 */

const SECTION_DEFS = [
    { id: "pre", title: "Pre-Match" },
    { id: "field", title: "On the Field" }
];

// Highest priority first; items are listed in this order when filling out a checklist.
const PRIORITIES = [
    { id: "critical", label: "Critical" },
    { id: "high", label: "High" },
    { id: "normal", label: "Normal" },
    { id: "low", label: "Low" }
];
const DEFAULT_PRIORITY = "normal";

const STARTER_ITEMS = {
    pre: [
        { text: "Fresh battery installed and strapped down", priority: "critical" },
        { text: "Battery voltage checked (12.5V+)", priority: "critical" },
        { text: "Bumpers on and showing the correct alliance color", priority: "critical" },
        { text: "Radio powered and secured", priority: "critical" },
        { text: "All bolts, chains and belts checked", priority: "high" },
        { text: "Controllers and driver station laptop charged", priority: "high" }
    ],
    field: [
        { text: "Robot placed in the correct starting position", priority: "critical" },
        { text: "Correct autonomous routine selected", priority: "critical" },
        { text: "Driver station connected to the field (all green)", priority: "critical" },
        { text: "Game piece preloaded", priority: "high" },
        { text: "Controllers plugged in and mapped to the right ports", priority: "high" }
    ]
};

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : (fallback || key);
}

const DEFAULT_STATUS_LABELS = {
    connecting: "Connecting to live editing…",
    live: "Live — changes save automatically",
    readonly: "Live — view only",
    reconnecting: "Reconnecting… your changes are kept",
    offline: "Offline — your changes are kept on this device"
};

function statusLabel(status) {
    return t(`checklist.status.${status}`, DEFAULT_STATUS_LABELS[status] || status);
}

const state = {
    me: null,
    eventKey: "",
    matches: [],
    matchKey: "",
    eventCompletions: {},   // matchKey -> completion (for the ✓ in the match picker)
    mode: "fill",
    tpl: null,              // live session for the template
    match: null,            // live session for the selected match
    tplBar: null,
    matchBar: null
};

const el = {};

function collab() {
    return window.ObsidianscoutConfigCollab;
}

function esc(value) {
    return Obsidianscout.escapeHtml(String(value ?? ""));
}

function newId() {
    return Math.random().toString(36).slice(2, 10);
}

function clone(v) {
    return JSON.parse(JSON.stringify(v ?? null));
}

function parseJson(text, fallback) {
    try {
        const v = JSON.parse(text || "");
        return v && typeof v === "object" && !Array.isArray(v) ? v : fallback;
    } catch (_) {
        return fallback;
    }
}

function priorityRank(priority) {
    const i = PRIORITIES.findIndex((p) => p.id === priority);
    return i < 0 ? PRIORITIES.findIndex((p) => p.id === DEFAULT_PRIORITY) : i;
}

function priorityLabel(priority) {
    const p = PRIORITIES[priorityRank(priority)];
    return t(`checklist.priority.${p.id}`, p.label);
}

function priorityOptions(selected) {
    return PRIORITIES.map((p) => `<option value="${p.id}" ${p.id === selected ? "selected" : ""}>${esc(priorityLabel(p.id))}</option>`).join("");
}

// ------------------------------------------------------------------ documents

/** The template as the page shows it, built from the live view ({doc, keys}). */
function templateOf(view) {
    const doc = view?.doc || {};
    const fields = Array.isArray(doc.fields) ? doc.fields : [];
    return {
        sections: SECTION_DEFS.map((def) => ({
            id: def.id,
            title: doc.sections?.[def.id]?.title || def.title,
            items: fields
                .map((f, index) => ({ f, index, key: view.keys?.[index] }))
                .filter(({ f }) => f && (f.section || "pre") === def.id)
                .map(({ f, index, key }) => ({
                    id: String(f.id || ""),
                    text: typeof f.text === "string" ? f.text : "",
                    priority: PRIORITIES.some((p) => p.id === f.priority) ? f.priority : DEFAULT_PRIORITY,
                    index,
                    key
                }))
        }))
    };
}

function currentTemplate() {
    return templateOf(state.tpl?.lastView);
}

/** Items that count when filling out (blank items still being typed are skipped). */
function fillableItems(section) {
    return section.items.filter((it) => it.id && it.text.trim());
}

function allItemIds() {
    return currentTemplate().sections.flatMap((s) => fillableItems(s).map((it) => it.id));
}

function completionOf(view) {
    const doc = view?.doc || {};
    return {
        checked: doc.checked && typeof doc.checked === "object" && !Array.isArray(doc.checked) ? doc.checked : {},
        notes: typeof doc.notes === "string" ? doc.notes : ""
    };
}

function currentCompletion() {
    return completionOf(state.match?.lastView);
}

function isComplete(completion) {
    const ids = allItemIds();
    return ids.length > 0 && ids.every((id) => completion?.checked?.[id]);
}

// <option> elements can't be styled with ::after in most browsers, so finished matches get a text prefix.
function markOptionDone(opt, done) {
    const label = opt.textContent.replace(/^✓ /, "");
    opt.textContent = done ? `✓ ${label}` : label;
}

// ------------------------------------------------------------------ live sessions

function makeStatusBar(host) {
    const bar = collab().createStatusBar(host);
    const update = bar.update;
    bar.update = (status) => {
        update(status);
        const textEl = bar.element.querySelector(".collab-status-text");
        if (textEl) textEl.textContent = statusLabel(status);
    };
    return bar;
}

/** A live session plus the REST calls used to load it and to save while the socket can't connect. */
function openSession({ url, restGet, restPut, bar, offlineButton, onChange }) {
    const session = new (collab().ConfigCollabSession)({
        url,
        onView: () => onChange(),
        onStatus: (status) => {
            bar.update(status);
            updateOfflineButton(session, offlineButton);
        },
        onPresence: (editors) => {
            bar.setEditors(editors, session.sid);
            if (session === state.tpl) decorateEditors();
        },
        onNotice: (level, message) => {
            if (message) Obsidianscout.showToast(message, level === "error" ? "error" : level === "success" ? "success" : "info");
        }
    });
    session.restGet = restGet;
    session.restPut = restPut;
    bar.update("connecting");
    bar.setEditors([], session.sid);
    return session;
}

function updateOfflineButton(session, button) {
    if (!button) return;
    button.classList.toggle("hidden", !(session && !session.isLive && session.hasUnsavedChanges));
}

/** Saves edits made while offline by merging them into the latest saved copy. */
async function saveOffline(session, button) {
    if (!session) return;
    try {
        await session.saveViaRest(
            async () => parseJson((await session.restGet())?.dataJson, {}),
            (doc) => session.restPut(JSON.stringify(doc))
        );
        Obsidianscout.showToast(t("checklist.toast.changes_saved", "Changes saved"), "success");
    } catch (err) {
        Obsidianscout.showToast(err.message || t("checklist.toast.save_error", "Could not save yet"), "error");
    }
    updateOfflineButton(session, button);
}

/** Applies a change to a live document; re-renders unless the change came from the input being typed in. */
function edit(session, mutate, rerender = true) {
    if (!session) return;
    const next = clone(session.lastView.doc);
    mutate(next);
    session.commit(next);
    updateOfflineButton(session, session === state.tpl ? el.tplOffline : el.matchOffline);
    if (rerender) renderAll();
}

async function openTemplateSession() {
    const restGet = () => Obsidianscout.request("/api/match-checklist/template");
    state.tpl = openSession({
        url: "/api/match-checklist/collab?template=1",
        restGet,
        restPut: (dataJson) => Obsidianscout.request("/api/match-checklist/template", { method: "PUT", json: { dataJson } }),
        bar: state.tplBar,
        offlineButton: el.tplOffline,
        onChange: () => {
            refreshMatchOptionMarks();
            renderAll();
        }
    });
    try {
        state.tpl.load(parseJson((await restGet())?.dataJson, {}));
    } catch (err) {
        Obsidianscout.showToast(err.message || t("checklist.toast.load_templates_error", "Failed to load checklists"), "error");
    }
    state.tpl.connect();
}

async function openMatchSession(eventKey, matchKey) {
    if (state.match) state.match.close();
    state.match = null;
    el.matchBarHost.classList.toggle("hidden", !matchKey);
    updateOfflineButton(null, el.matchOffline);
    if (!eventKey || !matchKey) return;

    const q = `eventKey=${encodeURIComponent(eventKey)}&matchKey=${encodeURIComponent(matchKey)}`;
    const restGet = () => Obsidianscout.request(`/api/match-checklist?${q}`);
    const session = openSession({
        url: `/api/match-checklist/collab?${q}`,
        restGet,
        restPut: (dataJson) => Obsidianscout.request("/api/match-checklist", { method: "PUT", json: { eventKey, matchKey, dataJson } }),
        bar: state.matchBar,
        offlineButton: el.matchOffline,
        onChange: () => {
            if (state.match !== session) return;
            state.eventCompletions[matchKey] = completionOf(session.lastView);
            refreshMatchOptionMarks();
            renderAll();
        }
    });
    state.match = session;
    try {
        const doc = parseJson((await restGet())?.dataJson, {});
        if (state.match !== session) return;
        session.load(doc);
    } catch (err) {
        Obsidianscout.showToast(err.message || t("checklist.toast.load_match_error", "Failed to load checklist"), "error");
    }
    if (state.match === session) session.connect();
}

// ------------------------------------------------------------------ bootstrapping

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;
    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();
    state.me = me;

    ["event", "match", "our-matches", "match-controls", "disabled", "fill", "edit", "fill-sections", "edit-sections",
        "match-title", "status", "reset", "notes", "starter", "tab-fill", "tab-edit", "tpl-bar-host", "match-bar-host",
        "tpl-offline", "match-offline"].forEach((id) => {
        el[id.replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = document.getElementById(`checklist-${id}`);
    });

    if (!me.nonScoutingToolsEnabled) {
        el.disabled.classList.remove("hidden");
        el.matchControls.classList.add("hidden");
        el.tabFill.disabled = true;
        el.tabEdit.disabled = true;
        return;
    }
    if (!collab()) {
        Obsidianscout.showToast(t("checklist.toast.live_failed", "Live editing failed to load; refresh the page"), "error");
        return;
    }

    state.tplBar = makeStatusBar(el.tplBarHost);
    state.matchBar = makeStatusBar(el.matchBarHost);
    el.matchBarHost.classList.add("hidden");

    wireTabs();
    wireFill();
    wireEdit();

    await openTemplateSession();
    await initEventSelect();
    setMode(currentTemplate().sections.some((s) => fillableItems(s).length) ? "fill" : "edit");

    window.addEventListener("obsidianscout:languagechange", () => {
        refreshMatchOptionMarks();
        renderMatchOptions();
        renderAll();
        if (window.Obsidianscout && typeof Obsidianscout.applyTranslations === "function") {
            Obsidianscout.applyTranslations();
        }
    });

    window.addEventListener("beforeunload", (e) => {
        if (state.tpl?.hasUnsavedChanges || state.match?.hasUnsavedChanges) {
            e.preventDefault();
            e.returnValue = "";
        }
    });
});

function setMode(mode) {
    state.mode = mode;
    el.tabFill.setAttribute("aria-selected", String(mode === "fill"));
    el.tabEdit.setAttribute("aria-selected", String(mode === "edit"));
    el.tabFill.classList.toggle("ghost", mode !== "fill");
    el.tabEdit.classList.toggle("ghost", mode !== "edit");
    el.fill.classList.toggle("hidden", mode !== "fill");
    el.edit.classList.toggle("hidden", mode !== "edit");
    el.matchControls.classList.toggle("hidden", mode !== "fill");
    if (mode === "fill") refreshMatchOptionMarks();
    renderAll();
}

function wireTabs() {
    el.tabFill.addEventListener("click", () => setMode("fill"));
    el.tabEdit.addEventListener("click", () => setMode("edit"));
}

function renderAll() {
    if (state.mode === "fill") collab().preserveFocus(el.fillSections, renderFill);
    else collab().preserveFocus(el.editSections, renderEdit);
}

// ------------------------------------------------------------------ event + match pickers

async function initEventSelect() {
    let settings = {};
    try {
        const res = await Obsidianscout.request("/api/settings");
        settings = res.settings || res;
    } catch (_) { /* offline */ }
    state.eventKey = String(settings.eventKey || "").toLowerCase();
    const linkedEvent = new URLSearchParams(location.search).get("event");
    if (linkedEvent) state.eventKey = linkedEvent.toLowerCase();

    let events = [];
    try {
        events = await Obsidianscout.request(`/api/events?year=${settings.year || new Date().getFullYear()}&cached=1`) || [];
    } catch (_) { /* optional */ }

    el.event.innerHTML = "";
    const keys = new Set();
    const add = (key, label) => {
        if (!key || keys.has(key)) return;
        keys.add(key);
        const opt = document.createElement("option");
        opt.value = key;
        opt.textContent = label;
        el.event.appendChild(opt);
    };
    if (state.eventKey) add(state.eventKey, `${state.eventKey} (${t("checklist.event.current", "current")})`);
    (Array.isArray(events) ? events : []).forEach((ev) => add(String(ev.eventKey).toLowerCase(), `${ev.name || ev.eventKey} (${ev.eventKey})`));
    if (!keys.size) {
        const opt = document.createElement("option");
        opt.value = "";
        opt.textContent = t("checklist.event.none", "No event selected");
        el.event.appendChild(opt);
    }
    el.event.value = state.eventKey;
    el.event.addEventListener("change", () => loadEvent(el.event.value));
    el.ourMatches.addEventListener("change", () => renderMatchOptions());
    el.match.addEventListener("change", () => onMatchSelected(el.match.value));

    await loadEvent(state.eventKey);
}

function compLevelRank(compLevel) {
    const level = String(compLevel || "").trim().toLowerCase();
    if (["p", "pr", "pm", "practice"].includes(level)) return 0;
    if (["q", "qm", "qual", "qualification"].includes(level)) return 1;
    return 2;
}

function compareMatches(a, b) {
    return (compLevelRank(a.compLevel) - compLevelRank(b.compLevel)) ||
        ((a.setNumber || 1) - (b.setNumber || 1)) ||
        ((a.matchNumber || 0) - (b.matchNumber || 0));
}

function teamNumberOf(teamKey) {
    const m = String(teamKey || "").match(/\d+/);
    return m ? Number(m[0]) : NaN;
}

function isOurMatch(match) {
    const ours = Number(state.me.teamNumber);
    return [...(match.redTeams || []), ...(match.blueTeams || [])].some((k) => teamNumberOf(k) === ours);
}

function matchLabel(match) {
    return Obsidianscout.localize(match.label) ||
        (match.compLevel ? `${String(match.compLevel).toUpperCase()} ${match.matchNumber || ""}`.trim() : match.matchKey);
}

function allianceOf(match) {
    const ours = Number(state.me.teamNumber);
    if ((match.redTeams || []).some((k) => teamNumberOf(k) === ours)) return "Red";
    if ((match.blueTeams || []).some((k) => teamNumberOf(k) === ours)) return "Blue";
    return "";
}

function allianceName(color) {
    if (color === "Red") return t("checklist.alliance.red", "Red");
    if (color === "Blue") return t("checklist.alliance.blue", "Blue");
    return color;
}

function sectionDisplayTitle(section) {
    if (section.id === "pre" && section.title === "Pre-Match") return t("checklist.section.pre", "Pre-Match");
    if (section.id === "field" && section.title === "On the Field") return t("checklist.section.field", "On the Field");
    return section.title;
}

async function loadEvent(eventKey) {
    state.eventKey = eventKey || "";
    state.matches = [];
    state.eventCompletions = {};
    if (state.eventKey) {
        const [matches, completions] = await Promise.all([
            Obsidianscout.request(`/api/matches?eventKey=${encodeURIComponent(state.eventKey)}`).catch(() => []),
            Obsidianscout.request(`/api/match-checklist/event?eventKey=${encodeURIComponent(state.eventKey)}`).catch(() => [])
        ]);
        state.matches = (Array.isArray(matches) ? matches : []).slice().sort(compareMatches);
        (Array.isArray(completions) ? completions : []).forEach((c) => {
            state.eventCompletions[c.matchKey] = completionOf({ doc: parseJson(c.dataJson, {}) });
        });
    }
    state.matchKey = "";
    renderMatchOptions();
}

function refreshMatchOptionMarks() {
    if (!el.match) return;
    [...el.match.options].forEach((opt) => {
        if (opt.value && opt.value !== "__custom__") markOptionDone(opt, isComplete(state.eventCompletions[opt.value]));
    });
}

function renderMatchOptions() {
    const previous = state.matchKey;
    const onlyOurs = el.ourMatches.checked;
    const visible = state.matches.filter((m) => !onlyOurs || isOurMatch(m));

    el.match.innerHTML = "";
    const placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = !state.eventKey ? t("checklist.match.select_event_first", "Select an event first")
        : visible.length ? t("checklist.match.select_match_placeholder", "— Select Match —")
        : onlyOurs && state.matches.length ? t("checklist.match.no_matches_for_team", "No matches for our team yet") : t("checklist.match.no_matches_found", "No matches found");
    el.match.appendChild(placeholder);

    visible.forEach((m) => {
        const opt = document.createElement("option");
        opt.value = m.matchKey;
        const alliance = allianceOf(m);
        opt.textContent = alliance ? `${matchLabel(m)} (${allianceName(alliance)})` : matchLabel(m);
        el.match.appendChild(opt);
    });

    // Practice matches and anything not on the schedule.
    Object.keys(state.eventCompletions)
        .filter((k) => k.startsWith("custom_") && !visible.some((m) => m.matchKey === k))
        .forEach((k) => {
            const opt = document.createElement("option");
            opt.value = k;
            opt.textContent = k.slice("custom_".length).replace(/_/g, " ");
            el.match.appendChild(opt);
        });

    if (state.eventKey) {
        const custom = document.createElement("option");
        custom.value = "__custom__";
        custom.textContent = t("checklist.match.custom_option", "+ Custom / practice match...");
        el.match.appendChild(custom);
    }
    refreshMatchOptionMarks();

    const linked = new URLSearchParams(location.search).get("match");
    const target = [previous, linked].find((k) => k && [...el.match.options].some((o) => o.value === k));
    if (target) {
        el.match.value = target;
        if (target !== state.matchKey || !state.match) onMatchSelected(target);
    } else {
        // Default to our next match without a finished checklist.
        const next = visible.find((m) => !isComplete(state.eventCompletions[m.matchKey]));
        if (next && onlyOurs) {
            el.match.value = next.matchKey;
            onMatchSelected(next.matchKey);
        } else {
            onMatchSelected("");
        }
    }
}

async function onMatchSelected(matchKey) {
    if (matchKey === "__custom__") {
        const name = (prompt(t("checklist.match.custom_prompt", "Name this match (e.g. Practice 3):")) || "").trim();
        const slug = name.replace(/[^A-Za-z0-9 _-]/g, "").trim().replace(/\s+/g, "_").slice(0, 50);
        if (!slug) {
            el.match.value = state.matchKey;
            return;
        }
        matchKey = `custom_${slug}`;
        if (![...el.match.options].some((o) => o.value === matchKey)) {
            const opt = document.createElement("option");
            opt.value = matchKey;
            opt.textContent = name;
            el.match.insertBefore(opt, el.match.lastElementChild);
        }
        el.match.value = matchKey;
    }

    state.matchKey = matchKey || "";
    await openMatchSession(state.eventKey, state.matchKey);
    renderAll();
}

// ------------------------------------------------------------------ fill out

function renderFill() {
    const hasMatch = Boolean(state.matchKey && state.match);
    const match = state.matches.find((m) => m.matchKey === state.matchKey);
    const alliance = allianceOf(match);
    const allianceStr = alliance ? ` · ${t("checklist.alliance_label", "{alliance} alliance").replace("{alliance}", allianceName(alliance))}` : "";
    el.matchTitle.textContent = !state.matchKey ? t("checklist.select_match", "Select a match")
        : match ? `${matchLabel(match)}${allianceStr}`
        : state.matchKey.replace(/^custom_/, "").replace(/_/g, " ");
    el.reset.disabled = !hasMatch;
    el.notes.disabled = !hasMatch;
    const completion = currentCompletion();
    // Don't replace what someone is typing; their next keystroke carries the whole note anyway.
    if (document.activeElement !== el.notes) el.notes.value = completion.notes;
    renderStatus(completion);

    const template = currentTemplate();
    if (!template.sections.some((s) => fillableItems(s).length)) {
        el.fillSections.innerHTML = `<div class="card"><p class="checklist-empty" style="margin:0;">${t("checklist.fill.empty_template", "No checklist items yet. Use <strong>Edit Checklists</strong> to create your pre-match and on-the-field lists.")}</p></div>`;
        return;
    }

    el.fillSections.innerHTML = template.sections.map((section) => {
        // Sort by priority; within a priority keep the order set in Edit Checklists.
        const items = fillableItems(section)
            .slice()
            .sort((a, b) => (priorityRank(a.priority) - priorityRank(b.priority)) || (a.index - b.index));
        const done = items.filter((it) => completion.checked[it.id]).length;
        const total = items.length;
        const pct = total ? Math.round((done / total) * 100) : 0;
        const rows = items.map((it) => {
            const check = completion.checked[it.id];
            const meta = check ? `<span class="checklist-item-meta" title="${esc(new Date(check.at).toLocaleString())}">${esc(check.by)}</span>` : "";
            return `<li class="checklist-item${check ? " checked" : ""}" data-collab-key="${esc(it.key)}">
                <label>
                    <input type="checkbox" data-item="${esc(it.id)}" ${check ? "checked" : ""} ${hasMatch ? "" : "disabled"} />
                    <span class="checklist-item-text">${esc(it.text.trim())}</span>
                    <span class="checklist-priority priority-${esc(it.priority)}">${esc(priorityLabel(it.priority))}</span>
                    ${meta}
                </label>
            </li>`;
        }).join("");
        const secTitle = sectionDisplayTitle(section);
        return `<div class="card checklist-section">
            <h2><span>${esc(secTitle)}</span><span class="checklist-status">${done}/${total}</span></h2>
            <div class="checklist-progress${total && done === total ? " done" : ""}"><span style="width:${pct}%"></span></div>
            ${total ? `<ul class="checklist-items">${rows}</ul>` : `<p class="checklist-empty">${t("checklist.fill.empty_section", "No items in this list.")}</p>`}
        </div>`;
    }).join("");
}

function renderStatus(completion) {
    if (!state.matchKey) {
        el.status.textContent = t("checklist.status.pick_match", "Pick a match to start checking items off.");
        return;
    }
    const items = currentTemplate().sections.flatMap(fillableItems);
    const done = items.filter((it) => completion.checked[it.id]).length;
    const doneText = items.length && done === items.length
        ? t("checklist.status.all_complete", "All checks complete ✓")
        : t("checklist.status.done_count", "{done} of {total} done").replace("{done}", done).replace("{total}", items.length);
    const parts = [doneText];
    const criticalLeft = items.filter((it) => it.priority === "critical" && !completion.checked[it.id]).length;
    if (criticalLeft) {
        parts.push(t("checklist.status.critical_left", "{count} critical left").replace("{count}", criticalLeft));
    }
    el.status.textContent = parts.join(" · ");
}

function wireFill() {
    el.fillSections.addEventListener("change", (e) => {
        const box = e.target.closest("input[type=checkbox][data-item]");
        if (!box || !state.match) return;
        const id = box.dataset.item;
        const checked = box.checked;
        const mark = { by: state.me.username, at: Date.now() };
        Obsidianscout.triggerHaptic?.("light");
        edit(state.match, (doc) => {
            doc.checked = doc.checked && typeof doc.checked === "object" && !Array.isArray(doc.checked) ? doc.checked : {};
            if (checked) doc.checked[id] = doc.checked[id] || mark;
            else delete doc.checked[id];
        });
        state.eventCompletions[state.matchKey] = currentCompletion();
        refreshMatchOptionMarks();
    });

    el.notes.addEventListener("input", () => {
        if (!state.match) return;
        const notes = el.notes.value;
        edit(state.match, (doc) => { doc.notes = notes; }, false);
    });

    el.reset.addEventListener("click", () => {
        if (!state.match) return;
        if (!confirm(t("checklist.reset_confirm", "Uncheck every item for this match?"))) return;
        edit(state.match, (doc) => { doc.checked = {}; });
        state.eventCompletions[state.matchKey] = currentCompletion();
        refreshMatchOptionMarks();
    });

    el.matchOffline.addEventListener("click", () => saveOffline(state.match, el.matchOffline));
}

// ------------------------------------------------------------------ edit template

function renderEdit() {
    const template = currentTemplate();
    el.editSections.innerHTML = template.sections.map((section) => {
        const rows = section.items.map((it, idx) => `
            <li class="checklist-edit-row" data-collab-key="${esc(it.key)}" data-index="${it.index}">
                <input type="text" value="${esc(it.text)}" maxlength="200" aria-label="${esc(t("checklist.edit.item_aria", "Checklist item"))}" data-field="text" />
                <select data-field="priority" aria-label="${esc(t("checklist.edit.priority_aria", "Priority"))}" class="checklist-priority-select">${priorityOptions(it.priority)}</select>
                <button type="button" class="btn ghost" data-action="up" data-section="${esc(section.id)}" title="${esc(t("checklist.edit.move_up", "Move up"))}" aria-label="${esc(t("checklist.edit.move_up", "Move up"))}" ${idx === 0 ? "disabled" : ""}>&uarr;</button>
                <button type="button" class="btn ghost" data-action="down" data-section="${esc(section.id)}" title="${esc(t("checklist.edit.move_down", "Move down"))}" aria-label="${esc(t("checklist.edit.move_down", "Move down"))}" ${idx === section.items.length - 1 ? "disabled" : ""}>&darr;</button>
                <button type="button" class="btn ghost" data-action="remove" title="${esc(t("checklist.edit.remove", "Remove"))}" aria-label="${esc(t("checklist.edit.remove", "Remove"))}">&times;</button>
            </li>`).join("");
        return `<div class="card checklist-section" data-section="${esc(section.id)}">
            <div class="field" style="margin-top:0;" data-collab-key="title-${esc(section.id)}">
                <label>${esc(t("checklist.edit.list_name", "List name"))}</label>
                <input type="text" value="${esc(sectionDisplayTitle(section))}" maxlength="60" data-field="title" data-section="${esc(section.id)}" />
            </div>
            <ul class="checklist-items">${rows || `<li class="checklist-empty">${esc(t("checklist.edit.no_items", "No items yet."))}</li>`}</ul>
            <form class="checklist-edit-row mt-12" data-add="${esc(section.id)}" data-collab-key="add-${esc(section.id)}" data-no-loading="true" autocomplete="off">
                <input type="text" placeholder="${esc(t("checklist.edit.add_placeholder", "Add an item..."))}" maxlength="200" aria-label="${esc(t("checklist.edit.new_item_aria", "New checklist item"))}" />
                <select aria-label="${esc(t("checklist.edit.priority_aria", "Priority"))}" class="checklist-priority-select">${priorityOptions(DEFAULT_PRIORITY)}</select>
                <button type="submit" class="btn">${esc(t("checklist.edit.add_btn", "Add"))}</button>
            </form>
        </div>`;
    }).join("");
    decorateEditors();
}

/** Shows which list item each teammate is working in. */
function decorateEditors() {
    if (state.tpl && state.mode === "edit") collab().decorateCards(el.editSections, state.tpl.editors, state.tpl.sid);
}

function wireEdit() {
    el.editSections.addEventListener("input", (e) => {
        const input = e.target;
        if (input.dataset.field === "title") {
            const section = input.dataset.section;
            const value = input.value;
            edit(state.tpl, (doc) => {
                doc.sections = doc.sections && typeof doc.sections === "object" ? doc.sections : {};
                doc.sections[section] = { ...(doc.sections[section] || {}), title: value };
            }, false);
        } else if (input.dataset.field === "text") {
            const index = Number(input.closest("[data-index]").dataset.index);
            const value = input.value;
            edit(state.tpl, (doc) => { doc.fields[index].text = value; }, false);
        }
    });

    el.editSections.addEventListener("change", (e) => {
        const select = e.target;
        if (select.dataset.field !== "priority") return;
        const index = Number(select.closest("[data-index]").dataset.index);
        edit(state.tpl, (doc) => { doc.fields[index].priority = select.value; });
    });

    // Tell teammates which item this editor is in.
    el.editSections.addEventListener("focusin", (e) => {
        const key = e.target.closest("[data-collab-key]")?.getAttribute("data-collab-key");
        state.tpl?.setFocus(key && !key.startsWith("add-") && !key.startsWith("title-") ? key : null);
    });
    el.editSections.addEventListener("focusout", () => state.tpl?.setFocus(null));

    el.editSections.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-action]");
        if (!btn) return;
        const index = Number(btn.closest("[data-index]").dataset.index);
        const action = btn.dataset.action;
        edit(state.tpl, (doc) => {
            if (action === "remove") {
                doc.fields.splice(index, 1);
                return;
            }
            // Swap with the neighbouring item of the same list (both lists share one ordered array).
            const section = btn.dataset.section;
            const sameList = doc.fields.map((f, i) => ((f.section || "pre") === section ? i : -1)).filter((i) => i >= 0);
            const pos = sameList.indexOf(index);
            const other = sameList[action === "up" ? pos - 1 : pos + 1];
            if (other === undefined) return;
            [doc.fields[index], doc.fields[other]] = [doc.fields[other], doc.fields[index]];
        });
    });

    el.editSections.addEventListener("submit", (e) => {
        const form = e.target.closest("form[data-add]");
        if (!form) return;
        e.preventDefault();
        const input = form.querySelector("input");
        const text = input.value.trim();
        if (!text) return;
        const priority = form.querySelector("select")?.value || DEFAULT_PRIORITY;
        const section = form.dataset.add;
        input.value = "";
        edit(state.tpl, (doc) => {
            doc.fields = Array.isArray(doc.fields) ? doc.fields : [];
            doc.fields.push({ id: newId(), section, text, priority });
        });
        el.editSections.querySelector(`form[data-add="${CSS.escape(section)}"] input`)?.focus();
    });

    el.starter.addEventListener("click", () => {
        const template = currentTemplate();
        const hasItems = template.sections.some((s) => s.items.length);
        if (hasItems && !confirm(t("checklist.edit.starter_confirm", "Add the starter items to your lists? Existing items are kept."))) return;
        edit(state.tpl, (doc) => {
            doc.fields = Array.isArray(doc.fields) ? doc.fields : [];
            template.sections.forEach((s) => {
                const existing = new Set(s.items.map((it) => it.text.trim().toLowerCase()));
                (STARTER_ITEMS[s.id] || []).forEach(({ text, priority }) => {
                    if (!existing.has(text.toLowerCase())) doc.fields.push({ id: newId(), section: s.id, text, priority });
                });
            });
        });
    });

    el.tplOffline.addEventListener("click", () => saveOffline(state.tpl, el.tplOffline));
}
