function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : (fallback || key);
}

const SVG_ICONS = {
    copy: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>`,
    qr: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="5" height="5" x="3" y="3" rx="1"/><rect width="5" height="5" x="16" y="3" rx="1"/><rect width="5" height="5" x="3" y="16" rx="1"/><path d="M21 16h-3a2 2 0 0 0-2 2v3"/><path d="M21 21v.01"/><path d="M12 7v3a2 2 0 0 1-2 2H7"/><path d="M3 12h.01"/><path d="M12 3h.01"/><path d="M12 16v.01"/><path d="M16 12h1"/><path d="M21 12v.01"/><path d="M12 21v-1"/></svg>`,
    preview: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/></svg>`,
    revoke: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="m4.93 4.93 14.14 14.14"/></svg>`,
    refresh: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/></svg>`
};

function req(path, options) {
    if (window.Obsidianscout && typeof Obsidianscout.request === 'function') {
        return Obsidianscout.request(path, options);
    }
    return fetch(path, options).then(r => r.json());
}

function toast(msg, type) {
    if (window.Obsidianscout && typeof Obsidianscout.showToast === 'function') {
        Obsidianscout.showToast(msg, type);
    } else {
        alert(msg);
    }
}

let allShares = [];

document.addEventListener('DOMContentLoaded', async () => {
    if (window.Obsidianscout && typeof Obsidianscout.initTheme === 'function') {
        Obsidianscout.initTheme();
    }
    if (window.Obsidianscout && typeof Obsidianscout.requireAuth === 'function') {
        const me = await Obsidianscout.requireAuth();
        if (!me) return;
        Obsidianscout.setUserBadge(me);
        Obsidianscout.setActiveNav();
        Obsidianscout.adjustNavForRole(me);
        Obsidianscout.wireLogout();
        Obsidianscout.wireThemeToggle();
    }
    await fetchAndRenderShares();
    setupEventListeners();
});

window.addEventListener('obsidianscout:languagechange', () => {
    applyFilters();
});

async function fetchAndRenderShares() {
    const tbody = document.getElementById('shares-table-body');
    tbody.innerHTML = `
        <tr>
            <td colspan="8" style="text-align: center; padding: 40px; color: var(--muted);">
                <i class="fas fa-spinner fa-spin"></i> Loading shared links...
            </td>
        </tr>
    `;

    try {
        const shares = await req('/api/shares/team');
        allShares = Array.isArray(shares) ? shares : [];
        updateStats();
        applyFilters();
    } catch (err) {
        console.error('Failed to load team shares:', err);
        tbody.innerHTML = `
            <tr>
                <td colspan="8" style="text-align: center; padding: 40px; color: var(--danger, #ef4444);">
                    <i class="fas fa-exclamation-circle"></i> Failed to load shared links. ${err.message || ''}
                </td>
            </tr>
        `;
    }
}

function updateStats() {
    let active = 0;
    let expired = 0;
    let revoked = 0;
    let views = 0;

    allShares.forEach(s => {
        views += (s.viewCount || 0);
        if (s.isRevoked) revoked++;
        else if (s.isExpired) expired++;
        else active++;
    });

    document.getElementById('stat-active-count').innerText = active;
    document.getElementById('stat-views-count').innerText = views;
    document.getElementById('stat-expired-count').innerText = expired;
    document.getElementById('stat-revoked-count').innerText = revoked;
}

function applyFilters() {
    const searchTerm = document.getElementById('shares-search-input').value.toLowerCase().trim();
    const statusFilter = document.getElementById('shares-status-filter').value;
    const typeFilter = document.getElementById('shares-type-filter').value;

    const filtered = allShares.filter(s => {
        // Status filter
        if (statusFilter === 'active' && (s.isRevoked || s.isExpired)) return false;
        if (statusFilter === 'expired' && (!s.isExpired || s.isRevoked)) return false;
        if (statusFilter === 'revoked' && !s.isRevoked) return false;

        // Type filter
        if (typeFilter !== 'all' && s.resourceType !== typeFilter) return false;

        // Search term
        if (searchTerm) {
            const matchTitle = (s.title || '').toLowerCase().includes(searchTerm);
            const matchCreator = (s.createdByUsername || '').toLowerCase().includes(searchTerm);
            const matchEvent = (s.targetEventKey || '').toLowerCase().includes(searchTerm);
            if (!matchTitle && !matchCreator && !matchEvent) return false;
        }

        return true;
    });

    renderTable(filtered);
}

function renderTable(shares) {
    const tbody = document.getElementById('shares-table-body');
    if (shares.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="8" style="text-align: center; padding: 40px; color: var(--muted);">
                    <i class="fas fa-inbox fa-2x mb-8" style="opacity: 0.5;"></i>
                    <p style="margin: 0;">No shared links found matching criteria.</p>
                </td>
            </tr>
        `;
        return;
    }

    tbody.innerHTML = shares.map(s => {
        const fullUrl = s.shareUrl || `${window.location.origin}/shared/${s.token}`;
        
        let statusBadge = `<span class="share-badge share-badge-active">${t('shares.status_active', 'ACTIVE')}</span>`;
        if (s.isRevoked) {
            statusBadge = `<span class="share-badge share-badge-revoked">${t('shares.status_revoked', 'REVOKED')}</span>`;
        } else if (s.isExpired) {
            statusBadge = `<span class="share-badge share-badge-expired">${t('shares.status_expired', 'EXPIRED')}</span>`;
        }

        let scopeBadge = `<span class="share-badge share-badge-${s.accessScope}">${t('shares.scope_' + s.accessScope, s.accessScope.toUpperCase())}</span>`;
        if (s.hasPin) {
            scopeBadge += ` <i class="fas fa-lock" style="font-size: 0.75rem; color: #eab308;" title="PIN Protected"></i>`;
        }

        const typeLabels = {
            graph: t('shares.type_graph', 'Graph'),
            predictor: t('shares.type_predictor', 'Predictor'),
            event_predictor: t('shares.type_predictor', 'Event Predictor'),
            all_data: t('shares.type_all_data', 'All Data'),
            qual_data: t('shares.type_all_data', 'Qual Data'),
            pit_data: t('shares.type_all_data', 'Pit Data'),
            match_data: t('shares.type_all_data', 'Match Data'),
            custom_analytics: t('shares.type_custom_analytics', 'Custom Analytics')
        };

        const expiresStr = s.expiresAt ? new Date(s.expiresAt).toLocaleDateString() : t('share_modal.exp_never', 'Permanent');

        return `
            <tr>
                <td>
                    <div style="font-weight: 600; color: var(--ink);">${escapeHtml(s.title)}</div>
                    <small style="color: var(--muted);">${s.targetEventKey || 'All Events'} • ${s.shareMode === 'frozen_snapshot' ? t('shares.mode_snapshot', 'Snapshot') : t('shares.mode_live', 'Live')}</small>
                </td>
                <td><span class="badge">${typeLabels[s.resourceType] || s.resourceType}</span></td>
                <td>${scopeBadge}</td>
                <td><strong>${s.viewCount || 0}</strong></td>
                <td><span style="color: var(--muted);">${escapeHtml(s.createdByUsername)}</span></td>
                <td><span style="font-size: 0.85rem; color: var(--muted);">${expiresStr}</span></td>
                <td>${statusBadge}</td>
                <td style="text-align: right;">
                    <div class="shares-action-group">
                        <button class="shares-action-btn btn-copy-link" data-url="${fullUrl}" title="${t('shares.copy_link', 'Copy Link')}" aria-label="${t('shares.copy_link', 'Copy Link')}">
                            ${SVG_ICONS.copy}
                        </button>
                        <button class="shares-action-btn btn-qr-link" data-url="${fullUrl}" data-title="${escapeHtml(s.title)}" title="${t('shares.view_qr', 'Show QR Code')}" aria-label="${t('shares.view_qr', 'Show QR Code')}">
                            ${SVG_ICONS.qr}
                        </button>
                        <a href="/shared/${s.token}" target="_blank" class="shares-action-btn" title="${t('shares.preview', 'Open Preview')}" aria-label="${t('shares.preview', 'Open Preview')}">
                            ${SVG_ICONS.preview}
                        </a>
                        ${!s.isRevoked ? `
                            <button class="shares-action-btn danger btn-revoke-link" data-token="${s.token}" title="${t('shares.revoke', 'Revoke Link')}" aria-label="${t('shares.revoke', 'Revoke Link')}">
                                ${SVG_ICONS.revoke}
                            </button>
                        ` : ''}
                    </div>
                </td>
            </tr>
        `;
    }).join('');

    bindTableActionButtons();
}

