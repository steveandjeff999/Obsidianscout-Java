/**
 * Component Edit Entry Modal Module - ObsidianScout
 * Allows Admin and SuperAdmin users to edit scouting entries directly from data pages.
 *
 * Saving writes only what the user changed. Every other value goes back exactly as it was stored, unset fields stay
 * unset, and values this form can't show (lists, objects, photos) are never touched.
 */

import { request } from '../base/http.js';
import { showToast } from './toast.js';
import { localize } from '../base/i18n.js';

const LAYOUT_TYPES = new Set(['section', 'divider', 'header']);
const DISPLAY_ONLY_TYPES = new Set(['static_text', 'label', 'info']);
const CHECKBOX_TYPES = new Set(['checkbox', 'toggle', 'boolean']);
const NUMBER_TYPES = new Set(['counter', 'number', 'stepper', 'slider', 'range', 'rating', 'stars']);
const SELECT_TYPES = new Set(['select', 'dropdown', 'choice', 'radio']);
const TEXTAREA_TYPES = new Set(['textarea', 'notes']);
const IMAGE_TYPES = new Set(['image', 'image_upload', 'photo']);
const RESERVED_KEYS = new Set(['eventKey', 'matchKey', 'matchNumber', 'compLevel', 'targetTeamNumber', 'id', 'clientId', 'isPrescout', 'timestamp']);

const LABEL_STYLE = 'font-size: 0.85rem; font-weight: 600; color: var(--muted);';
const FIELD_GROUP_STYLE = 'display: flex; flex-direction: column; gap: 6px;';
const NOTE_STYLE = 'margin: 0; font-size: 0.82rem; color: var(--muted);';

const isUnset = (value) => value === undefined || value === null;
const isScalar = (value) => isUnset(value) || ['string', 'number', 'boolean'].includes(typeof value);

function entryApiPath(entry, type) {
    const prefix = (type === 'pit') ? 'pit-scouting' : ((type === 'qual' || type === 'qualitative') ? 'qual-scouting' : 'scouting');
    let cleanId = String(entry.originalId || entry.id || '');
    ['match-', 'pit-', 'qual-', 'qualitative-'].forEach(p => {
        if (cleanId.toLowerCase().startsWith(p)) cleanId = cleanId.substring(p.length);
    });
    return `/api/${prefix}/${cleanId}`;
}

function entryTypeLabel(type) {
    return (type === 'pit') ? 'Pit' : ((type === 'qual' || type === 'qualitative') ? 'Qualitative' : 'Match');
}

function buildGroup(labelText) {
    const group = document.createElement('div');
    group.style.cssText = FIELD_GROUP_STYLE;
    const label = document.createElement('label');
    label.style.cssText = LABEL_STYLE;
    label.textContent = labelText;
    group.appendChild(label);
    return group;
}

function buildSectionHeading(text) {
    const heading = document.createElement('div');
    heading.style.cssText = 'margin-top: 10px; padding-bottom: 6px; border-bottom: 1px solid var(--border-color);';
    const title = document.createElement('h4');
    title.style.cssText = 'margin: 0; font-size: 0.95rem; font-weight: 700; color: var(--text-color);';
    title.textContent = text;
    heading.appendChild(title);
    return heading;
}

function buildCheckbox(labelText, name, value, controls) {
    const group = buildGroup(labelText);
    group.style.cssText = `display: flex; flex-direction: row; align-items: center; justify-content: space-between;
        padding: 8px 12px; border-radius: 8px; border: 1px solid var(--border-color);`;
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.name = name;
    checkbox.checked = (value === true || value === 'true' || value === 1);
    checkbox.style.cssText = 'width: 18px; height: 18px; cursor: pointer; accent-color: var(--primary);';
    group.appendChild(checkbox);
    controls.push({ key: name, el: checkbox, kind: 'checkbox' });
    return group;
}

function buildNumber(labelText, name, value, field, controls) {
    const group = buildGroup(labelText);
    const input = document.createElement('input');
    input.type = 'number';
    input.name = name;
    input.placeholder = 'Not set';
    if (!isUnset(value)) input.value = value;
    if (!isUnset(field.min)) input.min = field.min;
    if (!isUnset(field.max)) input.max = field.max;
    input.step = isUnset(field.step) ? 'any' : field.step;
    input.style.width = '100%';
    group.appendChild(input);
    controls.push({ key: name, el: input, kind: 'number' });
    return group;
}

