/**
 * General table and chart builder. The model (or the rule router) describes WHAT to show as a small spec and this
 * code builds it from the scouting data, so every number on screen is exact and any combination can be asked for.
 *
 *   make_table {teams, exclude, match, conditions, columns, sort_by, order, limit, rows: "teams"|"matches", chart, document, title}
 *   make_chart {type, teams, exclude, match, conditions, metrics, x, by: "team"|"match", sort_by, order, limit, title}
 *
 * A column / metric is any metric name, "max <metric>", "min <metric>", "stdev <metric>", "matches scouted",
 * "matches played", "official rank", "record", "ranking score", or arithmetic such as "EPA - xP" or "(Auto + Teleop) / 2".
 *
 * Also here: specFromQuestion (keyword draft for a new request), editSpec (follow-ups such as "remove OPR from that
 * table", done in code) and specFromDisplay (turns any earlier table into an editable spec).
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
import { evaluate, r1, t, toTeamNumbers } from "./shared.js";

// ------------------------------------------------------------------ text helpers

const norm = (text) => String(text || "").toLocaleLowerCase().replace(/[^\p{L}\p{N}%+ ]+/gu, " ").replace(/\s+/g, " ").trim();
const clone = (o) => JSON.parse(JSON.stringify(o || {}));
const compact = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== null && !(Array.isArray(v) && !v.length)));
// Aliases too vague to count as a column on their own ("points" in "most points" is fine as a metric, not as a column hint).
const VAGUE_ALIASES = new Set(["points", "point", "score", "scoring", "overall", "puan", "puntos", "puntaje", "נקודות", "סך", "data", "match 13"]);

/** Every metric named in a text, in order of mention, with the character spans they cover. */
export function findMetricsInText(ctx, text) {
    const padded = ` ${norm(text)} `;
    const spans = [];
    ctx.metrics.forEach((metric) => {
        (metric.aliases || [metric.label.toLowerCase()]).forEach((alias) => {
            const a = norm(alias);
            if (!a || VAGUE_ALIASES.has(a)) return;
            let idx = padded.indexOf(` ${a} `);
            while (idx >= 0) {
                spans.push({ start: idx + 1, end: idx + 1 + a.length, metric });
                idx = padded.indexOf(` ${a} `, idx + 1);
            }
        });
    });
    spans.sort((x, y) => (y.end - y.start) - (x.end - x.start) || x.start - y.start);
    const taken = [];
    spans.forEach((s) => { if (!taken.some((o) => s.start < o.end && o.start < s.end)) taken.push(s); });
    taken.sort((a, b) => a.start - b.start);
    const metrics = [];
    taken.forEach((s) => { if (!metrics.includes(s.metric)) metrics.push(s.metric); });
    return { metrics, spans: taken, text: padded };
}

// ------------------------------------------------------------------ columns

const SPECIALS = [
    { key: "scouted", label: "Matches scouted", re: /\b(?:matches scouted|scouted matches|scouting reports|reports|times scouted|scouted count)\b/i, value: (s) => (s ? s.matchesScouted : null) },
    { key: "played", label: "Matches played", re: /\b(?:matches played|played matches|games played)\b/i, value: (s) => (s ? s.matchesPlayed : null) },
    { key: "rank", label: "Official rank", re: /\b(?:official rank(?:ing)?|current rank(?:ing)?|standings?|event rank)\b/i, value: (s, tm) => numOrNull(tm.rank ?? tm.ranking) },
    { key: "rs", label: "Ranking score", re: /\b(?:ranking score|ranking points|rs)\b/i, value: (s, tm) => numOrNull(tm.rankingScore ?? tm.sortOrder1 ?? tm.rs) },
    { key: "record", label: "Record", re: /\b(?:record|w-l-t|win loss)\b/i, text: true, value: (s, tm) => (tm.record ? `${tm.record.wins || 0}-${tm.record.losses || 0}-${tm.record.ties || 0}` : (tm.recordStr || null)) }
];
function numOrNull(v) {
    const n = Number(v);
    return v === null || v === undefined || v === "" || !Number.isFinite(n) || n === 999 ? null : n;
}

const STATS = [
    { stat: "max", re: /^(?:max(?:imum)?|highest|best|peak|top)\s+(?:of\s+)?/i, label: "max" },
    { stat: "min", re: /^(?:min(?:imum)?|lowest|worst)\s+(?:of\s+)?/i, label: "min" },
    { stat: "stdev", re: /^(?:std ?dev|stdev|standard deviation|spread|consistency)\s+(?:of\s+|in\s+)?/i, label: "std dev" },
    { stat: "n", re: /^(?:count|number of matches|samples)\s+(?:of\s+|for\s+)?/i, label: "N" },
    { stat: "avg", re: /^(?:avg|average|mean)\s+(?:of\s+)?/i, label: "" }
];

const OPERATOR = /[+*/]|\s-\s/;

/** Resolves one column description to {key, label, kind, ...}; null when nothing matches. */
export function resolveColumn(ctx, col) {
    if (col === null || col === undefined || col === "") return null;
    if (typeof col === "object") {
        const base = resolveColumn(ctx, col.expr || col.metric || col.column || col.label);
        if (!base) return null;
        if (col.stat && base.kind === "metric") {
            const st = STATS.find((s) => s.stat === String(col.stat).toLowerCase()) || STATS[4];
            base.stat = st.stat;
            base.label = st.label ? `${base.metric.label} (${st.label})` : base.metric.label;
            base.key = `${base.metric.id}:${base.stat}`;
        }
        if (col.label && typeof col.label === "string" && col.expr) base.label = col.label;
        return base;
    }
    const raw = String(col).trim().replace(/\s*\((?:avg|average)\)\s*$/i, "");
    if (!raw) return null;
    const exact = ctx.metrics.find((m) => m.label.toLowerCase() === raw.toLowerCase() || m.id === raw);
    if (exact) return metricColumn(exact, "avg");
    const special = SPECIALS.find((s) => s.re.test(raw) && norm(raw).split(" ").length <= 4);
    if (special) return { key: `special:${special.key}`, label: special.label, kind: "special", special };
    if (OPERATOR.test(raw)) {
        const expr = resolveExpression(ctx, raw);
        if (expr) return expr;
    }
    const statSuffix = raw.match(/^(.+?)\s*\((max|min|std dev|stdev|n)\)$/i);
    if (statSuffix) return resolveColumn(ctx, { metric: statSuffix[1], stat: statSuffix[2].replace(/\s/g, "").replace("stddev", "stdev").toLowerCase() });
    for (const st of STATS) {
        if (st.re.test(raw)) {
            const rest = raw.replace(st.re, "");
            const m = Data.findMetric(ctx, rest, { minScore: 1 });
            if (m) return resolveColumn(ctx, { metric: m.label, stat: st.stat });
        }
    }
    const found = findMetricsInText(ctx, raw).metrics;
    if (found.length === 1) return metricColumn(found[0], "avg");
    // Fuzzy only when every word of the phrase matches the metric ("climbing" ~ "Climb"), never on one shared stem ("warp drive" is not "Driver Rating").
    const fuzzy = Data.findMetric(ctx, raw, { minScore: 2 });
    return fuzzy && coversAll(fuzzy, raw) ? metricColumn(fuzzy, "avg") : null;
}

