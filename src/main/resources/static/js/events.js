let currentEvents = [];
let currentUser = null;

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

    await loadEvents(year, true);

    const syncButton = document.getElementById("sync-events");
    if (!isAdmin) {
        syncButton.disabled = true;
    } else {
        syncButton.addEventListener("click", async () => {
            syncButton.disabled = true;
            try {
                const response = await Obsidianscout.request("/api/integrations/sync/events", { method: "POST" });
                Obsidianscout.showToast(response.message || t("events.synced", "Events synced"), "success");
                await loadEvents(year, true);
            } catch (error) {
                Obsidianscout.showToast(error.message || t("events.sync_failed", "Sync failed"), "error");
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

        const fragment = document.createDocumentFragment();

        events.forEach((event) => {
            const row = document.createElement("tr");

            const nameCell = document.createElement("td");
            nameCell.textContent = localize(event.name);
            const keyCell = document.createElement("td");
            keyCell.textContent = event.eventKey;
            const datesCell = document.createElement("td");
            datesCell.textContent = `${event.startDate || ""} - ${event.endDate || ""}`;
            const timezoneCell = document.createElement("td");
            timezoneCell.textContent = event.timezone || "";

            row.appendChild(nameCell);
            row.appendChild(keyCell);
            row.appendChild(datesCell);
            row.appendChild(timezoneCell);

            if (isAdmin) {
                const actionCell = document.createElement("td");
                actionCell.className = "admin-only";
                actionCell.style.display = "flex";
                actionCell.style.gap = "8px";

                const editButton = document.createElement("button");
                editButton.className = "btn-icon-edit";
                editButton.dataset.action = "edit";
                editButton.dataset.key = event.eventKey;
                editButton.textContent = t("common.edit", "Edit");

                const deleteButton = document.createElement("button");
                deleteButton.className = "btn-icon-edit delete";
                deleteButton.dataset.action = "delete";
                deleteButton.dataset.key = event.eventKey;
                deleteButton.style.color = "#c84b31";
                deleteButton.style.borderColor = "rgba(200, 75, 49, 0.25)";
                deleteButton.textContent = t("common.delete", "Delete");

                actionCell.appendChild(editButton);
                actionCell.appendChild(deleteButton);
                row.appendChild(actionCell);
            }

            fragment.appendChild(row);
        });

        body.replaceChildren(fragment);

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
                    if (confirm(t("events.confirm_delete", "Are you SURE you want to delete this event? This will permanently delete all associated teams, matches, and scouting entries!"))) {
                        try {
                            await Obsidianscout.request(`/api/events?eventKey=${key}`, { method: "DELETE" });
                            Obsidianscout.showToast(t("events.deleted_success", "Event deleted successfully"), "success");
                            await loadEvents(year, true);
                        } catch (error) {
                            Obsidianscout.showToast(error.message || t("events.delete_failed", "Failed to delete event"), "error");
                        }
                    }
                });
            });
        }
    } catch (error) {
        Obsidianscout.showToast(t("events.load_failed", "Unable to load events"), "error");
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
        titleEl.textContent = t("events.create_custom", "Create Custom Event");
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
            Obsidianscout.showToast(t("events.saved_success", "Event saved successfully"), "success");
            closeModal();
            await loadEvents(defaultYear, true);
        } catch (error) {
            Obsidianscout.showToast(error.message || t("events.save_failed", "Failed to save event"), "error");
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

    titleEl.textContent = t("events.edit_event", "Edit Event");
    keyInput.value = event.eventKey;
    keyInput.setAttribute("disabled", "true"); // Primary Key shouldn't be edited once created

    nameInput.value = event.name;
    yearInput.value = event.year;
    startDateInput.value = event.startDate || "";
    endDateInput.value = event.endDate || "";
    timezoneInput.value = event.timezone || "America/Chicago";

    modal.classList.add("show");
}

