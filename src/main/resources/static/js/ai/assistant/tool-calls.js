/**
 * Model-driven routing: tool guide and per-question shortlist, JSON schemas, argument clean-up, tool-call parsing
 * and the facts digest given to the model.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
import { digestFacts } from "./display.js";
import { r1 } from "./shared.js";
import { TOOLS } from "./tools.js";

// ------------------------------------------------------------------ tool guide

/**
 * When to use each tool and what the user sees afterwards. `tags` (with the tool's name and description) pick a
 * shortlist per question, so a small model reads ~14 relevant tools instead of a catalogue too long for its prompt.
 * `example` is [question, args] and is shown when the tool makes the shortlist.
 */
export const TOOL_GUIDE = {
    make_table: { use: "ANY custom table: choose the teams, the columns, filters, sorting and a row limit; also to CHANGE the table on screen (send the full new spec)", shows: "table (chart with chart:true, document with document:true)", tags: "table matrix spreadsheet columns column rows list remove add only sort order without include custom", example: ["table of EPA, OPR and xP for the top 10 teams by EPA", { columns: ["EPA", "OPR", "xP"], sort_by: "EPA", limit: 10 }] },
    make_chart: { use: "ANY custom chart (bar, stackedBar, line, scatter, radar, box, pie) for any metrics and teams; also to CHANGE the chart on screen", shows: "chart and its data table", tags: "chart graph plot pie bar line visualize visualise draw custom", example: ["pie chart of total points for the top 8 teams", { type: "pie", metrics: ["Total points"], limit: 8 }] },
    team_overview: { use: "everything about ONE team: averages, rank, matches played vs scouted, pit info, notes summary", shows: "stats table", tags: "team about overview profile how good tell info stats played scouted", example: ["how good is 254?", { team: 254 }] },
    top_teams: { use: "rank ALL teams by one metric (best, worst, top N, leaderboard)", shows: "ranking table, bar chart with chart:true", tags: "top best worst highest lowest rank ranking leaderboard most least who", example: ["who has the best auto?", { metric: "Auto points", n: 5 }] },
    epa_data: { use: "Statbotics EPA for one team, or all teams ranked by EPA", shows: "table, chart with chart:true", tags: "epa statbotics expected points added", example: ["top teams by EPA", { n: 8 }] },
    opr_data: { use: "OPR (TBA / FTC Scout) for one team, or all teams ranked by OPR", shows: "table, chart with chart:true", tags: "opr offensive power rating tba", example: ["what is 254's OPR?", { team: 254 }] },
    xp_data: { use: "Match 13 xP for one team, or all teams ranked by xP", shows: "table, chart with chart:true", tags: "xp exp expected match13 match 13", example: null },
    compare_teams: { use: "2-6 named teams side by side on several metrics", shows: "comparison table, chart with chart:true", tags: "compare versus vs against side by side better difference between", example: ["compare 254 and 1678 on auto", { teams: [254, 1678], metrics: ["Auto points"] }] },
    compare_metrics: { use: "two METRICS against each other across teams (EPA vs xP, auto vs teleop)", shows: "table and chart", tags: "metric difference epa xp opr gap correlate versus", example: ["average difference between EPA and xP", { metric_1: "EPA", metric_2: "xP" }] },
    compare_all_teams: { use: "one big table / matrix / spreadsheet of ALL teams across scouted data, EPA, OPR and xP", shows: "table with every team, plus a document", tags: "all every team table matrix spreadsheet artifact everyone whole event comprehensive", example: ["make a table of every team with EPA, OPR and xP", {}] },
    team_matches: { use: "one team's individual match scores", shows: "match table and line chart", tags: "match by match each match scores game history individual per match", example: ["254 match by match", { team: 254 }] },
    match_by_match: { use: "match-by-match lines for several teams", shows: "line chart and table", tags: "trend progression line over time match by match", example: null },
    metric_trend: { use: "how a metric changed over matches for 1-6 teams", shows: "line chart", tags: "trend changed improving over time line", example: null },
    scatter: { use: "correlate two metrics across all teams", shows: "scatter plot", tags: "scatter correlation plot relationship versus", example: ["scatter total points vs auto", { metric_x: "Total points", metric_y: "Auto points" }] },
    stacked_breakdown: { use: "auto / teleop / endgame split per team", shows: "stacked bar chart", tags: "breakdown phases stacked auto teleop endgame split", example: null },
    team_radar: { use: "skill profile of 1-4 teams", shows: "radar chart", tags: "radar spider profile skills shape", example: null },
    score_distribution: { use: "spread of each team's match scores", shows: "box plot", tags: "distribution spread box variance range", example: null },
    consistency: { use: "most or least consistent / reliable teams on a metric", shows: "table and bar chart", tags: "consistent consistency reliable reliability steady predictable volatile variance stdev inconsistent", example: ["most consistent teams in teleop", { metric: "Teleop points", n: 8 }] },
    recent_form: { use: "who is improving, hot or slumping lately (last N matches vs overall average)", shows: "table and grouped bar chart", tags: "recent form hot cold lately last momentum improving improved declining slump streak", example: ["who improved the most in their last 3 matches?", { last: 3 }] },
    team_schedule: { use: "one team's matches (default: our team) with partners, opponents, results, record and upcoming matches", shows: "schedule table", tags: "schedule our matches when play partners opponents record upcoming remaining", example: ["who are our partners in upcoming matches?", { unplayed_only: true }] },
    head_to_head: { use: "two teams' history at this event, as partners or opponents", shows: "table", tags: "head h2h played against each other record met beat", example: ["has 254 played against 1678?", { teams: [254, 1678] }] },
    alliance_builder: { use: "how strong a hypothetical alliance of 2-3 teams would be, optionally against opponents", shows: "table and stacked bar chart", tags: "alliance with together team up combined hypothetical what if strength", example: ["how strong would 254, 1678 and 118 be together?", { teams: [254, 1678, 118] }] },
    match_preview: { use: "one specific match ('next' = our next match): both alliances and the prediction", shows: "table, chart with chart:true", tags: "match next preview qm upcoming red blue", example: ["preview match 12", { match: "12" }] },
    match_predictions: { use: "win probabilities for upcoming matches", shows: "table", tags: "predictions predict win probability odds who will win", example: null },
    all_matches: { use: "the whole event match schedule", shows: "table", tags: "schedule matches list all results", example: null },
    all_teams: { use: "list of every team at the event", shows: "table", tags: "teams roster list attending names", example: null },
    current_rankings: { use: "official standings (rank, ranking score, record)", shows: "table, chart with chart:true", tags: "standings rankings official rank record leaderboard current", example: null },
    projected_rankings: { use: "simulated end-of-event rankings and playoff chances", shows: "table, chart with chart:true", tags: "projected projection simulate final end predicted finish playoff chance", example: ["where will we finish?", { n: 10 }] },
    pick_candidates: { use: "who we should pick for alliance selection (excludes our team)", shows: "table, chart with chart:true", tags: "pick picks alliance selection draft choose partner candidates", example: ["best picks using xP", { focus: "xP", n: 8 }] },
    filter_teams: { use: "teams meeting numeric conditions", shows: "table", tags: "filter more than less than above below least over under", example: ["teams with auto over 10", { conditions: [{ metric: "Auto points", op: ">", value: 10 }] }] },
    metric_summary: { use: "event-wide statistics of ONE metric (mean, median, quartiles, histogram)", shows: "statistics table and histogram", tags: "event average median mean statistics stats spread quartile typical league overall histogram", example: ["what is the event average for endgame?", { metric: "Endgame points" }] },
    team_percentiles: { use: "where ONE team ranks on every metric; its strengths and weaknesses", shows: "table and bar chart", tags: "percentile strengths weaknesses stack up good at weak strong every metric", example: ["what are 254's strengths and weaknesses?", { team: 254 }] },
    search_notes: { use: "find words in scout notes across all teams", shows: "table of matching notes", tags: "notes mention mentioned search said defense tipped broke comments described", example: ["which teams were noted for defense?", { query: "defense" }] },
    summarize_notes: { use: "summary of what scouts wrote about ONE team", shows: "text", tags: "notes summary summarize scouts say qualitative", example: null },
    pit_search: { use: "search pit scouting answers (drivetrain, mechanisms) or list one team's pit data", shows: "table", tags: "pit drivetrain swerve tank mecanum weight mechanism intake climber robot build", example: ["which teams have swerve?", { query: "swerve" }] },
    event_summary: { use: "the event at a glance", shows: "table", tags: "event summary overview status glance going", example: null },
    scouting_coverage: { use: "missing scouting reports by team and match", shows: "table", tags: "coverage missing unscouted reports gaps need scouting", example: ["which teams still need scouting?", {}] },
    list_metrics: { use: "every metric that can be ranked, compared or charted", shows: "table", tags: "metrics list available fields columns", example: null },
    create_strategy_brief: { use: "full game-plan document for a match", shows: "document", tags: "strategy game plan tactics brief", example: null },
    create_alliance_sheet: { use: "alliance-selection draft worksheet document", shows: "document", tags: "worksheet draft board pick sheet document", example: null },
    create_team_dossier: { use: "full scouting report document for one team", shows: "document", tags: "dossier report deep dive document", example: null },
    create_artifact: { use: "a custom document, only when no other tool fits", shows: "document", tags: "document artifact write custom", example: null },
    capabilities_help: { use: "what the assistant can do and which data sources exist", shows: "document", tags: "help capabilities can you do tools data sources", example: null },
    read_docs: { use: "how to use ObsidianScout (site help)", shows: "document", tags: "how use site help guide docs manual tutorial setup", example: null },
    calculate: { use: "exact arithmetic", shows: "nothing", tags: "calculate math sum plus minus divide", example: null }
};