const FILLER = new Set(["the", "of", "avg", "average", "mean", "points", "point", "pts", "score", "scouted", "data", "rate", "%"]);
function coversAll(metric, raw) {
    const aliasWords = new Set(metric.aliases.flatMap((a) => norm(a).split(" ")));
    const stem = (w) => Array.from(aliasWords).some((a) => a === w || (a.length >= 4 && w.length >= 4 && (a.startsWith(w) || w.startsWith(a))));
    return norm(raw).split(" ").filter((w) => w && !FILLER.has(w)).every(stem);
}

function metricColumn(metric, stat) {
    return { key: `${metric.id}:${stat}`, label: metric.label, kind: "metric", metric, stat };
}

function resolveExpression(ctx, raw) {
    const parts = raw.split(/(\s-\s|[+*/])/);
    const operands = [];
    let template = "";
    for (const part of parts) {
        const piece = part.trim();
        if (!piece) continue;
        if (["+", "-", "*", "/"].includes(piece)) { template += ` ${piece} `; continue; }
        let core = piece;
        let pre = "";
        let post = "";
        const balance = (s) => (s.match(/\(/g) || []).length - (s.match(/\)/g) || []).length;
        while (core.startsWith("(") && balance(core) > 0) { pre += "("; core = core.slice(1).trim(); }
        while (core.endsWith(")") && balance(core) < 0) { post += ")"; core = core.slice(0, -1).trim(); }
        if (/^-?\d+(?:\.\d+)?$/.test(core)) {
            template += `${pre}${core}${post}`;
            continue;
        }
        const operand = resolveColumn(ctx, core);
        if (!operand || operand.kind === "expr" || (operand.special && operand.special.text)) return null;
        operands.push(operand);
        template += `${pre}{${operands.length - 1}}${post}`;
    }
    if (!operands.length) return null;
    let i = 0;
    const label = template.replace(/\{\d+\}/g, () => operands[i++].label).replace(/\s+/g, " ").trim();
    return { key: `expr:${label}`, label, kind: "expr", operands, template };
}

/** A team's value for a resolved column (number, text for Record, or null). */
export function columnValue(ctx, col, team) {
    const s = ctx.stats.get(team);
    const tm = ctx.teams.get(team) || {};
    if (col.kind === "metric") {
        const m = s && s.metrics[col.metric.id];
        const v = m ? m[col.stat || "avg"] : null;
        return v === null || v === undefined || !Number.isFinite(v) ? null : (col.stat === "n" ? v : r1(v));
    }
    if (col.kind === "special") return col.special.value(s, tm);
    if (col.kind === "expr") return evalExpr(col, col.operands.map((o) => columnValue(ctx, o, team)));
    return null;
}

/** A value for one match of a team (metrics and arithmetic only). */
function matchValue(col, p) {
    if (col.kind === "metric") {
        const v = p.values ? p.values[col.metric.id] : null;
        return v === null || v === undefined || !Number.isFinite(v) ? null : r1(v);
    }
    if (col.kind === "expr") return evalExpr(col, col.operands.map((o) => matchValue(o, p)));
    return null;
}

function evalExpr(col, values) {
    if (values.some((v) => typeof v !== "number" || !Number.isFinite(v))) return null;
    try {
        return r1(evaluate(col.template.replace(/\{(\d+)\}/g, (_, i) => `(${values[Number(i)]})`)));
    } catch (_) {
        return null;
    }
}

const sameColumn = (a, b) => a && b && a.key === b.key;

// ------------------------------------------------------------------ team selection

const OPS = { ">": (v, x) => v > x, ">=": (v, x) => v >= x, "<": (v, x) => v < x, "<=": (v, x) => v <= x, "=": (v, x) => v === x, "==": (v, x) => v === x, "!=": (v, x) => v !== x };

function selectTeams(ctx, spec) {
    let teams;
    let scope = "all teams";
    if (spec.match) {
        const m = Data.findMatch(ctx, spec.match);
        teams = m ? [...(m.redTeams || []), ...(m.blueTeams || [])].map(Data.teamNumberFromKey).filter((x) => x && ctx.stats.has(x)) : [];
        scope = m ? Data.matchLabel(m) : `match ${spec.match}`;
    } else if (Array.isArray(spec.teams) && spec.teams.length) {
        teams = toTeamNumbers(ctx, spec.teams);
        scope = `teams ${teams.join(", ")}`;
    } else {
        teams = Array.from(ctx.stats.keys());
    }
    const exclude = new Set(toTeamNumbers(ctx, spec.exclude || []));
    if (spec.exclude_our_team && ctx.ourTeam) exclude.add(ctx.ourTeam);
    teams = teams.filter((x) => !exclude.has(x));
    const conditions = (spec.conditions || []).map((c) => ({ col: resolveColumn(ctx, c.column || c.metric), op: OPS[c.op] ? c.op : ">", value: Number(c.value) }))
        .filter((c) => c.col && Number.isFinite(c.value));
    if (conditions.length) {
        teams = teams.filter((team) => conditions.every((c) => {
            const v = columnValue(ctx, c.col, team);
            return typeof v === "number" && OPS[c.op](v, c.value);
        }));
    }
    return { teams, scope, conditions: conditions.map((c) => `${c.col.label} ${c.op} ${c.value}`) };
}

function sortTeams(ctx, teams, sortCol, order, keepOrder) {
    if (!sortCol) return teams;
    if (keepOrder && !sortCol.explicit) return teams;
    const dir = order === "asc" ? 1 : -1;
    return teams.slice().sort((a, b) => {
        const va = columnValue(ctx, sortCol, a);
        const vb = columnValue(ctx, sortCol, b);
        const na = typeof va !== "number";
        const nb = typeof vb !== "number";
        if (na && nb) return a - b;
        if (na) return 1;
        if (nb) return -1;
        return dir * (va - vb) || a - b;
    });
}

function highlights(rows, cols, keyOf = (r) => r.team) {
    const out = {};
    cols.forEach((col) => {
        const vals = rows.map((r) => ({ who: keyOf(r), v: r[col.label] })).filter((x) => typeof x.v === "number");
        if (!vals.length) return;
        const hi = vals.reduce((a, b) => (b.v > a.v ? b : a));
        const lo = vals.reduce((a, b) => (b.v < a.v ? b : a));
        out[col.label] = {
            highest: { team: hi.who, value: hi.v }, lowest: { team: lo.who, value: lo.v },
            average: r1(vals.reduce((s, x) => s + x.v, 0) / vals.length), teams_with_data: vals.length
        };
    });
    return out;
}

function resolveColumns(ctx, list) {
    const cols = [];
    const unknown = [];
    (Array.isArray(list) ? list : (list ? [list] : [])).forEach((c) => {
        const col = resolveColumn(ctx, c);
        if (!col) unknown.push(typeof c === "object" ? JSON.stringify(c) : String(c));
        else if (!cols.some((x) => sameColumn(x, col))) cols.push(col);
    });
    return { cols, unknown };
}

