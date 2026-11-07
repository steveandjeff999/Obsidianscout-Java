/**
 * Blob Storage - ObsidianScout
 * Lets superadmins watch photos and files move out of the database into SeaweedFS after an update.
 */

const REFRESH_MS = 5000;
let latest = null;

const esc = (value) => window.Obsidianscout.escapeHtml(String(value ?? ""));
const count = (value) => Number(value || 0).toLocaleString();

function bytes(value) {
    const n = Number(value || 0);
    if (n < 1024) return `${n} B`;
    const units = ["KB", "MB", "GB", "TB"];
    let v = n / 1024;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    return `${v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`;
}

/** Base64 characters to the size of the file they encode. */
const base64Bytes = (chars) => Math.floor(Number(chars || 0) * 3 / 4);

function ago(iso) {
    if (!iso) return "never";
    const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `${minutes} min ago`;
    return new Date(iso).toLocaleString();
}

function setPanel(id, visible) {
    document.getElementById(id).classList.toggle("hidden", !visible);
}

function icon(name) {
    return `<span class="material-symbols-outlined" aria-hidden="true">${name}</span>`;
}

function remainingTotal(data) {
    return data.categories.reduce((sum, c) => sum + c.remainingRows, 0);
}

function renderState(data) {
    const badge = document.getElementById("bs-state");
    let tone = "warn";
    let text = "Starting";
    let glyph = "hourglass_top";
    if (!data.enabled) {
        tone = "bad"; text = "Turned off"; glyph = "block";
    } else if (data.seaweedReady && data.network && data.network.problems > 0) {
        tone = "bad"; text = "Firewall blocking servers"; glyph = "gpp_maybe";
    } else if (data.seaweedReady) {
        tone = "good"; text = "SeaweedFS ready"; glyph = "check_circle";
    } else if (!data.platformSupported && data.mode === "single") {
        tone = "warn"; text = "Local disk only"; glyph = "hard_drive";
    } else if (data.installed) {
        tone = "bad"; text = "SeaweedFS not reachable"; glyph = "error";
    } else {
        text = "Installing SeaweedFS";
    }
    badge.className = `bs-badge ${tone}`;
    badge.innerHTML = `${icon(glyph)}${esc(text)}`;
    document.getElementById("bs-run").disabled = !data.enabled;
}

function renderOverall(data) {
    const remainingBytes = data.categories.reduce((sum, c) => sum + base64Bytes(c.remainingChars), 0);
    const storedBytes = data.categories.reduce((sum, c) => sum + c.storedBytes, 0);
    const total = remainingBytes + storedBytes;
    const rows = remainingTotal(data);
    const pct = total === 0 ? 100 : Math.floor((storedBytes / total) * 1000) / 10;
    const done = rows === 0;

    document.getElementById("bs-overall-pct").textContent = done ? "Done" : `${pct}%`;
    const fill = document.getElementById("bs-overall-fill");
    fill.style.width = `${done ? 100 : pct}%`;
    fill.classList.toggle("done", done);

    let text;
    if (!data.enabled) {
        text = "Blob storage is turned off in app-config.json (blob_storage.enabled), so nothing is moving.";
    } else if (done) {
        text = `Everything is out of the database: ${bytes(storedBytes)} in the blob store.`;
    } else {
        text = `${count(rows)} row${rows === 1 ? "" : "s"} (about ${bytes(remainingBytes)}) still in the database; ${bytes(storedBytes)} moved.`;
    }
    document.getElementById("bs-overall-text").textContent = text;
}

