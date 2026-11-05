/**
 * Model routing evaluation: runs canned questions through the real model and scores which tools it called, whether
 * the arguments were right, how many calls were rejected as invalid, and latency.
 *
 * In the browser, on the Assistant page with a model loaded:
 *     await ObsidianscoutAssistantEval.run()                 // default cases, the tier picked on the page
 *     await ObsidianscoutAssistantEval.run({ limit: 5 })     // first five cases only
 * The report is printed as a console table and returned. Cases name teams as {A} / {B} and a match as {M} / {R}; they
 * are filled from the loaded event (two scouted teams that played in the same match), so the set works at any event.
 * The Node tests run the same scoring against a scripted model (src/test/js/ai-assistant.test.mjs).
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

/**
 * expect: acceptable tools (first is preferred; [] = no tool should run), args: values the preferred tool's call must
 * contain (team lists compare as sets, strings case-insensitively), hard: multi-part questions.
 */
export const DEFAULT_CASES = [
    { q: "top 5 teams by auto", expect: ["top_teams", "make_table"] },
    { q: "compare {A} and {B}", expect: ["compare_teams", "make_table"], args: { teams: ["{A}", "{B}"] } },
    { q: "how good is {A}?", expect: ["team_overview"], args: { team: "{A}" } },
    { q: "what are {A}'s strengths and weaknesses?", expect: ["team_percentiles"], args: { team: "{A}" } },
    { q: "who should we pick for alliance selection?", expect: ["pick_candidates"] },
    { q: "preview match {M}", expect: ["match_preview"], args: { match: "{M}" } },
    { q: "has {A} played against {B}?", expect: ["head_to_head"], args: { teams: ["{A}", "{B}"] } },
    { q: "which teams are the most consistent in teleop?", expect: ["consistency"] },
    { q: "teams with total points over 20", expect: ["filter_teams", "make_table"] },
    { q: "hello!", expect: [] },
    { q: "compare {A} and {B} in match {M} using auto points", expect: ["make_table", "make_chart"], args: { teams: ["{A}", "{B}"], match: "{M}" }, hard: true },
    { q: "show auto and teleop for {A} and {B} over their last 3 matches", expect: ["make_table", "make_chart"], args: { teams: ["{A}", "{B}"], last: 3 }, hard: true },
    { q: "line chart of total points for {A} and {B} in matches {R}", expect: ["make_chart", "match_by_match"], args: { teams: ["{A}", "{B}"] }, hard: true },
    { q: "table of EPA minus xP for the top 8 teams by EPA", expect: ["make_table"], args: { limit: 8 }, hard: true },
    { q: "compara {A} y {B} en el partido {M} en puntos de autónomo", expect: ["make_table", "compare_teams"], args: { teams: ["{A}", "{B}"] }, hard: true, lang: "es" },
    { q: "{A} ve {B} takımlarını karşılaştır", expect: ["compare_teams", "make_table"], args: { teams: ["{A}", "{B}"] }, lang: "tr" }
];

/** {A}, {B}: two teams with scouting data that played in the same match {M}; {R}: a range of matches. */
export function fillPlaceholders(ctx) {
    const scouted = (team) => ((ctx.stats.get(team) || {}).perMatch || []).filter((p) => p.values && Object.entries(p.values).some(([k, v]) => !k.startsWith("ext:") && typeof v === "number"));
    let fill = null;
    for (const m of ctx.matches || []) {
        const six = [...(m.redTeams || []), ...(m.blueTeams || [])].map((k) => parseInt(String(k).replace(/^\D+/, ""), 10)).filter((n) => ctx.stats.has(n));
        const both = six.filter((n) => scouted(n).some((p) => p.matchNumber === m.matchNumber));
        if (both.length >= 2 && m.compLevel === "qm") { fill = { A: both[0], B: both[1], M: m.matchNumber }; break; }
    }
    if (!fill) {
        const teams = Array.from(ctx.stats.keys());
        fill = { A: teams[0], B: teams[1], M: 1 };
    }
    const lo = Math.max(1, fill.M - 3);
    return { ...fill, R: `${lo}-${lo + 6}` };
}

