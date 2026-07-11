function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    const isSuper = Obsidianscout.isSuperAdmin(me.role);
    const isAdmin = Obsidianscout.isAdmin(me.role);

    if (!isAdmin) {
        document.getElementById("admin-locked").classList.remove("hidden");
        document.getElementById("admin-panel").classList.add("hidden");
        return;
    }

    // Configure Creation Form Scope based on role
    const teamSelect = document.getElementById("banner-team-select");
    const teamInput = document.getElementById("banner-team-number");
    const teamField = document.getElementById("field-team-number");
    const globalOption = document.getElementById("option-global");
    const customOption = document.getElementById("option-custom");

    teamInput.value = me.teamNumber;

    if (isSuper) {
        globalOption.classList.remove("hidden");
        customOption.classList.remove("hidden");
    }

    teamSelect.addEventListener("change", () => {
        if (teamSelect.value === "global") {
            teamInput.value = 0;
            teamField.classList.add("hidden");
        } else if (teamSelect.value === "custom") {
            teamInput.value = "";
            teamField.classList.remove("hidden");
        } else {
            teamInput.value = me.teamNumber;
            teamField.classList.add("hidden");
        }
    });

    // Configure Edit Form Scope based on role
    const editTeamSelect = document.getElementById("edit-banner-team-select");
    const editTeamInput = document.getElementById("edit-banner-team-number");
    const editTeamField = document.getElementById("edit-field-team-number");
    const editGlobalOption = document.getElementById("edit-option-global");
    const editCustomOption = document.getElementById("edit-option-custom");

    if (isSuper) {
        editGlobalOption.classList.remove("hidden");
        editCustomOption.classList.remove("hidden");
    }

    editTeamSelect.addEventListener("change", () => {
        if (editTeamSelect.value === "global") {
            editTeamInput.value = 0;
            editTeamField.classList.add("hidden");
        } else if (editTeamSelect.value === "custom") {
            editTeamField.classList.remove("hidden");
        } else {
            editTeamInput.value = me.teamNumber;
            editTeamField.classList.add("hidden");
        }
    });

    // Expandable toggle field handling for Creation Form
    const expCheckbox = document.getElementById("banner-expandable");
    const expField = document.getElementById("expandable-msg-field");
    expCheckbox.addEventListener("change", () => {
        expField.classList.toggle("hidden", !expCheckbox.checked);
    });

    // Expandable toggle field handling for Edit Form
    const editExpCheckbox = document.getElementById("edit-banner-expandable");
    const editExpField = document.getElementById("edit-expandable-msg-field");
    editExpCheckbox.addEventListener("change", () => {
        editExpField.classList.toggle("hidden", !editExpCheckbox.checked);
    });

    // Modals references
    const editModal = document.getElementById("edit-modal");
    const editForm = document.getElementById("edit-form");
    const editCancelBtn = document.getElementById("edit-cancel");
    const editCloseBtn = document.getElementById("edit-close");

    function closeEditModal() {
        editModal.classList.remove("show");
        editForm.reset();
        editExpField.classList.add("hidden");
    }

    editCancelBtn.addEventListener("click", closeEditModal);
    editCloseBtn.addEventListener("click", closeEditModal);

    // List of banners state
    let bannersList = [];

    // Load Banners
    async function loadBanners() {
        try {
            const tableBody = document.querySelector("#banners-table tbody");
            tableBody.innerHTML = `<tr><td colspan="6" style="text-align: center; padding: 20px;" class="muted">${t("banners.loading", "Loading banners...")}</td></tr>`;

            bannersList = await Obsidianscout.request("/api/admin/banners");
            
            if (!bannersList || bannersList.length === 0) {
                tableBody.innerHTML = `<tr><td colspan="6" style="text-align: center; padding: 20px;" class="muted">${t("banners.no_banners", "No banners created yet.")}</td></tr>`;
                return;
            }

            tableBody.innerHTML = "";
            bannersList.forEach(banner => {
                const tr = document.createElement("tr");

                // Message
                const tdMessage = document.createElement("td");
                tdMessage.textContent = banner.message;
                tr.appendChild(tdMessage);

                // Type (Badge)
                const tdType = document.createElement("td");
                const typeBadge = document.createElement("span");
                typeBadge.className = `badge-type ${banner.bannerType}`;
                const typeMap = {
                    info: t("banners.type_info", "Info"),
                    warning: t("banners.type_warning", "Warning"),
                    success: t("banners.type_success", "Success"),
                    danger: t("banners.type_danger", "Danger")
                };
                typeBadge.textContent = typeMap[banner.bannerType] || banner.bannerType;
                tdType.appendChild(typeBadge);
                tr.appendChild(tdType);

                // Scope
                const tdScope = document.createElement("td");
                tdScope.innerHTML = banner.teamNumber === 0 
                    ? `<strong style="color: var(--accent);">${t("banners.scope_global", "Global")}</strong>` 
                    : `${t("banners.scope_team", "Team")} ${banner.teamNumber}`;
                tr.appendChild(tdScope);

                // Properties
                const tdProps = document.createElement("td");
                const props = [];
                if (!banner.isDismissible) props.push(t("banners.prop_immutable", "Immutable"));
                if (banner.isExpandable) props.push(t("banners.prop_expandable", "Expandable"));
                tdProps.textContent = props.join(", ") || t("banners.prop_standard", "Standard");
                tr.appendChild(tdProps);

                // Status
                const tdStatus = document.createElement("td");
                tdStatus.innerHTML = banner.isActive 
                    ? `<span class="badge success">${t("banners.status_active", "Active")}</span>` 
                    : `<span class="badge ghost">${t("banners.status_inactive", "Inactive")}</span>`;
                tr.appendChild(tdStatus);

                // Actions
                const tdActions = document.createElement("td");
                tdActions.className = "row gap-8";

                const editBtn = document.createElement("button");
                editBtn.type = "button";
                editBtn.className = "edit-btn";
                editBtn.textContent = t("banners.btn_edit", "Edit");
                editBtn.addEventListener("click", () => openEdit(banner));
                tdActions.appendChild(editBtn);

                const deleteBtn = document.createElement("button");
                deleteBtn.type = "button";
                deleteBtn.className = "delete-btn";
                deleteBtn.textContent = t("banners.btn_delete", "Delete");
                deleteBtn.addEventListener("click", () => deleteBanner(banner.id));
                tdActions.appendChild(deleteBtn);

                tr.appendChild(tdActions);

                tableBody.appendChild(tr);
            });
        } catch (error) {
            Obsidianscout.showToast(t("banners.err_load", "Failed to load banners list: ") + error.message, "error");
        }
    }

    // Submit Create Form
    const creationForm = document.getElementById("banner-form");
    creationForm.addEventListener("submit", async (e) => {
        e.preventDefault();

        const message = document.getElementById("banner-message").value;
        const bannerType = document.getElementById("banner-type").value;
        const teamNumber = parseInt(document.getElementById("banner-team-number").value, 10);

        if (teamSelect.value === "custom" && (isNaN(teamNumber) || teamNumber < 1)) {
            Obsidianscout.showToast(t("banners.err_invalid_custom_team", "Please enter a valid team number for the custom target scope."), "error");
            return;
        }
        const isDismissible = !document.getElementById("banner-immutable").checked;
        const isExpandable = document.getElementById("banner-expandable").checked;
        const expandableMessage = isExpandable ? document.getElementById("banner-expandable-message").value : "";
        const isActive = document.getElementById("banner-active").checked;

        try {
            await Obsidianscout.request("/api/admin/banners", {
                method: "POST",
                json: {
                    message,
                    bannerType,
                    teamNumber,
                    isDismissible,
                    isExpandable,
                    expandableMessage,
                    isActive
                }
            });

            Obsidianscout.showToast(t("banners.toast_created", "Banner created successfully!"), "success");
            creationForm.reset();
            expField.classList.add("hidden");
            teamInput.value = me.teamNumber;
            teamSelect.value = "team";
            teamField.classList.add("hidden");
            await loadBanners();
        } catch (error) {
            Obsidianscout.showToast(t("banners.err_create", "Failed to create banner: ") + error.message, "error");
        }
    });

    // Open Edit Modal
    function openEdit(banner) {
        document.getElementById("edit-banner-id").value = banner.id;
        document.getElementById("edit-banner-message").value = banner.message;
        document.getElementById("edit-banner-type").value = banner.bannerType;
        document.getElementById("edit-banner-immutable").checked = !banner.isDismissible;
        document.getElementById("edit-banner-expandable").checked = banner.isExpandable;
        document.getElementById("edit-banner-expandable-message").value = banner.expandableMessage || "";
        document.getElementById("edit-banner-active").checked = banner.isActive;

        if (banner.teamNumber === 0) {
            editTeamSelect.value = "global";
            editTeamInput.value = 0;
            editTeamField.classList.add("hidden");
        } else if (banner.teamNumber === me.teamNumber) {
            editTeamSelect.value = "team";
            editTeamInput.value = me.teamNumber;
            editTeamField.classList.add("hidden");
        } else {
            editTeamSelect.value = "custom";
            editTeamInput.value = banner.teamNumber;
            editTeamField.classList.remove("hidden");
        }

        editExpField.classList.toggle("hidden", !banner.isExpandable);
        editModal.classList.add("show");
    }

    // Submit Edit Form
    editForm.addEventListener("submit", async (e) => {
        e.preventDefault();

        const id = document.getElementById("edit-banner-id").value;
        const message = document.getElementById("edit-banner-message").value;
        const bannerType = document.getElementById("edit-banner-type").value;
        const teamNumber = parseInt(document.getElementById("edit-banner-team-number").value, 10);

        if (editTeamSelect.value === "custom" && (isNaN(teamNumber) || teamNumber < 1)) {
            Obsidianscout.showToast(t("banners.err_invalid_custom_team", "Please enter a valid team number for the custom target scope."), "error");
            return;
        }

        const isDismissible = !document.getElementById("edit-banner-immutable").checked;
        const isExpandable = document.getElementById("edit-banner-expandable").checked;
        const expandableMessage = isExpandable ? document.getElementById("edit-banner-expandable-message").value : "";
        const isActive = document.getElementById("edit-banner-active").checked;

        try {
            await Obsidianscout.request(`/api/admin/banners/${id}`, {
                method: "PUT",
                json: {
                    message,
                    bannerType,
                    teamNumber,
                    isDismissible,
                    isExpandable,
                    expandableMessage,
                    isActive
                }
            });

            Obsidianscout.showToast(t("banners.toast_updated", "Banner updated successfully!"), "success");
            closeEditModal();
            await loadBanners();
        } catch (error) {
            Obsidianscout.showToast(t("banners.err_update", "Failed to update banner: ") + error.message, "error");
        }
    });

    // Delete Banner
    async function deleteBanner(id) {
        if (!confirm(t("banners.confirm_delete", "Are you sure you want to delete this banner?"))) {
            return;
        }

        try {
            await Obsidianscout.request(`/api/admin/banners/${id}`, {
                method: "DELETE"
            });

            Obsidianscout.showToast(t("banners.toast_deleted", "Banner deleted successfully!"), "success");
            await loadBanners();
        } catch (error) {
            Obsidianscout.showToast(t("banners.err_delete", "Failed to delete banner: ") + error.message, "error");
        }
    }

    // Initial load
    await loadBanners();
});
