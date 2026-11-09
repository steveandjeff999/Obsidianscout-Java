/**
 * Intrusion detection - Server Insights (superadmins)
 * Mode switch, blocks and unblocking, unblock requests, allowlist, protected accounts, threat scores and the
 * searchable security log.
 */

const REFRESH_MS = 10000;
const LOG_PAGE = 50;

const api = () => window.Obsidianscout;
const esc = (value) => window.Obsidianscout.escapeHtml(String(value ?? ""));
const $ = (id) => document.getElementById(id);
const count = (value) => Number(value || 0).toLocaleString();
const dateTime = (ms) => (ms ? new Date(ms).toLocaleString() : "");

const TYPE_LABELS = {
    AUTH_FAILURE: "Failed sign-in",
    AUTH_FAILURE_UNKNOWN_USER: "Sign-in, no such account",
    PASSWORD_SPRAY: "Password spray",
    CREDENTIAL_STUFFING: "Credential stuffing",
    RATE_LIMITED: "Rate limited",
    ROUTE_SCAN: "Route scan",
    SCANNER_PATH: "Scanner probe",
    INJECTION_PROBE: "Injection probe",
    CSRF_FAILURE: "CSRF failure",
    FORBIDDEN_ROLE: "Permission denied",
    CROSS_TEAM_ACCESS: "Cross-team access",
    CLUSTER_SIG_INVALID: "Forged cluster call",
    SESSION_ANOMALY: "Session anomaly",
    TOKEN_REUSE_REVOKED: "Revoked session reused",
    SCRAPING: "Scraping",
    CHALLENGE_FAILED: "Security check failed",
    CHALLENGE_REQUIRED: "Security check asked",
    ACCOUNT_PROTECTED: "Account protected",
    ACCOUNT_PROTECTION_CLEARED: "Protection cleared",
    BLOCK_APPLIED: "Blocked",
    WOULD_BLOCK: "Would block",
    BLOCK_SKIPPED: "Block skipped",
    BLOCK_LIFTED: "Unblocked",
    UNBLOCK_REQUESTED: "Unblock requested",
    UNBLOCK_APPROVED: "Request approved",
    UNBLOCK_DENIED: "Request denied",
    SESSIONS_REVOKED: "Sessions signed out",
    ADMIN_ACTION: "Superadmin action"
};
const label = (type) => TYPE_LABELS[type] || type;

let started = false;
let summary = null;
let logOffset = 0;
let logFilters = {};

function showError(message) {
    const el = $("sec-error");
    el.textContent = message || "";
    el.classList.toggle("hidden", !message);
}

function toast(message, kind = "success") {
    api().showToast?.(message, kind);
}

async function call(path, options) {
    return api().request(path, { noCache: true, skipEntryStore: true, ...options });
}

function minutesLeft(expiresAt) {
    const ms = Math.max(0, expiresAt - Date.now());
    const m = Math.floor(ms / 60000);
    const s = Math.floor((ms % 60000) / 1000);
    return `${m}:${String(s).padStart(2, "0")}`;
}

function networkHint(users) {
    if (users >= 3) return `<span class="sec-pill medium" title="Several accounts signed in from here in the last hour">Shared (${users} users)</span>`;
    if (users > 0) return `<span class="sec-pill">${users} user${users === 1 ? "" : "s"} signed in</span>`;
    return `<span class="sec-muted">No sign-ins</span>`;
}

function stat(name, value, { detail = "", alert = false } = {}) {
    return `
        <div class="si-stat${alert ? " si-alert" : ""}">
            <div class="si-stat-label">${esc(name)}</div>
            <div class="si-stat-value">${esc(value)}</div>
            ${detail ? `<div class="si-stat-detail">${esc(detail)}</div>` : ""}
        </div>`;
}

// ------------------------------------------------------------------------------------------------ summary

