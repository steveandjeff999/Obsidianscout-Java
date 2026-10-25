/**
 * Component Save-Receipt Module - ObsidianScout
 * The confirmation shown after every scouting save. It names what was saved and says plainly where it went
 * (uploaded, on this device only, or not saved), and stays up long enough to read between matches.
 */

import { faIcon } from '../utilities/fa-icons.js';
import { triggerHaptic } from '../utilities/haptics.js';

const HOST_ID = "save-receipt-host";
const SHOW_MS = { server: 6000, local: 9000, rejected: 0, "not-saved": 0 };

let _hideTimer = null;

function escapeHtml(value) {
    return String(value == null ? "" : value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

/** "Team 254 · QM12" for one entry's payload. */
export function describeEntryPayload(data) {
    const d = data || {};
    const parts = [];
    const team = d.targetTeamNumber || d.teamNumber;
    if (team) parts.push(`Team ${team}`);
    if (d.matchKey) {
        parts.push(String(d.matchKey).split("_").pop().toUpperCase());
    } else if (d.matchNumber) {
        parts.push(`Match ${d.matchNumber}`);
    }
    return parts.join(" · ");
}

function describeAll(entries) {
    const all = entries || [];
    if (all.length > 1) {
        // A whole alliance for one match: "Teams 254, 1678, 118 · QM12".
        const match = describeEntryPayload({ matchKey: all[0].matchKey, matchNumber: all[0].matchNumber });
        const sameMatch = all.every((d) => describeEntryPayload({ matchKey: d.matchKey, matchNumber: d.matchNumber }) === match);
        const teams = all.map((d) => d.targetTeamNumber || d.teamNumber).filter(Boolean);
        if (sameMatch && teams.length === all.length) {
            const teamText = teams.length <= 6 ? `Teams ${teams.join(", ")}` : `${teams.length} teams`;
            return [teamText, match].filter(Boolean).join(" · ");
        }
    }
    const list = all.map(describeEntryPayload).filter(Boolean);
    if (!list.length) return "";
    if (list.length <= 3) return list.join(", ");
    return `${list.length} entries`;
}

/** Login page that brings the scout back here afterwards. */
export function signInUrl() {
    const here = `${window.location.pathname}${window.location.search}`;
    return `/login?next=${encodeURIComponent(here)}`;
}

function ensureHost() {
    let host = document.getElementById(HOST_ID);
    if (host) return host;
    host = document.createElement("div");
    host.id = HOST_ID;
    host.className = "save-receipt-host";
    host.setAttribute("role", "status");
    host.setAttribute("aria-live", "polite");
    host.addEventListener("click", (e) => {
        if (e.target.closest("[data-receipt-close]")) hideSaveReceipt();
    });
    document.body.appendChild(host);
    return host;
}

export function hideSaveReceipt() {
    clearTimeout(_hideTimer);
    const host = document.getElementById(HOST_ID);
    if (host) host.innerHTML = "";
}

/**
 * Shows the result of a save.
 * where: "server" (uploaded), "local" (kept on this device for upload later), "rejected" (the server refused it and
 * nothing was saved; the form still holds it), "not-saved" (couldn't be stored at all).
 * entries: the saved payloads, used to name them. detail: optional extra text. signInNeeded: adds a sign-in link.
 */
export function showSaveReceipt({ where, kindLabel = "", entries = [], detail = "", message = "", signInNeeded = false } = {}) {
    if (typeof document === "undefined" || !document.body) return;
    const host = ensureHost();
    const what = [kindLabel, describeAll(entries) || detail].filter(Boolean).join(" · ");

    let tone, icon, title, body;
    if (where === "server") {
        tone = "success";
        icon = "circle-check";
        title = "Uploaded to the server";
        body = "";
    } else if (where === "local") {
        tone = "warning";
        icon = "mobile-screen-button";
        title = "Saved on this device only";
        body = signInNeeded
            ? `Your sign-in has expired, so it can't upload yet. <a href="${escapeHtml(signInUrl())}">Sign in again</a> and it uploads automatically.`
            : "It uploads automatically when the server is reachable. You can keep scouting.";
    } else if (where === "rejected") {
        tone = "danger";
        icon = "circle-xmark";
        title = "Not saved: the server refused this entry";
        body = `${escapeHtml(message || "Check the form and try again.")} Your answers are still in the form.`;
    } else {
        tone = "danger";
        icon = "circle-xmark";
        title = "NOT saved";
        body = escapeHtml(message || "This device is out of storage. Upload or clear saved data in Cache Manager, then save again. Your answers are still in the form.");
    }

    host.innerHTML = `
        <div class="save-receipt save-receipt-${tone}">
            <div class="save-receipt-icon">${faIcon(icon, 20)}</div>
            <div class="save-receipt-text">
                <div class="save-receipt-title">${escapeHtml(title)}</div>
                ${what ? `<div class="save-receipt-what">${escapeHtml(what)}</div>` : ""}
                ${body ? `<div class="save-receipt-body">${body}</div>` : ""}
            </div>
            <button type="button" class="save-receipt-close" data-receipt-close aria-label="Dismiss">${faIcon("xmark", 14)}</button>
        </div>`;

    try {
        triggerHaptic(tone === "success" ? "success" : tone === "warning" ? "warning" : "error");
    } catch (e) { /* ignore */ }

    clearTimeout(_hideTimer);
    const ms = SHOW_MS[where];
    // Failures stay until dismissed or the next save.
    if (ms) _hideTimer = setTimeout(hideSaveReceipt, ms);
}
