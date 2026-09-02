function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

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
        const siblings = Array.from(mainContent.children).filter(child => !child.classList.contains("banner-container"));
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
        const teams = dataBundle.teams;

        entryCache = await loadEntryCache();

        let currentScope = "team"; // "team" | "red" | "blue" | "both"
        let currentAllianceTeams = [];

        const scopeButtons = document.querySelectorAll(".scope-btn");
        const teamFieldContainer = document.getElementById("team-field-container");

        function parseTeamNumber(key) {
            if (!key) return null;
            const cleaned = String(key).replace(/^(frc|ftc)/i, '').trim();
            const num = parseInt(cleaned, 10);
            return isNaN(num) ? null : num;
        }

        function getAllianceTeamsForMatch(match, scope) {
            if (!match) return [];
            const list = [];
            if (scope === "red" || scope === "both") {
                (match.redTeams || []).forEach((tKey, idx) => {
                    const num = parseTeamNumber(tKey);
                    if (num) {
                        const tObj = teams.find(t => t.teamNumber === num);
                        list.push({
                            teamNumber: num,
                            teamKey: tKey,
                            alliance: "red",
                            posLabel: `Red ${idx + 1}`,
                            nickname: tObj ? (tObj.nickname || tObj.name || "") : ""
                        });
                    }
                });
            }
            if (scope === "blue" || scope === "both") {
                (match.blueTeams || []).forEach((tKey, idx) => {
                    const num = parseTeamNumber(tKey);
                    if (num) {
                        const tObj = teams.find(t => t.teamNumber === num);
                        list.push({
                            teamNumber: num,
                            teamKey: tKey,
                            alliance: "blue",
                            posLabel: `Blue ${idx + 1}`,
                            nickname: tObj ? (tObj.nickname || tObj.name || "") : ""
                        });
                    }
                });
            }
            return list;
        }

        const reserved = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);
        const fields = injectSections(config.fields || []);

        function renderSingleTeamView() {
            fieldContainer.innerHTML = "";
            fieldContainer.style.display = "";
            fields
                .filter((field) => !reserved.has(field.id))
                .forEach((field) => {
                    const node = buildField(field);
                    if (node) fieldContainer.appendChild(node);
                });

            if (config.enableRobotRoleCollection) {
                const roleNode = buildRobotRoleCollectionField();
                fieldContainer.appendChild(roleNode);
            }
        }

        function renderAllianceAllTeamsView() {
            fieldContainer.innerHTML = "";
            fieldContainer.style.display = "block";
            if (!currentAllianceTeams.length) return;

            const gridContainer = document.createElement("div");
            gridContainer.className = "alliance-teams-grid";

            currentAllianceTeams.forEach(team => {
                const isRed = team.alliance === "red";
                const teamCard = document.createElement("div");
                teamCard.className = "card alliance-team-card";
                teamCard.dataset.teamNumber = team.teamNumber;
                teamCard.style.background = "rgba(255, 255, 255, 0.03)";
                teamCard.style.border = isRed ? "1px solid rgba(239, 68, 68, 0.35)" : "1px solid rgba(59, 130, 246, 0.35)";
                teamCard.style.borderRadius = "var(--radius, 14px)";
                teamCard.style.overflow = "hidden";
                teamCard.style.boxShadow = isRed ? "0 4px 16px rgba(239, 68, 68, 0.08)" : "0 4px 16px rgba(59, 130, 246, 0.08)";

                const header = document.createElement("div");
                header.style.cssText = `display:flex;align-items:center;justify-content:space-between;padding:12px 18px;background:${isRed ? 'rgba(239,68,68,0.12)' : 'rgba(59,130,246,0.12)'};border-bottom:1px solid ${isRed ? 'rgba(239,68,68,0.25)' : 'rgba(59,130,246,0.25)'};`;
                const nick = team.nickname ? `<span style="font-weight:normal;opacity:0.85;margin-left:4px;">(${team.nickname})</span>` : "";
                header.innerHTML = `
                    <div style="display:flex;align-items:center;gap:10px;">
                        <span class="badge" style="background:${isRed ? '#ef4444' : '#3b82f6'};color:#fff;font-weight:bold;font-size:12px;padding:3px 8px;border-radius:6px;">${team.posLabel}</span>
                        <span style="font-size:15px;font-weight:bold;color:var(--text);">Team ${team.teamNumber}${nick}</span>
                    </div>
                `;
                teamCard.appendChild(header);

                const body = document.createElement("div");
                body.className = "form-grid";
                body.style.padding = "16px";

                fields
                    .filter((field) => !reserved.has(field.id))
                    .forEach((field) => {
                        const node = buildField(field);
                        if (node) body.appendChild(node);
                    });

                if (config.enableRobotRoleCollection) {
                    body.appendChild(buildRobotRoleCollectionField());
                }

                teamCard.appendChild(body);

                // Populate with cached entry if present
                const cached = findEntry(entryCache, eventKey, team.teamNumber, matchSelect.value);
                if (cached) {
                    applyEntryToForm(cached, fields, teamCard);
                }

                gridContainer.appendChild(teamCard);
            });

            fieldContainer.appendChild(gridContainer);
        }

        async function refreshAllianceState() {
            const selectedMatchKey = matchSelect.value;
            const selectedMatch = matches.find(m => m.matchKey === selectedMatchKey);

            if (currentScope === "both") {
                submitButton.textContent = Obsidianscout.t("qual_scout.save_both_alliances", "Save Both Alliances (6 teams)");
            } else {
                submitButton.textContent = currentScope === "red"
                    ? Obsidianscout.t("qual_scout.save_red_alliance", "Save Red Alliance (3 teams)")
                    : Obsidianscout.t("qual_scout.save_blue_alliance", "Save Blue Alliance (3 teams)");
            }

            if (!selectedMatch) {
                currentAllianceTeams = [];
                fieldContainer.innerHTML = "";
                formBlocked.textContent = Obsidianscout.t('qual_scout.select_match_to_load_alliance', 'Select a match above to start qualitative alliance scouting.');
                setFormEnabled(form, formBlocked, false);
                return;
            }

            currentAllianceTeams = getAllianceTeamsForMatch(selectedMatch, currentScope);
            if (!currentAllianceTeams.length) {
                currentAllianceTeams = [];
                fieldContainer.innerHTML = "";
                formBlocked.textContent = Obsidianscout.t('qual_scout.no_teams_in_match', 'No teams found for this match alliance.');
                setFormEnabled(form, formBlocked, false);
                return;
            }

            setFormEnabled(form, formBlocked, true);
            renderAllianceAllTeamsView();
        }

        function collectAllianceEntries() {
            const selectedMatch = matches.find(m => m.matchKey === matchSelect.value);
            const matchNumberRaw = selectedMatch ? selectedMatch.matchNumber : "";
            const matchNum = matchNumberRaw ? Number(matchNumberRaw) : null;
            const entries = [];
            for (const t of currentAllianceTeams) {
                const teamCard = fieldContainer.querySelector(`[data-team-number='${t.teamNumber}']`);
                if (!teamCard) continue;
                const data = buildPayload(config.fields, teamCard);
                if (!data) return null; // Validation failed inside buildPayload
                entries.push({
                    ...data,
                    eventKey: eventKey,
                    targetTeamNumber: t.teamNumber,
                    matchKey: matchSelect.value,
                    matchNumber: matchNum,
                    type: "qual-scout"
                });
            }
            return entries;
        }

        if (scopeButtons && scopeButtons.length > 0) {
            scopeButtons.forEach(btn => {
                btn.addEventListener("click", async () => {
                    const newScope = btn.dataset.scope;
                    if (newScope === currentScope) return;

                    currentScope = newScope;
                    scopeButtons.forEach(b => {
                        const isActive = b.dataset.scope === currentScope;
                        b.classList.toggle("active", isActive);
                        b.classList.toggle("secondary", !isActive);
                    });

                    if (currentScope === "team") {
                        if (teamFieldContainer) teamFieldContainer.classList.remove("hidden");
                        submitButton.textContent = Obsidianscout.t("qual_scout.save_entry", "Save entry");
                        renderSingleTeamView();
                        updateMatchOptions(matchSelect, matches, settings.timezone, teamSelect.value);
                        await handleSelectionChange();
                    } else {
                        if (teamFieldContainer) teamFieldContainer.classList.add("hidden");
                        const currentMatchVal = matchSelect.value;
                        updateMatchOptions(matchSelect, matches, settings.timezone, null);
                        if (currentMatchVal) matchSelect.value = currentMatchVal;
                        await refreshAllianceState();
                    }
                });
            });
        }

        teamSelect.addEventListener("change", async () => {
            updateMatchOptions(matchSelect, matches, settings.timezone, teamSelect.value);
            matchSelect.value = "";
            await handleSelectionChange();
        });

        matchSelect.addEventListener("change", async () => {
            if (currentScope === "team") {
                await handleSelectionChange();
            } else {
                await refreshAllianceState();
            }
        });

        // Initialize single team fields by default
        renderSingleTeamView();

        if (clearButton) {
            clearButton.addEventListener("click", () => {
                if (!confirm(Obsidianscout.t("qual_scout.confirm_clear", "Are you sure you want to clear the form? All entered data will be reset."))) {
                    return;
                }
                if (currentScope === "team") {
                    clearFormFields(fields, fieldContainer);
                } else {
                    currentAllianceTeams.forEach(t => {
                        const teamCard = fieldContainer.querySelector(`[data-team-number='${t.teamNumber}']`);
                        if (teamCard) clearFormFields(fields, teamCard);
                    });
                }
            });
        }

        const exportJsonBtn = document.getElementById("scout-export-json");
        if (exportJsonBtn) {
            exportJsonBtn.addEventListener("click", () => {
                if (currentScope === "team") {
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
                } else {
                    if (!matchSelect.value || !currentAllianceTeams.length) {
                        Obsidianscout.showToast("Select a match to export alliance qualitative data", "error");
                        return;
                    }
                    const entriesList = collectAllianceEntries();
                    if (!entriesList || !entriesList.length) return;
                    const selectedMatch = matches.find(m => m.matchKey === matchSelect.value);
                    const matchNumberRaw = selectedMatch ? selectedMatch.matchNumber : "";
                    const matchNum = matchNumberRaw ? Number(matchNumberRaw) : null;
                    const allianceExport = {
                        type: "qual-alliance",
                        scope: currentScope,
                        matchKey: matchSelect.value,
                        matchNumber: matchNum,
                        eventKey: eventKey,
                        entries: entriesList
                    };
                    const filename = `qual_${eventKey || 'event'}_${currentScope}_alliance_match${matchNum || 'unknown'}.json`;
                    Obsidianscout.downloadJson(allianceExport, filename);
                }
            });
        }

        const genQrBtn = document.getElementById("scout-gen-qr");
        if (genQrBtn) {
            genQrBtn.addEventListener("click", () => {
                if (currentScope === "team") {
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
                } else {
                    if (!matchSelect.value || !currentAllianceTeams.length) {
                        Obsidianscout.showToast("Select a match to generate alliance QR", "error");
                        return;
                    }
                    const entriesList = collectAllianceEntries();
                    if (!entriesList || !entriesList.length) return;
                    const selectedMatch = matches.find(m => m.matchKey === matchSelect.value);
                    const matchNumberRaw = selectedMatch ? selectedMatch.matchNumber : "";
                    const matchNum = matchNumberRaw ? Number(matchNumberRaw) : null;
                    const allianceQrPayload = {
                        type: "qual-alliance",
                        scope: currentScope,
                        matchKey: matchSelect.value,
                        matchNumber: matchNum,
                        eventKey: eventKey,
                        entries: entriesList
                    };
                    const scopeTitle = currentScope === "both" ? "Both Alliances" : `${currentScope.toUpperCase()} Alliance`;
                    const allTeamNums = currentAllianceTeams.map(t => t.teamNumber).join(", ");
                    Obsidianscout.showQrModal(allianceQrPayload, `Qual Alliance (${scopeTitle})`, allTeamNums, matchSelect.value);
                }
            });
        }

        const saveOfflineButton = document.getElementById("scout-save-offline-btn");
        if (saveOfflineButton) {
            saveOfflineButton.addEventListener("click", () => {
                if (currentScope === "team") {
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
                } else {
                    if (!matchSelect.value || !currentAllianceTeams.length) {
                        Obsidianscout.showToast("Select a match first", "error");
                        return;
                    }
                    const entriesToSave = collectAllianceEntries();
                    if (!entriesToSave || !entriesToSave.length) return;

                    const pending = JSON.parse(Obsidianscout.safeGetItem("pending_qualitative_entries") || "[]");
                    entriesToSave.forEach(payload => {
                        pending.push({
                            data: payload,
                            createdAt: new Date().toISOString(),
                            ownerTeamNumber: me.teamNumber,
                            pending: true
                        });
                    });
                    Obsidianscout.safeSetItem("pending_qualitative_entries", JSON.stringify(pending));
                    Obsidianscout.showToast(`Saved locally (Offline mode) for ${entriesToSave.length} teams`, "success");
                    Obsidianscout.updateConnectionStatus();
                    window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));
                }
            });
        }

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            Obsidianscout.setButtonLoading(submitButton, true, t('scout.saving', 'Saving entry...'));

            if (currentScope === "team") {
                if (!teamSelect.value || !matchSelect.value) {
                    Obsidianscout.showToast("Select both a team and a match", "error");
                    Obsidianscout.setButtonLoading(submitButton, false);
                    return;
                }

                const payload = buildPayload(config.fields, form);
                if (!payload) {
                    Obsidianscout.setButtonLoading(submitButton, false);
                    return;
                }

                payload.eventKey = eventKey;
                payload.targetTeamNumber = teamSelect.value ? Number(teamSelect.value) : null;
                payload.matchKey = matchSelect.value || null;
                const selectedMatch = matchSelect.value ? matchSelect.selectedOptions[0] : null;
                const matchNumberRaw = selectedMatch ? selectedMatch.dataset.matchNumber : "";
                payload.matchNumber = matchNumberRaw ? Number(matchNumberRaw) : null;

                try {
                    const response = await Obsidianscout.request("/api/qual-scouting", {
                        method: "POST",
                        json: {
                            data: payload
                        }
                    });
                    Obsidianscout.showToast("Entry saved", "success");
                    
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
                    Obsidianscout.safeSetItem("cache:/api/qual-scouting", JSON.stringify(entryCache));
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
                    Obsidianscout.setButtonLoading(submitButton, false);
                }
            } else {
                // Alliance Scope Submit
                if (!matchSelect.value || !currentAllianceTeams.length) {
                    Obsidianscout.showToast("Select a match to save alliance data", "error");
                    Obsidianscout.setButtonLoading(submitButton, false);
                    return;
                }

                const entriesToSave = collectAllianceEntries();
                if (!entriesToSave || !entriesToSave.length) {
                    Obsidianscout.setButtonLoading(submitButton, false);
                    return;
                }

                try {
                    const response = await Obsidianscout.request("/api/qual-scouting/batch", {
                        method: "POST",
                        json: { entries: entriesToSave }
                    });
                    Obsidianscout.showToast(`Saved entries for ${entriesToSave.length} teams`, "success");

                    entriesToSave.forEach(payload => {
                        const newEntry = {
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
                    });

                    Obsidianscout.safeSetItem("cache:/api/qual-scouting", JSON.stringify(entryCache));
                    window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));
                } catch (error) {
                    if (!navigator.onLine || error.message.includes("Failed to fetch") || error.message.includes("NetworkError")) {
                        const pending = JSON.parse(Obsidianscout.safeGetItem("pending_qualitative_entries") || "[]");
                        entriesToSave.forEach(payload => {
                            pending.push({
                                data: payload,
                                createdAt: new Date().toISOString(),
                                ownerTeamNumber: me.teamNumber,
                                pending: true
                            });
                        });
                        Obsidianscout.safeSetItem("pending_qualitative_entries", JSON.stringify(pending));

                        Obsidianscout.showToast(`Saved locally (Offline mode) for ${entriesToSave.length} teams`, "success");
                        Obsidianscout.updateConnectionStatus();
                        window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));
                    } else {
                        Obsidianscout.showToast(error.message || "Failed to save", "error");
                    }
                } finally {
                    Obsidianscout.setButtonLoading(submitButton, false);
                }
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
    const teamKey = teamNumber ? `${Obsidianscout.getProgramPrefix()}${teamNumber}` : null;
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
        case "image":
        case "image_upload":
        case "photo":
            ({ wrapper: input, input: actualInput } = buildImageUpload(field));
            break;
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

    // Role collection payload
    const roleContainer = form.querySelector(".robot-role-collection-container") || form.querySelector("#robot-role-collection-container");
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

    const roleContainer = form.querySelector(".robot-role-collection-container") || form.querySelector("#robot-role-collection-container");
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

    const roleContainer = form.querySelector(".robot-role-collection-container") || form.querySelector("#robot-role-collection-container");
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
    wrapper.className = "form-section mt-18 robot-role-collection-container";

    const title = document.createElement("h3");
    title.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t('qual_scout.robot_roles', 'Robot Roles') : 'Robot Roles';
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
        text.textContent = (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(`qual_scout.role.${r.val.toLowerCase().replace('/', '')}`, r.name) : r.name;

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