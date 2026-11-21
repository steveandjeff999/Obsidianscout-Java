/*
 * Canvas scouting forms: shared rendering plus the scout-page runtime.
 *
 * A match scouting config may carry an optional `layout` next to its `fields`:
 *
 *   "layout": {
 *     "mode": "canvas",            // "classic" (the normal list form) or "canvas"; scouts see this by default
 *     "width": 1280, "height": 720, // design size in canvas units; the canvas scales to fit the screen
 *     "showUnplaced": true,        // list fields that aren't on the canvas below it
 *     "allowClassicToggle": true,  // scouts may switch to the classic list on their own device
 *     "pages": [{
 *       "id": "p_auto", "name": "Auto", "phase": "auto",
 *       "background": { "color": "#1b1a17", "image": "/api/blobs/...", "fit": "contain" },
 *       "elements": [
 *         { "id": "e1", "type": "button", "x": 40, "y": 60, "w": 160, "h": 90,
 *           "fieldId": "auto_scored", "action": "increment", "amount": 1, "label": "+1", "showValue": true,
 *           "style": { "bg": "#5b4ec7", "bgOpacity": 0.85, "color": "#ffffff", "fontSize": 22, "radius": 8 } },
 *         { "id": "e2", "type": "field", "fieldId": "field_7057", "x": 900, "y": 40, "w": 320, "h": 120 },
 *         { "id": "e3", "type": "image", "src": "/api/blobs/...", "fit": "contain", ... },
 *         { "id": "e4", "type": "text", "text": "Speaker", ... },
 *         { "id": "e5", "type": "shape", ... },
 *         { "id": "e6", "type": "value", "fieldId": "auto_scored", "prefix": "Fuel: ", ... }
 *       ]
 *     }]
 *   }
 *
 * The fields stay the source of truth for what gets collected, saved, validated and analysed. On the scout page the
 * classic inputs are still built; "field" elements move a field's real widget onto the canvas, and buttons drive
 * the hidden inputs and dispatch the same input/change events the classic widgets do. So drafts, review, QR, offline
 * saves, gamepads and points work unchanged, and apps that don't know about layouts keep showing the classic form.
 */
