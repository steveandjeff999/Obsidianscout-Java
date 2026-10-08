/**
 * Local AI inference worker - ObsidianScout
 * GPU: WebLLM (WebGPU). CPU fallback (Lite only): Transformers.js with a q8 ONNX model.
 * Runtimes and weights are imported/fetched from this server's /models/ path only.
 */

const CPU_FILE_CACHE = "obsidianscout-ai-files";
// Chromium rejects single Cache Storage entries of a few hundred MB, so large files are stored in chunks.
const CHUNK_BYTES = 64 * 1024 * 1024;

let state = {
    kind: null,       // "transformers" | "webllm"
    T: null,
    tokenizer: null,
    model: null,
    stopper: null,
    webllm: null,
    backend: null
};

function post(msg) {
    self.postMessage(msg);
}

// ------------------------------------------------------------------ chunked file cache (CPU fallback)

async function ensureChunkedFile(cache, url, bytes, onBytes) {
    if (await cache.match(url + "?meta")) {
        onBytes(bytes);
        return;
    }
    const chunks = Math.max(1, Math.ceil(bytes / CHUNK_BYTES));
    let done = 0;
    for (let i = 0; i < chunks; i++) {
        const key = `${url}?chunk=${i}`;
        const start = i * CHUNK_BYTES;
        const end = Math.min(bytes, start + CHUNK_BYTES) - 1;
        const size = end - start + 1;
        if (!(await cache.match(key))) {
            // no-store: the worker keeps its own copy, and a stale HTTP-cached error must never be reused.
            const res = await fetch(url, { headers: { Range: `bytes=${start}-${end}` }, credentials: "same-origin", cache: "no-store" });
            if (res.status === 206) {
                const reader = res.body.getReader();
                const parts = [];
                let got = 0;
                for (;;) {
                    const { done: finished, value } = await reader.read();
                    if (finished) break;
                    parts.push(value);
                    got += value.byteLength;
                    onBytes(done + got);
                }
                if (got !== size) throw new Error(`Incomplete download for ${url} (chunk ${i}: expected ${size}, got ${got})`);
                await cache.put(key, new Response(new Blob(parts)));
            } else if (res.status === 200) {
                if (i === 0) {
                    const reader = res.body.getReader();
                    const allParts = [];
                    let gotTotal = 0;
                    for (;;) {
                        const { done: finished, value } = await reader.read();
                        if (finished) break;
                        allParts.push(value);
                        gotTotal += value.byteLength;
                        onBytes(gotTotal);
                    }
                    const fullBlob = new Blob(allParts);
                    for (let c = 0; c < chunks; c++) {
                        const cStart = c * CHUNK_BYTES;
                        const cEnd = Math.min(bytes, cStart + CHUNK_BYTES);
                        const chunkBlob = fullBlob.slice(cStart, cEnd);
                        await cache.put(`${url}?chunk=${c}`, new Response(chunkBlob));
                    }
                    break;
                } else {
                    throw new Error(`Unexpected 200 OK for chunk ${i} of ${url}`);
                }
            } else {
                throw new Error(`Download failed (${res.status}) for ${url}`);
            }
        }
        done += size;
        onBytes(done);
    }
    await cache.put(url + "?meta", new Response(JSON.stringify({ bytes, chunks })));
}

async function readChunkedFile(cache, url) {
    const meta = await cache.match(url + "?meta");
    if (!meta) return undefined;
    const { bytes, chunks } = await meta.json();
    const parts = [];
    for (let i = 0; i < chunks; i++) {
        const hit = await cache.match(`${url}?chunk=${i}`);
        if (!hit) return undefined;
        parts.push(await hit.blob());
    }
    const type = url.endsWith(".json") ? "application/json" : "application/octet-stream";
    return new Response(new Blob(parts, { type }), { headers: { "content-length": String(bytes), "content-type": type } });
}

// ------------------------------------------------------------------ engines

/**
 * Loads a Transformers.js ONNX build: Lite's CPU fallback (device "wasm") or Gemma 4 on WebGPU.
 * Files are pre-downloaded into chunked Cache Storage (with progress) and served back through a custom cache,
 * because Chromium can't store the multi-hundred-MB files as single cache entries.
 */
