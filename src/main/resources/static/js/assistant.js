/**
 * Scouting Assistant page - ObsidianScout
 * Chat with an on-device model over the team's scouting data. Conversations stay on this device.
 */

import AI from "./ai/local-ai.js";
import UI from "./ai/ai-ui.js";
import Data from "./ai/ai-data.js";
import Tools from "./ai/ai-tools.js";

function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;
}

function fmt(template, values) {
    return String(template).replace(/\{(\w+)\}/g, (_, k) => (values[k] !== undefined ? values[k] : `{${k}}`));
}

const state = {
    me: null,
    ctx: null,
    eventKey: "",
    history: [],      // [{role, content, display?}]
    controller: null,
    busy: false
};

const el = {};

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;
    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();
    state.me = me;

    ["event", "tier", "disabled", "panel", "context", "refresh", "clear", "transcript", "suggestions", "form", "input", "stop", "send"].forEach((id) => {
        el[id] = document.getElementById(`assistant-${id}`);
    });

    if (!me.localAiEnabled) {
        el.disabled.classList.remove("hidden");
        el.tier.disabled = true;
        el.event.disabled = true;
        return;
    }
    el.panel.classList.remove("hidden");
    await UI.ensureMarkdownLibs();
    await initTierSelect();
    await initEventSelect();
    wireForm();
    await loadEvent(state.eventKey);
});

// ------------------------------------------------------------------ selectors

async function initTierSelect() {
    const tiers = await AI.getInstalledTiers();
    el.tier.innerHTML = "";
    if (!tiers.length) {
        const opt = document.createElement("option");
        opt.textContent = t("ai.err.none_installed_short", "No models installed");
        el.tier.appendChild(opt);
        el.tier.disabled = true;
        return;
    }
    const caps = await AI.capabilities();
    const resolved = await AI.resolveTier();
    tiers.forEach((tier) => {
        const assessment = AI.assessTier(tier, caps);
        const opt = document.createElement("option");
        opt.value = tier.id;
        opt.textContent = `${t("ai.tier." + tier.id, tier.name)} (${tier.params})${assessment.status === "unsupported" ? " - " + t("ai.cap.unsupported", "Not supported") : assessment.status === "slow" ? " - " + t("ai.cap.slow", "Will be slow") : ""}`;
        opt.disabled = assessment.status === "unsupported";
        opt.selected = resolved && resolved.id === tier.id;
        el.tier.appendChild(opt);
    });
    el.tier.addEventListener("change", async () => {
        AI.setSelectedTierId(el.tier.value);
        await AI.unload();
    });
}

async function initEventSelect() {
    let settings = {};
    try {
        const res = await Obsidianscout.request("/api/settings");
        settings = res.settings || res;
    } catch (_) { /* offline */ }
    state.eventKey = String(settings.eventKey || "").toLowerCase();
    let events = [];
    try {
        events = await Obsidianscout.request(`/api/events?year=${settings.year || new Date().getFullYear()}&cached=1`) || [];
    } catch (_) { /* optional */ }
    el.event.innerHTML = "";
    const keys = new Set();
    const add = (key, label) => {
        if (!key || keys.has(key)) return;
        keys.add(key);
        const opt = document.createElement("option");
        opt.value = key;
        opt.textContent = label;
        el.event.appendChild(opt);
    };
    if (state.eventKey) add(state.eventKey, `${state.eventKey} (${t("ai.assistant.current_event", "current")})`);
    (Array.isArray(events) ? events : []).forEach((ev) => add(String(ev.eventKey).toLowerCase(), `${ev.name || ev.eventKey} (${ev.eventKey})`));
    if (!keys.size) add("", t("ai.assistant.no_event", "No event selected"));
    el.event.value = state.eventKey;
    el.event.addEventListener("change", () => loadEvent(el.event.value));
}

// ------------------------------------------------------------------ data + conversation

