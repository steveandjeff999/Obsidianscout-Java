# Local AI Assistant — Implementation Plan (v2)

> **Status (2026-10-06): implemented.** User guide: `docs/LOCAL_AI_ASSISTANT.md` (+ `_es`, `_he`, `_tr`).
>
> **As built (differences from this plan, found during in-browser testing):**
> - **All GPU tiers use WebLLM**, including Lite (`mlc-ai/Qwen2.5-0.5B-Instruct-q4f16_1-MLC`). Transformers.js 4.3's q4f16 ONNX build of the 0.5B model produced garbage on WebGPU, so Transformers.js is used only for Lite's **CPU fallback** (q8 ONNX, WASM).
> - Chromium rejects single Cache Storage entries above ~200-400 MB, so the CPU fallback's ~500 MB ONNX file is cached in 64 MB Range-request chunks by the worker. `repetition_penalty` is not used (it corrupts output in Transformers.js 4.3), and `env.useWasmCache=false` keeps ORT from importing `blob:` scripts (blocked by the CSP).
> - Runtimes (Transformers.js, ONNX Runtime Web, web-llm) are **not vendored**: the server installer downloads them into `data/models/runtime/<version>/` alongside the weights, so the repo/native image stay small. Layout: `data/models/<tier>/<version>/{mlc,cpu}/…`.
> - **Lite never writes free prose about data.** Assistant answers on Lite are deterministic "briefs" built by code from tool results; notes summaries on Lite are extractive (clauses classified strength/weakness by keyword rules plus the model, then quoted). Standard/Advanced rewrite pre-sorted observations and get the briefs as key points, which removed invented strengths in testing.
> - The rule router understands English, Spanish, Turkish and Hebrew keywords; suggestion chips pass their tool route directly. Standard/Advanced pick tools with grammar-constrained JSON (`response_format` JSON schema in WebLLM); Advanced can chain up to 4 tool calls.
> - Model files are served with Range support, `/models/**` requires a session, and those paths are excluded from gzip and cluster peer forwarding.

**Models (all run in the browser, weights served by the ObsidianScout server):**

| Tier | Model | Engine | Backend | Approx. download | Approx. GPU memory | Intended for |
|---|---|---|---|---|---|---|
| **Lite** | Qwen2.5-0.5B-Instruct | Transformers.js | WebGPU (q4f16), or WASM CPU fallback (q4) | ~0.5 GB | ~1 GB | Notes summaries, tagging, simple Q&A. Works on phones and Chromebooks. |
| **Standard** | Qwen2.5-1.5B-Instruct | WebLLM (MLC) | WebGPU only (q4f16_1) | ~1.0 GB | ~1.7 GB | Better chat, single tool-call reasoning. Laptops and recent phones. |
| **Advanced** | Qwen2.5-3B-Instruct | WebLLM (MLC) | WebGPU only (q4f16_1) | ~1.8 GB | ~2.6 GB | Multi-step reasoning, strategy questions, longer conversations. Desktops and laptops with a dedicated or modern integrated GPU. |

All sizes are estimates. Phase 0 measures the real values, and the fetch script records exact sizes in the manifest.

**Decided:**
- The AI feature is **off by default** for every user.
- Users enable it in Personal Settings and pick a model **per device**.
- There is **no team or admin switch**.
- Summaries and conversations are **never shared or stored on the server**.
- The server only serves static weight files.

> ⚠️ **License check required before shipping the 3B model.** Qwen2.5-0.5B and 1.5B are Apache-2.0. **Qwen2.5-3B-Instruct is released under the Qwen Research License**, which limits use to non-commercial and research purposes unless you get a separate agreement. Confirm that ObsidianScout's use (hosting and redistributing the weights from your server) qualifies. If it doesn't, the registry design below lets you replace 3B with another ~3B model, or ship only Lite and Standard, without code changes.

---

## 1. Goals and principles

