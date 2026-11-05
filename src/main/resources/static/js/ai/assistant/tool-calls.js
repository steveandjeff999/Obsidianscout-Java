/**
 * Model-driven routing: tool guide and per-question shortlist, JSON schemas, argument clean-up, tool-call parsing
 * and the facts digest given to the model.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
import { findMetricsInText } from "./builder.js";
import { digestFacts } from "./display.js";
import { parseGemmaToolCalls } from "./gemma-format.js";
import { INTENTS, hasIntent } from "./router.js";
import { r1 } from "./shared.js";
import { stepSchema as perToolStepSchema } from "./tool-schemas.js";
import { TOOLS } from "./tools.js";

// ------------------------------------------------------------------ tool guide

/**
 * When to use each tool and what the user sees afterwards. `tags` (with the tool's name and description) pick a
 * shortlist per question, so a small model reads ~14 relevant tools instead of a catalogue too long for its prompt.
 * `example` is [question, args] and is shown when the tool makes the shortlist.
 */
export const TOOL_GUIDE = {
    make_table: { use: "ANY custom table: choose the teams, the columns, filters, sorting and a row limit; values from one match or a few matches (match / matches / last); also to CHANGE the table on screen (send the full new spec)", shows: "table (chart with chart:true, document with document:true)", tags: "table matrix spreadsheet columns column rows list remove add only sort order without include custom tabla tablo טבלה", example: ["table of EPA, OPR and xP for the top 10 teams by EPA", { columns: ["EPA", "OPR", "xP"], sort_by: "EPA", limit: 10 }],
        examples: [
            ["compare 254 and 1678 in match 10 using auto points", { teams: [254, 1678], match: "10", columns: ["Auto points"] }],
            ["auto and teleop for 118 and 4414 over their last 3 matches", { teams: [118, 4414], last: 3, columns: ["Auto points", "Teleop points"] }]
        ] },
    make_chart: { use: "ANY custom chart (bar, stackedBar, line, scatter, radar, box, pie) for any metrics and teams, optionally only some matches; also to CHANGE the chart on screen", shows: "chart and its data table", tags: "chart graph plot pie bar line visualize visualise draw custom gráfico grafik גרף תרשים", example: ["pie chart of total points for the top 8 teams", { type: "pie", metrics: ["Total points"], limit: 8 }],
        examples: [["line chart of xP for 254 and 1678 in matches 5 to 15", { type: "line", by: "match", teams: [254, 1678], matches: ["5-15"], metrics: ["xP"] }]] },
    team_overview: { use: "everything about ONE team: averages, rank, matches played vs scouted, pit info, notes summary", shows: "stats table", tags: "team about overview profile how good tell info stats played scouted", example: ["how good is 254?", { team: 254 }] },
    top_teams: { use: "rank ALL teams by one metric (best, worst, top N, leaderboard)", shows: "ranking table, bar chart with chart:true", tags: "top best worst highest lowest rank ranking leaderboard most least who", example: ["who has the best auto?", { metric: "Auto points", n: 5 }] },
    epa_data: { use: "Statbotics EPA for one team, or all teams ranked by EPA", shows: "table, chart with chart:true", tags: "epa statbotics expected points added rating", example: ["top teams by EPA", { n: 8 }] },
    opr_data: { use: "OPR (TBA / FTC Scout) for one team, or all teams ranked by OPR", shows: "table, chart with chart:true", tags: "opr offensive power rating tba ftc", example: ["what is 254's OPR?", { team: 254 }] },
    xp_data: { use: "Match 13 xP for one team, or all teams ranked by xP", shows: "table, chart with chart:true", tags: "xp exp expected match13 match 13 rating", example: ["top teams by xP", { n: 8 }] },
    compare_teams: { use: "2-6 named teams side by side on several metrics", shows: "comparison table, chart with chart:true", tags: "compare versus vs against side by side better difference between", example: ["compare 254 and 1678 on auto", { teams: [254, 1678], metrics: ["Auto points"] }] },
    compare_metrics: { use: "two METRICS against each other across teams (EPA vs xP, auto vs teleop)", shows: "table and chart", tags: "metric difference epa xp opr gap correlate versus", example: ["average difference between EPA and xP", { metric_1: "EPA", metric_2: "xP" }] },
    compare_all_teams: { use: "one big table / matrix / spreadsheet of ALL teams across scouted data, EPA, OPR and xP", shows: "table with every team, plus a document", tags: "all every team table matrix spreadsheet artifact everyone whole event comprehensive", example: ["make a table of every team with EPA, OPR and xP", {}] },
    team_matches: { use: "one team's individual match scores and match-by-match line chart", shows: "match table and line chart", tags: "match by match each match scores game history individual per match", example: ["254 match by match", { team: 254 }] },
    match_by_match: { use: "match-by-match scores and progression line chart for 1 or more teams across matches (supports xP, EPA, Total points, Auto, Teleop)", shows: "line chart and table", tags: "match by match per match each match trend progression line over time xp epa graph chart scores", example: ["teams 31, 1209, 1561, 1706 match by match using xP", { teams: [31, 1209, 1561, 1706], metric: "xP", chart: true }] },
    metric_trend: { use: "how a metric (e.g. xP, EPA, Total points, Auto) changed over matches for 1-6 teams", shows: "line chart", tags: "trend changed improving over time line match by match progression xp epa", example: ["xP trend for 1209 and 1561", { teams: [1209, 1561], metric: "xP" }] },
    scatter: { use: "two metrics against each other, for all teams or only the listed teams (pass teams)", shows: "scatter plot", tags: "scatter correlation plot relationship versus", example: ["scatter total points vs auto", { metric_x: "Total points", metric_y: "Auto points" }],
        examples: [["scatter plot of 254 and 1678 on OPR vs xP", { metric_x: "OPR", metric_y: "xP", teams: [254, 1678] }]] },
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
    qualitative_overview: { use: "qualitative scouting for ALL teams (or several): ratings, report counts, recurring note themes and example notes", shows: "table of every team",
        tags: "qualitative qual notes summary summarize all every teams ratings driver defense themes comments cualitativo cualitativa notas resumen todos nitel notlar özet tüm איכותי הערות סיכום כל",
        example: ["summarize the qualitative scouting data for all teams", {}] },
    pit_overview: { use: "pit scouting for ALL teams (or several) side by side: drivetrain, mechanisms, weight, every pit question", shows: "table of every team's pit answers",
        tags: "pit capabilities capability robot robots drivetrain mechanisms build specs all every teams overview summary pits fosa boxes pit verisi yetenek פיט יכולות",
        example: ["summarize every team's capabilities from pit scouting", {}] },
    match_reports: { use: "the individual scouting reports for ONE match: each team's scouted points and the scouts' notes, plus the result", shows: "table of the six teams' reports",
        tags: "reports report scouting match notes how did each team do what happened scouted partido informes maç rapor דוח משחק",
        example: ["what did our scouts record in match 12?", { match: "12" }] },
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

const CORE_TOOLS = ["make_table", "make_chart", "team_overview", "top_teams", "compare_teams", "match_by_match", "metric_trend", "match_preview", "pick_candidates"];

/** How to write make_table / make_chart specs; added to the router prompt whenever those tools are offered. */
export const SPEC_GUIDE = [
    "TABLE / CHART SPECS (make_table, make_chart):",
    "- Columns / metrics: exact metric names; or \"max <metric>\", \"min <metric>\", \"stdev <metric>\"; or \"Matches scouted\", \"Matches played\", \"Official rank\", \"Ranking score\", \"Record\"; or arithmetic such as \"EPA - xP\" or \"(Auto points + Teleop points) / 2\".",
    "- Teams: omit for every team; \"teams\": [..] for specific teams; \"exclude\": [..] to leave some out; \"conditions\": [{\"column\":\"EPA\",\"op\":\">\",\"value\":50}] to filter (a condition column can be arithmetic: auto beats teleop is {\"column\":\"Auto points - Teleop points\",\"op\":\">\",\"value\":0}).",
    "- Which matches: values are event averages unless you add \"match\": \"10\" (with teams: only that match; alone: that match's six teams), \"matches\": [\"10-20\"], or \"last\": 3.",
    "- \"sort_by\" + \"order\" (\"desc\" default, \"asc\") + \"limit\" for top / bottom N. make_table \"rows\": \"matches\" gives one row per team per match; make_chart \"by\": \"match\" plots match by match.",
    "- To change the table or chart on screen, call the same tool with the COMPLETE new spec (the current spec with the change applied)."
].join("\n");

const wordsOf = (text) => String(text || "").toLowerCase()
    .replace(/[^a-z0-9À-ɏ֐-׿\s]/g, " ")
    .split(/\s+/)
    .filter((w) => w.length > 1);

// Words that appear in most questions and say nothing about which tool fits.
const GENERIC_WORDS = new Set(["team", "teams", "all", "data", "show", "points", "point", "the", "for", "and", "with", "our", "which", "what", "who", "it", "now", "using", "use"]);

/**
 * Tools each router intent points at. INTENTS carry Spanish, Turkish and Hebrew words, so the shortlist works in every
 * supported language even though TOOL_GUIDE tags are English.
 */
const INTENT_TOOLS = {
    next: ["match_preview"], match: ["match_preview", "make_table", "match_reports"], strategy: ["create_strategy_brief"], worksheet: ["create_alliance_sheet"],
    dossier: ["create_team_dossier"], stacked: ["stacked_breakdown"], radar: ["team_radar"], box: ["score_distribution"],
    notes: ["summarize_notes", "search_notes", "qualitative_overview"], pick: ["pick_candidates"], trend: ["match_by_match", "metric_trend", "team_matches"],
    line: ["match_by_match", "make_chart"], scatter: ["scatter"], difference: ["compare_metrics"], versus: ["compare_teams"],
    compare: ["compare_teams", "compare_metrics"], top: ["top_teams"], worst: ["top_teams"], overview: ["team_overview"],
    chart: ["make_chart"], docs: ["read_docs"], all_matches: ["all_matches"], all_teams: ["all_teams"], predictions: ["match_predictions"],
    rankings: ["current_rankings"], projected: ["projected_rankings"], artifact: ["create_artifact"], match_count: ["team_overview"],
    capabilities: ["capabilities_help"], greeting: ["capabilities_help"], consistency: ["consistency"], form: ["recent_form"],
    schedule: ["team_schedule"], h2h: ["head_to_head"], alliance_build: ["alliance_builder"], stats: ["metric_summary"],
    percentile: ["team_percentiles"], note_search: ["search_notes"], pit: ["pit_search", "pit_overview"], metrics_list: ["list_metrics"],
    event_summary: ["event_summary"], coverage: ["scouting_coverage"]
};

// "match 10", "qm 10", "partido 10", "maç 10", "משחק 10", or Turkish "10. maç".
const MATCH_REF = /(?:\bmatch(?:es)?|\bqm|\bpartido|maç|משחק)\s*#?\s*(\d{1,3})(?:\s*(?:-|to|a|–)\s*(\d{1,3}))?(?!\d)|(?<!\d)(\d{1,3})\.?\s*(?:maç|partido)/i;

/**
 * What a question names, resolved in code so the model never has to map nicknames or guess metric labels:
 * { teams: [{number, name}], match: {ref, label, teams} | null, range: "10-20" | null, metrics: [label] }.
 */
export function questionEntities(ctx, question) {
    const q = String(question || "");
    const ref = q.match(MATCH_REF);
    const matchNum = ref ? Number(ref[1] || ref[3]) : null;
    const range = ref && ref[2] ? `${ref[1]}-${ref[2]}` : null;
    const m = matchNum && !range ? Data.findMatch(ctx, String(matchNum)) : null;
    const teams = Data.findTeamsInText(ctx, q, { excludeMatch: matchNum }).filter((n) => !(range && (n === Number(ref[1]) || n === Number(ref[2]))));
    let metrics = [];
    try { metrics = findMetricsInText(ctx, q).metrics.map((x) => x.label); } catch (_) { /* no metrics */ }
    return {
        teams: teams.map((number) => ({ number, name: Data.teamName(ctx, number) || "" })),
        match: m ? { ref: String(matchNum), label: Data.matchLabel(m), red: (m.redTeams || []).map(Data.teamNumberFromKey), blue: (m.blueTeams || []).map(Data.teamNumberFromKey) } : null,
        range,
        metrics
    };
}

/** Edit distance, stopping early once it exceeds max. */
function editDistance(a, b, max) {
    if (Math.abs(a.length - b.length) > max) return max + 1;
    let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
        const cur = [i];
        let best = i;
        for (let j = 1; j <= b.length; j++) {
            cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
            best = Math.min(best, cur[j]);
        }
        if (best > max) return max + 1;
        prev = cur;
    }
    return prev[b.length];
}