async function loadEvent(eventKey, force = false) {
    state.eventKey = eventKey || "";
    el.context.textContent = t("ai.assistant.loading_data", "Loading scouting data...");
    try {
        state.ctx = await Data.loadContext({ eventKey: state.eventKey, force });
    } catch (err) {
        el.context.textContent = err.message;
        return;
    }
    const ctx = state.ctx;
    el.context.textContent = fmt(t("ai.assistant.context", "{teams} teams with data · {entries} match entries · {matches} scheduled matches · loaded {time}"), {
        teams: Array.from(ctx.stats.values()).filter((s) => s.matchesScouted > 0).length,
        entries: ctx.entries.length,
        matches: ctx.matches.length,
        time: ctx.loadedAt.toLocaleTimeString()
    });
    const saved = await AI.idbGet("conversations", conversationKey());
    state.history = Array.isArray(saved) ? saved : [];
    renderTranscript();
    renderSuggestions();
}

function conversationKey() {
    return `${state.me ? state.me.userId : ""}|${state.eventKey}`;
}

async function saveConversation() {
    await AI.idbSet("conversations", conversationKey(), state.history.slice(-40));
}

function renderSuggestions() {
    const ctx = state.ctx;
    el.suggestions.innerHTML = "";
    if (!ctx) return;
    const top = Data.rankTeams(ctx, ctx.metrics[0]).slice(0, 2).map((r) => r.teamNumber);
    const autoMetric = ctx.metrics.find((m) => m.id === "score_auto");
    // Each chip carries its tool route, so it works in every language without parsing the translated text.
    const metricLabel = (id) => (ctx.metrics.find((m) => m.id === id) || {}).label;
    const prompts = [
        autoMetric ? { text: fmt(t("ai.suggest.top_auto", "Top 5 teams by {metric}"), { metric: autoMetric.label.toLowerCase() }), route: { tool: "top_teams", args: { metric: autoMetric.label, n: 5 } } } : null,
        top.length === 2 ? { text: fmt(t("ai.suggest.compare", "Compare {a} and {b}"), { a: top[0], b: top[1] }), route: { tool: "compare_teams", args: { teams: top } } } : null,
        top.length ? { text: fmt(t("ai.suggest.notes", "Summarize the notes on {a}"), { a: top[0] }), route: { tool: "summarize_notes", args: { team: top[0] } } } : null,
        ctx.matches.length && ctx.ourTeam ? { text: t("ai.suggest.next_match", "Preview our next match"), route: { tool: "match_preview", args: { match: "next" } } } : null,
        { text: t("ai.suggest.picks", "Who should we pick for our alliance?"), route: { tool: "pick_candidates", args: { n: 8 } } },
        { text: t("ai.suggest.scatter", "Scatter auto vs teleop"), route: { tool: "scatter", args: { metric_x: metricLabel("score_auto"), metric_y: metricLabel("score_teleop") } } }
    ].filter(Boolean);
    prompts.forEach(({ text, route }) => {
        const chip = document.createElement("button");
        chip.type = "button";
        chip.className = "assistant-chip";
        chip.textContent = text;
        chip.addEventListener("click", () => ask(text, route));
        el.suggestions.appendChild(chip);
    });
}

function renderTranscript() {
    el.transcript.innerHTML = "";
    if (!state.history.length) {
        const empty = document.createElement("div");
        empty.className = "assistant-empty";
        empty.innerHTML = `<i class="fa-solid fa-robot"></i><p></p>`;
        empty.querySelector("p").textContent = t("ai.assistant.empty", "Ask a question, or pick a suggestion below. Example: \"Who has the best endgame?\"");
        el.transcript.appendChild(empty);
        return;
    }
    state.history.forEach((msg) => {
        if (msg.role === "user") appendUser(msg.content);
        else renderAssistantMessage(appendAssistant(), msg);
    });
    el.transcript.scrollTop = el.transcript.scrollHeight;
}

function appendUser(text) {
    const empty = el.transcript.querySelector(".assistant-empty");
    if (empty) empty.remove();
    const bubble = document.createElement("div");
    bubble.className = "assistant-msg user";
    bubble.textContent = text;
    el.transcript.appendChild(bubble);
    return bubble;
}

