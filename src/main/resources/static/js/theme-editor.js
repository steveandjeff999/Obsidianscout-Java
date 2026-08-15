document.addEventListener("DOMContentLoaded", async () => {
    // 1. Authenticate and authorize
    const adminLocked = document.getElementById("admin-locked");
    const adminPanel = document.getElementById("admin-panel");

    let me = null;
    try {
        me = await Obsidianscout.requireAuth();
    } catch (e) {
        // Redirect to login handled by requireAuth
        return;
    }

    if (!me || !Obsidianscout.isAdmin(me.role)) {
        if (adminLocked) adminLocked.classList.remove("hidden");
        if (adminPanel) adminPanel.classList.add("hidden");
        return;
    }

    if (adminLocked) adminLocked.classList.add("hidden");
    if (adminPanel) adminPanel.classList.remove("hidden");

    // Initialize layout / sidebar
    if (window.Obsidianscout && typeof Obsidianscout.initLayout === "function") {
        Obsidianscout.initLayout();
    }
    if (window.Obsidianscout && typeof Obsidianscout.initTheme === "function") {
        Obsidianscout.initTheme();
    }

    // 2. Load settings
    const previewBox = document.getElementById("theme-preview-box");
    let loadedSettings = {};
    let themes = [];
    let activeThemeName = "";
    let selectedPresetIndex = 0;
    let isSaving = false;
    let pendingSave = false;

    try {
        const response = await Obsidianscout.request("/api/settings?local=true");
        loadedSettings = response.settings || response;
        themes = loadedSettings.themes || [];
        activeThemeName = loadedSettings.activeThemeName || "";

        // If themes list is empty, initialize with a Default preset using existing theme values
        if (themes.length === 0) {
            const legacyTheme = loadedSettings.theme || {};
            themes.push({
                name: "Default",
                lightAccent: legacyTheme.lightAccent || "#18181b",
                lightAccent2: legacyTheme.lightAccent2 || "#27272a",
                lightAccent3: legacyTheme.lightAccent3 || "#3f3f46",
                lightInk: legacyTheme.lightInk || "#09090b",
                lightMuted: legacyTheme.lightMuted || "#71717a",
                lightBg: legacyTheme.lightBg || "#ffffff",
                darkAccent: legacyTheme.darkAccent || "#3b82f6",
                darkAccent2: legacyTheme.darkAccent2 || "#38bdf8",
                darkAccent3: legacyTheme.darkAccent3 || "#a855f7",
                darkInk: legacyTheme.darkInk || "#f8fafc",
                darkMuted: legacyTheme.darkMuted || "#94a3b8",
                darkBg: legacyTheme.darkBg || "#09090b",
                btnRadius: legacyTheme.btnRadius || "8px"
            });
            activeThemeName = "Default";
        }

        // Set initial selected preset index
        selectedPresetIndex = themes.findIndex(t => t.name === activeThemeName);
        if (selectedPresetIndex === -1) {
            selectedPresetIndex = 0;
        }

        rebuildPresetDropdown();
        populateForm(themes[selectedPresetIndex]);
        updateVisualThemePreview();

    } catch (error) {
        Obsidianscout.showToast(error.message || "Failed to load settings", "error");
    }

    // 3. Background Design Engine Helpers
    function switchBgMode(target, mode) {
        const btns = document.querySelectorAll(`.bg-mode-btn[data-target="${target}"]`);
        btns.forEach(btn => {
            btn.classList.toggle("active", btn.dataset.mode === mode);
        });
        
        const sections = ["solid", "gradient", "preset"];
        sections.forEach(sec => {
            const el = document.getElementById(`bg-editor-${sec}-${target}`);
            if (el) {
                el.classList.toggle("hidden", sec !== mode);
            }
        });
    }

    function parseAndPopulateBg(target, bgValue) {
        if (!bgValue) bgValue = "";
        
        const presetItems = document.querySelectorAll(`#bg-editor-preset-${target} .bg-preset-item`);
        let foundPreset = false;
        
        // Remove active borders from all presets
        presetItems.forEach(item => item.style.outline = "");

        for (const item of presetItems) {
            if (item.getAttribute("data-value") === bgValue) {
                switchBgMode(target, "preset");
                item.style.outline = "3px solid var(--accent)";
                foundPreset = true;
                break;
            }
        }
        
        if (!foundPreset) {
            const gradMatch = bgValue.match(/linear-gradient\((\d+)deg,\s*(#[a-fA-F0-9]{6})\s+0%,\s*(#[a-fA-F0-9]{6})\s+100%\)/);
            if (gradMatch) {
                switchBgMode(target, "gradient");
                document.getElementById(`theme-${target}-bg-grad1`).value = gradMatch[2];
                document.getElementById(`theme-${target}-bg-grad2`).value = gradMatch[3];
                document.getElementById(`theme-${target}-bg-angle`).value = gradMatch[1];
                document.getElementById(`theme-${target}-bg-angle-val`).textContent = gradMatch[1] + "°";
            } else {
                switchBgMode(target, "solid");
                if (bgValue.startsWith("#") && (bgValue.length === 4 || bgValue.length === 7)) {
                    document.getElementById(`theme-${target}-bg-solid`).value = bgValue;
                } else {
                    document.getElementById(`theme-${target}-bg-solid`).value = target === "light" ? "#f6f1e8" : "#0f172a";
                }
            }
        }
        
        document.getElementById(`theme-${target}-bg`).value = bgValue;
    }

    function setupBgEventListeners(target) {
        // Mode switch tabs
        const btns = document.querySelectorAll(`.bg-mode-btn[data-target="${target}"]`);
        btns.forEach(btn => {
            btn.addEventListener("click", async () => {
                switchBgMode(target, btn.dataset.mode);
                generateBgFromControls(target);
                await saveAllThemes(true);
            });
        });
        
        // Solid color input
        const solidColorInput = document.getElementById(`theme-${target}-bg-solid`);
        if (solidColorInput) {
            solidColorInput.addEventListener("input", () => generateBgFromControls(target));
            solidColorInput.addEventListener("change", () => saveAllThemes(true));
        }
        
        // Gradient color inputs
        const grad1 = document.getElementById(`theme-${target}-bg-grad1`);
        const grad2 = document.getElementById(`theme-${target}-bg-grad2`);
        const angle = document.getElementById(`theme-${target}-bg-angle`);
        
        if (grad1) {
            grad1.addEventListener("input", () => generateBgFromControls(target));
            grad1.addEventListener("change", () => saveAllThemes(true));
        }
        if (grad2) {
            grad2.addEventListener("input", () => generateBgFromControls(target));
            grad2.addEventListener("change", () => saveAllThemes(true));
        }
        if (angle) {
            angle.addEventListener("input", () => {
                document.getElementById(`theme-${target}-bg-angle-val`).textContent = angle.value + "°";
                generateBgFromControls(target);
            });
            angle.addEventListener("change", () => saveAllThemes(true));
        }
        
        // Preset clicks
        const presetItems = document.querySelectorAll(`#bg-editor-preset-${target} .bg-preset-item`);
        presetItems.forEach(item => {
            item.addEventListener("click", async () => {
                presetItems.forEach(i => i.style.outline = "");
                item.style.outline = "3px solid var(--accent)";
                
                const val = item.getAttribute("data-value");
                document.getElementById(`theme-${target}-bg`).value = val;
                updateVisualThemePreview();
                await saveAllThemes(true);
            });
        });
    }

    function generateBgFromControls(target) {
        const activeBtn = document.querySelector(`.bg-mode-btn.active[data-target="${target}"]`);
        const mode = activeBtn ? activeBtn.dataset.mode : "solid";
        let val = "";
        
        if (mode === "solid") {
            val = document.getElementById(`theme-${target}-bg-solid`).value;
        } else if (mode === "gradient") {
            const g1 = document.getElementById(`theme-${target}-bg-grad1`).value;
            const g2 = document.getElementById(`theme-${target}-bg-grad2`).value;
            const angleVal = document.getElementById(`theme-${target}-bg-angle`).value;
            document.getElementById(`theme-${target}-bg-angle-val`).textContent = angleVal + "°";
            val = `linear-gradient(${angleVal}deg, ${g1} 0%, ${g2} 100%)`;
        } else if (mode === "preset") {
            const activePreset = document.querySelector(`#bg-editor-preset-${target} .bg-preset-item[style*="outline"]`);
            if (activePreset) {
                val = activePreset.getAttribute("data-value");
            } else {
                val = document.getElementById(`theme-${target}-bg`).value;
            }
        }
        
        document.getElementById(`theme-${target}-bg`).value = val;
        updateVisualThemePreview();
    }

    // 4. Form Population and Extraction
    function populateForm(theme) {
        if (!theme) return;
        document.getElementById("theme-light-accent").value = theme.lightAccent || "#0b8f88";
        document.getElementById("theme-light-accent2").value = theme.lightAccent2 || "#f28b35";
        document.getElementById("theme-light-accent3").value = theme.lightAccent3 || "#255a9c";
        document.getElementById("theme-light-ink").value = theme.lightInk || "#1d1a17";
        document.getElementById("theme-light-muted").value = theme.lightMuted || "#5f5b55";
        document.getElementById("theme-light-radius").value = theme.btnRadius || "999px";
        parseAndPopulateBg("light", theme.lightBg);

        document.getElementById("theme-dark-accent").value = theme.darkAccent || "#3ccfc0";
        document.getElementById("theme-dark-accent2").value = theme.darkAccent2 || "#f2a353";
        document.getElementById("theme-dark-accent3").value = theme.darkAccent3 || "#6aa2ff";
        document.getElementById("theme-dark-ink").value = theme.darkInk || "#f4f2ed";
        document.getElementById("theme-dark-muted").value = theme.darkMuted || "#c3bfb8";
        document.getElementById("theme-dark-radius").value = theme.btnRadius || "999px";
        parseAndPopulateBg("dark", theme.darkBg);
    }

    function saveCurrentPresetFromForm() {
        if (selectedPresetIndex < 0 || selectedPresetIndex >= themes.length) return;
        const radiusVal = document.getElementById("theme-light-radius").value; // Keep radius uniform
        themes[selectedPresetIndex].lightAccent = document.getElementById("theme-light-accent").value;
        themes[selectedPresetIndex].lightAccent2 = document.getElementById("theme-light-accent2").value;
        themes[selectedPresetIndex].lightAccent3 = document.getElementById("theme-light-accent3").value;
        themes[selectedPresetIndex].lightInk = document.getElementById("theme-light-ink").value;
        themes[selectedPresetIndex].lightMuted = document.getElementById("theme-light-muted").value;
        themes[selectedPresetIndex].lightBg = document.getElementById("theme-light-bg").value.trim();
        themes[selectedPresetIndex].btnRadius = radiusVal;

        themes[selectedPresetIndex].darkAccent = document.getElementById("theme-dark-accent").value;
        themes[selectedPresetIndex].darkAccent2 = document.getElementById("theme-dark-accent2").value;
        themes[selectedPresetIndex].darkAccent3 = document.getElementById("theme-dark-accent3").value;
        themes[selectedPresetIndex].darkInk = document.getElementById("theme-dark-ink").value;
        themes[selectedPresetIndex].darkMuted = document.getElementById("theme-dark-muted").value;
        themes[selectedPresetIndex].darkBg = document.getElementById("theme-dark-bg").value.trim();
        
        // Match dark mode selector to light mode selector for consistency
        document.getElementById("theme-dark-radius").value = radiusVal;
    }

    function rebuildPresetDropdown() {
        const selector = document.getElementById("preset-selector");
        if (!selector) return;
        selector.innerHTML = "";
        themes.forEach((t, index) => {
            const opt = document.createElement("option");
            opt.value = index;
            opt.textContent = t.name;
            selector.appendChild(opt);
        });
        selector.value = selectedPresetIndex;
        updateActivePresetLabel();
    }

    function updateActivePresetLabel() {
        const selector = document.getElementById("preset-selector");
        if (selector) {
            selector.value = selectedPresetIndex;
        }
        const nameLabel = document.getElementById("active-preset-name");
        if (nameLabel) {
            nameLabel.textContent = activeThemeName || "None";
        }
    }

    // 5. Live Component Sandbox preview logic
    
    function updateVisualThemePreview() {
        if (!previewBox) return;
        const isDarkPreview = previewBox.classList.contains("theme-dark-preview");
        const radiusVal = document.getElementById("theme-light-radius").value;
        
        previewBox.style.setProperty('--preview-radius', radiusVal);

        if (isDarkPreview) {
            const bg = document.getElementById("theme-dark-bg").value.trim() || "radial-gradient(ellipse at 20% 10%, #1a1f2e 0%, #13161c 45%, #0d1015 100%)";
            const accent = document.getElementById("theme-dark-accent").value;
            const accent2 = document.getElementById("theme-dark-accent2").value;
            const accent3 = document.getElementById("theme-dark-accent3").value;
            const ink = document.getElementById("theme-dark-ink").value;
            const muted = document.getElementById("theme-dark-muted").value;
            
            previewBox.style.background = bg;
            previewBox.style.setProperty('--preview-accent', accent);
            previewBox.style.setProperty('--preview-accent2', accent2);
            previewBox.style.setProperty('--preview-accent3', accent3);
            previewBox.style.setProperty('--preview-ink', ink);
            previewBox.style.setProperty('--preview-muted', muted);
        } else {
            const bg = document.getElementById("theme-light-bg").value.trim() || "radial-gradient(ellipse at 20% 10%, #ffecd2 0%, #f6f1e8 40%, #daeef0 100%)";
            const accent = document.getElementById("theme-light-accent").value;
            const accent2 = document.getElementById("theme-light-accent2").value;
            const accent3 = document.getElementById("theme-light-accent3").value;
            const ink = document.getElementById("theme-light-ink").value;
            const muted = document.getElementById("theme-light-muted").value;
            
            previewBox.style.background = bg;
            previewBox.style.setProperty('--preview-accent', accent);
            previewBox.style.setProperty('--preview-accent2', accent2);
            previewBox.style.setProperty('--preview-accent3', accent3);
            previewBox.style.setProperty('--preview-ink', ink);
            previewBox.style.setProperty('--preview-muted', muted);
        }
    }

    // 6. Wire Events
    const presetSelector = document.getElementById("preset-selector");
    if (presetSelector) {
        presetSelector.addEventListener("change", async () => {
            saveCurrentPresetFromForm();
            selectedPresetIndex = parseInt(presetSelector.value);
            populateForm(themes[selectedPresetIndex]);
            activeThemeName = themes[selectedPresetIndex].name;
            updateActivePresetLabel();
            updateVisualThemePreview();
            await saveAllThemes(true);
        });
    }

    const themeInputs = [
        "theme-light-accent", "theme-light-accent2", "theme-light-accent3", "theme-light-ink", "theme-light-muted",
        "theme-dark-accent", "theme-dark-accent2", "theme-dark-accent3", "theme-dark-ink", "theme-dark-muted"
    ];
    themeInputs.forEach(id => {
        const el = document.getElementById(id);
        if (el) {
            el.addEventListener("input", updateVisualThemePreview);
            el.addEventListener("change", () => saveAllThemes(true));
        }
    });

    // Make radius selection uniform between light & dark
    const lightRadius = document.getElementById("theme-light-radius");
    const darkRadius = document.getElementById("theme-dark-radius");
    if (lightRadius) {
        lightRadius.addEventListener("change", () => {
            if (darkRadius) darkRadius.value = lightRadius.value;
            updateVisualThemePreview();
            saveAllThemes(true);
        });
    }
    if (darkRadius) {
        darkRadius.addEventListener("change", () => {
            if (lightRadius) lightRadius.value = darkRadius.value;
            updateVisualThemePreview();
            saveAllThemes(true);
        });
    }

    const togglePreviewModeBtn = document.getElementById("preview-toggle-mode");
    if (togglePreviewModeBtn && previewBox) {
        const isBodyDark = document.body.classList.contains("theme-dark");
        previewBox.classList.toggle("theme-dark-preview", isBodyDark);
        
        togglePreviewModeBtn.addEventListener("click", () => {
            previewBox.classList.toggle("theme-dark-preview");
            updateVisualThemePreview();
        });
    }

    // Preset Buttons
    const btnCreate = document.getElementById("btn-create-preset");
    if (btnCreate) {
        btnCreate.addEventListener("click", async () => {
            saveCurrentPresetFromForm();
            const name = prompt("Enter a name for the new theme preset:");
            if (!name || !name.trim()) return;
            const cleaned = name.trim();
            if (themes.some(t => t.name.toLowerCase() === cleaned.toLowerCase())) {
                Obsidianscout.showToast("A preset with that name already exists.", "error");
                return;
            }
            // Clone currently selected theme
            const cloned = { ...themes[selectedPresetIndex], name: cleaned };
            themes.push(cloned);
            selectedPresetIndex = themes.length - 1;
            activeThemeName = cleaned;
            rebuildPresetDropdown();
            populateForm(themes[selectedPresetIndex]);
            updateVisualThemePreview();
            await saveAllThemes(true);
            Obsidianscout.showToast(`Preset "${cleaned}" created and set active.`, "success");
        });
    }

    const btnRename = document.getElementById("btn-rename-preset");
    if (btnRename) {
        btnRename.addEventListener("click", async () => {
            const current = themes[selectedPresetIndex];
            const name = prompt("Enter a new name for the selected theme preset:", current.name);
            if (!name || !name.trim()) return;
            const cleaned = name.trim();
            if (cleaned === current.name) return;
            if (themes.some(t => t.name.toLowerCase() === cleaned.toLowerCase())) {
                Obsidianscout.showToast("A preset with that name already exists.", "error");
                return;
            }
            const oldName = current.name;
            if (activeThemeName === oldName) {
                activeThemeName = cleaned;
            }
            current.name = cleaned;
            rebuildPresetDropdown();
            await saveAllThemes(true);
            Obsidianscout.showToast(`Preset renamed to "${cleaned}".`, "info");
        });
    }

    const btnDelete = document.getElementById("btn-delete-preset");
    if (btnDelete) {
        btnDelete.addEventListener("click", async () => {
            if (themes.length <= 1) {
                Obsidianscout.showToast("You must keep at least one theme preset.", "error");
                return;
            }
            const current = themes[selectedPresetIndex];
            if (!confirm(`Are you sure you want to delete the preset "${current.name}"?`)) return;

            themes.splice(selectedPresetIndex, 1);
            if (activeThemeName === current.name) {
                activeThemeName = themes[0].name;
            }
            selectedPresetIndex = 0;
            rebuildPresetDropdown();
            populateForm(themes[selectedPresetIndex]);
            updateVisualThemePreview();
            await saveAllThemes(true);
            Obsidianscout.showToast("Preset deleted.", "info");
        });
    }

    // Save All Logic (with Mutex Concurrency Queue)
    async function saveAllThemes(silent = false) {
        if (isSaving) {
            pendingSave = true;
            return;
        }
        isSaving = true;

        do {
            pendingSave = false;
            saveCurrentPresetFromForm();
            loadedSettings.themes = themes;
            loadedSettings.activeThemeName = activeThemeName;

            const activeTheme = themes.find(t => t.name === activeThemeName) || themes[0];
            loadedSettings.theme = activeTheme;

            try {
                await Obsidianscout.request("/api/settings", {
                    method: "PUT",
                    json: loadedSettings,
                    ...(silent ? {} : { button: document.getElementById("theme-save-all") })
                });
                
                if (activeTheme) {
                    Obsidianscout.safeSetItem("obsidian-custom-theme-config", JSON.stringify(activeTheme));
                    if (window.Obsidianscout && typeof Obsidianscout.initTheme === "function") {
                        Obsidianscout.initTheme();
                    }
                }
                
                if (!silent && !pendingSave) {
                    Obsidianscout.showToast("Theme configurations saved successfully. Reloading...", "success");
                    setTimeout(() => {
                        window.location.reload();
                    }, 1000);
                }

            } catch (error) {
                if (!silent) {
                    Obsidianscout.showToast(error.message || "Failed to save theme settings", "error");
                }
            }
        } while (pendingSave);

        isSaving = false;
    }

    // Bind save buttons
    const saveAllBtn = document.getElementById("theme-save-all");
    if (saveAllBtn) {
        saveAllBtn.addEventListener("click", () => saveAllThemes(false));
    }

    // Reset button
    const resetBtn = document.getElementById("theme-reset");
    if (resetBtn) {
        resetBtn.addEventListener("click", () => {
            document.getElementById("theme-light-accent").value = "#0b8f88";
            document.getElementById("theme-light-accent2").value = "#f28b35";
            document.getElementById("theme-light-accent3").value = "#255a9c";
            document.getElementById("theme-light-ink").value = "#1d1a17";
            document.getElementById("theme-light-muted").value = "#5f5b55";
            document.getElementById("theme-light-radius").value = "999px";
            parseAndPopulateBg("light", "");

            document.getElementById("theme-dark-accent").value = "#3ccfc0";
            document.getElementById("theme-dark-accent2").value = "#f2a353";
            document.getElementById("theme-dark-accent3").value = "#6aa2ff";
            document.getElementById("theme-dark-ink").value = "#f4f2ed";
            document.getElementById("theme-dark-muted").value = "#c3bfb8";
            document.getElementById("theme-dark-radius").value = "999px";
            parseAndPopulateBg("dark", "");
            
            updateVisualThemePreview();
            Obsidianscout.showToast("Selected preset values reset in form. Click Save to persist.", "info");
        });
    }

    // Initialize Background editor tab handlers
    setupBgEventListeners("light");
    setupBgEventListeners("dark");
});
