# Local AI Assistant

ObsidianScout can run small AI language models (Llama 3.2 and Gemma 4) **entirely inside your browser**. Your questions, notes and scouting data are never sent to an AI service: the server only hands your browser the model files, and everything else happens on your device.

The assistant is **off by default** for every user.

> **Experimental, not for competition use.** The Local AI Assistant started as a fun experiment, not a core feature. Small on-device models can misread questions or get things wrong, and running them is heavy on battery and storage. We recommend not relying on it at events: use the regular data, analytics and strategy pages for scouting and picking decisions.

## What it does

- **Scouting Assistant page** (`/assistant`): ask questions such as "Top 5 teams by auto", "Compare 254 and 1678", "Preview match 35" or "Who should we pick?". Tables and charts are computed directly from your scouting data. The written answer comes from the model, and any number in it that could not be found in your data is underlined so you can double-check it.
  It can also answer "Who is the most consistent in teleop?", "Who has improved over their last 3 matches?", "Our upcoming matches", "Has 254 played against 1678?", "How strong would 254, 1678 and 118 be together?", "What is the event average for endgame?", "What are 4414's strengths and weaknesses?", "Which teams were noted for defense?", "Which teams have swerve?" and "Which teams still need scouting?". The model is told exactly which tables and charts are shown above its answer, so it comments on them instead of rebuilding them.
- **Any table or chart you ask for**: name the teams, columns (any metric, max / min / std dev, matches scouted, official rank, or arithmetic such as "EPA - xP"), filters ("EPA over 60"), sorting and how many rows, as a table or as a bar, stacked, line, scatter, radar, box or pie chart. Then change it in plain words: "remove OPR from that table", "only the top 10", "sort by xP", "add auto", "just 254, 1678 and 118", "show it as a pie chart". Tables and charts are always built by code from your data; the model only decides what to show and comments on it.
- **Notes summaries**: on a team's profile page and on the Qualitative Data page, a "Summarize notes" button groups what scouts wrote into strengths and weaknesses.
- **Tidy note**: while qualitative scouting, a "Tidy note" button suggests a cleaned-up version of a note (spelling, shorthand). You choose whether to use it.

The assistant can only see data your account can already see. It can't change anything.

![Scouting Assistant](/assets/images/docs/assistant.webp)

## Turning it on

1. Open **Settings → Personal** and set **Local AI Assistant** to **Enabled**.
2. Under **AI models on this device**, pick a model and press **Download**. The download happens once per device; use Wi-Fi, ideally before the event.
3. The **Assistant** link appears in the sidebar.

Turning the setting off hides every AI feature. It also offers to delete the downloaded models from the device.

## Models

| Model | Size | Runs on | Best for |
|---|---|---|---|
| **Lite** (Llama 3.2 1B) | ~680 MB (GPU) or ~1.6 GB (CPU) | Almost any device; no GPU needed (slower) | Note summaries and quick lookups. Answers are built directly from your data. |
| **Standard** (Gemma 4 E2B, 2-bit on-device QAT) | ~2.5 GB | Laptops with WebGPU; recent high-end phones (slow) | Better question understanding and tool use; multi-part questions such as "compare 254 and 1678 in match 10 on auto" |
| **Standard 4-bit** (Gemma 4 E2B, 4-bit) | ~3.4 GB | Laptops and desktops with WebGPU and 8 GB+ memory (not phones) | Same model with more precise weights: more accurate tool calls and multi-part questions, a bit slower |
| **Advanced** (Gemma 4 E4B, 2-bit on-device QAT) | ~3.5 GB | Laptops and desktops with a capable GPU and 8 GB+ memory | Multi-step questions, strategy and artifacts |
| **Advanced 4-bit** (Gemma 4 E4B, 4-bit) | ~5.2 GB | Desktops or gaming laptops with a strong GPU (6 GB+ video memory) | The most accurate option; use it when the device can handle it |