function renderStats(data) {
    const storedObjects = data.categories.reduce((sum, c) => sum + c.storedObjects, 0);
    const tiles = [
        ["Still in the database", count(remainingTotal(data)), "rows holding base64"],
        ["In the blob store", count(storedObjects), "objects"],
        ["Volume servers", count(data.volumeServers), data.mode === "cluster" ? "registered with the master" : "on this server"],
        ["Waiting on local disk", count(data.pendingLocalObjects), "move in once SeaweedFS is reachable"],
        ["Single copy", count(data.singleCopyObjects), data.volumeServers >= 2 ? "getting a second copy" : "need a second server for a copy"],
    ];
    if (data.skippedRows > 0) {
        tiles.push(["Skipped", count(data.skippedRows), "rows that can't be moved (bad data); see server log"]);
    }
    document.getElementById("bs-stats").innerHTML = tiles.map(([label, value, detail]) => `
        <div class="bs-stat">
            <div class="bs-stat-label">${esc(label)}</div>
            <div class="bs-stat-value">${esc(value)}</div>
            <div class="bs-stat-detail">${esc(detail)}</div>
        </div>`).join("");
}

function renderCategories(data) {
    document.getElementById("bs-categories").innerHTML = data.categories.map((c) => {
        const remaining = base64Bytes(c.remainingChars);
        const total = remaining + c.storedBytes;
        const done = c.remainingRows === 0;
        const pct = total === 0 ? 100 : Math.floor((c.storedBytes / total) * 100);
        const left = done
            ? "Done"
            : `${count(c.remainingRows)} row${c.remainingRows === 1 ? "" : "s"} left (${bytes(remaining)})${c.sweptHourly ? " · checked hourly" : ""}`;
        return `
            <div class="bs-cat">
                <div class="bs-cat-head">
                    <span class="bs-cat-name">${esc(c.label)}</span>
                    <span class="bs-cat-meta">${esc(left)} · ${count(c.storedObjects)} stored (${esc(bytes(c.storedBytes))})</span>
                </div>
                <div class="bs-track thin"><div class="bs-fill${done ? " done" : ""}" style="width: ${done ? 100 : pct}%"></div></div>
            </div>`;
    }).join("");
}

function kv(rows) {
    return rows.map(([k, v, mono]) => `<dt>${esc(k)}</dt><dd${mono ? ' class="bs-mono"' : ""}>${esc(v)}</dd>`).join("");
}

function renderActivity(data) {
    const running = data.runningPass;
    const last = data.lastPass;
    const rows = [];
    if (running) {
        rows.push(["Now", data.currentStep || "Checking for work"]);
        rows.push(["Started", ago(running.startedAt)]);
    } else {
        rows.push(["Now", "Idle"]);
    }
    if (last) {
        rows.push(["Last pass", `${ago(last.finishedAt || last.startedAt)} on ${last.node}`]);
        rows.push(["Result", last.error ? `${last.outcome}: ${last.error}` : last.outcome]);
        rows.push(["Rows moved", count(last.moved)]);
        if (last.promoted > 0) rows.push(["Moved in from local disk", count(last.promoted)]);
    } else {
        rows.push(["Last pass", "Not yet (the first one runs about 30 seconds after startup)"]);
    }
    rows.push(["Moved since this server started", count(data.movedSinceStart)]);
    rows.push(["Counts as of", ago(data.countsAt)]);
    if (data.countsError) rows.push(["Counting failed", data.countsError]);
    document.getElementById("bs-activity").innerHTML = kv(rows);
}

function renderNode(data) {
    document.getElementById("bs-node").innerHTML = kv([
        ["Server", `${data.reportingNode} (v${data.serverVersion})`],
        ["Mode", data.mode === "cluster" ? "CockroachDB cluster" : "Single server"],
        ["SeaweedFS", data.platformSupported ? `${data.seaweedVersion}${data.installed ? "" : " (not installed yet)"}` : "No build for this platform; uses local disk"],
        ["Master", data.masterAddress ? `${data.masterAddress}${data.runsMaster ? " (this server)" : ""}` : "Unknown"],
        ["Volume server", data.runsVolume ? "Running" : "Not running"],
        ["Data folder", data.dataDirectory || "–", true],
    ]);
}

