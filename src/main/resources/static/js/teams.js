let currentTeams = [];
let currentUser = null;
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

    const isAdmin = Obsidianscout.isAdmin(me.role);
    if (isAdmin) {
        document.getElementById("add-team-btn").classList.remove("hidden");
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
            await loadTeams(currentEventKey);
        });
    } catch (err) {
        console.error("Failed to load events filter", err);
    }

    await loadTeams(currentEventKey);

    const syncButton = document.getElementById("sync-event");
    const statsButton = document.getElementById("sync-stats");

    if (!isAdmin) {
        syncButton.disabled = true;
        statsButton.disabled = true;
    } else {
        syncButton.addEventListener("click", async () => {
            await runSync(syncButton, "/api/integrations/sync/event");
            const refreshed = await Obsidianscout.request("/api/settings");
            currentEventKey = Obsidianscout.resolveEventKey(refreshed.settings);
            if (eventFilter) eventFilter.value = currentEventKey;
            await loadTeams(currentEventKey);
        });

        statsButton.addEventListener("click", async () => {
            await runSync(statsButton, "/api/integrations/sync/stats");
            await loadTeams(currentEventKey);
        });

        await setupModal(() => currentEventKey);
    }
});

async function loadTeams(eventKey) {
    const table = document.getElementById("teams-table");
    const body = table.querySelector("tbody");
    body.innerHTML = "";

    if (!eventKey) {
        Obsidianscout.showToast("Set year and event code in settings", "error");
        return;
    }

    try {
        const teams = await Obsidianscout.request(`/api/teams?eventKey=${eventKey}`);
        currentTeams = teams;
        const isAdmin = currentUser && Obsidianscout.isAdmin(currentUser.role);

        teams.forEach((team) => {
            const row = document.createElement("tr");
            const location = [team.city, team.state, team.country].filter(Boolean).join(", ");
            let actionHtml = "";
            if (isAdmin) {
                actionHtml = `<td class="admin-only" style="display: flex; gap: 8px;">
                    <button class="btn-icon-edit" data-action="edit" data-number="${team.teamNumber}">Edit</button>
                    <button class="btn-icon-edit delete" data-action="delete" data-number="${team.teamNumber}" data-key="${team.teamKey}" style="color: #c84b31; border-color: rgba(200, 75, 49, 0.25);">Delete</button>
                </td>`;
            }
            const displayNum = Obsidianscout.formatTeam(team.teamKey, team.teamNumber);
            row.innerHTML = `
                <td>${displayNum}</td>
                <td>${team.nickname || team.name || ""}</td>
                <td>${location}</td>
                <td>${team.opr !== null ? team.opr.toFixed(2) : ""}</td>
                <td>${team.epa !== null ? team.epa.toFixed(2) : ""}</td>
                ${actionHtml}
            `;
            body.appendChild(row);
        });

        // Wire edit & delete buttons
        if (isAdmin) {
            body.querySelectorAll('[data-action="edit"]').forEach(btn => {
                btn.addEventListener("click", () => {
                    const num = parseInt(btn.getAttribute("data-number"));
                    const team = currentTeams.find(t => t.teamNumber === num);
                    if (team) {
                        openEditModal(team);
                    }
                });
            });
            body.querySelectorAll('[data-action="delete"]').forEach(btn => {
                btn.addEventListener("click", async () => {
                    const num = btn.getAttribute("data-number");
                    const teamKey = btn.getAttribute("data-key");
                    if (confirm(`Are you sure you want to delete team ${num}?`)) {
                        try {
                            await Obsidianscout.request(`/api/teams?eventKey=${eventKey}&teamKey=${teamKey}`, { method: "DELETE" });
                            Obsidianscout.showToast("Team deleted successfully", "success");
                            await loadTeams(eventKey);
                        } catch (error) {
                            Obsidianscout.showToast(error.message || "Failed to delete team", "error");
                        }
                    }
                });
            });
        }
    } catch (error) {
        Obsidianscout.showToast("Unable to load teams", "error");
    }
}

