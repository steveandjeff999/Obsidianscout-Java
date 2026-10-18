/**
 * Match Checklist page - ObsidianScout
 * Drive-team checklists. The team builds any number of lists; each list has a frequency that decides how
 * often it is filled out. Shown only when "Non-Scouting Tools" is enabled in Personal Settings.
 *
 * Every document is edited live, the same way as the config editors (js/services/config-collab.js):
 * everyone's edits merge as they happen and autosave on the server. Edits that can't reach the server are
 * kept on this device (localStorage drafts) and saved automatically once it is reachable again.
 *
 * Template doc:
 *   { lists: { [listId]: { title, order, frequency, every } }, fields: [{ id, section: listId, text, priority }] }
 *   (Older templates have no `lists`; they had the fixed lists "pre" and "field", titled by `sections`.)
 * Completion doc (one per match, per day "day_YYYY-MM-DD", or per event "event"):
 *   { checked: { [itemId]: { by, at } }, notes, fields: [] }
 *
 * Frequencies: "match" (every match), "every_n" (every `every` of our matches, starting with our first),
 * "daily" (once a day, shared by all matches that day), "event" (once for the whole event).
 * The app (lib/screens/match_checklist_screen.dart) reads and writes the same documents.
 */

const TEMPLATE_KEY = "__template__";
const EVENT_SCOPE_KEY = "event";
const FREQUENCIES = ["match", "every_n", "daily", "event"];
const LEGACY_LISTS = ["pre", "field"];

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

const TICK_MS = 1000;

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : (fallback || key);
}