function defaultColumns(ctx) {
    const ids = ["score_total", "score_auto", "score_teleop", "score_endgame", "ext:epa", "ext:opr", "ext:exp"];
    return ids.map((id) => ctx.metrics.find((m) => m.id === id)).filter(Boolean).map((m) => metricColumn(m, "avg"));
}

function phaseColumns(ctx) {
    return ctx.metrics.filter((m) => m.kind === "score" && m.id !== "score_total").map((m) => metricColumn(m, "avg"));
}

const fmtCell = (v) => (v === null || v === undefined ? "-" : v);
// External metrics people ask for by name even when the event has no data for them (or an admin turned them off).
const EXTERNAL_NAMES = [
    { label: "xP", id: "ext:exp", re: /\b(?:xp|exp|expected points|match ?13)\b/i },
    { label: "EPA", id: "ext:epa", re: /\b(?:epa|statbotics)\b/i },
    { label: "OPR", id: "ext:opr", re: /\b(?:opr|offensive power rating)\b/i }
];

const unknownNote = (unknown, ctx) => {
    if (!unknown.length) return undefined;
    const external = unknown.filter((u) => EXTERNAL_NAMES.some((e) => e.re.test(u)));
    const other = unknown.filter((u) => !external.includes(u));
    return [
        external.length ? `${external.join(", ")} has no data at this event (it may be turned off in admin settings), so it is left out.` : "",
        other.length ? `Could not find: ${other.join(", ")}. Available metrics: ${ctx.metrics.map((m) => m.label).slice(0, 30).join(", ")}.` : ""
    ].filter(Boolean).join(" ");
};

// ------------------------------------------------------------------ make_table

function tableTitle(cols, scope, limit, sortCol, order) {
    const names = cols.slice(0, 4).map((c) => c.label).join(", ") + (cols.length > 4 ? ", ..." : "");
    const which = limit && sortCol ? `${order === "asc" ? "bottom" : "top"} ${limit} by ${sortCol.label}` : scope;
    return `${names} - ${which}`;
}

function buildTable(ctx, args) {
    const spec = clone(args);
    let { cols, unknown } = resolveColumns(ctx, spec.columns || spec.metrics || spec.metric);
    if (!cols.length) {
        if (unknown.length) return { facts: { error: unknownNote(unknown, ctx) } };
        cols = defaultColumns(ctx);
    }
    const order = spec.order === "asc" ? "asc" : "desc";
    let sortCol = spec.sort_by ? resolveColumn(ctx, spec.sort_by) : null;
    if (sortCol) {
        sortCol.explicit = true;
        if (!cols.some((c) => sameColumn(c, sortCol))) cols.push(sortCol);
    } else {
        sortCol = cols.find((c) => !(c.special && c.special.text));
    }
    const { teams: selected, scope, conditions } = selectTeams(ctx, spec);
    const limit = spec.limit || spec.n ? Math.max(1, parseInt(spec.limit || spec.n, 10)) : null;

    if (spec.rows === "matches") {
        const teams = selected.slice(0, 6);
        if (!teams.length || (!spec.teams && !spec.match)) return { facts: { error: "Say which team(s) to list match by match." } };
        const valueCols = cols.filter((c) => c.kind !== "special");
        const rows = [];
        teams.forEach((team) => {
            (ctx.stats.get(team).perMatch || []).forEach((p) => {
                const vals = valueCols.map((c) => matchValue(c, p));
                if (vals.every((v) => v === null)) return;
                rows.push({ team, match: p.matchNumber ? `Q${p.matchNumber}` : "?", num: p.matchNumber || 0, ...Object.fromEntries(valueCols.map((c, i) => [c.label, vals[i]])) });
            });
        });
        rows.sort((a, b) => a.num - b.num || a.team - b.team);
        const shown = limit ? rows.slice(0, limit) : rows;
        const clean = { ...spec, columns: valueCols.map((c) => c.label), rows: "matches" };
        return {
            facts: {
                title: spec.title || `${valueCols.map((c) => c.label).join(", ")} by match - ${teams.join(", ")}`,
                rows_are: "one row per team per match", row_count: shown.length, columns: valueCols.map((c) => c.label),
                unknown_columns: unknownNote(unknown, ctx),
                highlights: highlights(shown, valueCols, (r) => `${r.team} ${r.match}`),
                rows: shown.map(({ num, ...r }) => r)
            },
            table: {
                columns: [t("ai.col.match", "Match"), t("ai.col.team", "Team"), ...valueCols.map((c) => c.label)],
                rows: shown.map((r) => [r.match, Data.teamLabel(ctx, r.team), ...valueCols.map((c) => fmtCell(r[c.label]))])
            },
            spec: { tool: "make_table", args: compact(clean) },
            cols: valueCols, teamsShown: teams
        };
    }

    const keepOrder = Array.isArray(spec.teams) && spec.teams.length && !spec.sort_by;
    let teams = sortTeams(ctx, selected, sortCol, order, keepOrder);
    const considered = teams.length;
    if (limit) teams = teams.slice(0, limit);
    const rows = teams.map((team) => ({ team, ...Object.fromEntries(cols.map((c) => [c.label, columnValue(ctx, c, team)])) }));
    const title = spec.title || tableTitle(cols, scope, limit, sortCol, order);
    const clean = {
        ...spec, columns: cols.map((c) => c.label), sort_by: sortCol && sortCol.explicit ? sortCol.label : undefined,
        order: spec.order, limit: limit || undefined
    };
    delete clean.metrics;
    delete clean.metric;
    delete clean.n;
    return {
        facts: {
            title, rows_are: "one row per team", row_count: rows.length, teams_matching: considered, teams_at_event: ctx.stats.size,
            filters: conditions.length ? conditions : undefined, scope,
            sorted_by: sortCol ? `${sortCol.label} (${order === "asc" ? "lowest first" : "highest first"})` : undefined,
            columns: cols.map((c) => c.label), unknown_columns: unknownNote(unknown, ctx),
            highlights: highlights(rows, cols.filter((c) => !(c.special && c.special.text))),
            rows: rows.map((r) => ({ ...r, name: Data.teamName(ctx, r.team) || undefined }))
        },
        table: {
            columns: ["#", t("ai.col.team", "Team"), ...cols.map((c) => c.label)],
            rows: rows.map((r, i) => [i + 1, Data.teamLabel(ctx, r.team), ...cols.map((c) => fmtCell(r[c.label]))])
        },
        spec: { tool: "make_table", args: compact(clean) },
        cols, teamsShown: teams
    };
}

function markdownTable(title, table) {
    const esc = (v) => String(v ?? "").replace(/\|/g, "/");
    return [`# ${title}`, "", `| ${table.columns.map(esc).join(" | ")} |`, `| ${table.columns.map(() => "---").join(" | ")} |`,
        ...table.rows.map((r) => `| ${r.map(esc).join(" | ")} |`)].join("\n");
}

// ------------------------------------------------------------------ make_chart