function substitute(value, vars) {
    if (typeof value === "string") {
        const whole = value.match(/^\{([A-Z])\}$/);
        if (whole && vars[whole[1]] !== undefined && typeof vars[whole[1]] === "number") return vars[whole[1]];
        return value.replace(/\{([A-Z])\}/g, (_, k) => (vars[k] !== undefined ? String(vars[k]) : `{${k}}`));
    }
    if (Array.isArray(value)) return value.map((v) => substitute(v, vars));
    if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, substitute(v, vars)]));
    return value;
}

/** Whether `got` contains every expected argument. */
export function argsMatch(expected, got) {
    if (!expected) return true;
    return Object.entries(expected).every(([key, want]) => {
        const have = got ? got[key] : undefined;
        if (Array.isArray(want)) {
            const set = new Set((Array.isArray(have) ? have : [have]).map((v) => String(v).toLowerCase()));
            return want.every((w) => set.has(String(w).toLowerCase()));
        }
        if (typeof want === "number") return Number(have) === want;
        return String(have ?? "").toLowerCase().replace(/^(?:qm|q|match)\s*/, "") === String(want).toLowerCase();
    });
}

/** Scores one case from the tools that ran (results) and the calls rejected as invalid. */
export function scoreCase(c, results, rejected) {
    const ran = results.map((r) => r.tool);
    const toolOk = c.expect.length ? ran.some((tool) => c.expect.includes(tool)) : ran.length === 0;
    const main = results.find((r) => r.tool === c.expect[0]) || results.find((r) => c.expect.includes(r.tool));
    // Arguments are checked on the preferred tool only (an alternative tool may name things differently).
    const argsOk = !c.args || !main ? toolOk : (main.tool !== c.expect[0] || argsMatch(c.args, main.args));
    return { toolOk, argsOk: toolOk && argsOk, ran, rejected: rejected.length };
}

/**
 * Runs the cases. answerQuestion is ai-tools.js's (passed in to keep this module free of the model runtime).
 * Returns { rows, summary }.
 */
export async function runModelEval({ answerQuestion, ctx, tier, cases = DEFAULT_CASES, limit = 0, onResult = () => {}, signal } = {}) {
    const vars = fillPlaceholders(ctx);
    const list = (limit ? cases.slice(0, limit) : cases).map((c) => ({ ...c, q: substitute(c.q, vars), args: substitute(c.args, vars) }));
    const rows = [];
    for (const c of list) {
        if (signal && signal.aborted) break;
        const rejected = [];
        const started = (typeof performance !== "undefined" ? performance : Date).now();
        let results = [];
        let error = "";
        try {
            const out = await answerQuestion({ question: c.q, ctx, tier, routeOnly: true, signal, isZachary: false,
                onEvent: (ev) => { if (ev.type === "tool_rejected") rejected.push(ev); } });
            results = out.results || [];
        } catch (err) {
            if (err && err.name === "AbortError") throw err;
            error = (err && err.message) || String(err);
        }
        const ms = Math.round((typeof performance !== "undefined" ? performance : Date).now() - started);
        const s = scoreCase(c, results, rejected);
        const row = { question: c.q, expected: c.expect.join(" | ") || "(none)", ran: s.ran.join(", ") || "(none)", tool_ok: s.toolOk, args_ok: s.argsOk, rejected: s.rejected, ms, hard: !!c.hard, error };
        rows.push(row);
        onResult(row);
    }
    const pct = (n, d) => (d ? Math.round((n / d) * 100) : 0);
    const single = rows.filter((r) => !r.hard);
    const hard = rows.filter((r) => r.hard);
    const summary = {
        cases: rows.length,
        tool_accuracy: pct(rows.filter((r) => r.tool_ok).length, rows.length),
        args_accuracy: pct(rows.filter((r) => r.args_ok).length, rows.length),
        single_step_tool_accuracy: pct(single.filter((r) => r.tool_ok).length, single.length),
        multi_part_tool_accuracy: pct(hard.filter((r) => r.tool_ok).length, hard.length),
        rejected_calls: rows.reduce((s, r) => s + r.rejected, 0),
        avg_ms: rows.length ? Math.round(rows.reduce((s, r) => s + r.ms, 0) / rows.length) : 0
    };
    return { rows, summary };
}

const api = { DEFAULT_CASES, fillPlaceholders, argsMatch, scoreCase, runModelEval };
export default api;
