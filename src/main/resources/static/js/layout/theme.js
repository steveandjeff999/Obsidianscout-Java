/**
 * Layout Theme Module - ObsidianScout
 * Theme initialization, light/dark mode toggling, and dynamic CSS custom property injection.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';

function parseHexColor(str) {
    if (!str || typeof str !== 'string') return null;
    const match = str.match(/#(?:[0-9a-fA-F]{3,8})/);
    if (!match) return null;
    let hex = match[0].replace('#', '');
    if (hex.length === 3) {
        hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
    }
    if (hex.length >= 6) {
        return {
            r: parseInt(hex.substring(0, 2), 16),
            g: parseInt(hex.substring(2, 4), 16),
            b: parseInt(hex.substring(4, 6), 16)
        };
    }
    return null;
}

function rgbToHex(r, g, b) {
    const clamp = (v) => Math.max(0, Math.min(255, Math.round(v)));
    const toHex = (v) => clamp(v).toString(16).padStart(2, '0');
    return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

function blendRgb(c1, c2, weight) {
    return {
        r: c1.r * (1 - weight) + c2.r * weight,
        g: c1.g * (1 - weight) + c2.g * weight,
        b: c1.b * (1 - weight) + c2.b * weight
    };
}

export function getContrastColor(hexColor) {
    const rgb = parseHexColor(hexColor);
    if (!rgb) return '#ffffff';
    const yiq = (rgb.r * 299 + rgb.g * 587 + rgb.b * 114) / 1000;
    return yiq >= 165 ? '#09090b' : '#ffffff';
}

function computeCardRadius(radiusStr) {
    if (!radiusStr) return '18px';
    const clean = radiusStr.trim();
    if (clean === '0px') return '0px';
    if (clean === '6px') return '8px';
    if (clean === '8px') return '12px';
    if (clean === '12px') return '16px';
    const num = parseInt(clean);
    if (isNaN(num)) return '18px';
    if (num >= 999) return '20px';
    return Math.min(24, Math.max(0, num)) + 'px';
}

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
    
    const isExplicitDefault = (theme.name === "Default" || !theme.name) &&
        !theme.darkCard && !theme.lightCard &&
        (!theme.darkBg || theme.darkBg === "#09090b" || theme.darkBg === "#000000" || theme.darkBg === "#0a0c10") &&
        (!theme.lightBg || theme.lightBg === "#ffffff" || theme.lightBg === "#f8fafc");

    const activeRadius = isDark
        ? (theme.darkRadius || theme.btnRadius)
        : (theme.lightRadius || theme.btnRadius);
    if (activeRadius) target.style.setProperty('--btn-radius', activeRadius);
    else target.style.removeProperty('--btn-radius');

    if (isExplicitDefault) {
        // Cleanly restore default card & surface styles for standard Default preset
        target.style.removeProperty('--card-radius');
        target.style.removeProperty('--surface');
        target.style.removeProperty('--surface-1');
        target.style.removeProperty('--surface-2');
        target.style.removeProperty('--surface-3');
        target.style.removeProperty('--card-bg');
        target.style.removeProperty('--bg-card');
        target.style.removeProperty('--card-hover-bg');
        target.style.removeProperty('--surface-glass');
        target.style.removeProperty('--border-glass');
        target.style.removeProperty('--border');
        target.style.removeProperty('--border-subtle');
        target.style.removeProperty('--border-color');
        target.style.removeProperty('--primary');
        target.style.removeProperty('--on-primary');
        target.style.removeProperty('--accent-glow');
    } else {
        // Custom theme active: style cards and surfaces harmoniously
        const cardRadius = computeCardRadius(activeRadius);
        target.style.setProperty('--card-radius', cardRadius);

        if (isDark) {
            let surfaceColor = null;
            if (theme.darkCard) {
                surfaceColor = parseHexColor(theme.darkCard);
            }
            if (!surfaceColor) {
                const bgParsed = parseHexColor(theme.darkBg);
                if (bgParsed) {
                    // Elevate dark background by blending with 8% white and subtle accent tint
                    let elevated = blendRgb(bgParsed, { r: 255, g: 255, b: 255 }, 0.08);
                    const accParsed = parseHexColor(theme.darkAccent);
                    if (accParsed) {
                        elevated = blendRgb(elevated, accParsed, 0.04);
                    }
                    surfaceColor = elevated;
                }
            }

            if (surfaceColor) {
                const surfaceHex = rgbToHex(surfaceColor.r, surfaceColor.g, surfaceColor.b);
                const surface2 = blendRgb(surfaceColor, { r: 255, g: 255, b: 255 }, 0.06);
                const surface2Hex = rgbToHex(surface2.r, surface2.g, surface2.b);
                const surface3 = blendRgb(surfaceColor, { r: 255, g: 255, b: 255 }, 0.12);
                const surface3Hex = rgbToHex(surface3.r, surface3.g, surface3.b);

                target.style.setProperty('--surface', surfaceHex);
                target.style.setProperty('--surface-1', surfaceHex);
                target.style.setProperty('--card-bg', surfaceHex);
                target.style.setProperty('--bg-card', surfaceHex);
                target.style.setProperty('--surface-2', surface2Hex);
                target.style.setProperty('--card-hover-bg', surface2Hex);
                target.style.setProperty('--surface-3', surface3Hex);

                const glassBg = `rgba(${Math.round(surfaceColor.r)}, ${Math.round(surfaceColor.g)}, ${Math.round(surfaceColor.b)}, 0.85)`;
                target.style.setProperty('--surface-glass', glassBg);
                const subtleBorder = 'rgba(255, 255, 255, 0.12)';
                target.style.setProperty('--border', subtleBorder);
                target.style.setProperty('--border-subtle', 'rgba(255, 255, 255, 0.07)');
                target.style.setProperty('--border-color', subtleBorder);
                target.style.setProperty('--border-glass', subtleBorder);
            }
        } else {
            // Light mode custom theme
            let surfaceColor = null;
            if (theme.lightCard) {
                surfaceColor = parseHexColor(theme.lightCard);
            }
            if (!surfaceColor) {
                surfaceColor = { r: 255, g: 255, b: 255 };
            }

            const surfaceHex = rgbToHex(surfaceColor.r, surfaceColor.g, surfaceColor.b);
            const surface2 = blendRgb(surfaceColor, { r: 15, g: 23, b: 42 }, 0.03);
            const surface2Hex = rgbToHex(surface2.r, surface2.g, surface2.b);
            const surface3 = blendRgb(surfaceColor, { r: 15, g: 23, b: 42 }, 0.06);
            const surface3Hex = rgbToHex(surface3.r, surface3.g, surface3.b);

            target.style.setProperty('--surface', surfaceHex);
            target.style.setProperty('--surface-1', surfaceHex);
            target.style.setProperty('--card-bg', surfaceHex);
            target.style.setProperty('--bg-card', surfaceHex);
            target.style.setProperty('--surface-2', surface2Hex);
            target.style.setProperty('--card-hover-bg', surface2Hex);
            target.style.setProperty('--surface-3', surface3Hex);
            target.style.setProperty('--surface-glass', 'rgba(255, 255, 255, 0.8)');
            target.style.setProperty('--border', 'rgba(148, 163, 184, 0.22)');
            target.style.setProperty('--border-subtle', 'rgba(148, 163, 184, 0.12)');
            target.style.setProperty('--border-color', 'rgba(148, 163, 184, 0.22)');
            target.style.setProperty('--border-glass', 'rgba(255, 255, 255, 0.4)');
        }
    }
    
    if (isDark) {
        if (theme.darkAccent) {
            target.style.setProperty('--accent', theme.darkAccent);
            target.style.setProperty('--primary', theme.darkAccent);
            target.style.setProperty('--on-primary', getContrastColor(theme.darkAccent));
            const accParsed = parseHexColor(theme.darkAccent);
            if (accParsed) {
                target.style.setProperty('--accent-glow', `rgba(${accParsed.r}, ${accParsed.g}, ${accParsed.b}, 0.35)`);
            }
        } else {
            target.style.removeProperty('--accent');
            target.style.removeProperty('--primary');
            target.style.removeProperty('--on-primary');
            target.style.removeProperty('--accent-glow');
        }
        
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
        if (theme.lightAccent) {
            target.style.setProperty('--accent', theme.lightAccent);
            target.style.setProperty('--primary', theme.lightAccent);
            target.style.setProperty('--on-primary', getContrastColor(theme.lightAccent));
            const accParsed = parseHexColor(theme.lightAccent);
            if (accParsed) {
                target.style.setProperty('--accent-glow', `rgba(${accParsed.r}, ${accParsed.g}, ${accParsed.b}, 0.25)`);
            }
        } else {
            target.style.removeProperty('--accent');
            target.style.removeProperty('--primary');
            target.style.removeProperty('--on-primary');
            target.style.removeProperty('--accent-glow');
        }
        
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