let vocabulary = null;
/** Every word in the tool guide tags and names (5+ letters), for typo matching. */
function toolVocabulary() {
    if (!vocabulary) {
        vocabulary = new Set();
        Object.entries(TOOL_GUIDE).forEach(([name, g]) => wordsOf(`${g.tags} ${name.replace(/_/g, " ")}`).forEach((w) => { if (w.length >= 5) vocabulary.add(w); }));
    }
    return vocabulary;
}

/**
 * The question's words plus the tag words they are a typo of ("summerize" -> "summarize", "qualatative" ->
 * "qualitative", "capabilitys" -> "capabilities"): one edit for 5-7 letters, two for longer words.
 */
function questionWords(question) {
    const words = new Set(wordsOf(question).filter((w) => !GENERIC_WORDS.has(w)));
    const vocab = toolVocabulary();
    Array.from(words).forEach((w) => {
        if (w.length < 5 || vocab.has(w)) return;
        const max = w.length >= 8 ? 2 : 1;
        vocab.forEach((v) => { if (editDistance(w, v, max) <= max) words.add(v); });
    });
    return words;
}

/** The allowed tools most relevant to a question, best first, always including a few core ones. */
export function selectTools(question, allowed, limit = 12, ctx = null) {
    const qWords = questionWords(question);
    const intentHits = Object.keys(INTENTS).filter((name) => INTENT_TOOLS[name] && hasIntent(question, name));
    const ents = ctx ? questionEntities(ctx, question) : null;
    const scored = allowed.map((name) => {
        const guide = TOOL_GUIDE[name] || {};
        let score = CORE_TOOLS.includes(name) ? 0.5 : 0;
        wordsOf(guide.tags).forEach((w) => { if (qWords.has(w)) score += 3; });
        wordsOf(name.replace(/_/g, " ")).forEach((w) => { if (qWords.has(w)) score += 2; });
        wordsOf(TOOLS[name] && TOOLS[name].description).forEach((w) => { if (w.length > 3 && qWords.has(w)) score += 1; });
        intentHits.forEach((intent) => { if (INTENT_TOOLS[intent].includes(name)) score += 3; });
        if (ents) {
            // Several named things at once (teams + a match + a metric) is a custom table / chart, not a fixed tool.
            const parts = (ents.teams.length ? 1 : 0) + (ents.match || ents.range ? 1 : 0) + (ents.metrics.length ? 1 : 0);
            if (parts >= 2 && (name === "make_table" || name === "make_chart")) score += 3 + 2 * parts;
            // Two or more named teams: compare the teams, not two metrics across the whole event.
            if (ents.teams.length >= 2 && name === "compare_teams") score += ents.match || ents.range ? 2 : 4;
            if (ents.teams.length >= 2 && name === "compare_metrics") score -= 12;
            if (ents.teams.length === 1 && ["team_overview", "team_matches", "team_percentiles"].includes(name)) score += 1;
        }
        return { name, score };
    }).sort((a, b) => b.score - a.score);
    const picked = scored.filter((s) => s.score >= 1).slice(0, limit).map((s) => s.name);
    ["make_table", "make_chart", "team_overview", "top_teams", "capabilities_help"].forEach((name) => {
        if (allowed.includes(name) && !picked.includes(name)) picked.push(name);
    });
    return picked.slice(0, limit);
}