function appendAssistant() {
    const bubble = document.createElement("div");
    bubble.className = "assistant-msg bot";
    bubble.innerHTML = `<div class="assistant-cards"></div><div class="assistant-text ai-markdown"></div><div class="assistant-status ai-muted"></div><div class="assistant-meta"></div>`;
    el.transcript.appendChild(bubble);
    return bubble;
}

function renderAssistantMessage(bubble, msg) {
    const cards = bubble.querySelector(".assistant-cards");
    cards.innerHTML = "";
    (msg.display || []).forEach((d) => renderToolCards(cards, d));
    renderAnswerText(bubble, msg.content, msg.unverified || []);
    const meta = bubble.querySelector(".assistant-meta");
    meta.innerHTML = "";
    if (msg.content) meta.appendChild(UI.aiBadge());
    if (msg.tier) {
        const tier = document.createElement("span");
        tier.className = "ai-muted";
        tier.textContent = ` · ${t("ai.tier." + msg.tier, msg.tier)}`;
        meta.appendChild(tier);
    }
}

function esc(text) {
    return String(text ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function renderAnswerText(bubble, text, unverified) {
    const target = bubble.querySelector(".assistant-text");
    const { thought, content, isThinking } = UI.parseThoughtAndContent ? UI.parseThoughtAndContent(text || "") : { thought: "", content: text || "", isThinking: false };

    let html = "";
    if (thought || isThinking) {
        const openAttr = isThinking ? "open" : "";
        const spinnerHtml = isThinking ? `<span class="ai-thought-spinner"><i class="fa-solid fa-circle-notch fa-spin"></i></span>` : "";
        const labelText = isThinking ? t("ai.thought.thinking", "Thinking...") : t("ai.thought.process", "Thought Process");
        html += `
            <details class="ai-thought-box ${isThinking ? 'is-thinking' : ''}" ${openAttr}>
                <summary class="ai-thought-summary">
                    <span class="ai-thought-title">
                        <i class="fa-solid fa-brain"></i>
                        <span class="ai-thought-label">${esc(labelText)}</span>
                    </span>
                    ${spinnerHtml}
                </summary>
                <div class="ai-thought-content ai-markdown">${UI.renderMarkdown(thought)}</div>
            </details>
        `;
    }

    const bodyText = content || (!isThinking ? (text || "") : "");
    if (bodyText) {
        html += `<div class="ai-answer-body ai-markdown">${UI.renderMarkdown(bodyText)}</div>`;
    }

    target.innerHTML = html;
    if (unverified && unverified.length) {
        const answerBody = target.querySelector(".ai-answer-body") || target;
        markUnverified(answerBody, unverified);
    }
}

/** Wraps numbers the guardrail could not find in the data. */
function markUnverified(root, numbers) {
    const set = new Set(numbers.map(String));
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach((node) => {
        const parts = node.nodeValue.split(/(-?\d+(?:\.\d+)?)/);
        if (!parts.some((p) => set.has(String(Number(p))) && /\d/.test(p))) return;
        const frag = document.createDocumentFragment();
        parts.forEach((p) => {
            if (/^-?\d+(?:\.\d+)?$/.test(p) && set.has(String(Number(p)))) {
                const span = document.createElement("span");
                span.className = "ai-unverified";
                span.title = t("ai.unverified", "This number was not found in your scouting data - double-check it.");
                span.textContent = p;
                frag.appendChild(span);
            } else {
                frag.appendChild(document.createTextNode(p));
            }
        });
        node.parentNode.replaceChild(frag, node);
    });
}

// ------------------------------------------------------------------ tool result cards & artifacts

function renderToolCards(container, d) {
    if (d.artifact) container.appendChild(artifactCard(d.artifact));
    if (d.table && d.table.rows && d.table.rows.length) container.appendChild(tableCard(d.title, d.table));
    if (d.chart) container.appendChild(chartCard(d.chart));
    if (d.links && d.links.length) {
        const row = document.createElement("div");
        row.className = "assistant-links";
        d.links.forEach((link) => {
            const a = document.createElement("a");
            a.className = "btn ghost btn-sm";
            a.href = link.href;
            a.textContent = link.label;
            row.appendChild(a);
        });
        container.appendChild(row);
    }
}

function artifactCard(artifact) {
    const card = document.createElement("div");
    card.className = "assistant-artifact-card";
    const typeLabel = {
        strategy: "Strategy Brief",
        worksheet: "Alliance Worksheet",
        dossier: "Team Dossier",
        report: "Scouting Report",
        dashboard: "Interactive Dashboard"
    }[artifact.type] || "Artifact";

    const iconClass = {
        strategy: "fa-chess",
        worksheet: "fa-table-list",
        dossier: "fa-id-card",
        report: "fa-file-lines",
        dashboard: "fa-chart-pie"
    }[artifact.type] || "fa-file-lines";

    const escapeHtml = (str) => String(str || "").replace(/[&<>"']/g, (m) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[m]));

    card.innerHTML = `
        <div class="artifact-icon"><i class="fa-solid ${iconClass}"></i></div>
        <div class="artifact-info">
            <span class="artifact-badge">${escapeHtml(typeLabel)}</span>
            <div class="artifact-title">${escapeHtml(artifact.title || "Scouting Artifact")}</div>
            <div class="artifact-snippet">${escapeHtml(artifact.summary || artifact.markdown?.slice(0, 120)?.replace(/[#*`\n]/g, " ") || "")}</div>
        </div>
        <button type="button" class="btn btn-sm btn-open-artifact"><i class="fa-solid fa-arrow-up-right-from-square"></i> Open Artifact</button>
    `;

    card.querySelector(".btn-open-artifact").addEventListener("click", () => openArtifactModal(artifact));
    return card;
}

function openArtifactModal(artifact) {
    state.activeArtifact = artifact;
    const modal = document.getElementById("assistant-artifact-modal");
    if (!modal) return;
    const titleEl = document.getElementById("artifact-modal-title");
    const badgeEl = document.getElementById("artifact-type-badge");
    const contentEl = document.getElementById("artifact-modal-content");

    if (titleEl) titleEl.textContent = artifact.title || "Artifact";
    if (badgeEl) badgeEl.textContent = artifact.type ? artifact.type.toUpperCase() : "ARTIFACT";
    if (contentEl) contentEl.innerHTML = UI.renderMarkdown(artifact.markdown || "");

    modal.classList.remove("hidden");
}

function wireArtifactModal() {
    const modal = document.getElementById("assistant-artifact-modal");
    if (!modal) return;

    const btnClose = document.getElementById("artifact-btn-close");
    if (btnClose) btnClose.addEventListener("click", () => modal.classList.add("hidden"));

    const backdrop = modal.querySelector(".modal-backdrop");
    if (backdrop) backdrop.addEventListener("click", () => modal.classList.add("hidden"));

    const btnCopy = document.getElementById("artifact-btn-copy");
    if (btnCopy) {
        btnCopy.addEventListener("click", () => {
            if (state.activeArtifact && state.activeArtifact.markdown) {
                navigator.clipboard.writeText(state.activeArtifact.markdown);
                Obsidianscout.showToast("Artifact markdown copied to clipboard!", "success");
            }
        });
    }

    const btnDownload = document.getElementById("artifact-btn-download");
    if (btnDownload) {
        btnDownload.addEventListener("click", () => {
            if (state.activeArtifact) {
                const blob = new Blob([state.activeArtifact.markdown || ""], { type: "text/markdown;charset=utf-8" });
                const a = document.createElement("a");
                a.href = URL.createObjectURL(blob);
                a.download = `${String(state.activeArtifact.title || "artifact").replace(/[^a-z0-9-_]+/gi, "_")}.md`;
                a.click();
                setTimeout(() => URL.revokeObjectURL(a.href), 1000);
            }
        });
    }
}

function tableCard(title, table) {
    const card = document.createElement("div");
    card.className = "assistant-card";
    const head = document.createElement("div");
    head.className = "assistant-card-head";
    const label = document.createElement("span");
    label.textContent = title || t("ai.card.data", "Data");
    const csv = document.createElement("button");
    csv.type = "button";
    csv.className = "btn ghost btn-sm";
    csv.innerHTML = `<i class="fa-solid fa-download"></i> CSV`;
    csv.addEventListener("click", () => downloadCsv(title || "assistant-data", table));
    head.append(label, csv);
    const wrap = document.createElement("div");
    wrap.className = "assistant-table-wrap";
    const tbl = document.createElement("table");
    tbl.className = "table";
    const thead = document.createElement("thead");
    const hr = document.createElement("tr");
    table.columns.forEach((c) => { const th = document.createElement("th"); th.textContent = c; hr.appendChild(th); });
    thead.appendChild(hr);
    const tbody = document.createElement("tbody");
    table.rows.forEach((r) => {
        const tr = document.createElement("tr");
        r.forEach((c) => { const td = document.createElement("td"); td.textContent = c === null || c === undefined ? "-" : String(c); tr.appendChild(td); });
        tbody.appendChild(tr);
    });
    tbl.append(thead, tbody);
    wrap.appendChild(tbl);
    card.append(head, wrap);
    return card;
}

function downloadCsv(name, table) {
    const escCsv = (v) => {
        const s = v === null || v === undefined ? "" : String(v);
        return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const csv = [table.columns, ...table.rows].map((r) => r.map(escCsv).join(",")).join("\n");
    const blob = new Blob([csv], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `${String(name).replace(/[^a-z0-9-_]+/gi, "_").slice(0, 60) || "data"}.csv`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

function themeTokens() {
    const styles = getComputedStyle(document.body);
    return {
        text: styles.getPropertyValue("--ink").trim() || "#0f172a",
        accent: styles.getPropertyValue("--accent").trim() || "#2563eb",
        grid: document.body.classList.contains("theme-dark") ? "rgba(255,255,255,0.12)" : "rgba(0,0,0,0.08)"
    };
}

function chartCard(spec) {
    const card = document.createElement("div");
    card.className = "assistant-card";
    const head = document.createElement("div");
    head.className = "assistant-card-head";
    head.textContent = spec.title || "";
    const plot = document.createElement("div");
    plot.className = "assistant-chart";
    card.append(head, plot);
    const draw = () => {
        if (!window.Plotly) { setTimeout(draw, 200); return; }
        const theme = themeTokens();
        const palette = ["#2563eb", "#f59e0b", "#10b981", "#ef4444", "#8b5cf6", "#06b6d4", "#ec4899", "#6366f1"];
        let traces;
        let layout = {
            height: 320, margin: { l: 50, r: 20, t: 20, b: 50 },
            paper_bgcolor: "rgba(0,0,0,0)", plot_bgcolor: "rgba(0,0,0,0)",
            font: { color: theme.text, size: 12 },
            showlegend: (spec.series || []).length > 1,
            legend: { orientation: "h", y: -0.25 }
        };

        if (spec.type === "scatter") {
            const pts = spec.points || [];
            const showLabels = pts.length <= 15;
            if (pts.length > 0) {
                traces = [{
                    type: "scatter", mode: showLabels ? "markers+text" : "markers",
                    x: pts.map((p) => p.x), y: pts.map((p) => p.y), text: pts.map((p) => String(p.name || p.team)),
                    textposition: "top center", textfont: { size: 10, color: theme.text },
                    marker: { size: 10, color: pts.map((p) => (p.team === spec.highlight ? "#ef4444" : theme.accent)), line: { color: "rgba(255,255,255,0.4)", width: 1 } },
                    hovertemplate: "<b>%{text}</b><br>" + (spec.xTitle || "X") + ": %{x}<br>" + (spec.yTitle || "Y") + ": %{y}<extra></extra>"
                }];
            } else if (spec.series && spec.series.length) {
                traces = spec.series.map((s, i) => {
                    const sLen = (s.x || []).length;
                    const sLabels = sLen <= 15;
                    return {
                        type: "scatter", mode: sLabels ? "markers+text" : "markers", name: s.name,
                        x: s.x, y: s.y, text: s.text || (s.x ? s.x.map((_, idx) => String(idx + 1)) : []),
                        textposition: "top center", textfont: { size: 10, color: theme.text },
                        marker: { size: 10, color: s.color || palette[i % palette.length], line: { color: "rgba(255,255,255,0.4)", width: 1 } },
                        hovertemplate: "<b>%{text}</b><br>" + (spec.xTitle || "X") + ": %{x}<br>" + (spec.yTitle || "Y") + ": %{y}<extra></extra>"
                    };
                });
            } else {
                traces = [{
                    type: "scatter", mode: "markers",
                    x: spec.x || [], y: spec.y || [], text: spec.text || [],
                    marker: { size: 10, color: theme.accent },
                    hovertemplate: "<b>%{text}</b><br>" + (spec.xTitle || "X") + ": %{x}<br>" + (spec.yTitle || "Y") + ": %{y}<extra></extra>"
                }];
            }
            layout.xaxis = { title: spec.xTitle || "", gridcolor: theme.grid, zeroline: false };
            layout.yaxis = { title: spec.yTitle || "", gridcolor: theme.grid, zeroline: false };
            layout.hovermode = "closest";
        } else if (spec.type === "line") {
            traces = (spec.series || []).map((s, i) => ({
                type: "scatter", mode: "lines+markers", name: s.name, x: s.x || spec.x, y: s.y,
                line: { color: s.color || palette[i % palette.length], width: 2.5 },
                marker: { size: 6 }
            }));
            layout.xaxis = { title: spec.xTitle || "", gridcolor: theme.grid, type: "category" };
            layout.yaxis = { title: spec.yTitle || "", gridcolor: theme.grid };
        } else if (spec.type === "radar" || spec.type === "scatterpolar") {
            traces = (spec.series || []).map((s, i) => ({
                type: "scatterpolar", r: s.r, theta: s.theta || spec.theta,
                name: s.name, fill: "toself",
                line: { color: s.color || palette[i % palette.length] }
            }));
            layout.polar = {
                radialaxis: { visible: true, gridcolor: theme.grid },
                angularaxis: { gridcolor: theme.grid }
            };
        } else if (spec.type === "pie" || spec.type === "donut") {
            traces = [{
                type: "pie", hole: spec.type === "donut" ? 0.45 : 0,
                labels: spec.labels || spec.x, values: spec.values || (spec.series && spec.series[0]?.y),
                marker: { colors: palette }
            }];
        } else if (spec.type === "box") {
            traces = (spec.series || []).map((s, i) => ({
                type: "box", name: s.name, y: s.y, boxpoints: "all", jitter: 0.3, pointpos: -1.8,
                marker: { color: palette[i % palette.length] }
            }));
            layout.yaxis = { title: spec.yTitle || "", gridcolor: theme.grid };
        } else if (spec.type === "stackedBar") {
            traces = (spec.series || []).map((s, i) => ({
                type: "bar", name: s.name, x: spec.x, y: s.y,
                marker: { color: s.color || palette[i % palette.length] }
            }));
            layout.barmode = "stack";
            layout.xaxis = { title: spec.xTitle || "", gridcolor: theme.grid, type: "category" };
            layout.yaxis = { title: spec.yTitle || "", gridcolor: theme.grid };
        } else {
            // bar / groupedBar
            const isHorizontal = spec.orientation === "h" || spec.horizontal;
            traces = (spec.series || []).map((s, i) => ({
                type: "bar", name: s.name,
                x: isHorizontal ? (s.x || s.y) : spec.x,
                y: isHorizontal ? (s.y || spec.x) : s.y,
                orientation: isHorizontal ? "h" : "v",
                marker: { color: s.color || palette[i % palette.length] }
            }));
            layout.barmode = "group";
            layout.xaxis = { title: spec.xTitle || "", gridcolor: theme.grid, type: isHorizontal ? "linear" : "category" };
            layout.yaxis = { title: spec.yTitle || "", gridcolor: theme.grid, type: isHorizontal ? "category" : "linear" };
        }

        window.Plotly.newPlot(plot, traces, layout, { responsive: true, displayModeBar: false, displaylogo: false });
    };
    setTimeout(draw, 0);
    return card;
}

// ------------------------------------------------------------------ asking

function setBusy(busy) {
    state.busy = busy;
    el.send.disabled = busy;
    el.stop.classList.toggle("hidden", !busy);
    el.input.disabled = busy;
}

function wireForm() {
    wireArtifactModal();
    el.form.addEventListener("submit", (e) => {
        e.preventDefault();
        ask(el.input.value.trim());
    });
    el.input.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            el.form.requestSubmit();
        }
    });
    el.stop.addEventListener("click", () => state.controller && state.controller.abort());
    el.clear.addEventListener("click", async () => {
        state.history = [];
        await saveConversation();
        renderTranscript();
    });
    el.refresh.addEventListener("click", () => loadEvent(state.eventKey, true));
}

async function ask(question, route = null) {
    if (!question || state.busy || !state.ctx) return;
    el.input.value = "";
    setBusy(true);
    appendUser(question);
    const bubble = appendAssistant();
    const status = bubble.querySelector(".assistant-status");
    const cards = bubble.querySelector(".assistant-cards");
    const display = [];
    let finalText = "";
    let unverified = [];
    state.controller = new AbortController();
    el.transcript.scrollTop = el.transcript.scrollHeight;

    try {
        const tierId = el.tier.value || undefined;
        const { tier } = await UI.ensureModelReady({ tierId });
        const history = state.history.map((h) => ({ role: h.role, content: h.content }));
        await Tools.answerQuestion({
            question,
            history,
            ctx: state.ctx,
            tier,
            route,
            signal: state.controller.signal,
            onEvent: (ev) => {
                if (ev.type === "status") status.textContent = ev.text;
                else if (ev.type === "tool") {
                    const d = {
                        tool: ev.name,
                        title: ev.result.table ? toolTitle(ev.name) : "",
                        table: ev.result.table || null,
                        chart: ev.result.chart || null,
                        artifact: ev.result.artifact || null,
                        links: ev.result.links || null
                    };
                    display.push(d);
                    renderToolCards(cards, d);
                } else if (ev.type === "token") {
                    renderAnswerText(bubble, ev.full, []);
                } else if (ev.type === "done") {
                    finalText = ev.text;
                    unverified = ev.unverified || [];
                }
                el.transcript.scrollTop = el.transcript.scrollHeight;
            }
        });
        status.textContent = "";
        const msg = { role: "assistant", content: finalText, display, unverified, tier: tier.id };
        renderAssistantMessage(bubble, msg);
        state.history.push({ role: "user", content: question }, msg);
        await saveConversation();
    } catch (err) {
        status.textContent = "";
        if (err.name === "AbortError") {
            const partial = bubble.querySelector(".assistant-text").textContent;
            if (!partial) bubble.querySelector(".assistant-text").textContent = t("ai.stopped", "Stopped.");
        } else if (err.userCancelled) {
            bubble.remove();
        } else {
            const p = document.createElement("p");
            p.className = "notice ai-warn";
            p.textContent = err.message || String(err);
            bubble.querySelector(".assistant-text").appendChild(p);
        }
    } finally {
        state.controller = null;
        setBusy(false);
        el.input.focus();
    }
}

function toolTitle(name) {
    return {
        team_overview: t("ai.card.team_overview", "Team overview"),
        top_teams: t("ai.card.top_teams", "Ranking"),
        compare_teams: t("ai.card.compare", "Comparison"),
        metric_trend: "Match-by-Match Trend",
        match_preview: t("ai.card.match_preview", "Match preview"),
        pick_candidates: t("ai.card.picks", "Pick candidates"),
        projected_rankings: "Projected Rankings",
        stacked_breakdown: "Scoring Phase Breakdown",
        team_radar: "Team Radar Profile",
        score_distribution: "Score Distribution",
        create_strategy_brief: "Strategy Brief",
        create_alliance_sheet: "Alliance Selection Worksheet",
        create_team_dossier: "Team Dossier",
        create_artifact: "Artifact",
        filter_teams: t("ai.card.filter", "Matching teams")
    }[name] || t("ai.card.data", "Data");
}
