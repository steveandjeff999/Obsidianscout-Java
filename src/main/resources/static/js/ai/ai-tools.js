/**
 * Local AI assistant tools + orchestration - ObsidianScout
 *
 * The model never computes statistics. Questions are answered by:
 *   1. a deterministic rule router (handles most common questions instantly), else
 *   2. the model picking read-only tools (JSON; grammar-constrained on WebLLM tiers) from a per-question shortlist,
 *   3. JavaScript executing the tools against ai-data.js (tables + charts are built by code),
 *   4. the model writing a short answer grounded in the tool results, told exactly what is drawn above it,
 *   5. a guardrail flagging any number in the answer that does not appear in the tool results.
 *
 * Split into modules under ./assistant/: router, tools, tools-extra, tool-calls, display, prompts, briefs, verify,
 * docs-guide, shared.
 */

import AI from "./local-ai.js";
import { briefFor } from "./assistant/briefs.js";
import { lastVisualSpec } from "./assistant/builder.js";
import { digestFacts, displayManifest, historyDisplayNote, normalizeChart } from "./assistant/display.js";
import { answerSystemPrompt, displayReminder } from "./assistant/prompts.js";
import { hasIntent, ruleRoute } from "./assistant/router.js";
import { evaluate, t } from "./assistant/shared.js";
import { coerceArgs, eventDigest, parseJsonLoose, parseToolCalls, routerSystemPrompt, stepSchema } from "./assistant/tool-calls.js";
import { TOOLS } from "./assistant/tools.js";
import { cleanGraphRefusalText, unverifiedNumbers, verifyAndCorrectAnswer } from "./assistant/verify.js";

export { briefFor } from "./assistant/briefs.js";
export { editSpec, isEditRequest, lastVisualSpec, resolveColumn, specFromDisplay, specFromQuestion } from "./assistant/builder.js";
export { getDocGuide } from "./assistant/docs-guide.js";
export { displayManifest, digestFacts, historyDisplayNote, normalizeChart, toolTitle } from "./assistant/display.js";
export { ruleRoute } from "./assistant/router.js";
export { evaluate } from "./assistant/shared.js";
export { coerceArgs, parseJsonLoose, parseToolCalls, selectTools } from "./assistant/tool-calls.js";
export { TOOLS } from "./assistant/tools.js";
export { unverifiedNumbers, cleanGraphRefusalText, correctLeaderClaims, correctTableClaims, stripMarkdownTables, verifyAndCorrectAnswer } from "./assistant/verify.js";

// Tools that draw a chart when the question asks for one ("graph ...") even if the model forgot chart:true.
const CHARTABLE = ["top_teams", "epa_data", "opr_data", "xp_data", "compare_teams", "team_overview", "projected_rankings", "match_preview",
    "pick_candidates", "current_rankings", "compare_all_teams", "compare_metrics", "make_table"];
// Same subject = same answer for these, whatever the other arguments say.
const SUBJECT_TOOLS = ["compare_teams", "team_overview", "match_preview", "summarize_notes", "projected_rankings", "team_schedule", "team_percentiles"];

/** Keeps the system prompt and latest question; trims, then drops, older turns; then shortens the DATA section. */
function enforceMessageBudget(messages, maxChars = 5000) {
    let total = messages.reduce((sum, m) => sum + (m.content ? m.content.length : 0), 0);
    if (total <= maxChars) return messages;

    const result = [...messages];
    if (result.length > 2) {
        for (let i = 1; i < result.length - 1; i++) {
            if (result[i].content && result[i].content.length > 200) {
                result[i] = { ...result[i], content: result[i].content.slice(0, 200) + "..." };
            }
        }
        total = result.reduce((sum, m) => sum + (m.content ? m.content.length : 0), 0);
    }
    while (result.length > 2 && total > maxChars) {
        const removed = result.splice(1, 1)[0];
        total -= (removed.content ? removed.content.length : 0);
    }
    if (total > maxChars && result[0] && result[0].content) {
        const excess = total - maxChars;
        const sys = result[0].content;
        const dataIdx = sys.lastIndexOf("DATA:");
        if (dataIdx > 0) {
            const head = sys.slice(0, dataIdx + 5);
            const dataBody = sys.slice(dataIdx + 5);
            const keepLen = Math.max(200, dataBody.length - excess - 50);
            result[0] = { ...result[0], content: `${head}${dataBody.slice(0, keepLen)}\n(more data left out for space; the table on screen is complete)` };
        } else {
            result[0] = { ...result[0], content: sys.slice(0, Math.max(500, sys.length - excess)) + "..." };
        }
    }
    return result;
}

