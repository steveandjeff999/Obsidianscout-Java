/**
 * Local AI assistant tools + orchestration - ObsidianScout
 *
 * The model never computes statistics. Questions are answered by:
 *   1. on Lite, a deterministic rule router (handles most common questions instantly); on the Gemma tiers, the model;
 *   2. the model picking read-only tools from a per-question shortlist: Gemma 4 through its native function calling
 *      (declarations in the chat template, tool_call / tool_response turns), other models with {"calls":[...]} JSON
 *      (grammar-constrained on WebLLM, one schema branch per tool);
 *   3. every call checked against that tool's schema and the event (teams, metrics, matches); an invalid call gets one
 *      repair turn with the exact problem instead of running on wrong arguments;
 *   4. JavaScript executing the tools against ai-data.js (tables + charts are built by code);
 *   5. the model writing a short answer grounded in the tool results, told exactly what is drawn above it;
 *   6. a guardrail flagging any number in the answer that does not appear in the tool results.
 *
 * Split into modules under ./assistant/: router, tools, tools-extra, tool-calls, tool-schemas, gemma-format, display,
 * prompts, briefs, verify, docs-guide, eval, shared.
 */

import AI from "./local-ai.js";
import { briefFor } from "./assistant/briefs.js";
import { editSpec, isEditRequest, lastVisualSpec } from "./assistant/builder.js";
import { digestFacts, displayManifest, historyDisplayNote, normalizeChart } from "./assistant/display.js";
import { gemmaThought } from "./assistant/gemma-format.js";
import { answerSystemPrompt, displayReminder } from "./assistant/prompts.js";
import { hasIntent, ruleRoute } from "./assistant/router.js";
import { evaluate, t } from "./assistant/shared.js";
import { coerceArgsDetailed, describeTool, eventDigest, parseJsonLoose, parseToolCalls, questionEntities, routerSystemPrompt, routerTools, stepSchema } from "./assistant/tool-calls.js";
import { toolDeclarations, validateCall } from "./assistant/tool-schemas.js";
import { TOOLS } from "./assistant/tools.js";
import { cleanGraphRefusalText, unverifiedNumbers, verifyAndCorrectAnswer } from "./assistant/verify.js";

export { briefFor } from "./assistant/briefs.js";
export { editSpec, isEditRequest, lastVisualSpec, resolveColumn, specFromDisplay, specFromQuestion } from "./assistant/builder.js";
export { getDocGuide } from "./assistant/docs-guide.js";
export { displayManifest, digestFacts, historyDisplayNote, normalizeChart, toolTitle } from "./assistant/display.js";
export { ruleRoute } from "./assistant/router.js";
export { evaluate } from "./assistant/shared.js";
export { coerceArgs, coerceArgsDetailed, parseJsonLoose, parseToolCalls, questionEntities, selectTools } from "./assistant/tool-calls.js";
export { toolSchema, validateCall } from "./assistant/tool-schemas.js";
export { TOOLS } from "./assistant/tools.js";
export { unverifiedNumbers, cleanGraphRefusalText, correctLeaderClaims, correctTableClaims, stripMarkdownTables, verifyAndCorrectAnswer } from "./assistant/verify.js";

// Tools that draw a chart when the question asks for one ("graph ...") even if the model forgot chart:true.
const CHARTABLE = ["top_teams", "epa_data", "opr_data", "xp_data", "compare_teams", "team_overview", "projected_rankings", "match_preview",
    "pick_candidates", "current_rankings", "compare_all_teams", "compare_metrics", "make_table"];
// Same subject = same answer for these, whatever the other arguments say.
const SUBJECT_TOOLS = ["compare_teams", "team_overview", "match_preview", "summarize_notes", "projected_rankings", "team_schedule", "team_percentiles"];

const msgChars = (m) => (m.content ? m.content.length : 0) + (m.tool_calls ? JSON.stringify(m.tool_calls).length : 0);

/**
 * Fits messages in maxChars. The system prompt and everything from `protectFrom` on (the latest question and this
 * question's tool turns) are kept; the oldest history turns are dropped first, whole; then the DATA section of the
 * system prompt is shortened.
 */
