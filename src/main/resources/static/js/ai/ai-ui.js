/**
 * Local AI UI helpers - ObsidianScout
 * Download consent + progress dialogs, model readiness flow, sanitized markdown rendering,
 * the per-device model picker (Personal Settings) and the superadmin model installer panel.
 */

import AI from "./local-ai.js";

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function") ? window.Obsidianscout.t(key, fallback) : fallback;
}

function fmt(template, values) {
    return String(template).replace(/\{(\w+)\}/g, (_, k) => (values[k] !== undefined ? values[k] : `{${k}}`));
}

function esc(text) {
    return String(text ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function toast(message, tone = "info") {
    if (window.Obsidianscout && typeof window.Obsidianscout.showToast === "function") {
        window.Obsidianscout.showToast(message, tone);
    }
}

// ------------------------------------------------------------------ markdown

const scriptLoads = {};
function loadScript(src) {
    if (!scriptLoads[src]) {
        scriptLoads[src] = new Promise((resolve, reject) => {
            const el = document.createElement("script");
            el.src = src;
            el.onload = () => resolve();
            el.onerror = () => reject(new Error(`Failed to load ${src}`));
            document.head.appendChild(el);
        });
    }
    return scriptLoads[src];
}

export async function ensureMarkdownLibs() {
    if (!window.marked) await loadScript("/vendor/marked.min.js");
    if (!window.DOMPurify) await loadScript("/vendor/purify.min.js");
}

/** Model output is untrusted (it is derived from scout-written notes): always sanitize. */
export function renderMarkdown(text) {
    const source = String(text || "");
    if (window.marked && window.DOMPurify) {
        const html = window.marked.parse(source, { breaks: true, gfm: true });
        return window.DOMPurify.sanitize(html, { USE_PROFILES: { html: true }, FORBID_TAGS: ["style", "img", "iframe", "form", "input"], FORBID_ATTR: ["style"], ADD_TAGS: ["details", "summary"] });
    }
    return esc(source).replace(/\n/g, "<br>");
}

/**
 * Splits model response into reasoning thoughts and final markdown content.
 * Handles closed (<thought>...</thought> or <think>...</think>) as well as open/streaming thought tags.
 */
export function parseThoughtAndContent(raw) {
    if (!raw) return { thought: "", content: "", isThinking: false };
    const str = String(raw);

    // 1. Closed thought tag: <thought>...</thought> or <think>...</think>
    const closedMatch = str.match(/<(?:thought|think)>([\s\S]*?)<\/(?:thought|think)>\s*([\s\S]*)/i);
    if (closedMatch) {
        return {
            thought: closedMatch[1].trim(),
            content: closedMatch[2].trim(),
            isThinking: false
        };
    }

    // 2. Open / in-progress thought tag during streaming: <thought>...
    const openMatch = str.match(/<(?:thought|think)>([\s\S]*)$/i);
    if (openMatch) {
        return {
            thought: openMatch[1].trim(),
            content: "",
            isThinking: true
        };
    }

    // 3. Fallback: unstructured model thinking before final output
    const markerMatch = str.match(/^([\s\S]*?\b(?:Self-Correction|Constraint Check|Final Output(?:\s+Generation)?|Final Answer|Action)\.?\s*[:\.\n]\s*)([\s\S]+)$/i);
    if (markerMatch && markerMatch[1].length > 30 && markerMatch[2].trim().length > 0) {
        return {
            thought: markerMatch[1].trim(),
            content: markerMatch[2].trim(),
            isThinking: false
        };
    }

    // 4. No thought tags found
    return {
        thought: "",
        content: str.trim(),
        isThinking: false
    };
}

export function aiBadge() {
    const badge = document.createElement("span");
    badge.className = "ai-badge";
    badge.innerHTML = `<i class="fa-solid fa-microchip"></i> ${esc(t("ai.badge", "Generated on this device by AI - may be inaccurate"))}`;
    return badge;
}

// ------------------------------------------------------------------ dialogs

function createModal(title) {
    const backdrop = document.createElement("div");
    backdrop.className = "modal-backdrop ai-modal";
    backdrop.innerHTML = `
        <div class="modal-container" role="dialog" aria-modal="true" aria-labelledby="ai-modal-title">
            <div class="modal-header">
                <h3 class="modal-title" id="ai-modal-title"></h3>
            </div>
            <div class="ai-modal-body"></div>
            <div class="modal-footer"></div>
        </div>`;
    backdrop.querySelector(".modal-title").textContent = title;
    document.body.appendChild(backdrop);
    requestAnimationFrame(() => backdrop.classList.add("show"));
    return {
        el: backdrop,
        body: backdrop.querySelector(".ai-modal-body"),
        footer: backdrop.querySelector(".modal-footer"),
        close() {
            backdrop.classList.remove("show");
            setTimeout(() => backdrop.remove(), 250);
        }
    };
}

function button(label, className = "btn") {
    const b = document.createElement("button");
    b.type = "button";
    b.className = className;
    b.textContent = label;
    return b;
}

/** Asks before downloading a model. Resolves true when the user agrees. */
export function confirmDownload(tier, assessment) {
    return new Promise((resolve) => {
        const modal = createModal(t("ai.download.title", "Download AI model?"));
        const size = AI.formatBytes(assessment.downloadBytes);
        modal.body.innerHTML = `
            <p><strong>${esc(tier.name)}</strong> &middot; ${esc(tier.model)}</p>
            <p>${esc(fmt(t("ai.download.body", "This downloads about {size} to this device once. The model then runs entirely on this device: your questions and scouting data are never sent to an AI service."), { size }))}</p>
            <p class="notice">${esc(t("ai.download.wifi", "Use Wi-Fi, ideally before the event: venue networks are slow and shared."))}</p>
            ${assessment.status === "slow" ? `<p class="notice ai-warn">${esc(assessment.reason)}</p>` : ""}`;
        const cancel = button(t("ai.cancel", "Cancel"), "btn ghost");
        const ok = button(fmt(t("ai.download.confirm", "Download {size}"), { size }));
        cancel.addEventListener("click", () => { modal.close(); resolve(false); });
        ok.addEventListener("click", () => { modal.close(); resolve(true); });
        modal.footer.append(cancel, ok);
        ok.focus();
    });
}

/** Shows load/download progress; returns an updater. */
export function showProgress(title) {
    const modal = createModal(title);
    modal.body.innerHTML = `
        <div class="ai-progress"><div class="ai-progress-bar"></div></div>
        <p class="ai-progress-text notice"></p>`;
    const bar = modal.body.querySelector(".ai-progress-bar");
    const text = modal.body.querySelector(".ai-progress-text");
    return {
        update(p) {
            const ratio = Math.max(0, Math.min(1, p.ratio || 0));
            bar.style.width = `${(ratio * 100).toFixed(1)}%`;
            const parts = [`${Math.round(ratio * 100)}%`];
            if (p.total) parts.push(`${AI.formatBytes(p.loaded || 0)} / ${AI.formatBytes(p.total)}`);
            text.textContent = parts.join(" · ");
        },
        close: () => modal.close()
    };
}

/**
 * Makes sure a model is loaded for features to use. Asks for consent before any download.
 * Throws an Error (with .userCancelled when the user declined).
 */
export async function ensureModelReady({ tierId } = {}) {
    if (!(await AI.isEnabled())) throw new Error(t("ai.err.disabled", "The Local AI Assistant is turned off in your settings."));
    const tiers = await AI.getInstalledTiers();
    if (!tiers.length) throw new Error(t("ai.err.none_installed", "No AI models are installed on this server yet. Ask a site admin to install one."));
    // Without an explicit tier, keep using whatever model is already loaded (e.g. the Assistant's choice).
    const loaded = AI.getEngineStatus();
    const tier = tierId ? tiers.find((x) => x.id === tierId)
        : (loaded.tierId && !loaded.loading ? tiers.find((x) => x.id === loaded.tierId) : null) || await AI.resolveTier();
    if (!tier) throw new Error(t("ai.err.no_supported", "None of the installed AI models can run on this device."));

    const status = AI.getEngineStatus();
    if (status.tierId === tier.id && !status.loading) return { tier, backend: status.backend };

    const caps = await AI.capabilities();
    const assessment = AI.assessTier(tier, caps);
    if (assessment.status === "unsupported") throw new Error(assessment.reason);

    const downloaded = await AI.isDownloaded(tier, assessment.backend);
    if (!downloaded && !(await confirmDownload(tier, assessment))) {
        const err = new Error(t("ai.err.cancelled", "Download cancelled."));
        err.userCancelled = true;
        throw err;
    }
    const progress = showProgress(downloaded
        ? fmt(t("ai.loading", "Loading {name} model..."), { name: tier.name })
        : fmt(t("ai.downloading", "Downloading {name} model..."), { name: tier.name }));
    try {
        const result = await AI.load(tier.id, { onProgress: (p) => progress.update(p) });
        if (!AI.getSelectedTierId()) AI.setSelectedTierId(tier.id);
        return result;
    } finally {
        progress.close();
    }
}

// ------------------------------------------------------------------ Personal Settings: per-device model picker

/** Renders the model picker + storage controls. Shown only while the user has the feature enabled. */
export async function mountSettingsPanel(container) {
    if (!container) return;
    container.innerHTML = `<p class="notice">${esc(t("ai.settings.checking", "Checking this device..."))}</p>`;
    let manifest = null;
    let tiers = [];
    try {
        manifest = await AI.getManifest(true);
        tiers = manifest.tiers || [];
    } catch (err) {
        container.innerHTML = `<p class="notice ai-warn">${esc(err.message)}</p>`;
        return;
    }
    if (!tiers.length) {
        const installing = manifest && manifest.installing && manifest.installing.length;
        if (installing) {
            container.innerHTML = `<p class="notice"><span class="spinner" style="display:inline-block;width:14px;height:14px;margin-right:8px;vertical-align:middle;"></span>${esc(fmt(t("ai.settings.server_downloading", "The server is currently downloading AI models ({names}). They will appear here once ready."), { names: manifest.installing.join(", ") }))}</p>`;
            setTimeout(() => { if (document.body.contains(container)) mountSettingsPanel(container); }, 5000);
        } else {
            container.innerHTML = `<p class="notice">${esc(t("ai.err.none_installed", "No AI models are installed on this server yet. Ask a site admin to install one."))}</p>`;
        }
        return;
    }
    const caps = await AI.capabilities(true);
    const selected = AI.getSelectedTierId();
    const rows = await Promise.all(tiers.map(async (tier) => {
        const assessment = AI.assessTier(tier, caps);
        const downloaded = assessment.status !== "unsupported" && await AI.isDownloaded(tier, assessment.backend);
        return { tier, assessment, downloaded };
    }));
    const effective = rows.find((r) => r.tier.id === selected && r.assessment.status !== "unsupported")
        || rows.find((r) => r.downloaded) || rows.find((r) => r.assessment.status !== "unsupported");

    container.innerHTML = "";
    const intro = document.createElement("p");
    intro.className = "notice";
    intro.textContent = t("ai.settings.intro", "Choose the model this device uses. Larger models answer better but need a stronger GPU and a bigger download. This choice is saved per device.");
    container.appendChild(intro);

    const list = document.createElement("div");
    list.className = "ai-tier-list";
    rows.forEach(({ tier, assessment, downloaded }) => {
        const card = document.createElement("label");
        card.className = "ai-tier-card" + (assessment.status === "unsupported" ? " is-disabled" : "");
        const statusLabel = assessment.status === "supported" ? t("ai.cap.supported", "Supported")
            : assessment.status === "slow" ? t("ai.cap.slow", "Will be slow") : t("ai.cap.unsupported", "Not supported");
        card.innerHTML = `
            <input type="radio" name="ai-tier" value="${esc(tier.id)}" ${effective && effective.tier.id === tier.id ? "checked" : ""} ${assessment.status === "unsupported" ? "disabled" : ""}>
            <div class="ai-tier-main">
                <div class="ai-tier-title"><strong>${esc(t("ai.tier." + tier.id, tier.name))}</strong> <span class="ai-muted">${esc(tier.model)}</span>
                    <span class="ai-cap ai-cap-${assessment.status}">${esc(statusLabel)}</span></div>
                <div class="ai-muted">${esc(t("ai.tier." + tier.id + ".desc", ""))}</div>
                <div class="ai-muted">${esc(fmt(t("ai.settings.size", "Download {size} · {backend}"), {
                    size: AI.formatBytes(assessment.downloadBytes),
                    backend: assessment.backend === "cpu" ? t("ai.backend.cpu", "CPU") : t("ai.backend.gpu", "GPU (WebGPU)")
                }))}</div>
                ${assessment.reason ? `<div class="ai-muted ai-warn">${esc(assessment.reason)}</div>` : ""}
                <div class="ai-muted">${esc(fmt(t("ai.settings.license", "License: {license}"), { license: tier.license }))}</div>
            </div>
            <div class="ai-tier-actions"></div>`;
        const actions = card.querySelector(".ai-tier-actions");
        if (assessment.status !== "unsupported") {
            if (downloaded) {
                const state = document.createElement("span");
                state.className = "ai-cap ai-cap-supported";
                state.textContent = t("ai.settings.downloaded", "On this device");
                const del = button(t("ai.settings.delete", "Delete"), "btn ghost btn-sm");
                del.addEventListener("click", async (e) => {
                    e.preventDefault();
                    await AI.deleteDownloaded(tier.id);
                    toast(t("ai.settings.deleted", "Model removed from this device"), "success");
                    mountSettingsPanel(container);
                });
                actions.append(state, del);
            } else {
                const dl = button(t("ai.settings.download", "Download"), "btn btn-sm");
                dl.addEventListener("click", async (e) => {
                    e.preventDefault();
                    try {
                        await ensureModelReady({ tierId: tier.id });
                        AI.setSelectedTierId(tier.id);
                        toast(t("ai.settings.ready", "Model downloaded and ready"), "success");
                    } catch (err) {
                        if (!err.userCancelled) toast(err.message, "error");
                    }
                    mountSettingsPanel(container);
                });
                actions.append(dl);
            }
        }
        card.querySelector("input").addEventListener("change", () => {
            AI.setSelectedTierId(tier.id);
            toast(fmt(t("ai.settings.selected", "{name} model selected for this device"), { name: tier.name }), "success");
        });
        list.appendChild(card);
    });
    container.appendChild(list);

    const footer = document.createElement("div");
    footer.className = "ai-settings-footer";
    const usage = document.createElement("span");
    usage.className = "ai-muted";
    if (caps.usage !== null && caps.quota) {
        usage.textContent = fmt(t("ai.settings.storage", "Browser storage used: {used} of {quota}"), {
            used: AI.formatBytes(caps.usage), quota: AI.formatBytes(caps.quota)
        });
    }
    const clearAll = button(t("ai.settings.delete_all", "Delete all downloaded models"), "btn ghost btn-sm");
    clearAll.addEventListener("click", async () => {
        await AI.deleteAllDownloaded();
        await AI.idbClear("summaries");
        toast(t("ai.settings.deleted_all", "All AI models removed from this device"), "success");
        mountSettingsPanel(container);
    });
    footer.append(usage, clearAll);
    container.appendChild(footer);

    const cpuRow = document.createElement("label");
    cpuRow.className = "ai-muted ai-cpu-toggle";
    const cpuBox = document.createElement("input");
    cpuBox.type = "checkbox";
    cpuBox.checked = AI.isCpuForced();
    cpuBox.addEventListener("change", async () => {
        await AI.setCpuForced(cpuBox.checked);
        mountSettingsPanel(container);
    });
    cpuRow.append(cpuBox, document.createTextNode(" " + t("ai.settings.force_cpu", "Troubleshooting: don't use the GPU (only the Lite model can run on the CPU, slowly)")));
    container.appendChild(cpuRow);
}

// ------------------------------------------------------------------ superadmin: server-side model installer

export async function mountAdminPanel(container) {
    if (!container) return;
    let timer = null;
    const request = (path, opts) => window.Obsidianscout.request(path, opts);

    async function render() {
        let status;
        try {
            status = await request("/api/admin/local-ai/status");
        } catch (err) {
            container.innerHTML = `<p class="notice ai-warn">${esc(err.message)}</p>`;
            return;
        }
        container.innerHTML = `
            <p class="notice">${esc(t("ai.admin.intro", "Models are downloaded once from Hugging Face to this server and then served to browsers from /models/. Users can only use installed models, and each user must turn the assistant on in their own settings."))}</p>
            <p class="ai-muted">${esc(fmt(t("ai.admin.dir", "Model folder: {dir} · {free} free"), { dir: status.modelDir, free: AI.formatBytes(status.freeDiskBytes) }))}</p>`;
        const list = document.createElement("div");
        list.className = "ai-tier-list";
        let anyRunning = false;
        status.tiers.forEach((tier) => {
            const row = document.createElement("div");
            row.className = "ai-tier-card";
            const running = tier.state === "downloading";
            anyRunning = anyRunning || running;
            const pct = tier.totalBytes ? Math.round((tier.downloadedBytes / tier.totalBytes) * 100) : 0;
            const stateText = {
                installed: fmt(t("ai.admin.installed", "Installed ({size})"), { size: AI.formatBytes(tier.installedBytes) }),
                not_installed: t("ai.admin.not_installed", "Not installed"),
                downloading: fmt(t("ai.admin.downloading", "Downloading {pct}% ({done} / {total})"), { pct, done: AI.formatBytes(tier.downloadedBytes), total: AI.formatBytes(tier.totalBytes) }),
                cancelled: t("ai.admin.cancelled", "Cancelled - partial files are kept and the download resumes"),
                error: fmt(t("ai.admin.error", "Failed: {error}"), { error: tier.error || "" })
            }[tier.state] || tier.state;
            row.innerHTML = `
                <div class="ai-tier-main">
                    <div class="ai-tier-title"><strong>${esc(t("ai.tier." + tier.id, tier.name))}</strong> <span class="ai-muted">${esc(tier.model)} · ${esc(tier.license)}</span></div>
                    <div class="ai-muted">${esc(stateText)}</div>
                    ${(status.autoInstallTiers || []).includes(tier.id) ? `<div class="ai-muted">${esc(t("ai.admin.auto_install", "Installed automatically at startup (local_ai.auto_install_tiers)"))}</div>` : ""}
                    ${running ? `<div class="ai-progress"><div class="ai-progress-bar" style="width:${pct}%"></div></div>` : ""}
                </div>
                <div class="ai-tier-actions"></div>`;
            const actions = row.querySelector(".ai-tier-actions");
            if (running) {
                const cancel = button(t("ai.cancel", "Cancel"), "btn ghost btn-sm");
                cancel.addEventListener("click", async () => {
                    await request("/api/admin/local-ai/cancel", { method: "POST", json: { tier: tier.id } });
                    render();
                });
                actions.appendChild(cancel);
            } else if (tier.state === "installed") {
                const del = button(t("ai.admin.uninstall", "Uninstall"), "btn danger btn-sm");
                del.addEventListener("click", async () => {
                    if (!confirm(fmt(t("ai.admin.confirm_uninstall", "Remove the {name} model from this server? Users will no longer be able to use it."), { name: tier.name }))) return;
                    await request(`/api/admin/local-ai/${encodeURIComponent(tier.id)}`, { method: "DELETE" });
                    render();
                });
                actions.appendChild(del);
            } else {
                const install = button(tier.state === "not_installed" ? t("ai.admin.install", "Install") : t("ai.admin.retry", "Resume"), "btn btn-sm");
                install.addEventListener("click", async () => {
                    await request("/api/admin/local-ai/install", { method: "POST", json: { tier: tier.id } });
                    render();
                });
                actions.appendChild(install);
            }
            list.appendChild(row);
        });
        container.appendChild(list);
        clearTimeout(timer);
        if (anyRunning && document.body.contains(container)) timer = setTimeout(render, 2000);
    }
    await render();
}

const ui = { ensureMarkdownLibs, renderMarkdown, parseThoughtAndContent, aiBadge, confirmDownload, showProgress, ensureModelReady, mountSettingsPanel, mountAdminPanel };
if (typeof window !== "undefined") window.ObsidianscoutAIUI = ui;
export default ui;
