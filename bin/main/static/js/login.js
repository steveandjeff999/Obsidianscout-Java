document.addEventListener("DOMContentLoaded", () => {
    Obsidianscout.initTheme();
    const loginForm = document.getElementById("login-form");
    const loginButton = document.getElementById("login-submit");
    const registerForm = document.getElementById("register-form");
    const registerButton = document.getElementById("register-submit");

    // Tab switching
    const tabs = document.querySelectorAll("#auth-tabs .tab");
    const loginPanel = document.getElementById("login-panel");
    const registerPanel = document.getElementById("register-panel");

    tabs.forEach((tab) => {
        tab.addEventListener("click", () => {
            tabs.forEach((t) => t.classList.remove("active"));
            tab.classList.add("active");
            if (tab.dataset.tab === "register") {
                loginPanel.classList.add("hidden");
                loginPanel.hidden = true;
                registerPanel.classList.remove("hidden");
                registerPanel.hidden = false;
            } else {
                loginPanel.classList.remove("hidden");
                loginPanel.hidden = false;
                registerPanel.classList.add("hidden");
                registerPanel.hidden = true;
            }
        });
    });

    // Login
    loginForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        loginButton.disabled = true;

        const username = document.getElementById("username").value.trim();
        const teamNumber = parseInt(document.getElementById("teamNumber").value, 10);
        const password = document.getElementById("password").value;
        const keepMeLoggedIn = document.getElementById("keepMeLoggedIn").checked;

        try {
            await Obsidianscout.request("/api/auth/login", {
                method: "POST",
                json: {
                    username,
                    teamNumber,
                    password,
                    keepMeLoggedIn
                }
            });
            window.location.href = "/dashboard";
        } catch (error) {
            Obsidianscout.showToast(error.message || "Sign in failed", "error");
        } finally {
            loginButton.disabled = false;
        }
    });

    // Register
    registerForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        registerButton.disabled = true;

        const username = document.getElementById("reg-username").value.trim();
        const email = document.getElementById("reg-email").value.trim();
        const teamNumber = parseInt(document.getElementById("reg-team").value, 10);
        const password = document.getElementById("reg-password").value;
        const confirm = document.getElementById("reg-confirm").value;
        const role = document.getElementById("reg-role").value;
        const keepMeLoggedIn = document.getElementById("reg-keepMeLoggedIn").checked;

        if (password !== confirm) {
            Obsidianscout.showToast("Passwords do not match", "error");
            registerButton.disabled = false;
            return;
        }

        if (teamNumber <= 0 || isNaN(teamNumber)) {
            Obsidianscout.showToast("Enter a valid team number", "error");
            registerButton.disabled = false;
            return;
        }

        try {
            await Obsidianscout.request("/api/auth/register", {
                method: "POST",
                json: {
                    username,
                    email,
                    teamNumber,
                    password,
                    role,
                    keepMeLoggedIn
                }
            });
            Obsidianscout.showToast("Account created!", "success");
            window.location.href = "/dashboard";
        } catch (error) {
            Obsidianscout.showToast(error.message || "Registration failed", "error");
        } finally {
            registerButton.disabled = false;
        }
    });

    // Forgot password panel switching
    const forgotLink = document.getElementById("forgot-password-link");
    const forgotPanel = document.getElementById("forgot-password-panel");
    const forgotForm = document.getElementById("forgot-password-form");
    const forgotSubmit = document.getElementById("forgot-submit");
    const forgotBack = document.getElementById("forgot-back-to-login");
    const authTabs = document.getElementById("auth-tabs");

    // Forgot password tab switching elements
    const forgotTabs = document.querySelectorAll("#forgot-tabs .tab");
    const forgotEmailField = document.getElementById("forgot-email-field");
    const forgotCredentialsFields = document.getElementById("forgot-credentials-fields");
    const forgotNotice = document.getElementById("forgot-notice");
    const forgotEmailInput = document.getElementById("forgot-email");
    const forgotUsernameInput = document.getElementById("forgot-username");
    const forgotTeamInput = document.getElementById("forgot-team");
    let activeForgotTab = "email"; // default

    if (forgotTabs.length > 0) {
        forgotTabs.forEach((tab) => {
            tab.addEventListener("click", () => {
                forgotTabs.forEach((t) => t.classList.remove("active"));
                tab.classList.add("active");
                activeForgotTab = tab.dataset.tab;

                if (activeForgotTab === "email") {
                    forgotEmailField.classList.remove("hidden");
                    forgotEmailField.hidden = false;
                    forgotEmailInput.required = true;

                    forgotCredentialsFields.classList.add("hidden");
                    forgotCredentialsFields.hidden = true;
                    forgotUsernameInput.required = false;
                    forgotTeamInput.required = false;

                    forgotNotice.textContent = "Enter your registered email address. We will send you a link to reset your password.";
                } else {
                    forgotEmailField.classList.add("hidden");
                    forgotEmailField.hidden = true;
                    forgotEmailInput.required = false;

                    forgotCredentialsFields.classList.remove("hidden");
                    forgotCredentialsFields.hidden = false;
                    forgotUsernameInput.required = true;
                    forgotTeamInput.required = true;

                    forgotNotice.textContent = "Enter your username and team number. If your account has a registered email, we will send you a password reset link.";
                }
            });
        });
    }

    if (forgotLink && forgotPanel && forgotBack && authTabs) {
        forgotLink.addEventListener("click", (e) => {
            e.preventDefault();
            loginPanel.classList.add("hidden");
            loginPanel.hidden = true;
            registerPanel.classList.add("hidden");
            registerPanel.hidden = true;
            authTabs.classList.add("hidden");
            authTabs.hidden = true;

            forgotPanel.classList.remove("hidden");
            forgotPanel.hidden = false;

            // Reset forgot password tab to email tab
            if (forgotTabs.length > 0) {
                forgotTabs[0].click();
            }
        });

        forgotBack.addEventListener("click", () => {
            forgotPanel.classList.add("hidden");
            forgotPanel.hidden = true;
            authTabs.classList.remove("hidden");
            authTabs.hidden = false;

            // default back to login tab
            tabs.forEach((t) => t.classList.remove("active"));
            tabs[0].classList.add("active");
            loginPanel.classList.remove("hidden");
            loginPanel.hidden = false;
        });
    }

    if (forgotForm && forgotSubmit) {
        forgotForm.addEventListener("submit", async (e) => {
            e.preventDefault();
            forgotSubmit.disabled = true;

            const payload = {};
            if (activeForgotTab === "email") {
                const email = forgotEmailInput.value.trim();
                if (!email) {
                    Obsidianscout.showToast("Email address is required", "error");
                    forgotSubmit.disabled = false;
                    return;
                }
                payload.email = email;
            } else {
                const username = forgotUsernameInput.value.trim();
                const teamNumber = parseInt(forgotTeamInput.value, 10);
                if (!username || isNaN(teamNumber)) {
                    Obsidianscout.showToast("Username and team number are required", "error");
                    forgotSubmit.disabled = false;
                    return;
                }
                payload.username = username;
                payload.teamNumber = teamNumber;
            }

            try {
                const response = await Obsidianscout.request("/api/auth/forgot-password", {
                    method: "POST",
                    json: payload
                });
                Obsidianscout.showToast(response.message || "Reset link sent successfully", "success");
                
                // Switch back to login
                forgotPanel.classList.add("hidden");
                forgotPanel.hidden = true;
                authTabs.classList.remove("hidden");
                authTabs.hidden = false;
                tabs.forEach((t) => t.classList.remove("active"));
                tabs[0].classList.add("active");
                loginPanel.classList.remove("hidden");
                loginPanel.hidden = false;
            } catch (error) {
                Obsidianscout.showToast(error.message || "Failed to send reset link", "error");
            } finally {
                forgotSubmit.disabled = false;
            }
        });
    }

    // Check for existing session in background
    Obsidianscout.checkLoginStatus().then((loggedIn) => {
        if (loggedIn) {
            window.location.href = "/dashboard";
        }
    });
});
