/**
 * Service Form-Validation Module - ObsidianScout
 * Checks a scouting form before it's saved and shows every problem at once: each field that needs attention is
 * marked with the reason, each phase tab shows how many it has, and a summary at the top lists them.
 * Markers clear as soon as the scout fixes a field.
 */

import { faIcon } from '../utilities/fa-icons.js';

const SUMMARY_CLASS = "form-error-summary";
const wired = new WeakSet();

function wrapperFor(input, form) {
    if (!input) return null;
    let el = input;
    // The field's own block: a direct child of the fields container, or the nearest .field.
    while (el && el.parentElement && el.parentElement !== form) {
        if (el.parentElement.id === "form-fields" || el.parentElement.classList.contains("form-fields")) return el;
        if (el.classList && el.classList.contains("field")) return el;
        el = el.parentElement;
    }
    return input.closest(".field") || input.parentElement;
}

function tabFor(phase) {
    if (!phase) return null;
    return document.querySelector(`#scouting-tabs .tab[data-tab="${CSS.escape(phase)}"]`) ||
        document.querySelector(`.tab[data-tab="${CSS.escape(phase)}"]`);
}

/** Removes every marker this module added to the form. */
export function clearValidation(form) {
    if (!form) return;
    form.querySelectorAll(".field-invalid").forEach((el) => el.classList.remove("field-invalid"));
    form.querySelectorAll(".field-error-text").forEach((el) => el.remove());
    form.querySelectorAll("[aria-invalid='true']").forEach((el) => el.removeAttribute("aria-invalid"));
    document.querySelectorAll(".phase-error-count").forEach((el) => el.remove());
    const summary = form.parentElement && form.parentElement.querySelector(`.${SUMMARY_CLASS}`);
    if (summary) summary.remove();
    form.querySelectorAll(`.${SUMMARY_CLASS}`).forEach((el) => el.remove());
}

function summaryNames(labels) {
    const list = labels.filter(Boolean);
    return list.slice(0, 4).join(", ") + (list.length > 4 ? `, and ${list.length - 4} more` : "");
}

function refreshCounts(form) {
    document.querySelectorAll(".phase-error-count").forEach((el) => el.remove());
    const byPhase = {};
    form.querySelectorAll(".field-invalid").forEach((wrap) => {
        const phase = wrap.dataset.errorPhase;
        if (phase) byPhase[phase] = (byPhase[phase] || 0) + 1;
    });
    for (const [phase, count] of Object.entries(byPhase)) {
        const tab = tabFor(phase);
        if (!tab) continue;
        const badge = document.createElement("span");
        badge.className = "phase-error-count";
        badge.textContent = String(count);
        badge.setAttribute("aria-label", `${count} ${count === 1 ? "field needs" : "fields need"} attention`);
        tab.appendChild(badge);
    }
    const remaining = form.querySelectorAll(".field-invalid").length;
    const summary = form.querySelector(`.${SUMMARY_CLASS}`);
    if (summary && remaining === 0) summary.remove();
    else if (summary) {
        const text = summary.querySelector(".form-error-summary-text");
        if (text) text.textContent = `${remaining} ${remaining === 1 ? "field needs" : "fields need"} attention before this can be saved.`;
        const names = summary.querySelector(".form-error-summary-names");
        if (names) names.textContent = summaryNames(Array.from(form.querySelectorAll(".field-invalid")).map((w) => w.dataset.errorLabel || ""));
    }
}

function wireClearing(form) {
    if (wired.has(form)) return;
    wired.add(form);
    const onEdit = (e) => {
        const wrap = e.target && e.target.closest && e.target.closest(".field-invalid");
        if (!wrap || !form.contains(wrap)) return;
        wrap.classList.remove("field-invalid");
        wrap.querySelectorAll(".field-error-text").forEach((el) => el.remove());
        wrap.querySelectorAll("[aria-invalid='true']").forEach((el) => el.removeAttribute("aria-invalid"));
        refreshCounts(form);
    };
    form.addEventListener("input", onEdit);
    form.addEventListener("change", onEdit);
    // Counter buttons.
    form.addEventListener("click", (e) => { if (e.target.closest("button")) setTimeout(() => onEdit(e), 0); });
}

/**
 * Reads and checks every config field. Returns the payload, or null after marking each problem.
 * opts.readValue(field, input) reads one value; opts.getPhase(field) and opts.switchTab(phase) are optional and
 * used to jump to the first problem; opts.localize(label) translates labels.
 */
export function collectValidatedPayload(fields, form, opts = {}) {
    const { readValue, getPhase = null, switchTab = null } = opts;
    const localize = opts.localize || ((label) => label);
    clearValidation(form);
    const payload = {};
    const errors = [];

    for (const field of fields || []) {
        const input = form.querySelector(`[name='${CSS.escape(field.id)}']`);
        if (!input) continue;
        const value = readValue(field, input);
        const label = localize(field.label) || field.id;
        let message = null;

        if (field.required && (value === null || value === undefined || value === "")) {
            message = "Required";
        } else if (value !== null && value !== undefined && value !== "" &&
            (field.type === "number" || field.type === "counter" || field.type === "rating")) {
            const numVal = Number(value);
            if (field.min !== null && field.min !== undefined && numVal < field.min) message = `Must be at least ${field.min}`;
            else if (field.max !== null && field.max !== undefined && numVal > field.max) message = `Must be at most ${field.max}`;
        }

        if (message) {
            errors.push({ field, input, label, message, phase: getPhase ? getPhase(field) : null });
        } else if (value !== null && value !== undefined && value !== "") {
            payload[field.id] = value;
        }
    }

    if (!errors.length) return payload;

    wireClearing(form);
    for (const err of errors) {
        const wrap = wrapperFor(err.input, form);
        if (!wrap) continue;
        wrap.classList.add("field-invalid");
        wrap.dataset.errorLabel = err.label;
        if (err.phase) wrap.dataset.errorPhase = err.phase;
        const note = document.createElement("div");
        note.className = "field-error-text";
        note.id = `err-${err.field.id}`;
        note.innerHTML = `${faIcon("circle-xmark", 13)}<span></span>`;
        note.querySelector("span").textContent = err.message;
        wrap.appendChild(note);
        err.input.setAttribute("aria-invalid", "true");
        const describedBy = (err.input.getAttribute("aria-describedby") || "").split(" ").filter((id) => id && !id.startsWith("err-"));
        describedBy.push(note.id);
        err.input.setAttribute("aria-describedby", describedBy.join(" "));
    }

    const summary = document.createElement("div");
    summary.className = SUMMARY_CLASS;
    summary.setAttribute("role", "alert");
    const names = summaryNames(errors.map((e) => e.label));
    summary.innerHTML = `${faIcon("triangle-exclamation", 15)}<div><div class="form-error-summary-text"></div><div class="form-error-summary-names"></div></div>`;
    summary.querySelector(".form-error-summary-names").textContent = names;
    form.insertBefore(summary, form.firstChild);
    refreshCounts(form);

    const first = errors[0];
    if (first.phase && typeof switchTab === "function") {
        try { switchTab(first.phase); } catch (e) { /* ignore */ }
    }
    const target = wrapperFor(first.input, form) || first.input;
    try {
        target.scrollIntoView({ behavior: "smooth", block: "center" });
        if (typeof first.input.focus === "function" && first.input.type !== "hidden") first.input.focus({ preventScroll: true });
    } catch (e) { /* ignore */ }
    return null;
}
