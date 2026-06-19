
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

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

    const accountSelectField = document.getElementById("account-select-field");
    const accountSelect = document.getElementById("reset-account-select");
    const usernameInput = document.getElementById("reset-username");

    let accounts = [];
    let selectedUserId = null;

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
        
        if (response.valid && response.accounts && response.accounts.length > 0) {
            accounts = response.accounts;
            
            if (accounts.length > 1) {
                // Show dropdown
                if (accountSelectField) {
                    accountSelectField.classList.remove("hidden");
                    accountSelectField.hidden = false;
                }
                
                // Populate dropdown
                if (accountSelect) {
                    accountSelect.innerHTML = "";
                    accounts.forEach(acc => {
                        const opt = document.createElement("option");
                        opt.value = acc.userId;
                        opt.textContent = `${acc.username} (Team ${acc.teamNumber})`;
                        accountSelect.appendChild(opt);
                    });

                    // Set default selection to first account
                    selectedUserId = accounts[0].userId;
                    if (usernameInput) {
                        usernameInput.value = accounts[0].username;
                    }

                    // Handle selection changes
                    accountSelect.addEventListener("change", () => {
                        selectedUserId = parseInt(accountSelect.value, 10);
                        const matched = accounts.find(a => a.userId === selectedUserId);
                        if (matched && usernameInput) {
                            usernameInput.value = matched.username;
                        }
                    });
                }
                
                if (resetWelcome) {
                    resetWelcome.textContent = t('reset_password.reset_welcome', "Reset credentials for your email recovery link");
                }
            } else {
                // Only one account
                if (accountSelectField) {
                    accountSelectField.classList.add("hidden");
                    accountSelectField.hidden = true;
                }
                
                selectedUserId = accounts[0].userId;
                if (usernameInput) {
                    usernameInput.value = accounts[0].username;
                }

                if (resetWelcome) {
                    resetWelcome.textContent = `Reset credentials for ${accounts[0].username} (Team ${accounts[0].teamNumber})`;
                }
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
            const newUsername = usernameInput ? usernameInput.value.trim() : "";

            if (!newUsername) {
                Obsidianscout.showToast("Username is required", "error");
                resetSubmit.disabled = false;
                return;
            }

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
                        userId: selectedUserId,
                        newUsername: newUsername,
                        newPassword: newPassword
                    }
                });
                Obsidianscout.showToast("Credentials reset successful!", "success");
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
