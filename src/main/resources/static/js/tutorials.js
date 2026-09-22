/**
 * Interactive Tutorials Hub Controller - ObsidianScout
 * Renders permission-adapted single-feature tutorials, progress trackers, and Zachary assistant.
 */

import {
    getAccessibleTutorials,
    getCompletedTutorials,
    isTutorialCompleted,
    startTour,
    resetAllTutorialProgress,
    getTutorialMode,
    setTutorialMode,
    getNextIncompleteTutorial,
    speakZachary
} from './components/tour-wizard.js';

const FEATURE_ICONS_SVG = {
    feature_event_setup: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"></path></svg>`,
    feature_dashboard: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path><polyline points="9 22 9 12 15 12 15 22"></polyline></svg>`,
    feature_scout: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"></path><rect x="8" y="2" width="8" height="4" rx="1" ry="1"></rect><path d="M9 14l2 2 4-4"></path></svg>`,
    feature_pit_scout: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path><polyline points="3.27 6.96 12 12.01 20.73 6.96"></polyline><line x1="12" y1="22.08" x2="12" y2="12"></line></svg>`,
    feature_qual_scout: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon></svg>`,
    feature_qr_scanner: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg>`,
    feature_cache_manager: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>`,
    feature_all_data: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"></ellipse><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path></svg>`,
    feature_match_data: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect><line x1="16" y1="2" x2="16" y2="6"></line><line x1="8" y1="2" x2="8" y2="6"></line><line x1="3" y1="10" x2="21" y2="10"></line><path d="m9 16 2 2 4-4"></path></svg>`,
    feature_data_validation: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path><polyline points="9 12 11 14 15 10"></polyline></svg>`,
    feature_analytics: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"></line><line x1="12" y1="20" x2="12" y2="4"></line><line x1="6" y1="20" x2="6" y2="14"></line></svg>`,
    feature_compare: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 3h5v5"></path><path d="M4 20L21 3"></path><path d="M21 16v5h-5"></path><path d="M15 15l6 6"></path><path d="M4 4l5 5"></path></svg>`,
    feature_graphs: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 6 13.5 15.5 8.5 10.5 1 18"></polyline><polyline points="17 6 23 6 23 12"></polyline></svg>`,
    feature_predictor: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>`,
    feature_alliances: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"></path><circle cx="9" cy="7" r="4"></circle><polyline points="16 11 18 13 22 9"></polyline></svg>`,
    feature_alliance_selection: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6"></path><path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18"></path><path d="M4 22h16"></path><path d="M10 14.66V17c0 .55-.45 1-1 1H7c-.55 0-1-.45-1-1v-2.34"></path><path d="M18 14.66V17c0 .55-.45 1-1 1h-2c-.55 0-1-.45-1-1v-2.34"></path><path d="M18 2H6v7a6 6 0 0 0 12 0V2Z"></path></svg>`,
    feature_users: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>`,
    feature_admin_settings: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>`,
    feature_backup: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"></circle><circle cx="6" cy="12" r="3"></circle><circle cx="18" cy="19" r="3"></circle><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line></svg>`
};

