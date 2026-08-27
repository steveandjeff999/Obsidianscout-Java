
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

let originalMainContentHTML = "";
let mainContentWrapper = null;
let mainContent = null;
let currentEventKey = "";

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

    mainContent = document.querySelector(".main-content");
    if (mainContent) {
        const siblings = Array.from(mainContent.children).filter(child => !child.classList.contains("banner-container"));
        mainContentWrapper = document.createElement("div");
        mainContentWrapper.id = "prescout-pit-wrapper";
        siblings.forEach(child => mainContentWrapper.appendChild(child));
        mainContent.appendChild(mainContentWrapper);
        originalMainContentHTML = mainContentWrapper.innerHTML;
        await initPrescoutPit(me);
    }
});

async function initPrescoutPit(me) {
    if (!mainContentWrapper) return;
    Obsidianscout.showLoadingSpinner(mainContentWrapper, "Loading pit prescout config...");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        const config = await Obsidianscout.request("/api/pit-config");

        mainContentWrapper.innerHTML = originalMainContentHTML;

        const form = document.getElementById("pit-scouting-form");
        if (form) {
            form.noValidate = true;
        }
        const fieldContainer = document.getElementById("form-fields");
        const submitButton = document.getElementById("pit-submit");
        const clearButton = document.getElementById("pit-clear");
        const teamInput = document.getElementById("team-input");
        const timezoneBadge = document.getElementById("timezone-badge");
        const eventCodeInput = document.getElementById("event-code-input");
        const formBlocked = document.getElementById("form-blocked");

        timezoneBadge.textContent = settings.timezone || "UTC";

        const defaultEvent = Obsidianscout.resolveEventKey(settings) || settings.eventCode || "prescout";
        if (eventCodeInput && !eventCodeInput.value) {
            eventCodeInput.value = defaultEvent;
        }
        currentEventKey = (eventCodeInput && eventCodeInput.value.trim().toLowerCase()) || defaultEvent;

        let entryCache = await loadEntryCache();

        // Build dynamic form fields
        const reserved = new Set(["eventKey", "targetTeamNumber"]);
        const fields = (config.fields || []).filter((field) => field.type !== "section");
        fields
            .filter((field) => !reserved.has(field.id))
            .forEach((field) => {
                const node = buildField(field);
                if (node) {
                    fieldContainer.appendChild(node);
                }
            });

        async function handleSelectionChange(preserveFields = false) {
            const rawTeam = teamInput ? teamInput.value.trim() : "";
            const teamNumber = Number(rawTeam);
            currentEventKey = (eventCodeInput && eventCodeInput.value.trim().toLowerCase()) || defaultEvent;

            const ready = Boolean(teamNumber > 0 && currentEventKey);
            setFormEnabled(form, formBlocked, ready);

            if (!ready) {
                if (!preserveFields) {
                    clearFormFields(fields, form);
                }
                return;
            }

            if (!preserveFields) {
                clearFormFields(fields, form);
                const entry = findEntry(entryCache, currentEventKey, teamNumber);
                if (entry) {
                    applyEntryToForm(entry, fields, form);
                }
            }
        }

        if (teamInput) {
            teamInput.addEventListener("input", () => {
                handleSelectionChange(false);
            });
        }

        if (eventCodeInput) {
            eventCodeInput.addEventListener("input", () => {
                currentEventKey = eventCodeInput.value.trim().toLowerCase() || defaultEvent;
                handleSelectionChange(false);
            });
        }

        if (clearButton) {
            clearButton.addEventListener("click", () => {
                if (!confirm(Obsidianscout.t("pit_scout.confirm_clear", "Are you sure you want to clear the form? All entered data will be reset."))) {
                    return;
                }
                clearFormFields(fields, form);
            });
        }

        function resolvePayload() {
            const rawTeam = teamInput ? teamInput.value.trim() : "";
            const teamNumber = Number(rawTeam);
            if (!teamNumber || teamNumber <= 0) {
                Obsidianscout.showToast("Enter a valid team number", "error");
                return null;
            }

            currentEventKey = (eventCodeInput && eventCodeInput.value.trim().toLowerCase()) || defaultEvent;
            const payload = buildPayload(config.fields, form);
            if (!payload) return null;

            payload.eventKey = currentEventKey;
            payload.targetTeamNumber = teamNumber;
            payload.type = "prescout-pit";
            return payload;
        }

        const exportJsonBtn = document.getElementById("pit-export-json");
        if (exportJsonBtn) {
            exportJsonBtn.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                const filename = `prescout_pit_${payload.eventKey}_team${payload.targetTeamNumber}.json`;
                Obsidianscout.downloadJson(payload, filename);
            });
        }

        const genQrBtn = document.getElementById("pit-gen-qr");
        if (genQrBtn) {
            genQrBtn.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                Obsidianscout.showQrModal(payload, "Pit Prescouting", payload.targetTeamNumber, null);
            });
        }

        const saveOfflineButton = document.getElementById("pit-save-offline");
        if (saveOfflineButton) {
            saveOfflineButton.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                const pending = JSON.parse(Obsidianscout.safeGetItem("pending_prescout_pit_scouting_entries") || "[]");
                pending.push({
                    data: payload
                });
                Obsidianscout.safeSetItem("pending_prescout_pit_scouting_entries", JSON.stringify(pending));

                Obsidianscout.showToast(`Saved locally (Offline mode) - Team ${payload.targetTeamNumber}`, "success");
                Obsidianscout.updateConnectionStatus();

                clearFormFields(fields, form);
                handleSelectionChange(true);
            });
        }

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const payload = resolvePayload();
            if (!payload) return;

            Obsidianscout.setButtonLoading(submitButton, true, t('scout.saving', 'Saving entry...'));

            try {
                const response = await Obsidianscout.request("/api/prescout/pit-scouting", {
                    method: "POST",
                    json: {
                        data: payload
                    }
                });
                Obsidianscout.showToast(`Pit prescout entry saved for Team ${payload.targetTeamNumber}`, "success");
                
                const newEntry = (response && response.entry) ? response.entry : {
                    eventKey: payload.eventKey,
                    targetTeamNumber: payload.targetTeamNumber,
                    data: payload,
                    scoutName: me ? me.username : null,
                    updatedAt: new Date().toISOString()
                };
                const existingIdx = entryCache.findIndex(e => e.eventKey === newEntry.eventKey && e.targetTeamNumber === newEntry.targetTeamNumber);
                if (existingIdx >= 0) {
                    entryCache[existingIdx] = newEntry;
                } else {
                    entryCache.push(newEntry);
                }
                Obsidianscout.safeSetItem("cache:/api/prescout/pit-scouting", JSON.stringify(entryCache));
            } catch (error) {
                if (!navigator.onLine || error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
                    const pending = JSON.parse(Obsidianscout.safeGetItem("pending_prescout_pit_scouting_entries") || "[]");
                    pending.push({
                        data: payload
                    });
                    Obsidianscout.safeSetItem("pending_prescout_pit_scouting_entries", JSON.stringify(pending));

                    Obsidianscout.showToast(`Saved locally (Offline mode) - Team ${payload.targetTeamNumber}`, "success");
                    Obsidianscout.updateConnectionStatus();

                    clearFormFields(fields, form);
                    handleSelectionChange(true);
                } else {
        });

    } catch (err) {
        console.error(err);
        Obsidianscout.showRetryButton(mainContentWrapper, "Failed to load config: " + err.message, () => initPrescoutPit(me));
    }
}

