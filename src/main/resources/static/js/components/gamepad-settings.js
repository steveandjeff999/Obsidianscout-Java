/**
 * ObsidianScout Controller & Gamepad Settings Component
 * Complete Web UI matching the Flutter App layout, rules, HUD, and cards.
 * Self-contained SVG icons for 100% offline & arena reliability.
 */
(function(window) {
    'use strict';

    const ICONS = {
        gamepad: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><line x1="6" y1="12" x2="10" y2="12"></line><line x1="8" y1="10" x2="8" y2="14"></line><line x1="15" y1="13" x2="15.01" y2="13"></line><line x1="18" y1="11" x2="18.01" y2="11"></line><rect x="2" y="6" width="20" height="12" rx="6"></rect></svg>',
        playstation: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><circle cx="12" cy="12" r="10"></circle><polygon points="12 8 8 16 16 16" fill="none" stroke="currentColor" stroke-width="1.8"></polygon></svg>',
        keyboard: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><rect x="2" y="4" width="20" height="16" rx="2"></rect><line x1="6" y1="8" x2="6.01" y2="8"></line><line x1="10" y1="8" x2="10.01" y2="8"></line><line x1="14" y1="8" x2="14.01" y2="8"></line><line x1="18" y1="8" x2="18.01" y2="8"></line><line x1="6" y1="12" x2="6.01" y2="12"></line><line x1="10" y1="12" x2="10.01" y2="12"></line><line x1="14" y1="12" x2="14.01" y2="12"></line><line x1="18" y1="12" x2="18.01" y2="12"></line><line x1="7" y1="16" x2="17" y2="16"></line></svg>',
        plus: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>',
        edit: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>',
        copy: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>',
        export: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>',
        import: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>',
        trash: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path><line x1="10" y1="11" x2="10" y2="17"></line><line x1="14" y1="11" x2="14" y2="17"></line></svg>',
        save: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"></path><polyline points="17 21 17 13 7 13 7 21"></polyline><polyline points="7 3 7 8 15 8"></polyline></svg>',
        wand: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><path d="M15 4V2"></path><path d="M15 16v-2"></path><path d="M8 9h2"></path><path d="M20 9h2"></path><path d="M17.8 11.8 19 13"></path><path d="M15 9h0"></path><path d="M17.8 6.2 19 5"></path><path d="m3 21 9-9"></path><path d="M12.2 6.2 11 5"></path></svg>',
        refresh: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><polyline points="23 4 23 10 17 10"></polyline><polyline points="1 20 1 14 7 14"></polyline><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg>',
        sliders: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><line x1="4" y1="21" x2="4" y2="14"></line><line x1="4" y1="10" x2="4" y2="3"></line><line x1="12" y1="21" x2="12" y2="12"></line><line x1="12" y1="8" x2="12" y2="3"></line><line x1="20" y1="21" x2="20" y2="16"></line><line x1="20" y1="12" x2="20" y2="3"></line><line x1="1" y1="14" x2="7" y2="14"></line><line x1="9" y1="8" x2="15" y2="8"></line><line x1="17" y1="16" x2="23" y2="16"></line></svg>',
        eye: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path><circle cx="12" cy="12" r="3"></circle></svg>',
        eyeOff: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"></path><line x1="1" y1="1" x2="23" y2="23"></line></svg>',
        chevronDown: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><polyline points="6 9 12 15 18 9"></polyline></svg>',
        chevronUp: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><polyline points="18 15 12 9 6 15"></polyline></svg>',
        vibrate: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><path d="M2 8v8"></path><path d="M22 8v8"></path><path d="M6 5v14"></path><path d="M18 5v14"></path><rect x="10" y="3" width="4" height="18" rx="2"></rect></svg>',
        play: '<svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor" style="vertical-align: middle;"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg>',
        warning: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>',
        info: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>',
        check: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="vertical-align: middle;"><polyline points="20 6 9 17 4 12"></polyline></svg>',
        dish: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><path d="M4 11a9 9 0 0 1 9 9"></path><path d="M4 4a16 16 0 0 1 16 16"></path><circle cx="5" cy="19" r="1"></circle></svg>',
        pointer: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><path d="M3 3l7.07 16.97 2.51-7.39 7.39-2.51L3 3z"></path></svg>',
        toggle: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><rect x="1" y="5" width="22" height="14" rx="7" ry="7"></rect><circle cx="16" cy="12" r="3"></circle></svg>',
        cycle: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: middle;"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"></path></svg>',
        arrows: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align: middle;"><path d="M8 3 4 7l4 4"/><path d="M4 7h16"/><path d="m16 21 4-4-4-4"/><path d="M20 17H4"/></svg>'
    };

    function getFieldLabel(field) {
        if (!field) return '';
        if (typeof field.label === 'string') return field.label;
        if (field.label && typeof field.label === 'object') {
            return field.label.en || Object.values(field.label)[0] || field.id || '';
        }
        return field.id || '';
    }

    class GamepadSettingsUI {
        constructor() {
            this.activeContainer = null;
            this.modalBackdrop = null;
            this.selectedPhaseFilter = 'all';
            this.activeConfig = null;
            this.showLiveTester = false;
            this.hudRaf = null;
            this._boundStateChange = this.onGamepadStateChange.bind(this);
        }

        async init() {
            if (window.GamepadService) {
                window.GamepadService.onStateChanged(this._boundStateChange);
            }
        }

        onGamepadStateChange() {
            this.refreshHardwareCard();
            this.refreshProfilesList();
        }

        /**
         * Renders the complete settings UI into a target DOM container.
         * @param {HTMLElement|string} container - DOM element or selector
         * @param {Object} [options] - Options (e.g. config: activeFormConfig)
         */
        async render(container, options = {}) {
            const target = typeof container === 'string' ? document.querySelector(container) : container;
            if (!target) return;
            this.activeContainer = target;
            this.activeConfig = options.config || this.activeConfig || window.activeFormConfig || null;

            if (!this.activeConfig) {
                this.activeConfig = await this.fetchActiveConfig();
            }

            target.innerHTML = this.buildHTML();
            this.wireEvents(target);
            this.wireProfilesListEvents(target);
            this.renderBindingsTable(target);
            this.refreshHardwareCard(target);
            this.startHudLoop();
        }

        /**
         * Opens the Gamepad Settings as a modal popup.
         * @param {Object} [options] - Options (e.g. config: activeFormConfig)
         */
        async openModal(options = {}) {
            this.activeConfig = options.config || this.activeConfig || window.activeFormConfig || null;
            if (this.modalBackdrop && this.modalBackdrop.parentElement) {
                this.modalBackdrop.parentElement.removeChild(this.modalBackdrop);
            }

            const backdrop = document.createElement('div');
            backdrop.className = 'modal-backdrop show';
            backdrop.style.zIndex = '100050';

            const container = document.createElement('div');
            container.className = 'modal-container gamepad-modal-dialog';

            backdrop.appendChild(container);
            document.body.appendChild(backdrop);
            this.modalBackdrop = backdrop;

            await this.render(container, options);

            // Add modal close button in top header
            const headerRow = container.querySelector('.gamepad-app-header');
            if (headerRow) {
                const closeBtn = document.createElement('button');
                closeBtn.type = 'button';
                closeBtn.className = 'modal-close';
                closeBtn.innerHTML = '&times;';
                closeBtn.setAttribute('aria-label', 'Close dialog');
                closeBtn.addEventListener('click', () => this.closeModal());
                headerRow.appendChild(closeBtn);
            }

            backdrop.addEventListener('click', (e) => {
                if (e.target === backdrop) this.closeModal();
            });
        }

        closeModal() {
            this.stopHudLoop();
            if (this.modalBackdrop && this.modalBackdrop.parentElement) {
                this.modalBackdrop.parentElement.removeChild(this.modalBackdrop);
                this.modalBackdrop = null;
            }
        }

        buildHTML() {
            const service = window.GamepadService;
            const profile = service ? (typeof service.getActiveProfile === 'function' ? service.getActiveProfile() : service.activeProfile) : null;
            const isEnabled = profile ? profile.enabled : true;
            const showTooltips = profile ? (profile.showTooltips !== false) : true;
            const hapticEnabled = profile ? (profile.hapticEnabled !== false) : true;
            const hapticStrength = profile ? (profile.hapticStrength !== undefined ? profile.hapticStrength : 1.0) : 1.0;
            const controllerType = profile ? (profile.controllerType || 'xbox') : 'xbox';

            return `
                <div class="gamepad-settings-view">
                    <!-- Top Bar Header -->
                    <div class="gamepad-app-header">
                        <h2 class="gamepad-app-title">
                            <span style="color: var(--accent, #6366f1); display: inline-flex; align-items: center;">${ICONS.gamepad}</span>
                            <span>Gamepad &amp; Controller Setup</span>
                        </h2>
                        <div class="gamepad-header-actions" style="display: flex; gap: 8px; align-items: center;">
                            <button type="button" id="gamepad-btn-save-profile" class="btn" style="padding: 6px 14px; font-size: 0.82rem; font-weight: 700; display: inline-flex; align-items: center; gap: 6px;" title="Save profile to database">
                                ${ICONS.save} <span>Save Profile</span>
                            </button>
                            <button type="button" id="gamepad-btn-refresh-gamepads" class="btn secondary" style="padding: 6px 12px; font-size: 0.82rem; display: inline-flex; align-items: center; gap: 6px;" title="Refresh connected gamepads and profiles">
                                ${ICONS.refresh} <span>Refresh</span>
                            </button>
                        </div>
                    </div>

                    <!-- CARD 1: Controller Hardware Status Card -->
                    <div class="gamepad-card">
                        <div class="gamepad-hardware-header">
                            <div id="gamepad-hardware-icon" class="gamepad-hardware-icon-box">
                                ${ICONS.gamepad}
                            </div>
                            <div class="gamepad-hardware-text">
                                <h3 class="gamepad-hardware-title">Hardware Status</h3>
                                <p id="gamepad-hardware-subtitle" class="gamepad-hardware-subtitle">
                                    Detecting controller...
                                </p>
                            </div>
                            <div>
                                <label style="cursor: pointer; display: flex; align-items: center;">
                                    <input type="checkbox" id="gamepad-toggle-master-enable" ${isEnabled ? 'checked' : ''} style="width: 20px; height: 20px; cursor: pointer; accent-color: var(--accent, #6366f1);" />
                                </label>
                            </div>
                        </div>

                        <div style="border-top: 1px solid var(--border-color, rgba(0,0,0,0.06)); margin: 16px 0 12px 0;"></div>

                        <!-- Button Icon Style Selector (Xbox / PS4 / Keyboard) -->
                        <div>
                            <div class="gamepad-switch-title">Button Icon Style</div>
                            <p class="gamepad-switch-sub">Select A/B/X/Y (Xbox), ✕/○/□/△ (PS4/PS5), or Keyboard</p>
                            <div class="gamepad-segmented-group" id="gamepad-type-segmented">
                                <button type="button" class="gamepad-segment-btn ${controllerType === 'xbox' ? 'active' : ''}" data-type="xbox">
                                    ${ICONS.gamepad} <span>Xbox</span>
                                </button>
                                <button type="button" class="gamepad-segment-btn ${controllerType === 'playstation' ? 'active' : ''}" data-type="playstation">
                                    ${ICONS.playstation} <span>PS4 / PS5</span>
                                </button>
                                <button type="button" class="gamepad-segment-btn ${controllerType === 'keyboard' ? 'active' : ''}" data-type="keyboard">
                                    ${ICONS.keyboard} <span>Keyboard</span>
                                </button>
                            </div>
                        </div>

                        <div style="margin-top: 14px;"></div>

                        <!-- Tooltips Switch Row -->
                        <div class="gamepad-switch-row">
                            <div class="gamepad-switch-text">
                                <div class="gamepad-switch-title">Show Tooltips on Scouting Form</div>
                                <p class="gamepad-switch-sub">Displays button badges like [RT] or [A] next to form counters</p>
                            </div>
                            <input type="checkbox" id="gamepad-toggle-tooltips" ${showTooltips ? 'checked' : ''} style="width: 18px; height: 18px; cursor: pointer; accent-color: var(--accent, #6366f1);" />
                        </div>

                        <div style="border-top: 1px solid var(--border-color, rgba(0,0,0,0.06)); margin: 8px 0 12px 0;"></div>

                        <!-- Rumble & Haptics Switch Row -->
                        <div class="gamepad-switch-row">
                            <div class="gamepad-switch-text">
                                <div class="gamepad-switch-title">Controller Rumble &amp; Haptics</div>
                                <p class="gamepad-switch-sub">Vibrates controller on button taps, rapid fire, and actions</p>
                            </div>
                            <input type="checkbox" id="gamepad-toggle-haptics" ${hapticEnabled ? 'checked' : ''} style="width: 18px; height: 18px; cursor: pointer; accent-color: var(--accent, #6366f1);" />
                        </div>

                        <!-- Expandable Rumble Strength Box -->
                        <div id="gamepad-rumble-container" class="gamepad-rumble-box" style="${hapticEnabled ? '' : 'display:none;'}">
                            <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px;">
                                <div style="display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 0.88rem;">
                                    <span style="color: var(--accent, #6366f1); display: inline-flex; align-items: center;">${ICONS.vibrate}</span>
                                    <span>Rumble Strength</span>
                                </div>
                                <span id="gamepad-rumble-percent-badge" class="gamepad-strength-badge">${Math.round(hapticStrength * 100)}%</span>
                            </div>
                            <div style="display: flex; align-items: center; gap: 12px;">
                                <input type="range" id="gamepad-slider-rumble" min="0.05" max="1.0" step="0.05" value="${hapticStrength}" style="flex: 1; accent-color: var(--accent, #6366f1); cursor: pointer;" />
                                <button type="button" id="gamepad-btn-test-rumble" class="btn secondary" style="padding: 5px 12px; font-size: 0.82rem; white-space: nowrap; display: inline-flex; align-items: center; gap: 6px;">
                                    ${ICONS.play} <span>Test</span>
                                </button>
                            </div>
                        </div>
                    </div>

                    <!-- CARD 2: Controller Profiles List & Configuration Card -->
                    <div class="gamepad-card">
                        <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px;">
                            <div style="display: flex; align-items: center; gap: 8px;">
                                <span style="color: var(--accent, #6366f1); display: inline-flex; align-items: center;">${ICONS.sliders}</span>
                                <h3 style="margin: 0; font-size: 1rem; font-weight: 700;" id="gamepad-profile-count-title">
                                    Controller Profiles (${service?.getProfiles ? service.getProfiles().length : 1})
                                </h3>
                            </div>
                            <div style="display: flex; align-items: center; gap: 6px;">
                                <button type="button" id="gamepad-btn-save-card" class="btn" style="padding: 5px 12px; font-size: 0.8rem; font-weight: 700; display: inline-flex; align-items: center; gap: 6px;" title="Save profile to database">
                                    ${ICONS.save} <span>Save</span>
                                </button>
                                <button type="button" id="gamepad-btn-new-profile" class="btn secondary" style="padding: 5px 12px; font-size: 0.8rem; font-weight: 600; display: inline-flex; align-items: center; gap: 6px;" title="Create new profile">
                                    ${ICONS.plus} <span>New Profile</span>
                                </button>
                            </div>
                        </div>

                        <!-- Profile List -->
                        <div id="gamepad-profiles-list" class="gamepad-profiles-list">
                            ${this.renderProfilesList()}
                        </div>

                        <!-- Auto-Generate Smart Layout Button (Full Width) -->
                        <div style="margin-bottom: 10px;">
                            <button type="button" id="gamepad-btn-auto-gen" class="btn" style="width: 100%; padding: 10px 16px; font-size: 0.92rem; font-weight: 700; display: flex; align-items: center; justify-content: center; gap: 8px;" title="Auto-map buttons based on current match config">
                                ${ICONS.wand} <span>Auto-Generate Smart Layout</span>
                            </button>
                        </div>

                        <!-- Export & Import Buttons Bar -->
                        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px;">
                            <button type="button" id="gamepad-btn-export-config" class="btn secondary" style="padding: 8px 14px; font-size: 0.85rem; display: inline-flex; align-items: center; justify-content: center; gap: 6px;">
                                ${ICONS.export} <span>Export Active</span>
                            </button>
                            <button type="button" id="gamepad-btn-import-config" class="btn secondary" style="padding: 8px 14px; font-size: 0.85rem; display: inline-flex; align-items: center; justify-content: center; gap: 6px;">
                                ${ICONS.import} <span>Import Config</span>
                            </button>
                        </div>
                    </div>

                    <!-- CARD 3: Live Controller Input HUD (Collapsible) -->
                    <div class="gamepad-card">
                        <div id="gamepad-hud-toggle-btn" class="gamepad-hud-toggle">
                            <h3 class="gamepad-hud-title">
                                <span id="gamepad-hud-icon" style="color: #38bdf8; display: inline-flex; align-items: center;">${this.showLiveTester ? ICONS.eyeOff : ICONS.eye}</span>
                                <span>Live Controller Input HUD</span>
                            </h3>
                            <span id="gamepad-hud-chevron" style="color: var(--muted, #64748b); display: inline-flex; align-items: center;">${this.showLiveTester ? ICONS.chevronUp : ICONS.chevronDown}</span>
                        </div>

                        <div id="gamepad-hud-body" style="${this.showLiveTester ? '' : 'display:none;'} margin-top: 12px;">
                            <p class="notice" style="margin: 0 0 12px 0; font-size: 0.82rem;">
                                Press any button or pull analog triggers on your controller to test hardware response in real-time.
                            </p>

                            <!-- Analog Trigger Gauges -->
                            <div class="gamepad-trigger-gauges-row">
                                <div class="gamepad-trigger-gauge-box">
                                    <div style="display: flex; justify-content: space-between; font-size: 0.82rem; font-weight: 700;">
                                        <span id="gamepad-gauge-label-l">${controllerType === 'playstation' ? 'L2 Trigger' : 'LT Trigger'}</span>
                                        <span id="gamepad-gauge-val-l" style="color: var(--accent, #6366f1);">0%</span>
                                    </div>
                                    <div class="gamepad-trigger-bar-bg">
                                        <div id="gamepad-gauge-fill-l" class="gamepad-trigger-bar-fill"></div>
                                    </div>
                                </div>

                                <div class="gamepad-trigger-gauge-box">
                                    <div style="display: flex; justify-content: space-between; font-size: 0.82rem; font-weight: 700;">
                                        <span id="gamepad-gauge-label-r">${controllerType === 'playstation' ? 'R2 Trigger' : 'RT Trigger'}</span>
                                        <span id="gamepad-gauge-val-r" style="color: var(--accent, #6366f1);">0%</span>
                                    </div>
                                    <div class="gamepad-trigger-bar-bg">
                                        <div id="gamepad-gauge-fill-r" class="gamepad-trigger-bar-fill"></div>
                                    </div>
                                </div>
                            </div>

                            <!-- Live Button Chips -->
                            <div class="gamepad-hud-chips-wrap">
                                ${this.renderHudChips(controllerType)}
                            </div>
                        </div>
                    </div>

                    <!-- CARD 4: Configured Mappings Section -->
                    <div class="gamepad-card">
                        <div style="display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px;">
                            <h3 style="margin: 0; font-size: 1rem; font-weight: 700;" id="gamepad-mappings-count-title">
                                Configured Mappings (${profile?.bindings?.length || 0})
                            </h3>
                            <button type="button" id="gamepad-btn-add-binding" class="btn" style="padding: 6px 14px; font-size: 0.82rem; font-weight: 700; display: inline-flex; align-items: center; gap: 6px;">
                                ${ICONS.plus} <span>Add Mapping</span>
                            </button>
                        </div>

                        <!-- Phase Filter Chips -->
                        <div class="gamepad-filter-chips">
                            <button type="button" class="gamepad-filter-chip ${this.selectedPhaseFilter === 'all' ? 'active' : ''}" data-phase="all">All Phases</button>
                            <button type="button" class="gamepad-filter-chip ${this.selectedPhaseFilter === 'global' ? 'active' : ''}" data-phase="global">Global</button>
                            <button type="button" class="gamepad-filter-chip ${this.selectedPhaseFilter === 'auto' ? 'active' : ''}" data-phase="auto">Auto</button>
                            <button type="button" class="gamepad-filter-chip ${this.selectedPhaseFilter === 'teleop' ? 'active' : ''}" data-phase="teleop">Teleop</button>
                            <button type="button" class="gamepad-filter-chip ${this.selectedPhaseFilter === 'endgame' ? 'active' : ''}" data-phase="endgame">Endgame</button>
                            <button type="button" class="gamepad-filter-chip ${this.selectedPhaseFilter === 'postmatch' ? 'active' : ''}" data-phase="postmatch">Post Match</button>
                        </div>

                        <!-- Table / Cards Wrap -->
                        <div class="gamepad-bindings-table-wrap">
                            <table class="gamepad-bindings-table">
                                <thead>
                                    <tr>
                                        <th>Phase</th>
                                        <th>Target / Field</th>
                                        <th>Action</th>
                                        <th>Button / Key</th>
                                        <th>Trigger Mode</th>
                                        <th style="text-align: right;">Actions</th>
                                    </tr>
                                </thead>
                                <tbody id="gamepad-bindings-tbody">
                                    <!-- Populated dynamically -->
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            `;
        }

        renderHudChips(controllerType) {
            const buttons = [
                { key: 'button_a', xbox: 'A', ps: '✕', name: 'A / Cross' },
                { key: 'button_b', xbox: 'B', ps: '○', name: 'B / Circle' },
                { key: 'button_x', xbox: 'X', ps: '□', name: 'X / Square' },
                { key: 'button_y', xbox: 'Y', ps: '△', name: 'Y / Triangle' },
                { key: 'shoulder_l', xbox: 'LB', ps: 'L1', name: 'Left Bumper' },
                { key: 'shoulder_r', xbox: 'RB', ps: 'R1', name: 'Right Bumper' },
                { key: 'dpad_up', xbox: 'D-Up', ps: 'D-Up', name: 'D-Pad Up' },
                { key: 'dpad_down', xbox: 'D-Down', ps: 'D-Down', name: 'D-Pad Down' },
                { key: 'dpad_left', xbox: 'D-Left', ps: 'D-Left', name: 'D-Pad Left' },
                { key: 'dpad_right', xbox: 'D-Right', ps: 'D-Right', name: 'D-Pad Right' },
                { key: 'thumb_l', xbox: 'LS', ps: 'L3', name: 'Left Stick Click' },
                { key: 'thumb_r', xbox: 'RS', ps: 'R3', name: 'Right Stick Click' },
                { key: 'button_start', xbox: 'Menu', ps: 'Options', name: 'Start / Menu' },
                { key: 'button_back', xbox: 'View', ps: 'Share', name: 'Back / View' }
            ];

            return buttons.map(b => {
                const label = controllerType === 'playstation' ? b.ps : (controllerType === 'keyboard' ? b.xbox : b.xbox);
                return `
                    <div id="hud-chip-${b.key}" class="gamepad-hud-chip" data-key="${b.key}">
                        ${label} (${b.name})
                    </div>
                `;
            }).join('');
        }

        renderProfilesList() {
            const service = window.GamepadService;
            const profiles = service ? (typeof service.getProfiles === 'function' ? service.getProfiles() : (service.profiles || [])) : [];
            const active = service ? (typeof service.getActiveProfile === 'function' ? service.getActiveProfile() : service.activeProfile) : null;

            if (!profiles.length) {
                return '<p class="notice" style="margin: 0; font-size: 0.82rem;">No profiles available.</p>';
            }

            return profiles.map(p => {
                const isActive = active && active.id === p.id;
                const cType = p.controllerType || 'xbox';
                const iconSvg = cType === 'playstation' ? ICONS.playstation : (cType === 'keyboard' ? ICONS.keyboard : ICONS.gamepad);
                const typeLabel = cType === 'playstation' ? 'PS4 / PS5' : (cType === 'keyboard' ? 'Keyboard' : 'Xbox');
                const bindingCount = p.bindings?.length || 0;

                return `
                    <div class="gamepad-profile-item ${isActive ? 'active' : ''}" data-id="${p.id}">
                        <div class="gamepad-profile-item-main">
                            <div class="gamepad-profile-icon-box ${isActive ? 'active' : ''}">
                                ${iconSvg}
                            </div>
                            <div class="gamepad-profile-info">
                                <div style="display: flex; align-items: center; gap: 6px;">
                                    <span class="gamepad-profile-name">${p.name || 'Untitled Profile'}</span>
                                    ${isActive ? '<span class="badge badge-phase-endgame" style="font-size: 0.65rem; padding: 2px 6px; font-weight: 700;">ACTIVE</span>' : ''}
                                </div>
                                <div class="gamepad-profile-meta">
                                    <span>${typeLabel}</span>
                                    <span>•</span>
                                    <span>${bindingCount} binding${bindingCount === 1 ? '' : 's'}</span>
                                </div>
                            </div>
                        </div>
                        <div class="gamepad-profile-actions">
                            ${!isActive ? `<button type="button" class="btn secondary btn-use-profile" data-id="${p.id}" style="padding: 5px 12px; font-size: 0.8rem; font-weight: 600;">Use</button>` : ''}
                            <button type="button" class="btn secondary btn-rename-profile" data-id="${p.id}" title="Rename profile">
                                ${ICONS.edit} <span>Rename</span>
                            </button>
                            <button type="button" class="btn secondary btn-dup-profile" data-id="${p.id}" title="Duplicate profile">
                                ${ICONS.copy} <span>Copy</span>
                            </button>
                            <button type="button" class="btn secondary btn-export-profile-item" data-id="${p.id}" title="Export profile JSON">
                                ${ICONS.export} <span>Export</span>
                            </button>
                            ${profiles.length > 1 ? `
                                <button type="button" class="btn btn-del-binding btn-del-profile-item" data-id="${p.id}" title="Delete profile">
                                    ${ICONS.trash} <span>Delete</span>
                                </button>
                            ` : ''}
                        </div>
                    </div>
                `;
            }).join('');
        }

        wireProfilesListEvents(root = this.activeContainer || document) {
            const service = window.GamepadService;
            if (!service) return;

            // Use / Switch Profile
            root.querySelectorAll('.btn-use-profile').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const id = btn.dataset.id;
                    service.setActiveProfile(id);
                    this.render(this.activeContainer, { config: this.activeConfig });
                });
            });

            // Rename Profile
            root.querySelectorAll('.btn-rename-profile').forEach(btn => {
                btn.addEventListener('click', async (e) => {
                    e.stopPropagation();
                    const id = btn.dataset.id;
                    const p = (service.getProfiles ? service.getProfiles() : []).find(x => x.id === id);
                    if (!p) return;
                    const newName = prompt('Enter new profile name:', p.name || 'Custom Layout');
                    if (!newName || !newName.trim()) return;
                    p.name = newName.trim();
                    p.updatedAt = new Date().toISOString();
                    await service.saveProfile(p);
                    this.render(this.activeContainer, { config: this.activeConfig });
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast('Profile renamed!', 'success');
                    }
                });
            });

            // Duplicate Profile
            root.querySelectorAll('.btn-dup-profile').forEach(btn => {
                btn.addEventListener('click', async (e) => {
                    e.stopPropagation();
                    const id = btn.dataset.id;
                    const p = (service.getProfiles ? service.getProfiles() : []).find(x => x.id === id);
                    if (!p) return;
                    const dup = JSON.parse(JSON.stringify(p));
                    dup.id = `profile_${Date.now()}`;
                    dup.name = `${p.name} (Copy)`;
                    dup.updatedAt = new Date().toISOString();
                    await service.saveProfile(dup);
                    service.setActiveProfile(dup.id);
                    this.render(this.activeContainer, { config: this.activeConfig });
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast('Profile duplicated!', 'success');
                    }
                });
            });

            // Export Single Profile
            root.querySelectorAll('.btn-export-profile-item').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const id = btn.dataset.id;
                    const p = (service.getProfiles ? service.getProfiles() : []).find(x => x.id === id);
                    if (p) this.showExportModal(p);
                });
            });

            // Delete Profile
            root.querySelectorAll('.btn-del-profile-item').forEach(btn => {
                btn.addEventListener('click', async (e) => {
                    e.stopPropagation();
                    const id = btn.dataset.id;
                    const profiles = service.getProfiles ? service.getProfiles() : [];
                    if (profiles.length <= 1) {
                        if (window.Obsidianscout && Obsidianscout.showToast) {
                            Obsidianscout.showToast('Cannot delete the only remaining profile.', 'error');
                        }
                        return;
                    }
                    const p = profiles.find(x => x.id === id);
                    if (!p) return;
                    if (!confirm(`Are you sure you want to delete profile "${p.name}"?`)) return;
                    await service.deleteProfile(p.id);
                    this.render(this.activeContainer, { config: this.activeConfig });
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast(`Deleted profile "${p.name}".`, 'info');
                    }
                });
            });

            // Click Profile Item row to activate
            root.querySelectorAll('.gamepad-profile-item').forEach(item => {
                item.addEventListener('click', (e) => {
                    if (e.target.closest('button')) return;
                    const id = item.dataset.id;
                    if (id) {
                        service.setActiveProfile(id);
                        this.render(this.activeContainer, { config: this.activeConfig });
                    }
                });
            });
        }

        showNewProfileModal() {
            const service = window.GamepadService;
            if (!service) return;

            const modal = document.createElement('div');
            modal.className = 'modal-backdrop show';
            modal.style.zIndex = '100060';
            modal.innerHTML = `
                <div class="gamepad-modal-sheet" style="width: min(460px, 94vw);">
                    <div class="gamepad-sheet-handle"></div>
                    <div class="gamepad-sheet-header">
                        <h3 class="gamepad-sheet-title">
                            <span style="color: var(--accent); display: inline-flex; align-items: center;">${ICONS.plus}</span>
                            <span>Create New Profile</span>
                        </h3>
                        <button type="button" class="btn-sheet-close" style="background:none; border:none; font-size:1.4rem; cursor:pointer; color:var(--muted); line-height:1;">&times;</button>
                    </div>
                    <div style="display: flex; flex-direction: column; gap: 4px;">
                        <label style="font-size: 0.85rem; font-weight: 600; color: var(--ink);">Profile Name</label>
                        <input type="text" id="new-profile-name-input" class="input" placeholder="e.g. Driver Station Layout" value="Custom Layout ${service.getProfiles ? service.getProfiles().length + 1 : 1}" style="padding: 10px 12px; border-radius: 10px;" />
                    </div>
                    <div style="display: flex; flex-direction: column; gap: 4px;">
                        <label style="font-size: 0.85rem; font-weight: 600; color: var(--ink);">Controller Style</label>
                        <div class="gamepad-segmented-group" id="new-profile-type-segmented" style="margin-top: 2px;">
                            <button type="button" class="gamepad-segment-btn active" data-type="xbox">
                                ${ICONS.gamepad} <span>Xbox</span>
                            </button>
                            <button type="button" class="gamepad-segment-btn" data-type="playstation">
                                ${ICONS.playstation} <span>PS4 / PS5</span>
                            </button>
                            <button type="button" class="gamepad-segment-btn" data-type="keyboard">
                                ${ICONS.keyboard} <span>Keyboard</span>
                            </button>
                        </div>
                    </div>
                    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 10px;">
                        <button type="button" class="btn secondary btn-sheet-cancel" style="padding: 10px; font-weight: 600;">Cancel</button>
                        <button type="button" id="btn-save-new-profile" class="btn" style="padding: 10px; font-weight: 700;">Create Profile</button>
                    </div>
                </div>
            `;
            document.body.appendChild(modal);

            let selectedType = 'xbox';
            modal.querySelectorAll('#new-profile-type-segmented .gamepad-segment-btn').forEach(b => {
                b.addEventListener('click', () => {
                    modal.querySelectorAll('#new-profile-type-segmented .gamepad-segment-btn').forEach(x => x.classList.remove('active'));
                    b.classList.add('active');
                    selectedType = b.dataset.type || 'xbox';
                });
            });

            const closeFn = () => {
                if (modal.parentElement) modal.parentElement.removeChild(modal);
            };
            modal.querySelector('.btn-sheet-close')?.addEventListener('click', closeFn);
            modal.querySelector('.btn-sheet-cancel')?.addEventListener('click', closeFn);

            modal.querySelector('#btn-save-new-profile')?.addEventListener('click', async () => {
                const nameInput = modal.querySelector('#new-profile-name-input');
                const name = nameInput ? nameInput.value.trim() : '';
                if (!name) return;

                const newProfile = {
                    schemaVersion: 1,
                    id: `profile_${Date.now()}`,
                    name: name,
                    description: 'Custom user layout',
                    controllerType: selectedType,
                    enabled: true,
                    showTooltips: true,
                    hapticEnabled: true,
                    hapticStrength: 1.0,
                    updatedAt: new Date().toISOString(),
                    bindings: [
                        { id: 'b_tab_prev', inputKey: 'shoulder_l', actionType: 'switchTab', targetValue: 'prev', phase: 'global', triggerMode: 'singlePress' },
                        { id: 'b_tab_next', inputKey: 'shoulder_r', actionType: 'switchTab', targetValue: 'next', phase: 'global', triggerMode: 'singlePress' },
                        { id: 'b_submit', inputKey: 'button_start', actionType: 'submit', phase: 'global', triggerMode: 'singlePress' }
                    ]
                };

                await service.saveProfile(newProfile);
                service.setActiveProfile(newProfile.id);
                closeFn();
                this.render(this.activeContainer, { config: this.activeConfig });
                if (window.Obsidianscout && Obsidianscout.showToast) {
                    Obsidianscout.showToast(`Profile "${name}" created!`, 'success');
                }
            });
        }

        wireEvents(root) {
            const service = window.GamepadService;
            if (!service) return;

            // Save Profile Button (Header & Card)
            const handleSaveProfile = async (btn) => {
                const p = service.getActiveProfile();
                if (!p) return;
                const origHtml = btn ? btn.innerHTML : null;
                if (btn) {
                    btn.disabled = true;
                    btn.innerHTML = `${ICONS.save} <span>Saving...</span>`;
                }
                try {
                    const ok = await service.saveActiveProfileToServer();
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        if (ok) {
                            Obsidianscout.showToast(`Profile "${p.name}" saved to server!`, 'success');
                        } else {
                            Obsidianscout.showToast('Save Failed: Could not reach server (server offline). Profile was NOT saved to the server.', 'error');
                        }
                    }
                } catch (err) {
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast('Save Failed: Server is offline or unreachable.', 'error');
                    }
                } finally {
                    if (btn && origHtml) {
                        btn.disabled = false;
                        btn.innerHTML = origHtml;
                    }
                }
            };

            root.querySelector('#gamepad-btn-save-profile')?.addEventListener('click', (e) => handleSaveProfile(e.currentTarget));
            root.querySelector('#gamepad-btn-save-card')?.addEventListener('click', (e) => handleSaveProfile(e.currentTarget));

            // Refresh Gamepads & Server Profiles Sync
            const btnRefresh = root.querySelector('#gamepad-btn-refresh-gamepads');
            btnRefresh?.addEventListener('click', async () => {
                btnRefresh.disabled = true;
                const synced = await service.syncWithServer(true).catch(() => false);
                const count = service.getConnectedGamepads().length;
                this.refreshHardwareCard(root);
                this.refreshProfilesList(root);
                btnRefresh.disabled = false;
                if (window.Obsidianscout && Obsidianscout.showToast) {
                    if (synced) {
                        Obsidianscout.showToast(`${count} controller(s) detected, profiles synced from server.`, 'success');
                    } else {
                        Obsidianscout.showToast(`${count} controller(s) detected (Server is offline - could not sync profiles).`, 'warning');
                    }
                }
            });

            // Master Enable Toggle
            const toggleMaster = root.querySelector('#gamepad-toggle-master-enable');
            toggleMaster?.addEventListener('change', (e) => {
                const p = service.getActiveProfile();
                if (p) {
                    p.enabled = e.target.checked;
                    service.saveProfile(p);
                    this.refreshHardwareCard(root);
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast(p.enabled ? 'Controller controls enabled' : 'Controller controls disabled', 'info');
                    }
                }
            });

            // Controller Type Segmented Buttons
            root.querySelectorAll('#gamepad-type-segmented .gamepad-segment-btn').forEach(btn => {
                btn.addEventListener('click', () => {
                    const type = btn.dataset.type;
                    root.querySelectorAll('#gamepad-type-segmented .gamepad-segment-btn').forEach(b => b.classList.remove('active'));
                    btn.classList.add('active');
                    const p = service.getActiveProfile();
                    if (p) {
                        p.controllerType = type;
                        service.saveProfile(p);
                        this.updateGaugeLabels(root, type);
                        this.renderBindingsTable(root);
                        this.refreshProfilesList(root);
                    }
                });
            });

            // Tooltips toggle
            const toggleTooltips = root.querySelector('#gamepad-toggle-tooltips');
            toggleTooltips?.addEventListener('change', (e) => {
                const p = service.getActiveProfile();
                if (p) {
                    p.showTooltips = e.target.checked;
                    service.saveProfile(p);
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast(p.showTooltips ? 'Keybind badges enabled on forms' : 'Keybind badges hidden', 'info');
                    }
                }
            });

            // Haptics & Rumble Toggle
            const toggleHaptics = root.querySelector('#gamepad-toggle-haptics');
            const rumbleContainer = root.querySelector('#gamepad-rumble-container');
            const sliderRumble = root.querySelector('#gamepad-slider-rumble');
            const badgeRumble = root.querySelector('#gamepad-rumble-percent-badge');
            const btnTestRumble = root.querySelector('#gamepad-btn-test-rumble');

            toggleHaptics?.addEventListener('change', (e) => {
                const p = service.getActiveProfile();
                if (p) {
                    p.hapticEnabled = e.target.checked;
                    if (rumbleContainer) rumbleContainer.style.display = p.hapticEnabled ? 'block' : 'none';
                    service.saveProfile(p);
                }
            });

            sliderRumble?.addEventListener('input', (e) => {
                const val = parseFloat(e.target.value);
                if (badgeRumble) badgeRumble.textContent = `${Math.round(val * 100)}%`;
            });

            sliderRumble?.addEventListener('change', (e) => {
                const val = parseFloat(e.target.value);
                const p = service.getActiveProfile();
                if (p) {
                    p.hapticStrength = val;
                    service.saveProfile(p);
                    service.testRumble(val, 250);
                }
            });

            btnTestRumble?.addEventListener('click', async () => {
                const val = parseFloat(sliderRumble?.value || '1.0');
                const success = await service.testRumble(val, 300);
                if (window.Obsidianscout && Obsidianscout.showToast) {
                    if (success) {
                        Obsidianscout.showToast('Vibration signal sent to controller.', 'success');
                    } else {
                        Obsidianscout.showToast('No physical vibration motor response detected.', 'warning');
                    }
                }
            });

            // New Profile Button
            const btnNewProfile = root.querySelector('#gamepad-btn-new-profile');
            btnNewProfile?.addEventListener('click', () => {
                this.showNewProfileModal();
            });

            // Auto-Generate Smart Layout
            const btnAutoGen = root.querySelector('#gamepad-btn-auto-gen');
            btnAutoGen?.addEventListener('click', async () => {
                let currentConfig = this.activeConfig || window.activeFormConfig;
                if (!currentConfig || (!currentConfig.fields && !Array.isArray(currentConfig)) || (currentConfig.fields && currentConfig.fields.length === 0)) {
                    currentConfig = await this.fetchActiveConfig();
                    if (currentConfig) {
                        this.activeConfig = currentConfig;
                    }
                }

                const rawFields = Array.isArray(currentConfig) ? currentConfig : (currentConfig?.fields || currentConfig?.config?.fields || []);
                if (!currentConfig || rawFields.length === 0) {
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast('No active match configuration found to auto-generate from.', 'warning');
                    }
                    return;
                }
                const p = service.getActiveProfile();
                const controllerType = p ? p.controllerType : 'xbox';
                const newProfile = service.autoGenerateForConfig(currentConfig, controllerType, `Smart Layout (${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })})`);
                await service.saveProfile(newProfile);
                service.setActiveProfile(newProfile.id);
                if (window.Obsidianscout && Obsidianscout.showToast) {
                    Obsidianscout.showToast('Layout auto-generated from match config!', 'success');
                }
                this.render(this.activeContainer, { config: currentConfig });
            });

            // Export Config Dialog
            const btnExport = root.querySelector('#gamepad-btn-export-config');
            btnExport?.addEventListener('click', () => {
                const p = service.getActiveProfile();
                if (!p) return;
                this.showExportModal(p);
            });

            // Import Config Dialog
            const btnImport = root.querySelector('#gamepad-btn-import-config');
            btnImport?.addEventListener('click', () => {
                this.showImportModal();
            });

            // Collapsible HUD toggle
            const hudToggle = root.querySelector('#gamepad-hud-toggle-btn');
            hudToggle?.addEventListener('click', () => {
                this.showLiveTester = !this.showLiveTester;
                const hudBody = root.querySelector('#gamepad-hud-body');
                const hudIcon = root.querySelector('#gamepad-hud-icon');
                const hudChevron = root.querySelector('#gamepad-hud-chevron');
                if (hudBody) hudBody.style.display = this.showLiveTester ? 'block' : 'none';
                if (hudChevron) hudChevron.innerHTML = this.showLiveTester ? ICONS.chevronUp : ICONS.chevronDown;
                if (hudIcon) hudIcon.innerHTML = this.showLiveTester ? ICONS.eyeOff : ICONS.eye;
            });

            // Phase Filter Chips
            root.querySelectorAll('.gamepad-filter-chip').forEach(chip => {
                chip.addEventListener('click', () => {
                    root.querySelectorAll('.gamepad-filter-chip').forEach(c => c.classList.remove('active'));
                    chip.classList.add('active');
                    this.selectedPhaseFilter = chip.dataset.phase || 'all';
                    this.renderBindingsTable(root);
                });
            });

            // Add Binding Button
            const btnAddBinding = root.querySelector('#gamepad-btn-add-binding');
            btnAddBinding?.addEventListener('click', () => {
                this.openBindingEditorModal(null);
            });
        }

        startHudLoop() {
            if (this.hudRaf) cancelAnimationFrame(this.hudRaf);
            const loop = () => {
                if (this.showLiveTester && this.activeContainer) {
                    this.updateHudGauges();
                }
                this.hudRaf = requestAnimationFrame(loop);
            };
            this.hudRaf = requestAnimationFrame(loop);
        }

        stopHudLoop() {
            if (this.hudRaf) {
                cancelAnimationFrame(this.hudRaf);
                this.hudRaf = null;
            }
        }

        updateHudGauges() {
            const service = window.GamepadService;
            if (!service || !this.activeContainer) return;

            const ltVal = Math.abs(service.liveValues?.get('trigger_l') || 0.0);
            const rtVal = Math.abs(service.liveValues?.get('trigger_r') || 0.0);

            const fillL = this.activeContainer.querySelector('#gamepad-gauge-fill-l');
            const valL = this.activeContainer.querySelector('#gamepad-gauge-val-l');
            const fillR = this.activeContainer.querySelector('#gamepad-gauge-fill-r');
            const valR = this.activeContainer.querySelector('#gamepad-gauge-val-r');

            if (fillL && valL) {
                const p = Math.round(ltVal * 100);
                fillL.style.width = `${p}%`;
                valL.textContent = `${p}%`;
            }
            if (fillR && valR) {
                const p = Math.round(rtVal * 100);
                fillR.style.width = `${p}%`;
                valR.textContent = `${p}%`;
            }

            // Update button chips
            this.activeContainer.querySelectorAll('.gamepad-hud-chip').forEach(chip => {
                const key = chip.dataset.key;
                const isPressed = service.livePressed?.get(key) || false;
                if (isPressed) {
                    chip.classList.add('pressed');
                } else {
                    chip.classList.remove('pressed');
                }
            });
        }

        updateGaugeLabels(root, type) {
            const labelL = root.querySelector('#gamepad-gauge-label-l');
            const labelR = root.querySelector('#gamepad-gauge-label-r');
            if (labelL) labelL.textContent = type === 'playstation' ? 'L2 Trigger' : 'LT Trigger';
            if (labelR) labelR.textContent = type === 'playstation' ? 'R2 Trigger' : 'RT Trigger';
        }

        async fetchActiveConfig() {
            if (window.activeFormConfig && (window.activeFormConfig.fields || Array.isArray(window.activeFormConfig))) {
                return window.activeFormConfig;
            }

            // 1. Try Obsidianscout.request('/api/config')
            try {
                if (window.Obsidianscout && typeof window.Obsidianscout.request === 'function') {
                    const data = await window.Obsidianscout.request('/api/config');
                    if (data && (data.fields || Array.isArray(data))) return data;
                }
            } catch (_) {}

            // 2. Try fetch('/api/config')
            try {
                const res = await fetch('/api/config', { credentials: 'same-origin' });
                if (res.ok) {
                    const data = await res.json();
                    if (data && (data.fields || Array.isArray(data))) return data;
                }
            } catch (_) {}

            // 3. Try localStorage cached configs
            try {
                const localConfig = localStorage.getItem('obsidianscout:config') || localStorage.getItem('obsidian_form_config') || localStorage.getItem('active_config');
                if (localConfig) {
                    const parsed = JSON.parse(localConfig);
                    if (parsed && (parsed.fields || Array.isArray(parsed))) return parsed;
                }
            } catch (_) {}

            // 4. Try /api/qual-config
            try {
                if (window.Obsidianscout && typeof window.Obsidianscout.request === 'function') {
                    const data = await window.Obsidianscout.request('/api/qual-config');
                    if (data && (data.fields || Array.isArray(data))) return data;
                }
            } catch (_) {}

            return null;
        }

        refreshProfilesList(root = this.activeContainer || document) {
            const listEl = root.querySelector('#gamepad-profiles-list');
            const titleEl = root.querySelector('#gamepad-profile-count-title');
            const service = window.GamepadService;
            if (!service) return;

            const profiles = service ? (typeof service.getProfiles === 'function' ? service.getProfiles() : (service.profiles || [])) : [];
            const active = service ? (typeof service.getActiveProfile === 'function' ? service.getActiveProfile() : service.activeProfile) : null;

            if (titleEl) {
                titleEl.textContent = `Controller Profiles (${profiles.length})`;
            }
            if (listEl) {
                listEl.innerHTML = this.renderProfilesList();
                this.wireProfilesListEvents(root);
            }

            const countTitle = root.querySelector('#gamepad-mappings-count-title');
            if (countTitle) countTitle.textContent = `Configured Mappings (${active?.bindings?.length || 0})`;
        }

        refreshHardwareCard(root = this.activeContainer || document) {
            const iconBox = root.querySelector('#gamepad-hardware-icon');
            const subtitle = root.querySelector('#gamepad-hardware-subtitle');
            const service = window.GamepadService;
            if (!service) return;

            const gamepads = service ? (typeof service.getConnectedGamepads === 'function' ? service.getConnectedGamepads() : Array.from(service.connectedGamepads?.values() || [])) : [];
            const active = service.getActiveProfile();
            const isEnabled = active ? active.enabled : true;

            if (gamepads.length > 0) {
                const names = gamepads.map(g => g.id.split('(')[0].trim()).join(', ');
                if (iconBox) iconBox.className = `gamepad-hardware-icon-box ${isEnabled ? 'connected' : ''}`;
                if (subtitle) subtitle.textContent = `${gamepads.length} controller(s) connected: ${names}`;
            } else {
                if (iconBox) iconBox.className = 'gamepad-hardware-icon-box';
                if (subtitle) subtitle.textContent = 'No physical controller detected (Keyboard/Bluetooth ready)';
            }
        }

        renderBindingsTable(root = this.activeContainer || document) {
            const tbody = root.querySelector('#gamepad-bindings-tbody');
            const service = window.GamepadService;
            if (!tbody || !service) return;

            const profile = service ? (typeof service.getActiveProfile === 'function' ? service.getActiveProfile() : service.activeProfile) : null;
            if (!profile || !profile.bindings || profile.bindings.length === 0) {
                tbody.innerHTML = `
                    <tr>
                        <td colspan="6" style="text-align: center; color: var(--muted); padding: 28px;">
                            <div style="font-size: 2rem; opacity: 0.4; display: block; margin-bottom: 8px;">${ICONS.gamepad}</div>
                            No bindings configured. Click <strong>Auto-Generate Smart Layout</strong> or <strong>Add Mapping</strong> to get started.
                        </td>
                    </tr>
                `;
                return;
            }

            const filter = this.selectedPhaseFilter;
            const visibleBindings = profile.bindings.filter(b => {
                if (filter === 'all') return true;
                return (b.phase || 'global').toLowerCase() === filter.toLowerCase();
            });

            if (visibleBindings.length === 0) {
                tbody.innerHTML = `
                    <tr>
                        <td colspan="6" style="text-align: center; color: var(--muted); padding: 24px;">
                            No bindings configured for the <strong>${filter.toUpperCase()}</strong> period.
                        </td>
                    </tr>
                `;
                return;
            }

            const fields = (this.activeConfig?.fields || (Array.isArray(this.activeConfig) ? this.activeConfig : []) || window.activeFormConfig?.fields || []);

            tbody.innerHTML = visibleBindings.map((b) => {
                const phaseKey = (b.phase || 'global').toLowerCase();
                const phaseBadge = this.formatPhaseBadge(phaseKey);
                const keyName = service.getBindingDisplayName(b.inputKey, profile.controllerType);
                const shortBadge = service.getShortBadgeLabel ? service.getShortBadgeLabel(b.inputKey, profile.controllerType) : b.inputKey;
                const targetText = this.formatTargetText(b, fields);
                const actionLabel = this.formatActionType(b.actionType, b);
                const triggerLabel = this.formatTriggerMode(b.triggerMode, b);

                // Conflict detection
                const hasConflict = profile.bindings.some(other => {
                    if (other.id === b.id) return false;
                    if (other.inputKey !== b.inputKey) return false;
                    const oPhase = (other.phase || 'global').toLowerCase();
                    return oPhase === phaseKey || oPhase === 'global' || phaseKey === 'global';
                });

                return `
                    <tr data-binding-id="${b.id}" style="${hasConflict ? 'background: rgba(239, 68, 68, 0.06);' : ''}">
                        <td>${phaseBadge}</td>
                        <td>${targetText}</td>
                        <td>${actionLabel}</td>
                        <td>
                            <span class="gamepad-key-pill" title="${keyName}">
                                <span>${shortBadge}</span>
                            </span>
                            ${hasConflict ? `<span style="color: #ef4444; margin-left: 4px; display: inline-flex; vertical-align: middle;" title="Duplicate key conflict in this phase!">${ICONS.warning}</span>` : ''}
                        </td>
                        <td><span class="notice" style="margin: 0; font-size: 0.8rem; display: inline-block;">${triggerLabel}</span></td>
                        <td style="text-align: right;">
                            <div style="display: inline-flex; gap: 6px;">
                                <button type="button" class="btn secondary btn-edit-binding" data-id="${b.id}" style="padding: 4px 8px; font-size: 0.75rem;" title="Edit this keybinding">
                                    ${ICONS.edit} <span>Edit</span>
                                </button>
                                <button type="button" class="btn secondary btn-rebind" data-id="${b.id}" style="padding: 4px 8px; font-size: 0.75rem;" title="Press a new button/key to rebind">
                                    ${ICONS.refresh} <span>Rebind</span>
                                </button>
                                <button type="button" class="btn btn-del-binding" data-id="${b.id}" style="padding: 4px 8px; font-size: 0.75rem;" title="Delete binding">
                                    ${ICONS.trash} <span>Delete</span>
                                </button>
                            </div>
                        </td>
                    </tr>
                `;
            }).join('');

            // Wire edit, rebind and delete buttons
            tbody.querySelectorAll('.btn-edit-binding').forEach(btn => {
                btn.addEventListener('click', () => {
                    const id = btn.dataset.id;
                    this.openBindingEditorModal(id);
                });
            });

            tbody.querySelectorAll('.btn-rebind').forEach(btn => {
                btn.addEventListener('click', () => {
                    const id = btn.dataset.id;
                    this.startInteractiveRebind(id);
                });
            });

            tbody.querySelectorAll('.btn-del-binding').forEach(btn => {
                btn.addEventListener('click', () => {
                    const id = btn.dataset.id;
                    this.deleteBinding(id);
                });
            });
        }

        formatPhaseBadge(phase) {
            const p = (phase || 'global').toLowerCase();
            switch (p) {
                case 'auto':
                    return '<span class="badge badge-phase-auto" style="font-size: 0.75rem; font-weight: 700;">AUTO</span>';
                case 'teleop':
                    return '<span class="badge badge-phase-teleop" style="font-size: 0.75rem; font-weight: 700;">TELEOP</span>';
                case 'endgame':
                    return '<span class="badge badge-phase-endgame" style="font-size: 0.75rem; font-weight: 700;">ENDGAME</span>';
                case 'postmatch':
                    return '<span class="badge badge-phase-postmatch" style="font-size: 0.75rem; font-weight: 700;">POST-MATCH</span>';
                case 'global':
                default:
                    return '<span class="badge badge-phase-global" style="font-size: 0.75rem; font-weight: 700;">GLOBAL</span>';
            }
        }

        formatTargetText(b, fields = []) {
            if (b.actionType === 'switchTab') {
                const target = b.targetValue || 'next';
                switch (target) {
                    case 'next': return '<strong style="color: var(--ink);">Next Tab (Forward)</strong>';
                    case 'prev': return '<strong style="color: var(--ink);">Previous Tab (Backward)</strong>';
                    case 'auto': return '<strong style="color: var(--ink);">Auto Phase Tab</strong>';
                    case 'teleop': return '<strong style="color: var(--ink);">Teleop Phase Tab</strong>';
                    case 'endgame': return '<strong style="color: var(--ink);">Endgame Phase Tab</strong>';
                    case 'postmatch': return '<strong style="color: var(--ink);">Post-Match Tab</strong>';
                    default: return `<strong style="color: var(--ink);">${target.toUpperCase()} Tab</strong>`;
                }
            }
            if (b.actionType === 'submit') {
                return '<strong style="color: var(--ink);">Save / Submit Match Data</strong>';
            }
            if (b.actionType === 'barcode') {
                return '<strong style="color: var(--ink);">Generate QR / Barcode</strong>';
            }
            if (b.actionType === 'clearForm') {
                return '<strong style="color: var(--ink);">Reset / Clear Form</strong>';
            }

            if (b.targetFieldId) {
                const matchField = fields.find(f => f.id === b.targetFieldId);
                if (matchField) {
                    const label = getFieldLabel(matchField);
                    return `<strong style="color: var(--ink);">${label || matchField.id}</strong> <small style="color: var(--muted); font-size: 0.75rem;">(${matchField.id})</small>`;
                }
                return `<strong style="color: var(--ink);">${b.targetFieldId}</strong>`;
            }

            return '<span style="color: var(--muted);">None</span>';
        }

        formatActionType(type, b) {
            const step = b?.stepValue || 1;
            switch (type) {
                case 'increment': return `<span style="color: #10b981; font-weight: 600; display: inline-flex; align-items: center; gap: 4px;">${ICONS.plus} +${step} Increment</span>`;
                case 'decrement': return `<span style="color: #ef4444; font-weight: 600; display: inline-flex; align-items: center; gap: 4px;">-${step} Decrement</span>`;
                case 'toggle': return `<span style="color: #3b82f6; font-weight: 600; display: inline-flex; align-items: center; gap: 4px;">${ICONS.toggle} Toggle</span>`;
                case 'cycleOption': return `<span style="color: #8b5cf6; font-weight: 600; display: inline-flex; align-items: center; gap: 4px;">${ICONS.cycle} Next Option</span>`;
                case 'switchTab': return `<span style="color: #f59e0b; font-weight: 600; display: inline-flex; align-items: center; gap: 4px;">${ICONS.arrows} Switch Tab</span>`;
                case 'submit': return `<span style="color: #10b981; font-weight: 700; display: inline-flex; align-items: center; gap: 4px;">${ICONS.check} Submit Form</span>`;
                case 'barcode': return `<span style="color: #06b6d4; font-weight: 600; display: inline-flex; align-items: center; gap: 4px;">${ICONS.export} Generate QR</span>`;
                case 'clearForm': return `<span style="color: #ef4444; font-weight: 600; display: inline-flex; align-items: center; gap: 4px;">${ICONS.trash} Reset Form</span>`;
                default: return type;
            }
        }

        formatTriggerMode(mode, b) {
            switch (mode) {
                case 'scaledTrigger': return `Analog Trigger (${b.triggerMinHz || 2}-${b.triggerMaxHz || 16} Hz)`;
                case 'continuousHold': return `Hold (${b.repeatFrequencyHz || 6.0} Hz)`;
                case 'singlePress':
                default: return 'Single Tap';
            }
        }

        startInteractiveRebind(bindingId) {
            const service = window.GamepadService;
            if (!service) return;

            const profile = service.getActiveProfile();
            const binding = profile?.bindings?.find(b => b.id === bindingId);
            if (!binding) return;

            const fields = (this.activeConfig?.fields || (Array.isArray(this.activeConfig) ? this.activeConfig : []) || window.activeFormConfig?.fields || []);

            const overlay = document.createElement('div');
            overlay.className = 'modal-backdrop show';
            overlay.style.zIndex = '100060';
            overlay.innerHTML = `
                <div class="gamepad-modal-sheet" style="width: min(440px, 94vw); text-align: center; align-items: center;">
                    <div class="gamepad-sheet-handle"></div>
                    <div class="gamepad-hardware-icon-box connected" style="margin-top: 8px; width: 54px; height: 54px; font-size: 26px;">
                        ${ICONS.gamepad}
                    </div>
                    <div>
                        <h3 style="margin: 8px 0 4px 0; font-size: 1.2rem; color: var(--ink);">Listening for Input...</h3>
                        <p class="notice" style="margin: 0; font-size: 0.85rem;">
                            Press any button, trigger, D-pad direction, or keyboard key.
                        </p>
                    </div>
                    <div style="font-size: 0.85rem; color: var(--muted); margin-top: 4px;">
                        Target: <strong>${this.formatTargetText(binding, fields)}</strong>
                    </div>
                    <button type="button" class="btn ghost btn-cancel-capture" style="margin-top: 10px; width: 100%;">Cancel</button>
                </div>
            `;
            document.body.appendChild(overlay);

            const cancelFn = () => {
                service.stopListeningForBinding();
                if (overlay.parentElement) overlay.parentElement.removeChild(overlay);
            };

            overlay.querySelector('.btn-cancel-capture').addEventListener('click', cancelFn);

            service.startListeningForBinding((inputKey, displayName, isAnalog) => {
                binding.inputKey = inputKey;
                if (isAnalog && (inputKey === 'trigger_l' || inputKey === 'trigger_r' || inputKey === 'l2' || inputKey === 'r2')) {
                    binding.triggerMode = 'scaledTrigger';
                    binding.triggerMinHz = binding.triggerMinHz || 2.0;
                    binding.triggerMaxHz = binding.triggerMaxHz || 16.0;
                }
                service.saveProfile(profile);
                if (overlay.parentElement) overlay.parentElement.removeChild(overlay);
                if (window.Obsidianscout && Obsidianscout.showToast) {
                    Obsidianscout.showToast(`Bound to: ${displayName}`, 'success');
                }
                this.renderBindingsTable();
            });
        }

        deleteBinding(bindingId) {
            const service = window.GamepadService;
            if (!service) return;
            const profile = service.getActiveProfile();
            if (!profile) return;
            profile.bindings = profile.bindings.filter(b => b.id !== bindingId);
            service.saveProfile(profile);
            this.renderBindingsTable();
            this.refreshProfilesList();
            if (window.Obsidianscout && Obsidianscout.showToast) {
                Obsidianscout.showToast('Binding deleted', 'info');
            }
        }

        async openBindingEditorModal(bindingId = null) {
            const service = window.GamepadService;
            if (!service) return;
            const profile = service.getActiveProfile();
            if (!profile) return;

            if (!this.activeConfig) {
                this.activeConfig = await this.fetchActiveConfig();
            }

            const isEdit = Boolean(bindingId);
            const existing = isEdit ? profile.bindings?.find(b => b.id === bindingId) : null;
            const rawFields = (this.activeConfig?.fields || (Array.isArray(this.activeConfig) ? this.activeConfig : []) || window.activeFormConfig?.fields || []);
            const allFields = rawFields.filter(f => f && f.type !== 'section' && f.type !== 'header');

            // Working state matching Flutter app
            let state = {
                inputKey: existing?.inputKey || 'button_a',
                actionType: existing?.actionType || 'increment',
                targetFieldId: existing?.targetFieldId || null,
                targetValue: existing?.targetValue || 'next',
                phase: existing?.phase || 'global',
                triggerMode: existing?.triggerMode || 'singlePress',
                repeatFrequencyHz: existing?.repeatFrequencyHz || 6.0,
                triggerMinHz: existing?.triggerMinHz || 2.0,
                triggerMaxHz: existing?.triggerMaxHz || 16.0,
                triggerThreshold: existing?.triggerThreshold || 0.15,
                stepValue: existing?.stepValue || 1.0,
                isListening: false
            };

            const isSystemAction = (act) => ['switchTab', 'submit', 'barcode', 'clearForm'].includes(act);
            const isTriggerKey = (key) => ['trigger_l', 'trigger_r', 'l2', 'r2'].includes(key);
            const isRepeatableAction = (act) => act === 'increment' || act === 'decrement';

            const getMatchingFields = (act, fields) => {
                switch (act) {
                    case 'increment':
                    case 'decrement':
                        return fields.filter(f => {
                            const t = (f.type || '').toLowerCase();
                            return ['counter', 'number', 'stepper', 'slider', 'range', 'rating'].includes(t);
                        });
                    case 'toggle':
                        return fields.filter(f => {
                            const t = (f.type || '').toLowerCase();
                            return ['toggle', 'boolean', 'checkbox'].includes(t);
                        });
                    case 'cycleOption':
                        return fields.filter(f => {
                            const t = (f.type || '').toLowerCase();
                            return ['select', 'dropdown', 'radio', 'choice', 'multiselect'].includes(t);
                        });
                    default:
                        return [];
                }
            };

            const resolveInitialPhase = (fieldId) => {
                if (!fieldId) return 'global';
                const field = allFields.find(f => f.id === fieldId);
                if (field?.phase && String(field.phase).trim()) {
                    const p = String(field.phase).toLowerCase().trim();
                    return p === 'general' ? 'teleop' : p;
                }
                const lower = fieldId.toLowerCase();
                if (lower.startsWith('auto')) return 'auto';
                if (lower.startsWith('teleop')) return 'teleop';
                if (lower.startsWith('endgame')) return 'endgame';
                if (lower.startsWith('post')) return 'postmatch';
                return 'global';
            };

            // Set initial defaults
            if (!existing) {
                const matching = getMatchingFields(state.actionType, allFields);
                state.targetFieldId = matching[0]?.id || null;
                state.phase = isSystemAction(state.actionType) ? 'global' : resolveInitialPhase(state.targetFieldId);
            }

            const modalBackdrop = document.createElement('div');
            modalBackdrop.className = 'modal-backdrop show';
            modalBackdrop.style.zIndex = '100060';

            const modalSheet = document.createElement('div');
            modalSheet.className = 'gamepad-modal-sheet';
            modalBackdrop.appendChild(modalSheet);
            document.body.appendChild(modalBackdrop);

            const closeFn = () => {
                service.stopListeningForBinding();
                if (modalBackdrop.parentElement) modalBackdrop.parentElement.removeChild(modalBackdrop);
            };

            modalBackdrop.addEventListener('click', (e) => {
                if (e.target === modalBackdrop) closeFn();
            });

            const renderModalContent = () => {
                const isTrigger = isTriggerKey(state.inputKey);
                const isRepeatable = isRepeatableAction(state.actionType);

                if (!isRepeatable && state.triggerMode !== 'singlePress') {
                    state.triggerMode = 'singlePress';
                } else if (!isTrigger && state.triggerMode === 'scaledTrigger') {
                    state.triggerMode = 'singlePress';
                }

                const buttonDisplayName = service.getBindingDisplayName(state.inputKey, profile.controllerType);
                const shortBadge = service.getShortBadgeLabel ? service.getShortBadgeLabel(state.inputKey, profile.controllerType) : state.inputKey;

                // Conflict and duplicate checks
                const conflictingKey = profile.bindings.find(b => {
                    if (b.id === existing?.id) return false;
                    if (b.inputKey !== state.inputKey) return false;
                    const bPhase = (b.phase || 'global').toLowerCase();
                    const sPhase = (state.phase || 'global').toLowerCase();
                    return bPhase === sPhase || bPhase === 'global' || sPhase === 'global';
                });

                const duplicateField = (state.targetFieldId) ? profile.bindings.find(b => {
                    if (b.id === existing?.id) return false;
                    if (b.targetFieldId !== state.targetFieldId || b.actionType !== state.actionType) return false;
                    const bPhase = (b.phase || 'global').toLowerCase();
                    const sPhase = (state.phase || 'global').toLowerCase();
                    return bPhase === sPhase || bPhase === 'global' || sPhase === 'global';
                }) : null;

                const matchingFields = getMatchingFields(state.actionType, allFields);
                const fieldCategoryLabel = state.actionType === 'toggle'
                    ? 'Target Toggle / Checkbox Field'
                    : (state.actionType === 'cycleOption' ? 'Target Dropdown / Choice Field' : 'Target Counter / Stepper Field');
                const emptyWarning = state.actionType === 'toggle'
                    ? 'No toggle, boolean, or checkbox fields found in active match form.'
                    : (state.actionType === 'cycleOption' ? 'No dropdown, radio, or multi-choice fields found in active match form.' : 'No counter, stepper, or numeric fields found in active match form.');

                modalSheet.innerHTML = `
                    <div class="gamepad-sheet-handle"></div>

                    <!-- Header -->
                    <div class="gamepad-sheet-header">
                        <h3 class="gamepad-sheet-title">
                            <span style="color: var(--accent); display: inline-flex; align-items: center;">${isEdit ? ICONS.edit : ICONS.plus}</span>
                            <span>${isEdit ? 'Edit Controller / Key Binding' : 'Add Controller / Key Binding'}</span>
                        </h3>
                        <button type="button" class="btn-sheet-close" style="background:none; border:none; font-size:1.4rem; cursor:pointer; color:var(--muted); line-height:1;">&times;</button>
                    </div>

                    <!-- Button Conflict Banner -->
                    ${conflictingKey ? `
                        <div class="gamepad-banner warning-red">
                            <span style="display: inline-flex; align-items: center;">${ICONS.warning}</span>
                            <div>
                                <div class="gamepad-banner-title">Button Conflict Warning</div>
                                <div>"${buttonDisplayName}" is already bound to "${conflictingKey.actionType}" in the ${(conflictingKey.phase || 'global').toUpperCase()} period. Both actions may trigger.</div>
                            </div>
                        </div>
                    ` : ''}

                    <!-- Duplicate Field Banner -->
                    ${duplicateField ? `
                        <div class="gamepad-banner warning-amber">
                            <span style="display: inline-flex; align-items: center;">${ICONS.info}</span>
                            <div>
                                <div class="gamepad-banner-title">Existing Field Action Mapping</div>
                                <div>This field already has a "${duplicateField.actionType}" binding on ${service.getBindingDisplayName(duplicateField.inputKey, profile.controllerType)} (${(duplicateField.phase || 'global').toUpperCase()}).</div>
                            </div>
                        </div>
                    ` : ''}

                    <!-- 1. Input Capture Card -->
                    <div id="sheet-capture-card" class="gamepad-input-capture-card ${state.isListening ? 'listening' : ''}">
                        <div class="gamepad-capture-badge">${shortBadge}</div>
                        <div class="gamepad-capture-info">
                            <div class="gamepad-capture-title">
                                ${state.isListening ? 'PRESS ANY CONTROLLER BUTTON OR KEYBOARD KEY...' : buttonDisplayName}
                            </div>
                            <div class="gamepad-capture-subtitle">
                                ${state.isListening ? 'Listening for input on Controller / Keyboard...' : 'Tap to re-record button, trigger, or key'}
                            </div>
                        </div>
                        <span style="color: var(--accent); display: inline-flex; align-items: center;">${state.isListening ? ICONS.dish : ICONS.pointer}</span>
                    </div>

                    <!-- 2. Active Period / Phase -->
                    <div style="display: flex; flex-direction: column; gap: 4px;">
                        <label style="font-size: 0.85rem; font-weight: 600; color: var(--ink);">Active Period / Phase</label>
                        <select id="sheet-phase-select" class="select-wide" style="padding: 8px 12px; border-radius: 10px;" ${isSystemAction(state.actionType) ? 'disabled' : ''}>
                            <option value="global" ${state.phase === 'global' ? 'selected' : ''}>Global (Active in All Periods)</option>
                            <option value="auto" ${state.phase === 'auto' ? 'selected' : ''}>Autonomous Period Only</option>
                            <option value="teleop" ${state.phase === 'teleop' ? 'selected' : ''}>Teleoperated Period Only</option>
                            <option value="endgame" ${state.phase === 'endgame' ? 'selected' : ''}>Endgame Period Only</option>
                            <option value="postmatch" ${state.phase === 'postmatch' ? 'selected' : ''}>Post-Match Period Only</option>
                        </select>
                        ${isSystemAction(state.actionType) ? `
                            <p style="margin: 2px 0 0 2px; font-size: 0.75rem; color: var(--muted); font-style: italic;">
                                System actions (save, QR code, tab switching) apply globally across all periods.
                            </p>
                        ` : ''}
                    </div>

                    <!-- 3. Target Action -->
                    <div style="display: flex; flex-direction: column; gap: 4px;">
                        <label style="font-size: 0.85rem; font-weight: 600; color: var(--ink);">Target Action</label>
                        <select id="sheet-action-select" class="select-wide" style="padding: 8px 12px; border-radius: 10px;">
                            <option value="increment" ${state.actionType === 'increment' ? 'selected' : ''}>+ Increment Field Count</option>
                            <option value="decrement" ${state.actionType === 'decrement' ? 'selected' : ''}>- Decrement Field Count</option>
                            <option value="toggle" ${state.actionType === 'toggle' ? 'selected' : ''}>Toggle Checkbox / Boolean</option>
                            <option value="cycleOption" ${state.actionType === 'cycleOption' ? 'selected' : ''}>Cycle Dropdown / Radio Options</option>
                            <option value="switchTab" ${state.actionType === 'switchTab' ? 'selected' : ''}>Switch Scouting Tab (Auto/Teleop/Endgame)</option>
                            <option value="submit" ${state.actionType === 'submit' ? 'selected' : ''}>Save / Submit Match Data</option>
                            <option value="barcode" ${state.actionType === 'barcode' ? 'selected' : ''}>Generate Barcode / QR</option>
                            <option value="clearForm" ${state.actionType === 'clearForm' ? 'selected' : ''}>Reset / Clear Form</option>
                        </select>
                    </div>

                    <!-- 4. Target Field / Tab Selector (Strictly filtered by action) -->
                    ${(['increment', 'decrement', 'toggle', 'cycleOption'].includes(state.actionType)) ? `
                        <div style="display: flex; flex-direction: column; gap: 4px;">
                            <label style="font-size: 0.85rem; font-weight: 600; color: var(--ink);">${fieldCategoryLabel}</label>
                            ${matchingFields.length === 0 ? `
                                <div class="gamepad-banner warning-amber" style="margin: 0;">
                                    <span style="display: inline-flex; align-items: center;">${ICONS.warning}</span>
                                    <div>${emptyWarning}</div>
                                </div>
                            ` : `
                                <select id="sheet-target-field-select" class="select-wide" style="padding: 8px 12px; border-radius: 10px;">
                                    ${matchingFields.map(f => `
                                        <option value="${f.id}" ${state.targetFieldId === f.id ? 'selected' : ''}>
                                            ${getFieldLabel(f) || f.id} (${f.id})
                                        </option>
                                    `).join('')}
                                </select>
                            `}
                        </div>
                    ` : ''}

                    ${state.actionType === 'switchTab' ? `
                        <div style="display: flex; flex-direction: column; gap: 4px;">
                            <label style="font-size: 0.85rem; font-weight: 600; color: var(--ink);">Target Tab</label>
                            <select id="sheet-target-tab-select" class="select-wide" style="padding: 8px 12px; border-radius: 10px;">
                                <option value="next" ${state.targetValue === 'next' ? 'selected' : ''}>Next Tab (Cycle Forward)</option>
                                <option value="prev" ${state.targetValue === 'prev' ? 'selected' : ''}>Previous Tab (Cycle Backward)</option>
                                <option value="auto" ${state.targetValue === 'auto' ? 'selected' : ''}>Auto Phase</option>
                                <option value="teleop" ${state.targetValue === 'teleop' ? 'selected' : ''}>Teleop Phase</option>
                                <option value="endgame" ${state.targetValue === 'endgame' ? 'selected' : ''}>Endgame Phase</option>
                                <option value="postmatch" ${state.targetValue === 'postmatch' ? 'selected' : ''}>Post-Match Phase</option>
                            </select>
                        </div>
                    ` : ''}

                    <!-- 5. Input Execution Mode -->
                    <div style="display: flex; flex-direction: column; gap: 6px;">
                        <label style="font-size: 0.85rem; font-weight: 600; color: var(--ink);">Input Execution Mode</label>
                        ${!isRepeatable ? `
                            <div style="padding: 10px 12px; border-radius: 10px; background: rgba(0,0,0,0.03); border: 1px solid var(--border-color, rgba(0,0,0,0.08)); font-size: 0.82rem; color: var(--muted); display: flex; align-items: center; gap: 8px;">
                                <span style="color: var(--accent); display: inline-flex; align-items: center;">${ICONS.pointer}</span>
                                <span>Single Press Only (Hold-to-repeat is disabled for ${state.actionType})</span>
                            </div>
                        ` : `
                            <div class="gamepad-segmented-group" id="sheet-mode-segmented" style="margin-top: 0;">
                                <button type="button" class="gamepad-segment-btn ${state.triggerMode === 'singlePress' ? 'active' : ''}" data-mode="singlePress">
                                    ${ICONS.pointer} <span>1 Click</span>
                                </button>
                                <button type="button" class="gamepad-segment-btn ${state.triggerMode === 'continuousHold' ? 'active' : ''}" data-mode="continuousHold">
                                    ${ICONS.refresh} <span>Hold Repeat</span>
                                </button>
                                ${isTrigger ? `
                                    <button type="button" class="gamepad-segment-btn ${state.triggerMode === 'scaledTrigger' ? 'active' : ''}" data-mode="scaledTrigger">
                                        ${ICONS.sliders} <span>Trigger Scale</span>
                                    </button>
                                ` : ''}
                            </div>

                            ${!isTrigger ? `
                                <p style="margin: 0 0 2px 2px; font-size: 0.74rem; color: var(--muted); font-style: italic;">
                                    Variable speed scaling is available exclusively on analog triggers (LT / RT / L2 / R2).
                                </p>
                            ` : ''}

                            <!-- Continuous Hold Settings -->
                            ${state.triggerMode === 'continuousHold' ? `
                                <div class="gamepad-rumble-box" style="margin-top: 4px;">
                                    <div style="display: flex; justify-content: space-between; align-items: center; font-size: 0.82rem; margin-bottom: 6px;">
                                        <span>Repeat Frequency</span>
                                        <span class="gamepad-strength-badge">${state.repeatFrequencyHz.toFixed(1)} actions/sec (${Math.round(1000 / state.repeatFrequencyHz)}ms)</span>
                                    </div>
                                    <input type="range" id="sheet-slider-freq" min="1.0" max="25.0" step="1.0" value="${state.repeatFrequencyHz}" style="width: 100%; accent-color: var(--accent); cursor: pointer;" />
                                </div>
                            ` : ''}

                            <!-- Scaled Trigger Settings -->
                            ${state.triggerMode === 'scaledTrigger' ? `
                                <div class="gamepad-rumble-box" style="margin-top: 4px;">
                                    <p style="margin: 0 0 8px 0; font-size: 0.78rem; color: var(--muted);">
                                        Trigger Scaling: Pulling lightly increments slowly, pulling fully increments rapidly.
                                    </p>
                                    <div style="display: flex; justify-content: space-between; font-size: 0.8rem; margin-bottom: 2px;">
                                        <span>Light Pull Speed (Min)</span>
                                        <strong style="color: var(--accent);">${Math.round(state.triggerMinHz)} Hz</strong>
                                    </div>
                                    <input type="range" id="sheet-slider-min-hz" min="1.0" max="10.0" step="1.0" value="${state.triggerMinHz}" style="width: 100%; accent-color: var(--accent); cursor: pointer; margin-bottom: 8px;" />

                                    <div style="display: flex; justify-content: space-between; font-size: 0.8rem; margin-bottom: 2px;">
                                        <span>Full Pull Speed (Max)</span>
                                        <strong style="color: var(--accent);">${Math.round(state.triggerMaxHz)} Hz</strong>
                                    </div>
                                    <input type="range" id="sheet-slider-max-hz" min="10.0" max="30.0" step="1.0" value="${state.triggerMaxHz}" style="width: 100%; accent-color: var(--accent); cursor: pointer;" />
                                </div>
                            ` : ''}
                        `}
                    </div>

                    <!-- 6. Footer Actions -->
                    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 8px;">
                        <button type="button" class="btn secondary btn-sheet-cancel" style="padding: 10px; font-weight: 600;">Cancel</button>
                        <button type="button" id="sheet-save-btn" class="btn" style="padding: 10px; font-weight: 700;">
                            ${isEdit ? 'Save Changes' : 'Save Mapping'}
                        </button>
                    </div>
                `;

                // Wire modal events
                modalSheet.querySelector('.btn-sheet-close')?.addEventListener('click', closeFn);
                modalSheet.querySelector('.btn-sheet-cancel')?.addEventListener('click', closeFn);

                // Input capture card click
                const captureCard = modalSheet.querySelector('#sheet-capture-card');
                captureCard?.addEventListener('click', () => {
                    state.isListening = true;
                    renderModalContent();
                    service.startListeningForBinding((key, displayName, isAnalog) => {
                        state.inputKey = key;
                        state.isListening = false;
                        const isTrig = isTriggerKey(key);
                        const isRep = isRepeatableAction(state.actionType);

                        if (!isRep) {
                            state.triggerMode = 'singlePress';
                        } else if (isTrig) {
                            if (state.triggerMode === 'singlePress') {
                                state.triggerMode = 'scaledTrigger';
                            }
                        } else {
                            if (state.triggerMode === 'scaledTrigger') {
                                state.triggerMode = 'singlePress';
                            }
                        }
                        renderModalContent();
                    });
                });

                // Phase dropdown
                const phaseSelect = modalSheet.querySelector('#sheet-phase-select');
                phaseSelect?.addEventListener('change', (e) => {
                    state.phase = e.target.value;
                    renderModalContent();
                });

                // Action dropdown
                const actionSelect = modalSheet.querySelector('#sheet-action-select');
                actionSelect?.addEventListener('change', (e) => {
                    const newAct = e.target.value;
                    state.actionType = newAct;
                    const matching = getMatchingFields(newAct, allFields);
                    if (!matching.some(f => f.id === state.targetFieldId)) {
                        state.targetFieldId = matching[0]?.id || null;
                    }
                    if (isSystemAction(newAct)) {
                        state.phase = 'global';
                        state.triggerMode = 'singlePress';
                        if (newAct === 'switchTab') {
                            state.targetValue = state.targetValue || 'next';
                        }
                    } else if (state.targetFieldId) {
                        state.phase = resolveInitialPhase(state.targetFieldId);
                    }
                    renderModalContent();
                });

                // Target Field dropdown
                const targetFieldSelect = modalSheet.querySelector('#sheet-target-field-select');
                targetFieldSelect?.addEventListener('change', (e) => {
                    state.targetFieldId = e.target.value;
                    if (state.targetFieldId) {
                        state.phase = resolveInitialPhase(state.targetFieldId);
                    }
                    renderModalContent();
                });

                // Target Tab dropdown
                const targetTabSelect = modalSheet.querySelector('#sheet-target-tab-select');
                targetTabSelect?.addEventListener('change', (e) => {
                    state.targetValue = e.target.value;
                });

                // Mode segmented buttons
                modalSheet.querySelectorAll('#sheet-mode-segmented .gamepad-segment-btn').forEach(btn => {
                    btn.addEventListener('click', () => {
                        state.triggerMode = btn.dataset.mode;
                        renderModalContent();
                    });
                });

                // Sliders
                const sliderFreq = modalSheet.querySelector('#sheet-slider-freq');
                sliderFreq?.addEventListener('input', (e) => {
                    state.repeatFrequencyHz = parseFloat(e.target.value);
                    const badge = modalSheet.querySelector('.gamepad-strength-badge');
                    if (badge) badge.textContent = `${state.repeatFrequencyHz.toFixed(1)} actions/sec (${Math.round(1000 / state.repeatFrequencyHz)}ms)`;
                });

                const sliderMinHz = modalSheet.querySelector('#sheet-slider-min-hz');
                sliderMinHz?.addEventListener('input', (e) => {
                    state.triggerMinHz = parseFloat(e.target.value);
                    renderModalContent();
                });

                const sliderMaxHz = modalSheet.querySelector('#sheet-slider-max-hz');
                sliderMaxHz?.addEventListener('input', (e) => {
                    state.triggerMaxHz = parseFloat(e.target.value);
                    renderModalContent();
                });

                // Save button
                modalSheet.querySelector('#sheet-save-btn')?.addEventListener('click', () => {
                    const finalBinding = {
                        id: existing?.id || `bind_${Date.now()}_${Math.random().toString(36).substr(2, 4)}`,
                        inputKey: state.inputKey,
                        actionType: state.actionType,
                        phase: state.phase,
                        triggerMode: state.triggerMode,
                        targetFieldId: isSystemAction(state.actionType) ? undefined : state.targetFieldId,
                        targetValue: state.actionType === 'switchTab' ? (state.targetValue || 'next') : undefined,
                        repeatFrequencyHz: state.repeatFrequencyHz,
                        triggerMinHz: state.triggerMinHz,
                        triggerMaxHz: state.triggerMaxHz,
                        triggerThreshold: state.triggerThreshold,
                        stepValue: state.stepValue
                    };

                    profile.bindings = profile.bindings || [];
                    if (isEdit && existing) {
                        const idx = profile.bindings.findIndex(b => b.id === existing.id);
                        if (idx >= 0) {
                            profile.bindings[idx] = finalBinding;
                        } else {
                            profile.bindings.push(finalBinding);
                        }
                    } else {
                        profile.bindings.push(finalBinding);
                    }

                    service.saveProfile(profile);
                    closeFn();
                    this.renderBindingsTable();
                    this.refreshProfilesList();
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast(isEdit ? 'Mapping updated!' : 'Mapping saved!', 'success');
                    }
                });
            };

            renderModalContent();
        }

        showExportModal(profile) {
            const jsonStr = JSON.stringify(profile, null, 2);
            const modal = document.createElement('div');
            modal.className = 'gamepad-capture-overlay';
            modal.innerHTML = `
                <div class="gamepad-capture-box" style="width: min(580px, 94vw); text-align: left; align-items: stretch;">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <h3 style="margin: 0; font-size: 1.15rem; color: var(--ink); display: flex; align-items: center; gap: 8px;">
                            <span style="color: var(--accent); display: inline-flex; align-items: center;">${ICONS.export}</span>
                            <span>Export Controller Profile</span>
                        </h3>
                        <button type="button" class="modal-close btn-close-export" style="background: none; border: none; font-size: 1.4rem; cursor: pointer; color: var(--muted);">&times;</button>
                    </div>
                    <p class="notice" style="margin: 6px 0 10px 0; font-size: 0.82rem;">
                        Copy the JSON below or save it as a backup file.
                    </p>
                    <textarea id="gamepad-export-textarea" rows="12" readonly style="width: 100%; font-family: monospace; font-size: 0.8rem; padding: 10px; border-radius: 8px; resize: vertical; border: 1px solid var(--border-color, rgba(0,0,0,0.1));">${jsonStr}</textarea>
                    <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px;">
                        <button type="button" class="btn ghost btn-close-export">Close</button>
                        <button type="button" id="gamepad-btn-copy-export" class="btn" style="display: inline-flex; align-items: center; gap: 6px;">
                            ${ICONS.copy} <span>Copy to Clipboard</span>
                        </button>
                    </div>
                </div>
            `;
            document.body.appendChild(modal);

            const closeFn = () => {
                if (modal.parentElement) modal.parentElement.removeChild(modal);
            };
            modal.querySelectorAll('.btn-close-export').forEach(b => b.addEventListener('click', closeFn));

            modal.querySelector('#gamepad-btn-copy-export')?.addEventListener('click', () => {
                navigator.clipboard.writeText(jsonStr);
                if (window.Obsidianscout && Obsidianscout.showToast) {
                    Obsidianscout.showToast('Copied configuration to clipboard!', 'success');
                }
            });
        }

        showImportModal() {
            const modal = document.createElement('div');
            modal.className = 'gamepad-capture-overlay';
            modal.innerHTML = `
                <div class="gamepad-capture-box" style="width: min(580px, 94vw); text-align: left; align-items: stretch;">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <h3 style="margin: 0; font-size: 1.15rem; color: var(--ink); display: flex; align-items: center; gap: 8px;">
                            <span style="color: var(--accent); display: inline-flex; align-items: center;">${ICONS.import}</span>
                            <span>Import Controller Profile</span>
                        </h3>
                        <button type="button" class="modal-close btn-close-import" style="background: none; border: none; font-size: 1.4rem; cursor: pointer; color: var(--muted);">&times;</button>
                    </div>
                    <p class="notice" style="margin: 6px 0 10px 0; font-size: 0.82rem;">
                        Paste the JSON of a exported profile to load it into your profile list.
                    </p>
                    <textarea id="gamepad-import-textarea" rows="12" placeholder="Paste JSON profile here..." style="width: 100%; font-family: monospace; font-size: 0.8rem; padding: 10px; border-radius: 8px; resize: vertical; border: 1px solid var(--border-color, rgba(0,0,0,0.1));"></textarea>
                    <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px;">
                        <button type="button" class="btn ghost btn-close-import">Cancel</button>
                        <button type="button" id="gamepad-btn-apply-import" class="btn" style="display: inline-flex; align-items: center; gap: 6px;">
                            ${ICONS.import} <span>Import Profile</span>
                        </button>
                    </div>
                </div>
            `;
            document.body.appendChild(modal);

            const closeFn = () => {
                if (modal.parentElement) modal.parentElement.removeChild(modal);
            };
            modal.querySelectorAll('.btn-close-import').forEach(b => b.addEventListener('click', closeFn));

            modal.querySelector('#gamepad-btn-apply-import')?.addEventListener('click', async () => {
                const text = modal.querySelector('#gamepad-import-textarea')?.value?.trim();
                if (!text) return;
                try {
                    const parsed = JSON.parse(text);
                    if (!parsed.bindings || !Array.isArray(parsed.bindings)) {
                        throw new Error('Invalid profile format: missing bindings array');
                    }
                    parsed.id = parsed.id || `profile_import_${Date.now()}`;
                    parsed.name = parsed.name ? `${parsed.name} (Imported)` : 'Imported Profile';
                    parsed.updatedAt = new Date().toISOString();

                    await window.GamepadService.saveProfile(parsed);
                    window.GamepadService.setActiveProfile(parsed.id);
                    closeFn();
                    this.render(this.activeContainer, { config: this.activeConfig });
                    if (window.Obsidianscout && Obsidianscout.showToast) {
                        Obsidianscout.showToast('Profile imported successfully!', 'success');
                    }
                } catch (err) {
                    alert(`Import failed: ${err.message}`);
                }
            });
        }
    }

    window.GamepadSettingsUI = new GamepadSettingsUI();
    document.addEventListener('DOMContentLoaded', () => {
        window.GamepadSettingsUI.init();
    });
})(window);