function buildSelect(labelText, name, value, field, controls) {
    const group = buildGroup(labelText);
    const select = document.createElement('select');
    select.name = name;
    select.style.width = '100%';

    // Option values may be numbers; keep the original so a changed choice is stored with the same type.
    const optionValues = new Map();
    const addOption = (optionValue, optionLabel) => {
        const option = document.createElement('option');
        option.value = String(optionValue);
        option.textContent = optionLabel;
        select.appendChild(option);
        optionValues.set(option.value, optionValue);
    };

    const options = (field.options || []).map(opt => {
        const isObject = opt && typeof opt === 'object';
        const optionValue = isObject ? (opt.value !== undefined ? opt.value : opt.label) : opt;
        return { value: optionValue, label: isObject ? (opt.label || opt.value) : opt };
    });
    const inOptions = options.some(opt => String(opt.value) === String(value));

    if (isUnset(value)) {
        addOption('', 'Not set');
    } else if (!inOptions) {
        // The form changed since this was scouted: show the stored answer rather than silently picking another.
        addOption(value, String(value));
    }
    options.forEach(opt => addOption(opt.value, localize(opt.label)));
    select.value = isUnset(value) ? '' : String(value);

    group.appendChild(select);
    controls.push({ key: name, el: select, kind: 'select', optionValues });
    return group;
}

function buildTextarea(labelText, name, value, controls) {
    const group = buildGroup(labelText);
    const textarea = document.createElement('textarea');
    textarea.name = name;
    textarea.value = isUnset(value) ? '' : value;
    textarea.rows = 3;
    textarea.style.cssText = 'width: 100%; resize: vertical;';
    group.appendChild(textarea);
    controls.push({ key: name, el: textarea, kind: 'text' });
    return group;
}

function buildText(labelText, name, value, controls) {
    const group = buildGroup(labelText);
    const input = document.createElement('input');
    input.type = 'text';
    input.name = name;
    input.value = isUnset(value) ? '' : value;
    input.style.width = '100%';
    group.appendChild(input);
    controls.push({ key: name, el: input, kind: 'text' });
    return group;
}

function buildImagePreview(labelText, value) {
    const group = buildGroup(labelText);
    if (typeof value === 'string' && (value.startsWith('data:image/') || value.startsWith('/api/blobs/'))) {
        const thumb = document.createElement('img');
        thumb.src = value;
        thumb.alt = labelText;
        thumb.style.cssText = 'max-width: 140px; max-height: 100px; object-fit: contain; border-radius: 6px; border: 1px solid var(--border-color);';
        group.appendChild(thumb);
    } else {
        const note = document.createElement('p');
        note.style.cssText = NOTE_STYLE;
        note.textContent = isUnset(value) ? 'No photo' : 'Photo kept as it is';
        group.appendChild(note);
    }
    return group;
}

function buildReadOnlyNote(labelText) {
    const group = buildGroup(labelText);
    const note = document.createElement('p');
    note.style.cssText = NOTE_STYLE;
    note.textContent = "This answer can't be edited here and will be kept as it is.";
    group.appendChild(note);
    return group;
}

function buildFieldControl(field, value, controls) {
    const fieldType = (field.type || 'text').toLowerCase();
    const label = localize(field.label) || field.id;

    if (IMAGE_TYPES.has(fieldType)) return buildImagePreview(label, value);
    if (!isScalar(value)) return buildReadOnlyNote(label);
    if (CHECKBOX_TYPES.has(fieldType)) return buildCheckbox(label, field.id, value, controls);
    if (NUMBER_TYPES.has(fieldType)) return buildNumber(label, field.id, value, field, controls);
    if (SELECT_TYPES.has(fieldType)) return buildSelect(label, field.id, value, field, controls);
    if (TEXTAREA_TYPES.has(fieldType)) return buildTextarea(label, field.id, value, controls);
    return buildText(label, field.id, value, controls);
}

/** A stored value with no form field, shown with a control that keeps its type. Returns null when it can't be edited. */
function buildExtraControl(key, value, controls) {
    if (typeof value === 'boolean') return buildCheckbox(key, key, value, controls);
    if (typeof value === 'number') return buildNumber(key, key, value, {}, controls);
    if (typeof value === 'string' && !value.startsWith('data:')) return buildText(key, key, value, controls);
    return null;
}