async function loadTransformers(id, source, runtime, origin, device) {
    const cache = await caches.open(CPU_FILE_CACHE);

    // 1. Download (or confirm cached) every file, with byte-level progress.
    const total = source.files.reduce((sum, f) => sum + f.bytes, 0);
    let completed = 0;
    for (const file of source.files) {
        const url = origin + file.url;
        const base = completed;
        await ensureChunkedFile(cache, url, file.bytes, (n) => {
            const loaded = base + n;
            post({ type: "progress", id, loaded, total, ratio: total ? loaded / total : 0, text: file.url.split("/").pop() });
        });
        completed += file.bytes;
    }

    // 2. Load from that cache via a custom cache adapter.
    const T = await import(origin + runtime.transformers);
    T.env.allowRemoteModels = false;
    T.env.allowLocalModels = true;
    // Must stay a relative path: transformers.js treats http(s) URLs as remote and skips local loading.
    T.env.localModelPath = source.localModelPath;
    T.env.useBrowserCache = false;
    T.env.useCustomCache = true;
    T.env.customCache = {
        match: async (key) => {
            const k = String(key);
            if (!k.startsWith("/models/") && !k.startsWith(origin + "/models/")) return undefined;
            return readChunkedFile(cache, k.startsWith("/") ? origin + k : k);
        },
        put: async () => { /* files are pre-cached above */ }
    };
    // The WASM cache re-imports the ORT loader from a blob: URL, which our CSP (script-src 'self') blocks.
    T.env.useWasmCache = false;
    T.env.backends.onnx.wasm.wasmPaths = { mjs: origin + runtime.ortMjs, wasm: origin + runtime.ortWasm };

    post({ type: "progress", id, loaded: total, total, ratio: 1, text: "init" });
    const ModelClass = T[source.modelClass] || T.AutoModelForCausalLM;
    // A single-session model (Lite) takes a plain dtype string; multi-session models (Gemma 4) a per-session map.
    const dtypeKeys = Object.keys(source.dtype || {});
    const dtype = dtypeKeys.length === 1 && dtypeKeys[0] === "model" ? source.dtype.model : source.dtype;
    state.T = T;
    state.tokenizer = await T.AutoTokenizer.from_pretrained(source.modelId);
    if (!state.tokenizer.chat_template) {
        // Newer repos (e.g. Gemma 4) ship the template as chat_template.jinja, which the tokenizer doesn't read itself.
        const templateFile = (source.files || []).find((f) => f.url.endsWith("/chat_template.jinja"));
        const res = templateFile ? await readChunkedFile(cache, origin + templateFile.url) : null;
        if (res) state.tokenizer.chat_template = await res.text();
    }
    state.model = await ModelClass.from_pretrained(source.modelId, { dtype, device });
    state.kind = "transformers";
    state.backend = device === "wasm" ? "cpu" : "webgpu";
    state.modelId = source.modelId;
}

async function loadWebLLM(id, tier, runtime, origin) {
    const gpu = tier.webgpu;
    const webllm = await import(origin + runtime.webllm);
    const isLlama = /llama/i.test(gpu.mlcModelId || "");
    const contextSize = isLlama ? (tier.contextTokens || 2048) : ((gpu.modelLibUrl && gpu.modelLibUrl.includes("_cs1k")) ? 1024 : (tier.contextTokens || 4096));
    const overrides = isLlama ? {
        context_window_size: contextSize,
        ...(gpu.overrides || tier.overrides || {})
    } : {
        context_window_size: contextSize,
        sliding_window_size: -1,
        attention_sink_size: -1,
        ...(gpu.overrides || tier.overrides || {})
    };
    const appConfig = {
        model_list: [{
            model: origin + gpu.modelUrl,
            model_id: gpu.mlcModelId,
            model_lib: origin + gpu.modelLibUrl,
            vram_required_MB: tier.vramMB,
            overrides
        }],
        cacheBackend: "cache"
    };
    const total = gpu.downloadBytes || 0;
    state.webllm = await webllm.CreateMLCEngine(gpu.mlcModelId, {
        appConfig,
        initProgressCallback: (r) => {
            const ratio = r.progress || 0;
            post({ type: "progress", id, ratio, loaded: Math.round(total * ratio), total, text: r.text || "" });
        }
    });
    state.kind = "webllm";
    state.backend = "webgpu";
    state.modelId = gpu.mlcModelId;
}

function normalizeChatMessages(messages, isGemma = false) {
    if (!isGemma) return messages;
    const mapped = [];
    let pendingSystem = "";
    for (const m of messages || []) {
        if (m.role === "system") {
            pendingSystem += (pendingSystem ? "\n\n" : "") + (m.content || "");
        } else if (m.role === "user") {
            const content = pendingSystem ? `${pendingSystem}\n\n${m.content || ""}` : (m.content || "");
            pendingSystem = "";
            mapped.push({ role: "user", content });
        } else {
            mapped.push({ role: m.role || "assistant", content: m.content || "" });
        }
    }
    if (pendingSystem) {
        mapped.unshift({ role: "user", content: pendingSystem });
    }
    return mapped.length ? mapped : [{ role: "user", content: "Hello" }];
}

