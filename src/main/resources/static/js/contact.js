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

    // Populate pre-filled fields
    const nameInput = document.getElementById("contact-name");
    const emailInput = document.getElementById("contact-email");
    const teamInput = document.getElementById("contact-team");
    const form = document.getElementById("contact-form");
    const submitBtn = document.getElementById("contact-submit-btn");
    const submitSpinner = document.getElementById("submit-spinner");
    const btnText = document.getElementById("btn-text");

    if (nameInput) nameInput.value = me.username || "";
    if (emailInput) emailInput.value = me.email || "";
    if (teamInput) teamInput.value = me.teamNumber || "";

    if (form) {
        form.addEventListener("submit", async (e) => {
            e.preventDefault();

            // Set loading state
            submitBtn.disabled = true;
            submitSpinner.style.display = "block";
            btnText.textContent = Obsidianscout.t("contact.btn.sending", "Sending...");
            
            const name = nameInput ? nameInput.value.trim() : "";
            const replyToEmail = emailInput ? emailInput.value.trim() : "";
            const type = document.getElementById("contact-type").value;
            const message = document.getElementById("contact-message").value;

            try {
                const response = await Obsidianscout.request("/api/contact", {
                    method: "POST",
                    json: {
                        type: type,
                        name: name,
                        replyToEmail: replyToEmail || null,
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
                submitBtn.disabled = false;
                submitSpinner.style.display = "none";
                btnText.textContent = Obsidianscout.t("contact.btn.send", "Send Message");
            }
        });
    }
});
