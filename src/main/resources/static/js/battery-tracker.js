/**
 * Battery Tracker page - ObsidianScout
 * The drive team's batteries and which one runs in each of our matches. Shown only when "Non-Scouting
 * Tools" is enabled in Personal Settings, next to the Match Checklist (which shows the same battery per match).
 *
 * Two live documents, edited the same way as the checklists (js/services/collab-rooms.js):
 *   inventory  { batteries: { [id]: { name, type: "match"|"pit", order, retired, notes, createdAt } } }
 *   event plan { matches: { [matchKey]: { skipped, pinned, used } }, startAt, autoMarkUsed }
 * The schedule itself is computed by js/services/battery-rotation.js, so skips and new batteries show up at once.
 * The app (lib/screens/battery_tracker_screen.dart) reads and writes the same documents.
 */

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
    matches: [],         // our matches at the event, in play order
    inv: null,           // inventory room
    invReady: null,
    plan: null,          // this event's plan room
    invBar: null,
    planBar: null,
    scrolledFor: "",     // event whose next match was already scrolled into view
    hadPendingOffline: false
};

const el = {};
let rooms = null;

function battery() {
    return window.ObsidianBattery;
}

function matchUtils() {
    return window.ObsidianMatchUtils;
}

function collab() {
    return window.ObsidianscoutConfigCollab;
}

function esc(value) {
    return Obsidianscout.escapeHtml(String(value ?? ""));
}

function newId() {
    return Math.random().toString(36).slice(2, 10);
}

function isPlainObject(v) {
    return v !== null && typeof v === "object" && !Array.isArray(v);
}

// ------------------------------------------------------------------ documents

function inventoryDoc() {
    return state.inv?.session.lastView.doc || {};
}

function planDoc() {
    return state.plan && state.plan.eventKey === state.eventKey ? state.plan.session.lastView.doc || {} : {};
}

function batteries() {
    return battery().batteriesOf(inventoryDoc());
}

function nameOf(id) {
    return battery().displayName(batteries().find((b) => b.id === id)) || id;
}

function schedule() {
    return battery().computeSchedule(inventoryDoc(), planDoc(), state.matches.map((m) => m.matchKey));
}

function editInventory(mutate, rerender = true) {
    if (!state.inv) return;
    rooms.edit(state.inv, (doc) => {
        if (!isPlainObject(doc.batteries)) doc.batteries = {};
        mutate(doc);
    });
    updateOfflineBanner();
    if (rerender) renderAll();
}

function editPlan(mutate) {
    if (!state.plan) return;
    rooms.edit(state.plan, mutate);
    updateOfflineBanner();
    renderAll();
}

/** Renumbers `order` to the current display order so neighbours can be swapped. */
function normalizeOrder(doc) {
    battery().batteriesOf(doc).forEach((b, i) => { doc.batteries[b.id].order = i; });
}

// ------------------------------------------------------------------ live rooms

function setupRooms() {
    rooms = window.ObsidianscoutCollabRooms.create(Object.assign(battery().roomConfig(state.me), {
        keepOpen: (room) => room === state.inv || room === state.plan,
        onChange: () => {
            renderAll();
            updateOfflineBanner();
        },
        onStatus: (room, status) => {
            if (room === state.inv) state.invBar?.update(status);
            if (room === state.plan) state.planBar?.update(status);
            updateOfflineBanner();
        },
        onPresence: (room, editors) => {
            if (room === state.inv) {
                state.invBar?.setEditors(editors, room.session.sid);
                decorateEditors();
            }
            if (room === state.plan) state.planBar?.setEditors(editors, room.session.sid);
        }
    }));
}

async function openInventory() {
    if (!state.invReady) state.invReady = rooms.acquire("", "").then((room) => { state.inv = room; });
    await state.invReady;
    state.invBar.update(state.inv.session.status);
    state.invBar.setEditors(state.inv.session.editors, state.inv.session.sid);
}

async function openPlan(eventKey) {
    const current = state.plan;
    if (current?.eventKey === eventKey) return;
    state.plan = null;
    rooms.release(current);
    el.planBarHost.classList.toggle("hidden", !eventKey);
    if (!eventKey) return;
    const room = await rooms.acquire(eventKey, "");
    if (state.eventKey !== eventKey || state.plan) {
        rooms.release(room);
        return;
    }
    state.plan = room;
    state.planBar.update(room.session.status);
    state.planBar.setEditors(room.session.editors, room.session.sid);
}

function tick() {
    rooms.tick();
    updateOfflineBanner();
}

