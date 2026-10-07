/**
 * Model-driven routing: tool catalog, JSON schemas, tool-call parsing and the facts digest given to the model.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
import { r1 } from "./shared.js";
import { TOOLS } from "./tools.js";

// ------------------------------------------------------------------ model-driven routing

export function toolCatalog(ctx, allowed) {
    return Object.entries(TOOLS).filter(([name]) => allowed.includes(name))
        .map(([name, tool]) => `- ${name}(${Object.entries(tool.params).map(([k, v]) => `${k}: ${v}`).join(", ")}): ${tool.description}`)
        .join("\n");
}

export function metricList(ctx) {
    return ctx.metrics.map((m) => m.label).slice(0, 40).join(", ");
}

export const TOOL_ARGS_SCHEMA = {
    type: "object",
    properties: {
        team: { type: "integer" },
        teams: { type: "array", items: { type: "integer" } },
        metric: { type: "string" },
        metrics: { type: "array", items: { type: "string" } },
        metric_x: { type: "string" },
        metric_y: { type: "string" },
        n: { type: "integer" },
        order: { type: "string", enum: ["desc", "asc"] },
        match: { type: "string" },
        focus: { type: "string" },
        chart: { type: "boolean" },
        title: { type: "string" },
        type: { type: "string" },
        markdown: { type: "string" },
        expression: { type: "string" },
        topic: { type: "string" },
        unplayed_only: { type: "boolean" },
        order_by: { type: "string" },
        conditions: { type: "array", items: { type: "object", properties: { metric: { type: "string" }, op: { type: "string" }, value: { type: "number" } } } }
    }
};

export function stepSchema(allowed) {
    return {
        type: "object",
        properties: {
            action: { type: "string", enum: ["call", "calls", "answer"] },
            tool: { type: "string", enum: allowed },
            args: TOOL_ARGS_SCHEMA,
            calls: {
                type: "array",
                items: {
                    type: "object",
                    properties: {
                        tool: { type: "string", enum: allowed },
                        args: TOOL_ARGS_SCHEMA
                    },
                    required: ["tool"]
                }
            },
            tools: {
                type: "array",
                items: {
                    type: "object",
                    properties: {
                        tool: { type: "string", enum: allowed },
                        args: TOOL_ARGS_SCHEMA
                    },
                    required: ["tool"]
                }
            }
        },
        required: ["action"]
    };
}

export function parseJsonLoose(text) {
    if (!text) return null;
    const start = text.indexOf("{");
    if (start < 0) return null;
    let depth = 0;
    for (let i = start; i < text.length; i++) {
        if (text[i] === "{") depth++;
        else if (text[i] === "}") {
            depth--;
            if (depth === 0) {
                try { return JSON.parse(text.slice(start, i + 1)); } catch (_) { return null; }
            }
        }
    }
    return null;
}

/**
 * Extracts a list of tool call objects [{ tool, args }] from model output.
 * Supports:
 * - Single call: {"action":"call", "tool":"...", "args":{...}}
 * - Multiple calls in array: {"action":"calls", "calls":[{"tool":"...", "args":{...}}, ...]}
 * - Multiple calls in tools: {"action":"call", "tools":[{"tool":"...", "args":{...}}, ...]}
 * - Raw array of calls: [{"tool":"...", "args":{...}}, ...]
 * - Multiple separate JSON blocks emitted in text
 */
export function parseToolCalls(text, allowed) {
    if (!text) return [];
    const calls = [];
    const isAllowed = (name) => name && (!allowed || allowed.includes(name)) && TOOLS[name];

    const ingest = (obj) => {
        if (!obj) return;
        if (Array.isArray(obj)) {
            obj.forEach(ingest);
            return;
        }
        if (typeof obj !== "object") return;
        if (obj.action === "answer") return;

        if (Array.isArray(obj.calls)) {
            obj.calls.forEach(ingest);
        }
        if (Array.isArray(obj.tools)) {
            obj.tools.forEach(ingest);
        }
        if (obj.tool && isAllowed(obj.tool)) {
            calls.push({ tool: obj.tool, args: obj.args || {} });
        }
    };

    try {
        const parsed = JSON.parse(text.trim());
        ingest(parsed);
        if (calls.length > 0) return calls;
    } catch (_) { /* fall through to balanced block extractor */ }

    let i = 0;
    while (i < text.length) {
        const startBrace = text.indexOf("{", i);
        const startBracket = text.indexOf("[", i);
        let start = -1;
        let isArray = false;

        if (startBrace >= 0 && startBracket >= 0) {
            if (startBrace < startBracket) {
                start = startBrace;
                isArray = false;
            } else {
                start = startBracket;
                isArray = true;
            }
        } else if (startBrace >= 0) {
            start = startBrace;
            isArray = false;
        } else if (startBracket >= 0) {
            start = startBracket;
            isArray = true;
        } else {
            break;
        }

        const openChar = isArray ? "[" : "{";
        const closeChar = isArray ? "]" : "}";
        let depth = 0;
        let end = -1;

        for (let j = start; j < text.length; j++) {
            if (text[j] === openChar) depth++;
            else if (text[j] === closeChar) {
                depth--;
                if (depth === 0) {
                    end = j;
                    break;
                }
            }
        }

        if (end > start) {
            try {
                const subObj = JSON.parse(text.slice(start, end + 1));
                ingest(subObj);
            } catch (_) { /* continue */ }
            i = end + 1;
        } else {
            i = start + 1;
        }
    }

    return calls;
}

