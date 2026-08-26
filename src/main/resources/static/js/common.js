/**
 * ==========================================================================
 * ObsidianScout Master JavaScript Entry Point
 * Modularized Architecture
 * ==========================================================================
 */

// 1. Base Layer
import {
    safeGetItem,
    safeSetItem,
    safeRemoveItem,
    clearAllCaches,
    saveScrollPositions,
    restoreScrollPositions
} from './base/storage.js';

import {
    request,
    getCsrfToken,
    setButtonLoading,
    withButtonLoading,
    safeParse
} from './base/http.js';

import {
    ROLE_HIERARCHY,
    checkLoginStatus,
    getMe,
    requireAuth,
    hasRole,
    isAdmin,
    isSuperAdmin,
    canAccessAnalytics,
    wireLogout
} from './base/auth.js';

import {
    DEFAULT_LANG,
    currentLang,
    loadLocale,
    t,
    localize,
    setLanguage,
    applyTranslations,
    injectLanguageSelector
} from './base/i18n.js';

// 2. Layout Layer
import {
    initTheme,
    wireThemeToggle,
    applyCustomTheme,
    toggleThemeMode,
    bindThemeToggleButtons
} from './layout/theme.js';

import {
    setUserBadge,
    refreshNavAvatar,
    setActiveNav,
    adjustNavForRole,
    isPageAccessible,
    ensureSidebarAndFooter
} from './layout/navigation.js';

import {
    applyNavLayout,
    restoreSidebarLayout,
    setupTopbarDropdowns,
    wireSidebarToggle,
    sidebarCollapseKey
} from './layout/nav-layouts.js';

import {
    injectMobileTopBar
} from './layout/mobile-topbar.js';

// 3. Components Layer
import {
    showToast
} from './components/toast.js';

import {
    loadAndRenderBanners
} from './components/banners.js';

import {
    injectConnectionWidget,
    updateConnectionStatus,
    renderServerVersion,
    isServerOnline,
    checkServerConnection,
    setServerOnline,
    startConnectionMonitoring
} from './components/connection-widget.js';

import {
    showImageModal,
    openInlineCameraModal
} from './components/modals.js';

import {
    showQrModal
} from './components/qr-modal.js';

import {
    showSetupWizardModal
} from './components/setup-wizard.js';

import {
    openConflictResolutionModal
} from './components/conflict-modal.js';

import {
    initTour,
    startTour,
    endTour,
    showTourLevelSelector,
    runActiveTourStep,
    displayTourStepPopup,
    positionPopupNextToElement,
    clearTourDOM,
    syncTourProgressToServer,
    getTourStepsForRoleAndLevel
} from './components/tour-wizard.js';

// 4. Services Layer
import {
    CACHE_CONFIGS,
    syncOfflineEntries,
    syncOfflineCache
} from './services/offline-sync.js';

import {
    downloadJson,
    compressData,
    decompressData,
    compressAndChunkData
} from './services/data-compression.js';

import {
    initChatUnreadPolling
} from './services/chat-poller.js';

// 5. Utilities Layer
import {
    formatTimestamp,
    getDeviceTimezone,
    formatTimestampWithVenueTooltip,
    localToUtcEpoch,
    resolveEventKey,
    formatTeam,
    showLoadingSpinner,
    showRetryButton,
    getProgram,
    getProgramPrefix
} from './utilities/helpers.js';

import {
    triggerHaptic,
    initHapticDelegation
} from './utilities/haptics.js';

import {
    processImageUpload
} from './utilities/media.js';

import {
    injectLiquidGlassSVG
} from './utilities/svg-filters.js';

console.log("[CommonJS] Script initialized and loaded (Modular Architecture).");

// Export public API to window.Obsidianscout
window.Obsidianscout = {
    getProgram,
    getProgramPrefix,
    request,
    getMe,
    checkLoginStatus,
    requireAuth,
    showToast,
    triggerHaptic,
    setUserBadge,
    refreshNavAvatar,
    setActiveNav,
    adjustNavForRole,
    wireLogout,
    initTheme,
    wireThemeToggle,
    applyNavLayout,
    formatTimestamp,
    getDeviceTimezone,
    formatTimestampWithVenueTooltip,
    localToUtcEpoch,
    resolveEventKey,
    hasRole,
    isAdmin,
    isSuperAdmin,
    canAccessAnalytics,
    ROLE_HIERARCHY,
    updateConnectionStatus,
    isServerOnline,
    checkServerConnection,
    setServerOnline,
    startConnectionMonitoring,
    syncOfflineEntries,
    showLoadingSpinner,
    showRetryButton,
    openConflictResolutionModal,
    t,
    setLanguage,
    localize,
    formatTeam,
    safeGetItem,
    safeSetItem,
    safeRemoveItem,
    downloadJson,
    showQrModal,
    compressData,
    decompressData,
    CACHE_CONFIGS,
    startTour,
    endTour,
    showTourLevelSelector,
    showSetupWizardModal,
    setButtonLoading,
    withButtonLoading,
    processImageUpload,
    openInlineCameraModal,
    showImageModal
};

