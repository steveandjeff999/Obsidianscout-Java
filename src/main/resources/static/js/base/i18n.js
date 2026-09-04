/**
 * Base i18n Module - ObsidianScout
 * Internationalization support, translation dictionaries, DOM node auto-translation, and language selector.
 */

import { safeGetItem, safeSetItem } from './storage.js';

export const DEFAULT_LANG = safeGetItem("obsidianscout:lang") || "en";
export let currentLang = DEFAULT_LANG;
export const i18nCache = {};

let translationObserver = null;

export async function loadLocale(lang) {
    if (!lang) lang = "en";
    if (i18nCache[lang]) return i18nCache[lang];

    try {
        const res = await fetch(`/i18n/${lang}.json`);
        if (!res.ok) throw new Error("Locale fetch failed");
        const json = await res.json();
        i18nCache[lang] = json;
        return json;
    } catch (error) {
        if (lang !== "en") return loadLocale("en");
        return {};
    }
}

export function t(key, fallback) {
    const dict = i18nCache[currentLang] || {};
    return dict[key] || (i18nCache['en'] && i18nCache['en'][key]) || fallback || key;
}

/**
 * Localize a dynamic value which may be:
 * - a string (either literal or an i18n key)
 * - an object mapping language codes to translations { en: 'Label', es: 'Etiqueta' }
 */
export function localize(value) {
    if (value === null || value === undefined) return '';
    if (typeof value === 'object') {
        // prefer exact language, fallback to en then any available
        if (value[currentLang]) return value[currentLang];
        if (value.en) return value.en;
        // return first available value
        const keys = Object.keys(value);
        if (keys.length) return value[keys[0]];
        return '';
    }
    if (typeof value === 'string') {
        // if key exists in i18n dicts, return that translation
        const dict = i18nCache[currentLang] || {};
        if (dict[value]) return dict[value];
        if (i18nCache['en'] && i18nCache['en'][value]) return i18nCache['en'][value];
        return value;
    }
    return String(value);
}

export async function setLanguage(lang) {
    currentLang = lang || 'en';
    safeSetItem('obsidianscout:lang', currentLang);
    await loadLocale(currentLang);
    applyTranslations();
    window.dispatchEvent(new CustomEvent('obsidianscout:languagechange', { detail: { lang: currentLang } }));
}

