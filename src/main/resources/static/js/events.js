let currentEvents = [];
let currentUser = null;

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
    const year = settings.year;

    // Show/hide admin specific UI parts
    const isAdmin = Obsidianscout.isAdmin(me.role);
    if (isAdmin) {
        document.getElementById("add-event-btn").classList.remove("hidden");
        const actionHeaders = document.querySelectorAll(".admin-only");
        actionHeaders.forEach(h => h.classList.remove("hidden"));
    }

    await loadEvents(year, false);

    const syncButton = document.getElementById("sync-events");
    if (!isAdmin) {
        syncButton.disabled = true;
    } else {
        syncButton.addEventListener("click", async () => {
            syncButton.disabled = true;
            try {
                await Obsidianscout.request("/api/integrations/sync/events", { method: "POST" });
                Obsidianscout.showToast("Events synced", "success");
                await loadEvents(year, false);
            } catch (error) {
                Obsidianscout.showToast(error.message || "Sync failed", "error");
            } finally {
                syncButton.disabled = false;
            }
        });

        setupModal(year);
    }
});

async function loadEvents(year, cachedOnly) {
    const table = document.getElementById("events-table");
    const body = table.querySelector("tbody");
    body.innerHTML = "";

    try {
        const cachedParam = cachedOnly ? "&cached=1" : "";
        const events = await Obsidianscout.request(`/api/events?year=${year}${cachedParam}`);
        currentEvents = events;

        const isAdmin = currentUser && Obsidianscout.isAdmin(currentUser.role);

        events.forEach((event) => {
            const row = document.createElement("tr");
            let actionHtml = "";
            if (isAdmin) {
                actionHtml = `<td class="admin-only" style="display: flex; gap: 8px;">
                    <button class="btn-icon-edit" data-action="edit" data-key="${event.eventKey}">Edit</button>
                    <button class="btn-icon-edit delete" data-action="delete" data-key="${event.eventKey}" style="color: #c84b31; border-color: rgba(200, 75, 49, 0.25);">Delete</button>
                </td>`;
            }
            row.innerHTML = `
                <td>${event.name}</td>
                <td>${event.eventKey}</td>
                <td>${event.startDate || ""} - ${event.endDate || ""}</td>
                <td>${event.timezone || ""}</td>
                ${actionHtml}
            `;
            body.appendChild(row);
        });

        // Wire edit & delete buttons
        if (isAdmin) {
            body.querySelectorAll('[data-action="edit"]').forEach(btn => {
                btn.addEventListener("click", () => {
                    const key = btn.getAttribute("data-key");
                    const event = currentEvents.find(e => e.eventKey === key);
                    if (event) {
                        openEditModal(event);
                    }
                });
            });
            body.querySelectorAll('[data-action="delete"]').forEach(btn => {
                btn.addEventListener("click", async () => {
                    const key = btn.getAttribute("data-key");
                    if (confirm("Are you SURE you want to delete this event? This will permanently delete all associated teams, matches, and scouting entries!")) {
                        try {
                            await Obsidianscout.request(`/api/events?eventKey=${key}`, { method: "DELETE" });
                            Obsidianscout.showToast("Event deleted successfully", "success");
                            await loadEvents(year, false);
                        } catch (error) {
                            Obsidianscout.showToast(error.message || "Failed to delete event", "error");
                        }
                    }
                });
            });
        }
    } catch (error) {
        Obsidianscout.showToast("Unable to load events", "error");
    }
}

function setupModal(defaultYear) {
    const modal = document.getElementById("event-modal");
    const form = document.getElementById("event-form");
    const addBtn = document.getElementById("add-event-btn");
    const closeBtn = document.getElementById("event-modal-close");
    const cancelBtn = document.getElementById("event-modal-cancel");

    const keyInput = document.getElementById("event-key");
    const nameInput = document.getElementById("event-name");
    const yearInput = document.getElementById("event-year");
    const startDateInput = document.getElementById("event-start-date");
    const endDateInput = document.getElementById("event-end-date");
    const timezoneInput = document.getElementById("event-timezone");

    const titleEl = document.getElementById("event-modal-title");

    function openModal() {
        modal.classList.add("show");
    }

    function closeModal() {
        modal.classList.remove("show");
        form.reset();
        keyInput.removeAttribute("disabled");
    }

    addBtn.addEventListener("click", () => {
        titleEl.textContent = "Create Custom Event";
        keyInput.removeAttribute("disabled");
        yearInput.value = defaultYear;
        openModal();
    });

    closeBtn.addEventListener("click", closeModal);
    cancelBtn.addEventListener("click", closeModal);

    // Close on backdrop click
    modal.addEventListener("click", (e) => {
        if (e.target === modal) {
            closeModal();
        }
    });

    form.addEventListener("submit", async (e) => {
        e.preventDefault();
        
        const payload = {
            eventKey: keyInput.value.trim().toLowerCase(),
            name: nameInput.value.trim(),
            year: parseInt(yearInput.value),
            eventCode: null,
            startDate: startDateInput.value || null,
            endDate: endDateInput.value || null,
            timezone: timezoneInput.value.trim() || null
        };

        try {
            await Obsidianscout.request("/api/events", {
                method: "POST",
                json: payload
            });
            Obsidianscout.showToast("Event saved successfully", "success");
            closeModal();
            await loadEvents(defaultYear, false);
        } catch (error) {
            Obsidianscout.showToast(error.message || "Failed to save event", "error");
        }
    });
}

function openEditModal(event) {
    const modal = document.getElementById("event-modal");
    const keyInput = document.getElementById("event-key");
    const nameInput = document.getElementById("event-name");
    const yearInput = document.getElementById("event-year");
    const startDateInput = document.getElementById("event-start-date");
    const endDateInput = document.getElementById("event-end-date");
    const timezoneInput = document.getElementById("event-timezone");
    const titleEl = document.getElementById("event-modal-title");

    titleEl.textContent = "Edit Event";
    keyInput.value = event.eventKey;
    keyInput.setAttribute("disabled", "true"); // Primary Key shouldn't be edited once created

    nameInput.value = event.name;
    yearInput.value = event.year;
    startDateInput.value = event.startDate || "";
    endDateInput.value = event.endDate || "";
    timezoneInput.value = event.timezone || "America/Chicago";

    modal.classList.add("show");
}

