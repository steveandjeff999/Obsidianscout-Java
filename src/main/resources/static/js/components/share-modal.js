/**
 * Share Modal Component for ObsidianScout
 * Generates URLs with expiration, scope, and snapshot/live controls.
 */

import { request } from '../base/http.js';
import { showToast } from './toast.js';
import { t, applyTranslations } from '../base/i18n.js';

let modalOverlay = null;

export function initShareModal() {
    if (modalOverlay) return;

    // Inject CSS if not already present
    if (!document.getElementById('share-modal-css')) {
        const link = document.createElement('link');
        link.id = 'share-modal-css';
        link.rel = 'stylesheet';
        link.href = '/css/share-modal.css';
        document.head.appendChild(link);
    }

    modalOverlay = document.createElement('div');
    modalOverlay.className = 'share-modal-overlay';
    modalOverlay.id = 'obsidian-share-modal';
    modalOverlay.innerHTML = `
        <div class="share-modal-dialog">
            <div class="share-modal-header">
                <h3 class="share-modal-title">
                    <i class="fas fa-share-alt" style="color: var(--accent, #6366f1);"></i>
                    <span id="share-modal-title-text" data-i18n="share_modal.title">Share Data & Visuals</span>
                </h3>
                <button class="share-modal-close" id="share-modal-close-btn" title="Close">&times;</button>
            </div>
            
            <div class="share-modal-body" id="share-modal-body-config">
                <div class="share-field-group">
                    <label class="share-field-label" for="share-title-input" data-i18n="share_modal.link_title">Link Title</label>
                    <input type="text" id="share-title-input" class="share-input" placeholder="e.g., 2026 Midwest Regional Graphs" maxlength="120" />
                </div>

                <div class="share-field-group">
                    <label class="share-field-label" for="share-desc-input" data-i18n="share_modal.description">Description (Optional)</label>
                    <textarea id="share-desc-input" class="share-input" rows="2" placeholder="Notes for recipients..."></textarea>
                </div>

                <div class="share-field-group">
                    <label class="share-field-label" data-i18n="share_modal.access_scope">Access Scope</label>
                    <div class="share-radio-grid">
                        <div class="share-radio-card active" data-scope="public">
                            <div class="share-radio-title"><i class="fas fa-globe"></i> <span data-i18n="shares.scope_public">Public</span></div>
                            <div class="share-radio-desc" data-i18n="share_modal.scope_public_desc">Anyone with the shared link can view data.</div>
                        </div>
                        <div class="share-radio-card" data-scope="alliance">
                            <div class="share-radio-title"><i class="fas fa-users-gear"></i> <span data-i18n="shares.scope_alliance">Selected Team(s)</span></div>
                            <div class="share-radio-desc" data-i18n="share_modal.scope_alliance_desc">Restricted to specific comma-separated team numbers.</div>
                        </div>
                        <div class="share-radio-card" data-scope="pin">
                            <div class="share-radio-title"><i class="fas fa-lock"></i> <span data-i18n="shares.scope_pin">PIN Protected</span></div>
                            <div class="share-radio-desc" data-i18n="share_modal.scope_pin_desc">Securely hashed PIN using BCrypt with interactive PIN prompt modal on the shared viewer.</div>
                        </div>
                    </div>
                </div>

                <div class="share-field-group" id="share-scope-extra-alliance" style="display: none;">
                    <label class="share-field-label" for="share-allowed-teams" data-i18n="share_modal.allowed_teams">Allowed Team Numbers (comma-separated)</label>
                    <input type="text" id="share-allowed-teams" class="share-input" placeholder="e.g., 254, 1678, 1114" />
                </div>

                <div class="share-field-group" id="share-scope-extra-pin" style="display: none;">
                    <label class="share-field-label" for="share-pin-input" data-i18n="share_modal.pin_label">Set 4-8 Digit Access PIN</label>
                    <input type="password" id="share-pin-input" class="share-input" placeholder="Enter PIN code" maxlength="16" />
                </div>

                <div class="share-field-group">
                    <label class="share-field-label" for="share-expiry-select" data-i18n="share_modal.expiration">Link Expiration</label>
                    <select id="share-expiry-select" class="share-select">
                        <option value="1h" data-i18n="share_modal.exp_1h">1 Hour (Quick match review)</option>
                        <option value="24h" data-i18n="share_modal.exp_24h" selected>24 Hours</option>
                        <option value="7d" data-i18n="share_modal.exp_7d">7 Days (Event week)</option>
                        <option value="30d" data-i18n="share_modal.exp_30d">30 Days</option>
                        <option value="custom">Custom Date/Time...</option>
                        <option value="never" data-i18n="share_modal.exp_never">Never (Permanent until revoked)</option>
                    </select>
                </div>

                <div class="share-field-group" id="share-expiry-custom-group" style="display: none;">
                    <label class="share-field-label" for="share-expiry-custom">Custom Expiry Date & Time</label>
                    <input type="datetime-local" id="share-expiry-custom" class="share-input" />
                </div>

                <div class="share-field-group">
                    <label class="share-field-label" data-i18n="share_modal.data_mode">Data Mode</label>
                    <div class="share-radio-grid">
                        <div class="share-radio-card active" data-mode="live_query">
                            <div class="share-radio-title"><i class="fas fa-sync-alt"></i> <span data-i18n="share_modal.mode_live_title">Live Feed</span></div>
                            <div class="share-radio-desc" data-i18n="share_modal.mode_live_desc">Dynamic filtered data</div>
                        </div>
                        <div class="share-radio-card" data-mode="frozen_snapshot">
                            <div class="share-radio-title"><i class="fas fa-camera"></i> <span data-i18n="share_modal.mode_snapshot_title">Snapshot</span></div>
                            <div class="share-radio-desc" data-i18n="share_modal.mode_snapshot_desc">Freeze current data state</div>
                        </div>
                    </div>
                </div>

                <div class="share-toggle-row">
                    <div class="share-toggle-info">
                        <span class="share-toggle-title" data-i18n="share_modal.redact_notes">Redact Private Scout Notes</span>
                        <span class="share-toggle-desc" data-i18n="share_modal.redact_notes_desc">Strip tactical commentary for external viewers</span>
                    </div>
                    <input type="checkbox" id="share-redact-notes" checked style="width: 18px; height: 18px; accent-color: var(--accent, #6366f1);" />
                </div>

                <div class="share-toggle-row">
                    <div class="share-toggle-info">
                        <span class="share-toggle-title" data-i18n="share_modal.redact_scouts">Hide Scout Names</span>
                        <span class="share-toggle-desc" data-i18n="share_modal.redact_scouts_desc">Anonymize scout identities in data rows</span>
                    </div>
                    <input type="checkbox" id="share-redact-names" style="width: 18px; height: 18px; accent-color: var(--accent, #6366f1);" />
                </div>
            </div>

            <div class="share-modal-body" id="share-modal-body-result" style="display: none;">
                <div class="share-result-box">
                    <label class="share-field-label" data-i18n="share_modal.created_title">Shareable Link Ready!</label>
                    <div class="share-url-row">
                        <input type="text" id="share-result-url" class="share-input" readonly />
                        <button class="btn primary" id="share-copy-url-btn" type="button"><i class="fas fa-copy"></i> <span data-i18n="shares.copy_link">Copy</span></button>
                    </div>
                    <div class="share-qr-container" id="share-qr-box">
                        <div id="share-qr-render"></div>
                        <small style="color: #333; margin-top: 6px; font-weight: 600;">Scan to open in App or Web</small>
                    </div>
                </div>
            </div>

            <div class="share-modal-footer">
                <button class="btn ghost" id="share-cancel-btn" type="button">Cancel</button>
                <button class="btn primary" id="share-generate-btn" type="button">
                    <i class="fas fa-link"></i> <span data-i18n="share_modal.btn_create">Generate Shared URL</span>
                </button>
                <button class="btn primary" id="share-done-btn" type="button" style="display: none;" data-i18n="share_modal.btn_done">Done</button>
            </div>
        </div>
    `;

    applyTranslations(modalOverlay);

    document.body.appendChild(modalOverlay);
    bindModalEvents();
}

