/**
 * Component Connection Widget Module - ObsidianScout
 * Online/offline status widget, pending offline entry badge, and server version indicator.
 * Accurately probes and tracks server reachability (not just browser navigator.onLine).
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';
import { t } from '../base/i18n.js';
import { CACHE_CONFIGS } from '../services/offline-sync.js';

let _serverOnline = typeof navigator !== 'undefined' ? navigator.onLine : true;
let _isProbing = false;
let _probeTimer = null;
let _monitoringStarted = false;

export function isServerOnline() {
    return _serverOnline && (typeof navigator === 'undefined' || navigator.onLine);
}

export function setServerOnline(status) {
    const nextStatus = !!status;
    const changed = _serverOnline !== nextStatus;
    _serverOnline = nextStatus;
    updateConnectionStatus(isServerOnline());
    if (changed && typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent("obsidianscout:connection-changed", {
            detail: { online: isServerOnline() }
        }));
    }
}

export async function checkServerConnection({ force = false } = {}) {
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
        setServerOnline(false);
        return false;
    }

    if (_isProbing && !force) {
        return isServerOnline();
    }

    _isProbing = true;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 3500);

    try {
        const res = await fetch("/api/version", {
            method: "GET",
            cache: "no-store",
            credentials: "same-origin",
            headers: {
                "X-Requested-With": "XMLHttpRequest",
                "Cache-Control": "no-cache"
            },
            signal: controller.signal
        });

        clearTimeout(timeoutId);

        if (res.ok) {
            try {
                const data = await res.json();
                if (data && data.version) {
                    safeSetItem("obsidianscout:server_version", data.version);
                    const versionEl = document.getElementById("server-version") || document.querySelector(".sidebar #server-version");
                    if (versionEl) {
                        const displayVer = data.version.startsWith("v") ? data.version : `v${data.version}`;
                        versionEl.textContent = `Server ${displayVer}`;
                    }
                }
            } catch (jsonErr) {
                // Response was OK even if JSON parsing failed
            }
            setServerOnline(true);
            return true;
        } else {
            // 502/503/504 or server error response
            setServerOnline(false);
            return false;
        }
    } catch (e) {
        clearTimeout(timeoutId);
        setServerOnline(false);
        return false;
    } finally {
        _isProbing = false;
    }
}

export function startConnectionMonitoring() {
    if (_monitoringStarted || typeof window === 'undefined') return;
    _monitoringStarted = true;

    const scheduleNextProbe = () => {
        if (_probeTimer) clearTimeout(_probeTimer);
        const interval = document.hidden ? 20000 : 6000;
        _probeTimer = setTimeout(async () => {
            await checkServerConnection();
            scheduleNextProbe();
        }, interval);
    };

    window.addEventListener("online", () => {
        checkServerConnection({ force: true });
    });

    window.addEventListener("offline", () => {
        setServerOnline(false);
    });

    window.addEventListener("focus", () => {
        checkServerConnection({ force: true });
    });

    document.addEventListener("visibilitychange", () => {
        if (!document.hidden) {
            checkServerConnection({ force: true });
        }
        scheduleNextProbe();
    });

    scheduleNextProbe();
}

export function injectConnectionWidget(sidebar) {
    const brand = sidebar.querySelector(".sidebar-brand");
    if (!brand) return;

    let widget = sidebar.querySelector("#connection-status-widget") || document.getElementById("connection-status-widget");
    if (!widget) {
        widget = document.createElement("div");
        widget.id = "connection-status-widget";
        widget.className = `connection-widget ${isServerOnline() ? "online" : "offline"}`;
        widget.innerHTML = `
            <span class="status-dot"></span>
            <span class="status-text">${isServerOnline() ? "Online" : "Offline"}</span>
            <button id="btn-sync-offline" class="btn-sync-offline hidden">Sync (0)</button>
        `;

        const anchor = sidebar.querySelector(".sidebar-header") || brand;
        anchor.after(widget);

        const syncBtn = widget.querySelector("#btn-sync-offline");
        if (syncBtn) {
            syncBtn.addEventListener("click", (e) => {
                e.stopPropagation();
                if (window.Obsidianscout && typeof window.Obsidianscout.syncOfflineEntries === 'function') {
                    window.Obsidianscout.syncOfflineEntries();
                }
            });
        }
    }

    startConnectionMonitoring();
    updateConnectionStatus();
    checkServerConnection();
}

export function updateConnectionStatus(forcedStatus = null) {
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

    const isOnline = (forcedStatus !== null) ? !!forcedStatus : isServerOnline();

    if (isOnline) {
        widget.classList.remove("offline");
        widget.classList.add("online");
        if (text) {
            text.textContent = (typeof t === 'function') ? t('connection.online', 'Online') : 'Online';
        }
        if (syncBtn) {
            if (count > 0) {
                syncBtn.classList.remove("hidden");
                syncBtn.textContent = `${(typeof t === 'function' ? t('connection.sync','Sync') : 'Sync')} (${count})`;
                syncBtn.disabled = false;
            } else {
                syncBtn.classList.add("hidden");
            }
        }
    } else {
        widget.classList.remove("online");
        widget.classList.add("offline");
        if (text) {
            text.textContent = (typeof t === 'function') ? t('connection.offline','Offline') : 'Offline';
        }
        if (syncBtn) {
            if (count > 0) {
                syncBtn.classList.remove("hidden");
                syncBtn.textContent = `${(typeof t === 'function' ? t('connection.pending','Pending') : 'Pending')} (${count})`;
                syncBtn.disabled = true;
            } else {
                syncBtn.classList.add("hidden");
            }
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

        checkServerConnection();
    } catch (e) {
        console.warn("Failed to fetch server version:", e);
    }
}