function bindTableActionButtons() {
    // Copy
    document.querySelectorAll('.btn-copy-link').forEach(btn => {
        btn.addEventListener('click', () => {
            const url = btn.dataset.url;
            safeCopyText(url);
        });
    });

    // QR Dialog
    document.querySelectorAll('.btn-qr-link').forEach(btn => {
        btn.addEventListener('click', () => {
            const url = btn.dataset.url;
            const title = btn.dataset.title;
            showQrDialog(url, title);
        });
    });

    // Revoke
    document.querySelectorAll('.btn-revoke-link').forEach(btn => {
        btn.addEventListener('click', async () => {
            const token = btn.dataset.token;
            if (!confirm(t('shares.confirm_revoke', 'Are you sure you want to revoke this shared link? Anyone using it will immediately lose access.'))) {
                return;
            }

            btn.disabled = true;
            try {
                const res = await req(`/api/shares/${token}/revoke`, { method: 'POST' });
                if (res && res.success) {
                    toast(t('shares.revoked_success', 'Share link revoked successfully!'), 'success');
                    await fetchAndRenderShares();
                } else {
                    throw new Error('Revoke operation returned unsuccessful');
                }
            } catch (err) {
                console.error('Failed to revoke link:', err);
                toast(err.message || 'Failed to revoke link', 'error');
            } finally {
                btn.disabled = false;
            }
        });
    });
}

