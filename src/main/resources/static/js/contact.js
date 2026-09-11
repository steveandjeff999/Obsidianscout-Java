document.addEventListener("DOMContentLoaded", async () => {
    if (window.Obsidianscout && typeof Obsidianscout.initTheme === 'function') {
        Obsidianscout.initTheme();
    }

    let me = null;
    try {
        if (window.Obsidianscout && typeof Obsidianscout.checkLoginStatus === 'function') {
            const loggedIn = await Obsidianscout.checkLoginStatus();
            if (loggedIn) {
                me = await Obsidianscout.getMe();
            }
        }
    } catch (e) {
        console.warn("Contact auth check failed:", e);
    }

    if (window.Obsidianscout && typeof Obsidianscout.wireThemeToggle === 'function') {
        Obsidianscout.wireThemeToggle();
    }

    if (me) {
        document.body.classList.remove("is-guest");
        if (window.Obsidianscout) {
            Obsidianscout.setUserBadge(me);
            Obsidianscout.setActiveNav();
            Obsidianscout.adjustNavForRole(me);
            Obsidianscout.wireLogout();
        }
    } else {
        document.body.classList.add("is-guest");
    }

    // Populate pre-filled fields
    const nameInput = document.getElementById("contact-name");
    const emailInput = document.getElementById("contact-email");
    const teamInput = document.getElementById("contact-team");
    const form = document.getElementById("contact-form");
    const submitBtn = document.getElementById("contact-submit-btn");
    const submitSpinner = document.getElementById("submit-spinner");
    const btnText = document.getElementById("btn-text");

    if (nameInput) nameInput.value = me ? (me.username || "") : "";
    if (emailInput) emailInput.value = me ? (me.email || "") : "";
    if (teamInput) {
        if (me) {
            teamInput.value = me.teamNumber || "";
        } else {
            teamInput.removeAttribute("readonly");
            teamInput.removeAttribute("disabled");
            teamInput.setAttribute("placeholder", "e.g. 5454 (optional)");
        }
    }

    if (form) {
        form.addEventListener("submit", async (e) => {
            e.preventDefault();

            // Set loading state
            if (submitBtn) submitBtn.disabled = true;
            if (submitSpinner) submitSpinner.style.display = "block";
            if (btnText) btnText.textContent = Obsidianscout.t("contact.btn.sending", "Sending...");
            
            const name = nameInput ? nameInput.value.trim() : "";
            const replyToEmail = emailInput ? emailInput.value.trim() : "";
            const type = document.getElementById("contact-type").value;
            const message = document.getElementById("contact-message").value;

            try {
                const teamVal = teamInput && teamInput.value ? parseInt(teamInput.value.trim(), 10) : null;
                const response = await Obsidianscout.request("/api/contact", {
                    method: "POST",
                    json: {
                        type: type,
                        name: name,
                        replyToEmail: replyToEmail || null,
                        teamNumber: isNaN(teamVal) ? null : teamVal,
                        message: message
                    }
                });

                Obsidianscout.showToast(
                    Obsidianscout.t("contact.success", "Your message has been sent successfully to obsidianscoutfrc@gmail.com!"),
                    "success"
                );

                // Clear message field after success
                document.getElementById("contact-message").value = "";
            } catch (err) {
                console.error("Failed to send contact message:", err);
                let errorMsg = err.message || "Unknown error";
                
                // Show localized error for SMTP missing
                if (err.status === 503) {
                    errorMsg = Obsidianscout.t(
                        "contact.error.smtp",
                        "SMTP email configuration is missing or incorrect. Please contact your team admin."
                    );
                } else {
                    errorMsg = Obsidianscout.t("contact.error.generic", "An error occurred while sending the message: ") + errorMsg;
                }

                Obsidianscout.showToast(errorMsg, "error");
            } finally {
                // Restore state
                if (submitBtn) {
                    submitBtn.disabled = false;
                    Obsidianscout.setButtonLoading(submitBtn, false);
                }
                if (submitSpinner) submitSpinner.style.display = "none";
                if (btnText) btnText.textContent = Obsidianscout.t("contact.btn.send", "Send Message");
            }
        });
    }
});
