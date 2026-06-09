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

    const isUserAdmin = Obsidianscout.isAdmin(me.role);
    if (!isUserAdmin) {
        // Hide admin tabs
        const tabConfig = document.getElementById("tab-config");
        const tabApi = document.getElementById("tab-api");
        if (tabConfig) tabConfig.classList.add("hidden");
        if (tabApi) tabApi.classList.add("hidden");

        // Activate personal tab
        const tabPersonal = document.getElementById("tab-personal");
        if (tabPersonal) {
            tabPersonal.classList.add("active");
        }
        
        const tabConfigBtn = document.querySelector(".tab[data-tab='config']");
        if (tabConfigBtn) {
            tabConfigBtn.classList.remove("active");
        }

        // Show personal panel, hide others
        const panels = document.querySelectorAll("[data-panel]");
        panels.forEach((p) => {
            if (p.dataset.panel === "personal") {
                p.classList.remove("hidden");
            } else {
                p.classList.add("hidden");
            }
        });

        // Initialize personal setting dropdown
        const personalDisplaySelect = document.getElementById("personal-team-display");
        if (personalDisplaySelect) {
            personalDisplaySelect.value = localStorage.getItem("obsidianscout:team_display") || "merged";
            personalDisplaySelect.addEventListener("change", (e) => {
                localStorage.setItem("obsidianscout:team_display", e.target.value);
                Obsidianscout.showToast("Personal settings saved", "success");
                window.dispatchEvent(new CustomEvent("obsidianscout:teamdisplaychange", { detail: { format: e.target.value } }));
            });
        }

        wireTabs();
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
            const [configResponse, settingsResponse] = await Promise.all([
                Obsidianscout.request(mode.apiPath),
                Obsidianscout.request("/api/settings")
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

            // Initialize personal setting dropdown
            const personalDisplaySelect = document.getElementById("personal-team-display");
            if (personalDisplaySelect) {
                personalDisplaySelect.value = localStorage.getItem("obsidianscout:team_display") || "merged";
                personalDisplaySelect.addEventListener("change", (e) => {
                    localStorage.setItem("obsidianscout:team_display", e.target.value);
                    Obsidianscout.showToast("Personal settings saved", "success");
                    window.dispatchEvent(new CustomEvent("obsidianscout:teamdisplaychange", { detail: { format: e.target.value } }));
                });
            }

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

            // Populate settings
            const settings = settingsResponse.settings;
            document.getElementById("settings-year").value = settings.year;
            document.getElementById("settings-event-code").value = settings.eventCode || "";
            document.getElementById("settings-timezone").value = settings.timezone || "America/New_York";
            document.getElementById("settings-source").value = settings.preferredSource || "tba";
            document.getElementById("settings-epa").checked = settings.useStatboticsEpa;
            document.getElementById("settings-opr").checked = settings.useTbaOpr;
            document.getElementById("settings-tba-key").value = settings.apiKeys.tbaKey || "";
            document.getElementById("settings-first-user").value = settings.apiKeys.firstUsername || "";
            document.getElementById("settings-first-key").value = settings.apiKeys.firstKey || "";
            document.getElementById("settings-statbotics-key").value = settings.apiKeys.statboticsKey || "";

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
                        }
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

            // API settings save
            document.getElementById("settings-save").addEventListener("click", async () => {
                const payload = {
                    year: Number(document.getElementById("settings-year").value || new Date().getFullYear()),
                    eventCode: document.getElementById("settings-event-code").value.trim(),
                    timezone: document.getElementById("settings-timezone").value.trim(),
                    preferredSource: document.getElementById("settings-source").value,
                    useStatboticsEpa: document.getElementById("settings-epa").checked,
                    useTbaOpr: document.getElementById("settings-opr").checked,
                    apiKeys: {
                        tbaKey: document.getElementById("settings-tba-key").value.trim(),
                        firstUsername: document.getElementById("settings-first-user").value.trim(),
                        firstKey: document.getElementById("settings-first-key").value.trim(),
                        statboticsKey: document.getElementById("settings-statbotics-key").value.trim()
                    }
                };

                try {
                    await Obsidianscout.request("/api/settings", {
                        method: "PUT",
                        json: payload
                    });
                    Obsidianscout.showToast("API settings saved", "success");
                } catch (error) {
                    Obsidianscout.showToast(error.message || "Save failed", "error");
                }
            });

        } catch (error) {
            console.error("Failed to load settings data:", error);
            Obsidianscout.showRetryButton(adminPanelWrapper, "Failed to load settings: " + error.message, loadSettingsPageData);
        }
    }

    async function loadActiveConfig() {
        const mode = configModes[activeConfigKind];
        try {
            const config = await Obsidianscout.request(mode.apiPath);
            currentConfig = normalizeConfig(config, mode.defaultTitle);
            editor.value = JSON.stringify(currentConfig, null, 2);
            if (configTitleInput) {
                configTitleInput.value = currentConfig.title || "";
            }
            if (configVersionInput) {
                configVersionInput.value = currentConfig.version || 1;
            }
            renderVisualFields();
            showVisualEditor();
        } catch (error) {
            Obsidianscout.showToast("Unable to load config", "error");
        }
    }

    function normalizeConfig(config, defaultTitle) {
        const parsed = typeof config === "string" ? JSON.parse(config) : (config || {});
        return {
            title: parsed.title || defaultTitle,
            version: Number(parsed.version) || 1,
            fields: Array.isArray(parsed.fields) ? parsed.fields : [],
            analytics: Array.isArray(parsed.analytics) ? parsed.analytics : []
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

    /**
     * Renders all form fields in currentConfig.fields to the visualFieldsList container.
     */
    function renderVisualFields() {
        if (!visualFieldsList) return;
        visualFieldsList.innerHTML = "";

        const fields = currentConfig.fields || [];
        if (fields.length === 0) {
            const emptyNotice = document.createElement("div");
            emptyNotice.className = "notice";
            emptyNotice.style.textAlign = "center";
            emptyNotice.style.padding = "24px";
            emptyNotice.textContent = "No fields configured. Click '+ Add Field' to start building!";
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

    /**
     * Builds an interactive, styled configuration card for a field.
     */
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
            const lang = localStorage.getItem('obsidianscout:lang') || 'en';
            const val = e.target.value;
            if (field && typeof field.label === 'object' && field.label !== null) {
                field.label[lang] = val;
            } else {
                field.label = val;
            }
            title.textContent = val || `Field ${index + 1}`;
            
            // Auto-slugify ID if it is blank or matches a default auto-generated format
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
            
            // Initialize structures if switching types
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
        
        // Adjust for section type (doesn't need ID, Required etc in basic visual)
        if (field.type === "section") {
            divId.style.display = "none";
            divPhase.style.display = "none";
            divReq.style.display = "none";
        }
        
        // 5. Numeric Bounds (visible for number, counter, rating)
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
        
        // 6. Scoring Points (visible for number, counter, rating, checkbox)
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
                    const lang = localStorage.getItem('obsidianscout:lang') || 'en';
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
                    
                    // Rerender option rows
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

    /**
     * Swap fields in array
     */
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

    /**
     * Delete field with prompt
     */
    function deleteField(index) {
        if (confirm("Are you sure you want to delete this scouting field?")) {
            currentConfig.fields.splice(index, 1);
            updateRawFromVisual();
            renderVisualFields();
        }
    }

    /**
     * Create a new field to add to the config
     */
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
        
        // Scroll list
        if (visualFieldsList) {
            setTimeout(() => {
                visualFieldsList.lastElementChild?.scrollIntoView({ behavior: "smooth" });
            }, 50);
        }
    }

    /**
     * CamelCase text slugification for default IDs
     */
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

    /**
     * Commits visual changes in-place to currentConfig and serializes to the raw JSON editor textarea.
     */
    function updateRawFromVisual() {
        const titleVal = configTitleInput ? configTitleInput.value.trim() : "ObsidianScout";
        const versionVal = configVersionInput ? (Number(configVersionInput.value) || 1) : 1;

        currentConfig.title = titleVal;
        currentConfig.version = versionVal;

        // Deep copy/cleanup fields structure to emit beautiful, validated JSON schemas
        const cleanedFields = (currentConfig.fields || []).map((field) => {
            // Preserve localized label objects; trim strings
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
            
            // Numeric bounds
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
            
            // Points per action
            if (supportsPointsConfig() && (type === "number" || type === "counter" || type === "rating" || type === "checkbox")) {
                if (field.pointsPer !== undefined && field.pointsPer !== null && field.pointsPer !== "") {
                    cleaned.pointsPer = Number(field.pointsPer);
                }
            }
            
            // Select options
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

        const cleanedConfig = {
            title: titleVal,
            version: versionVal,
            fields: cleanedFields,
            analytics: currentConfig.analytics || []
        };

        editor.value = JSON.stringify(cleanedConfig, null, 2);
    }
});

function wireTabs() {
    const tabs = document.querySelectorAll(".tab");
    const panels = document.querySelectorAll("[data-panel]");
    tabs.forEach((tab) => {
        // Skip sub-tabs
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

async function loadSettings() {
    try {
        const response = await Obsidianscout.request("/api/settings");
        const settings = response.settings;
        document.getElementById("settings-year").value = settings.year;
        document.getElementById("settings-event-code").value = settings.eventCode || "";
        document.getElementById("settings-timezone").value = settings.timezone || "America/New_York";
        document.getElementById("settings-source").value = settings.preferredSource || "tba";
        document.getElementById("settings-epa").checked = settings.useStatboticsEpa;
        document.getElementById("settings-opr").checked = settings.useTbaOpr;
        document.getElementById("settings-tba-key").value = settings.apiKeys.tbaKey || "";
        document.getElementById("settings-first-user").value = settings.apiKeys.firstUsername || "";
        document.getElementById("settings-first-key").value = settings.apiKeys.firstKey || "";
        document.getElementById("settings-statbotics-key").value = settings.apiKeys.statboticsKey || "";
    } catch (error) {
        Obsidianscout.showToast("Unable to load API settings", "error");
    }
}

function isValidJson(text) {
    try {
        JSON.parse(text);
        return true;
    } catch (error) {
        return false;
    }
}
