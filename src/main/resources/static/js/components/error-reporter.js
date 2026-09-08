/**
 * Component Error Reporter Module - ObsidianScout
 * Global JavaScript runtime error and unhandled promise rejection detector.
 * Presents a transparent reporting modal asking users if they want to report bugs,
 * disclosure of team number, program, and user level, optional toggles to always send
 * or never ask again, and notes that preferences can be adjusted in User Settings.
 */

import { request, getCachedData } from '../base/http.js';
import { getMe } from '../base/auth.js';
import { showToast } from './toast.js';

let isModalOpen = false;
const recentErrorTimestamps = new Map();
const COOLDOWN_MS = 15000; // 15s cooldown for identical errors

/**
 * Checks whether an error is a benign browser artifact or extension noise.
 */
function isBenignError(error) {
    if (!error || !error.message) return true;
    const msg = error.message.toLowerCase();
    if (msg.includes("resizeobserver loop limit exceeded") ||
        msg.includes("resizeobserver loop completed with undelivered notifications") ||
        msg.includes("script error.") ||
        msg.includes("canceled") ||
        msg.includes("abort") ||
        msg.includes("network error when attempting to fetch resource")
    ) {
        return true;
    }
    const file = (error.filename || "").toLowerCase();
    if (file.includes("chrome-extension://") ||
        file.includes("moz-extension://") ||
        file.includes("safari-web-extension://")
    ) {
        return true;
    }
    return false;
}

/**
 * Dispatches an error report to the backend.
 */
async function sendErrorReportToServer(error, me) {
    const payload = {
        errorMessage: error.message || "Unknown client error",
        errorStack: error.stack || null,
        errorUrl: error.filename || window.location.href,
        lineNumber: error.lineno || null,
        columnNumber: error.colno || null,
        teamNumber: me ? me.teamNumber : null,
        program: me ? me.program : null,
        userRole: me ? me.role : null,
        username: me ? me.username : null,
        clientType: "web",
        userAgent: navigator.userAgent
    };

    try {
        await request("/api/bug-reports", {
            method: "POST",
            json: payload
        });
    } catch (err) {
        console.warn("[ErrorReporter] Could not deliver bug report to server:", err);
    }
}

/**
 * Saves the user's bug reporting preference to the server database.
 */
async function savePreferenceToServer(newPref, me) {
    if (me) {
        me.bugReportPreference = newPref;
    }
    try {
        localStorage.setItem("obsidianscout:bug_report_preference", newPref);
        await request("/api/user/profile-picture", {
            method: "PUT",
            json: { bugReportPreference: newPref }
        });
        try {
            localStorage.removeItem("cache:/api/auth/me");
            localStorage.removeItem("etag:/api/auth/me");
        } catch (_) {}
    } catch (err) {
        console.warn("[ErrorReporter] Could not persist preference to server:", err);
    }
}

/**
 * Handles incoming JS errors and coordinates prompting or silent transmission.
 */
async function handleJsError(error) {
    if (isBenignError(error)) return;

    // Rate-limit identical errors
    const errorKey = `${error.message}:${error.filename}:${error.lineno}`;
    const now = Date.now();
    const lastSeen = recentErrorTimestamps.get(errorKey);
    if (lastSeen && (now - lastSeen) < COOLDOWN_MS) {
        return;
    }
    recentErrorTimestamps.set(errorKey, now);

    // Retrieve user session info
    let me = null;
    try {
        me = await getMe();
    } catch (_) {}

    // Check preference
    const preference = (me && me.bugReportPreference)
        || localStorage.getItem("obsidianscout:bug_report_preference")
        || "ask";

    // If preference is 'never', completely suppress
    if (preference === "never") {
        return;
    }

    // If preference is 'always', silently send and do not display popup
    if (preference === "always") {
        sendErrorReportToServer(error, me);
        return;
    }

    // Preference is 'ask': present popup if not already showing one
    if (isModalOpen) return;
    isModalOpen = true;

    showErrorPromptModal(error, me);
}

/**
 * Renders the modal prompt asking the user if they would like to send the error back.
 */