const CORE_TOOLS = ["make_table", "make_chart", "team_overview", "top_teams", "compare_teams", "match_preview", "pick_candidates"];

/** How to write make_table / make_chart specs; added to the router prompt whenever those tools are offered. */
export const SPEC_GUIDE = [
    "TABLE / CHART SPECS (make_table, make_chart):",
    "- Columns / metrics: exact metric names from the Metrics list; or \"max <metric>\", \"min <metric>\", \"stdev <metric>\"; or \"Matches scouted\", \"Matches played\", \"Official rank\", \"Ranking score\", \"Record\"; or arithmetic between metrics such as \"EPA - xP\" or \"(Auto points + Teleop points) / 2\".",
    "- Teams: omit for every team; \"teams\": [254, 1678] for specific teams; \"exclude\": [...] to leave some out; \"match\": \"12\" for the six teams in a match; \"conditions\": [{\"column\":\"EPA\",\"op\":\">\",\"value\":50}] to filter (a condition column can be arithmetic too: \"teams whose auto beats their teleop\" is {\"column\":\"Auto points - Teleop points\",\"op\":\">\",\"value\":0}).",
    "- \"sort_by\" + \"order\" (\"desc\" default, \"asc\") + \"limit\" for top / bottom N. make_table \"rows\": \"matches\" lists each match of the given teams; make_chart \"by\": \"match\" plots values match by match.",
    "- To change the table or chart on screen, call the same tool with the COMPLETE new spec (copy the current spec and apply the change)."
].join("\n");