- **100% client-side inference.** Prompts, scouting data, and outputs never leave the device. The server only serves static model files from its own disk, so no third-party CDN is involved.
- **Off by default, per user**, with an explicit download consent per model per device.
- **Numbers and charts always come from code.** All three models paraphrase and reason over facts that deterministic JS computes; they never produce statistics themselves. The 3B model gets more autonomy (multi-step tool use), not permission to invent numbers.
- **One loaded model at a time per page.** Switching models unloads the previous one to free GPU memory.
- **Engine-agnostic core.** Every feature calls one `LocalLLM` facade. The engine (Transformers.js or WebLLM) is a per-model detail in the registry.

### Why two engines
- **Lite uses Transformers.js** because it's the only option with a solid **CPU/WASM fallback**, which phones without WebGPU need.
- **Standard and Advanced use WebLLM.** It has prebuilt, well-optimized Qwen2.5 1.5B and 3B q4f16 builds, and it ships weights as **~30–100 MB shards**. That matters for three reasons:
  1. Interrupted downloads resume per shard instead of restarting a 2 GB file.
  2. Each shard is far below Cloudflare's 512 MB per-file cache limit, so the edge can cache them.
  3. It avoids ONNX's 2 GB single-file protobuf limit, which the 3B model would hit in Transformers.js.
- These models are WebGPU-only anyway. Neither 1.5B nor 3B is usable on CPU WASM, and 3B doesn't fit in WASM's 4 GB address space with a KV cache.

---

## 2. Phase 0 — Spike (2–3 days, go/no-go)