const CHART_TYPES = [
    ["stackedBar", /\bstack(?:ed)?\b/i], ["scatter", /\bscatter|correlat/i], ["radar", /\bradar|spider|polar\b/i],
    ["box", /\bbox(?:\s*plot)?\b|\bwhisker/i], ["pie", /\bpie\b|\bdonut\b|\bdoughnut\b/i], ["line", /\bline\b|\btrend/i],
    ["bar", /\bbar\b|\bcolumn chart\b|\bhistogram\b/i]
];

export function chartTypeIn(text) {
    const hit = CHART_TYPES.find(([, re]) => re.test(String(text || "")));
    return hit ? hit[0] : null;
}

function normalizeType(type) {
    const s = String(type || "").toLowerCase().replace(/[\s_-]/g, "");
    return { stackedbar: "stackedBar", stacked: "stackedBar", groupedbar: "groupedBar", grouped: "groupedBar", bar: "bar", column: "bar",
        line: "line", trend: "line", scatter: "scatter", radar: "radar", spider: "radar", box: "box", boxplot: "box", pie: "pie", donut: "pie" }[s] || null;
}

function buildChart(ctx, args) {
    const spec = clone(args);
    let type = normalizeType(spec.type) || (spec.by === "match" ? "line" : null);
    let { cols, unknown } = resolveColumns(ctx, spec.metrics || spec.columns || spec.metric || spec.y);
    cols = cols.filter((c) => !(c.special && c.special.text));
    let xCol = spec.x || spec.metric_x ? resolveColumn(ctx, spec.x || spec.metric_x) : null;
    if (!cols.length && unknown.length) return { facts: { error: unknownNote(unknown, ctx) } };
    if (!type) type = cols.length > 1 ? "groupedBar" : "bar";
    if (type === "bar" && cols.length > 1) type = "groupedBar";
    if (!cols.length) {
        cols = type === "stackedBar" || type === "radar" ? phaseColumns(ctx) : [metricColumn(ctx.metrics.find((m) => m.id === "score_total") || ctx.metrics[0], "avg")];
    }
    if (type === "scatter") {
        if (!xCol) xCol = cols.length > 1 ? cols.shift() : null;
        if (!xCol) return { facts: { error: "A scatter plot needs two metrics (x and y)." } };
    }
    const order = spec.order === "asc" ? "asc" : "desc";
    const sortCol = spec.sort_by ? Object.assign(resolveColumn(ctx, spec.sort_by) || {}, { explicit: true }) : cols[0];
    const { teams: selected, scope, conditions } = selectTeams(ctx, spec);
    const keepOrder = Array.isArray(spec.teams) && spec.teams.length && !spec.sort_by;
    const defaults = { line: 5, radar: 4, box: 8, pie: 10 };
    const byMatch = spec.by === "match" || (type === "line" && spec.by !== "team");
    const limitRaw = spec.limit || spec.n || (keepOrder ? null : defaults[byMatch ? "line" : type]);
    let teams = sortTeams(ctx, selected, sortCol && sortCol.key ? sortCol : cols[0], order, keepOrder);
    if (limitRaw) teams = teams.slice(0, Math.max(1, parseInt(limitRaw, 10)));
    if (!teams.length) return { facts: { error: "No teams match that request.", filters: conditions } };

    const label = (team) => String(team);
    if (type === "pie") cols = cols.slice(0, 1);
    const which = limitRaw && !keepOrder && sortCol && sortCol.label ? `${order === "asc" ? "bottom" : "top"} ${teams.length} by ${sortCol.label}` : scope;
    const title = spec.title || `${type === "scatter" ? `${xCol.label} vs ${cols[0].label}` : cols.map((c) => c.label).join(", ")} - ${byMatch ? "by match" : which}`;
    let chart;
    let table;
    let rows;
    const clean = { ...spec, type, metrics: cols.map((c) => c.label), x: xCol ? xCol.label : undefined, by: byMatch ? "match" : undefined };
    delete clean.columns;
    delete clean.metric;
    delete clean.y;
    delete clean.metric_x;

    if (byMatch) {
        const seriesCols = teams.length === 1 ? cols : [cols[0]];
        const matchNums = new Set();
        teams.forEach((team) => (ctx.stats.get(team).perMatch || []).forEach((p) => { if (p.matchNumber && seriesCols.some((c) => matchValue(c, p) !== null)) matchNums.add(p.matchNumber); }));
        const xs = Array.from(matchNums).sort((a, b) => a - b);
        const valueAt = (team, col, num) => {
            const p = (ctx.stats.get(team).perMatch || []).find((x) => x.matchNumber === num);
            return p ? matchValue(col, p) : null;
        };
        const series = teams.length === 1
            ? seriesCols.map((c) => ({ name: c.label, y: xs.map((n) => valueAt(teams[0], c, n)) }))
            : teams.map((team) => ({ name: label(team), y: xs.map((n) => valueAt(team, seriesCols[0], n)) }));
        chart = { type: type === "bar" || type === "groupedBar" ? "groupedBar" : "line", title, x: xs.map((n) => `Q${n}`), xTitle: t("ai.chart.match", "Match"), yTitle: seriesCols.length === 1 ? seriesCols[0].label : "", series };
        rows = [];
        teams.forEach((team) => xs.forEach((n) => {
            const vals = seriesCols.map((c) => valueAt(team, c, n));
            if (vals.some((v) => v !== null)) rows.push({ team, match: `Q${n}`, ...Object.fromEntries(seriesCols.map((c, i) => [c.label, vals[i]])) });
        }));
        table = {
            columns: [t("ai.col.match", "Match"), t("ai.col.team", "Team"), ...seriesCols.map((c) => c.label)],
            rows: rows.map((r) => [r.match, Data.teamLabel(ctx, r.team), ...seriesCols.map((c) => fmtCell(r[c.label]))])
        };
        return finishChart(ctx, { chart, table, rows, cols: seriesCols, keyOf: (r) => `${r.team} ${r.match}`, clean, type: chart.type, scope, conditions, unknown });
    }

    const allCols = xCol ? [xCol, ...cols] : cols;
    rows = teams.map((team) => ({ team, ...Object.fromEntries(allCols.map((c) => [c.label, columnValue(ctx, c, team)])) }));
    table = {
        columns: ["#", t("ai.col.team", "Team"), ...allCols.map((c) => c.label)],
        rows: rows.map((r, i) => [i + 1, Data.teamLabel(ctx, r.team), ...allCols.map((c) => fmtCell(r[c.label]))])
    };
    const ys = (col) => rows.map((r) => r[col.label]);
    switch (type) {
        case "scatter": {
            const pts = rows.filter((r) => typeof r[xCol.label] === "number" && typeof r[cols[0].label] === "number");
            chart = { type: "scatter", title, xTitle: xCol.label, yTitle: cols[0].label, highlight: ctx.ourTeam || undefined,
                points: pts.map((r) => ({ x: r[xCol.label], y: r[cols[0].label], team: r.team, name: label(r.team) })) };
            break;
        }
        case "radar": {
            const maxOf = (col) => Math.max(0, ...Array.from(ctx.stats.keys()).map((tm) => columnValue(ctx, col, tm)).filter((v) => typeof v === "number"));
            const maxes = cols.map(maxOf);
            chart = { type: "radar", title, theta: cols.map((c) => c.label),
                series: rows.map((r) => ({ name: label(r.team), r: cols.map((c, i) => (typeof r[c.label] === "number" && maxes[i] > 0 ? r1(Math.max(0, r[c.label]) / maxes[i] * 100) : 0)) })) };
            break;
        }
        case "box":
            chart = { type: "box", title, yTitle: cols[0].label,
                series: teams.map((team) => ({ name: label(team), y: (ctx.stats.get(team).perMatch || []).map((p) => matchValue(cols[0], p)).filter((v) => v !== null) })) };
            break;
        case "pie":
            chart = { type: "pie", title, labels: rows.filter((r) => r[cols[0].label] > 0).map((r) => label(r.team)), values: rows.filter((r) => r[cols[0].label] > 0).map((r) => r[cols[0].label]) };
            break;
        case "stackedBar":
            chart = { type: "stackedBar", title, x: rows.map((r) => label(r.team)), xTitle: t("ai.col.team", "Team"), yTitle: t("ai.chart.points", "Points"),
                series: cols.map((c) => ({ name: c.label, y: ys(c).map((v) => (typeof v === "number" ? v : 0)) })) };
            break;
        case "line":
            chart = { type: "line", title, x: rows.map((r) => label(r.team)), xTitle: t("ai.col.team", "Team"), series: cols.map((c) => ({ name: c.label, y: ys(c) })) };
            break;
        default:
            chart = { type: cols.length > 1 ? "groupedBar" : "bar", title, x: rows.map((r) => label(r.team)), xTitle: t("ai.col.team", "Team"),
                yTitle: cols.length === 1 ? cols[0].label : "", series: cols.map((c) => ({ name: c.label, y: ys(c) })) };
    }
    return finishChart(ctx, { chart, table, rows, cols: allCols, keyOf: (r) => r.team, clean, type: chart.type, scope, conditions, unknown,
        note: type === "radar" ? "Radar values are each team's average as a % of the best team at the event on that metric." : undefined });
}

