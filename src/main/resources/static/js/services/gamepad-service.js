/**
 * ObsidianScout Gamepad & Controller Service
 * Full W3C Gamepad API + Keyboard engine with period-aware bindings,
 * scaled triggers, rapid fire, haptics, and server sync.
 */
(function(window) {
    'use strict';

    const STORAGE_KEY_PROFILES = 'obsidian_gamepad_profiles_v1';
    const STORAGE_KEY_ACTIVE_ID = 'obsidian_gamepad_active_id_v1';

    // Standard button names mapping to W3C indices
    const STANDARD_BUTTONS = [
        'button_a',      // 0: A / Cross
        'button_b',      // 1: B / Circle
        'button_x',      // 2: X / Square
        'button_y',      // 3: Y / Triangle
        'shoulder_l',    // 4: Left Bumper (L1 / LB)
        'shoulder_r',    // 5: Right Bumper (R1 / RB)
        'trigger_l',     // 6: Left Trigger (L2 / LT)
        'trigger_r',     // 7: Right Trigger (R2 / RT)
        'button_back',   // 8: Back / Select / View / Share
        'button_start',  // 9: Start / Menu / Options
        'thumb_l',       // 10: Left Stick Click
        'thumb_r',       // 11: Right Stick Click
        'dpad_up',       // 12: D-pad Up
        'dpad_down',     // 13: D-pad Down
        'dpad_left',     // 14: D-pad Left
        'dpad_right',    // 15: D-pad Right
        'button_guide'   // 16: Guide / Home
    ];

    class GamepadService {
        constructor() {
            this.connectedGamepads = new Map();
            this.activeGamepadIndex = null;
            this.lastActiveGamepad = null;
            this.profiles = [];
            this.activeProfile = null;
            this.currentPhase = 'auto'; // 'auto', 'teleop', 'endgame', 'postmatch'

            this.actionListeners = new Set();
            this.stateListeners = new Set();

            // Live state tracking
            this.livePressed = new Map();
            this.liveValues = new Map();
            this.repeatTimers = new Map();
            this.lastFireTimes = new Map();

            // Interactive capture mode
            this.isListeningForBinding = false;
            this.onBindingCaptured = null;

            this.pollingActive = false;
            this._boundPoll = this.poll.bind(this);

            this.init();
        }

        async init() {
            this.loadFromLocalStorage();
            this.setupEventListeners();
            this.startPolling();
            // Async server sync in background
            this.syncWithServer().catch(() => {});
        }

        setupEventListeners() {
            window.addEventListener('gamepadconnected', (e) => {
                const gp = e.gamepad;
                this.connectedGamepads.set(gp.index, gp);
                if (this.activeGamepadIndex === null) {
                    this.activeGamepadIndex = gp.index;
                }
                this.notifyStateChanged();
                if (window.Obsidianscout && typeof Obsidianscout.showToast === 'function') {
                    Obsidianscout.showToast(`Controller connected: ${gp.id.split('(')[0].trim()}`, 'info');
                }
            });

            window.addEventListener('gamepaddisconnected', (e) => {
                this.connectedGamepads.delete(e.gamepad.index);
                if (this.activeGamepadIndex === e.gamepad.index) {
                    const next = this.connectedGamepads.keys().next();
                    this.activeGamepadIndex = next.done ? null : next.value;
                }
                this.resetAllInputs('Gamepad disconnected');
                this.notifyStateChanged();
            });

            // Keyboard support
            window.addEventListener('keydown', (e) => this.handleKeyDown(e));
            window.addEventListener('keyup', (e) => this.handleKeyUp(e));

            // Window blur / visibility change -> reset inputs to prevent runaway repeats
            window.addEventListener('blur', () => this.resetAllInputs('Window blur'));
            document.addEventListener('visibilitychange', () => {
                if (document.hidden) this.resetAllInputs('Tab hidden');
            });
        }

        startPolling() {
            if (!this.pollingActive) {
                this.pollingActive = true;
                requestAnimationFrame(this._boundPoll);
            }
        }

        poll() {
            if (!this.pollingActive) return;

            try {
                const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
                let hasConnected = false;

                for (let i = 0; i < gamepads.length; i++) {
                    const gp = gamepads[i];
                    if (gp) {
                        hasConnected = true;
                        this.connectedGamepads.set(gp.index, gp);
                        this.processGamepadInputs(gp);
                    } else if (this.connectedGamepads.has(i)) {
                        this.connectedGamepads.delete(i);
                    }
                }

                if (!hasConnected && this.connectedGamepads.size > 0) {
                    this.connectedGamepads.clear();
                    this.notifyStateChanged();
                }
            } catch (err) {
                console.warn('[GamepadService] Polling error:', err);
            }

            requestAnimationFrame(this._boundPoll);
        }

        processGamepadInputs(gp) {
            this.lastActiveGamepad = gp;

            // 1. Process Buttons (0..16)
            for (let i = 0; i < gp.buttons.length && i < STANDARD_BUTTONS.length; i++) {
                const btn = gp.buttons[i];
                const key = STANDARD_BUTTONS[i];
                const isAnalog = key === 'trigger_l' || key === 'trigger_r';
                const threshold = isAnalog ? 0.12 : 0.35;
                const rawVal = (btn && typeof btn.value === 'number') ? btn.value : (btn && btn.pressed ? 1.0 : 0.0);
                const isPressed = isAnalog ? (rawVal >= threshold) : (btn.pressed || rawVal >= threshold);
                const value = rawVal;

                this.processInput(key, value, isPressed, isAnalog, gp);
            }

            // 2. Process Analog Sticks
            if (gp.axes && gp.axes.length >= 2) {
                this.processStickAxis('stick_l', true, gp.axes[0], gp);  // Left X
                this.processStickAxis('stick_l', false, gp.axes[1], gp); // Left Y
            }
            if (gp.axes && gp.axes.length >= 4) {
                this.processStickAxis('stick_r', true, gp.axes[2], gp);  // Right X
                this.processStickAxis('stick_r', false, gp.axes[3], gp); // Right Y
            }
        }

        processStickAxis(stickPrefix, isXAxis, value, gp) {
            const posKey = isXAxis ? `${stickPrefix}_right` : `${stickPrefix}_down`;
            const negKey = isXAxis ? `${stickPrefix}_left` : `${stickPrefix}_up`;
            const deadzone = 0.45;

            if (value > deadzone) {
                this.processInput(posKey, value, true, false, gp);
                this.processInput(negKey, 0.0, false, false, gp);
            } else if (value < -deadzone) {
                this.processInput(negKey, Math.abs(value), true, false, gp);
                this.processInput(posKey, 0.0, false, false, gp);
            } else if (Math.abs(value) < 0.2) {
                if (this.livePressed.get(posKey)) {
                    this.processInput(posKey, 0.0, false, false, gp);
                }
                if (this.livePressed.get(negKey)) {
                    this.processInput(negKey, 0.0, false, false, gp);
                }
            }
        }

        handleKeyDown(e) {
            if (this.isInputElement(document.activeElement)) return;
            const inputKey = this.normalizeKeyboardKey(e.code || e.key);
            if (!inputKey) return;

            // In interactive capture mode, capture the key
            if (this.isListeningForBinding && this.onBindingCaptured) {
                e.preventDefault();
                this.onBindingCaptured(inputKey, this.getBindingDisplayName(inputKey), false);
                this.isListeningForBinding = false;
                this.onBindingCaptured = null;
                return;
            }

            if (!this.livePressed.get(inputKey)) {
                this.processInput(inputKey, 1.0, true, false, null);
            }
        }

        handleKeyUp(e) {
            if (this.isInputElement(document.activeElement)) return;
            const inputKey = this.normalizeKeyboardKey(e.code || e.key);
            if (!inputKey) return;

            this.processInput(inputKey, 0.0, false, false, null);
        }

        isInputElement(el) {
            if (!el) return false;
            const tag = el.tagName ? el.tagName.toUpperCase() : '';
            return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
        }

        normalizeKeyboardKey(code) {
            if (!code) return null;
            const c = code.toLowerCase();
            if (c.startsWith('digit')) return `key_${c.replace('digit', '')}`;
            if (c.startsWith('numpad') && !isNaN(c.replace('numpad', ''))) return `key_${c.replace('numpad', '')}`;
            if (c.startsWith('key')) return `key_${c.replace('key', '')}`;
            if (c === 'arrowleft') return 'key_arrow_left';
            if (c === 'arrowright') return 'key_arrow_right';
            if (c === 'arrowup') return 'key_arrow_up';
            if (c === 'arrowdown') return 'key_arrow_down';
            if (c === 'space') return 'key_space';
            if (c === 'enter') return 'key_enter';
            if (c === 'tab') return 'key_tab';
            if (c === 'escape') return 'key_escape';
            if (c === 'backspace') return 'key_backspace';
            return `key_${c}`;
        }

        processInput(inputKey, value, isPressed, isAnalog, gp) {
            const wasPressed = this.livePressed.get(inputKey) || false;
            this.livePressed.set(inputKey, isPressed);
            this.liveValues.set(inputKey, value);

            // Capture mode
            if (isPressed && !wasPressed && this.isListeningForBinding && this.onBindingCaptured) {
                this.onBindingCaptured(inputKey, this.getBindingDisplayName(inputKey), isAnalog);
                this.isListeningForBinding = false;
                this.onBindingCaptured = null;
                return;
            }

            const profile = this.activeProfile;
            if (!profile || !profile.enabled) return;

            // Match bindings for this inputKey and active phase/global
            const matchingBindings = (profile.bindings || []).filter(b => {
                if (b.inputKey !== inputKey) return false;
                return b.phase === 'global' || b.phase === this.currentPhase;
            });

            if (matchingBindings.length === 0) return;

            for (const binding of matchingBindings) {
                this.handleBindingAction(binding, value, isPressed, wasPressed, gp);
            }
        }

        handleBindingAction(binding, value, isPressed, wasPressed, gp) {
            const bindingId = binding.id;

            // 1. Single Press mode
            if (binding.triggerMode === 'singlePress') {
                if (isPressed && !wasPressed) {
                    this.dispatchAction(binding, value, false, gp);
                }
                return;
            }

            // 2. Continuous Hold mode (Fixed Hz)
            if (binding.triggerMode === 'continuousHold' && binding.isRepeatable !== false) {
                if (isPressed) {
                    if (!this.repeatTimers.has(bindingId)) {
                        this.dispatchAction(binding, value, false, gp);
                        const freq = Math.max(1.0, binding.repeatFrequencyHz || 6.0);
                        const intervalMs = Math.round(1000.0 / freq);
                        this.clearRepeatTimer(bindingId);
                        const timer = setInterval(() => {
                            const isStillActive = this.isBindingActive(binding) && this.livePressed.get(binding.inputKey);
                            if (isStillActive) {
                                this.dispatchAction(binding, this.liveValues.get(binding.inputKey) || 1.0, true, gp || this.lastActiveGamepad);
                            } else {
                                this.clearRepeatTimer(bindingId);
                            }
                        }, intervalMs);
                        this.repeatTimers.set(bindingId, timer);
                    }
                } else {
                    this.clearRepeatTimer(bindingId);
                }
                return;
            }

            // 3. Scaled Trigger mode (Variable speed dynamically responsive to live trigger pressure)
            if (binding.triggerMode === 'scaledTrigger' && binding.isRepeatable !== false) {
                this.handleScaledTrigger(binding, value, isPressed, wasPressed, gp);
            }
        }

        isBindingActive(binding) {
            if (!binding) return false;
            const profile = this.activeProfile;
            if (!profile || !profile.enabled) return false;
            if (binding.phase && binding.phase !== 'global' && binding.phase !== this.currentPhase) return false;
            return true;
        }

        handleScaledTrigger(binding, value, isPressed, wasPressed, gp) {
            const bindingId = binding.id;
            const threshold = binding.triggerThreshold || 0.15;
            const effectiveVal = Math.abs(value !== undefined ? value : (this.liveValues.get(binding.inputKey) || 0.0));

            if (!isPressed || effectiveVal < threshold || !this.isBindingActive(binding)) {
                this.clearRepeatTimer(bindingId);
                this.lastFireTimes.delete(bindingId);
                return;
            }

            const now = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
            const minHz = Math.max(1.0, binding.triggerMinHz || 2.0);
            const maxHz = Math.max(minHz, binding.triggerMaxHz || 16.0);
            const normalizedPressure = Math.min(1.0, Math.max(0.0, (effectiveVal - threshold) / (1.0 - threshold)));
            const currentHz = minHz + (maxHz - minHz) * normalizedPressure;
            const intervalMs = Math.max(20, Math.round(1000.0 / currentHz));

            if (!this.lastFireTimes.has(bindingId)) {
                // Initial trigger pull or first trigger in this phase
                this.dispatchAction(binding, effectiveVal, false, gp);
                this.lastFireTimes.set(bindingId, now);
                this.scheduleScaledNext(binding, intervalMs, gp);
                return;
            }

            const lastFire = this.lastFireTimes.get(bindingId);
            const elapsed = now - lastFire;

            if (elapsed >= intervalMs) {
                // Pressure deepened and elapsed time already satisfies the faster rate -> fire immediately!
                this.dispatchAction(binding, effectiveVal, true, gp);
                this.lastFireTimes.set(bindingId, now);
                this.scheduleScaledNext(binding, intervalMs, gp);
            } else {
                // Reschedule next fire with the updated interval
                const remaining = intervalMs - elapsed;
                this.scheduleScaledNext(binding, remaining, gp);
            }
        }

        scheduleScaledNext(binding, delayMs, gp) {
            const bindingId = binding.id;
            this.clearRepeatTimer(bindingId);

            const timer = setTimeout(() => {
                const liveVal = Math.abs(this.liveValues.get(binding.inputKey) || 0.0);
                const threshold = binding.triggerThreshold || 0.15;
                const isStillPressed = this.livePressed.get(binding.inputKey);
                const isActive = this.isBindingActive(binding);

                if (isActive && isStillPressed && liveVal >= threshold) {
                    const now = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
                    this.dispatchAction(binding, liveVal, true, gp || this.lastActiveGamepad);
                    this.lastFireTimes.set(bindingId, now);

                    const minHz = Math.max(1.0, binding.triggerMinHz || 2.0);
                    const maxHz = Math.max(minHz, binding.triggerMaxHz || 16.0);
                    const normalizedPressure = Math.min(1.0, Math.max(0.0, (liveVal - threshold) / (1.0 - threshold)));
                    const nextHz = minHz + (maxHz - minHz) * normalizedPressure;
                    const nextIntervalMs = Math.max(20, Math.round(1000.0 / nextHz));

                    this.scheduleScaledNext(binding, nextIntervalMs, gp || this.lastActiveGamepad);
                } else {
                    this.clearRepeatTimer(bindingId);
                    this.lastFireTimes.delete(bindingId);
                }
            }, Math.max(0, Math.round(delayMs)));

            this.repeatTimers.set(bindingId, timer);
        }

        clearRepeatTimer(bindingId) {
            if (this.repeatTimers.has(bindingId)) {
                clearTimeout(this.repeatTimers.get(bindingId));
                clearInterval(this.repeatTimers.get(bindingId));
                this.repeatTimers.delete(bindingId);
            }
        }

        clearAllRepeatTimers() {
            for (const timer of this.repeatTimers.values()) {
                clearTimeout(timer);
                clearInterval(timer);
            }
            this.repeatTimers.clear();
            this.lastFireTimes.clear();
        }

        resetAllInputs(reason) {
            this.clearAllRepeatTimers();
            this.livePressed.clear();
            this.liveValues.clear();
            if (this.isListeningForBinding) {
                this.isListeningForBinding = false;
                this.onBindingCaptured = null;
            }
            if (reason) console.log(`[GamepadService] Reset inputs: ${reason}`);
        }

        setPhase(phase) {
            const newPhase = (phase || 'auto').toLowerCase();
            if (this.currentPhase === newPhase) return;
            this.currentPhase = newPhase;

            // Clear any active timers and fire records for bindings that do not belong to the new phase
            const profile = this.activeProfile;
            if (profile && Array.isArray(profile.bindings)) {
                for (const binding of profile.bindings) {
                    if (binding.phase && binding.phase !== 'global' && binding.phase !== newPhase) {
                        this.clearRepeatTimer(binding.id);
                        this.lastFireTimes.delete(binding.id);
                    }
                }
            } else {
                this.clearAllRepeatTimers();
            }
        }

        dispatchAction(binding, value, isRepeat, gp) {
            const event = {
                binding,
                value,
                isRepeat,
                timestamp: new Date()
            };

            for (const listener of this.actionListeners) {
                try { listener(event); } catch (e) { console.error(e); }
            }

            // Haptics & Vibration
            this.triggerHaptics(binding, value, isRepeat, gp);
        }

        async triggerHaptics(binding, value, isRepeat, gp) {
            const profile = this.activeProfile;
            if (!profile || !profile.hapticEnabled) return;
            const strength = Math.min(1.0, Math.max(0.0, profile.hapticStrength !== undefined ? profile.hapticStrength : 1.0));
            if (strength <= 0) return;

            const targetGp = gp || this.lastActiveGamepad || this.connectedGamepads.values().next().value;
            if (targetGp && targetGp.vibrationActuator && typeof targetGp.vibrationActuator.playEffect === 'function') {
                try {
                    let duration = isRepeat ? 30 : 50;
                    let weak = 0.5 * strength;
                    let strong = 0.25 * strength;

                    if (binding.actionType === 'switchTab') {
                        duration = 90;
                        weak = 0.8 * strength;
                        strong = 0.4 * strength;
                    } else if (binding.actionType === 'submit' || binding.actionType === 'barcode') {
                        duration = 160;
                        weak = 0.9 * strength;
                        strong = 0.7 * strength;
                    } else if (binding.triggerMode === 'scaledTrigger') {
                        weak = (0.3 + 0.6 * Math.abs(value)) * strength;
                        strong = weak * 0.4;
                    }

                    await targetGp.vibrationActuator.playEffect('dual-rumble', {
                        startDelay: 0,
                        duration: duration,
                        weakMagnitude: Math.min(1.0, weak),
                        strongMagnitude: Math.min(1.0, strong)
                    });
                } catch (_) {}
            }
        }

        async testRumble(strengthVal) {
            const profile = this.activeProfile;
            const strength = strengthVal !== undefined ? strengthVal : (profile ? profile.hapticStrength : 1.0);
            if (strength <= 0) return true;

            const targetGp = this.lastActiveGamepad || this.connectedGamepads.values().next().value;
            if (targetGp && targetGp.vibrationActuator && typeof targetGp.vibrationActuator.playEffect === 'function') {
                try {
                    await targetGp.vibrationActuator.playEffect('dual-rumble', {
                        startDelay: 0,
                        duration: 250,
                        weakMagnitude: Math.min(1.0, 0.9 * strength),
                        strongMagnitude: Math.min(1.0, 0.6 * strength)
                    });
                    return true;
                } catch (_) {
                    return false;
                }
            }
            return false;
        }

        onAction(callback) {
            this.actionListeners.add(callback);
            return () => this.actionListeners.delete(callback);
        }

        onStateChange(callback) {
            this.stateListeners.add(callback);
            return () => this.stateListeners.delete(callback);
        }

        onStateChanged(callback) {
            return this.onStateChange(callback);
        }

        notifyStateChanged() {
            for (const listener of this.stateListeners) {
                try { listener(this); } catch (e) { console.error(e); }
            }
            const formFields = document.getElementById("form-fields");
            if (formFields) {
                this.renderKeybindBadges(formFields, this.currentPhase);
            }
        }

        getProfiles() {
            return this.profiles || [];
        }

        getActiveProfile() {
            if (!this.activeProfile && this.profiles.length > 0) {
                this.activeProfile = this.profiles[0];
            }
            return this.activeProfile;
        }

        setActiveProfile(profileId) {
            const match = this.profiles.find(p => p.id === profileId);
            if (match) {
                this.clearAllRepeatTimers();
                this.activeProfile = match;
                this.saveToLocalStorage();
                this.notifyStateChanged();
            }
            return this.activeProfile;
        }

        getConnectedGamepads() {
            const list = [];
            for (const gp of this.connectedGamepads.values()) {
                if (gp) list.push(gp);
            }
            return list;
        }

        startListeningForBinding(callback) {
            this.isListeningForBinding = true;
            this.onBindingCaptured = callback;
        }

        stopListeningForBinding() {
            this.isListeningForBinding = false;
            this.onBindingCaptured = null;
        }

        // ==========================================
        // PROFILES, LOCAL CACHE & SERVER SYNC
        // ==========================================

        loadFromLocalStorage() {
            try {
                const stored = localStorage.getItem(STORAGE_KEY_PROFILES);
                if (stored) {
                    const parsed = JSON.parse(stored);
                    if (Array.isArray(parsed) && parsed.length > 0) {
                        this.profiles = parsed;
                    }
                }
                const activeId = localStorage.getItem(STORAGE_KEY_ACTIVE_ID);
                if (activeId) {
                    this.activeProfile = this.profiles.find(p => p.id === activeId) || this.profiles[0] || null;
                } else if (this.profiles.length > 0) {
                    this.activeProfile = this.profiles[0];
                }
            } catch (e) {
                console.warn('[GamepadService] Failed to load from local storage:', e);
            }

            if (!this.activeProfile) {
                const defaultProfile = this.createDefaultXboxProfile();
                this.profiles = [defaultProfile];
                this.activeProfile = defaultProfile;
                this.saveToLocalStorage();
            }
        }

        saveToLocalStorage() {
            try {
                localStorage.setItem(STORAGE_KEY_PROFILES, JSON.stringify(this.profiles));
                if (this.activeProfile) {
                    localStorage.setItem(STORAGE_KEY_ACTIVE_ID, this.activeProfile.id);
                }
            } catch (e) {
                console.warn('[GamepadService] Failed to save to local storage:', e);
            }
        }

        async syncWithServer(forceRefresh = false) {
            try {
                const res = await fetch('/api/gamepad/profiles', {
                    headers: { 'Accept': 'application/json' }
                });
                if (res.ok) {
                    const data = await res.json();
                    const serverProfiles = data.profiles || (Array.isArray(data) ? data : []);
                    if (serverProfiles.length > 0) {
                        if (forceRefresh) {
                            this.profiles = serverProfiles;
                        } else {
                            const serverIds = new Set(serverProfiles.map(p => p.id));
                            const localOnly = this.profiles.filter(p => !serverIds.has(p.id));
                            this.profiles = [...serverProfiles, ...localOnly];
                        }
                        const activeId = localStorage.getItem(STORAGE_KEY_ACTIVE_ID);
                        this.activeProfile = this.profiles.find(p => p.id === activeId) || this.profiles[0];
                        this.saveToLocalStorage();
                        this.notifyStateChanged();
                        return true;
                    }
                    return true;
                }
                return false;
            } catch (e) {
                console.warn('[GamepadService] syncWithServer failed (server offline):', e);
                return false;
            }
        }

        async saveProfile(profile, pushToServer = false) {
            const index = this.profiles.findIndex(p => p.id === profile.id);
            const toSave = Object.assign({}, profile, { updatedAt: new Date().toISOString() });
            if (index >= 0) {
                this.profiles[index] = toSave;
            } else {
                this.profiles.push(toSave);
            }
            if (this.activeProfile?.id === toSave.id || !this.activeProfile) {
                this.activeProfile = toSave;
            }
            this.saveToLocalStorage();
            this.notifyStateChanged();

            if (pushToServer) {
                await this.saveProfileToServer(toSave);
            }
            return toSave;
        }

        async saveActiveProfileToServer() {
            const profile = this.getActiveProfile();
            if (!profile) return false;
            return await this.saveProfile(profile, true);
        }

        async saveProfileToServer(profile) {
            try {
                const res = await fetch('/api/gamepad/profiles', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                    body: JSON.stringify(profile)
                });
                return res.ok;
            } catch (e) {
                console.warn('[GamepadService] Offline - profile saved locally only:', e);
                return false;
            }
        }

        async deleteProfile(profileId) {
            this.profiles = this.profiles.filter(p => p.id !== profileId);
            if (this.activeProfile?.id === profileId) {
                this.activeProfile = this.profiles[0] || this.createDefaultXboxProfile();
            }
            this.saveToLocalStorage();
            this.notifyStateChanged();

            try {
                await fetch(`/api/gamepad/profiles/${encodeURIComponent(profileId)}`, {
                    method: 'DELETE',
                    headers: { 'X-Requested-With': 'XMLHttpRequest' }
                });
            } catch (_) {}
        }

        createDefaultXboxProfile() {
            return {
                schemaVersion: 1,
                id: 'default_xbox',
                name: 'Standard Xbox Layout',
                description: 'Default layout with bumper tab switching and trigger rapid fire.',
                controllerType: 'xbox',
                enabled: true,
                showTooltips: true,
                hapticEnabled: true,
                hapticStrength: 1.0,
                updatedAt: new Date().toISOString(),
                bindings: [
                    { id: 'b_tab_prev', inputKey: 'shoulder_l', actionType: 'switchTab', targetValue: 'prev', phase: 'global', triggerMode: 'singlePress' },
                    { id: 'b_tab_next', inputKey: 'shoulder_r', actionType: 'switchTab', targetValue: 'next', phase: 'global', triggerMode: 'singlePress' },
                    { id: 'b_submit', inputKey: 'button_start', actionType: 'submit', phase: 'global', triggerMode: 'singlePress' },
                    { id: 'b_barcode', inputKey: 'button_back', actionType: 'barcode', phase: 'global', triggerMode: 'singlePress' }
                ]
            };
        }

        autoGenerateForConfig(config, controllerType = 'xbox', profileName = null) {
            let rawFields = [];
            if (Array.isArray(config)) {
                rawFields = config;
            } else if (config && Array.isArray(config.fields)) {
                rawFields = config.fields;
            } else if (config && config.config && Array.isArray(config.config.fields)) {
                rawFields = config.config.fields;
            } else if (typeof config === 'string') {
                try {
                    const parsed = JSON.parse(config);
                    rawFields = Array.isArray(parsed) ? parsed : (parsed.fields || []);
                } catch (_) {}
            }

            const fields = rawFields.filter(f => f && f.type !== 'section' && f.type !== 'header');
            const counters = fields.filter(f => {
                const t = (f.type || '').toLowerCase();
                return t === 'counter' || t === 'number' || t === 'stepper' || t === 'rating' || t === 'slider' || t === 'range';
            });
            const toggles = fields.filter(f => {
                const t = (f.type || '').toLowerCase();
                return t === 'toggle' || t === 'checkbox' || t === 'boolean';
            });
            const choices = fields.filter(f => {
                const t = (f.type || '').toLowerCase();
                return t === 'select' || t === 'dropdown' || t === 'radio' || t === 'choice' || t === 'multiselect';
            });

            const resolveFieldPhase = (f) => {
                if (f.phase) {
                    const p = String(f.phase).toLowerCase().trim();
                    if (p === 'general') return 'teleop';
                    return p;
                }
                const id = String(f.id || '').toLowerCase();
                if (id.startsWith('auto')) return 'auto';
                if (id.startsWith('teleop')) return 'teleop';
                if (id.startsWith('endgame')) return 'endgame';
                if (id.startsWith('post')) return 'postmatch';
                return 'global';
            };

            if (controllerType === 'keyboard') {
                const kbBindings = [
                    { id: 'kb_tab_prev', inputKey: 'key_arrow_left', actionType: 'switchTab', targetValue: 'prev', phase: 'global', triggerMode: 'singlePress' },
                    { id: 'kb_tab_next', inputKey: 'key_arrow_right', actionType: 'switchTab', targetValue: 'next', phase: 'global', triggerMode: 'singlePress' },
                    { id: 'kb_submit', inputKey: 'key_enter', actionType: 'submit', phase: 'global', triggerMode: 'singlePress' },
                    { id: 'kb_barcode', inputKey: 'key_b', actionType: 'barcode', phase: 'global', triggerMode: 'singlePress' }
                ];

                const phases = ['auto', 'teleop', 'endgame'];
                const numKeys = ['key_1', 'key_2', 'key_3', 'key_4', 'key_5', 'key_6'];
                const decKeys = ['key_q', 'key_w', 'key_e', 'key_r', 'key_t', 'key_y'];

                phases.forEach(p => {
                    const pCounters = counters.filter(c => resolveFieldPhase(c) === p);
                    const pToggles = toggles.filter(t => resolveFieldPhase(t) === p);
                    const pChoices = choices.filter(c => resolveFieldPhase(c) === p);

                    pCounters.forEach((c, i) => {
                        if (i < numKeys.length) {
                            kbBindings.push({
                                id: `kb_${p}_inc_${c.id}`,
                                inputKey: numKeys[i],
                                actionType: 'increment',
                                targetFieldId: c.id,
                                phase: p,
                                triggerMode: 'continuousHold',
                                repeatFrequencyHz: 6.0
                            });
                        }
                        if (i < decKeys.length) {
                            kbBindings.push({
                                id: `kb_${p}_dec_${c.id}`,
                                inputKey: decKeys[i],
                                actionType: 'decrement',
                                targetFieldId: c.id,
                                phase: p,
                                triggerMode: 'continuousHold',
                                repeatFrequencyHz: 5.0
                            });
                        }
                    });

                    if (pToggles.length > 0) {
                        kbBindings.push({
                            id: `kb_${p}_tog_${pToggles[0].id}`,
                            inputKey: 'key_space',
                            actionType: 'toggle',
                            targetFieldId: pToggles[0].id,
                            phase: p,
                            triggerMode: 'singlePress'
                        });
                    }
                    if (pChoices.length > 0) {
                        kbBindings.push({
                            id: `kb_${p}_choice_${pChoices[0].id}`,
                            inputKey: 'key_c',
                            actionType: 'cycleOption',
                            targetFieldId: pChoices[0].id,
                            phase: p,
                            triggerMode: 'singlePress'
                        });
                    }
                });

                return {
                    schemaVersion: 1,
                    id: `auto_gen_kb_${Date.now()}`,
                    name: profileName || 'Smart Keyboard Layout',
                    description: 'Period-aware layout mapped to numbers, shortcuts & spacebar.',
                    controllerType: 'keyboard',
                    enabled: true,
                    showTooltips: true,
                    hapticEnabled: true,
                    hapticStrength: 1.0,
                    updatedAt: new Date().toISOString(),
                    bindings: kbBindings
                };
            }

            const newBindings = [
                { id: 'auto_gen_lb', inputKey: 'shoulder_l', actionType: 'switchTab', targetValue: 'prev', phase: 'global', triggerMode: 'singlePress' },
                { id: 'auto_gen_rb', inputKey: 'shoulder_r', actionType: 'switchTab', targetValue: 'next', phase: 'global', triggerMode: 'singlePress' },
                { id: 'auto_gen_start', inputKey: 'button_start', actionType: 'submit', phase: 'global', triggerMode: 'singlePress' },
                { id: 'auto_gen_back', inputKey: 'button_back', actionType: 'barcode', phase: 'global', triggerMode: 'singlePress' }
            ];

            const bindPhase = (p) => {
                const pCounters = counters.filter(c => resolveFieldPhase(c) === p);
                const pToggles = toggles.filter(t => resolveFieldPhase(t) === p);
                const pChoices = choices.filter(c => resolveFieldPhase(c) === p);

                if (pCounters.length > 0) {
                    newBindings.push({
                        id: `${p}_gen_rt`,
                        inputKey: 'trigger_r',
                        actionType: 'increment',
                        targetFieldId: pCounters[0].id,
                        phase: p,
                        triggerMode: 'scaledTrigger',
                        triggerMinHz: 2.0,
                        triggerMaxHz: 16.0
                    });

                    if (pCounters.length > 1) {
                        newBindings.push({
                            id: `${p}_gen_lt`,
                            inputKey: 'trigger_l',
                            actionType: 'increment',
                            targetFieldId: pCounters[1].id,
                            phase: p,
                            triggerMode: 'scaledTrigger',
                            triggerMinHz: 2.0,
                            triggerMaxHz: 14.0
                        });
                    } else {
                        newBindings.push({
                            id: `${p}_gen_lt_dec`,
                            inputKey: 'trigger_l',
                            actionType: 'decrement',
                            targetFieldId: pCounters[0].id,
                            phase: p,
                            triggerMode: 'scaledTrigger',
                            triggerMinHz: 2.0,
                            triggerMaxHz: 12.0
                        });
                    }
                }

                if (pCounters.length > 2) {
                    newBindings.push({
                        id: `${p}_gen_btn_y`,
                        inputKey: 'button_y',
                        actionType: 'increment',
                        targetFieldId: pCounters[2].id,
                        phase: p,
                        triggerMode: 'continuousHold',
                        repeatFrequencyHz: 6.0
                    });
                } else if (pCounters.length > 0 && !newBindings.some(b => b.phase === p && b.inputKey === 'trigger_l' && b.actionType === 'decrement')) {
                    newBindings.push({
                        id: `${p}_gen_btn_y`,
                        inputKey: 'button_y',
                        actionType: 'decrement',
                        targetFieldId: pCounters[0].id,
                        phase: p,
                        triggerMode: 'continuousHold',
                        repeatFrequencyHz: 5.0
                    });
                }

                if (pCounters.length > 3) {
                    newBindings.push({
                        id: `${p}_gen_btn_x`,
                        inputKey: 'button_x',
                        actionType: 'increment',
                        targetFieldId: pCounters[3].id,
                        phase: p,
                        triggerMode: 'continuousHold',
                        repeatFrequencyHz: 6.0
                    });
                } else if (pCounters.length > 1) {
                    newBindings.push({
                        id: `${p}_gen_btn_x`,
                        inputKey: 'button_x',
                        actionType: 'decrement',
                        targetFieldId: pCounters[1].id,
                        phase: p,
                        triggerMode: 'continuousHold',
                        repeatFrequencyHz: 5.0
                    });
                }

                if (pToggles.length > 0) {
                    newBindings.push({
                        id: `${p}_gen_btn_a`,
                        inputKey: 'button_a',
                        actionType: 'toggle',
                        targetFieldId: pToggles[0].id,
                        phase: p,
                        triggerMode: 'singlePress'
                    });
                }
                if (pChoices.length > 0) {
                    newBindings.push({
                        id: `${p}_gen_btn_b`,
                        inputKey: 'button_b',
                        actionType: 'cycleOption',
                        targetFieldId: pChoices[0].id,
                        phase: p,
                        triggerMode: 'singlePress'
                    });
                }
            };

            bindPhase('auto');
            bindPhase('teleop');
            bindPhase('endgame');

            return {
                schemaVersion: 1,
                id: `auto_gen_${Date.now()}`,
                name: profileName || (controllerType === 'playstation' ? 'Smart PS4/PS5 Layout' : 'Smart Xbox Layout'),
                description: 'Auto-generated smart layout mapped to scouting form fields.',
                controllerType: controllerType,
                enabled: true,
                showTooltips: true,
                hapticEnabled: true,
                hapticStrength: 1.0,
                updatedAt: new Date().toISOString(),
                bindings: newBindings
            };
        }

        // ==========================================
        // KEY / BUTTON BADGE LABELS & FORMATTING
        // ==========================================

        getBindingDisplayName(key, type = 'xbox') {
            const controllerType = (type || this.activeProfile?.controllerType || 'xbox').toLowerCase();
            const isPs = controllerType === 'playstation';

            switch (key?.toLowerCase()) {
                case 'button_a':
                case 'button_south':
                case 'buttona':
                    return isPs ? '✕ Cross' : 'A';
                case 'button_b':
                case 'button_east':
                case 'buttonb':
                    return isPs ? '○ Circle' : 'B';
                case 'button_x':
                case 'button_west':
                case 'buttonx':
                    return isPs ? '□ Square' : 'X';
                case 'button_y':
                case 'button_north':
                case 'buttony':
                    return isPs ? '△ Triangle' : 'Y';
                case 'shoulder_l':
                case 'l1':
                case 'leftshoulder':
                    return isPs ? 'L1' : 'LB';
                case 'shoulder_r':
                case 'r1':
                case 'rightshoulder':
                    return isPs ? 'R1' : 'RB';
                case 'trigger_l':
                case 'l2':
                case 'lefttrigger':
                    return isPs ? 'L2 (Trigger)' : 'LT (Trigger)';
                case 'trigger_r':
                case 'r2':
                case 'righttrigger':
                    return isPs ? 'R2 (Trigger)' : 'RT (Trigger)';
                case 'button_back':
                case 'select':
                case 'view':
                case 'share':
                    return isPs ? 'Share' : 'View (⧉)';
                case 'button_start':
                case 'start':
                case 'options':
                case 'menu':
                    return isPs ? 'Options' : 'Menu (≡)';
                case 'thumb_l':
                case 'l3':
                case 'leftthumbstick':
                    return isPs ? 'L3' : 'LS (Left Stick)';
                case 'thumb_r':
                case 'r3':
                case 'rightthumbstick':
                    return isPs ? 'R3' : 'RS (Right Stick)';
                case 'dpad_up':
                case 'dpadup':
                    return 'D-Pad ↑';
                case 'dpad_down':
                case 'dpaddown':
                    return 'D-Pad ↓';
                case 'dpad_left':
                case 'dpadleft':
                    return 'D-Pad ←';
                case 'dpad_right':
                case 'dpadright':
                    return 'D-Pad →';
                case 'stick_l_up': return 'L-Stick ↑';
                case 'stick_l_down': return 'L-Stick ↓';
                case 'stick_l_left': return 'L-Stick ←';
                case 'stick_l_right': return 'L-Stick →';
                case 'stick_r_up': return 'R-Stick ↑';
                case 'stick_r_down': return 'R-Stick ↓';
                case 'stick_r_left': return 'R-Stick ←';
                case 'stick_r_right': return 'R-Stick →';
                case 'key_space': return 'Spacebar';
                case 'key_enter': return 'Enter (⏎)';
                case 'key_tab': return 'Tab (⇥)';
                case 'key_escape': return 'Esc';
                case 'key_backspace': return 'Backspace (⌫)';
                case 'key_arrow_left': return 'Arrow ←';
                case 'key_arrow_right': return 'Arrow →';
                case 'key_arrow_up': return 'Arrow ↑';
                case 'key_arrow_down': return 'Arrow ↓';
                case 'key_shift': return 'Shift';
                case 'key_control': return 'Ctrl';
                case 'key_alt': return 'Alt';
                default:
                    if (key && key.toLowerCase().startsWith('key_')) {
                        return `Key ${key.substring(4).toUpperCase()}`;
                    }
                    return key ? key.replace('_', ' ').toUpperCase() : '';
            }
        }

        getShortBadgeLabel(key, type = 'xbox') {
            const controllerType = (type || this.activeProfile?.controllerType || 'xbox').toLowerCase();
            const isPs = controllerType === 'playstation';

            switch (key?.toLowerCase()) {
                case 'button_a':
                case 'button_south':
                case 'buttona':
                    return isPs ? '✕' : 'A';
                case 'button_b':
                case 'button_east':
                case 'buttonb':
                    return isPs ? '○' : 'B';
                case 'button_x':
                case 'button_west':
                case 'buttonx':
                    return isPs ? '□' : 'X';
                case 'button_y':
                case 'button_north':
                case 'buttony':
                    return isPs ? '△' : 'Y';
                case 'shoulder_l':
                case 'l1':
                case 'leftshoulder':
                    return isPs ? 'L1' : 'LB';
                case 'shoulder_r':
                case 'r1':
                case 'rightshoulder':
                    return isPs ? 'R1' : 'RB';
                case 'trigger_l':
                case 'l2':
                case 'lefttrigger':
                    return isPs ? 'L2' : 'LT';
                case 'trigger_r':
                case 'r2':
                case 'righttrigger':
                    return isPs ? 'R2' : 'RT';
                case 'dpad_up':
                case 'dpadup':
                    return '▲';
                case 'dpad_down':
                case 'dpaddown':
                    return '▼';
                case 'dpad_left':
                case 'dpadleft':
                    return '◄';
                case 'dpad_right':
                case 'dpadright':
                    return '►';
                case 'thumb_l':
                case 'l3':
                case 'leftthumbstick':
                    return 'L3';
                case 'thumb_r':
                case 'r3':
                case 'rightthumbstick':
                    return 'R3';
                case 'button_start':
                case 'buttonstart':
                case 'menu':
                case 'options':
                    return isPs ? 'Options' : '≡';
                case 'button_back':
                case 'buttonback':
                case 'view':
                case 'select':
                case 'share':
                    return isPs ? 'Share' : '⧉';
                case 'key_space': return '␣';
                case 'key_enter': return '⏎';
                case 'key_tab': return '⇥';
                case 'key_escape': return 'Esc';
                case 'key_backspace': return '⌫';
                case 'key_arrow_up': return '↑';
                case 'key_arrow_down': return '↓';
                case 'key_arrow_left': return '←';
                case 'key_arrow_right': return '→';
                default:
                    if (key && key.toLowerCase().startsWith('key_')) {
                        return key.substring(4).toUpperCase();
                    }
                    return key ? key.toUpperCase() : '';
            }
        }

        renderKeybindBadges(container = document, phase = this.currentPhase) {
            const profile = this.activeProfile;
            if (!profile || !profile.enabled || !profile.showTooltips) {
                container.querySelectorAll('.gamepad-key-badge').forEach(el => el.remove());
                return;
            }

            container.querySelectorAll('.gamepad-key-badge').forEach(el => el.remove());

            const activeBindings = (profile.bindings || []).filter(b => b.phase === 'global' || b.phase === phase);

            activeBindings.forEach(binding => {
                if (!binding.targetFieldId) return;
                const fieldWrapper = container.querySelector(`[data-field-id="${binding.targetFieldId}"]`) ||
                                     container.querySelector(`#field-${binding.targetFieldId}`)?.closest('.field') ||
                                     container.querySelector(`#field-${binding.targetFieldId}`)?.closest('.counter');

                if (fieldWrapper) {
                    const labelEl = fieldWrapper.querySelector('label') || fieldWrapper;
                    let badge = labelEl.querySelector(`.gamepad-key-badge[data-binding-id="${binding.id}"]`);
                    if (!badge) {
                        badge = document.createElement('span');
                        badge.className = 'gamepad-key-badge';
                        badge.dataset.bindingId = binding.id;
                        const symbol = binding.actionType === 'increment' ? '+' : (binding.actionType === 'decrement' ? '-' : '');
                        badge.textContent = `[${this.getBindingDisplayName(binding.inputKey)}${symbol}]`;
                        labelEl.appendChild(badge);
                    }
                }
            });
        }
    }

    // Export singleton on window
    window.GamepadService = new GamepadService();

})(window);
