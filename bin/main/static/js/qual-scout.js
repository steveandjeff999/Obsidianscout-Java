let originalMainContentHTML = "";
let mainContentWrapper = null;
let mainContent = null;

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
        mainContentWrapper.id = "qual-scout-wrapper";
        siblings.forEach(child => mainContentWrapper.appendChild(child));
        mainContent.appendChild(mainContentWrapper);
        originalMainContentHTML = mainContentWrapper.innerHTML;
        await loadQualScoutPageData(me);
    }
});

async function loadQualScoutPageData(me) {
    if (!mainContentWrapper) return;
    Obsidianscout.showLoadingSpinner(mainContentWrapper, "Loading qualitative scouting form...");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        const eventKey = Obsidianscout.resolveEventKey(settings);
        
        const config = await Obsidianscout.request("/api/qual-config");

        // Restore original HTML
        mainContentWrapper.innerHTML = originalMainContentHTML;

        // Re-query elements
        const form = document.getElementById("scouting-form");
        if (form) {
            form.noValidate = true;
        }
        const fieldContainer = document.getElementById("form-fields");
        const submitButton = document.getElementById("scout-submit");
        const teamSelect = document.getElementById("team-select");
        const matchSelect = document.getElementById("match-select");
        const timezoneBadge = document.getElementById("timezone-badge");
        const eventBadge = document.getElementById("event-badge");
        const clearButton = document.getElementById("scout-clear");
        const formBlocked = document.getElementById("form-blocked");

        timezoneBadge.textContent = settings.timezone;
        if (eventBadge) {
            eventBadge.textContent = eventKey || "Not set";
        }

        let entryCache = [];
        let matches = [];

        const dataBundle = await loadTeamsAndMatches(eventKey, teamSelect, matchSelect, settings.timezone);
        matches = dataBundle.matches;

        entryCache = await loadEntryCache();

        teamSelect.addEventListener("change", async () => {
            updateMatchOptions(matchSelect, matches, settings.timezone, teamSelect.value);
            matchSelect.value = "";
            await handleSelectionChange();
        });

        matchSelect.addEventListener("change", async () => {
            await handleSelectionChange();
        });

        const reserved = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);
        const fields = injectSections(config.fields || []);
        fields
            .filter((field) => !reserved.has(field.id))
            .forEach((field) => {
                const node = buildField(field);
                fieldContainer.appendChild(node);
            });

        if (config.enableRobotRoleCollection) {
            const roleNode = buildRobotRoleCollectionField();
            fieldContainer.appendChild(roleNode);
        }

        if (clearButton) {
            clearButton.addEventListener("click", () => {
                clearFormFields(fields, form);
            });
        }

        const exportJsonBtn = document.getElementById("scout-export-json");
        if (exportJsonBtn) {
            exportJsonBtn.addEventListener("click", () => {
                if (!teamSelect.value || !matchSelect.value) {
                    Obsidianscout.showToast("Select both a team and a match", "error");
                    return;
                }
                const payload = buildPayload(config.fields, form);
                if (!payload) return;
                payload.eventKey = eventKey;
                payload.targetTeamNumber = Number(teamSelect.value);
                payload.matchKey = matchSelect.value;
                const selectedMatch = matchSelect.selectedOptions[0];
                const matchNumberRaw = selectedMatch ? selectedMatch.dataset.matchNumber : "";
                payload.matchNumber = matchNumberRaw ? Number(matchNumberRaw) : null;
                payload.type = "qual-scout";

                const filename = `qual_${eventKey || 'event'}_team${payload.targetTeamNumber}_match${payload.matchNumber || 'unknown'}.json`;
                Obsidianscout.downloadJson(payload, filename);
            });
        }

        const genQrBtn = document.getElementById("scout-gen-qr");
        if (genQrBtn) {
            genQrBtn.addEventListener("click", () => {
                if (!teamSelect.value || !matchSelect.value) {
                    Obsidianscout.showToast("Select both a team and a match", "error");
                    return;
                }
                const payload = buildPayload(config.fields, form);
                if (!payload) return;
                payload.eventKey = eventKey;
                payload.targetTeamNumber = Number(teamSelect.value);
                payload.matchKey = matchSelect.value;
                const selectedMatch = matchSelect.selectedOptions[0];
                const matchNumberRaw = selectedMatch ? selectedMatch.dataset.matchNumber : "";
                payload.matchNumber = matchNumberRaw ? Number(matchNumberRaw) : null;
                payload.type = "qual-scout";

                Obsidianscout.showQrModal(payload, "Qualitative Scouting", payload.targetTeamNumber, payload.matchKey);
            });
        }

        const saveOfflineButton = document.getElementById("scout-save-offline-btn");
        if (saveOfflineButton) {
            saveOfflineButton.addEventListener("click", () => {
                if (!teamSelect.value || !matchSelect.value) {
                    Obsidianscout.showToast("Select both a team and a match", "error");
                    return;
                }

                const payload = buildPayload(config.fields, form);
                if (!payload) return;

                payload.eventKey = eventKey;
                payload.targetTeamNumber = teamSelect.value ? Number(teamSelect.value) : null;
                payload.matchKey = matchSelect.value || null;
                const selectedMatch = matchSelect.value ? matchSelect.selectedOptions[0] : null;
                const matchNumberRaw = selectedMatch ? selectedMatch.dataset.matchNumber : "";
                payload.matchNumber = matchNumberRaw ? Number(matchNumberRaw) : null;

                const pending = JSON.parse(Obsidianscout.safeGetItem("pending_qualitative_entries") || "[]");
                pending.push({
                    data: payload,
                    createdAt: new Date().toISOString(),
                    ownerTeamNumber: me.teamNumber,
                    pending: true
                });
                Obsidianscout.safeSetItem("pending_qualitative_entries", JSON.stringify(pending));

                Obsidianscout.showToast("Saved locally (Offline mode)", "success");
                Obsidianscout.updateConnectionStatus();
                window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));

                clearFormFields(fields, form);
                handleSelectionChange();
            });
        }

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            submitButton.disabled = true;

            if (!teamSelect.value || !matchSelect.value) {
                Obsidianscout.showToast("Select both a team and a match", "error");
                submitButton.disabled = false;
                return;
            }

            const payload = buildPayload(config.fields, form);
            if (!payload) {
                submitButton.disabled = false;
                return;
            }

            payload.eventKey = eventKey;
            payload.targetTeamNumber = teamSelect.value ? Number(teamSelect.value) : null;
            payload.matchKey = matchSelect.value || null;
            const selectedMatch = matchSelect.value ? matchSelect.selectedOptions[0] : null;
            const matchNumberRaw = selectedMatch ? selectedMatch.dataset.matchNumber : "";
            payload.matchNumber = matchNumberRaw ? Number(matchNumberRaw) : null;

            try {
                await Obsidianscout.request("/api/qual-scouting", {
                    method: "POST",
                    json: {
                        data: payload
                    }
                });
                Obsidianscout.showToast("Entry saved", "success");
                entryCache = await loadEntryCache();
                window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));
            } catch (error) {
                if (!navigator.onLine || error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
                    const pending = JSON.parse(Obsidianscout.safeGetItem("pending_qualitative_entries") || "[]");
                    pending.push({
                        data: payload,
                        createdAt: new Date().toISOString(),
                        ownerTeamNumber: me.teamNumber,
                        pending: true
                    });
                    Obsidianscout.safeSetItem("pending_qualitative_entries", JSON.stringify(pending));

                    Obsidianscout.showToast("Saved locally (Offline mode)", "success");
                    Obsidianscout.updateConnectionStatus();
                    window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));

                    clearFormFields(fields, form);
                    handleSelectionChange();
                } else {
                    Obsidianscout.showToast(error.message || "Failed to save", "error");
                }
            } finally {
                submitButton.disabled = false;
            }
        });

        async function handleSelectionChange() {
            const teamValue = teamSelect.value;
            const matchValue = matchSelect.value;
            const ready = Boolean(teamValue && matchValue);
            setFormEnabled(form, formBlocked, ready);

            clearFormFields(fields, form);

            if (!ready) {
                return;
            }

            const teamNumber = Number(teamValue);
            const entry = findEntry(entryCache, eventKey, teamNumber, matchValue);
            if (entry) {
                applyEntryToForm(entry, fields, form);
            }
        }
    } catch (error) {
        console.error("Failed to load qual scout page:", error);
        Obsidianscout.showRetryButton(mainContentWrapper, "Failed to load qualitative scouting form: " + error.message, () => loadQualScoutPageData(me));
    }
}