function updateOfflineBanner() {
    if (!rooms) return;
    const pending = rooms.pendingOffline().length;
    if (pending) {
        el.offlineBannerText.textContent = t("battery.offline.banner",
            "You're offline. Battery changes can't be saved until you're back online. They're kept on this device and will save automatically when you reconnect.");
        state.hadPendingOffline = true;
    } else if (state.hadPendingOffline && !rooms.hasUnsaved()) {
        state.hadPendingOffline = false;
        Obsidianscout.showToast(t("checklist.offline.saved", "All offline changes saved"), "success");
    }
    el.offlineBanner.classList.toggle("hidden", !pending);
}

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

/** Shows which battery each teammate is editing. */
function decorateEditors() {
    if (state.inv) collab().decorateCards(el.inventory, state.inv.session.editors, state.inv.session.sid);
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

    ["event", "event-controls", "disabled", "layout", "inventory", "add", "add-name", "add-type", "settings", "rotation",
        "inv-bar-host", "plan-bar-host", "offline-banner", "offline-banner-text"].forEach((id) => {
        el[id.replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = document.getElementById(`battery-${id}`);
    });

    if (!me.nonScoutingToolsEnabled) {
        el.disabled.classList.remove("hidden");
        el.eventControls.classList.add("hidden");
        el.layout.classList.add("hidden");
        return;
    }
    if (!collab() || !window.ObsidianscoutCollabRooms || !battery() || !matchUtils()) {
        Obsidianscout.showToast(t("checklist.toast.live_failed", "Live editing failed to load; refresh the page"), "error");
        return;
    }

    setupRooms();
    state.invBar = makeStatusBar(el.invBarHost);
    state.planBar = makeStatusBar(el.planBarHost);
    el.planBarHost.classList.add("hidden");

    wireInventory();
    wireRotation();

    await openInventory();
    renderAll();
    await initEventSelect();
    rooms.resumeDrafts();
    setInterval(tick, TICK_MS);
    window.addEventListener("online", tick);

    window.addEventListener("obsidianscout:languagechange", () => {
        renderAll();
        if (window.Obsidianscout && typeof Obsidianscout.applyTranslations === "function") {
            Obsidianscout.applyTranslations();
        }
    });

    window.addEventListener("beforeunload", () => rooms.persistAll());
});

function renderAll() {
    if (!el.inventory) return;
    collab().preserveFocus(el.inventory, renderInventory);
    decorateEditors();
    collab().preserveFocus(el.settings, renderSettings);
    renderRotation();
}

// ------------------------------------------------------------------ event picker

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

    await loadEvent(state.eventKey);
}

async function loadEvent(eventKey) {
    state.eventKey = eventKey || "";
    state.matches = [];
    renderAll();
    if (state.eventKey) {
        const matches = await Obsidianscout.request(`/api/matches?eventKey=${encodeURIComponent(state.eventKey)}`).catch(() => []);
        if (state.eventKey !== (eventKey || "")) return;
        state.matches = (Array.isArray(matches) ? matches : [])
            .filter((m) => matchUtils().isTeamInMatch(m, state.me.teamNumber))
            .sort(matchUtils().compareMatches);
    }
    await openPlan(state.eventKey);
    renderAll();
}

// ------------------------------------------------------------------ inventory

function typeOptions(selected) {
    return [["match", t("battery.type.match", "Match")], ["pit", t("battery.type.pit", "Pit")]]
        .map(([v, label]) => `<option value="${v}" ${v === selected ? "selected" : ""}>${esc(label)}</option>`).join("");
}

function usageText(b, usage) {
    const used = usage[b.id] || [];
    if (!used.length) return "";
    const last = state.matches.find((m) => m.matchKey === used[used.length - 1]);
    return fmt(t("battery.stats.used", "Ran {count}× at this event · last {match}"),
        { count: used.length, match: last ? matchUtils().matchLabel(last) : used[used.length - 1] });
}