/** A history turn for the model: earlier replies carry a note of the tables/charts they showed. */
function historyMessage(h, maxChars) {
    const note = h.role === "assistant" ? historyDisplayNote(h.display) : "";
    const text = String(h.content || "").slice(0, maxChars);
    return { role: h.role, content: note ? `${note}\n${text}` : text };
}

/**
 * Answers one question. onEvent receives:
 *   {type:'status', text} | {type:'tool', name, args, result} | {type:'token', full} | {type:'done', text, unverified, results}
 * history items are {role, content, display?}; display is what an earlier reply showed (tables, charts, documents).
 */
export async function answerQuestion({ question, history = [], ctx, tier, signal, onEvent = () => {}, route = null }) {
    const profile = AI.tierProfile(tier);
    const allowed = Object.keys(TOOLS).filter((name) => name !== "calculate" || profile.calculate);
    const results = [];

    const wantsChart = hasIntent(question, "chart");
    const runTool = async (name, args) => {
        const tool = TOOLS[name];
        if (!tool) return { facts: { error: `Tool ${name} does not exist.` } };
        const toolArgs = coerceArgs(ctx, args);
        if (wantsChart && toolArgs.chart === undefined && CHARTABLE.includes(name)) toolArgs.chart = true;
        onEvent({ type: "status", text: t("ai.status.tool", "Looking up data ({tool})...").replace("{tool}", name.replace(/_/g, " ")) });
        try {
            const result = await tool.run(ctx, toolArgs, { signal });
            const entry = { tool: name, args: toolArgs, ...result, chart: normalizeChart(result.chart) };
            results.push(entry);
            onEvent({ type: "tool", name, args: toolArgs, result: entry });
            return entry;
        } catch (err) {
            if (err && err.name === "AbortError") throw err;
            const errEntry = { tool: name, args: toolArgs, facts: { error: err.message } };
            results.push(errEntry);
            onEvent({ type: "tool", name, args: toolArgs, result: errEntry });
            return errEntry;
        }
    };

    // A rule-router draft that did not account for every word (or an edit it could not apply) is checked by a
    // capable model, which writes the complete spec; small tiers use the draft (or normal routing).
    const refineRoute = async (draft) => {
        const fallback = () => (draft.unchanged ? ruleRoute(question, ctx, history, { skipEdit: true }) : draft);
        if (profile.toolMode !== "json" || !(profile.maxToolCalls > 0)) return fallback();
        onEvent({ type: "status", text: t("ai.status.planning", "Working out what data is needed...") });
        const kind = (draft.previous || draft).tool === "make_chart" ? "chart" : "table";
        const lines = [`Question: ${question}`];
        if (draft.edit) {
            lines.push(`The user is changing the ${kind} on screen. Its current spec: ${JSON.stringify(draft.previous)}`);
            if (!draft.unchanged) lines.push(`Keyword matching suggests: ${JSON.stringify({ tool: draft.tool, args: draft.args })} (check it against the question).`);
        } else {
            lines.push(`Keyword matching drafted: ${JSON.stringify({ tool: draft.tool, args: draft.args })}`);
            if (draft.leftover && draft.leftover.length) lines.push(`Words it did not understand: ${draft.leftover.join(", ")}.`);
        }
        lines.push("Reply with ONE call whose args are the COMPLETE spec that does exactly what the question asks (fix the draft, or choose a better tool).");
        lines.push("Keep every column, team and filter from the draft that the question asks for. Do not add sorting, limits or columns the question does not ask for.");
        const messages = [
            { role: "system", content: routerSystemPrompt(ctx, allowed, 1, { question, include: ["make_table", "make_chart"] }) },
            { role: "user", content: lines.join("\n") }
        ];
        const raw = await AI.generate(enforceMessageBudget(messages, Math.max(3500, profile.contextChars || 0)), {
            maxTokens: 320, temperature: 0, signal, jsonSchema: stepSchema(allowed)
        });
        const [call] = parseToolCalls(raw, allowed);
        if (!call) return fallback();
        return { tool: call.tool, args: call.args, fallback: draft.unchanged ? null : draft };
    };

    // 1. Deterministic routing first.
    let routed = route || ruleRoute(question, ctx, history);
    if (routed && routed.refine && !route) routed = await refineRoute(routed);
    if (routed) {
        const entry = await runTool(routed.tool, routed.args);
        // The model's spec failed (e.g. a column that does not exist): use the keyword draft instead.
        if (entry && entry.facts && entry.facts.error && routed.fallback) {
            results.splice(results.indexOf(entry), 1);
            await runTool(routed.fallback.tool, routed.fallback.args);
        }
    }

    // 2. Model routing (more steps on bigger tiers), from a shortlist of tools relevant to the question.
    const maxCalls = routed ? (profile.routedFollowUps || 0) : profile.maxToolCalls;
    const isCompact = (profile.contextChars <= 3000);
    if (maxCalls > 0) {
        onEvent({ type: "status", text: t("ai.status.planning", "Working out what data is needed...") });
        const routerMessages = [{ role: "system", content: routerSystemPrompt(ctx, allowed, maxCalls, { compact: isCompact, question }) }];
        history.slice(-2).forEach((h) => routerMessages.push(historyMessage(h, 250)));
        let pending = `Question: ${question}`;
        if (results.length) pending += `\nAlready retrieved (and shown to the user):\n${digestFacts(results, 900)}`;
        const onScreen = lastVisualSpec(history, ctx);
        if (onScreen) pending += `\nOn screen from the previous reply (to change it, call the same tool with the complete new spec): ${JSON.stringify(onScreen)}`;
        let callsLeft = maxCalls;
        for (let step = 0; step < maxCalls && callsLeft > 0; step++) {
            if (signal && signal.aborted) throw new DOMException("Aborted", "AbortError");
            routerMessages.push({ role: "user", content: pending });
            const raw = await AI.generate(enforceMessageBudget(routerMessages, isCompact ? 1600 : Math.max(3500, profile.contextChars || 0)), {
                maxTokens: 280, temperature: 0, signal,
                jsonSchema: profile.toolMode === "json" ? stepSchema(allowed) : null
            });
            routerMessages.push({ role: "assistant", content: raw });
            const toolCalls = parseToolCalls(raw, allowed).slice(0, callsLeft);
            if (!toolCalls.length) break;

            const subject = (a) => JSON.stringify((a && (a.teams || a.team || a.match)) ?? null);
            const stepFeedback = [];
            let executedCount = 0;
            for (const call of toolCalls) {
                const args = coerceArgs(ctx, call.args);
                const dup = results.some((r) => r.tool === call.tool &&
                    (JSON.stringify(r.args) === JSON.stringify(args) || (SUBJECT_TOOLS.includes(r.tool) && subject(r.args) === subject(args))));
                if (dup) continue;
                const entry = await runTool(call.tool, args);
                executedCount++;
                callsLeft--;
                if (entry && entry.facts && entry.facts.error) {
                    stepFeedback.push(`Result of ${call.tool}: ERROR - ${entry.facts.error}`);
                } else {
                    const shown = displayManifest([entry], ctx);
                    stepFeedback.push(`Result of ${call.tool}: SUCCESS${shown ? ` (shown to the user: ${shown.replace(/^\d+\.\s*/gm, "")})` : ""}\n${digestFacts([entry], 1000)}`);
                }
            }
            if (executedCount === 0) break;
            pending = `${stepFeedback.join("\n\n")}\n\nCall another tool only if the question still needs different data; otherwise reply {"action":"answer"}.`;
        }
    }

    // Lite's free-form tool picks are unreliable: drop model-chosen calls that failed, so the answer falls
    // back to the event digest instead of the model improvising around an error.
    if (profile.toolMode === "router") {
        for (let i = results.length - 1; i >= 0; i--) {
            const r = results[i];
            if (r.facts && r.facts.error && !(routed && r.tool === routed.tool)) results.splice(i, 1);
        }
    }

    // Artifacts the model wrote itself get the same number check as answers; flagged numbers are marked in the viewer.
    results.forEach((r) => {
        if (r.artifact && r.artifact.modelAuthored) {
            const others = results.filter((x) => x !== r && !(x.facts && x.facts.error));
            r.artifact.unverified = unverifiedNumbers(r.artifact.markdown || "", others, question, ctx);
        }
    });

    // A notes summary is already a finished answer.
    const finalTool = results.find((r) => r.final);
    if (finalTool && results.length === 1) {
        onEvent({ type: "done", text: finalTool.markdown, unverified: [], results });
        return { text: finalTool.markdown, results };
    }

    // 3a. Lite: answer with the code-built briefs (accurate by construction) rather than small-model prose.
    const briefs = results.map(briefFor).filter(Boolean);
    if (profile.codeAnswers && briefs.length) {
        const text = briefs.join("\n\n");
        onEvent({ type: "done", text, unverified: [], results });
        return { text, results, unverified: [] };
    }

    // 3b. Grounded answer. The facts get whatever room is left after the instructions, history and question.
    onEvent({ type: "status", text: t("ai.status.writing", "Writing answer...") });
    const budget = profile.contextChars || 5000;
    const manifest = displayManifest(results, ctx);
    const historyMsgs = history.slice(-profile.historyTurns * 2).map((h) => historyMessage(h, 300));
    const promptArgs = { ctx, profile, results, manifest, compact: isCompact };
    const userTurn = question + displayReminder(results);
    const fixedChars = answerSystemPrompt({ ...promptArgs, facts: "" }).length
        + historyMsgs.reduce((sum, m) => sum + m.content.length, 0) + userTurn.length;
    const factsBudget = Math.max(700, budget - fixedChars - 60);
    let factsText;
    if (results.length) {
        const keyPoints = briefs.length ? `KEY POINTS (computed by code, exact):\n${briefs.join("\n")}`.slice(0, Math.floor(factsBudget * 0.4)) : "";
        factsText = [keyPoints, digestFacts(results, factsBudget - keyPoints.length - 2)].filter(Boolean).join("\n\n");
    } else {
        factsText = JSON.stringify(eventDigest(ctx));
    }
    const system = answerSystemPrompt({ ...promptArgs, facts: factsText });

    const messages = [{ role: "system", content: system }, ...historyMsgs, { role: "user", content: userTurn }];
    const hasVisual = results.some((r) => r.chart || r.table || r.artifact);
    const hasTable = results.some((r) => r.table && r.table.rows && r.table.rows.length);
    const rawText = await AI.generate(enforceMessageBudget(messages, budget), {
        maxTokens: profile.maxAnswerTokens,
        temperature: 0,
        signal,
        onToken: (_d, full) => onEvent({ type: "token", full: cleanGraphRefusalText(full, hasVisual, hasTable) })
    });

    const verification = verifyAndCorrectAnswer(rawText, results.length ? results : [{ facts: eventDigest(ctx), args: {} }], question, ctx);
    const text = verification.text;
    const unverified = verification.unverified;

    onEvent({ type: "done", text, unverified, results });
    return { text, results, unverified };
}

const tools = { TOOLS, ruleRoute, answerQuestion, unverifiedNumbers, cleanGraphRefusalText, verifyAndCorrectAnswer, evaluate, parseJsonLoose, parseToolCalls };
export default tools;