let currentPayloadProvider = null;

function bindModalEvents() {
    const closeBtn = document.getElementById('share-modal-close-btn');
    const cancelBtn = document.getElementById('share-cancel-btn');
    const doneBtn = document.getElementById('share-done-btn');
    const generateBtn = document.getElementById('share-generate-btn');
    const copyBtn = document.getElementById('share-copy-url-btn');
    const expirySelect = document.getElementById('share-expiry-select');
    const customExpiryGroup = document.getElementById('share-expiry-custom-group');
    const scopeCards = modalOverlay.querySelectorAll('[data-scope]');
    const modeCards = modalOverlay.querySelectorAll('[data-mode]');

    const close = () => {
        modalOverlay.classList.remove('active');
    };

    closeBtn.addEventListener('click', close);
    cancelBtn.addEventListener('click', close);
    doneBtn.addEventListener('click', close);
    modalOverlay.addEventListener('click', (e) => {
        if (e.target === modalOverlay) close();
    });

    // Scope Card Switching
    scopeCards.forEach(card => {
        card.addEventListener('click', () => {
            scopeCards.forEach(c => c.classList.remove('active'));
            card.classList.add('active');
            const scope = card.dataset.scope;
            document.getElementById('share-scope-extra-alliance').style.display = scope === 'alliance' ? 'flex' : 'none';
            document.getElementById('share-scope-extra-pin').style.display = scope === 'pin' ? 'flex' : 'none';
        });
    });

    // Mode Card Switching
    modeCards.forEach(card => {
        card.addEventListener('click', () => {
            modeCards.forEach(c => c.classList.remove('active'));
            card.classList.add('active');
        });
    });

    // Expiry change
    expirySelect.addEventListener('change', () => {
        customExpiryGroup.style.display = expirySelect.value === 'custom' ? 'flex' : 'none';
    });

    // Copy URL
    copyBtn.addEventListener('click', () => {
        const urlInput = document.getElementById('share-result-url');
        const text = urlInput ? urlInput.value : '';
        safeCopyText(text, urlInput);
    });

    // Generate Share Link
    generateBtn.addEventListener('click', async () => {
        if (!currentPayloadProvider) {
            showToast('No data payload available to share', 'error');
            return;
        }

        const title = document.getElementById('share-title-input').value.trim() || 'Shared Scouting View';
        const description = document.getElementById('share-desc-input').value.trim() || null;
        const selectedScopeCard = modalOverlay.querySelector('[data-scope].active');
        const scope = selectedScopeCard ? selectedScopeCard.dataset.scope : 'public';
        const allowedTeams = scope === 'alliance' ? document.getElementById('share-allowed-teams').value.trim() : null;
        const pin = scope === 'pin' ? document.getElementById('share-pin-input').value.trim() : null;
        const selectedModeCard = modalOverlay.querySelector('[data-mode].active');
        const shareMode = selectedModeCard ? selectedModeCard.dataset.mode : 'live_query';
        const redactNotes = document.getElementById('share-redact-notes').checked;
        const redactNames = document.getElementById('share-redact-names').checked;

        if (scope === 'pin' && (!pin || pin.length < 4)) {
            showToast('Please enter a PIN of at least 4 digits', 'warning');
            return;
        }

        // Calculate expiresAt ISO
        const expiryChoice = expirySelect.value;
        let expiresAt = null;
        const now = new Date();
        if (expiryChoice === '1h') {
            expiresAt = new Date(now.getTime() + 60 * 60 * 1000).toISOString();
        } else if (expiryChoice === '24h') {
            expiresAt = new Date(now.getTime() + 24 * 60 * 60 * 1000).toISOString();
        } else if (expiryChoice === '7d') {
            expiresAt = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000).toISOString();
        } else if (expiryChoice === '30d') {
            expiresAt = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000).toISOString();
        } else if (expiryChoice === 'custom') {
            const customVal = document.getElementById('share-expiry-custom').value;
            if (!customVal) {
                showToast('Please pick a custom expiration date and time', 'warning');
                return;
            }
            expiresAt = new Date(customVal).toISOString();
        }

        generateBtn.disabled = true;
        generateBtn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Creating Link...';

        try {
            const payload = typeof currentPayloadProvider === 'function' ? await currentPayloadProvider() : currentPayloadProvider;

            const reqBody = {
                title,
                description,
                resourceType: payload.resourceType || 'graph',
                targetEventKey: payload.targetEventKey || null,
                shareMode,
                queryConfigJson: JSON.stringify(payload.queryConfig || {}),
                snapshotDataJson: shareMode === 'frozen_snapshot' ? JSON.stringify(payload.snapshotData || {}) : null,
                accessScope: scope,
                allowedTeams,
                pin,
                redactPrivateNotes: redactNotes,
                redactScoutNames: redactNames,
                expiresAt
            };

            const res = await request('/api/shares', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(reqBody)
            });

            if (!res || !res.token) {
                throw new Error('Failed to create shared link');
            }

            const fullUrl = `${window.location.origin}/shared/${res.token}`;
            document.getElementById('share-result-url').value = fullUrl;

            // Render QR Code using local QRCode library
            const qrContainer = document.getElementById('share-qr-render');
            await renderQrCodeToContainer(qrContainer, fullUrl, 180);

            document.getElementById('share-modal-body-config').style.display = 'none';
            document.getElementById('share-modal-body-result').style.display = 'flex';
            generateBtn.style.display = 'none';
            cancelBtn.style.display = 'none';
            doneBtn.style.display = 'inline-flex';

            showToast('Share link created successfully!', 'success');
        } catch (err) {
            console.error('Error creating share link:', err);
            showToast(err.message || 'Failed to create share link', 'error');
        } finally {
            generateBtn.disabled = false;
            generateBtn.innerHTML = '<i class="fas fa-link"></i> Generate Shared URL';
        }
    });
}