// Re-export for ES module consumers
export {
    getProgram,
    getProgramPrefix,
    request,
    getMe,
    checkLoginStatus,
    requireAuth,
    showToast,
    triggerHaptic,
    setUserBadge,
    refreshNavAvatar,
    setActiveNav,
    adjustNavForRole,
    wireLogout,
    initTheme,
    wireThemeToggle,
    applyNavLayout,
    formatTimestamp,
    getDeviceTimezone,
    formatTimestampWithVenueTooltip,
    localToUtcEpoch,
    resolveEventKey,
    hasRole,
    isAdmin,
    isSuperAdmin,
    canAccessAnalytics,
    ROLE_HIERARCHY,
    updateConnectionStatus,
    isServerOnline,
    checkServerConnection,
    setServerOnline,
    startConnectionMonitoring,
    syncOfflineEntries,
    showLoadingSpinner,
    showRetryButton,
    openConflictResolutionModal,
    t,
    setLanguage,
    localize,
    formatTeam,
    safeGetItem,
    safeSetItem,
    safeRemoveItem,
    downloadJson,
    showQrModal,
    compressData,
    decompressData,
    CACHE_CONFIGS,
    startTour,
    endTour,
    showTourLevelSelector,
    showSetupWizardModal,
    setButtonLoading,
    withButtonLoading,
    processImageUpload,
    openInlineCameraModal,
    showImageModal
};

// ==========================================================================
// Lifecycle & Service Worker Initialization
// ==========================================================================

function urlBase64ToUint8Array(base64String) {
    const padding = '='.repeat((4 - base64String.length % 4) % 4);
    const base64 = (base64String + padding)
        .replace(/\-/g, '+')
        .replace(/_/g, '/');
    const rawData = window.atob(base64);
    const outputArray = new Uint8Array(rawData.length);
    for (let i = 0; i < rawData.length; ++i) {
        outputArray[i] = rawData.charCodeAt(i);
    }
    return outputArray;
}

async function initPushNotifications(registration) {
    if (!('PushManager' in window)) {
        console.warn('[Push] Push messaging is not supported in this browser');
        return;
    }
    try {
        const permission = await Notification.requestPermission();
        if (permission !== 'granted') {
            console.warn('[Push] Permission for notifications was denied');
            return;
        }

        const response = await request("/api/push/public-key");
        if (!response || !response.publicKey) {
            console.warn('[Push] Failed to load VAPID public key from server');
            return;
        }
        const serverKey = urlBase64ToUint8Array(response.publicKey);

        let subscription = await registration.pushManager.getSubscription();
        if (subscription) {
            let keyMismatch = false;
            if (subscription.options && subscription.options.applicationServerKey) {
                const subKey = new Uint8Array(subscription.options.applicationServerKey);
                if (serverKey.length !== subKey.length || !serverKey.every((v, i) => v === subKey[i])) {
                    keyMismatch = true;
                }
            } else {
                keyMismatch = true;
            }

            if (keyMismatch) {
                console.log('[Push] VAPID public key changed or mismatch detected, unsubscribing...');
                await subscription.unsubscribe();
                subscription = null;
            }
        }

        if (!subscription) {
            subscription = await registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: serverKey
            });
        }

        await request("/api/push/subscribe", {
            method: "POST",
            json: subscription
        });
        console.log('[Push] Subscribed successfully and registered on backend');
    } catch (e) {
        console.warn('[Push] Failed to register push subscription:', e);
    }
}

function initGlobalSidebarAndUser(sidebarEl) {
    if (!sidebarEl) return;

    initTheme();
    wireThemeToggle();
    wireLogout();
    setActiveNav();

    try {
        const meText = safeGetItem("cache:/api/auth/me");
        if (meText) {
            const parsed = JSON.parse(meText);
            const user = parsed.user || parsed;
            if (user && user.username) {
                setUserBadge(user);
                adjustNavForRole(user);
            }
        }
    } catch (e) {
        console.warn("[Sidebar] Failed to load cached user info:", e);
    }

    getMe().then((user) => {
        if (user && user.username) {
            setUserBadge(user);
            adjustNavForRole(user);
        }
    }).catch((err) => {
        console.warn("[Sidebar] getMe background update failed:", err);
    });
}