/** One-line description of a tool for the model: when to use it and what the user sees. */
export function describeTool(name) {
    const guide = TOOL_GUIDE[name];
    const tool = TOOLS[name];
    return guide ? `${guide.use}. Shows: ${guide.shows}.` : (tool ? tool.description : name);
}

export function toolCatalog(ctx, allowed, names = null) {
    return (names || allowed).filter((name) => allowed.includes(name) && TOOLS[name]).map((name) => {
        const tool = TOOLS[name];
        return `- ${name}(${Object.keys(tool.params).join(", ")}): ${describeTool(name)}`;
    }).join("\n");
}

export function metricList(ctx, max = 80) {
    const custom = (ctx.metrics || []).map((m) => m.label);
    const standard = ["Total points", "Auto points", "Teleop points", "Endgame points", "EPA", "OPR", "xP", "Matches played", "Matches scouted", "Official rank", "Ranking score", "Record"];
    const all = Array.from(new Set([...custom, ...standard]));
    return all.slice(0, max).join(", ");
}

const intArg = { type: "integer" };
/** Union of every tool's arguments (kept for callers that need one schema; routing uses per-tool schemas). */
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
        matches: { type: "array", items: { type: "string" } },
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

/** Grammar for WebLLM routing: one branch per tool, each with only that tool's arguments (see tool-schemas.js). */
export function stepSchema(allowed, maxCalls = 4) {
    return perToolStepSchema(allowed, maxCalls);
}