const wordsOf = (text) => String(text || "").toLowerCase()
    .replace(/[^a-z0-9À-ɏ֐-׿\s]/g, " ")
    .split(/\s+/)
    .filter((w) => w.length > 1);

// Words that appear in most questions and say nothing about which tool fits.
const GENERIC_WORDS = new Set(["team", "teams", "match", "matches", "all", "data", "show", "points", "the", "for", "and", "with", "our", "which", "what", "who"]);

/** The allowed tools most relevant to a question, best first, always including a few core ones. */
export function selectTools(question, allowed, limit = 14) {
    const qWords = new Set(wordsOf(question).filter((w) => !GENERIC_WORDS.has(w)));
    const scored = allowed.map((name) => {
        const guide = TOOL_GUIDE[name] || {};
        let score = CORE_TOOLS.includes(name) ? 0.5 : 0;
        wordsOf(guide.tags).forEach((w) => { if (qWords.has(w)) score += 3; });
        wordsOf(name.replace(/_/g, " ")).forEach((w) => { if (qWords.has(w)) score += 2; });
        wordsOf(TOOLS[name] && TOOLS[name].description).forEach((w) => { if (w.length > 3 && qWords.has(w)) score += 1; });
        return { name, score };
    }).sort((a, b) => b.score - a.score);
    const picked = scored.filter((s) => s.score >= 1).slice(0, limit).map((s) => s.name);
    [...CORE_TOOLS, "capabilities_help"].forEach((name) => {
        if (allowed.includes(name) && !picked.includes(name)) picked.push(name);
    });
    return picked;
}

export function toolCatalog(ctx, allowed, names = null) {
    return (names || allowed).filter((name) => allowed.includes(name) && TOOLS[name]).map((name) => {
        const tool = TOOLS[name];
        const guide = TOOL_GUIDE[name];
        const what = guide ? `${guide.use}. Shows: ${guide.shows}.` : tool.description;
        return `- ${name}(${Object.keys(tool.params).join(", ")}): ${what}`;
    }).join("\n");
}

export function metricList(ctx) {
    return ctx.metrics.map((m) => m.label).slice(0, 40).join(", ");
}

