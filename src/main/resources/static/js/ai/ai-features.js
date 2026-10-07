/**
 * Local AI page features - ObsidianScout
 *  - Qualitative notes summaries (team profile + qualitative data pages), cached per team on this device
 *  - "Tidy note" suggestions while qualitative scouting
 * Everything is inert unless the signed-in user enabled the Local AI Assistant in Personal Settings.
 */

import AI from "./local-ai.js";
import UI from "./ai-ui.js";
import Data from "./ai-data.js";

const PROMPT_VERSION = 4;
const LANGUAGE_NAMES = { en: "English", es: "Spanish", he: "Hebrew", tr: "Turkish" };

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function") ? window.Obsidianscout.t(key, fallback) : fallback;
}

function fmt(template, values) {
    return String(template).replace(/\{(\w+)\}/g, (_, k) => (values[k] !== undefined ? values[k] : `{${k}}`));
}

function currentLanguage() {
    let lang = "en";
    try { lang = localStorage.getItem("obsidianscout:lang") || "en"; } catch (_) { /* ignore */ }
    return LANGUAGE_NAMES[lang] || "English";
}

function toast(message, tone = "info") {
    if (window.Obsidianscout && typeof window.Obsidianscout.showToast === "function") window.Obsidianscout.showToast(message, tone);
}

// ------------------------------------------------------------------ notes summaries

function summaryKey(teamNumber, eventKey) {
    return `${String(eventKey || "").toLowerCase()}|${teamNumber}`;
}

export async function getCachedSummary(teamNumber, eventKey) {
    return AI.idbGet("summaries", summaryKey(teamNumber, eventKey));
}

function ratingFacts(ctx, teamNumber) {
    const s = ctx.stats.get(Number(teamNumber));
    if (!s) return "";
    return ctx.metrics
        .filter((m) => m.kind === "qual" && s.metrics[m.id])
        .map((m) => `${m.label}: ${Data.round(s.metrics[m.id].avg, 1)} (from ${s.metrics[m.id].n} ratings)`)
        .join("; ");
}

// ------------------------------------------------------------------ note classification
// Notes are split into clauses and each clause is labelled S (strength), W (weakness) or N (neutral):
// keyword rules first, the model only for clauses the rules can't decide.

const NEGATED_PROBLEM = /\b(never|no|not|didn'?t|did not|without|zero|0)\s+(\w+\s+)?(tip\w*|broke\w*|jam\w*|lost|penalt\w*|miss\w*|fail\w*|died|stuck|foul\w*|issues?|problems?|mistakes?|drops?)\b/gi;
const NEGATIVE = /\b(tip(ped|s|ping)?|broke|broken|jam(med|s|ming)?|lost|penalt\w*|miss(ed|es|ing)?|slow|weak|died|dead|disabled|stuck|fail\w*|bad|poor|struggl\w*|unreliable|disconnect\w*|comms|fell|beached|pinned|foul\w*|card|inconsistent|sloppy)\b/i;
const POSITIVE = /\b(fast|great|good|strong|consistent|reliable|perfect|quick(ly)?|smooth|excellent|accurate|solid|efficient|clutch|amazing|nice|dominant|best|well)\b/i;

function heuristicLabel(text) {
    let rest = text;
    let pos = false;
    if (NEGATED_PROBLEM.test(rest)) {
        pos = true;
        rest = rest.replace(NEGATED_PROBLEM, " ");
    }
    NEGATED_PROBLEM.lastIndex = 0;
    const neg = NEGATIVE.test(rest);
    pos = pos || POSITIVE.test(rest);
    if (neg && !pos) return "W";
    if (pos && !neg) return "S";
    return null;
}

/** Splits notes into short observations ("fast cycles, but tipped" -> 2 clauses), merging duplicates. */
function toClauses(notes) {
    const map = new Map();
    notes.forEach((note) => {
        note.text.split(/[,;]|\.\s|\bbut\b|\bhowever\b|\balso\b/i)
            .map((c) => c.trim().replace(/^(and|then)\s+/i, "").replace(/\.$/, ""))
            .filter((c) => c.length >= 3)
            .forEach((clause) => {
                const key = clause.toLowerCase();
                const existing = map.get(key);
                if (existing) {
                    existing.count++;
                    if (note.match && !existing.matches.includes(note.match)) existing.matches.push(note.match);
                } else {
                    map.set(key, { text: clause, count: 1, matches: note.match ? [note.match] : [] });
                }
            });
    });
    return Array.from(map.values());
}

async function labelClauses(clauses, signal) {
    const labels = clauses.map((c) => heuristicLabel(c.text));
    const unknown = clauses.map((c, i) => ({ c, i })).filter((x) => !labels[x.i]);
    for (let start = 0; start < unknown.length; start += 12) {
        const batch = unknown.slice(start, start + 12);
        const reply = await AI.generate([
            { role: "system", content: "Label each robotics scouting observation: S = strength (the robot or team did something well), W = weakness (a problem or failure), N = neutral. Reply only in the form 1:S 2:W 3:N" },
            { role: "user", content: batch.map((x, j) => `${j + 1}. ${x.c.text}`).join("\n") }
        ], { maxTokens: 8 * batch.length, temperature: 0, signal });
        const parsed = {};
        (reply.match(/(\d+)\s*[:.)-]\s*([SWN])/gi) || []).forEach((m) => {
            const [, num, lab] = m.match(/(\d+)\s*[:.)-]\s*([SWN])/i);
            parsed[Number(num)] = lab.toUpperCase();
        });
        batch.forEach((x, j) => { labels[x.i] = parsed[j + 1] || "N"; });
    }
    return labels;
}