/**
 * Cleans model-written arguments: "frc254" / "254" -> 254, "254, 1678" -> [254, 1678], "our team" -> our number,
 * "true" -> true, numeric match -> "12", "10-20" -> ["10-20"]. Returns the cleaned args and every team reference
 * that is not at this event (so the model can be told, instead of the team silently vanishing).
 */
export function coerceArgsDetailed(ctx, args) {
    const out = { ...(args || {}) };
    const unknownTeams = [];
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
        const resolved = v.map((x) => ({ raw: x, team: resolveTeam(x) }));
        resolved.forEach((r) => {
            if (!(Number.isFinite(r.team) && ctx.stats && ctx.stats.has(r.team)) && key !== "exclude") unknownTeams.push(String(r.raw));
        });
        out[key] = Array.from(new Set(resolved.map((r) => r.team).filter((x) => Number.isFinite(x) && ctx.stats && ctx.stats.has(x))));
    });
    ["columns", "metrics"].forEach((key) => {
        if (typeof out[key] === "string") out[key] = out[key].split(/\s*[,;]\s*|\s+and\s+/i).filter(Boolean);
    });
    if (out.matches !== undefined && out.matches !== null && out.matches !== "") {
        const list = Array.isArray(out.matches) ? out.matches : String(out.matches).split(/\s*(?:,|;|\band\b)\s*/i);
        out.matches = list.map((m) => String(m).trim()).filter(Boolean);
    }
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
    return { args: out, unknownTeams: Array.from(new Set(unknownTeams)) };
}

