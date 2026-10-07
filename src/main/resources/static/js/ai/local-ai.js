/**
 * Local AI core - ObsidianScout
 * Runs Qwen2.5 models entirely in the browser (Transformers.js for Lite, WebLLM for Standard/Advanced).
 * The server only serves static weight files from /models/; prompts and data never leave the device.
 *
 * Exposed both as an ES module and as window.ObsidianscoutAI for classic page scripts.
 */

const PREF_TIER = "obsidianscout:local_ai_tier";
/** Troubleshooting switch: ignore WebGPU and use the CPU fallback (Lite only). */
const PREF_FORCE_CPU = "obsidianscout:local_ai_force_cpu";

export function isCpuForced() {
    try {
        return localStorage.getItem(PREF_FORCE_CPU) === "1";
    } catch (_) {
        return false;
    }
}

export async function setCpuForced(value) {
    try {
        if (value) localStorage.setItem(PREF_FORCE_CPU, "1");
        else localStorage.removeItem(PREF_FORCE_CPU);
    } catch (_) { /* storage blocked */ }
    capsPromise = null;
    await unload();
}
export const TIER_ORDER = ["lite", "standard", "gemma4e2b", "advanced", "gemma4e4b"];

/**
 * Per-tier behaviour. Features check these flags, never tier ids, so new tiers only need an entry here.
 *  toolMode        "router" = rule router first, at most one model-picked tool (results that error are dropped);
 *                  "json" = the model picks tools with JSON (grammar-constrained on WebLLM).
 *  maxToolCalls    model-picked tool calls when the rule router doesn't recognise the question.
 *  routedFollowUps extra model-picked calls allowed after a rule-routed tool.
 *  codeAnswers     answer with code-built briefs instead of model prose (tiny models invent details).
 *  extractiveSummaries  notes summaries quote scouts instead of being rewritten by the model.
 *  calculate / strategy  enable the calculator tool and the strategy-reasoning prompt.
 *  visibleReasoning  ask for a <thought> block before the answer (costly; only for fast WebLLM tiers).
 */
export const TIER_PROFILES = {
    lite: { maxAnswerTokens: 450, contextChars: 4200, toolMode: "router", maxToolCalls: 1, routedFollowUps: 0, historyTurns: 3, codeAnswers: true, extractiveSummaries: true, calculate: false, strategy: false, visibleReasoning: false },
    standard: { maxAnswerTokens: 500, contextChars: 4500, toolMode: "json", maxToolCalls: 2, routedFollowUps: 0, historyTurns: 3, codeAnswers: false, extractiveSummaries: false, calculate: false, strategy: false, visibleReasoning: false },
    // Gemma 4 runs through Transformers.js at a few tokens per second: fewer model-picked calls, shorter answers, no written reasoning.
    gemma4e2b: { maxAnswerTokens: 400, contextChars: 6000, toolMode: "json", maxToolCalls: 1, routedFollowUps: 0, historyTurns: 3, codeAnswers: false, extractiveSummaries: false, calculate: true, strategy: true, visibleReasoning: false },
    advanced: { maxAnswerTokens: 750, contextChars: 5000, toolMode: "json", maxToolCalls: 4, routedFollowUps: 2, historyTurns: 4, codeAnswers: false, extractiveSummaries: false, calculate: true, strategy: true, visibleReasoning: true },
    gemma4e4b: { maxAnswerTokens: 500, contextChars: 7000, toolMode: "json", maxToolCalls: 2, routedFollowUps: 0, historyTurns: 4, codeAnswers: false, extractiveSummaries: false, calculate: true, strategy: true, visibleReasoning: false }
};

/** Profile for a tier (object or id); unknown tiers get Lite's conservative settings. */
export function tierProfile(tierOrId) {
    const id = typeof tierOrId === "string" ? tierOrId : tierOrId && tierOrId.id;
    return TIER_PROFILES[id] || TIER_PROFILES.lite;
}

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function") ? window.Obsidianscout.t(key, fallback) : fallback;
}

// ------------------------------------------------------------------ user + manifest