/**
 * Opens the share modal configured for the calling page.
 * @param {Object} options
 * @param {string} options.defaultTitle
 * @param {string} options.resourceType (graph, predictor, event_predictor, all_data, qual_data, pit_data, match_data, custom_analytics, match_planning)
 * @param {string} [options.eventKey]
 * @param {Function|Object} options.payloadProvider returns { queryConfig, snapshotData, resourceType, targetEventKey }
 */
export function openShareModal({ defaultTitle, resourceType, eventKey, payloadProvider }) {
    initShareModal();
    currentPayloadProvider = payloadProvider;

    document.getElementById('share-title-input').value = defaultTitle || `ObsidianScout ${resourceType.toUpperCase()} Share`;
    document.getElementById('share-desc-input').value = '';
    document.getElementById('share-modal-body-config').style.display = 'flex';
    document.getElementById('share-modal-body-result').style.display = 'none';
    document.getElementById('share-generate-btn').style.display = 'inline-flex';
    document.getElementById('share-cancel-btn').style.display = 'inline-flex';
    document.getElementById('share-done-btn').style.display = 'none';

    modalOverlay.classList.add('active');
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
            showToast(t('shares.copied_to_clipboard', 'Link copied to clipboard!'), 'success');
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
            showToast(t('shares.copied_to_clipboard', 'Link copied to clipboard!'), 'success');
        } else {
            showToast('Unable to copy automatically. Please copy the URL manually.', 'warning');
        }
    } catch (_) {
        showToast('Unable to copy automatically. Please copy the URL manually.', 'warning');
    }
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

async function renderQrCodeToContainer(container, text, size = 180) {
    if (!container) return;
    container.innerHTML = '';
    const loaded = await ensureQrCodeLib();
    if (loaded && typeof QRCode !== 'undefined') {
        new QRCode(container, {
            text: text,
            width: size,
            height: size,
            colorDark: "#000000",
            colorLight: "#ffffff",
            correctLevel: QRCode.CorrectLevel.M
        });
    } else {
        container.innerHTML = `<img src="https://api.qrserver.com/v1/create-qr-code/?size=${size}x${size}&data=${encodeURIComponent(text)}" alt="Share QR Code" />`;
    }
}

