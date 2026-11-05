/**
 * Per-tool argument schemas and validation.
 *
 * Every tool gets its own JSON schema (types, enums, required fields), built from the tool's documented params. They are
 * used three ways:
 *   - toolDeclarations(): the native function declarations Gemma 4 reads through its chat template;
 *   - stepSchema(): a grammar for WebLLM's constrained decoding (one branch per tool, so the model cannot send another
 *     tool's arguments);
 *   - validateCall(): checks a call against the schema AND the event (teams that are here, metrics that exist, matches
 *     in the schedule). Transformers.js has no grammar-constrained decoding, so this check plus a repair turn is what
 *     keeps the Gemma tiers' arguments valid.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
import { resolveColumn } from "./builder.js";
import { TOOLS } from "./tools.js";

const INT = { type: "integer" };
const STR = { type: "string" };
const BOOL = { type: "boolean" };
const TEAM_LIST = { type: "array", items: INT };
const STR_LIST = { type: "array", items: STR };
const CONDITIONS = {
    type: "array",
    items: { type: "object", properties: { column: STR, op: { type: "string", enum: [">", ">=", "<", "<=", "=", "!="] }, value: { type: "number" } }, required: ["column", "op", "value"] }
};

/** Type of each parameter name used by any tool. */
const PARAM_TYPES = {
    team: INT, teams: TEAM_LIST, opponents: TEAM_LIST, exclude: TEAM_LIST,
    columns: STR_LIST, metrics: STR_LIST, conditions: CONDITIONS, matches: STR_LIST, fields: STR_LIST,
    limit: INT, n: INT, last: INT,
    rows: { type: "string", enum: ["teams", "matches"] }, by: { type: "string", enum: ["team", "match"] },
    x: STR, metric: STR, metric_1: STR, metric_2: STR, metric_x: STR, metric_y: STR, sort_by: STR, focus: STR,
    order: { type: "string", enum: ["desc", "asc"] }, match: STR, query: STR, title: STR, markdown: STR, expression: STR, topic: STR,
    chart: BOOL, document: BOOL, unplayed_only: BOOL,
    chart_type: { type: "string", enum: ["bar", "line"] },
    type: STR, order_by: { type: "string", enum: ["team_number", "rank", "epa", "xp"] }
};

/** Tool-specific overrides of PARAM_TYPES. */
const OVERRIDES = {
    consistency: { order: { type: "string", enum: ["most", "least"] } },
    recent_form: { order: { type: "string", enum: ["hot", "cold"] } },
    make_chart: { type: { type: "string", enum: ["bar", "stackedBar", "line", "scatter", "radar", "box", "pie"] } },
    create_artifact: { type: { type: "string", enum: ["strategy", "worksheet", "dossier", "report"] } }
};

/** Arguments a tool cannot do without. Tools that default sensibly (our team, all teams, the next match) need none. */
const REQUIRED = {
    team_overview: ["team"], team_matches: ["team"], create_team_dossier: ["team"], summarize_notes: ["team"],
    compare_teams: ["teams"], head_to_head: ["teams"], alliance_builder: ["teams"], team_radar: ["teams"],
    match_preview: ["match"], match_reports: ["match"], compare_metrics: ["metric_1", "metric_2"], scatter: ["metric_x", "metric_y"],
    search_notes: ["query"], filter_teams: ["conditions"], calculate: ["expression"], read_docs: ["topic"]
};

/** Limits on list lengths the tools enforce. */
const LIST_LIMITS = { compare_teams: { teams: [2, 6] }, head_to_head: { teams: [2, 2] }, alliance_builder: { teams: [2, 3], opponents: [0, 3] }, team_radar: { teams: [1, 4] }, metric_trend: { teams: [1, 6] } };

const METRIC_PARAMS = ["metric", "metric_1", "metric_2", "metric_x", "metric_y", "focus", "sort_by", "x"];
const COLUMN_PARAMS = ["columns", "metrics"];
const TEAM_PARAMS = ["team", "teams", "opponents", "exclude"];
// External ratings people ask for by name even when the event has no data for them; the tools explain that themselves.
const EXTERNAL = /\b(?:epa|opr|xp|exp|statbotics|match ?13)\b/i;

