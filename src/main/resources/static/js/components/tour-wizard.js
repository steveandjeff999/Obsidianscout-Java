/**
 * Component Tour Wizard Module - ObsidianScout
 * Interactive multi-level role-based guided walkthrough, tutorial overlays, and server progress syncing.
 */

import { safeGetItem, safeSetItem, safeRemoveItem } from '../base/storage.js';
import { request } from '../base/http.js';
import { showToast } from './toast.js';
import { t } from '../base/i18n.js';
import { isAdmin, isSuperAdmin } from '../base/auth.js';
import { isPageAccessible } from '../layout/navigation.js';

export const TOUR_STEPS = {
    scout_beginner: [
        { page: "dashboard", target: ".main-content h1", titleKey: "tour.step.dashboard.title", descKey: "tour.step.dashboard.desc" },
        { page: "scout", target: "#scouting-form", titleKey: "tour.step.scout.title", descKey: "tour.step.scout.desc" },
        { page: "pit-scout", target: "#pit-scouting-form", titleKey: "tour.step.pit_scout.title", descKey: "tour.step.pit_scout.desc" },
        { page: "qr-scanner", target: ".main-content", titleKey: "tour.step.qr_scanner.title", descKey: "tour.step.qr_scanner.desc" }
    ],
    scout_intermediate: [
        { page: "all-data", target: ".main-content", titleKey: "tour.step.all_data.title", descKey: "tour.step.all_data.desc" },
        { page: "qual-data", target: ".main-content", titleKey: "tour.step.qual_data.title", descKey: "tour.step.qual_data.desc" },
        { page: "pit-data", target: ".main-content", titleKey: "tour.step.pit_data.title", descKey: "tour.step.pit_data.desc" }
    ],
    scout_advanced: [
        { page: "analytics", target: ".main-content", titleKey: "tour.step.analytics.title", descKey: "tour.step.analytics.desc" },
        { page: "graphs", target: ".main-content", titleKey: "tour.step.graphs.title", descKey: "tour.step.graphs.desc" },
        { page: "predictor", target: ".main-content", titleKey: "tour.step.predictor.title", descKey: "tour.step.predictor.desc" },
        { page: "alliance-selection", target: ".main-content", titleKey: "tour.step.alliance_selection.title", descKey: "tour.step.alliance_selection.desc" }
    ],
    admin_beginner: [
        { page: "dashboard", target: "#nav-user", titleKey: "tour.step.admin_badge.title", descKey: "tour.step.admin_badge.desc" },
        { page: "users", target: ".main-content", titleKey: "tour.step.admin_users.title", descKey: "tour.step.admin_users.desc" },
        { page: "banners", target: ".main-content", titleKey: "tour.step.admin_banners.title", descKey: "tour.step.admin_banners.desc" }
    ],
    admin_intermediate: [
        { page: "admin-settings", target: "#admin-panel", titleKey: "tour.step.admin_panel.title", descKey: "tour.step.admin_panel.desc" },
        { page: "admin-settings", target: "#tab-config", titleKey: "tour.step.admin_tab_config.title", descKey: "tour.step.admin_tab_config.desc", clickToAdvance: true },
        { page: "admin-settings", target: "button[data-config-kind='pit']", titleKey: "tour.step.admin_config_pit.title", descKey: "tour.step.admin_config_pit.desc", clickToAdvance: true },
        { page: "admin-settings", target: "#btn-raw-editor", titleKey: "tour.step.admin_config_raw.title", descKey: "tour.step.admin_config_raw.desc", clickToAdvance: true },
        { page: "admin-settings", target: "#tab-api", titleKey: "tour.step.admin_tab_api.title", descKey: "tour.step.admin_tab_api.desc", clickToAdvance: true },
        { page: "admin-settings", target: "#tab-api", titleKey: "tour.step.admin_api_desc.title", descKey: "tour.step.admin_api_desc.desc" },
        { page: "admin-settings", target: "#tab-permissions", titleKey: "tour.step.admin_tab_permissions.title", descKey: "tour.step.admin_tab_permissions.desc", clickToAdvance: true },
        { page: "admin-settings", target: "#tab-permissions", titleKey: "tour.step.admin_permissions_desc.title", descKey: "tour.step.admin_permissions_desc.desc" }
    ],
    admin_advanced: [
        { page: "backup", target: ".main-content", titleKey: "tour.step.admin_backup.title", descKey: "tour.step.admin_backup.desc" },
        { page: "migration", target: ".main-content", titleKey: "tour.step.admin_migration.title", descKey: "tour.step.admin_migration.desc" }
    ]
};

