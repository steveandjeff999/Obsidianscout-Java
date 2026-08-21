/**
 * Component Connection Widget Module - ObsidianScout
 * Online/offline status widget, pending offline entry badge, and server version indicator.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';
import { request } from '../base/http.js';
import { t } from '../base/i18n.js';
import { CACHE_CONFIGS } from '../services/offline-sync.js';

export function injectConnectionWidget(sidebar) {
    const brand = sidebar.querySelector(".sidebar-brand");
    if (!brand) return;

    const widget = document.createElement("div");
    widget.id = "connection-status-widget";
    widget.className = "connection-widget online";
    widget.innerHTML = `
        <span class="status-dot"></span>
        <span class="status-text">Online</span>
        <button id="btn-sync-offline" class="btn-sync-offline hidden">Sync (0)</button>
    `;

    const anchor = sidebar.querySelector(".sidebar-header") || brand;
    anchor.after(widget);

    const syncBtn = widget.querySelector("#btn-sync-offline");
    syncBtn.addEventListener("click", (e) => {
        e.stopPropagation();
        if (window.Obsidianscout && typeof window.Obsidianscout.syncOfflineEntries === 'function') {
            window.Obsidianscout.syncOfflineEntries();
        }
    });

    updateConnectionStatus();
}

export function updateConnectionStatus() {
    const widget = document.getElementById("connection-status-widget");
    if (!widget) return;

    const dot = widget.querySelector(".status-dot");
    const text = widget.querySelector(".status-text");
    const syncBtn = widget.querySelector("#btn-sync-offline");

    let count = 0;
    for (const type in CACHE_CONFIGS) {
        const config = CACHE_CONFIGS[type];
        const pending = JSON.parse(safeGetItem(config.key) || "[]");
        count += pending.length;
    }

    const isOnline = navigator.onLine;

    if (isOnline) {
        widget.className = "connection-widget online";
        text.textContent = (typeof t === 'function') ? t('connection.online', 'Online') : 'Online';
        if (count > 0) {
            syncBtn.classList.remove("hidden");
            syncBtn.textContent = `${(typeof t === 'function' ? t('connection.sync','Sync') : 'Sync')} (${count})`;
            syncBtn.disabled = false;
        } else {
            syncBtn.classList.add("hidden");
        }
    } else {
        widget.className = "connection-widget offline";
        text.textContent = (typeof t === 'function') ? t('connection.offline','Offline') : 'Offline';
        if (count > 0) {
            syncBtn.classList.remove("hidden");
            syncBtn.textContent = `${(typeof t === 'function' ? t('connection.pending','Pending') : 'Pending')} (${count})`;
            syncBtn.disabled = true;
        } else {
            syncBtn.classList.add("hidden");
        }
    }
}

export async function renderServerVersion(sidebar) {
    try {
        const sb = sidebar || document.querySelector(".sidebar");
        if (!sb) return;
        const versionEl = sb.querySelector("#server-version") || document.getElementById("server-version");
        if (!versionEl) return;

        const cachedVersion = safeGetItem("obsidianscout:server_version");
        if (cachedVersion) {
            const displayVer = cachedVersion.startsWith("v") ? cachedVersion : `v${cachedVersion}`;
            versionEl.textContent = `Server ${displayVer}`;
        }

        if (navigator.onLine) {
            const data = await request("/api/version");
            if (data && data.version) {
                safeSetItem("obsidianscout:server_version", data.version);
                const displayVer = data.version.startsWith("v") ? data.version : `v${data.version}`;
                versionEl.textContent = `Server ${displayVer}`;
            }
        }
    } catch (e) {
        console.warn("Failed to fetch server version:", e);
    }
}