function buildField(field) {
    if (field.type === "section") {
        return null;
    }

    const wrapper = document.createElement("div");
    wrapper.className = "field";

    const label = document.createElement("label");
    label.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;
    label.htmlFor = `field-${field.id}`;

    let input;
    let actualInput = null;
    switch (field.type) {
        case "number":
            input = document.createElement("input");
            input.type = "number";
            applyNumberBounds(input, field);
            break;
        case "counter":
            ({ wrapper: input, input: actualInput } = buildCounter(field));
            break;
        case "rating":
            ({ wrapper: input, input: actualInput } = buildRating(field));
            break;
        case "select":
            input = document.createElement("select");
            (field.options || []).forEach((option) => {
                const optionNode = document.createElement("option");
                if (typeof option === "string") {
                    optionNode.value = option;
                    optionNode.textContent = option;
                } else {
                    optionNode.value = option.value;
                    optionNode.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(option.label) : option.label;
                }
                input.appendChild(optionNode);
            });
            break;
        case "text":
        case "static_text":
        case "label":
        case "info": {
            const staticDisplay = document.createElement("div");
            staticDisplay.className = "static-text-display";
            staticDisplay.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : (field.label || "");
            if (field.placeholder) {
                const sub = document.createElement("div");
                sub.className = "static-text-sub";
                sub.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.placeholder) : field.placeholder;
                staticDisplay.appendChild(sub);
            }
            wrapper.appendChild(staticDisplay);
            return wrapper;
        }
        case "checkbox":
            input = document.createElement("input");
            input.type = "checkbox";
            break;
        case "textarea":
        case "notes":
            input = document.createElement("textarea");
            break;
        case "image":
        case "image_upload":
        case "photo":
            ({ wrapper: input, input: actualInput } = buildImageUpload(field));
            break;
        default:
            input = document.createElement("input");
            input.type = "text";
            break;
    }

    const target = actualInput || input;
    if (target instanceof HTMLElement) {
        target.id = `field-${field.id}`;
        target.name = field.id;
        if (field.required) {
            target.required = true;
        }
    }

    wrapper.appendChild(label);
    wrapper.appendChild(input);
    return wrapper;
}