export function getTourStepsForRoleAndLevel(me, level) {
    const rawSteps = TOUR_STEPS[level] || [];
    return rawSteps.filter(step => isPageAccessible(step.page, me.role));
}

export async function syncTourProgressToServer() {
    if (!navigator.onLine) return;
    const active = safeGetItem('obsidianscout:tour_active') === 'true' ? safeGetItem('obsidianscout:tour_level') : null;
    const stepIndex = parseInt(safeGetItem('obsidianscout:tour_step_index') || '0', 10);
    const completed = JSON.parse(safeGetItem('obsidianscout:tour_completed_list') || '[]');
    
    try {
        await request('/api/user/tour-progress', {
            method: 'POST',
            json: { active, stepIndex, completed }
        });
    } catch (e) {
        console.warn('[Tour] Failed to sync progress to server:', e);
    }
}

export async function initTour(me) {
    if (!me) return;

    const sidebar = document.querySelector('.sidebar');
    if (sidebar) {
        const acctMenu = sidebar.querySelector('.topbar-account-menu');
        const footer = sidebar.querySelector('.sidebar-footer');
        const targetContainer = acctMenu || footer;
        if (targetContainer && !document.getElementById('btn-take-tour')) {
            const btn = document.createElement('button');
            btn.id = 'btn-take-tour';
            btn.className = 'btn ghost';
            btn.type = 'button';
            btn.style.marginTop = '4px';
            btn.style.width = '100%';
            btn.textContent = t('tour.take_tour', 'Take a Tour');
            btn.addEventListener('click', () => {
                showTourLevelSelector(me);
            });
            if (acctMenu) {
                const themeBtn = acctMenu.querySelector('[data-action="toggle-theme"]');
                if (themeBtn) {
                    acctMenu.insertBefore(btn, themeBtn);
                } else {
                    acctMenu.appendChild(btn);
                }
            } else {
                const apiAttr = footer.querySelector('#api-attribution');
                if (apiAttr) {
                    footer.insertBefore(btn, apiAttr);
                } else {
                    footer.appendChild(btn);
                }
            }
        }
    }

    const hasFetched = sessionStorage.getItem('obsidianscout:tour_fetched') === 'true';
    if (navigator.onLine && !hasFetched) {
        try {
            const res = await request('/api/user/tour-progress');
            if (res) {
                sessionStorage.setItem('obsidianscout:tour_fetched', 'true');
                if (res.active) {
                    safeSetItem('obsidianscout:tour_active', 'true');
                    safeSetItem('obsidianscout:tour_level', res.active);
                    safeSetItem('obsidianscout:tour_step_index', String(res.stepIndex || 0));
                } else {
                    if (safeGetItem('obsidianscout:tour_active') === 'true') {
                        safeRemoveItem('obsidianscout:tour_active');
                        safeRemoveItem('obsidianscout:tour_level');
                        safeRemoveItem('obsidianscout:tour_step_index');
                    }
                }
                if (res.completed && Array.isArray(res.completed)) {
                    safeSetItem('obsidianscout:tour_completed_list', JSON.stringify(res.completed));
                }
            }
        } catch (e) {
            console.warn('[Tour] Failed to load progress from server:', e);
        }
    }

    const isActive = safeGetItem('obsidianscout:tour_active') === 'true';
    if (isActive) {
        runActiveTourStep(me);
    }
}