const intArg = { type: "integer" };
export const TOOL_ARGS_SCHEMA = {
    type: "object",
    properties: {
        team: intArg,
        teams: { type: "array", items: intArg },
        opponents: { type: "array", items: intArg },
        exclude: { type: "array", items: intArg },
        columns: { type: "array", items: { type: "string" } },
        limit: intArg,
        rows: { type: "string", enum: ["teams", "matches"] },
        by: { type: "string", enum: ["team", "match"] },
        x: { type: "string" },
        document: { type: "boolean" },
        metric: { type: "string" },
        metrics: { type: "array", items: { type: "string" } },
        metric_1: { type: "string" },
        metric_2: { type: "string" },
        metric_x: { type: "string" },
        metric_y: { type: "string" },
        sort_by: { type: "string" },
        n: intArg,
        last: intArg,
        order: { type: "string" },
        match: { type: "string" },
        focus: { type: "string" },
        query: { type: "string" },
        chart: { type: "boolean" },
        chart_type: { type: "string", enum: ["bar", "line"] },
        title: { type: "string" },
        type: { type: "string" },
        markdown: { type: "string" },
        expression: { type: "string" },
        topic: { type: "string" },
        unplayed_only: { type: "boolean" },
        order_by: { type: "string" },
        conditions: { type: "array", items: { type: "object", properties: { column: { type: "string" }, metric: { type: "string" }, op: { type: "string" }, value: { type: "number" } } } }
    }
};

export function stepSchema(allowed) {
    const call = { type: "object", properties: { tool: { type: "string", enum: allowed }, args: TOOL_ARGS_SCHEMA }, required: ["tool"] };
    return {
        type: "object",
        properties: {
            action: { type: "string", enum: ["call", "calls", "answer"] },
            tool: { type: "string", enum: allowed },
            args: TOOL_ARGS_SCHEMA,
            calls: { type: "array", items: call },
            tools: { type: "array", items: call }
        },
        required: ["action"]
    };
}

/**
 * Cleans model-written arguments: "frc254" / "254" -> 254, "254, 1678" -> [254, 1678], "our team" -> our number,
 * "true" -> true, numeric match -> "12". Tools still validate everything themselves.
 */
