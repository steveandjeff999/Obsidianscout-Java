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
    restoreScrollPositions,
    getHttpCache,
    getHttpCacheSync,
    setHttpCache,
    removeHttpCache,
    clearAllHttpCaches,
    pruneHttpCaches,
    purgeScoutingHttpCache,
    migrateLocalStorageToIdb
} from './base/storage.js';

import {
    request,
    getCachedData,
    getCsrfToken,
    setButtonLoading,
    withButtonLoading,
    safeParse,
    purgeScoutingCache,
    isScoutingDataPath,
    canRoleCacheScouting
} from './base/http.js';
// Namespace imports for the newest exports: right after a deploy the previous service worker can still hand out the
// previous copy of these unversioned modules, and a missing named export would stop every page from loading.
import * as httpModule from './base/http.js';
import { isPublicMode, renderPublicChrome, wirePublicLinks } from './base/public-mode.js';
import * as offlineSyncModule from './services/offline-sync.js';

const isNetworkError = httpModule.isNetworkError || ((error) => {
    if (!error || error.status) return false;
    return !navigator.onLine || /failed to fetch|networkerror|timed out/i.test(String(error.message || ""));
});
const startAutoUpload = offlineSyncModule.startAutoUpload || (() => {});

import {
    ROLE_HIERARCHY,
    checkLoginStatus,
    getMe,
    requireAuth,
    hasRole,
    isAdmin,
    canUseAdminPage,
    isSuperAdmin,
    canAccessAnalytics,
    wireLogout,
    resetCachesForUser
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
    wireSidebarSearch,
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

import {
    initScrollReveal,
    refreshScrollReveal
} from './layout/scroll-reveal.js';

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
// Photo helpers by namespace (see the note on httpModule above).
import * as modalsModule from './components/modals.js';
const isImageValue = modalsModule.isImageValue || (() => false);
const imageThumbHtml = modalsModule.imageThumbHtml || (() => "");

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
    getAccessibleTutorials,
    getTutorialMode,
    setTutorialMode,
    resetAllTutorialProgress,
    speakZachary,
    stopZacharySpeech,
    isZacharyMuted,
    setZacharyMuted,
    showZacharyErrorExplainer
} from './components/tour-wizard.js';

import {
    initErrorReporter
} from './components/error-reporter.js';

// 4. Services Layer
import {
    CACHE_CONFIGS,
    syncOfflineEntries,
    syncOfflineCache,
    getPendingEntries,
    getPendingSummary,
    getFailedEntries,
    retryFailedEntry,
    discardFailedEntry,
    notifyPendingChanged,
    readPendingQueue,
    enqueueOfflineEntries,
    enqueueOfflineEntry,
    removePendingItem,
    clearPendingQueue
} from './services/offline-sync.js';
import { getLowBandwidthMode, setLowBandwidthMode, isLowBandwidth, getLinkStats } from './base/net-quality.js';
import { syncAllKinds, getSyncInfo } from './services/entry-store.js';

import {
    initSyncStatusBanner,
    renderSyncStatusBanner,
    isPendingLocally,
    updateLocalOnlyChip,
    showSaveResult
} from './components/sync-status-banner.js';

import {
    initAssignmentAdvance,
    getNextAssignment,
    markAssignmentLocallyDone,
    isAutoAdvanceEnabled,
    setAutoAdvanceEnabled,
    renderNextAssignmentChip,
    openSelectPicker
} from './services/assignment-advance.js';

import { faIcon } from './utilities/fa-icons.js';

// New files in this release; always fetched fresh, so plain named imports are safe.
import { saveScoutingEntries, saveScoutingEntry, shouldKeepOnDevice } from './services/scouting-save.js';
import { collectValidatedPayload, clearValidation } from './services/form-validation.js';
import { showSaveReceipt, hideSaveReceipt, signInUrl } from './components/save-receipt.js';
import { queueReady, storageMode } from './services/queue-store.js';
import { initStaleDataNotice } from './components/stale-data-notice.js';

import {
    saveDraft,
    loadDraft,
    clearDraft,
    offerDraftRestore,
    startDraftAutosave
} from './services/form-draft.js';
// Newest form-draft exports, by namespace (see the note on httpModule above).
import * as formDraftModule from './services/form-draft.js';

import {
    downloadJson,
    compressData,
    decompressData,
    compressAndChunkData
} from './services/data-compression.js';

import {
    initChatUnreadPolling
} from './services/chat-poller.js';

import {
    initThemeSync
} from './services/theme-sync.js';