async function ensureQrCodeLib() {
    if (typeof QRCode !== 'undefined') return true;
    return new Promise((resolve) => {
        const script = document.createElement('script');
        script.src = '/vendor/qrcode.min.js';
        script.onload = () => resolve(true);
        script.onerror = () => resolve(false);
        document.head.appendChild(script);
    });
}

async function showQrDialog(url, title) {
    const overlay = document.getElementById('qr-dialog-overlay');
    const qrRender = document.getElementById('dialog-qr-render');
    const urlInput = document.getElementById('dialog-qr-url');
    const titleElem = document.getElementById('dialog-qr-title');

    titleElem.innerText = title || 'Shared Link';
    urlInput.value = url;
    qrRender.innerHTML = '';

    const loaded = await ensureQrCodeLib();
    if (loaded && typeof QRCode !== 'undefined') {
        new QRCode(qrRender, {
            text: url,
            width: 180,
            height: 180,
            colorDark: "#000000",
            colorLight: "#ffffff",
            correctLevel: QRCode.CorrectLevel.M
        });
    } else {
        qrRender.innerHTML = `<img src="https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=${encodeURIComponent(url)}" alt="QR Code" />`;
    }

    overlay.classList.add('active');
}

function setupEventListeners() {
    document.getElementById('refresh-shares-btn').addEventListener('click', () => fetchAndRenderShares());
    document.getElementById('shares-search-input').addEventListener('input', () => applyFilters());
    document.getElementById('shares-status-filter').addEventListener('change', () => applyFilters());
    document.getElementById('shares-type-filter').addEventListener('change', () => applyFilters());

    const qrOverlay = document.getElementById('qr-dialog-overlay');
    document.getElementById('qr-dialog-close').addEventListener('click', () => qrOverlay.classList.remove('active'));
    qrOverlay.addEventListener('click', (e) => {
        if (e.target === qrOverlay) qrOverlay.classList.remove('active');
    });

    document.getElementById('dialog-qr-copy-btn').addEventListener('click', () => {
        const urlInput = document.getElementById('dialog-qr-url');
        const text = urlInput ? urlInput.value : '';
        safeCopyText(text, urlInput);
    });
}

function safeCopyText(text, inputElem = null) {
    if (!text) return;
    if (inputElem) {
        try {
            inputElem.focus();
            inputElem.select();
            if (typeof inputElem.setSelectionRange === 'function') {
                inputElem.setSelectionRange(0, 99999);
            }
        } catch (_) {}
    }

    if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
        navigator.clipboard.writeText(text).then(() => {
            toast(t('shares.copied_to_clipboard', 'Link copied to clipboard!'), 'success');
        }).catch(() => {
            fallbackExecCopy(text, inputElem);
        });
    } else {
        fallbackExecCopy(text, inputElem);
    }
}

function fallbackExecCopy(text, inputElem = null) {
    try {
        let success = false;
        if (inputElem) {
            success = document.execCommand('copy');
        }
        if (!success) {
            const temp = document.createElement('textarea');
            temp.value = text;
            temp.style.position = 'fixed';
            temp.style.left = '-9999px';
            temp.style.top = '0';
            temp.setAttribute('readonly', '');
            document.body.appendChild(temp);
            temp.focus();
            temp.select();
            if (typeof temp.setSelectionRange === 'function') {
                temp.setSelectionRange(0, 99999);
            }
            success = document.execCommand('copy');
            document.body.removeChild(temp);
        }
        if (success) {
            toast(t('shares.copied_to_clipboard', 'Link copied to clipboard!'), 'success');
        } else {
            toast('Unable to copy automatically. Please copy the URL manually.', 'warning');
        }
    } catch (_) {
        toast('Unable to copy automatically. Please copy the URL manually.', 'warning');
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
