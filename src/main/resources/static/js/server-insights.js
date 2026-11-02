/**
 * Server Insights - ObsidianScout
 * Shows superadmins the cluster's database pool, sockets, client fleet, JVM and security events.
 */

const REFRESH_MS = 10000;
const HIGH_CHURN_PER_MINUTE = 30;
const SLOW_P99_MS = 250;

let latest = null;
let selectedNode = "all";

const esc = (value) => window.Obsidianscout.escapeHtml(String(value));
const count = (value) => Number(value).toLocaleString();
const ms = (value) => `${Number(value).toFixed(1)} ms`;
const clockTime = (time) => new Date(time).toLocaleTimeString();

function setPanel(id, visible) {
    document.getElementById(id).classList.toggle("hidden", !visible);
}

function duration(value) {
    if (value < 1000) return `${value} ms`;
    if (value < 60000) return `${(value / 1000).toFixed(1)} s`;
    if (value < 3600000) return `${(value / 60000).toFixed(1)} min`;
    return `${(value / 3600000).toFixed(1)} h`;
}

function stat(label, value, { detail = "", alert = false } = {}) {
    return `
        <div class="si-stat${alert ? " si-alert" : ""}">
            <div class="si-stat-label">${esc(label)}</div>
            <div class="si-stat-value">${esc(value)}</div>
            ${detail ? `<div class="si-stat-detail">${esc(detail)}</div>` : ""}
        </div>`;
}

function setStats(id, stats) {
    document.getElementById(id).innerHTML = stats.join("");
}

function setChips(id, counts, emptyText) {
    const entries = Object.entries(counts);
    document.getElementById(id).innerHTML = entries.length
        ? entries.map(([name, total]) => `<span class="si-chip">${esc(name)}: ${count(total)}</span>`).join("")
        : `<span class="notice">${esc(emptyText)}</span>`;
}

function renderOverview(view) {
    const { latency, dbPool, webSockets, clientFleet, jvm } = view;
    setStats("insights-overview", [
        stat("p95 latency", ms(latency.p95Ms), { detail: `p50 ${ms(latency.p50Ms)}, p99 ${ms(latency.p99Ms)}` }),
        stat("Requests", `${latency.requestsPerSecond} /s`, { detail: `${count(latency.totalRequests)} total` }),
        stat("Errors", `${count(latency.error5xxCount)} 5xx`, { detail: `${count(latency.error4xxCount)} 4xx`, alert: latency.error5xxCount > 0 }),
        stat("DB pool", `${dbPool.activeConnections} / ${dbPool.maxPoolSize}`, { detail: `${dbPool.saturationPercent}% in use`, alert: !dbPool.isHealthy }),
        stat("Sockets", count(webSockets.activeConnections), { detail: `${count(webSockets.connectedUsers)} users` }),
        stat("Active devices", count(clientFleet.totalActiveDevices), { detail: "Last hour" }),
        stat("Heap", `${jvm.heapUsagePercent}%`, { detail: `${count(jvm.usedHeapMb)} of ${count(jvm.maxHeapMb)} MB`, alert: jvm.heapUsagePercent > 85 })
    ]);
}

function renderPool(pool) {
    document.getElementById("insights-pool-note").textContent = pool.threadsAwaiting > 0
        ? "Requests are waiting for database connections. Consider raising maximumPoolSize or looking for slow queries."
        : "No requests are waiting for a database connection.";
    setStats("insights-pool", [
        stat("Status", pool.isHealthy ? "Healthy" : "Starved", { alert: !pool.isHealthy }),
        stat("Active", count(pool.activeConnections)),
        stat("Idle", count(pool.idleConnections)),
        stat("Waiting threads", count(pool.threadsAwaiting), { alert: pool.threadsAwaiting > 0 }),
        stat("Total", count(pool.totalConnections)),
        stat("Maximum", count(pool.maxPoolSize)),
        stat("Minimum idle", count(pool.minIdle)),
        stat("Engine", pool.engineType)
    ]);
}

function renderSockets(sockets) {
    const unstable = sockets.churnRatePerMinute > HIGH_CHURN_PER_MINUTE;
    document.getElementById("insights-socket-note").textContent = unstable
        ? "High churn usually means unstable venue Wi-Fi or reconnect loops on phones."
        : "WebSocket connections are stable.";
    setStats("insights-sockets", [
        stat("Active sockets", count(sockets.activeConnections)),
        stat("Connected users", count(sockets.connectedUsers)),
        stat("Connects / min", count(sockets.connectsLastMinute)),
        stat("Disconnects / min", count(sockets.disconnectsLastMinute)),
        stat("Churn / min", count(sockets.churnRatePerMinute), { alert: unstable }),
        stat("Topic broadcasts", count(sockets.totalTopicBroadcasts)),
        stat("Dropped messages", count(sockets.droppedBroadcasts), { alert: sockets.droppedBroadcasts > 0 })
    ]);
}

function renderFleet(fleet) {
    setStats("insights-fleet", [
        stat("Active devices (1h)", count(fleet.totalActiveDevices)),
        stat("Outbox uploads", count(fleet.syncSubmissionsCount)),
        stat("Average queue time", duration(fleet.avgIngestLagMs)),
        stat("Longest queue time", duration(fleet.maxIngestLagMs))
    ]);
    setChips("insights-platforms", fleet.clientsByPlatform, "No active clients in the last hour.");
    setChips("insights-versions", fleet.clientsByVersion, "No client versions reported.");
}