export function runActiveTourStep(me) {
    const level = safeGetItem('obsidianscout:tour_level');
    const stepIndexStr = safeGetItem('obsidianscout:tour_step_index');
    let stepIndex = parseInt(stepIndexStr, 10);
    if (isNaN(stepIndex)) stepIndex = 0;

    const steps = getTourStepsForRoleAndLevel(me, level);
    if (!steps || steps.length === 0 || stepIndex >= steps.length) {
        endTour();
        return;
    }

    const step = steps[stepIndex];
    const currentPage = document.body.dataset.page;

    if (step.page !== currentPage) {
        const link = document.querySelector(`.sidebar-link[data-page="${step.page}"]`);
        if (link && link.href) {
            window.location.href = link.href;
        } else {
            const fallbackUrls = {
                "settings": "/config",
                "backup": "/backup",
                "migration": "/migration"
            };
            const url = fallbackUrls[step.page] || ("/" + step.page);
            window.location.href = url;
        }
        return;
    }

    let retries = 0;
    const maxRetries = 16;
    const checkInterval = setInterval(() => {
        const el = document.querySelector(step.target);
        if (el && el.offsetHeight > 0) {
            clearInterval(checkInterval);
            displayTourStepPopup(el, step, stepIndex, steps.length, me);
        } else {
            retries++;
            if (retries >= maxRetries) {
                clearInterval(checkInterval);
                displayTourStepPopup(null, step, stepIndex, steps.length, me);
            }
        }
    }, 250);
}

let activePopup = null;
let activeBackdrop = null;

