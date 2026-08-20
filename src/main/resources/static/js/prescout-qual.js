
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
        const siblings = Array.from(mainContent.children);
        mainContentWrapper = document.createElement("div");
        mainContentWrapper.id = "prescout-qual-wrapper";
        siblings.forEach(child => mainContentWrapper.appendChild(child));
        mainContent.appendChild(mainContentWrapper);
        originalMainContentHTML = mainContentWrapper.innerHTML;
        await initPrescoutQual(me);
    }
});

async function initPrescoutQual(me) {
    if (!mainContentWrapper) return;
    Obsidianscout.showLoadingSpinner(mainContentWrapper, "Loading qualitative prescout config...");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        const config = await Obsidianscout.request("/api/qual-config");

        mainContentWrapper.innerHTML = originalMainContentHTML;

        const form = document.getElementById("qual-scouting-form");
        if (form) {
            form.noValidate = true;
        }
        const fieldContainer = document.getElementById("form-fields");
        const submitButton = document.getElementById("qual-submit");
        const clearButton = document.getElementById("qual-clear");
        const teamInput = document.getElementById("team-input");
        const matchInput = document.getElementById("match-input");
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

        function getNextMatchNumber(teamNum, eventKey) {
            if (!teamNum) return 1;
            const key = eventKey || currentEventKey;
            const teamEntries = entryCache.filter(e => Number(e.targetTeamNumber) === Number(teamNum) && (!key || e.eventKey === key));
            const maxMatch = teamEntries.reduce((max, e) => Math.max(max, Number(e.matchNumber) || 0), 0);
            return maxMatch + 1;
        }

        // Build dynamic form fields
        const reserved = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);
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

            let matchNum = matchInput && matchInput.value ? Number(matchInput.value) : null;
            if (!matchNum || matchNum <= 0) {
                matchNum = getNextMatchNumber(teamNumber, currentEventKey);
                if (matchInput) {
                    matchInput.value = matchNum;
                }
            }

            if (!preserveFields) {
                clearFormFields(fields, form);
                const matchKey = `${currentEventKey}_qm${matchNum}`;
                const entry = findEntry(entryCache, currentEventKey, teamNumber, matchKey);
                if (entry) {
                    applyEntryToForm(entry, fields, form);
                }
            }
        }

        if (teamInput) {
            teamInput.addEventListener("input", () => {
                if (matchInput) {
                    const rawTeam = Number(teamInput.value.trim());
                    if (rawTeam > 0) {
                        matchInput.value = getNextMatchNumber(rawTeam, currentEventKey);
                    }
                }
                handleSelectionChange(false);
            });
        }

        if (matchInput) {
            matchInput.addEventListener("change", () => {
                handleSelectionChange(false);
            });
        }

        if (eventCodeInput) {
            eventCodeInput.addEventListener("input", () => {
                currentEventKey = eventCodeInput.value.trim().toLowerCase() || defaultEvent;
                if (teamInput && Number(teamInput.value.trim()) > 0 && matchInput) {
                    matchInput.value = getNextMatchNumber(Number(teamInput.value.trim()), currentEventKey);
                }
                handleSelectionChange(false);
            });
        }

        if (clearButton) {
            clearButton.addEventListener("click", () => {
                if (!confirm(Obsidianscout.t("qual_scout.confirm_clear", "Are you sure you want to clear the form? All entered data will be reset."))) {
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
            let matchNumber = matchInput && matchInput.value ? Number(matchInput.value) : null;
            if (!matchNumber || matchNumber <= 0) {
                matchNumber = getNextMatchNumber(teamNumber, currentEventKey);
                if (matchInput) {
                    matchInput.value = matchNumber;
                }
            }

            const payload = buildPayload(config.fields, form);
            if (!payload) return null;

            payload.eventKey = currentEventKey;
            payload.targetTeamNumber = teamNumber;
            payload.matchNumber = matchNumber;
            payload.matchKey = `${currentEventKey}_qm${matchNumber}`;
            payload.type = "prescout-qual";
            return payload;
        }

        const exportJsonBtn = document.getElementById("qual-export-json");
        if (exportJsonBtn) {
            exportJsonBtn.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                const filename = `prescout_qual_${payload.eventKey}_team${payload.targetTeamNumber}_match${payload.matchNumber}.json`;
                Obsidianscout.downloadJson(payload, filename);
            });
        }

        const genQrBtn = document.getElementById("qual-gen-qr");
        if (genQrBtn) {
            genQrBtn.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                Obsidianscout.showQrModal(payload, "Qualitative Prescouting", payload.targetTeamNumber, payload.matchKey);
            });
        }

        const saveOfflineButton = document.getElementById("qual-save-offline");
        if (saveOfflineButton) {
            saveOfflineButton.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                const pending = JSON.parse(Obsidianscout.safeGetItem("pending_prescout_qualitative_entries") || "[]");
                pending.push({
                    data: payload
                });
                Obsidianscout.safeSetItem("pending_prescout_qualitative_entries", JSON.stringify(pending));

                Obsidianscout.showToast(`Saved locally (Offline mode) - Match #${payload.matchNumber}`, "success");
                Obsidianscout.updateConnectionStatus();

                if (matchInput) {
                    matchInput.value = payload.matchNumber + 1;
                }
                clearFormFields(fields, form);
            });
        }

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const payload = resolvePayload();
            if (!payload) return;

            Obsidianscout.setButtonLoading(submitButton, true, t('scout.saving', 'Saving entry...'));

            try {
                const response = await Obsidianscout.request("/api/prescout/qual-scouting", {
                    method: "POST",
                    json: {
                        data: payload
                    }
                });
                Obsidianscout.showToast(`Qualitative prescout entry saved for Team ${payload.targetTeamNumber} (Match #${payload.matchNumber})`, "success");
                
                const newEntry = (response && response.entry) ? response.entry : {
                    eventKey: payload.eventKey,
                    targetTeamNumber: payload.targetTeamNumber,
                    matchKey: payload.matchKey,
                    matchNumber: payload.matchNumber,
                    data: payload,
                    scoutName: me ? me.username : null,
                    updatedAt: new Date().toISOString()
                };
                const existingIdx = entryCache.findIndex(e => e.eventKey === newEntry.eventKey && e.targetTeamNumber === newEntry.targetTeamNumber && e.matchKey === newEntry.matchKey);
                if (existingIdx >= 0) {
                    entryCache[existingIdx] = newEntry;
                } else {
                    entryCache.push(newEntry);
                }
                Obsidianscout.safeSetItem("cache:/api/prescout/qual-scouting", JSON.stringify(entryCache));

                if (matchInput) {
                    matchInput.value = payload.matchNumber + 1;
                }
                clearFormFields(fields, form);
            } catch (error) {
                if (!navigator.onLine || error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
                    const pending = JSON.parse(Obsidianscout.safeGetItem("pending_prescout_qualitative_entries") || "[]");
                    pending.push({
                        data: payload
                    });
                    Obsidianscout.safeSetItem("pending_prescout_qualitative_entries", JSON.stringify(pending));

                    Obsidianscout.showToast(`Saved locally (Offline mode) - Match #${payload.matchNumber}`, "success");
                    Obsidianscout.updateConnectionStatus();

                    if (matchInput) {
                        matchInput.value = payload.matchNumber + 1;
                    }
                    clearFormFields(fields, form);
                } else {
                    Obsidianscout.showToast(error.message || "Failed to save entry", "error");
                }
            } finally {
                Obsidianscout.setButtonLoading(submitButton, false);
            }
        });

    } catch (err) {
        console.error(err);
        Obsidianscout.showRetryButton(mainContentWrapper, "Failed to load config: " + err.message, () => initPrescoutQual(me));
    }
}