function finishChart(ctx, { chart, table, rows, cols, keyOf, clean, type, scope, conditions, unknown, note }) {
    return {
        facts: {
            chart_type: type, title: chart.title, scope, filters: conditions.length ? conditions : undefined,
            metrics: cols.map((c) => c.label), teams_shown: Array.from(new Set(rows.map((r) => r.team))).length,
            unknown_metrics: unknownNote(unknown, ctx), note,
            highlights: highlights(rows, cols, keyOf), rows
        },
        chart, table,
        spec: { tool: "make_chart", args: compact(clean) }
    };
}

// ------------------------------------------------------------------ tools

export const BUILDER_TOOLS = {
    make_table: {
        description: "Build ANY custom table: choose teams (list, all, a match, filters), columns (any metrics, max/min/stdev, matches scouted/played, official rank, record, or arithmetic like 'EPA - xP'), sorting and a row limit. Also edits an earlier table (pass the full changed spec).",
        params: {
            columns: "list of columns, e.g. [\"EPA\", \"Total points\", \"max Auto points\", \"EPA - xP\", \"matches scouted\"]",
            teams: "team numbers (omit for all teams)", exclude: "team numbers to leave out", match: "a match number to use its six teams",
            conditions: "filters [{column, op (> >= < <= = !=), value}]", sort_by: "column to sort by", order: "desc (default) or asc",
            limit: "max rows", rows: "'matches' for one row per team per match", chart: "true or a chart type to also draw it",
            document: "true to also create a document", title: "optional title"
        },
        async run(ctx, args) {
            const out = buildTable(ctx, args);
            if (out.facts.error) return out;
            const result = { facts: out.facts, table: out.table, spec: out.spec };
            if (args.chart) {
                const valueCols = out.cols.filter((c) => !(c.special && c.special.text)).slice(0, 4);
                const chart = buildChart(ctx, {
                    type: typeof args.chart === "string" ? args.chart : undefined, metrics: valueCols.map((c) => c.label),
                    teams: out.teamsShown, by: args.rows === "matches" ? "match" : undefined, title: out.facts.title
                });
                if (chart.chart) result.chart = chart.chart;
            }
            if (args.document) {
                result.artifact = { id: `table-${Date.now()}`, title: out.facts.title, type: "worksheet", summary: `${out.facts.row_count} rows: ${out.facts.columns.join(", ")}`, markdown: markdownTable(out.facts.title, out.table) };
            }
            return result;
        }
    },

    make_chart: {
        description: "Draw ANY chart: bar, grouped bar, stacked bar, line (by match or across teams), scatter, radar, box plot or pie, for any metrics (including arithmetic like 'EPA - xP') and any teams, filters, sorting and limit. Also edits an earlier chart.",
        params: {
            type: "bar, stackedBar, line, scatter, radar, box or pie", metrics: "metrics to plot (y values; for stackedBar the stacked parts)",
            x: "x metric for scatter", by: "'match' for match-by-match values", teams: "team numbers (omit for all / top teams)",
            exclude: "team numbers to leave out", match: "a match number to use its six teams", conditions: "filters [{column, op, value}]",
            sort_by: "metric to sort / pick top teams by", order: "desc or asc", limit: "max teams", title: "optional title"
        },
        async run(ctx, args) {
            const out = buildChart(ctx, args);
            return out.facts.error ? out : { facts: out.facts, chart: out.chart, table: out.table, spec: out.spec };
        }
    }
};

// ------------------------------------------------------------------ keyword drafts for new requests

const KNOWN_WORDS = new Set(("a an the of for with and or to me my show make create build give generate put display list table tables chart charts graph graphs plot plots "
    + "visualize visualise visualization all every each teams team their them it its in on at by sorted sort order ordered rank ranked ranking top best worst bottom first last "
    + "highest lowest ascending descending data columns column include including please can could would you i want need see comparing compare compared "
    + "versus vs between difference minus plus ratio times divided per match matches event this that these those our we us only just except excluding without but not "
    + "over under above below more less than least most greater fewer is are which who what how bar line pie scatter radar box stacked spider new side numbers values "
    + "stats statistics metrics metric like also as from into of everyone everybody here there now then so do does get got one ones grouped group whole entire full "
    + "average averages avg mean max min maximum minimum std dev stdev each every scouted played scouting artifact document doc spreadsheet matrix up down rows row "
    + "next to vs. against compare along alongside plus including both how many showing shown having where whose").split(/\s+/));

