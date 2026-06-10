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
        const teamSelect = document.getElementById("team-select");
        const matchSelect = document.getElementById("match-select");
        const timezoneBadge = document.getElementById("timezone-badge");
        const eventCodeInput = document.getElementById("event-code-input");
        const loadEventBtn = document.getElementById("load-event-btn");
        const clearButton = document.getElementById("scout-clear");
        const formBlocked = document.getElementById("form-blocked");
        const pointsPreviewCard = document.getElementById("points-preview");

        timezoneBadge.textContent = settings.timezone;

        let entryCache = [];
        let matches = [];

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

        if (clearButton) {
            clearButton.addEventListener("click", () => {
                clearFormFields(fields, form);
                updatePointsPreview(fields, form, pointsPreview);
            });
        }

        const exportJsonBtn = document.getElementById("scout-export-json");
        if (exportJsonBtn) {
            exportJsonBtn.addEventListener("click", () => {
                if (!teamSelect.value || !matchSelect.value || !currentEventKey) {
                    Obsidianscout.showToast("Select a team, match, and event", "error");
                    return;
                }
                const payload = buildPayload(config.fields, form);
                if (!payload) return;
                payload.eventKey = currentEventKey;
                payload.targetTeamNumber = Number(teamSelect.value);
                payload.matchKey = matchSelect.value;
                const selectedMatch = matchSelect.selectedOptions[0];
                const matchNumberRaw = selectedMatch ? selectedMatch.dataset.matchNumber : "";
                payload.matchNumber = matchNumberRaw ? Number(matchNumberRaw) : null;
                payload.type = "prescout-scout";

                const filename = `prescout_scout_${currentEventKey}_team${payload.targetTeamNumber}_match${payload.matchNumber || 'unknown'}.json`;
                Obsidianscout.downloadJson(payload, filename);
            });
        }

        const genQrBtn = document.getElementById("scout-gen-qr");
        if (genQrBtn) {
            genQrBtn.addEventListener("click", () => {
                if (!teamSelect.value || !matchSelect.value || !currentEventKey) {
                    Obsidianscout.showToast("Select a team, match, and event", "error");
                    return;
                }
                const payload = buildPayload(config.fields, form);
                if (!payload) return;
                payload.eventKey = currentEventKey;
                payload.targetTeamNumber = Number(teamSelect.value);
                payload.matchKey = matchSelect.value;
                const selectedMatch = matchSelect.selectedOptions[0];
                const matchNumberRaw = selectedMatch ? selectedMatch.dataset.matchNumber : "";
                payload.matchNumber = matchNumberRaw ? Number(matchNumberRaw) : null;
                payload.type = "prescout-scout";

                Obsidianscout.showQrModal(payload, "Match Prescouting", payload.targetTeamNumber, payload.matchKey);
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
            matchSelect.disabled = true;
            Obsidianscout.showToast("Syncing event data from API...", "info");

            try {
                // Determine eventKey (usually year + eventCode)
                let key = rawCode;
                if (!/^\d{4}/.test(rawCode)) {
                    key = `${settings.year}${rawCode}`;
                }
                currentEventKey = key;

                await Obsidianscout.request(`/api/prescout/sync-event?eventKey=${key}`, {
                    method: "POST"
                });

                Obsidianscout.showToast("Event schedules synced successfully!", "success");

                const dataBundle = await loadTeamsAndMatches(key, teamSelect, matchSelect, settings.timezone);
                matches = dataBundle.matches;

                teamSelect.disabled = false;
                matchSelect.disabled = false;

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

        teamSelect.addEventListener("change", async () => {
            updateMatchOptions(matchSelect, matches, settings.timezone, teamSelect.value);
            matchSelect.value = "";
            await handleSelectionChange();
        });

        matchSelect.addEventListener("change", async () => {
            await handleSelectionChange();
        });

        async function handleSelectionChange() {
            const teamValue = teamSelect.value;
            const matchValue = matchSelect.value;
            const ready = Boolean(teamValue && matchValue && currentEventKey);
            setFormEnabled(form, formBlocked, pointsPreviewCard, ready);

            clearFormFields(fields, form);
            updatePointsPreview(fields, form, pointsPreview);

            if (!ready) {
                return;
            }

            const teamNumber = Number(teamValue);
            const entry = findEntry(entryCache, currentEventKey, teamNumber, matchValue);
            if (entry) {
                applyEntryToForm(entry, fields, form);
                updatePointsPreview(fields, form, pointsPreview);
            }
        }

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            submitButton.disabled = true;

            if (!teamSelect.value || !matchSelect.value || !currentEventKey) {
                Obsidianscout.showToast("Select both a team and a match", "error");
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
            payload.matchKey = matchSelect.value;
            const selectedMatch = matchSelect.selectedOptions[0];
            const matchNumberRaw = selectedMatch ? selectedMatch.dataset.matchNumber : "";
            payload.matchNumber = matchNumberRaw ? Number(matchNumberRaw) : null;

            try {
                await Obsidianscout.request("/api/prescout/scouting", {
                    method: "POST",
                    json: {
                        data: payload
                    }
                });
                Obsidianscout.showToast("Prescout entry saved", "success");
                entryCache = await loadEntryCache();
            } catch (error) {
                Obsidianscout.showToast(error.message || "Failed to save entry", "error");
            } finally {
                submitButton.disabled = false;
            }
        });

    } catch (err) {
        console.error(err);
        Obsidianscout.showRetryButton(mainContentWrapper, "Failed to load config: " + err.message, () => initPrescoutScout(me));
    }
}

async function loadTeamsAndMatches(eventKey, teamSelect, matchSelect, timezone) {
    const teams = await Obsidianscout.request(`/api/teams?eventKey=${eventKey}`);
    const matches = await Obsidianscout.request(`/api/matches?eventKey=${eventKey}`);

    teamSelect.innerHTML = "";
    const teamPlaceholder = document.createElement("option");
    teamPlaceholder.value = "";
    teamPlaceholder.textContent = "Select team";
    teamSelect.appendChild(teamPlaceholder);

    teams.forEach((team) => {
        const option = document.createElement("option");
        option.value = team.teamNumber;
        const displayNum = Obsidianscout.formatTeam(team.teamKey, team.teamNumber);
        option.textContent = `${displayNum} ${team.nickname || team.name || ""}`.trim();
        teamSelect.appendChild(option);
    });

    updateMatchOptions(matchSelect, matches, timezone, teamSelect.value);

    return { teams, matches };
}

function updateMatchOptions(matchSelect, matches, timezone, selectedTeam) {
    matchSelect.innerHTML = "";
    const matchPlaceholder = document.createElement("option");
    matchPlaceholder.value = "";
    matchPlaceholder.textContent = "Select match";
    matchSelect.appendChild(matchPlaceholder);

    const teamNumber = selectedTeam ? Number(selectedTeam) : null;
    const teamKey = teamNumber ? `frc${teamNumber}` : null;
    let filtered = matches;
    if (teamKey) {
        filtered = matches.filter((match) =>
            matchHasTeam(match.redTeams, teamKey) || matchHasTeam(match.blueTeams, teamKey)
        );
    }

    filtered.forEach((match) => {
        const option = document.createElement("option");
        option.value = match.matchKey;
        option.dataset.matchNumber = match.matchNumber || "";
        const timeLabel = Obsidianscout.formatTimestamp(match.scheduledTime, timezone);
        const matchLabel = match.label || `${match.compLevel.toUpperCase()} ${match.matchNumber || ""}`;
        const redTeams = formatTeamList(match.redTeams);
        const blueTeams = formatTeamList(match.blueTeams);
        const teamsLabel = redTeams || blueTeams ? ` | R: ${redTeams} | B: ${blueTeams}` : "";
        const fullLabel = `${matchLabel} ${timeLabel}${teamsLabel}`.trim();
        option.textContent = truncateLabel(fullLabel, 110);
        option.title = fullLabel;
        matchSelect.appendChild(option);
    });
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
    return teams.some(key => {
        if (key === teamKey) return true;
        const parts = key.split('/');
        return parts.some(part => {
            const norm = part.startsWith('frc') ? part : `frc${part}`;
            return norm === teamKey;
        });
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

function injectSections(fields) {
    if (!fields.length) {
        return fields;
    }
    if (fields.some((field) => field.type === "section")) {
        return fields;
    }

    const result = [];
    const inserted = new Set();
    fields.forEach((field) => {
        const phase = getFieldPhase(field);
        if (!inserted.has("auto") && phase === "auto") {
            result.push({ id: "sectionAuto", label: "Auto", type: "section" });
            inserted.add("auto");
        }
        if (!inserted.has("teleop") && phase === "teleop") {
            result.push({ id: "sectionTeleop", label: "Teleop", type: "section" });
            inserted.add("teleop");
        }
        if (!inserted.has("endgame") && phase === "endgame") {
            result.push({ id: "sectionEndgame", label: "Endgame", type: "section" });
            inserted.add("endgame");
        }
        result.push(field);
    });
    return result;
}

function getFieldPhase(field) {
    if (!field) {
        return "";
    }
    if (field.phase) {
        const p = String(field.phase).toLowerCase();
        if (p === "postmatch" || p === "post-match" || p === "post match" || p === "post") return "postmatch";
        return p;
    }
    const id = String(field.id || "").toLowerCase();
    if (id.startsWith("auto")) return "auto";
    if (id.startsWith("teleop")) return "teleop";
    if (id.startsWith("endgame")) return "endgame";
    return "postmatch";
}

function buildCounter(field) {
    const wrapper = document.createElement("div");
    wrapper.className = "counter";
    const minus = document.createElement("button");
    minus.type = "button";
    minus.textContent = "-";

    const input = document.createElement("input");
    input.type = "number";
    input.value = field.min || 0;
    applyNumberBounds(input, field);

    const plus = document.createElement("button");
    plus.type = "button";
    plus.textContent = "+";

    minus.addEventListener("click", () => {
        const step = field.step || 1;
        input.value = String(Math.max(Number(input.value || 0) - step, field.min || 0));
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
        input.value = "";
        if (field.type === "rating") {
            const output = input.parentElement?.querySelector("output");
            if (output) {
                output.textContent = input.min || "0";
            }
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
        if (enabled) {
            switchTab("auto");
        }
    }
    if (pointsCard) {
        pointsCard.classList.toggle("hidden", !enabled);
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