async function onDOMContentLoaded() {
    // Clean up legacy i18n caches in localStorage
    try {
        const keysToRemove = [];
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (key && key.startsWith("i18n:")) {
                keysToRemove.push(key);
            }
        }
        keysToRemove.forEach(k => localStorage.removeItem(k));
    } catch (e) {
        // ignore
    }

    if ('serviceWorker' in navigator) {
        let refreshing = false;
        navigator.serviceWorker.addEventListener('controllerchange', () => {
            console.log('[ServiceWorker] Active controller changed to new version');
        });

        navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' })
            .then(reg => {
                console.log('[ServiceWorker] Registered with scope:', reg.scope);
                // Force an update check on page load
                reg.update().catch(err => console.warn('[ServiceWorker] Update check failed:', err));

                reg.addEventListener('updatefound', () => {
                    const newWorker = reg.installing;
                    if (newWorker) {
                        newWorker.addEventListener('statechange', () => {
                            if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
                                console.log('[ServiceWorker] New version installed and ready.');
                            }
                        });
                    }
                });

                // Periodically check for updates every hour
                setInterval(() => {
                    reg.update().catch(err => console.warn('[ServiceWorker] Periodic update check failed:', err));
                }, 3600000);

                navigator.serviceWorker.ready.then(readyReg => {
                    initPushNotifications(readyReg);
                });
            })
            .catch(err => console.error('[ServiceWorker] Registration failed:', err));
    }

    initTheme();
    applyNavLayout();
    wireThemeToggle();

    const sidebar = document.querySelector(".sidebar");
    if (sidebar) {
        await ensureSidebarAndFooter(sidebar);
        injectConnectionWidget(sidebar);
        injectLanguageSelector(sidebar);
        renderServerVersion(sidebar);
        initGlobalSidebarAndUser(sidebar);
    }

    wireSidebarToggle();
    injectMobileTopBar();
    startConnectionMonitoring();

    window.addEventListener("online", async () => {
        if ('serviceWorker' in navigator) {
            navigator.serviceWorker.ready.then(reg => {
                reg.update().catch(err => console.warn('[ServiceWorker] Online update check failed:', err));
            });
        }
        const online = await checkServerConnection({ force: true });
        if (online) {
            const isCacheManager = document.body && document.body.dataset.page === "cache-manager";
            if (!isCacheManager) {
                syncOfflineEntries();
            }
            syncOfflineCache();
            const isDataPage = ['dashboard', 'qual-data', 'pit-data', 'all-data', 'analytics', 'graphs', 'events', 'teams', 'matches', 'predictor', 'alliances', 'users', 'config', 'settings'].includes(document.body.dataset.page);
            const isUserEditing = document.querySelector('input:focus, textarea:focus') !== null;
            if (isDataPage && !isUserEditing) {
                saveScrollPositions();
                window.location.reload();
            }
        }
    });
    window.addEventListener("offline", () => {
        setServerOnline(false);
    });

    checkServerConnection({ force: true }).then((online) => {
        if (online) {
            const isCacheManager = document.body && document.body.dataset.page === "cache-manager";
            if (!isCacheManager) {
                syncOfflineEntries();
            }
            syncOfflineCache();
        }
    });

    // Load selected language bundle and apply translations
    loadLocale(safeGetItem('obsidianscout:lang') || 'en').then(() => applyTranslations());
    restoreScrollPositions();
    initChatUnreadPolling();

    // Global form submit listener to automatically show button loading indicators
    document.addEventListener("submit", (e) => {
        const form = e.target;
        if (!form || form.getAttribute("data-no-loading") === "true") return;
        const submitter = e.submitter || form.querySelector('button[type="submit"], input[type="submit"]');
        if (submitter && submitter.getAttribute("data-no-loading") !== "true") {
            setButtonLoading(submitter, true);
            if (form.checkValidity && !form.checkValidity()) {
                setButtonLoading(submitter, false);
            }
        }
    });

    // If native form validation fails (invalid event fires), instantly restore submit button
    document.addEventListener("invalid", (e) => {
        const form = e.target.form;
        if (form) {
            const submitter = form.querySelector('button[type="submit"], input[type="submit"]');
            if (submitter) setButtonLoading(submitter, false);
        }
    }, true);

    // When a user interacts with or edits a form after an error, reset any stuck loading state on that form's submit buttons
    document.addEventListener("input", (e) => {
        const form = e.target.form;
        if (form) {
            form.querySelectorAll('button[data-loading="true"], button.is-loading, input[type="submit"][data-loading="true"]').forEach((btn) => {
                setButtonLoading(btn, false);
            });
        }
    });
    document.addEventListener("change", (e) => {
        const form = e.target.form;
        if (form) {
            form.querySelectorAll('button[data-loading="true"], button.is-loading, input[type="submit"][data-loading="true"]').forEach((btn) => {
                setButtonLoading(btn, false);
            });
        }
    });

    // Run background cache synchronization every 5 minutes
    setInterval(syncOfflineCache, 300000);
}

if (document.readyState === 'loading') {
    document.addEventListener("DOMContentLoaded", () => {
        injectLiquidGlassSVG();
        loadAndRenderBanners();
        initHapticDelegation();
        onDOMContentLoaded();
    });
} else {
    injectLiquidGlassSVG();
    loadAndRenderBanners();
    initHapticDelegation();
    onDOMContentLoaded();
}

window.addEventListener("beforeunload", (event) => {
    let count = 0;
    for (const type in CACHE_CONFIGS) {
        const config = CACHE_CONFIGS[type];
        const pending = JSON.parse(safeGetItem(config.key) || "[]");
        count += pending.length;
    }
    if (count > 0) {
        const message = (typeof t === 'function') ? t('unsynced_entries','You have unsynced offline scouting entries! If you leave, they might not be synced to the server.') : "You have unsynced offline scouting entries! If you leave, they might not be synced to the server.";
        event.returnValue = message;
        return message;
    }
});
