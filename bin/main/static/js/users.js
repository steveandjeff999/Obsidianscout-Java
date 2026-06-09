let limit = 50;
let offset = 0;
let currentSearch = "";
let currentTeamFilter = "";
let currentRoleFilter = "";

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    if (!Obsidianscout.isAdmin(me.role)) {
        document.getElementById("admin-locked").classList.remove("hidden");
        document.getElementById("admin-panel").classList.add("hidden");
        return;
    }

    // Show SUPERADMIN option in dropdowns only for superadmins
    if (Obsidianscout.isSuperAdmin(me.role)) {
        document.querySelector(".superadmin-option").style.display = "";
        document.querySelector(".edit-superadmin-option").style.display = "";
        const filterSuperadminOpt = document.querySelector(".filter-superadmin-option");
        if (filterSuperadminOpt) filterSuperadminOpt.style.display = "";
    }

    // Hide/disable team filter field if user is not a superadmin
    if (!Obsidianscout.isSuperAdmin(me.role)) {
        const teamFilterField = document.getElementById("team-filter-field");
        if (teamFilterField) {
            teamFilterField.style.display = "none";
        }
    }

    // For ADMIN, pre-fill team number and lock it
    const teamInput = document.getElementById("user-team");
    if (!Obsidianscout.isSuperAdmin(me.role)) {
        teamInput.value = me.teamNumber;
        teamInput.readOnly = true;
    }

    // ── Modal wiring ──────────────────────────────────────────────────────────
    const modal = document.getElementById("edit-modal");

    function openModal(user) {
        document.getElementById("edit-user-id").value  = user.id;
        document.getElementById("edit-username").value = user.username;
        document.getElementById("edit-password").value = "";
        document.getElementById("edit-role").value     = user.role;

        // Admins cannot change the role of a superadmin
        const roleField = document.getElementById("edit-role-field");
        if (!Obsidianscout.isSuperAdmin(me.role) && user.role === "SUPERADMIN") {
            roleField.style.display = "none";
        } else {
            roleField.style.display = "";
        }

        modal.classList.add("show");
        document.getElementById("edit-username").focus();
    }

    function closeModal() {
        modal.classList.remove("show");
    }

    document.getElementById("edit-cancel").addEventListener("click", closeModal);
    document.getElementById("edit-cancel-btn").addEventListener("click", closeModal);

    // Close on backdrop click
    modal.addEventListener("click", (e) => {
        if (e.target === modal) closeModal();
    });

    // Close on Escape
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape" && modal.classList.contains("show")) closeModal();
    });

    // Save button
    document.getElementById("edit-save-btn").addEventListener("click", async () => {
        const userId   = document.getElementById("edit-user-id").value;
        const username = document.getElementById("edit-username").value.trim();
        const password = document.getElementById("edit-password").value;
        const role     = document.getElementById("edit-role").value;

        const payload = {};
        if (username) payload.username = username;
        if (password) payload.password = password;
        if (role)     payload.role     = role;

        if (Object.keys(payload).length === 0) {
            Obsidianscout.showToast("Nothing to update", "info");
            return;
        }

        try {
            await Obsidianscout.request(`/api/admin/users/${userId}`, {
                method: "PUT",
                json: payload
            });
            Obsidianscout.showToast("User updated", "success");
            closeModal();
            await loadUsers(me, openModal);
        } catch (err) {
            Obsidianscout.showToast(err.message || "Update failed", "error");
        }
    });

    // ── Create user form ──────────────────────────────────────────────────────
    document.getElementById("user-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const username   = document.getElementById("user-username").value.trim();
        const teamNumber = parseInt(document.getElementById("user-team").value, 10);
        const password   = document.getElementById("user-password").value;
        const role       = document.getElementById("user-role").value;

        try {
            await Obsidianscout.request("/api/admin/users", {
                method: "POST",
                json: { username, teamNumber, password, role }
            });
            Obsidianscout.showToast("User created", "success");
            event.target.reset();
            if (!Obsidianscout.isSuperAdmin(me.role)) teamInput.value = me.teamNumber;
            await loadUsers(me, openModal);
        } catch (error) {
            Obsidianscout.showToast(error.message || "User creation failed", "error");
        }
    });

    const searchUsernameInput = document.getElementById("search-username");
    const searchTeamInput = document.getElementById("search-team");
    const searchRoleSelect = document.getElementById("search-role");
    const resetFiltersBtn = document.getElementById("reset-filters");
    const loadMoreBtn = document.getElementById("load-more-btn");

    let debounceTimeout = null;
    function triggerSearch() {
        currentSearch = searchUsernameInput.value.trim();
        currentTeamFilter = searchTeamInput.value.trim();
        currentRoleFilter = searchRoleSelect.value;
        loadUsers(me, openModal, false);
    }

    searchUsernameInput.addEventListener("input", () => {
        clearTimeout(debounceTimeout);
        debounceTimeout = setTimeout(triggerSearch, 300);
    });

    searchTeamInput.addEventListener("input", () => {
        clearTimeout(debounceTimeout);
        debounceTimeout = setTimeout(triggerSearch, 300);
    });

    searchRoleSelect.addEventListener("change", triggerSearch);

    resetFiltersBtn.addEventListener("click", () => {
        searchUsernameInput.value = "";
        searchTeamInput.value = "";
        searchRoleSelect.value = "";
        triggerSearch();
    });

    loadMoreBtn.addEventListener("click", () => {
        loadUsers(me, openModal, true);
    });

    await loadUsers(me, openModal);
});