function paramSchema(tool, name) {
    const base = (OVERRIDES[tool] && OVERRIDES[tool][name]) || PARAM_TYPES[name] || STR;
    const description = TOOLS[tool] && TOOLS[tool].params ? TOOLS[tool].params[name] : undefined;
    return description ? { ...base, description: String(description) } : { ...base };
}

/** JSON schema of one tool's arguments. */
export function toolSchema(name) {
    const tool = TOOLS[name];
    if (!tool) return null;
    const properties = {};
    Object.keys(tool.params || {}).forEach((p) => { properties[p] = paramSchema(name, p); });
    const required = (REQUIRED[name] || []).filter((p) => properties[p]);
    return { type: "object", properties, ...(required.length ? { required } : {}) };
}

/** Native function declarations for the chat template (OpenAI tools shape, which Gemma 4's template reads). */
export function toolDeclarations(names, describe = (name) => TOOLS[name] && TOOLS[name].description) {
    return names.filter((name) => TOOLS[name]).map((name) => ({
        type: "function",
        function: { name, description: describe(name) || name, parameters: toolSchema(name) }
    }));
}

/**
 * Grammar for WebLLM routing: {"calls":[...]} where each call is exactly one tool with that tool's own arguments.
 * An empty list means no data is needed.
 */
export function stepSchema(allowed, maxCalls = 4) {
    const branches = allowed.filter((name) => TOOLS[name]).map((name) => ({
        type: "object",
        properties: { tool: { type: "string", enum: [name] }, args: toolSchema(name) },
        required: ["tool", "args"]
    }));
    return {
        type: "object",
        properties: { calls: { type: "array", items: { anyOf: branches }, maxItems: Math.max(1, maxCalls) } },
        required: ["calls"]
    };
}

const typeOk = (schema, v) => {
    switch (schema.type) {
        case "integer": return Number.isInteger(v);
        case "number": return typeof v === "number" && Number.isFinite(v);
        case "boolean": return typeof v === "boolean";
        case "string": return typeof v === "string";
        case "array": return Array.isArray(v);
        case "object": return v && typeof v === "object" && !Array.isArray(v);
        default: return true;
    }
};

const RANGE = /^\s*(?:qm|q|match)?\s*(\d{1,3})\s*(?:-|to|–)\s*(?:qm|q|match)?\s*(\d{1,3})\s*$/i;

/** Every match a "matches" entry refers to ("12", "QM 12", "10-20"). Empty when none exist. */
export function resolveMatchRefs(ctx, refs) {
    const out = [];
    (Array.isArray(refs) ? refs : [refs]).forEach((ref) => {
        const range = RANGE.exec(String(ref));
        if (range) {
            const [a, b] = [Number(range[1]), Number(range[2])].sort((x, y) => x - y);
            ctx.matches.filter((m) => m.compLevel === "qm" && m.matchNumber >= a && m.matchNumber <= b).forEach((m) => out.push(m));
            return;
        }
        const m = Data.findMatch(ctx, ref);
        if (m) out.push(m);
    });
    return Array.from(new Set(out));
}

function metricOptions(ctx) {
    return ctx.metrics.map((m) => m.label).slice(0, 40).join(", ");
}

function teamOptions(ctx) {
    const nums = Array.from(ctx.stats.keys()).sort((a, b) => a - b);
    return nums.length > 70 ? `${nums.slice(0, 70).join(", ")}, ...` : nums.join(", ");
}

/**
 * Checks one call (arguments already cleaned by coerceArgs). Returns { args, errors }: args has unknown parameters
 * removed and enum values case-corrected; errors are short, fixable messages for the model ("" when valid).
 * unknownTeams are team references coerceArgs could not resolve.
 */
