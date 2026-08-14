function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    // DOM references declared in outer scope so they can be re-bound and accessed by helper functions
    let editor, saveButton, exportButton, importInput;
    let btnVisual, btnRaw, containerVisual, containerRaw;
    let configTitleInput, configVersionInput, btnAddField, visualFieldsList, configModeButtons;
    let adminPanelWrapper = null;
    let adminPanel = null;
    let originalAdminPanelHTML = "";
    let loadedSettings = null;

    const isUserAdmin = Obsidianscout.isAdmin(me.role);
    const isUserSuperAdmin = Obsidianscout.isSuperAdmin(me.role);
    if (!isUserAdmin) {
        document.getElementById("admin-locked").classList.remove("hidden");
        document.getElementById("admin-panel").classList.add("hidden");
        return;
    }

    const configModes = {
        game: {
            apiPath: "/api/config",
            defaultTitle: "ObsidianScout",
            exportName: "scouting-config.json"
        },
        pit: {
            apiPath: "/api/pit-config",
            defaultTitle: "ObsidianScout Pit Scouting",
            exportName: "pit-scouting-config.json"
        },
        qual: {
            apiPath: "/api/qual-config",
            defaultTitle: "ObsidianScout Qualitative Scouting",
            exportName: "qualitative-scouting-config.json"
        }
    };

    // Local configuration state
    let activeConfigKind = "game";
    let currentConfig = { version: 1, title: "ObsidianScout", fields: [], analytics: [] };

    function supportsPointsConfig() {
        return activeConfigKind !== "qual";
    }

    const configurablePages = [
        { id: "dashboard", label: "Dashboard" },
        { id: "scout", label: "Scout" },
        { id: "pit-scout", label: "Pit Scout" },
        { id: "qual-scout", label: "Qual Scout" },
        { id: "qr-scanner", label: "QR Scanner" },
        { id: "all-data", label: "All Data" },
        { id: "qual-data", label: "Qual Data" },
        { id: "pit-data", label: "Pit Data" },
        { id: "analytics", label: "Analytics" },
        { id: "graphs", label: "Graphs" },
        { id: "teams", label: "Teams" },
        { id: "rankings", label: "Rankings" },
        { id: "qual-rankings", label: "Qual Rankings" },
        { id: "matches", label: "Matches" },
        { id: "predictor", label: "Predictor" },
        { id: "event-predictor", label: "Event Predictor" },
        { id: "alliances", label: "Alliances" },
        { id: "alliance-selection", label: "Alliance Selection" },
        { id: "chat", label: "Chat" },
        { id: "backup", label: "Data Sharing" },
        { id: "docs", label: "Docs" },
        { id: "contact", label: "Contact" },
        { id: "admin-settings", label: "Admin Settings" },
        { id: "users", label: "Users" },
        { id: "banners", label: "Banners" }
    ];

    function renderPermissionsCheckboxes(scoutPages, analyticsPages, adminPages) {
        const scoutList = document.getElementById("scout-pages-list");
        const analyticsList = document.getElementById("analytics-pages-list");
        const adminList = document.getElementById("admin-pages-list");
        if (!scoutList || !analyticsList || !adminList) return;

        scoutList.innerHTML = "";
        analyticsList.innerHTML = "";
        adminList.innerHTML = "";

        configurablePages.forEach((page) => {
            // Determine checkbox states for Scout
            const isScoutDisabled = page.id === "dashboard" || ["admin-settings", "users", "banners"].includes(page.id);
            const isScoutChecked = page.id === "dashboard" ? true : scoutPages.includes(page.id) && !isScoutDisabled;

            // Scout Checkbox
            const scoutLabel = document.createElement("label");
            scoutLabel.style.display = "flex";
            scoutLabel.style.alignItems = "center";
            scoutLabel.style.gap = "8px";
            scoutLabel.style.cursor = isScoutDisabled ? "not-allowed" : "pointer";
            if (isScoutDisabled) scoutLabel.style.opacity = "0.6";

            const scoutInput = document.createElement("input");
            scoutInput.type = "checkbox";
            scoutInput.id = `scout-page-${page.id}`;
            scoutInput.checked = isScoutChecked;
            if (isScoutDisabled) scoutInput.disabled = true;
            scoutLabel.appendChild(scoutInput);
            scoutLabel.appendChild(document.createTextNode(page.label));
            scoutList.appendChild(scoutLabel);

            // Determine checkbox states for Analytics
            const isAnalyticsDisabled = page.id === "dashboard" || ["admin-settings", "users", "banners"].includes(page.id);
            const isAnalyticsChecked = page.id === "dashboard" ? true : analyticsPages.includes(page.id) && !isAnalyticsDisabled;

            // Analytics Checkbox
            const analyticsLabel = document.createElement("label");
            analyticsLabel.style.display = "flex";
            analyticsLabel.style.alignItems = "center";
            analyticsLabel.style.gap = "8px";
            analyticsLabel.style.cursor = isAnalyticsDisabled ? "not-allowed" : "pointer";
            if (isAnalyticsDisabled) analyticsLabel.style.opacity = "0.6";

            const analyticsInput = document.createElement("input");
            analyticsInput.type = "checkbox";
            analyticsInput.id = `analytics-page-${page.id}`;
            analyticsInput.checked = isAnalyticsChecked;
            if (isAnalyticsDisabled) analyticsInput.disabled = true;
            analyticsLabel.appendChild(analyticsInput);
            analyticsLabel.appendChild(document.createTextNode(page.label));
            analyticsList.appendChild(analyticsLabel);

            // Determine checkbox states for Admin
            const isAdminDisabled = page.id === "dashboard" || page.id === "admin-settings";
            const isAdminChecked = isAdminDisabled ? true : adminPages.includes(page.id);

            // Admin Checkbox
            const adminLabel = document.createElement("label");
            adminLabel.style.display = "flex";
            adminLabel.style.alignItems = "center";
            adminLabel.style.gap = "8px";
            adminLabel.style.cursor = isAdminDisabled ? "not-allowed" : "pointer";
            if (isAdminDisabled) adminLabel.style.opacity = "0.6";

            const adminInput = document.createElement("input");
            adminInput.type = "checkbox";
            adminInput.id = `admin-page-${page.id}`;
            adminInput.checked = isAdminChecked;
            if (isAdminDisabled) adminInput.disabled = true;
            adminLabel.appendChild(adminInput);
            adminLabel.appendChild(document.createTextNode(page.label));
            adminList.appendChild(adminLabel);
        });
    }

    adminPanel = document.getElementById("admin-panel");
    if (adminPanel) {
        const siblings = Array.from(adminPanel.children).filter(child => child.tagName !== "H1");
        adminPanelWrapper = document.createElement("div");
        adminPanelWrapper.id = "settings-wrapper";
        siblings.forEach(child => adminPanelWrapper.appendChild(child));
        adminPanel.appendChild(adminPanelWrapper);
        originalAdminPanelHTML = adminPanelWrapper.innerHTML;
        await loadSettingsPageData();
    }

    async function loadSettingsPageData() {
        if (!adminPanelWrapper) return;
        Obsidianscout.showLoadingSpinner(adminPanelWrapper, "Loading settings...");

        try {
            const mode = configModes[activeConfigKind];
            const [configResponse, settingsResponse, emailResponse, cloudflaredResponse] = await Promise.all([
                Obsidianscout.request(mode.apiPath + "?local=true"),
                Obsidianscout.request("/api/settings?local=true"),
                isUserSuperAdmin ? Obsidianscout.request("/api/admin/email-settings").catch(err => { console.warn("Failed to load email settings:", err); return null; }) : Promise.resolve(null),
                isUserSuperAdmin ? Obsidianscout.request("/api/admin/cloudflared").catch(err => { console.warn("Failed to load cloudflared settings:", err); return null; }) : Promise.resolve(null)
            ]);

            adminPanelWrapper.innerHTML = originalAdminPanelHTML;

            // Re-query elements
            editor = document.getElementById("config-editor");
            saveButton = document.getElementById("config-save");
            exportButton = document.getElementById("config-export");
            importInput = document.getElementById("config-import");

            btnVisual = document.getElementById("btn-visual-editor");
            btnRaw = document.getElementById("btn-raw-editor");
            containerVisual = document.getElementById("visual-editor-container");
            containerRaw = document.getElementById("raw-editor-container");
            configTitleInput = document.getElementById("config-title");
            configVersionInput = document.getElementById("config-version");
            btnAddField = document.getElementById("btn-add-field");
            visualFieldsList = document.getElementById("visual-fields-list");
            configModeButtons = document.querySelectorAll("[data-config-kind]");

            // Sub-tab switching logic
            if (btnVisual && btnRaw && containerVisual && containerRaw) {
                btnVisual.addEventListener("click", () => {
                    const text = editor.value.trim();
                    if (!isValidJson(text)) {
                        Obsidianscout.showToast("Raw JSON is invalid. Fix syntax errors before switching to Visual Editor.", "error");
                        return;
                    }
                    
                    try {
                        currentConfig = JSON.parse(text);
                        if (!currentConfig.fields) currentConfig.fields = [];
                        if (!currentConfig.analytics) currentConfig.analytics = [];
                        
                        configTitleInput.value = currentConfig.title || "";
                        configVersionInput.value = currentConfig.version || 1;
                        
                        renderVisualFields();
                        
                        btnRaw.classList.remove("active");
                        btnVisual.classList.add("active");
                        containerRaw.classList.add("hidden");
                        containerVisual.classList.remove("hidden");
                    } catch (err) {
                        Obsidianscout.showToast("Failed to parse config properties", "error");
                    }
                });
                
                btnRaw.addEventListener("click", () => {
                    updateRawFromVisual();
                    btnVisual.classList.remove("active");
                    btnRaw.classList.add("active");
                    containerVisual.classList.add("hidden");
                    containerRaw.classList.remove("hidden");
                });
            }

            if (configTitleInput) {
                configTitleInput.addEventListener("input", updateRawFromVisual);
            }
            if (configVersionInput) {
                configVersionInput.addEventListener("input", updateRawFromVisual);
            }
            const roleCheckbox = document.getElementById("config-enable-role-collection");
            if (roleCheckbox) {
                roleCheckbox.addEventListener("change", () => {
                    currentConfig.enableRobotRoleCollection = roleCheckbox.checked;
                    updateRawFromVisual();
                });
            }

            if (btnAddField) {
                btnAddField.addEventListener("click", addField);
            }

            configModeButtons.forEach((button) => {
                button.addEventListener("click", async () => {
                    const nextKind = button.dataset.configKind;
                    if (!nextKind || nextKind === activeConfigKind || !configModes[nextKind]) {
                        return;
                    }
                    activeConfigKind = nextKind;
                    updateConfigModeButtons();
                    await loadActiveConfig();
                });
            });

            wireTabs();

            // Populate configs
            currentConfig = normalizeConfig(configResponse, mode.defaultTitle);
            editor.value = JSON.stringify(currentConfig, null, 2);
            if (configTitleInput) {
                configTitleInput.value = currentConfig.title || "";
            }
            if (configVersionInput) {
                configVersionInput.value = currentConfig.version || 1;
            }
            renderVisualFields();
            showVisualEditor();
            await updateDefaultPresetsDropdown();

            // Helper functions to safely assign values/checked states
            const setVal = (id, val) => {
                const el = document.getElementById(id);
                if (el) el.value = val;
            };
            const setChecked = (id, val) => {
                const el = document.getElementById(id);
                if (el) el.checked = val;
            };
            const getVal = (id, fallback = "") => {
                const el = document.getElementById(id);
                return el ? el.value : fallback;
            };
            const getChecked = (id) => {
                const el = document.getElementById(id);
                return el ? el.checked : false;
            };

            // Populate settings
            loadedSettings = settingsResponse.settings;
            setVal("settings-year", loadedSettings.year);
            setVal("settings-event-code", loadedSettings.eventCode || "");
            setVal("settings-timezone", loadedSettings.timezone || "America/New_York");
            setVal("settings-source", loadedSettings.preferredSource || "tba");
            setVal("settings-statbotics-url", loadedSettings.statboticsBaseUrl || "https://api.statbotics.io");
            setChecked("settings-epa", loadedSettings.useStatboticsEpa);
            setChecked("settings-opr", loadedSettings.useTbaOpr);
            setChecked("settings-chat", loadedSettings.chatEnabled);

            const isFtc = me.program === "FTC";
            const yearNote = document.getElementById("settings-year-note");
            if (yearNote) {
                yearNote.style.display = isFtc ? "block" : "none";
            }

            const tbaCard = document.getElementById("settings-tba-card");
            const tbaHeading = document.getElementById("settings-tba-heading") || document.querySelector('h3[data-i18n="config.the_blue_alliance"]');
            const tbaLabel = document.getElementById("settings-tba-label");
            const tbaOption = document.querySelector('#settings-source option[value="tba"]');
            const tbaField = document.getElementById("settings-tba-field");
            const tbaNotice = document.getElementById("settings-tba-notice");
            const tbaTestBtnEl = document.getElementById("settings-tba-test");

            const firstHeading = document.getElementById("settings-first-heading") || document.querySelector('h3[data-i18n="config.first_api"]');
            const firstOption = document.querySelector('#settings-source option[value="first"]');

            if (isFtc) {
                if (tbaCard) tbaCard.style.display = "";
                if (tbaHeading) tbaHeading.textContent = "FTC Scout";
                if (tbaOption) tbaOption.textContent = "FTC Scout";
                if (tbaField) tbaField.style.display = "none";
                if (tbaNotice) tbaNotice.style.display = "block";
                if (tbaTestBtnEl) tbaTestBtnEl.textContent = "Test FTC Scout API";

                if (firstHeading) firstHeading.textContent = "FIRST FTC API";
                if (firstOption) firstOption.textContent = "FIRST FTC API";

                const epaCheckbox = document.getElementById("settings-epa");
                if (epaCheckbox && epaCheckbox.parentElement) {
                    epaCheckbox.parentElement.style.display = "none";
                }
                const oprCheckbox = document.getElementById("settings-opr");
                if (oprCheckbox && oprCheckbox.parentElement) {
                    const textNode = Array.from(oprCheckbox.parentElement.childNodes).find(n => n.nodeType === Node.TEXT_NODE);
                    if (textNode) {
                        textNode.textContent = " Use FTC Scout OPR";
                    }
                }
                const statboticsCard = document.getElementById("settings-statbotics-card");
                if (statboticsCard) {
                    statboticsCard.style.display = "none";
                }
            } else {
                if (tbaCard) tbaCard.style.display = "";
                if (tbaHeading) tbaHeading.textContent = "The Blue Alliance";
                if (tbaLabel) tbaLabel.textContent = "TBA key";
                if (tbaOption) tbaOption.textContent = "The Blue Alliance";
                if (tbaField) tbaField.style.display = "";
                if (tbaNotice) tbaNotice.style.display = "none";
                if (tbaTestBtnEl) tbaTestBtnEl.textContent = "Test Connection";

                if (firstHeading) firstHeading.textContent = "FIRST API";
                if (firstOption) firstOption.textContent = "FIRST API";

                const epaCheckbox = document.getElementById("settings-epa");
                if (epaCheckbox && epaCheckbox.parentElement) {
                    epaCheckbox.parentElement.style.display = "";
                }
                const oprCheckbox = document.getElementById("settings-opr");
                if (oprCheckbox && oprCheckbox.parentElement) {
                    const textNode = Array.from(oprCheckbox.parentElement.childNodes).find(n => n.nodeType === Node.TEXT_NODE);
                    if (textNode) {
                        textNode.textContent = " Use TBA OPR";
                    }
                }
                const statboticsCard = document.getElementById("settings-statbotics-card");
                if (statboticsCard) {
                    statboticsCard.style.display = "";
                }
            }

            if (loadedSettings.apiKeys) {
                setVal("settings-tba-key", loadedSettings.apiKeys.tbaKey || "");
                setVal("settings-first-user", loadedSettings.apiKeys.firstUsername || "");
                setVal("settings-first-key", loadedSettings.apiKeys.firstKey || "");
            }

            // Render permissions checkboxes
            renderPermissionsCheckboxes(
                loadedSettings.scoutPages || [],
                loadedSettings.analyticsPages || [],
                loadedSettings.adminPages || []
            );

            // Wire Setup Wizard manual trigger
            const btnRunWizard = document.getElementById("btn-run-setup-wizard");
            if (btnRunWizard) {
                btnRunWizard.addEventListener("click", () => {
                    Obsidianscout.showSetupWizardModal(me, loadedSettings, true);
                });
            }



            // Save configuration
            saveButton.addEventListener("click", async () => {
                let text = editor.value.trim();
                if (!isValidJson(text)) {
                    Obsidianscout.showToast("Config JSON is invalid", "error");
                    return;
                }

                try {
                    currentConfig = JSON.parse(text);
                    if (!currentConfig.fields) currentConfig.fields = [];
                    if (!currentConfig.analytics) currentConfig.analytics = [];
                    
                    if (configTitleInput) configTitleInput.value = currentConfig.title || "";
                    if (configVersionInput) configVersionInput.value = currentConfig.version || 1;
                    
                    renderVisualFields();
                } catch (err) {}

                try {
                    await Obsidianscout.request(configModes[activeConfigKind].apiPath, {
                        method: "PUT",
                        json: {
                            configJson: text
                        },
                        button: saveButton
                    });
                    Obsidianscout.showToast("Config saved", "success");
                } catch (error) {
                    Obsidianscout.showToast(error.message || "Save failed", "error");
                }
            });

            // Export configuration
            exportButton.addEventListener("click", () => {
                updateRawFromVisual();
                const blob = new Blob([editor.value], { type: "application/json" });
                const url = URL.createObjectURL(blob);
                const link = document.createElement("a");
                link.href = url;
                link.download = configModes[activeConfigKind].exportName;
                link.click();
                URL.revokeObjectURL(url);
            });

            // Import configuration
            importInput.addEventListener("change", () => {
                const file = importInput.files[0];
                if (!file) {
                    return;
                }
                const reader = new FileReader();
                reader.onload = () => {
                    const text = reader.result;
                    if (isValidJson(text)) {
                        editor.value = text;
                        try {
                            currentConfig = JSON.parse(text);
                            if (!currentConfig.fields) currentConfig.fields = [];
                            if (!currentConfig.analytics) currentConfig.analytics = [];
                            
                            if (configTitleInput) configTitleInput.value = currentConfig.title || "";
                            if (configVersionInput) configVersionInput.value = currentConfig.version || 1;
                            
                            renderVisualFields();
                            Obsidianscout.showToast("Config imported successfully", "success");
                        } catch (err) {
                            Obsidianscout.showToast("Imported JSON structure has errors", "error");
                        }
                    } else {
                        Obsidianscout.showToast("Invalid JSON file", "error");
                    }
                };
                reader.readAsText(file);
            });

            // Reset to Default Preset logic
            const btnApplyPreset = document.getElementById("btn-apply-preset");
            if (btnApplyPreset) {
                btnApplyPreset.addEventListener("click", async () => {
                    const selector = document.getElementById("preset-selector");
                    const presetName = selector ? selector.value : "";
                    const targetType = activeConfigKind === "game" ? "match" : (activeConfigKind === "qual" ? "qualitative" : "pit");

                    const label = presetName ? `preset '${presetName}'` : `active program default`;
                    if (!confirm(`Are you sure you want to reset the current ${activeConfigKind} config editor to ${label}? Any unsaved changes will be overwritten.`)) {
                        return;
                    }

                    try {
                        let updatedConfigObj;
                        if (presetName) {
                            updatedConfigObj = await Obsidianscout.request("/api/config/apply-default", {
                                method: "POST",
                                json: { configType: targetType, presetName: presetName },
                                button: btnApplyPreset
                            });
                        } else {
                            updatedConfigObj = await Obsidianscout.request("/api/config/reset", {
                                method: "POST",
                                json: { configType: targetType },
                                button: btnApplyPreset
                            });
                        }

                        currentConfig = normalizeConfig(updatedConfigObj, mode.defaultTitle);
                        editor.value = JSON.stringify(currentConfig, null, 2);
                        if (configTitleInput) configTitleInput.value = currentConfig.title || "";
                        if (configVersionInput) configVersionInput.value = currentConfig.version || 1;
                        renderVisualFields();

                        Obsidianscout.showToast(`Reset ${activeConfigKind} config editor to ${label} successfully!`, "success");
                    } catch (err) {
                        Obsidianscout.showToast(err.message || "Failed to reset config to default preset", "error");
                    }
                });
            }

            // API settings save
            const settingsSaveBtn = document.getElementById("settings-save");
            if (settingsSaveBtn) {
                settingsSaveBtn.addEventListener("click", async () => {
                    loadedSettings.year = Number(getVal("settings-year") || new Date().getFullYear());
                    loadedSettings.eventCode = getVal("settings-event-code").trim();
                    loadedSettings.timezone = getVal("settings-timezone").trim();
                    loadedSettings.preferredSource = getVal("settings-source");
                    loadedSettings.useStatboticsEpa = getChecked("settings-epa");
                    loadedSettings.useTbaOpr = getChecked("settings-opr");
                    loadedSettings.chatEnabled = getChecked("settings-chat");
                    loadedSettings.statboticsBaseUrl = getVal("settings-statbotics-url").trim() || "https://api.statbotics.io";
                    loadedSettings.apiKeys = {
                        tbaKey: getVal("settings-tba-key").trim(),
                        firstUsername: getVal("settings-first-user").trim(),
                        firstKey: getVal("settings-first-key").trim()
                    };

                    try {
                        const response = await Obsidianscout.request("/api/settings", {
                            method: "PUT",
                            json: loadedSettings,
                            button: settingsSaveBtn
                        });
                        loadedSettings = response.settings;
                        Obsidianscout.showToast("API settings saved", "success");
                    } catch (error) {
                        Obsidianscout.showToast(error.message || "Save failed", "error");
                    }
                });
            }

            // Wire API key test buttons
            const tbaTestBtn = document.getElementById("settings-tba-test");
            if (tbaTestBtn) {
                tbaTestBtn.addEventListener("click", async () => {
                    const isFtc = me.program === "FTC";
                    const apiTarget = isFtc ? "ftcscout" : "tba";
                    const label = isFtc ? "FTC Scout API" : "TBA Key";
                    Obsidianscout.setButtonLoading(tbaTestBtn, true, "Testing...");
                    try {
                        const res = await Obsidianscout.request("/api/settings/test-api", {
                            method: "POST",
                            json: {
                                api: apiTarget,
                                tbaKey: getVal("settings-tba-key").trim()
                            }
                        });
                        if (res && res.success) {
                            Obsidianscout.showToast(res.message || `${label} tested successfully!`, "success");
                        } else {
                            Obsidianscout.showToast((res && res.message) || `${label} test failed.`, "error");
                        }
                    } catch (err) {
                        Obsidianscout.showToast(err.message || `${label} test failed.`, "error");
                    } finally {
                        Obsidianscout.setButtonLoading(tbaTestBtn, false);
                    }
                });
            }

            const firstTestBtn = document.getElementById("settings-first-test");
            if (firstTestBtn) {
                firstTestBtn.addEventListener("click", async () => {
                    Obsidianscout.setButtonLoading(firstTestBtn, true, "Testing...");
                    try {
                        const res = await Obsidianscout.request("/api/settings/test-api", {
                            method: "POST",
                            json: {
                                api: "first",
                                firstUsername: getVal("settings-first-user").trim(),
                                firstKey: getVal("settings-first-key").trim()
                            }
                        });
                        if (res && res.success) {
                            Obsidianscout.showToast(res.message || "FIRST API credentials are valid!", "success");
                        } else {
                            Obsidianscout.showToast((res && res.message) || "FIRST API test failed.", "error");
                        }
                    } catch (err) {
                        Obsidianscout.showToast(err.message || "FIRST API test failed.", "error");
                    } finally {
                        Obsidianscout.setButtonLoading(firstTestBtn, false);
                    }
                });
            }

            const statboticsTestBtn = document.getElementById("settings-statbotics-test");
            if (statboticsTestBtn) {
                statboticsTestBtn.addEventListener("click", async () => {
                    Obsidianscout.setButtonLoading(statboticsTestBtn, true, "Testing...");
                    try {
                        const res = await Obsidianscout.request("/api/settings/test-api", {
                            method: "POST",
                            json: {
                                api: "statbotics",
                                statboticsBaseUrl: getVal("settings-statbotics-url").trim()
                            }
                        });
                        if (res && res.success) {
                            Obsidianscout.showToast(res.message || "Statbotics API connection successful!", "success");
                        } else {
                            Obsidianscout.showToast((res && res.message) || "Statbotics API test failed.", "error");
                        }
                    } catch (err) {
                        Obsidianscout.showToast(err.message || "Statbotics API test failed.", "error");
                    } finally {
                        Obsidianscout.setButtonLoading(statboticsTestBtn, false);
                    }
                });
            }

            // Permissions save
            const permissionsSaveBtn = document.getElementById("permissions-save");
            if (permissionsSaveBtn) {
                permissionsSaveBtn.addEventListener("click", async () => {
                    const scoutPages = [];
                    const analyticsPages = [];
                    const adminPages = [];
                    
                    configurablePages.forEach((page) => {
                        const scoutEl = document.getElementById(`scout-page-${page.id}`);
                        const analyticsEl = document.getElementById(`analytics-page-${page.id}`);
                        const adminEl = document.getElementById(`admin-page-${page.id}`);
                        if (scoutEl && scoutEl.checked) {
                            scoutPages.push(page.id);
                        }
                        if (analyticsEl && analyticsEl.checked) {
                            analyticsPages.push(page.id);
                        }
                        if (adminEl && adminEl.checked) {
                            adminPages.push(page.id);
                        }
                    });
                    
                    loadedSettings.scoutPages = scoutPages;
                    loadedSettings.analyticsPages = analyticsPages;
                    loadedSettings.adminPages = adminPages;
                    loadedSettings.chatEnabled = getChecked("settings-chat");

                    try {
                        const response = await Obsidianscout.request("/api/settings", {
                            method: "PUT",
                            json: loadedSettings,
                            button: permissionsSaveBtn
                        });
                        loadedSettings = response.settings;
                        Obsidianscout.showToast("Permissions saved successfully", "success");
                    } catch (error) {
                        Obsidianscout.showToast(error.message || "Failed to save permissions", "error");
                    }
                });
            }

            // Populate email settings if superadmin
            if (isUserSuperAdmin && emailResponse) {
                const tabEmail = document.getElementById("tab-email");
                if (tabEmail) tabEmail.classList.remove("hidden");

                setVal("settings-email-host", emailResponse.host || "");
                setVal("settings-email-port", emailResponse.port || 587);
                setVal("settings-email-username", emailResponse.username || "");
                setVal("settings-email-password", emailResponse.passwordPlain || "");
                setVal("settings-email-from", emailResponse.fromAddress || "");
                setVal("settings-email-encryption", emailResponse.encryption || "STARTTLS");

                // Save SMTP settings listener
                const emailSaveBtn = document.getElementById("settings-email-save");
                if (emailSaveBtn) {
                    emailSaveBtn.addEventListener("click", async () => {
                        const payload = {
                            host: getVal("settings-email-host").trim(),
                            port: parseInt(getVal("settings-email-port"), 10) || 587,
                            username: getVal("settings-email-username").trim(),
                            passwordPlain: getVal("settings-email-password").trim(),
                            fromAddress: getVal("settings-email-from").trim(),
                            encryption: getVal("settings-email-encryption")
                        };
                        try {
                            await Obsidianscout.request("/api/admin/email-settings", {
                                method: "PUT",
                                json: payload,
                                button: emailSaveBtn
                            });
                            Obsidianscout.showToast("Email settings saved", "success");
                        } catch (err) {
                            Obsidianscout.showToast(err.message || "Failed to save email settings", "error");
                        }
                    });
                }

                // Test SMTP settings listener
                const emailTestBtn = document.getElementById("settings-email-test");
                if (emailTestBtn) {
                    emailTestBtn.addEventListener("click", async () => {
                        const testEmail = getVal("settings-email-test-address").trim();
                        if (!testEmail) {
                            Obsidianscout.showToast("Please enter a recipient email address", "error");
                            return;
                        }
                        Obsidianscout.showToast("Sending test email...", "info");
                        try {
                            await Obsidianscout.request("/api/admin/email-settings/test", {
                                method: "POST",
                                json: {
                                    host: getVal("settings-email-host").trim(),
                                    port: parseInt(getVal("settings-email-port"), 10) || 587,
                                    username: getVal("settings-email-username").trim(),
                                    passwordPlain: getVal("settings-email-password").trim(),
                                    fromAddress: getVal("settings-email-from").trim(),
                                    encryption: getVal("settings-email-encryption"),
                                    testEmail: testEmail
                                },
                                button: emailTestBtn
                            });
                            Obsidianscout.showToast("Test email sent successfully", "success");
                        } catch (err) {
                            Obsidianscout.showToast(err.message || "Failed to send test email", "error");
                        }
                    });
                }
            }

            // Populate Cloudflare Tunnel settings if superadmin
            if (isUserSuperAdmin && cloudflaredResponse) {
                const tabCloudflared = document.getElementById("tab-cloudflared");
                if (tabCloudflared) tabCloudflared.classList.remove("hidden");

                const cfSettings = cloudflaredResponse.settings || {};
                const cfStatus = cloudflaredResponse.status || {};

                const enabledCb = document.getElementById("settings-cloudflared-enabled");
                if (enabledCb) enabledCb.checked = !!cfSettings.enabled;

                setVal("settings-cloudflared-tunnel-id", cfSettings.tunnelId || "");
                setVal("settings-cloudflared-tunnel-token", cfSettings.tunnelToken || "");
                setVal("settings-cloudflared-target-url", cfSettings.targetUrl || "http://localhost:8080");

                const updateStatusUI = (status) => {
                    const statusText = document.getElementById("cloudflared-status-text");
                    if (statusText) {
                        statusText.textContent = status.statusMessage || "Unknown";
                        if (status.isRunning) {
                            statusText.style.color = "#10b981"; // green
                        } else if (status.enabled) {
                            statusText.style.color = "#f59e0b"; // yellow/orange
                        } else {
                            statusText.style.color = "var(--muted)";
                        }
                    }
                };

                updateStatusUI(cfStatus);

                // Save Cloudflared settings listener
                const cfSaveBtn = document.getElementById("settings-cloudflared-save");
                if (cfSaveBtn) {
                    cfSaveBtn.addEventListener("click", async () => {
                        const payload = {
                            enabled: !!(document.getElementById("settings-cloudflared-enabled")?.checked),
                            tunnelId: getVal("settings-cloudflared-tunnel-id").trim(),
                            tunnelToken: getVal("settings-cloudflared-tunnel-token").trim(),
                            targetUrl: getVal("settings-cloudflared-target-url").trim() || "http://localhost:8080"
                        };
                        try {
                            const res = await Obsidianscout.request("/api/admin/cloudflared", {
                                method: "PUT",
                                json: payload,
                                button: cfSaveBtn
                            });
                            updateStatusUI(res.status || {});
                            Obsidianscout.showToast("Cloudflare Tunnel settings saved", "success");
                        } catch (err) {
                            Obsidianscout.showToast(err.message || "Failed to save Cloudflare Tunnel settings", "error");
                        }
                    });
                }

                // Restart Cloudflared tunnel listener
                const cfRestartBtn = document.getElementById("cloudflared-restart-btn");
                if (cfRestartBtn) {
                    cfRestartBtn.addEventListener("click", async () => {
                        Obsidianscout.showToast("Restarting Cloudflare Tunnel...", "info");
                        try {
                            const res = await Obsidianscout.request("/api/admin/cloudflared/restart", {
                                method: "POST",
                                button: cfRestartBtn
                            });
                            updateStatusUI(res.status || {});
                            Obsidianscout.showToast("Cloudflare Tunnel restarted", "success");
                        } catch (err) {
                            Obsidianscout.showToast(err.message || "Failed to restart Cloudflare Tunnel", "error");
                        }
                    });
                }
            }

            // Wipe Team Scouting Data logic
            const btnWipeTeamData = document.getElementById("btn-wipe-team-data");
            const wipePasswordConfirm = document.getElementById("wipe-password-confirm");
            if (btnWipeTeamData && wipePasswordConfirm) {
                btnWipeTeamData.addEventListener("click", async () => {
                    const password = wipePasswordConfirm.value;
                    if (!password) {
                        Obsidianscout.showToast("Please enter your password to confirm data wipe.", "error");
                        return;
                    }

                    if (!confirm("ARE YOU ABSOLUTELY SURE? This will permanently delete all of your team's match, pit, and qualitative scouting entries, as well as synced events, teams, and matches!")) {
                        return;
                    }

                    Obsidianscout.setButtonLoading(btnWipeTeamData, true, "Wiping Data...");
                    try {
                        const res = await Obsidianscout.request("/api/admin/wipe-team-data", {
                            method: "POST",
                            json: { password: password }
                        });

                        if (res.success) {
                            Obsidianscout.showToast("Team scouting data wiped successfully!", "success");
                            wipePasswordConfirm.value = "";
                            setTimeout(() => {
                                window.location.reload();
                            }, 1000);
                        } else {
                            Obsidianscout.showToast("Failed to wipe team data: " + (res.error || "Unknown error"), "error");
                            Obsidianscout.setButtonLoading(btnWipeTeamData, false);
                        }
                    } catch (e) {
                        Obsidianscout.showToast("Error wiping team data: " + e.message, "error");
                        Obsidianscout.setButtonLoading(btnWipeTeamData, false);
                    }
                });
            }

            // Cluster Security Keys card (Site Admin / SuperAdmin only)
            const clusterKeysCard = document.getElementById("cluster-keys-card");
            if (clusterKeysCard) {
                if (isUserSuperAdmin) {
                    clusterKeysCard.style.display = "block";
                    const btnRegen = document.getElementById("btn-regenerate-cluster-keys");
                    if (btnRegen) {
                        btnRegen.addEventListener("click", async () => {
                            if (!confirm("Are you sure you want to regenerate all cluster keys (Session Secret & VAPID keys)?\n\nRotating session keys will require active users across all nodes to sign in again.")) {
                                return;
                            }
                            Obsidianscout.setButtonLoading(btnRegen, true, "Regenerating...");
                            try {
                                const resp = await Obsidianscout.request("/api/admin/cluster/regenerate-keys", {
                                    method: "POST"
                                });
                                if (resp && resp.success) {
                                    Obsidianscout.showToast(resp.message || "Cluster keys successfully regenerated!", "success");
                                } else {
                                    Obsidianscout.showToast("Failed to regenerate cluster keys: " + ((resp && (resp.error || resp.message)) || "Unknown error"), "error");
                                }
                            } catch (err) {
                                Obsidianscout.showToast("Error regenerating cluster keys: " + err.message, "error");
                            } finally {
                                Obsidianscout.setButtonLoading(btnRegen, false);
                            }
                        });
                    }
                } else {
                    clusterKeysCard.style.display = "none";
                }
            }

        } catch (error) {

            console.error("Failed to load settings data:", error);
            Obsidianscout.showRetryButton(adminPanelWrapper, "Failed to load settings: " + error.message, loadSettingsPageData);
        }
    }

    async function loadActiveConfig() {
        const mode = configModes[activeConfigKind];
        try {
            const config = await Obsidianscout.request(mode.apiPath + "?local=true");
            currentConfig = normalizeConfig(config, mode.defaultTitle);
            editor.value = JSON.stringify(currentConfig, null, 2);
            if (configTitleInput) {
                configTitleInput.value = currentConfig.title || "";
            }
            if (configVersionInput) {
                configVersionInput.value = currentConfig.version || 1;
            }

            // Qualitative settings card visibility & checkbox state
            const cardQual = document.getElementById("qualitative-settings-card");
            if (cardQual) {
                if (activeConfigKind === "qual") {
                    cardQual.classList.remove("hidden");
                    const roleCheckbox = document.getElementById("config-enable-role-collection");
                    if (roleCheckbox) {
                        roleCheckbox.checked = !!currentConfig.enableRobotRoleCollection;
                    }
                } else {
                    cardQual.classList.add("hidden");
                }
            }

            renderVisualFields();
            showVisualEditor();
            await updateDefaultPresetsDropdown();
        } catch (error) {
            Obsidianscout.showToast("Unable to load config", "error");
        }
    }

    async function updateDefaultPresetsDropdown() {
        const selector = document.getElementById("preset-selector");
        if (!selector) return;

        const targetType = activeConfigKind === "game" ? "match" : (activeConfigKind === "qual" ? "qualitative" : "pit");

        try {
            const presets = await Obsidianscout.request(`/api/config/defaults?type=${targetType}`);
            selector.innerHTML = `<option value="">-- Reset to Active Default --</option>`;
            if (presets && Array.isArray(presets)) {
                presets.forEach(p => {
                    const opt = document.createElement("option");
                    opt.value = p.name;
                    opt.textContent = `${p.name}${p.isDefault ? ' (Active Default)' : ''}`;
                    if (p.isDefault) opt.selected = true;
                    selector.appendChild(opt);
                });
            }
        } catch (err) {
            console.warn("Failed to load default presets list for admin editor:", err);
        }
    }

    function normalizeConfig(config, defaultTitle) {
        const parsed = typeof config === "string" ? JSON.parse(config) : (config || {});
        const reserved = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);
        const fields = (Array.isArray(parsed.fields) ? parsed.fields : [])
            .filter((field) => field && !reserved.has(field.id));
        return {
            title: parsed.title || defaultTitle,
            version: Number(parsed.version) || 1,
            fields: fields,
            analytics: Array.isArray(parsed.analytics) ? parsed.analytics : [],
            enableRobotRoleCollection: !!parsed.enableRobotRoleCollection
        };
    }

    function updateConfigModeButtons() {
        configModeButtons.forEach((button) => {
            button.classList.toggle("active", button.dataset.configKind === activeConfigKind);
        });
    }

    function showVisualEditor() {
        if (!btnVisual || !btnRaw || !containerVisual || !containerRaw) {
            return;
        }
        btnRaw.classList.remove("active");
        btnVisual.classList.add("active");
        containerRaw.classList.add("hidden");
        containerVisual.classList.remove("hidden");
    }

    function renderVisualFields() {
        if (!visualFieldsList) return;
        visualFieldsList.classList.remove("view-only-editor");
        visualFieldsList.innerHTML = "";

        const fields = currentConfig.fields || [];
        if (fields.length === 0) {
            const emptyNotice = document.createElement("div");
            emptyNotice.className = "notice";
            emptyNotice.style.textAlign = "center";
            emptyNotice.style.padding = "24px";
            emptyNotice.textContent = t('settings.no_fields_configured', "No fields configured. Click '+ Add Field' to start building!");
            visualFieldsList.appendChild(emptyNotice);
            return;
        }

        const hasManualSections = fields.some((field) => field.type === "section");
        if (hasManualSections) {
            fields.forEach((field, index) => {
                const cardNode = createFieldCard(field, index);
                visualFieldsList.appendChild(cardNode);
            });
            return;
        }

        const groups = [
            { key: "auto", title: "Auto" },
            { key: "teleop", title: "Teleop" },
            { key: "endgame", title: "Endgame" },
            { key: "", title: "General" }
        ];

        groups.forEach((group) => {
            const groupFields = fields.filter((field) => {
                const phase = resolveFieldPhase(field);
                if (!group.key) {
                    return !phase;
                }
                return phase === group.key;
            });
            if (!groupFields.length) {
                return;
            }

            const header = document.createElement("div");
            header.className = "form-section";
            const headerTitle = document.createElement("h3");
            headerTitle.textContent = group.title;
            header.appendChild(headerTitle);
            visualFieldsList.appendChild(header);

            groupFields.forEach((field) => {
                const fieldIndex = fields.indexOf(field);
                const cardNode = createFieldCard(field, fieldIndex);
                visualFieldsList.appendChild(cardNode);
            });
        });
    }

    function createFieldCard(field, index) {
        const card = document.createElement("div");
        card.className = "field-card";
        
        // Header
        const header = document.createElement("div");
        header.className = "field-card-header";
        
        const title = document.createElement("h4");
        title.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? (Obsidianscout.localize(field.label) || `Field ${index + 1}`) : (field.label || `Field ${index + 1}`);
        
        const controls = document.createElement("div");
        controls.className = "field-card-controls";
        
        const typeBadge = document.createElement("span");
        typeBadge.className = "type-badge";
        typeBadge.textContent = field.type || "text";
        controls.appendChild(typeBadge);
        
        // Move Up
        const btnUp = document.createElement("button");
        btnUp.type = "button";
        btnUp.className = "btn-control-icon";
        btnUp.innerHTML = "▲";
        btnUp.title = "Move Up";
        btnUp.disabled = index === 0;
        btnUp.addEventListener("click", () => moveField(index, -1));
        controls.appendChild(btnUp);
        
        // Move Down
        const btnDown = document.createElement("button");
        btnDown.type = "button";
        btnDown.className = "btn-control-icon";
        btnDown.innerHTML = "▼";
        btnDown.title = "Move Down";
        btnDown.disabled = index === currentConfig.fields.length - 1;
        btnDown.addEventListener("click", () => moveField(index, 1));
        controls.appendChild(btnDown);
        
        // Delete
        const btnDel = document.createElement("button");
        btnDel.type = "button";
        btnDel.className = "btn-control-icon delete";
        btnDel.innerHTML = "🗑️";
        btnDel.title = "Delete Field";
        btnDel.addEventListener("click", () => deleteField(index));
        controls.appendChild(btnDel);
        
        header.appendChild(title);
        header.appendChild(controls);
        card.appendChild(header);
        
        // Card Body
        const body = document.createElement("div");
        body.className = "field-card-body";
        
        // 1. Label Input
        const divLabel = document.createElement("div");
        divLabel.className = "field";
        const labelTag = document.createElement("label");
        labelTag.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.field_label','Field Label') : 'Field Label';
        const inputLabel = document.createElement("input");
        inputLabel.type = "text";
        inputLabel.value = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : (field.label || "");
        inputLabel.placeholder = "e.g. Teleop Cycles";
        inputLabel.addEventListener("input", (e) => {
            const lang = Obsidianscout.safeGetItem('obsidianscout:lang') || 'en';
            const val = e.target.value;
            if (field && typeof field.label === 'object' && field.label !== null) {
                field.label[lang] = val;
            } else {
                field.label = val;
            }
            title.textContent = val || `Field ${index + 1}`;
            
            const inputId = divId.querySelector("input");
            if (inputId && shouldAutoUpdateId(field, inputId.value)) {
                const base = slugify(e.target.value);
                const autoId = ensureUniqueSlug(base, collectFieldIds(index, field));
                field.id = autoId;
                field._autoId = autoId;
                inputId.value = autoId;
            }
            updateRawFromVisual();
        });
        divLabel.appendChild(labelTag);
        divLabel.appendChild(inputLabel);
        body.appendChild(divLabel);
        
        // 2. ID Input
        const divId = document.createElement("div");
        divId.className = "field";
        const labelId = document.createElement("label");
        labelId.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.field_id','Field ID / Slug') : 'Field ID / Slug';
        const inputId = document.createElement("input");
        inputId.type = "text";
        inputId.value = field.id || "";
        inputId.placeholder = "e.g. teleopCycles";
        inputId.addEventListener("input", (e) => {
            field.id = e.target.value;
            field._autoId = null;
            updateRawFromVisual();
        });
        divId.appendChild(labelId);
        divId.appendChild(inputId);
        body.appendChild(divId);
        
        // 3. Type Select
        const divType = document.createElement("div");
        divType.className = "field";
        const labelType = document.createElement("label");
        labelType.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.type','Type') : 'Type';
        const selectType = document.createElement("select");
        const types = ["text", "textarea", "number", "counter", "rating", "checkbox", "select", "section"];
        types.forEach((t) => {
            const opt = document.createElement("option");
            opt.value = t;
            opt.textContent = t.toUpperCase();
            opt.selected = field.type === t;
            selectType.appendChild(opt);
        });
        selectType.addEventListener("change", (e) => {
            field.type = e.target.value;
            
            if (field.type === "select" && !field.options) {
                field.options = [];
            }
            if ((field.type === "number" || field.type === "counter" || field.type === "rating") && field.min === undefined) {
                field.min = field.type === "rating" ? 1 : 0;
                field.max = field.type === "rating" ? 5 : 10;
                field.step = 1;
            }
            updateRawFromVisual();
            renderVisualFields();
        });
        divType.appendChild(labelType);
        divType.appendChild(selectType);
        body.appendChild(divType);

        // 4. Phase Select (auto/teleop/endgame)
        const divPhase = document.createElement("div");
        divPhase.className = "field";
        const labelPhase = document.createElement("label");
        labelPhase.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.phase','Phase') : 'Phase';
        const selectPhase = document.createElement("select");
        const phaseOptions = [
            { value: "", label: (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('phase.general','General') : 'General' },
            { value: "auto", label: (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('phase.auto','Auto') : 'Auto' },
            { value: "teleop", label: (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('phase.teleop','Teleop') : 'Teleop' },
            { value: "endgame", label: (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('phase.endgame','Endgame') : 'Endgame' }
        ];
        phaseOptions.forEach((phase) => {
            const option = document.createElement("option");
            option.value = phase.value;
            option.textContent = phase.label;
            selectPhase.appendChild(option);
        });
        selectPhase.value = field.phase || resolveFieldPhase(field) || "";
        selectPhase.addEventListener("change", (e) => {
            field.phase = e.target.value || "";
            updateRawFromVisual();
        });
        divPhase.appendChild(labelPhase);
        divPhase.appendChild(selectPhase);
        body.appendChild(divPhase);
        
        // 5. Required Checkbox
        const divReq = document.createElement("div");
        divReq.className = "field";
        const labelReq = document.createElement("label");
        labelReq.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.required','Required') : 'Required';
        const labelWrap = document.createElement("label");
        labelWrap.style.display = "flex";
        labelWrap.style.alignItems = "center";
        labelWrap.style.gap = "8px";
        labelWrap.style.cursor = "pointer";
        labelWrap.style.marginTop = "8px";
        const inputReq = document.createElement("input");
        inputReq.type = "checkbox";
        inputReq.checked = !!field.required;
        inputReq.addEventListener("change", (e) => {
            field.required = e.target.checked;
            updateRawFromVisual();
        });
        labelWrap.appendChild(inputReq);
        labelWrap.appendChild(document.createTextNode((window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.is_required','Is Required') : ' Is Required'));
        divReq.appendChild(labelReq);
        divReq.appendChild(labelWrap);
        body.appendChild(divReq);
        
        // Adjust for section type
        if (field.type === "section") {
            divId.style.display = "none";
            divPhase.style.display = "none";
            divReq.style.display = "none";
        }
        
        // 5. Numeric Bounds
        if (field.type === "number" || field.type === "counter" || field.type === "rating") {
            const boundsDiv = document.createElement("div");
            boundsDiv.className = "field-card-body-full grid gap-12 mt-6";
            boundsDiv.style.gridTemplateColumns = "repeat(auto-fit, minmax(100px, 1fr))";
            
            // Min
            const divMin = document.createElement("div");
            divMin.className = "field";
            const labelMin = document.createElement("label");
            labelMin.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.min','Min') : 'Min';
            const inputMin = document.createElement("input");
            inputMin.type = "number";
            inputMin.value = field.min !== undefined && field.min !== null ? field.min : "";
            inputMin.addEventListener("input", (e) => {
                field.min = e.target.value !== "" ? Number(e.target.value) : null;
                updateRawFromVisual();
            });
            divMin.appendChild(labelMin);
            divMin.appendChild(inputMin);
            boundsDiv.appendChild(divMin);
            
            // Max
            const divMax = document.createElement("div");
            divMax.className = "field";
            const labelMax = document.createElement("label");
            labelMax.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.max','Max') : 'Max';
            const inputMax = document.createElement("input");
            inputMax.type = "number";
            inputMax.value = field.max !== undefined && field.max !== null ? field.max : "";
            const counterHasNoLimit = field.type === "counter" && (field.max === undefined || field.max === null || field.max === "");
            let inputNoLimit = null;
            inputMax.disabled = counterHasNoLimit;
            inputMax.addEventListener("input", (e) => {
                field.max = e.target.value !== "" ? Number(e.target.value) : null;
                if (inputNoLimit && e.target.value !== "") {
                    inputNoLimit.checked = false;
                    inputMax.disabled = false;
                }
                updateRawFromVisual();
            });
            divMax.appendChild(labelMax);
            divMax.appendChild(inputMax);
            if (field.type === "counter") {
                const noLimitWrap = document.createElement("label");
                noLimitWrap.style.display = "flex";
                noLimitWrap.style.alignItems = "center";
                noLimitWrap.style.gap = "8px";
                noLimitWrap.style.cursor = "pointer";
                noLimitWrap.style.marginTop = "8px";
                inputNoLimit = document.createElement("input");
                inputNoLimit.type = "checkbox";
                inputNoLimit.checked = counterHasNoLimit;
                inputNoLimit.addEventListener("change", (e) => {
                    if (e.target.checked) {
                        field.max = null;
                        inputMax.value = "";
                        inputMax.disabled = true;
                    } else {
                        field.max = 10;
                        inputMax.value = "10";
                        inputMax.disabled = false;
                    }
                    updateRawFromVisual();
                });
                noLimitWrap.appendChild(inputNoLimit);
                noLimitWrap.appendChild(document.createTextNode((window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.no_limit','No limit') : 'No limit'));
                divMax.appendChild(noLimitWrap);
            }
            boundsDiv.appendChild(divMax);
            
            // Step
            const divStep = document.createElement("div");
            divStep.className = "field";
            const labelStep = document.createElement("label");
            labelStep.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.step','Step') : 'Step';
            const inputStep = document.createElement("input");
            inputStep.type = "number";
            inputStep.value = field.step !== undefined && field.step !== null ? field.step : "";
            inputStep.addEventListener("input", (e) => {
                field.step = e.target.value !== "" ? Number(e.target.value) : null;
                updateRawFromVisual();
            });
            divStep.appendChild(labelStep);
            divStep.appendChild(inputStep);
            boundsDiv.appendChild(divStep);
            
            body.appendChild(boundsDiv);
        }
        
        // 6. Scoring Points
        if (supportsPointsConfig() && (field.type === "number" || field.type === "counter" || field.type === "rating" || field.type === "checkbox")) {
            const divPoints = document.createElement("div");
            divPoints.className = "field";
            const labelPoints = document.createElement("label");
            labelPoints.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.points_per','Points per action') : 'Points per action';
            const inputPoints = document.createElement("input");
            inputPoints.type = "number";
            inputPoints.step = "any";
            inputPoints.placeholder = "e.g. 3.0";
            inputPoints.value = field.pointsPer !== undefined && field.pointsPer !== null ? field.pointsPer : "";
            inputPoints.addEventListener("input", (e) => {
                field.pointsPer = e.target.value !== "" ? Number(e.target.value) : null;
                updateRawFromVisual();
            });
            divPoints.appendChild(labelPoints);
            divPoints.appendChild(inputPoints);
            body.appendChild(divPoints);
        }
        
        // 7. Select options list builder
        if (field.type === "select") {
            const optBuilder = document.createElement("div");
            optBuilder.className = "field-card-body-full options-builder";
            
            const optHeader = document.createElement("div");
            optHeader.className = "options-builder-header";
            
            const optTitle = document.createElement("div");
            optTitle.className = "options-builder-title";
            optTitle.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.options_title', supportsPointsConfig() ? 'Options (Label | Value | Points)' : 'Options (Label | Value)') : (supportsPointsConfig() ? 'Options (Label | Value | Points)' : 'Options (Label | Value)');
            
            const btnAddOpt = document.createElement("button");
            btnAddOpt.type = "button";
            btnAddOpt.className = "btn secondary";
            btnAddOpt.style.padding = "6px 12px";
            btnAddOpt.style.fontSize = "11px";
            btnAddOpt.style.boxShadow = "none";
            btnAddOpt.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.add_option','+ Add Option') : '+ Add Option';
            
            optHeader.appendChild(optTitle);
            optHeader.appendChild(btnAddOpt);
            optBuilder.appendChild(optHeader);
            
            const optList = document.createElement("div");
            optList.className = "options-builder-list";
            
            const options = field.options || [];
            
            const renderOptionRow = (option, optIdx) => {
                const row = document.createElement("div");
                row.className = "option-item-row";
                
                const inLabel = document.createElement("input");
                inLabel.type = "text";
                inLabel.value = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(option.label) : (option.label || "");
                inLabel.placeholder = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('settings.option_label_placeholder','Label (e.g. High)') : 'Label (e.g. High)';
                inLabel.addEventListener("input", (e) => {
                    const lang = Obsidianscout.safeGetItem('obsidianscout:lang') || 'en';
                    const val = e.target.value;
                    if (option && typeof option.label === 'object' && option.label !== null) {
                        option.label[lang] = val;
                    } else {
                        option.label = val;
                    }
                    if (shouldAutoUpdateOptionValue(option)) {
                        const base = slugify(e.target.value);
                        const autoValue = ensureUniqueSlug(base, collectOptionValues(options, option));
                        option.value = autoValue;
                        option._autoValue = autoValue;
                        inVal.value = autoValue;
                    }
                    updateRawFromVisual();
                });
                
                const inVal = document.createElement("input");
                inVal.type = "text";
                inVal.value = option.value || "";
                inVal.placeholder = "Value";
                inVal.addEventListener("input", (e) => {
                    option.value = e.target.value;
                    option._autoValue = null;
                    updateRawFromVisual();
                });
                
                let inPts = null;
                if (supportsPointsConfig()) {
                    inPts = document.createElement("input");
                    inPts.type = "number";
                    inPts.step = "any";
                    inPts.value = option.points !== undefined && option.points !== null ? option.points : 0;
                    inPts.placeholder = "Pts";
                    inPts.addEventListener("input", (e) => {
                        option.points = e.target.value !== "" ? Number(e.target.value) : 0;
                        updateRawFromVisual();
                    });
                }
                
                const btnDelOpt = document.createElement("button");
                btnDelOpt.type = "button";
                btnDelOpt.className = "btn-control-icon delete";
                btnDelOpt.style.width = "32px";
                btnDelOpt.style.height = "32px";
                btnDelOpt.style.borderRadius = "8px";
                btnDelOpt.innerHTML = "🗑️";
                btnDelOpt.title = "Delete Option";
                btnDelOpt.addEventListener("click", () => {
                    options.splice(optIdx, 1);
                    updateRawFromVisual();
                    
                    optList.innerHTML = "";
                    options.forEach((o, oIdx) => {
                        optList.appendChild(renderOptionRow(o, oIdx));
                    });
                });
                
                row.appendChild(inLabel);
                row.appendChild(inVal);
                if (inPts) {
                    row.appendChild(inPts);
                }
                row.appendChild(btnDelOpt);
                return row;
            };
            
            options.forEach((opt, optIdx) => {
                optList.appendChild(renderOptionRow(opt, optIdx));
            });
            
            btnAddOpt.addEventListener("click", () => {
                const newOpt = supportsPointsConfig() ? { label: "", value: "", points: 0 } : { label: "", value: "" };
                options.push(newOpt);
                updateRawFromVisual();
                optList.appendChild(renderOptionRow(newOpt, options.length - 1));
            });
            
            optBuilder.appendChild(optList);
            body.appendChild(optBuilder);
        }
        
        card.appendChild(body);
        return card;
    }

    function moveField(index, direction) {
        const targetIdx = index + direction;
        if (targetIdx < 0 || targetIdx >= currentConfig.fields.length) {
            return;
        }
        
        const temp = currentConfig.fields[index];
        currentConfig.fields[index] = currentConfig.fields[targetIdx];
        currentConfig.fields[targetIdx] = temp;
        
        updateRawFromVisual();
        renderVisualFields();
    }

    function deleteField(index) {
        if (confirm("Are you sure you want to delete this scouting field?")) {
            currentConfig.fields.splice(index, 1);
            updateRawFromVisual();
            renderVisualFields();
        }
    }

    function addField() {
        const baseId = ensureUniqueSlug(supportsPointsConfig() ? "newField" : "newNote", collectFieldIds());
        const newField = supportsPointsConfig() ? {
            id: baseId,
            _autoId: baseId,
            label: "New Field",
            type: "counter",
            required: false,
            min: 0,
            max: 10,
            step: 1,
            pointsPer: 0
        } : {
            id: baseId,
            _autoId: baseId,
            label: "New Note",
            type: "textarea",
            required: false
        };
        currentConfig.fields.push(newField);
        updateRawFromVisual();
        renderVisualFields();
        
        if (visualFieldsList) {
            setTimeout(() => {
                visualFieldsList.lastElementChild?.scrollIntoView({ behavior: "smooth" });
            }, 50);
        }
    }

    function slugify(text) {
        if (!text) return "";
        return text
            .replace(/[^a-zA-Z0-9\s-_]/g, "")
            .trim()
            .split(/[\s\-_]+/)
            .map((word, index) => {
                if (index === 0) return word.toLowerCase();
                return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
            })
            .join("");
    }

    function collectFieldIds(excludeIndex = -1, excludeField = null) {
        return new Set(
            (currentConfig.fields || [])
                .filter((field, index) => index !== excludeIndex && field !== excludeField)
                .map((field) => field.id)
                .filter((id) => id && id.trim())
        );
    }

    function collectOptionValues(options, excludeOption) {
        return new Set(
            (options || [])
                .filter((option) => option !== excludeOption)
                .map((option) => option.value)
                .filter((value) => value && String(value).trim())
        );
    }

    function ensureUniqueSlug(base, existing) {
        if (!base) return "";
        let candidate = base;
        let counter = 2;
        while (existing && existing.has(candidate)) {
            candidate = `${base}${counter}`;
            counter += 1;
        }
        return candidate;
    }

    function shouldAutoUpdateId(field, currentValue) {
        if (!currentValue) return true;
        if (currentValue.startsWith("field_")) return true;
        if (field && field._autoId && field._autoId === currentValue) return true;
        return false;
    }

    function shouldAutoUpdateOptionValue(option) {
        if (!option) return true;
        if (!option.value) return true;
        if (option._autoValue && option._autoValue === option.value) return true;
        return false;
    }

    function resolveFieldPhase(field) {
        if (!field) return "";
        if (field.phase) return String(field.phase).toLowerCase();
        const id = String(field.id || "").toLowerCase();
        if (id.startsWith("auto")) return "auto";
        if (id.startsWith("teleop")) return "teleop";
        if (id.startsWith("endgame")) return "endgame";
        return "";
    }

    function updateRawFromVisual() {
        const titleVal = configTitleInput ? configTitleInput.value.trim() : "ObsidianScout";
        const versionVal = configVersionInput ? (Number(configVersionInput.value) || 1) : 1;

        currentConfig.title = titleVal;
        currentConfig.version = versionVal;

        const cleanedFields = (currentConfig.fields || []).map((field) => {
            let normalizedLabel = "";
            if (field.label !== undefined && field.label !== null) {
                if (typeof field.label === 'string') {
                    normalizedLabel = field.label.trim();
                } else if (typeof field.label === 'object') {
                    const obj = {};
                    Object.keys(field.label).forEach((k) => {
                        const v = field.label[k];
                        obj[k] = (typeof v === 'string') ? v.trim() : v;
                    });
                    normalizedLabel = obj;
                }
            }

            const cleaned = {
                id: field.id ? field.id.trim() : "",
                label: normalizedLabel,
                type: field.type || "text",
                required: !!field.required
            };
            
            const type = cleaned.type;
            
            if (type === "section") {
                delete cleaned.required;
                return cleaned;
            }

            if (field.phase) {
                cleaned.phase = String(field.phase);
            }
            
            if (type === "number" || type === "counter" || type === "rating") {
                if (field.min !== undefined && field.min !== null && field.min !== "") {
                    cleaned.min = Number(field.min);
                }
                if (field.max !== undefined && field.max !== null && field.max !== "") {
                    cleaned.max = Number(field.max);
                }
                if (field.step !== undefined && field.step !== null && field.step !== "") {
                    cleaned.step = Number(field.step);
                }
            }
            
            if (supportsPointsConfig() && (type === "number" || type === "counter" || type === "rating" || type === "checkbox")) {
                if (field.pointsPer !== undefined && field.pointsPer !== null && field.pointsPer !== "") {
                    cleaned.pointsPer = Number(field.pointsPer);
                }
            }
            
            if (type === "select") {
                cleaned.options = (field.options || []).map((opt) => {
                    let normalizedOptLabel = "";
                    if (opt.label !== undefined && opt.label !== null) {
                        if (typeof opt.label === 'string') {
                            normalizedOptLabel = opt.label.trim();
                        } else if (typeof opt.label === 'object') {
                            const o = {};
                            Object.keys(opt.label).forEach((k) => {
                                const v = opt.label[k];
                                o[k] = (typeof v === 'string') ? v.trim() : v;
                            });
                            normalizedOptLabel = o;
                        }
                    }
                    return {
                        label: normalizedOptLabel,
                        value: opt.value ? opt.value.trim() : "",
                        ...(supportsPointsConfig() ? { points: opt.points !== undefined && opt.points !== null ? Number(opt.points) : 0 } : {})
                    };
                });
            }
            
            return cleaned;
        });

        const roleCheckbox = document.getElementById("config-enable-role-collection");
        const enableRoles = roleCheckbox ? roleCheckbox.checked : !!currentConfig.enableRobotRoleCollection;

        const cleanedConfig = {
            title: titleVal,
            version: versionVal,
            fields: cleanedFields,
            analytics: currentConfig.analytics || [],
            enableRobotRoleCollection: activeConfigKind === "qual" ? enableRoles : false
        };

        editor.value = JSON.stringify(cleanedConfig, null, 2);
    }
});

function wireTabs() {
    const tabs = document.querySelectorAll(".tab");
    const panels = document.querySelectorAll("[data-panel]");
    tabs.forEach((tab) => {
        if (tab.id === "btn-visual-editor" || tab.id === "btn-raw-editor" || tab.dataset.configKind) {
            return;
        }
        tab.addEventListener("click", () => {
            tabs.forEach((item) => {
                if (item.id !== "btn-visual-editor" && item.id !== "btn-raw-editor" && !item.dataset.configKind) {
                    item.classList.remove("active");
                }
            });
            panels.forEach((panel) => panel.classList.add("hidden"));
            tab.classList.add("active");
            const target = tab.dataset.tab;
            document.querySelector(`[data-panel='${target}']`).classList.remove("hidden");
        });
    });
}

function isValidJson(text) {
    try {
        JSON.parse(text);
        return true;
    } catch (error) {
        return false;
    }
}



