let currentEventKey = "";
let currentUser = null;
let originalTableCardHTML = "";
let tableCard = null;

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

function localize(value) {
    return (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(value) : value;
}

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }
    currentUser = me;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    const table = document.getElementById("matches-table");
    tableCard = table.closest(".card");
    originalTableCardHTML = tableCard.innerHTML;

    await initMatchesPage();
});

async function initMatchesPage() {
    Obsidianscout.showLoadingSpinner(tableCard, t("status.loading", "Loading events and matches..."));

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        const settings = settingsResponse.settings;
        currentEventKey = Obsidianscout.resolveEventKey(settings);
        const timezone = settings.timezone;

        const isAdmin = Obsidianscout.isAdmin(currentUser.role);

        // Restore HTML
        tableCard.innerHTML = originalTableCardHTML;

        // Populate event filter select dropdown
        const eventFilter = document.getElementById("event-filter");
        const events = await Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`);
        eventFilter.innerHTML = "";
        events.forEach(e => {
            const opt = document.createElement("option");
            opt.value = e.eventKey;
            opt.textContent = `${localize(e.name)} (${e.eventKey})`;
            if (e.eventKey === currentEventKey) {
                opt.selected = true;
            }
            eventFilter.appendChild(opt);
        });

        eventFilter.addEventListener("change", async () => {
            currentEventKey = eventFilter.value;
            await loadMatches(currentEventKey, timezone);
        });

        const syncButton = document.getElementById("sync-event");

        if (!isAdmin) {
            syncButton.disabled = true;
        } else {
            document.getElementById("add-match-btn").classList.remove("hidden");
            document.querySelectorAll(".admin-only").forEach(h => h.classList.remove("hidden"));

            syncButton.addEventListener("click", async () => {
                syncButton.disabled = true;
                try {
                    const response = await Obsidianscout.request("/api/integrations/sync/event", { method: "POST" });
                    Obsidianscout.showToast(response.message || t("matches.synced", "Matches synced"), "success");
                    const refreshed = await Obsidianscout.request("/api/settings");
                    currentEventKey = Obsidianscout.resolveEventKey(refreshed.settings);
                    if (eventFilter) eventFilter.value = currentEventKey;
                    await loadMatches(currentEventKey, timezone);
                } catch (error) {
                    Obsidianscout.showToast(error.message || t("matches.sync_failed", "Sync failed"), "error");
                } finally {
                    syncButton.disabled = false;
                }
            });

            await setupModal(() => currentEventKey, timezone);
        }

        await loadMatches(currentEventKey, timezone);

    } catch (error) {
        console.error("Failed to initialize matches page:", error);
        Obsidianscout.showRetryButton(tableCard, t("status.load_failed", "Failed to load page data: ") + error.message, initMatchesPage);
    }
}

async function loadMatches(eventKey, timezone) {
    const table = document.getElementById("matches-table");
    const body = table.querySelector("tbody");
    body.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 24px;"><div class="spinner" style="margin: 0 auto 12px; width: 32px; height: 32px;"></div><div>' + t("status.loading", t('matches.loading_matches', "Loading matches...")) + '</div></td></tr>';

    if (!eventKey) {
        Obsidianscout.showToast(t("matches.set_event_key", "Set an event key in settings"), "error");
        body.innerHTML = '<tr><td colspan="5" style="text-align: center; color: var(--muted);">' + t("matches.no_event", "No event selected") + '</td></tr>';
        return;
    }

    try {
        const matches = await Obsidianscout.request(`/api/matches?eventKey=${eventKey}`);
        currentMatches = matches;
        const isAdmin = currentUser && Obsidianscout.isAdmin(currentUser.role);
        const fragment = document.createDocumentFragment();

        body.innerHTML = "";

        if (matches.length === 0) {
            body.innerHTML = '<tr><td colspan="5" style="text-align: center; color: var(--muted); padding: 24px;">' + t("matches.no_matches", "No matches found for this event.") + '</td></tr>';
            return;
        }

        matches.forEach((match) => {
            const row = document.createElement("tr");
            const red = match.redTeams.map(k => Obsidianscout.formatTeam(k)).join(", ");
            const blue = match.blueTeams.map(k => Obsidianscout.formatTeam(k)).join(", ");
            const timeLabel = Obsidianscout.formatTimestamp(match.scheduledTime, timezone);
            const matchCell = document.createElement("td");
            matchCell.textContent = localize(match.label) || (match.compLevel.toUpperCase() + " " + (match.matchNumber || ""));
            const timeCell = document.createElement("td");
            timeCell.textContent = timeLabel;
            const redCell = document.createElement("td");
            redCell.textContent = red;
            const blueCell = document.createElement("td");
            blueCell.textContent = blue;

            row.appendChild(matchCell);
            row.appendChild(timeCell);
            row.appendChild(redCell);
            row.appendChild(blueCell);

            if (isAdmin) {
                const actionCell = document.createElement("td");
                actionCell.className = "admin-only";
                actionCell.style.display = "flex";
                actionCell.style.gap = "8px";

                const editButton = document.createElement("button");
                editButton.className = "btn-icon-edit";
                editButton.dataset.action = "edit";
                editButton.dataset.key = match.matchKey;
                editButton.textContent = t("common.edit", "Edit");

                const deleteButton = document.createElement("button");
                deleteButton.className = "btn-icon-edit delete";
                deleteButton.dataset.action = "delete";
                deleteButton.dataset.key = match.matchKey;
                deleteButton.style.color = "#c84b31";
                deleteButton.style.borderColor = "rgba(200, 75, 49, 0.25)";
                deleteButton.textContent = t("common.delete", "Delete");

                actionCell.appendChild(editButton);
                actionCell.appendChild(deleteButton);
                row.appendChild(actionCell);
            }
            fragment.appendChild(row);
        });

        body.appendChild(fragment);

        // Wire edit & delete buttons
        if (isAdmin) {
            body.querySelectorAll('[data-action="edit"]').forEach(btn => {
                btn.addEventListener("click", () => {
                    const key = btn.getAttribute("data-key");
                    const match = currentMatches.find(m => m.matchKey === key);
                    if (match) {
                        openEditModal(match);
                    }
                });
            });
            body.querySelectorAll('[data-action="delete"]').forEach(btn => {
                btn.addEventListener("click", async () => {
                    const key = btn.getAttribute("data-key");
                    if (confirm(t("matches.confirm_delete", "Are you sure you want to delete this match?"))) {
                        try {
                            await Obsidianscout.request(`/api/matches?matchKey=${key}`, { method: "DELETE" });
                            Obsidianscout.showToast(t("matches.deleted_success", "Match deleted successfully"), "success");
                            await loadMatches(eventKey, timezone);
                        } catch (error) {
                            Obsidianscout.showToast(error.message || t("matches.delete_failed", "Failed to delete match"), "error");
                        }
                    }
                });
            });
        }
    } catch (error) {
        body.innerHTML = `<tr><td colspan="5" style="text-align: center; padding: 24px;">
            <div class="retry-error-text" style="margin-bottom: 12px;">${t("matches.load_failed", "Unable to load matches")}: ${error.message}</div>
            <button class="retry-btn" type="button" id="retry-matches-btn">${t("btn.retry", "Retry")}</button>
        </td></tr>`;
        document.getElementById("retry-matches-btn").addEventListener("click", () => loadMatches(eventKey, timezone));
    }
}

async function setupModal(defaultEventKey, timezone) {
    const modal = document.getElementById("match-modal");
    const form = document.getElementById("match-form");
    const addBtn = document.getElementById("add-match-btn");
    const closeBtn = document.getElementById("match-modal-close");
    const cancelBtn = document.getElementById("match-modal-cancel");

    const eventSelect = document.getElementById("match-event-key");
    const compLevelSelect = document.getElementById("match-comp-level");
    const matchNumberInput = document.getElementById("match-number");
    const timeInput = document.getElementById("match-scheduled-time");
    const redTeam1 = document.getElementById("match-red-team-1");
    const redTeam2 = document.getElementById("match-red-team-2");
    const redTeam3 = document.getElementById("match-red-team-3");
    const blueTeam1 = document.getElementById("match-blue-team-1");
    const blueTeam2 = document.getElementById("match-blue-team-2");
    const blueTeam3 = document.getElementById("match-blue-team-3");
    const keyInput = document.getElementById("match-key");
    const titleEl = document.getElementById("match-modal-title");

    // Populate events dropdown
    try {
        const events = await Obsidianscout.request("/api/events?cached=1");
        eventSelect.innerHTML = "";
        events.forEach(e => {
            const opt = document.createElement("option");
            opt.value = e.eventKey;
            opt.textContent = `${e.name} (${e.eventKey})`;
            eventSelect.appendChild(opt);
        });
    } catch (err) {
        console.error("Failed to load events list", err);
    }

    function openModal() {
        modal.classList.add("show");
    }

    function closeModal() {
        modal.classList.remove("show");
        form.reset();
        eventSelect.removeAttribute("disabled");
        compLevelSelect.removeAttribute("disabled");
        matchNumberInput.removeAttribute("disabled");
        const activeKey = typeof defaultEventKey === "function" ? defaultEventKey() : defaultEventKey;
        eventSelect.value = activeKey || "";
        keyInput.value = "";
    }

    addBtn.addEventListener("click", () => {
        titleEl.textContent = t("matches.add_manual", "Add Match Manually");
        eventSelect.removeAttribute("disabled");
        compLevelSelect.removeAttribute("disabled");
        matchNumberInput.removeAttribute("disabled");
        keyInput.value = "";
        const activeKey = typeof defaultEventKey === "function" ? defaultEventKey() : defaultEventKey;
        eventSelect.value = activeKey || "";
        openModal();
    });

    closeBtn.addEventListener("click", closeModal);
    cancelBtn.addEventListener("click", closeModal);

    modal.addEventListener("click", (e) => {
        if (e.target === modal) {
            closeModal();
        }
    });

    form.addEventListener("submit", async (e) => {
        e.preventDefault();

        const redTeams = [
            redTeam1.value.trim(),
            redTeam2.value.trim(),
            redTeam3.value.trim()
        ].filter(Boolean);

        const blueTeams = [
            blueTeam1.value.trim(),
            blueTeam2.value.trim(),
            blueTeam3.value.trim()
        ].filter(Boolean);

        if (redTeams.length !== 3 || blueTeams.length !== 3) {
            Obsidianscout.showToast(t("matches.alliances_invalid", "Alliances must have exactly 3 teams each"), "error");
            return;
        }

        const scheduledTime = timeInput.value ? Math.floor(new Date(timeInput.value).getTime() / 1000) : null;

        const payload = {
            matchKey: keyInput.value,
            eventKey: eventSelect.value,
            compLevel: compLevelSelect.value,
            setNumber: 1,
            matchNumber: parseInt(matchNumberInput.value) || 1,
            scheduledTime: scheduledTime,
            redTeams: redTeams,
            blueTeams: blueTeams
        };

        try {
            await Obsidianscout.request("/api/matches", {
                method: "POST",
                json: payload
            });
            Obsidianscout.showToast(t("matches.saved_success", "Match saved successfully"), "success");
            closeModal();
            const activeEvent = eventSelect.value;
            currentEventKey = activeEvent;
            const eventFilter = document.getElementById("event-filter");
            if (eventFilter) {
                eventFilter.value = activeEvent;
            }
            await loadMatches(activeEvent, timezone);
        } catch (error) {
            Obsidianscout.showToast(error.message || t("matches.save_failed", "Failed to save match"), "error");
        }
    });
}

function openEditModal(match) {
    const modal = document.getElementById("match-modal");
    const eventSelect = document.getElementById("match-event-key");
    const compLevelSelect = document.getElementById("match-comp-level");
    const matchNumberInput = document.getElementById("match-number");
    const timeInput = document.getElementById("match-scheduled-time");
    const redTeam1 = document.getElementById("match-red-team-1");
    const redTeam2 = document.getElementById("match-red-team-2");
    const redTeam3 = document.getElementById("match-red-team-3");
    const blueTeam1 = document.getElementById("match-blue-team-1");
    const blueTeam2 = document.getElementById("match-blue-team-2");
    const blueTeam3 = document.getElementById("match-blue-team-3");
    const keyInput = document.getElementById("match-key");
    const titleEl = document.getElementById("match-modal-title");

    titleEl.textContent = t("matches.edit_match", "Edit Match");
    eventSelect.value = match.eventKey;
    eventSelect.setAttribute("disabled", "true");

    compLevelSelect.value = match.compLevel;
    compLevelSelect.setAttribute("disabled", "true");

    matchNumberInput.value = match.matchNumber || 1;
    matchNumberInput.setAttribute("disabled", "true");

    keyInput.value = match.matchKey;

    if (match.scheduledTime) {
        timeInput.value = formatForDatetimeLocal(match.scheduledTime);
    } else {
        timeInput.value = "";
    }

    redTeam1.value = match.redTeams[0] ? match.redTeams[0].replace(/^frc/, "") : "";
    redTeam2.value = match.redTeams[1] ? match.redTeams[1].replace(/^frc/, "") : "";
    redTeam3.value = match.redTeams[2] ? match.redTeams[2].replace(/^frc/, "") : "";
    blueTeam1.value = match.blueTeams[0] ? match.blueTeams[0].replace(/^frc/, "") : "";
    blueTeam2.value = match.blueTeams[1] ? match.blueTeams[1].replace(/^frc/, "") : "";
    blueTeam3.value = match.blueTeams[2] ? match.blueTeams[2].replace(/^frc/, "") : "";

    modal.classList.add("show");
}

function formatForDatetimeLocal(seconds) {
    if (!seconds) return "";
    const date = new Date(seconds * 1000);
    const pad = (num) => String(num).padStart(2, '0');
    const yyyy = date.getFullYear();
    const MM = pad(date.getMonth() + 1);
    const dd = pad(date.getDate());
    const hh = pad(date.getHours());
    const mm = pad(date.getMinutes());
    return `${yyyy}-${MM}-${dd}T${hh}:${mm}`;
}