The model choice is saved **per device**, so your phone can use Lite while your laptop uses Advanced. Models your device can't run are greyed out with the reason. The 2-bit builds are for phones and lighter laptops; pick the 4-bit build of the same model when your device has the memory for it.

**Browser support:** every model except Lite needs WebGPU with 16-bit shader support (recent Chrome or Edge on desktop or Android, Safari 26+). Lite also works without WebGPU, on the CPU. The Gemma 4 models run through Transformers.js: slower to write than Lite, but they understand questions and pick tools far better.

**Troubleshooting:** if answers are garbled or the page crashes, tick "don't use the GPU" in the model panel to force the CPU path, or delete and re-download the model.

## For server administrators

Models are installed on the server once, and then every user's browser downloads them from your server (`/models/...`). Nothing is fetched from third-party sites at runtime.

- **From the website:** Storage Manager (site admin) → **Local AI models** → **Install**. Progress is shown live; interrupted downloads resume, and every file is checked against its SHA-256 hash.
- **From the command line:** `obsidianscout-server --install-ai-models lite,gemma4e2b,gemma4e2bq4,gemma4e4b,gemma4e4bq4` (or `java -jar obsidianscout-server.jar --install-ai-models lite`).
- Files are stored in `data/models/`. Set `OBSIDIANSCOUT_MODELS_DIR` (or `-Dobsidianscout.modelsDir=`) to use another folder. All five models need about 17 GB of disk; installs are refused if they would leave less than `local_ai.min_free_disk_mb` (default 2048 MB) free.
- **Automatic installs:** nothing is downloaded at startup unless you list tiers in `config/app-config.json`, e.g. `"local_ai": { "auto_install_tiers": ["lite"] }`. Uninstalling a tier in Storage Manager is permanent unless it is listed there.
- **Automatic cleanup:** on every start the server deletes model files that the current version no longer uses (removed models, older versions, old runtimes). Browsers delete their cached copies of retired models the next time they open the site.
- In a cluster, install the models on every node that serves users. Model requests are always served by the local node.

**Licenses:** Gemma 4 E2B/E4B are Apache-2.0. Llama 3.2 1B is under the Llama 3.2 Community License (Built with Llama).

## For developers

`node --test src/test/js/ai-assistant.test.mjs` (also run by `gradlew check`) loads a synthetic event and checks that every routed tool and suggestion chip exists, that the routing evaluation set in `src/test/js/assistant-eval.json` (English, Spanish, Turkish, Hebrew) routes correctly, that every tool runs, and the number guardrail. Add a case there whenever you add a tool or a routing rule.

**How tool calling works.** Lite routes common questions with the rule router (`assistant/router.js`). On the Gemma tiers the model picks the tools:

- The tools are passed to Gemma 4's chat template as native function declarations. Each tool has its own schema (`assistant/tool-schemas.js`): types, enums and required fields. Calls and results go back and forth as real `tool_call` / `tool_response` turns, and the model can use its built-in thinking mode. `assistant/gemma-format.js` parses this format.
- Transformers.js can't constrain decoding to a grammar, so every call is checked against its schema and the event (teams that are at the event, metrics that exist, matches in the schedule) before it runs. An invalid call isn't run: the model gets one repair turn that says exactly what was wrong.
- The router prompt names the teams, match and metrics found in the question, resolved in code. The tool shortlist also matches Spanish, Turkish and Hebrew questions.
- `make_table` / `make_chart` take `match`, `matches` and `last`, so one call can answer "compare 254 and 1678 in match 10 using auto points".
- Prompt budgets are in tokens (`contextTokens` in `TIER_PROFILES`, `local-ai.js`), and routing is capped at a few steps per question.

**Model routing eval.** To score the loaded model on canned questions, open the Assistant page with a model loaded and run this in the browser console:

    await ObsidianscoutAssistantEval.run()

It reports tool accuracy, argument accuracy, rejected calls and latency per question (`assistant/eval.js`). Run it on each tier after changing prompts, schemas or tools.
