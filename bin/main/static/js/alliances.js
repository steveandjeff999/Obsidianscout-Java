(function () {
    'use strict';

    const { request, requireAuth, showToast, setUserBadge, setActiveNav,
            adjustNavForRole, wireLogout, initTheme, wireThemeToggle, isAdmin } = window.Obsidianscout;

    let currentUser = null;
    let alliances = [];
    let pendingInvites = [];
    let importSources = [];
    const LIVE_REFRESH_MS = 10000;

    // ─────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────

    async function init() {
        initTheme();
        currentUser = await requireAuth();
        if (!currentUser) return;

        setUserBadge(currentUser);
        setActiveNav();
        adjustNavForRole(currentUser);
        wireLogout();
        wireThemeToggle();
        wireModals();
        attachFormListeners();
        injectSidebarBadge();

        await Promise.all([loadAlliances(), loadInvites()]);
        await loadInviteBadge();

        setInterval(async () => {
            await Promise.all([loadAlliances(), loadInvites(), loadInviteBadge()]);
        }, LIVE_REFRESH_MS);

        // Show/hide Create button for non-admins
        const createBtn = document.getElementById('btn-create-alliance');
        if (!isAdmin(currentUser.role)) {
            createBtn.style.display = 'none';
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Data loading
    // ─────────────────────────────────────────────────────────────────

    async function loadAlliances() {
        try {
            alliances = await request('/api/alliances') || [];
            renderAlliances();
        } catch (err) {
            showToast('Failed to load alliances: ' + err.message, 'error');
        }
    }

    async function loadInvites() {
        try {
            pendingInvites = await request('/api/alliances/invites') || [];
            renderInvites();
        } catch (err) {
            // Non-fatal
        }
    }

    async function loadInviteBadge() {
        try {
            const data = await request('/api/alliances/invites/count');
            const count = data?.count || 0;

            // Sidebar badge
            const badge = document.getElementById('alliance-invite-badge');
            if (badge) {
                badge.textContent = count;
                badge.style.display = count > 0 ? 'inline-flex' : 'none';
            }

            // Update dashboard card if we're on the right page (no-op otherwise)
            const dashCard = document.getElementById('alliance-invite-card');
            if (dashCard) {
                dashCard.style.display = count > 0 ? '' : 'none';
                const countEl = dashCard.querySelector('.invite-count');
                if (countEl) countEl.textContent = count;
            }
        } catch (err) {
            // Silently ignore — badge is non-critical
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Sidebar invite badge injection
    // ─────────────────────────────────────────────────────────────────

    function injectSidebarBadge() {
        const link = document.getElementById('nav-alliances');
        if (!link) return;
        link.style.display = 'flex';
        link.style.alignItems = 'center';

        const badge = document.createElement('span');
        badge.id = 'alliance-invite-badge';
        badge.className = 'invite-badge';
        badge.style.display = 'none';
        link.appendChild(badge);
    }

    // ─────────────────────────────────────────────────────────────────
    // Render: Alliances
    // ─────────────────────────────────────────────────────────────────

    function renderAlliances() {
        const grid = document.getElementById('alliances-grid');
        const countBadge = document.getElementById('alliance-count');
        if (!grid) return;

        countBadge.textContent = alliances.length + ' alliance' + (alliances.length !== 1 ? 's' : '');

        if (!alliances.length) {
            grid.innerHTML = `
                <div class="card" style="grid-column:1/-1;">
                    <div class="empty-state">
                        <div class="empty-icon">🤝</div>
                        <p>No alliances yet. Create one and invite partner teams to start sharing data.</p>
                        ${isAdmin(currentUser?.role) ? '<button class="btn" onclick="document.getElementById(\'btn-create-alliance\').click()">+ Create Alliance</button>' : ''}
                    </div>
                </div>`;
            return;
        }

        grid.innerHTML = alliances.map(a => renderAllianceCard(a)).join('');
    }

    function renderAllianceCard(alliance) {
        const isOwner = alliance.ownerTeamNumber === currentUser?.teamNumber;
        const canManage = isAdmin(currentUser?.role) && isOwner;

        const members = (alliance.members || []);

        const chips = members.map(m => {
            const cls = m.status.toLowerCase();
            const label = m.status.charAt(0) + m.status.slice(1).toLowerCase();
            const canRemove = canManage && m.status !== 'OWNER';
            const isSelf = m.teamNumber === currentUser?.teamNumber && m.status !== 'OWNER';

            return `<span class="member-chip ${cls}" title="Team ${m.teamNumber} — ${label}">
                Team ${m.teamNumber}
                <span style="opacity:0.55;font-weight:400;margin-left:2px;">(${label})</span>
                ${(canRemove || isSelf) ? `<button class="remove-member" type="button" data-alliance="${alliance.id}" data-team="${m.teamNumber}" title="Remove team" aria-label="Remove Team ${m.teamNumber}">×</button>` : ''}
            </span>`;
        }).join('');

        const eventTag = alliance.eventKey
            ? `<span style="background:var(--surface-2);border-radius:6px;padding:2px 8px;font-size:11px;font-weight:600;">${alliance.eventKey}</span>`
            : '';

        const actions = [];
        if (canManage) {
            actions.push(`<button class="btn-xs ghost" data-action="edit-alliance" data-id="${alliance.id}">Edit</button>`);
            actions.push(`<button class="btn-xs warn" data-action="invite-team" data-id="${alliance.id}">+ Invite Team</button>`);
            actions.push(`<button class="btn-xs ghost" data-action="import-data" data-id="${alliance.id}">Import Data</button>`);
            actions.push(`<button class="btn-xs danger" data-action="delete-alliance" data-id="${alliance.id}">Delete</button>`);
        } else {
            // Non-owner can leave if they're an accepted member
            const myMembership = members.find(m => m.teamNumber === currentUser?.teamNumber);
            if (myMembership && myMembership.status === 'ACCEPTED') {
                actions.push(`<button class="btn-xs danger" data-action="leave-alliance" data-id="${alliance.id}" data-team="${currentUser.teamNumber}">Leave</button>`);
            }
        }

        return `
            <div class="alliance-card" data-alliance-id="${alliance.id}">
                <div class="alliance-card-header">
                    <div>
                        <h3 class="alliance-card-title">${escHtml(alliance.name)}</h3>
                        <div style="margin-top:6px;display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
                            ${eventTag}
                            <span style="font-size:11px;color:var(--muted);">Owner: Team ${alliance.ownerTeamNumber}</span>
                        </div>
                    </div>
                </div>
                ${alliance.notes ? `<p style="margin:0;font-size:13px;color:var(--muted);line-height:1.5;">${escHtml(alliance.notes)}</p>` : ''}
                <div>
                    <div style="font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:1px;color:var(--muted);margin-bottom:8px;">Members</div>
                    <div class="member-list">${chips || '<span style="font-size:13px;color:var(--muted);">No members yet</span>'}</div>
                </div>
                ${actions.length ? `<div class="alliance-actions">${actions.join('')}</div>` : ''}
            </div>`;
    }

    // ─────────────────────────────────────────────────────────────────
    // Render: Invites
    // ─────────────────────────────────────────────────────────────────

    function renderInvites() {
        const section = document.getElementById('invites-section');
        const list = document.getElementById('invites-list');
        if (!section || !list) return;

        if (!pendingInvites.length) {
            section.style.display = 'none';
            return;
        }

        section.style.display = '';

        list.innerHTML = pendingInvites.map(inv => `
            <div class="invite-card" data-invite-alliance="${inv.id}">
                <div class="invite-card-info">
                    <div class="invite-card-name">${escHtml(inv.name)}</div>
                    <div class="invite-card-meta">
                        Owned by Team ${inv.ownerTeamNumber}
                        ${inv.eventKey ? ` · ${inv.eventKey}` : ''}
                        · ${inv.members?.length || 0} member(s)
                    </div>
                </div>
                <div class="invite-card-actions">
                    <button class="btn-xs accept" data-action="accept-invite" data-id="${inv.id}">Accept</button>
                    <button class="btn-xs danger" data-action="decline-invite" data-id="${inv.id}">Decline</button>
                </div>
            </div>`).join('');
    }

    // ─────────────────────────────────────────────────────────────────
    // Event delegation
    // ─────────────────────────────────────────────────────────────────

    document.addEventListener('click', async (e) => {
        const el = e.target.closest('[data-action]');
        if (!el) return;
        const action = el.dataset.action;

        switch (action) {
            case 'edit-alliance':
                openEditModal(parseInt(el.dataset.id));
                break;
            case 'invite-team':
                openInviteModal(parseInt(el.dataset.id));
                break;
            case 'delete-alliance':
                await handleDeleteAlliance(parseInt(el.dataset.id));
                break;
            case 'import-data':
                await openImportModal(parseInt(el.dataset.id));
                break;
            case 'leave-alliance':
                await handleRemoveMember(parseInt(el.dataset.id), parseInt(el.dataset.team));
                break;
            case 'accept-invite':
                await handleRespondInvite(parseInt(el.dataset.id), true);
                break;
            case 'decline-invite':
                await handleRespondInvite(parseInt(el.dataset.id), false);
                break;
        }
    });

    // Remove member via × button
    document.addEventListener('click', async (e) => {
        const el = e.target.closest('.remove-member');
        if (!el) return;
        const allianceId = parseInt(el.dataset.alliance);
        const teamNumber = parseInt(el.dataset.team);
        await handleRemoveMember(allianceId, teamNumber);
    });

    // ─────────────────────────────────────────────────────────────────
    // Alliance CRUD actions
    // ─────────────────────────────────────────────────────────────────

    async function handleDeleteAlliance(id) {
        const alliance = alliances.find(a => a.id === id);
        if (!alliance) return;
        if (!confirm(`Delete alliance "${alliance.name}"? All memberships will be removed and data sharing will stop.`)) return;

        try {
            await request(`/api/alliances/${id}`, { method: 'DELETE' });
            showToast('Alliance deleted.', 'success');
            await loadAlliances();
            await loadInviteBadge();
        } catch (err) {
            showToast('Failed to delete: ' + err.message, 'error');
        }
    }

    async function handleRemoveMember(allianceId, teamNumber) {
        const isSelf = teamNumber === currentUser?.teamNumber;
        const msg = isSelf
            ? 'Leave this alliance? You will no longer see their scouting data.'
            : `Remove Team ${teamNumber} from this alliance?`;
        if (!confirm(msg)) return;

        try {
            await request(`/api/alliances/${allianceId}/members/${teamNumber}`, { method: 'DELETE' });
            showToast(isSelf ? 'You left the alliance.' : `Team ${teamNumber} removed.`, 'success');
            await loadAlliances();
        } catch (err) {
            showToast('Failed: ' + err.message, 'error');
        }
    }

    async function handleRespondInvite(allianceId, accept) {
        try {
            await request(`/api/alliances/${allianceId}/respond`, {
                method: 'POST',
                json: { accept }
            });
            showToast(accept ? 'You joined the alliance! Data is now shared.' : 'Invite declined.', accept ? 'success' : 'info');
            await Promise.all([loadAlliances(), loadInvites(), loadInviteBadge()]);
        } catch (err) {
            showToast('Failed: ' + err.message, 'error');
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Modals
    // ─────────────────────────────────────────────────────────────────

    function wireModals() {
        // Close on backdrop click
        ['modal-alliance', 'modal-invite', 'modal-import'].forEach(id => {
            const backdrop = document.getElementById(id);
            if (!backdrop) return;
            backdrop.addEventListener('click', (e) => {
                if (e.target === backdrop) closeModal(id);
            });
        });

        document.getElementById('btn-create-alliance')?.addEventListener('click', openCreateModal);
        document.getElementById('btn-alliance-cancel')?.addEventListener('click', () => closeModal('modal-alliance'));
        document.getElementById('btn-invite-cancel')?.addEventListener('click', () => closeModal('modal-invite'));
        document.getElementById('btn-import-cancel')?.addEventListener('click', () => closeModal('modal-import'));
        document.getElementById('import-source-team')?.addEventListener('change', renderImportEventOptions);
        document.getElementById('import-event-key')?.addEventListener('change', renderImportSourceSummary);
    }

    function openCreateModal() {
        const modal = document.getElementById('modal-alliance');
        document.getElementById('modal-alliance-title').textContent = 'New Alliance';
        document.getElementById('btn-alliance-save').textContent = 'Create Alliance';
        document.getElementById('alliance-edit-id').value = '';
        document.getElementById('alliance-name').value = '';
        document.getElementById('alliance-event-key').value = '';
        document.getElementById('alliance-notes').value = '';
        openModal('modal-alliance');
        setTimeout(() => document.getElementById('alliance-name')?.focus(), 60);
    }

    function openEditModal(id) {
        const alliance = alliances.find(a => a.id === id);
        if (!alliance) return;
        document.getElementById('modal-alliance-title').textContent = 'Edit Alliance';
        document.getElementById('btn-alliance-save').textContent = 'Save Changes';
        document.getElementById('alliance-edit-id').value = id;
        document.getElementById('alliance-name').value = alliance.name || '';
        document.getElementById('alliance-event-key').value = alliance.eventKey || '';
        document.getElementById('alliance-notes').value = alliance.notes || '';
        openModal('modal-alliance');
        setTimeout(() => document.getElementById('alliance-name')?.focus(), 60);
    }

    function openInviteModal(allianceId) {
        document.getElementById('invite-alliance-id').value = allianceId;
        document.getElementById('invite-team-number').value = '';
        openModal('modal-invite');
        setTimeout(() => document.getElementById('invite-team-number')?.focus(), 60);
    }

    async function openImportModal(allianceId) {
        document.getElementById('import-alliance-id').value = allianceId;
        document.getElementById('import-match').checked = true;
        document.getElementById('import-pit').checked = true;
        document.getElementById('import-qual').checked = true;
        await loadImportSources();
        renderImportTeamOptions();
        openModal('modal-import');
        setTimeout(() => document.getElementById('import-source-team')?.focus(), 60);
    }

    async function loadImportSources() {
        try {
            importSources = await request('/api/alliances/import-sources') || [];
        } catch (err) {
            importSources = [];
            showToast('Failed to load import choices: ' + err.message, 'error');
        }
    }

    function renderImportTeamOptions() {
        const teamSelect = document.getElementById('import-source-team');
        if (!teamSelect) return;

        const teams = Array.from(new Set(importSources.map(s => s.teamNumber))).sort((a, b) => a - b);
        if (!teams.length) {
            teamSelect.innerHTML = '<option value="">No scouting data found</option>';
        } else {
            teamSelect.innerHTML = '<option value="">Select a team...</option>' + teams
                .map(team => `<option value="${team}">Team ${team}</option>`)
                .join('');
        }
        renderImportEventOptions();
    }

    function renderImportEventOptions() {
        const teamSelect = document.getElementById('import-source-team');
        const eventSelect = document.getElementById('import-event-key');
        if (!teamSelect || !eventSelect) return;

        const teamNumber = Number(teamSelect.value);
        const sources = importSources
            .filter(s => s.teamNumber === teamNumber)
            .sort((a, b) => String(a.eventKey || '').localeCompare(String(b.eventKey || '')));

        if (!teamNumber) {
            eventSelect.innerHTML = '<option value="">Select a team first</option>';
        } else if (!sources.length) {
            eventSelect.innerHTML = '<option value="">No events found</option>';
        } else {
            eventSelect.innerHTML = sources.map(source => {
                const eventKey = source.eventKey || '__NO_EVENT__';
                const label = source.eventKey || 'No event key';
                const total = source.matchScoutingCount + source.pitScoutingCount + source.qualitativeScoutingCount;
                return `<option value="${escHtml(eventKey)}">${escHtml(label)} - ${total} entries</option>`;
            }).join('');
        }
        renderImportSourceSummary();
    }

    function renderImportSourceSummary() {
        const summary = document.getElementById('import-source-summary');
        const teamNumber = Number(document.getElementById('import-source-team')?.value);
        const selectedEvent = document.getElementById('import-event-key')?.value || '';
        const eventValue = selectedEvent === '__NO_EVENT__' ? '' : selectedEvent;
        const source = importSources.find(s => s.teamNumber === teamNumber && (s.eventKey || '') === eventValue);

        if (!summary || !source) {
            summary?.classList.remove('open');
            if (summary) summary.innerHTML = '';
            return;
        }

        summary.innerHTML = [
            `<span class="badge">Match ${source.matchScoutingCount}</span>`,
            `<span class="badge">Pit ${source.pitScoutingCount}</span>`,
            `<span class="badge">Qual ${source.qualitativeScoutingCount}</span>`
        ].join('');
        summary.classList.add('open');
    }

    function openModal(id) {
        document.getElementById(id)?.classList.add('open');
    }

    function closeModal(id) {
        document.getElementById(id)?.classList.remove('open');
    }

    // ─────────────────────────────────────────────────────────────────
    // Form submission
    // ─────────────────────────────────────────────────────────────────

    function attachFormListeners() {
        document.getElementById('form-alliance')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            await handleAllianceSave();
        });

        document.getElementById('form-invite')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            await handleInviteSubmit();
        });

        document.getElementById('form-import')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            await handleImportSubmit();
        });
    }

    async function handleAllianceSave() {
        const saveBtn = document.getElementById('btn-alliance-save');
        const editId = document.getElementById('alliance-edit-id').value;
        const name = document.getElementById('alliance-name').value.trim();
        const eventKey = document.getElementById('alliance-event-key').value.trim() || null;
        const notes = document.getElementById('alliance-notes').value.trim() || null;

        if (!name) { showToast('Alliance name is required.', 'error'); return; }

        saveBtn.disabled = true;
        const originalText = saveBtn.textContent;
        saveBtn.textContent = 'Saving…';

        try {
            if (editId) {
                await request(`/api/alliances/${editId}`, {
                    method: 'PUT',
                    json: { name, eventKey, notes }
                });
                showToast('Alliance updated!', 'success');
            } else {
                await request('/api/alliances', {
                    method: 'POST',
                    json: { name, eventKey, notes }
                });
                showToast('Alliance created!', 'success');
            }
            closeModal('modal-alliance');
            await loadAlliances();
        } catch (err) {
            showToast('Failed: ' + err.message, 'error');
        } finally {
            saveBtn.disabled = false;
            saveBtn.textContent = originalText;
        }
    }

    async function handleInviteSubmit() {
        const sendBtn = document.getElementById('btn-invite-send');
        const allianceId = parseInt(document.getElementById('invite-alliance-id').value);
        const teamNumber = parseInt(document.getElementById('invite-team-number').value);

        if (!teamNumber || teamNumber <= 0) { showToast('Please enter a valid team number.', 'error'); return; }

        sendBtn.disabled = true;
        sendBtn.textContent = 'Sending…';

        try {
            await request(`/api/alliances/${allianceId}/invite`, {
                method: 'POST',
                json: { partnerTeamNumber: teamNumber }
            });
            showToast(`Invite sent to Team ${teamNumber}!`, 'success');
            closeModal('modal-invite');
            await loadAlliances();
        } catch (err) {
            showToast('Failed: ' + err.message, 'error');
        } finally {
            sendBtn.disabled = false;
            sendBtn.textContent = 'Send Invite';
        }
    }

    async function handleImportSubmit() {
        const btn = document.getElementById('btn-import-submit');
        const allianceId = parseInt(document.getElementById('import-alliance-id').value);
        const sourceTeamRaw = document.getElementById('import-source-team').value;
        const selectedEvent = document.getElementById('import-event-key').value;
        const eventKey = selectedEvent || null;
        const includeMatchScouting = Boolean(document.getElementById('import-match').checked);
        const includePitScouting = Boolean(document.getElementById('import-pit').checked);
        const includeQualitativeScouting = Boolean(document.getElementById('import-qual').checked);

        if (!sourceTeamRaw || Number(sourceTeamRaw) <= 0) {
            showToast('Enter a valid source team number.', 'error');
            return;
        }

        if (!includeMatchScouting && !includePitScouting && !includeQualitativeScouting) {
            showToast('Select at least one data type to import.', 'error');
            return;
        }

        btn.disabled = true;
        const originalText = btn.textContent;
        btn.textContent = 'Importing...';

        try {
            const response = await request(`/api/alliances/${allianceId}/import`, {
                method: 'POST',
                json: {
                    sourceTeamNumber: Number(sourceTeamRaw),
                    eventKey,
                    includeMatchScouting,
                    includePitScouting,
                    includeQualitativeScouting
                }
            });

            const importedMatch = response?.importedMatchScouting || 0;
            const importedPit = response?.importedPitScouting || 0;
            const importedQual = response?.importedQualitativeScouting || 0;
            const skipped = response?.skippedDuplicates || 0;
            const skippedText = skipped ? ` Skipped ${skipped} duplicate${skipped === 1 ? '' : 's'}.` : '';
            showToast(`Imported ${importedMatch + importedPit + importedQual} entries (match ${importedMatch}, pit ${importedPit}, qual ${importedQual}).${skippedText}`, 'success');

            closeModal('modal-import');
            await Promise.all([loadAlliances(), loadInvites(), loadInviteBadge()]);
        } catch (err) {
            showToast('Import failed: ' + err.message, 'error');
        } finally {
            btn.disabled = false;
            btn.textContent = originalText;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────

    function escHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ─────────────────────────────────────────────────────────────────
    // Boot
    // ─────────────────────────────────────────────────────────────────

    document.addEventListener('DOMContentLoaded', init);
})();