function renderSummary(s) {
    $("sec-mode").value = s.mode;
    $("sec-autorevoke").checked = !!s.autoRevokeSessions;
    $("sec-mode-note").textContent = !s.enabled
        ? "Detection is turned off in app-config.json."
        : s.mode === "enforce"
            ? `Enforcing: hostile addresses are blocked (never more than ${s.maxBlockMinutes} minutes), risky sign-ins get a security check, and targeted accounts are protected.`
            : "Observing: everything is detected, logged and alerted, but nothing is blocked. Switch to Enforce once the alerts look right.";

    const events = s.eventsLast24h || {};
    const high = s.timeline.reduce((n, b) => n + b.high, 0);
    const medium = s.timeline.reduce((n, b) => n + b.medium, 0);
    $("sec-stats").innerHTML = [
        stat("Active blocks", count(s.activeBlocks), { alert: s.activeBlocks > 0 }),
        stat("Unblock requests", count(s.pendingUnblockRequests), { detail: "Waiting for review", alert: s.pendingUnblockRequests > 0 }),
        stat("Protected accounts", count(s.protectedAccounts), { alert: s.protectedAccounts > 0 }),
        stat("High events (24 h)", count(high), { detail: `${count(medium)} medium`, alert: high > 0 }),
        stat("Failed sign-ins (24 h)", count((events.AUTH_FAILURE || 0) + (events.AUTH_FAILURE_UNKNOWN_USER || 0))),
        stat("Credential stuffing", s.credentialStuffingActive ? "Under way" : "None", { alert: s.credentialStuffingActive }),
        ...(s.droppedEvents > 0 ? [stat("Dropped events", count(s.droppedEvents), { detail: "Queue was full", alert: true })] : [])
    ].join("");

    const peak = Math.max(1, ...s.timeline.map((b) => b.low + b.medium + b.high + b.info));
    $("sec-timeline").innerHTML = s.timeline.map((b) => {
        const total = b.low + b.medium + b.high + b.info;
        const pct = (n) => `${(n / peak) * 100}%`;
        const hour = new Date(b.hourStart).getHours();
        return `<div class="sec-bar" title="${esc(`${hour}:00 - ${total} events (${b.high} high, ${b.medium} medium, ${b.low} low)`)}">
            <span class="info" style="height:${pct(b.info)}"></span>
            <span class="low" style="height:${pct(b.low)}"></span>
            <span class="medium" style="height:${pct(b.medium)}"></span>
            <span class="high" style="height:${pct(b.high)}"></span>
        </div>`;
    }).join("");

    const entries = Object.entries(events).sort((a, b) => b[1] - a[1]);
    $("sec-types").innerHTML = entries.length
        ? entries.map(([type, n]) => `<span class="si-chip">${esc(label(type))}: ${count(n)}</span>`).join("")
        : `<span class="sec-muted">No security events in the last 24 hours.</span>`;

    const badge = $("sec-pending-badge");
    badge.textContent = String(s.pendingUnblockRequests);
    badge.classList.toggle("hidden", !s.pendingUnblockRequests);

    $("sec-offenders").innerHTML = s.topOffenders.map((o) => `
        <tr>
            <td>${esc(o.ip)}</td>
            <td class="si-number">${esc(o.score)}</td>
            <td><span class="sec-pill ${o.level === "BLOCK" ? "high" : o.level === "NORMAL" ? "" : "medium"}">${esc(o.level)}</span></td>
            <td>${o.lastEventAt ? `${esc(label(o.lastEventType))}, ${esc(dateTime(o.lastEventAt))}` : ""}</td>
            <td><div class="sec-actions">
                <button class="btn ghost" type="button" data-block-ip="${esc(o.ip)}">Block</button>
                <button class="btn ghost" type="button" data-log-ip="${esc(o.ip)}">Show events</button>
            </div></td>
        </tr>`).join("") || `<tr><td colspan="5" class="notice">No address has a notable score right now.</td></tr>`;
}

// ------------------------------------------------------------------------------------------------ lists

function renderRequests(requests) {
    $("sec-requests").innerHTML = requests.map((r) => `
        <tr>
            <td>${esc(dateTime(r.createdAt))}</td>
            <td class="sec-wrap"><strong>${esc(r.name)}</strong>${r.teamNumber != null ? ` (team ${esc(r.teamNumber)})` : ""}<br>
                <span class="sec-muted">${esc(r.ip)}${r.email ? ` · ${esc(r.email)}` : ""}</span>
                ${r.highRisk ? `<br><span class="sec-pill high">High risk</span>` : ""}</td>
            <td class="sec-wrap">${esc(r.message)}</td>
            <td class="sec-wrap">${esc(r.blockReason || "")}${r.blockExpiresAt ? `<br><span class="sec-muted">${esc(minutesLeft(r.blockExpiresAt))} left</span>` : ""}</td>
            <td>${networkHint(r.recentDistinctUsers)}</td>
            <td><div class="sec-actions">
                <button class="btn" type="button" data-approve="${esc(r.id)}">Approve</button>
                <button class="btn ghost" type="button" data-deny="${esc(r.id)}">Deny</button>
            </div></td>
        </tr>`).join("") || `<tr><td colspan="6" class="notice">No requests waiting.</td></tr>`;
}