function clauseLine(c) {
    const tags = [c.count > 1 ? `x${c.count}` : "", c.matches.slice(0, 4).join(", ")].filter(Boolean).join(" · ");
    return `- ${c.text}${tags ? ` (${tags})` : ""}`;
}

function sorted(clauses, labels, lab, limit) {
    return clauses.filter((_, i) => labels[i] === lab).sort((a, b) => b.count - a.count).slice(0, limit);
}

/** Lite: the summary is assembled from the scouts' own words (the 0.5B model invents details when writing prose). */
function extractiveSummary(clauses, labels, notesCount, ratings) {
    const none = `- ${t("ai.summary.nothing_noted", "Nothing noted")}`;
    const strengths = sorted(clauses, labels, "S", 6).map(clauseLine);
    const weaknesses = sorted(clauses, labels, "W", 6).map(clauseLine);
    const other = sorted(clauses, labels, "N", 4).map(clauseLine);
    const parts = [
        `**${t("ai.summary.strengths", "Strengths")}**`, ...(strengths.length ? strengths : [none]), "",
        `**${t("ai.summary.weaknesses", "Weaknesses")}**`, ...(weaknesses.length ? weaknesses : [none])
    ];
    if (other.length) parts.push("", `**${t("ai.summary.other", "Other notes")}**`, ...other);
    const count = (lab) => clauses.filter((_, i) => labels[i] === lab).reduce((s, c) => s + c.count, 0);
    parts.push("", `**${t("ai.summary.overall", "Overall")}:** ` + fmt(t("ai.summary.counts", "{pos} positive and {neg} negative observations from {n} notes."), {
        pos: count("S"), neg: count("W"), n: notesCount
    }) + (ratings ? ` ${ratings}.` : ""));
    return parts.join("\n");
}

/** Standard/Advanced: the model rewrites pre-sorted observations; it never decides what counts as a strength. */
function rewritePrompt(tierId, label, ratings, clauses, labels, budgetChars) {
    const list = (lab, limit) => {
        const items = sorted(clauses, labels, lab, limit).map(clauseLine);
        return items.length ? items.join("\n") : "(none)";
    };
    const system = [
        "You are a scouting analyst for a FIRST Robotics team. Rewrite the sorted scout observations below into a short summary.",
        "Use ONLY these observations. The Strengths section may only use STRENGTH observations, Weaknesses only WEAKNESS observations.",
        "If a list is (none), write exactly '- Nothing noted' for that section. Never add abilities, numbers or events that are not listed.",
        "Merge duplicates (xN means N scouts reported it).",
        "Output Markdown: **Strengths**, **Weaknesses**, **Watch for** (risks to plan around, taken only from weaknesses; '- Nothing noted' if none),",
        tierId === "advanced" ? "**Conflicting reports** (only if a strength and a weakness contradict each other; otherwise omit the section)," : "",
        "then one line starting with **Overall:**.",
        `Write in ${currentLanguage()}.`
    ].filter(Boolean).join(" ");
    let user = [
        `Team ${label}.`,
        ratings ? `Average qualitative ratings: ${ratings}.` : "",
        "STRENGTH observations:", list("S", 14),
        "WEAKNESS observations:", list("W", 14),
        "OTHER observations:", list("N", 8)
    ].filter(Boolean).join("\n");
    if (user.length > budgetChars) user = user.slice(0, budgetChars);
    return [{ role: "system", content: system }, { role: "user", content: user }];
}