function buildCounter(field) {
    const wrapper = document.createElement("div");
    wrapper.className = "counter";

    const step = field.step || 1;
    const doubleStep = field.doubleStep !== undefined ? field.doubleStep : field.double_step;
    const hasDoubleStep = doubleStep !== undefined && doubleStep !== null && Number(doubleStep) > 0;
    const dStep = hasDoubleStep ? Number(doubleStep) : null;

    const input = document.createElement("input");
    input.type = "number";
    input.value = field.min ?? 0;
    applyNumberBounds(input, field);

    if (hasDoubleStep) {
        const minusDouble = document.createElement("button");
        minusDouble.type = "button";
        minusDouble.className = "btn-counter-double btn-counter-minus-double";
        minusDouble.textContent = `-${dStep}`;
        minusDouble.addEventListener("click", () => {
            const min = field.min ?? 0;
            input.value = String(Math.max(Number(input.value || 0) - dStep, min));
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });

        const minus = document.createElement("button");
        minus.type = "button";
        minus.className = "btn-counter-single btn-counter-minus";
        minus.textContent = `-${step}`;
        minus.addEventListener("click", () => {
            const min = field.min ?? 0;
            input.value = String(Math.max(Number(input.value || 0) - step, min));
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });

        const plus = document.createElement("button");
        plus.type = "button";
        plus.className = "btn-counter-single btn-counter-plus";
        plus.textContent = `+${step}`;
        plus.addEventListener("click", () => {
            const max = field.max ?? Number.POSITIVE_INFINITY;
            input.value = String(Math.min(Number(input.value || 0) + step, max));
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });

        const plusDouble = document.createElement("button");
        plusDouble.type = "button";
        plusDouble.className = "btn-counter-double btn-counter-plus-double";
        plusDouble.textContent = `+${dStep}`;
        plusDouble.addEventListener("click", () => {
            const max = field.max ?? Number.POSITIVE_INFINITY;
            input.value = String(Math.min(Number(input.value || 0) + dStep, max));
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });

        wrapper.appendChild(minusDouble);
        wrapper.appendChild(minus);
        wrapper.appendChild(input);
        wrapper.appendChild(plus);
        wrapper.appendChild(plusDouble);
    } else {
        const minus = document.createElement("button");
        minus.type = "button";
        minus.textContent = "-";
        minus.addEventListener("click", () => {
            const min = field.min ?? 0;
            input.value = String(Math.max(Number(input.value || 0) - step, min));
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });

        const plus = document.createElement("button");
        plus.type = "button";
        plus.textContent = "+";
        plus.addEventListener("click", () => {
            const max = field.max ?? Number.POSITIVE_INFINITY;
            input.value = String(Math.min(Number(input.value || 0) + step, max));
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });

        wrapper.appendChild(minus);
        wrapper.appendChild(input);
        wrapper.appendChild(plus);
    }

    return { wrapper, input };
}

