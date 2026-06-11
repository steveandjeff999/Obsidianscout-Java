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
        const teamSelect = document.getElementById("team-select");
        const timezoneBadge = document.getElementById("timezone-badge");
        const eventCodeInput = document.getElementById("event-code-input");
        const loadEventBtn = document.getElementById("load-event-btn");
        const formBlocked = document.getElementById("form-blocked");

        timezoneBadge.textContent = settings.timezone;

        let entryCache = [];

        // Build dynamic form fields
        const reserved = new Set(["eventKey", "targetTeamNumber"]);
        const fields = config.fields || [];
        fields
            .filter((field) => !reserved.has(field.id))
            .forEach((field) => {
                fieldContainer.appendChild(buildField(field));
            });

        if (clearButton) {
            clearButton.addEventListener("click", () => {
                clearFormFields(fields, form);
            });
        }

        const exportJsonBtn = document.getElementById("pit-export-json");
        if (exportJsonBtn) {
            exportJsonBtn.addEventListener("click", () => {
                if (!teamSelect.value || !currentEventKey) {
                    Obsidianscout.showToast("Select a team and load an event first", "error");
                    return;
                }
                const payload = buildPayload(config.fields, form);
                if (!payload) return;
                payload.eventKey = currentEventKey;
                payload.targetTeamNumber = Number(teamSelect.value);
                payload.type = "prescout-pit";

                const filename = `prescout_pit_${currentEventKey}_team${payload.targetTeamNumber}.json`;
                Obsidianscout.downloadJson(payload, filename);
            });
        }

        const genQrBtn = document.getElementById("pit-gen-qr");
        if (genQrBtn) {
            genQrBtn.addEventListener("click", () => {
                if (!teamSelect.value || !currentEventKey) {
                    Obsidianscout.showToast("Select a team and load an event first", "error");
                    return;
                }
                const payload = buildPayload(config.fields, form);
                if (!payload) return;
                payload.eventKey = currentEventKey;
                payload.targetTeamNumber = Number(teamSelect.value);
                payload.type = "prescout-pit";

                Obsidianscout.showQrModal(payload, "Pit Prescouting", payload.targetTeamNumber, null);
            });
        }

        // Event loading logic
        loadEventBtn.addEventListener("click", async () => {
            const rawCode = eventCodeInput.value.trim().toLowerCase();
            if (!rawCode) {
                Obsidianscout.showToast("Enter a valid event code", "error");
                return;
            }

            loadEventBtn.disabled = true;
            eventCodeInput.disabled = true;
            teamSelect.disabled = true;
            Obsidianscout.showToast("Syncing event teams...", "info");

            try {
                let key = rawCode;
                if (!/^\d{4}/.test(rawCode)) {
                    key = `${settings.year}${rawCode}`;
                }
                currentEventKey = key;

                await Obsidianscout.request(`/api/prescout/sync-event?eventKey=${key}`, {
                    method: "POST"
                });

                Obsidianscout.showToast("Event teams synced successfully!", "success");

                await loadTeams(key, teamSelect);
                teamSelect.disabled = false;

                entryCache = await loadEntryCache();
                await handleSelectionChange();
            } catch (err) {
                console.error(err);
                Obsidianscout.showToast("Failed to load event: " + err.message, "error");
            } finally {
                loadEventBtn.disabled = false;
                eventCodeInput.disabled = false;
            }
        });

        teamSelect.addEventListener("change", handleSelectionChange);

        async function handleSelectionChange() {
            const teamValue = teamSelect.value;
            const ready = Boolean(teamValue && currentEventKey);
            setFormEnabled(form, formBlocked, ready);
            clearFormFields(fields, form);

            if (!ready) {
                return;
            }

            const entry = findEntry(entryCache, currentEventKey, Number(teamValue));
            if (entry) {
                applyEntryToForm(entry, fields, form);
            }
        }

        const saveOfflineButton = document.getElementById("pit-save-offline");
        if (saveOfflineButton) {
            saveOfflineButton.addEventListener("click", () => {
                if (!teamSelect.value || !currentEventKey) {
                    Obsidianscout.showToast("Select a team", "error");
                    return;
                }

                const payload = buildPayload(config.fields, form);
                if (!payload) return;

                payload.eventKey = currentEventKey;
                payload.targetTeamNumber = Number(teamSelect.value);

                const pending = JSON.parse(Obsidianscout.safeGetItem("pending_prescout_pit_scouting_entries") || "[]");
                pending.push({
                    data: payload
                });
                Obsidianscout.safeSetItem("pending_prescout_pit_scouting_entries", JSON.stringify(pending));

                Obsidianscout.showToast("Saved locally (Offline mode)", "success");
                Obsidianscout.updateConnectionStatus();

                clearFormFields(fields, form);
                handleSelectionChange();
            });
        }

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            submitButton.disabled = true;

            if (!teamSelect.value || !currentEventKey) {
                Obsidianscout.showToast("Select a team", "error");
                submitButton.disabled = false;
                return;
            }

            const payload = buildPayload(config.fields, form);
            if (!payload) {
                submitButton.disabled = false;
                return;
            }

            payload.eventKey = currentEventKey;
            payload.targetTeamNumber = Number(teamSelect.value);

            try {
                await Obsidianscout.request("/api/prescout/pit-scouting", {
                    method: "POST",
                    json: {
                        data: payload
                    }
                });
                Obsidianscout.showToast("Pit prescout entry saved", "success");
                entryCache = await loadEntryCache();
            } catch (error) {
                if (!navigator.onLine || error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
                    const pending = JSON.parse(Obsidianscout.safeGetItem("pending_prescout_pit_scouting_entries") || "[]");
                    pending.push({
                        data: payload
                    });
                    Obsidianscout.safeSetItem("pending_prescout_pit_scouting_entries", JSON.stringify(pending));

                    Obsidianscout.showToast("Saved locally (Offline mode)", "success");
                    Obsidianscout.updateConnectionStatus();

                    clearFormFields(fields, form);
                    handleSelectionChange();
                } else {
                    Obsidianscout.showToast(error.message || "Failed to save entry", "error");
                }
            } finally {
                submitButton.disabled = false;
            }
        });

    } catch (err) {
        console.error(err);
        Obsidianscout.showRetryButton(mainContentWrapper, "Failed to load config: " + err.message, () => initPrescoutPit(me));
    }
}

