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

    // Check for existing session in background
    Obsidianscout.getMe().then((existing) => {
        if (existing) {
            window.location.href = "/dashboard";
        }
    });
});
