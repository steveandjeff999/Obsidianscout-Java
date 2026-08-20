
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
        mainContentWrapper.id = "prescout-scout-wrapper";
        siblings.forEach(child => mainContentWrapper.appendChild(child));
        mainContent.appendChild(mainContentWrapper);
        originalMainContentHTML = mainContentWrapper.innerHTML;
        await initPrescoutScout(me);
    }
});

async function initPrescoutScout(me) {
    if (!mainContentWrapper) return;
    Obsidianscout.showLoadingSpinner(mainContentWrapper, "Loading prescout config...");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        const config = await Obsidianscout.request("/api/config");

        mainContentWrapper.innerHTML = originalMainContentHTML;

        const form = document.getElementById("scouting-form");
        if (form) {
            form.noValidate = true;
        }
        const fieldContainer = document.getElementById("form-fields");
        const submitButton = document.getElementById("scout-submit");
        const teamInput = document.getElementById("team-input");
        const matchInput = document.getElementById("match-input");
        const timezoneBadge = document.getElementById("timezone-badge");
        const eventCodeInput = document.getElementById("event-code-input");
        const clearButton = document.getElementById("scout-clear");
        const formBlocked = document.getElementById("form-blocked");
        const pointsPreviewCard = document.getElementById("points-preview");

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
        const fields = injectSections(config.fields || []);
        
        fields
            .filter((field) => !reserved.has(field.id))
            .forEach((field) => {
                if (field.type === "section") {
                    return;
                }
                const node = buildField(field);
                node.dataset.phase = getFieldPhase(field);
                fieldContainer.appendChild(node);
            });

        const tabsRow = document.getElementById("scouting-tabs");
        if (tabsRow) {
            const tabs = tabsRow.querySelectorAll(".tab");
            tabs.forEach(tab => {
                tab.addEventListener("click", () => {
                    switchTab(tab.dataset.tab);
                });
            });
        }
        switchTab("auto");

        const pointsPreview = {
            auto: document.getElementById("points-auto"),
            teleop: document.getElementById("points-teleop"),
            endgame: document.getElementById("points-endgame"),
            total: document.getElementById("points-total")
        };

        fieldContainer.addEventListener("input", () => updatePointsPreview(fields, form, pointsPreview));
        fieldContainer.addEventListener("change", () => updatePointsPreview(fields, form, pointsPreview));
        updatePointsPreview(fields, form, pointsPreview);

        async function handleSelectionChange(preserveFields = false) {
            const rawTeam = teamInput ? teamInput.value.trim() : "";
            const teamNumber = Number(rawTeam);
            currentEventKey = (eventCodeInput && eventCodeInput.value.trim().toLowerCase()) || defaultEvent;

            const ready = Boolean(teamNumber > 0 && currentEventKey);
            setFormEnabled(form, formBlocked, pointsPreviewCard, ready);

            if (!ready) {
                if (!preserveFields) {
                    clearFormFields(fields, form);
                    updatePointsPreview(fields, form, pointsPreview);
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
                updatePointsPreview(fields, form, pointsPreview);
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
                if (!confirm(Obsidianscout.t("scout.confirm_clear", "Are you sure you want to clear the form? All entered data will be reset."))) {
                    return;
                }
                clearFormFields(fields, form);
                updatePointsPreview(fields, form, pointsPreview);
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
            payload.type = "prescout-scout";
            return payload;
        }

        const exportJsonBtn = document.getElementById("scout-export-json");
        if (exportJsonBtn) {
            exportJsonBtn.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                const filename = `prescout_scout_${payload.eventKey}_team${payload.targetTeamNumber}_match${payload.matchNumber}.json`;
                Obsidianscout.downloadJson(payload, filename);
            });
        }

        const genQrBtn = document.getElementById("scout-gen-qr");
        if (genQrBtn) {
            genQrBtn.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                Obsidianscout.showQrModal(payload, "Match Prescouting", payload.targetTeamNumber, payload.matchKey);
            });
        }

        const saveOfflineButton = document.getElementById("scout-save-offline");
        if (saveOfflineButton) {
            saveOfflineButton.addEventListener("click", () => {
                const payload = resolvePayload();
                if (!payload) return;

                const pending = JSON.parse(Obsidianscout.safeGetItem("pending_prescout_scouting_entries") || "[]");
                pending.push({
                    data: payload
                });
                Obsidianscout.safeSetItem("pending_prescout_scouting_entries", JSON.stringify(pending));

                Obsidianscout.showToast(`Saved locally (Offline mode) - Match #${payload.matchNumber}`, "success");
                Obsidianscout.updateConnectionStatus();

                // Advance to next match number automatically and clear fields
                if (matchInput) {
                    matchInput.value = payload.matchNumber + 1;
                }
                clearFormFields(fields, form);
                updatePointsPreview(fields, form, pointsPreview);
            });
        }

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const payload = resolvePayload();
            if (!payload) return;

            Obsidianscout.setButtonLoading(submitButton, true, t('scout.saving', 'Saving entry...'));

            try {
                const response = await Obsidianscout.request("/api/prescout/scouting", {
                    method: "POST",
                    json: {
                        data: payload
                    }
                });
                Obsidianscout.showToast(`Prescout entry saved for Team ${payload.targetTeamNumber} (Match #${payload.matchNumber})`, "success");
                
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
                Obsidianscout.safeSetItem("cache:/api/prescout/scouting", JSON.stringify(entryCache));

                // Auto-advance to next match number and clear form fields for rapid scouting
                if (matchInput) {
                    matchInput.value = payload.matchNumber + 1;
                }
                clearFormFields(fields, form);
                updatePointsPreview(fields, form, pointsPreview);
            } catch (error) {
                if (!navigator.onLine || error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
                    const pending = JSON.parse(Obsidianscout.safeGetItem("pending_prescout_scouting_entries") || "[]");
                    pending.push({
                        data: payload
                    });
                    Obsidianscout.safeSetItem("pending_prescout_scouting_entries", JSON.stringify(pending));

                    Obsidianscout.showToast(`Saved locally (Offline mode) - Match #${payload.matchNumber}`, "success");
                    Obsidianscout.updateConnectionStatus();

                    if (matchInput) {
                        matchInput.value = payload.matchNumber + 1;
                    }
                    clearFormFields(fields, form);
                    updatePointsPreview(fields, form, pointsPreview);
                } else {
                    Obsidianscout.showToast(error.message || "Failed to save", "error");
                }
            } finally {
                Obsidianscout.setButtonLoading(submitButton, false);
            }
        });

    } catch (err) {
        console.error(err);
        Obsidianscout.showRetryButton(mainContentWrapper, "Failed to load config: " + err.message, () => initPrescoutScout(me));
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

function injectSections(fields) {
    return (fields || []).filter((field) => field.type !== "section");
}

function getFieldPhase(field) {
    if (!field) {
        return "teleop";
    }
    if (field.phase) {
        const p = String(field.phase).toLowerCase().trim();
        if (p === "postmatch" || p === "post-match" || p === "post match" || p === "post") return "postmatch";
        if (p === "general" || p === "") return "teleop";
        return p;
    }
    const id = String(field.id || "").toLowerCase();
    if (id.startsWith("auto")) return "auto";
    if (id.startsWith("teleop")) return "teleop";
    if (id.startsWith("endgame")) return "endgame";
    if (id.startsWith("post")) return "postmatch";
    return "teleop";
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

function updatePointsPreview(fields, form, preview) {
    if (!preview) {
        return;
    }
    const totals = { auto: 0, teleop: 0, endgame: 0, total: 0 };
    fields.forEach((field) => {
        if (field.type === "section") {
            return;
        }
        const input = form.querySelector(`[name='${field.id}']`);
        if (!input) {
            return;
        }
        const value = readFieldValue(field, input);
        if (value === null || value === "") {
            return;
        }
        const points = fieldPoints(field, value);
        totals.total += points;
        const phase = getFieldPhase(field);
        if (phase === "auto") {
            totals.auto += points;
        } else if (phase === "teleop") {
            totals.teleop += points;
        } else if (phase === "endgame") {
            totals.endgame += points;
        }
    });

    if (preview.auto) preview.auto.textContent = formatNumber(totals.auto);
    if (preview.teleop) preview.teleop.textContent = formatNumber(totals.teleop);
    if (preview.endgame) preview.endgame.textContent = formatNumber(totals.endgame);
    if (preview.total) preview.total.textContent = formatNumber(totals.total);
}

async function loadEntryCache() {
    try {
        const entries = await Obsidianscout.request("/api/prescout/scouting");
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

function setFormEnabled(form, notice, pointsCard, enabled) {
    if (notice) {
        notice.classList.toggle("hidden", enabled);
    }
    const tabsRow = document.getElementById("scouting-tabs");
    if (tabsRow) {
        tabsRow.classList.toggle("hidden", !enabled);
    }
    const fieldContainer = document.getElementById("form-fields");
    if (fieldContainer) {
        fieldContainer.classList.toggle("hidden", !enabled);
    }
    if (pointsCard) {
        pointsCard.classList.toggle("hidden", !enabled);
    }
    const actionsRow = form.querySelector(".row.gap-12") || form.querySelector(".form-actions");
    if (actionsRow) {
        actionsRow.classList.toggle("hidden", !enabled);
    }
    const inputs = form.querySelectorAll("input, select, textarea, button");
    inputs.forEach((input) => {
        if (input.classList.contains("tab")) {
            input.disabled = !enabled;
            return;
        }
        if (input.id === "scout-submit" || input.id === "scout-clear") {
            input.disabled = !enabled;
            return;
        }
        input.disabled = !enabled;
    });
    if (enabled) {
        switchTab("auto");
    }
}

function switchTab(activeTab) {
    const tabs = document.querySelectorAll("#scouting-tabs .tab");
    tabs.forEach(tab => {
        if (tab.dataset.tab === activeTab) {
            tab.classList.add("active");
        } else {
            tab.classList.remove("active");
        }
    });

    const fields = document.querySelectorAll("#form-fields .field");
    fields.forEach(field => {
        if (field.dataset.phase === activeTab) {
            field.classList.remove("hidden");
        } else {
            field.classList.add("hidden");
        }
    });
}


function fieldPoints(field, value) {
    if (!field) {
        return 0;
    }
    const type = String(field.type || "").toLowerCase();
    const pointsPer = Number(field.pointsPer || 0);

    if (type === "counter" || type === "number" || type === "rating") {
        const number = Number(value) || 0;
        return number * pointsPer;
    }

    if (type === "checkbox") {
        return value ? pointsPer : 0;
    }

    if (type === "select") {
        const options = field.options || [];
        const match = options.find((option) => option.value === value || option.label === value);
        return match ? Number(match.points || 0) : 0;
    }

    return 0;
}

function formatNumber(value) {
    return Number.isInteger(value) ? value.toString() : Number(value).toFixed(2);
}