function renderBlocks(blocks) {
    $("sec-blocks").innerHTML = blocks.map((b) => `
        <tr>
            <td><strong>${esc(b.ipOrCidr)}</strong>${b.pendingRequestId ? `<br><span class="sec-pill medium">Request waiting</span>` : ""}</td>
            <td class="sec-wrap">${esc(b.reason)}<br><span class="sec-muted">by ${esc(b.createdBy)}, ${esc(dateTime(b.createdAt))}</span></td>
            <td class="si-number">${esc(b.strike)}</td>
            <td class="si-number">${esc(minutesLeft(b.expiresAt))}</td>
            <td class="si-number">${count(b.hits)}</td>
            <td>${networkHint(b.recentDistinctUsers)}</td>
            <td><button class="btn" type="button" data-lift="${esc(b.id)}" data-lift-ip="${esc(b.ipOrCidr)}">
                <span class="material-symbols-outlined" aria-hidden="true" style="font-size:16px;vertical-align:-3px;">lock_open</span> Unblock</button></td>
        </tr>`).join("") || `<tr><td colspan="7" class="notice">Nothing is blocked.</td></tr>`;
    $("sec-unblock-all").disabled = blocks.length === 0;
}

function renderAllowlist(entries) {
    $("sec-allowlist").innerHTML = entries.map((e) => `
        <tr>
            <td><strong>${esc(e.ipOrCidr)}</strong></td>
            <td class="sec-wrap">${esc(e.note)}</td>
            <td>${e.fromConfig ? "app-config.json" : `${esc(e.createdBy)}, ${esc(dateTime(e.createdAt))}`}</td>
            <td>${e.expiresAt ? esc(dateTime(e.expiresAt)) : "Never"}</td>
            <td>${e.fromConfig ? "" : `<button class="btn ghost" type="button" data-allow-remove="${esc(e.id)}">Remove</button>`}</td>
        </tr>`).join("") || `<tr><td colspan="5" class="notice">No entries. Add a venue or school network to keep it from being blocked.</td></tr>`;
}

function renderProtected(accounts) {
    $("sec-protected").innerHTML = accounts.map((a) => `
        <tr>
            <td><strong>${esc(a.username)}</strong>${a.teamNumber != null ? ` <span class="sec-muted">(team ${esc(a.teamNumber)}${a.program ? ` ${esc(a.program)}` : ""})</span>` : ""}</td>
            <td>${esc(dateTime(a.protectedUntil))}</td>
            <td><button class="btn ghost" type="button" data-unprotect="${esc(a.account)}" data-unprotect-name="${esc(a.username)}">Clear</button></td>
        </tr>`).join("") || `<tr><td colspan="3" class="notice">No accounts are protected.</td></tr>`;
}

async function loadAll() {
    try {
        const [s, requests, blocks, allowlist, protectedAccounts] = await Promise.all([
            call("/api/admin/security/summary"),
            call("/api/admin/security/unblock-requests?status=pending"),
            call("/api/admin/security/blocks?active=true"),
            call("/api/admin/security/allowlist"),
            call("/api/admin/security/protected-accounts")
        ]);
        summary = s;
        renderSummary(s);
        renderRequests(requests);
        renderBlocks(blocks);
        renderAllowlist(allowlist);
        renderProtected(protectedAccounts);
        showError("");
    } catch (error) {
        showError(`Couldn't load security data: ${error.message}`);
    }
}

// ------------------------------------------------------------------------------------------------ log