function truncateLabel(text, maxLength) {
    if (!text || text.length <= maxLength) {
        return text;
    }
    return `${text.slice(0, Math.max(0, maxLength - 3))}...`;
}

function formatTeamList(teamKeys) {
    if (!teamKeys || !teamKeys.length) {
        return "";
    }
    return teamKeys
        .map((key) => Obsidianscout.formatTeam(key))
        .join(", ");
}

function matchHasTeam(teams, teamKey) {
    if (!teams || !teamKey) return false;
    const cleanTeam = teamKey.replace(/^(frc|ftc)/, "");
    return teams.some(key => {
        const cleanKey = key.replace(/^(frc|ftc)/, "");
        if (cleanKey === cleanTeam) return true;
        const parts = key.split('/');
        return parts.some(part => {
            return part.replace(/^(frc|ftc)/, "") === cleanTeam;
        });
    });
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
            const options = field.options || [];
            options.forEach((option) => {
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
        case "checkbox":
            input = document.createElement("input");
            input.type = "checkbox";
            break;
        case "textarea":
            input = document.createElement("textarea");
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
    input.value = field.min || 0;
    applyNumberBounds(input, field);

    if (hasDoubleStep) {
        const minusDouble = document.createElement("button");
        minusDouble.type = "button";
        minusDouble.className = "btn-counter-double btn-counter-minus-double";
        minusDouble.textContent = `-${dStep}`;
        minusDouble.addEventListener("click", () => {
            const min = field.min || 0;
            input.value = String(Math.max(Number(input.value || 0) - dStep, min));
            input.dispatchEvent(new Event("input", { bubbles: true }));
        });

        const minus = document.createElement("button");
        minus.type = "button";
        minus.className = "btn-counter-single btn-counter-minus";
        minus.textContent = `-${step}`;
        minus.addEventListener("click", () => {
            const min = field.min || 0;
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
            const min = field.min || 0;
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
        const entries = await Obsidianscout.request("/api/prescout/qual-scouting");
        return Array.isArray(entries) ? entries : [];
    } catch (error) {
        return [];
    }
}

function findEntry(entries, eventKey, teamNumber, matchKey) {
    return entries.find((entry) =>
        entry.eventKey === eventKey && entry.targetTeamNumber === teamNumber && entry.matchKey === matchKey
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
        if (field.type === "checkbox") {
            input.checked = false;
            return;
        }
        input.value = (field.type === "rating" && field.min !== undefined && field.min !== null) ? String(field.min) : (field.type === "rating" ? "1" : "");
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