export function enforceMessageBudget(messages, maxChars = 5000, protectFrom = messages.length - 1) {
    const result = [...messages];
    let total = result.reduce((sum, m) => sum + msgChars(m), 0);
    let protect = protectFrom;
    while (total > maxChars && protect > 1) {
        const removed = result.splice(1, 1)[0];
        total -= msgChars(removed);
        protect--;
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
    const text = String(h.content || "").replace(/<thought>[\s\S]*?<\/thought>/gi, "").trim().slice(0, maxChars);
    return { role: h.role, content: note ? `${note}\n${text}` : text };
}

const looksLikeAttempt = (raw) => /[{[]/.test(raw) && !/"calls"\s*:\s*\[\s*\]/.test(raw) && !/"action"\s*:\s*"answer"/.test(raw);

/**
 * The model picks and runs tools. Returns nothing; results land in `results` through runTool.
 * Native (Gemma) turns are real tool_call / tool_response messages; JSON turns are flattened to text by the worker.
 */
async function modelRoute({ question, history, ctx, profile, allowed, results, runTool, signal, onEvent }) {
    // Native tool calls only when the loaded engine really takes declarations; otherwise the tools are listed in the
    // prompt and the model replies in JSON, so a model is never routed without seeing any tools.
    const engine = typeof AI.getEngineStatus === "function" ? AI.getEngineStatus() : {};
    const native = profile.toolFormat === "gemma" && engine.nativeTools !== false;
    const maxCalls = Math.min(15, Math.max(1, profile.maxToolCalls || 15));
    const compact = (profile.contextTokens || 2048) <= 2048;
    onEvent({ type: "status", text: t("ai.status.planning", "Working out what data is needed...") });

    const onScreen = lastVisualSpec(history, ctx);
    // Teams the question names (by number or name, resolved in code).
    const named = questionEntities(ctx, question).teams.map((x) => x.number);
    // The previous chart / table is something to edit only when the question asks for an edit or names no teams of its
    // own; a question about new teams is a new request, so the old tool is neither forced into the shortlist nor
    // suggested ("compare 757 and Wildcard" after a metric-vs-metric chart must not redraw that chart).
    const editing = isEditRequest(question);
    const followsScreen = !!(onScreen && onScreen.tool && (editing || !named.length));
    // An edit ("make that a scatter plot", "add 254", "use xP instead") is applied to the on-screen spec in code and
    // offered as the suggested call, and the teams on screen must stay in the call (no all-teams redraw).
    const draft = followsScreen && editing ? editSpec(onScreen, question, ctx) : null;
    const screenTeams = followsScreen && onScreen.args && Array.isArray(onScreen.args.teams) ? onScreen.args.teams : [];
    const required = named.length >= 2 ? named : (editing && screenTeams.length >= 2 && !named.length ? screenTeams : []);
    const include = followsScreen ? Array.from(new Set([onScreen.tool, ...(draft && draft.changed ? [draft.tool] : [])])) : [];
    const limit = compact ? 6 : (profile.routerToolCount || 10);
    const offered = routerTools(ctx, allowed, question, include, limit);
    const system = routerSystemPrompt(ctx, allowed, maxCalls, { compact, question, include, native, limit });
    const tools = native ? toolDeclarations(offered, describeTool) : null;
    const jsonSchema = native ? null : stepSchema(offered, maxCalls);
    // The declarations are rendered into the prompt by the chat template, so they count against the same budget.
    const routerBudget = Math.max(1500, (profile.routerChars || 4000) - (tools ? JSON.stringify(tools).length : 0));

    const messages = [{ role: "system", content: system }];
    history.slice(-profile.historyTurns * 2).forEach((h) => messages.push(historyMessage(h, 250)));
    const questionIdx = messages.length;
    let pending = native ? question : `Question: ${question}`;
    if (followsScreen) pending += `\n\nOn screen from the previous reply (context only; to change it, call the same tool with the complete new spec): ${JSON.stringify(onScreen)}`;
    if (draft && draft.changed) pending += `\nSuggested call (the on-screen spec with this change applied; use it unless the question asks for something else): ${JSON.stringify({ tool: draft.tool, args: draft.args })}`;
    messages.push({ role: "user", content: pending });

    const subject = (a) => JSON.stringify((a && (a.teams || a.team || a.match)) ?? null);
    let callsLeft = maxCalls;
    let repairsLeft = 1;
    let steps = profile.maxRouterSteps || 3;
    for (let step = 0; step < steps && callsLeft > 0; step++) {
        if (signal && signal.aborted) throw new DOMException("Aborted", "AbortError");
        const raw = await AI.generate(enforceMessageBudget(messages, routerBudget, questionIdx), {
            maxTokens: profile.maxRouterTokens || 500, temperature: 0, signal, raw: true,
            tools, thinking: native && !!profile.routerThinking, jsonSchema
        });
        const { calls, rejected } = parseToolCalls(raw, allowed, { withRejected: true });

        if (!calls.length && !rejected.length) {
            // No call: the model is done (native: it wrote a sentence). A garbled JSON attempt gets one retry.
            if (!native && repairsLeft > 0 && looksLikeAttempt(raw)) {
                repairsLeft--;
                steps++;
                messages.push({ role: "assistant", content: raw });
                messages.push({ role: "user", content: 'That reply could not be read. Reply with JSON only: {"calls":[{"tool":"<name>","args":{...}}]} or {"calls":[]}.' });
                continue;
            }
            break;
        }

        const toolCalls = [];
        const toolMsgs = [];
        let executed = 0;
        let invalid = 0;
        const respond = (name, args, content) => {
            const id = `call_${step}_${toolCalls.length}`;
            toolCalls.push({ id, type: "function", function: { name, arguments: args && typeof args === "object" ? args : {} } });
            toolMsgs.push({ role: "tool", tool_call_id: id, name, content });
        };
        rejected.forEach((r) => {
            invalid++;
            respond(r.tool, {}, `ERROR: ${r.tool} was not run because ${r.reason}. Tools you can call: ${offered.join(", ")}.`);
        });
        // Named-teams guard: when the question names two or more teams and no call in this step (nor any earlier
        // result) includes any of them, the model has picked an event-wide tool by mistake; it gets one repair.
        const teamsIn = (a) => {
            const out = new Set();
            if (!a) return out;
            [a.team, ...(Array.isArray(a.teams) ? a.teams : []), ...(Array.isArray(a.opponents) ? a.opponents : [])].forEach((x) => { if (Number.isFinite(x)) out.add(x); });
            return out;
        };
        const cleanedCalls = calls.slice(0, callsLeft).map((call) => ({ call, ...coerceArgsDetailed(ctx, call.args) }));
        const covered = new Set([...results.flatMap((r) => [...teamsIn(r.args)]), ...cleanedCalls.flatMap((c) => [...teamsIn(c.args)])]);
        const missesNamed = required.length >= 2 && !required.some((n) => covered.has(n)) && repairsLeft > 0;
        for (const { call, args: cleaned, unknownTeams } of cleanedCalls) {
            const { args, errors } = validateCall(ctx, call.tool, cleaned, { unknownTeams });
            if (missesNamed && !errors.length) {
                errors.push(draft && draft.changed && !named.length
                    ? `This changes the ${onScreen.tool === "make_table" ? "table" : "chart"} on screen (teams ${required.join(", ")}), but this call drops those teams. Call ${draft.tool} with ${JSON.stringify(draft.args)}.`
                    : `The question is about teams ${required.join(", ")}, but this call does not include them. Call a tool that takes teams (compare_teams, make_table or make_chart) with teams: [${required.join(", ")}].`);
            }
            if (errors.length) {
                invalid++;
                onEvent({ type: "tool_rejected", name: call.tool, args: call.args, errors });
                respond(call.tool, call.args, `ERROR: not run. ${errors.join(" ")} Call ${call.tool} again with corrected arguments.`);
                continue;
            }
            const dup = results.some((r) => r.tool === call.tool &&
                (JSON.stringify(r.args) === JSON.stringify(args) || (SUBJECT_TOOLS.includes(r.tool) && subject(r.args) === subject(args))));
            if (dup) {
                respond(call.tool, args, "Already retrieved above; use that result.");
                continue;
            }
            const entry = await runTool(call.tool, args, { coerced: true });
            executed++;
            callsLeft--;
            if (entry && entry.facts && entry.facts.error) {
                respond(call.tool, args, `ERROR: ${entry.facts.error}`);
            } else {
                const shown = displayManifest([entry], ctx);
                respond(call.tool, args, `SUCCESS${shown ? ` (shown to the user: ${shown.replace(/^\d+\.\s*/gm, "")})` : ""}\n${digestFacts([entry], 1000)}`);
            }
        }
        const thought = native ? gemmaThought(raw) : "";
        messages.push({ role: "assistant", content: "", tool_calls: toolCalls, ...(thought ? { reasoning: thought } : {}) });
        messages.push(...toolMsgs);
        if (!native && toolMsgs.length) {
            const last = toolMsgs[toolMsgs.length - 1];
            last.content += '\n\nCall another tool only if the question still needs different data; otherwise reply {"calls":[]}.';
        }
        if (invalid && repairsLeft > 0) {
            // The repair turn does not use up a step: the model gets one chance to fix what was wrong.
            repairsLeft--;
            steps++;
            continue;
        }
        if (executed === 0) break;
    }
}

/**
 * Answers one question. onEvent receives:
 *   {type:'status', text} | {type:'tool', name, args, result} | {type:'tool_rejected', name, args, errors}
 *   | {type:'token', full} | {type:'done', text, unverified, results}
 * history items are {role, content, display?}; display is what an earlier reply showed (tables, charts, documents).
 */
export async function answerQuestion({ question, history = [], ctx, tier, signal, onEvent = () => {}, route = null, isZachary = undefined, routeOnly = false }) {
    const profile = AI.tierProfile(tier);
    const allowed = Object.keys(TOOLS).filter((name) => name !== "calculate" || profile.calculate);
    const results = [];
    const engineStatus = typeof AI.getEngineStatus === "function" ? AI.getEngineStatus() : {};
    const native = profile.toolFormat === "gemma" && engineStatus.nativeTools !== false;

    const wantsChart = hasIntent(question, "chart");
    const isCompact = (profile.contextChars <= 3000);
    const runTool = async (name, args, { coerced = false } = {}) => {
        const tool = TOOLS[name];
        if (!tool) return { facts: { error: `Tool ${name} does not exist.` } };
        const toolArgs = coerced ? { ...args } : coerceArgsDetailed(ctx, args).args;
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

    // 1. Explicit programmatic route (suggestion chips), or the deterministic rule router on "router" tiers (Lite).
    let routed = route;
    if (!routed && profile.toolMode === "router") {
        routed = ruleRoute(question, ctx, history);
    }
    if (routed && routed.tool) {
        await runTool(routed.tool, routed.args);
    } else if (profile.maxToolCalls > 0) {
        // 2. Model routing.
        await modelRoute({ question, history, ctx, profile, allowed, results, runTool, signal, onEvent });
    }
    // Routing only (the evaluation harness): the tools that ran, no answer.
    if (routeOnly) return { text: "", results, unverified: [] };

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
    const budget = profile.contextChars || 4000;
    const manifest = displayManifest(results, ctx);
    const historyMsgs = history.slice(-profile.historyTurns * 2).map((h) => historyMessage(h, 300));
    let zacharyActive = isZachary;
    if (zacharyActive === undefined) {
        try { zacharyActive = (typeof window !== "undefined" && window.localStorage && window.localStorage.getItem("obsidianscout:tutorial_mode") === "zachary"); } catch (_) {}
    }
    // Gemma thinks in its own channel (shown as <thought>); other models are asked for a <thought> block in the prompt.
    const nativeThinking = native && !!profile.visibleReasoning && !!profile.answerThinking;
    const promptArgs = { ctx, profile, results, manifest, compact: isCompact, isZachary: !!zacharyActive, nativeThinking };
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
        thinking: nativeThinking,
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