function renderNodes(data) {
    const outdated = data.nodes.filter((n) => n.online && !n.onCurrentVersion).length;
    document.getElementById("bs-nodes-note").textContent = outdated > 0
        ? `${outdated} server${outdated === 1 ? " is" : "s are"} still on an older version. Older servers can't show moved photos until they update.`
        : "Each server runs a SeaweedFS volume server; one also runs the master.";
    document.getElementById("bs-nodes").innerHTML = data.nodes.length === 0
        ? `<tr><td colspan="5" class="bs-muted">No servers reported.</td></tr>`
        : data.nodes.map((n) => `
            <tr>
                <td>${esc(n.ip)}${n.isLocal ? ' <span class="bs-muted">(this server)</span>' : ""}${n.online ? "" : ' <span class="bs-muted">offline</span>'}</td>
                <td>${esc(n.serverVersion)}${n.onCurrentVersion ? "" : ' <span class="bs-badge warn" style="padding: 1px 8px; font-size: 11px;">older</span>'}</td>
                <td class="${n.volumeRegistered ? "bs-yes" : "bs-no"}">${n.volumeRegistered ? "Registered" : "Not registered"}</td>
                <td class="${n.isMaster ? "bs-yes" : "bs-no"}">${n.isMaster ? "Master" : "–"}</td>
                <td class="bs-number">${count(n.volumes)}</td>
            </tr>`).join("");
}

const PORT_STATE = {
    ok: { cls: "ok", glyph: "check_circle", text: "Open" },
    blocked: { cls: "blocked", glyph: "block", text: "Blocked" },
    partial: { cls: "blocked", glyph: "warning", text: "Partly blocked" },
    unreachable: { cls: "blocked", glyph: "error", text: "Unreachable" },
    not_listening: { cls: "idle", glyph: "radio_button_unchecked", text: "Not in use" },
    untested: { cls: "idle", glyph: "help", text: "Not tested" },
};

function commandHtml(fix) {
    return `
        <div class="bs-cmd-label">${esc(fix.label)}</div>
        <div class="bs-cmd">
            <code>${esc(fix.command)}</code>
            <button type="button" class="btn ghost bs-copy" data-command="${esc(fix.command)}" title="Copy command">
                <span class="material-symbols-outlined" aria-hidden="true" style="font-size: 18px;">content_copy</span>
            </button>
        </div>`;
}

function renderFirewall(data) {
    const net = data.network;
    const alert = document.getElementById("bs-firewall");
    const broken = net && net.applicable ? net.nodes.filter((n) => n.blockedPorts.length > 0) : [];
    if (broken.length === 0) {
        alert.classList.add("hidden");
        alert.innerHTML = "";
        return;
    }
    alert.innerHTML = `
        <h2>${icon("gpp_maybe")}Firewall changes needed</h2>
        <p class="notice" style="margin: 0;">Until these are open, photos stored on one server can't be read or copied from the others.
        Run the command on each server listed (it opens all SeaweedFS ports, to Tailscale addresses only), then wait about 15 seconds for this page to re-check.</p>
        ${broken.map((n) => `
            <div class="bs-fix-node">
                <strong>${esc(n.ip)}</strong>${n.os ? ` <span class="bs-muted">(${esc(n.os)})</span>` : ""}:
                port${n.blockedPorts.length === 1 ? "" : "s"} ${esc(n.blockedPorts.join(", "))} blocked
                ${n.fixes.map(commandHtml).join("")}
            </div>`).join("")}`;
    alert.classList.remove("hidden");
}

