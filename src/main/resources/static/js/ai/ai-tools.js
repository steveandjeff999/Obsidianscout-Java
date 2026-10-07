/**
 * Local AI assistant tools + orchestration - ObsidianScout
 *
 * The model never computes statistics. Questions are answered by:
 *   1. a deterministic rule router (handles most common questions instantly), else
 *   2. the model picking a read-only tool (JSON; grammar-constrained on WebLLM tiers), possibly several (Advanced),
 *   3. JavaScript executing the tool against ai-data.js (tables + charts are built by code),
 *   4. the model writing a short answer grounded only in the tool results,
 *   5. a guardrail flagging any number in the answer that does not appear in the tool results.
 *
 * Split into modules under ./assistant/: router, tools, tool-calls, briefs, verify, docs-guide, shared.
 */

import AI from "./local-ai.js";
import UI from "./ai-ui.js";
import { briefFor } from "./assistant/briefs.js";
import { hasIntent, ruleRoute } from "./assistant/router.js";
import { currentLanguage, evaluate, t } from "./assistant/shared.js";
import { compactFacts, eventDigest, parseJsonLoose, parseToolCalls, routerSystemPrompt, stepSchema } from "./assistant/tool-calls.js";
import { TOOLS } from "./assistant/tools.js";
import { cleanGraphRefusalText, unverifiedNumbers, verifyAndCorrectAnswer } from "./assistant/verify.js";

export { briefFor } from "./assistant/briefs.js";
export { getDocGuide } from "./assistant/docs-guide.js";
export { ruleRoute } from "./assistant/router.js";
export { evaluate } from "./assistant/shared.js";
export { parseJsonLoose, parseToolCalls } from "./assistant/tool-calls.js";
export { TOOLS } from "./assistant/tools.js";
export { unverifiedNumbers, cleanGraphRefusalText, verifyAndCorrectAnswer } from "./assistant/verify.js";

// ------------------------------------------------------------------ orchestration

/**
 * Answers one question. onEvent receives:
 *   {type:'status', text} | {type:'tool', name, args, result} | {type:'token', full} | {type:'done', text, unverified, results}
 */