function renderBatteryRow(b, group, index, count, usage) {
    const moveButtons = group === "retired" ? "" : `<div class="battery-move">
            <button type="button" class="btn ghost" data-action="up" ${index === 0 ? "disabled" : ""} title="${esc(t("battery.action.move_up", "Move up"))}" aria-label="${esc(t("battery.action.move_up", "Move up"))}">&#9650;</button>
            <button type="button" class="btn ghost" data-action="down" ${index === count - 1 ? "disabled" : ""} title="${esc(t("battery.action.move_down", "Move down"))}" aria-label="${esc(t("battery.action.move_down", "Move down"))}">&#9660;</button>
        </div>`;
    const stats = usageText(b, usage);
    return `<li class="battery-row${b.retired ? " retired" : ""}" data-collab-key="${esc(b.id)}" data-id="${esc(b.id)}">
        ${moveButtons || "<span></span>"}
        <div class="battery-row-main">
            <input type="text" data-field="name" maxlength="40" value="${esc(b.name)}" placeholder="${esc(t("battery.name_placeholder", "Name"))}" aria-label="${esc(t("battery.name_placeholder", "Name"))}" />
            <select data-field="type" aria-label="${esc(t("battery.type.label", "Type"))}">${typeOptions(b.type)}</select>
            <input type="text" class="battery-notes" data-field="notes" maxlength="200" value="${esc(b.notes)}" placeholder="${esc(t("battery.notes_placeholder", "Notes (age, condition, voltage…)"))}" aria-label="${esc(t("battery.notes_label", "Notes"))}" />
        </div>
        <div class="battery-row-actions">
            ${b.retired
                ? `<button type="button" class="btn ghost" data-action="restore">${esc(t("battery.action.restore", "Restore"))}</button>
                   <button type="button" class="btn ghost battery-danger" data-action="delete">${esc(t("battery.action.delete", "Delete"))}</button>`
                : `<button type="button" class="btn ghost" data-action="retire" title="${esc(t("battery.action.retire_hint", "Take out of use but keep its history"))}">${esc(t("battery.action.retire", "Retire"))}</button>`}
        </div>
        ${stats ? `<div class="battery-stats">${esc(stats)}</div>` : ""}
    </li>`;
}

function renderInventory() {
    const all = batteries();
    if (!state.inv) {
        el.inventory.innerHTML = `<p class="battery-empty">${esc(t("battery.loading", "Loading…"))}</p>`;
        return;
    }
    if (!all.length) {
        el.inventory.innerHTML = `<p class="battery-empty">${esc(t("battery.inventory.empty", "No batteries yet. Add them below, or start with six numbered batteries."))}</p>
            <button type="button" class="btn ghost" data-action="quick-add">${esc(t("battery.inventory.quick_add", "Add B1–B6"))}</button>`;
        return;
    }
    const usage = battery().usageByBattery(planDoc(), state.matches.map((m) => m.matchKey));
    const groups = [
        ["match", t("battery.group.match", "Match batteries (in rotation)"), all.filter((b) => !b.retired && b.type === "match")],
        ["pit", t("battery.group.pit", "Pit batteries (never in a match)"), all.filter((b) => !b.retired && b.type === "pit")],
        ["retired", t("battery.group.retired", "Retired"), all.filter((b) => b.retired)]
    ];
    el.inventory.innerHTML = groups
        .filter(([id, , list]) => list.length || id !== "retired")
        .map(([id, title, list]) => `<div class="battery-group-title">${esc(title)} · ${list.length}</div>
            ${list.length
                ? `<ul class="battery-list" data-group="${id}">${list.map((b, i) => renderBatteryRow(b, id, i, list.length, usage)).join("")}</ul>`
                : `<p class="battery-empty">${esc(id === "match"
                    ? t("battery.group.match_empty", "No match batteries. Set a battery's type to Match to put it in the rotation.")
                    : t("battery.group.pit_empty", "No pit batteries."))}</p>`}`)
        .join("");
}

function addBatteries(entries) {
    editInventory((doc) => {
        normalizeOrder(doc);
        let order = Object.keys(doc.batteries).length;
        entries.forEach(({ name, type }) => {
            doc.batteries[newId()] = { name, type, order: order++, retired: false, notes: "", createdAt: Date.now() };
        });
    });
}