function renderNetwork(data) {
    const net = data.network;
    const summary = document.getElementById("bs-net-summary");
    const wrap = document.getElementById("bs-net-table-wrap");
    const rules = document.getElementById("bs-net-rules");
    if (!net) {
        summary.textContent = "Couldn't check the network this time.";
        wrap.classList.add("hidden");
        rules.classList.add("hidden");
        return;
    }
    summary.textContent = `${net.summary} Checked ${ago(net.checkedAt)}.`;
    if (!net.applicable) {
        wrap.classList.add("hidden");
        rules.classList.add("hidden");
        return;
    }
    wrap.classList.remove("hidden");
    const ports = net.requiredPorts;
    const uses = new Map();
    net.nodes.forEach((n) => n.ports.forEach((p) => uses.set(p.port, p.use)));
    document.getElementById("bs-net-head").innerHTML = `<tr><th>Server</th>${ports.map((p) => `<th title="${esc(uses.get(p) || "")}">${p}<div class="bs-muted" style="font-weight: 400;">${esc(uses.get(p) || "")}</div></th>`).join("")}</tr>`;
    document.getElementById("bs-net-body").innerHTML = net.nodes.map((n) => `
        <tr>
            <td>${esc(n.ip)}${n.os ? ` <span class="bs-muted">${esc(n.os)}</span>` : ""}${n.online ? "" : ' <span class="bs-muted">offline</span>'}</td>
            ${ports.map((port) => {
                const p = n.ports.find((x) => x.port === port);
                const st = PORT_STATE[p?.state] || PORT_STATE.untested;
                return `<td><span class="bs-port ${st.cls}" title="${esc(p?.detail || "")}">${icon(st.glyph)}${esc(st.text)}</span></td>`;
            }).join("")}
        </tr>`).join("");

    // The rules are worth having on every server before it's needed (any server can become master).
    const sample = net.nodes.find((n) => n.fixes.length > 0);
    if (sample) {
        const all = new Map();
        net.nodes.forEach((n) => n.fixes.forEach((f) => all.set(f.label, f)));
        document.getElementById("bs-net-rules-body").innerHTML = `
            <p class="notice">TCP ${esc(ports.join(", "))}, from Tailscale addresses (100.64.0.0/10). Ports showing "Not in use" are for the master, which can move to any server, so open them everywhere.</p>
            ${[...all.values()].map(commandHtml).join("")}`;
        rules.classList.remove("hidden");
    }
}

function render() {
    if (!latest) return;
    renderState(latest);
    renderFirewall(latest);
    renderOverall(latest);
    renderStats(latest);
    renderCategories(latest);
    renderActivity(latest);
    renderNode(latest);
    renderNetwork(latest);
    renderNodes(latest);
}

async function load() {
    try {
        latest = await window.Obsidianscout.request("/api/admin/blob-storage/status");
        setPanel("bs-error", false);
        render();
    } catch (error) {
        const notice = document.getElementById("bs-error");
        notice.textContent = `Couldn't load the latest status: ${error.message}`;
        setPanel("bs-error", true);
    }
}

async function runNow() {
    const button = document.getElementById("bs-run");
    button.disabled = true;
    try {
        await window.Obsidianscout.request("/api/admin/blob-storage/run", { method: "POST" });
        window.Obsidianscout.showToast?.("Migration pass started on this server.", "success");
        setTimeout(load, 1500);
    } catch (error) {
        window.Obsidianscout.showToast?.(`Couldn't start a pass: ${error.message}`, "error");
    } finally {
        setTimeout(() => { button.disabled = !(latest && latest.enabled); }, 3000);
    }
}

document.addEventListener("DOMContentLoaded", async () => {
    const api = window.Obsidianscout;
    api.initTheme?.();
    const me = await api.requireAuth?.();
    if (!me) return;
    api.setUserBadge?.(me);
    api.wireThemeToggle?.();

    if (!api.isSuperAdmin?.(me.role)) {
        setPanel("superadmin-locked", true);
        setPanel("bs-panel", false);
        return;
    }

    document.getElementById("bs-run").addEventListener("click", runNow);
    document.getElementById("bs-panel").addEventListener("click", async (event) => {
        const button = event.target.closest(".bs-copy");
        if (!button) return;
        try {
            await navigator.clipboard.writeText(button.dataset.command);
            api.showToast?.("Command copied.", "success");
        } catch {
            api.showToast?.("Couldn't copy; select the command and copy it by hand.", "error");
        }
    });
    await load();
    setInterval(() => {
        if (!document.hidden) load();
    }, REFRESH_MS);
    // Refreshing pauses in a background tab; catch up as soon as it's looked at again.
    document.addEventListener("visibilitychange", () => {
        if (!document.hidden) load();
    });
});
