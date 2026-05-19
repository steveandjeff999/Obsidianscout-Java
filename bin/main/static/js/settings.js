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

    const editor = document.getElementById("config-editor");
    const saveButton = document.getElementById("config-save");
    const exportButton = document.getElementById("config-export");
    const importInput = document.getElementById("config-import");

    if (!Obsidianscout.isAdmin(me.role)) {
        document.getElementById("admin-locked").classList.remove("hidden");
        document.getElementById("admin-panel").classList.add("hidden");
        return;
    }

    // Visual Editor elements
    const btnVisual = document.getElementById("btn-visual-editor");
    const btnRaw = document.getElementById("btn-raw-editor");
    const containerVisual = document.getElementById("visual-editor-container");
    const containerRaw = document.getElementById("raw-editor-container");
    const configTitleInput = document.getElementById("config-title");
    const configVersionInput = document.getElementById("config-version");
    const btnAddField = document.getElementById("btn-add-field");
    const visualFieldsList = document.getElementById("visual-fields-list");

    // Local configuration state
    let currentConfig = { version: 1, title: "ObsidianScout", fields: [], analytics: [] };

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
            // Keep synced
            updateRawFromVisual();
            btnVisual.classList.remove("active");
            btnRaw.classList.add("active");
            containerVisual.classList.add("hidden");
            containerRaw.classList.remove("hidden");
        });
    }

    // Global metadata change listeners
    if (configTitleInput) {
        configTitleInput.addEventListener("input", updateRawFromVisual);
    }
    if (configVersionInput) {
        configVersionInput.addEventListener("input", updateRawFromVisual);
    }

    // Add field listener
    if (btnAddField) {
        btnAddField.addEventListener("click", addField);
    }

    wireTabs();
    await loadConfig(editor);
    await loadSettings();

    // Save configuration
    saveButton.addEventListener("click", async () => {
        let text = editor.value.trim();
        if (!isValidJson(text)) {
            Obsidianscout.showToast("Config JSON is invalid", "error");
            return;
        }

        try {
            // Parse & sync state in case raw was edited
            currentConfig = JSON.parse(text);
            if (!currentConfig.fields) currentConfig.fields = [];
            if (!currentConfig.analytics) currentConfig.analytics = [];
            
            if (configTitleInput) configTitleInput.value = currentConfig.title || "";
            if (configVersionInput) configVersionInput.value = currentConfig.version || 1;
            
            renderVisualFields();
        } catch (err) {
            // Safe fallback
        }

        try {
            await Obsidianscout.request("/api/config", {
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
        link.download = "scouting-config.json";
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

    /**
     * Renders all form fields in currentConfig.fields to the visualFieldsList container.
     */
    function renderVisualFields() {
        if (!visualFieldsList) return;
        visualFieldsList.innerHTML = "";
        
        if (currentConfig.fields.length === 0) {
            const emptyNotice = document.createElement("div");
            emptyNotice.className = "notice";
            emptyNotice.style.textAlign = "center";
            emptyNotice.style.padding = "24px";
            emptyNotice.textContent = "No fields configured. Click '+ Add Field' to start building!";
            visualFieldsList.appendChild(emptyNotice);
            return;
        }

        currentConfig.fields.forEach((field, index) => {
            const cardNode = createFieldCard(field, index);
            visualFieldsList.appendChild(cardNode);
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
        title.textContent = field.label || `Field ${index + 1}`;
        
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
        labelTag.textContent = "Field Label";
        const inputLabel = document.createElement("input");
        inputLabel.type = "text";
        inputLabel.value = field.label || "";
        inputLabel.placeholder = "e.g. Teleop Cycles";
        inputLabel.addEventListener("input", (e) => {
            field.label = e.target.value;
            title.textContent = e.target.value || `Field ${index + 1}`;
            
            // Auto-slugify ID if it is blank or matches a default auto-generated format
            const inputId = divId.querySelector("input");
            if (inputId && (!field.id || field.id.startsWith("field_"))) {
                const autoId = slugify(e.target.value);
                field.id = autoId;
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
        labelId.textContent = "Field ID / Slug";
        const inputId = document.createElement("input");
        inputId.type = "text";
        inputId.value = field.id || "";
        inputId.placeholder = "e.g. teleopCycles";
        inputId.addEventListener("input", (e) => {
            field.id = e.target.value;
            updateRawFromVisual();
        });
        divId.appendChild(labelId);
        divId.appendChild(inputId);
        body.appendChild(divId);
        
        // 3. Type Select
        const divType = document.createElement("div");
        divType.className = "field";
        const labelType = document.createElement("label");
        labelType.textContent = "Type";
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
        
        // 4. Required Checkbox
        const divReq = document.createElement("div");
        divReq.className = "field";
        const labelReq = document.createElement("label");
        labelReq.textContent = "Required";
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
        labelWrap.appendChild(document.createTextNode(" Is Required"));
        divReq.appendChild(labelReq);
        divReq.appendChild(labelWrap);
        body.appendChild(divReq);
        
        // Adjust for section type (doesn't need ID, Required etc in basic visual)
        if (field.type === "section") {
            divId.style.display = "none";
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
            labelMin.textContent = "Min";
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
            labelMax.textContent = "Max";
            const inputMax = document.createElement("input");
            inputMax.type = "number";
            inputMax.value = field.max !== undefined && field.max !== null ? field.max : "";
            inputMax.addEventListener("input", (e) => {
                field.max = e.target.value !== "" ? Number(e.target.value) : null;
                updateRawFromVisual();
            });
            divMax.appendChild(labelMax);
            divMax.appendChild(inputMax);
            boundsDiv.appendChild(divMax);
            
            // Step
            const divStep = document.createElement("div");
            divStep.className = "field";
            const labelStep = document.createElement("label");
            labelStep.textContent = "Step";
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
        if (field.type === "number" || field.type === "counter" || field.type === "rating" || field.type === "checkbox") {
            const divPoints = document.createElement("div");
            divPoints.className = "field";
            const labelPoints = document.createElement("label");
            labelPoints.textContent = "Points per action";
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
            optTitle.textContent = "Options (Label | Value | Points)";
            
            const btnAddOpt = document.createElement("button");
            btnAddOpt.type = "button";
            btnAddOpt.className = "btn secondary";
            btnAddOpt.style.padding = "6px 12px";
            btnAddOpt.style.fontSize = "11px";
            btnAddOpt.style.boxShadow = "none";
            btnAddOpt.textContent = "+ Add Option";
            
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
                inLabel.value = option.label || "";
                inLabel.placeholder = "Label (e.g. High)";
                inLabel.addEventListener("input", (e) => {
                    option.label = e.target.value;
                    if (!option.value || option.value === slugify(inLabel.placeholder)) {
                        option.value = e.target.value;
                        inVal.value = e.target.value;
                    }
                    updateRawFromVisual();
                });
                
                const inVal = document.createElement("input");
                inVal.type = "text";
                inVal.value = option.value || "";
                inVal.placeholder = "Value";
                inVal.addEventListener("input", (e) => {
                    option.value = e.target.value;
                    updateRawFromVisual();
                });
                
                const inPts = document.createElement("input");
                inPts.type = "number";
                inPts.step = "any";
                inPts.value = option.points !== undefined && option.points !== null ? option.points : 0;
                inPts.placeholder = "Pts";
                inPts.addEventListener("input", (e) => {
                    option.points = e.target.value !== "" ? Number(e.target.value) : 0;
                    updateRawFromVisual();
                });
                
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
                row.appendChild(inPts);
                row.appendChild(btnDelOpt);
                return row;
            };
            
            options.forEach((opt, optIdx) => {
                optList.appendChild(renderOptionRow(opt, optIdx));
            });
            
            btnAddOpt.addEventListener("click", () => {
                const newOpt = { label: "", value: "", points: 0 };
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
        const newField = {
            id: "field_" + Math.random().toString(36).substr(2, 5),
            label: "New Field",
            type: "counter",
            required: false,
            min: 0,
            max: 10,
            step: 1,
            pointsPer: 0
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
            .replace(/[^a-zA-Z0-9\s-_]/g, '')
            .trim()
            .split(/[\s\-_]+/)
            .map((word, index) => {
                if (index === 0) return word.toLowerCase();
                return word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();
            })
            .join('');
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
        currentConfig.fields = currentConfig.fields.map((field) => {
            const cleaned = {
                id: field.id ? field.id.trim() : "",
                label: field.label ? field.label.trim() : "",
                type: field.type || "text",
                required: !!field.required
            };
            
            const type = cleaned.type;
            
            if (type === "section") {
                delete cleaned.required;
                return cleaned;
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
            if (type === "number" || type === "counter" || type === "rating" || type === "checkbox") {
                if (field.pointsPer !== undefined && field.pointsPer !== null && field.pointsPer !== "") {
                    cleaned.pointsPer = Number(field.pointsPer);
                }
            }
            
            // Select options
            if (type === "select") {
                cleaned.options = (field.options || []).map((opt) => ({
                    label: opt.label ? opt.label.trim() : "",
                    value: opt.value ? opt.value.trim() : "",
                    points: opt.points !== undefined && opt.points !== null ? Number(opt.points) : 0
                }));
            }
            
            return cleaned;
        });
        
        editor.value = JSON.stringify(currentConfig, null, 2);
    }
});

function wireTabs() {
    const tabs = document.querySelectorAll(".tab");
    const panels = document.querySelectorAll("[data-panel]");
    tabs.forEach((tab) => {
        // Skip sub-tabs
        if (tab.id === "btn-visual-editor" || tab.id === "btn-raw-editor") {
            return;
        }
        tab.addEventListener("click", () => {
            tabs.forEach((item) => {
                if (item.id !== "btn-visual-editor" && item.id !== "btn-raw-editor") {
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

async function loadConfig(editor) {
    try {
        const config = await Obsidianscout.request("/api/config");
        // Deep copy to local config variable
        const titleInput = document.getElementById("config-title");
        const versionInput = document.getElementById("config-version");
        
        // Trigger event loop switch to allow DOM rendering context to bind
        setTimeout(() => {
            const btnVisual = document.getElementById("btn-visual-editor");
            if (btnVisual) {
                // Parse loaded config
                const parsed = typeof config === "string" ? JSON.parse(config) : config;
                
                // Assign to top scope currentConfig in memory
                // Find currentConfig via closing over state or updating raw
                const settingsScope = document.querySelector("[data-page='settings']");
                if (settingsScope) {
                    // Let's programmatically load and trigger visual tabs
                    editor.value = JSON.stringify(parsed, null, 2);
                    btnVisual.click();
                }
            }
        }, 100);
        
    } catch (error) {
        Obsidianscout.showToast("Unable to load config", "error");
    }
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