/** coerceArgs without the unknown-team report. Tools still validate everything themselves. */
export function coerceArgs(ctx, args) {
    return coerceArgsDetailed(ctx, args).args;
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
 * - Gemma 4 native calls: <|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>
 * With { withRejected: true } it returns { calls, rejected } where rejected lists calls naming a tool that is not
 * offered, or whose arguments could not be read (so the model can be told and retry).
 */
export function parseToolCalls(text, allowed, { withRejected = false } = {}) {
    const rejected = [];
    const result = (calls) => (withRejected ? { calls, rejected } : calls);
    if (!text) return result([]);
    const isAllowed = (name) => name && (!allowed || allowed.includes(name)) && TOOLS[name];

    // Gemma 4 native syntax takes priority: it is what the model was trained on.
    if (/call:[A-Za-z_]\w*\s*\{/.test(String(text))) {
        const native = parseGemmaToolCalls(text);
        const ok = [];
        native.forEach((c) => {
            if (c.malformed) rejected.push({ tool: c.tool, reason: "its arguments could not be read" });
            else if (!isAllowed(c.tool)) rejected.push({ tool: c.tool, reason: TOOLS[c.tool] ? "it is not offered for this question" : "there is no such tool" });
            else ok.push({ tool: c.tool, args: c.args });
        });
        if (ok.length || rejected.length) return result(ok);
    }
    const calls = [];

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
        const toolName = obj.tool || obj.name || (obj.function && obj.function.name);
        let toolArgs = obj.args || obj.arguments || (obj.function && obj.function.arguments) || {};
        if (typeof toolArgs === "string") {
            try { toolArgs = JSON.parse(toolArgs); } catch (_) {}
        }
        if (toolName && isAllowed(toolName)) {
            calls.push({ tool: toolName, args: typeof toolArgs === "object" && toolArgs !== null ? toolArgs : {} });
        } else if (typeof toolName === "string" && toolName) {
            rejected.push({ tool: toolName, reason: TOOLS[toolName] ? "it is not offered for this question" : "there is no such tool" });
        }
    };

    const clean = String(text).replace(/```(?:json)?\s*([\s\S]*?)\s*```/gi, "$1").replace(/<tool_call>([\s\S]*?)<\/tool_call>/gi, "$1").trim();

    try {
        const parsed = JSON.parse(clean);
        ingest(parsed);
        if (calls.length > 0 || rejected.length > 0) return result(calls);
    } catch (_) { /* fall through to balanced block extractor */ }

    let i = 0;
    while (i < clean.length) {
        const startBrace = clean.indexOf("{", i);
        const startBracket = clean.indexOf("[", i);
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

        for (let j = start; j < clean.length; j++) {
            if (clean[j] === openChar) depth++;
            else if (clean[j] === closeChar) {
                depth--;
                if (depth === 0) {
                    end = j;
                    break;
                }
            }
        }

        if (end > start) {
            try {
                const subObj = JSON.parse(clean.slice(start, end + 1));
                ingest(subObj);
            } catch (_) { /* continue */ }
            i = end + 1;
        } else {
            i = start + 1;
        }
    }

    return result(calls);
}

/** Worked examples for the shortlisted tools (composite ones first, since those are hardest for small models). */
function examplesFor(names, limit, native) {
    const out = [];
    names.forEach((name) => {
        const g = TOOL_GUIDE[name];
        if (!g) return;
        [...(g.examples || []), ...(g.example ? [g.example] : [])].forEach((ex) => {
            out.push(native ? `"${ex[0]}" -> ${name} ${JSON.stringify(ex[1])}` : `"${ex[0]}" -> ${JSON.stringify({ calls: [{ tool: name, args: ex[1] }] })}`);
        });
    });
    return out.slice(0, limit);
}

/** The teams, match and metrics a question names, already resolved, so the model copies them instead of guessing. */
export function entitiesNote(ctx, question) {
    const e = questionEntities(ctx, question);
    const parts = [];
    if (e.teams.length) parts.push(`teams ${e.teams.map((t) => (t.name ? `${t.number} (${t.name})` : String(t.number))).join(", ")}`);
    if (e.match) parts.push(`match "${e.match.ref}" = ${e.match.label} (red ${e.match.red.join(", ")}; blue ${e.match.blue.join(", ")})`);
    if (e.range) parts.push(`matches "${e.range}"`);
    if (e.metrics.length) parts.push(`metrics ${e.metrics.map((m) => `"${m}"`).join(", ")}`);
    return parts.length ? `The latest question mentions: ${parts.join("; ")}.` : "";
}

function teamNumbers(ctx) {
    const nums = Array.from(ctx.stats ? ctx.stats.keys() : []).sort((a, b) => a - b);
    return { count: nums.length, text: nums.length > 120 ? `${nums.slice(0, 120).join(", ")}, ...` : nums.join(", ") };
}

/**
 * System prompt for the routing step. Only a per-question shortlist of tools is offered.
 * native: the tools are passed to the chat template as function declarations (Gemma 4), so this prompt only explains
 * how to use them; otherwise the shortlist is listed here and the reply is JSON {"calls":[...]}.
 * Returns the prompt text; routerTools() gives the shortlist itself.
 */
export function routerSystemPrompt(ctx, allowed, maxCalls, { compact = false, question = "", include = [], native = false, limit = 10 } = {}) {
    const ours = ctx.ourTeam || "unknown";
    const entities = entitiesNote(ctx, question);
    const jsonFormat = 'Reply with JSON only: {"calls":[{"tool":"<name>","args":{...}}]}, or {"calls":[]} when no scouting data is needed.';
    if (compact) {
        // Small prompt budget: six tools at most, and the reply format before the list so trimming never loses it.
        const few = routerTools(ctx, allowed, question, include, 6);
        return [
            "You choose data tools for a FIRST Robotics scouting assistant. The app runs them and shows the results.",
            jsonFormat,
            `Our team: ${ours}. Metrics: ${metricList(ctx, 30)}.`,
            entities,
            ...examplesFor(few, 2, false).map((e) => `Example: ${e}`),
            "Tools:",
            toolCatalog(ctx, allowed, few)
        ].filter(Boolean).join("\n");
    }
    const names = routerTools(ctx, allowed, question, include, limit);
    const teams = teamNumbers(ctx);
    const builder = names.includes("make_table") || names.includes("make_chart");
    return [
        "You choose data tools for the ObsidianScout scouting assistant (FIRST Robotics). The app runs the tools you call and draws their tables and charts; a later step writes the answer from the results.",
        `Event: ${ctx.eventKey || "unknown"}. Our team: ${ours} ("we", "us", "our" mean team ${ours}).`,
        `Teams at the event (${teams.count}): ${teams.text}.`,
        `Metrics (exact names): ${metricList(ctx)}.`,
        entities,
        "",
        ...(native ? [] : ["TOOLS:", toolCatalog(ctx, allowed, names), ""]),
        "RULES:",
        `- Call the tool that fits the latest question. Make several calls (at most ${maxCalls}) only when it needs separate sets of data.`,
        "- Use team numbers and metric names exactly as listed. Add \"chart\": true when the user wants a chart, graph or plot.",
        "- One question can combine teams, matches and metrics (\"compare 254 and 1678 in match 10 on auto\"): use make_table or make_chart with all of them.",
        "- Earlier messages are context for words like \"them\" or \"that table\"; to change the table or chart on screen, call its tool again with the full new spec.",
        native
            ? "- If no scouting data is needed (greeting, small talk, a general question), or the results so far already answer the question, reply with a short plain sentence and call no tool."
            : "- If no scouting data is needed (greeting, small talk, a general question), or the results so far already answer the question, reply {\"calls\":[]}.",
        ...(native ? [] : [`- ${jsonFormat}`]),
        "",
        ...(builder ? [SPEC_GUIDE, ""] : []),
        "EXAMPLES:",
        ...examplesFor(names, 6, native)
    ].filter((line) => line !== null && line !== undefined).join("\n");
}

/** The shortlist offered for a question: tools on screen first, then the most relevant. */
export function routerTools(ctx, allowed, question, include = [], limit = 10) {
    return Array.from(new Set([...include.filter((n) => allowed.includes(n)), ...selectTools(question, allowed, limit, ctx)])).slice(0, limit + include.length);
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
