/**
 * Service Form-Draft Module - ObsidianScout
 * Draft auto-save and restore prompt for scouting forms. Drafts are stored with the offline queue (IndexedDB), so a
 * draft with photos fits, and are saved shortly after every change and whenever the page is hidden or closed.
 */

import * as queueStore from './queue-store.js';
import { showToast } from '../components/toast.js';
import { faIcon } from '../utilities/fa-icons.js';

const MODAL_ID = "obsidian-draft-restore-modal";
const UNDO_ID = "obsidian-draft-undo";
const CHANGE_DEBOUNCE_MS = 700;
/** formId -> resets that form's autosave starting point (after a save clears or keeps the form). */
const rebaselineHooks = new Map();

function escapeHtml(value) {
    return String(value == null ? "" : value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

/**
 * True when a draft holds something the scout entered. Fields starting with "_" (the team and match selection)
 * don't count on their own, so picking a team without filling anything in doesn't leave a draft behind.
 */
export function hasMeaningfulDraftData(data) {
    if (!data || typeof data !== 'object') return false;
    for (const [key, value] of Object.entries(data)) {
        if (value === null || value === undefined) continue;
        if (key.startsWith("_")) continue;
        if (typeof value === 'string' && value.trim() !== "") return true;
        if (typeof value === 'number' && value !== 0) return true;
        if (typeof value === 'boolean' && value === true) return true;
        if (Array.isArray(value) && value.length > 0) return true;
    }
    return false;
}

/** Saves draft data for a form. Resolves once stored. */
export function saveDraft(formId, data) {
    if (!formId) return Promise.resolve(false);
    if (!hasMeaningfulDraftData(data)) {
        return deleteStoredDraft(formId).then(() => false);
    }
    return queueStore.putDraftRecord(formId, data, Date.now()).catch((e) => {
        console.warn("[FormDraft] Failed to save draft for", formId, e);
        return false;
    });
}

/** `{ data, savedAt }` for a form, or null. Complete once the queue store has loaded. */
export function loadDraft(formId) {
    if (!formId) return null;
    try {
        return queueStore.getDraftRecord(formId);
    } catch (e) {
        console.warn("[FormDraft] Failed to load draft for", formId, e);
        return null;
    }
}

function deleteStoredDraft(formId) {
    return queueStore.deleteDraftRecord(formId).catch(() => {});
}

/**
 * Tells autosave the form has just been filled for a new selection (a saved entry loaded, or cleared), so what it
 * shows now isn't the scout's unsaved work.
 */
export function resetDraftBaseline(formId) {
    const hook = rebaselineHooks.get(formId);
    if (hook) hook.now();
}

/** Clears a form's draft (after it was saved, or the form was cleared on purpose). */
export function clearDraft(formId) {
    if (!formId) return;
    deleteStoredDraft(formId);
    const hook = rebaselineHooks.get(formId);
    if (hook) hook.soon();
    const existingModal = document.getElementById(MODAL_ID);
    if (existingModal) existingModal.remove();
}

function ageText(savedAt) {
    const ageMin = Math.max(1, Math.round((Date.now() - (savedAt || Date.now())) / 60000));
    if (ageMin < 60) return `${ageMin} min ago`;
    const hrs = Math.round(ageMin / 60);
    if (hrs < 48) return `${hrs} h ago`;
    return `${Math.round(hrs / 24)} days ago`;
}

function contextText(data) {
    const team = data._targetTeamNumber || "";
    const match = data._matchKey ? String(data._matchKey).split("_").pop().toUpperCase() : "";
    if (team && match) return `Team ${team}, ${match}`;
    if (team) return `Team ${team}`;
    if (match) return match;
    return "";
}

/** A short bar offering to bring back a draft that was just discarded. */
function offerUndoDiscard(formId, draft, applyFn) {
    const old = document.getElementById(UNDO_ID);
    if (old) old.remove();
    const bar = document.createElement("div");
    bar.id = UNDO_ID;
    bar.className = "draft-undo-bar";
    bar.setAttribute("role", "status");
    bar.innerHTML = `<span>Draft discarded.</span> <button type="button" class="btn ghost draft-undo-btn">${faIcon("arrows-rotate", 13)} Undo</button>`;
    document.body.appendChild(bar);
    const timer = setTimeout(() => bar.remove(), 8000);
    bar.querySelector(".draft-undo-btn").addEventListener("click", () => {
        clearTimeout(timer);
        bar.remove();
        queueStore.putDraftRecord(formId, draft.data, draft.savedAt || Date.now());
        runRestore(draft, applyFn);
    });
}

function runRestore(draft, applyFn) {
    try {
        // A form can return a warning, e.g. when the draft's team or match is no longer on the schedule.
        const result = applyFn(draft.data);
        const warning = result && typeof result === "object" ? result.warning : (typeof result === "string" ? result : null);
        if (warning) showToast(warning, "warning");
        else showToast("Draft restored", "success");
    } catch (e) {
        console.error("[FormDraft] Failed restoring draft:", e);
        showToast("Couldn't restore the draft", "error");
    }
}

/**
 * Asks whether to restore a form's unsaved draft. `applyFn(data)` fills the form; it may return
 * `{ warning }` (or a string) when part of the draft couldn't be applied.
 */
export async function offerDraftRestore(formId, applyFn) {
    try {
        await queueStore.queueReady();
    } catch (e) { /* fall through with whatever is loaded */ }
    const draft = loadDraft(formId);
    if (!draft || !draft.data || !hasMeaningfulDraftData(draft.data)) {
        if (draft) deleteStoredDraft(formId);
        return;
    }

    const existingModal = document.getElementById(MODAL_ID);
    if (existingModal) existingModal.remove();

    const context = contextText(draft.data);
    const previousFocus = document.activeElement;

    const overlay = document.createElement("div");
    overlay.id = MODAL_ID;
    overlay.className = "draft-restore-overlay";
    overlay.innerHTML = `
        <div class="card draft-restore-box" role="dialog" aria-modal="true" aria-labelledby="draft-restore-title" aria-describedby="draft-restore-desc">
            <div class="draft-restore-head">
                <div class="draft-restore-icon">${faIcon("clock", 22)}</div>
                <div>
                    <h3 id="draft-restore-title" class="draft-restore-title">Restore unsaved draft?</h3>
                    <div class="draft-restore-age">Saved ${escapeHtml(ageText(draft.savedAt))}</div>
                </div>
                <button type="button" class="draft-restore-close" data-draft-action="close" aria-label="Decide later">${faIcon("xmark", 16)}</button>
            </div>
            <p id="draft-restore-desc" class="draft-restore-desc">
                This device has a scouting entry that was never saved${context ? ` for <strong>${escapeHtml(context)}</strong>` : ""}.
                Restore it to keep going, or discard it.
            </p>
            <div class="draft-restore-actions">
                <button type="button" class="btn secondary" data-draft-action="discard">Discard draft</button>
                <button type="button" class="btn primary" data-draft-action="restore">Restore draft</button>
            </div>
        </div>`;
    document.body.appendChild(overlay);

    const box = overlay.querySelector(".draft-restore-box");
    const close = () => {
        document.removeEventListener("keydown", onKey, true);
        overlay.remove();
        if (previousFocus && typeof previousFocus.focus === "function" && document.contains(previousFocus)) {
            try { previousFocus.focus(); } catch (e) { /* ignore */ }
        }
    };
    const onKey = (e) => {
        if (e.key === "Escape") {
            e.preventDefault();
            close();
        } else if (e.key === "Tab") {
            // Keep focus inside the dialog.
            const focusable = Array.from(box.querySelectorAll("button"));
            if (!focusable.length) return;
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        }
    };
    document.addEventListener("keydown", onKey, true);

    overlay.addEventListener("click", (e) => {
        const btn = e.target.closest("[data-draft-action]");
        if (!btn) return;
        const action = btn.dataset.draftAction;
        if (action === "close") {
            // Closing keeps the draft; it's offered again next time.
            close();
        } else if (action === "discard") {
            deleteStoredDraft(formId);
            close();
            offerUndoDiscard(formId, draft, applyFn);
        } else if (action === "restore") {
            close();
            runRestore(draft, applyFn);
        }
    });

    const restoreBtn = overlay.querySelector('[data-draft-action="restore"]');
    if (restoreBtn) restoreBtn.focus();
}

/**
 * Keeps a form's draft saved: shortly after every change (typing, picking, tapping a counter), every
 * `intervalMs`, and right away when the page is hidden or closed. Returns a teardown function.
 */
export function startDraftAutosave(formId, formElement, getFormData, intervalMs = 15000) {
    if (!formId || typeof getFormData !== 'function') return () => {};

    let debounceTimer = null;
    let lastSaved = null;
    // What the form held when it was loaded, cleared or switched to another team or match. Only answers that differ
    // from it are the scout's own work; defaults (a rating's starting value) and an entry loaded from the server aren't.
    let baseline = null;
    let baselineSelection = null;
    let lastAnswers = null;
    let savedHere = false;

    const split = (data) => {
        const selection = {};
        const answers = {};
        for (const [key, value] of Object.entries(data)) {
            if (key.startsWith("_")) selection[key] = value;
            else answers[key] = value;
        }
        return { selection: JSON.stringify(selection), answers };
    };
    const differs = (a, b) => {
        if (!a || !b) return true;
        const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
        for (const key of keys) {
            if (JSON.stringify(a[key] ?? null) !== JSON.stringify(b[key] ?? null)) return true;
        }
        return false;
    };
    const differsFromBaseline = (answers) => !baseline || differs(answers, baseline);

    const doSave = () => {
        clearTimeout(debounceTimer);
        debounceTimer = null;
        try {
            const data = getFormData();
            if (!data) return;
            const { selection, answers } = split(data);
            const answersMoved = differs(answers, lastAnswers);
            lastAnswers = answers;
            if (selection !== baselineSelection) {
                baselineSelection = selection;
                if (!answersMoved) {
                    // Only the team or match changed: start over from what the form shows now.
                    baseline = answers;
                    lastSaved = null;
                    return;
                }
                // The selection and the answers changed together, and the form didn't say it reloaded: keep the
                // answers as unsaved work rather than risk dropping them.
            }
            const serialized = JSON.stringify(data);
            if (serialized === lastSaved) return;
            lastSaved = serialized;
            if (hasMeaningfulDraftData(data) && differsFromBaseline(answers)) {
                saveDraft(formId, data);
                savedHere = true;
            } else if (savedHere) {
                // The scout cleared or undid everything they entered: no draft left to offer.
                deleteStoredDraft(formId);
                savedHere = false;
            }
        } catch (e) {
            console.warn("[FormDraft] Autosave error:", e);
        }
    };
    // The form as it is now is the starting point.
    const rebaseline = () => {
        try {
            const data = getFormData();
            if (!data) return;
            const parts = split(data);
            baselineSelection = parts.selection;
            baseline = parts.answers;
            lastAnswers = parts.answers;
            lastSaved = null;
        } catch (e) { /* ignore */ }
    };
    rebaseline();
    rebaselineHooks.set(formId, { now: rebaseline, soon: () => setTimeout(rebaseline, CHANGE_DEBOUNCE_MS) });
    const soon = () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(doSave, CHANGE_DEBOUNCE_MS);
    };

    const timerId = setInterval(doSave, intervalMs);
    const onHidden = () => {
        if (document.visibilityState === "hidden") doSave();
    };

    if (formElement) {
        formElement.addEventListener("input", soon);
        formElement.addEventListener("change", soon);
        // Counter and toggle buttons change values without input events.
        formElement.addEventListener("click", soon);
        formElement.addEventListener("focusout", soon);
    }
    document.addEventListener("visibilitychange", onHidden);
    window.addEventListener("pagehide", doSave);

    return () => {
        rebaselineHooks.delete(formId);
        clearInterval(timerId);
        clearTimeout(debounceTimer);
        if (formElement) {
            formElement.removeEventListener("input", soon);
            formElement.removeEventListener("change", soon);
            formElement.removeEventListener("click", soon);
            formElement.removeEventListener("focusout", soon);
        }
        document.removeEventListener("visibilitychange", onHidden);
        window.removeEventListener("pagehide", doSave);
    };
}

/** Every config field's current value in a form, for a draft. */
export function collectDraftFields(form, fields) {
    const data = {};
    if (!form) return data;
    (fields || []).forEach((f) => {
        if (!f || !f.id || f.type === "section") return;
        const el = form.elements[f.id];
        if (!el) return;
        if (el.type === "checkbox") {
            data[f.id] = el.checked;
        } else if (el.type === "radio" || (typeof RadioNodeList !== "undefined" && el instanceof RadioNodeList)) {
            const checked = form.querySelector(`input[name="${CSS.escape(f.id)}"]:checked`);
            if (checked) data[f.id] = checked.value;
        } else if (el.value !== undefined && el.value !== "") {
            data[f.id] = el.value;
        }
    });
    return data;
}

/** Puts a draft's values back into a form's config fields. */
export function applyDraftFields(form, fields, data) {
    if (!form || !data) return;
    (fields || []).forEach((f) => {
        if (!f || !f.id || data[f.id] === undefined || data[f.id] === null) return;
        const val = data[f.id];
        const name = CSS.escape(f.id);
        if (f.type === "checkbox") {
            const el = form.querySelector(`input[name="${name}"]`);
            if (el) el.checked = Boolean(val);
        } else if (f.type === "radio") {
            const radio = form.querySelector(`input[name="${name}"][value="${CSS.escape(String(val))}"]`);
            if (radio) radio.checked = true;
        } else if (f.type === "image" || f.type === "image_upload" || f.type === "photo") {
            const input = form.querySelector(`[name="${name}"]`);
            const container = input ? input.closest(".image-upload-field-container") : null;
            if (container && typeof container.updateImage === "function") container.updateImage(val);
        } else {
            const el = form.elements[f.id] || form.querySelector(`[name="${name}"]`);
            if (el) {
                el.value = val;
                if (f.type === "rating") el.dispatchEvent(new Event("input", { bubbles: true }));
            }
        }
    });
}