function buildRating(field) {
    const wrapper = document.createElement("div");
    wrapper.className = "rating-stars-container";

    const input = document.createElement("input");
    input.type = "hidden";
    const minVal = Number(field.min !== undefined && field.min !== null ? field.min : 1);
    const maxVal = Number(field.max !== undefined && field.max !== null ? field.max : 5);
    input.min = minVal;
    input.max = maxVal;
    input.value = String(minVal);

    const starsGroup = document.createElement("div");
    starsGroup.className = "rating-stars-group";

    const starElements = [];
    const count = Math.max(1, maxVal - minVal + 1);

    function updateStars(val) {
        input.value = String(val);
        starElements.forEach((btn, idx) => {
            const starNum = minVal + idx;
            if (starNum <= val) {
                btn.classList.add("selected");
                btn.textContent = "★";
            } else {
                btn.classList.remove("selected");
                btn.textContent = "☆";
            }
        });
    }

    for (let i = 0; i < count; i++) {
        const starNum = minVal + i;
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "rating-star-btn";
        btn.dataset.value = String(starNum);
        btn.textContent = (starNum <= minVal) ? "★" : "☆";
        if (starNum <= minVal) {
            btn.classList.add("selected");
        }
        btn.addEventListener("click", (e) => {
            e.preventDefault();
            updateStars(starNum);
            input.dispatchEvent(new Event("input", { bubbles: true }));
            input.dispatchEvent(new Event("change", { bubbles: true }));
        });
        starsGroup.appendChild(btn);
        starElements.push(btn);
    }

    input.addEventListener("input", () => {
        const val = Number(input.value) || minVal;
        updateStars(val);
    });

    wrapper.appendChild(input);
    wrapper.appendChild(starsGroup);
    return { wrapper, input };
}

