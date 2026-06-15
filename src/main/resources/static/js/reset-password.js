document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();

    const urlParams = new URLSearchParams(window.location.search);
    const token = urlParams.get("token");

    const loadingToken = document.getElementById("loading-token");
    const invalidTokenPanel = document.getElementById("invalid-token-panel");
    const resetFormPanel = document.getElementById("reset-form-panel");
    const resetWelcome = document.getElementById("reset-welcome");
    const resetForm = document.getElementById("reset-password-form");
    const resetSubmit = document.getElementById("reset-submit");

    if (!token) {
        if (loadingToken) {
            loadingToken.classList.add("hidden");
            loadingToken.hidden = true;
        }
        if (invalidTokenPanel) {
            invalidTokenPanel.classList.remove("hidden");
            invalidTokenPanel.hidden = false;
        }
        return;
    }

    try {
        const response = await Obsidianscout.request(`/api/auth/verify-reset-token?token=${encodeURIComponent(token)}`);
        if (loadingToken) {
            loadingToken.classList.add("hidden");
            loadingToken.hidden = true;
        }
        
        if (response.valid) {
            if (resetWelcome) {
                resetWelcome.textContent = `Reset password for ${response.username} (Team ${response.teamNumber})`;
            }
            if (resetFormPanel) {
                resetFormPanel.classList.remove("hidden");
                resetFormPanel.hidden = false;
            }
        } else {
            if (invalidTokenPanel) {
                invalidTokenPanel.classList.remove("hidden");
                invalidTokenPanel.hidden = false;
            }
        }
    } catch (error) {
        if (loadingToken) {
            loadingToken.classList.add("hidden");
            loadingToken.hidden = true;
        }
        if (invalidTokenPanel) {
            invalidTokenPanel.classList.remove("hidden");
            invalidTokenPanel.hidden = false;
        }
    }

    if (resetForm && resetSubmit) {
        resetForm.addEventListener("submit", async (e) => {
            e.preventDefault();
            resetSubmit.disabled = true;

            const newPassword = document.getElementById("new-password").value;
            const confirmPassword = document.getElementById("confirm-password").value;

            if (newPassword !== confirmPassword) {
                Obsidianscout.showToast("Passwords do not match", "error");
                resetSubmit.disabled = false;
                return;
            }

            try {
                await Obsidianscout.request("/api/auth/reset-password", {
                    method: "POST",
                    json: {
                        token: token,
                        newPassword: newPassword
                    }
                });
                Obsidianscout.showToast("Password reset successful!", "success");
                setTimeout(() => {
                    window.location.href = "/index";
                }, 2000);
            } catch (error) {
                Obsidianscout.showToast(error.message || "Failed to reset password", "error");
                resetSubmit.disabled = false;
            }
        });
    }
});