export function validateCall(ctx, name, args, { unknownTeams = [] } = {}) {
    const errors = [];
    const schema = toolSchema(name);
    if (!schema) return { args: {}, errors: [`There is no tool named "${name}".`] };
    const out = {};
    Object.entries(args || {}).forEach(([key, value]) => {
        if (value === undefined || value === null || value === "") return;
        const ps = schema.properties[key];
        if (!ps) {
            // Other unknown parameters are dropped silently, but dropping teams would quietly show every team instead
            // of the ones asked for (e.g. a scatter "of 757 and 4646" drawn for the whole event).
            const teams = key === "team" || key === "teams" ? (Array.isArray(value) ? value : [value]).filter((x) => x !== "" && x !== null) : [];
            if (teams.length) {
                const takesTeams = schema.properties.team ? "team" : (schema.properties.teams ? "teams" : null);
                errors.push(takesTeams
                    ? `${name} takes "${takesTeams}", not "${key}".`
                    : `${name} cannot limit to specific teams, so ${teams.join(", ")} would be ignored. Use make_chart (any chart type, with "teams"), make_table or compare_teams instead.`);
            }
            return;
        }
        let v = value;
        if (ps.enum && typeof v === "string") {
            const hit = ps.enum.find((e) => e.toLowerCase() === v.toLowerCase());
            if (hit) v = hit;
            else { errors.push(`${key} must be one of: ${ps.enum.join(", ")} (got "${v}").`); return; }
        }
        // make_table chart may name a chart type; anything else must match the declared type.
        const chartType = key === "chart" && typeof v === "string";
        if (!chartType && !typeOk(ps, v)) {
            errors.push(`${key} must be ${ps.type === "array" ? `a list of ${ps.items.type}s` : `a${ps.type === "integer" ? "n" : ""} ${ps.type}`}.`);
            return;
        }
        out[key] = v;
    });

    (schema.required || []).forEach((key) => {
        if (out[key] === undefined || (Array.isArray(out[key]) && !out[key].length)) errors.push(`${key} is required.`);
    });

    if (unknownTeams.length) {
        errors.push(`Team${unknownTeams.length > 1 ? "s" : ""} ${unknownTeams.join(", ")} ${unknownTeams.length > 1 ? "are" : "is"} not at this event. Teams here: ${teamOptions(ctx)}.`);
    }
    TEAM_PARAMS.forEach((key) => {
        if (key === "team" && out.team !== undefined && !ctx.stats.has(out.team)) {
            errors.push(`Team ${out.team} is not at this event. Teams here: ${teamOptions(ctx)}.`);
        }
    });
    const limits = LIST_LIMITS[name] || {};
    Object.entries(limits).forEach(([key, [lo, hi]]) => {
        const len = Array.isArray(out[key]) ? out[key].length : 0;
        if (out[key] !== undefined && (len < lo || len > hi)) errors.push(`${key} needs ${lo === hi ? lo : `${lo}-${hi}`} team numbers (got ${len}).`);
    });

    METRIC_PARAMS.forEach((key) => {
        const v = out[key];
        if (typeof v !== "string" || EXTERNAL.test(v)) return;
        if (!resolveColumn(ctx, v)) errors.push(`${key} "${v}" is not a metric. Metrics: ${metricOptions(ctx)}.`);
    });
    COLUMN_PARAMS.forEach((key) => {
        if (!Array.isArray(out[key])) return;
        const bad = out[key].filter((c) => typeof c === "string" && !EXTERNAL.test(c) && !resolveColumn(ctx, c));
        if (bad.length) errors.push(`Unknown ${key}: ${bad.join(", ")}. Use metric names (${metricOptions(ctx)}), "max <metric>", "min <metric>", "stdev <metric>", "Matches scouted", "Official rank", or arithmetic like "EPA - xP".`);
    });
    if (Array.isArray(out.conditions)) {
        out.conditions.forEach((c) => {
            const col = c && (c.column || c.metric);
            if (!col || !Number.isFinite(Number(c.value))) errors.push("Each condition needs column, op and a numeric value.");
            else if (!EXTERNAL.test(col) && !resolveColumn(ctx, col)) errors.push(`Condition column "${col}" is not a metric.`);
        });
    }

    if (typeof out.match === "string") {
        const isNext = /^\s*next\s*$/i.test(out.match);
        if (!Data.findMatch(ctx, out.match)) {
            errors.push(isNext ? "Our team has no upcoming match in the schedule." : `Match "${out.match}" is not in the schedule (${ctx.matches.length} matches; use a number like "12").`);
        }
    }
    if (Array.isArray(out.matches)) {
        const bad = out.matches.filter((ref) => !resolveMatchRefs(ctx, [ref]).length);
        if (bad.length) errors.push(`Matches not in the schedule: ${bad.join(", ")}.`);
    }
    return { args: out, errors };
}

const api = { toolSchema, toolDeclarations, stepSchema, validateCall, resolveMatchRefs };
export default api;