export function applyTranslations() {
    if (translationObserver) {
        translationObserver.disconnect();
    }

    // 1. Translate elements with data-i18n
    document.querySelectorAll('[data-i18n]').forEach((el) => {
        const key = el.dataset.i18n;
        if (!key) return;
        const text = t(key);
        if (el.textContent !== text) {
            el.textContent = text;
        }
    });

    document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
        const key = el.dataset.i18nPlaceholder;
        if (!key) return;
        const text = t(key);
        if (el.getAttribute('placeholder') !== text) {
            el.setAttribute('placeholder', text);
        }
    });

    // 2. Scan for untagged elements containing English text matching en.json
    const enDict = i18nCache['en'] || {};
    const valToKey = {};
    for (const [k, v] of Object.entries(enDict)) {
        if (typeof v === 'string') {
            valToKey[v.trim()] = k;
        }
    }

    const selectors = 'h1, h2, h3, h4, h5, h6, p, span, label, button, option, td, th, div, a';
    document.querySelectorAll(selectors).forEach((el) => {
        if (el.hasAttribute('data-i18n')) return;
        if (el.children.length > 0) return;
        // Ignore sidebar links because they have dynamic page routing names
        if (el.classList.contains('sidebar-link')) return;

        const txt = (el.textContent || '').trim();
        if (!txt) return;

        if (valToKey[txt]) {
            const key = valToKey[txt];
            el.setAttribute('data-i18n', key);
            const translated = t(key);
            if (el.textContent !== translated) {
                el.textContent = translated;
            }
        }
    });

    document.querySelectorAll('input[placeholder], textarea[placeholder]').forEach((el) => {
        if (el.hasAttribute('data-i18n-placeholder')) return;
        const plc = (el.getAttribute('placeholder') || '').trim();
        if (!plc) return;

        if (valToKey[plc]) {
            const key = valToKey[plc];
            el.setAttribute('data-i18n-placeholder', key);
            const translated = t(key);
            if (el.getAttribute('placeholder') !== translated) {
                el.setAttribute('placeholder', translated);
            }
        }
    });

    // Sidebar links by data-page
    document.querySelectorAll('.sidebar-link[data-page]').forEach((link) => {
        const page = link.dataset.page;
        const key = `nav.${page}`;
        const text = t(key);
        if (link.textContent !== text) {
            link.textContent = text;
            link.title = text;
        }
    });

    // Theme toggle and logout
    document.querySelectorAll("[data-action='toggle-theme']").forEach((btn) => {
        const span = btn.querySelector("span");
        if (span) {
            const text = t('btn.toggle_theme', span.textContent.trim());
            if (span.textContent !== text) span.textContent = text;
            btn.setAttribute("title", text);
            btn.setAttribute("aria-label", text);
            return;
        }
        // If the button contains an icon (SVG or child element) or is an icon button, do not overwrite with plain text
        if (btn.querySelector("svg") || btn.classList.contains("btn-theme-toggle") || btn.classList.contains("theme-toggle-btn")) {
            btn.setAttribute("title", t('btn.toggle_theme', 'Toggle theme'));
            btn.setAttribute("aria-label", t('btn.toggle_theme', 'Toggle theme'));
            return;
        }
        const text = t('btn.toggle_theme', btn.textContent.trim());
        if (btn.textContent !== text) btn.textContent = text;
    });
    const logoutBtn = document.querySelector("[data-action='logout']");
    if (logoutBtn) {
        const text = t('btn.logout', logoutBtn.textContent);
        if (logoutBtn.textContent !== text) logoutBtn.textContent = text;
    }

    // Connection widget status text
    const widget = document.getElementById('connection-status-widget');
    if (widget) {
        const textEl = widget.querySelector('.status-text');
        if (textEl) {
            const isOnline = navigator.onLine;
            const text = isOnline ? t('connection.online') : t('connection.offline');
            if (textEl.textContent !== text) textEl.textContent = text;
        }
    }

    // Direction for RTL languages
    if (currentLang === 'he') {
        document.documentElement.dir = 'rtl';
    } else {
        document.documentElement.dir = 'ltr';
    }

    if (translationObserver) {
        translationObserver.observe(document.body, { childList: true, subtree: true });
    }
}

export function injectLanguageSelector(sidebar) {
    try {
        const footer = sidebar.querySelector('.sidebar-footer');
        if (!footer) return;
        // Avoid duplicate
        if (document.getElementById('lang-select')) return;
        const wrap = document.createElement('div');
        wrap.className = 'field';
        wrap.style.marginTop = '8px';
        const sel = document.createElement('select');
        sel.id = 'lang-select';
        sel.style.padding = '6px';
        const opts = [
            { v: 'en', l: 'English' },
            { v: 'es', l: 'Español' },
            { v: 'tr', l: 'Türkçe' },
            { v: 'he', l: 'עברית' }
        ];
        opts.forEach((o) => {
            const opt = document.createElement('option');
            opt.value = o.v;
            opt.textContent = o.l;
            sel.appendChild(opt);
        });
        sel.value = safeGetItem('obsidianscout:lang') || 'en';
        sel.addEventListener('change', async (e) => {
            await setLanguage(e.target.value);
            if (window.Obsidianscout && typeof window.Obsidianscout.updateConnectionStatus === 'function') {
                window.Obsidianscout.updateConnectionStatus();
            }
        });
        wrap.appendChild(sel);
        footer.insertBefore(wrap, footer.firstChild);
    } catch (e) {
        console.warn('Failed to inject language selector', e);
    }
}
