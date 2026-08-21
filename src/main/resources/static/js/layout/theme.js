/**
 * Layout Theme Module - ObsidianScout
 * Theme initialization, light/dark mode toggling, and dynamic CSS custom property injection.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';

export function applyCustomTheme(themeOrSettings) {
    if (!themeOrSettings) return;
    
    let theme = themeOrSettings;
    if (themeOrSettings.theme !== undefined) {
        theme = themeOrSettings.theme || {};
        safeSetItem("obsidian-custom-theme-config", JSON.stringify(theme));
    }
    
    const isDark = document.body.classList.contains("theme-dark");
    console.log("[Theme] Applying custom theme. Mode:", isDark ? "dark" : "light", "Custom theme object:", theme);
    const target = document.body;
    if (!target) return;
    
    if (theme.btnRadius) target.style.setProperty('--btn-radius', theme.btnRadius);
    else target.style.removeProperty('--btn-radius');
    
    if (isDark) {
        if (theme.darkAccent) target.style.setProperty('--accent', theme.darkAccent);
        else target.style.removeProperty('--accent');
        
        if (theme.darkAccent2) target.style.setProperty('--accent-2', theme.darkAccent2);
        else target.style.removeProperty('--accent-2');
        
        if (theme.darkAccent3) target.style.setProperty('--accent-3', theme.darkAccent3);
        else target.style.removeProperty('--accent-3');
        
        if (theme.darkInk) {
            target.style.setProperty('--ink', theme.darkInk);
            target.style.color = theme.darkInk;
        } else {
            target.style.removeProperty('--ink');
            target.style.color = '';
        }
        
        if (theme.darkMuted) target.style.setProperty('--muted', theme.darkMuted);
        else target.style.removeProperty('--muted');
        
        if (theme.darkBg) target.style.setProperty('--bg', theme.darkBg);
        else target.style.removeProperty('--bg');
    } else {
        if (theme.lightAccent) target.style.setProperty('--accent', theme.lightAccent);
        else target.style.removeProperty('--accent');
        
        if (theme.lightAccent2) target.style.setProperty('--accent-2', theme.lightAccent2);
        else target.style.removeProperty('--accent-2');
        
        if (theme.lightAccent3) target.style.setProperty('--accent-3', theme.lightAccent3);
        else target.style.removeProperty('--accent-3');
        
        if (theme.lightInk) {
            target.style.setProperty('--ink', theme.lightInk);
            target.style.color = theme.lightInk;
        } else {
            target.style.removeProperty('--ink');
            target.style.color = '';
        }
        
        if (theme.lightMuted) target.style.setProperty('--muted', theme.lightMuted);
        else target.style.removeProperty('--muted');
        
        if (theme.lightBg) target.style.setProperty('--bg', theme.lightBg);
        else target.style.removeProperty('--bg');
    }
}

export function initTheme() {
    const saved = safeGetItem("obsidian-theme") || "light";
    const isDark = saved === "dark";
    document.body.classList.toggle("theme-dark", isDark);
    console.log("[Theme] Initialized theme:", saved, "| body classList has theme-dark:", document.body.classList.contains("theme-dark"));
    try {
        const cachedTheme = safeGetItem("obsidian-custom-theme-config");
        if (cachedTheme) {
            applyCustomTheme(JSON.parse(cachedTheme));
        } else {
            const cachedText = safeGetItem("cache:/api/settings");
            if (cachedText) {
                const parsed = JSON.parse(cachedText);
                applyCustomTheme(parsed.settings || parsed);
            }
        }
    } catch (e) {
        console.error("[Theme] Error in initTheme custom theme apply:", e);
    }
}

export function toggleThemeMode() {
    console.log("[Theme] Toggle theme button clicked! Current theme-dark before toggle:", document.body.classList.contains("theme-dark"));
    const isDark = document.body.classList.toggle("theme-dark");
    const newThemeStr = isDark ? "dark" : "light";
    safeSetItem("obsidian-theme", newThemeStr);
    console.log("[Theme] Toggled theme to:", newThemeStr, "| body classList has theme-dark:", document.body.classList.contains("theme-dark"));
    try {
        const cachedTheme = safeGetItem("obsidian-custom-theme-config");
        if (cachedTheme) {
            applyCustomTheme(JSON.parse(cachedTheme));
        } else {
            const cachedText = safeGetItem("cache:/api/settings");
            if (cachedText) {
                const parsed = JSON.parse(cachedText);
                applyCustomTheme(parsed.settings || parsed);
            }
        }
    } catch (err) {
        console.error("[Theme] Error in click toggle custom theme apply:", err);
    }
}

export function bindThemeToggleButtons(root = document) {
    root.querySelectorAll("[data-action='toggle-theme']").forEach((button) => {
        if (button.dataset.themeToggleBound === "true") return;
        button.dataset.themeToggleBound = "true";
        button.addEventListener("click", (e) => {
            e.preventDefault();
            e.stopPropagation();
            toggleThemeMode();
        });
    });
}

export function wireThemeToggle(root = document) {
    bindThemeToggleButtons(root);

    if (window._themeToggleDelegated) return;
    window._themeToggleDelegated = true;
    console.log("[Theme] Registered global click handler for [data-action='toggle-theme']");

    document.addEventListener("click", (e) => {
        const toggle = e.target.closest("[data-action='toggle-theme']");
        if (!toggle || toggle.dataset.themeToggleBound === "true") return;

        e.preventDefault();
        toggleThemeMode();
    });
}