export function routerSystemPrompt(ctx, allowed, maxCalls) {
    return [
        "You route questions for a FIRST Robotics scouting assistant to data tools. Reply with JSON only.",
        `Event: ${ctx.eventKey || "unknown"}. Our team: ${ctx.ourTeam || "unknown"}.`,
        `Teams with data: ${Array.from(ctx.stats.keys()).slice(0, 80).join(", ")}.`,
        `Metrics: ${metricList(ctx)}.`,
        "Tools:",
        toolCatalog(ctx, allowed),
        "",
        "TOOL CALLING INSTRUCTIONS (Single or Multiple Tools):",
        "- You may call a SINGLE tool: {\"action\":\"call\",\"tool\":...,\"args\":{...}}",
        "- You may call MULTIPLE tools in a single response: {\"action\":\"calls\",\"calls\":[{\"tool\":\"...\",\"args\":{...}},{\"tool\":\"...\",\"args\":{...}}]}",
        "- When you have enough data to answer, reply with: {\"action\":\"answer\"}",
        "",
        'Example single: "who has the best auto?" -> {"action":"call","tool":"top_teams","args":{"metric":"Auto points","n":5}}',
        'Example multiple: "how does 254 compare with 1678 and what are the projected rankings?" -> {"action":"calls","calls":[{"tool":"compare_teams","args":{"teams":[254,1678]}},{"tool":"projected_rankings","args":{"n":10}}]}',
        'Example multiple: "show me top teams by xP and by OPR" -> {"action":"calls","calls":[{"tool":"top_teams","args":{"metric":"xP","n":5}},{"tool":"top_teams","args":{"metric":"OPR","n":5}}]}',
        'Example: "scatter total points vs auto points" -> {"action":"call","tool":"scatter","args":{"metric_x":"Total points","metric_y":"Auto points"}}',
        'Example: "graph team 1209 match by match" -> {"action":"call","tool":"metric_trend","args":{"teams":[1209],"metric":"Total points"}}',
        'Example: "match 13 xP graph" -> {"action":"call","tool":"match_preview","args":{"match":"13","metric":"xP"}}',
        'Example: "create a game plan for match 13" -> {"action":"call","tool":"create_strategy_brief","args":{"match":"13"}}',
        'Example: "alliance selection worksheet" -> {"action":"call","tool":"create_alliance_sheet","args":{}}',
        'Example: "radar chart for team 254" -> {"action":"call","tool":"team_radar","args":{"teams":[254]}}',
        'Example: "phase breakdown of top 5" -> {"action":"call","tool":"stacked_breakdown","args":{"n":5}}',
        'Example: "score distribution box plot" -> {"action":"call","tool":"score_distribution","args":{}}',
        'Example: "what is the projected end of the event / predicted rankings?" -> {"action":"call","tool":"projected_rankings","args":{"n":10}}',
        'Example: "what can you do?" -> {"action":"call","tool":"capabilities_help","args":{}}',
        "Only pass team numbers the user mentioned or that a previous tool result returned; never guess teams or carry forward stale teams from previous questions. To find teams by ability use top_teams, scatter or filter_teams."
    ].join("\n");
}

export function compactFacts(results, budgetChars) {
    let text = results.map((r) => `### ${r.tool}(${JSON.stringify(r.args)})\n${JSON.stringify(r.facts)}`).join("\n\n");
    if (text.length > budgetChars) text = text.slice(0, budgetChars) + "\n...(truncated)";
    return text;
}

export function eventDigest(ctx) {
    const top = Data.rankTeams(ctx, ctx.metrics[0]).slice(0, 8);
    return {
        event: ctx.eventKey, our_team: ctx.ourTeam, teams_with_data: ctx.stats.size, matches_in_schedule: ctx.matches.length,
        top_by_total_points: top.map((r) => ({ team: r.teamNumber, avg: r1(r.avg) })),
        metrics_available: ctx.metrics.map((m) => m.label).slice(0, 25)
    };
}