/** Operator words between two named metrics, and "difference between A and B" / "ratio of A to B" phrasing. */
function expressionsIn(ctx, question) {
    const { spans, text } = findMetricsInText(ctx, question);
    const out = [];
    for (let i = 0; i + 1 < spans.length; i++) {
        const a = spans[i];
        const b = spans[i + 1];
        const between = text.slice(a.end, b.start).trim();
        const before = text.slice(Math.max(0, a.start - 30), a.start);
        let op = null;
        if (/^(?:minus|-|less)$/.test(between) || (/^(?:and|vs|versus)$/.test(between) && /\b(?:difference|gap|delta)\s+(?:between\s+)?(?:the\s+)?$/.test(before))) op = "-";
        else if (/^(?:plus|\+)$/.test(between)) op = "+";
        else if (/^(?:times|x|\*)$/.test(between)) op = "*";
        else if (/^(?:divided by|over|per|\/)$/.test(between) || (/^to$/.test(between) && /\bratio\s+of\s+(?:the\s+)?$/.test(before))) op = "/";
        if (op) out.push(`${a.metric.label} ${op} ${b.metric.label}`);
    }
    // Literal arithmetic the user typed ("EPA - xP", "(auto + teleop) / 2").
    const literal = String(question).match(/[\w%() .]+(?:\s-\s|[+*/])[\w%() .]+/g) || [];
    literal.forEach((piece) => {
        const col = resolveExpression(ctx, piece.trim());
        if (col && !out.includes(col.label)) out.push(col.label);
    });
    return out;
}

function conditionsIn(ctx, question) {
    const re = /([\p{L}%][\p{L}\p{N}%() .-]{0,40}?)\s*(>=|<=|!=|>|<|=|at least|at most|over|above|more than|greater than|higher than|under|below|less than|lower than|fewer than|equal to|equals)\s*(-?\d+(?:\.\d+)?)/giu;
    const ops = { "at least": ">=", "at most": "<=", over: ">", above: ">", "more than": ">", "greater than": ">", "higher than": ">", under: "<", below: "<", "less than": "<", "lower than": "<", "fewer than": "<", "equal to": "=", equals: "=" };
    const out = [];
    let m;
    while ((m = re.exec(question))) {
        const found = findMetricsInText(ctx, m[1]).metrics;
        const special = SPECIALS.find((s) => s.re.test(m[1]));
        const col = found.length ? found[found.length - 1].label : (special ? special.label : null);
        if (col) out.push({ column: col, op: ops[m[2].toLowerCase()] || m[2], value: Number(m[3]), text: m[0] });
    }
    return out;
}

/**
 * Keyword draft of a make_table / make_chart call for a new request. `confident` is false when the question has
 * words the draft does not account for, so a capable model can refine it.
 */
