/**
 * Component Setup Wizard Module - ObsidianScout
 * Multi-step first-time setup walkthrough modal for configuring event code, season, API keys, and game configs.
 */

import { safeSetItem } from '../base/storage.js';
import { request } from '../base/http.js';
import { showToast } from './toast.js';

export function showSetupWizardModal(me, settings, forceOpen = false) {
    if (document.getElementById("setup-wizard-backdrop")) return;

    const backdrop = document.createElement("div");
    backdrop.id = "setup-wizard-backdrop";
    backdrop.className = "modal-backdrop show";

    const container = document.createElement("div");
    container.className = "modal-container";
    container.style.width = "min(650px, 95vw)";
    container.style.maxHeight = "95vh";
    backdrop.appendChild(container);
    document.body.appendChild(backdrop);

    let currentStep = 1;
    const totalSteps = 4;
    
    const localSettings = JSON.parse(JSON.stringify(settings));
    if (!localSettings.apiKeys) {
        localSettings.apiKeys = { tbaKey: "", firstUsername: "", firstKey: "" };
    }
    
    let uploadedGameConfigJson = null;

    function updateStepUI() {
        const dots = container.querySelectorAll(".wizard-step-dot");
        dots.forEach((dot, index) => {
            const stepNum = index + 1;
            dot.classList.remove("active", "completed");
            if (stepNum === currentStep) {
                dot.classList.add("active");
            } else if (stepNum < currentStep) {
                dot.classList.add("completed");
                dot.innerHTML = "&#10003;";
            } else {
                dot.innerHTML = stepNum;
            }
        });

        const progressPercent = ((currentStep - 1) / (totalSteps - 1)) * 100;
        const progressBar = container.querySelector(".wizard-progress-bar");
        if (progressBar) {
            progressBar.style.width = `${progressPercent}%`;
        }

        const contents = container.querySelectorAll(".wizard-step-content");
        contents.forEach((content) => {
            const stepNum = parseInt(content.dataset.step, 10);
            if (stepNum === currentStep) {
                content.classList.add("active");
            } else {
                content.classList.remove("active");
            }
        });

        const btnBack = container.querySelector(".btn-wizard-back");
        const btnNext = container.querySelector(".btn-wizard-next");
        
        if (btnBack) {
            btnBack.style.display = currentStep === 1 ? "none" : "block";
        }
        if (btnNext) {
            if (currentStep === totalSteps) {
                btnNext.textContent = "Finish & Sync";
                btnNext.className = "btn btn-wizard-next";
            } else {
                btnNext.textContent = "Next";
                btnNext.className = "btn secondary btn-wizard-next";
            }
        }
    }

    function validateStep() {
        if (currentStep === 2) {
            const yearInput = container.querySelector("#wizard-year").value.trim();
            if (yearInput) {
                const yearVal = parseInt(yearInput, 10);
                if (isNaN(yearVal) || yearVal < 2000 || yearVal > 2100) {
                    showToast("Please enter a valid season year (e.g. 2026)", "error");
                    return false;
                }
            } else if (!localSettings.year) {
                showToast("Please enter a valid season year (e.g. 2026)", "error");
                return false;
            }

            const timezoneVal = container.querySelector("#wizard-timezone").value.trim();
            if (!timezoneVal && !localSettings.timezone) {
                showToast("Please enter a timezone (e.g. America/New_York)", "error");
                return false;
            }
        }
        return true;
    }

    function saveInputsToState() {
        if (currentStep === 2) {
            const yearInput = container.querySelector("#wizard-year");
            if (yearInput && yearInput.value) {
                const yearVal = parseInt(yearInput.value, 10);
                if (!isNaN(yearVal)) localSettings.year = yearVal;
            }
            const codeInput = container.querySelector("#wizard-event-code");
            if (codeInput) {
                const codeVal = codeInput.value.trim();
                if (codeVal) localSettings.eventCode = codeVal.toLowerCase();
            }
            const tzInput = container.querySelector("#wizard-timezone");
            if (tzInput) {
                const tzVal = tzInput.value.trim();
                if (tzVal) localSettings.timezone = tzVal;
            }
            const sourceInput = container.querySelector("#wizard-source");
            if (sourceInput && sourceInput.value) {
                localSettings.preferredSource = sourceInput.value;
            }
        } else if (currentStep === 3) {
            const tbaInput = container.querySelector("#wizard-tba-key");
            if (tbaInput) {
                const tbaVal = tbaInput.value.trim();
                if (tbaVal) localSettings.apiKeys.tbaKey = tbaVal;
            }
            const firstUserInput = container.querySelector("#wizard-first-user");
            if (firstUserInput) {
                const firstUserVal = firstUserInput.value.trim();
                if (firstUserVal) localSettings.apiKeys.firstUsername = firstUserVal;
            }
            const firstKeyInput = container.querySelector("#wizard-first-key");
            if (firstKeyInput) {
                const firstKeyVal = firstKeyInput.value.trim();
                if (firstKeyVal) localSettings.apiKeys.firstKey = firstKeyVal;
            }
        }
    }

    function closeWizard() {
        backdrop.remove();
    }

    async function handleCancel() {
        if (confirm("Are you sure you want to exit the setup wizard? This will skip the initial setup (you can still configure settings manually in Admin Settings) and prevent this wizard from showing again on every page reload.")) {
            try {
                localSettings.setupWizardCompleted = true;
                const response = await request("/api/settings", {
                    method: "PUT",
                    json: localSettings
                });
                safeSetItem("cache:/api/settings", JSON.stringify(response.settings || response));
                closeWizard();
                showToast("Setup skipped. You can configure settings anytime in Admin Settings.", "info");
                if (forceOpen) {
                    setTimeout(() => window.location.reload(), 1000);
                }
            } catch (e) {
                console.error("Failed to mark setup wizard as completed:", e);
                closeWizard();
            }
        }
    }

    const isFtc = me && me.program === "FTC";
    const seasonTitle = isFtc ? "FTC Event & Season Details" : "FRC Event & Season Details";
    const seasonDesc = isFtc ? "Specify your current season year and the event code to sync teams and matches from the FTC Scout APIs." : "Specify your current season year and the event code to sync teams and matches from the FRC APIs.";
    const yearDesc = isFtc ? "The 4-digit FTC season year." : "The 4-digit FRC season year.";
    const sourceTbaLabel = isFtc ? "FTC Scout" : "The Blue Alliance";
    const sourceFirstLabel = isFtc ? "FIRST FTC API" : "FIRST API";
    const credentialsDesc = isFtc ? "Enter your API credentials. FTC Scout does not require a key, but you can optionally configure official FIRST FTC API credentials below." : "Enter your API keys to enable automatic schedule syncing. Leave blank if syncing offline via QR codes.";
    const tbaKeyStyle = isFtc ? "display: none;" : "margin-bottom: 16px;";
    const firstUsernameLabel = isFtc ? "FIRST FTC API Username" : "FIRST API Username";
    const firstKeyLabel = isFtc ? "FIRST FTC API Key" : "FIRST API Key";

    container.innerHTML = `
        <div class="modal-header">
            <h2 class="modal-title">Admin Setup Wizard</h2>
            <button class="modal-close btn-wizard-cancel" aria-label="Close">&times;</button>
        </div>
        
        <div class="wizard-progress">
            <div class="wizard-progress-bar"></div>
            <div class="wizard-step-dot active">1</div>
            <div class="wizard-step-dot">2</div>
            <div class="wizard-step-dot">3</div>
            <div class="wizard-step-dot">4</div>
        </div>
        
        <div class="wizard-body" style="margin-bottom: 24px; min-height: 250px;">
            <div class="wizard-step-content active" data-step="1">
                <div class="wizard-welcome-card">
                    <span class="wizard-welcome-icon">🚀</span>
                    <h3 class="wizard-welcome-title">Welcome to ObsidianScout!</h3>
                    <p class="wizard-welcome-desc">Let's configure your team's scouting workspace in a few quick steps. We'll set up your event configurations, API keys, and confirm your scouting forms.</p>
                    
                    <div class="wizard-section-divider"></div>
                    
                    <p class="notice" style="margin-bottom: 16px;">
                        We highly recommend reviewing our Getting Started Guide first to learn how the database, syncing, and roles operate.
                    </p>
                    <a href="/docs" target="_blank" class="btn" style="display: inline-flex; align-items: center; justify-content: center; gap: 8px; text-decoration: none; width: 100%; max-width: 320px; margin: 0 auto 12px;">
                        📖 Read Getting Started Tutorial
                    </a>
                </div>
            </div>
            
            <div class="wizard-step-content" data-step="2">
                <h3 style="margin-top: 0; margin-bottom: 8px;">${seasonTitle}</h3>
                <p class="notice" style="margin-bottom: 16px;">${seasonDesc}</p>
                
                <div class="form-grid" style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px;">
                    <div class="field">
                        <label for="wizard-year">Season Year</label>
                        <input id="wizard-year" type="number" value="${localSettings.year || new Date().getFullYear()}" />
                        <span class="wizard-field-desc">${yearDesc}</span>
                    </div>
                    <div class="field">
                        <label for="wizard-event-code">Event Code</label>
                        <input id="wizard-event-code" type="text" placeholder="e.g. okok" value="${localSettings.eventCode || ''}" />
                        <span class="wizard-field-desc">Short event code (e.g., 'okok' for Oklahoma Regional).</span>
                    </div>
                    <div class="field">
                        <label for="wizard-timezone">Timezone</label>
                        <input id="wizard-timezone" type="text" placeholder="America/New_York" value="${localSettings.timezone || 'America/New_York'}" />
                        <span class="wizard-field-desc">Database logs and schedule offsets use this timezone.</span>
                    </div>
                    <div class="field">
                        <label for="wizard-source">Preferred API Source</label>
                        <select id="wizard-source">
                            <option value="tba" ${localSettings.preferredSource === 'tba' ? 'selected' : ''}>${sourceTbaLabel}</option>
                            <option value="first" ${localSettings.preferredSource === 'first' ? 'selected' : ''}>${sourceFirstLabel}</option>
                        </select>
                        <span class="wizard-field-desc">The primary API to fetch event schedule.</span>
                    </div>
                </div>
            </div>
            
            <div class="wizard-step-content" data-step="3">
                <h3 style="margin-top: 0; margin-bottom: 8px;">API Credentials</h3>
                <p class="notice" style="margin-bottom: 16px;">${credentialsDesc}</p>
                
                <div class="field" style="${tbaKeyStyle}">
                    <label for="wizard-tba-key">The Blue Alliance Read Key</label>
                    <input id="wizard-tba-key" type="password" placeholder="TBA Read API Key" value="${localSettings.apiKeys.tbaKey || ''}" />
                </div>
                
                <div class="wizard-section-divider"></div>
                
                <div class="split" style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px;">
                    <div class="field">
                        <label for="wizard-first-user">${firstUsernameLabel}</label>
                        <input id="wizard-first-user" type="text" placeholder="FIRST Username" value="${localSettings.apiKeys.firstUsername || ''}" />
                    </div>
                    <div class="field">
                        <label for="wizard-first-key">${firstKeyLabel}</label>
                        <input id="wizard-first-key" type="password" placeholder="FIRST API Secret Key" value="${localSettings.apiKeys.firstKey || ''}" />
                    </div>
                </div>
            </div>
            
            <div class="wizard-step-content" data-step="4">
                <h3 style="margin-top: 0; margin-bottom: 8px;">Verify Scouting Forms</h3>
                <p class="notice" style="margin-bottom: 16px;">ObsidianScout comes pre-loaded with default scouting forms. You can optionally upload a custom Game Form configuration JSON file below.</p>
                
                <div class="field">
                    <label class="btn ghost btn-file" style="display: inline-flex; width: 100%; justify-content: center; padding: 12px; margin-bottom: 12px; cursor: pointer;">
                        📁 Import Custom Game Form JSON
                        <input id="wizard-config-import" class="input-hidden" type="file" accept="application/json" />
                    </label>
                    <div id="wizard-import-status" class="notice" style="text-align: center; color: var(--accent-2); font-weight: 600;"></div>
                </div>
                
                <div class="wizard-card-preview">
                    <h4 style="margin: 0 0 8px 0; font-size: 14px;">Forms status:</h4>
                    <ul style="margin: 0; padding-left: 20px; font-size: 13px; color: var(--muted); display: grid; gap: 4px;">
                        <li>✓ Default Match/Game Scouting Form loaded</li>
                        <li>✓ Default Pit Scouting Form loaded</li>
                        <li>✓ Default Qualitative Scouting Form loaded</li>
                    </ul>
                </div>
            </div>
        </div>
        
        <div class="modal-footer">
            <button type="button" class="btn ghost btn-wizard-cancel" style="margin-right: auto;">Exit</button>
            <button type="button" class="btn ghost btn-wizard-back" style="display: none;">Back</button>
            <button type="button" class="btn secondary btn-wizard-next">Next</button>
        </div>
    `;

    const btnCancelList = container.querySelectorAll(".btn-wizard-cancel");
    btnCancelList.forEach(btn => btn.addEventListener("click", handleCancel));

    const btnBack = container.querySelector(".btn-wizard-back");
    btnBack.addEventListener("click", () => {
        if (currentStep > 1) {
            saveInputsToState();
            currentStep--;
            updateStepUI();
        }
    });

    const btnNext = container.querySelector(".btn-wizard-next");
    btnNext.addEventListener("click", async () => {
        if (!validateStep()) return;
        saveInputsToState();

        if (currentStep < totalSteps) {
            currentStep++;
            updateStepUI();
        } else {
            btnNext.disabled = true;
            btnNext.textContent = "Saving...";
            
            try {
                localSettings.setupWizardCompleted = true;
                
                const code = localSettings.eventCode.trim();
                if (code) {
                    localSettings.eventKey = `${localSettings.year}${code}`.toLowerCase();
                }
                
                const response = await request("/api/settings", {
                    method: "PUT",
                    json: localSettings
                });
                
                if (uploadedGameConfigJson) {
                    await request("/api/config", {
                        method: "PUT",
                        json: {
                            configJson: JSON.stringify(uploadedGameConfigJson)
                        }
                    });
                }

                safeSetItem("cache:/api/settings", JSON.stringify(response.settings || response));
                
                showToast("Configuration saved successfully!", "success");
                closeWizard();

                const eventKey = localSettings.eventKey;
                if (eventKey) {
                    showToast(`Initiating data sync for ${eventKey}...`, "info");
                    request(`/api/prescout/sync-event?eventKey=${eventKey}`, { method: "POST" })
                        .then((counts) => {
                            showToast(`Sync finished! Cached ${counts.syncedTeams || counts.teams || 0} teams and ${counts.syncedMatches || counts.matches || 0} matches.`, "success");
                            window.dispatchEvent(new CustomEvent("obsidianscout:offline-entries-synced"));
                            if (window.location.pathname.includes("dashboard") || window.location.pathname.includes("admin-settings")) {
                                setTimeout(() => window.location.reload(), 1500);
                            }
                        })
                        .catch(err => {
                            console.error("Sync failed:", err);
                            showToast("Initial sync failed: API keys might be invalid or rate limited.", "error");
                        });
                }
                
                if (forceOpen) {
                    setTimeout(() => window.location.reload(), 1000);
                }

            } catch (error) {
                console.error("Setup Wizard save failed:", error);
                showToast("Failed to save settings: " + error.message, "error");
                btnNext.disabled = false;
                btnNext.textContent = "Finish & Sync";
            }
        }
    });

    const configImportInput = container.querySelector("#wizard-config-import");
    if (configImportInput) {
        configImportInput.addEventListener("change", () => {
            const file = configImportInput.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = () => {
                const text = reader.result;
                try {
                    const parsed = JSON.parse(text);
                    if (!parsed.fields) parsed.fields = [];
                    uploadedGameConfigJson = parsed;
                    
                    const statusEl = container.querySelector("#wizard-import-status");
                    if (statusEl) {
                        statusEl.textContent = `✓ Custom Game Form "${parsed.title || 'Imported'}" verified and ready to save.`;
                    }
                    showToast("Scouting config file loaded successfully!", "success");
                } catch (e) {
                    showToast("Invalid config JSON file format.", "error");
                }
            };
            reader.readAsText(file);
        });
    }

    updateStepUI();
}