Build `scratch/llm-spike.html`, which loads each tier from `/models/` and logs: download time, cold and warm load time, time to first token, tok/s, peak GPU memory (from WebLLM's stats), and output on five fixed prompts (two notes summaries, one comparison, one strategy question, one tool-call JSON).

**Device matrix:**

| Device | Lite | Standard | Advanced |
|---|---|---|---|
| Windows desktop, discrete GPU, Chrome | ✓ | ✓ | ✓ |
| Laptop, integrated GPU, Chrome or Edge | ✓ | ✓ | ✓ (measure) |
| Android, Chrome | ✓ | measure | ✗ expected |
| iPhone, Safari 26 (WebGPU) | ✓ | measure (tab memory cap) | ✗ expected |
| Firefox (WebGPU on Windows) | ✓ | measure | measure |
| Low-end Chromebook | ✓ WASM | ✗ | ✗ |

**Exit criteria:**
- Measured sizes and memory recorded into the registry.
- The gating thresholds in §5.3 are confirmed.
- WebLLM works under the CSP in §4.4. Specifically, it needs nothing beyond `'wasm-unsafe-eval'`. If it needs `'unsafe-eval'`, stop and reconsider before going further.
- Quality is judged acceptable per tier.

---

## 3. Phase 1 — Per-user setting (default **off**)

### 3.1 Server column
New column: `users.local_ai_enabled BOOLEAN NOT NULL DEFAULT FALSE`. Follow the `bugReportPreference` pattern at every touchpoint:

| File | Change |
|---|---|
| `db/Tables.kt` (`Users`, ~line 22) | `val localAiEnabled = bool("local_ai_enabled").default(false)` |
| `db/DatabaseFactory.kt` (~393, ~458, ~547) | Add the SQLite default mapping (`columnName == "local_ai_enabled" -> "0"`) and `ALTER TABLE users ADD COLUMN IF NOT EXISTS local_ai_enabled BOOLEAN NOT NULL DEFAULT FALSE` in both migration paths |
| `auth/AuthService.kt` (~70, ~527, ~915) | User DTO field, a `newLocalAiEnabled: Boolean?` param on `updateUser` + `stmt[localAiEnabled]`, and the row mapper |
| `auth/Session.kt` (~45, ~82–103, ~160–179) | Session field + DB-refresh comparison |
| `routes/Models.kt` (`UpdateUserRequest`) | `val localAiEnabled: Boolean? = null` |
| `routes/Routes.kt` | Pass it through in `PUT /api/user` and `PUT /api/user/profile-picture`, and copy it into the session (~2489–2545). Include it in every `/api/auth/me` response builder (~241, 258, 314, 331, 365, 484, 501, 2035) and in the "session attributes changed" check (~376). |
| `db/QuorumFallbackStore.kt:558`, `db/SnapshotService.kt:276, 817` | **Copy the column.** These copy user columns by hand, so without this, replication and backup restore would silently reset the setting. |

**Tests (Kotlin):**
- A new user gets `localAiEnabled == false`.
- `PUT /api/user {localAiEnabled:true}` is reflected in `/api/auth/me`.
- A snapshot/restore round trip keeps the value.

### 3.2 Per-device state (client, not server)
The model choice depends on the device: a scout's phone might run Lite while their laptop runs Advanced. So these live in `localStorage` (through `Obsidianscout.safeGetItem`/`safeSetItem`), not the DB:
- `obsidianscout:local_ai_model`: `lite` | `standard` | `advanced`
- `obsidianscout:local_ai_downloaded`: a cache of which models are fully downloaded. The source of truth is checking Cache Storage.

### 3.3 Settings UI (`static/config.html` personal panel, next to `#personal-bug-report-pref` ~line 153; logic in `js/settings.js`)
A **Local AI Assistant** card:
1. **Enable on/off toggle**, default off. Wire it with `wirePersonalLocalAiWidget(currentMe)`, modeled on the bug-report widget (~line 237): PUT `{ localAiEnabled }`, then clear `cache:/api/auth/me` and `etag:/api/auth/me`. The rest of the card shows only when it's on.
2. **Model picker**: three radio cards (Lite, Standard, Advanced), each showing download size, GPU memory, and a capability badge from §5.3: ✓ Supported, ⚠ Will be slow, or ✗ Not supported on this device. Unsupported options are disabled, with a reason such as "Requires WebGPU" or "Needs ~2.6 GB GPU memory".
3. **Per-model storage rows**: status (Not downloaded / Downloading 43% / Ready, 1.02 GB), plus **Download** and **Delete** buttons. Show total use from `navigator.storage.estimate()`.
4. **Turning the toggle off** shows a dialog: "Also delete downloaded models (X GB) from this device?"

---

## 4. Phase 1 — Server serves the weights, plus the platform fixes

### 4.1 Model storage layout (filesystem, outside the app bundle)
```
data/models/
  manifest.json                         ← generated by fetch script, served to clients
  qwen2.5-0.5b-instruct/<hf-rev>/       ← Transformers.js layout
      config.json, generation_config.json, tokenizer.json, tokenizer_config.json
      onnx/model_q4f16.onnx, onnx/model_q4.onnx
  qwen2.5-1.5b-instruct-q4f16_1-mlc/<hf-rev>/   ← WebLLM layout
      mlc-chat-config.json, ndarray-cache.json, tokenizer.json, ..., params_shard_*.bin
  qwen2.5-3b-instruct-q4f16_1-mlc/<hf-rev>/
      ...
  libs/
      Qwen2.5-1.5B-Instruct-q4f16_1-ctx4k_cs1k-webgpu.wasm   ← WebLLM compiled model libs, pinned to the web-llm version
      Qwen2.5-3B-Instruct-q4f16_1-ctx4k_cs1k-webgpu.wasm
```
- **This must not go under `src/main/resources/static/`.** `resource-config.json` includes `static/.*`, which would bake gigabytes into the native image and the fat JAR.
- Every path contains a pinned revision, so URLs are immutable and can be cached forever.
- **Disk:** about 3.5–4 GB for all three tiers.

### 4.2 Fetch script: `scripts/fetch-ai-models.ps1` and `scripts/fetch-ai-models.sh`
- Usage: `fetch-ai-models --models lite,standard,advanced [--dir data/models]`.
- It downloads from the pinned HF revisions:
  - `onnx-community/Qwen2.5-0.5B-Instruct`
  - `mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC`
  - `mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC`
  - the matching WebLLM model-lib `.wasm` files from `mlc-ai/binary-mlc-llm-libs`, at the tag that matches the vendored web-llm version
- It verifies SHA-256 for each file and is **resumable**: it skips files whose hash already matches.
- It writes `manifest.json` with an entry per installed tier: `{ id, tier, engine, revision, baseUrl, modelLibUrl?, files: [{path, bytes, sha256}], totalBytes, vramMB, contextTokens, license }`.
- Call it from `release-local.bat` and the update scripts with an opt-in flag, so normal updates don't re-download.
- The server **only advertises installed tiers**. If the operator only fetches Lite, the settings UI only shows Lite.

### 4.3 `/models` route (`routes/Routes.kt`, before the `staticFiles("/", …)` catch-all ~line 4148)
```kotlin
// Local AI model weights: served from disk, never bundled. Session required to prevent hotlinking.
route("/models") {
    intercept(ApplicationCallPipeline.Plugins) { call.requireSession() }   // or the project's existing auth guard
    staticFiles("/", File(AppConfig.localAi.modelDir)) {
        cacheControl { listOf(CacheControl.MaxAge(31_536_000, visibility = CacheControl.Visibility.Private)) }
        // + header "Cache-Control: ..., immutable" (paths contain pinned revisions)
    }
}
```
- **Session required.** Same-origin `fetch` sends the session cookie, so it works for both engines. Unauthenticated requests get a 401 and burn no bandwidth.
- **Add the `PartialContent` plugin** (`io.ktor:ktor-server-partial-content-jvm:$ktorVersion`) and `AutoHeadResponse` to `build.gradle.kts` and `App.kt`. Range requests let browsers and Cloudflare resume large files. It's also needed if Transformers.js's 0.5B single file is interrupted.
- `GET /models/manifest.json` returns the generated manifest. Use `Cache-Control: no-cache`, because this file changes when tiers are installed.
- **Exclude `/models/**` from the `Compression` plugin.** See §4.5.
- **Response headers:** `Content-Type: application/octet-stream` for `.bin` and `.onnx`, `application/wasm` for `.wasm`, and `application/json` for the JSON files.
- **Bandwidth warning in the consent modal:** "Download at home on Wi-Fi, not at the event." Thirty scouts each pulling 2 GB over venue Wi-Fi will not work.

### 4.4 Fix: Content-Security-Policy (`App.kt` ~line 185)
```diff
- script-src 'self' 'unsafe-inline' https://static.cloudflareinsights.com;
+ script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval' https://static.cloudflareinsights.com;
+ worker-src 'self' blob:;
```
- `'wasm-unsafe-eval'` lets ONNX Runtime and TVM compile WebAssembly.
- `worker-src` allows the inference worker and the blob workers that ORT spawns.
- `connect-src 'self'` stays as is, because everything is served by your own origin. **No third-party hosts are added.**
- **Threads (optional, later):** multithreaded WASM, which only matters for Lite on CPU, needs `SharedArrayBuffer`, which needs cross-origin isolation. If you want it, send `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: credentialless` **only on the `/assistant` response**, not site-wide, so cdnjs fonts and the Cloudflare beacon keep working.

### 4.5 Fix: compression (`App.kt` ~line 195)
```kotlin
install(Compression) {
    gzip { priority = 1.0; minimumSize(256); condition { !request.path().startsWith("/models") } }
    deflate { priority = 0.9; minimumSize(256); condition { !request.path().startsWith("/models") } }
    identity()
}
```
Quantized weights don't compress. Without this, gzipping 2 GB per download burns CPU, and Range requests break.

### 4.6 Fix: service worker (`static/sw.js`)
1. **`activate` (~line 224) currently deletes every cache that isn't `CACHE_NAME`.** That wipes Transformers.js's `transformers-cache` and WebLLM's `webllm/model`, `webllm/config`, and `webllm/wasm` caches on every version bump, which would mean up to 4 GB re-downloaded per user per release.
   ```diff
   - if (key !== CACHE_NAME) {
   + if (key.startsWith('obsidianscout-shell-') && key !== CACHE_NAME) {
   ```
2. **The fetch handler stale-while-revalidates every same-origin asset into the shell cache** (~line 286), which would duplicate the weights. Bypass the model paths early:
   ```js
   if (url.pathname.startsWith('/models/') || url.pathname.startsWith('/vendor/transformers/') || url.pathname.startsWith('/vendor/web-llm/')) {
       return; // engines manage their own Cache Storage
   }
   ```
3. Precache `/assistant.html`, `/js/assistant.js`, and `/js/ai/*.js`. **Don't** precache the vendor engines or the weights.

After a model download finishes, call `navigator.storage.persist()` so the browser is less likely to evict gigabytes of weights under storage pressure.

### 4.7 Vendored libraries (`static/vendor/`)
- `vendor/transformers/`: the pinned `@huggingface/transformers` ESM build plus the `ort-wasm-simd-threaded.jsep.{mjs,wasm}` files.
- `vendor/web-llm/`: the pinned `@mlc-ai/web-llm` ESM build.
- `vendor/purify.min.js`: DOMPurify. It's required because `marked.min.js` doesn't sanitize, and model output is untrusted (it's derived from scout-written notes).
- **Check MIME types** in both deployments: `staticFiles` (native) and `staticResources` (JAR) must serve `.wasm` as `application/wasm` and `.mjs` as `text/javascript`. The comment above `respondStaticHtml` mentions native MIME problems, so test both.

---

## 5. Phase 2 — AI core (`static/js/ai/`)

### 5.1 Files
| File | Responsibility |
|---|---|
| `ai/registry.js` | Fetches `/models/manifest.json` and merges it with static per-tier settings (§5.4). Exposes `listTiers()` and `getTier(id)`. |
| `ai/capabilities.js` | Probes the device: `navigator.gpu?.requestAdapter()`, `adapter.features.has('shader-f16')`, `adapter.limits.maxBufferSize` and `maxStorageBufferBindingSize`, `navigator.deviceMemory`, `navigator.storage.estimate()`, and a mobile UA hint. Returns `supported` / `slow` / `unsupported`, with a reason, per tier. |
| `ai/local-llm.js` | Main-thread facade used by every feature. Exposes `isEnabled()` (`me.localAiEnabled`), `activeTier()`, `isDownloaded(tier)`, `download(tier, onProgress, signal)`, `load(tier, onProgress)`, `chat(messages, { tools?, maxTokens, temperature, onToken, signal })`, `unload()`, and `deleteModel(tier)`. It serializes requests through a queue. |
| `ai/llm-worker.js` | Module worker. It hosts **one** engine adapter and handles `load`, `generate`, `abort`, and `unload`. Inference never runs on the UI thread. |
| `ai/engines/transformers-engine.js` | Adapter for Transformers.js. Config: `env.allowRemoteModels=false`, `env.localModelPath='/models/'`, `env.backends.onnx.wasm.wasmPaths='/vendor/transformers/'`. Streams with `TextStreamer` and aborts with `InterruptableStoppingCriteria`. |
| `ai/engines/webllm-engine.js` | Adapter for WebLLM. It creates an `MLCEngine` with a custom `appConfig.model_list` that points `model` at `/models/<id>/<rev>/` and `model_lib` at `/models/libs/<lib>.wasm`, so nothing is fetched from HF or GitHub at runtime. It streams through `chat.completions.create({ stream: true })` and aborts with `interruptGenerate()`. |
| `ai/tool-protocol.js` | **Engine-independent tool calling.** It renders tool schemas into the system prompt in Qwen2.5's native `<tools>` / `<tool_call>{json}</tool_call>` format, parses tool calls out of streamed text, and validates arguments against hand-written schemas. Our own code owns this, so it behaves the same on both engines. |
| `ai/prompts.js` | System prompts and few-shot examples **per tier**, versioned so summary cache keys change when prompts change. |
| `ai/ai-ui.js` | Shared UI: per-tier download consent modal (size, Wi-Fi warning, storage), progress bar, "Generated on your device by AI — may be wrong" badge, and unsupported-device notices. |

### 5.2 Rules for every caller
- If `!LocalLLM.isEnabled()`, render **nothing**: no buttons, no nav link, no prompts. This is the default state.
- **Never auto-download.** Downloading always requires a click on the consent modal.
- If the selected tier isn't downloaded, offer to download it, or to use another tier that's already downloaded.
- Sanitize all rendered model output with DOMPurify after `marked`.
- Unload on `pagehide` to free GPU memory. Handle `GPUDevice.lost` by reloading once and showing a toast if it fails again.

### 5.3 Device gating (starting thresholds; Phase 0 confirms them)
| Tier | Requirement |
|---|---|
| Lite | Always allowed. WebGPU uses q4f16. Without WebGPU, it falls back to WASM q4 and shows a ⚠ "slow" warning when `deviceMemory ≤ 4`. |
| Standard | WebGPU plus `shader-f16`, and the adapter `maxBufferSize` must meet the model's largest buffer. On mobile it's ⚠. |
| Advanced | WebGPU plus `shader-f16`, not mobile, `deviceMemory ≥ 8` (Chrome caps this value at 8), and enough storage quota for 2 GB. Otherwise ✗. If the device has no `shader-f16`, a `q4f32_1` build is an optional extra the fetch script can download (`--include-f32`), but it needs about 40% more memory. |

### 5.4 Per-tier behaviour profile
| | Lite (0.5B) | Standard (1.5B) | Advanced (3B) |
|---|---|---|---|
| Context budget | 3k tokens | 6k | 8k |
| Question routing | Rule router, then a constrained-JSON fallback | Rule router, then native `<tool_call>`, **one** tool | Native `<tool_call>` loop, **up to 4** tool calls per turn |
| Conversation memory | Last 2 turns | Last 6 turns | Older turns rolled into a running summary |
| Max answer tokens | 200 | 400 | 700 |
| Extra abilities | — | Follow-up questions ("and their climb?") | Multi-step plans ("find top 3 defenders, compare their teleop, chart it"), pick-list and strategy reasoning, `calculate` tool for arithmetic |

The rule router still runs first on Standard and Advanced for obvious requests such as "graph 254 auto". It's instant, so there's no reason to spend a model call.

---

## 6. Phase 3 — Qualitative notes summarization (all tiers)

- **Data:**
  - Get qual entries from `GET /api/qual-scouting?includePrescout=true` and field definitions from `GET /api/qual-config`.
  - Text fields are those with `type` of `textarea` or `text`, such as `"Notes"` in `config/defaults/frc2026-qualitative.json`.
  - Rating fields are averaged by code and passed in as facts.
- **Algorithm:**
  - Notes that fit in the tier's context budget go in a single pass. Otherwise, **map-reduce**: summarize chunks, then summarize the summaries.
  - Lite chunks by about 10 notes. Advanced can usually do a whole event's notes for one team in one pass.
- **Output:**
  - 3–5 bullets under **Strengths**, **Weaknesses**, and **Watch for**, plus a one-line overall.
  - Advanced also adds **"Contradictions between scouts"**, for example when one scout says a team is fast and another says slow.
- **Cache (device only):** stored in IndexedDB using `base/idb-cache.js` `getDb()`, keyed on `team|eventKey|tier|promptVersion|hash(entry ids + timestamps)`. A summary regenerates only when the notes or the tier change.
- **Where it appears:**
  - `team.js`: a "Summarize notes" card.
  - `qual-data.js`: a per-team action, plus "Summarize all teams at this event" as a cancellable background queue.
  - `alliance-selection.js`: shows a cached summary only and never generates one.

---

## 7. Phase 4 — Assistant page (`/assistant`)

### 7.1 Wiring
- `static/assistant.html` reuses `chat.html`'s shell (head, `common.js`, sidebar injection) and loads `/vendor/plotly-2.32.0.min.js`, `marked`, DOMPurify, and `js/assistant.js`.
- Add `"assistant" to "assistant.html"` to the `pages` map in `Routes.kt` (~line 4048).
- Add a sidebar link in `static/base.html` next to `#nav-chat` (~line 136): `id="nav-assistant"`, `data-i18n="nav.assistant"`. In `js/layout/navigation.js`, hide it unless `me.localAiEnabled`.
- Add i18n keys to `static/i18n/{en,es,he,tr}.json`. Check the RTL layout for `he`.
- If a disabled user opens `/assistant` directly, show "Assistant is off — enable it in Settings" with a link.

### 7.2 Page
- **Header:** a model switcher showing the current tier and its state, plus an event selector that defaults to the current event from `/api/settings`.
- **Transcript:** streaming bubbles, with inline **chart cards** (Plotly) and **table cards**, each with a "Download CSV" button.
- **Advanced tier only:** a collapsible "Steps" panel per answer that lists the tool calls made, so you can see how it got its answer.
- **Controls:** a Stop button, prompt chips, and a "Clear conversation" button. History stays in IndexedDB on the device only.

### 7.3 Data context (`ai/data-context.js`)
- Load these once per event, through `Obsidianscout.request` so the existing ETag/IDB cache and offline mode apply: `/api/settings`, `/api/config`, `/api/teams?eventKey=`, `/api/matches?eventKey=`, `/api/scouting?includePrescout=true`, `/api/pit-scouting?includePrescout=true`, `/api/qual-scouting?includePrescout=true`, and `/api/stats/history?eventKey=`.
- **Extract the per-team aggregation** that `graphs.js` and `team.js` already do into `js/utilities/scouting-stats.js`, and use it from all three places, so the assistant's numbers match the rest of the site.
- Build a **metric catalog** (`{ id, label, aliases[], kind }`) from the game config fields.
- **Security:** the assistant only calls endpoints the user's session already reaches, and everything stays on the device.

### 7.4 Tools (read-only; executed by JS, never by the model)
| Tool | Args | Output |
|---|---|---|
| `team_overview` | team | key averages, matches scouted, pit highlights, cached notes summary |
| `top_teams` | metric, n, sort | ranked table + bar chart |
| `compare_teams` | teams[2–6], metrics[]? | table + grouped bar or radar chart |
| `metric_trend` | teams[], metric | line chart by match number |
| `scatter` | metricX, metricY | all event teams, with the user's own team highlighted |
| `match_preview` | match number or "next" | both alliances' summed averages plus the existing predictor (`/api/.../predict`) |
| `summarize_notes` | team | calls the Phase 3 summarizer |
| `filter_teams` *(Std/Adv)* | conditions[] (`metric op value`) | teams that match the conditions |
| `calculate` *(Adv)* | expression over named values | a safe arithmetic evaluator using a small parser, **not** `eval` |
| `open_page` | team/page | renders a button. It never navigates on its own. |

### 7.5 Pipeline
1. **Rule router:** team numbers are matched against the event team list, metrics by fuzzy matching against the catalog, and intent by keywords. If it's confident, it runs the tool directly.
2. **Model routing, if needed:**
   - Lite: `generateJson` with a fixed schema. If validation fails, it uses `answer_general` with a compact event digest.
   - Standard: native `<tool_call>`, one call.
   - Advanced: a loop of model → `<tool_call>` → tool result appended as a `tool` message → model, for up to 4 iterations, then a final answer.
3. Charts and tables render **as soon as the tool returns**.
4. **Final prose:** the system prompt says "Use only the facts in tool results. If something isn't there, say you don't know. Never state a number that isn't in a tool result."
5. **Guardrail:** after generation, check that every number in the answer appears in that turn's tool results. If one doesn't, add an "unverified number" marker.

### 7.6 Evaluation harness
`scratch/assistant-eval.html` runs about 60 canned questions, each with expected tools and arguments: 30 single-step and 30 multi-step or follow-up. It reports per-tier tool accuracy, numeric-guardrail violations, and latency. Release targets:

| Metric | Lite | Standard | Advanced |
|---|---|---|---|
| Single-step tool accuracy | ≥ 90% (mostly from the rule router) | ≥ 90% | ≥ 95% |
| Multi-step tool accuracy | — | — | ≥ 75% |

---

## 8. Phase 5 — Small tasks (use the active tier)
- **Note tagging:** labels such as defense, fast cycles, broke down, penalties, strong driver. Shown as filter chips on qual data.
- **Pick-list blurb:** in alliance selection, a one-sentence pick rationale from `team_overview` facts.
- **Tidy note:** an optional suggestion in `qual-scout.js` that fixes spelling and expands shorthand. The user accepts or rejects it; it never auto-replaces text.
- **Match strategy brief** on `match-planning`, built from `match_preview` facts. On Advanced, it also suggests defensive assignments based on the opponents' strongest metrics.

---

## 9. Phase 6 — Hardening and release
- **Kotlin tests:** the setting round trip and the default being off; snapshot/restore keeping the value; `/models` returns 401 without a session, sends no gzip, supports Range (206), and sends immutable cache headers; and the manifest only lists installed tiers.
- **Manual matrix:** the §2 device matrix × {first download, interrupted download and resume, warm load, offline after download, switching tiers, disabling with delete}.
- **Version-bump test:** bump the version, reload, and confirm **no model re-downloads**. This verifies the `sw.js` fix.
- **Failure handling:**
  - Quota exceeded: show the size needed and suggest deleting another tier.
  - GPU device lost: retry once, then show a toast.
  - Manifest missing a tier: hide that tier.
  - Load failure: disable AI features for the session.
- **Docs:** a `static/docs.html` section covering what runs locally, per-tier sizes and requirements, how to enable or disable it, how to delete models, and the server operator's `fetch-ai-models` instructions and disk needs. Update `MOBILE_API_SERVER_DOCS.md` with the new `localAiEnabled` field.

---

## 10. Sequencing
| Phase | Work | Estimate |
|---|---|---|
| 0 | Spike across all three tiers + device matrix + license check | 2–3 days |
| 1 | Setting column (default off) end to end; `/models` route + PartialContent + manifest; fetch script; CSP, compression, and SW fixes; vendoring | 3–4 days |
| 2 | Registry, capabilities, facade, worker, both engine adapters, tool protocol, settings model picker | 4–5 days |
| 3 | Notes summarization | 2 days |
| 4 | Assistant page, data context, tools, per-tier pipelines, guardrail, eval | 7–9 days |
| 5 | Small tasks | 2 days |
| 6 | Hardening, i18n, docs | 3 days |

## 11. Files

**New:**
- `scripts/fetch-ai-models.{ps1,sh}`
- `static/assistant.html`, `static/js/assistant.js`
- `static/js/ai/{registry,capabilities,local-llm,llm-worker,tool-protocol,prompts,ai-ui,json-guard,data-context,router,tools,charts}.js`
- `static/js/ai/engines/{transformers-engine,webllm-engine}.js`
- `static/js/utilities/scouting-stats.js`
- `static/vendor/{transformers/*,web-llm/*,purify.min.js}`
- `data/models/` (runtime only; gitignored)

**Changed:**
- `build.gradle.kts`: partial-content dependency.
- `App.kt`: CSP, compression exclusion, PartialContent and AutoHeadResponse.
- `Routes.kt`: pages map, authenticated `/models` route, `me` payloads, `PUT /user`.
- Data layer: `Models.kt`, `Tables.kt`, `DatabaseFactory.kt`, `AuthService.kt`, `Session.kt`, `QuorumFallbackStore.kt`, `SnapshotService.kt`.
- `config/app-config.json`: `localAi.modelDir`.
- `.gitignore`: `data/models/`.
- Static pages: `static/sw.js`, `static/base.html`, `static/config.html`, `static/docs.html`.
- Page scripts: `js/settings.js`, `js/layout/navigation.js`, `js/team.js`, `js/qual-data.js`, `js/graphs.js`, `js/alliance-selection.js`, `js/qual-scout.js`, `js/match-planning.js`.
- `i18n/*.json`.
