/**
 * What the user can see. Tables, charts and documents are drawn by code above each answer, so the model has to be
 * told exactly what is on screen (or it apologises for "not being able to make a table" that is already there).
 *   - normalizeChart: one chart shape for the renderer, so every chart a tool returns actually draws
 *   - displayManifest / historyDisplayNote: plain-language list of the visuals above an answer
 *   - digestFacts: tool facts sized to the model's budget; long lists are abbreviated with a count, never cut mid-way
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import { t } from "./shared.js";

// ------------------------------------------------------------------ card titles

const TOOL_TITLES = {
    team_overview: ["ai.card.team_overview", "Team overview"],
    top_teams: ["ai.card.top_teams", "Ranking"],
    epa_data: ["ai.card.top_teams", "Ranking"],
    opr_data: ["ai.card.top_teams", "Ranking"],
    xp_data: ["ai.card.top_teams", "Ranking"],
    compare_teams: ["ai.card.compare", "Comparison"],
    compare_metrics: ["ai.card.compare_metrics", "Metric comparison"],
    compare_all_teams: ["ai.card.compare_all", "All-team comparison"],
    team_matches: ["ai.card.team_matches", "Match-by-match scores"],
    match_by_match: ["ai.card.team_matches", "Match-by-match scores"],
    metric_trend: ["ai.card.trend", "Match-by-match trend"],
    scatter: ["ai.card.scatter", "Scatter plot"],
    stacked_breakdown: ["ai.card.stacked", "Scoring phase breakdown"],
    team_radar: ["ai.card.radar", "Team radar profile"],
    score_distribution: ["ai.card.distribution", "Score distribution"],
    match_preview: ["ai.card.match_preview", "Match preview"],
    pick_candidates: ["ai.card.picks", "Pick candidates"],
    projected_rankings: ["ai.card.projected", "Projected rankings"],
    current_rankings: ["ai.card.rankings", "Current rankings"],
    all_matches: ["ai.card.schedule", "Match schedule"],
    all_teams: ["ai.card.teams", "Teams at this event"],
    match_predictions: ["ai.card.predictions", "Match predictions"],
    create_strategy_brief: ["ai.card.strategy", "Strategy brief"],
    create_alliance_sheet: ["ai.card.alliance_sheet", "Alliance selection worksheet"],
    create_team_dossier: ["ai.card.dossier", "Team dossier"],
    create_artifact: ["ai.card.artifact", "Document"],
    filter_teams: ["ai.card.filter", "Matching teams"],
    team_schedule: ["ai.card.team_schedule", "Team schedule"],
    head_to_head: ["ai.card.head_to_head", "Head to head"],
    consistency: ["ai.card.consistency", "Consistency"],
    recent_form: ["ai.card.recent_form", "Recent form"],
    alliance_builder: ["ai.card.alliance_builder", "Alliance builder"],
    metric_summary: ["ai.card.metric_summary", "Event statistics"],
    team_percentiles: ["ai.card.percentiles", "Percentiles"],
    search_notes: ["ai.card.search_notes", "Scout notes search"],
    pit_search: ["ai.card.pit_search", "Pit scouting search"],
    list_metrics: ["ai.card.metrics", "Available metrics"],
    event_summary: ["ai.card.event_summary", "Event summary"],
    scouting_coverage: ["ai.card.coverage", "Scouting coverage"],
    make_table: ["ai.card.custom_table", "Custom table"],
    make_chart: ["ai.card.custom_chart", "Custom chart"]
};

export function toolTitle(name) {
    const entry = TOOL_TITLES[name];
    return entry ? t(entry[0], entry[1]) : t("ai.card.data", "Data");
}

// ------------------------------------------------------------------ charts

const CHART_NAMES = {
    bar: "bar chart", groupedBar: "grouped bar chart", stackedBar: "stacked bar chart", line: "line chart",
    scatter: "scatter plot", radar: "radar chart", scatterpolar: "radar chart", box: "box plot", pie: "pie chart", donut: "donut chart"
};

const lengthOf = (a) => (Array.isArray(a) ? a.length : 0);

/** Brings every chart to one shape ({type, series:[{name, y|r}], x?}); returns null when there is nothing to draw. */
export function normalizeChart(spec) {
    if (!spec || typeof spec !== "object") return null;
    const chart = { ...spec, type: spec.type || "bar" };
    const pieLike = chart.type === "pie" || chart.type === "donut";
    // Some tools return a single {x, y}; the renderer draws bars, lines and boxes from series only.
    if (!pieLike && chart.type !== "scatter" && !lengthOf(chart.series) && lengthOf(chart.y)) {
        chart.series = [{ name: chart.yTitle || chart.title || "", y: chart.y }];
        delete chart.y;
    }
    const points = pieLike
        ? lengthOf(chart.values || (chart.series && chart.series[0] && chart.series[0].y))
        : chart.type === "scatter"
            ? lengthOf(chart.points) || lengthOf(chart.y) || Math.max(0, ...(chart.series || []).map((s) => lengthOf(s.y)))
            : Math.max(0, ...(chart.series || []).map((s) => lengthOf(s.y) || lengthOf(s.r)));
    return points > 0 ? chart : null;
}