let mePromise = null;
export async function getMe(force = false) {
    if (!mePromise || force) {
        mePromise = (async () => {
            try {
                if (window.Obsidianscout && typeof window.Obsidianscout.getMe === "function") {
                    return await window.Obsidianscout.getMe();
                }
            } catch (_) { /* fall through */ }
            return null;
        })();
    }
    return mePromise;
}

/** True when this user has turned the feature on in Personal Settings (off by default). */
export async function isEnabled() {
    const me = await getMe();
    return !!(me && me.localAiEnabled);
}

let manifestPromise = null;
export async function getManifest(force = false) {
    if (!manifestPromise || force) {
        manifestPromise = fetch("/api/local-ai/manifest", { credentials: "same-origin", cache: "no-store" })
            .then((res) => {
                if (!res.ok) throw new Error(`Manifest request failed (${res.status})`);
                return res.json();
            })
            .then((manifest) => {
                pruneRetiredCaches(manifest).catch(() => {});
                return manifest;
            })
            .catch((err) => {
                manifestPromise = null;
                throw err;
            });
    }
    return manifestPromise;
}

/**
 * Deletes browser-cached model files for tiers the server no longer has (e.g. the retired Gemma 2 tiers),
 * superseded versions of current tiers, and old runtimes. Runs at most once per browser session.
 */
export async function pruneRetiredCaches(manifest) {
    if (!manifest || !manifest.knownTiers || !("caches" in window)) return 0;
    try {
        if (sessionStorage.getItem("obsidianscout:ai_cache_pruned") === manifest.runtime.version) return 0;
        sessionStorage.setItem("obsidianscout:ai_cache_pruned", manifest.runtime.version);
    } catch (_) { /* storage blocked: prune anyway */ }
    const known = manifest.knownTiers;
    let removed = 0;
    for (const name of await caches.keys()) {
        if (!(name.startsWith("webllm/") || name === CPU_FILE_CACHE || name === "transformers-cache")) continue;
        const cache = await caches.open(name);
        for (const req of await cache.keys()) {
            const parts = new URL(req.url).pathname.split("/").filter(Boolean); // models, <tier|runtime>, <version>, ...
            if (parts[0] !== "models" || parts.length < 3) continue;
            const stale = parts[1] === "runtime" ? parts[2] !== manifest.runtime.version : known[parts[1]] !== parts[2];
            if (stale) {
                await cache.delete(req);
                removed++;
            }
        }
    }
    return removed;
}

export async function getInstalledTiers() {
    try {
        const manifest = await getManifest();
        return (manifest.tiers || []).slice().sort((a, b) => TIER_ORDER.indexOf(a.id) - TIER_ORDER.indexOf(b.id));
    } catch (_) {
        return [];
    }
}

export function getSelectedTierId() {
    try {
        return localStorage.getItem(PREF_TIER) || "";
    } catch (_) {
        return "";
    }
}

export function setSelectedTierId(id) {
    try {
        localStorage.setItem(PREF_TIER, id);
    } catch (_) { /* storage blocked */ }
    window.dispatchEvent(new CustomEvent("obsidianscout:ai-tierchange", { detail: { tier: id } }));
}

// ------------------------------------------------------------------ device capabilities