async function generateTransformers(id, messages, options) {
    const { T, tokenizer, model } = state;
    const isGemma = /gemma/i.test(state.modelId || "");
    const cleanMessages = normalizeChatMessages(messages, isGemma);
    const inputs = tokenizer.apply_chat_template(cleanMessages, { add_generation_prompt: true, return_dict: true });
    let full = "";
    const streamer = new T.TextStreamer(tokenizer, {
        skip_prompt: true,
        skip_special_tokens: true,
        callback_function: (text) => {
            full += text;
            post({ type: "token", id, text, full });
        }
    });
    state.stopper = new T.InterruptableStoppingCriteria();
    const sample = (options.temperature || 0) > 0;
    await model.generate({
        ...inputs,
        max_new_tokens: options.maxTokens,
        do_sample: sample,
        ...(sample ? { temperature: options.temperature, top_p: 0.9 } : {}),
        // Note: repetition_penalty corrupts output in transformers.js 4.3 (echoes the prompt, then loops).
        streamer,
        stopping_criteria: state.stopper
    });
    state.stopper = null;
    return full.trim();
}

async function generateWebLLM(id, messages, options) {
    const isGemma = /gemma/i.test(state.modelId || "");
    const cleanMessages = normalizeChatMessages(messages, isGemma);
    const temp = (options.temperature == null || options.temperature === 0) ? 0.05 : options.temperature;
    const request = {
        messages: cleanMessages,
        stream: true,
        temperature: temp,
        top_p: 0.9,
        max_tokens: options.maxTokens || 256
    };
    if (options.jsonSchema) {
        request.response_format = {
            type: "json_object",
            schema: typeof options.jsonSchema === "string" ? options.jsonSchema : JSON.stringify(options.jsonSchema)
        };
    }
    let full = "";
    let chunks;
    try {
        chunks = await state.webllm.chat.completions.create(request);
    } catch (err) {
        if (request.response_format) {
            delete request.response_format;
            chunks = await state.webllm.chat.completions.create(request);
        } else {
            throw err;
        }
    }
    for await (const chunk of chunks) {
        const text = (chunk.choices && chunk.choices[0] && chunk.choices[0].delta && chunk.choices[0].delta.content) || "";
        if (text) {
            full += text;
            post({ type: "token", id, text, full });
        }
    }
    return full.trim();
}

async function unloadAll() {
    try {
        if (state.model && state.model.dispose) await state.model.dispose();
    } catch (_) { /* ignore */ }
    try {
        if (state.webllm && state.webllm.unload) await state.webllm.unload();
    } catch (_) { /* ignore */ }
    state = { kind: null, T: null, tokenizer: null, model: null, stopper: null, webllm: null, backend: null };
}

self.addEventListener("message", async (event) => {
    const msg = event.data || {};
    try {
        if (msg.type === "abort") {
            if (state.stopper) state.stopper.interrupt();
            if (state.webllm) state.webllm.interruptGenerate();
            return;
        }
        if (msg.type === "load") {
            await unloadAll();
            if (msg.backend === "cpu") {
                await loadTransformers(msg.id, msg.tier.cpu, msg.runtime, msg.origin, "wasm");
            } else if (msg.tier.webgpu && msg.tier.webgpu.engine === "transformers") {
                await loadTransformers(msg.id, msg.tier.webgpu, msg.runtime, msg.origin, "webgpu");
            } else {
                await loadWebLLM(msg.id, msg.tier, msg.runtime, msg.origin);
            }
            post({ type: "loaded", id: msg.id, backend: state.backend });
            return;
        }
        if (msg.type === "generate") {
            if (!state.kind) throw new Error("No model is loaded.");
            const text = state.kind === "transformers"
                ? await generateTransformers(msg.id, msg.messages, msg.options || {})
                : await generateWebLLM(msg.id, msg.messages, msg.options || {});
            post({ type: "done", id: msg.id, text });
            return;
        }
        if (msg.type === "unload") {
            await unloadAll();
            post({ type: "unloaded", id: msg.id });
        }
    } catch (err) {
        post({ type: "error", id: msg.id, message: (err && err.message) || String(err) });
    }
});