function fmt(text, values) {
    return String(text).replace(/\{(\w+)\}/g, (m, k) => (values[k] !== undefined ? values[k] : m));
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
    tpl: null,              // template room
    fillRooms: {},          // scope ("match" | "day" | "event") -> room shown in Fill Out
    tplBar: null,
    matchBar: null,
    battery: { inv: null, invReady: null, plan: null }, // battery tracker rooms: inventory + this event's rotation plan
    hadPendingOffline: false
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

function isPlainObject(v) {
    return v !== null && typeof v === "object" && !Array.isArray(v);
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

function todayKey() {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, "0");
    return `day_${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

// ------------------------------------------------------------------ template documents

/** The lists defined by a template doc, in display order. */
function listsOf(doc) {
    if (isPlainObject(doc?.lists)) {
        return Object.entries(doc.lists)
            .filter(([, l]) => isPlainObject(l))
            .map(([id, l]) => ({
                id,
                title: typeof l.title === "string" ? l.title : "",
                order: Number.isFinite(l.order) ? l.order : 0,
                frequency: FREQUENCIES.includes(l.frequency) ? l.frequency : "match",
                every: Math.max(2, Math.min(99, parseInt(l.every, 10) || 2))
            }))
            .sort((a, b) => (a.order - b.order) || a.id.localeCompare(b.id));
    }
    // Older template: the fixed Pre-Match / On the Field lists.
    return LEGACY_LISTS.map((id, order) => ({
        id, order, frequency: "match", every: 2,
        title: typeof doc?.sections?.[id]?.title === "string" ? doc.sections[id].title : ""
    }));
}

/** Converts an older template to the `lists` form before changing its lists. */
function ensureLists(doc) {
    if (isPlainObject(doc.lists)) return doc.lists;
    doc.lists = {};
    listsOf(doc).forEach((l) => {
        doc.lists[l.id] = { title: l.title, order: l.order, frequency: l.frequency, every: l.every };
    });
    return doc.lists;
}

/** The template as the page shows it, built from the live view ({doc, keys}). */
function templateOf(view) {
    const doc = view?.doc || {};
    const fields = Array.isArray(doc.fields) ? doc.fields : [];
    return {
        lists: listsOf(doc).map((list) => ({
            ...list,
            items: fields
                .map((f, index) => ({ f, index, key: view.keys?.[index] }))
                .filter(({ f }) => f && (f.section || "pre") === list.id)
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
    return templateOf(state.tpl?.session?.lastView);
}

/** Items that count when filling out (blank items still being typed are skipped). */
function fillableItems(list) {
    return list.items.filter((it) => it.id && it.text.trim());
}

function listTitle(list) {
    const title = list.title.trim();
    if (list.id === "pre" && (!title || title === "Pre-Match")) return t("checklist.section.pre", "Pre-Match");
    if (list.id === "field" && (!title || title === "On the Field")) return t("checklist.section.field", "On the Field");
    return title || t("checklist.list.untitled", "Untitled list");
}

function frequencyLabel(list) {
    switch (list.frequency) {
        case "every_n": return fmt(t("checklist.freq.every_n", "Every {n} matches"), { n: list.every });
        case "daily": return t("checklist.freq.daily", "Once a day");
        case "event": return t("checklist.freq.event", "Once per event");
        default: return t("checklist.freq.match", "Every match");
    }
}

function frequencyOptions(selected) {
    const labels = {
        match: t("checklist.freq.match", "Every match"),
        every_n: t("checklist.freq.every_n_option", "Every few matches"),
        daily: t("checklist.freq.daily", "Once a day"),
        event: t("checklist.freq.event", "Once per event")
    };
    return FREQUENCIES.map((f) => `<option value="${f}" ${f === selected ? "selected" : ""}>${esc(labels[f])}</option>`).join("");
}

/** Which completion doc a list is filled out in: "match", "day" or "event". */
function scopeOf(list) {
    if (list.frequency === "daily") return "day";
    if (list.frequency === "event") return "event";
    return "match";
}

function completionOf(view) {
    const doc = view?.doc || {};
    return {
        checked: isPlainObject(doc.checked) ? doc.checked : {},
        notes: typeof doc.notes === "string" ? doc.notes : ""
    };
}

// ------------------------------------------------------------------ "every N matches" scheduling

function ourMatches() {
    return state.matches.filter(isOurMatch);
}

/** Whether a list is filled out for a match. Matches outside our schedule (custom/practice) always are. */
function isDueForMatch(list, matchKey) {
    if (list.frequency !== "every_n") return true;
    const idx = ourMatches().findIndex((m) => m.matchKey === matchKey);
    return idx < 0 || idx % list.every === 0;
}

function nextDueMatch(list, matchKey) {
    const ours = ourMatches();
    const idx = ours.findIndex((m) => m.matchKey === matchKey);
    return ours.find((m, i) => i > idx && i % list.every === 0) || null;
}

/** A match is done when every item of its per-match lists that are due for it is checked. */
function isMatchComplete(matchKey, completion) {
    if (!completion) return false;
    const ids = currentTemplate().lists
        .filter((l) => scopeOf(l) === "match" && isDueForMatch(l, matchKey))
        .flatMap((l) => fillableItems(l).map((it) => it.id));
    return ids.length > 0 && ids.every((id) => completion.checked?.[id]);
}

// <option> elements can't be styled with ::after in most browsers, so finished matches get a text prefix
// and the battery the match needs (Battery Tracker) is added to the end.
function labelOption(opt, done, batteryLabel) {
    if (opt.dataset.label === undefined) opt.dataset.label = opt.textContent.replace(/^✓ /, "");
    const label = batteryLabel ? `${opt.dataset.label} · ${batteryLabel}` : opt.dataset.label;
    opt.textContent = done ? `✓ ${label}` : label;
}

// ------------------------------------------------------------------ live rooms

// Checklist documents (template, per match/day/event) and the battery tracker's documents, each kept live
// by js/services/collab-rooms.js with offline drafts on this device.
let checklistRooms = null;
let batteryRooms = null;

function roomIdFor(eventKey, matchKey) {
    return matchKey === TEMPLATE_KEY ? "template" : `${eventKey}|${matchKey}`;
}

function setupRooms() {
    const userKey = `${state.me.teamNumber}:${state.me.program || "FRC"}:${state.me.userId || state.me.id || state.me.username}`;
    checklistRooms = window.ObsidianscoutCollabRooms.create({
        draftKey: () => `obsidianscout:checklist-drafts:${userKey}`,
        roomId: roomIdFor,
        endpoints: (eventKey, matchKey) => {
            if (matchKey === TEMPLATE_KEY) {
                return {
                    url: "/api/match-checklist/collab?template=1",
                    get: () => Obsidianscout.request("/api/match-checklist/template"),
                    put: (dataJson) => Obsidianscout.request("/api/match-checklist/template", { method: "PUT", json: { dataJson } })
                };
            }
            const q = `eventKey=${encodeURIComponent(eventKey)}&matchKey=${encodeURIComponent(matchKey)}`;
            return {
                url: `/api/match-checklist/collab?${q}`,
                get: () => Obsidianscout.request(`/api/match-checklist?${q}`),
                put: (dataJson) => Obsidianscout.request("/api/match-checklist", { method: "PUT", json: { eventKey, matchKey, dataJson } })
            };
        },
        keepOpen: (room) => room === state.tpl,
        onChange: onRoomChange,
        onStatus: onRoomStatus,
        onPresence: onRoomPresence
    });
    batteryRooms = window.ObsidianscoutCollabRooms.create(Object.assign(battery().roomConfig(state.me), {
        keepOpen: (room) => room === state.battery.inv || room === state.battery.plan,
        onChange: () => {
            refreshMatchOptionMarks();
            renderAll();
            updateOfflineBanner();
        },
        onStatus: () => updateOfflineBanner()
    }));
}

function acquireRoom(eventKey, matchKey) {
    return checklistRooms.acquire(eventKey, matchKey);
}

function releaseRoom(room) {
    checklistRooms.release(room);
}

function onRoomChange(room) {
    if (room.docKey !== TEMPLATE_KEY && room.eventKey === state.eventKey) {
        state.eventCompletions[room.docKey] = completionOf(room.session.lastView);
    }
    refreshMatchOptionMarks();
    // A list's frequency may have changed, which can need a day/event document opened.
    if (room === state.tpl && state.mode === "fill") syncFillRooms();
    else renderAll();
    updateOfflineBanner();
}

function onRoomStatus(room, status) {
    if (room === state.tpl) state.tplBar?.update(status);
    if (room === state.fillRooms.match) state.matchBar?.update(status);
    updateOfflineBanner();
}

function onRoomPresence(room, editors) {
    if (room === state.tpl) {
        state.tplBar?.setEditors(editors, room.session.sid);
        decorateEditors();
    }
    if (room === state.fillRooms.match) state.matchBar?.setEditors(editors, room.session.sid);
}

/**
 * Housekeeping every second: keeps drafts current, saves over plain HTTP when the live connection can't be
 * made but the network is up, and closes background rooms once everything in them is saved.
 */
function tick() {
    checklistRooms.tick();
    batteryRooms.tick();
    updateOfflineBanner();
}

function updateOfflineBanner() {
    if (!el.offlineBanner || !checklistRooms || !batteryRooms) return;
    const pending = checklistRooms.pendingOffline().length + batteryRooms.pendingOffline().length;
    const show = pending > 0;
    if (show) {
        el.offlineBannerText.textContent = fmt(t("checklist.offline.banner",
            "You're offline. Changes to {count} checklist(s) can't be saved until you're back online. They're kept on this device and will save automatically when you reconnect."),
            { count: pending });
        state.hadPendingOffline = true;
    } else if (state.hadPendingOffline && !checklistRooms.hasUnsaved() && !batteryRooms.hasUnsaved()) {
        state.hadPendingOffline = false;
        Obsidianscout.showToast(t("checklist.offline.saved", "All offline changes saved"), "success");
    }
    el.offlineBanner.classList.toggle("hidden", !show);
}

/** Edits a live document and shares it; re-renders unless the change came from the input being typed in. */
function edit(room, mutate, rerender = true) {
    if (!room) return;
    checklistRooms.edit(room, mutate);
    if (room.docKey !== TEMPLATE_KEY && room.eventKey === state.eventKey) {
        state.eventCompletions[room.docKey] = completionOf(room.session.lastView);
        maybeAutoMarkUsed(room.docKey);
        refreshMatchOptionMarks();
    }
    updateOfflineBanner();
    if (rerender) renderAll();
}

// ------------------------------------------------------------------ batteries (see js/battery-tracker.js)

function battery() {
    return window.ObsidianBattery;
}

/** Opens the battery inventory and this event's rotation plan (the banner stays hidden without batteries). */
async function syncBatteryRooms() {
    const eventKey = state.eventKey;
    // Event switches can overlap; open the inventory only once.
    if (!state.battery.invReady) state.battery.invReady = batteryRooms.acquire("", "").then((room) => { state.battery.inv = room; });
    await state.battery.invReady;
    const current = state.battery.plan;
    if (current?.eventKey === eventKey) return;
    state.battery.plan = null;
    batteryRooms.release(current);
    if (!eventKey) return;
    const room = await batteryRooms.acquire(eventKey, "");
    if (state.eventKey !== eventKey || state.battery.plan) {
        batteryRooms.release(room);
        return;
    }
    state.battery.plan = room;
    refreshMatchOptionMarks();
    renderAll();
}

function batteryInventory() {
    return state.battery.inv?.session.lastView.doc || {};
}

function batteryPlan() {
    return state.battery.plan?.session.lastView.doc || {};
}

/** matchKey -> { batteryId, source, skipped } for our matches at the selected event. */
function batterySchedule() {
    if (!state.battery.plan || state.battery.plan.eventKey !== state.eventKey) return {};
    return battery().computeSchedule(batteryInventory(), batteryPlan(), ourMatches().map((m) => m.matchKey));
}

function batteryName(id) {
    return battery().displayName(battery().batteriesOf(batteryInventory()).find((b) => b.id === id)) || id;
}

function editBatteryPlan(mutate) {
    const room = state.battery.plan;
    if (!room) return;
    batteryRooms.edit(room, mutate);
    refreshMatchOptionMarks();
    updateOfflineBanner();
    renderAll();
}

/** Records the scheduled battery as used once a match's checklist is finished (a Battery Tracker setting). */
function maybeAutoMarkUsed(matchKey) {
    if (!state.battery.plan || !battery().autoMarkUsed(batteryPlan())) return;
    if (battery().usedOf(batteryPlan(), matchKey)) return;
    if (!isMatchComplete(matchKey, state.eventCompletions[matchKey])) return;
    const slot = batterySchedule()[matchKey];
    if (!slot?.batteryId) return;
    editBatteryPlan((doc) => battery().markUsed(doc, matchKey, slot.batteryId));
}

function renderBattery() {
    const host = el.battery;
    if (!host) return;
    const hasBatteries = battery().batteriesOf(batteryInventory()).length > 0;
    const slot = batterySchedule()[state.matchKey];
    if (!hasBatteries || !state.matchKey || !slot) {
        host.classList.add("hidden");
        host.innerHTML = "";
        return;
    }
    host.classList.remove("hidden");
    const label = slot.batteryId
        ? fmt(esc(t("battery.checklist.needs", "Battery: {name}")), { name: `<strong>${esc(batteryName(slot.batteryId))}</strong>` })
        : esc(t("battery.checklist.none", "No battery available: every battery is skipped for this match"));
    const sourceNote = slot.source === "used" ? t("battery.source.used", "Used")
        : slot.source === "pinned" ? t("battery.source.pinned", "Pinned") : "";
    const skippedNote = slot.skipped.length
        ? fmt(t("battery.checklist.skipped", "Skipped: {names}"), { names: slot.skipped.map(batteryName).join(", ") })
        : "";
    const button = (action, text, primary) =>
        `<button type="button" class="btn ${primary ? "" : "ghost "}btn-sm" data-battery-action="${action}">${esc(text)}</button>`;
    host.innerHTML = `<span class="checklist-battery-icon">${battery().BATTERY_ICON}</span>
        <span class="checklist-battery-text"><span>${label}${sourceNote ? ` <span class="checklist-battery-tag">${esc(sourceNote)}</span>` : ""}</span>${skippedNote ? `<span class="checklist-battery-meta">${esc(skippedNote)}</span>` : ""}</span>
        <span class="checklist-section-actions">
            ${slot.batteryId && slot.source !== "used" ? button("skip", t("battery.action.skip", "Skip")) : ""}
            ${slot.skipped.length ? button("unskip", t("battery.action.unskip", "Undo skips")) : ""}
            ${slot.source === "used" ? button("unuse", t("battery.action.unmark_used", "Not used"))
                : slot.batteryId ? button("use", t("battery.action.mark_used", "Mark used"), true) : ""}
        </span>`;
}

function wireBattery() {
    el.battery.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-battery-action]");
        if (!btn || !state.matchKey) return;
        const matchKey = state.matchKey;
        const slot = batterySchedule()[matchKey];
        if (!slot) return;
        Obsidianscout.triggerHaptic?.("light");
        editBatteryPlan((doc) => {
            switch (btn.dataset.batteryAction) {
                case "skip": if (slot.batteryId) battery().skip(doc, matchKey, slot.batteryId, state.me.username); break;
                case "unskip": battery().clearSkips(doc, matchKey); break;
                case "use": if (slot.batteryId) battery().markUsed(doc, matchKey, slot.batteryId); break;
                case "unuse": battery().markUsed(doc, matchKey, ""); break;
            }
        });
    });
}

/** Opens the match/day/event documents the Fill Out view needs and lets go of the others. */
async function syncFillRooms() {
    const lists = currentTemplate().lists;
    const wanted = {
        match: state.eventKey && state.matchKey ? state.matchKey : null,
        day: state.eventKey && lists.some((l) => scopeOf(l) === "day") ? todayKey() : null,
        event: state.eventKey && lists.some((l) => scopeOf(l) === "event") ? EVENT_SCOPE_KEY : null
    };
    const eventKey = state.eventKey;
    for (const scope of Object.keys(wanted)) {
        const current = state.fillRooms[scope];
        const wantedId = wanted[scope] ? roomIdFor(eventKey, wanted[scope]) : null;
        if (current?.id === wantedId) continue;
        state.fillRooms[scope] = null;
        releaseRoom(current);
        if (!wantedId) continue;
        const room = await acquireRoom(eventKey, wanted[scope]);
        // The selection may have changed while this opened.
        if (state.eventKey !== eventKey || state.fillRooms[scope]) {
            releaseRoom(room);
            continue;
        }
        state.fillRooms[scope] = room;
        if (scope === "match") {
            state.matchBar.update(room.session.status);
            state.matchBar.setEditors(room.session.editors, room.session.sid);
        }
    }
    el.matchBarHost.classList.toggle("hidden", !state.fillRooms.match);
    renderAll();
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
        "match-title", "status", "notes", "starter", "add-list", "tab-fill", "tab-edit", "tpl-bar-host", "match-bar-host",
        "offline-banner", "offline-banner-text", "battery"].forEach((id) => {
        el[id.replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = document.getElementById(`checklist-${id}`);
    });

    if (!me.nonScoutingToolsEnabled) {
        el.disabled.classList.remove("hidden");
        el.matchControls.classList.add("hidden");
        el.tabFill.disabled = true;
        el.tabEdit.disabled = true;
        return;
    }
    if (!collab() || !window.ObsidianscoutCollabRooms || !battery() || !window.ObsidianMatchUtils) {
        Obsidianscout.showToast(t("checklist.toast.live_failed", "Live editing failed to load; refresh the page"), "error");
        return;
    }
    setupRooms();

    state.tplBar = makeStatusBar(el.tplBarHost);
    state.matchBar = makeStatusBar(el.matchBarHost);
    el.matchBarHost.classList.add("hidden");

    wireTabs();
    wireFill();
    wireEdit();
    wireBattery();

    state.tpl = await acquireRoom("", TEMPLATE_KEY);
    state.tplBar.update(state.tpl.session.status);
    await initEventSelect();
    setMode(currentTemplate().lists.some((l) => fillableItems(l).length) ? "fill" : "edit");
    checklistRooms.resumeDrafts();
    batteryRooms.resumeDrafts();
    setInterval(tick, TICK_MS);
    window.addEventListener("online", tick);

    window.addEventListener("obsidianscout:languagechange", () => {
        renderMatchOptions();
        renderAll();
        if (window.Obsidianscout && typeof Obsidianscout.applyTranslations === "function") {
            Obsidianscout.applyTranslations();
        }
    });

    // Drafts survive a reload, so leaving only needs a warning while the newest edits are still being written.
    window.addEventListener("beforeunload", () => {
        checklistRooms.persistAll();
        batteryRooms.persistAll();
    });
});

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

function setMode(mode) {
    state.mode = mode;
    el.tabFill.setAttribute("aria-selected", String(mode === "fill"));
    el.tabEdit.setAttribute("aria-selected", String(mode === "edit"));
    el.tabFill.classList.toggle("ghost", mode !== "fill");
    el.tabEdit.classList.toggle("ghost", mode !== "edit");
    el.fill.classList.toggle("hidden", mode !== "fill");
    el.edit.classList.toggle("hidden", mode !== "edit");
    el.matchControls.classList.toggle("hidden", mode !== "fill");
    if (mode === "fill") {
        refreshMatchOptionMarks();
        syncFillRooms();
    }
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

// Ordering and alliance helpers are shared with the Battery Tracker (js/services/match-utils.js).
function compareMatches(a, b) {
    return window.ObsidianMatchUtils.compareMatches(a, b);
}

function isOurMatch(match) {
    return window.ObsidianMatchUtils.isTeamInMatch(match, state.me.teamNumber);
}

function matchLabel(match) {
    return window.ObsidianMatchUtils.matchLabel(match);
}

function allianceOf(match) {
    return window.ObsidianMatchUtils.allianceOf(match, state.me.teamNumber);
}

function allianceName(color) {
    if (color === "Red") return t("checklist.alliance.red", "Red");
    if (color === "Blue") return t("checklist.alliance.blue", "Blue");
    return color;
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
    syncBatteryRooms();
}

function refreshMatchOptionMarks() {
    if (!el.match) return;
    const schedule = batterySchedule();
    [...el.match.options].forEach((opt) => {
        if (!opt.value || opt.value === "__custom__") return;
        const batteryId = schedule[opt.value]?.batteryId;
        labelOption(opt, isMatchComplete(opt.value, state.eventCompletions[opt.value]), batteryId ? fmt(t("battery.checklist.needs", "Battery: {name}"), { name: batteryName(batteryId) }) : "");
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

    const customEntries = Object.keys(state.eventCompletions)
        .filter((k) => k.startsWith("custom_") && !visible.some((m) => m.matchKey === k))
        .map((k) => ({
            matchKey: k,
            label: k.slice("custom_".length).replace(/_/g, " ")
        }));

    const allOptions = [
        ...visible.map((m) => ({ match: m, isCustom: false })),
        ...customEntries.map((c) => ({ match: c, isCustom: true }))
    ].sort((a, b) => compareMatches(a.match, b.match));

    allOptions.forEach(({ match: m, isCustom }) => {
        const opt = document.createElement("option");
        opt.value = m.matchKey;
        if (isCustom) {
            opt.textContent = m.label;
        } else {
            const alliance = allianceOf(m);
            opt.textContent = alliance ? `${matchLabel(m)} (${allianceName(alliance)})` : matchLabel(m);
        }
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
        onMatchSelected(target);
    } else {
        // Default to our next match without a finished checklist.
        const next = visible.find((m) => !isMatchComplete(m.matchKey, state.eventCompletions[m.matchKey]));
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
        if (!state.eventCompletions[matchKey]) {
            state.eventCompletions[matchKey] = { checked: {}, notes: "" };
        }
        state.matchKey = matchKey;
        renderMatchOptions();
        return;
    }

    state.matchKey = matchKey || "";
    await syncFillRooms();
}

// ------------------------------------------------------------------ fill out

function renderFill() {
    const match = state.matches.find((m) => m.matchKey === state.matchKey);
    const alliance = allianceOf(match);
    const allianceStr = alliance ? ` · ${t("checklist.alliance_label", "{alliance} alliance").replace("{alliance}", allianceName(alliance))}` : "";
    el.matchTitle.textContent = !state.matchKey ? t("checklist.select_match", "Select a match")
        : match ? `${matchLabel(match)}${allianceStr}`
        : state.matchKey.replace(/^custom_/, "").replace(/_/g, " ");

    renderBattery();

    const matchRoom = state.fillRooms.match;
    el.notes.disabled = !matchRoom;
    const notes = completionOf(matchRoom?.session.lastView).notes;
    // Don't replace what someone is typing; their next keystroke carries the whole note anyway.
    if (document.activeElement !== el.notes) el.notes.value = notes;

    const template = currentTemplate();
    const lists = template.lists.filter((l) => fillableItems(l).length);
    renderStatus(lists);
    if (!lists.length) {
        el.fillSections.innerHTML = `<div class="card"><p class="checklist-empty" style="margin:0;">${t("checklist.fill.empty_template", "No checklist items yet. Use <strong>Edit Checklists</strong> to create your lists.")}</p></div>`;
        return;
    }
    el.fillSections.innerHTML = lists.map(renderFillList).join("");
}

function renderFillList(list) {
    const scope = scopeOf(list);
    const room = state.fillRooms[scope];
    const header = (extra) => `<h2><span>${esc(listTitle(list))}</span>${extra || ""}</h2>
        <div class="checklist-frequency">${esc(frequencyLabel(list))}${scopeNote(list)}</div>`;

    if (!state.eventKey) {
        return `<div class="card checklist-section">${header()}<p class="checklist-empty">${esc(t("checklist.fill.needs_event", "Pick an event to fill this list."))}</p></div>`;
    }
    if (scope === "match" && !state.matchKey) {
        return `<div class="card checklist-section">${header()}<p class="checklist-empty">${esc(t("checklist.fill.needs_match", "Pick a match to fill this list."))}</p></div>`;
    }
    if (scope === "match" && !isDueForMatch(list, state.matchKey)) {
        const next = nextDueMatch(list, state.matchKey);
        const text = next
            ? fmt(t("checklist.fill.not_due", "Not due this match. Next due: {match}"), { match: matchLabel(next) })
            : t("checklist.fill.not_due_again", "Not due again at this event.");
        return `<div class="card checklist-section checklist-not-due">${header()}<p class="checklist-empty">${esc(text)}</p></div>`;
    }

    const completion = completionOf(room?.session.lastView);
    // Sort by priority; within a priority keep the order set in Edit Checklists.
    const items = fillableItems(list).slice()
        .sort((a, b) => (priorityRank(a.priority) - priorityRank(b.priority)) || (a.index - b.index));
    const done = items.filter((it) => completion.checked[it.id]).length;
    const total = items.length;
    const pct = total ? Math.round((done / total) * 100) : 0;
    const rows = items.map((it) => {
        const check = completion.checked[it.id];
        const meta = check ? `<span class="checklist-item-meta" title="${esc(new Date(check.at).toLocaleString())}">${esc(check.by)}</span>` : "";
        return `<li class="checklist-item${check ? " checked" : ""}" data-collab-key="${esc(it.key)}">
            <label>
                <input type="checkbox" data-item="${esc(it.id)}" data-scope="${scope}" ${check ? "checked" : ""} ${room ? "" : "disabled"} />
                <span class="checklist-item-text">${esc(it.text.trim())}</span>
                <span class="checklist-priority priority-${esc(it.priority)}">${esc(priorityLabel(it.priority))}</span>
                ${meta}
            </label>
        </li>`;
    }).join("");
    const resetBtn = `<button type="button" class="btn ghost btn-sm" data-reset-list="${esc(list.id)}" data-scope="${scope}" ${room && done ? "" : "disabled"}>${esc(t("checklist.reset_list", "Reset"))}</button>`;
    return `<div class="card checklist-section">
        ${header(`<span class="checklist-section-actions"><span class="checklist-status">${done}/${total}</span>${resetBtn}</span>`)}
        <div class="checklist-progress${total && done === total ? " done" : ""}"><span style="width:${pct}%"></span></div>
        <ul class="checklist-items">${rows}</ul>
    </div>`;
}

/** Explains which copy of a daily / per-event list is shown. */
function scopeNote(list) {
    if (list.frequency === "daily") {
        return ` · ${esc(fmt(t("checklist.fill.today", "Today ({date})"), { date: new Date().toLocaleDateString() }))}`;
    }
    if (list.frequency === "event") return ` · ${esc(t("checklist.fill.whole_event", "Shared for the whole event"))}`;
    return "";
}

function renderStatus(lists) {
    if (!state.eventKey) {
        el.status.textContent = t("checklist.match.select_event_first", "Select an event first");
        return;
    }
    const shown = lists.filter((l) => {
        const scope = scopeOf(l);
        if (scope !== "match") return true;
        return state.matchKey && isDueForMatch(l, state.matchKey);
    });
    if (!state.matchKey && !shown.length) {
        el.status.textContent = t("checklist.status.pick_match", "Pick a match to start checking items off.");
        return;
    }
    let done = 0, total = 0, criticalLeft = 0;
    shown.forEach((l) => {
        const checked = completionOf(state.fillRooms[scopeOf(l)]?.session.lastView).checked;
        fillableItems(l).forEach((it) => {
            total++;
            if (checked[it.id]) done++;
            else if (it.priority === "critical") criticalLeft++;
        });
    });
    const parts = [total && done === total
        ? t("checklist.status.all_complete", "All checks complete ✓")
        : t("checklist.status.done_count", "{done} of {total} done").replace("{done}", done).replace("{total}", total)];
    if (criticalLeft) parts.push(t("checklist.status.critical_left", "{count} critical left").replace("{count}", criticalLeft));
    if (!state.matchKey) parts.push(t("checklist.status.pick_match", "Pick a match to start checking items off."));
    el.status.textContent = parts.join(" · ");
}

function wireFill() {
    el.fillSections.addEventListener("change", (e) => {
        const box = e.target.closest("input[type=checkbox][data-item]");
        const room = box && state.fillRooms[box.dataset.scope];
        if (!room) return;
        const id = box.dataset.item;
        const checked = box.checked;
        const mark = { by: state.me.username, at: Date.now() };
        Obsidianscout.triggerHaptic?.("light");
        edit(room, (doc) => {
            doc.checked = isPlainObject(doc.checked) ? doc.checked : {};
            if (checked) doc.checked[id] = doc.checked[id] || mark;
            else delete doc.checked[id];
        });
    });

    el.fillSections.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-reset-list]");
        const room = btn && state.fillRooms[btn.dataset.scope];
        if (!room) return;
        const list = currentTemplate().lists.find((l) => l.id === btn.dataset.resetList);
        if (!list) return;
        if (!confirm(fmt(t("checklist.reset_list_confirm", "Uncheck every item in \"{name}\"?"), { name: listTitle(list) }))) return;
        const ids = new Set(list.items.map((it) => it.id));
        edit(room, (doc) => {
            doc.checked = isPlainObject(doc.checked) ? doc.checked : {};
            ids.forEach((id) => delete doc.checked[id]);
        });
    });

    el.notes.addEventListener("input", () => {
        const room = state.fillRooms.match;
        if (!room) return;
        const notes = el.notes.value;
        edit(room, (doc) => { doc.notes = notes; }, false);
    });
}

// ------------------------------------------------------------------ edit template

function renderEdit() {
    const template = currentTemplate();
    const lists = template.lists;
    if (!lists.length) {
        el.editSections.innerHTML = `<div class="card"><p class="checklist-empty" style="margin:0;">${esc(t("checklist.edit.no_lists", "No lists yet. Use \"Add list\" to create one."))}</p></div>`;
        decorateEditors();
        return;
    }
    el.editSections.innerHTML = lists.map((list, listIdx) => {
        const rows = list.items.map((it, idx) => `
            <li class="checklist-edit-row" data-collab-key="${esc(it.key)}" data-index="${it.index}">
                <input type="text" value="${esc(it.text)}" maxlength="200" aria-label="${esc(t("checklist.edit.item_aria", "Checklist item"))}" data-field="text" />
                <select data-field="priority" aria-label="${esc(t("checklist.edit.priority_aria", "Priority"))}" class="checklist-priority-select">${priorityOptions(it.priority)}</select>
                <button type="button" class="btn ghost" data-action="up" data-list="${esc(list.id)}" title="${esc(t("checklist.edit.move_up", "Move up"))}" aria-label="${esc(t("checklist.edit.move_up", "Move up"))}" ${idx === 0 ? "disabled" : ""}>&uarr;</button>
                <button type="button" class="btn ghost" data-action="down" data-list="${esc(list.id)}" title="${esc(t("checklist.edit.move_down", "Move down"))}" aria-label="${esc(t("checklist.edit.move_down", "Move down"))}" ${idx === list.items.length - 1 ? "disabled" : ""}>&darr;</button>
                <button type="button" class="btn ghost" data-action="remove" title="${esc(t("checklist.edit.remove", "Remove"))}" aria-label="${esc(t("checklist.edit.remove", "Remove"))}">&times;</button>
            </li>`).join("");
        const titleValue = list.title.trim() ? list.title : (LEGACY_LISTS.includes(list.id) ? listTitle(list) : "");
        return `<div class="card checklist-section" data-list="${esc(list.id)}">
            <div class="checklist-list-settings" data-collab-key="list-${esc(list.id)}">
                <div class="field">
                    <label>${esc(t("checklist.edit.list_name", "List name"))}</label>
                    <input type="text" value="${esc(titleValue)}" maxlength="60" data-field="list-title" data-list="${esc(list.id)}" placeholder="${esc(t("checklist.list.untitled", "Untitled list"))}" />
                </div>
                <div class="field">
                    <label>${esc(t("checklist.edit.frequency", "How often"))}</label>
                    <select data-field="frequency" data-list="${esc(list.id)}">${frequencyOptions(list.frequency)}</select>
                </div>
                <div class="field ${list.frequency === "every_n" ? "" : "hidden"}">
                    <label>${esc(t("checklist.edit.every_label", "Every how many of our matches"))}</label>
                    <input type="number" min="2" max="99" step="1" value="${list.every}" data-field="every" data-list="${esc(list.id)}" />
                </div>
                <div class="checklist-list-actions">
                    <button type="button" class="btn ghost btn-sm" data-list-action="up" data-list="${esc(list.id)}" title="${esc(t("checklist.edit.move_list_up", "Move list up"))}" aria-label="${esc(t("checklist.edit.move_list_up", "Move list up"))}" ${listIdx === 0 ? "disabled" : ""}>&uarr;</button>
                    <button type="button" class="btn ghost btn-sm" data-list-action="down" data-list="${esc(list.id)}" title="${esc(t("checklist.edit.move_list_down", "Move list down"))}" aria-label="${esc(t("checklist.edit.move_list_down", "Move list down"))}" ${listIdx === lists.length - 1 ? "disabled" : ""}>&darr;</button>
                    <button type="button" class="btn ghost btn-sm checklist-danger" data-list-action="delete" data-list="${esc(list.id)}">${esc(t("checklist.edit.delete_list", "Delete list"))}</button>
                </div>
            </div>
            <ul class="checklist-items">${rows || `<li class="checklist-empty">${esc(t("checklist.edit.no_items", "No items yet."))}</li>`}</ul>
            <form class="checklist-edit-row mt-12" data-add="${esc(list.id)}" data-collab-key="add-${esc(list.id)}" data-no-loading="true" autocomplete="off">
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
    if (state.tpl && state.mode === "edit") collab().decorateCards(el.editSections, state.tpl.session.editors, state.tpl.session.sid);
}

/** Renumbers list order 0..n-1 (in current display order) so lists can be swapped. */
function normalizedLists(doc) {
    const lists = ensureLists(doc);
    listsOf(doc).forEach((l, i) => { lists[l.id].order = i; });
    return lists;
}

function wireEdit() {
    el.editSections.addEventListener("input", (e) => {
        const input = e.target;
        const field = input.dataset.field;
        if (field === "list-title") {
            const id = input.dataset.list;
            const value = input.value;
            edit(state.tpl, (doc) => { ensureLists(doc)[id].title = value; }, false);
        } else if (field === "text") {
            const index = Number(input.closest("[data-index]").dataset.index);
            const value = input.value;
            edit(state.tpl, (doc) => { doc.fields[index].text = value; }, false);
        }
    });

    el.editSections.addEventListener("change", (e) => {
        const input = e.target;
        const field = input.dataset.field;
        if (field === "priority") {
            const index = Number(input.closest("[data-index]").dataset.index);
            edit(state.tpl, (doc) => { doc.fields[index].priority = input.value; });
        } else if (field === "frequency") {
            const id = input.dataset.list;
            edit(state.tpl, (doc) => {
                const list = ensureLists(doc)[id];
                list.frequency = input.value;
                if (input.value === "every_n" && !(parseInt(list.every, 10) >= 2)) list.every = 2;
            });
        } else if (field === "every") {
            const id = input.dataset.list;
            const every = Math.max(2, Math.min(99, parseInt(input.value, 10) || 2));
            edit(state.tpl, (doc) => { ensureLists(doc)[id].every = every; });
        }
    });

    // Tell teammates which item this editor is in.
    el.editSections.addEventListener("focusin", (e) => {
        const key = e.target.closest("[data-collab-key]")?.getAttribute("data-collab-key");
        state.tpl?.session.setFocus(key && !key.startsWith("add-") && !key.startsWith("list-") ? key : null);
    });
    el.editSections.addEventListener("focusout", () => state.tpl?.session.setFocus(null));

    el.editSections.addEventListener("click", (e) => {
        const listBtn = e.target.closest("button[data-list-action]");
        if (listBtn) {
            onListAction(listBtn.dataset.listAction, listBtn.dataset.list);
            return;
        }
        const btn = e.target.closest("button[data-action]");
        if (!btn) return;
        const index = Number(btn.closest("[data-index]").dataset.index);
        const action = btn.dataset.action;
        edit(state.tpl, (doc) => {
            if (action === "remove") {
                doc.fields.splice(index, 1);
                return;
            }
            // Swap with the neighbouring item of the same list (all lists share one ordered array).
            const listId = btn.dataset.list;
            const sameList = doc.fields.map((f, i) => ((f.section || "pre") === listId ? i : -1)).filter((i) => i >= 0);
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
        const listId = form.dataset.add;
        input.value = "";
        edit(state.tpl, (doc) => {
            doc.fields = Array.isArray(doc.fields) ? doc.fields : [];
            doc.fields.push({ id: newId(), section: listId, text, priority });
        });
        el.editSections.querySelector(`form[data-add="${CSS.escape(listId)}"] input`)?.focus();
    });

    el.addList.addEventListener("click", () => {
        const id = newId();
        edit(state.tpl, (doc) => {
            const lists = normalizedLists(doc);
            lists[id] = { title: "", order: Object.keys(lists).length, frequency: "match", every: 2 };
        });
        el.editSections.querySelector(`input[data-field="list-title"][data-list="${CSS.escape(id)}"]`)?.focus();
    });

    el.starter.addEventListener("click", () => {
        const template = currentTemplate();
        const hasItems = template.lists.some((l) => l.items.length);
        if (hasItems && !confirm(t("checklist.edit.starter_confirm", "Add the starter items to your lists? Existing items are kept."))) return;
        edit(state.tpl, (doc) => {
            const lists = normalizedLists(doc);
            doc.fields = Array.isArray(doc.fields) ? doc.fields : [];
            Object.entries(STARTER_ITEMS).forEach(([listId, items]) => {
                if (!lists[listId]) lists[listId] = { title: "", order: Object.keys(lists).length, frequency: "match", every: 2 };
                const existing = new Set(doc.fields.filter((f) => f.section === listId).map((f) => String(f.text || "").trim().toLowerCase()));
                items.forEach(({ text, priority }) => {
                    const translated = t(`checklist.starter.${starterKey(text)}`, text);
                    if (!existing.has(translated.toLowerCase())) doc.fields.push({ id: newId(), section: listId, text: translated, priority });
                });
            });
        });
    });
}

/** Stable translation key for a starter item. */
function starterKey(text) {
    return {
        "Fresh battery installed and strapped down": "battery",
        "Battery voltage checked (12.5V+)": "voltage",
        "Bumpers on and showing the correct alliance color": "bumpers",
        "Radio powered and secured": "radio",
        "All bolts, chains and belts checked": "mechanical",
        "Controllers and driver station laptop charged": "controllers_charged",
        "Robot placed in the correct starting position": "start_position",
        "Correct autonomous routine selected": "auto_selected",
        "Driver station connected to the field (all green)": "ds_connected",
        "Game piece preloaded": "preload",
        "Controllers plugged in and mapped to the right ports": "controllers_mapped"
    }[text] || "item";
}

function onListAction(action, id) {
    if (action === "delete") {
        const list = currentTemplate().lists.find((l) => l.id === id);
        if (!list) return;
        const message = fmt(t("checklist.edit.delete_list_confirm", "Delete \"{name}\" and all of its items?"), { name: listTitle(list) });
        if (!confirm(message)) return;
        edit(state.tpl, (doc) => {
            delete ensureLists(doc)[id];
            doc.fields = (Array.isArray(doc.fields) ? doc.fields : []).filter((f) => (f.section || "pre") !== id);
        });
        return;
    }
    edit(state.tpl, (doc) => {
        const lists = normalizedLists(doc);
        const ordered = listsOf(doc);
        const pos = ordered.findIndex((l) => l.id === id);
        const other = ordered[action === "up" ? pos - 1 : pos + 1];
        if (pos < 0 || !other) return;
        const tmp = lists[id].order;
        lists[id].order = lists[other.id].order;
        lists[other.id].order = tmp;
    });
}