function buildImageUpload(field) {
    const wrapper = document.createElement("div");
    wrapper.className = "image-upload-field-container";
    wrapper.style.cssText = "display:flex;flex-direction:column;gap:10px;padding:12px;background:rgba(255,255,255,0.03);border:1px dashed rgba(255,255,255,0.18);border-radius:12px;";

    const hiddenInput = document.createElement("input");
    hiddenInput.type = "hidden";
    hiddenInput.name = field.id;
    hiddenInput.id = `field-${field.id}`;
    if (field.required) hiddenInput.required = true;

    const fileInput = document.createElement("input");
    fileInput.type = "file";
    fileInput.accept = "image/*";
    fileInput.style.display = "none";

    const emptyState = document.createElement("div");
    emptyState.className = "image-empty-state";
    emptyState.style.cssText = "display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;padding:16px 8px;text-align:center;";

    const emptyPrompt = document.createElement("div");
    emptyPrompt.style.cssText = "color:#94a3b8;font-size:0.85rem;";
    emptyPrompt.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.placeholder) : (field.placeholder || "Take a live photo or upload an image of the robot:");

    const btnRow = document.createElement("div");
    btnRow.style.cssText = "display:flex;flex-wrap:wrap;gap:8px;justify-content:center;";

    const btnCamera = document.createElement("button");
    btnCamera.type = "button";
    btnCamera.className = "btn";
    btnCamera.style.cssText = "background:linear-gradient(135deg,#0284c7,#0369a1);color:#fff;border:none;padding:8px 16px;border-radius:8px;font-weight:600;font-size:0.85rem;display:flex;align-items:center;gap:6px;cursor:pointer;";
    btnCamera.innerHTML = `<span>Take Photo</span>`;

    const btnBrowse = document.createElement("button");
    btnBrowse.type = "button";
    btnBrowse.className = "btn";
    btnBrowse.style.cssText = "background:rgba(255,255,255,0.08);color:#f8fafc;border:1px solid rgba(255,255,255,0.15);padding:8px 14px;border-radius:8px;font-weight:600;font-size:0.85rem;display:flex;align-items:center;gap:6px;cursor:pointer;";
    btnBrowse.innerHTML = `<span>Choose Image</span>`;

    btnRow.appendChild(btnCamera);
    btnRow.appendChild(btnBrowse);
    emptyState.appendChild(emptyPrompt);
    emptyState.appendChild(btnRow);

    const previewState = document.createElement("div");
    previewState.className = "image-preview-state";
    previewState.style.cssText = "display:none;flex-direction:column;align-items:center;gap:10px;";

    const imgPreview = document.createElement("img");
    imgPreview.style.cssText = "max-width:100%;max-height:240px;border-radius:8px;border:1px solid rgba(255,255,255,0.12);object-fit:contain;cursor:pointer;";
    imgPreview.title = "Click to inspect full image";

    const badgeRow = document.createElement("div");
    badgeRow.style.cssText = "display:flex;align-items:center;justify-content:space-between;width:100%;font-size:0.8rem;color:#94a3b8;";

    const sizeBadge = document.createElement("span");
    sizeBadge.className = "img-meta-badge";
    sizeBadge.style.cssText = "background:rgba(56,189,248,0.15);color:#38bdf8;padding:2px 8px;border-radius:6px;font-weight:600;";

    const actionButtons = document.createElement("div");
    actionButtons.style.cssText = "display:flex;gap:8px;";

    const btnRetake = document.createElement("button");
    btnRetake.type = "button";
    btnRetake.className = "btn btn-sm";
    btnRetake.style.cssText = "background:rgba(255,255,255,0.08);color:#f8fafc;border:1px solid rgba(255,255,255,0.15);padding:4px 10px;border-radius:6px;font-size:0.8rem;cursor:pointer;";
    btnRetake.textContent = "Change / Retake";

    const btnRemove = document.createElement("button");
    btnRemove.type = "button";
    btnRemove.className = "btn btn-sm";
    btnRemove.style.cssText = "background:rgba(239,68,68,0.15);color:#f87171;border:1px solid rgba(239,68,68,0.3);padding:4px 10px;border-radius:6px;font-size:0.8rem;cursor:pointer;";
    btnRemove.textContent = "Remove";

    actionButtons.appendChild(btnRetake);
    actionButtons.appendChild(btnRemove);
    badgeRow.appendChild(sizeBadge);
    badgeRow.appendChild(actionButtons);

    previewState.appendChild(imgPreview);
    previewState.appendChild(badgeRow);

    wrapper.appendChild(hiddenInput);
    wrapper.appendChild(fileInput);
    wrapper.appendChild(emptyState);
    wrapper.appendChild(previewState);

    function updatePreview(dataUrl, infoText = "") {
        if (dataUrl) {
            hiddenInput.value = dataUrl;
            imgPreview.src = dataUrl;
            sizeBadge.textContent = infoText || "Cleaned & Compressed";
            emptyState.style.display = "none";
            previewState.style.display = "flex";
        } else {
            hiddenInput.value = "";
            imgPreview.src = "";
            fileInput.value = "";
            emptyState.style.display = "flex";
            previewState.style.display = "none";
        }
    }

    wrapper.updateImage = updatePreview;

    btnCamera.addEventListener("click", () => {
        Obsidianscout.openInlineCameraModal({
            onCapture: (result) => {
                updatePreview(result.dataUrl, `${result.width}x${result.height} (${result.formattedSize})`);
                hiddenInput.dispatchEvent(new Event("change", { bubbles: true }));
            }
        });
    });

    btnBrowse.addEventListener("click", () => fileInput.click());
    btnRetake.addEventListener("click", () => {
        btnCamera.click();
    });
    btnRemove.addEventListener("click", () => {
        updatePreview(null);
        hiddenInput.dispatchEvent(new Event("change", { bubbles: true }));
    });

    fileInput.addEventListener("change", async (e) => {
        const file = e.target.files[0];
        if (!file) return;
        try {
            const result = await Obsidianscout.processImageUpload(file);
            updatePreview(result.dataUrl, `${result.width}x${result.height} (${result.formattedSize})`);
            hiddenInput.dispatchEvent(new Event("change", { bubbles: true }));
        } catch (err) {
            Obsidianscout.showToast(err.message || "Failed to process image", "error");
        }
    });

    imgPreview.addEventListener("click", () => {
        if (hiddenInput.value) {
            Obsidianscout.showImageModal(hiddenInput.value, field.label || "Robot Photo");
        }
    });

    return { wrapper, input: hiddenInput };
}

function applyNumberBounds(input, field) {
    if (field.min !== null && field.min !== undefined) {
        input.min = field.min;
    }
    if (field.max !== null && field.max !== undefined) {
        input.max = field.max;
    }
    if (field.step !== null && field.step !== undefined) {
        input.step = field.step;
    }
}