function renderLogRows(page, append) {
    const rows = page.events.map((e) => `
        <tr>
            <td style="white-space: nowrap;">${esc(dateTime(e.at))}</td>
            <td><span class="sec-pill ${e.severity === "HIGH" ? "high" : e.severity === "MEDIUM" ? "medium" : ""}">${esc(label(e.type))}</span></td>
            <td>${esc(e.ip)}</td>
            <td>${esc(e.username || "")}${e.teamNumber != null ? ` (${esc(e.teamNumber)})` : ""}</td>
            <td class="sec-wrap">${esc(e.details || `${e.method} ${e.path}`)}</td>
            <td>${esc(e.node)}</td>
        </tr>`).join("");
    const body = $("sec-log");
    if (append) body.insertAdjacentHTML("beforeend", rows);
    else body.innerHTML = rows || `<tr><td colspan="6" class="notice">No matching events.</td></tr>`;
    const shown = page.offset + page.events.length;
    $("sec-log-count").textContent = page.total ? `Showing ${count(shown)} of ${count(page.total)}` : "";
    $("sec-log-more").classList.toggle("hidden", shown >= page.total);
    logOffset = shown;
}

async function searchLog(append = false) {
    const params = new URLSearchParams({ limit: String(LOG_PAGE), offset: String(append ? logOffset : 0) });
    Object.entries(logFilters).forEach(([k, v]) => { if (v) params.set(k, v); });
    try {
        renderLogRows(await call(`/api/admin/security/events?${params}`), append);
    } catch (error) {
        showError(`Couldn't search the log: ${error.message}`);
    }
}

// ------------------------------------------------------------------------------------------------ dialog

/** Opens the shared dialog and resolves to its values, or null when cancelled. */
function openDialog({ title, text, confirm, noteLabel = "Reason", liftOptions = true, noteRequired = false }) {
    const dialog = $("sec-dialog");
    $("sec-dialog-title").textContent = title;
    $("sec-dialog-text").textContent = text || "";
    $("sec-dialog-note-label").textContent = noteLabel;
    $("sec-dialog-note").value = "";
    $("sec-dialog-note").required = noteRequired;
    $("sec-dialog-reset").checked = true;
    $("sec-dialog-allow").value = "none";
    $("sec-dialog-lift-options").classList.toggle("hidden", !liftOptions);
    $("sec-dialog-confirm").textContent = confirm;
    return new Promise((resolve) => {
        const onClose = () => {
            dialog.removeEventListener("close", onClose);
            if (dialog.returnValue !== "confirm") return resolve(null);
            resolve({
                note: $("sec-dialog-note").value.trim(),
                resetStrikes: $("sec-dialog-reset").checked,
                allowlistFor: $("sec-dialog-allow").value
            });
        };
        dialog.addEventListener("close", onClose);
        dialog.returnValue = "";
        dialog.showModal();
    });
}

// ------------------------------------------------------------------------------------------------ actions

async function act(promise, done) {
    try {
        await promise;
        if (done) toast(done);
        await loadAll();
    } catch (error) {
        toast(error.message || "Something went wrong", "error");
    }
}