async function loadUsers(me, openModal, append = false) {
    const tbody = document.querySelector("#user-table tbody");
    if (!tbody) return;

    if (!append) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 24px;"><div class="spinner" style="margin: 0 auto 12px; width: 32px; height: 32px;"></div><div>Loading users...</div></td></tr>';
        offset = 0;
    }

    try {
        const params = new URLSearchParams();
        params.append("limit", limit);
        params.append("offset", offset);
        if (currentSearch) params.append("q", currentSearch);
        if (currentTeamFilter) params.append("teamNumber", currentTeamFilter);
        if (currentRoleFilter) params.append("role", currentRoleFilter);

        const users = await Obsidianscout.request(`/api/admin/users?${params.toString()}`);
        
        if (!append) {
            tbody.innerHTML = "";
        }

        if (users.length === 0 && !append) {
            tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: var(--muted); padding: 24px;">No users found.</td></tr>';
            return;
        }

        users.forEach((user) => {
            const roleLabel = user.role === "SUPERADMIN" ? "Super Admin"
                : user.role.charAt(0) + user.role.slice(1).toLowerCase();

            // Superadmin can edit anyone; admin can edit non-superadmin users on their team
            const canEdit = Obsidianscout.isSuperAdmin(me.role)
                || (user.role !== "SUPERADMIN" && user.teamNumber === me.teamNumber);

            const row = document.createElement("tr");
            row.innerHTML = `
                <td>${user.username}</td>
                <td>${user.teamNumber}</td>
                <td>${roleLabel}</td>
                <td>${new Date(user.createdAt).toLocaleDateString()}</td>
                <td>${canEdit ? `<button class="edit-btn" data-id="${user.id}">Edit</button>` : ""}</td>
            `;

            if (canEdit) {
                row.querySelector(".edit-btn").addEventListener("click", () => openModal(user));
            }

            tbody.appendChild(row);
        });

        offset += users.length;

        const loadMoreContainer = document.getElementById("load-more-container");
        if (users.length === limit) {
            loadMoreContainer.classList.remove("hidden");
        } else {
            loadMoreContainer.classList.add("hidden");
        }
    } catch (error) {
        console.error("Failed to load users:", error);
        if (!append) {
            tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; padding: 24px;">
                <div class="retry-error-text" style="margin-bottom: 12px;">Failed to load users: ${error.message}</div>
                <button class="retry-btn" type="button" id="retry-users-btn">Retry</button>
            </td></tr>`;
            const retryBtn = document.getElementById("retry-users-btn");
            if (retryBtn) {
                retryBtn.addEventListener("click", () => loadUsers(me, openModal, append));
            }
        } else {
            Obsidianscout.showToast("Unable to load more users: " + error.message, "error");
        }
    }
}
