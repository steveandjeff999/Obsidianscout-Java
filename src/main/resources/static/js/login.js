document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const loginForm = document.getElementById("login-form");
    const loginButton = document.getElementById("login-submit");
    const registerForm = document.getElementById("register-form");
    const registerButton = document.getElementById("register-submit");

    const existing = await Obsidianscout.getMe();
    if (existing) {
        window.location.href = "/dashboard.html";
        return;
    }

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
                registerPanel.classList.remove("hidden");
            } else {
                loginPanel.classList.remove("hidden");
                registerPanel.classList.add("hidden");
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

        try {
            await Obsidianscout.request("/api/auth/login", {
                method: "POST",
                json: {
                    username,
                    teamNumber,
                    password
                }
            });
            window.location.href = "/dashboard.html";
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
        const teamNumber = parseInt(document.getElementById("reg-team").value, 10);
        const password = document.getElementById("reg-password").value;
        const confirm = document.getElementById("reg-confirm").value;
        const role = document.getElementById("reg-role").value;

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
                    teamNumber,
                    password,
                    role
                }
            });
            Obsidianscout.showToast("Account created!", "success");
            window.location.href = "/dashboard.html";
        } catch (error) {
            Obsidianscout.showToast(error.message || "Registration failed", "error");
        } finally {
            registerButton.disabled = false;
        }
    });
});