function buildPayload(fields, form) {
    const payload = {};
    for (const field of fields) {
        const input = form.querySelector(`[name='${field.id}']`);
        if (!input) {
            continue;
        }
        const value = readFieldValue(field, input);
        const label = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;

        if (field.required && (value === null || value === "")) {
            Obsidianscout.showToast(`Missing ${label}`, "error");
            if (typeof switchTab === "function" && typeof getFieldPhase === "function") {
                const phase = getFieldPhase(field);
                if (phase) switchTab(phase);
            }
            return null;
        }

        if (value !== null && value !== "") {
            if (field.type === "number" || field.type === "counter" || field.type === "rating") {
                const numVal = Number(value);
                if (field.min !== null && field.min !== undefined && numVal < field.min) {
                    Obsidianscout.showToast(`${label} must be at least ${field.min}`, "error");
                    if (typeof switchTab === "function" && typeof getFieldPhase === "function") {
                        const phase = getFieldPhase(field);
                        if (phase) switchTab(phase);
                    }
                    return null;
                }
                if (field.max !== null && field.max !== undefined && numVal > field.max) {
                    Obsidianscout.showToast(`${label} must be at most ${field.max}`, "error");
                    if (typeof switchTab === "function" && typeof getFieldPhase === "function") {
                        const phase = getFieldPhase(field);
                        if (phase) switchTab(phase);
                    }
                    return null;
                }
            }
            payload[field.id] = value;
        }
    }
    return payload;
}

function readFieldValue(field, input) {
    if (field.type === "checkbox") {
        return input.checked;
    }
    if (field.type === "number" || field.type === "counter" || field.type === "rating") {
        return input.value === "" ? null : Number(input.value);
    }
    return input.value.trim();
}

async function loadEntryCache() {
    try {
        const entries = await Obsidianscout.request("/api/prescout/pit-scouting");
        return Array.isArray(entries) ? entries : [];
    } catch (error) {
        return [];
    }
}

function findEntry(entries, eventKey, teamNumber) {
    return entries.find((entry) =>
        entry.eventKey === eventKey && entry.targetTeamNumber === teamNumber
    );
}

function applyEntryToForm(entry, fields, form) {
    if (!entry || !entry.data) {
        return;
    }
    fields.forEach((field) => {
        if (field.type === "section") {
            return;
        }
        const input = form.querySelector(`[name='${field.id}']`);
        if (!input) {
            return;
        }
        const value = entry.data[field.id];
        if (value === undefined || value === null) {
            return;
        }
        if (field.type === "image" || field.type === "image_upload" || field.type === "photo") {
            const container = input.closest(".image-upload-field-container");
            if (container && typeof container.updateImage === "function") {
                container.updateImage(value);
            }
            return;
        }
        if (field.type === "checkbox") {
            input.checked = Boolean(value);
            return;
        }
        input.value = value;
        if (field.type === "rating") {
            input.dispatchEvent(new Event("input", { bubbles: true }));
        }
    });
}

function clearFormFields(fields, form) {
    fields.forEach((field) => {
        if (field.type === "section") {
            return;
        }
        const input = form.querySelector(`[name='${field.id}']`);
        if (!input) {
            return;
        }
        if (field.type === "image" || field.type === "image_upload" || field.type === "photo") {
            const container = input.closest(".image-upload-field-container");
            if (container && typeof container.updateImage === "function") {
                container.updateImage(null);
            }
            return;
        }
        if (field.type === "checkbox") {
            input.checked = false;
            return;
        }
        input.value = field.type === "counter" && field.min !== undefined && field.min !== null ? field.min : ((field.type === "rating" && field.min !== undefined && field.min !== null) ? String(field.min) : (field.type === "rating" ? "1" : ""));
        if (field.type === "rating") {
            input.dispatchEvent(new Event("input", { bubbles: true }));
        }
    });
}

function setFormEnabled(form, notice, enabled) {
    if (notice) {
        notice.classList.toggle("hidden", enabled);
    }
    const fieldContainer = document.getElementById("form-fields");
    if (fieldContainer) {
        fieldContainer.classList.toggle("hidden", !enabled);
    }
    const actionsRow = form.querySelector(".row.gap-12") || form.querySelector(".form-actions");
    if (actionsRow) {
        actionsRow.classList.toggle("hidden", !enabled);
    }
    const inputs = form.querySelectorAll("input, select, textarea, button");
    inputs.forEach((input) => {
        input.disabled = !enabled;
    });
}
