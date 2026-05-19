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

    if (!Obsidianscout.isAdmin(me.role)) {
        document.getElementById("admin-locked").classList.remove("hidden");
        document.getElementById("admin-panel").classList.add("hidden");
        return;
    }

    // Show SUPERADMIN option in role dropdown only for superadmins
    if (Obsidianscout.isSuperAdmin(me.role)) {
        const superOption = document.querySelector(".superadmin-option");
        if (superOption) {
            superOption.style.display = "";
        }
    }

    // For ADMIN, pre-fill team number with their own team and make it read-only
    const teamInput = document.getElementById("user-team");
    if (!Obsidianscout.isSuperAdmin(me.role)) {
        teamInput.value = me.teamNumber;
        teamInput.readOnly = true;
    }

    await loadUsers();

    document.getElementById("user-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const username = document.getElementById("user-username").value.trim();
        const teamNumber = parseInt(document.getElementById("user-team").value, 10);
        const password = document.getElementById("user-password").value;
        const role = document.getElementById("user-role").value;

        try {
            await Obsidianscout.request("/api/admin/users", {
                method: "POST",
                json: {
                    username,
                    teamNumber,
                    password,
                    role
                }
            });
            Obsidianscout.showToast("User created", "success");
            event.target.reset();
            // Re-fill team number for non-superadmins
            if (!Obsidianscout.isSuperAdmin(me.role)) {
                teamInput.value = me.teamNumber;
            }
            await loadUsers();
        } catch (error) {
            Obsidianscout.showToast(error.message || "User creation failed", "error");
        }
    });
});

async function loadUsers() {
    const table = document.getElementById("user-table");
    const body = table.querySelector("tbody");
    body.innerHTML = "";

    try {
        const users = await Obsidianscout.request("/api/admin/users");
        users.forEach((user) => {
            const row = document.createElement("tr");
            const roleLabel = user.role === "SUPERADMIN" ? "Super Admin"
                : user.role.charAt(0) + user.role.slice(1).toLowerCase();
            row.innerHTML = `
                <td>${user.username}</td>
                <td>${user.teamNumber}</td>
                <td>${roleLabel}</td>
                <td>${user.createdAt}</td>
            `;
            body.appendChild(row);
        });
    } catch (error) {
        Obsidianscout.showToast("Unable to load users", "error");
    }
}
