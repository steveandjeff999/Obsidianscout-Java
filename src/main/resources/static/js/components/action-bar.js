/**
 * Sticky Action Bar - ObsidianScout
 *
 * Pairs with css/components/action-bar.css. Any `[data-action-bar]` inside a
 * `[data-action-scope]` gets a live status line:
 *   clean  -> "Changes are not saved automatically"
 *   dirty  -> "Unsaved changes" (after any input/change inside the scope)
 *   saving -> "Saving..." (after the `[data-action-bar-save]` button is pressed)
 *   saved  -> "All changes saved" (on the next success toast)
 *   error  -> "Save failed" (on the next error/warning toast; stays dirty)
 *
 * Everything is delegated from `document`, so pages that rebuild their DOM
 * (innerHTML re-renders) keep working without re-initialising.
 *
 * Scopes whose content syncs live can opt out of dirty tracking with
 * `data-action-bar-auto="true"` on the bar (set by the page script).
 * Page scripts can also drive it directly via window.ObsidianscoutActionBar.
 */

import { t } from '../base/i18n.js';

const SAVE_TIMEOUT_MS = 15000;
const SAVED_RESET_MS = 4000;

const STATES = {
    clean: { key: 'action_bar.not_auto_saved', text: 'Changes are not saved automatically' },
    dirty: { key: 'action_bar.unsaved', text: 'Unsaved changes' },
    saving: { key: 'action_bar.saving', text: 'Saving...' },
    saved: { key: 'action_bar.saved', text: 'All changes saved' },
    error: { key: 'action_bar.failed', text: 'Save failed - your changes are still unsaved' },
    live: { key: 'action_bar.live', text: 'Changes sync automatically' }
};

/** The save currently waiting for a toast to tell us how it went. */
let pendingBar = null;
let pendingTimer = null;
const resetTimers = new WeakMap();

function statusEl(bar) {
    let el = bar.querySelector('[data-action-bar-status]');
    if (!el) {
        el = document.createElement('span');
        el.className = 'action-bar-status';
        el.setAttribute('data-action-bar-status', '');
        bar.insertBefore(el, bar.firstChild);
    }
    if (!el.hasAttribute('role')) {
        el.setAttribute('role', 'status');
        el.setAttribute('aria-live', 'polite');
    }
    return el;
}

export function setActionBarState(bar, state, customText) {
    if (!bar) return;
    const def = STATES[state] || STATES.clean;
    bar.dataset.state = state;
    const el = statusEl(bar);
    const text = customText || t(def.key, def.text);
    el.innerHTML = '';
    const dot = document.createElement('span');
    dot.className = 'action-bar-dot';
    dot.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    label.textContent = text;
    el.append(dot, label);

    clearTimeout(resetTimers.get(bar));
    if (state === 'saved') {
        resetTimers.set(bar, setTimeout(() => {
            if (bar.dataset.state === 'saved') setActionBarState(bar, isAuto(bar) ? 'live' : 'clean');
        }, SAVED_RESET_MS));
    }
}

function isAuto(bar) {
    return bar.dataset.actionBarAuto === 'true';
}

function barsForTarget(target) {
    const scope = target.closest && target.closest('[data-action-scope]');
    if (!scope) return [];
    // Nearest bars only: a nested scope owns its own bar.
    return Array.from(scope.querySelectorAll('[data-action-bar]'))
        .filter((bar) => bar.closest('[data-action-scope]') === scope);
}

function onEdit(event) {
    const target = event.target;
    if (!target || !target.closest) return;
    if (target.closest('[data-action-bar-ignore]')) return;
    barsForTarget(target).forEach((bar) => {
        if (isAuto(bar)) return;
        if (bar.dataset.state !== 'saving') setActionBarState(bar, 'dirty');
    });
}

function finishPending(outcome) {
    if (!pendingBar) return;
    const bar = pendingBar;
    pendingBar = null;
    clearTimeout(pendingTimer);
    if (outcome === 'success') setActionBarState(bar, 'saved');
    else if (outcome === 'error') setActionBarState(bar, 'error');
    else setActionBarState(bar, isAuto(bar) ? 'live' : 'clean');
}

function onClick(event) {
    const btn = event.target.closest && event.target.closest('[data-action-bar-save]');
    if (!btn) return;
    const bar = btn.closest('[data-action-bar]');
    if (!bar) return;
    if (pendingBar && pendingBar !== bar) finishPending('timeout');
    pendingBar = bar;
    setActionBarState(bar, 'saving');
    clearTimeout(pendingTimer);
    // Saves that report nothing (no toast) fall back to the neutral state rather than claiming success.
    pendingTimer = setTimeout(() => finishPending('timeout'), SAVE_TIMEOUT_MS);
}

function onToast(event) {
    if (!pendingBar) return;
    const tone = event.detail && event.detail.tone;
    if (tone === 'success') finishPending('success');
    else if (tone === 'error' || tone === 'warning') finishPending('error');
}

/** Paint the initial status on any bar that has none yet (covers re-rendered DOM). */
function paintNewBars(root = document) {
    root.querySelectorAll('[data-action-bar]:not([data-state])').forEach((bar) => {
        setActionBarState(bar, isAuto(bar) ? 'live' : 'clean');
    });
}

let initialised = false;

export function initActionBars() {
    if (initialised) return;
    initialised = true;
    document.addEventListener('input', onEdit, true);
    document.addEventListener('change', onEdit, true);
    document.addEventListener('click', onClick, true);
    document.addEventListener('obsidianscout:toast', onToast);
    paintNewBars();
    try {
        new MutationObserver((records) => {
            for (const r of records) {
                if (r.addedNodes && r.addedNodes.length) {
                    paintNewBars();
                    return;
                }
            }
        }).observe(document.body, { childList: true, subtree: true });
    } catch (e) {
        // Older browsers: static bars are already painted.
    }
}

/** A page reporting the outcome itself settles any save still waiting on a toast. */
function settle(bar) {
    if (bar && pendingBar === bar) {
        pendingBar = null;
        clearTimeout(pendingTimer);
    }
}

const api = {
    setState: (bar, state, text) => { settle(bar); setActionBarState(bar, state, text); },
    markDirty: (bar) => { settle(bar); setActionBarState(bar, 'dirty'); },
    markSaved: (bar) => { settle(bar); setActionBarState(bar, 'saved'); },
    markFailed: (bar, text) => { settle(bar); setActionBarState(bar, 'error', text); },
    markClean: (bar) => { settle(bar); setActionBarState(bar, 'clean'); },
    /** Live-synced scopes: no dirty tracking, status reads "Changes sync automatically". */
    setAuto: (bar, auto, text) => {
        if (!bar) return;
        settle(bar);
        bar.dataset.actionBarAuto = auto ? 'true' : 'false';
        setActionBarState(bar, auto ? 'live' : 'clean', auto ? text : undefined);
    }
};

if (typeof window !== 'undefined') {
    window.ObsidianscoutActionBar = api;
}