function quote(text) {
    return `"${String(text || "").replace(/"/g, "'").slice(0, 90)}"`;
}

function seriesNames(chart) {
    const names = (chart.series || []).map((s) => s.name).filter(Boolean).map(String);
    if (!names.length) return "";
    return names.length > 6 ? `${names.slice(0, 6).join(", ")}, +${names.length - 6} more` : names.join(", ");
}

export function describeChart(chart) {
    const c = normalizeChart(chart);
    if (!c) return "";
    const kind = CHART_NAMES[c.type] || "chart";
    const series = c.series || [];
    const axes = [c.xTitle && `x: ${c.xTitle}`, c.yTitle && `y: ${c.yTitle}`].filter(Boolean).join(", ");
    const categories = lengthOf(c.x) || Math.max(0, ...series.map((s) => lengthOf(s.x) || lengthOf(s.y)));
    let shape;
    switch (c.type) {
        case "scatter":
            shape = `${lengthOf(c.points) || categories} points`;
            break;
        case "line":
            shape = `${series.length} line${series.length === 1 ? "" : "s"} (${seriesNames(c)}) over ${categories} matches`;
            break;
        case "radar":
        case "scatterpolar":
            shape = `${series.length} team${series.length === 1 ? "" : "s"} (${seriesNames(c)}) across ${lengthOf(c.theta) || lengthOf(series[0] && series[0].theta)} axes`;
            break;
        case "box":
            shape = `${series.length} boxes (${seriesNames(c)})`;
            break;
        case "stackedBar":
            shape = `${categories} bars, each split into ${seriesNames(c)}`;
            break;
        case "pie":
        case "donut":
            shape = `${lengthOf(c.labels || c.x)} slices`;
            break;
        default:
            shape = series.length > 1
                ? `${series.length} series (${seriesNames(c)}) across ${categories} categories`
                : `${categories} bars`;
    }
    return `${kind} ${quote(c.title)} - ${shape}${axes ? ` [${axes}]` : ""}`;
}

// ------------------------------------------------------------------ tables, documents, manifest

function rowLabels(table) {
    const cols = (table.columns || []).map((c) => String(c).toLowerCase());
    let idx = cols.findIndex((c) => c === "team" || c === t("ai.col.team", "Team").toLowerCase());
    if (idx < 0) idx = cols.findIndex((c) => c !== "#" && c !== "rank");
    if (idx < 0) idx = 0;
    const labels = table.rows.map((r) => String(r[idx] ?? "")).filter(Boolean);
    if (labels.length <= 4) return labels.join(", ");
    return `${labels.slice(0, 3).join(", ")} ... ${labels[labels.length - 1]}`;
}

export function describeTable(title, table, teamCount) {
    if (!table || !lengthOf(table.rows)) return "";
    const rows = table.rows.length;
    const cols = table.columns || [];
    const hasTeamCol = cols.some((c) => /team|equipo|takım|קבוצה/i.test(String(c)));
    const everyTeam = hasTeamCol && teamCount && rows >= teamCount ? " (every team at the event)" : "";
    return `table ${quote(title)} - all ${rows} row${rows === 1 ? "" : "s"}${everyTeam}; columns: ${cols.join(" | ")}; rows: ${rowLabels(table)}`;
}

