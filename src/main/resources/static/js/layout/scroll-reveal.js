/**
 * ==========================================================================
 * Scroll Reveal / Dynamic Slide-In Controller
 * Provides ultra-fast, snappy slide-in animations as elements enter the viewport
 * ==========================================================================
 */

let observer = null;
let mutationObserver = null;

const REVEAL_SELECTORS = [
    '.card',
    '.metric-card',
    '.dashboard-kpi-card',
    '.dash-tile',
    '.dash-match-card',
    '.team-stat-card',
    '.record-card',
    '.phase-card',
    '.table-container',
    '.form-card',
    '.auth-card',
    '.panel',
    '.scroll-reveal'
].join(',');

const IGNORE_SELECTORS = [
    '.sidebar',
    '.mobile-topbar',
    '.header-actions',
    '.modal',
    '.modal-content',
    '.toast',
    '.no-reveal'
].join(',');

function shouldAnimate() {
    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) {
        return false;
    }
    const prefersReducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    return !prefersReducedMotion;
}

export function initScrollReveal() {
    if (!shouldAnimate()) {
        document.querySelectorAll(REVEAL_SELECTORS).forEach((el) => {
            el.classList.add('is-revealed');
        });
        return;
    }

    if (!observer) {
        observer = new IntersectionObserver((entries, obs) => {
            entries.forEach((entry) => {
                if (entry.isIntersecting) {
                    entry.target.classList.add('is-revealed');
                    obs.unobserve(entry.target);
                }
            });
        }, {
            root: null,
            rootMargin: '0px 0px -20px 0px',
            threshold: 0.02
        });
    }

    refreshScrollReveal();

    // Observe dynamically added content
    if (!mutationObserver && typeof MutationObserver !== 'undefined') {
        mutationObserver = new MutationObserver(() => {
            refreshScrollReveal();
        });
        const main = document.querySelector('main, .main-content, .app-shell, body');
        if (main) {
            mutationObserver.observe(main, { childList: true, subtree: true });
        }
    }

    // Safety fallback: ensure no element remains hidden if scroll container differs
    setTimeout(() => {
        document.querySelectorAll('.scroll-reveal:not(.is-revealed)').forEach((el) => {
            const rect = el.getBoundingClientRect();
            if (rect.top < window.innerHeight + 100) {
                el.classList.add('is-revealed');
            }
        });
    }, 500);
}

export function refreshScrollReveal() {
    if (!observer || !shouldAnimate()) return;

    const elements = document.querySelectorAll(REVEAL_SELECTORS);
    elements.forEach((el) => {
        if (el.closest(IGNORE_SELECTORS)) return;

        if (!el.classList.contains('scroll-reveal')) {
            el.classList.add('scroll-reveal');
            
            // If already in viewport on page load, reveal immediately
            const rect = el.getBoundingClientRect();
            if (rect.top < window.innerHeight && rect.bottom > 0) {
                // Snappy micro-delay for above-the-fold elements
                requestAnimationFrame(() => {
                    el.classList.add('is-revealed');
                });
            } else {
                observer.observe(el);
            }
        } else if (!el.classList.contains('is-revealed')) {
            const rect = el.getBoundingClientRect();
            if (rect.top < window.innerHeight && rect.bottom > 0) {
                el.classList.add('is-revealed');
                observer.unobserve(el);
            } else {
                observer.observe(el);
            }
        }
    });
}