let capsPromise = null;
export async function capabilities(force = false) {
    if (!capsPromise || force) {
        capsPromise = (async () => {
            const caps = {
                webgpu: false,
                shaderF16: false,
                maxBufferSize: 0,
                adapterName: "",
                deviceMemory: navigator.deviceMemory || null,
                mobile: false,
                quota: null,
                usage: null
            };
            caps.mobile = !!(navigator.userAgentData && navigator.userAgentData.mobile) ||
                /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent || "");
            let forceCpu = false;
            try { forceCpu = localStorage.getItem(PREF_FORCE_CPU) === "1"; } catch (_) { /* storage blocked */ }
            try {
                if (navigator.gpu && !forceCpu) {
                    let adapter = null;
                    try {
                        adapter = await navigator.gpu.requestAdapter({ powerPreference: "high-performance" });
                    } catch (e) {
                        console.warn("[LocalAI] High-performance WebGPU adapter request failed:", e);
                    }
                    if (!adapter) {
                        try {
                            adapter = await navigator.gpu.requestAdapter();
                        } catch (e) {
                            console.warn("[LocalAI] Default WebGPU adapter request failed:", e);
                        }
                    }
                    if (adapter) {
                        caps.webgpu = true;
                        caps.shaderF16 = adapter.features.has("shader-f16");
                        caps.maxBufferSize = Number(adapter.limits.maxBufferSize || 0);
                        const info = adapter.info || {};
                        caps.adapterName = [info.vendor, info.architecture, info.description].filter(Boolean).join(" ");
                    } else {
                        console.warn("[LocalAI] navigator.gpu is available but requestAdapter returned null. Check chrome://gpu.");
                    }
                } else if (!navigator.gpu) {
                    console.warn("[LocalAI] navigator.gpu is undefined. Note: WebGPU requires a Secure Context (https:// or http://localhost).");
                }
            } catch (err) {
                console.warn("[LocalAI] WebGPU detection error:", err);
            }
            try {
                if (navigator.storage && navigator.storage.estimate) {
                    const est = await navigator.storage.estimate();
                    caps.quota = est.quota || null;
                    caps.usage = est.usage || null;
                }
            } catch (_) { /* ignore */ }
            return caps;
        })();
    }
    return capsPromise;
}

/**
 * Whether a tier can run on this device, and on which backend.
 * GPU path = WebLLM (needs WebGPU + shader-f16). CPU path = Transformers.js q8 (Lite only, slower).
 * @returns {{status:'supported'|'slow'|'unsupported', reason:string, backend:'webgpu'|'cpu', downloadBytes:number}}
 */
export function assessTier(tier, caps) {
    const gpuOk = caps.webgpu && caps.shaderF16 && !!tier.webgpu;
    if (!gpuOk) {
        if (tier.cpu) {
            return {
                status: "slow",
                backend: "cpu",
                downloadBytes: tier.cpu.downloadBytes || 0,
                reason: t("ai.cap.no_webgpu_cpu", "No usable WebGPU: runs on the CPU, so replies are slower.")
            };
        }
        return {
            status: "unsupported",
            backend: "webgpu",
            downloadBytes: (tier.webgpu && tier.webgpu.downloadBytes) || 0,
            reason: !caps.webgpu
                ? t("ai.cap.requires_webgpu", "Requires a browser with WebGPU (recent Chrome, Edge or Safari).")
                : t("ai.cap.requires_f16", "This GPU/browser lacks 16-bit shader support (shader-f16).")
        };
    }
    const base = { backend: "webgpu", downloadBytes: tier.webgpu.downloadBytes || 0 };
    if (caps.quota && caps.usage !== null && caps.quota - caps.usage < base.downloadBytes * 1.05) {
        return { ...base, status: "unsupported", reason: t("ai.cap.no_storage", "Not enough browser storage for this model.") };
    }
    // Device rules come from the server manifest (mobile: ok|slow|no, minDeviceMemoryGB, vramMB).
    const gb = Math.max(1, Math.round((tier.vramMB || 0) / 1024 * 10) / 10);
    if (caps.mobile && tier.mobile === "no") {
        return { ...base, status: "unsupported", reason: t("ai.cap.desktop_only_gb", "Needs a laptop or desktop GPU (~{gb} GB of GPU memory).").replace("{gb}", gb) };
    }
    // navigator.deviceMemory is capped at 8 by browsers, so 8 GB requirements pass on any large machine.
    if (tier.minDeviceMemoryGB && caps.deviceMemory && caps.deviceMemory < tier.minDeviceMemoryGB) {
        return { ...base, status: "unsupported", reason: t("ai.cap.low_memory_gb", "Needs at least {gb} GB of device memory.").replace("{gb}", tier.minDeviceMemoryGB) };
    }
    if (caps.mobile && tier.mobile === "slow") {
        return { ...base, status: "slow", reason: t("ai.cap.mobile_slow", "May be slow or run out of memory on phones.") };
    }
    return { ...base, status: "supported", reason: "" };
}

