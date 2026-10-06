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
            const res = await fetch(url, { headers: { Range: `bytes=${start}-${end}` }, credentials: "same-origin" });
            if (!(res.status === 206 || (res.status === 200 && chunks === 1))) {
                throw new Error(`Download failed (${res.status}) for ${url}`);
            }
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
            if (got !== size) throw new Error(`Incomplete download for ${url}`);
            await cache.put(key, new Response(new Blob(parts)));
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

async function loadTransformersCpu(id, tier, runtime, origin) {
    const cpu = tier.cpu;
    const cache = await caches.open(CPU_FILE_CACHE);

    // 1. Download (or confirm cached) every file, with byte-level progress.
    const total = cpu.files.reduce((sum, f) => sum + f.bytes, 0);
    let completed = 0;
    for (const file of cpu.files) {
        const url = origin + file.url;
        const base = completed;
        await ensureChunkedFile(cache, url, file.bytes, (n) => {
            const loaded = base + n;
            post({ type: "progress", id, loaded, total, ratio: total ? loaded / total : 0, text: file.url.split("/").pop() });
        });
        completed += file.bytes;
    }

    // 2. Load from that cache via a custom cache adapter (Transformers' own browser cache can't store large files).
    const T = await import(origin + runtime.transformers);
    T.env.allowRemoteModels = false;
    T.env.allowLocalModels = true;
    // Must stay a relative path: transformers.js treats http(s) URLs as remote and skips local loading.
    T.env.localModelPath = cpu.localModelPath;
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
    state.T = T;
    state.tokenizer = await T.AutoTokenizer.from_pretrained(cpu.modelId);
    state.model = await T.AutoModelForCausalLM.from_pretrained(cpu.modelId, { dtype: cpu.dtype, device: "wasm" });
    state.kind = "transformers";
    state.backend = "cpu";
}

async function loadWebLLM(id, tier, runtime, origin) {
    const gpu = tier.webgpu;
    const webllm = await import(origin + runtime.webllm);
    const appConfig = {
        model_list: [{
            model: origin + gpu.modelUrl,
            model_id: gpu.mlcModelId,
            model_lib: origin + gpu.modelLibUrl,
            vram_required_MB: tier.vramMB,
            overrides: {
                context_window_size: tier.contextTokens || 4096
            }
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
}

async function generateTransformers(id, messages, options) {
    const { T, tokenizer, model } = state;
    const inputs = tokenizer.apply_chat_template(messages, { add_generation_prompt: true, return_dict: true });
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
    const request = {
        messages,
        stream: true,
        temperature: options.temperature,
        top_p: 0.9,
        max_tokens: options.maxTokens
    };
    let full = "";
    const chunks = await state.webllm.chat.completions.create(request);
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
                await loadTransformersCpu(msg.id, msg.tier, msg.runtime, msg.origin);
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