export function specFromQuestion(question, ctx, { chart = false } = {}) {
    const q = String(question || "");
    const lower = q.toLowerCase();
    const type = chartTypeIn(lower);
    const wantsChart = chart || !!type || /\b(?:graph|chart|plot|visuali[sz]e)\b/i.test(lower);
    const byMatch = /\b(?:per match|by match|each match|every match|match by match|match-by-match|over time|over the event)\b/i.test(lower);
    const used = new Set();
    const matchRef = lower.match(/\b(?:match|qm|q)\s*#?\s*(\d{1,3})\b/);
    if (matchRef) used.add(Number(matchRef[1]));
    const limitRef = lower.match(/\b(?:top|best|first|bottom|worst|last|lowest|highest)\s+(\d{1,3})\b/);
    if (limitRef) used.add(Number(limitRef[1]));
    const conditions = conditionsIn(ctx, q);
    conditions.forEach((c) => used.add(c.value));
    const nums = (lower.match(/\b\d{1,5}\b/g) || []).map(Number);
    const exclude = [];
    const exRe = /\b(?:except|excluding|without|not|but not|minus)\s+((?:(?:team\s+)?\d{1,5}(?:\s*(?:,|and|&)\s*)?)+)/gi;
    let ex;
    while ((ex = exRe.exec(lower))) (ex[1].match(/\d{1,5}/g) || []).map(Number).filter((n) => ctx.stats.has(n)).forEach((n) => { exclude.push(n); used.add(n); });
    const teams = Array.from(new Set(nums.filter((n) => ctx.stats.has(n) && !used.has(n))));

    const { metrics } = findMetricsInText(ctx, q);
    const exprs = expressionsIn(ctx, q);
    const columns = [];
    SPECIALS.forEach((s) => { if (s.re.test(lower)) columns.push(s.label); });
    const found = findMetricsInText(ctx, q);
    found.spans.forEach((span) => {
        const stat = found.text.slice(Math.max(0, span.start - 26), span.start).match(/\b(max(?:imum)?|min(?:imum)?|peak|std ?dev|stdev|standard deviation)\s+(?:of\s+)?(?:the\s+)?$/);
        const col = stat ? resolveColumn(ctx, `${stat[1]} ${span.metric.label}`) : null;
        const label = col ? col.label : span.metric.label;
        if (!columns.includes(label)) columns.push(label);
    });
    exprs.forEach((e) => columns.push(e));
    const sortRef = q.match(/\b(?:sort(?:ed)?|order(?:ed)?|rank(?:ed)?)\s+(?:them\s+|it\s+)?by\s+([^,.;!?]+)/i);
    const sortCol = sortRef ? resolveColumn(ctx, (findMetricsInText(ctx, sortRef[1]).metrics[0] || {}).label || sortRef[1]) : null;
    const order = /\b(?:worst|lowest|bottom|least|ascending|smallest)\b/i.test(lower) ? "asc" : undefined;

    // Confidence: every remaining word is accounted for.
    let rest = ` ${norm(q)} `;
    ctx.metrics.forEach((m) => m.aliases.forEach((a) => { const n = norm(a); if (n) rest = rest.split(` ${n} `).join(" "); }));
    SPECIALS.forEach((s) => { rest = rest.replace(new RegExp(s.re.source, "gi"), " "); });
    // Asked-for external metrics this event has no data for still count as understood; the table says they are missing.
    EXTERNAL_NAMES.forEach((e) => {
        if (!e.re.test(lower) || ctx.metrics.some((m) => m.id === e.id)) return;
        if (!columns.includes(e.label)) columns.push(e.label);
        rest = rest.replace(new RegExp(e.re.source, "gi"), " ");
    });
    const leftover = rest.split(" ").filter((w) => w && !KNOWN_WORDS.has(w) && !/^\d+(?:\.\d+)?$/.test(w) && !/^(?:qm|q|team|frc)\d*$/.test(w));

    const tool = wantsChart ? "make_chart" : "make_table";
    const args = { teams: teams.length ? teams : undefined, exclude: exclude.length ? exclude : undefined, match: matchRef && !teams.length ? matchRef[1] : undefined,
        conditions: conditions.length ? conditions.map(({ text, ...c }) => c) : undefined, sort_by: sortCol ? sortCol.label : undefined, order,
        limit: limitRef ? Number(limitRef[1]) : undefined };
    if (tool === "make_chart") {
        Object.assign(args, { type: type || undefined, metrics: columns.length ? columns : undefined, by: byMatch ? "match" : undefined });
    } else {
        Object.assign(args, { columns: columns.length ? columns : undefined, rows: byMatch && teams.length ? "matches" : undefined,
            document: /\b(?:artifact|document|doc|export)\b/i.test(lower) || undefined });
    }
    Object.keys(args).forEach((k) => args[k] === undefined && delete args[k]);
    return { tool, args, confident: leftover.length === 0, leftover };
}

// ------------------------------------------------------------------ follow-up edits

const EDIT_START = /^\s*(?:(?:ok(?:ay)?|now|and|also|then|please|pls|can you|could you|would you|can u|actually|but|instead)[\s,]+)*(?:remove|drop|delete|hide|exclude|take out|get rid of|leave out|add|include|put|insert|append|sort|order|rank|reverse|flip|only|just|limit|filter|keep|show only|make it|make that|turn it|turn that|change|switch|use|swap|replace|rename|title|call it|transpose|same|without|plus|with only)\b/i;
const EDIT_REFERENCE = /\b(?:that|this|the|same|previous|last|above)\s+(?:table|chart|graph|plot|list|one|data|columns?)\b|\b(?:it|them|that)\s+(?:as|into|by)\b|\bas an?\s+(?:bar|line|pie|scatter|radar|box|stacked|table|chart|graph)\b|\binstead\b/i;

export function isEditRequest(question) {
    return EDIT_START.test(String(question || "")) || EDIT_REFERENCE.test(String(question || ""));
}

/** Converts an earlier reply's table into an editable make_table spec (for tools that did not return one). */
export function specFromDisplay(display, ctx) {
    if (!display) return null;
    if (display.spec && display.spec.tool) return clone(display.spec);
    const table = display.table;
    if (!table || !table.rows || !table.rows.length) return null;
    const teamIdx = (table.columns || []).findIndex((c) => /^(?:team|equipo|takım|קבוצה)$/i.test(String(c).trim()));
    if (teamIdx < 0) return null;
    const columns = [];
    table.columns.forEach((c, i) => {
        if (i === teamIdx || /^(?:#|n|max|min|std dev|rank|match|alliance)$/i.test(String(c).trim())) return;
        const col = resolveColumn(ctx, c);
        if (col && !columns.includes(col.label)) columns.push(col.label);
    });
    if (!columns.length) return null;
    const teams = table.rows.map((r) => parseInt(String(r[teamIdx]), 10)).filter((n) => ctx.stats.has(n));
    return { tool: "make_table", args: { columns, teams: teams.length >= ctx.stats.size ? undefined : teams } };
}

/** The most recent visual in the conversation as an editable spec, or null. */
export function lastVisualSpec(history, ctx) {
    for (let i = (history || []).length - 1; i >= 0; i--) {
        const h = history[i];
        if (!h || h.role !== "assistant" || !Array.isArray(h.display)) continue;
        for (let j = h.display.length - 1; j >= 0; j--) {
            const spec = specFromDisplay(h.display[j], ctx);
            if (spec) return spec;
        }
    }
    return null;
}

function itemsIn(ctx, phrase) {
    const teams = (String(phrase).match(/\b\d{1,5}\b/g) || []).map(Number).filter((n) => ctx.stats.has(n));
    const cols = [];
    SPECIALS.forEach((s) => { if (s.re.test(phrase)) cols.push(s.label); });
    expressionsIn(ctx, phrase).forEach((e) => cols.push(e));
    findMetricsInText(ctx, phrase).metrics.forEach((m) => cols.push(m.label));
    const statRef = String(phrase).match(/\b(max(?:imum)?|min(?:imum)?|std ?dev|stdev|highest|lowest)\s+(?:of\s+)?([\p{L}\s]+)/iu);
    if (statRef) {
        const col = resolveColumn(ctx, `${statRef[1]} ${statRef[2]}`);
        if (col && col.kind === "metric" && col.stat !== "avg") {
            const idx = cols.indexOf(col.metric.label);
            if (idx >= 0) cols.splice(idx, 1);
            cols.push(col.label);
        }
    }
    return { teams, cols };
}

const CLAUSE_END = "(?=\\s*(?:,\\s*(?:and\\s+|then\\s+)?(?:add|remove|drop|delete|hide|sort|order|only|just|show|make|limit|include|keep|put)\\b|;|\\.(?!\\d)|!|\\?|$|\\bthen\\b|\\band (?:then |also )?(?:add|remove|drop|sort|only|show|make|limit|include|keep)\\b|\\bbut\\b))";

/**
 * Applies a follow-up to the previous spec in code: remove / add columns or teams, keep only some, sort, top N,
 * switch between table and chart types, by-match rows, title. Returns {tool, args, changed, confident}.
 */
export function editSpec(previous, question, ctx) {
    let tool = previous.tool === "make_chart" ? "make_chart" : "make_table";
    const args = clone(previous.args);
    const q = String(question || "");
    const lower = q.toLowerCase();
    let changed = false;
    const field = () => (tool === "make_chart" ? "metrics" : "columns");
    const list = () => {
        const key = field();
        if (!Array.isArray(args[key]) || !args[key].length) args[key] = (args.columns || args.metrics || defaultColumns(ctx).map((c) => c.label)).slice();
        return args[key];
    };
    const hasCol = (arr, label) => arr.some((c) => sameColumn(resolveColumn(ctx, c), resolveColumn(ctx, label)));
    const dropCol = (arr, label) => arr.filter((c) => !sameColumn(resolveColumn(ctx, c), resolveColumn(ctx, label)));
    const accounted = [];
    const clause = (re) => {
        const out = [];
        const full = new RegExp(re.source + "\\s+(.+?)" + CLAUSE_END, "gi");
        let m;
        while ((m = full.exec(q))) { out.push(m[1]); accounted.push(m[0]); }
        return out;
    };

    // Table <-> chart and chart type.
    const type = chartTypeIn(lower);
    if ((type || /\b(?:graph|chart|plot)\b/.test(lower)) && /\b(?:as|into|make it|turn it|make that|turn that|switch to|change to|graph it|chart it|plot it|show it|draw)\b/.test(lower)) {
        const cols = (args.columns || args.metrics || []).filter((c) => { const r = resolveColumn(ctx, c); return r && !(r.special && r.special.text); });
        tool = "make_chart";
        args.type = type || "bar";
        const named = itemsIn(ctx, q).cols;
        args.metrics = named.length ? named : cols.slice(0, type === "stackedBar" || type === "radar" ? 8 : 4);
        if (args.rows === "matches") { args.by = "match"; delete args.rows; }
        delete args.columns;
        delete args.document;
        changed = true;
    } else if (/\b(?:as|into|back to|make it|turn it|switch to)\s+a?\s*table\b/.test(lower) && tool === "make_chart") {
        tool = "make_table";
        args.columns = args.metrics || [];
        if (args.by === "match") { args.rows = "matches"; delete args.by; }
        ["metrics", "type", "x", "by"].forEach((k) => delete args[k]);
        changed = true;
    }

    // Replace ("use xP instead of EPA", "replace EPA with xP", "swap EPA for xP").
    const replace = q.match(/\b(?:replace|swap)\s+(.+?)\s+(?:with|for)\s+(.+?)(?=[,.;!?]|$)/i) || q.match(/\b(?:use|show|switch to)\s+(.+?)\s+instead\s+of\s+(.+?)(?=[,.;!?]|$)/i);
    if (replace) {
        const [from, to] = /instead/i.test(replace[0]) ? [replace[2], replace[1]] : [replace[1], replace[2]];
        const a = itemsIn(ctx, from).cols[0];
        const b = itemsIn(ctx, to).cols[0];
        if (a && b) {
            const arr = list().map((c) => (sameColumn(resolveColumn(ctx, c), resolveColumn(ctx, a)) ? b : c));
            const before = list();
            args[field()] = hasCol(before, b) ? dropCol(before, a) : arr;
            if (args.sort_by && sameColumn(resolveColumn(ctx, args.sort_by), resolveColumn(ctx, a))) args.sort_by = b;
            accounted.push(replace[0]);
            changed = true;
        }
    } else if (/\binstead\b/i.test(q)) {
        const { cols } = itemsIn(ctx, q);
        if (cols.length) {
            const arr = list();
            const valueCols = arr.filter((c) => { const r = resolveColumn(ctx, c); return r && r.kind !== "special"; });
            if (valueCols.length === 1) args[field()] = arr.map((c) => (c === valueCols[0] ? cols[0] : c));
            else if (!hasCol(arr, cols[0])) args[field()] = [...arr, cols[0]];
            if (args.sort_by || valueCols.length !== 1) args.sort_by = cols[0];
            accounted.push(q);
            changed = true;
        }
    }

    // Removals.
    clause(/\b(?:remove|drop|delete|hide|exclude|take out|get rid of|leave out|without|no more)/).forEach((phrase) => {
        const { teams, cols } = itemsIn(ctx, phrase);
        cols.forEach((c) => { if (hasCol(list(), c)) { args[field()] = dropCol(list(), c); changed = true; } });
        if (teams.length) {
            if (Array.isArray(args.teams) && args.teams.length) args.teams = args.teams.filter((x) => !teams.includes(Number(x)));
            else args.exclude = Array.from(new Set([...(args.exclude || []), ...teams]));
            changed = true;
        }
    });

    // "only top 10", "just 254 and 1678", "only EPA and xP", "only teams with EPA over 50".
    clause(/\b(?:only|just|keep only|show only|with only|limit (?:it |this )?to)/).forEach((phrase) => {
        // "just remove OPR" / "only sort by xP" are other edits, not "keep only".
        if (/^\s*(?:remove|drop|delete|hide|exclude|take out|add|include|insert|sort|order|rank|reverse|flip|make|turn|change|switch|use|swap|replace|rename)\b/i.test(phrase)) return;
        const limit = phrase.match(/\b(?:top|best|first|bottom|worst|last)?\s*(\d{1,3})\s*(?:teams|rows)?\b/i);
        const conds = conditionsIn(ctx, phrase);
        const { teams, cols } = itemsIn(ctx, phrase);
        if (conds.length) {
            args.conditions = [...(args.conditions || []), ...conds.map(({ text, ...c }) => c)];
            changed = true;
        } else if (teams.length) {
            args.teams = teams;
            delete args.exclude;
            changed = true;
        } else if (limit && /\b(?:top|best|first|bottom|worst|last)\b|\b\d{1,3}\s*(?:teams|rows)\b/i.test(phrase)) {
            args.limit = Number(limit[1]);
            if (/\b(?:bottom|worst|last)\b/i.test(phrase)) args.order = "asc";
            changed = true;
        }
        if (cols.length && !conds.length) { args[field()] = cols; changed = true; }
    });

    // Additions.
    clause(/\b(?:add|include|put in|insert|append|also show|and also)/).forEach((phrase) => {
        const { teams, cols } = itemsIn(ctx, phrase);
        cols.forEach((c) => { if (!hasCol(list(), c)) { args[field()] = [...list(), c]; changed = true; } });
        if (teams.length) {
            if (Array.isArray(args.exclude)) args.exclude = args.exclude.filter((x) => !teams.includes(Number(x)));
            if (Array.isArray(args.teams) && args.teams.length) args.teams = Array.from(new Set([...args.teams.map(Number), ...teams]));
            changed = true;
        }
    });

    // Sorting and order.
    const sortRef = q.match(/\b(?:sort|order|rank|re-?sort)(?:ed)?\s+(?:it\s+|them\s+|this\s+|that\s+|the table\s+)?(?:by|on)\s+([^,.;!?]+)/i);
    if (sortRef) {
        const { cols } = itemsIn(ctx, sortRef[1]);
        if (cols[0]) {
            args.sort_by = cols[0];
            if (!hasCol(list(), cols[0])) args[field()] = [...list(), cols[0]];
            args.order = /\b(?:asc|ascending|lowest|smallest|worst|low to high)\b/i.test(sortRef[1]) ? "asc" : undefined;
            accounted.push(sortRef[0]);
            changed = true;
        }
    }
    if (/\b(?:reverse|flip)(?:\s+(?:it|the order|order))?\b|\b(?:ascending|lowest first|smallest first|worst first|low to high)\b/i.test(q)) {
        args.order = /\b(?:reverse|flip)\b/i.test(q) && args.order === "asc" ? undefined : "asc";
        changed = true;
    } else if (/\b(?:descending|highest first|best first|high to low)\b/i.test(q)) {
        args.order = undefined;
        changed = true;
    }
    const top = q.match(/\b(top|best|first|bottom|worst|last)\s+(\d{1,3})\b/i);
    if (top && !args.limit) {
        args.limit = Number(top[2]);
        if (/bottom|worst|last/i.test(top[1])) args.order = "asc";
        changed = true;
    }
    if (/\b(?:all|every)\s+teams?\b|\beveryone\b/i.test(q) && (args.limit || args.teams || args.match)) {
        delete args.limit;
        delete args.teams;
        delete args.match;
        changed = true;
    }
    if (/\b(?:per match|by match|each match|match by match|match-by-match)\b/i.test(q)) {
        if (tool === "make_chart") args.by = "match"; else args.rows = "matches";
        changed = true;
    }
    const title = q.match(/\b(?:title(?:d)?|call it|name it|rename it(?:\s+to)?)\s*:?\s*["“']?(.+?)["”']?\s*$/i);
    if (title) { args.title = title[1]; changed = true; accounted.push(title[0]); }
    else if (changed) delete args.title;

    Object.keys(args).forEach((k) => (args[k] === undefined || (Array.isArray(args[k]) && !args[k].length && k !== "columns" && k !== "metrics")) && delete args[k]);

    // Confidence: the words left after removing what was understood are all known filler.
    let rest = q;
    accounted.forEach((a) => { rest = rest.replace(a, " "); });
    const leftover = norm(rest).split(" ").filter((w) => w && !KNOWN_WORDS.has(w) && !EDIT_WORDS.has(w) && !/^\d+$/.test(w)
        && !ctx.metrics.some((m) => m.aliases.some((a) => norm(a).split(" ").includes(w))));
    const needsTeams = (args.rows === "matches" || (tool === "make_chart" && args.by === "match")) && !args.match && !(Array.isArray(args.teams) && args.teams.length && args.teams.length <= 6);
    return { tool, args, changed, confident: changed && leftover.length === 0 && !needsTeams, leftover };
}

const EDIT_WORDS = new Set(("remove drop delete hide exclude take out get rid leave without add include put insert append sort order rank reverse flip only just limit "
    + "filter keep make turn change switch use swap replace rename title call name transpose same instead into back okay ok actually it them that this "
    + "previous last above also then please pls u column columns row rows chart graph plot table more no").split(/\s+/));