export function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return "0 MB";
    const gb = bytes / (1024 ** 3);
    if (gb >= 1) return `${gb.toFixed(2)} GB`;
    return `${Math.round(bytes / (1024 ** 2))} MB`;
}

// ------------------------------------------------------------------ downloaded-model cache (Cache Storage)

/** Cache used by the worker for CPU-fallback files (stored in chunks; see ai-worker.js). */
export const CPU_FILE_CACHE = "obsidianscout-ai-files";

/** True when every file the engine needs for this backend is already in Cache Storage. */
export async function isDownloaded(tier, backend) {
    if (!("caches" in window)) return false;
    try {
        const onnx = backend === "cpu" ? tier.cpu : (tier.webgpu && tier.webgpu.engine === "transformers" ? tier.webgpu : null);
        if (backend === "cpu" || onnx) {
            // Transformers.js builds are stored by the worker in chunked Cache Storage entries (see ai-worker.js).
            if (!onnx || !(onnx.files || []).length) return false;
            const cache = await caches.open(CPU_FILE_CACHE);
            for (const file of onnx.files) {
                if (!(await cache.match(new URL(file.url + "?meta", location.origin).href))) return false;
            }
            return true;
        }
        // WebLLM stores weight shards and config in its own webllm/* caches, keyed by URL. (The small model
        // library .wasm goes through the regular HTTP cache, so it isn't checked here.)
        const required = (tier.webgpu.files || []).filter((u) => /params_shard_|mlc-chat-config\.json$/.test(u));
        if (!required.length) return false;
        for (const url of required) {
            if (!(await caches.match(new URL(url, location.origin).href))) return false;
        }
        return true;
    } catch (_) {
        return false;
    }
}

/** Removes every cached file belonging to a tier (any version, both backends). */
export async function deleteDownloaded(tierId) {
    if (engine.tierId === tierId) await unload();
    if (!("caches" in window)) return 0;
    let removed = 0;
    const prefix = `/models/${tierId}/`;
    for (const name of await caches.keys()) {
        const cache = await caches.open(name);
        for (const req of await cache.keys()) {
            if (new URL(req.url).pathname.startsWith(prefix)) {
                await cache.delete(req);
                removed++;
            }
        }
    }
    capsPromise = null;
    return removed;
}

export async function deleteAllDownloaded() {
    let removed = 0;
    for (const id of TIER_ORDER) removed += await deleteDownloaded(id);
    return removed;
}

// ------------------------------------------------------------------ engine (Web Worker)

const engine = {
    worker: null,
    tierId: null,
    backend: null,
    loading: null,
    seq: 0,
    pending: new Map(),
    activeGenerateId: null,
    queue: Promise.resolve()
};

function ensureWorker() {
    if (engine.worker) return engine.worker;
    const worker = new Worker("/js/ai/ai-worker.js", { type: "module" });
    worker.addEventListener("message", (event) => {
        const msg = event.data || {};
        const pending = engine.pending.get(msg.id);
        if (!pending) return;
        if (msg.type === "progress") {
            pending.onProgress && pending.onProgress(msg);
        } else if (msg.type === "token") {
            pending.onToken && pending.onToken(msg.text, msg.full);
        } else if (msg.type === "error") {
            engine.pending.delete(msg.id);
            pending.reject(new Error(msg.message || "Local AI error"));
        } else if (msg.type === "loaded" || msg.type === "done" || msg.type === "unloaded") {
            engine.pending.delete(msg.id);
            pending.resolve(msg);
        }
    });
    worker.addEventListener("error", (event) => {
        const err = new Error(event.message || "Local AI worker crashed");
        engine.pending.forEach((p) => p.reject(err));
        engine.pending.clear();
        engine.worker = null;
        engine.tierId = null;
        engine.loading = null;
    });
    engine.worker = worker;
    return worker;
}