function renderLatency(latency, wholeCluster) {
    setPanel("insights-latency-note", wholeCluster);
    setStats("insights-latency", [
        stat("p50", ms(latency.p50Ms)),
        stat("p90", ms(latency.p90Ms)),
        stat("p95", ms(latency.p95Ms)),
        stat("p99", ms(latency.p99Ms), { alert: latency.p99Ms > SLOW_P99_MS }),
        stat("Slowest", ms(latency.maxMs)),
        stat("Requests / s", latency.requestsPerSecond)
    ]);
}

function renderJvm(jvm) {
    setStats("insights-jvm", [
        stat("Heap used", `${count(jvm.usedHeapMb)} MB`, { detail: `of ${count(jvm.maxHeapMb)} MB`, alert: jvm.heapUsagePercent > 85 }),
        stat("Non-heap used", `${count(jvm.nonHeapUsedMb)} MB`),
        stat("Live threads", count(jvm.liveThreads)),
        stat("Peak threads", count(jvm.peakThreads)),
        stat("Daemon threads", count(jvm.daemonThreads)),
        stat("GC time", `${count(jvm.gcTimeMs)} ms`, { detail: `${count(jvm.gcCount)} runs` }),
        stat("Uptime", `${(jvm.uptimeSeconds / 3600).toFixed(1)} h`)
    ]);
}

function renderSecurity(security) {
    setStats("insights-security", [
        stat("Failed logins (1h)", count(security.failedLoginsLastHour), { alert: security.failedLoginsLastHour > 5 }),
        stat("Rate limit hits (1h)", count(security.rateLimitHitsLastHour), { alert: security.rateLimitHitsLastHour > 0 })
    ]);
    document.getElementById("insights-audit").innerHTML = security.recentAuditLogs.map((item) => `
        <tr>
            <td>${esc(clockTime(item.timestamp))}</td>
            <td>${esc(item.action)}</td>
            <td>${esc(item.username)} (${esc(item.role)})</td>
            <td>${esc(item.details)}</td>
            <td>${esc(item.ipAddress)}</td>
        </tr>`).join("") || `<tr><td colspan="5" class="notice">Nothing recorded since the server started.</td></tr>`;
}

function renderNodeOptions(nodes) {
    const select = document.getElementById("insights-node");
    const options = [["all", `All nodes (${nodes.length})`], ...nodes.map((node) => [node.nodeIp, node.nodeIp])];
    if (!options.some(([value]) => value === selectedNode)) selectedNode = "all";
    select.innerHTML = options.map(([value, label]) => `<option value="${esc(value)}">${esc(label)}</option>`).join("");
    select.value = selectedNode;
    setPanel("insights-node-field", nodes.length > 1);
}

function render() {
    if (!latest) return;
    renderNodeOptions(latest.nodes);
    const view = latest.nodes.find((node) => node.nodeIp === selectedNode) || latest.combined;
    renderOverview(view);
    renderPool(view.dbPool);
    renderSockets(view.webSockets);
    renderFleet(view.clientFleet);
    renderLatency(view.latency, selectedNode === "all" && latest.nodes.length > 1);
    renderJvm(view.jvm);
    renderSecurity(view.security);

    const unreachable = document.getElementById("insights-unreachable");
    unreachable.textContent = latest.unreachableNodes.length
        ? `No figures from ${latest.unreachableNodes.join(", ")}, so the totals here are missing that node.`
        : "";
    setPanel("insights-unreachable", latest.unreachableNodes.length > 0);
}

async function load() {
    try {
        const cluster = await window.Obsidianscout.request("/api/admin/cluster/analytics/overview");
        if (!cluster.combined) throw new Error("no node answered");
        latest = cluster;
        setPanel("insights-error", false);
        render();
    } catch (error) {
        const notice = document.getElementById("insights-error");
        notice.textContent = `Couldn't load the latest figures: ${error.message}`;
        setPanel("insights-error", true);
    }
}

async function loadThreadDump() {
    const text = document.getElementById("insights-dump-text");
    const node = selectedNode === "all" ? "" : `?node=${encodeURIComponent(selectedNode)}`;
    document.getElementById("insights-dump-title").textContent =
        selectedNode === "all" ? "JVM thread dump" : `JVM thread dump: ${selectedNode}`;
    text.textContent = "Loading...";
    try {
        const response = await fetch(`/api/admin/cluster/analytics/thread-dump${node}`, { credentials: "same-origin" });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        text.textContent = await response.text();
    } catch (error) {
        text.textContent = `Couldn't load the thread dump: ${error.message}`;
    }
}

function wireThreadDump() {
    const dialog = document.getElementById("insights-dump");
    document.getElementById("insights-dump-open").addEventListener("click", () => {
        dialog.showModal();
        loadThreadDump();
    });
    document.getElementById("insights-dump-refresh").addEventListener("click", loadThreadDump);
    document.getElementById("insights-dump-close").addEventListener("click", () => dialog.close());
    document.getElementById("insights-dump-copy").addEventListener("click", async () => {
        try {
            await navigator.clipboard.writeText(document.getElementById("insights-dump-text").textContent);
            window.Obsidianscout.showToast?.("Thread dump copied", "success");
        } catch (error) {
            window.Obsidianscout.showToast?.("Couldn't copy the thread dump", "error");
        }
    });
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
        setPanel("insights-panel", false);
        return;
    }

    document.getElementById("insights-node").addEventListener("change", (event) => {
        selectedNode = event.target.value;
        render();
    });
    wireThreadDump();

    await load();
    setInterval(() => {
        if (!document.hidden) load();
    }, REFRESH_MS);
});