const readControl = (control) => (control.kind === 'checkbox' ? control.el.checked : control.el.value);

function applyControl(control, data) {
    const raw = readControl(control);
    if (raw === control.initial) return;

    if (control.kind === 'checkbox') {
        data[control.key] = raw;
    } else if (control.kind === 'number') {
        if (raw === '') delete data[control.key];
        else data[control.key] = Number(raw);
    } else if (control.kind === 'select') {
        if (raw === '') delete data[control.key];
        else data[control.key] = control.optionValues.get(raw);
    } else {
        data[control.key] = raw;
    }
}

export function openEditEntryModal(options) {
    const {
        type = 'match',
        fields = [],
        entry = {},
        onSaved = () => {}
    } = options;

    if (!entry) return;

    const typeLabel = entryTypeLabel(type);
    const teamNum = entry.targetTeamNumber || (entry.data && entry.data.targetTeamNumber) || 'Unknown';
    const matchNum = entry.matchNumber || (entry.data && entry.data.matchNumber) || null;

    // Remove existing modal if open
    const existing = document.getElementById('obsidian-edit-entry-modal');
    if (existing) existing.remove();

    const modalOverlay = document.createElement('div');
    modalOverlay.id = 'obsidian-edit-entry-modal';
    modalOverlay.style.cssText = `
        position: fixed; top: 0; left: 0; right: 0; bottom: 0;
        background: rgba(0, 0, 0, 0.75); z-index: 10000;
        display: flex; align-items: center; justify-content: center;
        padding: 16px; backdrop-filter: blur(4px);
    `;

    const modalContent = document.createElement('div');
    modalContent.style.cssText = `
        background: var(--card-bg); color: var(--text-color);
        border: 1px solid var(--border-color); border-radius: 12px;
        max-width: 650px; width: 100%; max-height: 90vh; display: flex; flex-direction: column;
        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.5);
        overflow: hidden;
    `;

    // Clone current data
    const currentData = Object.assign({}, entry.data || {});

    // Header
    const header = document.createElement('div');
    header.style.cssText = `
        padding: 16px 20px; border-bottom: 1px solid var(--border-color);
        display: flex; align-items: center; justify-content: space-between;
    `;
    header.innerHTML = `
        <div>
            <h3 style="margin: 0; font-size: 1.15rem; font-weight: 700; color: var(--primary); display: flex; align-items: center; gap: 8px;">
                <span class="material-symbols-outlined" style="font-size: 1.25rem;">edit</span> <span id="edit-entry-title"></span>
            </h3>
            <p style="margin: 4px 0 0 0; font-size: 0.82rem; color: var(--muted);">
                Modify submitted scouting values and save changes directly to the database.
            </p>
        </div>
        <button id="modal-close-btn" type="button" aria-label="Close" style="background: transparent; border: none; font-size: 1.5rem; color: var(--muted); cursor: pointer; padding: 4px 8px; line-height: 1;">&times;</button>
    `;
    header.querySelector('#edit-entry-title').textContent = `Edit ${typeLabel} Entry: Team ${teamNum}${matchNum ? ` (Match ${matchNum})` : ''}`;
    modalContent.appendChild(header);

    // Form body
    const body = document.createElement('form');
    body.id = 'edit-entry-form';
    body.style.cssText = `padding: 20px; overflow-y: auto; flex: 1; display: flex; flex-direction: column; gap: 16px;`;
    // Enter in a form with a single text box would otherwise reload the page.
    body.addEventListener('submit', (e) => e.preventDefault());

    const controls = [];
    const knownFieldIds = new Set();

    // Render defined schema fields
    fields.forEach(field => {
        if (!field || !field.id) return;
        knownFieldIds.add(field.id);
        const fieldType = (field.type || 'text').toLowerCase();
        if (DISPLAY_ONLY_TYPES.has(fieldType)) return;
        if (LAYOUT_TYPES.has(fieldType)) {
            body.appendChild(buildSectionHeading(localize(field.label) || 'Section'));
            return;
        }
        body.appendChild(buildFieldControl(field, currentData[field.id], controls));
    });

    // Stored values the form doesn't define (submission metadata, fields removed from the form)
    const extras = Object.keys(currentData)
        .filter(k => !knownFieldIds.has(k) && !RESERVED_KEYS.has(k))
        .map(k => buildExtraControl(k, currentData[k], controls))
        .filter(Boolean);
    if (extras.length > 0) {
        body.appendChild(buildSectionHeading('Additional Properties'));
        extras.forEach(group => body.appendChild(group));
    }

    controls.forEach(control => { control.initial = readControl(control); });

    modalContent.appendChild(body);

    // Footer actions
    const footer = document.createElement('div');
    footer.style.cssText = `
        padding: 16px 20px; border-top: 1px solid var(--border-color);
        display: flex; align-items: center; justify-content: flex-end; gap: 12px;
    `;
    footer.innerHTML = `
        <button type="button" id="btn-cancel-edit" class="btn ghost" style="padding: 8px 16px; border-radius: 8px; cursor: pointer;">Cancel</button>
        <button type="button" id="btn-save-edit" class="btn primary" style="padding: 8px 18px; border-radius: 8px; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 6px;">
            <span class="material-symbols-outlined" style="font-size: 1.1rem;">save</span> Save Changes
        </button>
    `;
    modalContent.appendChild(footer);

    modalOverlay.appendChild(modalContent);
    document.body.appendChild(modalOverlay);

    // Close logic
    const closeModal = () => modalOverlay.remove();
    header.querySelector('#modal-close-btn').addEventListener('click', closeModal);
    footer.querySelector('#btn-cancel-edit').addEventListener('click', closeModal);
    modalOverlay.addEventListener('click', (e) => {
        if (e.target === modalOverlay) closeModal();
    });

    // Save logic
    const saveBtn = footer.querySelector('#btn-save-edit');
    saveBtn.addEventListener('click', async () => {
        const invalid = controls.find(control => readControl(control) !== control.initial && !control.el.checkValidity());
        if (invalid) {
            invalid.el.reportValidity();
            return;
        }

        saveBtn.disabled = true;
        saveBtn.textContent = 'Saving...';

        const updatedData = Object.assign({}, currentData);
        controls.forEach(control => applyControl(control, updatedData));

        // Ensure metadata. The data pages show prescout entries under a "prescout" label, which is not a real event.
        const storedEventKey = entry.rawEventKey || (entry.eventKey !== 'prescout' ? entry.eventKey : null);
        if (storedEventKey) {
            updatedData.eventKey = storedEventKey;
        }
        if (entry.targetTeamNumber !== undefined && entry.targetTeamNumber !== null) {
            updatedData.targetTeamNumber = entry.targetTeamNumber;
        }
        if (type !== 'pit') {
            if (entry.matchKey) updatedData.matchKey = entry.matchKey;
            if (entry.matchNumber !== undefined && entry.matchNumber !== null) {
                updatedData.matchNumber = entry.matchNumber;
            }
        }

        try {
            await request(entryApiPath(entry, type), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ data: updatedData })
            });

            showToast(`${typeLabel} entry updated successfully!`, 'success');
            closeModal();
            if (typeof onSaved === 'function') {
                await onSaved();
            }
        } catch (err) {
            console.error('Failed to update scouting entry:', err);
            showToast(`Failed to update entry: ${err.message || err}`, 'error');
            saveBtn.disabled = false;
            saveBtn.innerHTML = `<span class="material-symbols-outlined" style="font-size: 1.1rem;">save</span> Save Changes`;
        }
    });
}

/**
 * Confirm and delete a scouting entry
 */
export async function confirmAndDeleteEntry(entry, type, onDeleted) {
    if (!entry) return;
    const typeLabel = entryTypeLabel(type);
    const teamNum = entry.targetTeamNumber || (entry.data && entry.data.targetTeamNumber) || 'Unknown';
    const matchNum = entry.matchNumber || (entry.data && entry.data.matchNumber) || null;
    const matchInfo = matchNum ? ` (Match ${matchNum})` : '';

    const confirmed = window.confirm(`Are you sure you want to delete this ${typeLabel} scouting entry for Team ${teamNum}${matchInfo}? This cannot be undone.`);
    if (!confirmed) return;

    try {
        await request(entryApiPath(entry, type), {
            method: 'DELETE'
        });
        showToast(`${typeLabel} entry deleted successfully!`, 'success');
        if (typeof onDeleted === 'function') {
            await onDeleted();
        }
    } catch (err) {
        console.error('Failed to delete scouting entry:', err);
        showToast(`Failed to delete entry: ${err.message || err}`, 'error');
    }
}