function call(type, payload, handlers = {}) {
    const worker = ensureWorker();
    const id = ++engine.seq;
    return new Promise((resolve, reject) => {
        engine.pending.set(id, { resolve, reject, ...handlers });
        worker.postMessage({ type, id, ...payload });
    }).then((msg) => ({ ...msg, id }));
}

export function getEngineStatus() {
    return { tierId: engine.tierId, backend: engine.backend, loading: !!engine.loading };
}

/**
 * Loads a tier into the worker, downloading (and caching) weights on first use.
 * onProgress receives { ratio (0..1), loaded, total, text }.
 */
export async function load(tierId, { onProgress } = {}) {
    const tiers = await getInstalledTiers();
    const tier = tiers.find((x) => x.id === tierId);
    if (!tier) throw new Error(t("ai.err.tier_not_installed", "This model is not installed on the server."));
    const caps = await capabilities();
    const assessment = assessTier(tier, caps);
    if (assessment.status === "unsupported") throw new Error(assessment.reason);

    if (engine.tierId === tierId && !engine.loading) return { tier, backend: engine.backend };
    if (engine.loading) {
        try { await engine.loading; } catch (_) { /* replaced below */ }
        if (engine.tierId === tierId) return { tier, backend: engine.backend };
    }
    if (engine.tierId && engine.tierId !== tierId) await unload();

    const manifest = await getManifest();
    engine.loading = call("load", {
        tier,
        runtime: manifest.runtime,
        backend: assessment.backend,
        origin: location.origin
    }, { onProgress });
    try {
        const msg = await engine.loading;
        engine.tierId = tierId;
        engine.backend = msg.backend || assessment.backend;
        try {
            if (navigator.storage && navigator.storage.persist) await navigator.storage.persist();
        } catch (_) { /* best effort */ }
        capsPromise = null;
        return { tier, backend: engine.backend };
    } finally {
        engine.loading = null;
    }
}

/**
 * Streams a chat completion. messages: [{role:'system'|'user'|'assistant', content}].
 * options: { maxTokens, temperature, onToken(delta, full), signal, jsonSchema }
 * Requests are serialized: only one generation runs at a time.
 */
export function generate(messages, options = {}) {
    const run = async () => {
        if (!engine.tierId) throw new Error(t("ai.err.not_loaded", "No model is loaded."));
        if (options.signal && options.signal.aborted) throw new DOMException("Aborted", "AbortError");
        const onAbort = () => abort();
        if (options.signal) options.signal.addEventListener("abort", onAbort, { once: true });
        try {
            const result = await call("generate", {
                messages,
                options: {
                    maxTokens: options.maxTokens || 400,
                    temperature: options.temperature ?? 0.3,
                    jsonSchema: options.jsonSchema || null
                }
            }, { onToken: options.onToken });
            if (options.signal && options.signal.aborted) {
                const err = new DOMException("Aborted", "AbortError");
                err.partial = result.text || "";
                throw err;
            }
            return result.text || "";
        } catch (err) {
            const isDisposedOrUnloaded = /disposed|not loaded|MLCEngine\.reload|Device lost|GrammarMatcher/i.test(err.message || "");
            if (isDisposedOrUnloaded && engine.tierId) {
                const currentTierId = engine.tierId;
                await unload();
                await load(currentTierId);
                const retryResult = await call("generate", {
                    messages,
                    options: {
                        maxTokens: options.maxTokens || 400,
                        temperature: options.temperature ?? 0.3,
                        jsonSchema: options.jsonSchema || null
                    }
                }, { onToken: options.onToken });
                return retryResult.text || "";
            }
            throw err;
        } finally {
            if (options.signal) options.signal.removeEventListener("abort", onAbort);
        }
    };
    const next = engine.queue.then(run, run);
    engine.queue = next.catch(() => {});
    return next;
}

export function abort() {
    if (engine.worker) engine.worker.postMessage({ type: "abort" });
}

export async function unload() {
    if (!engine.worker) return;
    try {
        await call("unload", {});
    } catch (_) { /* ignore */ }
    engine.worker.terminate();
    engine.worker = null;
    engine.tierId = null;
    engine.backend = null;
}