export function describeArtifact(artifact) {
    if (!artifact) return "";
    return `document ${quote(artifact.title)} (${artifact.type || "report"}) - opens with the "Open Artifact" button and holds the full content`;
}

/** Lines describing one tool result (or one saved display entry from an earlier reply). */
export function describeVisuals(item, teamCount) {
    if (!item) return [];
    const title = item.title || (item.facts && item.facts.title) || toolTitle(item.tool);
    return [
        item.artifact && describeArtifact(item.artifact),
        item.table && describeTable(title, item.table, teamCount),
        item.chart && describeChart(item.chart)
    ].filter(Boolean);
}

/** Numbered list of everything drawn above the answer; "" when nothing is shown. */
export function displayManifest(results, ctx) {
    const teamCount = ctx && ctx.stats ? ctx.stats.size : 0;
    const lines = [];
    results.forEach((r) => {
        if (r.facts && r.facts.error) return;
        describeVisuals(r, teamCount).forEach((line) => lines.push(`${lines.length + 1}. ${line} (from ${r.tool})`));
    });
    return lines.join("\n");
}

/** Short note for conversation history, so follow-ups know what an earlier reply showed. */
export function historyDisplayNote(display, maxChars = 220) {
    if (!Array.isArray(display) || !display.length) return "";
    const parts = [];
    display.forEach((d) => {
        const title = d.title || toolTitle(d.tool);
        if (d.table && lengthOf(d.table.rows)) parts.push(`table ${quote(title)} (${d.table.rows.length} rows)`);
        if (d.chart) {
            const c = normalizeChart(d.chart);
            if (c) parts.push(`${CHART_NAMES[c.type] || "chart"} ${quote(c.title)}`);
        }
        if (d.artifact) parts.push(`document ${quote(d.artifact.title)}`);
    });
    if (!parts.length) return "";
    const note = `[Shown with this reply: ${parts.join("; ")}]`;
    return note.length > maxChars ? `${note.slice(0, maxChars - 4)}...]` : note;
}

// ------------------------------------------------------------------ facts digest

function abbreviate(value, maxItems, inTable, depth) {
    if (Array.isArray(value)) {
        const kept = value.slice(0, maxItems).map((x) => abbreviate(x, maxItems, inTable, depth + 1));
        if (value.length > maxItems) {
            kept.push(`(+${value.length - maxItems} more; ${inTable ? "every one is listed in the table shown above" : "left out here for space"})`);
        }
        return kept;
    }
    if (value && typeof value === "object") {
        const entries = Object.entries(value).filter(([, x]) => x !== undefined && x !== null && x !== "");
        const limit = depth === 0 ? Infinity : Math.max(6, maxItems * 2);
        const out = {};
        entries.slice(0, limit).forEach(([k, x]) => { out[k] = abbreviate(x, maxItems, inTable, depth + 1); });
        if (entries.length > limit) out._more = `+${entries.length - limit} more fields${inTable ? " (in the table shown above)" : ""}`;
        return out;
    }
    if (typeof value === "string" && value.length > 500) return `${value.slice(0, 497)}...`;
    return value;
}

function digestOne(result, maxItems) {
    const inTable = !!(result.table && lengthOf(result.table.rows));
    const args = Object.fromEntries(Object.entries(result.args || {}).filter(([, v]) => v !== undefined && v !== null && v !== ""));
    return `### ${result.tool}(${JSON.stringify(args)})\n${JSON.stringify(abbreviate(result.facts || {}, maxItems, inTable, 0))}`;
}

/**
 * Tool facts as text within budgetChars. Lists shrink step by step (with an explicit "+N more" marker) until
 * everything fits, so the model never sees a silently truncated list and concludes the data is incomplete.
 */
export function digestFacts(results, budgetChars) {
    if (!results || !results.length) return "";
    for (const maxItems of [40, 25, 15, 10, 6, 4, 2]) {
        const text = results.map((r) => digestOne(r, maxItems)).join("\n\n");
        if (text.length <= budgetChars) return text;
    }
    const share = Math.max(160, Math.floor(budgetChars / results.length) - 2);
    return results.map((r) => {
        const s = digestOne(r, 2);
        return s.length > share ? `${s.slice(0, share - 3)}...` : s;
    }).join("\n\n");
}