async function loadTeamsAndMatches(eventKey, teamSelect, matchSelect, timezone) {
    const teams = eventKey ? await Obsidianscout.request(`/api/teams?eventKey=${eventKey}`) : [];
    const matches = eventKey ? await Obsidianscout.request(`/api/matches?eventKey=${eventKey}`) : [];

    teamSelect.innerHTML = "";
    const teamPlaceholder = document.createElement("option");
    teamPlaceholder.value = "";
    teamPlaceholder.textContent = (window.Obsidianscout && Obsidianscout.t) ? Obsidianscout.t('scout.select_team', 'Select team') : 'Select team';
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
    matchPlaceholder.textContent = (window.Obsidianscout && Obsidianscout.t) ? Obsidianscout.t('scout.select_match', 'Select match') : 'Select match';
    matchSelect.appendChild(matchPlaceholder);

    const teamNumber = selectedTeam ? Number(selectedTeam) : null;
    const teamKey = teamNumber ? `frc${teamNumber}` : null;
    let filtered = matches;
    if (teamKey) {
        const byTeam = matches.filter((match) =>
            matchHasTeam(match.redTeams, teamKey) || matchHasTeam(match.blueTeams, teamKey)
        );
        if (byTeam.length) {
            filtered = byTeam;
        }
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
        return String(field.phase).toLowerCase();
    }
    const id = String(field.id || "").toLowerCase();
    if (id.startsWith("auto")) return "auto";
    if (id.startsWith("teleop")) return "teleop";
    if (id.startsWith("endgame")) return "endgame";
    return "";
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

    // Role collection payload
    const roleContainer = form.querySelector("#robot-role-collection-container");
    if (roleContainer) {
        const checked = [];
        roleContainer.querySelectorAll(".robot-role-checkbox:checked").forEach(cb => {
            checked.push(cb.value);
        });
        payload.robotRoles = checked;
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
        const entries = await Obsidianscout.request("/api/qual-scouting");
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

    const roleContainer = form.querySelector("#robot-role-collection-container");
    if (roleContainer) {
        roleContainer.querySelectorAll(".robot-role-checkbox").forEach(cb => {
            cb.checked = false;
            cb.dispatchEvent(new Event("change"));
        });
        const roles = entry.data.robotRoles || [];
        roles.forEach(role => {
            const cb = roleContainer.querySelector(`.robot-role-checkbox[value='${role}']`);
            if (cb) {
                cb.checked = true;
                cb.dispatchEvent(new Event("change"));
            }
        });
    }
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

    const roleContainer = form.querySelector("#robot-role-collection-container");
    if (roleContainer) {
        roleContainer.querySelectorAll(".robot-role-checkbox").forEach(cb => {
            cb.checked = false;
            cb.dispatchEvent(new Event("change"));
        });
    }
}

function buildRobotRoleCollectionField() {
    const wrapper = document.createElement("div");
    wrapper.id = "robot-role-collection-container";
    wrapper.className = "form-section mt-18";

    const title = document.createElement("h3");
    title.textContent = "Robot Roles";
    wrapper.appendChild(title);

    const rolesContainer = document.createElement("div");
    rolesContainer.className = "row wrap gap-8 mt-12";

    const roles = [
        { name: "Cycling", val: "Cycling" },
        { name: "Stealing", val: "Stealing" },
        { name: "Scoring", val: "Scoring" },
        { name: "Feeding", val: "Feeding" },
        { name: "Defending", val: "Defending" },
        { name: "N/C", val: "N/C" }
    ];

    roles.forEach(r => {
        const label = document.createElement("label");
        label.className = "checkbox-btn";
        label.style.display = "inline-flex";
        label.style.alignItems = "center";
        label.style.gap = "6px";
        label.style.padding = "8px 16px";
        label.style.borderRadius = "20px";
        label.style.border = "1px solid var(--border)";
        label.style.cursor = "pointer";
        label.style.userSelect = "none";
        label.style.fontSize = "13px";
        label.style.fontWeight = "600";
        label.style.background = "var(--surface-3)";

        const input = document.createElement("input");
        input.type = "checkbox";
        input.value = r.val;
        input.className = "robot-role-checkbox hidden";
        input.style.display = "none";

        const text = document.createElement("span");
        text.textContent = r.name;

        label.appendChild(input);
        label.appendChild(text);

        const updateStyle = () => {
            if (input.checked) {
                label.style.background = "var(--primary)";
                label.style.color = "var(--on-primary)";
                label.style.borderColor = "var(--primary)";
            } else {
                label.style.background = "var(--surface-3)";
                label.style.color = "var(--text)";
                label.style.borderColor = "var(--border)";
            }
        };

        input.addEventListener("change", updateStyle);
        rolesContainer.appendChild(label);
    });

    wrapper.appendChild(rolesContainer);
    return wrapper;
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