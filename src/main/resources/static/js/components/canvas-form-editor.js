/*
 * Admin editor for canvas scouting forms (layout model documented in canvas-form.js).
 *
 *   const editor = ObsidianCanvasEditor.create(container, {
 *       getLayout: () => currentConfig.layout,   // raw layout (may be undefined)
 *       getFields: () => currentConfig.fields,
 *       onChange: (layout) => { ... }             // called with the new layout after every edit
 *   });
 *   editor.render();                              // re-read after the config changed elsewhere
 *
 * Drag elements to move them, drag a corner to resize. With the canvas focused: arrow keys nudge (Shift = 10),
 * Delete removes, Ctrl/Cmd+D duplicates, Escape deselects.
 */
(function () {
    "use strict";

    const CF = () => window.ObsidianCanvasForm;
    const t = (key, fallback) =>
        (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;
    const toast = (msg, kind) => { if (window.Obsidianscout && Obsidianscout.showToast) Obsidianscout.showToast(msg, kind || "info"); };

    const SIZE_PRESETS = [
        { id: "1280x720", label: "Landscape 16:9 (1280 x 720)", w: 1280, h: 720 },
        { id: "1024x768", label: "Tablet 4:3 (1024 x 768)", w: 1024, h: 768 },
        { id: "720x1280", label: "Phone portrait (720 x 1280)", w: 720, h: 1280 },
        { id: "1280x1600", label: "Tall scroll (1280 x 1600)", w: 1280, h: 1600 }
    ];
    const PHASES = [["", "None (no controller)"], ["auto", "Auto"], ["teleop", "Teleop"], ["endgame", "Endgame"], ["postmatch", "Post match"]];
    const ACTION_LABELS = { increment: "Add to it (+)", decrement: "Take away from it (-)", set: "Set it to one answer", toggle: "Tick / untick it", cycle: "Go to the next answer" };

    function h(tag, attrs, ...children) {
        const el = document.createElement(tag);
        if (attrs) {
            for (const [k, v] of Object.entries(attrs)) {
                if (v === undefined || v === null || v === false) continue;
                if (k === "class") el.className = v;
                else if (k === "text") el.textContent = v;
                else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
                else if (k === "value") el.value = v;
                else if (k === "checked") el.checked = !!v;
                else el.setAttribute(k, v === true ? "" : String(v));
            }
        }
        children.flat().forEach((c) => { if (c !== null && c !== undefined && c !== false) el.append(c); });
        return el;
    }
    const icon = (name) => h("span", { class: "material-symbols-outlined", "aria-hidden": "true", text: name });
    const clone = (o) => JSON.parse(JSON.stringify(o));

    async function uploadImage(file) {
        if (!file) return "";
        const processed = await Obsidianscout.processImageUpload(file, 1920, 0.85);
        try {
            // (fetch() on a data: URL is blocked by the site's connect-src policy, so decode it by hand.)
            const [head, b64] = processed.dataUrl.split(",");
            const mime = (head.match(/^data:([^;]+)/) || [])[1] || "image/jpeg";
            const raw = atob(b64);
            const bytes = new Uint8Array(raw.length);
            for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
            const blob = new Blob([bytes], { type: mime });
            const body = new FormData();
            body.append("file", blob, "canvas-image");
            const res = await Obsidianscout.request("/api/config/canvas-image", { method: "POST", body });
            if (res && res.url) return res.url;
        } catch (e) {
            const unavailable = (e && (e.status === 503 || /storage/i.test(e.message || "")));
            if (!unavailable) throw e;
        }
        // No file storage on this server: keep the (compressed) image inside the config instead.
        toast(t("canvas.image_inline", "File storage isn't running, so the image is stored inside the form config. Keep it small."), "info");
        return processed.dataUrl;
    }

    function create(container, opts) {
        let layout = CF().normalizeLayout(opts.getLayout());
        let pageIndex = 0;
        let selectedId = null;
        let snap = 10;
        let scale = 1;
        let stopFit = null;
        // Wording; the Default Configs page passes its own so the same editor fits a season default.
        const copy = Object.assign({
            step1Title: "Choose what scouts see",
            step1Hint: "Pick the classic list or your canvas, and the shape of the canvas.",
            modeLabel: t("canvas.scouts_see", "Scouts see"),
            modeOff: t("canvas.mode_classic", "Classic list"),
            modeOn: t("canvas.mode_canvas", "Canvas layout"),
            modeOffHint: t("canvas.mode_classic_hint", "Off: scouts get the normal list form. You can keep designing here; nothing changes for scouts until you pick Canvas layout."),
            modeOnHint: t("canvas.mode_canvas_hint", "On: the Scout page opens with this canvas. The classic list still works underneath, so saving and data are unchanged."),
            fieldsWhere: "Add, rename or delete fields in the Visual Editor tab.",
            liveNote: "Scouts only see the canvas once step 1 is set to Canvas layout. Anything not placed can still be listed under the canvas, so nothing gets missed.",
            startText: "Nothing is on a canvas yet. Start from your current fields, then move and restyle them.",
            startButton: "Start a canvas from my fields"
        }, opts.copy || {});

        const fields = () => (opts.getFields() || []).filter((f) => f && f.id);
        const fieldsById = () => new Map(fields().map((f) => [f.id, f]));
        const page = () => layout.pages[pageIndex] || null;
        const selected = () => (page() ? page().elements.find((e) => e.id === selectedId) : null) || null;

        let commitTimer = null;
        let commitPending = false;
        function commit(now) {
            clearTimeout(commitTimer);
            const send = () => { commitPending = false; opts.onChange(clone(layout)); };
            if (now) send(); else { commitPending = true; commitTimer = setTimeout(send, 350); }
        }

        container.innerHTML = "";
        container.classList.add("cfe");
        const introEl = h("div", { class: "cfe-intro" });
        const settingsEl = h("div", { class: "card soft cfe-settings" });
        const pagesEl = h("div", { class: "cfe-row cfe-pages" });
        const pageSettingsEl = h("div", { class: "cfe-page-settings" });
        const toolbarEl = h("div", { class: "cfe-palette" });
        const viewport = h("div", { class: "cf-viewport cfe-viewport", tabindex: "0", "aria-label": "Canvas editor" });
        const inspector = h("div", { class: "cfe-inspector" });
        const unplacedEl = h("div", { class: "grid gap-8" });
        const stepsEl = h("div", { class: "cfe-steps" });
        container.append(introEl, stepsEl);

        const stepHead = (n, title, hint) => h("div", { class: "cfe-step-head" },
            h("span", { class: "cfe-step-num", text: String(n) }),
            h("div", null, h("h3", { text: title }), hint ? h("p", { class: "cfe-hint", text: hint }) : null));

        /** Steps 2-4 only make sense once scouts can see the canvas (or there are pages to edit). */
        function renderSteps() {
            stepsEl.innerHTML = "";
            stepsEl.append(
                h("section", { class: "cfe-step" },
                    stepHead(1, copy.step1Title, copy.step1Hint),
                    settingsEl)
            );
            if (!layout.pages.length && layout.mode !== "canvas") {
                stepsEl.append(h("div", { class: "card soft cfe-start" },
                    h("p", { text: copy.startText }),
                    h("button", { type: "button", class: "btn", onclick: () => { generateFromFields(); commit(true); render(); } }, icon("auto_awesome"), copy.startButton)));
                return;
            }
            stepsEl.append(
                h("section", { class: "cfe-step" },
                    stepHead(2, "Pages", "Pages are tabs scouts swipe between, usually one per match phase (Auto, Teleop, Endgame). Pick a page to edit it."),
                    pagesEl, pageSettingsEl),
                h("section", { class: "cfe-step" },
                    stepHead(3, "Add things to this page", "Click one to drop it in the middle of the page. You can move it next."),
                    toolbarEl),
                h("section", { class: "cfe-step" },
                    stepHead(4, "Arrange and set up", "Drag things to move them, drag a corner to resize, and click one to see and change its settings."),
                    h("div", { class: "cfe-main" }, h("div", { class: "grid gap-8", style: "min-width:0" }, viewport, unplacedEl), inspector))
            );
        }

        // -------------------------------------------------------------- guide

        const TIPS_KEY = "obsidian-canvas-editor-tips";
        function tipsOpen() { try { return localStorage.getItem(TIPS_KEY) !== "hidden"; } catch (e) { return true; } }
        function renderIntro() {
            introEl.innerHTML = "";
            const open = tipsOpen();
            const toggle = h("button", { type: "button", class: "btn ghost", onclick: () => {
                try { localStorage.setItem(TIPS_KEY, open ? "hidden" : "shown"); } catch (e) { /* ignore */ }
                renderIntro();
            } }, icon(open ? "expand_less" : "help"), open ? "Hide guide" : "How does this work?");
            if (!open) {
                introEl.append(h("div", { class: "cfe-row", style: "justify-content:flex-end" }, toggle));
                return;
            }
            const item = (ic, title, text) => h("li", null, icon(ic), h("div", null, h("strong", { text: title }), h("span", { text })));
            introEl.append(h("div", { class: "card soft cfe-guide" },
                h("div", { class: "cfe-row", style: "justify-content:space-between" },
                    h("h3", { text: "How the canvas works" }), toggle),
                h("p", { class: "cfe-hint", text: "The canvas lets you draw your own scouting screen instead of a plain list: put a field map in the background, place big tap buttons on it, and arrange everything where your scouts expect it." }),
                h("ul", { class: "cfe-guide-list" },
                    item("list_alt", "Your fields still decide what gets recorded",
                        `${copy.fieldsWhere} The canvas only decides where they appear and how scouts tap them. Removing something from the canvas never deletes a field or any data.`),
                    item("input", "Field widgets show a field's normal control",
                        "A counter with + and -, a dropdown, a notes box, and so on. Each field can be placed once."),
                    item("touch_app", "Buttons are big shortcuts for a field",
                        "Tapping one changes a field: add 1 to a count, pick a dropdown answer, or tick a checkbox. You can have many buttons for the same field, and make them see-through to sit on top of a picture."),
                    item("image", "Images and text are just for looks",
                        "Use them for field maps, robot diagrams, headings or instructions. They don't record anything."),
                    item("cloud_done", "Changes save as you go", copy.liveNote))));
        }

        // -------------------------------------------------------------- form-wide settings

        function renderSettings() {
            settingsEl.innerHTML = "";
            const modeSeg = h("div", { class: "cfe-segment", role: "group" },
                h("button", { type: "button", class: layout.mode === "classic" ? "active" : "", text: copy.modeOff,
                    onclick: () => { layout.mode = "classic"; commit(true); render(); } }),
                h("button", { type: "button", class: layout.mode === "canvas" ? "active" : "", text: copy.modeOn,
                    onclick: () => {
                        layout.mode = "canvas";
                        if (!layout.pages.length) generateFromFields();
                        commit(true); render();
                    } }));
            const presetSel = h("select", { onchange: (e) => {
                const p = SIZE_PRESETS.find((x) => x.id === e.target.value);
                if (p) setSize(p.w, p.h);
            } }, h("option", { value: "", text: "Custom size" }),
            SIZE_PRESETS.map((p) => h("option", { value: p.id, text: p.label, selected: p.w === layout.width && p.h === layout.height })));
            const wIn = h("input", { type: "number", min: "200", max: "4000", value: layout.width, "aria-label": "Canvas width", onchange: (e) => setSize(Number(e.target.value), layout.height) });
            const hIn = h("input", { type: "number", min: "200", max: "6000", value: layout.height, "aria-label": "Canvas height", onchange: (e) => setSize(layout.width, Number(e.target.value)) });

            const check = (checked, onToggle, label, hint) => h("div", null,
                h("label", { class: "checkbox-label" }, h("input", { type: "checkbox", checked, onchange: (e) => onToggle(e.target.checked) }), h("span", { text: label })),
                h("p", { class: "cfe-hint cfe-indent", text: hint }));
            settingsEl.append(
                h("div", { class: "field" }, h("label", { text: copy.modeLabel }), modeSeg,
                    h("p", { class: "cfe-hint", text: layout.mode === "canvas" ? copy.modeOnHint : copy.modeOffHint })),
                h("div", { class: "field" }, h("label", { text: t("canvas.size", "Canvas shape") }), presetSel,
                    h("div", { class: "cfe-size mt-6" }, wIn, h("span", { text: "wide x" }), hIn, h("span", { text: "tall" })),
                    h("p", { class: "cfe-hint", text: "Choose the shape of the screens your scouts use. The canvas shrinks or grows to fit each screen but keeps this shape, so a landscape canvas looks small on a phone held upright." })),
                h("div", { class: "grid gap-8" },
                    check(layout.showUnplaced, (v) => { layout.showUnplaced = v; commit(true); renderUnplaced(); },
                        t("canvas.show_unplaced", "List fields that aren't on the canvas below it"),
                        "Recommended. Any field you haven't placed still appears in a short list under the canvas, so scouts can fill it in."),
                    check(layout.allowClassicToggle, (v) => { layout.allowClassicToggle = v; commit(true); },
                        t("canvas.allow_toggle", "Let scouts switch to the classic list on their device"),
                        "Adds a \"Classic list\" button on the Scout page, handy for scouts on small phones. It only changes their own screen."))
            );
        }

        function setSize(w, hgt) {
            layout.width = Math.round(Math.min(4000, Math.max(200, Number(w) || layout.width)));
            layout.height = Math.round(Math.min(6000, Math.max(200, Number(hgt) || layout.height)));
            layout = CF().normalizeLayout(layout);
            commit(true);
            render();
        }

        // -------------------------------------------------------------- pages

        function renderPages() {
            pagesEl.innerHTML = "";
            pageSettingsEl.innerHTML = "";
            const tabs = h("div", { class: "tab-row", style: "margin:0" });
            layout.pages.forEach((p, i) => {
                tabs.append(h("button", { type: "button", class: `tab${i === pageIndex ? " active" : ""}`, text: p.name,
                    onclick: () => { pageIndex = i; selectedId = null; render(); } }));
            });
            pagesEl.append(tabs,
                h("button", { type: "button", class: "btn ghost", title: "Add an empty page", onclick: addPage }, icon("add"), t("canvas.add_page", "New page")),
                h("button", { type: "button", class: "btn ghost", title: "Replaces every page with one page per phase, each holding that phase's fields",
                    onclick: () => {
                        if (layout.pages.length && !confirm(t("canvas.confirm_generate", "Replace all canvas pages with an automatic layout of your fields?"))) return;
                        generateFromFields(); commit(true); render();
                    } }, icon("auto_awesome"), t("canvas.generate", "Start over from my fields")));

            const p = page();
            if (!p) return;
            const bg = p.background || {};
            const bgFile = h("input", { type: "file", accept: "image/png,image/jpeg,image/webp", class: "input-hidden", onchange: async (e) => {
                const file = e.target.files[0];
                e.target.value = "";
                if (!file) return;
                try {
                    p.background = { ...(p.background || {}), image: await uploadImage(file) };
                    commit(true); render();
                } catch (err) { toast(err.message || "Upload failed", "error"); }
            } });
            pageSettingsEl.append(
                h("div", { class: "field" }, h("label", { text: "Page name" }),
                    h("input", { type: "text", value: p.name, maxlength: "40", oninput: (e) => { p.name = e.target.value || "Page"; commit(); renderPagesTabsOnly(); } }),
                    h("p", { class: "cfe-hint", text: "Shown on the tab above the canvas." })),
                h("div", { class: "field" }, h("label", { text: "Controller phase" }),
                    h("select", { onchange: (e) => { p.phase = e.target.value; commit(true); } },
                        PHASES.map(([v, l]) => h("option", { value: v, text: l, selected: (p.phase || "") === v }))),
                    h("p", { class: "cfe-hint", text: "Only matters if scouts use a gamepad or keyboard: that phase's button mapping is used while this page is open." })),
                h("div", { class: "field" }, h("label", { text: "Background" }),
                    h("div", { class: "cfe-row" },
                        h("input", { type: "color", value: CF().safeColor(bg.color, "#ffffff"), "aria-label": "Background colour",
                            oninput: (e) => { p.background = { ...(p.background || {}), color: e.target.value }; commit(); renderStage(); } }),
                        h("label", { class: "btn ghost btn-file" }, icon("image"), bg.image ? "Replace image" : "Image", bgFile),
                        bg.image ? h("select", { "aria-label": "Background fit", onchange: (e) => { p.background.fit = e.target.value; commit(true); renderStage(); } },
                            [["contain", "Fit"], ["cover", "Fill and crop"], ["fill", "Stretch"]].map(([v, l]) => h("option", { value: v, text: l, selected: (bg.fit || "contain") === v }))) : null,
                        bg.image ? h("button", { type: "button", class: "btn ghost", onclick: () => { delete p.background.image; commit(true); render(); } }, "Remove image") : null),
                    h("p", { class: "cfe-hint", text: "A colour or picture behind everything on this page, such as a field map. For a picture you want to resize or move, use Add > Image instead." })),
                h("div", { class: "field" }, h("label", { text: "Order and delete" }),
                    h("div", { class: "cfe-row" },
                        h("button", { type: "button", class: "btn ghost", disabled: pageIndex === 0, title: "Show this tab earlier", onclick: () => movePage(-1) }, icon("chevron_left"), "Move left"),
                        h("button", { type: "button", class: "btn ghost", disabled: pageIndex === layout.pages.length - 1, title: "Show this tab later", onclick: () => movePage(1) }, icon("chevron_right"), "Move right"),
                        h("button", { type: "button", class: "btn ghost cfe-danger", onclick: deletePage }, icon("delete"), "Delete page")))
            );
        }

        function renderPagesTabsOnly() {
            const tabs = pagesEl.querySelectorAll(".tab-row .tab");
            layout.pages.forEach((p, i) => { if (tabs[i]) tabs[i].textContent = p.name; });
        }

        function addPage() {
            layout.pages.push({ id: CF().uid("page"), name: `Page ${layout.pages.length + 1}`, phase: "", background: {}, elements: [] });
            pageIndex = layout.pages.length - 1;
            selectedId = null;
            commit(true); render();
        }
        function movePage(dir) {
            const j = pageIndex + dir;
            if (j < 0 || j >= layout.pages.length) return;
            [layout.pages[pageIndex], layout.pages[j]] = [layout.pages[j], layout.pages[pageIndex]];
            pageIndex = j;
            commit(true); render();
        }
        function deletePage() {
            const p = page();
            if (!p) return;
            if (p.elements.length && !confirm(`Delete page "${p.name}" and its ${p.elements.length} element(s)?`)) return;
            layout.pages.splice(pageIndex, 1);
            pageIndex = Math.max(0, pageIndex - 1);
            selectedId = null;
            commit(true); render();
        }

        /** One page per phase, each holding that phase's fields as widgets in a grid. */
        function generateFromFields() {
            const phaseOf = (f) => {
                // Same rule as the scout page: the field's phase, else its id prefix, else teleop.
                const p = String(f.phase || "").toLowerCase().trim() || String(f.id || "").toLowerCase();
                if (p.startsWith("post")) return "postmatch";
                for (const ph of ["auto", "teleop", "endgame"]) if (p.startsWith(ph)) return ph;
                return "teleop";
            };
            const heightFor = (type) => ({ counter: 120, select: 110, rating: 110, checkbox: 70, timer: 150, textarea: 180, notes: 180, image: 280, photo: 280, image_upload: 280 }[type] || 110);
            const names = { auto: "Auto", teleop: "Teleop", endgame: "Endgame", postmatch: "Post match" };
            const W = layout.width;
            const cols = W >= 1000 ? 3 : (W >= 640 ? 2 : 1);
            const gap = 20;
            const colW = Math.floor((W - gap * (cols + 1)) / cols);
            let maxBottom = 0;
            const pages = [];
            ["auto", "teleop", "endgame", "postmatch"].forEach((phase) => {
                const list = fields().filter((f) => f.type !== "section" && phaseOf(f) === phase);
                if (!list.length) return;
                const colY = new Array(cols).fill(gap);
                const elements = list.map((f) => {
                    const c = colY.indexOf(Math.min(...colY));
                    const hh = heightFor(f.type);
                    const el = { id: CF().uid("el"), type: "field", fieldId: f.id, x: gap + c * (colW + gap), y: colY[c], w: colW, h: hh, style: {} };
                    colY[c] += hh + gap;
                    maxBottom = Math.max(maxBottom, colY[c]);
                    return el;
                });
                pages.push({ id: CF().uid("page"), name: names[phase], phase, background: {}, elements });
            });
            if (!pages.length) pages.push({ id: CF().uid("page"), name: "Page 1", phase: "", background: {}, elements: [] });
            if (maxBottom > layout.height) layout.height = Math.min(6000, Math.ceil(maxBottom / 10) * 10);
            layout.pages = pages;
            layout = CF().normalizeLayout(layout);
            pageIndex = 0;
            selectedId = null;
        }

        // -------------------------------------------------------------- toolbar

        function renderToolbar() {
            toolbarEl.innerHTML = "";
            const disabled = !page();
            const add = (type, iconName, label, desc) =>
                h("button", { type: "button", class: "cfe-palette-item", disabled, onclick: () => addElement(type) },
                    icon(iconName), h("strong", { text: label }), h("span", { text: desc }));
            toolbarEl.append(
                add("field", "input", "Field widget", "One of your fields with its normal control (counter, dropdown, notes...)."),
                add("button", "touch_app", "Tap button", "A big button that changes a field: +1 to a count, pick an answer, tick a box."),
                add("image", "image", "Image", "A picture such as a field map. Put tap buttons on top of it."),
                add("value", "pin", "Live value", "Shows a field's current answer so scouts can check it at a glance."),
                add("text", "title", "Text", "A heading, label or instructions for scouts."),
                add("shape", "crop_square", "Box", "A coloured box to group or highlight an area."),
                h("label", { class: "checkbox-label cfe-snap", title: "Keeps things lined up by moving in steps of 10" },
                    h("input", { type: "checkbox", checked: snap > 1, onchange: (e) => { snap = e.target.checked ? 10 : 1; renderStage(); } }),
                    h("span", { text: "Snap to grid (keeps things lined up)" }))
            );
        }

        function addElement(type) {
            const p = page();
            if (!p) return;
            const W = layout.width, H = layout.height;
            const sizes = { button: [180, 100], field: [380, 120], image: [Math.round(W * 0.6), Math.round(H * 0.6)], text: [300, 60], value: [200, 70], shape: [300, 200] };
            const [w, hh] = sizes[type];
            const el = { id: CF().uid("el"), type, w, h: hh, x: Math.round((W - w) / 2 / 10) * 10, y: Math.round((H - hh) / 2 / 10) * 10, style: {} };
            const used = usedFieldWidgets();
            if (type === "button") {
                const f = fields().find((x) => CF().BUTTON_FIELD_TYPES.has(x.type));
                if (f) { el.fieldId = f.id; el.action = CF().actionsFor(f.type)[0]; el.amount = f.step || 1; if (el.action === "set") el.value = defaultSetValue(f); }
                el.showValue = !!f && f.type === "counter";
            } else if (type === "field") {
                const f = fields().find((x) => x.type !== "section" && !used.has(x.id));
                if (f) el.fieldId = f.id;
            } else if (type === "value") {
                const f = fields().find((x) => x.type !== "section");
                if (f) el.fieldId = f.id;
                el.prefix = "";
            } else if (type === "text") {
                el.text = "Label";
            }
            p.elements.push(el);
            selectedId = el.id;
            commit(true);
            render();
            if (type === "image") setTimeout(() => { const inp = inspector.querySelector("input[type=file]"); if (inp) inp.click(); }, 0);
        }

        function defaultSetValue(f) {
            if (f.type === "select") return (f.options && f.options[0]) ? f.options[0].value : "";
            if (f.type === "checkbox") return "true";
            if (f.type === "text" || f.type === "textarea") return "";
            return f.min || 0;
        }

        function usedFieldWidgets(exceptId) {
            const used = new Set();
            layout.pages.forEach((p) => p.elements.forEach((e) => { if (e.type === "field" && e.fieldId && e.id !== exceptId) used.add(e.fieldId); }));
            return used;
        }

        // -------------------------------------------------------------- stage

        let stageEl = null;
        function renderStage() {
            if (stopFit) { stopFit(); stopFit = null; }
            viewport.innerHTML = "";
            const p = page();
            if (!p) {
                viewport.style.height = "auto";
                viewport.append(h("div", { style: "padding:40px;text-align:center;color:var(--muted)" },
                    h("p", { text: "No pages yet." }),
                    h("button", { type: "button", class: "btn", onclick: () => { generateFromFields(); commit(true); render(); } }, "Lay out my fields on a canvas")));
                return;
            }
            stageEl = CF().renderStage(layout, p, { mode: "edit", fieldsById: fieldsById() });
            if (!(p.background && (p.background.color || p.background.image))) stageEl.style.backgroundColor = "#ffffff";
            if (snap > 1) {
                const grid = h("div", { class: "cfe-grid" });
                grid.style.backgroundImage = "linear-gradient(rgba(91,78,199,.08) 1px, transparent 1px), linear-gradient(90deg, rgba(91,78,199,.08) 1px, transparent 1px)";
                grid.style.backgroundSize = `${snap * 4}px ${snap * 4}px`;
                stageEl.append(grid);
            }
            viewport.append(stageEl);
            if (!p.elements.length) {
                viewport.append(h("div", { class: "cfe-empty-hint" },
                    icon("add_circle"), h("strong", { text: "This page is empty" }),
                    h("span", { text: "Use step 3 above to add a field widget, an image or a tap button." })));
            }
            markSelection();
            stopFit = CF().fitStage(viewport, stageEl, layout, { maxHeight: Math.max(320, window.innerHeight * 0.75), onScale: (s) => { scale = s; } });
        }

        function markSelection() {
            if (!stageEl) return;
            stageEl.querySelectorAll(".cfe-handle").forEach((x) => x.remove());
            stageEl.querySelectorAll(".cfe-selected").forEach((x) => x.classList.remove("cfe-selected"));
            const node = selectedId ? stageEl.querySelector(`[data-el-id="${CSS.escape(selectedId)}"]`) : null;
            if (!node) return;
            node.classList.add("cfe-selected");
            ["nw", "ne", "sw", "se"].forEach((k) => node.append(h("span", { class: "cfe-handle", "data-h": k })));
        }

        const snapTo = (v) => Math.round(v / snap) * snap;
        let drag = null;

        viewport.addEventListener("pointerdown", (e) => {
            if (!stageEl || e.button > 0) return;
            const node = e.target.closest(".cf-el");
            const handle = e.target.closest(".cfe-handle");
            viewport.focus({ preventScroll: true });
            if (!node) {
                if (selectedId) { selectedId = null; markSelection(); renderInspector(); }
                return;
            }
            const el = page().elements.find((x) => x.id === node.dataset.elId);
            if (!el) return;
            e.preventDefault();
            if (selectedId !== el.id) { selectedId = el.id; markSelection(); renderInspector(); }
            drag = { el, node, mode: handle ? handle.dataset.h : "move", sx: e.clientX, sy: e.clientY, box: { x: el.x, y: el.y, w: el.w, h: el.h }, moved: false };
            viewport.setPointerCapture(e.pointerId);
        });

        viewport.addEventListener("pointermove", (e) => {
            if (!drag) return;
            const dx = (e.clientX - drag.sx) / scale;
            const dy = (e.clientY - drag.sy) / scale;
            if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 2) return;
            drag.moved = true;
            const b = drag.box, el = drag.el, min = 16;
            if (drag.mode === "move") {
                el.x = snapTo(b.x + dx);
                el.y = snapTo(b.y + dy);
            } else {
                let x1 = b.x, y1 = b.y, x2 = b.x + b.w, y2 = b.y + b.h;
                if (drag.mode.includes("w")) x1 = Math.min(snapTo(b.x + dx), x2 - min);
                if (drag.mode.includes("e")) x2 = Math.max(snapTo(b.x + b.w + dx), x1 + min);
                if (drag.mode.includes("n")) y1 = Math.min(snapTo(b.y + dy), y2 - min);
                if (drag.mode.includes("s")) y2 = Math.max(snapTo(b.y + b.h + dy), y1 + min);
                el.x = x1; el.y = y1; el.w = x2 - x1; el.h = y2 - y1;
            }
            el.x = Math.max(-el.w + 16, Math.min(layout.width - 16, el.x));
            el.y = Math.max(-el.h + 16, Math.min(layout.height - 16, el.y));
            Object.assign(drag.node.style, { left: `${el.x}px`, top: `${el.y}px`, width: `${el.w}px`, height: `${el.h}px` });
        });

        const endDrag = () => {
            if (!drag) return;
            const moved = drag.moved;
            drag = null;
            if (moved) { commit(true); renderInspector(); }
        };
        viewport.addEventListener("pointerup", endDrag);
        viewport.addEventListener("pointercancel", endDrag);

        viewport.addEventListener("keydown", (e) => {
            const el = selected();
            if (!el) return;
            const step = e.shiftKey ? 10 : 1;
            const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
            if (moves[e.key]) {
                e.preventDefault();
                el.x += moves[e.key][0];
                el.y += moves[e.key][1];
                renderStage(); renderInspector(); commit();
            } else if (e.key === "Delete" || e.key === "Backspace") {
                e.preventDefault(); removeSelected();
            } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "d") {
                e.preventDefault(); duplicateSelected();
            } else if (e.key === "Escape") {
                selectedId = null; markSelection(); renderInspector();
            }
        });

        function removeSelected() {
            const p = page();
            if (!p || !selectedId) return;
            p.elements = p.elements.filter((x) => x.id !== selectedId);
            selectedId = null;
            commit(true); render();
        }
        function duplicateSelected() {
            const el = selected();
            if (!el) return;
            const copy = { ...clone(el), id: CF().uid("el"), x: el.x + 20, y: el.y + 20 };
            if (copy.type === "field") delete copy.fieldId; // a field's widget can only be on the canvas once
            page().elements.push(copy);
            selectedId = copy.id;
            commit(true); render();
        }
        function reorder(toFront) {
            const p = page(), el = selected();
            if (!p || !el) return;
            p.elements = p.elements.filter((x) => x !== el);
            if (toFront) p.elements.push(el); else p.elements.unshift(el);
            commit(true); renderStage();
        }

        // -------------------------------------------------------------- inspector

        const fieldRow = (label, control, hint) => h("div", { class: "field" }, h("label", { text: label }), control, hint ? h("p", { class: "cfe-hint", text: hint }) : null);
        const numInput = (value, onVal, attrs) => h("input", { type: "number", value: value ?? "", ...(attrs || {}), oninput: (e) => onVal(e.target.value === "" ? undefined : Number(e.target.value)) });

        /** Updates el from an inspector control: redraw the stage now, save shortly after. */
        function edit(el, mutate) {
            mutate(el);
            renderStage();
            commit();
        }

        function colorControl(label, s, key, opKey, dflt, dfltOp) {
            const parts = [h("input", { type: "color", value: CF().safeColor(s[key], dflt), "aria-label": label, oninput: (e) => edit(null, () => { s[key] = e.target.value; }) })];
            if (opKey) {
                const pct = Math.round((s[opKey] ?? dfltOp) * 100);
                const out = h("span", { class: "cfe-hint", style: "width:42px;text-align:right", text: `${pct}%` });
                parts.push(h("input", { type: "range", min: "0", max: "100", value: pct, "aria-label": `${label} opacity`,
                    oninput: (e) => { out.textContent = `${e.target.value}%`; edit(null, () => { s[opKey] = Number(e.target.value) / 100; }); } }), out);
            }
            return fieldRow(label, h("div", { class: "cfe-color" }, parts));
        }

        function textStyleControls(s, dflt) {
            return [
                h("div", { class: "cfe-grid2" },
                    fieldRow("Font size", numInput(s.fontSize ?? dflt.fontSize, (v) => edit(null, () => { s.fontSize = v; }), { min: "6", max: "200" })),
                    fieldRow("Align", h("select", { onchange: (e) => edit(null, () => { s.align = e.target.value; }) },
                        ["left", "center", "right"].map((a) => h("option", { value: a, text: a, selected: (s.align || dflt.align) === a }))))),
                h("label", { class: "checkbox-label" }, h("input", { type: "checkbox", checked: s.bold ?? dflt.bold, onchange: (e) => edit(null, () => { s.bold = e.target.checked; }) }), h("span", { text: "Bold" })),
                colorControl("Text colour", s, "color", null, dflt.color)
            ];
        }

        function fieldSelect(el, filter, onPick) {
            const list = fields().filter(filter);
            return h("select", { onchange: (e) => onPick(e.target.value) },
                h("option", { value: "", text: "-- choose a field --" }),
                list.map((f) => h("option", { value: f.id, text: `${CF().fieldLabel(f)} (${f.type})`, selected: f.id === el.fieldId })));
        }

        /** One plain sentence: what a tap on this button does. */
        function describeButton(el, field) {
            const name = `"${CF().fieldLabel(field)}"`;
            const actions = CF().actionsFor(field.type);
            const action = actions.includes(el.action) ? el.action : actions[0];
            const amount = Math.abs(Number(el.amount) || Number(field.step) || 1);
            if (action === "increment") return `Each tap adds ${amount} to ${name}.`;
            if (action === "decrement") return `Each tap takes ${amount} off ${name} (never below ${field.min ?? 0}). Handy as an undo button.`;
            if (action === "toggle") return `Each tap ticks or unticks ${name}.`;
            if (action === "cycle") return `Each tap moves ${name} to its next answer.`;
            if (field.type === "select") {
                const opt = (field.options || []).find((o) => String(o.value) === String(el.value));
                return `A tap sets ${name} to "${opt ? CF().fieldLabel({ label: opt.label, id: opt.value }) : el.value}". Make one button per answer.`;
            }
            if (field.type === "checkbox") return `A tap sets ${name} to ${String(el.value) === "false" ? "unticked" : "ticked"}.`;
            return `A tap sets ${name} to ${el.value === "" || el.value === undefined ? "blank" : `"${el.value}"`}.`;
        }

        function refreshSummary(el, field) {
            const span = inspector.querySelector(".cfe-summary-text");
            if (span) span.textContent = describeButton(el, field);
        }

        function renderInspector() {
            inspector.innerHTML = "";
            const el = selected();
            if (!el) {
                inspector.append(h("h3", { text: "Settings" }),
                    h("p", { class: "cfe-hint", text: "Click something on the canvas to see what it does and change it here." }),
                    h("ul", { class: "cfe-howto" },
                        h("li", { text: "Move: drag it." }),
                        h("li", { text: "Resize: drag a corner square." }),
                        h("li", { text: "Nudge: arrow keys (hold Shift for bigger steps)." }),
                        h("li", { text: "Copy: Ctrl+D. Delete: Delete key." })),
                    h("p", { class: "cfe-hint", text: "Example: add an Image of the field, then add Tap buttons over each scoring area and choose \"Make it an invisible tap area\". Scouts just tap where the robot scored." }));
                return;
            }
            const s = el.style || (el.style = {});
            const byId = fieldsById();
            const field = el.fieldId ? byId.get(el.fieldId) : null;
            const titles = { button: "Tap button", field: "Field widget", image: "Image", text: "Text", value: "Live value", shape: "Box" };
            const blurbs = {
                button: "Changes a field each time a scout taps it. Pick the field, then what a tap should do.",
                field: "Shows a field's normal control, exactly like the classic form. Resize the box if it looks cramped.",
                image: "A picture for reference, like a field map. It doesn't record anything; put tap buttons on top of it.",
                text: "A heading, label or instructions. It doesn't record anything.",
                value: "Shows the current answer for a field, updating as scouts tap. Read-only.",
                shape: "A coloured box to group things or mark an area. It doesn't record anything."
            };
            const group = (title) => h("div", { class: "cfe-group-title", text: title });
            inspector.append(h("h3", { text: titles[el.type] || "Element" }), h("p", { class: "cfe-hint", text: blurbs[el.type] || "" }));
            if (el.type === "button" || el.type === "field" || el.type === "value") inspector.append(group("What it does"));

            if (el.type === "button") {
                inspector.append(fieldRow("Which field it changes", fieldSelect(el, (f) => CF().BUTTON_FIELD_TYPES.has(f.type), (id) => {
                    el.fieldId = id || undefined;
                    const f = byId.get(id);
                    if (f) { el.action = CF().actionsFor(f.type)[0]; el.value = defaultSetValue(f); el.amount = f.step || 1; }
                    commit(true); renderStage(); renderInspector();
                })));
                if (!field) inspector.append(h("p", { class: "cfe-hint cfe-warn", text: "Pick the field this button should change. Only counters, numbers, ratings, checkboxes, dropdowns and text fields can be changed by a button." }));
                if (field) {
                    const actions = CF().actionsFor(field.type);
                    const action = actions.includes(el.action) ? el.action : actions[0];
                    inspector.append(fieldRow("What a tap does", h("select", { onchange: (e) => {
                        el.action = e.target.value;
                        if (el.action === "set" && el.value === undefined) el.value = defaultSetValue(field);
                        commit(true); renderStage(); renderInspector();
                    } }, actions.map((a) => h("option", { value: a, text: ACTION_LABELS[a], selected: a === action })))));
                    if (action === "increment" || action === "decrement") {
                        inspector.append(fieldRow("How much per tap", numInput(el.amount ?? field.step ?? 1, (v) => { edit(el, () => { el.amount = v; }); refreshSummary(el, field); }, { min: "1" })));
                    } else if (action === "set") {
                        let ctl;
                        if (field.type === "select") {
                            ctl = h("select", { onchange: (e) => edit(el, () => { el.value = e.target.value; }) },
                                (field.options || []).map((o) => h("option", { value: o.value, text: CF().fieldLabel({ label: o.label, id: o.value }), selected: String(o.value) === String(el.value) })));
                        } else if (field.type === "checkbox") {
                            ctl = h("select", { onchange: (e) => edit(el, () => { el.value = e.target.value; }) },
                                h("option", { value: "true", text: "Checked (yes)", selected: String(el.value) !== "false" }),
                                h("option", { value: "false", text: "Unchecked (no)", selected: String(el.value) === "false" }));
                        } else if (field.type === "text" || field.type === "textarea") {
                            ctl = h("input", { type: "text", value: el.value ?? "", oninput: (e) => edit(el, () => { el.value = e.target.value; }) });
                        } else {
                            ctl = numInput(el.value ?? 0, (v) => edit(el, () => { el.value = v ?? 0; }));
                        }
                        ctl.addEventListener("change", () => refreshSummary(el, field));
                        ctl.addEventListener("input", () => refreshSummary(el, field));
                        inspector.append(fieldRow("Set it to", ctl, "The button stays highlighted while the field has this answer, so scouts can see what's picked."));
                    }
                }
                if (field) inspector.append(h("div", { class: "cfe-summary" }, icon("touch_app"), h("span", { class: "cfe-summary-text", text: describeButton(el, field) })));
                inspector.append(
                    group("How it looks"),
                    fieldRow("Label", h("input", { type: "text", value: el.label ?? "", placeholder: field ? CF().defaultButtonLabel(el, field) : "Button",
                        oninput: (e) => edit(el, () => { if (e.target.value === "") delete el.label; else el.label = e.target.value; }) }), "Leave empty to use the automatic label."),
                    h("label", { class: "checkbox-label" }, h("input", { type: "checkbox", checked: el.showValue, onchange: (e) => edit(el, () => { el.showValue = e.target.checked; }) }), h("span", { text: "Show the current count on the button" })),
                    h("label", { class: "checkbox-label" }, h("input", { type: "checkbox", checked: el.label === "", onchange: (e) => edit(el, () => { if (e.target.checked) el.label = ""; else delete el.label; }) }), h("span", { text: "Hide the label" })),
                    h("button", { type: "button", class: "btn ghost", onclick: () => { s.bgOpacity = 0; el.label = ""; commit(true); renderStage(); renderInspector(); } }, icon("visibility_off"), "Make it an invisible tap area"),
                    h("p", { class: "cfe-hint", text: "Invisible tap areas let scouts tap straight on a picture. You'll see a dashed outline here; scouts see a quick flash when they tap." }),
                    colorControl("Background (slide to 0% for see-through)", s, "bg", "bgOpacity", "#5b4ec7", 0.9),
                    ...textStyleControls(s, { fontSize: 20, align: "center", bold: true, color: "#ffffff" }),
                    h("div", { class: "cfe-grid2" },
                        fieldRow("Corner radius", numInput(s.radius ?? 8, (v) => edit(el, () => { s.radius = v; }), { min: "0" })),
                        fieldRow("Border width", numInput(s.borderWidth ?? 0, (v) => edit(el, () => { s.borderWidth = v; }), { min: "0" }))),
                    colorControl("Border colour", s, "borderColor", null, "#ffffff")
                );
            } else if (el.type === "field") {
                const used = usedFieldWidgets(el.id);
                inspector.append(fieldRow("Which field to show", fieldSelect(el, (f) => !used.has(f.id), (id) => { el.fieldId = id || undefined; commit(true); renderStage(); renderInspector(); }),
                    "Each field can be shown once as a widget. Need more ways to tap it? Add Tap buttons for the same field."));
                if (!field) inspector.append(h("p", { class: "cfe-hint cfe-warn", text: "Pick which field to show here." }));
            } else if (el.type === "image") {
                const file = h("input", { type: "file", accept: "image/png,image/jpeg,image/webp", class: "input-hidden", onchange: async (e) => {
                    const f = e.target.files[0];
                    e.target.value = "";
                    if (!f) return;
                    try { el.src = await uploadImage(f); commit(true); renderStage(); renderInspector(); }
                    catch (err) { toast(err.message || "Upload failed", "error"); }
                } });
                inspector.append(
                    h("label", { class: "btn secondary btn-file" }, icon("upload"), el.src ? "Replace image" : "Upload image", file),
                    fieldRow("Fit", h("select", { onchange: (e) => edit(el, () => { el.fit = e.target.value; }) },
                        [["contain", "Fit inside"], ["cover", "Fill and crop"], ["fill", "Stretch"]].map(([v, l]) => h("option", { value: v, text: l, selected: (el.fit || "contain") === v })))),
                    fieldRow("Corner radius", numInput(s.radius ?? 0, (v) => edit(el, () => { s.radius = v; }), { min: "0" }))
                );
            } else if (el.type === "text") {
                inspector.append(
                    fieldRow("Text", h("textarea", { rows: "3", oninput: (e) => edit(el, () => { el.text = e.target.value; }) }, el.text || "")),
                    ...textStyleControls(s, { fontSize: 24, align: "center", bold: false, color: "#1b1a17" }),
                    colorControl("Background", s, "bg", "bgOpacity", "#000000", 0)
                );
            } else if (el.type === "value") {
                inspector.append(
                    fieldRow("Which field to show", fieldSelect(el, (f) => f.type !== "section", (id) => { el.fieldId = id || undefined; commit(true); renderStage(); })),
                    h("div", { class: "cfe-grid2" },
                        fieldRow("Text before", h("input", { type: "text", value: el.prefix || "", placeholder: "Fuel: ", oninput: (e) => edit(el, () => { el.prefix = e.target.value; }) })),
                        fieldRow("Text after", h("input", { type: "text", value: el.suffix || "", placeholder: " pts", oninput: (e) => edit(el, () => { el.suffix = e.target.value; }) }))),
                    group("How it looks"),
                    ...textStyleControls(s, { fontSize: 22, align: "center", bold: true, color: "#1b1a17" }),
                    colorControl("Background", s, "bg", "bgOpacity", "#ffffff", 0.85),
                    fieldRow("Corner radius", numInput(s.radius ?? 6, (v) => edit(el, () => { s.radius = v; }), { min: "0" }))
                );
            } else {
                inspector.append(
                    colorControl("Fill", s, "bg", "bgOpacity", "#5b4ec7", 0.15),
                    h("div", { class: "cfe-grid2" },
                        fieldRow("Corner radius", numInput(s.radius ?? 8, (v) => edit(el, () => { s.radius = v; }), { min: "0" })),
                        fieldRow("Border width", numInput(s.borderWidth ?? 2, (v) => edit(el, () => { s.borderWidth = v; }), { min: "0" }))),
                    colorControl("Border colour", s, "borderColor", null, "#5b4ec7")
                );
            }

            inspector.append(
                group("Position and size"),
                h("p", { class: "cfe-hint", text: "Easier to drag on the canvas; use these for exact numbers." }),
                h("div", { class: "cfe-grid2" },
                    fieldRow("From left", numInput(el.x, (v) => edit(el, () => { el.x = v || 0; }))),
                    fieldRow("From top", numInput(el.y, (v) => edit(el, () => { el.y = v || 0; }))),
                    fieldRow("Width", numInput(el.w, (v) => edit(el, () => { el.w = Math.max(8, v || 8); }), { min: "8" })),
                    fieldRow("Height", numInput(el.h, (v) => edit(el, () => { el.h = Math.max(8, v || 8); }), { min: "8" }))),
                h("div", { class: "cfe-row" },
                    h("button", { type: "button", class: "btn ghost", title: "Put this on top of things it overlaps", onclick: () => reorder(true) }, icon("flip_to_front"), "To front"),
                    h("button", { type: "button", class: "btn ghost", title: "Put this behind things it overlaps", onclick: () => reorder(false) }, icon("flip_to_back"), "To back")),
                h("div", { class: "cfe-row" },
                    h("button", { type: "button", class: "btn ghost", title: "Ctrl+D", onclick: duplicateSelected }, icon("content_copy"), "Duplicate"),
                    h("button", { type: "button", class: "btn ghost cfe-danger", title: "Delete key", onclick: removeSelected }, icon("delete"), "Remove from canvas")),
                el.type === "field" ? h("p", { class: "cfe-hint", text: "Removing a widget only takes it off the canvas. The field and its data stay." }) : null
            );
        }

        // -------------------------------------------------------------- unplaced fields

        function renderUnplaced() {
            unplacedEl.innerHTML = "";
            if (!layout.pages.length) return;
            const onCanvas = new Set();
            layout.pages.forEach((p) => p.elements.forEach((e) => { if (e.fieldId) onCanvas.add(e.fieldId); }));
            const missing = fields().filter((f) => f.type !== "section" && !onCanvas.has(f.id));
            if (!missing.length) {
                unplacedEl.append(h("p", { class: "cfe-hint", text: "Every field is on the canvas." }));
                return;
            }
            unplacedEl.append(
                h("strong", { text: `Fields not on the canvas yet (${missing.length})` }),
                h("p", { class: "cfe-hint", text: layout.showUnplaced
                    ? "Not on the canvas yet (scouts see these in a list under it). Click one to add its widget to this page:"
                    : "Not on the canvas and hidden from scouts. Required ones will block saving. Click one to add its widget to this page:" }),
                h("div", { class: "cfe-chips" }, missing.map((f) => h("button", { type: "button", class: "cfe-chip", text: `${CF().fieldLabel(f)}${f.required ? " *" : ""}`,
                    onclick: () => {
                        const p = page();
                        if (!p) return;
                        const el = { id: CF().uid("el"), type: "field", fieldId: f.id, x: 20, y: 20, w: Math.min(380, layout.width - 40), h: 120, style: {} };
                        p.elements.push(el);
                        selectedId = el.id;
                        commit(true); render();
                    } })))
            );
        }

        // -------------------------------------------------------------- entry points

        function render() {
            const focusInInspector = inspector.contains(document.activeElement);
            pageIndex = Math.min(pageIndex, Math.max(0, layout.pages.length - 1));
            if (selectedId && !selected()) selectedId = null;
            renderIntro();
            renderSettings();
            renderSteps();
            renderPages();
            renderToolbar();
            renderStage();
            if (!focusInInspector) renderInspector();
            renderUnplaced();
        }

        return {
            render,
            /** Re-reads the layout from the config (after a load, a live edit by someone else, or a raw JSON edit). */
            reload() {
                if (drag) return;
                const next = CF().normalizeLayout(opts.getLayout());
                // Our own edit coming back (or a pending one about to be sent): nothing new to show.
                if (stageEl && (commitPending || JSON.stringify(next) === JSON.stringify(layout))) return;
                layout = next;
                render();
            },
            destroy() {
                if (stopFit) stopFit();
                clearTimeout(commitTimer);
                container.innerHTML = "";
            }
        };
    }

    window.ObsidianCanvasEditor = { create };
})();
