/**
 * Load Profiler - ObsidianScout
 * Shows superadmins the real load on the servers, with live refresh traffic split from everything else.
 */

import { combineProfiles, formatBytes, formatMs, formatPercent, formatRate, niceMaximum, summarize } from './utilities/load-profile.js';

const REFRESH_MS = 15000;
const MAX_ROUTES_SHOWN = 25;
const SVG_NS = "http://www.w3.org/2000/svg";
const CHART_HEIGHT = 240;
const CHART_MARGIN = { top: 10, right: 8, bottom: 24, left: 44 };
const MAX_BAR_WIDTH = 18;
const STACK_GAP = 2;
const BAR_RADIUS = 4;

let latest = null;
let selectedNode = "all";

const esc = (value) => window.Obsidianscout.escapeHtml(String(value));
const count = (value) => Number(value).toLocaleString();
const clockTime = (ms) => new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });

function setPanel(id, visible) {
    document.getElementById(id).classList.toggle("hidden", !visible);
}

function svgElement(name, attributes) {
    const element = document.createElementNS(SVG_NS, name);
    Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, value));
    return element;
}

/** A bar with rounded top corners, anchored flat to whatever is underneath it. */
function barPath(x, y, width, height, rounded) {
    const radius = rounded ? Math.min(BAR_RADIUS, width / 2, height) : 0;
    return `M${x},${y + height}V${y + radius}Q${x},${y} ${x + radius},${y}H${x + width - radius}Q${x + width},${y} ${x + width},${y + radius}V${y + height}Z`;
}

function renderTooltip(tooltip, row, x, chartWidth) {
    const others = row.requests - row.liveRequests;
    tooltip.innerHTML = `
        <strong>${esc(clockTime(row.minute))}</strong>
        <div><span class="lp-swatch lp-swatch-live"></span>Live refresh: ${count(row.liveRequests)}</div>
        <div><span class="lp-swatch lp-swatch-other"></span>Everything else: ${count(others)}</div>
        <div>Server time: ${esc(formatMs(row.serverMs))}</div>
        <div>Data sent: ${esc(formatBytes(row.bytes))}</div>
        <div>Invalidations: ${count(row.invalidations)}</div>`;
    tooltip.classList.remove("hidden");
    const flip = x > chartWidth / 2;
    tooltip.style.left = flip ? "" : `${x + 12}px`;
    tooltip.style.right = flip ? `${chartWidth - x + 12}px` : "";
}

function renderChart(minutes) {
    const container = document.getElementById("load-chart");
    const tooltip = document.getElementById("load-chart-tooltip");
    container.querySelector("svg")?.remove();
    tooltip.classList.add("hidden");

    const width = Math.max(320, container.clientWidth);
    const plotWidth = width - CHART_MARGIN.left - CHART_MARGIN.right;
    const plotHeight = CHART_HEIGHT - CHART_MARGIN.top - CHART_MARGIN.bottom;
    const band = plotWidth / Math.max(1, minutes.length);
    const barWidth = Math.max(2, Math.min(MAX_BAR_WIDTH, band - 2));
    const top = niceMaximum(Math.max(0, ...minutes.map((row) => row.requests)));
    const scale = (value) => (value / top) * plotHeight;
    const baseline = CHART_MARGIN.top + plotHeight;

    const svg = svgElement("svg", {
        viewBox: `0 0 ${width} ${CHART_HEIGHT}`,
        width,
        height: CHART_HEIGHT,
        role: "img",
        "aria-label": "API requests per minute, split into live refresh and everything else"
    });

    [0, 0.5, 1].forEach((share) => {
        const y = baseline - plotHeight * share;
        svg.appendChild(svgElement("line", { x1: CHART_MARGIN.left, x2: width - CHART_MARGIN.right, y1: y, y2: y, class: "lp-grid" }));
        const label = svgElement("text", { x: CHART_MARGIN.left - 8, y: y + 4, class: "lp-axis", "text-anchor": "end" });
        label.textContent = count(top * share);
        svg.appendChild(label);
    });

    const labelEvery = Math.max(1, Math.ceil(minutes.length / 6));
    minutes.forEach((row, index) => {
        const slot = CHART_MARGIN.left + band * index;
        const x = slot + (band - barWidth) / 2;
        const others = row.requests - row.liveRequests;
        const otherHeight = scale(others);
        const liveHeight = scale(row.liveRequests);
        const gap = others > 0 && row.liveRequests > 0 ? STACK_GAP : 0;

        if (others > 0) {
            svg.appendChild(svgElement("path", {
                d: barPath(x, baseline - otherHeight, barWidth, otherHeight, row.liveRequests === 0),
                class: "lp-bar-other"
            }));
        }
        if (row.liveRequests > 0) {
            const y = baseline - otherHeight - gap - liveHeight;
            svg.appendChild(svgElement("path", { d: barPath(x, y, barWidth, liveHeight, true), class: "lp-bar-live" }));
        }
        if (index % labelEvery === 0) {
            const label = svgElement("text", { x: slot + band / 2, y: CHART_HEIGHT - 6, class: "lp-axis", "text-anchor": "middle" });
            label.textContent = clockTime(row.minute);
            svg.appendChild(label);
        }

        const hit = svgElement("rect", { x: slot, y: CHART_MARGIN.top, width: band, height: plotHeight, class: "lp-hit" });
        hit.addEventListener("mouseenter", () => renderTooltip(tooltip, row, slot + band / 2, width));
        svg.appendChild(hit);
    });
    svg.addEventListener("mouseleave", () => tooltip.classList.add("hidden"));
    container.appendChild(svg);
}

