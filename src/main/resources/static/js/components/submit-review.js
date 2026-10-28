/**
 * Component Submit Review Module - ObsidianScout
 * Pre-submit overview modal for the scouting forms: shows every answer so the
 * scout can check it, then either goes back to edit or submits from the modal.
 */

import { safeGetItem } from '../base/storage.js';
import { escapeHtml } from '../utilities/helpers.js';

export const REVIEW_BEFORE_SUBMIT_KEY = "obsidianscout:review_before_submit";

const NON_INPUT_TYPES = new Set(["section", "header", "divider", "static_text", "label", "info"]);

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function")
        ? window.Obsidianscout.t(key, fallback)
        : fallback;
}

function localize(label) {
    return (window.Obsidianscout && typeof window.Obsidianscout.localize === "function")
        ? window.Obsidianscout.localize(label)
        : label;
}

export function isReviewBeforeSubmitEnabled() {
    return (safeGetItem(REVIEW_BEFORE_SUBMIT_KEY) || "enabled") === "enabled";
}

/**
 * Formats one answer the way a scout reads it: option labels instead of
 * option values, Yes/No for checkboxes. Blank answers (zero, unset, empty) are
 * flagged so the modal can dim them and filled-in values stand out.
 */
export function reviewRowForField(field, value) {
    const type = String(field.type || "").toLowerCase();
    const label = localize(field.label || field.id);
    const options = Array.isArray(field.options) ? field.options : [];
    const optionLabel = (v) => {
        const s = v === null || v === undefined ? "" : String(v);
        const opt = options.find((o) => String(o.value ?? o) === s);
        return opt ? localize(opt.label ?? opt.value ?? s) : s;
    };

    switch (type) {
        case "checkbox":
        case "boolean":
        case "toggle": {
            const on = value === true || value === "true";
            return { label, value: on ? t("review.yes", "Yes") : t("review.no", "No"), blank: !on };
        }
        case "counter":
        case "number":
        case "stepper":
        case "slider":
        case "range": {
            const n = value === null || value === undefined || value === "" ? null : Number(value);
            return { label, value: n === null || Number.isNaN(n) ? "-" : String(n), blank: !n };
        }
        case "rating":
        case "stars": {
            const n = value === null || value === undefined || value === "" ? null : Number(value);
            if (n === null || Number.isNaN(n)) return { label, value: "-", blank: true };
            return { label, value: `${n} / ${field.max ?? 5}`, blank: false };
        }
        case "select":
        case "dropdown":
        case "radio":
        case "choice": {
            const text = optionLabel(value);
            return { label, value: text || "-", blank: !text };
        }
        case "multiselect":
        case "multi-select": {
            const list = Array.isArray(value) ? value : [];
            return { label, value: list.length ? list.map(optionLabel).join(", ") : t("review.none", "None"), blank: !list.length };
        }
        case "image":
        case "image_upload":
        case "photo": {
            const count = Array.isArray(value) ? value.length : (value ? 1 : 0);
            const text = count === 0
                ? t("review.no_photo", "No photo")
                : (count === 1 ? t("review.one_photo", "1 photo") : `${count} ${t("review.photos", "photos")}`);
            return { label, value: text, blank: count === 0 };
        }
        default: {
            const text = value === null || value === undefined ? "" : String(value).trim();
            return { label, value: text || "-", blank: !text };
        }
    }
}

/**
 * Groups the config's fields into review sections. With groupOf (e.g. match
 * phase) fields are grouped by its result; otherwise the config's own
 * section/header fields start each group.
 */
export function buildReviewSections(fields, data, { groupOf = null, groupTitle = null, defaultTitle = null } = {}) {
    const groups = new Map();
    let currentHeader = "";
    for (const field of fields || []) {
        const type = String(field.type || "").toLowerCase();
        if (type === "section" || type === "header") currentHeader = localize(field.label || "");
        if (NON_INPUT_TYPES.has(type)) continue;
        const group = groupOf ? groupOf(field) : currentHeader;
        if (!groups.has(group)) groups.set(group, []);
        groups.get(group).push(reviewRowForField(field, data ? data[field.id] : undefined));
    }
    return Array.from(groups, ([group, rows]) => ({
        title: group ? (groupTitle ? groupTitle(group) : group) : (defaultTitle || t("review.responses", "Responses")),
        rows
    }));
}

export function phaseTitle(phase) {
    const titles = { auto: "Auto", teleop: "Teleop", endgame: "Endgame", postmatch: "Post-Match" };
    return t(`review.phase.${phase}`, titles[phase] || phase);
}

