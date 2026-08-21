/**
 * Component Conflict Modal Module - ObsidianScout
 * Conflict resolution and side-by-side discrepancy modal for duplicate scout submissions with consensus calculation.
 */

import { request } from '../base/http.js';
import { showToast } from './toast.js';
import { localize } from '../base/i18n.js';

export function openConflictResolutionModal(options) {
    const { type = 'match', fields = [], conflictingEntries = [], onResolved = () => {} } = options;
    if (!conflictingEntries || conflictingEntries.length === 0) return;

    const endpointPrefix = (type === 'pit') ? 'pit-scouting' : ((type === 'qual' || type === 'qualitative') ? 'qual-scouting' : 'scouting');
    const primary = conflictingEntries[0];
    const teamNum = primary.targetTeamNumber || (primary.data && primary.data.targetTeamNumber) || 'Unknown';
    const matchNum = primary.matchNumber || (primary.data && primary.data.matchNumber) || null;

    // Remove existing modal if any
    const existing = document.getElementById('obsidian-conflict-modal');
    if (existing) existing.remove();

    const modalOverlay = document.createElement('div');
    modalOverlay.id = 'obsidian-conflict-modal';
    modalOverlay.style.cssText = `
        position: fixed; top: 0; left: 0; right: 0; bottom: 0;
        background: rgba(0, 0, 0, 0.75); z-index: 10000;
        display: flex; align-items: center; justify-content: center;
        padding: 16px; backdrop-filter: blur(4px);
    `;

    const modalContent = document.createElement('div');
    modalContent.style.cssText = `
        background: var(--card-bg, #18181b); color: var(--text-color, #f8fafc);
        border: 1px solid var(--border-color, #27272a); border-radius: 12px;
        max-width: 900px; width: 100%; max-height: 90vh; display: flex; flex-direction: column;
        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.5);
        overflow: hidden; animation: fadeIn 0.15s ease-out;
    `;

    // Calculate consensus merged values
    const consensusData = {};
    // 1. Seed with all keys
    conflictingEntries.forEach(entry => {
        if (entry.data && typeof entry.data === 'object') {
            Object.keys(entry.data).forEach(k => {
                if (consensusData[k] === undefined && entry.data[k] !== undefined && entry.data[k] !== null) {
                    consensusData[k] = entry.data[k];
                }
            });
        }
    });
    // 2. Ensure meta fields
    if (primary.eventKey) consensusData.eventKey = primary.eventKey;
    if (primary.matchKey) consensusData.matchKey = primary.matchKey;
    if (primary.matchNumber !== undefined) consensusData.matchNumber = primary.matchNumber;
    if (primary.targetTeamNumber !== undefined) consensusData.targetTeamNumber = primary.targetTeamNumber;

    // 3. Compute field averages & votes
    fields.forEach(field => {
        const id = field.id;
        const values = conflictingEntries
            .map(e => e.data ? e.data[id] : null)
            .filter(v => v !== undefined && v !== null);

        if (values.length > 0) {
            const ft = (field.type || '').toLowerCase();
            if (['number', 'counter', 'slider', 'range', 'rating'].includes(ft)) {
                let sum = 0;
                let count = 0;
                values.forEach(v => {
                    const n = Number(v);
                    if (!isNaN(n)) { sum += n; count++; }
                });
                if (count > 0) {
                    const avg = sum / count;
                    consensusData[id] = (avg % 1 === 0) ? avg : Number(avg.toFixed(1));
                }
            } else if (ft === 'checkbox') {
                const trueCount = values.filter(v => v === true || v === 'true' || v === 1).length;
                consensusData[id] = trueCount >= (values.length / 2);
            } else {
                const nonEmpties = values.filter(v => String(v).trim().length > 0);
                if (nonEmpties.length > 0) consensusData[id] = nonEmpties[0];
            }
        }

        // 4. Default fallbacks for required fields
        if (field.required && (consensusData[id] === undefined || consensusData[id] === null || (consensusData[id] === '' && (field.type || '').toLowerCase() !== 'text'))) {
            const ft = (field.type || '').toLowerCase();
            if (['number', 'counter', 'slider', 'range', 'rating'].includes(ft)) {
                consensusData[id] = field.min || 0;
            } else if (ft === 'checkbox') {
                consensusData[id] = false;
            } else if (field.options && field.options.length > 0) {
                consensusData[id] = field.options[0].value;
            } else {
                consensusData[id] = 0;
            }
        }
    });

    // Header
    const header = document.createElement('div');
    header.style.cssText = `
        padding: 16px 20px; border-bottom: 1px solid var(--border-color, #27272a);
        display: flex; align-items: center; justify-content: space-between;
        background: rgba(234, 179, 8, 0.05);
    `;
    header.innerHTML = `
        <div>
            <h3 style="margin: 0; font-size: 1.15rem; font-weight: 700; color: #fbbf24; display: flex; align-items: center; gap: 8px;">
                <span>⚠️</span> Resolve Discrepancy: Team ${teamNum}${matchNum ? ` (Match ${matchNum})` : ''}
            </h3>
            <p style="margin: 4px 0 0 0; font-size: 0.82rem; color: #a1a1aa;">
                ${conflictingEntries.length} conflicting submissions found. Compare side-by-side or save a merged consensus.
            </p>
        </div>
        <button id="modal-close-btn" style="background: transparent; border: none; font-size: 1.5rem; color: #a1a1aa; cursor: pointer; padding: 4px 8px; line-height: 1;">&times;</button>
    `;
    modalContent.appendChild(header);

    // Body with Scrollable Table
    const body = document.createElement('div');
    body.style.cssText = `padding: 20px; overflow-y: auto; flex: 1;`;

    // Comparison Table
    const tableScroll = document.createElement('div');
    tableScroll.style.cssText = `overflow-x: auto; margin-bottom: 20px; border: 1px solid var(--border-color, #27272a); border-radius: 8px;`;

    const table = document.createElement('table');
    table.style.cssText = `width: 100%; border-collapse: collapse; font-size: 0.85rem; text-align: left;`;

    // Thead
    let theadHtml = `
        <thead>
            <tr style="background: rgba(255,255,255,0.03); border-bottom: 1px solid var(--border-color, #27272a);">
                <th style="padding: 10px 14px; font-weight: 700; width: 220px;">Field</th>
    `;
    conflictingEntries.forEach((entry, idx) => {
        const rawId = entry.originalId || entry.id || '';
        const scouter = entry.scoutUsername || entry.username || `Team ${entry.ownerTeamNumber || 'Partner'}`;
        const time = entry.createdAt ? new Date(entry.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';
        theadHtml += `
            <th style="padding: 10px 14px; font-weight: 700;">
                <div style="font-weight: 700; color: var(--primary, #38bdf8);">Submission #${idx + 1}</div>
                <div style="font-size: 0.75rem; color: #a1a1aa; font-weight: normal;">${scouter} (${time})</div>
            </th>
        `;
    });
    theadHtml += `
                <th style="padding: 10px 14px; font-weight: 700; color: #fbbf24; background: rgba(234, 179, 8, 0.05);">Consensus</th>
            </tr>
        </thead>
    `;

    // Tbody
    let tbodyHtml = `<tbody>`;
    fields.forEach(field => {
        const vals = conflictingEntries.map(e => e.data ? e.data[field.id] : undefined);
        const isDiff = vals.some(v => String(v) !== String(vals[0]));
        const rowStyle = isDiff
            ? `background: rgba(234, 179, 8, 0.08); border-bottom: 1px solid rgba(234, 179, 8, 0.2); font-weight: 600;`
            : `border-bottom: 1px solid var(--border-color, #27272a);`;

        const label = (window.Obsidianscout && typeof window.Obsidianscout.localize === 'function')
            ? window.Obsidianscout.localize(field.label)
            : (localize ? localize(field.label) : (field.label || field.id));

        tbodyHtml += `<tr style="${rowStyle}">
            <td style="padding: 8px 14px; color: ${isDiff ? '#fde047' : '#cbd5e1'};">
                ${isDiff ? '⚠️ ' : ''}${label}
            </td>`;

        vals.forEach(v => {
            const displayVal = (v === true) ? '✓ True' : (v === false ? '✗ False' : (v !== undefined && v !== null ? v : '--'));
            tbodyHtml += `<td style="padding: 8px 14px;">${displayVal}</td>`;
        });

        const consVal = consensusData[field.id];
        const displayCons = (consVal === true) ? '✓ True' : (consVal === false ? '✗ False' : (consVal !== undefined && consVal !== null ? consVal : '--'));
        tbodyHtml += `<td style="padding: 8px 14px; font-weight: 700; color: #fbbf24; background: rgba(234, 179, 8, 0.05);">${displayCons}</td></tr>`;
    });
    tbodyHtml += `</tbody>`;

    table.innerHTML = theadHtml + tbodyHtml;
    tableScroll.appendChild(table);
    body.appendChild(tableScroll);

    // Actions container
    const actionsCard = document.createElement('div');
    actionsCard.style.cssText = `
        background: rgba(255,255,255,0.02); border: 1px solid var(--border-color, #27272a);
        border-radius: 8px; padding: 16px; display: flex; flex-direction: column; gap: 12px;
    `;
    actionsCard.innerHTML = `
        <div style="font-weight: 700; font-size: 0.9rem; color: #e2e8f0; margin-bottom: 4px;">Resolution Options:</div>
        <div style="display: flex; flex-wrap: wrap; gap: 8px;">
            ${conflictingEntries.map((entry, idx) => {
                const rawId = entry.originalId || entry.id || '';
                const scouter = entry.scoutUsername || entry.username || `Submission #${idx + 1}`;
                return `<button class="btn secondary btn-sm btn-keep-single" data-id="${rawId}" style="padding: 6px 12px; font-size: 0.8rem; border-radius: 6px; cursor: pointer;">
                    ✓ Keep Submission #${idx + 1} (${scouter})
                </button>`;
            }).join('')}
        </div>
        <div style="margin-top: 6px; padding-top: 12px; border-top: 1px solid var(--border-color, #27272a); display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px;">
            <div style="font-size: 0.8rem; color: #a1a1aa;">
                Or combine them into a single consensus entry with numeric averages and boolean consensus.
            </div>
            <button id="btn-save-consensus" class="btn primary" style="background: #eab308; color: #0f172a; font-weight: 700; border: none; padding: 8px 16px; border-radius: 6px; cursor: pointer; font-size: 0.85rem;">
                ⚡ Save Merged Consensus
            </button>
        </div>
        <div id="modal-status-msg" style="font-size: 0.8rem; color: #38bdf8; display: none;"></div>
    `;
    body.appendChild(actionsCard);

    modalContent.appendChild(body);
    modalOverlay.appendChild(modalContent);
    document.body.appendChild(modalOverlay);

    // Event handlers
    const closeModal = () => modalOverlay.remove();
    header.querySelector('#modal-close-btn').addEventListener('click', closeModal);
    modalOverlay.addEventListener('click', (e) => {
        if (e.target === modalOverlay) closeModal();
    });

    const statusMsg = actionsCard.querySelector('#modal-status-msg');
    const showStatus = (text) => {
        statusMsg.style.display = 'block';
        statusMsg.textContent = text;
    };

    // Keep Single Entry
    actionsCard.querySelectorAll('.btn-keep-single').forEach(btn => {
        btn.addEventListener('click', async () => {
            const winningId = btn.getAttribute('data-id');
            btn.disabled = true;
            showStatus('Resolving conflict...');
            try {
                for (const entry of conflictingEntries) {
                    const rawId = entry.originalId || entry.id || '';
                    let cleanId = String(rawId);
                    ['match-', 'pit-', 'qual-', 'qualitative-'].forEach(p => {
                        if (cleanId.toLowerCase().startsWith(p)) cleanId = cleanId.substring(p.length);
                    });
                    if (rawId && rawId !== winningId && cleanId !== winningId) {
                        await request(`/api/${endpointPrefix}/${cleanId}`, { method: 'DELETE' });
                    }
                }
                showToast('Conflict resolved successfully!', 'success');
                closeModal();
                if (typeof onResolved === 'function') onResolved();
            } catch (err) {
                showStatus('Error: ' + err.message);
                showToast('Failed to resolve: ' + err.message, 'error');
            }
        });
    });

    // Save Merged Consensus
    const consensusBtn = actionsCard.querySelector('#btn-save-consensus');
    consensusBtn.addEventListener('click', async () => {
        consensusBtn.disabled = true;
        showStatus('Saving merged consensus entry...');
        try {
            let primaryCleanId = String(primary.originalId || primary.id || '');
            ['match-', 'pit-', 'qual-', 'qualitative-'].forEach(p => {
                if (primaryCleanId.toLowerCase().startsWith(p)) primaryCleanId = primaryCleanId.substring(p.length);
            });

            // Update primary entry
            await request(`/api/${endpointPrefix}/${primaryCleanId}`, {
                method: 'PUT',
                json: { data: consensusData }
            });

            // Delete other conflicting entries
            for (let i = 1; i < conflictingEntries.length; i++) {
                const entry = conflictingEntries[i];
                let cleanId = String(entry.originalId || entry.id || '');
                ['match-', 'pit-', 'qual-', 'qualitative-'].forEach(p => {
                    if (cleanId.toLowerCase().startsWith(p)) cleanId = cleanId.substring(p.length);
                });
                if (cleanId && cleanId !== primaryCleanId) {
                    await request(`/api/${endpointPrefix}/${cleanId}`, { method: 'DELETE' });
                }
            }

            showToast('Consensus merged entry saved!', 'success');
            closeModal();
            if (typeof onResolved === 'function') onResolved();
        } catch (err) {
            showStatus('Error: ' + err.message);
            showToast('Failed to save consensus: ' + err.message, 'error');
        }
    });
}