async function loadTeams(eventKey, teamSelect) {
    const teams = await Obsidianscout.request(`/api/teams?eventKey=${eventKey}`);

    teamSelect.innerHTML = "";
    const placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "Select team";
    teamSelect.appendChild(placeholder);

    teams.forEach((team) => {
        const option = document.createElement("option");
        option.value = team.teamNumber;
        const displayNum = Obsidianscout.formatTeam(team.teamKey, team.teamNumber);
        option.textContent = `${displayNum} ${team.nickname || team.name || ""}`.trim();
        teamSelect.appendChild(option);
    });
}

function buildField(field) {
    if (field.type === "section") {
        const section = document.createElement("div");
        section.className = "form-section";
        const title = document.createElement("h3");
        title.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;
        section.appendChild(title);
        return section;
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
    const minus = document.createElement("button");
    minus.type = "button";
    minus.textContent = "-";

    const input = document.createElement("input");
    input.type = "number";
    input.value = field.min ?? 0;
    applyNumberBounds(input, field);

    const plus = document.createElement("button");
    plus.type = "button";
    plus.textContent = "+";

    minus.addEventListener("click", () => {
        const step = field.step || 1;
        const min = field.min ?? 0;
        input.value = String(Math.max(Number(input.value || 0) - step, min));
        input.dispatchEvent(new Event("input", { bubbles: true }));
    });

    plus.addEventListener("click", () => {
        const step = field.step || 1;
        const max = field.max ?? Number.POSITIVE_INFINITY;
        input.value = String(Math.min(Number(input.value || 0) + step, max));
        input.dispatchEvent(new Event("input", { bubbles: true }));
    });

    wrapper.appendChild(minus);
    wrapper.appendChild(input);
    wrapper.appendChild(plus);
    return { wrapper, input };
}

function buildRating(field) {
    const wrapper = document.createElement("div");
    wrapper.className = "rating";

    const input = document.createElement("input");
    input.type = "range";
    input.min = field.min || 1;
    input.max = field.max || 5;
    input.step = field.step || 1;
    input.value = input.min;

    const output = document.createElement("output");
    output.textContent = input.value;
    input.addEventListener("input", () => {
        output.textContent = input.value;
    });

    wrapper.appendChild(input);
    wrapper.appendChild(output);
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
        if (field.type === "checkbox") {
            input.checked = Boolean(value);
            return;
        }
        input.value = value;
        if (field.type === "rating") {
            const output = input.parentElement?.querySelector("output");
            if (output) {
                output.textContent = input.value;
            }
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
        input.value = field.type === "counter" && field.min !== undefined && field.min !== null ? field.min : "";
        if (field.type === "rating") {
            const output = input.parentElement?.querySelector("output");
            if (output) {
                output.textContent = input.min || "0";
            }
        }
    });
}

function setFormEnabled(form, notice, enabled) {
    if (notice) {
        notice.classList.toggle("hidden", enabled);
    }
    const inputs = form.querySelectorAll("input, select, textarea, button");
    inputs.forEach((input) => {
        input.disabled = !enabled;
    });
}