function renderStats(summary, profile) {
    const tiles = [
        ["Requests per second", formatRate(summary.requestsPerSecond), `Peak minute ${formatRate(summary.peakPerSecond)}/s`],
        ["Live refresh per second", formatRate(summary.livePerSecond), `${formatPercent(summary.liveShare)} of requests`],
        ["Live refresh server time", formatPercent(summary.liveServerShare), `Of all time spent answering`],
        ["Average response", formatMs(summary.averageMs), `${count(summary.errors)} server errors`],
        ["Data sent per minute", formatBytes(summary.bytesPerMinute), `${formatBytes(summary.liveBytes)} from live refresh`],
        ["Users connected", count(profile.usersConnected), `${count(summary.invalidations)} invalidations sent`],
        ["CPU", formatPercent(profile.cpuLoad), profile.nodeCount > 1 ? "Busiest node, right now" : "Right now"],
        ["Memory", formatPercent(profile.heapShare), profile.nodeCount > 1 ? "Busiest node, right now" : "Right now"]
    ];
    document.getElementById("load-stats").innerHTML = tiles.map(([label, value, detail]) => `
        <div class="lp-stat">
            <div class="lp-stat-label">${esc(label)}</div>
            <div class="lp-stat-value">${esc(value)}</div>
            <div class="lp-stat-detail">${esc(detail)}</div>
        </div>`).join("");
}

function renderRoutes(routes, windowMinutes) {
    const body = document.getElementById("load-routes");
    const totalMs = routes.reduce((sum, route) => sum + route.serverMs, 0);
    body.innerHTML = routes.slice(0, MAX_ROUTES_SHOWN).map((route) => `
        <tr>
            <td class="lp-route">${esc(route.route)}</td>
            <td class="lp-number">${count(route.requests)}</td>
            <td class="lp-number">${esc((route.requests / windowMinutes).toFixed(1))}</td>
            <td class="lp-number">${esc(formatPercent(route.requests ? route.liveRequests / route.requests : 0))}</td>
            <td class="lp-number">${esc(formatMs(route.requests ? route.serverMs / route.requests : 0))}</td>
            <td class="lp-number">${esc(formatMs(route.maxMs))}</td>
            <td class="lp-number">${esc(formatPercent(totalMs ? route.serverMs / totalMs : 0))}</td>
            <td class="lp-number">${esc(formatBytes(route.bytes))}</td>
        </tr>`).join("") || `<tr><td colspan="8" class="notice">No requests in this window.</td></tr>`;
}

function renderTopics(topics) {
    document.getElementById("load-topics").innerHTML = topics.map((topic) => `
        <tr>
            <td>${esc(topic.topic)}</td>
            <td class="lp-number">${count(topic.messages)}</td>
            <td class="lp-number">${count(topic.usersReached)}</td>
        </tr>`).join("") || `<tr><td colspan="3" class="notice">No invalidations in this window.</td></tr>`;
}

function renderMinutes(minutes) {
    document.getElementById("load-minutes").innerHTML = minutes.slice().reverse().map((row) => `
        <tr>
            <td>${esc(clockTime(row.minute))}</td>
            <td class="lp-number">${count(row.requests)}</td>
            <td class="lp-number">${count(row.liveRequests)}</td>
            <td class="lp-number">${esc(formatMs(row.serverMs))}</td>
            <td class="lp-number">${esc(formatBytes(row.bytes))}</td>
            <td class="lp-number">${count(row.invalidations)}</td>
        </tr>`).join("");
}

function renderNodeOptions(nodes) {
    const select = document.getElementById("load-node");
    const options = [["all", `All nodes (${nodes.length})`], ...nodes.map((node) => [node.nodeIp, node.nodeIp])];
    if (!options.some(([value]) => value === selectedNode)) selectedNode = "all";
    select.innerHTML = options.map(([value, label]) => `<option value="${esc(value)}">${esc(label)}</option>`).join("");
    select.value = selectedNode;
    document.getElementById("load-node-field").classList.toggle("hidden", nodes.length < 2);
}

function render() {
    if (!latest) return;
    renderNodeOptions(latest.nodes);
    const nodes = selectedNode === "all" ? latest.nodes : latest.nodes.filter((node) => node.nodeIp === selectedNode);
    const profile = combineProfiles(nodes);
    renderStats(summarize(profile), profile);
    renderChart(profile.minutes);
    renderRoutes(profile.routes, latest.windowMinutes);
    renderTopics(profile.topics);
    renderMinutes(profile.minutes);

    const unreachable = document.getElementById("load-unreachable");
    unreachable.textContent = latest.unreachableNodes.length
        ? `No figures from ${latest.unreachableNodes.join(", ")}, so the totals here are missing that traffic.`
        : "";
    setPanel("load-unreachable", latest.unreachableNodes.length > 0);
}

async function load() {
    const minutes = document.getElementById("load-window").value;
    try {
        latest = await window.Obsidianscout.request(`/api/admin/cluster/load-profile?minutes=${encodeURIComponent(minutes)}`);
        setPanel("load-error", false);
        render();
    } catch (error) {
        const notice = document.getElementById("load-error");
        notice.textContent = `Couldn't load the latest figures: ${error.message}`;
        setPanel("load-error", true);
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
        setPanel("load-panel", false);
        return;
    }

    document.getElementById("load-window").addEventListener("change", load);
    document.getElementById("load-node").addEventListener("change", (event) => {
        selectedNode = event.target.value;
        render();
    });
    window.addEventListener("resize", render);

    await load();
    setInterval(() => {
        if (!document.hidden) load();
    }, REFRESH_MS);
});