/**
 * Picks the tier features should use on this device: the user's selection if it's installed and runnable,
 * otherwise the best tier that is already downloaded, otherwise the best runnable tier.
 */
export async function resolveTier() {
    const tiers = await getInstalledTiers();
    if (!tiers.length) return null;
    const caps = await capabilities();
    const runnable = tiers.filter((tier) => assessTier(tier, caps).status !== "unsupported");
    if (!runnable.length) return null;
    const selected = runnable.find((tier) => tier.id === getSelectedTierId());
    if (selected) return selected;
    for (const tier of runnable.slice().reverse()) {
        if (await isDownloaded(tier, assessTier(tier, caps).backend)) return tier;
    }
    return runnable[0];
}

// ------------------------------------------------------------------ IndexedDB (summaries, conversations)

const DB_NAME = "obsidianscout-ai";
let dbPromise = null;

function openDb() {
    if (!dbPromise) {
        dbPromise = new Promise((resolve, reject) => {
            const req = indexedDB.open(DB_NAME, 1);
            req.onupgradeneeded = () => {
                const db = req.result;
                if (!db.objectStoreNames.contains("summaries")) db.createObjectStore("summaries");
                if (!db.objectStoreNames.contains("conversations")) db.createObjectStore("conversations");
            };
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        }).catch((err) => {
            dbPromise = null;
            throw err;
        });
    }
    return dbPromise;
}

export async function idbGet(store, key) {
    try {
        const db = await openDb();
        return await new Promise((resolve, reject) => {
            const req = db.transaction(store, "readonly").objectStore(store).get(key);
            req.onsuccess = () => resolve(req.result ?? null);
            req.onerror = () => reject(req.error);
        });
    } catch (_) {
        return null;
    }
}

export async function idbSet(store, key, value) {
    try {
        const db = await openDb();
        await new Promise((resolve, reject) => {
            const tx = db.transaction(store, "readwrite");
            tx.objectStore(store).put(value, key);
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
        });
    } catch (_) { /* storage blocked */ }
}

export async function idbDelete(store, key) {
    try {
        const db = await openDb();
        await new Promise((resolve, reject) => {
            const tx = db.transaction(store, "readwrite");
            tx.objectStore(store).delete(key);
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
        });
    } catch (_) { /* storage blocked */ }
}

export async function idbClear(store) {
    try {
        const db = await openDb();
        await new Promise((resolve, reject) => {
            const tx = db.transaction(store, "readwrite");
            tx.objectStore(store).clear();
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
        });
    } catch (_) { /* storage blocked */ }
}

/** Short stable hash for cache keys. */
export function hashString(text) {
    let h1 = 0xdeadbeef ^ text.length;
    let h2 = 0x41c6ce57 ^ text.length;
    for (let i = 0; i < text.length; i++) {
        const ch = text.charCodeAt(i);
        h1 = Math.imul(h1 ^ ch, 2654435761);
        h2 = Math.imul(h2 ^ ch, 1597334677);
    }
    h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
    h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
    return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
}

if (typeof window !== "undefined") window.addEventListener("pagehide", () => {
    if (engine.worker) {
        engine.worker.terminate();
        engine.worker = null;
        engine.tierId = null;
    }
});

const api = {
    TIER_ORDER,
    TIER_PROFILES,
    tierProfile,
    pruneRetiredCaches,
    getMe,
    isEnabled,
    getManifest,
    getInstalledTiers,
    getSelectedTierId,
    setSelectedTierId,
    capabilities,
    isCpuForced,
    setCpuForced,
    assessTier,
    formatBytes,
    isDownloaded,
    deleteDownloaded,
    deleteAllDownloaded,
    getEngineStatus,
    load,
    generate,
    abort,
    unload,
    resolveTier,
    idbGet,
    idbSet,
    idbDelete,
    idbClear,
    hashString
};

if (typeof window !== "undefined") window.ObsidianscoutAI = Object.assign(window.ObsidianscoutAI || {}, api);
export default api;