export async function answerQuestion({ question, history = [], ctx, tier, signal, onEvent = () => {}, route = null }) {
    const profile = AI.tierProfile(tier);
    const allowed = Object.keys(TOOLS).filter((name) => name !== "calculate" || profile.calculate);
    const results = [];

    const wantsChart = hasIntent(question, "chart");
    const runTool = async (name, args) => {
        const tool = TOOLS[name];
        if (!tool) return { facts: { error: `Tool ${name} does not exist.` } };
        const toolArgs = { ...(args || {}) };
        if (wantsChart && toolArgs.chart === undefined && ["top_teams", "compare_teams", "team_overview", "projected_rankings", "match_preview", "pick_candidates"].includes(name)) {
            toolArgs.chart = true;
        }
        onEvent({ type: "status", text: t("ai.status.tool", "Looking up data ({tool})...").replace("{tool}", name.replace(/_/g, " ")) });
        try {
            const result = await tool.run(ctx, toolArgs, { signal });
            const entry = { tool: name, args: toolArgs, ...result };
            results.push(entry);
            onEvent({ type: "tool", name, args: toolArgs, result: entry });
            return entry;
        } catch (err) {
            const errEntry = { tool: name, args: toolArgs, facts: { error: err.message } };
            results.push(errEntry);
            onEvent({ type: "tool", name, args: toolArgs, result: errEntry });
            return errEntry;
        }
    };

    // 1. Deterministic routing first.
    const routed = route || ruleRoute(question, ctx, history);
    if (routed) await runTool(routed.tool, routed.args);

    // 2. Model routing (more steps on bigger tiers).
    const maxCalls = routed ? (profile.routedFollowUps || 0) : profile.maxToolCalls;
    if (maxCalls > 0) {
        onEvent({ type: "status", text: t("ai.status.planning", "Working out what data is needed...") });
        const routerMessages = [{ role: "system", content: routerSystemPrompt(ctx, allowed, maxCalls) }];
        history.slice(-2).forEach((h) => routerMessages.push({ role: h.role, content: String(h.content).slice(0, 250) }));
        let pending = `Question: ${question}`;
        if (results.length) pending += `\nAlready retrieved:\n${compactFacts(results, 800)}`;
        for (let step = 0; step < maxCalls; step++) {
            if (signal && signal.aborted) throw new DOMException("Aborted", "AbortError");
            routerMessages.push({ role: "user", content: pending });
            const cappedRouterMessages = enforceMessageBudget(routerMessages, 3500);
            const raw = await AI.generate(cappedRouterMessages, {
                maxTokens: 280, temperature: 0, signal,
                jsonSchema: profile.toolMode === "json" ? stepSchema(allowed) : null
            });
            routerMessages.push({ role: "assistant", content: raw });
            const toolCalls = parseToolCalls(raw, allowed);
            if (!toolCalls.length) break;

            const subject = (a) => JSON.stringify((a && (a.teams || a.team || a.match)) ?? null);
            const stepFeedback = [];
            let executedCount = 0;

            for (const call of toolCalls) {
                const dup = results.some((r) => r.tool === call.tool &&
                    (JSON.stringify(r.args) === JSON.stringify(call.args || {}) ||
                     (["compare_teams", "team_overview", "match_preview", "summarize_notes", "projected_rankings"].includes(r.tool) && subject(r.args) === subject(call.args))));
                if (dup) continue;

                const entry = await runTool(call.tool, call.args || {});
                executedCount++;
                if (entry && entry.facts && entry.facts.error) {
                    stepFeedback.push(`Execution result for ${call.tool}: ERROR - ${entry.facts.error}`);
                } else {
                    stepFeedback.push(`Execution result for ${call.tool}: SUCCESS\nData:\n${JSON.stringify(entry.facts).slice(0, 1000)}`);
                }
            }

            if (executedCount === 0) break;
            pending = `${stepFeedback.join("\n\n")}\n\nCall additional tools if needed, or reply with {"action":"answer"}.`;
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

    // 3a. Lite: answer with the code-built briefs (accurate by construction) rather than 0.5B prose.
    const briefs = results.map(briefFor).filter(Boolean);
    if (profile.codeAnswers && briefs.length) {
        const text = briefs.join("\n\n");
        onEvent({ type: "done", text, unverified: [], results });
        return { text, results, unverified: [] };
    }

    // 3b. Grounded answer.
    onEvent({ type: "status", text: t("ai.status.writing", "Writing answer...") });
    let factsText = results.length ? compactFacts(results, profile.contextChars - 1800) : JSON.stringify(eventDigest(ctx));
    if (briefs.length) factsText = `KEY POINTS (computed, 100% verified facts):\n${briefs.join("\n")}\n\n${factsText}`;
    const system = [
        "You are the ObsidianScout scouting assistant for a FIRST Robotics team.",
        `Our team: ${ctx.ourTeam || "unknown"}. Event: ${ctx.eventKey || "unknown"}.`,
        "",
        "SYSTEM CAPABILITIES & LOCAL TOOLS:",
        "- You have full access to local scouting analytics tools: match schedule (`all_matches`), team roster (`all_teams`), win predictions (`match_predictions`), official rankings (`current_rankings`), site user manuals and documentation (`read_docs`), match-by-match line graphs (`match_by_match`, `metric_trend`), ranking bar charts (`top_teams`), team comparisons (`compare_teams`), 2D scatter plots (`scatter`), radar skill charts (`team_radar`), phase breakdown stacked charts (`stacked_breakdown`), score distributions (`score_distribution`), match previews (`match_preview`), strategy briefs (`create_strategy_brief`), alliance pick sheets (`create_alliance_sheet`), team dossiers (`create_team_dossier`), and Monte Carlo projections (`projected_rankings`).",
        "- The UI system AUTOMATICALLY plots and renders interactive charts, graphs, and tables directly above your message whenever data or visualisations are requested.",
        "- NEVER state 'As a text-based AI, I don't have access to tools/APIs' or 'I cannot create charts'. You are connected to local ObsidianScout tools and data.",
        "- When the user asks to graph/chart or when a chart is shown, refer directly to the displayed graph/chart above (e.g., 'As shown in the graph above...').",
        "",
        // Written-out reasoning costs hundreds of tokens; only fast tiers get it (see TIER_PROFILES.visibleReasoning).
        ...(profile.visibleReasoning ? [
            "REASONING & CHAIN-OF-THOUGHT:",
            "- You can think step-by-step before producing your final response.",
            "- Wrap your internal chain-of-thought, calculations, and data checks inside <thought>...</thought> tags at the beginning of your response.",
            "- In your <thought> block: check team numbers, verify match numbers/averages, review rankings, and reason through tactical trade-offs.",
            "- After </thought>, output only your clear, direct, and verified final markdown answer for the user.",
            ""
        ] : ["Answer directly and concisely. Do not write out your reasoning.", ""]),
        "CONVERSATIONAL FOCUS & FRESHNESS:",
        "- Always address the USER'S LATEST QUESTION directly as your primary focus. Never repeat old answers or keep analyzing a previously discussed team unless the user specifically asks a follow-up about them.",
        "- Use conversation history ONLY for pronoun resolution or context when the latest question is a direct continuation (e.g., 'how does that compare?', 'what about their teleop?').",
        "- If the latest question is general, greeting, or meta (such as 'what can you do?', 'help', 'who are you?'), answer the question directly without referring back to older team graphs.",
        "",
        "SCOUTED VS PLAYED MATCHES DISTINCTION:",
        "- 'matches_scouted_by_our_team' indicates how many match reports OUR team's scouts submitted locally.",
        "- 'matches_played_at_event' indicates how many official matches the team has competed in at the event (from match schedule / Statbotics).",
        "- If a team has matches_scouted = 0 but has played matches (or has EPA/OPR/xP data from N > 0 matches), ALWAYS state that the team has played in matches at the event, but our scouting team has not submitted local match reports for them yet.",
        "- NEVER say a team has played 0 matches when only their locally scouted count is 0.",
        "",
        "STRICT FACT-CHECKING RULES:",
        "- Answer using ONLY the DATA below. Never state any statistic or number not present in the DATA.",
        "- STRICT TEAM ATTRIBUTION: Only attribute scores, averages, or rankings to the EXACT team that achieved them. Never confuse 'our team' with other teams.",
        `- If asked about our team (${ctx.ourTeam || "our team"}) and our team is not in the DATA, explicitly state that data for our team is not in the retrieved results.`,
        "- Never invent robot capabilities (defense, game strategy, shooter speed) not explicitly mentioned in the scout notes or pit data.",
        "- Do not repeat entire tables row by row: summarise the key takeaways in 2-5 concise sentences or short bullets.",
        profile.strategy ? "When asked for strategy (picks, defense, match plans), reason step by step from the verified DATA." : "",
        `Reply in ${currentLanguage()}.`,
        "",
        "DATA:",
        factsText
    ].filter(Boolean).join("\n");
function enforceMessageBudget(messages, maxChars = 5000) {
    let total = messages.reduce((sum, m) => sum + (m.content ? m.content.length : 0), 0);
    if (total <= maxChars) return messages;

    // 1. Trim history items (keep system at index 0 and latest user at last index)
    const result = [...messages];
    if (result.length > 2) {
        for (let i = 1; i < result.length - 1; i++) {
            if (result[i].content && result[i].content.length > 200) {
                result[i] = { ...result[i], content: result[i].content.slice(0, 200) + "..." };
            }
        }
        total = result.reduce((sum, m) => sum + (m.content ? m.content.length : 0), 0);
    }

    // 2. Drop older history if still over budget
    while (result.length > 2 && total > maxChars) {
        const removed = result.splice(1, 1)[0];
        total -= (removed.content ? removed.content.length : 0);
    }

    // 3. Truncate system prompt DATA section if still over budget
    if (total > maxChars && result[0] && result[0].content) {
        const excess = total - maxChars;
        const sys = result[0].content;
        const dataIdx = sys.indexOf("DATA:");
        if (dataIdx > 0) {
            const head = sys.slice(0, dataIdx + 5);
            const dataBody = sys.slice(dataIdx + 5);
            const keepLen = Math.max(200, dataBody.length - excess - 50);
            result[0] = { ...result[0], content: head + "\n" + dataBody.slice(0, keepLen) + "\n...(truncated for context limit)" };
        } else {
            result[0] = { ...result[0], content: sys.slice(0, Math.max(500, sys.length - excess)) + "..." };
        }
    }
    return result;
}

    const messages = [{ role: "system", content: system }];
    history.slice(-profile.historyTurns * 2).forEach((h) => messages.push({ role: h.role, content: String(h.content).slice(0, 300) }));
    messages.push({ role: "user", content: question });

    const cappedMessages = enforceMessageBudget(messages, profile.contextChars || 5000);

    const rawText = await AI.generate(cappedMessages, {
        maxTokens: profile.maxAnswerTokens,
        temperature: 0,
        signal,
        onToken: (_d, full) => onEvent({ type: "token", full: cleanGraphRefusalText(full, results.some((r) => r.chart || r.table)) })
    });

    const verification = verifyAndCorrectAnswer(rawText, results.length ? results : [{ facts: eventDigest(ctx), args: {} }], question, ctx);
    const text = verification.text;
    const unverified = verification.unverified;

    onEvent({ type: "done", text, unverified, results });
    return { text, results, unverified };
}

const tools = { TOOLS, ruleRoute, answerQuestion, unverifiedNumbers, cleanGraphRefusalText, verifyAndCorrectAnswer, evaluate, parseJsonLoose, parseToolCalls };
export default tools;