export function displayTourStepPopup(targetEl, step, stepIndex, totalSteps, me) {
    clearTourDOM();

    const backdrop = document.createElement('div');
    backdrop.className = 'tour-backdrop';
    document.body.appendChild(backdrop);
    activeBackdrop = backdrop;

    if (targetEl) {
        targetEl.classList.add('tour-highlighted');
        targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    const popup = document.createElement('div');
    popup.className = 'tour-popup';
    
    const currentNum = stepIndex + 1;
    const progressText = t('tour.step_progress', `Step ${currentNum} of ${totalSteps}`)
                            .replace('{current}', currentNum)
                            .replace('{total}', totalSteps);

    const titleText = t(step.titleKey, step.titleKey);
    const descText = t(step.descKey, step.descKey);

    const isLast = stepIndex === totalSteps - 1;

    popup.innerHTML = `
        <button class="tour-popup-close" aria-label="Close">&times;</button>
        <h3>${titleText}</h3>
        <p>${descText}</p>
        <div class="tour-popup-footer">
            <span class="tour-popup-progress">${progressText}</span>
            <div class="tour-popup-nav">
                ${stepIndex > 0 ? `<button type="button" class="btn-tour-back">${t('tour.btn_back', 'Back')}</button>` : ''}
                <button type="button" class="btn-tour-next">${isLast ? t('tour.btn_finish', 'Finish') : t('tour.btn_next', 'Next')}</button>
            </div>
        </div>
    `;

    document.body.appendChild(popup);
    activePopup = popup;

    popup.querySelector('.tour-popup-close').addEventListener('click', () => {
        endTour();
    });

    const nextBtn = popup.querySelector('.btn-tour-next');
    const isClickToAdvance = step.clickToAdvance === true && targetEl;

    if (isClickToAdvance) {
        if (nextBtn) {
            nextBtn.style.display = 'none';
        }
        const progress = popup.querySelector('.tour-popup-progress');
        if (progress) {
            progress.innerHTML += ` <span style="display:block;margin-top:4px;color:var(--accent-2);font-weight:700;font-size:10px;">${t('tour.click_to_advance', '(Click highlighted element to continue)')}</span>`;
        }

        const advanceHandler = async () => {
            clearTourDOM();
            safeSetItem('obsidianscout:tour_step_index', stepIndex + 1);
            await syncTourProgressToServer();
            setTimeout(() => {
                runActiveTourStep(me);
            }, 150);
        };

        targetEl.addEventListener('click', advanceHandler, { once: true });
        targetEl._tourAdvanceHandler = advanceHandler;
    } else {
        if (nextBtn) {
            nextBtn.addEventListener('click', async () => {
                if (isLast) {
                    const currentLevel = safeGetItem('obsidianscout:tour_level');
                    await endTour(currentLevel);
                    showToast(t('tour.finished_toast', 'Tutorial completed!'), 'success');
                } else {
                    safeSetItem('obsidianscout:tour_step_index', stepIndex + 1);
                    await syncTourProgressToServer();
                    runActiveTourStep(me);
                }
            });
        }
    }

    const backBtn = popup.querySelector('.btn-tour-back');
    if (backBtn) {
        backBtn.addEventListener('click', async () => {
            safeSetItem('obsidianscout:tour_step_index', Math.max(0, stepIndex - 1));
            await syncTourProgressToServer();
            runActiveTourStep(me);
        });
    }

    if (targetEl) {
        positionPopupNextToElement(popup, targetEl);
        const reposition = () => positionPopupNextToElement(popup, targetEl);
        window.addEventListener('resize', reposition);
        window.addEventListener('scroll', reposition);
        popup.dataset.repositionListeners = 'true';
        popup._reposition = reposition;
    } else {
        popup.style.position = 'fixed';
        popup.style.top = '50%';
        popup.style.left = '50%';
        popup.style.transform = 'translate(-50%, -50%)';
    }
}

export function positionPopupNextToElement(popup, targetEl) {
    const rect = targetEl.getBoundingClientRect();
    const popupWidth = popup.offsetWidth || 320;
    const popupHeight = popup.offsetHeight || 150;
    const margin = 12;

    let top = rect.bottom + window.scrollY + margin;
    let left = rect.left + window.scrollX + (rect.width - popupWidth) / 2;

    if (rect.bottom + popupHeight + margin > window.innerHeight) {
        if (rect.top - popupHeight - margin > 0) {
            top = rect.top + window.scrollY - popupHeight - margin;
        } else {
            if (rect.right + popupWidth + margin < window.innerWidth) {
                top = rect.top + window.scrollY + (rect.height - popupHeight) / 2;
                left = rect.right + window.scrollX + margin;
            } else if (rect.left - popupWidth - margin > 0) {
                top = rect.top + window.scrollY + (rect.height - popupHeight) / 2;
                left = rect.left + window.scrollX - popupWidth - margin;
            }
        }
    }

    const viewportWidth = window.innerWidth;
    if (left < 10) left = 10;
    if (left + popupWidth > viewportWidth - 10) left = viewportWidth - popupWidth - 10;

    popup.style.top = `${top}px`;
    popup.style.left = `${left}px`;
}

export function clearTourDOM() {
    if (activePopup && activePopup.dataset.repositionListeners === 'true' && activePopup._reposition) {
        window.removeEventListener('resize', activePopup._reposition);
        window.removeEventListener('scroll', activePopup._reposition);
    }

    document.querySelectorAll('.tour-highlighted, [id^="tab-"], [data-config-kind], .sidebar-link').forEach(el => {
        if (el._tourAdvanceHandler) {
            el.removeEventListener('click', el._tourAdvanceHandler);
            delete el._tourAdvanceHandler;
        }
    });

    document.querySelectorAll('.tour-highlighted').forEach(el => {
        el.classList.remove('tour-highlighted');
    });

    if (activePopup) {
        activePopup.remove();
        activePopup = null;
    }
    if (activeBackdrop) {
        activeBackdrop.remove();
        activeBackdrop = null;
    }
}

export async function endTour(completedLevelKey = null) {
    clearTourDOM();
    safeRemoveItem('obsidianscout:tour_active');
    safeRemoveItem('obsidianscout:tour_level');
    safeRemoveItem('obsidianscout:tour_step_index');
    
    if (completedLevelKey) {
        const completed = JSON.parse(safeGetItem('obsidianscout:tour_completed_list') || '[]');
        if (!completed.includes(completedLevelKey)) {
            completed.push(completedLevelKey);
        }
        safeSetItem('obsidianscout:tour_completed_list', JSON.stringify(completed));
    }

    await syncTourProgressToServer();
}

export function showTourLevelSelector(me) {
    const existing = document.getElementById('tour-level-modal-overlay');
    if (existing) existing.remove();

    const overlay = document.createElement('div');
    overlay.id = 'tour-level-modal-overlay';
    overlay.className = 'tour-modal-overlay';

    const isUserAdmin = isAdmin(me.role) || isSuperAdmin(me.role);
    const completedList = JSON.parse(safeGetItem('obsidianscout:tour_completed_list') || '[]');
    
    const checkMark = (levelKey) => {
        if (completedList.includes(levelKey)) {
            return ` <span style="color:var(--accent-3);font-size:12px;margin-left:4px;font-weight:700;">✔</span>`;
        }
        return '';
    };

    let adminSectionHtml = '';
    if (isUserAdmin) {
        adminSectionHtml = `
            <div class="tour-modal-section-title">${t('tour.admin_track', 'Administrator-only Tracks')}</div>
            <div class="tour-level-grid">
                <button type="button" class="tour-level-btn" data-level="admin_beginner">
                    <span class="tour-level-name">${t('tour.level_beginner', 'Beginner Track')}${checkMark('admin_beginner')}</span>
                    <span class="tour-level-desc">${t('tour.step.admin_badge.title', 'User Management & Roles')}</span>
                </button>
                <button type="button" class="tour-level-btn" data-level="admin_intermediate">
                    <span class="tour-level-name">${t('tour.level_intermediate', 'Intermediate Track')}${checkMark('admin_intermediate')}</span>
                    <span class="tour-level-desc">${t('tour.step.admin_configs.title', 'Configs & Permissions')}</span>
                </button>
                <button type="button" class="tour-level-btn" data-level="admin_advanced">
                    <span class="tour-level-name">${t('tour.level_advanced', 'Advanced Track')}${checkMark('admin_advanced')}</span>
                    <span class="tour-level-desc">${t('tour.step.admin_backup.title', 'Backups & Legacy Migrations')}</span>
                </button>
            </div>
        `;
    }

    overlay.innerHTML = `
        <div class="tour-modal">
            <button class="tour-modal-close" aria-label="Close">&times;</button>
            <h2>${t('tour.welcome_title', 'Take a Tour!')}</h2>
            <p>${t('tour.welcome_desc', 'Welcome to ObsidianScout! Select your experience level to start a custom, interactive walkthrough of our features.')}</p>
            
            <div class="tour-modal-section-title">${t('tour.scout_analytics_track', 'Scout & Analytics Tracks')}</div>
            <div class="tour-level-grid">
                <button type="button" class="tour-level-btn" data-level="scout_beginner">
                    <span class="tour-level-name">${t('tour.level_beginner', 'Beginner Track')}${checkMark('scout_beginner')}</span>
                    <span class="tour-level-desc">${t('tour.step.dashboard.title', 'Forms & Basic Entry')}</span>
                </button>
                <button type="button" class="tour-level-btn" data-level="scout_intermediate">
                    <span class="tour-level-name">${t('tour.level_intermediate', 'Intermediate Track')}${checkMark('scout_intermediate')}</span>
                    <span class="tour-level-desc">${t('tour.step.all_data.title', 'Data Browsing & Queries')}</span>
                </button>
                <button type="button" class="tour-level-btn" data-level="scout_advanced">
                    <span class="tour-level-name">${t('tour.level_advanced', 'Advanced Track')}${checkMark('scout_advanced')}</span>
                    <span class="tour-level-desc">${t('tour.step.analytics.title', 'Charts, Predictions & Synergy')}</span>
                </button>
            </div>

            ${adminSectionHtml}
        </div>
    `;

    document.body.appendChild(overlay);

    overlay.querySelector('.tour-modal-close').addEventListener('click', () => {
        overlay.remove();
    });

    overlay.querySelectorAll('.tour-level-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const level = btn.dataset.level;
            overlay.remove();
            startTour(me, level);
        });
    });
}

export async function startTour(me, level) {
    safeSetItem('obsidianscout:tour_active', 'true');
    safeSetItem('obsidianscout:tour_level', level);
    safeSetItem('obsidianscout:tour_step_index', 0);
    await syncTourProgressToServer();
    runActiveTourStep(me);
}