import {
    recordDeviceHistory,
    getDeviceHistory,
    getDeviceHistoryForAccount,
    purgeExpiredHistory,
    getActiveAccountInfo,
    markEntryAsSynced,
    markEntriesAsSynced,
    markMatchingEntryAsSynced,
    deleteDeviceHistoryEntry,
    clearDeviceHistory,
    exportDeviceHistory,
    importDeviceHistory,
    HISTORY_STORAGE_KEY,
    RETENTION_DAYS
} from './services/device-history.js';

// 5. Utilities Layer
import {
    formatTimestamp,
    getDeviceTimezone,
    formatTimestampWithVenueTooltip,
    localToUtcEpoch,
    resolveEventKey,
    getEventTimezone,
    formatTeam,
    formatScouter,
    showLoadingSpinner,
    showRetryButton,
    getProgram,
    getProgramPrefix,
    escapeHtml,
    createMatchVideoLinks
} from './utilities/helpers.js';

import {
    triggerHaptic,
    initHapticDelegation
} from './utilities/haptics.js';

import {
    keepScreenAwake,
    releaseScreenAwake
} from './utilities/wake-lock.js';

import {
    showSubmitReview,
    buildReviewSections,
    reviewRowForField,
    isReviewBeforeSubmitEnabled,
    confirmOpenSubmitReview,
    phaseTitle as reviewPhaseTitle,
    reviewActionLabel
} from './components/submit-review.js';

import {
    processImageUpload,
    isWebpSupported
} from './utilities/media.js';

import {
    injectLiquidGlassSVG
} from './utilities/svg-filters.js';

console.log("[CommonJS] Script initialized and loaded (Modular Architecture).");
initErrorReporter();
// Before any page script asks for data, so a first answer from the offline cache is noticed too.
initStaleDataNotice();

export function showModal(modalIdOrEl) {
    const el = typeof modalIdOrEl === 'string' ? document.getElementById(modalIdOrEl) : modalIdOrEl;
    if (el) {
        el.classList.add('show');
        el.classList.remove('hidden');
    }
}

export function hideModal(modalIdOrEl) {
    const el = typeof modalIdOrEl === 'string' ? document.getElementById(modalIdOrEl) : modalIdOrEl;
    if (el) {
        el.classList.remove('show');
    }
}

// Export public API to window.Obsidianscout
window.Obsidianscout = {
    showModal,
    hideModal,
    initErrorReporter,
    escapeHtml,
    createMatchVideoLinks,
    getProgram,
    getProgramPrefix,
    request,
    isNetworkError,
    getCachedData,
    getMe,
    checkLoginStatus,
    requireAuth,
    showToast,
    triggerHaptic,
    keepScreenAwake,
    releaseScreenAwake,
    showSubmitReview,
    buildReviewSections,
    reviewRowForField,
    isReviewBeforeSubmitEnabled,
    confirmOpenSubmitReview,
    reviewPhaseTitle,
    reviewActionLabel,
    setUserBadge,
    refreshNavAvatar,
    setActiveNav,
    adjustNavForRole,
    wireSidebarSearch,
    wireLogout,
    resetCachesForUser,
    initTheme,
    wireThemeToggle,
    applyNavLayout,
    formatTimestamp,
    getDeviceTimezone,
    formatTimestampWithVenueTooltip,
    localToUtcEpoch,
    resolveEventKey,
    getEventTimezone,
    hasRole,
    isAdmin,
    canUseAdminPage,
    isSuperAdmin,
    canAccessAnalytics,
    ROLE_HIERARCHY,
    updateConnectionStatus,
    isServerOnline,
    checkServerConnection,
    setServerOnline,
    startConnectionMonitoring,
    syncOfflineEntries,
    getPendingEntries,
    getPendingSummary,
    getFailedEntries,
    retryFailedEntry,
    discardFailedEntry,
    notifyPendingChanged,
    readPendingQueue,
    enqueueOfflineEntries,
    enqueueOfflineEntry,
    removePendingItem,
    clearPendingQueue,
    getLowBandwidthMode,
    setLowBandwidthMode,
    isLowBandwidth,
    getLinkStats,
    syncAllKinds,
    getSyncInfo,
    initSyncStatusBanner,
    renderSyncStatusBanner,
    isPendingLocally,
    updateLocalOnlyChip,
    showSaveResult,
    initAssignmentAdvance,
    getNextAssignment,
    markAssignmentLocallyDone,
    isAutoAdvanceEnabled,
    setAutoAdvanceEnabled,
    renderNextAssignmentChip,
    openSelectPicker,
    faIcon,
    showLoadingSpinner,
    showRetryButton,
    openConflictResolutionModal,
    t,
    setLanguage,
    localize,
    formatTeam,
    formatScouter,
    safeGetItem,
    safeSetItem,
    safeRemoveItem,
    clearAllCaches,
    getHttpCache,
    getHttpCacheSync,
    setHttpCache,
    removeHttpCache,
    clearAllHttpCaches,
    downloadJson,
    showQrModal,
    compressData,
    decompressData,
    CACHE_CONFIGS,
    saveDraft,
    loadDraft,
    clearDraft,
    offerDraftRestore,
    startDraftAutosave,
    initTour,
    runActiveTourStep,
    displayTourStepPopup,
    startTour,
    endTour,
    showTourLevelSelector,
    getAccessibleTutorials,
    getTutorialMode,
    setTutorialMode,
    resetAllTutorialProgress,
    speakZachary,
    stopZacharySpeech,
    isZacharyMuted,
    setZacharyMuted,
    showZacharyErrorExplainer,
    showSetupWizardModal,
    setButtonLoading,
    withButtonLoading,
    processImageUpload,
    isWebpSupported,
    openInlineCameraModal,
    showImageModal,
    isImageValue,
    imageThumbHtml,
    purgeScoutingCache,
    isScoutingDataPath,
    canRoleCacheScouting,
    loadAndRenderBanners,
    recordDeviceHistory,
    getDeviceHistory,
    getDeviceHistoryForAccount,
    purgeExpiredHistory,
    getActiveAccountInfo,
    markEntryAsSynced,
    markEntriesAsSynced,
    markMatchingEntryAsSynced,
    deleteDeviceHistoryEntry,
    clearDeviceHistory,
    exportDeviceHistory,
    importDeviceHistory,
    HISTORY_STORAGE_KEY,
    RETENTION_DAYS,
    saveScoutingEntries,
    saveScoutingEntry,
    shouldKeepOnDevice,
    collectValidatedPayload,
    clearValidation,
    showSaveReceipt,
    hideSaveReceipt,
    signInUrl,
    queueReady,
    storageMode,
    isSignInNeededForUpload: offlineSyncModule.isSignInNeededForUpload || (() => false),
    collectDraftFields: formDraftModule.collectDraftFields || (() => ({})),
    applyDraftFields: formDraftModule.applyDraftFields || (() => {}),
    resetDraftBaseline: formDraftModule.resetDraftBaseline || (() => {}),
    newClientId: offlineSyncModule.newClientId
};