/**
 * Summarizes a team's scout notes. Returns { text, notesCount, tier, createdAt, cached }.
 * Cached per team/event on this device; regenerated only when the notes, model tier or language change.
 */
export async function summarizeTeam({ teamNumber, eventKey, onToken, signal, force = false, ctx = null }) {
    ctx = ctx || await Data.loadContext({ eventKey });
    const notes = Data.teamNotes(ctx, teamNumber);
    if (!notes.length) {
        const err = new Error(t("ai.summary.no_notes", "No written scout notes for this team yet."));
        err.noNotes = true;
        throw err;
    }
    const { tier } = await UI.ensureModelReady();
    const hash = AI.hashString([PROMPT_VERSION, tier.id, currentLanguage(), ...notes.map((n) => n.id + n.text)].join("\u0001"));
    const key = summaryKey(teamNumber, ctx.eventKey);
    const cached = await AI.idbGet("summaries", key);
    if (cached && cached.hash === hash && !force) return { ...cached, cached: true };

    const profile = AI.tierProfile(tier);
    const label = Data.teamLabel(ctx, teamNumber);
    const ratings = ratingFacts(ctx, teamNumber);

    if (onToken) onToken("", t("ai.summary.sorting", "Sorting notes..."), true);
    const clauses = toClauses(notes);
    const labels = await labelClauses(clauses, signal);

    let text;
    if (profile.extractiveSummaries) {
        text = extractiveSummary(clauses, labels, notes.length, ratings);
    } else {
        text = await AI.generate(rewritePrompt(tier.id, label, ratings, clauses, labels, profile.contextChars - 1500), {
            maxTokens: Math.min(420, profile.maxAnswerTokens), temperature: 0, signal,
            onToken: onToken ? (d, full) => onToken(d, full, false) : undefined
        });
    }

    const record = { text, hash, tier: tier.id, notesCount: notes.length, createdAt: new Date().toISOString() };
    await AI.idbSet("summaries", key, record);
    return { ...record, cached: false };
}