(function () {
    "use strict";

    const DEFAULT_W = 1280;
    const DEFAULT_H = 720;
    const NUMERIC_TYPES = new Set(["counter", "number", "rating"]);
    const BUTTON_FIELD_TYPES = new Set(["counter", "number", "rating", "checkbox", "select", "text", "textarea"]);
    const IMAGE_SRC = /^(\/api\/blobs\/[0-9a-f-]{36}|data:image\/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/=]+)$/;
    const HEX = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i;

    const t = (key, fallback) =>
        (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;
    const localize = (label) =>
        (window.Obsidianscout && typeof Obsidianscout.localize === "function") ? Obsidianscout.localize(label) : (typeof label === "string" ? label : "");

    // ------------------------------------------------------------------ model

    function isObj(v) { return v && typeof v === "object" && !Array.isArray(v); }
    function num(v, dflt, lo, hi) {
        const n = Number(v);
        if (!Number.isFinite(n)) return dflt;
        return Math.min(hi, Math.max(lo, n));
    }
    function uid(prefix) { return `${prefix}_${Math.random().toString(36).slice(2, 9)}`; }
    function safeColor(c, dflt) { return typeof c === "string" && HEX.test(c.trim()) ? c.trim() : dflt; }
    function safeSrc(src) { return typeof src === "string" && IMAGE_SRC.test(src) ? src : ""; }

    function rgba(hex, alpha) {
        const h = safeColor(hex, "#000000").slice(1);
        const full = h.length === 3 ? h.split("").map((c) => c + c).join("") : h;
        const n = parseInt(full, 16);
        const a = num(alpha, 1, 0, 1);
        return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
    }

    function normalizeElement(raw, W, H) {
        const el = { ...raw };
        el.id = typeof raw.id === "string" && raw.id ? raw.id : uid("el");
        el.type = ["button", "field", "text", "image", "shape", "value"].includes(raw.type) ? raw.type : "shape";
        el.w = Math.round(num(raw.w, 160, 8, W));
        el.h = Math.round(num(raw.h, 80, 8, H));
        el.x = Math.round(num(raw.x, 0, -el.w + 8, W - 8));
        el.y = Math.round(num(raw.y, 0, -el.h + 8, H - 8));
        el.style = isObj(raw.style) ? { ...raw.style } : {};
        return el;
    }

    function normalizePage(raw, index, W, H) {
        const page = { ...raw };
        page.id = typeof raw.id === "string" && raw.id ? raw.id : uid("page");
        page.name = typeof raw.name === "string" && raw.name.trim() ? raw.name : `Page ${index + 1}`;
        page.phase = ["auto", "teleop", "endgame", "postmatch"].includes(raw.phase) ? raw.phase : "";
        page.background = isObj(raw.background) ? { ...raw.background } : {};
        page.elements = (Array.isArray(raw.elements) ? raw.elements : []).filter(isObj).map((e) => normalizeElement(e, W, H));
        return page;
    }

    /** A layout with every value in range. Unknown keys are kept so newer layouts survive a round trip. */
    function normalizeLayout(raw) {
        const l = isObj(raw) ? raw : {};
        const width = Math.round(num(l.width, DEFAULT_W, 200, 4000));
        const height = Math.round(num(l.height, DEFAULT_H, 200, 6000));
        const pages = (Array.isArray(l.pages) ? l.pages : []).filter(isObj).map((p, i) => normalizePage(p, i, width, height));
        return {
            ...l,
            mode: l.mode === "canvas" ? "canvas" : "classic",
            width,
            height,
            showUnplaced: l.showUnplaced !== false,
            allowClassicToggle: l.allowClassicToggle !== false,
            pages
        };
    }

    function hasCanvas(config) {
        if (!config || !isObj(config.layout)) return false;
        const layout = normalizeLayout(config.layout);
        return layout.mode === "canvas" && layout.pages.length > 0;
    }

    function fieldLabel(field) {
        if (!field) return "";
        return localize(field.label) || field.id || "";
    }

    /** Actions a button can take on a field of this type; the first is the default. */
    function actionsFor(fieldType) {
        const type = String(fieldType || "").toLowerCase();
        if (NUMERIC_TYPES.has(type)) return ["increment", "decrement", "set"];
        if (type === "checkbox") return ["toggle", "set"];
        if (type === "select") return ["set", "cycle"];
        if (type === "text" || type === "textarea") return ["set"];
        return [];
    }

    function defaultButtonLabel(el, field) {
        if (!field) return t("canvas.button", "Button");
        const action = el.action || actionsFor(field.type)[0];
        const name = fieldLabel(field);
        if (action === "increment") return `+${el.amount || field.step || 1} ${name}`;
        if (action === "decrement") return `-${el.amount || field.step || 1} ${name}`;
        if (action === "toggle") return name;
        if (action === "cycle") return name;
        if (action === "set") {
            if (field.type === "select") {
                const opt = (field.options || []).find((o) => String(o.value) === String(el.value));
                return opt ? localize(opt.label) || opt.value : name;
            }
            if (field.type === "checkbox") return `${name}: ${String(el.value) === "false" ? "No" : "Yes"}`;
            return `${name} = ${el.value ?? ""}`;
        }
        return name;
    }

    // ------------------------------------------------------------------ rendering (shared by editor and runtime)

    function applyBox(node, el) {
        node.style.left = `${el.x}px`;
        node.style.top = `${el.y}px`;
        node.style.width = `${el.w}px`;
        node.style.height = `${el.h}px`;
    }

    function applyBackground(stage, page) {
        const bg = page.background || {};
        stage.style.backgroundColor = safeColor(bg.color, "");
        const src = safeSrc(bg.image);
        stage.style.backgroundImage = src ? `url("${src}")` : "";
        const fit = bg.fit === "cover" ? "cover" : (bg.fit === "fill" ? "100% 100%" : "contain");
        stage.style.backgroundSize = fit;
        stage.style.backgroundPosition = "center";
        stage.style.backgroundRepeat = "no-repeat";
    }

    function styleText(node, s, defaults) {
        node.style.fontSize = `${num(s.fontSize, defaults.fontSize, 6, 200)}px`;
        node.style.color = safeColor(s.color, defaults.color);
        node.style.fontWeight = s.bold === false ? "400" : (s.bold || defaults.bold ? "700" : "400");
        const align = ["left", "center", "right"].includes(s.align) ? s.align : defaults.align;
        node.style.textAlign = align;
        node.style.justifyContent = align === "left" ? "flex-start" : (align === "right" ? "flex-end" : "center");
    }

    function styleFill(node, s, defaults) {
        const bgOpacity = num(s.bgOpacity, defaults.bgOpacity, 0, 1);
        node.style.background = bgOpacity > 0 ? rgba(safeColor(s.bg, defaults.bg), bgOpacity) : "transparent";
        node.style.borderRadius = `${num(s.radius, defaults.radius, 0, 999)}px`;
        const bw = num(s.borderWidth, defaults.borderWidth, 0, 40);
        node.style.border = bw > 0 ? `${bw}px solid ${safeColor(s.borderColor, defaults.borderColor)}` : "none";
    }

    /**
     * Builds the node for one element. ctx.mode is "edit" (admin editor: nothing is interactive) or "run".
     * ctx.fieldsById maps field ids to fields.
     */
    function createElementNode(el, ctx) {
        const field = el.fieldId ? ctx.fieldsById.get(el.fieldId) : null;
        const s = el.style || {};
        let node;

        switch (el.type) {
            case "button": {
                node = document.createElement(ctx.mode === "run" ? "button" : "div");
                if (ctx.mode === "run") node.type = "button";
                node.className = "cf-el cf-button";
                styleFill(node, s, { bg: "#5b4ec7", bgOpacity: 0.9, radius: 8, borderWidth: 0, borderColor: "#ffffff" });
                styleText(node, s, { fontSize: 20, color: "#ffffff", bold: true, align: "center" });
                const label = document.createElement("span");
                label.className = "cf-button-label";
                label.textContent = typeof el.label === "string" && el.label !== "" ? el.label : (el.label === "" ? "" : defaultButtonLabel(el, field));
                node.appendChild(label);
                if (el.showValue) {
                    const badge = document.createElement("span");
                    badge.className = "cf-badge";
                    badge.textContent = "0";
                    node.appendChild(badge);
                }
                if (num(s.bgOpacity, 0.9, 0, 1) === 0) node.classList.add("cf-zone");
                if (!field && el.fieldId) node.classList.add("cf-broken");
                break;
            }
            case "field": {
                node = document.createElement("div");
                node.className = "cf-el cf-field";
                if (ctx.mode === "edit") {
                    node.classList.add("cf-field-placeholder");
                    const title = document.createElement("strong");
                    title.textContent = field ? fieldLabel(field) : (el.fieldId ? `Missing field: ${el.fieldId}` : t("canvas.pick_field", "Pick a field"));
                    const sub = document.createElement("span");
                    sub.textContent = field ? `${field.type} widget` : "";
                    node.append(title, sub);
                    if (!field) node.classList.add("cf-broken");
                }
                break;
            }
            case "text": {
                node = document.createElement("div");
                node.className = "cf-el cf-text";
                styleFill(node, s, { bg: "#000000", bgOpacity: 0, radius: 0, borderWidth: 0, borderColor: "#000000" });
                styleText(node, s, { fontSize: 24, color: "#1b1a17", bold: false, align: "center" });
                node.textContent = typeof el.text === "string" ? el.text : "";
                break;
            }
            case "image": {
                node = document.createElement("div");
                node.className = "cf-el cf-image";
                const src = safeSrc(el.src);
                if (src) {
                    const img = document.createElement("img");
                    img.alt = "";
                    img.draggable = false;
                    img.src = src;
                    img.style.objectFit = el.fit === "cover" ? "cover" : (el.fit === "fill" ? "fill" : "contain");
                    node.appendChild(img);
                } else if (ctx.mode === "edit") {
                    node.classList.add("cf-image-empty");
                    node.textContent = t("canvas.no_image", "No image yet");
                }
                node.style.borderRadius = `${num(s.radius, 0, 0, 999)}px`;
                break;
            }
            case "value": {
                node = document.createElement("div");
                node.className = "cf-el cf-value";
                styleFill(node, s, { bg: "#ffffff", bgOpacity: 0.85, radius: 6, borderWidth: 0, borderColor: "#000000" });
                styleText(node, s, { fontSize: 22, color: "#1b1a17", bold: true, align: "center" });
                const span = document.createElement("span");
                span.className = "cf-value-text";
                span.textContent = `${el.prefix || ""}${ctx.mode === "edit" ? (field ? fieldLabel(field) : "?") : "0"}${el.suffix || ""}`;
                node.appendChild(span);
                if (!field) node.classList.add("cf-broken");
                break;
            }
            default: {
                node = document.createElement("div");
                node.className = "cf-el cf-shape";
                styleFill(node, s, { bg: "#5b4ec7", bgOpacity: 0.15, radius: 8, borderWidth: 2, borderColor: "#5b4ec7" });
            }
        }

        node.dataset.elId = el.id;
        if (el.fieldId) node.dataset.fieldId = el.fieldId;
        applyBox(node, el);
        return node;
    }

    /** A stage (canvas surface) at design size, with every element of the page. */
    function renderStage(layout, page, ctx) {
        const stage = document.createElement("div");
        stage.className = "cf-stage";
        stage.style.width = `${layout.width}px`;
        stage.style.height = `${layout.height}px`;
        applyBackground(stage, page);
        // On the scout page, skip anything tied to a field this team's form doesn't have (e.g. a season default
        // canvas on a team that renamed or removed a field); those fields simply aren't on the canvas.
        const elements = ctx.mode === "run"
            ? page.elements.filter((el) => !el.fieldId || ctx.fieldsById.has(el.fieldId))
            : page.elements;
        elements.forEach((el, i) => {
            const node = createElementNode(el, ctx);
            node.style.zIndex = String(i + 1);
            stage.appendChild(node);
        });
        return stage;
    }

    /** Scales a design-size stage to its viewport's width. Returns a disconnect function. */
    function fitStage(viewport, stage, layout, opts = {}) {
        const apply = () => {
            const avail = viewport.clientWidth || layout.width;
            let scale = avail / layout.width;
            if (opts.maxScale) scale = Math.min(scale, opts.maxScale);
            if (opts.maxHeight) scale = Math.min(scale, opts.maxHeight / layout.height);
            stage.style.transform = `scale(${scale})`;
            viewport.style.height = `${Math.ceil(layout.height * scale)}px`;
            viewport.dataset.scale = String(scale);
            if (opts.onScale) opts.onScale(scale);
        };
        apply();
        if (typeof ResizeObserver === "function") {
            const ro = new ResizeObserver(apply);
            ro.observe(viewport);
            return () => ro.disconnect();
        }
        window.addEventListener("resize", apply);
        return () => window.removeEventListener("resize", apply);
    }

    // ------------------------------------------------------------------ runtime (scout page)

    let active = null;

    function readValue(field, input) {
        if (!input) return null;
        if (field.type === "checkbox") return input.checked;
        if (NUMERIC_TYPES.has(field.type)) return input.value === "" ? null : Number(input.value);
        return input.value;
    }

    function displayValue(field, input) {
        const v = readValue(field, input);
        if (v === null || v === undefined || v === "") return field.type === "counter" ? "0" : "-";
        if (field.type === "checkbox") return v ? t("common.yes", "Yes") : t("common.no", "No");
        if (field.type === "select") {
            const opt = (field.options || []).find((o) => String(o.value) === String(v));
            return opt ? (localize(opt.label) || opt.value) : String(v);
        }
        return String(v);
    }

    function fire(input) {
        input.dispatchEvent(new Event("input", { bubbles: true }));
        input.dispatchEvent(new Event("change", { bubbles: true }));
    }

    function applyAction(el, field, input) {
        if (!field || !input || input.disabled) return false;
        const type = String(field.type || "").toLowerCase();
        const allowed = actionsFor(type);
        const action = allowed.includes(el.action) ? el.action : allowed[0];
        if (!action) return false;

        if (type === "checkbox") {
            input.checked = action === "toggle" ? !input.checked : String(el.value) !== "false";
        } else if (NUMERIC_TYPES.has(type)) {
            const floor = field.min !== undefined && field.min !== null ? Number(field.min) : (type === "rating" ? 1 : 0);
            const ceil = field.max !== undefined && field.max !== null ? Number(field.max) : Number.POSITIVE_INFINITY;
            const cur = Number(input.value) || 0;
            const amount = Math.abs(Number(el.amount) || Number(field.step) || 1);
            let next = cur;
            if (action === "increment") next = cur + amount;
            else if (action === "decrement") next = cur - amount;
            else next = Number(el.value) || 0;
            input.value = String(Math.min(ceil, Math.max(floor, next)));
        } else if (type === "select") {
            const options = Array.from(input.options || []);
            if (!options.length) return false;
            if (action === "cycle") {
                input.selectedIndex = (input.selectedIndex + 1) % options.length;
            } else {
                if (!options.some((o) => o.value === String(el.value))) return false;
                input.value = String(el.value);
            }
        } else {
            input.value = el.value === undefined || el.value === null ? "" : String(el.value);
        }
        fire(input);
        return true;
    }

    /**
     * Shows the canvas form in `host`. opts: { config, form, fieldContainer, onPageChange(page) }.
     * Field widgets are moved out of fieldContainer onto the canvas; unmount() puts them back in order.
     */
    function mount(host, opts) {
        if (active) active.unmount();
        const { config, form, fieldContainer } = opts;
        const layout = normalizeLayout(config.layout);
        const fields = (config.fields || []).filter(isObj);
        const fieldsById = new Map(fields.map((f) => [f.id, f]));
        const ctx = { mode: "run", fieldsById };
        const originalOrder = Array.from(fieldContainer.children);
        const moved = new Set();
        const pageOfField = new Map();
        const elById = new Map();
        const disconnects = [];

        host.innerHTML = "";
        host.classList.remove("hidden");
        host.classList.add("cf-host");

        const tabs = document.createElement("div");
        tabs.className = "tab-row cf-page-tabs";
        if (layout.pages.length < 2) tabs.classList.add("hidden");
        host.appendChild(tabs);

        const viewport = document.createElement("div");
        viewport.className = "cf-viewport";
        host.appendChild(viewport);

        const stages = layout.pages.map((page) => {
            const stage = renderStage(layout, page, ctx);
            stage.classList.add("hidden");
            viewport.appendChild(stage);
            stage.querySelectorAll(".cf-field").forEach((slot) => {
                const fid = slot.dataset.fieldId;
                if (!fid || moved.has(fid)) return;
                const node = fieldContainer.querySelector(`:scope > [data-field-id="${CSS.escape(fid)}"]`);
                if (!node) return;
                node.classList.remove("hidden");
                slot.appendChild(node);
                moved.add(fid);
            });
            page.elements.forEach((el) => {
                elById.set(el.id, el);
                if (el.fieldId && !pageOfField.has(el.fieldId)) pageOfField.set(el.fieldId, page.id);
            });
            return stage;
        });

        // Buttons: one delegated handler for every page.
        viewport.addEventListener("click", (e) => {
            const btn = e.target.closest(".cf-button");
            if (!btn || !viewport.contains(btn) || btn.disabled) return;
            const el = elById.get(btn.dataset.elId);
            if (!el) return;
            const field = fieldsById.get(el.fieldId);
            const input = field ? form.querySelector(`[name="${CSS.escape(field.id)}"]`) : null;
            if (applyAction(el, field, input)) {
                btn.classList.remove("cf-pressed");
                void btn.offsetWidth;
                btn.classList.add("cf-pressed");
                if (window.Obsidianscout && typeof Obsidianscout.triggerHaptic === "function") {
                    try { Obsidianscout.triggerHaptic("light"); } catch (_) { /* ignore */ }
                }
            }
        });

        let currentIndex = 0;
        const tabButtons = layout.pages.map((page, i) => {
            const b = document.createElement("button");
            b.type = "button";
            b.className = "tab";
            b.textContent = page.name;
            b.addEventListener("click", () => showPage(i));
            tabs.appendChild(b);
            return b;
        });

        function showPage(i) {
            currentIndex = Math.max(0, Math.min(layout.pages.length - 1, i));
            stages.forEach((s, idx) => s.classList.toggle("hidden", idx !== currentIndex));
            tabButtons.forEach((b, idx) => b.classList.toggle("active", idx === currentIndex));
            const stage = stages[currentIndex];
            disconnects.splice(0).forEach((d) => d());
            if (stage) disconnects.push(fitStage(viewport, stage, layout));
            if (typeof opts.onPageChange === "function") opts.onPageChange(layout.pages[currentIndex]);
        }

        // Fields that aren't on the canvas stay in the classic list below it (or hidden, if the admin chose so).
        const unplaced = fields.filter((f) => !moved.has(f.id) && f.type !== "section");
        fieldContainer.classList.add("cf-unplaced");
        fieldContainer.classList.toggle("cf-store-hidden", !layout.showUnplaced || unplaced.length === 0);
        fieldContainer.querySelectorAll(":scope > [data-field-id]").forEach((node) => {
            const f = fieldsById.get(node.dataset.fieldId);
            // Section headers only make sense next to the fields they introduce.
            if (f && f.type === "section") node.classList.toggle("hidden", !unplaced.some((u) => (u.phase || "") === (f.phase || "")));
            else node.classList.remove("hidden");
        });

        function refresh() {
            viewport.querySelectorAll(".cf-button, .cf-value").forEach((node) => {
                const el = elById.get(node.dataset.elId);
                const field = el ? fieldsById.get(el.fieldId) : null;
                const input = field ? form.querySelector(`[name="${CSS.escape(field.id)}"]`) : null;
                if (!field || !input) return;
                node.classList.toggle("cf-invalid", input.getAttribute("aria-invalid") === "true");
                if (node.classList.contains("cf-value")) {
                    const span = node.querySelector(".cf-value-text");
                    if (span) span.textContent = `${el.prefix || ""}${displayValue(field, input)}${el.suffix || ""}`;
                    return;
                }
                const badge = node.querySelector(".cf-badge");
                if (badge) badge.textContent = displayValue(field, input);
                const action = actionsFor(field.type).includes(el.action) ? el.action : actionsFor(field.type)[0];
                let on = false;
                if (field.type === "checkbox") on = action === "toggle" ? input.checked : (input.checked === (String(el.value) !== "false"));
                else if (action === "set") on = String(input.value) === String(el.value ?? "");
                node.classList.toggle("cf-active", on);
            });
        }

        const onFormChange = () => refresh();
        form.addEventListener("input", onFormChange);
        form.addEventListener("change", onFormChange);

        showPage(0);
        refresh();

        const controller = {
            layout,
            refresh,
            showPage,
            currentPage: () => layout.pages[currentIndex] || null,
            /** After a failed save: jump to the first problem and mark the buttons bound to invalid fields. */
            revealInvalid() {
                refresh();
                const firstInvalid = fields.find((f) => {
                    const input = form.querySelector(`[name="${CSS.escape(f.id)}"]`);
                    return input && input.getAttribute("aria-invalid") === "true";
                });
                if (!firstInvalid) return;
                const pageId = pageOfField.get(firstInvalid.id);
                if (pageId) {
                    showPage(layout.pages.findIndex((p) => p.id === pageId));
                    host.scrollIntoView({ behavior: "smooth", block: "start" });
                } else {
                    fieldContainer.classList.remove("cf-store-hidden");
                    const node = fieldContainer.querySelector(`:scope > [data-field-id="${CSS.escape(firstInvalid.id)}"]`);
                    if (node) node.scrollIntoView({ behavior: "smooth", block: "center" });
                }
            },
            unmount() {
                form.removeEventListener("input", onFormChange);
                form.removeEventListener("change", onFormChange);
                disconnects.splice(0).forEach((d) => d());
                originalOrder.forEach((node) => fieldContainer.appendChild(node));
                fieldContainer.classList.remove("cf-unplaced", "cf-store-hidden");
                host.innerHTML = "";
                host.classList.add("hidden");
                if (active === controller) active = null;
            }
        };
        active = controller;
        return controller;
    }

    window.ObsidianCanvasForm = {
        DEFAULT_W,
        DEFAULT_H,
        BUTTON_FIELD_TYPES,
        normalizeLayout,
        hasCanvas,
        actionsFor,
        defaultButtonLabel,
        fieldLabel,
        safeSrc,
        safeColor,
        rgba,
        uid,
        createElementNode,
        renderStage,
        applyBackground,
        fitStage,
        mount,
        isActive: () => !!active,
        active: () => active,
        refreshAll: () => { if (active) active.refresh(); }
    };
})();
