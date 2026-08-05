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

    /** Resize an image File to a square JPEG data-URL (max `size` px). */
    async function resizeImageToBase64(file, size = 384) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = (e) => {
                const img = new Image();
                img.onload = () => {
                    const canvas = document.createElement("canvas");
                    canvas.width = size;
                    canvas.height = size;
                    const ctx = canvas.getContext("2d");
                    const srcSize = Math.min(img.width, img.height);
                    const sx = (img.width - srcSize) / 2;
                    const sy = (img.height - srcSize) / 2;
                    ctx.drawImage(img, sx, sy, srcSize, srcSize, 0, 0, size, size);
                    resolve(canvas.toDataURL("image/png"));
                };
                img.onerror = reject;
                img.src = e.target.result;
            };
            reader.onerror = reject;
            reader.readAsDataURL(file);
        });
    }

    function wirePersonalAvatarWidget(currentMe) {
        const avatarImg = document.getElementById("personal-avatar-img");
        const avatarPlaceholder = document.getElementById("personal-avatar-placeholder");
        const avatarInput = document.getElementById("personal-avatar-input");
        const avatarRemove = document.getElementById("personal-avatar-remove");
        if (!avatarImg || !avatarPlaceholder || !avatarInput || !avatarRemove) return;

        function renderAvatar(profilePicture) {
            if (profilePicture) {
                avatarImg.src = profilePicture;
                avatarImg.style.display = "block";
                avatarPlaceholder.style.display = "none";
            } else {
                const initials = (currentMe.username || "?").slice(0, 2).toUpperCase();
                let hue = 0;
                for (let i = 0; i < (currentMe.username || "").length; i++) {
                    hue = (hue + currentMe.username.charCodeAt(i) * 37) % 360;
                }
                avatarPlaceholder.textContent = initials;
                avatarPlaceholder.style.setProperty("--avatar-hue", hue + "deg");
                avatarPlaceholder.style.display = "flex";
                avatarImg.style.display = "none";
                avatarImg.src = "";
            }
        }
        renderAvatar(currentMe.profilePicture);

        [avatarImg, avatarPlaceholder].forEach((el) => {
            el.addEventListener("click", () => avatarInput.click());
        });

        avatarInput.addEventListener("change", async (e) => {
            const file = e.target.files[0];
            if (!file) return;
            try {
                const base64 = await resizeImageToBase64(file, 384);
                const updated = await Obsidianscout.request("/api/user/profile-picture", {
                    method: "PUT",
                    json: { profilePicture: base64 }
                });
                renderAvatar(updated.profilePicture);
                Obsidianscout.refreshNavAvatar(updated.profilePicture);
                Obsidianscout.showToast("Profile picture updated", "success");
            } catch (err) {
                Obsidianscout.showToast(err.message || "Failed to upload picture", "error");
            }
            avatarInput.value = "";
        });

        avatarRemove.addEventListener("click", async () => {
            try {
                await Obsidianscout.request("/api/user/profile-picture", {
                    method: "PUT",
                    json: { clearProfilePicture: true }
                });
                renderAvatar(null);
                Obsidianscout.refreshNavAvatar(null);
                Obsidianscout.showToast("Profile picture removed", "success");
            } catch (err) {
                Obsidianscout.showToast(err.message || "Failed to remove picture", "error");
            }
        });
    }

    function wirePersonalEmailWidget(currentMe) {
        const personalEmail = document.getElementById("personal-email");
        const personalSaveEmail = document.getElementById("personal-save-email");
        if (!personalEmail || !personalSaveEmail) return;

        personalEmail.value = currentMe.email || "";

        personalSaveEmail.addEventListener("click", async () => {
            const emailVal = personalEmail.value.trim();
            try {
                const updated = await Obsidianscout.request("/api/user/profile-picture", {
                    method: "PUT",
                    json: { email: emailVal }
                });
                currentMe.email = updated.email;
                personalEmail.value = updated.email || "";
                Obsidianscout.showToast("Email address updated", "success");
            } catch (err) {
                Obsidianscout.showToast(err.message || "Failed to update email", "error");
            }
        });
    }

    function wirePersonalNotificationPrefWidget(currentMe) {
        const personalNotifPref = document.getElementById("personal-notification-pref");
        if (!personalNotifPref) return;

        personalNotifPref.value = currentMe.notificationPreference || "all";

        personalNotifPref.addEventListener("change", async (e) => {
            const val = e.target.value;
            try {
                const updated = await Obsidianscout.request("/api/user/profile-picture", {
                    method: "PUT",
                    json: { notificationPreference: val }
                });
                currentMe.notificationPreference = updated.notificationPreference;
                personalNotifPref.value = updated.notificationPreference || "all";
                Obsidianscout.showToast("Notification preferences updated", "success");
            } catch (err) {
                Obsidianscout.showToast(err.message || "Failed to update notification preferences", "error");
            }
        });
    }

    function wirePersonalDeleteAccountWidget(currentMe) {
        const deleteBtn = document.getElementById("personal-delete-account");
        if (!deleteBtn) return;

        deleteBtn.addEventListener("click", async () => {
            const confirmed = confirm("Are you absolutely sure you want to delete your account? This action cannot be undone and you will be immediately signed out.");
            if (!confirmed) return;

            try {
                await Obsidianscout.request("/api/user", {
                    method: "DELETE"
                });
                Obsidianscout.showToast("Account deleted successfully", "success");
                window.location.href = "/";
            } catch (err) {
                Obsidianscout.showToast(err.message || "Failed to delete account", "error");
            }
        });
    }

    function wirePersonalHapticPrefWidget() {
        const personalHapticPref = document.getElementById("personal-haptic-pref");
        if (!personalHapticPref) return;

        personalHapticPref.value = Obsidianscout.safeGetItem("obsidianscout:haptic_feedback") || "enabled";

        personalHapticPref.addEventListener("change", (e) => {
            const val = e.target.value;
            Obsidianscout.safeSetItem("obsidianscout:haptic_feedback", val);
            Obsidianscout.showToast("Personal settings saved", "success");
            if (val === "enabled" && typeof Obsidianscout.triggerHaptic === "function") {
                Obsidianscout.triggerHaptic("success");
            }
        });
    }

    function wirePersonalNavLayoutWidget() {
        const personalNavLayoutSelect = document.getElementById("personal-nav-layout");
        if (!personalNavLayoutSelect) return;

        let savedVal = Obsidianscout.safeGetItem("obsidianscout:nav_layout") || "sidebar-left";
        if (savedVal === "sidebar") savedVal = "sidebar-left";
        if (savedVal === "topbar") savedVal = "topbar-top";
        personalNavLayoutSelect.value = savedVal;

        personalNavLayoutSelect.addEventListener("change", (e) => {
            const val = e.target.value;
            Obsidianscout.safeSetItem("obsidianscout:nav_layout", val);
            if (typeof Obsidianscout.applyNavLayout === "function") {
                Obsidianscout.applyNavLayout(val);
            }
            Obsidianscout.showToast("Personal settings saved", "success");
        });
    }

    // Initialize personal setting dropdown
    const personalDisplaySelect = document.getElementById("personal-team-display");
    if (personalDisplaySelect) {
        personalDisplaySelect.value = Obsidianscout.safeGetItem("obsidianscout:team_display") || "merged";
        personalDisplaySelect.addEventListener("change", (e) => {
            Obsidianscout.safeSetItem("obsidianscout:team_display", e.target.value);
            Obsidianscout.showToast("Personal settings saved", "success");
            window.dispatchEvent(new CustomEvent("obsidianscout:teamdisplaychange", { detail: { format: e.target.value } }));
        });
    }

    wirePersonalAvatarWidget(me);
    wirePersonalEmailWidget(me);
    wirePersonalNotificationPrefWidget(me);
    wirePersonalDeleteAccountWidget(me);
    wirePersonalHapticPrefWidget();
    wirePersonalNavLayoutWidget();
});