/** Renders an "AI summary of scout notes" card into container. */
export async function mountSummaryCard(container, { teamNumber, eventKey }) {
    if (!container || !teamNumber) return null;
    const enabled = await AI.isEnabled().catch(() => false);
    if (!enabled) return null;
    const tiers = await AI.getInstalledTiers().catch(() => []);
    if (!tiers.length) return null;

    const existing = container.querySelector(`.ai-summary-card[data-team="${teamNumber}"]`);
    if (existing) return existing;

    container.innerHTML = "";
    const card = document.createElement("div");
    card.className = "ai-summary-card";
    card.dataset.team = String(teamNumber);
    card.innerHTML = `
        <div class="ai-summary-head">
            <strong><i class="fa-solid fa-wand-magic-sparkles"></i> ${t("ai.summary.title", "AI summary of scout notes")}</strong>
            <div class="ai-summary-actions"></div>
        </div>
        <div class="ai-summary-body ai-markdown"></div>
        <div class="ai-summary-foot"></div>`;
    const body = card.querySelector(".ai-summary-body");
    const actions = card.querySelector(".ai-summary-actions");
    const foot = card.querySelector(".ai-summary-foot");
    let controller = null;

    const runBtn = document.createElement("button");
    runBtn.type = "button";
    runBtn.className = "btn btn-sm";
    const stopBtn = document.createElement("button");
    stopBtn.type = "button";
    stopBtn.className = "btn ghost btn-sm";
    stopBtn.textContent = t("ai.stop", "Stop");
    stopBtn.hidden = true;
    actions.append(runBtn, stopBtn);

    function showResult(result) {
        body.innerHTML = UI.renderMarkdown(result.text);
        foot.innerHTML = "";
        foot.appendChild(UI.aiBadge());
        const meta = document.createElement("span");
        meta.className = "ai-muted";
        meta.textContent = " " + fmt(t("ai.summary.meta", "From {n} notes · {date}"), {
            n: result.notesCount, date: new Date(result.createdAt).toLocaleString()
        });
        foot.appendChild(meta);
        runBtn.textContent = t("ai.summary.regenerate", "Regenerate");
    }

    async function run(force) {
        controller = new AbortController();
        runBtn.disabled = true;
        stopBtn.hidden = false;
        body.innerHTML = `<p class="ai-muted">${t("ai.thinking", "Thinking...")}</p>`;
        await UI.ensureMarkdownLibs();
        try {
            const result = await summarizeTeam({
                teamNumber, eventKey, force, signal: controller.signal,
                onToken: (_d, full, status) => {
                    body.innerHTML = status ? `<p class="ai-muted">${full}</p>` : UI.renderMarkdown(full);
                }
            });
            showResult(result);
        } catch (err) {
            if (err.name === "AbortError") {
                body.innerHTML = `<p class="ai-muted">${t("ai.stopped", "Stopped.")}</p>`;
            } else if (err.userCancelled) {
                body.innerHTML = "";
            } else {
                body.innerHTML = `<p class="notice ai-warn"></p>`;
                body.firstChild.textContent = err.message;
            }
        } finally {
            runBtn.disabled = false;
            stopBtn.hidden = true;
            controller = null;
        }
    }

    runBtn.textContent = t("ai.summary.run", "Summarize notes");
    runBtn.addEventListener("click", () => run(runBtn.textContent === t("ai.summary.regenerate", "Regenerate")));
    stopBtn.addEventListener("click", () => controller && controller.abort());

    UI.ensureMarkdownLibs().then(async () => {
        const cached = await getCachedSummary(teamNumber, eventKey);
        if (cached) showResult(cached);
    });
    container.appendChild(card);
    return card;
}

// ------------------------------------------------------------------ "Tidy note" for scouts

async function tidyText(text, signal) {
    await UI.ensureModelReady();
    return AI.generate([
        { role: "system", content: "You clean up robotics scouting notes. Fix spelling and grammar and expand obvious shorthand (e.g. 'auto', 'def' -> 'defense'). Keep every fact, keep it short, do not add new information, and do not make it sound more positive or negative than the original. Reply with the cleaned note only." },
        { role: "user", content: text }
    ], { maxTokens: Math.min(300, Math.ceil(text.length / 2) + 60), temperature: 0.1, signal });
}

