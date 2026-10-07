# Local AI Assistant

ObsidianScout can run small AI language models (Qwen2.5) **entirely inside your browser**. Your questions, notes and scouting data are never sent to an AI service: the server only hands your browser the model files, and everything else happens on your device.

The assistant is **off by default** for every user.

## What it does

- **Scouting Assistant page** (`/assistant`): ask questions such as "Top 5 teams by auto", "Compare 254 and 1678", "Preview match 35" or "Who should we pick?". Tables and charts are computed directly from your scouting data. The written answer comes from the model, and any number in it that could not be found in your data is underlined so you can double-check it.
- **Notes summaries**: on a team's profile page and on the Qualitative Data page, a "Summarize notes" button groups what scouts wrote into strengths and weaknesses.
- **Tidy note**: while qualitative scouting, a "Tidy note" button suggests a cleaned-up version of a note (spelling, shorthand). You choose whether to use it.

The assistant can only see data your account can already see. It can't change anything.

## Turning it on

1. Open **Settings → Personal** and set **Local AI Assistant** to **Enabled**.
2. Under **AI models on this device**, pick a model and press **Download**. The download happens once per device; use Wi-Fi, ideally before the event.
3. The **Assistant** link appears in the sidebar.

Turning the setting off hides every AI feature. It also offers to delete the downloaded models from the device.

## Models

| Model | Size | Runs on | Best for |
|---|---|---|---|
| **Lite** (Qwen2.5 0.5B) | ~300 MB (GPU) or ~520 MB (CPU) | Almost any device; no GPU needed (slower) | Note summaries and quick lookups. Answers are built directly from your data. |
| **Standard** (Qwen2.5 1.5B) | ~880 MB | Laptops and recent phones with WebGPU | Better written answers and question understanding |
| **Gemma 4 E2B** (Gemma 4 E2B, on-device QAT) | ~2.5 GB | Laptops with WebGPU; recent high-end phones (slow) | Stronger reasoning and tool use than the Qwen models of similar speed |
| **Advanced** (Qwen2.5 3B) | ~1.75 GB | Laptops and desktops with a capable GPU | Multi-step questions and strategy (picks, match plans) |
| **Gemma 4 E4B** (Gemma 4 E4B, on-device QAT) | ~3.5 GB | Laptops and desktops with a capable GPU and 8 GB+ memory | The most capable model: multi-step questions, strategy and artifacts |

The model choice is saved **per device**, so your phone can use Lite while your laptop uses Advanced. Models your device can't run are greyed out with the reason.

**Browser support:** every model except Lite needs WebGPU with 16-bit shader support (recent Chrome or Edge on desktop or Android, Safari 26+). Lite also works without WebGPU, on the CPU. The Gemma 4 models run through Transformers.js and are slower to write answers than the Qwen models, but reason better.

**Troubleshooting:** if answers are garbled or the page crashes, tick "don't use the GPU" in the model panel to force the CPU path, or delete and re-download the model.

## For server administrators

Models are installed on the server once, and then every user's browser downloads them from your server (`/models/...`). Nothing is fetched from third-party sites at runtime.

- **From the website:** Storage Manager (site admin) → **Local AI models** → **Install**. Progress is shown live; interrupted downloads resume, and every file is checked against its SHA-256 hash.
- **From the command line:** `obsidianscout-server --install-ai-models lite,standard,advanced` (or `java -jar obsidianscout-server.jar --install-ai-models lite`).
- Files are stored in `data/models/`. Set `OBSIDIANSCOUT_MODELS_DIR` (or `-Dobsidianscout.modelsDir=`) to use another folder. All five models need about 9.5 GB of disk; installs are refused if they would leave less than `local_ai.min_free_disk_mb` (default 2048 MB) free.
- **Automatic installs:** nothing is downloaded at startup unless you list tiers in `config/app-config.json`, e.g. `"local_ai": { "auto_install_tiers": ["lite"] }`. Uninstalling a tier in Storage Manager is permanent unless it is listed there.
- **Automatic cleanup:** on every start the server deletes model files that the current version no longer uses (removed models, older versions, old runtimes). Browsers delete their cached copies of retired models the next time they open the site.
- In a cluster, install the models on every node that serves users. Model requests are always served by the local node.

**Licenses:** Qwen2.5 0.5B and 1.5B and Gemma 4 E2B/E4B are Apache-2.0. Qwen2.5 3B is under the Qwen Research License (non-commercial use).

## For developers

`node --test src/test/js/ai-assistant.test.mjs` (also run by `gradlew check`) loads a synthetic event and checks that every routed tool and suggestion chip exists, that the routing evaluation set in `src/test/js/assistant-eval.json` (English, Spanish, Turkish, Hebrew) routes correctly, that every tool runs, and the number guardrail. Add a case there whenever you add a tool or a routing rule.