function wire() {
    $("sec-save-mode").addEventListener("click", async () => {
        const mode = $("sec-mode").value;
        if (mode === "enforce" && summary && summary.mode !== "enforce" &&
            !window.confirm("Switch to Enforce? Hostile addresses will start being blocked (up to an hour each) and risky sign-ins will get a security check.")) {
            $("sec-mode").value = summary.mode;
            return;
        }
        await act(call("/api/admin/security/settings", {
            method: "POST",
            json: { mode, autoRevokeSessions: $("sec-autorevoke").checked }
        }), "Security settings saved");
    });

    document.getElementById("security").addEventListener("click", async (event) => {
        const target = event.target.closest("button");
        if (!target) return;
        const d = target.dataset;
        if (d.lift) {
            const values = await openDialog({
                title: `Unblock ${d.liftIp}`,
                text: "The address is let through on every node right away and its threat score is reset.",
                confirm: "Unblock"
            });
            if (!values) return;
            await act(call(`/api/admin/security/blocks/${encodeURIComponent(d.lift)}/lift`, {
                method: "POST",
                json: { reason: values.note, resetStrikes: values.resetStrikes, allowlistFor: values.allowlistFor }
            }), `${d.liftIp} unblocked`);
        } else if (d.approve) {
            const values = await openDialog({
                title: "Approve unblock request",
                text: "Lifts the block. The requester is emailed if they left an address.",
                confirm: "Approve",
                noteLabel: "Note to the requester (optional)"
            });
            if (!values) return;
            await act(call(`/api/admin/security/unblock-requests/${encodeURIComponent(d.approve)}/approve`, {
                method: "POST",
                json: { note: values.note, resetStrikes: values.resetStrikes, allowlistFor: values.allowlistFor }
            }), "Request approved and block lifted");
        } else if (d.deny) {
            const values = await openDialog({
                title: "Deny unblock request",
                text: "The block stays until it runs out (an hour at most).",
                confirm: "Deny",
                noteLabel: "Note to the requester (optional)",
                liftOptions: false
            });
            if (!values) return;
            await act(call(`/api/admin/security/unblock-requests/${encodeURIComponent(d.deny)}/deny`, {
                method: "POST",
                json: { note: values.note }
            }), "Request denied");
        } else if (d.allowRemove) {
            await act(call(`/api/admin/security/allowlist/${encodeURIComponent(d.allowRemove)}`, { method: "DELETE" }), "Removed from the allowlist");
        } else if (d.unprotect) {
            await act(call("/api/admin/security/protected-accounts/clear", { method: "POST", json: { account: d.unprotect } }),
                `${d.unprotectName || d.unprotect} can sign in with a password again`);
        } else if (d.blockIp) {
            $("sec-block-ip").value = d.blockIp;
            $("sec-block-reason").focus();
        } else if (d.logIp) {
            $("sec-log-ip").value = d.logIp;
            logFilters = { ip: d.logIp };
            await searchLog(false);
            $("sec-log").scrollIntoView({ behavior: "smooth", block: "nearest" });
        }
    });

    $("sec-unblock-all").addEventListener("click", async () => {
        const values = await openDialog({
            title: "Unblock every address",
            text: "For when a rule has caught legitimate users, for example everyone at an event.",
            confirm: "Unblock all",
            liftOptions: false,
            noteRequired: true
        });
        if (!values) return;
        if (!values.note) return toast("Give a reason for lifting every block.", "error");
        await act(call("/api/admin/security/blocks/unblock-all", { method: "POST", json: { reason: values.note } }), "Every block lifted");
    });

    $("sec-block-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const ipOrCidr = $("sec-block-ip").value.trim();
        if (!ipOrCidr) return;
        const minutes = Math.max(1, Math.min(60, Number($("sec-block-minutes").value) || 60));
        await act(call("/api/admin/security/blocks", {
            method: "POST",
            json: { ipOrCidr, minutes, reason: $("sec-block-reason").value.trim() }
        }), `${ipOrCidr} blocked for ${minutes} minutes`);
        $("sec-block-ip").value = "";
        $("sec-block-reason").value = "";
    });

    $("sec-allow-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const ipOrCidr = $("sec-allow-ip").value.trim();
        if (!ipOrCidr) return;
        const hours = $("sec-allow-for").value;
        await act(call("/api/admin/security/allowlist", {
            method: "POST",
            json: { ipOrCidr, note: $("sec-allow-note").value.trim(), expiresInHours: hours ? Number(hours) : null }
        }), `${ipOrCidr} allowlisted`);
        $("sec-allow-ip").value = "";
        $("sec-allow-note").value = "";
    });

    const typeSelect = $("sec-log-type");
    typeSelect.insertAdjacentHTML("beforeend", Object.keys(TYPE_LABELS)
        .map((type) => `<option value="${esc(type)}">${esc(label(type))}</option>`).join(""));
    $("sec-log-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        logFilters = {
            ip: $("sec-log-ip").value.trim(),
            user: $("sec-log-user").value.trim(),
            type: typeSelect.value,
            severity: $("sec-log-severity").value
        };
        await searchLog(false);
    });
    $("sec-log-more").addEventListener("click", () => searchLog(true));
}

window.addEventListener("obsidianscout:insights-ready", async () => {
    if (started) return;
    started = true;
    wire();
    await Promise.all([loadAll(), searchLog(false)]);
    if (window.location.hash === "#security") $("security").scrollIntoView({ block: "start" });
    setInterval(() => {
        if (!document.hidden && !$("sec-dialog").open) loadAll();
    }, REFRESH_MS);
});
