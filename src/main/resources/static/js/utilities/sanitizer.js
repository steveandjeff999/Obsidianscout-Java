/**
 * Sanitizer Utility Module - ObsidianScout
 * Wraps DOMPurify with strict allowlist policies and link hardening for alert banners and user-generated text.
 */

import { escapeHtml } from './helpers.js';

let purifyPromise = null;
let hookInitialized = false;

function setupPurifyHooks(purifyInstance) {
    if (hookInitialized || !purifyInstance || !purifyInstance.addHook) return;
    hookInitialized = true;

    purifyInstance.addHook('afterSanitizeAttributes', (node) => {
        // Enforce safe link behavior
        if (node.tagName === 'A') {
            node.setAttribute('target', '_blank');
            node.setAttribute('rel', 'noopener noreferrer');
        }
    });
}

/**
 * Ensures DOMPurify is loaded from /vendor/purify.min.js and configured.
 * Returns the DOMPurify instance, or null if loading failed.
 */
export function ensureDOMPurify() {
    if (typeof window === 'undefined') return Promise.resolve(null);
    if (window.DOMPurify) {
        setupPurifyHooks(window.DOMPurify);
        return Promise.resolve(window.DOMPurify);
    }
    if (!purifyPromise) {
        purifyPromise = new Promise((resolve) => {
            const script = document.createElement('script');
            script.src = '/vendor/purify.min.js';
            script.onload = () => {
                if (window.DOMPurify) {
                    setupPurifyHooks(window.DOMPurify);
                }
                resolve(window.DOMPurify || null);
            };
            script.onerror = (err) => {
                console.warn('Failed to load DOMPurify from /vendor/purify.min.js; fallback escaping will be active.', err);
                resolve(null);
            };
            document.head.appendChild(script);
        });
    }
    return purifyPromise;
}

export const BANNER_PURIFY_CONFIG = {
    ALLOWED_TAGS: [
        'b', 'strong', 'i', 'em', 'u', 's', 'strike',
        'span', 'p', 'br', 'a', 'code', 'pre',
        'ul', 'ol', 'li'
    ],
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class'],
    ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i,
    RETURN_TRUSTED_TYPE: false
};

/**
 * Synchronously sanitizes banner HTML using DOMPurify if available,
 * or safely escapes HTML as a resilient fallback.
 *
 * @param {string|null|undefined} rawHtml
 * @returns {string} Sanitized HTML string safe for innerHTML insertion
 */
export function sanitizeBannerHtml(rawHtml) {
    if (rawHtml === null || rawHtml === undefined) return '';
    const str = String(rawHtml);
    if (!str.trim()) return '';

    if (typeof window !== 'undefined' && window.DOMPurify && typeof window.DOMPurify.sanitize === 'function') {
        setupPurifyHooks(window.DOMPurify);
        return window.DOMPurify.sanitize(str, BANNER_PURIFY_CONFIG);
    }

    // Fallback: Safe escape without allowing raw un-sanitized tags
    return escapeHtml(str);
}
