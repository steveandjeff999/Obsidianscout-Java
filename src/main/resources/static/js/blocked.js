/**
 * Blocked page - ObsidianScout
 * Shown instead of any page while the visitor's IP is blocked. Counts down to the automatic lift, lets the visitor
 * ask an administrator to lift it sooner, and sends them on once they're let through.
 */
import { fetchAndSolveChallenge } from "./security/pow.js";

const $ = (id) => document.getElementById(id);
let expiresAt = 0;
let requestId = null;
let pollTimer = null;
let tickTimer = null;
let refreshing = false;

function nextUrl() {
    try {
        const next = new URLSearchParams(window.location.search).get("next");
        if (next && /^\/(?![/\\])/.test(next) && !next.startsWith("/blocked")) return next;
    } catch (e) { /* ignore */ }
    return "/dashboard";
}

function show(id, visible) {
    $(id).classList.toggle("hidden", !visible);
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        credentials: "same-origin",
        ...options,
        headers: {
            "Accept": "application/json",
            "X-Requested-With": "XMLHttpRequest",
            ...(options.body ? { "Content-Type": "application/json" } : {}),
            ...(options.headers || {})
        }
    });
    let data = null;
    try { data = await response.json(); } catch (e) { data = null; }
    if (!response.ok) {
        const err = new Error((data && data.error) || `HTTP ${response.status}`);
        err.status = response.status;
        throw err;
    }
    return data;
}

function tick() {
    const left = Math.max(0, expiresAt - Date.now());
    const minutes = Math.floor(left / 60000);
    const seconds = Math.floor((left % 60000) / 1000);
    $("blocked-countdown").textContent = `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
    if (left <= 0 && !refreshing) {
        refreshing = true;
        refreshStatus().finally(() => { setTimeout(() => { refreshing = false; }, 5000); });
    }
}

function setRequestStatus(status, note) {
    show("blocked-request-panel", false);
    show("blocked-request-status", true);
    const icon = $("blocked-status-icon");
    const title = $("blocked-status-title");
    const text = $("blocked-status-text");
    if (status === "approved") {
        icon.textContent = "check_circle";
        icon.style.color = "#12b76a";
        title.textContent = "Approved";
        text.textContent = "Your network has been unblocked. Taking you back...";
        setTimeout(() => window.location.replace(nextUrl()), 1500);
    } else if (status === "denied") {
        icon.textContent = "cancel";
        icon.style.color = "#d92d20";
        title.textContent = "Not approved";
        text.textContent = (note ? `${note} ` : "") + "The block still lifts on its own when the timer runs out.";
    } else if (status === "expired") {
        icon.textContent = "schedule";
        title.textContent = "The block has ended";
        text.textContent = "Taking you back...";
    } else {
        icon.textContent = "pending_actions";
        title.textContent = "Request sent";
        text.textContent = "An administrator will review it. This page updates on its own.";
    }
}

async function pollRequest() {
    if (!requestId) return;
    try {
        const status = await api(`/api/security/unblock-request/status?id=${encodeURIComponent(requestId)}`);
        setRequestStatus(status.status, status.reviewNote);
    } catch (e) { /* keep showing the last status */ }
}

async function refreshStatus() {
    let status;
    try {
        status = await api("/api/security/block-status");
    } catch (e) {
        return;
    }
    $("blocked-ip").textContent = status.ip;
    if (!status.blocked) {
        clearInterval(tickTimer);
        clearInterval(pollTimer);
        show("blocked-active", false);
        show("blocked-clear", true);
        $("blocked-title").textContent = "Access restored";
        $("blocked-intro").classList.add("hidden");
        $("blocked-continue").href = nextUrl();
        return;
    }
    expiresAt = status.expiresAt;
    show("blocked-active", true);
    show("blocked-clear", false);
    if (!status.unblockRequestsEnabled) show("blocked-request-panel", false);
    if (status.pendingRequestId) {
        requestId = status.pendingRequestId;
        setRequestStatus(status.pendingRequestStatus || "pending");
    }
}

function wireForm() {
    const message = $("blocked-message");
    message.addEventListener("input", () => { $("blocked-message-count").textContent = String(message.value.length); });
    $("blocked-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const name = $("blocked-name").value.trim();
        const text = message.value.trim();
        const teamRaw = $("blocked-team").value.trim();
        const email = $("blocked-email").value.trim();
        const error = $("blocked-error");
        show("blocked-error", false);
        if (!name || !text) {
            error.textContent = "Please enter your name and say what you were doing.";
            show("blocked-error", true);
            return;
        }
        const button = $("blocked-submit");
        button.disabled = true;
        const original = button.innerHTML;
        button.textContent = "Running a quick security check...";
        try {
            const challenge = await fetchAndSolveChallenge();
            button.textContent = "Sending...";
            const result = await api("/api/security/unblock-request", {
                method: "POST",
                body: JSON.stringify({
                    name,
                    teamNumber: teamRaw ? Number(teamRaw) : null,
                    email: email || null,
                    message: text,
                    challenge
                })
            });
            requestId = result.id;
            setRequestStatus(result.status, result.reviewNote);
        } catch (e) {
            error.textContent = e.message || "Couldn't send the request.";
            show("blocked-error", true);
        } finally {
            button.disabled = false;
            button.innerHTML = original;
        }
    });
}

document.addEventListener("DOMContentLoaded", async () => {
    wireForm();
    await refreshStatus();
    tickTimer = setInterval(tick, 1000);
    tick();
    pollTimer = setInterval(() => {
        if (document.hidden) return;
        if (requestId) pollRequest();
        refreshStatus();
    }, 15000);
});