function wireInventory() {
    el.add.addEventListener("submit", (e) => {
        e.preventDefault();
        const name = el.addName.value.trim();
        if (!name) {
            el.addName.focus();
            return;
        }
        addBatteries([{ name, type: el.addType.value === "pit" ? "pit" : "match" }]);
        el.addName.value = "";
        el.addName.focus();
    });

    el.inventory.addEventListener("input", (e) => {
        const field = e.target.dataset.field;
        const id = e.target.closest("[data-id]")?.dataset.id;
        if (!id || (field !== "name" && field !== "notes")) return;
        const value = e.target.value;
        editInventory((doc) => {
            if (doc.batteries[id]) doc.batteries[id][field] = value;
        }, false);
        renderRotation();
    });

    el.inventory.addEventListener("change", (e) => {
        if (e.target.dataset.field !== "type") return;
        const id = e.target.closest("[data-id]")?.dataset.id;
        const type = e.target.value === "pit" ? "pit" : "match";
        editInventory((doc) => {
            if (doc.batteries[id]) doc.batteries[id].type = type;
        });
    });

    el.inventory.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-action]");
        if (!btn) return;
        const action = btn.dataset.action;
        if (action === "quick-add") {
            addBatteries([1, 2, 3, 4, 5, 6].map((n) => ({ name: `B${n}`, type: "match" })));
            return;
        }
        const row = btn.closest("[data-id]");
        const id = row?.dataset.id;
        if (!id) return;
        if (action === "delete" && !confirm(fmt(t("battery.delete_confirm", "Delete battery \"{name}\"? Matches it ran in will lose that record. Retire it instead to keep its history."), { name: nameOf(id) }))) return;
        editInventory((doc) => {
            const b = doc.batteries[id];
            if (!b) return;
            switch (action) {
                case "retire": b.retired = true; break;
                case "restore": b.retired = false; break;
                case "delete": delete doc.batteries[id]; break;
                case "up":
                case "down": {
                    normalizeOrder(doc);
                    // Swap with the neighbouring battery in the same group.
                    const groupIds = [...row.closest("ul").querySelectorAll("[data-id]")].map((n) => n.dataset.id);
                    const i = groupIds.indexOf(id);
                    const other = groupIds[action === "up" ? i - 1 : i + 1];
                    if (!other || !doc.batteries[other]) return;
                    const o = doc.batteries[other].order;
                    doc.batteries[other].order = b.order;
                    b.order = o;
                    break;
                }
            }
        });
    });

    // Tell teammates which battery this editor is in.
    el.inventory.addEventListener("focusin", (e) => {
        state.inv?.session.setFocus(e.target.closest("[data-collab-key]")?.getAttribute("data-collab-key") || null);
    });
    el.inventory.addEventListener("focusout", () => state.inv?.session.setFocus(null));
}

// ------------------------------------------------------------------ rotation

function renderSettings() {
    if (!state.eventKey || !state.plan) {
        el.settings.innerHTML = "";
        return;
    }
    const plan = planDoc();
    const rotation = battery().rotationOf(inventoryDoc());
    el.settings.innerHTML = `
        <div class="field">
            <label for="battery-start-at">${esc(t("battery.settings.start_at", "First battery at this event"))}</label>
            <select id="battery-start-at" data-setting="startAt">
                <option value="">${esc(t("battery.settings.start_first", "First in the list"))}</option>
                ${rotation.map((b) => `<option value="${esc(b.id)}" ${plan.startAt === b.id ? "selected" : ""}>${esc(battery().displayName(b))}</option>`).join("")}
            </select>
        </div>
        <div class="field">
            <label class="battery-check">
                <input type="checkbox" data-setting="autoMarkUsed" ${battery().autoMarkUsed(plan) ? "checked" : ""} />
                <span>${esc(t("battery.settings.auto_mark", "Mark a match's battery as used when its checklist is finished"))}</span>
            </label>
        </div>`;
}

