/**
 * Service Form-Draft Module - ObsidianScout
 * LocalStorage draft auto-save and restoration popup modal for scouting forms.
 */

import { safeGetItem, safeSetItem, safeRemoveItem } from '../base/storage.js';
import { showToast } from '../components/toast.js';

const DRAFT_PREFIX = "obsidianscout:draft:";

/**
 * Checks if form draft contains meaningful user input (not just empty strings or zero defaults).
 */
export function hasMeaningfulDraftData(data) {
    if (!data || typeof data !== 'object') return false;
    for (const [key, value] of Object.entries(data)) {
        if (value === null || value === undefined) continue;
        if (key.startsWith("_")) {
            if (typeof value === 'string' && value.trim() !== "") return true;
            if (typeof value === 'number' && value > 0) return true;
            continue;
        }
        if (typeof value === 'string' && value.trim() !== "") return true;
        if (typeof value === 'number' && value !== 0) return true;
        if (typeof value === 'boolean' && value === true) return true;
        if (Array.isArray(value) && value.length > 0) return true;
    }
    return false;
}

/**
 * Saves draft data for a given form identifier.
 */
export function saveDraft(formId, data) {
    if (!formId) return;
    if (!hasMeaningfulDraftData(data)) {
        clearDraft(formId);
        return;
    }
    try {
        const payload = {
            data: data,
            savedAt: Date.now()
        };
        safeSetItem(DRAFT_PREFIX + formId, JSON.stringify(payload));
    } catch (e) {
        console.warn("[FormDraft] Failed to save draft for", formId, e);
    }
}

/**
 * Loads draft data for a given form identifier.
 */
export function loadDraft(formId) {
    if (!formId) return null;
    try {
        const raw = safeGetItem(DRAFT_PREFIX + formId);
        if (!raw) return null;
        return JSON.parse(raw);
    } catch (e) {
        console.warn("[FormDraft] Failed to load draft for", formId, e);
        return null;
    }
}

/**
 * Clears draft data for a given form identifier.
 */
export function clearDraft(formId) {
    if (!formId) return;
    safeRemoveItem(DRAFT_PREFIX + formId);
    const existingModal = document.getElementById("obsidian-draft-restore-modal");
    if (existingModal) {
        existingModal.remove();
    }
}

/**
 * Displays a popup modal asking the user if they want to restore an unsaved draft.
 */
export function offerDraftRestore(formId, applyFn) {
    const draft = loadDraft(formId);
    if (!draft || !draft.data || !hasMeaningfulDraftData(draft.data)) {
        clearDraft(formId);
        return;
    }

    const existingModal = document.getElementById("obsidian-draft-restore-modal");
    if (existingModal) existingModal.remove();

    const ageMin = Math.max(1, Math.round((Date.now() - (draft.savedAt || Date.now())) / 60000));
    let timeStr = `${ageMin}m ago`;
    if (ageMin > 60) {
        const hrs = Math.round(ageMin / 60);
        timeStr = `${hrs}h ago`;
    }

    const tNum = draft.data._targetTeamNumber || "";
    const mKey = draft.data._matchKey || "";
    let contextStr = "";
    if (tNum && mKey) {
        contextStr = ` for <strong>Team ${tNum} (Match ${mKey})</strong>`;
    } else if (tNum) {
        contextStr = ` for <strong>Team ${tNum}</strong>`;
    }

    const modalOverlay = document.createElement("div");
    modalOverlay.id = "obsidian-draft-restore-modal";
    modalOverlay.style.cssText = `
        position: fixed; top: 0; left: 0; right: 0; bottom: 0;
        background: rgba(0, 0, 0, 0.75); z-index: 99999;
        display: flex; align-items: center; justify-content: center;
        padding: 16px; backdrop-filter: blur(5px);
    `;

    const modalBox = document.createElement("div");
    modalBox.className = "card";
    modalBox.style.cssText = `
        max-width: 480px; width: 100%; border-radius: 14px;
        background: var(--surface-1, #18181b); color: var(--text-color, #f8fafc);
        border: 1px solid var(--border-color, rgba(255,255,255,0.12));
        padding: 24px; display: flex; flex-direction: column; gap: 16px;
        box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
        animation: fadeIn 0.15s ease-out;
    `;

    modalBox.innerHTML = `
        <div style="display: flex; align-items: center; justify-content: space-between;">
            <div style="display: flex; align-items: center; gap: 10px;">
                <div style="font-size: 1.5rem;">📋</div>
                <div>
                    <h3 style="margin: 0; font-size: 1.15rem; font-weight: 700;">Restore Saved Draft?</h3>
                    <div style="font-size: 0.8rem; color: var(--muted, #94a3b8); margin-top: 2px;">Saved ${timeStr}</div>
                </div>
            </div>
            <button type="button" class="btn-close-draft-modal" style="background: none; border: none; color: var(--muted); font-size: 1.4rem; cursor: pointer; padding: 2px 6px;">✕</button>
        </div>
        <p style="margin: 0; color: var(--text-color, #cbd5e1); font-size: 0.9rem; line-height: 1.5;">
            An unsaved scouting draft was recovered on this device${contextStr}. Would you like to restore your progress or discard the draft?
        </p>
        <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px;">
            <button type="button" class="btn secondary btn-discard-draft" style="padding: 8px 16px; font-size: 0.85rem;">Discard Draft</button>
            <button type="button" class="btn primary btn-restore-draft" style="padding: 8px 18px; font-size: 0.85rem; font-weight: 700;">Restore Draft</button>
        </div>
    `;

    modalOverlay.appendChild(modalBox);
    document.body.appendChild(modalOverlay);

    const closeModal = () => {
        if (modalOverlay.parentElement) {
            modalOverlay.parentElement.removeChild(modalOverlay);
        }
    };

    modalBox.querySelector(".btn-close-draft-modal").addEventListener("click", closeModal);

    modalBox.querySelector(".btn-discard-draft").addEventListener("click", () => {
        clearDraft(formId);
        closeModal();
        if (typeof showToast === 'function') {
            showToast("Draft discarded", "info");
        }
    });

    modalBox.querySelector(".btn-restore-draft").addEventListener("click", () => {
        try {
            applyFn(draft.data);
            closeModal();
            if (typeof showToast === 'function') {
                showToast("Draft restored successfully", "success");
            }
        } catch (e) {
            console.error("[FormDraft] Failed restoring draft:", e);
            closeModal();
        }
    });
}

/**
 * Starts periodic draft auto-save (15s) and binds blur listeners on the form.
 * Returns a teardown function.
 */
export function startDraftAutosave(formId, formElement, getFormData, intervalMs = 15000) {
    if (!formId || typeof getFormData !== 'function') return () => {};

    const doSave = () => {
        try {
            const data = getFormData();
            if (data && hasMeaningfulDraftData(data)) {
                saveDraft(formId, data);
            }
        } catch (e) {
            console.warn("[FormDraft] Autosave error:", e);
        }
    };

    // Periodic timer (15 seconds)
    const timerId = setInterval(doSave, intervalMs);

    // Save on input blur
    const blurHandler = (e) => {
        if (e.target && (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" || e.target.tagName === "TEXTAREA")) {
            doSave();
        }
    };

    if (formElement) {
        formElement.addEventListener("focusout", blurHandler);
    }

    return () => {
        clearInterval(timerId);
        if (formElement) {
            formElement.removeEventListener("focusout", blurHandler);
        }
    };
}