document.addEventListener('DOMContentLoaded', async () => {
    // 1. Common initialization
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    // Ensure we do not auto-forward while browsing the tutorials hub
    Obsidianscout.safeRemoveItem('obsidianscout:tour_active');
    Obsidianscout.safeRemoveItem('obsidianscout:tour_active_id');
    Obsidianscout.safeRemoveItem('obsidianscout:tour_step_index');

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    // 2. State & Elements
    let currentFilter = 'all';
    let searchQuery = '';
    const accessibleTutorials = getAccessibleTutorials(me);

    const cardsContainer = document.getElementById('tutorial-cards-container');
    const progressSummaryText = document.getElementById('progress-summary-text');
    const progressBarFill = document.getElementById('progress-bar-fill');
    const heroZacharyText = document.getElementById('hero-zachary-text');
    const heroZacharyImg = document.getElementById('hero-zachary-img');
    const modeSelect = document.getElementById('tutorial-mode-select');
    const resumeBtn = document.getElementById('btn-resume-learning-path');
    const resumeBtnLabel = document.getElementById('resume-btn-label');
    const searchInput = document.getElementById('tutorial-search-input');
    const filterButtons = document.querySelectorAll('.btn-filter');
    const resetBtn = document.getElementById('btn-reset-tutorial-progress');

    // 3. Mode picker init
    if (modeSelect) {
        modeSelect.value = getTutorialMode();
        modeSelect.addEventListener('change', (e) => {
            const newMode = e.target.value;
            setTutorialMode(newMode);
            Obsidianscout.showToast(`Tutorial guide mode set to: ${newMode}`, 'success');
            renderHeroSpeech();
        });
    }

    // 4. Zachary avatar interactive click in hero
    if (heroZacharyImg) {
        heroZacharyImg.addEventListener('click', () => {
            heroZacharyImg.classList.add('zachary-wiggle');
            setTimeout(() => heroZacharyImg.classList.remove('zachary-wiggle'), 600);
            
            const quotes = [
                "I'm Zachary! I analyze event statistics and ensure our scouting data integrity.",
                "Clean quantitative data is what wins alliance selections. Don't let the team down!",
                "Need me to walk you through a feature? Just click 'Start Tutorial' on any card below!",
                "You can switch me to Standard Mode anytime if my technical insights are too advanced for you!"
            ];
            const q = quotes[Math.floor(Math.random() * quotes.length)];
            if (heroZacharyText) {
                heroZacharyText.textContent = q;
            }
            speakZachary(q);
        });
    }

    // 5. Update progress & Zachary Hero text
    function updateProgress() {
        const total = accessibleTutorials.length;
        const completedList = getCompletedTutorials();
        const completedCount = accessibleTutorials.filter(t => completedList.includes(t.id)).length;
        const percent = total > 0 ? Math.round((completedCount / total) * 100) : 0;

        if (progressSummaryText) {
            progressSummaryText.textContent = `${completedCount} / ${total} Completed (${percent}%)`;
        }
        if (progressBarFill) {
            progressBarFill.style.width = `${percent}%`;
        }

        const nextTut = getNextIncompleteTutorial(me);
        if (resumeBtnLabel) {
            if (completedCount === total && total > 0) {
                resumeBtnLabel.textContent = 'Replay Learning Path';
            } else if (nextTut) {
                resumeBtnLabel.textContent = `Next: ${nextTut.title}`;
            } else {
                resumeBtnLabel.textContent = 'Start First Tutorial';
            }
        }

        renderHeroSpeech(completedCount, total, percent);
    }

    function renderHeroSpeech(completedCount = 0, total = 0, percent = 0) {
        if (!heroZacharyText) return;
        const mode = getTutorialMode();

        if (mode === 'disabled') {
            heroZacharyText.textContent = "Tutorials are currently disabled. You can re-enable Zachary or Standard mode anytime using the dropdown on the right!";
            return;
        }

        if (percent === 100 && total > 0) {
            heroZacharyText.textContent = "🏆 INCREDIBLE! You've mastered every single feature available to your account! You're officially a certified ObsidianScout legend!";
        } else if (percent >= 50) {
            heroZacharyText.textContent = `You're over halfway there with ${completedCount} of ${total} features mastered! Keep rolling, the strategy team is going to love you!`;
        } else if (completedCount > 0) {
            heroZacharyText.textContent = `Great start! You've completed ${completedCount} features. Pick another card below to keep leveling up!`;
        } else {
            heroZacharyText.textContent = "Welcome to the interactive learning hub! Choose any feature below to start a step-by-step walkthrough with yours truly!";
        }
    }

    // 6. Render Feature Cards
    function renderCards() {
        if (!cardsContainer) return;
        cardsContainer.innerHTML = '';

        let filtered = accessibleTutorials.filter(tut => {
            const completed = isTutorialCompleted(tut.id);
            if (currentFilter === 'completed' && !completed) return false;
            if (currentFilter === 'not_completed' && completed) return false;
            if (searchQuery) {
                const q = searchQuery.toLowerCase();
                return tut.title.toLowerCase().includes(q) || tut.desc.toLowerCase().includes(q);
            }
            return true;
        });

        if (filtered.length === 0) {
            cardsContainer.innerHTML = `
                <div class="tutorial-empty-state">
                    <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" style="color: var(--muted); margin-bottom: 12px;"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>
                    <p>No feature tutorials match your search or filter.</p>
                </div>
            `;
            return;
        }

        filtered.forEach(tut => {
            const completed = isTutorialCompleted(tut.id);
            const card = document.createElement('div');
            card.className = `tutorial-card ${completed ? 'completed' : ''}`;
            
            const iconSvg = FEATURE_ICONS_SVG[tut.id] || `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>`;

            const multiPageBadge = tut.pages && tut.pages.length > 1 
                ? `<span class="tutorial-card-pill pill-multipage" title="Covers ${tut.pages.length} interconnected pages"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="margin-right:2px;"><polygon points="12 2 2 7 12 12 22 7 12 2"></polygon><polyline points="2 17 12 22 22 17"></polyline><polyline points="2 12 12 17 22 12"></polyline></svg> ${tut.pages.length} Pages</span>`
                : '';

            const durationBadge = `<span class="tutorial-card-pill pill-duration"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="margin-right:2px;"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg> ${tut.duration || '2 min'}</span>`;

            const statusBadge = completed ? '<span class="tutorial-card-pill pill-status-done"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" style="margin-right:2px;"><polyline points="20 6 9 17 4 12"></polyline></svg> Mastered</span>' : '';

            card.innerHTML = `
                <div class="tutorial-card-header">
                    <div class="tutorial-card-icon">
                        ${iconSvg}
                    </div>
                    <div class="tutorial-card-badges">
                        ${multiPageBadge}
                        ${durationBadge}
                        ${statusBadge}
                    </div>
                </div>

                <div class="tutorial-card-body">
                    <h3 class="tutorial-card-title">${tut.title}</h3>
                    <p class="tutorial-card-desc">${tut.desc}</p>
                </div>

                <div class="tutorial-card-footer">
                    <span class="tutorial-steps-count">${tut.steps.length} Steps</span>
                    <button type="button" class="btn ${completed ? 'ghost' : 'primary'} btn-start-single-tut" data-tut-id="${tut.id}">
                        ${completed 
                            ? `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="margin-right:4px;"><polyline points="23 4 23 10 17 10"></polyline><polyline points="1 20 1 14 7 14"></polyline><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg> Review` 
                            : `<svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" style="margin-right:4px;"><polygon points="5 3 19 12 5 21 5 3"></polygon></svg> Start`}
                    </button>
                </div>
            `;

            card.querySelector('.btn-start-single-tut').addEventListener('click', () => {
                startTour(me, tut.id);
            });

            cardsContainer.appendChild(card);
        });
    }

    // 7. Event listeners
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            searchQuery = e.target.value.trim();
            renderCards();
        });
    }

    filterButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            filterButtons.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentFilter = btn.dataset.filter;
            renderCards();
        });
    });

    if (resumeBtn) {
        resumeBtn.addEventListener('click', () => {
            const nextTut = getNextIncompleteTutorial(me);
            if (nextTut) {
                startTour(me, nextTut.id);
            } else if (accessibleTutorials.length > 0) {
                startTour(me, accessibleTutorials[0].id);
            }
        });
    }

    if (resetBtn) {
        resetBtn.addEventListener('click', async () => {
            if (confirm('Are you sure you want to reset all your tutorial progress?')) {
                await resetAllTutorialProgress();
                Obsidianscout.showToast('Tutorial progress reset', 'info');
                updateProgress();
                renderCards();
            }
        });
    }

    // 8. Initial render
    updateProgress();
    renderCards();
});
