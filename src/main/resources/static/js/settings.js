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

    function wirePersonalUsernameWidget(currentMe) {
        const personalUsername = document.getElementById("personal-username");
        const personalSaveUsername = document.getElementById("personal-save-username");
        if (!personalUsername || !personalSaveUsername) return;

        personalUsername.value = currentMe.username || "";

        personalSaveUsername.addEventListener("click", async () => {
            const usernameVal = personalUsername.value.trim();
            if (!usernameVal) {
                Obsidianscout.showToast("Username cannot be blank", "error");
                return;
            }
            if (usernameVal === currentMe.username) {
                Obsidianscout.showToast("Username is already set to this", "info");
                return;
            }
            try {
                const updated = await Obsidianscout.request("/api/user/profile-picture", {
                    method: "PUT",
                    json: { username: usernameVal }
                });
                currentMe.username = updated.username;
                personalUsername.value = updated.username || "";
                try {
                    localStorage.removeItem("cache:/api/auth/me");
                } catch (e) {}
                Obsidianscout.setUserBadge(currentMe);

                // Update avatar initials placeholder if custom photo is not set
                const avatarImg = document.getElementById("personal-avatar-img");
                const avatarPlaceholder = document.getElementById("personal-avatar-placeholder");
                if (avatarPlaceholder && (!avatarImg || avatarImg.style.display === "none")) {
                    const initials = (currentMe.username || "?").slice(0, 2).toUpperCase();
                    let hue = 0;
                    for (let i = 0; i < (currentMe.username || "").length; i++) {
                        hue = (hue + currentMe.username.charCodeAt(i) * 37) % 360;
                    }
                    avatarPlaceholder.textContent = initials;
                    avatarPlaceholder.style.setProperty("--avatar-hue", hue + "deg");
                }

                Obsidianscout.showToast("Username updated successfully", "success");
            } catch (err) {
                Obsidianscout.showToast(err.message || "Failed to update username", "error");
            }
        });
    }

    function wirePersonalPasswordWidget() {
        const passwordInput = document.getElementById("personal-password");
        const confirmInput = document.getElementById("personal-confirm-password");
        const savePasswordBtn = document.getElementById("personal-save-password");
        if (!passwordInput || !confirmInput || !savePasswordBtn) return;

        savePasswordBtn.addEventListener("click", async () => {
            const password = passwordInput.value;
            const confirm = confirmInput.value;

            if (!password) {
                Obsidianscout.showToast("Please enter a new password", "error");
                return;
            }
            if (password !== confirm) {
                Obsidianscout.showToast("Passwords do not match", "error");
                return;
            }

            try {
                await Obsidianscout.request("/api/user/profile-picture", {
                    method: "PUT",
                    json: { password: password }
                });
                passwordInput.value = "";
                confirmInput.value = "";
                Obsidianscout.showToast("Password updated successfully", "success");
            } catch (err) {
                Obsidianscout.showToast(err.message || "Failed to update password", "error");
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

    function wirePersonalNodeAlertsWidget(currentMe) {
        const container = document.getElementById("personal-node-alerts-container");
        const toggle = document.getElementById("personal-node-alerts-toggle");
        if (!container || !toggle) return;

        if (currentMe.role === "SUPERADMIN") {
            container.classList.remove("hidden");
            toggle.checked = !!currentMe.nodeAlertsEnabled;

            toggle.addEventListener("change", async (e) => {
                const enrolled = e.target.checked;
                try {
                    const updated = await Obsidianscout.request("/api/user/profile-picture", {
                        method: "PUT",
                        json: { nodeAlertsEnabled: enrolled }
                    });
                    currentMe.nodeAlertsEnabled = updated.nodeAlertsEnabled;
                    toggle.checked = !!updated.nodeAlertsEnabled;
                    try { localStorage.removeItem("cache:/api/auth/me"); } catch (e) {}
                    Obsidianscout.showToast(enrolled ? "Enrolled in node health alerts" : "Unsubscribed from node health alerts", "success");
                } catch (err) {
                    toggle.checked = !enrolled;
                    Obsidianscout.showToast(err.message || "Failed to update node alert settings", "error");
                }
            });
        } else {
            container.classList.add("hidden");
        }
    }

    function wirePersonalDeviceSessionsWidget(currentMe) {
        const listEl = document.getElementById("personal-sessions-list");
        const loadingEl = document.getElementById("personal-sessions-loading");
        const revokeOthersBtn = document.getElementById("revoke-other-sessions-btn");
        if (!listEl) return;

        function formatRelativeTime(dateStr) {
            if (!dateStr) return "Unknown";
            try {
                const date = new Date(dateStr);
                const now = new Date();
                const diffMs = now - date;
                const diffSec = Math.floor(diffMs / 1000);
                if (diffSec < 60) return "Active just now";
                const diffMin = Math.floor(diffSec / 60);
                if (diffMin < 60) return `${diffMin}m ago`;
                const diffHours = Math.floor(diffMin / 60);
                if (diffHours < 24) return `${diffHours}h ago`;
                const diffDays = Math.floor(diffHours / 24);
                if (diffDays < 7) return `${diffDays}d ago`;
                return date.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
            } catch (e) {
                return dateStr;
            }
        }

        function getDeviceIcon(clientType, deviceName) {
            const name = (deviceName || "").toLowerCase();
            if (clientType === "mobile" || name.includes("iphone") || name.includes("ipad") || name.includes("android") || name.includes("mobile")) {
                return "📱";
            }
            if (name.includes("windows") || name.includes("mac") || name.includes("linux") || name.includes("chromeos")) {
                return "💻";
            }
            return "🌐";
        }

        async function loadSessions() {
            try {
                if (loadingEl) loadingEl.style.display = "block";
                listEl.innerHTML = "";

                const res = await Obsidianscout.request("/api/user/sessions", { method: "GET" });
                const sessions = (res && res.sessions) ? res.sessions : [];

                if (loadingEl) loadingEl.style.display = "none";

                if (!sessions || sessions.length === 0) {
                    listEl.innerHTML = `<div style="padding: 12px; color: var(--text-muted, #888); font-size: 14px;">No active sessions found.</div>`;
                    return;
                }

                sessions.forEach((s) => {
                    const icon = getDeviceIcon(s.clientType, s.deviceName);
                    const lastActive = formatRelativeTime(s.lastActiveAt);
                    const createdAt = s.createdAt ? new Date(s.createdAt).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" }) : "";
                    
                    const item = document.createElement("div");
                    item.className = "card";
                    item.style.cssText = `
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        flex-wrap: wrap;
                        gap: 16px;
                        padding: 14px 18px;
                        border-radius: 10px;
                        border: 1px solid ${s.isCurrent ? "var(--accent, #3b82f6)" : "var(--border-color, rgba(255,255,255,0.08))"};
                        background: ${s.isCurrent ? "rgba(59, 130, 246, 0.05)" : "var(--card-bg, rgba(255,255,255,0.02))"};
                    `;

                    const escapedDevice = Obsidianscout.escapeHtml ? Obsidianscout.escapeHtml(s.deviceName || "Unknown Device") : (s.deviceName || "Unknown Device");
                    const escapedIp = s.ipAddress ? (Obsidianscout.escapeHtml ? Obsidianscout.escapeHtml(s.ipAddress) : s.ipAddress) : "";

                    item.innerHTML = `
                        <div style="display: flex; align-items: center; gap: 14px; min-width: 220px; flex: 1;">
                            <div style="font-size: 28px; line-height: 1; display: flex; align-items: center; justify-content: center; width: 44px; height: 44px; border-radius: 8px; background: var(--bg-surface, rgba(255,255,255,0.05));">
                                ${icon}
                            </div>
                            <div>
                                <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
                                    <strong style="font-size: 15px; color: var(--text-primary, #fff);">${escapedDevice}</strong>
                                    ${s.isCurrent ? `<span style="background: rgba(34, 197, 94, 0.15); color: #22c55e; border: 1px solid rgba(34, 197, 94, 0.3); font-size: 11px; padding: 2px 7px; border-radius: 6px; font-weight: 600;">This Device (Active)</span>` : ""}
                                </div>
                                <div style="font-size: 13px; color: var(--text-muted, #888); margin-top: 4px; display: flex; flex-wrap: wrap; gap: 12px;">
                                    ${escapedIp ? `<span>🌐 ${escapedIp}</span>` : ""}
                                    <span>🕒 Last active: ${lastActive}</span>
                                    ${createdAt ? `<span>📅 Signed in: ${createdAt}</span>` : ""}
                                </div>
                            </div>
                        </div>
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <button class="btn ${s.isCurrent ? "outline" : "danger"}" type="button" data-session-id="${s.id}" data-is-current="${s.isCurrent}" style="padding: 6px 14px; font-size: 13px; border-radius: 6px; font-weight: 500;">
                                ${s.isCurrent ? "Log Out" : "Revoke Access"}
                            </button>
                        </div>
                    `;

                    const btn = item.querySelector("button[data-session-id]");
                    btn.addEventListener("click", async () => {
                        const isCurr = btn.dataset.isCurrent === "true";
                        const promptMsg = isCurr
                            ? "Are you sure you want to log out of this device?"
                            : `Revoke authorization for ${s.deviceName || "this device"}? That device will be immediately logged out.`;
                        if (!confirm(promptMsg)) return;

                        try {
                            btn.disabled = true;
                            btn.textContent = "Revoking...";
                            await Obsidianscout.request(`/api/user/sessions/${s.id}`, {
                                method: "DELETE"
                            });
                            Obsidianscout.showToast(isCurr ? "Logged out" : "Authorization revoked", "success");
                            if (isCurr) {
                                window.location.href = "/";
                            } else {
                                await loadSessions();
                            }
                        } catch (err) {
                            btn.disabled = false;
                            btn.textContent = isCurr ? "Log Out" : "Revoke Access";
                            Obsidianscout.showToast(err.message || "Failed to revoke authorization", "error");
                        }
                    });

                    listEl.appendChild(item);
                });
            } catch (err) {
                if (loadingEl) loadingEl.style.display = "none";
                listEl.innerHTML = `<div style="padding: 12px; color: var(--danger, #ef4444); font-size: 14px;">Failed to load active sessions: ${err.message || "Network error"}</div>`;
            }
        }

        if (revokeOthersBtn) {
            revokeOthersBtn.addEventListener("click", async () => {
                const confirmed = confirm("Are you sure you want to log out of all other devices and revoke their authorizations? Only your current device will remain logged in.");
                if (!confirmed) return;

                try {
                    revokeOthersBtn.disabled = true;
                    revokeOthersBtn.textContent = "Revoking...";
                    const res = await Obsidianscout.request("/api/user/sessions?othersOnly=true", {
                        method: "DELETE"
                    });
                    const count = res && res.revokedCount !== undefined ? res.revokedCount : "All other";
                    Obsidianscout.showToast(`Revoked ${count} other device authorization(s)`, "success");
                    await loadSessions();
                } catch (err) {
                    Obsidianscout.showToast(err.message || "Failed to revoke other sessions", "error");
                } finally {
                    revokeOthersBtn.disabled = false;
                    revokeOthersBtn.textContent = "Revoke All Other Devices";
                }
            });
        }

        loadSessions();
    }

    wirePersonalAvatarWidget(me);
    wirePersonalUsernameWidget(me);
    wirePersonalPasswordWidget();
    wirePersonalEmailWidget(me);
    wirePersonalNotificationPrefWidget(me);
    wirePersonalNodeAlertsWidget(me);
    wirePersonalDeviceSessionsWidget(me);
    wirePersonalDeleteAccountWidget(me);
    wirePersonalHapticPrefWidget();
    wirePersonalNavLayoutWidget();
});