/**
 * Confirm-button label for what happens after the review: "auto" (save),
 * "local" (save on device), "json" (download) or "qr" (show QR code).
 */
export function reviewActionLabel(action) {
    switch (action) {
        case "local": return t("scout.save_offline", "Save on device, upload later");
        case "json": return t("review.download_json", "Download JSON");
        case "qr": return t("review.show_qr", "Show QR Code");
        default: return t("review.submit", "Submit");
    }
}

/**
 * Opens the review modal. Resolves true when the scout presses the confirm
 * button, false when they go back to edit (Edit, close button, Escape or backdrop).
 * `action` picks the confirm label (see reviewActionLabel); `submitLabel` overrides it.
 */
export function showSubmitReview({ title, chips = [], sections = [], submitLabel = null, action = "auto" } = {}) {
    return new Promise((resolve) => {
        const backdrop = document.createElement("div");
        backdrop.className = "modal-backdrop submit-review-backdrop";
        backdrop.setAttribute("role", "dialog");
        backdrop.setAttribute("aria-modal", "true");
        backdrop.setAttribute("aria-labelledby", "submit-review-title");

        const chipsHtml = chips.length
            ? `<div class="submit-review-chips">${chips.map((c) => `
                <span class="submit-review-chip"><span class="submit-review-chip-label">${escapeHtml(c.label)}</span>${escapeHtml(String(c.value))}</span>`).join("")}
               </div>`
            : "";

        const sectionsHtml = sections.map((section) => `
            <section class="submit-review-section">
                <h4 class="submit-review-section-title">${escapeHtml(section.title)}</h4>
                <dl class="submit-review-rows">
                    ${section.rows.map((row) => `
                        <div class="submit-review-row${row.blank ? " is-blank" : ""}">
                            <dt>${escapeHtml(row.label)}</dt>
                            <dd>${escapeHtml(row.value)}</dd>
                        </div>`).join("")}
                </dl>
            </section>`).join("");

        backdrop.innerHTML = `
            <div class="modal-container submit-review-modal">
                <div class="modal-header">
                    <h3 class="modal-title" id="submit-review-title">${escapeHtml(title || t("review.title", "Review Entry"))}</h3>
                    <button type="button" class="modal-close" data-review="cancel" aria-label="${escapeHtml(t("review.edit", "Edit"))}">&times;</button>
                </div>
                <p class="submit-review-subtitle">${escapeHtml(t("review.subtitle", "Check your entry before it is saved."))}</p>
                ${chipsHtml}
                <div class="submit-review-body">${sectionsHtml}</div>
                <div class="modal-footer">
                    <button type="button" class="btn secondary" data-review="cancel">${escapeHtml(t("review.edit", "Edit"))}</button>
                    <button type="button" class="btn" data-review="confirm">${escapeHtml(submitLabel || reviewActionLabel(action))}</button>
                </div>
            </div>`;

        const previouslyFocused = document.activeElement;
        let settled = false;
        const finish = (confirmed) => {
            if (settled) return;
            settled = true;
            document.removeEventListener("keydown", onKey, true);
            backdrop.classList.remove("show");
            backdrop.classList.add("is-closing");
            setTimeout(() => backdrop.remove(), 250);
            if (previouslyFocused && typeof previouslyFocused.focus === "function") {
                try { previouslyFocused.focus({ preventScroll: true }); } catch (e) { /* element gone */ }
            }
            resolve(confirmed);
        };
        const onKey = (e) => {
            if (e.key === "Escape") {
                e.preventDefault();
                e.stopPropagation();
                finish(false);
            }
        };

        backdrop.addEventListener("click", (e) => {
            if (e.target === backdrop) return finish(false);
            const action = e.target.closest("[data-review]");
            if (action) finish(action.dataset.review === "confirm");
        });
        document.addEventListener("keydown", onKey, true);

        document.body.appendChild(backdrop);
        requestAnimationFrame(() => {
            backdrop.classList.add("show");
            const confirmBtn = backdrop.querySelector('[data-review="confirm"]');
            if (confirmBtn) confirmBtn.focus({ preventScroll: true });
        });
    });
}

/** True while a review modal is open; a second Submit press (e.g. gamepad) confirms it. */
export function confirmOpenSubmitReview() {
    const btn = document.querySelector('.submit-review-backdrop:not(.is-closing) [data-review="confirm"]');
    if (!btn) return false;
    btn.click();
    return true;
}
