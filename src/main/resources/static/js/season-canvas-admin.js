/*
 * Default Configs page: per-season canvas defaults (superadmin).
 *
 * For each program and season year a superadmin can design a canvas scouting form and switch it on, so every team on
 * that season that hasn't made its own canvas choice sees it, or leave it off (or remove it) so teams make their
 * own. The canvas is edited with the same editor teams use (canvas-form-editor.js); its buttons and widgets connect
 * to fields by id, taken from one of the program's match presets.
 */
(function () {
    "use strict";

    const $ = (sel, root) => (root || document).querySelector(sel);
    function h(tag, attrs, ...children) {
        const el = document.createElement(tag);
        for (const [k, v] of Object.entries(attrs || {})) {
            if (v === undefined || v === null || v === false) continue;
            if (k === "class") el.className = v;
            else if (k === "text") el.textContent = v;
            else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
            else if (k === "value") el.value = v;
            else el.setAttribute(k, v === true ? "" : String(v));
        }
        children.flat().forEach((c) => { if (c !== null && c !== undefined && c !== false) el.append(c); });
        return el;
    }
    const icon = (name) => h("span", { class: "material-symbols-outlined", "aria-hidden": "true", text: name });
    const toast = (msg, kind) => { if (window.Obsidianscout && Obsidianscout.showToast) Obsidianscout.showToast(msg, kind || "info"); };
    const api = (path, opts) => Obsidianscout.request(path, opts);

    const state = {
        program: "FRC",
        year: new Date().getFullYear(),
        seasons: [],        // SeasonCanvasDTO[] for the program
        presets: [],        // the program's match presets
        current: null,      // SeasonCanvasDTO for program + year, or null
        layout: null,       // layout being edited
        editor: null,
        saveTimer: null,
        saving: false
    };

    let root, listEl, statusEl, editorHost, saveStateEl;

    function presetFields() {
        const name = state.layout && state.layout.basedOnPreset;
        const preset = state.presets.find((p) => p.name === name) || state.presets.find((p) => p.isDefault) || state.presets[0];
        if (!preset) return [];
        try { return (JSON.parse(preset.configJson).fields || []).filter((f) => f && f.id); } catch (e) { return []; }
    }

    async function loadPrograms() {
        const [seasons, presets] = await Promise.all([
            api(`/api/admin/season-canvas?program=${encodeURIComponent(state.program)}`).catch(() => []),
            api("/api/admin/default-configs").catch(() => [])
        ]);
        state.seasons = Array.isArray(seasons) ? seasons : [];
        state.presets = (Array.isArray(presets) ? presets : []).filter((p) => p.program === state.program && p.configType === "match");
    }

    async function selectSeason() {
        await flushSave();
        state.current = state.seasons.find((s) => s.year === state.year) || null;
        state.layout = state.current ? JSON.parse(JSON.stringify(state.current.layout)) : null;
        render();
    }

    // ------------------------------------------------------------------ saving

    function scheduleSave() {
        clearTimeout(state.saveTimer);
        setSaveState("Unsaved changes...");
        state.saveTimer = setTimeout(save, 600);
    }

    async function flushSave() {
        if (state.saveTimer) { clearTimeout(state.saveTimer); state.saveTimer = null; await save(); }
    }

    async function save() {
        state.saveTimer = null;
        if (!state.layout) return;
        state.saving = true;
        setSaveState("Saving...");
        try {
            const saved = await api(`/api/admin/season-canvas/${state.program}/${state.year}`, { method: "PUT", json: { layout: state.layout } });
            state.current = saved;
            const i = state.seasons.findIndex((s) => s.year === saved.year);
            if (i >= 0) state.seasons[i] = saved; else state.seasons.push(saved);
            state.seasons.sort((a, b) => b.year - a.year);
            setSaveState(saved.active ? "Saved. Teams get this canvas." : "Saved as a draft. Teams don't get it while it's off.");
            renderList();
            renderStatus();
        } catch (e) {
            setSaveState(`Couldn't save: ${e.message || e}`, true);
        } finally {
            state.saving = false;
        }
    }

    function setSaveState(text, isError) {
        if (!saveStateEl) return;
        saveStateEl.textContent = text;
        saveStateEl.classList.toggle("cfe-warn", !!isError);
    }

    // ------------------------------------------------------------------ rendering

    function renderList() {
        listEl.innerHTML = "";
        if (!state.seasons.length) {
            listEl.append(h("span", { class: "cfe-hint", text: `No ${state.program} season has a canvas default yet.` }));
            return;
        }
        listEl.append(h("span", { class: "cfe-hint", text: "Seasons with a canvas:" }));
        state.seasons.forEach((s) => {
            listEl.append(h("button", {
                type: "button",
                class: `cfe-chip${s.year === state.year ? " cfe-chip-active" : ""}`,
                title: s.active ? "Teams get this canvas" : "Saved, but switched off",
                onclick: async () => { state.year = s.year; $("#season-year", root).value = String(s.year); await selectSeason(); }
            }, `${s.year} - ${s.active ? "on" : "off"}`));
        });
    }

    function renderStatus() {
        statusEl.innerHTML = "";
        const label = `${state.program} ${state.year}`;
        if (!state.layout) {
            statusEl.append(h("div", { class: "card soft cfe-season-card" },
                h("h3", { text: `${label}: no canvas default` }),
                h("p", { class: "cfe-hint", text: "Teams on this season use the classic list until they design their own canvas in Admin Settings. That's the right choice if you want every team to build its own." }),
                h("div", { class: "cfe-row" },
                    h("button", { type: "button", class: "btn", disabled: !state.presets.length, onclick: () => {
                        const base = state.presets.find((p) => p.isDefault) || state.presets[0];
                        state.layout = { mode: "classic", pages: [], basedOnPreset: base ? base.name : undefined };
                        scheduleSave();
                        render();
                    } }, icon("add"), `Design a default canvas for ${state.year}`)),
                !state.presets.length ? h("p", { class: "cfe-hint cfe-warn", text: `Create a ${state.program} match preset first: the canvas needs its fields to connect buttons and widgets to.` }) : null));
            return;
        }
        const active = state.current ? state.current.active : false;
        statusEl.append(h("div", { class: "card soft cfe-season-card" },
            h("h3", { text: active ? `${label}: teams get this canvas` : `${label}: draft (switched off)` }),
            h("p", { class: "cfe-hint", text: active
                ? "Every team set to this season that hasn't made its own canvas choice sees it on the Scout page, and gets your edits automatically. Teams can still make their own copy or switch to the classic list."
                : "Saved but not given to teams. Turn it on in step 1 below, or remove it so teams make their own." }),
            h("div", { class: "cfe-row" },
                h("button", { type: "button", class: "btn ghost cfe-danger", onclick: removeSeason }, icon("delete"), `Remove the ${state.year} canvas default`)),
            h("p", { class: "cfe-hint", text: "Removing it means teams on this season make their own canvas (or keep the classic list). Teams that already copied it keep their copy." })));
    }

    function renderFieldSource() {
        if (!state.layout) return null;
        const select = h("select", { onchange: (e) => {
            state.layout.basedOnPreset = e.target.value;
            scheduleSave();
            if (state.editor) state.editor.reload();
        } }, state.presets.map((p) => {
            const o = h("option", { value: p.name, text: `${p.name}${p.isDefault ? " (active default)" : ""}` });
            if (p.name === (state.layout.basedOnPreset || (state.presets.find((x) => x.isDefault) || {}).name)) o.selected = true;
            return o;
        }));
        return h("div", { class: "field cfe-field-source" },
            h("label", { text: "Fields to design with" }), select,
            h("p", { class: "cfe-hint", text: "Buttons and widgets connect to fields by their ID. Teams whose match form has the same fields (usually every team using this preset) get a fully working canvas. For a team that renamed or removed a field, anything tied to it is left out and the field is listed under the canvas." }));
    }

    function renderEditor() {
        editorHost.innerHTML = "";
        state.editor = null;
        if (!state.layout || !window.ObsidianCanvasEditor) return;
        const holder = h("div");
        editorHost.append(holder);
        const year = state.year, program = state.program;
        state.editor = window.ObsidianCanvasEditor.create(holder, {
            getLayout: () => state.layout,
            getFields: presetFields,
            onChange: (layout) => {
                const basedOnPreset = state.layout && state.layout.basedOnPreset;
                state.layout = { ...layout, basedOnPreset };
                scheduleSave();
            },
            copy: {
                step1Title: `Choose what teams get for ${year}`,
                step1Hint: "Turn the default on to give it to every team that hasn't made its own canvas, or leave it off so teams design their own.",
                modeLabel: "Teams without their own canvas get",
                modeOff: "Nothing (they make their own)",
                modeOn: "This canvas",
                modeOffHint: "Off: no default. Teams see the classic list until they build a canvas themselves. Your design here is kept as a draft.",
                modeOnHint: `On: every ${program} team set to ${year} that hasn't made its own choice sees this canvas on the Scout page and gets your later edits automatically. Teams can still make a copy or switch to the classic list.`,
                fieldsWhere: "The fields come from the preset chosen above.",
                liveNote: "Edits save automatically. While the default is on, teams get them the next time the Scout page loads.",
                startText: "This season's canvas is empty. Start from the preset's fields, then move and restyle them.",
                startButton: "Start from the preset's fields"
            }
        });
        state.editor.render();
    }

    function render() {
        renderList();
        renderStatus();
        const source = $("#season-field-source", root);
        source.innerHTML = "";
        const fs = renderFieldSource();
        if (fs) source.append(fs);
        saveStateEl.classList.toggle("hidden", !state.layout);
        renderEditor();
    }

    async function removeSeason() {
        if (!confirm(`Remove the ${state.program} ${state.year} canvas default? Teams on that season will make their own canvas or keep the classic list. Teams that already copied it keep their copy.`)) return;
        clearTimeout(state.saveTimer);
        state.saveTimer = null;
        try {
            await api(`/api/admin/season-canvas/${state.program}/${state.year}`, { method: "DELETE" });
            state.seasons = state.seasons.filter((s) => s.year !== state.year);
            state.current = null;
            state.layout = null;
            toast(`Removed the ${state.year} canvas default.`, "success");
            render();
        } catch (e) {
            toast(`Couldn't remove it: ${e.message || e}`, "error");
        }
    }

    async function init() {
        root = document.getElementById("season-canvas-root");
        if (!root || !window.Obsidianscout) return;
        const yearInput = h("input", { id: "season-year", type: "number", min: "1992", max: "2100", value: String(state.year), style: "width:110px" });
        const programSelect = h("select", { id: "season-program" },
            h("option", { value: "FRC", text: "FRC" }), h("option", { value: "FTC", text: "FTC" }));
        listEl = h("div", { class: "cfe-chips", style: "align-items:center" });
        statusEl = h("div");
        editorHost = h("div");
        saveStateEl = h("p", { class: "cfe-hint hidden", role: "status" });
        root.append(
            h("div", { class: "cfe-row", style: "gap:16px;align-items:flex-end" },
                h("div", { class: "field" }, h("label", { for: "season-program", text: "Program" }), programSelect),
                h("div", { class: "field" }, h("label", { for: "season-year", text: "Season year" }), yearInput),
                h("p", { class: "cfe-hint", style: "flex:1;min-width:220px;margin:0", text: "Teams follow the season year set on their API keys tab. FTC seasons use the year they started (2025 for 2025-2026)." })),
            listEl,
            statusEl,
            h("div", { id: "season-field-source" }),
            saveStateEl,
            editorHost);

        programSelect.addEventListener("change", async () => {
            await flushSave();
            state.program = programSelect.value;
            await loadPrograms();
            await selectSeason();
        });
        yearInput.addEventListener("change", async () => {
            const y = Number(yearInput.value);
            if (!Number.isInteger(y) || y < 1992 || y > 2100) { yearInput.value = String(state.year); return; }
            state.year = y;
            await selectSeason();
        });
        window.addEventListener("beforeunload", (e) => {
            if (state.saveTimer || state.saving) { e.preventDefault(); e.returnValue = ""; }
        });

        await loadPrograms();
        await selectSeason();
    }

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
})();