function attachTidy(textarea) {
    if (textarea.dataset.aiTidy) return;
    textarea.dataset.aiTidy = "1";
    const bar = document.createElement("div");
    bar.className = "ai-tidy-bar";
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn ghost btn-sm";
    btn.innerHTML = `<i class="fa-solid fa-wand-magic-sparkles"></i> ${t("ai.tidy.button", "Tidy note")}`;
    bar.appendChild(btn);
    textarea.insertAdjacentElement("afterend", bar);

    btn.addEventListener("click", async () => {
        const original = textarea.value.trim();
        if (original.length < 4) {
            toast(t("ai.tidy.too_short", "Write a note first."), "info");
            return;
        }
        btn.disabled = true;
        const prev = bar.querySelector(".ai-tidy-suggestion");
        if (prev) prev.remove();
        try {
            const cleaned = (await tidyText(original)).replace(/^["']|["']$/g, "").trim();
            if (!cleaned || cleaned === original) {
                toast(t("ai.tidy.no_change", "The note already looks good."), "info");
                return;
            }
            const box = document.createElement("div");
            box.className = "ai-tidy-suggestion";
            const p = document.createElement("p");
            p.textContent = cleaned;
            const accept = document.createElement("button");
            accept.type = "button";
            accept.className = "btn btn-sm";
            accept.textContent = t("ai.tidy.accept", "Use this");
            const dismiss = document.createElement("button");
            dismiss.type = "button";
            dismiss.className = "btn ghost btn-sm";
            dismiss.textContent = t("ai.tidy.dismiss", "Keep mine");
            accept.addEventListener("click", () => {
                textarea.value = cleaned;
                textarea.dispatchEvent(new Event("input", { bubbles: true }));
                textarea.dispatchEvent(new Event("change", { bubbles: true }));
                box.remove();
            });
            dismiss.addEventListener("click", () => box.remove());
            box.append(p, accept, dismiss);
            bar.appendChild(box);
        } catch (err) {
            if (!err.userCancelled) toast(err.message, "error");
        } finally {
            btn.disabled = false;
        }
    });
}

// ------------------------------------------------------------------ page hooks

async function hookTeamPage() {
    const params = new URLSearchParams(location.search);
    const teamNumber = parseInt(params.get("teamNumber"), 10);
    if (!teamNumber) return;
    let eventKey = params.get("eventKey");
    if (!eventKey) eventKey = (await Data.loadContext()).eventKey;
    const card = document.getElementById("card-qual-summary");
    if (!card) return;
    const holder = document.createElement("div");
    holder.className = "mt-12";
    const list = document.getElementById("team-qual-list");
    card.insertBefore(holder, list || null);
    const summary = await mountSummaryCard(holder, { teamNumber, eventKey });
    const actions = summary && summary.querySelector(".ai-summary-actions");
    if (actions) actions.prepend(askLink(fmt(t("ai.ask_team_question", "Tell me about team {team}"), { team: teamNumber }), eventKey));
}

function hookQualDataPage() {
    let settingsEvent = null;
    Data.loadContext().then((ctx) => { settingsEvent = ctx.eventKey; check(); }).catch(() => {});
    let scheduled = false;
    // The page re-renders (and even replaces) its panels, so look the elements up fresh on every change.
    const eventKeyNow = () => {
        const eventSelect = document.getElementById("qual-event-filter");
        const value = eventSelect ? eventSelect.value : "";
        return !value || value === "all" ? settingsEvent || "" : value;
    };
    function check() {
        scheduled = false;
        // The page can rebuild its panels after loading, so (re)attach the "Summarize all teams" bar here too.
        const rankTable = document.getElementById("qual-rank-table");
        if (rankTable) mountPrewarmBar(rankTable.closest(".card") || rankTable, eventKeyNow);
        const detail = document.getElementById("qual-team-detail");
        const title = document.getElementById("qual-team-title");
        if (!detail || !title) return;
        const match = (title.textContent || "").match(/\d+/);
        // mountSummaryCard clears its container, so give it a dedicated holder inside the detail panel.
        let holder = detail.querySelector(":scope > .ai-summary-holder");
        if (!match) {
            if (holder) holder.remove();
            return;
        }
        if (holder && holder.dataset.team === match[0]) return;
        if (!holder) {
            holder = document.createElement("div");
            holder.className = "ai-summary-holder";
            detail.appendChild(holder);
        }
        holder.dataset.team = match[0];
        mountSummaryCard(holder, { teamNumber: Number(match[0]), eventKey: eventKeyNow() });
    }
    new MutationObserver(() => {
        if (!scheduled) {
            scheduled = true;
            setTimeout(check, 50);
        }
    }).observe(document.body, { childList: true, subtree: true, characterData: true });
    check();
}

function hookQualScoutPage() {
    const scan = () => document.querySelectorAll("main textarea, .main-content textarea, form textarea").forEach(attachTidy);
    scan();
    new MutationObserver(scan).observe(document.body, { childList: true, subtree: true });
}

function hookAllianceSelectionPage() {
    const modal = document.getElementById("breakdown-modal-backdrop");
    const container = document.getElementById("breakdown-ai-summary-container");
    if (!modal || !container) return;
    // The team profile modal opens long after page load; mount the summary card each time it opens for a team.
    const sync = () => {
        if (!modal.classList.contains("open")) return;
        const title = document.getElementById("breakdown-modal-title");
        const match = (title && title.textContent || "").match(/\d+/);
        if (!match) return;
        const existing = container.querySelector(".ai-summary-card");
        if (existing && existing.dataset.team === match[0]) return;
        container.innerHTML = "";
        const eventFilter = document.getElementById("event-filter");
        mountSummaryCard(container, { teamNumber: Number(match[0]), eventKey: eventFilter ? eventFilter.value : "" });
    };
    new MutationObserver(sync).observe(modal, { attributes: true, attributeFilter: ["class"], subtree: true, childList: true, characterData: true });
    sync();
}

/** Link that opens the Assistant with a question already asked. */
function askLink(question, eventKey) {
    const a = document.createElement("a");
    a.className = "btn ghost btn-sm";
    a.href = `/assistant?q=${encodeURIComponent(question)}${eventKey ? `&event=${encodeURIComponent(eventKey)}` : ""}`;
    a.innerHTML = `<i class="fa-solid fa-robot"></i> `;
    a.appendChild(document.createTextNode(t("ai.ask_assistant", "Ask the assistant")));
    return a;
}

// ------------------------------------------------------------------ "event mode": pre-generate every summary

/**
 * Summarizes every team with notes at an event, one at a time (cached ones are skipped instantly), so summaries
 * are ready before alliance selection. onProgress({ done, total, team }).
 */
export async function summarizeAllTeams({ eventKey, onProgress, signal } = {}) {
    const ctx = await Data.loadContext({ eventKey });
    const teams = Array.from(ctx.stats.keys()).filter((n) => Data.teamNotes(ctx, n).length > 0).sort((a, b) => a - b);
    let done = 0;
    for (const team of teams) {
        if (signal && signal.aborted) throw new DOMException("Aborted", "AbortError");
        if (onProgress) onProgress({ done, total: teams.length, team });
        await summarizeTeam({ teamNumber: team, eventKey: ctx.eventKey, ctx, signal });
        done++;
    }
    if (onProgress) onProgress({ done, total: teams.length, team: null });
    return done;
}

function mountPrewarmBar(anchor, getEventKey) {
    if (!anchor || document.querySelector(".ai-prewarm-bar")) return;
    const bar = document.createElement("div");
    bar.className = "ai-prewarm-bar card soft";
    const label = document.createElement("span");
    label.className = "ai-muted";
    label.textContent = t("ai.prewarm.hint", "Generate AI note summaries for every team now, so they're ready instantly during alliance selection.");
    const run = document.createElement("button");
    run.type = "button";
    run.className = "btn btn-sm";
    run.textContent = t("ai.prewarm.run", "Summarize all teams");
    const stop = document.createElement("button");
    stop.type = "button";
    stop.className = "btn ghost btn-sm";
    stop.textContent = t("ai.stop", "Stop");
    stop.hidden = true;
    bar.append(label, run, stop);
    anchor.parentNode.insertBefore(bar, anchor);
    let controller = null;
    run.addEventListener("click", async () => {
        controller = new AbortController();
        run.disabled = true;
        stop.hidden = false;
        try {
            await UI.ensureModelReady();
            const n = await summarizeAllTeams({
                eventKey: getEventKey(), signal: controller.signal,
                onProgress: ({ done, total }) => { label.textContent = fmt(t("ai.prewarm.progress", "Summarizing teams: {done} of {total}..."), { done, total }); }
            });
            label.textContent = fmt(t("ai.prewarm.done", "{n} team summaries are ready on this device."), { n });
        } catch (err) {
            label.textContent = err.name === "AbortError" ? t("ai.stopped", "Stopped.") : (err.userCancelled ? "" : err.message);
        } finally {
            run.disabled = false;
            stop.hidden = true;
            controller = null;
        }
    });
    stop.addEventListener("click", () => controller && controller.abort());
}

async function init() {
    if (!(await AI.isEnabled())) return;
    const tiers = await AI.getInstalledTiers();
    if (!tiers.length) return;
    const page = document.body && document.body.dataset.page;
    try {
        if (page === "team") await hookTeamPage();
        else if (page === "qual-data") hookQualDataPage();
        else if (page === "qual-scout") hookQualScoutPage();
        else if (page === "alliance-selection") hookAllianceSelectionPage();
    } catch (err) {
        console.warn("[LocalAI] Feature hook failed:", err);
    }
}

// Page hooks only run in a browser page (the module is also imported by Node tests).
if (typeof document !== "undefined") {
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", () => init());
    } else {
        init();
    }
}

const features = { summarizeTeam, summarizeAllTeams, getCachedSummary, mountSummaryCard };
if (typeof window !== "undefined") {
    window.ObsidianscoutAIFeatures = features;
    try {
        window.dispatchEvent(new CustomEvent("obsidianscout:ai-features-ready"));
    } catch (_) { /* ignore */ }
}
export default features;
