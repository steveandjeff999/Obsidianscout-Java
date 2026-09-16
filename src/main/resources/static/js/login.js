
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

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

    function switchTab(targetTab) {
        tabs.forEach((t) => {
            if (t.dataset.tab === targetTab) {
                t.classList.add("active");
            } else {
                t.classList.remove("active");
            }
        });
        if (targetTab === "register") {
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
    }

    tabs.forEach((tab) => {
        tab.addEventListener("click", () => {
            switchTab(tab.dataset.tab);
        });
    });

    // Check URL parameters or hash to open register tab directly
    try {
        const urlParams = new URLSearchParams(window.location.search);
        const modeParam = (urlParams.get("mode") || urlParams.get("tab") || urlParams.get("action") || "").toLowerCase();
        const hash = window.location.hash.toLowerCase();
        if (["register", "signup", "create", "create-account", "new"].includes(modeParam) || ["#register", "#signup", "#create", "#create-account"].includes(hash)) {
            switchTab("register");
        }
    } catch (e) {
        console.warn("Error parsing URL tab parameter:", e);
    }

    // Login
    loginForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        Obsidianscout.setButtonLoading(loginButton, true, t('login.signing_in', 'Signing in...'));

        const username = document.getElementById("username").value.trim();
        const teamNumber = parseInt(document.getElementById("teamNumber").value, 10);
        const program = document.getElementById("login-program").value;
        const password = document.getElementById("password").value;
        const keepMeLoggedIn = document.getElementById("keepMeLoggedIn").checked;

        try {
            await Obsidianscout.request("/api/auth/login", {
                method: "POST",
                json: {
                    username,
                    teamNumber,
                    program,
                    password,
                    keepMeLoggedIn
                }
            });
            await handlePostLoginPasskeyCheck();
        } catch (error) {
            Obsidianscout.showToast(error.message || "Sign in failed", "error");
        } finally {
            Obsidianscout.setButtonLoading(loginButton, false);
        }
    });

    // Passkey Login
    const passkeyLoginBtn = document.getElementById("passkey-login-btn");
    if (passkeyLoginBtn) {
        if (!window.PublicKeyCredential) {
            passkeyLoginBtn.style.display = "none";
            const divider = document.getElementById("passkey-divider");
            if (divider) divider.style.display = "none";
        } else {
            passkeyLoginBtn.addEventListener("click", async () => {
                Obsidianscout.setButtonLoading(passkeyLoginBtn, true, t('login.authenticating_passkey', 'Authenticating...'));
                try {
                    const username = document.getElementById("username") ? document.getElementById("username").value.trim() : "";
                    const teamNumRaw = document.getElementById("teamNumber") ? document.getElementById("teamNumber").value.trim() : "";
                    const teamNumber = teamNumRaw ? parseInt(teamNumRaw, 10) : null;
                    const program = document.getElementById("login-program") ? document.getElementById("login-program").value : "FRC";
                    const keepMeLoggedIn = document.getElementById("keepMeLoggedIn") ? document.getElementById("keepMeLoggedIn").checked : false;

                    // 1. Get options from server
                    const options = await Obsidianscout.request("/api/auth/passkey/authenticate/begin", {
                        method: "POST",
                        json: {
                            username: username || null,
                            teamNumber: teamNumber && teamNumber > 0 ? teamNumber : null,
                            program: program || "FRC"
                        }
                    });

                    // Format publicKey credential request options
                    const publicKey = {
                        challenge: base64UrlToUint8Array(options.challenge),
                        timeout: options.timeout || 60000,
                        rpId: options.rpId,
                        userVerification: options.userVerification || "preferred"
                    };

                    if (options.allowCredentials && options.allowCredentials.length > 0) {
                        publicKey.allowCredentials = options.allowCredentials.map(c => ({
                            type: "public-key",
                            id: base64UrlToUint8Array(c.id)
                        }));
                    }

                    const credential = await navigator.credentials.get({ publicKey });
                    if (!credential) {
                        throw new Error("No credential returned");
                    }

                    const finishPayload = {
                        credentialId: credential.id,
                        clientDataJSON: arrayBufferToBase64Url(credential.response.clientDataJSON),
                        authenticatorData: arrayBufferToBase64Url(credential.response.authenticatorData),
                        signature: arrayBufferToBase64Url(credential.response.signature),
                        userHandle: credential.response.userHandle ? arrayBufferToBase64Url(credential.response.userHandle) : null,
                        keepMeLoggedIn: keepMeLoggedIn
                    };

                    await Obsidianscout.request("/api/auth/passkey/authenticate/finish", {
                        method: "POST",
                        json: finishPayload
                    });

                    window.location.href = "/dashboard";
                } catch (err) {
                    if (err.name !== "NotAllowedError") {
                        console.error("Passkey auth error:", err);
                        Obsidianscout.showToast(err.message || "Passkey authentication failed", "error");
                    }
                } finally {
                    Obsidianscout.setButtonLoading(passkeyLoginBtn, false);
                }
            });
        }
    }

    // Helper functions for WebAuthn Base64URL conversions
    function base64UrlToUint8Array(base64Url) {
        const padding = '='.repeat((4 - (base64Url.length % 4)) % 4);
        const base64 = (base64Url + padding).replace(/-/g, '+').replace(/_/g, '/');
        const raw = window.atob(base64);
        const output = new Uint8Array(raw.length);
        for (let i = 0; i < raw.length; ++i) {
            output[i] = raw.charCodeAt(i);
        }
        return output;
    }

    function arrayBufferToBase64Url(buffer) {
        const bytes = new Uint8Array(buffer);
        let binary = '';
        for (let i = 0; i < bytes.byteLength; i++) {
            binary += String.fromCharCode(bytes[i]);
        }
        return window.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
    }

    function getDefaultDeviceName() {
        const ua = navigator.userAgent || "";
        let os = "Device";
        if (/iPhone/i.test(ua)) os = "iPhone";
        else if (/iPad/i.test(ua)) os = "iPad";
        else if (/Android/i.test(ua)) os = "Android Device";
        else if (/Macintosh|Mac OS/i.test(ua)) os = "Mac";
        else if (/Windows/i.test(ua)) os = "Windows PC";
        else if (/CrOS/i.test(ua)) os = "Chromebook";
        else if (/Linux/i.test(ua)) os = "Linux PC";

        let browser = "";
        if (/Edg\//i.test(ua)) browser = "Edge";
        else if (/OPR\//i.test(ua) || /Opera/i.test(ua)) browser = "Opera";
        else if (/SamsungBrowser/i.test(ua)) browser = "Samsung Internet";
        else if (/Chrome/i.test(ua) && !/Chromium/i.test(ua)) browser = "Chrome";
        else if (/Firefox/i.test(ua)) browser = "Firefox";
        else if (/Safari/i.test(ua) && !/Chrome/i.test(ua)) browser = "Safari";

        if (browser) {
            return `${os} (${browser})`;
        }
        return os;
    }

    const PASSKEY_DISMISSED_KEY = "obsidianscout_passkey_prompt_dismissed";

    async function promptPasskeyEnrollment() {
        return new Promise((resolve) => {
            let backdrop = document.getElementById("passkey-enroll-modal-backdrop");
            if (!backdrop) {
                backdrop = document.createElement("div");
                backdrop.id = "passkey-enroll-modal-backdrop";
                backdrop.className = "modal-backdrop";
                document.body.appendChild(backdrop);
            }

            const defaultDevice = getDefaultDeviceName();

            backdrop.innerHTML = `
                <div class="modal-container" style="max-width: 460px;">
                    <div class="modal-header">
                        <div style="display: flex; align-items: center; gap: 10px;">
                            <div style="background: rgba(99, 102, 241, 0.15); color: var(--primary, #6366f1); width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center;">
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <circle cx="12" cy="12" r="3"></circle>
                                    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
                                </svg>
                            </div>
                            <h3 class="modal-title" style="font-size: 1.15rem;">Set up a Passkey</h3>
                        </div>
                        <button class="modal-close" id="passkey-prompt-close-btn">&times;</button>
                    </div>
                    <div class="modal-body">
                        <p style="color: var(--text-muted, #94a3b8); font-size: 0.95rem; line-height: 1.5; margin-bottom: 16px;">
                            Sign in faster and more securely next time using Windows Hello, Touch ID, Face ID, or your security key instead of entering your password.
                        </p>
                        <div class="field" style="margin-bottom: 8px;">
                            <label for="passkey-prompt-name" style="font-size: 0.85rem; font-weight: 600; margin-bottom: 6px; display: block;">Device Name</label>
                            <input id="passkey-prompt-name" type="text" value="${defaultDevice}" style="width: 100%; box-sizing: border-box;" />
                        </div>
                    </div>
                    <div class="modal-footer" style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 24px;">
                        <button class="btn ghost" id="passkey-prompt-skip-btn" type="button">Not Now</button>
                        <button class="btn" id="passkey-prompt-add-btn" type="button" style="display: flex; align-items: center; gap: 8px;">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                <circle cx="12" cy="12" r="3"></circle>
                                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
                            </svg>
                            <span>Add Passkey</span>
                        </button>
                    </div>
                </div>
            `;

            const closeDialog = (dismissedPermanently = false) => {
                if (dismissedPermanently) {
                    try {
                        localStorage.setItem(PASSKEY_DISMISSED_KEY, "true");
                    } catch (e) {
                        console.warn("Could not save passkey dismissal to localStorage:", e);
                    }
                }
                backdrop.classList.remove("show");
                setTimeout(() => {
                    if (backdrop.parentElement) backdrop.parentElement.removeChild(backdrop);
                    resolve();
                }, 200);
            };

            const closeBtn = backdrop.querySelector("#passkey-prompt-close-btn");
            const skipBtn = backdrop.querySelector("#passkey-prompt-skip-btn");
            const addBtn = backdrop.querySelector("#passkey-prompt-add-btn");
            const nameInput = backdrop.querySelector("#passkey-prompt-name");

            if (closeBtn) {
                closeBtn.addEventListener("click", () => closeDialog(false));
            }
            if (skipBtn) {
                skipBtn.addEventListener("click", () => closeDialog(true));
            }

            if (addBtn) {
                addBtn.addEventListener("click", async () => {
                    const friendlyName = (nameInput && nameInput.value.trim()) || defaultDevice;
                    Obsidianscout.setButtonLoading(addBtn, true, "Registering...");
                    try {
                        const options = await Obsidianscout.request("/api/auth/passkey/register/begin", {
                            method: "POST"
                        });

                        const publicKey = {
                            challenge: base64UrlToUint8Array(options.challenge),
                            rp: options.rp,
                            user: {
                                id: base64UrlToUint8Array(options.user.id),
                                name: options.user.name,
                                displayName: options.user.displayName
                            },
                            pubKeyCredParams: options.pubKeyCredParams,
                            timeout: options.timeout || 60000,
                            attestation: options.attestation || "none",
                            authenticatorSelection: options.authenticatorSelection || {}
                        };

                        if (options.excludeCredentials && options.excludeCredentials.length > 0) {
                            publicKey.excludeCredentials = options.excludeCredentials.map(c => ({
                                type: "public-key",
                                id: base64UrlToUint8Array(c.id),
                                transports: c.transports
                            }));
                        }

                        const credential = await navigator.credentials.create({ publicKey });
                        if (!credential) {
                            throw new Error("No credential was created");
                        }

                        const finishPayload = {
                            credentialId: credential.id,
                            clientDataJSON: arrayBufferToBase64Url(credential.response.clientDataJSON),
                            attestationObject: arrayBufferToBase64Url(credential.response.attestationObject),
                            friendlyName: friendlyName
                        };

                        await Obsidianscout.request("/api/auth/passkey/register/finish", {
                            method: "POST",
                            json: finishPayload
                        });

                        Obsidianscout.showToast("Passkey added successfully!", "success");
                        closeDialog(false);
                    } catch (err) {
                        if (err.name !== "NotAllowedError") {
                            console.error("Passkey registration error:", err);
                            Obsidianscout.showToast(err.message || "Failed to register passkey", "error");
                        }
                        Obsidianscout.setButtonLoading(addBtn, false);
                    }
                });
            }

            backdrop.classList.add("show");
        });
    }

    async function handlePostLoginPasskeyCheck() {
        try {
            if (!window.PublicKeyCredential) {
                window.location.href = "/dashboard";
                return;
            }

            const dismissed = localStorage.getItem(PASSKEY_DISMISSED_KEY);
            if (dismissed === "true") {
                window.location.href = "/dashboard";
                return;
            }

            // Check if user already has credentials registered
            const credentials = await Obsidianscout.request("/api/auth/passkey/credentials");
            if (Array.isArray(credentials) && credentials.length > 0) {
                // User already has passkeys registered
                window.location.href = "/dashboard";
                return;
            }

            // Prompt user to enroll passkey
            await promptPasskeyEnrollment();
            window.location.href = "/dashboard";
        } catch (e) {
            console.warn("Passkey check failed, continuing to dashboard:", e);
            window.location.href = "/dashboard";
        }
    }

    // Register
    registerForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        Obsidianscout.setButtonLoading(registerButton, true, t('login.registering', 'Registering...'));

        const username = document.getElementById("reg-username").value.trim();
        const email = document.getElementById("reg-email").value.trim();
        const teamNumber = parseInt(document.getElementById("reg-team").value, 10);
        const program = document.getElementById("reg-program").value;
        const password = document.getElementById("reg-password").value;
        const confirm = document.getElementById("reg-confirm").value;
        const role = document.getElementById("reg-role").value;
        const keepMeLoggedIn = document.getElementById("reg-keepMeLoggedIn").checked;

        if (password !== confirm) {
            Obsidianscout.showToast("Passwords do not match", "error");
            Obsidianscout.setButtonLoading(registerButton, false);
            return;
        }

        if (teamNumber <= 0 || isNaN(teamNumber)) {
            Obsidianscout.showToast("Enter a valid team number", "error");
            Obsidianscout.setButtonLoading(registerButton, false);
            return;
        }

        const lockedNotice = document.getElementById("register-locked-notice");
        if (lockedNotice) {
            lockedNotice.style.display = "none";
            lockedNotice.classList.add("hidden");
        }

        try {
            await Obsidianscout.request("/api/auth/register", {
                method: "POST",
                json: {
                    username,
                    email,
                    teamNumber,
                    program,
                    password,
                    role,
                    keepMeLoggedIn
                }
            });
            Obsidianscout.showToast("Account created!", "success");
            window.location.href = "/dashboard";
        } catch (error) {
            const errorMsg = error.message || "Registration failed";
            Obsidianscout.showToast(errorMsg, "error");
            if (lockedNotice && (errorMsg.toLowerCase().includes("locked") || error.status === 403)) {
                lockedNotice.style.display = "block";
                lockedNotice.classList.remove("hidden");
            }
        } finally {
            Obsidianscout.setButtonLoading(registerButton, false);
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

                    forgotNotice.textContent = t('login.enter_email', "Enter your registered email address. We will send you a link to reset your password.");
                } else {
                    forgotEmailField.classList.add("hidden");
                    forgotEmailField.hidden = true;
                    forgotEmailInput.required = false;

                    forgotCredentialsFields.classList.remove("hidden");
                    forgotCredentialsFields.hidden = false;
                    forgotUsernameInput.required = true;
                    forgotTeamInput.required = true;

                    forgotNotice.textContent = t('login.enter_username_team', "Enter your username and team number. If your account has a registered email, we will send you a password reset link.");
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
            Obsidianscout.setButtonLoading(forgotSubmit, true, "Sending...");

            const payload = {};
            if (activeForgotTab === "email") {
                const email = forgotEmailInput.value.trim();
                if (!email) {
                    Obsidianscout.showToast("Email address is required", "error");
                    Obsidianscout.setButtonLoading(forgotSubmit, false);
                    return;
                }
                payload.email = email;
            } else {
                const username = forgotUsernameInput.value.trim();
                const teamNumber = parseInt(forgotTeamInput.value, 10);
                if (!username || isNaN(teamNumber)) {
                    Obsidianscout.showToast("Username and team number are required", "error");
                    Obsidianscout.setButtonLoading(forgotSubmit, false);
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
                Obsidianscout.setButtonLoading(forgotSubmit, false);
            }
        });
    }

    // Check for existing session in background, validating program type match
    const loginProgramSelect = document.getElementById("login-program");

    async function verifySessionAndRedirect() {
        const loggedIn = await Obsidianscout.checkLoginStatus();
        if (loggedIn) {
            const user = await Obsidianscout.getMe();
            const selectedProgram = loginProgramSelect ? loginProgramSelect.value : "FRC";
            if (user && user.program && selectedProgram && user.program.toUpperCase() !== selectedProgram.toUpperCase()) {
                // Active session program differs from selected program; do not redirect.
                return;
            }
            window.location.href = "/dashboard";
        }
    }

    verifySessionAndRedirect();

    if (loginProgramSelect) {
        loginProgramSelect.addEventListener("change", () => {
            verifySessionAndRedirect();
        });
    }
});