// Re-export for ES module consumers
export {
    escapeHtml,
    createMatchVideoLinks,
    getProgram,
    getProgramPrefix,
    request,
    isNetworkError,
    getCachedData,
    getMe,
    checkLoginStatus,
    requireAuth,
    showToast,
    triggerHaptic,
    keepScreenAwake,
    releaseScreenAwake,
    showSubmitReview,
    buildReviewSections,
    reviewRowForField,
    isReviewBeforeSubmitEnabled,
    confirmOpenSubmitReview,
    reviewPhaseTitle,
    reviewActionLabel,
    setUserBadge,
    refreshNavAvatar,
    setActiveNav,
    adjustNavForRole,
    wireLogout,
    resetCachesForUser,
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
    canUseAdminPage,
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
    formatScouter,
    safeGetItem,
    safeSetItem,
    safeRemoveItem,
    clearAllCaches,
    getHttpCache,
    getHttpCacheSync,
    setHttpCache,
    removeHttpCache,
    clearAllHttpCaches,
    downloadJson,
    showQrModal,
    compressData,
    decompressData,
    CACHE_CONFIGS,
    initTour,
    runActiveTourStep,
    displayTourStepPopup,
    startTour,
    endTour,
    showTourLevelSelector,
    getAccessibleTutorials,
    getTutorialMode,
    setTutorialMode,
    resetAllTutorialProgress,
    speakZachary,
    stopZacharySpeech,
    isZacharyMuted,
    setZacharyMuted,
    showZacharyErrorExplainer,
    showSetupWizardModal,
    setButtonLoading,
    withButtonLoading,
    processImageUpload,
    isWebpSupported,
    openInlineCameraModal,
    showImageModal,
    isImageValue,
    imageThumbHtml,
    purgeScoutingCache,
    isScoutingDataPath,
    canRoleCacheScouting,
    loadAndRenderBanners,
    recordDeviceHistory,
    getDeviceHistory,
    getDeviceHistoryForAccount,
    purgeExpiredHistory,
    getActiveAccountInfo,
    markEntryAsSynced,
    markEntriesAsSynced,
    markMatchingEntryAsSynced,
    deleteDeviceHistoryEntry,
    clearDeviceHistory,
    exportDeviceHistory,
    importDeviceHistory,
    HISTORY_STORAGE_KEY,
    RETENTION_DAYS
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

// Asset version the server stamped on this page's script tags (e.g. /js/common.js?v=0.6.5.9)
function getPageAssetVersion() {
    const script = document.querySelector('script[src*="/js/common.js"]');
    if (!script) return null;
    try {
        return new URL(script.src, location.href).searchParams.get('v');
    } catch (e) {
        return null;
    }
}

// Resolves true only when the given worker's CACHE_NAME differs from this page's asset version
function isPageOlderThanWorker(worker) {
    const pageVersion = getPageAssetVersion();
    if (!pageVersion) return Promise.resolve(false);
    return new Promise((resolve) => {
        const channel = new MessageChannel();
        const timeout = setTimeout(() => resolve(false), 3000);
        channel.port1.onmessage = (event) => {
            clearTimeout(timeout);
            const cacheName = event.data && event.data.cacheName;
            resolve(!!cacheName && cacheName !== `obsidianscout-shell-v${pageVersion.replace(/\./g, '-')}`);
        };
        try {
            worker.postMessage({ type: 'GET_VERSION' }, [channel.port2]);
        } catch (e) {
            clearTimeout(timeout);
            resolve(false);
        }
    });
}

async function onDOMContentLoaded() {
    // Migrate legacy HTTP caches from localStorage to IndexedDB to free 5MB quota
    try {
        migrateLocalStorageToIdb();
    } catch (e) {
        console.warn("[Storage] IDB migration check failed:", e);
    }

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

    // Visitors to a public data view aren't part of the team: no offline shell, no push prompt.
    if ('serviceWorker' in navigator && !isPublicMode()) {
        navigator.serviceWorker.addEventListener('controllerchange', () => {
            console.log('[ServiceWorker] Active controller updated to new version. Updates will apply on next navigation or refresh.');
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
                                // Pages are fetched network-first, so the page on screen is usually already the
                                // version the new worker ships. Only prompt when this page is actually older.
                                isPageOlderThanWorker(newWorker).then((older) => {
                                    if (older && typeof showToast === 'function') {
                                        showToast(t("app.update_available", "New version installed. Refresh to apply latest changes."), "info");
                                    }
                                });
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
    initScrollReveal();

    // Public data view: own sidebar, no sign-in, nothing to sync, poll or upload.
    if (isPublicMode()) {
        await renderPublicChrome(document.querySelector(".sidebar"));
        wirePublicLinks();
        wireSidebarToggle();
        injectMobileTopBar();
        loadLocale(safeGetItem('obsidianscout:lang') || 'en').then(() => applyTranslations());
        return;
    }

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
    initSyncStatusBanner();
    startAutoUpload();

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
            // Caches refresh in the background; fresh data shows on the next page load (no forced reload).
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
            // Defer background sync so user's active page navigation gets full uncontended bandwidth
            setTimeout(() => {
                syncOfflineCache();
            }, 10000);
        }
    });

    // Load selected language bundle and apply translations
    loadLocale(safeGetItem('obsidianscout:lang') || 'en').then(() => applyTranslations());
    restoreScrollPositions();
    initChatUnreadPolling();
    initThemeSync();

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

    // If native form validation fails (invalid event fires), instantly restore submit button and trigger Zachary diagnostic if enabled (or toast on login)
    let lastInvalidToastTime = 0;
    document.addEventListener("invalid", (e) => {
        const form = e.target.form;
        if (form) {
            const submitter = form.querySelector('button[type="submit"], input[type="submit"]');
            if (submitter) setButtonLoading(submitter, false);

            const isLoginPage = (document.body && (document.body.dataset.page === "login" || document.body.classList.contains("login-page"))) ||
                (typeof window !== "undefined" && window.location && (window.location.pathname === "/login" || window.location.pathname === "/login.html" || window.location.pathname.endsWith("/login.html") || window.location.pathname.endsWith("/login")));

            if (isLoginPage) {
                const now = Date.now();
                if (now - lastInvalidToastTime > 1500) {
                    lastInvalidToastTime = now;
                    const errorMsg = e.target.validationMessage ||
                        (window.Obsidianscout && typeof window.Obsidianscout.t === 'function'
                            ? window.Obsidianscout.t('login.fill_required_fields', 'Please fill in all required fields.')
                            : 'Please fill in all required fields.');
                    showToast(errorMsg, "error");
                }
            } else {
                try {
                    const currentMode = typeof getTutorialMode === 'function'
                        ? getTutorialMode()
                        : (safeGetItem('obsidianscout:tutorial_mode') || 'standard');
                    if (currentMode === 'zachary') {
                        showZacharyErrorExplainer("Form constraint violation: A required field is empty or improperly formatted.");
                    }
                } catch (_) {}
            }
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