export function coerceArgs(ctx, args) {
    const out = { ...(args || {}) };
    const ours = (v) => (typeof v === "string" && /^\s*(?:our(?:\s+team)?|ours|us|we)\s*$/i.test(v) && ctx.ourTeam ? ctx.ourTeam : v);
    const resolveTeam = (v) => {
        if (v === undefined || v === null || v === "") return undefined;
        if (typeof v === "number" && ctx.stats && ctx.stats.has(v)) return v;
        const vOurs = ours(v);
        if (typeof vOurs === "number") return vOurs;
        const str = String(vOurs).trim();
        const list = Data.findTeamsInText ? Data.findTeamsInText(ctx, str) : [];
        if (list.length === 1) return list[0];
        const single = Data.findTeam ? Data.findTeam(ctx, str) : null;
        if (single && ctx.stats && ctx.stats.has(single)) return single;
        const n = parseInt(str.replace(/^\s*(?:frc|ftc|team\s*#?)/i, ""), 10);
        return Number.isFinite(n) ? n : v;
    };
    if (out.team !== undefined && out.team !== null && out.team !== "") out.team = resolveTeam(out.team);
    ["teams", "opponents", "exclude"].forEach((key) => {
        let v = out[key];
        if (v === undefined || v === null || v === "") return;
        if (typeof v === "string") {
            const extracted = Data.findTeamsInText ? Data.findTeamsInText(ctx, v) : [];
            if (extracted.length) {
                v = extracted;
            } else {
                v = v.split(/\s*(?:,|;|&|\band\b|\bvs\.?\b|\s)\s*/i).filter(Boolean);
            }
        }
        if (!Array.isArray(v)) v = [v];
        out[key] = Array.from(new Set(v.map(resolveTeam).filter((x) => Number.isFinite(x) && ctx.stats && ctx.stats.has(x))));
    });
    ["columns", "metrics"].forEach((key) => {
        if (typeof out[key] === "string") out[key] = out[key].split(/\s*[,;]\s*|\s+and\s+/i).filter(Boolean);
    });
    ["n", "last", "limit"].forEach((key) => {
        if (out[key] === undefined || out[key] === null) return;
        const n = parseInt(out[key], 10);
        out[key] = Number.isFinite(n) && n > 0 ? n : undefined;
    });
    ["unplayed_only", "document"].forEach((key) => {
        if (typeof out[key] === "string") out[key] = /^(true|yes|1)$/i.test(out[key]);
    });
    // chart may also name a chart type (make_table chart:"pie").
    ["chart"].forEach((key) => {
        if (typeof out[key] === "string") out[key] = /^(true|yes|1)$/i.test(out[key]) ? true : (/^(false|no|0)$/i.test(out[key]) ? false : out[key]);
    });
    if (typeof out.match === "number") out.match = String(out.match);
    return out;
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

function examplesFor(names, limit) {
    return names.map((name) => {
        const ex = TOOL_GUIDE[name] && TOOL_GUIDE[name].example;
        return ex ? `"${ex[0]}" -> ${JSON.stringify({ action: "call", tool: name, args: ex[1] })}` : null;
    }).filter(Boolean).slice(0, limit);
}

/** System prompt for the routing step. Only a per-question shortlist of tools is listed, so it fits small prompts. */
export function routerSystemPrompt(ctx, allowed, maxCalls, { compact = false, question = "", include = [] } = {}) {
    if (compact) {
        // Small prompt budget: six tools at most, and the reply format before the list so trimming never loses it.
        const few = selectTools(question, allowed, 6).slice(0, 6);
        return [
            "Route a FIRST Robotics scouting question to ONE tool. Output JSON only.",
            'Reply {"action":"call","tool":"...","args":{...}} or {"action":"answer"}',
            `Our team: ${ctx.ourTeam || "unknown"}. Metrics: ${metricList(ctx).slice(0, 160)}.`,
            ...examplesFor(few, 1).map((e) => `Example: ${e}`),
            "Tools:",
            toolCatalog(ctx, allowed, few)
        ].join("\n");
    }
    const names = Array.from(new Set([...include.filter((n) => allowed.includes(n)), ...selectTools(question, allowed, 12)]));
    const teamEntries = Array.from(ctx.stats ? ctx.stats.entries() : []);
    const teamListFormatted = teamEntries.slice(0, 40).map(([num, s]) => s.name ? `${num} (${s.name})` : String(num)).join(", ");
    return [
        "You choose data tools for a FIRST Robotics scouting assistant. Reply with JSON only.",
        `Event: ${ctx.eventKey || "unknown"}. Our team: ${ctx.ourTeam || "unknown"} ("we", "us" and "our team" mean team ${ctx.ourTeam || "unknown"}).`,
        `Teams at the event (${teamEntries.length}): ${teamListFormatted}${teamEntries.length > 40 ? ", ..." : ""}.`,
        `Metrics (use these exact names): ${metricList(ctx)}.`,
        "",
        "TOOLS (most relevant to this question; each shows its result to the user as a table, chart or document):",
        toolCatalog(ctx, allowed, names),
        "",
        "HOW TO CHOOSE:",
        "- Pick the tool whose purpose matches the question. Prefer ONE call.",
        `- Up to ${maxCalls} call${maxCalls === 1 ? "" : "s"} in total; use more than one only when the question needs different data (e.g. two different questions in one).`,
        "- Tables and charts are drawn automatically from the tool result. When the user asks for a graph, chart or plot, add \"chart\": true.",
        "- When the user references a team by their name or nickname (e.g. 'Citrus Circuits' -> 1678, 'Cheesy Poofs' -> 254), map it to their team number in tool arguments.",
        "- Use team numbers exactly as written. Leave out optional arguments you do not need.",
        "- Reply {\"action\":\"answer\"} for small talk or when the data already retrieved answers the question.",
        "",
        ...(names.includes("make_table") || names.includes("make_chart") ? [SPEC_GUIDE, ""] : []),
        "FORMAT:",
        '- One call: {"action":"call","tool":"<name>","args":{...}}',
        '- Several: {"action":"calls","calls":[{"tool":"<name>","args":{...}}]}',
        '- Done: {"action":"answer"}',
        "",
        "EXAMPLES:",
        ...examplesFor(names, 6)
    ].join("\n");
}

/** Tool facts for the model, sized to budgetChars (see digestFacts). */
export function compactFacts(results, budgetChars) {
    return digestFacts(results, budgetChars);
}

export function eventDigest(ctx) {
    const top = Data.rankTeams(ctx, ctx.metrics[0]).slice(0, 8);
    return {
        event: ctx.eventKey, our_team: ctx.ourTeam, teams_with_data: ctx.stats.size, matches_in_schedule: ctx.matches.length,
        top_by_total_points: top.map((r) => ({ team: r.teamNumber, avg: r1(r.avg) })),
        metrics_available: ctx.metrics.map((m) => m.label).slice(0, 25)
    };
}