function showErrorPromptModal(error, me) {
    const teamNumber = (me && me.teamNumber !== undefined) ? me.teamNumber : "Not logged in";
    const program = (me && me.program) ? me.program : "FRC";
    const userRole = (me && me.role) ? me.role : "Guest";

    const backdrop = document.createElement("div");
    backdrop.className = "modal-backdrop show";
    backdrop.id = "error-reporter-modal-backdrop";
    backdrop.style.zIndex = "999999";

    const container = document.createElement("div");
    container.className = "modal-container";
    container.style.maxWidth = "520px";

    container.innerHTML = `
        <div class="modal-header" style="margin-bottom: 16px;">
            <h2 class="modal-title" style="display: flex; align-items: center; gap: 10px; font-size: 19px; color: var(--danger, #ef4444);">
                Application Error Detected
            </h2>
            <button type="button" class="modal-close" id="btn-error-close" aria-label="Close">✕</button>
        </div>

        <p style="font-size: 14.5px; line-height: 1.5; margin-top: 0; color: var(--ink, #f1f5f9);">
            ObsidianScout encountered an unexpected error. Would you like to send this bug report back to the administrators so it can be fixed?
        </p>

        <div style="background: rgba(239, 68, 68, 0.08); border: 1px solid rgba(239, 68, 68, 0.25); border-radius: 8px; padding: 12px; margin-bottom: 16px;">
            <p style="margin: 0; font-size: 13px; color: var(--ink, #f1f5f9); font-weight: 500;">
                <strong>Report contents:</strong> This report will include your <strong>Team Number (${teamNumber})</strong>, <strong>Program (${program})</strong>, and <strong>User Level (${userRole})</strong>, along with technical error details.
            </p>
        </div>

        <div style="background: var(--surface-2, rgba(0,0,0,0.2)); border: 1px solid rgba(255,255,255,0.08); border-radius: 8px; padding: 10px 12px; margin-bottom: 18px; max-height: 80px; overflow-y: auto; font-family: monospace; font-size: 12px; color: #fca5a5; word-break: break-word;">
            ${error.message || "Unknown error"}
        </div>

        <div style="display: flex; flex-direction: column; gap: 10px; margin-bottom: 20px; background: rgba(255, 255, 255, 0.03); border-radius: 8px; padding: 12px; border: 1px solid rgba(255,255,255,0.06);">
            <label style="display: flex; align-items: center; gap: 10px; font-size: 13.5px; cursor: pointer; color: var(--ink, #f1f5f9);">
                <input type="checkbox" id="chk-always-send" style="width: 17px; height: 17px; cursor: pointer;" />
                <span>Always send bug reports automatically</span>
            </label>
            <label style="display: flex; align-items: center; gap: 10px; font-size: 13.5px; cursor: pointer; color: var(--text-muted, #94a3b8);">
                <input type="checkbox" id="chk-never-ask" style="width: 17px; height: 17px; cursor: pointer;" />
                <span>Never ask me again (hide error popups)</span>
            </label>
        </div>

        <p style="font-size: 12px; color: var(--text-muted, #94a3b8); margin: 0 0 20px 0;">
            Note: You can change this preference at any time in <strong>User Settings</strong>.
        </p>

        <div style="display: flex; justify-content: flex-end; gap: 10px; flex-wrap: wrap;">
            <button type="button" class="btn ghost" id="btn-error-cancel" style="padding: 8px 16px;">Don't Send</button>
            <button type="button" class="btn" id="btn-error-send" style="background: #3b82f6; color: #ffffff; padding: 8px 18px; font-weight: 600;">Send Error Report</button>
        </div>
    `;

    backdrop.appendChild(container);
    document.body.appendChild(backdrop);

    const chkAlways = container.querySelector("#chk-always-send");
    const chkNever = container.querySelector("#chk-never-ask");
    const btnSend = container.querySelector("#btn-error-send");
    const btnCancel = container.querySelector("#btn-error-cancel");
    const btnClose = container.querySelector("#btn-error-close");

    // Mutual exclusivity for toggles
    chkAlways?.addEventListener("change", () => {
        if (chkAlways.checked && chkNever) chkNever.checked = false;
    });
    chkNever?.addEventListener("change", () => {
        if (chkNever.checked && chkAlways) chkAlways.checked = false;
    });

    const closeModal = () => {
        isModalOpen = false;
        if (backdrop.parentElement) {
            backdrop.parentElement.removeChild(backdrop);
        }
    };

    btnSend?.addEventListener("click", async () => {
        btnSend.disabled = true;
        btnSend.textContent = "Sending...";

        if (chkAlways?.checked) {
            await savePreferenceToServer("always", me);
        } else if (chkNever?.checked) {
            await savePreferenceToServer("never", me);
        }

        await sendErrorReportToServer(error, me);
        closeModal();
        showToast("Error report submitted. Thank you!", "success");
    });

    btnCancel?.addEventListener("click", async () => {
        if (chkNever?.checked) {
            await savePreferenceToServer("never", me);
        } else if (chkAlways?.checked) {
            await savePreferenceToServer("always", me);
        }
        closeModal();
    });

    btnClose?.addEventListener("click", closeModal);
}

/**
 * Initializes the global error reporter.
 */
export function initErrorReporter() {
    if (window.__obsidianErrorReporterInitialized) return;
    window.__obsidianErrorReporterInitialized = true;

    window.addEventListener('error', (event) => {
        handleJsError({
            message: event.message || (event.error ? event.error.message : "Script error"),
            stack: event.error ? event.error.stack : null,
            filename: event.filename || window.location.href,
            lineno: event.lineno,
            colno: event.colno
        });
    });

    window.addEventListener('unhandledrejection', (event) => {
        const reason = event.reason;
        let message = "Unhandled Promise Rejection";
        let stack = null;
        if (reason instanceof Error) {
            message = reason.message;
            stack = reason.stack;
        } else if (typeof reason === 'string') {
            message = reason;
        } else if (reason && typeof reason === 'object') {
            message = reason.message || JSON.stringify(reason);
        }

        handleJsError({
            message: message,
            stack: stack,
            filename: window.location.href,
            lineno: null,
            colno: null
        });
    });

    console.log("[ErrorReporter] Global client error detector active.");
}
