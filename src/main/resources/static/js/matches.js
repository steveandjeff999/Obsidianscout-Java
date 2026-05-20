let currentEventKey = "";

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

    const settingsResponse = await Obsidianscout.request("/api/settings");
    const settings = settingsResponse.settings;
    currentEventKey = Obsidianscout.resolveEventKey(settings);
    const timezone = settings.timezone;

    const isAdmin = Obsidianscout.isAdmin(me.role);
    if (isAdmin) {
        document.getElementById("add-match-btn").classList.remove("hidden");
        document.querySelectorAll(".admin-only").forEach(h => h.classList.remove("hidden"));
    }

    // Populate event filter select dropdown
    const eventFilter = document.getElementById("event-filter");
    try {
        const events = await Obsidianscout.request(`/api/events?year=${settings.year}&cached=1`);
        eventFilter.innerHTML = "";
        events.forEach(e => {
            const opt = document.createElement("option");
            opt.value = e.eventKey;
            opt.textContent = `${e.name} (${e.eventKey})`;
            if (e.eventKey === currentEventKey) {
                opt.selected = true;
            }
            eventFilter.appendChild(opt);
        });

        eventFilter.addEventListener("change", async () => {
            currentEventKey = eventFilter.value;
            await loadMatches(currentEventKey, timezone);
        });
    } catch (err) {
        console.error("Failed to load events filter", err);
    }

    await loadMatches(currentEventKey, timezone);

    const syncButton = document.getElementById("sync-event");

    if (!isAdmin) {
        syncButton.disabled = true;
    } else {
        syncButton.addEventListener("click", async () => {
            syncButton.disabled = true;
            try {
                await Obsidianscout.request("/api/integrations/sync/event", { method: "POST" });
                Obsidianscout.showToast("Matches synced", "success");
                const refreshed = await Obsidianscout.request("/api/settings");
                currentEventKey = Obsidianscout.resolveEventKey(refreshed.settings);
                if (eventFilter) eventFilter.value = currentEventKey;
                await loadMatches(currentEventKey, timezone);
            } catch (error) {
                Obsidianscout.showToast(error.message || "Sync failed", "error");
            } finally {
                syncButton.disabled = false;
            }
        });

        await setupModal(() => currentEventKey, timezone);
    }
});

async function loadMatches(eventKey, timezone) {
    const table = document.getElementById("matches-table");
    const body = table.querySelector("tbody");
    body.innerHTML = "";

    if (!eventKey) {
        Obsidianscout.showToast("Set an event key in settings", "error");
        return;
    }

    try {
        const matches = await Obsidianscout.request(`/api/matches?eventKey=${eventKey}`);
        currentMatches = matches;
        const isAdmin = currentUser && Obsidianscout.isAdmin(currentUser.role);

        matches.forEach((match) => {
            const row = document.createElement("tr");
            const red = match.redTeams.join(", ");
            const blue = match.blueTeams.join(", ");
            const timeLabel = Obsidianscout.formatTimestamp(match.scheduledTime, timezone);
            let actionHtml = "";
            if (isAdmin) {
                actionHtml = `<td class="admin-only" style="display: flex; gap: 8px;">
                    <button class="btn-icon-edit" data-action="edit" data-key="${match.matchKey}">Edit</button>
                    <button class="btn-icon-edit delete" data-action="delete" data-key="${match.matchKey}" style="color: #c84b31; border-color: rgba(200, 75, 49, 0.25);">Delete</button>
                </td>`;
            }
            row.innerHTML = `
                <td>${match.label || `${match.compLevel.toUpperCase()} ${match.matchNumber || ""}`}</td>
                <td>${timeLabel}</td>
                <td>${red}</td>
                <td>${blue}</td>
                ${actionHtml}
            `;
            body.appendChild(row);
        });

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
                    if (confirm("Are you sure you want to delete this match?")) {
                        try {
                            await Obsidianscout.request(`/api/matches?matchKey=${key}`, { method: "DELETE" });
                            Obsidianscout.showToast("Match deleted successfully", "success");
                            await loadMatches(eventKey, timezone);
                        } catch (error) {
                            Obsidianscout.showToast(error.message || "Failed to delete match", "error");
                        }
                    }
                });
            });
        }
    } catch (error) {
        Obsidianscout.showToast("Unable to load matches", "error");
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
        titleEl.textContent = "Add Match Manually";
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
            Obsidianscout.showToast("Alliances must have exactly 3 teams each", "error");
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
            Obsidianscout.showToast("Match saved successfully", "success");
            closeModal();
            const activeEvent = eventSelect.value;
            currentEventKey = activeEvent;
            const eventFilter = document.getElementById("event-filter");
            if (eventFilter) {
                eventFilter.value = activeEvent;
            }
            await loadMatches(activeEvent, timezone);
        } catch (error) {
            Obsidianscout.showToast(error.message || "Failed to save match", "error");
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

    titleEl.textContent = "Edit Match";
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