async function runSync(button, path) {
    button.disabled = true;
    try {
        const response = await Obsidianscout.request(path, { method: "POST" });
        Obsidianscout.showToast(response.message || "Sync complete", "success");
    } catch (error) {
        Obsidianscout.showToast(error.message || "Sync failed", "error");
    } finally {
        button.disabled = false;
    }
}

async function setupModal(defaultEventKey) {
    const modal = document.getElementById("team-modal");
    const form = document.getElementById("team-form");
    const addBtn = document.getElementById("add-team-btn");
    const closeBtn = document.getElementById("team-modal-close");
    const cancelBtn = document.getElementById("team-modal-cancel");

    const eventSelect = document.getElementById("team-event-key");
    const numberInput = document.getElementById("team-number");
    const nameInput = document.getElementById("team-name");
    const cityInput = document.getElementById("team-city");
    const stateInput = document.getElementById("team-state");
    const countryInput = document.getElementById("team-country");
    const oprInput = document.getElementById("team-opr");
    const epaInput = document.getElementById("team-epa");
    const titleEl = document.getElementById("team-modal-title");

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
        numberInput.removeAttribute("disabled");
        eventSelect.removeAttribute("disabled");
        const activeKey = typeof defaultEventKey === "function" ? defaultEventKey() : defaultEventKey;
        eventSelect.value = activeKey || "";
    }

    addBtn.addEventListener("click", () => {
        titleEl.textContent = "Add Team Manually";
        numberInput.removeAttribute("disabled");
        eventSelect.removeAttribute("disabled");
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

        const teamName = nameInput.value.trim() || null;

        const payload = {
            eventKey: eventSelect.value,
            teamKey: `frc${numberInput.value.trim()}`,
            teamNumber: parseInt(numberInput.value.trim()),
            nickname: teamName,
            name: teamName,
            city: cityInput.value.trim() || null,
            state: stateInput.value.trim() || null,
            country: countryInput.value.trim() || null,
            opr: oprInput.value ? parseFloat(oprInput.value) : null,
            epa: epaInput.value ? parseFloat(epaInput.value) : null
        };

        try {
            await Obsidianscout.request("/api/teams", {
                method: "POST",
                json: payload
            });
            Obsidianscout.showToast("Team saved successfully", "success");
            closeModal();
            const activeEvent = eventSelect.value;
            currentEventKey = activeEvent;
            const eventFilter = document.getElementById("event-filter");
            if (eventFilter) {
                eventFilter.value = activeEvent;
            }
            await loadTeams(activeEvent);
        } catch (error) {
            Obsidianscout.showToast(error.message || "Failed to save team", "error");
        }
    });
}

function openEditModal(team) {
    const modal = document.getElementById("team-modal");
    const eventSelect = document.getElementById("team-event-key");
    const numberInput = document.getElementById("team-number");
    const nameInput = document.getElementById("team-name");
    const cityInput = document.getElementById("team-city");
    const stateInput = document.getElementById("team-state");
    const countryInput = document.getElementById("team-country");
    const oprInput = document.getElementById("team-opr");
    const epaInput = document.getElementById("team-epa");
    const titleEl = document.getElementById("team-modal-title");

    titleEl.textContent = "Edit Team";
    eventSelect.value = team.eventKey;
    eventSelect.setAttribute("disabled", "true");

    numberInput.value = team.teamNumber;
    numberInput.setAttribute("disabled", "true");

    nameInput.value = team.nickname || team.name || "";
    cityInput.value = team.city || "";
    stateInput.value = team.state || "";
    countryInput.value = team.country || "USA";
    oprInput.value = team.opr !== null ? team.opr : "";
    epaInput.value = team.epa !== null ? team.epa : "";

    modal.classList.add("show");
}