function renderRotation() {
    if (!state.eventKey) {
        el.rotation.innerHTML = `<p class="battery-empty">${esc(t("battery.rotation.needs_event", "Pick an event to see which battery runs in each match."))}</p>`;
        return;
    }
    if (!state.plan) {
        el.rotation.innerHTML = `<p class="battery-empty">${esc(t("battery.loading", "Loading…"))}</p>`;
        return;
    }
    if (!state.matches.length) {
        el.rotation.innerHTML = `<p class="battery-empty">${esc(t("battery.rotation.no_matches", "No matches for our team at this event yet. They show up here once the schedule is out."))}</p>`;
        return;
    }
    const rotation = battery().rotationOf(inventoryDoc());
    if (!rotation.length) {
        el.rotation.innerHTML = `<p class="battery-empty">${esc(t("battery.rotation.no_batteries", "Add a match battery to build the rotation."))}</p>`;
        return;
    }
    const slots = schedule();
    const usable = batteries().filter((b) => !b.retired);
    const nextKey = state.matches.find((m) => slots[m.matchKey].source !== "used")?.matchKey;

    el.rotation.innerHTML = `<ul class="battery-matches">${state.matches.map((m) => {
        const slot = slots[m.matchKey];
        const alliance = matchUtils().allianceOf(m, state.me.teamNumber);
        const tag = slot.source === "used" ? t("battery.source.used", "Used")
            : slot.source === "pinned" ? t("battery.source.pinned", "Pinned") : "";
        const chip = slot.batteryId
            ? `<span class="battery-chip">${battery().BATTERY_ICON} ${esc(nameOf(slot.batteryId))}</span>${tag ? ` <span class="battery-tag">${esc(tag)}</span>` : ""}`
            : `<span class="battery-chip none">${esc(t("battery.rotation.none", "No battery: all skipped"))}</span>`;
        const skipped = slot.skipped.length
            ? `<div class="battery-skipped">${esc(fmt(t("battery.checklist.skipped", "Skipped: {names}"), { names: slot.skipped.map(nameOf).join(", ") }))}</div>`
            : "";
        const entry = isPlainObject(planDoc().matches?.[m.matchKey]) ? planDoc().matches[m.matchKey] : {};
        const pinSelect = slot.source === "used" ? "" : `<select data-pin aria-label="${esc(t("battery.action.pin", "Pin a battery"))}" title="${esc(t("battery.action.pin", "Pin a battery"))}">
                <option value="">${esc(t("battery.pin.auto", "Auto"))}</option>
                ${usable.map((b) => `<option value="${esc(b.id)}" ${entry.pinned === b.id ? "selected" : ""}>${esc(fmt(t("battery.pin.option", "Pin {name}"), { name: battery().displayName(b) }))}</option>`).join("")}
            </select>`;
        return `<li class="battery-match${m.matchKey === nextKey ? " next" : ""}${slot.source === "used" ? " played" : ""}" data-match="${esc(m.matchKey)}">
            <div class="battery-match-label">
                <span>${esc(matchUtils().matchLabel(m))}</span>
                ${alliance ? `<span class="battery-alliance ${alliance.toLowerCase()}">${esc(alliance === "Red" ? t("checklist.alliance.red", "Red") : t("checklist.alliance.blue", "Blue"))}</span>` : ""}
            </div>
            <div>${chip}${skipped}</div>
            <div class="battery-match-actions">
                ${slot.batteryId && slot.source !== "used" ? `<button type="button" class="btn ghost" data-action="skip">${esc(t("battery.action.skip", "Skip"))}</button>` : ""}
                ${slot.skipped.length ? `<button type="button" class="btn ghost" data-action="unskip">${esc(t("battery.action.unskip", "Undo skips"))}</button>` : ""}
                ${pinSelect}
                ${slot.source === "used"
                    ? `<button type="button" class="btn ghost" data-action="unuse">${esc(t("battery.action.unmark_used", "Not used"))}</button>`
                    : slot.batteryId ? `<button type="button" class="btn" data-action="use">${esc(t("battery.action.mark_used", "Mark used"))}</button>` : ""}
            </div>
        </li>`;
    }).join("")}</ul>`;

    if (nextKey && state.scrolledFor !== state.eventKey) {
        state.scrolledFor = state.eventKey;
        el.rotation.querySelector(".battery-match.next")?.scrollIntoView({ block: "nearest" });
    }
}

function wireRotation() {
    el.rotation.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-action]");
        const matchKey = btn?.closest("[data-match]")?.dataset.match;
        if (!matchKey) return;
        const slot = schedule()[matchKey];
        if (!slot) return;
        Obsidianscout.triggerHaptic?.("light");
        editPlan((doc) => {
            switch (btn.dataset.action) {
                case "skip": if (slot.batteryId) battery().skip(doc, matchKey, slot.batteryId, state.me.username); break;
                case "unskip": battery().clearSkips(doc, matchKey); break;
                case "use": if (slot.batteryId) battery().markUsed(doc, matchKey, slot.batteryId); break;
                case "unuse": battery().markUsed(doc, matchKey, ""); break;
            }
        });
    });

    el.rotation.addEventListener("change", (e) => {
        if (!e.target.matches("select[data-pin]")) return;
        const matchKey = e.target.closest("[data-match]")?.dataset.match;
        const id = e.target.value;
        if (matchKey) editPlan((doc) => battery().pin(doc, matchKey, id));
    });

    el.settings.addEventListener("change", (e) => {
        const setting = e.target.dataset.setting;
        if (setting === "startAt") {
            const id = e.target.value;
            editPlan((doc) => {
                if (id) doc.startAt = id;
                else delete doc.startAt;
            });
        } else if (setting === "autoMarkUsed") {
            const on = e.target.checked;
            editPlan((doc) => {
                if (on) delete doc.autoMarkUsed;
                else doc.autoMarkUsed = false;
            });
        }
    });
}
